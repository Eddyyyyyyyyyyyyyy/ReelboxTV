package com.reelix.app.ui.screen.player

/**
 * Returns the delay before hiding the player controls, or null when they must stay visible.
 *
 * A configured value of 0 means "never hide"; it must not be treated as an immediate hide.
 */
fun controlsAutoHideDelayMs(
    seconds: Int,
    controlsVisible: Boolean,
    isPlaying: Boolean,
    episodesVisible: Boolean,
    menuOpen: Boolean,
): Long? {
    if (seconds <= 0 || !controlsVisible || !isPlaying || episodesVisible || menuOpen) return null
    return seconds * 1_000L
}
