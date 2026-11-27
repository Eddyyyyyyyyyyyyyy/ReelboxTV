package com.reelix.app.ui.screen.search

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.search.HotSearch
import com.reelix.app.data.search.SearchHistory
import com.reelix.app.data.search.SearchMatcher
import com.reelix.app.data.search.SearchResultGroup
import com.reelix.app.data.search.SearchResultGroups
import com.reelix.app.data.search.SearchSuggest
import com.reelix.app.data.source.jar.OnlineSearch
import com.reelix.app.ui.component.PageView
import com.reelix.app.ui.component.KeyRouting
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 搜索页跨详情返回需要保留的状态；由 MainActivity 持有。 */
class SearchSession {
    val browse = mutableMapOf<String, com.reelix.app.ui.component.PosterGridMemory>()
    var query: String = ""
    var submittedQuery: String = ""
    var showResults: Boolean = false
    var libraryHits: List<MediaItem> = emptyList()
    var selectedGroupKey: String = SearchResultGroups.ALL
    fun deleteDraftEnd() {
        if (query.isNotEmpty()) query = query.substring(0, query.offsetByCodePoints(query.length, -1))
    }
    fun submit(word: String) {
        query = word
        submittedQuery = word
        showResults = true
    }
}

/**
 * 搜索页 —— 原生 View 版，输入态和结果态都在同一个 View 里切换。
 */
