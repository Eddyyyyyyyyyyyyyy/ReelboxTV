package com.reelix.app.data.image

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 海报刮削缓存。
 *
 * ## 为什么需要
 *
 * 实况：TVBox 源的海报质量极不稳定
 * ```
 * · 有的源 vod_pic 为空                    → 界面全是灰块
 * · 有的指向已失效的图床                    → 黑块
 * · 有的给的是 1x1 占位图                   → 看起来像加载失败
 * · 网盘类源常常只有几帧截图，没有正规海报    → 难看
 * ```
 *
 * 补图来源已收敛到 TMDB 与当前在线源；这里只保留按片名的内存/磁盘缓存，
 * 以及手动刷新时的批量入口，避免旧调用点因缺少刮削源而空转。
 *
 * ## 缓存策略
 *
 * 同一个片名只查一次 —— 内存 + SharedPreferences 持久化。
 * 海报墙滚动时只读缓存，不发网络请求；片库手动刷新时批量补刮。
 * 片名重复率很高（同一部片在多个源里都有），所以缓存复用价值很高。
 */
object PosterFetcher {

    private const val TAG = "PosterFetcher"
    private const val PREFS = "reelix_poster_cache"
    private const val KEY_CACHE = "entries_v1"

    /** 片名 → 海报 URL。失败也缓存（用空串标记），避免重复请求死图。 */
    private val cache = ConcurrentHashMap<String, String>()

    private var appContext: Context? = null

    private val _revision = MutableStateFlow(0)

    /** 缓存版本号；UI 只观察它，不直接参与网络请求。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)
    private var persistJob: Job? = null
    private var scrapeJob: Job? = null

    /**
     * 只加载一次磁盘缓存。
     *
     * 必须在 Application.onCreate 里调用；不调用也能工作，只是不落盘。
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CACHE, null)
            if (!raw.isNullOrBlank()) {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    cache[key] = obj.optString(key, "")
                }
            }
        }.onFailure {
            Log.w(TAG, "读取海报缓存失败: ${it.message}")
        }
        _revision.value++
    }

    /** 只读缓存；不会发网络请求。 */
    fun cached(title: String): String? {
        val key = normalize(title)
        if (key.isEmpty()) return null
        return cache[key]?.takeIf { it.isNotBlank() }
    }

    /**
     * 忘掉一个片名的刮削结果。
     *
     * 删除历史记录时调用 —— 否则「片名 → 海报 URL」还留在表里，
     * 下次同一部片会直接命中一个**已经被清掉的地址**，表现为"图裂了"。
     */
    fun forget(title: String) {
        val key = normalize(title)
        if (key.isEmpty()) return
        if (cache.remove(key) != null) {
            _revision.value++
            persistSoon()
        }
    }

    /**
     * 按片名查海报。
     *
     * @return 海报 URL，查不到返回 null
     */
    suspend fun byTitle(
        title: String,
        year: Int? = null,
        /** 手动刷新时重试上次失败的条目；普通 UI 调用不要打开。 */
        retryMisses: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val key = normalize(title)
        if (key.isEmpty()) return@withContext null

        // 命中缓存（含"查过但没有"的情况）
        cache[key]?.let {
            if (it.isNotBlank() || !retryMisses) return@withContext it.ifBlank { null }
        }

        val url = runCatching {
            gate.withPermit { query(key, year) }
        }.getOrNull()
        cache[key] = url ?: ""
        _revision.value++
        persistSoon()

        if (url != null) {
            Log.d(TAG, "刮到海报: $title -> ${url.take(60)}")
        }
        url
    }

    /**
     * 手动刷新片库时调用：后台限流补刮，不阻塞 UI。
     *
     * 同一时间只允许一个批量任务；每批 3 个，和 ReelboxTV 的 ScrapeCache 一致。
     */
    fun scrapeAll(items: List<Pair<String, Int?>>) {
        if (items.isEmpty()) return
        if (scrapeJob?.isActive == true) return

        scrapeJob = scope.launch {
            try {
                items.chunked(3).forEach { chunk ->
                    coroutineScope {
                        chunk.map { (title, year) ->
                            async {
                                runCatching {
                                    byTitle(title, year, retryMisses = true)
                                }
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                persistJob?.cancel()
                persist()
            }
        }
    }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(800)
            persist()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        runCatching {
            val obj = JSONObject()
            cache.forEach { (key, value) -> obj.put(key, value) }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHE, obj.toString())
                .apply()
        }.onFailure {
            Log.w(TAG, "写入海报缓存失败: ${it.message}")
        }
    }

    /** No external scrape provider remains; TMDB / online source handle artwork upstream. */
    private fun query(title: String, year: Int?): String? = null

    /**
     * 片名归一化。
     *
     * 去掉季数/年份/画质等后缀，让「三体 第一季」「三体(2023)」都归到「三体」。
     */
    fun titleKey(title: String): String = normalize(title)

    private fun normalize(title: String): String {
        var s = title.trim()

        // 去掉括号内容：(2023) 【4K】 [国语]
        s = s.replace(Regex("""[(（\[【][^)）\]】]*[)）\]】]"""), " ")

        // 去掉季/集后缀
        s = s.replace(Regex("""第\s*\d+\s*[季集期话]"""), " ")
        s = s.replace(Regex("""(?i)\bS\d{1,2}\b"""), " ")
        s = s.replace(Regex("""(?i)\bSeason\s*\d+\b"""), " ")

        // 去掉常见画质标记
        s = s.replace(Regex("""(?i)(4K|8K|2160P|1080P|720P|HDR|蓝光|高清|超清|原画)"""), " ")

        // 折叠空白
        s = s.replace(Regex("""\s+"""), " ").trim()

        return s.ifBlank { title.trim() }
    }

}
