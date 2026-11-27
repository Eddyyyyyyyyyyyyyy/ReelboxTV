package com.reelix.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.reelix.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One saved TVBox subscription. Only the active URL is loaded at a time. */
data class SavedSubscription(
    val id: String = "",
    val name: String = "",
    val url: String = "",
)

/**
 * 应用设置仓库。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：
 *  - DataStore 需要额外依赖，且在这台机器上没有缓存，要联网拉
 *  - 设置项就这么几个，SharedPreferences 完全够用
 *  - 电视端对启动速度敏感，SharedPreferences 是同步读取、首帧就能拿到值
 *
 * 但对外暴露 StateFlow，UI 订阅方式和 DataStore 一致，
 * 将来要换实现不影响调用方。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 数据模型 ----------

    data class AppSettings(
        val appearance: AppearancePreferences = AppearancePreferences(),
        // Alist 片库
        val alistUrl: String = DEFAULT_ALIST_URL,
        val alistUser: String = DEFAULT_ALIST_USER,
        val alistPass: String = DEFAULT_ALIST_PASS,
        /** 扫描起始目录，空表示根目录 */
        val alistScanRoot: String = "/",
        /** 进入片库时自动扫描 */
        val autoScan: Boolean = true,
        /** 片库数据源：ALIST / WEBDAV */
        val librarySource: String = "ALIST",
        /** Multi-source switches; a blank address still means "not configured". */
        val alistEnabled: Boolean = true,
        val webdavEnabled: Boolean = false,
        val embyEnabled: Boolean = false,
        val webdavUrl: String = "",
        val webdavUser: String = "",
        val webdavPass: String = "",
        val embyUrl: String = "",
        val embyApiKey: String = "",
        val embyUser: String = "",
        val embyPass: String = "",

        // TVBox 源
        /** 订阅配置地址，空表示未配置 */
        val tvboxConfigUrl: String = "",
        /** 手动保存的订阅库；当前只加载 tvboxConfigUrl 指向的一份。 */
        val savedSubscriptions: List<SavedSubscription> = emptyList(),

        // 播放偏好
        /** 播放时保持屏幕常亮 */
        val keepScreenOn: Boolean = true,
        /** 控制层自动隐藏秒数 */
        val controlsHideSeconds: Int = 3,
        /** Stable enum name; older installs default to automatic video decoding. */
        val defaultDecoder: String = "AUTO",
        /** Player control panel size: COMPACT / STANDARD / LARGE. */
        val playerPanelSize: String = "STANDARD",

        // ---- TMDB 横版剧照刮削 ----
        //
        // 为什么不内置 key：这是用户自己的私有凭证。
        // 内置的话一旦被滥用，所有用户一起被封。让想用的人自己填更合理。
        //
        // 空字符串 = 功能关闭，App 行为和没有这个功能完全一样。
        /** TMDB v3 API Key（32 位十六进制），空表示未配置 */
        val tmdbApiKey: String = "",
        /** 图片/简介语言 */
        val tmdbLanguage: String = "zh-CN",
        /** 是否启用（即使填了 key 也可以临时关掉） */
        val tmdbEnabled: Boolean = !BuildConfig.PUBLIC_BUILD,
        /**
         * 已验证通过的 Key 指纹。
         *
         * 规则：只有测试成功过的 Key 才允许开启 TMDB。存指纹而不是明文，
         * 换 Key 后指纹不匹配 → 需要重新测试，避免"改了 Key 但没验证就启用"。
         */
        val tmdbVerifiedKey: String = "",
        /**
         * 刮削渠道列表，按顺序决定优先级。
         *
         * 内置源（TMDB / 在线源）固定在前，这里存的是用户自己加的渠道。
         * 只有 enabled 的参与刮削；空列表 = 不启用任何外部渠道。
         */
        val artworkChannels: List<com.reelix.app.data.image.ArtworkChannel> = emptyList(),
        /**
         * 刮削优先级（TMDB / STREAMX / TVMAZE 的排列）。
         *
         * 在线源固定垫底，不参与排序：它基本只有竖版海报。
         */
        val artworkOrder: List<String> = if (BuildConfig.PUBLIC_BUILD)
            listOf("DOUBAN", "STREAMX", "TVMAZE") else listOf("TMDB", "STREAMX", "TVMAZE"),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** 当前值（同步读取，首帧可用） */
    val current: AppSettings get() = _state.value

    // ---------- 读写 ----------

    private fun load(): AppSettings {
        val tvboxUrl = prefs.getString(KEY_TVBOX_URL, "") ?: ""
        val storedSubscriptions = parseSubscriptions(prefs.getString(KEY_SUBSCRIPTIONS, ""))
        val subscriptions = if (storedSubscriptions.isEmpty() && tvboxUrl.isNotBlank()) {
            listOf(SavedSubscription(id = subscriptionId(tvboxUrl), name = subscriptionName(tvboxUrl), url = tvboxUrl))
        } else {
            storedSubscriptions
        }
        val storedTmdbKey = prefs.getString(KEY_TMDB_API_KEY, "") ?: ""
        return AppSettings(
        appearance = AppearancePreferences(
            order = prefs.getString("navigation_order", "")?.split(',').orEmpty(),
            hidden = prefs.getString("navigation_hidden", "")?.split(',')?.toSet().orEmpty(),
            posterColumns = prefs.getInt("poster_columns", 6),
            // 旧版本存过 INFUSE/NETFLIX/APPLE_TV，读取时迁移到新 id。
            layoutTheme = normalizedLayoutTheme(prefs.getString("layout_theme", "ORIGINAL") ?: "ORIGINAL"),
        ).normalized(),
        alistUrl = prefs.getString(KEY_ALIST_URL, DEFAULT_ALIST_URL) ?: DEFAULT_ALIST_URL,
        alistUser = prefs.getString(KEY_ALIST_USER, DEFAULT_ALIST_USER) ?: DEFAULT_ALIST_USER,
        alistPass = prefs.getString(KEY_ALIST_PASS, DEFAULT_ALIST_PASS) ?: DEFAULT_ALIST_PASS,
        alistScanRoot = prefs.getString(KEY_SCAN_ROOT, "/") ?: "/",
        autoScan = prefs.getBoolean(KEY_AUTO_SCAN, true),
        librarySource = prefs.getString(KEY_LIBRARY_SOURCE, "ALIST") ?: "ALIST",
        alistEnabled = prefs.getBoolean(KEY_ALIST_ENABLED, true),
        webdavEnabled = prefs.getBoolean(KEY_WEBDAV_ENABLED, false),
        embyEnabled = prefs.getBoolean(KEY_EMBY_ENABLED, false),
        webdavUrl = prefs.getString(KEY_WEBDAV_URL, "") ?: "",
        webdavUser = prefs.getString(KEY_WEBDAV_USER, "") ?: "",
        webdavPass = prefs.getString(KEY_WEBDAV_PASS, "") ?: "",
        embyUrl = prefs.getString(KEY_EMBY_URL, "") ?: "",
        embyApiKey = prefs.getString(KEY_EMBY_API_KEY, "") ?: "",
        embyUser = prefs.getString(KEY_EMBY_USER, "") ?: "",
        embyPass = prefs.getString(KEY_EMBY_PASS, "") ?: "",
        tvboxConfigUrl = tvboxUrl,
        savedSubscriptions = subscriptions,
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
        controlsHideSeconds = prefs.getInt(KEY_HIDE_SECONDS, 3),
        defaultDecoder = prefs.getString("default_decoder", "AUTO") ?: "AUTO",
        playerPanelSize = prefs.getString("player_panel_size", "STANDARD") ?: "STANDARD",
        tmdbApiKey = storedTmdbKey,
        tmdbLanguage = prefs.getString(KEY_TMDB_LANGUAGE, "zh-CN") ?: "zh-CN",
        // 老用户升级：以前没有"已验证"概念，已开启且填了 Key 的视为已验证，
        // 否则升级后 TMDB 会被强制关闭，等于悄悄改了用户配置。
        tmdbEnabled = prefs.getBoolean(KEY_TMDB_ENABLED, !BuildConfig.PUBLIC_BUILD),
        tmdbVerifiedKey = prefs.getString(KEY_TMDB_VERIFIED_KEY, "")
            ?: if (prefs.getBoolean(KEY_TMDB_ENABLED, !BuildConfig.PUBLIC_BUILD) && storedTmdbKey.isNotBlank()) {
                com.reelix.app.data.image.TmdbKeyPolicy.fingerprint(storedTmdbKey)
            } else "",
        artworkChannels = loadArtworkChannels(prefs),
        artworkOrder = loadArtworkOrder(prefs),
        )
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val changed = block(_state.value)
        val next = changed.copy(appearance = changed.appearance.normalized())
        prefs.edit()
            .putString("navigation_order", next.appearance.order.joinToString(","))
            .putString("navigation_hidden", next.appearance.hidden.joinToString(","))
            .putInt("poster_columns", next.appearance.posterColumns)
            .putString("layout_theme", next.appearance.layoutTheme)
            .putString(KEY_ALIST_URL, next.alistUrl)
            .putString(KEY_ALIST_USER, next.alistUser)
            .putString(KEY_ALIST_PASS, next.alistPass)
            .putString(KEY_SCAN_ROOT, next.alistScanRoot)
            .putBoolean(KEY_AUTO_SCAN, next.autoScan)
            .putString(KEY_LIBRARY_SOURCE, next.librarySource)
            .putBoolean(KEY_ALIST_ENABLED, next.alistEnabled)
            .putBoolean(KEY_WEBDAV_ENABLED, next.webdavEnabled)
            .putBoolean(KEY_EMBY_ENABLED, next.embyEnabled)
            .putString(KEY_WEBDAV_URL, next.webdavUrl)
            .putString(KEY_WEBDAV_USER, next.webdavUser)
            .putString(KEY_WEBDAV_PASS, next.webdavPass)
            .putString(KEY_EMBY_URL, next.embyUrl)
            .putString(KEY_EMBY_API_KEY, next.embyApiKey)
            .putString(KEY_EMBY_USER, next.embyUser)
            .putString(KEY_EMBY_PASS, next.embyPass)
            .putString(KEY_TVBOX_URL, next.tvboxConfigUrl)
            .putString(KEY_SUBSCRIPTIONS, encodeSubscriptions(next.savedSubscriptions))
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .putInt(KEY_HIDE_SECONDS, next.controlsHideSeconds)
            .putString("default_decoder", next.defaultDecoder)
            .putString("player_panel_size", next.playerPanelSize)
            .putString(KEY_TMDB_API_KEY, next.tmdbApiKey)
            .putString(KEY_TMDB_LANGUAGE, next.tmdbLanguage)
            .putBoolean(KEY_TMDB_ENABLED, next.tmdbEnabled)
            .putString(KEY_TMDB_VERIFIED_KEY, next.tmdbVerifiedKey)
            .putString(KEY_ARTWORK_CHANNELS, encodeArtworkChannels(next.artworkChannels))
            .putString(KEY_ARTWORK_ORDER, next.artworkOrder.joinToString(","))
            .remove(KEY_EXTERNAL_ARTWORK_ENABLED)
            .remove(KEY_EXTERNAL_ARTWORK_URL)
            .remove(KEY_EXTERNAL_ARTWORK_SOURCES)
            .commit()
        _state.value = next
    }

    /** Sort changes must not depend on a potentially stale full settings snapshot. */
    fun updateArtworkOrder(order: List<String>) {
        val valid = order.filter { it in DEFAULT_ARTWORK_ORDER }.distinct()
        val nextOrder = valid + DEFAULT_ARTWORK_ORDER.filterNot { it in valid }
        prefs.edit().putString(KEY_ARTWORK_ORDER, nextOrder.joinToString(",")).commit()
        _state.value = _state.value.copy(artworkOrder = nextOrder)
    }

    /** 恢复默认（保留已填的地址，只恢复偏好项） */
    fun resetPreferences() {
        update {
            it.copy(
                keepScreenOn = true,
                controlsHideSeconds = 3,
                defaultDecoder = "AUTO",
                playerPanelSize = "STANDARD",
                autoScan = true,
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "reelix_settings"

        private const val KEY_ALIST_URL = "alist_url"
        private const val KEY_ALIST_USER = "alist_user"
        private const val KEY_ALIST_PASS = "alist_pass"
        private const val KEY_SCAN_ROOT = "alist_scan_root"
        private const val KEY_AUTO_SCAN = "auto_scan"
        private const val KEY_LIBRARY_SOURCE = "library_source"
        private const val KEY_ALIST_ENABLED = "library_alist_enabled"
        private const val KEY_WEBDAV_ENABLED = "library_webdav_enabled"
        private const val KEY_EMBY_ENABLED = "library_emby_enabled"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS = "webdav_pass"
        private const val KEY_EMBY_URL = "emby_url"
        private const val KEY_EMBY_API_KEY = "emby_api_key"
        private const val KEY_EMBY_USER = "emby_user"
        private const val KEY_EMBY_PASS = "emby_pass"
        private const val KEY_TVBOX_URL = "tvbox_url"
        private const val KEY_SUBSCRIPTIONS = "tvbox_subscriptions"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_HIDE_SECONDS = "hide_seconds"
        private const val KEY_TMDB_API_KEY = "tmdb_api_key"
        private const val KEY_TMDB_LANGUAGE = "tmdb_language"
        private const val KEY_TMDB_ENABLED = "tmdb_enabled"
        private const val KEY_TMDB_VERIFIED_KEY = "tmdb_verified_key"
        private const val KEY_EXTERNAL_ARTWORK_ENABLED = "external_artwork_enabled"
        private const val KEY_EXTERNAL_ARTWORK_URL = "external_artwork_url"
        private const val KEY_EXTERNAL_ARTWORK_SOURCES = "external_artwork_sources"
        private const val KEY_ARTWORK_CHANNELS = "artwork_channels"
        private const val KEY_ARTWORK_ORDER = "artwork_order"

        /** 默认值指向当前这台机器上实测可用的 alist */
        const val DEFAULT_ALIST_URL = ""
        const val DEFAULT_ALIST_USER = ""
        const val DEFAULT_ALIST_PASS = ""

        private fun parseSubscriptions(raw: String?): List<SavedSubscription> = runCatching {
            val array = JSONArray(raw.orEmpty())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").trim()
                if (url.isBlank()) null else SavedSubscription(
                    id = item.optString("id").trim().takeIf { it.isNotBlank() && it != url }
                        ?: subscriptionId(url),
                    name = item.optString("name").ifBlank { subscriptionName(url) },
                    url = url,
                )
            }.distinctBy { it.url }
        }.getOrDefault(emptyList())

        private fun encodeSubscriptions(items: List<SavedSubscription>): String = JSONArray().apply {
            items.distinctBy { it.url }.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id.ifBlank { subscriptionId(item.url) })
                    put("name", item.name.ifBlank { subscriptionName(item.url) })
                    put("url", item.url)
                })
            }
        }.toString()

        /** 渠道列表：统一走 ArtworkChannelCodec，避免多处格式漂移。 */
        private fun loadArtworkChannels(prefs: SharedPreferences): List<com.reelix.app.data.image.ArtworkChannel> =
            com.reelix.app.data.image.ArtworkChannelCodec.decode(prefs.getString(KEY_ARTWORK_CHANNELS, null))

        private fun encodeArtworkChannels(items: List<com.reelix.app.data.image.ArtworkChannel>): String =
            com.reelix.app.data.image.ArtworkChannelCodec.encode(items)

        private const val ORDER_TMDB = "TMDB"
        private const val ORDER_DOUBAN = "DOUBAN"
        private const val ORDER_STREAMX = "STREAMX"
        private const val ORDER_TVMAZE = "TVMAZE"
        private val DEFAULT_ARTWORK_ORDER = if (BuildConfig.PUBLIC_BUILD)
            listOf(ORDER_DOUBAN, ORDER_STREAMX, ORDER_TVMAZE)
        else listOf(ORDER_TMDB, ORDER_DOUBAN, ORDER_STREAMX, ORDER_TVMAZE)

        /** 顺序只允许这三种；缺失的按默认顺序补在末尾。 */
        private fun loadArtworkOrder(prefs: SharedPreferences): List<String> {
            val stored = prefs.getString(KEY_ARTWORK_ORDER, null)
                ?.split(',')?.map(String::trim)?.filter { it.isNotBlank() }
                .orEmpty()
            val valid = stored.filter { it in DEFAULT_ARTWORK_ORDER }.distinct()
            return valid + DEFAULT_ARTWORK_ORDER.filterNot { it in valid }
        }

        private fun subscriptionName(url: String): String = runCatching {
            java.net.URI(url).host?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: url.take(48)

        /** Stable across restarts, and independent of display names. */
        fun subscriptionId(url: String): String =
            UUID.nameUUIDFromBytes(url.trim().toByteArray(Charsets.UTF_8)).toString()
    }
}
