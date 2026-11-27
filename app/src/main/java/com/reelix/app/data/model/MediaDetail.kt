package com.reelix.app.data.model

/**
 * 详情页模型：一部作品的完整信息 + 多播放线路 + 剧集树。
 * 对应 TVBox 的 detail 返回，但做了归一化。
 */
data class MediaDetail(
    val item: MediaItem,
    /** 播放线路（不同源/不同清晰度），一个作品可以有 N 条 */
    val routes: List<PlayRoute> = emptyList(),
    /** 相关推荐 */
    val related: List<MediaItem> = emptyList(),
) {
    val primaryRoute: PlayRoute? get() = routes.firstOrNull()
}

/**
 * 一条播放线路，例如"高清线路1"、"蓝光线路"。
 */
data class PlayRoute(
    val name: String,
    /** 该线路的剧集列表，电影只有 1 项 */
    val episodes: List<Episode> = emptyList(),
    /** 该线路是否可直接解析播放（false 表示需要走嗅探/解析） */
    val directPlay: Boolean = true,
)

/**
 * 单集。
 * [flag] + [playId] 是两个给 spider 的定位参数：flag 是线路标识，playId 是集标识。
 */
data class Episode(
    val index: Int,
    val name: String,
    val flag: String = "",
    val playId: String = "",
    /** 直链（如果已经解析出来） */
    val url: String? = null,
    /** 播放需要带的请求头，Map 直传给 ExoPlayer */
    val headers: Map<String, String> = emptyMap(),
    /** 时长（秒），已知时填 */
    val durationSec: Long? = null,
    /** Display number; index remains unique even for versions and specials. */
    val episodeNumber: Int? = null,
    /** Source label before cleanup; null identifies an older cached episode. */
    val originalName: String? = null,
    /** Library file size; null for sources that do not provide a reliable size. */
    val sizeBytes: Long? = null,
    /** Library source that owns playId (alist / webdav / emby); blank for online sources. */
    val sourceKey: String = "",
    /** Optional DRM system marker (for example `widevine`); marker only, no license config is implied. */
    val drmScheme: String? = null,
) {
    val displayName: String
        get() = name.ifBlank { "第${index}集" }
}
