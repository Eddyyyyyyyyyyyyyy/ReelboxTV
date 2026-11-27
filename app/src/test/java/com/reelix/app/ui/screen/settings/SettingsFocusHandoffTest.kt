package com.reelix.app.ui.screen.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsFocusHandoffTest {
    @Test fun `请求焦点但实际按钮未聚焦不能标记交接成功`() {
        val handoff = SettingsFocusHandoff()
        val ticket = handoff.generation
        handoff.acknowledge(ticket, controlFocused = false)
        assertTrue(handoff.pending(ticket))
        handoff.acknowledge(ticket, controlFocused = true)
        assertFalse(handoff.pending(ticket))
    }

    @Test fun `旧请求不能完成新页面的焦点交接`() {
        val handoff = SettingsFocusHandoff()
        val previous = handoff.generation
        val current = handoff.request()
        handoff.acknowledge(previous, controlFocused = true)
        assertFalse(handoff.pending(previous))
        assertTrue(handoff.pending(current))
    }

    @Test fun `退出后旧焦点回调失效`() {
        val handoff = SettingsFocusHandoff()
        val ticket = handoff.generation
        handoff.close()
        handoff.acknowledge(ticket, controlFocused = true)
        assertFalse(handoff.pending(ticket))
        assertFalse(handoff.pending(handoff.generation))
    }
}
