package com.reelix.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoicePopupLayoutTest {

    @Test
    fun opensBelowWhenThereIsEnoughRoom() {
        val layout = choicePopupLayout(
            belowPx = 500,
            abovePx = 300,
            itemCount = 4,
            rowHeightPx = 48,
            paddingPx = 8,
        )
        assertFalse(layout.openAbove)
        assertEquals(200, layout.heightPx)
    }

    @Test
    fun flipsAboveWhenBelowWouldClip() {
        val layout = choicePopupLayout(
            belowPx = 80,
            abovePx = 500,
            itemCount = 4,
            rowHeightPx = 48,
            paddingPx = 8,
        )
        assertTrue(layout.openAbove)
        assertEquals(200, layout.heightPx)
    }

    @Test
    fun showsFiveRowsWhenAboveHasRoom() {
        val layout = choicePopupLayout(
            belowPx = 120,
            abovePx = 500,
            itemCount = 8,
            rowHeightPx = 48,
            paddingPx = 8,
        )
        assertTrue(layout.openAbove)
        assertEquals(248, layout.heightPx)
    }
}
