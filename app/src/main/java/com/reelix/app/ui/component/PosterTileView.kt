package com.reelix.app.ui.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.load
import coil.size.Precision
import coil.size.Scale
import com.reelix.app.data.image.LocalImageStore
import com.reelix.app.data.image.PosterFetcher
import com.reelix.app.R
import com.reelix.app.ui.theme.layout.NativeThemePalette
import com.reelix.app.ui.theme.layout.HistoryCardStyle

/**
 * View 海报墙的通用数据。
 *
 * 页面只负责把业务对象映射成这个模型；卡片不直接依赖 MediaItem / PlayProgressEntity。
 */
data class PosterTile(
    val key: String,
    val title: String,
    val posterUrl: String? = null,
    val posterRes: String? = null,
    val progress: Float = 0f,
    val rating: String? = null,
    val source: String? = null,
    val armed: Boolean = false,
    val allowTitleFallback: Boolean = true,
    val backdropUrl: String? = null,
    val subtitle: String? = null,
    /** Artwork picker shows explicit loading/error text; normal walls keep the quiet placeholder. */
    val showLoadingState: Boolean = false,
)

/**
 * 一张原生 View 海报卡。
 *
 * 刻意不做缩放动画：电视上每次焦点变化只改描边和标题颜色，避免整块区域重绘。
 */
internal class PosterTileView(context: Context) : LinearLayout(context) {

