package com.reelix.app.ui.screen.player

import com.reelix.app.player.TransferActivity

enum class PlayerNetworkState {
    PAUSED,
    CONNECTING,
    DOWNLOADING,
    NO_DOWNLOAD,
    LOCAL,
    UNAVAILABLE,
}

fun playerNetworkState(
    isPlaying: Boolean,
    activity: TransferActivity,
    bytesPerSecond: Long,
): PlayerNetworkState = when {
    !isPlaying -> PlayerNetworkState.PAUSED
    activity == TransferActivity.UNAVAILABLE -> PlayerNetworkState.UNAVAILABLE
    activity == TransferActivity.LOCAL -> PlayerNetworkState.LOCAL
    activity == TransferActivity.CONNECTING -> PlayerNetworkState.CONNECTING
    bytesPerSecond > 0 -> PlayerNetworkState.DOWNLOADING
    else -> PlayerNetworkState.NO_DOWNLOAD
}

