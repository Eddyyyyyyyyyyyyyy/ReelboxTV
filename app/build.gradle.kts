plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 发行开关：默认按发行配置构建（隐藏海报与资料设置、隐藏图源分类与来源角标）。
// 本地需要完整设置界面时用 -PpublicBuild=false。
val publicBuild = (findProperty("publicBuild") as String?)?.toBoolean() ?: true

android {
    namespace = "com.reelix.app"
    compileSdk = 35

    // 只有 QuickJS 这个原生库需要 NDK，其余全是纯 Kotlin/Java
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.reelix.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 94
        versionName = "0.3.70"
        buildConfigField("boolean", "PUBLIC_BUILD", publicBuild.toString())
        vectorDrawables { useSupportLibrary = true }

        // 只保留 ARM 架构。
        //
        // QuickJS 原生库要给每个 ABI 各编一份（arm64 约 1MB）。
        // 电视和机顶盒**全是 ARM**，x86/x86_64 只在模拟器上用，
        // 打进去纯浪费 —— 实测这两个能省约 2.2MB。
        // 模拟器调试使用 -PemulatorAbi=true，默认安装包仍仅包含 ARM。
        ndk {
            abiFilters += if (providers.gradleProperty("emulatorAbi").orNull == "true")
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            else listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 2026-11-09：按 docs/R8-PROGUARD-GUIDELINES.md 第一、六条启用 R8。
            //
            // 当前处于「阶段一」：shrink + optimize 开启，混淆暂关
            // （proguard-rules.pro 里的 -dontobfuscate）。目的是先让代码裁剪
            // 和资源压缩跑通，崩溃堆栈仍可读；等 docs/R8-TEST-CHECKLIST.md
            // 的 10 项连续两版全过，再摘掉 -dontobfuscate。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ★ 用 debug keystore 签 release（2026-11-06）。
            //
            // 目的只有一个：能覆盖安装到已装 debug 包的电视上，从而做
            // "debug vs release" 的冷启动对比。否则 release 是未签名包，adb
            // 直接拒装。
            //
            // 这不是发布签名，别拿它上架；真要发版再换成正式 keystore。
            // 签名相同 = 数据保留，不会清掉播放历史。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // 纯 JVM 单测（PC 上跑，不需要电视/模拟器）。
            // 但 AGP 默认把 android.jar 的每个方法都替换成抛异常，
            // 而 Parcelize / DefaultConstructorMarker 之类的引导类会顺手撞上；
            // 就地返回默认值，让纯逻辑测试不被这些副作用打死。
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 2026-11-09 瘦身：以下依赖全项目零 import，已移除
    //   navigation-compose        → 页面跳转自己用 TopDest 状态机实现
    //   lifecycle-viewmodel-compose → 没有 viewModel() 调用
    //   tv-material               → 页面迁到原生 View 后不再使用
    //   media3-session            → 没有 MediaSession
    //   media3-datasource-okhttp  → PlayerFactory 用的是 DefaultHttpDataSource

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    // 海报墙原型：View 版 RecyclerView 比 Compose 网格少一整棵语义树。
    implementation(libs.androidx.recyclerview)
    // TV 专用网格：VerticalGridView 自带焦点对齐/滚动策略，比裸 RecyclerView 稳。
    implementation(libs.androidx.leanback)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(project(":media3-ffmpeg"))
    implementation("org.videolan.android:libvlc-all:3.7.0")

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    // TVBox 生态的 spider jar 大量依赖 Gson（TVBox 主程序自带）。
    // 缺了它，jar 内部的 Init$UP 线程会 NoClassDefFoundError 直接崩进程。
    implementation(libs.gson)
    // 二维码生成（夸克扫码登录）
    implementation(libs.zxing)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 纯 JVM 单测：在 Windows 上直接跑，不依赖设备
    testImplementation(libs.junit)
    // Android 的 org.json 在单测里是空桩；这里换成真实实现，
    // 让字段映射这类纯解析逻辑能被真正验证。
    testImplementation("org.json:json:20240303")
}

// Release APK 统一落到项目根目录 dist/，文件名带版本，避免每次手工复制。
val releaseVersionName = android.defaultConfig.versionName ?: "dev"
val releaseVersionCode = android.defaultConfig.versionCode ?: 0
tasks.register<Copy>("copyReleaseApkToDist") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "Reelbox-$releaseVersionName${if (publicBuild) "-public" else ""}-vc$releaseVersionCode.apk" }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApkToDist")
}
