package com.reelix.app.ui.theme.sidebar

import com.reelix.app.ui.theme.layout.EpisodeRailPresentation
import com.reelix.app.ui.theme.layout.HistoryCardStyle
import com.reelix.app.ui.theme.layout.standardEpisodePresentation
import kotlin.math.max
import kotlin.math.roundToInt

internal fun sidebarHistoryCardStyle() = HistoryCardStyle(
    imageWidthPx = 400, imageHeightPx = 220, gapPx = 25,
    titleTextPx = 23f, subtitleTextPx = 18f,
    overlayTitle = true, cornerRadiusPx = 8,
    focusStrokePx = 4, focusOutsetPx = 5, progressHeightPx = 5,
)

internal fun sidebarEpisodePresentation(density: Float = 2f, fontScale: Float = 1f) =
    standardEpisodePresentation(density, fontScale)

/** Reference pixels, scaled once from the full page width, never from a column. */
internal class SidebarGeometry(hostWidthPx: Int) {
    val width = hostWidthPx.coerceAtLeast(1)
    val scale = width / 1920f

    fun px(referencePx: Number): Int = (referencePx.toFloat() * scale).roundToInt()

    val left get() = px(175)
    val homeWidth get() = px(900).coerceAtLeast(1)
    val homeTitleWidth get() = px(850).coerceAtLeast(1)
    val homeOverviewWidth get() = px(700).coerceAtLeast(1)
    val detailWidth get() = (width - left - px(100)).coerceAtLeast(1)
    val detailTitleWidth get() = minOf(px(1200).coerceAtLeast(1), detailWidth)
    val actionColumnWidth get() = px(580).coerceAtLeast(1)
    val overviewLeft get() = px(805)
    val detailOverviewWidth get() = (width - overviewLeft - px(100)).coerceAtLeast(1)

    fun resourceColumn(resourceTop: Int, clearance: Int): ResourceColumn {
        val padding = clearance.coerceAtLeast(0)
        return ResourceColumn(left - padding, resourceTop - padding, detailWidth + 2 * padding, padding)
    }

    data class ResourceColumn(val left: Int, val top: Int, val width: Int, val padding: Int)

    fun home(
        titleHeight: Int,
        metadataHeight: Int,
        overviewHeight: Int,
        actionsHeight: Int,
        auxiliaryHeight: Int,
        headerHeight: Int,
        historyHeight: Int,
        focusClearance: Int,
        expanded: Boolean = false,
    ): Home {
        val titleTop = px(230)
        val titleBottom = titleTop + titleHeight
        val metadataTop = titleBottom + if (metadataHeight > 0) px(16) else 0
        val metadataBottom = metadataTop + metadataHeight
        val overviewTop = metadataBottom + if (overviewHeight > 0) px(20) else 0
        val overviewBottom = overviewTop + overviewHeight
        val actionsTop = overviewBottom + if (actionsHeight > 0) px(26) else 0
        val actionsBottom = actionsTop + actionsHeight
        val auxiliaryTop = actionsBottom + if (auxiliaryHeight > 0) px(17) else 0
        val auxiliaryBottom = auxiliaryTop + auxiliaryHeight
        val headerTop = if (expanded) px(144) else max(px(744), auxiliaryBottom + px(72))
        val imageTop = if (expanded) headerTop + headerHeight + px(23)
            else max(px(806), headerTop + headerHeight + px(23))
        return Home(
            titleTop, metadataTop, overviewTop, actionsTop, auxiliaryTop,
            headerTop, imageTop - focusClearance,
            max(px(1080), imageTop + historyHeight - 2 * focusClearance + px(24)),
        )
    }

    fun detail(
        titleHeight: Int,
        metadataHeight: Int,
        actionsHeight: Int,
        overviewHeight: Int,
        sourceHeight: Int,
    ): Detail {
        val titleTop = px(300)
        val metadataTop = titleTop + titleHeight + if (metadataHeight > 0) px(21) else 0
        val workspaceTop = max(px(550), metadataTop + metadataHeight + px(88))
        val sourceTop = workspaceTop + overviewHeight + if (sourceHeight > 0) px(17) else 0
        val bodyBottom = max(workspaceTop + actionsHeight, sourceTop + sourceHeight)
        return Detail(titleTop, metadataTop, workspaceTop, sourceTop, max(px(885), bodyBottom + px(48)))
    }

    data class Home(
        val titleTop: Int,
        val metadataTop: Int,
        val overviewTop: Int,
        val actionsTop: Int,
        val auxiliaryTop: Int,
        val headerTop: Int,
        val historyTop: Int,
        val contentHeight: Int,
    )

    data class Detail(
        val titleTop: Int,
        val metadataTop: Int,
        val workspaceTop: Int,
        val sourceTop: Int,
        val resourceTop: Int,
    )
}
