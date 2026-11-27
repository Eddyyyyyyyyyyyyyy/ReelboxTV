package com.reelix.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutThemeMigrationTest {

    @Test
    fun `legacy brand ids migrate to neutral ids`() {
        assertEquals("GALLERY", normalizedLayoutTheme("INFUSE"))
        assertEquals("SIDEBAR", normalizedLayoutTheme("NETFLIX"))
        assertEquals("TOPBAR", normalizedLayoutTheme("APPLE_TV"))
    }

    @Test
    fun `current ids pass through`() {
        assertEquals("ORIGINAL", normalizedLayoutTheme("ORIGINAL"))
        assertEquals("SIDEBAR", normalizedLayoutTheme("SIDEBAR"))
        assertEquals("TOPBAR", normalizedLayoutTheme("TOPBAR"))
        assertEquals("GALLERY", normalizedLayoutTheme("GALLERY"))
    }

    @Test
    fun `unknown id falls back to original`() {
        assertEquals("ORIGINAL", normalizedLayoutTheme("SOMETHING_ELSE"))
        assertEquals("ORIGINAL", normalizedLayoutTheme(""))
    }
}
