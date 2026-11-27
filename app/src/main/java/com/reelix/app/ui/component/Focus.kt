package com.reelix.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.at

/**
 * 焦点 / 非焦点的视觉参数，抽成数据类，避免各处硬编码。
 *
 * 三档（按爆米花的克制程度）：
 *  - [SUBTLE]：只加白边，不放大（列表项、菜单项）
 *  - [LIFT]  ：放大 + 白边（海报卡片）
 *  - [GLOW]  ：放大 + 强调色边（主要按钮）
 */
enum class FocusLevel { SUBTLE, LIFT, GLOW }

data class FocusStyle(
    val scale: Float,
    val ringColor: Color,
    val ringWidth: Dp,
) {
    companion object {
        fun of(level: FocusLevel, accent: Color = RxColors.Accent) = when (level) {
            FocusLevel.SUBTLE -> FocusStyle(1f, Color.White.at(0.72f), 1.5.dp)
            FocusLevel.LIFT -> FocusStyle(1.045f, Color.White.at(0.92f), 2.dp)
            FocusLevel.GLOW -> FocusStyle(1.03f, accent, 2.dp)
        }
    }
}

/**
 * 焦点感知容器：把 focused 状态抛给调用者，自己不做视觉处理。
 * 用于需要自定义焦点表现的地方（Hero 大图、自定义卡片）。
 */
@Composable
fun FocusAwareBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        contentAlignment = contentAlignment
    ) {
        content(focused)
    }
}

/**
 * 焦点缩放：只缩放视觉，不动布局。
 * 单独抽出来，方便只想缩放不想要描边的场合。
 */
@Composable
fun Modifier.focusScale(focused: Boolean, target: Float = 1.045f): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused) target else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 800f),
        label = "focusScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
