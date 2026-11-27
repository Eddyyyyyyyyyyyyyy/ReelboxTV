package com.reelix.app.data.source.online

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRestoreTest {

    private val movies = MediaItem(id = "m1", title = "电影", sourceKey = "site", sourceId = "m1")

    private fun home(vararg categoryIds: String, withPosters: Boolean = true) = HomeContent(
        categories = categoryIds.map { SourceCategory(it, it) },
        recommendations = if (withPosters) listOf(movies) else emptyList(),
    )

    @Test
    fun `home posters are a valid first paint without category content`() {
        val selected = OnlineRestore.choose(home("movie"), null, emptyMap())
        assertTrue(selected.canPaintPosters)
        assertEquals("movie", selected.categoryId)
    }

    @Test
    fun `a cached category is shown when its own content exists`() {
        val selected = OnlineRestore.choose(
            home = home("movie"),
            requestedCategoryId = "movie",
            categories = mapOf("movie" to CategoryContent(items = listOf(movies))),
        )
        assertTrue(selected.canPaintPosters)
        assertEquals(1, selected.category?.items?.size)
    }

    @Test
    fun `a category id left from another site is discarded`() {
        val selected = OnlineRestore.choose(
            home = home("movie"),
            requestedCategoryId = "old-site-category",
            categories = mapOf("old-site-category" to CategoryContent(items = listOf(movies))),
        )
        assertEquals("movie", selected.categoryId)
        assertNull("旧站点的分类内容不能显示", selected.category)
    }

    @Test
    fun `empty home and empty category do not claim a first paint`() {
        val selected = OnlineRestore.choose(
            HomeContent(),
            "movie",
            mapOf("movie" to CategoryContent()),
        )
        assertFalse(selected.canPaintPosters)
    }

    @Test
    fun `there is no remembered default without a selected site`() {
        val cache = OnlineCache()
        assertEquals("", cache.cachedSelectedSiteKey())
        assertEquals("", cache.cachedSelectedCategoryId())
    }
}
