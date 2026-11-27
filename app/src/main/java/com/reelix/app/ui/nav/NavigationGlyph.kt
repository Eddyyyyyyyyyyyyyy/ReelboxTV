package com.reelix.app.ui.nav

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal class NavigationGlyph(private val destination: TopDest, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (destination) {
            TopDest.HOME -> {
                val roof = Path().apply { moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f) }
                canvas.drawPath(roof, paint)
                canvas.drawPath(Path().apply { moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f)
                    lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f) }, paint)
            }
            TopDest.ONLINE -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, paint)
                canvas.drawPath(Path().apply { moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close() }, paint)
            }
            TopDest.LIBRARY -> {
                canvas.drawRoundRect(5f, 5f, 21f, 21f, 1f, 1f, paint)
                canvas.drawLine(2f, 2f, 18f, 2f, paint)
                canvas.drawLine(2f, 2f, 2f, 18f, paint)
                canvas.drawLine(9f, 10f, 17f, 10f, paint)
                canvas.drawLine(9f, 15f, 17f, 15f, paint)
            }
            TopDest.SEARCH -> {
                canvas.drawCircle(10f, 10f, 6.5f, paint)
                canvas.drawLine(15f, 15f, 21f, 21f, paint)
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
