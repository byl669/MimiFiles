plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.miyuetech.mimifiles.core.datastore"
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
    api(libs.androidx.datastore.preferences)

    // M2 启用：Room 承载连接表（书签/回收站/历史在 M5 复用同一库）。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 凭据加密：**刻意不用 androidx.security:security-crypto**。
    // 理由：① 该库长期停在 1.1.0-alpha，alpha 依赖进生产分发不合适；
    //      ② 它会连带引入 tink + protobuf-lite，为"存 3 个字符串"付几百 KB 不划算；
    //      ③ 我们只需要 AES-GCM 加解密 + KeyStore 取钥，直接调 Android 平台 API
    //         就是 80 行代码，且版本完全可控（见 CredentialVault 的注释）。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
