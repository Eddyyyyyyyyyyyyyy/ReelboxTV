package com.reelix.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileNameParser 的回归测试。
 *
 * 为什么这个文件最该先有测试：
 *  片库质量 = 解析质量。解析错了，UI 做得再漂亮也没用（用户看到 38 个独立条目）。
 *  这套逻辑有上百个真实文件名的分支，靠人眼在电视上一个个试根本不可能覆盖。
 *
 * 测试里的文件名全部来自你片库里的真实样本。
 * 断言里对 title 用"包含"而不是"等于"，是因为片名的清理规则会演进，
 * 但"该出现的出现、不该出现的不出现"这条底线不能破。
 */
class FileNameParserTest {

    // ---------- 剧集：标准 SxxExx ----------

    @Test
    fun `标准 SxxExx 英文名`() {
        val p = FileNameParser.parse(
            "白莲花度假村.The.White.Lotus.S02E03.2160p.WEB-DL.H265.DDP5.1.mkv"
        )
        assertEquals(2, p.season)
        assertEquals(3, p.episode)
        assertEquals("2160p", p.resolution?.lowercase())
        assertTrue("片名应保留英文名，实际=${p.title}", p.title.contains("White", true))
        assertTrue("片名不该含分辨率，实际=${p.title}", !p.title.contains("2160p", true))
        assertTrue("片名不该含 H265，实际=${p.title}", !p.title.contains("H265", true))
        assertTrue("片名不该含 DDP，实际=${p.title}", !p.title.contains("DDP", true))
        assertTrue("片名不该含孤立数字，实际=${p.title}", p.title.split(' ').none { w -> w.all(Char::isDigit) })
        assertTrue(p.isSeries)
        assertTrue(p.confidence >= 0.7f)
    }

    @Test
    fun `紧凑写法 S01E350`() {
        val p = FileNameParser.parse("火 东丨S01E350.mp4")
        assertEquals(1, p.season)
        assertEquals(350, p.episode)
    }

    @Test
    fun `小写与点分隔 s01 e02`() {
        val p = FileNameParser.parse("some.show.s01.e02.720p.mkv")
        assertEquals(1, p.season)
        assertEquals(2, p.episode)
        assertEquals("720p", p.resolution?.lowercase())
    }

    @Test
    fun `连集 S01E01E02 应记录结束集`() {
        val p = FileNameParser.parse("Some.Show.S01E01E02.1080p.mkv")
        assertEquals(1, p.episode)
        assertEquals(2, p.endEpisode)
    }

    // ---------- 剧集：中文集号 ----------

    @Test
    fun `第X话 无片名时要给出可用片名而不是扩展名`() {
        val p = FileNameParser.parse("第93话.mp4")
        assertEquals(93, p.episode)
        assertFalse("不该带扩展名，实际=${p.title}", p.title.contains("mp4"))
        assertFalse("不该是空白", p.title.isBlank())
    }

    @Test
    fun `第X集 带片名`() {
        val p = FileNameParser.parse("三体 第一季 第07集.mp4")
        assertEquals(1, p.season)
        assertEquals(7, p.episode)
        assertTrue("片名应含三体，实际=${p.title}", p.title.contains("三体"))
    }

    @Test
    fun `第X期 综艺 上下分段后缀要被吃掉`() {
        val p = FileNameParser.parse("现在就出发第四季 第2期上.mp4")
        assertEquals(4, p.season)
        assertEquals(2, p.episode)
        assertFalse("不该残留上下，实际=${p.title}", p.title.endsWith("上") || p.title.endsWith("下"))
    }

    @Test
    fun `中文数字季`() {
        val p = FileNameParser.parse("庆余年 第二季 第03集.mkv")
        assertEquals(2, p.season)
        assertEquals(3, p.episode)
    }

    // ---------- 电影 ----------

    @Test
    fun `电影 片名加年份`() {
        val p = FileNameParser.parse(
            "流浪地球2.The.Wandering.Earth.II.2023.1080p.BluRay.x264.mkv"
        )
        assertEquals(2023, p.year)
        assertEquals("1080p", p.resolution?.lowercase())
        assertTrue("片名应含中文，实际=${p.title}", p.title.any { it.code > 0x4E00 })
        assertFalse(p.isSeries)
        assertTrue("不该含 x264，实际=${p.title}", !p.title.contains("x264", true))
    }

    @Test
    fun `电影 无年份纯外文`() {
        val p = FileNameParser.parse("Oppenheimer.2023.2160p.WEB-DL.mkv")
        assertEquals(2023, p.year)
        assertTrue(p.title.contains("Oppenheimer", true))
        // 回归点：早期版本这里会是 "WEB DL"（片名被整块吃掉）
        assertFalse("不该残留 WEB-DL，实际=${p.title}", p.title.contains("WEB", true))
    }

    @Test
    fun `方括号字幕组前缀应被剔除`() {
        val p = FileNameParser.parse("[高清影视之家][三体][01][2160p].mkv")
        assertTrue("片名应含三体，实际=${p.title}", p.title.contains("三体"))
        assertFalse("不应含字幕组名，实际=${p.title}", p.title.contains("高清影视之家"))
        assertFalse("不应含分辨率，实际=${p.title}", p.title.contains("2160p", true))
    }

    // ---------- 边界与鲁棒性 ----------

    @Test
    fun `带全路径只取文件名`() {
        val p = FileNameParser.parse("/115/我不是大师/我不是大师.S01E01.mkv")
        assertEquals(1, p.season)
        assertEquals(1, p.episode)
        assertTrue(p.title.contains("我不是大师"))
    }

    @Test
    fun `Windows 反斜杠路径`() {
        val p = FileNameParser.parse("D:\\片库\\风林火山.mkv")
        assertTrue("片名应含风林火山，实际=${p.title}", p.title.contains("风林火山"))
    }

    @Test
    fun `空字符串不崩`() {
        val p = FileNameParser.parse("")
        assertEquals("", p.title.trim())
        assertNull(p.episode)
    }

    @Test
    fun `纯数字文件名不给高置信度`() {
        val p = FileNameParser.parse("12345.mp4")
        assertTrue("纯数字片名应低置信度，实际=${p.confidence}", p.confidence < 0.5f)
    }

    @Test
    fun `分辨率提取覆盖 4K 写法`() {
        val p = FileNameParser.parse("某电影 2024 4K HDR.mkv")
        assertEquals("4k", p.resolution?.lowercase())
    }

    // ---------- parseBatch ----------

    @Test
    fun `批量解析取众数片名并按集号排序`() {
        val files = listOf(
            "与晋长安 第03集.mkv",
            "与晋长安 第01集.mkv",
            "与晋长安 第02集.mkv",
        )
        val batch = FileNameParser.parseBatch(files)
        assertTrue("片名应为与晋长安，实际=${batch.title}", batch.title.contains("与晋长安"))
        assertEquals(3, batch.files.size)
        assertEquals(listOf(1, 2, 3), batch.files.map { it.episode })
    }

    @Test
    fun `批量解析空输入返回空结构`() {
        val batch = FileNameParser.parseBatch(emptyList())
        assertEquals("", batch.title)
        assertTrue(batch.files.isEmpty())
    }
}
