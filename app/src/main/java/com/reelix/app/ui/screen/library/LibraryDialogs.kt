package com.reelix.app.ui.screen.library

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.reelix.app.R
import com.reelix.app.data.alist.*
import com.reelix.app.data.model.MediaItem
import com.reelix.app.data.repo.LibraryRepository
import com.reelix.app.data.image.*
import com.reelix.app.data.model.MediaKind
import coil.load
import coil.dispose
import com.reelix.app.ui.component.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

private data class LibraryDialogShell(
    val dialog: Dialog,
    val root: LinearLayout,
    val scope: CoroutineScope,
    val focus: LibraryDialogFocusCallbacks,
)

private class LibraryDialogFocusCallbacks(private val dialog: Dialog, private val root: View) {
    private val state = LibraryDialogFocusState()
    private var pending: LibraryDialogFocusRequest? = null
    val canFocus: Boolean get() = state.accepts(state.generation) && dialog.isShowing

    fun accepts(ticket: Long): Boolean = state.accepts(ticket) && dialog.isShowing
    fun invalidate(): Long {
        pending?.cancel()
        pending = null
        return state.invalidate()
    }
    fun openChild() { invalidate(); state.openChild() }
    fun closeChild() { invalidate(); state.closeChild() }
    fun close() { invalidate(); state.close() }
    fun request(action: () -> Unit) {
        if (!canFocus) return
        val ticket = invalidate()
        pending = LibraryDialogFocusRequest(root, { accepts(ticket) }, action).also { it.start() }
    }
}

/** One cancellable request waits for layout AND window ownership, not a fixed delay. */
private class LibraryDialogFocusRequest(
    private val root: View,
    private val valid: () -> Boolean,
    private val action: () -> Unit,
) : Runnable, android.view.ViewTreeObserver.OnGlobalLayoutListener, android.view.ViewTreeObserver.OnWindowFocusChangeListener,
    View.OnAttachStateChangeListener {
    private val observer = root.viewTreeObserver
    private var cancelled = false
    fun start() {
        observer.addOnGlobalLayoutListener(this)
        observer.addOnWindowFocusChangeListener(this)
        root.addOnAttachStateChangeListener(this)
        root.post(this)
    }
    fun cancel() {
        cancelled = true
        root.removeCallbacks(this)
        root.removeOnAttachStateChangeListener(this)
        if (observer.isAlive) {
            observer.removeOnGlobalLayoutListener(this)
            observer.removeOnWindowFocusChangeListener(this)
        }
        val currentObserver = root.viewTreeObserver
        if (currentObserver !== observer && currentObserver.isAlive) {
            currentObserver.removeOnGlobalLayoutListener(this)
            currentObserver.removeOnWindowFocusChangeListener(this)
        }
    }
    override fun run() {
        if (cancelled) return
        if (!valid()) { cancel(); return }
        if (!root.isAttachedToWindow || !root.isLaidOut || root.isLayoutRequested || !root.hasWindowFocus()) return
        cancel()
        action()
    }
    override fun onGlobalLayout() = run()
    override fun onWindowFocusChanged(hasFocus: Boolean) { if (hasFocus) run() }
    override fun onViewAttachedToWindow(view: View) = run()
    override fun onViewDetachedFromWindow(view: View) = cancel()
}

private class LibraryDialogViewAnchor(private val original: View) {
    private val explicitReturn = generateSequence(original.parent as? View) { it.parent as? View }
        .filterIsInstance<PopupFocusReturnHost>()
        .mapNotNull { it.popupFocusReturnTarget(original) }.firstOrNull()
    private val siblings = (original.parent as? ViewGroup)?.let { parent ->
        val index = parent.indexOfChild(original)
        (0 until parent.childCount).filter { it != index }
            .sortedWith(compareBy<Int> { kotlin.math.abs(it - index) }.thenByDescending { it })
            .map { parent.getChildAt(it) }
    }.orEmpty()

    fun restore() {
        explicitReturn?.let { it(); return }
        // Never ask a container to choose its first descendant as an implicit fallback.
        for (view in listOf(original) + siblings) {
            if (view !is ViewGroup && view.isAttachedToWindow && view.isShown &&
                view.isEnabled && view.isFocusable && view.requestFocus()) return
        }
    }
}

