package com.reelix.app.ui.screen.library

import com.reelix.app.data.alist.*

data class DirectoryBrowserRow(
    val path: String,
    val current: Boolean,
    val cachedVideos: Int,
    val cachedChildren: Int,
    val manual: DirectoryMode?,
    val mode: DirectoryMode,
    val failure: String?,
    val scanned: Boolean,
    val mount: Boolean,
)

fun directoryBrowserRows(scan: LibraryScan?, root: String, current: String,
    rules: Map<String, DirectoryMode>): List<DirectoryBrowserRow> {
    val boundary = libraryPath(root)
    val parent = libraryPath(current).takeIf { underDirectory(it, boundary) } ?: boundary
    val known = (scan?.directories.orEmpty() + DirectoryRules.directories(scan?.videos.orEmpty()) + boundary)
        .map(::libraryPath).filter { underDirectory(it, boundary) }.distinct().sorted()
    val videos = scan?.videos.orEmpty()
    val automatic = DirectoryRules.modes(videos, known)
    val children = known.filter { it != "/" }.groupingBy(::parentDirectory).eachCount()
    val counts = mutableMapOf<String, Int>()
    videos.forEach { video ->
        var ancestor = parentDirectory(video.path)
        while (underDirectory(ancestor, boundary)) {
            counts[ancestor] = (counts[ancestor] ?: 0) + 1
            if (ancestor == boundary) break
            ancestor = parentDirectory(ancestor)
        }
    }
    val scanned = scan?.directories.orEmpty().toSet()
    val mounts = videos.map { it.mountRoot }.toSet()
    return (listOf(parent) + known.filter { it != parent && parentDirectory(it) == parent }).map { path ->
        val manual = rules[path]
        DirectoryBrowserRow(path, path == parent, counts[path] ?: 0,
            children[path] ?: 0, manual,
            manual ?: automatic[path] ?: DirectoryMode.CATEGORY,
            scan?.failures?.firstOrNull { underDirectory(path, it.path) || underDirectory(it.path, path) }?.message,
            path in scanned, path == "/" || path in mounts)
    }
}

class DirectoryBrowserPosition(root: String) {
    val root = libraryPath(root)
    var current = this.root
        private set
    var selected = this.root
    private val positions = mutableMapOf<String, Pair<String, Pair<Int, Int>>>()
    fun remember(first: Int, offset: Int) { positions[current] = selected to (first to offset) }
    fun enter(path: String): Boolean {
        val normalized = libraryPath(path)
        if (normalized == current || !underDirectory(normalized, root) || parentDirectory(normalized) != current) return false
        current = normalized
        selected = positions[current]?.first ?: current
        return true
    }
    fun parent(): Boolean {
        if (current == root) return false
        val child = current
        current = parentDirectory(current).takeIf { underDirectory(it, root) } ?: root
        selected = child
        return true
    }
    fun viewport(): Pair<Int, Int> = positions[current]?.second ?: (0 to 0)
}
