package com.reelix.app.data.image

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkChannelTest {

    @Test fun `默认包含豆瓣Streamx与TVmaze且启用状态跟随发行配置`() {
        val defaults = ArtworkChannel.defaults()
        assertEquals(listOf("DOUBAN", "STREAMX", "TVMAZE"), defaults.map { it.kind })
        // 有设置入口时默认关闭（用户逐个开启）；无入口时默认开启。
        assertEquals(com.reelix.app.BuildConfig.PUBLIC_BUILD, defaults.all { it.enabled })
        assertEquals("豆瓣", defaults[0].displayName)
        assertEquals("Streamx", defaults[1].displayName)
        assertEquals("TVmaze", defaults[2].displayName)
    }

    @Test fun `空地址使用内置默认地址`() {
        val streamx = ArtworkChannel.streamx()
        assertEquals(streamx.defaultUrl, streamx.effectiveUrl)
        assertEquals("https://api.streamx.qzz.io", streamx.effectiveUrl)
        assertEquals("https://api.tvmaze.com", ArtworkChannel.tvmaze().effectiveUrl)
    }

    @Test fun `编码解码只保留内置类型字段`() {
        val raw = ArtworkChannelCodec.encode(
            listOf(
                ArtworkChannel.streamx(enabled = true, baseUrl = "https://mirror.example"),
                ArtworkChannel.tvmaze(enabled = false),
            ),
        )
        val decoded = ArtworkChannelCodec.decode(raw)
        assertEquals(listOf("STREAMX", "TVMAZE", "DOUBAN"), decoded.map { it.kind })
        assertEquals(true, decoded[0].enabled)
        assertEquals("https://mirror.example", decoded[0].baseUrl)
    }

    @Test fun `旧版自定义渠道按地址迁移为内置类型`() {
        val old = JSONArray().apply {
            put(JSONObject().apply {
                put("kind", "JSON")
                put("name", "streamx-api")
                put("baseUrl", "https://api.streamx.qzz.io")
                put("enabled", true)
            })
            put(JSONObject().apply {
                put("kind", "JSON")
                put("baseUrl", "https://api.tvmaze.com")
                put("enabled", true)
            })
            put(JSONObject().apply {
                put("kind", "JSON")
                put("baseUrl", "https://example.com/custom")
                put("enabled", true)
            })
        }
        val decoded = ArtworkChannelCodec.decode(old.toString())
        // 未知自定义渠道被丢弃；没出现的内置类型补默认项。
        assertEquals(listOf("STREAMX", "TVMAZE", "DOUBAN"), decoded.map { it.kind })
        assertEquals(true, decoded[0].enabled)
        assertEquals(true, decoded[1].enabled)
    }

    @Test fun `排序只影响内置来源且在线源固定最后`() {
        val channels = listOf(
            ArtworkChannel.streamx(enabled = true),
            ArtworkChannel.tvmaze(enabled = true),
        )
        val slots = artworkProviderSlots(
            tmdbConfigured = true,
            onlineAvailable = true,
            channels = channels,
            order = listOf("TVMAZE", "TMDB", "STREAMX"),
        )
        assertEquals(
            listOf(LibraryArtworkSource.TVMAZE, LibraryArtworkSource.TMDB,
                LibraryArtworkSource.STREAMX, LibraryArtworkSource.ONLINE),
            slots.map { it.source },
        )
    }

    @Test fun `停用的来源不进入刮削顺序`() {
        val channels = listOf(
            ArtworkChannel.streamx(enabled = false),
            ArtworkChannel.tvmaze(enabled = false),
        )
        val slots = artworkProviderSlots(false, true, channels)
        assertEquals(listOf(LibraryArtworkSource.ONLINE), slots.map { it.source })
    }
}
