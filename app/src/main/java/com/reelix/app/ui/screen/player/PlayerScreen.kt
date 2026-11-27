package com.reelix.app.ui.screen.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.reelix.app.R
import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.player.PlayerFactory
import com.reelix.app.player.DecoderMode
import com.reelix.app.player.*
import com.reelix.app.ui.component.KeyRouting
import androidx.compose.ui.text.style.TextAlign
import com.reelix.app.ui.theme.RxRadius
import com.reelix.app.ui.component.FocusLevel
import com.reelix.app.ui.component.FocusableSurface
import com.reelix.app.ui.theme.at
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class PlaybackFallbackTracker(
    val requestKey: String? = null,
    val attempted: Set<EngineKind> = emptySet(),
    val switching: Boolean = false,
)

/**
 * 播放页 —— ExoPlayer + 自定义控制层。
 *
 * 生命周期处理（电视盒子上最容易出问题的地方）：
 *  - ON_PAUSE 暂停播放（切后台不该继续响）
 *  - onDispose / ON_DESTROY 释放 player（不释放会泄漏 Surface 和解码器）
 *
 * 控制层自动隐藏：
 *  - 播放中 3 秒无操作 → 淡出
 *  - 暂停中不隐藏（正在看信息）
 *  - 任意按键唤出
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    detail: MediaDetail,
    initialRouteIndex: Int = 0,
    initialEpisodeIndex: Int = 0,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    defaultDecoder: DecoderMode = DecoderMode.AUTO,
    initialEngine: EngineKind = EngineKind.EXO,
    /** 0 = never hide the controls; otherwise seconds of inactivity. */
    controlsHideSeconds: Int = 3,
    /** COMPACT / STANDARD / LARGE */
    panelSize: PlayerPanelSize = PlayerPanelSize.STANDARD,
    /**
     * 解析播放直链的回调。
     *
     * 为什么需要这个：私有片库（Alist/115）的播放地址是带时效签名的
     * `raw_url`，几分钟就失效，**不能提前缓存**。所以每次开播前现取。
     * 演示数据（Episode.url 已填）不需要走这个回调。
     */
    resolveEpisode: (suspend (Episode) -> Episode?)? = null,
    /**
     * 外部传入的失败原因（比如网盘未登录）。
     *
     * 为什么要单独一个参数：`resolveEpisode` 返回 null 时播放器只知道
     * "没有地址"，但用户需要知道**为什么** —— 是没登录网盘、文件失效、
     * 还是源站挂了。这个字段就是给上层透传原因的。
     */
    externalError: String? = null,
    /** 当前解析阶段（上层透传，如"正在嗅探网页视频地址"）；null 表示未知 */
    resolveStage: String? = null,
    /** 解析起始时间戳（System.currentTimeMillis），用来显示已等待多久 */
    resolveStartedAt: Long = 0L,
    /**
     * 读取上次进度：返回 (集号, 毫秒位置)。
     * 首次进入播放页时用来"续播"，null 表示从头开始。
     */
    loadResumePosition: (suspend () -> ResumePoint?)? = null,
    /** 保存进度回调（节流由仓库负责，这里每 3 秒调一次即可） */
    onSaveProgress: (suspend (episodeIndex: Int, episodeName: String, routeName: String, positionMs: Long, durationMs: Long, finished: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var routeIndex by remember { mutableStateOf(initialRouteIndex) }
    var episode by remember {
        mutableStateOf(
            detail.routes.getOrNull(initialRouteIndex)
                ?.episodes?.getOrNull(initialEpisodeIndex)
        )
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var episodesVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(externalError) }
    val currentExternalError by rememberUpdatedState(externalError)
    // 失败后重新选中同一集时，父页面可能已换了一条新地址；旧错误必须随请求清除。
    androidx.compose.runtime.LaunchedEffect(externalError, routeIndex, episode?.index) {
        if (externalError != null) error = externalError
    }
    var isResolving by remember { mutableStateOf(false) }

    // 开始缓冲的时间戳，用于「卡住太久」的提示
    var bufferingSince by remember { mutableLongStateOf(0L) }

    // ---- 实时下载速度（bytes/s）----
    var networkStatus by remember { mutableStateOf(TransferStatus()) }

    // 续播目标：首次准备完成后 seek 到这里
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    // 已读过的续播点（避免重复读取）
    var resumeLoaded by remember { mutableStateOf(false) }

    // ---- 画面比例 / 解码模式 ----
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    var decoderMode by remember { mutableStateOf(defaultDecoder) }
    var engineKind by remember { mutableStateOf(initialEngine) }
    var preferredEngineKind by remember { mutableStateOf(initialEngine) }
    var engineResume by remember { mutableStateOf<PlaybackResume?>(null) }
    val fallbackTracker = remember { mutableStateOf(PlaybackFallbackTracker()) }
    // 顺序由详情页统一设置；播放器只读取，不再提供独立的正序/倒序按钮。
    val episodesReversed = remember(detail.item.id) { EpisodeOrderSession.reversed(detail.item.id) }

    // ---- 片头/片尾跳过（按剧保存）----
    var skipInfo by remember { mutableStateOf(SkipStore.load(context, detail.item.id)) }
    // 正在编辑片头还是片尾；null = 非编辑态
    var skipEditing by remember { mutableStateOf<SkipField?>(null) }

    val player: PlaybackEngine = remember(decoderMode, engineKind) {
        try {
            when (engineKind) {
                EngineKind.EXO -> ExoPlaybackEngine(context, decoderMode)
                EngineKind.VLC -> VlcPlaybackEngine(context, decoderMode)
                EngineKind.SYSTEM -> SystemMediaPlayerEngine(context)
            }
        } catch (failure: LinkageError) {
            UnavailablePlaybackEngine(engineKind, failure)
        } catch (failure: Exception) {
            UnavailablePlaybackEngine(engineKind, failure)
        }
    }
    val currentPlayer by rememberUpdatedState(player)

    /**
     * 统一的内核兜底：Exo → VLC → 系统播放器，每个在本次请求里只试一次。
     *
     * 返回 true 表示已经切到下一个内核；false 表示三条都试过了，只能报错。
     * 显式错误回调和下面的看门狗共用这一段，避免两处逻辑各写一套。
     */
    fun failoverToNextEngine(cause: Throwable?): Boolean {
        val tracker = fallbackTracker.value
        val next = tracker.requestKey
            ?.let { nextFallbackEngine(player.kind, tracker.attempted + player.kind) }
            ?: return false
        fallbackTracker.value = tracker.copy(
            attempted = tracker.attempted + player.kind + next,
            switching = true,
        )
        engineResume = mergeFallbackResume(engineResume, runCatching { player.snapshot() }.getOrNull())
        error = null
        android.util.Log.w(TAG, "${player.kind} 播放失败，切换到 $next", cause)
        // 轻提示：让用户知道内核被自动切换了，几秒后消失。
        // 回调可能不在主线程，Toast 统一切到主线程。
        val fromName = engineDisplayName(context, player.kind)
        val toName = engineDisplayName(context, next)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.player_engine_switched, fromName, toName),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        // 自动兜底也算一次内核变更，偏好跟着走，菜单才不会一直停在旧内核上。
        preferredEngineKind = next
        engineKind = next
        return true
    }

    val currentDrmScheme by rememberUpdatedState(episode?.drmScheme?.trim()?.takeIf { it.isNotEmpty() })
    var audioTracks by remember(player) { mutableStateOf(player.audioTracks) }
    var audioAutomatic by remember(player) { mutableStateOf(player.audioAutomatic) }
    var subtitleTracks by remember(player) { mutableStateOf(player.subtitleTracks) }
    val panelScale = remember(panelSize) { PlayerPanelScale(panelSize) }
    var videoResolution by remember(player) { mutableStateOf(player.videoResolution) }

    // 播放倍速（爆米花控制条上的"倍速"按钮；我们做 4 档循环）
    var speed by remember { mutableStateOf(1f) }
    LaunchedEffect(player, speed) {
        runCatching { player.setPlaybackSpeed(speed) }
        android.util.Log.i("ReelixPlayer", "倍速 -> ${speed}x")
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    /**
     * 离开播放页前的收尾。
     *
     * 为什么用 GlobalScope 而不是 rememberCoroutineScope：
     *  `exitWithSave()` 之后马上 `onBack()`，页面被移出组合，
     *  rememberCoroutineScope 会**立刻取消**，导致写入还没落库就断了。
     *  这是实测发现"退出后进度丢失"的根因。
     *  进度写入量极小（单条 upsert），用进程级作用域最稳。
     */
    fun exitWithSave() {
        val pos = runCatching { player.currentPosition }.getOrDefault(0L)
        val dur = runCatching { player.duration }.getOrDefault(0L)
        val epIdx = episode?.index ?: 1
        val epName = episode?.displayName.orEmpty()
        val rName = detail.routes.getOrNull(routeIndex)?.name.orEmpty()
        val cb = onSaveProgress

        if (dur > 0 && pos > 0) {
            AndroidLog.d(TAG, "exitWithSave: ep=$epIdx pos=$pos dur=$dur cb=${cb != null}")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    cb?.invoke(epIdx, epName, rName, pos, dur, pos >= dur - 5_000)
                }.onFailure {
                    AndroidLog.e(TAG, "saveProgress 回调失败", it)
                }
            }
        }
        player.pause()
        onBack()
    }

    // 选项面板是否展开 —— 提在这里，因为 BACK 分层和自动隐藏都要看它
    var playerMenuOpen by remember { mutableStateOf(false) }

    /**
     * BACK 的唯一执行入口（预览层和 BackHandler 都走它）。
     *
     * ★ 为什么要在预览层处理而不是只靠 BackHandler：
     *   BackHandler 走 OnBackPressedDispatcher，触发时机取决于回调注册顺序
     *   和按键抬起事件；实测在电视上表现为"第一次 BACK 无反应、第二次才生效"。
     *   而且有些遥控器的返回键发的是 ESCAPE 而不是 BACK，根本进不了 dispatcher。
     *   在 onPreviewKeyEvent 里按 KeyDown 直接判定，时序确定。
     */
    fun handleBack(): Boolean {
        if (skipEditing != null) {
            skipEditing = null
            return true
        }
        when (KeyRouting.playerBack(playerMenuOpen, controlsVisible, hasError = !error.isNullOrBlank())) {
            KeyRouting.PlayerBackAction.CLOSE_PANEL -> playerMenuOpen = false
            KeyRouting.PlayerBackAction.HIDE_CONTROLS -> controlsVisible = false
            KeyRouting.PlayerBackAction.EXIT -> exitWithSave()
        }
        return true
    }

    // 兜底：系统手势返回 / 预览层没拿到事件时仍然可用
    androidx.activity.compose.BackHandler(enabled = true) {
        handleBack()
    }

    // 释放
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 生命周期联动
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_DESTROY -> player.release()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 播放器状态订阅
    DisposableEffect(player) {
        val listener = object : PlaybackEngine.Listener {
            override fun onAudioChanged() {
                audioTracks = player.audioTracks
                audioAutomatic = player.audioAutomatic
            }
            override fun onSubtitlesChanged() { subtitleTracks = player.subtitleTracks }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onVideoSizeChanged() {
                videoResolution = player.videoResolution
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && fallbackTracker.value.switching) {
                    fallbackTracker.value = fallbackTracker.value.copy(switching = false)
                }
                // 缓冲计时：有些源既不报错也播不出来，一直停在 BUFFERING，
                // 界面黑屏 + 00:00 且没有任何提示（实测 2026-11-02）。
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                } else {
                    bufferingSince = 0L
                }

                if (state == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0)
                    error = null
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                if (player !== currentPlayer) return
                val drmScheme = currentDrmScheme
                if (drmScheme != null) {
                    error = context.getString(R.string.player_drm_not_configured, drmScheme)
                    android.util.Log.e(TAG, "DRM 不支持: scheme=$drmScheme", e)
                    return
                }
                val tracker = fallbackTracker.value
                if (tracker.switching) return
                if (failoverToNextEngine(e)) return
                // 把 ExoPlayer 的错误翻译成人话。
                //
                // 原文长这样：
                //   ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 403
                // 用户看不出是"源失效""需要登录"还是"网络问题"。
                val detail = e.cause?.message ?: e.message.orEmpty()
                error = buildString {
                    append(e.errorCodeName)
                    if (detail.isNotBlank()) {
                        append("：")
                        append(detail.take(120))
                    }
                    // 常见码补一句怎么办
                    when (e.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                            append("\n链接已失效或需要登录网盘，试试换条线路")
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            append("\n网络连不上，检查网络或换个源")
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                            append("\n" + context.getString(R.string.player_decoder_unavailable))
                        else -> Unit
                    }
                }
                android.util.Log.e(TAG, "播放错误: $error", e)
            }

        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // TrafficStats is app-wide, so every engine reports the same receive rate.
    LaunchedEffect(player) {
        networkStatus = TransferStatus()
        while (true) {
            delay(1_000)
            networkStatus = player.networkMeter.status(android.os.SystemClock.elapsedRealtime())
            if (com.reelix.app.BuildConfig.DEBUG) android.util.Log.d("ReelixTraffic",
                "engine=${player.kind} activity=${networkStatus.activity} bytesPerSecond=${networkStatus.bytesPerSecond}")
        }
    }

    // 进度轮询：Media3 没有每秒回调，250ms 轮询开销可忽略
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0)
            if (durationMs <= 0) durationMs = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }

    // ---- 片头/片尾自动跳过 ----
    //
    // 片头：positionMs < introEndMs 时直接 seek 到片头结束。
    //   seek 之后 positionMs >= introEndMs，循环不再命中 → 只跳一次。
    // 片尾：positionMs >= outroStartMs 时 seek 到结尾。
    //   电影（单集）seek 到结尾就是 ENDED，自然停止。
    LaunchedEffect(player, routeIndex, episode?.playId, skipInfo, playerMenuOpen, skipEditing) {
        // A newly captured outro must not end the episode while the user is still adjusting it.
        if (playerMenuOpen || skipEditing != null) return@LaunchedEffect
        while (true) {
            delay(500)
            val pos = player.currentPosition.coerceAtLeast(0)
            val dur = player.duration.coerceAtLeast(0)
            if (dur <= 0) continue

            if (skipInfo.introEndMs > 0 && pos < skipInfo.introEndMs) {
                player.seekTo(skipInfo.introEndMs)
                continue
            }
            if (skipInfo.outroStartMs > 0 &&
                skipInfo.outroStartMs < dur &&
                pos >= skipInfo.outroStartMs
            ) {
                player.seekTo(dur)
                // Repeated end seeks restart buffering/READY and cancel the next-episode countdown.
                // The effect restarts for the next episode or a changed skip setting.
                break
            }
        }
    }

    // ---- ① 首次进入读取续播点 ----
    LaunchedEffect(Unit) {
        if (resumeLoaded || loadResumePosition == null) return@LaunchedEffect
        resumeLoaded = true
        val rp = runCatching { loadResumePosition() }.getOrNull()
        if (rp != null && rp.positionMs > 0) {
            // 若记录的集号与当前不同，先切到那一集
            if (rp.episodeIndex > 0 && rp.episodeIndex != episode?.index) {
                val target = detail.routes.getOrNull(routeIndex)
                    ?.episodes?.firstOrNull { it.index == rp.episodeIndex }
                if (target != null) episode = target
            }
            pendingSeekMs = rp.positionMs
        }
    }

    // ---- ② 换集 / 换线路时重新加载 ----
    LaunchedEffect(player, routeIndex, episode?.playId) {
        val ep = episode ?: return@LaunchedEffect
        // 新选择必须从干净的错误状态开始，否则换线路仍会显示上一条的失败。
        error = null

        // 优先用已解析的直链；没有则回调仓库现取（片库场景）
        var resolutionFailure: String? = null
        var resolvedEpisode = ep
        val resolvedUrl: String? = ep.url ?: run {
            isResolving = true
            AndroidLog.d(TAG, "开始解析: ep=${ep.index} playId=${ep.playId.take(60)} flag=${ep.flag}")
            val resolved = try {
                resolveEpisode?.invoke(ep)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                resolutionFailure = failure.message
                null
            } finally {
                isResolving = false
            }
            AndroidLog.d(TAG, "解析结果: url=${resolved?.url?.take(100)}")
            // 顺便把直链缓存回本集，切回来不用再取
            if (resolved != null) {
                resolvedEpisode = resolved
                episode = resolved
            }
            resolved?.url
        }

        if (resolvedUrl.isNullOrBlank()) {
            error = resolutionFailure?.takeIf { it.isNotBlank() }
                ?: currentExternalError?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.player_no_url)
            return@LaunchedEffect
        }

        val drmScheme = (resolvedEpisode.drmScheme ?: ep.drmScheme ?: episode?.drmScheme)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (drmScheme != null) {
            if (engineKind != EngineKind.EXO) {
                // DRM requests are routed only to ExoPlayer. VLC and the system fallback
                // are deliberately skipped; no DRM license configuration exists yet.
                preferredEngineKind = EngineKind.EXO
                engineResume = null
                engineKind = EngineKind.EXO
            } else {
                error = context.getString(R.string.player_drm_not_configured, drmScheme)
                controlsVisible = true
            }
            return@LaunchedEffect
        }

        error = null
        val requestKey = listOf(routeIndex, resolvedEpisode.index, resolvedEpisode.playId, resolvedUrl)
            .joinToString("\u0000")
        val tracker = fallbackTracker.value
        fallbackTracker.value = if (tracker.requestKey != requestKey) {
            PlaybackFallbackTracker(requestKey, setOf(player.kind))
        } else {
            tracker.copy(attempted = tracker.attempted + player.kind, switching = false)
        }
        val restore = engineResume
        engineResume = null
        networkStatus = TransferStatus()
        videoResolution = null
        durationMs = 0
        bufferedMs = 0
        positionMs = restore?.positionMs ?: 0
        player.prepare(resolvedUrl, episode?.headers ?: ep.headers, restore)
        player.setPlaybackSpeed(speed)
        android.util.Log.i("ReelixPlayback", "episode=${ep.index} route=$routeIndex engine=$engineKind decoder=$decoderMode restored=${restore?.positionMs ?: 0}")
        controlsVisible = true

        // ---- 引擎看门狗 ----
        //
        // 有的内核失败时只打一行内部日志（例如 LibVLC 的 "Failed to create demuxer"），
        // 既不回调 onPlayerError 也不改状态，界面就一直黑屏、没有任何提示。
        // 这里兜住"prepare 之后迟迟进不了 READY"的情况：按失败处理，继续换内核；
        // 三条都试完才把结论告诉用户。
        kotlinx.coroutines.delay(ENGINE_STALL_TIMEOUT_MS)
        // LibVLC 遇到解不开的流时会先报 READY、再打一行 "Failed to create demuxer"，
        // 状态看着正常但进度永远是 0，所以"是否就绪"之外还要看有没有真实进度。
        val stalled = player.playbackState != Player.STATE_READY || player.currentPosition <= 0L
        if (stalled) {
            android.util.Log.w(
                TAG,
                "引擎看门狗: $engineKind 超过 ${ENGINE_STALL_TIMEOUT_MS / 1000}s 没有进度 " +
                    "(state=${player.playbackState} pos=${player.currentPosition})",
            )
            if (!failoverToNextEngine(null)) {
                error = context.getString(R.string.player_engines_exhausted)
                controlsVisible = true
            }
        }
    }

    // ---- ③ READY 后执行续播 seek ----
    // pendingSeek 往往在 prepare() 之前就设置好，此时播放器还没进入 READY；
    // 只监听 durationMs 变化会漏掉 READY 时机，导致续播点被丢掉。
    LaunchedEffect(pendingSeekMs) {
        val target = pendingSeekMs ?: return@LaunchedEffect
        var waitedMs = 0L
        while (pendingSeekMs == target) {
            val duration = player.duration
            if (player.playbackState == Player.STATE_READY && duration > 0) {
                val seekTarget = if (duration > 5_000) {
                    target.coerceAtMost(duration - 5_000)
                } else {
                    target
                }.coerceAtLeast(0)
                player.seekTo(seekTarget)
                pendingSeekMs = null
                break
            }
            // READY 还没到就继续等；超过 20 秒仍不可 seek 则放弃，避免无限轮询。
            if (waitedMs >= 20_000) {
                break
            }
            kotlinx.coroutines.delay(100)
            waitedMs += 100
        }
    }

    // ---- ③-b 缓冲超时提示 ----
    //
    // 有些源给的地址既不报错也出不来画面，ExoPlayer 会一直停在 BUFFERING，
    // 界面就是黑屏 + 00:00，用户不知道发生了什么（实测 2026-11-02）。
    // 超过 20 秒仍是 BUFFERING，就明确告诉他。
    LaunchedEffect(bufferingSince, error) {
        if (bufferingSince == 0L || error != null) return@LaunchedEffect
        kotlinx.coroutines.delay(20_000)
        if (bufferingSince != 0L && error == null && player.duration <= 0) {
            error = "加载超时（20 秒没有画面）\n" +
                "常见原因：这条线路需要登录网盘、链接已失效，或者源站很慢。\n" +
                "建议换一条线路，或在「设置 → 网盘授权」里登录对应网盘。"
            android.util.Log.w(TAG, "缓冲超时: bufferingSince=$bufferingSince")
        }
    }

    // ---- ④ 节流写进度：每 3 秒且位移 > 2 秒才写 ----
    LaunchedEffect(player, episode?.index, routeIndex) {
        var lastSaveAt = 0L
        var lastPos = 0L
        while (true) {
            delay(1_000)
            val pos = player.currentPosition
            val dur = player.duration
            val now = System.currentTimeMillis()
            if (dur > 0 && pos > 0 && (now - lastSaveAt >= 3_000) &&
                kotlin.math.abs(pos - lastPos) >= 2_000
            ) {
                lastSaveAt = now
                lastPos = pos
                AndroidLog.d(TAG, "tick save: ep=${episode?.index} pos=$pos dur=$dur")
                onSaveProgress?.invoke(
                    episode?.index ?: 1,
                    episode?.displayName.orEmpty(),
                    detail.routes.getOrNull(routeIndex)?.name.orEmpty(),
                    pos,
                    dur,
                    false,
                )
            }
        }
    }

    // 面板展开时不能自动隐藏，否则面板刚打开就被收掉

    // 控制层自动隐藏
    LaunchedEffect(controlsVisible, isPlaying, episodesVisible, playerMenuOpen, controlsInteraction, controlsHideSeconds) {
        val hideAfter = controlsAutoHideDelayMs(
            seconds = controlsHideSeconds,
            controlsVisible = controlsVisible,
            isPlaying = isPlaying,
            episodesVisible = episodesVisible,
            menuOpen = playerMenuOpen,
        )
        if (hideAfter != null) {
            delay(hideAfter)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsInteraction) {
        if (seekFeedback != null) { delay(900); seekFeedback = null }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { event ->
                // 按键怎么分流全在 KeyRouting 里，这里只负责执行副作用。
                // 规则本身有单测（KeyRoutingTest），电视上只需要验焦点框怎么走。
                val isBackLike = event.key == Key.Back || event.key == Key.Escape
                if (isBackLike) {
                    // 只在按下时处理，抬起直接吃掉，避免走系统默认返回
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) handleBack()
                    return@onPreviewKeyEvent true
                }
                val verdict = KeyRouting.playerTopLevel(
                    isKeyDown = event.type == KeyEventType.KeyDown,
                    isBack = false,
                    controlsVisible = controlsVisible,
                    isDown = event.key == Key.DirectionDown,
                    menuOpen = playerMenuOpen,
                    isLeft = event.key == Key.DirectionLeft,
                    isRight = event.key == Key.DirectionRight,
                    isConfirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                )
                if (verdict.consume && event.type == KeyEventType.KeyDown) {
                    controlsInteraction++
                }
                if (verdict.seekDirection != 0) {
                    val held = event.nativeKeyEvent.eventTime - event.nativeKeyEvent.downTime
                    val target = KeyRouting.seekBar(true, verdict.seekDirection < 0, verdict.seekDirection > 0,
                        player.currentPosition, durationMs, KeyRouting.seekStepFor(held))
                    if (target != null) {
                        player.seekTo(target)
                        seekFeedback = context.getString(if (verdict.seekDirection < 0)
                            R.string.player_seek_backward_hint else R.string.player_seek_forward_hint,
                            KeyRouting.seekStepFor(held) / 1000)
                    }
                }
                if (verdict.togglePlay) {
                    if (player.isPlaying) player.pause() else player.play()
                }
                if (verdict.revealControls) controlsVisible = true
                if (verdict.openMenu) playerMenuOpen = true
                verdict.consume
            }
    ) {
        // ---- 视频层 ----
        androidx.compose.runtime.key(player) {
            AndroidView(
                factory = { ctx -> player.createView(ctx) },
                update = { view -> player.updateView(view, aspectMode.name, controlsVisible) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- 控制层 ----
        // 菜单项要复用这两个动作，先抽成局部变量
        val toggleEpisodesAction: () -> Unit = {
            episodesVisible = !episodesVisible
            controlsVisible = true
        }
        val selectEpisodeAction: (Episode) -> Unit = { ep ->
            fallbackTracker.value = PlaybackFallbackTracker()
            episode = ep
            episodesVisible = false
            playerMenuOpen = false
            controlsVisible = true
        }
        val selectRouteAction: (Int) -> Unit = { idx ->
            if (idx != routeIndex) {
                val match = episode?.let { current ->
                    matchRouteEpisode(current, detail.routes.getOrNull(idx)?.episodes.orEmpty())
                } ?: RouteEpisodeMatch.Absent
                when (match) {
                    is RouteEpisodeMatch.Unique -> {
                        fallbackTracker.value = PlaybackFallbackTracker()
                        routeIndex = idx
                        episode = match.episode
                    }
                    else -> android.widget.Toast.makeText(context,
                        if (match is RouteEpisodeMatch.Ambiguous) R.string.route_match_player_ambiguous
                        else R.string.route_match_player_absent,
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            controlsVisible = true
        }
        val replayFromStartAction: () -> Unit = {
            player.seekTo(0)
            player.play()
            playerMenuOpen = false
            controlsVisible = true
        }
        fun markCurrentSkip(field: SkipField) {
            val updated = markSkipAt(skipInfo, field, player.currentPosition, player.duration)
            if (updated != null) {
                skipInfo = updated
                SkipStore.save(context, detail.item.id, updated)
            } else {
                android.widget.Toast.makeText(context, R.string.player_skip_time_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        val playlistLabel = stringResource(R.string.player_playlist)
        val routeLabel = stringResource(R.string.player_switch_route)
        // Position/buffer ticks must not rebuild hundreds of option models.
        val playerMenuItems = remember(detail, routeIndex, episode?.playId, speed, aspectMode, decoderMode, skipInfo,
            episodesReversed, audioTracks, audioAutomatic, subtitleTracks, player, engineKind) {
            val routeOptionKeys = playerRouteOptionKeys(detail.routes)
            buildList {
                val canonical = detail.routes.getOrNull(routeIndex)?.episodes.orEmpty()
                val eps = episodePresentation(canonical, episodesReversed)
                if (eps.size > 1) {
                    add(
                        PlayerMenuItem(
                            key = "episodes",
                            label = playlistLabel,
                            options = eps.map { ep ->
                                PlayerMenuOption(
                                    label = playerEpisodeOptionLabel(ep),
                                    selected = ep.index == episode?.index,
                                    onClick = { selectEpisodeAction(ep) },
                                    key = "${ep.flag}|${ep.playId}|${ep.index}",
                                    shortLabel = ep.index.toString(),
                                )
                            },
                        )
                    )
                }
                add(
                    PlayerMenuItem(
                        key = "replay",
                        label = context.getString(R.string.player_replay),
                        options = listOf(
                            PlayerMenuOption(
                                label = context.getString(R.string.player_replay_current),
                                selected = false,
                                onClick = replayFromStartAction,
                                key = "replay",
                            ),
                        ),
                    ),
                )
                add(
                    PlayerMenuItem(
                        key = "speed",
                        label = "倍速",
                        options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f).map { v ->
                            PlayerMenuOption(
                                label = if (v == 1f) "1.0" else "${v}x",
                                selected = speed == v,
                                onClick = { speed = v },
                                key = "speed:$v",
                            )
                        },
                    )
                )
                add(
                    PlayerMenuItem(
                        key = "aspect",
                        label = "画面模式",
                        options = AspectMode.entries.map { m ->
                            PlayerMenuOption(
                                label = m.label,
                                selected = aspectMode == m,
                                onClick = { aspectMode = m },
                                key = "aspect:$m",
                            )
                        },
                    )
                )
                add(
                    PlayerMenuItem(
                        key = "decoder",
                        label = context.getString(R.string.player_decoder),
                        options = DecoderMode.entries.map { m ->
                            PlayerMenuOption(
                                label = context.getString(if (engineKind == EngineKind.VLC && m == DecoderMode.HARDWARE)
                                    R.string.player_decoder_prefer_hardware else m.labelRes()),
                                selected = decoderMode == m,
                                key = "decoder:$m",
                                onClick = {
                                    if (decoderMode != m) {
                                        engineResume = player.snapshot()
                                        decoderMode = m
                                    }
                                },
                            )
                        },
                    )
                )
                val audioOptions = audioTracks.mapIndexed { index, track ->
                    val language = track.language.takeIf { it.isNotBlank() && it != "und" }?.let {
                        java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
                    }.orEmpty()
                    val description = listOf(track.label, language,
                        if (track.channels > 0) context.getString(R.string.player_audio_channels, track.channels) else "")
                        .filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    PlayerMenuOption(
                        label = context.getString(R.string.player_audio_track_number, 1, index + 1) +
                            if (description.isBlank()) "" else " · $description",
                        selected = track.selected,
                        key = track.key,
                        onClick = { player.selectAudio(track.key) },
                    )
                }
                if (audioOptions.isNotEmpty()) add(PlayerMenuItem(
                    key = "audio", label = context.getString(R.string.player_audio),
                    options = listOf(PlayerMenuOption(
                        label = context.getString(R.string.player_audio_auto),
                        selected = audioAutomatic,
                        key = "audio:auto",
                        onClick = { player.selectAudio(null) },
                    )) + audioOptions,
                ))
                if (subtitleTracks.size > 1) {
                    add(
                        PlayerMenuItem(
                            key = "subtitle",
                            label = context.getString(R.string.player_subtitle),
                            options = subtitleTracks.mapIndexed { index, track ->
                                PlayerMenuOption(
                                    label = track.label.ifBlank {
                                        context.getString(R.string.player_subtitle_track, index)
                                    },
                                    selected = track.selected,
                                    key = track.key,
                                    onClick = { player.selectSubtitle(track.key) },
                                )
                            },
                        ),
                    )
                }
                add(PlayerMenuItem(
                    key = "engine", label = context.getString(R.string.player_engine),
                    options = buildList {
                        addAll(listOf(EngineKind.EXO, EngineKind.VLC).map { kind -> PlayerMenuOption(
                            label = engineDisplayName(context, kind),
                            // 显示实际在跑的内核：自动兜底切到 VLC 后，菜单必须跟着变，
                            // 否则用户完全看不出切换发生过。
                            selected = engineKind == kind,
                            key = "engine:$kind",
                            onClick = {
                                val drm = episode?.drmScheme?.trim()?.takeIf { it.isNotEmpty() }
                                if (drm != null && kind == EngineKind.VLC) {
                                    preferredEngineKind = EngineKind.EXO
                                    engineKind = EngineKind.EXO
                                    error = context.getString(R.string.player_drm_not_configured, drm)
                                } else {
                                    // 即使点的就是当前内核，也要把偏好记下来，
                                    // 否则自动兜底后用户无法用菜单纠正选中态。
                                    preferredEngineKind = kind
                                    if (engineKind != kind) {
                                        engineResume = player.snapshot()
                                        fallbackTracker.value = PlaybackFallbackTracker()
                                        engineKind = kind
                                    }
                                }
                            },
                        ) })
                        // 系统播放器也允许手动选：自动兜底时面板能显示它，
                        // 用户也可以临时强制用它（例如 Exo/VLC 都不兼容的片源）。
                        add(PlayerMenuOption(
                            label = context.getString(R.string.player_engine_system),
                            selected = engineKind == EngineKind.SYSTEM,
                            key = "engine:SYSTEM",
                            onClick = {
                                preferredEngineKind = EngineKind.SYSTEM
                                if (engineKind != EngineKind.SYSTEM) {
                                    engineResume = player.snapshot()
                                    fallbackTracker.value = PlaybackFallbackTracker()
                                    engineKind = EngineKind.SYSTEM
                                }
                            },
                        ))
                    },
                ))
                add(
                    PlayerMenuItem(
                        key = "skip",
                        label = "跳过",
                        options = listOf(
                            PlayerMenuOption(
                                label = context.getString(R.string.player_skip_intro_time, formatDuration(skipInfo.introEndMs)),
                                selected = skipInfo.introEndMs > 0,
                                onClick = { skipEditing = SkipField.INTRO },
                                key = "intro",
                            ),
                            PlayerMenuOption(
                                label = context.getString(R.string.player_skip_outro_time, formatDuration(skipInfo.outroStartMs)),
                                selected = skipInfo.outroStartMs > 0,
                                onClick = { skipEditing = SkipField.OUTRO },
                                key = "outro",
                            ),
                            PlayerMenuOption(
                                label = context.getString(R.string.player_skip_current_intro),
                                selected = false,
                                onClick = { markCurrentSkip(SkipField.INTRO) },
                                key = "current_intro",
                            ),
                            PlayerMenuOption(
                                label = context.getString(R.string.player_skip_current_outro),
                                selected = false,
                                onClick = { markCurrentSkip(SkipField.OUTRO) },
                                key = "current_outro",
                            ),
                            PlayerMenuOption(
                                label = "关闭",
                                selected = !skipInfo.enabled,
                                onClick = {
                                    skipInfo = SkipInfo()
                                    SkipStore.save(context, detail.item.id, skipInfo)
                                },
                                key = "disable",
                            ),
                        ),
                    )
                )
                if (detail.routes.size > 1) {
                    add(
                        PlayerMenuItem(
                            key = "route",
                            label = routeLabel,
                            options = detail.routes.mapIndexed { i, r ->
                                PlayerMenuOption(
                                    label = r.name.ifBlank { "线路${i + 1}" },
                                    selected = i == routeIndex,
                                    onClick = { selectRouteAction(i) },
                                    key = routeOptionKeys[i],
                                )
                            },
                        )
                    )
                }
            }
        }

        PlayerControls(
            item = detail.item,
            routes = detail.routes,
            currentRouteIndex = routeIndex,
            currentEpisode = episode,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            visible = controlsVisible,
            seekHint = seekFeedback,
            onTogglePlay = {
                if (player.isPlaying) player.pause() else player.play()
                controlsVisible = true
            },
            onSeekTo = { ms ->
                player.seekTo(ms)
                controlsVisible = true
            },
            onSeekBy = { delta ->
                val target = (player.currentPosition + delta)
                    .coerceIn(0, durationMs.coerceAtLeast(0))
                player.seekTo(target)
                controlsVisible = true
            },
            onBack = { exitWithSave() },
            onSelectEpisode = selectEpisodeAction,
            onSelectRoute = selectRouteAction,
            onToggleEpisodes = toggleEpisodesAction,
            episodesVisible = episodesVisible,
            // 功能菜单：图标 + 选项列表（二级手风琴）。片库将来少传一项"线路"即可
            menuItems = playerMenuItems,
            menuOpen = playerMenuOpen,
            onMenuOpenChange = { playerMenuOpen = it },
            skipInfo = skipInfo,
            skipEditing = skipEditing,
            panelScale = panelScale,
            onSkipChange = { newInfo ->
                // 夹到 [0, duration]，并即时落盘（按剧）
                android.util.Log.i(TAG, "onSkipChange: intro=${newInfo.introEndMs} outro=${newInfo.outroStartMs} dur=$durationMs")
                skipInfo = newInfo.copy(
                    introEndMs = newInfo.introEndMs.coerceIn(0, durationMs),
                    outroStartMs = newInfo.outroStartMs.coerceIn(0, durationMs),
                )
                SkipStore.save(context, detail.item.id, skipInfo)
            },
            onSkipEditingDone = { skipEditing = null },
            networkBps = networkStatus.bytesPerSecond,
            transferActivity = networkStatus.activity,
            videoResolution = videoResolution,
            videoBitrate = player.videoBitrate,
            showBuffer = engineKind == EngineKind.EXO,
            bufferedDurationMs = (bufferedMs - positionMs).coerceAtLeast(0),
        )

        // ---- 解析直链中的提示 ----
        //
        // ★ 2026-11-07：从"一个 spinner + 一句固定文案"升级成"阶段 + 计时 + 超时判定"。
        //   原来的问题是解析可能走到 WebView 嗅探（3~15 秒），期间界面毫无变化，
        //   用户分不清"正在解析"和"已经卡死"。现在超过 10 秒会直接给出结论。
        if (isResolving) {
            ResolveOverlay(stage = resolveStage, startedAt = resolveStartedAt)
        }

        // ---- 错误提示 ----
        //
        // ★ 2026-11-02 升级：原来只有一行灰字，用户看完不知道下一步做什么。
        //   现在给出「换线路 / 换集 / 返回」三个出口 —— 实测在线源失败时，
        //   90% 的情况换一条线路就能播。
        error?.takeIf { !playerMenuOpen }?.let { msg ->
            // ★ 2026-11-07 简化：去掉「换线路 / 换集 / 返回」三个按钮。
            //
            //   原因：这层遮罩和播放器控制层的焦点互相抢，实机上经常选不中按钮
            //   （用户："会失焦，选不中弹窗的按钮"）。而用户真正需要的只是
            //   "知道为什么失败"，然后按 BACK 回详情页自己换线路 —— 那里本来就能换。
            //   所以这里只做纯展示，不提供任何可聚焦控件。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(700.dp)
                ) {
                    Text(
                        text = stringResource(R.string.player_error),
                        style = RxType.Section,
                        color = RxColors.TextPrimary
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = msg,
                        style = RxType.CardMeta.copy(fontSize = 14.sp, lineHeight = 22.sp),
                        color = RxColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = stringResource(R.string.playback_error_back_hint),
                        style = RxType.CardMeta.copy(fontSize = 12.sp),
                        color = RxColors.TextTertiary
                    )
                }
            }
        }
    }
}

private const val TAG = "ReelixPlayer"

/**
 * prepare 之后多久还没进入播放状态就判定这个内核失败。
 * 本地 4K 片源实测 2 秒左右就绪，15 秒足够容下慢速源，又不至于让用户对着黑屏干等。
 */
private const val ENGINE_STALL_TIMEOUT_MS = 15_000L

/**
 * 画面比例按钮必须真的改变 PlayerView 的缩放，不能在界面上只换文字。
 * CROP 保持铺满并居中裁切；ORIGINAL 用 100% 原尺寸。
 */
@OptIn(UnstableApi::class)
private fun aspectResizeMode(mode: AspectMode): Int = when (mode) {
    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
}

/** 内核名称：菜单与"已自动切换"提示共用，避免两处文案不一致。 */
private fun engineDisplayName(context: android.content.Context, kind: EngineKind): String =
    context.getString(when (kind) {
        EngineKind.EXO -> R.string.player_engine_exo
        EngineKind.VLC -> R.string.player_engine_vlc
        EngineKind.SYSTEM -> R.string.player_engine_system
    })
/**
 * 解析中的提示：spinner + 当前阶段 + 已等待秒数 + 超时结论。
 *
 * 为什么要显示秒数：解析链走到 WebView 嗅探时可能 3~15 秒，界面毫无变化会让
 * 用户无法判断"在解析"还是"卡死了"。超过 10 秒就直接给结论，让他按 BACK 换线路。
 */
@Composable
private fun ResolveOverlay(stage: String?, startedAt: Long) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        val base = if (startedAt > 0L) startedAt else System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - base
            kotlinx.coroutines.delay(200)
        }
    }
    val slow = elapsedMs > 10_000L

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(
                color = RxColors.Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.player_resolving),
                style = RxType.CardMeta,
                color = RxColors.TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    if (!stage.isNullOrBlank()) {
                        append(stage)
                        append(" · ")
                    }
                    append("%.1f 秒".format(elapsedMs / 1000.0))
                },
                style = RxType.CardMeta.copy(fontSize = 12.sp),
                color = RxColors.TextTertiary
            )
            if (slow) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "这条线路可能有问题，按 BACK 换一条",
                    style = RxType.CardMeta.copy(fontSize = 12.sp),
                    color = RxColors.Accent
                )
            }
        }
    }
}

/** 错误界面上的动作按钮 */
@Composable
private fun ErrorAction(
    text: String,
    onClick: () -> Unit,
) {    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.height(48.dp).width(168.dp),
        shape = RoundedCornerShape(RxRadius.button.dp),
        focusLevel = FocusLevel.GLOW,
        background = Color.White.at(0.14f),
        contentColor = Color.White,
        borderColor = Color.White.at(0.22f),
    ) {
        Text(
            text = text,
            style = RxType.Button.copy(fontSize = 14.sp),
            color = Color.White
        )
    }
}
