package com.reelix.app.ui.theme

import com.reelix.app.data.settings.AppearancePreferences
import com.reelix.app.ui.theme.layout.NativeThemeId
import com.reelix.app.ui.theme.layout.NativeThemeRegistry
import org.junit.Assert.*
import org.junit.Test

class NativeThemeRegistryTest {
    @Test fun `原主题默认保留且未知值回退`() {
        assertEquals("ORIGINAL", AppearancePreferences().normalized().layoutTheme)
        assertEquals("ORIGINAL", AppearancePreferences(layoutTheme = "broken").normalized().layoutTheme)
        assertNull(NativeThemeRegistry.create("ORIGINAL"))
        assertNull(NativeThemeRegistry.create("broken"))
    }
    @Test fun `三套原生布局拥有不同注册身份`() {
        val themes = listOf("GALLERY", "SIDEBAR", "TOPBAR").map { requireNotNull(NativeThemeRegistry.create(it)) }
        assertEquals(setOf(NativeThemeId.GALLERY, NativeThemeId.SIDEBAR, NativeThemeId.TOPBAR), themes.map { it.id }.toSet())
        assertEquals(3, themes.map { it.javaClass.name }.toSet().size)
    }
    @Test fun `只切换布局不改变海报尺寸和导航配置`() {
        val original = AppearancePreferences(order = listOf("SEARCH", "HOME", "ONLINE", "LIBRARY"),
            hidden = setOf("ONLINE"), posterColumns = 4)
        val changed = original.copy(layoutTheme = "SIDEBAR").normalized()
        assertEquals(original.order, changed.order)
        assertEquals(original.hidden, changed.hidden)
        assertEquals(4, changed.posterColumns)
        assertEquals("SIDEBAR", changed.layoutTheme)
    }
}
