package com.reelix.app.data.source.jar

/**
 * 播放地址嗅探的**判定规则**（纯函数，零平台依赖）。
 *
 * ## 为什么需要这一层
 *
 * 大量采集站 / 网盘源给的线路地址是**播放页 HTML**，不是媒体地址：
 * ```
 * https://vip.xxx-plays.com/share/<hash>    ← ArtPlayer 分享页
 * https://xxx.com/vodplay/123-1-1.html      ← 播放页，真地址在 JS 里
 * https://pan.quark.cn/s/xxxx               ← 网盘分享页
 * ```
 * 直接喂给 ExoPlayer 只会得到 `ERROR_CODE_IO_UNSPECIFIED`。
 *
 * TVBox 的做法是开一个隐藏 WebView 加载页面，**拦截所有媒体请求**，
 * 抓到 `.m3u8` / `.mp4` 就拿来播。这个文件是那套机制的"大脑"，
 * 实际加载交给 `WebViewSniffer`。
 *
 * ## 分工
 *
 * ```
 * SniffRules（本文件，纯函数）   ← 判定"该不该嗅探"/"是不是媒体"/"多个候选选哪个"
 * WebViewSniffer（平台层）       ← 真的去加载页面、拦截请求
 * ```
 *
 * 纯函数的好处是**能单测**，而 WebView 部分只能真机验证。
 */
object SniffRules {

    /** 认得的媒体扩展名。只认明确扩展名，不猜路径。 */
    private val MEDIA_EXTS = setOf(
        "m3u8", "mp4", "mkv", "flv", "ts", "mpd", "webm", "mov",
        "avi", "wmv", "rmvb", "m4v",
    )

    /**
     * 这个地址**看起来是**媒体吗（扩展名判定）。
     *
     * `/play/xxx` 这种"看起来像播放页"的**不认** ——
     * 宁可多嗅探一次，也不要把播放页当媒体喂给播放器
     * （那会报 SourceNotSupported，比多等几秒更糟）。
     */
    fun looksLikeMedia(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        // 先剥掉 TVBox 的 @头 后缀，再去 query/fragment
        val clean = UrlHeader.split(url).url
        val path = clean.substringBefore('?').substringBefore('#')

        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return false

        return path.substring(dot + 1).lowercase() in MEDIA_EXTS
    }

    /** 这个地址是 HLS 播放列表吗（需要重写内部 URI） */
    fun isHls(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = UrlHeader.split(url).url.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8", ignoreCase = true)
    }

    /** 这个地址是网盘分享页吗（需要登录态才能转直链） */
    fun isPanSharePage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return u.contains("pan.quark.cn/s/") ||
            u.contains("pan.baidu.com/s/") ||
            u.contains("aliyundrive.com/s/") ||
            u.contains("alipan.com/s/") ||
            u.contains("115.com/s/") ||
            u.contains("cloud.189.cn/t/")
    }

    /**
     * 从多个候选里挑一个最该用的。
     *
     * 优先级：
     * ```
     * 1. m3u8（HLS，最通用，电视兼容性最好）
     * 2. mp4
     * 3. 其它媒体
     * 4. 越早出现的越优先（通常主线路先发请求）
     * ```
     * 同一个地址出现多次只算一次。
     */
    fun pickBest(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null

        val distinct = candidates.distinct().filter { it.isNotBlank() && it.startsWith("http") }
        if (distinct.isEmpty()) return null

        // ① m3u8 优先
        distinct.firstOrNull { isHls(it) }?.let { return it }

        // ② mp4
        distinct.firstOrNull {
            val p = it.substringBefore('?').lowercase()
            p.endsWith(".mp4") || p.endsWith(".m4v")
        }?.let { return it }

        // ③ 其它已识别的媒体
        distinct.firstOrNull { looksLikeMedia(it) }?.let { return it }

        return null
    }
}
