package com.reelix.app.data.source.js

import android.util.Log
import com.reelix.app.data.source.js.JsHost
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * QuickJS 的 Kotlin 入口（M1：ES Module + 原生桥 + 爬虫入口调用）。
 *
 * 分工：
 * - native 层（`quickjs_jni.c`）提供 `_http` / `_pdfh` / `_pdfa` / `__log` 四个 JS 原生函数，
 *   以及 `nativeEvalModule` / `nativeCall` 两个入口。
 * - 本类提供 **C 层回调**（`moduleSource` / `httpSync` / `pdfhRule` / `pdfaRule` / `hostLog`），
 *   它们都是 `@JvmStatic`，被 native 层用 JNI 直接调用。
 * - drpy 风格爬虫需要的 JS 侧全局（`req/join/pdfh/…`）在 `assets/js/lib/host.js` 里，
 *   在跑爬虫前先 `eval(host.js)` 注入。
 */
object QuickJs {

    @Volatile
    private var loadError: String? = null

    /** 爬虫文件所在的根目录（`./xxx.js` 相对它解析）。 */
    @Volatile
    var spiderRoot: File? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val available: Boolean
        get() = loadError == null

    val loadErrorMessage: String?
        get() = loadError

    init {
        loadError = runCatching {
            System.loadLibrary("quickjs_jni")
            null
        }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    }

    // ---------------- native 入口 ----------------

    @JvmStatic
    private external fun nativeInit()

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeEval(code: String): String

    @JvmStatic
    private external fun nativeEvalModule(name: String): String

    @JvmStatic
    private external fun nativeCall(fn: String, argsJson: String): String

    @JvmStatic
    private external fun nativeGetGlobal(key: String): String

    /**
     * 全局锁 —— QuickJS 的 JSContext 是进程级唯一且**非线程安全**。
     *
     * 实测：227 个 JS 源并发调用时直接 SIGSEGV
     * （`Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)`）。
     * 所以所有 native 入口都必须串行。
     */
    private val LOCK = Any()

    fun init() {
        synchronized(LOCK) { runCatching { nativeInit() } }
    }

    fun version(): String = runCatching { nativeVersion() }.getOrElse { "不可用" }

    /** 执行 JS（全局作用域），返回结果字符串；异常以 `ERROR: ` 前缀返回。 */
    fun eval(code: String): String = synchronized(LOCK) {
        runCatching { nativeEval(code) }.getOrElse { "ERROR: ${it.message}" }
    }

    /** 加载一个 ES module（走模块加载器），default 导出放进 `globalThis.__entry`。 */
    fun evalModule(name: String): String = synchronized(LOCK) {
        runCatching { nativeEvalModule(name) }.getOrElse { "ERROR: ${it.message}" }
    }

    /** 调 `__entry` 上的方法。`argsJson` 是 JSON 数组。 */
    fun call(fn: String, argsJson: String): String = synchronized(LOCK) {
        runCatching { nativeCall(fn, argsJson) }.getOrElse { "ERROR: ${it.message}" }
    }

    fun getGlobal(key: String): String = synchronized(LOCK) {
        runCatching { nativeGetGlobal(key) }.getOrElse { "" }
    }

    // ---------------- 宿主回调（C 层调用） ----------------

    /**
     * 按模块名给源码。
     *
     * 三种来源（对应 TVBox 的 `assets://` / 远程 `.js` / 同目录相对导入）：
     * - `assets://js/lib/x.js` → APK 资产；**没有就退到内置 stub**（见 [BuiltinModules]）
     * - `http(s)://…/x.js`     → 下载并按 URL 哈希缓存 7 天
     * - 其它（`x.js` / `./x.js` 已由 native 归一化）→ `spiderRoot` 下的文件
     */
    @JvmStatic
    fun moduleSource(name: String): String? = runCatching {
        android.util.Log.d("QuickJs", "moduleSource 请求: " + name)
        when {
            name.startsWith("assets://") -> {
                val path = name.removePrefix("assets://")
                val ctx = JsHost.appContext
                val real = ctx?.assets?.open(path)?.bufferedReader()?.use { it.readText() }
                if (real != null) {
                    android.util.Log.d("QuickJs", "  从 assets 读到 " + path + " (" + real.length + ")")
                    real
                } else {
                    val stub = JsHost.builtinModule(path)
                    android.util.Log.d("QuickJs", "  assets 缺失，用内置 shim: " + path + " -> " + (stub != null))
                    stub
                }
            }

            name.startsWith("http://") || name.startsWith("https://") -> {
                // 先试网络；失败则按**文件名**去 assets 找同名 shim。
                //
                // 为什么需要这个兜底：drpy2.min.js 里硬编码了
                //   import "https://down.nigx.cn/qu.ax/cLFE.js"
                // 这类第三方地址，实测全部 403（站点已挂）。
                // 不兜底的话整个 drpy2 模块编译失败，221 个源全废。
                val net = fetchModule(name)
                if (net != null) {
                    android.util.Log.d("QuickJs", "  远程模块已下载: " + name)
                    net
                } else {
                    val fileName = name.substringAfterLast('/')
                    val local = JsHost.appContext?.assets
                        ?.open("js/lib/" + fileName)
                        ?.bufferedReader()?.use { it.readText() }
                    android.util.Log.d(
                        "QuickJs",
                        "  远程失败，本地 shim " + fileName + " -> " + (local != null)
                    )
                    local
                }
            }

            else -> {
                val root = spiderRoot
                val f = if (root != null) File(root, name) else File(name)
                if (f.exists()) f.readText() else null
            }
        }
    }.getOrNull()

