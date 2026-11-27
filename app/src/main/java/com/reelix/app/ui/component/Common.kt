package com.reelix.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelix.app.R
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxRadius
import com.reelix.app.ui.theme.RxSpace
import com.reelix.app.ui.theme.RxType
import com.reelix.app.ui.theme.at


/**
 * 次要按钮 —— 描边胶囊，比如"加入片库""切换线路"。
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        focusLevel = FocusLevel.LIFT,
        background = if (selected) RxColors.Accent.at(0.16f) else Color.White.at(0.06f),
        contentColor = if (selected) RxColors.Accent else RxColors.TextPrimary,
        borderColor = if (selected) RxColors.Accent.at(0.45f) else Color.White.at(0.10f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = 18.dp)
        ) {
            leading?.invoke()
            Text(text = text, style = RxType.Button)
        }
    }
}

/**
 * 所有可点击控件的统一底座 —— 处理焦点、点击、背景、圆角。
 *
 * 焦点的正确做法（踩过坑）：
 *  Compose 里 `Modifier.clickable` **本身就会注册一个焦点节点**。
 *  如果再叠一个 `Modifier.focusable()`，会出现两个焦点节点互相争抢，
 *  TV 端表现为"按方向键完全没反应、看不到焦点框"。
 *
 *  所以这里不再显式加 focusable，而是：
 *    1. 用 focusRequester 挂在 clickable 之前（请求焦点时能命中）
 *    2. 用 interactionSource.collectIsFocusedAsState 感知焦点状态
 *  FocusInteraction.Focus 是 clickable 内建的焦点通知，最可靠。
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 长按 OK 回调。
     *
     * 用途：历史/收藏页的"进入待删态"（借鉴 Reelbox 的交互）。
     * 实现方式：记录 KeyDown 时间，同一个键的 KeyUp 若超过阈值就判为长按。
     *
     * ★ 为什么不用 combinedClickable —— 它在电视上对长按的响应不稳定，
     *   而且和 focusable 混用时焦点事件会乱。手动计时更可控。
     */
    onLongClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(RxRadius.button.dp),
    focusLevel: FocusLevel = FocusLevel.LIFT,
    background: Color = Color.Transparent,
    /** 聚焦时的背景；null 表示沿用按钮默认的 SurfaceHigh 焦点底色。 */
    focusBackground: Color? = null,
    contentColor: Color = RxColors.TextPrimary,
    borderColor: Color? = null,
    /**
     * 是否由本组件绘制边框。
     *
     * ★ 海报墙必须传 `false` —— 卡片内部自己画焦点框（2dp 纯白，与在线一致）。
     *   留着它就会出现**两层框**：内层 2dp 白框被外层 3dp 橙框盖住，
     *   肉眼看到的是橙框（2026-11-04 真机复现：以为改了没生效）。
     *   非海报控件保持默认 true。
     */
    drawBorder: Boolean = true,
    enabled: Boolean = true,
    focusScale: Float? = null,
    focusRequester: FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var downAt by remember { mutableLongStateOf(0L) }
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    val longClick by androidx.compose.runtime.rememberUpdatedState(onLongClick)
    val localRequester = remember { FocusRequester() }
    val requester = focusRequester ?: localRequester
    val style = FocusStyle.of(focusLevel)
    val scale by animateFloatAsState(
        if (focused && enabled) focusScale ?: style.scale else 1f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 800f), label = "focusScale"
    )
    // Exactly one focus target and one confirm-key owner. Pointer handling never adds another target.
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) downAt = 0L
            }
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!confirm || !enabled) false else {
                    val down = event.type == KeyEventType.KeyDown
                    val now = android.os.SystemClock.uptimeMillis()
                    val action = KeyRouting.confirmPress(enabled, down, downAt != 0L,
                        if (downAt == 0L) 0 else now - downAt)
                    if (down && downAt == 0L) downAt = now
                    if (!down) downAt = 0L
                    when (action) {
                        KeyRouting.ConfirmAction.CLICK -> click()
                        KeyRouting.ConfirmAction.LONG_CLICK -> longClick?.invoke() ?: click()
                        else -> Unit
                    }
                    true
                }
            }
            .semantics {
                role = androidx.compose.ui.semantics.Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) { click(); true } else false }
            }
            .focusable(enabled = enabled)
            .pointerInput(enabled) {
                detectTapGestures(
                    onTap = { if (enabled) { requester.requestFocus(); click() } },
                    onLongPress = { if (enabled) { requester.requestFocus(); longClick?.invoke() } }
                )
            }
            .background(
                when {
                    !focused || !enabled -> background
                    focusBackground != null -> focusBackground
                    background != Color.White -> RxColors.SurfaceHigh
                    else -> background
                },
                shape
            )
            .then(
                if (!drawBorder) {
                    Modifier
                } else {
                    Modifier.border(
                        if (focused && enabled) 3.dp else 1.dp,
                        if (focused && enabled) RxColors.Accent else borderColor ?: Color.Transparent,
                        shape,
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content(focused) }
    }
}

/**
 * 统一的分区标题 —— 爆米花的标题特别轻：
 * 没有下划线、没有色块，只有字号和可选的"全部"。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = RxSpace.md.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = RxType.Section,
                color = RxColors.TextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = RxType.CardMeta,
                    color = RxColors.TextTertiary
                )
            }
        }
        if (onMore != null) {
            var focused by remember { mutableStateOf(false) }

    // 长按计时（遥控器路径）
    var keyDownAt by remember { mutableLongStateOf(0L) }

            Text(
                text = stringResource(R.string.action_see_all),
                style = RxType.CardMeta.copy(fontSize = 12.sp),
                color = if (focused) RxColors.Accent else RxColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(RxRadius.button.dp))
                    .clickable(onClick = onMore)
                    .focusable()
                    .onFocusChanged {
                focused = it.isFocused
                
            }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 底部渐变遮罩：把图片"融"进背景，避免硬切边。
 * 用在 Hero 大图和详情页 backdrop 下沿。
 *
 * 用 drawBehind 而不是 drawWithContent：
 * 这里只需要"在内容之下"画一层遮罩，drawBehind 更直接也更省。
 */
fun Modifier.bottomFade(
    height: Dp,
    color: Color = RxColors.Backdrop,
): Modifier = this.drawBehind {
    val h = height.toPx().coerceAtMost(size.height)
    if (h > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to color,
                startY = size.height - h,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - h),
            size = Size(size.width, h)
        )
    }
}

/** 顶部渐变遮罩，Hero 上沿避免和状态栏硬碰 */
fun Modifier.topFade(
    height: Dp,
    color: Color = RxColors.Backdrop,
): Modifier = this.drawBehind {
    val h = height.toPx().coerceAtMost(size.height)
    if (h > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to color,
                1f to Color.Transparent,
                startY = 0f,
                endY = h
            ),
            size = Size(size.width, h)
        )
    }
}
/** 长按判定阈值（毫秒） */
private const val LONG_PRESS_MS = 550L
/**
 * 消费掉当前手势剩下的所有事件（直到抬手）。
 *
 * 用途：长按触发后，把后续 up 吃掉，避免 clickable 再触发一次 onClick。
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}
