package com.reelix.app.ui.screen.panlogin

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.reelix.app.data.pan.PanCookieStore
import com.reelix.app.data.pan.QuarkQrLogin
import com.reelix.app.ui.component.FocusLevel
import com.reelix.app.ui.component.FocusableSurface
import com.reelix.app.ui.theme.RxColors
import com.reelix.app.ui.theme.RxRadius
import com.reelix.app.ui.theme.RxSpace
import com.reelix.app.ui.theme.RxType
import com.reelix.app.ui.theme.at
import kotlinx.coroutines.delay

/**
 * 网盘扫码登录页 —— 夸克网盘。
 *
 * 之前的 WebView 方案实测失败：那个 jar 的配置页返回空列表，
 * 授权项只是卡片，detailContent 也返回空对象 —— jar 根本不提供扫码界面。
 *
 * 所以直接对接夸克官方二维码 API：
 * 1. getTokenForQrcodeLogin 拿 token
 * 2. 二维码 = su.quark.cn/...?token=xxx
 * 3. 用户扫码确认
 * 4. getServiceTicketByQrcodeToken 拿 ticket
 * 5. account/info?st=ticket 从 Set-Cookie 提取凭据
 * 6. 写进 jar 的 prefs
 */
@Composable
fun PanLoginScreen(
    onBack: () -> Unit,
    onAuthorized: () -> Unit,
    onFallback: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var state by remember { mutableStateOf<QuarkQrLogin.State?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在获取二维码…") }
    var done by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableIntStateOf(0) }
    var cookiePreview by remember { mutableStateOf<String?>(null) }

    val refreshFocus = remember { FocusRequester() }
    BackHandler(onBack = onBack)

    LaunchedEffect(tick) {
        done = false
        cookiePreview = null
        statusText = "正在获取二维码…"
        qrBitmap = null

        val s = QuarkQrLogin.requestQr()
        state = s
        when (s) {
            is QuarkQrLogin.State.Waiting -> {
                qrBitmap = makeQr(s.qrContent, 420)
                statusText = "请用夸克 App 扫描二维码并确认登录"
                elapsed = 0
            }
            is QuarkQrLogin.State.Error -> statusText = "获取二维码失败：${s.message}"
            else -> statusText = "获取二维码失败"
        }
    }

    LaunchedEffect(state) {
        val waiting = state as? QuarkQrLogin.State.Waiting ?: return@LaunchedEffect
        var localElapsed = 0

        while (localElapsed < 180) {
            delay(2000)
            localElapsed += 2
            elapsed = localElapsed

            when (val r = QuarkQrLogin.poll(waiting.token)) {
                is QuarkQrLogin.State.Confirmed -> {
                    statusText = "已确认，正在获取登录凭据…"

                    val cookie = QuarkQrLogin.exchangeCookie(r.serviceTicket)
                    if (cookie.isNullOrBlank()) {
                        statusText = "拿到授权但换取 cookie 失败，请刷新重试"
                        return@LaunchedEffect
                    }

                    val ok = PanCookieStore.save(context, PanCookieStore.Provider.QUARK, cookie)
                    cookiePreview = cookie.take(80) + if (cookie.length > 80) "…" else ""
                    done = true
                    statusText = if (ok) {
                        "登录成功，凭据已写入播放组件"
                    } else {
                        "登录成功，但未找到播放组件存储（重启后可能需重登）"
                    }
                    return@LaunchedEffect
                }

                is QuarkQrLogin.State.Error -> {
                    statusText = "轮询出错：${r.message}"
                    return@LaunchedEffect
                }

                else -> Unit
            }
        }

        statusText = "二维码已超时，请点刷新二维码"
    }

    LaunchedEffect(Unit) {
        delay(300)
        runCatching { refreshFocus.requestFocus() }
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
            Column(Modifier.weight(1f)) {
                Text(
                    "夸克网盘登录",
                    style = RxType.Section.copy(fontSize = 18.sp),
                    color = RxColors.TextPrimary
                )
                Text(
                    "登录后玩偶、多多等网盘源才能播放",
                    style = RxType.CardMeta.copy(fontSize = 11.sp),
                    color = RxColors.TextTertiary
                )
            }

            FocusableSurface(
                onClick = { tick++ },
                modifier = Modifier
                    .width(120.dp)
                    .height(42.dp)
                    .focusRequester(refreshFocus),
                shape = RoundedCornerShape(RxRadius.button.dp),
                focusLevel = FocusLevel.GLOW,
                background = Color.White,
                contentColor = Color.Black,
            ) {
                Text("刷新二维码", style = RxType.Button, color = Color.Black)
            }

            if (onFallback != null) {
                Spacer(Modifier.width(12.dp))
                FocusableSurface(
                    onClick = onFallback,
                    modifier = Modifier
                        .width(120.dp)
                        .height(42.dp),
                    shape = RoundedCornerShape(RxRadius.button.dp),
                    focusLevel = FocusLevel.LIFT,
                    background = Color.White.at(0.10f),
                    contentColor = RxColors.TextPrimary,
                    borderColor = Color.White.at(0.14f),
                ) {
                    Text("改用网页登录", style = RxType.Button, color = RxColors.TextPrimary)
                }
            }

            if (done) {
                Spacer(Modifier.width(12.dp))
                FocusableSurface(
                    onClick = onAuthorized,
                    modifier = Modifier
                        .width(96.dp)
                        .height(42.dp),
                    shape = RoundedCornerShape(RxRadius.button.dp),
                    focusLevel = FocusLevel.GLOW,
                    background = RxColors.Accent,
                    contentColor = Color.Black,
                ) {
                    Text("完成", style = RxType.Button, color = Color.Black)
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(440.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "夸克登录二维码",
                            modifier = Modifier.size(400.dp)
                        )
                    } else {
                        Text(
                            "二维码加载中…",
                            style = RxType.Body,
                            color = Color(0xFF222222)
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))

                Text(
                    text = statusText,
                    style = RxType.Body,
                    color = when {
                        done -> Color(0xFF6BD08B)
                        statusText.contains("失败") || statusText.contains("超时") -> RxColors.Favorite
                        else -> RxColors.TextSecondary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )

                if (elapsed > 0 && !done) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已等待 ${elapsed} 秒",
                        style = RxType.CardMeta.copy(fontSize = 11.sp),
                        color = RxColors.TextTertiary
                    )
                }

                cookiePreview?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "凭据：$it",
                        style = RxType.CardMeta.copy(fontSize = 10.sp),
                        color = RxColors.TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 文本 → 二维码 Bitmap */
private fun makeQr(text: String, size: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) 0xFF0A0A0C.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    bmp
}.getOrNull()
