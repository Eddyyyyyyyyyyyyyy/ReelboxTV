package com.reelix.app.ui.theme.topbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reelix.app.ui.theme.layout.DetailActionPresentation
import com.reelix.app.ui.theme.layout.DetailThemeSlots
import com.reelix.app.ui.theme.layout.DetailThemeState
import com.reelix.app.ui.theme.layout.EpisodeRailPresentation
import com.reelix.app.ui.theme.layout.HistoryArrangement
import com.reelix.app.ui.theme.layout.HistoryCardStyle
import com.reelix.app.ui.theme.layout.HistoryPresentation
import com.reelix.app.ui.theme.layout.HomeThemeSlots
import com.reelix.app.ui.theme.layout.HomeThemeState
import com.reelix.app.ui.theme.layout.NativeThemeId
import com.reelix.app.ui.theme.layout.NativeThemeLayout
import com.reelix.app.ui.theme.layout.NativeThemePalette
import com.reelix.app.ui.theme.layout.PlayButtonStyle
import com.reelix.app.ui.theme.layout.ThemeLayoutBinding
import com.reelix.app.ui.theme.layout.standardDetailActions
import com.reelix.app.ui.theme.layout.standardEpisodePresentation
import com.reelix.app.ui.component.vDp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * VISUAL-V2: full 1920x1080 canvas, centered cinematic artwork and real shared content.
 * Home: viewport backdrop, information at (360,446)/1200px, 325x182px continue images.
 * Detail: title at y350, same-row actions y566, copy y672, resources from y901.
 * Long content grows naturally. Shared adapters retain all input and business ownership.
 */
class TopBarThemeLayout : NativeThemeLayout {
    override val id = NativeThemeId.TOPBAR
    override val palette = NativeThemePalette(
        background = 0xFF090909.toInt(),
        surface = 0xFF2A2A2D.toInt(),
        raisedSurface = 0xFF333337.toInt(),
        primaryText = 0xFFF5F5F7.toInt(),
        secondaryText = 0xFFBEC0C7.toInt(),
        accent = 0xFFF4F4F7.toInt(),
        onAccent = 0xFF121212.toInt(),
        focusRing = Color.WHITE,
        play = PlayButtonStyle(
            focusedFill = 0xFF333337.toInt(),
            focusedText = 0xFFFFFFFF.toInt(),
            focusRing = Color.WHITE,
            focusStrokePx = 2,
        ),
    )

    override fun installHome(slots: HomeThemeSlots): ThemeLayoutBinding<HomeThemeState> {
        requireParentless(listOfNotNull(
            slots.artwork, slots.foregroundPoster, slots.title, slots.metadata, slots.play,
            slots.detail, slots.historyHeader, slots.history, slots.emptyState, slots.overview,
        ))
        val background = FrameLayout(slots.root.context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            addView(slots.artwork, fillFrame())
            addView(AppleScrim(context, detail = false), fillFrame())
            visibility = View.GONE
        }
        val stage = HomeStage(slots, palette)
        val viewport = HomeViewport(stage)
        slots.root.setBackgroundColor(palette.background)
        slots.root.addView(background, fillFrame())
        slots.root.addView(viewport, fillFrame())
        return object : ThemeLayoutBinding<HomeThemeState> {
            private var disposed = false
            override fun update(state: HomeThemeState) {
                if (!disposed && background.parent === slots.root &&
                    viewport.parent === slots.root && stage.parent === viewport
                ) {
                    background.visibility = if (state.hasHistory) View.VISIBLE else View.GONE
                    if (stage.render(state)) viewport.scrollTo(0, 0)
                }
            }
            override fun dispose() {
                if (disposed) return
                disposed = true
                stage.configureHistory = null
            }
        }
    }

