package com.reelix.app.data.source.jar

import android.content.Context
import android.util.Log

/**
 * Spider 反射桥 —— 通过反射调用 jar 里的 TVBox 接口。
 *
 * ## 为什么必须用反射
 *
 * jar 里的类（`com.github.catvod.spider.Init`、`com.github.catvod.crawler.Spider`）
 * 只存在于运行时加载的 DexClassLoader 里，编译期 Reelbox 看不到它们。
 * 所以所有调用都得走反射。
 *
 * ## 约定的入口（从 dexdump 逆向确认）
 *
 * ```java
 * com.github.catvod.spider.Init
 *   static void init(Context)                          // 宿主注入 context
 *   static Spider getSpider(String key)                // 拿实例
 *   static Object[] proxyInvoke(HashMap)               // 统一代理调用
 *
 * com.github.catvod.crawler.Spider
 *   String homeContent(boolean filter)
 *   String homeVideoContent()
 *   String categoryContent(String tid, String pg, boolean filter, HashMap extend)
 *   String detailContent(List<String> ids)
 *   String searchContent(String key, boolean quick)
 *   String playerContent(String flag, String id, List<String> vipFlags)
 * ```
 *
 * ## 一个坑
 *
 * `Init.init(Context)` 必须**只调一次**，且必须在取 spider 之前。
 * 加固库会往 cacheDir 解出 `.wexfnw` 文件，
 * 重复 init 会因文件占用导致解密失败（现象是 classLoader 返回 null）。
 */
