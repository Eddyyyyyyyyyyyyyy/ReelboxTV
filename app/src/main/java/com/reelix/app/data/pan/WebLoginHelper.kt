package com.reelix.app.data.pan

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用 WebView 打开网盘网页版，扫码登录后提取 cookie。
 *
 * ## 为什么是这条路
 *
 * 试过三条路，只有这条确定可行：
 *
 * | 路线 | 结果 |
 * |---|---|
 * | 夸克扫码 API | ❌ `su.quark.cn/4_eMHBJ?token=x` 只在**夸克 App 内**有效，普通扫码提示「登录请求已过期」 |
 * | 手动填 Cookie | ✅ 可行但太难 —— 用户要在手机上开开发者工具复制整串 |
 * | **WebView 网页版登录** | ✅ **本方案** |
 *
 * ## 关键事实（实测）
 *
 * ```
 * 桌面 UA → https://pan.quark.cn/   → 200, 40KB, 含 canvas#react-qrcode-logo  ✅ 有真二维码
 * 移动 UA → 同上                    → 302 到 h5.sm.cn 推广页                  ❌
 * 桌面 UA → uop.quark.cn/cas/login  → 只有账号密码，没有二维码                  ❌
 * ```
 *
 * 所以必须用**桌面 UA** 打开 `pan.quark.cn` 首页，那里才是带二维码的登录入口。
 *
 * ## 交互设计
 *
 * 电视上没有鼠标，但**扫码本来就不需要点击**：
 * 1. WebView 加载页面，二维码自动渲染
 * 2. 用户用手机网盘 App 扫屏幕上的码
 * 3. 手机确认后页面跳转，我们用 CookieManager 轮询 cookie
 * 4. 拿到 cookie 立刻写进 jar 的 prefs
 *
 * 全程只需要用户做「扫一下」这一个动作，符合"不用填东西"的要求。
 */
object WebLoginHelper {

    private const val TAG = "WebLoginHelper"

    /** 各网盘的网页版登录地址（都用桌面 UA 打开） */
    fun loginUrl(provider: PanCookieStore.Provider): String = when (provider) {
        // 只有这个页面带真二维码
        PanCookieStore.Provider.QUARK -> "https://pan.quark.cn/"
        PanCookieStore.Provider.UC -> "https://drive.uc.cn/"
        PanCookieStore.Provider.ALI -> "https://www.alipan.com/"
        PanCookieStore.Provider.PAN115 -> "https://115.com/"
        PanCookieStore.Provider.BAIDU -> "https://pan.baidu.com/"
    }

    /**
     * 需要提取的 cookie 域名。
     *
     * CookieManager.getCookie(url) 只返回**该 URL 域**下的 cookie，
     * 所以要按每个网盘的真实 cookie 域去取。
     */
    fun cookieDomain(provider: PanCookieStore.Provider): String = when (provider) {
        PanCookieStore.Provider.QUARK -> "https://pan.quark.cn"
        PanCookieStore.Provider.UC -> "https://drive.uc.cn"
        PanCookieStore.Provider.ALI -> "https://www.alipan.com"
        PanCookieStore.Provider.PAN115 -> "https://115.com"
        PanCookieStore.Provider.BAIDU -> "https://pan.baidu.com"
    }

    /**
     * 登录态实际分布的域名。
     *
     * `CookieManager.getCookie(url)` 只返回该精确域下的 cookie，
     * 而夸克/UC 的登录态横跨 `pan.`、`uop.`、`passport.` 与父域 `.quark.cn`。
     * 清除时必须逐个域名清，否则用户会看到"清了还在"。
     */
    fun cookieDomains(provider: PanCookieStore.Provider): List<String> = when (provider) {
        PanCookieStore.Provider.QUARK -> listOf(
            "https://pan.quark.cn", "https://uop.quark.cn", "https://passport.quark.cn",
            "https://quark.cn", "https://www.quark.cn",
        )
        PanCookieStore.Provider.UC -> listOf(
            "https://drive.uc.cn", "https://fast.uc.cn", "https://uc.cn", "https://www.uc.cn",
        )
        PanCookieStore.Provider.ALI -> listOf(
            "https://www.alipan.com", "https://alipan.com", "https://passport.alipan.com",
            "https://auth.alipan.com",
        )
        PanCookieStore.Provider.PAN115 -> listOf(
            "https://115.com", "https://www.115.com", "https://passport.115.com", "https://webapi.115.com",
        )
        PanCookieStore.Provider.BAIDU -> listOf(
            "https://pan.baidu.com", "https://passport.baidu.com", "https://baidu.com", "https://www.baidu.com",
        )
    }

