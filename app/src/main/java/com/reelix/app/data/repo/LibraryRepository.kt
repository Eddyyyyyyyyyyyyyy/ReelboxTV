package com.reelix.app.data.repo

import com.reelix.app.data.alist.AlistClient
import com.reelix.app.data.alist.AlistLibraryBuilder
import com.reelix.app.data.alist.AlistLibraryBuilder.LibraryEntry
import com.reelix.app.data.alist.LibraryDataSource
import com.reelix.app.data.alist.LibrarySnapshotStore
import com.reelix.app.data.alist.LibraryLoadAction
import com.reelix.app.data.alist.libraryLoadAction
import com.reelix.app.data.alist.LibraryScan
import com.reelix.app.data.alist.withActivity
import com.reelix.app.data.alist.DirectoryMode
import com.reelix.app.data.alist.LibraryCustomizationStore
import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.PlayRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 片库仓库 —— 连接 Alist 和 UI 的中间层。
 *
 * 职责：
 *  1. 登录 alist + 扫描 + 构建媒体库（一次，缓存到内存）
 *  2. 为某一集取播放直链（每次都现取，因为 115 直链带时效签名）
 *  3. 对外暴露 StateFlow，UI 直接订阅
 *
 * 为什么直链不缓存：
 *  `raw_url` 里带 `t=<过期时间戳>` 和签名，几分钟就失效。
 *  缓存了会导致"点播放转圈然后失败"，这个坑很深。
 */
