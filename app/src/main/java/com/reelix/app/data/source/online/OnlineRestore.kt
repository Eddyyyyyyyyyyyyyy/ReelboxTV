package com.reelix.app.data.source.online

import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceCategory

/**
 * Pure first-paint selection for the online page.
 *
 * Kept separate from Compose so the edge cases seen on the TV can be
 * asserted without a device: an old category id from another site, a
 * category cache without a home cache, and an empty snapshot.
 */
object OnlineRestore {

    data class Selected(
        val home: HomeContent?,
        val categoryId: String?,
        val category: CategoryContent?,
        val canPaintPosters: Boolean,
    )

    fun choose(
        home: HomeContent?,
        requestedCategoryId: String?,
        categories: Map<String, CategoryContent>,
    ): Selected {
        val usableHome = home?.takeIf { it.categories.isNotEmpty() || it.recommendations.isNotEmpty() }
        val categoryId = chooseCategoryId(
            requested = requestedCategoryId,
            available = usableHome?.categories.orEmpty(),
            cachedIds = categories.keys,
        )
        val category = categoryId?.let { categories[it] }
        return Selected(
            home = usableHome,
            categoryId = categoryId,
            category = category,
            canPaintPosters = category?.items?.isNotEmpty() == true ||
                usableHome?.recommendations?.isNotEmpty() == true,
        )
    }

    /**
     * Prefer the remembered category only if it belongs to this site's home.
     * If no home category list exists, a cached category may still be valid,
     * but only when it is the sole unambiguous cached choice.
     */
    fun chooseCategoryId(
        requested: String?,
        available: List<SourceCategory>,
        cachedIds: Set<String>,
    ): String? {
        if (requested != null && available.any { it.id == requested }) return requested
        available.firstOrNull()?.id?.let { return it }
        if (requested != null && cachedIds.size == 1 && requested in cachedIds) return requested
        return cachedIds.singleOrNull()
    }
}
