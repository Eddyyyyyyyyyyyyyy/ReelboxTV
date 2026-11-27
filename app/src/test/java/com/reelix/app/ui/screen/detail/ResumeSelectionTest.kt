package com.reelix.app.ui.screen.detail

import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.PlayRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeSelectionTest {
    private fun route(name: String) = PlayRoute(name, listOf(Episode(1, "第1集", url = "x")))

    @Test fun `恢复历史线路顺序变化后仍按名称定位`() {
        val routes = listOf(route("线路B"), route("线路A"), route("线路C"))
        assertEquals(1, restoredRouteIndex(routes, "线路A"))
    }

    @Test fun `历史线路不存在或为空时回退第一条`() {
        val routes = listOf(route("线路A"), route("线路B"))
        assertEquals(0, restoredRouteIndex(routes, "线路X"))
        assertEquals(0, restoredRouteIndex(routes, null))
        assertEquals(0, restoredRouteIndex(emptyList(), "线路A"))
    }
}
