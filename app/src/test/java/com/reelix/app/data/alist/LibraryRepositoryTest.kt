package com.reelix.app.data.alist

import com.reelix.app.data.repo.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryRepositoryTest {
    private val videos = listOf(AlistVideo("/电影/流浪地球.2019.mkv", "流浪地球.2019.mkv", 100))
    private class Client : LibraryDataSource {
        override var isLoggedIn = false
        var loginCount = 0
        var failScan = false
        var forced = false
        var observeScan: () -> Unit = {}
        override suspend fun login(username: String, password: String): Result<Unit> {
            loginCount++; isLoggedIn = true; return Result.success(Unit)
        }
        override suspend fun scanVideos(root: String, maxDepth: Int, forceRefresh: Boolean, onProgress: (Int, String) -> Unit): List<AlistVideo> {
            forced = forceRefresh
            onProgress(3, "/drive")
            observeScan()
            if (failScan) error("network unavailable")
            return listOf(AlistVideo("/电影/新片.mkv", "新片.mkv", 100))
        }
        override suspend fun getFile(path: String) = AlistFileData(rawUrl = "https://example.test/video")
    }
    @Test fun `缓存离线恢复并在播放前登录`() = runBlocking {
        val client = Client()
        val store = object : LibrarySnapshotStore {
            override fun read() = videos
            override fun write(videos: List<AlistVideo>) = error("restore must not write")
        }
        val repo = LibraryRepository(mapOf("alist" to client), store)
        assertTrue(repo.load("user", "pass", allowScan = false).isSuccess)
        assertEquals(0, client.loginCount)
        val ready = repo.state.value as LibraryRepository.LibraryState.Ready
        assertEquals(1, ready.entries.size)
        assertNotNull(repo.resolveEpisode(ready.entries.single().episodes.single()))
        assertEquals(1, client.loginCount)
    }
    @Test fun `刷新失败保留已有片库和缓存`() = runBlocking {
        val client = Client()
        var writes = 0
        val store = object : LibrarySnapshotStore {
            override fun read() = videos
            override fun write(videos: List<AlistVideo>) { writes++ }
        }
        val repo = LibraryRepository(mapOf("alist" to client), store)
        repo.load("u", "p", allowScan = false)
        val before = repo.state.value
        client.failScan = true
        assertTrue(repo.load("u", "p", force = true).isFailure)
        assertEquals(before, repo.state.value)
        assertEquals(0, writes)
        assertNull(repo.scanProgress.value)
    }
    @Test fun `手动刷新过程中保留海报并发布进度然后更新完成`() = runBlocking {
        val client = Client()
        val repo = LibraryRepository(mapOf("alist" to client), object : LibrarySnapshotStore {
            override fun read() = videos
            override fun write(videos: List<AlistVideo>) = Unit
        })
        repo.load("u", "p", allowScan = false)
        val previous = repo.state.value
        client.observeScan = {
            assertEquals(previous, repo.state.value)
            assertTrue(repo.scanProgress.value.orEmpty().contains("3"))
        }
        assertTrue(repo.load("u", "p", force = true).isSuccess)
        assertTrue(client.forced)
        assertEquals("新片", repo.allItems().single().title)
        assertNull(repo.scanProgress.value)
    }
}
