plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 三种列表视图（列表 / 网格 / 详细信息）都用 ViewBinding，
    // 避免 5000 项的滚动路径上出现 findViewById。
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
    api(project(":core:model"))
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.google.material)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.recyclerview)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.fragment.ktx)
}
