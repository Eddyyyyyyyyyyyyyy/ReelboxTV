package com.reelix.app.ui.component

import com.reelix.app.data.model.Episode
import com.reelix.app.data.source.jar.TvBoxJsonParser
import com.reelix.app.data.source.online.CachedEpisode
import com.reelix.app.data.source.online.CachedPlayRoute
import com.reelix.app.player.episodeKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEpisodeNamesTest {
    private fun parse(vararg entries: Pair<String, String>) = TvBoxJsonParser.parsePlayRoutes(
        buildJsonObject {
            put("vod_play_from", "测试线路")
            put("vod_play_url", entries.joinToString("#") { "${it.first}\$${it.second}" })
        },
        "site",
    ).single()

    @Test fun `在线长名称原样保留并独立展示简短的综艺集号`() {
        val original = "现在就出发第四季 2026.10.18 第2期下 国语中字 2160p WEB-DL 60fps 完整版.mp4"
        val episode = parse(original to "episode-lower").episodes.single()
        val display = episodeCardPresentation(episode)
        assertEquals(original, display.resourceName)
        assertEquals("第2期·下", display.title)
        assertEquals(2, display.number)
    }

    @Test fun `电影版本显示清晰度配音且不把排列序号当作集号`() {
        val original = "[12.75GB] Verity.2026.REPACK.2160p.WEB-DL.国语版.DDP5.1.Atmos.DV.H265.mkv"
        val episode = parse(original to "movie-uhd").episodes.single()
        assertEquals(1, episode.index)
        val display = episodeCardPresentation(episode)
        assertEquals(original, display.resourceName)
        assertNull(display.number)
        assertEquals("4K·国语版", display.title)
    }

    @Test fun `完整原名经过磁盘缓存往返不会再次被清洗`() {
        val original = "某某剧.S02E12.1080p.WEB-DL.国语中字.完整未删减版本.mp4"
        val route = parse(original to "episode-12")
        val codec = Json { ignoreUnknownKeys = true }
        val encoded = codec.encodeToString(CachedPlayRoute.from(route))
        val restored = codec.decodeFromString<CachedPlayRoute>(encoded).toRoute()
        assertEquals(original, episodeCardPresentation(restored.episodes.single()).resourceName)
        assertEquals("第12集", episodeCardPresentation(restored.episodes.single()).title)
        assertEquals(route.episodes.map(::episodeKey), restored.episodes.map(::episodeKey))
    }

    @Test fun `旧缓存的自动列表前缀不再被误识别为电影集号`() {
        val episode = Episode(2, "02  4K 国语版", "测试线路", "movie-version")
        val display = episodeCardPresentation(episode)
        assertNull(display.number)
        assertEquals("4K·国语版", display.title)
    }

    @Test fun `名称修改保留现有清晰度排序资源标识和索引`() {
        val route = parse(
            "电影.1080p.WEB-DL.国语版.完整版本.mkv" to "movie-hd",
            "电影.2160p.WEB-DL.国语版.完整版本.mkv" to "movie-uhd",
        )
        assertEquals(listOf("movie-uhd", "movie-hd"), route.episodes.map { it.playId })
        assertEquals(listOf(1, 2), route.episodes.map { it.index })
        assertEquals(listOf("测试线路|movie-uhd|1", "测试线路|movie-hd|2"), route.episodes.map(::episodeKey))
        assertTrue(route.episodes.all { it.name.startsWith("%02d  ".format(it.index)) })
    }

    @Test fun `无原名字段的旧JSON缓存仍可读取`() {
        val restored = Json.decodeFromString<CachedEpisode>(
            """{"index":12,"name":"12  第12集","flag":"线路A","playId":"resource-12"}""",
        ).toEpisode()
        assertNull(restored.originalName)
        assertEquals("12  第12集", episodeResourceName(restored))
        assertEquals("第12集", episodeCardPresentation(restored).title)
        assertEquals("线路A|resource-12|12", episodeKey(restored))
    }

    @Test fun `未命名的资源保持未知且不借用列表序号或播放地址`() {
        val episode = parse("" to "https://example.com/video?token=private").episodes.single()
        val display = episodeCardPresentation(episode)
        assertEquals("", episodeResourceName(episode))
        assertEquals("", display.resourceName)
        assertNull(display.number)
        assertNull(display.title)
    }

    @Test fun `超长原名包含特殊字符仍完整保存`() {
        val original = "第十二集【特别篇】" + "超长的原始资源名称，国语&双语字幕".repeat(20) + ".mkv"
        val episode = parse(original to "resource-long").episodes.single()
        assertEquals(original, episode.originalName)
        assertEquals(original, episodeResourceName(episode))
        assertEquals("第12集", episodeCardPresentation(episode).title)
        assertEquals("测试线路|resource-long|1", episodeKey(episode))
    }

    @Test fun `电影续作数字不作为集号且源名可与旧展示名不同`() {
        val episode = Episode(
            index = 3,
            name = "03  1080P",
            flag = "线路A",
            playId = "movie-sequel",
            originalName = "电影续作 2 2026 1080p 国语版.mp4",
        )
        assertNull(episodeCardPresentation(episode).number)
        assertEquals("1080P·国语版", episodeCardPresentation(episode).title)
        assertEquals(episode.originalName, episodeResourceName(episode))
    }
}
