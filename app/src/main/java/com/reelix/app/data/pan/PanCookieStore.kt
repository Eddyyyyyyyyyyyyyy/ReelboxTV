package com.reelix.app.data.pan

import android.content.Context
import android.util.Log

/**
 * 把网盘凭据写进 jar 自己的 SharedPreferences。
 *
 * ## 为什么可以直接写
 *
 * 关键事实（ReelboxTV 项目实测确认）：
 * **spider jar 跑在宿主进程里**，它用的 SharedPreferences
 * 就是我们包里那几个文件。文件名 `NewWexFnw`（对应
 * `NewWexFnw_preferences.xml`）就是这类 Wex 系列 jar 的存储。
 *
 * 所以不需要破解加密 JS，也不需要 hook —— 直接往它自己的 prefs 里写，
 * jar 下次读配置时就能拿到。
 *
 * ## 双保险
 *
 * 除了写 prefs，调用方还可以把 cookie 合进站点 `ext` 传给 `init()`。
 * 两条路都写，不同 jar 版本读哪边都能覆盖到。
 *
 * ## 键名约定
 *
 * 沿用 TVBox 生态的配置模板：
 * ```
 * quark_cookie        夸克
 * uc_cookie           UC
 * baidu_cookie        百度
 * aliyun_token        阿里云盘
 * 115_cookie          115
 * thunder_username    迅雷账号
 * thunder_password    迅雷密码
 * ```
 */
object PanCookieStore {

    private const val TAG = "PanCookieStore"

    /** 已知的 Wex 系列 prefs 名；真实 jar 还会按站点分文件（sp_Hmys / sp_MaiDuiDui …）。 */
    private val KNOWN_PREF_NAMES = listOf(
        "NewWexFnw",
        "WexFnw",
        "spider",
        "config",
    )

    /** 我们自己维护的 prefs，写入 jar 时要排除。 */
    private val OUR_OTHER_PREFS = setOf(
        "reelix_settings", "reelix_pan_auth", "reelix_search", "reelix_skip",
        "reelix_poster_cache", "reelix_hot_search", "reelix_history_snapshot",
        "WebViewChromiumPrefs", "AwOriginVisitLoggerPrefs", "load_time",
        "poster_cache_state", "com.reelix.app_preferences",
    )

    /** 我们自己的存储（UI 回显、诊断用） */
    private const val OUR_PREFS = "reelix_pan_auth"

    /** 已知的按站点分文件的 jar prefs 前缀/名字，用于首次预建。 */
    private val JAR_SITE_PREFS = setOf(
        "spUtils", "appSx", "ff_sp", "Hmys", "MaiDuiDui", "sp_Hmys", "sp_MaiDuiDui",
    )

    /** 支持的网盘及其键名 */
    enum class Provider(val label: String, val cookieKey: String) {
        QUARK("夸克网盘", "quark_cookie"),
        UC("UC 网盘", "uc_cookie"),
        ALI("阿里云盘", "aliyun_token"),
        PAN115("115 网盘", "115_cookie"),
        BAIDU("百度网盘", "baidu_cookie"),
    }

    /**
     * 各网盘在 jar prefs 里可能出现的键名。
     *
     * Wex 系列 jar 不只读 `*_cookie`，还会把登录态存成 `quarkSt` / `quark_ck`
     * 这类短键（`quark_ck` 是它自己加密后的串）。判断"有没有登录过"必须两种都认，
     * 否则用户在 jar 里扫过码，设置页仍显示"未配置"。
     */
    private fun jarKeys(provider: Provider): List<String> = when (provider) {
        Provider.QUARK -> listOf("quark_cookie", "quarkSt", "quark_ck")
        Provider.UC -> listOf("uc_cookie", "ucSt", "uc_ck")
        Provider.ALI -> listOf("aliyun_token", "aliSt", "ali_ck", "ali_token")
        Provider.PAN115 -> listOf("115_cookie", "115St", "115_ck")
        Provider.BAIDU -> listOf("baidu_cookie", "baiduSt", "baidu_ck")
    }

    /** 看起来像网盘登录凭据的键；用于判断某个 prefs 是否存网盘登录态。 */
    internal fun isAuthKey(key: String): Boolean {
        val lower = key.lowercase()
        // 只认「整键就是 cookie/token」或「以 _cookie / _token / Cookie 结尾」，
        // 不用 contains：`cookieBannerDismissedAt` 这类普通开关也会命中。
        if (lower.endsWith("cookie") || lower.endsWith("_token")) return true
        return Provider.entries.any { jarKeys(it).any { k -> k.equals(key, ignoreCase = true) } }
    }

    /**
     * 写入 cookie。
     *
     * 会同时写进：
     * 1. 我们自己的 prefs（记录 + 回显）
     * 2. jar 的 prefs（真正生效的地方）
     *
     * @return 是否至少写成功了 jar 侧
     */
    fun save(context: Context, provider: Provider, cookie: String): Boolean {
        val app = context.applicationContext

        // ① 我们自己的记录
        runCatching {
            app.getSharedPreferences(OUR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(provider.cookieKey, cookie)
                .apply()
        }

        // ② 写进 jar 的 prefs
        var written = 0
        for (name in jarPrefNames(context)) {
            val sp = runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE) }.getOrNull() ?: continue
            val existing = sp.all
            // 已经存了同名键，或这个 prefs 本来就存网盘凭据，才写入。
            // 避免把 cookie 塞进无关的 jar 存储。
            val ownsKey = existing.containsKey(provider.cookieKey)
            val looksLikeJarAuth = existing.keys.any(::isAuthKey)
            // ★ 新源首次播放时它的 prefs 文件还不存在（existing 为空）。
            //   只认"已存在同名键"会导致那个源必须单独扫一次码，所以对**站点级**
            //   jar prefs（`sp_*` / 已知站点名）允许预建。
            //   通用名（config / spider）不预建，避免凭空造出一堆空 prefs。
            val knownJarStore = name.startsWith("sp_") || name in JAR_SITE_PREFS
            if (!ownsKey && !looksLikeJarAuth && !knownJarStore) continue
            runCatching {
                sp.edit().putString(provider.cookieKey, cookie).commit()
                written++
                Log.i(TAG, "写入 $name.${provider.cookieKey} 成功（原有 ${existing.size} 项）")
            }.onFailure { Log.w(TAG, "写入 $name 失败", it) }
        }

