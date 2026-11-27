package com.reelix.app.ui.screen.detail

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Bitmap
import android.graphics.Outline
import android.util.LruCache
import android.view.ViewTreeObserver
import kotlinx.coroutines.*
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Precision
import coil.size.Scale
import com.reelix.app.data.image.LocalImageStore
import com.reelix.app.data.model.Episode
import com.reelix.app.data.model.MediaDetail
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.model.PlayRoute
import com.reelix.app.ui.component.EpisodeRowView
import com.reelix.app.ui.component.PageView
import com.reelix.app.ui.component.PosterTile
import com.reelix.app.ui.component.PosterTileView
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp
import com.reelix.app.R
import com.reelix.app.player.EpisodeOrderSession
import com.reelix.app.player.EpisodeFocusSession
import com.reelix.app.player.episodePresentation
import com.reelix.app.player.episodeKey
import com.reelix.app.player.matchRouteEpisode
import com.reelix.app.player.RouteEpisodeMatch
import com.reelix.app.ui.component.KeyRouting
import com.reelix.app.ui.component.FocusBox
import com.reelix.app.ui.component.FocusRowGeometry
import com.reelix.app.data.alist.LibrarySection
import com.reelix.app.data.alist.LibrarySectionKind
import com.reelix.app.data.alist.librarySections
import com.reelix.app.ui.theme.layout.*
import com.reelix.app.ui.screen.home.homePlaybackClock

/**
 * 详情页 —— 原生 View 版。
 *
 * 背景固定，内容用 ScrollView 上移；剧集横轨和相关推荐都是 Leanback View。
 */