    /** 父域；删除时带上 Domain 属性才能清掉父域 cookie。 */
    fun cookieParentDomain(provider: PanCookieStore.Provider): String = when (provider) {
        PanCookieStore.Provider.QUARK -> ".quark.cn"
        PanCookieStore.Provider.UC -> ".uc.cn"
        PanCookieStore.Provider.ALI -> ".alipan.com"
        PanCookieStore.Provider.PAN115 -> ".115.com"
        PanCookieStore.Provider.BAIDU -> ".baidu.com"
    }

    /**
     * 判定"是否已登录"的关键 cookie 名。
     *
     * 每个网盘登录成功后都会下发一个标志性 cookie，出现它就说明成功。
     * ```
     * 夸克：__pus / b-user-id / __puus
     * UC：  __pus / b-user-id
     * 阿里：token / __puus
     * 115：  UID / CID
     * 百度：BDUSS
     * ```
     */
    fun authKeys(provider: PanCookieStore.Provider): List<String> = when (provider) {
        // __pus is also issued to guests; only account-bound cookies prove login.
        PanCookieStore.Provider.QUARK -> listOf("__puus", "b-user-id")
        PanCookieStore.Provider.UC -> listOf("__puus", "b-user-id")
        PanCookieStore.Provider.ALI -> listOf("__puus", "b-user-id")
        PanCookieStore.Provider.PAN115 -> listOf("UID", "CID", "SEID")
        PanCookieStore.Provider.BAIDU -> listOf("BDUSS", "STOKEN")
    }

    /**
     * 桌面 UA。
     *
     * ★ 这个值不能改 —— 移动 UA 会被 302 到推广页，拿不到二维码。
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 从 CookieManager 读出当前 cookie。
     *
     * @return `k=v; k2=v2` 形式，没登录返回 null
     */
    fun readCookie(provider: PanCookieStore.Provider): String? {
        val raw = runCatching {
            CookieManager.getInstance().getCookie(cookieDomain(provider))
        }.getOrNull()
        runCatching { CookieManager.getInstance().flush() }

        if (raw.isNullOrBlank()) return null

        // 过滤掉无意义的项，只留真正的业务 cookie
        val pairs = raw.split(";").mapNotNull { part ->
            val t = part.trim()
            if (t.isEmpty() || !t.contains('=')) null else t
        }

        if (pairs.isEmpty()) return null

        // 必须包含登录标志之一，否则只是游客 cookie
        val keys = authKeys(provider)
        val hasAuth = pairs.any { p ->
            val k = p.substringBefore('=').trim()
            keys.any { it.equals(k, ignoreCase = true) }
        }

        if (!hasAuth) {
            Log.d(TAG, "cookie 有 ${pairs.size} 项但缺登录标志（可能是游客态）")
            return null
        }

        Log.i(TAG, "读到 ${pairs.size} 项 cookie，含登录标志")
        return pairs.joinToString("; ")
    }

    /**
     * 轮询等待登录完成。
     *
     * 为什么用轮询而不是 WebViewClient 回调：
     * 各网盘的登录成功判断差异大（有的跳转、有的 XHR、有的 SPA 换状态），
     * 但**都是"cookie 里多出登录标志"**。盯 cookie 最通用。
     *
     * @param timeoutMs 最长等待（扫码 + 手机确认 + 跳转，给 3 分钟够）
     */
    suspend fun awaitLogin(
        provider: PanCookieStore.Provider,
        timeoutMs: Long = 180_000,
        intervalMs: Long = 1_500,
        onState: (String) -> Unit = {},
    ): String? = withTimeoutOrNull(timeoutMs) {
        var waited = 0L
        onState("等待扫码…")
        // Snapshot before the login page runs. A guest cookie present here must never
        // be accepted as a successful scan.
        val initial = readRawCookie(provider)

        while (waited < timeoutMs) {
            delay(intervalMs)
            waited += intervalMs

            // Ignore guest cookies that appear immediately while the page initializes.
            if (waited < MIN_CONFIRM_DELAY_MS) continue
            val cookie = readCookie(provider)
            // Require an account-bound cookie that was not present before the page opened.
            // Guest cookies and unrelated tracking-cookie changes must not look like a scan.
            if (cookie != null && hasNewAuthCookie(provider, initial, cookie)) {
                onState("已获取登录凭据")
                return@withTimeoutOrNull cookie
            }

            // 先提示扫码，1 分钟后提示可能在确认中
            if (waited == 60_000L) onState("还在等待…扫码后请在手机上点确认")
        }
        null
    }

