package com.reelix.app.ui.screen.library

import com.reelix.app.R

/**
 * 片库筛选（全部 / 剧集 / 电影）。
 *
 * ## 为什么单独一个文件
 *
 * 2026-11-09：原来定义在 `LibraryScreen.kt` 里 —— 那是 **Compose 版的片库页**，
 * 页面迁到原生 View（`LibraryView`）之后整个文件都成了死代码。
 *
 * `LibraryView` 还在用这个枚举做筛选，所以先拆出来独立成文件，再删原文件。
 */
enum class LibraryFilter(val labelRes: Int) {
    ALL(R.string.library_filter_all),
    SERIES(R.string.library_filter_series),
    MOVIE(R.string.library_filter_movie),
}

/** Human-readable label for a configured library source. */
fun librarySourceLabel(sourceKey: String): String = when (sourceKey.lowercase()) {
    "webdav" -> "WebDAV"
    "emby" -> "Emby"
    else -> "Alist"
}
