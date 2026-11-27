package com.reelix.app.ui.theme.gallery

import com.reelix.app.ui.theme.layout.EpisodeRailPresentation
import com.reelix.app.ui.theme.layout.HistoryCardStyle
import com.reelix.app.ui.theme.layout.standardEpisodePresentation
import kotlin.math.max
import kotlin.math.roundToInt

/** All anchors are original 1920 x 1080 screen pixels, including navigation. */
internal class GalleryGeometry(widthPx: Int, private val navigationHeightPx: Int? = null) {
    private val scale = widthPx.coerceAtLeast(1) / 1920f
    fun px(reference: Number): Int = (reference.toFloat() * scale).roundToInt()
    fun text(reference: Float, fontScale: Float): Float = reference * scale * fontScale

    val navigationHeight: Int get() = navigationHeightPx ?: px(152)
    fun contentY(screenY: Int): Int = screenY - px(112)
    fun contentHeight(screenHeight: Int): Int = (screenHeight - navigationHeight).coerceAtLeast(0)

    fun historyStyle(posterColumns: Int = 6) = HistoryCardStyle(
        imageWidthPx = px(215), imageHeightPx = px(315), gapPx = px(35),
        titleTextPx = text(22f, 1f), subtitleTextPx = text(18f, 1f),
        titleGapPx = px(15), subtitleGapPx = px(5), cornerRadiusPx = px(10),
        focusStrokePx = max(1, px(4)), focusOutsetPx = px(6),
        progressHeightPx = max(1, px(5)),
    ).forPosterColumns(posterColumns)

    fun episodeStyle(density: Float = 2f * scale, fontScale: Float = 1f) =
        standardEpisodePresentation(density, fontScale)

    data class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
        val bottom: Int get() = y + height
        val right: Int get() = x + width
    }

    data class Home(
        val collectionTop: Int,
        val historyTop: Int,
        val historyImageTop: Int,
        val recentTop: Int,
        val recentImageTop: Int,
    )

    data class Detail(
        val metadataTop: Int,
        val overviewTop: Int,
        val sourceTop: Int,
        val actionsTop: Int,
        val resourceTop: Int,
    )

    fun poster(): Box = Box(px(100), px(185), px(355), px(515))

    fun rail(imageTop: Int, cardHeight: Int, clearance: Int): Box =
        Box(px(100) - clearance, imageTop - clearance,
            px(1720) + clearance * 2, cardHeight + clearance * 2)

    fun home(
        resumeHeight: Int,
        collectionHeight: Int,
        historyHeaderHeight: Int,
        recentHeaderHeight: Int,
        cardHeight: Int,
        hasHistory: Boolean,
    ): Home {
        val resumeGrowth = max(0, resumeHeight - px(53))
        val collectionGrowth = max(0, collectionHeight - px(132))
        val historyTop = px(416) + resumeGrowth + collectionGrowth
        val imageTop = historyTop + max(px(39), historyHeaderHeight) + px(28)
        val recentTop = if (hasHistory) {
            max(px(930) + resumeGrowth + collectionGrowth, imageTop + cardHeight + px(57))
        } else historyTop
        return Home(
            px(255) + resumeGrowth, historyTop, imageTop,
            recentTop, recentTop + max(px(39), recentHeaderHeight) + px(28),
        )
    }

    fun detail(
        titleHeight: Int,
        metadataHeight: Int,
        overviewHeight: Int,
        sourceHeight: Int,
        actionsHeight: Int,
    ): Detail {
        val metadataTop = px(199) + max(px(73), titleHeight) + px(21)
        val overviewTop = max(px(365),
            metadataTop + metadataHeight + px(38))
        val sourceTop = overviewTop + overviewHeight + if (sourceHeight > 0) px(17) else 0
        val actionsTop = sourceTop + sourceHeight + px(33)
        return Detail(
            metadataTop, overviewTop, sourceTop, actionsTop,
            max(px(806), actionsTop + actionsHeight + px(188)),
        )
    }
}
