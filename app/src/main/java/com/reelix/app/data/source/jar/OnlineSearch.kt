package com.reelix.app.data.source.jar

import android.util.Log
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在线源搜索 —— 多站点并发，逐源出结果。
 *
 * ## 设计对齐 TVBox 原版
 *
 * 读了 SearchActivity.java + SourceViewModel.java，它的做法是：
 * 1. 所有 searchable=1 的源全部丢进 5 线程池
 * 2. AtomicInteger 计数，每完成一个源就 addData 追加到列表
 * 3. 首页源排到第一个先搜
 * 4. 结果做 name.contains(关键词) 过滤
 * 5. 再次搜索时 shutdownNow 取消上一轮
 * 这个类逐条对齐。
 *
 * ## 为什么逐源刷新而不是分批
 *
 * 之前是 chunked(5) —— 每批 5 个，等这批全部完成才刷新。
 * 问题：如果某个源卡了 15 秒，这 5 个的结果都要跟着等。
 *
 * 现在用 Semaphore(5) 控制并发度，但每个源一完成就立刻回调，
 * 快的源先出结果，用户能马上开始浏览。
 */
object OnlineSearch {

    private const val TAG = "OnlineSearch"
    private const val PER_SITE_TIMEOUT_MS = 15_000L

    /** 并发度。和 TVBox 的 5 线程池一致 —— 再多容易触发源站限流 */
    private const val CONCURRENCY = 5

    /** 一个源的搜索结果 */
    data class SiteResult(
        val siteKey: String,
        val siteName: String,
        val items: List<MediaItem>,
        val error: String? = null,
        /** 是否是当前首页源（UI 上可以加个标记） */
        val isHomeSite: Boolean = false,
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 并发搜索。
     *
     * @param sites 已加载的站点
     * @param keyword 关键词
     * @param homeSiteKey 当前首页源的 key（排第一位先搜）
     * @param onEach 每完成一个源就回调（边搜边显示）
     */
    suspend fun search(
        sites: List<SourceManager.SiteEntry>,
        keyword: String,
        homeSiteKey: String? = null,
        onEach: (SiteResult) -> Unit = {},
    ): List<SiteResult> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty() || sites.isEmpty()) return@withContext emptyList()

        // 1. 只搜 searchable 的源（TVBox 同样逻辑）
        val searchable = sites.filter { it.site.searchable == 1 }
        if (searchable.isEmpty()) {
            Log.w(TAG, "没有可搜索的源")
            return@withContext emptyList()
        }

        // 2. 首页源排第一位（TVBox: searchRequestList.add(0, home)）
        val ordered = if (homeSiteKey != null) {
            val home = searchable.firstOrNull { it.site.key == homeSiteKey }
            if (home != null) listOf(home) + searchable.filter { it !== home }
            else searchable
        } else {
            searchable
        }

        Log.i(TAG, "搜索「$kw」：${ordered.size} 个源，首页源=${homeSiteKey ?: "无"}")

        val out = java.util.Collections.synchronizedList(mutableListOf<SiteResult>())
        val semaphore = Semaphore(CONCURRENCY)

        coroutineScope {
            // 用 Channel 把完成事件按完成顺序送回调用方，
            // 而不是等所有 async 结束再一起处理。
            val done = Channel<SiteResult>(Channel.UNLIMITED)

            val jobs = ordered.map { entry ->
                async {
                    semaphore.withPermit {
                        val r = searchOne(entry, kw, entry.site.key == homeSiteKey)
                        done.send(r)
                        r
                    }
                }
            }

            // 每完成一个就回调
            val collector = launch {
                repeat(ordered.size) {
                    val r = done.receive()
                    out.add(r)
                    onEach(r)
                }
            }

            jobs.forEach { it.await() }
            collector.join()
            done.close()
        }

        val list = out.toList()
        Log.i(TAG, "搜索完成：" + list.count { it.items.isNotEmpty() } + "/" + list.size + " 个源有结果")
        list
    }

    /**
     * 搜单个源。
     *
     * 注意：search 在 Spider 接口里是 suspend，但 jar 内部可能长时间阻塞，
     * 所以外面套 withTimeoutOrNull —— 超时就放弃这个源，不能让它拖累其他源。
     */
    private suspend fun searchOne(
        entry: SourceManager.SiteEntry,
        keyword: String,
        isHome: Boolean,
    ): SiteResult {
        val spider = entry.spider
        if (spider == null) {
            return SiteResult(entry.site.key, entry.displayName, emptyList(), "无 spider", isHome)
        }

        val r: List<MediaItem>? = withTimeoutOrNull(PER_SITE_TIMEOUT_MS) {
            runCatching { spider.search(keyword, 1) }.getOrElse { e ->
                Log.d(TAG, "[" + entry.displayName + "] 搜索失败: " + e.message)
                null
            }
        }

        if (r == null) {
            return SiteResult(entry.site.key, entry.displayName, emptyList(), "超时/失败", isHome)
        }

        // 3. 片名过滤（TVBox: video.name.contains(searchTitle)）
        //
        // 为什么需要：很多源的关键词匹配很宽松 —— 搜「三体」会返回
        // 「三体漫画解说」「我的三体」「三体之外的某某」。
        // 不过滤的话结果列表里一半是噪音。
        val filtered = r.filter { item ->
            item.title.contains(keyword, ignoreCase = true)
        }

        // 给每个 item 补 sourceKey（保险：jar 的 parseVod 一般已写）
        val items = filtered.map { item ->
            if (item.sourceKey.isBlank()) item.copy(sourceKey = entry.site.key) else item
        }

        return SiteResult(entry.site.key, entry.displayName, items, null, isHome)
    }
}