package com.reelix.app.ui.screen.search

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.search.SearchResultGroup
import com.reelix.app.data.search.SearchResultGroups
import com.reelix.app.BuildConfig
import com.reelix.app.ui.component.PageView
import com.reelix.app.ui.component.PosterGridView
import com.reelix.app.ui.component.PosterTile
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.focusTileAt
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp

/**
 * 搜索结果 —— 左侧源筛选 + 右侧 Leanback 海报墙。
 */
class SearchResultView(
    context: Context,
    private val onOpenItem: (MediaItem) -> Unit,
    private val onExitTop: () -> Unit,
    private val onGroupSelected: (String) -> Unit = {},
    private val browse: MutableMap<String, com.reelix.app.ui.component.PosterGridMemory> = mutableMapOf(),
) : LinearLayout(context), PageView {

    private val count = context.textView("", 15f, ViewTheme.textPrimary)
    // ★ 2026-11-07 去掉右上角的「✕」按钮。
    //
    //   它和系统 BACK 键功能重复，而且因为成了 focusFirst() 的落点，
    //   每次进搜索结果焦点都先停在"关闭"上，反而挡路。
    //   现在焦点直接进过滤器列表，退出统一用 BACK。
    private val filterGrid = VerticalGridView(context).apply {
        setNumColumns(1)
        setHorizontalSpacing(0)
        setVerticalSpacing(context.vDp(6))
        setPadding(0, 0, 0, 0)
        clipToPadding = true
        setHasFixedSize(true)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = true
    }
    private val filterAdapter = FilterAdapter(
        onClick = { group -> selectGroup(group.key) },
        onRight = { grid.focusPosition(0) },
        onUp = { onExitTop() },
    )
    private val grid = PosterGridView(
        context = context,
        columns = 4,
        onClick = { tile -> tilePayload[tile.key]?.let(onOpenItem) },
        onExitUp = { focusSelectedFilter() },
        onExitLeft = { focusSelectedFilter() },
        minimumColumns = 4,
    )

    private var groups: List<SearchResultGroup> = emptyList()
    private var selectedKey: String = SearchResultGroups.ALL
    private var sourceLabels: Map<String, String> = emptyMap()
    private val tilePayload = LinkedHashMap<String, MediaItem>()
    private var lastSignature: String = ""

    init {
        orientation = HORIZONTAL
        setBackgroundColor(ViewTheme.backdrop)
        setPadding(context.vDp(48), context.vDp(96), context.vDp(48), context.vDp(24))

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(context.vDp(280), LayoutParams.MATCH_PARENT)
        }
        addView(left)

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            count,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        left.addView(
            head,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        left.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(8)))
        left.addView(
            filterGrid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        filterGrid.adapter = filterAdapter

        addView(View(context), LinearLayout.LayoutParams(context.vDp(20), 1))
        addView(
            grid,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
    }

    fun setPosterSize(columns: Int) = grid.setPosterSize(columns)

    fun render(
        groups: List<SearchResultGroup>,
        selectedKey: String,
        sourceLabels: Map<String, String>,
        running: Boolean,
    ) {
        val signature = buildString {
            append(selectedKey)
            append('|').append(running)
            append('|').append(sourceLabels.hashCode())
            groups.forEach { group ->
                append('|').append(group.key)
                group.items.forEach { append(',').append(SearchResultGroups.itemKey(it)) }
            }
        }
        if (signature == lastSignature) return
        lastSignature = signature
        val filterWasFocused = filterGrid.hasFocus()
        val gridWasFocused = grid.hasFocus()
        val focusedGridIndex = grid.currentPosition()
        this.groups = groups
        this.selectedKey = selectedKey
        this.sourceLabels = sourceLabels
        val all = groups.firstOrNull { it.key == SearchResultGroups.ALL }?.items.orEmpty()
        count.text = if (running) "搜索中…" else "搜索结果：${all.size}"
        filterAdapter.submit(groups, selectedKey)
        renderTiles()
        post {
            when {
                filterWasFocused -> focusSelectedFilter()
                gridWasFocused && grid.hasFocus() -> grid.restoreFocus(focusedGridIndex)
            }
        }
    }

    /** 结果更新后如果焦点丢了，把焦点送回结果区；不抢用户正在操作的焦点。 */
    fun ensureFocus() {
        if (!hasFocus() && !filterGrid.hasFocus() && !grid.hasFocus()) {
            focusFirst()
        }
    }

    fun selectGroup(key: String) {
        if (selectedKey == key || groups.none { it.key == key }) return
        selectedKey = key
        onGroupSelected(key)
        filterAdapter.submit(groups, selectedKey)
        renderTiles()
    }

    private fun renderTiles() {
        grid.useMemory(browse.getOrPut(selectedKey) { com.reelix.app.ui.component.PosterGridMemory() })
        val shown = groups.firstOrNull { it.key == selectedKey }?.items
            ?: groups.firstOrNull { it.key == SearchResultGroups.ALL }?.items.orEmpty()
        tilePayload.clear()
        val tiles = shown.map { item ->
            val key = SearchResultGroups.itemKey(item)
            tilePayload[key] = item
            PosterTile(
                key = key,
                title = item.matchedResourceName?.let { "${item.title} · $it" } ?: item.title,
                posterUrl = item.poster,
                posterRes = item.posterRes,
                rating = item.rating?.let { String.format("%.1f", it) },
                // 启用来源隐藏时，搜索结果不显示来源角标。
                source = if (BuildConfig.PUBLIC_BUILD) null else sourceLabelFor(item, sourceLabels),
            )
        }
        grid.submit(tiles)
    }

    private fun focusSelectedFilter() {
        val index = groups.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        filterGrid.setSelectedPosition(index)
        filterGrid.post {
            val holder = filterGrid.findViewHolderForAdapterPosition(index)
            holder?.itemView?.requestFocus() ?: filterGrid.requestFocus()
        }
    }

    override fun focusFirst(): Boolean {
        // 原来落在右上角「✕」上；按钮去掉后直接进过滤器列表
        focusSelectedFilter()
        return true
    }

    override fun saveFocusIndex(): Int = grid.currentPosition().also { grid.savePosition() }

    override fun restoreFocus(index: Int) {
        grid.restoreFocus(index)
    }

    private fun sourceLabelFor(item: MediaItem, labels: Map<String, String>): String {
        if (item.fromLibrary) {
            if (item.sourceKey.equals("alist", ignoreCase = true)) {
                val parts = (item.matchedResourcePath ?: item.searchResources.firstOrNull()?.path)
                    ?.split('/')?.filter { it.isNotBlank() }.orEmpty()
                if (parts.size > 1) return parts.first()
            }
            return when {
                item.sourceKey.equals("alist", ignoreCase = true) -> "Alist"
                item.sourceKey.contains("webdav", ignoreCase = true) -> "WebDAV"
                else -> "本地"
            }
        }
        return labels[item.sourceKey] ?: item.sourceKey.ifBlank { "在线" }
    }
}

