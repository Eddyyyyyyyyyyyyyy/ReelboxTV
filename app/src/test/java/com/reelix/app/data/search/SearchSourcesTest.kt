package com.reelix.app.data.search

import com.reelix.app.data.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourcesTest {

    private fun item(id: String, sourceKey: String = "") =
        MediaItem(id = id, title = id, sourceKey = sourceKey)

    @Test
    fun `360kan parser reads title and keeps source order`() {
        val body = """
            {"data":[
              {"title":"战狼2"},
              {"title":"开心锤锤"},
              {"title":"战狼2"},
              {"title":"抓特务"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("战狼2", "开心锤锤", "抓特务"),
            HotSearch.parseWords(body),
        )
    }

    @Test
    fun `iqiyi parser reads data name and ignores blanks`() {
        val body = """
            {"code":"A00000","data":[
              {"name":"三体"},
              {"name":"三体电视剧真人版"},
              {"name":"  "}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("三体", "三体电视剧真人版"),
            SearchSuggest.parseSuggestions(body),
        )
    }

    @Test
    fun `local category follows the home site and all keeps home local other order`() {
        val groups = SearchResultGroups.build(
            localItems = listOf(item("local")),
            siteGroups = listOf(
                SearchResultGroup("site-a", "源A", listOf(item("home", "site-a"))),
                SearchResultGroup("site-b", "源B", listOf(item("other", "site-b"))),
            ),
            homeSiteKey = "site-a",
        )

        assertEquals(
            listOf("全部显示", "源A", "本地", "源B"),
            groups.map { it.label },
        )
        assertEquals(
            listOf("home", "local", "other"),
            groups.first().items.map { it.id },
        )
    }

    @Test
    fun `result groups hide empty local and keep each site separate`() {
        val groups = SearchResultGroups.build(
            localItems = emptyList(),
            siteGroups = listOf(
                SearchResultGroup("site-b", "源B", listOf(item("other", "site-b"))),
            ),
            homeSiteKey = null,
        )

        assertEquals(listOf("全部显示", "源B"), groups.map { it.label })
    }

    @Test
    fun `local is second when home site has no result`() {
        val groups = SearchResultGroups.build(
            localItems = listOf(item("local")),
            siteGroups = listOf(
                SearchResultGroup("site-b", "源B", listOf(item("other", "site-b"))),
            ),
            homeSiteKey = null,
        )

        assertEquals(listOf("全部显示", "本地", "源B"), groups.map { it.label })
        assertEquals(listOf("local", "other"), groups.first().items.map { it.id })
    }

    @Test
    fun `local item key gets local prefix`() {
        val key = SearchResultGroups.itemKey(item("abc"))
        assertTrue(key.startsWith("local:"))
        assertEquals("local:abc", key)
    }

    @Test
    fun `library item keeps local prefix even if source key is set`() {
        val key = SearchResultGroups.itemKey(item("abc", "stale-site").copy(fromLibrary = true))
        assertEquals("local:abc", key)
    }
}
