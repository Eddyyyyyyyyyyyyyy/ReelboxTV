package com.reelix.app.data.source.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嗅探判定规则测试。
 *
 * ## 为什么这些必须测
 *
 * 判定错了会直接导致播放失败，而且**很难在真机上定位**：
 * - 把播放页当媒体 → `ERROR_CODE_IO_UNSPECIFIED`，看起来像网络问题
 * - 把媒体当播放页 → 白等 12 秒嗅探，最后还是失败
 * - 候选选错 → 抓到花絮/广告的 m3u8 而不是正片
 *
 * 所以规则层必须是纯函数 + 全覆盖测试。
 */
class SniffRulesTest {

    // ---------- looksLikeMedia ----------

    @Test
    fun `认得常见媒体扩展名`() {
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.m3u8"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.mp4"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.mkv"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.flv"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.ts"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.mpd"))
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertTrue(SniffRules.looksLikeMedia("https://x.com/A.M3U8"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/A.MP4"))
    }

    @Test
    fun `带查询参数仍识别`() {
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.m3u8?token=abc&t=1"))
        assertTrue(SniffRules.looksLikeMedia("https://x.com/v.m4v?sign=xyz#t=10"))
    }

    @Test
    fun `带 TVBox 头后缀仍识别`() {
        assertTrue(SniffRules.looksLikeMedia("https://x.com/a.mp4@Referer=https://s.com/"))
    }

    @Test
    fun `播放页不是媒体`() {
        assertFalse(SniffRules.looksLikeMedia("https://x.com/vodplay/123-1-1.html"))
        assertFalse(SniffRules.looksLikeMedia("https://vip.ffzy-plays.com/share/abc"))
        assertFalse(SniffRules.looksLikeMedia("https://x.com/play/123"))
    }

    @Test
    fun `空值和异常输入安全`() {
        assertFalse(SniffRules.looksLikeMedia(null))
        assertFalse(SniffRules.looksLikeMedia(""))
        assertFalse(SniffRules.looksLikeMedia("   "))
        assertFalse(SniffRules.looksLikeMedia("https://x.com/a."))
        assertFalse(SniffRules.looksLikeMedia("no-dot-here"))
    }

    // ---------- isHls ----------

    @Test
    fun `识别 m3u8`() {
        assertTrue(SniffRules.isHls("https://x.com/a.m3u8"))
        assertTrue(SniffRules.isHls("https://x.com/a.m3u8?token=1"))
        assertFalse(SniffRules.isHls("https://x.com/a.mp4"))
        assertFalse(SniffRules.isHls(null))
    }

    // ---------- isPanSharePage ----------

    @Test
    fun `识别网盘分享页`() {
        assertTrue(SniffRules.isPanSharePage("https://pan.quark.cn/s/abcdef"))
        assertTrue(SniffRules.isPanSharePage("https://pan.baidu.com/s/1abc"))
        assertTrue(SniffRules.isPanSharePage("https://www.aliyundrive.com/s/xyz"))
        assertTrue(SniffRules.isPanSharePage("https://115.com/s/abc"))
    }

    @Test
    fun `普通页面不是网盘分享`() {
        assertFalse(SniffRules.isPanSharePage("https://v.qq.com/x/cover/abc.html"))
        assertFalse(SniffRules.isPanSharePage("https://x.com/a.m3u8"))
        assertFalse(SniffRules.isPanSharePage(null))
    }

    // ---------- pickBest ----------

    @Test
    fun `m3u8 优先于 mp4`() {
        val best = SniffRules.pickBest(
            listOf("https://x.com/a.mp4", "https://x.com/b.m3u8")
        )
        assertEquals("https://x.com/b.m3u8", best)
    }

    @Test
    fun `没有 m3u8 时选 mp4`() {
        val best = SniffRules.pickBest(
            listOf("https://x.com/a.flv", "https://x.com/b.mp4")
        )
        assertEquals("https://x.com/b.mp4", best)
    }

    @Test
    fun `重复候选去重`() {
        val best = SniffRules.pickBest(
            listOf("https://x.com/a.m3u8", "https://x.com/a.m3u8")
        )
        assertEquals("https://x.com/a.m3u8", best)
    }

    @Test
    fun `非 http 候选被过滤`() {
        val best = SniffRules.pickBest(
            listOf("blob:https://x.com/abc", "data:text/plain,x", "https://x.com/real.m3u8")
        )
        assertEquals("https://x.com/real.m3u8", best)
    }

    @Test
    fun `全是非媒体时返回 null`() {
        assertNull(SniffRules.pickBest(listOf("https://x.com/page.html")))
        assertNull(SniffRules.pickBest(emptyList()))
        assertNull(SniffRules.pickBest(listOf("")))
    }

    @Test
    fun `保持原始顺序取第一个 m3u8`() {
        val best = SniffRules.pickBest(
            listOf("https://x.com/first.m3u8", "https://x.com/second.m3u8")
        )
        assertEquals("https://x.com/first.m3u8", best)
    }
}
