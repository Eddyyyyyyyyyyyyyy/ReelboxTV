package com.reelix.app.data.streamx

import com.reelix.app.data.image.LibraryArtworkCandidate
import com.reelix.app.data.image.LibraryWorkIdentity
import com.reelix.app.data.image.LibraryWorkMetadata
import com.reelix.app.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * streamx-api 客户端（keyless）。
 *
 * 只有一次搜索请求，返回 `metas[]`，每条带 `poster` 和 `background`，
 * 正好覆盖"竖图 + 横图"。需要简介时再查一次 `/meta/{type}/{id}`。
 */
object StreamxClient {

    const val DEFAULT_BASE_URL = "https://api.streamx.qzz.io"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .build()
    }

    fun baseUrl(configured: String): String {
        val raw = configured.trim().ifBlank { DEFAULT_BASE_URL }
        val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            "https://$raw"
        }
        return withScheme.trimEnd('/')
    }

    suspend fun search(
        baseUrl: String,
        title: String,
        withOverview: Boolean,
        userAgent: String,
    ): List<LibraryArtworkCandidate> = withContext(Dispatchers.IO) {
        val query = title.trim()
        if (query.isEmpty()) return@withContext emptyList()
        val root = get("${baseUrl(baseUrl)}/search?q=${URLEncoder.encode(query, "UTF-8")}", userAgent)
        val metas = root.optJSONArray("metas") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until metas.length()) {
                val meta = metas.optJSONObject(i) ?: continue
                val name = meta.optString("name").trim()
                if (name.isEmpty()) continue
                val poster = meta.optString("poster").takeIf { it.startsWith("http") } ?: continue
                val backdrop = meta.optString("background").takeIf { it.startsWith("http") }
                val type = meta.optString("type")
                val kind = if (type.equals("series", true)) MediaKind.SERIES else MediaKind.MOVIE
                val year = meta.optString("releaseInfo").take(4).toIntOrNull()
                val imdbId = meta.optString("imdb_id").ifBlank { meta.optString("id") }
                if (imdbId.isBlank()) continue
                val overview = if (withOverview) {
                    runCatching { overview(baseUrl, type, imdbId, userAgent) }.getOrNull()
                } else null
                add(
                    LibraryArtworkCandidate(
                        title = name,
                        year = year,
                        poster = poster,
                        backdrop = backdrop,
                        kind = kind,
                        identity = LibraryWorkIdentity(
                            provider = "STREAMX",
                            id = imdbId,
                            mediaType = type,
                            title = name,
                            year = year,
                            kind = kind.name,
                        ),
                        overview = overview,
                    ),
                )
            }
        }
    }

    /** 英文简介。失败返回 null，不影响横图。 */
    private fun overview(baseUrl: String, type: String, id: String, userAgent: String): String? {
        val safeType = if (type.equals("series", true)) "series" else "movie"
        val root = get("${baseUrl(baseUrl)}/meta/$safeType/$id?enrich=0", userAgent)
        return root.optJSONObject("meta")?.optString("description")?.takeIf { it.isNotBlank() }
    }

    suspend fun metadata(baseUrl: String, identity: LibraryWorkIdentity, userAgent: String): LibraryWorkMetadata? =
        withContext(Dispatchers.IO) {
            val safeType = if (identity.mediaType.equals("series", true)) "series" else "movie"
            val root = runCatching { get("${baseUrl(baseUrl)}/meta/$safeType/${identity.id}?enrich=0", userAgent) }
                .getOrNull() ?: return@withContext null
            val meta = root.optJSONObject("meta") ?: return@withContext null
            val overview = meta.optString("description").takeIf { it.isNotBlank() }
            val directors = meta.optJSONArray("director")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val cast = meta.optJSONArray("cast")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            if (overview == null && directors.isEmpty() && cast.isEmpty()) return@withContext null
            LibraryWorkMetadata(
                identity = identity,
                overview = overview,
                directors = directors,
                cast = cast.map { com.reelix.app.data.image.LibraryPersonCredit(it) },
                backdrop = meta.optString("background").takeIf { it.startsWith("http") },
            )
        }

    /** 连通性测试：返回 null 表示成功。 */
    suspend fun test(baseUrl: String, userAgent: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val root = get("${baseUrl(baseUrl)}/search?q=batman", userAgent)
            val metas = root.optJSONArray("metas")
            if (metas == null || metas.length() == 0) {
                "请求成功，但没有返回结果"
            } else {
                val first = metas.optJSONObject(0)
                val name = first?.optString("name").orEmpty()
                if (name.isBlank()) "返回了结果，但缺少标题字段" else "成功：${metas.length()} 条，示例《$name》"
            }
        }.getOrElse { e -> e.message?.take(120) ?: "连接失败" }
    }

    private fun get(url: String, userAgent: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent.ifBlank { "Reelbox (Android TV)" })
            .build()
        val response = http.newCall(request).execute()
        response.use {
            check(it.isSuccessful) { "Streamx HTTP ${it.code}" }
            val bytes = it.body?.bytes() ?: error("Streamx empty response")
            check(bytes.size <= 4 * 1024 * 1024) { "Streamx response too large" }
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
}
