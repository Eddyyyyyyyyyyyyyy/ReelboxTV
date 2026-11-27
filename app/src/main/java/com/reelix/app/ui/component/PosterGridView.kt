package com.reelix.app.ui.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.reelix.app.data.settings.posterGeometry
import com.reelix.app.data.settings.restoredPosterPosition

/** Navigation-session state, owned above disposable page Views. */
class PosterGridMemory {
    val regions = mutableMapOf<String, PosterGridMemory>()
    var expanded = false
    var key: String? = null
    var index: Int = 0
    var section: String = ""
    var entered: Boolean = false
    internal var keys: List<String> = emptyList()
    internal var layout: android.os.Parcelable? = null
}

/**
 * 通用 View 海报墙。
 *
 * 首页历史、片库、搜索结果共用这一套：VerticalGridView + 通用卡片 + 边界键 +
 * 长按步进闸。页面只负责把业务对象映射成 [PosterTile]。
 */
class PosterGridView(
    context: Context,
    columns: Int = 6,
    private val onClick: (PosterTile) -> Unit,
    private val onLongClick: ((PosterTile) -> Unit)? = null,
    private val onExitUp: (() -> Unit)? = null,
    private val onExitLeft: (() -> Unit)? = null,
    private val onFocusedIndexChange: (Int) -> Unit = {},
    memory: PosterGridMemory = PosterGridMemory(),
    private val minimumColumns: Int = 1,
) : FrameLayout(context) {
    private var memory = memory
    private var restoreViewport = true
    private var pendingFocus = false
    private var submission = 0

    /**
     * 是否允许网格在数据提交/重排后**主动**抢焦点。
     *
     * ★ 2026-11-09：冷启动时 MainActivity 会把焦点放到顶部导航「首页」，
     *   但 Leanback 的 `setSelectedPosition()` 会留下一个"待聚焦"状态，
     *   布局完成后自己把焦点拽回第一张贴——实测 0.3 秒后焦点就跳走了。
     *
     *   这里默认 **false**：网格不主动抢。等用户真的按「下」进入内容区
     *   （`HomeView.focusFirst()`）时才置 true，之后恢复正常行为。
     */
    var autoFocusEnabled = false

    var columns: Int = columns
        private set
    private var referenceColumns = 6
    private var themeGeometry: com.reelix.app.data.settings.PosterGeometry? = null

    private val sharedFocusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sharedFrame = RectF()
    private var sharedFrameRadius = 0f
    private var sharedFrameVisible = false
    private var sharedFrameCard: PosterTileView? = null
    private val sharedFrameRect = Rect()
    private val cardLocation = IntArray(2)
    private val hostLocation = IntArray(2)
    private var sharedFrameListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    /** 上一次真正画出去的焦点框（用于只重绘新旧两块区域）。 */
    private val paintedFrame = Rect()
    private var paintedFrameValid = false
    private val dirtyFrame = Rect()

    private val host = Host()
    private val controller = TileGridController()

    val grid: VerticalGridView = VerticalGridView(context).apply {
        setNumColumns(columns)
        setHorizontalSpacing(context.vDp(10))
        setVerticalSpacing(context.vDp(16))
        setPadding(context.vDp(28), 0, context.vDp(28), context.vDp(40))
        clipToPadding = true
        setHasFixedSize(true)
        itemAnimator = null
        setItemViewCacheSize(16)
        recycledViewPool.setMaxRecycledViews(0, 24)
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(ViewTheme.backdrop)
        setFocusDrawingOrderEnabled(true)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    init {
        referenceColumns = this.columns
        val initialWidth = initialCellWidth()
        host.cellWidthPx = initialWidth
        grid.setColumnWidth(initialWidth)
        addView(
            grid,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        grid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> grid.post { updateGeometry() } }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // 焦点框画在本层，RecyclerView 自己重绘不会带上它。
                // 只刷「上一帧的框 + 当前焦点卡」两块，而不是整屏。
                if (sharedFrameVisible) invalidateFrameAndFocusCard()
            }
        })

        val adapter = PosterTileAdapter(
            context = context,
            columns = this.columns,
            onClick = onClick,
            onLongClick = onLongClick,
            onExitLeft = onExitLeft,
            onFocused = { index ->
                this.memory.index = index
                this.memory.key = host.adapter?.currentList?.getOrNull(index)?.key
                this.memory.entered = true
                onFocusedIndexChange(index)
            },
            cellWidth = { host.cellWidthPx },
        )
        host.adapter = adapter
        host.grid = grid
        controller.grid = grid
        controller.columns = columns
        grid.adapter = adapter

        var lastExitAt = 0L
        grid.setOnKeyInterceptListener { event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) grid.tag = null
            val now = SystemClock.uptimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    when {
                        event.action != KeyEvent.ACTION_DOWN -> false
                        else -> {
                            // ★ 不再按时间节流：Leanback 自己会合并重复按键，
                            //   额外的 140ms 闸门把上限压到每秒 7 行，手感是"一跳一跳"。
                            // Leanback aligns by column, so a short last row leaves the
                            // right-hand columns with no DOWN target. Clamp to the last item.
                            val focused = grid.focusedChild
                            val pos = focused?.let { grid.getChildAdapterPosition(it) }
                                ?: RecyclerView.NO_POSITION
                            if (pos == RecyclerView.NO_POSITION) return@setOnKeyInterceptListener true
                            val count = host.adapter?.itemCount ?: 0
                            val lastRowStart = ((count - 1) / this.columns) * this.columns
                            if (pos < lastRowStart && pos + this.columns > count - 1) {
                                grid.post { focusTileAt(grid, count - 1, this.columns) }
                                true
                            } else {
                                false
                            }
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    val focused = grid.focusedChild
                    val focusedPos = focused?.let { grid.getChildAdapterPosition(it) }
                        ?: RecyclerView.NO_POSITION
                    // ★ 滚动/重绑期间 focusedChild 可能短暂为 null。
                    //   此时 selectedPosition 仍可能是旧值（甚至 0），
                    //   不能拿它判断“已经在第一行”，否则长按 UP 会直接跳导航栏。
                    if (focusedPos == RecyclerView.NO_POSITION) return@setOnKeyInterceptListener true
                    val pos = focusedPos
                    val atTopRow = pos in 0 until this.columns
                    val canScrollUp = grid.canScrollVertically(-1)
                    val verdict = KeyRouting.gridUpAtTop(
                        atTopRow = atTopRow,
                        isKeyDown = event.action == KeyEvent.ACTION_DOWN,
                        repeatCount = event.repeatCount,
                        lastExitAt = lastExitAt,
                        now = now,
                    )

                    val handled = if (atTopRow) {
                        if (event.action == KeyEvent.ACTION_DOWN && canScrollUp) {
                            // ★ 只滚视口，不改 selectedPosition；否则会跳回第一列。
                            grid.scrollBy(0, -grid.computeVerticalScrollOffset())
                        }
                        if (verdict.exitToHeader && !canScrollUp) {
                            lastExitAt = now
                            grid.post { onExitUp?.invoke() }
                        }
                        true
                    } else if (pos >= this.columns) {
                        if (event.action == KeyEvent.ACTION_DOWN) moveFocusOneRowUp(grid, pos - this.columns)
                        true
                    } else {
                        false
                    }
                    handled
                }

                else -> false
            }
        }
    }

    fun setPosterSize(referenceColumns: Int) {
        this.referenceColumns = referenceColumns
        updateGeometry()
    }

    fun configureTheme(columns: Int, width: Int, gap: Int) {
        themeGeometry = com.reelix.app.data.settings.PosterGeometry(columns.coerceAtLeast(1), width.coerceAtLeast(1))
        grid.setPadding(0, 0, 0, context.vDp(24))
        grid.setHorizontalSpacing(gap)
        updateGeometry()
    }

    fun setThemePalette(palette: com.reelix.app.ui.theme.layout.NativeThemePalette) {
        host.adapter?.palette = palette
        host.adapter?.notifyDataSetChanged()
    }

    private fun updateGeometry() {
        val available = grid.width - grid.paddingLeft - grid.paddingRight
        if (available <= 0 || grid.isComputingLayout) return
        val geometry = themeGeometry ?: posterGeometry(resources.displayMetrics.widthPixels, available,
            context.vDp(10), context.vDp(28), referenceColumns, minimumColumns)
        if (columns == geometry.columns && host.cellWidthPx == geometry.width) return
        val adapter = host.adapter ?: return
        val position = currentPosition()
        val key = adapter.currentList.getOrNull(position)?.key
        val focused = grid.hasFocus()
        grid.stopScroll()
        grid.tag = null
        columns = geometry.columns
        host.cellWidthPx = geometry.width
        adapter.columns = columns
        controller.columns = columns
        grid.setNumColumns(columns)
        grid.setColumnWidth(geometry.width)
        adapter.notifyDataSetChanged()
        val target = restoredPosterPosition(adapter.currentList.map { it.key }, key, position)
        grid.setSelectedPosition(target)
        if (focused && autoFocusEnabled) grid.post { focusPosition(target) }
    }

    private fun initialCellWidth(): Int {
        val available = (resources.displayMetrics.widthPixels - grid.paddingLeft - grid.paddingRight)
            .coerceAtLeast(1)
        return posterGeometry(
            resources.displayMetrics.widthPixels,
            available,
            context.vDp(10),
            context.vDp(28),
            referenceColumns,
            minimumColumns,
        ).width
    }

    fun submit(tiles: List<PosterTile>, onCommitted: () -> Unit = {}) {
        val adapter = host.adapter ?: return
        if (!restoreViewport) savePosition()
        val request = ++submission
        val hadFocus = grid.hasFocus()
        // ★ FocusTrace：记录提交瞬间网格是否已有焦点。
        //   如果这里是 true，说明系统在布局阶段已经把焦点给了网格，
        //   那么下面 focusPosition 就会把焦点"钉"在第一项 —— 冷启动焦点跳到历史第一项就是这么来的。
        android.util.Log.i(
            "FocusTrace",
            "PosterGrid.submit hadFocus=$hadFocus pendingFocus=$pendingFocus tiles=${tiles.size} " +
                "restoreViewport=$restoreViewport"
        )
        adapter.submitList(tiles) {
            if (request != submission) return@submitList
            if (tiles.isNotEmpty()) {
                val target = restoredPosterPosition(tiles.map { it.key }, memory.key, memory.index)
                if (restoreViewport) {
                    if (memory.keys == tiles.map { it.key }) memory.layout?.let { grid.layoutManager?.onRestoreInstanceState(it) }
                    grid.setSelectedPosition(target)
                    restoreViewport = false
                }
                if (autoFocusEnabled && (pendingFocus || (hadFocus && grid.hasFocus()))) {
                    android.util.Log.i(
                        "FocusTrace",
                        "PosterGrid -> focusPosition($target) 抢焦点 " +
                            "(pending=$pendingFocus hadFocus=$hadFocus nowFocus=${grid.hasFocus()})"
                    )
                    pendingFocus = false
                    focusPosition(target)
                }
            }
            onCommitted()
        }
    }

    fun useMemory(next: PosterGridMemory) {
        if (memory === next) return
        savePosition(); memory = next; restoreViewport = true; pendingFocus = false
    }

    fun savePosition() {
        val items = host.adapter?.currentList.orEmpty()
        if (items.isEmpty()) return
        val index = currentPosition().coerceIn(items.indices)
        memory.index = index; memory.key = items[index].key
        memory.keys = items.map { it.key }
        memory.layout = grid.layoutManager?.onSaveInstanceState()
    }

    fun restoreFocus(fallback: Int) {
        val items = host.adapter?.currentList.orEmpty()
        memory.index = if (memory.key == null) fallback else memory.index
        pendingFocus = items.isEmpty()
        if (items.isNotEmpty()) focusPosition(restoredPosterPosition(items.map { it.key }, memory.key, memory.index))
    }

    override fun onDetachedFromWindow() {
        savePosition()
        grid.tag = null
        pendingFocus = false
        sharedFrameListener?.let { listener ->
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
        }
        sharedFrameListener = null
        hideSharedFrame()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sharedFrameListener == null) {
            val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
                val inside = newFocus is PosterTileView && newFocus.parent === grid
                if (!inside && sharedFrameVisible) hideSharedFrame()
                // 同一网格内移动：只重绘新旧两张卡的外扩框，不整屏重绘。
                val oldTile = oldFocus as? PosterTileView
                val newTile = newFocus as? PosterTileView
                if (inside && oldTile != null && oldTile.parent === grid && newTile != null) {
                    invalidateTiles(oldTile, newTile)
                } else if (oldFocus !== newFocus) {
                    // 跨区域进出：直接落位/消失，一次整屏重绘可以接受。
                    invalidate()
                }
            }
            sharedFrameListener = listener
            viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        }
    }

    /** 只刷新两张卡片焦点框所在的区域。 */
    private fun invalidateTiles(first: PosterTileView, second: PosterTileView) {
        val bounds = Rect()
        var has = false
        for (tile in arrayOf(first, second)) {
            val radius = tile.focusFrameRect(sharedFrameRect) ?: return invalidate()
            tile.getLocationInWindow(cardLocation)
            getLocationInWindow(hostLocation)
            val left = sharedFrameRect.left + cardLocation[0] - hostLocation[0]
            val top = sharedFrameRect.top + cardLocation[1] - hostLocation[1]
            val right = sharedFrameRect.right + cardLocation[0] - hostLocation[0]
            val bottom = sharedFrameRect.bottom + cardLocation[1] - hostLocation[1]
            if (has) bounds.union(left, top, right, bottom) else {
                bounds.set(left, top, right, bottom)
                has = true
            }
            if (radius <= 0f) return invalidate()
        }
        if (!has) return invalidate()
        val pad = focusPaintPadding()
        bounds.inset(-pad, -pad)
        invalidate(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    override fun dispatchDraw(canvas: Canvas) {
        syncSharedFrame()
        super.dispatchDraw(canvas)
        if (!sharedFrameVisible || sharedFrame.isEmpty) {
            paintedFrameValid = false
            return
        }
        val card = sharedFrameCard ?: return
        sharedFocusPaint.color = card.focusRingColor()
        sharedFocusPaint.strokeWidth = card.focusStrokeWidth()
        val radius = sharedFrameRadius.coerceAtLeast(0f)
        canvas.drawRoundRect(sharedFrame, radius, radius, sharedFocusPaint)
        // 记下这次画出去的位置，下次移动时用它算脏矩形。
        sharedFrame.roundOut(paintedFrame)
        paintedFrameValid = true
    }

    /** 每帧对齐一次：卡片被回收/重绑或滚动后也能自愈。 */
    private fun syncSharedFrame() {
        val card = grid.focusedChild as? PosterTileView
        if (card == null || !card.isFocused) {
            hideSharedFrame()
            return
        }
        val radius = card.focusFrameRect(sharedFrameRect)
        if (radius == null) {
            // 没有主题卡片样式时由卡片自己画框，这里不接管。
            hideSharedFrame()
            return
        }
        card.getLocationInWindow(cardLocation)
        getLocationInWindow(hostLocation)
        val dx = (cardLocation[0] - hostLocation[0]).toFloat()
        val dy = (cardLocation[1] - hostLocation[1]).toFloat()
        sharedFrame.set(
            sharedFrameRect.left + dx,
            sharedFrameRect.top + dy,
            sharedFrameRect.right + dx,
            sharedFrameRect.bottom + dy,
        )
        sharedFrameRadius = radius
        sharedFrameVisible = true

        if (sharedFrameCard !== card) {
            sharedFrameCard?.setSuppressOuterFocus(false)
            card.setSuppressOuterFocus(true)
            sharedFrameCard = card
        }
    }

    private fun hideSharedFrame() {
        val hadFrame = sharedFrameVisible || sharedFrameCard != null
        sharedFrameCard?.setSuppressOuterFocus(false)
        sharedFrameCard = null
        sharedFrameVisible = false
        if (hadFrame) invalidateFrame()
    }

    /** 焦点框描边会外扩，重绘区域要把它算进去。 */
    private fun focusPaintPadding(): Int =
        ((sharedFrameCard?.focusStrokeWidth() ?: 2f) + 2f).toInt() + context.vDp(4)

    /**
     * 只刷新旧两块框所在的区域。
     *
     * 整屏 invalidate 在滚动时会把整面墙重绘一遍，电视 GPU 上很贵；
     * 焦点框只有几十 dp，脏矩形能把这部分成本降一个数量级。
     */
    private fun invalidateFrame() {
        if (!paintedFrameValid) {
            invalidate()
            return
        }
        val pad = focusPaintPadding()
        dirtyFrame.set(paintedFrame)
        dirtyFrame.inset(-pad, -pad)
        invalidate(dirtyFrame.left, dirtyFrame.top, dirtyFrame.right, dirtyFrame.bottom)
    }

    /**
     * 滚动时同时刷新「上一帧画出去的框」和「当前焦点卡」。
     *
     * 只刷其中一个会留下残影：滚动中卡片位置在变，旧框位置和新框位置不重合。
     */
    private fun invalidateFrameAndFocusCard() {
        val pad = focusPaintPadding()
        val union = Rect()
        var has = false
        if (paintedFrameValid) {
            union.set(paintedFrame)
            has = true
        }
        val card = sharedFrameCard
        if (card != null && card.isAttachedToWindow) {
            val radius = card.focusFrameRect(sharedFrameRect)
            if (radius == null) return invalidate()
            card.getLocationInWindow(cardLocation)
            getLocationInWindow(hostLocation)
            val dx = cardLocation[0] - hostLocation[0]
            val dy = cardLocation[1] - hostLocation[1]
            val left = sharedFrameRect.left + dx
            val top = sharedFrameRect.top + dy
            val right = sharedFrameRect.right + dx
            val bottom = sharedFrameRect.bottom + dy
            if (has) union.union(left, top, right, bottom) else {
                union.set(left, top, right, bottom)
                has = true
            }
        }
        if (!has) return invalidate()
        union.inset(-pad, -pad)
        invalidate(union.left, union.top, union.right, union.bottom)
    }

    /** 片库手动刷新海报后强制重绑可见卡片。 */
    fun refresh() {
        host.adapter?.notifyDataSetChanged()
    }

    fun focusPosition(index: Int) {
        val items = host.adapter?.currentList.orEmpty()
        if (items.isEmpty()) { memory.index = index.coerceAtLeast(0); pendingFocus = true; return }
        focusTileAt(grid, index.coerceIn(items.indices), columns)
    }

    fun currentPosition(): Int {
        val focused = grid.focusedChild
        val focusedPos = focused?.let { grid.getChildAdapterPosition(it) }
            ?: RecyclerView.NO_POSITION
        return if (focusedPos != RecyclerView.NO_POSITION) focusedPos else grid.selectedPosition
    }

    fun exitTo(request: () -> Unit) {
        grid.tag = null
        grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        grid.isFocusable = false
        grid.clearFocus()
        grid.post {
            request()
            grid.post {
                grid.isFocusable = true
                grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }

    private class Host {
        var grid: VerticalGridView? = null
        var adapter: PosterTileAdapter? = null
        var cellWidthPx: Int = 0
    }
}

private object PosterTileDiff : DiffUtil.ItemCallback<PosterTile>() {
    override fun areItemsTheSame(oldItem: PosterTile, newItem: PosterTile): Boolean =
        oldItem.key == newItem.key

    override fun areContentsTheSame(oldItem: PosterTile, newItem: PosterTile): Boolean =
        oldItem == newItem
}

internal class PosterTileAdapter(
    private val context: Context,
    var columns: Int,
    private val onClick: (PosterTile) -> Unit,
    private val onLongClick: ((PosterTile) -> Unit)?,
    private val onExitLeft: (() -> Unit)?,
    private val onFocused: (Int) -> Unit,
    private val cellWidth: () -> Int,
    private val imageHeight: (() -> Int)? = null,
) : ListAdapter<PosterTile, PosterTileAdapter.Holder>(PosterTileDiff) {
    var palette: com.reelix.app.ui.theme.layout.NativeThemePalette? = null
    var cardStyle: com.reelix.app.ui.theme.layout.HistoryCardStyle? = null

    inner class Holder(val card: PosterTileView) : RecyclerView.ViewHolder(card) {
        var longFired: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = PosterTileView(parent.context)
        val holder = Holder(card)

        card.setOnKeyListener { _, keyCode, event ->
            handleKey(holder, keyCode, event)
        }
        card.setOnFocusChangeListener { _, hasFocus ->
            card.setFocused(hasFocus)
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onFocused(pos)
            } else {
                handler.removeCallbacksAndMessages(null)
                holder.longFired = false
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = getItem(position) ?: return
        val width = posterWidthPx()
        holder.card.setTheme(palette)
        holder.card.setPresentation(cardStyle)
        holder.card.setCardWidth(if (imageHeight != null) width else 0)
        holder.card.bind(tile, width, cardStyle?.imageHeightPx ?: imageHeight?.invoke() ?: (width * 3) / 2)
    }

    private fun posterWidthPx(): Int {
        val cell = cellWidth().takeIf { it > 0 }
            ?: (context.resources.displayMetrics.widthPixels / columns.coerceAtLeast(1))
        return cell.coerceAtLeast(1)
    }

    private fun handleKey(holder: Holder, keyCode: Int, event: KeyEvent): Boolean {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return false

        val activate = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val count = itemCount
        val atBoundary = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> position % columns == 0
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                position == count - 1 || position % columns == columns - 1
            KeyEvent.KEYCODE_DPAD_DOWN -> position >= count - columns
            else -> false
        }

        if (atBoundary) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                event.action == KeyEvent.ACTION_DOWN &&
                onExitLeft != null
            ) {
                holder.itemView.post { onExitLeft.invoke() }
            }
            // ★ 边界键连 KeyUp 一起吃掉，否则 Leanback 会用 KeyUp 把焦点送走。
            return true
        }

        if (!activate) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            val tile = getItem(position) ?: return true
            if (event.repeatCount == 0) holder.longFired = false
            if (onLongClick != null && event.repeatCount == 0 && !holder.longFired) {
                handler.postDelayed({
                    if (holder.card.isFocused &&
                        holder.bindingAdapterPosition == position
                    ) {
                        holder.longFired = true
                        onLongClick.invoke(tile)
                    }
                }, 550L)
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacksAndMessages(null)
            if (!holder.longFired) getItem(position)?.let(onClick)
            holder.longFired = false
            return true
        }

        return false
    }
}

/**
 * 海报墙的外部焦点入口。
 */
class TileGridController {
    internal var grid: VerticalGridView? = null
    internal var columns: Int = 1

    val isReady: Boolean get() = grid != null

    fun focusPosition(index: Int) {
        val vg = grid ?: return
        focusTileAt(vg, index.coerceAtLeast(0), columns)
    }

    fun currentPosition(): Int {
        val vg = grid ?: return 0
        val focused = vg.focusedChild
        val focusedPos = focused?.let { vg.getChildAdapterPosition(it) }
            ?: RecyclerView.NO_POSITION
        return if (focusedPos != RecyclerView.NO_POSITION) focusedPos else vg.selectedPosition
    }

    fun exitTo(request: () -> Unit) {
        val vg = grid ?: return
        vg.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        vg.isFocusable = false
        vg.clearFocus()
        vg.post {
            request()
            vg.post {
                vg.isFocusable = true
                vg.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }
}

/**
 * 把焦点放到指定下标并保留列。
 *
 * 目标行未布局时先选行首，再点名聚焦目标卡片；避免 Leanback 把目标当成
 * 新一行的起点而跳到第一列。
 */
internal fun focusTileAt(vg: VerticalGridView, target: Int, columns: Int) {
    vg.tag = null
    val count = vg.adapter?.itemCount ?: 0
    if (count <= 0) return
    val safeTarget = target.coerceIn(0, count - 1)

    val direct = vg.findViewHolderForAdapterPosition(safeTarget)
    if (direct != null) {
        focusHolder(vg, safeTarget, direct)
        return
    }

    // ★ 目标行还没布局时，只把行首当作“滚动锚点”，绝不能把焦点停在行首。
    //   之前的兜底是 rowHolder.itemView.requestFocus()：滚动动画还没把目标行
    //   布局出来时，焦点就会留在第一列，用户看到的就是“按 UP 突然跳到第一列”。
    //   这里改成逐帧重试目标卡片，直到它真正被布局出来。
    val rowStart = KeyRouting.gridRowStart(safeTarget, columns)
    vg.setSelectedPosition(rowStart)

    val token = Any()
    vg.tag = token
    retryFocusTile(vg, safeTarget, token, attempt = 0)
}

private const val FOCUS_TILE_MAX_FRAMES = 16

/**
 * 上移一行。
 *
 * ★ 不能用 [focusTileAt] 的多帧重试来做逐行上移：
 *   每次 UP 都会取消上一次重试，用户连续按时就会“按了没反应”。
 *   这里先用当前卡片高度即时滚动一行，让目标行进入布局，再聚焦目标列。
 */
private fun moveFocusOneRowUp(vg: VerticalGridView, target: Int) {
    // 逐行按键优先：作废可能还在跑的“恢复焦点”重试，避免旧任务稍后把焦点抢回去。
    vg.tag = null
    val direct = vg.findViewHolderForAdapterPosition(target)
    if (direct != null) {
        focusHolder(vg, target, direct)
        return
    }

    val focused = vg.focusedChild
    val rowHeight = (focused?.height ?: 0) + vg.verticalSpacing
    if (rowHeight > 0) vg.scrollBy(0, -rowHeight)

    val token = Any()
    vg.tag = token
    vg.post {
        if (vg.tag !== token || !vg.isAttachedToWindow || !vg.hasFocus()) return@post
        val holder = vg.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            vg.tag = null
            focusHolder(vg, target, holder)
            return@post
        }
        // 目标行仍不在布局内（例如第一行离得很远）：直接滚到目标再聚焦。
        vg.scrollToPosition(target)
        vg.post finishStep@{
            if (vg.tag !== token || !vg.isAttachedToWindow || !vg.hasFocus()) return@finishStep
            vg.tag = null
            val last = vg.findViewHolderForAdapterPosition(target)
            if (last != null) {
                focusHolder(vg, target, last)
            }
        }
    }
}

/**
 * 聚焦一张卡片。
 *
 * ★ 卡片在 RecyclerView 的缓存区里、但完全在视口外时，
 *   `requestFocus()` 会返回 true 却不真正生效。必须先把目标滚进可视区。
 */
private fun focusHolder(
    vg: VerticalGridView,
    target: Int,
    holder: RecyclerView.ViewHolder,
) {
    val view = holder.itemView
    val visibleTop = vg.paddingTop
    val visibleBottom = vg.height - vg.paddingBottom
    when {
        view.top < visibleTop -> vg.scrollBy(0, view.top - visibleTop)
        view.bottom > visibleBottom -> vg.scrollBy(0, view.bottom - visibleBottom)
    }
    view.requestFocus()
    vg.setSelectedPosition(target)
}

private fun retryFocusTile(
    vg: VerticalGridView,
    target: Int,
    token: Any,
    attempt: Int,
) {
    vg.postOnAnimation {
        // 期间又按了别的方向键：这次重试作废，避免旧任务把焦点抢回去。
        if (vg.tag !== token || !vg.isAttachedToWindow) return@postOnAnimation

        val holder = vg.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            focusHolder(vg, target, holder)
            vg.tag = null
            return@postOnAnimation
        }

        if (attempt >= FOCUS_TILE_MAX_FRAMES) {
            // 兜底：直接滚到目标项再试一次；仍然不把焦点交给行首。
            vg.scrollToPosition(target)
            vg.post {
                if (vg.tag !== token || !vg.isAttachedToWindow) return@post
                val last = vg.findViewHolderForAdapterPosition(target)
                if (last != null) {
                    focusHolder(vg, target, last)
                }
                vg.tag = null
            }
            return@postOnAnimation
        }

        retryFocusTile(vg, target, token, attempt + 1)
    }
}
