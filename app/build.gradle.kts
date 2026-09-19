plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（CI 双架构矩阵 / 模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

// 本机（Android 真机）没有 arm64 宿主版 NDK —— Google 官方只发 x86_64/darwin/windows 宿主，
// 所以真机上跑不了 CMake 原生构建。带 -PusePrebuiltPty 时改用 app/prebuilt/pty 里那份
// 预编译好的 libdshpty.so（取自官方 APK），跳过 CMake；CI 不带该参数，行为与原来完全一致。
val usePrebuiltPty = project.hasProperty("usePrebuiltPty")

android {
    namespace = "app.dsh.mobile"
    // CI 上 compileSdk=35；本地 SDK 只有 33/36/36.1 时可临时降/升到此值，
    // compileSdk 仅决定编译期 API 可见性，不影响运行时行为（targetSdk=28 才是生效阈值）。
    compileSdk = 36

    defaultConfig {
        // 【共存版】包名与官方版（app.dsh.mobile）错开 → 独立数据目录、独立权限授予、
        // 独立无障碍服务、独立 Shizuku authority（Manifest 用 ${applicationId} 占位符自动跟随）。
        // namespace 保持 app.dsh.mobile 不动：Kotlin 包名/R 类不变，避免大面积改 import。
        applicationId = "app.dsh.mobile.dev"
        minSdk = 26
        // 关键决策：targetSdk 28 —— sideload 分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve bionic 二进制（Termux 同款策略）。
        targetSdk = 28
        versionCode = 92
        versionName = "1.2.45"

        ndk {
            abiFilters += listOf(targetAbi)
        }
        if (!usePrebuiltPty) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c11"
                    arguments += "-DANDROID_STL=none"
                }
            }
        }
    }

    if (!usePrebuiltPty) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    // 只有显式开启预编译模式时才把 app/prebuilt/pty 注册为 jniLibs 来源；
    // 否则一个源目录都不注册，保证 CI（走 CMake）不会同时打进两份 libdshpty.so 而冲突。
    // 注意刻意不用默认的 src/main/jniLibs —— 那个目录 AGP 一定会扫，CI 上就会撞车。
    if (usePrebuiltPty) {
        sourceSets.getByName("main").jniLibs.srcDir("prebuilt/pty")
    }

    // CI 固定签名：workflow 会在仓库根预置 .ci/debug.keystore 这把固定密钥库，
    // 使每次云端构建的 APK 签名一致 → 可直接覆盖安装，Dev 版数据不丢。
    // 文件不存在时保持 AGP 默认行为（本地开发等环境完全不受影响）。
    // 注意：不依赖 AGP 隐式生成的 ~/.android/debug.keystore —— 那个位置在 CI 上
    // 不可控（实测每次构建都是新密钥），必须显式指定路径才稳。
    // 密钥库不入库：.gitignore 里的 *.keystore 已覆盖它。
    signingConfigs {
        getByName("debug") {
            val ciKeystore = rootProject.file(".ci/debug.keystore")
            if (ciKeystore.exists()) {
                storeFile = ciKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // targetSdk 28 会触发大量 lint 提示（前台服务类型、通知权限等），
        // 这些是刻意的兼容性决策，不阻断构建。
        abortOnError = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 扩展中心解包链路：Termux .deb 的 data.tar.xz 解码（纯 Java 实现，~110KB）
    implementation("org.tukaani:xz:1.10")
    // Shizuku 官方 API（m1.25）：bind 服务才能触发授权弹窗与真实 adb-shell 能力
    // aidl 提供 IShizukuService/IRemoteProcess（进程执行），api 提供授权与 binder 封装
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // 单元测试（JVM，不需要设备）：给纯逻辑（会话目录名解码等）上回归网
    testImplementation("junit:junit:4.13.2")
}
