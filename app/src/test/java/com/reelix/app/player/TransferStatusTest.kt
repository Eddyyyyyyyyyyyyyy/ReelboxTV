package com.reelix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferStatusTest {
    @Test
    fun `unsupported traffic stats never fake a zero rate`() {
        val meter = NetworkSpeedMeter(0) { NetworkSpeedMeter.UNSUPPORTED }
        assertEquals(TransferActivity.UNAVAILABLE, meter.status(1_000).activity)
    }

    @Test
    fun `receive delta becomes a smoothed megabyte rate`() {
        var rx = 1_000L
        val meter = NetworkSpeedMeter(0) { rx }
        meter.status(0)
        rx += 2 * 1024 * 1024
        assertEquals(2L * 1024 * 1024, meter.status(1_000).bytesPerSecond)
        assertEquals(TransferActivity.DOWNLOADING, meter.status(2_000).activity)
    }

    @Test
    fun `idle and local playback report zero without polling errors`() {
        var rx = 5_000L
        val meter = NetworkSpeedMeter(0) { rx }
        meter.status(0)
        assertEquals(0L, meter.status(1_000).bytesPerSecond)
        meter.begin(1_000, observable = true, local = true)
        assertEquals(TransferActivity.IDLE, meter.status(2_000).activity)
    }

    @Test
    fun `speed label stays numeric and bitrate is optional`() {
        assertEquals("2.00 MB/s", formatNetworkSpeed(2L * 1024 * 1024))
        assertEquals("0.0 MB/s", formatNetworkSpeed(0))
        assertEquals("18.5 Mbps", formatVideoBitrate(18_500_000))
        assertNull(formatVideoBitrate(null))
        assertNull(formatVideoBitrate(0))
    }
}
