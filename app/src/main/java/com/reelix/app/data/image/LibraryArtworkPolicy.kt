package com.reelix.app.data.image

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/**
 * 一个刮削来源。
 *
 * 每种来源都有自己的适配器，顺序由用户在设置里调整；在线源固定垫底。
 */
enum class LibraryArtworkSource {
    TMDB, DOUBAN, STREAMX, TVMAZE, ONLINE;

    /** 是否只用来补横图（不改条目已有的竖版海报）。 */
    val backdropOnly: Boolean get() = this == STREAMX || this == TVMAZE

    val displayName: String get() = when (this) {
        TMDB -> "TMDB"
        DOUBAN -> "豆瓣"
        STREAMX -> "Streamx"
        TVMAZE -> "TVmaze"
        ONLINE -> "在线源"
    }
}

/**
 * 一次刮削里"要试哪个来源"。
 *
 * 内置源直接用 [source]；外部渠道额外带上配置，遍历顺序就是用户的优先级。
 */
data class ArtworkProviderSlot(val source: LibraryArtworkSource) {
    val id: String get() = source.name
    val label: String get() = source.displayName
}
/** A retained key is not permission to query a provider the user disabled. */
fun manualLibraryArtworkSources(tmdbEnabled: Boolean, apiKey: String, onlineAvailable: Boolean,
    channels: List<ArtworkChannel> = emptyList(),
    order: List<String> = emptyList()): List<LibraryArtworkSource> =
    artworkProviderSlots(tmdbEnabled && apiKey.isNotBlank(), onlineAvailable, channels, order)
        .map { it.source }

/** 画廊筛选用：只列已启用渠道，在线源固定最后。 */
fun manualArtworkFilterSources(tmdbEnabled: Boolean, apiKey: String, onlineAvailable: Boolean,
    channels: List<ArtworkChannel> = emptyList(),
    order: List<String> = emptyList()): List<LibraryArtworkSource> =
    manualLibraryArtworkSources(tmdbEnabled, apiKey, onlineAvailable, channels, order)

