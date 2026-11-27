package com.reelix.app.data.alist

import org.junit.Assert.*
import org.junit.Test

class LibraryActivityTest {
    private val a = AlistVideo("/夸克/剧/e1.mkv", "e1.mkv", 100, sourceIdentity = "s", modifiedAt = 1000)
    @Test fun `刷新相同资源不改变活动时间而新增和替换会推进`() {
        val initial = LibraryScan(listOf(a)).withActivity(null, 2000)
        val refresh = LibraryScan(listOf(a)).withActivity(initial, 3000)
        assertEquals(2000, libraryActivityAt(refresh.videos))
        val changed = LibraryScan(listOf(a.copy(sizeBytes = 200))).withActivity(refresh, 4000)
        assertEquals(4000, libraryActivityAt(changed.videos))
        val added = LibraryScan(listOf(a, a.copy(path = "/夸克/剧/e2.mkv"))).withActivity(initial, 5000)
        assertEquals(5000, libraryActivityAt(added.videos))
        assertEquals(2000, added.videos.first().firstSeenAt)
    }
    @Test fun `旧缓存补齐修改时间不会把所有作品当成今天新增`() {
        val old = LibraryScan(listOf(a.copy(modifiedAt = 0)))
        assertEquals(1000, libraryActivityAt(LibraryScan(listOf(a)).withActivity(old, 5000).videos))
    }
    @Test fun `日期处理时区和异常值`() {
        assertEquals(1704067200000, libraryModifiedAt("2024-01-01T08:00:00+08:00", Long.MAX_VALUE))
        assertEquals(0, libraryModifiedAt("0001-01-01T00:00:00Z"))
        assertEquals(0, libraryModifiedAt("bad"))
        assertEquals(100, libraryModifiedAt("2099-01-01T00:00:00Z", 100))
    }
    @Test fun `失败恢复从归档找回时间且不同账号不继承`() {
        val archived = LibraryScan(emptyList(), archivedVideos = listOf(a.copy(firstSeenAt = 2000, changedAt = 2000)))
        assertEquals(2000, libraryActivityAt(LibraryScan(listOf(a)).withActivity(archived, 5000).videos))
        assertEquals(5000, libraryActivityAt(LibraryScan(listOf(a.copy(sourceIdentity = "other"))).withActivity(archived, 5000).videos))
    }
    @Test fun `观看与实际更新按最近时间混排且换封面不改变顺序`() {
        val items = AlistLibraryBuilder.build(listOf(
            a.copy(path = "/夸克/旧片/a.mkv", changedAt = 2000),
            a.copy(path = "/夸克/更新/b.mkv", changedAt = 5000))).items
        val watchedId = items.single { it.item.title == "旧片" }.item.id
        assertEquals(listOf("旧片", "更新"), libraryEntriesByActivity(items, mapOf(watchedId to 6000)).map { it.item.title })
        assertEquals(listOf("更新", "旧片"), libraryEntriesByActivity(items).map { it.item.title })
        assertEquals(libraryEntriesByActivity(items).map { it.item.id },
            libraryEntriesByActivity(items.map { it.copy(item = it.item.copy(poster = "https://cover")) }).map { it.item.id })
    }
}
