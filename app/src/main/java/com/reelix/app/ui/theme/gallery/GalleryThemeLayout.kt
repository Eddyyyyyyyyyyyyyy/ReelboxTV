package com.reelix.app.ui.theme.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reelix.app.ui.theme.layout.DetailActionPresentation
import com.reelix.app.ui.theme.layout.DetailThemeSlots
import com.reelix.app.ui.theme.layout.DetailThemeState
import com.reelix.app.ui.theme.layout.EpisodeRailPresentation
import com.reelix.app.ui.theme.layout.HistoryArrangement
import com.reelix.app.ui.theme.layout.HistoryPresentation
import com.reelix.app.ui.theme.layout.HomeThemeSlots
import com.reelix.app.ui.theme.layout.HomeThemeState
import com.reelix.app.ui.theme.layout.NativeThemeId
import com.reelix.app.ui.theme.layout.NativeThemeLayout
import com.reelix.app.ui.theme.layout.NativeThemePalette
import com.reelix.app.ui.theme.layout.PlayButtonStyle
import com.reelix.app.ui.theme.layout.ThemeLayoutBinding
import com.reelix.app.ui.theme.layout.standardDetailActions
import com.reelix.app.ui.component.vDp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Gallery visual-v2: full-screen 1920 x 1080 reference coordinates, scaled by
 * actual host width. Compact horizontal resume, collection filters, separate
 * history/recent rails; poster-left Detail and resources anchored at y=806.
 * Shared slots keep all input, data, stable keys and focus ownership.
 * Long text grows the following regions; it never scales the font down.
 */
class GalleryThemeLayout : NativeThemeLayout {
    override val id = NativeThemeId.GALLERY
    override val palette = NativeThemePalette(
        background = 0xFF18191B.toInt(),
        surface = 0xFF292A2D.toInt(),
        raisedSurface = 0xFF414146.toInt(),
        primaryText = 0xFFF5F5F7.toInt(),
        secondaryText = 0xFFC7C8CE.toInt(),
        accent = 0xFFFFCA6A.toInt(),
        onAccent = 0xFF121212.toInt(),
        focusRing = 0xFFFFCA6A.toInt(),
        play = PlayButtonStyle(
            focusedFill = 0xFFFFCA6A.toInt(),
            focusedText = 0xFF121212.toInt(),
            focusRing = 0xFFFFCA6A.toInt(),
        ),
    )

    override fun installHome(slots: HomeThemeSlots): ThemeLayoutBinding<HomeThemeState> {
        val surface = GalleryHome(slots, palette)
        slots.root.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))
        return object : ThemeLayoutBinding<HomeThemeState> {
            private var disposed = false
            override fun update(state: HomeThemeState) {
                if (!disposed) surface.render(state)
            }
            override fun dispose() {
                disposed = true
                surface.disposed = true
            }
        }
    }

    override fun installDetail(slots: DetailThemeSlots): ThemeLayoutBinding<DetailThemeState> {
        require(slots.scroll.childCount == 0) { "Gallery requires an empty dedicated detail scroll host" }
        val content = GalleryDetail(slots, palette)
        val artGroup = FrameLayout(slots.root.context).apply {
            isFocusable = false
            alpha = 0.25f
            addView(slots.backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(GalleryBackdropShade(context, palette.background),
                FrameLayout.LayoutParams(MATCH, MATCH))
        }
        slots.root.setBackgroundColor(palette.background)
        slots.root.addView(artGroup, FrameLayout.LayoutParams(MATCH, MATCH))
        slots.scroll.configureScroll()
        slots.scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        slots.root.addView(slots.scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        return object : ThemeLayoutBinding<DetailThemeState> {
            private var disposed = false
            override fun update(state: DetailThemeState) {
                if (!disposed) content.requestLayout()
            }
            override fun dispose() {
                disposed = true
                content.disposed = true
            }
        }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun ScrollView.configureScroll() {
    isFillViewport = true
    isFocusable = false
    isFocusableInTouchMode = false
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    clipToPadding = true
}

private fun host(context: Context, view: View?): FrameLayout = FrameLayout(context).apply {
    isFocusable = false
    if (view != null) addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    else visibility = View.GONE
}

private fun View.present(): Boolean = visibility != View.GONE
private fun View.heightWhenPresent(): Int = if (present()) measuredHeight else 0
private fun View.widthWhenPresent(): Int = if (present()) measuredWidth else 0
private fun View.fontScale(): Float = resources.configuration.fontScale

private fun TextView.type(
    g: GalleryGeometry, size: Float, color: Int, lines: Int = 1,
    bold: Boolean = false, lineHeight: Float? = null,
) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, g.text(size, fontScale()))
    setTextColor(color)
    setSingleLine(false)
    setHorizontallyScrolling(false)
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
    letterSpacing = 0f
    setPadding(0, 0, 0, 0)
    typeface = Typeface.create(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
    val metrics = paint.fontMetrics
    setLineSpacing(
        if (lineHeight != null) g.text(lineHeight, fontScale()) - (metrics.descent - metrics.ascent) else 0f,
        1f,
    )
}

private fun textNodes(view: View): List<TextView> = when (view) {
    is TextView -> listOf(view)
    is ViewGroup -> (0 until view.childCount).flatMap { textNodes(view.getChildAt(it)) }
    else -> emptyList()
}

private fun actionShape(fill: Int, radius: Int, stroke: Int = 0, ring: Int = 0) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke > 0) setStroke(stroke, ring)
    }

