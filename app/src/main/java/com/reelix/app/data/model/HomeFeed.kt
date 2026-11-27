package com.reelix.app.data.model

/**
 * 首页一次性要渲染的全部内容。
 * 爆米花式首页结构很固定，所以直接把它拍平成一个模型，UI 只做渲染不做组装：
 *
 *  Hero 区（1 张大图轮播）
 *  ↓
 *  继续观看（横滑，带进度条）
 *  ↓
 *  最近添加（横滑）
 *  ↓
 *  多个分类 rail（高分 / 剧集 / 电影 / 动漫）
 */
data class HomeFeed(
    val heroes: List<MediaItem> = emptyList(),
    val continueWatching: List<MediaItem> = emptyList(),
    val recentlyAdded: List<MediaItem> = emptyList(),
    val rails: List<HomeRail> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** 一条横滑分类 */
data class HomeRail(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
    /** 点击"更多"跳转的分类 id，空表示不显示更多 */
    val moreCategoryId: String? = null,
)

/** 首页 Hero 轮播需要的信息（比 MediaItem 多一个大字标语） */
data class HeroSlide(
    val item: MediaItem,
    val slogan: String = "",
    val tagline: String = "",
)
