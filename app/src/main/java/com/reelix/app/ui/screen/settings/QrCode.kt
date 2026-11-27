package com.reelix.app.ui.screen.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码。
 *
 * 用 zxing 生成 BitMatrix，再用 Canvas 一格一格画。
 * 为什么不转成 Bitmap：少一次内存分配，且缩放时不会有插值模糊 ——
 * 二维码被插值模糊后手机可能扫不出来。
 *
 * 纠错级别用 M（15%）：局域网地址通常 40~60 字符，
 * M 级在这个长度下码点数量适中，屏幕上不会密到扫不出。
 */
@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 248.dp,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val matrix = remember(data) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, hints)
        }.getOrNull()
    }

    if (matrix == null) return

    Canvas(modifier.size(size)) {
        val cols = matrix.width
        val rows = matrix.height
        if (cols <= 0 || rows <= 0) return@Canvas

        // 先铺白底
        drawRect(color = background, size = this.size)

        val cellW = this.size.width / cols
        val cellH = this.size.height / rows

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (!matrix.get(x, y)) continue
                drawRect(
                    color = foreground,
                    topLeft = Offset(x * cellW, y * cellH),
                    // +0.6 防止相邻格之间出现 1px 白缝（浮点取整导致）
                    size = Size(cellW + 0.6f, cellH + 0.6f),
                )
            }
        }
    }
}
