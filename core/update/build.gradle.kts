plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.update"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

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

dependencies {
    // 日志（MimiLog 的环形缓冲是排障链路的一部分）与进程级作用域（AppScope）
    api(project(":core:common"))
    // 网络限并发调度器：更新检查走的是同一个"别把家庭宽带上行打满"的池子
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    // FileProvider：把下载好的 APK 以 content:// 授权给**系统安装器**读。
    // 直接给 file:// 在 Android 7 起会抛 FileUriExposedException，
    // 而且那等于把整个 cacheDir 暴露给调用方 —— FileProvider 能精确到"只有 update/ 这一个子目录"。
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // 清单是 JSON。本地 JVM 单测里 `org.json` 是 android.jar 的空壳（一调就抛
    // "not mocked"），所以测试源集必须带一份真实现。
    //
    // 为什么选 `org.json:json` 而不是 kotlinx.serialization：Android 平台自带的
    // `org.json` 就源自这个包（API 完全一致），换成 kotlinx 反而要在主源集里多带
    // 一个运行时 + 编译器插件 —— 为了解析一个 40 行的清单不值得。这里只在 test
    // 源集引入，不进 APK。
    testImplementation(libs.json)
}
