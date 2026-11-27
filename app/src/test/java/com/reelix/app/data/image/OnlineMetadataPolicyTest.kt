package com.reelix.app.data.image

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineMetadataPolicyTest {

    private val oldItem = MediaItem(
        id = "1",
        title = "奥德赛2026",
        year = 2025,
        rating = 6.1,
        ratingSource = "ONLINE",
        overview = "源站旧简介",
        director = "旧导演",
        cast = listOf("旧演员"),
        kind = MediaKind.MOVIE,
    )

    private val manualEntry = OnlineMetadataStore.Entry(
        rating = 8.024,
        ratingSource = "TMDB",
        overview = "TMDB 新简介",
        year = 2026,
        directors = listOf("克里斯托弗·诺兰"),
        cast = listOf(LibraryPersonCredit("马特·达蒙", "Odysseus", "https://avatar")),
        identity = LibraryWorkIdentity("TMDB", "1368337", "movie", "奥德赛", "The Odyssey", 2026, "MOVIE"),
        manual = true,
    )

    @Test
    fun `手动资料的新值逐字段覆盖旧值`() {
        val applied = applyOnlineMetadata(oldItem, manualEntry)

        assertEquals(8.024, applied.rating!!, 0.0001)
        assertEquals("TMDB", applied.ratingSource)
        assertEquals("TMDB 新简介", applied.overview)
        assertEquals(2026, applied.year)
        assertEquals("克里斯托弗·诺兰", applied.director)
        assertEquals(listOf("马特·达蒙（Odysseus）"), applied.cast)
        assertEquals("马特·达蒙", applied.castCredits.single().name)
    }

    @Test
    fun `手动资料为空的字段保留旧值和旧评分来源`() {
        val applied = applyOnlineMetadata(oldItem, manualEntry.copy(
            rating = null,
            ratingSource = null,
            overview = " ",
            year = null,
            directors = emptyList(),
            cast = emptyList(),
        ))

        assertEquals(6.1, applied.rating!!, 0.0001)
        assertEquals("ONLINE", applied.ratingSource)
        assertEquals("源站旧简介", applied.overview)
        assertEquals(2025, applied.year)
        assertEquals("旧导演", applied.director)
        assertEquals(listOf("旧演员"), applied.cast)
        assertEquals(oldItem.castCredits, applied.castCredits)
    }

    @Test
    fun `自动资料仍然只补空不覆盖源站资料`() {
        val applied = applyOnlineMetadata(oldItem, manualEntry.copy(manual = false))

        assertEquals(6.1, applied.rating!!, 0.0001)
        assertEquals("ONLINE", applied.ratingSource)
        assertEquals("源站旧简介", applied.overview)
        assertEquals(2025, applied.year)
        assertEquals("旧导演", applied.director)
        assertEquals(listOf("旧演员"), applied.cast)
    }

    @Test
    fun `旧资料缓存没有手动标记时按自动资料处理`() {
        val entry = Json.decodeFromString<OnlineMetadataStore.Entry>("""{"overview":"旧缓存"}""")
        assertFalse(entry.manual)
        assertNull(entry.identity)
        assertEquals("源站旧简介", applyOnlineMetadata(oldItem, entry).overview)
    }
}
