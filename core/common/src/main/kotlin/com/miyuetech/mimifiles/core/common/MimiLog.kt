package com.miyuetech.mimifiles.core.common

import android.util.Log

/**
 * 轻量日志：环形缓冲 + 手动导出。
 *
 * ## 为什么不用 Crashlytics / Timber
 * - Crashlytics 会带进 GMS 依赖链，与"轻量化 + 无广告 + 内部侧载"的定位冲突；
 * - Timber 只是个门面，真正要的"最近 N 条能一键导出"它不给。
 *
 * 这里要解决的是一个很具体的运维问题：**用户说"连不上 NAS"时，我们拿不到现场**。
 * 内部侧载的场景下没有崩溃平台可查，只能让用户在设置页点"导出日志"，
 * 把最近几百条发过来。所以缓冲要常驻、格式要能直接贴进聊天窗口。
 *
 * ## 铁律：这里绝不能出现密码
 * SMB / WebDAV 的密码、Keystore 密文、HTTP `Authorization` 头都不允许进日志。
 * 光靠"写代码时小心"是靠不住的，所以提供 [redact] 把已知敏感模式一律替换掉，
 * 并且网络后端在打印 URL / 连接串时**必须**过一遍它。
 */
object MimiLog {

    /** 级别。数值越大越严重，用于设置页"仅记录警告以上"的过滤。 */
    enum class Level(val tag: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
    }

    data class Record(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: String? = null,
    ) {
        /** 单行导出格式：`10:31:02.417 W MimiSmb 连接超时`。 */
        fun format(): String = buildString {
            append(formatClock(timestamp))
            append(' ').append(level.tag).append(' ')
            append(tag).append(' ')
            append(message)
            throwable?.let { append(" | ").append(it) }
        }
    }

    /** 环形缓冲容量。400 条 × 平均 120 字符 ≈ 50 KB 内存，可忽略。 */
    private const val CAPACITY = 400

    private val lock = Any()

    // ArrayDeque + 手动淘汰：比每次 add 后 list 裁剪便宜，且不会产生中间集合。
    private val buffer = ArrayDeque<Record>(CAPACITY + 1)

    /**
     * 是否把日志同时写给 logcat。
     *
     * debug 变体默认开（`adb logcat -s` 直接看），release 默认关——
     * 侧载分发时 logcat 里有明文路径与主机名没有益处，而缓冲导出保留了排障能力。
     * 由各模块的初始化按 `BuildConfig.DEBUG` 设置。
     */
    @Volatile
    var mirrorToLogcat: Boolean = false

    /** 低于此级别的记录被丢弃（默认全收）。 */
    @Volatile
    var minLevel: Level = Level.DEBUG

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, tag, message, throwable)

    fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val record = Record(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" },
        )
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(record)
        }
        if (mirrorToLogcat) {
            val text = record.message + (record.throwable?.let { " | $it" } ?: "")
            when (level) {
                Level.DEBUG -> Log.d(tag, text)
                Level.INFO -> Log.i(tag, text)
                Level.WARN -> Log.w(tag, text)
                Level.ERROR -> Log.e(tag, text)
            }
        }
    }

    /** 最近 [limit] 条（时间正序）。设置页与导出都从这里取。 */
    fun snapshot(limit: Int = CAPACITY): List<Record> = synchronized(lock) {
        if (limit >= buffer.size) buffer.toList() else buffer.toList().takeLast(limit)
    }

    /** 导出为可直接粘贴的纯文本。 */
    fun export(limit: Int = CAPACITY): String = buildString {
        append("# 密密文件管理 日志导出（最近 ").append(limit).append(" 条）\n")
        append("# 设备时间：").append(formatClock(System.currentTimeMillis())).append('\n')
        for (record in snapshot(limit)) {
            append(record.format()).append('\n')
        }
    }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun size(): Int = synchronized(lock) { buffer.size }

    /**
     * 脱敏。
     *
     * 覆盖三类会真正泄漏凭据的形态：
     *  1. URL 里的 `user:pass@host`——WebDAV 客户端与 OkHttp 打日志时最容易带出来；
     *  2. 查询串/表单里的 `password=…`、`passwd=`、`pwd=`、`token=`；
     *  3. HTTP 头里的 `Authorization: Basic xxx`。
     *
     * 这不是万无一失的 DLP，而是"把最容易犯的错堵住"的工程习惯：
     * 只要网络后端打印连接信息时统一过这里，就不会有明文密码掉进用户导出的日志里。
     */
    fun redact(input: String): String {
        var out = input
        out = AUTH_HEADER.replace(out, "$1***")
        out = URL_CREDENTIALS.replace(out, "$1***@")
        out = QUERY_SECRET.replace(out, "$1***")
        return out
    }

    private fun formatClock(epochMillis: Long): String {
        // 刻意不用 SimpleDateFormat（非线程安全，且这里只想要 HH:mm:ss.SSS）。
        val seconds = epochMillis / 1000
        val millis = epochMillis % 1000
        val secOfDay = ((seconds + TIMEZONE_OFFSET_SECONDS) % 86400 + 86400) % 86400
        val h = secOfDay / 3600
        val m = (secOfDay % 3600) / 60
        val s = secOfDay % 60
        return buildString(12) {
            append(pad2(h.toInt())).append(':')
            append(pad2(m.toInt())).append(':')
            append(pad2(s.toInt())).append('.')
            append(millis.toString().padStart(3, '0'))
        }
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    /**
     * 时区偏移（秒）。
     *
     * 用 `java.util.TimeZone` 取而不是 `ZoneId`：后者在 minSdk 24 上需要 API 26 或
     * desugaring；而这里的用途只是让日志时间戳能对上用户看到的时钟，
     * 用最老的可移植 API 就够了。
     */
    private val TIMEZONE_OFFSET_SECONDS: Long =
        (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toLong()

    private val AUTH_HEADER = Regex("""(?i)(authorization\s*[:=]\s*)(\S.*)""")
    private val URL_CREDENTIALS = Regex("""(://[^/@:\s]+):[^/@\s]+@""")
    private val QUERY_SECRET = Regex("""(?i)((?:password|passwd|pwd|secret|token|pincode)\s*=\s*)([^&\s]*)""")
}
