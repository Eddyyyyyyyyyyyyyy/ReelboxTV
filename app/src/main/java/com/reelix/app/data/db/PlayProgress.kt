package com.reelix.app.data.db

import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 播放进度记录 —— 影视 App 的命脉。
 *
 * 设计要点：
 *
 * 1. **主键用 mediaId，一部片子只有一条记录**
 *    用户关心的是"这部剧我看到第几集、拖到第几分钟"，
 *    而不是"我在第几条线路上看过"。所以线路只作为附加信息存。
 *
 * 2. **同时存集号和毫秒位置**
 *    剧集：`episodeIndex` 决定从哪一集继续，`positionMs` 是这一集内的位置
 *    电影：`episodeIndex` 恒为 1
 *
 * 3. **`updatedAt` 用于排序**
 *    "继续观看"要按最近观看倒序，这是最基本的体验。
 *
 * 4. **`durationMs` 用来算进度百分比**
 *    海报下方的进度条需要 0..1 的比例，没有总时长算不出来。
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "play_progress")
data class PlayProgressEntity(
    @PrimaryKey val mediaId: String,
    /** 片名（冗余存储，避免列表页还要回查源数据） */
    val title: String = "",
    /** 封面（本地的资源名或 URL，同上） */
    val posterRes: String? = null,
    val posterUrl: String? = null,
    /** 当前集号 */
    val episodeIndex: Int = 1,
    /** 当前集名称（"第3集"），展示用 */
    val episodeName: String = "",
    /** 当前线路名 */
    val routeName: String = "",
    /** 当前播放位置（毫秒） */
    val positionMs: Long = 0,
    /** 总时长（毫秒） */
    val durationMs: Long = 0,
    /** 最后更新时间戳 */
    val updatedAt: Long = System.currentTimeMillis(),
    /** 是否已看完 */
    val finished: Boolean = false,

    /**
     * 来源站点 key（在线源才有，片库为空）。
     *
     * 为什么必须存：首页现在就是「播放历史页」，用户点历史里的片子要能
     * **回到当初那个源**继续看。不存 sourceKey 的话，
     * 点进去只能靠 mediaId 猜源，猜错就会「找不到该片」。
     */
    val sourceKey: String = "",

    /**
     * 源内条目 id（如 `/index.php/vod/detail/id/3317.html`）。
     *
     * 同理：详情页要靠它去取线路和选集。
     */
    val sourceId: String = "",

    // ---- Hero 展示需要 ----
    //
    // 首页 Hero 沿用旧版版式（标签胶囊 + 元信息行 + 大标题 + 原名 + 两行简介），
    // 这些都来自 MediaItem。进度表原本只存片名和海报，Hero 会缺一半信息。
    // 所以在保存进度时把这些一起冗余下来，避免为了渲染 Hero 再回查源。

    /** 原名（如 "Dune: Part Two"） */
    val originalTitle: String? = null,
    /** 年份 */
    val year: Int? = null,
    /** 评分 */
    val rating: Double? = null,
    /** 内容类型（电影/剧集/动漫…） */
    val kindName: String = "",
    /** 简介 */
    val overview: String? = null,
    /** 背景大图（Hero 用，没有就退回 poster） */
    val backdropUrl: String? = null,
) {
    /** 进度比例 0..1 */
    val progress: Float
        get() = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f
}

@Dao
interface PlayProgressDao {
    @androidx.room.Transaction
    suspend fun migrateLibraryRecord(expected: PlayProgressEntity, replacement: PlayProgressEntity) {
        // A player may have saved a newer position while the migration was preparing its backup.
        if (find(expected.mediaId) != expected) return
        val target = find(replacement.mediaId)
        if (target == null || target.updatedAt <= replacement.updatedAt) upsert(replacement)
        if (expected.mediaId != replacement.mediaId) delete(expected.mediaId)
    }

    /** 继续观看列表：未看完 + 有进度 + 按最近时间倒序 */
    @Query(
        """
        SELECT * FROM play_progress
        WHERE finished = 0 AND positionMs > 0
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeContinueWatching(limit: Int = 20): Flow<List<PlayProgressEntity>>

    /** 全部记录（片库详情页判断"是否看过"用） */
    @Query("SELECT * FROM play_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlayProgressEntity>>

    /** 总条数（诊断用） */
    @Query("SELECT COUNT(*) FROM play_progress")
    suspend fun countAll(): Int

    /** 单条查询 */
    @Query("SELECT * FROM play_progress WHERE mediaId = :mediaId LIMIT 1")
    suspend fun find(mediaId: String): PlayProgressEntity?

    @Query("SELECT * FROM play_progress WHERE mediaId = :mediaId LIMIT 1")
    fun observe(mediaId: String): Flow<PlayProgressEntity?>

    /** 只补空白的 Hero 横图，不改观看进度、最近排序或已有有效图片。 */
    @Query("UPDATE play_progress SET backdropUrl = :backdropUrl WHERE mediaId = :mediaId AND (backdropUrl IS NULL OR backdropUrl = '')")
    suspend fun updateBackdropIfEmpty(mediaId: String, backdropUrl: String)

    /** 写入或覆盖 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlayProgressEntity)

    @Query("DELETE FROM play_progress WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    @Query("DELETE FROM play_progress")
    suspend fun clearAll()

    /** 清理很久以前的记录，避免无限增长 */
    @Query("DELETE FROM play_progress WHERE updatedAt < :before AND finished = 1")
    suspend fun purgeFinishedBefore(before: Long)
}

@Database(
    entities = [PlayProgressEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ReelixDatabase : RoomDatabase() {
    abstract fun playProgressDao(): PlayProgressDao

    companion object {
        /**
         * v1 → v2：加 sourceKey / sourceId。
         *
         * 为什么加：首页改成「播放历史页」之后，用户点历史里的片子
         * 必须能回到**当初那个源**继续看。不存这两个字段的话，
         * 点进去只能靠 mediaId 猜源，猜错就是「找不到该片」。
         *
         * 老记录留空串，行为退化成"按 mediaId 猜"，不影响已有数据。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_progress ADD COLUMN sourceKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE play_progress ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2 → v3：加 Hero 展示用的元信息。
         *
         * ★ 踩过的坑（2026-11-01，装上去直接闪退）：
         *   「Room cannot verify the data integrity.
         *     Looks like you've changed schema but forgot to update the version number.」
         *   改了实体字段但**忘记升 version**，Room 检测到 identity hash 对不上，
         *   直接抛 IllegalStateException 崩在启动。
         *   以后加字段必须同时：① 升 version ② 写迁移 ③ 注册迁移。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_progress ADD COLUMN originalTitle TEXT")
                db.execSQL("ALTER TABLE play_progress ADD COLUMN year INTEGER")
                db.execSQL("ALTER TABLE play_progress ADD COLUMN rating REAL")
                db.execSQL("ALTER TABLE play_progress ADD COLUMN kindName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE play_progress ADD COLUMN overview TEXT")
                db.execSQL("ALTER TABLE play_progress ADD COLUMN backdropUrl TEXT")
            }
        }
    }
}
