package com.reelix.app.data.alist

/** Keep restoration and scan failure handling testable without a device. */
interface LibraryDataSource {
    val isLoggedIn: Boolean
    /** Stable id of the configured source: alist / webdav / emby. */
    val sourceKey: String get() = "alist"
    suspend fun login(username: String, password: String): Result<Unit>
    suspend fun scanVideos(root: String = "/", maxDepth: Int = 12, forceRefresh: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> }): List<AlistVideo>
    suspend fun getFile(path: String): AlistFileData?
    /** Removes a real file/directory on the backing drive. Sources stay read-only by default. */
    suspend fun remove(path: String, isDirectory: Boolean = false): Result<Unit> =
        Result.failure(UnsupportedOperationException("该源不支持删除"))
    suspend fun scanSnapshot(root: String, maxDepth: Int = 12, forceRefresh: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> }): LibraryScan =
        LibraryScan(scanVideos(root, maxDepth, forceRefresh, onProgress))
}

interface LibrarySnapshotStore {
    fun read(): List<AlistVideo>?
    fun write(videos: List<AlistVideo>)
    fun readSnapshot(): LibraryScan? = read()?.let { LibraryScan(it) }
    fun writeSnapshot(snapshot: LibraryScan) = write(snapshot.videos)
}
