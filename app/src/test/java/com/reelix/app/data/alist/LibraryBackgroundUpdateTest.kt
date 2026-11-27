package com.reelix.app.data.alist

import com.reelix.app.data.repo.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryBackgroundUpdateTest {
    @Test fun `自动检查先发布缓存同会话不重复扫描且暂时失败保留资源`() = runBlocking {
        val old = AlistVideo("/网盘/作品/01.mp4", "01.mp4", 100)
        val cached = LibraryScan(listOf(old))
        var scans = 0
        lateinit var repo: LibraryRepository
        val client = object : LibraryDataSource {
            override val isLoggedIn = true
            override suspend fun login(username: String, password: String) = Result.success(Unit)
            override suspend fun getFile(path: String): AlistFileData? = null
            override suspend fun scanVideos(root: String, maxDepth: Int, forceRefresh: Boolean, onProgress: (Int, String) -> Unit): List<AlistVideo> = error("use snapshot")
            override suspend fun scanSnapshot(root: String, maxDepth: Int, forceRefresh: Boolean, onProgress: (Int, String) -> Unit): LibraryScan {
                scans++
                assertTrue(repo.allItems().isNotEmpty())
                return LibraryScan(emptyList(), failures = listOf(DirectoryFailure("/网盘", "temporary")))
            }
        }
        val store = object : LibrarySnapshotStore {
            override fun read() = cached.videos
            override fun write(videos: List<AlistVideo>) {}
        }
        repo = LibraryRepository(mapOf("alist" to client), store)
        assertTrue(repo.load("u", "p", checkForUpdates = true).isSuccess)
        assertEquals(1, scans)
        assertEquals(listOf(old.path), repo.currentScan?.videos?.map { it.path })
        assertTrue(repo.load("u", "p", checkForUpdates = true).isSuccess)
        assertEquals(1, scans)
    }
}
