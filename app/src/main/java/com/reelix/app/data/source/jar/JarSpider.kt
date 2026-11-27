package com.reelix.app.data.source.jar

import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.PlayResolution
import com.reelix.app.data.source.Spider
import com.reelix.app.data.source.TvBoxSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JarSpider —— 把 TVBox 的 jar 型站点适配成 Reelbox 的 [Spider] 接口。
 *
 * ## 数据流
 *
 * ```
 * TVBox 配置 sites[i]
 *   { key, name, type=3, api="csp_WoggGuard", ext="..." }
 *          ↓
 *    SpiderJarLoader  下载并加载 jar → ClassLoader
 *          ↓
 *    SpiderBridge     反射调 Init.init / getSpider
 *          ↓
 *    JarSpider        实现 Reelbox 的 Spider 接口
 *          ↓
 *    TvBoxJsonParser  把 TVBox JSON 转成领域模型
 * ```
 *
 * ## 为什么要包一层接口
 *
 * jar 里的方法返回的是**原始 JSON 字符串**，UI 层不该碰 JSON。
 * 这个类的职责就是：调 jar → 拿到 JSON → 解析成 `MediaItem` / `MediaDetail`。
 *
 * ## 线程
 *
 * jar 内部全是同步网络请求（OkHttp 阻塞调用），
 * 所以每个方法都切到 [Dispatchers.IO]。
 */
