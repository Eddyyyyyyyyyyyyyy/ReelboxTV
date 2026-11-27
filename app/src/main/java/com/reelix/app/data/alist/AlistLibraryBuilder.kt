package com.reelix.app.data.alist

import com.reelix.app.data.library.FileNameParser
import com.reelix.app.data.model.*

/** Directory identity owns membership. Scraping and filename parsing never change it. */
object AlistLibraryBuilder {
    data class Result(val items: List<LibraryEntry>, val skipped: Int)
    data class LibraryEntry(
        val item: MediaItem, val files: List<AlistVideo>, val episodes: List<Episode>,
        val routes: List<PlayRoute> = emptyList(), val legacyIds: Set<String> = emptySet(),
        val season: Int? = null, val directory: String = "", val sourceIdentity: String = "",
        val activityAt: Long = 0L,
    )
    fun build(videos: List<AlistVideo>, rules: Map<String, DirectoryMode> = emptyMap()): Result {
        val unique = videos.distinctBy { it.sourceIdentity to it.path }
        val entries = mutableListOf<LibraryEntry>()
        for ((origin, sourceFiles) in unique.groupBy { it.sourceIdentity to
            it.mountRoot.ifBlank { "/" + it.path.trim('/').substringBefore('/') } }) {
            val source = origin.first
            val mount = origin.second
            val directories = DirectoryRules.directories(sourceFiles)
            val modes = DirectoryRules.modes(sourceFiles, directories, rules)
            val groups = sourceFiles.groupBy { file ->
                val ancestors = mutableListOf<String>()
                var parent = parentDirectory(file.path)
                while (true) { ancestors += parent; if (parent == "/") break; parent = parentDirectory(parent) }
                ancestors.reverse()
                ancestors.firstOrNull { underDirectory(it, mount) && modes[it] == DirectoryMode.WORK } ?: file.path
            }
            for ((owner, files) in groups) {
                val isFile = files.any { it.path == owner }
                val ordered = files.sortedWith { a, b -> compareResourceNames(a.path, b.path) }
                val parsed = ordered.map { FileNameParser.parse(it.name) }
                val folderName = owner.substringAfterLast('/')
                val title = if (isFile) parsed.first().title.ifBlank { folderName.substringBeforeLast('.', folderName) }
                    else folderName.replace(Regex("[（(](?:19|20)\\d{2}[）)]"), "").trim().ifBlank { folderName }
                val folderYear = Regex("(?:19|20)\\d{2}").find(folderName)?.value?.toIntOrNull()
                val year = folderYear ?: parsed.mapNotNull { it.year }.distinct().singleOrNull()
                val numbers = parsed.map { it.episode }
                val keepIndices = numbers.all { it != null && it > 0 } && numbers.distinct().size == numbers.size
                val episodic = numbers.any { it != null } || Regex("第.+季|season", RegexOption.IGNORE_CASE).containsMatchIn(folderName) ||
                    ordered.any { file -> parentDirectory(file.path).split('/').any { librarySectionTag(it) != null } }
                val episodes = ordered.mapIndexed { i, file -> Episode(
                    index = if (keepIndices) numbers[i]!! else i + 1,
                    name = file.name, flag = "alist", playId = file.path, episodeNumber = parsed[i].episode,
                    sizeBytes = file.sizeBytes,
                    sourceKey = file.sourceKey,
                ) }
                val item = MediaItem(id = "alist_dir_" + libraryStableId("directory-v2|$source|$owner").removePrefix("alist_"), title = title,
                    year = year, kind = if (episodic) MediaKind.SERIES else MediaKind.MOVIE,
                    fromLibrary = true,
                    sourceKey = ordered.firstOrNull { it.sourceKey.isNotBlank() }?.sourceKey ?: "alist",
                    sourceId = owner,
                    searchResources = ordered.map { MediaSearchResource(it.name, it.path) })
                entries += LibraryEntry(item, ordered, episodes, directory = if (isFile) parentDirectory(owner) else owner,
                    sourceIdentity = source, activityAt = libraryActivityAt(ordered))
            }
        }
        // Only unambiguous aliases survive a split; an old merged ID must never pick an arbitrary drive.
        val aliases = mutableMapOf<String, MutableSet<String>>()
        val old = LegacyAlistLibraryBuilder.build(unique).items
        val oldByPath = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        old.forEach { legacy -> legacy.files.forEach { file ->
            oldByPath.getOrPut(file.sourceIdentity to file.path) { mutableSetOf() }.addAll(legacy.legacyIds + legacy.item.id)
        } }
        for (entry in entries) {
            val paths = entry.files.map { it.sourceIdentity to it.path }.toSet()
            val ids = paths.flatMap { oldByPath[it].orEmpty() }.toSet()
            ids.forEach { aliases.getOrPut(it) { mutableSetOf() }.add(entry.item.id) }
        }
        return Result(entries.map { entry -> entry.copy(legacyIds = aliases.filterValues { it == setOf(entry.item.id) }.keys) }
            .sortedWith(compareByDescending<LibraryEntry> { it.activityAt }.thenBy { it.item.title }.thenBy { it.item.id }), 0)
    }
    fun toPlayRoutes(entry: LibraryEntry, preferredPath: String = ""): List<PlayRoute> =
        listOf(PlayRoute("片库", entry.episodes, true))
    fun toPlayRoute(entry: LibraryEntry, client: AlistClient): PlayRoute = toPlayRoutes(entry).first()
    fun find(entries: List<LibraryEntry>, item: MediaItem): LibraryEntry? {
        entries.singleOrNull { it.item.id == item.id }?.let { return it }
        if (item.sourceId.isNotBlank()) {
            val candidate = entries.singleOrNull { e -> e.files.any { it.path == item.sourceId } || e.item.sourceId == item.sourceId }
            if (candidate != null) {
                val proven = if (item.id.startsWith("alist_dir_")) identityBelongsTo(candidate.sourceIdentity, item)
                    else legacyIdentityMatches(candidate.files, item.id) ||
                        LegacyAlistLibraryBuilder.build(entries.flatMap { it.files }).items.any { old ->
                            old.item.id == item.id && old.files.any { file -> candidate.files.any {
                                it.sourceIdentity == file.sourceIdentity && it.path == file.path
                            } }
                        }
                if (proven) return candidate
            }
        }
        if (item.id.startsWith("alist_dir_")) return null
        return entries.singleOrNull { item.id in it.legacyIds && legacyIdentityMatches(it.files, item.id) }
    }
    private fun legacyIdentityMatches(files: List<AlistVideo>, id: String): Boolean =
        LegacyAlistLibraryBuilder.build(files).items.any { it.item.id == id }
    private fun identityBelongsTo(source: String, item: MediaItem): Boolean {
        var path = item.sourceId
        while (path.isNotBlank()) {
            val expected = "alist_dir_" + libraryStableId("directory-v2|$source|$path").removePrefix("alist_")
            if (expected == item.id) return true
            if (path == "/") break
            path = parentDirectory(path)
        }
        return false
    }
    fun matches(entry: LibraryEntry, item: MediaItem) = find(listOf(entry), item) != null
    fun historyEpisode(entry: LibraryEntry, oldItem: MediaItem, index: Int, name: String, route: String): Episode? {
        entry.episodes.singleOrNull { it.playId == oldItem.sourceId }?.let { return it }
        if (oldItem.id == entry.item.id) return entry.episodes.singleOrNull { it.index == index }
        entry.episodes.singleOrNull { it.name == name }?.let { return it }
        val legacy = LegacyAlistLibraryBuilder.build(entry.files).items
        val candidates = legacy.filter { oldItem.id == it.item.id || oldItem.id in it.legacyIds ||
            it.files.any { file -> file.path == oldItem.sourceId } }
            .flatMap { old -> LegacyAlistLibraryBuilder.toPlayRoutes(old) }
        val matchingRoute = candidates.filter { it.name == route }.ifEmpty { candidates }
        val paths = matchingRoute.flatMap { it.episodes }.filter { it.index == index }.map { it.playId }.distinct()
        return paths.singleOrNull()?.let { path -> entry.episodes.singleOrNull { it.playId == path } }
    }
    data class Stats(val totalItems: Int, val seriesCount: Int, val movieCount: Int, val episodeCount: Int)
    fun stats(entries: List<LibraryEntry>) = Stats(entries.size, entries.count { it.item.kind == MediaKind.SERIES },
        entries.count { it.item.kind != MediaKind.SERIES }, entries.sumOf { it.episodes.size })
}
