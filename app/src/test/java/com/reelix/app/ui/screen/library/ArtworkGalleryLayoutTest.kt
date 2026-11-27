package com.reelix.app.ui.screen.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkGalleryLayoutTest {

    @Test
    fun twoRowsAlwaysFitInsideRail() {
        listOf(220, 280, 358, 420).forEach { railHeight ->
            val cell = artworkRailCellSize(railHeight, gapPx = 12, labelHeightPx = 44)
            assertTrue("cell must be tall enough for image and label", cell.heightPx > cell.labelHeightPx)
            assertTrue("two rows plus gap must fit", cell.heightPx * 2 + 12 <= railHeight)
            assertEquals(railHeight, cell.heightPx * 2 + 12)
        }
    }

    @Test
    fun posterKeepsTwoByThreeRatio() {
        val cell = artworkRailCellSize(railHeightPx = 600, gapPx = 12, labelHeightPx = 44)
        assertEquals(cell.imageHeightPx * 2 / 3, cell.widthPx)
        assertTrue(cell.widthPx * 3 >= cell.imageHeightPx * 2 - 2)
    }

    @Test
    fun shortRailStillLeavesUsableCards() {
        val cell = artworkRailCellSize(railHeightPx = 120, gapPx = 12, labelHeightPx = 44)
        assertTrue(cell.imageHeightPx >= 40)
        assertTrue(cell.widthPx >= 40)
    }

    @Test
    fun largePreviewKeepsPortraitAndLandscapeRatios() {
        val size = artworkLargePreviewSize(
            mainWidthPx = 866,
            mainHeightPx = 384,
            textHeightPx = 68,
            gapPx = 16,
        )
        assertTrue(kotlin.math.abs(size.portraitHeightPx * 2 / 3 - size.portraitWidthPx) <= 1)
        assertTrue(kotlin.math.abs(size.landscapeWidthPx * 9 / 16 - size.landscapeHeightPx) <= 1)
        assertEquals(size.portraitWidthPx + 16 + size.landscapeWidthPx, size.columnWidthPx)
        assertTrue(size.columnWidthPx <= (866 * 0.58f).toInt() + 2)
        assertTrue(size.portraitHeightPx > 198)
    }

    @Test
    fun largePreviewShrinksOnNarrowScreens() {
        val size = artworkLargePreviewSize(
            mainWidthPx = 420,
            mainHeightPx = 240,
            textHeightPx = 60,
            gapPx = 12,
        )
        assertTrue(size.columnWidthPx <= (420 * 0.58f).toInt() + 2)
        assertTrue(size.portraitWidthPx > 0)
        assertTrue(size.landscapeHeightPx > 0)
    }

    @Test
    fun fullWidthPreviewUsesMostOfTheAvailableWidth() {
        val size = artworkLargePreviewSize(
            mainWidthPx = 900,
            mainHeightPx = 400,
            textHeightPx = 70,
            gapPx = 16,
            maxColumnRatio = 0.94f,
        )
        assertTrue(size.columnWidthPx <= (900 * 0.94f).toInt() + 2)
        assertTrue(size.columnWidthPx > 500)
        assertTrue(size.portraitHeightPx > 198)
    }
}
