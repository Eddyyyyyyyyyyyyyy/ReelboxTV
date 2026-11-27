package com.reelix.app.data.douban

import com.reelix.app.data.image.ImageHeaderRegistry
import com.reelix.app.data.image.LibraryArtworkCandidate
import com.reelix.app.data.image.LibraryPersonCredit
import com.reelix.app.data.image.LibraryWorkIdentity
import com.reelix.app.data.image.LibraryWorkMetadata
import com.reelix.app.data.image.artworkTitle
import com.reelix.app.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 豆瓣资料源。
 *
 * 实测约束（2026-11-23）：
 * - JSON 搜索约 10 次/2 分钟即 403，且按 IP 限制，不能用于批量刮削；
 *   所以这里只用 HTML 搜索（宽松得多），片库批量也不会调用豆瓣。
 * - subject / photos / credits 在 403 期间仍可用。
 * - photos 的 type 参数被忽略，必须自己按宽高比过滤横图。
 * - 图片 CDN 必须带 Referer: https://movie.douban.com/，否则 418。
 */
object DoubanClient {

    const val DEFAULT_BASE_URL = "https://m.douban.com"
    private const val IMAGE_BASE = "https://movie.douban.com"
    private const val SEARCH_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.40"
    private const val IMAGE_REFERER = "https://movie.douban.com/"
    private const val MAX_BODY = 2 * 1024 * 1024
    private const val SEARCH_INTERVAL_MS = 1_500L
    private const val DETAIL_INTERVAL_MS = 800L

    private val lastSearchAt = AtomicLong(0L)
    private val lastDetailAt = AtomicLong(0L)
    private val cooldownUntil = AtomicLong(0L)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
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

    /** 搜索结果的最小信息；年份/评分要再查 subject 才有。 */
    data class SearchHit(val id: String, val title: String, val poster: String?)

    suspend fun search(baseUrl: String, title: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val query = title.trim()
        if (query.isEmpty()) return@withContext emptyList()
        throttle(lastSearchAt, SEARCH_INTERVAL_MS)
        // 不带 type：带 type=movie 时剧集/综艺结果会被过滤，片库里的综艺就搜不到。
        val url = "${baseUrl(baseUrl)}/search/?query=${URLEncoder.encode(query, "UTF-8")}"
        val html = getText(url, SEARCH_UA, "https://m.douban.com/") ?: return@withContext emptyList()
        parseSearchHtml(html)
    }

    suspend fun subject(baseUrl: String, id: String, kindHint: MediaKind): DoubanSubject? =
        withContext(Dispatchers.IO) {
            val order = when (kindHint) {
                MediaKind.MOVIE -> listOf("movie")
                MediaKind.SERIES -> listOf("tv", "movie")
                else -> listOf("movie", "tv")
            }
            for (type in order) {
                throttle(lastDetailAt, DETAIL_INTERVAL_MS)
                val root = getJson(
                    "${baseUrl(baseUrl)}/rexxar/api/v2/$type/$id",
                    "https://m.douban.com/movie/subject/$id/",
                ) ?: continue
                parseSubject(root, id, type)?.let { return@withContext it }
            }
            null
        }

    suspend fun photos(baseUrl: String, subject: DoubanSubject): List<LibraryArtworkCandidate> =
        withContext(Dispatchers.IO) {
            val path = if (subject.kind == MediaKind.MOVIE) "movie" else "tv"
            throttle(lastDetailAt, DETAIL_INTERVAL_MS)
            val root = getJson(
                "${baseUrl(baseUrl)}/rexxar/api/v2/$path/${subject.id}/photos?count=20&start=0",
                "https://m.douban.com/movie/subject/${subject.id}/",
            ) ?: return@withContext emptyList()
            parsePhotos(root, subject)
        }

    suspend fun credits(baseUrl: String, subject: DoubanSubject): List<LibraryPersonCredit> =
        withContext(Dispatchers.IO) {
            val path = if (subject.kind == MediaKind.MOVIE) "movie" else "tv"
            throttle(lastDetailAt, DETAIL_INTERVAL_MS)
            val root = getJson(
                "${baseUrl(baseUrl)}/rexxar/api/v2/$path/${subject.id}/credits",
                "https://m.douban.com/movie/subject/${subject.id}/",
            ) ?: return@withContext emptyList()
            parseCredits(root)
        }

