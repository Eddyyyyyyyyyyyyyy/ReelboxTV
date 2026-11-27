package com.reelix.app.data.image

import android.content.Context

/**
 * 内置图片资源查找器。
 *
 * 背景：国内电视上 TMDB 图被 DNS 污染，演示数据必须用打包图。
 * Coil 对 `file:///android_asset/` 实测加载失败，所以把图放
 * `res/drawable-nodpi`，运行时按资源名查 ID。
 *
 * 资源命名约定（见 assets 迁移脚本）：
 *   p_<key>  竖版海报
 *   b_<key>  横版大图
 *
 * 查找是懒缓存：第一次查完存进 Map，避免反复反射。
 */
object LocalImageStore {

    private val cache = HashMap<String, Int>(64)

    /**
     * 按资源名查 drawable 的 ID。
     * @param name 不含扩展名，如 "p_dune2" / "b_dune2"
     * @return 资源 ID，找不到返回 0
     */
    fun id(context: Context, name: String?): Int {
        if (name.isNullOrBlank()) return 0
        cache[name]?.let { return it }
        val res = context.resources
        val id = res.getIdentifier(name, "drawable", context.packageName)
        cache[name] = id
        return id
    }

    /** 拼一个可直接给 Coil 的 android.resource:// URI，找不到返回 null */
    fun uri(context: Context, name: String?): String? {
        val id = id(context, name)
        return if (id != 0) {
            "android.resource://${context.packageName}/drawable/$name"
        } else null
    }

    /** 是否有这张内置图 */
    fun has(context: Context, name: String?): Boolean = id(context, name) != 0
}
