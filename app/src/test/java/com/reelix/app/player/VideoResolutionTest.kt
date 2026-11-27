package com.reelix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoResolutionTest {
    @Test fun `普通视频显示编码尺寸`() {
        assertEquals(VideoResolution(1920, 1080), videoResolutionOf(1920, 1080))
    }

    @Test fun `非方形像素换算实际画面宽度`() {
        assertEquals(VideoResolution(1920, 1080), videoResolutionOf(1440, 1080, 4f / 3f))
    }

    @Test fun `旋转视频交换显示宽高`() {
        assertEquals(VideoResolution(1080, 1920), videoResolutionOf(1920, 1080, 1f, 90))
        assertEquals(VideoResolution(1080, 1920), videoResolutionOf(1920, 1080, 1f, -90))
    }

    @Test fun `未知尺寸与损坏比例安全回退`() {
        assertNull(videoResolutionOf(0, 1080))
        assertNull(videoResolutionOf(1920, -1))
        assertEquals(VideoResolution(1920, 1080), videoResolutionOf(1920, 1080, Float.NaN))
        assertEquals(VideoResolution(1920, 1080), videoResolutionOf(1920, 1080, 0f))
    }
}
