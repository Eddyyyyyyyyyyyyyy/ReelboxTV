package com.reelix.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遥控器按键路由的断言。
 *
 * 这些用例存在的唯一理由：**每一条都对应一个在电视上真实发生过（或极易发生）的问题**。
 * 有了它们，改焦点逻辑时不用上电视就能确认没退化。
 */
class KeyRoutingTest {
    @Test fun `选集横轨下方存在按钮时上下交接按键`() {
        assertEquals(-1, KeyRouting.episodeRowExit(true, false, true))
        assertEquals(1, KeyRouting.episodeRowExit(false, true, true))
        assertEquals(0, KeyRouting.episodeRowExit(false, true, false))
        assertEquals(0, KeyRouting.episodeRowExit(false, false, true))
    }

    @Test fun `控制条隐藏或显示左右都直接跳转`() {
        for (visible in listOf(false, true)) {
            val v = KeyRouting.playerTopLevel(true, false, visible, isRight = true)
            assertEquals(1, v.seekDirection)
            assertTrue(v.consume)
            assertTrue(v.revealControls)
        }
    }
    @Test fun `面板打开左右不能快进也不能切播放状态`() {
        val v = KeyRouting.playerTopLevel(true, false, true, menuOpen = true, isLeft = true)
        assertFalse(v.consume)
        assertEquals(0, v.seekDirection)
    }
    @Test fun `确认切换播放且长按重复不连切`() {
        assertTrue(KeyRouting.playerTopLevel(true, false, false, isConfirm = true).togglePlay)
        assertFalse(KeyRouting.playerTopLevel(true, false, true, isConfirm = true, repeatCount = 2).togglePlay)
    }
    @Test fun `控制条显示按下直接打开面板`() {
        assertTrue(KeyRouting.playerTopLevel(true, false, true, isDown = true).openMenu)
    }

    @Test fun `选集向上经过分页再关闭面板向下进入下一组`() {
        val pages = listOf(true, false, false)
        assertEquals(0 to true, KeyRouting.panelVertical(0, false, true, pages))
        assertNull(KeyRouting.panelVertical(0, true, true, pages))
        assertEquals(0 to false, KeyRouting.panelVertical(0, true, false, pages))
        assertEquals(1 to false, KeyRouting.panelVertical(0, false, false, pages))
    }

    @Test fun `编辑片头时返回只结束编辑且确认抬起不点击选项`() {
        assertEquals(KeyRouting.PanelAction.FINISH_EDIT,
            KeyRouting.panelInput(true, false, false, false, true, true, 0))
        assertEquals(KeyRouting.PanelAction.CONSUME,
            KeyRouting.panelInput(false, false, false, true, false, true, 0))
    }

    // ================= 播放页顶层 =================

    @Test
    fun `遥控器返回键必须永远放行`() {
        // README Bug 2：控制层隐藏时把 BACK 也吞了，用户按返回只弹出控制层、退不出去。
        for (visible in listOf(true, false)) {
            val v = KeyRouting.playerTopLevel(
                isKeyDown = true,
                isBack = true,
                controlsVisible = visible,
            )
            assertFalse("controlsVisible=$visible 时 BACK 不能拦截", v.consume)
            assertFalse("BACK 也不该触发唤出控制层", v.revealControls)
        }
    }

    @Test
    fun `控制层隐藏时其它键先唤出控制层`() {
        val v = KeyRouting.playerTopLevel(
            isKeyDown = true,
            isBack = false,
            controlsVisible = false,
        )
        assertTrue("应当吃掉这次按键", v.consume)
        assertTrue("并唤出控制层", v.revealControls)
    }

    @Test
    fun `控制层显示时放行给内部控件`() {
        val v = KeyRouting.playerTopLevel(
            isKeyDown = true,
            isBack = false,
            controlsVisible = true,
        )
        assertFalse("否则按钮和进度条都点不动", v.consume)
        assertFalse(v.revealControls)
    }

    @Test
    fun `按键抬起事件一律放行`() {
        // 不移除抬起事件会导致一次按键触发两次（唤出→立刻又被吃掉）
        val v = KeyRouting.playerTopLevel(
            isKeyDown = false,
            isBack = false,
            controlsVisible = false,
        )
        assertFalse(v.consume)
        assertFalse(v.revealControls)
    }

    // ================= 设置项 =================

