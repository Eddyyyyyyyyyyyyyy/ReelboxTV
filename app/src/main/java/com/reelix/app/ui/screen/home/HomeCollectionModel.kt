package com.reelix.app.ui.screen.home

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind

data class HomeCollectionItem(val item: MediaItem, val firstAddedAt: Long = 0L, val activityAt: Long = 0L)
enum class HomeCollectionFilter { ALL, MOVIE, SERIES, RECENT }
enum class HomeCollectionSort { ACTIVITY, ADDED, TITLE }

fun homeCollectionMatches(kind: MediaKind, filter: HomeCollectionFilter): Boolean = when (filter) {
    HomeCollectionFilter.MOVIE -> kind == MediaKind.MOVIE || kind == MediaKind.UNKNOWN
    HomeCollectionFilter.SERIES -> kind == MediaKind.SERIES
    else -> true
}

fun homeCollectionItems(items: List<HomeCollectionItem>, filter: HomeCollectionFilter,
    sort: HomeCollectionSort, watched: Map<String, Long>): List<HomeCollectionItem> {
    val filtered = items.filter { homeCollectionMatches(it.item.kind, filter) }
    return when {
        filter == HomeCollectionFilter.RECENT || sort == HomeCollectionSort.ADDED ->
            filtered.sortedByDescending { it.firstAddedAt }
        sort == HomeCollectionSort.TITLE -> filtered.sortedBy { it.item.title }
        else -> filtered.sortedByDescending { maxOf(it.activityAt, watched[it.item.id] ?: 0L) }
    }
}

fun homePlaybackClock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
