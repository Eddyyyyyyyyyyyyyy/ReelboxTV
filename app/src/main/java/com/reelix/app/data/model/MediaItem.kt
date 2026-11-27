package com.reelix.app.data.model

/**
 * 全站统一的媒体条目 —— 首页海报墙、搜索结果、详情页共用同一个模型。
 * 这样换导航目的地不用做类型转换，也是一个"缝合"应用能活下去的前提。
 */
data class MediaItem(
    val id: String,
    /** 片名 */
    val title: String,
    /** 原始片名 / 英文名 / 其他语言名，详情页副标题用 */
    val originalTitle: String? = null,
    /** 竖版海报 */
    val poster: String? = null,
    /** 横版 backdrop，Hero 和详情页大海报用 */
    val backdrop: String? = null,
    /** 年份 */
    val year: Int? = null,
    /** 评分 0..10 */
    val rating: Double? = null,
    /** 类型："电影" / "剧集" / "动漫" / "综艺" */
    val kind: MediaKind = MediaKind.UNKNOWN,
    /** 简介 */
    val overview: String? = null,
    /** 时长（分钟），剧集为单集时长 */
    val durationMin: Int? = null,
    /** 地区 */
    val area: String? = null,
    /** 语言 */
    val language: String? = null,
    /** 演员 */
    val cast: List<String> = emptyList(),
    /** 演员头像；有头像时详情页用它替换纯文字演职员。 */
    val castCredits: List<com.reelix.app.data.image.LibraryPersonCredit> = emptyList(),
    /** 评分来源（TMDB / 豆瓣 / Streamx / TVmaze）；用于详情页显示“豆瓣 8.7”。 */
    val ratingSource: String? = null,
    /** 导演 */
    val director: String? = null,
    /** 来源站点 —— 多源聚合时的标识 */
    val sourceKey: String = "",
    /** 该站点内部的 id */
    val sourceId: String = "",
    /** 是否来自私有片库（本地/网盘）而不是在线源 */
    val fromLibrary: Boolean = false,
    /**
     * 内置打包图片的资源名（不含扩展名，如 "p_dune2" / "b_dune2"）。
     *
     * 为什么要这个字段：
     *  国内电视上 TMDB 图被 DNS 污染，演示数据必须用打包进 APK 的图。
     *  Coil 对 `file:///android_asset/` 支持不好（实测加载失败），
     *  所以把图放进 res/drawable-nodpi，通过资源名查找。
     * 真实接入网络源后这个字段留空，走 [poster]/[backdrop] 的 URL。
     */
    val posterRes: String? = null,
    val backdropRes: String? = null,
    /** 观看进度 0..1，用于"继续观看" */
    val progress: Float = 0f,
    /** 已看过的集数（剧集），用于继续观看定位 */
    val lastEpisode: Int? = null,
    /** 加入片库时间戳 */
    val addedAt: Long = 0L,
    /** Local filenames enrich existing search without changing the owning directory identity. */
    val searchResources: List<MediaSearchResource> = emptyList(),
    val matchedResourcePath: String? = null,
    val matchedResourceName: String? = null,
    val preferredResourcePath: String? = null,
) {
    /** 海报墙下方的元信息行："2024 · 剧集 · 8.7" */
    val metaLine: String
        get() = listOfNotNull(
            year?.toString(),
            kind.label.takeIf { it.isNotEmpty() },
            rating?.let { String.format("%.1f", it) }
        ).joinToString(" · ")

    /** 详情页副标题："2024 · 中国大陆 · 45分钟" */
    val detailMetaLine: String
        get() = listOfNotNull(
            year?.toString(),
            area,
            durationMin?.let { "${it}分钟" }
        ).joinToString(" · ")

    /** 详情页元信息三段：年份 / 类型 / 时长 */
    val detailMetaParts: List<String>
        get() = listOfNotNull(
            year?.toString(),
            kind.label.takeIf { it.isNotBlank() },
            durationMin?.let { "${it}分钟" },
            area?.takeIf { it.isNotBlank() }
        )

    /**
     * 详情页背景质量判定 —— 决定用哪套布局。
     *
     * 这是整个"TVBox 源没有大海报"问题的解法核心：
     * 源站给的图参差不齐，UI 不能让用户看到糊图或黑屏，
     * 所以在入口处一次性判定，之后详情页三套布局各司其职。
     *
     * 判定顺序：
     *  1. backdropRes（内置横图）→ CINEMATIC
     *  2. backdrop URL 是横版  → CINEMATIC
     *  3. 有竖图（res 或 url） → COMPOSITE
     *  4. 全无                 → FALLBACK
     */
    val backdropQuality: BackdropQuality
        get() {
            if (!backdropRes.isNullOrBlank()) return BackdropQuality.CINEMATIC
            val bd = backdrop?.takeIf { it.isNotBlank() }
            if (bd != null && bd.looksLandscape) return BackdropQuality.CINEMATIC
            if (!posterRes.isNullOrBlank() || !poster.isNullOrBlank() || bd != null) {
                return BackdropQuality.COMPOSITE
            }
            return BackdropQuality.FALLBACK
        }

    /** 详情页/卡片实际要显示的竖图资源名（本地优先） */
    val effectivePosterRes: String? get() = posterRes

    /** 详情页实际要显示的横图资源名（本地优先） */
    val effectiveBackdropRes: String? get() = backdropRes

    /** 有没有任何可显示的图 */
    val hasAnyImage: Boolean
        get() = !posterRes.isNullOrBlank() || !poster.isNullOrBlank() ||
            !backdropRes.isNullOrBlank() || !backdrop.isNullOrBlank()
}

