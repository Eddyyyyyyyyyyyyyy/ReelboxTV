package com.reelix.app.ui.screen.search

import org.junit.Assert.*
import org.junit.Test
import com.reelix.app.ui.component.KeyRouting

class SearchDraftTest {
    @Test fun `删除在按下生效抬起不重复长按限速`() {
        assertTrue(KeyRouting.searchDeleteStep(true, true, false, 1000, 0))
        assertFalse(KeyRouting.searchDeleteStep(false, true, true, 1010, 1000))
        assertFalse(KeyRouting.searchDeleteStep(true, true, true, 1050, 1000))
        assertTrue(KeyRouting.searchDeleteStep(true, true, true, 1100, 1000))
        assertFalse(KeyRouting.searchDeleteStep(true, false, false, 1000, 0))
        assertFalse(KeyRouting.searchDeleteStep(true, true, true, 900, 1000))
    }
    @Test fun `提交历史词直接进入结果态`() {
        val session = SearchSession()
        session.submit("蜘蛛侠")
        assertEquals("蜘蛛侠", session.query)
        assertEquals("蜘蛛侠", session.submittedQuery)
        assertTrue(session.showResults)
    }
    @Test fun `选词后连续删除修改实际草稿`() {
        val session = SearchSession()
        session.query = "蜘蛛侠3XXXX"
        repeat(5) { session.deleteDraftEnd() }
        assertEquals("蜘蛛侠", session.query)
    }
    @Test fun `删除按完整字符处理且空串安全`() {
        val session = SearchSession()
        session.query = "电影😀"
        session.deleteDraftEnd()
        assertEquals("电影", session.query)
        repeat(5) { session.deleteDraftEnd() }
        assertEquals("", session.query)
    }
}
