package com.reelix.app.data.source.js

import android.util.Log
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.Spider
import com.reelix.app.data.source.jar.TvBoxJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **JS 型站点**（`type=3` + `api` 以 `.js` 结尾）—— drpy 系列爬虫。
 *
 * ## 覆盖范围
 *
 * 这类源在公开配置里占比**极高**：`js.json` 里 298 个站点中有 **227 个（76%）** 是这种。
 *
 * ```json
 * {
 *   "key": "drpy_js_example",
 *   "type": 3,
 *   "api": "./lib/drpy2.min.js",     ← 以 .js 结尾
 *   "ext": "https://example.com/"
 * }
 * ```
 *
 * ## 运行机制
 *
 * ```
 * ① QuickJS 加载 host.js（注入 req/joinUrl/pdfh/pdfa 等全局）
 * ② evalModule(api) 把爬虫脚本作为 ES module 执行
 * ③ 爬虫的 default 导出挂到 globalThis.__entry
 * ④ 调 entry.init(ext) → entry.homeContent() / categoryContent() …
 * ⑤ 返回 JSON 字符串，交给 TvBoxJsonParser 解析（和 jar 源同一套）
 * ```
 *
 * ## 与其他源的一致性
 *
 * 返回值格式**和 spider jar 完全一致**，所以解析层直接复用
 * [TvBoxJsonParser]，不重复实现。
 *
 * ## 线程约束
 *
 * QuickJS 上下文**不是线程安全的**，且 `globalThis.__entry`
 * 全局唯一（同一时刻只能挂一个爬虫）。所以所有调用必须：
 * - 串行化（`synchronized`）
 * - 换源时重新 evalModule
 */
