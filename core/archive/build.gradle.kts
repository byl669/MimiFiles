plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.archive"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests {
            // 单测里会走到 `MimiLog`（内部用 `android.util.Log`）。
            // 不打这根开关时，任何一次日志调用都会抛
            // "Method d in android.util.Log not mocked"，让一个纯 JVM 的解析测试
            // 因为"记了条日志"而失败 —— 那是测试基建问题，不是被测代码的问题。
            isReturnDefaultValues = true
        }
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

    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    // ── 压缩包（M3 补强：用户 2026-10-14 要求"常见压缩格式的预览与解压"）──
    //
    // ## 为什么是 commons-compress 而不是 7-Zip 的 JNI 绑定
    // `net.sf.sevenzipjbinding` 覆盖面更广，但它要为每个 ABI 打原生库；
    // 而本工程只需要**读 + 解压**这几类最常见的格式，commons-compress 是纯 Java：
    //   · zip / tar / gz / bz2 / **7z**（`SevenZFile`，含 LZMA、LZMA2）—— 一个依赖全包
    //   · rar —— commons-compress 从 1.16 起移除了 RAR，另用 junrar（只读）
    // 代价是**不能写 7z/rar**（本工程只做"解压"，不提供"新建压缩包"，故不影响）。
    //
    // `commons-io` 是 commons-compress 的运行时必需项（`IOUtils` 等），显式声明
    // 而不是靠传递依赖：它在多个 API 上被直接使用，版本不能被别处悄悄顶掉。
    implementation(libs.commons.compress) {
        // ⚠️ zstd-jni 是**原生库**（自带 arm64 的 .so）。本工程不支持 .zst，
        // 而它会实打实地往包里塞一份原生代码，故排除。
        // 只要不使用 `CompressorStreamFactory`（它会静态引用 zstd/brotli 的类），
        // 相关类就是不可达的，R8 会直接删掉，运行时也不会去加载。
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation(libs.commons.io)
    // .xz / .lzma 的编解码器：commons-compress 只定义接口，实现由 tukaani 提供。
    implementation(libs.xz)
    // RAR 只读。junrar 7.x 要求 JDK 8+，与 minSdk 24 的 desugaring 兼容。
    implementation(libs.junrar)

    testImplementation(libs.junit)
    // `runBlocking` 来自 coroutines-core（已随 implementation 进主源集）；
    // 测试里要用它驱动 suspend 的解压流程，所以显式声明一次。
    testImplementation(libs.kotlinx.coroutines.core)
}
