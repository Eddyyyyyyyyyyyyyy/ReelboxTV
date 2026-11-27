# ============================================================
# Reelix 混淆规则
#
# ★ 修改本文件前请先阅读 docs/R8-PROGUARD-GUIDELINES.md
# ★ 每次改完必须跑 docs/R8-TEST-CHECKLIST.md 的 10 项
#
# 规则原则：
#   1. 只保护「被外部按名字引用」的东西，不保护整个包
#   2. 每条规则写明保护对象 / 原因 / 代码位置
#   3. 第三方库自带 consumer rules 的，这里不重复
# ============================================================

# ------------------------------------------------------------
# 阶段一：只裁剪 + 优化，暂不混淆
#
# 为什么保留 -dontobfuscate：
#   项目有三处反射面同时存在 ——
#     ① DexClassLoader 加载外部 TVBox jar（Class.forName / getDeclaredMethod）
#     ② QuickJS 的 JNI（按 native 方法名查找）
#     ③ kotlinx.serialization（运行时反射获取 $$serializer）
#   第一次开 R8 时如果同时混淆，三类故障表现相似（都是 NoClassDefFoundError
#   / UnsatisfiedLinkError 一类），很难判断是哪一处没保护好。
#
#   先只做 shrink + optimize：代码会被删、会被优化，但名字不变，
#   崩溃堆栈仍然可读，能直接定位到行。
#
# 退出条件（满足全部才删除本行）：
#   - docs/R8-TEST-CHECKLIST.md 的 10 项连续两个版本全部通过
#   - 期间无 R8 相关崩溃
#   - 已建立 mapping.txt 归档机制
# ------------------------------------------------------------
-dontobfuscate

# 保留行号信息，否则崩溃堆栈只有 "Unknown Source"
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ------------------------------------------------------------
# ① TVBox Spider 接口（外部 jar 实现它）
# ------------------------------------------------------------
# 保护对象：com.github.catvod.crawler.Spider 及其全部成员
# 原因：DexClassLoader 加载的 jar 里的类 implements 这个接口。
#       接口方法被裁剪或改名 → jar 里的类找不到父类方法 →
#       NoClassDefFoundError / AbstractMethodError，表现为所有 jar 源失效。
# 代码位置：app/src/main/java/com/github/catvod/crawler/Spider.kt
# 引用方：SpiderBridge（Class.forName(CLASS_SPIDER)）
-keep class com.github.catvod.crawler.Spider { *; }

# 保护对象：com.github.catvod.crawler.SpiderDebug
# 原因：TVBox jar 内部按全限定名调用它输出调试日志，
#       被裁剪后 jar 里的日志调用会抛 NoClassDefFoundError
# 代码位置：app/src/main/java/com/github/catvod/crawler/SpiderDebug.kt
-keep class com.github.catvod.crawler.SpiderDebug { *; }

