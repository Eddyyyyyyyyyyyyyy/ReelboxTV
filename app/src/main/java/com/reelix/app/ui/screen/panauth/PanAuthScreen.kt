package com.reelix.app.ui.screen.panauth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelix.app.data.pan.PanCookieStore
import com.reelix.app.data.pan.WebLoginHelper
import com.reelix.app.ui.component.FocusLevel
import com.reelix.app.ui.component.FocusableSurface
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxRadius
import com.reelix.app.ui.theme.RxSpace
import com.reelix.app.ui.theme.RxType
import com.reelix.app.ui.theme.at
import kotlinx.coroutines.delay

/**
 * 网盘凭据页。
 *
 * 实测结论（2026-11-23，电视真机）：Wex 系列 jar 把网盘登录态存在自己的
 * `spUtils` 里，键名 `quarkSt` / `quark_ck`，且 `quark_ck` 是加密串。
 * 宿主写明文 cookie 进去，jar 读不到，播放时仍会弹自己的二维码。
 *
 * 所以这里不再承诺"授权后免扫码"，只做凭据管理：
 * - 显示哪些网盘保存过登录信息
 * - 支持逐个清除（多域名 + 父域一起清）
 * - 手动粘贴仅作为记录，不代表播放免扫码
 */
@Composable
fun PanAuthScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialProvider: PanCookieStore.Provider? = null,
) {
    val context = LocalContext.current

    var savedTick by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    val configured = remember(savedTick) {
        PanCookieStore.Provider.entries.filter { PanCookieStore.isConfigured(context, it) }.toSet()
    }

    val firstActionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(250)
        runCatching { firstActionFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RxColors.Backdrop)
            .padding(top = 28.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RxSpace.screenEdge.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "网盘凭据",
                    style = RxType.Section.copy(fontSize = 18.sp),
                    color = RxColors.TextPrimary
                )
                Text(
                    "只记录已保存的登录信息，播放时可能仍需在源内扫码",
                    style = RxType.CardMeta.copy(fontSize = 11.sp),
                    color = RxColors.TextTertiary
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        LazyColumn(
            contentPadding = PaddingValues(
                start = RxSpace.screenEdge.dp,
                end = RxSpace.screenEdge.dp,
                bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item("hint") {
                Text(
                    text = "这里只负责查看和清除。部分网盘源的播放组件把登录态存在自己的加密存储里，" +
                        "外部保存的 Cookie 不会被读取，所以这里不再提供扫码授权。\n" +
                        "清除会同时清掉 WebView 的 cookie 与网页存储，其他网盘在网页里的登录态也会一并失效。",
                    style = RxType.CardMeta.copy(fontSize = 12.sp),
                    color = RxColors.TextTertiary
                )
                Spacer(Modifier.height(6.dp))
            }

            items(PanCookieStore.Provider.entries.size) { idx ->
                val p = PanCookieStore.Provider.entries[idx]
                val isConfigured = p in configured
                val highlighted = initialProvider == p

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color.White.at(0.04f),
                            RoundedCornerShape(RxRadius.button.dp)
                        )
                        .padding(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = RxType.Body, color = RxColors.TextPrimary)
                            Text(
                                if (isConfigured) "已保存凭据" else "未保存凭据",
                                style = RxType.CardMeta.copy(fontSize = 11.sp),
                                color = if (isConfigured) Color(0xFF6BD08B) else RxColors.TextTertiary
                            )
                        }

                        FocusableSurface(
                            onClick = {
                                WebLoginHelper.clearCookie(context, p)
                                savedTick++
                            },
                            modifier = Modifier
                                .width(120.dp)
                                .height(40.dp)
                                .then(if (idx == 0) Modifier.focusRequester(firstActionFocus) else Modifier),
                            shape = RoundedCornerShape(RxRadius.button.dp),
                            focusLevel = FocusLevel.GLOW,
                            background = Color.White.at(0.06f),
                            contentColor = Color(0xFFFF8A80),
                            borderColor = Color.White.at(0.10f),
                        ) {
                            Text("清除凭据", style = RxType.Button, color = Color(0xFFFF8A80))
                        }
                    }

                }
            }
        }
    }
}
