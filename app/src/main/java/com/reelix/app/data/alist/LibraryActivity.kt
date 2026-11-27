package com.reelix.app.data.alist

import java.time.Instant
import java.time.OffsetDateTime

fun libraryModifiedAt(raw: String, now: Long = System.currentTimeMillis()): Long {
    val value = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull() ?: 0L
    return value.coerceAtLeast(0L).coerceAtMost(now)
}

/** Refresh alone is not an update; old caches without discovery times do not jump to today. */
fun LibraryScan.withActivity(previous: LibraryScan?, now: Long): LibraryScan {
    val known = previous?.let { it.videos + it.archivedVideos }.orEmpty().associateBy { it.sourceIdentity to it.path }
    return copy(videos = videos.map { file ->
        val old = known[file.sourceIdentity to file.path]
        if (old == null) file.copy(firstSeenAt = now, changedAt = now)
        else file.copy(firstSeenAt = old.firstSeenAt, changedAt = if (file.sizeBytes != old.sizeBytes ||
            (old.modifiedAt > 0 && file.modifiedAt > 0 && file.modifiedAt != old.modifiedAt)) now else old.changedAt)
    })
}

fun libraryActivityAt(files: List<AlistVideo>): Long = files.maxOfOrNull {
    maxOf(it.modifiedAt, it.firstSeenAt, it.changedAt)
} ?: 0L

fun libraryEntriesByActivity(entries: List<AlistLibraryBuilder.LibraryEntry>, watched: Map<String, Long> = emptyMap()): List<AlistLibraryBuilder.LibraryEntry> =
    entries.sortedWith(compareByDescending<AlistLibraryBuilder.LibraryEntry> { maxOf(it.activityAt, watched[it.item.id] ?: 0L) }
        .thenByDescending { it.files.maxOfOrNull { file -> file.modifiedAt } ?: 0L }
        .thenBy { it.item.title }.thenBy { it.item.id })
