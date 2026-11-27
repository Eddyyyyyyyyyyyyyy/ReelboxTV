package com.reelix.app.player

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Per-playback HLS-only loopback proxy. It forwards headers/ranges, removes explicitly
 * cue-marked ad breaks, rewrites nested playlist URIs and strips image wrappers only
 * when a multi-packet MPEG-TS sync pattern confirms the payload.
 */
internal class HlsStreamProxy(private val headers: Map<String, String>) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "ReelixHlsProxy").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val servedCount = java.util.concurrent.atomic.AtomicInteger()
    private val urlLock = Any()
    private val urls = LinkedHashMap<String, String>(256, 0.75f, true)
    private val reverseUrls = HashMap<String, String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    @Volatile private var closed = false
    private val prefix = "http://127.0.0.1:${server.localPort}/$token/"

    init {
        executor.execute {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                executor.execute {
                    try { socket.use { runCatching { serve(it) } } }
                    finally { sockets -= socket }
                }
            }
        }
    }

    fun url(remoteUrl: String): String {
        // 地址已由 OkHttp 规范化过；这里只做协议兜底，避免严格 URI 解析在含特殊字符时抛异常。
        require(remoteUrl.startsWith("http://", true) || remoteUrl.startsWith("https://", true))
        val key = synchronized(urlLock) {
            reverseUrls[remoteUrl]?.let { existing ->
                urls[existing]
                return prefix + existing
            }
            val created = UUID.randomUUID().toString()
            reverseUrls[remoteUrl] = created
            urls[created] = remoteUrl
            while (urls.size > MAX_URLS) {
                val eldest = urls.entries.first()
                urls.remove(eldest.key)
                reverseUrls.remove(eldest.value)
            }
            created
        }
        return prefix + key
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 35_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine()?.takeIf { it.length < 16_384 }?.split(' ') ?: return
        if (requestLine.size < 2 || requestLine[0] !in setOf("GET", "HEAD")) return
        val path = requestLine[1].substringBefore('?')
        val marker = "/$token/"
        if (!path.startsWith(marker)) return
        val key = path.removePrefix(marker)
        val remoteUrl = synchronized(urlLock) { urls[key] } ?: run {
            android.util.Log.w(
                "ReelixHlsProxy",
                "unknown key ${key.take(40)} registered=${synchronized(urlLock) { urls.size }} path=${path.take(160)}",
            )
            writeError(socket, 404, "Not Found")
            return
        }

        val forwarded = LinkedHashMap<String, String>()
        headers.filterKeys { it.lowercase() !in HOP_BY_HOP_HEADERS }.forEach { (name, value) ->
            forwarded[name] = value
        }
        forwarded["Accept-Encoding"] = "identity"
        var headerBytes = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            headerBytes += line.length
            require(headerBytes <= MAX_REQUEST_HEADER_BYTES)
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (name.lowercase() in FORWARDED_REQUEST_HEADERS) {
                forwarded[name] = line.substring(colon + 1).trim()
            }
        }

        val upstreamRequest = Request.Builder().url(remoteUrl).method(requestLine[0], null).apply {
            forwarded.forEach { (name, value) -> header(name, value) }
        }.build()
        client.newCall(upstreamRequest).execute().use { response ->
            val body = response.body
            val output = socket.getOutputStream()
            val contentType = response.header("Content-Type").orEmpty()
            val finalUrl = response.request.url.toString()
            if (!response.isSuccessful) {
                android.util.Log.w(
                    "ReelixHlsProxy",
                    "upstream ${response.code} for ${remoteUrl.take(300)} (final=${finalUrl.take(300)})",
                )
            }
            val hasRange = forwarded.keys.any { it.equals("range", true) }

            fun sendHead(type: String, length: Long?, stripRangeMetadata: Boolean = false) {
                val out = buildString {
                    append("HTTP/1.1 ${response.code} ${response.message.ifBlank { "OK" }}\r\n")
                    append("Connection: close\r\n")
                    listOf("Accept-Ranges", "Content-Range", "ETag", "Last-Modified", "Cache-Control", "Expires")
                        .forEach { name ->
                            if (stripRangeMetadata && name in setOf("Content-Range", "ETag")) return@forEach
                            response.header(name)?.let { append("$name: $it\r\n") }
                        }
                    response.headers("Set-Cookie").forEach { append("Set-Cookie: $it\r\n") }
                    if (type.isNotBlank()) append("Content-Type: $type\r\n")
                    if (length != null && length >= 0) append("Content-Length: $length\r\n")
                    append("\r\n")
                }
                output.write(out.toByteArray(Charsets.ISO_8859_1))
            }

            if (requestLine[0] == "HEAD") {
                sendHead(contentType, body?.contentLength())
                return
            }
            val input = body?.byteStream() ?: run { sendHead(contentType, 0); return }
            // 有的源把 m3u8 当普通文件下发（Content-Type 是 octet-stream，地址也可能重定向到无后缀路径）。
            // 只看响应头会把播放列表原样转发出去，里面的相对分片地址就会被解析到本地代理上而 404，
            // 所以这里按内容开头判断：以 #EXTM3U 开头的一律当播放列表重写。
            val prefixBytes = readPrefix(input, TS_SCAN_BYTES)
            if (servedCount.getAndIncrement() < 8) {
                android.util.Log.i(
                    "ReelixHlsProxy",
                    "resp code=${response.code} ct=${contentType.take(40)} len=${body?.contentLength()} " +
                        "range=$hasRange head=${String(prefixBytes.take(24).toByteArray(), Charsets.ISO_8859_1)}",
                )
            }
            val looksLikePlaylist = response.isSuccessful &&
                prefixBytes.toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U")
            if (looksLikePlaylist && hasRange) {
                // A rewritten playlist has a different length, so it cannot answer a byte range.
                // Let the client read the original directly; relative URIs then resolve against
                // the origin instead of this proxy.
                writeRedirect(output, finalUrl)
                return
            }
            val isPlaylist = looksLikePlaylist
            if (isPlaylist) {
                val restBytes = readCapped(input, MAX_MANIFEST_BYTES - prefixBytes.size)
                if (restBytes == null) {
                    writeRedirect(output, finalUrl)
                    return
                }
                val bytes = prefixBytes + restBytes
                val source = bytes.toString(Charsets.UTF_8)
                val attempt = runCatching {
                    val withoutCueAds = removeCueMarkedAds(source)
                    rewriteHls(withoutCueAds, finalUrl, ::url).toByteArray(Charsets.UTF_8)
                }
                val rewritten = attempt.getOrNull()
                android.util.Log.i(
                    "ReelixHlsProxy",
                    "manifest ${finalUrl.take(120)} in=${bytes.size}B out=${rewritten?.size ?: -1}B",
                )
                if (rewritten == null) {
                    // Passing the original bytes through would leave its relative segment URIs
                    // pointing at this proxy. Redirect instead so the player reads the playlist
                    // from the origin, where relative URIs resolve correctly.
                    android.util.Log.w(
                        "ReelixHlsProxy",
                        "manifest 重写失败，改为直连原始地址",
                        attempt.exceptionOrNull(),
                    )
                    writeRedirect(output, finalUrl)
                } else {
                    sendHead("application/vnd.apple.mpegurl", rewritten.size.toLong(), stripRangeMetadata = true)
                    output.write(rewritten)
                }
                return
            }

            // Do not rewrite byte-range responses: their offsets refer to the original resource.
            val strip = if (response.code == 200 && !hasRange)
                findImageWrappedTsPayload(prefixBytes, contentType) else null
            if (strip != null) {
                val length = body.contentLength().takeIf { it >= 0 }?.minus(strip)?.coerceAtLeast(0)
                sendHead("video/mp2t", length, stripRangeMetadata = true)
                output.write(prefixBytes, strip, prefixBytes.size - strip)
            } else {
                sendHead(contentType, body.contentLength())
                output.write(prefixBytes)
            }
            val buffer = ByteArray(32 * 1024)
            while (!closed) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        synchronized(urlLock) { urls.clear(); reverseUrls.clear() }
        // OkHttp may close TLS sockets while evicting the pool. Engine release can run on
        // the UI thread during a fallback, so keep all OkHttp cleanup on this proxy's IO pool.
        executor.execute {
            runCatching {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
        executor.shutdown()
    }

    private fun writeError(socket: Socket, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code $message\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun readPrefix(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(limit)
        val buffer = ByteArray(minOf(8192, limit))
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            if (count == 0) break
        }
        return output.toByteArray()
    }

    private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeRedirect(output: java.io.OutputStream, destination: String) {
        output.write("HTTP/1.1 307 Temporary Redirect\r\nLocation: $destination\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun removeCueMarkedAds(source: String): String {
        val lines = source.lineSequence().toList()
        val cueOutCount = lines.count { it.trimStart().startsWith("#EXT-X-CUE-OUT:", true) || it.trim().equals("#EXT-X-CUE-OUT", true) }
        val cueInCount = lines.count { it.trim().equals("#EXT-X-CUE-IN", true) }
        // An unmatched marker may be a provider-specific annotation; keep the playlist intact.
        if (cueOutCount == 0 || cueOutCount != cueInCount) return source

        val result = ArrayList<String>(lines.size)
        var inAd = false
        for (line in lines) {
            val trimmed = line.trim()
            val starts = trimmed.startsWith("#EXT-X-CUE-OUT:", true) || trimmed.equals("#EXT-X-CUE-OUT", true)
            val ends = trimmed.equals("#EXT-X-CUE-IN", true)
            when {
                starts -> inAd = true
                ends -> {
                    inAd = false
                    if (result.lastOrNull()?.trim() != "#EXT-X-DISCONTINUITY") result += "#EXT-X-DISCONTINUITY"
                }
                inAd -> Unit
                else -> result += line
            }
        }
        return result.joinToString("\n")
    }

    companion object {
        private const val MAX_URLS = 4096
        private const val MAX_REQUEST_HEADER_BYTES = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        private const val TS_SCAN_BYTES = 64 * 1024
        private val HOP_BY_HOP_HEADERS = setOf("host", "connection", "content-length", "accept-encoding", "proxy-connection")
        private val FORWARDED_REQUEST_HEADERS = setOf("range", "if-range", "if-none-match", "if-modified-since")
        private val packetSizes = intArrayOf(188, 192, 204)

        private fun findImageWrappedTsPayload(prefix: ByteArray, contentType: String): Int? {
            val imageType = contentType.substringBefore(';').trim().startsWith("image/", true)
            val hasImageSignature = isPng(prefix) || isJpeg(prefix) || isGif(prefix)
            if (!imageType && !hasImageSignature) return null
            if (!hasImageSignature || prefix.isEmpty()) return null

            for (offset in 1 until prefix.size) {
                for (packetSize in packetSizes) {
                    val syncOffset = if (packetSize == 192) offset + 4 else offset
                    if (hasTsSync(prefix, syncOffset, packetSize, requiredPackets = 5)) return offset
                }
            }
            return null
        }

        private fun hasTsSync(bytes: ByteArray, offset: Int, stride: Int, requiredPackets: Int): Boolean {
            if (offset < 0 || offset + (requiredPackets - 1) * stride >= bytes.size) return false
            repeat(requiredPackets) { packet ->
                if ((bytes[offset + packet * stride].toInt() and 0xff) != 0x47) return false
            }
            return true
        }

        private fun isPng(bytes: ByteArray) = bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte()
        private fun isJpeg(bytes: ByteArray) = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        private fun isGif(bytes: ByteArray) = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
    }
}
