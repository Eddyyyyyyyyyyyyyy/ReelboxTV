package com.reelix.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusRowGeometryTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = FocusBox(left, top, right, bottom)

    @Test
    fun `up picks the nearest row above instead of the topmost`() {
        val current = box(300, 900, 460, 1060)
        val candidates = listOf(
            box(0, 100, 200, 150),    // 顶部播放键（最远，不能被选中）
            box(300, 400, 460, 450),  // 中间行
            box(0, 700, 200, 750),    // 紧邻的上一行
        )
        assertEquals(2, FocusRowGeometry.pickVertical(current, candidates, up = true))
    }

    @Test
    fun `up prefers the column closest horizontally inside the band`() {
        val current = box(600, 900, 760, 1060)
        val candidates = listOf(
            box(0, 400, 140, 450),
            box(640, 400, 780, 450),
            box(1200, 400, 1340, 450),
        )
        assertEquals(1, FocusRowGeometry.pickVertical(current, candidates, up = true))
    }

    @Test
    fun `overlapping columns win over distant ones`() {
        val current = box(300, 900, 460, 1060)
        val candidates = listOf(
            box(1500, 400, 1640, 450),
            box(280, 400, 420, 450),
        )
        assertEquals(1, FocusRowGeometry.pickVertical(current, candidates, up = true))
    }

    @Test
    fun `no candidate in the requested direction returns null`() {
        val current = box(300, 100, 460, 150)
        val candidates = listOf(box(300, 400, 460, 450))
        assertNull(FocusRowGeometry.pickVertical(current, candidates, up = true))
    }

    @Test
    fun `rows with slightly different heights still count as one band`() {
        val current = box(300, 900, 460, 1060)
        val candidates = listOf(
            box(300, 400, 460, 448),
            box(300, 418, 460, 462),
            box(300, 200, 460, 250),
        )
        assertEquals(0, FocusRowGeometry.pickVertical(current, candidates, up = true))
    }

    @Test
    fun `down picks the nearest row below`() {
        val current = box(300, 100, 460, 150)
        val candidates = listOf(
            box(300, 400, 460, 450),
            box(300, 700, 460, 750),
        )
        assertEquals(0, FocusRowGeometry.pickVertical(current, candidates, up = false))
    }
}
