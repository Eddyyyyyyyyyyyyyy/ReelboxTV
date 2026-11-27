package com.reelix.app.data.image

import android.content.Context
import android.util.Log
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

/**
 * 海报缓存的清理入口。
 *
 * ## 为什么需要单独一层
 *
 * 缓存本身由 Coil 管（内存 20% + 磁盘 256MB LRU），但 Coil 只认 URL，
 * **不知道"这张图属于哪部片、还要不要"**。业务侧的删除动作（删历史）
 * 和"看过但没留下记录"的滞图，都得靠这一层去清。
 *
 * ## 三种清理
 *
 * ```
 * evict()      删一条历史时立即清 —— 1~5ms，用户无感
 * purgeStale() 兜底批量清 —— 秒级，必须分片 + 放在后台时机
 * clearAll()   设置里的「清除图片缓存」
 * ```
 */
object PosterCacheCleaner {

    private const val TAG = "PosterCache"

    /** 批量清理时每处理多少个 key 让出一次 IO */
    private const val YIELD_EVERY = 200

    /**
     * 清掉一部片的竖版海报与横版背景。
     *
     * 只清磁盘 + 内存里那两个 URL，不动别人的图；两个 URL 都先过
     * [artworkCanBeEvicted]，仍被历史/片库/在线结果引用的图不会被删。
     * 调用方通常是在"删除历史记录"之后，失败也无所谓（有 purgeStale 兜底）。
     */
    suspend fun evict(
        context: Context,
        posterUrl: String?,
        backdropUrl: String?,
        title: String?,
        keepUrls: Set<String> = emptySet(),
        keepTitles: Set<String> = emptySet(),
        forgetTitle: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val loader = context.imageLoader
        listOfNotNull(posterUrl, backdropUrl)
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { url ->
                if (!artworkCanBeEvicted(url, keepUrls)) return@forEach
                runCatching {
                    loader.diskCache?.remove(url)
                    loader.memoryCache?.remove(MemoryCache.Key(url))
                    // 访问登记也要一起清，否则表会越积越多
                    PosterAccessLog.forget(url)
                }.onFailure { Log.w(TAG, "evict 失败: ${it.message}") }
            }
        // 片名 → 海报 URL 的映射只在该片名已无任何引用时清，
        // 否则片库/在线页还在用这部片，下次会白白重刮一次。
        // 判定内部会归一化，和 keepTitles（artworkTitleKeys）保持同一套口径。
        title?.takeIf { forgetTitle && titleCacheCanBeForgotten(it, keepTitles) }?.let {
            runCatching { PosterFetcher.forget(it) }
        }
    }

    /**
     * 批量清理"没人认领"的滞图。
     *
     * ## 判定规则
     *
     * ```
     * url ∈ keepUrls                  → 保留（历史 / 片库还在用）
     * 最后一次**访问**在 olderThanMs 之前 → 删除
     * 其余                            → 保留
     * ```
     *
     * ## 为什么不遍历磁盘缓存
     *
     * Coil 2.7 的 `DiskCache` **没有 keys()**，无法枚举；而且文件元数据里的
     * `lastModified` 是**写入时间**，读缓存不会重写，所以"天天看"的图
     * 时间戳依然很旧，按它清理会误删。
     *
     * 改成遍历 [PosterAccessLog]（我们自己记的访问表）：
     * 几千条内存数据，毫秒级；而且时间语义是准确的"最后读取时间"。
     *
     * 代价：升级前就已存在的图没有登记，清不到 —— 由「清除图片缓存」兜底。
     *
     * @param keepUrls 需要保护的图片 URL（历史/片库/在线的竖图与横图）
     * @return 实际删除的数量
     */
    suspend fun purgeStale(
        context: Context,
        keepUrls: Set<String>,
        olderThanMs: Long,
    ): Int = withContext(Dispatchers.IO) {
        val cache = context.imageLoader.diskCache ?: return@withContext 0
        val now = System.currentTimeMillis()
        val cutoff = now - olderThanMs

        val log = PosterAccessLog.entries()
        if (log.isEmpty()) {
            Log.i(TAG, "purgeStale: 访问日志为空，跳过")
            return@withContext 0
        }

        var removed = 0
        var scanned = 0
        for ((url, lastAccess) in log) {
            scanned++
            // 保护名单里的图永不自动删（历史 / 片库还在引用）
            if (url in keepUrls) continue
            if (lastAccess in 1..cutoff) {
                runCatching { cache.remove(url) }.onSuccess {
                    PosterAccessLog.forget(url)
                    removed++
                }
            }
            // 虽说是内存遍历，但每条都可能有一次磁盘 remove，
            // 所以仍然分片让出，避免和同时进行的海报加载抢 IO。
            if (scanned % YIELD_EVERY == 0) yield()
        }

        Log.i(
            TAG,
            "purgeStale: 登记 $scanned 个 / 保护 ${keepUrls.size} 个 / 删除 $removed 个",
        )
        removed
    }

    /**
     * 「每天最多一次」的自动清理入口。
     *
     * 由页面在 **onStop**（用户不看屏幕了）时调用，避免和正在进行的海报加载抢 IO。
     *
     * ## 两道闸
     *
     * 1. **24 小时内只跑一次** —— 记录在 SharedPreferences 里
     * 2. **保护名单为空就跳过** —— 历史/片库还没就绪时（首启、片库扫描中）
     *    保护名单是不完整的，这时候清等于随机删图，宁可这轮不清
     *
     * @param keepUrls 历史 + 片库当前引用的海报 URL
     * @return 实际删除数量
     */
    suspend fun purgeIfDue(
        context: Context,
        keepUrls: Set<String>,
        olderThanMs: Long = DEFAULT_OLDER_THAN_MS,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    ): Int = withContext(Dispatchers.IO) {
        if (keepUrls.isEmpty()) {
            Log.i(TAG, "purgeIfDue: 保护名单为空（历史/片库未就绪），跳过")
            return@withContext 0
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_PURGE, 0L)
        if (last > 0L && now - last < minIntervalMs) {
            return@withContext 0
        }
        val removed = purgeStale(context, keepUrls, olderThanMs)
        prefs.edit().putLong(KEY_LAST_PURGE, now).apply()
        removed
    }

    private const val PREFS = "poster_cache_state"
    private const val KEY_LAST_PURGE = "last_purge_at"

    /** 滞图存活期：7 天（用户 2026-11-07 定） */
    private const val DEFAULT_OLDER_THAN_MS = 7L * 24 * 3600 * 1000

    /** 自动清理最小间隔：24 小时 */
    private const val DEFAULT_MIN_INTERVAL_MS = 24L * 3600 * 1000

    /** 清空全部图片缓存（设置页的手动入口）。 */    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        com.reelix.app.data.alist.LibraryCustomizationStore.cleanupUnused(context)
        runCatching {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            // 访问登记也要清 —— 否则下一次 purgeStale 会对着一堆
            // 已经不存在的图做 remove，白跑一轮
            PosterAccessLog.clear()
            Log.i(TAG, "已清空图片缓存")
        }.onFailure { Log.w(TAG, "clearAll 失败: ${it.message}") }
    }

    /** 当前磁盘占用（字节）。读不到返回 0。 */
    fun diskUsageBytes(context: Context): Long =
        runCatching { (context.imageLoader.diskCache?.size ?: 0L) +
            java.io.File(context.applicationContext.filesDir, "library-artwork").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "jpg" }.sumOf { it.length() } }.getOrDefault(0L)
}
