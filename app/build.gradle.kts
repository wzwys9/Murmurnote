plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "app.murmurnote.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.murmurnote.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // 没有正式签名环境变量时仍允许本地/CI 构建可安装的测试 APK。
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = false
    }

    // AGP / Compose lint 的部分 detector 跑到 Kotlin 2.0.21 K2 metadata 上会崩,
    // 是 lint 的工具 bug,跟代码无关。release 走 lintVitalAnalyzeRelease 强制跑 lint,只能在这里把这一项关掉。
    // 同源问题：RememberInCompositionDetector 在 K2 上抛 IncompatibleClassChangeError，错误消息里
    // 也明确建议 disable，等 AGP/Compose 配上跟 Kotlin 2.0.21 K2 一致的 lint API 再开回来。
    lint {
        disable += "NullSafeMutableLiveData"
        // RememberInCompositionDetectorKt 整个 helper 文件在 K2 metadata 上抛 IncompatibleClassChangeError，
        // 任何调到它的 detector 都会 hard-fail —— 已知至少这三个 Compose runtime 检测器都会撞同一处。
        // AGP/Compose 升到与 Kotlin 2.0.21 K2 一致的 lint API 之前都得绕开。
        disable += "RememberInComposition"
        disable += "FrequentlyChangingValue"
        disable += "AutoboxingStateCreation"
    }

    // onnxruntime / ffmpeg-kit / sherpa-onnx 的原生 .so 在四个 ABI 上合计超过 200 MB。
    // Debug 和 release 都按 ABI 拆 APK；单个设备只需要一套 ABI。Release 单 APK 约 48-68 MB，
    // debug 因未启用 R8 会额外带较大的 dex，但也不再生成 200+ MB 的 universal APK。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
        // sherpa-onnx 的几个 .so 自带的 ELF 调试段不去掉，stripDebugSymbols 已经会喊"Unable to strip"
        // 但仍然会打进包；这里显式声明它们不参与压缩，加快 APK 安装时的 dex 优化路径。
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // assets 里的模型文件不要 zip 压缩 ——
    // (a) int8 量化二进制几乎压不出来，反而拖慢 APK 安装
    // (b) sherpa-onnx 启动时要求"文件路径"，AsrModelManager.installBundledModelIfNeeded 会从
    //     assets 拷到 filesDir；assets 不压缩才能用 mmap 直接拷，速度快几倍
    // tokenizer 文本文件比较小，顺手不压，安装时拷到 filesDir 也更直接
    androidResources {
        ignoreAssetsPatterns += "qwen3_asr_0_6b"
        noCompress += listOf("onnx", "txt", "json")
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // 反射调用 sherpa-onnx Kotlin 数据类的命名参数构造（避免硬编码字段顺序，跨小版本更稳）
    implementation(libs.kotlin.reflect)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle / Navigation
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Glance (Widget)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // FFmpeg
    implementation(libs.ffmpeg.kit.audio)

    // Coil
    implementation(libs.coil.compose)

    // 本地 ASR：tar.bz2 解压（解 sherpa-onnx 模型包）。
    implementation(libs.commons.compress)

    // 本地 ASR 运行库当前以 Kotlin 绑定源码 + app/src/main/jniLibs/<abi>/*.so 形式提交。
    // 这里保留 AAR fileTree 入口，方便以后整体替换为官方 sherpa-onnx Android AAR。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    testImplementation(libs.junit)
}
