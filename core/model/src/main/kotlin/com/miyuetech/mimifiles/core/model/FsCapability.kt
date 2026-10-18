package com.miyuetech.mimifiles.core.model

/**
 * 后端能力位（位掩码）。
 *
 * 用途有二：
 *  1. **UI 灰置**：不支持的能力直接把菜单项禁用，而不是等用户点了再报错；
 *  2. **能力协商**：跨后端复制/移动时据此选择"原生 copy"还是"流式中转"。
 *
 * 用 `Int` 位掩码而不是 `Set<Enum>`：能力会跟着每个 `FsEntry` 走，
 * 集合对象在 5000 条目的列表里是实打实的分配开销。
 */
object FsCapability {
    const val NONE = 0x0000

    /** 可列目录。 */
    const val LIST = 0x0001

    /** 可读取文件内容。 */
    const val READ = 0x0002

    /** 可写入文件内容（含覆盖与追加）。 */
    const val WRITE = 0x0004

    /** 可新建目录。 */
    const val CREATE_DIRECTORY = 0x0008

    /** 可删除。 */
    const val DELETE = 0x0010

    /** 可重命名。 */
    const val RENAME = 0x0020

    /** 支持**随机读**（seek）——视频拖动进度条的前提。 */
    const val SEEK = 0x0040

    /** 后端能提供缩略图（如 Android MediaStore）。 */
    const val THUMBNAIL = 0x0080

    /** 能观察外部变化（如本机 `FileObserver`）。 */
    const val WATCH = 0x0100

    /** 能读修改时间/权限等元数据。 */
    const val METADATA = 0x0200

    /** 改属性（隐藏、只读位）。 */
    const val SET_ATTRIBUTES = 0x0400

    // 注意：以下组合项用 `val` 而非 `const val` —— Kotlin 的编译期常量表达式**不支持**
    // 位运算（`or`/`shl` 是普通函数调用，不是运算符重载），写成 const 会直接编译失败。
    // 写成十六进制字面量也能过，但可读性会崩掉，而这两项没有任何性能意义。

    /** 只读后端（如无写权限的 SAF 授权树）。 */
    val READ_ONLY: Int = LIST or READ or SEEK or METADATA

    /** 本机存储的完整能力。 */
    val FULL_LOCAL: Int = LIST or READ or WRITE or CREATE_DIRECTORY or DELETE or
        RENAME or SEEK or THUMBNAIL or WATCH or METADATA or SET_ATTRIBUTES

    /** 人类可读的能力串，仅用于日志与排障。 */
    fun describe(caps: Int): String {
        if (caps == NONE) return "none"
        val sb = StringBuilder()
        fun mark(name: String, bit: Int) {
            if (caps.has(bit)) {
                if (sb.isNotEmpty()) sb.append('|')
                sb.append(name)
            }
        }
        mark("list", LIST); mark("read", READ); mark("write", WRITE)
        mark("mkdir", CREATE_DIRECTORY); mark("delete", DELETE); mark("rename", RENAME)
        mark("seek", SEEK); mark("thumb", THUMBNAIL); mark("watch", WATCH)
        mark("meta", METADATA); mark("attrs", SET_ATTRIBUTES)
        return sb.toString()
    }
}

/**
 * 能力位判定。刻意做成**顶层扩展**（而不是 `FsCapability` 的成员）：
 * 成员扩展函数要求调用点 `with(FsCapability) { }` 或显式 import 限定路径，
 * 用起来到处别扭；顶层扩展只 import 一次，`caps.has(FsCapability.SEEK)` 随处可读。
 */
fun Int.has(capability: Int): Boolean = (this and capability) == capability

fun Int.hasAll(vararg capabilities: Int): Boolean = capabilities.all { has(it) }

fun Int.hasAny(vararg capabilities: Int): Boolean = capabilities.any { has(it) }
