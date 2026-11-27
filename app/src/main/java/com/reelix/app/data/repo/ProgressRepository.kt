package com.reelix.app.data.repo

import android.content.Context
import androidx.room.Room
import com.reelix.app.data.db.PlayProgressDao
import com.reelix.app.data.db.PlayProgressEntity
import com.reelix.app.data.db.ReelixDatabase
import com.reelix.app.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 播放进度仓库。
 *
 * 写入节流策略（关键，别让数据库被播放器拖垮）：
 *  播放器每 250ms 回调一次位置，但数据库**不能**跟着写 250ms 一次。
 *  这里用 [shouldPersist] 做两道闸：
 *    1. 时间闸：距上次写入 > 3 秒才写
 *    2. 位移闸：位置变化 > 2 秒才写（避免暂停时反复写同一条）
 *  暂停 / 退出 / 切换集时再强制写一次，保证不丢。
 */
class ProgressRepository(
    private val dao: PlayProgressDao,
) {

    /** 继续观看（首页用） */
    fun observeContinueWatching(limit: Int = 20): Flow<List<PlayProgressEntity>> =
        dao.observeContinueWatching(limit)

    /**
     * 全部播放历史（首页用，按最近播放排序）。
     *
     * 和 observeContinueWatching 的区别：那个带 limit，是给"继续观看"用的；
     * 首页是完整的历史页，要显示全部记录，所以单开一个不带上限的方法。
     */
    fun observeAllHistory(): Flow<List<PlayProgressEntity>> = dao.observeAll()

    /**
     * First database value for the initial paint.
     *
     * Room's Flow starts later than Compose's first frame. Without this read,
     * HomeScreen briefly renders “no history” even when history exists.
     */
    suspend fun snapshotAll(): List<PlayProgressEntity> = withContext(Dispatchers.IO) {
        dao.observeAll().first()
    }

    /** 全部进度（片库页标进度条用），转成 Map 方便查 */
    fun observeProgressMap(): Flow<Map<String, PlayProgressEntity>> =
        dao.observeAll().map { list -> list.associateBy { it.mediaId } }

    suspend fun find(mediaId: String): PlayProgressEntity? = withContext(Dispatchers.IO) {
        dao.find(mediaId)
    }

    suspend fun updateBackdropIfEmpty(mediaId: String, backdropUrl: String?) =
        withContext(Dispatchers.IO) {
            backdropUrl?.takeIf { it.isNotBlank() }?.let { dao.updateBackdropIfEmpty(mediaId, it) }
        }

    suspend fun migrateLibrary(context: Context, entries: List<com.reelix.app.data.alist.AlistLibraryBuilder.LibraryEntry>) =
        withContext(Dispatchers.IO) {
            val records = snapshotAll()
            val changes = records.mapNotNull { old -> migratedLibraryRecord(entries, old)?.let { old to it } }
            if (changes.isEmpty()) return@withContext
            // Keep the original data outside Room before touching identities; ambiguous records stay in Room.
            val atomic = android.util.AtomicFile(java.io.File(context.filesDir, "library-history-migration-backup.json"))
            val codec = Json { ignoreUnknownKeys = true }
            val backup = if (atomic.baseFile.exists() || java.io.File(atomic.baseFile.path + ".bak").exists()) atomic.openRead().bufferedReader().use {
                codec.decodeFromString<List<PlayProgressEntity>>(it.readText())
            } else emptyList()
            val output = atomic.startWrite()
            try {
                output.write(codec.encodeToString((backup + records).distinctBy { it.mediaId to it.updatedAt }).toByteArray())
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            changes.forEach { (old, replacement) -> dao.migrateLibraryRecord(old, replacement) }
        }

    /**
     * 保存进度。
     * @param force true = 忽略节流（暂停/退出/切集时用）
     */
    suspend fun save(
        item: MediaItem,
        episodeIndex: Int,
        episodeName: String,
        routeName: String,
        positionMs: Long,
        durationMs: Long,
        finished: Boolean = false,
        force: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        // 进度太靠前不记。
        //
        // ★ 阈值从 10 秒降到 3 秒（2026-11-02）：
        //   10 秒太激进 —— 用户点开看一眼觉得不对就退，历史里什么都没有，
        //   下次还得重新找片。3 秒足以过滤「误触」这种真正的噪音。
        if (positionMs < 3_000 && !force) return@withContext
        // 播放完了就不该继续展示
        // A library item often has no backdrop of its own; the TMDB backdrop was written
        // back separately. Overwriting with null here would make Hero fall back to the
        // small poster again, so keep the previous value when this save has none.
        val previousBackdrop = dao.find(item.id)?.backdropUrl?.takeIf { it.isNotBlank() }
        val entity = PlayProgressEntity(
            mediaId = item.id,
            title = item.title,
            posterRes = item.posterRes,
            posterUrl = item.poster,
            episodeIndex = episodeIndex,
            episodeName = episodeName,
            routeName = routeName,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
            finished = finished,
            // 来源必须落库：首页是历史页，点进去要能回到当初那个源
            sourceKey = item.sourceKey,
            sourceId = item.sourceId,
            // Hero 展示用的元信息也一并冗余，避免渲染首屏还要回查源
            originalTitle = item.originalTitle,
            year = item.year,
            rating = item.rating,
            kindName = item.kind.label,
            overview = item.overview,
            backdropUrl = item.backdrop?.takeIf { it.isNotBlank() } ?: previousBackdrop,
        )
        android.util.Log.i(
            "ReelixSave",
            "upsert 开始: mediaId=${entity.mediaId} title=${entity.title} pos=${entity.positionMs}"
        )
        try {
            dao.upsert(entity)
            android.util.Log.i("ReelixSave", "upsert 成功")
        } catch (e: Throwable) {
            android.util.Log.e("ReelixSave", "upsert 失败: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }

        // 立刻回读验证（确认是否真的落库）
        runCatching {
            val n = dao.countAll()
            android.util.Log.i("ReelixSave", "回读: 表里共 $n 条")
        }.onFailure {
            android.util.Log.e("ReelixSave", "回读失败: ${it.message}", it)
        }
    }

    /**
     * 节流判断：给播放器回调用的轻量检查。
     *
     * 逻辑放在 companion 的纯函数里，这样 PC 上的单测不需要 Android 环境
     * 就能钉住"什么该写、什么不该写"这两条最容易回归的边界。
     */
    fun shouldPersist(
        lastSaveAt: Long,
        lastSavedPosition: Long,
        now: Long,
        position: Long,
    ): Boolean = shouldPersist(lastSaveAt, lastSavedPosition, now, position)

    suspend fun delete(mediaId: String) = withContext(Dispatchers.IO) {
        dao.delete(mediaId)
    }

    /**
     * 删除一条记录，并**返回被删掉的那条**。
     *
     * 返回值是给海报缓存清理用的：删历史等于"不看了"，那张海报也不该
     * 继续留在磁盘里。调用方拿到记录后调
     * `PosterCacheCleaner.evict(context, entry.posterUrl, entry.title)`。
     *
     * ★ 必须先 find 再 delete —— 删完就查不到了。
     */
    suspend fun deleteAndGet(mediaId: String): PlayProgressEntity? =
        withContext(Dispatchers.IO) {
            val entry = runCatching { dao.find(mediaId) }.getOrNull()
            dao.delete(mediaId)
            entry
        }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    /** 清理 30 天前已看完的记录 */
    suspend fun purgeOld() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        dao.purgeFinishedBefore(cutoff)
    }

    companion object {
        /** 两次写入最小间隔 */
        const val SAVE_INTERVAL_MS = 3_000L
        /** 两次写入最小位置差 */
        const val SAVE_MIN_DELTA_MS = 2_000L
        /** 低于这个位置不记录（看了不到 10 秒不该出现在"继续观看"） */
        const val MIN_PERSIST_POSITION_MS = 10_000L

        /**
         * 纯函数版节流判断（PC 上可单测）。
         *
         * 两道闸：
         *  1. 时间闸：距上次写入严格大于 [SAVE_INTERVAL_MS]
         *  2. 位移闸：与上次写入位置之差的绝对值严格大于 [SAVE_MIN_DELTA_MS]
         *     —— 取绝对值，用户往回拖也要记录。
         */
        fun shouldPersist(
            lastSaveAt: Long,
            lastSavedPosition: Long,
            now: Long,
            position: Long,
        ): Boolean {
            if (now - lastSaveAt < SAVE_INTERVAL_MS) return false
            if (kotlin.math.abs(position - lastSavedPosition) < SAVE_MIN_DELTA_MS) return false
            return true
        }

        @Volatile
        private var db: ReelixDatabase? = null

        /** 全局单例数据库 */
        fun database(context: Context): ReelixDatabase {
            return db ?: synchronized(this) {
                db ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReelixDatabase::class.java,
                    "reelix.db"
                )
                    // 注册迁移而不是 fallbackToDestructiveMigration ——
                    // 破坏性迁移会**清空用户所有播放记录**，
                    // 首页现在就是历史页，清空等于让首页变空，代价太大。
                    .addMigrations(ReelixDatabase.MIGRATION_1_2, ReelixDatabase.MIGRATION_2_3)
                    .build()
                    .also { db = it }
            }
        }

        fun create(context: Context): ProgressRepository =
            ProgressRepository(database(context).playProgressDao())
    }
}
