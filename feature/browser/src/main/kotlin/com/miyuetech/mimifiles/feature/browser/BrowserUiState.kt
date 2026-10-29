package com.miyuetech.mimifiles.feature.browser

import com.miyuetech.mimifiles.core.fslocal.StorageTier
import com.miyuetech.mimifiles.core.model.FileViewMode
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.FsSort

/** 剪切板内容。`cut=true` 表示这些条目粘贴后会从原位消失。 */
data class ClipboardState(
    val paths: List<FsPath>,
    val cut: Boolean,
    /** 来源目录，用于"原地粘贴"时提示用户。 */
    val sourceDir: FsPath?,
) {
    val count: Int get() = paths.size
}

/**
 * 空态/进度态的**类型**。
 *
 * 用枚举而不是直接存文案：文案属于 `res/values`，状态属于 ViewModel。
 * 混在一起的直接后果是深色/浅色、多语言、以及"改一句提示语要动业务代码"。
 */
enum class EmptyKind {
    /** 不显示空态（正常有内容）。 */
    NONE,

    /** 正在读取。 */
    LOADING,

    /** 目录确实为空。 */
    EMPTY,

    /**
     * 搜索没有命中（M6 新增）。
     *
     * 与 [EMPTY] 必须分开：目录里明明有 800 个文件，只是没有一个匹配当前关键词，
     * 此时显示"此文件夹为空"是在**撒谎**，用户会去怀疑目录读错了。
     */
    NO_MATCH,

    /** 无权访问该目录。 */
    NO_PERMISSION,

    /** 其他失败（磁盘错、网络断）。 */
    ERROR,
}

/**
 * 搜索状态（M6 · P1-5）。
 *
 * ## 为什么"本地过滤"与"深搜"共用一个 query 而不是两个
 * 用户输入的是**一个**关键词，界面里也只该有一条结果列表。拆成"过滤框 + 深度开关"
 * 会立刻产生两个问题：两条结果列表谁覆盖谁、以及同一个词在两种模式下得到不同的
 * 集合却看不出区别。所以这里只有一个 query，深搜是它的**后台延伸**——
 * 先把当前目录的命中立刻显示出来（零延迟），深搜的结果再陆续并进来。
 */
data class SearchState(
    /** 当前关键词（原样保留大小写，匹配时统一转小写）。 */
    val query: String = "",

    /** 深搜是否仍在进行。 */
    val running: Boolean = false,

    /** 已访问的条目数（进度提示用）。扫描上限见 ViewModel 的 `SEARCH_SCAN_LIMIT`。 */
    val scanned: Int = 0,

    /** 是否因为到达扫描上限而提前停止。 */
    val truncated: Boolean = false,

    /** 走到过多少层目录（提示用，让用户知道"搜到哪了"）。 */
    val visitedDirs: Int = 0,
) {
    val active: Boolean get() = query.isNotBlank()
}

/**
 * 浏览界面的全部可变状态。
 *
 * ## 为什么把「位置首页」建模成 `location == null`
 * 首页（存储列表）与普通目录不是一类东西：它没有父目录、不能新建、不能粘贴、
 * 面包屑也无从画起。用一个 `null` 表达"当前不在任何目录里"，
 * 比再加一个 `isHome: Boolean` 与 `location` 互斥要难写错——后者迟早会出现
 * "isHome=true 但 location 也有值"的非法组合。
 */
data class BrowserUiState(
    /** 当前目录；`null` 表示「位置」首页。 */
    val location: FsPath? = null,

    /** 当前列表（已按用户偏好过滤 + 排序）。 */
    val entries: List<FsEntry> = emptyList(),

    /** 本机存储入口（内部存储 / SD 卡 / SAF 授权目录）。 */
    val localRoots: List<FsEntry> = emptyList(),

    /**
     * 网络存储入口（每条 SMB / WebDAV 连接一个）。
     *
     * 与 [localRoots] 分开存而不是混进一个列表：侧栏要把它们渲染成两组
     * （见 `NavigationTreeView.RootSection`），而"哪些是本机"这个信息一旦合并
     * 就丢了——靠 scheme 反推等于让 UI 层去认识每一种协议。
     */
    val networkRoots: List<FsEntry> = emptyList(),

    /** 快速访问（收藏 / 最近）；M5 接入书签与历史后填充。 */
    val quickAccess: List<FsEntry> = emptyList(),

    /**
     * 收藏的文件夹路径（M7.12）。与 [quickAccess] 同源——quickAccess 就是它
     * 映射成 [FsEntry] 的渲染形态——分开存是因为多选栏判断"收藏还是取消收藏"
     * 只需要路径集合，不需要合成条目。
     */
    val favorites: List<FsPath> = emptyList(),

    val selection: Set<FsPath> = emptySet(),

    /** 是否处于多选模式（决定点击行为与勾选框显隐）。 */
    val selectionMode: Boolean = false,

    val clipboard: ClipboardState? = null,

    val viewMode: FileViewMode = FileViewMode.LIST,

    val sort: FsSort = FsSort.DEFAULT,

    val showHidden: Boolean = false,

    /** 空态类型。 */
    val empty: EmptyKind = EmptyKind.NONE,

    /** [EmptyKind.ERROR] 时的原始错误串，只用于日志与角标，不直接展示。 */
    val errorText: String? = null,

    /** 存储权限档位；`null` 表示尚未探测。 */
    val tier: StorageTier? = null,

    /** 已持久化的 SAF 目录授权数量（提示条文案要用，不能从 roots 里反推）。 */
    val grantCount: Int = 0,

    /** 搜索状态；`null` 表示不在搜索中。 */
    val search: SearchState? = null,

    /**
     * 返回上一级后要"落"到的那一条（M7.24）。
     *
     * 存的是**刚离开的那个子目录**的路径。没有它，从 `A/B/C` 退回 `A/B` 时列表会回到
     * 顶端，用户得重新往下翻——在一堆同类命名素材的目录里，这几乎等于重新找一遍。
     *
     * 一次性语义：界面滚到位后调 [com.miyuetech.mimifiles.feature.browser.BrowserViewModel.onRevealScrolled]，
     * ViewModel 再留一小会儿高亮就自动清空。因此它不参与"当前位置"的判定，
     * 只影响渲染。
     */
    val revealPath: FsPath? = null,
) {
    /**
     * 全部存储入口（合并视图）。首页列表与"当前位置是否为根"的判定都用它。
     *
     * 计算属性而不是构造参数：它完全由 [localRoots] 与 [networkRoots] 决定，
     * 存成字段就多出一个可以与来源不一致的状态。放在类体里是因为**主构造的参数
     * 只允许声明属性，不能挂自定义 getter**。
     */
    val roots: List<FsEntry> get() = localRoots + networkRoots

    /** 是否停留在「位置」首页。 */
    val isHome: Boolean get() = location == null

    /** 是否处于降级权限（应给出「去授权」入口）。 */
    val degraded: Boolean get() = tier != null && tier != StorageTier.FULL

    val selectedCount: Int get() = selection.size

    /** 已选条目（顺序跟随列表，便于"分享/复制"保持可视顺序）。 */
    val selectedEntries: List<FsEntry> get() = entries.filter { it.path in selection }

    /** 已选条目里可直接钉到桌面的**目录**（单文件快捷方式已明确不做）。 */
    val selectedDirectories: List<FsEntry> get() = selectedEntries.filter { it.isDirectory }

    /** 能否在当前目录写入（首页与只读后端不能）。 */
    val canWrite: Boolean get() = !isHome
}
