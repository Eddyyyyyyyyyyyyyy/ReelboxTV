package com.reelix.app.ui.theme.sidebar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.reelix.app.ui.theme.layout.NativeThemePalette
import kotlin.math.ceil
import kotlin.math.max

internal abstract class SidebarSurface(context: Context) : ViewGroup(context) {
    private val placements = LinkedHashMap<View, Rect>()

    init {
        isFocusable = false
        clipChildren = true
        clipToPadding = true
    }

    protected fun beginMeasure() { placements.clear() }

    protected fun node(view: View, width: Int, exactHeight: Int? = null, minimumHeight: Int = 0): Int {
        if (view.visibility == GONE) return 0
        val w = MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY)
        view.measure(w, MeasureSpec.makeMeasureSpec(exactHeight?.coerceAtLeast(0) ?: 0,
            if (exactHeight == null) MeasureSpec.UNSPECIFIED else MeasureSpec.EXACTLY))
        if (exactHeight == null && view.measuredHeight < minimumHeight) {
            view.measure(w, MeasureSpec.makeMeasureSpec(minimumHeight, MeasureSpec.EXACTLY))
        }
        return view.measuredHeight
    }

    protected fun text(
        view: TextView?, width: Int, g: SidebarGeometry, size: Float, lineHeight: Float,
        color: Int, weight: Int = 400, maxLines: Int = Int.MAX_VALUE,
    ): Int {
        if (view == null || view.visibility == GONE) return 0
        styleSidebarText(view, g, size, lineHeight, color, weight, maxLines)
        node(view, width)
        val height = max(view.measuredHeight,
            ceil(lineHeight * g.scale * resources.configuration.fontScale * view.lineCount.coerceAtLeast(1)).toInt())
        return node(view, width, exactHeight = height)
    }

    protected fun position(view: View, x: Int, y: Int) {
        if (view.visibility != GONE) placements[view] = Rect(x, y, x + view.measuredWidth, y + view.measuredHeight)
    }

    protected fun finishMeasure(width: Int, height: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        placements.forEach { (view, rect) -> view.layout(rect.left, rect.top, rect.right, rect.bottom) }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}

internal fun styleSidebarText(
    view: TextView, g: SidebarGeometry, size: Float, lineHeight: Float, color: Int,
    weight: Int = 400, maxLines: Int = Int.MAX_VALUE,
) {
    // Convert design px to sp without dividing by fontScale: accessibility scaling survives.
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * g.scale / view.resources.displayMetrics.density)
    val originalTypeface = view.typeface ?: Typeface.DEFAULT
    if (Build.VERSION.SDK_INT >= 28) {
        if (originalTypeface.weight != weight || originalTypeface.isItalic) {
            view.typeface = Typeface.create(originalTypeface, weight, false)
        }
    } else {
        val style = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
        if (originalTypeface.style != style) view.typeface = Typeface.create(originalTypeface, style)
    }
    view.setTextColor(color)
    view.includeFontPadding = false
    view.setHorizontallyScrolling(false)
    if (view.maxLines != maxLines) view.maxLines = maxLines
    view.ellipsize = if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END
    view.letterSpacing = 0f
    view.gravity = Gravity.START or Gravity.TOP
    val fontHeight = view.paint.fontMetrics.run { descent - ascent }
    view.setLineSpacing((lineHeight * g.scale * view.resources.configuration.fontScale - fontHeight).coerceAtLeast(0f), 1f)
}

internal fun styleHomeAction(view: View, g: SidebarGeometry, palette: NativeThemePalette, primary: Boolean) {
    view.minimumWidth = g.px(if (primary) 220 else 147)
    view.minimumHeight = g.px(66)
    if (view is TextView) {
        val normalText = if (primary) palette.onAccent else palette.primaryText
        styleSidebarText(view, g, 25f, 34f, normalText, weight = if (primary) 650 else 400, maxLines = 2)
        view.gravity = Gravity.CENTER
        view.maxWidth = g.px(if (primary) 500 else 380).coerceAtLeast(1)
        view.compoundDrawablePadding = g.px(12)
        view.compoundDrawablesRelative.filterNotNull().forEach { it.setBounds(0, 0, g.px(29), g.px(29)) }
        view.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(palette.secondaryText, normalText, normalText),
        ))
    }
    fun shape(fill: Int, focus: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = g.px(8).toFloat()
        if (focus) setStroke(g.px(4).coerceAtLeast(1), if (primary) palette.onAccent else palette.accent)
    }
    view.background = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(palette.surface))
        addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) palette.accent else palette.raisedSurface, true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(palette.raisedSurface))
        addState(intArrayOf(), shape(if (primary) palette.accent else palette.surface))
    }
    view.setPadding(g.px(28), 0, g.px(28), 0)
}

/** Exact Sidebar CSS stops; image acquisition and top-safe cropping belong to the adapter. */
internal class SidebarScrim(context: Context) : View(context) {
    private val paint = Paint()
    private var horizontal: Shader? = null
    private var vertical: Shader? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        horizontal = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xF0101010.toInt(), 0xB0101010.toInt(), 0x00101010),
            floatArrayOf(0f, .3f, .75f), Shader.TileMode.CLAMP,
        )
        vertical = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            intArrayOf(0xFF101010.toInt(), 0x00101010),
            floatArrayOf(0f, .55f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        // CSS paints the first-listed horizontal gradient over the vertical gradient.
        paint.shader = vertical
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = horizontal
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
