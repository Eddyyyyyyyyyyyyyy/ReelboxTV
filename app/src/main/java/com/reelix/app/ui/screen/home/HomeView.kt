package com.reelix.app.ui.screen.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.load
import coil.size.Precision
import coil.size.Scale
import com.reelix.app.data.db.PlayProgressEntity
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.MediaKind
import com.reelix.app.data.image.LocalImageStore
import com.reelix.app.data.repo.toMediaItem
import com.reelix.app.ui.component.PageView
import com.reelix.app.ui.component.PosterGridView
import com.reelix.app.ui.component.PosterTile
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.vDp
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.R
import com.reelix.app.ui.theme.layout.*
import com.reelix.app.ui.component.HomeHistoryView

/**
 * 首页整页 —— Hero + 历史海报墙。
 *
 * Hero 和历史共用同一个页面根布局和焦点链：Hero DOWN 进第一张历史，
 * 历史第一行 UP 回 Hero；焦点进入历史时 Hero 上移，回到 Hero 时恢复完整首屏。
 */
class HomeView(
    context: Context,
    private val onPlay: (PlayProgressEntity) -> Unit,
    private val onOpenDetail: (PlayProgressEntity) -> Unit,
    private val onDelete: (String, (Boolean) -> Unit) -> Unit,
    private val onGoOnline: () -> Unit,
    private val onGoLibrary: () -> Unit,
    private val onExitTop: () -> Unit,
    private val browse: com.reelix.app.ui.component.PosterGridMemory = com.reelix.app.ui.component.PosterGridMemory(),
    private val theme: NativeThemeLayout? = null,
    private val onOpenLibraryItem: (MediaItem) -> Unit = {},
    initialColumns: Int = 6,
) : FrameLayout(context), PageView {

    private var themeBinding: ThemeLayoutBinding<HomeThemeState>? = null
    private var initialLoading = false
    private var posterColumns = initialColumns
    private var libraryItems = emptyList<HomeCollectionItem>()
    private val collectionMemory = browse.regions.getOrPut("collection-state") { com.reelix.app.ui.component.PosterGridMemory() }
    private val collectionFocus = browse.regions.getOrPut("collection-focus") { com.reelix.app.ui.component.PosterGridMemory() }
    private var collectionFilter = HomeCollectionFilter.entries.firstOrNull { it.name == collectionMemory.section } ?: HomeCollectionFilter.ALL
    private var collectionSort = HomeCollectionSort.entries.firstOrNull { it.name == collectionMemory.key } ?: HomeCollectionSort.ACTIVITY
    private var shownHistory = emptyList<PlayProgressEntity>()
    private var shownLibrary = emptyList<HomeCollectionItem>()
    private val hero = HeroView(
        context = context,
        onPlay = { history.firstOrNull()?.let(onPlay) },
        onDetail = { history.firstOrNull()?.let(onOpenDetail) },
        onExitTop = onExitTop,
    )
    private val content = LinearLayout(context)
    private val header = LinearLayout(context)
    private val headerTitle = context.textView("历史", 18f, ViewTheme.textPrimary)
    private val headerCount = context.textView("", 12f, ViewTheme.textTertiary)
    private val grid = HomeHistoryView(
        context = context,
        onClick = { tile -> handleClick(tile.key) },
        onLongClick = { _ -> enterDeleteMode() },
        onExitUp = {
            if (!deleteMode) {
                setHistoryMode(false)
                if (theme?.id == NativeThemeId.GALLERY) collectionHeader.focusSelected() else hero.focusPlay()
            }
        },
        canExitUp = { !deleteMode },
        onFocusedIndexChange = {
            if (theme == null) setHistoryMode(true) else browse.section = "history"
        },
        memory = browse,
        onExitDown = when (theme?.id) {
            NativeThemeId.GALLERY -> ({ focusRecent() })
            null -> null
            else -> ({ setHistoryMode(true) })
        },
        initialColumns = initialColumns,
    )
    private val recentTitle = context.textView(context.getString(R.string.theme_recent_added), 15.5f, ViewTheme.textPrimary)
    private val recentHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(recentTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    private val recent = HomeHistoryView(context, { tile ->
        shownLibrary.firstOrNull { it.item.id == tile.key }?.item?.let(onOpenLibraryItem)
    }, null, onExitUp = { if (shownHistory.isNotEmpty()) grid.restoreFocus(browse.index) else collectionHeader.focusSelected() },
        onFocusedIndexChange = { browse.section = "recent" },
        memory = browse.regions.getOrPut("recent") { com.reelix.app.ui.component.PosterGridMemory() },
        initialColumns = initialColumns)
    private val collectionHeader = HomeCollectionHeader(context,
        onFilter = { collectionFilter = it; collectionMemory.section = it.name; render(history, initialLoading) },
        onSort = { collectionSort = it; collectionMemory.key = it.name; render(history, initialLoading) },
        onFocused = { collectionFocus.key = it; setHistoryMode(false); browse.section = "collection" },
        onExitUp = { hero.focusPlay() }, onExitDown = { focusCollectionItems() })
    private val collectionEmptyButton = collectionLink(R.string.settings_cat_library) { onGoLibrary() }
    private val collectionEmpty = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(context.textView(context.getString(R.string.theme_collection_empty), 14f, ViewTheme.textSecondary))
        addView(collectionEmptyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.vDp(44)))
    }
    private val empty = LinearLayout(context)
    private val emptyText = context.textView(
        "还没有播放历史",
        20f,
        ViewTheme.textPrimary,
        Gravity.CENTER,
    )
    private val emptyOnline = heroButton("去在线", primary = true) { onGoOnline() }
    private val emptyLibrary = heroButton("去片库", primary = false) { onGoLibrary() }

    private var history: List<PlayProgressEntity> = emptyList()
    private var deleteMode = false
    private val pendingDeletes = mutableSetOf<String>()
    private var swallowBackUp = false
    /** 页面重建后用户是否已经自己按过键；按过就停止"返回焦点"的自动恢复。 */
    private var userKeySinceAttach = false
    private var historyMode = false
    private var animator: ValueAnimator? = null
    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        // 焦点离开整个首页（例如回到顶部导航）时，Hero 必须复位。
        // 只靠 Hero 按钮的 onFocus 不够：焦点可能从历史卡直接跳到 TopBar。
        if (newFocus != null && !isDescendant(newFocus)) {
            val remembered = browse.section
            setHistoryMode(false)
            browse.section = remembered
        }
    }

    init {
        setBackgroundColor(ViewTheme.backdrop)

        // ★ 冷启动不让历史网格抢焦点。
        //   Leanback 的网格布局完会自己 focus 第一项，`focusHome()` 抢回来 0.3 秒后
        //   又会被它夺走。这里先把子项聚焦能力关掉，等用户按「下」进入内容区
        //   （focusFirst）再打开。详见 HomeHistoryView.autoFocusEnabled。
        grid.autoFocusEnabled = false

        addView(
            hero,
            LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(420)),
        )

        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, context.vDp(100), 0, 0)
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(context.vDp(48), 0, context.vDp(48), 0)
        header.addView(
            headerTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        header.addView(
            headerCount,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = context.vDp(10) },
        )
        header.addView(
            View(context),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        content.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.vDp(40),
            ),
        )

        content.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER
        empty.visibility = GONE
        empty.addView(emptyText)
        empty.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(20)))
        val emptyButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        emptyButtons.addView(
            emptyOnline,
            LinearLayout.LayoutParams(context.vDp(160), context.vDp(48)),
        )
        emptyButtons.addView(View(context), LinearLayout.LayoutParams(context.vDp(12), 1))
        emptyButtons.addView(
            emptyLibrary,
            LinearLayout.LayoutParams(context.vDp(160), context.vDp(48)),
        )
        empty.addView(emptyButtons)
        listOf(emptyOnline, emptyLibrary).forEach { b ->
            b.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == KeyEvent.ACTION_DOWN
                ) {
                    onExitTop()
                    true
                } else {
                    false
                }
            }
        }
        addView(
            empty,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        hero.onFocusChanged = { focused -> if (focused) { setHistoryMode(false); browse.section = "hero" } }
        hero.onDownToHistory = { focusFirstHistory() }
        if (theme?.id == NativeThemeId.GALLERY) hero.onDownToHistory = { collectionHeader.focusSelected() }

        setHistoryMode(if (theme != null) browse.expanded else browse.section == "history", animate = false)
        if (theme != null) {
            val themeRoot = FrameLayout(context)
            val base = hero.themeSlots(themeRoot, header, grid, empty, grid::configure)
            val slots = base.copy(collectionHeader = collectionHeader, recentHeader = recentHeader,
                recentItems = recent, collectionEmpty = collectionEmpty, configureRecent = recent::configure,
                historyTitle = headerTitle, recentTitle = recentTitle)
            removeAllViews()
            addView(themeRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            header.setPadding(0, 0, 0, 0)
            headerCount.visibility = GONE
            headerTitle.setText(if (theme.id == NativeThemeId.TOPBAR) R.string.theme_continue_watching else R.string.theme_recent_watched)
            themeBinding = theme.installHome(slots)
            grid.setPalette(theme.palette)
            recent.setPalette(theme.palette)
            hero.setTheme(theme.palette)
            updateTheme()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
    }

    override fun onDetachedFromWindow() {
        deleteMode = false
        pendingDeletes.clear()
        themeBinding?.dispose()
        animator?.cancel()
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        super.onDetachedFromWindow()
    }

    private fun isDescendant(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === this) return true
            current = current.parent as? View
        }
        return false
    }

    fun setAppearance(preferences: com.reelix.app.data.settings.AppearancePreferences) {
        val columns = preferences.normalized().posterColumns
        val resized = posterColumns != columns
        posterColumns = columns
        grid.setPosterSize(columns)
        recent.setPosterSize(columns)
        collectionEmptyButton.visibility = if ("LIBRARY" in preferences.visible) VISIBLE else GONE
        emptyOnline.visibility = if ("ONLINE" in preferences.visible) VISIBLE else GONE
        emptyLibrary.visibility = if ("LIBRARY" in preferences.visible) VISIBLE else GONE
        if (resized) updateTheme()
    }

    private fun focusEmpty(): Boolean = when {
        emptyOnline.visibility == VISIBLE -> emptyOnline.requestFocus()
        emptyLibrary.visibility == VISIBLE -> emptyLibrary.requestFocus()
        else -> { onExitTop(); true }
    }

    fun render(history: List<PlayProgressEntity>, initialLoading: Boolean, collection: List<HomeCollectionItem> = libraryItems) {
        this.initialLoading = initialLoading
        libraryItems = collection
        val effectiveHistory = history.map { entry ->
            val item = entry.toMediaItem()
            entry.copy(
                posterUrl = item.poster,
                posterRes = item.posterRes,
                backdropUrl = item.backdrop,
            )
        }
        val previous = this.history
        val previousPosition = grid.currentPosition().coerceAtLeast(0)
        val focusedKey = previous.getOrNull(previousPosition)?.mediaId
        val restoreAfterDelete = deleteMode && grid.hasFocus() && focusedKey != null && history.none { it.mediaId == focusedKey }
        this.history = effectiveHistory
        val gallery = theme?.id == NativeThemeId.GALLERY
        shownHistory = if (!gallery) effectiveHistory else if (collectionFilter == HomeCollectionFilter.RECENT) emptyList()
            else effectiveHistory.filter { homeCollectionMatches(MediaKind.fromLabel(it.kindName), collectionFilter) }
        shownLibrary = if (gallery) homeCollectionItems(libraryItems, collectionFilter, collectionSort, effectiveHistory.associate { it.mediaId to it.updatedAt }) else emptyList()
        collectionHeader.bind(collectionFilter, collectionSort)
        grid.visibility = if (shownHistory.isEmpty()) GONE else VISIBLE
        header.visibility = grid.visibility
        recentHeader.visibility = if (shownLibrary.isEmpty()) GONE else VISIBLE
        recent.visibility = recentHeader.visibility
        collectionEmpty.visibility = if (shownLibrary.isEmpty()) VISIBLE else GONE
        if (gallery) recent.submit(shownLibrary.map { entry -> PosterTile(entry.item.id, entry.item.title,
            entry.item.poster, entry.item.posterRes, progress = entry.item.progress,
            backdropUrl = entry.item.backdrop, subtitle = listOfNotNull(entry.item.year?.toString(),
                entry.item.kind.label.takeIf { it.isNotBlank() }).joinToString(" · ")) })
        if (effectiveHistory.isEmpty()) {
            grid.submit(emptyList())
            hero.visibility = GONE
            content.visibility = GONE
            empty.visibility = if (gallery && libraryItems.isNotEmpty()) GONE else VISIBLE
            emptyText.text = if (deleteMode) context.getString(R.string.history_delete_empty)
                else if (initialLoading) "正在读取播放历史…" else "还没有播放历史"
            if (deleteMode) emptyOnline.post {
                if (gallery && libraryItems.isNotEmpty()) collectionHeader.focusSelected() else focusEmpty()
            }
            updateTheme()
            return
        }

        hero.visibility = VISIBLE
        content.visibility = VISIBLE
        empty.visibility = GONE

        val heroEntry = effectiveHistory.first()
        hero.render(heroEntry)
        if (theme?.id == NativeThemeId.SIDEBAR) hero.hideResumeExtras()
        headerCount.text = "${effectiveHistory.size} 部"

        grid.submit(
            shownHistory.map { entry ->
                PosterTile(
                    key = entry.mediaId,
                    title = entry.title,
                    posterUrl = entry.posterUrl,
                    posterRes = entry.posterRes,
                    progress = entry.progress,
                    armed = deleteMode,
                    backdropUrl = entry.backdropUrl,
                    subtitle = if (theme?.id == NativeThemeId.GALLERY) listOfNotNull(entry.year?.toString(),
                        MediaKind.fromLabel(entry.kindName).label.takeIf { it.isNotBlank() }).joinToString(" · ")
                        else homePlaybackClock(entry.positionMs),
                )
            }
        ) {
            if (restoreAfterDelete && deleteMode && isAttachedToWindow) {
                grid.focusPosition(previousPosition.coerceAtMost(effectiveHistory.lastIndex))
            }
        }
        updateTheme()
    }

    /** The region that owned focus when the user opened a detail page. */
    fun focusRegion(): String = when (browse.section) {
        "history", "recent" -> browse.section
        else -> "hero"
    }

    /** Restore a history-poster entry even when the recreated page briefly focused Hero. */
    fun restoreHistoryFocus(index: Int) {
        browse.section = "history"
        if (theme != null) browse.expanded = true
        grid.autoFocusEnabled = true
        setHistoryMode(true, animate = false)
        grid.restoreFocus(index)
    }

    private fun updateTheme() {
        val entry = history.firstOrNull()
        themeBinding?.update(HomeThemeState(history.isNotEmpty(), initialLoading, historyMode,
            !entry?.backdropUrl.isNullOrBlank(), !entry?.posterUrl.isNullOrBlank() || !entry?.posterRes.isNullOrBlank(),
            libraryItems.isNotEmpty(), posterColumns))
        theme?.let { hero.setTheme(it.palette) }
    }

    private fun handleClick(key: String) {
        val entry = history.firstOrNull { it.mediaId == key } ?: return
        if (deleteMode) {
            if (!pendingDeletes.add(key)) return
            onDelete(key) { success ->
                pendingDeletes.remove(key)
                if (!success && isAttachedToWindow) android.widget.Toast.makeText(context,
                    R.string.history_delete_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            // A poster click is always a history-origin navigation, even if a
            // transient focus event briefly reported the Hero region.
            browse.section = "history"
            onPlay(entry)
        }
    }

    private fun enterDeleteMode() {
        deleteMode = true
        render(history, initialLoading = false)
    }

    /** Home owns deletion mode before the root activity considers exiting the application. */
    fun handleBack(): Boolean {
        if (!deleteMode) return false
        deleteMode = false
        render(history, initialLoading = false)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 用户一旦自己按了方向键，就不要再让"返回焦点"的恢复循环抢焦点。
        if (event.action == KeyEvent.ACTION_DOWN) userKeySinceAttach = true
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && (deleteMode || swallowBackUp)) {
                if (event.repeatCount == 0 && deleteMode) handleBack()
                swallowBackUp = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && swallowBackUp) {
                swallowBackUp = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setHistoryMode(enabled: Boolean, animate: Boolean = true) {
        if (theme == null) browse.section = if (enabled) "history" else "hero" else browse.expanded = enabled
        if (historyMode == enabled && animate) return
        historyMode = enabled
        if (themeBinding != null) {
            if (!enabled) grid.cancelFocusRequest()
            updateTheme()
            return
        }
        val heroTarget = if (enabled) -context.vDp(420).toFloat() else 0f
        val contentTarget = if (enabled) 0f else context.vDp(320).toFloat()

        animator?.cancel()
        if (!animate) {
            hero.translationY = heroTarget
            content.translationY = contentTarget
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            val heroStart = hero.translationY
            val contentStart = content.translationY
            addUpdateListener {
                val f = it.animatedFraction
                hero.translationY = heroStart + (heroTarget - heroStart) * f
                content.translationY = contentStart + (contentTarget - contentStart) * f
            }
            start()
        }
    }

    private fun focusFirstHistory() {
        if (history.isEmpty()) return
        grid.autoFocusEnabled = true
        if (themeBinding != null) {
            grid.post { if (isAttachedToWindow) focusHistoryTile(0, attempt = 0) }
        } else focusHistoryTile(0, attempt = 0)
    }

    /**
     * 聚焦历史列表第 [index] 张卡；列表刚重建时卡片可能还没布局，
     * 单次请求会静默失败（表现是 DOWN 没反应），这里最多重试约 1 秒。
     */
    private fun focusHistoryTile(index: Int, attempt: Int) {
        if (!isAttachedToWindow) return
        grid.focusPosition(index)
        if (grid.hasFocus() || attempt >= 12) return
        grid.postDelayed({ if (isAttachedToWindow) focusHistoryTile(index, attempt + 1) }, 80L)
    }

    private fun focusRecent() {
        if (shownLibrary.isNotEmpty()) recent.restoreFocus(0) else collectionEmptyButton.requestFocus()
    }

    private fun focusCollectionItems() {
        if (shownHistory.isNotEmpty()) {
            grid.post { if (isAttachedToWindow) grid.restoreFocus(browse.index) }
        } else focusRecent()
    }

    private fun collectionLink(label: Int, action: () -> Unit): TextView =
        context.textView(context.getString(label), 10.5f, ViewTheme.textSecondary, Gravity.CENTER).apply {
            id = View.generateViewId()
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                view.background = roundedBackground(Color.TRANSPARENT, context.vDp(4),
                    if (focused) context.vDp(2) else 0, theme?.palette?.focusRing ?: ViewTheme.focusRing)
            }
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusCollectionItems()
                        else if (theme?.id == NativeThemeId.GALLERY) collectionHeader.focusSelected() else hero.focusPlay()
                    }
                    true
                } else false
            }
        }

    private fun heroButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        context.textView(
            text = label,
            sizeSp = 14f,
            color = if (primary) Color.BLACK else ViewTheme.textPrimary,
            gravity = Gravity.CENTER,
        ).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(context.vDp(18), 0, context.vDp(18), 0)
            // ★ 2026-11-09：焦点态背景改成预建的两份 drawable（StateListDrawable 自动切）。
            //
            //   原来在 onFocusChange 里 `v.background = roundedBackground(...)`，
            //   而 setBackground() 内部会 requestLayout() —— 焦点每移动一次就重新测量整棵树，
            //   长按方向键时明显不跟手。现在回调里一行都不用写。
            background = focusStateBackground(
                normalColor = if (primary) Color.WHITE else 0x1AFFFFFF,
                focusColor = if (primary) ViewTheme.accent else 0x33FFFFFF,
                radiusPx = context.vDp(8),
            )
            setOnClickListener { onClick() }
        }

    override fun focusFirst(): Boolean {
        // 用户主动进入内容区了，从这里开始允许网格接管焦点。
        // 冷启动阶段这个开关是 false，所以数据加载完成后网格不会把焦点从导航栏抢走。
        grid.autoFocusEnabled = true

        return when {
            theme?.id == NativeThemeId.GALLERY && browse.section == "recent" && shownLibrary.isNotEmpty() -> {
                recent.restoreFocus(0); true
            }
            theme?.id == NativeThemeId.GALLERY && (browse.section == "collection" || history.isEmpty() && libraryItems.isNotEmpty()) ->
                collectionHeader.restoreFocus(collectionFocus.key)
            history.isEmpty() -> focusEmpty()
            browse.section == "history" -> { grid.restoreFocus(browse.index); true }
            else -> hero.focusPlay()
        }
    }

    override fun saveFocusIndex(): Int = if (browse.section == "recent") recent.currentPosition().also { recent.savePosition() }
        else grid.currentPosition().also { grid.savePosition() }

    override fun restoreFocus(index: Int) {
        when (browse.section) {
            "recent" -> recent.restoreFocus(index)
            "collection" -> collectionHeader.restoreFocus(collectionFocus.key)
            "hero" -> hero.focusPlay()
            else -> grid.restoreFocus(index)
        }
    }

    /**
     * 恢复焦点并返回"是否真的落到目标区域"。
     *
     * 详情页返回时 HomeView 是重建的，hero/网格可能还没挂到窗口，
     * 单次 requestFocus 会静默失败（表现就是焦点消失）。调用方据此重试。
     */
    fun restoreFocusChecked(index: Int, region: String): Boolean = when {
        // 用户已经自己操作了：恢复循环立即收手，否则会把焦点从列表抢回 hero。
        userKeySinceAttach -> true
        else -> restoreRegionFocus(index, region)
    }

    private fun restoreRegionFocus(index: Int, region: String): Boolean = when (region) {
        "history" -> {
            if (grid.hasFocus()) true
            else {
                restoreHistoryFocus(index)
                grid.hasFocus()
            }
        }
        "recent" -> {
            if (recent.hasFocus()) true
            else {
                recent.restoreFocus(index)
                recent.hasFocus()
            }
        }
        "collection" -> {
            if (collectionHeader.hasFocus()) true
            else {
                collectionHeader.restoreFocus(collectionFocus.key)
                collectionHeader.hasFocus()
            }
        }
        else -> {
            // 回到 hero 时也要允许 DOWN 进入历史列表（冷启动期间这个开关是关的）。
            grid.autoFocusEnabled = true
            if (hero.hasFocus()) true else hero.focusPlay()
        }
    }
}

