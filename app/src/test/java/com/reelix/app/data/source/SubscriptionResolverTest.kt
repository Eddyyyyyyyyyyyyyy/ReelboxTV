package com.reelix.app.data.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 订阅地址解析。
 *
 * ## 这套测试守的是真实问题
 *
 * 2026-11-02 用户填 `http://www.饭太硬.cc/tv` 加载失败，报：
 * ```
 * Unexpected JSON token at offset 0: Expected start
 * ```
 * 实际那个地址返回的是「**JPEG 图片 + `**` + base64 编码的 JSON**」。
 *
 * 如果只做「fetch → JSON.parse」，三种情况都会失败：
 * ```
 * ① 图片 + ** + base64   ← 饭太硬这类
 * ② 纯 base64
 * ③ 导航页 HTML
 * ```
 * 这个测试把三种都覆盖住。
 */
class SubscriptionResolverTest {

    // ---------------- decode：判断是不是配置 ----------------

    @Test
    fun `纯 JSON 有 sites 时识别为配置`() {
        val json = """{"sites":[{"key":"a","name":"A","type":3,"api":"csp_X"}],"spider":"x"}"""
        val r = SubscriptionResolver.decode(json.toByteArray(Charsets.UTF_8))
        assertNotNull("应该识别为配置", r)
        assertTrue(r!!.contains("\"sites\""))
    }

    @Test
    fun `没有 sites 字段的 JSON 不算配置`() {
        // 很多接口返回 {"code":0,"msg":"ok"}，那不是 TVBox 配置
        val json = """{"code":0,"msg":"ok","data":[]}"""
        assertNull(SubscriptionResolver.decode(json.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `base64 编码的配置能解开`() {
        val original = """{"sites":[{"key":"b","name":"B","type":3,"api":"csp_Y"}]}"""
        val b64 = Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
        val r = SubscriptionResolver.decode(b64.toByteArray(Charsets.ISO_8859_1))
        assertNotNull("base64 应该能解", r)
        assertTrue(r!!.contains("\"sites\""))
    }

    @Test
    fun `base64 前缀的配置能解开`() {
        val original = """{"sites":[{"key":"c","name":"C","type":3,"api":"csp_Z"}]}"""
        val b64 = Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
        val r = SubscriptionResolver.decode("base64://$b64".toByteArray(Charsets.UTF_8))
        assertNotNull(r)
        assertTrue(r!!.contains("\"sites\""))
    }

    @Test
    fun `图片加双星号加 base64 能解开`() {
        // ★ 这就是饭太硬的真实格式：
        //   JPEG 字节 + "**" + base64(JSON)
        val original = """{"sites":[{"key":"d","name":"D","type":3,"api":"csp_W"}]}"""
        val b64 = Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))

        // 模拟 JPEG 二进制（非 UTF-8 字节）
        val fakeJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x81.toByte(), 0x82.toByte(), 0x83.toByte(),
        )
        val marker = "**".toByteArray(Charsets.ISO_8859_1)
        val payload = fakeJpeg + marker + b64.toByteArray(Charsets.ISO_8859_1)

        val r = SubscriptionResolver.decode(payload)
        assertNotNull("图片+**+base64 应该能解出来", r)
        val sites = Json.parseToJsonElement(r!!).jsonObject["sites"]!!.jsonArray
        assertEquals(1, sites.size)
        assertTrue(sites[0].jsonObject["key"].toString().contains("d"))
    }

