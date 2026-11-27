package com.reelix.app.data.source.online

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import kotlinx.serialization.json.Json
import java.util.LinkedHashMap

/**
 * 在线页快照缓存。
 *
 * 这是纯逻辑层：不碰 Android Context，磁盘读写由 [OnlineCacheStore] 注入。
 * 缓存只解决“先看到上次的内容”，不能把旧数据伪装成本次网络结果，
 * 因此每项都带 [savedAt]，UI 可以据此提示正在后台刷新。
 */
class OnlineCache(
    private val store: Store = Store.Memory,
    /** 缓存键的站点部分；空值会让换源后的旧快照无法区分。 */
    private val defaultSiteKey: String = "",
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 缓存持久化接口；生产环境用文件，测试用内存。 */
    interface Store {
        fun read(): String?
        fun write(json: String)

        object Memory : Store {
            @Volatile
            var value: String? = null
            override fun read(): String? = value
            override fun write(json: String) {
                value = json
            }
        }
    }

    data class Value<T>(val data: T, val savedAt: Long)

    private data class DetailKey(val site: String, val vodId: String)

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private var home: Value<HomeContent>? = null
    private val categories = LinkedHashMap<String, Value<CategoryContent>>()
    private val details = LinkedHashMap<DetailKey, Value<MediaDetail>>()
    private var subscription: Value<String>? = null
    private var onlineSites: List<OnlineSiteSnapshot>? = null
    private var selectedSiteKey = ""
    private var selectedCategoryId = ""
    private var loaded = false

    /** 只保留最近常用的详情，避免长播/搜遍网站后内存一直涨。 */
    private val maxDetails = 160
    private val maxCategories = 48

    /**
     * 从磁盘恢复。只加载，不做网络请求。
     *
     * @return 是否成功读到过有效缓存
     */
    fun load(): Boolean = synchronized(lock) {
        if (loaded) {
            return memoryHasData()
        }

        // Reads can fail independently of decode (power loss, corrupt temp file).
        // A failed read must not set loaded=true and permanently hide a later good read.
        val text = runCatching { store.read() }.getOrElse {
            AndroidLog.w(TAG, "cache read failed: ${it.message}")
            return false
        } ?: return false
        // An empty file is authoritative; a failed read is not, so the next
        // entry to ONLINE can retry instead of staying permanently "empty".
        if (text.isBlank()) {
            loaded = true
            return false
        }
        AndroidLog.d(TAG, "read ${text.length} chars from disk")
        val catalog = runCatching { json.decodeFromString(CachedCatalog.serializer(), text) }.getOrNull()
            ?: return false.also { AndroidLog.w(TAG, "catalog decode failed; will retry later") }
        if (catalog.version != CATALOG_VERSION) {
            AndroidLog.w(TAG, "catalog version mismatch: ${catalog.version}")
            return false
        }
        loaded = true

        home = catalog.home
            ?.takeIf { it.siteKey.isNotBlank() }
            ?.let { Value(it.home.toHome(), it.savedAt) }
        homeSiteKey = catalog.home?.siteKey.orEmpty()

        catalog.categories
            .filter { it.siteKey.isNotBlank() && it.categoryId.isNotBlank() }
            .forEach { snap ->
                categories[key(snap.siteKey, snap.categoryId)] =
                    Value(snap.content.toContent(), snap.savedAt)
            }
        trimCategories()

        catalog.details
            .filter { it.siteKey.isNotBlank() && it.vodId.isNotBlank() }
            .forEach { snap ->
                details[key(DetailKey(snap.siteKey, snap.vodId))] =
                    Value(snap.detail.toDetail(), snap.savedAt)
            }
        trimDetails()

        onlineSites = catalog.sites.filter { it.key.isNotBlank() }
        selectedSiteKey = catalog.selectedSiteKey
        selectedCategoryId = catalog.selectedCategoryId

        subscription = catalog.subscription?.takeIf { it.source.isNotBlank() && it.json.isNotBlank() }
            ?.let { Value(it.json, it.savedAt) }

        AndroidLog.i(
            TAG,
            "restored site=${homeSiteKey.ifBlank { "-" }} home=${home?.data?.recommendations?.size ?: 0} " +
                "categories=${categories.size} details=${details.size} subscription=${subscription != null}",
        )
        return true
    }

    private fun memoryHasData(): Boolean =
        home != null || categories.isNotEmpty() || details.isNotEmpty() ||
            subscription != null || !onlineSites.isNullOrEmpty()

    fun cachedHome(): Value<HomeContent>? = synchronized(lock) { home }

    /**
     * Restore the exact site list that the user last saw.
     *
     * The raw config is enough to reparse, but it still needs jar initialization
     * before the online page can open. Keeping the site descriptors makes the
     * first paint independent of app startup.
     */
    fun cachedSites(): List<OnlineSiteSnapshot>? = synchronized(lock) { onlineSites }

    fun putSites(
        sites: List<OnlineSiteSnapshot>,
        selectedKey: String? = null,
        selectedCategory: String? = null,
    ) {
        synchronized(lock) {
            if (sites.isEmpty()) return
            onlineSites = sites
            if (selectedKey != null) selectedSiteKey = selectedKey
            if (selectedCategory != null) selectedCategoryId = selectedCategory
            persist()
        }
    }

    fun cachedSelectedSiteKey(): String = synchronized(lock) { selectedSiteKey }

    fun cachedSelectedCategoryId(): String = synchronized(lock) { selectedCategoryId }

    /** The category that belongs to the site, never a category id left from another site. */
    fun cachedCategoryForSite(siteKey: String): Value<CategoryContent>? = synchronized(lock) {
        if (siteKey.isBlank()) return null
        categories.entries.firstOrNull { it.key.startsWith("$siteKey|") }?.value
    }

    fun cacheDebugSummary(): String = synchronized(lock) {
        "sites=${onlineSites?.size ?: 0} selected=$selectedSiteKey " +
            "home=${home?.data?.recommendations?.size ?: 0} categories=${categories.size} " +
            "details=${details.size} subscription=${subscription != null}"
    }

    fun invalidateCorruptCache() = synchronized(lock) {
        // Keep `loaded` true: a recurring disk read failure should not be retried
        // on every frame. New Home/Category writes still persist normally.
        home = null
        categories.clear()
        details.clear()
        onlineSites = null
        selectedSiteKey = ""
        selectedCategoryId = ""
        subscription = null
        subscriptionSource = ""
        subscriptionUrl = ""
    }

    fun cachedHomeFor(siteKey: String): Value<HomeContent>? = synchronized(lock) {
        home.takeIf { siteKey.isNotBlank() && homeSiteKey == siteKey }
    }

    fun cachedCategory(siteKey: String, categoryId: String): Value<CategoryContent>? =
        synchronized(lock) { categories[key(siteKey, categoryId)] }

    fun putCategory(
        siteKey: String,
        categoryId: String,
        content: CategoryContent,
    ): Value<CategoryContent> = synchronized(lock) {
        val saved = Value(content, now())
        if (content.items.isNotEmpty()) {
            categories.remove(key(siteKey, categoryId))
            categories[key(siteKey, categoryId)] = saved
            trimCategories()
            persist()
        }
        saved
    }

    fun cachedDetail(siteKey: String, vodId: String): Value<MediaDetail>? =
        synchronized(lock) { details[key(DetailKey(siteKey, vodId))] }

    /** 详情缓存必须先有线路；没有线路的空壳会把“加载失败”伪装成“加载成功”。 */
    fun putDetail(
        siteKey: String,
        vodId: String,
        detail: MediaDetail,
    ): Value<MediaDetail>? = synchronized(lock) {
        if (detail.routes.none { route -> route.episodes.isNotEmpty() }) return null
        val saved = Value(detail, now())
        val k = DetailKey(siteKey, vodId)
        details.remove(k)
        details[k] = saved
        trimDetails()
        persist()
        saved
    }

    fun invalidateDetail(siteKey: String, vodId: String) {
        synchronized(lock) {
            if (details.remove(key(DetailKey(siteKey, vodId))) != null) persist()
        }
    }

    fun cachedSubscription(source: String): Value<String>? = synchronized(lock) {
        // 配置 source 变了就不能拿旧订阅冒充当前配置，否则站点 key 会错位。
        subscription?.takeIf { source.isNotBlank() && source == subscriptionSource }
    }

    /**
     * Startup-only fallback. A temporary DNS failure must not hide the last
     * downloaded config; callers can refresh against the current source later.
     */
    fun cachedSubscriptionAny(): Value<String>? = synchronized(lock) { subscription }

    /** 上次保存订阅时使用的原始地址；用来判断用户是否改了订阅源。 */
    fun cachedSubscriptionSource(): String = synchronized(lock) { subscriptionSource }

    fun putSubscription(source: String, configUrl: String, json: String): Value<String>? =
        synchronized(lock) {
            if (json.isBlank()) return null
            val saved = Value(json, now())
            subscription = saved
            subscriptionSource = source
            subscriptionUrl = configUrl
            persist()
            saved
        }

    fun clear() = synchronized(lock) {
        home = null
        homeSiteKey = ""
        categories.clear()
        details.clear()
        subscription = null
        onlineSites = null
        selectedSiteKey = ""
        selectedCategoryId = ""
        subscriptionSource = ""
        subscriptionUrl = ""
        loaded = true
        runCatching { store.write("") }
    }

    /** 立即把内存里的状态写盘；测试可直接验证序列化结果。 */
    fun flush() = synchronized(lock) { persist() }

    private var homeSiteKey = ""
    private var subscriptionSource = ""
    private var subscriptionUrl = ""

    fun putHomeForSite(siteKey: String, content: HomeContent): Value<HomeContent>? =
        synchronized(lock) {
            if (siteKey.isBlank()) return null
            if (content.categories.isEmpty() && content.recommendations.isEmpty()) return null
            val saved = Value(content, now())
            home = saved
            homeSiteKey = siteKey
            persist()
            saved
        }

    private fun persist() {
        val catalog = CachedCatalog(
            version = CATALOG_VERSION,
            sites = onlineSites.orEmpty(),
            selectedSiteKey = selectedSiteKey,
            selectedCategoryId = selectedCategoryId,
            home = home?.let {
                val site = homeSiteKey.ifBlank { defaultSiteKey }
                CachedHomeSnapshot(site, CachedHomeContent.from(it.data), it.savedAt)
            },
            categories = categories.entries.map { (k, v) ->
                val parts = k.split('|', limit = 2)
                CachedCategorySnapshot(
                    siteKey = parts.getOrNull(0).orEmpty(),
                    categoryId = parts.getOrNull(1).orEmpty(),
                    content = CachedCategoryContent.from(v.data),
                    savedAt = v.savedAt,
                )
            },
            details = details.entries.map { (k, v) ->
                CachedDetailSnapshot(
                    siteKey = k.site,
                    vodId = k.vodId,
                    detail = CachedMediaDetail.from(v.data),
                    savedAt = v.savedAt,
                )
            },
            subscription = subscription?.let {
                CachedSubscription(subscriptionSource, subscriptionUrl, it.data, it.savedAt)
            },
        )
        runCatching {
            val encoded = json.encodeToString(CachedCatalog.serializer(), catalog)
            store.write(encoded)
            AndroidLog.d(TAG, "wrote ${encoded.length} chars to disk")
        }.onFailure {
            AndroidLog.e(TAG, "cache write failed: ${it.message}", it)
        }
    }

    private fun trimDetails() {
        while (details.size > maxDetails) {
            val oldest = details.keys.firstOrNull() ?: break
            details.remove(oldest)
        }
    }

    private fun trimCategories() {
        while (categories.size > maxCategories) {
            val oldest = categories.keys.firstOrNull() ?: break
            categories.remove(oldest)
        }
    }

    private fun key(site: String, categoryId: String) = "$site|$categoryId"
    private fun key(detail: DetailKey) = detail

    companion object {
        private const val CATALOG_VERSION = 1
        private const val TAG = "OnlineCache"
    }
}

