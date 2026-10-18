plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.feature.preview"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // ⚠️ 库模块的 targetSdk 只影响 test APK，但必须设：缺省回落到 minSdk=24 时，
        // 系统会给 test APK 弹「DeprecatedTargetSdkVersionDialog」并抢走窗口焦点，
        // 双指捏合注入（androidTest）全部失焦失败。
        targetSdk = 36
        // 双指捏合没法用 `adb input` 模拟（它只支持单指）。这是全工程**唯一**
        // 能真实回放"两根手指"事件流的手段：instrumentation 注入合成多点触控。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":core:archive"))
    implementation(project(":core:model"))
    implementation(project(":core:fs"))

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 音频预览复用播放器内核（Media3），视频播放器在 :feature:player 里。
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)

    // ZoomTransform 的"捏合焦点保持不动"这类性质**没法用 adb 验证**
    // （`input` 只支持单指，模拟不了双指捏合），只能靠纯函数单测钉死。
    // 它是纯数学、不碰任何 View，所以在 JVM 上跑得又快又稳。
    testImplementation(libs.junit)

    // 双指捏合的端到端验证（androidTest）：注入合成 MotionEvent 序列。
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
