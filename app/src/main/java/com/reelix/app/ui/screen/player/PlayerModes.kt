package com.reelix.app.ui.screen.player

/**
 * 画面比例模式。
 *
 * ## 为什么需要
 *
 * 不同源的画面尺寸差异很大：
 * ```
 * 16:9  正常电影/剧集
 * 4:3   老片、部分日剧、动画
 * 2.35:1 宽银幕（上下自带黑边）
 * 1:1   部分短剧
 * 竖屏   手机短剧（9:16）
 * ```
 *
 * 默认的 `FIT` 会完整显示但可能留大黑边；`FILL` 会拉伸变形；
 * `CROP` 铺满但可能切掉字幕。用户需要能切。
 *
 * ## 对齐 TVBox
 *
 * TVBox 的播放器有这个切换（`PlayActivity.java` 里叫"画面比例"），
 * 循环切换 4 档。
 */
enum class AspectMode(val label: String) {
    /** 适应屏幕：完整显示，可能留黑边 */
    FIT("适应"),

    /** 拉伸铺满：填满屏幕但可能变形 */
    FILL("拉伸"),

    /** 裁剪铺满：保持比例但切掉边缘 */
    CROP("裁剪"),

    /** 原始尺寸：不做任何缩放 */
    ORIGINAL("原始");

    /** 循环切下一档 */
    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

/** Device software codecs are limited; no FFmpeg video renderer is bundled. */
fun com.reelix.app.player.DecoderMode.labelRes(): Int = when (this) {
    com.reelix.app.player.DecoderMode.AUTO -> com.reelix.app.R.string.decoder_auto
    com.reelix.app.player.DecoderMode.HARDWARE -> com.reelix.app.R.string.decoder_hardware
    com.reelix.app.player.DecoderMode.SOFTWARE -> com.reelix.app.R.string.decoder_software
}

/**
 * 提供「在解码能力范围内找到最佳尺寸」的辅助。
 */
object AspectHelper {

    /**
     * 把画面比例模式换算成 ExoPlayer 的缩放参数。
     *
     * @return `(缩放系数, 是否用内容框)`
     * ```
     * FIT      → (1.0, true)   适应窗口
     * FILL     → (1.0, false)  拉伸填满
     * CROP     → 需要按比例算
     * ORIGINAL → (1.0, false)  不缩放
     * ```
     */
    fun scaleFor(
        mode: AspectMode,
        videoWidth: Int,
        videoHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Float {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1f

        val videoRatio = videoWidth.toFloat() / videoHeight
        val viewRatio = viewWidth.toFloat() / viewHeight

        return when (mode) {
            AspectMode.FIT -> 1f
            AspectMode.FILL -> 1f
            AspectMode.ORIGINAL -> {
                // 原始像素尺寸：视频比视图小时不放大，比视图大时缩小
                minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
            }
            AspectMode.CROP -> {
                // 裁剪：放大到刚好铺满
                if (videoRatio > viewRatio) {
                    // 视频更宽 → 按高度铺满
                    (viewHeight.toFloat() / videoHeight) * (videoRatio / viewRatio)
                } else {
                    (viewWidth.toFloat() / videoWidth) * (viewRatio / videoRatio)
                }
            }
        }
    }
}
