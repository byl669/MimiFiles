package com.miyuetech.mimifiles.feature.browser

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miyuetech.mimifiles.core.bootstrap.Backends
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.common.MimeTypes
import com.miyuetech.mimifiles.core.datastore.SettingsRepository
import com.miyuetech.mimifiles.core.fs.ConflictPolicy
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.FsAccessDeniedException
import com.miyuetech.mimifiles.core.fs.FsCancelledException
import com.miyuetech.mimifiles.core.fs.FsException
import com.miyuetech.mimifiles.core.fs.FsNotFoundException
import com.miyuetech.mimifiles.core.fs.FsOps
import com.miyuetech.mimifiles.core.fs.toFsException
import com.miyuetech.mimifiles.core.fslocal.LocalStorageProvider
import com.miyuetech.mimifiles.core.fslocal.SCHEME_LOCAL
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FileViewMode
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsKind
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.FsSortField
import com.miyuetech.mimifiles.core.model.sortedBySpec
import com.miyuetech.mimifiles.core.preview.PreviewArgs
import com.miyuetech.mimifiles.core.shortcut.FolderShortcuts
import com.miyuetech.mimifiles.core.shortcut.ShortcutResult
import com.miyuetech.mimifiles.core.shortcut.ShortcutTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 浏览主界面的状态机与全部文件操作。
 *
 * ## 单一数据源
 * 界面上的每一处（列表、面包屑、导航树、多选栏、状态栏）都从 [state] 派生。
 * 没有任何一处自己存状态——这类页面最容易出的 bug 就是"列表刷新了但导航树还指着旧目录"，
 * 而只要只有一个源，这种不一致在结构上就不可能出现。
 *
 * ## 线程约定
 * `state` 只在主线程变更（`viewModelScope` 默认 Main）。真正阻塞的活（列举、复制、
 * 探测权限）都 `withContext(Dispatchers.IO)`。**View 层因此永远不需要考虑线程**。
 *
 * ## 与 View 层的边界
 * VM 不持有 Activity/Fragment/View，也不产出中文文案：
 * - 要弹提示 → 发 [BrowserMessage]；
 * - 要跳系统页/弹框 → 发 [BrowserRequest]；
 * - 要渲染空态 → 只给 [EmptyKind]。
 * 这样旋转屏幕、进程重建都不会留下失效引用。
 */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<BrowserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<BrowserMessage> = _messages.asSharedFlow()

    private val _requests = MutableSharedFlow<BrowserRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<BrowserRequest> = _requests.asSharedFlow()

    /**
     * 未过滤、未排序的原始列举结果。
     *
     * 排序/显示隐藏文件的偏好变化时，从这份原始数据**重新派生**列表，
     * 而不是重新去读一遍目录——在 NAS 上重读一次目录是几百毫秒，用户只是想换个排序。
     */
    private var rawEntries: List<FsEntry> = emptyList()

    private var storage: LocalStorageProvider? = null

    private var ops: FsOps? = null

    private var listJob: Job? = null

    /** 「返回上级后落位高亮」的撤销任务。再次导航时必须作废，否则会清掉新一轮的 revealPath。 */
    private var revealClearJob: Job? = null

    /** 深搜任务。取消它 = 停止遍历（[updateSearchQuery] / [exitSearch] 都会取消）。 */
    private var searchJob: Job? = null

    /**
     * 深搜命中的条目（**当前目录之外**的那些）。
     *
     * 与 [rawEntries] 分开存，而不是把命中也塞进 [rawEntries]：后者是"这个目录的内容"，
     * 它同时被排序、被"显示隐藏文件"、被多选全选、被状态栏统计使用。把子目录里的东西
     * 混进去，会让"全选"去选一堆看不见其父目录的文件 —— 那是一个没有合理语义的操作。
     * 两份分开、由 [deriveEntries] 合并，才谈得上"搜索模式"是一个**视图**而不是一次搬迁。
     */
    private var deepHits: List<FsEntry> = emptyList()

    private var initialized = false

    init {
        observeSettings()
    }

    // ── 生命周期入口 ────────────────────────────────────────────────────

    /**
     * 首次进入时调用。
     *
     * @param target 由桌面快捷方式透传的目标目录；`null` 表示普通启动（落到第一个卷根）。
     *
     * 重复调用时若带 [target] 则视为一次跳转（`singleTask` 下点桌面图标走的是
     * `onNewIntent`，不会重建 Activity）。
     */
    fun initialize(target: FsPath?) {
        if (initialized) {
            target?.let(::openPath)
            return
        }
        initialized = true

        _state.update { it.copy(empty = EmptyKind.LOADING) }
        viewModelScope.launch {
            // Backends.storage 要读 SAF 授权表（SharedPreferences）、并注册全部四个后端
            // （local / saf / smb / webdav），所以放 IO 线程；这也是它没有被放进
            // Application.onCreate 的原因——那里只允许读一次主题。
            //
            // 走 Backends（而不是自己 new 一个 LocalStorageProvider 再 install）是为了
            // 与 SAF DocumentsProvider 共用**同一个**后端注册结果：系统单独拉起 provider
            // 时浏览页可能一次都没跑过，两处各自注册必然出现"谁先跑谁说了算"的歧义。
            val provider = withContext(Dispatchers.IO) { Backends.storage(getApplication()) }
            storage = provider
            ops = FsOps(FileSystems.registry)
            // 连接库一变，侧栏的"网络位置"组跟着变（含首次进入时的初始加载）。
            observeConnections()

            val access = withContext(Dispatchers.IO) { provider.access(refresh = true) }
            publishAccess(access)

            if (target != null) {
                openPath(target)
            } else {
                val first = access.roots.firstOrNull()
                if (first != null) openPath(first.path) else showHome()
            }
        }
    }

    /** 从系统设置页/授权对话框返回时调用，重新判断权限档位。 */
    fun refreshStorageAccess() {
        val provider = storage ?: return
        viewModelScope.launch {
            val access = withContext(Dispatchers.IO) { provider.access(refresh = true) }
            publishAccess(access)
            // 首页展示的就是根列表，档位一变必须重建；在普通目录里则不动用户的位置
            if (_state.value.isHome) showHome()
        }
    }

    /**
     * 登记一条 SAF 目录授权。
     *
     * @param flags 直接来自 `ACTION_OPEN_DOCUMENT_TREE` 结果 Intent；
     *              里面带不带写权限位决定了这个目录能不能写。
     */
    fun registerSafGrant(uri: Uri, flags: Int) {
        val provider = storage ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { provider.registerSafGrant(uri, flags) }
            refreshStorageAccess()
        }
    }

    // ── 导航 ────────────────────────────────────────────────────────────

    /** 「位置」首页：列出全部存储入口。 */
    fun showHome() {
        listJob?.cancel()
        // 首页与"搜索某个目录"互斥：搜索结果里每一项的父目录都是刚才那个目录，
        // 而首页没有父目录可循。留着搜索态只会让用户看到一份与当前位置无关的列表。
        exitSearch()
        val roots = _state.value.roots
        rawEntries = roots
        _state.update {
            it.copy(
                location = null,
                selection = emptySet(),
                selectionMode = false,
                empty = if (roots.isEmpty()) EmptyKind.NO_PERMISSION else EmptyKind.NONE,
                errorText = null,
                // 首页没有"刚离开的子目录"这回事，别把上一页的高亮带出来
                revealPath = null,
            )
        }
        deriveEntries()
    }

    /**
     * 进目录。
     *
     * @param reveal     回到父目录时要"落"到的那一条（只有 [goUp] 会传）。
     * @param clearFirst 是否先清空列表。换目录**必须**清（否则会短暂看到上一个目录的内容）；
     *                   原地刷新**必须不清**（清了 DiffUtil 就变成"全删+全加"，
     *                   RecyclerView 的滚动位置直接回到顶端，见 [refresh]）。
     */
    fun openPath(path: FsPath, reveal: FsPath? = null, clearFirst: Boolean = true) {
        listJob?.cancel()
        // 进目录 = 换了一个搜索范围。不退出搜索的话，新目录的列表会被上一次的
        // 关键词继续过滤，而且深搜会从**新目录**接着往子树里跑 ——
        // 用户以为自己只是"点进一个文件夹"，实际触发了第二轮全树遍历。
        exitSearch()
        _state.update {
            it.copy(
                location = path,
                selection = emptySet(),
                selectionMode = false,
                empty = if (clearFirst) EmptyKind.LOADING else it.empty,
                errorText = null,
                entries = if (clearFirst) emptyList() else it.entries,
                revealPath = reveal,
            )
        }
        // 上一次的"到点自动撤高亮"必须作废，否则它会在这次导航的半途把 revealPath 清掉。
        revealClearJob?.cancel()
        listJob = viewModelScope.launch { reload(path) }
    }

    /**
     * 重新读取当前目录（下拉刷新、粘贴/删除之后、从预览/播放页返回）。
     *
     * ## 为什么**不清空**当前列表（M7.24）
     * 以前它等价于 `openPath(current)`，会先把 `entries` 置空、`empty` 置 LOADING。
     * 本地盘上用户看不出来；但"改完东西要刷新"的场景代价很具体：列表先空一帧再填回来，
     * `DiffUtil` 看到的是"全删 + 全加"，**RecyclerView 的滚动位置直接回到顶端**。
     * 审片时在播放页删掉一个片子、退回列表，就得从头再翻一遍 —— 正是用户抱怨的那件事。
     *
     * 现在只换数据不换空态（`reload` 拿到新列表才替换），中间那几十毫秒旧列表还在。
     */
    fun refresh() {
        val current = _state.value.location
        if (current == null) showHome() else openPath(current, clearFirst = false)
    }

    /**
     * 返回上一级；已在卷根或首页时停在首页。
     *
     * ## 为什么要把"刚离开的目录"带过去（M7.24）
     * 退回父目录后，用户要的不是"这个目录"，而是"我刚才待的那个子文件夹"。
     * 滚到顶端等于让他重新找一遍，在一堆同名系列的素材目录里尤其难受。
     */
    fun goUp() {
        val current = _state.value.location ?: return
        val atRoot = _state.value.roots.any { it.path == current }
        val parent = current.parent
        if (atRoot || parent == null || parent.isRoot) {
            showHome()
        } else {
            openPath(parent, reveal = current)
        }
    }

    /**
     * 界面已经把列表滚到 [BrowserUiState.revealPath] 了。
     *
     * 计时从"真的滚到位"开始，而不是从"发起导航"开始：NAS 上列一次目录要几百毫秒，
     * 若从导航时就开始倒计时，慢盘上会出现"高亮已经撤了、列表才刚出来"。
     */
    fun onRevealScrolled() {
        revealClearJob?.cancel()
        revealClearJob = viewModelScope.launch {
            delay(REVEAL_HIGHLIGHT_MS)
            _state.update { if (it.revealPath == null) it else it.copy(revealPath = null) }
        }
    }

    /** 点击列表条目：多选模式下切换勾选，否则进目录 / 打开文件。 */
    fun openEntry(entry: FsEntry) {
        if (_state.value.selectionMode) {
            toggleSelection(entry.path)
            return
        }
        if (entry.isDirectory) {
            openPath(entry.path)
        } else {
            // M3：交给统一预览入口。**同级列表在这里算**——只有 ViewModel 知道当前的
            // 排序与过滤（隐藏文件开关、排序字段），而预览里左右滑的顺序必须与用户
            // 在列表里看到的完全一致。
            _requests.tryEmit(BrowserRequest.OpenPreview(previewArgsFor(entry)))
        }
    }

    /**
     * 为 [entry] 组织预览入参。
     *
     * 规则：**点的是媒体（图片/视频）就把同级媒体都带上，否则只带它自己**。
     *
     * 为什么不做"把整个目录都带上"：预览页的画廊只认图片/视频，把一个 `.txt` 或
     * 安装包放进同级列表，用户滑过去只会看到一页"打不开"。而反过来，若在文本文件上
     * 也带上同级媒体，用户从 txt 往左滑会莫名其妙跳到一张照片——都不是他期待的。
     */
    private fun previewArgsFor(entry: FsEntry): PreviewArgs {
        val mime = MimeTypes.of(entry.name)
        if (!MimeTypes.isMedia(mime)) {
            return PreviewArgs.of(listOf(entry.path), 0)
        }
        val siblings = _state.value.entries.filter {
            it.isFile && MimeTypes.isMedia(MimeTypes.of(it.name))
        }
        val index = siblings.indexOfFirst { it.path == entry.path }
        if (index < 0) return PreviewArgs.of(listOf(entry.path), 0)
        return PreviewArgs.of(siblings.map { it.path }, index)
    }

    // ── 多选 ────────────────────────────────────────────────────────────

    fun enterSelection(path: FsPath) {
        _state.update { it.copy(selection = setOf(path), selectionMode = true) }
    }

    fun toggleSelection(path: FsPath) {
        val current = _state.value
        val next = if (path in current.selection) current.selection - path else current.selection + path
        _state.update { it.copy(selection = next, selectionMode = next.isNotEmpty()) }
    }

    /** 全选当前列表；再点一次清空（同一颗按钮的两种语义）。 */
    fun toggleSelectAll() {
        val current = _state.value
        if (current.entries.isEmpty()) return
        if (current.selection.size >= current.entries.size) {
            exitSelection()
        } else {
            val all = current.entries.mapTo(LinkedHashSet()) { entry -> entry.path }
            _state.update { it.copy(selection = all, selectionMode = true) }
        }
    }

    fun exitSelection() {
        _state.update { it.copy(selection = emptySet(), selectionMode = false) }
    }

    // ── 搜索（M6 · P1-5）────────────────────────────────────────────────

    /**
     * 关键词变化。
     *
     * 分成两段是刻意的：
     *  1. **立刻**用当前目录的列表做过滤（纯内存、零延迟）——每敲一个字都该有反馈，
     *     而这一段的代价与"当前目录有多少条"成正比，与子目录规模无关；
     *  2. [SEARCH_DEBOUNCE_MS] 之后才启动**深搜**（往子目录里走）。深搜的代价与
     *     整棵树成正比，不防抖的话每敲一个字符就起一轮全树遍历，在 NAS 上
     *     会把连接池直接打满——而用户只是打字快了点。
     *
     * 深搜的启动条件里**没有**"关键词长度 ≥ N"这类门槛：单字符搜索在文件管理器里
     * 是正常用法（找某个字母开头的目录），而防抖已经把抖动吃掉了；真正的护栏是
     * 扫描上限与可取消。
     */
    fun updateSearchQuery(query: String) {
        searchJob?.cancel()
        deepHits = emptyList()
        if (query.isBlank()) {
            _state.update { it.copy(search = null) }
            deriveEntries()
            return
        }
        _state.update { it.copy(search = SearchState(query = query)) }
        deriveEntries()
        val root = _state.value.location ?: return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            deepSearch(root, query)
        }
    }

    /** 退出搜索：清关键词、撤结果、恢复成"这就是当前目录的内容"。 */
    fun exitSearch() {
        if (_state.value.search == null) return
        searchJob?.cancel()
        searchJob = null
        deepHits = emptyList()
        _state.update { it.copy(search = null) }
        deriveEntries()
    }

    /**
     * 从 [root] 往下走，把名字包含关键词的条目陆续并进结果。
     *
     * ## 并发限流
     * 本机后端用 [LOCAL_WORKERS] 个 worker 并行列举，网络后端用 [NETWORK_WORKERS]。
     * 这不是拍脑袋：方案 §8.3 第 1 条已经把 SMB 的并发上限定在 4，而目录**列举**
     * 比数据读取轻得多；取 2 是为同时进行的预览/播放留余量——深搜跑起来的时候，
     * 用户很可能正看着某个文件，甚至正在播放。
     *
     * ## 上限与可取消
     * 累计扫描到 [SEARCH_SCAN_LIMIT] 条后主动停下，并把 `SearchState.truncated` 置位。
     * 一个几十万文件的共享下，"搜完"这个承诺既给不了、也没人真的需要；
     * 给一个**明确的**边界，比给一个永远转不完的圈诚实。
     * 取消有两条路：清空输入框 / 按返回键（[exitSearch]），或离开当前目录
     * （[openPath] / [showHome] 都会调 [exitSearch]）—— 一 cancel，worker 在下一个
     * 挂起点就退出，不需要额外的协作标志。
     *
     * ## 为什么结果只往"根目录之外"收
     * 根目录自己的子项由 `rawEntries` 提供（本地过滤那一段已经收过了）。
     * 深搜再从根收一遍，结果列表里就会出现**每个命中项两份**——一份来自过滤、
     * 一份来自遍历。这个 bug 在只有一层子目录时看起来像"重复渲染"，很容易被
     * 当成 ListAdapter 的问题去查。
     */
    private suspend fun deepSearch(root: FsPath, query: String) = coroutineScope {
        val needle = query.lowercase()
        val registry = FileSystems.registry
        val workerCount = if (root.scheme == SCHEME_LOCAL) LOCAL_WORKERS else NETWORK_WORKERS

        // 待列举目录队列。`pending` 数的是"已入队但还没处理完"的目录数，
        // 归零才关队列 —— 单看队列是否为空会提前退出（worker 之间有时差）。
        val dirs = Channel<FsPath>(Channel.UNLIMITED)
        val found = Channel<FsEntry>(Channel.UNLIMITED)
        val pending = AtomicInteger(1)
        val scanned = AtomicInteger(0)
        val visited = AtomicInteger(0)
        val truncated = AtomicBoolean(false)
        dirs.trySend(root)

        val walkers = List(workerCount) {
            launch(Dispatchers.IO) {
                for (dir in dirs) {
                    val children = try {
                        registry.resolve(dir).list(dir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // 单个子目录读不了（权限、被删、网络抖动）不该让整轮搜索失败：
                        // 用户要的是"能搜到多少算多少"，而不是"一个坏目录毁掉全部结果"。
                        emptyList()
                    }
                    visited.incrementAndGet()
                    for (child in children) {
                        if (child.isDirectory) {
                            pending.incrementAndGet()
                            dirs.trySend(child.path)
                        }
                        if (dir != root && child.name.lowercase().contains(needle)) {
                            found.trySend(child)
                        }
                    }
                    if (scanned.addAndGet(children.size) >= SEARCH_SCAN_LIMIT) {
                        truncated.set(true)
                        dirs.close()
                    }
                    if (pending.decrementAndGet() == 0) dirs.close()
                }
            }
        }

        // 单点发布：worker 只往 `found` 里投，状态更新全部由这一条在 Main 上的协程做。
        // 这样 `deepHits` 只有一个写入者，不需要为它加锁；也让"多久刷一次界面"
        // 变成一个可以调的常数（[SEARCH_PUBLISH_MS]），而不是每个目录刷一次。
        val accumulated = ArrayList<FsEntry>()
        val publisher = launch {
            var lastPublish = 0L
            for (hit in found) {
                accumulated += hit
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublish >= SEARCH_PUBLISH_MS) {
                    lastPublish = now
                    publishSearch(accumulated, scanned.get(), visited.get(), truncated.get(), done = false)
                }
            }
        }

        walkers.joinAll()
        found.close()
        publisher.join()
        publishSearch(accumulated, scanned.get(), visited.get(), truncated.get(), done = true)
    }

    private fun publishSearch(
        hits: List<FsEntry>,
        scanned: Int,
        visitedDirs: Int,
        truncated: Boolean,
        done: Boolean,
    ) {
        deepHits = ArrayList(hits)
        _state.update { current ->
            // 搜索可能在遍历过程中被取消（用户清了输入框）——那时 `search` 已经是 null，
            // 这次发布就不再有任何意义，直接丢掉，别把结果又贴回一个不存在的搜索里。
            val search = current.search ?: return@update current
            current.copy(
                search = search.copy(
                    running = !done,
                    scanned = scanned,
                    visitedDirs = visitedDirs,
                    truncated = truncated,
                ),
            )
        }
        deriveEntries()
    }

    // ── 偏好 ────────────────────────────────────────────────────────────

    fun setViewMode(mode: FileViewMode) {
        viewModelScope.launch { settings.setViewMode(mode) }
    }

    /** 点击某一列：同列翻转方向，异列切列并回到升序。 */
    fun sortBy(field: FsSortField) {
        viewModelScope.launch { settings.setSortSpec(_state.value.sort.toggled(field)) }
    }

    fun toggleSortDirection() {
        val sort = _state.value.sort
        viewModelScope.launch { settings.setSortSpec(sort.copy(ascending = !sort.ascending)) }
    }

    fun toggleShowHidden() {
        viewModelScope.launch { settings.setShowHidden(!_state.value.showHidden) }
    }

    // ── 写操作 ──────────────────────────────────────────────────────────

    /** 新建文件夹；重名自动改为「新建文件夹 (1)」，与资源管理器一致。 */
    fun createFolder(name: String) {
        val dir = _state.value.location ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        runWrite({ ops().createDirectoryUnique(dir, trimmed) }) { created ->
            _messages.tryEmit(BrowserMessage.Created(created.displayName))
        }
    }

    fun rename(path: FsPath, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == path.displayName) return
        runWrite({ ops().rename(path, trimmed) }) { renamed ->
            _messages.tryEmit(BrowserMessage.Renamed(renamed.displayName))
        }
    }

    fun delete(paths: List<FsPath>) {
        if (paths.isEmpty()) return
        runWrite({ ops().deleteAll(paths) }) { failures ->
            _messages.tryEmit(BrowserMessage.Deleted(paths.size - failures.size, failures.size))
        }
    }

    /** 把选中项放进剪切板（[cut] 决定是移动还是复制）。不真正搬运，粘贴时才动手。 */
    fun clipboardSelect(paths: List<FsPath>, cut: Boolean) {
        if (paths.isEmpty()) return
        _state.update {
            it.copy(clipboard = ClipboardState(paths, cut, it.location))
        }
        _messages.tryEmit(BrowserMessage.ClipboardSet(paths.size, cut))
        exitSelection()
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null) }
    }

    /**
     * 粘贴到当前目录。
     *
     * 冲突策略固定为 [ConflictPolicy.KEEP_BOTH]：M1 不弹冲突对话框，
     * 因为"两者都保留"是唯一**不会丢数据**的默认值——其余两种都需要用户
     * 明确知道自己要覆盖什么，那是 M5 的交互。
     *
     * ## M6：进度、取消与"切走也不中断"
     * 这一段现在有三件此前没有的保障，缺任何一件用户都会立刻感觉到：
     *
     * | 保障 | 缺了它的现象 |
     * |---|---|
     * | 进度推给 [TransferCenter] | 点了粘贴之后界面**毫无反应**，几 GB 的目录像卡死 |
     * | 前台服务 + 进度通知 | 切到别的应用后进程被回收，目录里留下**半截文件** |
     * | 可取消（[TransferCancelToken]） | 点错了只能等它跑完，或者强杀应用 |
     */
    fun paste() {
        val target = _state.value.location ?: return
        val clipboard = _state.value.clipboard ?: return

        // 一次只允许一次搬运。放行第二次的后果不是"排队"，而是**两条传输同时写同一个目标目录**
        // —— 重名策略各自算各自的，最终会落地两份内容相同的副本，且用户完全不知道多出来的那份从哪来。
        if (TransferCenter.state.value != null) {
            _messages.tryEmit(BrowserMessage.TransferBusy)
            return
        }

        val token = TransferCancelToken()
        val kind = if (clipboard.cut) TransferKind.MOVE else TransferKind.COPY
        TransferCenter.begin(kind, clipboard.paths.size, token)
        // 顺序不能反：必须在**应用仍处于前台**时拉起前台服务（Android 12+ 的硬约束），
        // 而 `paste()` 的直接调用者就是一次点击。
        TransferForegroundService.start(getApplication())

        runWrite({ transferInto(target, clipboard, token) }) { result ->
            // 收尾必须在 onDone 里做，而不是在传输循环内部：循环有几条出口
            // （正常结束 / 取消 / 单个失败），任何一条漏掉收尾都会留下一条
            // 永远不消失的常驻通知。
            TransferCenter.finish(token)
            TransferForegroundService.stop(getApplication())

            // 移动全部成功后清空剪切板，避免同一批文件被搬第二次。
            // 取消的情况**不**清空：用户还可能要再试一次。
            if (clipboard.cut && result.second == 0 && !token.isCancelled) {
                _state.update { it.copy(clipboard = null) }
            }
            _messages.tryEmit(
                if (token.isCancelled) {
                    BrowserMessage.PasteCancelled(result.first)
                } else {
                    BrowserMessage.Pasted(result.first, result.second, clipboard.cut)
                },
            )
        }
    }

    /**
     * @return `成功数 to 失败数`。单个失败不中断其余，最后一次性汇总提示。
     *
     * 取消被当作**正常结果**处理（跳出循环、返回已完成的数量），而不是当成异常往外抛：
     * 抛出去的话 `runWrite` 会走进失败分支，`onDone` 就永远不执行——收尾（撤通知、
     * 清进度）随之丢失。把"取消"建模成业务结果，收尾就只有一条路径。
     */
    private suspend fun transferInto(
        target: FsPath,
        clipboard: ClipboardState,
        token: TransferCancelToken,
    ): Pair<Int, Int> {
        var ok = 0
        var failed = 0
        for ((index, path) in clipboard.paths.withIndex()) {
            if (token.isCancelled) break
            // 先亮出"要搬的是哪一项"。大小此刻还不知道（`FsOps` 内部才 stat），
            // 所以先给未知；第一次进度回调就会把它补上——不必为了一个进度条
            // 先对每一项多跑一次 stat（在 NAS 上那是每一项一个往返）。
            TransferCenter.enterItem(index, path.displayName, TransferProgress.UNKNOWN_SIZE)
            try {
                if (clipboard.cut) {
                    ops().move(
                        src = path,
                        destDir = target,
                        onProgress = TransferCenter::onBytes,
                        isCancelled = token::isCancelled,
                    )
                } else {
                    ops().copy(
                        src = path,
                        destDir = target,
                        onProgress = TransferCenter::onBytes,
                        isCancelled = token::isCancelled,
                    )
                }
                ok++
            } catch (_: FsCancelledException) {
                break
            } catch (_: FsException) {
                failed++
            }
        }
        return ok to failed
    }

    // ── 桌面快捷方式 ────────────────────────────────────────────────────

    /** 请求把某个目录钉到桌面；Activity 会先弹改名框，再回调 [pinToDesktop]。 */
    fun requestPinDirectory(path: FsPath) {
        _requests.tryEmit(BrowserRequest.PinDirectory(path, path.displayName))
    }

    /**
     * 真正创建快捷方式。
     *
     * [ShortcutTarget.path] 存的是 **`FsPath.toString()` 的完整文本**（`local:///…`），
     * 而不是裸路径。理由见 `FsPath` 的冻结契约：`toString`/`parse` 往返无损，
     * 于是 `fsId` / `path` / 显示名三者能各自独立演化，且 SMB 的 authority（连接 id）
     * 不会在往返中丢失。
     */
    fun pinToDesktop(path: FsPath, label: String) {
        val context = getApplication<Application>()
        val name = label.trim().ifEmpty { path.displayName }
        val target = ShortcutTarget(
            fsId = path.scheme,
            path = path.toString(),
            displayName = name,
        )
        val result = runCatching { FolderShortcuts.requestCreate(context, target) }
            .getOrElse { ShortcutResult.Failed(it.message ?: it.javaClass.simpleName) }
        when (result) {
            ShortcutResult.PinRequested, ShortcutResult.LegacyRequested ->
                _messages.tryEmit(BrowserMessage.Pinned(name))

            ShortcutResult.Unsupported -> _messages.tryEmit(BrowserMessage.PinUnsupported)

            is ShortcutResult.Failed -> _messages.tryEmit(BrowserMessage.PinFailed(result.reason))
        }
    }

    // ── 权限请求（发给 Activity 去跳系统页）─────────────────────────────

    fun requestAllFilesAccess() {
        _requests.tryEmit(BrowserRequest.GrantAllFilesAccess)
    }

    fun requestRuntimePermission() {
        _requests.tryEmit(BrowserRequest.GrantRuntimePermission)
    }

    fun requestSafDirectory() {
        _requests.tryEmit(BrowserRequest.PickSafDirectory)
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    private fun observeSettings() {
        viewModelScope.launch {
            settings.viewMode.collect { mode -> _state.update { it.copy(viewMode = mode) } }
        }
        viewModelScope.launch {
            settings.sortSpec.collect { spec ->
                _state.update { it.copy(sort = spec) }
                deriveEntries()
            }
        }
        viewModelScope.launch {
            settings.showHidden.collect { show ->
                _state.update { it.copy(showHidden = show) }
                deriveEntries()
            }
        }
        // 收藏（M7.12）：持久层一变就同步进状态。quickAccess 是它的渲染形态
        // （侧栏「快速访问」分组），收藏路径本身另存一份供多选栏判断去留。
        viewModelScope.launch {
            settings.favorites.collect { paths ->
                _state.update {
                    it.copy(
                        favorites = paths,
                        quickAccess = paths.map { p -> FsEntry(path = p, kind = FsKind.DIRECTORY) },
                    )
                }
            }
        }
    }

    /**
     * 收藏 / 取消收藏一个文件夹（M7.12，幂等切换）。
     *
     * 三个入口共用这一个开关：多选栏「收藏」、行尾「⋯」菜单、侧栏节点**长按**。
     * 写入走 DataStore，状态由 [observeSettings] 里的 collect 回灌——这里只发
     * 结果消息，不直接改 [BrowserUiState]，避免双写打架。
     */
    fun toggleFavorite(path: FsPath) {
        viewModelScope.launch {
            val name = path.displayName
            val isFavorite = settings.favorites.first().any { it == path }
            if (isFavorite) {
                settings.removeFavorite(path)
                _messages.tryEmit(BrowserMessage.FavoriteRemoved(name))
            } else {
                settings.addFavorite(path)
                _messages.tryEmit(BrowserMessage.FavoriteAdded(name))
            }
        }
    }

    private suspend fun reload(path: FsPath) {
        try {
            val fs = FileSystems.registry.resolve(path)
            // 性能探针：M1 的验收标准是"5000 文件目录首屏 ≤ 300 ms"，
            // 而这条路径是首屏耗时的全部来源（排序与映射都在内存里，毫秒级）。
            // 用 debug 级日志常驻，回归时 `adb logcat -s MimiPerf` 就能看到。
            val startedAt = SystemClock.elapsedRealtime()
            val listed = fs.list(path)
            Log.d(
                PERF_TAG,
                "list ${path} -> ${listed.size} items in " +
                    "${SystemClock.elapsedRealtime() - startedAt} ms",
            )
            rawEntries = listed
            _state.update {
                it.copy(
                    location = path,
                    // 卷根的 displayName 是设备名（"emulated"/"0"），必须换成侧栏用的
                    // "内部存储"这类人类可读标签
                    empty = if (listed.isEmpty()) EmptyKind.EMPTY else EmptyKind.NONE,
                    errorText = null,
                )
            }
            deriveEntries()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = t.toFsException(path)
            if (failure is FsCancelledException) return
            // 失败必须留痕。
            //
            // 在加上这一行之前，列举失败在**任何地方都看不到原因**：界面只显示固定的
            // 「无法读取此位置 / 请检查权限或稍后重试」，`Log.d(PERF_TAG)` 只在成功时打，
            // 而 `MimiLog` 也被绕过了。于是"切到网络位置读不出来"这类问题只能靠反复重试
            // 去猜——M2 实测就卡在这里：SMB 目录**第二次**访问失败，日志里却一条记录都没有，
            // 既不知道是会话失效、权限被拒还是路径不对。
            MimiLog.w(
                FS_TAG,
                "list $path failed: ${failure.javaClass.simpleName}: ${failure.message}",
            )
            rawEntries = emptyList()
            _state.update {
                it.copy(
                    location = path,
                    entries = emptyList(),
                    empty = when (failure) {
                        is FsAccessDeniedException -> EmptyKind.NO_PERMISSION
                        // 目录可能刚被别的进程删掉：当作空目录，不弹错
                        is FsNotFoundException -> EmptyKind.EMPTY
                        else -> EmptyKind.ERROR
                    },
                    errorText = failure.message,
                )
            }
        }
    }

    /**
     * 从 [rawEntries]（+ 搜索时的 [deepHits]）按当前偏好派生列表。
     * 偏好变化、列举完成、搜索进度更新都走这里。
     *
     * ## 空态为什么也在这里定
     * 它以前只由 `reload()` 在列举成功/失败时设置，于是"搜出来零条"会落进
     * "NONE"——界面既不显示空态、列表又是空的，用户看到一片空白，
     * 分不清是"没匹配上"还是"卡住了"。空态是**列表内容的函数**，
     * 就该和列表在同一处计算。
     */
    private fun deriveEntries() {
        val current = _state.value
        val visible = if (current.showHidden) rawEntries else rawEntries.filterNot { it.isHidden }
        val searching = current.search?.active == true
        val base = if (searching) {
            visible + deepHits.filterNot { it.isHidden }
        } else {
            visible
        }
        val entries = base.sortedBySpec(current.sort)
        // 加载中/出错/无权限这三态由列举路径决定，这里不许覆盖——否则一次偏好变化
        // 就能把"正在读取"变成"文件夹为空"。
        val empty = when (current.empty) {
            EmptyKind.LOADING, EmptyKind.ERROR, EmptyKind.NO_PERMISSION -> current.empty
            else -> when {
                current.location == null -> current.empty
                entries.isNotEmpty() -> EmptyKind.NONE
                searching -> EmptyKind.NO_MATCH
                else -> EmptyKind.EMPTY
            }
        }
        _state.update { it.copy(entries = entries, empty = empty) }
    }

    private fun publishAccess(access: com.miyuetech.mimifiles.core.fslocal.StorageAccess) {
        _state.update {
            it.copy(localRoots = access.roots, tier = access.tier, grantCount = access.grants.size)
        }
    }

    /**
     * 跟着连接库走：连接一增删改，侧栏与首页的"网络位置"立刻跟着变。
     *
     * 订阅而不是"每次进首页重新查一遍"：连接是可以在另一个页面（连接管理）里被改的，
     * 而用户改完返回浏览页时我们并不会收到任何回调。用 Flow 就没有"返回后忘了刷新"
     * 这个可能——这是 M1 定下的数据流约定的直接收益。
     *
     * 与 [publishAccess] 的本地根各管一半：本机根只在权限变化时动，网络根只在
     * 数据库变化时动，两者互不触发对方的 IO。
     */
    private fun observeConnections() {
        val repository = Backends.connections(getApplication())
        viewModelScope.launch {
            repository.profiles.collect { profiles ->
                val entries = profiles.map { it.toRootEntry() }
                _state.update { it.copy(networkRoots = entries) }
                // 正停在「位置」首页时，列表展示的就是 roots，必须重建；
                // 已经在某个目录里则不动——用户的位置不该因为他处新增了一条连接而跳走。
                if (_state.value.isHome) showHome()
            }
        }
    }

    /**
     * 连接 → 导航树/首页条目。
     *
     * 路径用 [ConnectionProfile.rootFsPath]，也就是**挂载点本身**，而不是
     * `scheme://id` 的根：SMB 的根是"共享列表"，而用户配连接时已经把共享名填进去了，
     * 点进去应该直接看到他配的那个共享，而不是再选一次。
     */
    private fun ConnectionProfile.toRootEntry(): FsEntry = FsEntry(
        path = rootFsPath(),
        name = displayName.ifBlank { endpointLabel },
        kind = FsKind.DIRECTORY,
    )

    private fun ops(): FsOps = ops ?: FsOps(FileSystems.registry)

    /**
     * 写操作的统一包装。
     *
     * 三件事一次性保证：**跑在 IO 线程**、**成功后一定刷新目录**、**失败一定变成一条提示**。
     * 抽出来的价值在第二条——"重命名后列表还是旧名"这类问题全部来自某个分支忘了刷新。
     * [onDone] 在 IO 段结束后于主线程执行，于是所有 `_state` 变更都留在主线程。
     */
    private fun <T> runWrite(block: suspend () -> T, onDone: (T) -> Unit = {}) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val failure = _state.value.location?.let { t.toFsException(it) }
                if (failure !is FsCancelledException) {
                    // 写操作失败同样必须留痕。界面上的表现只有一条一闪而过的提示条
                    // （而且它恰好是 `uiautomator` 抓不到的那种视图），排障时等于没有信息。
                    // M2 实测就吃过这个亏：重命名在服务端已经成功、应用却判成失败，
                    // 而"为什么失败"在任何地方都看不到。
                    MimiLog.w(
                        FS_TAG,
                        "write at ${_state.value.location} failed: " +
                            "${failure?.javaClass?.simpleName}: ${failure?.message}",
                    )
                    _messages.tryEmit(BrowserMessage.Failed(failure?.message ?: t.message.orEmpty()))
                }
                return@launch
            }
            onDone(result)
            refresh()
        }
    }

    private companion object {
        /** 性能探针的 logcat tag：`adb logcat -s MimiPerf`。 */
        const val PERF_TAG = "MimiPerf"

        /** 文件系统失败的 tag：`adb logcat -s MimiFs`。与网络后端的 MimiSmb / MimiDav 并列。 */
        const val FS_TAG = "MimiFs"

        /** 输入停顿多久才开始深搜。400 ms 是"打字节奏"与"NAS 上重读一棵树"之间的折中。 */
        const val SEARCH_DEBOUNCE_MS = 400L

        /** 深搜最多访问多少条目录项。到顶就停并把 `truncated` 置位（宁可说清边界，不假装搜完）。 */
        const val SEARCH_SCAN_LIMIT = 20_000

        /** 结果发布的节流间隔。改这个值只影响"结果滚进来的观感"，不影响正确性。 */
        const val SEARCH_PUBLISH_MS = 250L

        /**
         * 「返回上级后落位」的高亮保留时长。
         *
         * 太短（<1s）用户还没看清就没了；太长会让人以为这一行是"选中/剪切中"。
         * 1.6s 大约等于"看到 → 确认是它 → 手指开始动"的一拍。
         */
        const val REVEAL_HIGHLIGHT_MS = 1600L

        /** 本机后端的列举并发。本机 `list()` 是一次 syscall 级别的操作，开 4 条不构成压力。 */
        const val LOCAL_WORKERS = 4

        /**
         * 网络后端的列举并发。
         *
         * 取 2 而不是 4（方案 §8.3 给 SMB 的上限）：那个 4 是**数据传输**的上限，
         * 而深搜会与预览/播放同时进行。把额度全给列举，会出现"搜着搜着视频卡住"。
         */
        const val NETWORK_WORKERS = 2
    }
}
