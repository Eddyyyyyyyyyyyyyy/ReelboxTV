package com.reelix.app.ui.component

/**
 * 电视遥控器的按键语义层。
 *
 * ## 为什么要抽这一层
 *
 * 之前按键判断散在三个文件里（`PlayerScreen` 的返回键放行、`SettingControls` 的
 * 设置项确认、`PlayerControls` 的进度条左右快进），每处各写一遍 `Key.Back` /
 * `Key.DirectionCenter`，然后各自踩坑。README 里记的 Bug 2（**遥控器 BACK 退不出播放页**）
 * 就是这么来的：控制层隐藏时"拦截所有按键"的分支把 `BACK` 也吞了。
 *
 * 这类 bug 的共性：**按键该归谁处理，是按当前界面状态决定的**，
 * 而状态判断被埋在了 Composable 里 —— 只能上电视按遥控器才能发现。
 *
 * 抽成纯函数后：
 * - 状态机可以在 PC 上跑断言（见 `KeyRoutingTest`，不加设备就能测）
 * - 电视上只需验证"焦点框实际怎么走"，不需要再验证"逻辑对不对"
 *
 * ## 职责边界（很重要）
 *
 * 这一层**只做路由决策，不做副作用**。返回 `Consumed = true` 表示"这次按键我吃了，
 * 不要再往下传"；具体动作（唤出控制层、存进度退出）由调用方执行。
 */
object KeyRouting {
    fun searchDeleteStep(isKeyDown: Boolean, confirm: Boolean, held: Boolean,
        now: Long, lastDeleteAt: Long): Boolean =
        isKeyDown && confirm && (!held || (now >= lastDeleteAt && now - lastDeleteAt >= 100L))

    enum class SettingsBackAction { DEFER, CLOSE_AUTH, RETURN_TO_RAIL, EXIT }

    fun settingsBack(inContent: Boolean, authPage: Boolean, overlayOpen: Boolean): SettingsBackAction = when {
        overlayOpen -> SettingsBackAction.DEFER
        authPage -> SettingsBackAction.CLOSE_AUTH
        inContent -> SettingsBackAction.RETURN_TO_RAIL
        else -> SettingsBackAction.EXIT
    }

    fun settingsBackKey(isKeyDown: Boolean, isBack: Boolean, owned: Boolean,
        repeatCount: Int = 0, cancelled: Boolean = false, enabled: Boolean = true): ChoiceAction = when {
        !isBack || !enabled -> ChoiceAction.IGNORE
        isKeyDown -> ChoiceAction.CONSUME
        owned && !cancelled && repeatCount == 0 -> ChoiceAction.APPLY
        else -> ChoiceAction.CONSUME
    }

    fun settingsRecoverKey(isKeyDown: Boolean, shellHasFocus: Boolean, navigationOrConfirm: Boolean): Boolean =
        isKeyDown && shellHasFocus && navigationOrConfirm

    fun settingsRailKey(isKeyDown: Boolean, right: Boolean, left: Boolean, repeatCount: Int = 0): ChoiceAction = when {
        right -> if (isKeyDown && repeatCount == 0) ChoiceAction.APPLY else ChoiceAction.CONSUME
        left -> ChoiceAction.CONSUME
        else -> ChoiceAction.IGNORE
    }
    enum class ChoiceAction { IGNORE, CONSUME, UP, DOWN, APPLY, CANCEL }

    /** The popup owns the entire key pair, including Back's release. */
    fun choicePopupKey(isKeyDown: Boolean, up: Boolean = false, down: Boolean = false,
        horizontal: Boolean = false, back: Boolean = false, confirm: Boolean = false,
        confirmOwned: Boolean = false): ChoiceAction = when {
        back -> if (isKeyDown) ChoiceAction.CONSUME else ChoiceAction.CANCEL
        confirm -> if (!isKeyDown && confirmOwned) ChoiceAction.APPLY else ChoiceAction.CONSUME
        up -> if (isKeyDown) ChoiceAction.UP else ChoiceAction.CONSUME
        down -> if (isKeyDown) ChoiceAction.DOWN else ChoiceAction.CONSUME
        horizontal -> ChoiceAction.CONSUME
        else -> ChoiceAction.IGNORE
    }

    enum class ConfirmAction { NONE, HOLD, CLICK, LONG_CLICK }

    /** A matching Down owns the whole sequence; focus loss cancels its owner. */
    fun confirmPress(enabled: Boolean, down: Boolean, hasDown: Boolean, heldMs: Long): ConfirmAction = when {
        !enabled -> ConfirmAction.NONE
        down -> ConfirmAction.HOLD
        !hasDown -> ConfirmAction.NONE
        heldMs >= 550L -> ConfirmAction.LONG_CLICK
        else -> ConfirmAction.CLICK
    }

