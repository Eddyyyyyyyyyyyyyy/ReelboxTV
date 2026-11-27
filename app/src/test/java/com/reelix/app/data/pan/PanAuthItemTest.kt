package com.reelix.app.data.pan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置类条目识别测试。
 *
 * ## 为什么必须测
 *
 * 这个识别错一次，用户就会：
 * - 漏判 → 点进详情页 → **闪退**（实测小米电视）
 * - 误判 → 普通片子被拦下来 → 打不开
 *
 * 而且这两类条目（影视 vs 配置）在列表里长得一样，
 * 只能靠 vod_id 判断，所以规则必须准。
 */
class PanAuthItemTest {

    // ---------- 按标题识别（vod_id 是序号时唯一可用的线索） ----------

    @Test
    fun `标题里的清除 Cookie 识别为直接清除`() {
        val m = PanAuthItem.matchTitle("清除嘟嘟Cookie")
        assertNotNull("应该识别", m)
        assertEquals(PanAuthItem.Action.CLEAR, m!!.action)
        assertEquals(PanCookieStore.Provider.BAIDU, m.provider)
    }

    @Test
    fun `源站花名要映射到正确网盘`() {
        assertEquals(PanCookieStore.Provider.UC, PanAuthItem.matchTitle("清除优汐Cookie")?.provider)
        assertEquals(PanCookieStore.Provider.QUARK, PanAuthItem.matchTitle("清除夸父Cookie")?.provider)
        assertEquals(PanCookieStore.Provider.ALI, PanAuthItem.matchTitle("清除阿狸token")?.provider)
    }

    @Test
    fun `登入自己网盘识别为打开授权页`() {
        val m = PanAuthItem.matchTitle("登入自己网盘")
        assertNotNull("应该识别", m)
        assertEquals(PanAuthItem.Action.OPEN_AUTH, m!!.action)
    }

    @Test
    fun `普通片名不能被误判`() {
        assertNull(PanAuthItem.matchTitle("蜘蛛侠：崭新之日"))
        assertNull(PanAuthItem.matchTitle("现在就出发 第四季"))
        assertNull(PanAuthItem.matchTitle("我不是大师"))
        assertNull(PanAuthItem.matchTitle("网盘"))
    }

    // ---------- 应该识别的 ----------

    @Test
    fun `夸克配置项`() {
        val m = PanAuthItem.match("quarkcookie")
        assertNotNull("应该识别", m)
        assertEquals(PanCookieStore.Provider.QUARK, m!!.provider)
    }

    @Test
    fun `UC 配置项不能误判成夸克`() {
        val m = PanAuthItem.match("ucpancookie")
        assertNotNull(m)
        assertEquals("uc 应优先于 quark", PanCookieStore.Provider.UC, m!!.provider)
    }

    @Test
    fun `115 配置项`() {
        assertEquals(PanCookieStore.Provider.PAN115, PanAuthItem.match("115pandiy")!!.provider)
    }

    @Test
    fun `阿里 token 配置项`() {
        assertEquals(PanCookieStore.Provider.ALI, PanAuthItem.match("aliyuntoken")!!.provider)
    }

    @Test
    fun `百度配置项`() {
        assertEquals(PanCookieStore.Provider.BAIDU, PanAuthItem.match("baiducookie")!!.provider)
    }

    @Test
    fun `不认识但明显是配置项的也要拦下来`() {
        // 天翼不是我们支持的网盘，但绝不能放它进详情页 —— 会闪退
        val m = PanAuthItem.match("tianyilogin")
        assertNotNull("应拦下", m)
        assertNull("但不是已知网盘", m!!.provider)
    }

    @Test
    fun `emby server 也算配置项`() {
        assertNotNull(PanAuthItem.match("editembyserver"))
    }

    @Test
    fun `弹幕按钮也算`() {
        assertNotNull(PanAuthItem.match("danmubtn"))
    }

    // ---------- 不应该识别的 ----------

    @Test
    fun `普通影视 id 不拦`() {
        assertNull(PanAuthItem.match("/index.php/vod/detail/id/3317.html"))
        assertNull(PanAuthItem.match("msearch:37446297"))
        assertNull(PanAuthItem.match("12345"))
    }

    @Test
    fun `片名里带 cookie 但不是配置项`() {
        // 有些片名可能含 user 之类，但没有 cookie/login/token 组合就不该拦
        assertNull(PanAuthItem.match("movie_user_story"))
    }

    @Test
    fun `空值安全`() {
        assertNull(PanAuthItem.match(""))
        assertNull(PanAuthItem.match("   "))
    }

    @Test
    fun `大小写不敏感`() {
        assertNotNull(PanAuthItem.match("QuarkCookie"))
        assertNotNull(PanAuthItem.match("QUARKCOOKIE"))
    }

    // ---------- anyConfigItem ----------

    @Test
    fun `列表里有一个配置项就返回 true`() {
        val ids = listOf("/detail/1.html", "quarkcookie", "/detail/2.html")
        assertTrue(PanAuthItem.anyConfigItem(ids))
    }

    @Test
    fun `全是普通内容返回 false`() {
        val ids = listOf("/detail/1.html", "/detail/2.html")
        assertTrue(!PanAuthItem.anyConfigItem(ids))
    }
}
