package com.reelix.app.ui.screen.player

/**
 * 续播点 —— 从数据库读出来的"上次看到哪"。
 *
 * 只保留播放页需要的两个字段：
 *  - [episodeIndex]：第几集（电影恒为 1）
 *  - [positionMs]：这一集里的毫秒位置
 *
 * 线路不用带上：直接沿用详情页当前选中的线路即可，
 * 因为线路是技术细节，用户记不住也不需要记。
 */
data class ResumePoint(
    val episodeIndex: Int,
    val positionMs: Long,
)
