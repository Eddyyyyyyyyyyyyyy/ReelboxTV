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
 * 内嵌设置服务器 —— App 里所有「长输入」都可以在电脑/手机上填。
 *
 * ## 设计目标（用户明确要求）
 *
 * ```
 * ① 固定地址：http://<电视IP>:8888
 *    手机、电脑都能访问，不用每次扫码
 * ② 内网使用，不做鉴权 —— 用户明确说过「内网访问，无需考虑安全问题」
 * ③ 常驻运行，随时打开浏览器就能改设置
 * ```
 *
 * ## 为什么不引 NanoHTTPD
 *
 * 需求就两个接口：
 * ```
 * GET  /              返回设置页（一个 HTML）
 * POST /save/<group>  保存某一组设置
 * ```
 * 手写 300 行够用，且完全可控。项目原则：不加依赖前先问「不加会死吗」。
 *
 * ## 安全边界（虽然用户说不用管，但代码里仍然保留最低限度）
 *
 * ```
 * ① 只响应 GET/POST，其他方法 405
 * ② 只监听已配置的路由，未匹配 404
 * ③ 不执行任何 shell、不读任意文件
 * ```
 * 即：这个端口的攻击面只有「改 App 自己的设置」，没有别的东西。
 *
 * ## 端口选择
 *
 * `8888` 好记（用户要求「简单固定」）。被占用时自动往后找 8889/8890…
 * 但会**优先重试 8888** —— 电视上通常没有别的服务抢这个端口。
 */
