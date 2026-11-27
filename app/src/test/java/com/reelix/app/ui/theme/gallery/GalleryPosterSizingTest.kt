package com.reelix.app.ui.theme.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryPosterSizingTest {
    private val geometry = GalleryGeometry(1920)

    @Test fun `导航统一后内容保留相对留白`() {
        assertEquals(152, geometry.navigationHeight)
        assertEquals(13, geometry.contentY(125))
        assertEquals(165, geometry.navigationHeight + geometry.contentY(125))
        assertEquals(928, geometry.contentHeight(1080))
    }
    @Test fun `五档海报同时调整宽高并保留文字和边框大小`() {
        val styles = (4..8).map(geometry::historyStyle)
        assertEquals(listOf(323, 258, 215, 184, 161), styles.map { it.imageWidthPx })
        assertEquals(listOf(473, 378, 315, 270, 236), styles.map { it.imageHeightPx })
        assertEquals(1, styles.map { it.titleTextPx }.distinct().size)
        assertEquals(1, styles.map { it.focusClearancePx }.distinct().size)
    }
    @Test fun `焦点外框在卡片自身范围内而非越界绘制`() {
        for (columns in 4..8) {
            val style = geometry.historyStyle(columns)
            assertEquals(style.imageWidthPx + 20, style.cellWidthPx)
            assertEquals(style.height() + 20, style.cellHeightPx())
            assertEquals(style.imageWidthPx + style.gapPx, style.cellWidthPx + style.cellGapPx)
        }
    }
    @Test fun `放大后最近加入区跟随真实卡片高度下移`() {
        val standard = geometry.home(53, 132, 39, 39, geometry.historyStyle().height(), true)
        val large = geometry.home(53, 132, 39, 39, geometry.historyStyle(4).height(), true)
        assertTrue(large.recentTop > standard.recentTop)
        assertTrue(large.recentTop > large.historyImageTop + geometry.historyStyle(4).height())
    }
    @Test fun `详情选集边框预留保持原来的图片步长`() {
        val style = geometry.episodeStyle()
        assertEquals(338, style.cellWidthPx)
        assertEquals(299, style.cellHeightPx)
        assertEquals(14, style.cellGapPx)
        assertEquals(style.widthPx + style.gapPx, style.cellWidthPx + style.cellGapPx)
    }
}
