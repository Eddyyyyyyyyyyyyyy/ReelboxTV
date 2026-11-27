package com.reelix.app.ui.component

import com.reelix.app.data.settings.*
import org.junit.Assert.*
import org.junit.Test

class AppearancePreferencesTest {
    @Test fun `损坏设置补全页面并保留不可隐藏页面`() {
        val p = AppearancePreferences(listOf("LIBRARY", "LIBRARY", "invalid"), setOf("HOME", "SEARCH", "ONLINE", "SETTINGS"), 99).normalized()
        assertEquals(listOf("LIBRARY", "HOME", "ONLINE", "SEARCH"), p.order)
        assertEquals(setOf("ONLINE"), p.hidden)
        assertEquals(6, p.posterColumns)
    }
    @Test fun `导航重排按标识切换隐藏页返回首页`() {
        val p = AppearancePreferences().move("LIBRARY", -2).copy(hidden = setOf("ONLINE"))
        assertEquals("LIBRARY", p.visible.first())
        assertEquals("HOME", p.safePage("ONLINE"))
        assertEquals("SEARCH", p.safePage("SEARCH"))
        assertEquals("HOME", p.safePage("HOME"))
    }
    @Test fun `五档全屏列数和搜索真实尺寸一致`() {
        (4..8).forEach { count ->
            val full = posterGeometry(1920, 1808, 20, 56, count)
            val search = posterGeometry(1920, 1016, 20, 56, count)
            assertEquals(count, full.columns)
            assertEquals(full.width, search.width)
            assertTrue(search.columns < count)
        }
    }
    @Test fun `搜索侧栏至少容纳四列且缩窄单卡宽度`() {
        val standard = posterGeometry(1920, 1016, 20, 56, 6, minimumColumns = 4)
        assertEquals(4, standard.columns)
        assertEquals(239, standard.width)
        val full = posterGeometry(1920, 1808, 20, 56, 6)
        assertTrue(standard.width < full.width)
    }
    @Test fun `重排尺寸后按key恢复并容忍内容删除`() {
        assertEquals(2, restoredPosterPosition(listOf("b", "c", "a"), "a", 0))
        assertEquals(1, restoredPosterPosition(listOf("b", "c"), "a", 99))
        assertEquals(0, restoredPosterPosition(emptyList(), "a", 5))
    }
}
