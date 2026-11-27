package com.reelix.app.ui.screen.settings

enum class NativeSettingsSection { LIBRARY, ONLINE, ARTWORK, PLAYBACK, APPEARANCE, DATA, ABOUT, AUTH }

class NativeSettingsDraft {
    private var baseline = emptyMap<String, String>()
    private var values = emptyMap<String, String>()
    val dirty: Boolean get() = baseline != values
    fun value(key: String): String = values[key].orEmpty()
    fun sync(saved: Map<String, String>) {
        if (!dirty) discard(saved)
    }
    fun edit(key: String, value: String) { values = values + (key to value) }
    fun discard(saved: Map<String, String>) { baseline = saved.toMap(); values = saved.toMap() }
}

class NativeSettingsSession {
    var section = NativeSettingsSection.LIBRARY
    /** Which source panel the library page shows: alist / webdav / emby. */
    var librarySourceTab: String = "alist"
    var inContent = false
    var focusKey = "rail:LIBRARY"
    val contentFocus = mutableMapOf<NativeSettingsSection, String>()
    val scrollPositions = mutableMapOf<NativeSettingsSection, Int>()
    private val drafts = mutableMapOf<NativeSettingsSection, NativeSettingsDraft>()
    val dirtySections: Set<NativeSettingsSection> get() = drafts.filterValues { it.dirty }.keys.toSet()
    fun draft(section: NativeSettingsSection): NativeSettingsDraft = drafts.getOrPut(section) { NativeSettingsDraft() }
    fun reset() {
        section = NativeSettingsSection.LIBRARY
        inContent = false
        focusKey = "rail:LIBRARY"
        contentFocus.clear()
        scrollPositions.clear()
        drafts.clear()
    }
}
