package com.reelix.app.ui.component

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ViewHolderTask
import androidx.recyclerview.widget.RecyclerView
import com.reelix.app.data.settings.restoredPosterPosition
import com.reelix.app.ui.theme.layout.HistoryArrangement
import com.reelix.app.ui.theme.layout.HistoryPresentation

internal class HomeHistoryView(
    context: Context,
    onClick: (PosterTile) -> Unit,
    onLongClick: ((PosterTile) -> Unit)?,
    private val onExitUp: () -> Unit,
    private val onFocusedIndexChange: (Int) -> Unit,
    private val memory: PosterGridMemory,
    private val onExitDown: (() -> Unit)? = null,
    private val canExitUp: () -> Boolean = { true },
    initialColumns: Int = 6,
) : FrameLayout(context) {
    private val vertical = PosterGridView(context, columns = initialColumns, onClick = onClick,
        onLongClick = onLongClick, onExitUp = onExitUp, onFocusedIndexChange = onFocusedIndexChange, memory = memory)
    private var horizontal: HorizontalGridView? = null
    private var horizontalAdapter: PosterTileAdapter? = null
    private var tiles = emptyList<PosterTile>()
    private var presentation: HistoryPresentation? = null
    private var referenceColumns = initialColumns
    private var cardWidth = 1
    private var imageHeight = 1
    private var focusTicket = 0L
    private var waitingFocus = false

    /**
     * 是否允许内部网格接管焦点。
     *
     * ★ 2026-11-09：Leanback 的 `VerticalGridView` 在布局完成后会**自己**把焦点
     *   给第一个子项——不是我们代码调的，`requestFocus` 拦不住（`hadFocus=false`、
     *   `pendingFocus=false` 时焦点照样跳）。实测冷启动 0.3 秒后焦点就从导航栏
     *   「首页」跑到了历史第一张海报。
     *
     *   唯一可靠的办法是把 `descendantFocusability` 设为 BLOCK，让子项在系统层面
     *   不可聚焦。等用户真的按「下」进入内容区（`HomeView.focusFirst()`）再放开。
     */
    var autoFocusEnabled: Boolean = true
        set(value) {
            field = value
            val mode = if (value) {
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            } else {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            vertical.descendantFocusability = mode
            horizontal?.descendantFocusability = mode
            if (!value) vertical.clearFocus()
        }
    private var submission = 0L
    private var palette: com.reelix.app.ui.theme.layout.NativeThemePalette? = null
    private val click = onClick
    private val longClick = onLongClick

    init { addView(vertical, LayoutParams(-1, -1)) }

    fun setPalette(palette: com.reelix.app.ui.theme.layout.NativeThemePalette) {
        this.palette = palette
        vertical.setThemePalette(palette)
        vertical.grid.setBackgroundColor(palette.background)
        horizontalAdapter?.palette = palette
        horizontalAdapter?.notifyDataSetChanged()
    }

    fun setPosterSize(columns: Int) {
        if (referenceColumns == columns) return
        referenceColumns = columns
        vertical.setPosterSize(columns)
        presentation?.let(::applyGeometry)
    }

    fun configure(next: HistoryPresentation) {
        if (presentation == next) return
        presentation = next
        val useRail = next.arrangement != HistoryArrangement.POSTER_GRID
        if (useRail && horizontal == null) {
            val rail = HorizontalGridView(context).apply {
                setNumRows(1)
                itemAnimator = null
                clipToPadding = true
                setPadding(context.vDp(2), context.vDp(2), context.vDp(2), context.vDp(2))
                setHasFixedSize(true)
                isFocusable = true
                isFocusableInTouchMode = true
            }
            horizontal = rail
            val adapter = PosterTileAdapter(context, Int.MAX_VALUE, click, longClick,
                onExitLeft = { exitRail() }, onFocused = { position ->
                    memory.index = position
                    memory.key = tiles.getOrNull(position)?.key
                    memory.entered = true
                    onFocusedIndexChange(position)
                }, cellWidth = { cardWidth }, imageHeight = { imageHeight })
            horizontalAdapter = adapter
            adapter.palette = palette
            rail.adapter = adapter
            rail.setOnKeyInterceptListener { event ->
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) exitRail() else onExitDown?.invoke()
                    }
                    true
                } else {
                    if (event.action == KeyEvent.ACTION_DOWN) { focusTicket++; waitingFocus = false }
                    false
                }
            }
            addView(rail, LayoutParams(-1, -1))
        }
        vertical.visibility = if (useRail) GONE else VISIBLE
        horizontal?.visibility = if (useRail) VISIBLE else GONE
        memory.layout = null
        applyGeometry(next)
        if (tiles.isNotEmpty()) submit(tiles)
    }

    private fun applyGeometry(next: HistoryPresentation) {
        val scale = 6f / referenceColumns.coerceIn(4, 8)
        val style = next.cardStyle?.let { if (next.posterSizeApplied) it else it.forPosterColumns(referenceColumns) }
        cardWidth = style?.imageWidthPx ?: context.vDp((next.cardWidthDp * scale).toInt().coerceAtLeast(48))
        val fullHeight = style?.height(resources.configuration.fontScale) ?: context.vDp((next.cardHeightDp * scale).toInt().coerceAtLeast(56))
        imageHeight = style?.imageHeightPx ?: (fullHeight - context.vDp(30)).coerceAtLeast(1)
        setPadding(0, 0, 0, 0)
        clipChildren = true
        if (next.arrangement == HistoryArrangement.POSTER_GRID) {
            val available = width.takeIf { it > 0 } ?: context.vDp(next.preferredColumns * next.cardWidthDp +
                (next.preferredColumns - 1) * next.gapDp)
            val columns = ((available + context.vDp(next.gapDp)) / (cardWidth + context.vDp(next.gapDp))).coerceAtLeast(1)
            vertical.configureTheme(columns, cardWidth, context.vDp(next.gapDp))
        } else {
            horizontalAdapter?.cardStyle = style
            val cellHeight = style?.cellHeightPx(resources.configuration.fontScale) ?: fullHeight + context.vDp(4)
            horizontal?.setRowHeight(cellHeight)
            horizontal?.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, cellHeight)
            if (style != null) {
                horizontal?.setPadding(0, 0, 0, 0)
                horizontal?.clipChildren = true
                horizontal?.clipToPadding = true
            }
            horizontal?.setHorizontalSpacing(style?.cellGapPx ?: context.vDp(next.gapDp))
            horizontalAdapter?.notifyDataSetChanged()
        }
    }

    fun submit(next: List<PosterTile>, onCommitted: () -> Unit = {}) {
        tiles = next
        val adapter = horizontalAdapter
        if (horizontal?.visibility != VISIBLE || adapter == null) {
            vertical.submit(next, onCommitted)
            return
        }
        val request = ++submission
        val shown = next.map { tile ->
            if (presentation?.arrangement == HistoryArrangement.WIDE_RAIL && !tile.backdropUrl.isNullOrBlank())
                tile.copy(posterUrl = tile.backdropUrl, posterRes = null) else tile
        }
        adapter.submitList(shown) {
            if (request != submission) return@submitList
            if (shown.isNotEmpty()) {
                val target = restoredPosterPosition(shown.map { it.key }, memory.key, memory.index)
                horizontal?.setSelectedPosition(target)
                if (waitingFocus) focusPosition(target)
            }
            onCommitted()
        }
    }

    fun currentPosition(): Int {
        val rail = horizontal?.takeIf { it.visibility == VISIBLE } ?: return vertical.currentPosition()
        return rail.focusedChild?.let { rail.getChildAdapterPosition(it) }?.takeIf { it != RecyclerView.NO_POSITION }
            ?: rail.selectedPosition.coerceAtLeast(0)
    }

    fun savePosition() {
        if (horizontal?.visibility != VISIBLE) { vertical.savePosition(); return }
        val position = currentPosition().coerceIn(0, (tiles.size - 1).coerceAtLeast(0))
        memory.index = position
        memory.key = tiles.getOrNull(position)?.key
        memory.keys = tiles.map { it.key }
    }

    fun restoreFocus(index: Int) {
        if (horizontal?.visibility != VISIBLE) { vertical.restoreFocus(index); return }
        focusPosition(restoredPosterPosition(tiles.map { it.key }, memory.key, index))
    }

    fun focusPosition(index: Int) {
        val rail = horizontal?.takeIf { it.visibility == VISIBLE } ?: run { vertical.focusPosition(index); return }
        if (tiles.isEmpty()) { waitingFocus = true; memory.index = index; return }
        waitingFocus = true
        val ticket = ++focusTicket
        val target = index.coerceIn(tiles.indices)
        val key = tiles[target].key
        rail.setSelectedPosition(target, object : ViewHolderTask {
            override fun run(holder: RecyclerView.ViewHolder) {
                if (ticket == focusTicket && isAttachedToWindow && tiles.getOrNull(target)?.key == key) {
                    holder.itemView.requestFocus()
                    waitingFocus = false
                }
            }
        })
    }

    private fun exitRail() {
        val rail = horizontal ?: return
        if (!canExitUp()) return
        cancelFocusRequest()
        rail.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        rail.isFocusable = false
        rail.clearFocus()
        onExitUp()
        rail.post {
            if (isAttachedToWindow) {
                rail.isFocusable = true
                rail.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }

    fun cancelFocusRequest() {
        focusTicket++
        waitingFocus = false
        vertical.grid.tag = null
    }

    override fun onDetachedFromWindow() {
        savePosition()
        cancelFocusRequest()
        super.onDetachedFromWindow()
    }
}
