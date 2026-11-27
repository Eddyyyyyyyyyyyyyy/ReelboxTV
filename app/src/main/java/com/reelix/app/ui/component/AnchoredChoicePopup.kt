package com.reelix.app.ui.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private data class AnchoredPopupOwner(val state: AnchoredChoiceState, val popup: WeakReference<PopupWindow>,
    var returnRequest: Runnable? = null)
private val anchoredPopupOwners = WeakHashMap<View, AnchoredPopupOwner>()

internal data class ChoicePopupLayout(val openAbove: Boolean, val heightPx: Int)

internal fun choicePopupLayout(
    belowPx: Int,
    abovePx: Int,
    itemCount: Int,
    rowHeightPx: Int,
    paddingPx: Int,
): ChoicePopupLayout {
    val desired = minOf(itemCount, 5) * rowHeightPx + paddingPx
    val openAbove = belowPx < desired && abovePx > belowPx
    val available = (if (openAbove) abovePx else belowPx).coerceAtLeast(1)
    val minimum = minOf(itemCount, 3) * rowHeightPx + paddingPx
    val height = desired.coerceAtMost(available).coerceAtLeast(minOf(minimum, available))
    return ChoicePopupLayout(openAbove, height)
}

/** A new modal in the host supersedes both the old popup and its queued return. */
internal fun cancelAnchoredChoicePopupFocus(host: View) {
    val root = host.rootView
    val owner = anchoredPopupOwners.remove(root) ?: return
    owner.returnRequest?.let { root.removeCallbacks(it) }
    owner.state.close()
    owner.popup.get()?.dismiss()
}

/** A focus-owning list anchored below a button; it never changes the page's layout. */
internal fun showAnchoredChoicePopup(anchor: View, labels: List<String>, selected: Int,
    onSelect: (Int) -> Unit): PopupWindow = showAnchoredChoicePopup(anchor, labels, selected,
    restoreFocus = {
        if (anchor.isAttachedToWindow && anchor.isShown && anchor.isEnabled && anchor.isFocusable) anchor.requestFocus()
    }, onSelect = onSelect)

