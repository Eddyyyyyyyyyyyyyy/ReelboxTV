package com.reelix.app.ui.theme.topbar

import com.reelix.app.ui.theme.layout.HistoryCardStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarGeometryTest {
    private fun clearance(width: Int = 1920): Int {
        val scale = TopBarGeometry.scale(width)
        return HistoryCardStyle(
            325, 182, 25, 23f, 18f,
            focusStrokePx = TopBarGeometry.px(4, scale),
            focusOutsetPx = TopBarGeometry.px(5, scale),
        ).focusClearancePx
    }

    @Test fun homeUsesTheApprovedFullCanvasAnchors() {
        val home = TopBarGeometry.home(1920, 802, 36, 212, clearance(), false)
        assertEquals(890, home.heroHeight)
        assertEquals(865, home.headerTop)
        assertEquals(9, clearance())
        assertEquals(TopBarRect(91, 915, 1748, 230), home.history)
        assertEquals(924, home.history.y + clearance())
    }

    @Test fun referenceViewportExposes156PixelsOfTheFirstImage() {
        val home = TopBarGeometry.home(1920, 802, 36, 212, clearance(), false)
        assertEquals(156, (1080 - home.history.y - clearance()).coerceIn(0, 182))
    }

    @Test fun historyBoundsIncludeAllSidesOfTheExternalFocusRing() {
        val home = TopBarGeometry.home(1920, 802, 36, 212, clearance(), false)
        assertEquals(100, home.history.x + clearance())
        assertEquals(1730, home.history.width - clearance() * 2)
        assertEquals(212, home.history.height - clearance() * 2)
    }

    @Test fun expandedHistoryRetainsACompleteMeasuredRail() {
        val home = TopBarGeometry.home(1920, 802, 36, 212, clearance(), true)
        assertEquals(128, home.headerTop)
        assertEquals(178, home.history.y)
        assertEquals(230, home.history.height)
        assertTrue(home.history.bottom < 1080)
    }

    @Test fun longHomeCopyMovesTheShelfInsteadOfOverlappingActions() {
        val home = TopBarGeometry.home(1920, 1000, 36, 212, clearance(), false)
        assertEquals(1063, home.headerTop)
        assertEquals(1088, home.heroHeight)
        assertTrue(home.history.y > 1000)
    }

    @Test fun canvasScalingDoesNotDependOnAndroidDensityOrNavigationInsets() {
        val home = TopBarGeometry.home(960, 401, 18, 106, clearance(960), false)
        assertEquals(445, home.heroHeight)
        assertEquals(433, home.headerTop)
        assertEquals(45, home.history.x)
        assertEquals(875, home.history.width)
        assertEquals(116, home.history.height)
    }

    @Test fun detailResourceSectionIsAllowedInsideTheFirstViewport() {
        val detail = TopBarGeometry.detail(1920, 104, 35, 76, 94, 38)
        assertEquals(350, detail.titleTop)
        assertEquals(475, detail.metadataTop)
        assertEquals(566, detail.actionsTop)
        assertEquals(672, detail.overviewTop)
        assertEquals(778, detail.sourceTop)
        assertEquals(901, detail.resourcesTop)
        assertTrue(detail.resourcesTop < 1080)
    }

    @Test fun absentOptionalCopyDoesNotInventSourceRows() {
        val detail = TopBarGeometry.detail(1920, 104, 0, 76, 0, 0)
        assertEquals(566, detail.actionsTop)
        assertEquals(901, detail.resourcesTop)
    }

    @Test fun twoTitleLinesMoveActionsWithoutChangingTheirGap() {
        val detail = TopBarGeometry.detail(1920, 208, 35, 76, 95, 39)
        assertEquals(579, detail.metadataTop)
        assertEquals(670, detail.actionsTop)
        assertEquals(776, detail.overviewTop)
    }

    @Test fun sixLineOverviewAndActualSourceStayBeforeResources() {
        val detail = TopBarGeometry.detail(1920, 104, 35, 76, 284, 39)
        assertEquals(968, detail.sourceTop)
        assertEquals(1091, detail.resourcesTop)
        assertTrue(detail.resourcesTop >= detail.sourceTop + 39 + 84)
    }

    @Test fun largePrimaryActionsAlsoGrowTheInformationRegion() {
        val detail = TopBarGeometry.detail(1920, 104, 35, 120, 95, 39)
        assertEquals(716, detail.overviewTop)
        assertTrue(detail.resourcesTop > 901)
    }

    @Test fun degenerateHostSizesNeverProduceNegativeRailDimensions() {
        for (width in listOf(0, 1, 640, 1280, 1920, 2560)) {
            for (expanded in listOf(false, true)) {
                val home = TopBarGeometry.home(width, 0, 0, 0, 0, expanded)
                assertTrue(home.heroHeight >= 0)
                assertTrue(home.history.width >= 0)
                assertTrue(home.history.height >= 0)
            }
        }
    }

    @Test fun episodeCardOriginAndCompleteOutsideRingMatchTheOriginal() {
        val rail = TopBarGeometry.episodes(1920, 963, 100, clearance(), 0)
        assertEquals(TopBarRect(91, 954, 1738, 118), rail)
        assertEquals(963, rail.y + clearance())
    }

    @Test fun sharedEpisodeHeightCanGrowWithoutClippingText() {
        val rail = TopBarGeometry.episodes(1920, 963, 100, clearance(), 166)
        assertEquals(166, rail.height)
    }
}
