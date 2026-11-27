package com.reelix.app.data.source.js

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * JS 源运行时的最小宿主环境。
 *
 * 从参考项目移植过来的 QuickJs / JsRuntime 依赖三个宿主设施：
 * 全局 Application、统一 UA、assets 缺模块时的兜底。
 * Reelbox 没有这些，但不想改 QuickJs 的内部逻辑
 * （那部分已经在真机上跑通过），所以这里补齐最小适配层。
 */
object JsHost {

    private const val TAG = "JsHost"

    @Volatile
    var appContext: Context? = null
        private set

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 统一 UA，和 spider jar 用的是同一个 */
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 简易 GET，给模块下载用 */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP " + resp.code + ": " + url)
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure {
        Log.e(TAG, "请求失败: " + url, it)
    }.getOrNull()

    /**
     * assets 里缺失模块时的内置兜底。
     *
     * drpy 系列爬虫会 import 一堆 assets/js/lib 下的模块。
     * 我们只搬了 host.js，没有把整包 drpy 库都搬过来。
     *
     * 缺模块时如果直接返回 null，QuickJS 会抛 module not found，
     * 整个源直接不可用。所以这里返回空壳模块，让 import 成功。
     */
    fun builtinModule(path: String): String? {
        val name = path.substringAfterLast('/')
        if (name.isBlank()) return null

        Log.d(TAG, "assets 缺模块 " + path + "，返回空壳")

        val sb = StringBuilder()
        sb.append("// 空壳模块：Reelbox 未内置该模块\n")
        sb.append("// 让 import 不报错，导出空实现让上层自然降级\n")
        sb.append("var __stub = function(){ return ''; };\n")
        sb.append("export default __stub;\n")
        sb.append("export var pdfh = __stub;\n")
        sb.append("export var pdfa = __stub;\n")
        sb.append("export var pd = __stub;\n")
        sb.append("export var req = __stub;\n")
        sb.append("export var joinUrl = function(a,b){ return b; };\n")
        return sb.toString()
    }
}