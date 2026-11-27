package com.reelix.app.ui.screen.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.reelix.app.ui.component.KeyRouting
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp
import com.reelix.app.R

/** A rail keeps its focused child attached while Leanback scrolls and recycles neighbours. */
class PlayerOptionsView(context: Context) : ScrollView(context) {
    var panelScale: PlayerPanelScale = PlayerPanelScale(PlayerPanelSize.STANDARD)
        set(value) { if (field != value) { field = value; invalidate() } }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val groups = mutableListOf<Group>()
    private var items: List<PlayerMenuItem> = emptyList()
    private var opened = false
    private var groupIndex = 0
    private var onPages = false
    private var skipInfo = SkipInfo()
    private var editing: SkipField? = null
    private var onSkipChange: (SkipInfo) -> Unit = {}
    private var onEditingDone: () -> Unit = {}
    private var onClose: () -> Unit = {}
    private var playbackPosition = 0L
    private val focusRequests = PlayerPanelFocusRequests()
    private var keyOwnership = PlayerPanelKeyOwnership()
    private var focusLayoutView: View? = null
    private var focusLayoutListener: View.OnLayoutChangeListener? = null

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        setPadding(context.vDp(58), context.vDp(20), context.vDp(58), context.vDp(24))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    internal fun render(data: List<PlayerMenuItem>, open: Boolean, skip: SkipInfo, edit: SkipField?,
        change: (SkipInfo) -> Unit, done: () -> Unit, close: () -> Unit, positionMs: Long,
        keys: PlayerPanelKeyOwnership) {
        val dataChanged = items !== data
        val opening = open && !opened
        val hadFocus = hasFocus()
        val headerFocused = groups.getOrNull(groupIndex)?.actionButton?.hasFocus() == true
        val oldGroupIndex = groupIndex
        val oldGroupKey = groups.getOrNull(groupIndex)?.key
        val oldGroupKeys = groups.map { it.key }
        val structureChanged = groups.map { it.key } != data.map { it.key }
        keyOwnership = keys
        if (dataChanged || open != opened) {
            cancelLayoutFocus()
            focusRequests.update(open)
        }
        opened = open
        if (!open || structureChanged) {
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            clearFocus()
        }
        if (!open) keyOwnership.cancelConfirm()
        visibility = if (open) VISIBLE else GONE
        items = data
        skipInfo = skip; editing = edit
        onSkipChange = change; onEditingDone = done; onClose = close
        playbackPosition = positionMs
        if (structureChanged) {
            val retained = groups.associateBy { it.key }
            groups.clear(); column.removeAllViews()
            data.forEach { item ->
                (retained[item.key] ?: Group(item.key)).also { groups += it; column.addView(it.container) }
            }
        }
        groupIndex = if (opening) data.indexOfFirst { it.options.isNotEmpty() }.coerceAtLeast(0)
            else restorePlayerOptionIndex(data.map { it.key }, oldGroupKey, oldGroupIndex, previousKeys = oldGroupKeys)
        if (open && (groups.isEmpty() || groupIndex < 0)) { closePanel(); return }
        data.forEachIndexed { index, item -> groups[index].bind(item) }
        if (open) descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        if (opening) {
            onPages = false
            groups.forEachIndexed { index, group -> group.position = selected(data[index]) }
            activate(focus = true)
        } else if (dataChanged || structureChanged) {
            if ((groups.getOrNull(groupIndex)?.options?.itemCount ?: 0) <= PAGE_SIZE) onPages = false
            activate(focus = open && hadFocus, header = headerFocused && oldGroupKey == groups.getOrNull(groupIndex)?.key)
        }
    }

    private fun selected(item: PlayerMenuItem) =
        if (item.options.isEmpty()) -1 else item.options.indexOfFirst { it.selected }.coerceAtLeast(0)

