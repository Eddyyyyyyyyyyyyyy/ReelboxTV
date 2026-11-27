package com.reelix.app.data.tvmaze

import com.reelix.app.data.image.LibraryArtworkCandidate
import com.reelix.app.data.image.LibraryWorkIdentity
import com.reelix.app.data.image.artworkTitle
import com.reelix.app.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * TVmaze 客户端。
 *
 * ## 能力边界（实测）
 *
 * - **只有剧集（show），没有电影**。电影条目完全查不到，调用前必须先过滤。
 * - 每个 show 有多张 background / poster，天然适合做"多候选"。
 * - 中文查询会误配（"与晋长安" → "Shadow Love"），所以必须用 score 阈值兜底。
 *
 * ## 为什么只用于手动换海报
 *
 * TVmaze 是社区库，中文剧的封面经常缺或错。自动补图一旦用错图，
 * 用户看到的是"别的剧"的海报，比没图更糟。所以只进手动候选列表。
 *
 * ## 限流
 *
 * 官方要求每 IP 至少 20 次 / 10 秒。这里做客户端侧节流 + 429 退避，
 * 并显式设置可识别的 UA（官方明确要求）。
 */
object TvmazeClient {

    const val DEFAULT_BASE_URL = "https://api.tvmaze.com"

    /** 低于这个相似度直接丢弃，避免中文片名模糊匹配到无关剧。 */
    private const val MIN_SCORE = 0.75

    /** 两次请求的最小间隔（官方 20 次/10 秒，这里留余量）。 */
    private const val MIN_INTERVAL_MS = 600L

    private val lastRequestAt = AtomicLong(0L)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            // 官方要求不要留下多余的空闲连接。
            .connectionPool(okhttp3.ConnectionPool(1, 30, TimeUnit.SECONDS))
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

    /**
     * 搜索剧集并返回候选（含多张横图/竖图）。
     *
     * @param englishTitle 优先用英文原名查询；为空才退回中文标题
     */
    suspend fun search(
        baseUrl: String,
        title: String,
        englishTitle: String?,
        year: Int?,
        userAgent: String,
    ): List<LibraryArtworkCandidate> = withContext(Dispatchers.IO) {
        val query = englishTitle?.takeIf { it.isNotBlank() } ?: title
        if (query.isBlank()) return@withContext emptyList()

        val shows = searchShows(baseUrl, query, userAgent)
        if (shows.isEmpty()) return@withContext emptyList()

        val wanted = artworkTitle(title)
        val wantedEn = englishTitle?.let(::artworkTitle).orEmpty()

        buildList {
            for (show in shows) {
                val name = show.optJSONObject("show")?.optString("name").orEmpty()
                if (name.isBlank()) continue
                val score = show.optDouble("score", 0.0)
                val premiered = show.optJSONObject("show")?.optString("premiered").orEmpty()
                val showYear = premiered.take(4).toIntOrNull()
                val nameKey = artworkTitle(name)
                // 名字必须对得上，或者接口自己的相似度足够高；否则视为误配。
                val nameMatches = nameKey.isNotEmpty() &&
                    (nameKey == wanted || (wantedEn.isNotEmpty() && nameKey == wantedEn))
                if (!nameMatches && score < MIN_SCORE) continue
                if (year != null && showYear != null && kotlin.math.abs(showYear - year) > 1) continue

                val showId = show.optJSONObject("show")?.optInt("id", 0) ?: 0
                if (showId <= 0) continue
                val images = runCatching { showImages(baseUrl, showId, userAgent) }.getOrDefault(emptyList())
                val backdrops = images.filter { it.optString("type") == "background" }
                    .mapNotNull { it.optJSONObject("resolutions")?.optJSONObject("original")?.optString("url") }
                    .filter { it.startsWith("http") }
                val posters = images.filter { it.optString("type") == "poster" }
                    .mapNotNull { it.optJSONObject("resolutions")?.optJSONObject("original")?.optString("url") }
                    .filter { it.startsWith("http") }
                val fallbackPoster = show.optJSONObject("show")?.optJSONObject("image")
                    ?.optString("original")?.takeIf { it.startsWith("http") }
                val poster = posters.firstOrNull() ?: fallbackPoster ?: continue

                // 每张横图都单独成一个候选，让用户自己挑构图。
                val backdropsOrNull = backdrops.ifEmpty { listOf(null) }
                backdropsOrNull.forEachIndexed { index, backdrop ->
                    add(
                        LibraryArtworkCandidate(
                            title = name,
                            year = showYear,
                            poster = poster,
                            backdrop = backdrop,
                            kind = MediaKind.SERIES,
                            originalTitle = null,
                            identity = LibraryWorkIdentity(
                                provider = PROVIDER,
                                id = showId.toString(),
                                mediaType = "series",
                                title = name,
                                year = showYear,
                                kind = MediaKind.SERIES.name,
                            ),
                            // 同一条目多张横图时给个序号，避免候选 key 重复被去重掉。
                            siteName = if (index > 0) "#${index + 1}" else null,
                        ),
                    )
                }
            }
        }
    }

    /** 连通性测试：返回 null 表示成功。 */
    suspend fun test(baseUrl: String, userAgent: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val shows = searchShows(baseUrl, "The Bear", userAgent)
            if (shows.isEmpty()) "未返回结果" else null
        }.getOrElse { e -> e.message?.take(120) ?: "连接失败" }
    }

    private fun searchShows(baseUrl: String, query: String, userAgent: String): List<JSONObject> {
        val body = get(
            "${baseUrl(baseUrl)}/search/shows?q=${URLEncoder.encode(query, "UTF-8")}",
            userAgent,
        )
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun showImages(baseUrl: String, showId: Int, userAgent: String): List<JSONObject> {
        val body = get("${baseUrl(baseUrl)}/shows/$showId/images", userAgent)
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun get(url: String, userAgent: String): String {
        // 客户端侧节流：官方要求 20 次/10 秒，这里串行化并保证最小间隔。
        val now = System.currentTimeMillis()
        val last = lastRequestAt.get()
        val wait = (last + MIN_INTERVAL_MS) - now
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt.set(System.currentTimeMillis())

        repeat(2) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()
            val response = http.newCall(request).execute()
            response.use {
                if (it.code == 429) {
                    // 退避后再试一次，绝不立即重发。
                    if (attempt == 0) {
                        Thread.sleep(1_500L * (attempt + 1))
                        return@repeat
                    }
                    error("TVmaze rate limited")
                }
                check(it.isSuccessful) { "TVmaze HTTP ${it.code}" }
                return it.body?.string() ?: error("TVmaze empty response")
            }
        }
        error("TVmaze request failed")
    }

    const val PROVIDER = "TVMAZE"
}