    private fun readRawCookie(provider: PanCookieStore.Provider): String? =
        runCatching { CookieManager.getInstance().getCookie(cookieDomain(provider)) }.getOrNull()

    private fun hasAuthCookie(provider: PanCookieStore.Provider, raw: String): Boolean {
        val keys = authKeys(provider)
        return raw.split(";").any { part ->
            val name = part.substringBefore('=').trim()
            keys.any { it.equals(name, ignoreCase = true) }
        }
    }

    private fun hasNewAuthCookie(
        provider: PanCookieStore.Provider,
        before: String?,
        after: String,
    ): Boolean {
        val keys = authKeys(provider)
        fun names(raw: String?): Set<String> = raw.orEmpty().split(";")
            .mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                name.takeIf { it.isNotBlank() }
            }.toSet()
        val beforeNames = names(before)
        val afterNames = names(after)
        return keys.any { key ->
            afterNames.any { it.equals(key, ignoreCase = true) } &&
                beforeNames.none { it.equals(key, ignoreCase = true) }
        }
    }

    private const val MIN_CONFIRM_DELAY_MS = 8_000L

    /**
     * 配置 WebView 以便正常显示扫码页。
     *
     * 几个必须的开关：
     * ```
     * javaScriptEnabled     ← 二维码是 canvas 画的，没 JS 就是白屏
     * domStorageEnabled     ← 登录态存 localStorage
     * userAgentString       ← 必须是桌面 UA（见上）
     * 第三方 cookie 接受    ← 登录会跨域
     * ```
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        // Allow the SPA to open dialogs/windows; otherwise some login buttons do nothing.
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // 电视没有触摸，但让页面能接收点击（有些网盘要先点"扫码登录"）
            mediaPlaybackRequiresUserGesture = false
            // 部分登录页会混用 http 资源；不放行时页面可能只剩白底。
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "登录页加载完成: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                Log.w(
                    TAG,
                    "登录页资源失败: ${request?.url} " +
                        "code=${error?.errorCode} ${error?.description}",
                )
            }
        }
    }

    /**
     * 清掉网页侧的登录态。
     *
     * ★ 用户已确认接受"全局清干净"：CookieManager 与 WebStorage 都没有
     *   按网盘精确清理的 API（HttpOnly cookie 对 `getCookie` 不可见，
     *   localStorage 只能按 origin 删或全删），所以这里会连带清掉其他网盘
     *   在 WebView 里的登录态。这是取舍，不是遗漏。
     *
     * 清三处：
     * 1. CookieManager：`removeAllCookies`（含 HttpOnly）
     * 2. WebStorage：`deleteAllData`（夸克/UC 的 SPA 把登录态放 localStorage）
     * 3. WebView 磁盘缓存
     */
    fun clearCookie(context: Context, provider: PanCookieStore.Provider) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            Log.i(TAG, "已清理全部 WebView cookie（触发方：${provider.label}）")
        }
        // localStorage / IndexedDB：网页版扫码页的登录态主要存在这里。
        runCatching {
            android.webkit.WebStorage.getInstance().deleteAllData()
            Log.i(TAG, "已清理 WebStorage")
        }
        runCatching {
            android.webkit.WebView(context.applicationContext).run {
                clearCache(true)
                destroy()
            }
            Log.i(TAG, "已清理 WebView 缓存")
        }
        runCatching {
            android.webkit.WebViewDatabase.getInstance(context.applicationContext)
                .clearHttpAuthUsernamePassword()
        }
        // 同时清我们自己存的
        runCatching {
            PanCookieStore.clear(context, provider)
        }
        Log.i(TAG, "已清理 ${provider.label} 的 cookie")
    }
}
