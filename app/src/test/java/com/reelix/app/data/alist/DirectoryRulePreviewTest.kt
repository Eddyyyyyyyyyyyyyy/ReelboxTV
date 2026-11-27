package com.reelix.app.data.alist

import com.reelix.app.data.repo.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DirectoryRulePreviewTest {
    private fun video(path: String, source: String = "account") =
        AlistVideo(path, path.substringAfterLast('/'), 1L, mountRoot = "/drive", sourceIdentity = source)

    private val files = listOf(video("/drive/Movies/One.mkv"), video("/drive/Movies/Two.mkv"))

    @Test fun `merge compares real builder posters and video owners`() {
        val result = DirectoryRulePreview.calculate(LibraryScan(files), emptyMap(), "/drive/Movies", DirectoryMode.WORK)
        assertEquals(2, result.beforePosters)
        assertEquals(1, result.afterPosters)
        assertEquals(1, result.added.size)
        assertEquals(2, result.removed.size)
        assertEquals(2, result.ownershipChanges.size)
        assertEquals(0, result.unchangedVideos)
        assertEquals(2, result.pathVideos)
        assertEquals("/drive/Movies", result.added.single().owner)
        assertEquals(2, result.added.single().videoCount)
    }

    @Test fun `expand gives every direct video its own poster`() {
        val rules = mapOf("/drive/Movies" to DirectoryMode.WORK)
        val result = DirectoryRulePreview.calculate(LibraryScan(files), rules, "/drive/Movies", DirectoryMode.CATEGORY)
        assertEquals(1, result.beforePosters)
        assertEquals(2, result.afterPosters)
        assertEquals(2, result.added.size)
        assertEquals(1, result.removed.size)
        assertEquals(files.map { it.path }.toSet(), result.ownershipChanges.map { it.resource.path }.toSet())
    }

    @Test fun `restore auto removes just the selected override`() {
        val rules = mapOf("/drive/Movies" to DirectoryMode.WORK, "/drive/Other" to DirectoryMode.WORK)
        val scan = LibraryScan(files + video("/drive/Other/A.mkv") + video("/drive/Other/B.mkv"))
        val result = DirectoryRulePreview.calculate(scan, rules, "/drive/Movies", null)
        assertEquals(2, result.beforePosters)
        assertEquals(3, result.afterPosters)
        assertEquals(2, result.unchangedVideos)
        assertEquals(2, result.ownershipChanges.size)
    }

    @Test fun `empty folder changes rule but creates no fictional poster`() {
        val result = DirectoryRulePreview.calculate(LibraryScan(emptyList(), listOf("/drive/Empty")),
            emptyMap(), "/drive/Empty", DirectoryMode.WORK)
        assertEquals(0, result.beforePosters)
        assertEquals(0, result.afterPosters)
        assertEquals(0, result.pathVideos)
        assertTrue(result.added.isEmpty())
        assertTrue(result.ownershipChanges.isEmpty())
    }

    @Test fun `nested work merge includes videos below descendants`() {
        val scan = LibraryScan(listOf(video("/drive/Collection/Alpha/a.mkv"), video("/drive/Collection/Beta/b.mkv")))
        val result = DirectoryRulePreview.calculate(scan, emptyMap(), "/drive/Collection", DirectoryMode.WORK)
        assertEquals(2, result.beforePosters)
        assertEquals(1, result.afterPosters)
        assertEquals(2, result.ownershipChanges.size)
    }

    @Test fun `child override can change automatic ancestor and sibling ownership`() {
        val scan = LibraryScan(listOf(video("/drive/Show/Season 1/a.mkv"), video("/drive/Show/Show/b.mkv")))
        val result = DirectoryRulePreview.calculate(scan, emptyMap(), "/drive/Show/Show", DirectoryMode.CATEGORY)
        assertEquals(1, result.beforePosters)
        assertEquals(2, result.afterPosters)
        assertEquals(2, result.ownershipChanges.size)
        assertEquals(1, result.pathVideos)
    }

    @Test fun `failed scans preserve actual counts and report archived uncertainty`() {
        val failure = DirectoryFailure("/drive/Movies", "permission denied")
        val scan = LibraryScan(files, failures = listOf(failure), archivedVideos = listOf(files.first()))
        val result = DirectoryRulePreview.calculate(scan, emptyMap(), "/drive/Movies", DirectoryMode.WORK)
        assertEquals(listOf(failure), result.failures)
        assertEquals(1, result.archivedVideos)
        assertEquals(2, result.pathVideos)
        assertEquals(1, result.afterPosters)
    }

    @Test fun `failed empty scan is not proof of no remote videos`() {
        val result = DirectoryRulePreview.calculate(
            LibraryScan(emptyList(), failures = listOf(DirectoryFailure("/drive", "offline"))),
            emptyMap(), "/drive", DirectoryMode.WORK)
        assertEquals(0, result.pathVideos)
        assertEquals(1, result.failures.size)
    }

    @Test fun `same effective rule leaves posters and owners unchanged`() {
        val rules = mapOf("/drive/Movies" to DirectoryMode.WORK)
        val result = DirectoryRulePreview.calculate(LibraryScan(files), rules, "/drive/Movies", DirectoryMode.WORK)
        assertEquals(result.beforePosters, result.afterPosters)
        assertEquals(2, result.unchangedVideos)
        assertTrue(result.added.isEmpty())
        assertTrue(result.removed.isEmpty())
        assertTrue(result.ownershipChanges.isEmpty())
    }

    @Test fun `restoring automatic recognition can leave grouping unchanged`() {
        val scan = LibraryScan(listOf(video("/drive/Show/a.mkv"), video("/drive/Show/b.mkv")))
        val result = DirectoryRulePreview.calculate(scan, mapOf("/drive/Show" to DirectoryMode.WORK), "/drive/Show", null)
        assertEquals(1, result.afterPosters)
        assertEquals(2, result.unchangedVideos)
        assertTrue(result.ownershipChanges.isEmpty())
    }

    @Test fun `same path across sources remains distinct and duplicate scans count once`() {
        val scan = LibraryScan(files + files + files.map { it.copy(sourceIdentity = "other") })
        val result = DirectoryRulePreview.calculate(scan, emptyMap(), "/drive/Movies", DirectoryMode.WORK)
        assertEquals(4, result.pathVideos)
        assertEquals(4, result.beforePosters)
        assertEquals(2, result.afterPosters)
        assertEquals(4, result.ownershipChanges.size)
        assertEquals(2, result.ownershipChanges.map { it.resource.sourceIdentity }.toSet().size)
    }

    @Test fun `prefix sibling stays untouched`() {
        val scan = LibraryScan(files + video("/drive/Movies2/Unrelated.mkv"))
        val result = DirectoryRulePreview.calculate(scan, emptyMap(), "/drive/Movies", DirectoryMode.WORK)
        assertEquals(2, result.pathVideos)
        assertEquals(1, result.unchangedVideos)
        assertFalse(result.ownershipChanges.any { it.resource.path.contains("Movies2") })
    }

    @Test fun `preview is deterministic and never mutates caller cache or rules`() {
        val videos = files.toMutableList()
        val rules = mutableMapOf("/drive/Other" to DirectoryMode.WORK)
        val directories = DirectoryRules.directories(videos).toMutableList()
        val failures = mutableListOf(DirectoryFailure("/drive/Other", "offline"))
        val archive = mutableListOf(files.first())
        val scan = LibraryScan(videos, directories, failures, archive)
        val before = scan.copy(videos = videos.toList(), directories = directories.toList(),
            failures = failures.toList(), archivedVideos = archive.toList())
        val rulesBefore = rules.toMap()
        val preview = DirectoryRulePreview.calculate(scan, rules, " /drive//Movies/ ", DirectoryMode.WORK)
        assertEquals("/drive/Movies", preview.path)
        assertEquals(preview, DirectoryRulePreview.calculate(scan, rules, preview.path, DirectoryMode.WORK))
        assertEquals(before, scan)
        assertEquals(rulesBefore, rules)
        assertEquals(1, preview.afterPosters)
    }

    @Test fun `preview cannot scan or write repository snapshot and does not publish posters`() = runBlocking {
        var reads = 0
        var writes = 0
        val client = object : LibraryDataSource {
            override val isLoggedIn = false
            override suspend fun login(username: String, password: String): kotlin.Result<Unit> =
                error("Preview must not log in")
            override suspend fun scanVideos(root: String, maxDepth: Int, forceRefresh: Boolean,
                onProgress: (Int, String) -> Unit): List<AlistVideo> = error("Preview must not scan")
            override suspend fun getFile(path: String): AlistFileData? = error("Preview must not resolve files")
        }
        val repo = LibraryRepository(mapOf("alist" to client), object : LibrarySnapshotStore {
            override fun read(): List<AlistVideo> { reads++; return files }
            override fun write(videos: List<AlistVideo>) { writes++ }
        })
        assertTrue(repo.load("user", "password", allowScan = false).isSuccess)
        val scan = checkNotNull(repo.currentScan)
        val state = repo.state.value
        val rules = repo.directoryRules()
        val readsBefore = reads
        val preview = DirectoryRulePreview.calculate(scan, rules, "/drive/Movies", DirectoryMode.WORK)
        assertEquals(1, preview.afterPosters)
        // Discarding this value is the cancel boundary; only the dialog's confirm callback persists.
        assertSame(scan, repo.currentScan)
        assertSame(state, repo.state.value)
        assertEquals(rules, repo.directoryRules())
        assertEquals(readsBefore, reads)
        assertEquals(0, writes)
    }
}
