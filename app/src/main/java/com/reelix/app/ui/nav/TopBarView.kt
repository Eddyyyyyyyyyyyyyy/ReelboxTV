package com.reelix.app.ui.nav

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.reelix.app.R
import com.reelix.app.data.settings.AppearancePreferences
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp
import com.reelix.app.ui.theme.layout.NativeThemeId
import com.reelix.app.ui.theme.layout.NativeThemeLayout
import com.reelix.app.ui.theme.layout.NativeThemePalette
import com.reelix.app.ui.theme.layout.ThemeActionGlyph
import kotlin.math.roundToInt

private const val FOCUS_SWITCH_DELAY_MS = 600L

class TopBarView(
    context: Context,
    private val onSelect: (TopDest) -> Unit,
    private val onEnterContent: () -> Boolean,
    private val onOpenSettings: (() -> Unit)? = null,
) : FrameLayout(context) {
    private var themeId = NativeThemeId.ORIGINAL
    private var palette: NativeThemePalette? = null
    private var current = TopDest.HOME
    private var order = TopDest.entries.toList()
    private var scale = 1f
    private val shade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xBF080809.toInt(), Color.TRANSPARENT))
    }
    private val brand = context.textView("Reelbox", 17f, ViewTheme.textPrimary, Gravity.CENTER_VERTICAL)
    private val navigation = LinearLayout(context)
    private val items = TopDest.entries.associateWith { destination -> NavItem(context, destination) }
    private val settingsLabel = context.textView("•••", 13f, ViewTheme.textSecondary, Gravity.CENTER)
    private val settingsIcon = ImageView(context)
    private val settings = settingButton()
    var lastSettingsKey: String = "settings"
        private set
    /** Focus-only tab switching; disabled while a detail page, player or settings is open. */
    var focusSwitchEnabled: Boolean = true

    init {
        isFocusable = false
        clipChildren = false
        addView(shade)
        addView(brand)
        addView(navigation)
        addView(settings)
        rebuildNavigation()
    }

    private fun settingButton() = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        contentDescription = context.getString(R.string.settings_title)
        addView(settingsLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(settingsIcon)
        setOnClickListener {
            lastSettingsKey = "settings"
            onOpenSettings?.invoke()
        }
        setOnFocusChangeListener { _, _ -> styleSettings() }
    }

    fun setTheme(theme: NativeThemeLayout?) {
        val next = theme?.id ?: NativeThemeId.ORIGINAL
        if (next == themeId && palette == theme?.palette) return
        themeId = next
        palette = theme?.palette
        rebuildNavigation()
        requestLayout()
    }

    fun setCurrent(destination: TopDest) {
        current = destination
        items.values.forEach { it.cancelFocusSwitch() }
        configureFocusLinks()
        items.values.forEach { it.style() }
    }

    fun setAppearance(preferences: AppearancePreferences) {
        val next = preferences.visible.map { TopDest.valueOf(it) }
        if (next == order) return
        val focused = items.entries.firstOrNull { it.value.hasFocus() }?.key
        order = next
        rebuildNavigation()
        if (focused != null) items[focused.takeIf { it in order } ?: TopDest.HOME]?.requestFocus()
    }

    private fun rebuildNavigation() {
        // ★ FocusTrace：这里的 removeAllViews() 会把所有 NavItem 从视图树摘下，
        //   焦点必然丢失。需要确认它是否在冷启动后几百毫秒内被调用（那就是焦点被抢的元凶）。
        val before = findFocus()
        android.util.Log.i(
            "FocusTrace",
            "TopBar.rebuildNavigation 开始 焦点=${before?.javaClass?.simpleName}(${before?.contentDescription})"
        )
        val restoreSettings = settings.hasFocus()
        items.values.forEach { it.cancelFocusSwitch() }
        (settings.parent as? ViewGroup)?.removeView(settings)
        navigation.orientation = if (themeId == NativeThemeId.SIDEBAR) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        navigation.gravity = Gravity.CENTER
        navigation.removeAllViews()
        order.forEach { navigation.addView(items.getValue(it)) }
        if (themeId == NativeThemeId.SIDEBAR) navigation.addView(settings) else addView(settings)
        settings.visibility = if (onOpenSettings == null) GONE else VISIBLE
        configureFocusLinks()
        styleSettings()
        if (restoreSettings) settings.requestFocus()
        android.util.Log.i(
            "FocusTrace",
            "TopBar.rebuildNavigation 结束 焦点=${findFocus()?.javaClass?.simpleName}(${findFocus()?.contentDescription})"
        )
    }

    private fun configureFocusLinks() {
        val sidebar = themeId == NativeThemeId.SIDEBAR
        order.forEachIndexed { index, destination ->
            val item = items.getValue(destination)
            item.nextFocusUpId = if (sidebar) items.getValue(order.getOrElse(index - 1) { destination }).id else View.NO_ID
            item.nextFocusDownId = if (sidebar) order.getOrNull(index + 1)?.let { items.getValue(it).id }
                ?: if (settings.visibility == VISIBLE) settings.id else item.id else View.NO_ID
        }
        settings.nextFocusUpId = if (sidebar) order.lastOrNull()?.let { items.getValue(it).id } ?: settings.id else View.NO_ID
        settings.nextFocusDownId = if (sidebar) settings.id else View.NO_ID
        settings.nextFocusLeftId = if (sidebar) settings.id else View.NO_ID
        // Let the system focus search reach the current page's content instead of trapping focus.
        settings.nextFocusRightId = View.NO_ID
    }

    private fun styleSettings() {
        val sidebar = themeId == NativeThemeId.SIDEBAR
        settingsLabel.visibility = if (sidebar) GONE else VISIBLE
        settingsIcon.visibility = if (sidebar) VISIBLE else GONE
        settingsLabel.setTextColor(palette?.primaryText ?: ViewTheme.textPrimary)
        if (sidebar) {
            settings.background = null
            if (settingsIcon.layoutParams.width != px(46) || settingsIcon.layoutParams.height != px(46))
                settingsIcon.layoutParams = LayoutParams(px(46), px(46), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            settingsIcon.setPadding(px(6), px(6), px(6), px(6))
            settingsIcon.setImageDrawable(ThemeActionGlyph(settingsLabel, "settings", px(34)))
            settingsIcon.background = roundedBackground(
                if (settings.isFocused) 0x20FFFFFF else Color.TRANSPARENT,
                px(8), if (settings.isFocused) maxOf(1, px(2)) else 0, palette?.focusRing ?: ViewTheme.focusRing)
            return
        }
        settings.background = roundedBackground(
            if (settings.isFocused) ViewTheme.surfaceHigh else Color.TRANSPARENT,
            (5 * scale).roundToInt(),
            if (settings.isFocused) maxOf(1, (2 * scale).roundToInt()) else 0,
            ViewTheme.focusRing)
    }

    private fun exact(view: View, width: Int, height: Int) {
        if (view.visibility != GONE) view.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY))
    }
    private fun px(reference: Int) = (reference * scale).roundToInt()
    private fun font(view: TextView, reference: Float) {
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, reference * scale / resources.displayMetrics.density)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val original = themeId != NativeThemeId.SIDEBAR
        scale = if (original) resources.displayMetrics.density / 2f else width / 1920f
        val headerHeight = if (original) context.vDp(76) else px(112)
        val height = if (themeId == NativeThemeId.SIDEBAR) MeasureSpec.getSize(heightMeasureSpec) else headerHeight
        setMeasuredDimension(width, height)
        exact(shade, width, headerHeight)
        font(brand, 34f)
        brand.setTypeface(brand.typeface, if (original) Typeface.NORMAL else Typeface.BOLD)
        // 侧栏主题用主题自己的强调色，不再沿用某个平台的品牌红。
        brand.setTextColor(if (themeId == NativeThemeId.SIDEBAR) palette?.accent ?: ViewTheme.accent else palette?.primaryText ?: ViewTheme.textPrimary)
        brand.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(headerHeight, MeasureSpec.AT_MOST))
        order.forEach { destination ->
            val item = items.getValue(destination)
            item.configure(original, themeId == NativeThemeId.SIDEBAR)
            item.layoutParams = LinearLayout.LayoutParams(if (themeId == NativeThemeId.SIDEBAR) px(80) else LinearLayout.LayoutParams.WRAP_CONTENT,
                if (themeId == NativeThemeId.SIDEBAR) px(81) else LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            }
        }
        if (themeId == NativeThemeId.SIDEBAR) settings.layoutParams = LinearLayout.LayoutParams(px(80), px(81))
        navigation.measure(MeasureSpec.makeMeasureSpec(if (themeId == NativeThemeId.SIDEBAR) px(113) else width,
            if (themeId == NativeThemeId.SIDEBAR) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        configureFocusLinks()
        if (themeId != NativeThemeId.SIDEBAR) exact(settings, context.vDp(36), context.vDp(36))
        styleSettings()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val original = themeId != NativeThemeId.SIDEBAR
        val headerHeight = if (original) context.vDp(76) else px(112)
        shade.layout(0, 0, width, headerHeight)
        val brandLeft = if (original) context.vDp(48) else px(if (themeId == NativeThemeId.TOPBAR) 90 else 96)
        val brandTop = (headerHeight - brand.measuredHeight) / 2
        brand.layout(brandLeft, brandTop, brandLeft + brand.measuredWidth, brandTop + brand.measuredHeight)
        val navigationLeft = when (themeId) {
            NativeThemeId.SIDEBAR -> 0
            else -> brand.right + context.vDp(44)
        }
        val navigationTop = if (themeId == NativeThemeId.SIDEBAR) px(160) else (headerHeight - navigation.measuredHeight) / 2
        navigation.layout(navigationLeft, navigationTop, navigationLeft + navigation.measuredWidth, navigationTop + navigation.measuredHeight)
        if (themeId == NativeThemeId.SIDEBAR) return
        val settingsRight = width - if (original) context.vDp(48) else px(95)
        val settingsTop = (headerHeight - settings.measuredHeight) / 2
        settings.layout(settingsRight - settings.measuredWidth, settingsTop, settingsRight, settingsTop + settings.measuredHeight)
    }

    fun focusHome(): Boolean {
        // ★ 2026-11-09 诊断日志：冷启动后焦点会跑到历史第一项，
        //   需要确认 focusHome() 到底成功没有。用 FocusTrace 过滤。
        val item = items[TopDest.HOME]
        val ok = item?.requestFocus() == true
        android.util.Log.i(
            "FocusTrace",
            "TopBar.focusHome ok=$ok itemExists=${item != null} " +
                "attached=$isAttachedToWindow itemAttached=${item?.isAttachedToWindow} " +
                "itemFocusable=${item?.isFocusable}"
        )
        return ok
    }
    fun focusCurrent(): Boolean = items[current]?.requestFocus() == true
    fun focusSettings(@Suppress("UNUSED_PARAMETER") key: String? = null): Boolean = settings.requestFocus()

    private inner class NavItem(context: Context, private val destination: TopDest) : LinearLayout(context) {
        private val label = context.textView(context.getString(destination.labelRes), 13f, ViewTheme.textPrimary, Gravity.CENTER)
        private val icon = ImageView(context)
        private val underline = View(context)
        private var original = true
        private var sidebar = false
        init {
            id = View.generateViewId()
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            contentDescription = context.getString(destination.labelRes)
            addView(icon)
            addView(label)
            label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            label.setSingleLine(true)
            addView(underline)
            setOnClickListener { onSelect(destination) }
            setOnFocusChangeListener { _, focused ->
                style()
                if (focused) scheduleFocusSwitch() else removeCallbacks(focusSwitch)
            }
            setOnKeyListener { _, code, event ->
                val entry = if (sidebar) code == KeyEvent.KEYCODE_DPAD_RIGHT else code == KeyEvent.KEYCODE_DPAD_DOWN
                if (entry) {
                    if (event.action == KeyEvent.ACTION_DOWN) onEnterContent() else true
                } else sidebar && code == KeyEvent.KEYCODE_DPAD_LEFT
            }
        }

        private val focusSwitch = Runnable {
            if (!isFocused || !isAttachedToWindow) return@Runnable
            if (!focusSwitchEnabled) return@Runnable
            if (current == destination) return@Runnable
            onSelect(destination)
        }

        private fun scheduleFocusSwitch() {
            removeCallbacks(focusSwitch)
            if (current == destination) return
            postDelayed(focusSwitch, FOCUS_SWITCH_DELAY_MS)
        }

        fun cancelFocusSwitch() = removeCallbacks(focusSwitch)
        fun configure(original: Boolean, sidebar: Boolean) {
            this.original = original
            this.sidebar = sidebar
            gravity = if (sidebar) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            label.visibility = if (sidebar) GONE else VISIBLE
            icon.visibility = if (sidebar) VISIBLE else GONE
            underline.visibility = if (original && current == destination) VISIBLE else GONE
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (if (original) 26f else 25f) * scale / resources.displayMetrics.density)
            setPadding(if (original) context.vDp(12) else 0, if (original) context.vDp(6) else 0,
                if (original) context.vDp(12) else 0, if (original) context.vDp(6) else 0)
            icon.layoutParams = LinearLayout.LayoutParams(px(if (sidebar) 46 else 34), px(if (sidebar) 46 else 34))
            underline.layoutParams = LinearLayout.LayoutParams(px(16), px(2)).apply { topMargin = px(5) }
            style()
        }
        fun style() {
            val selected = current == destination
            val accent = palette?.accent ?: ViewTheme.accent
            val color = if (selected) accent else if (isFocused) palette?.primaryText ?: ViewTheme.textPrimary
                else palette?.secondaryText ?: ViewTheme.textTertiary
            label.setTextColor(color)
            icon.setImageDrawable(NavigationGlyph(destination, color))
            underline.setBackgroundColor(accent)
            underline.visibility = if (original && (selected || isFocused)) VISIBLE else GONE
            if (sidebar) {
                setBackgroundColor(Color.TRANSPARENT)
                icon.background = roundedBackground(if (isFocused) 0x20FFFFFF else Color.TRANSPARENT,
                    px(8), if (isFocused) maxOf(1, px(2)) else 0, palette?.focusRing ?: ViewTheme.focusRing)
                icon.setPadding(px(6), px(6), px(6), px(6))
            } else {
                icon.background = null
                icon.setPadding(0, 0, 0, 0)
                background = roundedBackground(if (isFocused) 0x20FFFFFF else Color.TRANSPARENT,
                    px(5), if (isFocused) maxOf(1, px(2)) else 0, palette?.focusRing ?: ViewTheme.focusRing)
            }
        }
    }
}
