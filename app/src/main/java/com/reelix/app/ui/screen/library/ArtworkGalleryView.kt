package com.reelix.app.ui.screen.library

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.load
import com.reelix.app.R
import com.reelix.app.data.image.ArtworkBackdropState
import com.reelix.app.data.image.LibraryArtworkCandidate
import com.reelix.app.data.image.LibraryArtworkSource
import com.reelix.app.data.image.artworkBackdropState
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp

/** State needed by the gallery to apply one candidate. */
data class ArtworkGallerySelection(
    val candidate: LibraryArtworkCandidate,
)

/**
 * Two-stage artwork picker. The browse stage shows the candidate rail plus a small preview;
 * pressing OK on a candidate opens the large preview while keeping the rail interactive.
 */
internal class ArtworkGalleryView(
    context: Context,
    title: String,
    private val onClose: () -> Unit,
    private val onSearch: (String) -> Unit,
    private val onApply: (ArtworkGallerySelection) -> Unit,
    private val onlineOnly: Boolean = false,
    /** 已启用渠道，按设置里的刮削顺序；空表示未知，退回内置默认。 */
    private val filterSources: List<LibraryArtworkSource> = emptyList(),
) : FrameLayout(context) {

    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.32f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val scrim = View(context).apply {
        setBackgroundColor(0xC8000000.toInt())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val headerTitle = context.textView(title, 24f, ViewTheme.textPrimary)
    private val workName = context.textView("", 14f, ViewTheme.textSecondary)
    private val status = context.textView("", 13f, ViewTheme.textSecondary)
    private val searchInput = android.widget.EditText(context).apply {
        setTextColor(ViewTheme.textPrimary)
        setHintTextColor(ViewTheme.textTertiary)
        textSize = 16f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        hint = context.getString(R.string.library_artwork_query)
        background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(6), context.vDp(2), ViewTheme.focusRing)
        setPadding(context.vDp(12), context.vDp(8), context.vDp(12), context.vDp(8))
    }
    private val searchButton = actionButton(context.getString(R.string.library_artwork_search)) { onSearch(query()) }
    /**
     * 搜索框右侧的「删除 / 清空」。
     *
     * 电视遥控器没有退格键，长片名打错一个字就只能整串重输。
     * 这两个按钮只编辑输入框里的文字，不碰海报和资料。
     */
    private val deleteCharButton = actionButton(context.getString(R.string.library_artwork_delete_char)) {
        deleteLastChar()
    }
    private val clearQueryButton = actionButton(context.getString(R.string.library_artwork_clear_query)) {
        searchInput.setText("")
    }
    private val filterAll = actionButton(context.getString(R.string.library_artwork_gallery_all)) { setFilter(null) }
    private val filterChips = linkedMapOf<LibraryArtworkSource, TextView>()
    private val portrait = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedBackground(ViewTheme.surface, context.vDp(8))
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val landscape = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedBackground(ViewTheme.surface, context.vDp(8))
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val candidateTitle = context.textView("", 17f, ViewTheme.textPrimary)
    private val candidateMeta = context.textView("", 13f, ViewTheme.textSecondary)
    private val backdropStatus = context.textView("", 12f, ViewTheme.textTertiary)
    private val previewCounter = context.textView("", 12f, ViewTheme.textSecondary, Gravity.CENTER).apply {
        visibility = GONE
    }
    private val useButton = actionButton(context.getString(R.string.library_artwork_gallery_use)) { applySelection() }
    private val searchRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val filterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val main = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val previewColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val previewRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val railColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val railStack = FrameLayout(context)
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        visibility = GONE
    }
    // Two rows of small posters, scrolling horizontally together.
    private val railScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipToPadding = true
        setPadding(context.vDp(4), context.vDp(4), context.vDp(4), context.vDp(4))
    }
    private val railColumns = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
    }
    private val empty = context.textView(
        context.getString(R.string.library_artwork_gallery_empty), 14f, ViewTheme.textTertiary, Gravity.CENTER,
    ).apply { visibility = GONE }
    private val cards = mutableListOf<CandidateCard>()
    private var allCandidates: List<LibraryArtworkCandidate> = emptyList()
    private var filterBy: LibraryArtworkSource? = null
    // 换海报固定同步资料：有资料就覆盖，没有就只换图。
    private var selectedIndex = -1
    private var selectedCandidate: LibraryArtworkCandidate? = null
    private var portraitReady = false
    private var backdropState = ArtworkBackdropState.NONE
    private var backdropLoading = false
    private var largePreview = false
    private var pendingCandidateFocus = false
    private var lastRailHeight = 0
    private var canUseSelection = false

    init {
        setBackgroundColor(ViewTheme.backdrop)
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.setPadding(context.vDp(28), context.vDp(20), context.vDp(28), context.vDp(16))

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(headerTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(top, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(workName, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(2)
        })

        // A full-width search box dominates the dialog; cap it and keep the chips readable.
        searchRow.addView(searchInput, LinearLayout.LayoutParams(searchInputWidth(), context.vDp(44)))
        searchRow.addView(searchButton, LinearLayout.LayoutParams(context.vDp(104), context.vDp(44)).apply {
            leftMargin = context.vDp(8)
        })
        searchRow.addView(deleteCharButton, LinearLayout.LayoutParams(context.vDp(88), context.vDp(44)).apply {
            leftMargin = context.vDp(8)
        })
        searchRow.addView(clearQueryButton, LinearLayout.LayoutParams(context.vDp(88), context.vDp(44)).apply {
            leftMargin = context.vDp(8)
        })
        searchRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        content.addView(searchRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(8)
        })

        // 启用来源隐藏时不显示图源分类，避免让使用者看出海报来自哪个站点。
        if (!com.reelix.app.BuildConfig.PUBLIC_BUILD) {
            // 分类顺序跟随设置里的刮削顺序；全部固定最前，在线源固定最后。
            val ordered = (filterSources.ifEmpty {
                listOf(
                    LibraryArtworkSource.TMDB, LibraryArtworkSource.DOUBAN,
                    LibraryArtworkSource.STREAMX, LibraryArtworkSource.TVMAZE,
                )
            }).distinct()
            val chips = buildList {
                add(filterAll)
                ordered.forEach { source ->
                    add(actionButton(sourceLabel(source)) { setFilter(source) }.also { filterChips[source] = it })
                }
            }
            chips.forEachIndexed { index, chip ->
                filterRow.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(32)).apply {
                    leftMargin = if (index == 0) 0 else context.vDp(8)
                })
            }
        }
        filterRow.addView(status, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = context.vDp(12); gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        content.addView(filterRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(6)
        })

        // 同步资料开关已移除：固定同步，不再让用户二选一。

        // Portrait and landscape are shown side by side so neither gets clipped.
        previewRow.addView(portrait, LinearLayout.LayoutParams(context.vDp(132), context.vDp(198)))
        previewRow.addView(landscape, LinearLayout.LayoutParams(context.vDp(190), context.vDp(107)).apply {
            leftMargin = context.vDp(8)
        })
        previewColumn.addView(previewCounter, LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT))
        previewColumn.addView(previewRow, LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT))
        previewColumn.addView(candidateTitle, LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(6)
        })
        previewColumn.addView(candidateMeta, LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT))
        previewColumn.addView(backdropStatus, LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT))
        main.addView(previewColumn, LinearLayout.LayoutParams(context.vDp(340), LayoutParams.MATCH_PARENT))

        railStack.addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        railScroll.addView(railColumns, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        railStack.addView(railScroll, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
        ))
        railColumn.addView(railStack, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        main.addView(railColumn, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = context.vDp(16)
        })
        content.addView(main, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = context.vDp(8); bottomMargin = context.vDp(8)
        })

        actions.addView(useButton, LinearLayout.LayoutParams(context.vDp(180), context.vDp(40)))
        content.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        railScroll.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (largePreview) useButton.requestFocus()
                    else filterAll.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (largePreview) useButton.requestFocus() else false
                }
                else -> false
            }
        }
        searchInput.setOnEditorActionListener { _, _, _ -> onSearch(query()); true }
        useButton.setOnKeyListener { _, code, event ->
            if (largePreview && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    switchCandidate(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
                true
            } else false
        }
        main.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (largePreview && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                applyPreviewSizes()
            }
        }
        railScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height > 0 && height != lastRailHeight && height != oldBottom - oldTop) {
                lastRailHeight = height
                if (cards.isNotEmpty()) post { rebuildVisibleCards(focusFirst = false) }
            }
        }
        updateFilterStyles()
        updateUseState()
    }

    fun setWorkInfo(info: String) {
        workName.text = info
    }

    /**
     * Aligns the search button's left edge with the first candidate poster:
     * preview column (340dp) + rail gap (16dp) - search gap (8dp).
     */
    private fun searchInputWidth(): Int =
        context.vDp(PREVIEW_COLUMN_DP + RAIL_GAP_DP - SEARCH_GAP_DP)

    fun setSearchQuery(value: String) {
        searchInput.setText(value)
        searchInput.setSelection(searchInput.text.length)
    }

    /**
     * 删掉输入框最后一个字符，并保持焦点在输入框里。
     *
     * 用 `editable.delete` 而不是退格键事件：电视遥控器的 KEYCODE_DEL
     * 在部分机型的 EditText 上不生效，直接改文本最稳。
     */
    private fun deleteLastChar() {
        val text = searchInput.text ?: return
        val start = searchInput.selectionStart.coerceAtLeast(0)
        val end = searchInput.selectionEnd.coerceAtLeast(0)
        if (start != end) {
            // 有选中内容时删选区，符合常见编辑器习惯。
            text.delete(minOf(start, end), maxOf(start, end))
        } else if (start > 0) {
            text.delete(start - 1, start)
        }
        searchInput.setSelection(searchInput.text.length)
    }

    fun setSearching(message: String) {
        status.text = message
    }

    fun submit(candidates: List<LibraryArtworkCandidate>, message: String, focusFirst: Boolean = false) {
        allCandidates = candidates
        if (focusFirst) pendingCandidateFocus = true
        val visible = visibleCandidates()
        selectedIndex = -1
        selectedCandidate = null
        portraitReady = false
        backdropState = ArtworkBackdropState.NONE
        backdropLoading = false
        status.text = message.ifBlank { context.getString(R.string.library_artwork_gallery_count, visible.size) }
        // Cards are rebuilt below so a plain LinearLayout rail always shows every candidate.
        if (visible.isEmpty()) {
            status.text = context.getString(R.string.library_artwork_gallery_empty)
            empty.visibility = VISIBLE
            railScroll.visibility = GONE
            clearPreview()
        } else {
            empty.visibility = GONE
            railScroll.visibility = VISIBLE
            rebuildVisibleCards(focusFirst = pendingCandidateFocus)
        }
        updateUseState()
    }

    fun focusCandidates() {
        pendingCandidateFocus = true
        railScroll.post {
            if (cards.isNotEmpty()) {
                pendingCandidateFocus = false
                focusFirstCandidate()
            } else {
                filterAll.requestFocus()
            }
        }
    }

    fun cancelPendingCandidateFocus() {
        pendingCandidateFocus = false
    }

    fun handleBack(): Boolean {
        if (!largePreview) {
            onClose()
            return true
        }
        exitLargePreview()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (largePreview && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) exitLargePreview()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun query(): String = searchInput.text?.toString()?.trim().orEmpty()

    fun disposeImages() {
        backdrop.dispose()
        portrait.dispose()
        landscape.dispose()
    }

    private fun focusFirstCandidate() {
        val first = cards.firstOrNull() ?: return
        railScroll.smoothScrollTo(0, 0)
        first.requestFocus()
    }

    private fun rebuildVisibleCards(focusFirst: Boolean) {
        val visible = visibleCandidates()
        if (visible.isEmpty()) return
        val previousCandidate = selectedCandidate
        val cell = artworkRailCellSize(
            railHeightPx = (railScroll.height - railScroll.paddingTop - railScroll.paddingBottom)
                .takeIf { it > 0 } ?: context.vDp(350),
            gapPx = context.vDp(6),
            labelHeightPx = context.vDp(22),
        )
        rebuildCards(visible, cell)
        selectedIndex = -1
        select(visible.indexOf(previousCandidate).takeIf { it >= 0 } ?: 0)
        if (focusFirst) {
            pendingCandidateFocus = false
            railScroll.post { if (isAttachedToWindow) focusFirstCandidate() }
        }
    }

    private fun clearPreview() {
        portrait.dispose()
        landscape.dispose()
        portrait.setImageDrawable(null)
        landscape.setImageDrawable(null)
        candidateTitle.text = ""
        candidateMeta.text = ""
        backdropStatus.text = ""
    }

    private fun visibleCandidates(): List<LibraryArtworkCandidate> =
        allCandidates.filter { filterBy == null || it.source == filterBy }

    private fun setFilter(next: LibraryArtworkSource?) {
        if (filterBy == next) return
        pendingCandidateFocus = false
        filterBy = next
        updateFilterStyles()
        submit(allCandidates, context.getString(R.string.library_artwork_gallery_count, visibleCandidates().size))
    }

    private fun select(index: Int) {
        val candidate = visibleCandidates().getOrNull(index) ?: return
        if (selectedIndex == index && selectedCandidate == candidate) return
        selectedIndex = index
        selectedCandidate = candidate
        cards.forEachIndexed { i, card -> card.setSelectedRing(i == index) }
        candidateTitle.text = listOfNotNull(candidate.title, candidate.year?.toString()).joinToString(" · ")
        candidateMeta.text = listOfNotNull(
            when (candidate.kind) {
                com.reelix.app.data.model.MediaKind.MOVIE -> context.getString(R.string.library_artwork_movie)
                com.reelix.app.data.model.MediaKind.SERIES -> context.getString(R.string.library_artwork_series)
                else -> null
            },
            when (candidate.source) {
                LibraryArtworkSource.TMDB -> context.getString(R.string.library_artwork_tmdb)
                LibraryArtworkSource.DOUBAN -> context.getString(R.string.library_artwork_douban)
                LibraryArtworkSource.ONLINE -> candidate.siteName?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.library_artwork_online)
                LibraryArtworkSource.STREAMX -> context.getString(R.string.library_artwork_streamx)
                LibraryArtworkSource.TVMAZE -> context.getString(R.string.library_artwork_tvmaze)
                null -> null
            },
        ).joinToString(" · ")
        portraitReady = false
        portrait.dispose()
        portrait.setImageDrawable(null)
        portrait.load(candidate.poster) {
            size(400, 600)
            crossfade(false)
            listener(
                onSuccess = { _, _ -> portraitReady = true; updateUseState() },
                onError = { _, _ -> portraitReady = false; updateUseState() },
            )
        }
        backdropState = artworkBackdropState(candidate.backdrop, null)
        backdropLoading = !candidate.backdrop.isNullOrBlank()
        landscape.dispose()
        landscape.setImageDrawable(null)
        if (candidate.backdrop.isNullOrBlank()) {
            backdropStatus.text = context.getString(R.string.library_artwork_portrait_only)
        } else {
            backdropStatus.text = context.getString(R.string.library_artwork_backdrop_pending)
            landscape.load(candidate.backdrop) {
                size(1280, 720)
                crossfade(false)
                listener(
                    onSuccess = { _, result ->
                        backdropState = artworkBackdropState(
                            candidate.backdrop, true,
                            result.drawable.intrinsicWidth, result.drawable.intrinsicHeight,
                        )
                        backdropLoading = false
                        backdropStatus.text = context.getString(when (backdropState) {
                            ArtworkBackdropState.LOADED -> R.string.library_artwork_gallery_backdrop_available
                            else -> R.string.library_artwork_gallery_backdrop_failed
                        })
                        updateUseState()
                    },
                    onError = { _, _ ->
                        backdropState = ArtworkBackdropState.FAILED
                        backdropLoading = false
                        backdropStatus.text = context.getString(R.string.library_artwork_gallery_backdrop_failed)
                        updateUseState()
                    },
                )
            }
        }
        backdrop.dispose()
        backdrop.setImageDrawable(null)
        if (!candidate.backdrop.isNullOrBlank()) {
            backdrop.load(candidate.backdrop) {
                size(1280, 720)
                crossfade(false)
            }
        }
        if (largePreview) {
            cards.getOrNull(index)?.let {
                useButton.nextFocusLeftId = it.id
            }
        }
        updatePreviewCounter()
        updateUseState()
    }

    private fun switchCandidate(delta: Int) {
        val visible = visibleCandidates()
        if (visible.size < 2 || selectedIndex !in visible.indices) return
        val next = Math.floorMod(selectedIndex + delta, visible.size)
        select(next)
    }

    private fun updatePreviewCounter() {
        val candidate = selectedCandidate
        if (!largePreview || candidate == null) {
            previewCounter.visibility = GONE
            return
        }
        val visible = visibleCandidates()
        previewCounter.text = listOfNotNull(
            context.getString(
                R.string.library_artwork_gallery_position,
                (selectedIndex + 1).coerceAtLeast(1),
                visible.size,
            ),
            sourceLabel(candidate).takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        previewCounter.visibility = VISIBLE
    }

    private fun sourceLabel(candidate: LibraryArtworkCandidate): String = when (candidate.source) {
        LibraryArtworkSource.TMDB -> "TMDB"
        LibraryArtworkSource.DOUBAN -> context.getString(R.string.library_artwork_douban)
        LibraryArtworkSource.ONLINE -> candidate.siteName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.library_artwork_online)
        LibraryArtworkSource.STREAMX -> context.getString(R.string.library_artwork_streamx)
        LibraryArtworkSource.TVMAZE -> context.getString(R.string.library_artwork_tvmaze)
        null -> ""
    }

    private fun applySelection() {
        if (!canUseSelection) return
        val candidate = selectedCandidate ?: return
        val confirmed = candidate.copy(
            backdrop = candidate.backdrop?.takeIf { backdropState == ArtworkBackdropState.LOADED },
        )
        onApply(ArtworkGallerySelection(confirmed))
    }

    private fun updateUseState() {
        // 有作品身份就同步资料，没有就只换图，不因此禁用“使用”。
        val identityReady = true
        canUseSelection = portraitReady && identityReady && !backdropLoading
        useButton.alpha = if (canUseSelection) 1f else 0.45f
    }

    private fun updateFilterStyles() {
        styleSegment(filterAll, filterBy == null)
        filterChips.forEach { (source, chip) -> styleSegment(chip, filterBy == source) }
    }

    private fun sourceLabel(source: LibraryArtworkSource): String = when (source) {
        LibraryArtworkSource.TMDB -> context.getString(R.string.library_artwork_tmdb)
        LibraryArtworkSource.DOUBAN -> context.getString(R.string.library_artwork_douban)
        LibraryArtworkSource.STREAMX -> context.getString(R.string.library_artwork_streamx)
        LibraryArtworkSource.TVMAZE -> context.getString(R.string.library_artwork_tvmaze)
        LibraryArtworkSource.ONLINE -> context.getString(R.string.library_artwork_online)
    }

    private fun styleSegment(view: TextView, active: Boolean) {
        view.tag = active
        // Focus and selection are separate: keep the focus ring visible while selected.
        val focused = view.hasFocus()
        view.background = roundedBackground(
            if (focused) Color.WHITE else if (active) ViewTheme.accent else ViewTheme.surfaceElevated,
            context.vDp(6),
            if (focused) context.vDp(3) else if (active) context.vDp(2) else 0,
            if (focused) ViewTheme.focusRing else ViewTheme.focusRing,
        )
        view.setTextColor(if (focused || active) Color.BLACK else ViewTheme.textPrimary)
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        context.textView(label, 13f, ViewTheme.textPrimary, Gravity.CENTER).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(context.vDp(12), context.vDp(6), context.vDp(12), context.vDp(6))
            background = focusStateBackground(
                ViewTheme.surfaceElevated, ViewTheme.surfaceHigh, context.vDp(6),
                context.vDp(2), ViewTheme.focusRing,
            )
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, _ -> styleSegment(this, this.tag == true) }
        }

    private fun openLargePreview(index: Int) {
        if (cards.getOrNull(index) == null) return
        select(index)
        largePreview = true
        applyPreviewStage()
        useButton.post {
            if (largePreview && !useButton.requestFocus()) {
                useButton.postDelayed({ if (largePreview) useButton.requestFocus() }, 50)
            }
        }
    }

    private fun exitLargePreview() {
        if (!largePreview) return
        largePreview = false
        applyPreviewStage()
        cards.getOrNull(selectedIndex)?.let { card ->
            card.post { if (card.isAttachedToWindow) card.requestFocus() }
        }
    }

    private fun applyPreviewStage() {
        searchRow.visibility = if (largePreview) GONE else VISIBLE
        filterRow.visibility = if (largePreview) GONE else VISIBLE
        railColumn.visibility = if (largePreview) GONE else VISIBLE
        actions.visibility = if (largePreview) VISIBLE else GONE
        previewCounter.visibility = if (largePreview) VISIBLE else GONE
        previewColumn.gravity = if (largePreview) Gravity.CENTER_HORIZONTAL else Gravity.START
        applyPreviewSizes()
        updatePreviewCounter()
    }

    private fun applyPreviewSizes() {
        if (!largePreview) {
            portrait.layoutParams = LinearLayout.LayoutParams(context.vDp(132), context.vDp(198))
            landscape.layoutParams = LinearLayout.LayoutParams(context.vDp(190), context.vDp(107)).apply {
                leftMargin = context.vDp(8)
            }
            previewRow.layoutParams = LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT)
            previewColumn.layoutParams = LinearLayout.LayoutParams(context.vDp(340), LayoutParams.MATCH_PARENT)
            previewCounter.layoutParams = LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT)
            candidateTitle.layoutParams = LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT)
                .apply { topMargin = context.vDp(6) }
            candidateMeta.layoutParams = LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT)
            backdropStatus.layoutParams = LinearLayout.LayoutParams(context.vDp(340), LayoutParams.WRAP_CONTENT)
            candidateTitle.gravity = Gravity.START
            candidateMeta.gravity = Gravity.START
            backdropStatus.gravity = Gravity.START
            return
        }
        content.post {
            if (!largePreview || main.width <= 0 || main.height <= 0) return@post
            val textHeight = context.vDp(22) + candidateTitle.height + candidateMeta.height + backdropStatus.height
            val size = artworkLargePreviewSize(
                mainWidthPx = main.width,
                mainHeightPx = main.height,
                textHeightPx = textHeight + context.vDp(8),
                gapPx = context.vDp(8),
                maxColumnRatio = 0.94f,
            )
            portrait.layoutParams = LinearLayout.LayoutParams(size.portraitWidthPx, size.portraitHeightPx)
            landscape.layoutParams = LinearLayout.LayoutParams(size.landscapeWidthPx, size.landscapeHeightPx).apply {
                leftMargin = context.vDp(8)
            }
            previewRow.layoutParams = LinearLayout.LayoutParams(size.columnWidthPx, LayoutParams.WRAP_CONTENT)
            previewColumn.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            previewCounter.layoutParams = LinearLayout.LayoutParams(size.columnWidthPx, LayoutParams.WRAP_CONTENT)
            candidateTitle.layoutParams = LinearLayout.LayoutParams(size.columnWidthPx, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = context.vDp(6) }
            candidateMeta.layoutParams = LinearLayout.LayoutParams(size.columnWidthPx, LayoutParams.WRAP_CONTENT)
            backdropStatus.layoutParams = LinearLayout.LayoutParams(size.columnWidthPx, LayoutParams.WRAP_CONTENT)
            candidateTitle.gravity = Gravity.CENTER_HORIZONTAL
            candidateMeta.gravity = Gravity.CENTER_HORIZONTAL
            backdropStatus.gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun rebuildCards(items: List<LibraryArtworkCandidate>, cell: ArtworkRailCellSize) {
        railColumns.removeAllViews()
        cards.clear()
        // Two columns per page; each column stacks two small posters.
        items.chunked(2).forEach { pair ->
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            pair.forEachIndexed { offset, candidate ->
                val index = cards.size
                val card = CandidateCard(context, cell.imageHeightPx, cell.labelHeightPx)
                card.id = View.generateViewId()
                card.layoutParams = LinearLayout.LayoutParams(cell.widthPx, cell.heightPx).apply {
                    bottomMargin = if (offset == 0) context.vDp(6) else 0
                }
                card.onSelect = { select(index) }
                card.onClick = { openLargePreview(index) }
                card.bind(candidate, false, sourceLabel(candidate))
                column.addView(card)
                cards += card
            }
            railColumns.addView(column, LinearLayout.LayoutParams(cell.widthPx, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = context.vDp(8)
            })
        }
    }

    /** Fixed-size candidate with an explicit focus ring, independent of grid focus state. */
    private class CandidateCard(
        context: Context,
        private val imageHeight: Int,
        private val labelHeight: Int,
    ) : LinearLayout(context) {
        private val frame = FrameLayout(context)
        private val image = ImageView(context)
        private val letter = TextView(context)
        private val ring = View(context)
        private val badge = TextView(context)
        private val label = TextView(context)
        var onSelect: (() -> Unit)? = null
        var onClick: (() -> Unit)? = null

        init {
            orientation = VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS

            frame.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, imageHeight)
            image.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.background = GradientDrawable().apply {
                cornerRadius = context.vDp(8).toFloat()
                setColor(ViewTheme.posterPlaceholder)
            }
            image.clipToOutline = true
            image.outlineProvider = ViewOutlineProvider.BACKGROUND
            frame.addView(image)

            letter.layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letter.setTextColor(ViewTheme.textTertiary)
            frame.addView(letter)

            ring.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            ring.background = GradientDrawable().apply {
                cornerRadius = context.vDp(8).toFloat()
                setStroke(context.vDp(2), ViewTheme.focusRing)
                setColor(Color.TRANSPARENT)
            }
            ring.visibility = INVISIBLE
            frame.addView(ring)

            badge.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                leftMargin = context.vDp(6)
                topMargin = context.vDp(6)
                rightMargin = context.vDp(6)
            }
            badge.maxWidth = context.vDp(96)
            badge.maxLines = 1
            badge.ellipsize = TextUtils.TruncateAt.END
            badge.gravity = Gravity.CENTER
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            badge.setTextColor(Color.WHITE)
            badge.setPadding(context.vDp(6), context.vDp(2), context.vDp(6), context.vDp(2))
            badge.background = roundedBackground(0xCC000000.toInt(), context.vDp(4))
            badge.visibility = GONE
            frame.addView(badge)

            label.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, labelHeight)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            label.setTextColor(ViewTheme.textSecondary)
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER_VERTICAL

            addView(frame)
            addView(label)
            setOnClickListener { onClick?.invoke() }
            setOnFocusChangeListener { _, focused ->
                ring.visibility = if (focused) VISIBLE else INVISIBLE
                label.setTextColor(if (focused) Color.WHITE else ViewTheme.textSecondary)
                if (focused) onSelect?.invoke()
            }
        }

        fun bind(candidate: LibraryArtworkCandidate, highlighted: Boolean, sourceText: String) {
            label.text = listOfNotNull(candidate.title, candidate.year?.toString()).joinToString(" · ")
            badge.text = sourceText
            badge.visibility = if (sourceText.isBlank()) GONE else VISIBLE
            ring.visibility = if (hasFocus() || highlighted) VISIBLE else INVISIBLE
            letter.text = "加载中"
            letter.visibility = VISIBLE
            image.dispose()
            image.setImageDrawable(null)
            image.load(candidate.poster) {
                size(300, 450)
                crossfade(false)
                listener(
                    onSuccess = { _, _ -> letter.visibility = INVISIBLE },
                    onError = { _, _ ->
                        letter.text = "无海报"
                        letter.visibility = VISIBLE
                    },
                )
            }
        }

        fun setSelectedRing(selected: Boolean) {
            ring.visibility = if (hasFocus() || selected) VISIBLE else INVISIBLE
        }
    }

    private fun artworkKey(candidate: LibraryArtworkCandidate): String =
        "${candidate.identity}|${candidate.source}|${candidate.poster}|${candidate.title}|${candidate.year}"

    private companion object {
        const val PREVIEW_COLUMN_DP = 340
        const val RAIL_GAP_DP = 16
        const val SEARCH_GAP_DP = 8
    }
}
