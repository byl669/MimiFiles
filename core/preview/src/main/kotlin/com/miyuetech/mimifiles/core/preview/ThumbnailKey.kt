package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.model.FsPath
import java.security.MessageDigest

/**
 * 缩略图的缓存键。
 *
 * ## 为什么不能直接用路径当键
 *  - 路径含 `:` `/` 等文件系统不友好字符，直接当文件名要转义，而转义是可逆性 bug 的温床；
 *  - 更重要的是**内容会变**：`IMG_0001.jpg` 被覆盖成另一张照片后，路径没变。
 *    只按路径缓存，用户会看到旧缩略图——而且这个 bug 极难被归因（"重启就好了"）。
 *    所以键里必须带 `size` + `lastModified`，形成"内容指纹"。
 *
 * ## 为什么用 SHA-256 而不是 `hashCode()`
 * `hashCode()` 是 32 位且实现不稳定（不同进程可能不同、不同 JVM 版本可能不同）。
 * 缓存键要落盘、要跨进程复用（`DocumentsProvider` 也可能读它），必须是**确定且足够宽**的。
 * 取摘要前 32 个十六进制字符（128 位）已经远超碰撞可忽略的量级。
 *
 * ## ⚠️ [VERSION] 的用途
 * 一旦缩略图的**生成算法**变了（例如把最大边从 384 改成 512、或换编码格式），
 * 旧缓存必须整体失效，否则会出现"新旧尺寸混在一起"的诡异排版。
 * 改算法时**必须**把这个数字加一。
 */
object ThumbnailKey {

    private const val VERSION = 1

    private const val HEX_LENGTH = 32

    fun of(path: FsPath, size: Long, lastModified: Long): String {
        val raw = "$VERSION|$path|$size|$lastModified"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(HEX_LENGTH)
        var i = 0
        while (sb.length < HEX_LENGTH && i < digest.size) {
            sb.append(HEX[(digest[i].toInt() shr 4) and 0x0F])
            if (sb.length < HEX_LENGTH) sb.append(HEX[digest[i].toInt() and 0x0F])
            i++
        }
        return sb.toString()
    }

    /**
     * 磁盘缓存的二级目录（取键的前 2 位）。
     *
     * 理由很实际：单个目录塞几万个文件后，`readdir` 在部分文件系统（尤其 FAT32 的
     * SD 卡）上会明显变慢，而且 `trimDisk` 排序的代价也随之上升。
     * 256 个桶是"够散"与"目录数可控"之间的常见折中。
     */
    fun shardOf(key: String): String =
        if (key.length >= 2) key.substring(0, 2) else "00"

    private val HEX = "0123456789abcdef".toCharArray()
}