private fun styleResumeAction(view: View, g: GalleryGeometry, palette: NativeThemePalette) {
    view.background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), actionShape(
            palette.accent, g.px(10), max(1, g.px(4)), palette.primaryText))
        addState(intArrayOf(android.R.attr.state_pressed),
            actionShape(palette.primaryText, g.px(10)))
        addState(intArrayOf(), actionShape(palette.accent, g.px(10)))
    }
    textNodes(view).forEach {
        it.type(g, 22f, palette.onAccent, bold = true)
        it.gravity = Gravity.CENTER
    }
}

private fun View.measureExact(width: Int, height: Int) {
    if (!present()) return
    measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), View.MeasureSpec.EXACTLY))
}

private fun View.measureContent(width: Int) {
    if (!present()) return
    measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
}

private fun View.measureNatural(maxWidth: Int) {
    if (!present()) return
    measure(View.MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(0), View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
}

private fun View.place(x: Int, y: Int, width: Int = measuredWidth, height: Int = measuredHeight) {
    if (present()) layout(x, y, x + width, y + height)
}

private class GalleryHome(
    slots: HomeThemeSlots,
    palette: NativeThemePalette,
) : FrameLayout(slots.root.context) {
    private val collection = GalleryCollection(slots, palette)
    private val scroll = ScrollView(context).apply {
        configureScroll()
        addView(collection, LayoutParams(MATCH, WRAP))
    }
    var disposed: Boolean
        get() = collection.disposed
        set(value) { collection.disposed = value }

    init {
        isFocusable = false
        setBackgroundColor(palette.background)
        addView(scroll, LayoutParams(MATCH, MATCH))
    }

    fun render(state: HomeThemeState) = collection.render(state)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val geometry = GalleryGeometry(width, context.vDp(76))
        val height = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: geometry.px(1080)
        scroll.measureExact(width, geometry.contentHeight(height))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val geometry = GalleryGeometry(width, context.vDp(76))
        scroll.place(0, geometry.navigationHeight, width, geometry.contentHeight(height))
    }
}

