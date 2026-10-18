plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.feature.settings"
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
    api(project(":core:datastore"))
    // 分享设置项要按 `ShareServer.type` 分组（FTP / HTTP 各一个端口），
    // 所以需要那个枚举。只用到类型，不碰服务端实现。
    implementation(project(":core:share-server"))
    // 软件更新：观察 `UpdateEngine.state` 并发起检查/下载/安装。
    implementation(project(":core:update"))
    implementation(libs.kotlinx.coroutines.android)
}
