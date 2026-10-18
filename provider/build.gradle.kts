plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.provider"
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
    api(project(":core:model"))

    // 本模块是"系统拉起进程时也要能用"的入口，所以它自己负责装配后端，
    // 不能寄望浏览页先跑过。
    implementation(project(":core:fs-bootstrap"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)
}