    @Test
    fun `浏览态按确认键应触发开关切换`() {
        val a = KeyRouting.settingItem(
            isKeyDown = true, isConfirm = true, isBack = false, editing = false,
        )
        assertEquals(KeyRouting.Action.CONFIRM, a)
    }

    @Test
    fun `编辑态按确认键只是结束输入`() {
        val a = KeyRouting.settingItem(
            isKeyDown = true, isConfirm = true, isBack = false, editing = true,
        )
        assertEquals("不能冒泡去触发别的按钮", KeyRouting.Action.FINISH_EDITING, a)
    }

    @Test
    fun `编辑态按返回键收起键盘但不退出设置页`() {
        // 这是电视上最容易做坏的一处：返回键本该只收键盘，
        // 结果直接把整个设置页退了，用户刚输的地址白填。
        val a = KeyRouting.settingItem(
            isKeyDown = true, isConfirm = false, isBack = true, editing = true,
        )
        assertEquals(KeyRouting.Action.FINISH_EDITING, a)
    }

    @Test
    fun `浏览态按返回键交给外层`() {
        val a = KeyRouting.settingItem(
            isKeyDown = true, isConfirm = false, isBack = true, editing = false,
        )
        assertNull("浏览态返回 = 退出设置页，不是这一层的事", a)
    }

    @Test
    fun `设置项不处理方向键`() {
        val a = KeyRouting.settingItem(
            isKeyDown = true, isConfirm = false, isBack = false, editing = false,
        )
        assertNull("方向键交给 Compose 的焦点遍历", a)
    }

    // ================= 进度条 =================

    @Test
    fun `右键快进 10 秒`() {
        val p = KeyRouting.seekBar(
            isKeyDown = true, isLeft = false, isRight = true,
            positionMs = 30_000, durationMs = 600_000,
        )
        assertEquals(40_000L, p)
    }

    @Test
    fun `左键快退 10 秒`() {
        val p = KeyRouting.seekBar(
            isKeyDown = true, isLeft = true, isRight = false,
            positionMs = 30_000, durationMs = 600_000,
        )
        assertEquals(20_000L, p)
    }

    @Test
    fun `片头按左键不能出负数`() {
        // ExoPlayer 收到负 seek 位置会抛 IllegalArgumentException
        val p = KeyRouting.seekBar(
            isKeyDown = true, isLeft = true, isRight = false,
            positionMs = 3_000, durationMs = 600_000,
        )
        assertEquals(0L, p)
    }

    @Test
    fun `片尾按右键不能超过总时长`() {
        val p = KeyRouting.seekBar(
            isKeyDown = true, isLeft = false, isRight = true,
            positionMs = 598_000, durationMs = 600_000,
        )
        assertEquals(600_000L, p)
    }

    @Test
    fun `时长未知时不做 seek`() {
        assertNull(
            KeyRouting.seekBar(
                isKeyDown = true, isLeft = false, isRight = true,
                positionMs = 10_000, durationMs = 0,
            )
        )
    }

    @Test
    fun `进度条不处理其它键`() {
        assertNull(
            KeyRouting.seekBar(
                isKeyDown = true, isLeft = false, isRight = false,
                positionMs = 10_000, durationMs = 600_000,
            )
        )
    }

    @Test
    fun `第一次返回不退出`() {
        assertFalse(KeyRouting.shouldExitOnSecondBack(lastBackAt = 0L, now = 10_000L))
    }

    @Test
    fun `第二次返回在窗口内退出`() {
        assertTrue(
            KeyRouting.shouldExitOnSecondBack(
                lastBackAt = 10_000L,
                now = 11_500L,
                windowMs = 2_000L,
            )
        )
    }

    @Test
    fun `第二次返回超出窗口不退出`() {
        assertFalse(
            KeyRouting.shouldExitOnSecondBack(
                lastBackAt = 10_000L,
                now = 12_001L,
                windowMs = 2_000L,
            )
        )
    }

    @Test
    fun `时钟回拨不误退出`() {
        assertFalse(
            KeyRouting.shouldExitOnSecondBack(
                lastBackAt = 10_000L,
                now = 9_999L,
                windowMs = 2_000L,
            )
        )
    }

    // ================= 海报墙第一行 UP =================

