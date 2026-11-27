package com.reelix.app.data.source.jar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清晰度排序测试。
 *
 * ## 背景
 *
 * 网盘源的"集"往往是同一部片的多个文件版本，原始顺序是乱的：
 * ```
 * 01  HDR           ← 用户看到这个在最前面，但可能不是最好的
 * 02  1080P
 * 03  4K            ← 这个才是最好的，要排到最前
 * ```
 * 排序错了不影响播放，但用户体验很差 —— 每次都要翻到最后找 4K。
 */
class QualityRankTest {

    private fun vod(from: String, url: String) = Json.parseToJsonElement(
        """{"vod_play_from":"$from","vod_play_url":"$url"}"""
    ).jsonObject

    // ---------- qualityRank 打分 ----------

    @Test
    fun `4K 高于 1080P`() {
        assertTrue(
            TvBoxJsonParser.qualityRank("4K 版本") >
                TvBoxJsonParser.qualityRank("1080P 版本")
        )
    }

    @Test
    fun `1080P 高于 720P`() {
        assertTrue(
            TvBoxJsonParser.qualityRank("1080P") >
                TvBoxJsonParser.qualityRank("720P")
        )
    }

    @Test
    fun `2160P等同于 4K`() {
        assertEquals(
            TvBoxJsonParser.qualityRank("2160P"),
            TvBoxJsonParser.qualityRank("4K")
        )
    }

    @Test
    fun `HDR 有加分`() {
        assertTrue(
            TvBoxJsonParser.qualityRank("4K HDR") >
                TvBoxJsonParser.qualityRank("4K")
        )
    }

    @Test
    fun `原盘加分最多`() {
        assertTrue(
            TvBoxJsonParser.qualityRank("4K 原盘") >
                TvBoxJsonParser.qualityRank("4K HDR")
        )
    }

    @Test
    fun `标清扣分`() {
        assertTrue(
            TvBoxJsonParser.qualityRank("标清") <
                TvBoxJsonParser.qualityRank("普通")
        )
    }

    @Test
    fun `中文原画等同于高分辨率`() {
        assertTrue(TvBoxJsonParser.qualityRank("超清") > 0)
        assertTrue(TvBoxJsonParser.qualityRank("原画") > 0)
    }

    @Test
    fun `空字符串返回 0`() {
        assertEquals(0, TvBoxJsonParser.qualityRank(""))
    }

    // ---------- 排序结果 ----------

    @Test
    fun `解析后按清晰度从高到低排`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod(
                "线路A",
                "720P\$http://a/1.mp4#4K\$http://a/2.mp4#1080P\$http://a/3.mp4"
            )
        )
        val names = routes[0].episodes.map { it.name }

        assertEquals("应有 3 项", 3, names.size)
        assertTrue("4K 应在第一: $names", names[0].contains("4K"))
        assertTrue("1080P 应在第二: $names", names[1].contains("1080P"))
        assertTrue("720P 应在最后: $names", names[2].contains("720P"))
    }

    @Test
    fun `排序后重新编号`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("A", "720P\$http://a/1.mp4#4K\$http://a/2.mp4")
        )
        val eps = routes[0].episodes

        assertTrue("第一项应是 01 开头: ${eps[0].name}", eps[0].name.startsWith("01"))
        assertTrue("第二项应是 02 开头: ${eps[1].name}", eps[1].name.startsWith("02"))
        assertEquals("index 应重排", 1, eps[0].index)
        assertEquals("index 应重排", 2, eps[1].index)
    }

    @Test
    fun `排序不丢内容`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("A", "720P\$http://a/1.mp4#4K\$http://a/2.mp4#1080P\$http://a/3.mp4")
        )
        val ids = routes[0].episodes.map { it.playId }.toSet()

        assertEquals(3, ids.size)
        assertTrue(ids.contains("http://a/1.mp4"))
        assertTrue(ids.contains("http://a/2.mp4"))
        assertTrue(ids.contains("http://a/3.mp4"))
    }

    @Test
    fun `同样清晰度保持原顺序`() {
        val routes = TvBoxJsonParser.parsePlayRoutes(
            vod("A", "版本一\$http://a/1.mp4#版本二\$http://a/2.mp4")
        )
        val eps = routes[0].episodes
        assertEquals("http://a/1.mp4", eps[0].playId)
        assertEquals("http://a/2.mp4", eps[1].playId)
    }
}
