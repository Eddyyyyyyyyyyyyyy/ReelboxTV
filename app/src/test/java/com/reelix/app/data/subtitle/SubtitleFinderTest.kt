package com.reelix.app.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外挂字幕发现测试。
 *
 * ## 为什么必须测
 *
 * 字幕匹配错了，用户看到的是**别的片的字幕** —— 比没有字幕更糟。
 * 而字幕文件命名极其混乱，必须覆盖常见形态。
 */
class SubtitleFinderTest {

    private val video = "沙丘2.mkv"

    private fun find(siblings: List<String>) =
        SubtitleFinder.find(video, siblings) { "https://x.com/$it" }

    @Test
    fun `同名 srt 能发现`() {
        val r = find(listOf("沙丘2.mkv", "沙丘2.srt"))
        assertEquals(1, r.size)
        assertEquals("沙丘2.srt", r[0].name)
    }

    @Test
    fun `语言标签能识别`() {
        val r = find(listOf("沙丘2.chs.srt", "沙丘2.cht.srt", "沙丘2.eng.srt"))
        val langs = r.map { it.lang }.toSet()
        assertTrue("应识别简体: $langs", langs.contains("chs"))
        assertTrue("应识别繁体: $langs", langs.contains("cht"))
        assertTrue("应识别英文: $langs", langs.contains("eng"))
    }

    @Test
    fun `中文优先于英文`() {
        val r = find(listOf("沙丘2.eng.srt", "沙丘2.chs.srt"))
        assertEquals("简体应排第一", "chs", r[0].lang)
    }

    @Test
    fun `强制字幕优先级最高`() {
        val r = find(listOf("沙丘2.chs.srt", "沙丘2.forced.srt"))
        assertEquals("forced", r[0].lang)
    }

    @Test
    fun `多种字幕格式都认`() {
        val r = find(listOf("沙丘2.srt", "沙丘2.ass", "沙丘2.vtt"))
        assertEquals(3, r.size)
    }

    @Test
    fun `不相关文件被排除`() {
        val r = find(listOf("沙丘2.mkv", "另一个片子.srt", "readme.txt", "封面.jpg"))
        assertTrue("不该把别的片字幕算进来: ${r.map { it.name }}", r.isEmpty())
    }

    @Test
    fun `视频文件本身不算字幕`() {
        val r = find(listOf("沙丘2.mkv", "沙丘2.mp4"))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `空输入安全`() {
        assertTrue(SubtitleFinder.find("", listOf("a.srt")) { it }.isEmpty())
        assertTrue(find(emptyList()).isEmpty())
    }

    @Test
    fun `中文标签能识别`() {
        val r = find(listOf("沙丘2.简体.srt"))
        assertEquals("chs", r[0].lang)
    }

    // ---------- 编码解码 ----------

    @Test
    fun `UTF8 正常解码`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\n你好世界\n".toByteArray(Charsets.UTF_8)
        val s = SubtitleFinder.decode(bytes)
        assertTrue("应含中文: $s", s.contains("你好世界"))
    }

    @Test
    fun `带 BOM 的 UTF8 能解`() {
        val raw = "你好".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + raw
        assertEquals("你好", SubtitleFinder.decode(withBom))
    }

    @Test
    fun `GBK 能解`() {
        val bytes = "字幕测试".toByteArray(charset("GBK"))
        val s = SubtitleFinder.decode(bytes)
        assertTrue("GBK 应正确解码: $s", s.contains("字幕"))
    }

    @Test
    fun `空字节安全`() {
        assertEquals("", SubtitleFinder.decode(ByteArray(0)))
    }
}
