package com.reelix.app.data.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

/**
 * 订阅地址解析：从用户填的任意地址，挖出真正的 TVBox 配置。
 *
 * 实测案例（2026-11-02）：
 * http://www.饭太硬.cc/tv
 * 返回的是「100x100 JPEG 图片 + 双星号 + base64 编码的 JSON」。
 * 直接当 JSON 读会报 Unexpected JSON token，用户看不出这个地址其实可用。
 *
 * 解析策略：
 *   1. 纯 JSON（含 sites）
 *   2. 图片或垃圾数据 + 双星号 + base64
 *   3. base64 前缀
 *   4. 整段 base64
 * 都不是就挖 HTML 里的链接（重点是 data-clipboard-text），再递归。
 *
 * 防护：请求预算 12 次 / 2MB / 30 秒，visited 防循环，深度上限 3 层。
 */
object SubscriptionResolver {

    private const val TAG = "SubscriptionResolver"
    private const val MAX_DEPTH = 3
    private const val MAX_REQUESTS = 12
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_MS = 30_000L

    private val USER_AGENTS = listOf(
        "okhttp/4.12.0",
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    sealed interface Result {
        data class Config(val json: String, val fromUrl: String) : Result
        data class Choices(val options: List<Choice>) : Result
        data class Failed(val reason: String) : Result
    }

    data class Choice(val url: String, val label: String, val originUrl: String? = null)

    private sealed interface FetchAttempt {
        data class Success(val body: ByteArray, val finalUrl: String) : FetchAttempt
        data class RetryWithNextUserAgent(val reason: String) : FetchAttempt
        data class Failed(val reason: String) : FetchAttempt
    }

    private class Budget {
        var requests = 0
        var bytes = 0
        val startedAt = System.currentTimeMillis()
        val visited = mutableSetOf<String>()

        fun remainingMs(): Long =
            (MAX_TOTAL_MS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)

        fun canRequest(): Boolean = requests < MAX_REQUESTS && bytes < MAX_BYTES && remainingMs() > 0L
    }

    suspend fun resolve(url: String): Result = withContext(Dispatchers.IO) {
        walk(url.trim(), 0, Budget())
    }

    private suspend fun walk(url: String, depth: Int, budget: Budget): Result {
        if (depth > MAX_DEPTH) {
            return Result.Failed("解析层数超过 $MAX_DEPTH 层，可能是死循环")
        }
        if (!budget.visited.add(url)) {
            return Result.Failed("地址循环引用：$url")
        }
        if (!budget.canRequest()) {
            return Result.Failed("请求次数或流量超限（已试 ${budget.requests} 次）")
        }

        var fetched: FetchAttempt.Success? = null
        var retryReason: String? = null
        for (ua in USER_AGENTS) {
            when (val attempt = fetch(url, ua, budget)) {
                is FetchAttempt.Success -> {
                    fetched = attempt
                    break
                }
                is FetchAttempt.RetryWithNextUserAgent -> retryReason = attempt.reason
                is FetchAttempt.Failed -> return Result.Failed(attempt.reason)
            }
        }
        val success = fetched ?: return Result.Failed(retryReason ?: "无法访问 $url")
        val body = success.body
        val finalUrl = success.finalUrl
        budget.visited.add(finalUrl)

        val head = body.take(8).joinToString(" ") { "%02X".format(it) }
        val markerAt = findMarker(body)
        Log.i(TAG, "第 ${depth + 1} 层：${body.size} 字节，头=$head，标记位置=$markerAt")

        decode(body, finalUrl)?.let { json ->
            Log.i(TAG, "第 ${depth + 1} 层拿到配置（${json.length} 字符）")
            return Result.Config(json, finalUrl)
        }

        val text = runCatching { String(body, Charsets.UTF_8) }.getOrElse { "" }

        when (val shape = TvBoxCrypto.classify(text)) {
            is TvBoxCrypto.Shape.Depot -> {
                val candidates = LinkedHashMap<String, Choice>()
                for (entry in shape.entries) {
                    val rawUrl = entry.url
                    val childUrl = when {
                        rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true) -> rawUrl
                        rawUrl.startsWith("./") || rawUrl.startsWith("../") || rawUrl.startsWith("/") ->
                            ConfigPaths.resolve(finalUrl, rawUrl)
                        else -> ConfigPaths.resolve(finalUrl, "./$rawUrl")
                    }
                    if (childUrl.isNotBlank() && childUrl != finalUrl) {
                        candidates[childUrl] = Choice(childUrl, entry.name, originUrl = finalUrl)
                    }
                }

                val nestedChoices = LinkedHashMap<String, Choice>()
                for (candidate in candidates.values) {
                    if (!budget.canRequest()) break
                    Log.i(TAG, "第 ${depth + 1} 层仓库候选：${candidate.url}")
                    when (val result = walk(candidate.url, depth + 1, budget)) {
                        is Result.Config -> return result
                        is Result.Choices -> result.options.forEach { choice ->
                            if (!nestedChoices.containsKey(choice.url)) nestedChoices[choice.url] = choice
                        }
                        is Result.Failed -> Unit
                    }
                }

                return Result.Choices(
                    if (nestedChoices.isNotEmpty()) nestedChoices.values.toList()
                    else candidates.values.toList(),
                )
            }
            is TvBoxCrypto.Shape.Message -> {
                return Result.Failed(shape.text)
            }
            else -> Unit
        }

        val found = candidates(text, finalUrl)
        Log.i(TAG, "第 ${depth + 1} 层不是配置，挖到 ${found.size} 个候选")

        if (found.isEmpty()) {
            return Result.Failed(
                buildString {
                    append("这个地址返回的不是 TVBox 配置，也找不到可用的子地址。\n")
                    append("返回内容开头：")
                    append(text.take(80).replace(Regex("\\s+"), " ").trim())
                }
            )
        }

        if (found.size == 1) {
            return walk(found.first().url, depth + 1, budget)
        }

        for (c in found.take(6)) {
            if (!budget.canRequest()) break
            val r = walk(c.url, depth + 1, budget)
            if (r is Result.Config) return r
        }

        return Result.Choices(found)
    }

