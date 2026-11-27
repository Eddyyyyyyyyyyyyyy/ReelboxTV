package com.reelix.app.debug

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Emulator-only, loopback-only HTTP fixture with a bounded payload rate and byte ranges. */
class LocalMediaFixture(private val file: File) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val url = "http://127.0.0.1:${server.localPort}/fixture.mp4"

    init {
        workers.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { serve(client) }; runCatching { client.close() } }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use { client ->
        client.soTimeout = 10000
        val reader = client.getInputStream().bufferedReader()
        val request = reader.readLine() ?: return
        if (!request.startsWith("GET /fixture.mp4 ")) return
        var start = 0L
        var range = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) {
                start = line.substringAfter("bytes=", "0-").substringBefore('-').toLongOrNull() ?: 0
                range = true
            }
        }
        val output = client.getOutputStream()
        val length = file.length()
        if (start !in 0 until length) {
            output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val header = buildString {
            append(if (range) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
            append("Content-Length: ${length - start}\r\n")
            if (range) append("Content-Range: bytes $start-${length - 1}/$length\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray())
        file.inputStream().use { input ->
            input.channel.position(start)
            val chunk = ByteArray(8192)
            while (!Thread.currentThread().isInterrupted) {
                val count = input.read(chunk)
                if (count < 0) break
                output.write(chunk, 0, count)
                output.flush()
                Thread.sleep(32) // about 250 KiB/s; enough to observe live updates before load completion.
            }
        }
    }

    override fun close() {
        server.close()
        workers.shutdownNow()
    }
}
