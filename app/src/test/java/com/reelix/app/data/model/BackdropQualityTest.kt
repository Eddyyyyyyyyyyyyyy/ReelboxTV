package com.reelix.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 背景图质量判定。
 *
 * ## 为什么这段逻辑必须测
 *
 * 它决定详情页/Hero 用哪种构图：
 * ```
 * CINEMATIC  横版大图 → 直接铺满 + 左右渐变
 * COMPOSITE  只有竖图 → 模糊放大 + 双层渐变
 * FALLBACK   完全没图 → 纯色底 + 大标题
 * ```
 *
 * 判错的后果：
 * - 竖图被当横图 → 裁得只剩中间一条，很难看
 * - 有图被当没图 → 显示纯色底，浪费了好海报
 * - 没图被当有图 → 加载失败，露出破图占位
 *
 * 来源补回来的竖图会塞进 `poster`，所以 `poster 有值 → COMPOSITE`
 * 这条分支必须稳。
 */
class BackdropQualityTest {

    @Test
    fun `有横版 backdrop 走 CINEMATIC`() {
        val item = MediaItem(
            id = "1",
            title = "沙丘2",
            backdrop = "https://image.tmdb.org/t/p/w1280/abc.jpg",
        )
        assertEquals(BackdropQuality.CINEMATIC, item.backdropQuality)
    }

    @Test
    fun `只有竖版 poster 走 COMPOSITE`() {
        val item = MediaItem(
            id = "1",
            title = "三体",
            poster = "https://image.tmdb.org/t/p/w500/p123.jpg",
        )
        assertEquals(BackdropQuality.COMPOSITE, item.backdropQuality)
    }

    @Test
    fun `来源补回的竖图也是 COMPOSITE`() {
        // 刮削结果塞进 poster 后的形态 —— l_ratio_poster 是竖图
        val scraped = MediaItem(
            id = "1",
            title = "凡人修仙传",
            poster = "https://image.tmdb.org/t/p/w500/abc.jpg",
        )
        assertEquals(BackdropQuality.COMPOSITE, scraped.backdropQuality)
    }

    @Test
    fun `完全没图走 FALLBACK`() {
        val item = MediaItem(id = "1", title = "未知片子")
        assertEquals(BackdropQuality.FALLBACK, item.backdropQuality)
    }

    @Test
    fun `本地横图资源优先于网络图`() {
        val item = MediaItem(
            id = "1",
            title = "本地片",
            backdropRes = "backdrops/local.jpg",
            poster = "https://example.com/p.jpg",
        )
        assertEquals(BackdropQuality.CINEMATIC, item.backdropQuality)
    }

    @Test
    fun `竖版 backdrop 降级成 COMPOSITE`() {
        // 有些源把竖图塞在 backdrop 字段里 —— 不能当横图用
        val item = MediaItem(
            id = "1",
            title = "某剧",
            backdrop = "https://images.example/p9-portrait.jpg",
        )
        assertEquals(BackdropQuality.COMPOSITE, item.backdropQuality)
    }

    @Test
    fun `空字符串等同于没有`() {
        val item = MediaItem(
            id = "1",
            title = "空值片",
            backdrop = "",
            poster = "   ",
        )
        assertEquals(BackdropQuality.FALLBACK, item.backdropQuality)
    }
}
