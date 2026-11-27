package com.reelix.app.data.image

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class ManualArtworkStreamTest {
    @Test fun `各源独立返回失败不掩盖其他候选`() = runBlocking {
        val events = streamManualLibraryArtwork(LibraryArtworkSource.entries) {
            when (it) {
                LibraryArtworkSource.TMDB -> { delay(80); error("offline") }
                LibraryArtworkSource.ONLINE -> listOf(LibraryArtworkCandidate("电影", null, "https://images.example/poster.jpg"))
                LibraryArtworkSource.STREAMX, LibraryArtworkSource.TVMAZE, LibraryArtworkSource.DOUBAN -> emptyList()
            }
        }.toList()
        assertEquals(LibraryArtworkSource.entries.size, events.count { it.phase == ManualArtworkPhase.LOADING })
        assertEquals(LibraryArtworkSource.ONLINE, events.first { it.candidates.isNotEmpty() }.source)
        assertEquals(ManualArtworkPhase.ERROR, events.last { it.source == LibraryArtworkSource.TMDB }.phase)
    }
    @Test fun `关闭TMDB不请求并且无效图片不会进入候选`() = runBlocking {
        val called = mutableSetOf<LibraryArtworkSource>()
        val events = streamManualLibraryArtwork(manualLibraryArtworkSources(false, "saved", true)) {
            synchronized(called) { called += it }
            listOf(LibraryArtworkCandidate("非法", null, "file:///private"), LibraryArtworkCandidate("有效", null,
                "https://example.org/poster.jpg", backdrop = "javascript:bad"))
        }.toList()
        assertEquals(setOf(LibraryArtworkSource.ONLINE), called)
        assertEquals(1, events.last().candidates.size)
        assertNull(events.last().candidates.single().backdrop)
    }
    @Test fun `无可用手动来源时结束加载并提示配置`() = runBlocking {
        val events = streamManualLibraryArtwork(manualLibraryArtworkSources(false, "", false)) {
            error("No provider may be requested")
        }.toList()
        assertEquals(ManualArtworkPhase.ERROR, events.single().phase)
        assertNotNull(events.single().error)
    }
    @Test fun `取消搜索停止整个汇总而不误报来源失败`() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val work = launch {
            streamManualLibraryArtwork(listOf(LibraryArtworkSource.ONLINE)) {
                started.complete(Unit)
                try { awaitCancellation() } finally { finished.complete(Unit) }
            }.toList()
        }
        withTimeout(1000) { started.await() }
        work.cancelAndJoin()
        withTimeout(1000) { finished.await() }
        assertTrue(work.isCancelled)
    }
}
