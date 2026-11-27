package com.reelix.app.data.repo

import com.reelix.app.data.alist.*
import com.reelix.app.data.db.PlayProgressEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryHistoryMigrationTest {
    @Test fun `编号季合并后历史仍定位真实文件和账号`() {
        val files = listOf(file("/夸克/巨人/巨人 全季+OVA 4K/1.Season 1/第1集.mkv"),
            file("/夸克/巨人/巨人 全季+OVA 4K/2.Season 2/第1集.mkv"))
        val oldEntries = AlistLibraryBuilder.build(files, mapOf("/夸克/巨人" to DirectoryMode.CATEGORY,
            "/夸克/巨人/巨人 全季+OVA 4K" to DirectoryMode.CATEGORY)).items
        val oldEntry = oldEntries.single { it.files.single().path == files.last().path }
        val old = PlayProgressEntity(oldEntry.item.id, sourceKey = "alist", sourceId = files.last().path,
            episodeIndex = 1, episodeName = files.last().name, positionMs = 50000, updatedAt = 123)
        val merged = AlistLibraryBuilder.build(files).items
        val migrated = migratedLibraryRecord(merged, old)!!
        assertEquals(merged.single().item.id, migrated.mediaId)
        assertEquals(files.last().path, migrated.sourceId)
        assertEquals(123, migrated.updatedAt)
        assertNull(migratedLibraryRecord(AlistLibraryBuilder.build(files.map { it.copy(sourceIdentity = "other") }).items, old))
    }
    @Test fun `旧版带账号身份也不能按同路径迁到其他账号`() {
        val files = listOf(file("/夸克/作品/第1集.mp4"))
        val legacy = LegacyAlistLibraryBuilder.build(files).items.single()
        val record = PlayProgressEntity(legacy.item.id, sourceKey = "alist", sourceId = files.single().path,
            episodeIndex = 1, positionMs = 50000)
        val other = AlistLibraryBuilder.build(files.map { it.copy(sourceIdentity = "server|other") }).items
        assertNull(migratedLibraryRecord(other, record))
    }
    @Test fun `选择其他文件不能被旧续播跳回原文件`() {
        val record = PlayProgressEntity("work", sourceId = "/work/a.mp4", episodeIndex = 1, positionMs = 50000)
        val other = com.reelix.app.data.model.Episode(2, "b.mp4", "alist", "/work/b.mp4")
        assertFalse(canResumeEpisode(record, other, "片库", true))
        assertTrue(canResumeEpisode(record, other.copy(index = 1, playId = record.sourceId), "片库", true))
        assertFalse(canResumeEpisode(record.copy(finished = true), other.copy(playId = record.sourceId), "片库", true))
    }
    @Test fun `在线同集号在线路名变化后仍能续播`() {
        val record = PlayProgressEntity("work", episodeIndex = 5, routeName = "线路1", positionMs = 50000)
        val episode = com.reelix.app.data.model.Episode(5, "第5集", "线路2", "id-5")
        assertTrue(canResumeEpisode(record, episode, "线路2", false))
    }
    private fun file(path: String) = AlistVideo(path, path.substringAfterLast('/'), 100, mountRoot = "/夸克", sourceIdentity = "server|user")
    @Test fun `历史以文件路径迁移而不是同集号的版本`() {
        val files = listOf(file("/夸克/作品/第1集.1080p.mp4"), file("/夸克/作品/第1集.2160p.mp4"))
        val entries = AlistLibraryBuilder.build(files).items
        val legacyId = LegacyAlistLibraryBuilder.build(files).items.single { it.files.any { file -> file.path == files.last().path } }.item.id
        val old = PlayProgressEntity(legacyId, sourceKey = "alist", sourceId = files.last().path,
            episodeIndex = 1, positionMs = 35000, durationMs = 100000, updatedAt = 123)
        val migrated = migratedLibraryRecord(entries, old)!!
        assertEquals(entries.single().item.id, migrated.mediaId)
        assertEquals(files.last().path, migrated.sourceId)
        assertEquals(entries.single().episodes.last().index, migrated.episodeIndex)
        assertEquals(old.positionMs, migrated.positionMs)
        assertEquals(old.updatedAt, migrated.updatedAt)
    }
    @Test fun `同名作品不能凭标题认领无路径旧历史`() {
        val entries = AlistLibraryBuilder.build(listOf(file("/夸克/作品/视频.mp4"))).items
        assertNull(migratedLibraryRecord(entries, PlayProgressEntity("unknown", title = "作品")))
    }
    @Test fun `目录切为分类后文件路径迁移且账号隔离`() {
        val files = listOf(file("/夸克/作品/电影1.mp4"), file("/夸克/作品/电影2.mp4"))
        val previous = AlistLibraryBuilder.build(files).items.single()
        val old = PlayProgressEntity(previous.item.id, sourceKey = "alist", sourceId = files.last().path,
            episodeIndex = 2, episodeName = files.last().name, positionMs = 50000, durationMs = 100000)
        val separate = AlistLibraryBuilder.build(files, mapOf("/夸克/作品" to DirectoryMode.CATEGORY)).items
        val migrated = migratedLibraryRecord(separate, old)!!
        assertEquals(1, migrated.episodeIndex)
        assertEquals(files.last().path, migrated.sourceId)
        val other = AlistLibraryBuilder.build(files.map { it.copy(sourceIdentity = "server|other") }).items
        assertNull(migratedLibraryRecord(other, old))
    }
}
