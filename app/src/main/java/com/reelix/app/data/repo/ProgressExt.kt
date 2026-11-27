package com.reelix.app.data.repo

import com.reelix.app.data.db.PlayProgressEntity
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind

/**
 * 进度记录 → 媒体条目。
 *
 * 为什么需要这层转换：
 *  "继续观看"的卡片只需要 4 个信息：片名、封面、进度、在哪一集。
 *  直接从进度表拼出一个最小可用的 [MediaItem]，
 *  避免为了渲染首页再去回查源数据（网络源会很慢）。
 *
 * 点击时的行为：
 *  首页点"继续观看" → 进播放页续播，不经过详情页。
 *  所以这里 mediaId 必须原样保留，播放页靠它查进度。
 */
fun PlayProgressEntity.toMediaItem(): MediaItem = com.reelix.app.data.image.OnlineMetadataStore.apply(
    com.reelix.app.data.image.OnlineArtworkOverrides.apply(MediaItem(
    id = mediaId,
    title = title,
    poster = posterUrl,
    posterRes = posterRes,
    kind = com.reelix.app.data.model.MediaKind.fromLabel(kindName),
    progress = progress,
    lastEpisode = episodeIndex,
    // Hero 用的元信息
    originalTitle = originalTitle,
    year = year,
    rating = rating,
    overview = overview,
    backdrop = backdropUrl,
    // 来源必须原样带回去：首页点历史要能回到当初那个源。
    // 老记录（v1 时代）这两个字段是空串，行为退化成"按 mediaId 猜"。
    sourceKey = sourceKey,
    sourceId = sourceId,
    // WebDAV and Emby are library sources too; treating them as online would send
    // them down the TMDB-scrape and online-detail paths.
    fromLibrary = sourceKey.isBlank() || sourceKey in LIBRARY_SOURCE_KEYS,
)))

/** History is a snapshot; apply the current library/online artwork before rendering it. */
fun PlayProgressEntity.withEffectiveArtwork(repo: LibraryRepository): PlayProgressEntity {
    val item = repo.effectiveArtwork(toMediaItem())
    return copy(posterUrl = item.poster, posterRes = item.posterRes, backdropUrl = item.backdrop)
}

/** Sources that belong to the private library rather than an online TVBox site. */
val LIBRARY_SOURCE_KEYS = setOf("alist", "webdav", "emby")

/**
 * Sources whose drive API supports deleting files. Emby stays read-only and online
 * sites are never touched, so long-press deletion is limited to these.
 */
fun librarySourceSupportsDelete(sourceKey: String): Boolean =
    sourceKey == "alist" || sourceKey == "webdav"
