package com.reelix.app.data.tmdb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TMDB 刮削 —— 只用来补「横版剧照」。
 *
 * ## 为什么需要它
 *
 * TVBox 源给的 `vod_pic` 几乎都是竖版海报，`vod_pic_slide`（横图字段）
 * 大部分采集站根本不填。结果是详情页/Hero 只能走「竖图模糊放大」的降级方案。
 *
 * TMDB 的 `backdrop_path` 天生就是横版（16:9），是唯一可靠的横图来源。
 *
 * ## 设计原则
 *
 * ```
 * ① 没有 key 就完全不工作 —— 不发请求、不报错、不提示
 * ② 任何失败都静默降级 —— 网络挂了、key 错了、没搜到，都返回 null
 * ③ 内存缓存 + 失败缓存 —— 同一部片不重复请求，失败的也记住
 * ```
 *
 * 第 ① 条很重要：用户不填 key 时 App 行为必须和没有这个功能一样。
 */
object TmdbClient {

    private const val TAG = "TmdbClient"

    /** 图片基础地址。image.tmdb.org 在国内可直连，不需要代理 */
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

    /** API 基础地址。这个域名需要走代理 */
    private const val API_BASE = "https://api.themoviedb.org/3"

    private val json = Json {
        ignoreUnknownKeys = true
        // Stored.version 有默认值，不开这个的话它不会被写进文件，
        // 版本校验就永远"通过"了
        encodeDefaults = true
    }

    private val client by lazy { OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build() }

    /** 搜索结果缓存：key = "片名|年份" */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, TmdbMatch>()

    /** Memory-only lookup for the first frame; never starts network or disk work. */
    fun cachedMatch(title: String, year: Int? = null, language: String = "zh-CN"): TmdbMatch? =
        cache["${cleanTitle(title)}|${year ?: 0}|$language"]

    private val cacheLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    val isCacheLoaded: Boolean get() = cacheLoaded.isCompleted

    suspend fun awaitCache(context: Context) {
        init(context)
        cacheLoaded.await()
    }

    /**
     * 没搜到的 key。
     *
     * ★ 不能把 null 塞进 ConcurrentHashMap —— 它会直接抛 NPE。
     *   2026-11-04 电视实测：详情页 TMDB 匹配失败时 `cache[key] = null`
     *   让整个 App 直接退出（堆栈落在 ConcurrentHashMap.putVal）。
     *   用单独的 miss 集合保留“失败也缓存、避免反复请求”的原设计。
     */
    private val misses: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    // ---------------- 落盘 ----------------

    /**
     * 为什么匹配结果要存磁盘。
     *
     * 只在内存里的话，App 一重启就没了 —— 每次重启后首次进详情页都要重新请求
     * TMDB API（走代理 1~3 秒），而图片本身其实还躺在 Coil 的磁盘缓存里。
     * 落盘之后重启也能直接拿到 backdrop_path，详情页就是真秒开。
     */
    private const val STORE_FILE = "tmdb-cache.json"
    private const val STORE_VERSION = 1

    /** 落盘条数比内存上限（500）略小，控制文件体积（单条约 1KB） */
    private const val MAX_PERSISTED = 400

    @Serializable
    private data class Stored(
        val version: Int = STORE_VERSION,
        val matches: Map<String, TmdbMatch> = emptyMap(),
    )

