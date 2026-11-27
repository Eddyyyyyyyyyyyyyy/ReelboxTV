package com.reelix.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版 —— 爆米花式的克制：
 * - 不用超粗字重（最多 SemiBold），靠字距和留白建立层级
 * - 标题不加粗但加大字号，正文压低对比度
 * - 字距：标题略收紧（-0.5sp），正文正常
 */
private val Sans = FontFamily.Default

object RxType {
    /** 详情页大标题 */
    val Display = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.8).sp
    )
    /** 首页 Hero 标题 */
    val Hero = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.6).sp
    )
    /** 区块标题（继续观看 / 最近添加） */
    val Section = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp
    )
    /** 卡片标题（海报下方片名） */
    val CardTitle = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp
    )
    /** 卡片副标题（年份·类型） */
    val CardMeta = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp
    )
    /** 正文 */
    val Body = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp
    )
    /** 简介正文，行高放大提高可读性 */
    val BodyRelaxed = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp
    )
    /** 按钮文字 */
    val Button = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp
    )
    /** 标签 / 角标 */
    val Label = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp
    )
}

val RxTypography = Typography(
    displayLarge = RxType.Display,
    headlineLarge = RxType.Hero,
    titleLarge = RxType.Section,
    titleMedium = RxType.CardTitle,
    bodyLarge = RxType.Body,
    bodyMedium = RxType.BodyRelaxed,
    bodySmall = RxType.CardMeta,
    labelLarge = RxType.Button,
    labelSmall = RxType.Label
)
