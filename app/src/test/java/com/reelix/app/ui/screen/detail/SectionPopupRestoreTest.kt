package com.reelix.app.ui.screen.detail

import org.junit.Assert.*
import org.junit.Test

class SectionPopupRestoreTest {
    @Test fun `selection uses opened stable ID instead of updated list position`() {
        assertEquals("season-2",
            selectedLibrarySectionId(listOf("season-1", "season-2"), listOf("season-2", "season-1"), 2))
    }

    @Test fun `removed selected section is refused instead of selecting a replacement`() {
        assertNull(selectedLibrarySectionId(listOf("season-1", "season-2"), listOf("season-1", "season-3"), 2))
        assertNull(selectedLibrarySectionId(listOf("season-1"), emptyList(), 1))
        assertNull(selectedLibrarySectionId(listOf("season-1"), listOf("season-1"), 99))
    }

    @Test fun `original available season button remains the return target`() {
        assertEquals(SectionPopupReturnTarget.ANCHOR, sectionPopupReturnTarget(true, true, true, true))
    }

    @Test fun `hidden season button falls back within its action region then play`() {
        assertEquals(SectionPopupReturnTarget.ARTWORK, sectionPopupReturnTarget(true, false, true, true))
        assertEquals(SectionPopupReturnTarget.PLAY, sectionPopupReturnTarget(true, false, false, true))
        assertEquals(SectionPopupReturnTarget.NONE, sectionPopupReturnTarget(true, false, false, false))
    }

    @Test fun `old page close callback cannot take focus on a replacement page`() {
        assertEquals(SectionPopupReturnTarget.NONE, sectionPopupReturnTarget(false, true, true, true))
    }
}
