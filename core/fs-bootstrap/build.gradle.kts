plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.bootstrap"
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
    // 本机存储的状态中心（权限档位 / SAF 授权表）是上层要用的东西，故 api 出去
    api(project(":core:fs-local"))

    // 连接仓库是 [Backends.connections] 的返回类型，属于公开 API 的一部分，故 api
    api(project(":core:datastore"))

    // 预览能力是 [Backends.previews] 的返回类型，同理 api
    api(project(":core:preview"))

    // 网络后端只用于装配，不暴露其类型给调用方——保持 implementation，
    // 这样"新增一种协议"不会变成一次贯穿所有调用方的签名变更。
    implementation(project(":core:fs-smb"))
    implementation(project(":core:fs-webdav"))
    implementation(project(":core:common"))
}
