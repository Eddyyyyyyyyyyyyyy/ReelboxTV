package com.reelix.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSizeFormatTest {

    @Test
    fun formatsGigabytesAndMegabytes() {
        assertEquals("80.1GB", formatMediaSize(85_972_879_500))
        assertEquals("700MB", formatMediaSize(700L * 1024 * 1024))
    }

    @Test
    fun ignoresUnknownSize() {
        assertEquals("", formatMediaSize(0))
        assertEquals("", formatMediaSize(-1))
    }
}
