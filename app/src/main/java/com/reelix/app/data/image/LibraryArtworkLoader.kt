package com.reelix.app.data.image

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.source.SourceManager
import com.reelix.app.data.douban.DoubanClient
import com.reelix.app.data.streamx.StreamxClient
import com.reelix.app.data.tvmaze.TvmazeClient
import com.reelix.app.data.tmdb.TmdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Library-only cache: no title-only global fallback, no API key stored, no modification of manual artwork. */
class LibraryArtworkLoader(context: Context) {
    private val tag = "LibraryArtwork"
    private val batch = kotlinx.coroutines.flow.MutableStateFlow(ArtworkBatchStatus())
    val batchStatus: kotlinx.coroutines.flow.StateFlow<ArtworkBatchStatus> get() = batch
    fun retryMissing() { records.entries.removeAll { it.value.poster.isBlank() } }
    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Saved(val records: Map<String, LibraryArtworkRecord> = emptyMap())
    private val records = java.util.concurrent.ConcurrentHashMap<String, LibraryArtworkRecord>()
    private var loaded = false
    /** 按槽位 id 限流，外部渠道各自独立，互不拖累。 */
    private val nextRequest = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val requestMutex = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(slot: ArtworkProviderSlot): Mutex =
        requestMutex.getOrPut(slot.id) { Mutex() }
    private val http by lazy { OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() }
    private fun store() = AtomicFile(File(context.filesDir, "library-auto-artwork.json"))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** 部分公共服务（如 TVmaze）要求可识别的 UA。 */
    private fun userAgent(): String = "Reelbox/${com.reelix.app.BuildConfig.VERSION_NAME} (Android TV)"

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        records.putAll(runCatching { store().openRead().bufferedReader().use {
            json.decodeFromString<Saved>(it.readText()).records
        } }.getOrDefault(emptyMap()))
        loaded = true
    }

    suspend fun restore(items: List<MediaItem>, apply: suspend (String, LibraryArtworkRecord) -> Unit) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val ids = items.map { it.id }.toSet()
        records.values.filter {
            it.workId in ids && (it.poster.isNotBlank() || !it.backdrop.isNullOrBlank())
        }.groupBy { it.workId }.forEach { (id, covers) ->
            covers.maxByOrNull { it.retryAfter }?.let { apply(id, it) }
        }
    }

    suspend fun load(items: List<MediaItem>, site: SourceManager.SiteEntry?, sourceConfig: String,
        apiKey: String, language: String,
        channels: List<ArtworkChannel> = emptyList(),
        order: List<String> = emptyList(),
        progress: suspend (Int, Int) -> Unit,
        apply: suspend (String, LibraryArtworkRecord) -> Unit) = withContext(Dispatchers.IO) {
        ensureLoaded()
        batch.value = ArtworkBatchStatus(running = true, total = items.size)
        var missing = 0
        try {
        val available = site?.takeIf { it.site.searchable == 1 && it.spider?.searchable == true }
        val channelsByKind = channels.associateBy { it.kind }
        // 豆瓣有 IP 级限流，绝不进入片库批量扫描。
        val batchChannels = channels.filterNot { it.kind == ArtworkChannelKind.DOUBAN.name }
        val providers = artworkProviderSlots(apiKey.isNotBlank(), available != null, batchChannels, order)
        val externalSignature = channels.filter { it.enabled }
            .joinToString(",") { "${it.id}:${it.kind}:${it.baseUrl}" }
        fun keyFor(item: MediaItem) = digest(
            "${item.id}|${item.title}|${item.year}|${item.kind}|$sourceConfig|${available?.site?.key}|" +
                "$apiKey|$language|$externalSignature",
        )
        // Restore all known posters before the first slow search. Refresh must not blank the wall.
        for (item in items) {
            records[keyFor(item)]?.takeIf { it.poster.isNotBlank() || !it.backdrop.isNullOrBlank() }
                ?.let { apply(item.id, it) }
        }
        var done = 0
        // Process the current list in order. A cancelled/new scan is restarted with its full list, never discarded.
        for (item in items) {
            coroutineContext.ensureActive()
            batch.value = ArtworkBatchStatus(true, done, items.size, missing)
            progress(done, items.size)
            // Library refresh is poster-gated: a poster means this work is already done.
            if (!item.poster.isNullOrBlank() || !item.posterRes.isNullOrBlank()) { done++; continue }
            val key = keyFor(item)
            val now = System.currentTimeMillis()
            val cached = records[key]?.normalizedForUse()
            if (cached != null && cached.retryAfter > now) {
                if (cached.poster.isNotBlank()) {
                    apply(item.id, cached)
                    done++
                    continue
                }
                if (cached.state != ArtworkTargetState.FOUND) {
                    missing++
                    done++
                    continue
                }
            }
            val resolution = resolveLibraryArtwork(item, providers, query = { slot ->
                queryProvider(slot, item.title, apiKey, language, available, channels = channelsByKind)
            }, readable = ::readable)
            coroutineContext.ensureActive()
            val base = cached ?: LibraryArtworkRecord()
            val retryAfter = when (resolution.state) {
                ArtworkTargetState.FOUND -> Long.MAX_VALUE
                ArtworkTargetState.CONFIRMED_MISSING -> now + 7 * 24 * 60 * 60_000L
                ArtworkTargetState.FAILED -> now + 15 * 60_000L
                ArtworkTargetState.UNKNOWN -> 0L
            }
            val resolved = resolution.record
            val saved = (resolved ?: base).copy(
                state = resolution.state,
                retryAfter = retryAfter,
                workId = item.id,
            )
            records[key] = saved
            // Automatic artwork is disposable; a full disk must not discard the rest of the batch.
            runCatching { persist() }
            if (saved.poster.isNotBlank() || !saved.backdrop.isNullOrBlank()) apply(item.id, saved)
            if (saved.poster.isBlank()) missing++
            done++
        }
        progress(done, items.size)
        batch.value = ArtworkBatchStatus(false, done, items.size, missing)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { batch.value = batch.value.copy(error = e.message); throw e }
        finally { batch.value = batch.value.copy(running = false) }
    }

    fun searchManualUpdates(query: String, site: SourceManager.SiteEntry?, apiKey: String,
        tmdbEnabled: Boolean, language: String,
        channels: List<ArtworkChannel> = emptyList(),
        order: List<String> = emptyList(),
    ): kotlinx.coroutines.flow.Flow<ManualLibraryArtworkResult> {
        val available = site?.takeIf { it.site.searchable == 1 && it.spider?.searchable == true }
        val channelsByKind = channels.associateBy { it.kind }
        val slots = artworkProviderSlots(tmdbEnabled && apiKey.isNotBlank(), available != null, channels, order)
        return streamManualArtworkSlots(slots) { slot ->
            queryProvider(slot, query, apiKey, language, available, manual = true, channels = channelsByKind)
        }
    }

    private val metadataMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 手动换图后的资料：只认用户选中的作品身份，按 provider + id 直接取。
     *
     * 不再用旧片名重搜，也不跨渠道补搜。新来源没给某个字段时，由显示层
     * 逐字段保留旧值；这里只负责返回新来源真正拿到的内容。
     */
    suspend fun loadMetadataByIdentity(item: MediaItem, identity: LibraryWorkIdentity,
        site: SourceManager.SiteEntry?, apiKey: String, tmdbEnabled: Boolean,
        channels: List<ArtworkChannel> = emptyList(),
        order: List<String> = emptyList(),
    ): LibraryWorkMetadata? = withContext(Dispatchers.IO) {
        if (item.fromLibrary) return@withContext null
        val available = site?.takeIf { it.site.searchable == 1 && it.spider?.searchable == true }
        val providers = artworkProviderSlots(tmdbEnabled && apiKey.isNotBlank(), available != null, channels, order)
        val slot = providers.firstOrNull { it.id.equals(identity.provider, ignoreCase = true) }
        if (slot == null) {
            Log.i(tag, "在线资料按身份跳过: ${identity.provider}/${identity.id} 渠道未启用")
            return@withContext null
        }
        if (slot.source == LibraryArtworkSource.ONLINE &&
            (available == null || identity.siteKey != available.site.key)
        ) {
            Log.i(tag, "在线资料按身份跳过: ${identity.provider}/${identity.id} 站点不可用")
            return@withContext null
        }
        val metadata = try {
            metadataFor(identity, apiKey, available, slot, channels.associateBy { it.kind })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "在线资料按身份失败: ${identity.provider}/${identity.id}", e)
            return@withContext null
        }
        if (metadata == null || !metadata.hasContent) {
            Log.i(tag, "在线资料按身份无内容: ${identity.provider}/${identity.id}")
            return@withContext null
        }
        Log.i(tag, "在线资料按身份成功: ${identity.provider}/${identity.id} " +
            "overview=${!metadata.overview.isNullOrBlank()} cast=${metadata.cast.size} rating=${metadata.rating}")
        metadata
    }

    /**
     * 自动补资料：只在没有手动换图时使用，按用户顺序补齐评分/简介/演员头像。
     *
     * 不做跨渠道补搜：先找到第一个匹配的作品身份，再只问该渠道要资料，
     * 这样海报来自谁、评分就来自谁，符合"评分跟随海报来源"。
     */
    suspend fun loadOnlineMetadata(item: MediaItem, apiKey: String, tmdbEnabled: Boolean,
        language: String, channels: List<ArtworkChannel> = emptyList(),
        order: List<String> = emptyList(),
    ): LibraryWorkMetadata? = withContext(Dispatchers.IO) {
        if (item.fromLibrary) return@withContext null
        val providers = artworkProviderSlots(tmdbEnabled && apiKey.isNotBlank(), false, channels, order)
        if (providers.isEmpty()) return@withContext null
        val channelsByKind = channels.associateBy { it.kind }
        for (slot in providers) {
            coroutineContext.ensureActive()
            val found = try {
                queryProvider(slot, item.title, apiKey, language, null, channels = channelsByKind)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val match = uniqueLibraryArtworkMatch(item, found)
            if (match == null) {
                Log.d(tag, "在线资料无唯一匹配: ${item.title} provider=${slot.id} found=${found.size}")
                continue
            }
            val identity = match.identity ?: continue
            val extra = try {
                metadataFor(identity, apiKey, null, slot, channelsByKind)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (extra != null) {
                Log.i(tag, "在线资料搜索成功: ${item.title} -> ${identity.provider}/${identity.id}")
                return@withContext extra
            }
        }
        Log.i(tag, "在线资料搜索结束: ${item.title} 无结果")
        null
    }

    /** Only the opened library work requests credits. Scanning never makes one detail request per file. */
    suspend fun loadMetadata(item: MediaItem, binding: LibraryWorkIdentity?, coverOnly: Boolean,
        automatic: LibraryArtworkRecord?, cached: LibraryWorkMetadata?, site: SourceManager.SiteEntry?,
        apiKey: String, tmdbEnabled: Boolean, language: String,
        channels: List<ArtworkChannel> = emptyList(),
        order: List<String> = emptyList(),
    ): LibraryWorkMetadata? = withContext(Dispatchers.IO) {
        if (coverOnly) return@withContext null
        val available = site?.takeIf { it.site.searchable == 1 && it.spider?.searchable == true }
        val channelsByKind = channels.associateBy { it.kind }
        val providers = artworkProviderSlots(tmdbEnabled && apiKey.isNotBlank(), available != null, channels, order)
        val orderSignature = order.joinToString(",")
        // 绑定身份必须进指纹：换海报源（TMDB→豆瓣）后要重新取资料，不能命中旧缓存。
        val policy = digest(
            "${tmdbEnabled && apiKey.isNotBlank()}|$apiKey|${available?.site}|$language|" +
                channels.filter { it.enabled }.joinToString(",") { "${it.id}:${it.kind}:${it.baseUrl}" } +
                "|$orderSignature|$binding",
        )
        val now = System.currentTimeMillis()
        val requestKey = "${item.id}|${item.title}|$binding|$policy|$orderSignature"
        if ((metadataMisses[requestKey] ?: 0L) > now) return@withContext null
        // 换海报来源后，旧来源的缓存资料必须作废，不能按同名跨渠道复用。
        val validCached = cached?.takeIf {
            libraryMetadataApplies(item, it, binding) &&
                (binding == null || it.identity.provider == binding.provider)
        }
        var anchor = binding ?: automatic?.identity?.takeIf { libraryIdentityMatchesItem(item, it) } ?: validCached?.identity
        var seedPoster: String? = null
        var seedBackdrop: String? = null
        if (anchor == null) {
            for (slot in providers) {
                val found = try {
                    queryProvider(slot, item.title, apiKey, language, available, channels = channelsByKind)
                }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                val match = uniqueLibraryArtworkMatch(item, found) ?: continue
                if (!readable(match.poster)) continue
                anchor = match.identity
                seedPoster = match.poster
                seedBackdrop = match.backdrop?.takeIf { readable(it) }
                break
            }
        }
        val identity = anchor ?: run { metadataMisses[requestKey] = now + 15 * 60_000L; return@withContext null }
        // 旧来源的缓存：换来源时只用来"填空缺"，不参与命中判定。
        val stalePrevious = validCached?.takeIf { sameLibraryWork(identity, it.identity) }
        val previous = stalePrevious?.takeIf {
            it.identity.provider == identity.provider && sameLibraryWork(identity, it.identity)
        }
        if (previous != null && previous.retryAfter > now && previous.policy == policy) return@withContext previous
        var metadata = LibraryWorkMetadata(identity, poster = seedPoster ?: previous?.poster, backdrop = seedBackdrop ?: previous?.backdrop)
        val target = item.copy(title = identity.title, originalTitle = identity.originalTitle, year = identity.year,
            kind = runCatching { MediaKind.valueOf(identity.kind) }.getOrDefault(MediaKind.UNKNOWN))
        // 海报来源优先：评分/简介跟随海报来源，不额外跨渠道补搜。
        val orderedProviders = providers.sortedByDescending { it.id == identity.provider }
        for (slot in orderedProviders) {
            coroutineContext.ensureActive()
            // 内置源按 provider 名直接命中；外部渠道按配置 id 命中。
            val direct = identity.provider == slot.id &&
                (slot.source != LibraryArtworkSource.ONLINE || identity.siteKey == available?.site?.key)
            val candidateId = if (direct) identity else {
                val found = try {
                    queryProvider(slot, target.title, apiKey, language, available, channels = channelsByKind)
                }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                uniqueLibraryArtworkMatch(target, found)?.identity ?: continue
            }
            if (!sameLibraryWork(identity, candidateId)) continue
            val extra = try { metadataFor(candidateId, apiKey, available, slot, channelsByKind) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (extra != null) metadata = mergeLibraryMetadata(metadata, extra)
            if (metadata.complete) break
        }
        // ★ 换了资料来源（TMDB → 豆瓣）时，新来源的值必须覆盖旧值，
        //   否则 mergeLibraryMetadata 的"旧值非空就保留"会把新简介/演职员全压住，
        //   用户看到的就是"海报换了、资料没变"。
        //   新来源为空的字段仍然保留旧值（用户确认：界面不能突然变空）。
        // 同来源：只补空缺，不冲掉已有内容。
        // 换来源：新值优先，新来源缺的字段才退回旧值（用户确认不能变空）。
        metadata = if (previous != null) {
            mergeLibraryMetadata(metadata, previous)
        } else if (stalePrevious != null) {
            metadata.withFallbackFrom(stalePrevious)
        } else {
            metadata
        }
        if (!metadata.hasContent) { metadataMisses[requestKey] = now + 15 * 60_000L; return@withContext null }
        metadata.copy(policy = policy, retryAfter = now + if (metadata.complete) 7 * 24 * 60 * 60_000L else 15 * 60_000L)
    }

    private suspend fun metadataFor(identity: LibraryWorkIdentity, apiKey: String,
        site: SourceManager.SiteEntry?, slot: ArtworkProviderSlot,
        channels: Map<String, ArtworkChannel>): LibraryWorkMetadata? =
        mutexFor(slot).withLock {
        val provider = slot.source
        val now = System.currentTimeMillis()
        val wait = (nextRequest[slot.id] ?: 0L) - now
        if (wait > 5000) return@withLock null
        if (wait > 0) delay(wait)
        nextRequest[slot.id] = System.currentTimeMillis() + 800
        withTimeoutOrNull(15_000) {
            when (provider) {
                LibraryArtworkSource.TMDB -> TmdbClient.libraryMetadata(apiKey, identity)
                LibraryArtworkSource.ONLINE -> {
                    if (identity.siteKey != site?.site?.key) return@withTimeoutOrNull null
                    val item = site.spider?.detail(identity.id)?.item ?: return@withTimeoutOrNull null
                    val actual = LibraryWorkIdentity("ONLINE", item.sourceId, "", item.title, item.originalTitle,
                        item.year, item.kind.name, identity.siteKey)
                    // A source may return a stub or a different work for an expired ID.
                    val expected = identity.copy(provider = "EXPECTED")
                    if (actual.id != identity.id || !sameLibraryWork(expected, actual)) return@withTimeoutOrNull null
                    LibraryWorkMetadata(actual, item.overview?.takeIf { it.isNotBlank() },
                        item.director?.split(Regex("[/、,，]"))?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
                        item.cast.map { LibraryPersonCredit(it) },
                        rating = item.rating,
                        ratingSource = item.ratingSource)
                }
                LibraryArtworkSource.STREAMX -> channels[ArtworkChannelKind.STREAMX.name]
                    ?.let { StreamxClient.metadata(it.effectiveUrl, identity, userAgent()) }
                LibraryArtworkSource.DOUBAN -> channels[ArtworkChannelKind.DOUBAN.name]?.let { channel ->
                    val kind = runCatching { MediaKind.valueOf(identity.kind) }.getOrDefault(MediaKind.UNKNOWN)
                    // 已知 id 直接取资料，不再做 HTML 搜索。
                    // 评分只跟随海报来源：豆瓣不是海报来源时，丢弃豆瓣评分。
                    val metadata = DoubanClient.metadataById(channel.effectiveUrl, identity.id, kind)
                    if (identity.provider == "DOUBAN") metadata
                    else metadata?.copy(rating = null, ratingSource = null)
                }
                // TVmaze 定位是"补横图"，不提供资料补全。
                LibraryArtworkSource.TVMAZE -> null
            }
        }
    }

    private suspend fun queryProvider(slot: ArtworkProviderSlot, title: String, apiKey: String,
        language: String, site: SourceManager.SiteEntry?, manual: Boolean = false,
        channels: Map<String, ArtworkChannel> = emptyMap(),
    ): List<LibraryArtworkCandidate> = mutexFor(slot).withLock {
        val provider = slot.source
        val now = System.currentTimeMillis()
        val wait = (nextRequest[slot.id] ?: 0L) - now
        if (wait > 5000) {
            check(!manual) { "请求过于频繁，请稍后重试" }
            return@withLock emptyList()
        }
        if (wait > 0) delay(wait)
        nextRequest[slot.id] = System.currentTimeMillis() + 800
        try {
            withTimeoutOrNull(15_000) {
                when (provider) {
                    LibraryArtworkSource.TMDB -> TmdbClient.search(apiKey, title, language).map {
                        LibraryArtworkCandidate(it.title, it.year, it.posterUrlW500.orEmpty(), it.backdropUrlW1280,
                            if (it.mediaType == "movie") MediaKind.MOVIE else MediaKind.SERIES, it.originalTitle,
                            LibraryWorkIdentity("TMDB", it.id.toString(), it.mediaType, it.title, it.originalTitle, it.year,
                                if (it.mediaType == "movie") "MOVIE" else "SERIES"))
                    }
                    LibraryArtworkSource.ONLINE -> checkNotNull(site?.spider).search(title, 1).map {
                        LibraryArtworkCandidate(it.title, it.year, it.poster.orEmpty(), it.backdrop, it.kind, it.originalTitle,
                            it.sourceId.takeIf { id -> id.isNotBlank() }?.let { id -> LibraryWorkIdentity("ONLINE", id, "", it.title,
                                it.originalTitle, it.year, it.kind.name, checkNotNull(site).site.key) })
                    }
                    LibraryArtworkSource.STREAMX -> {
                        val channel = channels[ArtworkChannelKind.STREAMX.name]
                            ?: return@withTimeoutOrNull emptyList()
                        StreamxClient.search(
                            baseUrl = channel.effectiveUrl,
                            title = title,
                            withOverview = language.startsWith("en"),
                            userAgent = userAgent(),
                        )
                    }
                    LibraryArtworkSource.TVMAZE -> {
                        val channel = channels[ArtworkChannelKind.TVMAZE.name]
                            ?: return@withTimeoutOrNull emptyList()
                        TvmazeClient.search(
                            baseUrl = channel.effectiveUrl,
                            title = title,
                            englishTitle = null,
                            year = null,
                            userAgent = userAgent(),
                        )
                    }
                    LibraryArtworkSource.DOUBAN -> {
                        val channel = channels[ArtworkChannelKind.DOUBAN.name]
                            ?: return@withTimeoutOrNull emptyList()
                    DoubanClient.searchCandidates(channel.effectiveUrl, title, MediaKind.UNKNOWN)
                    }
                }
            }?.map { it.copy(source = provider, siteName = if (provider == LibraryArtworkSource.ONLINE) site?.site?.name else null) }
                ?: if (manual) error("请求超时") else emptyList()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            nextRequest[slot.id] = maxOf(nextRequest[slot.id] ?: 0L, System.currentTimeMillis() + 60_000)
            throw e
        }
    }

    private suspend fun readable(url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        return withTimeoutOrNull(10_000) {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(400, 600)
                .allowHardware(false).memoryCacheKey(url).diskCacheKey(url).build())
            result is SuccessResult && result.drawable.intrinsicWidth >= 80 && result.drawable.intrinsicHeight >= 80
        } ?: false
    }

    private fun persist() {
        val atomic = store()
        val output = atomic.startWrite()
        try {
            val limited = records.entries.sortedByDescending { it.value.retryAfter }.take(2000).associate { it.toPair() }
            output.write(json.encodeToString(Saved(limited)).toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
}
