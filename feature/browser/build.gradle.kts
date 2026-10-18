plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.feature.browser"
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
    api(project(":core:fs"))
    api(project(":core:fs-local"))
    api(project(":core:shortcut"))
    // 后端装配的唯一入口（本机 + SMB + WebDAV 一次装齐）
    implementation(project(":core:fs-bootstrap"))
    // M3：预览入参（PreviewArgs）与预览能力（类型探测 / 缩略图）
    implementation(project(":core:preview"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
