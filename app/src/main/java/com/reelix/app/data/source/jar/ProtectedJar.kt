package com.reelix.app.data.source.jar

import android.app.Application
import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 加固 spider jar 的初始化 —— **这是整个 jar 容器能跑起来的关键**。
 *
 * ## 为什么不能调 `Init.init(Context)`
 *
 * `com.github.catvod.spider.Init.init(Context)` 里带**包名白名单校验**：
 * 宿主包名不在 TVBox 的白名单里就直接 `Process.killProcess()`。
 * 而且它还会：
 * ```
 * this.app = (Application) ctx;                          // ①
 * this.loader = (DexClassLoader) DexNative.getLoader(ctx);  // ②
 * ```
 * 第 ② 步在非 TVBox 宿主里返回 null → NPE。
 *
 * 实测报错：
 * ```
 * Init.init 失败: null
 *   at com.github.catvod.spider.Init.init(Unknown Source:13)
 * Caused by: NullPointerException:
 *   Attempt to invoke virtual method 'int java.io.InputStream.read(byte[])' on a null object reference
 * ```
 *
 * ## 正确做法：反射绑定字段
 *
 * TVBox 对非白名单宿主自身就是这么干的（`ProtectedInitJar`，445 行）。
 * 逻辑是：**不调 init，直接把该塞的字段塞进去**。
 *
 * ```
 * 1. Init.get() 拿单例
 * 2. 遍历 Init 实例上所有 Context 类型的字段 → 塞 Application
 * 3. DexNative.getLoader(app) 拿 DexClassLoader
 * 4. 遍历 Init（含父类）所有 DexClassLoader 类型字段 → 塞 loader
 * ```
 *
 * ## 一个重要的性能事实
 *
 * `DexNative.getLoader()` 要 **14 秒左右**（在解密那个 888KB 的 guard 文件）。
 * 所以每个 jar **只能做一次**，重复跑就是"点一次卡半天"。
 */
object ProtectedJar {

    private const val TAG = "ProtectedJar"

    /**
     * 绑定 jar 的 Init 单例。
     *
     * @param cls jar 里 `com.github.catvod.spider.Init` 的 Class
     * @param app 宿主 Application（必须是 Application，不能是 Activity）
     * @return loader 是否绑定成功（决定后续 getSpider 能否工作）
     */
    fun init(cls: Class<*>, app: Application): Boolean {
        val instance = runCatching { cls.getMethod("get").invoke(null) }.getOrNull()

        if (instance == null) {
            Log.w(TAG, "Init.get() 拿不到实例，按普通 jar 处理")
            return false
        }

        bindContext(cls, instance, app)
        val bound = bindDexLoader(cls, instance, app)

        // 可选钩子，失败不影响主流程（不同版本 jar 方法名不同）
        runCatching { cls.getMethod("replaceCloudDiskNames").invoke(null) }
            .onFailure { Log.d(TAG, "replaceCloudDiskNames 不存在（正常）") }

        runCatching { cls.getMethod("startGoProxy", Context::class.java).invoke(null, app) }
            .onFailure { Log.d(TAG, "startGoProxy 不存在（正常）") }

        Log.i(TAG, "加固 jar 绑定完成: context=OK loader=$bound")
        return bound
    }

    /**
     * 把 Application 塞进 Init 实例上所有 **Context 类型**的实例字段。
     *
     * 官方实现是先试固定字段名（`c`），失败再遍历类型。
     * 这里直接遍历类型 —— 更稳，不受混淆命名影响。
     */
    private fun bindContext(cls: Class<*>, instance: Any, app: Application) {
        var hit = 0

        for (field in cls.declaredFields) {
            try {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!Context::class.java.isAssignableFrom(field.type)) continue

                field.isAccessible = true
                field.set(instance, app)
                hit++
            } catch (t: Throwable) {
                Log.w(TAG, "bindContext 失败: ${field.name}", t)
            }
        }

        Log.i(TAG, "bindContext: 绑定 $hit 个字段")
    }

    /**
     * 调 `DexNative.getLoader(app)` 拿 DexClassLoader，
     * 再塞进 Init（含父类）所有 DexClassLoader 类型的实例字段。
     */
    private fun bindDexLoader(cls: Class<*>, instance: Any, app: Application): Boolean {
        val loader = try {
            val dexNative = cls.classLoader.loadClass("com.github.catvod.spider.DexNative")
            val getLoader = dexNative.getMethod("getLoader", Any::class.java)
            getLoader.invoke(null, app)
        } catch (t: Throwable) {
            Log.e(TAG, "DexNative.getLoader 失败: ${t.cause?.message ?: t.message}", t)
            return false
        }

        if (loader !is DexClassLoader) {
            Log.e(TAG, "getLoader 返回的不是 DexClassLoader: ${loader?.javaClass?.name}")
            return false
        }

        var hit = 0
        var type: Class<*>? = cls

        while (type != null) {
            for (field: Field in type.declaredFields) {
                try {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (!DexClassLoader::class.java.isAssignableFrom(field.type)) continue

                    field.isAccessible = true
                    field.set(instance, loader)
                    hit++
                } catch (t: Throwable) {
                    Log.w(TAG, "bindDexLoader 失败: ${field.name}", t)
                }
            }

            type = type.superclass
        }

        Log.i(TAG, "bindDexLoader: 绑定 $hit 个字段")
        return hit > 0
    }
}