class JarSpider(
    override val key: String,
    override val name: String,
    override val searchable: Boolean,
    private val site: TvBoxSite,
    private val bridge: SpiderBridge,
    private val vipFlags: List<String> = emptyList(),
) : Spider {

    private val instanceHolder = InstanceHolder()

    /**
     * jar 里的 spider 实例，懒加载。
     *
     * 先走插件自己的单例，再退回直接实例化：
     * 1. `getSpider` —— 标准 catvod 约定，返回插件内部的单例。
     *    夸父盘这类插件把本地代理/播放会话挂在单例里，每次 new 实例会让
     *    playerContent 解析出的 `127.0.0.1:端口` 与实际在听的代理对不上。
     * 2. `instantiateSprite` —— 直接反射 new 出 spider 类。
     *    绕过 Init.getSpider，用于自研框架的 jar（`lubin.php` 那类，
     *    Init 方法名被混淆、没有 getSpider），或加固库导致 getSpider 失败时。
     */
    private val spider: Any? get() = instanceHolder.get {
        val api = site.api.ifBlank { "csp_${site.key}" }
        bridge.getSpider(api) ?: bridge.instantiateSprite(api, site.ext)
    }

    /** Reuse the initialized object without rebuilding it; only metadata changes. */
    fun withRefreshedSite(newSite: TvBoxSite, instance: Any?, flags: List<String> = vipFlags): JarSpider? {
        if (instance == null) return null
        return JarSpider(
            key = newSite.key,
            name = newSite.name,
            searchable = newSite.searchable == 1,
            site = newSite,
            bridge = bridge,
            vipFlags = flags,
        ).apply { instanceHolder.seed(instance) }
    }

    /** Access the already-initialized object without forcing a new initialization. */
    fun cachedSpiderInstance(): Any? = instanceHolder.valueOrNull()

    private class InstanceHolder {
        @Volatile
        private var initialized = false
        private var value: Any? = null

        fun get(factory: () -> Any?): Any? {
            if (initialized) return value
            synchronized(this) {
                if (!initialized) {
                    value = factory()
                    initialized = true
                }
                return value
            }
        }

        fun seed(instance: Any) {
            synchronized(this) {
                value = instance
                initialized = true
            }
        }

        fun valueOrNull(): Any? = if (initialized) value else null
    }

    /**
     * 该站点是否可用。
     *
     * ★ 不要在构建站点列表时触发懒加载：加固 jar 首次初始化可达十几秒，
     *   之前 SourceManager 遍历站点时调用本属性，等于把“加载列表”变成“初始化全部站点”。
     */
    val available: Boolean
        get() = if (bridge.isReady) true else cachedSpiderInstance() != null

    override suspend fun home(): HomeContent = withContext(Dispatchers.IO) {
        val s = spider ?: return@withContext HomeContent()

        // ① 分类 + 第一屏
        val raw = bridge.homeContent(s, filter = true)
        val base = TvBoxJsonParser.parseHome(raw, sourceKey = key)

        // ② 推荐位（单独接口）
        //
        // 为什么单独取：很多源的推荐位和分类是两个接口，
        // 不调 homeVideoContent 首页就只有分类、没有推荐内容。
        // 没有这个方法时返回 null，不影响主流程。
        val recRaw = bridge.homeVideoContent(s)
        if (recRaw.isNullOrBlank()) return@withContext base

        val recs = TvBoxJsonParser.parseCategory(recRaw, sourceKey = key).items
        if (recs.isEmpty()) return@withContext base

        android.util.Log.d("JarSpider", "[$key] 首页推荐 ${recs.size} 条")
        base.copy(recommendations = recs)
    }

    override suspend fun category(
        categoryId: String,
        page: Int,
        extend: Map<String, String>,
    ): CategoryContent = withContext(Dispatchers.IO) {
        val s = spider ?: return@withContext CategoryContent()

        // ★ 诊断：先按 TVBox 原版的参数调一次
        //
        // 原版 SourceViewModel.getList() 里是：
        //   sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect)
        // 注意第三个参数**恒为 true**，和筛选器有没有值无关。
        //
        // 我们之前传的是 `extend.isNotEmpty()`，没有筛选条件时传 false ——
        // 实测这种情况玩偶源返回 `{"total":2147483647,"limit":72,"list":[]}`，
        // 分类里 0 条内容；而多多/木偶用同样代码却能出 72 条，
        // 怀疑就是 filter 参数导致的差异。
        val filterFlag = extend.isNotEmpty()

        val raw = bridge.categoryContent(
            s,
            tid = categoryId,
            page = page.toString(),
            filter = filterFlag,
            extend = extend,
        )

        android.util.Log.i(
            "Reelbox",
            "CAT[$key] tid=$categoryId pg=$page filter=$filterFlag extend=$extend -> ${raw?.take(400) ?: "null"}"
        )

        val parsed = TvBoxJsonParser.parseCategory(raw, sourceKey = key)

        // 如果是空列表，再按 filter=true 重试一次，对比两次返回
        if (parsed.items.isEmpty()) {
            val retry = runCatching {
                bridge.categoryContent(
                    s,
                    tid = categoryId,
                    page = page.toString(),
                    filter = true,
                    extend = extend,
                )
            }.getOrNull()

            android.util.Log.i(
                "Reelbox",
                "CAT[$key] 【filter=true 重试】 tid=$categoryId -> ${retry?.take(400) ?: "null"}"
            )

            val retried = TvBoxJsonParser.parseCategory(retry, sourceKey = key)
            if (retried.items.isNotEmpty()) {
                android.util.Log.w(
                    "Reelbox",
                    "CAT[$key] 结论：filter=true 能出 ${retried.items.size} 条，filter=$filterFlag 出 0 条"
                )
                return@withContext retried
            }
        }

        parsed
    }

    override suspend fun detail(id: String): MediaDetail = withContext(Dispatchers.IO) {
        val s = spider ?: return@withContext MediaDetail(item = stubItem(id))
        val raw = bridge.detailContent(s, id)

        val parsed = TvBoxJsonParser.parseDetail(raw, sourceKey = key)
        if (parsed == null) {
            MediaDetail(item = stubItem(id))
        } else {
            val (item, routes) = parsed
            MediaDetail(
                item = item.copy(sourceKey = key),
                routes = routes,
            )
        }
    }

    override suspend fun search(keyword: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val s = spider ?: return@withContext emptyList()
            val raw = bridge.searchContent(s, keyword, quick = false)
            TvBoxJsonParser.parseSearch(raw, sourceKey = key)
        }

    override suspend fun resolvePlayUrl(routeFlag: String, episodeId: String): PlayResolution =
        withContext(Dispatchers.IO) {
            val s = spider ?: return@withContext PlayResolution(url = "", direct = false)

            val raw = bridge.playerContent(s, routeFlag, episodeId, vipFlags = vipFlags)
            android.util.Log.i(
                "Reelbox",
                "playerContent(flag=$routeFlag, id=${episodeId.take(50)}) => ${raw?.take(400) ?: "null"}"
            )
            val parsed = TvBoxJsonParser.parsePlayer(raw)
                ?: return@withContext PlayResolution(
                    url = "",
                    direct = false,
                    msg = "源没有返回可解析的内容",
                )

            if (parsed.url.isBlank() && !parsed.msg.isNullOrBlank()) {
                // 把 jar 的原因直接打到日志：未登录/转存失败/文件失效都在这里
                android.util.Log.w("Reelbox", "播放被拒绝[$routeFlag]: ${parsed.msg}")
            }

            PlayResolution(
                url = parsed.url,
                headers = parsed.headers,
                direct = parsed.direct,
                parserName = parsed.parserName,
                msg = parsed.msg,
                playUrl = parsed.playUrl,
                requiresParsing = !parsed.direct,
            )
        }

    /**
     * 原始分类调用 —— **不过解析层**，直接给回 jar 吐的 JSON 字符串。
     *
     * ## 为什么需要这条
     *
     * 网盘配置中心页面是 spider 用 `categoryContent("settings")` 吐出来的 **HTML**，
     * 藏在返回 JSON 的 `vod_content` 字段里。
     * 正常路径会被 [TvBoxJsonParser.parseVod] 里的 `stripHtml()` 洗掉标签，
     * 页面就废了。
     *
     * 所以扫码页这条链路必须绕开解析层，拿原始字符串自己挖 HTML。
     */
    suspend fun rawCategory(tid: String, page: Int = 1): String? = withContext(Dispatchers.IO) {
        val s = spider ?: return@withContext null
        bridge.categoryContent(s, tid = tid, page = page.toString(), filter = true)
    }

    /** 原始详情调用，同上 */
    suspend fun rawDetail(id: String): String? = withContext(Dispatchers.IO) {
        val s = spider ?: return@withContext null
        bridge.detailContent(s, id)
    }

    /** jar 拿不到详情时的占位，避免上层空指针 */
    private fun stubItem(id: String) = MediaItem(
        id = "${key}_$id",
        title = name.ifBlank { "未知" },
        sourceKey = key,
        sourceId = id,
    )

    companion object {
        /**
         * 判断一个站点配置是否是 jar 型。
         *
         * TVBox 的 type 约定：
         *  0 = csp_（走 jar）
         *  1 = JSON 接口
         *  3 = jar / csp（新版统一用 3）
         *  4 = 内置
         */
        fun isJarType(site: TvBoxSite): Boolean =
            site.type == 3 || site.type == 0 || site.api.startsWith("csp_")
    }
}