    private fun activate(focus: Boolean, header: Boolean = false) {
        cancelLayoutFocus()
        val ticket = focusRequests.issue()
        if (!opened) return
        // Keep the panel as owner while the newly expanded rail waits for layout.
        if (focus && !hasFocus()) requestFocus()
        if (groups.isEmpty()) return
        groupIndex = groupIndex.coerceIn(groups.indices)
        groups.forEachIndexed { i, g ->
            val active = i == groupIndex
            g.label.setTextColor(if (active) Color.WHITE else 0x88FFFFFF.toInt())
            g.rail.visibility = if (active) VISIBLE else GONE
            g.pages.visibility = if (active && g.options.itemCount > PAGE_SIZE) VISIBLE else GONE
            g.hint.visibility = if (active && g.key == "skip") VISIBLE else GONE
        }
        if (!focus) return
        val group = groups[groupIndex]
        if ((header || group.position < 0) && group.actionButton.visibility == VISIBLE) {
            group.actionButton.requestFocus()
            return
        }
        if (group.position < 0) { closePanel(); return }
        val rail = if (onPages) group.pages else group.rail
        fun focusRail() {
            if (focusRequests.allows(ticket) && isAttachedToWindow && isShown &&
                groups.getOrNull(groupIndex) === group && rail.visibility == VISIBLE) {
                val position = if (onPages) group.position / PAGE_SIZE else group.position
                if (group.position < 0) return
                rail.setSelectedPosition(position)
                rail.requestFocus()
                // Scroll only on group changes, never on each horizontal repeat.
                val bottom = group.container.bottom + paddingBottom
                val target = (bottom - height).coerceAtLeast(0)
                smoothScrollTo(0, target)
            }
        }
        if (rail.isLaidOut && !rail.isLayoutRequested) focusRail() else {
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    view.removeOnLayoutChangeListener(this)
                    if (focusLayoutListener === this) {
                        focusLayoutView = null
                        focusLayoutListener = null
                    }
                    focusRail()
                }
            }
            focusLayoutView = rail
            focusLayoutListener = listener
            rail.addOnLayoutChangeListener(listener)
        }
    }

    private fun cancelLayoutFocus() {
        focusLayoutListener?.let { focusLayoutView?.removeOnLayoutChangeListener(it) }
        focusLayoutView = null
        focusLayoutListener = null
    }

    private fun closePanel() {
        cancelLayoutFocus()
        opened = false
        focusRequests.update(false)
        keyOwnership.cancelConfirm()
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clearFocus()
        onClose()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (opened) {
            focusRequests.update(true)
            activate(focus = true)
        }
    }

    override fun onDetachedFromWindow() {
        cancelLayoutFocus()
        focusRequests.update(false)
        keyOwnership.cancelConfirm()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        if (!opened) return keyOwnership.consume(code, down, event.downTime)
        val confirm = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm && editing == null) {
            if (down && event.repeatCount == 0 && !keyOwnership.owns(code, event.downTime)) {
                keyOwnership.onDown(code, event.downTime, confirmTarget())
            }
            val action = KeyRouting.choicePopupKey(down, confirm = true,
                confirmOwned = keyOwnership.owns(code, event.downTime))
            if (!down) {
                val target = keyOwnership.releaseConfirm(code, event.downTime, confirmTarget(), event.isCanceled)
                if (action == KeyRouting.ChoiceAction.APPLY && target != null) clickTarget(target)
            }
            return true
        }
        if (!down && keyOwnership.consume(code, false, event.downTime)) return true
        val action = KeyRouting.panelInput(event.action == KeyEvent.ACTION_DOWN,
            code == KeyEvent.KEYCODE_DPAD_UP, code == KeyEvent.KEYCODE_DPAD_DOWN,
            confirm,
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE,
            editing != null, event.repeatCount)
        if (down && action != KeyRouting.PanelAction.PASS) keyOwnership.onDown(code, event.downTime)
        when (action) {
            KeyRouting.PanelAction.PASS -> return super.dispatchKeyEvent(event)
            KeyRouting.PanelAction.CONSUME -> Unit
            KeyRouting.PanelAction.CLOSE -> closePanel()
            KeyRouting.PanelAction.FINISH_EDIT -> { editing = null; onEditingDone() }
            KeyRouting.PanelAction.INCREASE_SKIP, KeyRouting.PanelAction.DECREASE_SKIP -> {
                val delta = KeyRouting.skipStepFor(event.eventTime - event.downTime) *
                    if (action == KeyRouting.PanelAction.INCREASE_SKIP) 1 else -1
                skipInfo = when (editing) {
                    SkipField.INTRO -> skipInfo.copy(introEndMs = (skipInfo.introEndMs + delta).coerceAtLeast(0))
                    SkipField.OUTRO -> skipInfo.copy(outroStartMs = (skipInfo.outroStartMs + delta).coerceAtLeast(0))
                    null -> skipInfo
                }
                onSkipChange(skipInfo)
            }
            KeyRouting.PanelAction.UP, KeyRouting.PanelAction.DOWN -> {
                val group = groups.getOrNull(groupIndex) ?: return true
                when (KeyRouting.panelHeaderInput(action == KeyRouting.PanelAction.UP,
                    action == KeyRouting.PanelAction.DOWN, group.actionButton.hasFocus(), onPages,
                    group.actionButton.visibility == VISIBLE, group.options.itemCount > PAGE_SIZE)) {
                    KeyRouting.PanelHeaderAction.HEADER -> {
                        cancelLayoutFocus()
                        focusRequests.issue()
                        group.actionButton.requestFocus()
                        return true
                    }
                    KeyRouting.PanelHeaderAction.OPTIONS -> { onPages = false; activate(true); return true }
                    KeyRouting.PanelHeaderAction.PAGES -> { onPages = true; activate(true); return true }
                    KeyRouting.PanelHeaderAction.CLOSE -> { closePanel(); return true }
                    KeyRouting.PanelHeaderAction.PASS -> Unit
                }
                val next = KeyRouting.panelVertical(groupIndex, onPages,
                    action == KeyRouting.PanelAction.UP, groups.map { it.options.itemCount > PAGE_SIZE })
                if (next == null) closePanel() else {
                    groupIndex = next.first; onPages = next.second; activate(true)
                }
            }
        }
        return true
    }

    private fun confirmTarget(): PlayerPanelConfirmTarget? {
        val group = groups.getOrNull(groupIndex) ?: return null
        if (group.actionButton.hasFocus()) return PlayerPanelConfirmTarget(group.key, "header", group.key)
        val pages = group.pages.hasFocus()
        val rail = if (pages) group.pages else group.rail
        if (!rail.hasFocus()) return null
        val focused = rail.findFocus() ?: return null
        val position = rail.findContainingViewHolder(focused)?.bindingAdapterPosition ?: return null
        val key = (if (pages) group.pageOptions else group.options).keyAt(position) ?: return null
        return PlayerPanelConfirmTarget(group.key, if (pages) "pages" else "options", key)
    }

    private fun clickTarget(target: PlayerPanelConfirmTarget) {
        val group = groups.firstOrNull { it.key == target.group } ?: return
        if (target.area == "header") {
            group.actionButton.performClick()
        } else {
            val pages = target.area == "pages"
            val adapter = if (pages) group.pageOptions else group.options
            val rail = if (pages) group.pages else group.rail
            val position = adapter.indexOfKey(target.resource)
            if (position >= 0) rail.findViewHolderForAdapterPosition(position)?.itemView?.performClick()
        }
    }

    private inner class Group(val key: String) {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val label = context.textView(sizeSp = panelScale.factor * 24f, color = Color.WHITE)
        val actionButton = context.textView(sizeSp = panelScale.factor * 15f, color = Color.WHITE, gravity = Gravity.CENTER).apply {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            setPadding((16 * panelScale.factor).toInt(), 0, (16 * panelScale.factor).toInt(), 0)
            setOnClickListener { if (opened) items.firstOrNull { it.key == key }?.onAction?.invoke() }
            setOnKeyListener { _, code, _ ->
                KeyRouting.panelBoundary(code == KeyEvent.KEYCODE_DPAD_LEFT,
                    code == KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1)
            }
            setOnFocusChangeListener { _, focused ->
                if (!focused) keyOwnership.cancelConfirm()
                // 背景交给下面的 StateListDrawable 自动切，这里只改文字色
                setTextColor(if (focused) Color.BLACK else Color.WHITE)
            }
            // 焦点态预建：普通深灰 → 聚焦白底（避免 setBackground 触发 requestLayout）
            background = focusStateBackground(
                0xFF494A4C.toInt(), Color.WHITE, context.vDp(5),
            )
        }
        val hint = context.textView(sizeSp = panelScale.factor * 12f, color = 0xBBFFFFFF.toInt())
        // 选集轨道下方的“当前文件”行；只在选集组显示。
        // 选集卡仍保留 1/2/3 的短标签，避免卡片过宽，完整文件名在这里补足。
        val fileName = context.textView(sizeSp = panelScale.factor * 12f, color = 0xBBFFFFFF.toInt()).apply {
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((4 * panelScale.factor).toInt(), 0, (4 * panelScale.factor).toInt(), 0)
            visibility = GONE
        }
        val railHeight = (47 * panelScale.factor).toInt().coerceAtLeast(PlayerPanelScale.MIN_TOUCH_DP.toInt())
        val pagesHeight = (32 * panelScale.factor).toInt().coerceAtLeast(PlayerPanelScale.MIN_TOUCH_DP.toInt())
        val rail = makeRail(railHeight)
        val pages = makeRail(pagesHeight)
        var position = 0
        val options = OptionsAdapter(key == "episodes", false, onFocused = { index ->
            updateFileName(index)
        }) { index ->
            if (opened) {
                if (key == "skip" && index in 0..1) editing = if (index == 0) SkipField.INTRO else SkipField.OUTRO
                items.firstOrNull { it.key == key }?.options?.getOrNull(index)?.onClick?.invoke()
            }
        }
        val pageOptions = OptionsAdapter(false, true) { page ->
            if (opened) {
                position = (page * PAGE_SIZE).coerceAtMost(options.itemCount - 1)
                onPages = false; activate(true)
            }
        }
        private var lastModel: PlayerMenuItem? = null

        init {
            val heading = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, -1, 1f))
                addView(actionButton, LinearLayout.LayoutParams(-2, context.vDp(36)))
            }
            container.addView(heading, LinearLayout.LayoutParams(-1, context.vDp((50 * panelScale.factor).toInt().coerceAtLeast(PlayerPanelScale.MIN_TOUCH_DP.toInt()))))
            container.addView(hint, LinearLayout.LayoutParams(-1, context.vDp((28 * panelScale.factor).toInt())))
            container.addView(pages, LinearLayout.LayoutParams(-1, context.vDp(pagesHeight)).apply { bottomMargin = context.vDp((10 * panelScale.factor).toInt()) })
            container.addView(rail, LinearLayout.LayoutParams(-1, context.vDp(railHeight)).apply { bottomMargin = context.vDp((10 * panelScale.factor).toInt()) })
            container.addView(fileName, LinearLayout.LayoutParams(-1, context.vDp((24 * panelScale.factor).toInt())).apply { bottomMargin = context.vDp((4 * panelScale.factor).toInt()) })
            rail.adapter = options; pages.adapter = pageOptions
            rail.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(parent: RecyclerView?, child: RecyclerView.ViewHolder?, pos: Int, subposition: Int) {
                    if (pos < 0) return
                    position = pos
                    updateFileName(pos)
                    if (rail.hasFocus()) {
                        onPages = false
                        pageOptions.highlight(pos / PAGE_SIZE)
                    }
                }
            })
            pages.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(parent: RecyclerView?, child: RecyclerView.ViewHolder?, pos: Int, subposition: Int) {
                    if (pos < 0 || !pages.hasFocus()) return
                    onPages = true
                    if (position / PAGE_SIZE != pos) {
                        position = (pos * PAGE_SIZE).coerceAtMost(options.itemCount - 1)
                        updateFileName(position)
                    }
                    // The content rail retains all episodes; page selection only moves its window.
                    rail.setSelectedPosition(position)
                    pageOptions.highlight(pos)
                }
            })
        }

        fun bind(item: PlayerMenuItem) {
            if (key == "skip") {
                val message = context.getString(
                if (editing != null) R.string.player_skip_editing_hint else R.string.player_skip_hint,
                formatDuration(playbackPosition))
                if (hint.text.toString() != message) hint.text = message
            }
            if (lastModel === item) return
            val previousKeys = lastModel?.options?.map { it.key }.orEmpty()
            val focusedKey = previousKeys.getOrNull(position)
            lastModel = item
            label.text = item.label
            actionButton.visibility = if (item.onAction != null) VISIBLE else GONE
            actionButton.text = item.actionLabel.orEmpty()
            val newPosition = restorePlayerOptionIndex(item.options.map { it.key }, focusedKey, position, selected(item), previousKeys)
            options.submit(item.options.map { Cell(it.key, it.label, it.selected, it.shortLabel) })
            position = newPosition
            updateFileName(newPosition)
            if (position >= 0) rail.setSelectedPosition(position)
            val count = (item.options.size + PAGE_SIZE - 1) / PAGE_SIZE
            pageOptions.submit((0 until count).map { page ->
                val first = item.options[page * PAGE_SIZE].shortLabel ?: (page * PAGE_SIZE + 1).toString()
                val last = item.options[((page + 1) * PAGE_SIZE).coerceAtMost(item.options.size) - 1].shortLabel
                    ?: ((page + 1) * PAGE_SIZE).coerceAtMost(item.options.size).toString()
                Cell("page:$page", "$first–$last", page == position / PAGE_SIZE)
            })
        }

        private fun updateFileName(index: Int?) {
            if (key != "episodes") {
                fileName.visibility = GONE
                return
            }
            val item = items.firstOrNull { it.key == key } ?: return
            val shown = item.options.getOrNull(index ?: position)?.label.orEmpty()
            fileName.visibility = if (shown.isBlank()) GONE else VISIBLE
            fileName.text = context.getString(R.string.player_current_file, shown)
        }
    }

    private fun makeRail(height: Int) = HorizontalGridView(context).apply {
        setNumRows(1)
        setHorizontalSpacing(context.vDp(7))
        setPadding(0, 0, 0, 0)
        setHasFixedSize(true)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        setItemViewCacheSize(18)
        layoutParams = LinearLayout.LayoutParams(-1, context.vDp(height))
    }

    private data class Cell(val key: String, val label: String, val selected: Boolean, val shortLabel: String? = null)
    private inner class OptionsAdapter(val episodes: Boolean, val page: Boolean,
        val onFocused: ((Int) -> Unit)? = null,
        val click: (Int) -> Unit) : RecyclerView.Adapter<OptionsAdapter.Holder>() {
        private var cells = emptyList<Cell>()
        var focusedIndex: Int = -1
            private set
        init { setHasStableIds(true) }
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        override fun getItemCount() = cells.size
        fun keyAt(position: Int): String? = cells.getOrNull(position)?.key
        fun indexOfKey(key: String): Int = cells.indexOfFirst { it.key == key }
        override fun getItemId(position: Int) = cells[position].key.fold(1125899906842597L) { hash, c -> hash * 31 + c.code }
        fun submit(next: List<Cell>) {
            if (cells == next) return
            val old = cells
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a].key == next[b].key
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
            })
            cells = next
            focusedIndex = focusedIndex.coerceIn(0, (next.size - 1).coerceAtLeast(0))
            diff.dispatchUpdatesTo(this)
        }
        fun highlight(position: Int) = submit(cells.mapIndexed { i, cell -> cell.copy(selected = i == position) })
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val text = context.textView(sizeSp = (if (page) 15f else 16f) * panelScale.factor, color = Color.WHITE, gravity = Gravity.CENTER).apply {
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setSingleLine(true)
                val horizontal = (if (episodes) 2 else 14) * panelScale.factor
                setPadding(context.vDp(horizontal.toInt()), 0, context.vDp(horizontal.toInt()), 0)
                // Leanback requires its own GridLayoutManager.LayoutParams (not base RecyclerView params).
                layoutParams = (parent as RecyclerView).layoutManager!!.generateDefaultLayoutParams().apply {
                    width = if (episodes) context.vDp((49 * panelScale.factor).toInt().coerceAtLeast(PlayerPanelScale.MIN_TOUCH_DP.toInt())) else -2
                    height = -1
                }
            }
            val holder = Holder(text)
            text.setOnFocusChangeListener { _, focused ->
                if (!focused) keyOwnership.cancelConfirm()
                if (focused) {
                    val position = holder.absoluteAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        focusedIndex = position
                        onFocused?.invoke(position)
                    }
                }
                style(holder)
            }
            text.setOnClickListener { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(click) }
            text.setOnKeyListener { _, code, _ ->
                KeyRouting.panelBoundary(code == KeyEvent.KEYCODE_DPAD_LEFT, code == KeyEvent.KEYCODE_DPAD_RIGHT,
                    holder.bindingAdapterPosition, itemCount)
            }
            return holder
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val cell = cells[position]
            holder.text.text = if (episodes) cell.shortLabel ?: cell.label else (if (cell.selected && !page) "✓ " else "") + cell.label
            holder.text.contentDescription = cell.label
            holder.text.isSelected = cell.selected
            style(holder)
        }
        private fun style(holder: Holder) {
            val text = holder.text
            val focused = text.hasFocus()
            text.setTextColor(if (focused) Color.BLACK else if (text.isSelected) 0xFF91ACFF.toInt() else Color.WHITE)
            text.background = roundedBackground(if (focused) Color.WHITE else if (page) 0x22FFFFFF else 0xFF494A4C.toInt(), context.vDp(5))
        }
    }

    companion object { private const val PAGE_SIZE = 15 }
}