/**
 * Hero —— 单屏海报 + 渐变 + 文案 + 继续观看入口。
 */
private class HeroView(
    context: Context,
    private val onPlay: () -> Unit,
    private val onDetail: () -> Unit,
    private val onExitTop: () -> Unit,
) : FrameLayout(context) {

    private val image = TopCropImageView(context)
    private val foregroundPoster = ImageView(context)
    private val gradient = HeroGradientView(context)
    private val tag = context.textView("", 11f, ViewTheme.textSecondary)
    private val meta = context.textView("", 11f, ViewTheme.textSecondary)
    private val title = context.textView("", 36f, ViewTheme.textPrimary)
    private val original = context.textView("", 13f, ViewTheme.textTertiary)
    private val overview = context.textView("", 13f, ViewTheme.textSecondary)
    private val play: TextView
    private val detail: TextView
    private val resumeLabel = context.textView(context.getString(R.string.theme_resume_label), 12.5f, ViewTheme.textPrimary)
    private val resumeTime = context.textView("", 10.5f, ViewTheme.textSecondary)
    private var themePalette: NativeThemePalette? = null
    private val themeProgress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        minimumHeight = context.vDp(4)
        isFocusable = false
        progressTintList = android.content.res.ColorStateList.valueOf(0xFF4C7DFF.toInt())
    }

    var onFocusChanged: ((Boolean) -> Unit)? = null
    var onDownToHistory: (() -> Unit)? = null

    init {
        setBackgroundColor(ViewTheme.backdrop)

        image.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(image)

        foregroundPoster.layoutParams = LayoutParams(context.vDp(190), context.vDp(285), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = context.vDp(110)
        }
        foregroundPoster.scaleType = ImageView.ScaleType.CENTER_CROP
        foregroundPoster.background = roundedBackground(
            ViewTheme.posterPlaceholder,
            context.vDp(ViewTheme.radiusPoster),
        )
        foregroundPoster.clipToOutline = true
        foregroundPoster.outlineProvider = ViewOutlineProvider.BACKGROUND
        foregroundPoster.visibility = GONE
        foregroundPoster.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(foregroundPoster)

        gradient.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(gradient)

        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                (resources.displayMetrics.widthPixels * 0.52f).toInt(),
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                leftMargin = context.vDp(48)
                bottomMargin = context.vDp(40)
            }
        }

        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(tag)
        metaRow.addView(View(context), LinearLayout.LayoutParams(context.vDp(10), 1))
        metaRow.addView(meta)
        textBlock.addView(metaRow)
        textBlock.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(14)))

        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        textBlock.addView(title)

        original.maxLines = 1
        original.ellipsize = TextUtils.TruncateAt.END
        textBlock.addView(original)

        overview.maxLines = 2
        overview.ellipsize = TextUtils.TruncateAt.END
        overview.setLineSpacing(context.vDp(4).toFloat(), 1f)
        textBlock.addView(overview)

        textBlock.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(20)))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        play = heroButton(context.getString(R.string.action_resume), primary = true) { onPlay() }
        detail = heroButton("详情", primary = false) { onDetail() }
        buttons.addView(play, LinearLayout.LayoutParams(context.vDp(150), context.vDp(48)))
        // 2026-11-20：Hero 只保留“继续观看”入口。Hero 本身来自历史记录，
        // “详情”与“播放”最终都会进详情页，两个按钮语义重复。
        detail.visibility = GONE
        textBlock.addView(buttons)

        addView(textBlock)

        listOf(play).forEach { b ->
            b.setOnKeyListener { _, keyCode, event ->
                when {
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        event.action == KeyEvent.ACTION_DOWN -> {
                        onDownToHistory?.invoke()
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                        event.action == KeyEvent.ACTION_DOWN -> {
                        onExitTop()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    fun render(entry: PlayProgressEntity) {
        val item = entry.toMediaItem()
        tag.text = item.kind.label
        tag.background = roundedBackground(0x1AFFFFFF, context.vDp(4))
        tag.setPadding(context.vDp(8), context.vDp(3), context.vDp(8), context.vDp(3))
        meta.text = item.metaLine
        if (themePalette != null) meta.text = listOfNotNull(entry.year?.toString(),
            MediaKind.fromLabel(entry.kindName).label.takeIf { it.isNotBlank() },
            entry.durationMs.takeIf { it > 0 }?.let(::homePlaybackClock)).joinToString("  ")
        resumeTime.text = if (entry.durationMs > 0) context.getString(R.string.theme_known_duration,
            homePlaybackClock(entry.positionMs), homePlaybackClock(entry.durationMs)) else homePlaybackClock(entry.positionMs)
        meta.visibility = if (meta.text.isNullOrBlank()) GONE else VISIBLE
        themeProgress.progress = (entry.progress * 1000).toInt()
        themeProgress.visibility = if (entry.progress > 0f) VISIBLE else GONE
        title.text = item.title
        original.text = item.originalTitle.orEmpty()
        original.visibility = if (item.originalTitle.isNullOrBlank()) GONE else VISIBLE
        overview.text = item.overview.orEmpty()
        overview.visibility = if (item.overview.isNullOrBlank()) GONE else VISIBLE

        play.text = when {
            entry.finished -> "重新播放"
            entry.positionMs > 0 -> "继续观看"
            else -> "立即播放"
        }

        val backdrop = entry.backdropUrl?.takeIf { it.isNotBlank() }
        val poster = entry.posterUrl?.takeIf { it.isNotBlank() }
        val localPoster = LocalImageStore.id(context, entry.posterRes)
        val hasBackdrop = !backdrop.isNullOrBlank()

        image.dispose()
        foregroundPoster.dispose()
        if (hasBackdrop) {
            image.load(backdrop) {
                size(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                precision(Precision.INEXACT)
                scale(Scale.FILL)
                crossfade(false)
            }
            foregroundPoster.visibility = GONE
        } else if (localPoster != 0) {
            image.setImageResource(localPoster)
            foregroundPoster.setImageResource(localPoster)
            foregroundPoster.visibility = VISIBLE
        } else if (!poster.isNullOrBlank()) {
            image.load(poster) {
                precision(Precision.INEXACT)
                scale(Scale.FILL)
                crossfade(false)
            }
            foregroundPoster.load(poster) {
                precision(Precision.INEXACT)
                scale(Scale.FILL)
                crossfade(false)
            }
            foregroundPoster.visibility = VISIBLE
        } else {
            image.setImageDrawable(null)
            foregroundPoster.visibility = GONE
        }
    }

    fun focusPlay(): Boolean = play.requestFocus()

    fun hideResumeExtras() {
        resumeLabel.visibility = GONE
        resumeTime.visibility = GONE
        themeProgress.visibility = GONE
    }

    fun themeSlots(root: FrameLayout, header: View, history: View, empty: View,
        configure: (HistoryPresentation) -> Unit): HomeThemeSlots {
        listOf(image, foregroundPoster, title, meta, overview, resumeLabel, resumeTime, play, detail, themeProgress, header, history, empty)
            .forEach { it.detachForTheme() }
        return HomeThemeSlots(root, image, foregroundPoster, title, meta, play, detail,
            themeProgress, header, history, empty, configure, overview, resumeLabel, resumeTime)
    }

    fun setTheme(palette: NativeThemePalette) {
        themePalette = palette
        val playStyle = palette.playButtonStyle()
        themeProgress.progressTintList = android.content.res.ColorStateList.valueOf(playStyle.progressFill)
        applyPlayButton(play, playStyle)
        applyThemeAction(detail, palette, false)
        setThemeGlyph(play, "play")
        setThemeGlyph(detail, "info")
    }

    private fun heroButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        context.textView(
            text = label,
            sizeSp = 14f,
            color = if (primary) Color.BLACK else ViewTheme.textPrimary,
            gravity = Gravity.CENTER,
        ).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(context.vDp(16), 0, context.vDp(16), 0)
            // ★ 2026-11-09：原生主题下用预建的焦点态背景；走主题布局时背景由主题接管。
            //
            //   原来在 onFocusChange 里 setBackground()，那会触发 requestLayout()，
            //   焦点移动一次就整树重测一次 —— 这是"不跟手"的主因。
            //   现在背景由 StateListDrawable 自动切，回调里只保留对外的焦点通知。
            background = if (themePalette == null) {
                focusStateBackground(
                    normalColor = if (primary) Color.WHITE else 0x1AFFFFFF,
                    focusColor = if (primary) ViewTheme.accent else 0x33FFFFFF,
                    radiusPx = context.vDp(8),
                )
            } else {
                roundedBackground(
                    if (primary) Color.WHITE else 0x1AFFFFFF,
                    context.vDp(8),
                )
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, focused ->
                // 这里是 Hero 上真正显示的按钮（播放/详情）。
                // 注意：本文件里有两个同名私有方法 `heroButton`，另一处在空态按钮上，
                // 改焦点样式时两处都要改（2026-11-07 曾因为只改一处排查很久）。
                onFocusChanged?.invoke(focused)
            }
        }
}

/** Hero 横图优先保留顶部人物头部，只从底部裁切。 */
private class TopCropImageView(context: Context) : ImageView(context) {
    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setScaleType(scaleType: ScaleType?) {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        super.setScaleType(ScaleType.MATRIX)
        applyTopCrop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTopCrop()
    }

    private fun applyTopCrop() {
        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        if (drawableWidth <= 0 || drawableHeight <= 0 || width <= 0 || height <= 0) return
        val scale = maxOf(width.toFloat() / drawableWidth, height.toFloat() / drawableHeight)
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - drawableWidth * scale) / 2f, 0f)
        }
    }
}

private class HeroGradientView(context: Context) : View(context) {
    private val paint = Paint()
    private val backdrop = ViewTheme.backdrop

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                backdrop,
                Color.argb(250, 11, 11, 13),
                Color.argb(184, 11, 11, 13),
                Color.argb(64, 11, 11, 13),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.28f, 0.48f, 0.68f, 0.84f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(140, 11, 11, 13),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                backdrop,
            ),
            floatArrayOf(0f, 0.25f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}
