package com.github.catvod.crawler

import android.util.Log

/**
 * 第三方插件二进制兼容层 —— `SpiderDebug`
 *
 * ## 为什么需要这个类
 *
 * jar 里的 spider 代码会调用 `com.github.catvod.crawler.SpiderDebug`，
 * 插件通过二进制符号引用调用该类；相关插件包不包含宿主侧实现。
 *
 * 实测直接加载 jar 会炸：
 * ```
 * NoClassDefFoundError: Failed resolution of:
 *   Lcom/github/catvod/crawler/SpiderDebug;
 *   at com.github.catvod.spider.Init.init(Unknown Source:320)
 * ```
 *
 * 所以宿主必须**按相同的包名和类名**提供一个实现。
 * jar 里的字节码是按符号引用调用的，只要签名对上就能链接成功。
 *
 * ## 签名维护原则
 *
 * 公开静态方法需要匹配实际插件引用。新增兼容项前，应从目标 JAR 的 `classes.dex` 核对调用签名：
 * ```
 * static void log(String msg)
 * static void log(String tag, String msg)     ← 部分版本
 * static void logError(String msg)            ← 部分版本
 * ```
 * 这里用 `log(String)` 和 `log(String, String)` 两个重载覆盖最常见的情况，
 * 并且全部用 @JvmStatic 保证 Java 静态方法语义。
 *
 * ## 实现原则
 *
 * 只做**转发**，不做任何业务逻辑。
 * 这个类被 jar 内部高频调用（每个网络请求都会打点），
 * 所以要够轻：默认走 android.util.Log.d，且带开关。
 */
object SpiderDebug {

    /** 是否输出日志。默认关，排查时打开 */
    @JvmStatic
    var enabled: Boolean = false

    private const val DEFAULT_TAG = "SpiderJar"

    @JvmStatic
    fun log(msg: String?) {
        if (!enabled) return
        Log.d(DEFAULT_TAG, msg ?: "null")
    }

    @JvmStatic
    fun log(tag: String?, msg: String?) {
        if (!enabled) return
        Log.d(tag ?: DEFAULT_TAG, msg ?: "null")
    }

    // 2026-11-19：真实订阅的 homeContent 会调用 log(Throwable)，
    // 旧版只实现了 String 重载，导致 NoSuchMethodError 并中断首页加载。
    @JvmStatic
    fun log(throwable: Throwable?) {
        if (!enabled || throwable == null) return
        Log.d(DEFAULT_TAG, "", throwable)
    }

    @JvmStatic
    fun log(tag: String?, throwable: Throwable?) {
        if (!enabled || throwable == null) return
        Log.d(tag ?: DEFAULT_TAG, "", throwable)
    }

    @JvmStatic
    fun log(tag: String?, msg: String?, throwable: Throwable?) {
        if (!enabled || throwable == null) return
        Log.d(tag ?: DEFAULT_TAG, msg ?: "null", throwable)
    }

    @JvmStatic
    fun logError(msg: String?) {
        if (!enabled) return
        Log.e(DEFAULT_TAG, msg ?: "null")
    }

    @JvmStatic
    fun logError(tag: String?, msg: String?) {
        if (!enabled) return
        Log.e(tag ?: DEFAULT_TAG, msg ?: "null")
    }

    @JvmStatic
    fun logError(throwable: Throwable?) {
        if (!enabled || throwable == null) return
        Log.e(DEFAULT_TAG, "", throwable)
    }

    @JvmStatic
    fun logError(tag: String?, throwable: Throwable?) {
        if (!enabled || throwable == null) return
        Log.e(tag ?: DEFAULT_TAG, "", throwable)
    }

    @JvmStatic
    fun logError(tag: String?, msg: String?, throwable: Throwable?) {
        if (!enabled || throwable == null) return
        Log.e(tag ?: DEFAULT_TAG, msg ?: "null", throwable)
    }
}