fun manualArtworkUrlAllowed(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

fun applyManualLibraryArtwork(item: MediaItem, poster: String, backdrop: String?): MediaItem =
    item.copy(
        poster = poster,
        posterRes = null,
        // Choosing only a poster must not erase an automatic/source backdrop.
        backdrop = backdrop ?: item.backdrop,
        backdropRes = if (backdrop != null) null else item.backdropRes,
    )

fun shouldScrapeDetailArtwork(item: MediaItem, tmdbEnabled: Boolean, apiKey: String,
    channelsAvailable: Boolean = false): Boolean =
    (channelsAvailable || (tmdbEnabled && apiKey.isNotBlank())) &&
        !(item.fromLibrary && item.poster?.startsWith("file:") == true)

enum class ManualArtworkPhase { LOADING, READY, EMPTY, ERROR }

data class ManualLibraryArtworkResult(val source: LibraryArtworkSource? = null,
    val candidates: List<LibraryArtworkCandidate> = emptyList(),
    val phase: ManualArtworkPhase = if (candidates.isEmpty()) ManualArtworkPhase.EMPTY else ManualArtworkPhase.READY,
    val error: String? = null)

fun streamManualLibraryArtwork(providers: List<LibraryArtworkSource>,
    query: suspend (LibraryArtworkSource) -> List<LibraryArtworkCandidate>): Flow<ManualLibraryArtworkResult> = channelFlow {
    if (providers.isEmpty()) send(ManualLibraryArtworkResult(phase = ManualArtworkPhase.ERROR,
        error = "请先启用并配置 TMDB，或在在线页选择支持搜索的源"))
    providers.distinct().forEach { send(ManualLibraryArtworkResult(it, phase = ManualArtworkPhase.LOADING)) }
    providers.distinct().forEach { provider ->
        launch(Dispatchers.IO) {
            val response = try {
                val found = withTimeout(20_000) { query(provider) }
                    .filter { manualArtworkUrlAllowed(it.poster) }
                    .map { it.copy(source = provider, backdrop = it.backdrop?.takeIf(::manualArtworkUrlAllowed)) }
                    .distinctBy(::artworkCandidateKey)
                ManualLibraryArtworkResult(provider, found)
            } catch (_: TimeoutCancellationException) {
                ManualLibraryArtworkResult(provider, phase = ManualArtworkPhase.ERROR, error = "请求超时")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                ManualLibraryArtworkResult(provider, phase = ManualArtworkPhase.ERROR,
                    error = failure.message?.take(160) ?: "请求失败")
            }
            send(response)
        }
    }
}

/**
 * 按槽位流式返回手动搜索结果（内置源 + 自定义渠道）。
 *
 * 每个渠道独立失败：一个渠道报错只影响它自己那一条结果。
 */
fun streamManualArtworkSlots(slots: List<ArtworkProviderSlot>,
    query: suspend (ArtworkProviderSlot) -> List<LibraryArtworkCandidate>,
): Flow<ManualLibraryArtworkResult> = channelFlow {
    if (slots.isEmpty()) {
        send(
            ManualLibraryArtworkResult(
                phase = ManualArtworkPhase.ERROR,
                error = "请先启用并配置 TMDB，或在在线页选择支持搜索的源",
            ),
        )
    }
    slots.forEach { send(ManualLibraryArtworkResult(it.source, phase = ManualArtworkPhase.LOADING)) }
    slots.forEach { slot ->
        launch(Dispatchers.IO) {
            val response = try {
                val found = withTimeout(20_000) { query(slot) }
                    .filter { manualArtworkUrlAllowed(it.poster) }
                    .map { it.copy(source = slot.source, backdrop = it.backdrop?.takeIf(::manualArtworkUrlAllowed)) }
                    .distinctBy(::artworkCandidateKey)
                ManualLibraryArtworkResult(slot.source, found)
            } catch (_: TimeoutCancellationException) {
                ManualLibraryArtworkResult(slot.source, phase = ManualArtworkPhase.ERROR, error = "请求超时")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ManualLibraryArtworkResult(
                    slot.source, phase = ManualArtworkPhase.ERROR,
                    error = failure.message?.take(160) ?: "请求失败",
                )
            }
            send(response)
        }
    }
}

/** Manual corrections keep fuzzy results for the user to choose; automatic exact matching stays separate. */
suspend fun searchManualLibraryArtwork(providers: List<LibraryArtworkSource>,
    query: suspend (LibraryArtworkSource) -> List<LibraryArtworkCandidate>): ManualLibraryArtworkResult {
    var failure: Exception? = null
    var responded = false
    for (provider in providers) {
        val found = try { query(provider).also { responded = true } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e; continue }
        val candidates = found.filter { manualArtworkUrlAllowed(it.poster) }.map {
            it.copy(backdrop = it.backdrop?.takeIf(::manualArtworkUrlAllowed))
        }.distinctBy(::artworkCandidateKey)
        if (candidates.isNotEmpty()) return ManualLibraryArtworkResult(provider, candidates)
    }
    if (!responded) failure?.let { throw it }
    return ManualLibraryArtworkResult()
}

/**
 * 按用户设定的优先级展开成槽位。
 *
 * - 顺序来自 [order]（TMDB / STREAMX / TVMAZE 的排列）
 * - 在线源固定最后：它基本只有竖版海报，放在前面只会拖慢刮削
 * - 停用的来源不进入列表 —— 这是"没启用就不刮削"的唯一判定点
 */
fun artworkProviderSlots(
    tmdbConfigured: Boolean,
    onlineAvailable: Boolean,
    channels: List<ArtworkChannel>,
    order: List<String> = emptyList(),
): List<ArtworkProviderSlot> = buildList {
    val enabled = channels.associateBy { it.kind }
    val ordered = order.ifEmpty { listOf("TMDB", "STREAMX", "TVMAZE") }
    ordered.forEach { key ->
        when (key) {
            "TMDB" -> if (tmdbConfigured) add(ArtworkProviderSlot(LibraryArtworkSource.TMDB))
            "DOUBAN" -> if (enabled[ArtworkChannelKind.DOUBAN.name]?.enabled == true) {
                add(ArtworkProviderSlot(LibraryArtworkSource.DOUBAN))
            }
            "STREAMX" -> if (enabled["STREAMX"]?.enabled == true) {
                add(ArtworkProviderSlot(LibraryArtworkSource.STREAMX))
            }
            "TVMAZE" -> if (enabled["TVMAZE"]?.enabled == true) {
                add(ArtworkProviderSlot(LibraryArtworkSource.TVMAZE))
            }
        }
    }
    if (onlineAvailable) add(ArtworkProviderSlot(LibraryArtworkSource.ONLINE))
}

data class LibraryArtworkCandidate(val title: String, val year: Int?, val poster: String,
    val backdrop: String? = null, val kind: MediaKind = MediaKind.UNKNOWN, val originalTitle: String? = null,
    val identity: LibraryWorkIdentity? = null, val source: LibraryArtworkSource? = null, val siteName: String? = null,
    /** 英文简介；只有外部资料源在"资料语言 = 英文"时提供。 */
    val overview: String? = null)
@Serializable enum class ArtworkTargetState { UNKNOWN, FOUND, CONFIRMED_MISSING, FAILED }

@Serializable data class LibraryArtworkRecord(
    val poster: String = "",
    val backdrop: String? = null,
    val state: ArtworkTargetState = ArtworkTargetState.UNKNOWN,
    val retryAfter: Long = 0L,
    val source: String = "",
    val workId: String = "",
    val identity: LibraryWorkIdentity? = null,
) {
    fun normalizedForUse(): LibraryArtworkRecord = copy(
        state = if (state == ArtworkTargetState.UNKNOWN &&
            (poster.isNotBlank() || !backdrop.isNullOrBlank())
        ) {
            ArtworkTargetState.FOUND
        } else state,
        retryAfter = if (retryAfter == 0L && (poster.isNotBlank() || !backdrop.isNullOrBlank())) {
            Long.MAX_VALUE
        } else retryAfter,
    )
}

data class LibraryArtworkResolution(
    val record: LibraryArtworkRecord?,
    val state: ArtworkTargetState,
)

fun artworkCandidateKey(candidate: LibraryArtworkCandidate): String =
    "${candidate.identity}|${candidate.source}|${candidate.poster}|${candidate.title}|${candidate.year}"

enum class ArtworkBackdropState { NONE, PENDING, LOADED, FAILED, UNSUITABLE }
fun artworkBackdropState(url: String?, loaded: Boolean?, width: Int = 0, height: Int = 0): ArtworkBackdropState = when {
    url.isNullOrBlank() -> ArtworkBackdropState.NONE
    loaded == null -> ArtworkBackdropState.PENDING
    !loaded -> ArtworkBackdropState.FAILED
    width <= height || height <= 0 -> ArtworkBackdropState.UNSUITABLE
    else -> ArtworkBackdropState.LOADED
}

fun artworkTitle(raw: String): String = raw.lowercase()
    .replace(Regex("[（(](?:19|20)\\d{2}[）)]"), "")
    .replace(Regex("(?i)(第[一二三四五六七八九十百零两0-9]+季|\\bseason\\s*\\d+|\\bs\\d{1,3}\\b|最终季|最終季|终季|全季|全集)"), "")
    .replace(Regex("[\\s._\\-·:：（）()【】\\[\\]]"), "")

/**
 * 片名归一化变体。
 *
 * 在线源的片名经常把年份直接粘在标题后面（例如「奥德赛2026」），而 TMDB
 * 返回的标题只叫「奥德赛」。只在尾部年份与条目年份一致时去掉它，避免把
 * 《1917》这种片名本身当成噪音。
 */
fun artworkTitleVariants(raw: String, year: Int?): Set<String> {
    val result = linkedSetOf<String>()
    artworkTitle(raw).takeIf { it.isNotEmpty() }?.let(result::add)
    val yearText = year?.toString() ?: return result
    val suffix = Regex("(?i)[.\\s]*[（(]?${Regex.escape(yearText)}[）)]?\\s*$")
    if (!suffix.containsMatchIn(raw)) return result
    artworkTitle(suffix.replace(raw, "")).takeIf { it.isNotEmpty() }?.let(result::add)
    return result
}

/** Exact normalized title and non-conflicting year/type; never adopt an arbitrary first search hit. */
fun libraryArtworkCandidates(item: MediaItem, candidates: List<LibraryArtworkCandidate>): List<LibraryArtworkCandidate> {
    val titles = artworkTitleVariants(item.title, item.year)
    return candidates.filter {
        val candidateTitles = buildSet {
            addAll(artworkTitleVariants(it.title, it.year))
            it.originalTitle?.let { original -> addAll(artworkTitleVariants(original, it.year)) }
        }
        titles.isNotEmpty() && titles.any(candidateTitles::contains) &&
            (item.year == null || it.year == null || item.year == it.year) &&
            (item.kind == MediaKind.UNKNOWN || it.kind == MediaKind.UNKNOWN ||
                (item.kind == MediaKind.MOVIE) == (it.kind == MediaKind.MOVIE)) &&
            (it.poster.startsWith("https://") || it.poster.startsWith("http://"))
    }.distinctBy { it.poster }.sortedByDescending { item.year != null && it.year == item.year }
}

/** Injected IO allows provider failures, invalid images and fallback order to be tested without Android. */
suspend fun resolveLibraryArtwork(item: MediaItem, providers: List<ArtworkProviderSlot>,
    query: suspend (ArtworkProviderSlot) -> List<LibraryArtworkCandidate>,
    readable: suspend (String) -> Boolean): LibraryArtworkResolution {
    var poster: String? = null
    var backdrop: String? = null
    var failed = false
    var source = ""
    var identity: LibraryWorkIdentity? = null
    suspend fun readableOrFalse(url: String): Boolean =
        try { readable(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    for (slot in providers) {
        val candidates = try { query(slot) } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                failed = true
                continue
            }
        for (candidate in libraryArtworkCandidates(item, candidates)) {
            if (poster == null && !slot.source.backdropOnly && readableOrFalse(candidate.poster)) {
                poster = candidate.poster
                source = slot.id
                identity = candidate.identity?.takeIf { it == uniqueLibraryArtworkMatch(item, candidates)?.identity }
            }
            if (backdrop == null) {
                val candidateBackdrop = candidate.backdrop
                if (candidateBackdrop != null && readableOrFalse(candidateBackdrop)) {
                    backdrop = candidateBackdrop
                    source = slot.id
                    identity = candidate.identity?.takeIf { it == uniqueLibraryArtworkMatch(item, candidates)?.identity }
                }
            }
            if (poster != null && backdrop != null) break
        }
        if (poster != null && backdrop != null) break
    }

    val state = when {
        poster != null -> ArtworkTargetState.FOUND
        failed -> ArtworkTargetState.FAILED
        else -> ArtworkTargetState.CONFIRMED_MISSING
    }
    return LibraryArtworkResolution(
        record = if (poster != null || backdrop != null) {
            LibraryArtworkRecord(
                poster = poster.orEmpty(),
                backdrop = backdrop,
                source = source,
                identity = identity,
            )
        } else null,
        state = state,
    )
}
