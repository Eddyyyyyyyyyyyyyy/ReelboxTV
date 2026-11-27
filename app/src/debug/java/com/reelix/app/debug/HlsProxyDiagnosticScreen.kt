package com.reelix.app.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelix.app.player.HlsStreamProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Exercises HLS rewriting and image-wrapped TS stripping using only emulator loopback. */
@Composable
fun HlsProxyDiagnosticScreen() {
    var report by remember { mutableStateOf("正在运行本地 HLS 代理检查…") }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { runHlsProxyCheck() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("HLS 代理模拟器检查", style = MaterialTheme.typography.headlineMedium)
            Text(report, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun runHlsProxyCheck(): String = runCatching {
    val headers = mapOf("Cookie" to "fixture=ok")
    LoopbackHlsFixture().use { fixture ->
        HlsStreamProxy(headers).use { proxy ->
            val client = OkHttpClient()
            val manifest = client.newCall(Request.Builder().url(proxy.url(fixture.playlistUrl)).build())
                .execute().use { response ->
                    check(response.isSuccessful) { "playlist HTTP ${response.code}" }
                    response.body?.string().orEmpty()
                }
            check("ad.ts" !in manifest && "#EXT-X-CUE-OUT" !in manifest && "#EXT-X-CUE-IN" !in manifest) {
                "cue 广告片段未被移除"
            }
            val segmentUrl = manifest.lineSequence().map(String::trim)
                .first { it.startsWith("http://127.0.0.1:") }
            val segment = client.newCall(Request.Builder().url(segmentUrl).build())
                .execute().use { response ->
                    check(response.isSuccessful) { "segment HTTP ${response.code}" }
                    check(response.header("Content-Type").orEmpty().contains("video/mp2t", true)) {
                        "伪装 TS 的图片响应未改为 video/mp2t"
                    }
                    response.body?.bytes() ?: error("segment body missing")
                }
            check(segment.size == 188 * 5 && segment[0] == 0x47.toByte()) { "TS 图片头未完整剥离" }
            check(fixture.requestedPaths().containsAll(setOf("/live/playlist.m3u8", "/live/media.ts")) &&
                "/live/ad.ts" !in fixture.requestedPaths()) { "相对 URI 解析或广告段过滤不正确" }
            check(fixture.allRequestsHadCookie()) { "代理未转发 Cookie 请求头" }
            "通过：m3u8 重写、cue 广告段移除、相对 URI、图片头伪装 TS 校正、Cookie 转发。\n" +
                "本检查验证代理协议处理；样例 TS 不含可解码的视频轨道，不代表实际频道播放成功。"
        }
    }
}.getOrElse { failure ->
    "失败：${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
}

private class LoopbackHlsFixture : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    val playlistUrl = "http://127.0.0.1:${server.localPort}/live/playlist.m3u8"
    private val wrappedTs = createWrappedTs()
    private val manifest = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-CUE-OUT:8
#EXTINF:4,
ad.ts
#EXT-X-CUE-IN
#EXTINF:4,
media.ts
#EXT-X-ENDLIST
""".trimIndent().toByteArray(Charsets.UTF_8)

    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { serve(socket) } }
            }
        }
    }

    private fun requestSnapshot() = synchronized(requests) { requests.toList() }
    fun requestedPaths(): Set<String> = requestSnapshot().map { it.first }.toSet()
    fun allRequestsHadCookie(): Boolean = requestSnapshot().isNotEmpty() &&
        requestSnapshot().all { it.second == "fixture=ok" }

    private fun serve(socket: Socket) = socket.use { client ->
        client.soTimeout = 5000
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        var cookie: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Cookie:", true)) cookie = line.substringAfter(':').trim()
        }
        requests += path to cookie
        val (type, body) = when (path) {
            "/live/playlist.m3u8" -> "application/vnd.apple.mpegurl" to manifest
            "/live/media.ts" -> "image/jpeg" to wrappedTs
            else -> {
                reply(client, 404, "text/plain", "not found".toByteArray())
                return
            }
        }
        if (cookie != "fixture=ok") {
            reply(client, 403, "text/plain", "missing cookie".toByteArray())
        } else reply(client, 200, type, body)
    }

    private fun reply(socket: Socket, status: Int, type: String, body: ByteArray) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status ${if (status == 200) "OK" else if (status == 403) "Forbidden" else "Not Found"}\r\n".toByteArray())
        output.write("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    override fun close() {
        runCatching { server.close() }
        workers.shutdownNow()
    }

    private fun createWrappedTs(): ByteArray {
        val imageHeader = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(29) { 0x11 }
        val packets = ByteArray(188 * 5) { 0x00 }
        repeat(5) { packet -> packets[packet * 188] = 0x47 }
        return imageHeader + packets
    }
}
