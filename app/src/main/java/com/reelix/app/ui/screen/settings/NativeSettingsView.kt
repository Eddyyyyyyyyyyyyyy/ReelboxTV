package com.reelix.app.ui.screen.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.reelix.app.R
import com.reelix.app.BuildConfig
import com.reelix.app.data.image.*
import com.reelix.app.data.pan.PanCookieStore
import com.reelix.app.data.repo.LibraryRepository
import com.reelix.app.data.settings.AppearancePreferences
import com.reelix.app.data.settings.SavedSubscription
import com.reelix.app.data.settings.SettingsRepository
import com.reelix.app.data.source.SourceManager
import com.reelix.app.data.tmdb.TmdbClient
import com.reelix.app.data.image.TmdbKeyPolicy
import com.reelix.app.data.tvmaze.TvmazeClient
import com.reelix.app.data.streamx.StreamxClient
import com.reelix.app.data.douban.DoubanClient
import com.reelix.app.player.DecoderMode
import com.reelix.app.ui.screen.player.PlayerPanelSize
import com.reelix.app.ui.component.*
import com.reelix.app.ui.nav.TopDest
import com.reelix.app.ui.screen.player.labelRes
import kotlinx.coroutines.*

data class NativeSettingsSnapshot(
    val settings: SettingsRepository.AppSettings,
    val source: SourceManager.State,
    val scan: LibraryRepository.ScanStatus,
    val scanProgress: String?,
    val artwork: ArtworkBatchStatus,
    val historyCount: Int,
    val providers: Set<String>,
    val phoneUrl: String?,
    val version: String,
)

data class NativeSettingsActions(
    val onUpdate: ((SettingsRepository.AppSettings) -> SettingsRepository.AppSettings) -> Unit,
    val onUpdateArtworkOrder: ((List<String>) -> Unit)? = null,
    val onClose: () -> Unit,
    val onTestConnection: suspend (SettingsRepository.AppSettings) -> ConnectionTestResult,
    val onReloadSource: suspend () -> Unit,
    val onOpenAuth: (PanCookieStore.Provider) -> Unit,
    /** 清除某个网盘保存的凭据；返回清除后的已配置集合。 */
    val onClearAuth: ((PanCookieStore.Provider) -> Unit)? = null,
    val onRefresh: () -> Unit,
    val onManageDirectories: () -> Unit,
    val onRetryArtwork: () -> Unit,
    val onClearHistory: () -> Unit,
    val onClearImages: (() -> Unit) -> Unit,
)

