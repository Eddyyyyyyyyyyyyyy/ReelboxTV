package com.reelix.app.data.source.jar

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Spider Jar 加载器 —— 把 TVBox 生态的 jar 变成可用组件。
 *
 * ## jar 的真实形态（这个是关键认知）
 *
 * 从 `饭太硬` 源下载到的 `spider` 文件伪装成 `.jpg`，扩展名不可信。
 * 实测解包后只有 5 个文件：
 * ```
 * classes.dex                    60KB   ← 引导层，只有加载器逻辑
 * assets/wexguard_v7.so          47KB   ← armeabi-v7a 解密库
 * assets/wexguard_v8.so          48KB   ← arm64-v8a 解密库
 * assets/wexshinidie.guard      868KB   ← 真实业务逻辑，加密的
 * META-INF/MANIFEST.MF
 * ```
 *
 * 也就是说：**真正的 spider 实现不在 classes.dex 里**，
 * 被 WexGuard 这套加固方案加密进了 `.guard` 文件，
 * 运行时由对应的 `.so` 解密后动态加载。
 *
 * 所以宿主不能自己解开 jar —— 必须让 jar 自己的代码去做。
 *
 * ## 加载流程
 *
 * ```
 * 1. 下载（可能是 jpg 扩展名，要按文件头校验）
 * 2. 校验 md5（配置里 `;md5;xxx` 那段）
 * 3. 拷到应用私有目录，扩展名改回 .jar
 * 4. DexClassLoader 加载 → 反射调 Init.init(context)
 * 5. 用 Init.getSpider(key) 拿具体 spider 实例
 * 6. 把 .so 解压到 nativeLibraryDir 可读位置（加固库要靠类加载器找）
 * ```
 *
 * ## 踩坑记录
 *
 * - **扩展名必须是 .jar 或 .dex**：DexClassLoader 按扩展名决定怎么读，
 *   直接传 `.jpg` 会抛 `FileNotFoundException`。
 * - **.so 必须能加载**：加固方案依赖 native 库解密，
 *   如果 .so 解不出来，`Init.classLoader()` 会返回 null，后面全是空指针。
 * - **md5 校验必须做**：这类源的 jar 地址会轮换，偶尔返回 CDN 错误页（HTML），
 *   不校验就会拿一个 HTML 去 DexClassLoader，报错信息完全误导。
 */
class SpiderJarLoader(private val context: Context) {

    /** 加载结果 */
    sealed interface Result {
        /** 加载成功，spiderClassLoader 可用于 getSpider */
        data class Success(val loader: ClassLoader, val jarPath: String) : Result
        data class Failure(val reason: String) : Result
    }

    /** 已加载的缓存，key = URL 的 md5 */
    private val loaded = HashMap<String, Result>()

    /**
     * 从一个 `spider` 字段值解析出 URL 和 md5。
     *
     * 格式：`http://xxx/spider.jpg;md5;abc123...`
     *       `http://xxx/spider.jar`（无校验）
     */
    fun parseSpiderSpec(spec: String): SpiderSpec? {
        if (spec.isBlank()) return null
        val parts = spec.split(";")
        val url = parts.firstOrNull()?.trim().orEmpty()
        if (url.isBlank()) return null
        val md5 = parts.getOrNull(2)?.trim()?.lowercase()?.takeIf { it.length == 32 }
        return SpiderSpec(url, md5)
    }

    data class SpiderSpec(val url: String, val md5: String?)

    /**
     * 加载 spider jar。
     *
     * @param spec 配置里的 spider 字段
     * @param fetch 下载函数（由调用方注入，便于复用 OkHttp 和统一超时）
     */
    fun load(
        spec: SpiderSpec,
        fetch: (String) -> ByteArray?,
    ): Result {
        val cacheKey = hashOf(spec.url)
        loaded[cacheKey]?.let { return it }

        // ★ 有本地副本时绝不先重下载：加固 jar 解密 guard 要十几秒，
        //   而用户已经有一份通过 md5 校验的文件。网络刷新交给后台。
        val jarDir = File(context.filesDir, "spider").apply { mkdirs() }
        val jarFile = File(jarDir, "spider_$cacheKey.jar")
        val local = localCopy(jarFile, spec.md5, cacheKey)
        if (local != null) return local

        val bytes = fetch(spec.url)
        if (bytes == null) {
            val f = Result.Failure("下载失败：${spec.url.take(60)}")
            loaded[cacheKey] = f
            return f
        }

        // ① 内容校验：不是 ZIP 说明拿到了错误页
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            val f = Result.Failure("下载内容不是 jar（可能是错误页），大小 ${bytes.size}")
            loaded[cacheKey] = f
            return f
        }

        // ② md5 校验
        val expectedMd5 = spec.md5
        if (expectedMd5 != null) {
            val actual = md5Of(bytes)
            if (!actual.equals(expectedMd5, ignoreCase = true)) {
                val f = Result.Failure(
                    "md5 不匹配，期望 ${expectedMd5.take(8)}… 实际 ${actual.take(8)}…"
                )
                loaded[cacheKey] = f
                return f
            }
        }

        // ③ 落到私有目录，扩展名必须是 .jar
        // 上次可能留下半截文件，而且可能已被设成只读 → 先删掉才能重写
        if (jarFile.exists() && jarFile.length() != bytes.size.toLong()) {
            jarFile.setWritable(true)
            jarFile.delete()
        }

        if (!jarFile.exists()) {
            try {
                jarFile.writeBytes(bytes)
            } catch (e: Exception) {
                val f = Result.Failure("写入失败：${e.message}")
                loaded[cacheKey] = f
                return f
            }
        }

