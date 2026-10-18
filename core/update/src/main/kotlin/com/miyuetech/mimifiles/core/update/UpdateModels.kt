package com.miyuetech.mimifiles.core.update

import java.io.File

/**
 * 更新清单里的一个安装包条目。
 *
 * @param abi 目标 ABI（`arm64-v8a` / `armeabi-v7a`）。本应用当前只出 arm64 单包，
 *        但字段保留 —— 清单结构是**跨应用共用**的同构约定，保持同构才能共用
 *        发布脚本与文档。
 * @param url 相对路径或**绝对地址**均可（相对清单自身地址解析；绝对地址原样用）。
 *        GitHub Release 通道里就是绝对地址（Release 资产没有稳定的目录前缀）。
 * @param sha256 小写十六进制，64 位。校验它是**防传输损坏**的第一道闸。
 * @param size 字节数。只在进度条算不准时兜底（`Content-Length` 缺失的场景），
 *        所以允许为 0（未知），不参与校验。
 */
data class UpdateApkFile(
    val abi: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

/**
 * 版本清单（`manifest.json`）。
 *
 * ## 字段为什么是这几个
 * 与相册 App（`moveimg-android`）共用同一套结构，服务端目录也共用同一种布局。
 * 这样"发布脚本、服务器说明文档、排障手册"三样东西在两个项目之间是同一份知识，
 * 不需要为第二个应用重新推一遍——而重新推一遍正是最容易出错的地方。
 *
 * @param versionCode 单调整数，**唯一参与"有没有新版"判断的字段**
 *        （`versionName` 只用于显示：写 `0.5.0-beta` 不该影响升级判定）。
 * @param minSupported 低于它的客户端**必须**更新。用于"老版本有安全问题 /
 *        与新服务端协议不兼容"这类必须强制的情况：UI 上不给"稍后"。
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val publishedAt: String,
    val notes: String,
    val minSupported: Int,
    val files: List<UpdateApkFile>,
)

/** 当前已安装应用的版本信息。 */
data class InstalledVersion(
    val versionCode: Long,
    val versionName: String,
)

/**
 * 更新链路的可读异常。
 *
 * ## 为什么不用 `IllegalStateException`
 * 这些失败的**受众是用户**（设置页上要显示出来），而不是开发者。用一个专门的类型，
 * 才能保证"每一条 throw 都带了一句能照做的话"，而不是半路被谁 `?: "错误"` 兜成空。
 */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 更新状态机。
 *
 * ## 为什么是这几个状态
 * 每个状态对应**界面上一个不同的东西可做**（按钮标签、可选动作都不一样）。
 * 一条经验：状态数量恰好等于"用户能采取的不同动作数"，多一个就会出现
 * "界面在这个状态下不知道该显示什么"的空白分支。
 */
sealed interface UpdateState {

    /** 还没检查过。设置页显示当前版本，按钮是「检查更新」。 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** 远端不比本地新。注意这只表示"清单里的 versionCode 不大于本地"。 */
    data class UpToDate(val remoteVersionName: String) : UpdateState

    /** 有新版本，尚未下载。 */
    data class Available(
        val manifest: UpdateManifest,
        val file: UpdateApkFile,
    ) : UpdateState

    data class Downloading(val percent: Int) : UpdateState

    /** 下载完成且**三重校验通过**，可以交给系统安装器。 */
    data class Ready(
        val apk: File,
        val manifest: UpdateManifest,
    ) : UpdateState

    /** Android 8+ 缺「安装未知应用」授权：先去系统设置开一次，回来再装。 */
    data class NeedInstallPermission(
        val apk: File,
        val manifest: UpdateManifest,
    ) : UpdateState

    data class Failed(val message: String) : UpdateState
}

/** 面板是否需要**强制**更新（`minSupported` 高于当前版本时不给「稍后」）。 */
fun UpdateState.isForced(installedCode: Long): Boolean = when (this) {
    is UpdateState.Available -> installedCode < manifest.minSupported
    is UpdateState.Ready -> installedCode < manifest.minSupported
    is UpdateState.NeedInstallPermission -> installedCode < manifest.minSupported
    else -> false
}

/** 面板上那条"要更新到哪一版"的信息；非更新态返回 null。 */
val UpdateState.targetManifest: UpdateManifest?
    get() = when (this) {
        is UpdateState.Available -> manifest
        is UpdateState.Ready -> manifest
        is UpdateState.NeedInstallPermission -> manifest
        is UpdateState.UpToDate -> null
        else -> null
    }
