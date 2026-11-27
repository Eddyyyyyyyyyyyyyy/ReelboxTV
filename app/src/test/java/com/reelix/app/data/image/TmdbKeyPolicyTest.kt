package com.reelix.app.data.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbKeyPolicyTest {

    @Test fun `空 Key 永远不允许启用`() {
        assertFalse(TmdbKeyPolicy.canEnable("", ""))
        assertFalse(TmdbKeyPolicy.canEnable("   ", TmdbKeyPolicy.fingerprint("   ")))
    }

    @Test fun `没测试过的 Key 不允许启用`() {
        assertFalse(TmdbKeyPolicy.canEnable("abcdef0123456789", ""))
    }

    @Test fun `测试通过后同一个 Key 可以启用`() {
        val key = "abcdef0123456789"
        val fp = TmdbKeyPolicy.fingerprint(key)
        assertTrue(TmdbKeyPolicy.canEnable(key, fp))
        // 前后空格不影响判定，指纹按 trim 后的值算。
        assertTrue(TmdbKeyPolicy.canEnable("  $key  ", fp))
    }

    @Test fun `换 Key 后验证失效`() {
        val verified = TmdbKeyPolicy.fingerprint("old-key-0001")
        assertFalse(TmdbKeyPolicy.canEnable("new-key-0002", verified))
    }

    @Test fun `指纹稳定且不保存明文`() {
        val key = "abcdef0123456789"
        assertTrue(TmdbKeyPolicy.fingerprint(key) == TmdbKeyPolicy.fingerprint(key))
        assertFalse(TmdbKeyPolicy.fingerprint(key).contains(key))
    }
}