private class FilterAdapter(
    private val onClick: (SearchResultGroup) -> Unit,
    private val onRight: () -> Unit,
    private val onUp: () -> Unit,
) : RecyclerView.Adapter<FilterAdapter.Holder>() {

    private var items: List<SearchResultGroup> = emptyList()
    private var selectedKey: String = SearchResultGroups.ALL

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = items[position].key.fold(1125899906842597L) { h, c -> 31 * h + c.code }

    inner class Holder(val row: TextView) : RecyclerView.ViewHolder(row)

    fun submit(groups: List<SearchResultGroup>, selected: String) {
        val old = items
        val oldSelected = selectedKey
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = groups.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].key == groups[b].key
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == groups[b] &&
                (old[a].key == oldSelected) == (groups[b].key == selected)
        })
        items = groups
        selectedKey = selected
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = TextView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(parent.context.vDp(14), 0, parent.context.vDp(14), 0)
            minimumHeight = parent.context.vDp(48)
        }
        val holder = Holder(row)
        row.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        row.setOnFocusChangeListener { _, focused ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) paint(holder, items[pos].key == selectedKey)
            // Focus callbacks may run inside Leanback's layout. Defer adapter updates until it exits.
            if (focused) row.post {
                val current = holder.bindingAdapterPosition
                if (row.hasFocus() && current != RecyclerView.NO_POSITION) onClick(items[current])
            }
        }
        row.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                onRight()
                true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                event.action == KeyEvent.ACTION_DOWN &&
                holder.bindingAdapterPosition == 0
            ) {
                onUp()
                true
            } else {
                false
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val group = items[position]
        val selected = group.key == selectedKey
        holder.row.text = buildString {
            append(group.label)
            if (group.items.isNotEmpty()) append("  ${group.items.size}")
        }
        paint(holder, selected)
    }

    private fun paint(holder: Holder, selected: Boolean) {
        val v = holder.row
        v.setTextColor(if (selected) ViewTheme.accent else ViewTheme.textPrimary)
        v.background = roundedBackground(
            if (selected) 0x33FFFFFF else if (v.hasFocus()) 0x1AFFFFFF else 0x0AFFFFFF,
            v.context.vDp(8), if (v.hasFocus()) v.context.vDp(2) else 0, ViewTheme.focusRing,
        )
    }
}