/**
 * 详情页背景的三种形态。
 *
 * - [CINEMATIC]：真有横版大图。全屏铺图 + 左下文字块，Hero 式。
 *                电影类最理想，视觉冲击最强。
 * - [COMPOSITE]：只有竖版海报（TVBox 源的常见情况）。
 *                模糊底 + 前景清晰竖图 + 右侧文字。
 *                这不是降级，剧集类内容本来就该是竖图为主。
 * - [FALLBACK] ：一张图都没有（离线/断网/源失效）。
 *                纯色底 + 大标题 + 空占位框，保证界面不塌。
 */
enum class BackdropQuality { CINEMATIC, COMPOSITE, FALLBACK }

/**
 * 从 URL 猜这张图是不是横版。
 *
 * 判定维度（按优先级）：
 *  1. 本地 assets 目录名（`/backdrops/` vs `/posters/`）—— 我们自己控制的，最可靠
 *  2. TMDB 尺寸约定：w1280/w1920/original = 横版；w500/w342/w185/w200 = 竖版
 *  3. 路径关键词：backdrop/banner/landscape vs poster/cover/vertical
 *
 * 都无法判定时保守返回 false —— 走 COMPOSITE 比误用糊图更安全。
 */
private val String.looksLandscape: Boolean
    get() {
        val u = lowercase()
        // ① 本地 assets（演示数据 / 片库缓存）
        if (u.contains("/backdrops/")) return true
        if (u.contains("/posters/")) return false
        // ② TMDB 尺寸
        if (u.contains("/w1280/") || u.contains("/w1920/") || u.contains("/original/")) return true
        if (u.contains("/w500/") || u.contains("/w342/") || u.contains("/w185/") || u.contains("/w200/")) return false
        // ③ 关键词
        if (u.contains("backdrop") || u.contains("banner") || u.contains("landscape")) return true
        if (u.contains("poster") || u.contains("cover") || u.contains("vertical")) return false
        return false
    }

enum class MediaKind(val label: String, val pathSegment: String) {
    MOVIE("电影", "movie"),
    SERIES("剧集", "series"),
    ANIME("动漫", "anime"),
    VARIETY("综艺", "variety"),
    DOCUMENTARY("纪录片", "doc"),
    UNKNOWN("", "other");

    companion object {
        fun fromLabel(raw: String?): MediaKind {
            val s = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.label.isNotEmpty() && (s.contains(it.label) || s.contains(it.name, true))
            } ?: UNKNOWN
        }
    }
}
