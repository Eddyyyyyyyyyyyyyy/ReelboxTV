package com.reelix.app.data.source

import android.content.Context
import android.util.Log
import com.reelix.app.data.source.jar.JarSpider
import com.reelix.app.data.source.js.JsHost
import com.reelix.app.data.source.js.JsSpider
import com.reelix.app.data.source.json.JsonSpider
import com.reelix.app.data.source.json.XmlSpider
import com.reelix.app.data.source.jar.SpiderBridge
import com.reelix.app.data.source.jar.SpiderJarLoader
import com.reelix.app.data.source.online.OnlineCache
import com.reelix.app.data.source.online.OnlineCacheStore
import com.reelix.app.data.source.online.OnlineSiteSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 在线源管理器 —— TVBox 订阅的完整加载链路。
 *
 * ## 职责
 *
 * ```
 * 1. 下载订阅配置（JSON）
 * 2. 解析出 spider spec + sites 列表
 * 3. 下载并加载 spider jar
 * 4. 为每个 jar 型站点创建 JarSpider
 * 5. 对外暴露「可用站点列表」
 * ```
 *
 * ## 一个必须处理的问题：订阅配置可能拉不到
 *
 * 实测这台设备的网络里，`tvbox.xxx.top` 这类配置域名被 DNS 污染成
 * 198.19.x.x（100% 丢包），但 spider jar 所在的 CDN 是通的。
 *
 * 所以这里支持**直接传配置 JSON 文本**（用户可以把配置内容粘到设置里），
 * 不强制走网络。这是这类环境下唯一能用的方式。
 */
