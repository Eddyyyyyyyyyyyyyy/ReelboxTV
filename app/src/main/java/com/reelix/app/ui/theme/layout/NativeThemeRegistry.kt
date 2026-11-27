package com.reelix.app.ui.theme.layout

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.TextView
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.vDp
import com.reelix.app.ui.theme.topbar.TopBarThemeLayout
import com.reelix.app.ui.theme.gallery.GalleryThemeLayout
import com.reelix.app.ui.theme.sidebar.SidebarThemeLayout

object NativeThemeRegistry {
    fun create(name: String): NativeThemeLayout? = when (name) {
        "GALLERY" -> GalleryThemeLayout()
        "SIDEBAR" -> SidebarThemeLayout()
        "TOPBAR" -> TopBarThemeLayout()
        else -> null
    }
}

internal fun NativeThemePalette.playButtonStyle(): PlayButtonStyle =
    play ?: PlayButtonStyle(
        focusedFill = accent,
        focusedText = onAccent,
        focusRing = accent,
    )

internal fun applyPlayButton(view: View, style: PlayButtonStyle) {
    view.background = focusStateBackground(
        normalColor = style.normalFill,
        focusColor = style.focusedFill,
        radiusPx = view.context.vDp(8),
        focusStrokeWidthPx = style.focusStrokePx,
        focusStrokeColor = style.focusRing,
    )
    val colors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(style.focusedText, style.normalText),
    )
    fun tint(node: View) {
        if (node is TextView) {
            node.isDuplicateParentStateEnabled = node !== view
            node.setTextColor(colors)
        } else if (node is android.view.ViewGroup) {
            (0 until node.childCount).forEach { tint(node.getChildAt(it)) }
        }
    }
    tint(view)
}

internal fun applyThemeAction(view: View, palette: NativeThemePalette, primary: Boolean, radiusDp: Int = 7) {
    val focusedFill = if (primary) palette.accent else palette.raisedSurface
    fun shape(fill: Int, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = view.context.vDp(radiusDp).toFloat()
        setColor(fill)
        if (focused) setStroke(view.context.vDp(2), if (primary) palette.onAccent else palette.focusRing)
    }
    view.background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(focusedFill, true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(focusedFill, true))
        addState(intArrayOf(), shape(if (primary) palette.accent else palette.surface, false))
    }
    val colors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(if (primary) palette.onAccent else palette.primaryText,
            if (primary) palette.onAccent else palette.primaryText,
            if (primary) palette.onAccent else palette.primaryText))
    fun tint(node: View) {
        if (node is TextView) { node.isDuplicateParentStateEnabled = node !== view; node.setTextColor(colors) }
        else if (node is android.view.ViewGroup) (0 until node.childCount).forEach { tint(node.getChildAt(it)) }
    }
    tint(view)
}

internal fun View.detachForTheme(): View {
    (parent as? android.view.ViewGroup)?.removeView(this)
    return this
}
