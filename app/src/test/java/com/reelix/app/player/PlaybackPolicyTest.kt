package com.reelix.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun `接收量下降时速度不会变成负数`() {
        var rx = 10_000L
        val meter = NetworkSpeedMeter(0) { rx }
        meter.status(0)
        rx = 9_000L
        assertEquals(0L, meter.status(1_000).bytesPerSecond)
    }
    @Test fun `解码偏好明确过滤且未知设置回到自动`() {
        assertFalse(DecoderMode.HARDWARE.accepts(false, true))
        assertFalse(DecoderMode.SOFTWARE.accepts(true, false))
        assertTrue(DecoderMode.AUTO.accepts(true, false))
        assertTrue(DecoderMode.SOFTWARE.accepts(false, true))
        assertFalse(DecoderMode.HARDWARE.accepts(false, false))
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromStored("SOFTWARE"))
        assertEquals(DecoderMode.AUTO, DecoderMode.fromStored("unknown"))
    }
}
