package com.reelix.app.ui.component

import org.junit.Assert.*
import org.junit.Test

class ChoicePopupKeyTest {
    @Test fun `下拉方向键不确认选择也不穿透`() {
        assertEquals(KeyRouting.ChoiceAction.UP, KeyRouting.choicePopupKey(true, up = true))
        assertEquals(KeyRouting.ChoiceAction.DOWN, KeyRouting.choicePopupKey(true, down = true))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.choicePopupKey(false, down = true))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.choicePopupKey(true, horizontal = true))
    }
    @Test fun `返回在抬键时取消防止退出详情`() {
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.choicePopupKey(true, back = true))
        assertEquals(KeyRouting.ChoiceAction.CANCEL, KeyRouting.choicePopupKey(false, back = true))
    }
    @Test fun `OK完整按下抬起才应用不借用打开按钮的抬键`() {
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.choicePopupKey(true, confirm = true))
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.choicePopupKey(false, confirm = true))
        assertEquals(KeyRouting.ChoiceAction.APPLY, KeyRouting.choicePopupKey(false, confirm = true, confirmOwned = true))
    }
}
