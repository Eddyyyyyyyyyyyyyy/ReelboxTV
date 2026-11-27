package com.reelix.app.ui.screen.search

/**
 * 在线搜索的 UI 状态。
 *
 * ## 为什么单独一个文件
 *
 * 2026-11-09：这个类原来定义在 `SearchScreen.kt` 里 —— 那是 **Compose 版的搜索页**，
 * 页面迁到原生 View（`SearchView` / `SearchResultView`）之后整个文件都成了死代码。
 *
 * 但 `MainActivity` 和 `SearchView` 还在用这个数据类，所以先把它拆出来独立成文件，
 * 再删掉那个 900 行的大文件。
 */
data class OnlineSearchUiState(
    val running: Boolean = false,
    val results: List<com.reelix.app.data.source.jar.OnlineSearch.SiteResult> = emptyList(),
    val keyword: String = "",
) {
    val hits: List<com.reelix.app.data.source.jar.OnlineSearch.SiteResult>
        get() = results.filter { it.items.isNotEmpty() }

    val totalCount: Int get() = hits.sumOf { it.items.size }
}
