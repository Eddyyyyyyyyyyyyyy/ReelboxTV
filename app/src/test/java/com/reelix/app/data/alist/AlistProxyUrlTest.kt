package com.reelix.app.data.alist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlistProxyUrlTest {

    @Test
    fun buildsDownloadProxyWithEncodedPath() {
        val client = AlistClient("http://192.168.1.111:5244")
        val url = client.proxyUrl("/115/蜘蛛侠：崭新之日 (2026) - IMAX 2160p.mkv")
        assertTrue(url!!.startsWith("http://192.168.1.111:5244/d/115/"))
        assertTrue(url.contains("%E8%9C%98%E8%9B%9B%E4%BE%A0"))
        assertTrue(url.contains("2026"))
        assertTrue(url.contains("2160p.mkv"))
        assertFalse(url.contains("蜘蛛侠"))
        assertFalse(url.contains('?'))
    }

    @Test
    fun keepsAlistBasePath() {
        val client = AlistClient("http://fixture.test/alist")
        val url = client.proxyUrl("/115/movie.mp4")
        assertTrue(url!!.startsWith("http://fixture.test/alist/d/115/movie.mp4"))
    }
}
