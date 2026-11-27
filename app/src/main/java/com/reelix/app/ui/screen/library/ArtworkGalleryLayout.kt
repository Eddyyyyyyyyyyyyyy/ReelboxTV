package com.reelix.app.ui.screen.library

/**
 * Pure sizing rules for the artwork rail and the enlarged preview.
 *
 * Keeping these out of the view makes the "two rows always fit" guarantee testable
 * without an Android runtime.
 */
internal data class ArtworkRailCellSize(
    val widthPx: Int,
    val heightPx: Int,
    val imageHeightPx: Int,
    val labelHeightPx: Int,
)

internal fun artworkRailCellSize(
    railHeightPx: Int,
    gapPx: Int,
    labelHeightPx: Int,
): ArtworkRailCellSize {
    val safeGap = gapPx.coerceAtLeast(0)
    val safeLabel = labelHeightPx.coerceAtLeast(0)
    val height = ((railHeightPx - safeGap) / 2).coerceAtLeast(safeLabel + 40)
    val imageHeight = (height - safeLabel).coerceAtLeast(40)
    val width = (imageHeight * 2 / 3).coerceAtLeast(40)
    return ArtworkRailCellSize(width, height, imageHeight, safeLabel)
}

internal data class ArtworkLargePreviewSize(
    val portraitWidthPx: Int,
    val portraitHeightPx: Int,
    val landscapeWidthPx: Int,
    val landscapeHeightPx: Int,
    val columnWidthPx: Int,
)

internal fun artworkLargePreviewSize(
    mainWidthPx: Int,
    mainHeightPx: Int,
    textHeightPx: Int,
    gapPx: Int,
    maxColumnRatio: Float = 0.58f,
): ArtworkLargePreviewSize {
    val safeGap = gapPx.coerceAtLeast(0)
    val availableHeight = (mainHeightPx - textHeightPx - safeGap).coerceAtLeast(120)
    var portraitHeight = availableHeight
    var portraitWidth = (portraitHeight * 2 / 3).coerceAtLeast(40)
    var landscapeWidth = (portraitWidth * 3 / 2).coerceAtLeast(40)
    var landscapeHeight = (landscapeWidth * 9 / 16).coerceAtLeast(40)
    if (landscapeHeight > availableHeight) {
        landscapeHeight = availableHeight
        landscapeWidth = (landscapeHeight * 16 / 9).coerceAtLeast(40)
    }
    var columnWidth = portraitWidth + safeGap + landscapeWidth
    val maxColumnWidth = (mainWidthPx * maxColumnRatio).toInt()
    if (maxColumnWidth > 0 && columnWidth > maxColumnWidth) {
        val scale = maxColumnWidth.toFloat() / columnWidth
        portraitWidth = (portraitWidth * scale).toInt().coerceAtLeast(40)
        portraitHeight = (portraitWidth * 3 / 2).coerceAtLeast(40)
        landscapeWidth = (landscapeWidth * scale).toInt().coerceAtLeast(40)
        landscapeHeight = (landscapeWidth * 9 / 16).coerceAtLeast(40)
        columnWidth = portraitWidth + safeGap + landscapeWidth
    }
    return ArtworkLargePreviewSize(
        portraitWidthPx = portraitWidth,
        portraitHeightPx = portraitHeight,
        landscapeWidthPx = landscapeWidth,
        landscapeHeightPx = landscapeHeight,
        columnWidthPx = columnWidth,
    )
}
