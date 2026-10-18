plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.feature.share"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        viewBinding = true
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
    api(project(":core:ui"))
    api(project(":core:share-server"))
    api(project(":core:network"))

    // 后端装配入口：面板要能解析"用户手输的路径"是否真实存在，
    // 就必须保证 local / smb / webdav 三个后端已经注册（Backends.install 是幂等的）。
    implementation(project(":core:fs-bootstrap"))
    implementation(project(":core:fs"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
}
