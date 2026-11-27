package com.reelix.app.data.alist

import com.reelix.app.data.repo.LibraryRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LibraryScanTest {
    private fun video(path: String) = AlistVideo(path, path.substringAfterLast('/'), 100)
    @Test fun `暂时失败保留原资源成功扫描确认空目录才删除旧文件`() {
        val previous = LibraryScan(listOf(video("/bad/a.mp4"), video("/good/b.mp4")))
        val partial = LibraryScan(listOf(video("/good/c.mp4")), failures = listOf(DirectoryFailure("/bad", "driver not init")))
            .retainFailed(previous)
        assertEquals(listOf("/bad/a.mp4"), partial.archivedVideos.map { it.path })
        assertEquals(setOf("/good/c.mp4", "/bad/a.mp4"), partial.videos.map { it.path }.toSet())
        assertTrue(LibraryScan(emptyList()).retainFailed(partial).archivedVideos.isEmpty())
    }
    @Test fun `取消不能被吞成目录失败`() = runBlocking {
        try {
            LibraryScanner { _, _ -> throw CancellationException("cancel") }.scan("/", 12, true, "") { _, _ -> }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
    }
    @Test fun `旧失败快照重启恢复已知资源且不触发重扫`() = runBlocking {
        var scans = 0
        val cached = LibraryScan(emptyList(), listOf("/", "/bad"), listOf(DirectoryFailure("/bad", "unavailable")),
            listOf(video("/bad/a.mp4")))
        val client = object : LibraryDataSource {
            override val isLoggedIn = false
            override suspend fun login(username: String, password: String) = error("must not login")
            override suspend fun getFile(path: String): AlistFileData? = null
            override suspend fun scanVideos(root: String, maxDepth: Int, forceRefresh: Boolean, onProgress: (Int, String) -> Unit): List<AlistVideo> {
                scans++; error("must not scan")
            }
        }
        val store = object : LibrarySnapshotStore {
            override fun read() = cached.videos
            override fun write(videos: List<AlistVideo>) = error("must not write")
            override fun readSnapshot() = cached
        }
        val repo = LibraryRepository(mapOf("alist" to client), store)
        assertTrue(repo.load("u", "p").isSuccess)
        assertEquals(0, scans)
        assertFalse((repo.state.value as LibraryRepository.LibraryState.Ready).entries.isEmpty())
        assertEquals(cached.failures, repo.currentScan?.failures)
        assertEquals(cached.archivedVideos, repo.currentScan?.archivedVideos)
    }
}