    override fun installDetail(slots: DetailThemeSlots): ThemeLayoutBinding<DetailThemeState> {
        requireParentless(listOfNotNull(
            slots.backdrop, slots.foregroundPoster, slots.scroll, slots.title, slots.metadata,
            slots.overview, slots.primaryActions, slots.routeHeader, slots.routes,
            slots.episodeHeader, slots.episodes, slots.selectedResource, slots.episodeOrder,
            slots.loading, slots.supplemental, slots.backToTop, slots.sourceInfo,
        ))
        require(slots.scroll.childCount == 0) { "TopBar requires an empty shared detail ScrollView" }
        val content = DetailContent(slots, palette)
        val stage = object : FrameLayout(slots.root.context) {
            init {
                isFocusable = false
                addView(slots.backdrop, fillFrame())
                addView(AppleScrim(context, detail = true), fillFrame())
                slots.scroll.isFillViewport = true
                slots.scroll.isVerticalScrollBarEnabled = false
                slots.scroll.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
                addView(slots.scroll, fillFrame())
            }
        }
        slots.root.addView(stage, fillFrame())
        return object : ThemeLayoutBinding<DetailThemeState> {
            private var disposed = false
            override fun update(state: DetailThemeState) {
                if (!disposed && stage.parent === slots.root) {
                    content.posterArea.visibility = if (!state.hasBackdrop && state.hasPoster) View.VISIBLE else View.GONE
                }
            }
            override fun dispose() {
                if (disposed) return
                disposed = true
                content.configureActions = null
                content.configureEpisodes = null
            }
        }
    }
}

/** Bounds are theme-owned; the supplied nodes never acquire new IDs or input listeners. */
private abstract class AppleCanvas(context: Context) : ViewGroup(context) {
    protected val boxes = linkedMapOf<View, TopBarRect>()
    protected var scale = 1f
    protected fun p(value: Number): Int = TopBarGeometry.px(value, scale)

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    protected fun attach(view: View) {
        addView(view, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    protected fun measureNatural(view: View?, width: Int, minimum: Int = 0, exactWidth: Boolean = true): Int {
        if (view == null || view.visibility == GONE) return 0
        view.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), if (exactWidth) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return maxOf(view.measuredHeight, minimum)
    }

    protected fun place(view: View?, x: Int, y: Int, width: Int, height: Int) {
        if (view == null || view.visibility == GONE) return
        val rect = TopBarRect(x, y, width.coerceAtLeast(0), height.coerceAtLeast(0))
        view.measure(
            MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY),
        )
        boxes[view] = rect
    }

    protected fun textHeight(view: TextView?, width: Int, font: Float, line: Float, color: Int, lines: Int = 2): Int {
        if (view == null || view.visibility == GONE) return 0
        styleText(view, font, line, scale, color, lines)
        measureNatural(view, width)
        return maxOf(view.measuredHeight, (line * scale * fontScale() * view.lineCount.coerceAtLeast(1)).toInt())
    }

    protected fun fontScale(): Float = resources.configuration.fontScale.coerceAtLeast(0.1f)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        boxes.forEach { (view, rect) ->
            if (view.visibility != GONE) view.layout(rect.x, rect.y, rect.x + rect.width, rect.bottom)
        }
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(WRAP, WRAP)
}

