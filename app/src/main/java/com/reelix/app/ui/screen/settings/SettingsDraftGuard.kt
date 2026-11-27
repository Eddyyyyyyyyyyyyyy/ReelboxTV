package com.reelix.app.ui.screen.settings

import androidx.compose.runtime.*

/** Only explicit navigation away asks about unsaved connection edits. Focus movement is harmless. */
class SettingsDraftGuard {
    var dirty by mutableStateOf(false)
    var discard: () -> Unit = {}
    var pending by mutableStateOf<(() -> Unit)?>(null)
    fun leave(action: () -> Unit) { if (dirty) pending = action else action() }
    fun confirmDiscard() {
        val action = pending
        pending = null
        discard()
        dirty = false
        action?.invoke()
    }
}