class DetailView(
    context: Context,
    private val onPlay: (PlayRoute, Episode) -> Unit,
    private val onOpenItem: (MediaItem) -> Unit,
    private val onChangeArtwork: (MediaItem) -> Unit = {},
    private val onDeleteEpisode: (Episode) -> Unit = {},
    private val theme: NativeThemeLayout? = null,
    private val onExitUp: () -> Unit = {},
) : FrameLayout(context), PageView, com.reelix.app.ui.component.PopupFocusReturnHost {

    private var themeBinding: ThemeLayoutBinding<DetailThemeState>? = null
    private var episodeRegionFocused = false
    private var routesLoading = false
    private var sourceLabel: String? = null
    private var actionPresentation: DetailActionPresentation? = null
    private var deleteEpisode: Episode? = null
    private val controlPalette = theme?.palette ?: nativeDetailPalette()
    private val backdrop = DetailBackdropView(context)
    private val scrim = DetailScrimView(context)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private var sectionPopup: android.widget.PopupWindow? = null
    private var sectionPopupSignature: List<Pair<String, String>>? = null
    private var sectionPopupEpoch = 0
    /** 从播放器返回后要还原的选集卡；只有真正拿到焦点后才会清掉。 */
    private var pendingEpisodeFocusKey: String? = null
    private var episodeFocusLoopRunning = false
    /** 线路 chip 的"聚焦即切换"延迟任务；快速划过时不触发整页重渲染。 */
    private var pendingRouteSelectView: View? = null
    private var pendingRouteSelect: Runnable? = null
    /** 页面上可聚焦控件的扫描结果；布局或滚动变化时作废。 */
    private var focusTargetsCache: List<View>? = null
    private val scrollWatcher = ViewTreeObserver.OnScrollChangedListener {
        backdrop.setContentScroll(scroll.scrollY)
        // 卡片位置随滚动变化，跨行判定缓存必须作废。
        focusTargetsCache = null
    }
    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        if (focused != null && isInside(focused, this)) {
            when {
                isInside(focused, episodeRow) || focused === episodeOrder || focused === backToTop ||
                    isInside(focused, routeRow) -> updateEpisodeFocus(true)
                focused === playButton || isInside(focused, libraryTools) -> updateEpisodeFocus(false)
            }
        }
    }

    private fun isInside(view: View, parent: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === parent) return true
            node = node.parent as? View
        }
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
        viewTreeObserver.addOnScrollChangedListener(scrollWatcher)
    }

    /**
     * 从播放器返回时窗口焦点会晚于页面重建到位。
     * 只要还有待还原的选集卡，焦点一回来就再试一次。
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        val key = pendingEpisodeFocusKey ?: return
        if (!hasWindowFocus) return
        post {
            val workId = detail?.item?.id ?: return@post
            if (episodeRow.focusKey(key)) {
                pendingEpisodeFocusKey = null
                EpisodeFocusSession.forget(workId)
            }
        }
    }

    /**
     * 删除态下 BACK 只退出删除态，不要冒泡到 Activity 把整个详情页关掉。
     *
     * ★ 之前没有任何 View 消费 BACK，按键直接落到 MainActivity 的 BackHandler，
     *   用户看到的就是"长按出现垃圾桶 → BACK → 详情页没了"。
     *   KeyUp 一起吃掉，否则 Leanback/Activity 会用 UP 再触发一次返回。
     */
    private var swallowDeleteBackUp = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && (deleteEpisode != null || swallowDeleteBackUp)) {
                if (event.repeatCount == 0 && deleteEpisode != null) exitDeleteMode()
                swallowDeleteBackUp = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && swallowDeleteBackUp) {
                swallowDeleteBackUp = false
                return true
            }
        }
        // 上下键在页面层统一按几何位置跨行：只去紧邻的可见行，不会跳过中间行。
        // 行不需要登记，内容区里新增一行会自动参与判定。
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            if (moveFocusToAdjacentRow(up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 找到当前焦点"紧挨着的上一行/下一行"里水平位置最近的控件。
     *
     * 找不到就返回 false，把按键交还给原有逻辑（例如播放键 ↑ 回顶栏、返回顶部 ↑ 回内容）。
     * 之所以在页面层统一处理：每行各写一套跳转规则，新增行时必然漏，表现就是跳行。
     */
    private fun moveFocusToAdjacentRow(up: Boolean): Boolean {
        val started = android.os.SystemClock.elapsedRealtime()
        val focused = findFocus() ?: return false
        if (focused === this) return false
        val current = focusBoxOf(focused) ?: return false
        val targets = focusTargets()
        if (targets.size <= 1) return false
        val boxes = ArrayList<FocusBox>(targets.size)
        val views = ArrayList<View>(targets.size)
        targets.forEach { view ->
            if (view === focused) return@forEach
            val box = focusBoxOf(view) ?: return@forEach
            boxes += box
            views += view
        }
        if (boxes.isEmpty()) return false
        val index = FocusRowGeometry.pickVertical(current, boxes, up) ?: return false
        var target = views[index]
        // 进入线路行（不分方向）时只落到"当前已选线路"的 chip。
        // 按水平距离挑会选到别的线路 → 触发 selectRoute → 整页重渲染，既卡又误切线路。
        if (isInside(target, routeRow)) {
            routeChips.getOrNull(routeIndex)?.takeIf { it.isShown && it.isFocusable }?.let { target = it }
        }
        // 进入选集行（不分方向）时优先落"当前播放中/最近一次播放"的那张卡，
        // 找不到对应集（例如换了线路）再退回几何最近列。
        if (isInside(target, episodeRow)) {
            val key = preferredEpisodeFocusKey()
            if (key != null && episodeRow.hasKey(key)) {
                episodeRow.selectKey(key)
                if (episodeRow.focusRow()) {
                    android.util.Log.i("FocusCost", "row-move up=$up -> played episode")
                    return true
                }
            }
        }
        val targetBox = focusBoxOf(target) ?: return false
        // 目标行不在可视区时先即时滚进来，避免焦点已到、画面还在平滑滚动的迟滞感。
        ensureRowVisible(targetBox)
        if (!target.requestFocus()) return false
        if (up) {
            // 目标已经是可聚焦的顶行（上面没有别的行）时，顺带把页面滚到顶部，
            // 否则会出现"焦点到了播放键、海报还露半截，要再按一次 UP"。
            val others = ArrayList<FocusBox>(boxes.size)
            boxes.forEachIndexed { i, box -> if (views[i] !== target) others += box }
            if (FocusRowGeometry.pickVertical(targetBox, others, up = true) == null) {
                scroll.scrollTo(0, 0)
            }
        }
        android.util.Log.i(
            "FocusCost",
            "row-move up=$up target=${target.javaClass.simpleName} " +
                "cost=${android.os.SystemClock.elapsedRealtime() - started}ms",
        )
        return true
    }

    /**
     * 当前播放中/最近一次播放的集：优先用播放记录里的集号，
     * 没有播放记录时退回选集行自己的当前选中项。
     */
    private fun preferredEpisodeFocusKey(): String? {
        val index = watchProgress?.episodeIndex
        if (index != null) {
            currentRoute()?.episodes?.firstOrNull { it.index == index }?.let { return episodeKey(it) }
        }
        return episodeRow.selectedKey()
    }

    /** 目标不在可视区时即时滚动（不播动画）。 */
    private fun ensureRowVisible(box: FocusBox) {
        if (scroll.childCount == 0 || scroll.height <= 0) return
        val location = IntArray(2)
        scroll.getLocationOnScreen(location)
        val top = location[1]
        val bottom = top + scroll.height
        val padding = context.vDp(12)
        val delta = when {
            box.top < top + padding -> box.top - top - padding
            box.bottom > bottom - padding -> box.bottom - bottom + padding
            else -> 0
        }
        if (delta != 0) scroll.scrollBy(0, delta)
    }

    private fun focusBoxOf(view: View): FocusBox? {
        if (view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return FocusBox(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /**
     * 页面上真正可聚焦的控件（一次遍历；焦点容器本身不算，避免焦点停在整行上）。
     *
     * 扫描结果按布局/滚动缓存：单按和长按连发都只扫一次，
     * 否则每次按键都全树遍历，实机就是"单按一顿、长按反而顺"。
     */
    private fun focusTargets(): List<View> {
        focusTargetsCache?.let { return it }
        val list = ArrayList<View>(64)
        collectFocusTargets(this, list)
        focusTargetsCache = list
        return list
    }

    /** @return 这棵子树里是否有可聚焦控件 */
    private fun collectFocusTargets(view: View, out: MutableList<View>): Boolean {
        if (!view.isShown) return false
        if (view is ViewGroup) {
            var any = false
            for (index in 0 until view.childCount) {
                if (collectFocusTargets(view.getChildAt(index), out)) any = true
            }
            if (!any && view.isFocusable && view.isEnabled) {
                out += view
                return true
            }
            return any
        }
        if (view.isFocusable && view.isEnabled) {
            out += view
            return true
        }
        return false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        focusTargetsCache = null
        super.onLayout(changed, left, top, right, bottom)
    }

    /** 退出单集删除态，焦点回到刚才那张卡片。 */
    private fun exitDeleteMode() {
        val target = deleteEpisode ?: return
        deleteEpisode = null
        episodeRow.arm(null)
        episodeRow.selectKey(episodeKey(target))
        episodeRow.focusRow()
    }

    override fun onDetachedFromWindow() {
        sectionPopupEpoch++
        themeBinding?.dispose()
        backdrop.onArtworkChanged = null
        sectionPopup?.dismiss(); sectionPopup = null
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollWatcher)
        super.onDetachedFromWindow()
    }

    private val title = context.textView("", 44f, ViewTheme.textPrimary).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val meta = context.textView("", 13f, ViewTheme.textSecondary)
    private val sourceInfo = context.textView("", 11f, ViewTheme.textSecondary).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val overview = context.textView("", 13f, ViewTheme.textSecondary).apply {
        maxLines = 6
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(0f, 0.9f)
    }
    /** 评分/年份行，位于简介上方、播放按钮右侧。 */
    private val scoreLine = context.textView("", 13f, ViewTheme.accent).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private var routeSignature: List<String>? = null
    private val playButton = PlayButtonView(context)
    private val playTime = context.textView("", 10.5f, ViewTheme.textSecondary).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = GONE
    }
    private val playColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val routeHeader = LinearLayout(context)
    private val routeTitle = context.textView("", 22f, ViewTheme.textPrimary)
    private val routeCount = context.textView("", 13f, ViewTheme.textTertiary)
    private val routeRow = LinearLayout(context)
    private val routeScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = true
    }
    private val routeChips = mutableListOf<TextView>()
    private val selectedResource = context.textView("", 13f, ViewTheme.textSecondary).apply {
        setSingleLine(false)
        setHorizontallyScrolling(false)
    }
    private val libraryTools = LinearLayout(context)
    private val primaryActions = LinearLayout(context)
    private val episodeHead = LinearLayout(context)
    private var folder: String? = null
    private var selectedVersion: String? = null
    private var pendingVersionKey: String? = null
    private var sections: List<LibrarySection> = emptyList()
    private var watchProgress: com.reelix.app.data.db.PlayProgressEntity? = null
    private val folderButton = toolButton(R.string.library_choose_section) { showFolders() }
    private val artworkButton = toolButton(R.string.library_change_artwork) { detail?.item?.let(onChangeArtwork) }
    private val episodeTitle = context.textView("选择剧集", 22f, ViewTheme.textPrimary)
    private val episodeCount = context.textView("", 13f, ViewTheme.textTertiary)
    private val episodeLoading = context.textView("正在获取线路和选集…", 13f, ViewTheme.textTertiary)
    private val episodeOrder: TextView = context.textView("", 13f, ViewTheme.textPrimary, Gravity.CENTER).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setPadding(context.vDp(18), 0, context.vDp(18), 0)
        // ★ 2026-11-09：焦点态改成预建 drawable（StateListDrawable 自动切）。
        //   原来的 setOnFocusChangeListener 里 setBackground() 会触发 requestLayout()，
        //   焦点每移动一次整棵树重测一次 —— 这是"不跟手"的主因。
        background = focusStateBackground(
            0x14FFFFFF, 0x33FFFFFF, context.vDp(6), context.vDp(2), ViewTheme.focusRing,
        )
        setOnClickListener {
            detail?.let { value ->
                EpisodeOrderSession.set(value.item.id, !EpisodeOrderSession.reversed(value.item.id))
                render(value, loadingRoutes = false, finished = finished)
            }
        }
        setOnKeyListener { _, code, event ->
            val exit = KeyRouting.episodeRowExit(code == KeyEvent.KEYCODE_DPAD_UP,
                code == KeyEvent.KEYCODE_DPAD_DOWN, true)
            if (exit == 0) false else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (exit < 0) episodeRow.focusRow()
                    // 演员行在正序/倒序下方、返回顶部上方，必须按视觉顺序走。
                    else if (castAvatars.visibility == VISIBLE && castAvatars.focusFirst()) Unit
                    else if (relatedRow.visibility == VISIBLE) relatedRow.focusFirst() else focusBackToTop()
                }
                true
            }
        }
    }
    private val episodeRow: EpisodeRowView = EpisodeRowView(
        context = context,
        onPlay = { ep ->
            // 记住是从哪张选集卡进的播放器，返回时把焦点还给这张卡。
            detail?.item?.id?.let { EpisodeFocusSession.remember(it, episodeKey(ep)) }
            playbackRoute()?.let { onPlay(it, ep) }
        },
        onExitUp = { onEpisodeUp() },
        // 最左侧卡片再按 LEFT 什么都不做：跨行由页面统一处理，不能跳回播放键。
        onBoundaryStart = {},
        onExitDown = {
            // 视觉顺序：选集卡片 → 正序/倒序 → 演职员 → 相关推荐 → 返回顶部。
            // 正序按钮被隐藏（单集）时不能跳过演职员直接到相关推荐。
            when {
                episodeOrder.visibility == VISIBLE -> episodeOrder.requestFocus()
                castAvatars.visibility == VISIBLE && castAvatars.focusFirst() -> Unit
                relatedRow.visibility == VISIBLE -> relatedRow.focusFirst()
                else -> focusBackToTop()
            }
        },
        onSelected = {
            if (episodeRow.hasFocus() && routeSelectionActive && routeMatchMessage == null) routeEpisode = it
            showSelectedResource(it)
        },
        onLongPress = { episode ->
            deleteEpisode = episode
            episodeRow.arm(episodeKey(episode))
        },
        canLongPress = { episode ->
            com.reelix.app.data.repo.librarySourceSupportsDelete(
                episode.sourceKey.ifBlank { detail?.item?.sourceKey.orEmpty() })
        },
        onArmedClick = { episode ->
            if (deleteEpisode != null) {
                deleteEpisode = null
                episodeRow.arm(null)
                onDeleteEpisode(episode)
            }
        },
    )
    private val castTitle = context.textView("演职员", 22f, ViewTheme.textPrimary)
    private val castText = context.textView("", 13f, ViewTheme.textTertiary).apply {
        maxLines = 8
        ellipsize = TextUtils.TruncateAt.END
    }
    private val castAvatars = CastAvatarRow(context)
    init {
        // 视觉顺序是：选集卡片 → 正序/倒序 → 演职员 → 返回顶部。
        //
        // ★ 不能只认 episodeOrder：单文件/单集详情里它被隐藏（GONE），
        //   requestFocus() 会直接失败，焦点就卡在演职员行上按不动。
        //   隐藏时回选集卡片行 —— 不是播放按钮，那样会越过整排选集。
        castAvatars.onExitUp = {
            if (episodeOrder.visibility == VISIBLE && episodeOrder.requestFocus()) Unit
            else if (episodeRow.visibility == VISIBLE) episodeRow.focusRow()
            else playButton.requestFocus()
        }
        castAvatars.onExitDown = { focusBackToTop() }
    }
    private val relatedTitle = context.textView("相关推荐", 22f, ViewTheme.textPrimary)
    private val relatedRow: RelatedRowView = RelatedRowView(
        context = context,
        onClick = { item -> onOpenItem(item) },
        onExitUp = {
            // 与 DOWN 对称：相关推荐 ↑ 先回演职员，再回正序/选集。
            when {
                castAvatars.visibility == VISIBLE && castAvatars.focusFirst() -> Unit
                episodeOrder.visibility == VISIBLE && episodeOrder.requestFocus() -> Unit
                episodeRow.visibility == VISIBLE -> episodeRow.focusRow()
                else -> playButton.requestFocus()
            }
        },
        onExitDown = { focusBackToTop() },
    )
    private val backToTop = context.textView("返回顶部", 14f, ViewTheme.textPrimary, Gravity.CENTER).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        // 焦点态预建：聚焦时底色 0x2EFFFFFF + 2dp 描边，失焦是 0x14FFFFFF
        background = focusStateBackground(
            0x14FFFFFF, 0x2EFFFFFF, context.vDp(22), context.vDp(2), ViewTheme.focusRing,
        )
        setOnClickListener {
            scroll.smoothScrollTo(0, 0)
            playButton.requestFocus()
        }
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                // ★ 按层级往上走，不能直接跳播放键。
                //
                //   原来无条件调 relatedRow.focusFirst()：相关推荐为空时它返回 false，
                //   但按键已经被 true 吃掉，焦点就交给系统 focusSearch，
                //   实机表现是「从返回顶部按 UP 直接飞到播放按钮」。
                //   现在按 相关推荐 → 剧集行 → 播放键 逐级回退。
                when {
                    relatedRow.visibility == VISIBLE && relatedRow.focusFirst() -> Unit
                    castAvatars.visibility == VISIBLE && castAvatars.focusFirst() -> Unit
                    episodeOrder.visibility == VISIBLE && episodeOrder.requestFocus() -> Unit
                    episodeRow.visibility == VISIBLE && episodeRow.focusRow() -> Unit
                    else -> playButton.requestFocus()
                }
                true
            } else {
                false
            }
        }
        // 焦点态背景由上面的 StateListDrawable 自动切换，不再需要回调
    }

    private var detail: MediaDetail? = null
    private var routeIndex: Int = 0
    private var finished: Boolean = false
    private var routeSelectionActive = false
    private var routeEpisode: Episode? = null
    private var routeAnchor: Episode? = null
    private var routeMatchMessage: Int? = null

    init {
        setBackgroundColor(ViewTheme.backdrop)
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        addView(scroll)

        content.orientation = LinearLayout.VERTICAL
        content.setPadding(context.vDp(56), context.vDp(322), context.vDp(56), context.vDp(40))
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(title)
        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(14)))
        // ★ 播放键与简介并排（2026-11-07）：简介挪到按钮右侧。
        //
        //   原来简介在按钮下面占 3 行，把剧集行整个往下推，首屏看不到几集。
        //   并排之后纵向省出约 3 行高度，且"左边能点的、右边能读的"分区更清楚。
        val playRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            minimumHeight = context.vDp(92)
        }
        primaryActions.orientation = LinearLayout.VERTICAL
        playColumn.addView(playButton, LinearLayout.LayoutParams(context.vDp(260), context.vDp(46)))
        playTime.layoutParams = LinearLayout.LayoutParams(context.vDp(260), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.vDp(4) }
        playColumn.addView(playTime)
        primaryActions.addView(playColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        libraryTools.orientation = LinearLayout.HORIZONTAL
        libraryTools.addView(folderButton, LinearLayout.LayoutParams(context.vDp(170), context.vDp(40)))
        libraryTools.addView(artworkButton, LinearLayout.LayoutParams(context.vDp(170), context.vDp(40)).apply { leftMargin = context.vDp(10) })
        primaryActions.addView(libraryTools, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.vDp(8) })
        playRow.addView(
            primaryActions,
            LinearLayout.LayoutParams(context.vDp(350), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        playRow.addView(View(context), LinearLayout.LayoutParams(context.vDp(20), 1))
        val overviewArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        overviewArea.addView(scoreLine, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.vDp(4)
        })
        overviewArea.addView(overview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        playRow.addView(
            overviewArea,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(
            playRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(12)))

        routeHeader.visibility = GONE
        routeHeader.orientation = LinearLayout.HORIZONTAL
        routeHeader.gravity = Gravity.CENTER_VERTICAL
        routeTitle.setText(R.string.detail_routes)
        routeHeader.addView(routeTitle)
        routeHeader.addView(routeCount, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = context.vDp(12) })
        content.addView(routeHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.vDp(64) })

        routeRow.orientation = LinearLayout.HORIZONTAL
        routeRow.gravity = Gravity.CENTER_VERTICAL
        routeScroll.addView(routeRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(
            routeScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.vDp(46),
            ).apply { topMargin = context.vDp(10) },
        )

        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(12)))

        episodeHead.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        episodeHead.addView(episodeTitle)
        episodeHead.addView(
            episodeCount,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = context.vDp(12) },
        )
        content.addView(episodeHead)
        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(14)))
        content.addView(
            episodeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.vDp(144),
            ),
        )
        episodeLoading.visibility = GONE
        content.addView(selectedResource, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.vDp(8)
            bottomMargin = context.vDp(6)
        })
        content.addView(episodeOrder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, context.vDp(40)).apply { topMargin = context.vDp(6) })
        content.addView(episodeLoading)

        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(36)))
        content.addView(castTitle)
        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(10)))
        content.addView(castAvatars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, context.vDp(118)))
        content.addView(castText)

        // User removed recommendations: the former library row was simply the first eight unrelated works.
        relatedTitle.visibility = GONE
        relatedRow.visibility = GONE

        content.addView(View(context), LinearLayout.LayoutParams(1, context.vDp(30)))
        content.addView(
            backToTop,
            LinearLayout.LayoutParams(context.vDp(180), context.vDp(44)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        playButton.setOnKeyListener { _, keyCode, event ->
            if (theme != null && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    // Sidebar keeps the sidebar visible over the detail page. UP from play
                    // should reveal the full poster first; only leave when already at the top.
                    if (scroll.scrollY > 0) scroll.smoothScrollTo(0, 0)
                    else if (showTopNavigation(true, theme.id)) onExitUp()
                }
                true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                // 有下级就接住；一个都没有就放行，别把按键吞掉
                onPlayDown()
            } else {
                false
            }
        }
        if (theme != null) {
            val themeRoot = FrameLayout(context)
            val foreground = backdrop.prepareThemePoster(theme.id == NativeThemeId.GALLERY)
            val supplemental = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            listOf(scoreLine, castTitle, castText).forEach { addView(it.detachForTheme()) }
            addView(castAvatars.detachForTheme())
            }
            val nodes = listOf(backdrop, scroll, title, meta, overview, primaryActions, routeHeader, routeScroll,
                episodeHead, episodeRow, selectedResource, episodeOrder, episodeLoading, backToTop)
            nodes.forEach { it.detachForTheme() }
            scroll.removeAllViews()
            removeAllViews()
            episodeRow.minimumHeight = context.vDp(144)
            routeScroll.minimumHeight = context.vDp(46)
            episodeOrder.minimumHeight = context.vDp(40)
            backToTop.minimumHeight = context.vDp(44)
            libraryTools.orientation = LinearLayout.VERTICAL
            for (index in 0 until libraryTools.childCount) libraryTools.getChildAt(index).layoutParams =
                LinearLayout.LayoutParams(context.vDp(260), context.vDp(40)).apply { if (index > 0) topMargin = context.vDp(8) }
            playButton.layoutParams = LinearLayout.LayoutParams(context.vDp(260), context.vDp(46))
            libraryTools.layoutParams = LinearLayout.LayoutParams(context.vDp(260), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = context.vDp(8) }
            primaryActions.gravity = if (theme.id == NativeThemeId.TOPBAR) Gravity.CENTER_HORIZONTAL else Gravity.START
            addView(themeRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            themeBinding = theme.installDetail(DetailThemeSlots(themeRoot, backdrop, foreground, scroll,
                title, meta, overview, primaryActions, routeHeader, routeScroll, episodeHead, episodeRow,
                selectedResource, episodeOrder, episodeLoading, supplemental, backToTop,
                sourceInfo = sourceInfo, configureActions = ::configureThemeActions,
                configureEpisodes = { style -> episodeRow.configurePresentation(style, theme.palette) }))
            backdrop.onArtworkChanged = { updateTheme() }
        }
        configureThemeActions(standardDetailActions(resources.displayMetrics.density))
        episodeRow.configurePresentation(standardEpisodePresentation(
            resources.displayMetrics.density, resources.configuration.fontScale), controlPalette)
        playButton.setTheme(controlPalette)
        playTime.setTextColor(controlPalette.secondaryText)
        listOf(folderButton, artworkButton, episodeOrder, backToTop).forEach { button ->
            button.setOnFocusChangeListener(null)
            applyThemeAction(button, controlPalette, false, radiusDp = 8)
        }
        setThemeGlyph(folderButton, "layers")
        setThemeGlyph(artworkButton, "image")
    }

    private fun configureThemeActions(presentation: DetailActionPresentation) {
        val expectedOrientation = if (presentation.toolsBelow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val tools = listOf(folderButton, artworkButton)
        val resolved = resolveDetailToolLayouts(
            visibleSlots = tools.map { it.visibility == VISIBLE },
            playWidthPx = presentation.playWidthPx,
            toolWidthsPx = presentation.toolWidthsPx,
            gapPx = presentation.gapPx,
        )
        val groupParams = libraryTools.layoutParams as? LinearLayout.LayoutParams
        if (actionPresentation == presentation && primaryActions.orientation == expectedOrientation &&
            playButton.layoutParams.width == presentation.playWidthPx && playButton.layoutParams.height == presentation.playHeightPx &&
            (playTime.layoutParams as? LinearLayout.LayoutParams)?.width == presentation.playWidthPx &&
            (playColumn.layoutParams as? LinearLayout.LayoutParams)?.width == presentation.playWidthPx &&
            libraryTools.gravity == Gravity.START &&
            groupParams?.leftMargin == (if (presentation.toolsBelow) 0 else presentation.toolGroupGapPx) &&
            groupParams.topMargin == (if (presentation.toolsBelow) presentation.toolGroupGapPx else 0) &&
            tools.withIndex().all { (index, button) -> button.layoutParams.width == resolved[index].widthPx &&
                button.layoutParams.height == presentation.toolHeightPx &&
                (button.layoutParams as? LinearLayout.LayoutParams)?.leftMargin ==
                    resolved[index].leftMarginPx }) return
        actionPresentation = presentation
        primaryActions.orientation = if (presentation.toolsBelow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        primaryActions.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        playButton.layoutParams = LinearLayout.LayoutParams(presentation.playWidthPx, presentation.playHeightPx)
        playTime.layoutParams = LinearLayout.LayoutParams(presentation.playWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.vDp(4) }
        playColumn.layoutParams = LinearLayout.LayoutParams(presentation.playWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        playButton.setLabelSize(presentation.playTextPx / resources.displayMetrics.density)
        libraryTools.orientation = LinearLayout.HORIZONTAL
        libraryTools.gravity = Gravity.START
        libraryTools.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (presentation.toolsBelow) topMargin = presentation.toolGroupGapPx else leftMargin = presentation.toolGroupGapPx
        }
        listOf(folderButton, artworkButton).forEachIndexed { index, button ->
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, presentation.toolTextPx / resources.displayMetrics.density)
            val padding = (12 * presentation.toolTextPx / 23f).toInt()
            button.setPadding(padding, 0, padding, 0)
            button.minimumWidth = 0
            button.minimumHeight = 0
            button.layoutParams = LinearLayout.LayoutParams(resolved[index].widthPx, presentation.toolHeightPx)
                .apply { leftMargin = resolved[index].leftMarginPx }
            setThemeGlyph(button, if (index == 0) "layers" else "image")
        }
    }

    fun setSourceLabel(label: String?) { sourceLabel = label }

    private fun updateEpisodeFocus(focused: Boolean) {
        episodeRegionFocused = focused
        backdrop.setEpisodeFocus(focused)
        updateTheme()
    }

    private fun updateTheme() {
        val item = detail?.item ?: return
        themeBinding?.update(DetailThemeState(item.fromLibrary, backdrop.hasWide, backdrop.hasPoster,
            episodeRegionFocused, routesLoading))
        theme?.let {
            playButton.setTheme(it.palette)
            playTime.setTextColor(it.palette.secondaryText)
            applyThemeAction(folderButton, it.palette, false, radiusDp = 8)
            applyThemeAction(artworkButton, it.palette, false, radiusDp = 8)
            setThemeGlyph(folderButton, "layers")
            setThemeGlyph(artworkButton, "image")
        }
        actionPresentation?.let(::configureThemeActions)
    }

    /**
     * 接收 TMDB 补刮到的横图。
     *
     * 详情页自己**不发网络请求**（View 层不碰业务 IO），由 MainActivity 刮好后
     * 通过 `AndroidView.update` 推进来。传 null = 没刮到，继续用条目自带的图。
     */
    fun applyScrapedBackdrop(url: String?) {
        backdrop.setOverrideBackdrop(url)
    }

    fun render(
        detail: MediaDetail,
        loadingRoutes: Boolean,
        finished: Boolean,
        deferBackdrop: Boolean = false,
        progressRecord: com.reelix.app.data.db.PlayProgressEntity? = watchProgress,
    ) {
        val renderStarted = android.os.SystemClock.elapsedRealtime()
        focusTargetsCache = null
        val item = detail.item
        val isNew = this.detail?.item?.id != item.id
        this.detail = detail
        this.finished = finished
        routesLoading = loadingRoutes
        watchProgress = progressRecord
        if (isNew) {
            sectionPopupEpoch++
            sectionPopup?.dismiss()
            sectionPopup = null
            routeSelectionActive = false
            routeEpisode = null
            routeAnchor = null
            routeMatchMessage = null
            routeIndex = if (item.fromLibrary) 0
                else restoredRouteIndex(detail.routes, progressRecord?.routeName)
            folder = null
            selectedVersion = null
            pendingVersionKey = null
        }
        routeIndex = routeIndex.coerceIn(0, (detail.routes.size - 1).coerceAtLeast(0))

        title.text = item.title
        meta.text = item.detailMetaLine.ifBlank { item.metaLine }
        meta.visibility = if (theme == null || meta.text.isNullOrBlank()) GONE else VISIBLE
        scoreLine.text = listOfNotNull(
            item.year?.toString(),
            item.rating?.let { rating ->
                val source = item.ratingSource?.takeIf { it.isNotBlank() }
                if (source != null) "$source ${String.format(java.util.Locale.US, "%.1f", rating)}"
                else String.format(java.util.Locale.US, "%.1f", rating)
            },
        ).joinToString(" · ")
        scoreLine.visibility = if (scoreLine.text.isNullOrBlank()) GONE else VISIBLE
        sourceInfo.text = listOfNotNull(
            sourceLabel?.takeIf { it.isNotBlank() } ?: if (item.fromLibrary) context.getString(R.string.settings_cat_library) else null,
            watchProgress?.positionMs?.takeIf { it > 0 }?.let { context.getString(R.string.theme_last_watched, homePlaybackClock(it)) }
        ).joinToString("  ·  ")
        sourceInfo.visibility = if (sourceInfo.text.isNullOrBlank()) GONE else VISIBLE
        overview.text = item.overview.orEmpty()
        overview.visibility = if (item.overview.isNullOrBlank()) GONE else VISIBLE

        playButton.setOnClickListener { playCurrent() }

        backdrop.bind(item, deferBackdrop)
        rebuildRoutes(detail.routes)
        episodeTitle.setText(if (item.fromLibrary) R.string.library_choose_file else R.string.detail_choose_episode)
        sections = if (item.fromLibrary) librarySections(item.sourceId, currentRoute()?.episodes.orEmpty()) else emptyList()
        if (sections.none { it.id == folder }) folder = null
        if (isNew && sections.isNotEmpty()) folder =
            sections.firstOrNull { item.matchedResourcePath in it.paths }?.id
                ?: sections.firstOrNull { item.preferredResourcePath in it.paths }?.id
                ?: sections.firstOrNull { watchProgress?.sourceId in it.paths }?.id
                ?: sections.firstOrNull { it.tags.first().kind == LibrarySectionKind.SEASON }?.id
                ?: sections.first().id
        val versions = theme != null && item.fromLibrary && sections.isEmpty() &&
            com.reelix.app.ui.component.hasNamedVersionChoices(currentRoute()?.episodes.orEmpty())
        if (currentRoute()?.episodes?.none { it.playId == selectedVersion } != false) selectedVersion = null
        folderButton.visibility = if (sections.isNotEmpty() || versions) VISIBLE else GONE
        // Online items still get the artwork tool; there is no library folder to choose.
        artworkButton.visibility = if (item.fromLibrary || (item.sourceKey.isNotBlank() && item.sourceId.isNotBlank()))
            VISIBLE else GONE
        libraryTools.visibility = if (folderButton.visibility == VISIBLE || artworkButton.visibility == VISIBLE)
            VISIBLE else GONE
        folderButton.text = if (versions) currentRoute()?.episodes?.firstOrNull { it.playId == selectedVersion }
            ?.let { com.reelix.app.ui.component.episodeCardPresentation(it).title ?: it.displayName }
            ?: context.getString(R.string.theme_all_versions)
        else context.getString(R.string.library_section_button,
            sections.firstOrNull { it.id == folder }?.let(::sectionLabel) ?: context.getString(R.string.library_all_episodes))

        val route = currentRoute()
        val episodes = visibleEpisodes()
        val resume = preferredPlaybackEpisode(episodes, item)
        if (theme != null) {
            val tokens = listOfNotNull(item.year?.toString(), item.kind.label.takeIf { it.isNotBlank() },
                item.durationMin?.takeIf { it > 0 }?.let { minutes ->
                    if (minutes >= 60) context.getString(R.string.theme_duration_hours_minutes, minutes / 60, minutes % 60)
                    else context.getString(R.string.theme_duration_minutes, minutes)
                })
            meta.text = themeMetadata(meta, tokens, declaredEpisodeBadges(resume))
            meta.visibility = if (meta.text.isNullOrBlank()) GONE else VISIBLE
        }
        // preferredPlaybackEpisode 在完全没有历史时也会返回第一集作为兜底；
        // 只有确实存在可续播记录时才显示“继续观看”，否则新片会误显示
        // “继续观看·第05集”（片库文件名里的期数会被当成集号）。
        val resumable = resume?.takeIf { ep ->
            !finished && if (item.fromLibrary) {
                watchProgress?.sourceId == ep.playId
            } else {
                watchProgress?.episodeIndex == ep.index ||
                    (watchProgress == null && item.lastEpisode == ep.index)
            }
        }
        val resumeNumber = resumable?.let { com.reelix.app.ui.component.episodeCardPresentation(it).number }
        playButton.bind(text = when {
            routeMatchMessage != null -> context.getString(R.string.route_match_choose_episode)
            finished && resume != null -> context.getString(R.string.detail_rewatch)
            resumeNumber != null -> context.getString(R.string.detail_resume_episode, resumeNumber)
            resumable != null -> context.getString(R.string.action_resume)
            else -> context.getString(R.string.action_play)
        }, progress = if (resume != null) item.progress else 0f)
        updatePlayTime(item, watchProgress)
        episodeCount.text = context.getString(if (item.fromLibrary) R.string.library_file_count else R.string.detail_episode_count, episodes.size)
        episodeLoading.text = if (item.fromLibrary) context.getString(R.string.library_no_files) else if (loadingRoutes) {
            "正在获取线路和选集…"
        } else {
            "暂无可用播放源，换条线路或换个源试试"
        }
        episodeLoading.visibility = if (episodes.isEmpty()) VISIBLE else GONE
        episodeRow.visibility = if (episodes.isEmpty()) GONE else VISIBLE
        episodeOrder.visibility = if (episodes.size > 1) VISIBLE else GONE
        val reversed = EpisodeOrderSession.reversed(item.id)
        episodeOrder.text = context.getString(if (reversed) R.string.player_order_desc else R.string.player_order_asc)
        playButton.alpha = if (episodes.isEmpty()) 0.55f else 1f
        if (episodes.isNotEmpty()) {
            val record = watchProgress
            val current = if (record == null) null else if (item.fromLibrary) {
                episodes.singleOrNull { it.playId == record.sourceId }?.index
            } else if (record.routeName == route?.name) record.episodeIndex else null
            val shown = episodePresentation(episodes, reversed)
            val preferred = if (item.fromLibrary || routeSelectionActive) resume?.let(::episodeKey) else null
            val select = shown.indexOfFirst { if (preferred != null) episodeKey(it) == preferred else it.index == (current ?: item.lastEpisode) }.coerceAtLeast(0)
            episodeRow.submit(shown, current, item.progress, select)
            pendingVersionKey?.let { episodeRow.selectKey(it); pendingVersionKey = null }
            shown.firstOrNull { com.reelix.app.player.episodeKey(it) == episodeRow.selectedKey() }
                ?.let(::showSelectedResource) ?: shown.getOrNull(select)?.let(::showSelectedResource)
        } else {
            selectedResource.text = ""
        }

        val director = item.director?.takeIf { it.isNotBlank() }
        val cast = item.cast.filter { it.isNotBlank() }.take(20)
        val avatarCast = item.castCredits.filter { !it.avatar.isNullOrBlank() }
        castText.text = buildString {
            if (director != null) append("导演 $director")
            if (cast.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("主演 ${cast.joinToString("、")}")
            }
        }
        castAvatars.submit(avatarCast)
        // 有头像时只显示有头像的演员；一个都没有才回退到文字。
        val showAvatars = avatarCast.isNotEmpty()
        castAvatars.visibility = if (showAvatars) VISIBLE else GONE
        castText.visibility = if (showAvatars || castText.text.isNullOrBlank()) GONE else VISIBLE
        updateTheme()
        castTitle.visibility = if (showAvatars || !castText.text.isNullOrBlank()) VISIBLE else GONE

        if (sectionPopup?.isShowing == true &&
            (loadingRoutes || sectionPopupSignature != popupChoicesSignature())) {
            sectionPopup?.dismiss()
            sectionPopup = null
        }
        if (isNew) {
            val epoch = sectionPopupEpoch
            val restoreKey = EpisodeFocusSession.last(item.id)
            post {
                if (isAttachedToWindow && detail.item.id == this.detail?.item?.id &&
                    epoch == sectionPopupEpoch && sectionPopup?.isShowing != true) {
                    // 从播放器返回时先把焦点还给刚播过的那张卡，
                    // 否则顶部「继续播放」会被默认焦点抢走。
                    if (restoreKey != null) scheduleEpisodeFocusRestore(restoreKey) else focusFirst()
                }
            }
        }
        val renderCost = android.os.SystemClock.elapsedRealtime() - renderStarted
        if (renderCost >= 6) {
            android.util.Log.i(
                "FocusCost",
                "detail render cost=${renderCost}ms routes=${detail.routes.size} new=$isNew",
            )
        }
    }

    private fun toolButton(label: Int, action: () -> Unit): TextView = context.textView(
        context.getString(label), 13f, ViewTheme.textPrimary, Gravity.CENTER).apply {
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(context.vDp(12), 0, context.vDp(12), 0)
        background = focusStateBackground(
            ViewTheme.surfaceElevated, ViewTheme.surfaceElevated,
            context.vDp(6), context.vDp(2), ViewTheme.focusRing,
        )
        setOnClickListener { action() }
        setOnKeyListener { _, code, event ->
            val exit = KeyRouting.episodeRowExit(code == KeyEvent.KEYCODE_DPAD_UP, code == KeyEvent.KEYCODE_DPAD_DOWN, true)
            if (exit == 0) false else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (exit < 0) playButton.requestFocus() else focusBelowTool()
                }
                true
            }
        }
    }

    /** Route chips come before episodes, matching the visual order on the page. */
    private fun focusBelowTool(): Boolean {
        if (routeRow.visibility == VISIBLE) {
            routeChips.getOrNull(routeIndex)?.let { if (it.requestFocus()) return true }
        }
        if (episodeRow.focusRow()) return true
        focusBackToTop()
        return true
    }
    private fun showFolders() {
        val value = detail ?: return
        if (!folderButton.isAttachedToWindow || !folderButton.isShown) return
        if (sections.isEmpty()) { showVersions(); return }
        val choices = listOf(context.getString(R.string.library_all_episodes)) + sections.map(::sectionLabel)
        if (sectionPopup?.isShowing == true) return
        val openedIds = sections.map { it.id }
        val itemId = value.item.id
        val epoch = ++sectionPopupEpoch
        sectionPopupSignature = sections.map { it.id to sectionLabel(it) }
        val frame = android.graphics.Rect().also { getWindowVisibleDisplayFrame(it) }
        val location = IntArray(2).also { folderButton.getLocationOnScreen(it) }
        val needed = context.vDp(minOf(choices.size, 5) * 48 + 22)
        val available = frame.bottom - location[1] - folderButton.height
        if (available < needed) scroll.scrollBy(0, needed - available)
        sectionPopup = com.reelix.app.ui.component.showAnchoredChoicePopup(folderButton, choices,
            if (folder == null) 0 else sections.indexOfFirst { it.id == folder } + 1,
            restoreFocus = {
                fun available(view: View) = view.isAttachedToWindow && view.isShown && view.isEnabled && view.isFocusable
                val targets = when (sectionPopupReturnTarget(
                    samePage = isAttachedToWindow && detail?.item?.id == itemId && epoch == sectionPopupEpoch,
                    anchorAvailable = available(folderButton),
                    artworkAvailable = available(artworkButton),
                    playAvailable = available(playButton),
                )) {
                    SectionPopupReturnTarget.ANCHOR -> listOf(folderButton, artworkButton, playButton)
                    SectionPopupReturnTarget.ARTWORK -> listOf(artworkButton, playButton)
                    SectionPopupReturnTarget.PLAY -> listOf(playButton)
                    SectionPopupReturnTarget.NONE -> emptyList()
                }
                if (targets.isNotEmpty() && targets.none { available(it) && it.requestFocus() }) {
                    android.util.Log.w("ReelixDetail", "Unable to restore section popup focus for $itemId")
                }
            }) { which ->
            val current = detail ?: return@showAnchoredChoicePopup
            if (current.item.id != itemId || epoch != sectionPopupEpoch) return@showAnchoredChoicePopup
            val chosenId = selectedLibrarySectionId(openedIds, sections.map { it.id }, which)
            if (which != 0 && chosenId == null) return@showAnchoredChoicePopup
            folder = chosenId
            selectedResource.text = ""
            render(current, false, finished)
        }
    }

    private fun popupChoicesSignature(): List<Pair<String, String>> = if (sections.isNotEmpty())
        sections.map { it.id to sectionLabel(it) } else currentRoute()?.episodes.orEmpty().map {
            episodeKey(it) to (com.reelix.app.ui.component.episodeCardPresentation(it).title ?: it.displayName) }

    private fun showVersions() {
        val value = detail ?: return
        val episodes = currentRoute()?.episodes.orEmpty()
        if (theme == null || !value.item.fromLibrary || !com.reelix.app.ui.component.hasNamedVersionChoices(episodes) ||
            sectionPopup?.isShowing == true) return
        val itemId = value.item.id
        val epoch = ++sectionPopupEpoch
        val signature = popupChoicesSignature()
        sectionPopupSignature = signature
        val labels = listOf(context.getString(R.string.theme_all_versions)) + signature.map { it.second }
        sectionPopup = com.reelix.app.ui.component.showAnchoredChoicePopup(folderButton, labels,
            episodes.indexOfFirst { it.playId == selectedVersion }.let { if (it < 0) 0 else it + 1 },
            restoreFocus = {
                if (isAttachedToWindow && detail?.item?.id == itemId && epoch == sectionPopupEpoch)
                    popupFocusReturnTarget(folderButton)?.invoke()
            }) { choice ->
                if (detail?.item?.id != itemId || epoch != sectionPopupEpoch || popupChoicesSignature() != signature)
                    return@showAnchoredChoicePopup
                selectedVersion = if (choice == 0) null else episodes.getOrNull(choice - 1)?.playId
                val target = episodes.firstOrNull { it.playId == selectedVersion }
                    ?: com.reelix.app.data.alist.preferredLibraryEpisode(episodes, value.item.matchedResourcePath,
                        value.item.preferredResourcePath, watchProgress?.sourceId) ?: episodes.first()
                pendingVersionKey = episodeKey(target)
                detail?.let { render(it, false, finished) }
            }
    }

    override fun popupFocusReturnTarget(origin: View): (() -> Boolean)? {
        if (origin !== folderButton && origin !== artworkButton) return null
        val page = detail?.item?.id ?: return null
        val other = if (origin === artworkButton) folderButton else artworkButton
        return {
            if (!isAttachedToWindow || detail?.item?.id != page) false
            else listOf(origin, other, playButton).any {
                it.isAttachedToWindow && it.isShown && it.isEnabled && it.isFocusable && it.requestFocus()
            }
        }
    }

    private fun sectionLabel(section: LibrarySection): String = section.tags.joinToString(" · ") { tag ->
        val label = context.getString(when (tag.kind) {
            LibrarySectionKind.SEASON -> R.string.library_section_season
            LibrarySectionKind.CHAPTER -> R.string.library_section_chapter
            LibrarySectionKind.PART -> R.string.library_section_part
            LibrarySectionKind.FINAL_SEASON -> R.string.library_section_final_season
            LibrarySectionKind.FINAL_CHAPTER -> R.string.library_section_final_chapter
            LibrarySectionKind.OAD -> R.string.library_section_oad
            LibrarySectionKind.OVA -> R.string.library_section_ova
            LibrarySectionKind.SPECIAL -> R.string.library_section_special
            LibrarySectionKind.OTHER -> R.string.library_section_other
        }, tag.number)
        if (tag.part.isEmpty()) label else label + " · " + context.getString(R.string.library_section_part, tag.part)
    }

    private fun visibleEpisodes(): List<Episode> {
        val paths = sections.firstOrNull { it.id == folder }?.paths
        return currentRoute()?.episodes.orEmpty().filter { paths == null || it.playId in paths }
    }
    private fun playbackRoute(): PlayRoute? = currentRoute()?.copy(episodes = visibleEpisodes())
    private fun showSelectedResource(episode: Episode) {
        val mediaName = com.reelix.app.ui.component.episodeResourceName(episode)
        val size = episode.sizeBytes?.takeIf { it > 0 }
            ?.let { com.reelix.app.ui.component.formatMediaSize(it) }
        val resource = context.getString(if (detail?.item?.fromLibrary == true)
            R.string.library_current_resource else R.string.detail_current_episode,
            listOfNotNull(mediaName, size).joinToString(" · "))
        selectedResource.text = routeMatchMessage?.let { context.getString(it) + "\n" + resource } ?: resource
    }

    private fun focusLibraryTool(): Boolean =
        if (folderButton.visibility == VISIBLE) folderButton.requestFocus() else artworkButton.requestFocus()

    private fun rebuildRoutes(routes: List<PlayRoute>) {
        val signature = routes.map { route -> route.name + "\u0000" + route.episodes.size }
        val multipleRoutes = routes.size > 1 && detail?.item?.fromLibrary != true
        routeRow.visibility = if (multipleRoutes) VISIBLE else GONE
        routeScroll.visibility = routeRow.visibility
        routeHeader.visibility = routeRow.visibility
        routeCount.text = context.getString(R.string.detail_route_count, routes.size)
        if (!multipleRoutes) {
            if (routeSignature != signature) {
                routeSignature = signature
                routeChips.clear()
                routeRow.removeAllViews()
            }
            return
        }
        if (routeSignature == signature) {
            updateRouteChips()
            return
        }
        routeSignature = signature
        routeChips.clear()
        routeRow.removeAllViews()
        routes.forEachIndexed { index, route ->
            val chip = context.textView(
                route.name.ifBlank { context.getString(R.string.route_match_fallback_route, index + 1) },
                13f,
                if (index == routeIndex) Color.BLACK else Color.WHITE,
                Gravity.CENTER,
            ).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                maxLines = 1
                maxWidth = context.vDp(300)
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = text
                setPadding(context.vDp(18), 0, context.vDp(18), 0)
                setOnClickListener {
                    cancelPendingRouteSelect()
                    selectRoute(index)
                }
                setOnFocusChangeListener { v, focused ->
                    styleRouteChip(v as TextView, index, focused)
                    if (focused) {
                        scheduleRouteSelect(v, index)
                        routeScroll.post {
                            if (!v.isAttachedToWindow || !v.hasFocus()) return@post
                            val left = v.left
                            val right = v.right
                            val x = routeScroll.scrollX
                            when {
                                left < x -> routeScroll.smoothScrollTo(left, 0)
                                right > x + routeScroll.width -> routeScroll.smoothScrollTo(right - routeScroll.width, 0)
                            }
                        }
                    } else {
                        cancelPendingRouteSelect(v)
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    val exit = KeyRouting.episodeRowExit(keyCode == KeyEvent.KEYCODE_DPAD_UP,
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN, true)
                    when {
                        exit != 0 -> {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                if (exit < 0) playButton.requestFocus()
                                else if (!episodeRow.focusRow()) playButton.requestFocus()
                            }
                            true
                        }
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index == 0 -> true
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index == routes.lastIndex -> true
                        else -> false
                    }
                }
            }
            routeChips += chip
            routeRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    context.vDp(40),
                ).apply { rightMargin = context.vDp(10) },
            )
        }
        updateRouteChips()
    }

    private fun styleRouteChip(chip: TextView, index: Int, focused: Boolean) {
        val selected = index == routeIndex
        val colors = themeRouteColors(controlPalette, selected, focused)
        chip.background = roundedBackground(colors.background, context.vDp(8),
            if (focused) context.vDp(2) else 0, colors.border)
        chip.setTextColor(colors.text)
    }

    private fun updateRouteChips() {
        routeChips.forEachIndexed { index, chip -> styleRouteChip(chip, index, chip.hasFocus()) }
    }

    /**
     * 线路 chip 的"聚焦即切换"延迟 180ms：
     * 快速划过多个 chip 时只在停留的那个上真正切换，避免连续整页重渲染。
     */
    private fun scheduleRouteSelect(chip: View, index: Int) {
        cancelPendingRouteSelect()
        val task = Runnable {
            pendingRouteSelect = null
            pendingRouteSelectView = null
            if (chip.isAttachedToWindow && chip.hasFocus()) selectRoute(index)
        }
        pendingRouteSelectView = chip
        pendingRouteSelect = task
        chip.postDelayed(task, 180L)
    }

    private fun cancelPendingRouteSelect(chip: View? = null) {
        val task = pendingRouteSelect ?: return
        val view = pendingRouteSelectView
        if (chip != null && view !== chip) return
        view?.removeCallbacks(task)
        pendingRouteSelect = null
        pendingRouteSelectView = null
    }

    private fun selectRoute(index: Int) {
        val started = android.os.SystemClock.elapsedRealtime()
        val routes = detail?.routes.orEmpty()
        if (index !in routes.indices || routeIndex == index) {
            updateRouteChips()
            return
        }
        val source = if (routeSelectionActive) routeEpisode ?: routeAnchor
            else currentRoute()?.episodes?.firstOrNull { episodeKey(it) == episodeRow.selectedKey() }
        routeAnchor = source
        val match = source?.let { matchRouteEpisode(it, routes[index].episodes) } ?: RouteEpisodeMatch.Absent
        routeSelectionActive = true
        routeEpisode = (match as? RouteEpisodeMatch.Unique)?.episode
        routeMatchMessage = when (match) {
            is RouteEpisodeMatch.Unique -> null
            is RouteEpisodeMatch.Ambiguous -> R.string.route_match_ambiguous
            RouteEpisodeMatch.Absent -> R.string.route_match_absent
        }
        routeIndex = index
        detail?.let { render(it, loadingRoutes = false, finished = finished) }
        updateRouteChips()
        android.util.Log.i(
            "FocusCost",
            "selectRoute -> $index cost=${android.os.SystemClock.elapsedRealtime() - started}ms",
        )
    }

    private fun currentRoute(): PlayRoute? =
        detail?.routes?.getOrNull(routeIndex) ?: detail?.routes?.firstOrNull()

    private fun updatePlayTime(item: MediaItem, record: com.reelix.app.data.db.PlayProgressEntity?) {
        val duration = record?.durationMs?.takeIf { it > 0 }
            ?: item.durationMin?.toLong()?.times(60_000L)?.takeIf { it > 0 }
            ?: 0L
        val position = record?.positionMs?.takeIf { it > 0 }
            ?: if (duration > 0 && item.progress > 0f) (item.progress * duration).toLong() else 0L
        if (position > 0) {
            playTime.text = if (duration > 0) context.getString(
                R.string.detail_play_time,
                homePlaybackClock(position),
                homePlaybackClock(duration),
            ) else context.getString(R.string.detail_play_time_unknown, homePlaybackClock(position))
            playTime.visibility = VISIBLE
        } else {
            playTime.visibility = GONE
        }
    }

    private fun preferredPlaybackEpisode(
        episodes: List<Episode>,
        item: MediaItem,
    ): Episode? {
        if (item.fromLibrary) {
            // 手动选了版本/季集时尊重用户选择。
            selectedVersion?.let { version ->
                episodes.firstOrNull { it.playId == version }?.let {
                    return it
                }
            }
            // 否则优先回到真正看过的那一个文件；matchedResourcePath 可能只是
            // 当前展示用的文件，不能覆盖播放进度里的 sourceId。
            watchProgress?.sourceId?.let { watched ->
                episodes.firstOrNull { it.playId == watched }?.let {
                    return it
                }
            }
            val fallback = com.reelix.app.data.alist.preferredLibraryEpisode(
                episodes,
                item.matchedResourcePath,
                item.preferredResourcePath,
                null,
            ) ?: episodes.firstOrNull()
            return fallback
        }
        if (routeSelectionActive && routeEpisode != null) return routeEpisode
        val record = watchProgress
        if (record != null && record.episodeIndex > 0) {
            episodes.firstOrNull { it.index == record.episodeIndex }?.let {
                return it
            }
        }
        return episodes.firstOrNull { it.index == item.lastEpisode } ?: episodes.firstOrNull()
    }

    private fun playCurrent() {
        deleteEpisode?.let { target ->
            deleteEpisode = null
            episodeRow.arm(null)
            onDeleteEpisode(target)
            return
        }
        routeMatchMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            episodeRow.focusRow()
            return
        }
        val route = playbackRoute()
        if (route == null) {
            android.widget.Toast.makeText(context, "暂无可用播放源", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val item = detail?.item ?: return
        val episode = preferredPlaybackEpisode(route.episodes, item)
        if (episode == null) {
            android.widget.Toast.makeText(context, "暂无可用播放源", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 从顶部播放键进入，返回时仍回到播放键，而不是跳到某张选集卡。
        EpisodeFocusSession.forget(item.id)
        onPlay(route, episode)
    }

    /**
     * 播放键按 DOWN 的去向。
     *
     * ★ 返回 Boolean（2026-11-07）：原来无条件 `return true`，
     *   当详情页没有线路、没有剧集、也没有相关推荐时（比如片库条目没扫描到、
     *   或在线源详情加载失败），`episodeRow.focusRow()` 返回 false，
     *   按键却被吃掉了 —— 实机表现是"焦点直接从画面上消失，页面也不滚"。
     *   现在没有下级可去时返回 false，把按键放行给系统滚动/搜索。
     */
    private fun onPlayDown(): Boolean = when {
        libraryTools.visibility == VISIBLE -> focusLibraryTool()
        routeRow.visibility == VISIBLE ->
            routeChips.getOrNull(routeIndex)?.requestFocus() ?: episodeRow.focusRow()
        episodeRow.visibility == VISIBLE -> episodeRow.focusRow()
        relatedRow.visibility == VISIBLE -> relatedRow.focusFirst()
        else -> false
    }

    private fun onEpisodeUp() {
        // 选集行按 UP 先回播放按钮，而不是跳到「更换海报」。
        //
        // ★ 之前优先 focusLibraryTool()，实机表现是「选集 ↑ 直接到更换海报」，
        //   用户要多按一次 UP 才回到播放键，与「播放键是主要内容入口」的直觉相反。
        //   播放按钮始终可见且可聚焦，直接回它；只有在它不可用时才逐级回退。
        if (playButton.requestFocus()) return
        if (libraryTools.visibility == VISIBLE && focusLibraryTool()) return
        if (routeRow.visibility == VISIBLE) routeChips.getOrNull(routeIndex)?.requestFocus()
    }

    private fun focusBackToTop() {
        backToTop.requestFocus()
    }

    override fun focusFirst(): Boolean {
        scroll.scrollTo(0, 0)
        return playButton.requestFocus()
    }

    /**
     * 从播放页返回时把焦点还给刚才那张选集卡，方便直接按"下一集"。
     * 没有记录（例如从顶部播放键进入）就返回 false，交给默认行为。
     */
    fun focusLastPlayedEpisode(): Boolean {
        val workId = detail?.item?.id ?: return false
        val key = pendingEpisodeFocusKey ?: EpisodeFocusSession.last(workId) ?: return false
        if (episodeRow.visibility != View.VISIBLE) return false
        if (episodeRow.focusKey(key)) {
            pendingEpisodeFocusKey = null
            // 一次性：真正拿到焦点才清掉，下次重新进详情页仍回到顶部播放键。
            EpisodeFocusSession.forget(workId)
            return true
        }
        scheduleEpisodeFocusRestore(key)
        return false
    }

    /**
     * 详情页刚重建时选集行可能还没布局、窗口也还没拿到焦点，
     * 单次 requestFocus 很容易落空；这里每 50ms 重试一次，最多约 3 秒。
     * 只有卡片确实拿到焦点才清掉一次性记录；始终拿不到才把焦点放回播放键。
     */
    private fun scheduleEpisodeFocusRestore(key: String) {
        pendingEpisodeFocusKey = key
        if (episodeFocusLoopRunning) return
        episodeFocusLoopRunning = true
        fun step(remaining: Int) {
            if (pendingEpisodeFocusKey != key) {
                episodeFocusLoopRunning = false
                return
            }
            val workId = detail?.item?.id
            if (workId != null && episodeRow.visibility == View.VISIBLE && episodeRow.focusKey(key)) {
                pendingEpisodeFocusKey = null
                episodeFocusLoopRunning = false
                EpisodeFocusSession.forget(workId)
                return
            }
            if (remaining <= 0) {
                pendingEpisodeFocusKey = null
                episodeFocusLoopRunning = false
                // 兜底：别让焦点悬空，至少回到播放键。
                if (findFocus() == null) focusFirst()
                return
            }
            postDelayed({ step(remaining - 1) }, 50L)
        }
        post { step(60) }
    }

    override fun saveFocusIndex(): Int = 0

    override fun restoreFocus(index: Int) {
        focusFirst()
    }
}

/**
 * 演员头像行：只显示有头像的演员，最多 20 人，可横向聚焦移动。
 *
 * 焦点边界由 [onExitUp] / [onExitDown] 显式接管，避免默认 focusSearch
 * 在行内到顶时跳到上下相邻控件。
 */
private class CastAvatarRow(context: Context) : HorizontalScrollView(context) {
    var onExitUp: (() -> Unit)? = null
    var onExitDown: (() -> Unit)? = null
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
    }
    private val avatars = mutableListOf<ImageView>()
    private var cards = mutableListOf<LinearLayout>()

    init {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = true
        addView(row, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun submit(credits: List<com.reelix.app.data.image.LibraryPersonCredit>) {
        val shown = credits.filter { !it.avatar.isNullOrBlank() }.take(20)
        if (avatars.size == shown.size && avatars.zip(shown).all { (view, credit) ->
                view.contentDescription == credit.name
            }
        ) return
        row.removeAllViews()
        avatars.clear()
        cards = mutableListOf()
        shown.forEach { credit ->
            val card = object : LinearLayout(context) {
                override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        onExitUp?.invoke()
                        return true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        onExitDown?.invoke()
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = focusStateBackground(
                    Color.TRANSPARENT, 0x22FFFFFF, context.vDp(8),
                    context.vDp(2), ViewTheme.focusRing,
                )
                setPadding(context.vDp(4), context.vDp(4), context.vDp(4), context.vDp(4))
                setOnFocusChangeListener { view, focused ->
                    if (focused) view.post { smoothScrollToCard(view) }
                }
            }
            val avatar = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(28))
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                contentDescription = credit.name
            }
            avatar.load(credit.avatar) {
                crossfade(false)
                placeholder(roundedColorDrawable(0x22FFFFFF, context.vDp(28)))
                error(roundedColorDrawable(0x22FFFFFF, context.vDp(28)))
            }
            card.addView(avatar, LinearLayout.LayoutParams(context.vDp(56), context.vDp(56)))
            card.addView(context.textView(credit.name, 11f, ViewTheme.textPrimary, Gravity.CENTER).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(context.vDp(76), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.vDp(4)
            })
            // 第二行固定显示身份/角色；缺角色时用“演员”，绝不重复显示姓名。
            //
            // ★ 豆瓣对「演自己」的条目会把 character 返回成“自己”，
            //   之前只过滤了「和姓名相同」和「饰」前缀，于是整行都是“自己”。
            //   自指角色统一归成“演员”。
            val role = credit.role
                ?.trim()
                ?.removePrefix("饰")
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.isSelfReferential(credit.name) }
                ?: "演员"
            card.addView(context.textView(role, 10f, ViewTheme.textTertiary, Gravity.CENTER).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(context.vDp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = context.vDp(10)
            })
            cards += card
            avatars += avatar
        }
    }

    /** 把焦点卡滚进可视区，避免焦点走到屏幕外。 */
    fun focusFirst(): Boolean {
        val first = cards.firstOrNull() ?: return false
        if (!first.requestFocus()) return false
        first.post { smoothScrollToCard(first) }
        return true
    }

    /** 把焦点卡滚进可视区，避免焦点走到屏幕外。 */
    private fun smoothScrollToCard(card: View) {
        val left = card.left - context.vDp(16)
        val right = card.right + context.vDp(16)
        val visibleLeft = scrollX
        val visibleRight = scrollX + width
        when {
            left < visibleLeft -> smoothScrollTo(left.coerceAtLeast(0), 0)
            right > visibleRight -> smoothScrollTo(right - width, 0)
        }
    }
}

