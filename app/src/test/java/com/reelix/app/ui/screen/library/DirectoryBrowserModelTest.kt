package com.reelix.app.ui.screen.library

import com.reelix.app.data.alist.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryBrowserModelTest {
    private val scan = LibraryScan(listOf(AlistVideo("/夸克/剧集/剧名/S01/E01.mkv", "E01.mkv", 1,
        mountRoot = "/夸克")), directories = listOf("/", "/夸克", "/夸克/剧集", "/夸克/剧集/剧名",
        "/夸克/剧集/剧名/S01", "/夸克/失败", "/其他"),
        failures = listOf(DirectoryFailure("/夸克/失败", "driver not init")))

    @Test fun `仅显示扫描范围内的本目录和直接子目录`() {
        val rows = directoryBrowserRows(scan, "/夸克/剧集", "/", emptyMap())
        assertEquals(listOf("/夸克/剧集", "/夸克/剧集/剧名"), rows.map { it.path })
        assertTrue(rows.first().current)
        assertEquals(1, rows.first().cachedVideos)
        assertEquals(1, rows.first().cachedChildren)
    }
    @Test fun `失败未扫描和零缓存保持不同含义`() {
        val rows = directoryBrowserRows(scan, "/", "/夸克", emptyMap())
        assertEquals("driver not init", rows.first { it.path.endsWith("失败") }.failure)
        val unknown = directoryBrowserRows(null, "/夸克", "/夸克", emptyMap()).single()
        assertFalse(unknown.scanned)
        assertEquals(0, unknown.cachedVideos)
        assertNull(unknown.failure)
    }
    @Test fun `网盘根来源于真实挂载字段`() {
        assertTrue(directoryBrowserRows(scan, "/", "/", emptyMap()).first { it.path == "/夸克" }.mount)
    }
    @Test fun `返回上级保持子目录和滚动位置且不可越界`() {
        val state = DirectoryBrowserPosition("/夸克")
        state.selected = "/夸克/剧集"
        state.remember(2, -18)
        assertTrue(state.enter("/夸克/剧集"))
        assertTrue(state.parent())
        assertEquals("/夸克/剧集", state.selected)
        assertEquals(2 to -18, state.viewport())
        assertFalse(state.parent())
        assertFalse(state.enter("/其他"))
        assertFalse(state.enter("/夸克2"))
    }
    @Test fun `浏览不修改规则对象`() {
        val rules = mapOf("/夸克/剧集" to DirectoryMode.CATEGORY)
        val rows = directoryBrowserRows(scan, "/", "/夸克", rules)
        assertEquals(DirectoryMode.CATEGORY, rows.first { it.path.endsWith("剧集") }.manual)
        assertEquals(1, rules.size)
    }
}
