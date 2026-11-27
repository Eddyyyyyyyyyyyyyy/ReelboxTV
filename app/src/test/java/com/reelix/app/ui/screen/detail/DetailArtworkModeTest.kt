package com.reelix.app.ui.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailArtworkModeTest {
    @Test fun `只有海报成功时显示组合而不是空框`() {
        assertEquals(DetailArtworkMode.NEUTRAL, detailArtworkMode(false, false, false, false))
        assertEquals(DetailArtworkMode.POSTER, detailArtworkMode(true, false, false, false))
    }
    @Test fun `新横幅只有URL时保留原组合实际成功才隐藏`() {
        assertEquals(DetailArtworkMode.POSTER, detailArtworkMode(true, false, true, true))
        assertEquals(DetailArtworkMode.WIDE, detailArtworkMode(true, true, false, true))
    }
    @Test fun `已缓存横幅初始等待时不先闪竖图失败后再回退`() {
        assertEquals(DetailArtworkMode.NEUTRAL, detailArtworkMode(true, false, true, false))
        assertEquals(DetailArtworkMode.POSTER, detailArtworkMode(true, false, false, false))
    }
    @Test fun `无海报也可以独立显示成功横幅`() {
        assertEquals(DetailArtworkMode.WIDE, detailArtworkMode(false, true, false, false))
    }
}
