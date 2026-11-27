package com.reelix.app

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.core.view.doOnLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelix.app.data.alist.AlistClient
import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.HomeFeed
import com.reelix.app.data.model.HomeRail
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.db.PlayProgressEntity
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.data.repo.LibraryRepository
import com.reelix.app.data.image.PosterFetcher
import com.reelix.app.ui.screen.settings.ConnectionTestResult
import com.reelix.app.ui.screen.settings.NativeSettingsActions
import com.reelix.app.ui.screen.settings.NativeSettingsSession
import com.reelix.app.ui.screen.settings.NativeSettingsSnapshot
import com.reelix.app.ui.screen.settings.NativeSettingsView
import com.reelix.app.data.web.PhoneFillServer
import com.reelix.app.data.web.FillField
import com.reelix.app.data.web.ServerField
import com.reelix.app.data.web.ServerFieldType
import com.reelix.app.data.web.ServerGroup
import com.reelix.app.data.web.ServerOption
import com.reelix.app.data.web.ServerSnapshot
import com.reelix.app.data.web.ServerSubscription
import com.reelix.app.data.web.SettingsWebServer
import com.reelix.app.data.settings.SettingsRepository
import com.reelix.app.data.settings.SavedSubscription
import com.reelix.app.data.pan.PanAuthItem
import com.reelix.app.data.pan.PanCookieStore
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceManager
import com.reelix.app.data.emby.EmbyClient
import com.reelix.app.data.webdav.WebDavClient
import com.reelix.app.data.source.jar.OnlineSearch
import com.reelix.app.data.source.jar.ParseExecutor
import com.reelix.app.data.source.jar.SniffRules
import com.reelix.app.data.source.jar.SourceProbe
import com.reelix.app.data.source.jar.WebViewSniffer
import com.reelix.app.data.source.jar.isDirectPlayback
import com.reelix.app.data.source.jar.playbackWebRequests
import com.reelix.app.data.source.jar.combinePlaybackHeaders
import com.reelix.app.data.source.online.OnlineCache
import com.reelix.app.data.source.online.OnlineCacheStore
import com.reelix.app.data.source.online.PlayResolutionCache
import com.reelix.app.data.source.online.OnlineSiteSnapshot
import com.reelix.app.ui.screen.player.ResumePoint
import com.reelix.app.data.repo.toMediaItem
import com.reelix.app.data.repo.withEffectiveArtwork
import com.reelix.app.data.repo.ProgressRepository
import com.reelix.app.data.repo.HistorySnapshotStore
import com.reelix.app.ui.nav.TopBar
import com.reelix.app.ui.nav.TopDest
import com.reelix.app.ui.nav.TopBarView
import com.reelix.app.ui.screen.detail.DetailView
import com.reelix.app.ui.screen.home.HomeView
import com.reelix.app.ui.screen.home.HomeCollectionItem
import com.reelix.app.ui.screen.library.LibraryView
import com.reelix.app.ui.screen.online.OnlineView
import com.reelix.app.ui.screen.online.OnlineViewState
import com.reelix.app.ui.screen.panauth.PanAuthScreen
import com.reelix.app.ui.screen.panlogin.PanLoginScreen
import com.reelix.app.ui.screen.player.PlayerScreen
import com.reelix.app.ui.component.KeyRouting
import com.reelix.app.ui.screen.search.OnlineSearchUiState
import com.reelix.app.ui.screen.search.SearchView
import com.reelix.app.ui.screen.search.SearchSession
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxProvideTextSelection
import com.reelix.app.ui.theme.RxType
import com.reelix.app.ui.theme.ReelboxTheme
import com.reelix.app.ui.theme.layout.NativeThemeRegistry
import com.reelix.app.ui.theme.layout.NativeThemeId
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import com.reelix.app.R

private const val EXIT_WINDOW_MS = 2_000L

/**
 * 片库条目名单是否已经可信。
 *
 * `Ready` = 扫描完成，名单完整。`Idle` 有两种情况：没有启用任何片库源
 * （名单本来就空，可信），或配置了源但还没开始扫描（名单空但不可信）。
 */
internal fun libraryTitlesKnown(
    state: LibraryRepository.LibraryState,
    anySourceEnabled: Boolean,
): Boolean = when (state) {
    is LibraryRepository.LibraryState.Ready -> true
    is LibraryRepository.LibraryState.Idle -> !anySourceEnabled
    else -> false
}

private fun applySubscriptionAction(
    settings: SettingsRepository.AppSettings,
    values: Map<String, String>,
): SettingsRepository.AppSettings {
    val action = values["action"].orEmpty()
    val id = values["id"].orEmpty()
    val subscriptions = settings.savedSubscriptions
    return when (action) {
        "add", "update" -> {
            val url = values["url"].orEmpty().trim()
            require(url.length <= 2048 && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
                "请输入有效的 http/https 订阅地址"
            }
            val editing = if (action == "update") subscriptions.firstOrNull { it.id == id }
                ?: error("订阅不存在或已被删除") else null
            val name = values["name"].orEmpty().trim().take(80).ifBlank {
                runCatching { java.net.URI(url).host }.getOrNull().orEmpty().ifBlank { url.take(48) }
            }
            val existingUrl = subscriptions.firstOrNull { it.url == url }
            val item = when {
                editing?.url == url -> editing.copy(name = name)
                existingUrl != null -> existingUrl.copy(name = name)
                else -> SavedSubscription(SettingsRepository.subscriptionId(url), name, url)
            }
            val next = when {
                editing != null -> subscriptions.mapNotNull { current ->
                    when {
                        current.id == editing.id -> item
                        current.id == item.id || current.url == url -> null
                        else -> current
                    }
                }
                existingUrl != null -> subscriptions.map { if (it.id == existingUrl.id) item else it }
                else -> subscriptions + item
            }
            settings.copy(savedSubscriptions = next, tvboxConfigUrl = url)
        }
        "activate" -> {
            val selected = subscriptions.firstOrNull { it.id == id } ?: error("订阅不存在或已被删除")
            settings.copy(tvboxConfigUrl = selected.url)
        }
        "delete" -> {
            val selected = subscriptions.firstOrNull { it.id == id } ?: error("订阅不存在或已被删除")
            val remaining = subscriptions.filterNot { it.id == id }
            val activeUrl = if (settings.tvboxConfigUrl == selected.url) remaining.firstOrNull()?.url.orEmpty()
            else settings.tvboxConfigUrl
            settings.copy(savedSubscriptions = remaining, tvboxConfigUrl = activeUrl)
        }
        else -> error("未知订阅操作")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 顺序修正（2026-11-06）：必须在 super.onCreate 之后调。
        //   之前放在 super 之前，窗口还没交给 Activity，edge-to-edge 的
        //   insets 监听会额外走一次重建。
        enableEdgeToEdge()
        setContent {
            ReelboxTheme {
                RxProvideTextSelection {
                    ReelboxRoot()
                }
            }
        }
    }
}

/**
 * 应用根 —— 状态机式导航。
 *
 * 三级层次（返回键逐级退）：
 *   播放页  →  详情页  →  首页/片库
 *
 * 为什么现在不用 Navigation-Compose：
 * 详情页有三套布局要反复对比调参，路由加进去反而妨碍快速验证。
 * 等 UI 定型再换。
 */
