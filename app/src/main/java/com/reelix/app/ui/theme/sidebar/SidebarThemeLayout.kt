package com.reelix.app.ui.theme.sidebar

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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

class SidebarThemeLayout : NativeThemeLayout {
    override val id = NativeThemeId.SIDEBAR
    override val palette = NativeThemePalette(
        background = 0xFF101010.toInt(),
        surface = 0xFF292929.toInt(),
        raisedSurface = 0xFF393939.toInt(),
        primaryText = 0xFFF5F5F7.toInt(),
        secondaryText = 0xFFBEC0C7.toInt(),
        accent = 0xFFED2639.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        focusRing = 0xFFED2639.toInt(),
        play = PlayButtonStyle(
            focusedFill = 0xFFED2639.toInt(),
            focusedText = 0xFFFFFFFF.toInt(),
            focusRing = 0xFFED2639.toInt(),
        ),
    )

    override fun installHome(slots: HomeThemeSlots): ThemeLayoutBinding<HomeThemeState> {
        val card = sidebarHistoryCardStyle()
        slots.configureHistory(
            HistoryPresentation(HistoryArrangement.WIDE_RAIL, 1, 200, 125, 12, card),
        )
        val background = FrameLayout(slots.root.context).apply {
            isFocusable = false
            isClickable = false
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(slots.artwork, FrameLayout.LayoutParams(-1, -1))
            addView(SidebarScrim(context), FrameLayout.LayoutParams(-1, -1))
        }
        val surface = SidebarHomeSurface(slots, palette, card)
        val scroll = ScrollView(slots.root.context).apply {
            isFocusable = false
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setFadingEdgeLength(0)
            addView(surface, ViewGroup.LayoutParams(-1, -2))
        }
        slots.root.setBackgroundColor(palette.background)
        slots.root.addView(background, FrameLayout.LayoutParams(-1, -1))
        slots.root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        slots.root.addView(slots.emptyState, FrameLayout.LayoutParams(-1, -1))
        return object : ThemeLayoutBinding<HomeThemeState> {
            private var disposed = false
            override fun update(state: HomeThemeState) {
                if (disposed) return
                scroll.visibility = if (state.hasHistory) View.VISIBLE else View.GONE
                background.visibility =
                    if (state.hasHistory && (state.hasBackdrop || state.hasPoster)) View.VISIBLE else View.GONE
                slots.foregroundPoster.visibility =
                    if (state.hasHistory && state.hasPoster && !state.hasBackdrop) View.VISIBLE else View.GONE
                if (surface.setHistoryExpanded(state.historyExpanded)) scroll.scrollTo(0, 0)
                surface.requestLayout()
            }
            override fun dispose() { disposed = true }
        }
    }

    override fun installDetail(slots: DetailThemeSlots): ThemeLayoutBinding<DetailThemeState> {
        slots.configureActions?.invoke(standardDetailActions(slots.root.resources.displayMetrics.density))
        slots.root.setBackgroundColor(palette.background)
        slots.root.addView(slots.backdrop, FrameLayout.LayoutParams(-1, -1))
        slots.root.addView(SidebarScrim(slots.root.context), FrameLayout.LayoutParams(-1, -1))
        val episodes = sidebarEpisodePresentation(slots.root.resources.displayMetrics.density,
            slots.root.resources.configuration.fontScale)
        val surface = SidebarDetailSurface(slots, palette, episodes)
        slots.scroll.isVerticalScrollBarEnabled = false
        slots.scroll.addView(surface, ViewGroup.LayoutParams(-1, -2))
        slots.root.addView(slots.scroll, FrameLayout.LayoutParams(-1, -1))
        // Invoke after mounting, so the callback's actual episode height is not overwritten.
        slots.configureEpisodes?.invoke(episodes)
        return object : ThemeLayoutBinding<DetailThemeState> {
            private var disposed = false
            override fun update(state: DetailThemeState) {
                if (disposed) return
                slots.foregroundPoster.visibility =
                    if (state.hasPoster && !state.hasBackdrop) View.VISIBLE else View.GONE
                // Loading, route state and episode-region blur remain adapter-owned.
                surface.requestLayout()
            }
            override fun dispose() { disposed = true }
        }
    }
}

