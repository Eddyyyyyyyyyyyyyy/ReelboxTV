package com.reelix.app.ui.theme.layout

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView

internal class ThemeActionGlyph(private val owner: TextView, val kind: String, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        paint.color = owner.currentTextColor
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (kind) {
            "play" -> canvas.drawPath(Path().apply { moveTo(5f, 3f); lineTo(20f, 12f); lineTo(5f, 21f); close() }, paint)
            "info" -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(12f, 11f, 12f, 17f, paint)
                canvas.drawCircle(12f, 7f, .5f, paint)
            }
            "layers" -> {
                canvas.drawPath(Path().apply { moveTo(3f, 7f); lineTo(12f, 2f); lineTo(21f, 7f); lineTo(12f, 12f); close() }, paint)
                canvas.drawPath(Path().apply { moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f); moveTo(3f, 17f); lineTo(12f, 22f); lineTo(21f, 17f) }, paint)
            }
            "image" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 2f, 2f, paint)
                canvas.drawCircle(8f, 8f, 2f, paint)
                canvas.drawPath(Path().apply { moveTo(4f, 18f); lineTo(10f, 12f); lineTo(14f, 16f); lineTo(18f, 11f); lineTo(21f, 15f) }, paint)
            }
            "settings" -> {
                canvas.drawLine(3f, 6f, 21f, 6f, paint)
                canvas.drawLine(3f, 18f, 21f, 18f, paint)
                canvas.drawCircle(8f, 6f, 3f, paint)
                canvas.drawCircle(16f, 18f, 3f, paint)
            }
            "next" -> canvas.drawPath(Path().apply { moveTo(8f, 4f); lineTo(16f, 12f); lineTo(8f, 20f) }, paint)
            "down" -> canvas.drawPath(Path().apply { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }, paint)
        }
        canvas.restore()
    }
    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class ThemeGlyphSpan(
    val icon: ThemeActionGlyph,
    val size: Int,
    val gap: Int,
    val trailing: Boolean,
) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, metrics: Paint.FontMetricsInt?): Int {
        metrics?.let {
            val original = paint.fontMetricsInt
            val extra = (size - (original.descent - original.ascent)).coerceAtLeast(0)
            it.ascent = original.ascent - extra / 2
            it.top = original.top - extra / 2
            it.descent = original.descent + extra - extra / 2
            it.bottom = original.bottom + extra - extra / 2
        }
        return size + gap
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, horizontal: Float,
        top: Int, baseline: Int, bottom: Int, paint: Paint) {
        val metrics = paint.fontMetricsInt
        val iconTop = baseline + (metrics.ascent + metrics.descent - size) / 2
        canvas.save()
        canvas.translate(horizontal + if (trailing) gap else 0, iconTop.toFloat())
        icon.setBounds(0, 0, size, size)
        icon.draw(canvas)
        canvas.restore()
    }
}

internal fun setThemeGlyph(view: TextView, kind: String, trailing: Boolean = false, referenceSize: Int = 29) {
    val scale = view.resources.displayMetrics.widthPixels / 1920f
    val size = (referenceSize * scale).toInt().coerceAtLeast(1)
    val plain = view.text.toString().replace("\uFFFC", "").trim()
    val old = (view.text as? android.text.Spanned)?.getSpans(0, view.text.length, ThemeGlyphSpan::class.java)?.firstOrNull()
    if (old?.icon?.kind == kind && old.size == size && old.trailing == trailing) return
    view.setCompoundDrawablesRelative(null, null, null, null)
    val content = android.text.SpannableString(if (trailing) "$plain\uFFFC" else "\uFFFC$plain")
    val index = if (trailing) plain.length else 0
    content.setSpan(ThemeGlyphSpan(ThemeActionGlyph(view, kind, size), size,
        if (plain.isBlank()) 0 else (12 * scale).toInt(), trailing), index, index + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    view.text = content
    if (plain.isNotBlank()) view.contentDescription = plain
}
