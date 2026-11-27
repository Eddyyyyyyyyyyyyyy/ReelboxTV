package com.reelix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFallbackResumeTest {
    @Test
    fun `a fallback engine without progress keeps the last usable resume position`() {
        val audio = AudioPreference("exo:0:2", "English", "en", 2)
        val previous = PlaybackResume(positionMs = 1_922_435L, playWhenReady = true, audio = audio)
        val failedBeforeReady = PlaybackResume(positionMs = 0L, playWhenReady = true, audio = null)

        val merged = mergeFallbackResume(previous, failedBeforeReady)

        assertEquals(1_922_435L, merged?.positionMs)
        assertEquals(audio, merged?.audio)
    }

    @Test
    fun `a newer fallback snapshot replaces an older resume point`() {
        val previous = PlaybackResume(positionMs = 1_000L, playWhenReady = true, audio = null)
        val current = PlaybackResume(positionMs = 2_000L, playWhenReady = false, audio = null)

        assertEquals(current, mergeFallbackResume(previous, current))
    }

    @Test
    fun `a missing snapshot does not discard a resume point`() {
        val previous = PlaybackResume(positionMs = 1_000L, playWhenReady = true, audio = null)

        assertEquals(previous, mergeFallbackResume(previous, null))
        assertNull(mergeFallbackResume(null, null))
    }
}
