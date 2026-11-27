package com.reelix.app.ui.screen.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.reelix.app.R
import com.reelix.app.ui.component.ViewTheme
import com.reelix.app.ui.component.focusStateBackground
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.textView
import com.reelix.app.ui.component.vDp

internal class NativeSettingRow(context: Context) : LinearLayout(context) {
    private val title = context.textView("", 15f, ViewTheme.textPrimary)
    private val subtitle = context.textView("", 12f, ViewTheme.textSecondary)
    private val value = context.textView("", 13f, ViewTheme.accent, Gravity.END or Gravity.CENTER_VERTICAL)
    private val actions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    init {
        id = View.generateViewId()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.vDp(56)
        setPadding(context.vDp(14), context.vDp(10), context.vDp(14), context.vDp(10))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        title.maxLines = 2
        subtitle.maxLines = 4
        value.maxLines = 1
        value.ellipsize = android.text.TextUtils.TruncateAt.END
        val words = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.vDp(4) })
        }
        addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value, LayoutParams(context.vDp(260), LayoutParams.WRAP_CONTENT).apply { leftMargin = context.vDp(12) })
        addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.vDp(10)
        })
        // ★ 2026-11-09：焦点态改成预建的两份 drawable，由 StateListDrawable 自动切换。
        //
        //   原来是焦点回调里 `style(focused)` → `setBackground()`，
        //   而 setBackground() 会 requestLayout() —— 设置页左侧 8 个分类来回切时
        //   每次都要重测整棵树，这是"不跟手"的主要来源之一。
        background = focusStateBackground(
            ViewTheme.surfaceElevated, ViewTheme.surfaceHigh,
            context.vDp(7), context.vDp(2), ViewTheme.focusRing,
        )
    }
    fun bind(label: String, state: String = "", help: String = "") {
        title.text = label
        value.text = state
        value.visibility = if (state.isBlank()) GONE else VISIBLE
        subtitle.text = help
        subtitle.visibility = if (help.isBlank()) GONE else VISIBLE
        contentDescription = listOf(label, state, help).filter { it.isNotBlank() }.joinToString("，")
    }
    fun style(focused: Boolean) {
        background = roundedBackground(
            if (focused) ViewTheme.surfaceHigh else ViewTheme.surfaceElevated,
            context.vDp(7), if (focused) context.vDp(2) else 0, ViewTheme.focusRing)
    }

    fun addAction(label: String, destructive: Boolean = false, onClick: () -> Unit): TextView {
        actions.visibility = VISIBLE
        val button = context.textView(
            text = label,
            sizeSp = 12f,
            color = if (destructive) 0xFFFF8A80.toInt() else ViewTheme.textPrimary,
            gravity = Gravity.CENTER,
        ).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(context.vDp(10), 0, context.vDp(10), 0)
            background = focusStateBackground(
                ViewTheme.surfaceHigh, ViewTheme.surfaceHigh, context.vDp(5),
                context.vDp(2), ViewTheme.focusRing,
            )
            setOnClickListener { onClick() }
        }
        actions.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, context.vDp(34)).apply {
            leftMargin = context.vDp(6)
        })
        return button
    }
}

internal fun settingsDialog(context: Context, title: String): Pair<Dialog, LinearLayout> {
    val dialog = Dialog(context)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.vDp(24), context.vDp(20), context.vDp(24), context.vDp(20))
        background = roundedBackground(ViewTheme.surface, context.vDp(12))
        addView(context.textView(title, 21f, ViewTheme.textPrimary),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.vDp(16)
            })
    }
    dialog.setContentView(content)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes.apply { dimAmount = .72f }
    }
    return dialog to content
}

internal fun Dialog.sizeForSettings(context: Context, widthDp: Int = 640) {
    window?.setLayout(minOf(context.vDp(widthDp), context.resources.displayMetrics.widthPixels - context.vDp(48)),
        WindowManager.LayoutParams.WRAP_CONTENT)
}

internal fun View.isImeVisible(): Boolean {
    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this) ?: return false
    return insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
}

internal class NativeSettingsEditor(context: Context, private val finish: () -> Unit) : EditText(context) {
    /** True while this field owns the keyboard; BACK then closes the IME instead of the dialog. */
    var imeBackGuard: (() -> Boolean)? = null
    /** BACK after the keyboard is closed must cancel, never commit. */
    var onBack: (() -> Unit)? = null
    private var imeHiddenByBackAt = 0L
    init {
        id = View.generateViewId()
        setTextColor(ViewTheme.textPrimary)
        setHintTextColor(ViewTheme.textTertiary)
        textSize = 16f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        setPadding(context.vDp(12), context.vDp(12), context.vDp(12), context.vDp(12))
        background = roundedBackground(ViewTheme.surfaceHigh, context.vDp(6), context.vDp(2), ViewTheme.focusRing)
        setOnEditorActionListener { _, _, _ -> finish(); true }
    }
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val now = SystemClock.uptimeMillis()
                if (now - imeHiddenByBackAt <= 900L) {
                    imeHiddenByBackAt = 0L
                    requestBackExit()
                } else {
                    imeHiddenByBackAt = now
                    hideKeyboard()
                }
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.repeatCount == 0) requestBackExit()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun requestBackExit() {
        val cancel = onBack
        if (cancel != null) cancel() else if (imeBackGuard?.invoke() != true) finish()
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }
}
