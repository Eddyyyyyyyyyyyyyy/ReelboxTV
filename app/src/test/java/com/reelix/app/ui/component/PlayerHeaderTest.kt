package com.reelix.app.ui.component

import org.junit.Assert.*
import org.junit.Test

class PlayerHeaderTest {
    @Test fun `选集倒序入口和页码轨道可上下往返`() {
        assertEquals(KeyRouting.PanelHeaderAction.HEADER, KeyRouting.panelHeaderInput(true, false, false, true, true, true))
        assertEquals(KeyRouting.PanelHeaderAction.PAGES, KeyRouting.panelHeaderInput(false, true, true, false, true, true))
        assertEquals(KeyRouting.PanelHeaderAction.CLOSE, KeyRouting.panelHeaderInput(true, false, true, false, true, true))
    }
    @Test fun `短剧无页码仍能到达倒序按钮且不影响其他组`() {
        assertEquals(KeyRouting.PanelHeaderAction.HEADER, KeyRouting.panelHeaderInput(true, false, false, false, true, false))
        assertEquals(KeyRouting.PanelHeaderAction.OPTIONS, KeyRouting.panelHeaderInput(false, true, true, false, true, false))
        assertEquals(KeyRouting.PanelHeaderAction.PASS, KeyRouting.panelHeaderInput(true, false, false, false, false, false))
        assertEquals(KeyRouting.PanelHeaderAction.PASS, KeyRouting.panelHeaderInput(true, false, false, false, true, true))
    }
}
