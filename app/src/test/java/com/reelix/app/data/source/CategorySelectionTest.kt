package com.reelix.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分类切换的「受控」语义。
 *
 * ## 为什么写这个测
 *
 * 踩过的坑（2026-11-01）：
 * `OnlineSourceScreen` 原本在内部 `remember(homeContent)` 保存一份 categoryId。
 * 切站点时上层把 `onlineHome` 置空、`onlineCategoryId` 置空，
 * 但**内部那份副本的 remember key 在新 home 到达前没变**，
 * 于是页面继续显示旧站点的分类列表 ——
 * 表现就是「站点是 A，分类却是 B 的，点进去还是空的」。
 *
 * 修法：改成受控组件，categoryId 完全由上层持有，页面内不再存副本。
 *
 * 这个测试断言的是「选中态解析」这条纯逻辑：
 * ```
 * 上层给的 categoryId 有值     → 用它
 * 上层给的为 null             → 退回第一个分类
 * 上层给的指向不存在的分类     → 也退回第一个（防止残留脏 id）
 * ```
 */
class CategorySelectionTest {

    private val categories = listOf(
        SourceCategory("1", "电影"),
        SourceCategory("2", "剧集"),
        SourceCategory("3", "综艺"),
    )

    @Test
    fun `上层指定的分类优先`() {
        assertEquals("2", resolveSelectedId("2", categories))
    }

    @Test
    fun `上层为 null 时退回第一个`() {
        assertEquals("1", resolveSelectedId(null, categories))
    }

    @Test
    fun `上层给了不存在的 id 也退回第一个`() {
        // 切站点后残留的旧 categoryId 就是这种情况
        assertEquals("1", resolveSelectedId("99", categories))
    }

    @Test
    fun `空分类列表返回 null`() {
        assertNull(resolveSelectedId("1", emptyList()))
    }

    /**
     * 模拟页面里的选中态解析逻辑。
     *
     * 和 `OnlineSourceScreen` 里的实际写法保持一致 ——
     * 如果那边改了算法，这个测试要跟着改。
     */
    private fun resolveSelectedId(
        requested: String?,
        list: List<SourceCategory>,
    ): String? {
        if (list.isEmpty()) return null
        return requested?.takeIf { id -> list.any { it.id == id } } ?: list.first().id
    }
}
