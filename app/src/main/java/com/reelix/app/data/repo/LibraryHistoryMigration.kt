package com.reelix.app.data.repo

import com.reelix.app.data.alist.AlistLibraryBuilder
import com.reelix.app.data.db.PlayProgressEntity
import com.reelix.app.data.model.Episode

fun canResumeEpisode(
    record: PlayProgressEntity,
    episode: Episode?,
    @Suppress("UNUSED_PARAMETER") routeName: String,
    library: Boolean,
): Boolean {
    if (episode == null || record.finished) return false
    return if (library) record.sourceId == episode.playId else
        // 在线源的线路名可能因源配置变化而不同；集号相同仍应恢复该集位置。
        record.episodeIndex == episode.index
}

/** A historical number alone is not evidence when seasons or encodes share that number. */
fun migratedLibraryRecord(entries: List<AlistLibraryBuilder.LibraryEntry>, old: PlayProgressEntity): PlayProgressEntity? {
    if (old.sourceKey.isNotBlank() && old.sourceKey != "alist") return null
    val item = old.toMediaItem()
    val entry = AlistLibraryBuilder.find(entries, item) ?: return null
    val episode = AlistLibraryBuilder.historyEpisode(entry, item, old.episodeIndex, old.episodeName, old.routeName) ?: return null
    val poster = entry.item.poster ?: old.posterUrl
    val backdrop = entry.item.backdrop ?: old.backdropUrl
    if (entry.item.id == old.mediaId && old.sourceId == episode.playId && old.episodeIndex == episode.index &&
        poster == old.posterUrl && backdrop == old.backdropUrl) return null
    return old.copy(mediaId = entry.item.id, title = entry.item.title,
        sourceKey = "alist", sourceId = episode.playId, episodeIndex = episode.index, episodeName = episode.name,
        routeName = "片库", posterUrl = poster, backdropUrl = backdrop,
        year = entry.item.year ?: old.year, kindName = entry.item.kind.label)
}
