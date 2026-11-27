package com.reelix.app.ui.screen.detail

enum class DetailArtworkMode { NEUTRAL, POSTER, WIDE }

/** A URL is only a request. Keep an existing poster until a wide image actually succeeds. */
fun detailArtworkMode(posterLoaded: Boolean, wideLoaded: Boolean, waitingKnownWide: Boolean,
    posterWasVisible: Boolean): DetailArtworkMode = when {
    wideLoaded -> DetailArtworkMode.WIDE
    posterLoaded && (!waitingKnownWide || posterWasVisible) -> DetailArtworkMode.POSTER
    else -> DetailArtworkMode.NEUTRAL
}
