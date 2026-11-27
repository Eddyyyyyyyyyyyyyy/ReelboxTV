package com.reelix.app.data.search

import com.reelix.app.data.alist.*
import org.junit.Assert.*
import org.junit.Test

class LibraryResourceSearchTest {
    @Test fun `搜索内部文件名返回所属作品并保留匹配资源路径`() {
        val files = listOf(AlistVideo("/夸克/导演合集/独特片名.1080p.国语.mkv", "独特片名.1080p.国语.mkv", 1),
            AlistVideo("/夸克/导演合集/其他电影.mkv", "其他电影.mkv", 1))
        val entry = AlistLibraryBuilder.build(files, mapOf("/夸克/导演合集" to DirectoryMode.WORK)).items.single()
        val results = SearchMatcher.search("独特片名", listOf(entry.item))
        assertEquals(entry.item.id, results.single().item.id)
        assertEquals(files.first().path, results.single().item.matchedResourcePath)
        assertEquals(files.first().name, results.single().item.matchedResourceName)
        assertEquals("/夸克/导演合集", results.single().item.sourceId)
    }
}
