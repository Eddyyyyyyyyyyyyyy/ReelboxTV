package com.reelix.app.ui.screen.settings

/**
 * Pure UP/DOWN neighbour calculation for the settings content pane.
 *
 * Rows are indexed top-to-bottom; each row holds its focusable items left-to-right.
 * Vertical movement stays inside the pane and clamps horizontally, so a 3-chip
 * segmented row can still move down into a single full-width row (and back),
 * while the first row's UP and the last row's DOWN resolve to "no target"
 * (the caller pins focus in place instead of letting the system jump to the rail).
 */
internal object SettingsFocusChain {

    /**
     * @return (rowIndex, columnIndex) of the vertical neighbour, or null at the pane edge.
     */
    fun verticalNeighbor(
        rowSizes: List<Int>,
        rowIndex: Int,
        column: Int,
        direction: Int,
    ): Pair<Int, Int>? {
        if (direction == 0 || rowIndex !in rowSizes.indices) return null
        var index = rowIndex + if (direction > 0) 1 else -1
        while (index in rowSizes.indices) {
            val size = rowSizes[index]
            if (size > 0) return index to column.coerceIn(0, size - 1)
            index += if (direction > 0) 1 else -1
        }
        return null
    }
}
