package com.reelix.app.data.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 配置里的相对路径归一化 —— 移植自 TVBox 分支的 `ConfigPaths`。
 *
 * ## 为什么必须有这一层
 *
 * TVBox 配置里经常写相对路径，因为站长要能随时换域名：
 * ```json
 * {
 *   "spider": "./csp.jar;md5;abc123",
 *   "sites": [{ "api": "/api/v1/example", "ext": "./ext/site.json" }],
 *   "wallpaper": "./wall.jpg"
 * }
 * ```
 * 如果原样交给后面的加载器：
 * ```
 * · "./csp.jar"      → jar 下载 404 → 所有 type=3 站点全废（占配置 90%）
 * · "/api/v1/example" → 请求 404 → 该站点永远空列表
 * ```
 * 表现出来是「配置加载成功，但站点全空」，用户完全看不出原因。
 *
 * ## 处理哪些字段
 *
 * 和 TVBox 保持一致：`spider` / `jar` / `api` / `ext` / `url` /
 * `sourceUrl` / `playUrl` / `wallpaper` / `epg` / `logo`，
 * 另外 `urls` 和 `wallpaper` 作为数组时逐项处理。
 *
 * ## 为什么不能简单用 `URI.resolve`
 *
 * 配置里到处是中文域名、`{}` 模板、`;md5;` 后缀：
 * ```
 * https://动态壁纸.饭.eu.org/
 * https://x.com/spider_{key}.jar;md5;abc123
 * ```
 * `java.net.URI` 对这些直接抛异常。所以先用 OkHttp 的 HttpUrl（它能容忍
 * 中文域名，会自动 percent-encode），失败再退回纯字符串拼接。
 */
object ConfigPaths {

    /** 需要做相对路径补全的字段名（和 TVBox 一致） */
    private val PATHS = setOf(
        "spider", "jar", "api", "ext", "url",
        "sourceUrl", "playUrl", "wallpaper", "epg", "logo",
    )

    /**
     * 把一个可能是相对路径的值解析成绝对地址。
     *
     * @param base 配置本身的最终 URL（**必须是重定向之后的地址**）
     * @param path 配置里写的值
     * @return 绝对地址；如果本来就不是相对路径则原样返回
     */
    fun resolve(base: String, path: String): String {
        if (base.isBlank() || path.isBlank()) return path

        // nodejs:/node: 前缀要保留，不能参与解析
        val prefix = when {
            path.startsWith("nodejs:") -> "nodejs:"
            path.startsWith("node:") -> "node:"
            else -> ""
        }
        val value = path.removePrefix(prefix)

        // 只处理相对路径。绝对 URL、协议头（clan:// / proxy:// / assets://）保持不动
        if (!(value.startsWith("./") || value.startsWith("../") || value.startsWith("/"))) {
            return path
        }

        // `xxx.jar;md5;hash` —— md5 部分可能是另一个相对路径，要单独解析
        val parts = value.split(";md5;", limit = 2)
        val resource = parts[0].replace("{", "%7B").replace("}", "%7D")

        val resolved = runCatching {
            base.toHttpUrlOrNull()?.resolve(resource)?.toString()
        }.getOrNull() ?: runCatching {
            // HttpUrl 解析不了时（极少数怪 base），退回字符串拼接
            java.net.URI(base).resolve(resource).toString()
        }.getOrNull() ?: return path

        val restored = resolved.replace("%7B", "{").replace("%7D", "}")
        return buildString {
            append(prefix)
            append(restored)
            if (parts.size > 1) {
                append(";md5;")
                append(resolve(base, parts[1]))
            }
        }
    }

    /**
     * 递归遍历整个配置树，把 PATHS 里的相对路径替换成绝对地址。
     *
     * 注意**跳过 `header`** —— 它的值是请求头，里面出现 `/` 不是路径。
     */
    /** @return 补全后的新树（kotlinx 的 JsonElement 不可变，必须用返回值） */
    fun normalize(root: JsonElement, base: String): JsonElement {
        if (base.isBlank()) return root
        return walk(root, base)
    }

    /**
     * 递归补全。
     *
     * ★★ 2026-11-02 修：kotlinx.serialization 的 JsonObject / JsonArray
     *   **是不可变的**。原来用 `(this as? MutableMap)?.put(...)` 强转，
     *   转换必然失败、又被 runCatching 静默吞掉 ——
     *   结果「路径补全」这个功能**从来没有生效过**。
     *   实测现象：配置里 spider 写 `./csp.jar`，加载后仍是 `./csp.jar`，
     *   导致 jar 下载 404、所有站点全空。
     *
     *   现在改成**重建对象树**：walk 返回新的 JsonElement，逐层替换上去。
     */
    private fun walk(element: JsonElement, base: String): JsonElement {
        return when (element) {
            is JsonArray -> JsonArray(element.map { walk(it, base) })
            is JsonObject -> {
                val out = LinkedHashMap<String, JsonElement>(element.size)

                for ((key, value) in element) {
                    when {
                        // urls / wallpaper 是字符串数组，逐项补全
                        (key == "urls" || key == "wallpaper") && value is JsonArray -> {
                            out[key] = JsonArray(
                                value.map { item ->
                                    if (isString(item)) {
                                        JsonPrimitive(resolve(base, (item as JsonPrimitive).content.trim()))
                                    } else {
                                        walk(item, base)
                                    }
                                }
                            )
                        }

                        // 已知路径字段：补全
                        isString(value) && key in PATHS -> {
                            out[key] = JsonPrimitive(
                                resolve(base, (value as JsonPrimitive).content.trim())
                            )
                        }

                        // header 里的值不是路径，跳过
                        key == "header" -> out[key] = value

                        else -> out[key] = walk(value, base)
                    }
                }

                JsonObject(out)
            }
            else -> element
        }
    }

    private fun isString(e: JsonElement?): Boolean =
        e is JsonPrimitive && e.isString
}