class LibraryRepository(
    sources: Map<String, LibraryDataSource>,
    private val snapshotStore: LibrarySnapshotStore? = null,
) {
    /** Configured sources, scanned serially. Single-source setups keep the old behaviour. */
    private val clients: Map<String, LibraryDataSource> = sources.toMap()
    private val client: LibraryDataSource = clients.values.firstOrNull()
        ?: error("LibraryRepository requires at least one source")
    /** Source of the entry that owns each playId, so playback can pick the right client. */
    private val entrySource = mutableMapOf<String, String>()
    private val loadMutex = Mutex()
    /** Guards only the short snapshot swap, so deletion never waits on a long scan. */
    private val publishLock = Any()
    private val loginMutex = Mutex()
    private var credentials: Pair<String, String> = "" to ""
    private var credentialsBySource: Map<String, Pair<String, String>> = emptyMap()
    private var addressesBySource: Map<String, String> = emptyMap()
    private var snapshot: LibraryScan? = null
    /** Cache writers per source, captured on load so deletion can persist without a rescan. */
    private val cacheWriters = mutableMapOf<String, (LibraryScan) -> Unit>()
    /** One customization store per source; artwork and rules must never leak across drives. */
    private val customizations = mutableMapOf<String, LibraryCustomizationStore>()
    private var sourceFallback = ""
    private var automaticCheckDone = false
    fun resetAutomaticCheck() { automaticCheckDone = false }
    private val automaticCovers = mutableMapOf<String, com.reelix.app.data.image.LibraryArtworkRecord>()
    val currentScan: LibraryScan? get() = snapshot
    fun directoryRules(): Map<String, DirectoryMode> =
        customizations.values.flatMap { it.rules().entries }.associate { it.key to it.value }

    private fun customizationFor(id: String): LibraryCustomizationStore? {
        val sourceKey = entrySource[id] ?: return customizations["alist"] ?: customizations.values.firstOrNull()
        return customizations[sourceKey] ?: customizations.values.firstOrNull()
    }

    private fun publish(scan: LibraryScan) {
        // Called from both the scan coroutine and the deletion path; keep the swap atomic.
        synchronized(publishLock) { publishLocked(scan) }
    }

    private fun publishLocked(scan: LibraryScan) {
        val enriched = if (sourceFallback.isBlank()) scan else scan.copy(
            videos = scan.videos.map { if (it.sourceIdentity.isBlank()) it.copy(sourceIdentity = sourceFallback) else it },
            archivedVideos = scan.archivedVideos.map { if (it.sourceIdentity.isBlank()) it.copy(sourceIdentity = sourceFallback) else it })
        val result = AlistLibraryBuilder.build(enriched.videos, directoryRules())
        val entries = result.items.map { it.copy(item = applyArtwork(it.item)) }
        snapshot = enriched
        entriesById = entries.associateBy { it.item.id }
        entrySource.clear()
        entries.forEach { entry ->
            val key = entry.files.firstOrNull { it.sourceKey.isNotBlank() }?.sourceKey
                ?: entry.item.sourceKey.takeIf { it.isNotBlank() } ?: "alist"
            entrySource[entry.item.id] = key
        }
        _stats.value = AlistLibraryBuilder.stats(entries)
        _state.value = LibraryState.Ready(entries)
    }
    suspend fun setDirectoryMode(path: String, mode: DirectoryMode?) = withContext(Dispatchers.IO) { loadMutex.withLock {
        val owner = customizations.entries.firstOrNull { (_, store) -> path in store.rules() }?.value
            ?: customizations["alist"] ?: customizations.values.firstOrNull()
        checkNotNull(owner).setMode(path, mode)
        snapshot?.let(::publish)
    } }
    fun artworkBinding(id: String) = customizationFor(id)?.binding(id)
    fun coverOnly(id: String) = customizationFor(id)?.coverOnly(id) == true
    fun metadata(id: String) = customizationFor(id)?.metadata(id)
    fun automaticArtwork(id: String) = automaticCovers[id]
    suspend fun setMetadata(id: String, binding: com.reelix.app.data.image.LibraryWorkIdentity?,
        record: com.reelix.app.data.image.LibraryWorkMetadata) = withContext(Dispatchers.IO) { loadMutex.withLock {
        // 手动路径：资料跟随用户选中的作品，不再要求与海报绑定身份一致。
        if (coverOnly(id)) return@withLock
        coroutineContext.ensureActive()
        checkNotNull(customizationFor(id)).setMetadata(id, record)
        snapshot?.let(::publish)
    } }
    fun artworkTaskKey(id: String): String = "${checkNotNull(customizationFor(id)).artworkAccountKey}:$id"
    suspend fun setPreferredResource(id: String, path: String) = withContext(Dispatchers.IO) { loadMutex.withLock {
        if (entriesById[id]?.files?.none { it.path == path } != false) return@withLock
        customizationFor(id)?.setResourceChoice(id, path)
        snapshot?.let(::publish)
    } }
    suspend fun setCover(id: String, poster: String, backdrop: String?, identity: com.reelix.app.data.image.LibraryWorkIdentity? = null,
        ticket: com.reelix.app.data.image.ArtworkCommitGate.Ticket? = null): Boolean = withContext(Dispatchers.IO) {
        val store = checkNotNull(customizationFor(id))
        val committed = store.saveCover(id, poster, backdrop, identity, ticket)
        if (committed) loadMutex.withLock { snapshot?.let(::publish) }
        committed
    }

    fun effectiveArtwork(item: MediaItem): MediaItem =
        if (item.fromLibrary || item.sourceKey in LIBRARY_SOURCE_KEYS) applyArtwork(item)
        else com.reelix.app.data.image.OnlineArtworkOverrides.apply(item)

    private fun applyArtwork(item: MediaItem): MediaItem {
        val cover = automaticCovers[item.id]
        val automatic = if (cover == null) item else item.copy(
            // Automatic artwork only fills gaps; source/manual artwork keeps priority.
            poster = item.poster?.takeIf { it.isNotBlank() } ?: cover.poster.takeIf { it.isNotBlank() },
            backdrop = item.backdrop?.takeIf { it.isNotBlank() } ?: cover.backdrop,
        )
        return customizationFor(item.id)?.apply(automatic) ?: automatic
    }
    suspend fun setAutomaticCover(id: String, cover: com.reelix.app.data.image.LibraryArtworkRecord) = withContext(Dispatchers.IO) { loadMutex.withLock {
        automaticCovers[id] = cover
        val state = _state.value as? LibraryState.Ready ?: return@withLock
        val entries = state.entries.map { if (it.item.id == id) it.copy(item = applyArtwork(it.item)) else it }
        entriesById = entries.associateBy { it.item.id }
        _state.value = LibraryState.Ready(entries)
    } }

    private suspend fun ensureLogin() = loginMutex.withLock {
        if (!client.isLoggedIn) client.login(credentials.first, credentials.second).getOrThrow()
    }

    private suspend fun ensureLogin(sourceKey: String) = loginMutex.withLock {
        val source = clients[sourceKey] ?: clients.values.first()
        val pair = credentialsBySource[sourceKey] ?: credentials
        if (!source.isLoggedIn) source.login(pair.first, pair.second).getOrThrow()
    }

    /** Merge per-source scans without losing failures or the archived list. */
    private fun mergeScans(scans: List<Pair<String, LibraryScan>>): LibraryScan {
        val videos = scans.flatMap { it.second.videos }
        val archived = scans.flatMap { it.second.archivedVideos }
        return LibraryScan(
            videos = videos.distinctBy { it.sourceIdentity to it.path },
            directories = scans.flatMap { it.second.directories }.distinct().sorted(),
            failures = scans.flatMap { it.second.failures },
            archivedVideos = archived.distinctBy { it.sourceIdentity to it.path },
        )
    }

    /** 片库状态 */
    sealed interface LibraryState {
        data object Idle : LibraryState
        data class Loading(val message: String) : LibraryState
        data class Ready(val entries: List<LibraryEntry>) : LibraryState
        data class Error(val message: String) : LibraryState
    }

    private val _state = MutableStateFlow<LibraryState>(LibraryState.Idle)
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _scanProgress = MutableStateFlow<String?>(null)
    val scanProgress: StateFlow<String?> = _scanProgress.asStateFlow()
    data class ScanStatus(val started: Boolean = false, val running: Boolean = false,
        val directories: Int = 0, val failures: Int = 0, val error: String? = null)
    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val _stats = MutableStateFlow(AlistLibraryBuilder.stats(emptyList()))
    val stats: StateFlow<AlistLibraryBuilder.Stats> = _stats.asStateFlow()

    /** 已构建的条目，按 id 索引，详情页用 */
    private var entriesById: Map<String, LibraryEntry> = emptyMap()

    /**
     * 登录 + 扫描 + 构建。可重复调用（会重新扫描）。
     *
     * @param root 扫描起始目录，默认 "/" 扫全部挂载点
     */
    /**
     * 加载片库。
     *
     * ## 缓存策略（2026-11-02）
     *
     * ```
     * force = false（默认，进页面时）
     *   有缓存 -> 直接读缓存，秒开
     *   没缓存 -> 走完整扫描
     *
     * force = true（用户手动点刷新）
     *   不走缓存，完整扫描，扫完写入缓存
     * ```
     *
     * 用户要求：「不要每次打开都去读一遍，我手动刷新的时候才刷新，这样能秒开」。
     *
     * ## 为什么缓存存的是 AlistVideo 而不是 LibraryEntry
     *
     * AlistVideo 保存文件与来源元数据，序列化不依赖 UI 模型。
     * build() 是纯计算，180 部片实测约 50ms，重新跑完全无感。
     */
    suspend fun load(
        username: String,
        password: String,
        root: String = "/",
        messages: LoadingMessages = LoadingMessages.default(),
        /** true = 忽略缓存，强制重新扫描 */
        force: Boolean = false,
        /** 缓存需要 Context；为 null 时不使用缓存 */
        cacheContext: android.content.Context? = null,
        /** Alist 地址，用于判断缓存是否有效 */
        serverUrl: String = "",
        /** Per-source credentials; a missing key falls back to username/password. */
        sourceCredentials: Map<String, Pair<String, String>> = emptyMap(),
        /** Per-source server address, used for cache validation and customization keys. */
        sourceAddresses: Map<String, String> = emptyMap(),
        allowScan: Boolean = true,
        checkForUpdates: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) { loadMutex.withLock {
        credentials = username to password
        credentialsBySource = sourceCredentials
        addressesBySource = sourceAddresses
        sourceFallback = if (serverUrl.isBlank()) "" else "${com.reelix.app.data.alist.normalizeLibraryServer(serverUrl)}|$username"
        if (cacheContext != null && customizations.isEmpty()) {
            clients.keys.forEach { key ->
                val address = addressesBySource[key] ?: serverUrl
                val pair = credentialsBySource[key] ?: credentials
                customizations[key] = LibraryCustomizationStore(cacheContext, address, pair.first, root)
            }
        }
        var previous = _state.value as? LibraryState.Ready
        fun loading(message: String) {
            _scanProgress.value = message
            if (previous == null) _state.value = LibraryState.Loading(message)
        }
        fun storeFor(sourceKey: String): LibrarySnapshotStore? = snapshotStore ?: cacheContext?.let { ctx ->
            val address = addressesBySource[sourceKey] ?: serverUrl
            object : LibrarySnapshotStore {
                override fun read() = com.reelix.app.data.alist.LibraryCache.load(
                    ctx, root, address, username, sourceKey)?.videos
                override fun write(videos: List<com.reelix.app.data.alist.AlistVideo>) =
                    com.reelix.app.data.alist.LibraryCache.save(ctx, videos, root, address, username,
                        sourceKey = sourceKey)
                override fun readSnapshot() = com.reelix.app.data.alist.LibraryCache.load(
                    ctx, root, address, username, sourceKey)?.scan
                override fun writeSnapshot(snapshot: LibraryScan) =
                    com.reelix.app.data.alist.LibraryCache.save(ctx, snapshot.videos, root, address,
                        username, snapshot, sourceKey)
            }
        }
        val store = storeFor("alist")
        try {
            val checkNow = checkForUpdates && allowScan && !automaticCheckDone
            if (!force && previous != null && !checkNow) return@withLock Result.success(Unit)
            // ---- ① 先试缓存 ----
            val stores = clients.keys.associateWith { storeFor(it) }
        cacheWriters.clear()
        stores.forEach { (key, value) -> if (value != null) cacheWriters[key] = { scan -> value.writeSnapshot(scan) } }
            val cachedScans = if (!force) stores.mapNotNull { (key, value) ->
                value?.readSnapshot()?.let { key to it }
            } else emptyList()
            val cached = cachedScans.takeIf { it.isNotEmpty() }?.let { scans ->
                mergeScans(scans)
            }
            when (libraryLoadAction(cached != null, allowScan, force)) {
                LibraryLoadAction.RESTORE -> {
                    // ★ Restore before any HTTP request; playback authenticates in resolveEpisode.
                    publish(cached!!.retainFailed(cached))
                    previous = _state.value as? LibraryState.Ready
                    if (!checkNow) return@withLock Result.success(Unit)
                }
                LibraryLoadAction.IDLE -> return@withLock Result.success(Unit)
                LibraryLoadAction.SCAN -> Unit
            }

            // ---- ② 完整扫描 ----
            automaticCheckDone = true
            _scanStatus.value = ScanStatus(started = true, running = true)
            // Sources scan one after another: OpenWrt-hosted Alist must not get N parallel crawls.
            val scannedBySource = mutableListOf<Pair<String, LibraryScan>>()
            clients.entries.forEachIndexed { index, (sourceKey, source) ->
                loading(messages.sourceProgress(index + 1, clients.size))
                ensureLogin(sourceKey)
                val scanned = source.scanSnapshot(root, forceRefresh = force || checkNow) { dirs, current ->
                    _scanStatus.value = _scanStatus.value.copy(directories = dirs)
                    loading(messages.scanned(dirs, current))
                }
                // Tag every file with its configured source so the merged scan stays resolvable.
                scannedBySource += sourceKey to scanned.copy(
                    videos = scanned.videos.map { it.copy(sourceKey = sourceKey) },
                    archivedVideos = scanned.archivedVideos.map { it.copy(sourceKey = sourceKey) },
                )
            }
            val scanned = mergeScans(scannedBySource)

            loading(messages.building)
            val previousScan = (snapshot ?: cachedScans.takeIf { it.isNotEmpty() }?.let(::mergeScans))?.let { scan -> scan.copy(
                videos = scan.videos.map { if (it.sourceIdentity.isBlank()) it.copy(sourceIdentity = sourceFallback) else it },
                archivedVideos = scan.archivedVideos.map { if (it.sourceIdentity.isBlank()) it.copy(sourceIdentity = sourceFallback) else it }) }
            val complete = scanned.withActivity(previousScan, System.currentTimeMillis()).retainFailed(previousScan)
            coroutineContext.ensureActive()
            scannedBySource.forEach { (key, value) ->
                stores[key]?.writeSnapshot(value)
            }
            publish(complete)
            _scanStatus.value = _scanStatus.value.copy(running = false, directories = scanned.directories.size, failures = scanned.failures.size)
            Result.success(Unit)
        } catch (e: CancellationException) {
            _state.value = previous ?: LibraryState.Idle
            throw e
        } catch (e: Exception) {
            _scanStatus.value = _scanStatus.value.copy(running = false, error = e.message)
            if (previous != null) {
                entriesById = previous.entries.associateBy { it.item.id }
                _stats.value = AlistLibraryBuilder.stats(previous.entries)
            }
            _state.value = previous ?: LibraryState.Error(e.message ?: messages.unknownError)
            Result.failure(e)
        } finally {
            _scanStatus.value = _scanStatus.value.copy(running = false)
            _scanProgress.value = null
        }
    } }

    /**
     * 扫描过程中的提示文案。
     *
     * 这些字符串会**显示在电视屏幕上**（片库页的 Loading 态），所以必须能本地化，
     * 但仓库层拿不到 Context —— 于是由调用方传进来（默认值见 [default]）。
     * 默认值是中文，保证测试和未传参的调用方照常工作。
     */
    data class LoadingMessages(
        val loggingIn: String = "正在登录片库…",
        val scanning: String = "正在扫描文件…",
        val building: String = "正在整理媒体库…",
        val noVideos: String = "没有找到视频文件",
        val unknownError: String = "未知错误",
        val sourceProgress: (index: Int, total: Int) -> String = { index, total ->
            if (total <= 1) "正在扫描文件…" else "正在扫描第 $index/$total 个源…"
        },
        val scanned: (dirs: Int, current: String) -> String = { dirs, current ->
            "已扫描 $dirs 个目录\n${current.takeLast(40)}"
        },
    ) {
        companion object {
            fun default(): LoadingMessages = LoadingMessages()
        }
    }

    /** 片库里所有条目（首页/片库页用） */
    fun allItems(): List<MediaItem> =
        (_state.value as? LibraryState.Ready)?.entries?.map { it.item }.orEmpty()

    /** 按 id 取详情 */
    suspend fun detail(id: String): MediaDetail? = withContext(Dispatchers.IO) {
        val entry = entriesById[id] ?: entriesById.values.firstOrNull { id in it.legacyIds } ?: return@withContext null
        MediaDetail(
            item = entry.item,
            routes = AlistLibraryBuilder.toPlayRoutes(entry),
            related = allItems().filter { it.id != id }.take(8),
        )
    }

    /**
     * 取某一集的可播放直链。
     *
     * 关键：[Episode.playId] 存的是 alist 内的路径，
     * 这里现取 `raw_url` 拼成可播放的 Episode。
     */
    suspend fun resolveEpisode(episode: Episode): Episode? = withContext(Dispatchers.IO) {
        val path = episode.playId.takeIf { it.isNotBlank() } ?: return@withContext null
        val owner = entrySource.entries.firstOrNull { (id, _) ->
            entriesById[id]?.episodes?.any { it.playId == path } == true
        }?.value
        val sourceKey = episode.sourceKey.takeIf { it.isNotBlank() } ?: owner ?: client.sourceKey
        val source = clients[sourceKey] ?: client

        runCatching {
            ensureLogin(sourceKey)
            val file = source.getFile(path)
            runCatching { android.util.Log.i(
                "ReelixPlay",
                "getFile($sourceKey:$path) -> rawUrl=${file?.rawUrl?.take(120)} isDir=${file?.isDir}"
            ) }
            val f = file ?: return@withContext null
            if (f.rawUrl.isBlank()) {
                android.util.Log.w("ReelixPlay", "rawUrl 为空，文件名=${f.name}")
                return@withContext null
            }
            val rawHost = runCatching { java.net.URI(f.rawUrl).host.orEmpty() }.getOrDefault("")
            val needsAlistProxy = source is AlistClient &&
                (f.provider.contains("115", ignoreCase = true) || rawHost.contains("115cdn", ignoreCase = true))
            val playbackUrl = if (needsAlistProxy) {
                (source as AlistClient).proxyUrl(path)?.also {
                    android.util.Log.i("ReelixPlay", "115 直链 403，改走 Alist 代理: ${it.take(120)}")
                } ?: f.rawUrl
            } else {
                f.rawUrl
            }
            episode.copy(url = playbackUrl)
        }.onFailure {
            android.util.Log.e("ReelixPlay", "getFile 异常: ${it.message}", it)
        }.getOrNull()
    }

    /** 批量解析一集所属线路的所有集（预取几集，避免每次切换都等） */
    suspend fun resolveRoute(route: PlayRoute, limit: Int = 3): PlayRoute =
        withContext(Dispatchers.IO) {
            val resolved = route.episodes.mapIndexed { i, ep ->
                if (i < limit) resolveEpisode(ep) ?: ep else ep
            }
            route.copy(episodes = resolved)
        }

    /** Deletes one real file on its owning drive. */
    suspend fun deleteFile(episode: Episode): Result<Unit> = withContext(Dispatchers.IO) {
        val path = episode.playId.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalArgumentException("路径为空"))
        val sourceKey = episode.sourceKey.takeIf { it.isNotBlank() }
            ?: entrySource.entries.firstOrNull { (id, _) ->
                entriesById[id]?.episodes?.any { it.playId == path } == true
            }?.value ?: client.sourceKey
        val source = clients[sourceKey]
            ?: return@withContext Result.failure(IllegalStateException("找不到数据源：$sourceKey"))
        try {
            ensureLogin(sourceKey)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        source.remove(path, isDirectory = false)
    }

    /** Deletes a whole work: its directory, or the single file that represents it. */
    suspend fun deleteEntry(entry: LibraryEntry): Result<Unit> = withContext(Dispatchers.IO) {
        val sourceKey = entry.files.firstOrNull { it.sourceKey.isNotBlank() }?.sourceKey
            ?: entry.item.sourceKey.takeIf { it.isNotBlank() } ?: client.sourceKey
        val source = clients[sourceKey]
            ?: return@withContext Result.failure(IllegalStateException("找不到数据源：$sourceKey"))
        // A single-file work must delete that file. entry.directory is its PARENT folder,
        // so using it here would delete every sibling (e.g. the whole /115 mount).
        val single = entry.files.singleOrNull()
        val (target, isDirectory) = when {
            entry.files.isEmpty() ->
                return@withContext Result.failure(IllegalStateException("没有可删除的文件"))
            single != null -> single.path to false
            else -> {
                val directory = entry.directory.trimEnd('/')
                val unsafe = directory.isBlank() || directory == "/" ||
                    entry.files.any { !it.path.startsWith("$directory/") }
                if (unsafe) return@withContext Result.failure(
                    IllegalStateException("目录路径不安全，已取消删除"))
                directory to true
            }
        }
        try {
            ensureLogin(sourceKey)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        source.remove(target, isDirectory)
    }

    /** Forgets local edits after the drive confirmed deletion. */
    suspend fun forgetDeleted(id: String, directory: String?) = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            customizations.values.forEach { store ->
                store.removeWork(id)
                if (!directory.isNullOrBlank()) store.setMode(directory, null)
            }
        }
    }

    fun providerLabel(id: String): String =
        entriesById[id]?.files?.firstOrNull()?.provider?.takeIf { it.isNotBlank() } ?: ""

    /**
     * Drops paths from the in-memory snapshot, republishes and rewrites that source's cache.
     *
     * Deletion should feel instant. We deliberately do NOT start a background rescan: if the
     * drive refused part of the removal, the next manual refresh will simply bring it back.
     */
    suspend fun removePathsFromSnapshot(paths: Set<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        // Deliberately NOT taking loadMutex: a long scan must not delay the visible deletion.
        val current = snapshot ?: return@withContext
        val remaining = current.videos.filterNot { it.path in paths }
        val remainingArchived = current.archivedVideos.filterNot { it.path in paths }
        val next = current.copy(videos = remaining, archivedVideos = remainingArchived)
        publish(next)
    }

    /**
     * Cache persistence is separate from the visible removal so a slow disk never delays the UI.
     * A failure here only means the next launch rescans; it never resurrects the item on screen.
     */
    suspend fun persistSnapshotCache() = withContext(Dispatchers.IO) {
        val current = snapshot ?: return@withContext
        current.videos.plus(current.archivedVideos)
            .groupBy { it.sourceKey.ifBlank { "alist" } }
            .forEach { (sourceKey, videos) ->
                val directories = current.directories.filter { directory ->
                    videos.any { it.path.startsWith(directory.trimEnd('/') + "/") }
                }
                runCatching {
                    cacheWriters[sourceKey]?.invoke(LibraryScan(videos, directories, current.failures))
                }.onFailure {
                    android.util.Log.w("ReelixDelete", "cache write failed for $sourceKey", it)
                }
            }
    }
}
