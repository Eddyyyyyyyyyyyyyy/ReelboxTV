package com.reelix.app.ui.screen.online

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.source.CategoryContent
import com.reelix.app.data.source.HomeContent
import com.reelix.app.data.source.SourceCategory
import com.reelix.app.data.source.SourceManager
import com.reelix.app.ui.component.PageView
import com.reelix.app.ui.component.FilterChip
import com.reelix.app.ui.component.PosterGridMemory
import com.reelix.app.ui.component.PosterGridView
import com.reelix.app.ui.component.PosterTile
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp

data class OnlineViewState(
    val sites: List<SourceManager.SiteEntry> = emptyList(),
    val currentSiteIndex: Int = 0,
    val home: HomeContent? = null,
    val category: CategoryContent? = null,
    val selectedCategoryId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val refreshing: Boolean = false,
    val refreshingDetail: String? = null,
    val loadingSites: Boolean = false,
    val posterMemory: PosterGridMemory = PosterGridMemory(),
)

/**
 * 在线页原生外壳。
 *
 * 数据、缓存、站点/分类切换仍由 MainActivity 持有；这里只负责原生 View、
 * 焦点链和 PosterGridView 的状态映射。
 */
class OnlineView(
    context: Context,
    private val onSelectSite: (Int) -> Unit,
    private val onSelectCategory: (String) -> Unit,
    private val onOpenItem: (MediaItem) -> Unit,
    private val onExitTop: () -> Unit,
    private val onPosterIndexChange: (Int) -> Unit = {},
    initialColumns: Int = 6,
) : LinearLayout(context), PageView {

    private val header = LinearLayout(context)
    private val siteButton = context.textView("", 13f, ViewTheme.textSecondary, Gravity.CENTER).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(context.vDp(10), 0, context.vDp(10), 0)
        id = View.generateViewId()
        setOnClickListener { showSitePopup() }
        setOnFocusChangeListener { _, focused ->
            setTextColor(if (focused) ViewTheme.textPrimary else ViewTheme.textSecondary)
        }
        background = focusStateBackground(
            normalColor = 0x0DFFFFFF,
            focusColor = 0x33FFFFFF,
            radiusPx = context.vDp(8),
            focusStrokeWidthPx = context.vDp(2),
            focusStrokeColor = ViewTheme.focusRing,
        )
        setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) focusGrid()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) focusFirstCategory()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) onExitTop()
                    true
                }
                else -> false
            }
        }
    }

    private val categoryScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = true
    }
    private val categoryRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val categories = LinkedHashMap<String, FilterChip>()
    private var categorySignature: List<Pair<String, String>> = emptyList()
    private var selectedCategoryId: String? = null

    private val refreshStatus = context.textView("", 11f, ViewTheme.textTertiary)
    private val status = context.textView(
        sizeSp = 14f,
        color = ViewTheme.textTertiary,
        gravity = Gravity.CENTER,
    )
    private val content = FrameLayout(context)
    private val grid = PosterGridView(
        context = context,
        columns = initialColumns,
        onClick = { tile -> tilePayload[tile.key]?.let(onOpenItem) },
        onExitUp = { focusSelectedCategoryOrSite() },
        onFocusedIndexChange = onPosterIndexChange,
    )
    private val tilePayload = LinkedHashMap<String, MediaItem>()
    private var state = OnlineViewState()
    private var sitePopup: PopupWindow? = null

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ViewTheme.backdrop)
        setPadding(0, context.vDp(84), 0, 0)

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(context.vDp(28), 0, context.vDp(28), 0)
        header.addView(siteButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(36)))
        categoryScroll.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        categoryRow.setPadding(context.vDp(18), 0, 0, 0)
        categoryScroll.addView(categoryRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        header.addView(categoryScroll)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(40)))

        // Background refresh is silent: no banner above the poster wall.
        refreshStatus.visibility = GONE
        addView(View(context), LayoutParams(1, context.vDp(10)))

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
    }

    fun setPosterSize(columns: Int) = grid.setPosterSize(columns)

    fun setPosterMemory(memory: PosterGridMemory) {
        grid.useMemory(memory)
    }

    fun render(next: OnlineViewState) {
        state = next
        val currentSite = next.sites.getOrNull(next.currentSiteIndex)
        siteButton.text = currentSite?.displayName.orEmpty()
        siteButton.visibility = if (next.sites.isEmpty()) GONE else VISIBLE
        categoryScroll.visibility = if (next.sites.isEmpty()) GONE else VISIBLE
        header.visibility = if (next.sites.isEmpty() && next.home == null) GONE else VISIBLE
        grid.useMemory(next.posterMemory)

        if (next.sites.isEmpty()) {
            showStatus(
                if (next.loadingSites) "正在恢复在线源…\n先显示上次缓存，随后自动更新"
                else "还没有加载在线源\n去设置里填入订阅地址并点「加载订阅源」",
            )
            return
        }

        selectedCategoryId = next.selectedCategoryId
        rebuildCategories(next.home?.categories.orEmpty())
        refreshStatus.text = ""
        refreshStatus.visibility = GONE

        when {
            next.loading -> if (tilePayload.isEmpty()) showStatus("加载中…") else renderItems(next)
            next.error != null -> showStatus(
                if (next.refreshing) "后台更新失败，已保留缓存：${next.error.take(60)}"
                else "加载失败：${next.error.take(60)}",
            )
            else -> renderItems(next)
        }
    }

    /** Rebind visible posters after an online artwork override changes. */
    fun refreshPosters() {
        if (grid.visibility == VISIBLE) grid.refresh()
    }

    private fun renderItems(next: OnlineViewState) {
        val items = if (next.selectedCategoryId != null && next.category == null) {
            emptyList()
        } else {
            next.category?.items ?: next.home?.recommendations.orEmpty()
        }
        if (items.isEmpty()) {
            showStatus(
                if (next.selectedCategoryId != null && next.category == null) "正在读取分类内容…"
                else "这个分类暂时没有内容",
            )
            return
        }

        tilePayload.clear()
        val tiles = items.map { item ->
            val shown = com.reelix.app.data.image.OnlineArtworkOverrides.apply(item)
            tilePayload[item.id] = shown
            PosterTile(
                key = item.id,
                title = shown.title,
                posterUrl = shown.poster,
                posterRes = shown.posterRes,
                progress = shown.progress,
                allowTitleFallback = false,
            )
        }
        status.visibility = GONE
        grid.visibility = VISIBLE
        grid.submit(tiles)
    }

    private fun showStatus(message: String) {
        grid.visibility = GONE
        status.visibility = VISIBLE
        status.text = message
    }

    private fun rebuildCategories(next: List<SourceCategory>) {
        val signature = next.map { it.id to it.name }
        if (signature != categorySignature) {
            val focused = categories.entries.firstOrNull { it.value.hasFocus() }?.key
            categorySignature = signature
            categories.clear()
            categoryRow.removeAllViews()
            next.forEach { category ->
                val chip = FilterChip(
                    context = context,
                    label = category.name,
                    onClick = { onSelectCategory(category.id) },
                    onDown = ::focusGrid,
                    onUp = onExitTop,
                )
                chip.id = View.generateViewId()
                categories[category.id] = chip
                categoryRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(36)).apply {
                    rightMargin = context.vDp(8)
                })
            }
            if (focused != null) categories[focused]?.requestFocus()
        }
        categories.forEach { (id, chip) -> chip.setChosen(id == selectedCategoryId) }
    }

    private fun focusGrid() {
        grid.autoFocusEnabled = true
        if (grid.visibility == VISIBLE) grid.focusPosition(grid.currentPosition())
    }

    private fun focusSelectedCategoryOrSite() {
        val chip = selectedCategoryId?.let(categories::get) ?: categories.values.firstOrNull()
        if (chip == null) {
            siteButton.requestFocus()
        } else {
            chip.requestFocus()
        }
    }

    private fun focusFirstCategory(): Boolean {
        val chip = categories.values.firstOrNull() ?: return false
        chip.requestFocus()
        return true
    }

    private fun showSitePopup() {
        if (state.sites.isEmpty()) return
        sitePopup?.dismiss()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.vDp(12), context.vDp(10), context.vDp(12), context.vDp(10))
            background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(12))
        }
        panel.addView(
            context.textView("选择在线源", 12f, ViewTheme.textTertiary, Gravity.CENTER_VERTICAL).apply {
                setPadding(context.vDp(12), 0, context.vDp(12), context.vDp(6))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(34)),
        )
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var selected: TextView? = null
        state.sites.forEachIndexed { index, site ->
            val row = context.textView(site.displayName, 14f, ViewTheme.textPrimary, Gravity.CENTER_VERTICAL).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(context.vDp(12), 0, context.vDp(12), 0)
                background = focusStateBackground(
                    normalColor = if (index == state.currentSiteIndex) 0x24FFD466 else Color.TRANSPARENT,
                    focusColor = 0x33FFFFFF,
                    radiusPx = context.vDp(8),
                    focusStrokeWidthPx = context.vDp(2),
                    focusStrokeColor = ViewTheme.focusRing,
                )
                setOnClickListener {
                    sitePopup?.dismiss()
                    if (index != state.currentSiteIndex) onSelectSite(index)
                }
                setOnFocusChangeListener { view, focused ->
                    if (focused) scroll.post {
                        val top = view.top
                        val bottom = view.bottom
                        val visibleTop = scroll.scrollY
                        val visibleBottom = visibleTop + scroll.height
                        when {
                            top < visibleTop -> scroll.scrollTo(0, top)
                            bottom > visibleBottom -> scroll.scrollTo(0, bottom - scroll.height)
                        }
                    }
                }
            }
            if (index == state.currentSiteIndex) selected = row
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(46)))
        }
        scroll.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        panel.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val topOffset = context.vDp(84)
        val availableHeight = (resources.displayMetrics.heightPixels - topOffset - context.vDp(16))
            .coerceAtLeast(context.vDp(240))
        val popupHeight = minOf(context.vDp(86 + state.sites.size * 46), availableHeight)
        val popup = PopupWindow(
            panel,
            context.vDp(360),
            popupHeight,
            true,
        )
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = context.vDp(12).toFloat()
        popup.setOnDismissListener {
            if (sitePopup === popup) sitePopup = null
            siteButton.post { siteButton.requestFocus() }
        }
        sitePopup = popup
        popup.showAtLocation(this, Gravity.END or Gravity.TOP, context.vDp(28), topOffset)
        popup.contentView.post { selected?.requestFocus() }
    }

    override fun focusFirst(): Boolean {
        if (siteButton.visibility != VISIBLE) return false
        return siteButton.requestFocus()
    }

    override fun saveFocusIndex(): Int = grid.currentPosition()

    override fun restoreFocus(index: Int) {
        grid.autoFocusEnabled = true
        grid.restoreFocus(index)
    }

}