    /**
     * 一级页面双击 BACK 退出的判定。
     *
     * 第一次 BACK 只记录时间并提示；第二次必须落在 [windowMs] 内。
     * 时钟回拨或没有第一次记录时一律不退出，避免误杀进程。
     */
    fun shouldExitOnSecondBack(
        lastBackAt: Long,
        now: Long,
        windowMs: Long = 2_000L,
    ): Boolean {
        if (lastBackAt <= 0L || windowMs <= 0L) return false
        if (now < lastBackAt) return false
        return now - lastBackAt <= windowMs
    }


    /**
     * 播放页顶层按键的结果。
     *
     * @param consume 是否拦截，不再传给下层
     * @param revealControls 是否要顺手把控制层唤出来
     */
    data class PlayerVerdict(
        val consume: Boolean,
        val revealControls: Boolean = false,
        /** 是否同时打开选项面板（DOWN 专用） */
        val openMenu: Boolean = false,
        val seekDirection: Int = 0,
        val togglePlay: Boolean = false,
    )

    /**
     * 播放页最外层容器的按键判定。
     *
     * 核心规则（**顺序不能改**）：
     * ```
     * 1. 只处理按下事件（抬起事件一律放行，否则会双触发）
     * 2. BACK 永远放行 → 交给 playerBack 决定层级
     * 3. 控制层已显示 → 放行给内部控件（按钮、进度条）
     * 4. 控制层隐藏 + 其它键 → 吃掉，并唤出控制层
     * 5. 控制层隐藏 + DOWN   → 只唤出控制层，下一次 DOWN 才开面板
     * ```
     *
     * ★ 第 2 条必须在第 4 条之前。反过来写就是 Bug 2：
     *   BACK 被"唤出控制层"分支吞掉，用户觉得返回键坏了。
     *
     * ★ 2026-11-20 调整：DOWN 与控制层隐藏时的 UP 一致，先只唤出进度条；
     *   进度条显示后再次 DOWN 才进入选集/选项面板，避免一按就跳过进度条。
     */
    fun playerTopLevel(
        isKeyDown: Boolean,
        isBack: Boolean,
        controlsVisible: Boolean,
        isDown: Boolean = false,
        menuOpen: Boolean = false,
        isLeft: Boolean = false,
        isRight: Boolean = false,
        isConfirm: Boolean = false,
        repeatCount: Int = 0,
    ): PlayerVerdict {
        if (isBack) return PlayerVerdict(consume = false)
        if (menuOpen) return PlayerVerdict(consume = false)
        val owned = isLeft || isRight || isConfirm || isDown
        if (!isKeyDown) return PlayerVerdict(consume = owned)
        if (isLeft || isRight) return PlayerVerdict(true, true,
            seekDirection = if (isLeft) -1 else 1)
        if (isConfirm) return PlayerVerdict(true, true, togglePlay = repeatCount == 0)
        if (controlsVisible) {
            // 进度条已经显示时，DOWN 才是“进入选集/选项面板”的第二段按键。
            if (isDown) return PlayerVerdict(true, true, openMenu = true)
            return PlayerVerdict(consume = false)
        }
        // 控制层隐藏时，UP 和 DOWN 都只负责唤出控制层。
        // 旧行为把 DOWN 直接开面板，用户看不到进度条过渡（2026-11-20 反馈）。
        return PlayerVerdict(
            consume = true,
            revealControls = true,
        )
    }

    /** Native rails own horizontal navigation; vertical movement has one panel owner. */
    enum class PanelHeaderAction { PASS, HEADER, PAGES, OPTIONS, CLOSE }
    fun panelHeaderInput(up: Boolean, down: Boolean, onHeader: Boolean, onPages: Boolean,
        hasAction: Boolean, paginated: Boolean): PanelHeaderAction = when {
        !hasAction -> PanelHeaderAction.PASS
        onHeader && up -> PanelHeaderAction.CLOSE
        onHeader && down -> if (paginated) PanelHeaderAction.PAGES else PanelHeaderAction.OPTIONS
        up && (onPages || !paginated) -> PanelHeaderAction.HEADER
        else -> PanelHeaderAction.PASS
    }

    fun panelVertical(group: Int, onPages: Boolean, up: Boolean, paginated: List<Boolean>): Pair<Int, Boolean>? {
        if (paginated.isEmpty()) return null
        if (up && !onPages && paginated[group]) return group to true
        if (!up && onPages) return group to false
        if (up && group == 0) return null
        return (group + if (up) -1 else 1).coerceIn(paginated.indices) to false
    }

    fun panelBoundary(isLeft: Boolean, isRight: Boolean, index: Int, count: Int): Boolean =
        (isLeft && index <= 0) || (isRight && index >= count - 1)

