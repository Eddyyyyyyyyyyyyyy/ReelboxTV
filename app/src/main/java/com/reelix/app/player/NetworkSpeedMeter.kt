package com.reelix.app.player

import android.net.TrafficStats
import android.os.Process
import java.util.Locale

enum class TransferActivity { CONNECTING, DOWNLOADING, CACHE, LOCAL, IDLE, UNAVAILABLE }

data class TransferStatus(
    val bytesPerSecond: Long = 0,
    val activity: TransferActivity = TransferActivity.CONNECTING,
)

/**
 * App-wide receive rate from [TrafficStats].
 *
 * Every engine reports the same number, so ExoPlayer and VLC cannot disagree about
 * "network speed". The value includes artwork and refresh traffic, and drops to zero
 * while playback is served from cache — both are expected.
 */
class NetworkSpeedMeter(
    startMs: Long,
    private val rxProvider: () -> Long = ::appRxBytes,
) {
    private val samples = ArrayDeque<Long>(SAMPLE_COUNT)
    private var lastTotal = UNSUPPORTED
    private var lastTime = startMs
    private var running = true

    init {
        reset(startMs)
    }

    @Synchronized
    fun begin(nowMs: Long, observable: Boolean = true, local: Boolean = false) {
        running = observable && !local
        reset(nowMs)
    }

    @Synchronized
    fun status(nowMs: Long): TransferStatus {
        val total = rxBytes()
        if (total < 0) {
            return TransferStatus(activity = TransferActivity.UNAVAILABLE)
        }
        if (lastTotal < 0) {
            lastTotal = total
            lastTime = nowMs
            return TransferStatus(activity = TransferActivity.CONNECTING)
        }
        val elapsed = nowMs - lastTime
        if (elapsed < SAMPLE_INTERVAL_MS) return snapshot()
        val delta = (total - lastTotal).coerceAtLeast(0)
        lastTotal = total
        lastTime = nowMs
        samples.addLast(delta * 1000 / elapsed)
        while (samples.size > SAMPLE_COUNT) samples.removeFirst()
        return snapshot()
    }

    private fun snapshot(): TransferStatus {
        if (!running) return TransferStatus(activity = TransferActivity.IDLE)
        val average = if (samples.isEmpty()) 0L else samples.sum() / samples.size
        return TransferStatus(
            bytesPerSecond = average,
            activity = if (average > 0) TransferActivity.DOWNLOADING else TransferActivity.IDLE,
        )
    }

    private fun reset(nowMs: Long) {
        samples.clear()
        lastTotal = rxBytes()
        lastTime = nowMs
    }

    private fun rxBytes(): Long = runCatching { rxProvider() }.getOrDefault(UNSUPPORTED)

    companion object {
        private const val SAMPLE_COUNT = 4
        private const val SAMPLE_INTERVAL_MS = 1_000L
        const val UNSUPPORTED = -1L

        private fun appRxBytes(): Long = TrafficStats.getUidRxBytes(Process.myUid())
    }
}

/** Numeric-only speed label; no "App network" wording in the HUD. */
fun formatNetworkSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "0.0 MB/s"
    val mbps = bytesPerSecond / (1024.0 * 1024.0)
    return if (mbps >= 10) String.format(Locale.US, "%.1f MB/s", mbps)
    else String.format(Locale.US, "%.2f MB/s", mbps)
}

/** Video bitrate label; only shown when the container actually declares one. */
fun formatVideoBitrate(bitsPerSecond: Int?): String? {
    if (bitsPerSecond == null || bitsPerSecond <= 0) return null
    val mbps = bitsPerSecond / 1_000_000.0
    return if (mbps >= 10) String.format(Locale.US, "%.1f Mbps", mbps)
    else String.format(Locale.US, "%.2f Mbps", mbps)
}
