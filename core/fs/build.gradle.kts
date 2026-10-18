plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.fs"
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
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    // FsOps 的冲突策略、递归复制、自嵌套防护都是纯逻辑，用内存后端在 JVM 上跑穿。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
