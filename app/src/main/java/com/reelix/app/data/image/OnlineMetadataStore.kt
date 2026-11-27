package com.reelix.app.data.image

import android.content.Context
import com.reelix.app.data.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 在线条目的资料缓存。
 *
 * 和 online-artwork-overrides.json 分开：那个只存用户手动选的图，
 * 这里存刮削得到的评分/简介/演员头像，二者生命周期不同。
 */
object OnlineMetadataStore {

    @Serializable
    data class Entry(
        val rating: Double? = null,
        val ratingSource: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val directors: List<String> = emptyList(),
        val cast: List<LibraryPersonCredit> = emptyList(),
        /** 手动换图所选作品的精确身份；用于判断缓存是否仍对应同一部作品。 */
        val identity: LibraryWorkIdentity? = null,
        /** 手动换图同步的资料：新值优先，缺失字段才回退旧值。 */
        val manual: Boolean = false,
        val savedAt: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val cache = LinkedHashMap<String, Entry>()
    @Volatile private var loaded = false
    private var context: Context? = null

    fun init(appContext: Context) {
        if (loaded) return
        context = appContext.applicationContext
        val file = file(context!!)
        if (file.isFile) {
            runCatching {
                json.decodeFromString<Map<String, Entry>>(file.readText()).forEach { (k, v) -> cache[k] = v }
            }
        }
        loaded = true
    }

    fun apply(item: MediaItem): MediaItem {
        ensureLoaded()
        if (item.fromLibrary) return item
        val entry = cache[key(item)] ?: return item
        return applyOnlineMetadata(item, entry)
    }

    fun get(item: MediaItem): Entry? {
        ensureLoaded()
        if (item.fromLibrary) return null
        return cache[key(item)]
    }

    fun save(item: MediaItem, entry: Entry) {
        ensureLoaded()
        val cacheKey = key(item) ?: return
        cache[cacheKey] = entry.copy(savedAt = System.currentTimeMillis())
        persist()
    }

    fun remove(item: MediaItem) {
        ensureLoaded()
        val cacheKey = key(item) ?: return
        if (cache.remove(cacheKey) != null) persist()
    }

    private fun key(item: MediaItem): String? {
        if (item.sourceKey.isBlank() || item.sourceId.isBlank()) return null
        return "${item.sourceKey}|${item.sourceId}"
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "online-metadata.json")

    private fun ensureLoaded() {
        if (loaded) return
        com.reelix.app.ReelboxApp.app()?.let(::init)
    }

    @Synchronized
    private fun persist() {
        val ctx = context ?: return
        runCatching {
            val limited = cache.entries.sortedByDescending { it.value.savedAt }.take(2000)
                .associate { it.key to it.value }
            val temp = File(ctx.filesDir, "online-metadata.json.tmp")
            temp.writeText(json.encodeToString(limited))
            temp.renameTo(file(ctx))
        }
    }
}

/**
 * 资料应用规则。
 *
 * 自动补资料仍只填空：源站自己有简介/评分时，不让后台刮削悄悄覆盖。
 * 手动换图则相反：用户已经明确选了新作品，新来源的非空字段优先；新来源
 * 没给的字段才保留旧值，既不把界面清空，也不把旧来源的值冒充成新来源。
 */
internal fun applyOnlineMetadata(item: MediaItem, entry: OnlineMetadataStore.Entry): MediaItem {
    if (!entry.manual) {
        return item.copy(
            rating = item.rating ?: entry.rating,
            ratingSource = item.ratingSource ?: entry.ratingSource,
            overview = item.overview?.takeIf { it.isNotBlank() } ?: entry.overview,
            castCredits = item.castCredits.ifEmpty { entry.cast },
        )
    }
    val newCast = entry.cast.takeIf { it.isNotEmpty() }
    return item.copy(
        rating = entry.rating ?: item.rating,
        ratingSource = if (entry.rating != null) entry.ratingSource else item.ratingSource,
        overview = entry.overview?.takeIf { it.isNotBlank() } ?: item.overview,
        year = entry.year ?: item.year,
        director = entry.directors.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: item.director,
        cast = newCast?.map { credit ->
            credit.name + (credit.role?.takeIf(String::isNotBlank)?.let { role -> "（$role）" } ?: "")
        } ?: item.cast,
        castCredits = newCast ?: item.castCredits,
    )
}