@Composable
private fun ReelboxRoot() {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    var dest by remember { mutableStateOf(TopDest.HOME) }
    var cachedSiteKeys by remember {
        mutableStateOf<List<OnlineSiteSnapshot>>(emptyList())
    }
    var onlineStateSawReady by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<MediaItem?>(null) }
    var detailSource by remember { mutableStateOf<DetailSource?>(null) }
    var playing by remember { mutableStateOf<PlayingTarget?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showPanLogin by remember { mutableStateOf(false) }
    var showPanAuth by remember { mutableStateOf(false) }
    // 从「配置类条目」进来时要直接定位到哪个网盘
    var pendingPanProvider by remember { mutableStateOf<PanCookieStore.Provider?>(null) }
    // 清除授权后递增，强制设置页重新读取已配置集合。
    var panAuthTick by remember { mutableIntStateOf(0) }

    // 播放解析失败原因（网盘未登录时最常见的反馈）
    var playResolveError by remember { mutableStateOf<String?>(null) }
    // ★ 解析阶段 + 起始时间（2026-11-07）。
    //
    //   播放页靠这两个值显示"卡在哪一步、等了多久"：解析链里 WebView 嗅探
    //   可能跑 3~15 秒，只给一个 spinner 的话用户分不清"在解析"和"已卡死"。
    var resolveStage by remember { mutableStateOf<String?>(null) }
    var resolveStartedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

    // 在线搜索状态
    var onlineSearch by remember { mutableStateOf(OnlineSearchUiState()) }

    // 在线源详情（异步从 jar 拉，拿到后覆盖占位）
    var onlineDetail by remember { mutableStateOf<MediaDetail?>(null) }
    var onlineDetailLoading by remember { mutableStateOf(false) }
    // 详情缓存来自上一轮，线路/选集先显示；网络失败时保留它们。
    var onlineDetailFromCache by remember { mutableStateOf(false) }

    // ---- 在线源浏览状态 ----
    // -1 = 尚未从缓存取到选择；不要用写着 7 的初始值碰运气。
    var onlineSiteIndex by remember { mutableIntStateOf(-1) }
    var onlineSiteKey by remember { mutableStateOf("") }
    var onlineHome by remember { mutableStateOf<HomeContent?>(null) }
    var onlineCategory by remember { mutableStateOf<CategoryContent?>(null) }
    var onlineCategorySiteKey by remember { mutableStateOf("") }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineCategoryLoading by remember { mutableStateOf(false) }
    var onlineContentLoading by remember { mutableStateOf(false) }
    var onlineFromCache by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }
    var onlineCategoryId by remember { mutableStateOf<String?>(null) }
    // 在线海报墙的焦点位置。详情页会把在线页移出组合，所以必须由 Activity 这一层持有。
    //
    // ★ 刻意不用 mutableStateOf：焦点每移动一格都写 State 会让整个页面重组一次，
    //   正好抵消掉 RecyclerView 省下来的性能。这里只需要“离开页面前记住”，
    //   返回详情时 MainActivity 本来就会重组，读数组里的最新值即可。
    val onlinePosterIndex = remember { intArrayOf(0) }
    val onlineBrowse = remember { mutableMapOf<String, com.reelix.app.ui.component.PosterGridMemory>() }
    var restoreOnlinePosterFocus by remember { mutableStateOf(false) }
    var onlineViewRef by remember { mutableStateOf<OnlineView?>(null) }
    var onlineCacheTick by remember { mutableIntStateOf(0) }

    // 主界面改成 View 后，焦点下标必须由 Activity 持有：
    // 详情页会把当前页移出组合，页面内部的 remember 活不到返回那一刻。
    val homePosterIndex = remember { intArrayOf(0) }
    val homeBrowse = remember { com.reelix.app.ui.component.PosterGridMemory() }
    val libraryBrowse = remember { com.reelix.app.ui.component.PosterGridMemory() }
    val libraryPosterIndex = remember { intArrayOf(0) }
    val searchPosterIndex = remember { intArrayOf(0) }
    var restoreHomePosterFocus by remember { mutableStateOf(false) }
    var homeReturnRegion by remember { mutableStateOf("hero") }
    var restoreLibraryPosterFocus by remember { mutableStateOf(false) }
    var restoreSearchPosterFocus by remember { mutableStateOf(false) }
    var homeViewRef by remember { mutableStateOf<HomeView?>(null) }
    var libraryViewRef by remember { mutableStateOf<LibraryView?>(null) }
    var searchViewRef by remember { mutableStateOf<SearchView?>(null) }
    var topBarViewRef by remember { mutableStateOf<TopBarView?>(null) }
    var detailViewRef by remember { mutableStateOf<DetailView?>(null) }
    // 从播放页返回时，把焦点还给刚才那张选集卡（而不是顶部的"继续观看"）。
    var restoreDetailEpisodeFocus by remember { mutableStateOf(false) }
    var restoreSettingsButtonFocus by remember { mutableStateOf(false) }
    var settingsReturnKey by remember { mutableStateOf("settings") }
    val nativeSettingsSession = remember { NativeSettingsSession() }
    var nativeSettingsViewRef by remember { mutableStateOf<NativeSettingsView?>(null) }
    val searchSession = remember { SearchSession() }
    // 从哪个一级页面进入详情；返回时据此恢复对应海报墙的焦点。
    var detailOrigin by remember { mutableStateOf<TopDest?>(null) }

    // 播放页关闭后详情页的 AndroidView 会重建，等它挂上再要焦点。
    //
    // ★ 这里不能在开头就把 restoreDetailEpisodeFocus 置回 false：
    //   它本身是 LaunchedEffect 的 key，提前写回会让这次协程在第一个 delay
    //   结束前就被取消，重试循环一次都跑不到（返回焦点失败的根因之一）。
    //   只在成功或超时后清标记；详情页内部也会并行重试。
    androidx.compose.runtime.LaunchedEffect(playing, restoreDetailEpisodeFocus) {
        if (playing != null || !restoreDetailEpisodeFocus) return@LaunchedEffect
        repeat(25) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 80L else 120L)
            if (detailViewRef?.focusLastPlayedEpisode() == true) {
                restoreDetailEpisodeFocus = false
                return@LaunchedEffect
            }
        }
        restoreDetailEpisodeFocus = false
    }

    // 首页返回焦点：HomeView 重建后 hero/网格可能还没挂上，
    // 只请求一次会静默失败（焦点悬空，按方向键才被系统捡回来）。
    // 这里最多重试约 3 秒，而且以"目标区域真的拿到焦点"为准。
    androidx.compose.runtime.LaunchedEffect(dest, detailItem, restoreHomePosterFocus) {
        if (!restoreHomePosterFocus || detailItem != null || dest != TopDest.HOME) return@LaunchedEffect
        repeat(25) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 60L else 120L)
            val view = homeViewRef ?: return@repeat
            if (view.restoreFocusChecked(homePosterIndex[0], homeReturnRegion)) {
                restoreHomePosterFocus = false
                homeReturnRegion = "hero"
                return@LaunchedEffect
            }
        }
        restoreHomePosterFocus = false
        homeReturnRegion = "hero"
    }

    val heroFocus = remember { FocusRequester() }
    // 冷启动的默认焦点是导航栏“首页”。请求只发一次，避免之后
    // 从设置/详情返回时又被拉回导航栏。
    var topBarInitialFocusPending by remember { mutableStateOf(true) }
    LaunchedEffect(topBarInitialFocusPending, topBarViewRef, showSettings, showPanLogin, showPanAuth, detailItem, playing) {
        if (!topBarInitialFocusPending || showSettings || showPanLogin || showPanAuth || detailItem != null || playing != null) return@LaunchedEffect
        val view = topBarViewRef ?: return@LaunchedEffect
        view.doOnLayout {
            view.post {
                val eligible = topBarViewRef === view && view.isAttachedToWindow && topBarInitialFocusPending &&
                    !showSettings && !showPanLogin && !showPanAuth && detailItem == null && playing == null
                val ok = eligible && view.focusHome()
                if (ok) topBarInitialFocusPending = false
            }
        }
    }
    var lastBackAt by remember { mutableLongStateOf(0L) }

    // 任何一级页面或覆盖层变化都重新计时，避免在别处按过 BACK 后
    // 回到根页面被一次误触直接退出。
    LaunchedEffect(dest, showSettings, showPanLogin, showPanAuth, detailItem, playing) {
        lastBackAt = 0L
    }
    // 2026-11-09：演示数据（MockData 的 9 部假电影）已移除。
    // 这里原本是 `remember { buildMockFeed() }`，无条件创建一份假 HomeFeed，
    // 只在"片库为空时的搜索列表"里用到 —— 那部分也一起去掉了。

    // ---- 设置（SharedPreferences，同步可读） ----
    val settingsRepo = remember { SettingsRepository(context) }
    val settings by settingsRepo.state.collectAsStateWithLifecycle()
    val settingsWebServer = remember { SettingsWebServer() }
    var settingsServerUrl by remember { mutableStateOf<String?>(null) }

    fun serverField(
        key: String,
        label: String,
        value: String,
        placeholder: String = "",
        hint: String = "",
        secret: Boolean = false,
    ) = ServerField(key, label, value, placeholder, hint, secret)

    fun artworkChannelUrl(kind: String): String =
        settingsRepo.current.artworkChannels.firstOrNull { it.kind == kind }?.baseUrl.orEmpty()

    fun artworkChannelDefault(kind: String): String =
        settingsRepo.current.artworkChannels.firstOrNull { it.kind == kind }?.defaultUrl.orEmpty()

    settingsWebServer.snapshotProvider = {
        val s = settingsRepo.current
        ServerSnapshot(
            version = BuildConfig.VERSION_NAME,
            deviceLabel = "Reelbox 设置",
            accessInfo = settingsWebServer.accessUrl(),
            subscriptions = s.savedSubscriptions.map { item ->
                ServerSubscription(
                    id = item.id,
                    name = item.name,
                    url = item.url,
                    active = item.url == s.tvboxConfigUrl,
                )
            },
            groups = listOf(
                ServerGroup(
                    key = "library",
                    title = "片库连接",
                    hint = "三个源可同时启用；扫描目录由三个源共用。订阅库、网盘授权、任务和缓存在电视端操作。",
                    fields = listOf(
                        serverField("alistUrl", "Alist 服务地址", s.alistUrl, "http://192.168.1.100:5244"),
                        serverField("alistUser", "Alist 用户名", s.alistUser),
                        serverField("alistPass", "Alist 密码", s.alistPass, secret = true),
                        serverField("alistScanRoot", "扫描目录", s.alistScanRoot, "/", "三种数据源共用"),
                        serverField("webdavUrl", "WebDAV 地址", s.webdavUrl, "http://192.168.1.100:8080/dav"),
                        serverField("webdavUser", "WebDAV 用户名", s.webdavUser),
                        serverField("webdavPass", "WebDAV 密码", s.webdavPass, secret = true),
                        serverField("embyUrl", "Emby/Jellyfin 地址", s.embyUrl, "http://192.168.1.100:8096"),
                        serverField("embyApiKey", "Emby/Jellyfin API Key", s.embyApiKey, secret = true),
                        serverField("embyUser", "Emby/Jellyfin 用户名", s.embyUser),
                        serverField("embyPass", "Emby/Jellyfin 密码", s.embyPass, secret = true),
                    ),
                ),
                ServerGroup(
                    key = "tvbox",
                    title = "在线源 · 订阅",
                    hint = "可保存多条 TVBox 订阅；设为当前或新增后电视会加载对应配置。",
                    fields = emptyList(),
                ),
                ServerGroup(
                    key = "tmdb",
                    title = "海报与资料",
                    hint = "这里编辑 API Key 和渠道地址；启用、语言、优先顺序及连接测试仍在电视端设置。",
                    fields = listOf(
                        serverField("tmdbApiKey", "API Key (v3)", s.tmdbApiKey, "32 位十六进制", secret = true),
                        serverField("streamxUrl", "Streamx 地址", artworkChannelUrl("STREAMX"), artworkChannelDefault("STREAMX")),
                        serverField("tvmazeUrl", "TVmaze 地址", artworkChannelUrl("TVMAZE"), artworkChannelDefault("TVMAZE")),
                        serverField("doubanUrl", "豆瓣地址", artworkChannelUrl("DOUBAN"), artworkChannelDefault("DOUBAN")),
                    ),
                ),
            ),
        )
    }

    settingsWebServer.saveHandler = { group, values ->
        settingsRepo.update { s ->
            when (group) {
                "library" -> s.copy(
                    alistUrl = values["alistUrl"]?.trim() ?: s.alistUrl,
                    alistUser = values["alistUser"]?.trim() ?: s.alistUser,
                    alistPass = values["alistPass"] ?: s.alistPass,
                    alistScanRoot = values["alistScanRoot"]?.trim()?.ifBlank { "/" } ?: s.alistScanRoot,
                    webdavUrl = values["webdavUrl"]?.trim() ?: s.webdavUrl,
                    webdavUser = values["webdavUser"]?.trim() ?: s.webdavUser,
                    webdavPass = values["webdavPass"] ?: s.webdavPass,
                    embyUrl = values["embyUrl"]?.trim() ?: s.embyUrl,
                    embyApiKey = values["embyApiKey"]?.trim() ?: s.embyApiKey,
                    embyUser = values["embyUser"]?.trim() ?: s.embyUser,
                    embyPass = values["embyPass"] ?: s.embyPass,
                )
                "subscriptions" -> applySubscriptionAction(s, values)
                "tmdb" -> {
                    val nextKey = values["tmdbApiKey"]?.trim() ?: s.tmdbApiKey
                    if (nextKey != s.tmdbApiKey) com.reelix.app.data.tmdb.TmdbClient.clearCache()
                    val urls = mapOf(
                        "STREAMX" to values["streamxUrl"],
                        "TVMAZE" to values["tvmazeUrl"],
                        "DOUBAN" to values["doubanUrl"],
                    )
                    val channels = s.artworkChannels.toMutableList()
                    urls.forEach { (kind, url) ->
                        if (url != null) {
                            val index = channels.indexOfFirst { it.kind == kind }
                            if (index >= 0) channels[index] = channels[index].copy(baseUrl = url.trim())
                            else channels += com.reelix.app.data.image.ArtworkChannel(kind = kind, baseUrl = url.trim())
                        }
                    }
                    s.copy(tmdbApiKey = nextKey, artworkChannels = channels)
                }
                else -> s
            }
        }
        "已保存"
    }

    DisposableEffect(Unit) {
        settingsWebServer.start()
        settingsServerUrl = if (settingsWebServer.isRunning()) settingsWebServer.accessUrl() else null
        onDispose { settingsWebServer.stop() }
    }
    val nativeTheme = remember(settings.appearance.layoutTheme) { NativeThemeRegistry.create(settings.appearance.layoutTheme) }
    LaunchedEffect(settings.appearance) {
        val safe = TopDest.valueOf(settings.appearance.safePage(dest.name))
        if (safe != dest) {
            dest = safe
            topBarViewRef?.post { topBarViewRef?.focusHome() }
        }
        detailOrigin = detailOrigin?.let { TopDest.valueOf(settings.appearance.safePage(it.name)) }
    }

    // ---- 片库（真实 Alist） ----
    // 地址变了就重建 client，避免一直用旧地址
    // Every configured source joins one library; only non-empty addresses are used.
    val configuredLibrarySources = remember(
        settings.alistUrl, settings.webdavUrl, settings.embyUrl,
        settings.alistEnabled, settings.webdavEnabled, settings.embyEnabled,
    ) {
        buildMap {
            if (settings.alistEnabled && settings.alistUrl.isNotBlank()) put("alist", Unit)
            if (settings.webdavEnabled && settings.webdavUrl.isNotBlank()) put("webdav", Unit)
            if (settings.embyEnabled && settings.embyUrl.isNotBlank()) put("emby", Unit)
        }.keys.toList()
    }
    val libraryUrl = when {
        settings.librarySource.equals("WEBDAV", ignoreCase = true) -> settings.webdavUrl
        settings.librarySource.equals("EMBY", ignoreCase = true) -> settings.embyUrl
        else -> settings.alistUrl
    }
    val libraryUser = when {
        settings.librarySource.equals("WEBDAV", ignoreCase = true) -> settings.webdavUser
        settings.librarySource.equals("EMBY", ignoreCase = true) -> settings.embyUser
        else -> settings.alistUser
    }
    val libraryPass = when {
        settings.librarySource.equals("WEBDAV", ignoreCase = true) -> settings.webdavPass
        settings.librarySource.equals("EMBY", ignoreCase = true) -> settings.embyPass
        else -> settings.alistPass
    }
    val libraryRepo = remember(
        settings.librarySource,
        settings.alistUrl,
        settings.alistUser,
        settings.alistPass,
        settings.alistScanRoot,
        settings.webdavUrl,
        settings.webdavUser,
        settings.webdavPass,
        settings.embyUrl,
        settings.embyApiKey,
        settings.embyUser,
        settings.embyPass,
        settings.alistEnabled,
        settings.webdavEnabled,
        settings.embyEnabled,
    ) {
        LibraryRepository(buildMap {
            if (settings.alistEnabled && settings.alistUrl.isNotBlank()) put("alist", AlistClient(baseUrl = settings.alistUrl))
            if (settings.webdavEnabled && settings.webdavUrl.isNotBlank())
                put("webdav", WebDavClient(settings.webdavUrl, settings.webdavUser, settings.webdavPass))
            if (settings.embyEnabled && settings.embyUrl.isNotBlank())
                put("emby", EmbyClient(settings.embyUrl, settings.embyApiKey, settings.embyUser, settings.embyPass))
        }.ifEmpty { mapOf("alist" to AlistClient(baseUrl = settings.alistUrl)) })
    }
    // Emby authenticates with its API key; the others use user/password.
    val librarySourceCredentials = {
        buildMap {
            put("alist", settings.alistUser to settings.alistPass)
            put("webdav", settings.webdavUser to settings.webdavPass)
            put("emby", settings.embyUser to settings.embyApiKey)
        }
    }
    val librarySourceAddresses = {
        buildMap {
            put("alist", settings.alistUrl)
            put("webdav", settings.webdavUrl)
            put("emby", settings.embyUrl)
        }
    }
    val libraryState by libraryRepo.state.collectAsStateWithLifecycle()
    var homeCollection by remember(libraryRepo) { mutableStateOf(emptyList<HomeCollectionItem>()) }
    LaunchedEffect(libraryState, libraryRepo) {
        (libraryState as? LibraryRepository.LibraryState.Ready)?.let { ready ->
            homeCollection = ready.entries.map { entry -> HomeCollectionItem(entry.item,
                entry.files.map { it.firstSeenAt }.filter { it > 0 }.minOrNull() ?: 0L, entry.activityAt) }
        }
    }
    val libraryScanProgress by libraryRepo.scanProgress.collectAsStateWithLifecycle()
    val libraryTaskStatus by libraryRepo.scanStatus.collectAsStateWithLifecycle()
    var libraryRefreshRequested by remember(libraryRepo) { mutableStateOf(false) }
    var libraryRefreshResult by remember(libraryRepo) { mutableStateOf<String?>(null) }
    LaunchedEffect(libraryRefreshResult, libraryScanProgress) {
        val result = libraryRefreshResult ?: return@LaunchedEffect
        if (libraryScanProgress != null) return@LaunchedEffect
        kotlinx.coroutines.delay(6_000)
        if (libraryRefreshResult == result) libraryRefreshResult = null
    }
    var libraryArtworkTick by remember(libraryRepo) { mutableStateOf(0) }
    // 片库换图是异步完成的（对话框先关、后台再写盘），
    // 成功后必须让详情页重新取一次资料，否则只有海报变、简介/演职员还是旧的。
    androidx.compose.runtime.DisposableEffect(libraryRepo) {
        val downloads = com.reelix.app.data.image.LibraryArtworkDownloads.get(context)
        // setCover 已经写入了新的 binding；这里只需要让详情页的资料 effect 重跑。
        downloads.onSucceeded = { libraryArtworkTick++ }
        onDispose { downloads.onSucceeded = null }
    }
    var libraryArtworkProgress by remember(libraryRepo) { mutableStateOf<String?>(null) }
    val refreshLibrary by androidx.compose.runtime.rememberUpdatedState<() -> Unit>({
        if (!libraryRefreshRequested && libraryScanProgress == null) {
            libraryRefreshRequested = true
            libraryRefreshResult = null
            scope.launch {
                try {
                    val result = libraryRepo.load(
                        username = libraryUser, password = libraryPass,
                        root = settings.alistScanRoot.ifBlank { "/" },
                        messages = libraryLoadingMessages(context), force = true,
                        cacheContext = context, serverUrl = libraryUrl,
                        sourceCredentials = librarySourceCredentials(),
                        sourceAddresses = librarySourceAddresses(),
                    )
                    if (result.isSuccess) {
                        val entries = (libraryRepo.state.value as? LibraryRepository.LibraryState.Ready)?.entries.orEmpty()
                        val failures = libraryRepo.currentScan?.failures.orEmpty()
                        libraryRefreshResult = if (failures.isEmpty()) context.getString(R.string.library_refresh_done, entries.size)
                            else context.getString(R.string.library_scan_partial, entries.size, failures.size,
                                failures.take(3).joinToString("\n") { "${it.path}: ${it.message}" })
                        Toast.makeText(context, libraryRefreshResult, Toast.LENGTH_LONG).show()
                        // Scanning is complete; image scraping must not block another manual refresh.
                        libraryArtworkTick++
                    } else {
                        libraryRefreshResult = context.getString(R.string.library_error, result.exceptionOrNull()?.message.orEmpty())
                        Toast.makeText(context, context.getString(R.string.library_error,
                            result.exceptionOrNull()?.message.orEmpty()), Toast.LENGTH_LONG).show()
                    }
                } finally {
                    libraryRefreshRequested = false
                }
            }
        }
    })
    val libraryStats by libraryRepo.stats.collectAsStateWithLifecycle()
    val posterRevision by PosterFetcher.revision.collectAsState()
    val manageLibraryDirectories by androidx.compose.runtime.rememberUpdatedState<() -> Unit>({
        com.reelix.app.ui.screen.library.LibraryDialogs.directories(context, libraryRepo, settings.alistScanRoot.ifBlank { "/" })
    })

    // ---- 在线源（TVBox jar） ----
    val onlineCache = remember { OnlineCache(OnlineCacheStore(context)) }
    // ★ 必须与页面共用同一份 OnlineCache。各自 new 一份时，一侧先把磁盘读进内存、
    //   另一侧再写盘，会拿到不完整的内存目录；首页快照也可能被清掉。
    val sourceManager = remember { SourceManager(context, onlineCache) }
    val sourceState by sourceManager.state.collectAsStateWithLifecycle()
    val libraryArtworkLoader = remember(context) { com.reelix.app.data.image.LibraryArtworkLoader(context) }
    val artworkTaskStatus by libraryArtworkLoader.batchStatus.collectAsStateWithLifecycle()
    val libraryArtworkItems = (libraryState as? LibraryRepository.LibraryState.Ready)?.entries?.map { it.item }.orEmpty()
    val artworkIdentity: List<List<Any?>> = libraryArtworkItems.map { listOf(it.id, it.title, it.year, it.kind) }
    var restoredArtworkIdentity by remember(libraryRepo) { mutableStateOf<List<List<Any?>>?>(null) }
    LaunchedEffect(libraryRepo, artworkIdentity) {
        libraryArtworkLoader.restore(libraryArtworkItems) { id, cover -> libraryRepo.setAutomaticCover(id, cover) }
        restoredArtworkIdentity = artworkIdentity
    }
    val artworkSite = (sourceState as? SourceManager.State.Ready)?.sites?.firstOrNull { it.site.key == onlineSiteKey }
    val changeLibraryArtwork by androidx.compose.runtime.rememberUpdatedState<(MediaItem) -> Unit>({ item ->
        if (item.fromLibrary) {
            com.reelix.app.ui.screen.library.LibraryDialogs.artwork(
                context, libraryRepo, item,
                searchCandidates = { query ->
                    val current = settingsRepo.current
                    libraryArtworkLoader.searchManualUpdates(query, artworkSite, current.tmdbApiKey,
                        current.tmdbEnabled, current.tmdbLanguage,
                        channels = current.artworkChannels,
                        order = current.artworkOrder)
                },
                filterSources = com.reelix.app.data.image.manualArtworkFilterSources(
                    tmdbEnabled = settingsRepo.current.tmdbEnabled,
                    apiKey = settingsRepo.current.tmdbApiKey,
                    onlineAvailable = artworkSite != null,
                    channels = settingsRepo.current.artworkChannels,
                    order = settingsRepo.current.artworkOrder,
                ),
            )
        } else {
            // Online items: poster-only override, keyed by source + vod id.
            com.reelix.app.ui.screen.library.LibraryDialogs.artwork(
                context, libraryRepo, item,
                searchCandidates = { query ->
                    val current = settingsRepo.current
                    libraryArtworkLoader.searchManualUpdates(query, artworkSite, current.tmdbApiKey,
                        current.tmdbEnabled, current.tmdbLanguage,
                        channels = current.artworkChannels,
                        order = current.artworkOrder)
                },
                onlineOnly = true,
                filterSources = com.reelix.app.data.image.manualArtworkFilterSources(
                    tmdbEnabled = settingsRepo.current.tmdbEnabled,
                    apiKey = settingsRepo.current.tmdbApiKey,
                    onlineAvailable = artworkSite != null,
                    channels = settingsRepo.current.artworkChannels,
                    order = settingsRepo.current.artworkOrder,
                ),
                onOnlineApplied = { identity ->
                    val updated = libraryRepo.effectiveArtwork(item)
                    detailItem = updated
                    onlineDetail = onlineDetail?.let { detail ->
                        detail.copy(item = libraryRepo.effectiveArtwork(detail.item))
                    }
                    // 有作品身份才按新作品重取资料；没有身份就只换图，
                    // 绝不再拿旧片名去猜一份“资料”。
                    if (identity != null) {
                        com.reelix.app.data.image.OnlineMetadataStore.remove(item)
                        libraryArtworkTick++
                    }
                    onlineViewRef?.refreshPosters()
                },
            )
        }
    })
    LaunchedEffect(libraryRepo, artworkIdentity, restoredArtworkIdentity, artworkSite, sourceState::class, settings.tvboxConfigUrl,
        settings.tmdbApiKey, settings.tmdbEnabled, settings.tmdbLanguage, settings.artworkChannels,
        settings.artworkOrder, libraryArtworkTick) {
        if (libraryArtworkItems.isEmpty()) return@LaunchedEffect
        if (restoredArtworkIdentity != artworkIdentity) return@LaunchedEffect
        // Wait for the chosen online source before batch artwork lookup.
        if (settings.tvboxConfigUrl.isNotBlank() && sourceState !is SourceManager.State.Ready && sourceState !is SourceManager.State.Error) return@LaunchedEffect
        if ((sourceState as? SourceManager.State.Ready)?.sites?.isNotEmpty() == true && onlineSiteKey.isBlank()) return@LaunchedEffect
        try {
            libraryArtworkLoader.load(libraryArtworkItems, artworkSite, settings.tvboxConfigUrl,
                if (settings.tmdbEnabled) settings.tmdbApiKey else "", settings.tmdbLanguage,
                channels = settings.artworkChannels,
                order = settings.artworkOrder,
                progress = { done, total ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        libraryArtworkProgress = context.getString(R.string.library_artwork_progress, done, total)
                    }
                }, apply = { id, cover -> libraryRepo.setAutomaticCover(id, cover) })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w("LibraryArtwork", "Automatic artwork deferred", e) }
        finally { libraryArtworkProgress = null }
    }
    // 重启时先恢复“最后选的源”的选择态；站点目录由 SourceManager 自己复用本机配置。
    // Read the snapshot before the first online page; not inside a child page's effect.
    LaunchedEffect(Unit) {
        // ★ 缓存文件约 100KB，读取 + JSON 解码必须离开主线程。
        //   以前直接在主线程跑，启动后第一段可见卡顿就是它。
        val loaded = withContext(Dispatchers.IO) { onlineCache.load() }
        if (!loaded) {
            // A previous empty write or corrupt file must not force the user to clear app data.
            withContext(Dispatchers.IO) { onlineCache.invalidateCorruptCache() }
        }
        sourceManager.restoreSitesCache()
        cachedSiteKeys = onlineCache.cachedSites().orEmpty()
        // Only a selected site is remembered; there is no separate settings "default source".
        val remembered = onlineCache.cachedSelectedSiteKey()
        if (remembered.isNotBlank()) {
            val cachedIndex = cachedSiteKeys.indexOfFirst { it.key == remembered }
            if (cachedIndex >= 0) onlineSiteIndex = cachedIndex
            onlineSiteKey = remembered
            val rememberedCategory = onlineCache.cachedSelectedCategoryId()
            if (rememberedCategory.isNotBlank()) {
                onlineCategoryId = rememberedCategory
                onlineCategory = onlineCache.cachedCategory(remembered, rememberedCategory)?.data
                onlineCategorySiteKey = remembered
            }
        }
        if (onlineSiteIndex < 0 && cachedSiteKeys.isNotEmpty()) {
            onlineSiteIndex = 0
            onlineSiteKey = cachedSiteKeys.first().key
        }
        onlineCacheTick++
    }

    LaunchedEffect(sourceState, onlineCacheTick) {
        val loaded = (sourceState as? SourceManager.State.Ready)?.sites.orEmpty()
        if (loaded.isNotEmpty()) {
            cachedSiteKeys = loaded.map { entry ->
                OnlineSiteSnapshot(
                    key = entry.site.key,
                    name = entry.site.name,
                    type = entry.site.type,
                    api = entry.site.api,
                    ext = entry.site.ext,
                    searchable = entry.site.searchable,
                    fromJar = entry.fromJar,
                )
            }
        }
        val ready = sourceState as? SourceManager.State.Ready ?: return@LaunchedEffect
        if (onlineStateSawReady) return@LaunchedEffect
        onlineStateSawReady = true
        // One transition from cached descriptors to live spiders, without a spinner.
        val remembered = onlineCache.cachedSelectedSiteKey()
        val rememberedIndex = remembered
            .takeIf { it.isNotBlank() }
            ?.let { key -> ready.sites.indexOfFirst { it.site.key == key } } ?: -1
        if (rememberedIndex >= 0) {
            onlineSiteIndex = rememberedIndex
            onlineSiteKey = remembered
            onlineCache.putSites(cachedSiteKeys, selectedKey = remembered)
        } else {
            // Remembered site disappeared (or none remembered): first usable site.
            onlineSiteIndex = 0
            onlineSiteKey = ready.sites.firstOrNull()?.site?.key.orEmpty()
        }
        onlineCacheTick++
    }
    // 站点实测结果：加载完自动探一遍，让用户知道哪些源真的能用
    var probeResults by remember { mutableStateOf<List<SourceProbe.Result>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }

    // ---- 播放进度（Room） ----
    // 搜索历史已挪到 ReelboxApp.onCreate 里初始化（这里是 composable 主体，
    // 每次重组都会执行，不该放 Application 级初始化）。

    val progressRepo = remember { ProgressRepository.create(context) }

    /**
     * A deleted episode must not erase the whole work from history.
     * If it was the resume point, move to the nearest surviving episode; otherwise do nothing.
     */
    suspend fun advanceHistoryPastDeleted(
        entry: com.reelix.app.data.alist.AlistLibraryBuilder.LibraryEntry,
        deleted: com.reelix.app.data.model.Episode,
    ) {
        val record = runCatching { progressRepo.find(entry.item.id) }.getOrNull() ?: return
        val ordered = entry.episodes
        val deletedAt = ordered.indexOfFirst { it.playId == deleted.playId }
        if (deletedAt < 0) return
        val wasCurrent = record.sourceId == deleted.playId ||
            (record.routeName == "片库" && record.episodeIndex == deleted.index)
        if (!wasCurrent) return
        val replacement = ordered.getOrNull(deletedAt + 1) ?: ordered.getOrNull(deletedAt - 1)
        if (replacement == null) {
            runCatching { progressRepo.delete(entry.item.id) }
        } else {
            runCatching {
                progressRepo.save(
                    item = entry.item.copy(sourceId = replacement.playId),
                    episodeIndex = replacement.index,
                    episodeName = replacement.displayName,
                    routeName = record.routeName,
                    positionMs = 0,
                    durationMs = 0,
                    force = true,
                )
            }
        }
    }
    // Startup restores the last history list before Room's Flow can deliver its first value.
    // ★ 读快照必须发生在本次 composition 的 remember 计算里；
    //   放进 LaunchedEffect 会晚一帧，首页 Hero 就会先空出来再出现。
    val initialHistory = remember { HistorySnapshotStore.load(context) }
    var historySnapshot by remember { mutableStateOf(initialHistory) }
    var firstHistoryReady by remember { mutableStateOf(historySnapshot != null) }
    // First frame uses a direct query; the Flow then keeps it current.
    LaunchedEffect(progressRepo) {
        // ★ 已有快照就**不要再查库**（2026-11-06）。
        //
        //   原来的写法是无条件 `historySnapshot = snapshotAll()`：快照明明已经
        //   能画首屏，却在首帧后几十毫秒被全表查询结果再覆盖一次，触发首页
        //   整页重建（比首帧本身还贵）。
        //   现在只在本机还没快照（首次安装 / 快照损坏）时兜底查一次。
        if (historySnapshot == null) {
            historySnapshot = runCatching { progressRepo.snapshotAll() }.getOrDefault(emptyList())
        }
    }
    // 首页是「播放历史页」，要显示全部记录（不设上限），不能像原来那样只取 20 条
    // Null means Room has not emitted yet; an emitted empty list means the last history entry was deleted.
    val observedHistory = remember(progressRepo) {
        progressRepo.observeAllHistory().map { records -> records as List<com.reelix.app.data.db.PlayProgressEntity>? }
    }
    val historyFlow by observedHistory.collectAsStateWithLifecycle(initialValue = null)
    val history = historyFlow ?: historySnapshot.orEmpty()

    // ---- 海报缓存：每天一次，在「App 进入后台」时清掉 7 天没看过的图 ----
    //
    // 为什么放 onStop 而不是启动时：
    //   清理要逐个 remove 文件，几百毫秒到 2 秒。启动时跑会和首屏海报加载抢
    //   电视那点 eMMC 带宽；切后台时用户已经不看屏幕了，随便它跑。
    // 为什么必须等数据就绪：
    //   保护名单 = 历史 + 片库。这两个都是异步加载的，片库还要连 Alist 扫描。
    //   名单不完整时清理 = 随机删图，所以宁可这一轮不清。
    val latestHistoryForCache by androidx.compose.runtime.rememberUpdatedState(history)
    val latestLibStateForCache by androidx.compose.runtime.rememberUpdatedState(libraryState)
    val latestHistoryReady by androidx.compose.runtime.rememberUpdatedState(firstHistoryReady)
    val latestOnlineHomeForCache by androidx.compose.runtime.rememberUpdatedState(onlineHome)
    val latestOnlineCategoryForCache by androidx.compose.runtime.rememberUpdatedState(onlineCategory)
    val latestOnlineSearchForCache by androidx.compose.runtime.rememberUpdatedState(onlineSearch)
    val cacheLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(cacheLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            val libState = latestLibStateForCache
            val libReady = libState is LibraryRepository.LibraryState.Ready ||
                libState is LibraryRepository.LibraryState.Idle
            if (!latestHistoryReady || !libReady) return@LifecycleEventObserver

            val keep = com.reelix.app.data.image.collectArtworkReferences(
                latestHistoryForCache.map { it.posterUrl to it.backdropUrl },
            ).toMutableSet()
            if (libState is LibraryRepository.LibraryState.Ready) {
                keep += com.reelix.app.data.image.collectArtworkReferences(
                    libState.entries.map { it.item.poster to it.item.backdrop },
                )
            }
            val onlineVisible = latestOnlineHomeForCache?.let { it.recommendations + it.heroes }.orEmpty() +
                latestOnlineCategoryForCache?.items.orEmpty() +
                latestOnlineSearchForCache.hits.flatMap { it.items }
            keep += com.reelix.app.data.image.collectArtworkReferences(
                onlineVisible.map { it.poster to it.backdrop },
            )
            scope.launch {
                runCatching {
                    com.reelix.app.data.image.PosterCacheCleaner
                        .purgeIfDue(context, keep)
                }
            }
        }
        cacheLifecycle.lifecycle.addObserver(observer)
        onDispose { cacheLifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(historySnapshot, historyFlow) {
        if (historyFlow != null) {
            historySnapshot = historyFlow
            firstHistoryReady = true
        }
    }
    val progressMap by progressRepo.observeProgressMap()
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    LaunchedEffect(libraryRepo, libraryState) {
        val entries = (libraryState as? LibraryRepository.LibraryState.Ready)?.entries ?: return@LaunchedEffect
        runCatching { progressRepo.migrateLibrary(context, entries) }
            .onFailure { android.util.Log.e("LibraryHistory", "History migration deferred; original records retained", it) }
    }
    LaunchedEffect(historySnapshot) {
        val snap = historySnapshot ?: return@LaunchedEffect
        // ★ 必须丢到 IO：encode + 写盘是阻塞操作，原来在 LaunchedEffect 里
        //   直接调 = 在主线程跑，每次历史变化都会卡一下（启动时历史刚从 Room
        //   回来，正好撞上首帧）。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            HistorySnapshotStore.save(context, snap)
        }
    }

    // ★ 本地恢复不受自动扫描开关控制，也不能因切换导航而取消。
    var libraryOpened by remember(libraryRepo) { mutableStateOf(false) }
    LaunchedEffect(dest, libraryRepo) {
        if (dest == TopDest.LIBRARY || dest == TopDest.SEARCH) libraryOpened = true
    }
    LaunchedEffect(
        libraryRepo,
        libraryOpened,
        settings.autoScan,
    ) {
        val libraryNeedsLoad =
            libraryState is LibraryRepository.LibraryState.Idle ||
                libraryState is LibraryRepository.LibraryState.Error
        if (!settings.autoScan) libraryRepo.resetAutomaticCheck()
        if (libraryNeedsLoad || (libraryOpened && settings.autoScan)) {
            // 进页面时**优先用缓存**（force = false）：
            // 有缓存就秒开，没缓存才完整扫描。
            // 用户手动点「刷新」时传 force = true。
            libraryRepo.load(
                username = libraryUser,
                password = libraryPass,
                root = settings.alistScanRoot.ifBlank { "/" },
                messages = libraryLoadingMessages(context),
                force = false,
                cacheContext = context,
                serverUrl = libraryUrl,
                sourceCredentials = librarySourceCredentials(),
                sourceAddresses = librarySourceAddresses(),
                allowScan = libraryOpened && settings.autoScan,
                checkForUpdates = libraryOpened && settings.autoScan,
            )
        }
    }

    // 清理过期进度（一次性）
    LaunchedEffect(Unit) {
        runCatching { progressRepo.purgeOld() }
    }

    var loadedTvboxUrl by remember { mutableStateOf("") }

    /**
     * 启动时自动加载订阅 —— 不用每次手动点「加载订阅源」。
     *
     * 触发条件：
     *  - 配置里填了订阅地址
     *  - 当前还没加载过（Idle）
     *
     * 注意 `loadFromUrl` 要 ~15 秒（加固 jar 解密 guard），
     * 所以放后台跑，不阻塞任何 UI。
     */
    LaunchedEffect(settings.tvboxConfigUrl) {
        val url = settings.tvboxConfigUrl
        if (url.isBlank()) return@LaunchedEffect

        val firstLoad = loadedTvboxUrl.isBlank()
        val changed = url != loadedTvboxUrl
        loadedTvboxUrl = url

        // 地址没变且已经有站点：不要重复初始化加固 jar。
        if (!firstLoad && !changed && sourceState !is SourceManager.State.Idle) {
            return@LaunchedEffect
        }

        // Restore the last subscription instantly; no network request or site
        // probe runs while the first online page is being shown.
        // ★ 本机订阅已经完整保存了站点与 spider 配置；解析成功就不再立刻
        //   重复解析同一份 JSON。否则加固 jar 会被绑定两次，启动白白多等一轮。
        // ★ 只有首次加载且缓存来源就是当前地址时才用缓存；
        //   用户改了订阅地址必须重新拉新配置。
        if (firstLoad) {
            val usedCache = sourceManager.loadCachedConfigWithoutNetwork()
            if (usedCache && onlineCache.cachedSubscriptionSource() == url) {
                return@LaunchedEffect
            }
        }
        sourceManager.loadFromUrl(url)
    }

    // 在线源加载用独立 scope，不绑 composition。首页刷新与分类刷新各用独立 job，
    // 避免“上一个分类还没返回，新分类就排队”的额外等待。
    val onlineScope = rememberCoroutineScope()
    var onlineHomeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var onlineHomeJobSiteKey by remember { mutableStateOf("") }
    var onlineCategoryJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Network payloads already fetched in this process, so returning to a tab reuses them.
    val onlineHomeFetchedSites = remember { mutableSetOf<String>() }
    val onlineCategoryFetched = remember { mutableSetOf<String>() }
    // An explicit cache tick (site reload) must bypass the reuse sets.
    var onlineCacheTickSeen by remember { mutableIntStateOf(onlineCacheTick) }
    var onlineCategoryTickSeen by remember { mutableIntStateOf(onlineCacheTick) }

    LaunchedEffect(dest, onlineSiteKey, sourceState, onlineCacheTick) {
        if (dest != TopDest.ONLINE) return@LaunchedEffect
        val forced = onlineCacheTick != onlineCacheTickSeen
        if (forced) {
            onlineCacheTickSeen = onlineCacheTick
            onlineHomeFetchedSites.clear()
            onlineCategoryFetched.clear()
        }
        // 缓存站点顺序和真实站点顺序不一定相同；index 只在列表内部使用，
        // 跨到 SourceManager 时必须换成 stable key。
        val siteKey = onlineSiteKey.ifBlank {
            cachedSiteKeys.getOrNull(onlineSiteIndex.coerceAtLeast(0))?.key.orEmpty()
        }
        if (onlineSiteKey.isBlank() && siteKey.isNotBlank()) onlineSiteKey = siteKey
        if (siteKey.isBlank()) return@LaunchedEffect
        // Top-level restore already loaded the snapshot; no disk retry on this UI frame.

        // ---- 第一步：先使用磁盘中的上一份快照，不显示加载遮罩 ----
        val cachedHome = onlineCache.cachedHomeFor(siteKey)
        // Only content explicitly stored for this exact site counts as a first paint.
        if (cachedHome != null) {
            onlineHome = cachedHome.data
            onlineFromCache = true
            onlineLoading = false
            // 选择态一定来自这个快照：过期/主站的 categoryId 不能拿来展示。
            val storedCategory = onlineCategoryId
                ?.takeIf { id -> cachedHome.data.categories.any { it.id == id } }
                ?: cachedHome.data.categories.firstOrNull()?.id
            if (storedCategory != null) {
                onlineCategoryId = storedCategory
                onlineCategory = onlineCache.cachedCategory(siteKey, storedCategory)?.data
                onlineCategorySiteKey = siteKey
            }
        } else if (onlineHome?.recommendations.isNullOrEmpty()) {
            onlineLoading = true
        }
        onlineError = null

        // ★ 关键性能边界：磁盘快照阶段绝不初始化 jar。
        //   快照已经先出现；如果站点目录尚未恢复，下次 Ready 会重新进入这里。
        // ★ 必须按 key 找真实站点，不能拿“缓存站点下标”去索引实际站点表：
        //   两者顺序不同时，会把 A 站点的位置指向 B 站点，或者像 01:10 版本
        //   那样一直用快照里的 spider=null，导致快照显示后永远不发首页请求。
        val spider = sourceManager.find(siteKey)?.spider
        if (spider == null) {
            // There is genuinely no first paint, so keep the loader visible;
            // a cache-backed first paint must not show a spinner.
            if (onlineHome?.recommendations.isNullOrEmpty()) onlineLoading = true
            return@LaunchedEffect
        }
        // Returning to the tab reuses the payload fetched earlier in this process.
        if (!forced && siteKey in onlineHomeFetchedSites && !onlineHome?.recommendations.isNullOrEmpty()) {
            onlineCategoryLoading = false
            onlineLoading = false
            onlineFromCache = onlineFromCache || cachedHome != null
            return@LaunchedEffect
        }
        // 同一个站点只允许一条首页/分类请求。siteKey 变化时才替换，
        // Ready 状态再次发射不能取消刚开始的请求。
        if (onlineHomeJob?.isActive == true && onlineHomeJobSiteKey == siteKey) return@LaunchedEffect
        onlineCategoryLoading = onlineHome != null

        val job = onlineScope.launch {
            // 只有换站时才取消上一条请求。启动时 Ready 会重进本 effect，
            // 之前的写法会在新请求刚发出时把它取消，导致玩偶一直“加载中”。
            // Any failure (including site init) hands control back to the UI.
            try {
                // ---- 第二步：分类/推荐和分类内容分别刷新，互不阻塞 ----
                var home = onlineHome
                if (home == null) {
                    onlineCategoryLoading = true
                    val started = System.currentTimeMillis()
                    home = runCatching { spider.home() }.getOrElse {
                        android.util.Log.e("OnlineLoad", "home failed site=$siteKey in ${System.currentTimeMillis() - started}ms: ${it.message}", it)
                        onlineError = it.message ?: "加载失败"
                        onlineCategoryLoading = false
                        onlineLoading = false
                        return@launch
                    }
                    android.util.Log.i("OnlineLoad", "home site=$siteKey in ${System.currentTimeMillis() - started}ms size=${home.recommendations.size}")
                    onlineHome = home
                    onlineHomeFetchedSites += siteKey
                    withContext(Dispatchers.IO) { onlineCache.putHomeForSite(siteKey, home) }
                } else {
                    onlineCategoryLoading = true
                    runCatching { spider.home() }.onSuccess { fresh ->
                        if (fresh.categories.isNotEmpty() || fresh.recommendations.isNotEmpty()) {
                            onlineHome = fresh
                            onlineHomeFetchedSites += siteKey
                            withContext(Dispatchers.IO) { onlineCache.putHomeForSite(siteKey, fresh) }
                        }
                    }.onFailure {
                        android.util.Log.w("OnlineCache", "首页刷新失败，保留快照: ${it.message}")
                    }
                    onlineCategoryLoading = false
                }

                if (onlineCategoryId == null ||
                    onlineHome?.categories?.none { it.id == onlineCategoryId } == true
                ) {
                    val catId = home.categories.firstOrNull()?.id
                    if (catId != null) onlineCategoryId = catId
                }
                onlineLoading = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onlineError = onlineError ?: (e.message ?: "加载失败")
            } finally {
                onlineCategoryLoading = false
                onlineContentLoading = false
                onlineLoading = false
            }
        }
        onlineHomeJob = job
        onlineHomeJobSiteKey = siteKey
    }

    LaunchedEffect(dest, onlineSiteKey, onlineCategoryId, sourceState, onlineCacheTick) {
                if (dest != TopDest.ONLINE) return@LaunchedEffect
                // No selected category means the grid is the home recommendation.
                // Do not request the first category and cover its cached posters.
                val catId = onlineCategoryId ?: return@LaunchedEffect
        val siteKey = onlineSiteKey.ifBlank {
            cachedSiteKeys.getOrNull(onlineSiteIndex.coerceAtLeast(0))?.key.orEmpty()
        }
        if (siteKey.isBlank()) return@LaunchedEffect
        val categoryKey = "$siteKey|$catId"
        val categoryCached = onlineCache.cachedCategory(siteKey, catId)
        val categoryForced = onlineCacheTick != onlineCategoryTickSeen
        if (categoryForced) onlineCategoryTickSeen = onlineCacheTick
        if (!categoryForced && categoryKey in onlineCategoryFetched) {
            categoryCached?.let { onlineCategory = it.data }
            onlineContentLoading = false
            return@LaunchedEffect
        }

        val cached = categoryCached
        if (cached != null) {
            onlineCategory = cached.data
            onlineFromCache = true
            onlineContentLoading = false
        } else {
            onlineContentLoading = true
        }

        val spider = sourceManager.find(siteKey)?.spider ?: return@LaunchedEffect
        onlineCategoryJob?.cancel()
        onlineCategoryJob = onlineScope.launch {
            try {
                // Disk snapshots are written off the main path: 72 posters of JSON
                // must not delay the frame that shows the new category.
                val fresh = runCatching { spider.category(catId) }.getOrNull()
                if (fresh != null && fresh.items.isNotEmpty()) {
                    onlineCategoryFetched += categoryKey
                    withContext(Dispatchers.IO) { onlineCache.putCategory(siteKey, catId, fresh) }
                }
                // A stale response for a category the user already left must not replace the grid.
                if (fresh != null && onlineCategoryId == catId) onlineCategory = fresh
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("OnlineCache", "分类刷新失败，保留快照: ${e.message}")
            } finally {
                onlineContentLoading = false
            }
        }
    }

    BackHandler(enabled = showSettings && !showPanAuth && !showPanLogin) {
        nativeSettingsViewRef?.handleBack()
    }
    BackHandler(enabled = !showSettings && (playing != null || detailItem != null || showPanLogin || showPanAuth)) {
        when {
            showPanAuth -> {
                showPanAuth = false
                pendingPanProvider = null
            }
            showPanLogin -> showPanLogin = false
            // 播放页自己处理了 BackHandler（要存进度），这里只兜底。
            // 兜底路径同样要置恢复标记，否则返回后焦点会留在顶部播放键。
            playing != null -> {
                playing = null
                if (detailItem != null) restoreDetailEpisodeFocus = true
            }
            detailItem != null -> {
                // 返回时按“进入详情的来源页”恢复焦点，而不是按数据来源判断。
                when (detailOrigin) {
                    TopDest.HOME -> restoreHomePosterFocus = true
                    TopDest.LIBRARY -> restoreLibraryPosterFocus = true
                    TopDest.SEARCH -> restoreSearchPosterFocus = true
                    TopDest.ONLINE -> restoreOnlinePosterFocus = true
                    null -> Unit
                }
                detailOrigin = null
                detailItem = null
                detailSource = null
                onlineDetail = null
                onlineDetailFromCache = false
            }
        }
    }

    // 一级页面（首页/在线/片库/搜索）没有可退的上级页面：
    // 第一次 BACK 只提示，2 秒内再次 BACK 才退出，避免误触。
    val atRootPage = playing == null && detailItem == null &&
        !showSettings && !showPanLogin && !showPanAuth
    BackHandler(enabled = atRootPage) {
        if (dest == TopDest.HOME && homeViewRef?.handleBack() == true) {
            lastBackAt = 0L
            return@BackHandler
        }
        if (dest == TopDest.SEARCH && searchViewRef?.handleBack() == true) {
            return@BackHandler
        }
        val now = SystemClock.uptimeMillis()
        if (KeyRouting.shouldExitOnSecondBack(lastBackAt, now, EXIT_WINDOW_MS)) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(
                context,
                context.getString(R.string.press_back_again_to_exit),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    suspend fun evictUnusedHistoryArtwork(posterUrl: String?, backdropUrl: String?, title: String?) {
        val remaining = progressRepo.snapshotAll()
        val visible = libraryRepo.allItems() + onlineHome?.recommendations.orEmpty() + onlineHome?.heroes.orEmpty() +
            onlineCategory?.items.orEmpty() + onlineSearch.hits.flatMap { it.items }
        val keepUrls = com.reelix.app.data.image.collectArtworkReferences(
            remaining.map { it.posterUrl to it.backdropUrl } +
                visible.map { it.poster to it.backdrop },
        )
        val keepTitles = com.reelix.app.data.image.artworkTitleKeys(
            remaining.map { it.title } + visible.map { it.title },
        )
        val libraryReady = libraryTitlesKnown(
            libraryState,
            settings.alistEnabled || settings.webdavEnabled || settings.embyEnabled,
        )
        com.reelix.app.data.image.PosterCacheCleaner
            .evict(context, posterUrl, backdropUrl, title, keepUrls, keepTitles, forgetTitle = libraryReady)
    }

    /** 清空历史时批量清理：引用名单只算一次，避免每条记录都回查一次库。 */
    suspend fun evictUnusedHistoryArtwork(removed: List<PlayProgressEntity>) {
        if (removed.isEmpty()) return
        val remaining = progressRepo.snapshotAll()
        val visible = libraryRepo.allItems() + onlineHome?.recommendations.orEmpty() + onlineHome?.heroes.orEmpty() +
            onlineCategory?.items.orEmpty() + onlineSearch.hits.flatMap { it.items }
        val keepUrls = com.reelix.app.data.image.collectArtworkReferences(
            remaining.map { it.posterUrl to it.backdropUrl } +
                visible.map { it.poster to it.backdrop },
        )
        val keepTitles = com.reelix.app.data.image.artworkTitleKeys(
            remaining.map { it.title } + visible.map { it.title },
        )
        val libraryReady = libraryTitlesKnown(
            libraryState,
            settings.alistEnabled || settings.webdavEnabled || settings.embyEnabled,
        )
        removed.forEach {
            com.reelix.app.data.image.PosterCacheCleaner
                .evict(context, it.posterUrl, it.backdropUrl, it.title, keepUrls, keepTitles,
                    forgetTitle = libraryReady)
        }
    }

    Box(Modifier.fillMaxSize().background(RxColors.Backdrop)) {
        val target = playing
        val currentDetail = detailItem
        // configured() 会读 CookieManager（同步 IPC），不能放在每次重组的 update 里。
        val panProviders = remember(panAuthTick, showSettings) {
            PanCookieStore.configured(context).map { it.name }.toSet()
        }

        when {
            // ---------- ⓪-a 网盘授权页（填 Cookie） ----------
            showPanAuth -> {
                PanAuthScreen(
                    initialProvider = pendingPanProvider,
                    onBack = {
                        showPanAuth = false
                        pendingPanProvider = null
                    },
                )
            }

            // ---------- ⓪-a 网盘扫码页 ----------
            showPanLogin -> {
                PanLoginScreen(
                    onBack = { showPanLogin = false },
                    onAuthorized = {
                        showPanLogin = false
                        // 授权后重建 spider 实例，让 jar 读到新写入的凭据
                        scope.launch {
                            sourceManager.loadFromUrl(settings.tvboxConfigUrl)
                        }
                    },
                )
            }

            // ---------- ⓪ 设置页（最上层） ----------
            showSettings -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { NativeSettingsView(it, nativeSettingsSession).also { view -> nativeSettingsViewRef = view } },
                    onRelease = { view -> view.dispose(); if (nativeSettingsViewRef === view) nativeSettingsViewRef = null },
                    update = { view ->
                        view.render(NativeSettingsSnapshot(
                            settings, sourceState, libraryTaskStatus, libraryScanProgress, artworkTaskStatus,
                            history.size, panProviders,
                            settingsServerUrl, BuildConfig.VERSION_NAME,
                        ), NativeSettingsActions(
                            onUpdate = { settingsRepo.update(it) },
                            onUpdateArtworkOrder = { settingsRepo.updateArtworkOrder(it) },
                            onClose = {
                                nativeSettingsSession.reset()
                                restoreSettingsButtonFocus = true
                                showSettings = false
                            },
                            onTestConnection = { candidate ->
                                withContext(Dispatchers.IO) {
                                    try {
                                        when (candidate.librarySource.uppercase()) {
                                            "WEBDAV" -> {
                                                val client = com.reelix.app.data.webdav.WebDavClient(
                                                    candidate.webdavUrl, candidate.webdavUser, candidate.webdavPass,
                                                )
                                                client.login(candidate.webdavUser, candidate.webdavPass).getOrThrow()
                                                ConnectionTestResult(true, "WebDAV 连接成功")
                                            }
                                            "EMBY" -> {
                                                val client = com.reelix.app.data.emby.EmbyClient(
                                                    candidate.embyUrl, candidate.embyApiKey,
                                                    candidate.embyUser, candidate.embyPass,
                                                )
                                                client.login(candidate.embyUser, candidate.embyPass).getOrThrow()
                                                ConnectionTestResult(true, "Emby/Jellyfin 连接成功")
                                            }
                                            else -> {
                                                val client = AlistClient(baseUrl = candidate.alistUrl)
                                                client.login(candidate.alistUser, candidate.alistPass).getOrThrow()
                                                val items = client.list("/")
                                                ConnectionTestResult(true, context.getString(R.string.settings_test_ok, items.size))
                                            }
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (failure: Exception) {
                                        ConnectionTestResult(false, context.getString(R.string.settings_test_failed, failure.message?.take(100).orEmpty()))
                                    }
                                }
                            },
                            onReloadSource = {
                                if (settingsRepo.current.tvboxConfigUrl.isNotBlank()) {
                                    sourceManager.loadFromUrl(settingsRepo.current.tvboxConfigUrl)
                                    val sites = sourceManager.sites()
                                    if (sites.isNotEmpty()) {
                                        probing = true
                                        try { probeResults = SourceProbe.probeAll(sites) } finally { probing = false }
                                    }
                                }
                            },
                            onOpenAuth = { provider -> pendingPanProvider = provider; showPanAuth = true },
                            onClearAuth = { provider ->
                                com.reelix.app.data.pan.WebLoginHelper.clearCookie(context, provider)
                                panAuthTick++
                            },
                            onRefresh = { refreshLibrary() },
                            onManageDirectories = { com.reelix.app.ui.screen.library.LibraryDialogs.directories(context, libraryRepo, settings.alistScanRoot) },
                            onRetryArtwork = { scope.launch { withContext(Dispatchers.IO) { libraryArtworkLoader.retryMissing() }; libraryArtworkTick++ } },
                            onClearImages = { done -> scope.launch {
                                withContext(Dispatchers.IO) { com.reelix.app.data.image.PosterCacheCleaner.clearAll(context) }
                                done()
                            } },
                            onClearHistory = { scope.launch {
                                val removed = progressRepo.snapshotAll()
                                progressRepo.clearAll()
                                evictUnusedHistoryArtwork(removed)
                            } },
                        ))
                    },
                )
            }

            // ---------- ① 播放页 ----------
            target != null -> {
                PlayerScreen(
                    detail = target.detail,
                    defaultDecoder = com.reelix.app.player.DecoderMode.fromStored(settings.defaultDecoder),
                    controlsHideSeconds = settings.controlsHideSeconds,
                    panelSize = com.reelix.app.ui.screen.player.PlayerPanelSize.fromStored(settings.playerPanelSize),
                    initialRouteIndex = target.routeIndex,
                    initialEpisodeIndex = target.episodeIndex,
                    onBack = {
                        playing = null
                        restoreDetailEpisodeFocus = true
                    },
                    // 需要直链时回调（alist 的 raw_url 有签名，必须现取）
                    // 解析播放地址：按来源分流
                    // - 片库：走 alist 取带签名的直链
                    // - 在线源：调 jar 的 playerContent(flag, id) 拿真实地址
                    resolveEpisode = resolve@{ ep ->
                        playResolveError = null
                        val srcKey = target.detail.item.sourceKey
                        val onlineSites = (sourceState as? SourceManager.State.Ready)?.sites.orEmpty()
                        val onlineSpider = onlineSites.firstOrNull { it.site.key == srcKey }?.spider

                        if (onlineSpider != null) {
                            val cacheKey = PlayResolutionCache.key(srcKey, ep.flag, ep.playId)
                            val remembered = PlayResolutionCache.get(cacheKey)
                            if (remembered != null) {
                                android.util.Log.i("PLAYTRACE", "播放解析命中短期缓存: $cacheKey")
                                return@resolve ep.copy(
                                    url = remembered.url,
                                    headers = remembered.headers,
                                )
                            }
                            // ★ 解析开始：记录起点 + 第一个阶段。
                            //   播放页会显示「阶段 · 已等待 X.X 秒」，超过 10 秒给结论。
                            resolveStartedAt = System.currentTimeMillis()
                            resolveStage = "正在向源站请求播放地址"
                            // ★ 统一前缀 PLAYTRACE：开机后一条 logcat | grep PLAYTRACE
                            //   就能看到解析链每一步，不用逐条翻日志。
                            android.util.Log.i(
                                "PLAYTRACE",
                                "开始 srcKey=$srcKey flag=${ep.flag} playId=${ep.playId.take(120)}"
                            )
                            android.util.Log.i(
                                "Reelbox",
                                "播放解析[在线]: srcKey=$srcKey flag=${ep.flag} id=${ep.playId}"
                            )
                            val res = try {
                                onlineSpider.resolvePlayUrl(ep.flag, ep.playId)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                playResolveError = failure.message
                                null
                            }
                            android.util.Log.i(
                                "PLAYTRACE",
                                "playerContent -> url=${res?.url?.take(150) ?: "null"} " +
                                    "direct=${res?.direct} parser=${res?.parserName} msg=${res?.msg}"
                            )
                            android.util.Log.i(
                                "Reelbox",
                                "播放解析结果: url=${res?.url?.take(100)} direct=${res?.direct}"
                            )
                            // ── 播放解析链 ─────────────────────────────
                            // ① jar 直接给媒体地址           → 用（最快）
                            // ② jar 给的是页面地址           → WebView 嗅探
                            // ③ jar 给的是网盘分享页         → 直接报"要登录"
                            // ④ jar 什么都没给（带 msg）     → 把 msg 上屏
                            // ─────────────────────────────────────────
                            if (res == null || res.url.isBlank()) {
                                PlayResolutionCache.remove(cacheKey)
                                android.util.Log.w(
                                    "PLAYTRACE",
                                    "无地址，msg=${res?.msg ?: "null"}"
                                )
                                playResolveError = res?.msg ?: playResolveError
                                    ?: "源没有返回播放地址（网盘源需要先在「设置 → 网盘授权」里填 Cookie）"
                                android.util.Log.w("Reelbox", "播放解析失败: $playResolveError")
                                return@resolve null
                            }

                            val pageUrl = res.url

                            // ① 本来就是媒体 → 直接用
                            android.util.Log.i(
                                "PLAYTRACE",
                                "looksLikeMedia=${SniffRules.looksLikeMedia(pageUrl)} " +
                                    "isPanShare=${SniffRules.isPanSharePage(pageUrl)}"
                            )
                            if (!SniffRules.isPanSharePage(pageUrl) && isDirectPlayback(res)) {
                                android.util.Log.i("Reelbox", "① 直链可播: ${pageUrl.take(90)}")
                                PlayResolutionCache.put(cacheKey, pageUrl, res.headers)
                                return@resolve ep.copy(
                                    url = pageUrl,
                                    headers = res.headers,
                                )
                            }

                            // ③ 网盘分享页 → 嗅探没意义，直接报原因
                            if (SniffRules.isPanSharePage(pageUrl)) {
                                PlayResolutionCache.remove(cacheKey)
                                playResolveError =
                                    "这条线路是网盘分享页，需要先在「设置 → 网盘授权」里登录对应网盘"
                                android.util.Log.w("Reelbox", "③ 网盘分享页: $pageUrl")
                                return@resolve null
                            }

                            // ②-a 先试 HTTP 解析接口（配置里的 parses[]）
                            //     一次 HTTP 约 500ms，比开 WebView 快一个数量级
                            resolveStage = "正在用解析接口解析"
                            playResolveError = null
                            val parses = sourceManager.parses()
                            if (res.playUrl.isNullOrBlank() && parses.any { it.type == 1 || it.type == 2 }) {
                                val fast = try {
                                    ParseExecutor.resolve(pageUrl, ep.flag, parses)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                if (fast?.url != null) {
                                    android.util.Log.i(
                                        "Reelbox",
                                        "②-a 接口解析成功(${fast.elapsedMs}ms, ${fast.parserName}): " +
                                            fast.url.take(90)
                                    )
                                    PlayResolutionCache.put(cacheKey, fast.url, combinePlaybackHeaders(res.headers, fast.headers))
                                    return@resolve ep.copy(
                                        url = fast.url,
                                        headers = combinePlaybackHeaders(res.headers, fast.headers),
                                    )
                                }
                                android.util.Log.d("Reelbox", "②-a 接口解析未命中: ${fast?.error}")
                            }

                            // ②-b 播放页 HTML → WebView 嗅探
                            resolveStage = "正在嗅探网页视频地址（最慢的一步）"
                            android.util.Log.i("Reelbox", "②-b 走嗅探: ${pageUrl.take(90)}")
                            var sniff: WebViewSniffer.Result? = null
                            for (request in playbackWebRequests(res, parses, ep.flag)) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                sniff = try {
                                    WebViewSniffer(context).sniff(request.url, request.headers)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                                if (sniff?.url != null) break
                            }

                            val sniffUrl = sniff?.url
                            if (sniffUrl != null) {
                                android.util.Log.i(
                                    "Reelbox",
                                    "② 嗅探成功(${sniff?.elapsedMs}ms): ${sniffUrl.take(90)}"
                                )
                                val playbackHeaders = combinePlaybackHeaders(res.headers, sniff?.headers.orEmpty())
                                PlayResolutionCache.put(cacheKey, sniffUrl, playbackHeaders)
                                ep.copy(url = sniffUrl, headers = playbackHeaders)
                            } else {
                                // ── ③ 兜底：把原始地址直接交给播放器试一次 ──
                                //
                                // 踩过的坑（2026-11-02）：
                                // 有些源的 playerContent 直接返回**裸直链**，但路径里没扩展名，例如：
                                //   https://cdn.xxx.com/v/abc123?sign=xxx
                                // looksLikeMedia() 因为没扩展名判为 false → 去嗅探
                                // → 嗅探也抓不到（那不是网页）→ 报错或卡住
                                //
                                // 这时候正确的做法是：**直接喂给 ExoPlayer**。
                                // 喂错了最多报 SourceNotSupported（有明确提示），
                                // 比让用户对着黑屏干等好得多。
                                val looksHttp = pageUrl.startsWith("http://", true) ||
                                    pageUrl.startsWith("https://", true)

                                if (looksHttp && !res.requiresParsing && res.playUrl.isNullOrBlank() &&
                                    !SniffRules.isPanSharePage(pageUrl)) {
                                    android.util.Log.w(
                                        "PLAYTRACE",
                                        "嗅探失败，兜底直接把原地址交给播放器: ${pageUrl.take(120)}"
                                    )
                                    ep.copy(url = pageUrl, headers = res.headers)
                                } else {
                                    PlayResolutionCache.remove(cacheKey)
                                    playResolveError = context.getString(R.string.playback_parse_failed,
                                        sniff?.error.orEmpty())
                                    android.util.Log.w("Reelbox", "② 嗅探失败: $playResolveError")
                                    null
                                }
                            }
                        } else {
                            // 片库分支：走 alist 现取带签名的直链
                            resolveStartedAt = System.currentTimeMillis()
                            resolveStage = "正在从片库取直链"
                            android.util.Log.i(
                                "ReelixPlay",
                                "片库解析: playId=${ep.playId} flag=${ep.flag}"
                            )
                            val r = libraryRepo.resolveEpisode(ep)
                            android.util.Log.i(
                                "ReelixPlay",
                                "片库解析结果: url=${r?.url?.take(120)}"
                            )
                            if (r == null) {
                                playResolveError = "片库取直链失败：playId=${ep.playId.take(80)}"
                            }
                            r
                        }
                    },
                    externalError = playResolveError,
                    resolveStage = resolveStage,
                    resolveStartedAt = resolveStartedAt,
                    // 续播：读上次看到哪
                    loadResumePosition = {
                        val route = target.detail.routes.getOrNull(target.routeIndex)
                        val selected = route?.episodes?.getOrNull(target.episodeIndex)
                        val record = progressRepo.find(target.detail.item.id)
                        val can = record?.let {
                            com.reelix.app.data.repo.canResumeEpisode(
                                it, selected, route?.name.orEmpty(), target.detail.item.fromLibrary,
                            )
                        } == true
                        record?.takeIf { can }?.let {
                            ResumePoint(it.episodeIndex, it.positionMs)
                        }
                    },
                    // 存进度
                    onSaveProgress = { epIdx, epName, routeName, pos, dur, finished ->
                        android.util.Log.i(
                            "ReelixSave",
                            "onSaveProgress: id=${target.detail.item.id} title=${target.detail.item.title} " +
                                "ep=$epIdx pos=$pos dur=$dur finished=$finished"
                        )
                        val savedEpisode = target.detail.routes
                            .firstOrNull { it.name == routeName }
                            ?.episodes
                            ?.firstOrNull { it.index == epIdx }
                            ?: target.detail.routes
                                .flatMap { it.episodes }
                                .firstOrNull { it.index == epIdx }
                        progressRepo.save(
                            item = if (target.detail.item.fromLibrary) target.detail.item.copy(sourceId =
                                savedEpisode?.playId
                                    ?: target.detail.item.sourceId) else target.detail.item,
                            episodeIndex = epIdx,
                            episodeName = epName,
                            routeName = routeName,
                            positionMs = pos,
                            durationMs = dur,
                            finished = finished,
                            // ★ 退出播放页时**强制保存**。
                            //
                            // 原来的 force = finished 有两个漏点：
                            //   ① 看 8 秒就退出 → positionMs < 10_000 被丢弃
                            //   ② 看到一半退出但 finished=false → 同样被丢弃
                            // 用户实际看了就该校出现在历史里，哪怕只看了几秒。
                            // 节流（3 秒一次）由 PlayerScreen 负责，这里不用再拦。
                            force = true,
                        )
                    },
                )
            }

            // ---------- ② 详情页 ----------
            currentDetail != null -> {
                // 在线源条目：先显示上一份线路/选集快照。详情 key 必须同时区分站点和作品 id；
                // 任一不同即视为上一部作品，不能等待网络返回后才替换。
                val onlineDetailMatches = onlineDetail?.let {
                    it.item.sourceKey == currentDetail.sourceKey && it.item.sourceId == currentDetail.sourceId
                } == true
                if (detailSource == DetailSource.Online && !onlineDetailMatches) {
                    onlineDetail = null
                }
                if (detailSource == DetailSource.Online) {
                    val cached = onlineCache.cachedDetail(currentDetail.sourceKey, currentDetail.sourceId)
                    onlineDetailFromCache = cached != null
                    if (cached != null) onlineDetail = cached.data
                    onlineDetailLoading = onlineDetail == null
                }
                LaunchedEffect(currentDetail.sourceKey, currentDetail.sourceId, detailSource, sourceState) {
                    if (detailSource != DetailSource.Online) return@LaunchedEffect
                    val siteKey = currentDetail.sourceKey
                    val vodId = currentDetail.sourceId
                    val cached = onlineCache.cachedDetail(siteKey, vodId)
                    onlineDetailFromCache = cached != null
                    if (cached != null) {
                        onlineDetail = cached.data
                    }

                    val sites = (sourceState as? SourceManager.State.Ready)?.sites.orEmpty()
                    val spider = sites.firstOrNull { it.site.key == siteKey }?.spider
                        ?: sourceManager.find(siteKey)?.spider
                    // The cached subscription becomes Ready before the network refresh.
                    if (spider == null) {
                        onlineDetailLoading = false
                        return@LaunchedEffect
                    }

                    runCatching { spider.detail(vodId) }
                        .onSuccess { fresh ->
                            if (fresh?.routes?.any { r -> r.episodes.isNotEmpty() } == true) {
                                onlineDetail = fresh
                                onlineCache.putDetail(siteKey, vodId, fresh)
                            }
                        }
                        .onFailure {
                            android.util.Log.w("DetailCache", "详情刷新失败，保留快照: ${it.message}")
                        }
                    // The request is over either way; never leave the routes spinner forever.
                    onlineDetailLoading = false
                }

                // ★ 详情页拿到的 item 来自海报列表，本身没有播放进度
                //   （buildDetail(Online) 直接返回原 item）。进度存在 PlayProgress
                //   表里，这里按 mediaId 取回来注入 —— 否则"第N集 / 重看"永远不显示
                //   （2026-11-05 用户反馈：播过了但按钮还是"播放"）。
                val baseDetail = onlineDetail
                    ?: buildDetail(currentDetail, detailSource, libraryRepo,
                        (libraryState as? LibraryRepository.LibraryState.Ready)?.entries)
                val metadataBinding = libraryRepo.artworkBinding(baseDetail.item.id)
                val metadataCoverOnly = libraryRepo.coverOnly(baseDetail.item.id)
                val metadataArtwork = libraryRepo.automaticArtwork(baseDetail.item.id)
                val onlineOverrideSource = com.reelix.app.data.image.OnlineArtworkOverrides.sourceOf(baseDetail.item)
                val onlineOverrideIdentity = com.reelix.app.data.image.OnlineArtworkOverrides.identityOf(baseDetail.item)
                LaunchedEffect(baseDetail.item.id, metadataBinding, metadataCoverOnly, artworkSite,
                    metadataArtwork?.identity, settings.tmdbApiKey, settings.tmdbEnabled, settings.tmdbLanguage,
                    settings.artworkChannels, settings.artworkOrder,
                    // 换图完成后（片库异步 / 在线立即）都要重取资料，
                    // 否则只有海报变、简介和演职员仍是旧来源。
                    libraryArtworkTick,
                    onlineOverrideSource, onlineOverrideIdentity) {
                    if (!baseDetail.item.fromLibrary) {
                        val cachedEntry = com.reelix.app.data.image.OnlineMetadataStore.get(baseDetail.item)
                        // 手动换图：只认用户选中的作品身份，按 ID 取资料。
                        if (onlineOverrideSource != null || onlineOverrideIdentity != null) {
                            val identity = onlineOverrideIdentity
                            if (identity == null) {
                                // 老的手动覆盖没有身份：只换图，不拿旧片名猜资料。
                                android.util.Log.i("OnlineMetadata",
                                    "手动换图无身份，资料保持: ${baseDetail.item.sourceKey}/${baseDetail.item.sourceId}")
                                return@LaunchedEffect
                            }
                            val sameCachedWork = cachedEntry?.identity?.let {
                                it.provider == identity.provider && it.id == identity.id &&
                                    it.siteKey == identity.siteKey
                            } == true
                            if (cachedEntry?.manual == true && sameCachedWork) {
                                android.util.Log.d("OnlineMetadata",
                                    "手动资料缓存命中: ${identity.provider}/${identity.id}")
                                return@LaunchedEffect
                            }
                            android.util.Log.i("OnlineMetadata",
                                "手动资料开始: ${baseDetail.item.title} -> ${identity.provider}/${identity.id}")
                            try {
                                val record = libraryArtworkLoader.loadMetadataByIdentity(
                                    baseDetail.item, identity, artworkSite,
                                    settingsRepo.current.tmdbApiKey, settingsRepo.current.tmdbEnabled,
                                    channels = settingsRepo.current.artworkChannels,
                                    order = settingsRepo.current.artworkOrder)
                                if (record != null) {
                                    com.reelix.app.data.image.OnlineMetadataStore.save(baseDetail.item,
                                        com.reelix.app.data.image.OnlineMetadataStore.Entry(
                                            rating = record.rating,
                                            ratingSource = record.ratingSource,
                                            overview = record.overview,
                                            year = record.identity.year,
                                            directors = record.directors,
                                            cast = record.cast,
                                            identity = record.identity,
                                            manual = true,
                                        ))
                                    android.util.Log.i("OnlineMetadata",
                                        "手动资料已保存: ${identity.provider}/${identity.id} " +
                                            "overview=${!record.overview.isNullOrBlank()} cast=${record.cast.size} " +
                                            "rating=${record.rating}")
                                    libraryArtworkTick++
                                } else {
                                    android.util.Log.i("OnlineMetadata",
                                        "手动资料无内容，保留旧值: ${identity.provider}/${identity.id}")
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("OnlineMetadata",
                                    "手动资料失败: ${identity.provider}/${identity.id}", e)
                            }
                            return@LaunchedEffect
                        }
                        // 没有手动换图：沿用自动补资料，只填空，不覆盖源站已有内容。
                        if (cachedEntry != null) return@LaunchedEffect
                        try {
                            val record = libraryArtworkLoader.loadOnlineMetadata(
                                baseDetail.item, settingsRepo.current.tmdbApiKey, settingsRepo.current.tmdbEnabled,
                                settingsRepo.current.tmdbLanguage,
                                channels = settingsRepo.current.artworkChannels,
                                order = settingsRepo.current.artworkOrder)
                            if (record != null) {
                                com.reelix.app.data.image.OnlineMetadataStore.save(baseDetail.item,
                                    com.reelix.app.data.image.OnlineMetadataStore.Entry(
                                        rating = record.rating,
                                        ratingSource = record.ratingSource,
                                        overview = record.overview,
                                        year = record.identity.year,
                                        directors = record.directors,
                                        cast = record.cast,
                                        identity = record.identity,
                                        manual = false,
                                    ))
                                android.util.Log.i("OnlineMetadata",
                                    "自动资料已保存: ${record.identity.provider}/${record.identity.id}")
                                libraryArtworkTick++
                            } else {
                                android.util.Log.i("OnlineMetadata",
                                    "自动资料无结果: ${baseDetail.item.title}")
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            android.util.Log.w("OnlineMetadata",
                                "自动资料失败: ${baseDetail.item.title}", e)
                        }
                        return@LaunchedEffect
                    }
                    try {
                        val record = libraryArtworkLoader.loadMetadata(baseDetail.item, metadataBinding, metadataCoverOnly,
                            metadataArtwork, libraryRepo.metadata(baseDetail.item.id),
                            artworkSite, settingsRepo.current.tmdbApiKey, settingsRepo.current.tmdbEnabled,
                            settingsRepo.current.tmdbLanguage,
                            channels = settingsRepo.current.artworkChannels,
                            order = settingsRepo.current.artworkOrder)
                        if (record != null) libraryRepo.setMetadata(baseDetail.item.id, metadataBinding, record)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { /* Existing valid metadata remains visible when a provider fails. */ }
                }
                val savedProgress = progressMap[baseDetail.item.id]
                val overrideItem = com.reelix.app.data.image.OnlineMetadataStore.apply(
                    libraryRepo.effectiveArtwork(baseDetail.item))
                val displayDetail = if (savedProgress != null) {
                    baseDetail.copy(
                        item = overrideItem.copy(
                            progress = savedProgress.progress,
                            lastEpisode = savedProgress.episodeIndex,
                        )
                    )
                } else {
                    baseDetail.copy(item = overrideItem)
                }
                // ★ TMDB 补刮横图（2026-11-07 从旧 Compose 详情页搬回）。
                //
                //   迁到 View 版时这段逻辑整个丢了：`ArtworkResolver` 只留在已废弃的
                //   DetailScreen.kt 里，DetailView 连 tmdbKey 参数都没有。结果详情页
                //   背景永远用海报墙那张竖图 —— 用户看到的就是"等很久也不出现大图"。
                //
                //   只有"一个横图都没有"时才发请求；片库条目自带本地横图，不浪费配额。
                val canScrapeBackdrop = !displayDetail.item.fromLibrary && com.reelix.app.data.image.shouldScrapeDetailArtwork(
                    displayDetail.item, settings.tmdbEnabled, settings.tmdbApiKey,
                    channelsAvailable = settings.artworkChannels.any { it.enabled })
                val tmdbFirst = settings.artworkOrder.firstOrNull()?.let { it == "TMDB" } ?: true
                val tmdbCanContribute = settings.tmdbEnabled && settings.tmdbApiKey.isNotBlank()
                var detailScraped by remember(displayDetail.item.id, displayDetail.item.title,
                    displayDetail.item.year, displayDetail.item.backdrop, displayDetail.item.poster,
                    settings.tmdbApiKey, settings.tmdbEnabled, settings.tmdbLanguage, settings.artworkOrder) {
                    mutableStateOf(if (canScrapeBackdrop && tmdbFirst && tmdbCanContribute &&
                        displayDetail.item.backdrop.isNullOrBlank())
                        com.reelix.app.data.tmdb.TmdbClient.cachedMatch(displayDetail.item.title,
                            displayDetail.item.year, settings.tmdbLanguage)?.backdropUrlW1280 else null)
                }
                var detailArtworkReady by remember(displayDetail.item.id, displayDetail.item.poster,
                    settings.tmdbApiKey, settings.tmdbEnabled, settings.tmdbLanguage, settings.artworkOrder) {
                    mutableStateOf(!canScrapeBackdrop || !displayDetail.item.backdrop.isNullOrBlank() ||
                        !displayDetail.item.backdropRes.isNullOrBlank() ||
                        (tmdbFirst && tmdbCanContribute && com.reelix.app.data.tmdb.TmdbClient.isCacheLoaded))
                }
                LaunchedEffect(
                    displayDetail.item.id,
                    displayDetail.item.backdrop,
                    displayDetail.item.poster,
                    settings.tmdbApiKey,
                    settings.tmdbEnabled,
                    settings.tmdbLanguage,
                    settings.artworkChannels,
                    settings.artworkOrder,
                ) {
                    val item = displayDetail.item
                    val hasWide = !item.backdrop.isNullOrBlank() ||
                        !item.backdropRes.isNullOrBlank()
                    if (hasWide) { detailScraped = null; detailArtworkReady = true; return@LaunchedEffect }
                    if (!canScrapeBackdrop) {
                        detailArtworkReady = true
                        return@LaunchedEffect
                    }
                    // 仅当 TMDB 是首选来源时才等本地缓存；否则直接按用户顺序请求。
                    if (tmdbFirst && tmdbCanContribute) {
                        com.reelix.app.data.tmdb.TmdbClient.awaitCache(context)
                        detailScraped = com.reelix.app.data.tmdb.TmdbClient.cachedMatch(
                            item.title, item.year, settings.tmdbLanguage)?.backdropUrlW1280
                    } else {
                        detailScraped = null
                    }
                    detailArtworkReady = true
                    if (!detailScraped.isNullOrBlank()) return@LaunchedEffect
                    detailScraped = runCatching {
                        com.reelix.app.data.tmdb.ArtworkResolver.resolve(
                            tmdbKey = settings.tmdbApiKey,
                            tmdbEnabled = settings.tmdbEnabled,
                            title = item.title,
                            year = item.year,
                            language = settings.tmdbLanguage,
                            needOverview = false,
                            channels = settings.artworkChannels,
                            order = settings.artworkOrder,
                        ).backdrop
                    }.getOrNull()
                }
                LaunchedEffect(displayDetail.item.id, detailScraped, displayDetail.item.backdrop) {
                    val backdrop = detailScraped?.takeIf { it.isNotBlank() }
                        ?: displayDetail.item.backdrop?.takeIf { it.isNotBlank() }
                        ?: return@LaunchedEffect
                    runCatching {
                        progressRepo.updateBackdropIfEmpty(displayDetail.item.id, backdrop)
                    }.onFailure {
                        android.util.Log.w("ReelixArtwork", "History backdrop update skipped", it)
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        DetailView(
                            context = ctx,
                            theme = nativeTheme,
                            onExitUp = { topBarViewRef?.focusCurrent() },
                            onPlay = { route, episode ->
                        // ★★ 必须优先用 onlineDetail（异步加载到的真实详情）。
                        //
                        // 踩过的坑（2026-11-02）：原来这里用 buildDetail()，
                        // 而 buildDetail 对在线源固定返回 routes = emptyList()，
                        // 结果播放页的 episode 初始化成 null：
                        //   · LaunchedEffect(routeIndex, episode?.index) 直接 return
                        //   · resolveEpisode 从来不执行
                        //   · 表现就是「进播放页黑屏卡在 00:00，没有任何日志和报错」
                        //
                        // 片库场景 onlineDetail 为 null，退回 buildDetail 是对的。
                        val baseDetail = onlineDetail
                            ?: buildDetail(currentDetail, detailSource, libraryRepo,
                                (libraryState as? LibraryRepository.LibraryState.Ready)?.entries)
                        // Manual online artwork is part of the visible item; playback must not
                        // fall back to the raw source snapshot and lose the chosen backdrop.
                        val playbackItem = libraryRepo.effectiveArtwork(baseDetail.item)
                        // ★ TMDB 刮到的横图必须写回 item（2026-11-07）。
                        //
                        //   否则播放时 ProgressRepository.save 存下的
                        //   backdropUrl 永远是空的，历史里那一条就只剩竖版海报 ——
                        //   HomeView 判断没有横图就退回"小海报 + 前景竖图"，
                        //   表现就是"明明详情页有大图，Hero 还是小海报"。
                        val detail = if (
                            playbackItem.backdrop.isNullOrBlank() &&
                            !detailScraped.isNullOrBlank()
                        ) {
                            baseDetail.copy(
                                item = playbackItem.copy(backdrop = detailScraped)
                            )
                        } else {
                            baseDetail.copy(item = playbackItem)
                        }

                        // A season selection supplies a subset of the library route; preserve it for auto-next.
                        val playbackDetail = if (detail.item.fromLibrary) detail.copy(routes = listOf(route)) else detail
                        val rIdx = playbackDetail.routes.indexOf(route).coerceAtLeast(0)
                        val routeAt = playbackDetail.routes.getOrNull(rIdx) ?: route
                        val eIdx = routeAt.episodes.indexOf(episode).coerceAtLeast(0)

                        android.util.Log.i(
                            "ReelixPlay",
                            "进播放页: routes=${detail.routes.size} rIdx=$rIdx " +
                                "eps=${routeAt.episodes.size} eIdx=$eIdx"
                        )

                        if (detail.item.fromLibrary) scope.launch {
                            runCatching { libraryRepo.setPreferredResource(detail.item.id, episode.playId) }
                        }
                        playing = PlayingTarget(
                            detail = playbackDetail,
                            routeIndex = rIdx,
                            episodeIndex = eIdx,
                        )
                            },
                            onOpenItem = {
                                detailItem = it
                                onlineDetail = null
                                onlineDetailFromCache = false
                            },
                            onChangeArtwork = { changeLibraryArtwork(it) },
                            onDeleteEpisode = { episode ->
                                val entry = (libraryState as? LibraryRepository.LibraryState.Ready)
                                    ?.entries?.firstOrNull { candidate ->
                                        candidate.episodes.any { it.playId == episode.playId }
                                    }
                                if (entry != null) {
                                    // Long-press + trash + OK is already two deliberate steps,
                                    // so a single file deletes without another dialog.
                                    // Remove locally first: the drive round-trip can take seconds and the
                                    // grid must react immediately. A later refresh restores anything the
                                    // drive refused.
                                    scope.launch {
                                        // Remove on screen first; the drive round-trip and cache
                                        // write happen afterwards so nothing waits on the network.
                                        libraryRepo.removePathsFromSnapshot(setOf(episode.playId))
                                        advanceHistoryPastDeleted(entry, episode)
                                        libraryRepo.persistSnapshotCache()
                                        val result = libraryRepo.deleteFile(episode)
                                        val failure = result.exceptionOrNull()
                                        if (failure != null) {
                                            android.util.Log.w("ReelixDelete",
                                                "remote delete failed, item will reappear after rescan " +
                                                    "source=${episode.sourceKey.ifBlank { entry.item.sourceKey }} " +
                                                    "path=${episode.playId}", failure)
                                            Toast.makeText(context, R.string.library_delete_drive_failed,
                                                Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                        ).also { detailViewRef = it }
                    },
                    onRelease = { view -> if (detailViewRef === view) detailViewRef = null },
                    update = { view ->
                        // 先推 TMDB 横图，再 render —— render 内部会按新图重绑背景
                        view.applyScrapedBackdrop(detailScraped)
                        view.setSourceLabel(if (displayDetail.item.fromLibrary)
                            context.getString(R.string.settings_cat_library) + " · " + displayDetail.item.sourceId
                        else (sourceState as? SourceManager.State.Ready)?.sites?.firstOrNull {
                            it.site.key == displayDetail.item.sourceKey }?.displayName
                            ?: cachedSiteKeys.firstOrNull { it.key == displayDetail.item.sourceKey }?.name)
                        view.render(
                            detail = displayDetail,
                            loadingRoutes = detailSource == DetailSource.Online && onlineDetailLoading,
                            finished = savedProgress?.finished == true,
                            deferBackdrop = !detailArtworkReady,
                            progressRecord = savedProgress,
                        )
                    },
                )
            }

            // ---------- ③ 首页 / 片库 ----------
            else -> {
                val contentEntry = remember(dest) { androidx.compose.ui.focus.FocusRequester() }
                // ★ 首页不要包 focusGroup：焦点组在“焦点不在组内”时会自行把
                //   首个子节点拉进来，实机表现为 Hero 被自动滚动或焦点被拖回顶部。
                //   片库/搜索仍保留原有分组，避免改坏既有的 D-pad 走位。
                Box(
                    Modifier.fillMaxSize().focusRequester(contentEntry).then(
                        if (dest == TopDest.HOME) Modifier else Modifier.focusGroup()
                    ).then(if (nativeTheme?.id == NativeThemeId.SIDEBAR && dest != TopDest.HOME)
                        Modifier.padding(start = 56.5.dp) else Modifier)
                ) {
                when (dest) {
                    TopDest.HOME -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                HomeView(
                                    context = ctx,
                                    theme = nativeTheme,
                                    browse = homeBrowse,
                                    initialColumns = settings.appearance.posterColumns,
                                    onPlay = { entry ->
                                        homePosterIndex[0] = homeViewRef?.saveFocusIndex() ?: 0
                                        homeReturnRegion = homeViewRef?.focusRegion() ?: "hero"
                                        detailOrigin = TopDest.HOME
                                        val item = entry.toMediaItem()
                                        detailItem = item
                                        detailSource = if (!item.fromLibrary && item.sourceKey.isNotBlank()) {
                                            DetailSource.Online
                                        } else {
                                            DetailSource.Library
                                        }
                                    },
                                    onOpenDetail = { entry ->
                                        homePosterIndex[0] = homeViewRef?.saveFocusIndex() ?: 0
                                        homeReturnRegion = homeViewRef?.focusRegion() ?: "hero"
                                        detailOrigin = TopDest.HOME
                                        val item = entry.toMediaItem()
                                        detailItem = item
                                        detailSource = if (!item.fromLibrary && item.sourceKey.isNotBlank()) {
                                            DetailSource.Online
                                        } else {
                                            DetailSource.Library
                                        }
                                    },
                                    onDelete = { mediaId, completed ->
                                        scope.launch {
                                            val deleted = runCatching { progressRepo.deleteAndGet(mediaId) }
                                            completed(deleted.isSuccess)
                                            deleted.onSuccess { entry ->
                                                runCatching {
                                                    evictUnusedHistoryArtwork(
                                                        entry?.posterUrl,
                                                        entry?.backdropUrl,
                                                        entry?.title,
                                                    )
                                                }
                                            }.onFailure { android.util.Log.e("ReelixHistory", "Delete failed", it) }
                                        }
                                    },
                                    onGoOnline = { dest = TopDest.ONLINE },
                                    onGoLibrary = { dest = TopDest.LIBRARY },
                                    onOpenLibraryItem = { item ->
                                        homePosterIndex[0] = homeViewRef?.saveFocusIndex() ?: 0
                                        homeReturnRegion = homeViewRef?.focusRegion() ?: "hero"
                                        detailOrigin = TopDest.HOME
                                        detailItem = item
                                        detailSource = DetailSource.Library
                                        onlineDetail = null
                                        onlineDetailFromCache = false
                                    },
                                    onExitTop = { topBarViewRef?.focusCurrent() },
                                ).also { homeViewRef = it }
                            },
                            update = { view ->
                                view.setAppearance(settings.appearance)
                                view.render(history.map { it.withEffectiveArtwork(libraryRepo) },
                                    initialLoading = !firstHistoryReady, collection = homeCollection)
                            },
                        )
                    }

                    TopDest.ONLINE -> {
                        val liveSites = (sourceState as? SourceManager.State.Ready)?.sites.orEmpty()
                        val snapshotSites = cachedSiteKeys.map { snapshot ->
                            SourceManager.SiteEntry(
                                site = com.reelix.app.data.source.TvBoxSite(
                                    key = snapshot.key,
                                    name = snapshot.name,
                                    type = snapshot.type,
                                    api = snapshot.api,
                                    ext = snapshot.ext,
                                    searchable = snapshot.searchable,
                                ),
                                spider = null,
                                fromJar = snapshot.fromJar,
                            )
                        }
                        // Snapshot order stays authoritative for the remembered index.
                        // Once the live list exists it must win: snapshot entries carry
                        // spider=null and cannot fetch anything.
                        val sites = liveSites.ifEmpty { snapshotSites }
                        val currentSiteIndex = sites.indexOfFirst { it.site.key == onlineSiteKey }
                            .takeIf { it >= 0 } ?: onlineSiteIndex
                        val currentSites = rememberUpdatedState(sites)
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                OnlineView(
                                    context = ctx,
                                    initialColumns = settings.appearance.posterColumns,
                                    onSelectSite = { idx ->
                                        // ★ 只更新“选中哪一站”，不要在这里把状态重置成 loading。
                                        //   真正的快照替换与后台刷新交给按 siteKey 的 effect。
                                        val activeSites = currentSites.value
                                        val chosenKey = activeSites.getOrNull(idx)?.site?.key
                                            ?: cachedSiteKeys.getOrNull(idx)?.key.orEmpty()
                                        if (chosenKey.isNotBlank() && chosenKey != onlineSiteKey) {
                                            onlineHomeJob?.cancel()
                                            onlineCategoryJob?.cancel()
                                            // The category id belongs to the previous site; never reuse it.
                                            onlineCategoryId = null
                                            onlineCategory = null
                                            onlineCategorySiteKey = ""
                                            onlineError = null
                                            onlineCache.putSites(
                                                activeSites.map { entry ->
                                                    OnlineSiteSnapshot(
                                                        key = entry.site.key,
                                                        name = entry.site.name,
                                                        type = entry.site.type,
                                                        api = entry.site.api,
                                                        ext = entry.site.ext,
                                                        searchable = entry.site.searchable,
                                                        fromJar = entry.fromJar,
                                                    )
                                                },
                                                selectedKey = chosenKey,
                                                selectedCategory = null,
                                            )
                                            val restored = onlineCache.cachedHomeFor(chosenKey)?.data
                                            val restoredCategoryId = restored?.categories?.firstOrNull()?.id
                                            val restoredCategory = restoredCategoryId
                                                ?.let { onlineCache.cachedCategory(chosenKey, it)?.data }
                                            if (restored != null) {
                                                onlineHome = restored
                                                onlineCategoryId = restoredCategoryId
                                                onlineCategory = restoredCategory
                                                onlineCategorySiteKey = if (restoredCategory != null) chosenKey else ""
                                                onlineLoading = false
                                                onlineFromCache = true
                                            } else {
                                                onlineHome = null
                                                onlineLoading = true
                                            }
                                            onlineSiteIndex = idx
                                            onlineSiteKey = chosenKey
                                        }
                                    },
                                    onSelectCategory = { cid ->
                                        onlineCategoryJob?.cancel()
                                        onlineCategoryId = cid
                                        // 切分类先显示该分类的快照，后台请求随后覆盖。
                                        val siteKey = onlineSiteKey.ifBlank {
                                            cachedSiteKeys.getOrNull(onlineSiteIndex)?.key.orEmpty()
                                        }
                                        onlineCategory = onlineCache.cachedCategory(siteKey, cid)?.data
                                        onlineContentLoading = onlineCategory == null &&
                                            onlineCache.cachedHomeFor(siteKey)?.data?.recommendations?.isNotEmpty() != true &&
                                            onlineHome?.recommendations?.isNotEmpty() != true
                                    },
                                    onOpenItem = { item ->
                                        // 配置类条目和“切源”入口不能进详情页。
                                        // ★ 先按 ID 认，认不出再按标题认：有些源把 vod_id
                                        //   写成序号，只有标题是「清除嘟嘟Cookie」这种中文别名。
                                        val cfg = PanAuthItem.match(item.sourceId)
                                            ?: PanAuthItem.matchTitle(item.title)
                                        if (item.sourceId.startsWith("msearch:") ||
                                            item.title == "点我切源"
                                        ) {
                                            Toast.makeText(
                                                context,
                                                "这是切源入口，请在「搜索」里搜片名",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else if (cfg?.action == PanAuthItem.Action.CLEAR) {
                                            // 和 TVBox 一致：点「清除 X Cookie」直接清，不进二级页。
                                            val provider = cfg.provider
                                            if (provider != null) {
                                                com.reelix.app.data.pan.WebLoginHelper.clearCookie(context, provider)
                                                panAuthTick++
                                                Toast.makeText(
                                                    context,
                                                    "${provider.label} 凭据已清除",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "无法识别要清除哪个网盘",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        } else if (cfg != null) {
                                            pendingPanProvider = cfg.provider
                                            showPanAuth = true
                                        } else {
                                            detailOrigin = TopDest.ONLINE
                                            detailItem = item
                                            detailSource = DetailSource.Online
                                        }
                                    },
                                    onExitTop = { topBarViewRef?.focusCurrent() },
                                    onPosterIndexChange = { onlinePosterIndex[0] = it },
                                ).also { onlineViewRef = it }
                            },
                            update = { view ->
                                view.setPosterSize(settings.appearance.posterColumns)
                                view.render(
                                    OnlineViewState(
                                        sites = sites,
                                        currentSiteIndex = currentSiteIndex,
                                        home = onlineHome,
                                        category = onlineCategory,
                                        selectedCategoryId = onlineCategoryId,
                                        loading = onlineLoading,
                                        error = onlineError,
                                        refreshing = onlineCategoryLoading || onlineContentLoading,
                                        refreshingDetail = when {
                                            onlineContentLoading -> "正在更新分类内容…"
                                            onlineCategoryLoading -> "正在更新分类与推荐…"
                                            else -> null
                                        },
                                        loadingSites = sites.isEmpty(),
                                        posterMemory = onlineBrowse.getOrPut(
                                            "$onlineSiteKey|$onlineCategoryId",
                                        ) { com.reelix.app.ui.component.PosterGridMemory() },
                                    ),
                                )
                                if (restoreOnlinePosterFocus) {
                                    view.restoreFocus(onlinePosterIndex[0])
                                    restoreOnlinePosterFocus = false
                                }
                            },
                            onRelease = { view ->
                                if (onlineViewRef === view) onlineViewRef = null
                            },
                        )
                    }

                    TopDest.LIBRARY -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                LibraryView(
                                    context = ctx,
                                    initialColumns = settings.appearance.posterColumns,
                                    browse = libraryBrowse,
                                    onOpenItem = { item ->
                                        libraryPosterIndex[0] = libraryViewRef?.saveFocusIndex() ?: 0
                                        detailOrigin = TopDest.LIBRARY
                                        detailItem = item
                                        detailSource = DetailSource.Library
                                    },
                                    onRefresh = { refreshLibrary() },
                                    onManageDirectories = { manageLibraryDirectories() },
                                    onExitTop = { topBarViewRef?.focusCurrent() },
                                    onDeleteEntry = { entry ->
                                        val nonVideo = (entry.files.size - entry.episodes.size).coerceAtLeast(0)
                                        val totalBytes = entry.files.sumOf { it.sizeBytes }
                                        com.reelix.app.ui.screen.library.LibraryDialogs.deleteConfirm(
                                            context = context,
                                            heading = context.getString(
                                                if (entry.files.size > 1 || entry.directory.isNotBlank())
                                                    R.string.library_delete_heading_work
                                                else R.string.library_delete_heading_file),
                                            workName = entry.item.title,
                                            path = entry.directory.ifBlank { entry.files.firstOrNull()?.path.orEmpty() },
                                            stats = listOfNotNull(
                                                context.getString(R.string.library_delete_videos, entry.episodes.size),
                                                if (nonVideo > 0) context.getString(R.string.library_delete_others, nonVideo) else null,
                                                context.getString(R.string.library_delete_size,
                                                    com.reelix.app.ui.component.formatMediaSize(totalBytes)),
                                                context.getString(R.string.library_delete_source,
                                                    com.reelix.app.ui.screen.library.librarySourceLabel(entry.item.sourceKey)),
                                            ),
                                            twice = entry.files.size > 1 || entry.directory.isNotBlank(),
                                        ) {
                                            // Same optimistic pattern as single episodes: hide it now,
                                            // talk to the drive in the background.
                                            val ids = entry.episodes.mapNotNull { ep ->
                                                (libraryState as? LibraryRepository.LibraryState.Ready)
                                                    ?.entries?.firstOrNull { it.episodes.any { e -> e.playId == ep.playId } }?.item?.id
                                            }.toSet() + entry.item.id
                                            ids.forEach { id -> runCatching { progressRepo.delete(id) } }
                                            libraryRepo.forgetDeleted(entry.item.id, entry.directory)
                                            libraryRepo.removePathsFromSnapshot(entry.files.map { it.path }.toSet())
                                            scope.launch {
                                                libraryRepo.persistSnapshotCache()
                                                val result = libraryRepo.deleteEntry(entry)
                                                val failure = result.exceptionOrNull()
                                                if (failure != null) {
                                                    android.util.Log.w("ReelixDelete",
                                                        "remote delete failed, item will reappear after rescan " +
                                                            "source=${entry.item.sourceKey} " +
                                                            "path=${entry.directory.ifBlank { entry.files.firstOrNull()?.path.orEmpty() }}",
                                                        failure)
                                                    Toast.makeText(context, R.string.library_delete_drive_failed,
                                                        Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            null
                                        }
                                    },
                                ).also { libraryViewRef = it }
                            },
                            update = { view ->
                                view.setPosterSize(settings.appearance.posterColumns)
                                view.render(
                                    state = libraryState,
                                    stats = libraryStats,
                                    progress = progressMap,
                                    posterRevision = posterRevision,
                                    refreshMessage = libraryScanProgress,
                                    artworkMessage = libraryArtworkProgress,
                                    refreshResult = libraryRefreshResult,
                                )
                                if (restoreLibraryPosterFocus) {
                                    view.restoreFocus(libraryPosterIndex[0])
                                    restoreLibraryPosterFocus = false
                                }
                            },
                        )
                    }

                    TopDest.SEARCH -> {
                        // 搜索对象 = 片库里的片子。
                        // 2026-11-09：原来片库为空时会退回内置演示条目（9 部假电影），
                        // 现在演示数据已整体移除，空就是空。
                        val searchable = remember(libraryState) {
                            (libraryState as? LibraryRepository.LibraryState.Ready)
                                ?.entries?.map { it.item }.orEmpty()
                        }
                        val searchSiteOrder = remember(sourceState) {
                            (sourceState as? SourceManager.State.Ready)?.sites
                                ?.map { it.site.key }
                                .orEmpty()
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SearchView(
                                    context = ctx,
                                    onSearchOnline = { kw ->
                                        val sites = (sourceState as? SourceManager.State.Ready)
                                            ?.sites.orEmpty()
                                        if (sites.isNotEmpty() && kw.isNotBlank()) {
                                            onlineSearch = OnlineSearchUiState(running = true, keyword = kw)
                                            scope.launch {
                                                val homeKey = onlineSiteKey
                                                val res = OnlineSearch.search(sites, kw, homeKey) { partial ->
                                                    onlineSearch = onlineSearch.copy(
                                                        results = onlineSearch.results + partial
                                                    )
                                                }
                                                onlineSearch = OnlineSearchUiState(
                                                    running = false,
                                                    results = res,
                                                    keyword = kw,
                                                )
                                            }
                                        }
                                    },
                                    onOpenItem = { item ->
                                        searchPosterIndex[0] = searchViewRef?.saveFocusIndex() ?: 0
                                        detailOrigin = TopDest.SEARCH
                                        detailItem = item
                                        detailSource = when {
                                            item.fromLibrary -> DetailSource.Library
                                            item.sourceKey.isNotBlank() -> DetailSource.Online
                                            else -> DetailSource.Mock
                                        }
                                    },
                                    onExitTop = { topBarViewRef?.focusCurrent() },
                                    session = searchSession,
                                ).also { searchViewRef = it }
                            },
                            update = { view ->
                                view.setPosterSize(settings.appearance.posterColumns)
                                view.render(
                                    candidates = searchable,
                                    online = onlineSearch,
                                    siteOrder = searchSiteOrder,
                                )
                                if (restoreSearchPosterFocus) {
                                    view.restoreFocus(searchPosterIndex[0])
                                    restoreSearchPosterFocus = false
                                }
                            },
                        )
                    }
                }

                } // content focus group

            }
        }
        if (playing == null && !showSettings && !showPanLogin && !showPanAuth &&
            com.reelix.app.ui.theme.layout.showTopNavigation(detailItem != null, nativeTheme?.id)) {
            val chromeShape = remember {
                GenericShape { size, _ ->
                    addRect(Rect(0f, 0f, size.width, (size.width * 112f / 1920f).coerceAtMost(size.height)))
                    addRect(Rect(0f, 0f, size.width * 113f / 1920f, size.height))
                }
            }
            AndroidView(
                modifier = Modifier.align(Alignment.TopStart).then(if (nativeTheme?.id == NativeThemeId.SIDEBAR)
                    Modifier.fillMaxSize().graphicsLayer { clip = true; shape = chromeShape } else Modifier.fillMaxWidth()),
                factory = { ctx ->
                    TopBarView(ctx,
                        onSelect = { destination ->
                            detailItem = null
                            detailSource = null
                            detailOrigin = null
                            onlineDetail = null
                            onlineDetailFromCache = false
                            restoreHomePosterFocus = false
                            homeReturnRegion = "hero"
                            restoreLibraryPosterFocus = false
                            restoreSearchPosterFocus = false
                            restoreOnlinePosterFocus = false
                            dest = destination
                        },
                        onEnterContent = {
                            if (detailItem != null) detailViewRef?.focusFirst() ?: false else when (dest) {
                                TopDest.HOME -> homeViewRef?.focusFirst() ?: false
                                TopDest.LIBRARY -> libraryViewRef?.focusFirst() ?: false
                                TopDest.SEARCH -> searchViewRef?.focusFirst() ?: false
                                TopDest.ONLINE -> onlineViewRef?.focusFirst() ?: false
                            }
                        },
                        onOpenSettings = {
                            settingsReturnKey = topBarViewRef?.lastSettingsKey ?: "settings"
                            restoreSettingsButtonFocus = false
                            showSettings = true
                        },
                    ).also { topBarViewRef = it }
                },
                onRelease = { view -> if (topBarViewRef === view) topBarViewRef = null },
                update = { view ->
                    view.setTheme(nativeTheme)
                    view.setAppearance(settings.appearance)
                    view.setCurrent(dest)
                    view.focusSwitchEnabled = detailItem == null && playing == null && !showSettings
                    if (restoreSettingsButtonFocus) view.post {
                        if (topBarViewRef === view && view.isAttachedToWindow && !showSettings &&
                            !showPanLogin && !showPanAuth && playing == null &&
                            view.focusSettings(settingsReturnKey)) restoreSettingsButtonFocus = false
                    }
                },
            )
        }
    }
}

/** 详情页数据从哪来 */
private enum class DetailSource { Mock, Library, Online }

/** 播放目标：哪一部、哪条线路、哪一集 */
private data class PlayingTarget(
    val detail: MediaDetail,
    val routeIndex: Int,
    val episodeIndex: Int,
)

@Composable
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.placeholder_pending, name),
            style = RxType.Body,
            color = RxColors.TextTertiary
        )
    }
}

/**
 * 按来源构建详情。
 *
 * 片库条目：走 alist，剧集列表来自真实文件名解析，
 * 播放时由 PlayerScreen 回调 resolveEpisode 现取直链。
 * 演示条目：用打包的内置图 + 公开测试流。
 */
private fun buildDetail(
    item: MediaItem,
    source: DetailSource?,
    repo: LibraryRepository,
    libraryEntries: List<com.reelix.app.data.alist.AlistLibraryBuilder.LibraryEntry>? = null,
): MediaDetail {
    return when (source) {
        DetailSource.Online -> {
            // 在线源条目：item.sourceId 是站点内的 vod_id，
            // 详情要靠对应站点去取。这里先用 item 本身兜底，
            // 真实详情在 OnlineDetailLoader 里异步补。
            MediaDetail(
                item = item,
                routes = emptyList(),
                related = emptyList(),
            )
        }
        DetailSource.Library -> {
            // 片库条目：直接用 item 本身的信息，剧集在 load 时已解析好
            val entries = libraryEntries ?: (repo.state.value as? LibraryRepository.LibraryState.Ready)?.entries
            val entry = com.reelix.app.data.alist.AlistLibraryBuilder.find(entries.orEmpty(), item)
            MediaDetail(
                item = entry?.item?.copy(matchedResourcePath = item.matchedResourcePath, matchedResourceName = item.matchedResourceName) ?: item,
                routes = entry?.let { com.reelix.app.data.alist.AlistLibraryBuilder.toPlayRoutes(it, item.sourceId) }.orEmpty(),
                related = entries.orEmpty()
                    .filter { it.item.id != item.id }
                    .take(8)
                    .map { it.item },
            )
        }
        // 2026-11-09：既不来自在线源、也不来自片库（理论上不该发生）。
        // 原来这里退回 buildMockDetail() 造一份带测试流的假详情；
        // 演示数据移除后，只返回 item 本身、没有线路 —— 详情页会显示"无剧集"。
        else -> MediaDetail(item = item, routes = emptyList(), related = emptyList())
    }
}


/** 片库线路名（内部标识，同上） */
private object LibraryRouteNames {
    const val LIBRARY = "片库"
}

/**
 * 把扫描提示文案从资源里读出来，交给仓库层。
 *
 * 仓库层（`LibraryRepository`）故意不持 Context —— 它是纯逻辑，
 * 单测里可以直接 new。文案在 MainActivity 这一层注入，两边都干净。
 */
private fun libraryLoadingMessages(context: android.content.Context) =
    LibraryRepository.LoadingMessages(
        loggingIn = context.getString(R.string.library_logging_in),
        scanning = context.getString(R.string.library_scanning),
        building = context.getString(R.string.library_building),
        noVideos = context.getString(R.string.library_no_videos),
        unknownError = context.getString(R.string.library_unknown_error),
        scanned = { dirs, current ->
            context.getString(R.string.library_scanned, dirs, current.takeLast(40))
        },
    )

