package com.reelix.app.ui.screen.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelix.app.R
import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.player.VideoResolution
import com.reelix.app.player.playerHeaderPresentation
import com.reelix.app.player.secondaryLine
import com.reelix.app.ui.component.FocusLevel
import com.reelix.app.ui.component.FocusableSurface
import com.reelix.app.ui.component.KeyRouting
import com.reelix.app.ui.theme.RxRadius
import com.reelix.app.ui.theme.at
import java.util.Locale

/* ============================================================
 * 播放页控制层 —— 按网易爆米花 TV 2.6.23 真机实测重写
 *
 * 数据来源：work/popcorn-recon/PLAYER-UI-SPEC.md
 * 设备 1920×1080 / density 320 → 1dp = 2px，可用区 960×540dp
 * ============================================================ */

/** 屏幕左右安全边 —— 实测标题、菜单行、播放键都在 116px = 58dp */
private val Edge = 58.dp
private const val PLAY_ICON_DP = 32f

private val TitleSp = 24.sp
private val TimeSp = 14.sp
private val MenuSp = 16.sp
private val PanelLabelSp = 24.sp
private val TileSp = 16.sp

private val PlayIconSize = 32.dp

private val TrackHeight = 5.dp
private val KnobSize = 10.dp

private val MenuGap = 40.dp

/** 面板折叠态行距 —— 实测相邻两项 100~106px = 50~53dp */
private val PanelRowHeight = 50.dp

/** 选集方格：49×47dp（实测 option_btn），一页 15 个一屏放得下 */
private val TileWidth = 49.dp
private val TileHeight = 47.dp
private val TileGap = 7.dp

/** 每页集数 —— 爆米花的分页粒度 */
private const val PageSize = 15

/* ---------- 颜色（screencap 直方图实测） ---------- */
private val Accent = Color(0xFF5980FF)
private val PillIdle = Color(0xFF494A4C)
private val FocusBg = Color(0xFFFFFFFF)
private val FocusInk = Color(0xFF09090A)
private val MenuIdle = Color(0xFFE2E2E8)
private val TrackIdle = Color(0xFFB7B5BA)

data class PlayerMenuOption(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val key: String = label,
    val shortLabel: String? = null,
)

data class PlayerMenuItem(
    val key: String,
    val label: String,
    // 2026-11-09 删掉 icon 字段：PlayerOptionsView 是原生 View 实现，
    // 只读 label 和 options，从来没渲染过图标（grep 该文件没有任何 Icon 引用）。
    // 之前 PlayerScreen 传进来的 7 个 Icons.Rounded.* 全是死代码，
    // 也是 material-icons-extended 这个 8~12MB 大包的仅存用途。
    val options: List<PlayerMenuOption> = emptyList(),
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Composable
fun PlayerControls(
    item: MediaItem,
    routes: List<PlayRoute>,
    currentRouteIndex: Int,
    currentEpisode: Episode?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    visible: Boolean,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onBack: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onSelectRoute: (Int) -> Unit,
    onToggleEpisodes: () -> Unit,
    episodesVisible: Boolean,
    modifier: Modifier = Modifier,
    menuItems: List<PlayerMenuItem> = emptyList(),
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    skipInfo: SkipInfo = SkipInfo(),
    skipEditing: SkipField? = null,
    onSkipChange: (SkipInfo) -> Unit = {},
    onSkipEditingDone: () -> Unit = {},
    networkBps: Long = 0L,
    transferActivity: com.reelix.app.player.TransferActivity = com.reelix.app.player.TransferActivity.CONNECTING,
    videoResolution: VideoResolution? = null,
    videoBitrate: Int? = null,
    showBuffer: Boolean = true,
    bufferedDurationMs: Long = 0L,
    seekHint: String? = null,
    panelScale: PlayerPanelScale = PlayerPanelScale(PlayerPanelSize.STANDARD),
) {
    val playFocus = remember { FocusRequester() }
    val panelKeys = remember { PlayerPanelKeyOwnership() }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200),
        label = "controlsAlpha"
    )
    val panelRise by animateDpAsState(
        targetValue = if (menuOpen) 0.dp else 24.dp,
        animationSpec = tween(250),
        label = "panelRise"
    )

    LaunchedEffect(menuOpen, visible) {
        if (!menuOpen && visible) {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    if (!visible && alpha < 0.01f) return

    Box(modifier = modifier.fillMaxSize().alpha(alpha).onPreviewKeyEvent { event ->
        !menuOpen && panelKeys.consume(
            event.nativeKeyEvent.keyCode,
            event.type == KeyEventType.KeyDown,
            event.nativeKeyEvent.downTime,
        )
    }) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Black.at(0.72f),
                        0.22f to Color.Transparent,
                        0.48f to Color.Transparent,
                        1.0f to Color.Black.at(0.96f),
                    )
                )
            )
        )

        val barAlpha by animateFloatAsState(
            targetValue = if (menuOpen) 0f else 1f,
            animationSpec = tween(140),
            label = "barAlpha"
        )
        val panelAlpha by animateFloatAsState(
            targetValue = if (menuOpen) 1f else 0f,
            animationSpec = tween(140),
            label = "panelAlpha"
        )

        ControlBar(
            item = item,
            currentEpisode = currentEpisode,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            menuItems = menuItems,
            playFocus = playFocus,
            interactive = !menuOpen,
            alphaProvider = { barAlpha },
            scale = panelScale,
            menuScale = panelScale,
            networkBps = networkBps,
            transferActivity = transferActivity,
            videoResolution = videoResolution,
            videoBitrate = videoBitrate,
            showBuffer = showBuffer,
            bufferedDurationMs = bufferedDurationMs,
            onTogglePlay = onTogglePlay,
        )

        OptionPanel(
            items = menuItems,
            positionMs = positionMs,
            riseOffset = panelRise,
            menuOpen = menuOpen,
            panelAlpha = panelAlpha,
            skipInfo = skipInfo,
            skipEditing = skipEditing,
            onSkipChange = onSkipChange,
            onSkipEditingDone = onSkipEditingDone,
            onClose = { onMenuOpenChange(false) },
            panelKeys = panelKeys,
            scale = panelScale,
        )

        seekHint?.let { hint ->
            Text(
                text = hint,
                style = TextStyle(fontSize = panelScale.sp(20f), fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = panelScale.dp(132f))
                    .background(Color.Black.at(0.55f), RoundedCornerShape(panelScale.dp(6f)))
                    .padding(horizontal = panelScale.dp(18f), vertical = panelScale.dp(8f))
            )
        }
    }
}

