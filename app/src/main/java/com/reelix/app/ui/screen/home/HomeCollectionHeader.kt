package com.reelix.app.ui.screen.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.reelix.app.R
import com.reelix.app.ui.component.roundedBackground
import com.reelix.app.ui.component.showAnchoredChoicePopup
import com.reelix.app.ui.component.textView

internal class HomeCollectionHeader(context: Context,
    private val onFilter: (HomeCollectionFilter) -> Unit,
    private val onSort: (HomeCollectionSort) -> Unit,
    private val onFocused: (String) -> Unit,
    private val onExitUp: () -> Unit,
    private val onExitDown: () -> Unit,
) : FrameLayout(context) {
    private val heading = context.textView(context.getString(R.string.theme_collection_title), 21f, Color.WHITE).apply {
        setTypeface(typeface, Typeface.BOLD)
    }
    private val filters = HomeCollectionFilter.entries.associateWith { filter ->
        button(context.getString(when (filter) {
            HomeCollectionFilter.ALL -> R.string.theme_filter_all
            HomeCollectionFilter.MOVIE -> R.string.theme_filter_movie
            HomeCollectionFilter.SERIES -> R.string.theme_filter_series
            HomeCollectionFilter.RECENT -> R.string.theme_recent_added
        })) { onFilter(filter) }.apply { tag = "filter:${filter.name}" }
    }
    private val sorting = button("") {
        val values = HomeCollectionSort.entries
        showAnchoredChoicePopup(sortingAnchor(), values.map { context.getString(sortLabel(it)) }, values.indexOf(sort)) {
            onSort(values[it])
        }
    }.apply { tag = "sort" }
    private var filter = HomeCollectionFilter.ALL
    private var sort = HomeCollectionSort.ACTIVITY
    private var selectedControl: View? = null
    private var scale = 1f
    init {
        isFocusable = false
        addView(heading)
        filters.values.forEach(::addView)
        addView(sorting)
    }
    private fun sortingAnchor(): View = sorting
    private fun sortLabel(value: HomeCollectionSort): Int = when (value) {
        HomeCollectionSort.ACTIVITY -> R.string.theme_sort_activity
        HomeCollectionSort.ADDED -> R.string.theme_recent_added
        HomeCollectionSort.TITLE -> R.string.theme_sort_title
    }
    private fun button(label: String, action: () -> Unit) = context.textView(label, 11.5f, 0xFFC7C8CE.toInt()).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        gravity = android.view.Gravity.CENTER
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            if (focused) { selectedControl = this; onFocused(tag?.toString().orEmpty()) }
            style()
        }
        setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP) onExitUp() else onExitDown()
                }
                true
            } else false
        }
    }
    fun bind(filter: HomeCollectionFilter, sort: HomeCollectionSort) {
        this.filter = filter
        this.sort = sort
        sorting.text = context.getString(sortLabel(sort))
        style()
    }
    private fun style() {
        filters.forEach { (kind, view) ->
            val active = kind == filter
            view.setTextColor(if (active) 0xFF121212.toInt() else 0xFFC7C8CE.toInt())
            view.background = roundedBackground(if (active) 0xFFFFCA6A.toInt() else Color.TRANSPARENT,
                (6 * scale).toInt(), if (view.isFocused) (4 * scale).toInt().coerceAtLeast(1) else 0, 0xFFFFCA6A.toInt())
        }
        sorting.background = roundedBackground(Color.TRANSPARENT, (6 * scale).toInt(),
            if (sorting.isFocused) (4 * scale).toInt().coerceAtLeast(1) else 0, 0xFFFFCA6A.toInt())
    }
    fun focusSelected(): Boolean = (selectedControl ?: filters.getValue(filter)).requestFocus()
    fun restoreFocus(key: String?): Boolean {
        val target = if (key == "sort") sorting else filters.entries.firstOrNull { "filter:${it.key.name}" == key }?.value
        return (target ?: filters.getValue(filter)).requestFocus()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        scale = width / 1720f
        fun px(value: Int) = (value * scale).toInt().coerceAtLeast(1)
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42f * scale / resources.displayMetrics.density)
        heading.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(px(60), MeasureSpec.AT_MOST))
        (filters.values + sorting).forEach { view ->
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 23f * scale / resources.displayMetrics.density)
            view.setPadding(px(20), 0, px(20), 0)
            view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(px(49), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, px(132))
        style()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        heading.layout(0, 0, heading.measuredWidth, heading.measuredHeight)
        val rowTop = (82 * scale).toInt()
        var cursorLeft = 0
        filters.values.forEach { view ->
            view.layout(cursorLeft, rowTop, cursorLeft + view.measuredWidth, rowTop + view.measuredHeight)
            cursorLeft += view.measuredWidth + (15 * scale).toInt()
        }
        sorting.layout(width - sorting.measuredWidth, rowTop, width, rowTop + sorting.measuredHeight)
    }
}
