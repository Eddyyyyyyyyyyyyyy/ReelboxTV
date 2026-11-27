package com.reelix.app.player

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.ui.component.episodeCardPresentation
import com.reelix.app.ui.component.episodeResourceName
import java.util.Locale

data class PlayerHeaderPresentation(
    val title: String,
    /** Full source file name; null when the source gave no usable name. */
    val fileName: String?,
    val episodeLabel: String?,
    val episodeNumber: Int?,
    val resolutionLabel: String?,
)

/**
 * 标题下方第二行：完整文件名优先，其次短集名，最后才是“第 N 集”兜底。
 */
fun PlayerHeaderPresentation.secondaryLine(numberLabel: String?): String? =
    fileName ?: episodeLabel ?: numberLabel

/**
 * 选集面板里每一项的完整标签；按钮本身仍用 shortLabel 显示 1/2/3。
 */
fun playerEpisodeOptionLabel(episode: Episode): String =
    fullSourceFileName(episode) ?: episode.displayName

fun playerHeaderPresentation(
    item: MediaItem,
    episode: Episode?,
    resolutionLabel: String?,
    bitrate: Int? = null,
): PlayerHeaderPresentation {
    val display = episode?.let(::episodeCardPresentation)
    val movie = item.kind == MediaKind.MOVIE
    val label = if (movie && display != null) {
        // Filename quality is a version label, never the measured playback resolution.
        val version = listOfNotNull(
            display.resolution?.let {
                when (it.lowercase(Locale.ROOT)) {
                    "2160p", "4k" -> "4K"
                    else -> it.uppercase(Locale.ROOT)
                }
            },
        ) + display.variants
        version.distinct().joinToString("\u00b7").ifBlank {
            display.title?.takeIf {
                display.number == null &&
                    normalizedHeaderName(it) != normalizedHeaderName(item.title) &&
                    normalizedHeaderName(it) !in genericMovieLabels
            }.orEmpty()
        }.takeIf { it.isNotBlank() }
    } else {
        display?.title?.takeIf { it.isNotBlank() }
    }
    return PlayerHeaderPresentation(
        title = item.title,
        fileName = headerFileName(item, episode),
        episodeLabel = label,
        episodeNumber = display?.number?.takeIf { !movie && label == null },
        resolutionLabel = listOfNotNull(
            resolutionLabel?.takeIf { it.isNotBlank() },
            formatVideoBitrate(bitrate),
        ).joinToString(" · ").ifBlank { null },
    )
}

/**
 * 播放器对外只展示源的完整文件名，短名仅留给详情页卡片内部。
 * 电影文件名与片名完全相同时不重复显示，沿用旧的去重行为。
 */
private fun headerFileName(item: MediaItem, episode: Episode?): String? {
    val raw = episode?.let(::fullSourceFileName) ?: return null
    if (item.kind != MediaKind.MOVIE) return raw
    val stem = raw.replace(mediaExtension, "")
    val normalized = normalizedHeaderName(stem)
    return raw.takeIf { normalized != normalizedHeaderName(item.title) && normalized !in genericMovieLabels }
}

/**
 * 完整源文件名；在线旧缓存没有 originalName 时其 name 已是短名，不能算完整名。
 */
private fun fullSourceFileName(episode: Episode): String? {
    if (!episode.isLibrarySource() && episode.originalName == null) return null
    return episodeResourceName(episode).trim().takeIf { it.isNotEmpty() }
}

private fun Episode.isLibrarySource(): Boolean =
    sourceKey == "alist" || sourceKey == "webdav" || sourceKey == "emby" || flag == "alist"

private val mediaExtension =
    Regex("(?i)\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|flv|rmvb|webm|iso)$")

private fun normalizedHeaderName(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private val genericMovieLabels = setOf(
    "\u6b63\u7247", "\u64ad\u653e", "\u5728\u7ebf\u64ad\u653e", "\u7535\u5f71",
)
