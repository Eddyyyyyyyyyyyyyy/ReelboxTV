package com.reelix.app.ui.screen.player

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp

enum class PlayerPanelSize(val key: String) {
    COMPACT("COMPACT"), SMALL("SMALL"), STANDARD("STANDARD"), LARGE("LARGE");

    companion object {
        fun fromStored(value: String): PlayerPanelSize = entries.firstOrNull { it.key == value } ?: STANDARD
    }
}

/** dp/sp multipliers only; no View or Compose scaleX/scaleY transform. */
data class PlayerPanelScale(private val size: PlayerPanelSize) {
    val factor: Float = when (size) {
        PlayerPanelSize.COMPACT -> 0.75f
        PlayerPanelSize.SMALL -> 0.85f
        PlayerPanelSize.STANDARD -> 1f
        PlayerPanelSize.LARGE -> 1.15f
    }

    /** Free scaling for spacing/type; touch targets must use [touchDp]. */
    fun dp(value: Float): Dp = (value * factor).dp
    fun dpInt(value: Int): Dp = dp(value.toFloat())
    fun touchDp(value: Float): Dp = (value * factor).coerceAtLeast(MIN_TOUCH_DP).dp
    fun touchDpInt(value: Int): Dp = touchDp(value.toFloat())
    fun sp(value: Float): TextUnit = (value * factor).sp
    fun spInt(value: Int): TextUnit = sp(value.toFloat())

    companion object {
        const val MIN_TOUCH_DP = 40f
    }
}
