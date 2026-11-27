package com.reelix.app.ui.screen.player

import android.content.Context

fun markSkipAt(info: SkipInfo, field: SkipField, positionMs: Long, durationMs: Long): SkipInfo? {
    if (durationMs <= 0) return null
    val time = positionMs.coerceIn(0, durationMs)
    return when (field) {
        SkipField.INTRO -> info.copy(introEndMs = time)
        SkipField.OUTRO -> info.copy(outroStartMs = time)
    }
}

/**
 * 片头/片尾跳过设置 —— **按剧保存**（一部剧一份，不是按集）。
 *
 * - [introEndMs]：片头结束时间（从 0 播到这里就跳过片头）
 * - [outroStartMs]：片尾开始时间（播到这里就跳过片尾）
 */
data class SkipInfo(
    val introEndMs: Long = 0L,
    val outroStartMs: Long = 0L,
) {
    val enabled: Boolean get() = introEndMs > 0 || outroStartMs > 0
}

/** 正在编辑哪个字段（片头 or 片尾），null 表示非编辑态 */
enum class SkipField { INTRO, OUTRO }

/**
 * 跳过设置的本地存储。
 *
 * 为什么用 SharedPreferences 而不是 Room：
 *  数据量极小（每部剧两个 long），Room 要建表 + 迁移，杀鸡用牛刀。
 *  key 用 mediaId 区分，和 SettingsRepository 的 SharedPreferences 风格一致。
 */
object SkipStore {
    private const val PREFS = "reelix_skip"

    fun load(context: Context, mediaId: String): SkipInfo {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SkipInfo(
            introEndMs = p.getLong("${mediaId}_intro", 0L),
            outroStartMs = p.getLong("${mediaId}_outro", 0L),
        )
    }

    fun save(context: Context, mediaId: String, info: SkipInfo) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("${mediaId}_intro", info.introEndMs)
            .putLong("${mediaId}_outro", info.outroStartMs)
            .apply()
    }
}
