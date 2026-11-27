package com.reelix.app.data.source.jar

import android.util.Log
import com.reelix.app.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * 站点连通性自检 —— 加载完订阅后，逐个探一下能不能真的取到内容。
 *
 * ## 为什么需要这一步
 *
 * "jar 加载成功" 和 "站点能用" 是两回事：
 * - jar 加载成功 = 类实例化成功，说明字节码链接没问题
 * - 站点能用 = `homeContent()` 返回了合法 JSON，说明站点的网络请求、
 *   签名、鉴权全部正常
 *
 * 实测差别很大：`Wogg` 能实例化，但它的 `homeContent` 可能因为
 * 需要夸克 cookie 而返回空。不做这一步的话，用户点进去只会看到白屏。
 *
 * ## 设计
 *
 * 每个站点并发探测，带超时。结果只做**分类**，不解析完整内容：
 * - [Status.OK]：拿到了非空 JSON 且含 `class` 或 `list`
 * - [Status.EMPTY]：请求成功但没内容（多半要登录/扫码）
 * - [Status.ERROR]：抛异常或超时
 *
 * 这个结果直接给设置页展示，让用户知道哪些源"现在真能用"。
 */
object SourceProbe {

    private const val TAG = "SourceProbe"

    enum class Status { OK, EMPTY, ERROR, SKIPPED }

    data class Result(
        val siteKey: String,
        val siteName: String,
        val status: Status,
        val detail: String,
        /** 拿到的分类名，展示用 */
        val categories: List<String> = emptyList(),
    ) {
        val display: String
            get() = when (status) {
                Status.OK -> "${categories.size} 个分类"
                Status.EMPTY -> "无内容（可能需登录）"
                Status.ERROR -> "失败：$detail"
                Status.SKIPPED -> "跳过"
            }
    }

    /**
     * 探测单个站点。
     *
     * 注意超时要短：TVBox 的 spider 内部网络请求没有上限，
     * 有的源会卡几十秒。这里用 [withTimeoutOrNull] 兜住。
     */
    suspend fun probe(entry: SourceManager.SiteEntry, timeoutMs: Long = 12_000): Result =
        withContext(Dispatchers.IO) {
            val spider = entry.spider
            if (spider == null) {
                return@withContext Result(
                    entry.site.key, entry.displayName, Status.SKIPPED, "无 spider 实例"
                )
            }

            val raw = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                runCatching { spider.home() }.getOrNull()
            }

            if (raw == null) {
                Log.d(TAG, "${entry.displayName}: 超时或异常")
                return@withContext Result(
                    entry.site.key, entry.displayName, Status.ERROR, "超时"
                )
            }

            val cats = raw.categories.map { it.name }
            val hasContent = cats.isNotEmpty() || raw.recommendations.isNotEmpty()

            Result(
                siteKey = entry.site.key,
                siteName = entry.displayName,
                status = if (hasContent) Status.OK else Status.EMPTY,
                detail = "",
                categories = cats.take(8),
            )
        }

    /**
     * 并发探测全部站点。
     *
     * 并发度限制在 4：这些 spider 内部都在打网络请求，
     * 全并发容易触发源站限流，也会把电视的网络打满。
     */
    suspend fun probeAll(
        entries: List<SourceManager.SiteEntry>,
        onEach: (Result) -> Unit = {},
    ): List<Result> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Result>()
        for (group in entries.chunked(4)) {
            val results = coroutineScope {
                group.map { entry -> async { probe(entry) } }.map { d -> d.await() }
            }
            out.addAll(results)
            for (r in results) onEach(r)
        }
        return@withContext out
    }
}