    private var appContext: Context? = null
    private var persistJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 由 ReelboxApp.onCreate 调，后台读一次文件。 */
    @Synchronized fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { try { load() } finally { cacheLoaded.complete(Unit) } }
    }

    private fun storeFile(ctx: Context) = File(ctx.filesDir, STORE_FILE)

    private fun load() {
        val ctx = appContext ?: return
        runCatching {
            val f = storeFile(ctx)
            if (!f.exists() || f.length() == 0L) return
            // 体积保护：异常大的文件不在启动路径上解析
            if (f.length() > 4 * 1024 * 1024) {
                Log.w(TAG, "TMDB 缓存过大(${f.length()}B)，跳过加载")
                return
            }
            val stored = json.decodeFromString(Stored.serializer(), f.readText(Charsets.UTF_8))
            if (stored.version != STORE_VERSION) {
                Log.w(TAG, "TMDB 缓存版本不匹配(${stored.version})，忽略")
                return
            }
            stored.matches.forEach { (k, v) ->
                if (cache.size < 500) cache[k] = v
            }
            Log.i(TAG, "TMDB 缓存加载 ${cache.size} 条")
        }.onFailure {
            Log.w(TAG, "读取 TMDB 缓存失败: ${it.message}")
        }
    }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1500)
            persist()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        runCatching {
            val subset = cache.entries
                .take(MAX_PERSISTED)
                .associate { it.key to it.value }
            val body = json.encodeToString(
                Stored.serializer(),
                Stored(version = STORE_VERSION, matches = subset),
            )
            storeFile(ctx).writeText(body, Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "写入 TMDB 缓存失败: ${it.message}")
        }
    }

    /**
     * 一条 TMDB 结果。
     *
     * 只保留我们真正用的字段 —— 完整结构见
     * `https://developer.themoviedb.org/reference/search-multi`
     */
    @Serializable
    data class TmdbMatch(
        val id: Int,
        /** 电影用 title，剧集用 name */
        val title: String,
        val originalTitle: String?,
        /** 横版剧照路径（/xxxx.jpg），可能为空 */
        val backdropPath: String?,
        /** 竖版海报路径 */
        val posterPath: String?,
        val overview: String?,
        val year: Int?,
        val rating: Double?,
        /** "movie" 或 "tv" */
        val mediaType: String,
    ) {
        /** 横图完整 URL（w1280 足够 1080p 电视用） */
        val backdropUrlW1280: String?
            get() = backdropPath?.let { "${IMAGE_BASE}w1280$it" }

        /** 横图 1920 宽（4K/大屏用） */
        val backdropUrlW1920: String?
            get() = backdropPath?.let { "${IMAGE_BASE}w1920$it" }

        /** 竖图完整 URL */
        val posterUrlW500: String?
            get() = posterPath?.let { "${IMAGE_BASE}w500$it" }
    }

    /**
     * 按片名 + 年份搜索。
     *
     * @param apiKey TMDB v3 API Key（32 位十六进制）
     * @param title 片名
     * @param year 年份（用于从多个同名结果里挑对的，可为空）
     * @param language 语言，影响标题和简介
     * @return 最佳匹配，失败/没 key/没结果都返回 null
     */
    suspend fun match(
        apiKey: String,
        title: String,
        year: Int? = null,
        language: String = "zh-CN",
    ): TmdbMatch? = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        // 没有 key 就完全不工作（设计原则 ①）
        if (key.isEmpty()) return@withContext null

        val name = cleanTitle(title)
        if (name.isEmpty()) return@withContext null
        if (appContext != null) cacheLoaded.await()

        val cacheKey = "$name|${year ?: 0}|$language"
        cache[cacheKey]?.let { return@withContext it }
        if (misses.contains(cacheKey)) return@withContext null

        val result = runCatching {
            val url = buildString {
                append(API_BASE)
                append("/search/multi")
                append("?api_key=").append(key)
                append("&language=").append(language)
                append("&query=").append(java.net.URLEncoder.encode(name, "UTF-8"))
                append("&include_adult=false")
            }

            val body = get(url) ?: return@runCatching null
            val root = json.parseToJsonElement(body).jsonObject

            val list = root["results"]?.jsonArray ?: return@runCatching null
            val candidates = list.mapNotNull { parseItem(it.jsonObject) }
            if (candidates.isEmpty()) return@runCatching null

            pickBest(candidates, name, year)
        }.getOrElse { e ->
            Log.d(TAG, "TMDB 搜索失败（静默降级）: ${e.message}")
            null
        }

        // 缓存结果；失败记进 misses，避免反复请求
        if (result != null) {
            if (cache.size < 500) cache[cacheKey] = result
            // 异步落盘，重启后不用再请求一次 API
            persistSoon()
        } else if (misses.size < 500) {
            misses.add(cacheKey)
        }
        result
    }

    /**
     * 从候选里挑最合适的。
     *
     * 优先级：
     * ```
     * ① 片名完全一致 + 年份一致
     * ② 片名完全一致
     * ③ 片名包含关系 + 年份一致
     * ④ 第一个有 backdrop 的
     * ```
     * 第 ④ 条兜底很重要：有时片名对不上，但有横图就先用着，
     * 总比没有强。
     */
    suspend fun search(apiKey: String, query: String, language: String = "zh-CN"): List<TmdbMatch> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank() && query.isNotBlank())
        fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        val url = "$API_BASE/search/multi?api_key=${encode(apiKey.trim())}&language=${encode(language)}&query=${encode(query.trim())}&include_adult=false"
        val body = get(url) ?: error("TMDB unavailable")
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
            .mapNotNull { parseItem(it.jsonObject) }.filter { it.posterPath != null }.take(20)
    }

    suspend fun libraryMetadata(apiKey: String, identity: com.reelix.app.data.image.LibraryWorkIdentity): com.reelix.app.data.image.LibraryWorkMetadata? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || identity.mediaType !in listOf("movie", "tv") || !identity.id.matches(Regex("\\d+"))) return@withContext null
        val credits = if (identity.mediaType == "tv") "aggregate_credits" else "credits"
        val encodedKey = java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")
        val body = get("$API_BASE/${identity.mediaType}/${identity.id}?api_key=$encodedKey&language=zh-CN&append_to_response=$credits") ?: return@withContext null
        com.reelix.app.data.image.parseTmdbLibraryMetadata(body, identity)
    }

    private fun pickBest(
        candidates: List<TmdbMatch>,
        query: String,
        year: Int?,
    ): TmdbMatch? {
        val q = query.lowercase()

        // ① 片名一致 + 年份一致
        if (year != null) {
            candidates.firstOrNull {
                it.title.lowercase() == q && it.year == year
            }?.let { return it }
        }

        // ② 片名一致
        candidates.firstOrNull { it.title.lowercase() == q }?.let { return it }

        // ③ 包含关系 + 年份一致
        if (year != null) {
            candidates.firstOrNull {
                (it.title.lowercase().contains(q) || q.contains(it.title.lowercase())) &&
                    it.year == year
            }?.let { return it }
        }

        // ④ 包含关系
        candidates.firstOrNull {
            it.title.lowercase().contains(q) || q.contains(it.title.lowercase())
        }?.let { return it }

        // ⑤ 有横图的优先
        return candidates.firstOrNull { it.backdropPath != null }
    }

    /** 解析单条结果 */
    private fun parseItem(obj: JsonObject): TmdbMatch? {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull ?: return null
        // search/multi 会混进 person，跳过
        if (mediaType !in listOf("movie", "tv")) return null

        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: return null

        val original = obj["original_title"]?.jsonPrimitive?.contentOrNull
            ?: obj["original_name"]?.jsonPrimitive?.contentOrNull

        val date = obj["release_date"]?.jsonPrimitive?.contentOrNull
            ?: obj["first_air_date"]?.jsonPrimitive?.contentOrNull
        val year = date?.take(4)?.toIntOrNull()

        return TmdbMatch(
            id = id,
            title = title,
            originalTitle = original?.takeIf { it.isNotBlank() },
            backdropPath = obj["backdrop_path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "null" },
            posterPath = obj["poster_path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "null" },
            overview = obj["overview"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() },
            year = year,
            rating = obj["vote_average"]?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it > 0 },
            mediaType = mediaType,
        )
    }

    /**
     * 清洗片名，提高搜索命中率。
     *
     * 源站给的片名经常带这些后缀：
     * ```
     * 「沙丘2 国语」      → 去掉语言标记
     * 「沙丘2【4K】」     → 去掉画质标记
     * 「沙丘2.2024」      → 去掉年份
     * 「凡人修仙传 年番4」 → 保留（这是正片名的一部分）
     * ```
     * 注意「年番4」这类要保留 —— 它是片名组成部分，不是噪音。
     */
    internal fun cleanTitle(raw: String): String {
        var s = raw.trim()

        // 去掉方括号/圆括号里的纯标记
        s = s.replace(Regex("[\\[【(（][^\\]】)）]{1,8}[\\]】)）]"), " ")

        // 去掉画质/语言/版本标记（前后有空格或边界）
        val noise = listOf(
            "国语", "粤语", "英语", "日语", "韩语", "中字", "中文字幕", "双语",
            "4K", "1080P", "720P", "2160P", "HDR", "蓝光", "BD", "HD",
            "高清", "超清", "完整版", "未删减", "加长版", "导演剪辑版",
            "TC", "TS", "HDTC", "枪版", "抢先版",
        )
        for (n in noise) {
            s = s.replace(Regex("(?i)\\b${Regex.escape(n)}\\b"), " ")
            s = s.replace(n, " ")
        }

        // 去掉末尾的年份 .2024 / (2024)
        s = s.replace(Regex("[.\\s]*[（(]?(19|20)\\d{2}[）)]?\\s*$"), " ")

        // 折叠空白
        s = s.replace(Regex("\\s+"), " ").trim()

        return s
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> resp.body?.string()
                resp.code == 401 -> {
                    Log.w(TAG, "API Key 无效")
                    null
                }
                else -> {
                    Log.d(TAG, "HTTP ${resp.code}")
                    null
                }
            }
        }
    }.getOrNull()

    /**
     * 清空匹配缓存。
     *
     * ★ 必须连 [misses] 一起清（2026-11-07 修）。
     *
     *   原来只清 cache：某次网络抖动导致某部片进了 misses 之后，即使之后
     *   换了正确的 key，那部片在**本次进程内**也永远不会再请求 ——
     *   表现就是"改了 key 也不生效，重启 App 才恢复"。
     */
    fun clearCache() {
        cache.clear()
        misses.clear()
        // 内存清完要立刻落盘，否则重启后旧数据又回来了
        persist()
    }
}
