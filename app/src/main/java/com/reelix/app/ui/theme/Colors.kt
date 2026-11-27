package com.reelix.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Reelbox 色板 —— 网易爆米花式极简：深色底 + 单一暖色点缀 + 大量中性灰。
 * 关键原则：屏幕上永远只有 1 个彩色元素（强调色），其余全是灰度。
 * 这样海报本身才是唯一的视觉主角。
 */
object RxColors {
    /** 影院熄灯后的黑，带一点点冷调，不是纯黑（纯黑 OLED 上和内容边界糊在一起） */
    val Backdrop = Color(0xFF0B0B0D)
    /** 一级容器：卡片底、导航条 */
    val Surface = Color(0xFF141418)
    /** 二级容器：输入框、未选中按钮 */
    val SurfaceElevated = Color(0xFF1E1E24)
    /** 三级：hover / focus 底色 */
    val SurfaceHigh = Color(0xFF2A2A32)

    /** 主文字 */
    val TextPrimary = Color(0xFFF2F2F4)
    /** 次文字：年份、时长、简介 */
    val TextSecondary = Color(0xFF8A8A93)
    /** 弱文字：分隔符、占位 */
    val TextTertiary = Color(0xFF55555F)

    /** 强调色：播放按钮、选中态、进度条。暖黄油色，不是霓虹 */
    val Accent = Color(0xFFFFD466)
    /** 强调色的深一档，用于按下态 */
    val AccentPressed = Color(0xFFE5B84A)

    /** 聚焦描边（TV 焦点）—— 半透明白，克制 */
    val FocusRing = Color(0xFFFFFFFF)
    /** 收藏红 */
    val Favorite = Color(0xFFFF5A6E)

    /** 海报占位（加载中/无图），用中性灰而不是彩色，避免抢戏 */
    val PosterPlaceholder = Color(0xFF232329)
    /** 海报占位渐变末端 */
    val PosterPlaceholderEnd = Color(0xFF16161A)

    /** 播放页控制层遮罩 */
    val Scrim = Color(0xCC000000)
    val ScrimLight = Color(0x66000000)
}

/**
 * 间距系统 —— 8dp 基准，但爆米花风格要"松弛"，
 * 所以区块级留白用 24/32，信息内部用 4/8/12。
 */
object RxSpace {
    val xs = 4
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 24
    val xxl = 32
    val section = 40
    val screenEdge = 48
}

/** 圆角 —— 爆米花风格偏小圆角，不做胶囊 */
object RxRadius {
    val poster = 12
    val card = 10
    val button = 8
    val sheet = 20
    val full = 999
}
