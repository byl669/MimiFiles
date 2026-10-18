pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ── JitPack：仅放行 sardine-android（WebDAV 客户端）─────────
        // 用 includeGroup 精确收敛，避免 JitPack 成为通用依赖来源（供给链最小面）。
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.thegrizzlylabs")
            }
        }
    }
}

rootProject.name = "MimiFiles"

include(":app")

// ── 核心层 ──────────────────────────────────────────────
include(":core:common")        // 工具、扩展、Result/异常体系、日志
include(":core:model")         // FsEntry / FsPath / ConnectionProfile / Capabilities
include(":core:fs")            // ★ 文件系统抽象层（FileSystem 接口 + 注册表 + 异常）
include(":core:fs-local")      // 本机存储（File + SAF 降级）
include(":core:fs-smb")        // SMB（smbj + jcifs-ng）
include(":core:fs-webdav")     // WebDAV 客户端（sardine-android）
include(":core:fs-bootstrap")  // ★ 后端装配入口（把 4 个后端一次性注册进注册表）
include(":core:shortcut")      // ★ 桌面快捷方式（文件夹 → 桌面图标，pin + legacy 双路）
include(":core:network")       // 连通性、网卡枚举、NSD/mDNS
include(":core:share-server")  // 分享服务端（NanoHTTPD 内核 + FtpServer 内核 + FsBridge）
include(":core:preview")
include(":core:archive")       // 类型探测、缩略图、编码探测、PDF/文本渲染器
include(":core:datastore")     // Room / DataStore / Keystore
include(":core:ui")            // 主题、通用控件
include(":core:update")        // ★ 应用内自更新（清单/校验/下载/状态机）

// ── 功能层 ──────────────────────────────────────────────
include(":feature:browser")    // ★ 文件浏览主界面（竖屏单栏 / 横屏左树右表）
include(":feature:connection") // 连接管理（SMB / WebDAV）
include(":feature:preview")    // 图片/文本/PDF 预览
include(":feature:player")     // 视频/音乐播放
include(":feature:share")      // ★ 无线分享面板
include(":feature:settings")   // 设置

// ── 系统集成 ────────────────────────────────────────────
include(":provider")           // ★ SAF DocumentsProvider（把 SMB 暴露给系统）

// ── 构建期工具（不进 APK）────────────────────────────────
// Baseline Profile 的采集模块。它是 `com.android.test`，只在本机跑
// `:baselineprofile:generateBaselineProfile` 时存在；产出的规则文件被 AGP
// 编进 `:app` 的 `assets/dexopt/baseline.prof`，**它自己不随包下发**。
include(":baselineprofile")