class SpiderBridge(
    private val loader: ClassLoader,
) {

    companion object {
        private const val TAG = "SpiderBridge"
        private const val CLASS_INIT = "com.github.catvod.spider.Init"
        private const val CLASS_SPIDER = "com.github.catvod.crawler.Spider"
    }

    private var initClass: Class<*>? = null
    private var initialized = false

    /** 上一次 init 是否成功 */
    val isReady: Boolean get() = initialized

    /**
     * 调用 jar 的 `Init.init(context)`。
     *
     * 只有成功返回才能继续 getSpider，否则后面全是空指针。
     */
    fun init(context: Context): Boolean {
        if (initialized) return true
        appContext = context.applicationContext

        val app = context.applicationContext as? android.app.Application
            ?: return false.also { Log.e(TAG, "context 不是 Application") }

        return runCatching {
            val cls = Class.forName(CLASS_INIT, true, loader)
            initClass = cls

            // ★★★ 关键：**不调 Init.init(Context)**，改用反射绑定字段
            //
            // 原因：`Init.init` 里有包名白名单校验（非 TVBox 宿主直接
            // Process.killProcess），且内部 DexNative.getLoader 在非 TVBox
            // 环境下会拿到 null 而抛 NPE。
            //
            // ProtectedJar 等价于 TVBox 自己的 ProtectedInitJar：
            // 直接把 Application 和 DexClassLoader 塞进 Init 单例的对应字段。
            //
            // 注意这一步要 ~14 秒（在解密 888KB 的 guard 文件），所以
            // 只在首次调用时执行，由 initialized 标志兜住。
            val ok = ProtectedJar.init(cls, app)
            initialized = true
            Log.d(TAG, "加固 jar 绑定${if (ok) "成功" else "部分成功（loader 未绑定）"}")
            ok
        }.getOrElse { e ->
            Log.e(TAG, "ProtectedJar.init 失败: ${e.cause?.message ?: e.message}", e)
            false
        }
    }

    /**
     * `Init.getSpider(key)` —— 按配置里的 api 名（如 `csp_WoggGuard`）取实例。
     */
    fun getSpider(key: String): Any? {
        if (!initialized) return null
        return runCatching {
            val cls = initClass ?: Class.forName(CLASS_INIT, true, loader)
            val method = cls.getDeclaredMethod("getSpider", String::class.java)
            method.isAccessible = true
            method.invoke(null, key)
        }.getOrElse { e ->
            Log.e(TAG, "getSpider($key) 失败: ${e.cause?.message ?: e.message}")
            null
        }
    }

    /**
     * 直接实例化 spider 类 —— **绕过 Init.getSpider**。
     *
     * ## 为什么需要这条路径
     *
     * 两个原因：
     *
     * 1. **加固型 jar 的 Init.getSpider 不可用**（DexNative 无法在宿主侧解密）
     * 2. **自研框架的 jar（如 lubin.php）根本没有 getSpider**，
     *    它的 Init 只有混淆过的 `a()`，不遵循 catvod 约定
     *
     * 但它们的 spider 类（`Wogg`、`PanWebShare` 等）**仍然是标准
     * `com.github.catvod.crawler.Spider` 的子类**，方法名也是标准的：
     * `homeContent` / `categoryContent` / `detailContent` / `searchContent` / `playerContent`。
     *
     * 所以可以直接 `Class.forName(...).newInstance()` + 调标准方法，
     * 完全不经过 Init。这是绕开加固限制的唯一途径。
     *
     * @param api 配置里的 api，形如 `csp_Wogg` / `csp_PanWebShare`
     * @param ext 站点 ext 字段
     */
    fun instantiateSprite(api: String, ext: String? = null): Any? {
        // csp_Wogg -> com.github.catvod.spider.Wogg
        val className = when {
            api.startsWith("csp_") -> "com.github.catvod.spider." + api.removePrefix("csp_")
            api.startsWith("com.") -> api
            else -> "com.github.catvod.spider.$api"
        }

        return runCatching {
            val cls = Class.forName(className, true, loader)
            val instance = cls.getDeclaredConstructor().newInstance()

            // 有 init(Context, String) 就先调一次，很多 spider 依赖它拿配置
            runCatching {
                val init = cls.getMethod("init", Context::class.java, String::class.java)
                init.invoke(instance, appContext, ext)
            }

            Log.d(TAG, "实例化成功: $className")
            instance
        }.getOrElse { e ->
            Log.e(TAG, "实例化 $className 失败: ${e.cause?.message ?: e.message}")
            null
        }
    }

    /** 供 instantiateSprite 用的 Application Context */
    private var appContext: Context? = null

    /** 设置 Context（init 时一起传） */
    fun attachContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /**
     * 调用 spider 实例的某个方法，返回 JSON 字符串。
     *
     * 统一入口：所有 homeContent/categoryContent/... 都走这里。
     *
     * @param spider getSpider 拿到的实例
     * @param method 方法名
     * @param argTypes 参数类型
     * @param args 参数
     */
    /**
     * 反射调用 spider 方法。
     *
     * ## ★ 为什么要有「按名字+参数个数」的降级
     *
     * 踩过的坑（2026-11-02，玩偶源分类永远返回空列表）：
     *
     * 我们原来只做 `getMethod(name, *argTypes)`，**类型必须精确匹配**。
     * 但 jar 里 `categoryContent` 的签名是：
     * ```
     * categoryContent(String, String, boolean, HashMap)
     *                  ↑       ↑       ↑        ↑
     *                            这个是**基本类型** boolean，
     *                            不是包装类 java.lang.Boolean
     * ```
     * 我们传的是 `Boolean::class.java`（包装类），`getMethod` 找不到签名就返回 null，
     * 于是「分类有名字、点进去 0 条内容」。
     *
     * Reelbox 的做法值得抄：**先按精确签名找，找不到就退化成「同名 + 参数个数相同」**。
     * 这样即使类型表有偏差也能调通。
     */
    fun invoke(
        spider: Any,
        method: String,
        argTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): String? {
        return runCatching {
            val klass = spider.javaClass

            // ① 先按精确签名找
            val m = runCatching { klass.getMethod(method, *argTypes) }
                .getOrElse {
                    // ② 退化：同名 + 参数个数相同
                    klass.methods.firstOrNull {
                        it.name == method && it.parameterCount == args.size
                    } ?: throw IllegalStateException(
                        "$method 方法不存在（${klass.simpleName}）"
                    )
                }

            val result = kotlinx.coroutines.runBlocking {
                // jar 内部可能有阻塞 IO，放到 IO 线程
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    m.invoke(spider, *args)
                }
            }
            result as? String
        }.getOrElse { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.e(TAG, "invoke $method 失败: ${cause.javaClass.simpleName}: ${cause.message}")
            null
        }
    }

    // ---------- 常用方法的便捷封装 ----------

    fun homeContent(spider: Any, filter: Boolean = false): String? =
        invoke(spider, "homeContent", arrayOf(java.lang.Boolean.TYPE), arrayOf(filter))

    /**
     * `homeVideoContent()` —— 首页推荐位。
     *
     * ## 和 homeContent 的区别
     *
     * ```
     * homeContent(true)     → {"class":[...], "list":[...]}
     *                          分类列表 + 第一屏内容
     *
     * homeVideoContent()    → {"list":[...]}
     *                          只有推荐位，没有分类
     * ```
     * 很多源的首页推荐是**单独一个接口**，和分类分开取。
     * 不调这个方法，首页就只能显示分类，推荐位是空的。
     *
     * 注意：部分 jar 没有这个方法，invoke 会返回 null，属正常。
     */
    fun homeVideoContent(spider: Any): String? =
        invoke(spider, "homeVideoContent", emptyArray(), emptyArray())

    @Suppress("UNCHECKED_CAST")
    fun categoryContent(
        spider: Any,
        tid: String,
        page: String,
        filter: Boolean = false,
        extend: Map<String, String> = emptyMap(),
    ): String? = invoke(
        spider,
        "categoryContent",
        arrayOf(String::class.java, String::class.java, java.lang.Boolean.TYPE, java.util.HashMap::class.java),
        arrayOf(tid, page, filter, HashMap(extend)),
    )

    fun detailContent(spider: Any, id: String): String? =
        invoke(spider, "detailContent", arrayOf(List::class.java), arrayOf(listOf(id)))

    fun searchContent(spider: Any, key: String, quick: Boolean = false): String? =
        invoke(
            spider,
            "searchContent",
            arrayOf(String::class.java, Boolean::class.java),
            arrayOf(key, quick),
        )

    fun playerContent(
        spider: Any,
        flag: String,
        id: String,
        vipFlags: List<String> = emptyList(),
    ): String? = invoke(
        spider,
        "playerContent",
        arrayOf(String::class.java, String::class.java, List::class.java),
        arrayOf(flag, id, vipFlags),
    )
}
