package com.reelix.app.ui.screen.player

import com.reelix.app.player.TransferActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTransferStateTest {
    @Test
    fun `paused playback reports paused before download state`() {
        assertEquals(
            PlayerNetworkState.PAUSED,
            playerNetworkState(false, TransferActivity.DOWNLOADING, 8_000_000),
        )
    }

    @Test
    fun `download speed is reported only when bytes arrive`() {
        assertEquals(PlayerNetworkState.DOWNLOADING,
            playerNetworkState(true, TransferActivity.DOWNLOADING, 2_000_000))
        assertEquals(PlayerNetworkState.NO_DOWNLOAD,
            playerNetworkState(true, TransferActivity.IDLE, 0))
    }

    @Test
    fun `unsupported traffic stats are not shown as idle`() {
        assertEquals(PlayerNetworkState.UNAVAILABLE,
            playerNetworkState(true, TransferActivity.UNAVAILABLE, 0))
    }
}
