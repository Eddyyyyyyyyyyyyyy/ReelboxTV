package com.reelix.app.data.source.jar

import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.TvBoxParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class PlaybackPageRequest(val url: String, val headers: Map<String, String>)

fun isDirectPlayback(resolution: PlayResolution): Boolean =
    resolution.playUrl.isNullOrBlank() &&
        (resolution.direct || (!resolution.requiresParsing && SniffRules.looksLikeMedia(resolution.url)))

internal fun parserRequestHeaders(parser: TvBoxParse): Map<String, String> {
    val extra = parser.ext?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val agent = parser.ua?.takeIf { it.isNotBlank() }?.let { mapOf("User-Agent" to it) }.orEmpty()
    return combinePlaybackHeaders(agent, playbackHeaderObject(extra?.get("header")))
}

fun playbackWebRequests(
    resolution: PlayResolution,
    parsers: List<TvBoxParse>,
    lineFlag: String,
): List<PlaybackPageRequest> = buildList {
    resolution.playUrl?.takeIf { it.isNotBlank() }?.let { prefix ->
        val url = if (prefix.contains("{url}")) prefix.replace("{url}", java.net.URLEncoder.encode(resolution.url, "UTF-8"))
            else prefix + resolution.url
        add(PlaybackPageRequest(url, resolution.headers))
    }
    parsers.filter { it.type == 0 && it.url.isNotBlank() && (it.flag.isEmpty() || lineFlag in it.flag) }
        .sortedByDescending { it.name == resolution.parserName }
        .take(2)
        .forEach { parser ->
            add(PlaybackPageRequest(ParseExecutor.buildUrl(parser, resolution.url), parserRequestHeaders(parser)))
        }
    add(PlaybackPageRequest(resolution.url, resolution.headers))
}.distinctBy { it.url }