# ------------------------------------------------------------
# ①-b TVBox 外部 jar 直接使用的第三方库
# ------------------------------------------------------------
# 电视实测（2026-11-19，v0.3.3 / vc27，R8 阶段一）：
#   NoSuchMethodError: okhttp3.Request$Builder.url(String)
#   NoSuchMethodError: okhttp3.Dispatcher.setMaxRequests(int)
#   NoClassDefFoundError: com.google.gson.Gson
# 堆栈位于 com.github.catvod.spider.merge.*，即 DexClassLoader 加载的外部 jar。
# R8 只看到 APK 自身的调用图，看不到 jar 内部直接调用的公共 API，
# 因此把未使用的 OkHttp 方法、Okio 类和整个 Gson 裁掉了。
# 这些库是动态扩展边界的一部分，不是普通“应用未引用依赖”。
-keep class okhttp3.** { public *; }
-keep class okio.** { public *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**


# ------------------------------------------------------------
# ② JNI：所有 native 方法
# ------------------------------------------------------------
# 保护对象：项目中所有 native 方法（当前只有 QuickJs）
# 原因：JNI 由 C 侧按方法名查找（RegisterNatives 除外），
#       方法名被裁剪/改名 → UnsatisfiedLinkError
# 代码位置：app/src/main/java/com/reelix/app/data/source/js/QuickJs.kt
#          （System.loadLibrary("quickjs_jni")）
# 说明：这是通用写法，比单独 keep QuickJs 更稳，新增 JNI 时自动覆盖
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保护对象：com.reelix.app.data.source.js.QuickJs 全部静态回调
# 原因：quickjs_jni.c 的 nativeInit 通过 GetStaticMethodID 按名字查找
#       moduleSource / httpSync / pdfhRule / pdfaRule / hostLog。
#       R8 看不到 C 侧调用，2026-11-19 电视上搜索在线片源时裁掉
#       moduleSource/httpSync，GetStaticMethodID 返回空后直接 SIGABRT。
# 代码位置：app/src/main/cpp/quickjs_jni.c:432-440
-keep class com.reelix.app.data.source.js.QuickJs { *; }


# ------------------------------------------------------------
# ③ kotlinx.serialization
# ------------------------------------------------------------
# 保护对象：所有 @Serializable 类由编译器生成的 Xxx$$serializer
# 原因：运行时通过反射获取序列化器实例，
#       $$serializer 被裁剪 → 报 "Serializer for class X is not found"
# 代码位置：data/source/TvBoxConfig.kt、data/alist/AlistModels.kt、
#          data/source/online/OnlineCacheModels.kt 等 9 个文件
# 说明：库自身的 consumer rules 已覆盖 kotlinx.serialization.*，
#       这里只补「我们自己生成的 serializer」
-keep,includedescriptorclasses class com.reelix.**$$serializer { *; }
-keepclassmembers class com.reelix.** {
    *** Companion;
}
-keepclasseswithmembers class com.reelix.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 序列化依赖注解和泛型签名
-keepattributes *Annotation*, InnerClasses


# ------------------------------------------------------------
# ④ LibVLC（AAR 有基础 consumer rules，这里补 JNI 回调）
# ------------------------------------------------------------
# 保护对象：org.videolan.libvlc 全部
# 原因：VLC 的 JNI 层按类名/方法名回调 Java 侧（IMedia、MediaPlayer.EventListener 等），
#       这些接口被裁剪后播放器会静默失败
# 依赖：org.videolan.android:libvlc-all:3.7.0
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**


# ------------------------------------------------------------
# ⑤ 第三方库警告静默
# ------------------------------------------------------------
# OkHttp / Okio 的可选依赖（Conscrypt、BouncyCastle 等）在 Android 上不存在，
# 只是编译期引用，运行时不会走到 —— 静默即可，不需要 keep
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 的可选解码器/网络库按设备能力加载，未接入的会报 missing class
-dontwarn androidx.media3.**


# ------------------------------------------------------------
# 已删除的规则（2026-11-09）
# ------------------------------------------------------------
# -keep class com.reelix.app.data.model.** { *; }
#   删除原因：范围过大，会阻止 R8 对整个数据模型包做裁剪与优化，
#             等于开了 R8 却没收益。项目源码不使用 Gson 反射反序列化这些类，
#             序列化需求已由上面的 $$serializer 规则覆盖。
#   恢复条件：如果未来出现 Gson.fromJson(.., Model::class.java) 这类用法，
#             必须按「具体类 + 具体字段」重新加，不要恢复整包规则。
#
# -keep class com.github.catvod.** { *; }
#   删除原因：把 APK 自己写的 SpiderDebug、SourceProbe 等一并保护了，
#             而这些类只需要按需保护（上面 ① 已精确列出 Spider / SpiderDebug）。
#   注意：com.github.catvod.spider.Init 在**外部 jar 里**，不在 APK 中，
#         不需要也不应该为它写 keep 规则。
