package com.reelix.app.data.source.jar

import com.reelix.app.data.source.TvBoxParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP 解析接口的纯函数部分测试。
 *
 * ## 为什么测这个
 *
 * `parses[]` 是 TVBox 配置里的"快速解析通道"：一次 HTTP ~500ms，
 * 比 WebView 嗅探（3~12s）快一个数量级。但 URL 拼接和响应解析
 * 一旦错了，就会**永远静默失败**然后回退到慢路径 —— 用户只觉得"好慢"。
 *
 * 测试覆盖三种常见解析器 URL 形态和三种响应形态。
 */
class ParseExecutorTest {

    private fun parser(url: String, type: Int = 1, name: String = "测试解析") =
        TvBoxParse(name = name, type = type, url = url)

    private val pageUrl = "https://v.qq.com/x/cover/abc.html"

    // ---------- buildUrl ----------

    @Test
    fun `简单问号结尾直接拼`() {
        val u = ParseExecutor.buildUrl(parser("https://jx.com/?url="), pageUrl)
        assertTrue("应含编码后的地址: $u", u.startsWith("https://jx.com/?url="))
        assertTrue("应包含 v.qq.com", u.contains("v.qq.com") || u.contains("v%2Eqq%2Ecom"))
    }

    @Test
    fun `已有 query 时用 and 追加`() {
        val u = ParseExecutor.buildUrl(parser("https://jx.com/api?key=abc"), pageUrl)
        assertTrue("应用 & 追加: $u", u.contains("key=abc&url="))
    }

    @Test
    fun `没有问号时自动补问号`() {
        val u = ParseExecutor.buildUrl(parser("https://jx.com/jx.php"), pageUrl)
        assertTrue("应自动补 ?url=: $u", u.contains("jx.php?url="))
    }

    @Test
    fun `支持 url 占位符`() {
        val u = ParseExecutor.buildUrl(parser("https://jx.com/p/{url}/play"), pageUrl)
        assertTrue("占位符应被替换: $u", !u.contains("{url}"))
        assertTrue("应包含编码地址: $u", u.contains("v.qq.com") || u.contains("%2F"))
    }

    @Test
    fun `空 url 返回空串`() {
        assertEquals("", ParseExecutor.buildUrl(parser(""), pageUrl))
        assertEquals("", ParseExecutor.buildUrl(parser("   "), pageUrl))
    }

    // ---------- parseResponse ----------

    @Test
    fun `标准形态`() {
        val r = ParseExecutor.parseResponse(
            """{"url":"https://cdn.com/a.m3u8","header":{"Referer":"https://x.com/"}}"""
        )
        assertEquals("https://cdn.com/a.m3u8", r?.first)
        assertEquals("https://x.com/", r?.second?.get("Referer"))
    }

    @Test
    fun `包一层 data`() {
        val r = ParseExecutor.parseResponse(
            """{"code":200,"data":{"url":"https://cdn.com/b.mp4"}}"""
        )
        assertEquals("https://cdn.com/b.mp4", r?.first)
    }

    @Test
    fun `没有 url 字段返回 null`() {
        assertNull(ParseExecutor.parseResponse("""{"code":200,"msg":"ok"}"""))
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(ParseExecutor.parseResponse("<html>not json</html>"))
        assertNull(ParseExecutor.parseResponse(""))
        assertNull(ParseExecutor.parseResponse(null))
    }

    @Test
    fun `url 不是 http 时返回 null`() {
        assertNull(ParseExecutor.parseResponse("""{"url":"blob:xyz"}"""))
    }

    @Test
    fun `没有 header 时返回空 map`() {
        val r = ParseExecutor.parseResponse("""{"url":"https://cdn.com/c.m3u8"}""")
        assertEquals("https://cdn.com/c.m3u8", r?.first)
        assertTrue("header 应为空: ${r?.second}", r?.second?.isEmpty() == true)
    }
}
