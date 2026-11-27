package com.reelix.app.data.alist

import org.junit.Assert.*
import org.junit.Test

class LibraryLoadPolicyTest {
    @Test fun `关闭自动扫描仍然恢复缓存`() {
        assertEquals(LibraryLoadAction.RESTORE, libraryLoadAction(true, false, false))
    }
    @Test fun `关闭扫描且没有缓存时保持待刷新`() {
        assertEquals(LibraryLoadAction.IDLE, libraryLoadAction(false, false, false))
    }
    @Test fun `手动刷新跳过缓存执行扫描`() {
        assertEquals(LibraryLoadAction.SCAN, libraryLoadAction(true, false, true))
    }
}
