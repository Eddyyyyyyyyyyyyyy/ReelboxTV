package com.reelix.app.data.source.js

import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * PDFH 选择器引擎（TVBox 风格），M1 实现。
 *
 * 规则语法（与 TVBox 的 `pdfh/pdfa` 一致）：
 * ```
 * pdfh(html, "div.item&&a--href")
 * pdfa(html, "ul.list&&li--Text")
 * pd(html,  "div.x--Html")
 * ```
 * · `&&` 串选择器链（每一步缩小范围）
 * · `--属性` 取值：`Text`（文本）、`Html`（内部 HTML）、或任意属性名（href/src/…）
 * · `||` 并联选择器（任一命中）
 * · 支持 `:eq(n)` / `:lt(n)` / `:gt(n)` / `:first` / `:last`（jsoup 原生支持）
 *
 * ★ M1 先做"够用"版：正确跑通 `pdfh/pdfa/pd` 主路径，特殊伪类与 `||` 后续按真实源补。
 */
object Pdfh {

    /** 返回 JSON 字符串：单值（标量或数组）。没命中返回空串。 */
    fun pdfh(html: String, rule: String): String {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return JSONArray().toString()

        val list = apply(doc, rule)

        if (list.isEmpty()) return "\"\""

        return jsonValue(list.first())
    }

    /** 返回 JSON 数组字符串。 */
    fun pdfa(html: String, rule: String): String {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return JSONArray().toString()

        val list = apply(doc, rule)

        val arr = JSONArray()
        list.forEach { arr.put(unescapeJsonValue(it)) }

        return arr.toString()
    }

    /* ---------------- 内部 ---------------- */

    private fun apply(doc: Document, rule: String): List<String> {
        val steps = rule.split("&&").map { it.trim() }.filter { it.isNotEmpty() }

        if (steps.isEmpty()) return emptyList()

        // 初始范围：整个文档
        var current: List<Element> = listOf(doc)

        for (step in steps) {
            val (selector, attr) = splitAttr(step)

            // 选择器可能是 `a||b`（并联）
            val alts = selector.split("||").map { it.trim() }.filter { it.isNotEmpty() }

            val next = mutableListOf<Element>()

            for (el in current) {
                for (sel in alts) {
                    runCatching { next += el.select(sel) }
                }
            }

            if (attr != null) {
                return next.map { attrValue(it, attr) }
            }

            current = next
        }

        // 没有取属性 → 返回当前节点的文本
        return current.map { it.text() }
    }

    /** 拆 `div.item--href` → ("div.item", "href")；没有 `--` → (sel, null)。 */
    private fun splitAttr(step: String): Pair<String, String?> {
        val idx = step.indexOf("--")

        if (idx < 0) return step to null

        return step.substring(0, idx).trim() to step.substring(idx + 2).trim()
    }

    private fun attrValue(el: Element, attr: String): String = when (attr) {
        "Text", "text" -> el.text()
        "Html", "html" -> el.html()
        "Href", "href" -> el.attr("href")
        "Src", "src" -> el.attr("src")
        else -> el.attr(attr)
    }

    private fun jsonValue(v: String): String = org.json.JSONObject.quote(v)

    /** jsoup 会反转义部分实体，JSON 侧直接用 `quote` 就够了；这里留作占位。 */
    private fun unescapeJsonValue(v: String): String = v
}