    /** 远程 JS 模块：下到 `filesDir/js-cache/<md5>.js`，7 天内复用。 */
    private fun fetchModule(url: String): String? {
        val dir = JsHost.appContext?.let { File(it.filesDir, "js-cache") } ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null

        val file = File(dir, md5(url) + ".js")

        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000) {
            return file.readText()
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", JsHost.UA)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null

            val text = resp.body?.string() ?: return null
            runCatching { file.writeText(text) }

            return text
        }
    }

    private fun md5(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 同步 HTTP：解析 options，走 OkHttp。返回 JSON 字符串，失败返回 null。 */
    @JvmStatic
    fun httpSync(url: String, optionsJson: String): String? = runCatching {
        val opt = try {
            JSONObject(optionsJson)
        } catch (_: Exception) {
            JSONObject()
        }

        val method = opt.optString("method", "GET").uppercase()
        val headers = opt.optJSONObject("headers")
        val body = opt.optString("body").takeIf { it.isNotEmpty() }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", JsHost.UA)

        headers?.keys()?.forEach { key ->
            builder.header(key, headers.optString(key))
        }

        if (body != null) {
            builder.method(method, body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        } else {
            builder.method(method, null)
        }

        client.newCall(builder.build()).execute().use { resp ->
            val out = JSONObject()
            out.put("ok", resp.isSuccessful)
            out.put("status", resp.code)
            out.put("url", resp.request.url.toString())

            val h = JSONObject()
            resp.headers.names().forEach { h.put(it, resp.header(it) ?: "") }
            out.put("headers", h)

            out.put("content", resp.body?.string() ?: "")

            out.toString()
        }
    }.getOrNull()

    /** `pdfh(html, rule)` → 取第一条。返回 JSON 字符串（标量或数组）。 */
    @JvmStatic
    fun pdfhRule(rule: String, html: String): String = Pdfh.pdfh(html, rule)

    /** `pdfa(html, rule)` → 取全部。返回 JSON 数组字符串。 */
    @JvmStatic
    fun pdfaRule(rule: String, html: String): String = Pdfh.pdfa(html, rule)

    @JvmStatic
    fun hostLog(msg: String) {
        Log.i("QuickJs", msg)
    }

    /** 自检（设置页那个「JS 引擎自检」按钮）。 */
    fun selfTest(): String {
        if (!available) {
            return "原生库加载失败：$loadErrorMessage"
        }

        init()

        val results = mutableListOf<String>()

        results += "版本 ${version()}"
        results += "1+1 = " + eval("1 + 1")
        results += "JSON = " + eval("JSON.stringify({a:1,b:[2,3]})")

        return results.joinToString("\n")
    }
}

/** 持有 ApplicationContext，供 `moduleSource` 读 assets 用。 */
object AppHolder {
    @Volatile
    var context: android.content.Context? = null
}

/**
 * `assets://js/lib/xxx.js` 的内置兜底模块。
 *
 * ★ 为什么需要：drpy 驱动的 import 是**硬依赖**（`import cheerio from "assets://js/lib/cheerio.min.js"`），
 *   文件不存在整个模块就加载失败。这些库是**运行时依赖**，不是可删的死代码。
 * ★ 但也不需要把 680KB 的 cheerio 塞进 APK：drpy 只用它做 `cheerio.jinja2` 模板渲染，
 *   真正的 HTML 解析走 `pdfh/pdfa`（原生 jsoup）。所以这里给最小实现。
 */
internal object BuiltinModules {

    fun of(path: String): String? = when {
        path.endsWith("cheerio.min.js") -> CHEERIO
        path.endsWith("crypto-js.js") -> "export default {};"
        path.endsWith("pako.min.js") ->
            "globalThis.pako = globalThis.pako || { ungzip: () => new Uint8Array(0) }; export default {};"
        path.endsWith("json5.js") ->
            "globalThis.JSON5 = globalThis.JSON5 || JSON; export default {};"
        path.endsWith("jinja.js") ->
            "globalThis.jinja = globalThis.jinja || { render: (t) => t }; export default {};"
        else -> null
    }

    private const val CHEERIO = """
const cheerio = {
  jinja2(template, obj) {
    if (obj == null) return template;
    return String(template).replace(/\{\{\s*([\w.]+)\s*\}\}/g, (m, key) => {
      const v = key.split(".").reduce((o, k) => (o == null ? undefined : o[k]), obj);
      return v == null ? "" : String(v);
    });
  },
  load(html) {
    return { text: () => String(html), find: () => ({ text: () => "", attr: () => "", length: 0 }), attr: () => "", each: () => {} };
  }
};
export default cheerio;
"""
}

