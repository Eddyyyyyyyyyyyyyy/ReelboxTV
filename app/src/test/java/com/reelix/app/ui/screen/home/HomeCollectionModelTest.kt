package com.reelix.app.ui.screen.home

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class HomeCollectionModelTest {
    @Test fun `类型筛选沿用片库电影和未知类型规则`() {
        assertTrue(homeCollectionMatches(MediaKind.UNKNOWN, HomeCollectionFilter.MOVIE))
        assertTrue(homeCollectionMatches(MediaKind.MOVIE, HomeCollectionFilter.MOVIE))
        assertFalse(homeCollectionMatches(MediaKind.SERIES, HomeCollectionFilter.MOVIE))
        assertTrue(homeCollectionMatches(MediaKind.SERIES, HomeCollectionFilter.SERIES))
        assertTrue(homeCollectionMatches(MediaKind.ANIME, HomeCollectionFilter.ALL))
    }
    @Test fun `活动排序包含观看时间而最近加入不冒用刷新时间`() {
        val items = listOf(
            HomeCollectionItem(MediaItem("older", "旧片"), 10, 900),
            HomeCollectionItem(MediaItem("newer", "新片"), 100, 100),
            HomeCollectionItem(MediaItem("unknown", "旧缓存")))
        assertEquals("newer", homeCollectionItems(items, HomeCollectionFilter.ALL, HomeCollectionSort.ACTIVITY, mapOf("newer" to 1000)).first().item.id)
        assertEquals(listOf("newer", "older", "unknown"), homeCollectionItems(items,
            HomeCollectionFilter.RECENT, HomeCollectionSort.ACTIVITY, emptyMap()).map { it.item.id })
    }
    @Test fun `时间展示不产生负数也不把未知时长当真实时长`() {
        assertEquals("00:00:00", homePlaybackClock(-1))
        assertEquals("00:58:24", homePlaybackClock(3504000))
        assertEquals("02:20:00", homePlaybackClock(8400000))
    }
}
