package com.reelix.app.ui.screen.library

/** A return target follows identity, never a new item that happens to occupy the old index. */
internal data class LibraryDialogPosterAnchor(val keys: List<String>, val key: String) {
    fun position(currentKeys: List<String>): Int? {
        val positions = currentKeys.withIndex().associate { it.value to it.index }
        positions[key]?.let { return it }
        val oldIndex = keys.indexOf(key)
        if (oldIndex < 0) return null
        for (distance in 1 until keys.size) {
            for (index in listOf(oldIndex + distance, oldIndex - distance)) {
                keys.getOrNull(index)?.let { positions[it] }?.let { return it }
            }
        }
        return null
    }
}

/** Callback tickets belong to one visible layer and are revoked on navigation or dismissal. */
internal class LibraryDialogFocusState {
    var generation = 0L
        private set
    private var childOpen = false
    private var closed = false

    fun invalidate(): Long = ++generation
    fun openChild() { childOpen = true; invalidate() }
    fun closeChild() { childOpen = false; invalidate() }
    fun close() { closed = true; invalidate() }
    fun accepts(ticket: Long): Boolean = !closed && !childOpen && ticket == generation
}
