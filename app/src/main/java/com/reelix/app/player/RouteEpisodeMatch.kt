package com.reelix.app.player

import com.reelix.app.data.model.Episode
import java.text.Normalizer
import java.util.Locale

sealed interface RouteEpisodeMatch {
    data class Unique(val episode: Episode) : RouteEpisodeMatch
    data object Absent : RouteEpisodeMatch
    data class Ambiguous(val candidates: List<Episode>) : RouteEpisodeMatch
}

/**
 * Only compares labels inside the same work. index/episodeNumber are presentation or storage
 * positions, never cross-route evidence. Unknown identity is deliberately not a first-item fallback.
 */
fun matchRouteEpisode(source: Episode, candidates: List<Episode>): RouteEpisodeMatch {
    val identity = routeIdentity(source) ?: return RouteEpisodeMatch.Absent
    val matches = candidates.filter { routeIdentity(it) == identity }
    return when (matches.size) {
        0 -> RouteEpisodeMatch.Absent
        1 -> RouteEpisodeMatch.Unique(matches.single())
        else -> RouteEpisodeMatch.Ambiguous(matches)
    }
}

private data class RouteIdentity(
    val season: Int?,
    val serial: Int?,
    val unit: String?,
    val date: String?,
    val parts: List<String>,
    val specials: List<String>,
    val title: String?,
)

private const val NUMBER = "[0-9零〇一二两三四五六七八九十百]+"
private val seasonToken = Regex("第\\s*($NUMBER)\\s*季|(?<![a-z])(?:season\\s*|s)(\\d{1,3})(?!\\d)")
private val serialToken = Regex("第\\s*($NUMBER)\\s*([集话話回期])(?![a-z])|(?<![a-z])(?:episode\\s*|ep?\\s*)(\\d{1,4})(?![a-z0-9])")
private val dateToken = Regex("(?<!\\d)((?:19|20)\\d{2})[年._ /-]?(\\d{2})[月._ /-]?(\\d{2})日?(?!\\d)")
private val specialToken = Regex(
    "特别篇|特别版|特別篇|番外篇?|特辑|特輯|预告片?|預告片?|花絮|彩蛋|加更|纯享版?|純享版?|会员版?|會員版?|超前营业|" +
        "解说版?|解說版?|抢先版?|搶先版?|导演剪辑版?|導演剪輯版?|未删减版?|未刪減版?|合集|总集篇|總集篇|" +
        "(?<![a-z])(?:ova|oad|sp|special|trailer)(?![a-z])")
private val qualityToken = Regex(
    "(?<![a-z0-9])(?:\\d{3,4}p|[248]k|uhd|fhd|hd|sd|hdr10\\+?|hdr|dv|dolby[ ._-]?vision|" +
        "web[ ._-]?dl|webrip|bluray|blu[ ._-]?ray|h[ ._-]?26[45]|hevc|avc)(?![a-z0-9])|" +
        "国粤双语|国语|粤语|日语|英语|韩语|台配|多音轨|中字|双语|高清|超清|蓝光")
private val partToken = Regex(
    "[上中下](?:半(?:[场場期集部篇])?|[期集部篇])|[上中下](?=\\s|[\\p{Punct}】]|$)|" +
        "(?<![a-z])(?:upper|middle|lower)(?![a-z])|(?<![a-z])part\\s*(\\d+)(?![a-z0-9])")
private val extensionToken = Regex("\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|flv|rmvb|webm|iso)$")
private val rangeOrDecimalToken = Regex("$NUMBER\\s*[-~至]\\s*$NUMBER|\\d\\.\\d")
private val unknownPartToken = Regex("[上中下]|(?<![a-z])part")
private val bracketNumberToken = Regex("[（(\\[]\\s*$NUMBER\\s*[）)\\]]")
private val punctuationToken = Regex("[\\s\\p{Punct}【】]+")
private val bareSerialToken = Regex("\\d{1,3}")
private val genericTitles = setOf("正片", "播放", "全集", "完整版", "电影", "movie", "full")
private val chineseNumberToken = Regex(
    "零|〇|[零〇]*[一二两三四五六七八九]|[一二两三四五六七八九]?十[一二两三四五六七八九]?|" +
        "[一二两三四五六七八九]百(?:[零〇][一二两三四五六七八九]|[一二两三四五六七八九]十[一二两三四五六七八九]?)?")