class SettingsWebServer(
    private val preferredPort: Int = 8888,
) {
    private val TAG = "SettingsWebServer"

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /** 实际监听端口 */
    var port: Int = 0
        private set

    /**
     * 每收到一次 GET /，就调用这个拿最新设置。
     * 由上层注入，保证总是渲染当前值而不是启动时的快照。
     */
    var snapshotProvider: (() -> ServerSnapshot)? = null

    /** 收到保存请求时回调：(分组名, 表单键值) */
    var saveHandler: ((String, Map<String, String>) -> String)? = null

    /**
     * 启动。
     *
     * @return 是否启动成功
     */
    fun start(): Boolean {
        if (running.get()) return true

        val socket = bind(preferredPort)
        if (socket == null) {
            Log.e(TAG, "端口 $preferredPort..${preferredPort + 20} 全被占用")
            return false
        }

        port = socket.localPort
        serverSocket = socket
        running.set(true)

        thread(name = "settings-web", isDaemon = true) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { handle(client) }
                        .onFailure { Log.d(TAG, "请求失败: ${it.message}") }
                }
            }
        }

        Log.i(TAG, "设置服务已启动: ${lanIp()}:$port")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun isRunning(): Boolean = running.get()

    /** 给 UI 显示的访问地址 */
    fun accessUrl(): String = "http://${lanIp()}:$port"

    // ────────────────── 内部 ──────────────────

    private fun bind(startPort: Int): ServerSocket? {
        // 先试两次首选端口（避免瞬时占用误判）
        repeat(2) {
            runCatching { tryBind(startPort) }.getOrNull()?.let { return it }
            Thread.sleep(120)
        }
        // 再往后找
        for (p in (startPort + 1)..(startPort + 20)) {
            runCatching { tryBind(p) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun tryBind(p: Int): ServerSocket {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(p), 16)
        return s
    }

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 10_000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = s.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val snap = snapshotProvider?.invoke()
                    if (snap == null) {
                        respond(out, 503, "text/html; charset=utf-8", errPage("服务未就绪"))
                    } else {
                        respond(out, 200, "text/html; charset=utf-8", renderPage(snap))
                    }
                }

                method == "POST" && path.startsWith("/save/") -> {
                    val group = path.removePrefix("/save/")
                    val body = readBody(input, contentLength)
                    val params = parseForm(body)
                    val result = runCatching { saveHandler?.invoke(group, params) ?: "保存回调未注册" }
                    result.fold(
                        onSuccess = { respond(out, 200, "text/html; charset=utf-8", okPage(it)) },
                        onFailure = { respond(out, 400, "text/html; charset=utf-8", errPage("保存失败：${it.message}")) },
                    )
                }

                else -> respond(out, 404, "text/html; charset=utf-8", errPage("页面不存在"))
            }
        }
    }

    private fun readBody(input: BufferedReader, len: Int): String {
        if (len <= 0) return ""
        val buf = CharArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
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
                val i = pair.indexOf('=')
                if (i <= 0) null
                else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
            .toMap()

    private fun lanIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    // ────────────────── HTML ──────────────────

    private fun renderPage(snap: ServerSnapshot): String {
        val categories = snap.groups.joinToString("\n") { group ->
            """
<button class="category" type="button" data-category="${esc(group.key)}">
  <span><strong>${esc(group.title)}</strong><small>${esc(group.hint.orEmpty())}</small></span>
  <span class="arrow" aria-hidden="true">›</span>
</button>
"""
        }
        val sections = snap.groups.joinToString("\n") { g ->
            val content = if (g.key == "tvbox") renderSubscriptions(snap.subscriptions) else {
                """
  <form method="post" action="/save/${esc(g.key)}">
    ${g.fields.joinToString("\n") { renderField(it) }}
    <div class="actions"><button type="submit">保存</button><span class="save-status"></span></div>
  </form>
"""
            }
            """
<section class="card settings-section" data-section="${esc(g.key)}" hidden>
  <button class="back" type="button">‹ 全部设置</button>
  <h2>${esc(g.title)}</h2>
  ${g.hint?.let { "<p class=\"hint\">${esc(it)}</p>" } ?: ""}
  $content
</section>
"""
        }

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reelbox 设置</title>
<style>
  *{box-sizing:border-box}
  body{margin:0;padding:0 0 60px;background:#0b0b0d;color:#f2f2f4;
       font:15px/1.55 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
  header{padding:26px 22px 18px;border-bottom:1px solid #1c1c22}
  h1{font-size:21px;font-weight:600;margin:0}
  .sub{font-size:12px;color:#8a8a94;margin-top:5px}
  .wrap{max-width:760px;margin:0 auto;padding:0 18px}
  .category-list{display:grid;gap:10px;margin:20px 0}
  .category{display:flex;align-items:center;justify-content:space-between;gap:18px;width:100%;
        padding:17px 18px;text-align:left;color:#f2f2f4;background:#141418;
        border:1px solid #25252c;border-radius:12px;cursor:pointer}
  .category:hover,.category:focus-visible{border-color:#e8b44d;outline:none;background:#1a1a20}
  .category strong{display:block;font-size:15px;font-weight:600}
  .category small{display:block;margin-top:4px;color:#a5a5af;font-size:12px;line-height:1.45}
  .arrow{font-size:24px;color:#a5a5af}
  .card{background:#141418;border:1px solid #25252c;border-radius:12px;
        padding:20px 20px 22px;margin:20px 0}
  .back{width:auto;margin:0 0 20px;padding:7px 0;color:#d5d5dc;background:transparent;text-align:left}
  .back:hover,.back:focus-visible{color:#e8b44d;outline:2px solid #e8b44d;outline-offset:3px}
  h2{font-size:15px;font-weight:600;margin:0 0 4px;color:#f2f2f4}
  .hint{font-size:11.5px;color:#6a6a74;margin:0 0 14px;overflow-wrap:anywhere;word-break:break-word}
  label{display:block;font-size:12px;color:#8a8a94;margin:16px 0 6px}
  input{width:100%;padding:13px 14px;font-size:15px;color:#f2f2f4;
        background:#1e1e24;border:1px solid #2a2a32;border-radius:9px;outline:none;
        font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
  input:focus{border-color:#e8b44d}
  select{width:100%;padding:13px 14px;font-size:15px;color:#f2f2f4;
        background:#1e1e24;border:1px solid #2a2a32;border-radius:9px;outline:none}
  select:focus{border-color:#e8b44d}
  .check{display:flex;align-items:center;gap:9px;margin:16px 0 4px;font-size:14px;color:#f2f2f4}
  .check input{width:20px;height:20px;accent-color:#e8b44d}
  .fhint{font-size:11px;color:#6a6a74;margin-top:5px;overflow-wrap:anywhere;word-break:break-word}
  .actions{display:flex;align-items:center;gap:12px;margin-top:18px}
  .actions button{flex:1;width:auto}
  button{width:100%;padding:14px;font-size:15px;font-weight:600;
         color:#0b0b0d;background:#fff;border:none;border-radius:9px;cursor:pointer}
  button:active{opacity:.82}
  button:disabled{opacity:.55}
  .save-status{font-size:12px;color:#8a8a94;white-space:nowrap}
  .save-status.ok{color:#6bd08b}
  .save-status.err{color:#ff8a80}
  .subscription-list{display:grid;gap:10px;margin:18px 0 26px}
  .subscription-item{padding:14px;border:1px solid #2a2a32;border-radius:10px;background:#1a1a20}
  .subscription-top{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
  .subscription-name{font-size:14px;font-weight:600;overflow-wrap:anywhere}
  .subscription-active{color:#6bd08b;font-size:11px;white-space:nowrap}
  .subscription-url{margin-top:5px;color:#a5a5af;font-size:12px;overflow-wrap:anywhere;word-break:break-word}
  .subscription-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
  .subscription-actions button,.secondary{width:auto;padding:8px 12px;color:#f2f2f4;background:#25252c;font-size:13px}
  .subscription-actions button:disabled{color:#8a8a94}
  .subscription-empty{padding:14px 0;color:#a5a5af;font-size:13px}
  .form-title{margin:26px 0 4px;font-size:14px}
  .form-hint{margin:0;color:#a5a5af;font-size:12px}
  .card.dirty h2:after{content:" · 未保存";color:#e8b44d;font-size:12px;font-weight:500}
  .bar{position:fixed;left:0;right:0;bottom:0;z-index:9;display:flex;align-items:center;gap:12px;
       padding:12px 18px calc(12px + env(safe-area-inset-bottom));background:#141418;
       border-top:1px solid #2a2a32;box-shadow:0 -8px 24px rgba(0,0,0,.35);display:none}
  .bar.show{display:flex}
  .bar span{flex:1;font-size:13px;color:#f2f2f4}
  .bar button{width:auto;margin:0;padding:10px 18px;font-size:14px}
  .os{font-size:11px;color:#4a4a54;text-align:center;margin-top:26px}
  [hidden]{display:none!important}
  ::selection{background:#e8b44d;color:#0b0b0d}
  :focus-visible{outline:2px solid #e8b44d;outline-offset:3px}
  @media(max-width:520px){header{padding:20px 0 14px}.wrap{padding:0 14px}.category{padding:15px}.card{padding:17px}}
</style>
</head>
<body>
<header><div class="wrap">
  <h1>Reelbox 设置</h1>
  <div class="sub">${esc(snap.deviceLabel)} · 改完点保存，电视上立即生效</div>
</div></header>
<div class="wrap">
<div class="category-list" id="category-list">${categories}</div>
${sections}
  <div class="os">Reelbox ${esc(snap.version)} · ${esc(snap.accessInfo)}</div>
</div>
<div class="bar" id="save-bar"><span id="save-bar-text"></span><button type="button" id="save-all">全部保存</button></div>
<script>
(() => {
  const forms = [...document.querySelectorAll('form:not(#subscription-form)')];
  const list = document.getElementById('category-list');
  const sections = [...document.querySelectorAll('.settings-section')];
  function showCategory(key) {
    list.hidden = Boolean(key);
    sections.forEach(section => { section.hidden = section.dataset.section !== key; });
    if (!key) return;
    const section = sections.find(item => item.dataset.section === key);
    section?.querySelector('input,select,button')?.focus({preventScroll:true});
  }
  document.querySelectorAll('[data-category]').forEach(button => {
    button.addEventListener('click', () => showCategory(button.dataset.category));
  });
  document.querySelectorAll('.settings-section .back').forEach(button => {
    button.addEventListener('click', () => showCategory(''));
  });
  const subscriptionForm = document.getElementById('subscription-form');
  if (subscriptionForm) {
    const nameInput = subscriptionForm.elements.namedItem('name');
    const urlInput = subscriptionForm.elements.namedItem('url');
    const idInput = subscriptionForm.elements.namedItem('id');
    const actionInput = subscriptionForm.elements.namedItem('action');
    const submitButton = document.getElementById('subscription-submit');
    const cancelButton = document.getElementById('subscription-cancel');
    const status = document.getElementById('subscription-status');
    function resetSubscriptionForm() {
      subscriptionForm.reset();
      idInput.value = '';
      actionInput.value = 'add';
      submitButton.textContent = '添加订阅';
      cancelButton.hidden = true;
      status.textContent = '';
    }
    document.querySelectorAll('[data-sub-action]').forEach(button => {
      button.addEventListener('click', async () => {
        const action = button.dataset.subAction;
        const id = button.dataset.subId || '';
        if (action === 'edit') {
          idInput.value = id;
          nameInput.value = button.dataset.subName || '';
          urlInput.value = button.dataset.subUrl || '';
          actionInput.value = 'update';
          submitButton.textContent = '保存修改';
          cancelButton.hidden = false;
          nameInput.focus();
          subscriptionForm.scrollIntoView({behavior:'smooth',block:'start'});
          return;
        }
        if (action === 'delete' && !window.confirm('确定删除这条订阅吗？')) return;
        button.disabled = true;
        try {
          const body = new URLSearchParams({action:action,id:id});
          const response = await fetch('/save/subscriptions',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:body});
          if (!response.ok) throw new Error('请求失败（HTTP ' + response.status + '）');
          window.location.reload();
        } catch (error) {
          button.disabled = false;
          status.className = 'save-status err';
          status.textContent = error.message;
        }
      });
    });
    cancelButton.addEventListener('click', resetSubscriptionForm);
    subscriptionForm.addEventListener('submit', async event => {
      event.preventDefault();
      submitButton.disabled = true;
      status.className = 'save-status';
      status.textContent = '保存中…';
      try {
        const body = new URLSearchParams(new FormData(subscriptionForm));
        const response = await fetch(subscriptionForm.action,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:body});
        if (!response.ok) throw new Error('保存失败（HTTP ' + response.status + '）');
        window.location.reload();
      } catch (error) {
        submitButton.disabled = false;
        status.className = 'save-status err';
        status.textContent = error.message;
      }
    });
  }
  const bar = document.getElementById('save-bar');
  const barText = document.getElementById('save-bar-text');
  const saveAll = document.getElementById('save-all');
  const dirty = () => forms.filter(f => f.classList.contains('dirty'));
  function updateBar() {
    const count = dirty().length;
    bar.classList.toggle('show', count > 0);
    barText.textContent = count > 0 ? count + ' 组有未保存修改' : '';
  }
  async function save(form) {
    const card = form.closest('.card');
    const status = form.querySelector('.save-status');
    const button = form.querySelector('button[type=submit]');
    button.disabled = true;
    status.className = 'save-status';
    status.textContent = '保存中…';
    try {
      const body = new URLSearchParams(new FormData(form));
      const response = await fetch(form.action, {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
        body
      });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      form.classList.remove('dirty');
      card.classList.remove('dirty');
      status.className = 'save-status ok';
      status.textContent = '已保存 ' + new Date().toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    } catch (error) {
      status.className = 'save-status err';
      status.textContent = '保存失败：' + error.message;
    } finally {
      button.disabled = false;
      updateBar();
    }
  }
  forms.forEach(form => {
    form.addEventListener('input', () => {
      form.classList.add('dirty');
      form.closest('.card').classList.add('dirty');
      updateBar();
    });
    form.addEventListener('change', () => {
      form.classList.add('dirty');
      form.closest('.card').classList.add('dirty');
      updateBar();
    });
    form.addEventListener('submit', event => {
      event.preventDefault();
      save(form);
    });
  });
  saveAll.addEventListener('click', async () => {
    saveAll.disabled = true;
    for (const form of dirty()) await save(form);
    saveAll.disabled = false;
    updateBar();
  });
  window.addEventListener('beforeunload', event => {
    if (dirty().length > 0) { event.preventDefault(); event.returnValue = ''; }
  });
  updateBar();
})();
</script>
</body>
</html>
"""
    }

    private fun renderField(f: ServerField): String = when (f.type) {
        ServerFieldType.CHECKBOX -> """
    <label class="check">
      <input name="${esc(f.key)}" value="1" type="checkbox" ${if (f.value == "1" || f.value.equals("true", true)) "checked" else ""}>
      <span>${esc(f.label)}</span>
    </label>
    ${if (f.hint.isNotBlank()) "<div class=\"fhint\">${esc(f.hint)}</div>" else ""}
"""
        ServerFieldType.SELECT -> """
    <label>${esc(f.label)}</label>
    <select name="${esc(f.key)}">
      ${f.options.joinToString("\n") { option ->
        "<option value=\"${esc(option.value)}\" ${if (option.value == f.value) "selected" else ""}>${esc(option.label)}</option>"
      }}
    </select>
    ${if (f.hint.isNotBlank()) "<div class=\"fhint\">${esc(f.hint)}</div>" else ""}
"""
        ServerFieldType.PASSWORD, ServerFieldType.TEXT -> """
    <label>${esc(f.label)}</label>
    <input name="${esc(f.key)}" value="${esc(f.value)}"
           type="${if (f.type == ServerFieldType.PASSWORD) "password" else "text"}"
           placeholder="${esc(f.placeholder)}"
           autocapitalize="off" autocorrect="off" spellcheck="false">
    ${if (f.hint.isNotBlank()) "<div class=\"fhint\">${esc(f.hint)}</div>" else ""}
"""
    }

    private fun renderSubscriptions(items: List<ServerSubscription>): String {
        val list = if (items.isEmpty()) {
            "<div class=\"subscription-empty\">还没有保存的订阅地址。</div>"
        } else items.joinToString("\n") { item ->
            """
<article class="subscription-item">
  <div class="subscription-top">
    <div class="subscription-name">${esc(item.name)}</div>
    ${if (item.active) "<span class=\"subscription-active\">当前使用</span>" else ""}
  </div>
  <div class="subscription-url">${esc(item.url)}</div>
  <div class="subscription-actions">
    <button type="button" data-sub-action="activate" data-sub-id="${esc(item.id)}" ${if (item.active) "disabled" else ""}>设为当前</button>
    <button type="button" data-sub-action="edit" data-sub-id="${esc(item.id)}" data-sub-name="${esc(item.name)}" data-sub-url="${esc(item.url)}">编辑</button>
    <button type="button" data-sub-action="delete" data-sub-id="${esc(item.id)}">删除</button>
  </div>
</article>
"""
        }
        return """
<div class="subscription-list">$list</div>
<h3 class="form-title">添加或编辑订阅</h3>
<p class="form-hint">保存后会切换到这条订阅；原有订阅会保留。</p>
<form id="subscription-form" method="post" action="/save/subscriptions">
  <input type="hidden" name="action" value="add">
  <input type="hidden" name="id" value="">
  <label for="subscription-name">订阅名称</label>
  <input id="subscription-name" name="name" type="text" maxlength="80" autocomplete="off" placeholder="例如：家庭源">
  <label for="subscription-url">订阅地址</label>
  <input id="subscription-url" name="url" type="url" required maxlength="2048" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="https://example.com/tv/config.json">
  <div class="actions">
    <button id="subscription-submit" type="submit">添加订阅</button>
    <button id="subscription-cancel" class="secondary" type="button" hidden>取消编辑</button>
  </div>
  <div id="subscription-status" class="save-status" role="status"></div>
</form>
""".trimIndent()
    }

    private fun okPage(msg: String): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>已保存</title>
<style>
 body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
      background:#0b0b0d;color:#f2f2f4;text-align:center;
      font:16px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}
 .i{font-size:46px;color:#6bd08b;margin-bottom:14px}
 a{color:#8a8a94;font-size:13px;margin-top:18px;display:inline-block;text-decoration:none}
</style></head>
<body><div>
 <div class="i">✓</div>
 <div>${esc(msg)}</div>
 <a href="/">← 继续修改</a>
</div></body></html>
"""

    private fun errPage(msg: String): String = """
<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>出错了</title>
<style>
 body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
      background:#0b0b0d;color:#f2f2f4;text-align:center;
      font:16px/1.6 -apple-system,sans-serif}
</style></head>
<body><div><div style="font-size:40px;margin-bottom:12px">✕</div>${esc(msg)}</div></body></html>
"""

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/** 服务器渲染用的一份设置快照 */
data class ServerSnapshot(
    val version: String,
    val deviceLabel: String,
    val accessInfo: String,
    val groups: List<ServerGroup>,
    val subscriptions: List<ServerSubscription> = emptyList(),
)

data class ServerSubscription(val id: String, val name: String, val url: String, val active: Boolean)

/** 一组设置（页面上一个卡片） */
data class ServerGroup(
    val key: String,
    val title: String,
    val hint: String? = null,
    val fields: List<ServerField>,
)

/** 一个输入框 */
data class ServerField(
    val key: String,
    val label: String,
    val value: String,
    val placeholder: String = "",
    val hint: String = "",
    val secret: Boolean = false,
    val type: ServerFieldType = if (secret) ServerFieldType.PASSWORD else ServerFieldType.TEXT,
    val options: List<ServerOption> = emptyList(),
)

enum class ServerFieldType {
    TEXT, PASSWORD, CHECKBOX, SELECT,
}

data class ServerOption(val value: String, val label: String)
