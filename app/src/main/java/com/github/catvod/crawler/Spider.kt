package com.github.catvod.crawler

/**
 * 第三方插件二进制兼容层 —— `Spider` 基类
 *
 * ## 为什么需要
 *
 * jar 里的 `com.github.catvod.spider.Wogg` 等类都 `extends
 * com.github.catvod.crawler.Spider`。这些插件包不包含宿主所需的基类，
 * 因此 Reelbox 提供同名兼容类。
 *
 * 不提供的话，加载任意 spider 类时都会：
 * ```
 * NoClassDefFoundError: com/github/catvod/crawler/Spider
 * ```
 *
 * ## 为什么用 Kotlin 写
 *
 * Kotlin 的类默认是 final，jar 里的子类继承会失败。
 * 所以这个类必须显式 `open`，且所有方法都要 `open`。
 *
 * ## 签名依据
 *
 * 兼容层需要暴露的签名取决于目标插件的符号引用。维护时应检查对应 JAR 的 DEX 调用点，
 * 并用实际插件验证链接结果；这里不声明接口实现的历史源码来源：
 * ```
 * init(Context, String)               // 初始化，ext 是站点配置里的 ext 字段
 * homeContent(boolean)                // 首页
 * homeVideoContent()                  // 首页视频推荐
 * categoryContent(String,String,boolean,HashMap)
 * detailContent(List<String>)
 * searchContent(String, boolean)
 * playerContent(String, String, List<String>)
 * liveContent(String)                 // 直播
 * manualVideoCheck()                  // 是否支持手动检测
 * isVideoFormat(String)               // 是否是视频地址
 * }
 * ```
 *
 * ## 实现原则
 *
 * 全部返回空实现（null / 空串 / false），**不做任何业务逻辑**。
 * 真正的逻辑在 jar 的子类里，它们会 override 这些方法。
 * 基类只是让字节码链接成功。
 */
abstract class Spider {

    /**
     * 初始化。
     * @param context Android Context（jar 里通常只用它取 cacheDir、packageName）
     * @param ext 配置里的 ext 字段（可能是一段 JSON）
     */
    open fun init(context: android.content.Context?, ext: String?) {}

    /** 首页分类 + 推荐 */
    open fun homeContent(filter: Boolean): String = ""

    /** 首页视频推荐（部分版本单独一个方法） */
    open fun homeVideoContent(): String = ""

    /** 分类内容 */
    open fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String?, String?>?,
    ): String = ""

    /** 详情 */
    open fun detailContent(ids: List<String>?): String = ""

    /** 搜索 */
    open fun searchContent(key: String?, quick: Boolean): String = ""

    /** 播放地址解析 */
    open fun playerContent(
        flag: String?,
        id: String?,
        vipFlags: List<String?>?,
    ): String = ""

    /** 直播源 */
    open fun liveContent(url: String?): String = ""

    /** 是否需要手动检测播放地址 */
    open fun manualVideoCheck(): Boolean = false

    /** 判断一个 URL 是否是视频格式 */
    open fun isVideoFormat(url: String?): Boolean = false

    /** 站点销毁时的清理 */
    open fun destroy() {}

    /**
     * 兼容层配套的静态工具方法集合。
     *
     * ## 为什么必须放在这里
     *
     * jar 里的字节码直接对 `com.github.catvod.crawler.Spider` 调用静态方法：
     * ```
     * NoSuchMethodError: No static method safeDns()Lokhttp3/Dns;
     *   in class Lcom/github/catvod/crawler/Spider;
     * ```
     * 只要签名差一点就抛 NoSuchMethodError（不是 NoClassDefFoundError，
     * 报错信息里会明确写出"or its super classes"）。
     *
     * ## safeDns 的作用
     *
     * 插件可能会调用此方法获取 DNS 实例。Reelbox 兼容层返回 OkHttp 系统 DNS，
     * 不实现 HTTP DNS 或自定义解析器。
     *
     * 这里不做复杂实现：直接返回 OkHttp 的默认 DNS（`Dns.SYSTEM`），
     * 语义上是"用系统解析"。在局域网/正常网络下这已经足够；
     * 遇到污染的域名本来也不该由 spider 层解决。
     */
    companion object {
        /**
         * 返回一个 OkHttp Dns 实例。
         *
         * 注意返回类型必须是 `okhttp3.Dns` —— jar 里的字节码
         * 按这个类型做 check-cast，返回别的类型会 ClassCastException。
         */
        @JvmStatic
        fun safeDns(): okhttp3.Dns = DefaultDns

        /** 默认实现：直接委托系统解析 */
        private val DefaultDns: okhttp3.Dns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }
}