class SourceManager(
    context: Context,
    /** Online snapshot cache (home / category / detail / subscription). */
    private val onlineCache: OnlineCache = OnlineCache(OnlineCacheStore(context)),
) {

    /**
     * 必须持有 **Application**，不是 Activity。
     *
     * TVBox 的 `Init.init(Context)` 内部会做 `(Application) ctx` 强转，
     * 传 Activity 进去直接 ClassCastException：
     * ```
     * Init.init 失败: MainActivity cannot be cast to android.app.Application
     * ```
     * 实测这个错误会让 63 个站点全部加载失败，且报错信息完全误导
     * （看起来像找不到类，实际是类型不匹配）。
     */
    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "SourceManager"
    }

    /** 加载状态 */
    sealed interface State {
        data object Idle : State
        data class Loading(val message: String) : State
        data class Ready(
            val sites: List<SiteEntry>,
            /** 加载失败的站点及原因 */
            val failures: List<Pair<String, String>>,
        ) : State
        data class Error(val message: String) : State
    }

    /** 一个可用站点 */
    data class SiteEntry(
        val site: TvBoxSite,
        val spider: Spider?,
        /** 是否来自 jar */
        val fromJar: Boolean,
    ) {
        val displayName: String get() = site.name.ifBlank { site.key }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * ★ 延迟创建：SourceManager 在首帧组合里就 new 出来，
     *   构造 OkHttpClient（连接池/Dispatcher/RouteDatabase）会拖慢启动。
     *   真正发请求时才付这笔钱。
     */
    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile
    private var jarLoader: SpiderJarLoader? = null

    /**
     * 当前已加载的配置。
     *
     * 解析器（parses[]）在播放阶段要用（把播放页换成直链），
     * 所以不能在 loadFromJson 里当局部变量丢掉。
     */
    private var currentConfig: TvBoxConfig? = null

    /** Raw config currently loaded; used to persist an offline subscription copy. */
    @Volatile
    private var currentConfigRaw: String = ""

    @Volatile
    private var currentConfigSource: String = ""

    /** 配置里的解析器列表（播放时用） */
    fun parses() = currentConfig?.parses.orEmpty()

    @Volatile
    private var bridge: SpiderBridge? = null
    /** 已经初始化过的 jar 规格；同一份 jar 不重复 init。 */
    private var bridgeSpec: SpiderJarLoader.SpiderSpec? = null

    /**
     * One spider per site for the lifetime of this manager.
     *
     * The lazy proxy is shared; otherwise every home/category/detail call
     * creates a new JarSpider and re-runs `init(Context, ext)`. That was one
     * of the main causes of the first-open delay.
     */
    private val entries = LinkedHashMap<String, SiteEntry>()

    /** Whether the local subscription copy is being used while the network refreshes. */
    @Volatile
    var servingCache: Boolean = false
        private set

    /**
     * 从 URL 加载订阅。
     */
    /**
     * 从任意地址加载订阅。
     *
     * ## 为什么不再直接 fetchText
     *
     * 用户填的地址经常不是配置本身。实测（2026-11-02）：
     * ```
     * http://www.饭太硬.cc/tv
     * ```
     * 返回的是「JPEG 图片 + `**` + base64 编码的 JSON」——
     * 直接当 JSON 读会报 `Unexpected JSON token at offset 0`，
     * 用户根本看不出这个地址其实是能用的。
     *
     * 现在交给 [SubscriptionResolver] 做四步递归解析：
     * 纯 JSON → base64（含 `**` 尾巴）→ 导航页挖链接 → 递归
     */
    suspend fun loadFromUrl(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        currentConfigSource = url
        val hadSnapshot = state.value is State.Ready
        // ★ 不在这里再恢复一次本机订阅：MainActivity 已在首帧前调用
        //   loadCachedConfigWithoutNetwork()。重复解析会重建全部 spider 并重新绑定
        //   加固 jar，只增加启动等待，没有新的首屏内容。
        try {
            // Never replace an already usable snapshot with a blocking spinner.
            if (!hadSnapshot) _state.value = State.Loading("正在解析订阅地址…")

            when (val r = SubscriptionResolver.resolve(url)) {
                is SubscriptionResolver.Result.Config -> {
                    if (!hadSnapshot) {
                        _state.value = State.Loading("已从 ${r.fromUrl.take(60)} 解析到配置")
                    }
                    loadFromJson(r.json, r.fromUrl).also {
                        if (it.isSuccess) onlineCache.putSubscription(url, r.fromUrl, r.json)
                    }
                }

                is SubscriptionResolver.Result.Choices -> {
                    // 导航页有多个候选，让用户选
                    val msg = buildString {
                        append("这个地址是导航页，找到 ${r.options.size} 个候选配置：\n")
                        r.options.take(6).forEachIndexed { i, c ->
                            append("${i + 1}. ")
                            append(c.label.takeIf { it.isNotBlank() } ?: c.url.take(70))
                            append(" → ${c.url.take(140)}")
                            c.originUrl?.takeIf { it.isNotBlank() }?.let { append("（来自 ${it.take(100)}）") }
                            append("\n")
                        }
                        append("请把其中一个地址填到「配置地址」里。")
                    }
                    if (!hadSnapshot) _state.value = State.Error(msg)
                    Result.failure(IllegalStateException(msg))
                }

                is SubscriptionResolver.Result.Failed -> {
                    if (!hadSnapshot) _state.value = State.Error(r.reason)
                    Result.failure(IllegalStateException(r.reason))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadFromUrl 失败", e)
            // A failed silent refresh must not erase the cache-backed site list.
            if (!hadSnapshot && _state.value !is State.Ready) {
                _state.value = State.Error(e.message ?: "未知错误")
            }
            Result.failure(e)
        }
    }

    /**
     * 从 JSON 文本加载订阅（推荐方式 —— 绕过 DNS 问题）。
     */
    suspend fun loadFromJson(jsonText: String, configUrl: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (_state.value !is State.Ready) _state.value = State.Loading("正在解析配置…")
            val config = TvBoxConfig.parse(jsonText)
            currentConfig = config
            currentConfigRaw = jsonText
            if (currentConfigSource.isNotBlank()) {
                onlineCache.putSubscription(currentConfigSource, configUrl, jsonText)
            }

            val sites = config.sites.filter { it.name.isNotBlank() || it.key.isNotBlank() }
            if (sites.isEmpty()) error("配置里没有站点")

            // ① 处理 spider jar
            var jarOk = false
            var jarErr: String? = null
            val spiderSpec = config.spider?.let { jarLoader?.parseSpiderSpec(it) }
                ?: SpiderJarLoader(context).also { jarLoader = it }
                    .parseSpiderSpec(config.spider.orEmpty())

            if (spiderSpec != null) {
                if (bridge != null && bridgeSpec == spiderSpec) {
                    // 启动时配置可能被加载两次（缓存一次、网络一次）。
                    // 插件的代理/会话状态挂在 Init 上，重复初始化会留下两套状态，
                    // 表现为解析出的本地端口和实际在听的端口对不上。
                    jarOk = true
                    Log.d(TAG, "spider jar 未变化，复用已初始化实例")
                } else {
                    if (_state.value !is State.Ready) {
                        _state.value = State.Loading("正在加载 spider 组件…")
                    }
                    val loader = jarLoader ?: SpiderJarLoader(context).also { jarLoader = it }

                    val loadStarted = System.currentTimeMillis()
                    when (val r = loader.load(spiderSpec) { u -> fetchBytes(u) }) {
                        is SpiderJarLoader.Result.Success -> {
                            val b = SpiderBridge(r.loader)
                            b.attachContext(context)
                            if (b.init(context)) {
                                bridge = b
                                bridgeSpec = spiderSpec
                                jarOk = true
                                Log.d(TAG, "spider jar 加载成功: ${r.jarPath} (${System.currentTimeMillis() - loadStarted}ms)")
                            } else {
                                jarErr = "Init.init 失败（加固库未就绪）"
                            }
                        }
                        is SpiderJarLoader.Result.Failure -> {
                            jarErr = r.reason
                            Log.e(TAG, "spider jar 加载失败: ${r.reason}")
                        }
                    }
                }
            } else {
                jarErr = "配置未提供 spider 字段"
            }

            // ② 构建站点列表
            if (_state.value !is State.Ready) {
                _state.value = State.Loading("正在初始化站点…")
            }
            servingCache = false
            val previous = LinkedHashMap(entries)
            entries.clear()
            val failures = mutableListOf<Pair<String, String>>()

            // JS 源需要 QuickJS，先确保宿主上下文就位
            runCatching { JsHost.attach(context) }

            for (site in sites) {
                // ★ JS 源判定要放在 isJarType 之前：
                //   JS 源的 type 也是 3，但 api 以 .js 结尾，
                //   走 jar 分支会找不到类（实测会把 227 个源全判失败）。
                if (site.api.trim().endsWith(".js", ignoreCase = true)) {
                    val js = JsSpider(
                        api = site.api,
                        key = site.key,
                        ext = site.ext.orEmpty(),
                        name = site.name,
                        searchable = site.searchable == 1,
                        configBaseUrl = configUrl,
                        vipFlags = config.flags,
                    )
                    if (js.available) {
                        entries[site.key] = SiteEntry(site, js, fromJar = false)
                    } else {
                        failures += site.name to
                            (com.reelix.app.data.source.js.QuickJs.loadErrorMessage
                                ?: "QuickJS 不可用")
                    }
                } else if (JarSpider.isJarType(site)) {
                    val b = bridge
                    if (jarOk && b != null) {
                        // ★ 不在这里同步 instantiate：加固 jar 的首次 init 可能要十几秒。
                        //   先让站点列表可用，第一次真正访问该站点时再初始化；
                        //   同一个 site.key 的 JarSpider 在本次 SourceManager 生命周期内复用。
                        val old = previous[site.key]?.takeIf { it.fromJar }?.spider as? JarSpider
                        // Only skip `init`; the refreshed config still updates name/searchable/ext metadata.
                        val js = old?.withRefreshedSite(site, old.cachedSpiderInstance(), config.flags)
                            ?: JarSpider(
                                key = site.key,
                                name = site.name,
                                searchable = site.searchable == 1,
                                site = site,
                                bridge = b,
                                vipFlags = config.flags,
                            )
                        if (js.available) {
                            entries[site.key] = SiteEntry(site, js, fromJar = true)
                        } else {
                            failures += site.name to "spider 实例创建失败"
                        }
                    } else {
                        failures += site.name to (jarErr ?: "jar 未加载")
                    }
                } else if (site.type == 1 && site.api.startsWith("http")) {
                    // JSON 型站点（type=1）：纯 HTTP 接口，不需要 jar。
                    // 这类源不依赖加固/解密，是最容易跑通的一类，之前被整类跳过。
                    val json = JsonSpider(
                        api = site.api,
                        key = site.key,
                        ext = site.ext,
                        name = site.name,
                        searchable = site.searchable == 1,
                    )
                    entries[site.key] = SiteEntry(
                        site = site,
                        spider = json,
                        fromJar = false,
                    )
                } else if (site.type == 0 && site.api.startsWith("http")) {
                    // XML 型站点（type=0）：RSS 接口，同样不需要 jar。
                    // 这类源多数带 m3u8 直链，是最"干净"的一类。
                    entries[site.key] = SiteEntry(
                        site = site,
                        spider = XmlSpider(
                            api = site.api,
                            key = site.key,
                            ext = site.ext,
                            name = site.name,
                            searchable = site.searchable == 1,
                        ),
                        fromJar = false,
                    )
                } else {
                    // 仍未支持：type=4（JS 源，需要 QuickJS）等
                    failures += site.name to "暂不支持的站点类型 type=${site.type}"
                }
            }

            _state.value = State.Ready(entries.values.toList(), failures)
            onlineCache.putSites(
                entries.values.map { entry ->
                    OnlineSiteSnapshot(
                        key = entry.site.key,
                        name = entry.site.name,
                        type = entry.site.type,
                        api = entry.site.api,
                        ext = entry.site.ext,
                        searchable = entry.site.searchable,
                        fromJar = entry.fromJar,
                    )
                },
                selectedKey = null,
            )
            Log.d(TAG, "站点加载完成: 可用 ${entries.size}，失败 ${failures.size}")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "loadFromJson 失败", e)
            _state.value = State.Error(e.message ?: "未知错误")
        }
    }

    /** 可用站点 */
    fun sites(): List<SiteEntry> =
        (_state.value as? State.Ready)?.sites.orEmpty()

    /** Cached config text for the immediate startup path; caller still parses on IO. */
    fun cachedConfigText(): String? {
        onlineCache.load()
        return onlineCache.cachedSubscriptionAny()?.data
    }

    /**
     * Startup restoration must not load a jar or instantiate every site.
     *
     * The UI can already draw the cached site list and cached posters. Only
     * the selected site is initialized lazily by [loadFromUrl] when its request
     * actually needs a spider.
     */
    fun restoreSitesCache(): List<OnlineSiteSnapshot> {
        onlineCache.load()
        return onlineCache.cachedSites().orEmpty()
    }

    /** Applies the saved config without touching the network; the UI already has a snapshot to show. */
    suspend fun loadCachedConfigWithoutNetwork(): Boolean {
        val text = cachedConfigText() ?: return false
        return loadFromJsonLocal(text).isSuccess
    }

    /** Explicit source keeps cached startup from re-downloading or re-persisting via the URL flow. */
    private suspend fun loadFromJsonLocal(jsonText: String): Result<Unit> {
        val sourceBefore = currentConfigSource
        currentConfigSource = ""
        return try {
            loadFromJson(jsonText)
        } finally {
            currentConfigSource = sourceBefore
        }
    }

    /** 启动时从本机订阅文本构建站点实例；不下载配置。 */
    suspend fun restoreFromCache(): Boolean {
        val text = cachedConfigText() ?: return false
        return runCatching { loadFromJson(text).isSuccess }.getOrDefault(false) && sites().isNotEmpty()
    }

    /** 按 key 找站点 */
    fun find(key: String): SiteEntry? =
        sites().firstOrNull { it.site.key == key }

    // ---------- 网络 ----------

    /**
     * 拉配置文本。
     *
     * ## ★ 为什么要检查 Content-Type
     *
     * 踩过的坑（2026-11-02，用户填 `http://www.饭太硬.cc/tv` 加载失败）：
     * 那个地址实际返回的是**一张 JPEG 图片**（`image/x-ms-bmp`），
     * 我们直接把图片字节当字符串交给 JSON 解析器，报错是：
     * ```
     * Unexpected JSON token at offset 0: Expected start
     * ```
     * 这句话对用户毫无意义 —— 他看不出「地址填错了」这个真正原因。
     *
     * 现在先看 Content-Type，不是文本类型就直接给出可操作的提示。
     */
    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            // TVBox 生态普遍认这个 UA
            .header("User-Agent", "okhttp/3.15")
            .build()
        return okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}")
            }

            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val body = resp.body?.string().orEmpty()

            // 非文本类型 → 说明这个地址不是配置接口
            val looksTextual = contentType.isEmpty() ||
                contentType.contains("json") ||
                contentType.contains("text") ||
                contentType.contains("xml") ||
                contentType.contains("javascript")

            if (!looksTextual) {
                error(
                    "这个地址返回的是 ${contentType}，不是配置文件。\n" +
                        "请确认填的是「配置 json 地址」，不是官网页面或图片。"
                )
            }

            // 是文本但明显不是 json（比如返回 HTML 页面）
            val head = body.trimStart()
            if (head.isNotEmpty() && !head.startsWith("{") && !head.startsWith("[")) {
                val what = when {
                    head.startsWith("<!DOCTYPE", true) || head.startsWith("<html", true) -> "网页"
                    else -> "非 JSON 内容"
                }
                error(
                    "这个地址返回的是$what，不是 TVBox 配置。\n" +
                        "请确认填的是「配置 json 地址」。"
                )
            }

            body
        }
    }

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "okhttp/3.15")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.bytes()
        }
    }.getOrNull()
}
