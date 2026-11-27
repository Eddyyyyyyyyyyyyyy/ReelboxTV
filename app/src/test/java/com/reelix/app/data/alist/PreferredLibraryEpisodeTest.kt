package com.reelix.app.data.alist

import com.reelix.app.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class PreferredLibraryEpisodeTest {
    private val episodes = listOf(Episode(1, "国配", "alist", "/a.mkv"), Episode(2, "粤配", "alist", "/b.mkv"))
    @Test fun `搜索匹配优先于上次选择仅选当前作品中存在的资源`() {
        assertEquals("/a.mkv", preferredLibraryEpisode(episodes, "/a.mkv", "/b.mkv", "/b.mkv")?.playId)
        assertEquals("/b.mkv", preferredLibraryEpisode(episodes, null, "/b.mkv", "/a.mkv")?.playId)
        assertNull(preferredLibraryEpisode(episodes, "/outside.mkv", "/gone.mkv", null))
    }
}
