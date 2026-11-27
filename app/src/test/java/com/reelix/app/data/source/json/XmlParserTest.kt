package com.reelix.app.data.source.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XML 源（type=0）解析测试。
 *
 * ## 为什么用真实样本
 *
 * XML 接口的字段名和 JSON 版不同（`<des>` 而不是 `vod_content`，
 * `<dl><dd flag="...">` 而不是 `vod_play_from`/`vod_play_url`），
 * 手写假数据容易"测通了但真实数据崩"。
 *
 * `sample_vod.xml` 是从 `cj.lziapi.com` 真实抓取的前 3 条，
 * 保留了原始 CDATA、嵌套 `<p>` 简介、两条 flag 线路。
 */
class XmlParserTest {

    private val sample: String by lazy {
        javaClass.classLoader!!
            .getResourceAsStream("sample_vod.xml")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    @Test
    fun `能解析出条目`() {
        val c = XmlParser.parseCategory(sample, "lzi")
        assertEquals("应有 3 条", 3, c.items.size)
    }

    @Test
    fun `基本字段正确`() {
        val first = XmlParser.parseCategory(sample, "lzi").items.first()
        assertEquals("最后一桌", first.title)
        assertEquals("lzi_163386", first.id)
        assertEquals("163386", first.sourceId)
        assertEquals("lzi", first.sourceKey)
        assertEquals(2026, first.year)
        assertEquals("韩国", first.area)
        assertEquals("韩语", first.language)
    }

    @Test
    fun `海报地址被提取`() {
        val first = XmlParser.parseCategory(sample, "lzi").items.first()
        assertNotNull(first.poster)
        assertTrue("海报应是 http 地址: ${first.poster}", first.poster!!.startsWith("http"))
    }

    @Test
    fun `简介的 HTML 被剥掉`() {
        val first = XmlParser.parseCategory(sample, "lzi").items.first()
        val ov = first.overview.orEmpty()
        assertTrue("简介不该含标签: $ov", !ov.contains("<p>") && !ov.contains("</"))
        assertTrue("简介应有内容", ov.isNotBlank())
    }

    @Test
    fun `分页信息被读取`() {
        val c = XmlParser.parseCategory(sample, "lzi")
        assertEquals(1, c.page)
        assertEquals(100, c.pageCount)
        assertEquals(2000, c.total)
    }

    @Test
    fun `详情能解析出线路`() {
        val parsed = XmlParser.parseDetail(sample, "lzi")
        assertNotNull(parsed)
        val (item, routes) = parsed!!
        assertEquals("最后一桌", item.title)
        assertTrue("应有线路: ${routes.size}", routes.isNotEmpty())
    }

    @Test
    fun `两条线路都被解析`() {
        val (_, routes) = XmlParser.parseDetail(sample, "lzi")!!
        val names = routes.map { it.name }
        assertTrue("应有 liangzi 线路: $names", names.any { it.contains("liangzi") })
        assertTrue("应有 lzm3u8 线路: $names", names.any { it.contains("lzm3u8") })
    }

    @Test
    fun `线路里的集被正确切分`() {
        val (_, routes) = XmlParser.parseDetail(sample, "lzi")!!
        val m3u8 = routes.firstOrNull { it.name.contains("lzm3u8") }
        assertNotNull("应有 m3u8 线路", m3u8)
        assertTrue("m3u8 线路应有集: ${m3u8!!.episodes.size}", m3u8.episodes.isNotEmpty())

        val ep = m3u8.episodes.first()
        assertTrue("地址应是 m3u8: ${ep.playId}", ep.playId.contains(".m3u8"))
        assertEquals("flag 应保留线路名", "lzm3u8", ep.flag)
    }

    @Test
    fun `share 线路的地址是播放页`() {
        val (_, routes) = XmlParser.parseDetail(sample, "lzi")!!
        val share = routes.firstOrNull { it.name.contains("liangzi") }
        assertNotNull(share)
        assertTrue(
            "share 线路地址应含 /share/: ${share!!.episodes.first().playId}",
            share.episodes.first().playId.contains("/share/")
        )
    }

    @Test
    fun `搜索结构同分类`() {
        val items = XmlParser.parseSearch(sample, "lzi")
        assertEquals(3, items.size)
    }

    @Test
    fun `首页能从 list 推出分类`() {
        val home = XmlParser.parseHome(sample, "lzi")
        assertEquals(3, home.recommendations.size)
        assertTrue("应推出分类: ${home.categories.size}", home.categories.isNotEmpty())
    }

    @Test
    fun `空输入安全`() {
        assertTrue(XmlParser.parseCategory(null, "x").items.isEmpty())
        assertTrue(XmlParser.parseCategory("", "x").items.isEmpty())
        assertTrue(XmlParser.parseCategory("not xml at all", "x").items.isEmpty())
        assertNull(XmlParser.parseDetail(null, "x"))
        assertNull(XmlParser.parseDetail("<rss></rss>", "x"))
    }
}
