plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.miyuetech.mimifiles.core.shareserver"
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

    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    // ── HTTP 网页分享 ───────────────────────────────────────────
    // NanoHTTPD 是单文件内核（约 90 KB），纯 Java、无原生库，
    // 在 Android 上被大量应用验证过。选它而不是 Netty/OkHttp-MockWebServer：
    // 后两者为"高并发服务端"设计，而我们只需要"一个浏览器连着下载一个文件"。
    implementation(libs.nanohttpd)

    // ── 二维码 ─────────────────────────────────────────────────
    // 只引 `core`（无 android artifact）：负责把地址编码成 BitMatrix，
    // 真正的 Bitmap 由本模块自己画——省掉一个只做 `Bitmap` 适配的依赖。
    // 全程本地计算，不联网、不上传地址（方案 §10.4「二维码一致」）。
    implementation(libs.zxing.core)

    // ── FTP 服务端 ─────────────────────────────────────────────
    // ⚠️ 必须排除 spring-context：ftpserver-core 只在 `config.spring.*` 命名空间
    // 处理器里用到 Spring，而那条路径是"用 XML 描述服务端配置"的老式玩法，
    // 我们在代码里直接 new FtpServerFactory，永远走不到那里。
    // 不排除的话，Spring 会拖进 spring-core/beans/aop/expression 四棵大子树
    // （编译期若干 MB），而它们在 Android 上还有 java.beans 一类的缺类问题。
    implementation(libs.ftpserver.core) {
        exclude(group = "org.springframework")
    }

    // ftpserver 用 slf4j 打日志。不引绑定会打印一句 "Failed to load class
    // org.slf4j.impl.StaticLoggerBinder" 的告警，功能不受影响但会污染 logcat；
    // 这里引 NOP 绑定把日志彻底静音——FTP 服务端的排障走我们自己的 MimiLog。
    implementation(libs.slf4j.nop)

    // 相对路径规范化、根级过滤、端口探测、Basic 认证解析都是纯逻辑，
    // 用 JVM 单测穷举边界（越狱、NUL、反斜杠、超长）比在设备上试可靠得多。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