private class GalleryCollection(
    private val slots: HomeThemeSlots,
    private val palette: NativeThemePalette,
) : ViewGroup(slots.root.context) {
    private val resume = GalleryResume(slots, palette)
    private val divider = View(context).apply { setBackgroundColor(0xFF414146.toInt()) }
    private val collection = slots.collectionHeader?.let { view ->
        val suppliedHeight = view.layoutParams?.height?.takeIf { it > 0 } ?: WRAP
        host(context, view).apply {
            getChildAt(0).layoutParams = FrameLayout.LayoutParams(MATCH, suppliedHeight)
        }
    } ?: host(context, null)
    private val historyHeader = host(context, slots.historyHeader).apply {
        getChildAt(0).layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
    }
    private val history = host(context, slots.history)
    private val recentHeader = host(context, slots.recentHeader).apply {
        getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
    }
    private val recent = host(context, slots.recentItems)
    private val empty = host(context, slots.collectionEmpty)
    private val globalEmpty = host(context, slots.emptyState)
    private val unused = FrameLayout(context).apply {
        visibility = GONE
        listOfNotNull(slots.artwork, slots.foregroundPoster, slots.metadata,
            slots.detail, slots.progress, slots.overview).forEach { addView(it) }
    }
    private var state = HomeThemeState(false, true, false, false, false)
    private var historyStyle: HistoryPresentation? = null
    private var recentStyle: HistoryPresentation? = null
    private var geometry = GalleryGeometry(1920)
    private var positions = geometry.home(53, 132, 39, 39, 390, true)
    private var cardHeight = 390
    private var clearance = 0
    var disposed = false

    init {
        isFocusable = false
        listOf(resume, divider, collection, historyHeader, history, recentHeader,
            recent, empty, globalEmpty, unused).forEach { addView(it) }
        render(state)
    }

    fun render(value: HomeThemeState) {
        state = value
        resume.visibility = if (value.hasHistory) VISIBLE else GONE
        divider.visibility = resume.visibility
        historyHeader.visibility = if (value.hasHistory) VISIBLE else GONE
        history.visibility = historyHeader.visibility
        val showRecent = value.hasLibrary && slots.recentItems?.present() == true
        recentHeader.visibility = if (showRecent && slots.recentHeader != null) VISIBLE else GONE
        recent.visibility = if (showRecent) VISIBLE else GONE
        // A populated library can still have zero results for its active filter.
        empty.visibility = if (slots.collectionEmpty?.present() == true) VISIBLE else GONE
        globalEmpty.visibility =
            if (!value.hasHistory && !value.hasLibrary && slots.collectionEmpty?.present() != true) VISIBLE else GONE
        // historyExpanded is a focus-region signal, not permission to remove
        // the resume/filter sections from the scrollable reference composition.
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val g = GalleryGeometry(width, context.vDp(76))
        geometry = g
        val inner = g.px(1720)
        val style = g.historyStyle(state.posterColumns)
        clearance = style.focusClearancePx
        cardHeight = style.height(fontScale())
        val density = resources.displayMetrics.density
        val presentation = HistoryPresentation(
            HistoryArrangement.POSTER_RAIL, 7,
            (style.imageWidthPx / density).roundToInt().coerceAtLeast(1),
            (cardHeight / density).roundToInt().coerceAtLeast(1),
            (style.gapPx / density).roundToInt(), style, posterSizeApplied = true,
        )
        if (!disposed && presentation != historyStyle) {
            historyStyle = presentation
            slots.configureHistory(presentation)
        }
        if (!disposed && presentation != recentStyle) {
            recentStyle = presentation
            slots.configureRecent?.invoke(presentation)
        }
        styleHeaders(g)
        resume.measureContent(inner)
        collection.measureContent(inner)
        historyHeader.measureContent(inner)
        recentHeader.measureContent(inner)
        positions = g.home(
            resume.heightWhenPresent(), collection.heightWhenPresent(),
            historyHeader.heightWhenPresent(), recentHeader.heightWhenPresent(),
            cardHeight, state.hasHistory,
        )
        val rail = g.rail(positions.historyImageTop, cardHeight, clearance)
        history.measureExact(rail.width, rail.height)
        recent.measureExact(rail.width, rail.height)
        empty.measureContent(inner)
        globalEmpty.measureContent(inner)
        divider.measureExact(inner, max(1, g.px(1)))
        val end = maxOf(
            if (recent.present()) positions.recentImageTop + cardHeight + g.px(80) else 0,
            if (history.present()) positions.historyImageTop + cardHeight + g.px(80) else 0,
            if (empty.present()) positions.recentTop + empty.measuredHeight + g.px(80) else 0,
            if (globalEmpty.present()) positions.historyTop + globalEmpty.measuredHeight + g.px(80) else 0,
            positions.collectionTop + collection.heightWhenPresent() + g.px(80),
        )
        setMeasuredDimension(width, max(g.contentHeight(g.px(1080)), g.contentY(end)))
    }

    private fun styleHeaders(g: GalleryGeometry) {
        slots.historyTitle?.type(g, 31f, palette.primaryText, bold = true)
        slots.historyLink?.type(g, 21f, palette.secondaryText)
        slots.recentTitle?.type(g, 31f, palette.primaryText, bold = true)
        if (slots.historyTitle == null) styleHeader(slots.historyHeader, g, palette)
        if (slots.recentTitle == null) slots.recentHeader?.let { styleHeader(it, g, palette) }
        // collectionHeader owns its original internal sizes and selected colors.
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val g = geometry
        resume.place(g.px(100), g.contentY(g.px(125)))
        divider.place(g.px(100), g.contentY(g.px(125) + resume.heightWhenPresent() + g.px(20)))
        collection.place(g.px(100), g.contentY(positions.collectionTop))
        historyHeader.place(g.px(100), g.contentY(positions.historyTop))
        val historyBox = g.rail(positions.historyImageTop, cardHeight, clearance)
        history.place(historyBox.x, g.contentY(historyBox.y))
        recentHeader.place(g.px(100), g.contentY(positions.recentTop))
        val recentBox = g.rail(positions.recentImageTop, cardHeight, clearance)
        recent.place(recentBox.x, g.contentY(recentBox.y))
        val emptyTop = if (state.hasHistory) positions.recentTop else positions.historyTop
        empty.place(g.px(100), g.contentY(emptyTop))
        globalEmpty.place(g.px(100), g.contentY(positions.historyTop))
    }
}

