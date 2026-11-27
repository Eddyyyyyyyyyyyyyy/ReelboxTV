package com.reelix.app.data.alist

enum class DirectoryMode { CATEGORY, WORK }

fun libraryPath(path: String): String = "/" + path.trim().trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
fun underDirectory(path: String, directory: String): Boolean =
    path == libraryPath(directory) || path.startsWith(libraryPath(directory).trimEnd('/') + "/")
fun parentDirectory(path: String): String = path.substringBeforeLast('/').ifBlank { "/" }

object DirectoryRules {
    private val categories = setOf("电影", "电视剧", "剧集", "动漫", "综艺", "视频", "网盘", "根目录",
        "movies", "movie", "tv", "series", "video", "videos", "下载", "电影合集", "剧集合集")
    fun isCategoryName(name: String) = name.trim().lowercase() in categories
    fun isVariant(name: String): Boolean = librarySectionTag(name) != null || Regex(
        "(?i)(season[ ._-]*\\d+|s\\d{1,3}|第[一二三四五六七八九十百零两0-9]+季|\\d{3,4}p|[248]k|hdr|uhd|dv|全集|正片|更新中|完结|花絮|加更|特典|特别篇|预告|extras|specials|x26[45]|h[ .]?26[45]|hevc|av1)([ ._-].*)?"
    ).matches(name.trim())
    fun directories(videos: List<AlistVideo>): List<String> = buildSet {
        add("/")
        videos.forEach { file ->
            var path = parentDirectory(file.path)
            while (path != "/") { add(path); path = parentDirectory(path) }
        }
    }.sorted()
    fun automatic(path: String, videos: List<AlistVideo>, directories: Collection<String>): DirectoryMode {
        return modes(videos, directories)[path] ?: DirectoryMode.CATEGORY
    }

    fun modes(videos: List<AlistVideo>, directories: Collection<String>, overrides: Map<String, DirectoryMode> = emptyMap()): Map<String, DirectoryMode> {
        val parents = videos.map { parentDirectory(it.path) }.toSet()
        val mounts = videos.map { libraryPath(it.mountRoot.ifBlank { "/" + it.path.trim('/').substringBefore('/') }) }.toSet()
        val children = directories.filter { it != "/" }.groupBy(::parentDirectory)
        val result = mutableMapOf<String, DirectoryMode>()
        directories.sortedByDescending { it.count { c -> c == '/' } }.forEach { path ->
            val nested = children[path].orEmpty()
            result[path] = overrides[path] ?: when {
                path == "/" || path in mounts || isCategoryName(path.substringAfterLast('/')) -> DirectoryMode.CATEGORY
                path in parents -> DirectoryMode.WORK
                nested.isNotEmpty() && nested.all { child ->
                    isVariant(child.substringAfterLast('/')) ||
                        (result[child] == DirectoryMode.WORK && sameWorkPackage(path.substringAfterLast('/'), child.substringAfterLast('/')))
                } -> DirectoryMode.WORK
                else -> DirectoryMode.CATEGORY
            }
        }
        return result
    }

    private fun sameWorkPackage(parent: String, child: String): Boolean {
        fun clean(value: String) = value.replace(Regex("(?i)(全季|全集|合集|全系列|高码率?|高码|蓝光|超清|高清|[248]k|\\d{3,4}p|ova|oad|hdr|hevc)"), "")
            .replace(Regex("[\\s+._\\-【】\\[\\]（）()]+"), "").lowercase()
        return !isCategoryName(parent) && clean(parent).isNotEmpty() && clean(parent) == clean(child)
    }
}

/** Natural ordering preserves dates/upper/lower/special titles without inventing episode numbers. */
fun compareResourceNames(a: String, b: String): Int {
    val chunks = Regex("\\d+|\\D+")
    val left = chunks.findAll(a.lowercase()).map { it.value }.toList()
    val right = chunks.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]; val y = right[i]
        val c = if (x.first().isDigit() && y.first().isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (c != 0) return c
    }
    return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
}
fun libraryStableId(value: String): String {
    var hash = 1125899906842597L
    value.forEach { hash = 31 * hash + it.code }
    return "alist_${hash.toULong().toString(16)}"
}
