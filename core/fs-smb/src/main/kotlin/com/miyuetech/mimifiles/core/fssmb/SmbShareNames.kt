package com.miyuetech.mimifiles.core.fssmb

/**
 * 把 jcifs 服务器根列表返回的原始名字净化成「可以当作共享名点进去」的列表。
 *
 * ## 为什么要单独一个纯函数
 * 共享枚举本身要连真实服务器，没法在 JVM 单测里跑；但**名字净化**是最容易
 * 被脏数据咬到的一段（尾斜杠、`.` / `..`、大小写重复、管理共享），把它抽成
 * 零依赖的纯函数，才能用快测把边界钉住。
 *
 * ## 过滤规则
 *  - jcifs 的服务器根子项名字带尾斜杠（`"home/"`），而 [com.miyuetech.mimifiles.core.model.FsPath.child]
 *    要求单段名不含斜杠，必须去掉；
 *  - `.` / `..` 不是共享，拼进路径会被 [com.miyuetech.mimifiles.core.model.FsPath] 拒绝；
 *  - `$` 结尾的是 Windows 管理共享（`IPC$` / `ADMIN$` / `C$` / `print$`），
 *    既不是"用户的文件夹"，点进去也必然报「不支持的共享类型」；
 *  - 大小写不敏感去重：SMB 共享名本身不区分大小写。
 */
internal object SmbShareNames {

    fun sanitize(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(raw.size)
        for (item in raw) {
            val name = item.trim().trimEnd('/', '\\').trim()
            if (name.isEmpty() || name == "." || name == "..") continue
            if (name.endsWith("$")) continue
            // 服务端偶发会同时返回带/不带尾斜杠的同一共享；净化的时机在去重之前，
            // 所以这里判的是净化后的名字。
            if (!seen.add(name.lowercase())) continue
            out += name
        }
        // 显式 Comparator：`compareBy(...) { it }` 在这个写法下推断不出 T。
        // 先按忽略大小写的顺序排，完全相等时再按原字符串排，保证结果稳定。
        out.sortWith(
            Comparator { a, b ->
                val insensitive = a.compareTo(b, ignoreCase = true)
                if (insensitive != 0) insensitive else a.compareTo(b)
            },
        )
        return out
    }
}
