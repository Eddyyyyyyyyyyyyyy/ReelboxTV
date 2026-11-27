package com.reelix.app.data.image

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 海报访问登记表 —— 「这张图最后一次被**读取**是什么时候」。
 *
 * ## 为什么需要它（而不是直接读 Coil 的缓存）
 *
 * 两个原因：
 *
 * 1. **Coil 2.7 的 `DiskCache` 没有 `keys()`**，无法枚举缓存里有哪些图，
 *    更无法把缓存文件映射回 URL（文件名是哈希）。
 * 2. 就算能枚举，文件元数据里的 `lastModified` 是**写入时间**，不是访问时间。
 *    Coil 读缓存命中时不会重写文件，所以"天天在看"的图时间戳依然很旧 ——
 *    按时间清理会把它们误删。
 *
 * 所以自己记一份 `url → 最后访问时间`，清理时遍历这份表：
 * 数据量小（几千条）、毫秒级、而且**时间语义是准确的**。
 *
 * ## 写入频率
 *
 * 每次图片加载成功都会调 [record]，但只是内存 Map 赋值（纳秒级）。
 * 落盘做了 3 秒防抖，避免滚动海报墙时反复写文件。
 */
object PosterAccessLog {

    private const val TAG = "PosterAccess"

    private const val FILE_NAME = "poster-access.json"

    /** 最多记多少条；超出后淘汰最旧的。4000 条约 400KB。 */
    private const val MAX_ENTRIES = 4000

    /** 超过这个数量就裁掉最旧的 1/4 */
    private const val TRIM_BATCH = 1000

    private val map = ConcurrentHashMap<String, Long>()

    private var appContext: Context? = null
    private var persistJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 由 ReelboxApp.onCreate 调，后台读一次文件。 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            load()
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load() {
        val ctx = appContext ?: return
        runCatching {
            val f = file(ctx)
            if (!f.exists() || f.length() == 0L) return
            // 体积保护：异常大的文件不在启动路径上解析
            if (f.length() > 2 * 1024 * 1024) {
                Log.w(TAG, "访问日志过大(${f.length()}B)，跳过加载")
                return
            }
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optLong(k, 0L)
                if (k.isNotBlank() && v > 0) map[k] = v
            }
            Log.i(TAG, "访问日志加载 ${map.size} 条")
        }.onFailure {
            Log.w(TAG, "读取访问日志失败: ${it.message}")
        }
    }

    /**
     * 记一次访问。
     *
     * ★ 高频调用（每张图加载成功一次），所以这里只做内存赋值 + 防抖落盘，
     *   绝不能在这里直接写文件。
     */
    fun record(url: String) {
        if (url.isBlank()) return
        // 只记真正的网络图；内置图/本地文件不占 Coil 磁盘缓存
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        map[url] = System.currentTimeMillis()
        persistSoon()
    }

    /** 清理时用：返回当前快照（复制一份，避免遍历中被修改）。 */
    fun entries(): Map<String, Long> = HashMap(map)

    /** 图被清掉了，登记也要一起清，否则表会越积越多。 */
    fun forget(url: String) {
        if (map.remove(url) != null) persistSoon()
    }

    fun clear() {
        map.clear()
        persistSoon()
    }

    fun size(): Int = map.size

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(3000)
            persist()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        runCatching {
            if (map.size > MAX_ENTRIES) trim()
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            file(ctx).writeText(obj.toString(), Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "写入访问日志失败: ${it.message}")
        }
    }

    /** 超过上限时，按时间淘汰最旧的一批 */
    private fun trim() {
        val sorted = map.entries.sortedBy { it.value }
        val removeCount = (map.size - MAX_ENTRIES + TRIM_BATCH).coerceAtLeast(0)
        sorted.take(removeCount).forEach { map.remove(it.key) }
        Log.i(TAG, "访问日志超过 $MAX_ENTRIES，淘汰 $removeCount 条")
    }
}
