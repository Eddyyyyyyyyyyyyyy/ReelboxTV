package com.reelix.app.ui.screen.settings

internal class SettingsFocusHandoff {
    var generation = 0L
        private set
    private var acknowledged = -1L
    private var closed = false

    fun request(): Long = ++generation
    fun pending(ticket: Long): Boolean = !closed && ticket == generation && acknowledged != ticket
    fun acknowledge(ticket: Long, controlFocused: Boolean) {
        if (!closed && ticket == generation && controlFocused) acknowledged = ticket
    }
    fun close() { closed = true; generation++ }
}
