package com.reelix.app.player

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.ui.component.episodeCardPresentation
import org.junit.Assert.*
import org.junit.Test

class PlayerHeaderPresentationTest {
    private val series = MediaItem("series", "Test series", kind = MediaKind.SERIES)
    private val movie = MediaItem("movie", "Test Movie", kind = MediaKind.MOVIE)

    private fun header(episode: Episode?, item: MediaItem = series, resolution: String? = null) =
        playerHeaderPresentation(item, episode, resolution)

    @Test fun `episode changes update the label even when list index is unchanged`() {
        val first = Episode(1, "old", originalName = "作品 第2期上 2160p.mp4")
        val second = first.copy(originalName = "作品 第2期下 加更 2160p.mp4")
        assertEquals("第2期·上", header(first).episodeLabel)
        assertEquals("第2期·下·加更", header(second).episodeLabel)
        assertEquals("作品 第2期上 2160p.mp4", header(first).secondaryLine(null))
        assertEquals("作品 第2期下 加更 2160p.mp4", header(second).secondaryLine(null))
    }

    @Test fun `online player shows the full source file name not the cleaned short name`() {
        val raw = "现在就出发第四季 2026.10.18 第2期下 国语中字 2160p WEB-DL 60fps 完整版.mp4"
        val episode = Episode(1, "01  2160P", originalName = raw)
        assertEquals(raw, header(episode).fileName)
        assertEquals(raw, header(episode).secondaryLine(null))
    }

    @Test fun `legacy cache without original name falls back to the short episode label`() {
        val episode = Episode(12, "12  第12集", flag = "线路A", playId = "resource-12")
        assertNull(header(episode).fileName)
        assertEquals("第12集", header(episode).secondaryLine(null))
        assertEquals("12  第12集", playerEpisodeOptionLabel(episode))
    }

    @Test fun `episode option label keeps the full name while cards keep the short label`() {
        val raw = "作品 第2期下 加更 2160p.mp4"
        val episode = Episode(2, "02  2160P", originalName = raw)
        assertEquals(raw, playerEpisodeOptionLabel(episode))
        assertEquals("第2期·下·加更", episodeCardPresentation(episode).title)
    }

    @Test fun `episode option label falls back to display name when the source gave none`() {
        assertEquals("第3集", playerEpisodeOptionLabel(Episode(3, "第3集")))
    }

    @Test fun `long source names use existing short episode display without changing source`() {
        val raw = "作品 第十二集 " + "long original resource name ".repeat(80) + "1080p.mkv"
        val episode = Episode(7, "07  1080P", originalName = raw)
        assertEquals("第12集", header(episode).episodeLabel)
        assertEquals(raw, episode.originalName)
    }

    @Test fun `unparsed long names remain available for UI ellipsis`() {
        val raw = "Behind the scenes ".repeat(80).trim()
        assertEquals(raw, header(Episode(9, raw)).episodeLabel)
    }

    @Test fun `missing names do not manufacture an episode from list index or URL`() {
        assertNull(header(Episode(8, "", playId = "https://example.com/private")).episodeLabel)
        assertNull(header(Episode(8, "", playId = "https://example.com/private")).episodeNumber)
        assertNull(header(Episode(8, "old", originalName = "")).episodeLabel)
        assertNull(header(Episode(8, "https://example.com/private")).episodeLabel)
    }

    @Test fun `local numeric episode uses parsed number not list position`() {
        val value = header(Episode(9, "01", "alist", "/series/01.mkv"))
        assertNull(value.episodeLabel)
        assertEquals(1, value.episodeNumber)
    }

    @Test fun `local unknown episode uses resource basename`() {
        assertEquals("幕后", header(Episode(9, "9", "alist", "/series/幕后.mp4")).episodeLabel)
    }

    @Test fun `movie duplicate title is suppressed ignoring case whitespace and extension`() {
        assertNull(header(Episode(1, "test   movie.mkv"), movie).episodeLabel)
        assertNull(header(Episode(1, "old", originalName = "Test Movie"), movie).episodeLabel)
    }

    @Test fun `generic movie labels and list numbers are not episodes`() {
        listOf("", "正片", "播放", "在线播放", "电影", "01", "第1集").forEach {
            val value = header(Episode(1, it), movie)
            assertNull(it, value.episodeLabel)
            assertNull(it, value.episodeNumber)
        }
    }

    @Test fun `movie quality voice and dynamic range remain distinguishable`() {
        val episode = Episode(2, "02  4K", originalName = "Test Movie 2160p 国语版 DV.mkv")
        assertEquals("4K·国语版·DV", header(episode, movie).episodeLabel)
        assertNull(header(episode, movie).episodeNumber)
    }

    @Test fun `local movie versions retain quality even when compact title repeats movie`() {
        val episode = Episode(2, "2", "alist", "/movie/Test Movie 1080p.mkv")
        assertEquals("1080P", header(episode, movie).episodeLabel)
    }

    @Test fun `named movie cuts are retained`() {
        assertEquals("导演剪辑版", header(Episode(1, "导演剪辑版"), movie).episodeLabel)
    }

    @Test fun `resolution comes only from actual playback not the filename`() {
        val episode = Episode(1, "作品 第3集 2160p.mp4")
        assertEquals("1280×720", header(episode, resolution = "1280×720").resolutionLabel)
        assertNull(header(episode).resolutionLabel)
    }

    @Test fun `absent episode and resolution leave only the media title`() {
        val value = header(null)
        assertEquals(series.title, value.title)
        assertNull(value.episodeLabel)
        assertNull(value.episodeNumber)
        assertNull(value.resolutionLabel)
    }

    @Test fun `resolution stays visible without an episode`() {
        assertEquals("1920×1080", header(null, resolution = "1920×1080").resolutionLabel)
    }

    @Test fun `bitrate shares the resolution line and is omitted when unknown`() {
        val episode = Episode(1, "正片")
        assertEquals("3840×2160 · 18.5 Mbps",
            playerHeaderPresentation(movie, episode, "3840×2160", 18_500_000).resolutionLabel)
        assertEquals("3840×2160",
            playerHeaderPresentation(movie, episode, "3840×2160", null).resolutionLabel)
        assertEquals("18.5 Mbps",
            playerHeaderPresentation(movie, episode, null, 18_500_000).resolutionLabel)
        assertNull(playerHeaderPresentation(movie, episode, null, 0).resolutionLabel)
    }
}