    enum class PanelAction { PASS, CONSUME, UP, DOWN, INCREASE_SKIP, DECREASE_SKIP, FINISH_EDIT, CLOSE }
    fun panelInput(down: Boolean, up: Boolean, lower: Boolean, confirm: Boolean,
        back: Boolean, editing: Boolean, repeat: Int): PanelAction {
        if (editing) return when {
            !down -> PanelAction.CONSUME
            back || confirm -> if (repeat == 0) PanelAction.FINISH_EDIT else PanelAction.CONSUME
            up -> PanelAction.INCREASE_SKIP
            lower -> PanelAction.DECREASE_SKIP
            else -> PanelAction.CONSUME
        }
        if (!down) return if (up || lower || back) PanelAction.CONSUME else PanelAction.PASS
        return when {
            back -> if (repeat == 0) PanelAction.CLOSE else PanelAction.CONSUME
            up -> PanelAction.UP
            lower -> PanelAction.DOWN
            else -> PanelAction.PASS
        }
    }

    /** BACK 在播放页的三种落点，按"最内层先响应"排序 */
    enum class PlayerBackAction {
        /** 面板开着 → 只关面板 */
        CLOSE_PANEL,

        /** 控制层可见 → 只关控制层，不退出播放 */
        HIDE_CONTROLS,

        /** 都关着 → 退出播放页 */
        EXIT,
    }

    /**
     * 播放页 BACK 的分层判定。
     *
     * ★ 真机行为：BACK 从内到外一层层退，不能一上来就退出播放。
     *   面板 → 控制层 → 退出，三步。之前直接 exitWithSave()，
     *   用户按 BACK 想收面板，结果整页被关掉（2026-11-06 反馈）。
     */
    fun playerBack(
        panelOpen: Boolean,
        controlsVisible: Boolean,
        hasError: Boolean = false,
    ): PlayerBackAction = when {
        panelOpen -> PlayerBackAction.CLOSE_PANEL
        hasError -> PlayerBackAction.EXIT
        controlsVisible -> PlayerBackAction.HIDE_CONTROLS
        else -> PlayerBackAction.EXIT
    }

    /**
     * 设置项（文本框 / 开关）的按键判定。
     *
     * @param editing 文本框是否处于编辑态（键盘弹出）
     * @return [Action] 要执行的动作，或者 null 表示放行
     */
    fun settingItem(
        isKeyDown: Boolean,
        isConfirm: Boolean,
        isBack: Boolean,
        editing: Boolean,
    ): Action? {
        if (!isKeyDown) return null

        return when {
            // 编辑态：确认键 = 完成输入（不要冒泡去触发别的按钮）
            editing && isConfirm -> Action.FINISH_EDITING
            // 编辑态：返回键 = 收起键盘、退到浏览态，**不退出设置页**
            editing && isBack -> Action.FINISH_EDITING
            // 浏览态：确认键 = 切换开关 / 开始编辑
            !editing && isConfirm -> Action.CONFIRM
            // 浏览态：返回键 = 交给外层（退出设置页）
            else -> null
        }
    }

    /**
     * 进度条的按键判定：左右键快进快退 10 秒。
     *
     * ★ 结果要**夹到 [0, duration]**。早期这里直接加减，片头按左键会得到负数，
     *   ExoPlayer 会抛 IllegalArgumentException。
     */
    /**
     * 长按左右时每步的跳跃量 —— **阻尼式加速**。
     *
     * 短按一次固定 10 秒；按住不放，随按住时长分档提速：
     * ```
     * 0 – 0.6s    10s   （够用来微调）
     * 0.6 – 1.5s  20s
     * 1.5 – 2.5s  40s
     * 2.5 – 4.0s  80s
     * > 4.0s     160s
     * ```
     * 分档而不是连续加速，是为了让结果可预期 —— 连续曲线在遥控器上
     * 很难判断"我现在是几倍速"，用户会过冲。
     *
     * @param heldMs 从按下到现在的时间；<0 视为 0
     */
    fun seekStepFor(heldMs: Long): Long = when {
        heldMs <= 600L -> 10_000L
        heldMs <= 1_500L -> 20_000L
        heldMs <= 2_500L -> 40_000L
        heldMs <= 4_000L -> 80_000L
        else -> 160_000L
    }

    /**
     * 片头/片尾跳过设置里，编辑时间时长按上下键的每步增量 —— **阻尼式加速**。
     *
     * 短按一次 ±1 秒（满足微调 1–2 秒）；按住不放随按住时长分档提速：
     * ```
     * 0 – 0.6s    1s
     * 0.6 – 1.5s  2s
     * 1.5 – 2.5s  5s
     * 2.5 – 4.0s  10s
     * > 4.0s      30s
     * ```
     * 分档的原因和 seekStepFor 一样：让结果可预期，避免连续加速时过冲。
     *
     * @param heldMs 从按下到现在的时间；<0 视为 0
     */
    fun skipStepFor(heldMs: Long): Long = when {
        heldMs <= 600L -> 1_000L
        heldMs <= 1_500L -> 2_000L
        heldMs <= 2_500L -> 5_000L
        heldMs <= 4_000L -> 10_000L
        else -> 30_000L
    }

