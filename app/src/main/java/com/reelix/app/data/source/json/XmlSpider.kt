package com.reelix.app.data.source.json

import android.util.Log
import com.reelix.app.ReelboxApp
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.Spider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * **XML 型站点（type=0）** —— RSS 接口源。
 *
 * ## 和 JsonSpider 的差别
 *
 * 只有一点：返回的是 XML 而不是 JSON。
 * 请求参数、动作名（`ac=videolist`）**完全一样**：
 * ```
 * https://cj.lziapi.com/api.php/provide/vod/at/xml/?ac=videolist&t=15&pg=1
 * ```
 *
 * 所以两个类结构几乎镜像，只是解析器不同：
 * ```
 * JsonSpider → TvBoxJsonParser
 * XmlSpider  → XmlParser
 * ```
 *
 * ## 为什么值得单独实现
 *
 * 公开配置里 type=0 占比不小（`0821.json` 里有 2 个），
 * 而且这类源**不需要 jar、不需要登录、不需要嗅探**（多数带 m3u8 直链），
 * 是最"干净"的一类。
 */
class XmlSpider(
    private val api: String,
    override val key: String,
    private val ext: String? = null,
    override val name: String = key,
    override val searchable: Boolean = true,
    /**
     * HTTP 客户端 —— 和 [JsonSpider] 一样取全局共享实例，理由见那个类的注释。
     * 关键收益：所有 JSON/XML 型站点共用同一个连接池，
     * 打同一个 CDN 时只做一次 TLS 握手。
     */
    private val http: OkHttpClient = ReelboxApp.http() ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : Spider {

    override suspend fun home(): HomeContent = withContext(Dispatchers.IO) {
        XmlParser.parseHome(request("ac=videolist"), sourceKey = key)
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
            extend.forEach { (k, v) -> append("&").append(enc(k)).append("=").append(enc(v)) }
        }
        XmlParser.parseCategory(request(params), sourceKey = key)
    }

    override suspend fun detail(id: String): MediaDetail = withContext(Dispatchers.IO) {
        val raw = request("ac=videolist&ids=${enc(id)}")
        val parsed = XmlParser.parseDetail(raw, sourceKey = key)
        if (parsed == null) {
            MediaDetail(
                item = MediaItem(
                    id = "${key}_$id",
                    sourceId = id,
                    sourceKey = key,
                    title = "加载失败",
                )
            )
        } else {
            MediaDetail(item = parsed.first, routes = parsed.second)
        }
    }

    override suspend fun search(keyword: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val raw = request("ac=videolist&wd=${enc(keyword)}&pg=$page")
            XmlParser.parseSearch(raw, sourceKey = key)
        }

    /**
     * 播放地址。
     *
     * XML 源的 `dd` 里通常带两条线路：
     * ```
     * flag="liangzi"  → .../share/xxx   ← 分享页，上层要嗅探
     * flag="lzm3u8"   → .../index.m3u8  ← 直链，能直接播
     * ```
     * 所以这里**不做任何加工**，把 episodeId 原样返回，
     * 由上层解析链判断（`SniffRules.looksLikeMedia`）。
     */
    override suspend fun resolvePlayUrl(routeFlag: String, episodeId: String): PlayResolution =
        withContext(Dispatchers.IO) {
            PlayResolution(url = episodeId, direct = false)
        }

    // ---------- HTTP ----------

    private fun request(query: String): String? {
        val url = JsonSpider.buildUrlStatic(api, query) ?: return null

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "[$key] HTTP ${resp.code}")
                    return@use null
                }
                decode(resp.body?.bytes())
            }
        }.onFailure {
            Log.e(TAG, "[$key] 请求失败", it)
        }.getOrNull()
    }

    /** 解 gzip（同 JsonSpider 的理由：不手写 Accept-Encoding，自己兜底） */
    private fun decode(bytes: ByteArray?): String? {
        if (bytes == null) return null
        if (bytes.size < 2) return String(bytes, Charsets.UTF_8)

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

    private companion object {
        const val TAG = "XmlSpider"
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