    private val radiusPx = context.vDp(ViewTheme.radiusPoster).toFloat()
    private val poster = PosterAspectFrame(context)
    private val image = TileArtworkImageView(context)
    private val letter = TextView(context)
    private val ring = View(context)
    private val progress = TileProgressLine(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val ratingBadge = TextView(context)
    private val sourceBadge = TextView(context)
    private val armedOverlay = FrameLayout(context)
    private val armedBadge = View(context)
    private val armedIcon = ImageView(context)
    private var armedBadgeSize = 0

    private val titleFocused = Color.WHITE
    private val titleNormal = 0x99FFFFFF.toInt()
    private var fixedCardWidth = 0
    private var themePalette: NativeThemePalette? = null
    private var presentation: HistoryCardStyle? = null
    private val outerFocus = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 共享焦点框启用时，卡片不再自己画外框。 */
    private var suppressOuterFocus = false

    init {
        orientation = VERTICAL
        isFocusable = true
        // 模拟器/鼠标点击后也要保持焦点，否则触摸模式会让后续 D-pad 失效。
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(Color.TRANSPARENT)

        poster.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(poster)

        image.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.background = GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(ViewTheme.posterPlaceholder)
        }
        image.clipToOutline = true
        image.outlineProvider = ViewOutlineProvider.BACKGROUND
        image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(image)

        letter.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        letter.setTextColor(ViewTheme.textTertiary)
        letter.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(letter)

        progress.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        progress.cornerRadius = radiusPx
        progress.visibility = INVISIBLE
        progress.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(progress)

        ring.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        ring.background = GradientDrawable().apply {
            cornerRadius = radiusPx
            setStroke(context.vDp(2), ViewTheme.focusRing)
            setColor(Color.TRANSPARENT)
        }
        ring.visibility = INVISIBLE
        ring.isFocusable = false
        ring.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(ring)

        ratingBadge.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, context.vDp(6), context.vDp(6), 0) }
        ratingBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        ratingBadge.setTextColor(Color.WHITE)
        ratingBadge.setPadding(context.vDp(6), context.vDp(2), context.vDp(6), context.vDp(2))
        ratingBadge.background = roundedBackground(0xB3000000.toInt(), context.vDp(4))
        ratingBadge.visibility = GONE
        ratingBadge.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(ratingBadge)

        sourceBadge.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, context.vDp(6), context.vDp(8)) }
        sourceBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        sourceBadge.setTextColor(Color.WHITE)
        sourceBadge.maxLines = 1
        sourceBadge.ellipsize = TextUtils.TruncateAt.END
        sourceBadge.setPadding(context.vDp(6), context.vDp(2), context.vDp(6), context.vDp(2))
        sourceBadge.background = roundedBackground(0xB3000000.toInt(), context.vDp(4))
        sourceBadge.visibility = GONE
        sourceBadge.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        poster.addView(sourceBadge)

        armedOverlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        // Dim the poster so the trash glyph stays readable on both bright and dark artwork.
        armedOverlay.setBackgroundColor(0x66000000)
        armedOverlay.visibility = GONE
        // Icon-only badge: the confirmation dialog explains what gets deleted.
        val deletionContent = FrameLayout(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            armedBadge.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            addView(armedBadge)
            armedIcon.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            addView(armedIcon)
        }
        armedOverlay.addView(
            deletionContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        poster.addView(armedOverlay)

        title.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(30))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        title.setTextColor(titleNormal)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.gravity = Gravity.CENTER_VERTICAL
        title.setPadding(0, context.vDp(6), 0, 0)
        title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(title)
        subtitle.visibility = GONE
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        subtitle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    fun bind(tile: PosterTile, imageWidth: Int, imageHeight: Int) {
        if (poster.imageHeight != imageHeight) {
            poster.imageHeight = imageHeight
            poster.requestLayout()
        }
        title.text = tile.title
        subtitle.text = tile.subtitle.orEmpty()
        subtitle.visibility = if (presentation != null && !tile.subtitle.isNullOrBlank()) VISIBLE else GONE
        contentDescription = tile.title
        progress.fraction = tile.progress.coerceIn(0f, 1f)
        progress.visibility = if (tile.progress > 0f) VISIBLE else INVISIBLE

        ratingBadge.text = tile.rating
        ratingBadge.visibility = if (tile.rating.isNullOrBlank()) GONE else VISIBLE
        sourceBadge.text = tile.source
        sourceBadge.visibility = if (tile.source.isNullOrBlank()) GONE else VISIBLE
        setArmed(tile.armed)

        val localId = LocalImageStore.id(context, tile.posterRes)
        val remote = tile.posterUrl?.takeIf { it.isNotBlank() }
            ?: if (tile.allowTitleFallback) PosterFetcher.cached(tile.title) else null

        when {
            localId != 0 -> {
                image.dispose()
                letter.visibility = INVISIBLE
                image.setImageResource(localId)
            }
            !remote.isNullOrBlank() -> {
                // Only the artwork picker needs explicit pending/error text. Normal poster
                // walls keep the old quiet placeholder to avoid flashing labels while scrolling.
                if (tile.showLoadingState) {
                    letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    letter.text = "加载中"
                    letter.visibility = VISIBLE
                } else {
                    letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    letter.text = fallbackLetter(tile.title)
                    letter.visibility = INVISIBLE
                }
                image.load(remote) {
                    size(imageWidth, imageHeight)
                    precision(Precision.INEXACT)
                    scale(Scale.FILL)
                    crossfade(false)
                    memoryCacheKey(remote)
                    diskCacheKey(remote)
                    listener(
                        onSuccess = { _, _ -> letter.visibility = INVISIBLE },
                        onError = { _, _ ->
                            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            letter.text = if (tile.showLoadingState) "图片失败" else fallbackLetter(tile.title)
                            letter.visibility = VISIBLE
                        },
                    )
                }
            }
            else -> {
                // ★ 必须取消上一张图的请求，否则复用到无图条目时旧图会晚到串台。
                image.dispose()
                image.setImageDrawable(null)
                letter.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    if (tile.showLoadingState) 14f else 28f)
                letter.text = when {
                    tile.showLoadingState && !tile.allowTitleFallback -> "无海报"
                    else -> fallbackLetter(tile.title)
                }
                letter.visibility = VISIBLE
            }
        }

        setFocused(isFocused)
    }

    fun setFocused(focused: Boolean) {
        ring.visibility = if (focused && presentation == null) VISIBLE else INVISIBLE
        title.setTextColor(if (presentation?.overlayTitle == true || focused) themePalette?.primaryText ?: titleFocused else themePalette?.primaryText ?: titleNormal)
        invalidate()
    }

    fun setArmed(armed: Boolean) {
        if (armed) layoutArmedBadge()
        armedOverlay.visibility = if (armed) VISIBLE else GONE
    }

    /**
     * Badge scales with the poster but the stroke stays 2dp, so it never looks stretched.
     * Sizes are computed in px and applied as layout, not scaleX/scaleY.
     */
    private fun layoutArmedBadge() {
        val posterWidth = poster.width.takeIf { it > 0 } ?: image.width.takeIf { it > 0 } ?: fixedCardWidth
        if (posterWidth <= 0) return
        val diameter = (posterWidth * 0.26f).toInt().coerceIn(context.vDp(36), context.vDp(64))
        if (diameter == armedBadgeSize) return
        armedBadgeSize = diameter
        armedBadge.layoutParams = FrameLayout.LayoutParams(diameter, diameter, Gravity.CENTER)
        armedBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99000000.toInt())
            setStroke(context.vDp(2), 0x66FFFFFF)
        }
        val icon = (diameter * 0.52f).toInt().coerceAtLeast(context.vDp(18))
        armedIcon.layoutParams = FrameLayout.LayoutParams(icon, icon, Gravity.CENTER)
        armedIcon.setImageResource(R.drawable.ic_history_delete)
        armedIcon.setColorFilter(Color.WHITE)
        armedIcon.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun setCardWidth(width: Int) {
        fixedCardWidth = width.coerceAtLeast(0) + (presentation?.focusClearancePx ?: 0) * 2
        image.topAligned = fixedCardWidth > 0
    }

    fun setTheme(palette: NativeThemePalette?) {
        if (themePalette == palette) return
        themePalette = palette
        palette ?: return
        (ring.background as? GradientDrawable)?.setStroke(context.vDp(2), palette.focusRing)
        progress.tint = palette.accent
        subtitle.setTextColor(palette.secondaryText)
    }

    fun setPresentation(style: HistoryCardStyle?) {
        if (presentation == style) return
        presentation = style
        val clearance = style?.focusClearancePx ?: 0
        setPadding(clearance, clearance, clearance, clearance)
        style ?: return
        clipChildren = false
        clipToPadding = false
        image.background = roundedBackground(themePalette?.surface ?: ViewTheme.surface, style.cornerRadiusPx)
        armedOverlay.background = roundedBackground(0xCC101011.toInt(), style.cornerRadiusPx)
        title.includeFontPadding = false
        subtitle.includeFontPadding = false
        val density = resources.displayMetrics.density
        val fontScale = resources.configuration.fontScale
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.titleTextPx / density)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.subtitleTextPx / density)
        val titleHeight = (style.titleTextPx * 1.4f * fontScale).toInt()
        val subtitleHeight = (style.subtitleTextPx * 1.4f * fontScale).toInt()
        (title.parent as? ViewGroup)?.removeView(title)
        if (style.overlayTitle) {
            val scale = style.titleTextPx / 23f
            title.gravity = Gravity.BOTTOM
            title.setPadding((20 * scale).toInt(), (48 * scale).toInt(), (20 * scale).toInt(), (20 * scale).toInt())
            title.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xED080809.toInt()))
            poster.addView(title, 1, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        } else {
            title.gravity = Gravity.CENTER_VERTICAL
            title.background = null
            title.setPadding(0, style.titleGapPx, 0, 0)
            addView(title, 1, LayoutParams(LayoutParams.MATCH_PARENT, titleHeight + style.titleGapPx))
        }
        subtitle.setPadding(0, style.subtitleGapPx, 0, 0)
        subtitle.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, subtitleHeight + style.subtitleGapPx)
        subtitle.setTextColor(themePalette?.secondaryText ?: ViewTheme.textSecondary)
        progress.thickness = style.progressHeightPx.toFloat()
        progress.cornerRadius = style.cornerRadiusPx.toFloat()
        requestLayout()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val style = presentation ?: return
        if (!isFocused || suppressOuterFocus) return
        outerFocus.color = themePalette?.focusRing ?: Color.WHITE
        outerFocus.strokeWidth = style.focusStrokePx.toFloat()
        val inset = style.focusOutsetPx.toFloat() + style.focusStrokePx / 2f
        canvas.drawRoundRect(poster.left - inset, poster.top - inset, poster.right + inset, poster.bottom + inset,
            style.cornerRadiusPx + inset, style.cornerRadiusPx + inset, outerFocus)
    }

    fun setSuppressOuterFocus(value: Boolean) {
        if (suppressOuterFocus == value) return
        suppressOuterFocus = value
        invalidate()
    }

    /**
     * 焦点框在卡片坐标系里的矩形；返回圆角半径（px）。
     * 没有样式时返回 null，调用方回退到卡片自己的外框。
     */
    fun focusFrameRect(out: android.graphics.Rect): Float? {
        val style = presentation ?: return null
        if (poster.width <= 0 || poster.height <= 0) return null
        val inset = style.focusOutsetPx + style.focusStrokePx / 2
        out.set(poster.left - inset, poster.top - inset, poster.right + inset, poster.bottom + inset)
        return (style.cornerRadiusPx + inset).toFloat()
    }

    fun focusRingColor(): Int = themePalette?.focusRing ?: Color.WHITE

    fun focusStrokeWidth(): Float = (presentation?.focusStrokePx ?: 2).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(if (fixedCardWidth > 0) MeasureSpec.makeMeasureSpec(fixedCardWidth, MeasureSpec.EXACTLY)
            else widthMeasureSpec, heightMeasureSpec)
    }

    private fun fallbackLetter(text: String): String =
        text.trim().firstOrNull { it.isLetterOrDigit() || it.code > 0x4E00 }?.toString().orEmpty()
}