private fun libraryDialogActivityAnchor(context: Context): LibraryDialogViewAnchor? {
    var current = context
    while (current is android.content.ContextWrapper && current !is android.app.Activity) {
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    val activity = current as? android.app.Activity ?: return null
    val anchor = activity.currentFocus?.let(::LibraryDialogViewAnchor)
    cancelAnchoredChoicePopupFocus(activity.window.decorView)
    return anchor
}

/** Dismissal cancels search/preview only. Confirmed downloads belong to the process. */
object LibraryDialogs {
    private fun button(context: Context, label: Int, action: () -> Unit): TextView =
        context.textView(context.getString(label), 14f, ViewTheme.textPrimary, Gravity.CENTER).apply {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            setPadding(context.vDp(16), context.vDp(8), context.vDp(16), context.vDp(8))
            background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(6))
            setOnFocusChangeListener { view, focused -> view.background = roundedBackground(ViewTheme.surfaceElevated,
                context.vDp(6), if (focused) context.vDp(2) else 0, ViewTheme.focusRing) }
            setOnClickListener { action() }
        }
    private fun shell(context: Context, title: Int): LibraryDialogShell {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.vDp(24), context.vDp(20), context.vDp(24), context.vDp(20))
            setBackgroundColor(ViewTheme.backdrop)
        }
        root.addView(context.textView(context.getString(title), 24f, ViewTheme.textPrimary))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val focus = LibraryDialogFocusCallbacks(dialog, root)
        dialog.setOnDismissListener { focus.close(); scope.cancel() }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return LibraryDialogShell(dialog, root, scope, focus)
    }
    private fun show(context: Context, dialog: Dialog) {
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * .92).toInt(),
            (context.resources.displayMetrics.heightPixels * .9).toInt())
    }

    fun directories(context: Context, repo: LibraryRepository, scanRoot: String) {
        val returnFocus = libraryDialogActivityAnchor(context)
        val (dialog, root, scope, focus) = shell(context, R.string.library_manage_directories)
        lateinit var previewAction: (String, DirectoryMode?, View) -> Unit
        val manager = NativeDirectoryManagerView(context, scanRoot) { path, mode, origin ->
            previewAction(path, mode, origin)
        }
        root.addView(manager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val status = context.textView("", 13f, ViewTheme.textSecondary)
        root.addView(status)
        var busy = false
        var previewDialog: Dialog? = null
        dialog.setOnDismissListener {
            focus.close()
            previewDialog?.dismiss()
            scope.cancel()
            returnFocus?.restore()
        }
        fun preview(target: String, mode: DirectoryMode?, origin: View) {
            if (busy) return
            val cached = repo.currentScan ?: run { status.text = "尚未扫描，没有可预览的缓存"; return }
            val expectedStatus = repo.scanStatus.value
            if (expectedStatus.running) {
                status.setText(R.string.directory_preview_scan_running)
                return
            }
            val rules = repo.directoryRules()
            val returnToMode = LibraryDialogViewAnchor(origin)
            focus.invalidate()
            busy = true
            status.setText(R.string.directory_preview_calculating)
            scope.launch {
                var opened = false
                fun isCurrent() = repo.currentScan === cached && repo.directoryRules() == rules &&
                    repo.scanStatus.value == expectedStatus
                try {
                    val result = withContext(Dispatchers.Default) {
                        DirectoryRulePreview.calculate(cached, rules, target, mode)
                    }
                    ensureActive()
                    if (!isCurrent()) {
                        status.setText(R.string.directory_preview_stale)
                        return@launch
                    }
                    focus.openChild()
                    previewDialog = previewDirectoryRule(context, result, expectedStatus.error,
                        onConfirm = {
                            check(isCurrent()) { context.getString(R.string.directory_preview_stale) }
                            repo.setDirectoryMode(target, mode)
                        },
                        onClosed = {
                            busy = false
                            status.text = ""
                            previewDialog = null
                            focus.closeChild()
                            if (dialog.isShowing) {
                                manager.refresh(repo.currentScan, repo.directoryRules())
                                focus.request { returnToMode.restore() }
                            }
                        })
                    opened = true
                    status.text = ""
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = context.getString(R.string.library_operation_failed, e.message.orEmpty()) }
                finally { if (!opened) { busy = false; focus.closeChild() } }
            }
        }
        previewAction = ::preview
        var backHeld = false
        dialog.setOnKeyListener { _, code, event ->
            if (code != android.view.KeyEvent.KEYCODE_BACK) false else {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) backHeld = true
                if (event.action == android.view.KeyEvent.ACTION_UP && backHeld) {
                    backHeld = false
                    if (!event.isCanceled && !manager.handleBack()) dialog.dismiss()
                }
                true
            }
        }
        manager.refresh(repo.currentScan, repo.directoryRules())
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        focus.request { manager.focusList() }
    }

    private fun previewDirectoryRule(
        context: Context,
        preview: DirectoryRulePreview.Result,
        scanError: String?,
        onConfirm: suspend () -> Unit,
        onClosed: () -> Unit,
    ): Dialog {
        val (dialog, root, scope, focus) = shell(context, R.string.directory_preview_title)
        val modeLabel = context.getString(when (preview.mode) {
            DirectoryMode.WORK -> R.string.library_directory_work_title
            DirectoryMode.CATEGORY -> R.string.library_directory_category_title
            null -> R.string.library_directory_auto_title
        })
        root.addView(context.textView(context.getString(R.string.directory_preview_target, modeLabel, preview.path),
            16f, ViewTheme.textPrimary).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE })
        root.addView(context.textView(context.getString(R.string.directory_preview_counts,
            preview.pathVideos, preview.beforePosters, preview.afterPosters, preview.added.size, preview.removed.size),
            14f, ViewTheme.textPrimary))
        root.addView(context.textView(context.getString(R.string.directory_preview_ownership_counts,
            preview.ownershipChanges.size, preview.unchangedVideos), 14f, ViewTheme.textPrimary))
        val partial = preview.failures.isNotEmpty() || preview.archivedVideos > 0 || scanError != null
        root.addView(context.textView(if (partial) {
            context.getString(R.string.directory_preview_partial, preview.failures.size, preview.archivedVideos)
        } else context.getString(R.string.directory_preview_cached_only), 13f, ViewTheme.textSecondary))
        val details = buildList {
            add(context.getString(R.string.directory_preview_cache_boundary))
            add(context.getString(R.string.directory_preview_path, preview.path))
            if (partial) {
                add(context.getString(R.string.directory_preview_partial, preview.failures.size, preview.archivedVideos))
            }
            scanError?.let { add(context.getString(R.string.directory_preview_scan_error, it)) }
            preview.failures.forEach {
                add(context.getString(R.string.directory_preview_failure, it.path, it.message))
            }
            if (preview.added.isEmpty() && preview.removed.isEmpty() && preview.ownershipChanges.isEmpty()) {
                add(context.getString(R.string.directory_preview_unchanged))
            }
            preview.added.forEach {
                add(context.getString(R.string.directory_preview_added, it.title, it.owner, it.videoCount))
            }
            preview.removed.forEach {
                add(context.getString(R.string.directory_preview_removed, it.title, it.owner, it.videoCount))
            }
            preview.ownershipChanges.forEach {
                add(context.getString(R.string.directory_preview_moved, it.resource.path,
                    it.before.title, it.before.owner, it.after.title, it.after.owner))
            }
        }
        val list = ListView(context).apply {
            dividerHeight = context.vDp(4)
            isFocusable = true
            isFocusableInTouchMode = true
            selector = context.getDrawable(R.drawable.library_list_focus)
            adapter = object : ArrayAdapter<String>(context, 0, details) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    ((convertView as? TextView) ?: context.textView(sizeSp = 14f, color = ViewTheme.textSecondary)).apply {
                        text = details[position]
                        setPadding(context.vDp(12), context.vDp(10), context.vDp(12), context.vDp(10))
                    }
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = context.vDp(12)
            bottomMargin = context.vDp(12)
        })
        val status = context.textView("", 13f, ViewTheme.textSecondary).apply { maxLines = 3 }
        root.addView(status)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions)
        var applying = false
        val cancel = button(context, R.string.directory_preview_cancel) { if (!applying) dialog.dismiss() }
        val confirm = button(context, R.string.directory_preview_apply) {
            if (applying) return@button
            applying = true
            dialog.setCancelable(false)
            status.setText(R.string.directory_preview_applying)
            scope.launch {
                try {
                    onConfirm()
                    dialog.dismiss()
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    status.text = context.getString(R.string.library_operation_failed, e.message.orEmpty())
                } finally {
                    applying = false
                    dialog.setCancelable(true)
                }
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(confirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = context.vDp(12)
        })
        list.id = View.generateViewId()
        cancel.id = View.generateViewId()
        confirm.id = View.generateViewId()
        // A large diff must not require traversing every resource before reaching the actions.
        list.nextFocusRightId = cancel.id
        cancel.nextFocusUpId = list.id
        confirm.nextFocusUpId = list.id
        cancel.nextFocusRightId = confirm.id
        confirm.nextFocusLeftId = cancel.id
        dialog.setOnDismissListener { focus.close(); scope.cancel(); onClosed() }
        show(context, dialog)
        focus.request { list.setSelection(0); list.requestFocus() }
        return dialog
    }

    /**
     * Drive deletion confirmation. Single files confirm once; whole works require two
     * confirmations because subtitles/nfo/artwork inside the folder are deleted too.
     */
    fun deleteConfirm(
        context: Context,
        heading: String,
        workName: String,
        path: String,
        stats: List<String>,
        twice: Boolean,
        /** Return null on success; a message means failure and keeps the card open. */
        onConfirm: suspend () -> String?,
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val focus = LibraryDialogFocusCallbacks(dialog, dialog.window?.decorView ?: View(context))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.vDp(26), context.vDp(22), context.vDp(26), context.vDp(20))
            background = GradientDrawable().apply {
                cornerRadius = context.vDp(16).toFloat()
                setColor(ViewTheme.surfaceElevated)
                setStroke(context.vDp(1), 0x1AFFFFFF)
            }
            elevation = context.vDp(14).toFloat()
        }
        // Title row: warning glyph + verb, so the object of deletion is obvious at a glance.
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(context.textView("⚠", 20f, 0xFFE58A80.toInt()), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = context.vDp(8)
        })
        titleRow.addView(context.textView(heading, 20f, ViewTheme.textPrimary).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(titleRow)

        card.addView(context.textView(workName, 18f, ViewTheme.textPrimary).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(14) })
        if (path.isNotBlank()) {
            val pathView = context.textView(path, 12f, ViewTheme.textTertiary).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                setOnFocusChangeListener { view, focused ->
                    (view as TextView).maxLines = if (focused) 6 else 1
                    view.requestLayout()
                }
            }
            card.addView(pathView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(6) })
        }
        if (stats.isNotEmpty()) {
            val statsBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.vDp(14), context.vDp(10), context.vDp(14), context.vDp(10))
                background = roundedBackground(0x14FFFFFF, context.vDp(10))
            }
            stats.forEach { line ->
                statsBlock.addView(context.textView(line, 13f, ViewTheme.textSecondary))
            }
            card.addView(statsBlock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(12) })
        }
        card.addView(context.textView(context.getString(R.string.library_delete_permanent), 13f,
            0xFFE58A80.toInt()).apply { setPadding(0, context.vDp(12), 0, 0) })
        val status = context.textView("", 13f, ViewTheme.textSecondary).apply { maxLines = 3 }
        card.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(6) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        card.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(16) })
        var stage = 0
        var busy = false
        lateinit var confirm: TextView
        val onDelete: () -> Unit = {
            if (busy) {
                Unit
            } else if (twice && stage == 0) {
                stage = 1
                confirm.text = context.getString(R.string.library_delete_final)
                status.text = context.getString(R.string.library_delete_second_hint)
                card.background = GradientDrawable().apply {
                    cornerRadius = context.vDp(16).toFloat()
                    setColor(ViewTheme.surfaceElevated)
                    setStroke(context.vDp(2), 0xFFD9534F.toInt())
                }
            } else {
                busy = true
                dialog.setCancelable(false)
                status.text = context.getString(R.string.library_deleting)
                scope.launch {
                    try {
                        val failure = onConfirm()
                        // A returned message means the deletion failed; keep the card open so
                        // the reason stays readable instead of flashing a toast.
                        if (failure == null) dialog.dismiss() else status.text = failure
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status.text = e.message ?: context.getString(R.string.library_delete_failed)
                    } finally {
                        busy = false
                        dialog.setCancelable(true)
                    }
                }
            }
        }
        confirm = dialogAction(context,
            if (twice) R.string.library_delete_continue else R.string.action_confirm, true, onDelete)
        // BACK is the cancel action, so a separate cancel button only adds noise.
        actions.addView(confirm, LinearLayout.LayoutParams(context.vDp(200), context.vDp(44)))
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener { focus.close(); scope.cancel() }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout(
            minOf(context.vDp(440), (metrics.widthPixels * 0.42f).toInt()),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        focus.request { confirm.requestFocus() }
        return dialog
    }

    /** Narrow-card action button: neutral cancel vs low-saturation destructive confirm. */
    private fun dialogAction(context: Context, label: Int, destructive: Boolean,
        action: () -> Unit): TextView =
        context.textView(context.getString(label), 14f,
            if (destructive) 0xFFFFFFFF.toInt() else ViewTheme.textPrimary, Gravity.CENTER).apply {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            setPadding(context.vDp(16), 0, context.vDp(16), 0)
            val fill = if (destructive) 0xFFB4463F.toInt() else ViewTheme.surfaceHigh
            background = roundedBackground(fill, context.vDp(8), context.vDp(1),
                if (destructive) 0x33FFFFFF else 0x1AFFFFFF)
            setOnFocusChangeListener { view, focused ->
                view.background = roundedBackground(fill, context.vDp(8),
                    context.vDp(if (focused) 2 else 1),
                    if (focused) ViewTheme.focusRing else if (destructive) 0x33FFFFFF else 0x1AFFFFFF)
            }
            setOnClickListener { action() }
        }

    fun artwork(context: Context, repo: LibraryRepository, item: MediaItem,
        searchCandidates: (String) -> Flow<ManualLibraryArtworkResult>,
        onlineOnly: Boolean = false,
        filterSources: List<LibraryArtworkSource> = emptyList(),
        onOnlineApplied: ((LibraryWorkIdentity?) -> Unit)? = null) {
        val returnFocus = libraryDialogActivityAnchor(context)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val downloads = LibraryArtworkDownloads.get(context)
        val taskKey = repo.artworkTaskKey(item.id)
        scope.launch {
            downloads.states.collect { states ->
                val task = states[taskKey]
                if (task?.phase == ArtworkDownloadPhase.SUCCEEDED ||
                    task?.phase == ArtworkDownloadPhase.FAILED
                ) dialog.dismiss()
            }
        }
        var results: List<LibraryArtworkCandidate> = emptyList()
        var searchJob: Job? = null
        var searchGeneration = 0L
        lateinit var galleryView: ArtworkGalleryView
        fun search() {
            val query = galleryView.query()
            if (query.isBlank()) return
            searchJob?.cancel()
            val generation = ++searchGeneration
            results = emptyList()
            galleryView.submit(emptyList(), context.getString(R.string.library_artwork_searching))
            searchJob = scope.launch {
                try {
                    val responses = linkedMapOf<LibraryArtworkSource, ManualLibraryArtworkResult>()
                    searchCandidates(query).collect { response ->
                        response.source?.let { responses[it] = response }
                        val found = if (response.source == null) response.candidates else LibraryArtworkSource.entries.flatMap {
                            responses[it]?.candidates.orEmpty()
                        }.distinctBy(::artworkCandidateKey)
                        ensureActive()
                        if (!dialog.isShowing || generation != searchGeneration) return@collect
                        results = found
                        val providerText = responses.entries.joinToString("   ·   ") { (provider, value) ->
                            context.getString(when (provider) {
                                LibraryArtworkSource.TMDB -> R.string.library_artwork_tmdb
                                LibraryArtworkSource.DOUBAN -> R.string.library_artwork_douban
                                LibraryArtworkSource.ONLINE -> R.string.library_artwork_online
                                LibraryArtworkSource.STREAMX -> R.string.library_artwork_streamx
                                LibraryArtworkSource.TVMAZE -> R.string.library_artwork_tvmaze
                            }) + "：" + when (value.phase) {
                                ManualArtworkPhase.LOADING -> context.getString(R.string.library_artwork_searching)
                                ManualArtworkPhase.READY -> "${value.candidates.size} 项"
                                ManualArtworkPhase.EMPTY -> context.getString(R.string.library_artwork_gallery_empty)
                                ManualArtworkPhase.ERROR -> value.error.orEmpty()
                            }
                        }
                        galleryView.submit(found, providerText)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (dialog.isShowing && generation == searchGeneration) {
                        galleryView.setSearching(context.getString(R.string.library_artwork_search_failed))
                    }
                }
            }
        }
        galleryView = ArtworkGalleryView(
            context = context,
            title = context.getString(R.string.library_change_artwork),
            onClose = { dialog.dismiss() },
            onSearch = {
                galleryView.cancelPendingCandidateFocus()
                search()
            },
            onlineOnly = onlineOnly,
            filterSources = filterSources,
            onApply = { selection ->
                // 有作品身份才同步资料；没有就只换图。
                val syncMetadata = selection.candidate.identity != null
            if (onlineOnly) {
                OnlineArtworkOverrides.save(
                    item,
                    selection.candidate.poster,
                    selection.candidate.backdrop,
                    selection.candidate.source?.name,
                    selection.candidate.identity,
                )
                onOnlineApplied?.invoke(selection.candidate.identity)
            } else {
                downloads.submit(repo, item, selection.candidate, syncMetadata)
            }
                Toast.makeText(context, R.string.library_artwork_download_started, Toast.LENGTH_LONG).show()
                dialog.dismiss()
            },
        )
        galleryView.setWorkInfo(
            listOfNotNull(item.title, item.year?.toString()).joinToString(" · "),
        )
        galleryView.setSearchQuery(item.title)
        dialog.setContentView(galleryView)
        dialog.setOnKeyListener { _, keyCode, event ->
            keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP && galleryView.handleBack()
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            ++searchGeneration
            searchJob?.cancel()
            galleryView.disposeImages()
            scope.cancel()
            returnFocus?.restore()
        }
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * .96).toInt(),
            (context.resources.displayMetrics.heightPixels * .96).toInt())
        galleryView.focusCandidates()
        // The previous picker searched immediately; keep that behavior instead of
        // presenting an empty gallery until the user presses 搜索.
        galleryView.post { if (dialog.isShowing) search() }
    }

    private fun artworkSource(context: Context, candidate: LibraryArtworkCandidate): String = when (candidate.source) {
        LibraryArtworkSource.TMDB -> context.getString(R.string.library_artwork_tmdb)
        LibraryArtworkSource.DOUBAN -> context.getString(R.string.library_artwork_douban)
        LibraryArtworkSource.ONLINE -> candidate.siteName?.takeIf(String::isNotBlank)?.let {
            context.getString(R.string.library_artwork_online_site, it)
        } ?: context.getString(R.string.library_artwork_online)
        LibraryArtworkSource.STREAMX -> context.getString(R.string.library_artwork_streamx)
        LibraryArtworkSource.TVMAZE -> context.getString(R.string.library_artwork_tvmaze)
        null -> ""
    }

    private fun artworkDescription(context: Context, candidate: LibraryArtworkCandidate): String = listOfNotNull(
        candidate.title, candidate.year?.toString(), when (candidate.kind) {
            MediaKind.MOVIE -> context.getString(R.string.library_artwork_movie)
            MediaKind.SERIES -> context.getString(R.string.library_artwork_series)
            else -> null
        }).joinToString(" · ")

    private fun previewArtwork(context: Context, candidate: LibraryArtworkCandidate,
        onDownload: (LibraryArtworkCandidate) -> Unit, onClosed: () -> Unit): Dialog {
        val (dialog, root, scope, focus) = shell(context, R.string.library_artwork_preview)
        root.addView(context.textView(artworkDescription(context, candidate), 18f, ViewTheme.textPrimary).apply { maxLines = 2 })
        root.addView(context.textView(artworkSource(context, candidate), 14f, ViewTheme.textSecondary))
        val images = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val portrait = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(ViewTheme.surface) }
        val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(ViewTheme.surface) }
        images.addView(portrait, LinearLayout.LayoutParams(context.vDp(125), ViewGroup.LayoutParams.MATCH_PARENT))
        images.addView(backdrop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = context.vDp(16) })
        root.addView(images, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.vDp(12); bottomMargin = context.vDp(12) })
        val capability = context.textView("", 14f, ViewTheme.textPrimary)
        val hint = context.textView("", 13f, ViewTheme.textSecondary).apply { maxLines = 3 }
        root.addView(capability); root.addView(hint)
        var state = artworkBackdropState(candidate.backdrop, null)
        var posterLoaded: Boolean? = null
        var submitted = false
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.vDp(46)))
        val confirm = button(context, R.string.library_artwork_download) {
            if (submitted || posterLoaded != true || state == ArtworkBackdropState.PENDING) return@button
            submitted = true
            onDownload(candidate.copy(backdrop = candidate.backdrop.takeIf { state == ArtworkBackdropState.LOADED }))
            dialog.dismiss()
        }
        fun render() {
            capability.setText(when (state) {
                ArtworkBackdropState.NONE -> R.string.library_artwork_portrait_only
                ArtworkBackdropState.PENDING -> R.string.library_artwork_backdrop_pending
                ArtworkBackdropState.LOADED -> R.string.library_artwork_backdrop_loaded
                ArtworkBackdropState.FAILED -> R.string.library_artwork_backdrop_failed
                ArtworkBackdropState.UNSUITABLE -> R.string.library_artwork_backdrop_unsuitable
            })
            hint.setText(when {
                posterLoaded == false -> R.string.library_artwork_poster_failed
                posterLoaded == null || state == ArtworkBackdropState.PENDING -> R.string.library_artwork_preview_loading
                state == ArtworkBackdropState.LOADED -> R.string.library_artwork_backdrop_hint
                else -> R.string.library_artwork_portrait_hint
            })
            // Keep a stable focus node while loading; confirmation is guarded above.
            confirm.alpha = if (posterLoaded == true && state != ArtworkBackdropState.PENDING) 1f else .45f
        }
        fun loadPreview() {
            portrait.dispose(); backdrop.dispose()
            posterLoaded = null; state = artworkBackdropState(candidate.backdrop, null)
            portrait.setImageDrawable(null); backdrop.setImageDrawable(null)
            backdrop.visibility = if (state == ArtworkBackdropState.NONE) View.GONE else View.VISIBLE
            render()
            portrait.load(candidate.poster) {
                size(400, 600); crossfade(false)
                listener(onSuccess = { _, _ -> if (scope.isActive) { posterLoaded = true; render() } },
                    onError = { _, _ -> if (scope.isActive) { posterLoaded = false; render() } })
            }
            if (!candidate.backdrop.isNullOrBlank()) backdrop.load(candidate.backdrop) {
                size(1280, 720); crossfade(false)
                listener(onSuccess = { _, result -> if (scope.isActive) {
                    state = artworkBackdropState(candidate.backdrop, true, result.drawable.intrinsicWidth, result.drawable.intrinsicHeight)
                    render()
                } }, onError = { _, _ -> if (scope.isActive) { state = ArtworkBackdropState.FAILED; render() } })
            }
        }
        actions.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        actions.addView(button(context, R.string.library_artwork_preview_retry) { loadPreview() })
        actions.addView(button(context, R.string.library_artwork_back) { dialog.dismiss() })
        dialog.setOnDismissListener { focus.close(); scope.cancel(); portrait.dispose(); backdrop.dispose(); onClosed() }
        show(context, dialog)
        focus.request { confirm.requestFocus() }
        loadPreview()
        return dialog
    }
}
