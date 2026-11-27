package com.reelix.app.data.pan

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 夸克网盘扫码登录 —— 直接走夸克自己的二维码 API。
 *
 * ## 为什么不用 WebView
 *
 * 网页版 `pan.quark.cn` 现在会跳到 `h5.sm.cn` 的推广页，
 * 套 WebView 既拿不到干净的登录界面，提取 cookie 的 key 也对不上 jar 的格式。
 *
 * 夸克的移动端 App 用的是**独立的二维码登录 API**，实测可用：
 *
 * ```
 * ① GET uop.quark.cn/cas/ajax/getTokenForQrcodeLogin
 *    → {"status":2000000,"data":{"members":{"token":"sta..."}}}
 *
 * ② 二维码内容 = https://su.quark.cn/4_eMHBJ?token=<token>
 *    用户用夸克 App 扫描 → 手机确认
 *
 * ③ GET uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?token=<token>
 *    → 未扫码：{"status":50004001,"message":"Query result is empty"}
 *    → 已确认：{"status":2000000,"data":{"members":{"service_ticket":"..."}}}
 *
 * ④ 用 service_ticket 换正式 cookie（下一步）
 * ```
 *
 * ## 和 jar 的衔接
 *
 * 拿到 cookie 后写进 jar 自己的 SharedPreferences
 * （文件名 `NewWexFnw`，实测由 spider 在宿主进程内使用），
 * 也可以合并进站点 `ext` 传给 `init()`。两条路都写，双保险。
 */
object QuarkQrLogin {

    private const val TAG = "QuarkQrLogin"

    private const val URL_TOKEN = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
    private const val URL_STATUS = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
    private const val QR_BASE = "https://su.quark.cn/4_eMHBJ?token="

    /** 扫码状态 */
    sealed interface State {
        /** 已拿到 token，展示二维码等待扫描 */
        data class Waiting(val token: String, val qrContent: String) : State

        /** 已扫码，等待手机确认 */
        data object Scanned : State

        /** 已确认，拿到了 service ticket */
        data class Confirmed(val serviceTicket: String) : State

        /** 二维码过期，需要重新获取 */
        data object Expired : State

        data class Error(val message: String) : State
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        // ★ 必须关闭自动 cookie 管理：换 cookie 那步要从 Set-Cookie 头里
        //   手工捞原始值，OkHttp 自动处理会把头吃掉
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .build()

    /** 夸克接口对 UA 敏感，用移动端 UA 最稳 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 第一步：获取二维码。
     *
     * @return [State.Waiting] 里带 token 和二维码内容
     */
    suspend fun requestQr(): State = withContext(Dispatchers.IO) {
        runCatching {
            val body = get(URL_TOKEN) ?: return@runCatching State.Error("取二维码失败：无响应")
            val root = json.parseToJsonElement(body).jsonObject
            val status = root["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (status != 2000000) {
                return@runCatching State.Error("夸克返回异常：${root["message"]?.jsonPrimitive?.contentOrNull}")
            }
            val token = root["data"]?.jsonObject
                ?.get("members")?.jsonObject
                ?.get("token")?.jsonPrimitive?.contentOrNull
                ?: return@runCatching State.Error("没有拿到 token")

            Log.i(TAG, "二维码已生成: ${token.take(12)}…")
            State.Waiting(token, QR_BASE + token)
        }.getOrElse { State.Error(it.message ?: "未知错误") }
    }

    /**
     * 第二步：轮询扫码状态。
     *
     * 调用方每 2 秒调一次。返回：
     * - [State.Scanned]     用户已扫，还没在手机上点确认
     * - [State.Confirmed]   确认完成，可以换 cookie 了
     * - [State.Expired]     过期
     * - [State.Waiting]     继续等
     *
     * 夸克的返回码（实测）：
     * ```
     * 50004001  Query result is empty   → 还没扫
     * 2000000   有 service_ticket       → 已确认
     * 其他       过期或异常
     * ```
     */
    suspend fun poll(token: String): State = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$URL_STATUS?token=$token")
                ?: return@runCatching State.Error("轮询失败：无响应")

            val root = json.parseToJsonElement(body).jsonObject
            val status = root["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()

            when (status) {
                2000000 -> {
                    val ticket = root["data"]?.jsonObject
                        ?.get("members")?.jsonObject
                        ?.get("service_ticket")?.jsonPrimitive?.contentOrNull
                    if (ticket.isNullOrBlank()) {
                        Log.d(TAG, "已确认但无 ticket，视为待确认")
                        State.Scanned
                    } else {
                        Log.i(TAG, "扫码确认成功，ticket=${ticket.take(10)}…")
                        State.Confirmed(ticket)
                    }
                }

                // 未扫码 / 未确认
                50004001 -> State.Waiting(token, QR_BASE + token)

                else -> {
                    Log.d(TAG, "轮询返回 status=$status msg=$message")
                    if (message.contains("expire", ignoreCase = true)) {
                        State.Expired
                    } else {
                        State.Waiting(token, QR_BASE + token)
                    }
                }
            }
        }.getOrElse { State.Error(it.message ?: "轮询异常") }
    }

    /**
     * 第四步：用 service ticket 换 cookie。
     *
     * 夸克的登录态走 **Set-Cookie**，不在 body 里：
     * ```
     * GET pan.quark.cn/account/info?st=<service_ticket>
     *   → Set-Cookie: ctoken=...; path=/; secure
     *   → Set-Cookie: __puus=...; ...
     * ```
     * 所以这里必须关掉 OkHttp 的自动 cookie 管理，
     * 才能从响应头里把原始 Set-Cookie 全部捞出来自己拼。
     *
     * @return `k=v; k2=v2` 形式的 cookie 串，失败返回 null
     */
    suspend fun exchangeCookie(serviceTicket: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://pan.quark.cn/account/info?st=$serviceTicket")
                .header("User-Agent", UA)
                .header("Referer", "https://pan.quark.cn/")
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.headers("Set-Cookie")
                if (raw.isEmpty()) {
                    Log.w(TAG, "换 cookie 时没有 Set-Cookie，响应码 ${resp.code}")
                    return@use null
                }

                // 只取 k=v，去掉 path/secure 等属性
                val pairs = raw.mapNotNull { line ->
                    line.substringBefore(';').trim().takeIf { it.contains('=') }
                }.distinct()

                val cookie = pairs.joinToString("; ")
                Log.i(TAG, "拿到 cookie ${pairs.size} 项: ${pairs.map { it.substringBefore('=') }}")
                cookie.ifBlank { null }
            }
        }.getOrElse {
            Log.e(TAG, "exchangeCookie 失败", it)
            null
        }
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://pan.quark.cn/")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string()
        }
    }.onFailure { Log.e(TAG, "请求失败: $url", it) }.getOrNull()
}
