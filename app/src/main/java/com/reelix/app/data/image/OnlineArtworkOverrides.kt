package com.reelix.app.data.image

import android.content.Context
import com.reelix.app.data.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Locally chosen artwork for online items.
 *
 * Keyed by source + vod id rather than the volatile list id, so a refresh or a source
 * switch keeps the user's chosen poster.
 */
object OnlineArtworkOverrides {

    @Serializable
    data class Override(
        val poster: String = "",
        val backdrop: String? = null,
        /** 手动选择的来源（TMDB / DOUBAN / ...）；资料要跟随这个来源。 */
        val source: String? = null,
        /** 手动所选作品的精确身份；资料按这个 ID 取，不再用旧片名重搜。 */
        val identity: LibraryWorkIdentity? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val cache = LinkedHashMap<String, Override>()
    @Volatile private var loaded = false
    private var context: Context? = null

    fun init(appContext: Context) {
        if (loaded) return
        context = appContext.applicationContext
        val file = file(context!!)
        if (file.isFile) {
            runCatching {
                json.decodeFromString<Map<String, Override>>(file.readText()).forEach { (k, v) ->
                    if (v.poster.isNotBlank()) cache[k] = v
                }
            }
        }
        loaded = true
    }

    fun apply(item: MediaItem): MediaItem {
        ensureLoaded()
        if (item.fromLibrary) return item
        val key = key(item) ?: return item
        val value = cache[key] ?: return item
        return item.copy(poster = value.poster, backdrop = value.backdrop ?: item.backdrop)
    }

    fun save(item: MediaItem, poster: String, backdrop: String?, source: String? = null,
        identity: LibraryWorkIdentity? = null) {
        ensureLoaded()
        val key = key(item) ?: return
        if (poster.isBlank()) return
        cache[key] = Override(poster, backdrop, source, identity)
        persist()
    }

    /** 该条目手动选择的海报来源；没有手动覆盖时为空。 */
    fun sourceOf(item: MediaItem): String? {
        ensureLoaded()
        val key = key(item) ?: return null
        return cache[key]?.source
    }

    /** 该条目手动选择的作品身份；老数据可能为空。 */
    fun identityOf(item: MediaItem): LibraryWorkIdentity? {
        ensureLoaded()
        val key = key(item) ?: return null
        return cache[key]?.identity
    }

    fun remove(item: MediaItem) {
        ensureLoaded()
        val key = key(item) ?: return
        if (cache.remove(key) != null) persist()
    }

    private fun key(item: MediaItem): String? {
        if (item.fromLibrary || item.sourceKey.isBlank() || item.sourceId.isBlank()) return null
        return "${item.sourceKey}|${item.sourceId}"
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "online-artwork-overrides.json")

    private fun ensureLoaded() {
        if (loaded) return
        com.reelix.app.ReelboxApp.app()?.let(::init)
    }

    @Synchronized
    private fun persist() {
        val ctx = context ?: return
        runCatching {
            val temp = File(ctx.filesDir, "online-artwork-overrides.json.tmp")
            temp.writeText(json.encodeToString(cache.toMap()))
            temp.renameTo(file(ctx))
        }
    }
}
