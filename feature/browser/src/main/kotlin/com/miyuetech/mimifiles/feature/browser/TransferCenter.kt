package com.miyuetech.mimifiles.feature.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 正在进行的搬运是"复制"还是"移动"（只影响文案与图标，不影响流程）。 */
enum class TransferKind { COPY, MOVE }

/**
 * 一次搬运的可见状态。
 *
 * ## 为什么字节数是"当前文件"的，而不是"整批"的
 * 整批的总字节需要先遍历一遍所有源（跨后端 `stat` 每一项），在 NAS 上是几百毫秒到几秒的
 * 阻塞，而用户点下粘贴时最想要的是"立刻看到有反应"。所以进度条按**条目**推进，
 * 字节数只描述当前那一个文件——这也符合资源管理器的表现。
 *
 * @param doneItems 已完成条目数（**不含**当前正在传的那一条）
 * @param currentBytes 当前条目已传字节
 * @param currentTotal 当前条目总字节；[UNKNOWN] 表示未知（目录或后端不给大小）
 */
data class TransferProgress(
    val kind: TransferKind,
    val totalItems: Int,
    val doneItems: Int,
    val currentName: String,
    val currentBytes: Long,
    val currentTotal: Long,
    val cancelRequested: Boolean,
) {
    /** 条目序号（1 起），用于「第 3/12 项」这种文案。完成全部后不再 +1。 */
    val itemOrdinal: Int get() = if (doneItems >= totalItems) totalItems else doneItems + 1

    /**
     * 当前条目的百分比；未知大小或已开始下一项时返回 [UNKNOWN]。
     *
     * 用 -1 而不是 0 表达"不知道"：进度条据此切换到**不确定模式**（来回滑动），
     * 而 0% 会让人以为"卡住了"。这两者在用户体验上差别很大。
     */
    val currentPercent: Int
        get() {
            if (currentTotal <= 0L) return UNKNOWN
            val raw = currentBytes * 100L / currentTotal
            return raw.coerceIn(0L, 100L).toInt()
        }

    companion object {
        const val UNKNOWN = -1
        const val UNKNOWN_SIZE = -1L
    }
}

/**
 * 取消令牌。
 *
 * ## 为什么是"令牌"而不是一个全局布尔量
 * 全局布尔量有一个具体且必然会踩到的问题：用户取消了 A 搬运 → 布尔量置真 →
 * 用户立刻开始 B 搬运 → B 一启动就被判定成"已取消"。用令牌把它锚定在某一次搬运上，
 * "上一次的取消"在结构上不可能影响下一次。
 *
 * `@Volatile`：写发生在主线程（对话框点取消），读发生在 IO 线程（传输循环里）。
 * 少了它，传输线程可能一直读到缓存的 `false`，现象是"点了取消没用"。
 */
class TransferCancelToken {

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
    }
}

/**
 * 搬运进度的**进程级**发布点。
 *
 * ## 为什么必须放在进程级、而不是 ViewModel 里
 * 三方要同时看同一份进度，而它们互不相识：
 *  - `BrowserViewModel` 产生进度（它是唯一持有 `FsOps` 的地方）；
 *  - `TransferForegroundService` 把它渲染成通知；
 *  - `DocumentsActivity` 把它渲染成对话框。
 *
 * ViewModel 与 Activity 之间可以直接传（`state` 流），但**服务**不行：
 * 它由系统在完全不同的时机创建，拿不到任何 Activity 或 ViewModel 的引用。
 * 所以进度必须落在一个进程级、谁都能订阅的地方。这也是本对象存在的**唯一**理由
 * ——它不持有业务逻辑，不启动协程，不知道 FsOps 是什么。
 *
 * ## 为什么状态是 `null` 表示"没有搬运"
 * 用一个可空值表达"空闲"，比再加一个 `isRunning: Boolean` 更不容易写出非法状态
 * （后者迟早会出现 `isRunning=true` 但字段全是默认值）。
 */
object TransferCenter {

    private val _state = MutableStateFlow<TransferProgress?>(null)
    val state: StateFlow<TransferProgress?> = _state.asStateFlow()

    /**
     * 当前那次搬运的取消令牌。
     *
     * 只被业务层用来判断"用户点没点取消"；[requestCancel] 通过它下发取消，
     * 因此通知栏的「取消」与对话框的「取消」走的是**同一条**路径——
     * 这是"两个入口行为不一致"这类 bug 的最省事预防。
     */
    @Volatile
    private var current: TransferCancelToken? = null

    /** 开始一次搬运。会把上一次遗留的状态直接覆盖掉（不允许两次搬运并存）。 */
    fun begin(kind: TransferKind, totalItems: Int, token: TransferCancelToken) {
        current = token
        _state.value = TransferProgress(
            kind = kind,
            totalItems = totalItems,
            doneItems = 0,
            currentName = "",
            currentBytes = 0L,
            currentTotal = TransferProgress.UNKNOWN_SIZE,
            cancelRequested = false,
        )
    }

    /** 切换到第 [index] 个条目（0 起）。 */
    fun enterItem(index: Int, name: String, size: Long) {
        _state.update { progress ->
            progress?.copy(
                doneItems = index,
                currentName = name,
                currentBytes = 0L,
                currentTotal = size,
            ) ?: return@update null
        }
    }

    /**
     * 当前条目的字节进度。
     *
     * 由 `FsOps` 的 `onProgress` 驱动，**频率可能很高**（64 KiB 一块），
     * 所以这里只做一次 `MutableStateFlow.update`：StateFlow 天然会合并快速更新，
     * 界面与通知都只看到最新的那一份，不需要我们自己做节流。
     */
    fun onBytes(copied: Long, total: Long) {
        _state.update { progress ->
            progress?.copy(
                currentBytes = copied,
                // `total` 为 -1（目录或后端不给大小）时保留未知，不要写成 0：
                // 0 会让百分比恒为"未知"之外的另一个含义（0%），二者在界面上不同。
                currentTotal = if (total > 0) total else TransferProgress.UNKNOWN_SIZE,
            ) ?: return@update null
        }
    }

    /** 用户点了取消（对话框或通知）。 */
    fun requestCancel() {
        val token = current ?: return
        token.cancel()
        _state.update { it?.copy(cancelRequested = true) }
    }

    /**
     * 结束一次搬运，回到空闲。
     *
     * 带 [token] 是为了挡住"迟到的结束"：上一次搬运的收尾如果晚于下一次的开始，
     * 无条件清空会把**新的那次**进度一起抹掉（表现为进度条刚出现就消失）。
     */
    fun finish(token: TransferCancelToken) {
        if (current !== token) return
        current = null
        _state.value = null
    }
}
