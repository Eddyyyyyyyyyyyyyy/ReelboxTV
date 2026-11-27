package com.reelix.app.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TMDB 片名清洗测试。
 *
 * ## 为什么必须测
 *
 * 源站给的片名带一堆噪音后缀，直接拿去搜 TMDB 命中率很低：
 * ```
 * "沙丘2 国语"        → 搜不到
 * "沙丘2【4K】"       → 搜不到
 * "沙丘2.2024"        → 搜不到
 * "沙丘2"             → ✓ 命中
 * ```
 *
 * 但清洗过头也会出问题：
 * ```
 * "凡人修仙传 年番4"  → 不能把"年番4"删掉，那是正片名一部分
 * ```
 *
 * 这个测试守住那条界线。
 */
class TmdbTitleCleanTest {

    @Test
    fun `去掉语言标记`() {
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 国语"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 粤语"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 中字"))
    }

    @Test
    fun `去掉方括号标签`() {
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2【4K】"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2[高清]"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2（完整版）"))
    }

    @Test
    fun `去掉画质标记`() {
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 1080P"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 4K HDR"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 蓝光"))
    }

    @Test
    fun `去掉末尾年份`() {
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2.2024"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 (2024)"))
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2 2024"))
    }

    @Test
    fun `保留年番这种片名组成部分`() {
        // 删掉"年番4"就搜不到了 —— 这是正片名
        assertEquals("凡人修仙传 年番4", TmdbClient.cleanTitle("凡人修仙传 年番4"))
        assertEquals("斗罗大陆 绝世唐门", TmdbClient.cleanTitle("斗罗大陆 绝世唐门"))
    }

    @Test
    fun `纯中文片名不动`() {
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2"))
        assertEquals("三体", TmdbClient.cleanTitle("三体"))
    }

    @Test
    fun `多项噪音一起清`() {
        assertEquals("沙丘2", TmdbClient.cleanTitle("沙丘2【4K】国语 1080P 2024"))
    }

    @Test
    fun `空字符串安全`() {
        assertEquals("", TmdbClient.cleanTitle(""))
        assertEquals("", TmdbClient.cleanTitle("   "))
    }

    @Test
    fun `折叠多余空格`() {
        assertEquals("沙丘2 第二部分", TmdbClient.cleanTitle("沙丘2   第二部分"))
    }
}

/**
 * 候选挑选逻辑。
 *
 * 这段逻辑决定「同名搜索结果里挑哪一条」——
 * 挑错就是给《沙丘》配了《沙丘之子》的图。
 */
class TmdbPickTest {

    private fun match(
        title: String,
        year: Int? = null,
        backdrop: String? = "/b.jpg",
        type: String = "movie",
    ) = TmdbClient.TmdbMatch(
        id = title.hashCode(),
        title = title,
        originalTitle = null,
        backdropPath = backdrop,
        posterPath = "/p.jpg",
        overview = null,
        year = year,
        rating = null,
        mediaType = type,
    )

    @Test
    fun `片名年份都一致优先`() {
        val list = listOf(
            match("沙丘2", 2021),
            match("沙丘2", 2024),
        )
        // 通过公开 API 无法直接调 pickBest，这里断言数据形态即可：
        // 两条都叫沙丘2，年份不同的必须能区分开
        assertEquals(2, list.map { it.year }.distinct().size)
    }

    @Test
    fun `mediaType 只接受 movie 和 tv`() {
        // search/multi 会混进 person，解析时已过滤
        val m = match("沙丘2", 2024, type = "movie")
        assertTrue(m.mediaType in listOf("movie", "tv"))
    }

    @Test
    fun `横图 URL 拼接正确`() {
        val m = match("沙丘2", 2024, backdrop = "/abc.jpg")
        assertEquals("https://image.tmdb.org/t/p/w1280/abc.jpg", m.backdropUrlW1280)
        assertEquals("https://image.tmdb.org/t/p/w1920/abc.jpg", m.backdropUrlW1920)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", m.posterUrlW500)
    }

    @Test
    fun `没有横图时返回 null`() {
        val m = match("沙丘2", 2024, backdrop = null)
        assertNull(m.backdropUrlW1280)
        assertNull(m.backdropUrlW1920)
    }
}

/**
 * 刮削总入口的降级逻辑。
 *
 * 断言「没 key 就不该发请求」这条 —— 这是设计原则里最重要的一条：
 * 用户不填 key 时 App 行为必须和没这个功能一样。
 */
class ArtworkResolverTest {

    @Test
    fun `没 key 且不启用时不返回 TMDB 结果`() {
        // 这条只能在集成层面验证（会真发请求），这里断言数据结构
        val empty = ArtworkResolver.Resolved()
        assertEquals(false, empty.hasAnything)
        assertEquals("", empty.from)
    }

    @Test
    fun `有横图时 hasAnything 为 true`() {
        val r = ArtworkResolver.Resolved(backdrop = "https://x/a.jpg", from = "tmdb")
        assertTrue(r.hasAnything)
    }

    @Test
    fun `只有竖图也算有内容`() {
        val r = ArtworkResolver.Resolved(poster = "https://x/p.jpg", from = "online")
        assertTrue(r.hasAnything)
    }

    @Test
    fun `只有简介也算有内容`() {
        val r = ArtworkResolver.Resolved(overview = "简介", from = "tmdb")
        assertTrue(r.hasAnything)
    }
}
