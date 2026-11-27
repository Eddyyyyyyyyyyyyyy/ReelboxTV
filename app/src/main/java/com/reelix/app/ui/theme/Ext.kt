package com.reelix.app.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 文本选中色跟随强调色，避免系统默认的蓝色跳出来破坏单色体系。
 */
@Composable
fun RxProvideTextSelection(content: @Composable () -> Unit) {
    val colors = TextSelectionColors(
        handleColor = RxColors.Accent,
        backgroundColor = RxColors.Accent.copy(alpha = 0.30f)
    )
    CompositionLocalProvider(LocalTextSelectionColors provides colors, content = content)
}

/** 常用 alpha 派生色，省得到处写 copy(alpha=) */
fun Color.at(alpha: Float): Color = this.copy(alpha = alpha)
