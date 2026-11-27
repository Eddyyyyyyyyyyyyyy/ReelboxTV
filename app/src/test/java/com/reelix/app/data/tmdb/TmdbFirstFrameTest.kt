package com.reelix.app.data.tmdb

import org.junit.Assert.*
import org.junit.Test

class TmdbFirstFrameTest {
    @Test fun `详情首帧直接取得已缓存横幅并隔离年份和语言`() {
        val match = TmdbClient.TmdbMatch(99, "回归样例", null, "/wide.jpg", "/poster.jpg", null, 2026, null, "movie")
        val field = TmdbClient::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(TmdbClient) as MutableMap<String, TmdbClient.TmdbMatch>
        val key = "回归样例|2026|zh-CN"
        cache[key] = match
        try {
            assertEquals(match.backdropUrlW1280, TmdbClient.cachedMatch("回归样例【4K】", 2026)?.backdropUrlW1280)
            assertNull(TmdbClient.cachedMatch("回归样例", 2025))
            assertNull(TmdbClient.cachedMatch("回归样例", 2026, "en-US"))
        } finally { cache.remove(key) }
    }
}
