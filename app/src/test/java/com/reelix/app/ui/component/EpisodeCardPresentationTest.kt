package com.reelix.app.ui.component

import com.reelix.app.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class EpisodeCardPresentationTest {
    @Test fun `版本入口要求真实清晰度或音轨标记且不能冒充剧集`() {
        assertTrue(hasNamedVersionChoices(listOf(Episode(1, "4K·国语", playId = "a"),
            Episode(2, "1080P·英语", playId = "b"))))
        assertFalse(hasNamedVersionChoices(listOf(Episode(1, "第1集", playId = "a"),
            Episode(2, "第2集", playId = "b"))))
        assertFalse(hasNamedVersionChoices(listOf(Episode(1, "正片", playId = "a"),
            Episode(2, "花絮", playId = "b"))))
    }
    @Test fun `综艺日期加更上下和OAD不会丢失`() {
        val upper = episodeCardPresentation(Episode(1, "", "alist", "/作品/2026.10.17_第2期上_4K_60fps.mp4"))
        val lower = episodeCardPresentation(Episode(1, "", "alist", "/作品/2026.10.17_第2期下_加更.mp4"))
        assertTrue(upper.title!!.contains("上")); assertTrue(upper.title!!.contains("2026.10.17"))
        assertTrue(lower.title!!.contains("下")); assertTrue(lower.title!!.contains("加更"))
        assertEquals("OAD 1", episodeCardPresentation(Episode(1, "", "alist", "/作品/OAD 01.mkv")).title)
        assertNull(episodeCardPresentation(Episode(9, "", "alist", "/作品/2026.10.17.mp4")).number)
        assertEquals("幕后", episodeCardPresentation(Episode(9, "", "alist", "/作品/幕后.mp4")).title)
    }
    @Test fun `真实编号文件可以显示原集号且技术数字不会误认`() {
        listOf("01.mkv" to 1, "片名 01.mkv" to 1, "[01].mkv" to 1,
            "第十二集.mp4" to 12,
            "[Spade4K] Shingeki no Kyojin S3 Pt.2 01 (BD HEVC 3840x2160 FLAC).mkv" to 1,
            "Attack on Titan The Final Season - 17 [BD HEVC 2160P FLAC].mkv" to 17,
            "幕后 2026 60fps 1080p.mp4" to null).forEach { (name, expected) ->
            assertEquals(name, expected, episodeCardPresentation(Episode(8, name, "alist", "/作品/$name")).number)
        }
    }
    @Test fun `网盘卡片用原basename保留综艺区别`() {
        val raw = "2026.10.17_第2期上_4K_60fps.mp4"
        val info = episodeCardPresentation(Episode(7, "2", "alist", "/夸克/现在就出发第四季/$raw"))
        assertEquals(raw, info.resourceName)
        assertEquals(2, info.number)
        assertNotNull(info.resolution)
    }
    @Test fun `在线源不能从URL伪造资源文件名`() {
        val info = episodeCardPresentation(Episode(12, "第12集加更", "online", "https://example/secret?token=private"))
        assertEquals("第12集加更", info.resourceName)
        assertEquals(12, info.number)
        assertNull(info.resolution)
    }
    @Test fun `未知集号的文件不把排列索引冒充原集号`() {
        val info = episodeCardPresentation(Episode(8, "幕后.mp4", "alist", "/作品/幕后.mp4"))
        assertNull(info.number)
        assertNull(info.resolution)
    }
}
