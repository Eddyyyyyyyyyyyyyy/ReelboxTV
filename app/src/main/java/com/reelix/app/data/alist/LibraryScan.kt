package com.reelix.app.data.alist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class DirectoryFailure(val path: String, val message: String)
data class LibraryScan(
    val videos: List<AlistVideo>,
    val directories: List<String> = DirectoryRules.directories(videos),
    val failures: List<DirectoryFailure> = emptyList(),
    /** Last-known files beneath failed directories; a successful listing is required to confirm deletion. */
    val archivedVideos: List<AlistVideo> = emptyList(),
) {
    fun retainFailed(previous: LibraryScan?): LibraryScan {
        val archive = previous?.let { it.videos + it.archivedVideos }.orEmpty()
            .filter { video -> failures.any { underDirectory(video.path, it.path) } }
            .distinctBy { it.sourceIdentity to it.path }
        return copy(videos = (videos + archive).distinctBy { it.sourceIdentity to it.path },
            directories = (directories + previous?.directories.orEmpty().filter { path -> failures.any { underDirectory(path, it.path) } }).distinct(),
            archivedVideos = archive)
    }
}

/** A complete paginated directory is the smallest commit unit. Cancellation never publishes a scan. */
class LibraryScanner(private val list: suspend (String, Boolean) -> List<AlistFileItem>) {
    suspend fun scan(root: String, maxDepth: Int, refresh: Boolean, source: String,
        progress: (Int, String) -> Unit): LibraryScan {
        val videos = mutableListOf<AlistVideo>()
        val directories = mutableListOf<String>()
        val failures = mutableListOf<DirectoryFailure>()
        var count = 0
        suspend fun walk(path: String, depth: Int, mount: String) {
            coroutineContext.ensureActive()
            directories += path
            progress(++count, path)
            val items = try {
                check(depth <= maxDepth) { "Scan depth exceeded ($maxDepth)" }
                list(path, refresh)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { failures += DirectoryFailure(path, e.message.orEmpty()); return }
            for (item in items) {
                coroutineContext.ensureActive()
                val child = path.trimEnd('/') + "/" + item.name
                if (item.isDir) walk(child, depth + 1, if (path == "/") child else mount)
                else if (item.isVideo) videos += AlistVideo(child, item.name, item.size, item.provider, mount, source,
                    modifiedAt = libraryModifiedAt(item.modified))
            }
        }
        val normalized = libraryPath(root)
        walk(normalized, 0, if (normalized == "/") "/" else "/" + normalized.trim('/').substringBefore('/'))
        return LibraryScan(videos.sortedBy { it.path }, directories.distinct().sorted(), failures)
    }
}
