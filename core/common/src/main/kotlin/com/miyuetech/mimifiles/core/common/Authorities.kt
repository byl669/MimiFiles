package com.miyuetech.mimifiles.core.common

/**
 * 应用级标识符的**单一来源**。
 *
 * ## 为什么需要它
 * `AndroidManifest.xml` 里的 `android:authorities` 只能是字面量（清单无法引用 Kotlin 常量），
 * 所以"清单里写一个、代码里再写一个"是无法完全避免的。能避免的是**代码里写好几遍**：
 * 一旦出现三四处副本，改一处忘一处的结果就是 provider 授权失败或 SAF `content://`
 * 打不开——而这属于"编译通过、运行才炸"的问题。
 *
 * 因此约定：**代码侧一律引用这里**，清单侧保持与这里一致（清单那行会注明指向本文件）。
 */
object Authorities {

    /**
     * `:provider` 里那个 SAF `DocumentsProvider`。
     *
     * 用途之一是把网络路径变成平台解码器能吃的 `content://`
     * （`ImageDecoder` / 外部应用都只认 `content://` 或 `File`）。
     */
    const val DOCUMENTS = "com.miyuetech.mimifiles.documents"

    /**
     * 本机文件的 `FileProvider`（M3 引入）。
     *
     * 用途是把本机文件走 `content://` 交给外部应用打开（Office / 安装包 / 压缩包），
     * 而不暴露明文路径、也不会触发 `FileUriExposedException`。
     */
    const val FILE_PROVIDER = "com.miyuetech.mimifiles.files"
}