/**
 * 播放结果的短期内存缓存。
 *
 * 它不写盘：源站直链（尤其网盘）通常有签名有效期，
 * 把“上次能播”的地址跨会话复用会直接变成播放失败。
 */
object PlayResolutionCache {

    /** Only the URL and headers are needed; never cache parser diagnostics or whole responses. */
    data class Entry(val url: String, val headers: Map<String, String>, val at: Long)

    private val maxEntries = 48
    private val ttlMs = 2 * 60 * 1000L
    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    fun key(siteKey: String, routeFlag: String, playId: String): String =
        "$siteKey|$routeFlag|$playId"

    fun get(key: String, now: Long = System.currentTimeMillis()): Entry? =
        synchronized(map) {
            val entry = map[key] ?: return null
            if (now - entry.at > ttlMs || entry.at > now + 60_000) {
                map.remove(key)
                null
            } else {
                entry
            }
        }

    fun put(
        key: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ) {
        // 只缓存拿到地址的结果，错误结果必须允许下一次重新请求。
        if (url.isBlank()) return
        synchronized(map) { map[key] = Entry(url, headers, now) }
    }

    fun remove(key: String) {
        synchronized(map) { map.remove(key) }
    }

    fun clear() {
        synchronized(map) { map.clear() }
    }
}

/**
 * 这些模型在 UI 层频繁读取，给它们保留可读的便捷属性，
 * 避免每个页面都重复写 `routes.flatMap { it.episodes }`。
 */
val MediaDetail.hasPlayableEpisodes: Boolean
    get() = routes.any { route -> route.episodes.any { it.playId.isNotBlank() || !it.url.isNullOrBlank() } }

val PlayRoute.playableEpisodes: List<Episode>
    get() = episodes.filter { it.playId.isNotBlank() || !it.url.isNullOrBlank() }
