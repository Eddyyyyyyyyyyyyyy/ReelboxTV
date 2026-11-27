package com.reelix.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHeaderRegistryTest {

    @Test fun `登记的头能按 URL 取回`() {
        ImageHeaderRegistry.clear()
        val url = "https://img.example.com/a.jpg"
        ImageHeaderRegistry.put(url, mapOf("Referer" to "https://site.com/"))
        assertEquals(mapOf("Referer" to "https://site.com/"), ImageHeaderRegistry.headersFor(url))
    }

    @Test fun `未登记或空值返回空表`() {
        ImageHeaderRegistry.clear()
        assertTrue(ImageHeaderRegistry.headersFor("https://img.example.com/none.jpg").isEmpty())
        ImageHeaderRegistry.put("https://img.example.com/b.jpg", emptyMap())
        assertTrue(ImageHeaderRegistry.headersFor("https://img.example.com/b.jpg").isEmpty())
        ImageHeaderRegistry.put(null, mapOf("Referer" to "x"))
        assertTrue(ImageHeaderRegistry.headersFor("").isEmpty())
    }

    @Test fun `超出容量时淘汰最久未用的`() {
        ImageHeaderRegistry.clear()
        repeat(600) { i ->
            ImageHeaderRegistry.put("https://img.example.com/$i.jpg", mapOf("Referer" to "r$i"))
        }
        // 最早的那条应已被淘汰，最近的仍在。
        assertTrue(ImageHeaderRegistry.headersFor("https://img.example.com/0.jpg").isEmpty())
        assertEquals(
            mapOf("Referer" to "r599"),
            ImageHeaderRegistry.headersFor("https://img.example.com/599.jpg"),
        )
    }

    @Test fun `豆瓣图床才补 Referer`() {
        assertTrue(ImageSources.isDoubanImageHost("https://img1.doubanio.com/view/photo/a.jpg"))
        assertTrue(ImageSources.isDoubanImageHost("https://img9.doubanio.com/x.jpg"))
        assertFalse(ImageSources.isDoubanImageHost("https://gimg0.baidu.com/x.jpg"))
        assertFalse(ImageSources.isDoubanImageHost("https://notdoubanio.com/x.jpg"))

        val headers = ImageSources.headersFor("https://img3.doubanio.com/view/photo/a.jpg")
        assertEquals(ImageSources.DOUBAN_REFERER, headers["Referer"])
        assertTrue(headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
        assertTrue(ImageSources.headersFor("https://gimg0.baidu.com/x.jpg").isEmpty())
    }
}
