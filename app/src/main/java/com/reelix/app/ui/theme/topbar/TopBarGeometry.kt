package com.reelix.app.ui.theme.topbar

import kotlin.math.roundToInt

internal data class TopBarRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val bottom: Int get() = y + height
}

internal data class TopBarHomeGeometry(
    val heroHeight: Int,
    val headerTop: Int,
    val history: TopBarRect,
)

internal data class TopBarDetailGeometry(
    val titleTop: Int,
    val metadataTop: Int,
    val actionsTop: Int,
    val overviewTop: Int,
    val sourceTop: Int,
    val resourcesTop: Int,
)

/** Reference lengths are physical pixels on the approved full 1920x1080 canvas. */
internal object TopBarGeometry {
    fun scale(width: Int): Float = width.coerceAtLeast(0) / 1920f

    fun px(reference: Number, scale: Float): Int = (reference.toFloat() * scale).roundToInt()

    fun home(
        width: Int,
        infoBottom: Int,
        headerHeight: Int,
        cardHeight: Int,
        clearance: Int,
        expanded: Boolean,
    ): TopBarHomeGeometry {
        val scale = scale(width)
        val headerTop = if (expanded) px(128, scale) else maxOf(px(865, scale), infoBottom + px(63, scale))
        return TopBarHomeGeometry(
            heroHeight = maxOf(px(890, scale), infoBottom + px(88, scale)),
            headerTop = headerTop,
            history = TopBarRect(
                x = px(100, scale) - clearance,
                y = headerTop + headerHeight + px(23, scale) - clearance,
                width = px(1730, scale) + clearance * 2,
                height = cardHeight.coerceAtLeast(0) + clearance * 2,
            ),
        )
    }

    fun detail(
        width: Int,
        titleHeight: Int,
        metadataHeight: Int,
        actionsHeight: Int,
        overviewHeight: Int,
        sourceHeight: Int,
    ): TopBarDetailGeometry {
        val scale = scale(width)
        val titleTop = px(350, scale)
        val metadataTop = titleTop + titleHeight + px(21, scale)
        val metadataBottom = if (metadataHeight > 0) metadataTop + metadataHeight else titleTop + titleHeight
        val actionsTop = maxOf(px(566, scale), metadataBottom + px(56, scale))
        val overviewTop = actionsTop + actionsHeight + px(30, scale)
        val overviewBottom = overviewTop + overviewHeight
        val sourceTop = overviewBottom + if (sourceHeight > 0) px(12, scale) else 0
        val infoBottom = if (sourceHeight > 0) sourceTop + sourceHeight else overviewBottom
        return TopBarDetailGeometry(
            titleTop, metadataTop, actionsTop, overviewTop, sourceTop,
            resourcesTop = maxOf(px(901, scale), infoBottom + px(84, scale)),
        )
    }

    fun episodes(
        width: Int,
        imageTop: Int,
        imageHeight: Int,
        clearance: Int,
        measuredHeight: Int,
    ): TopBarRect = TopBarRect(
        x = px(100, scale(width)) - clearance,
        y = imageTop - clearance,
        width = px(1720, scale(width)) + clearance * 2,
        height = maxOf(imageHeight + clearance * 2, measuredHeight.coerceAtLeast(0)),
    )
}