class SearchView(
    context: Context,
    private val onOpenItem: (MediaItem) -> Unit,
    private val onSearchOnline: (String) -> Unit,
    private val onExitTop: () -> Unit,
    private val session: SearchSession,
) : FrameLayout(context), PageView {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val input = SearchInputView(
        context = context,
        onQueryChange = { session.query = it; updateInput(); scheduleSuggest() },
        onSubmit = { submit(session.query) },
        onClear = { session.query = ""; updateInput(); scheduleSuggest() },
        onDelete = { session.deleteDraftEnd(); updateInput(); scheduleSuggest() },
        onClearHistory = { SearchHistory.clear(); history = emptyList(); updateInput() },
        onPickSuggestion = ::submit,
        onExitTop = onExitTop,
    )
    private val result = SearchResultView(
        context = context,
        onOpenItem = onOpenItem,
        onExitTop = onExitTop,
        onGroupSelected = { session.selectedGroupKey = it },
        browse = session.browse,
    )

    private var suggests: List<String> = emptyList()
    private var hotWords: List<String> = HotSearch.cached(context)
    private var hotLoading: Boolean = hotWords.isEmpty()
    private var suggestLoading: Boolean = false
    private var history: List<String> = SearchHistory.list()
    private var candidates: List<MediaItem> = emptyList()
    private var online: OnlineSearchUiState = OnlineSearchUiState()
    private var siteOrder: List<String> = emptyList()

    init {
        setBackgroundColor(ViewTheme.backdrop)
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(result, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        input.visibility = if (session.showResults) GONE else VISIBLE
        result.visibility = if (session.showResults) VISIBLE else GONE
        updateInput()
        loadHot()
        if (session.showResults) renderResult()
    }

    fun setPosterSize(columns: Int) = result.setPosterSize(columns)

    fun render(
        candidates: List<MediaItem>,
        online: OnlineSearchUiState,
        siteOrder: List<String>,
    ) {
        val candidatesChanged = this.candidates != candidates
        this.candidates = candidates
        this.online = online
        this.siteOrder = siteOrder
        // 片库可能在用户提交搜索之后才加载完；候选集变了要重算本地命中。
        if (candidatesChanged && session.showResults && session.submittedQuery.isNotBlank()) {
            session.libraryHits = SearchMatcher.search(session.submittedQuery, candidates)
                .map { it.item }
        }
        if (session.showResults) renderResult()
    }

    fun handleBack(): Boolean {
        if (!session.showResults) return false
        showInput()
        return true
    }

    private fun loadHot() {
        scope.launch {
            if (!HotSearch.isFresh(context)) {
                hotLoading = hotWords.isEmpty()
                val fresh = HotSearch.refresh(context)
                if (fresh.isNotEmpty()) hotWords = fresh
                hotLoading = false
                updateInput()
            } else {
                hotLoading = false
                updateInput()
            }
        }
    }

    private fun submit(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        session.submit(k)
        session.libraryHits = SearchMatcher.search(k, candidates).map { it.item }
        SearchHistory.add(k)
        history = SearchHistory.list()
        input.visibility = GONE
        result.visibility = VISIBLE
        renderResult()
        result.post { result.focusFirst() }
        onSearchOnline(k)
    }

    private fun showInput() {
        session.showResults = false
        result.visibility = GONE
        input.visibility = VISIBLE
        updateInput()
        scheduleSuggest()
        input.focusDelete()
    }

    private fun updateInput() {
        input.render(
            query = session.query,
            suggests = suggests,
            hotWords = hotWords,
            hotLoading = hotLoading,
            suggestLoading = suggestLoading,
            history = history,
        )
    }

    private fun scheduleSuggest() {
        scope.launch {
            val requested = session.query
            if (requested.isBlank()) {
                suggests = emptyList()
                suggestLoading = false
                updateInput()
                return@launch
            }
            suggestLoading = true
            updateInput()
            kotlinx.coroutines.delay(200)
            val result = SearchSuggest.suggest(requested)
            if (session.query == requested) {
                suggests = result
                suggestLoading = false
                updateInput()
            }
        }
    }

    private fun renderResult() {
        val siteGroups = online.hits
            .filter { it.items.isNotEmpty() }
            .sortedWith(
                compareBy { site ->
                    siteOrder.indexOf(site.siteKey).let { if (it >= 0) it else Int.MAX_VALUE }
                }
            )
            .map { site ->
                SearchResultGroup(
                    key = site.siteKey,
                    label = site.siteName,
                    items = site.items.map { item ->
                        if (item.sourceKey.isBlank()) item.copy(sourceKey = site.siteKey) else item
                    },
                )
            }
        val homeSiteKey = online.hits.firstOrNull { it.isHomeSite }?.siteKey
        val groups = SearchResultGroups.build(session.libraryHits, siteGroups, homeSiteKey)
        if (groups.none { it.key == session.selectedGroupKey }) {
            session.selectedGroupKey = SearchResultGroups.ALL
        }
        val labels = siteGroups.associate { it.key to it.label }
        result.render(groups, session.selectedGroupKey, labels, online.running)
    }

    override fun focusFirst(): Boolean =
        if (session.showResults) result.focusFirst() else input.focusQuery()

    override fun saveFocusIndex(): Int = result.saveFocusIndex()

    override fun restoreFocus(index: Int) {
        result.restoreFocus(index)
    }
}

/**
 * 搜索输入态：搜索框 + TV 键盘 + 热门/联想 + 历史。
 */
private class SearchInputView(
    context: Context,
    private val onQueryChange: (String) -> Unit,
    private val onSubmit: () -> Unit,
    private val onClear: () -> Unit,
    private val onDelete: () -> Unit,
    private val onClearHistory: () -> Unit,
    private val onPickSuggestion: (String) -> Unit,
    private val onExitTop: () -> Unit,
) : LinearLayout(context) {

    private val searchBox: TextView
    private val backspaceButton: TextView
    private val keyboard = VerticalGridView(context)
    private val middleGrid = VerticalGridView(context)
    private val historyGrid = VerticalGridView(context)
    private val middleTitle = context.textView("热门搜索", 14f, ViewTheme.textSecondary)
    private val keyboardAdapter = TextGridAdapter(
        items = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".map { it.toString() },
        columns = 6,
        itemHeight = 36,
        cellWidthDp = 52,
        onClick = { ch -> onQueryChange(currentQuery + ch) },
    )
    private val middleAdapter = TextGridAdapter(
        items = emptyList(),
        columns = 1,
        itemHeight = 44,
        cellWidthDp = 220,
        onClick = { word -> if (word in selectableSuggestions) onPickSuggestion(word) },
    )
    private val historyAdapter = TextGridAdapter(
        items = emptyList(),
        columns = 2,
        itemHeight = 44,
        cellWidthDp = 150,
        onClick = { onPickSuggestion(it) },
    )
    private var currentQuery: String = ""
    private var selectableSuggestions: Set<String> = emptySet()
    private var deleteHeld = false
    private var lastDeleteAt = 0L

    init {
        orientation = HORIZONTAL
        setPadding(context.vDp(20), context.vDp(88), context.vDp(20), context.vDp(12))

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.40f)
        }
        addView(left)

        left.addView(
            context.textView("全局搜索", 15f, ViewTheme.textPrimary, Gravity.CENTER).apply {
                background = roundedBackground(0x0FFFFFFF, context.vDp(8))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.vDp(34)),
        )
        left.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(6)))

        searchBox = context.textView("输入要搜索的内容", 14f, ViewTheme.textTertiary).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(context.vDp(14), 0, context.vDp(14), 0)
            // 焦点态预建：普通态 1dp 淡金边，聚焦 2dp 强调色边（和原来视觉一致）
            background = focusStateBackground(
                normalColor = 0x0DFFFFFF,
                focusColor = 0x0DFFFFFF,
                radiusPx = context.vDp(8),
                focusStrokeWidthPx = context.vDp(2),
                focusStrokeColor = ViewTheme.accent,
                normalStrokeWidthPx = context.vDp(1),
                normalStrokeColor = 0x59FFD466,
            )
        }
        left.addView(
            searchBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.vDp(38)),
        )
        left.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(6)))

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val searchButton = actionButton("搜索", primary = true) { onSubmit() }
        val clearButton = actionButton("清空", primary = false) { onClear() }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(0, context.vDp(36), 1f))
        searchRow.addView(View(context), LinearLayout.LayoutParams(context.vDp(10), 1))
        searchRow.addView(clearButton, LinearLayout.LayoutParams(0, context.vDp(36), 1f))
        left.addView(searchRow)
        left.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(6)))

        // ★ 2026-11-07：删掉「远程搜索」按钮。
        //
        //   它从 Compose 版搬过来时就是个空实现（原 `onRemote = {}`），点了没有任何
        //   反应；焦点链里也从没接上过（搜索键的 DOWN 直接跳到键盘），用户永远选不到。
        //   一个既不能用、又看不出效果的按钮比没有更糟，所以直接去掉。
        //   只保留「删除」（退格），并把它正式接进焦点链。
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        backspaceButton = actionButton("删除", primary = false) { onDelete() }
        val deleteFocusStyle = backspaceButton.onFocusChangeListener
        backspaceButton.setOnFocusChangeListener { view, focused ->
            deleteFocusStyle?.onFocusChange(view, focused)
            if (!focused) { deleteHeld = false; lastDeleteAt = 0L }
        }
        actionRow.addView(
            backspaceButton,
            LinearLayout.LayoutParams(0, context.vDp(36), 1f),
        )
        left.addView(actionRow)
        left.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(6)))

        keyboard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        keyboard.setNumColumns(6)
        keyboard.setHorizontalSpacing(context.vDp(6))
        keyboard.setVerticalSpacing(context.vDp(5))
        keyboard.setPadding(0, 0, 0, 0)
        keyboard.clipToPadding = true
        keyboard.itemAnimator = null
        keyboard.adapter = keyboardAdapter
        keyboard.isFocusable = true
        left.addView(keyboard)

        searchBox.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN -> {
                    searchButton.requestFocus()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    onExitTop()
                    true
                }
                else -> false
            }
        }
        // 焦点链：搜索框 → 搜索/清空 → 删除 → 键盘
        // ★ 原来这两个 DOWN 都直接跳 keyboard，中间那一行永远选不到（2026-11-07 修复）
        searchButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                backspaceButton.requestFocus()
                true
            } else {
                false
            }
        }
        clearButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                backspaceButton.requestFocus()
                true
            } else {
                false
            }
        }
        backspaceButton.setOnKeyListener { _, keyCode, event ->
            val confirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            val now = android.os.SystemClock.uptimeMillis()
            when {
                confirm -> {
                    if (KeyRouting.searchDeleteStep(event.action == KeyEvent.ACTION_DOWN, true, deleteHeld, now, lastDeleteAt)) {
                        lastDeleteAt = now
                        onDelete()
                    }
                    deleteHeld = event.action == KeyEvent.ACTION_DOWN
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN -> {
                    keyboard.requestFocus()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    searchBox.requestFocus()
                    true
                }
                else -> false
            }
        }
        keyboard.setOnKeyInterceptListener { event ->
            val focused = keyboard.focusedChild
            val pos = focused?.let { keyboard.getChildAdapterPosition(it) } ?: -1
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && pos in 0..5) {
                        // 键盘正上方是「删除」，再上一行才是搜索框
                        backspaceButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && pos >= 0 && pos % 6 == 5) {
                        middleGrid.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && pos >= 0 && pos % 6 == 0) {
                        searchBox.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        addView(View(context), LinearLayout.LayoutParams(context.vDp(20), 1))

        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.18f)
        }
        addView(middle)
        middle.addView(
            middleTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.vDp(34),
            ),
        )
        middleGrid.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        middleGrid.setNumColumns(1)
        middleGrid.setVerticalSpacing(context.vDp(6))
        middleGrid.setPadding(0, 0, 0, 0)
        middleGrid.clipToPadding = true
        middleGrid.itemAnimator = null
        middleGrid.adapter = middleAdapter
        middleGrid.isFocusable = true
        middleGrid.setOnKeyInterceptListener { event ->
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        keyboard.requestFocus()
                        true
                    } else false
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        historyGrid.requestFocus()
                        true
                    } else false
                else -> false
            }
        }
        middle.addView(middleGrid)

        addView(View(context), LinearLayout.LayoutParams(context.vDp(32), 1))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 0.42f)
        }
        addView(right)
        val historyHead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyHead.addView(
            context.textView("搜索历史", 14f, ViewTheme.textSecondary),
            LinearLayout.LayoutParams(0, context.vDp(34), 1f),
        )
        historyHead.addView(
            actionButton("🗑", primary = false) { onClearHistory() },
            LinearLayout.LayoutParams(context.vDp(40), context.vDp(34)),
        )
        right.addView(historyHead)
        historyGrid.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        historyGrid.setNumColumns(2)
        historyGrid.setColumnWidth(context.vDp(150))
        historyGrid.setHorizontalSpacing(context.vDp(10))
        historyGrid.setVerticalSpacing(context.vDp(8))
        historyGrid.setPadding(0, 0, 0, 0)
        historyGrid.clipToPadding = true
        historyGrid.itemAnimator = null
        historyGrid.adapter = historyAdapter
        historyGrid.isFocusable = true
        // ★ 历史区是 2 列，左右两列都必须能走。
        //
        //   原来的写法无条件拦截 LEFT 并跳到中间栏 —— 结果在**右列**按 LEFT 也会被
        //   拦走，"右列 → 左列"这一步永远做不到，观感就是"历史分成左右两块，
        //   焦点只能停在右边"（2026-11-07 用户反馈）。
        //   现在只在第一列时才交给中间栏，其余情况放行给 Leanback 走列内移动。
        historyGrid.setOnKeyInterceptListener { event ->
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                val focused = historyGrid.focusedChild
                val pos = focused?.let { historyGrid.getChildAdapterPosition(it) } ?: -1
                // Leanback 的 VerticalGridView 没有 getNumColumns()，
                // 这里的 2 必须和本文件里 setNumColumns(2) 保持一致。
                val cols = 2
                if (pos < 0 || pos % cols == 0) {
                    middleGrid.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        right.addView(historyGrid)
    }

    fun render(
        query: String,
        suggests: List<String>,
        hotWords: List<String>,
        hotLoading: Boolean,
        suggestLoading: Boolean,
        history: List<String>,
    ) {
        currentQuery = query
        searchBox.text = query.ifBlank { "输入要搜索的内容" }
        searchBox.setTextColor(if (query.isBlank()) ViewTheme.textTertiary else ViewTheme.textPrimary)
        searchBox.contentDescription = query
        val middleItems = if (query.isBlank()) hotWords else suggests
        selectableSuggestions = middleItems.toSet()
        middleTitle.text = if (query.isBlank()) "热门搜索" else "猜你想搜"
        if (middleItems.isEmpty() && (hotLoading || suggestLoading)) {
            middleAdapter.submit(listOf(if (query.isBlank()) "正在加载热门搜索…" else "正在联想…"))
        } else if (middleItems.isEmpty()) {
            middleAdapter.submit(
                listOf(if (query.isBlank()) "暂无热门搜索" else "没有匹配的联想词")
            )
        } else {
            middleAdapter.submit(middleItems)
        }
        historyAdapter.submit(history)
    }

    fun focusQuery(): Boolean = searchBox.requestFocus()
    fun focusDelete(): Boolean = backspaceButton.requestFocus()

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        context.textView(
            text = label,
            sizeSp = 14f,
            color = if (primary) Color.BLACK else ViewTheme.textPrimary,
            gravity = Gravity.CENTER,
        ).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            // 焦点态预建：主按钮 白→强调色，次按钮 暗→更亮。
            // 文字色和焦点无关（创建时已由 textView(color=...) 定好），不需要监听。
            background = focusStateBackground(
                normalColor = if (primary) Color.WHITE else 0x14FFFFFF,
                focusColor = if (primary) ViewTheme.accent else 0x2EFFFFFF,
                radiusPx = context.vDp(8),
            )
            setOnClickListener { onClick() }
        }
}

private class TextGridAdapter(
    items: List<String>,
    private val columns: Int,
    private val itemHeight: Int,
    private val cellWidthDp: Int,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<TextGridAdapter.Holder>() {

    private val items = items.toMutableList()

    inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    fun submit(next: List<String>) {
        if (items == next) return
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = TextView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            minimumHeight = parent.context.vDp(itemHeight)
            minimumWidth = parent.context.vDp(cellWidthDp)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (columns == 6) 16f else 13f)
            setTextColor(ViewTheme.textPrimary)
            background = roundedBackground(0x12FFFFFF, parent.context.vDp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(parent.context.vDp(10), 0, parent.context.vDp(10), 0)
        }
        val holder = Holder(view)
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        // 背景交给预建的 StateListDrawable 自动切；监听里只保留"文字色随焦点变"
        view.background = focusStateBackground(
            0x12FFFFFF, 0x33FFFFFF, view.context.vDp(8), view.context.vDp(2), ViewTheme.focusRing,
        )
        view.setOnFocusChangeListener { v, focused ->
            (v as TextView).setTextColor(if (focused) Color.WHITE else ViewTheme.textPrimary)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position]
    }
}
