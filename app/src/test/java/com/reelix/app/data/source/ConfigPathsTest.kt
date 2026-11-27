package com.reelix.app.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置里的相对路径补全。
 *
 * ## 为什么要单独测这个
 *
 * 2026-11-02 发现它**从来没生效过**：
 * ```kotlin
 * ConfigPaths.normalize(root, base)   // 返回 Unit，新树被丢弃
 * return root.toString()              // 返回的还是原树
 * ```
 * 后果是配置里写 `"./csp.jar"` 的地方永远保持相对路径，
 * jar 下载必然 404，**所有站点全空**。
 *
 * 而这个 bug 之所以能活这么久，就是因为 ConfigPaths 没有任何单测。
 * 下面的用例守住这条线：normalize 必须**返回**改过的树。
 */
class ConfigPathsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    /** normalize 接收并返回 JsonElement，这里统一转成 JsonObject 方便断言 */
    private fun norm(o: JsonObject, base: String): JsonObject =
        ConfigPaths.normalize(o, base).jsonObject

    private fun pathOf(o: JsonObject, key: String): String =
        o[key]!!.jsonPrimitive.content

    private fun nestedPath(o: JsonObject, arrKey: String, idx: Int, field: String): String =
        (o[arrKey] as kotlinx.serialization.json.JsonArray)[idx]
            .jsonObject[field]!!.jsonPrimitive.content

    // ---------- 基本补全 ----------

    @Test
    fun `spider 的相对路径会被补成绝对地址`() {
        val root = obj("""{"spider":"./csp.jar;md5;abc","sites":[]}""")
        val fixed = norm(root, "https://cdn.example.com/tvbox/config.json")

        // ★ 关键断言：normalize 必须返回新树，而不是原地修改后返回 Unit
        assertNotEquals("normalize 必须返回新树", root, fixed)
        assertEquals(
            "https://cdn.example.com/tvbox/csp.jar;md5;abc",
            pathOf(fixed, "spider")
        )
    }

    @Test
    fun `sites 里 api 的根路径会被补全`() {
        val root = obj("""{"sites":[{"key":"a","api":"/api/v1/vod"}]}""")
        val fixed = norm(root, "https://site.example.com/tv/cfg.json")

        assertEquals(
            "https://site.example.com/api/v1/vod",
            nestedPath(fixed, "sites", 0, "api")
        )
    }

    @Test
    fun `ext 的相对路径会被补全`() {
        val root = obj("""{"sites":[{"key":"a","api":"csp_X","ext":"./ext/site.json"}]}""")
        val fixed = norm(root, "https://site.example.com/tv/cfg.json")

        assertEquals(
            "https://site.example.com/tv/ext/site.json",
            nestedPath(fixed, "sites", 0, "ext")
        )
    }

    // ---------- 不该动的 ----------

    @Test
    fun `绝对地址保持不变`() {
        val root = obj("""{"spider":"https://x.com/csp.jar;md5;abc","sites":[]}""")
        val fixed = norm(root, "https://cdn.example.com/tvbox/config.json")

        assertEquals("https://x.com/csp.jar;md5;abc", pathOf(fixed, "spider"))
    }

    @Test
    fun `自定义协议头保持不变`() {
        for (scheme in listOf("clan://", "proxy://", "assets://", "file://")) {
            val root = obj("""{"spider":"${scheme}local.jar","sites":[]}""")
            val fixed = norm(root, "https://cdn.example.com/cfg.json")
            assertEquals("$scheme 不该被改写", "${scheme}local.jar", pathOf(fixed, "spider"))
        }
    }

    @Test
    fun `nodejs 前缀会被保留`() {
        val root = obj("""{"spider":"nodejs:./js/drpy.js","sites":[]}""")
        val fixed = norm(root, "https://cdn.example.com/tvbox/cfg.json")

        val v = pathOf(fixed, "spider")
        assertTrue("应保留 nodejs: 前缀，实际: $v", v.startsWith("nodejs:"))
    }

    @Test
    fun `header 里的值不是路径 不该被改写`() {
        val root = obj(
            """{"sites":[{"key":"a","api":"http://x.com/api","header":{"Referer":"/not/a/path"}}]}"""
        )
        val fixed = norm(root, "https://cdn.example.com/cfg.json")

        val header = (fixed["sites"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["header"]!!.jsonObject
        assertEquals("/not/a/path", header["Referer"]!!.jsonPrimitive.content)
    }

    // ---------- 边界 ----------

    @Test
    fun `空 base 时原样返回`() {
        val root = obj("""{"spider":"./csp.jar"}""")
        val fixed = norm(root, "")
        assertEquals(root, fixed)
    }

    @Test
    fun `空对象不炸`() {
        val fixed = norm(obj("{}"), "https://x.com/cfg.json")
        assertTrue(fixed.isEmpty())
    }

    /**
     * ext 内部的键名是**不固定**的（Cloud-drive / siteUrl / json / type / name…），
     * 所以不能盲目补全 —— 否则会把 `"type":"video"` 这种值也当成路径改写。
     *
     * 这里断言的是：ext 里**不在词表内**的随意字段保持原样，
     * 而词表内的字段（url / ext / json 之类）才补全。
     * 这是有意为之的保守策略，不是 bug。
     */
    @Test
    fun `ext 里未知字段保持原样`() {
        val root = obj(
            """{"sites":[{"key":"a","ext":{"type":"video","name":"Fy"}}]}"""
        )
        val fixed = norm(root, "https://cdn.example.com/tv/cfg.json")

        val ext = (fixed["sites"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["ext"]!!.jsonObject

        assertEquals("video", ext["type"]!!.jsonPrimitive.content)
        assertEquals("Fy", ext["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ext 里已知路径字段会被补全`() {
        // 真实配置里确实有 "ext":{"url":"./x.json"} 这种写法
        val root = obj(
            """{"sites":[{"key":"a","ext":{"url":"./ext/x.json"}}]}"""
        )
        val fixed = norm(root, "https://cdn.example.com/tv/cfg.json")

        val url = (fixed["sites"] as kotlinx.serialization.json.JsonArray)[0]
            .jsonObject["ext"]!!.jsonObject["url"]!!.jsonPrimitive.content

        assertEquals("https://cdn.example.com/tv/ext/x.json", url)
    }

    // ---------- Json 注释清洗（同文件族） ----------

    @Test
    fun `双斜杠注释行会被删掉`() {
        val raw = """
            {
              "sites": [
                {"key":"a","type":3},
                //{"key":"被屏蔽的","type":3},
                {"key":"b","type":3}
              ]
            }
        """.trimIndent()

        val cleaned = SubscriptionResolver.stripJsonComments(raw)
        assertTrue("注释行应消失", !cleaned.contains("被屏蔽的"))
        assertTrue("正常行应保留", cleaned.contains("\"key\":\"a\""))
        assertTrue("正常行应保留", cleaned.contains("\"key\":\"b\""))
    }

    @Test
    fun `URL 里的双斜杠不会被误删`() {
        val raw = """{"spider":"https://cdn.x.com/csp.jar","sites":[]}"""
        val cleaned = SubscriptionResolver.stripJsonComments(raw)
        assertTrue("http:// 必须保留", cleaned.contains("https://cdn.x.com/csp.jar"))
    }
}
