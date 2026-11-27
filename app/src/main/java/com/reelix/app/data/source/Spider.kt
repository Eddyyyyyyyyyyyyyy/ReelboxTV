package com.reelix.app.data.source

import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem

/**
 * 源协议接口 —— 对齐 TVBox 的 Spider 契约。
 *
 * 设计要点：
 *  1. 全部返回已解析好的领域对象，不再是原始 JSON 字符串。
 *     原始 TVBox 让上层到处 `JSONObject.getJSONArray(...)` 解析，UI 层直接烂掉。
 *  2. 接口里不含任何网络细节，网络由各实现自己持有。
 *  3. 所有方法挂 suspend，切线程由实现负责（要么 withContext，要么本来就是异步源）。
 *
 * 落地顺序建议：
 *  - JsonApiSpider：最快，type=1 的接口直接 HTTP 拿 JSON
 *  - JsSpider：跑 .js 源（Rhino 或 QuickJS），量大
 *  - JarSpider：DexClassLoader 加载远程 jar，最重
 *  - LibrarySpider：本地/网盘片库，不是 TVBox 源，但实现同一接口
 */
interface Spider {

    /** 站点唯一标识，用于多源聚合时溯源 */
    val key: String

    /** 展示名 */
    val name: String

    /** 是否支持搜索 */
    val searchable: Boolean

    /**
     * 首页分类 + 推荐。
     * @return 分类列表（分类名 -> 该分类下的推荐内容）
     */
    suspend fun home(): HomeContent

    /**
     * 分类内容分页。
     * @param categoryId 分类 id（来自 [HomeContent.categories]）
     * @param page 从 1 开始
     * @param extend 筛选参数（年份/地区/类型），来自分类的 filter
     */
    suspend fun category(
        categoryId: String,
        page: Int = 1,
        extend: Map<String, String> = emptyMap(),
    ): CategoryContent

    /** 详情 */
    suspend fun detail(id: String): MediaDetail

    /** 搜索 */
    suspend fun search(keyword: String, page: Int = 1): List<MediaItem>

    /**
     * 解析出可播放地址。
     * @param routeFlag 线路标识
     * @param episodeId 集标识
     */
    suspend fun resolvePlayUrl(routeFlag: String, episodeId: String): PlayResolution
}

/** 首页返回 */
data class HomeContent(
    val categories: List<SourceCategory> = emptyList(),
    val recommendations: List<MediaItem> = emptyList(),
    /** 首页大图 Hero（部分源提供） */
    val heroes: List<MediaItem> = emptyList(),
)

/** 分类 */
data class SourceCategory(
    val id: String,
    val name: String,
    val filters: List<FilterGroup> = emptyList(),
)

/** 筛选项（年份/地区/类型） */
data class FilterGroup(
    val key: String,
    val name: String,
    val options: List<FilterOption> = emptyList(),
)

data class FilterOption(val value: String, val label: String)

/** 分类内容 */
data class CategoryContent(
    val items: List<MediaItem> = emptyList(),
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int = 0,
)

/**
 * 播放解析结果。
 * [url] 可能是直链 m3u8/mp4，也可能是一个需要解析器再处理的网页地址。
 */
data class PlayResolution(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** true = 直链可播；false = 需要走解析器嗅探 */
    val direct: Boolean = true,
    /** 解析器名（direct=false 时用） */
    val parserName: String? = null,
    /** 字幕地址，可选 */
    val subtitleUrl: String? = null,
    /**
     * 解析失败时 jar/解析器给出的原因。
     *
     * 网盘类源常见：
     * ```
     * 未登录         → 需要配置 cookie
     * 转存失败       → 分享链接失效
     * 文件不存在     → 源站删了
     * ```
     * 这个字段直接透传到播放器错误层，用户能看到到底为什么播不了，
     * 而不是对着 00:00 的黑屏猜。
     */
    val msg: String? = null,
    val playUrl: String? = null,
    val requiresParsing: Boolean = false,
)
