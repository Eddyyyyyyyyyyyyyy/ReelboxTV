package com.reelix.app.data.image

import org.junit.Assert.*
import org.junit.Test

class ArtworkReferencePolicyTest {
    @Test fun `共享图片和仍使用的手动文件必须保留`() {
        assertFalse(artworkCanBeEvicted("https://example.com/shared", setOf("https://example.com/shared")))
        assertTrue(artworkCanBeEvicted("https://example.com/old", setOf("https://example.com/shared")))
        assertFalse(manualArtworkCanBeReclaimed("poster-live.jpg", setOf("poster-live.jpg"), emptySet()))
        assertFalse(manualArtworkCanBeReclaimed("backdrop-saving.jpg", emptySet(), setOf("backdrop-saving.jpg")))
        assertFalse(manualArtworkCanBeReclaimed("account.json", emptySet(), emptySet()))
        assertTrue(manualArtworkCanBeReclaimed("poster-old.jpg", emptySet(), emptySet()))
    }

    @Test fun `保护名单必须同时包含竖图和横图`() {
        val refs = collectArtworkReferences(
            listOf(
                "https://example.com/poster-a" to "https://example.com/backdrop-a",
                "https://example.com/poster-b" to null,
                null to "https://example.com/backdrop-c",
                "" to "  ",
            ),
        )
        assertEquals(
            setOf(
                "https://example.com/poster-a",
                "https://example.com/backdrop-a",
                "https://example.com/poster-b",
                "https://example.com/backdrop-c",
            ),
            refs,
        )
    }

    @Test fun `片名映射只在无引用时丢弃`() {
        val keep = artworkTitleKeys(listOf("三体 S2", "Dune: Part Two (2024)"))
        assertFalse(titleCacheCanBeForgotten("三体 S2", keep))
        // 归一化会去掉季数与画质后缀，因此这些都算仍被引用。
        assertFalse(titleCacheCanBeForgotten("三体 S2 4K", keep))
        assertFalse(titleCacheCanBeForgotten("三体 S1", keep))
        assertFalse(titleCacheCanBeForgotten("三体(2024)", keep))
        assertTrue(titleCacheCanBeForgotten("繁花", keep))
        // 空片名没有可清的映射，不算"可以丢弃"。
        assertFalse(titleCacheCanBeForgotten("   ", keep))
        assertFalse(titleCacheCanBeForgotten(null, keep))
    }
}
