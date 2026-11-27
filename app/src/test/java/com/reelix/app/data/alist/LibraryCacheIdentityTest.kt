package com.reelix.app.data.alist

import org.junit.Assert.*
import org.junit.Test

class LibraryCacheIdentityTest {
    @Test fun `省略协议与大小写默认端口不应让缓存失效`() {
        assertEquals(normalizeLibraryServer("HOST:5244/"), normalizeLibraryServer("HTTP://host:5244"))
        assertEquals(normalizeLibraryServer("http://host:80"), normalizeLibraryServer("host"))
    }
    @Test fun `服务路径大小写和根目录变化仍须隔离`() {
        assertNotEquals(normalizeLibraryServer("http://host/Media"), normalizeLibraryServer("http://host/media"))
        assertEquals(normalizeLibraryRoot("foo/"), normalizeLibraryRoot("/foo"))
        assertNotEquals(normalizeLibraryRoot("/A"), normalizeLibraryRoot("/B"))
    }
}
