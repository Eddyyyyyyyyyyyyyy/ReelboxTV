package com.reelix.app.data.source.json

import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.data.model.Episode
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.jar.TvBoxJsonParser
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TVBox **XML 接口**（type=0）的解析器。
 *
 * ## 这是什么
 *
 * type=0 的 `api` 形如：
 * ```
 * https://cj.lziapi.com/api.php/provide/vod/at/xml/
 *                                          ↑ 注意 /at/xml/
 * ```
 * 返回的是 **RSS 风格 XML**，字段和 JSON 版一一对应：
 *
 * ```xml
 * <rss version="5.1">
 *   <list page="1" pagecount="8095" recordcount="161891">
 *     <video>
 *       <id>163386</id>
 *       <tid>15</tid>
 *       <name><![CDATA[最后一桌]]></name>
 *       <type>韩国剧</type>
 *       <pic>https://...webp</pic>
 *       <lang>韩语</lang>
 *       <area>韩国</area>
 *       <year>2026</year>
 *       <note><![CDATA[更新至第03集]]></note>
 *       <actor><![CDATA[...]]></actor>
 *       <director><![CDATA[...]]></director>
 *       <des><![CDATA[<p>简介</p>]]></des>
 *       <dl>
 *         <dd flag="liangzi"><![CDATA[第01集$https://.../share/xxx]]></dd>
 *         <dd flag="lzm3u8"><![CDATA[第01集$https://.../index.m3u8]]></dd>
 *       </dl>
 *     </video>
 *   </list>
 * </rss>
 * ```
 *
 * ## 两条线路的差别（实测）
 *
 * ```
 * flag="liangzi"  → .../share/xxx   ← 分享页，要嗅探
 * flag="lzm3u8"   → .../index.m3u8  ← 直链，能直接播
 * ```
 * 所以解析出来的 `flag` **必须原样保留**——上层会据此判断要不要嗅探。
 *
 * ## 为什么不用 XmlPullParser
 *
 * `DocumentBuilderFactory` 在 Android 上自带（`org.w3c.dom`），
 * 不需要额外依赖。虽然 DOM 比 pull 慢，但单次响应 40KB~400KB，
 * 解析耗时都在 10ms 级，不是瓶颈。
 */
object XmlParser {

    /** 解析首页（含分类 + 推荐列表） */
    fun parseHome(xml: String?, sourceKey: String): HomeContent {
        val root = parse(xml) ?: return HomeContent()
        val videos = root.getElementsByTagName("video")

        val items = (0 until videos.length).mapNotNull { i ->
            parseVideo(videos.item(i) as? Element ?: return@mapNotNull null, sourceKey)
        }

        // XML 版首页通常不带 <class>，分类要从 list 里推
        // （按 tid + type 去重，够用）
        val cats = LinkedHashMap<String, String>()
        (0 until videos.length).forEach { i ->
            val v = videos.item(i) as? Element ?: return@forEach
            val tid = v.text("tid").orEmpty()
            val type = v.text("type").orEmpty()
            if (tid.isNotBlank() && type.isNotBlank() && !cats.containsKey(tid)) cats[tid] = type
        }

        return HomeContent(
            categories = cats.map { (id, name) ->
                com.reelix.app.data.source.SourceCategory(id, name, emptyList())
            },
            recommendations = items,
        )
    }

    /** 解析分类列表 */
    fun parseCategory(xml: String?, sourceKey: String): CategoryContent {
        val root = parse(xml) ?: return CategoryContent()
        val videos = root.getElementsByTagName("video")

        val items = (0 until videos.length).mapNotNull { i ->
            parseVideo(videos.item(i) as? Element ?: return@mapNotNull null, sourceKey)
        }

        val list = root.getElementsByTagName("list").item(0) as? Element
        return CategoryContent(
            items = items,
            page = list?.getAttribute("page")?.toIntOrNull() ?: 1,
            pageCount = list?.getAttribute("pagecount")?.toIntOrNull() ?: 1,
            total = list?.getAttribute("recordcount")?.toIntOrNull() ?: items.size,
        )
    }

    /** 解析搜索（结构同分类） */
    fun parseSearch(xml: String?, sourceKey: String): List<MediaItem> =
        parseCategory(xml, sourceKey).items

    /** 解析详情（含线路） */
    fun parseDetail(xml: String?, sourceKey: String): Pair<MediaItem, List<PlayRoute>>? {
        val root = parse(xml) ?: return null
        val videos = root.getElementsByTagName("video")
        if (videos.length == 0) return null

        val v = videos.item(0) as? Element ?: return null
        val item = parseVideo(v, sourceKey) ?: return null

        return item to parseRoutes(v, sourceKey)
    }

    // ---------- 内部 ----------

