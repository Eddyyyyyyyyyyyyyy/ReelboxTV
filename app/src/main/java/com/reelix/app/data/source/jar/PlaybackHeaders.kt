package com.reelix.app.data.source.jar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun playbackHeaderObject(element: JsonElement?): Map<String, String> {
    val value = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> element.contentOrNull?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        else -> null
    } ?: return emptyMap()
    return value.mapNotNull { (name, content) ->
        (content as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains('\r') && !it.contains('\n') }
            ?.let { name to it }
    }.toMap()
}

internal fun combinePlaybackHeaders(vararg sources: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    sources.forEach { source ->
        source.forEach { (name, value) ->
            headers.keys.firstOrNull { it.equals(name, true) }?.let(headers::remove)
            headers[name] = value
        }
    }
    return headers
}