private class TileArtworkImageView(context: Context) : ImageView(context) {
    var topAligned = false
        set(value) { field = value; scaleType = if (value) ScaleType.MATRIX else ScaleType.CENTER_CROP; crop() }
    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) { super.setImageDrawable(drawable); crop() }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight); crop()
    }
    private fun crop() {
        if (!topAligned) return
        val source = drawable ?: return
        if (width <= 0 || height <= 0 || source.intrinsicWidth <= 0 || source.intrinsicHeight <= 0) return
        val scale = maxOf(width.toFloat() / source.intrinsicWidth, height.toFloat() / source.intrinsicHeight)
        imageMatrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.intrinsicWidth * scale) / 2f, 0f)
        }
    }
}

/** 固定 2:3 的海报框，避免每次 bind 重新算 LayoutParams。 */
private class PosterAspectFrame(context: Context) : FrameLayout(context) {
    var imageHeight: Int = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = imageHeight.takeIf { it > 0 } ?: (width * 3) / 2
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}

/** 底部 2.5dp 进度线；没有进度时整条 INVISIBLE。 */
private class TileProgressLine(context: Context) : View(context) {
    var thickness = 2.5f * resources.displayMetrics.density
    var cornerRadius = 0f
    private val track = Paint().apply { color = 0x33FFFFFF }
    private val bar = Paint().apply { color = 0xFFFFD466.toInt() }
    private val clip = Path()
    private val bounds = RectF()
    var tint: Int
        get() = bar.color
        set(value) { bar.color = value; invalidate() }

    var fraction: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        if (fraction <= 0f) return
        val radius = cornerRadius.coerceIn(0f, minOf(width, height) / 2f)
        canvas.save()
        if (radius > 0f) {
            clip.reset()
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
        }
        val top = height - thickness
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), track)
        canvas.drawRect(0f, top, width * fraction.coerceIn(0f, 1f), height.toFloat(), bar)
        canvas.restore()
    }
}
