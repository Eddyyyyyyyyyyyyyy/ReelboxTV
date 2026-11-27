package com.reelix.app.ui.screen.settings

import org.junit.Assert.*
import org.junit.Test

class NativeSettingsSessionTest {
    @Test fun `退出检查所有分类草稿而不是最后预览分类`() {
        val session = NativeSettingsSession()
        session.draft(NativeSettingsSection.LIBRARY).edit("url", "draft")
        session.draft(NativeSettingsSection.ONLINE).edit("subscription", "draft")
        session.section = NativeSettingsSection.ABOUT
        assertEquals(setOf(NativeSettingsSection.LIBRARY, NativeSettingsSection.ONLINE), session.dirtySections)
        session.draft(NativeSettingsSection.ONLINE).discard(emptyMap())
        assertEquals(setOf(NativeSettingsSection.LIBRARY), session.dirtySections)
        session.reset()
        assertTrue(session.dirtySections.isEmpty())
    }
    @Test fun `外部状态刷新不能覆盖未保存输入`() {
        val draft = NativeSettingsDraft()
        draft.sync(mapOf("url" to "saved"))
        draft.edit("url", "editing")
        draft.sync(mapOf("url" to "phone-update"))
        assertEquals("editing", draft.value("url"))
        assertTrue(draft.dirty)
    }
    @Test fun `无修改时接收手机更新`() {
        val draft = NativeSettingsDraft()
        draft.sync(mapOf("url" to "saved"))
        draft.sync(mapOf("url" to "phone-update"))
        assertEquals("phone-update", draft.value("url"))
        assertFalse(draft.dirty)
    }
    @Test fun `放弃修改采用当前保存值而不是旧快照`() {
        val draft = NativeSettingsDraft()
        draft.sync(mapOf("url" to "saved"))
        draft.edit("url", "editing")
        draft.discard(mapOf("url" to "phone-update"))
        assertEquals("phone-update", draft.value("url"))
        assertFalse(draft.dirty)
    }
    @Test fun `不同分类草稿隔离且返回分类不丢输入`() {
        val session = NativeSettingsSession()
        session.draft(NativeSettingsSection.LIBRARY).sync(mapOf("url" to "saved"))
        session.draft(NativeSettingsSection.LIBRARY).edit("url", "editing")
        session.section = NativeSettingsSection.ONLINE
        session.inContent = false
        assertEquals("editing", session.draft(NativeSettingsSection.LIBRARY).value("url"))
        assertFalse(session.draft(NativeSettingsSection.ONLINE).dirty)
    }
    @Test fun `重开设置不保留上次焦点和草稿`() {
        val session = NativeSettingsSession()
        session.section = NativeSettingsSection.DATA
        session.inContent = true
        session.focusKey = "clear-history"
        session.contentFocus[session.section] = "clear-history"
        session.reset()
        assertEquals(NativeSettingsSection.LIBRARY, session.section)
        assertEquals("rail:LIBRARY", session.focusKey)
        assertFalse(session.inContent)
        assertTrue(session.contentFocus.isEmpty())
    }
}