class JsSpider(
    private val api: String,
    override val key: String,
    private val ext: String = "",
    override val name: String = key,
    override val searchable: Boolean = true,
    /**
     * 配置文件的地址。
     *
     * 为什么需要：JS 源的 api 往往是**相对路径**（`./lib/drpy2.min.js`），
     * 意思是"相对于配置文件所在目录"。实测 js.json 里
     * 227 个源全部是相对路径（221 个 drpy2.min.js + 2 个 drpy.min.js）。
     *
     * 拿到 base 后就能拼成绝对 URL 去下载并缓存。
     */
    private val configBaseUrl: String = "",
    private val vipFlags: List<String> = emptyList(),
) : Spider {

    // 注意：这里**不能**用实例锁。
    //
    // QuickJS 的 JSContext 和 globalThis.__entry 都是**进程级唯一**的，
    // 多个 JsSpider 实例各持一把锁 = 等于没锁。
    // 实测：227 个 JS 源并发探测时直接 SIGSEGV（libc Fatal signal 11）。
    // 所以必须用 companion 里的全局锁，把所有 JS 调用串成一条线。
    private val lock get() = GLOBAL_LOCK

    /** 当前已 init 的站点 key（换源要重新加载） */
    @Volatile
    private var currentKey: String? = null

    // host.js 是**进程级**注入，不是实例级。
    //
    // ✓ 正确：整个进程只注入一次（放进 companion）
    // ✗ 错误：每个 JsSpider 实例各自注入 → 第二次会撞 host.js 里的
    //         `const VERSION` 等全局声明，报 "identifier already declared"，
    //         而且这个异常会污染 JSContext，后续所有 eval 都返回空 ERROR。
    private val hostReady: Boolean get() = HOST_READY

    /** QuickJS 是否可用（native 库加载失败时为 false） */
    val available: Boolean get() = QuickJs.available

    // ---------- Spider ----------

    override suspend fun home(): HomeContent = withContext(Dispatchers.IO) {
        val raw = invokeSafely("home", "[true]")
        TvBoxJsonParser.parseHome(raw, sourceKey = key)
    }

    override suspend fun category(
        categoryId: String,
        page: Int,
        extend: Map<String, String>,
    ): CategoryContent = withContext(Dispatchers.IO) {
        // 参数顺序和 jar 版一致：tid, pg, filter, extend
        val extendJson = if (extend.isEmpty()) "{}" else {
            extend.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"${esc(k)}\":\"${esc(v)}\""
            }
        }
        val args = "[\"${esc(categoryId)}\",\"$page\",${extend.isNotEmpty()},$extendJson]"
        val raw = invokeSafely("category", args)
        TvBoxJsonParser.parseCategory(raw, sourceKey = key)
    }

    override suspend fun detail(id: String): MediaDetail = withContext(Dispatchers.IO) {
        val raw = invokeSafely("detail", "[\"${esc(id)}\"]")
        val parsed = TvBoxJsonParser.parseDetail(raw, sourceKey = key)
            ?: return@withContext MediaDetail(
                item = MediaItem(
                    id = "${key}_$id",
                    sourceId = id,
                    sourceKey = key,
                    title = "加载失败",
                )
            )
        MediaDetail(item = parsed.first, routes = parsed.second)
    }

    override suspend fun search(keyword: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val raw = invokeSafely("search", "[\"${esc(keyword)}\",true,\"$page\"]")
            TvBoxJsonParser.parseSearch(raw, sourceKey = key)
        }

    override suspend fun resolvePlayUrl(routeFlag: String, episodeId: String): PlayResolution =
        withContext(Dispatchers.IO) {
            // playerContent(flag, id, vipFlags)
            val flags = kotlinx.serialization.json.buildJsonArray {
                vipFlags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            val raw = invokeSafely("play", "[\"${esc(routeFlag)}\",\"${esc(episodeId)}\",$flags]")
            TvBoxJsonParser.parsePlayer(raw)
                ?.let { p ->
                    PlayResolution(
                        url = p.url,
                        headers = p.headers,
                        direct = p.direct,
                        parserName = p.parserName,
                        msg = p.msg,
                        playUrl = p.playUrl,
                        requiresParsing = !p.direct,
                    )
                }
                ?: PlayResolution(url = "", direct = false, msg = "JS 源未返回播放地址")
        }

    // ---------- 内部 ----------

    /**
     * 确保模块已加载并 init。
     *
     * @return null 表示成功，否则是错误说明
     */
    private fun ensure(): String? = synchronized(lock) {
        if (!QuickJs.available) {
            return "QuickJS 不可用：" + (QuickJs.loadErrorMessage ?: "未知原因")
        }

        QuickJs.init()

        // ① 注入 host.js（整个进程只做一次）
        if (!HOST_READY) {
            val host = JsHost.appContext?.assets
                ?.open("js/lib/host.js")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return "缺少 assets/js/lib/host.js"


            val r = QuickJs.eval(host)
            if (r.startsWith("ERROR")) {
                return "host.js 执行失败：[" + r.take(300) + "] len=" + host.length
            }

            HOST_READY = true
            Log.i(TAG, "[$key] host.js 已注入")
        }

        // ② 同源且已 init 过 → 直接用
        if (currentKey == key) return null

        // ③ 加载爬虫模块（相对路径要先解析成可下载的地址）
        val moduleName = resolveModuleName()
        Log.i(TAG, "[$key] 加载模块: $moduleName")
        val loaded = QuickJs.evalModule(moduleName)
        if (loaded.startsWith("ERROR")) {
            Log.e(TAG, "[$key] evalModule 失败: " + loaded.take(300))
            return "模块加载失败：" + loaded.take(120)
        }

        // ④ init(ext)
        val initArgs = if (ext.isBlank()) "[]" else "[\"${esc(ext)}\"]"
        val initResult = QuickJs.call("init", initArgs)
        if (initResult.startsWith("ERROR")) {
            // init 失败不一定是致命的：有些爬虫的 init 是可选钩子
            Log.w(TAG, "[$key] init 返回异常: " + initResult.take(200))
        }

        currentKey = key
        Log.i(TAG, "[$key] 模块已加载: " + api)
        return null
    }

    /**
     * 调爬虫方法，返回原始 JSON 字符串。
     *
     * 任何异常都转成 null（上层会当成"没有数据"），
     * 不让 JS 的错误把 App 拖崩。
     */
    private fun invokeSafely(fn: String, argsJson: String): String? {
        val err = ensure()
        if (err != null) {
            Log.w(TAG, "[$key] $fn 跳过：$err")
            return null
        }

        val r = synchronized(lock) {
            runCatching { QuickJs.call(fn, argsJson) }.getOrElse { "ERROR: " + it.message }
        }

        if (r.isBlank() || r.startsWith("ERROR")) {
            Log.d(TAG, "[$key] $fn 返回: " + r.take(200))
            return null
        }
        return r
    }

    /**
     * 把 api 解析成 QuickJs 能加载的模块名。
     *
     * 三种情况：
     * ```
     * https://x.com/a.js   → 原样（QuickJs 会下载并缓存）
     * ./lib/drpy2.min.js   → 拼上配置的 base URL
     * assets://js/lib/x.js → 原样（从 APK 资产读）
     * ```
     */
    private fun resolveModuleName(): String {
        val a = api.trim()
        if (a.isEmpty()) return a

        // 绝对 URL / assets:// 原样用
        if (a.startsWith("http://") || a.startsWith("https://") || a.startsWith("assets://")) {
            return a
        }

        // 相对路径：拼配置所在目录
        if (configBaseUrl.isBlank()) {
            Log.w(TAG, "[$key] 相对路径 " + a + " 但没有配置 baseUrl")
            return a
        }

        val base = configBaseUrl.trim().substringBeforeLast('/')
        val rel = a.removePrefix("./")
        return base + "/" + rel
    }

    /** JSON 字符串转义 */
    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private companion object {
        const val TAG = "JsSpider"

        /** 进程级唯一：QuickJS 上下文不是线程安全的 */
        val GLOBAL_LOCK = Any()

        /** host.js 是否已注入 —— 进程级，只能注入一次 */
        @Volatile
        var HOST_READY = false
    }
}
