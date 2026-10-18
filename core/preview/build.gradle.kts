plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.preview"
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

    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    // EXIF 方向 / 时间 / GPS：图片预览的硬需求。
    implementation(libs.androidx.exifinterface)

    // `SeekableSourceDataSource` 把 FileSystem 接到 Media3。
    // 用 `api` 是因为它**继承** `androidx.media3.datasource.DataSource`，
    // 该类型出现在公开签名上，消费方（:feature:player / :feature:preview）编译期需要它。
    api(libs.media3.datasource)

    // 纯逻辑单测：魔数嗅探 / 路由 / 编码判定 / 缓存键全部是同步的纯函数，
    // 只要 JUnit 就够（不需要 Robolectric，也不需要协程测试库——
    // 这一点是刻意设计的：把可测的部分从 Android 依赖里剥出来）。
    testImplementation(libs.junit)
}
