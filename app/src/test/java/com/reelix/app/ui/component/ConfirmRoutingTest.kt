package com.reelix.app.ui.component

import org.junit.Assert.*
import org.junit.Test

class ConfirmRoutingTest {
    @Test fun `down is consumed without action`() {
        assertEquals(KeyRouting.ConfirmAction.HOLD, KeyRouting.confirmPress(true, true, false, 0))
    }
    @Test fun `unmatched up never clicks`() {
        assertEquals(KeyRouting.ConfirmAction.NONE, KeyRouting.confirmPress(true, false, false, 1000))
    }
    @Test fun `short press clicks once`() {
        assertEquals(KeyRouting.ConfirmAction.CLICK, KeyRouting.confirmPress(true, false, true, 200))
    }
    @Test fun `long press arms instead of clicking`() {
        assertEquals(KeyRouting.ConfirmAction.LONG_CLICK, KeyRouting.confirmPress(true, false, true, 550))
    }
    @Test fun `disabled never acts`() {
        assertEquals(KeyRouting.ConfirmAction.NONE, KeyRouting.confirmPress(false, false, true, 1000))
    }
}
