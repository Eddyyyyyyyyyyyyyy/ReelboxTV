package com.reelix.app.data.alist

import org.junit.Assert.*
import org.junit.Test

class LibraryDirectoryRegressionTest {
    private fun video(path: String) = AlistVideo(path, path.substringAfterLast('/'), 100L, mountRoot = "/夸克")

    @Test fun `真实巨人九组九十七个文件归顶层一个作品`() {
        val groups = listOf("0.OAD" to 8, "1.Season 1" to 25, "2.Season 2" to 12,
            "3.Season 3 -Part1" to 12, "4.Season 3 -Part2" to 10,
            "5.The Final Season -Part1" to 16, "6.The Final Season -Part2" to 12,
            "7.The Final Chapter -Part1" to 1, "8.The Final Chapter -Part2" to 1)
        val files = groups.flatMap { (folder, count) -> (1..count).map {
            video("/夸克/进击的巨人/进击的巨人 全季+OVA  4K 高码/$folder/episode$it.mkv")
        } }
        val items = AlistLibraryBuilder.build(files).items
        assertEquals(1, items.size)
        assertEquals("进击的巨人", items.single().item.title)
        assertEquals(97, items.single().files.size)
        assertEquals(97, items.single().episodes.map { it.index }.toSet().size)
    }

    @Test fun `排序前缀不是季号且识别终章和特别篇`() {
        listOf("1.Season 1", "0.OAD", "OVA", "5.The Final Season -Part1",
            "8.The Final Chapter -Part2", "第一章", "Chapter 2", "S03").forEach {
            assertTrue(it, DirectoryRules.isVariant(it))
        }
        assertFalse(DirectoryRules.isVariant("1.流浪地球"))
    }

    @Test fun `分类和不同作品包装目录不能被吞并`() {
        val items = AlistLibraryBuilder.build(listOf(
            video("/夸克/动漫/进击的巨人/1.Season 1/e01.mkv"),
            video("/夸克/动漫/三体/1.Season 1/e01.mkv"),
            video("/夸克/电影/作品A/作品B/正片.mkv"),
        )).items
        assertEquals(setOf("进击的巨人", "三体", "作品B"), items.map { it.item.title }.toSet())
        assertEquals(3, items.sumOf { it.files.size })
    }

    @Test fun `分组只显示语义目录且未知内容仍可达`() {
        val entry = AlistLibraryBuilder.build(listOf(
            video("/夸克/巨人/1.Season 1/e01.mkv"), video("/夸克/巨人/5.The Final Season -Part1/e01.mkv"),
            video("/夸克/巨人/7.The Final Chapter -Part1/e01.mkv"), video("/夸克/巨人/0.OAD/e01.mkv"),
            video("/夸克/巨人/未知/花絮.mkv")
        ), mapOf("/夸克/巨人" to DirectoryMode.WORK)).items.single()
        val groups = librarySections(entry.directory, entry.episodes)
        assertEquals(5, groups.size)
        assertEquals(entry.episodes.map { it.playId }.toSet(), groups.flatMap { it.paths }.toSet())
        assertEquals(LibrarySectionTag(LibrarySectionKind.FINAL_SEASON, part = "1"),
            groups.single { it.tags.first().kind == LibrarySectionKind.FINAL_SEASON }.tags.single())
        assertTrue(librarySections("/夸克/巨人", entry.episodes.filter { it.playId.contains("未知") }).isEmpty())
    }
}
