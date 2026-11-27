package com.reelix.app.data.alist

import com.reelix.app.data.model.Episode

enum class LibrarySectionKind { SEASON, CHAPTER, PART, FINAL_SEASON, FINAL_CHAPTER, OAD, OVA, SPECIAL, OTHER }
data class LibrarySectionTag(val kind: LibrarySectionKind, val number: String = "", val part: String = "")
data class LibrarySection(val id: String, val tags: List<LibrarySectionTag>, val paths: Set<String>)

/** Leading numbers order directories; only the explicit Season/Chapter token carries a season number. */
fun librarySectionTag(raw: String): LibrarySectionTag? {
    val name = raw.trim().replace(Regex("^\\d+[.、_\\-]\\s*"), "")
    val part = Regex("(?i)\\bpart[ ._-]*(\\d+)").find(name)?.groupValues?.get(1).orEmpty()
    val numbered = Regex("(?i)^(season[ ._-]*|s)(\\d{1,3})(?=$|[ ._-])").find(name)
    if (numbered != null) return LibrarySectionTag(LibrarySectionKind.SEASON, numbered.groupValues[2], part)
    val chinese = Regex("^第([一二三四五六七八九十百零两0-9]+)(季|章|部)(?:$|[ ._-].*)").find(name)
    if (chinese != null) return LibrarySectionTag(when (chinese.groupValues[2]) {
        "季" -> LibrarySectionKind.SEASON; "章" -> LibrarySectionKind.CHAPTER; else -> LibrarySectionKind.PART
    }, chinese.groupValues[1], part)
    val chapter = Regex("(?i)^(chapter|part)[ ._-]*(\\d+)(?:$|[ ._-].*)").find(name)
    if (chapter != null) return LibrarySectionTag(if (chapter.groupValues[1].equals("chapter", true)) LibrarySectionKind.CHAPTER else LibrarySectionKind.PART, chapter.groupValues[2])
    val kind = when {
        Regex("(?i)^(the[ ._-]+)?final[ ._-]+season(?:$|[ ._-].*)|^(最终季|最終季|终季)(?:$|[ ._-].*)").matches(name) -> LibrarySectionKind.FINAL_SEASON
        Regex("(?i)^(the[ ._-]+)?final[ ._-]+chapter[s]?(?:$|[ ._-].*)|^(终章|終章|最终章)(?:$|[ ._-].*)").matches(name) -> LibrarySectionKind.FINAL_CHAPTER
        Regex("(?i)^oad(?:$|[ ._-].*)").matches(name) -> LibrarySectionKind.OAD
        Regex("(?i)^ova(?:$|[ ._-].*)").matches(name) -> LibrarySectionKind.OVA
        Regex("(?i)^(specials?|extras|特别篇|特典|番外|花絮)(?:$|[ ._-].*)").matches(name) -> LibrarySectionKind.SPECIAL
        else -> return null
    }
    return LibrarySectionTag(kind, part = part)
}

/** Unknown files remain reachable as Other, but never create a directory chooser by themselves. */
fun librarySections(root: String, episodes: List<Episode>): List<LibrarySection> {
    val prefix = libraryPath(root).trimEnd('/') + "/"
    val grouped = episodes.groupBy { ep ->
        if (!ep.playId.startsWith(prefix)) emptyList() else parentDirectory(ep.playId).removePrefix(prefix)
            .split('/').mapNotNull(::librarySectionTag)
    }
    if (grouped.keys.none { it.isNotEmpty() }) return emptyList()
    return grouped.map { (tags, files) -> LibrarySection(
        id = tags.joinToString("|") { "${it.kind}:${it.number}:${it.part}" }.ifBlank { "other" },
        tags = tags.ifEmpty { listOf(LibrarySectionTag(LibrarySectionKind.OTHER)) }, paths = files.map { it.playId }.toSet()) }
}
