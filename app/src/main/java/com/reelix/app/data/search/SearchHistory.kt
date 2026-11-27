package com.reelix.app.data.search

import android.content.Context
import android.content.SharedPreferences

/**
 * 搜索历史 —— 纯本地，最多 20 条。
 *
 * ## 为什么需要
 *
 * 电视上打字成本高，搜过的词第二次还要重打一遍是折磨。
 * 搜索页现在的分工：
 * ```
 * 没输入       →  中栏显示 360kan 热门搜索
 * 输入中       →  中栏显示爱奇艺联想词
 * 右侧栏       →  始终显示本地搜索历史
 * ```
 *
 * ## 存储
 *
 * SharedPreferences，键 `search_history`，JSON 数组。
 * 不用 Room：数据量小、无关联查询、只需要「读全部 / 加一条 / 清空」。
 */
object SearchHistory {

    private const val PREF_NAME = "reelix_search"
    private const val KEY_HISTORY = "search_history"
    private const val SEPARATOR = "\u0001"

    private const val MAX_SIZE = 20

    private var prefs: SharedPreferences? = null

    /**
     * 由 [com.reelix.app.ReelboxApp.onCreate] 调一次。
     *
     * ★ 不要放在 Compose 的 composable 主体里（2026-11-06 修正）：
     *   之前写在 `ReelboxRoot()` 函数体第一行且没包 `remember`，等于每次重组
     *   都会走一遍。虽然内部有 null 判空所以不贵，但这属于"启动路径上的无谓调用"，
     *   而且语义上它就是 Application 级初始化，放错层了。
     *
     * 注：`getSharedPreferences()` 本身只构造对象，真正的 XML 读取发生在
     *   第一次 `getString`/`getAll`。所以这里几乎零成本，不需要挪到后台线程。
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    /** 读全部历史（最近的在前） */
    fun list(): List<String> {
        val raw = prefs?.getString(KEY_HISTORY, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /**
     * 加一条。
     *
     * 去重逻辑：同一个词再次搜索时**移到最前面**，而不是重复添加。
     * 这样「最常用的」自然浮到顶部。
     */
    fun add(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return

        val current = list().toMutableList()
        current.removeAll { it.equals(k, ignoreCase = true) }
        current.add(0, k)

        val trimmed = current.take(MAX_SIZE)
        prefs?.edit()
            ?.putString(KEY_HISTORY, trimmed.joinToString(SEPARATOR))
            ?.apply()
    }

    /** 删一条 */
    fun remove(keyword: String) {
        val current = list().toMutableList()
        current.removeAll { it == keyword }
        prefs?.edit()
            ?.putString(KEY_HISTORY, current.joinToString(SEPARATOR))
            ?.apply()
    }

    /** 清空全部 */
    fun clear() {
        prefs?.edit()?.remove(KEY_HISTORY)?.apply()
    }
}