class NativeSettingsView(
    context: Context,
    private val session: NativeSettingsSession,
) : LinearLayout(context), PageView, PopupFocusReturnHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapshot: NativeSettingsSnapshot? = null
    private lateinit var actions: NativeSettingsActions
    private val state get() = requireNotNull(snapshot)
    private val rail = LinearLayout(context)
    private val pane = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val page = LinearLayout(context)
    private val paneTitle = context.textView("", 22f, ViewTheme.textPrimary)
    private val paneHelp = context.textView("", 13f, ViewTheme.textSecondary)
    private val controls = linkedMapOf<String, NativeSettingRow>()
    private val focusTargets = linkedMapOf<String, View>()
    private val bindings = linkedMapOf<String, () -> Unit>()
    /**
     * Focusable rows in visual order; each inner list is one horizontal row
     * (a full-width setting row, or the chips of a segmented/choice row).
     */
    private val contentRows = mutableListOf<MutableList<View>>()
    /** Width of each row in [contentRows], refreshed with the focus chain. */
    private val contentRowSizes = mutableListOf<Int>()
    /** Focusable content view → its (row, column) in [contentRows]. */
    private val contentCells = mutableMapOf<View, Pair<Int, Int>>()
    private var builtSection: NativeSettingsSection? = null
    /** Pending segment switch; applied once focus rests on it. */
    private var pendingSegment: Pair<String, String>? = null
    private val segmentSettleMs = 400L
    /** Pending rail preview; applied only after focus rests on that category. */
    private var pendingRailSection: NativeSettingsSection? = null
    private val railPreview = Runnable {
        val target = pendingRailSection ?: return@Runnable
        if (session.focusKey != "rail:${target.name}") return@Runnable
        preview(target)
    }
    private var topology: List<String> = emptyList()
    private val busy = mutableSetOf<String>()
    private val results = mutableMapOf<String, String>()
    private var cacheBytes = 0L
    private var cacheLoaded = false
    private var dialog: Dialog? = null
    private var focusEpoch = 0L
    private var pendingFocus: Runnable? = null
    private var backKey: Int? = null
    private var navigationKey: Int? = null
    private var disposed = false

    init {
        orientation = VERTICAL
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFocusable = false
        setBackgroundColor(ViewTheme.backdrop)
        setPadding(context.vDp(28), context.vDp(24), context.vDp(28), context.vDp(24))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(context.textView(context.getString(R.string.settings_workspace_title), 26f, ViewTheme.textPrimary),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(46)))
        val columns = LinearLayout(context)
        addView(columns, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.vDp(16) })
        rail.orientation = VERTICAL
        columns.addView(rail, LayoutParams(context.vDp(208), LayoutParams.MATCH_PARENT).apply { rightMargin = context.vDp(20) })
        val railItems = mutableListOf<View>()
        NativeSettingsSection.entries
            .filter { it != NativeSettingsSection.AUTH && !(BuildConfig.PUBLIC_BUILD && it == NativeSettingsSection.ARTWORK) }
            .forEachIndexed { index, section ->
            val row = control("rail:${section.name}", context.getString(titleRes(section))) { enter(section) }
            row.minimumHeight = context.vDp(44)
            row.setOnKeyListener { _, code, event ->
                when {
                    code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            navigationKey = code
                            enter(section)
                        }
                        true
                    }
                    // First rail entry stops on UP instead of wrapping or escaping.
                    code == KeyEvent.KEYCODE_DPAD_UP -> index == 0
                    code == KeyEvent.KEYCODE_DPAD_LEFT -> false
                    else -> false
                }
            }
            rail.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(44)).apply { bottomMargin = context.vDp(5) })
            railItems += row
        }
        // Tool entry, not a settings category: directly below "关于" so DOWN reaches it.
        val phoneRow = control("phone", context.getString(R.string.settings_phone_entry)) { guarded { showPhone() } }
        rail.addView(phoneRow, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(44)))
        railItems += phoneRow
        // Rail owns its own UP/DOWN chain: the last entry (phone) stops instead of
        // wrapping or letting the system search jump into the content pane.
        railItems.forEachIndexed { index, item ->
            railItems.getOrNull(index - 1)?.let { item.nextFocusUpId = it.id }
            railItems.getOrNull(index + 1)?.let { item.nextFocusDownId = it.id }
        }
        pane.orientation = VERTICAL
        columns.addView(pane, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val paneHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        paneHeader.addView(paneTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        pane.addView(paneHeader)
        pane.addView(paneHelp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(6); bottomMargin = context.vDp(12)
        })
        scroll.isFillViewport = true
        scroll.isFocusable = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        page.orientation = VERTICAL
        page.setPadding(0, 0, context.vDp(4), context.vDp(16))
        scroll.addView(page, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pane.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun render(snapshot: NativeSettingsSnapshot, actions: NativeSettingsActions) {
        if (disposed) return
        // 未提供「海报与资料」入口时，会话若停在该页，回落到在线设置。
        if (BuildConfig.PUBLIC_BUILD && session.section == NativeSettingsSection.ARTWORK) {
            session.section = NativeSettingsSection.ONLINE
        }
        this.snapshot = snapshot
        this.actions = actions
        listOf(NativeSettingsSection.LIBRARY, NativeSettingsSection.ONLINE, NativeSettingsSection.ARTWORK)
            .forEach { session.draft(it).sync(savedValues(it)) }
        val nextTopology = when (session.section) {
            // Switching the tab or any enable flag must rebuild so fields appear/disappear.
            NativeSettingsSection.LIBRARY -> listOf(
                session.librarySourceTab,
                snapshot.settings.alistEnabled.toString(),
                snapshot.settings.webdavEnabled.toString(),
                snapshot.settings.embyEnabled.toString(),
            )
            NativeSettingsSection.ONLINE -> {
                val sites = (snapshot.source as? SourceManager.State.Ready)?.sites?.map { it.site.key }.orEmpty()
                // Include the active URL so switching subscriptions reorders and rebinds rows.
                sites + snapshot.settings.savedSubscriptions.map { "sub:${it.id}" } +
                    "active:${snapshot.settings.tvboxConfigUrl}"
            }
            NativeSettingsSection.APPEARANCE -> snapshot.settings.appearance.normalized().order
            // 渠道增删/改名/启停都会改变行数或文案，必须进 topology 才会重建。
            NativeSettingsSection.ARTWORK -> snapshot.settings.artworkChannels.map {
                "${it.kind}:${it.enabled}:${it.baseUrl}"
            } + snapshot.settings.artworkOrder
            NativeSettingsSection.DATA -> emptyList()
            else -> emptyList()
        }
        if (builtSection != session.section || topology != nextTopology) {
            val focused = controls.entries.firstOrNull { it.value.hasFocus() }?.key
                ?: focusTargets.entries.firstOrNull { it.value.hasFocus() }?.key
            rebuildPage()
            topology = nextTopology
            if (focused != null && !focused.startsWith("rail:") && focused != "phone")
                scheduleFocus(focused.takeIf { it in focusTargets } ?: firstContentKey())
        }
        refreshBindings()
    }

    private fun titleRes(section: NativeSettingsSection): Int = when (section) {
        NativeSettingsSection.LIBRARY -> R.string.settings_cat_library
        NativeSettingsSection.ONLINE -> R.string.settings_cat_online
        NativeSettingsSection.ARTWORK -> R.string.settings_cat_artwork
        NativeSettingsSection.PLAYBACK -> R.string.settings_cat_playback
        NativeSettingsSection.APPEARANCE -> R.string.settings_cat_appearance
        NativeSettingsSection.DATA -> R.string.settings_cat_data
        NativeSettingsSection.ABOUT -> R.string.settings_cat_about
        NativeSettingsSection.AUTH -> R.string.settings_online_auth
    }

    private fun savedValues(section: NativeSettingsSection): Map<String, String> = when (section) {
        NativeSettingsSection.LIBRARY -> state.settings.let {
            mapOf(
                "url" to it.alistUrl, "user" to it.alistUser, "pass" to it.alistPass,
                "root" to it.alistScanRoot, "webdavUrl" to it.webdavUrl,
                "webdavUser" to it.webdavUser, "webdavPass" to it.webdavPass,
                "embyUrl" to it.embyUrl, "embyApiKey" to it.embyApiKey,
                "embyUser" to it.embyUser, "embyPass" to it.embyPass,
            )
        }
        NativeSettingsSection.ONLINE -> emptyMap()
        NativeSettingsSection.ARTWORK -> mapOf("key" to state.settings.tmdbApiKey)
        else -> emptyMap()
    }

    /** Field editors commit on "完成"; BACK only cancels, so no separate group save is needed. */
    private fun commitDraft(section: NativeSettingsSection) {
        val draft = session.draft(section)
        when (section) {
            NativeSettingsSection.LIBRARY -> {
                val root = draft.value("root").trim().ifBlank { "/" }
                actions.onUpdate { current ->
                    current.copy(
                        alistUrl = draft.value("url").trim(),
                        alistUser = draft.value("user").trim(),
                        alistPass = draft.value("pass"),
                        alistScanRoot = root,
                        webdavUrl = draft.value("webdavUrl").trim(),
                        webdavUser = draft.value("webdavUser").trim(),
                        webdavPass = draft.value("webdavPass"),
                        embyUrl = draft.value("embyUrl").trim(),
                        embyApiKey = draft.value("embyApiKey").trim(),
                        embyUser = draft.value("embyUser").trim(),
                        embyPass = draft.value("embyPass"),
                    )
                }
                draft.discard(mapOf(
                    "url" to draft.value("url").trim(),
                    "user" to draft.value("user").trim(),
                    "pass" to draft.value("pass"),
                    "root" to root,
                    "webdavUrl" to draft.value("webdavUrl").trim(),
                    "webdavUser" to draft.value("webdavUser").trim(),
                    "webdavPass" to draft.value("webdavPass"),
                    "embyUrl" to draft.value("embyUrl").trim(),
                    "embyApiKey" to draft.value("embyApiKey").trim(),
                    "embyUser" to draft.value("embyUser").trim(),
                    "embyPass" to draft.value("embyPass"),
                ))
            }
            NativeSettingsSection.ARTWORK -> {
                val key = draft.value("key").trim()
                actions.onUpdate { it.copy(tmdbApiKey = key) }
                TmdbClient.clearCache()
                draft.discard(mapOf("key" to key))
            }
            else -> Unit
        }
        refreshBindings()
    }

    private fun control(key: String, label: String, action: () -> Unit): NativeSettingRow =
        NativeSettingRow(context).also { row ->
            controls[key] = row
            focusTargets[key] = row
            row.bind(label)
            row.setOnClickListener { action() }
            row.setOnFocusChangeListener { _, focused ->
                // 背景由 NativeSettingRow 里预建的 StateListDrawable 自动切换，
                // 这里只保留焦点业务（记住位置、切换右侧预览）
                if (focused) {
                    session.focusKey = key
                    if (key.startsWith("rail:")) {
                        session.inContent = false
                        // Wait until focus rests before rebuilding; holding UP/DOWN should not
                        // rebuild the right pane for every category it passes.
                        scheduleRailPreview(NativeSettingsSection.valueOf(key.substringAfter(':')))
                    }
                    else if (key == "phone") {
                        session.inContent = false
                        // Tool entry: never switches on dwell, only on OK.
                        removeCallbacks(railPreview)
                        pendingRailSection = null
                    }
                    else {
                        session.inContent = true
                        session.contentFocus[session.section] = key
                    }
                }
            }
            if (key == "phone") row.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        controls["rail:${NativeSettingsSection.ABOUT.name}"]?.requestFocus()
                    }
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // Bottom of the rail: stop instead of searching into the pane.
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        navigationKey = code
                        enter(session.section)
                    }
                    true
                } else false
            }
            else if (!key.startsWith("rail:")) row.setOnKeyListener { _, code, event ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) { navigationKey = code; returnToRail() }
                        true
                    }
                    // Single-row items bypass focusSearch at the pane edges so a
                    // held UP/DOWN can never wander onto the rail.
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                        handleVerticalKey(row, code, event)
                    else -> false
                }
            }
        }

    private fun row(key: String, label: Int, value: () -> String = { "" }, help: () -> String = { "" },
        action: () -> Unit): NativeSettingRow = row(key, context.getString(label), value, help, action)

    private fun row(key: String, label: String, value: () -> String = { "" }, help: () -> String = { "" },
        action: () -> Unit): NativeSettingRow {
        val row = control(key, label, action)
        page.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.vDp(7) })
        contentRows += mutableListOf<View>(row)
        bindings[key] = { row.bind(label, value(), help()) }
        return row
    }

    private fun info(key: String, value: () -> String) {
        val text = context.textView("", 13f, ViewTheme.textSecondary).apply {
            setPadding(context.vDp(12), context.vDp(6), context.vDp(12), context.vDp(10))
        }
        page.addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bindings[key] = { text.text = value() }
    }

    private fun heading(label: Int) {
        page.addView(context.textView(context.getString(label), 18f, ViewTheme.textPrimary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.vDp(12); bottomMargin = context.vDp(10)
            })
    }

    private fun field(key: String, label: Int, secret: Boolean = false) {
        val section = session.section
        row("field:$key", label, value = {
            val text = session.draft(section).value(key)
            if (secret && text.isNotEmpty()) "••••••••" else text
        }) { showEditor(key, context.getString(label), secret, section) }
    }

    private fun toggle(key: String, label: Int, checked: () -> Boolean,
        help: Int? = null, update: (SettingsRepository.AppSettings) -> SettingsRepository.AppSettings) {
        row(key, label, { context.getString(if (checked()) R.string.settings_native_on else R.string.settings_native_off) },
            { help?.let(context::getString).orEmpty() }) { actions.onUpdate(update) }
    }

    private fun <Value> choice(key: String, label: Int, options: List<Value>, selected: () -> Value,
        text: (Value) -> String, update: (Value) -> Unit) {
        row(key, label, { text(selected()) }) {
            val anchor = controls.getValue(key)
            showAnchoredChoicePopup(anchor, options.map(text), options.indexOf(selected()).coerceAtLeast(0),
                restoreFocus = { scheduleFocus(key) }) { position -> options.getOrNull(position)?.let(update) }
        }
    }

    private fun choiceGrid(
        keyPrefix: String,
        label: Int,
        options: List<String>,
        selected: () -> String,
        text: (String) -> String,
        update: (String) -> Unit,
    ) {
        page.addView(
            context.textView(context.getString(label), 14f, ViewTheme.textSecondary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.vDp(12); bottomMargin = context.vDp(8)
            },
        )
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (rowIndex == 0) 0 else context.vDp(8), 0, 0)
            }
            val rowItems = mutableListOf<View>()
            pair.forEachIndexed { index, option ->
                val key = "$keyPrefix:$option"
                val chip = context.textView("", 14f, ViewTheme.textPrimary, Gravity.CENTER).apply {
                    id = View.generateViewId()
                    this.text = text(option)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setPadding(context.vDp(14), 0, context.vDp(14), 0)
                    setOnClickListener {
                        update(option)
                        session.focusKey = key
                        refreshBindings()
                    }
                    setOnFocusChangeListener { _, focused ->
                        if (focused) {
                            session.focusKey = key
                            session.inContent = true
                            session.contentFocus[session.section] = key
                        }
                        styleChoiceChip(this, selected() == option, focused)
                    }
                    setOnKeyListener { _, code, event ->
                        when (code) {
                            // Only the left-most chip is the pane boundary.
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (event.action == KeyEvent.ACTION_DOWN && index == 0) {
                                    navigationKey = code
                                    returnToRail()
                                }
                                index == 0
                            }
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                                handleVerticalKey(this, code, event)
                            else -> false
                        }
                    }
                }
                focusTargets[key] = chip
                rowItems += chip
                row.addView(chip, LayoutParams(0, context.vDp(44), 1f).apply {
                    if (index > 0) leftMargin = context.vDp(8)
                })
            }
            contentRows += rowItems
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        page.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.vDp(8)
        })
        bindings["$keyPrefix:style"] = {
            options.forEach { option ->
                focusTargets["$keyPrefix:$option"]?.let {
                    styleChoiceChip(it, selected() == option, it.hasFocus())
                    (it as? TextView)?.text = text(option)
                    it.contentDescription = text(option)
                }
            }
        }
    }

    /**
     * Single-row segmented control.
     *
     * The rebuild is posted to the next frame: rebuilding synchronously inside the
     * clicked chip's own listener removes the focused view and leaves key handling dead.
     */
    private fun segmentedRow(
        keyPrefix: String,
        options: List<String>,
        selected: () -> String,
        text: (String) -> String,
        update: (String) -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Switch only after focus rests, so holding UP/DOWN does not rebuild the page at every stop.
        val settle = Runnable {
            val pending = pendingSegment
            if (pending != null && session.focusKey == pending.first) applySegment(pending.second, update)
        }
        val rowItems = mutableListOf<View>()
        options.forEachIndexed { index, option ->
            val key = "$keyPrefix:$option"
            val chip = context.textView("", 14f, ViewTheme.textPrimary, Gravity.CENTER).apply {
                id = View.generateViewId()
                this.text = text(option)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(context.vDp(14), 0, context.vDp(14), 0)
                setOnClickListener {
                    if (selected() == option) return@setOnClickListener
                    session.focusKey = key
                    session.inContent = true
                    session.contentFocus[session.section] = key
                    applySegment(option, update)
                }
                setOnFocusChangeListener { _, focused ->
                    if (focused) {
                        session.focusKey = key
                        session.inContent = true
                        session.contentFocus[session.section] = key
                        if (selected() != option) {
                            pendingSegment = key to option
                            removeCallbacks(settle)
                            postDelayed(settle, segmentSettleMs)
                        }
                    } else {
                        removeCallbacks(settle)
                    }
                    styleChoiceChip(this, selected() == option, focused)
                }
                setOnKeyListener { _, code, event ->
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT ->
                            if (event.action == KeyEvent.ACTION_DOWN && index == 0) {
                                navigationKey = code
                                returnToRail()
                                true
                            } else {
                                // Non-first segments let the system move focus left.
                                false
                            }
                        // Only the last segment is a boundary; earlier ones move right normally.
                        KeyEvent.KEYCODE_DPAD_RIGHT -> index == options.lastIndex
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                            handleVerticalKey(this, code, event)
                        else -> false
                    }
                }
            }
            focusTargets[key] = chip
            rowItems += chip
            row.addView(chip, LayoutParams(0, context.vDp(44), 1f).apply {
                if (index > 0) leftMargin = context.vDp(8)
            })
        }
        contentRows += rowItems
        page.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.vDp(8)
        })
        bindings["$keyPrefix:style"] = {
            options.forEach { option ->
                focusTargets["$keyPrefix:$option"]?.let {
                    styleChoiceChip(it, selected() == option, it.hasFocus())
                    (it as? TextView)?.text = text(option)
                }
            }
        }
    }

    /** Applies a segment change on the next frame so the pressed chip is not removed mid-event. */
    private fun applySegment(option: String, update: (String) -> Unit) {
        pendingSegment = null
        post { update(option) }
    }

    private companion object {
        const val RAIL_PREVIEW_SETTLE_MS = 400L
    }

    private fun styleChoiceChip(chip: View, selected: Boolean, focused: Boolean) {
        chip.background = focusStateBackground(
            if (selected) ViewTheme.accent else ViewTheme.surfaceElevated,
            if (selected) ViewTheme.accent else ViewTheme.surfaceHigh,
            context.vDp(7), context.vDp(2), ViewTheme.focusRing,
        )
        (chip as? TextView)?.setTextColor(if (selected) Color.BLACK else ViewTheme.textPrimary)
    }

    private fun refreshBindings() {
        if (snapshot == null || disposed) return
        bindings.values.toList().forEach { it() }
        controls.filterKeys { it.startsWith("rail:") }.forEach { (key, row) ->
            row.isSelected = key == "rail:${session.section.name}"
        }
    }

    private fun rebuildPage() {
        if (builtSection != null) session.scrollPositions[builtSection!!] = scroll.scrollY
        controls.keys.filter { it != "phone" && !it.startsWith("rail:") }.toList()
            .forEach { controls.remove(it) }
        focusTargets.keys.filter { it != "phone" && !it.startsWith("rail:") }.toList()
            .forEach { focusTargets.remove(it) }
        contentRows.clear()
        contentRowSizes.clear()
        contentCells.clear()
        bindings.clear()
        page.removeAllViews()
        builtSection = session.section
        paneTitle.setText(titleRes(session.section))
        paneHelp.setText(when (session.section) {
            NativeSettingsSection.LIBRARY -> R.string.settings_library_help
            NativeSettingsSection.ONLINE, NativeSettingsSection.AUTH -> R.string.settings_online_help
            NativeSettingsSection.ARTWORK -> R.string.settings_artwork_help
            NativeSettingsSection.PLAYBACK -> R.string.settings_playback_help
            NativeSettingsSection.APPEARANCE -> R.string.settings_appearance_help
            NativeSettingsSection.DATA -> R.string.settings_data_help
            NativeSettingsSection.ABOUT -> R.string.settings_about_help
        })
        when (session.section) {
            NativeSettingsSection.LIBRARY -> libraryPage()
            NativeSettingsSection.ONLINE -> onlinePage()
            NativeSettingsSection.ARTWORK -> artworkPage()
            NativeSettingsSection.PLAYBACK -> playbackPage()
            NativeSettingsSection.APPEARANCE -> appearancePage()
            NativeSettingsSection.DATA -> dataPage()
            NativeSettingsSection.ABOUT -> {
                info("version") { context.getString(R.string.settings_app_version) + "  " + state.version }
                info("package") { context.getString(R.string.settings_application_id) + "  " + context.packageName }
            }
            NativeSettingsSection.AUTH -> authPage()
        }
        applyFocusChain()
        val restoredY = session.scrollPositions[session.section] ?: 0
        scroll.post { if (isAttachedToWindow && !disposed) scroll.scrollTo(0, restoredY) }
        refreshBindings()
    }

    /**
     * Explicit UP/DOWN chain for the content pane.
     *
     * Without it Android's default focus search leaves the pane at its top/bottom
     * row and lands on the rail, which then previews another category mid-press.
     * First row stops on UP, last row stops on DOWN, LEFT is the only way back.
     */
    private fun applyFocusChain() {
        if (contentRows.isEmpty()) return
        val railId = controls["rail:${session.section.name}"]?.id
        contentRowSizes.clear()
        contentRowSizes += contentRows.map { it.size }
        val rowSizes = contentRowSizes
        contentRows.forEachIndexed { rowIndex, items ->
            if (items.isEmpty()) return@forEachIndexed
            items.forEachIndexed { column, view ->
                contentCells[view] = rowIndex to column
                val up = SettingsFocusChain.verticalNeighbor(rowSizes, rowIndex, column, -1)
                val down = SettingsFocusChain.verticalNeighbor(rowSizes, rowIndex, column, 1)
                // Edges stay unset: [handleVerticalKey] swallows UP/DOWN there, so
                // the system's focus search never gets a chance to reach the rail.
                view.nextFocusUpId = up?.let { contentRows[it.first][it.second].id } ?: View.NO_ID
                view.nextFocusDownId = down?.let { contentRows[it.first][it.second].id } ?: View.NO_ID
                // Horizontal groups handle LEFT themselves; single rows fall back to the rail.
                if (items.size == 1 && railId != null) view.nextFocusLeftId = railId
            }
        }
    }

    /**
     * Moves focus one row up/down inside the content pane and swallows the key.
     *
     * Returns false when [view] is not part of the current page (e.g. a view from
     * a page being replaced), so the caller can fall back to normal handling.
     * At the first/last row focus stays put and the pane scrolls instead, so
     * trailing non-focusable text (subscription status, site lists) stays reachable.
     */
    private fun handleVerticalKey(view: View, code: Int, event: KeyEvent): Boolean {
        val cell = contentCells[view] ?: return false
        val down = code == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action == KeyEvent.ACTION_DOWN) {
            val target = SettingsFocusChain.verticalNeighbor(
                contentRowSizes, cell.first, cell.second,
                if (down) 1 else -1,
            )
            if (target != null) {
                contentRows[target.first][target.second].requestFocus()
            } else {
                val step = context.vDp(160)
                val content = scroll.getChildAt(0)?.height ?: 0
                val max = (content - scroll.height).coerceAtLeast(0)
                val y = (scroll.scrollY + if (down) step else -step).coerceIn(0, max)
                scroll.smoothScrollTo(0, y)
            }
        }
        return true
    }

    private fun moveFocusInContent(up: Boolean): Boolean {
        val focused = findFocus() ?: return false
        if (!isInside(focused, page)) return false
        val current = focusBoxOf(focused) ?: return false
        val targets = ArrayList<View>()
        collectFocusTargets(page, targets)
        if (targets.size > 1) {
            val boxes = ArrayList<FocusBox>(targets.size)
            val views = ArrayList<View>(targets.size)
            targets.forEach { view ->
                if (view === focused) return@forEach
                val box = focusBoxOf(view) ?: return@forEach
                boxes += box
                views += view
            }
            val index = if (boxes.isEmpty()) null else FocusRowGeometry.pickVertical(current, boxes, up)
            if (index != null) {
                val target = views[index]
                ensureRowVisible(focusBoxOf(target) ?: return false)
                if (target.requestFocus()) return true
            }
        }
        // 内容区首行/末行：焦点留在原地，只滚动页面（与旧逻辑一致）。
        val step = context.vDp(160)
        val contentHeight = scroll.getChildAt(0)?.height ?: 0
        val max = (contentHeight - scroll.height).coerceAtLeast(0)
        val y = (scroll.scrollY + if (up) -step else step).coerceIn(0, max)
        scroll.smoothScrollTo(0, y)
        return true
    }

    private fun focusBoxOf(view: View): FocusBox? {
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return FocusBox(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun collectFocusTargets(view: View, out: MutableList<View>): Boolean {
        if (!view.isShown) return false
        if (view is ViewGroup) {
            var any = false
            for (index in 0 until view.childCount) {
                if (collectFocusTargets(view.getChildAt(index), out)) any = true
            }
            if (!any && view.isFocusable && view.isEnabled) {
                out += view
                return true
            }
            return any
        }
        if (view.isFocusable && view.isEnabled) {
            out += view
            return true
        }
        return false
    }

    private fun isInside(view: View, parent: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === parent) return true
            node = node.parent as? View
        }
        return false
    }

    /** 目标行不在可视区时即时滚进来，避免平滑滚动的迟滞感。 */
    private fun ensureRowVisible(box: FocusBox) {
        if (scroll.height <= 0) return
        val location = IntArray(2)
        scroll.getLocationOnScreen(location)
        val top = location[1]
        val bottom = top + scroll.height
        val padding = context.vDp(10)
        val delta = when {
            box.top < top + padding -> box.top - top - padding
            box.bottom > bottom - padding -> box.bottom - bottom + padding
            else -> 0
        }
        if (delta != 0) scroll.scrollBy(0, delta)
    }

    private fun libraryPage() {
        // Only the selected source panel is rendered, so three sources don't triple the page height.
        page.addView(context.textView(context.getString(R.string.settings_library_connection),
            14f, ViewTheme.textSecondary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.vDp(12); bottomMargin = context.vDp(8)
            })
        segmentedRow("library-tab",
            listOf("alist", "webdav", "emby"),
            { session.librarySourceTab },
            { key -> context.getString(when (key) {
                "webdav" -> R.string.settings_library_webdav
                "emby" -> R.string.settings_library_emby
                else -> R.string.settings_library_alist
            }) }) { key ->
            if (session.librarySourceTab != key) {
                session.librarySourceTab = key
                snapshot?.let { render(it, actions) }
            }
        }
        when (session.librarySourceTab) {
            "webdav" -> {
                toggle("webdav-enabled", R.string.settings_library_enabled, { state.settings.webdavEnabled }) {
                    it.copy(webdavEnabled = !it.webdavEnabled)
                }
                if (state.settings.webdavEnabled) {
                    field("webdavUrl", R.string.settings_address)
                    field("webdavUser", R.string.settings_username)
                    field("webdavPass", R.string.settings_password, secret = true)
                    row("test", R.string.settings_test_connection, { status("alist") },
                        { results["alist"] ?: context.getString(R.string.settings_config_entered) }) {
                        val draft = session.draft(session.section)
                        val candidate = state.settings.copy(
                            librarySource = "WEBDAV",
                            webdavUrl = draft.value("webdavUrl").trim(),
                            webdavUser = draft.value("webdavUser").trim(),
                            webdavPass = draft.value("webdavPass"),
                            alistScanRoot = draft.value("root").trim().ifBlank { "/" },
                        )
                        runTask("alist") { actions.onTestConnection(candidate).message }
                    }
                } else {
                    info("webdav-off") { context.getString(R.string.settings_library_disabled_hint) }
                }
            }
            "emby" -> {
                toggle("emby-enabled", R.string.settings_library_enabled, { state.settings.embyEnabled }) {
                    it.copy(embyEnabled = !it.embyEnabled)
                }
                if (state.settings.embyEnabled) {
                    field("embyUrl", R.string.settings_address)
                    field("embyApiKey", R.string.settings_native_api_key, secret = true)
                    field("embyUser", R.string.settings_username)
                    field("embyPass", R.string.settings_password, secret = true)
                    info("emby-help") { "Emby/Jellyfin 可用 API Key，或填写用户名密码后保存连接。" }
                    row("test", R.string.settings_test_connection, { status("alist") },
                        { results["alist"] ?: context.getString(R.string.settings_config_entered) }) {
                        val draft = session.draft(session.section)
                        val candidate = state.settings.copy(
                            librarySource = "EMBY",
                            embyUrl = draft.value("embyUrl").trim(),
                            embyApiKey = draft.value("embyApiKey").trim(),
                            embyUser = draft.value("embyUser").trim(),
                            embyPass = draft.value("embyPass"),
                            alistScanRoot = draft.value("root").trim().ifBlank { "/" },
                        )
                        runTask("alist") { actions.onTestConnection(candidate).message }
                    }
                } else {
                    info("emby-off") { context.getString(R.string.settings_library_disabled_hint) }
                }
            }
            else -> {
                toggle("alist-enabled", R.string.settings_library_enabled, { state.settings.alistEnabled }) {
                    it.copy(alistEnabled = !it.alistEnabled)
                }
                if (state.settings.alistEnabled) {
                    field("url", R.string.settings_address)
                    field("user", R.string.settings_username)
                    field("pass", R.string.settings_password, secret = true)
                    row("test", R.string.settings_test_connection, { status("alist") },
                        { results["alist"] ?: context.getString(R.string.settings_config_entered) }) {
                        val draft = session.draft(session.section)
                        val candidate = state.settings.copy(
                            librarySource = "ALIST",
                            alistUrl = draft.value("url").trim(),
                            alistUser = draft.value("user").trim(),
                            alistPass = draft.value("pass"),
                            alistScanRoot = draft.value("root").trim().ifBlank { "/" },
                        )
                        runTask("alist") { actions.onTestConnection(candidate).message }
                    }
                } else {
                    info("alist-off") { context.getString(R.string.settings_library_disabled_hint) }
                }
            }
        }
        field("root", R.string.settings_scan_root)
        info("root-help") { context.getString(R.string.settings_scan_root_shared_hint) }
        toggle("auto-scan", R.string.settings_auto_scan, { state.settings.autoScan }, R.string.settings_auto_scan_help) {
            it.copy(autoScan = !it.autoScan)
        }
    }

    private fun onlinePage() {
        row("auth", R.string.settings_online_auth, help = { context.getString(R.string.settings_online_auth_help) }) {
            enter(NativeSettingsSection.AUTH)
        }
        heading(R.string.settings_subscription_source)
        val orderedSources = state.settings.savedSubscriptions.sortedWith(
            compareByDescending<SavedSubscription> { it.url == state.settings.tvboxConfigUrl },
        )
        if (state.settings.savedSubscriptions.isEmpty()) {
            info("subscription-library-empty") { context.getString(R.string.settings_subscription_empty) }
        } else {
            orderedSources.forEach { saved ->
                val active = state.settings.tvboxConfigUrl == saved.url
                row(
                    "subscription:${saved.id}",
                    saved.name.ifBlank { saved.url },
                    {
                        (if (active) context.getString(R.string.settings_subscription_using) + " · " else "") + saved.url
                    },
                ) {
                    showSubscriptionActions(saved)
                }
            }
        }
        row("subscription-add", R.string.settings_subscription_add_source) {
            showSubscriptionForm(null, saveAsNew = true, origin = "subscription-add")
        }
        row("reload", R.string.settings_reload_subscription, { status("subscription") }) {
            if (session.draft(session.section).dirty) results["subscription"] = context.getString(R.string.settings_save_first)
            else reloadSource()
            refreshBindings()
        }
        heading(R.string.settings_subscription_state)
        info("source-state") {
            when (val source = state.source) {
                is SourceManager.State.Ready -> context.getString(R.string.settings_loaded_sites, source.sites.size)
                is SourceManager.State.Loading -> source.message
                is SourceManager.State.Error -> source.message
                else -> context.getString(R.string.settings_subscription_idle)
            } + results["subscription"]?.let { "\n$it" }.orEmpty()
        }
        (state.source as? SourceManager.State.Ready)?.sites?.forEach { site ->
            info("site:${site.site.key}") { site.displayName + "  ·  " + site.site.type }
        }
    }

    private fun reloadSource() {
        if (state.source !is SourceManager.State.Loading)
            runTask("subscription") { actions.onReloadSource(); "" }
    }

    private fun subscriptionName(url: String): String = runCatching {
        java.net.URI(url).host?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: url.take(48)

    private fun prefillSubscriptionEditor(saved: SavedSubscription, markDirty: Boolean = true) {
        showSubscriptionForm(saved, saveAsNew = false, origin = "subscription:${saved.id}")
    }

    private fun showSubscriptionActions(saved: SavedSubscription) {
        val (modal, content) = settingsDialog(context, context.getString(R.string.settings_subscription_actions))
        val isActive = state.settings.tvboxConfigUrl == saved.url
        val actions = listOfNotNull(
            if (isActive) null else context.getString(R.string.settings_subscription_use),
            context.getString(R.string.settings_subscription_edit),
            context.getString(R.string.settings_subscription_save_as_new),
            context.getString(R.string.settings_subscription_delete),
        )
        val rows = actions.map { label ->
            dialogButton(content, android.R.string.ok) { Unit }.apply { bind(label) }
        }
        fun perform(index: Int) {
            val label = actions[index]
            when (label) {
                context.getString(R.string.settings_subscription_use) -> {
                    modal.dismiss()
                    useSubscription(saved, "subscription:${saved.id}")
                }
                context.getString(R.string.settings_subscription_edit) -> {
                    modal.dismiss()
                    showSubscriptionForm(saved, saveAsNew = false, origin = "subscription:${saved.id}")
                }
                context.getString(R.string.settings_subscription_save_as_new) -> {
                    modal.dismiss()
                    showSubscriptionForm(saved, saveAsNew = true, origin = "subscription:${saved.id}")
                }
                context.getString(R.string.settings_subscription_delete) -> {
                    modal.dismiss()
                    deleteSubscription(saved)
                }
                else -> modal.dismiss()
            }
        }
        rows.forEachIndexed { index, row ->
            row.setOnClickListener { perform(index) }
        }
        showModal(modal, "subscription:${saved.id}") { rows.first().requestFocus() }
    }

    private fun useSubscription(saved: SavedSubscription, origin: String) {
        actions.onUpdate { it.copy(tvboxConfigUrl = saved.url) }
        reloadSource()
        // Force a rebuild: the active row moves to the top and its "使用中" label changes.
        snapshot?.let { rebuildPage() }
        refreshBindings()
        scheduleFocus(origin)
    }

    private fun showSubscriptionForm(saved: SavedSubscription?, saveAsNew: Boolean, origin: String) {
        val (modal, content) = settingsDialog(
            context,
            context.getString(if (saveAsNew) R.string.settings_subscription_add_source else R.string.settings_subscription_edit_source),
        )
        val nameField = NativeSettingsEditor(context) { }.apply {
            hint = context.getString(R.string.settings_subscription_name)
            setText(saved?.name.orEmpty())
            setSelection(text.length)
        }
        val urlField = NativeSettingsEditor(context) { }.apply {
            hint = context.getString(R.string.settings_subscription_address)
            setText(if (saveAsNew) saved?.url.orEmpty() else saved?.url.orEmpty())
            setSelection(text.length)
        }
        content.addView(nameField, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(52)).apply {
            bottomMargin = context.vDp(8)
        })
        content.addView(urlField, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(52)).apply {
            bottomMargin = context.vDp(8)
        })
        val statusText = context.textView("", 12f, ViewTheme.textSecondary)
        content.addView(statusText)
        var finished = false
        lateinit var save: NativeSettingRow
        fun close() {
            if (finished) return
            finished = true
            nameField.hideKeyboard()
            urlField.hideKeyboard()
            modal.dismiss()
        }
        nameField.onBack = { close() }
        urlField.onBack = { close() }
        fun submit() {
            val url = urlField.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                statusText.text = context.getString(R.string.settings_subscription_url_required)
                return
            }
            val name = nameField.text?.toString()?.trim()?.ifBlank { subscriptionName(url) }
            saveSubscription(saved, name.orEmpty(), url, saveAsNew, origin)
            close()
        }
        val saveLabel = if (saveAsNew) R.string.settings_subscription_add else R.string.settings_save_connect
        save = dialogButton(content, saveLabel) { submit() }
        nameField.nextFocusDownId = urlField.id
        urlField.nextFocusDownId = save.id
        var imeHiddenByBack = false
        modal.setOnKeyListener { _, code, event ->
            if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                val current = modal.currentFocus as? NativeSettingsEditor
                if (imeHiddenByBack) {
                    close()
                } else if (current?.isImeVisible() == true) {
                    imeHiddenByBack = true
                    current.hideKeyboard()
                } else {
                    close()
                }
                true
            } else false
        }
        modal.setOnCancelListener { close() }
        modal.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        showModal(modal, origin) { save.requestFocus() }
    }

    private fun saveSubscription(
        saved: SavedSubscription?,
        name: String,
        url: String,
        saveAsNew: Boolean,
        origin: String,
    ) {
        val current = state.settings.savedSubscriptions
        val editing = if (saveAsNew) null else saved?.let { source -> current.firstOrNull { it.id == source.id } }
        val sameUrl = current.firstOrNull { it.url == url }
        val displayName = name.ifBlank { subscriptionName(url) }
        val item = when {
            editing != null && editing.url == url -> editing.copy(name = displayName)
            editing != null -> SavedSubscription(
                id = SettingsRepository.subscriptionId(url), name = displayName, url = url,
            )
            sameUrl != null && !saveAsNew -> sameUrl.copy(name = displayName)
            else -> SavedSubscription(
                id = SettingsRepository.subscriptionId(url), name = displayName, url = url,
            )
        }
        val next = when {
            editing != null && editing.url == url ->
                current.map { if (it.id == editing.id) item else it }
            sameUrl != null && !saveAsNew && editing == null ->
                current.map { if (it.id == sameUrl.id) item else it }
            else -> current.filterNot { it.url == url } + item
        }
        actions.onUpdate { it.copy(savedSubscriptions = next, tvboxConfigUrl = url) }
        if (state.settings.tvboxConfigUrl == url) reloadSource()
        refreshBindings()
        scheduleFocus("subscription:${item.id}".takeIf { origin == "subscription-add" || origin.startsWith("subscription:") }
            ?: "subscription-add")
    }

    private fun deleteSubscription(saved: SavedSubscription) {
        confirm(
            R.string.settings_subscription_delete,
            context.getString(R.string.settings_subscription_delete_confirm),
            "subscription:${saved.id}",
        ) {
            val current = state.settings.savedSubscriptions
            val remaining = current.filterNot { it.id == saved.id }
            val wasActive = state.settings.tvboxConfigUrl == saved.url
            val nextUrl = if (wasActive) remaining.firstOrNull()?.url.orEmpty() else state.settings.tvboxConfigUrl
            actions.onUpdate {
                it.copy(savedSubscriptions = remaining, tvboxConfigUrl = nextUrl)
            }
            refreshBindings()
        }
    }

    private fun artworkPage() {
        // 资料语言管所有来源（TMDB 与外部渠道），所以放第一行。
        choice("tmdb-language", R.string.settings_native_language,
            listOf("zh-CN", "zh-TW", "en-US", "ja-JP"),
            { state.settings.tmdbLanguage }, { language ->
                context.getString(when (language) {
                    "zh-TW" -> R.string.settings_native_language_tw
                    "en-US" -> R.string.settings_native_language_en
                    "ja-JP" -> R.string.settings_native_language_ja
                    else -> R.string.settings_native_language_zh
                })
            }) { language -> actions.onUpdate { it.copy(tmdbLanguage = language) }; TmdbClient.clearCache() }

        // TMDB 收成一行：右侧只显示开关状态，点开才编辑 Key / 测试 / 切换开关。
        row("tmdb", R.string.settings_tmdb_row, {
            context.getString(
                if (state.settings.tmdbEnabled) R.string.settings_channel_enabled
                else R.string.settings_channel_disabled,
            )
        }) { showTmdbEditor() }

        channelSection()
    }

    /**
     * TMDB 配置弹窗。
     *
     * 规则：没测试成功过的 Key 不允许开启。测试成功会记录 Key 指纹并自动打开，
     * 换 Key 后指纹失效，必须重新测试。
     */
    private fun showTmdbEditorLegacy() {
        val (modal, content) = settingsDialog(context, context.getString(R.string.settings_tmdb_row))
        var enabled = state.settings.tmdbEnabled

        // ① 启用开关：和渠道弹窗同一位置、同一样式。
        val enableRow = NativeSettingRow(context)
        fun refreshEnable() {
            enableRow.bind(
                context.getString(R.string.settings_tmdb_enable),
                context.getString(if (enabled) R.string.settings_native_on else R.string.settings_native_off),
            )
        }
        enableRow.setOnClickListener {
            // 未验证的 Key 不允许开启；提示在测试结果行里给。
            if (!enabled && !TmdbKeyPolicy.canEnable(state.settings.tmdbApiKey, state.settings.tmdbVerifiedKey)) {
                return@setOnClickListener
            }
            enabled = !enabled
            refreshEnable()
        }
        refreshEnable()
        content.addView(enableRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ② Key 编辑
        content.addView(context.textView(context.getString(R.string.settings_native_api_key), 13f, ViewTheme.textSecondary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(12) })
        val editor = NativeSettingsEditor(context) { }.apply {
            setText(state.settings.tmdbApiKey)
            setSelection(text.length)
        }
        content.addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(52)))

        // ③ 状态行：测试结果就地显示
        val statusText = context.textView("", 13f, ViewTheme.textSecondary)
        content.addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(8)
        })

        // ④ 按钮：完成 / 测试（横向平分，不会再被挤出屏幕）
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(14)
        })
        dialogActionButton(buttons, R.string.settings_native_done) {
            val key = editor.text.toString().trim()
            if (enabled && !TmdbKeyPolicy.canEnable(key, state.settings.tmdbVerifiedKey)) {
                statusText.text = context.getString(R.string.settings_tmdb_test_first)
                return@dialogActionButton
            }
            actions.onUpdate { it.copy(tmdbApiKey = key, tmdbEnabled = enabled) }
            TmdbClient.clearCache()
            editor.hideKeyboard()
            modal.dismiss()
        }
        dialogActionButton(buttons, R.string.settings_channel_test) {
            val key = editor.text.toString().trim()
            if (key.isBlank()) {
                statusText.text = context.getString(R.string.settings_tmdb_key_required)
                return@dialogActionButton
            }
            runDialogTask("tmdb-dialog", statusText) {
                val match = TmdbClient.match(key, "沙丘2", null, state.settings.tmdbLanguage)
                if (match == null) {
                    context.getString(R.string.settings_channel_test_failed)
                } else {
                    actions.onUpdate {
                        it.copy(
                            tmdbApiKey = key,
                            tmdbVerifiedKey = TmdbKeyPolicy.fingerprint(key),
                        )
                    }
                    TmdbClient.clearCache()
                    context.getString(R.string.settings_channel_test_ok)
                }
            }
        }

        showModal(modal, "tmdb") { editor.requestFocus() }
    }

    /**
     * 三个固定刮削来源：TMDB / Streamx / TVmaze。
     *
     * 顺序决定优先级（在线源固定最后）；点某一行直接进编辑弹窗，
     * 不再有中间的操作菜单。
     */
    private fun channelSection() {
        channelRow(LibraryArtworkSource.DOUBAN)
        channelRow(LibraryArtworkSource.STREAMX)
        channelRow(LibraryArtworkSource.TVMAZE)
        row("artwork-order", R.string.settings_artwork_order, { orderSummary() }) { showOrderEditor() }
    }

    private fun channelOf(source: LibraryArtworkSource): ArtworkChannel? =
        state.settings.artworkChannels.firstOrNull { it.kind == source.name }

    private fun enabledText(enabled: Boolean): String =
        context.getString(if (enabled) R.string.settings_channel_enabled else R.string.settings_channel_disabled)

    private fun channelRow(source: LibraryArtworkSource) {
        val channel = channelOf(source) ?: return
        row("channel:${source.name}", source.displayName, { enabledText(channel.enabled) }) {
            showChannelEditor(source)
        }
    }

    private fun orderSummary(): String {
        val order = state.settings.artworkOrder
        return (order + "ONLINE").joinToString(" → ") {
            LibraryArtworkSource.entries.firstOrNull { source -> source.name == it }?.displayName ?: it
        }
    }

    /** 排序弹窗：点某一项，选“放到第 N 位”。 */
    private fun showOrderEditorLegacy() {
        val (modal, content) = settingsDialog(context, context.getString(R.string.settings_artwork_order))
        content.addView(
            context.textView(context.getString(R.string.settings_artwork_order_hint), 13f, ViewTheme.textSecondary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.vDp(10) },
        )
        state.settings.artworkOrder.forEachIndexed { index, key ->
            val source = LibraryArtworkSource.entries.firstOrNull { it.name == key } ?: return@forEachIndexed
            row("order:$key", source.displayName, {
                context.getString(R.string.settings_artwork_order_position, index + 1)
            }) {
                val anchor = controls.getValue("order:$key")
                val labels = state.settings.artworkOrder.indices.map {
                    context.getString(R.string.settings_artwork_order_position, it + 1)
                }
                showAnchoredChoicePopup(anchor, labels, index,
                    restoreFocus = { scheduleFocus("order:$key") }) { position ->
                        actions.onUpdate { current ->
                            val list = current.artworkOrder.toMutableList()
                            val from = list.indexOf(key)
                            if (from < 0 || position == from) return@onUpdate current
                            list.removeAt(from)
                            list.add(position.coerceIn(0, list.size), key)
                            current.copy(artworkOrder = list)
                        }
                        modal.dismiss()
                    }
            }
        }
        showModal(modal, "artwork-order") { controls["order:${state.settings.artworkOrder.firstOrNull()}"]?.requestFocus() }
    }

    /**
     * 渠道编辑器。
     *
     * 每个渠道只暴露它真正需要的字段（目前只有实例地址），
     * 结构和 TMDB 弹窗保持一致：字段 + 启用 + 完成/测试。
     */
    private fun showChannelEditorLegacy(source: LibraryArtworkSource) {
        val base = channelOf(source) ?: ArtworkChannel(kind = source.name)
        val (modal, content) = settingsDialog(context, source.displayName)
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.vDp(4), 0, context.vDp(4), context.vDp(8))
        }
        scroll.addView(form, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val editors = linkedMapOf<String, EditText>()
        fun field(key: String, label: String, value: String, hint: String = "") {
            form.addView(context.textView(label, 13f, ViewTheme.textSecondary),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.vDp(10)
                })
            val edit = EditText(context).apply {
                setText(value)
                setSingleLine()
                setTextColor(ViewTheme.textPrimary)
                setHintTextColor(ViewTheme.textTertiary)
                if (hint.isNotBlank()) setHint(hint)
                background = focusStateBackground(
                    ViewTheme.surfaceElevated, ViewTheme.surfaceHigh,
                    context.vDp(7), context.vDp(2), ViewTheme.focusRing,
                )
                setPadding(context.vDp(12), context.vDp(8), context.vDp(12), context.vDp(8))
            }
            editors[key] = edit
            form.addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(48)))
        }

        field("baseUrl", context.getString(R.string.settings_channel_base_url), base.baseUrl, "https://api.example.com")
        form.addView(
            context.textView(
                context.getString(R.string.settings_channel_default_url, base.effectiveUrl),
                12f, ViewTheme.textTertiary,
            ),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(4) },
        )

        content.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 启用开关放在底部按钮上方，和 TMDB 弹窗保持同一位置与样式。
        var enabled = base.enabled
        val enableRow = NativeSettingRow(context)
        fun refreshEnable() {
            enableRow.bind(
                context.getString(R.string.settings_channel_enable_row),
                context.getString(if (enabled) R.string.settings_native_on else R.string.settings_native_off),
            )
        }
        enableRow.setOnClickListener { enabled = !enabled; refreshEnable() }
        refreshEnable()
        content.addView(enableRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(10)
        })

        // 底部一行：完成 / 测试 / 测试结果（结果在测试按钮右侧，窗口不关闭）。
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(actionsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(10)
        })
        val testStatus = context.textView("", 13f, ViewTheme.textSecondary).apply { maxLines = 2 }

        fun value(key: String, fallback: String = ""): String {
            val text = editors[key]?.text?.toString()?.trim()
            return if (text.isNullOrEmpty()) fallback else text
        }
        /** 用弹窗里当前的输入构造渠道，不要求先保存。 */
        fun draftChannel(): ArtworkChannel = base.copy(baseUrl = value("baseUrl"), enabled = enabled)
        dialogActionButton(actionsRow, R.string.settings_native_done) {
            val next = base.copy(baseUrl = value("baseUrl"), enabled = enabled)
            actions.onUpdate { current ->
                val list = current.artworkChannels.toMutableList()
                val at = list.indexOfFirst { it.kind == next.kind }
                if (at >= 0) list[at] = next else list += next
                current.copy(artworkChannels = list)
            }
            modal.dismiss()
        }

        // 用当前输入直接测，不必先保存；结果就地显示。
        dialogActionButton(actionsRow, R.string.settings_channel_test) {
            val draft = draftChannel()
            runDialogTask("channel-test:${draft.id}", testStatus) {
                val agent = "Reelbox/${state.version} (Android TV)"
                val error = when (draft.kind) {
                    ArtworkChannelKind.TVMAZE.name -> TvmazeClient.test(draft.effectiveUrl, agent)
                    ArtworkChannelKind.DOUBAN.name -> DoubanClient.test(draft.effectiveUrl)
                    else -> StreamxClient.test(draft.effectiveUrl, agent)
                }
                error ?: context.getString(R.string.settings_channel_test_ok)
            }
        }
        // 测试结果紧跟在测试按钮右侧，窗口保持打开。
        actionsRow.addView(testStatus, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = context.vDp(12)
            rightMargin = context.vDp(6)
        })

        showModal(modal, "channel-edit:${source.name}") { editors["baseUrl"]?.requestFocus() }
    }

    /**
     * TMDB 与内置渠道共用的编辑弹窗。
     *
     * 结构固定为：字段 → 启用开关 → 状态行 → 保存/测试。
     * 默认焦点放在启用开关上，保存后收键盘并回到打开弹窗的那一行。
     */
    private fun showArtworkSourceEditor(
        title: String,
        fieldLabel: String,
        initialValue: String,
        defaultAddress: String? = null,
        initiallyEnabled: Boolean,
        origin: String,
        toggleError: (String) -> String? = { null },
        save: (String, Boolean) -> String?,
        test: suspend (String) -> String,
    ) {
        val (modal, content) = settingsDialog(context, title)
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.vDp(2), 0, context.vDp(2), 0)
        }

        form.addView(
            context.textView(fieldLabel, 13f, ViewTheme.textSecondary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.vDp(6)
            },
        )

        var enabled = initiallyEnabled
        val statusText = context.textView("", 13f, ViewTheme.textSecondary).apply { maxLines = 3 }
        var editor: NativeSettingsEditor? = null

        fun value(): String = editor?.text?.toString()?.trim().orEmpty()

        fun submit() {
            val error = save(value(), enabled)
            if (error != null) {
                statusText.text = error
                return
            }
            editor?.hideKeyboard()
            modal.dismiss()
        }

        editor = NativeSettingsEditor(context) { submit() }.apply {
            setText(initialValue)
            setSelection(text.length)
            onBack = { modal.dismiss() }
        }
        form.addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(52)))

        defaultAddress?.takeIf { it.isNotBlank() }?.let { address ->
            form.addView(
                context.textView(
                    context.getString(R.string.settings_channel_default_url, address),
                    12f, ViewTheme.textTertiary,
                ),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.vDp(4)
                },
            )
        }
        content.addView(form, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val enableRow = NativeSettingRow(context)
        fun refreshEnable() {
            enableRow.bind(
                context.getString(
                    if (enabled) R.string.settings_artwork_close
                    else R.string.settings_artwork_start,
                ),
                context.getString(if (enabled) R.string.settings_native_on else R.string.settings_native_off),
            )
        }
        enableRow.setOnClickListener {
            val next = !enabled
            val error = if (next) toggleError(value()) else null
            if (error != null) {
                statusText.text = error
            } else {
                enabled = next
                refreshEnable()
            }
        }
        refreshEnable()
        content.addView(enableRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(10)
        })

        content.addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(8)
        })

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(14)
        })
        dialogActionButton(buttons, R.string.settings_save) { submit() }
        dialogActionButton(buttons, R.string.settings_channel_test) {
            runDialogTask("artwork-test:$origin", statusText) { test(value()) }
        }

        showModal(modal, origin) {
            enableRow.postDelayed({ if (modal.isShowing) enableRow.requestFocus() }, 60)
        }
    }

    private fun showTmdbEditor() {
        showArtworkSourceEditor(
            title = context.getString(R.string.settings_tmdb_row),
            fieldLabel = context.getString(R.string.settings_native_api_key),
            initialValue = state.settings.tmdbApiKey,
            initiallyEnabled = state.settings.tmdbEnabled,
            origin = "tmdb",
            toggleError = { key ->
                if (TmdbKeyPolicy.canEnable(key, state.settings.tmdbVerifiedKey)) null
                else context.getString(R.string.settings_tmdb_test_first)
            },
            save = { key, enabled ->
                if (enabled && !TmdbKeyPolicy.canEnable(key, state.settings.tmdbVerifiedKey)) {
                    context.getString(R.string.settings_tmdb_test_first)
                } else {
                    actions.onUpdate { it.copy(tmdbApiKey = key, tmdbEnabled = enabled) }
                    TmdbClient.clearCache()
                    null
                }
            },
            test = { key ->
                if (key.isBlank()) {
                    context.getString(R.string.settings_tmdb_key_required)
                } else {
                    val match = TmdbClient.match(key, "沙丘2", null, state.settings.tmdbLanguage)
                    if (match == null) {
                        context.getString(R.string.settings_channel_test_failed)
                    } else {
                        actions.onUpdate {
                            it.copy(
                                tmdbApiKey = key,
                                tmdbVerifiedKey = TmdbKeyPolicy.fingerprint(key),
                            )
                        }
                        TmdbClient.clearCache()
                        context.getString(R.string.settings_channel_test_ok)
                    }
                }
            },
        )
    }

    /** Sort dialog rows must live in this dialog, never in the main page's focus map. */
    private fun showOrderEditor() {
        val (modal, content) = settingsDialog(context, context.getString(R.string.settings_artwork_order))
        content.addView(
            context.textView(context.getString(R.string.settings_artwork_order_hint), 13f, ViewTheme.textSecondary),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.vDp(10)
            },
        )

        val rows = linkedMapOf<String, NativeSettingRow>()
        state.settings.artworkOrder.forEachIndexed { index, key ->
            val source = LibraryArtworkSource.entries.firstOrNull { it.name == key } ?: return@forEachIndexed
            val row = NativeSettingRow(context).apply {
                bind(source.displayName, context.getString(R.string.settings_artwork_order_position, index + 1))
            }
            row.setOnClickListener {
                val labels = state.settings.artworkOrder.indices.map {
                    context.getString(R.string.settings_artwork_order_position, it + 1)
                }
                showAnchoredChoicePopup(
                    anchor = row,
                    labels = labels,
                    selected = index,
                    restoreFocus = { rows[key]?.requestFocus() },
                ) { position ->
                    val order = state.settings.artworkOrder.toMutableList()
                    val from = order.indexOf(key)
                    if (from >= 0 && position != from) {
                        order.removeAt(from)
                        order.add(position.coerceIn(0, order.size), key)
                        actions.onUpdateArtworkOrder?.invoke(order) ?: actions.onUpdate {
                            it.copy(artworkOrder = order)
                        }
                    }
                    modal.dismiss()
                }
            }
            rows[key] = row
            content.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.vDp(7)
            })
        }
        content.addView(
            context.textView(
                context.getString(R.string.settings_artwork_order_online_fixed),
                13f, ViewTheme.textTertiary,
            ),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.vDp(10)
            },
        )
        showModal(modal, "artwork-order") {
            rows[state.settings.artworkOrder.firstOrNull()]?.requestFocus()
                ?: rows.values.firstOrNull()?.requestFocus()
        }
    }

    private fun showChannelEditor(source: LibraryArtworkSource) {
        val base = channelOf(source) ?: ArtworkChannel(kind = source.name)
        showArtworkSourceEditor(
            title = source.displayName,
            fieldLabel = context.getString(R.string.settings_channel_base_url),
            initialValue = base.baseUrl,
            defaultAddress = base.effectiveUrl,
            initiallyEnabled = base.enabled,
            origin = "channel:${source.name}",
            save = { url, enabled ->
                val next = base.copy(baseUrl = url, enabled = enabled)
                actions.onUpdate { current ->
                    val list = current.artworkChannels.toMutableList()
                    val at = list.indexOfFirst { it.kind == next.kind }
                    if (at >= 0) list[at] = next else list += next
                    current.copy(artworkChannels = list)
                }
                null
            },
            test = { url ->
                val draft = base.copy(baseUrl = url)
                val agent = "Reelbox/${state.version} (Android TV)"
                val error = when (draft.kind) {
                    ArtworkChannelKind.TVMAZE.name -> TvmazeClient.test(draft.effectiveUrl, agent)
                    ArtworkChannelKind.DOUBAN.name -> DoubanClient.test(draft.effectiveUrl)
                    else -> StreamxClient.test(draft.effectiveUrl, agent)
                }
                error ?: context.getString(R.string.settings_channel_test_ok)
            },
        )
    }

    private fun playbackPage() {
        toggle("keep-screen", R.string.settings_keep_screen_on, { state.settings.keepScreenOn }) { it.copy(keepScreenOn = !it.keepScreenOn) }
        choice("hide-controls", R.string.settings_controls_hide, listOf(0, 3, 5, 8), { state.settings.controlsHideSeconds },
            { seconds -> if (seconds == 0) context.getString(R.string.settings_controls_hide_never) else context.getString(R.string.settings_controls_hide_seconds, seconds) }) {
            seconds -> actions.onUpdate { it.copy(controlsHideSeconds = seconds) }
        }
        choice(
            "player-panel-size", R.string.settings_player_panel_size,
            listOf(PlayerPanelSize.COMPACT, PlayerPanelSize.SMALL, PlayerPanelSize.STANDARD, PlayerPanelSize.LARGE),
            { PlayerPanelSize.fromStored(state.settings.playerPanelSize) },
            { size -> context.getString(when (size) {
                PlayerPanelSize.COMPACT -> R.string.settings_player_panel_compact
                PlayerPanelSize.SMALL -> R.string.settings_player_panel_small
                PlayerPanelSize.STANDARD -> R.string.settings_player_panel_standard
                PlayerPanelSize.LARGE -> R.string.settings_player_panel_large
            }) },
        ) { size -> actions.onUpdate { it.copy(playerPanelSize = size.key) } }
        choice("decoder", R.string.decoder_default, DecoderMode.entries.toList(),
            { DecoderMode.fromStored(state.settings.defaultDecoder) }, { context.getString(it.labelRes()) }) {
            mode -> actions.onUpdate { it.copy(defaultDecoder = mode.name) }
        }
        info("decoder-help") { context.getString(R.string.decoder_default_hint) }
    }

    private fun appearancePage() {
        choice("layout-theme", R.string.theme_layout_title, listOf("ORIGINAL", "GALLERY", "SIDEBAR", "TOPBAR"),
            { state.settings.appearance.layoutTheme }, { name ->
                context.getString(when (name) {
                    "GALLERY" -> R.string.theme_layout_gallery
                    "SIDEBAR" -> R.string.theme_layout_sidebar
                    "TOPBAR" -> R.string.theme_layout_topbar
                    else -> R.string.theme_layout_original
                })
            }) { name -> actions.onUpdate { it.copy(appearance = it.appearance.copy(layoutTheme = name)) } }
        info("theme-help") { context.getString(R.string.theme_layout_help) }
        choice("poster-size", R.string.appearance_poster_size, (4..8).toList(), { state.settings.appearance.posterColumns },
            { count -> context.getString(listOf(R.string.appearance_size_largest, R.string.appearance_size_large,
                R.string.appearance_size_standard, R.string.appearance_size_small, R.string.appearance_size_smallest)[count - 4]) }) {
            count -> actions.onUpdate { it.copy(appearance = it.appearance.copy(posterColumns = count)) }
        }
        info("poster-help") { context.getString(R.string.appearance_poster_hint) }
        heading(R.string.appearance_navigation)
        state.settings.appearance.normalized().order.forEachIndexed { index, name ->
            val label = context.getString(TopDest.valueOf(name).labelRes)
            val canHide = name in AppearancePreferences.HIDEABLE
            row("nav:$name", label, {
                val shown = if (name in state.settings.appearance.hidden) {
                    R.string.appearance_hidden
                } else {
                    R.string.appearance_shown
                }
                context.getString(R.string.appearance_navigation_position, index + 1, context.getString(shown))
            }) {
                val anchor = controls.getValue("nav:$name")
                val options = buildList {
                    add(context.getString(R.string.appearance_move_before))
                    add(context.getString(R.string.appearance_move_after))
                    if (canHide) add(context.getString(
                        if (name in state.settings.appearance.hidden) R.string.appearance_show
                        else R.string.appearance_hide,
                    ))
                }
                showAnchoredChoicePopup(anchor, options, 0, restoreFocus = { scheduleFocus("nav:$name") }) { choice ->
                    if (choice == 0 || choice == 1) {
                        actions.onUpdate {
                            it.copy(appearance = it.appearance.move(name, if (choice == 0) -1 else 1))
                        }
                    } else if (choice == 2 && canHide) {
                        actions.onUpdate {
                            it.copy(appearance = it.appearance.copy(hidden =
                                if (name in it.appearance.hidden) it.appearance.hidden - name
                                else it.appearance.hidden + name))
                        }
                    }
                }
            }
        }
        info("navigation-help") { context.getString(R.string.appearance_navigation_hint) }
        row("appearance-reset", R.string.appearance_reset) { actions.onUpdate { it.copy(appearance = AppearancePreferences()) } }
    }

    private fun authPage() {
        info("auth-help") { context.getString(R.string.settings_native_auth_help) }
        listOf(PanCookieStore.Provider.QUARK to R.string.settings_native_provider_quark, PanCookieStore.Provider.UC to R.string.settings_native_provider_uc,
            PanCookieStore.Provider.PAN115 to R.string.settings_native_provider_115, PanCookieStore.Provider.ALI to R.string.settings_native_provider_aliyun,
            PanCookieStore.Provider.BAIDU to R.string.settings_native_provider_baidu).forEach { (provider, label) ->
            row("provider:${provider.name}", label, { context.getString(if (provider.name in state.providers)
                R.string.settings_native_authorized else R.string.settings_native_unauthorized) }) { actions.onOpenAuth(provider) }
            if (provider.name in state.providers && actions.onClearAuth != null) {
                row("provider-clear:${provider.name}", R.string.settings_native_clear_auth,
                    { context.getString(R.string.settings_native_clear_auth_hint) }) {
                    confirm(R.string.settings_native_clear_auth, "provider-clear:${provider.name}") {
                        actions.onClearAuth?.invoke(provider)
                        snapshot?.let { rebuildPage() }
                        refreshBindings()
                    }
                }
            }
        }
    }

    private fun dataPage() {
        heading(R.string.settings_tasks_heading)
        info("scan-status") { state.scan.let { scan -> when {
            scan.running -> state.scanProgress ?: context.getString(R.string.settings_loading)
            scan.error != null -> scan.error
            !scan.started -> context.getString(R.string.settings_not_started)
            else -> context.getString(R.string.settings_task_scan_done, scan.directories, scan.failures)
        } } }
        row("scan-retry", R.string.settings_scan_now, { if (state.scan.running) context.getString(R.string.settings_loading) else "" }) {
            if (!state.scan.running) actions.onRefresh()
        }
        val artwork = state.artwork
        when {
            artwork.running -> info("artwork-status") {
                context.getString(R.string.settings_task_artwork) + "：" +
                    context.getString(R.string.settings_task_artwork_running, artwork.processed, artwork.total)
            }
            artwork.error != null -> row(
                "artwork-retry",
                R.string.settings_task_artwork,
                { artwork.error.orEmpty() },
                { context.getString(R.string.settings_task_retry) },
            ) { actions.onRetryArtwork() }
            else -> info("artwork-status") {
                context.getString(R.string.settings_task_artwork) + "：" +
                    if (artwork.total == 0) context.getString(R.string.settings_not_started)
                    else context.getString(R.string.settings_task_artwork_done, artwork.processed, artwork.total, artwork.missing)
            }
        }
        heading(R.string.settings_storage_heading)
        row("directories", R.string.library_manage_directories, { context.getString(R.string.settings_open_content) }) {
            guarded { actions.onManageDirectories() }
        }
        info("cache-size") { android.text.format.Formatter.formatFileSize(context, cacheBytes) }
        row("clear-images", R.string.settings_cache_clear, { status("clear-images") }, { context.getString(R.string.settings_cache_keep) }) {
            if ("clear-images" !in busy) confirm(R.string.settings_cache_clear, "clear-images") {
                busy += "clear-images"; refreshBindings()
                actions.onClearImages {
                    if (!disposed) { busy -= "clear-images"; loadCacheSize(); refreshBindings() }
                }
            }
        }
        row("clear-history", R.string.settings_clear_history, { context.getString(R.string.settings_history_count, state.historyCount) }) {
            confirm(R.string.settings_clear_history, "clear-history") { actions.onClearHistory() }
        }
        if (!cacheLoaded) loadCacheSize()
    }

    private fun loadCacheSize() {
        cacheLoaded = true
        scope.launch {
            cacheBytes = withContext(Dispatchers.IO) { PosterCacheCleaner.diskUsageBytes(context) }
            refreshBindings()
        }
    }

    private fun status(key: String): String = if (key in busy) context.getString(R.string.settings_loading) else ""
    private fun runTask(key: String, operation: suspend () -> String) {
        if (!busy.add(key)) return
        refreshBindings()
        scope.launch {
            try {
                results[key] = operation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { results[key] = failure.message ?: context.getString(R.string.settings_task_failed) }
            finally { busy -= key; if (!disposed) refreshBindings() }
        }
    }

    /**
     * 弹窗内的任务：结果直接写回弹窗里的文本，不走设置页的 results 表。
     *
     * 设置页的 runTask 只 refreshBindings()，而弹窗是独立视图，
     * 不刷新就会看起来"点了没反应"。
     */
    private fun runDialogTask(key: String, target: TextView, operation: suspend () -> String) {
        if (!busy.add(key)) return
        target.text = context.getString(R.string.settings_loading)
        scope.launch {
            val text = try {
                operation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failure.message ?: context.getString(R.string.settings_task_failed) }
            finally { busy -= key }
            if (!disposed && target.isAttachedToWindow) target.text = text
        }
    }

    private fun guarded(action: () -> Unit) {
        if (session.dirtySections.isEmpty()) { action(); return }
        val origin = session.focusKey
        val (modal, content) = settingsDialog(context, context.getString(R.string.settings_discard_title))
        content.addView(context.textView(context.getString(R.string.settings_discard_body), 14f, ViewTheme.textSecondary))
        val keep = dialogButton(content, R.string.settings_keep_editing) { modal.dismiss() }
        dialogButton(content, R.string.settings_discard) {
            session.dirtySections.forEach { section -> session.draft(section).discard(savedValues(section)) }
            refreshBindings()
            modal.dismiss()
            action()
        }
        showModal(modal, origin) { keep.requestFocus() }
    }

    private fun confirm(title: Int, origin: String, action: () -> Unit) {
        confirm(title, context.getString(R.string.settings_native_confirm_clear), origin, action)
    }

    private fun confirm(title: Int, body: String, origin: String, action: () -> Unit) {
        val (modal, content) = settingsDialog(context, context.getString(title))
        content.addView(context.textView(body, 15f, ViewTheme.textSecondary))
        val cancel = dialogButton(content, R.string.action_cancel) { modal.dismiss() }
        dialogButton(content, R.string.action_confirm) { modal.dismiss(); action() }
        showModal(modal, origin) { cancel.requestFocus() }
    }

    private fun dialogButton(parent: LinearLayout, label: Int, action: () -> Unit): NativeSettingRow =
        NativeSettingRow(context).apply {
            bind(context.getString(label))
            // 焦点态背景由 NativeSettingRow 内部预建，这里不需要监听
            setOnClickListener { action() }
            parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(10) })
        }

    /**
     * 横向排列的弹窗按钮。
     *
     * 不能用 `NativeSettingRow`：它内部把值区固定成 260dp，
     * 放进带权重的横向容器会把标题压扁（之前"测试按钮看不见"就是这个原因）。
     * 这里用纯文本按钮，文字居中，多个按钮按 weight 平分一行。
     */
    private fun dialogActionButton(parent: LinearLayout, label: Int, weight: Float = 1f,
        action: () -> Unit): TextView =
        context.textView(context.getString(label), 15f, ViewTheme.textPrimary, Gravity.CENTER).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = focusStateBackground(
                ViewTheme.surfaceElevated, ViewTheme.surfaceHigh,
                context.vDp(7), context.vDp(2), ViewTheme.focusRing,
            )
            setOnClickListener { action() }
            parent.addView(this, LayoutParams(0, context.vDp(50), weight).apply {
                leftMargin = context.vDp(6)
                rightMargin = context.vDp(6)
            })
        }

    private fun showEditor(key: String, label: String, secret: Boolean, section: NativeSettingsSection) {
        val (modal, content) = settingsDialog(context, label)
        var editor: NativeSettingsEditor? = null
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            session.draft(section).edit(key, editor?.text?.toString().orEmpty())
            commitDraft(section)
            editor?.hideKeyboard()
            modal.dismiss()
        }
        fun cancelEdit() {
            if (finished) return
            finished = true
            editor?.hideKeyboard()
            modal.dismiss()
        }
        editor = NativeSettingsEditor(context) { finish() }.apply {
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(session.draft(section).value(key))
            setSelection(text.length)
            onBack = { cancelEdit() }
        }
        content.addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(56)))
        dialogButton(content, R.string.settings_native_done) { finish() }
        var imeHiddenByBack = false
        modal.setOnKeyListener { _, code, event ->
            if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                if (imeHiddenByBack) {
                    cancelEdit()
                } else if (editor?.isImeVisible() == true) {
                    imeHiddenByBack = true
                    editor?.hideKeyboard()
                } else {
                    cancelEdit()
                }
                true
            } else false
        }
        modal.setOnCancelListener { cancelEdit() }
        modal.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showModal(modal, "field:$key") { editor.requestFocus() }
    }

    private fun showPhone() {
        val (modal, content) = settingsDialog(context, context.getString(R.string.settings_phone_entry))
        val address = state.phoneUrl
        content.addView(context.textView(context.getString(R.string.settings_native_phone_help), 14f, ViewTheme.textSecondary))
        if (address != null) {
            val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 300, 300,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
            val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE }
            val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            content.addView(ImageView(context).apply { setImageBitmap(bitmap); setBackgroundColor(Color.WHITE) },
                LayoutParams(context.vDp(210), context.vDp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = context.vDp(12) })
        }
        content.addView(context.textView(address ?: context.getString(R.string.settings_native_phone_unavailable), 17f, ViewTheme.textPrimary, Gravity.CENTER),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(12) })
        val hint = context.textView(
            context.getString(R.string.settings_native_back_hint),
            13f,
            ViewTheme.textSecondary,
            Gravity.CENTER,
        ).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(context.vDp(14), context.vDp(10), context.vDp(14), context.vDp(10))
            background = focusStateBackground(
                ViewTheme.surfaceElevated, ViewTheme.surfaceHigh, context.vDp(7),
                context.vDp(2), ViewTheme.focusRing,
            )
        }
        content.addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(16)
        })
        showModal(modal, "phone") { hint.requestFocus() }
    }

    private fun showModal(modal: Dialog, origin: String, onShow: () -> Unit) {
        cancelAnchoredChoicePopupFocus(this)
        dialog?.dismiss()
        focusEpoch++
        val ticket = focusEpoch
        dialog = modal
        modal.setOnDismissListener {
            if (dialog === modal) dialog = null
            if (!disposed && ticket == focusEpoch) scheduleFocus(origin)
        }
        modal.show()
        modal.sizeForSettings(context)
        onShow()
    }

    /** Library opens on its first segment; other sections use their first real row. */
    private fun firstContentKey(): String =
        if (session.section == NativeSettingsSection.LIBRARY && "library-tab:alist" in focusTargets) {
            "library-tab:alist"
        } else controls.keys.firstOrNull {
            !it.startsWith("rail:") && it != "phone"
        } ?: "rail:${session.section.name}"

    private fun preview(section: NativeSettingsSection) {
        if (session.section == section) return
        session.section = section
        snapshot?.let { render(it, actions) }
    }

    /** Rail preview debounce, mirroring the top navigation's focus-rest behaviour. */
    private fun scheduleRailPreview(section: NativeSettingsSection) {
        removeCallbacks(railPreview)
        pendingRailSection = section
        if (session.section == section) return
        postDelayed(railPreview, RAIL_PREVIEW_SETTLE_MS)
    }

    private fun enter(section: NativeSettingsSection) {
        // OK is explicit: cancel any debounce and switch immediately.
        removeCallbacks(railPreview)
        pendingRailSection = null
        preview(section)
        snapshot?.let { render(it, actions) }
        val target = firstContentKey()
        session.inContent = !target.startsWith("rail:")
        scheduleFocus(target)
    }

    private fun returnToRail() {
        session.inContent = false
        val section = if (session.section == NativeSettingsSection.AUTH) NativeSettingsSection.ONLINE else session.section
        scheduleFocus("rail:${section.name}")
    }

    fun handleBack() {
        if (dialog?.isShowing == true) { dialog?.cancel(); return }
        when (KeyRouting.settingsBack(session.inContent, session.section == NativeSettingsSection.AUTH, false)) {
            KeyRouting.SettingsBackAction.CLOSE_AUTH -> enter(NativeSettingsSection.ONLINE)
            KeyRouting.SettingsBackAction.RETURN_TO_RAIL -> returnToRail()
            KeyRouting.SettingsBackAction.EXIT -> guarded { actions.onClose() }
            KeyRouting.SettingsBackAction.DEFER -> Unit
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 内容区上下键统一按几何位置跨行：行不需要登记，新增设置行自动参与。
        // 边界处保持焦点不动、只滚动页面，不能顺着系统 focusSearch 跳到左侧栏目。
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            if (moveFocusInContent(event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) return true
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == navigationKey) { navigationKey = null; return true }
        val action = KeyRouting.settingsBackKey(event.action == KeyEvent.ACTION_DOWN,
            event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE,
            backKey == event.keyCode, event.repeatCount, event.isCanceled)
        if (action == KeyRouting.ChoiceAction.IGNORE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && backKey == null) backKey = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) { backKey = null; if (action == KeyRouting.ChoiceAction.APPLY) handleBack() }
        return true
    }

    private fun scheduleFocus(key: String) {
        session.focusKey = key
        pendingFocus?.let(::removeCallbacks)
        val ticket = ++focusEpoch
        val request = Runnable {
            pendingFocus = null
            if (!disposed && isAttachedToWindow && hasWindowFocus() && dialog?.isShowing != true && ticket == focusEpoch)
                (focusTargets[key]?.takeIf { it.isShown && it.isEnabled }
                    ?: focusTargets["rail:${session.section.name}"])?.requestFocus()
        }
        pendingFocus = request
        doOnLayout { if (!disposed && ticket == focusEpoch) post(request) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleFocus(session.focusKey) }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && !disposed && dialog?.isShowing != true && findFocus() == null) scheduleFocus(session.focusKey)
    }
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        focusTargets[session.focusKey]?.requestFocus() == true || focusTargets["rail:LIBRARY"]?.requestFocus() == true
    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        focusEpoch++
        pendingFocus?.let(::removeCallbacks)
        pendingFocus = null
        dialog?.dismiss()
        dialog = null
        cancelAnchoredChoicePopupFocus(this)
        scope.cancel()
    }
    override fun popupFocusReturnTarget(origin: View): (() -> Boolean)? {
        val key = controls.entries.firstOrNull { it.value === origin }?.key ?: return null
        return { if (!disposed && isAttachedToWindow) { scheduleFocus(key); true } else false }
    }
    override fun focusFirst(): Boolean = controls[session.focusKey]?.requestFocus() == true
    override fun saveFocusIndex(): Int = NativeSettingsSection.entries.indexOf(session.section)
    override fun restoreFocus(index: Int) { scheduleFocus(session.focusKey) }
}
