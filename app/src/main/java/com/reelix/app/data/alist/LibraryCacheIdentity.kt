package com.reelix.app.data.alist

import java.net.URI

internal fun normalizeLibraryServer(value: String): String {
    val raw = value.trim().trimEnd('/')
    val url = if (raw.contains("://")) raw else "http://$raw"
    return runCatching {
        val uri = URI(url)
        val scheme = uri.scheme.lowercase()
        val port = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        URI(scheme, uri.userInfo, uri.host.lowercase(), port, uri.path.trimEnd('/'), uri.query, null).toString()
    }.getOrDefault(url)
}

internal fun normalizeLibraryRoot(value: String): String = "/" + value.trim().trim('/')
