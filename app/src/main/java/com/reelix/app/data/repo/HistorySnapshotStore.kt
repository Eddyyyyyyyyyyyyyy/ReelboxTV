package com.reelix.app.data.repo

import android.content.Context
import com.reelix.app.data.db.PlayProgressEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 首页历史的首帧快照。
 *
 * Room Flow 的第一次发射晚于 Compose 首帧；仅把空列表当成初始值，会短暂
 * 画出“还没有播放历史”。这里存的是只读展示副本，**不是新的数据真相**：
 * Room 一返回就覆盖它，删除/清空历史也会同步覆盖。
 */
object HistorySnapshotStore {

    private const val FILE_NAME = "history-snapshot.json"
    private const val VERSION = 1

    @Serializable
    private data class Stored(
        val version: Int = VERSION,
        val savedAt: Long = 0L,
        val entries: List<StoredEntry> = emptyList(),
    )

    @Serializable
    private data class StoredEntry(
        val mediaId: String = "",
        val title: String = "",
        val posterRes: String? = null,
        val posterUrl: String? = null,
        val episodeIndex: Int = 1,
        val episodeName: String = "",
        val routeName: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val updatedAt: Long = 0L,
        val finished: Boolean = false,
        val sourceKey: String = "",
        val sourceId: String = "",
        val originalTitle: String? = null,
        val year: Int? = null,
        val rating: Double? = null,
        val kindName: String = "",
        val overview: String? = null,
        val backdropUrl: String? = null,
    ) {
        fun toEntity() = PlayProgressEntity(
            mediaId = mediaId,
            title = title,
            posterRes = posterRes,
            posterUrl = posterUrl,
            episodeIndex = episodeIndex,
            episodeName = episodeName,
            routeName = routeName,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = updatedAt,
            finished = finished,
            sourceKey = sourceKey,
            sourceId = sourceId,
            originalTitle = originalTitle,
            year = year,
            rating = rating,
            kindName = kindName,
            overview = overview,
            backdropUrl = backdropUrl,
        )

        companion object {
            fun from(entry: PlayProgressEntity) = StoredEntry(
                mediaId = entry.mediaId,
                title = entry.title,
                posterRes = entry.posterRes,
                posterUrl = entry.posterUrl,
                episodeIndex = entry.episodeIndex,
                episodeName = entry.episodeName,
                routeName = entry.routeName,
                positionMs = entry.positionMs,
                durationMs = entry.durationMs,
                updatedAt = entry.updatedAt,
                finished = entry.finished,
                sourceKey = entry.sourceKey,
                sourceId = entry.sourceId,
                originalTitle = entry.originalTitle,
                year = entry.year,
                rating = entry.rating,
                kindName = entry.kindName,
                // 截断而不是丢弃：Hero 要用它显示两行简介（HomeScreen 里读的是
                // item.overview）。只截长度，不改含义，Room 数据一到就换成完整版。
                overview = entry.overview?.take(SNAPSHOT_OVERVIEW_MAX),
                backdropUrl = entry.backdropUrl,
            )
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Synchronous read: this runs before the first Compose frame. */
    fun load(context: Context): List<PlayProgressEntity>? = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        // ★ 体积保护：这个文件是在**首帧之前同步读**的（为了 Hero 不闪），
        //   2026-11-05 起加了上限。超过 200KB 就不在启动路径上解它，
        //   直接交给 Room 异步出数据 —— 宁可晚一帧，也不要卡首帧。
        if (file.length() > 200 * 1024) {
            android.util.Log.w("HistorySnapshot", "快照过大(${file.length()}B)，跳过同步加载")
            return null
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        val stored = json.decodeFromString(Stored.serializer(), file.readText(Charsets.UTF_8))
        android.util.Log.i(
            "HistorySnapshot",
            "同步加载 ${stored.entries.size} 条，耗时 ${android.os.SystemClock.uptimeMillis() - t0}ms，${file.length()}B"
        )
        if (stored.version != VERSION) return null
        // 空快照不能证明用户当前真的没有历史，交给 Room 决定。
        stored.entries.takeIf { it.isNotEmpty() }?.map { it.toEntity() }
    }.getOrNull()

    fun save(context: Context, entries: List<PlayProgressEntity>) {
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val body = json.encodeToString(
                Stored.serializer(),
                Stored(
                    entries = entries.take(MAX_ITEMS).map { StoredEntry.from(it) },
                    savedAt = System.currentTimeMillis(),
                ),
            )
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    /**
     * 快照最多存多少条。
     *
     * ★ 2026-11-06：200 → 60。
     *
     * 这个文件是在**首帧之前同步读+反序列化**的，条数直接决定启动耗时。
     * 但首页 `LazyVerticalGrid` 一屏最多也就摆 10~12 张卡，60 条足够覆盖
     * 首屏加两次翻页；Room 的 Flow 在几十毫秒内就会补上全部历史。
     *
     * 之前是 200 条，加上每条带完整简介，文件轻松超过 200KB，触发
     * [load] 里的体积保护 → 直接返回 null → 首页退化成"正在读取播放历史…"
     * 空白等 Room。**表现就是"越用越慢"**，不是某次改坏了。
     */
    private const val MAX_ITEMS = 60

    /**
     * 快照里保存的简介长度上限。
     *
     * Hero 只显示 2 行（约 40 字），160 字远远够用；而源站的简介常有
     * 200~500 字，截断后单条体积能降一半以上。
     *
     * 代价可接受：快照只是**首帧的临时展示副本**，Room 一到就覆盖，
     * 所以详情页拿到的简介仍是完整的。
     */
    private const val SNAPSHOT_OVERVIEW_MAX = 160
}
