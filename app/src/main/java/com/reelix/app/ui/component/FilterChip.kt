package com.reelix.app.ui.component

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared pill chip used by both the online and library poster walls.
 *
 * Keeping one component guarantees identical size, radius, colors and key behavior,
 * so the two walls cannot drift apart again.
 */
class FilterChip(
    context: Context,
    label: String,
    private val onClick: () -> Unit,
    private val onDown: (() -> Unit)? = null,
    private val onUp: (() -> Unit)? = null,
) : LinearLayout(context) {

    private val text = context.textView(label, 13f, ViewTheme.textSecondary, Gravity.CENTER)
    private var selected = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setPadding(context.vDp(16), 0, context.vDp(16), 0)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, _ -> style() }
        setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDown?.invoke(); true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    onUp?.invoke(); true
                }
                else -> false
            }
        }
        style()
    }

    fun setChosen(value: Boolean) {
        selected = value
        style()
    }

    fun setLabel(value: String) {
        text.text = value
    }

    private fun style() {
        val focused = hasFocus()
        background = roundedBackground(
            color = if (selected) 0x24FFD466 else 0x0DFFFFFF,
            radiusPx = context.vDp(18),
            strokeWidthPx = if (focused) context.vDp(2) else 0,
            strokeColor = ViewTheme.focusRing,
        )
        text.setTextColor(
            when {
                focused -> Color.WHITE
                selected -> ViewTheme.accent
                else -> ViewTheme.textSecondary
            },
        )
    }
}
