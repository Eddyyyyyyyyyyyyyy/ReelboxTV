package com.reelix.app.data.source.json

import android.util.Log
import com.reelix.app.ReelboxApp
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.Spider
import com.reelix.app.data.source.jar.TvBoxJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * **JSON 型站点（type=1）** —— 纯 HTTP 接口源，不需要 jar。
 *
 * ## 这是最轻的一种源
 *
 * TVBox 配置里：
 * ```json
 * {
 *   "key": "someapi",
 *   "name": "某某接口",
 *   "type": 1,
 *   "api": "https://api.example.com/vod",
 *   "ext": ""
 * }
 * ```
 * `type=1` 表示"直接用 HTTP 拿 JSON"，**没有 jar、没有解密、没有 WebView**。
 *
 * ## 协议约定
 *
 * TVBox 的 JSON 接口用 `ac` 参数区分动作：
 * ```
 * ?ac=videolist&t=分类id&pg=1     → 分类列表
 * ?ac=videolist&wd=关键词&pg=1     → 搜索
 * ?ac=videolist&ids=xxx           → 详情
 * ?ac=videolist                    → 首页
 * ```
 * 返回结构**和 spider 的输出完全一致**（`list` / `class` / `vod_*` 字段），
 * 所以解析层可以直接复用 [TvBoxJsonParser]。
 *
 * ## 为什么值得实现
 *
 * 这类源不依赖 jar 的加固/解密，是最容易跑通的一类。
 * 有些配置里 JSON 源占比不低，而之前全部被标记成"暂未支持"直接跳过。
 */
