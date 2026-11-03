# MimiFiles · 密密文件管理

轻量级 Android 文件管理器：**单 APK 约 14 MB**，本机存储 + SMB/WebDAV 网络共享，
内置视频播放器与无线分享。绿色无广告、零遥测，支持应用内自更新。

**当前版本：`0.7.26-M7.24`（versionCode 38）** · Kotlin 2.1 · AGP 8.13.2 · Gradle 8.14.4

## 为什么选它

- **🪶 轻量** —— 单 APK（arm64-v8a）约 14 MB，无广告、无推送、无统计 SDK，装上即用
- **🚀 SMB 传输深度优化** —— 对 SMB2/3 读写作了缓冲与并发调优，局域网大文件拷贝
  速度明显快于同类工具；WebDAV（Basic/Digest、自签证书指纹钉扎）同样支持
- **🥽 AR 眼镜 / 外接显示器直连播放** —— 视频画面直接输出到外置显示器
  （XREAL / Rokid 等 DP 头显、电视、投影），手机屏幕只留控制界面；
  竖屏视频居中黑边、字幕同步上屏、播放中插拔自动切换与自愈
- **🔒 隐私优先** —— 全部数据留在本机与你的 NAS；更新检查是一次无参数、
  无设备标识的静态文件 GET，安装包 sha256 校验后才进安装流程
- **🖥 大屏形态** —— 平板横屏左树右表（Windows 资源管理器形态），
  并通过 DocumentsProvider 接入系统文件选择器
- **🎬 播放器细节** —— 多字幕轨道选择（自动优选简体 > 繁体 > 其它中文）、
  手势进度/音量/亮度、审片时**就地删片**（删除后按顺序接着播下一个）
- **📡 无线分享** —— 局域网内 HTTP/FTP 服务端，电脑直接访问手机文件
- **♻️ 应用内自更新** —— GitHub Releases（安装包）+ Release 资产清单双通道，
  清单多镜像回退，客户端取所有镜像中最大的 versionCode

## 快速开始

### 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | `jvmTarget = 17`，AGP 8.13 强制要求 |
| Android SDK | Platform 36 / Build-Tools 36 | `compileSdk = 36`，`minSdk = 24`，`targetSdk = 36` |
| Gradle | 8.14.4 | 由 `gradle/wrapper` 自带，无需本机安装 |
| AGP | 8.13.2 | 见 `gradle/libs.versions.toml` |
| 设备 | arm64-v8a | APK 用 `splits.abi` 只出 arm64，x86 模拟器装不上 |

`local.properties` 里的 `sdk.dir` 指向本机 SDK（**不入库**，换机器自己写一行）。

### 构建与安装

