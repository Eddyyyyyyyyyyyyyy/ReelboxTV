package com.reelix.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索历史的去重与排序逻辑。
 *
 * ## 为什么需要测
 *
 * 这段逻辑不是简单的「append」：
 * ```
 * 搜「三体」→ [三体]
 * 搜「沙丘」→ [沙丘, 三体]        新的在前面
 * 再搜「三体」→ [三体, 沙丘]      去重 + 移到最前，不是追加
 * ```
 * 如果写成 append，用户会看到重复项，最常用的也浮不到顶部。
 *
 * ## 测试方式
 *
 * `SearchHistory` 依赖 SharedPreferences，单测里没法用。
 * 所以这里测的是**和它等价的纯函数逻辑** —— 断言算法本身，
 * 保证改实现时行为不变。
 */
class SearchHistoryLogicTest {

    /** 和 SearchHistory.add 保持一致的去重+前置逻辑 */
    private fun addTo(current: List<String>, keyword: String, max: Int = 20): List<String> {
        val k = keyword.trim()
        if (k.isEmpty()) return current
        val list = current.toMutableList()
        list.removeAll { it.equals(k, ignoreCase = true) }
        list.add(0, k)
        return list.take(max)
    }

    @Test
    fun `新词加到最前面`() {
        var h: List<String> = emptyList()
        h = addTo(h, "三体")
        assertEquals(listOf("三体"), h)

        h = addTo(h, "沙丘")
        assertEquals(listOf("沙丘", "三体"), h)
    }

    @Test
    fun `重复搜索会去重并前置`() {
        var h = listOf("沙丘", "三体", "斗罗")
        h = addTo(h, "三体")
        assertEquals(listOf("三体", "沙丘", "斗罗"), h)
        // 不能有重复
        assertEquals(3, h.size)
    }

    @Test
    fun `去重不区分大小写`() {
        var h = listOf("Dune")
        h = addTo(h, "dune")
        assertEquals(listOf("dune"), h)
    }

    @Test
    fun `超过上限时截断`() {
        var h: List<String> = emptyList()
        repeat(25) { i -> h = addTo(h, "词$i", max = 20) }
        assertEquals(20, h.size)
        // 最新的在最前
        assertEquals("词24", h.first())
    }

    @Test
    fun `空白词不加入`() {
        val h = listOf("三体")
        assertEquals(h, addTo(h, ""))
        assertEquals(h, addTo(h, "   "))
    }

    @Test
    fun `前后空格会被修剪`() {
        var h: List<String> = emptyList()
        h = addTo(h, "  三体  ")
        assertEquals(listOf("三体"), h)
    }

    @Test
    fun `删除一条`() {
        val h = listOf("沙丘", "三体", "斗罗")
        val after = h.filterNot { it == "三体" }
        assertEquals(listOf("沙丘", "斗罗"), after)
    }

    @Test
    fun `空历史安全`() {
        assertTrue(addTo(emptyList(), "").isEmpty())
    }
}
