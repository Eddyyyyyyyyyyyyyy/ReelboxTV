package com.reelix.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放进度节流逻辑的测试。
 *
 * 这段逻辑是"电视能不能用"的关键：
 *  写太勤 → ExoPlayer 每 250ms 回调一次全打进 SQLite，电视闪一下就卡；
 *  写太懒 → 用户退出后进度丢失，续播失效。
 * 它现在是 [ProgressRepository.Companion] 里的纯函数，可以在 PC 上直接测。
 */
class ProgressThrottleTest {

    private val interval = ProgressRepository.SAVE_INTERVAL_MS
    private val delta = ProgressRepository.SAVE_MIN_DELTA_MS

    private fun shouldPersist(now: Long, lastAt: Long, pos: Long, lastPos: Long): Boolean =
        ProgressRepository.shouldPersist(lastAt, lastPos, now, pos)

    @Test
    fun `间隔与位移都满_应当写入`() {
        val t0 = 1_000_000L
        assertTrue(
            shouldPersist(
                now = t0 + interval + 1,
                lastAt = t0,
                pos = 5_000 + delta + 1,
                lastPos = 5_000,
            )
        )
    }

    @Test
    fun `时间不够_不写`() {
        val t0 = 1_000_000L
        assertFalse(
            "只过了 1 秒不该写，否则播放器每 250ms 一次回调会拖垮数据库",
            shouldPersist(now = t0 + 1_000, lastAt = t0, pos = 60_000, lastPos = 1_000)
        )
    }

    @Test
    fun `时间够但位置没动_不写`() {
        val t0 = 1_000_000L
        assertFalse(
            "暂停时位置不变，不该反复写同一条",
            shouldPersist(now = t0 + 10_000, lastAt = t0, pos = 60_000, lastPos = 59_999)
        )
    }

    @Test
    fun `刚好跨越时间闸就应当写入`() {
        val t0 = 1_000_000L
        // 时间闸是"大于等于"：满 3 秒即可；位移闸同样"大于等于"2 秒。
        // 这条用例把边界语义钉死，防止以后有人把 >= 改成 > 导致电视上少写进度。
        assertTrue(shouldPersist(now = t0 + interval, lastAt = t0, pos = 10_000, lastPos = 0))
        assertFalse(shouldPersist(now = t0 + interval - 1, lastAt = t0, pos = 10_000, lastPos = 0))
        assertFalse(shouldPersist(now = t0 + interval, lastAt = t0, pos = delta - 1, lastPos = 0))
    }

    @Test
    fun `倒放拖回也应当写入`() {
        val t0 = 1_000_000L
        assertTrue(
            "位移闸取绝对值，往前拖也要记录",
            shouldPersist(now = t0 + interval + 1, lastAt = t0, pos = 1_000, lastPos = 90_000)
        )
    }

    @Test
    fun `首次写入 lastAt 为 0 时应立即写`() {
        assertTrue(shouldPersist(now = 5_000, lastAt = 0, pos = 20_000, lastPos = 0))
    }

    @Test
    fun `新建播放位置太靠前不应进入继续观看`() {
        // 这是 save() 里的规则：非强制且 < 10 秒的记录丢弃
        assertTrue(progressTooEarly(3_000))
        assertFalse(progressTooEarly(30_000))
    }

    private fun progressTooEarly(positionMs: Long): Boolean =
        positionMs < ProgressRepository.MIN_PERSIST_POSITION_MS
}