/* ============================================================
 * 控制条
 * ============================================================ */

@Composable
private fun BoxScope.ControlBar(
    item: MediaItem,
    currentEpisode: Episode?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    menuItems: List<PlayerMenuItem>,
    playFocus: FocusRequester,
    interactive: Boolean,
    alphaProvider: () -> Float,
    scale: PlayerPanelScale,
    menuScale: PlayerPanelScale,
    networkBps: Long,
    transferActivity: com.reelix.app.player.TransferActivity,
    videoResolution: VideoResolution?,
    videoBitrate: Int?,
    showBuffer: Boolean,
    bufferedDurationMs: Long,
    onTogglePlay: () -> Unit,
) {
    val header = remember(item, currentEpisode, videoResolution, videoBitrate) {
        playerHeaderPresentation(item, currentEpisode, videoResolution?.label, videoBitrate)
    }
    val episodeLabel = header.secondaryLine(header.episodeNumber?.let {
        stringResource(R.string.player_header_episode_number, it)
    })
    val networkText = when (playerNetworkState(isPlaying, transferActivity, networkBps)) {
        PlayerNetworkState.PAUSED -> "0.0 MB/s"
        PlayerNetworkState.CONNECTING -> "0.0 MB/s"
        PlayerNetworkState.DOWNLOADING -> formatSpeed(networkBps)
        PlayerNetworkState.NO_DOWNLOAD -> "0.0 MB/s"
        PlayerNetworkState.LOCAL -> "0.0 MB/s"
        PlayerNetworkState.UNAVAILABLE -> stringResource(R.string.player_transfer_unavailable)
    }
    val bufferText = stringResource(R.string.player_buffer_remaining, formatBuffered(bufferedDurationMs))
    Box(Modifier.fillMaxSize().alpha(alphaProvider())) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = scale.dp(58f), end = scale.dp(58f), top = scale.dp(32f)),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(0.6f)) {
                Text(
                    text = header.title,
                    style = TextStyle(fontSize = scale.sp(24f), fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episodeLabel?.let { label ->
                    Text(
                        text = label,
                        style = TextStyle(fontSize = scale.sp(16f), fontWeight = FontWeight.Medium),
                        color = Color.White.at(0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                header.resolutionLabel?.let { resolution ->
                    Text(
                        text = resolution,
                        style = TextStyle(fontSize = scale.sp(13f), fontWeight = FontWeight.Medium),
                        color = Color.White.at(0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(0.4f),
            ) {
                Text(networkText, style = TextStyle(fontSize = scale.sp(12f)), color = Color.White.at(0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                if (showBuffer) {
                    Text(bufferText, style = TextStyle(fontSize = scale.sp(12f)), color = Color.White.at(0.85f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = scale.dp(58f))
        ) {
            Row(
                Modifier.fillMaxWidth().height(scale.touchDp(PLAY_ICON_DP)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayPauseIcon(
                    isPlaying = isPlaying,
                    onClick = onTogglePlay,
                    focusRequester = playFocus,
                    enabled = interactive,
            scale = menuScale,
                )
                Spacer(Modifier.width(scale.dp(10f)))
                TimelineBar(
                    scale = scale,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    interactive = interactive,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(scale.dp(10f)))
                Text(
                    text = formatDuration(positionMs) + " / " + formatDuration(durationMs),
                    style = TextStyle(fontSize = scale.sp(14f), fontWeight = FontWeight.Medium),
                    color = Color.White,
                    maxLines = 1,
                    textAlign = TextAlign.End
                )
            }

            Spacer(Modifier.height(scale.dp(12f)))

            Row(
                Modifier.fillMaxWidth().height(scale.dp(22f)),
                horizontalArrangement = Arrangement.spacedBy(scale.dp(40f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                menuItems.forEach { m ->
                    Text(
                        text = m.label,
                        style = TextStyle(fontSize = scale.sp(16f), fontWeight = FontWeight.Medium),
                        color = MenuIdle,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(scale.dp(8f)))

            Box(Modifier.fillMaxWidth().height(scale.dp(28f)), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.at(0.45f),
                modifier = Modifier.size(scale.dp(28f))
                )
            }
        }
    }
}

@Composable
private fun PlayPauseIcon(
    isPlaying: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    enabled: Boolean = true,
    scale: PlayerPanelScale = PlayerPanelScale(PlayerPanelSize.STANDARD),
) {
    var focused by remember { mutableStateOf(false) }
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .size(scale.touchDpInt(40))
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(RxRadius.full.dp),
        focusLevel = FocusLevel.SUBTLE,
        background = Color.Transparent,
        focusBackground = Color.Transparent,
        contentColor = Color.White,
        drawBorder = false,
        focusScale = 1f,
        enabled = enabled,
        focusRequester = focusRequester,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 2026-11-09：Pause 不在 material-icons-core 里（core 只有 49 个图标），
            // 所以从 extended 的源码里把路径提取出来做成了 res/drawable/ic_pause.xml。
            // PlayArrow 是 core 自带的，继续用。
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = stringResource(R.string.player_pause),
                    tint = Color.White,
                    modifier = Modifier.size(scale.dpInt(40))
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    tint = Color.White,
                    modifier = Modifier.size(scale.dpInt(40))
                )
            }
            if (focused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White, RoundedCornerShape(RxRadius.full.dp))
                )
            }
        }
    }
}

/**
 * 进度条：轨道 5dp、滑块 10dp。
 *
 * 左右键支持**阻尼加速**：短按 10 秒，按住越久步进越大（KeyRouting.seekStepFor）。
 * 每次跳转通过 [onSeekHint] 回传浮层文案。
 */
@Composable
private fun TimelineBar(
    scale: PlayerPanelScale,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val buffered = if (durationMs > 0) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(scale.touchDpInt(40)),
        contentAlignment = Alignment.CenterStart
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(TrackHeight).background(TrackIdle))
        Box(Modifier.fillMaxWidth(buffered).height(TrackHeight).background(Color.White.at(0.55f)))
        Box(Modifier.fillMaxWidth(progress).height(TrackHeight).background(Color.White))
        if (interactive) {
            Box(
                Modifier
                    .offset(x = width * progress - KnobSize / 2)
                    .size(KnobSize)
                    .clip(RoundedCornerShape(RxRadius.full.dp))
                    .background(Color.White)
            )
        }
    }
}

/* ============================================================
 * 选项面板
 * ============================================================ */

@Composable
private fun BoxScope.OptionPanel(
    items: List<PlayerMenuItem>,
    positionMs: Long,
    riseOffset: Dp,
    menuOpen: Boolean,
    panelAlpha: Float,
    skipInfo: SkipInfo,
    skipEditing: SkipField?,
    onSkipChange: (SkipInfo) -> Unit,
    onSkipEditingDone: () -> Unit,
    onClose: () -> Unit,
    panelKeys: PlayerPanelKeyOwnership,
    scale: PlayerPanelScale,
) {
    if (!menuOpen && panelAlpha < 0.01f) return
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.align(Alignment.BottomStart)
            .fillMaxWidth().fillMaxHeight(0.62f)
            .offset(y = riseOffset).alpha(panelAlpha)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.at(0.90f), Color.Black.at(0.96f)))),
        factory = { PlayerOptionsView(it).apply { panelScale = scale } },
        update = {
            it.panelScale = scale
            it.render(items, menuOpen, skipInfo, skipEditing, onSkipChange, onSkipEditingDone, onClose, positionMs, panelKeys)
        },
    )
}

/** 实时下载速度 → 人话（MB/s 或 KB/s） */
private fun formatSpeed(bps: Long): String {
    return com.reelix.app.player.formatNetworkSpeed(bps)
}

/** 缓冲时长 → 秒（一位小数，便于判断"还剩多少缓冲"） */
private fun formatBuffered(ms: Long): String {
    if (ms <= 0) return "0.0s"
    return String.format(Locale.US, "%.1fs", ms / 1000.0)
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}