    /**
     * 手动换海报的候选列表。
     *
     * 控制请求量：HTML 搜索 1 次 + 前 3 条各查 subject + 仅第 1 条查 photos，
     * 总共不超过 5 次，避免触发豆瓣限流。
     */
    suspend fun searchCandidates(baseUrl: String, title: String, kind: MediaKind): List<LibraryArtworkCandidate> =
        withContext(Dispatchers.IO) {
            val hits = search(baseUrl, title)
            if (hits.isEmpty()) return@withContext emptyList()
            val wanted = artworkTitle(title)
            val ordered = hits.sortedByDescending { artworkTitle(it.title) == wanted }.take(3)
            val out = mutableListOf<LibraryArtworkCandidate>()
            ordered.forEachIndexed { index, hit ->
                val subject = subject(baseUrl, hit.id, kind) ?: return@forEachIndexed
                val backdrops = if (index == 0) photos(baseUrl, subject).mapNotNull { it.backdrop } else emptyList()
                val poster = subject.poster ?: hit.poster?.let(::largePosterUrl)
                if (poster.isNullOrBlank()) return@forEachIndexed
                if (backdrops.isEmpty()) {
                    out += LibraryArtworkCandidate(
                        title = subject.title,
                        year = subject.year,
                        poster = poster,
                        backdrop = null,
                        kind = subject.kind,
                        originalTitle = subject.originalTitle,
                        identity = subject.identity(),
                        overview = subject.overview,
                    )
                } else {
                    backdrops.forEach { backdrop ->
                        out += LibraryArtworkCandidate(
                            title = subject.title,
                            year = subject.year,
                            poster = poster,
                            backdrop = backdrop,
                            kind = subject.kind,
                            originalTitle = subject.originalTitle,
                            identity = subject.identity(),
                            overview = subject.overview,
                        )
                    }
                }
            }
            out.distinctBy { "${it.identity}|${it.poster}|${it.backdrop}" }
        }

    /** 详情页/手动换图：搜索 → subject → photos/credits，一次拿齐资料。 */
    suspend fun lookup(baseUrl: String, title: String, year: Int?, kind: MediaKind,
        needBackdrops: Boolean = true, needCredits: Boolean = true): DoubanSubject? =
        withContext(Dispatchers.IO) {
            val hits = search(baseUrl, title)
            if (hits.isEmpty()) return@withContext null
            val wanted = com.reelix.app.data.image.artworkTitle(title)
            val ordered = hits.sortedByDescending { com.reelix.app.data.image.artworkTitle(it.title) == wanted }
            for (hit in ordered.take(3)) {
                val subject = subject(baseUrl, hit.id, kind) ?: continue
                if (!subject.matches(title, year, kind)) continue
                val personCredits = if (needCredits) credits(baseUrl, subject) else emptyList()
                val photoCandidates = if (needBackdrops) photos(baseUrl, subject) else emptyList()
                return@withContext subject.copy(
                    credits = personCredits,
                    backdrops = photoCandidates.mapNotNull { it.backdrop },
                )
            }
            null
        }

