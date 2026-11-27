package com.reelix.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.reelix.app.data.image.PosterFetcher
import com.reelix.app.data.repo.ProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ReelboxApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashGuard()
        // ★ 搜索历史是 Application 级单例，初始化放这里而不是 Compose 的
        //   composable 主体（原来在 ReelboxRoot 里且没包 remember，每次重组都跑）。
        //   getSharedPreferences 只构造对象、不读盘，所以不需要挪后台。
        com.reelix.app.data.search.SearchHistory.init(this)
        warmUp()
    }

    /**
     * 把“第一次用到才初始化”的重活挪到后台：
     *  - Coil ImageLoader（OkHttp + 256MB 磁盘缓存）
     *  - Room 数据库构建
     *
     * 首帧和首次进首页不再临时付这笔钱。
     */
    private fun warmUp() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // 海报缓存是 SharedPreferences 里的一整段 JSON，解析量可能不小；
            // 放到后台，首帧不再等它。
            runCatching { PosterFetcher.init(this@ReelboxApp) }
            // 海报访问登记表（清理滞图用的），同样是后台读文件
            runCatching { com.reelix.app.data.image.PosterAccessLog.init(this@ReelboxApp) }
            runCatching { com.reelix.app.data.image.OnlineArtworkOverrides.init(this@ReelboxApp) }
            runCatching { com.reelix.app.data.image.OnlineMetadataStore.init(this@ReelboxApp) }
            runCatching { imageLoader }
            runCatching { ProgressRepository.database(this@ReelboxApp) }
            // TMDB 匹配结果落盘后也在这里加载，避免进详情页时多等一次
            runCatching { com.reelix.app.data.tmdb.TmdbClient.init(this@ReelboxApp) }
        }
    }

    /**
     * 全局未捕获异常兜底。
     *
     * ## 为什么需要
     *
     * TVBox 生态的 jar 是**第三方字节码**，我们在它里面调
     * `home()` / `detail()` / `play()`，内部抛什么异常都不受控：
     * ```
     * · 有的 jar 在 detail() 里直接 throw RuntimeException
     * · 有的在 init() 里崩在 native 层
     * · 有的在 UI 线程回调里抛（这次点"配置网盘cookie"闪退就是这个）
     * ```
     * 一个源崩掉不该让整个 App 退出——用户只会看到"点一下就没了"。
     *
     * ## 策略
     *
     * 记下现场（堆栈 + 线程），然后把 crash 页面渲染出来告诉用户
     * "刚才那个源崩了，已跳过"，而不是静默退出。
     *
     * 注意：这里**不吞掉异常**（不调用 exitProcess(0) 假装没事），
     * 而是记录后交给系统的默认处理器 —— 但会先写一份到日志，
     * 这样用户反馈"我点了X闪退"时能直接查到堆栈。
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Log.e(TAG, "=== 未捕获异常 ===", throwable)
                Log.e(TAG, "线程: " + thread.name)
                Log.e(TAG, "异常: " + throwable.javaClass.name + ": " + throwable.message)

                // 把最近几帧打到主日志，方便一眼看出是哪个模块
                throwable.stackTrace.take(12).forEach { e ->
                    Log.e(TAG, "  at " + e.toString())
                }

                // 如果是 jar 反射调用抛的，把 cause 也带出来
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    Log.e(TAG, "  caused by: " + cause.javaClass.name + ": " + cause.message)
                    cause = cause.cause
                    depth++
                }
            }

            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 图片请求拦截器：补齐图床必须的请求头。
     *
     * 优先级（后者不覆盖前者已有的头）：
     *  1. 源在图片地址里声明的头（`xxx.jpg@Referer=...`）
     *  2. 域名规则（豆瓣图床不带 Referer 会 418）
     *  3. 默认 UA 兜底
     */
    private val imageHeaderInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()

        val builder = request.newBuilder()
        // ① 源自己声明的头最权威
        com.reelix.app.data.image.ImageHeaderRegistry.headersFor(url).forEach { (name, value) ->
            if (request.header(name) == null) builder.header(name, value)
        }
        // ② 域名级规则（目前只有豆瓣图床）
        com.reelix.app.data.image.ImageSources.headersFor(url).forEach { (name, value) ->
            if (request.header(name) == null) builder.header(name, value)
        }
        // ③ 部分图床要求非空 UA，统一兜底
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", "Reelbox/0.1 (Android TV)")
        }
        chain.proceed(builder.build())
    }

    /**
     * 全局 OkHttp —— 图片、源请求、TMDB 共用同一个连接池。
     *
     * 为什么不给每个模块建一个：影视类 App 并发请求极多
     * （首页 20 张海报 + 详情页刮削 + 播放地址解析），
     * 共用连接池能省掉大量 TLS 握手。
     */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // 电视端并发不高，12 条 idle 足够；3 分钟比默认 5 分钟更适合
            // 弱网（连接长期挂着反而被 NAT 掉，复用时会先失败一次再重连）
            .connectionPool(ConnectionPool(12, 3, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 6
                }
            )
            .addInterceptor(imageHeaderInterceptor)
            .build()
    }

    /**
     * 源请求专用 client —— **从 [okHttp] 派生**，共享连接池与 Dispatcher。
     *
     * ## 为什么必须 newBuilder() 而不是新建
     *
     * `OkHttpClient.Builder()` 从零建 = 独立 ConnectionPool + 独立 Dispatcher +
     * 独立 RouteDatabase。之前 `JsonSpider` / `XmlSpider` 各自 `Builder().build()`，
     * 51 个站点就是 51 个 client、255 条 idle 连接、几十次重复 TLS 握手。
     *
     * `okHttp.newBuilder()` 派生出的 client **共享同一个连接池**，
     * 只是超时/拦截器可以不同。标准做法。
     *
     * ## 为什么超时要短
     *
     * 源接口经常整个域名挂掉，12s 连接超时会让"搜索"卡住。
     * 8s 足够正常接口响应，坏源能更快被放弃。
     */
    val spiderHttp: OkHttpClient by lazy {
        okHttp.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "ReelboxApp"

        /**
         * 给非 Application 上下文（如 Spider 内部）取共享 client。
         *
         * `JsonSpider` / `XmlSpider` 的构造参数默认值用这个，
         * 这样调用点不用改，也保证全 App 只有一个连接池。
         */
        @Volatile
        private var instance: ReelboxApp? = null

        fun app(): ReelboxApp? = instance

        fun http(): OkHttpClient? = instance?.spiderHttp
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .crossfade(true)
            // ★ 每张图加载成功（含缓存命中）都记一次访问时间。
            //
            //   这是「清理 7 天没看的海报」唯一可靠的数据源：Coil 2.7 的
            //   DiskCache 既不能枚举 key，文件元数据也只有写入时间没有访问时间。
            //   在这里统一拦截，比在十几个加载点各加一行靠谱。
            .eventListener(object : coil.EventListener {
                override fun onSuccess(
                    request: coil.request.ImageRequest,
                    result: coil.request.SuccessResult,
                ) {
                    val url = when (val d = request.data) {
                        is String -> d
                        is android.net.Uri -> d.toString()
                        else -> null
                    }
                    if (!url.isNullOrBlank()) {
                        com.reelix.app.data.image.PosterAccessLog.record(url)
                    }
                }
            })
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            // 电视端网络不稳，缓存策略放宽，减少重复请求
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
