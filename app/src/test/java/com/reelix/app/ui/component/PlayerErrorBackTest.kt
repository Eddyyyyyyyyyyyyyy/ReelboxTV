package com.reelix.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorBackTest {
    @Test fun `播放报错时控制条可见也一次退出`() {
        assertEquals(KeyRouting.PlayerBackAction.EXIT,
            KeyRouting.playerBack(panelOpen = false, controlsVisible = true, hasError = true))
    }

    @Test fun `错误界面的选项面板仍先关闭本层`() {
        assertEquals(KeyRouting.PlayerBackAction.CLOSE_PANEL,
            KeyRouting.playerBack(panelOpen = true, controlsVisible = true, hasError = true))
    }

    @Test fun `正常播放维持原有分层返回`() {
        assertEquals(KeyRouting.PlayerBackAction.HIDE_CONTROLS,
            KeyRouting.playerBack(panelOpen = false, controlsVisible = true))
        assertEquals(KeyRouting.PlayerBackAction.EXIT,
            KeyRouting.playerBack(panelOpen = false, controlsVisible = false, hasError = true))
    }
}
