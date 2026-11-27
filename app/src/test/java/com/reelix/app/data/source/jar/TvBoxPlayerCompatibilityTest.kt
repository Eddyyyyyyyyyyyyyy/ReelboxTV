package com.reelix.app.data.source.jar

import org.junit.Assert.*
import org.junit.Test

class TvBoxPlayerCompatibilityTest {
    @Test fun `空播放地址仍保留源返回的错误`() {
        val parsed = TvBoxJsonParser.parsePlayer("""{"parse":0,"url":"","msg":"文件已失效"}""")
        assertNotNull(parsed)
        assertEquals("", parsed?.url)
        assertEquals("文件已失效", parsed?.msg)
    }

    @Test fun `字符串形式的请求头不会静默丢失`() {
        val parsed = TvBoxJsonParser.parsePlayer(
            """{"parse":0,"url":"https://cdn.example/media","header":"{\"Referer\":\"https://source.example/\",\"User-Agent\":\"SourceUA\"}"}""",
        )
        assertEquals("https://source.example/", parsed?.headers?.get("Referer"))
        assertEquals("SourceUA", parsed?.headers?.get("User-Agent"))
    }

    @Test fun `嵌套解析响应保留顶层和资源层的请求头`() {
        val result = ParseExecutor.parseResponse(
            """{"header":{"Referer":"https://source.example/"},"data":{"url":"https://cdn.example/a.mp4","header":{"Cookie":"fixture=1"}}}""",
        )
        assertEquals(mapOf("Referer" to "https://source.example/", "Cookie" to "fixture=1"), result?.second)
    }

    @Test fun `格式错误的单个解析响应不会抛出异常`() {
        assertNull(ParseExecutor.parseResponse("""{"url":{"bad":"value"}}"""))
    }

    @Test fun `普通地址中的at符号不会被当作请求头截断`() {
        val url = "https://cdn.example/a.mp4?account=user@example.com"
        val split = UrlHeader.split(url)
        assertEquals(url, split.url)
        assertTrue(split.headers.isEmpty())
    }

    @Test fun `at符号存在时仍能识别真正的请求头后缀`() {
        val url = "https://cdn.example/a.mp4?account=user@example.com@Referer=https://source.example/"
        val split = UrlHeader.split(url)
        assertEquals("https://cdn.example/a.mp4?account=user@example.com", split.url)
        assertEquals("https://source.example/", split.headers["Referer"])
    }
}
