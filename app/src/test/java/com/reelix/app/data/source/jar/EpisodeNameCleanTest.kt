package com.reelix.app.data.source.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选集名清洗测试。
 *
 * ## 背景
 *
 * 网盘类源的 `vod_play_url` 里，「集名」往往直接就是文件名：
 * ```
 * [12.75GB] Verity.2026.REPACK.2160p.AMZN.WEB-DL.DDP5.1.Atmos.mkv
 * ```
 * 直接展示会把详情页布局撑爆，用户也看不出这是第几集。
 */
class EpisodeNameCleanTest {

    private val parser = TvBoxJsonParser

    @Test
    fun `中文集名原样保留`() {
        assertEquals("第01集", parser.cleanEpisodeName("第01集"))
        assertEquals("第一话", parser.cleanEpisodeName("第一话"))
        assertEquals("正片", parser.cleanEpisodeName("正片"))
    }

    @Test
    fun `剥掉开头的体积标记`() {
        val r = parser.cleanEpisodeName("[4.06GB] 4k")
        assertFalse(r.contains("4.06GB"))
    }

    @Test
    fun `剥掉扩展名`() {
        val r = parser.cleanEpisodeName("妖神记.mp4")
        assertEquals("妖神记", r)
    }

    @Test
    fun `超长英文名提取清晰度`() {
        val raw = "[12.75GB] Verity.2026.REPACK.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-Qshan.mkv"
        val r = parser.cleanEpisodeName(raw)
        // 应该抽出 2160P 或 HDR / DV 这类有意义的标记
        assertTrue("结果应该短: $r", r.length <= 20)
        assertTrue("应该含清晰度标记: $r", r.contains("2160") || r.contains("HDR") || r.contains("DV") || r.contains("REPACK") || r.contains("2160P"))
    }

    @Test
    fun `能抽出 EP 编号`() {
        val raw = "SomeShow.S01E05.1080p.WEB-DL.AAC.x264-Group.mkv"
        val r = parser.cleanEpisodeName(raw)
        assertTrue("应该含 EP 编号: $r", r.contains("EP") || r.contains("05") || r.contains("1080"))
    }

    @Test
    fun `能抽出第X集`() {
        val raw = "某某剧 第12集 高清版本 完整版"
        val r = parser.cleanEpisodeName(raw)
        assertTrue("应该含第12集: $r", r.contains("第12集"))
    }

    @Test
    fun `空串安全`() {
        assertEquals("", parser.cleanEpisodeName(""))
        assertEquals("", parser.cleanEpisodeName("   "))
    }

    @Test
    fun `短英文名保留`() {
        assertEquals("4k", parser.cleanEpisodeName("4k"))
    }

    @Test
    fun `结果永远不会超长`() {
        val veryLong = "A".repeat(200) + ".mp4"
        val r = parser.cleanEpisodeName(veryLong)
        assertTrue("长度 ${r.length} 应 <= 20", r.length <= 20)
    }
}
