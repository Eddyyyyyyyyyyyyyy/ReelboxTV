package com.reelix.app.ui.component

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun `手机浮层或未保存对话框独占返回`() {
        assertEquals(KeyRouting.SettingsBackAction.DEFER, KeyRouting.settingsBack(true, false, true))
        assertEquals(KeyRouting.SettingsBackAction.DEFER, KeyRouting.settingsBack(false, false, true))
    }

    @Test fun `返回从授权到内容再到分类最后退出`() {
        assertEquals(KeyRouting.SettingsBackAction.CLOSE_AUTH, KeyRouting.settingsBack(true, true, false))
        assertEquals(KeyRouting.SettingsBackAction.CLOSE_AUTH, KeyRouting.settingsBack(false, true, false))
        assertEquals(KeyRouting.SettingsBackAction.RETURN_TO_RAIL, KeyRouting.settingsBack(true, false, false))
        assertEquals(KeyRouting.SettingsBackAction.EXIT, KeyRouting.settingsBack(false, false, false))
    }

    @Test fun `编辑控件消费按下后孤立抬起不能退出设置`() {
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsBackKey(false, true, false))
    }

    @Test fun `返回只在有效配对抬起执行一次`() {
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsBackKey(true, true, false))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsBackKey(true, true, true, repeatCount = 4))
        assertEquals(KeyRouting.ChoiceAction.APPLY, KeyRouting.settingsBackKey(false, true, true))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsBackKey(false, true, true, cancelled = true))
    }

    @Test fun `设置失去所有权不处理返回或其他键`() {
        assertEquals(KeyRouting.ChoiceAction.IGNORE, KeyRouting.settingsBackKey(true, true, false, enabled = false))
        assertEquals(KeyRouting.ChoiceAction.IGNORE, KeyRouting.settingsBackKey(true, false, false))
    }

    @Test fun `仅在设置空壳持有焦点时恢复而不拦截正常子控件`() {
        assertTrue(KeyRouting.settingsRecoverKey(true, true, true))
        assertFalse(KeyRouting.settingsRecoverKey(true, false, true))
        assertFalse(KeyRouting.settingsRecoverKey(false, true, true))
        assertFalse(KeyRouting.settingsRecoverKey(true, true, false))
    }

    @Test fun `分类右键只在按下时打开左右抬起不漏到内容`() {
        assertEquals(KeyRouting.ChoiceAction.APPLY, KeyRouting.settingsRailKey(true, right = true, left = false))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsRailKey(false, right = true, left = false))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsRailKey(true, right = true, left = false, repeatCount = 2))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.settingsRailKey(true, right = false, left = true))
        assertEquals(KeyRouting.ChoiceAction.IGNORE, KeyRouting.settingsRailKey(true, right = false, left = false))
    }
}
