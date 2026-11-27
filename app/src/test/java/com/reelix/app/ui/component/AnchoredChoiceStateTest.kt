package com.reelix.app.ui.component

import org.junit.Assert.*
import org.junit.Test

class AnchoredChoiceStateTest {
    @Test fun `close invalidates pending layout and initial focus callbacks`() {
        val state = AnchoredChoiceState(8, 5)
        val ticket = state.focusTicket()
        assertTrue(state.canFocus(ticket))
        state.close()
        assertFalse(state.canFocus(ticket))
        assertNull(state.move(1))
    }

    @Test fun `new direction invalidates the posted initial selection`() {
        val state = AnchoredChoiceState(8, 5)
        val initial = state.focusTicket()
        assertEquals(6, state.move(1))
        assertFalse(state.canFocus(initial))
        assertTrue(state.canFocus(state.focusTicket()))
    }

    @Test fun `confirm release must belong to the same physical key`() {
        val state = AnchoredChoiceState(3, 1)
        state.press(23, 0)
        assertTrue(state.ownsKey(23))
        assertFalse(state.ownsKey(66))
        state.release(23)
        assertFalse(state.ownsKey(23))
    }

    @Test fun `focus loss and close clear key ownership and forbid new ownership`() {
        val state = AnchoredChoiceState(3, 1)
        state.press(4, 0)
        state.cancelKeys()
        assertFalse(state.ownsKey(4))
        state.press(23, 0)
        state.close()
        assertFalse(state.ownsKey(23))
        state.press(23, 0)
        assertFalse(state.ownsKey(23))
    }

    @Test fun `empty choices and repeated boundary keys do not throw`() {
        assertNull(AnchoredChoiceState(0, 0).move(1))
        val state = AnchoredChoiceState(3, 2)
        assertEquals(2, state.move(1))
        assertEquals(1, state.move(-1))
        assertEquals(0, state.move(-1))
        assertEquals(0, state.move(-1))
    }

    @Test fun `late selection notification cannot overwrite closed state`() {
        val state = AnchoredChoiceState(10, 7)
        state.close()
        state.focused(0)
        assertEquals(7, state.position)
    }

    @Test fun `apply closes ownership before callback and dismisses even on failure`() {
        val state = AnchoredChoiceState(10, 7)
        val ticket = state.focusTicket()
        var dismissed = false
        try {
            state.applyChoice(7, onSelect = {
                assertFalse(state.isOpen)
                assertFalse(state.canFocus(ticket))
                throw IllegalStateException("loading failed")
            }, onDismiss = { dismissed = true })
            fail("expected callback failure")
        } catch (_: IllegalStateException) {
            assertTrue(dismissed)
        }
    }

    @Test fun `late or repeated apply cannot run a second selection`() {
        val state = AnchoredChoiceState(3, 1)
        var applied = 0
        var dismissed = 0
        state.applyChoice(99, { applied++ }, { dismissed++ })
        assertEquals(0, applied)
        assertTrue(state.isOpen)
        state.applyChoice(1, { applied++ }, { dismissed++ })
        state.applyChoice(1, { applied++ }, { dismissed++ })
        assertEquals(1, applied)
        assertEquals(1, dismissed)
    }
}
