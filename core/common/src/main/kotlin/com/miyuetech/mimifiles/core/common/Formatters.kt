package com.miyuetech.mimifiles.core.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 展示层格式化。
 *
 * ## 为什么不用 `java.time`
 * minSdk 是 24，`java.time` 要到 API 26 才有；开启 core library desugaring 能补上，
 * 但那会给每个构建变体加上一层字节码转换。文件管理器要格式化的就三件事
 * （大小、时间、时长），`SimpleDateFormat` 足够且零成本。
 *
 * 线程安全：`SimpleDateFormat` 不是线程安全的，而列表滚动会在多个 binder 线程上取格式，
 * 所以每个 pattern 各持一个 [ThreadLocal] 实例——比每次 `synchronized` 便宜得多。
 */
object Formatters {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    private val dateTimeFormat = threadLocalFormat("yyyy-MM-dd HH:mm")
    private val timeFormat = threadLocalFormat("HH:mm")
    private val monthDayFormat = threadLocalFormat("MM-dd HH:mm")
    private val yearMonthDayFormat = threadLocalFormat("yyyy-MM-dd")

    private fun threadLocalFormat(pattern: String): ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat(pattern, Locale.getDefault())
        }

    /**
     * 字节数 → 人类可读。保留 1 位小数，并去掉无意义的 `.0`。
     *
     * 未知大小（负数）返回 `"--"`，让 UI 不必各自写 null 分支。
     */
    fun size(bytes: Long): String {
        if (bytes < 0) return "--"
        if (bytes < 1024) return "$bytes B"
        val value = when {
            bytes < 1024L * 1024 -> bytes / KB to "KB"
            bytes < 1024L * 1024 * 1024 -> bytes / MB to "MB"
            bytes < 1024L * 1024 * 1024 * 1024 -> bytes / GB to "GB"
            else -> bytes / TB to "TB"
        }
        val (v, unit) = value
        val rounded = Math.round(v * 10) / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            "${rounded.toLong()} $unit"
        } else {
            "${rounded} $unit"
        }
    }

    /**
     * 修改时间 → 分级展示：
     *  - 今天 → `HH:mm`
     *  - 今年 → `MM-dd HH:mm`
     *  - 更早 → `yyyy-MM-dd`
     *
     * 未知（<=0 或明显不合理的未来值）返回 `"--"`。
     */
    fun dateTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return "--"
        // SMB 上偶见时钟错乱的时间戳；超过 1 天的未来值一律当脏数据处理
        if (millis > now + 24L * 3600_000) return "--"

        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val current = Calendar.getInstance().apply { timeInMillis = now }

        val sameYear = target.get(Calendar.YEAR) == current.get(Calendar.YEAR)
        val sameDay = sameYear &&
            target.get(Calendar.DAY_OF_YEAR) == current.get(Calendar.DAY_OF_YEAR)

        val date = Date(millis)
        return when {
            sameDay -> timeFormat.get()!!.format(date)
            sameYear -> monthDayFormat.get()!!.format(date)
            else -> yearMonthDayFormat.get()!!.format(date)
        }
    }

    /** 精确到分钟的全量时间，用于"属性"面板。 */
    fun fullDateTime(millis: Long): String =
        if (millis <= 0L) "--" else dateTimeFormat.get()!!.format(Date(millis))

    /** 播放时长 / 剩余时间 → `H:MM:SS` 或 `M:SS`。 */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "0:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** 条目数量摘要，如 `12 项`、`3 个文件夹，9 个文件`。 */
    fun itemSummary(total: Int, directories: Int): String {
        val files = (total - directories).coerceAtLeast(0)
        return if (directories > 0) "$total 项（$directories 个文件夹，$files 个文件）" else "$total 项"
    }
}
