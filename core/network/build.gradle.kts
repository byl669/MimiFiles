plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.network"
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
    api(project(":core:common"))
    // NetworkMonitor 需要 Context / ConnectivityManager；限并发调度器需要协程核心
    // （已由 :core:common 以 api 暴露，这里显式声明是为了本模块能独立编译）。
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    // PrivateNetworkProxy 的私网判定是纯 JVM 逻辑（只依赖 java.net），
    // 用普通单元测试穷举各种写法比"在模拟器上连一次试试"可靠得多——
    // 这条判断错了会让用户在公司网络下连不上家里的 NAS，而现象只是"连不上"。
    testImplementation(libs.junit)
}
