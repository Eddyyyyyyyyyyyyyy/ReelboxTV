package com.reelix.app.ui.component

import com.reelix.app.data.model.Episode
import org.junit.Assert.*
import org.junit.Test

class ResourceVariantTest {
    @Test fun `仅从实际文件名突出清晰度和配音不猜测未知版本`() {
        val known = episodeCardPresentation(Episode(1, "电影.2160p.国粤双语.mkv", "alist", "/电影.2160p.国粤双语.mkv"))
        assertTrue(known.variants.contains("国粤双语"))
        val unknown = episodeCardPresentation(Episode(1, "电影.mkv", "alist", "/电影.mkv"))
        assertTrue(unknown.variants.isEmpty())
        assertEquals("电影.mkv", unknown.resourceName)
    }
}