    suspend fun test(baseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val hits = search(baseUrl, "流浪地球2")
            when {
                hits.isEmpty() -> "请求成功，但没有返回结果"
                hits.first().id.isBlank() -> "返回了结果，但缺少 subject id"
                else -> null
            }
        }.getOrElse { e -> e.message?.take(120) ?: "连接失败" }
    }

    /**
     * 已知 subject id 时直接取资料，不再做 HTML 搜索。
     *
     * 详情页和片库补资料都走这里：一次 subject + 一次 credits，控制请求量。
     */
    suspend fun metadataById(baseUrl: String, id: String, kind: MediaKind,
        needCredits: Boolean = true): LibraryWorkMetadata? = withContext(Dispatchers.IO) {
        val subject = subject(baseUrl, id, kind) ?: return@withContext null
        val personCredits = if (needCredits) credits(baseUrl, subject) else emptyList()
        subject.copy(credits = personCredits).toMetadata()
    }

    // ---------------- 解析（纯函数，可单测） ----------------

    fun parseSearchHtml(html: String): List<SearchHit> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href*=/movie/subject/]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            val id = Regex("/movie/subject/(\\d+)").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = anchor.selectFirst("span.subject-title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val poster = anchor.selectFirst("img")?.attr("src")?.takeIf { isUsableImage(it) }
            SearchHit(id, title, poster)
        }.distinctBy { it.id }
    }

    fun parseSubject(root: JSONObject, id: String, mediaType: String): DoubanSubject? {
        val title = root.optString("title").trim()
        if (title.isEmpty()) return null
        val kind = when {
            mediaType == "movie" -> MediaKind.MOVIE
            mediaType == "tv" -> MediaKind.SERIES
            else -> MediaKind.UNKNOWN
        }
        val aka = root.optJSONArray("aka")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val rating = root.optJSONObject("rating")?.optDouble("value", 0.0)?.takeIf { it > 0 }
        val poster = root.optJSONObject("pic")?.optString("large")?.takeIf { isUsableImage(it) }
            ?.let(::largePosterUrl)
        val directors = root.optJSONArray("directors")?.let { array ->
            (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
            }
        }.orEmpty()
        val actors = root.optJSONArray("actors")?.let { array ->
            (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
            }
        }.orEmpty()
        val durations = root.optJSONArray("durations")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return DoubanSubject(
            id = id,
            title = title,
            originalTitle = root.optString("original_title").takeIf { it.isNotBlank() },
            aka = aka,
            year = root.optString("year").take(4).toIntOrNull()
                ?: root.optString("pubdate").take(4).toIntOrNull(),
            kind = kind,
            rating = rating,
            poster = poster,
            overview = root.optString("intro").takeIf { it.isNotBlank() },
            countries = root.optJSONArray("countries")?.toStringList().orEmpty(),
            genres = root.optJSONArray("genres")?.toStringList().orEmpty(),
            languages = root.optJSONArray("languages")?.toStringList().orEmpty(),
            episodes = root.optInt("episodes_count", 0).takeIf { it > 0 },
            durationMinutes = durations.firstNotNullOfOrNull { parseMinutes(it) },
            directors = directors,
            actors = actors,
        )
    }

    fun parsePhotos(root: JSONObject, subject: DoubanSubject): List<LibraryArtworkCandidate> {
        val photos = root.optJSONArray("photos") ?: return emptyList()
        val out = mutableListOf<LibraryArtworkCandidate>()
        for (i in 0 until photos.length()) {
            val photo = photos.optJSONObject(i) ?: continue
            val image = photo.optJSONObject("image") ?: continue
            val large = image.optJSONObject("large") ?: continue
            val url = large.optString("url").takeIf { isUsableImage(it) } ?: continue
            val width = large.optInt("width", 0)
            val height = large.optInt("height", 0)
            // 只取真正的横图，排除竖图和接近方形的剧照。
            if (width < 900 || height <= 0 || width <= height * 1.2) continue
            val description = photo.optString("description").trim()
            if (looksLikeNoise(description)) continue
            ImageHeaderRegistry.put(url, mapOf("Referer" to IMAGE_REFERER))
            out += LibraryArtworkCandidate(
                title = subject.title,
                year = subject.year,
                poster = subject.poster.orEmpty(),
                backdrop = url,
                kind = subject.kind,
                originalTitle = subject.originalTitle,
                identity = subject.identity(),
            )
        }
        return out.distinctBy { it.backdrop }
    }

    fun parseCredits(root: JSONObject): List<LibraryPersonCredit> {
        val items = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                val avatar = item.optJSONObject("avatar")?.optString("large")
                    ?.takeIf { isUsableImage(it) }
                    ?.also { ImageHeaderRegistry.put(it, mapOf("Referer" to IMAGE_REFERER)) }
                val role = item.optString("simple_character").takeIf { it.isNotBlank() }
                    ?: item.optString("character").takeIf { it.isNotBlank() }
                add(LibraryPersonCredit(name, role, avatar))
            }
        }.distinctBy { it.name }
    }

    // ---------------- 网络 ----------------

    private fun throttle(last: AtomicLong, interval: Long) {
        val now = System.currentTimeMillis()
        val cool = cooldownUntil.get()
        if (cool > now) error("豆瓣请求过于频繁，请稍后重试")
        val wait = (last.get() + interval) - now
        if (wait > 0) Thread.sleep(wait)
        last.set(System.currentTimeMillis())
    }

    private fun getText(url: String, ua: String, referer: String): String? {
        val response = request(url, ua, referer)
        response.use {
            if (!it.isSuccessful) return null
            val bytes = it.body?.bytes() ?: return null
            if (bytes.size > MAX_BODY) return null
            return String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * 探测式请求：404 不是错误，只是"这个 type 不适用"。
     * 只有限流才抛异常，让上层停止继续打豆瓣。
     */
    private fun request(url: String, ua: String, referer: String): okhttp3.Response {
        val request = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val response = http.newCall(request).execute()
        if (response.code == 403 || response.code == 429) {
            response.close()
            cooldownUntil.set(System.currentTimeMillis() + 120_000L)
            error("豆瓣限流（HTTP ${response.code}）")
        }
        return response
    }

    private fun getJson(url: String, referer: String): JSONObject? {
        val text = getText(url, SEARCH_UA, referer) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun isUsableImage(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (url.contains("movie-static/pics/", ignoreCase = true)) return false
        return true
    }

    /**
     * 搜索返回的封面带签名 query，直接下载只有 120×169。
     * 去掉 query 并改走大图路径才能拿到可用分辨率。
     */
    private fun largePosterUrl(url: String): String {
        val clean = url.substringBefore('?')
        return when {
            clean.contains("/m_ratio_poster/") -> clean.replace("/m_ratio_poster/", "/l_ratio_poster/")
            clean.contains("/s_ratio_poster/") -> clean.replace("/s_ratio_poster/", "/l_ratio_poster/")
            else -> clean
        }.also { ImageHeaderRegistry.put(it, mapOf("Referer" to IMAGE_REFERER)) }
    }

    private fun looksLikeNoise(description: String): Boolean {
        if (description.isBlank()) return false
        val noise = listOf("发布会", "首映", "红毯", "花絮", "幕后", "路演", "活动现场")
        return noise.any { description.contains(it) }
    }

    private fun parseMinutes(value: String): Int? {
        val match = Regex("(\\d+)").find(value) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}

data class DoubanSubject(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val aka: List<String> = emptyList(),
    val year: Int? = null,
    val kind: MediaKind = MediaKind.UNKNOWN,
    val rating: Double? = null,
    val poster: String? = null,
    val overview: String? = null,
    val countries: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val episodes: Int? = null,
    val durationMinutes: Int? = null,
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val credits: List<LibraryPersonCredit> = emptyList(),
    val backdrops: List<String> = emptyList(),
) {
    fun identity(): LibraryWorkIdentity = LibraryWorkIdentity(
        provider = "DOUBAN",
        id = id,
        mediaType = if (kind == MediaKind.MOVIE) "movie" else "tv",
        title = title,
        originalTitle = originalTitle ?: aka.firstOrNull(),
        year = year,
        kind = kind.name,
    )

    fun toMetadata(): LibraryWorkMetadata = LibraryWorkMetadata(
        identity = identity(),
        overview = overview,
        directors = directors,
        cast = if (credits.isNotEmpty()) credits else actors.map { LibraryPersonCredit(it) },
        poster = poster,
        backdrop = backdrops.firstOrNull(),
        rating = rating,
        ratingSource = if (rating != null) "豆瓣" else null,
    )

    fun matches(title: String, year: Int?, kind: MediaKind): Boolean {
        val wanted = artworkTitle(title)
        val names = (listOf(this.title, originalTitle.orEmpty()) + aka)
            .map(::artworkTitle)
            .filter { it.isNotBlank() }
        if (wanted.isBlank() || wanted !in names) return false
        if (year != null && this.year != null && kotlin.math.abs(year - this.year) > 1) return false
        if (kind != MediaKind.UNKNOWN && this.kind != MediaKind.UNKNOWN &&
            (kind == MediaKind.MOVIE) != (this.kind == MediaKind.MOVIE)
        ) return false
        return true
    }
}
