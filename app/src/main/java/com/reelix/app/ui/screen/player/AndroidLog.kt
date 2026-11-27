package com.reelix.app.ui.screen.player

import android.util.Log

/**
 * 播放链路日志开关。
 *
 * 为什么单独抽一个常量：
 *  电视端调试只能靠 `adb logcat`，但 Release 包里不该刷屏。
 *  这里统一走 Debug 开关，排查时打开、发版时关掉。
 */
internal object AndroidLog {
    const val TAG = "ReelixPlayer"

    var enabled: Boolean = true

    fun d(tag: String = TAG, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun e(tag: String = TAG, msg: String, tr: Throwable? = null) {
        if (enabled) Log.e(tag, msg, tr)
    }
}