    private fun parse(xml: String?): Element? {
        if (xml.isNullOrBlank()) return null

        // ★ 脏数据修补（对齐 TVBox 的 SourceViewModel.xml）
        //
        // 部分采集站的 XML 会输出**空标签**：
        // ```
        // <year></year>
        // <state></state>
        // ```
        // 严格解析器会把这些当"缺少值"处理，
        // 我们的 DOM 解析虽然能过，但 toIntOrNull() 会得到 null，
        // 表现是年份丢失。
        //
        // TVBox 的做法是直接把空标签填成 0 —— 我们也照做，
        // 至少保证数值字段不丢。
        val fixed = xml
            .replace("<year></year>", "<year>0</year>")
            .replace("<state></state>", "<state>0</state>")
            .replace("<score></score>", "<score>0</score>")

        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // 关掉外部实体：接口返回的内容不可信
                isNamespaceAware = false
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(fixed.toByteArray(Charsets.UTF_8)))
            doc.documentElement
        }.getOrNull()
    }

    private fun parseVideo(v: Element, sourceKey: String): MediaItem? {
        val id = v.text("id") ?: return null
        val name = v.text("name") ?: return null

        return MediaItem(
            id = "${sourceKey}_$id",
            sourceId = id,
            sourceKey = sourceKey,
            title = name,
            originalTitle = v.text("sub")?.takeIf { it.isNotBlank() },
            poster = v.text("pic")?.takeIf { it.isNotBlank() },
            year = v.text("year")?.toIntOrNull(),
            area = v.text("area")?.takeIf { it.isNotBlank() },
            language = v.text("lang")?.takeIf { it.isNotBlank() },
            overview = v.text("des")?.let { stripHtml(it) }?.takeIf { it.isNotBlank() },
            kind = parseKind(v.text("type").orEmpty()),
            rating = v.text("score")?.toDoubleOrNull(),
        )
    }

    /**
     * 解析 `<dl><dd flag="xxx">第01集$url#第02集$url</dd></dl>`。
     *
     * 每个 `<dd>` 是一条线路，`flag` 属性是线路名，
     * 内部用 `#` 分集、`$` 分名字与地址 —— 和 JSON 版完全一致的约定。
     *
     * 所以这里直接复用 [TvBoxJsonParser.parsePlayRoutes] 的切分逻辑，
     * 只是先把 XML 拼成一个等价的 `vod_play_from` / `vod_play_url` 结构。
     */
    private fun parseRoutes(v: Element, sourceKey: String): List<PlayRoute> {
        val dl = runCatching {
            val nodes = v.getElementsByTagName("dl")
            if (nodes.length == 0) return emptyList()
            nodes.item(0) as Element
        }.getOrNull() ?: return emptyList()

        val dds = dl.getElementsByTagName("dd")
        if (dds.length == 0) return emptyList()

        val froms = mutableListOf<String>()
        val urls = mutableListOf<String>()

        for (i in 0 until dds.length) {
            val dd = dds.item(i) as? Element ?: continue
            val flag = dd.getAttribute("flag").ifBlank { "线路${i + 1}" }
            val content = dd.textContent.orEmpty().trim()
            if (content.isBlank()) continue
            froms.add(flag)
            urls.add(content)
        }

        if (urls.isEmpty()) return emptyList()

        // 拼成 JSON 版的格式，复用同一套切分 + 清洗逻辑
        val fake = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"vod_play_from":${quote(froms.joinToString("$$$"))},""" +
                """"vod_play_url":${quote(urls.joinToString("$$$"))}}"""
        ).let { it as kotlinx.serialization.json.JsonObject }

        return TvBoxJsonParser.parsePlayRoutes(fake, sourceKey)
    }

    /** JSON 字符串转义 */
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** 取子元素文本（含 CDATA） */
    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        if (nodes.length == 0) return null
        val n: Node = nodes.item(0)
        return n.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 从 `<type>` 判断内容类型。
     *
     * XML 源的 `type` 字段是中文分类名（韩国剧/国产剧/动漫/综艺…），
     * 用关键词匹配就够，不需要完整词表。
     */
    private fun parseKind(typeName: String): com.reelix.app.data.model.MediaKind {
        val s = typeName.lowercase()
        return when {
            s.contains("纪录") || s.contains("doc") ->
                com.reelix.app.data.model.MediaKind.DOCUMENTARY
            s.contains("综艺") || s.contains("show") || s.contains("variety") ->
                com.reelix.app.data.model.MediaKind.VARIETY
            s.contains("动漫") || s.contains("动画") || s.contains("anime") ->
                com.reelix.app.data.model.MediaKind.ANIME
            s.contains("剧") || s.contains("tv") || s.contains("series") ->
                com.reelix.app.data.model.MediaKind.SERIES
            s.isBlank() -> com.reelix.app.data.model.MediaKind.UNKNOWN
            else -> com.reelix.app.data.model.MediaKind.MOVIE
        }
    }
}
