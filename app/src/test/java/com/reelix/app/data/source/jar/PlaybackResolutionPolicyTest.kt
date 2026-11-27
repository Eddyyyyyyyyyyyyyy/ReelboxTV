package com.reelix.app.data.source.jar

import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.TvBoxParse
import org.junit.Assert.*
import org.junit.Test

class PlaybackResolutionPolicyTest {
    @Test fun `源声明无后缀直链时不再先走网页解析`() {
        assertTrue(isDirectPlayback(PlayResolution("https://cdn.example/video/opaque", direct = true)))
    }

    @Test fun `源明确要求解析时不因后缀看似媒体而绕过`() {
        assertFalse(isDirectPlayback(PlayResolution("https://source.example/a.m3u8",
            direct = false, requiresParsing = true)))
        assertTrue(isDirectPlayback(PlayResolution("https://cdn.example/a.mp4", direct = false)))
    }

    @Test fun `源的playUrl前缀优先保留原始地址`() {
        val resolution = PlayResolution("https://source.example/a?id=2", direct = false,
            playUrl = "https://parser.example/?url=")
        val requests = playbackWebRequests(resolution, emptyList(), "线路")
        assertEquals("https://parser.example/?url=https://source.example/a?id=2", requests.first().url)
        assertEquals(resolution.url, requests.last().url)
        assertFalse(isDirectPlayback(resolution.copy(direct = true)))
    }

    @Test fun `网页解析入口遵守线路白名单且不外传媒体Cookie`() {
        val parsers = listOf(
            TvBoxParse("不匹配", 0, "https://wrong.example/?url=", flag = listOf("其他线路")),
            TvBoxParse("匹配", 0, "https://parser.example/?url=",
                ext = """{"header":{"Referer":"https://parser.example/"}}""", flag = listOf("线路")),
            TvBoxParse("JSON", 1, "https://json.example/?url="),
        )
        val requests = playbackWebRequests(PlayResolution("https://source.example/page",
            headers = mapOf("Cookie" to "source=private"), direct = false), parsers, "线路")
        assertEquals(2, requests.size)
        assertTrue(requests.first().url.startsWith("https://parser.example/"))
        assertNull(requests.first().headers["Cookie"])
        assertEquals("source=private", requests.last().headers["Cookie"])
    }

    @Test fun `解析器扩展请求头覆盖默认UA且兼容字符串头`() {
        val parser = TvBoxParse("解析", 1, "https://parser.example", ua = "DefaultUA",
            ext = """{"header":"{\"user-agent\":\"ParserUA\",\"Cookie\":\"parser=1\"}"}""")
        val headers = parserRequestHeaders(parser)
        assertEquals("ParserUA", headers["user-agent"])
        assertFalse(headers.containsKey("User-Agent"))
        assertEquals("parser=1", headers["Cookie"])
    }

    @Test fun `playUrl和空地址错误分别保留`() {
        val parsed = TvBoxJsonParser.parsePlayer(
            """{"parse":1,"playUrl":"https://parser.example/?url=","url":"https://source.example/page"}""",
        )
        assertEquals("https://parser.example/?url=", parsed?.playUrl)
        assertFalse(parsed!!.direct)
        assertEquals("https://source.example/page", parsed.url)
    }

    @Test fun `合并头字段忽略大小写且保持最新值`() {
        val headers = combinePlaybackHeaders(mapOf("Referer" to "old", "Cookie" to "a=1"),
            mapOf("referer" to "new"))
        assertEquals(mapOf("Cookie" to "a=1", "referer" to "new"), headers)
    }
}
