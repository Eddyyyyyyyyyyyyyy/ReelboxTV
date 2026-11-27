package com.reelix.app.ui.component

import com.reelix.app.ui.screen.player.SkipField
import com.reelix.app.ui.screen.player.SkipInfo
import com.reelix.app.ui.screen.player.markSkipAt
import org.junit.Assert.*
import org.junit.Test

class SkipMarkTest {
    @Test fun `当前时间作为片头结束且保留片尾`() {
        assertEquals(SkipInfo(65_250, 100_000), markSkipAt(SkipInfo(0, 100_000), SkipField.INTRO, 65_250, 120_000))
    }
    @Test fun `当前时间作为片尾开始且保留片头`() {
        assertEquals(SkipInfo(10_000, 100_000), markSkipAt(SkipInfo(10_000), SkipField.OUTRO, 100_000, 120_000))
    }
    @Test fun `未取得时长时不能把已有设置清零`() {
        assertNull(markSkipAt(SkipInfo(10_000), SkipField.INTRO, 0, 0))
    }
    @Test fun `当前时间夹到视频范围内`() {
        assertEquals(120_000L, markSkipAt(SkipInfo(), SkipField.OUTRO, 200_000, 120_000)?.outroStartMs)
        assertEquals(0L, markSkipAt(SkipInfo(), SkipField.INTRO, -1, 120_000)?.introEndMs)
    }
}
