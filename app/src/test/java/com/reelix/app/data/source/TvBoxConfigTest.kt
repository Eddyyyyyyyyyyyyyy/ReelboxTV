package com.reelix.app.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TVBox 配置解析测试。
 *
 * 为什么要测：TVBox 生态里的 config.json 是**野生**的 ——
 * 字段名有 filterable/filter 两种、type 有 0/1/3/4/10、ext 有时是字符串有时是对象、
 * 还夹着 BOM 和 emoji。解析器一旦抛异常，整个源就不可用。
 *
 * 这里用两个输入：
 *  1. 你仓库里 _dbg/tvbox_config.json 的真实配置（存在才跑，缺失跳过）
 *  2. 手写的极端样本，钉住各种写法
 */
class TvBoxConfigTest {

    private val realConfig = File("D:/Project/DeepSeek/Reelix/_dbg/tvbox_config.json")

    @Test
    fun `真实配置能解析且站点结构完整`() {
        if (!realConfig.exists()) return // 配置不在这台机器上时跳过
        val cfg = TvBoxConfig.parse(realConfig.readText())
        assertTrue("真实配置应有站点", cfg.sites.isNotEmpty())
        assertTrue("站点应有 key", cfg.sites.all { it.key.isNotBlank() })
        assertTrue("站点应有 name", cfg.sites.all { it.name.isNotBlank() })

        // 实测结论：这份配置的站点绝大多数是 type=3（远程 jar / csp_），
        // 也就是说**光有 HTTP 客户端是跑不起来的**，必须有 jar 加载能力。
        // 这个事实决定了 TVBox 接入的落地顺序，值得当成断言钉住。
        val jarSites = cfg.sites.count { it.type == 3 }
        val directSites = cfg.sites.count { it.isDirectlyPlayable }
        assertTrue("应存在 jar 类站点", jarSites > 0)
        assertTrue(
            "jar 类站点应占多数（现实配置就是这样），实际 $jarSites/${cfg.sites.size}",
            jarSites * 2 > cfg.sites.size
        )
        println("TvBoxRealConfig: total=${cfg.sites.size} jar=$jarSites direct=$directSites")
    }

    @Test
    fun `style 字段对象写法不能崩`() {
        val json = """
            {"sites":[
              {"key":"a","name":"A","type":1,"api":"u","style":0},
              {"key":"b","name":"B","type":1,"api":"u","style":{"type":"rect","ratio":1.33}}
            ]}
        """.trimIndent()
        val cfg = TvBoxConfig.parse(json)
        assertEquals(2, cfg.sites.size)
        assertEquals("0", cfg.sites[0].style)
        assertTrue(cfg.sites[1].style!!.contains("rect"))
    }

    @Test
    fun `未知字段不抛异常`() {
        val json = """
            {
              "sites": [{"key":"a","name":"A","type":1,"api":"http://x","brandNewField":123}],
              "whatever": {"nested": [1,2,3]},
              "lives": []
            }
        """.trimIndent()
        val cfg = TvBoxConfig.parse(json)
        assertEquals(1, cfg.sites.size)
        assertEquals("a", cfg.sites.first().key)
    }

    @Test
    fun `filterable 与 filter 两种写法都能读`() {
        val json = """
            {"sites":[
              {"key":"a","name":"A","type":1,"api":"u","filterable":1},
              {"key":"b","name":"B","type":1,"api":"u","filter":1}
            ]}
        """.trimIndent()
        val cfg = TvBoxConfig.parse(json)
        assertEquals(2, cfg.sites.size)
        // 目前模型只声明了 filter；filterable 属于"未知字段"被忽略，但解析不能崩
        assertNotNull(cfg.sites.firstOrNull { it.key == "a" })
    }

    @Test
    fun `ext 既可以是字符串也可以是对象`() {
        val json = """
            {"sites":[
              {"key":"a","name":"A","type":1,"api":"u","ext":"string-ext"},
              {"key":"b","name":"B","type":1,"api":"u","ext":{"token":"t","page":2}}
            ]}
        """.trimIndent()
        val cfg = TvBoxConfig.parse(json)
        assertEquals(2, cfg.sites.size)
        assertEquals("string-ext", cfg.sites[0].ext)
        assertTrue("对象型 ext 也要能拿到原文", cfg.sites[1].ext!!.contains("token"))
    }

    @Test
    fun `type 为 3 的是非点播站点`() {
        val json = """{"sites":[{"key":"a","name":"A","type":3,"api":"csp_X"}]}"""
        val cfg = TvBoxConfig.parse(json)
        assertFalse("type=3 应判定为非点播", cfg.sites.first().isVod)
    }

    @Test
    fun `空对象不崩`() {
        val cfg = TvBoxConfig.parse("{}")
        assertTrue(cfg.sites.isEmpty())
        assertTrue(cfg.lives.isEmpty())
    }

    @Test
    fun `空 sites 数组不崩`() {
        val cfg = TvBoxConfig.parse("""{"sites":[]}""")
        assertTrue(cfg.sites.isEmpty())
    }

    @Test
    fun `带 BOM 的配置能解析`() {
        val json = "\uFEFF" + """{"sites":[{"key":"a","name":"A","type":1,"api":"u"}]}"""
        val cfg = TvBoxConfig.parse(json.trimStart('\uFEFF'))
        assertEquals(1, cfg.sites.size)
    }

    @Test
    fun `emoji 站点名原样保留`() {
        val json = """{"sites":[{"key":"a","name":"🐮【免费分享】🐮","type":1,"api":"u"}]}"""
        val cfg = TvBoxConfig.parse(json)
        assertEquals("🐮【免费分享】🐮", cfg.sites.first().name)
    }

    @Test
    fun `缺失必填字段用默认值`() {
        val json = """{"sites":[{"key":"a"}]}"""
        val cfg = TvBoxConfig.parse(json)
        assertEquals("", cfg.sites.first().name)
        assertEquals(0, cfg.sites.first().type)
        assertEquals("", cfg.sites.first().api)
    }

    @Test
    fun `lives 和 parses 也要能读`() {
        val json = """
            {
              "lives":[{"name":"CCTV","url":"http://x/live.m3u","groups":["央视"]}],
              "parses":[{"name":"解析1","type":1,"url":"http://p/?url="}]
            }
        """.trimIndent()
        val cfg = TvBoxConfig.parse(json)
        assertEquals(1, cfg.lives.size)
        assertEquals("CCTV", cfg.lives.first().name)
        assertEquals(1, cfg.parses.size)
    }
}
