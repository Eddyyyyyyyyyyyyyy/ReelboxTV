package com.reelix.app.ui.screen.library

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.reelix.app.R
import com.reelix.app.data.alist.AlistLibraryBuilder
import com.reelix.app.data.db.PlayProgressEntity
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.repo.LibraryRepository
import com.reelix.app.ui.component.PageView
import com.reelix.app.ui.component.FilterChip
import com.reelix.app.ui.component.PosterGridView
import com.reelix.app.ui.component.PosterTile
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.vDp
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView

/**
 * 片库页 —— 原生 View 版。
 *
 * 结构：筛选行 + Leanback 海报墙。页面只做“状态 → PosterTile”的映射，
 * 不直接发网络请求。
 */
class LibraryView(
    context: Context,
    private val onOpenItem: (MediaItem) -> Unit,
    private val onRefresh: () -> Unit,
    private val onExitTop: () -> Unit,
    private val onManageDirectories: () -> Unit = {},
    private val browse: com.reelix.app.ui.component.PosterGridMemory = com.reelix.app.ui.component.PosterGridMemory(),
    initialColumns: Int = 6,
    private val onDeleteEntry: ((AlistLibraryBuilder.LibraryEntry) -> Unit)? = null,
) : LinearLayout(context), PageView {

    private val chips = LinkedHashMap<LibraryFilter, FilterChip>()
    private val content = FrameLayout(context)
    private val status = context.textView(
        sizeSp = 14f,
        color = ViewTheme.textTertiary,
        gravity = Gravity.CENTER,
    )
    private val statsText = context.textView("", 11f, ViewTheme.textTertiary)
    private val refreshStatus = context.textView("", 12f, ViewTheme.textSecondary)
    private var refreshing = false
    private val refreshButton = FilterChip(
        context, context.getString(R.string.library_refresh),
        onClick = { if (!refreshing) onRefresh() },
        onUp = onExitTop,
    )
    private val grid = PosterGridView(
        context = context,
        columns = initialColumns,
        onClick = { tile ->
            if (deleteMode) {
                // In delete mode OK confirms; the confirmation dialog owns the second step.
                entriesById[tile.key]?.let { entry -> onDeleteEntry?.invoke(entry) }
            } else {
                tilePayload[tile.key]?.let(onOpenItem)
            }
        },
        onExitUp = { firstChip()?.requestFocus() },
        onLongClick = { tile ->
            val entry = entriesById[tile.key]
            if (onDeleteEntry != null && entry != null &&
                com.reelix.app.data.repo.librarySourceSupportsDelete(entry.item.sourceKey)
            ) {
                deleteMode = true
                armedKey = tile.key
                renderEntries()
            }
        },
        memory = browse,
    )

    private var filter = LibraryFilter.entries.firstOrNull { it.name == browse.section } ?: LibraryFilter.ALL
    private var deleteMode = false
    private var armedKey: String? = null
    private val entriesById = LinkedHashMap<String, AlistLibraryBuilder.LibraryEntry>()
    /** null = all sources; only exposed when more than one source is configured. */
    private var sourceFilter: String? = null
    private val sourceChips = LinkedHashMap<String, FilterChip>()
    private val sourceRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.vDp(28), 0, context.vDp(28), 0)
        visibility = GONE
    }
    private var entries: List<AlistLibraryBuilder.LibraryEntry> = emptyList()
    private var progressMap: Map<String, PlayProgressEntity> = emptyMap()
    private var lastRevision = -1
    private val tilePayload = LinkedHashMap<String, MediaItem>()

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ViewTheme.backdrop)
        setPadding(0, context.vDp(84), 0, 0)

        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.vDp(28), 0, context.vDp(28), 0)
        }
        addView(
            filterRow,
            LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(40)),
        )

        LibraryFilter.entries.forEach { f ->
            val chip = FilterChip(
                context = context,
                label = context.getString(f.labelRes),
                onClick = { filter = f; browse.section = f.name; renderEntries() },
                onDown = { grid.focusPosition(grid.currentPosition()) },
                onUp = onExitTop,
            )
            chips[f] = chip
            filterRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(36)).apply {
                rightMargin = context.vDp(8)
            })
        }

        filterRow.addView(View(context), LayoutParams(0, 1, 1f))

        val refresh = refreshButton
        refresh.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                onExitTop()
                true
            } else {
                false
            }
        }
        filterRow.addView(refresh)
        filterRow.addView(FilterChip(
            context, context.getString(R.string.library_manage_directories),
            onClick = onManageDirectories,
            onDown = { grid.focusPosition(grid.currentPosition()) },
            onUp = onExitTop,
        ))
        filterRow.addView(
            statsText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.vDp(12)
            },
        )
        addView(sourceRow, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(36)))

        addView(View(context), LayoutParams(1, context.vDp(10)))
        refreshStatus.setPadding(context.vDp(28), 0, context.vDp(28), 0)
        refreshStatus.visibility = GONE
        addView(refreshStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)

        grid.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        content.addView(grid)

        status.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        status.visibility = GONE
        content.addView(status)

        updateChips()
    }

    fun setPosterSize(columns: Int) = grid.setPosterSize(columns)

    /** Source chips appear only when the library aggregates several sources. */
    private fun rebuildSourceChips() {
        val sources = entries.mapNotNull { it.item.sourceKey.takeIf(String::isNotBlank) }
            .distinct().sorted()
        if (sources.size <= 1) {
            sourceRow.visibility = GONE
            sourceChips.clear()
            sourceRow.removeAllViews()
            if (sourceFilter != null) {
                sourceFilter = null
            }
            return
        }
        val signature = sources
        val focused = sourceChips.entries.firstOrNull { it.value.hasFocus() }?.key
        if (sourceRow.childCount != sources.size + 1 || sourceChips.keys.toList() != signature) {
            sourceRow.removeAllViews()
            sourceChips.clear()
            val all = FilterChip(
                context = context,
                label = context.getString(R.string.library_filter_all),
                onClick = { sourceFilter = null; renderEntries() },
                onDown = { grid.focusPosition(grid.currentPosition()) },
                onUp = onExitTop,
            )
            sourceChips[""] = all
            sourceRow.addView(all, LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(32)).apply {
                rightMargin = context.vDp(8)
            })
            sources.forEach { sourceKey ->
                val chip = FilterChip(
                    context = context,
                    label = librarySourceLabel(sourceKey),
                    onClick = { sourceFilter = sourceKey; renderEntries() },
                    onDown = { grid.focusPosition(grid.currentPosition()) },
                    onUp = onExitTop,
                )
                sourceChips[sourceKey] = chip
                sourceRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(32)).apply {
                    rightMargin = context.vDp(8)
                })
            }
            if (focused != null) sourceChips[focused]?.requestFocus()
        }
        sourceRow.visibility = VISIBLE
        sourceChips.forEach { (key, chip) -> chip.setChosen(key == (sourceFilter ?: "")) }
    }

    fun render(
        state: LibraryRepository.LibraryState,
        stats: AlistLibraryBuilder.Stats,
        progress: Map<String, PlayProgressEntity>,
        posterRevision: Int,
        refreshMessage: String? = null,
        refreshResult: String? = null,
        artworkMessage: String? = null,
    ) {
        refreshing = refreshMessage != null
        refreshButton.setLabel(context.getString(if (refreshing) R.string.library_refreshing else R.string.library_refresh))
        refreshStatus.text = listOfNotNull(refreshMessage ?: refreshResult, artworkMessage).joinToString("\n")
        refreshStatus.visibility = if (refreshStatus.text.isNotBlank()) VISIBLE else GONE
        progressMap = progress
        if (lastRevision != posterRevision) {
            lastRevision = posterRevision
            grid.refresh()
        }

        when (state) {
            is LibraryRepository.LibraryState.Idle -> showStatus(context.getString(R.string.library_idle))
            is LibraryRepository.LibraryState.Loading ->
                // Keep cached posters visible while a refresh is running.
                if (entries.isEmpty()) showStatus(state.message) else renderEntries()
            is LibraryRepository.LibraryState.Error ->
                showStatus(context.getString(R.string.library_error, state.message))
            is LibraryRepository.LibraryState.Ready -> {
                entries = state.entries
                statsText.text = context.getString(R.string.library_stats_files, stats.totalItems, stats.episodeCount)
                renderEntries()
            }
        }
    }

    private fun renderEntries() {
        updateChips()
        rebuildSourceChips()
        val byKind = when (filter) {
            LibraryFilter.ALL -> entries
            LibraryFilter.SERIES -> entries.filter { it.item.kind == MediaKind.SERIES }
            LibraryFilter.MOVIE -> entries.filter {
                it.item.kind == MediaKind.MOVIE || it.item.kind == MediaKind.UNKNOWN
            }
        }
        val filtered = sourceFilter?.let { key -> byKind.filter { it.item.sourceKey == key } } ?: byKind

        if (filtered.isEmpty()) {
            grid.visibility = GONE
            status.visibility = VISIBLE
            status.text = context.getString(R.string.library_empty_filter)
            return
        }

        tilePayload.clear()
        entriesById.clear()
        val tiles = com.reelix.app.data.alist.libraryEntriesByActivity(filtered,
            progressMap.mapValues { it.value.updatedAt }).map { entry ->
            val p = progressMap[entry.item.id]
            tilePayload[entry.item.id] = entry.item
            entriesById[entry.item.id] = entry
            PosterTile(
                key = entry.item.id,
                title = entry.item.title,
                posterUrl = entry.item.poster,
                posterRes = entry.item.posterRes,
                progress = p?.progress ?: entry.item.progress,
                allowTitleFallback = false,
                // Delete mode lights every card so the whole grid reads as armed.
                armed = deleteMode,
                source = entry.item.sourceKey
                    .takeIf { it.isNotBlank() && sourceFilter == null && sourceChips.isNotEmpty() }
                    ?.let(::librarySourceLabel),
            )
        }
        grid.visibility = VISIBLE
        status.visibility = GONE
        grid.submit(tiles)
    }

    private fun showStatus(message: String) {
        grid.visibility = GONE
        status.visibility = VISIBLE
        status.text = message
    }

    /** BACK leaves delete mode before the page itself handles the key. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (deleteMode && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_UP) {
                deleteMode = false
                armedKey = null
                renderEntries()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateChips() {
        chips.forEach { (f, chip) -> chip.setChosen(f == filter) }
    }

    private fun firstChip(): FilterChip? = chips[filter] ?: chips.values.firstOrNull()

    override fun focusFirst(): Boolean = if (browse.entered && grid.visibility == VISIBLE) { grid.restoreFocus(browse.index); true } else firstChip()?.requestFocus() == true

    override fun saveFocusIndex(): Int = grid.currentPosition().also { grid.savePosition() }

    override fun restoreFocus(index: Int) {
        grid.restoreFocus(index)
    }
}
