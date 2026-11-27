package com.reelix.app.data.source.jar

/**
 * 磁力链 / 特殊协议识别。
 *
 * ## 为什么需要
 *
 * 部分源（`p2p` 类、`賤片`、`新6V`）给的"播放地址"根本不是 HTTP：
 * ```
 * magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=片名.mkv
 * ed2k://|file|片名.mkv|1234567|ABCDEF...|/
 * thunder://QUFodHRwOi8v...
 * ```
 *
 * 这些地址 ExoPlayer **完全不认识**，丢进去只会报错。
 * 用户看到的是「播放失败」，但实际是「这个源需要 BT 客户端」——
 * 完全是两种不同的情况，混在一起会让人以为 App 坏了。
 *
 * ## TVBox 是怎么处理的
 *
 * 它内置**迅雷 SDK**（`Thunder.parse()`）：
 * ```
 * 磁力链 → 迅雷 SDK 提交 → 云端离线下载 → 返回 HTTP 直链 → 播放
 * ```
 * 这个 SDK 是闭源商业库，我们拿不到。
 *
 * ## 我们的三层应对
 *
 * ```
 * ① 识别    —— 判断地址是不是磁力/ed2k/迅雷协议
 * ② 改选    —— 详情页有多条线路时，自动优先选可播的非磁力线
 * ③ 标注    —— 磁力线路打上标记，UI 上一眼看出哪条不能用
 * ```
 *
 * 目标：把「播放失败」变成「这条是磁力链，换其他线路」。
 * 用户不再困惑，知道该怎么办。
 */
object MagnetLink {

    /** 协议类型 */
    enum class Kind(val label: String) {
        /** 磁力链接 */
        MAGNET("磁力链接"),

        /** 电驴 */
        ED2K("电驴链接"),

        /** 迅雷专用链 */
        THUNDER("迅雷链接"),

        /** 普通 HTTP 地址 —— 可以播 */
        HTTP(""),
    }

    /**
     * 判断地址的协议类型。
     *
     * 只看前缀，不做正则匹配 —— 这层要快，详情页一次要过几十个地址。
     */
    fun kind(url: String?): Kind {
        val u = url?.trim()?.lowercase().orEmpty()
        if (u.isEmpty()) return Kind.HTTP

        return when {
            u.startsWith("magnet:") -> Kind.MAGNET
            u.startsWith("ed2k://") -> Kind.ED2K
            u.startsWith("thunder://") -> Kind.THUNDER
            else -> Kind.HTTP
        }
    }

    /** 是磁力/电驴/迅雷这类需要外部客户端的地址吗 */
    fun isUnsupported(url: String?): Boolean = kind(url) != Kind.HTTP

    /**
     * 给用户看的说明。
     *
     * 这句话会直接显示在播放失败的位置 —— 所以要具体：
     * 说清"是什么"和"怎么办"，而不是干巴巴的"播放失败"。
     */
    fun hint(url: String?): String? = when (kind(url)) {
        Kind.MAGNET -> "这条线路是磁力链接，需要 BT 下载器才能播放。请换一条其他线路。"
        Kind.ED2K -> "这条线路是电驴链接，需要 eMule 才能播放。请换一条其他线路。"
        Kind.THUNDER -> "这条线路是迅雷专用链，需要迅雷才能播放。请换一条其他线路。"
        Kind.HTTP -> null
    }

    /**
     * 从一组线路里挑一条**可播的**。
     *
     * ## 为什么需要
     *
     * 详情页默认选中第一条线路。如果第一条恰好是磁力链，
     * 用户点播放就失败 —— 但第二条可能是正常的 HTTP 线路。
     *
     * 这里主动跳过磁力线，让默认选中落在一个能播的线路上。
     *
     * @return 可播线路的索引，全都不行返回 0（保持原样）
     */
    fun pickPlayableIndex(routeEpisodes: List<List<String?>>): Int {
        for ((idx, eps) in routeEpisodes.withIndex()) {
            // 一条线路里只要有一集是 HTTP，就认为这条线路可用
            if (eps.any { !isUnsupported(it) && !it.isNullOrBlank() }) {
                return idx
            }
        }
        return 0
    }

    /**
     * 单条线路是否可用（不含磁力等外部协议）。
     *
     * UI 用它决定要不要显示"不可播"标记。
     */
    fun isRoutePlayable(episodeUrls: List<String?>): Boolean =
        episodeUrls.any { !isUnsupported(it) && !it.isNullOrBlank() }
}
