package com.reelix.app.data.source.jar

/**
 * 解析 TVBox 的「URL 带头」约定。
 *
 * ## 这是什么
 *
 * TVBox 生态里，spider 返回的地址可能长这样：
 * ```
 * https://img.example.com/poster/xxx.jpg@Referer=https://example.com/@User-Agent=Mozilla/5.0
 * ```
 * `@` 之后是 **HTTP 请求头**（`Key=Value`，多个用 `@` 分隔）。
 *
 * 直接把这串丢给图片库/播放器会 404 或 403：
 * - 图片 → 片名出来了但海报全黑
 * - 视频 → `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`
 *
 * ## 为什么要单独成模块
 *
 * 这不是某个源的特例，而是**整个 TVBox 生态的通用约定**：
 * 海报、播放地址、甚至详情页链接都可能带 `@头`。
 * 所以解析逻辑必须只有一份，所有消费方都走它。
 *
 * ## 边界处理
 *
 * 第一段必须以 `http` 开头才认为是「URL + 头」；
 * 否则这个 `@` 只是 URL 里的普通字符（比如邮箱、查询参数），原样返回。
 */
object UrlHeader {

    data class Parsed(
        val url: String,
        val headers: Map<String, String>,
    )

    fun split(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed("", emptyMap())
        if (!text.contains('@')) return Parsed(text, emptyMap())

        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return Parsed(text, emptyMap())
        val markers = Regex("@([A-Za-z][A-Za-z0-9_-]*)=").findAll(text).toList()
        if (markers.isEmpty()) return Parsed(text, emptyMap())
        val headers = markers.mapIndexed { index, match ->
            val end = markers.getOrNull(index + 1)?.range?.first ?: text.length
            match.groupValues[1] to text.substring(match.range.last + 1, end).trim()
        }.toMap()
        return Parsed(text.substring(0, markers.first().range.first), headers)
    }

    /**
     * 判断一个地址是否**已经是媒体地址**（m3u8/mp4 等），
     * 决定要不要再走解析链。
     *
     * 只看扩展名和常见特征，不发请求 —— 这层要快。
     */
    fun looksLikeMedia(url: String?): Boolean {
        val u = url?.trim()?.lowercase().orEmpty()
        if (u.isEmpty()) return false

        // 去掉 @ 头部分再判断扩展名
        val clean = u.substringBefore('@')

        return clean.endsWith(".m3u8") ||
            clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") ||
            clean.endsWith(".flv") ||
            clean.endsWith(".ts") ||
            clean.endsWith(".mpd") ||
            clean.contains(".m3u8?") ||
            clean.contains(".mp4?") ||
            // 带签名参数的直链
            (clean.contains("token=") && (clean.contains("video") || clean.contains("play")))
    }
}