    @Test
    fun `长按到顶按 130ms 节奏跨层，过快的重复不连续退出`() {
        val v = KeyRouting.gridUpAtTop(
            atTopRow = true,
            isKeyDown = true,
            repeatCount = 0,
            lastExitAt = 10_000L,
            now = 10_100L,
            crossMs = 130L,
        )
        assertTrue("网格顶部必须吃掉这次 UP", v.consume)
        assertFalse("距上一次跨层只有 100ms，不能再次退出", v.exitToHeader)
    }

    @Test
    fun `停手后再按一次才回分类行`() {
        val v = KeyRouting.gridUpAtTop(
            atTopRow = true,
            isKeyDown = true,
            repeatCount = 0,
            lastExitAt = 10_000L,
            now = 10_400L,
            crossMs = 130L,
        )
        assertTrue(v.consume)
        assertTrue("安静超过阈值，视为重新按下", v.exitToHeader)
    }

    @Test
    fun `长按超过 130ms 可以继续跨下一层`() {
        val v = KeyRouting.gridUpAtTop(
            atTopRow = true,
            isKeyDown = true,
            repeatCount = 3,
            lastExitAt = 10_000L,
            now = 11_000L,
            crossMs = 130L,
        )
        assertTrue(v.consume)
        assertTrue("长按超过节奏后允许继续跨层", v.exitToHeader)
    }

    @Test
    fun `第一行抬起事件只消费不退出`() {
        val v = KeyRouting.gridUpAtTop(
            atTopRow = true,
            isKeyDown = false,
            repeatCount = 0,
            lastExitAt = 10_000L,
            now = 10_500L,
            crossMs = 130L,
        )
        assertTrue("KeyUp 必须吃掉，否则 Leanback 会当默认处理", v.consume)
        assertFalse(v.exitToHeader)
    }

    @Test
    fun `不在第一行时 UP 放行`() {
        val v = KeyRouting.gridUpAtTop(
            atTopRow = false,
            isKeyDown = true,
            repeatCount = 0,
            lastExitAt = 10_000L,
            now = 10_500L,
        )
        assertFalse("本函数只负责第一行；非第一行由调用方自行移动", v.consume)
        assertFalse(v.exitToHeader)
    }

    @Test
    fun `进入页面后第一次按 UP 直接回分类行`() {
        val v = KeyRouting.gridUpAtTop(
            atTopRow = true,
            isKeyDown = true,
            repeatCount = 0,
            lastExitAt = 0L,
            now = 10_000L,
        )
        assertTrue(v.consume)
        assertTrue("没有上一次记录时视为一次新按下", v.exitToHeader)
    }

    // ================= 海报墙行首计算 =================

    @Test
    fun `非行首下标回到本行行首`() {
        // 6 列时 53 是第 8 行第 5 列，行首是 48。
        assertEquals(48, KeyRouting.gridRowStart(position = 53, columns = 6))
    }

    @Test
    fun `行首下标保持不变`() {
        assertEquals(48, KeyRouting.gridRowStart(position = 48, columns = 6))
        assertEquals(0, KeyRouting.gridRowStart(position = 0, columns = 6))
    }

    @Test
    fun `非法列数不产生负下标`() {
        assertEquals(53, KeyRouting.gridRowStart(position = 53, columns = 0))
        assertEquals(0, KeyRouting.gridRowStart(position = -1, columns = 6))
    }

    // ================= 海报墙长按步进闸 =================
    //
    // 真机现象（2026-11-04）：长按 UP/DOWN 有明显顿挫。
    // 原因是长按 repeat 约每 50ms 一次，而每次移动都会启动一次约 250ms 的
    // 滚动动画，动画没播完就被下一次按键重置。所以要在移动之前先过一道
    // 时间闸，只放行节奏够慢的按键。

    @Test
    fun `进入网格后的第一次按键必须放行`() {
        assertTrue(
            "没有上一次记录时不能吞掉用户的第一次按键",
            KeyRouting.gridStepAllowed(lastStepAt = 0L, now = 10_000L, stepMs = 140L),
        )
    }

    @Test
    fun `长按的快速 repeat 要被拦住`() {
        assertFalse(
            "间隔 50ms 是长按 repeat，放行会让动画反复被打断",
            KeyRouting.gridStepAllowed(lastStepAt = 10_000L, now = 10_050L, stepMs = 140L),
        )
    }

    @Test
    fun `刚好达到阈值的按键放行`() {
        assertTrue(
            "边界值必须放行，否则实际步进会变成两倍阈值",
            KeyRouting.gridStepAllowed(lastStepAt = 10_000L, now = 10_140L, stepMs = 140L),
        )
    }