class JsonSpider(
    private val api: String,
    override val key: String,
    private val ext: String? = null,
    override val name: String = key,
    override val searchable: Boolean = true,
    /**
     * HTTP 客户端 —— 默认取 App 全局共享实例。
     *
     * ## 为什么必须共享
     *
     * 之前这里是 `OkHttpClient.Builder().build()`，**每个实例一个 client**。
     * 51 个站点的配置就是 51 个 client：各自一套 ConnectionPool（默认 5 条
     * idle 连接）、Dispatcher、RouteDatabase。后果：
     *  · 同批站点打同一 CDN 时，TLS 握手各做各的（TV 盒子上单次 300~800ms）
     *  · 255 条常驻空闲连接，tv 盒子内存和 fd 都吃紧
     *  · 失败路由不共享，同一个挂掉的域名被反复重试
     *
     * 改成 `ReelboxApp.spiderHttp`（从全局 client `newBuilder()` 派生）后
     * **共享连接池**，只是超时策略不同。
     *
     * 保留参数是为了单测能注入 MockWebServer。
     */
    private val http: OkHttpClient = ReelboxApp.http() ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : Spider {

    /** 内部别名：老代码里用 siteKey 更清楚它是"站点标识" */
    private val siteKey: String get() = key

    override suspend fun home(): HomeContent = withContext(Dispatchers.IO) {
        val raw = request("ac=videolist")
        TvBoxJsonParser.parseHome(raw, sourceKey = siteKey)
    }

    override suspend fun category(
        categoryId: String,
        page: Int,
        extend: Map<String, String>,
    ): CategoryContent = withContext(Dispatchers.IO) {
        val params = buildString {
            append("ac=videolist")
            append("&t=").append(enc(categoryId))
            append("&pg=").append(page)
            // ext 里的筛选项（年份/地区/类型）直接透传
            extend.forEach { (k, v) -> append("&").append(enc(k)).append("=").append(enc(v)) }
        }
        val raw = request(params)
        TvBoxJsonParser.parseCategory(raw, sourceKey = siteKey)
    }

    override suspend fun detail(id: String): MediaDetail = withContext(Dispatchers.IO) {
        val raw = request("ac=videolist&ids=${enc(id)}")
        val parsed = TvBoxJsonParser.parseDetail(raw, sourceKey = siteKey)
        if (parsed == null) {
            MediaDetail(item = MediaItem(
                id = "${siteKey}_$id",
                sourceId = id,
                sourceKey = siteKey,
                title = "加载失败",
            ))
        } else {
            MediaDetail(item = parsed.first, routes = parsed.second)
        }
    }

    override suspend fun search(keyword: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val raw = request("ac=videolist&wd=${enc(keyword)}&pg=$page")
            TvBoxJsonParser.parseSearch(raw, sourceKey = siteKey)
        }

    /**
     * 播放地址解析。
     *
     * JSON 型站点的 `vod_play_url` 里给的**通常就是直链**（因为这类接口
     * 后面往往接的是采集站），但也可能给播放页。这里不做特殊处理 ——
     * 返回原地址，由上层的解析链（接口 → 嗅探）决定怎么处理。
     */
    override suspend fun resolvePlayUrl(routeFlag: String, episodeId: String): PlayResolution =
        withContext(Dispatchers.IO) {
            // 直接问接口要一次（部分接口支持 ac=play 直接给直链）
            val raw = runCatching { request("ac=play&flag=${enc(routeFlag)}&id=${enc(episodeId)}") }
                .getOrNull()
            TvBoxJsonParser.parsePlayer(raw)
                ?.let { p ->
                    PlayResolution(
                        url = p.url,
                        headers = p.headers,
                        direct = p.direct,
                        parserName = p.parserName,
                        msg = p.msg,
                        playUrl = p.playUrl,
                        requiresParsing = !p.direct,
                    )
                }
                // 接口不支持 ac=play 时，把 episodeId 原样返回给解析链
                ?: PlayResolution(url = episodeId, direct = com.reelix.app.data.source.jar.UrlHeader.looksLikeMedia(episodeId))
        }

    // ---------- HTTP ----------

    /**
     * 请求接口。
     *
     * ## 为什么手动处理 gzip
     *
     * 部分老接口不管请求头怎么写都返回 gzip 字节，
     * OkHttp 的解压只在**它自己加了 Accept-Encoding 时**才生效；
     * 一旦我们在请求头里手写了 `Accept-Encoding: gzip`，
     * `resp.body.string()` 拿到的会是**乱码**。
     * 所以这里统一不写 Accept-Encoding，并自己兜底解 gzip。
     */
    private fun request(query: String): String? {
        val url = buildUrl(api, query) ?: return null

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "[$siteKey] HTTP ${resp.code}: $url")
                    return@use null
                }
                val bytes = resp.body?.bytes() ?: return@use null
                decodeBody(bytes)
            }
        }.onFailure {
            Log.e(TAG, "[$siteKey] 请求失败: $url", it)
        }.getOrNull()
    }

    /** 解 gzip 并转字符串（UTF-8） */
    private fun decodeBody(bytes: ByteArray): String {
        if (bytes.size < 2) return String(bytes, Charsets.UTF_8)

        // gzip magic: 1f 8b
        val isGzip = bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!isGzip) return String(bytes, Charsets.UTF_8)

        return runCatching {
            GZIPInputStream(bytes.inputStream()).use { gz ->
                val out = ByteArrayOutputStream()
                gz.copyTo(out)
                String(out.toByteArray(), Charsets.UTF_8)
            }
        }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    internal companion object {
        /**
         * 静态版 URL 拼接 —— 给 XmlSpider 复用。
         *
         * 两个类的接口地址形态完全一样（都可能已带 `?`），
         * 所以拼接逻辑只写一份。
         */
        fun buildUrlStatic(base: String, query: String): String? {
            val b = base.trim()
            if (b.isEmpty()) return null
            return when {
                b.endsWith("?") -> b + query
                b.contains('?') -> "$b&$query"
                else -> "$b?$query"
            }
        }

        const val TAG = "JsonSpider"
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    /**
     * 拼接口地址（纯函数，可单测）。
     *
     * 三种形态都要支持：
     * ```
     * https://api.com/vod          → https://api.com/vod?ac=videolist
     * https://api.com/vod?key=abc  → https://api.com/vod?key=abc&ac=videolist
     * https://api.com/vod?          → https://api.com/vod?ac=videolist
     * ```
     */
    internal fun buildUrl(base: String, query: String): String? {
        val b = base.trim()
        if (b.isEmpty()) return null

        return when {
            b.endsWith("?") -> b + query
            b.contains('?') -> "$b&$query"
            else -> "$b?$query"
        }
    }

    /**
     * 解 gzip（纯函数，可单测）。
     *
     * 为什么手动处理：部分老接口不管请求头怎么写都返回 gzip 字节，
     * 而 OkHttp 只在**它自己加了 Accept-Encoding 时**才自动解压。
     * 一旦在请求头里手写 `Accept-Encoding: gzip`，
     * `resp.body.string()` 拿到的是乱码。
     */
    internal fun decodeBodyForTest(bytes: ByteArray): String = decodeBody(bytes)
}
