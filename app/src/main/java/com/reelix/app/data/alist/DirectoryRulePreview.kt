package com.reelix.app.data.alist

/** Cached grouping only: this helper has no repository, persistence or network access. */
object DirectoryRulePreview {
    data class Resource(val sourceIdentity: String, val path: String)
    data class Poster(val id: String, val title: String, val owner: String, val videoCount: Int)
    data class OwnershipChange(val resource: Resource, val before: Poster, val after: Poster)
    data class Result(
        val path: String,
        val mode: DirectoryMode?,
        val beforePosters: Int,
        val afterPosters: Int,
        val added: List<Poster>,
        val removed: List<Poster>,
        val ownershipChanges: List<OwnershipChange>,
        val unchangedVideos: Int,
        val pathVideos: Int,
        val failures: List<DirectoryFailure>,
        val archivedVideos: Int,
    )

    fun calculate(scan: LibraryScan, rules: Map<String, DirectoryMode>, path: String, mode: DirectoryMode?): Result {
        val normalized = libraryPath(path)
        val proposed = rules.toMutableMap().apply {
            if (mode == null) remove(normalized) else put(normalized, mode)
        }
        val before = AlistLibraryBuilder.build(scan.videos, rules).items
        val after = AlistLibraryBuilder.build(scan.videos, proposed).items
        fun poster(entry: AlistLibraryBuilder.LibraryEntry) =
            Poster(entry.item.id, entry.item.title, entry.item.sourceId, entry.files.size)
        fun owners(entries: List<AlistLibraryBuilder.LibraryEntry>) = buildMap {
            entries.forEach { entry ->
                val owner = poster(entry)
                entry.files.forEach { put(Resource(it.sourceIdentity, it.path), owner) }
            }
        }
        val previousOwners = owners(before)
        val nextOwners = owners(after)
        val changes = previousOwners.mapNotNull { (resource, old) ->
            val next = checkNotNull(nextOwners[resource])
            if (old.id == next.id) null else OwnershipChange(resource, old, next)
        }.sortedWith(compareBy({ it.resource.path }, { it.resource.sourceIdentity }))
        val previousIds = before.map { it.item.id }.toSet()
        val nextIds = after.map { it.item.id }.toSet()
        return Result(normalized, mode, before.size, after.size,
            after.filter { it.item.id !in previousIds }.map(::poster),
            before.filter { it.item.id !in nextIds }.map(::poster), changes,
            previousOwners.size - changes.size, previousOwners.keys.count { underDirectory(it.path, normalized) },
            scan.failures.toList(), scan.archivedVideos.distinctBy { it.sourceIdentity to it.path }.size)
    }
}
