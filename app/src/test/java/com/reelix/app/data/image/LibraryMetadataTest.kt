package com.reelix.app.data.image

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LibraryMetadataTest {
    private val work = LibraryWorkIdentity("TMDB", "1399", "tv", "权力的游戏", "Game of Thrones", 2011, "SERIES")
    @Test fun `资料可序列化恢复且旧图片缓存没有身份字段仍可读`() {
        val record = LibraryWorkMetadata(work, "简介", listOf("导演"), listOf(LibraryPersonCredit("演员", "角色")))
        assertEquals(record, Json.decodeFromString<LibraryWorkMetadata>(Json.encodeToString(record)))
        assertNull(Json.decodeFromString<LibraryArtworkRecord>("""{"poster":"https://example.com/p"}""").identity)
    }
    @Test fun `资料应用保留文件归属集号和历史合集仅明确匹配才应用`() {
        val item = MediaItem("local", "合集", sourceId = "/目录", lastEpisode = 4, progress = .5f)
        val record = LibraryWorkMetadata(work, "简介")
        assertFalse(libraryMetadataApplies(item, record, null))
        assertTrue(libraryMetadataApplies(item, record, work))
        assertFalse(libraryMetadataApplies(item, record, work.copy(id = "other")))
        val applied = applyLibraryMetadata(item, record)
        assertEquals(item.id, applied.id); assertEquals(item.title, applied.title)
        assertEquals(item.sourceId, applied.sourceId); assertEquals(item.lastEpisode, applied.lastEpisode)
        assertEquals(item.progress, applied.progress)
    }
    @Test fun `跨源补字段必须验证作品不能混入同名电影或重拍版`() {
        assertFalse(sameLibraryWork(work, work.copy(provider = "ONLINE", id = "1", year = 2024)))
        assertFalse(sameLibraryWork(work, work.copy(provider = "ONLINE", id = "1", kind = "MOVIE")))
        assertFalse(sameLibraryWork(work, work.copy(provider = "ONLINE", id = "1", title = "其他片", originalTitle = null)))
        assertTrue(sameLibraryWork(work, work.copy(provider = "ONLINE", id = "1")))
        // 源站片名把年份粘在标题后面时，仍能按“标题 + 年份”认定为同一部作品。
        val source = LibraryWorkIdentity("ONLINE", "1", "", "奥德赛2026", null, 2026, "MOVIE", "site")
        val tmdb = LibraryWorkIdentity("TMDB", "1368337", "movie", "奥德赛", "The Odyssey", 2026, "MOVIE")
        assertTrue(sameLibraryWork(source, tmdb))
    }
    @Test fun `合集名称不自动绑定候选电影且同名多版本不擅自选第一条`() {
        val item = MediaItem("local", "导演合集", kind = MediaKind.MOVIE)
        val candidate = LibraryArtworkCandidate("电影", 2026, "https://images.example/p", identity = work)
        assertNull(uniqueLibraryArtworkMatch(item, listOf(candidate)))
        val movie = MediaItem("local", "同名电影", kind = MediaKind.MOVIE)
        val one = candidate.copy(title = movie.title, kind = movie.kind, identity = work.copy(id = "1"))
        assertNull(uniqueLibraryArtworkMatch(movie, listOf(one, one.copy(identity = work.copy(id = "2")))))
    }
    @Test fun `TMDB资料验证ID并解析中文简介导演和角色`() {
        val result = parseTmdbLibraryMetadata("""{"id":1399,"name":"权力的游戏","first_air_date":"2011-04-17","overview":"中文简介","aggregate_credits":{"cast":[{"name":"演员","roles":[{"character":"角色"}]}],"crew":[{"name":"导演","jobs":[{"job":"Director"}]}]}}""", work)
        assertEquals("中文简介", result?.overview)
        assertEquals(listOf("导演"), result?.directors)
        assertEquals("角色", result?.cast?.single()?.role)
        assertNull(parseTmdbLibraryMetadata("""{"id":999,"overview":"错片"}""", work))
    }
    @Test fun `补空字段保留有效资料并隔离不属于同一作品的数据`() {
        val old = LibraryWorkMetadata(work, overview = "原简介", directors = listOf("原导演"))
        val updated = mergeLibraryMetadata(old, LibraryWorkMetadata(work.copy(provider = "ONLINE", id = "1"), cast = listOf(LibraryPersonCredit("演员", "角色"))))
        assertEquals("原简介", updated.overview)
        assertEquals(listOf("原导演"), updated.directors)
        assertEquals("演员", updated.cast.single().name)
        assertEquals(old, mergeLibraryMetadata(old, LibraryWorkMetadata(work.copy(id = "2", title = "不同片", originalTitle = null), overview = "错片")))
    }
}
