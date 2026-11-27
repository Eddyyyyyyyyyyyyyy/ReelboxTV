package com.reelix.app.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.reelix.app.R
import com.reelix.app.data.model.Episode
import com.reelix.app.ui.theme.layout.EpisodeRailPresentation
import com.reelix.app.ui.theme.layout.NativeThemePalette
import com.reelix.app.ui.theme.layout.standardEpisodePresentation

/** Static information card. Focus and recorded progress have independent visual channels. */
internal class EpisodeCardView(context: Context) : LinearLayout(context) {
    private val frame = LinearLayout(context)
    private val number = context.textView("", 19f, ViewTheme.textPrimary)
    private val quality = context.textView("", 11f, ViewTheme.textSecondary, Gravity.END)
    private val watched = context.textView("", 11f, ViewTheme.textSecondary)
    private val track = FrameLayout(context)
    private val bar = View(context)
    private val caption = context.textView("", 12f, ViewTheme.textSecondary)
    private val armedScrim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAD000000.toInt()
    }
    private val armedPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var armed = false
    /** Owns OK so a held press can become a long-press instead of an immediate click. */
    var onConfirmKey: ((KeyEvent) -> Boolean)? = null
    private var fraction = 0f
    private var presentation: EpisodeRailPresentation? = null
    private var palette: NativeThemePalette? = null
    private val focusPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipChildren = true
        frame.orientation = LinearLayout.VERTICAL
        addView(frame)
        number.setTypeface(null, Typeface.BOLD)
        number.maxLines = 2
        number.ellipsize = TextUtils.TruncateAt.END
        number.includeFontPadding = true
        number.gravity = Gravity.TOP or Gravity.START
        quality.includeFontPadding = true
        quality.gravity = Gravity.TOP or Gravity.END
        quality.maxLines = 2
        quality.ellipsize = TextUtils.TruncateAt.END
        watched.includeFontPadding = true
        watched.setSingleLine(true)
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            isBaselineAligned = false
        }
        top.addView(number, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(quality, LinearLayout.LayoutParams(context.vDp(54), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.vDp(6)
        })
        frame.addView(top, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        frame.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        frame.addView(watched, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        track.setBackgroundColor(0x33FFFFFF)
        frame.addView(track, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(3)).apply {
            topMargin = context.vDp(2)
        })
        bar.setBackgroundColor(ViewTheme.accent)
        track.addView(bar, FrameLayout.LayoutParams(0, LayoutParams.MATCH_PARENT))
        track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateProgressWidth() }
        caption.maxLines = 2
        caption.includeFontPadding = true
        caption.setHorizontallyScrolling(false)
        caption.ellipsize = TextUtils.TruncateAt.END
        addView(caption)
        setOnFocusChangeListener { _, focused -> updateStyle(focused) }
        configure(null, null)
    }
    private fun updateStyle(focused: Boolean) {
        frame.background = roundedBackground(palette?.surface ?: ViewTheme.surfaceElevated,
            presentation?.cornerRadiusPx ?: context.vDp(8),
            0, palette?.focusRing ?: ViewTheme.focusRing)
        caption.setTextColor(if (focused) palette?.primaryText ?: ViewTheme.textPrimary
            else palette?.secondaryText ?: ViewTheme.textSecondary)
        invalidate()
    }
    fun configure(style: EpisodeRailPresentation?, palette: NativeThemePalette?) {
        val resolved = style ?: standardEpisodePresentation(
            resources.displayMetrics.density, resources.configuration.fontScale)
        if (presentation == resolved && this.palette == palette) return
        presentation = resolved
        this.palette = palette
        val clearance = resolved.focusClearancePx
        setPadding(clearance, clearance, clearance, clearance)
        frame.layoutParams = LayoutParams(resolved.widthPx, resolved.heightPx)
        frame.setPadding(resolved.horizontalPaddingPx, resolved.verticalPaddingPx,
            resolved.horizontalPaddingPx, resolved.verticalPaddingPx)
        number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, resolved.titleTextPx / resources.displayMetrics.density)
        number.setTextColor(palette?.primaryText ?: ViewTheme.textPrimary)
        quality.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, resolved.qualityTextPx / resources.displayMetrics.density)
        quality.setTextColor(palette?.secondaryText ?: ViewTheme.textSecondary)
        quality.layoutParams = (quality.layoutParams as LayoutParams).apply { width = resolved.qualityWidthPx }
        watched.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, resolved.subtitleTextPx / resources.displayMetrics.density)
        watched.setTextColor(palette?.secondaryText ?: ViewTheme.textSecondary)
        caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, resolved.captionTextPx / resources.displayMetrics.density)
        caption.layoutParams = LayoutParams(resolved.widthPx, resolved.captionHeightPx).apply {
            topMargin = resolved.captionGapPx
        }
        track.layoutParams = (track.layoutParams as LayoutParams).apply { height = resolved.progressHeightPx }
        track.setBackgroundColor(palette?.raisedSurface ?: 0x33FFFFFF)
        bar.setBackgroundColor(palette?.accent ?: ViewTheme.accent)
        updateStyle(isFocused)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val style = presentation
        super.onMeasure(if (style != null) MeasureSpec.makeMeasureSpec(style.cellWidthPx, MeasureSpec.EXACTLY) else widthMeasureSpec,
            if (style != null) MeasureSpec.makeMeasureSpec(style.cellHeightPx, MeasureSpec.EXACTLY) else heightMeasureSpec)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            onConfirmKey?.let { if (it(event)) return true }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val style = presentation ?: return
        if (armed) {
            val radius = minOf(width, height) * 0.18f
            canvas.drawCircle(width / 2f, height / 2f, radius * 1.35f, armedScrim)
            val cx = width / 2f
            val cy = height / 2f
            val w = radius * 0.62f
            val h = radius * 0.7f
            armedPaint.strokeWidth = context.vDp(2).toFloat()
            val path = android.graphics.Path().apply {
                // Lid
                moveTo(cx - w, cy - h * 0.55f)
                lineTo(cx + w, cy - h * 0.55f)
                // Handle
                moveTo(cx - w * 0.4f, cy - h * 0.55f)
                lineTo(cx - w * 0.32f, cy - h * 0.85f)
                lineTo(cx + w * 0.32f, cy - h * 0.85f)
                lineTo(cx + w * 0.4f, cy - h * 0.55f)
                // Body
                moveTo(cx - w * 0.78f, cy - h * 0.3f)
                lineTo(cx - w * 0.62f, cy + h * 0.9f)
                lineTo(cx + w * 0.62f, cy + h * 0.9f)
                lineTo(cx + w * 0.78f, cy - h * 0.3f)
                // Ribs
                moveTo(cx - w * 0.2f, cy)
                lineTo(cx - w * 0.2f, cy + h * 0.55f)
                moveTo(cx + w * 0.2f, cy)
                lineTo(cx + w * 0.2f, cy + h * 0.55f)
            }
            canvas.drawPath(path, armedPaint)
        }
        if (!isFocused) return
        val outside = style.focusOutsetPx + style.focusStrokePx / 2f
        focusPaint.color = palette?.focusRing ?: Color.WHITE
        focusPaint.strokeWidth = style.focusStrokePx.toFloat()
        canvas.drawRoundRect(frame.left - outside, frame.top - outside, frame.right + outside, frame.bottom + outside,
            style.cornerRadiusPx + outside, style.cornerRadiusPx + outside, focusPaint)
    }
    private fun updateProgressWidth() {
        val width = (track.width * fraction).toInt()
        if (bar.layoutParams.width != width) bar.layoutParams =
            (bar.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
    }
    fun bind(episode: Episode, progress: Float) {
        val info = episodeCardPresentation(episode)
        number.text = info.title ?: if (info.number != null) context.getString(R.string.episode_card_number, info.number)
            else context.getString(R.string.episode_card_unknown)
        quality.text = listOfNotNull(info.resolution, info.variants.firstOrNull()).joinToString("\n")
        quality.visibility = if (quality.text.isNullOrBlank()) GONE else VISIBLE
        quality.maxLines = 2
        quality.ellipsize = TextUtils.TruncateAt.END
        caption.text = info.resourceName.ifBlank { context.getString(R.string.episode_card_unknown) }
        contentDescription = listOf(number.text, quality.text, caption.text).filter { it.isNotBlank() }.joinToString(" · ")
        fraction = progress.coerceIn(0f, 1f)
        watched.text = context.getString(R.string.episode_card_progress, (fraction * 100).toInt())
        watched.visibility = if (fraction > 0f) VISIBLE else INVISIBLE
        track.visibility = if (fraction > 0f) VISIBLE else INVISIBLE
        updateProgressWidth()
        updateStyle(isFocused)
    }

    fun setArmed(armed: Boolean) {
        if (this.armed == armed) return
        this.armed = armed
        invalidate()
    }
}
