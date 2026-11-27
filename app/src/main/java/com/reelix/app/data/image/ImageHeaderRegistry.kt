package com.reelix.app.data.image

/**
 * 图片地址 → 请求头 的注册表。
 *
 * ## 为什么需要
 *
 * TVBox 生态里，源返回的图片地址经常自带请求头后缀：
 * ```
 * https://img.x.com/a.jpg@Referer=https://site.com/@User-Agent=Mozilla/5.0
 * ```
 * 解析时必须把后缀剥掉，否则 Coil 会把整串当 URL，直接 404。
 * 但剥掉之后请求头也丢了，做了防盗链的图床就会返回 403/418。
 *
 * 播放地址那条链路是把 `headers` 直接传给播放器的；图片链路没有这个参数，
 * 所以用一个进程级的注册表承接：解析时存，图片拦截器按最终 URL 取。
 *
 * ## 为什么按 URL 而不是按条目
 *
 * 同一个 URL 可能被多个条目复用（同一部片在多个源里），按 URL 去重最省。
 * 拦截器拿到的就是 Coil 最终请求的 URL，不需要改任何加载点。
 *
 * ## 容量
 *
 * 一次搜索可能解析几百条，长期累积会一直涨。这里用有界 LRU：
 * 超过 [MAX_ENTRIES] 丢最久没用的。图片头都很短，几十 KB 量级。
 */
object ImageHeaderRegistry {

    private const val MAX_ENTRIES = 512

    private val cache = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 登记一个图片地址需要的请求头。
     *
     * 空 URL 或空头表直接忽略 —— 绝大多数图床不需要任何头，
     * 不要为了它们把表撑大。
     */
    @Synchronized
    fun put(url: String?, headers: Map<String, String>?) {
        val key = url?.trim().orEmpty()
        if (key.isEmpty() || headers.isNullOrEmpty()) return
        cache[key] = headers.toMap()
    }

    /** 取一个图片地址的请求头；没有登记返回空表。 */
    @Synchronized
    fun headersFor(url: String?): Map<String, String> {
        val key = url?.trim().orEmpty()
        if (key.isEmpty()) return emptyMap()
        return cache[key].orEmpty()
    }

    @Synchronized
    fun clear() = cache.clear()
}