    @Test
    fun `正常手速的连按不受影响`() {
        assertTrue(
            "200ms 一次属于正常连按，不能被闸门吃掉",
            KeyRouting.gridStepAllowed(lastStepAt = 10_000L, now = 10_200L, stepMs = 140L),
        )
    }

    @Test
    fun `时钟回拨时不放行`() {
        assertFalse(
            "uptime 理论上不回退，但真回退时不能当成新按键",
            KeyRouting.gridStepAllowed(lastStepAt = 10_000L, now = 9_999L, stepMs = 140L),
        )
    }

    @Test
    fun `阈值非正数等同于关闭节流`() {
        assertTrue(KeyRouting.gridStepAllowed(lastStepAt = 10_000L, now = 10_001L, stepMs = 0L))
        assertTrue(KeyRouting.gridStepAllowed(lastStepAt = 10_000L, now = 10_001L, stepMs = -5L))
    }

    @Test
    fun `控制层隐藏时 DOWN 与 UP 一样只唤出控制层`() {
        val v = KeyRouting.playerTopLevel(
            isKeyDown = true,
            isBack = false,
            controlsVisible = false,
            isDown = true,
        )
        assertTrue("应当吃掉这次按键", v.consume)
        assertTrue(v.revealControls)
        assertFalse("第一次 DOWN 不能跳过进度条直接开面板", v.openMenu)
    }

    @Test
    fun `控制层隐藏时其它键只唤出控制层`() {
        val v = KeyRouting.playerTopLevel(
            isKeyDown = true,
            isBack = false,
            controlsVisible = false,
            isDown = false,
        )
        assertTrue(v.revealControls)
        assertFalse("控制层隐藏时不该顺手开面板", v.openMenu)
    }

    @Test
    fun `BACK 分层：面板开着只关面板`() {
        assertEquals(
            "面板开着时 BACK 必须先关面板，不能把控制层或整页一起退掉",
            KeyRouting.PlayerBackAction.CLOSE_PANEL,
            KeyRouting.playerBack(panelOpen = true, controlsVisible = true),
        )
    }

    @Test
    fun `BACK 分层：控制层可见只关控制层`() {
        assertEquals(
            "控制层可见时 BACK 只收控制层，不退出播放",
            KeyRouting.PlayerBackAction.HIDE_CONTROLS,
            KeyRouting.playerBack(panelOpen = false, controlsVisible = true),
        )
    }

    @Test
    fun `BACK 分层：都关着才退出`() {
        assertEquals(
            KeyRouting.PlayerBackAction.EXIT,
            KeyRouting.playerBack(panelOpen = false, controlsVisible = false),
        )
    }

    @Test
    fun `长按快进的阻尼分档`() {
        assertEquals("短按就是 10 秒，够微调", 10_000L, KeyRouting.seekStepFor(0L))
        assertEquals(10_000L, KeyRouting.seekStepFor(600L))
        assertEquals("过了 0.6 秒进第二档", 20_000L, KeyRouting.seekStepFor(601L))
        assertEquals(40_000L, KeyRouting.seekStepFor(1_600L))
        assertEquals(80_000L, KeyRouting.seekStepFor(2_600L))
        assertEquals("4 秒后封顶 160 秒", 160_000L, KeyRouting.seekStepFor(4_100L))
    }

    @Test
    fun `负数按住时长按最短档处理`() {
        assertEquals(10_000L, KeyRouting.seekStepFor(-500L))
    }

    @Test
    fun `跳过时间编辑的长按阻尼分档`() {
        assertEquals("短按就是 1 秒，够微调", 1_000L, KeyRouting.skipStepFor(0L))
        assertEquals(1_000L, KeyRouting.skipStepFor(600L))
        assertEquals("过了 0.6 秒进第二档", 2_000L, KeyRouting.skipStepFor(601L))
        assertEquals(5_000L, KeyRouting.skipStepFor(1_600L))
        assertEquals(10_000L, KeyRouting.skipStepFor(2_600L))
        assertEquals("4 秒后封顶 30 秒", 30_000L, KeyRouting.skipStepFor(4_100L))
    }

    @Test
    fun `跳过时间编辑负数按住时长按最短档`() {
        assertEquals(1_000L, KeyRouting.skipStepFor(-500L))
    }
}