    @Test
    fun `纯图片没有 base64 时返回 null`() {
        val fakeJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10
        )
        assertNull(SubscriptionResolver.decode(fakeJpeg))
    }

    @Test
    fun `空字节返回 null`() {
        assertNull(SubscriptionResolver.decode(ByteArray(0)))
    }

    @Test
    fun `带 BOM 的 JSON 能解开`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = """{"sites":[]}""".toByteArray(Charsets.UTF_8)
        val r = SubscriptionResolver.decode(bom + json)
        assertNotNull(r)
    }

    // ---------------- candidates：挖链接 ----------------

    @Test
    fun `从 data-clipboard-text 挖出配置地址`() {
        // ★ 这是导航页最重要的一种 —— <a href> 里根本没有
        val html = """
            <div class="copy-btn" data-clipboard-text="http://fty.xxooo.cf/tv">复制</div>
            <div class="copy-btn" data-clipboard-text="http://cdn.qiaoji8.com/tvbox.json">复制</div>
        """.trimIndent()

        val list = SubscriptionResolver.candidates(html, "http://example.com/")
        assertEquals(2, list.size)
        assertTrue(list.any { it.url == "http://fty.xxooo.cf/tv" })
        assertTrue(list.any { it.url == "http://cdn.qiaoji8.com/tvbox.json" })
    }

    @Test
    fun `从 a href 挖出地址`() {
        val html = """<a href="http://a.com/config.json">配置A</a>"""
        val list = SubscriptionResolver.candidates(html, "http://example.com/")
        assertTrue(list.any { it.url == "http://a.com/config.json" })
    }

    @Test
    fun `相对路径会补全`() {
        val html = """<a href="/tv/config.json">配置</a>"""
        val list = SubscriptionResolver.candidates(html, "http://example.com/page/")
        assertTrue(
            "相对路径应补全为绝对地址",
            list.any { it.url.contains("example.com") && it.url.endsWith("config.json") }
        )
    }

    @Test
    fun `过滤掉无关链接`() {
        val html = """
            <script src="https://www.googletagmanager.com/gtag/js"></script>
            <img src="https://x.com/logo.png">
            <a href="https://x.com/style.css">样式</a>
            <a href="http://real.com/tv.json">真配置</a>
        """.trimIndent()

        val list = SubscriptionResolver.candidates(html, "http://example.com/")
        assertEquals("应只保留配置类链接", 1, list.size)
        assertEquals("http://real.com/tv.json", list.first().url)
    }

    @Test
    fun `忽略 javascript 和 data 协议`() {
        val html = """
            <a href="javascript:void(0)">点击</a>
            <a href="data:text/plain,abc">数据</a>
            <a href="#top">顶部</a>
            <a href="http://real.com/a.json">真</a>
        """.trimIndent()

        val list = SubscriptionResolver.candidates(html, "http://example.com/")
        assertTrue(list.none { it.url.startsWith("javascript") })
        assertTrue(list.none { it.url.startsWith("data:") })
        assertEquals(1, list.size)
    }

    @Test
    fun `从 script 里挖 json 路径`() {
        val html = """<script>var cfg = "/api/tvbox.json";</script>"""
        val list = SubscriptionResolver.candidates(html, "http://example.com/")
        assertTrue(
            "script 里的 .json 也要挖出来",
            list.any { it.url.endsWith("tvbox.json") }
        )
    }

    @Test
    fun `空 HTML 返回空列表`() {
        assertTrue(SubscriptionResolver.candidates("", "http://x.com").isEmpty())
    }

    @Test
    fun `排除自身地址`() {
        val html = """<a href="http://example.com/">首页</a>"""
        val list = SubscriptionResolver.candidates(html, "http://example.com/")
        assertTrue("不该把导航页自己当候选", list.none { it.url == "http://example.com/" })
    }

    // ---------------- 相对路径归一化 ----------------

    @Test
    fun `配置里的相对 spider 补成绝对地址`() {
        // 真实场景：配置托管在子目录，spider 写相对路径
        val json = """{"spider":"./csp.jar;md5;abc123","sites":[{"key":"a","name":"A","type":3,"api":"csp_X"}]}"""
        val r = SubscriptionResolver.decode(
            json.toByteArray(Charsets.UTF_8),
            baseUrl = "https://cdn.example.com/tvbox/config.json",
        )
        assertNotNull(r)
        assertTrue("spider 应补成绝对地址，实际: $r", r!!.contains("https://cdn.example.com/tvbox/csp.jar;md5;abc123"))
    }

    @Test
    fun `配置里的根路径 api 补成绝对地址`() {
        val json = """{"sites":[{"key":"b","name":"B","type":1,"api":"/api/v1/vod"}]}"""
        val r = SubscriptionResolver.decode(
            json.toByteArray(Charsets.UTF_8),
            baseUrl = "https://site.example.com/tv/cfg.json",
        )
        assertNotNull(r)
        assertTrue("api 应补成 https://site.example.com/api/v1/vod，实际: $r", r!!.contains("https://site.example.com/api/v1/vod"))
    }

    @Test
    fun `绝对地址不会被改写`() {
        val json = """{"spider":"https://x.com/csp.jar;md5;abc","sites":[{"key":"c","name":"C","type":3,"api":"csp_Y"}]}"""
        val r = SubscriptionResolver.decode(
            json.toByteArray(Charsets.UTF_8),
            baseUrl = "https://cdn.example.com/tvbox/config.json",
        )
        assertNotNull(r)
        assertTrue("绝对地址应保持不变", r!!.contains("https://x.com/csp.jar;md5;abc"))
    }

    @Test
    fun `不传 baseUrl 时相对路径保持原样`() {
        val json = """{"spider":"./csp.jar","sites":[{"key":"d","name":"D","type":3,"api":"csp_Z"}]}"""
        val r = SubscriptionResolver.decode(json.toByteArray(Charsets.UTF_8))
        assertNotNull(r)
        assertTrue("没有 baseUrl 时不该乱改", r!!.contains("./csp.jar"))
    }

    // ---------------- JSON 结构分支 ----------------

    @Test
    fun `订阅仓库格式能被识别`() {
        val depot = """{"urls":[{"url":"./sub/config.json","name":"稳定版"}]}"""
        val shape = TvBoxCrypto.classify(depot)
        assertTrue("应识别为仓库", shape is TvBoxCrypto.Shape.Depot)
        shape as TvBoxCrypto.Shape.Depot
        assertEquals("./sub/config.json", shape.url)
        assertEquals("稳定版", shape.name)
    }

    @Test
    fun `msg 字段透传为错误提示`() {
        val msg = """{"msg":"本站已停止维护，请使用新地址"}"""
        val shape = TvBoxCrypto.classify(msg)
        assertTrue("应识别为提示", shape is TvBoxCrypto.Shape.Message)
        assertEquals("本站已停止维护，请使用新地址", (shape as TvBoxCrypto.Shape.Message).text)
    }

    @Test
    fun `带 sites 的 JSON 识别为配置`() {
        val cfg = """{"sites":[{"key":"e","name":"E","type":3,"api":"csp_E"}]}"""
        assertTrue(TvBoxCrypto.classify(cfg) is TvBoxCrypto.Shape.Config)
    }

    @Test
    fun `无关键字的 JSON 是 Unknown`() {
        assertTrue(TvBoxCrypto.classify("""{"code":0,"data":[]}""") is TvBoxCrypto.Shape.Unknown)
    }

    // ---------------- 解密 ----------------

    @Test
    fun `pk 后缀的 AES-ECB 配置能解开`() {
        // 用和 TVBox 一致的方式加密：密钥右补 "0" 到 16 位，AES-ECB-PKCS7
        val plain = """{"sites":[{"key":"f","name":"F","type":3,"api":"csp_F"}]}"""
        val key = "testkey"
        val padded = key + "0".repeat(16 - key.length)

        // ★ JVM 标准实现只有 PKCS5Padding（对 AES 而言等价于 PKCS7）
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(padded.toByteArray(Charsets.UTF_8), "AES"),
        )
        val hex = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val r = SubscriptionResolver.decode(hex.toByteArray(Charsets.US_ASCII), pkKey = key)
        assertNotNull("AES-ECB 应能解开，实际: $r", r)
        assertTrue(r!!.contains("\"sites\""))
    }

    @Test
    fun `密钥错误时返回 null`() {
        val hex = "0123456789abcdef0123456789abcdef"
        assertNull(SubscriptionResolver.decode(hex.toByteArray(Charsets.US_ASCII), pkKey = "wrong"))
    }

    // ---------------- 相对路径工具 ----------------

    @Test
    fun `ConfigPaths 解析同级相对路径`() {
        assertEquals(
            "https://x.com/tvd/csp.jar",
            ConfigPaths.resolve("https://x.com/tvd/config.json", "./csp.jar"),
        )
    }

    @Test
    fun `ConfigPaths 保留 md5 段`() {
        assertEquals(
            "https://x.com/tvd/csp.jar;md5;abc123",
            ConfigPaths.resolve("https://x.com/tvd/config.json", "./csp.jar;md5;abc123"),
        )
    }

    @Test
    fun `ConfigPaths 不动绝对地址和自定义 scheme`() {
        val base = "https://x.com/tvd/config.json"
        assertEquals("https://a.com/b.json", ConfigPaths.resolve(base, "https://a.com/b.json"))
        assertEquals("clan://192.168.1.5/tv.json", ConfigPaths.resolve(base, "clan://192.168.1.5/tv.json"))
        assertEquals("csp_Example", ConfigPaths.resolve(base, "csp_Example"))
    }
}
