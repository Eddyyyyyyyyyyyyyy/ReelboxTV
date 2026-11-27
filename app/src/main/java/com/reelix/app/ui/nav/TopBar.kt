package com.reelix.app.ui.nav

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelix.app.R
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxRadius
import com.reelix.app.ui.theme.RxSpace
import com.reelix.app.ui.theme.RxType
import com.reelix.app.ui.theme.at

/** 三个一级页面。刻意只有三个：发现 / 我的 / 搜索。设置藏在右上角。 */
enum class TopDest(val labelRes: Int) {
    HOME(R.string.nav_home),
    /** 在线源 —— TVBox 订阅里的站点内容 */
    ONLINE(R.string.nav_online),
    LIBRARY(R.string.nav_library),
    SEARCH(R.string.nav_search),
}

/**
 * 顶部导航 —— 爆米花式的极简做法。
 *
 * 不是底部 TabBar（TV 端底部 TabBar 很难聚焦，手机端也偏重），
 * 而是一条贴着顶部、几乎看不见的轻条：
 *
 *   Reelbox    首页  片库  搜索                              ⚙
 *
 * 选中态只有两处变化：文字变白 + 下方一条 2dp 短横线。
 * 没有背景色块、没有图标、没有角标。
 */
@Composable
fun TopBar(
    current: TopDest,
    onSelect: (TopDest) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onEnterContent: () -> Boolean = { false },
    logoText: String = "Reelbox",
    onOpenSettings: (() -> Unit)? = null,
    /** 首次进入应用时把焦点放到“首页”，之后不再主动抢焦点。 */
    requestInitialFocus: Boolean = false,
    onInitialFocusDone: () -> Unit = {},
) {
    val homeFocus = remember { FocusRequester() }
    var anyNavFocused by remember { mutableStateOf(false) }
    LaunchedEffect(requestInitialFocus) {
        if (!requestInitialFocus) return@LaunchedEffect
        // 导航栏有入场动画；等首帧和一次布局完成后再请求，
        // 避免 requester 尚未绑定导致焦点回落到内容区。
        withFrameNanos { }
        repeat(6) {
            kotlinx.coroutines.delay(50)
            // 用户可能已经在重试窗口内把焦点移到「在线/片库/搜索」，
            // 这时绝不能再把焦点抢回「首页」。
            if (anyNavFocused) {
                onInitialFocusDone()
                return@LaunchedEffect
            }
            if (runCatching { homeFocus.requestFocus() }.isSuccess) {
                onInitialFocusDone()
                return@LaunchedEffect
            }
        }
        onInitialFocusDone()
    }
    if (visible) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown) {
                        if (event.type == KeyEventType.KeyDown) onEnterContent() else true
                    } else false
                }
                .onFocusChanged { anyNavFocused = it.hasFocus }
                .focusGroup()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(RxColors.Backdrop.at(0.92f), Color.Transparent)
                    )
                )
                .padding(
                    start = RxSpace.screenEdge.dp,
                    end = RxSpace.screenEdge.dp,
                    top = 18.dp,
                    bottom = 22.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 品牌字标：极轻，不用图标
            Text(
                text = logoText,
                style = RxType.Section.copy(fontSize = 17.sp, letterSpacing = (-0.3).sp),
                color = RxColors.TextPrimary
            )

            Spacer(Modifier.width(44.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TopDest.entries.forEach { dest ->
                    NavItem(
                        label = stringResource(dest.labelRes),
                        selected = dest == current,
                        onClick = { onSelect(dest) },
                        focusRequester = if (dest == TopDest.HOME) homeFocus else null,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (onOpenSettings != null) {
                SettingsDot(onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val textColor = when {
        focused -> RxColors.TextPrimary
        selected -> RxColors.TextPrimary
        else -> RxColors.TextTertiary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(RxRadius.button.dp))
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
            )

            .onFocusChanged { focused = it.isFocused }
            // ★★ 2026-11-02：这里**不再用 focusProperties 接管 DOWN**。
            //
            // 原方案是「TopBar 的 down 指向内容区首项的 FocusRequester」，
            // 但它引发了**三次** "FocusRequester is not initialized" 崩溃：
            //   1. 片库页只标记 ready 没绑定 requester
            //   2. 切页时 ready 没清，TopBar 指向已销毁的 requester
            //   3. 即使加了双层判断，ready 与绑定之间仍有时间窗
            //
            // 根本问题：**跨组件的焦点 requester 生命周期无法可靠同步**。
            // 收益（DOWN 能进内容区）远小于代价（随机崩溃）。
            //
            // 现在退回 Compose 原生焦点搜索：TopBar 用 focusGroup 包住，
            // 内容区自己也是焦点组，两者靠**几何位置**自然衔接。
            // 代价是某些布局下 DOWN 可能需要多按一次，但**不会崩**。
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(
                if (focused) Color.White.at(0.12f) else Color.Transparent,
                RoundedCornerShape(RxRadius.button.dp)
            )
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) RxColors.Accent else Color.Transparent,
                RoundedCornerShape(RxRadius.button.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = RxType.CardMeta.copy(fontSize = 13.sp),
            color = textColor
        )
        Spacer(Modifier.height(5.dp))
        // 选中/焦点短横线
        Box(
            Modifier
                .height(2.dp)
                .width(if (selected) 16.dp else if (focused) 12.dp else 0.dp)
                .clip(RoundedCornerShape(RxRadius.full.dp))
                .background(if (focused) RxColors.Accent else RxColors.TextPrimary)
        )
    }
}

/** 设置入口：一个很小的圆点，不用齿轮图标（齿轮太工业了） */
@Composable
private fun SettingsDot(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(36.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(RxRadius.full.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (focused) Color.White.at(0.10f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(RxRadius.full.dp))
                        .background(if (focused) RxColors.TextPrimary else RxColors.TextTertiary)
                )
            }
        }
    }
}
