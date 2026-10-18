package com.miyuetech.mimifiles.core.preview

import android.net.Uri
import com.miyuetech.mimifiles.core.model.FsPath

/**
 * 把 [FsPath] 塞进一个 `Uri`，供 Media3 / `MediaMetadataRetriever` 这类
 * **只认 Uri 或文件描述符**的底层组件寻址。
 *
 * ## 形状
 * ```
 * mimifs:smb://smb-bc321f3c/MIMISHARE/media/big.mp4
 * mimifs:local:///sdcard/DCIM/IMG_0001.jpg
 * ```
 * 即 `mimifs:` + `FsPath.toString()`。选这个形状而不是"把路径塞进 path 段"，
 * 是因为 [FsPath.toString] 的解析/还原契约已在 M1 冻结并被桌面快捷方式、
 * SAF `documentId` 复用——再套一层自定义编码只会多一个会出错的地方。
 *
 * ## 为什么不能直接用 `file://`
 * 网络路径没有 `file://`；本机路径用 `file://` 会在 targetSdk ≥ 24 上触发
 * `FileUriExposedException`（一旦这个 Uri 逃到应用外，例如 ExoPlayer 的错误上报）。
 * 自定义 scheme 同时回避了两件事。
 */
object PreviewUri {

    /** 自定义 scheme。不要注册到 `IntentFilter`——它只在进程内流转。 */
    const val SCHEME = "mimifs"

    fun of(path: FsPath): Uri = Uri.parse("$SCHEME:$path")

    /** 解析回 [FsPath]；不是 `mimifs:` 地址就返回 `null`。 */
    fun pathOf(uri: Uri?): FsPath? {
        if (uri == null) return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        return FsPath.parse(uri.schemeSpecificPart)
    }

    fun isPreviewUri(uri: Uri?): Boolean =
        uri != null && SCHEME.equals(uri.scheme, ignoreCase = true)
}
