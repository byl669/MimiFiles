# MimiFiles · 密密文件管理

一款面向 Android 的本地优先文件管理器：本机存储 + SMB/WebDAV 网络共享，
内置视频播放器与无线分享。单 APK（arm64-v8a），绿色无遥测。

> 应用内自更新。更新通道：**GitHub Releases（安装包）+ jsDelivr CDN（清单）**。

## 功能一览

- **文件浏览**：手机竖屏单栏 / 平板横屏左树右表（Windows 资源管理器形态），面包屑导航
- **网络文件系统**：SMB1/2/3、WebDAV（Basic/Digest、自签证书指纹钉扎），连接配置本地保存
- **视频播放**：基于 media3/ExoPlayer，多字幕轨道选择与自动优选（简体 > 繁体 > 其它中文）、
  外接显示器输出（Presentation 副屏渲染，竖屏视频居中）、手势进度/音量/亮度
- **多选操作**：ES 文件浏览器式顶栏（计数/全选）+ 底栏（复制/剪切/重命名/删除 + 更多）
- **收藏**：目录快速访问，侧栏直达
- **无线分享**：局域网内 HTTP/FTP 服务端，电脑直接访问手机文件
- **应用内自更新**：清单 + APK 双重 sha256 校验，R8 keep 出口闸门

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
