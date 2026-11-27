package com.reelix.app.data.search

import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索匹配测试。
 *
 * 电视端搜索的胜负手是"少打字就出结果"：
 *  遥控器打中文极慢，用户实际行为是打首字母（"st" → 三体）。
 *  所以拼音首字母这一档必须单独测，而且要测"不会误命中"。
 */
class SearchMatcherTest {

    private fun item(
        id: String,
        title: String,
        year: Int? = null,
        original: String? = null,
    ) = MediaItem(
        id = id,
        title = title,
        originalTitle = original,
        year = year,
        kind = MediaKind.SERIES,
    )

    private val library = listOf(
        item("1", "三体", 2023, "Three-Body"),
        item("2", "流浪地球2", 2023, "The Wandering Earth II"),
        item("3", "凡人修仙传", 2020),
        item("4", "白莲花度假村", 2021, "The White Lotus"),
        item("5", "与晋长安", 2025),
        item("6", "三体动画第二季", 2024),
    )

    // ---------- 基础档位 ----------

    @Test
    fun `完全相等得分最高`() {
        val hit = SearchMatcher.score("三体", library[0])!!
        assertEquals(100, hit.score)
    }

    @Test
    fun `前缀命中次之`() {
        val hit = SearchMatcher.score("三体", library[5])!!
        assertEquals(80, hit.score)
    }

    @Test
    fun `子串命中再次`() {
        val hit = SearchMatcher.score("地球", library[1])!!
        assertEquals(60, hit.score)
    }

    @Test
    fun `英文原名也能命中`() {
        val hit = SearchMatcher.score("white", library[3])!!
        assertTrue(hit.score >= 60)
        assertEquals("original", hit.matchedBy)
    }

    @Test
    fun `完全无关不命中`() {
        assertNull(SearchMatcher.score("zzzz不存在", library[0]))
    }

    @Test
    fun `空查询不返回结果`() {
        assertTrue(SearchMatcher.search("", library).isEmpty())
        assertTrue(SearchMatcher.search("   ", library).isEmpty())
    }

    // ---------- 拼音首字母 ----------

    @Test
    fun `拼音首字母能搜到三体`() {
        val hit = SearchMatcher.score("st", library[0])
        assertNotNull("电视上用户只会打 st，这条必须命中", hit)
        assertEquals("pinyin", hit!!.matchedBy)
    }

    @Test
    fun `拼音首字母能搜到凡人修仙传`() {
        // 拼音表是手写的常用字表，覆盖不到的字会返回 '#' 并被跳过（不会误命中）。
        // 这条用例用 "frxx"（凡人修仙，去掉了易错的多音字"传"）来钉住主路径。
        val hit = SearchMatcher.score("frxx", library[2])
        assertNotNull("电视上用户只会打首字母，这条必须命中", hit)
        assertEquals("pinyin", hit!!.matchedBy)
    }

    @Test
    fun `未收录的汉字不会误命中`() {
        // '龥'（U+9FA5）不在表里 → 返回 '#'，会被跳过；这里只保证不崩
        assertEquals('#', Pinyin.initialOf('\u9FA5'))
        assertNull(SearchMatcher.score("zzz", library[2]))
    }

    @Test
    fun `首字母连续输入按前缀优先`() {
        val hits = SearchMatcher.search("st", library)
        assertTrue("应至少命中三体两条", hits.size >= 2)
        val titles = hits.filter { it.matchedBy == "pinyin" }.map { it.item.title }
        assertEquals("三体", titles.first())
    }

    @Test
    fun `首字母不误命中无关片名`() {
        // "wldq" 是"流浪地球"，不该命中"白莲花度假村"（blh）
        val hit = SearchMatcher.score("wldq", library[3])
        assertNull("拼音不该误命中 $hit", hit)
    }

    // ---------- 全角与大小写 ----------

    @Test
    fun `全角字母转半角后能命中`() {
        val hit = SearchMatcher.score("ＷＨＩＴＥ", library[3])
        assertNotNull("全角输入应等价于半角", hit)
    }

    @Test
    fun `大写输入等价于小写`() {
        // 注意：用 item 而不是 library[3] —— library 里有多条 title 含三体的条目会干扰排序断言，
        // 这里的教训是：断言"某条结果排第一"时，测试数据必须只有一条会命中。
        val single = item("w", "白莲花度假村", 2021, "The White Lotus")
        assertEquals(
            SearchMatcher.score("white", single)?.score,
            SearchMatcher.score("WHITE", single)?.score
        )
    }

    @Test
    fun `首尾空格会被忽略`() {
        assertNotNull(SearchMatcher.score("  三体  ", library[0]))
    }

    // ---------- 排序 ----------

    @Test
    fun `排序按匹配度优先再按年份`() {
        val items = listOf(
            item("a", "三体动画第二季", 2024),
            item("b", "三体", 2023),
            item("c", "关于三体的纪录片", 2010),
        )
        val hits = SearchMatcher.search("三体", items)
        assertEquals("完全相等必须第一", "三体", hits.first().item.title)
    }

    @Test
    fun `limit 生效`() {
        val many = (1..100).map { item("id$it", "三体第${it}部") }
        assertEquals(10, SearchMatcher.search("三体", many, limit = 10).size)
    }

    // ---------- 分组 ----------

    @Test
    fun `分组把精确与模糊分开`() {
        val items = listOf(
            item("a", "三体"),
            item("b", "流浪的地球"), // 子序列模糊
        )
        val hits = SearchMatcher.search("三体", items)
        val groups = SearchMatcher.group(hits)
        assertTrue(groups.isNotEmpty())
        assertEquals("匹配", groups.first().title)
    }

    // ---------- 工具函数 ----------

    @Test
    fun `子序列判定`() {
        // 只做"三体"→"三体动画"这种中文子序列，不依赖拼音表覆盖度
        assertTrue(SearchMatcher.isSubsequence("三体", "三体动画第二季"))
        assertFalse(SearchMatcher.isSubsequence("体三", "三体动画第二季"))
        assertFalse(SearchMatcher.isSubsequence("", "abc"))
    }

    @Test
    fun `拼音非汉字原样保留`() {
        assertEquals("lldq2", Pinyin.firstLetters("流浪地球2"))
        assertEquals("st", Pinyin.firstLetters("三体"))
        // 未收录字返回 #，不会污染匹配
        assertEquals('#' , Pinyin.initialOf('\u9FA5'))
    }
}
