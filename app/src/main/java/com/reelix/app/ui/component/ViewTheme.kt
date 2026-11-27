package com.reelix.app.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxRadius

/**
 * View 页面共用的一套颜色/字号/尺寸。
 *
 * Compose 侧的 `RxColors` / `RxType` 不能直接在 View 里用，这里只做一次转换，
 * 避免每个页面各写一套色值。
 */
internal object ViewTheme {
    val backdrop = RxColors.Backdrop.toArgb()
    val surface = RxColors.Surface.toArgb()
    val surfaceElevated = RxColors.SurfaceElevated.toArgb()
    val surfaceHigh = RxColors.SurfaceHigh.toArgb()
    val textPrimary = RxColors.TextPrimary.toArgb()
    val textSecondary = RxColors.TextSecondary.toArgb()
    val textTertiary = RxColors.TextTertiary.toArgb()
    val accent = RxColors.Accent.toArgb()
    val focusRing = Color.WHITE
    val posterPlaceholder = RxColors.PosterPlaceholder.toArgb()
    val posterPlaceholderEnd = RxColors.PosterPlaceholderEnd.toArgb()
    val favorite = RxColors.Favorite.toArgb()

    val radiusPoster = RxRadius.poster
    val radiusButton = RxRadius.button
}

internal fun Context.vDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

internal fun Context.textView(
    text: String = "",
    sizeSp: Float = 13f,
    color: Int = ViewTheme.textSecondary,
    gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    this.gravity = gravity
    includeFontPadding = false
}

internal fun roundedBackground(
    color: Int,
    radiusPx: Int,
    strokeWidthPx: Int = 0,
    strokeColor: Int = Color.TRANSPARENT,
): GradientDrawable = GradientDrawable().apply {
    cornerRadius = radiusPx.toFloat()
    setColor(color)
    if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
}

/**
 * 焦点态背景（预建好的两份 drawable，交给系统切状态）。
 *
 * ## 为什么不用「焦点监听里改背景」
 *
 * 原来的写法是：
 * ```
 * setOnFocusChangeListener { v, focused -> v.background = roundedBackground(...) }
 * ```
 * 而 `View.setBackground()` 内部会调 **`requestLayout()`** —— 焦点每移动一次，
 * 整棵视图树就重新测量一次。TV 上长按方向键一秒移动 5~10 次焦点，
 * 就是 5~10 次完整的 measure/layout，表现就是「焦点不跟手」。
 *
 * 这里用 `StateListDrawable`：两份形状在**创建时就建好**，
 * 之后焦点变化由 `View.onFocusChanged()` → `refreshDrawableState()` 自动切换，
 * **零分配、零 requestLayout、回调里一行代码都不用写**。
 *
 * 用法：
 * ```
 * view.background = focusStateBackground(normalColor, focusColor, radiusPx)
 * // setOnFocusChangeListener 整个可以删掉
 * ```
 *
 * ★ 注意 `addState` 的顺序：**带条件的状态必须先加，默认状态必须最后加**。
 */
internal fun focusStateBackground(
    normalColor: Int,
    focusColor: Int,
    radiusPx: Int,
    focusStrokeWidthPx: Int = 0,
    focusStrokeColor: Int = Color.TRANSPARENT,
    // 搜索框这类控件「未聚焦」时也有描边（只是更细、颜色更淡），
    // 所以普通态也要能配置描边，否则聚焦/失焦的边框宽度差会丢。
    normalStrokeWidthPx: Int = 0,
    normalStrokeColor: Int = Color.TRANSPARENT,
): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_focused),
        GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(focusColor)
            if (focusStrokeWidthPx > 0) setStroke(focusStrokeWidthPx, focusStrokeColor)
        }
    )
    addState(
        intArrayOf(),
        GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(normalColor)
            if (normalStrokeWidthPx > 0) setStroke(normalStrokeWidthPx, normalStrokeColor)
        }
    )
}

/**
 * 页面级焦点入口。
 *
 * TopBar / 详情返回只依赖这个接口，不关心页面内部是 Leanback 还是普通 View。
 */
interface PageView {
    fun focusFirst(): Boolean
    fun saveFocusIndex(): Int
    fun restoreFocus(index: Int)
}
