package com.reelix.app.ui.theme.layout

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import com.reelix.app.data.model.Episode
import com.reelix.app.ui.component.episodeCardPresentation

internal fun declaredEpisodeBadges(episode: Episode?): List<String> {
    val info = episode?.let(::episodeCardPresentation) ?: return emptyList()
    val resolution = when (info.resolution?.lowercase()) {
        "2160p", "4k" -> "4K"
        else -> info.resolution?.uppercase()
    }
    return listOfNotNull(resolution) + info.variants.filter {
        it.uppercase() in setOf("HDR", "HDR10", "HDR10+", "DOLBY VISION", "DOLBY.VISION", "DV")
    }
}

internal fun themeMetadata(view: TextView, tokens: List<String>, badges: List<String>): CharSequence {
    val result = SpannableStringBuilder()
    val scale = view.resources.displayMetrics.widthPixels / 1920f
    (tokens.filter { it.isNotBlank() }.map { it to false } + badges.distinct().map { it to true }).forEach { (label, badge) ->
        if (result.isNotEmpty()) {
            val start = result.length
            result.append(" ")
            result.setSpan(object : ReplacementSpan() {
                override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, metrics: Paint.FontMetricsInt?) =
                    (17 * scale).toInt()
                override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, horizontal: Float,
                    top: Int, baseline: Int, bottom: Int, paint: Paint) = Unit
            }, start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val start = result.length
        result.append(label)
        if (badge) result.setSpan(object : ReplacementSpan() {
            private fun textPaint(paint: Paint) = Paint(paint).apply { textSize = paint.textSize * 19f / 24f; color = 0xFFF5F5F7.toInt() }
            override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, metrics: Paint.FontMetricsInt?) =
                (textPaint(paint).measureText(label) + 18 * scale).toInt()
            override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, horizontal: Float,
                top: Int, baseline: Int, bottom: Int, paint: Paint) {
                val letters = textPaint(paint)
                val metrics = letters.fontMetrics
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x66FFFFFF
                    style = Paint.Style.STROKE
                    strokeWidth = scale.coerceAtLeast(.5f)
                }
                canvas.drawRoundRect(horizontal, baseline + metrics.ascent - 4 * scale,
                    horizontal + letters.measureText(label) + 18 * scale, baseline + metrics.descent + 4 * scale,
                    3 * scale, 3 * scale, stroke)
                canvas.drawText(label, horizontal + 9 * scale, baseline.toFloat(), letters)
            }
        }, start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return result
}