/** Native focus-driven scrolling is only needed when real text exceeds the reference canvas. */
private class HomeViewport(private val content: HomeStage) : ScrollView(content.context) {
    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

private class HomeStage(
    private val slots: HomeThemeSlots,
    private val palette: NativeThemePalette,
) : AppleCanvas(slots.root.context) {
    private val hero = FrameLayout(context).apply { isFocusable = false }
    private val info = HomeInfo(slots, palette)
    private val posterArea = FrameLayout(context).apply { isFocusable = false }
    private val emptyArea = FrameLayout(context).apply { isFocusable = false }
    private var state: HomeThemeState? = null
    private var lastPresentation: HistoryPresentation? = null
    private var lastFontScale = 0f
    var viewportHeight = 0
    var configureHistory: ((HistoryPresentation) -> Unit)? = slots.configureHistory

    init {
        setBackgroundColor(Color.TRANSPARENT)
        posterArea.addView(slots.foregroundPoster, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        hero.addView(posterArea, fillFrame())
        hero.addView(info, FrameLayout.LayoutParams(WRAP, WRAP))
        attach(hero)
        attach(slots.historyHeader)
        attach(slots.history)
        emptyArea.addView(slots.emptyState, fillFrame())
        attach(emptyArea)
        slots.historyHeader.setPadding(0, 0, 0, 0)
        slots.historyHeader.minimumHeight = 0
        emptyArea.visibility = GONE
    }

    fun render(next: HomeThemeState): Boolean {
        if (state == next) return false
        val resetViewport = state?.historyExpanded != next.historyExpanded || state?.hasHistory != next.hasHistory
        state = next
        hero.visibility = if (next.hasHistory) VISIBLE else GONE
        emptyArea.visibility = if (next.hasHistory) GONE else VISIBLE
        posterArea.visibility = if (!next.hasBackdrop && next.hasPoster) VISIBLE else GONE
        requestLayout()
        return resetViewport
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = viewportHeight
        scale = TopBarGeometry.scale(width)
        boxes.clear()
        info.measure(MeasureSpec.makeMeasureSpec(p(1200), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val infoBottom = p(446) + info.measuredHeight
        (info.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = p(1200)
            this.height = info.measuredHeight
            leftMargin = p(360)
            topMargin = p(446)
        }
        (slots.foregroundPoster.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = p(144)
            this.height = p(216)
            topMargin = maxOf(p(112), context.vDp(76) + p(16))
        }
        slots.historyTitle?.let { styleText(it, 27f, 36f, scale, palette.primaryText, 1, centered = false) }
        slots.historyLink?.let { styleText(it, 21f, 29f, scale, palette.secondaryText, 1, centered = false) }
        val headerHeight = measureNatural(slots.historyHeader, p(1730), p(36))
        val style = HistoryCardStyle(
            imageWidthPx = p(325).coerceAtLeast(1),
            imageHeightPx = p(182).coerceAtLeast(1),
            gapPx = p(25),
            titleTextPx = 23f * scale,
            subtitleTextPx = 18f * scale,
            titleGapPx = 0,
            subtitleGapPx = p(5),
            overlayTitle = true,
            cornerRadiusPx = p(12),
            focusStrokePx = p(4),
            focusOutsetPx = p(5),
            progressHeightPx = p(5),
        )
        val density = resources.displayMetrics.density
        val presentation = HistoryPresentation(
            HistoryArrangement.WIDE_RAIL, 5,
            (style.imageWidthPx / density).roundToInt().coerceAtLeast(1),
            (style.height(fontScale()) / density).roundToInt().coerceAtLeast(1),
            (style.gapPx / density).roundToInt(),
            style,
        )
        if (width > 0 && (lastPresentation != presentation || lastFontScale != fontScale())) {
            lastPresentation = presentation
            lastFontScale = fontScale()
            configureHistory?.invoke(presentation)
        }
        val geometry = TopBarGeometry.home(
            width, infoBottom, headerHeight, style.height(fontScale()),
            style.focusClearancePx, state?.historyExpanded == true,
        )
        val railOffset = if (state?.historyExpanded == true)
            (context.vDp(76) + p(32) - geometry.headerTop).coerceAtLeast(0) else 0
        place(hero, 0, 0, width, geometry.heroHeight)
        hero.translationY = if (state?.historyExpanded == true) -geometry.heroHeight.toFloat() else 0f
        if (state?.hasHistory != false) {
            place(slots.historyHeader, p(100), geometry.headerTop + railOffset, p(1730), headerHeight)
            val rail = geometry.history
            place(slots.history, rail.x, rail.y + railOffset, rail.width, rail.height)
        } else {
            place(slots.historyHeader, 0, height, p(1730), headerHeight)
            place(slots.history, 0, height, p(1730), style.height(fontScale()))
        }
        place(emptyArea, 0, 0, width, height)
        // No normal-state scroll range: first card focus must retain the Hero and image peek.
        val needsOverflow = state?.historyExpanded == true ||
            geometry.history.y + style.focusClearancePx >= height
        val contentBottom = when {
            state?.hasHistory == false -> height
            needsOverflow -> maxOf(geometry.history.bottom + railOffset, infoBottom)
            else -> maxOf(height, infoBottom + p(24))
        }
        setMeasuredDimension(width, maxOf(height, contentBottom))
    }
}

private class HomeInfo(
    private val slots: HomeThemeSlots,
    private val palette: NativeThemePalette,
) : AppleCanvas(slots.root.context) {
    init {
        listOfNotNull(slots.title, slots.metadata, slots.overview, slots.play, slots.detail).forEach(::attach)
        slots.title.setTypeface(slots.title.typeface, Typeface.BOLD)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        scale = width / 1200f
        boxes.clear()
        val titleHeight = textHeight(slots.title, width, 83f, 101.26f, palette.primaryText)
        place(slots.title, 0, 0, width, titleHeight)
        val metaHeight = textHeight(slots.metadata, width, 24f, 35f, palette.secondaryText)
        val metaTop = titleHeight + p(16)
        place(slots.metadata, 0, metaTop, width, metaHeight)
        val overviewWidth = p(900)
        val overviewHeight = textHeight(slots.overview, overviewWidth, 25f, 45f, palette.secondaryText, 2)
        val overviewTop = metaTop + metaHeight + p(20)
        place(slots.overview, (width - overviewWidth) / 2, overviewTop, overviewWidth, overviewHeight)
        val buttonTop = overviewTop + overviewHeight + p(28)
        val buttonHeight = maxOf(p(66), ceil(35f * scale * fontScale()).toInt() + p(12))
        styleButton(slots.play, scale, palette.onAccent, 25f)
        styleButton(slots.detail, scale, palette.primaryText, 25f)
        val playWidth = if (slots.play.visibility == GONE) 0 else maxOf(p(220), intrinsicWidth(slots.play))
        val detailWidth = if (slots.detail.visibility == GONE) 0 else maxOf(p(147), intrinsicWidth(slots.detail))
        val gap = if (playWidth > 0 && detailWidth > 0) p(16) else 0
        val total = playWidth + detailWidth + gap
        if (total <= width) {
            val left = (width - total) / 2
            place(slots.play, left, buttonTop, playWidth, buttonHeight)
            place(slots.detail, left + playWidth + gap, buttonTop, detailWidth, buttonHeight)
            setMeasuredDimension(width, buttonTop + buttonHeight)
        } else {
            place(slots.play, 0, buttonTop, width, buttonHeight)
            place(slots.detail, 0, buttonTop + buttonHeight + p(16), width, buttonHeight)
            setMeasuredDimension(width, buttonTop + buttonHeight * 2 + p(16))
        }
    }

    private fun intrinsicWidth(view: View): Int {
        if (view.visibility == GONE) return 0
        view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return view.measuredWidth
    }
}

private class DetailContent(
    private val slots: DetailThemeSlots,
    private val palette: NativeThemePalette,
) : AppleCanvas(slots.root.context) {
    val posterArea = FrameLayout(context).apply { isFocusable = false }
    var configureActions: ((DetailActionPresentation) -> Unit)? = slots.configureActions
    var configureEpisodes: ((EpisodeRailPresentation) -> Unit)? = slots.configureEpisodes
    private var lastActions: DetailActionPresentation? = null
    private var lastEpisodes: EpisodeRailPresentation? = null
    private val orderLayout = nativeButtonLayout(slots.episodeOrder, WRAP, 40)
    private val backToTopLayout = nativeButtonLayout(
        slots.backToTop, (180 * resources.displayMetrics.density).roundToInt(), 44,
    )

    init {
        posterArea.addView(slots.foregroundPoster, FrameLayout.LayoutParams(MATCH, MATCH))
        attach(posterArea)
        listOfNotNull(
            slots.title, slots.metadata, slots.primaryActions, slots.overview, slots.sourceInfo,
            slots.routeHeader, slots.routes, slots.episodeHeader, slots.episodes,
            slots.selectedResource, slots.episodeOrder, slots.loading, slots.supplemental, slots.backToTop,
        ).forEach(::attach)
        slots.episodeOrder.layoutParams = orderLayout
        slots.backToTop.layoutParams = backToTopLayout
        slots.title.setTypeface(slots.title.typeface, Typeface.BOLD)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        scale = TopBarGeometry.scale(width)
        boxes.clear()
        val innerWidth = p(1720)
        val readingWidth = p(1350)
        val actions = standardDetailActions(resources.displayMetrics.density)
        if (width > 0 && lastActions != actions) {
            lastActions = actions
            configureActions?.invoke(actions)
        }
        val episodeStyle = standardEpisodePresentation(resources.displayMetrics.density, fontScale())
        if (width > 0 && lastEpisodes != episodeStyle) {
            lastEpisodes = episodeStyle
            configureEpisodes?.invoke(episodeStyle)
        }
        val titleHeight = textHeight(slots.title, innerWidth, 87f, 104.4f, palette.primaryText)
        val metaHeight = textHeight(slots.metadata, innerWidth, 24f, 35f, palette.secondaryText)
        val actionHeight = measureNatural(slots.primaryActions, innerWidth, exactWidth = false)
        val actionWidth = slots.primaryActions.measuredWidth.coerceAtMost(innerWidth)
        val overviewHeight = textHeight(slots.overview, readingWidth, 27f, 47.25f, 0xFFE0E0E5.toInt(), 6)
        val sourceHeight = textHeight(slots.sourceInfo, readingWidth, 22f, 38.5f, palette.secondaryText)
        val geometry = TopBarGeometry.detail(width, titleHeight, metaHeight, actionHeight, overviewHeight, sourceHeight)
        place(posterArea, (width - p(144)) / 2, p(112), p(144), p(216))
        place(slots.title, p(100), geometry.titleTop, innerWidth, titleHeight)
        place(slots.metadata, p(100), geometry.metadataTop, innerWidth, metaHeight)
        place(slots.primaryActions, (width - actionWidth) / 2, geometry.actionsTop, actionWidth, actionHeight)
        place(slots.overview, (width - readingWidth) / 2, geometry.overviewTop, readingWidth, overviewHeight)
        place(slots.sourceInfo, (width - readingWidth) / 2, geometry.sourceTop, readingWidth, sourceHeight)
        var top = geometry.resourcesTop
        fun section(view: View, gap: Int = 0, minimum: Int = 0) {
            if (view.visibility == GONE) return
            top += gap
            val height = measureNatural(view, innerWidth, minimum)
            place(view, p(100), top, innerWidth, height)
            top += height
        }
        fun nativeButton(view: View, layout: ViewGroup.LayoutParams, gap: Int) {
            if (view.visibility == GONE) return
            top += gap
            val requestedWidth = layout.width
            val requestedHeight = layout.height
            view.measure(
                MeasureSpec.makeMeasureSpec(
                    if (requestedWidth >= 0) minOf(requestedWidth, innerWidth) else innerWidth,
                    if (requestedWidth >= 0) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST,
                ),
                MeasureSpec.makeMeasureSpec(
                    requestedHeight.coerceAtLeast(0),
                    if (requestedHeight >= 0) MeasureSpec.EXACTLY else MeasureSpec.UNSPECIFIED,
                ),
            )
            val buttonWidth = view.measuredWidth
            val buttonHeight = view.measuredHeight
            place(view, p(100), top, buttonWidth, buttonHeight)
            top += buttonHeight
        }
        styleHeader(slots.routeHeader, scale, palette)
        styleHeader(slots.episodeHeader, scale, palette)
        section(slots.routeHeader, minimum = p(38))
        section(slots.routes, gap = p(24), minimum = p(100))
        section(slots.episodeHeader, gap = if (slots.routes.visibility != GONE) p(28) else 0, minimum = p(38))
        if (slots.episodes.visibility != GONE) {
            val clearance = episodeStyle.focusClearancePx
            val configuredHeight = slots.episodes.layoutParams?.height?.coerceAtLeast(0) ?: 0
            val naturalHeight = measureNatural(slots.episodes, innerWidth + clearance * 2)
            val rail = TopBarGeometry.episodes(
                width, top + p(24), episodeStyle.heightPx, clearance,
                maxOf(naturalHeight, configuredHeight),
            )
            place(slots.episodes, rail.x, rail.y, rail.width, rail.height)
            top = rail.bottom
        }
        section(slots.selectedResource, gap = p(12))
        nativeButton(slots.episodeOrder, orderLayout, gap = p(12))
        section(slots.loading, gap = p(12))
        section(slots.supplemental, gap = p(36))
        nativeButton(slots.backToTop, backToTopLayout, gap = p(32))
        val minimumHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(width, maxOf(top + p(40), minimumHeight))
    }
}

private class AppleScrim(context: Context, private val detail: Boolean) : View(context) {
    private val paint = Paint()
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            if (detail) intArrayOf(0x33090909, 0x33090909, 0xFF090909.toInt())
            else intArrayOf(0x00090909, 0x30090909, 0xFF090909.toInt()),
            if (detail) floatArrayOf(0f, 0.17f, 1f) else floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private fun styleText(
    view: TextView, font: Float, line: Float, scale: Float, color: Int, lines: Int,
    centered: Boolean = true,
) {
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, font * scale / view.resources.displayMetrics.density)
    view.setTextColor(color)
    if (view.includeFontPadding) view.includeFontPadding = false
    view.letterSpacing = 0f
    if (view.maxLines != lines) view.maxLines = lines
    view.ellipsize = TextUtils.TruncateAt.END
    view.gravity = Gravity.CENTER_VERTICAL or if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
    view.textAlignment = if (centered) View.TEXT_ALIGNMENT_CENTER else View.TEXT_ALIGNMENT_GRAVITY
    view.setHorizontallyScrolling(false)
    view.setPadding(0, 0, 0, 0)
    val metrics = view.paint.fontMetrics
    val targetLine = line * scale * view.resources.configuration.fontScale
    view.setLineSpacing((targetLine - (metrics.descent - metrics.ascent)).coerceAtLeast(0f), 1f)
}

private fun styleButton(view: View, scale: Float, color: Int, font: Float) {
    view.minimumHeight = 0
    view.minimumWidth = 0
    if (view is TextView) {
        view.minHeight = 0
        view.minWidth = 0
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, font * scale / view.resources.displayMetrics.density)
        view.setTextColor(color)
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.compoundDrawablePadding = TopBarGeometry.px(12, scale)
        view.setPadding(TopBarGeometry.px(28, scale), 0, TopBarGeometry.px(28, scale), 0)
        val drawables = view.compoundDrawablesRelative
        var changed = false
        drawables.filterNotNull().forEach {
            val size = TopBarGeometry.px(29, scale)
            if (it.bounds.width() != size || it.bounds.height() != size) {
                it.setBounds(0, 0, size, size)
                changed = true
            }
        }
        if (changed) {
            view.setCompoundDrawablesRelative(drawables[0], drawables[1], drawables[2], drawables[3])
        }
    }
    (view.background as? GradientDrawable)?.cornerRadius = 12f * scale
}

private fun styleHeader(view: View, scale: Float, palette: NativeThemePalette) {
    var first = true
    fun visit(node: View) {
        if (node is TextView) {
            styleText(node, if (first) 30f else 22f, 38f, scale,
                if (first) palette.primaryText else palette.secondaryText, 2, centered = false)
            first = false
        } else if (node is ViewGroup) {
            for (index in 0 until node.childCount) visit(node.getChildAt(index))
        }
    }
    visit(view)
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private fun fillFrame() = FrameLayout.LayoutParams(MATCH, MATCH)

private fun nativeButtonLayout(view: View, fallbackWidthPx: Int, fallbackHeightDp: Int): ViewGroup.LayoutParams {
    val original = view.layoutParams
    return ViewGroup.LayoutParams(
        original?.width?.takeIf { it >= 0 || it == WRAP } ?: fallbackWidthPx,
        original?.height?.takeIf { it >= 0 || it == WRAP }
            ?: (fallbackHeightDp * view.resources.displayMetrics.density).roundToInt(),
    )
}

private fun requireParentless(views: List<View>) {
    require(views.distinct().size == views.size) { "TopBar slots must be distinct Views" }
    require(views.all { it.parent == null }) { "TopBar slots must be parentless" }
}
