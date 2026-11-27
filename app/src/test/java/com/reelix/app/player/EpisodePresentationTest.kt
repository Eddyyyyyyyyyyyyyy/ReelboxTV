package com.reelix.app.player

import com.reelix.app.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class EpisodePresentationTest {
    private val episodes = listOf(2, 5, 8).map { Episode(it, "第${it}集", "a", "file:$it") }
    @Test fun `倒序保留真实集号和原列表`() {
        assertEquals(listOf(8, 5, 2), episodePresentation(episodes, true).map { it.index })
        assertEquals(listOf(2, 5, 8), episodes.map { it.index })
    }
    @Test fun `反转后按业务身份恢复焦点而不是沿用下标`() {
        val shown = episodePresentation(episodes, true)
        assertEquals(2, episodePosition(shown, episodeKey(episodes.first()), 0))
        assertEquals(1, episodePosition(shown, episodeKey(episodes[1]), 0))
    }
}
