package com.reelix.app.data.alist

import org.junit.Assert.*
import org.junit.Test

class DirectoryGroupingTest {
    private fun file(path: String) = AlistVideo(path, path.substringAfterLast('/'), 100,
        mountRoot = "/" + path.trim('/').substringBefore('/'), sourceIdentity = "server|user")

    @Test fun `作品合集只能一个海报且保留所有原文件`() {
        val files = listOf(file("/夸克/蜘蛛侠/蜘蛛侠1.2002.mp4"), file("/夸克/蜘蛛侠/蜘蛛侠2.2004.mp4"))
        val entries = AlistLibraryBuilder.build(files).items
        assertEquals(1, entries.size)
        assertEquals("蜘蛛侠", entries.single().item.title)
        assertEquals(files.map { it.name }.toSet(), entries.single().episodes.map { it.name }.toSet())
    }
    @Test fun `综艺技术词和加更不能拆出作品`() {
        val files = listOf(file("/夸克/现在就出发第四季/2026.10.17_第2期上_4K_60fps.mp4"),
            file("/夸克/现在就出发第四季/加更幕后60fps.mp4"))
        val entries = AlistLibraryBuilder.build(files).items
        assertEquals(1, entries.size)
        assertEquals("现在就出发第四季", entries.single().item.title)
        assertEquals(2, entries.single().episodes.size)
    }
    @Test fun `相同电影不同网盘独立`() {
        val entries = AlistLibraryBuilder.build(listOf(file("/115/流浪地球.2019.mkv"),
            file("/夸克/流浪地球.2019.mkv"))).items
        assertEquals(2, entries.size)
    }
    @Test fun `作品下的季和版本都保留在同海报`() {
        val entries = AlistLibraryBuilder.build(listOf(file("/115/三体/Season 1/三体.S01E01.mp4"),
            file("/115/三体/Season 2/三体.S02E01.mp4"))).items
        assertEquals(1, entries.size)
        assertEquals(2, entries.single().episodes.size)
    }
    @Test fun `手动分类拆散文件再设作品完整汇总且身份不受年份影响`() {
        val files = listOf(file("/夸克/收藏/蜘蛛侠1.mp4"), file("/夸克/收藏/蜘蛛侠2.mp4"))
        assertEquals(2, AlistLibraryBuilder.build(files, mapOf("/夸克/收藏" to DirectoryMode.CATEGORY)).items.size)
        val grouped = AlistLibraryBuilder.build(files, mapOf("/夸克/收藏" to DirectoryMode.WORK)).items.single()
        assertEquals(2, grouped.files.size)
        assertEquals(grouped.item.id, AlistLibraryBuilder.build(files.reversed()).items.single().item.id)
    }
    @Test fun `父作品归属覆盖内部命名且分类目录可以任意深`() {
        val files = listOf(file("/夸克/电影/华语/蜘蛛侠/1080p/蜘蛛侠1.mp4"),
            file("/夸克/电影/华语/蜘蛛侠/花絮/幕后.mp4"))
        val entries = AlistLibraryBuilder.build(files).items
        assertEquals(1, entries.size)
        assertEquals("蜘蛛侠", entries.single().item.title)
        assertEquals(2, entries.single().files.size)
    }
    @Test fun `新身份不能用同路径跨账号恢复`() {
        val a = file("/夸克/作品/视频.mp4")
        val before = AlistLibraryBuilder.build(listOf(a)).items.single()
        val other = AlistLibraryBuilder.build(listOf(a.copy(sourceIdentity = "server|other"))).items
        assertNull(AlistLibraryBuilder.find(other, before.item.copy(sourceId = a.path)))
    }
    @Test fun `即使把总根目录设为作品也不能跨挂载合并`() {
        val entries = AlistLibraryBuilder.build(listOf(file("/夸克/蜘蛛侠/1.mp4"), file("/115/蜘蛛侠/2.mp4")),
            mapOf("/" to DirectoryMode.WORK)).items
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.item.id }.distinct().size)
    }
    @Test fun `散文件归组到目录后可按播放路径恢复`() {
        val files = listOf(file("/夸克/收藏/蜘蛛侠1.mp4"), file("/夸克/收藏/蜘蛛侠2.mp4"))
        val standalone = AlistLibraryBuilder.build(files, mapOf("/夸克/收藏" to DirectoryMode.CATEGORY)).items
        val grouped = AlistLibraryBuilder.build(files).items
        assertNotNull(AlistLibraryBuilder.find(grouped, standalone.first().item))
    }
}
