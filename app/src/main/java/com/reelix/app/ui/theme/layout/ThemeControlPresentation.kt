package com.reelix.app.ui.theme.layout

data class ThemeControlColors(val background: Int, val text: Int, val border: Int)

fun showTopNavigation(detailOpen: Boolean, theme: NativeThemeId?): Boolean =
    !detailOpen || theme == NativeThemeId.SIDEBAR

fun themeRouteColors(palette: NativeThemePalette, selected: Boolean, focused: Boolean): ThemeControlColors =
    when {
        // 选中+聚焦时边框必须用焦点环色：用 onAccent（深色）压在深色背景的选中态上，
        // 用户完全看不出焦点落在哪一条线路上。
        selected && focused -> ThemeControlColors(palette.accent, palette.onAccent, palette.focusRing)
        selected -> ThemeControlColors(palette.accent, palette.onAccent, palette.focusRing)
        focused -> ThemeControlColors(palette.raisedSurface, palette.primaryText, palette.focusRing)
        else -> ThemeControlColors(palette.surface, palette.primaryText, palette.focusRing)
    }
