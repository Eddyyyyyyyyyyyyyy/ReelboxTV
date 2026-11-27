package com.reelix.app.data.douban

import com.reelix.app.data.model.MediaKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubanClientTest {

    private fun fixture(name: String): String {
        val roots = listOf(
            "C:/Users/Xixi/Documents/Codex/2026-11-23/https-github-com-pilipala9919-marcomovie-https/outputs/fixtures",
            "C:/Users/Xixi/Documents/Codex/2026-11-23/marco-inspect/outputs/fixtures",
        )
        val file = roots.asSequence().map { java.io.File(it, name) }.firstOrNull { it.isFile }
        return file?.readText() ?: ""
    }

    @Test fun `HTML搜索能解析subject与标题`() {
        val html = fixture("douban-search-html-qingyunian.html")
        if (html.isBlank()) return
        val hits = DoubanClient.parseSearchHtml(html)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.id.matches(Regex("\\d+")) })
        assertTrue(hits.any { it.title.contains("庆余年") })
    }

    @Test fun `subject能解析评分年份与别名`() {
        val body = fixture("douban-subject-movie-35267208.json")
        if (body.isBlank()) return
        val subject = DoubanClient.parseSubject(JSONObject(body), "35267208", "movie")
        assertNotNull(subject)
        assertEquals("流浪地球2", subject!!.title)
        assertEquals(2023, subject.year)
        assertEquals(8.3, subject.rating!!, 0.001)
        assertTrue(subject.aka.any { it.contains("The Wandering Earth") })
        assertTrue(subject.directors.contains("郭帆"))
    }

    @Test fun `别名可用于匹配英文片名`() {
        val body = fixture("douban-subject-movie-35267208.json")
        if (body.isBlank()) return
        val subject = DoubanClient.parseSubject(JSONObject(body), "35267208", "movie")!!
        assertTrue(subject.matches("The Wandering Earth Ⅱ", 2023, MediaKind.MOVIE))
        assertTrue(subject.matches("流浪地球2", 2023, MediaKind.MOVIE))
        assertTrue(!subject.matches("流浪地球", 2019, MediaKind.MOVIE))
    }

    @Test fun `photos只保留横图`() {
        val body = fixture("douban-photos-movie-35267208.json")
        val subjectBody = fixture("douban-subject-movie-35267208.json")
        if (body.isBlank() || subjectBody.isBlank()) return
        val subject = DoubanClient.parseSubject(JSONObject(subjectBody), "35267208", "movie")!!
        val photos = DoubanClient.parsePhotos(JSONObject(body), subject)
        assertTrue(photos.isNotEmpty())
        assertTrue(photos.all { it.backdrop?.startsWith("https://") == true })
    }

    @Test fun `credits能解析演员头像与角色`() {
        val body = fixture("douban-credits-tv-25853071.json")
        if (body.isBlank()) return
        val credits = DoubanClient.parseCredits(JSONObject(body))
        assertTrue(credits.isNotEmpty())
        assertTrue(credits.any { !it.avatar.isNullOrBlank() })
        assertTrue(credits.any { it.name == "张若昀" })
    }

    @Test fun `搜索封面必须去掉签名query`() {
        val html = fixture("douban-search-html-qingyunian.html")
        if (html.isBlank()) return
        val hits = DoubanClient.parseSearchHtml(html)
        assertTrue(hits.all { it.poster == null || !it.poster.contains("sa_cv") })
    }

    @Test fun `占位图不会进入候选`() {
        val html = "<a href=\"/movie/subject/1/\"><span class=\"subject-title\">X</span>" +
            "<img src=\"https://img1.doubanio.com/asset.doubanio.com/cuphead/movie-static/pics/tv_default_large.png\"></a>"
        val hits = DoubanClient.parseSearchHtml(html)
        assertEquals(1, hits.size)
        assertNull(hits.first().poster)
    }
}
