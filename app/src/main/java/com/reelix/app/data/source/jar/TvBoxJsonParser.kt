package com.reelix.app.data.source.jar

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.FilterGroup
import com.reelix.app.data.source.FilterOption
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TVBox 返回 JSON 的解析器。
 *
 * ## 为什么单独抽一个
 *
 * TVBox 生态的 JSON 格式是**事实标准**，但字段名极其不稳定：
 * `vod_play_from` / `vod_play_url` 里用 `$$$` 分线路、`#` 分集、
 * `$` 分集名和地址。字段缺失、类型错乱是常态（有的源返回 null，
 * 有的返回空字符串，有的数字用字符串表示）。
 *
 * 解析这部分最容易出问题，又完全可以脱离 Android 测试，
 * 所以抽成纯函数并配单测。
 *
 * ## 关键格式说明
 *
 * ```
 * vod_play_from: "线路1$$$线路2"
 * vod_play_url:  "第1集$url1#第2集$url2$$$第1集$url3#第2集$url4"
 * ```
 * `$$$` 分隔线路，`#` 分隔集，`$` 分隔集名和地址。
 */
object TvBoxJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ---------- 首页 ----------

    /**
     * 解析 `homeContent` 返回值。
     *
     * ```json
     * {
     *   "class": [{"type_id":"1","type_name":"电影"}],
     *   "filters": {"1":[{"key":"year","name":"年份","value":[{"v":"2024","n":"2024"}]}]},
     *   "list": [{...vod...}]
     * }
     * ```
     */
    fun parseHome(raw: String?, sourceKey: String = ""): HomeContent {
        if (raw.isNullOrBlank()) return HomeContent()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject

            // 分类
            val categories = root["class"]?.jsonArrayOrNull()?.mapNotNull { el ->
                val obj = el.jsonObjectOrNull() ?: return@mapNotNull null
                val id = obj.str("type_id") ?: return@mapNotNull null
                val name = obj.str("type_name") ?: id
                SourceCategory(id = id, name = name)
            }.orEmpty()

            // 筛选器
            val filters = parseFilters(root["filters"])
            val withFilters = categories.map { cat ->
                cat.copy(filters = filters[cat.id].orEmpty())
            }

            // 推荐位
            val items = root["list"]?.jsonArrayOrNull()
                ?.mapNotNull { parseVod(it, sourceKey) }.orEmpty()

            HomeContent(
                categories = withFilters,
                recommendations = items,
                heroes = items.take(5),
            )
        }.getOrElse { HomeContent() }
    }

    private fun parseFilters(el: JsonElement?): Map<String, List<FilterGroup>> {
        val obj = el?.jsonObjectOrNull() ?: return emptyMap()
        return obj.mapNotNull { (tid, arr) ->
            val groups = arr.jsonArrayOrNull()?.mapNotNull { g ->
                val go = g.jsonObjectOrNull() ?: return@mapNotNull null
                val key = go.str("key") ?: return@mapNotNull null
                val name = go.str("name") ?: key
                val options = go["value"]?.jsonArrayOrNull()?.mapNotNull { v ->
                    val vo = v.jsonObjectOrNull() ?: return@mapNotNull null
                    FilterOption(
                        value = vo.str("v").orEmpty(),
                        label = vo.str("n") ?: vo.str("v").orEmpty(),
                    )
                }.orEmpty()
                FilterGroup(key = key, name = name, options = options)
            }.orEmpty()
            tid to groups
        }.toMap()
    }

    // ---------- 分类内容 ----------

    /**
     * 解析 `categoryContent`。
     * ```json
     * {"page":1,"pagecount":100,"limit":20,"total":1000,"list":[...]}
     * ```
     */
    fun parseCategory(raw: String?, sourceKey: String = ""): CategoryContent {
        if (raw.isNullOrBlank()) return CategoryContent()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            // ★ sourceKey 必须传下去：否则海报墙 item 的 sourceKey 为空，
            //   点进详情时找不到对应站点，会误用"当前选中站点"，
            //   表现为"详情返回站点名、routes=0"（实测踩过）。
            val items = root["list"]?.jsonArrayOrNull()
                ?.mapNotNull { parseVod(it, sourceKey) }.orEmpty()
            CategoryContent(
                items = items,
                page = root.num("page") ?: 1,
                pageCount = root.num("pagecount") ?: 1,
                total = root.num("total") ?: items.size,
            )
        }.getOrElse { CategoryContent() }
    }

    // ---------- 详情 ----------

    /**
     * 解析 `detailContent`，返回媒体条目 + 线路。
     *
     * TVBox 的 detail 返回 `{"list":[{vod...}]}`，取第一条。
     */
    fun parseDetail(raw: String?, sourceKey: String = ""): Pair<MediaItem, List<PlayRoute>>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val vod = root["list"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
                ?: return null

            val item = parseVod(vod, sourceKey) ?: return null
            val routes = parsePlayRoutes(vod, sourceKey)
            item to routes
        }.getOrNull()
    }

    /**
     * 解析 `vod_play_from` + `vod_play_url` 成线路列表。
     *
     * 这是整个 TVBox 协议里最容易崩的一处：
     * - 两个字段长度可能不一致（from 有 3 个，url 只有 2 个）
     * - 地址里可能包含 `$` 或 `#`（罕见但存在）
     * - 空集、仅有一集
     */
    fun parsePlayRoutes(vod: JsonObject, sourceKey: String = ""): List<PlayRoute> {
        val fromRaw = vod.str("vod_play_from").orEmpty()
        val urlRaw = vod.str("vod_play_url").orEmpty()
        if (urlRaw.isBlank()) return emptyList()

        val froms = fromRaw.split("$$$").map { it.trim() }.filter { it.isNotEmpty() }
        val urlGroups = urlRaw.split("$$$")

        return urlGroups.mapIndexedNotNull { groupIdx, group ->
            val episodes = group.split("#").mapNotNull { entry ->
                val e = entry.trim()
                if (e.isEmpty()) return@mapNotNull null
                val idx = e.indexOf('$')
                if (idx <= 0) {
                    // 没有 "$"，整条就是地址
                    Episode(
                        index = 0,
                        name = "",
                        flag = froms.getOrNull(groupIdx).orEmpty(),
                        playId = e,
                        originalName = "",
                    )
                } else {
                    Episode(
                        index = 0,
                        // 原始 name 往往是整个文件名，例如
                        //   [12.75GB] Verity.2026.REPACK.2160p.AMZN.WEB-DL.DDP5.1.Atmos.mkv
                        // 直接展示会把界面撑爆，也看不出是哪一集。
                        name = cleanEpisodeName(e.substring(0, idx)),
                        flag = froms.getOrNull(groupIdx).orEmpty(),
                        playId = e.substring(idx + 1),
                        originalName = e.substring(0, idx),
                    )
                }
            }

            if (episodes.isEmpty()) return@mapIndexedNotNull null

            // 序号 + 兜底名。
            //
            // 网盘类源的"集"往往不是真正的剧集，而是**同一部片的多个文件版本**
            // （不同清晰度/不同字幕组/不同体积）。清洗后名字可能变成空串或
            // 只剩 "2160P" 这种，单独展示看不出是第几个。
            // 所以统一加 "01 / 02" 前缀，让用户在遥控器上一眼能数清。
            // 排序 + 打标。
            //
            // 网盘类源的"集"往往不是真正的剧集，而是**同一部片的多个文件版本**
            // （不同清晰度 / 不同体积 / 不同字幕组）。原始顺序是乱的，
            // 用户看到的是：
            //   01  HDR
            //   02  1080P
            //   03  4K
            // 这样根本不知道该点哪个。
            //
            // 这里按**清晰度从高到低**排序，并把标签换成一眼能懂的写法。
            val sorted = episodes
                .mapIndexed { i, ep -> i to ep }
                .sortedByDescending { (_, ep) -> qualityRank(ep.name) }
                .map { (_, ep) -> ep }

            val numbered = sorted.mapIndexed { i, ep ->
                val label = ep.name.ifBlank { "版本" + (i + 1) }
                ep.copy(
                    index = i + 1,
                    // 保留序号前缀：多版本时用户要靠它区分（4K / 1080P / HDR），
                    // 没有序号在遥控器上根本数不清点到第几个。
                    name = "%02d  %s".format(i + 1, label),
                )
            }

            PlayRoute(
                name = froms.getOrNull(groupIdx) ?: ("线路" + (groupIdx + 1)),
                episodes = numbered,
            )
        }
    }

    // ---------- 搜索 ----------

    /** 解析 `searchContent`，结构与 categoryContent 相同 */
    fun parseSearch(raw: String?, sourceKey: String = ""): List<MediaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            root["list"]?.jsonArrayOrNull()
                ?.mapNotNull { parseVod(it, sourceKey) }
                .orEmpty()
        }.getOrElse { emptyList() }
    }

    // ---------- 播放地址 ----------

    /**
     * 解析 `playerContent`。
     * ```json
     * {"parse":0,"playUrl":"","url":"https://...","header":{"User-Agent":"..."}}
     * ```
     * `parse=0` 表示 url 是直链；`parse=1` 表示需要走解析器。
     */
    fun parsePlayer(raw: String?): ParsedPlayer? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val parse = root.num("parse") ?: 0
            val url = root.str("url") ?: root.str("playUrl").orEmpty()
            val message = root.str("msg")?.takeIf { it.isNotBlank() }
            if (url.isBlank() && message == null) return null

            val jsonHeaders = playbackHeaderObject(root["header"])

            // ★ URL 里可能内嵌请求头（TVBox 的 @ 约定），必须拆出来。
            // 例：https://cdn.x.com/a.mp4@Referer=https://site.com/@User-Agent=Mozilla
            // 不拆的话整串丢给 ExoPlayer 会 404。
            val uh = UrlHeader.split(url)

            val headers = LinkedHashMap<String, String>()
            headers.putAll(jsonHeaders)
            headers.putAll(uh.headers)   // URL 内嵌的优先

            ParsedPlayer(
                url = uh.url,
                direct = parse == 0,
                headers = headers,
                parserName = root.str("jx").orEmpty().takeIf { it.isNotBlank() },
                msg = message,
                playUrl = root.str("playUrl")?.takeIf { root.str("url") != null && it.isNotBlank() },
            )
        }.getOrNull()
    }

    data class ParsedPlayer(
        val url: String,
        val direct: Boolean,
        val headers: Map<String, String>,
        val parserName: String?,
        /**
         * jar 返回的提示信息。
         *
         * 网盘类源在**未登录/转存失败/文件失效**时会带 msg，例如：
         * ```
         * {"msg":"未登录","url":""}
         * {"msg":"转存失败","url":""}
         * ```
         * 不把它带出来，用户只看到黑屏，完全不知道为什么播不了。
         */
        val msg: String? = null,
        val playUrl: String? = null,
    )

    // ---------- vod 对象 → MediaItem ----------

    fun parseVod(el: JsonElement?, sourceKey: String = ""): MediaItem? {
        val obj = el?.jsonObjectOrNull() ?: return null
        val id = obj.str("vod_id") ?: return null
        val name = obj.str("vod_name") ?: return null

        // 图片可能是相对路径，也可能带 TVBox 的 @头 后缀
        // （部分 spider 会返回 xxx.jpg@Referer=https://example.com/）
        // 不剥掉的话 Coil 会把整串当 URL，直接 404 → 海报全黑。
        // 剥掉的头登记到注册表，由图片拦截器按 URL 补回去（防盗链图床需要）。
        val pic = obj.str("vod_pic")?.let { registerArtworkHeaders(UrlHeader.split(it)) }
        val backdropRaw = obj.str("vod_pic_slide") ?: obj.str("vod_pic_screenshot")
        val backdrop = backdropRaw?.let { registerArtworkHeaders(UrlHeader.split(it)) }

        return MediaItem(
            id = "${sourceKey}_$id",
            title = name,
            originalTitle = obj.str("vod_sub")?.takeIf { it.isNotBlank() },
            poster = pic,
            backdrop = backdrop,
            year = obj.str("vod_year")?.toIntOrNull(),
            // Generic score only.
            rating = obj.str("vod_score")?.toDoubleOrNull(),
            kind = parseKind(obj.str("vod_class") ?: obj.str("type_name")),
            overview = obj.str("vod_content")?.stripHtml()?.takeIf { it.isNotBlank() },
            area = obj.str("vod_area"),
            language = obj.str("vod_lang"),
            cast = obj.str("vod_actor")?.split(",", "，", "/")?.map { it.trim() }
                ?.filter { it.isNotEmpty() }?.take(12).orEmpty(),
            director = obj.str("vod_director"),
            sourceKey = sourceKey,
            sourceId = id,
            // 时长：TVBox 的 vod_duration 有时是 "45分钟" 有时是 "00:45:00"
            durationMin = obj.str("vod_duration")?.let { parseDurationMinutes(it) },
        )
    }

    /**
     * 把 TVBox 图片地址里内嵌的请求头登记下来，并返回干净的 URL。
     *
     * 源写 `xxx.jpg@Referer=https://site.com/` 时，裸 URL 请求会被防盗链拦；
     * 头必须由图片拦截器补回去。
     */
    private fun registerArtworkHeaders(parsed: UrlHeader.Parsed): String {
        if (parsed.headers.isNotEmpty()) {
            com.reelix.app.data.image.ImageHeaderRegistry.put(parsed.url, parsed.headers)
        }
        return parsed.url
    }

    private fun parseKind(raw: String?): MediaKind {
        val s = raw.orEmpty()
        return when {
            s.contains("剧") || s.contains("连续") -> MediaKind.SERIES
            s.contains("动漫") || s.contains("动画") -> MediaKind.ANIME
            s.contains("综艺") || s.contains("真人秀") -> MediaKind.VARIETY
            s.contains("纪录") -> MediaKind.DOCUMENTARY
            s.contains("电影") || s.contains("片") -> MediaKind.MOVIE
            else -> MediaKind.UNKNOWN
        }
    }

    private fun parseDurationMinutes(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        // "00:45:00" -> 45
        if (s.contains(":")) {
            val parts = s.split(":")
            return when (parts.size) {
                3 -> parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0)
                2 -> parts[0].toIntOrNull()
                else -> null
            }
        }
        // "45分钟"
        Regex("""(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun String.stripHtml(): String =
        replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    // ---------- JsonElement 扩展 ----------

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull(): JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.num(key: String): Int? =
        this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    @Suppress("unused")
    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.let { it.booleanOrNull }

    /**
     * 清洗选集名。
     *
     * ## 为什么需要
     *
     * TVBox 源的 `vod_play_url` 里，每个条目是 `名字$地址`。
     * 网盘类源的「名字」往往直接就是文件名：
     * ```
     * [12.75GB] Verity.2026.REPACK.2160p.AMZN.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-Qshan.mkv
     * ```
     * 直接把这种字符串当"集名"展示，界面上会出现一长串撑爆布局的英文，
     * 用户也完全看不出这是第几集。
     *
     * ## 处理策略
     *
     * 1. 剥掉开头的 `[体积]` 标记
     * 2. 剥掉扩展名
     * 3. 如果去掉后仍然很长（>24 字）或全是英文，提取其中的
     *    **集数特征**（第X集/EP01/E01/01）或**清晰度**（4K/1080P/HDR）
     * 4. 实在提取不出，就用"视频 N"占位由调用方补序号
     */
    internal fun cleanEpisodeName(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        // ① 去掉开头的 [12.75GB] / 【4K】 之类
        s = s.replace(Regex("""^[\[【][^\]】]{1,20}[\]】]\s*"""), "")

        // ② 去掉扩展名
        s = s.replace(Regex("""\.(mkv|mp4|avi|ts|flv|wmv|mov|m2ts)$""", RegexOption.IGNORE_CASE), "")

        // ③ 短名直接返回（中文的"第01集"之类）
        if (s.length <= 16) return s

        // ④ 长名：尝试抽取有意义的片段
        val marks = mutableListOf<String>()

        Regex("""第\s*(\d{1,3})\s*[集话話期]""").find(s)?.let { marks += "第${it.groupValues[1]}集" }
        Regex("""(?i)\bEP?\s*(\d{1,3})\b""").find(s)?.let { marks += "EP${it.groupValues[1]}" }
        Regex("""(?i)\bE(\d{1,3})\b""").find(s)?.let { if (marks.isEmpty()) marks += "EP${it.groupValues[1]}" }

        Regex("""(?i)(4K|2160P|1080P|720P|HDR|DV|蓝光|原盘)""").find(s)?.let {
            marks += it.groupValues[1].uppercase()
        }

        if (marks.isNotEmpty()) return marks.distinct().joinToString(" ")

        // ⑤ 全是英文/符号且超长 → 只留前 16 字
        return s.take(16) + "…"
    }

    /**
     * 清晰度打分 —— 用于把高清版本排到前面。
     *
     * 返回越大越清晰。这样 4K 版本永远在 720P 前面，
     * 用户打开详情页第一个看到的就是最好的画质。
     */
    internal fun qualityRank(name: String): Int {
        val n = name.uppercase()
        var score = 0

        // 分辨率（权重最高）
        score += when {
            n.contains("8K") -> 8000
            n.contains("4K") || n.contains("2160P") -> 4000
            n.contains("2K") || n.contains("1440P") -> 2000
            n.contains("1080P") || n.contains("1080I") -> 1080
            n.contains("720P") -> 720
            n.contains("576P") -> 576
            n.contains("480P") -> 480
            else -> 0
        }

        // HDR / 杜比视界等画质增强
        if (n.contains("DOLBY") || n.contains("DV")) score += 200
        if (n.contains("HDR")) score += 150
        if (n.contains("蓝光") || n.contains("BLU")) score += 100
        if (n.contains("原盘") || n.contains("REMUX")) score += 180

        // 原画/超清这类中文标记
        if (n.contains("原画")) score += 900
        if (n.contains("超清")) score += 600
        if (n.contains("高清")) score += 300

        // 低画质扣分（放到最后）
        if (n.contains("标清")) score -= 200
        if (n.contains("流畅")) score -= 400

        return score
    }
}