private class GalleryResume(
    private val slots: HomeThemeSlots,
    private val palette: NativeThemePalette,
) : ViewGroup(slots.root.context) {
    private var g = GalleryGeometry(1920)
    private var titleX = 0
    private var timeX = 0
    private var playWidth = 0

    init {
        isFocusable = false
        listOfNotNull(slots.resumeLabel, slots.title, slots.resumeTime, slots.play).forEach { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // This strip is 1720 reference pixels wide, not a separate screen.
        g = GalleryGeometry((width * 1920f / 1720f).roundToInt())
        slots.resumeLabel?.type(g, 25f, palette.primaryText, lines = 2)
        slots.title.type(g, 25f, palette.primaryText, lines = 2, bold = true)
        slots.resumeTime?.type(g, 21f, palette.secondaryText, lines = 2)
        styleResumeAction(slots.play, g, palette)
        playWidth = g.px(185)
        val playHeight = max(g.px(53), g.text(35f, fontScale()).roundToInt())
        slots.play.measureExact(playWidth, playHeight)
        val textBudget = (width - playWidth - g.px(30)).coerceAtLeast(0)
        slots.resumeLabel?.measureNatural(minOf(g.px(120), textBudget / 4))
        val labelWidth = slots.resumeLabel?.widthWhenPresent() ?: 0
        val labelGap = if (labelWidth > 0) g.px(30) else 0
        val remaining = (textBudget - labelWidth - labelGap).coerceAtLeast(0)
        slots.resumeTime?.measureNatural(remaining / 2)
        val timeWidth = slots.resumeTime?.widthWhenPresent() ?: 0
        val timeGap = if (timeWidth > 0) g.px(30) else 0
        slots.title.measureNatural((remaining - timeWidth - timeGap).coerceAtLeast(0))
        titleX = labelWidth + labelGap
        timeX = titleX + slots.title.widthWhenPresent() + timeGap
        setMeasuredDimension(width, maxOf(
            playHeight, slots.title.heightWhenPresent(),
            slots.resumeLabel?.heightWhenPresent() ?: 0,
            slots.resumeTime?.heightWhenPresent() ?: 0,
        ))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun centered(view: View?, x: Int) {
            view?.place(x, (height - view.heightWhenPresent()) / 2)
        }
        centered(slots.resumeLabel, 0)
        centered(slots.title, titleX)
        centered(slots.resumeTime, timeX)
        centered(slots.play, width - playWidth)
    }
}

private fun styleHeader(view: View, g: GalleryGeometry, palette: NativeThemePalette) {
    textNodes(view).forEachIndexed { index, text ->
        text.type(g, if (index == 0) 30f else 22f,
            if (index == 0) palette.primaryText else palette.secondaryText, bold = index == 0)
    }
}

private class GalleryDetail(
    private val slots: DetailThemeSlots,
    private val palette: NativeThemePalette,
) : ViewGroup(slots.root.context) {
    private val poster = FrameLayout(context).apply {
        isFocusable = false
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        addView(slots.foregroundPoster, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    private val resourceColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }
    private val resourceNodes = listOf(
        slots.loading, slots.routeHeader, slots.routes, slots.episodeHeader,
        slots.episodes, slots.selectedResource, slots.episodeOrder, slots.supplemental,
        slots.backToTop,
    )
    private val suppliedHeights = resourceNodes.associateWith {
        it.layoutParams?.height?.takeIf { height -> height > 0 }
    }
    private val utilityWidths = listOf(slots.episodeOrder, slots.backToTop).associateWith {
        it.layoutParams?.width ?: WRAP
    }
    private var g = GalleryGeometry(1920)
    private var positions = g.detail(73, 34, 98, 39, 66)
    private var presentation: DetailActionPresentation? = null
    private var actionFontScale = Float.NaN
    private var episodePresentation: EpisodeRailPresentation? = null
    private var episodeFontScale = Float.NaN
    private var episodeClearance = 0
    var disposed = false

    init {
        isFocusable = false
        slots.foregroundPoster.scaleType = ImageView.ScaleType.CENTER_CROP
        listOfNotNull(poster, slots.title, slots.metadata, slots.overview,
            slots.sourceInfo, slots.primaryActions).forEach { addView(it) }
        resourceNodes.forEach {
            val height = if (it === slots.episodes) WRAP else suppliedHeights[it] ?: WRAP
            resourceColumn.addView(it, LinearLayout.LayoutParams(utilityWidths[it] ?: MATCH, height))
        }
        addView(resourceColumn)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        g = GalleryGeometry(width)
        val p = g.poster()
        poster.background = actionShape(palette.surface, g.px(10))
        poster.measureExact(p.width, p.height)
        slots.title.type(g, 61f, palette.primaryText, lines = 2, bold = true, lineHeight = 73.2f)
        slots.metadata.type(g, 24f, palette.secondaryText, lines = 2, lineHeight = 34f)
        slots.overview.type(g, 28f, 0xFFE0E0E5.toInt(), lines = 6, lineHeight = 49f)
        slots.sourceInfo?.type(g, 22f, palette.secondaryText, lines = 3, lineHeight = 38.5f)
        measureText(slots.title, g.px(1300), 73.2f)
        measureText(slots.metadata, g.px(1300), 34f)
        measureText(slots.overview, g.px(1180), 49f)
        slots.sourceInfo?.let { measureText(it, g.px(1180), 38.5f) }
        val actions = standardDetailActions(resources.displayMetrics.density)
        if (!disposed && (presentation != actions || actionFontScale != fontScale())) {
            presentation = actions
            actionFontScale = fontScale()
            slots.configureActions?.invoke(actions)
        }
        slots.primaryActions.measureContent(g.px(1300))
        positions = g.detail(
            slots.title.heightWhenPresent(), slots.metadata.heightWhenPresent(),
            slots.overview.heightWhenPresent(), slots.sourceInfo?.heightWhenPresent() ?: 0,
            slots.primaryActions.heightWhenPresent(),
        )
        configureResources()
        resourceColumn.setPadding(episodeClearance, 0, episodeClearance, 0)
        resourceColumn.measureContent(g.px(1720) + 2 * episodeClearance)
        setMeasuredDimension(width,
            max(g.px(1080), positions.resourceTop + resourceColumn.measuredHeight + g.px(80)))
    }

    private fun measureText(view: TextView, width: Int, lineHeight: Float) {
        if (!view.present()) return
        view.measureContent(width)
        val lines = view.lineCount.coerceIn(1, view.maxLines)
        val height = max(view.measuredHeight, (g.text(lineHeight, fontScale()) * lines).roundToInt())
        view.measureExact(width, height)
    }

    private fun configureResources() {
        val episodes = g.episodeStyle(resources.displayMetrics.density, fontScale())
        episodeClearance = episodes.focusClearancePx
        if (!disposed && (episodePresentation != episodes || episodeFontScale != fontScale())) {
            episodePresentation = episodes
            episodeFontScale = fontScale()
            slots.configureEpisodes?.invoke(episodes)
        }
        styleHeader(slots.routeHeader, g, palette)
        styleHeader(slots.episodeHeader, g, palette)
        slots.routeHeader.minimumHeight = g.text(38f, fontScale()).roundToInt()
        slots.episodeHeader.minimumHeight = g.text(38f, fontScale()).roundToInt()
        (slots.selectedResource as? TextView)?.type(g, 22f, palette.secondaryText, lines = 3)
        (slots.loading as? TextView)?.type(g, 22f, palette.secondaryText, lines = 3)
        var first = true
        resourceNodes.forEach { view ->
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.topMargin = if (first) 0 else when (view) {
                slots.routes, slots.episodes -> g.px(24)
                slots.episodeHeader -> g.px(30)
                slots.selectedResource -> g.px(12)
                else -> g.px(20)
            }
            if (view === slots.routes) {
                view.minimumHeight = max(view.minimumHeight, g.text(100f, fontScale()).roundToInt())
            }
            if (view === slots.episodes) {
                // The callback owns the actual viewport height and font growth.
                // Offset its padded root so the visible card stays at x100.
                params.leftMargin = -episodeClearance
                params.rightMargin = -episodeClearance
                params.topMargin -= episodeClearance
            }
            if (view.present()) first = false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val p = g.poster()
        poster.place(p.x, p.y)
        slots.title.place(g.px(520), g.px(199))
        slots.metadata.place(g.px(520), positions.metadataTop)
        slots.overview.place(g.px(520), positions.overviewTop)
        slots.sourceInfo?.place(g.px(520), positions.sourceTop)
        slots.primaryActions.place(g.px(520), positions.actionsTop)
        resourceColumn.place(g.px(100) - episodeClearance, positions.resourceTop)
    }
}

private class GalleryBackdropShade(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var horizontal: Shader? = null
    private var vertical: Shader? = null

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        horizontal = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(color, (color and 0x00FFFFFF) or (212 shl 24), Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 0.79f), Shader.TileMode.CLAMP)
        vertical = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, color),
            floatArrayOf(0f, 0.16f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = vertical
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = horizontal
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
