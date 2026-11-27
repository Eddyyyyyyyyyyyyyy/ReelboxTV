package com.reelix.app.data.image

import java.security.MessageDigest

/**
 * TMDB Key 的验证规则。
 *
 * 产品规则：没测试成功的 Key 不允许开启 TMDB。
 * 判定放在纯函数里，方便单测，也避免 UI 和存储各写一套。
 */
object TmdbKeyPolicy {

    /** Key 指纹：只存哈希，不额外落一份明文。 */
    fun fingerprint(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        return MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** 该 Key 是否已验证通过（空 Key 一律未验证）。 */
    fun isVerified(key: String, verifiedFingerprint: String): Boolean {
        val fp = fingerprint(key)
        return fp.isNotEmpty() && fp == verifiedFingerprint
    }

    /** 是否允许把 TMDB 打开。 */
    fun canEnable(key: String, verifiedFingerprint: String): Boolean = isVerified(key, verifiedFingerprint)
}
