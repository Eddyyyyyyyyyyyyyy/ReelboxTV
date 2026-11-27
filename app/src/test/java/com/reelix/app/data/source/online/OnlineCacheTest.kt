package com.reelix.app.data.source.online

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineCacheTest {

    private fun item(id: String, title: String = id) = MediaItem(
        id = id,
        title = title,
        sourceKey = "site",
        sourceId = id,
    )

    private fun home(vararg titles: String) = HomeContent(
        categories = listOf(SourceCategory("1", "电影")),
        recommendations = titles.map { item(it) },
    )

    private fun detail(vararg eps: String) = MediaDetail(
        item = item("vod-1"),
        routes = listOf(
            PlayRoute(
                name = "线路1",
                episodes = eps.mapIndexed { index, name ->
                    Episode(index + 1, name, flag = "线路1", playId = name)
                },
            )
        ),
    )

    @Test
    fun `disk snapshot is visible before any network result`() {
        val store = OnlineCache.Store.Memory
        store.value = null
        val first = OnlineCache(store) { 1_000L }
        first.load()
        first.putHomeForSite("site", home("A"))
        first.putDetail("site", "vod-1", detail("第1集", "第2集"))

        val second = OnlineCache(store) { 2_000L }
        assertTrue(second.load())
        assertEquals("A", second.cachedHome()?.data?.recommendations?.first()?.title)
        assertEquals(2, second.cachedDetail("site", "vod-1")?.data?.routes?.first()?.episodes?.size)
    }

    @Test
    fun `empty home response must not wipe a usable snapshot`() {
        val cache = OnlineCache(now = { 1L })
        cache.putHomeForSite("site", home("A"))
        cache.putHomeForSite("site", HomeContent())
        assertEquals("A", cache.cachedHome()?.data?.recommendations?.single()?.title)
    }

    @Test
    fun `empty category response must not wipe saved items`() {
        val cache = OnlineCache()
        cache.putCategory("site", "1", CategoryContent(items = listOf(item("A"))))
        cache.putCategory("site", "1", CategoryContent(items = emptyList()))
        assertEquals(1, cache.cachedCategory("site", "1")?.data?.items?.size)
    }

    @Test
    fun `home snapshot is site scoped`() {
        val cache = OnlineCache()
        cache.putHomeForSite("site-a", home("A"))
        assertNotNull(cache.cachedHomeFor("site-a"))
        assertNull("切到 site-b 不能拿 site-a 的海报", cache.cachedHomeFor("site-b"))
    }

    @Test
    fun `detail without routes is never cached as a success`() {
        val cache = OnlineCache()
        val shell = MediaDetail(item = item("vod-1"))
        assertNull(cache.putDetail("site", "vod-1", shell))
        assertNull(cache.cachedDetail("site", "vod-1"))
    }

    @Test
    fun `detail cache survives restart and keeps lines and episodes`() {
        val store = OnlineCache.Store.Memory
        store.value = null
        OnlineCache(store).putDetail("site", "vod-1", detail("第1集", "第2集"))
        val restored = OnlineCache(store).also { it.load() }
        val cached = restored.cachedDetail("site", "vod-1")
        assertEquals("线路1", cached?.data?.routes?.first()?.name)
        assertEquals(listOf("第1集", "第2集"), cached?.data?.routes?.first()?.episodes?.map { it.name })
    }

    @Test
    fun `subscription is not reused for a different source identity`() {
        val cache = OnlineCache()
        cache.putSubscription("old-url", "old-config", "{\"sites\":[]}")
        assertNotNull(cache.cachedSubscription("old-url"))
        assertNull(cache.cachedSubscription("new-url"))
    }

    @Test
    fun `play resolution returns only unexpired address`() {
        PlayResolutionCache.clear()
        val key = PlayResolutionCache.key("site", "线路1", "第1集")
        PlayResolutionCache.put(key, "https://cdn.example/a.m3u8", mapOf("Referer" to "r"), now = 10_000)
        assertEquals("https://cdn.example/a.m3u8", PlayResolutionCache.get(key, 11_000)?.url)
        assertNull("直链不能无限期复用", PlayResolutionCache.get(key, 10_000 + 3 * 60_000))
    }

    @Test
    fun `failed play resolution is not cached`() {
        PlayResolutionCache.clear()
        val key = PlayResolutionCache.key("site", "线路1", "第1集")
        PlayResolutionCache.clear()
        PlayResolutionCache.put(key, "", emptyMap(), now = 1_000)
        assertNull(PlayResolutionCache.get(key, 1_100))
    }

    @Test
    fun `cache clears all snapshots`() {
        val cache = OnlineCache()
        cache.putHomeForSite("site", home("A"))
        cache.putDetail("site", "vod-1", detail("第1集"))
        cache.clear()
        assertNull(cache.cachedHome())
        assertNull(cache.cachedDetail("site", "vod-1"))
    }

    @Test
    fun `play cache only stores url and headers`() {
        PlayResolutionCache.clear()
        val key = PlayResolutionCache.key("site", "线路1", "第1集")
        PlayResolutionCache.put(key, "https://cdn.example/a.m3u8", mapOf("Referer" to "r"), now = 1_000)
        val hit = PlayResolutionCache.get(key, 1_100)
        assertEquals(mapOf("Referer" to "r"), hit?.headers)
        assertEquals("https://cdn.example/a.m3u8", hit?.url)
    }

    @Test
    fun `site list and selected default survive restart`() {
        val store = OnlineCache.Store.Memory
        store.value = null
        OnlineCache(store).putSites(
            listOf(
                OnlineSiteSnapshot(key = "a", name = "源A", type = 3, api = "csp_A"),
                OnlineSiteSnapshot(key = "b", name = "源B", type = 3, api = "csp_B", fromJar = true),
            ),
            selectedKey = "b",
        )
        val restored = OnlineCache(store).also { it.load() }
        assertEquals(listOf("a", "b"), restored.cachedSites()?.map { it.key })
        assertEquals("b", restored.cachedSelectedSiteKey())
    }

    @Test
    fun `refreshing site descriptors does not erase selected default`() {
        val cache = OnlineCache()
        cache.putSites(listOf(OnlineSiteSnapshot(key = "a", name = "源A")), selectedKey = "a")
        cache.putSites(listOf(OnlineSiteSnapshot(key = "a", name = "源A"), OnlineSiteSnapshot(key = "b", name = "源B")))
        assertEquals("a", cache.cachedSelectedSiteKey())
    }

    @Test
    fun `selecting a site keeps it after a descriptor refresh without a selection`() {
        val store = OnlineCache.Store.Memory
        store.value = null
        val first = OnlineCache(store)
        first.putSites(
            listOf(OnlineSiteSnapshot(key = "a", name = "源A"), OnlineSiteSnapshot(key = "b", name = "源B")),
            selectedKey = "b",
        )
        val restarted = OnlineCache(store).also { it.load() }
        restarted.putSites(listOf(OnlineSiteSnapshot(key = "a", name = "源A"), OnlineSiteSnapshot(key = "b", name = "源B")))
        assertEquals("b", restarted.cachedSelectedSiteKey())
    }
}
