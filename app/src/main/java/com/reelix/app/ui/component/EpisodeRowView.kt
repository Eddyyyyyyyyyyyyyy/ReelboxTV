package com.reelix.app.ui.component

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.ViewHolderTask
import com.reelix.app.player.episodeKey
import com.reelix.app.player.episodePosition
import com.reelix.app.data.model.Episode
import com.reelix.app.ui.theme.layout.EpisodeRailPresentation
import com.reelix.app.ui.theme.layout.NativeThemePalette
import com.reelix.app.ui.theme.layout.standardEpisodePresentation

/**
 * 剧集横轨的原生 View 版。
 *
 * 复用 [EpisodeCardView]；HorizontalGridView 负责行内焦点与滚动。
 */
class EpisodeRowView(
    context: Context,
    private val onPlay: (Episode) -> Unit,
    private val onExitUp: () -> Unit,
    private val onBoundaryStart: () -> Unit = {},
    private val onExitDown: (() -> Unit)? = null,
    private val onSelected: (Episode) -> Unit = {},
    private val onLongPress: ((Episode) -> Unit)? = null,
    private val onArmedClick: ((Episode) -> Unit)? = null,
    /** Per-episode gate; long-press does nothing when this returns false. */
    private val canLongPress: ((Episode) -> Boolean)? = null,
) : FrameLayout(context) {

    private val adapter = Adapter(onPlay, onExitUp, onBoundaryStart, onExitDown, onLongPress, onArmedClick)
    private var longPressRunnable: Runnable? = null
    private var longFired = false
    private var longPressPosition = RecyclerView.NO_POSITION
    val grid = HorizontalGridView(context).apply {
        setNumRows(1)
        setHorizontalSpacing(context.vDp(16))
        setVerticalSpacing(0)
        setPadding(0, 0, 0, 0)
        clipToPadding = true
        setHasFixedSize(true)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
        adapter = this@EpisodeRowView.adapter
    }

    private var signature: List<Episode> = emptyList()
    private var currentIndex: Int? = null
    private var currentProgress: Float = 0f
    private var selectPosition: Int = 0
    private var focusGeneration = 0
    private var presentation: EpisodeRailPresentation? = null
    private var armedKey: String? = null
    fun selectedKey(): String? = signature.getOrNull(selectPosition)?.let(::episodeKey)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            MeasureSpec.getSize(heightMeasureSpec) == 0) MeasureSpec.makeMeasureSpec(
                presentation?.cellHeightPx ?: context.vDp(144), MeasureSpec.EXACTLY)
            else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, height)
    }

    fun configurePresentation(requested: EpisodeRailPresentation, palette: NativeThemePalette?) {
        val style = requested
        val height = style.cellHeightPx
        if (presentation == style && adapter.palette == palette && layoutParams?.height == height) return
        presentation = style
        adapter.presentation = style
        adapter.palette = palette
        minimumHeight = height
        layoutParams?.let { it.height = height; layoutParams = it }
        setPadding(0, 0, 0, 0)
        clipChildren = true
        grid.clipChildren = true
        grid.clipToPadding = true
        grid.minimumHeight = style.cellHeightPx
        grid.setRowHeight(style.cellHeightPx)
        grid.setHorizontalSpacing(style.cellGapPx)
        adapter.notifyDataSetChanged()
    }

    init {
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configurePresentation(standardEpisodePresentation(
            resources.displayMetrics.density, resources.configuration.fontScale), null)
        grid.setOnFocusChangeListener { _, _ -> if (!grid.hasFocus()) focusGeneration++ }
        grid.setOnKeyInterceptListener { event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) focusGeneration++
            val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!confirm || onLongPress == null) return@setOnKeyInterceptListener false
            val focused = grid.focusedChild ?: return@setOnKeyInterceptListener false
            val position = grid.getChildAdapterPosition(focused)
            val episode = signature.getOrNull(position) ?: return@setOnKeyInterceptListener false
            if (canLongPress?.invoke(episode) == false) return@setOnKeyInterceptListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    longFired = false
                    longPressPosition = position
                    clearLongPress()
                    val runnable = Runnable {
                        if (longPressPosition == position && grid.focusedChild === focused) {
                            longFired = true
                            onLongPress?.invoke(episode)
                        }
                    }
                    longPressRunnable = runnable
                    grid.postDelayed(runnable, LONG_PRESS_MS)
                }
                // Swallow the press so the grid cannot also start playback.
                true
            } else {
                clearLongPress()
                val fired = longFired
                longFired = false
                if (!fired) {
                    if (armedKey != null && episodeKey(episode) == armedKey) onArmedClick?.invoke(episode)
                    else onPlay(episode)
                }
                true
            }
        }
        grid.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(parent: RecyclerView, child: RecyclerView.ViewHolder?, position: Int, subposition: Int) {
                if (position in signature.indices && child?.itemView?.hasFocus() == true) {
                    this@EpisodeRowView.selectPosition = position
                    onSelected(signature[position])
                }
            }
        })
    }

    fun submit(
        episodes: List<Episode>,
        currentIndex: Int?,
        currentProgress: Float,
        selectPosition: Int,
    ) {
        val progress = currentProgress.coerceIn(0f, 1f)
        if (signature == episodes &&
            this.currentIndex == currentIndex &&
            this.currentProgress == progress
        ) {
            return
        }
        val previousKey = selectedKey()
        val wasFocused = grid.hasFocus()
        signature = episodes
        this.currentIndex = currentIndex
        this.currentProgress = progress
        this.selectPosition = episodePosition(episodes, previousKey, selectPosition)
        val target = this.selectPosition
        adapter.submit(episodes, currentIndex, progress)
        this.selectPosition = target
        grid.setSelectedPosition(target)
        if (wasFocused) focusRow()
    }

    fun focusRow(): Boolean {
        if (signature.isEmpty()) return false
        val target = selectPosition
        val generation = ++focusGeneration
        fun focusWhenReady(attempt: Int) {
            if (generation != focusGeneration || !isAttachedToWindow || !hasWindowFocus()) return
            requestRectangleOnScreen(android.graphics.Rect(0, 0, width, height), true)
            grid.setSelectedPosition(target, object : ViewHolderTask {
                override fun run(holder: RecyclerView.ViewHolder) {
                    if (generation == focusGeneration && isAttachedToWindow && hasWindowFocus()) holder.itemView.requestFocus()
                }
            })
            val holder = grid.findViewHolderForAdapterPosition(target)
            if (holder != null) {
                holder.itemView.requestFocus()
                return
            }
            if (attempt > 0) grid.postOnAnimation { focusWhenReady(attempt - 1) }
        }
        grid.post { focusWhenReady(4) }
        return true
    }

    fun selectKey(key: String) {
        val position = signature.indexOfFirst { episodeKey(it) == key }
        if (position < 0) return
        focusGeneration++
        selectPosition = position
        grid.setSelectedPosition(position)
    }

    /** 这一集是否在当前行的数据里（换了线路后集号可能对不上）。 */
    fun hasKey(key: String): Boolean = signature.any { episodeKey(it) == key }

    /**
     * 直接把焦点放到指定选集卡上。
     *
     * 与 [focusRow] 的区别：这里只在实际拿到焦点时返回 true。
     * 详情页从播放器返回时选集行可能还没布局、窗口也还没拿到焦点，
     * 一次 requestFocus 很容易落空，调用方需要靠返回值决定是否重试。
     */
    fun focusKey(key: String): Boolean {
        val position = signature.indexOfFirst { episodeKey(it) == key }
        if (position < 0) return false
        focusGeneration++
        selectPosition = position
        grid.setSelectedPosition(position)
        if (!isAttachedToWindow || !hasWindowFocus()) return false
        val holder = grid.findViewHolderForAdapterPosition(position) ?: return false
        return holder.itemView.requestFocus()
    }

    /** Marks one card for destructive confirmation without changing playback wiring. */
    fun arm(key: String?) {
        armedKey = key
        adapter.armedKey = key
        adapter.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        focusGeneration++
        clearLongPress()
        super.onDetachedFromWindow()
    }

    private fun clearLongPress() {
        longPressRunnable?.let(grid::removeCallbacks)
        longPressRunnable = null
    }

    private class Adapter(
        private val onPlay: (Episode) -> Unit,
        private val onExitUp: () -> Unit,
        private val onBoundaryStart: () -> Unit,
        private val onExitDown: (() -> Unit)?,
        private val onLongPress: ((Episode) -> Unit)?,
        private val onArmedClick: ((Episode) -> Unit)?,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        var presentation: EpisodeRailPresentation? = null
        var palette: NativeThemePalette? = null
        var armedKey: String? = null

        private var items: List<Episode> = emptyList()
        private var currentIndex: Int? = null
        private var currentProgress: Float = 0f

        inner class Holder(val card: EpisodeCardView) : RecyclerView.ViewHolder(card)
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = episodeKey(items[position]).fold(1125899906842597L) { h, c -> 31 * h + c.code }

        fun submit(list: List<Episode>, index: Int?, progress: Float) {
            val old = items
            val oldIndex = currentIndex
            val oldProgress = currentProgress
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(a: Int, b: Int) = episodeKey(old[a]) == episodeKey(list[b])
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == list[b] &&
                    (old[a].index == oldIndex) == (list[b].index == index) && oldProgress == progress
            })
            items = list
            currentIndex = index
            currentProgress = progress
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = EpisodeCardView(parent.context)
            val holder = Holder(card)
            card.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val episode = items[pos]
                    // An armed card confirms deletion instead of starting playback.
                    if (armedKey != null && episodeKey(episode) == armedKey) onArmedClick?.invoke(episode)
                    else onPlay(episode)
                }
            }
            card.setOnKeyListener { _, keyCode, event ->
                val pos = holder.bindingAdapterPosition
                val exit = KeyRouting.episodeRowExit(keyCode == KeyEvent.KEYCODE_DPAD_UP,
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN, onExitDown != null)
                when {
                    exit != 0 -> {
                        if (event.action == KeyEvent.ACTION_DOWN) card.post {
                            if (exit < 0) onExitUp() else onExitDown?.invoke()
                        }
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos <= 0 -> {
                        if (event.action == KeyEvent.ACTION_DOWN) card.post { onBoundaryStart() }
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos >= items.lastIndex -> true
                    else -> false
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = items[position]
            val isCurrent = currentIndex != null && episode.index == currentIndex
            holder.card.configure(presentation, palette)
            holder.card.bind(
                episode = episode,
                progress = if (isCurrent) currentProgress else 0f,
            )
            holder.card.setArmed(armedKey != null && episodeKey(episode) == armedKey)
        }
    }
}

private const val LONG_PRESS_MS = 550L
