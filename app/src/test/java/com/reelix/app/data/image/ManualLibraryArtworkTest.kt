package com.reelix.app.data.image

import com.reelix.app.data.model.MediaItem
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException

class ManualLibraryArtworkTest {
    @Test fun `关闭TMDB的手动搜索确实不会查询TMDB并保留源横图`() = runBlocking {
        val called = mutableListOf<LibraryArtworkSource>()
        val candidate = LibraryArtworkCandidate("候选作品", 2026, "http://images.example/poster", "https://images.example/wide")
        val result = searchManualLibraryArtwork(manualLibraryArtworkSources(false, "saved-key", true)) {
            called += it; listOf(candidate)
        }
        assertEquals(listOf(LibraryArtworkSource.ONLINE), called)
        assertEquals(listOf(candidate), result.candidates)
        assertEquals(LibraryArtworkSource.ONLINE, result.source)
    }
    @Test fun `自动兜底只查TMDB与在线源`() = runBlocking {
        val called = mutableListOf<LibraryArtworkSource>()
        val result = searchManualLibraryArtwork(manualLibraryArtworkSources(true, "key", true)) {
            called += it
            when (it) {
                LibraryArtworkSource.TMDB -> error("offline")
                LibraryArtworkSource.ONLINE -> listOf(LibraryArtworkCandidate("新名", 2026, "http://images.example/poster"))
                LibraryArtworkSource.STREAMX, LibraryArtworkSource.TVMAZE, LibraryArtworkSource.DOUBAN -> emptyList()
            }
        }
        assertEquals(listOf(LibraryArtworkSource.TMDB, LibraryArtworkSource.ONLINE), called)
        assertEquals(LibraryArtworkSource.ONLINE, result.source)
        assertEquals("新名", result.candidates.single().title)
        assertNull(result.candidates.single().backdrop)
    }
    @Test fun `空搜索结果与所有接口失败可区分`() = runBlocking {
        assertTrue(searchManualLibraryArtwork(listOf(LibraryArtworkSource.ONLINE)) { emptyList() }.candidates.isEmpty())
        try {
            searchManualLibraryArtwork(listOf(LibraryArtworkSource.ONLINE)) { error("offline") }
            fail("all failed")
        } catch (e: IllegalStateException) { assertEquals("offline", e.message) }
    }
    @Test fun `人工保存无横图后详情不再自动补回TMDB横图`() {
        val item = MediaItem("a", "片名", poster = "file:///poster.jpg", fromLibrary = true)
        assertFalse(shouldScrapeDetailArtwork(item, true, "key"))
        assertFalse(shouldScrapeDetailArtwork(item.copy(poster = "https://images.example/a"), false, "key"))
        assertTrue(shouldScrapeDetailArtwork(item.copy(poster = "https://images.example/a"), true, "key"))
    }
    @Test(expected = CancellationException::class) fun `取消手动搜索不会再请求后备源`() { runBlocking {
        searchManualLibraryArtwork(manualLibraryArtworkSources(true, "key", true)) { throw CancellationException() }
    } }
    @Test fun `关闭TMDB即使保留key手动也只查当前源`() {
        assertEquals(listOf(LibraryArtworkSource.ONLINE),
            manualLibraryArtworkSources(false, "configured-key", true))
    }
    @Test fun `启用且配置TMDB优先否则仍可无key更换海报`() {
        assertEquals(listOf(LibraryArtworkSource.TMDB, LibraryArtworkSource.ONLINE),
            manualLibraryArtworkSources(true, "configured-key", true))
        assertEquals(listOf(LibraryArtworkSource.ONLINE),
            manualLibraryArtworkSources(true, "", true))
        assertEquals(emptyList<LibraryArtworkSource>(), manualLibraryArtworkSources(false, "key", false))
    }
    @Test fun `HTTP图片可保存但拒绝非网络地址`() {
        assertTrue(manualArtworkUrlAllowed("http://images.example/poster.jpg"))
        assertTrue(manualArtworkUrlAllowed("https://images.example/poster.jpg"))
        listOf("https://", "https:///missing-host", "file:///tmp/image", "javascript:alert(1)", "").forEach {
            assertFalse(it, manualArtworkUrlAllowed(it))
        }
    }
    @Test fun `手动只换竖海报时保留已有横图且不更改作品身份`() {
        val old = MediaItem("stable-id", "作品", poster = "https://old/poster", backdrop = "https://old/wide",
            backdropRes = "old_wide", fromLibrary = true, sourceId = "/作品", lastEpisode = 3)
        val result = applyManualLibraryArtwork(old, "file:///poster.jpg", null)
        assertEquals("https://old/wide", result.backdrop)
        assertEquals("old_wide", result.backdropRes)
        assertEquals(old.id, result.id); assertEquals(old.sourceId, result.sourceId)
        assertEquals(old.title, result.title); assertEquals(old.lastEpisode, result.lastEpisode)
    }
}
