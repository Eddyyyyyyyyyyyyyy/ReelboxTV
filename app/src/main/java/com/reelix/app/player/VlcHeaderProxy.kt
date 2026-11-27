package com.reelix.app.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** VLC 3 does not expose arbitrary HTTP request headers. Used only when those headers are required. */
internal class VlcHeaderProxy(
    private val headers: Map<String, String>,
) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString()
    private val executor = Executors.newCachedThreadPool { job -> Thread(job, "ReelixVlcHeaders").apply { isDaemon = true } }
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    private val prefix = "http://127.0.0.1:${server.localPort}/$token/"

    init {
        executor.execute {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                executor.execute {
                    try { socket.use { runCatching { serve(it) } } } finally { sockets -= socket }
                }
            }
        }
    }

    fun url(remote: String): String {
        val uri = URI(remote)
        require(uri.scheme in listOf("http", "https"))
        val origin = Base64.getUrlEncoder().withoutPadding().encodeToString("${uri.scheme}://${uri.rawAuthority}".toByteArray())
        return prefix + origin + uri.rawPath.ifBlank { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 20_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine()?.takeIf { it.length < 16_384 }?.split(' ') ?: return
        if (request.size < 2 || request[0] !in listOf("GET", "HEAD")) return
        val path = request[1].substringBefore('?')
        val marker = "/$token/"
        if (!path.startsWith(marker)) return
        val encoded = path.removePrefix(marker).substringBefore('/')
        val origin = String(Base64.getUrlDecoder().decode(encoded))
        val remote = origin + path.removePrefix(marker + encoded) + request[1].substringAfter('?', "").let { if (it.isBlank()) "" else "?$it" }
        val builder = Request.Builder().url(remote).method(request[0], null)
        headers.filterKeys { it.lowercase() !in setOf("host", "connection", "content-length", "accept-encoding") }
            .forEach { (k, v) -> builder.header(k, v) }
        // Identity prevents body decompression from invalidating byte ranges and response lengths.
        builder.header("Accept-Encoding", "identity")
        var total = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            total += line.length
            require(total < 65_536)
            val name = line.substringBefore(':').trim()
            if (name.lowercase() in setOf("range", "if-range")) builder.header(name, line.substringAfter(':').trim())
        }
        client.newCall(builder.build()).execute().use { response ->
            val output = socket.getOutputStream()
            val body = response.body
            val type = response.header("Content-Type").orEmpty()
            val finalUrl = response.request.url.toString()
            val manifest = response.isSuccessful && (type.contains("mpegurl", true) || type.contains("dash+xml", true) ||
                finalUrl.substringBefore('?').endsWith(".m3u8", true) || finalUrl.substringBefore('?').endsWith(".mpd", true))
            fun sendHead(length: Long?, rewritten: Boolean) {
                val head = buildString {
                    append("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\nConnection: close\r\n")
                    for (name in listOf("Content-Type", "Accept-Ranges", "Content-Range", "ETag", "Last-Modified")) {
                        if (rewritten && name in listOf("Content-Range", "ETag")) continue
                        response.header(name)?.let { append("$name: $it\r\n") }
                    }
                    if (length != null && length >= 0) append("Content-Length: $length\r\n")
                    append("\r\n")
                }
                output.write(head.toByteArray(Charsets.ISO_8859_1))
            }
            if (request[0] == "HEAD") { sendHead(body?.contentLength(), false); return }
            val input = body?.byteStream() ?: run { sendHead(0, false); return }
            // 部分源把 m3u8 当普通文件下发，响应头和地址都看不出是播放列表。
            // 只按头发判断会把列表原样转发，相对分片地址就会被拼到本地代理上而 404。
            val prefix = readPrefix(input, 64 * 1024)
            val looksLikeHls = String(prefix, Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
            if (manifest || looksLikeHls) {
                val bytes = java.io.ByteArrayOutputStream()
                bytes.write(prefix, 0, prefix.size)
                val buffer = ByteArray(16_384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(bytes.size() + n <= 8 * 1024 * 1024)
                    bytes.write(buffer, 0, n)
                }
                val source = bytes.toString("UTF-8")
                val data = runCatching {
                    (if (source.trimStart().startsWith("#EXTM3U")) rewriteHls(source, finalUrl, ::url)
                    else rewriteDash(source, finalUrl, ::url)).toByteArray(Charsets.UTF_8)
                }.getOrNull()
                if (data == null) {
                    // 重写失败就把请求指回原地址，避免相对分片被解析到本地代理上。
                    output.write(
                        ("HTTP/1.1 307 Temporary Redirect\r\nLocation: $finalUrl\r\n" +
                            "Connection: close\r\nContent-Length: 0\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                    )
                } else {
                    sendHead(data.size.toLong(), true)
                    output.write(data)
                }
            } else {
                sendHead(body.contentLength(), false)
                output.write(prefix)
                val buffer = ByteArray(32_768)
                while (!closed) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
            }
            output.flush()
        }
    }

    private fun readPrefix(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(limit)
        val buffer = ByteArray(minOf(8192, limit))
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            if (count == 0) break
        }
        return output.toByteArray()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        // Closing pooled TLS connections can perform socket I/O. Engine release may run
        // during a UI-thread fallback, so cancel and evict on this proxy's worker pool.
        executor.execute {
            runCatching {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
        executor.shutdown()
    }
}

/**
 * Resolve a playlist reference against its base URL.
 *
 * Provider manifests routinely contain references that are legal for them but
 * illegal in a strict URI (spaces, `|`, stray `%`). `URI.resolve` throws on those
 * and used to abort the whole rewrite. OkHttp's `HttpUrl.resolve` percent-encodes
 * them and returns null instead of throwing, which is exactly what we need here.
 */
internal fun resolvePlaylistUrl(base: String, reference: String): String? {
    val ref = reference.trim()
    if (ref.isEmpty() || ref.startsWith("#")) return null
    val baseUrl = base.toHttpUrlOrNull() ?: return null
    return baseUrl.resolve(ref)?.toString()
}

internal fun rewriteHls(source: String, base: String, mapUrl: (String) -> String): String =
    source.lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            !line.trimStart().startsWith('#') ->
                resolvePlaylistUrl(base, line)
                    ?.takeIf { it.startsWith("http", true) }
                    ?.let(mapUrl) ?: line
            else -> Regex("URI=\"([^\"]+)\"").replace(line) { hit ->
                resolvePlaylistUrl(base, hit.groupValues[1])
                    ?.takeIf { it.startsWith("http", true) }
                    ?.let { "URI=\"${mapUrl(it)}\"" } ?: hit.value
            }
        }
    }

internal fun rewriteDash(source: String, base: String, mapUrl: (String) -> String): String {
    require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(source))
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Android's XML parser does not implement every Xerces feature. Reject DTDs above as well.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
    }
    val document = builder.parse(java.io.ByteArrayInputStream(source.toByteArray()))
    fun walk(element: org.w3c.dom.Element, inherited: URI) {
        val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? org.w3c.dom.Element }
        val bases = children.filter { it.localName == "BaseURL" }
        val resolved = bases.firstOrNull()?.textContent?.trim()?.let { inherited.resolve(it) } ?: inherited
        for (child in bases) child.textContent = mapUrl(inherited.resolve(child.textContent.trim()).toString())
        for (attr in listOf("media", "initialization", "index", "sourceURL", "href")) {
            if (element.hasAttribute(attr)) {
                val uri = resolved.resolve(element.getAttribute(attr))
                if (uri.scheme in listOf("http", "https")) element.setAttribute(attr, mapUrl(uri.toString()))
            }
        }
        for (child in children.filter { it.localName != "BaseURL" }) {
            if (child.localName == "Location") child.textContent = mapUrl(resolved.resolve(child.textContent.trim()).toString())
            else walk(child, resolved)
        }
    }
    walk(document.documentElement, URI(base))
    return java.io.StringWriter().also {
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
            javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(it))
    }.toString()
}
