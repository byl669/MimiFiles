// ==========================================================================
// Baseline Profile 采集模块（M6 · 方案 §8.1.1 标为「必做」）
//
// ## 它是什么
// 一个 `com.android.test` 模块：**不是 APK**，而是一段跑在设备上的 instrumentation
// 测试。它按脚本操作目标应用（`:app`），同时由 ART 记录"哪些方法被真正执行过"，
// 最后把这份记录导出成 `baseline-prof.txt`。
//
// ## 它产出什么，怎么进到 APK 里
// `:baselineprofile:generateBaselineProfile` 会把结果写到
// `app/src/<variant>/generated/baselineProfiles/baseline-prof.txt`；
// AGP 随后把它编成 `assets/dexopt/baseline.prof` 打进 APK。
// 设备上安装时由 `androidx.profileinstaller`（M0 已接入运行期）把它交给 ART，
// 于是**首屏要用的那批方法在安装时就预编译好了**，冷启动不再从解释执行开始。
//
// ## 为什么它不占运行时体积
// 本模块的所有依赖（benchmark / uiautomator）都只在本机采集时用到，
// 不进 `:app` 的依赖图。真正随包下发的只有那份几百 KB 的规则文件。
//
// ## 为什么 minSdk 是 24 而不是跟着 app 走另一个值
// `benchmark-macro-junit4` 自身要求 `minSdk >= 24`；而 `:app` 的 minSdk 正好是 24。
// 两者必须一致，否则 `generateBaselineProfile` 会在设备校验阶段报"目标应用
// 的 minSdk 低于本模块"。
// ==========================================================================
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.miyuetech.mimifiles.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        // 采集是 instrumentation 测试，必须声明 runner。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 告诉插件"我要给谁采集"。`:app` 会据此被打成可 profile 的变体
    // （清单里自动加上 `<profileable android:shell="true"/>`）。
    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ⚠️ 这里**刻意不写** `androidComponents {}` 去"排除 release 变体"。
//
// 曾经想加一段"非 debug 变体不生成采集任务"的逻辑，但它要么是空 `if`、
// 要么就得手工去操作插件内部的变体列表 —— 前者是死代码（本项目的规矩是必删），
// 后者是在替插件做它自己的决定。采集哪个变体由插件按 `targetProjectPath`
// 推导，我们要管的是"事情有没有真的发生"（见 build.gradle.kts 顶部与
// docs 里的验证步骤），而不是在这里摆一个看起来在管的开关。

dependencies {
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
