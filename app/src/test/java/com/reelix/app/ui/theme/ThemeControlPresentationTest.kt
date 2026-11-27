package com.reelix.app.ui.theme

import com.reelix.app.ui.theme.layout.*
import org.junit.Assert.*
import org.junit.Test

class ThemeControlPresentationTest {
    private fun palette(accent: Int, onAccent: Int) = NativeThemePalette(
        0, 1, 2, 3, 4, accent, onAccent, accent)

    @Test fun `原生苹果Gallery详情不展示顶部导航`() {
        assertFalse(showTopNavigation(true, null))
        assertFalse(showTopNavigation(true, NativeThemeId.GALLERY))
        assertFalse(showTopNavigation(true, NativeThemeId.TOPBAR))
        assertTrue(showTopNavigation(true, NativeThemeId.SIDEBAR))
    }
    @Test fun `退出详情后四主题都恢复顶部导航`() {
        for (theme in NativeThemeId.entries) assertTrue(showTopNavigation(false, theme))
        assertTrue(showTopNavigation(false, null))
    }

    @Test fun `选中线路焦点使用各自主题而非原生黄色`() {
        val sidebar = palette(0xFFED2639.toInt(), -1)
        val apple = palette(0xFFF4F4F7.toInt(), 0xFF121212.toInt())
        assertEquals(sidebar.accent, themeRouteColors(sidebar, true, true).background)
        assertEquals(sidebar.onAccent, themeRouteColors(sidebar, true, true).text)
        assertEquals(apple.accent, themeRouteColors(apple, true, true).background)
        assertEquals(apple.onAccent, themeRouteColors(apple, true, true).text)
    }
    @Test fun `线路失焦仍保留选中区别但不覆盖整个资源区`() {
        val style = palette(10, 11)
        assertEquals(style.accent, themeRouteColors(style, true, false).background)
        assertEquals(style.onAccent, themeRouteColors(style, true, false).text)
        assertEquals(style.primaryText, themeRouteColors(style, false, false).text)
    }
}
