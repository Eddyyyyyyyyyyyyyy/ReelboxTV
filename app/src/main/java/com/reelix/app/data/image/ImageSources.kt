package com.reelix.app.data.image

/**
 * 图片源策略 —— 这是本工程在**国内网络环境下能显示海报**的关键。
 *
 * 背景（实测）：
 *  - `image.tmdb.org` 在部分国内网络/智能电视上被 DNS 污染到 198.19.19.31，100% 丢包
 *  - 但 PC 端同域名正常（143.244.50.212），说明是设备侧 DNS 问题，不是全局墙
 *  - 电视用户不可能去改 DNS，所以必须在 App 层做兜底
 *
 * 策略：
 *  1. 主图源可配置（默认 TMDB）
 *  2. TMDB 图片走"域名池"，可换成反代/镜像
 *  3. 加载失败自动降级到下一档，不重试同一个域名
 *  4. 所有图源最终都归一到 [ImageSources.resolve] 一个出口
 */
object ImageSources {

    /** TMDB 官方 + 常见反代（失效时自动跳下一个） */
    private val TMDB_HOSTS = listOf(
        "https://image.tmdb.org",
        // 反代候选：可在此追加你自己的可用镜像
        // "https://tmdb.example.workers.dev",
    )

    /**
     * 当前使用的图片源。
     * 运行时可由设置页修改，默认 TMDB。
     */
    enum class Provider {
        /** TMDB 官方/反代（需要网络环境支持） */
        TMDB,

        /** 源站自带图（TVBox 源直接给的 URL，不做改写） */
        ORIGIN,
    }

    /** 全局当前图源，设置页可改 */
    @Volatile
    var current: Provider = Provider.TMDB

    /**
     * 把 TMDB 的相对路径 / 完整 URL 转成当前图源可用的 URL。
     *
     * 用法：
     * ```
     * ImageSources.resolve("/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg", ImageSize.POSTER_M)
     * ```
     */
    fun resolve(path: String?, size: ImageSize = ImageSize.POSTER_M): String? {
        if (path.isNullOrBlank()) return null

        // 已经是完整 URL 且不是 tmdb，直接返回（TVBox 源给的地址）
        if (path.startsWith("http")) {
            return if (current == Provider.TMDB) rewriteTmdb(path, size) else path
        }

        // 相对路径 → 拼当前图源
        return when (current) {
            Provider.TMDB -> tmdbUrl(path, size)
            Provider.ORIGIN -> null
        }
    }

    /** TMDB 图片尺寸档位 */
    enum class ImageSize(val tmdb: String) {
        /** 海报：列表小图 */
        POSTER_S("w185"),
        /** 海报：卡片 */
        POSTER_M("w342"),
        /** 海报：详情页大图 */
        POSTER_L("w500"),
        /** 横版：Hero / 详情页 backdrop */
        BACKDROP_M("w780"),
        /** 横版：大屏 */
        BACKDROP_L("w1280"),
    }

    private fun tmdbUrl(path: String, size: ImageSize): String {
        val host = TMDB_HOSTS.firstOrNull() ?: "https://image.tmdb.org"
        return "$host/t/p/${size.tmdb}$path"
    }

    /** 把已是 TMDB 的完整 URL 按需要的尺寸重写 */
    private fun rewriteTmdb(url: String, size: ImageSize): String {
        val cleaned = url.replace("https://image.tmdb.org", "")
            .replace(Regex("""/t/p/[^/]+/"""), "/t/p/${size.tmdb}/")
        return tmdbUrl(cleaned, size)
    }

    /** 豆瓣图片必须带的 Referer：不带直接返回 418（实测）。 */
    const val DOUBAN_REFERER = "https://movie.douban.com/"

    /** 豆瓣图床用的桌面 UA：移动 UA 会被部分镜像降级成占位图。 */
    const val DOUBAN_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 按域名补齐图床必须的请求头。
     *
     * 只处理图片 CDN，不碰任何数据接口 —— 豆瓣的资料/搜索接口仍然停用。
     * 这些头是图床的防盗链要求，不是登录态，也不带 Cookie。
     */
    fun headersFor(url: String): Map<String, String> = buildMap {
        if (isDoubanImageHost(url)) {
            put("Referer", DOUBAN_REFERER)
            put("User-Agent", DOUBAN_USER_AGENT)
        }
    }

    /** 豆瓣图床域名判断（img1/2/3/9.doubanio.com 等）。 */
    fun isDoubanImageHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "doubanio.com" || host.endsWith(".doubanio.com")
    }
}
