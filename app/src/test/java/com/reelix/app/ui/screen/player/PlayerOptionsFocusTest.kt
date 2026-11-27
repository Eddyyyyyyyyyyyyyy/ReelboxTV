package com.reelix.app.ui.screen.player

import com.reelix.app.ui.component.KeyRouting
import org.junit.Assert.*
import org.junit.Test

class PlayerOptionsFocusTest {
    @Test fun `missing option follows surviving original neighbor through insertion and reorder`() {
        assertEquals(1, restorePlayerOptionIndex(listOf("new", "c", "auto", "a"), "b", 2,
            previousKeys = listOf("auto", "a", "b", "c")))
    }

    @Test fun `entirely replaced options do not select an unrelated slot`() {
        assertEquals(-1, restorePlayerOptionIndex(listOf("new-1", "new-2"), "old-2", 1,
            previousKeys = listOf("old-1", "old-2")))
    }

    @Test fun `cancelled physical confirm is consumed without applying its target`() {
        val keys = PlayerPanelKeyOwnership()
        val target = PlayerPanelConfirmTarget("audio", "options", "track:b")
        keys.onDown(23, 100, target)
        assertNull(keys.releaseConfirm(23, 100, target, cancelled = true))
        assertFalse(keys.owns(23, 100))
    }

    @Test fun `same label routes retain distinct keys and follow identity through reorder`() {
        fun route(flag: String) = com.reelix.app.data.model.PlayRoute("Same label",
            listOf(com.reelix.app.data.model.Episode(1, "EP01", flag, "$flag/1")))
        val a = route("source-a")
        val b = route("source-b")
        val keys = playerRouteOptionKeys(listOf(a, b))
        assertEquals(2, keys.distinct().size)
        assertEquals(keys.reversed(), playerRouteOptionKeys(listOf(b, a)))
    }

    @Test fun `resource key survives reorder and selected marker changes`() {
        assertEquals(0, restorePlayerOptionIndex(listOf("audio:b", "audio:auto", "audio:a"), "audio:b", 2, 1))
        assertEquals(2, restorePlayerOptionIndex(listOf("e3", "e1", "e2"), "e2", 1, 0))
    }

    @Test fun `removed resource uses nearest remaining position not selected first item`() {
        assertEquals(2, restorePlayerOptionIndex(listOf("auto", "a", "c"), "b", 2, 0))
        assertEquals(1, restorePlayerOptionIndex(listOf("auto", "a"), "b", 3, 0))
    }

    @Test fun `removed group stays near its previous position`() {
        assertEquals(3, restorePlayerOptionIndex(listOf("episodes", "speed", "decoder", "engine", "skip"), "audio", 3))
    }

    @Test fun `initial focus follows selected resource while empty lists have no position`() {
        assertEquals(2, restorePlayerOptionIndex(listOf("auto", "a", "b"), null, 0, 2))
        assertEquals(-1, restorePlayerOptionIndex(emptyList(), "gone", 10))
        assertEquals(0, restorePlayerOptionIndex(listOf("a"), "gone", -2))
    }

    @Test fun `old layout request cannot run after close and reopen`() {
        val requests = PlayerPanelFocusRequests()
        requests.update(true)
        val old = requests.issue()
        assertTrue(requests.allows(old))
        requests.update(false)
        assertFalse(requests.allows(old))
        requests.update(true)
        val fresh = requests.issue()
        assertFalse(requests.allows(old))
        assertTrue(requests.allows(fresh))
    }

    @Test fun `new activation replaces older request even for the same group`() {
        val requests = PlayerPanelFocusRequests()
        requests.update(true)
        val options = requests.issue()
        val pages = requests.issue()
        assertFalse(requests.allows(options))
        assertTrue(requests.allows(pages))
    }

    @Test fun `data rebind invalidates a captured layout position`() {
        val requests = PlayerPanelFocusRequests()
        requests.update(true)
        val old = requests.issue()
        requests.update(true)
        assertFalse(requests.allows(old))
    }

    @Test fun `closed or detached panel cannot grant focus`() {
        val requests = PlayerPanelFocusRequests()
        val closed = requests.issue()
        assertFalse(requests.allows(closed))
        requests.update(true)
        val attached = requests.issue()
        requests.update(false)
        assertFalse(requests.allows(attached))
    }

    @Test fun `closing key release and repeats remain owned after panel closes`() {
        val keys = PlayerPanelKeyOwnership()
        keys.onDown(19, 100)
        assertTrue(keys.consume(19, true, 100))
        assertTrue(keys.consume(19, false, 100))
        assertFalse(keys.consume(19, false, 100))
    }

    @Test fun `old release cannot clear ownership of a newer key press`() {
        val keys = PlayerPanelKeyOwnership()
        keys.onDown(23, 100)
        keys.onDown(23, 200)
        assertFalse(keys.consume(23, false, 100))
        assertTrue(keys.owns(23, 200))
        assertTrue(keys.consume(23, false, 200))
    }

    @Test fun `unmatched confirm release cannot select a different resource`() {
        val keys = PlayerPanelKeyOwnership()
        keys.onDown(23, 100)
        assertEquals(KeyRouting.ChoiceAction.CONSUME, KeyRouting.choicePopupKey(
            isKeyDown = false, confirm = true, confirmOwned = keys.owns(23, 200),
        ))
        assertEquals(KeyRouting.ChoiceAction.APPLY, KeyRouting.choicePopupKey(
            isKeyDown = false, confirm = true, confirmOwned = keys.owns(23, 100),
        ))
    }

    @Test fun `confirm belongs to the original stable resource not a replacement at the same index`() {
        val keys = PlayerPanelKeyOwnership()
        val original = PlayerPanelConfirmTarget("audio", "options", "track:b")
        keys.onDown(23, 100, original)
        assertNull(keys.releaseConfirm(23, 100, original.copy(resource = "track:c")))
    }

    @Test fun `stable resource may move without changing confirm ownership`() {
        val keys = PlayerPanelKeyOwnership()
        val original = PlayerPanelConfirmTarget("audio", "options", "track:b")
        keys.onDown(23, 100, original)
        assertEquals(original, keys.releaseConfirm(23, 100, original.copy()))
        assertNull(keys.releaseConfirm(23, 100, original))
    }

    @Test fun `closing cancels confirm but retains the matching release owner`() {
        val keys = PlayerPanelKeyOwnership()
        val original = PlayerPanelConfirmTarget("episodes", "options", "episode:12")
        keys.onDown(23, 100, original)
        keys.cancelConfirm()
        assertTrue(keys.owns(23, 100))
        assertNull(keys.releaseConfirm(23, 100, original))
    }
}
