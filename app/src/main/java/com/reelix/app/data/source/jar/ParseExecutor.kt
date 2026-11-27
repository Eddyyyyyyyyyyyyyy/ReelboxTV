package com.reelix.app.data.source.jar

import android.util.Log
import com.reelix.app.data.source.TvBoxParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HTTP 解析接口执行器 —— 把播放页地址换成直链。
 *
 * ## 为什么需要（嗅探之前的快速通道）
 *
 * `WebViewSniffer` 能解决"页面里藏地址"，但代价是**每次 3~12 秒**。
 * 而 TVBox 配置里的 `parses[]` 就是**专门用来做这件事的 HTTP 接口**：
 * ```
 * GET https://jx.example.com/?url=https%3A%2F%2Fv.qq.com%2Fx
 * → {"url":"https://cdn.example.com/a.m3u8","header":{...}}
 * ```
 * 一次 HTTP 约 500ms，比开 WebView 快一个数量级。
 *
 * ## 策略链
 *
 * ```
 * ① 本来就是媒体              → 直接用
 * ② parses 里有匹配的解析器   → 调它（快）      ← 本文件
 * ③ 接口失败/没有匹配         → WebView 嗅探（慢但兜得住）
 * ④ 都失败                    → 报错
 * ```
 *
 * ## 解析器类型
 *
 * ```
 * type = 1  普通 JSON 接口（本文件处理）
 * type = 2  带额外参数的接口（同 1，只是 URL 拼接不同）
 * type = 3  需要 WebView 的（转给嗅探）
 * type = 0  嗅探型（转给嗅探）
 * ```
 */
object ParseExecutor {

    private const val TAG = "ParseExecutor"

    data class Result(
        val url: String?,
        val headers: Map<String, String> = emptyMap(),
        val error: String? = null,
        val parserName: String? = null,
        val elapsedMs: Long = 0,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 尝试用配置里的解析器解析。
     *
     * @param pageUrl 播放页地址
     * @param lineFlag 线路名（部分解析器按线路名区分）
     * @param parses 配置里的解析器列表
     */
    suspend fun resolve(
        pageUrl: String,
        lineFlag: String?,
        parses: List<TvBoxParse>,
    ): Result = withContext(Dispatchers.IO) {
        if (pageUrl.isBlank()) return@withContext Result(null, error = "地址为空")
        if (parses.isEmpty()) return@withContext Result(null, error = "配置里没有解析器")

        // 只挑 HTTP 型（type 1/2）；嗅探型交给 WebViewSniffer
        val httpParsers = parses.filter { (it.type == 1 || it.type == 2) &&
            (it.flag.isEmpty() || lineFlag in it.flag) }
        if (httpParsers.isEmpty()) {
            return@withContext Result(null, error = "没有 HTTP 型解析器")
        }

        // 按线路名挑：名字能对上就优先，否则全部按顺序试
        val ordered = httpParsers.sortedByDescending { p ->
            val n = p.name
            if (lineFlag != null && n.isNotBlank() && lineFlag.contains(n, ignoreCase = true)) 1 else 0
        }

        var lastError: String? = null

        for (p in ordered) {
            currentCoroutineContext().ensureActive()
            val started = System.currentTimeMillis()
            val req = buildUrl(p, pageUrl)
            if (req.isBlank()) continue

            // 注意用 try/catch 而不是 runCatching：
            // runCatching 是 inline lambda，里面不能写 continue。
            val body: String? = try {
                val r = Request.Builder()
                    .url(req)
                    .header("User-Agent", UA)
                    .apply { parserRequestHeaders(p).forEach { (name, value) -> header(name, value) } }
                    .build()
                http.newCall(r).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.string()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${p.name}: ${e.message}"
                Log.d(TAG, "解析器 ${p.name} 失败: ${e.message}")
                continue
            }
            currentCoroutineContext().ensureActive()

            val parsed = parseResponse(body)
            val elapsed = System.currentTimeMillis() - started

            if (parsed?.first != null) {
                Log.i(TAG, "解析器 ${p.name} 成功 (${elapsed}ms): ${parsed.first.take(80)}")
                return@withContext Result(
                    url = parsed.first,
                    headers = parsed.second,
                    parserName = p.name,
                    elapsedMs = elapsed,
                )
            }

            lastError = "${p.name}: 未返回地址"
        }

        Log.w(TAG, "所有 HTTP 解析器都没成功: $lastError")
        Result(null, error = lastError ?: "所有解析器都失败")
    }

    /**
     * 拼解析请求地址。
     *
     * 两种常见写法：
     * ```
     * https://jx.com/?url=            → 直接拼在后面
     * https://jx.com/jx.php?url=      → 同上
     * https://jx.com/api?key=x&url=   → 已有 query，用 &
     * ```
     */
    internal fun buildUrl(parser: TvBoxParse, pageUrl: String): String {
        val base = parser.url.trim()
        if (base.isBlank()) return ""

        val encoded = java.net.URLEncoder.encode(pageUrl, "UTF-8")
        return when {
            base.contains("{url}") -> base.replace("{url}", encoded)
            base.endsWith("=") || base.endsWith("?") -> base + encoded
            base.contains('?') -> "$base&url=$encoded"
            else -> "$base?url=$encoded"
        }
    }

    /**
     * 解析响应。
     *
     * 兼容三种形态：
     * ```
     * {"url":"https://...","header":{...}}     标准
     * {"data":{"url":"https://..."}}           包一层
     * {"code":200,"url":"https://..."}         带 code
     * ```
     */
    internal fun parseResponse(body: String?): Pair<String, Map<String, String>>? {
        if (body.isNullOrBlank()) return null

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null

        // 标准位置
        (root["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }?.let { u ->
            val split = UrlHeader.split(u)
            return split.url to combinePlaybackHeaders(headersOf(root), split.headers)
        }

        // 包一层 data
        runCatching {
            val data = root["data"]?.jsonObject ?: return@runCatching
            (data["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }
                ?.let {
                    val split = UrlHeader.split(it)
                    return split.url to combinePlaybackHeaders(headersOf(root), headersOf(data), split.headers)
                }
        }

        return null
    }

    private fun headersOf(obj: JsonObject): Map<String, String> {
        return playbackHeaderObject(obj["header"])
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
