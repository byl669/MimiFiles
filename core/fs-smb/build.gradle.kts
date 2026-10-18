plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.fssmb"
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
    api(project(":core:network"))

    // M2：smbj（SMB2/3 主选）+ jcifs-ng（SMB1 兼容老 NAS/盒子）
    implementation(libs.smbj)
    implementation(libs.jcifs.ng)

    // 密码/凭据由 :core:datastore 解密后当场交给本模块建立会话，
    // 但本模块持有 ConnectionProfile 类型，需看到 model。
    implementation(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
