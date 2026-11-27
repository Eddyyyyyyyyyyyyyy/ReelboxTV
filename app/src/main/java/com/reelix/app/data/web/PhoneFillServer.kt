package com.reelix.app.data.web

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 极简内嵌 HTTP 服务 —— 只服务于「手机扫码填写设置」这一件事。
 *
 * ## 为什么不引 NanoHTTPD
 *
 * 需求只有两个接口：
 * ```
 * GET  /            → 返回一个 HTML 表单页
 * POST /save        → 接收表单，回调给上层
 * ```
 * 为这点东西引一个 3 万行的依赖，违背项目「不加依赖前先问：不加会死吗」的原则。
 * 手写 200 行够用，而且完全可控。
 *
 * ## 安全考量
 *
 * ```
 * ① 只在局域网监听（绑定 0.0.0.0 但只用于内网地址）
 * ② 每次启动生成一次性 token，URL 里带 ?t=xxx
 *    没 token 的请求一律 403 —— 防止同网段的其他设备误入
 * ③ 填完自动关闭服务，端口不常驻
 * ```
 *
 * ## 生命周期
 *
 * ```
 * 用户点「用手机填写」
 *   → start()  分配端口、生成 token、返回可扫码的 URL
 *   → 手机打开填表 → POST /save
 *   → onSave 回调 → 自动 stop()
 * 用户按 BACK 取消
 *   → stop()
 * ```
 */
class PhoneFillServer(
    /** 端口从 8080 起找空位 */
    private val startPort: Int = 8080,
) {
    private val TAG = "PhoneFillServer"

    private var serverSocket: ServerSocket? = null
    private var running = AtomicBoolean(false)

    /** 一次性令牌 */
    private var token: String = ""

    /** 实际监听的端口 */
    var port: Int = 0
        private set

    /**
     * 启动服务。
     *
     * @param fields 要填的字段（id → 显示名 + 当前值）
     * @param onSave 手机提交后的回调
     * @return 可扫码的完整 URL；启动失败返回 null
     */
    fun start(
        fields: List<FillField>,
        onSave: (Map<String, String>) -> Unit,
    ): String? {
        if (running.get()) return buildUrl()

        token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        // 找空端口
        var socket: ServerSocket? = null
        for (p in startPort..(startPort + 20)) {
            socket = runCatching { ServerSocket() }.getOrNull() ?: break
            val bound = runCatching {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(p), 8)
                true
            }.getOrDefault(false)

            if (bound) {
                port = p
                break
            }
            runCatching { socket.close() }
            socket = null
        }

        if (socket == null) {
            Log.e(TAG, "找不到可用端口")
            return null
        }

        serverSocket = socket
        running.set(true)

        thread(name = "phone-fill-server", isDaemon = true) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { handle(client, fields, onSave) }
                        .onFailure { Log.d(TAG, "请求处理失败: ${it.message}") }
                }
            }
        }

        Log.i(TAG, "已启动 http://0.0.0.0:$port token=$token")
        return buildUrl()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "已停止")
    }

    fun isRunning(): Boolean = running.get()

    /** 本机在局域网里的地址，用于拼二维码内容 */
    fun buildUrl(): String = "http://${lanIp()}:$port/?t=$token"

    // ─────────────── 内部 ───────────────

    private fun handle(
        client: Socket,
        fields: List<FillField>,
        onSave: (Map<String, String>) -> Unit,
    ) {
        client.use { s ->
            s.soTimeout = 8000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out: OutputStream = s.getOutputStream()

            // 请求行
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]

            // 读 header，顺便算 Content-Length
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val reqToken = query.split("&")
                .firstOrNull { it.startsWith("t=") }
                ?.substringAfter("t=")
                ?: ""

            // token 校验
            if (reqToken != token) {
                respond(out, 403, "text/plain; charset=utf-8", "403 Forbidden")
                return
            }

            when {
                method == "GET" && path == "/" -> {
                    respond(out, 200, "text/html; charset=utf-8", htmlForm(fields, token))
                }

                method == "POST" && path == "/save" -> {
                    val body = CharArray(contentLength).also { buf ->
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(buf, read, contentLength - read)
                            if (n <= 0) break
                            read += n
                        }
                    }.concatToString()

                    val params = parseForm(body)
                    if (params.isEmpty()) {
                        respond(out, 400, "text/html; charset=utf-8", resultHtml(false, "没有收到内容"))
                        return
                    }

                    // 回调写设置
                    runCatching { onSave(params) }
                        .onFailure { Log.e(TAG, "保存失败", it) }

                    respond(out, 200, "text/html; charset=utf-8", resultHtml(true, "已保存，电视上会自动刷新"))

                    // 保存完就关，不留着端口
                    thread(isDaemon = true) {
                        Thread.sleep(1200)
                        stop()
                    }
                }

                else -> respond(out, 404, "text/plain; charset=utf-8", "404")
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else {
                    val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    k to v
                }
            }
            .toMap()

    /** 本机局域网 IP */
    private fun lanIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }

    // ─────────────── HTML ───────────────

    private fun htmlForm(fields: List<FillField>, token: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>Reelbox 设置</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;padding:24px 20px 60px;background:#0b0b0d;color:#f2f2f4;
       font:15px/1.55 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
  h1{font-size:20px;font-weight:600;margin:0 0 6px}
  .sub{font-size:12px;color:#8a8a94;margin-bottom:26px}
  label{display:block;font-size:12px;color:#8a8a94;margin:18px 0 7px}
  input{width:100%;padding:14px 14px;font-size:16px;color:#f2f2f4;
        background:#1e1e24;border:1px solid #2a2a32;border-radius:10px;outline:none}
  input:focus{border-color:#e8b44d}
  button{width:100%;margin-top:30px;padding:16px;font-size:16px;font-weight:600;
         color:#0b0b0d;background:#fff;border:none;border-radius:10px}
  button:active{opacity:.8}
  .hint{font-size:11px;color:#6a6a74;margin-top:6px}
</style>
</head>
<body>
<h1>Reelbox 设置</h1>
<div class="sub">填完点保存，电视上会自动刷新</div>
<form method="post" action="/save?t=$token">
${fields.joinToString("") { f ->
        """
  <label>${escape(f.label)}</label>
  <input name="${escape(f.key)}" value="${escape(f.value)}"
         type="${if (f.secret) "password" else "text"}"
         placeholder="${escape(f.placeholder)}"
         autocapitalize="off" autocorrect="off" spellcheck="false">
  ${if (f.hint.isNotBlank()) "<div class=\"hint\">${escape(f.hint)}</div>" else ""}
"""
    }}
  <button type="submit">保存</button>
</form>
</body>
</html>
""".trimIndent()

    private fun resultHtml(ok: Boolean, message: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${if (ok) "已保存" else "失败"}</title>
<style>
  body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
       background:#0b0b0d;color:#f2f2f4;
       font:16px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;text-align:center}
  .icon{font-size:44px;margin-bottom:16px}
  .ok{color:#6bd08b}.err{color:#ff8a80}
</style>
</head>
<body><div>
  <div class="icon ${if (ok) "ok" else "err"}">${if (ok) "✓" else "✕"}</div>
  <div>${escape(message)}</div>
  <div style="font-size:12px;color:#6a6a74;margin-top:14px">可以关掉这个页面了</div>
</div></body>
</html>
""".trimIndent()

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/** 手机端要填的一个字段 */
data class FillField(
    val key: String,
    val label: String,
    val value: String,
    val placeholder: String = "",
    val hint: String = "",
    val secret: Boolean = false,
)
