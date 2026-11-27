package com.reelix.app.ui.theme.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryGeometryTest {
    private val reference = GalleryGeometry(1920)

    @Test fun referencePosterMatchesOriginalNotOldTwoByThreeRatio() {
        assertEquals(GalleryGeometry.Box(100, 185, 355, 515), reference.poster())
    }

    @Test fun referenceHomeKeepsAllOriginalSections() {
        assertEquals(GalleryGeometry.Home(255, 416, 483, 930, 997),
            reference.home(53, 132, 39, 39, 390, true))
    }

    @Test fun historyRailReservesOuterFocusClearance() {
        val style = reference.historyStyle()
        val clearance = style.focusClearancePx
        assertEquals(10, clearance)
        val box = reference.rail(483, style.height(), clearance)
        assertEquals(GalleryGeometry.Box(90, 473, 1740, 410), box)
        assertEquals(100, box.x + clearance)
        assertEquals(483, box.y + clearance)
        assertEquals(1820, box.right - clearance)
    }

    @Test fun recentRailIsASeparateSection() {
        val style = reference.historyStyle()
        assertEquals(GalleryGeometry.Box(90, 987, 1740, 410),
            reference.rail(997, style.height(), style.focusClearancePx))
    }

    @Test fun noHistoryPromotesRealRecentLibraryWithoutFabricatingHistory() {
        val home = reference.home(0, 132, 0, 39, 390, false)
        assertEquals(416, home.recentTop)
        assertEquals(483, home.recentImageTop)
    }

    @Test fun longResumeAndCollectionMoveBothSectionsDown() {
        assertEquals(GalleryGeometry.Home(302, 503, 570, 1017, 1084),
            reference.home(100, 172, 39, 39, 390, true))
    }

    @Test fun largeCardLabelsMoveRecentSectionInsteadOfOverlappingIt() {
        val home = reference.home(53, 132, 39, 39, 480, true)
        assertEquals(1020, home.recentTop)
        assertTrue(home.recentTop >= home.historyImageTop + 480)
    }

    @Test fun longHistoryHeaderMovesItsImagesAndFollowingSection() {
        val home = reference.home(53, 132, 78, 39, 390, true)
        assertEquals(522, home.historyImageTop)
        assertEquals(969, home.recentTop)
    }

    @Test fun sampleDetailResourcesAreOnFirstScreenAt806() {
        assertEquals(GalleryGeometry.Detail(293, 365, 480, 552, 806),
            reference.detail(73, 34, 98, 39, 66))
    }

    @Test fun longTitlePushesOverviewActionsAndResourcesTogether() {
        assertEquals(GalleryGeometry.Detail(366, 438, 553, 625, 879),
            reference.detail(146, 34, 98, 39, 66))
    }

    @Test fun sixLineOverviewDoesNotCollideWithActionsOrResourceHeader() {
        val detail = reference.detail(73, 34, 294, 39, 66)
        assertEquals(748, detail.actionsTop)
        assertEquals(1002, detail.resourceTop)
        assertTrue(detail.resourceTop > detail.actionsTop + 66)
    }

    @Test fun missingSourceUsesNaturalFlowWithoutPlaceholderText() {
        val detail = reference.detail(73, 34, 98, 0, 66)
        assertEquals(463, detail.sourceTop)
        assertEquals(496, detail.actionsTop)
        assertEquals(806, detail.resourceTop)
    }

    @Test fun tallerRealActionRowExtendsResourceAnchor() {
        assertEquals(900, reference.detail(73, 34, 98, 39, 160).resourceTop)
    }

    @Test fun halfWidthScalesWholeScreenWithoutNavigationSubtraction() {
        val half = GalleryGeometry(960)
        assertEquals(GalleryGeometry.Box(50, 93, 178, 258), half.poster())
        assertEquals(63, half.px(125))
        assertEquals(403, half.px(806))
        assertEquals(30.5f, half.text(61f, 1f), 0.001f)
        assertEquals(39.65f, half.text(61f, 1.3f), 0.001f)
    }

    @Test fun scaledRailsStillContainOriginalImageOrigins() {
        for (width in listOf(960, 1280, 1920, 2560, 3840)) {
            val g = GalleryGeometry(width)
            val style = g.historyStyle()
            val clearance = style.focusClearancePx
            val rail = g.rail(g.px(483), style.height(), clearance)
            assertEquals(g.px(100), rail.x + clearance)
            assertEquals(g.px(483), rail.y + clearance)
        }
    }

    @Test fun episodePresentationUsesOriginalCardAndCompleteOuterStroke() {
        val style = reference.episodeStyle()
        assertEquals(320, style.widthPx)
        assertEquals(197, style.heightPx)
        assertEquals(32, style.gapPx)
        assertEquals(38f, style.titleTextPx, 0f)
        assertEquals(22f, style.subtitleTextPx, 0f)
        assertEquals(24, style.horizontalPaddingPx)
        assertEquals(20, style.verticalPaddingPx)
        assertEquals(16, style.cornerRadiusPx)
        assertEquals(9, style.focusClearancePx)
    }

    @Test fun episodeViewportClearanceKeepsVisibleCardAtOriginalAnchor() {
        val style = reference.episodeStyle()
        val clearance = style.focusClearancePx
        val headerTop = 806
        val headerHeight = 38
        val rootTop = headerTop + headerHeight + 24 - clearance
        assertEquals(859, rootTop)
        assertEquals(868, rootTop + clearance)
        assertEquals(215, style.heightPx + clearance * 2)
        assertEquals(91, 100 - clearance)
    }

    @Test fun halfWidthScalesBothPresentationContracts() {
        val g = GalleryGeometry(960)
        val history = g.historyStyle()
        val episodes = g.episodeStyle()
        assertEquals(158, history.imageHeightPx)
        assertEquals(5, history.focusClearancePx)
        assertEquals(160, episodes.widthPx)
        assertEquals(100, episodes.heightPx)
        assertEquals(5, episodes.focusClearancePx)
        assertEquals(19f, episodes.titleTextPx, 0f)
    }
}