private fun routeIdentity(episode: Episode): RouteIdentity? {
    // A cleaned name may have a synthetic number; an available original label owns the evidence.
    var text = Normalizer.normalize(episode.originalName ?: episode.name, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim()
        .replace(extensionToken, "")
    if (text.isBlank() || text.startsWith("http://") || text.startsWith("https://")) return null
    text = text.replace(qualityToken, " ")
    val dates = dateToken.findAll(text).toList()
    if (dates.size > 1) return null
    val date = dates.singleOrNull()?.let {
        val year = it.groupValues[1].toInt()
        val month = it.groupValues[2].toInt()
        val day = it.groupValues[3].toInt()
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (month !in 1..12 || day !in 1..days[month - 1]) return null
        "%04d%02d%02d".format(Locale.ROOT, year, month, day)
    }
    text = text.replace(dateToken, " ")
    if (rangeOrDecimalToken.containsMatchIn(text)) return null
    val seasons = seasonToken.findAll(text).toList()
    if (seasons.size > 1) return null
    val season = seasons.singleOrNull()?.let {
        labelNumber(it.groupValues[1].ifBlank { it.groupValues[2] }) ?: return null
    }
    text = text.replace(seasonToken, " ")
    val serials = serialToken.findAll(text).toList()
    if (serials.size > 1) return null
    val serialMatch = serials.singleOrNull()
    var serial = serialMatch?.let {
        labelNumber(it.groupValues[1].ifBlank { it.groupValues[3] }) ?: return null
    }
    var unit = serialMatch?.let { if (it.groupValues[2] == "期") "issue" else "episode" }
    text = text.replace(serialToken, " ")
    val specials = specialToken.findAll(text).map {
        when (it.value) {
            "特別篇", "特别版", "特辑", "特輯", "special", "sp" -> "特别篇"
            "番外篇" -> "番外"
            "预告片", "預告片", "預告", "trailer" -> "预告"
            "純享版", "純享", "纯享版" -> "纯享"
            "會員版", "會員", "会员版" -> "会员"
            else -> it.value
        }
    }.toList().sorted()
    text = text.replace(specialToken, " ")
    val parts = partToken.findAll(text).map {
        when {
            it.groupValues[1].isNotEmpty() -> "part:${it.groupValues[1].trimStart('0').ifBlank { "0" }}"
            it.value.startsWith("上") || it.value == "upper" -> "upper"
            it.value.startsWith("下") || it.value == "lower" -> "lower"
            else -> "middle"
        }
    }.toList()
    if (parts.size > 1) return null
    text = text.replace(partToken, " ")
    if ((serialMatch != null || date != null) &&
        unknownPartToken.containsMatchIn(text)) return null
    if (bracketNumberToken.containsMatchIn(text)) return null
    val residue = text.replace(punctuationToken, "")
    if (serial == null && date == null && residue.matches(bareSerialToken)) {
        serial = residue.toInt()
        unit = "episode"
    }
    // Unsupported numeric forms (ranges, fractional episodes, partial dates) cannot become
    // an exact-title escape hatch. Specials retain residual titles to avoid conflating extras.
    if (serial == null && date == null && residue.any { it.isDigit() }) return null
    if ((serialMatch != null || date != null) && residue.any { it.isDigit() }) return null
    val title = if (serial == null && date == null || specials.isNotEmpty()) residue else null
    if (serial == null && date == null &&
        (title.isNullOrBlank() || title in genericTitles)) return null
    return RouteIdentity(season, serial, unit, date, parts, specials, title)
}

private fun labelNumber(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    if (!chineseNumberToken.matches(text)) return null
    var total = 0
    var digit = 0
    for (char in text) {
        val value = "零一二三四五六七八九".indexOf(char)
        when {
            value >= 0 -> digit = value
            char == '〇' -> digit = 0
            char == '两' -> digit = 2
            char == '十' || char == '百' -> {
                total += (if (digit == 0) 1 else digit) * if (char == '十') 10 else 100
                digit = 0
            }
            else -> return null
        }
    }
    return total + digit
}