        // ★★★ Android 14 的关键一步：写完立刻设成只读
        //
        // API 34 起 `DexClassLoader` 拒绝加载可写目录里的 dex：
        // ```
        // Writable dex file '/data/user/0/.../spider_xxx.jar' is not allowed.
        // ```
        // 官方要求"代码目录必须不可写"，所以 `setReadOnly()`（0444）就能通过检查。
        //
        // 早期版本这里是改用 `InMemoryDexClassLoader` 绕过的 —— 那样确实能加载 dex，
        // 但**丢掉了 jar 的物理路径**，加固壳的 DexNative 找不到 guard 文件，
        // 表现为 `Init.init` 抛 NullPointerException（InputStream 为 null）。
        // 设只读是正解，既保留路径又通过检查。
        if (jarFile.canWrite() && !jarFile.setReadOnly()) {
            Log.w(TAG, "setReadOnly 失败，DexClassLoader 可能报 Writable dex file")
        }

        // ④ 把 jar 内的 .so 解出来（加固壳依赖），但**不 load**
        val nativeDir = extractNativeLibs(jarFile)

        // ⑤ DexClassLoader —— 带 libDir，让加固壳自己找到 so
        val optimizedDir = File(context.codeCacheDir, "spider_$cacheKey").apply { mkdirs() }
        val loader: ClassLoader = try {
            dalvik.system.DexClassLoader(
                jarFile.absolutePath,
                optimizedDir.absolutePath,
                nativeDir?.absolutePath,
                context.classLoader,
            )
        } catch (e: Exception) {
            val f = Result.Failure("DexClassLoader 失败：${e.message}")
            loaded[cacheKey] = f
            return f
        }
        val success = Result.Success(loader, jarFile.absolutePath)
        loaded[cacheKey] = success
        return success
    }

    /**
     * Reuse a previously verified jar when its md5 still matches.
     *
     * No network is needed here; if the local file is corrupt, return null and
     * the caller falls back to downloading it again.
     */
    private fun localCopy(jarFile: File, expectedMd5: String?, cacheKey: String): Result? {
        if (!jarFile.exists() || jarFile.length() <= 0) return null
        if (expectedMd5 != null && !md5Of(jarFile.readBytes()).equals(expectedMd5, ignoreCase = true)) {
            jarFile.setWritable(true)
            jarFile.delete()
            return null
        }
        return runCatching {
            if (jarFile.canWrite()) jarFile.setReadOnly()
            val nativeDir = extractNativeLibs(jarFile)
            val optimizedDir = File(context.codeCacheDir, "spider_$cacheKey").apply { mkdirs() }
            val loader = dalvik.system.DexClassLoader(
                jarFile.absolutePath,
                optimizedDir.absolutePath,
                nativeDir?.absolutePath,
                context.classLoader,
            )
            Result.Success(loader, jarFile.absolutePath).also {
                loaded[cacheKey] = it
                Log.i(TAG, "复用本地 spider jar: ${jarFile.name}")
            }
        }.getOrElse {
            Log.w(TAG, "本地 spider jar 不可用，改为重新下载: ${it.message}")
            null
        }
    }

    /** 从 jar 里抽出 classes.dex 的字节 */
    private fun extractDexBytes(jarFile: File): ByteArray? = runCatching {
        java.util.zip.ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry("classes.dex") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()

    /**
     * 把 jar 里的 `assets` 目录下的 .so 解压到私有目录，返回该目录。
     *
     * 为什么必须做：加固方案的解密逻辑在 `wexguard_v7/v8.so` 里，
     * `DexNative` 会从 `Init.context().getCacheDir()` 附近找这些库。
     * 不解压的话，`Init.classLoader()` 内部加载 so 失败，返回 null。
     */
    /**
     * 把 jar 里的加固库解压出来，**但不主动 load**。
     *
     * ## 为什么不能自己 System.load
     *
     * 实测这么写会**直接 native crash**（不是异常，try/catch 兜不住）：
     * ```
     * F DEBUG: backtrace ...
     *   at SpiderJarLoader.extractNativeLibs(SpiderJarLoader.kt:227)
     * ```
     * 原因是 WexGuard 的 so 有完整性校验，宿主进程直接 load 会触发
     * abort（多半是 JNI_OnLoad 里检测到非预期调用方）。
     *
     * ## 正确做法
     *
     * 只把 so 放到能读到的位置，让 jar 内部的 `DexNative` 自己通过
     * 它自己的加载逻辑去 load —— 那里面有完整的初始化流程。
     * 宿主插手反而会崩。
     *
     * 代价：如果 jar 内部的加载路径写死了别的目录，so 就加载不到，
     * 表现是加固型源全部失败。此时 JVM 侧无法补偿，
     * 只能依赖未加固的 jar（`lubin.php` 那类）。
     */
    private fun extractNativeLibs(jarFile: File): File? {
        val abi = if (Build.CPU_ABI.contains("64")) "v8" else "v7"
        val libName = "libwexguard_$abi.so"
        val outDir = File(context.filesDir, "spider_native").apply { mkdirs() }
        val outFile = File(outDir, libName)

        if (outFile.exists() && outFile.length() > 0) return outDir

        return runCatching {
            java.util.zip.ZipFile(jarFile).use { zip ->
                val entry = zip.getEntry("assets/wexguard_$abi.so") ?: return null
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Log.d(TAG, "加固库已解出（不主动 load）: $libName")
            outDir
        }.getOrElse {
            Log.w(TAG, "解压加固库失败: ${it.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SpiderJarLoader"
    }

    private fun hashOf(s: String): String =
        md5Of(s.toByteArray()).take(12)

    private fun md5Of(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
