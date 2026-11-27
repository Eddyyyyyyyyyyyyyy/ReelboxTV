package com.reelix.app.data.settings

/** Stored names are page identities, independent of their current visual positions. */
data class AppearancePreferences(
    val order: List<String> = DEFAULT_ORDER,
    val hidden: Set<String> = emptySet(),
    val posterColumns: Int = 6,
    val layoutTheme: String = "ORIGINAL",
) {
    fun normalized() = copy(
        order = (order.filter { it in DEFAULT_ORDER } + DEFAULT_ORDER).distinct(),
        hidden = hidden.intersect(HIDEABLE),
        posterColumns = posterColumns.takeIf { it in 4..8 } ?: 6,
        layoutTheme = normalizedLayoutTheme(layoutTheme),
    )
    val visible: List<String> get() = normalized().let { p -> p.order.filterNot { it in p.hidden } }
    fun safePage(page: String): String = page.takeIf { it in visible } ?: "HOME"
    fun move(page: String, delta: Int): AppearancePreferences {
        val p = normalized()
        val next = p.order.toMutableList()
        val from = next.indexOf(page)
        if (from < 0) return p
        val to = (from + delta).coerceIn(next.indices)
        next.removeAt(from); next.add(to, page)
        return p.copy(order = next)
    }
    companion object {
        val DEFAULT_ORDER = listOf("HOME", "ONLINE", "LIBRARY", "SEARCH")
        val HIDEABLE = setOf("ONLINE", "LIBRARY")
        val LAYOUT_THEMES = setOf("ORIGINAL", "GALLERY", "SIDEBAR", "TOPBAR")
    }
}

/**
 * 主题 id 的合法化 + 旧值迁移。
 *
 * 早期版本用品牌名做主题 id（INFUSE/NETFLIX/APPLE_TV），公开版已改成描述布局的
 * 中性 id；这里把已存的旧值映射过来，避免升级后主题被重置。
 */
fun normalizedLayoutTheme(value: String): String {
    val migrated = when (value) {
        "INFUSE" -> "GALLERY"
        "NETFLIX" -> "SIDEBAR"
        "APPLE_TV" -> "TOPBAR"
        else -> value
    }
    return migrated.takeIf { it in AppearancePreferences.LAYOUT_THEMES } ?: "ORIGINAL"
}

data class PosterGeometry(val columns: Int, val width: Int)

/** All containers use the same screen-based width; sidebars only reduce column count. */
fun posterGeometry(
    screenWidth: Int,
    availableWidth: Int,
    gap: Int,
    edge: Int,
    referenceColumns: Int,
    minimumColumns: Int = 1,
): PosterGeometry {
    val reference = referenceColumns.takeIf { it in 4..8 } ?: 6
    val target = ((screenWidth - 2 * edge - (reference - 1) * gap) / reference).coerceAtLeast(1)
    var width = target.coerceAtMost(availableWidth.coerceAtLeast(1))
    var columns = ((availableWidth + gap) / (width + gap)).coerceAtLeast(1)
    if (columns < minimumColumns && availableWidth > 0) {
        val fitted = ((availableWidth - (minimumColumns - 1) * gap) / minimumColumns)
            .coerceIn(1, width)
        width = fitted
        columns = ((availableWidth + gap) / (width + gap)).coerceAtLeast(1)
    }
    return PosterGeometry(columns, width)
}

fun restoredPosterPosition(keys: List<String>, key: String?, fallback: Int): Int =
    keys.indexOf(key).takeIf { it >= 0 } ?: fallback.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
