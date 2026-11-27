package com.reelix.app.data.image

import org.junit.Assert.*
import org.junit.Test

class ArtworkPreviewTest {
    @Test fun `URL仅表示待确认真实加载且为横图后才可提交横幅`() {
        assertEquals(ArtworkBackdropState.NONE, artworkBackdropState(null, null))
        assertEquals(ArtworkBackdropState.PENDING, artworkBackdropState("https://example.com/b", null))
        assertEquals(ArtworkBackdropState.FAILED, artworkBackdropState("https://example.com/b", false))
        assertEquals(ArtworkBackdropState.UNSUITABLE, artworkBackdropState("https://example.com/b", true, 400, 600))
        assertEquals(ArtworkBackdropState.LOADED, artworkBackdropState("https://example.com/b", true, 1280, 720))
    }
    @Test fun `共享海报的不同候选仍有独立身份`() {
        val one = LibraryArtworkCandidate("同名", 2020, "https://example.com/p", identity = LibraryWorkIdentity("TMDB", "1", "movie", "同名"))
        val two = one.copy(identity = one.identity!!.copy(id = "2"))
        assertNotEquals(artworkCandidateKey(one), artworkCandidateKey(two))
    }
}
