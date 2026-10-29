import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Baseline Profile：**必须打在目标应用上**，`generateBaselineProfile` 任务
    // 是这个插件在 app 模块里创建的（它负责把 `:baselineprofile` 采到的规则
    // 合并进 `src/main/generated/baselineProfiles/`，再在打包时编成
    // `assets/dexopt/baseline.prof`）。只打在测试模块上时任务根本不存在 ——
    // M6 实测：`Cannot locate tasks that match ':baselineprofile:generateBaselineProfile'`。
    alias(libs.plugins.androidx.baselineprofile)
}

// ── 签名（凭据外置，绝不硬编码进构建脚本）────────────────
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.miyuetech.mimifiles"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miyuetech.mimifiles"
        minSdk = 24
        targetSdk = 36
        versionCode = (providers.gradleProperty("mimi.versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("mimi.versionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // 冒烟用的本机镜像（`adb reverse tcp:9999 tcp:9999` 之后，
            // 模拟器里的 127.0.0.1:9999 就是宿主机的 9999）。
            // 之所以不让 debug 也打生产地址：更新链路要验的是"下载→三重校验→安装"
            // 这一整段，而 debug 包的包名与签名都和生产包不同，拿生产清单里的
            // release 包去校验**必然**被签名那一关拒绝 —— 那是判据错了，不是代码错了。
            // 生产通道（HTTPS + 真清单）由 `--es update.url <生产地址>` 那条调试入口单独验。
            buildConfigField(
                "java.util.List<String>",
                "UPDATE_MANIFEST_URLS",
                "java.util.Arrays.asList(\"http://127.0.0.1:9999/manifest.json\")",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 更新清单地址：GitHub 仓库 + CDN 多镜像（M7.17 起，替代自建 NAS 通道）。
            // 清单是小文件，提交在仓库 update/manifest.json；APK 大文件不进仓库，
            // 挂 GitHub Release，清单里的 url 字段是 Release 资产的绝对地址。
            //
            // ## 为什么是镜像列表而不是单一地址（PiliPlus 项目实测教训）
            // raw.githubusercontent.com 内容最实时但国内直连常被墙；jsDelivr CDN
            // 可达性最好但对 @main 有小时级缓存、purge 也不保证生效。
            //
            // ## 为什么要列三个 jsDelivr 服务商（M7.22，2026-10-29 实测）
            // jsDelivr 的 `cdn.` / `fastly.` / `gcore.` / `testingcf.` 是**不同 CDN
            // 服务商、不同缓存命名空间**，同一次 purge 之后它们可能停在不同的代际：
            // 发布 0.7.23 之后同一分钟内实测 cdn=33、fastly=33、gcore=35、
            // testingcf=35。只列 cdn+fastly，国内用户（raw 被墙）就会全部拿到
            // 旧清单，被明确告知"已是最新"。多列两个服务商把"恰好都陈旧"的概率
            // 压下去，配合客户端取最大版本号的规则才算真的堵住。
            // 顺序按"常见可达性 + 实测新鲜度"排，客户端只在新版未找到时才继续问。
            buildConfigField(
                "java.util.List<String>",
                "UPDATE_MANIFEST_URLS",
                "java.util.Arrays.asList(" +
                    "\"https://raw.githubusercontent.com/byl669/MimiFiles/main/update/manifest.json\", " +
                    "\"https://cdn.jsdelivr.net/gh/byl669/MimiFiles@main/update/manifest.json\", " +
                    "\"https://gcore.jsdelivr.net/gh/byl669/MimiFiles@main/update/manifest.json\", " +
                    "\"https://testingcf.jsdelivr.net/gh/byl669/MimiFiles@main/update/manifest.json\", " +
                    "\"https://fastly.jsdelivr.net/gh/byl669/MimiFiles@main/update/manifest.json\")",
            )
        }
    }

    // ── ABI：只出 arm64-v8a 单包 ────────────────────────────────
    // 目标设备为 arm64 手机/平板（骁龙 865+ 等），不做 armv7 老盒子，
    // 故不再产出 armeabi-v7a 备包，也不保留 universal 包。
    //
    // 说明：这里用 splits.abi 显式锁定单 ABI 而非 abiFilters，
    //      产出物命名仍为 app-arm64-v8a-<buildType>.apk（可读性与脚本匹配）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // ── buildFeatures ───────────────────────────────────────────
    // ⚠️ 这里**只开 `buildConfig`，刻意不开 `viewBinding`**。
    //
    // 原因是一个会**在 release 上炸、debug 上不炸**的 AGP 行为：壳模块一旦开
    // ViewBinding，`dataBindingGenBaseClasses<Release>` 会把**依赖树里所有库的布局**
    // 也生成一遍 binding 类，落在**壳模块自己的包**下（这里是
    // `com.miyuetech.mimifiles.databinding.ActivityPreviewBinding`），而那些类 import 的是
    // **壳模块的 `R`**。本项目 `android.nonTransitiveRClass=true`，壳的 `R` 里当然没有
    // `layout/activity_preview`（它是 `:feature:preview` 的资源），于是
    // `:app:compileReleaseJavaWithJavac` 报：
    //
    //   错误: 找不到符号   View root = inflater.inflate(R.layout.activity_preview, …)
    //                    符号: 变量 layout   位置: 类 R
    //
    // 而 `assembleDebug` 全程绿灯——因为 debug 的生成物早在坏状态出现前就 UP-TO-DATE 了，
    // 只要没有任何东西让那个任务失效，就永远看不到它。**改一个库布局就会让它重新跑**
    // （本轮给 `activity_preview.xml` 加了一个控件就复现了），届时 release 直接构建不出来。
    // 这也是方案 §12「构建缓存把坏产物原样还原」那条在**代码层**的真正根因：
    // 坏状态一直在，缓存只是替它挡了很多次。
    //
    // 壳模块本来就不需要 ViewBinding：它没有自己的布局目录（`app/src/main/res/layout/`
    // 是空的），`SplashActivity` 用启动主题的 `windowBackground`、不加载任何布局，
    // `FileApp` 是 Application。所以正解不是"排缓存"，而是**把壳的 ViewBinding 关掉**，
    // 让 binding 只在真正拥有布局的模块里生成。
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**",
                "**/attach_hotspot_windows.dll",
                "kotlin/**",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Baseline Profile 规则的**来源**声明。没有这一行，`generateBaselineProfile`
    // 只会打一条 warning 然后**什么都不做**（M6 实测：BUILD SUCCESSFUL 但
    // `src/main/generated/` 根本没出现）—— 这个 `baselineProfile` 配置是
    // `androidx.baselineprofile` 插件在 app 上注册的专用依赖通道，
    // 它不进 APK 的依赖图，只告诉插件"采集代码在 :baselineprofile 模块里"。
    baselineProfile(project(":baselineprofile"))

    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:fs"))
    implementation(project(":core:fs-local"))
    // M2：网络存储后端。壳工程显式依赖它们，是为了让 R8 看到实现类、
    // 也为了让两种网络后端在 release 包里被真正打进 dex（注册是运行期反射式查找的，
    // 若只靠功能模块传递依赖，容易在裁剪时被当作"未使用的库"整块去掉）。
    implementation(project(":core:fs-smb"))
    implementation(project(":core:fs-webdav"))
    implementation(project(":core:network"))
    implementation(project(":core:datastore"))
    implementation(project(":core:shortcut"))
    // 应用内自更新（M5）。壳工程显式依赖，理由与其余功能模块相同：
    // 引擎是在 `FileApp.onCreate` 里按**类名**装配的，只靠传递依赖时
    // R8 很容易把"看起来没人引用"的那几个类裁掉。
    implementation(project(":core:update"))
    // M3：预览与播放。壳工程显式依赖，理由与网络后端相同——
    // 注册/路由是运行期按类名查找的，若只靠功能模块传递依赖，
    // R8 容易把"看起来没人用"的实现类整块裁掉。
    implementation(project(":core:preview"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:preview"))
    implementation(project(":feature:player"))
    implementation(project(":feature:connection"))
    implementation(project(":feature:settings"))
    // M4：无线分享。壳工程显式依赖，理由与其余功能模块相同——
    // 面板是靠 `FeatureRoutes` 里的**类名字符串**跳转过去的，
    // 若只靠传递依赖，R8 很可能把"看起来没人引用"的 Activity/Service 裁掉，
    // 表现是"点了没反应"。显式依赖 + 清单里的 android:name 一起，才留得住。
    implementation(project(":feature:share"))
    implementation(project(":provider"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
}
