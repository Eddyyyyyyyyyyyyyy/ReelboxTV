package com.reelix.app.ui.screen.library

import org.junit.Assert.*
import org.junit.Test

class LibraryDialogFocusStateTest {
    @Test fun `return follows candidate key after reorder`() {
        val anchor = LibraryDialogPosterAnchor(listOf("a", "b", "c"), "b")
        assertEquals(2, anchor.position(listOf("c", "a", "b")))
    }

    @Test fun `removed candidate returns to next surviving neighbor not old index`() {
        val anchor = LibraryDialogPosterAnchor(listOf("a", "b", "c", "d", "e"), "c")
        assertEquals(2, anchor.position(listOf("inserted", "e", "d", "a")))
    }

    @Test fun `removed candidate uses previous neighbor when next neighbor vanished`() {
        val anchor = LibraryDialogPosterAnchor(listOf("a", "b", "c", "d"), "c")
        assertEquals(1, anchor.position(listOf("a", "b", "new")))
    }

    @Test fun `entirely replaced candidates return to search instead of arbitrary first or last`() {
        val anchor = LibraryDialogPosterAnchor(listOf("a", "b", "c"), "b")
        assertNull(anchor.position(listOf("x", "y", "z")))
        assertNull(anchor.position(emptyList()))
    }

    @Test fun `child opening invalidates pending search or layout focus`() {
        val state = LibraryDialogFocusState()
        val ticket = state.invalidate()
        assertTrue(state.accepts(ticket))
        state.openChild()
        assertFalse(state.accepts(ticket))
        assertFalse(state.accepts(state.generation))
        state.closeChild()
        assertFalse(state.accepts(ticket))
        assertTrue(state.accepts(state.generation))
    }

    @Test fun `new request supersedes old queued return`() {
        val state = LibraryDialogFocusState()
        val previous = state.invalidate()
        val current = state.invalidate()
        assertFalse(state.accepts(previous))
        assertTrue(state.accepts(current))
    }

    @Test fun `dismissed parent never accepts callback even when child later closes`() {
        val state = LibraryDialogFocusState()
        state.openChild()
        state.close()
        state.closeChild()
        assertFalse(state.accepts(state.generation))
        assertFalse(state.accepts(state.invalidate()))
    }
}
