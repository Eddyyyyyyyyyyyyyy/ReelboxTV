package com.reelix.app.ui.screen.library

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.reelix.app.R
import com.reelix.app.data.alist.*
import com.reelix.app.ui.component.*

class NativeDirectoryManagerView(
    context: Context,
    root: String,
    private val onPreview: (String, DirectoryMode?, View) -> Unit,
) : LinearLayout(context) {
    private val position = DirectoryBrowserPosition(root)
    private val pathLabel = context.textView("", 15f, ViewTheme.textSecondary)
    private val targetLabel = context.textView("", 17f, ViewTheme.textPrimary)
    private val summary = context.textView("", 13f, ViewTheme.textSecondary)
    private val list = ListView(context).apply {
        dividerHeight = context.vDp(3)
        isFocusable = true
        isFocusableInTouchMode = true
        selector = context.getDrawable(R.drawable.library_list_focus)
    }
    private val rules = LinearLayout(context).apply { orientation = VERTICAL }
    private val modes = listOf(DirectoryMode.WORK, DirectoryMode.CATEGORY, null)
    private val buttons = mutableListOf<TextView>()
    private var rows = emptyList<DirectoryBrowserRow>()
    private var scan: LibraryScan? = null
    private var savedRules = emptyMap<String, DirectoryMode>()
    private var renderGeneration = 0L
    private val selectionOffsets = mutableMapOf<String, Int>()

    init {
        orientation = VERTICAL
        isFocusable = false
        addView(pathLabel, LayoutParams(-1, context.vDp(42)))
        val columns = LinearLayout(context)
        addView(columns, LayoutParams(-1, 0, 1f))
        columns.addView(list, LayoutParams(0, -1, .65f).apply { rightMargin = context.vDp(20) })
        columns.addView(rules, LayoutParams(0, -1, .35f))
        targetLabel.maxLines = 3
        targetLabel.ellipsize = TextUtils.TruncateAt.MIDDLE
        rules.addView(targetLabel)
        rules.addView(summary, LayoutParams(-1, -2).apply { topMargin = context.vDp(8); bottomMargin = context.vDp(12) })
        modes.forEach { mode ->
            val title = context.getString(when (mode) {
                DirectoryMode.WORK -> R.string.library_directory_work_title
                DirectoryMode.CATEGORY -> R.string.library_directory_category_title
                null -> R.string.library_directory_auto_title
            })
            val button = context.textView(title, 15f, ViewTheme.textPrimary, Gravity.CENTER_VERTICAL).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding(context.vDp(12), 0, context.vDp(12), 0)
                setOnClickListener { onPreview(position.selected, mode, this) }
                setOnFocusChangeListener { _, _ -> styleModes() }
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (event.action == KeyEvent.ACTION_DOWN) focusList()
                        true
                    } else false
                }
            }
            buttons += button
            rules.addView(button, LayoutParams(-1, context.vDp(47)).apply { bottomMargin = context.vDp(6) })
        }
        rules.addView(context.textView(context.getString(R.string.library_directory_category_desc) +
            "\n选择识别方式后先预览，确认才应用。不会移动或删除网盘文件。", 12f, ViewTheme.textSecondary))
        addView(context.textView("上下：查看目录　OK：进入　右：修改规则　左：返回列表　返回：上级／退出",
            12f, ViewTheme.textTertiary), LayoutParams(-1, context.vDp(28)))
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long) {
                rows.getOrNull(index)?.let { position.selected = it.path; describe(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        list.setOnItemClickListener { _, _, index, _ ->
            rows.getOrNull(index)?.let {
                position.selected = it.path
                if (it.current) buttons.first().requestFocus() else {
                    remember()
                    if (position.enter(it.path)) refresh(scan, savedRules)
                }
            }
        }
        list.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) buttons.first().requestFocus()
                true
            } else false
        }
    }

    private fun remember() {
        position.remember(list.firstVisiblePosition.coerceAtLeast(0), list.getChildAt(0)?.top ?: 0)
        selectionOffsets[position.current] = list.getChildAt(list.selectedItemPosition - list.firstVisiblePosition)?.top ?: 0
    }

    fun refresh(scan: LibraryScan?, rules: Map<String, DirectoryMode>) {
        this.scan = scan
        savedRules = rules
        val ticket = ++renderGeneration
        rows = directoryBrowserRows(scan, position.root, position.current, rules)
        pathLabel.text = "当前位置：${position.current}"
        val selected = rows.indexOfFirst { it.path == position.selected }.coerceAtLeast(0)
        position.selected = rows[selected].path
        list.adapter = object : ArrayAdapter<DirectoryBrowserRow>(context, 0, rows) {
            override fun getView(index: Int, recycled: View?, parent: ViewGroup): View {
                val row = rows[index]
                return LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = context.vDp(56)
                    setPadding(context.vDp(12), context.vDp(5), context.vDp(12), context.vDp(5))
                    val title = if (row.current) "本目录" else row.path.substringAfterLast('/')
                    val name = context.textView(title, 16f, ViewTheme.textPrimary).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    addView(name)
                    val mode = if (row.manual == null) "自动" else "手动"
                    addView(context.textView("$mode · ${modeName(row.mode)} · 缓存 ${row.cachedVideos} 视频 / ${row.cachedChildren} 子目录",
                        12f, ViewTheme.textSecondary))
                    contentDescription = "$title，${row.path}"
                    layoutParams = AbsListView.LayoutParams(-1, -2)
                }
            }
        }
        val (first, offset) = position.viewport()
        list.setSelectionFromTop(first, offset)
        list.post {
            if (ticket == renderGeneration && isAttachedToWindow) {
                list.setSelectionFromTop(selected, selectionOffsets[position.current] ?: context.vDp(12))
            }
        }
        describe(rows[selected])
    }

    private fun modeName(mode: DirectoryMode) = context.getString(
        if (mode == DirectoryMode.WORK) R.string.library_mode_work else R.string.library_mode_category)

    private fun describe(row: DirectoryBrowserRow) {
        targetLabel.text = "规则目标\n${row.path}"
        summary.text = listOfNotNull(
            if (row.mount) "网盘根目录" else null,
            when {
                row.failure != null -> "扫描失败：${row.failure}"
                !row.scanned -> "尚未扫描，远端内容未知"
                row.cachedVideos == 0 -> "缓存暂无视频，不代表远端为空"
                else -> "缓存 ${row.cachedVideos} 个视频 · ${row.cachedChildren} 个子目录"
            },
            "${if (row.manual == null) "自动判断" else "手动设置"}：${modeName(row.mode)}",
        ).joinToString("\n")
        styleModes()
    }

    private fun styleModes() {
        val selected = rows.firstOrNull { it.path == position.selected }?.manual
        buttons.forEachIndexed { index, button ->
            button.background = roundedBackground(if (modes[index] == selected) 0x24FFD466 else ViewTheme.surfaceElevated,
                context.vDp(6), if (button.hasFocus()) context.vDp(2) else 0, ViewTheme.focusRing)
        }
    }

    fun focusList(): Boolean = list.requestFocus()
    fun handleBack(): Boolean {
        if (rules.hasFocus()) { focusList(); return true }
        remember()
        if (!position.parent()) return false
        refresh(scan, savedRules)
        focusList()
        return true
    }
}
