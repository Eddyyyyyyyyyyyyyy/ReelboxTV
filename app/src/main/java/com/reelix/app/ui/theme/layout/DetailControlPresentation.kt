package com.reelix.app.ui.theme.layout

import com.reelix.app.ui.component.ViewTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class DetailToolSlotLayout(
    val widthPx: Int,
    val leftMarginPx: Int,
)

/**
 * Resolves tool widths per visible slot. A lone artwork button uses the full play
 * button width; two visible tools keep their declared slot widths and gap.
 */
internal fun resolveDetailToolLayouts(
    visibleSlots: List<Boolean>,
    playWidthPx: Int,
    toolWidthsPx: List<Int>,
    gapPx: Int,
): List<DetailToolSlotLayout> {
    val visibleIndexes = visibleSlots.indices.filter { visibleSlots[it] }
    return visibleSlots.mapIndexed { index, visible ->
        val fallbackWidth = toolWidthsPx.getOrElse(index) { playWidthPx / 2 }
        if (!visible) {
            DetailToolSlotLayout(fallbackWidth, 0)
        } else {
            val position = visibleIndexes.indexOf(index)
            DetailToolSlotLayout(
                widthPx = if (visibleIndexes.size == 1) playWidthPx else fallbackWidth,
                leftMarginPx = if (position <= 0) 0 else gapPx,
            )
        }
    }
}

fun standardDetailActions(density: Float): DetailActionPresentation {
    fun pixels(value: Int) = (value * density).roundToInt()
    return DetailActionPresentation(
        playWidthPx = pixels(260),
        playHeightPx = pixels(46),
        playTextPx = 14f * density,
        // Tool widths are resolved per visible button; two slots keep folder + artwork aligned.
        toolWidthsPx = listOf(pixels(170), pixels(170)),
        toolHeightPx = pixels(40),
        toolTextPx = 13f * density,
        gapPx = pixels(8),
        toolsBelow = true,
        toolGroupGapPx = pixels(8),
    )
}

fun standardEpisodePresentation(density: Float, fontScale: Float = 1f): EpisodeRailPresentation {
    fun pixels(value: Float) = (value * density).roundToInt().coerceAtLeast(1)
    fun lineHeight(size: Float) = ceil(size * density * fontScale * 1.5f).toInt()
    val frameHeight = pixels(20f) + maxOf(lineHeight(19f) * 2, lineHeight(11f) * 2) +
        lineHeight(11f) + pixels(5f)
    return EpisodeRailPresentation(
        widthPx = pixels(160f),
        heightPx = maxOf(pixels(96f), frameHeight),
        gapPx = pixels(16f),
        titleTextPx = 19f * density,
        subtitleTextPx = 11f * density,
        horizontalPaddingPx = pixels(12f),
        verticalPaddingPx = pixels(10f),
        cornerRadiusPx = pixels(8f),
        focusStrokePx = pixels(2f),
        focusOutsetPx = pixels(2.5f),
        captionHeightPx = maxOf(pixels(36f), lineHeight(12f) * 2),
        captionGapPx = pixels(6f),
        captionTextPx = 12f * density,
        qualityWidthPx = pixels(54f),
        qualityTextPx = 11f * density,
        progressHeightPx = pixels(3f),
    )
}

internal fun nativeDetailPalette() = NativeThemePalette(
    background = ViewTheme.backdrop,
    surface = ViewTheme.surfaceElevated,
    raisedSurface = ViewTheme.surfaceHigh,
    primaryText = ViewTheme.textPrimary,
    secondaryText = ViewTheme.textSecondary,
    accent = ViewTheme.accent,
    onAccent = 0xFF121212.toInt(),
    focusRing = ViewTheme.focusRing,
)
