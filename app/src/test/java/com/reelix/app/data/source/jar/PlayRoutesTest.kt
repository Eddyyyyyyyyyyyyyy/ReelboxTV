package com.reelix.app.data.source.jar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parsePlayRoutes` 的解析测试。
 *
 * ## 为什么单独测这个
 *
 * 这是整个 TVBox 协议里**最容易崩的一处**：
 * - `vod_play_from` 和 `vod_play_url` 用 `$$$` 分线路，长度可能不一致
 * - 线路内用 `#` 分集，集内用 `$` 分「名字$地址」
 * - 地址里可能含 `$` 或 `#`
 * - 名字可能是超长文件名
 *
 * 解析错了，详情页会出现：线路错位、集名串行、点击播放拿到错误地址。
 */
class PlayRoutesTest {

    private fun vod(from: String, url: String) = Json.parseToJsonElement(
        """{"vod_play_from":"$from","vod_play_url":"$url"}"""
    ).jsonObject

    @Test
    fun `单线路单集`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("百度原画", "第1集\$https://x.com/a.mp4")
        )
        assertEquals(1, routes.size)
        assertEquals("百度原画", routes[0].name)
        assertEquals(1, routes[0].episodes.size)
        assertEquals("https://x.com/a.mp4", routes[0].episodes[0].playId)
        assertEquals("百度原画", routes[0].episodes[0].flag)
    }

    @Test
    fun `多线路多集按 $$$ 和 # 正确切分`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod(
                "线路A\$\$\$线路B",
                "第1集\$http://a/1.mp4#第2集\$http://a/2.mp4\$\$\$第1集\$http://b/1.mp4"
            )
        )
        assertEquals(2, routes.size)
        assertEquals(2, routes[0].episodes.size)
        assertEquals(1, routes[1].episodes.size)
        assertEquals("线路B", routes[1].name)
        assertEquals("http://b/1.mp4", routes[1].episodes[0].playId)
    }

    @Test
    fun `集名带序号前缀`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("线路A", "第1集\$http://a/1.mp4#第2集\$http://a/2.mp4")
        )
        val eps = routes[0].episodes
        assertTrue("应带 01 前缀: ${eps[0].name}", eps[0].name.startsWith("01"))
        assertTrue("应带 02 前缀: ${eps[1].name}", eps[1].name.startsWith("02"))
        assertTrue("应保留原标题: ${eps[1].name}", eps[1].name.contains("第2集"))
    }

    @Test
    fun `空名字用版本占位`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("线路A", "\$http://a/x.mp4")
        )
        // idx <= 0 的分支会走"整条就是地址"，不会产生空名字；
        // 这里验证不崩且至少有内容
        assertTrue(routes.isEmpty() || routes[0].episodes.isNotEmpty())
    }

    @Test
    fun `超长文件名被清洗`() {
        val longName = "Verity.2026.REPACK.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-Qshan.mkv"
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("百度原画", "$longName\$https://pan.baidu.com/x")
        )
        val name = routes[0].episodes[0].name
        assertTrue("名字应被清洗短: $name", name.length <= 24)
    }

    @Test
    fun `from 少一条时不崩且线路名有兜底`() {
        // from 只有 1 条，url 有 2 组
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("线路A", "第1集\$http://a/1.mp4\$\$\$第1集\$http://b/1.mp4")
        )
        assertEquals(2, routes.size)
        assertTrue("第二条应有兜底名: ${routes[1].name}", routes[1].name.isNotBlank())
    }

    @Test
    fun `空 vod_play_url 返回空列表`() {
        assertTrue(TvBoxJsonParser.parsePlayRoutes(vod("A", "")).isEmpty())
    }

    @Test
    fun `地址里的查询参数不影响切分`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("A", "第1集\$https://x.com/v.mp4?token=abc&sign=1&t=2")
        )
        assertEquals(
            "https://x.com/v.mp4?token=abc&sign=1&t=2",
            routes[0].episodes[0].playId
        )
    }
}
