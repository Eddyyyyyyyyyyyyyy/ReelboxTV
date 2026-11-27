package com.reelix.app.data.source.online

/**
 * Small logging shim so the cache remains JVM-testable.
 *
 * Android's Log is stubbed in local unit tests; reflection keeps these
 * diagnostics optional and never changes cache results.
 */
internal object AndroidLog {
    fun d(tag: String, message: String) = runCatching {
        android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String) = runCatching {
        android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) = runCatching {
        if (throwable == null) android.util.Log.w(tag, message)
        else android.util.Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) = runCatching {
        if (throwable == null) android.util.Log.e(tag, message)
        else android.util.Log.e(tag, message, throwable)
    }
}
