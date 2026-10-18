plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.fswebdav"
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
    api(project(":core:fs"))
    api(project(":core:network"))
    implementation(project(":core:model"))

    // M2：WebDAV 客户端，与参考应用同款 sardine-android。
    // 依赖树：sardine-android 0.9 → okhttp 4.12.0 + simple-xml 2.7.1。
    //
    // 供给链说明：sardine-android 只在 jitpack.io 发布（Maven Central 无该坐标），
    // 故 settings.gradle.kts 中已用 includeGroup("com.github.thegrizzlylabs") 精确放行，
    // 不让 JitPack 成为通用依赖来源。若日后不接受构建期外部依赖，把 0.9 的 aar 落到
    // 本模块 libs/ 后改为 implementation(files("libs/sardine-android-0.9.aar")) 即可离线复现。
    implementation(libs.sardine.android) {
        // simple-xml 自带 stax/xpp3 老坐标，与 Android 内置 xmlpull 冲突，沿用方案第 5.3 节的排除表。
        exclude(group = "stax", module = "stax-api")
        exclude(group = "xpp3", module = "xpp3")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
