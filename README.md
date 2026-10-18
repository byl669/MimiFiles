# MimiFiles · 密密文件管理

轻量级 Android 文件管理器：**单 APK 约 13 MB**，本机存储 + SMB/WebDAV 网络共享，
内置视频播放器与无线分享。绿色无广告、零遥测，支持应用内自更新。

## 为什么选它

- **🪶 轻量** —— 单 APK（arm64-v8a）约 13 MB，无广告、无推送、无统计 SDK，装上即用
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
  手势进度/音量/亮度
- **📡 无线分享** —— 局域网内 HTTP/FTP 服务端，电脑直接访问手机文件
- **♻️ 应用内自更新** —— GitHub Releases（安装包）+ jsDelivr CDN（清单）
  双层分发，清单多镜像回退（raw → jsDelivr → fastly）

## 其它功能

- **文件浏览**：手机竖屏单栏 / 平板横屏双栏，面包屑导航
- **多选操作**：ES 文件浏览器式顶栏（计数/全选）+ 底栏（复制/剪切/重命名/删除 + 更多）
- **收藏**：目录快速访问，侧栏直达
- 连接配置本地保存；SMB1/2/3 全兼容

## 构建

```bash
# 需要 JDK 17+ / Android SDK；版本号在 gradle.properties（单一事实源）
./gradlew :app:assembleRelease
# 产物: app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

- 签名凭据外置：`keystore.properties`（不入库）。没有该文件时 release 包自动退化为
  debug 签名，仍可构建。
- 单元测试：`./gradlew test`（更新链路的 URL 拼接、SMB 地址解析等核心纯函数有断言覆盖）。

## 更新通道架构

```
客户端 ──GET──> jsDelivr CDN ──回源──> GitHub 仓库 update/manifest.json   （小文件，全球 CDN）
       ──GET──> github.com/releases/download/<tag>/xxx.apk ──302──> GitHub 对象 CDN   （大文件）
```

- 清单字段：`versionCode` / `versionName` / `notes` / `files[]`（abi、url、sha256、size）。
- 客户端对下载包做 sha256 校验后才进入安装流程；`minSupported` 支持服务端强制升级。
- 发布：`tools/publish-update-github.sh "<说明>" <apk>` —— 校验版本一致性 →
  R8 keep 闸门 → 提交清单 → 建 Release 上传资产 → purge CDN → 全链路自检。
- 零遥测：更新检查是一次无参数、无设备标识的静态文件 GET。

## 目录结构

```
app/                 壳工程（版本、签名、更新清单地址）
core/                纯逻辑与文件系统实现（model / fs-smb / fs-webdav / update / ui / …）
feature/             界面模块（browser / player / connection / preview / share / …）
provider/            DocumentsProvider（把本机/SMB/WebDAV 暴露给系统文件选择器）
scripts/             冒烟测试脚本（模拟器端到端）
tools/               发布脚本与 R8 keep 闸门
```

## License

仅供学习交流使用。