internal fun showAnchoredChoicePopup(anchor: View, labels: List<String>, selected: Int,
    restoreFocus: () -> Unit,
    onSelect: (Int) -> Unit): PopupWindow {
    val context = anchor.context
    val restoreHost = anchor.rootView
    val visible = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
    val screen = IntArray(2).also { anchor.getLocationOnScreen(it) }
    val window = IntArray(2).also { anchor.getLocationInWindow(it) }
    val gap = context.vDp(6)
    val rowHeight = context.vDp(48)
    val edge = context.vDp(8)
    val below = (visible.bottom - screen[1] - anchor.height - gap - edge).coerceAtLeast(1)
    val above = (screen[1] - visible.top - gap - edge).coerceAtLeast(1)
    val placement = choicePopupLayout(below, above, labels.size, rowHeight, context.vDp(8))
    val openAbove = placement.openAbove
    val height = placement.heightPx
    val textPaint = Paint().apply { textSize = 13f * context.resources.displayMetrics.scaledDensity }
    val desiredWidth = (labels.maxOfOrNull { textPaint.measureText(it) }?.toInt() ?: 0) + context.vDp(64)
    val width = maxOf(anchor.width, desiredWidth).coerceAtMost(visible.width() - context.vDp(16)).coerceAtLeast(1)
    val selectedIndex = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
    val state = AnchoredChoiceState(labels.size, selectedIndex)
    lateinit var popup: PopupWindow
    val grid = VerticalGridView(context).apply {
        setNumColumns(1)
        setPadding(context.vDp(4), context.vDp(4), context.vDp(4), context.vDp(4))
        clipToPadding = true
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
    }
    fun invalidatePopupFocus() {
        state.close()
        grid.tag = null
        grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        grid.clearFocus()
    }
    fun applyChoice(index: Int) {
        if (!popup.isShowing) return
        state.applyChoice(index, onSelect = {
            invalidatePopupFocus()
            try {
                onSelect(it)
            } catch (error: Exception) {
                android.util.Log.e("ReelixChoicePopup", "Unable to apply choice", error)
                android.widget.Toast.makeText(context, com.reelix.app.R.string.route_match_choice_failed,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }, onDismiss = { popup.dismiss() })
    }
    fun focusChoice(index: Int) {
        val ticket = state.focusTicket()
        grid.tag = null
        grid.post {
            if (state.canFocus(ticket) && popup.isShowing && grid.isAttachedToWindow) {
                focusTileAt(grid, index, 1)
            }
        }
    }
    val root = object : FrameLayout(context) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) state.cancelKeys()
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (!state.isOpen || !popup.isShowing) return true
            if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
            val confirm = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
            val back = event.keyCode == KeyEvent.KEYCODE_BACK
            val owned = state.ownsKey(event.keyCode) && !event.isCanceled
            val action = KeyRouting.choicePopupKey(event.action == KeyEvent.ACTION_DOWN,
                up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP, down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT,
                back = back, confirm = confirm, confirmOwned = owned)
            if (confirm || back) {
                if (event.isCanceled) state.cancelKeys()
                else if (event.action == KeyEvent.ACTION_DOWN) state.press(event.keyCode, event.repeatCount)
                else state.release(event.keyCode)
            }
            when (action) {
                KeyRouting.ChoiceAction.UP, KeyRouting.ChoiceAction.DOWN -> {
                    state.move(if (action == KeyRouting.ChoiceAction.UP) -1 else 1)?.let(::focusChoice)
                }
                KeyRouting.ChoiceAction.APPLY -> applyChoice(state.position)
                KeyRouting.ChoiceAction.CANCEL -> if (owned) {
                    invalidatePopupFocus()
                    popup.dismiss()
                }
                KeyRouting.ChoiceAction.CONSUME -> Unit
                KeyRouting.ChoiceAction.IGNORE -> return super.dispatchKeyEvent(event)
            }
            return true
        }
    }
    root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    grid.adapter = object : RecyclerView.Adapter<ChoiceHolder>() {
        override fun getItemCount() = labels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChoiceHolder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setPadding(context.vDp(12), 0, context.vDp(12), 0)
                // Leanback must generate its own LayoutParams rather than RecyclerView's base class.
                minimumHeight = rowHeight
            }
            val label = context.textView("", 13f, ViewTheme.textPrimary).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val check = ChoiceCheckView(context)
            row.addView(check, LinearLayout.LayoutParams(context.vDp(20), context.vDp(20)).apply { leftMargin = context.vDp(12) })
            val holder = ChoiceHolder(row, label, check)
            row.setOnFocusChangeListener { _, focused ->
                if (focused && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) state.focused(holder.bindingAdapterPosition)
                row.background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(6),
                    if (focused) context.vDp(2) else 0, ViewTheme.focusRing)
            }
            row.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) applyChoice(index)
            }
            return holder
        }
        override fun onBindViewHolder(holder: ChoiceHolder, index: Int) {
            holder.label.text = labels[index]
            holder.check.visibility = if (index == selectedIndex) View.VISIBLE else View.INVISIBLE
            holder.itemView.background = roundedBackground(ViewTheme.surfaceElevated, context.vDp(6),
                if (holder.itemView.isFocused) context.vDp(2) else 0, ViewTheme.focusRing)
        }
    }
    popup = PopupWindow(root, width, height, true).apply {
        setBackgroundDrawable(roundedBackground(ViewTheme.surfaceElevated, context.vDp(6)))
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        elevation = context.vDp(8).toFloat()
        animationStyle = 0
        setOnDismissListener {
            invalidatePopupFocus()
            val owner = anchoredPopupOwners[restoreHost]
            if (owner?.state !== state) return@setOnDismissListener
            val request = Runnable {
                if (anchoredPopupOwners[restoreHost] === owner) {
                    anchoredPopupOwners.remove(restoreHost)
                    if (restoreHost.isAttachedToWindow) restoreFocus()
                }
            }
            owner.returnRequest = request
            if (restoreHost.isAttachedToWindow) restoreHost.post(request)
            else anchoredPopupOwners.remove(restoreHost)
        }
    }
    if (labels.isEmpty() || !anchor.isAttachedToWindow || !anchor.isShown || anchor.windowToken == null) return popup
    cancelAnchoredChoicePopupFocus(restoreHost)
    anchoredPopupOwners[restoreHost] = AnchoredPopupOwner(state, WeakReference(popup))
    val x = minOf(window[0], window[0] + visible.right - screen[0] - width - context.vDp(8))
    val y = if (openAbove) {
        (window[1] - height - gap).coerceAtLeast(edge)
    } else {
        window[1] + anchor.height + gap
    }
    popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    grid.setSelectedPosition(selectedIndex)
    focusChoice(selectedIndex)
    return popup
}

private class ChoiceHolder(row: View, val label: android.widget.TextView, val check: View) : RecyclerView.ViewHolder(row)

internal class AnchoredChoiceState(private val count: Int, selected: Int) {
    var position = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
        private set
    var isOpen = true
        private set
    private var revision = 0
    private val pressed = mutableSetOf<Int>()

    fun focusTicket(): Int = revision
    fun canFocus(ticket: Int): Boolean = isOpen && ticket == revision
    fun move(delta: Int): Int? {
        if (!isOpen || count == 0) return null
        revision++
        position = (position + delta).coerceIn(0, count - 1)
        return position
    }
    fun focused(index: Int) { if (isOpen && index in 0 until count) position = index }
    fun ownsKey(code: Int): Boolean = isOpen && code in pressed
    fun press(code: Int, repeat: Int) { if (isOpen && repeat == 0) pressed += code }
    fun release(code: Int) { pressed -= code }
    fun cancelKeys() { pressed.clear() }
    fun applyChoice(index: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
        if (!isOpen || index !in 0 until count) return
        close()
        try {
            onSelect(index)
        } finally {
            onDismiss()
        }
    }
    fun close() {
        isOpen = false
        revision++
        cancelKeys()
    }
}

private class ChoiceCheckView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ViewTheme.accent; style = Paint.Style.STROKE; strokeWidth = context.vDp(2).toFloat()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        val path = Path().apply { moveTo(width * .2f, height * .52f); lineTo(width * .42f, height * .74f); lineTo(width * .82f, height * .28f) }
        canvas.drawPath(path, paint)
    }
}
