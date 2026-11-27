package com.reelix.app.data.image

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryArtworkPolicyTest {
    private val item = MediaItem("a", "三体", year = 2023, kind = MediaKind.SERIES)
    @Test fun `配置TMDB优先否则当前源优先`() {
        assertEquals(listOf(LibraryArtworkSource.TMDB, LibraryArtworkSource.ONLINE),
            artworkProviderSlots(true, true, emptyList()).map { it.source })
        assertEquals(listOf(LibraryArtworkSource.ONLINE),
            artworkProviderSlots(false, true, emptyList()).map { it.source })
    }
    @Test fun `不能以年份或首个有图结果冒充片名命中`() {
        val candidates = listOf(
            LibraryArtworkCandidate("其他片", 2023, "https://a"),
            LibraryArtworkCandidate("三体", 2020, "https://b"),
            LibraryArtworkCandidate("三体", 2023, "https://c", kind = MediaKind.MOVIE),
            LibraryArtworkCandidate("三体 第一季", 2023, "https://good", kind = MediaKind.SERIES))
        assertEquals(listOf("https://good"), libraryArtworkCandidates(item, candidates).map { it.poster })
    }
    @Test fun `尾部年份与条目年份一致时可以用短标题匹配`() {
        val odyssey = MediaItem("o", "奥德赛2026", year = 2026, kind = MediaKind.MOVIE)
        val candidate = LibraryArtworkCandidate("奥德赛", 2026, "https://poster", kind = MediaKind.MOVIE)
        assertEquals(listOf("https://poster"), libraryArtworkCandidates(odyssey, listOf(candidate)).map { it.poster })
    }
    @Test fun `片名本身是年份时不会被误裁剪`() {
        val item = MediaItem("1917", "1917", year = 2019, kind = MediaKind.MOVIE)
        val candidate = LibraryArtworkCandidate("1917", 2019, "https://poster", kind = MediaKind.MOVIE)
        assertEquals(listOf("https://poster"), libraryArtworkCandidates(item, listOf(candidate)).map { it.poster })
    }
    @Test fun `TMDB访问失败和源死图时返回空且无效横图不采用`() = runBlocking {
        val slots = artworkProviderSlots(true, true, emptyList())
        val called = mutableListOf<LibraryArtworkSource>()
        val result = resolveLibraryArtwork(item, slots, query = { slot ->
            called += slot.source
            when (slot.source) {
                LibraryArtworkSource.TMDB -> error("unavailable")
                LibraryArtworkSource.ONLINE -> listOf(LibraryArtworkCandidate("三体", 2023, "https://dead"))
                LibraryArtworkSource.STREAMX, LibraryArtworkSource.TVMAZE, LibraryArtworkSource.DOUBAN -> emptyList()
            }
        }, readable = { it == "https://good" })
        assertEquals(slots.map { it.source }, called)
        assertNull(result.record)
        assertEquals(ArtworkTargetState.FAILED, result.state)
    }
    @Test fun `命中即停止不滥用后备接口`() = runBlocking {
        val called = mutableListOf<LibraryArtworkSource>()
        val result = resolveLibraryArtwork(item, artworkProviderSlots(true, true, emptyList()), query = { slot ->
            called += slot.source
            listOf(LibraryArtworkCandidate("三体", 2023, "https://good", backdrop = "https://wide"))
        }, readable = { true })
        assertEquals(listOf(LibraryArtworkSource.TMDB), called)
        assertEquals(ArtworkTargetState.FOUND, result.state)
        assertEquals("https://good", result.record?.poster)
        assertEquals("https://wide", result.record?.backdrop)
    }
    @Test fun `拿到海报但没横图时仍算完成`() = runBlocking {
        val result = resolveLibraryArtwork(item, artworkProviderSlots(true, true, emptyList()), query = { slot ->
            if (slot.source == LibraryArtworkSource.TMDB) {
                listOf(LibraryArtworkCandidate("三体", 2023, "https://poster"))
            } else {
                emptyList()
            }
        }, readable = { true })
        assertEquals(ArtworkTargetState.FOUND, result.state)
        assertEquals("https://poster", result.record?.poster)
        assertNull(result.record?.backdrop)
    }
    @Test fun `所有人都没有图且无失败时是确认没有`() = runBlocking {
        val result = resolveLibraryArtwork(item, artworkProviderSlots(true, true, emptyList()), query = {
            emptyList()
        }, readable = { true })
        assertEquals(ArtworkTargetState.CONFIRMED_MISSING, result.state)
        assertNull(result.record)
    }
    @Test fun `任一来源失败且没有横图时是失败`() = runBlocking {
        val result = resolveLibraryArtwork(item, artworkProviderSlots(true, true, emptyList()), query = { slot ->
            when (slot.source) {
                LibraryArtworkSource.TMDB -> error("offline")
                LibraryArtworkSource.ONLINE -> emptyList()
                LibraryArtworkSource.STREAMX, LibraryArtworkSource.TVMAZE, LibraryArtworkSource.DOUBAN -> emptyList()
            }
        }, readable = { true })
        assertEquals(ArtworkTargetState.FAILED, result.state)
        assertNull(result.record)
    }
    @Test(expected = CancellationException::class) fun `取消不会被当成失败再轰炸后备源`() { runBlocking {
        resolveLibraryArtwork(item, artworkProviderSlots(true, true, emptyList()),
            query = { throw CancellationException() }, readable = { true })
    } }
}