```bash
./gradlew :app:assembleRelease        # 产物 app/build/outputs/apk/release/app-arm64-v8a-release.apk
./gradlew :app:assembleDebug          # 调试包（版本名带 -debug 后缀）
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

- 签名凭据外置：`keystore.properties` + `keystore/*.jks`（均不入库）。
  **没有这两个文件时 release 包自动退化为 debug 签名，仍可构建** —— 换机器接手时
  先用 debug 签名验证功能，拿到正式密钥后再出发布包。
- 单测：`./gradlew test`（覆盖路径规范化、SMB 地址解析、更新清单解析、
  删除后下一首/下一个的进位、目录变更标记等纯逻辑；不依赖设备）。
  当前基线 **313 项全绿**，`./gradlew test :app:assembleRelease` 约 2 分钟跑完。
- 发布闸门：`./tools/check-r8-keeps.sh`（见下文「R8 keep 闸门」）。

### 冒烟测试

`scripts/` 下是按里程碑积累的端到端脚本，需要连一台设备（模拟器或真机）：

```bash
./scripts/smoke.sh              # 基础冒烟（安装、启动、浏览）
./scripts/m76-player-ux-smoke.sh  # 播放器交互（含删除、字幕、旋转）
```

脚本用 `adb` 驱动，部分会起本地 SMB/WebDAV 服务（`scripts/m51-smb-server.py`、
`scripts/m2-dav-server.py`），跑之前先看脚本头部的说明。

## 模块结构

Gradle 多模块，**依赖方向单一**：`app → feature → core`，`feature` 之间互不依赖
（跨模块跳转走 `core:ui` 的 `FeatureRoutes` 字符串路由）。

### 核心层 `core/`

| 模块 | 职责 |
|---|---|
| `common` | 工具、扩展、Result/异常体系、日志（`MimiLog`）、`MimeTypes` |
| `model` | `FsEntry` / `FsPath`（跨存储统一路径，**M1 冻结契约**）/ 连接配置 / 能力位 |
| `fs` | 文件系统抽象层：`FileSystem` 接口 + 注册表 + 异常体系 + `FsMutations` |
| `fs-local` | 本机存储（File 直读，含 SAF 降级、`StorageAccessManager`） |
| `fs-smb` | SMB1/2/3（smbj + jcifs-ng 双栈） |
| `fs-webdav` | WebDAV（sardine-android，Basic/Digest，自签证书指纹钉扎） |
| `fs-bootstrap` | 后端装配入口：把 4 个后端一次性注册进注册表（幂等） |
| `shortcut` | 桌面快捷方式（文件夹 → 图标，pin + legacy 双路） |
| `network` | 连通性探测、网卡枚举、NSD/mDNS 发现 |
| `share-server` | 无线分享服务端（NanoHTTPD + FtpServer + `FsBridge`） |
| `preview` | 类型探测（魔数 + 扩展名）、缩略图、EXIF、预览参数路由、外部打开 |
| `archive` | 压缩包读取（zip/tar/gz/bz2/xz/7z，rar 只读） |
| `datastore` | Room / DataStore / Keystore（连接配置、播放进度、设置） |
| `ui` | 主题、通用控件、`FileListAdapter`、`FeatureRoutes` |
| `update` | 应用内自更新（清单解析 / 多镜像 / sha256 校验 / 下载状态机） |

### 界面层 `feature/`

| 模块 | 职责 |
|---|---|
| `browser` | 文件浏览主界面（竖屏单栏 / 横屏左树右表），多选、复制剪切、面包屑 |
| `connection` | 连接管理（SMB / WebDAV 的增删改与测试） |
| `preview` | 图片 / 文本 / PDF / 音频预览的统一入口（视频转交 player） |
| `player` | 视频 / 音乐播放：手势、画中画、旋转兜底、外接显示器、就地删片 |
| `share` | 无线分享面板（HTTP / FTP 开关、二维码、地址展示） |
| `settings` | 设置 |

### 其它

| 模块 | 职责 |
|---|---|
| `app` | 壳工程：版本号注入、签名、更新清单地址、启动页 |
| `provider` | `DocumentsProvider`：把本机 / SMB / WebDAV 暴露给系统文件选择器 |
| `baselineprofile` | Baseline Profile 采集（`com.android.test` 模块，不随 APK 下发） |

## 关键设计决策

以下几点是踩过坑后定下来的，改动前先读对应源码里的注释：

1. **`FsPath` 是冻结契约**。`scheme://authority/path` 的文本形式同时用于桌面快捷方式
   extras、书签、最近访问、`DocumentsProvider` 的 documentId。改格式会破坏已发布数据的兼容性。
2. **能力位驱动 UI**。后端用 `FsCapability` 声明支持哪些操作，只读后端（无删除位的
   SAF 授权树）在点击时立刻提示"这个位置不支持删除"，而不是静默失败。
3. **跨模块跳转走字符串路由**（`FeatureRoutes`），不互相 import。好处是任何
   feature 模块都能单独裁剪；代价是目标类可能不存在，所以调用处必须判空。
4. **预览页会把视频转手给播放器后立即 `finish()`**。不这样的话，从播放页返回会看到
   一个只剩海报的空预览页。副作用：播放页的 `setResult` 到不了浏览页（预览页已销毁），
   所以"删完要刷新列表"走 `FsMutations` 进程内标记，不走 ActivityResult。
5. **删除面板不用 `AlertDialog` / `PopupWindow`**。前者飘到屏幕正中、离删除键远；
   后者是独立窗口，**不跟随 M7.10 的渲染旋转兜底**（整层 root 旋转 90°）。
   所以它是布局内的全屏 scrim + 卡片，位置按锚点 `getLocationInWindow` 现算。
6. **删除前必须先 `player.stop()` + `clearMediaItems()`**。SMB/WebDAV 服务端对
   被其他句柄打开着的文件常直接拒绝删除（共享冲突），表现是"本地盘能删、NAS 删不掉"。
7. **列表刷新不清空数据**。`BrowserViewModel.refresh()` 用 `clearFirst = false`：
   一清空，`DiffUtil` 看到的就是"全删 + 全加"，RecyclerView 滚动位置直接回顶端。
8. **删除后 `savePosition` 要跳过刚删的文件**（`deletedPath` 墓碑），否则已 forget
   的进度会被 `onStop` 原样写回去。

## R8 keep 闸门

release 构建开了 R8 全程序优化 + 资源裁剪，而 SMB / WebDAV / FTP 依赖大量
**反射与 `ServiceLoader`** 构造的对象（MBassador 事件总线、jcifs-ng 的 SMB1 客户端、
smbj 传输层、BouncyCastle 的 MD4 摘要外壳）。这些类一旦被裁掉，症状是
"连接时抛 `ClassNotFoundException`"或"认证永远失败"，且**在 debug 包里复现不了**。

```bash
./tools/check-r8-keeps.sh      # 末行必须是 R8KEEPS_OK 12/12
```

它从 release APK 的 dex 里逐项确认 12 个关键类是否还在包内。**动了 R8 规则、
升级 smbj / jcifs-ng / BouncyCastle 之后必须重跑**。

## 版本与发布

版本号只有**一个事实源**：`gradle.properties` 的 `mimi.versionCode` / `mimi.versionName`。

> ⚠️ 不要用 `./gradlew -Pmimi.versionCode=…` 临时覆盖出发布包。`-P` 只影响这一次构建，
> 而发布脚本生成清单时读的是 `gradle.properties`。两者不一致的后果是
> "APK 是新的、清单声明旧版"，客户端据此认为没有更新 —— 服务端 200、客户端正常、
> 就是没人升得上去，且没有任何报错。

```bash
# 1) 改 gradle.properties（versionCode +1，versionName 带里程碑后缀）
# 2) 构建 + 闸门
./gradlew :app:assembleRelease && ./tools/check-r8-keeps.sh
# 3) 归档
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk dist/mimifiles-<版本>-arm64.apk
# 4) 发布（提交清单 → 建 Release → 上传 APK 与清单 → purge CDN → 全链路自检）
./tools/publish-update-github.sh "<更新说明>" dist/mimifiles-<版本>-arm64.apk
```

发布脚本的硬闸门（任一不过就退出非 0）：

- `output-metadata.json` 里的真实版本号与 `gradle.properties` 一致
- 仓库内 `update/manifest.json` 经 GitHub API 直读（无缓存）已更新
- `releases/latest/download/manifest.json` 已指向新版本（客户端首选地址）
- Release 上的 APK 资产可达（HTTP 200）

末行 `PUBLISH_OK` 为成功；`PUBLISH_DEGRADED` 表示 jsDelivr 各 CDN 缓存尚未刷新
（对 `@main` 的分支解析能滞后 12 小时），**不影响更新**——客户端会取所有镜像里最大的
versionCode，陈旧节点不会误导。

## 更新通道

```
客户端 ──GET──> github.com/<repo>/releases/latest/download/manifest.json   首选，无缓存
       ├─GET──> raw.githubusercontent.com/...                             兜底（国内常被墙）
       ├─GET──> cdn / gcore / testingcf / fastly .jsdelivr.net            兜底（可滞后 12h）
       └─GET──> github.com/<repo>/releases/download/<tag>/xxx.apk ──302──> GitHub 对象 CDN
```

- 清单字段：`versionCode` / `versionName` / `notes` / `files[]`（abi、url、sha256、size）/ `minSupported`。
- 客户端对多个镜像的清单取**最大 versionCode**，下载后校验 sha256 才进安装流程；
  `minSupported` 支持服务端强制升级。
- 零遥测：更新检查是一次无参数、无设备标识的静态文件 GET。

## 开发约定

- **提交信息**：`M<里程碑>: <做了什么>` 或 `fix:` / `update:` 前缀，正文说明**为什么**这样改
  （踩过的坑写进正文，比写在代码注释里更容易被下一次改动看到）。
- **改代码前先读注释**。本仓库的注释密度偏高是刻意的：几乎每处非常规写法都记着
  "曾经怎么坏的"。删注释前先确认那个前提还成立。
- **每个里程碑结束跑一遍**：`./gradlew test` → `:app:assembleRelease` →
  `./tools/check-r8-keeps.sh`。三者都过才算可发布。
- **新增依赖前先看 `gradle/libs.versions.toml` 里的注释**。那里记着刻意**不引入**
  某些库的理由（例如 Coil、subsampling-scale-image-view），以及必须与 media3
  精确对齐的版本（nextlib）。

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 构建报 `SDK location not found` | 缺 `local.properties`，写一行 `sdk.dir=/path/to/Android/sdk` |
| release 包能装但 SMB 连不上、debug 包正常 | R8 裁掉了反射类，跑 `./tools/check-r8-keeps.sh` 并按提示补 keep 规则 |
| x86 模拟器装不上 APK | APK 只出 arm64-v8a，用 arm64 镜像或真机 |
| 装上新版但"检查更新"说已是最新 | 版本号没改在 `gradle.properties`，或清单没推上去；看发布脚本末行判据 |
| NAS 上文件删不掉 | 服务端持有其他句柄；播放页删除前会先 `stop()`，其它入口需自行释放 |
| 播放页持续渲染时 `uiautomator dump` 失败 | 持续渲染无法 idle，改用 `adbc exec-out screencap` 读像素判断 |

## 目录结构

```
app/                 壳工程（版本注入、签名、更新清单地址）
core/                纯逻辑与文件系统实现（见上方模块表）
feature/             界面模块（browser / player / preview / connection / share / settings）
provider/            DocumentsProvider（把本机/SMB/WebDAV 暴露给系统文件选择器）
baselineprofile/     Baseline Profile 采集模块
gradle/              libs.versions.toml（版本目录）+ wrapper
scripts/             端到端冒烟脚本（adb 驱动，含本地 SMB/DAV 测试服务）
tools/               发布脚本与 R8 keep 闸门
update/manifest.json 更新清单（发布脚本写入并提交）
docs/                里程碑交付报告（**不入库**，仅本机留存）
dist/                历史发布包归档（**不入库**，仅本机留存）
```

## 不入库的本地文件

| 文件 | 内容 | 换机器怎么办 |
|---|---|---|
| `local.properties` | `sdk.dir` | 自己新建一行 |
| `keystore.properties` | 签名密钥别名与口令 | 从密钥保管处取回，否则只能出 debug 签名包 |
| `keystore/*.jks` | 发布签名密钥 | 同上。**丢失后无法覆盖安装旧版本** |
| `tools/publish-local.env` | 自建 NAS 桥接通道凭据 | 不用该通道可不建 |
| `dist/` `docs/` | 发布包与交付报告 | 可选，不影响构建 |

## License

仅供学习交流使用。