    /** 在字节数组里找最后一个双星号的位置 */
    private fun findMarker(bytes: ByteArray): Int {
        for (i in bytes.size - 2 downTo 0) {
            if (bytes[i] == 0x2A.toByte() && bytes[i + 1] == 0x2A.toByte()) return i
        }
        return -1
    }

    /**
     * 判断这段字节是不是 TVBox 配置。判定标准是含 sites 数组，
     * 不是能解析成 JSON 就行。
     */
    /**
     * @param baseUrl 配置的地址，用于把相对路径（./csp.jar）补成绝对地址
     * @param pkKey  AES 密钥。有些配置是 AES-ECB 加密的 hex，
     *               密钥通常在订阅页或群公告里给出，由上层传入
     */
    internal fun decode(
        bytes: ByteArray,
        baseUrl: String? = null,
        pkKey: String? = null,
    ): String? {
        if (bytes.isEmpty()) return null
        val r = decodeRaw(bytes, pkKey) ?: return null
        return baseUrl?.let { TvBoxCrypto.normalizePaths(r, it) } ?: r
    }

    private fun decodeRaw(bytes: ByteArray, pkKey: String? = null): String? {

        val text = runCatching {
            String(bytes, Charsets.UTF_8).trim().removePrefix("\uFEFF")
        }.getOrElse { "" }

        config(text)?.let { return it }

        // 直接在字节数组里找双星号，不经过 String 转换：
        // JPEG 里有大量非法 UTF-8 字节，先转字符串再匹配容易出岔子。
        val markerAt = findMarker(bytes)
        if (markerAt >= 0 && markerAt + 2 < bytes.size) {
            val tail = bytes.copyOfRange(markerAt + 2, bytes.size)
            decodeBase64(String(tail, Charsets.US_ASCII))?.let { return it }
        }

        if (text.startsWith("base64://")) {
            decodeBase64(text.removePrefix("base64://"))?.let { return it }
        }

        if (text.length >= 16 && text.matches(Regex("[A-Za-z0-9+/=\\s]+"))) {
            decodeBase64(text)?.let { return it }
        }

        // ---- 5. AES-ECB 加密的 hex（有 pkKey 才试）----
        //
        // 有些订阅把配置用 AES-ECB 加密成一长串 hex，密钥在订阅页公布。
        // 没给 key 时跳过，不会误判。
        if (!pkKey.isNullOrBlank() && text.matches(Regex("[0-9a-fA-F\\s]{32,}"))) {
            runCatching {
                TvBoxCrypto.ecbDecrypt(text.trim(), pkKey)
            }.getOrNull()?.let { plain ->
                config(plain)?.let { return it }
            }
        }

        return null
    }

