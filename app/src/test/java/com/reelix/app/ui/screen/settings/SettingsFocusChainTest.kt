package com.reelix.app.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsFocusChainTest {

    @Test fun `垂直移动保持列并在窄行钳制`() {
        val rows = listOf(3, 1, 3)
        assertEquals(0 to 0, SettingsFocusChain.verticalNeighbor(rows, 1, 0, -1))
        assertEquals(2 to 0, SettingsFocusChain.verticalNeighbor(rows, 1, 0, 1))
        // 从三列行下到单列行时钳到第 0 列。
        assertEquals(1 to 0, SettingsFocusChain.verticalNeighbor(rows, 0, 2, 1))
        // 从单列行上到三列行时仍落在第 0 列，而不是最后一列。
        assertEquals(0 to 0, SettingsFocusChain.verticalNeighbor(rows, 1, 0, -1))
    }

    @Test fun `首行向上和末行向下没有目标`() {
        val rows = listOf(1, 2)
        assertNull(SettingsFocusChain.verticalNeighbor(rows, 0, 0, -1))
        assertNull(SettingsFocusChain.verticalNeighbor(rows, 1, 1, 1))
    }

    @Test fun `跳过空行`() {
        val rows = listOf(1, 0, 1)
        assertEquals(2 to 0, SettingsFocusChain.verticalNeighbor(rows, 0, 0, 1))
        assertEquals(0 to 0, SettingsFocusChain.verticalNeighbor(rows, 2, 0, -1))
    }
}
