plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.feature.connection"
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
    api(project(":core:model"))

    // M2：连接管理要能真的"测试连通"，所以直接依赖两个后端模块。
    // 这是本模块唯一被允许的"横向依赖"——它的职责本来就是"懂所有协议的表单"。
    implementation(project(":core:fs-smb"))
    implementation(project(":core:fs-webdav"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:common"))

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
