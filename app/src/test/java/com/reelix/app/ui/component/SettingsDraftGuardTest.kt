package com.reelix.app.ui.component

import com.reelix.app.ui.screen.settings.SettingsDraftGuard
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftGuardTest {
    @Test fun `未保存连接离开必须确认返回继续编辑则不应用或丢弃`() {
        val guard = SettingsDraftGuard()
        var left = false
        var discarded = false
        guard.dirty = true; guard.discard = { discarded = true }
        guard.leave { left = true }
        assertFalse(left); assertFalse(discarded)
        guard.pending = null
        assertTrue(guard.dirty); assertFalse(discarded)
        guard.leave { left = true }; guard.confirmDiscard()
        assertTrue(left); assertTrue(discarded); assertFalse(guard.dirty)
    }
}
