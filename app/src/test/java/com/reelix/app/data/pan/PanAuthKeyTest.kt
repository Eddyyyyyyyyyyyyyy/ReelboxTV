package com.reelix.app.data.pan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判断 prefs 里哪些键算「网盘登录凭据」。
 *
 * 这条规则同时决定两件事，所以必须锁住：
 * 1. 设置页要不要显示「已配置」；
 * 2. 清除时删哪些键。
 *
 * 漏掉 `quarkSt` / `quark_ck` 会让 jar 里扫过码的用户永远显示「未配置」；
 * 误把普通业务键算进来，清除时就会删掉不该删的东西。
 */
class PanAuthKeyTest {

    @Test
    fun `recognises wex style short keys`() {
        assertTrue(PanCookieStore.isAuthKey("quarkSt"))
        assertTrue(PanCookieStore.isAuthKey("quark_ck"))
        assertTrue(PanCookieStore.isAuthKey("115St"))
        assertTrue(PanCookieStore.isAuthKey("ali_ck"))
    }

    @Test
    fun `recognises plain cookie and token keys`() {
        assertTrue(PanCookieStore.isAuthKey("quark_cookie"))
        assertTrue(PanCookieStore.isAuthKey("aliyun_token"))
        assertTrue(PanCookieStore.isAuthKey("someSiteCookie"))
    }

    @Test
    fun `ignores unrelated settings keys`() {
        assertFalse(PanCookieStore.isAuthKey("theme"))
        assertFalse(PanCookieStore.isAuthKey("lastPlayed"))
        assertFalse(PanCookieStore.isAuthKey("adFilter"))
        assertFalse(PanCookieStore.isAuthKey("cookieBannerDismissedAt"))
    }
}