private fun roundedColorDrawable(color: Int, radius: Int): android.graphics.drawable.Drawable =
    android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

/**
 * 「这个角色名是不是只是重复了演员本人」。
 *
 * 各渠道写法不统一，实际遇到过的自指形式：
 * - 豆瓣：`自己`（演自己）
 * - TMDB：`Himself` / `Herself` / `Self`
 * - 部分源：直接把姓名填进 character
 *
 * 命中就当成没有角色信息，由调用方回退成「演员」。
 */
internal fun String.isSelfReferential(name: String): Boolean {
    val value = trim()
    if (value.isEmpty()) return true
    if (value == name.trim()) return true
    return value.lowercase() in SELF_ROLE_LABELS
}

private val SELF_ROLE_LABELS = setOf(
    "自己", "本人", "饰演自己", "演自己",
    "self", "himself", "herself", "themselves", "themself",
    "self (voice)", "himself (voice)", "herself (voice)",
)

private class PlayButtonView(context: Context) : FrameLayout(context) {
    private val label = context.textView("播放", 14f, Color.BLACK, Gravity.CENTER)
    private val progressTrack = View(context)
    private val progress = View(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        clipChildren = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.vDp(8).toFloat())
            }
        }
        background = roundedBackground(Color.WHITE, context.vDp(8))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        progressTrack.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.vDp(3), Gravity.BOTTOM)
        progressTrack.setBackgroundColor(0x33FFFFFF)
        progressTrack.visibility = GONE
        addView(progressTrack)
        progress.layoutParams = LayoutParams(0, context.vDp(3), Gravity.BOTTOM)
        progress.setBackgroundColor(0xFF4C7DFF.toInt())
        addView(progress)
    }

    fun bind(text: String, progress: Float) {
        label.text = text
        this.progress.visibility = if (progress > 0f) VISIBLE else GONE
        progressTrack.visibility = if (progress > 0f) VISIBLE else GONE
        this.progress.post {
            val lp = this.progress.layoutParams as LayoutParams
            val trackWidth = progressTrack.width.takeIf { it > 0 } ?: width
            lp.width = (trackWidth * progress.coerceIn(0f, 1f)).toInt()
            this.progress.layoutParams = lp
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    fun setTheme(palette: NativeThemePalette) {
        val style = palette.playButtonStyle()
        applyPlayButton(this, style)
        setThemeGlyph(label, "play")
        progress.setBackgroundColor(style.progressFill)
        progressTrack.setBackgroundColor(style.progressTrack)
    }

    fun setLabelSize(sizeSp: Float) {
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        label.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
        setThemeGlyph(label, "play")
    }
}

private class DetailBackdropView(context: Context) : FrameLayout(context) {
    var onArtworkChanged: (() -> Unit)? = null
    val hasWide: Boolean get() = wideLoaded
    val hasPoster: Boolean get() = posterLoaded
    private var externalPoster = false
    private var alwaysPoster = false
    private val image = ImageView(context)
    private val posterBackground = ImageView(context)
    private val posterDim = View(context)
    private val foregroundPoster = ImageView(context)
    private val blurred = ImageView(context)
    private val episodeDim = View(context)
    private val blurScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var blurJob: Job? = null
    private var blurKey: String? = null
    private var episodeFocused = false
    private var overrideUrl: String? = null
    private var lastItemId: String? = null
    private var signature: List<Any?>? = null
    private var wideSignature: List<Any?>? = null
    private var generation = 0L
    private var posterLoaded = false
    private var wideLoaded = false
    private var waitingKnownWide = false
    private var posterWasVisible = false

    init {
        posterBackground.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        posterBackground.scaleType = ImageView.ScaleType.CENTER_CROP
        posterBackground.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        posterBackground.alpha = 0f
        addView(posterBackground)
        posterDim.setBackgroundColor(0x660B0B0D)
        posterDim.visibility = GONE
        addView(posterDim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        image.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // Wide images retain the existing top-aligned crop; poster backgrounds can crop freely.
        image.scaleType = ImageView.ScaleType.MATRIX
        image.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTopCrop() }
        image.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        image.alpha = 0f
        addView(image)
        foregroundPoster.layoutParams = LayoutParams(context.vDp(160), context.vDp(240), Gravity.TOP or Gravity.START).apply {
            leftMargin = context.vDp(56); topMargin = context.vDp(70)
        }
        foregroundPoster.scaleType = ImageView.ScaleType.CENTER_CROP
        foregroundPoster.background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(8))
        foregroundPoster.clipToOutline = true
        foregroundPoster.isFocusable = false
        foregroundPoster.isClickable = false
        foregroundPoster.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        foregroundPoster.visibility = GONE
        addView(foregroundPoster)
        blurred.scaleType = ImageView.ScaleType.FIT_XY
        blurred.alpha = 0f
        blurred.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(blurred, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        episodeDim.setBackgroundColor(0xB80B0B0D.toInt())
        episodeDim.alpha = 0f
        episodeDim.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(episodeDim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> prepareBlur() }
    }

    fun setContentScroll(y: Int) {
        // The poster ends at 310dp and the title starts at 330dp; both move by the same offset.
        if (!externalPoster) foregroundPoster.translationY = -y.toFloat()
    }

    fun prepareThemePoster(alwaysVisible: Boolean): ImageView {
        externalPoster = true
        alwaysPoster = alwaysVisible
        foregroundPoster.translationY = 0f
        foregroundPoster.detachForTheme()
        return foregroundPoster
    }

    fun setEpisodeFocus(focused: Boolean) {
        if (episodeFocused == focused) return
        episodeFocused = focused
        blurred.animate().cancel(); episodeDim.animate().cancel()
        blurred.animate().alpha(if (focused && blurred.drawable != null) 1f else 0f).setDuration(180).start()
        episodeDim.animate().alpha(if (focused) 1f else 0f).setDuration(180).start()
        if (focused) prepareBlur()
    }

    override fun onDetachedFromWindow() {
        blurJob?.cancel(); blurJob = null
        blurKey = null
        blurred.animate().cancel(); episodeDim.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun prepareBlur() {
        if (!isAttachedToWindow || width <= 0 || height <= 0 || (!posterLoaded && !wideLoaded)) return
        val key = "${signature}:${wideLoaded}:${width}x${height}"
        if (blurKey == key) return
        blurJob?.cancel()
        blurKey = key
        DetailBlurCache.get(key)?.let {
            blurred.setImageBitmap(it)
            blurred.alpha = if (episodeFocused) 1f else 0f
            return
        }
        val source = if (wideLoaded) image else posterBackground
        val w = 192
        val h = (height * w / width).coerceIn(1, 192)
        val snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(snapshot)
        canvas.scale(w.toFloat() / width, h.toFloat() / height)
        source.draw(canvas)
        blurJob = blurScope.launch {
            val result = withContext(Dispatchers.Default) { DetailBlurCache.blur(snapshot) }
            DetailBlurCache.put(key, result)
            if (blurKey == key && isAttachedToWindow) {
                blurred.setImageBitmap(result)
                if (episodeFocused) blurred.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    /** 等比铺满容器，水平居中、顶部对齐 —— 只裁下半部分 */
    private fun applyTopCrop() {
        val d = image.drawable ?: return
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        val vw = image.width
        val vh = image.height
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return
        val scale = maxOf(vw.toFloat() / dw, vh.toFloat() / dh)
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate((vw - dw * scale) / 2f, 0f)
        image.imageMatrix = m
    }

    fun setOverrideBackdrop(url: String?) {
        overrideUrl = url
    }

    fun bind(item: MediaItem, defer: Boolean = false) {
        if (lastItemId != item.id) {
            lastItemId = item.id
            generation++
            blurJob?.cancel(); blurKey = null
            blurred.setImageDrawable(null); blurred.alpha = 0f
            image.dispose(); posterBackground.dispose()
            image.setImageDrawable(null); posterBackground.setImageDrawable(null); foregroundPoster.setImageDrawable(null)
            signature = null; wideSignature = null
            posterLoaded = false; wideLoaded = false; waitingKnownWide = false; posterWasVisible = false
            present()
        }
        if (defer) return
        val wideUrls = listOfNotNull(overrideUrl, item.backdrop).filter { it.isNotBlank() }.distinct()
        val localWide = LocalImageStore.id(context, item.backdropRes)
        val localPoster = LocalImageStore.id(context, item.posterRes)
        val posterUrl = item.poster?.takeIf { it.isNotBlank() }
        val nextWideSignature = listOf(wideUrls, localWide)
        val nextSignature = listOf(nextWideSignature, posterUrl, localPoster)
        if (signature == nextSignature) return
        signature = nextSignature
        if (wideLoaded && wideSignature == nextWideSignature && !alwaysPoster) return
        wideSignature = nextWideSignature
        val token = ++generation
        image.dispose(); posterBackground.dispose()
        wideLoaded = false
        waitingKnownWide = (localWide != 0 || wideUrls.isNotEmpty()) && !posterWasVisible
        present()

        var posterStarted = false
        fun showPoster(reveal: Boolean = true) {
            if (token != generation) return
            if (reveal) waitingKnownWide = false
            present()
            if (posterStarted) return
            posterStarted = true
            if (localPoster != 0) {
                posterBackground.setImageResource(localPoster)
                foregroundPoster.setImageResource(localPoster)
                posterLoaded = true
                present()
            } else if (posterUrl != null) {
                posterBackground.load(posterUrl) {
                    // The small blur snapshot uses a software Canvas on API21+ TVs.
                    allowHardware(false)
                    precision(Precision.INEXACT); scale(Scale.FIT); crossfade(false)
                    listener(onSuccess = { _, result ->
                        if (token == generation) {
                            foregroundPoster.setImageDrawable(result.drawable.constantState?.newDrawable(resources)?.mutate() ?: result.drawable)
                            posterLoaded = true
                            present()
                        }
                    }, onError = { _, _ ->
                        if (token == generation) {
                            posterLoaded = false
                            foregroundPoster.setImageDrawable(null)
                            present()
                        }
                    })
                }
            } else {
                posterLoaded = false
                foregroundPoster.setImageDrawable(null)
                present()
            }
        }

        fun tryWide(position: Int) {
            if (token != generation) return
            val url = wideUrls.getOrNull(position)
            if (url == null) { showPoster(); return }
            image.load(url) {
                allowHardware(false)
                precision(Precision.INEXACT); scale(Scale.FIT); crossfade(false)
                listener(onSuccess = { _, result ->
                    if (token == generation) {
                        // A returned portrait or a placeholder is not a usable wide backdrop.
                        if (result.drawable.intrinsicWidth > result.drawable.intrinsicHeight && result.drawable.intrinsicHeight >= 80) {
                            wideLoaded = true; waitingKnownWide = false
                            applyTopCrop(); present()
                        } else tryWide(position + 1)
                    }
                }, onError = { _, _ -> if (token == generation) tryWide(position + 1) })
            }
        }

        if (alwaysPoster) showPoster(reveal = false)
        if (localWide != 0 && overrideUrl.isNullOrBlank()) {
            image.setImageResource(localWide)
            val drawable = image.drawable
            if (drawable != null && drawable.intrinsicWidth > drawable.intrinsicHeight) {
                wideLoaded = true; waitingKnownWide = false
                applyTopCrop(); present()
            } else tryWide(0)
        } else tryWide(0)
    }

    private fun present() {
        val mode = detailArtworkMode(posterLoaded, wideLoaded, waitingKnownWide, posterWasVisible)
        if (mode == DetailArtworkMode.POSTER) posterWasVisible = true
        posterBackground.alpha = if (mode == DetailArtworkMode.POSTER) 1f else 0f
        posterDim.visibility = if (mode == DetailArtworkMode.POSTER) VISIBLE else GONE
        foregroundPoster.visibility = if ((alwaysPoster && posterLoaded) || mode == DetailArtworkMode.POSTER) VISIBLE else GONE
        image.alpha = if (mode == DetailArtworkMode.WIDE) 1f else 0f
        if (mode != DetailArtworkMode.NEUTRAL) prepareBlur()
        onArtworkChanged?.invoke()
    }
}

/** Tiny bitmaps keep older TVs independent of API31 effects and bound both CPU work and memory. */
private object DetailBlurCache {
    private val cache = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun blur(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        var pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            val horizontal = IntArray(pixels.size)
            val output = IntArray(pixels.size)
            fun average(x: Int, y: Int, horizontalPass: Boolean, src: IntArray): Int {
                var r = 0; var g = 0; var b = 0
                for (d in -3..3) {
                    val xx = if (horizontalPass) (x + d).coerceIn(0, w - 1) else x
                    val yy = if (horizontalPass) y else (y + d).coerceIn(0, h - 1)
                    val color = src[yy * w + xx]
                    r += color shr 16 and 255; g += color shr 8 and 255; b += color and 255
                }
                return Color.rgb(r / 7, g / 7, b / 7)
            }
            for (y in 0 until h) for (x in 0 until w) horizontal[y * w + x] = average(x, y, true, pixels)
            for (y in 0 until h) for (x in 0 until w) output[y * w + x] = average(x, y, false, horizontal)
            pixels = output
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}

private class DetailScrimView(context: Context) : View(context) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            // ★ 2026-11-07：压黑区间从 0.35 下移到 0.75。
            //
            //   原来 0.35 处就压到 74% 不透明、0.62 处 96%，等于把横版海报
            //   抹掉了下面 2/3（用户："横图被抹掉了一半"）。
            //   现在 0~75% 完全透明（图完整露出），只在播放按钮那一条往下
            //   快速压黑，保证文字和按钮可读。
            //
            //   如果实测发现 44sp 的标题（约在 0.63）压在亮图上不清楚，
            //   把第二个 stop 从 0.75 调回 0.65 即可，其余不用动。
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(130, 11, 11, 13),
                Color.argb(225, 11, 11, 13),
                ViewTheme.backdrop,
            ),
            floatArrayOf(0f, 0.75f, 0.86f, 0.95f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), h, paint)
    }
}

private class RelatedRowView(
    context: Context,
    private val onClick: (MediaItem) -> Unit,
    private val onExitUp: () -> Unit,
    private val onExitDown: () -> Unit,
) : FrameLayout(context) {
    private val adapter = Adapter(onClick)
    private val grid = HorizontalGridView(context).apply {
        setNumRows(1)
        setHorizontalSpacing(context.vDp(14))
        setVerticalSpacing(0)
        setPadding(0, 0, 0, 0)
        clipToPadding = true
        setHasFixedSize(true)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        adapter = this@RelatedRowView.adapter
    }

    init {
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        grid.setOnKeyInterceptListener { event ->
            when {
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    event.action == KeyEvent.ACTION_DOWN -> {
                    grid.post { onExitUp() }
                    true
                }
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    event.action == KeyEvent.ACTION_DOWN -> {
                    grid.post { onExitDown() }
                    true
                }
                else -> false
            }
        }
    }

    fun submit(items: List<MediaItem>) {
        adapter.submit(items)
    }

    fun focusFirst(): Boolean {
        if (!grid.requestFocus()) return false
        grid.post {
            val holder = grid.findViewHolderForAdapterPosition(0)
            holder?.itemView?.requestFocus() ?: grid.requestFocus()
        }
        return true
    }

    private class Adapter(
        private val onClick: (MediaItem) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<MediaItem> = emptyList()

        inner class Holder(val card: PosterTileView) : RecyclerView.ViewHolder(card)

        fun submit(next: List<MediaItem>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = PosterTileView(parent.context)
            card.minimumWidth = parent.context.vDp(112)
            val holder = Holder(card)
            card.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            card.setOnKeyListener { _, keyCode, _ ->
                val pos = holder.bindingAdapterPosition
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos <= 0) ||
                    (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos >= items.lastIndex)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val width = holder.card.context.vDp(112)
            holder.card.bind(
                PosterTile(
                    key = item.id,
                    title = item.title,
                    posterUrl = item.poster,
                    posterRes = item.posterRes,
                    progress = item.progress,
                ),
                width,
                (width * 3) / 2,
            )
        }
    }
}