    fun seekBar(
        isKeyDown: Boolean,
        isLeft: Boolean,
        isRight: Boolean,
        positionMs: Long,
        durationMs: Long,
        stepMs: Long = 10_000L,
    ): Long? {
        if (!isKeyDown) return null
        if (durationMs <= 0L) return null

        return when {
            isLeft -> (positionMs - stepMs).coerceIn(0L, durationMs)
            isRight -> (positionMs + stepMs).coerceIn(0L, durationMs)
            else -> null
        }
    }

    /**
     * 海报墙第一行 UP 的判定结果。
     *
     * @param consume 是否在网格层吃掉这次 UP
     * @param exitToHeader 是否交回分类行
     */
    data class GridUpVerdict(
        val consume: Boolean,
        val exitToHeader: Boolean,
    )

    /**
     * 海报墙第一行的 UP 判定。
     *
     * ★ 不能只看 `repeatCount == 0`。实测部分电视遥控器长按时，每一次 repeat
     *   都会以 `repeatCount == 0` 重新下发；长按过程中焦点卡片也可能被重绑，
     *   事件换了接收者。只看 repeatCount 会把长按的第一帧当成"重新按了一下"，
     *   焦点跳回分类行后，后续按键继续往上就进了顶部导航栏（2026-11-04 复现）。
     *
     * 所以再加一道时间闸：两次跨层之间至少间隔 [crossMs]，才允许再次退出。
     * 单次按键只跨一层；长按约 130ms 后可以继续跨下一层。
     */
    fun gridUpAtTop(
        atTopRow: Boolean,
        isKeyDown: Boolean,
        repeatCount: Int,
        lastExitAt: Long,
        now: Long,
        crossMs: Long = 130L,
    ): GridUpVerdict {
        if (!atTopRow) return GridUpVerdict(consume = false, exitToHeader = false)
        if (!isKeyDown) return GridUpVerdict(consume = true, exitToHeader = false)

        val ready = lastExitAt <= 0L || (now >= lastExitAt && now - lastExitAt >= crossMs)
        return GridUpVerdict(
            consume = true,
            exitToHeader = ready,
        )
    }

    /**
     * 海报墙某一项所在行的行首下标。
     *
     * ★ 不要拿非行首下标直接 `setSelectedPosition`：Leanback 的
     *   `StaggeredGrid` 在目标行未布局时可能把该下标当成新一行的起点，
     *   焦点会跑到第一列。先选中行首，再点名聚焦目标项。
     */
    fun gridRowStart(position: Int, columns: Int): Int {
        if (position <= 0 || columns <= 0) return position.coerceAtLeast(0)
        return position - position % columns
    }

    /**
     * 海报墙长按时的步进闸：这一次按键允许移动吗？
     *
     * ★ 为什么需要：长按的 repeat 大约每 50ms 一次，而每次移动都会让
     *   Leanback 启动一次约 250ms 的滚动动画。动画没播完就被下一次按键
     *   重置，真机表现是"走一下被打断、再走一下"的顿挫，不是匀速滑动
     *   （2026-11-04 用户反馈"一直按着 up/down 不顺滑"）。
     *
     * 这里只做"放不放行"的判断，不移动焦点、不改时间戳 —— 调用方在被
     * 拒绝时要自己**吞掉**这次按键，同时**不要更新** lastStepAt，否则
     * 闸门会被 repeat 推着走，永远凑不满间隔。
     *
     * @param lastStepAt 上一次真正移动时的时间戳；0 表示还没动过
     * @param stepMs 两次移动的最小间隔；≤0 视为关闭节流（便于临时对照）
     */
    fun gridStepAllowed(lastStepAt: Long, now: Long, stepMs: Long = 140L): Boolean {
        if (stepMs <= 0L) return true
        if (lastStepAt <= 0L) return true
        if (now < lastStepAt) return false
        return now - lastStepAt >= stepMs
    }

    fun episodeRowExit(isUp: Boolean, isDown: Boolean, hasDownTarget: Boolean): Int = when {
        isUp -> -1
        isDown && hasDownTarget -> 1
        else -> 0
    }

    /** 设置项的按键动作 */
    enum class Action {
        /** 进入/确认（开关切换、文本框进入编辑） */
        CONFIRM,

        /** 结束编辑态（收键盘、保存），不退出页面 */
        FINISH_EDITING,
    }
}
