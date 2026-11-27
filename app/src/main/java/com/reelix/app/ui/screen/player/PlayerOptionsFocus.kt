package com.reelix.app.ui.screen.player

internal fun restorePlayerOptionIndex(
    keys: List<String>,
    previousKey: String?,
    previousIndex: Int,
    selectedIndex: Int = 0,
    previousKeys: List<String> = emptyList(),
): Int {
    if (keys.isEmpty()) return -1
    keys.indexOf(previousKey).takeIf { it >= 0 }?.let { return it }
    if (previousKey != null && previousKeys.isNotEmpty()) {
        val positions = keys.withIndex().associate { it.value to it.index }
        val original = previousKeys.indexOf(previousKey)
        if (original < 0) return -1
        for (distance in 1 until previousKeys.size) {
            for (index in listOf(original + distance, original - distance)) {
                previousKeys.getOrNull(index)?.let { positions[it] }?.let { return it }
            }
        }
        return -1
    }
    return (if (previousKey == null) selectedIndex else previousIndex).coerceIn(keys.indices)
}

internal class PlayerPanelFocusRequests {
    private var open = false
    private var revision = 0L

    fun update(open: Boolean) {
        revision++
        this.open = open
    }

    fun issue(): Long = ++revision

    fun allows(ticket: Long): Boolean = open && ticket == revision
}

internal class PlayerPanelKeyOwnership {
    private val downs = mutableMapOf<Int, Long>()
    private val confirmTargets = mutableMapOf<Int, PlayerPanelConfirmTarget>()

    internal fun onDown(code: Int, downTime: Long, target: PlayerPanelConfirmTarget? = null) {
        downs[code] = downTime
        if (target == null) confirmTargets.remove(code) else confirmTargets[code] = target
    }

    fun owns(code: Int, downTime: Long): Boolean = downs[code] == downTime

    fun consume(code: Int, down: Boolean, downTime: Long): Boolean {
        if (!owns(code, downTime)) return false
        if (!down) {
            downs.remove(code)
            confirmTargets.remove(code)
        }
        return true
    }

    internal fun releaseConfirm(code: Int, downTime: Long, current: PlayerPanelConfirmTarget?,
        cancelled: Boolean = false): PlayerPanelConfirmTarget? {
        val result = current.takeIf { !cancelled && owns(code, downTime) && confirmTargets[code] == it }
        consume(code, false, downTime)
        return result
    }

    fun cancelConfirm() {
        // Keep the key pair owned while preventing its release from clicking a new target.
        confirmTargets.clear()
    }
}

internal data class PlayerPanelConfirmTarget(val group: String, val area: String, val resource: String)

internal fun playerRouteOptionKeys(routes: List<com.reelix.app.data.model.PlayRoute>): List<String> {
    fun parts(values: List<String>) = values.joinToString("") { "${it.length}:$it" }
    val bases = routes.map { parts(listOf(it.name) + it.episodes.map { ep -> ep.flag }.filter { it.isNotBlank() }.distinct().sorted()) }
    val counts = bases.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return routes.mapIndexed { index, route ->
        val base = bases[index]
        val identity = if (counts[base] == 1) base else base + parts(route.episodes.map {
            parts(listOf(it.flag, it.playId))
        }.distinct().sorted())
        val ordinal = seen[identity] ?: 0
        seen[identity] = ordinal + 1
        "route:$identity:$ordinal"
    }
}
