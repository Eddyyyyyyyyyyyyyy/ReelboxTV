package com.reelix.app.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerControlsPolicyTest {
    @Test
    fun `zero seconds never auto hides`() {
        assertNull(controlsAutoHideDelayMs(0, true, true, false, false))
    }

    @Test
    fun `configured seconds are used verbatim`() {
        assertEquals(3_000L, controlsAutoHideDelayMs(3, true, true, false, false))
        assertEquals(5_000L, controlsAutoHideDelayMs(5, true, true, false, false))
        assertEquals(8_000L, controlsAutoHideDelayMs(8, true, true, false, false))
    }

    @Test
    fun `hidden controls or paused playback do not schedule another hide`() {
        assertNull(controlsAutoHideDelayMs(5, false, true, false, false))
        assertNull(controlsAutoHideDelayMs(5, true, false, false, false))
    }

    @Test
    fun `open episode or option panel keeps controls visible`() {
        assertNull(controlsAutoHideDelayMs(5, true, true, true, false))
        assertNull(controlsAutoHideDelayMs(5, true, true, false, true))
    }
}