    /**
     * 校验是不是配置：必须有 sites 数组。
     * 用 kotlinx.serialization 而不是 org.json，后者在 JVM 单测里是空壳桩。
     */
    private fun config(value: String): String? {
        val v = value.trim().removePrefix("\uFEFF").trim()
        if (v.isEmpty()) return null
        if (!v.startsWith("{")) return null
        if (!v.contains("\"sites\"")) return null

        val cleaned = stripJsonComments(v)
        val parsed = runCatching { TvBoxCrypto.isJsonObject(cleaned) }.getOrNull()
        return if (parsed == false) null else cleaned
    }

    /**
     * 去掉 JSON 里的双斜杠行注释。
     *
     * TVBox 配置不是严格 JSON，站长习惯用双斜杠屏蔽某个站点：
     *   //{"key":"原创","name":"原创不卡","type":3,"api":"csp_YCyzGuard"},
     * 标准解析器遇到就崩。实测饭太硬配置里有 3 行这种注释。
     *
     * 不能简单 replace：URL 里也有双斜杠（http://），会把地址打断。
     * 所以按行处理，只删行首是双斜杠的整行。
     */
    internal fun stripJsonComments(json: String): String {
        if (!json.contains("//")) return json
        return json.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    private fun decodeBase64(encoded: String): String? = runCatching {
        val bytes = base64Decode(encoded) ?: return@runCatching null
        config(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    /**
     * 纯 Kotlin 的 base64 解码。
     * 不用 android.util.Base64（JVM 单测里是空壳），
     * 也不用 java.util.Base64（minSdk 21 没有）。
     */
    internal fun base64Decode(input: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        val noWs = input.filterNot { it.isWhitespace() }
        if (noWs.isEmpty()) return null

        val core = noWs.trimEnd('=')
        if (core.isEmpty()) return null
        if (!core.all { it in alphabet }) return null
        if (noWs.length - core.length > 2) return null

        val out = java.io.ByteArrayOutputStream(core.length * 3 / 4 + 4)
        var buffer = 0
        var bits = 0

        for (c in core) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }

        return out.toByteArray()
    }

    /**
     * 从 HTML 里挖候选地址。
     * 重点第 1 条：饭太硬那类导航页，URL 只存在于 data-clipboard-text 里。
     */
    internal fun candidates(html: String, baseUrl: String): List<Choice> {
        if (html.isBlank()) return emptyList()

        val out = LinkedHashMap<String, Choice>()

        fun add(raw: String, label: String = "") {
            val u = raw.trim()
            if (u.isEmpty()) return
            if (u.startsWith("javascript:", true)) return
            if (u.startsWith("#")) return
            if (u.startsWith("data:")) return

            val abs = absolutize(u, baseUrl) ?: return
            if (abs == baseUrl) return
            if (!out.containsKey(abs)) out[abs] = Choice(abs, label.trim(), originUrl = baseUrl)
        }

                Regex("""data-clipboard-text\s*=\s*["']([^"']+)["']""")
            .findAll(html).forEach { add(it.groupValues[1]) }

        Regex("""<a[^>]+href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                add(m.groupValues[1], label)
            }

        Regex("""https?://[^\s"'<>\\]+""")
            .findAll(html).forEach { add(it.value) }

        Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { script ->
                Regex("""["']([^"'<>]+\.(?:json|txt|bmp|conf|m3u)(?:\?[^"']*)?)["']""")
                    .findAll(script.groupValues[1]).forEach { add(it.groupValues[1]) }
            }

        val noiseHosts = listOf(
            "googletagmanager", "google-analytics", "baidu.com",
            "cnzz", "beian", "favicon", "w3.org", "schema.org",
        )
        val noiseExts = listOf(
            "css", "js", "png", "jpg", "jpeg", "gif",
            "svg", "woff", "woff2", "ico", "webp",
        )

        return out.values.filter { c ->
            val low = c.url.lowercase()
            if (noiseHosts.any { low.contains(it) }) return@filter false

            val path = low.substringBefore('?').substringBefore('#')
            val lastSeg = path.substringAfterLast('/')
            if (lastSeg.isNotEmpty()) {
                val ext = lastSeg.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext in noiseExts) return@filter false
            }
            true
        }
    }

    /**
     * 相对路径转绝对 URL。
     * 不用 java.net.URI：它对中文域名、竖线、未编码空格都会抛异常。
     */
    private fun absolutize(u: String, baseUrl: String): String? {
        val raw = u.trim()
        if (raw.isEmpty()) return null

        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
        if (raw.startsWith("//")) {
            val scheme = if (baseUrl.startsWith("https://", true)) "https:" else "http:"
            return scheme + raw
        }

        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = baseUrl.substring(0, schemeEnd)
        val afterScheme = baseUrl.substring(schemeEnd + 3)
        val hostEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (hostEnd < 0) afterScheme else afterScheme.substring(0, hostEnd)

        return when {
            raw.startsWith("/") -> "$scheme://$authority$raw"
            else -> {
                val basePath = if (hostEnd < 0) "" else afterScheme.substring(hostEnd)
                val dir = basePath.substringBeforeLast('/', "")
                "$scheme://$authority$dir/$raw"
            }
        }
    }

    private fun fetch(url: String, ua: String, budget: Budget): FetchAttempt {
        var current = url
        val redirectVisited = mutableSetOf(url)

        repeat(4) {
            if (!budget.canRequest()) return FetchAttempt.Failed("请求次数或流量超限（已试 ${budget.requests} 次）")
            budget.requests++

            val req = Request.Builder()
                .url(current)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()

            val call = client.newCall(req)
            val remainingMs = budget.remainingMs()
            if (remainingMs <= 0L) return FetchAttempt.Failed("解析总耗时超过 30 秒")
            call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS)
            val response = runCatching { call.execute() }
            val resp = response.getOrNull()
                ?: return FetchAttempt.RetryWithNextUserAgent(response.exceptionOrNull()?.message ?: "网络请求失败")

            resp.use {
                if (resp.code in 300..399) {
                    val loc = resp.header("Location")
                        ?: return FetchAttempt.Failed("HTTP ${resp.code} 缺少 Location：$current")
                    current = absolutize(loc, current)
                        ?: return FetchAttempt.Failed("重定向地址无效：$loc")
                    if (!redirectVisited.add(current)) return FetchAttempt.Failed("重定向循环：$current")
                    return@repeat
                }

                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code}: $current")
                    return when (resp.code) {
                        403 -> FetchAttempt.RetryWithNextUserAgent("HTTP 403：$current")
                        429 -> {
                            val retryAfter = resp.header("Retry-After")
                            FetchAttempt.Failed(
                                if (retryAfter.isNullOrBlank()) "HTTP 429，服务端正在限流，请稍后重试：$current"
                                else "HTTP 429，服务端要求等待 Retry-After: $retryAfter：$current",
                            )
                        }
                        else -> FetchAttempt.Failed("HTTP ${resp.code}：$current")
                    }
                }

                val body = resp.body ?: return FetchAttempt.Failed("响应体为空：$current")
                val remainingBytes = MAX_BYTES - budget.bytes
                val bytes = runCatching {
                    readResponseBody(body, resp.header("Content-Encoding"), remainingBytes)
                }.getOrNull()
                if (bytes == null) {
                    budget.bytes = MAX_BYTES
                    Log.w(TAG, "响应体超过剩余流量预算或无法解压：$current")
                    return FetchAttempt.Failed("响应体超过 2 MB 预算或无法解压：$current")
                }
                budget.bytes += bytes.size
                return FetchAttempt.Success(bytes, current)
            }
        }

        return FetchAttempt.Failed("重定向次数超过限制：$url")
    }

    private fun readResponseBody(
        body: okhttp3.ResponseBody,
        contentEncoding: String?,
        limitBytes: Int,
    ): ByteArray? {
        if (limitBytes <= 0) return null
        val raw = BufferedInputStream(body.byteStream())
        raw.mark(2)
        val gzipMagic = raw.read() == 0x1F && raw.read() == 0x8B
        raw.reset()
        val gzipHeader = contentEncoding.orEmpty()
            .split(',')
            .any { it.trim().equals("gzip", ignoreCase = true) }
        val decoded = if (gzipMagic || gzipHeader) GZIPInputStream(raw) else raw
        return decoded.use { input ->
            val output = ByteArrayOutputStream(minOf(limitBytes, 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val readLimit = minOf(buffer.size, limitBytes - total + 1)
                val count = input.read(buffer, 0, readLimit)
                if (count < 0) break
                total += count
                if (total > limitBytes) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
