package com.reelix.app.ui.theme

import com.reelix.app.ui.theme.layout.resolveDetailToolLayouts
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailToolLayoutTest {

    @Test
    fun twoVisibleToolsKeepDeclaredWidthsAndGap() {
        val layouts = resolveDetailToolLayouts(
            visibleSlots = listOf(true, true),
            playWidthPx = 260,
            toolWidthsPx = listOf(170, 170),
            gapPx = 8,
        )
        assertEquals(170, layouts[0].widthPx)
        assertEquals(0, layouts[0].leftMarginPx)
        assertEquals(170, layouts[1].widthPx)
        assertEquals(8, layouts[1].leftMarginPx)
    }

    @Test
    fun loneArtworkToolMatchesPlayButtonWidth() {
        val layouts = resolveDetailToolLayouts(
            visibleSlots = listOf(false, true),
            playWidthPx = 260,
            toolWidthsPx = listOf(170, 170),
            gapPx = 8,
        )
        assertEquals(260, layouts[1].widthPx)
        assertEquals(0, layouts[1].leftMarginPx)
    }

    @Test
    fun loneFolderToolMatchesPlayButtonWidth() {
        val layouts = resolveDetailToolLayouts(
            visibleSlots = listOf(true, false),
            playWidthPx = 260,
            toolWidthsPx = listOf(170, 170),
            gapPx = 8,
        )
        assertEquals(260, layouts[0].widthPx)
        assertEquals(0, layouts[0].leftMarginPx)
    }
}