private class SidebarHomeSurface(
    private val slots: HomeThemeSlots,
    private val palette: NativeThemePalette,
    private val card: HistoryCardStyle,
) : SidebarSurface(slots.root.context) {
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val auxiliary = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private var actionStyleWidth = -1
    private var actionStyleFontScale = -1f
    private var actionStyleDensity = -1f
    private var historyExpanded = false
    private val heroNodes = listOfNotNull(
        slots.foregroundPoster, slots.title, slots.metadata,
        slots.overview, actions, auxiliary,
    )

    fun setHistoryExpanded(expanded: Boolean): Boolean {
        if (historyExpanded == expanded) return false
        historyExpanded = expanded
        heroNodes.forEach {
            it.translationY = if (expanded) -maxOf(measuredHeight, slots.root.height).toFloat() else 0f
        }
        requestLayout()
        return true
    }

    init {
        addView(slots.foregroundPoster)
        addView(slots.title)
        addView(slots.metadata)
        slots.overview?.let { addView(it) }
        actions.addView(slots.play, LinearLayout.LayoutParams(-2, -2))
        actions.addView(slots.detail, LinearLayout.LayoutParams(-2, -2))
        addView(actions)
        val resume = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        slots.resumeLabel?.let { resume.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        slots.resumeTime?.let { resume.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        auxiliary.addView(resume, LinearLayout.LayoutParams(-1, -2))
        auxiliary.addView(slots.progress, LinearLayout.LayoutParams(-1, -2))
        addView(auxiliary)
        addView(slots.historyHeader)
        addView(slots.history)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = SidebarGeometry(MeasureSpec.getSize(widthMeasureSpec))
        beginMeasure()
        val titleHeight = text(slots.title, g.homeTitleWidth, g, 85f, 103.7f, palette.primaryText, weight = 750)
        val metadataHeight = text(slots.metadata, g.homeWidth, g, 24f, 34f, palette.secondaryText)
        val overviewHeight = text(slots.overview, g.homeOverviewWidth, g, 25f, 45f, palette.secondaryText, maxLines = 2)
        val fontScale = resources.configuration.fontScale
        val density = resources.displayMetrics.density
        if (actionStyleWidth != g.width || actionStyleFontScale != fontScale || actionStyleDensity != density) {
            actionStyleWidth = g.width
            actionStyleFontScale = fontScale
            actionStyleDensity = density
            styleHomeAction(slots.play, g, palette, primary = true)
            styleHomeAction(slots.detail, g, palette, primary = false)
        }
        (slots.play.layoutParams as LinearLayout.LayoutParams).rightMargin =
            if (slots.detail.visibility != View.GONE) g.px(16) else 0
        val actionsHeight = node(actions, g.homeWidth)
        slots.resumeLabel?.let {
            styleSidebarText(it, g, 22f, 31f, palette.secondaryText)
            (it.layoutParams as LinearLayout.LayoutParams).rightMargin = g.px(12)
        }
        slots.resumeTime?.let { styleSidebarText(it, g, 22f, 31f, palette.secondaryText) }
        val auxiliaryHeight = node(auxiliary, g.homeWidth)
        slots.historyTitle?.let { styleSidebarText(it, g, 31f, 39f, palette.primaryText, weight = 600) }
        slots.historyLink?.let { styleSidebarText(it, g, 21f, 30f, palette.secondaryText) }
        val headerWidth = (g.width - g.left - g.px(90)).coerceAtLeast(1)
        val headerHeight = node(slots.historyHeader, headerWidth, minimumHeight = g.px(39))
        val clearance = g.px(card.focusClearancePx)
        val historyWidth = (headerWidth + 2 * clearance).coerceAtLeast(1)
        val historyHeight = g.px(card.height(resources.configuration.fontScale)) + 2 * clearance
        node(slots.history, historyWidth, exactHeight = historyHeight)
        val flow = g.home(
            titleHeight, metadataHeight, overviewHeight, actionsHeight, auxiliaryHeight,
            headerHeight, if (slots.history.visibility == View.GONE) 0 else historyHeight, clearance,
            expanded = historyExpanded,
        )
        node(slots.foregroundPoster, g.px(250), exactHeight = g.px(375))
        position(slots.foregroundPoster, g.px(1380), g.px(150))
        position(slots.title, g.left, flow.titleTop)
        position(slots.metadata, g.left, flow.metadataTop)
        slots.overview?.let { position(it, g.left, flow.overviewTop) }
        position(actions, g.left, flow.actionsTop)
        position(auxiliary, g.left, flow.auxiliaryTop)
        position(slots.historyHeader, g.left, flow.headerTop)
        position(slots.history, g.left - clearance, flow.historyTop)
        val heroOffset = if (historyExpanded)
            -maxOf(g.px(1080), flow.auxiliaryTop + auxiliaryHeight + g.px(100)).toFloat() else 0f
        heroNodes.forEach { it.translationY = heroOffset }
        finishMeasure(g.width, flow.contentHeight, heightMeasureSpec)
    }
}

private class SidebarDetailSurface(
    private val slots: DetailThemeSlots,
    private val palette: NativeThemePalette,
    private val episodePresentation: EpisodeRailPresentation,
) : SidebarSurface(slots.root.context) {
    private val resourcesColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = false
    }

    init {
        addView(slots.title)
        addView(slots.metadata)
        addView(slots.primaryActions)
        addView(slots.overview)
        slots.sourceInfo?.let { addView(it) }
        addView(slots.foregroundPoster)
        listOf(
            slots.routeHeader, slots.routes, slots.episodeHeader, slots.loading, slots.episodes,
            slots.selectedResource, slots.episodeOrder, slots.supplemental, slots.backToTop,
        ).forEach { child ->
            val nativeParams = child.layoutParams
            val compact = child === slots.episodeOrder || child === slots.backToTop
            resourcesColumn.addView(child, LinearLayout.LayoutParams(
                if (compact) nativeParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (compact) nativeParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.START })
        }
        addView(resourcesColumn)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = SidebarGeometry(MeasureSpec.getSize(widthMeasureSpec))
        beginMeasure()
        val titleHeight = text(slots.title, g.detailTitleWidth, g, 89f, 106.8f, palette.primaryText, weight = 700)
        val metadataHeight = text(slots.metadata, g.detailTitleWidth, g, 24f, 34f, palette.secondaryText)
        val actions = standardDetailActions(resources.displayMetrics.density)
        val toolWidth = actions.toolWidthsPx.sum() + actions.gapPx
        val actionWidth = maxOf(g.actionColumnWidth, actions.playWidthPx, toolWidth)
        val overviewLeft = maxOf(g.overviewLeft, g.left + actionWidth + g.px(40))
        val overviewWidth = (g.width - overviewLeft - g.px(100)).coerceAtLeast(1)
        val actionsHeight = node(slots.primaryActions, actionWidth)
        val overviewHeight = text(slots.overview, overviewWidth, g, 28f, 51.8f, 0xFFE0E0E5.toInt(), maxLines = 6)
        val sourceHeight = text(slots.sourceInfo, overviewWidth, g, 22f, 31f, palette.secondaryText)
        val flow = g.detail(titleHeight, metadataHeight, actionsHeight, overviewHeight, sourceHeight)
        val clearance = episodePresentation.focusClearancePx
        val resourceBounds = g.resourceColumn(flow.resourceTop, clearance)
        resourcesColumn.setPadding(clearance, clearance, clearance, clearance)
        for (index in 0 until resourcesColumn.childCount) {
            val child = resourcesColumn.getChildAt(index)
            if (child === slots.routeHeader || child === slots.episodeHeader) {
                (child as? TextView)?.let {
                    styleSidebarText(it, g, 30f, 38f, palette.primaryText, weight = 600)
                }
            }
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.bottomMargin =
                if (child === slots.routeHeader || child === slots.episodeHeader) g.px(24) else g.px(20)
            if (child === slots.episodes) {
                params.width = g.detailWidth + 2 * clearance
                params.leftMargin = -clearance
                params.rightMargin = -clearance
                params.topMargin = -clearance
                params.bottomMargin -= clearance
                // Keep the actual height set by configureEpisodes, including font-scale growth.
            }
        }
        val resourceHeight = node(resourcesColumn, resourceBounds.width)
        node(slots.foregroundPoster, g.px(180), exactHeight = g.px(270))
        position(slots.title, g.left, flow.titleTop)
        position(slots.metadata, g.left, flow.metadataTop)
        position(slots.primaryActions, g.left, flow.workspaceTop)
        position(slots.overview, overviewLeft, flow.workspaceTop)
        slots.sourceInfo?.let { position(it, overviewLeft, flow.sourceTop) }
        position(slots.foregroundPoster, g.px(1540), g.px(128))
        position(resourcesColumn, resourceBounds.left, resourceBounds.top)
        finishMeasure(g.width, maxOf(g.px(1080), resourceBounds.top + resourceHeight + g.px(40)), heightMeasureSpec)
    }
}
