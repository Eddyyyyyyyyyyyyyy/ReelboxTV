package com.reelix.app.ui.component

import com.reelix.app.data.library.FileNameParser
import com.reelix.app.data.model.Episode
import java.util.Locale

data class EpisodeCardPresentation(val number: Int?, val resourceName: String, val resolution: String?, val title: String? = null,
    val variants: List<String> = emptyList())

fun hasNamedVersionChoices(episodes: List<Episode>): Boolean = episodes.size > 1 &&
    episodes.distinctBy { it.flag to it.playId }.size == episodes.size &&
    episodes.all {
        val info = episodeCardPresentation(it)
        info.number == null && (info.resolution != null || info.variants.isNotEmpty())
    }

fun episodeResourceName(episode: Episode): String {
    val raw = episode.originalName ?: if (episode.flag == "alist") episode.playId.substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: episode.name else episode.name
    // URL tokens are never a user-facing resource filename.
    return raw.takeUnless { it.trimStart().startsWith("http://", true) || it.trimStart().startsWith("https://", true) }.orEmpty()
}

fun formatMediaSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) String.format(Locale.US, "%.1fGB", mb / 1024)
    else String.format(Locale.US, "%.0fMB", mb)
}

fun episodeCardPresentation(episode: Episode): EpisodeCardPresentation {
    val library = episode.flag == "alist"
    val name = episodeResourceName(episode)
    val displayName = if (!library && episode.originalName == null) name.replace(Regex("^\\d{2,}\\s{2,}"), "") else name
    val parsed = FileNameParser.parse(displayName)
    val stem = displayName.replace(Regex("(?i)\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|flv|rmvb|webm|iso)$"), "")
    val chinese = Regex("第\\s*([0-9零〇一二两三四五六七八九十百]+)\\s*([集话話回期])\\s*([上中下]?)").find(stem)
    val explicit = chinese?.groupValues?.get(1)?.let(::displayNumber)
    val typed = Regex("(?i)\\b(OAD|OVA|SP)\\s*[-_ ]*(\\d{1,3})(?!\\d)").find(stem)
    val cleaned = stem.replace(Regex("\\[[^\\]]*]")) { m ->
        if (m.value.matches(Regex("\\[\\d{1,3}]"))) m.value else " "
    }.replace(Regex("\\([^)]*\\)"), " ")
    val withoutDates = cleaned.replace(Regex("(?:19|20)\\d{2}[._-]\\d{2}[._-]\\d{2}"), " ")
    val bare = Regex("(?:^|[\\s._\\[\\]-])(\\d{1,3})(?=\\s*]?(?:\\s|$))")
        .findAll(withoutDates).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    val number = episode.episodeNumber ?: parsed.episode ?: explicit ?: typed?.groupValues?.get(2)?.toIntOrNull()
        ?: if (library) bare else stem.trim().takeIf { it.matches(Regex("\\d{1,3}")) }?.toIntOrNull()
    val extras = listOfNotNull(
        chinese?.groupValues?.get(3)?.takeIf { it.isNotBlank() },
        Regex("加更|会员版|纯享版|超前营业|番外|预告").find(stem)?.value,
        if (library) Regex("(?:19|20)\\d{2}[._-]\\d{2}[._-]\\d{2}").find(stem)?.value?.replace('_', '.')?.replace('-', '.') else null,
    ).distinct()
    val primary = when {
        chinese != null && number != null -> "第${number}${chinese.groupValues[2]}"
        typed != null -> "${typed.groupValues[1].uppercase()} ${number ?: typed.groupValues[2]}"
        else -> null
    }
    val compact = cleaned.replace(Regex("[._]+"), " ").split(Regex("\\s+"))
        .filterNot { FileNameParser.isTag(it) || it.matches(Regex("(?i)\\d+(fps|bit)")) }
        .joinToString(" ").trim(' ', '-', '_')
    val voice = Regex("(国粤双语|国语|粤语|日语|英语|韩语|台配|多音轨)(?:版)?").find(stem)?.value
    val dynamicRange = Regex("(?i)(?<![a-z0-9])(HDR10\\+?|HDR|Dolby[ ._-]?Vision|DV)(?![a-z0-9])")
        .find(stem)?.value?.uppercase()
    val onlineQuality = when (parsed.resolution?.lowercase()) {
        "2160p", "4k" -> "4K"
        "8k" -> "8K"
        else -> parsed.resolution?.uppercase()
    }
    val title = if (!library) {
        when {
            primary != null -> (listOf(primary) + extras).joinToString("·")
            number != null -> (listOf("第${number}集") + extras).joinToString("·")
            else -> listOfNotNull(onlineQuality ?: dynamicRange, voice).distinct().joinToString("·")
                .ifBlank { compact.ifBlank { stem } }.takeIf { it.isNotBlank() }
        }
    } else if (primary != null) (listOf(primary) + extras).joinToString(" · ")
        else if (number == null) compact.ifBlank { stem }.takeIf { it.isNotBlank() }
        else extras.takeIf { it.isNotEmpty() }?.let { (listOf("第${number}集") + it).joinToString(" · ") }
    val variants = listOfNotNull(voice, dynamicRange).distinct()
    return EpisodeCardPresentation(number, name, parsed.resolution, title, variants)
}

private fun displayNumber(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    var result = 0; var digit = 0
    for (c in text) {
        val value = "零一二三四五六七八九".indexOf(c)
        when {
            value >= 0 -> digit = value
            c == '〇' -> digit = 0
            c == '两' -> digit = 2
            c == '十' || c == '百' -> { result += (if (digit == 0) 1 else digit) * if (c == '十') 10 else 100; digit = 0 }
            else -> return null
        }
    }
    return (result + digit).takeIf { it > 0 }
}
