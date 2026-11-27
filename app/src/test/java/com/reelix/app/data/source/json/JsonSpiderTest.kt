package com.reelix.app.data.source.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * JSON 型站点（type=1）的可测部分。
 *
 * ## 为什么这些必须测
 *
 * type=1 源是纯 HTTP 接口，没有 jar、没有解密、没有 WebView ——
 * 是**最容易跑通**的一类源。但两个地方极易出错，而且错了会静默失败：
 *
 * 1. **URL 拼接**：接口地址可能已带 `?`，参数可能已存在
 * 2. **gzip 解压**：老接口常返回 gzip 字节，解不对就是乱码 → JSON 解析全 null
 *
 * 两者都不会抛异常，只会让"这个源没内容"，极难定位。
 */
class JsonSpiderTest {

    private fun spider(api: String = "https://api.example.com/vod") =
        JsonSpider(api = api, key = "test", ext = null, name = "测试源", searchable = true)

    // ---------- buildUrl ----------

    @Test
    fun `裸地址补问号`() {
        assertEquals(
            "https://api.example.com/vod?ac=videolist",
            spider().buildUrl("https://api.example.com/vod", "ac=videolist")
        )
    }

    @Test
    fun `已有 query 用 and 追加`() {
        assertEquals(
            "https://api.example.com/vod?key=abc&ac=videolist",
            spider().buildUrl("https://api.example.com/vod?key=abc", "ac=videolist")
        )
    }

    @Test
    fun `以问号结尾直接拼`() {
        assertEquals(
            "https://api.example.com/vod?ac=videolist",
            spider().buildUrl("https://api.example.com/vod?", "ac=videolist")
        )
    }

    @Test
    fun `空地址返回 null`() {
        assertNull(spider().buildUrl("", "ac=videolist"))
        assertNull(spider().buildUrl("   ", "ac=videolist"))
    }

    @Test
    fun `前后空格被裁掉`() {
        assertEquals(
            "https://a.com/api?ac=x",
            spider().buildUrl("  https://a.com/api  ", "ac=x")
        )
    }

    // ---------- gzip ----------

    @Test
    fun `普通 UTF8 原文直接返回`() {
        val text = """{"list":[{"vod_name":"妖神记"}]}"""
        assertEquals(text, spider().decodeBodyForTest(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `gzip 内容能正确解压`() {
        val text = """{"list":[{"vod_name":"三体","vod_id":"1"}]}"""
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val gz = out.toByteArray()

        // 确认真的是 gzip magic
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])

        val decoded = spider().decodeBodyForTest(gz)
        assertEquals(text, decoded)
        assertTrue("中文应完整: $decoded", decoded.contains("三体"))
    }

    @Test
    fun `空字节安全`() {
        assertEquals("", spider().decodeBodyForTest(ByteArray(0)))
    }

    @Test
    fun `单字节安全`() {
        assertEquals("a", spider().decodeBodyForTest("a".toByteArray()))
    }

    @Test
    fun `坏的 gzip 不至于崩`() {
        // magic 对但内容坏 → 应该回退成原文而不是抛
        val bad = byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x01, 0x02)
        val r = spider().decodeBodyForTest(bad)
        assertNotNull(r)
    }

    // ---------- Spider 接口属性 ----------

    @Test
    fun `key name searchable 正确暴露`() {
        val s = JsonSpider(
            api = "https://x.com/api",
            key = "mysite",
            ext = null,
            name = "我的源",
            searchable = true,
        )
        assertEquals("mysite", s.key)
        assertEquals("我的源", s.name)
        assertTrue(s.searchable)
    }
}
