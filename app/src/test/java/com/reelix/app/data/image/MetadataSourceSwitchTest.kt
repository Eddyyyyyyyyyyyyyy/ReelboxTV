package com.reelix.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 换海报来源后，资料必须跟着换。
 *
 * 之前的 bug：`mergeLibraryMetadata` 是「旧值非空就保留」，
 * 于是 TMDB → 豆瓣 换图后，简介/演职员全被旧值压住，用户看到「海报换了、资料没变」。
 */
class MetadataSourceSwitchTest {

    private fun identity(provider: String, id: String) =
        LibraryWorkIdentity(provider = provider, id = id, mediaType = "movie", title = "空枪", kind = "MOVIE")

    private fun metadata(provider: String, overview: String?, cast: List<LibraryPersonCredit> = emptyList()) =
        LibraryWorkMetadata(identity(provider, "1"), overview = overview, cast = cast)

    @Test
    fun `switching source keeps new values`() {
        val douban = metadata("DOUBAN", "豆瓣简介")
        val tmdbOld = metadata("TMDB", "TMDB 简介")

        val merged = douban.withFallbackFrom(tmdbOld)

        assertEquals("豆瓣简介", merged.overview)
    }

    @Test
    fun `switching source falls back when new value missing`() {
        val douban = metadata("DOUBAN", null)
        val tmdbOld = metadata("TMDB", "TMDB 简介")

        val merged = douban.withFallbackFrom(tmdbOld)

        // 用户确认：新来源没返回时不能把界面变空。
        assertEquals("TMDB 简介", merged.overview)
    }

    @Test
    fun `same source merge still fills gaps only`() {
        val current = metadata("DOUBAN", "已有简介")
        val extra = metadata("DOUBAN", "新简介")

        val merged = mergeLibraryMetadata(current, extra)

        assertEquals("已有简介", merged.overview)
    }
}
