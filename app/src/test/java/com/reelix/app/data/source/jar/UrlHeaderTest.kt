package com.reelix.app.data.source.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `UrlHeader` 单元测试。
 *
 * ## 为什么这个要测
 *
 * TVBox 的 `@头` 约定是整个生态的通用规则，一旦解析错了：
 * - 海报 → 整串当 URL，404，界面全黑
 * - 播放 → ExoPlayer 拿到带 `@Referer=...` 的地址，连不上
 *
 * 而且边界很容易踩：URL 里本身可能就有 `@`（查询参数、邮箱），
 * 不能无脑按 `@` 切。
 */
class UrlHeaderTest {

    @Test
    fun `纯 URL 不受影响`() {
        val p = UrlHeader.split("https://cdn.example.com/a.mp4")
        assertEquals("https://cdn.example.com/a.mp4", p.url)
        assertTrue(p.headers.isEmpty())
    }

    @Test
    fun `单个 Referer 头`() {
        val p = UrlHeader.split(
            "https://img.example.com/poster/x.jpg@Referer=https://example.com/"
        )
        assertEquals("https://img.example.com/poster/x.jpg", p.url)
        assertEquals("https://example.com/", p.headers["Referer"])
    }

    @Test
    fun `多个头用 @ 分隔`() {
        val p = UrlHeader.split(
            "https://a.com/x.mp4@Referer=https://s.com/@User-Agent=Mozilla/5.0"
        )
        assertEquals("https://a.com/x.mp4", p.url)
        assertEquals("https://s.com/", p.headers["Referer"])
        assertEquals("Mozilla/5.0", p.headers["User-Agent"])
    }

    @Test
    fun `第一段不是 http 时不当头解析`() {
        // 比如查询参数里带邮箱
        val raw = "user@example.com"
        val p = UrlHeader.split(raw)
        assertEquals(raw, p.url)
        assertTrue(p.headers.isEmpty())
    }

    @Test
    fun `相对路径原样返回`() {
        val p = UrlHeader.split("/upload/vod/20240101/x.jpg")
        assertEquals("/upload/vod/20240101/x.jpg", p.url)
        assertTrue(p.headers.isEmpty())
    }

    @Test
    fun `空串安全`() {
        assertEquals("", UrlHeader.split("").url)
        assertEquals("", UrlHeader.split(null).url)
    }

    @Test
    fun `头里的值可以带等号`() {
        val p = UrlHeader.split("https://a.com/v.mp4@Cookie=a=1;b=2")
        assertEquals("https://a.com/v.mp4", p.url)
        assertEquals("a=1;b=2", p.headers["Cookie"])
    }

    // ---------- looksLikeMedia ----------

    @Test
    fun `识别常见媒体扩展名`() {
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/a.m3u8"))
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/a.MP4"))
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/a.mkv"))
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/a.ts"))
    }

    @Test
    fun `带查询参数也能识别`() {
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/a.m3u8?token=abc&t=1"))
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/v/a.mp4?sign=xyz"))
    }

    @Test
    fun `带 @头的媒体地址也能识别`() {
        assertTrue(UrlHeader.looksLikeMedia("https://x.com/a.mp4@Referer=https://s.com/"))
    }

    @Test
    fun `网页地址不是媒体`() {
        assertFalse(UrlHeader.looksLikeMedia("https://v.qq.com/x/cover/abc.html"))
        assertFalse(UrlHeader.looksLikeMedia("https://pan.quark.cn/s/xxxx"))
        assertFalse(UrlHeader.looksLikeMedia(""))
        assertFalse(UrlHeader.looksLikeMedia(null))
    }
}
