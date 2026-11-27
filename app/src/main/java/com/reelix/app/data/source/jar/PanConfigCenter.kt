package com.reelix.app.data.source.jar

import android.util.Log
import com.reelix.app.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 网盘配置中心 —— 扫码授权页面的来源。
 *
 * ## 这个功能是什么
 *
 * `饭太硬` 这类源里，网盘类站点（玩偶、多多、双星…）播放前需要
 * **夸克 / 115 / 迅雷等网盘的登录 cookie**。TVBox 的做法是：
 *
 * ```
 * 打开 spider 自带的「配置中心」页面 → WebView 里扫码 → cookie 存到 jar 内部
 * ```
 *
 * 页面不是宿主画的，是 **spider 自己吐出来的 HTML**。
 *
 * ## 怎么拿到页面（实测结论）
 *
 * ```
 * ✗ proxy?do=pan&action=config   → 返回空 body
 * ✓ categoryContent("settings")  → 返回 JSON，HTML 藏在 vod_content 字段
 * ```
 *
 * ## 必须绕开解析层
 *
 * 正常链路里 `vod_content` 会被 [TvBoxJsonParser] 的 `stripHtml()` 洗掉标签，
 * 配置页直接废掉。所以这里走 [JarSpider.rawCategory] 拿原始 JSON，
 * 自己挖出 HTML 原文。
 */
object PanConfigCenter {

    private const val TAG = "PanConfigCenter"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 配置中心页面 */
    data class ConfigPage(
        val html: String?,
        val siteName: String,
        /** 失败原因，html 为空时给 UI 显示 */
        val reason: String = "",
    ) {
        val valid: Boolean get() = !html.isNullOrBlank()
    }

    /**
     * 在已加载站点里找配置中心。
     *
     * 判定：`api` 含 config，或站点名含「配置中心」。
     * 命中多个时优先 `Wexconfig` 这类通用配置中心。
     */
    fun findConfigSite(sites: List<SourceManager.SiteEntry>): SourceManager.SiteEntry? {
        val candidates = sites.filter { e ->
            e.site.api.contains("config", ignoreCase = true) ||
                e.displayName.contains("配置中心")
        }
        // 「通用类型」比「影视专用」覆盖面广，优先
        return candidates.firstOrNull { it.displayName.contains("通用") }
            ?: candidates.firstOrNull()
    }

    /**
     * 拉配置页。
     *
     * @param entry 配置中心站点
     * @param settingsTid 分类 id，默认 `settings`
     */
    suspend fun fetchPage(
        entry: SourceManager.SiteEntry,
        settingsTid: String = "settings",
    ): ConfigPage = withContext(Dispatchers.IO) {
        val jarSpider = entry.spider as? JarSpider
            ?: return@withContext ConfigPage(
                null, entry.displayName, "该站点不是 jar 型，无法读取配置页"
            )

        // ① 先问站点自己有哪些分类 —— "settings" 未必是这个 jar 的约定的 id
        val home = runCatching { jarSpider.home() }.getOrNull()
        val categories = home?.categories.orEmpty()
        Log.i(TAG, "配置中心分类: ${categories.map { "${it.id}=${it.name}" }}")

        // 候选 tid：默认 settings，加上分类列表里名字像"设置/配置"的
        val candidates = buildList {
            add(settingsTid)
            categories.forEach { c ->
                val n = c.name
                if (n.contains("设置") || n.contains("配置") || n.contains("网盘") ||
                    n.contains("登录") || n.contains("账号")
                ) add(c.id)
            }
            // 分类数量少时全试一遍，成本可控
            if (categories.size <= 6) categories.forEach { add(it.id) }
        }.distinct()

        for (tid in candidates) {
            val raw = runCatching { jarSpider.rawCategory(tid) }.getOrNull()
            if (raw.isNullOrBlank()) {
                Log.d(TAG, "tid=$tid 返回空")
                continue
            }
            val html = extractHtml(raw)
            if (html != null) {
                Log.i(TAG, "tid=$tid 拿到配置页：${html.length} 字符")
                return@withContext ConfigPage(html, entry.displayName)
            }
            // 即使不是 HTML，也把内容记下来方便排查
            val preview = raw.take(160)
            Log.d(TAG, "tid=$tid 无 HTML，返回：$preview")
            if (raw.contains("\"list\":[]")) {
                // 空列表，换下一个
                continue
            }
        }

        Log.w(TAG, "所有候选 tid 都没拿到 HTML: $candidates")
        ConfigPage(
            null,
            entry.displayName,
            "配置中心返回的不是网页（已试 ${candidates.size} 个分类）"
        )
    }

    /**
     * 从原始 JSON 里挖 HTML。
     *
     * 结构是标准的 TVBox category 返回：
     * ```json
     * { "list": [ { "vod_id": "web", "vod_content": "<html>...</html>" } ] }
     * ```
     * 部分版本放在 `vod_pic` 里（当作 data URI 或地址）。
     */
    private fun extractHtml(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null

        val list = runCatching { root["list"]?.jsonArray }.getOrNull()
            ?: return null

        for (el in list) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue

            // 优先 vod_content
            obj.str("vod_content")?.let { c ->
                if (looksLikeHtml(c)) return c
            }
            // 其次 vod_pic（有的 jar 把页面放这）
            obj.str("vod_pic")?.let { p ->
                if (looksLikeHtml(p)) return p
            }
            // 再看 vod_remarks / vod_name 有没有 html 片段
            obj.str("vod_remarks")?.let { r ->
                if (looksLikeHtml(r)) return r
            }
        }
        return null
    }

    /** 判定是不是 HTML（而不是普通简介文本） */
    private fun looksLikeHtml(s: String): Boolean {
        if (s.length < 200) return false
        val lower = s.lowercase()
        return lower.contains("<html") ||
            lower.contains("<!doctype") ||
            lower.contains("<div") ||
            lower.contains("<script") ||
            lower.contains("<body")
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * 诊断：直接问 jar 某个网盘授权项返回什么。
     *
     * 网盘配置中心的每个分类都是一张卡片，`vod_id` 就是授权项：
     * ```
     * quarkcookie   夸克
     * ucpancookie   UC
     * aliyuntoken   阿里
     * 115pandiy     115
     * tianyilogin   天翼
     * ```
     * 调 `detailContent(vod_id)` 看它给的是二维码页、还是纯配置说明。
     */
    suspend fun probeAuthItem(
        entry: SourceManager.SiteEntry,
        vodId: String,
    ): String? = withContext(Dispatchers.IO) {
        val jarSpider = entry.spider as? JarSpider ?: return@withContext null
        val raw = runCatching { jarSpider.rawDetail(vodId) }.getOrNull()
        Log.i(TAG, "detailContent($vodId) => ${raw?.take(600)}")
        raw
    }
}