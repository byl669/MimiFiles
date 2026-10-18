plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.feature.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:ui"))
    api(project(":core:preview"))

    // 后端装配入口：预览/播放**必须自己保证** 后端已注册，
    // 因为系统可以把这两个页面直接拉起（deep link / 测试 / 通知回跳），
    // 那时浏览页一次都没跑过，注册表是空的。Backends.install 幂等。
    implementation(project(":core:fs-bootstrap"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:fs"))
    implementation(project(":core:datastore"))

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    // FFmpeg 音频软解（AC3/EAC3/DTS/TrueHD……）：安卓不自带这些杜比/DTS 编码的
    // 解码器，不补包就无声。硬解优先，硬解不支持的编码才回落到 FFmpeg 软解。
    implementation(libs.nextlib.media3ext)

    testImplementation(libs.junit)
}
