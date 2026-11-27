package com.reelix.app.ui.theme.sidebar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarGeometryTest {
    @Test fun fullHdHomeMatchesOriginalAnchorsAndFocusClearance() {
        val g = SidebarGeometry(1920)
        val card = sidebarHistoryCardStyle()
        val clearance = card.focusClearancePx
        val h = g.home(104, 34, 90, 66, 0, 39, card.height() + 2 * clearance, clearance)
        assertEquals(9, clearance)
        assertEquals(175, g.left)
        assertEquals(850, g.homeTitleWidth)
        assertEquals(700, g.homeOverviewWidth)
        assertEquals(230, h.titleTop)
        assertEquals(350, h.metadataTop)
        assertEquals(404, h.overviewTop)
        assertEquals(520, h.actionsTop)
        assertEquals(744, h.headerTop)
        assertEquals(797, h.historyTop)
        assertEquals(806, h.historyTop + clearance)
        assertEquals(1080, h.contentHeight)
    }

    @Test fun onlyExplicitExpansionChangesDefaultHistoryAnchors() {
        val g = SidebarGeometry(1920)
        val card = sidebarHistoryCardStyle()
        val clearance = card.focusClearancePx
        val height = card.height() + 2 * clearance
        val normal = g.home(104, 34, 90, 66, 0, 39, height, clearance)
        val expanded = g.home(104, 34, 90, 66, 0, 39, height, clearance, expanded = true)
        assertEquals(744, normal.headerTop)
        assertEquals(806, normal.historyTop + clearance)
        assertEquals(144, expanded.headerTop)
        assertEquals(206, expanded.historyTop + clearance)
        assertEquals(normal.titleTop, expanded.titleTop)
        val restored = g.home(104, 34, 90, 66, 0, 39, height, clearance, expanded = false)
        assertEquals(normal, restored)
    }

    @Test fun episodePresentationUsesReferenceCardsAndCompleteOuterStrokeClearance() {
        val episodes = sidebarEpisodePresentation()
        assertEquals(320, episodes.widthPx)
        assertEquals(197, episodes.heightPx)
        assertEquals(32, episodes.gapPx)
        assertEquals(38f, episodes.titleTextPx, 0f)
        assertEquals(22f, episodes.subtitleTextPx, 0f)
        assertEquals(9, episodes.focusClearancePx)
        assertEquals(72, episodes.captionHeightPx)
        assertEquals(299, episodes.cellHeightPx)
    }

    @Test fun fullHdDetailMatchesOriginalColumnsAndResources() {
        val g = SidebarGeometry(1920)
        val d = g.detail(107, 34, 153, 156, 31)
        assertEquals(1200, g.detailTitleWidth)
        assertEquals(580, g.actionColumnWidth)
        assertEquals(805, g.overviewLeft)
        assertEquals(1015, g.detailOverviewWidth)
        assertEquals(300, d.titleTop)
        assertEquals(428, d.metadataTop)
        assertEquals(550, d.workspaceTop)
        assertEquals(723, d.sourceTop)
        assertEquals(885, d.resourceTop)
    }

    @Test fun scaleComesFromFullHostNotDensityOrNavigationInsets() {
        val full = SidebarGeometry(1920)
        val half = SidebarGeometry(960)
        assertEquals(full.scale / 2f, half.scale, 0f)
        assertEquals(88, half.left)
        assertEquals(425, half.homeTitleWidth)
        assertEquals(290, half.actionColumnWidth)
        assertEquals(443, half.px(885))
    }

    @Test fun resourceColumnExpandsAncestorsWithoutMovingVisibleContent() {
        val geometry = SidebarGeometry(1920)
        val clearance = sidebarEpisodePresentation().focusClearancePx
        val bounds = geometry.resourceColumn(885, clearance)
        assertEquals(166, bounds.left)
        assertEquals(876, bounds.top)
        assertEquals(1663, bounds.width)
        assertEquals(geometry.left, bounds.left + bounds.padding)
        assertEquals(885, bounds.top + bounds.padding)
        assertEquals(geometry.detailWidth, bounds.width - 2 * bounds.padding)
        assertEquals(bounds.left, bounds.left + bounds.padding - clearance)
        assertEquals(bounds.width, geometry.detailWidth + 2 * clearance)
    }

    @Test fun resourceColumnPreservesOriginAcrossHostScalesAndClearances() {
        for (width in listOf(960, 1280, 1920, 3840)) {
            val geometry = SidebarGeometry(width)
            for (referenceClearance in listOf(0, sidebarEpisodePresentation().focusClearancePx, 24)) {
                val clearance = geometry.px(referenceClearance)
                val top = geometry.px(885)
                val bounds = geometry.resourceColumn(top, clearance)
                assertEquals(geometry.left, bounds.left + bounds.padding)
                assertEquals(top, bounds.top + bounds.padding)
                assertEquals(geometry.detailWidth, bounds.width - 2 * bounds.padding)
                assertTrue(bounds.left >= 0)
                assertTrue(bounds.left + bounds.width <= width)
            }
        }
    }

    @Test fun longHomeTitleMovesOverviewActionsAndRailWithoutOverlap() {
        val g = SidebarGeometry(1920)
        val card = sidebarHistoryCardStyle()
        val clearance = card.focusClearancePx
        val height = card.height() + 2 * clearance
        val h = g.home(416, 68, 90, 120, 40, 78, height, clearance)
        assertTrue(h.metadataTop >= h.titleTop + 416 + 16)
        assertTrue(h.overviewTop >= h.metadataTop + 68 + 20)
        assertTrue(h.actionsTop >= h.overviewTop + 90 + 26)
        assertTrue(h.auxiliaryTop >= h.actionsTop + 120 + 17)
        assertTrue(h.headerTop >= h.auxiliaryTop + 40 + 72)
        assertTrue(h.historyTop + clearance >= h.headerTop + 78 + 23)
        assertTrue(h.contentHeight >= h.historyTop + height + 24 - clearance)
    }

    @Test fun sixLineOverviewAndSourcePushResourcesBelowBothColumns() {
        val g = SidebarGeometry(1920)
        val d = g.detail(214, 68, 200, 420, 80)
        assertTrue(d.workspaceTop >= d.metadataTop + 68 + 88)
        assertEquals(d.workspaceTop + 420 + 17, d.sourceTop)
        assertTrue(d.resourceTop >= d.sourceTop + 80 + 48)
        assertTrue(d.resourceTop > 885)
        val tools = g.detail(107, 34, 500, 52, 0)
        assertTrue(tools.resourceTop >= tools.workspaceTop + 500 + 48)
    }

    @Test fun absentOptionalTextDoesNotLeavePhantomParagraphMargins() {
        val g = SidebarGeometry(1920)
        val card = sidebarHistoryCardStyle()
        val clearance = card.focusClearancePx
        val h = g.home(104, 0, 0, 66, 0, 39, card.height() + 2 * clearance, clearance)
        assertEquals(334, h.metadataTop)
        assertEquals(h.metadataTop, h.overviewTop)
        assertEquals(360, h.actionsTop)
        assertEquals(744, h.headerTop)
        val d = g.detail(107, 0, 153, 0, 0)
        assertEquals(407, d.metadataTop)
        assertEquals(550, d.workspaceTop)
        assertEquals(d.workspaceTop, d.sourceTop)
        assertEquals(885, d.resourceTop)
    }

    @Test fun narrowAndZeroWidthKeepMeasurementWidthsPositive() {
        for (width in listOf(0, 1, 320, 960, 1280, 1920, 3840)) {
            val g = SidebarGeometry(width)
            assertTrue(g.width > 0)
            assertTrue(g.homeTitleWidth > 0)
            assertTrue(g.detailTitleWidth > 0)
            assertTrue(g.actionColumnWidth > 0)
            assertTrue(g.detailOverviewWidth > 0)
            assertTrue(g.left + g.detailWidth <= g.width)
            val d = g.detail(g.px(107), g.px(34), g.px(153), g.px(156), 0)
            assertTrue(d.resourceTop >= d.workspaceTop + g.px(153))
        }
    }
}