        if (written == 0) {
            Log.w(TAG, "没有找到 jar 的 prefs，只有我们自己记录了 cookie")
        }
        return written > 0
    }

    /**
     * 找出宿主里所有可能属于 jar 的 prefs。
     *
     * 真实 jar 会按站点分文件（`sp_Hmys`、`sp_MaiDuiDui`、`spUtils`、`appSx`、`ff_sp`），
     * 只写死 `NewWexFnw` 会全部落空 —— 这就是"授权成功但仍要扫码"的原因。
     */
    private fun jarPrefNames(context: Context): List<String> {
        val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        val existing = runCatching { dir.listFiles()?.map { it.nameWithoutExtension }.orEmpty() }
            .getOrDefault(emptyList())
        return (KNOWN_PREF_NAMES + existing)
            .filter { it.isNotBlank() && it !in OUR_OTHER_PREFS }
            .distinct()
    }

    /**
     * 清掉某个网盘的凭据。
     *
     * 同时清两处：
     * 1. 我们自己的记录（UI 回显用）
     * 2. jar 的 prefs（真正生效的地方）
     *
     * 不清的话，jar 下次启动还会读到旧 cookie，
     * 用户会以为"点了清理没生效"。
     */
    fun clear(context: Context, provider: Provider) {
        val app = context.applicationContext

        runCatching {
            app.getSharedPreferences(OUR_PREFS, Context.MODE_PRIVATE)
                .edit().remove(provider.cookieKey).apply()
        }

        // 删掉这个网盘在所有 prefs 文件里的全部键名变体
        // （`quark_cookie` + jar 自己的 `quarkSt` / `quark_ck`）。
        //
        // ★ 不删其他网盘的键：UI 上是"逐个清除"，清夸克却把 115 也清掉
        //   是反直觉的破坏性行为。CookieManager / WebStorage 只能全局清，
        //   那是 API 限制；jar prefs 能精确定位，就精确定位。
        val keys = jarKeys(provider)
        for (name in jarPrefNames(context)) {
            runCatching {
                val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                val targets = sp.all.keys.filter { key -> keys.any { it.equals(key, ignoreCase = true) } }
                if (targets.isEmpty()) return@runCatching
                val editor = sp.edit()
                targets.forEach(editor::remove)
                editor.commit()
                Log.i(TAG, "已清除 $name 的 ${targets.size} 项凭据：$targets")
            }
        }
    }

    /** 读回已保存的 cookie（UI 回显 / 诊断） */
    fun get(context: Context, provider: Provider): String? =
        context.applicationContext
            .getSharedPreferences(OUR_PREFS, Context.MODE_PRIVATE)
            .getString(provider.cookieKey, null)

    /**
     * 已配置哪些网盘。
     *
     * 三处任一命中即算：
     * 1. 我们自己的 `reelix_pan_auth`
     * 2. jar prefs 里的凭据键（`quarkSt` / `quark_ck` …）
     * 3. CookieManager 里仍然存在的 auth cookie
     *
     * 之所以要合并三处：用户实际是在 jar 自带的配置中心扫码的，
     * 那份凭据只进 jar prefs，不经过我们，只看 ① 会永远显示"未配置"。
     */
    fun configured(context: Context): List<Provider> =
        Provider.entries.filter { isConfigured(context, it) }

    fun isConfigured(context: Context, provider: Provider): Boolean {
        if (!get(context, provider).isNullOrBlank()) return true

        val app = context.applicationContext
        for (name in jarPrefNames(context)) {
            val sp = runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE) }.getOrNull() ?: continue
            val all = sp.all
            if (jarKeys(provider).any { key -> (all[key] as? String)?.isNotBlank() == true }) return true
        }

        return runCatching {
            val raw = android.webkit.CookieManager.getInstance()
                .getCookie(WebLoginHelper.cookieDomain(provider))
            !raw.isNullOrBlank() && WebLoginHelper.authKeys(provider).any { key ->
                raw.split(";").any { it.substringBefore('=').trim().equals(key, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    /**
     * 列出宿主里所有可能属于 jar 的 prefs 文件名。
     *
     * 诊断用：如果保存后 jar 还是说没登录，就把这个列表打出来，
     * 看它到底用了哪个文件名。
     */
    fun listPrefFiles(context: Context): List<String> =
        context.applicationContext
            .getSharedPreferences(OUR_PREFS, Context.MODE_PRIVATE)
            .all.keys.toList()
            .let { _ ->
                // 扫 data/data/<pkg>/shared_prefs 目录
                runCatching {
                    val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                    dir.listFiles()?.map { it.nameWithoutExtension }.orEmpty()
                }.getOrElse { emptyList() }
            }
}
