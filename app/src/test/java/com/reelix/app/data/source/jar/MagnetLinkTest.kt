package com.reelix.app.data.source.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 磁力链识别测试。
 *
 * ## 为什么必须测
 *
 * 识别错了会导致两种坏结果：
 * - **漏判**：磁力链被当成 HTTP 丢给 ExoPlayer → 播放失败 → 用户以为源坏了
 * - **误判**：正常 HTTP 地址被当成磁力跳过 → 明明能播却播不了
 *
 * 而且这两类地址在详情页里长得差不多，只能靠前缀判断。
 */
class MagnetLinkTest {

    // ---------- 识别 ----------

    @Test
    fun `识别磁力链`() {
        val m = "magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=片名.mkv"
        assertEquals(MagnetLink.Kind.MAGNET, MagnetLink.kind(m))
        assertTrue(MagnetLink.isUnsupported(m))
    }

    @Test
    fun `识别电驴`() {
        val e = "ed2k://|file|片名.mkv|1234567|ABCDEF|/"
        assertEquals(MagnetLink.Kind.ED2K, MagnetLink.kind(e))
        assertTrue(MagnetLink.isUnsupported(e))
    }

    @Test
    fun `识别迅雷专用链`() {
        assertEquals(
            MagnetLink.Kind.THUNDER,
            MagnetLink.kind("thunder://QUFodHRwOi8v...")
        )
    }

    @Test
    fun `HTTP 地址不当磁力`() {
        assertFalse(MagnetLink.isUnsupported("https://cdn.com/a.m3u8"))
        assertFalse(MagnetLink.isUnsupported("http://x.com/v.mp4"))
        assertFalse(MagnetLink.isUnsupported("/index.php/vod/play/id/1"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(MagnetLink.Kind.MAGNET, MagnetLink.kind("MAGNET:?xt=urn:btih:abc"))
        assertEquals(MagnetLink.Kind.THUNDER, MagnetLink.kind("THUNDER://xxx"))
    }

    @Test
    fun `空值安全`() {
        assertEquals(MagnetLink.Kind.HTTP, MagnetLink.kind(null))
        assertEquals(MagnetLink.Kind.HTTP, MagnetLink.kind(""))
        assertEquals(MagnetLink.Kind.HTTP, MagnetLink.kind("   "))
    }

    // ---------- 提示文案 ----------

    @Test
    fun `磁力链有明确提示`() {
        val h = MagnetLink.hint("magnet:?xt=urn:btih:abc")
        assertNotNull(h)
        assertTrue("应说明是磁力链: $h", h!!.contains("磁力"))
        assertTrue("应告诉用户怎么办: $h", h.contains("换"))
    }

    @Test
    fun `HTTP 没有提示`() {
        assertNull(MagnetLink.hint("https://x.com/a.m3u8"))
    }

    // ---------- 自动改选 ----------

    @Test
    fun `跳过磁力线选可播的`() {
        val routes = listOf(
            listOf("magnet:?xt=urn:btih:abc"),          // 线路0：磁力
            listOf("https://cdn.com/a.m3u8"),           // 线路1：可播
        )
        assertEquals(1, MagnetLink.pickPlayableIndex(routes))
    }

    @Test
    fun `第一条就可播时不改选`() {
        val routes = listOf(
            listOf("https://cdn.com/a.m3u8"),
            listOf("magnet:?xt=urn:btih:abc"),
        )
        assertEquals(0, MagnetLink.pickPlayableIndex(routes))
    }

    @Test
    fun `全是磁力时保持原样`() {
        val routes = listOf(
            listOf("magnet:?xt=urn:btih:abc"),
            listOf("ed2k://|file|x|1|y|/"),
        )
        assertEquals(0, MagnetLink.pickPlayableIndex(routes))
    }

    @Test
    fun `一条线路内有一集可播就算可用`() {
        val eps = listOf("magnet:?xt=urn:btih:abc", "https://cdn.com/a.mp4")
        assertTrue(MagnetLink.isRoutePlayable(eps))
    }

    @Test
    fun `全是磁力的线路不可用`() {
        val eps = listOf("magnet:?xt=urn:btih:abc", null, "")
        assertFalse(MagnetLink.isRoutePlayable(eps))
    }

    @Test
    fun `空列表安全`() {
        assertEquals(0, MagnetLink.pickPlayableIndex(emptyList()))
        assertFalse(MagnetLink.isRoutePlayable(emptyList()))
    }
}
