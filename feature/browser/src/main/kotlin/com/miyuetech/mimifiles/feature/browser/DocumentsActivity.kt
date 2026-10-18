package com.miyuetech.mimifiles.feature.browser

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.common.Authorities
import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fslocal.StorageAccessManager
import com.miyuetech.mimifiles.core.fslocal.StorageTier
import com.miyuetech.mimifiles.core.model.FileViewMode
import com.miyuetech.mimifiles.core.model.FsEntry
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.model.FsSortField
import com.miyuetech.mimifiles.core.preview.PreviewArgs
import com.miyuetech.mimifiles.core.preview.PreviewEnvironment
import com.miyuetech.mimifiles.core.shortcut.FolderShortcuts
import com.miyuetech.mimifiles.core.ui.FeatureRoutes
import com.miyuetech.mimifiles.core.ui.FileLabels
import com.miyuetech.mimifiles.core.ui.FileListAdapter
import com.miyuetech.mimifiles.core.ui.FileRowItem
import com.miyuetech.mimifiles.core.ui.GridSpec
import com.miyuetech.mimifiles.core.ui.MultiSelectBar
import com.miyuetech.mimifiles.core.ui.RootSection
import com.miyuetech.mimifiles.core.ui.ThumbnailProvider
import com.miyuetech.mimifiles.core.ui.applySystemBarIconAppearance
import com.miyuetech.mimifiles.core.ui.enableEdgeToEdge
import com.miyuetech.mimifiles.core.ui.isNightModeActive
import com.miyuetech.mimifiles.core.ui.padForSystemBars
import com.miyuetech.mimifiles.feature.browser.databinding.ActivityDocumentsBinding
import com.miyuetech.mimifiles.feature.browser.databinding.DialogInputBinding
import com.miyuetech.mimifiles.feature.browser.databinding.DialogTransferProgressBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.miyuetech.mimifiles.core.ui.R as CoreR

/**
 * 文件浏览主界面 —— 竖屏单栏 / 横屏与平板左树右表。
 *
 * ## 这一层只做四件事
 * 1. **渲染** [BrowserViewModel.state]；
 * 2. 把 [BrowserMessage] 变成 Toast（文案在本层，ViewModel 不产出中文）；
 * 3. 执行 [BrowserRequest]（跳系统权限页、拉起目录选择器、弹对话框）；
 * 4. 平台适配（系统栏 insets、抽屉、返回栈）。
 *
 * 任何"改数据"的动作都不在这里做，全部转调 ViewModel —— 这样旋转屏幕、
 * 进程重建之后不会出现"界面改了但数据没改"的错位。
 *
 * ## 布局只有一份
 * 竖屏与横屏共用 `activity_documents.xml`，差异由 `@bool/two_pane` 驱动：
 * `false` 时导航窗格收起为抽屉，`true` 时抽屉被锁定为常开、成为静态左树。
 * 因此这里没有"两套列表逻辑"，只有一处 [setupPanes] 的分支。
 *
 * ## 关于 `CoreR`
 * 工程开了 `android.nonTransitiveRClass=true`，本模块的 `R` 只含自己的资源，
 * 引用 `:core:ui` 的语义色/图标/尺寸时必须走它自己的 `R`。别名一次，
 * 免得满屏全限定名。
 */
class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding

    private val viewModel: BrowserViewModel by viewModels()

    private lateinit var adapter: FileListAdapter
    private lateinit var storageManager: StorageAccessManager

    /**
     * 最近一次渲染的状态。
     *
     * 对话框与菜单回调需要"当前选中了什么"，而它们不在 collect 的栈里。
     * 直接读 `viewModel.state.value` 也可以，但那等于在两层之间开了第二个真相入口；
     * 保留一份渲染快照，能保证回调看到的就是用户看到的那一屏。
     */
    private var rendered = BrowserUiState()

    /** 是否为双栏形态（横屏 / 平板），由资源限定符决定，运行期不变。 */
    private var twoPane = false

    /** 首次 `onResume` 不重复探测权限（`onCreate` 已经探过一轮）。 */
    private var firstResume = true

    // ── 搬运进度对话框 ──────────────────────────────────────────────────

    /**
     * 进度对话框与其 binding。
     *
     * 保留 binding 而不是每次刷新都重新 `findViewById`：进度更新在传输期间
     * 可能每秒好几次，而三个控件都要改。
     */
    private var transferDialog: AlertDialog? = null
    private var transferBinding: DialogTransferProgressBinding? = null

    /**
     * 用户是否已经把进度对话框「隐藏」掉了。
     *
     * 语义刻意做成"对**本次搬运**的隐藏"而不是"以后都别再弹"：
     * 下一次搬运必须有新的对话框，否则用户会以为功能失效了。
     * 因此它在进度归空（一次搬运结束）时被清零，并跨屏幕旋转保留（`onSaveInstanceState`）
     * ——旋转时把用户刚关掉的弹窗又弹回来是最容易被吐槽的一类细节。
     */
    private var transferHidden = false

    /**
     * 抑制"关闭即视为隐藏"。
     *
     * `AlertDialog` 的关闭不区分"用户点了隐藏/点到了外面"与"程序自己关的"，
     * 而这两者的语义正好相反（前者要记住隐藏，后者不能）。用一个只在
     * 程序主动关闭期间置位的标志区分，比去猜是哪个按钮触发的可靠。
     */
    private var suppressHideOnDismiss = false

    /** 搜索菜单项（M6 · P1-5）。判"要不要先收起搜索"时要用它，见 [exitSearch]。 */
    private var searchItem: MenuItem? = null

    // ── 平台结果回调 ────────────────────────────────────────────────────

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshStorageAccess()
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 这个页面没有可靠的 resultCode，回到前台即重新探测一次
            viewModel.refreshStorageAccess()
        }

    /**
     * 目录授权用的是裸 `StartActivityForResult` 而不是 `OpenDocumentTree` 契约。
     *
     * 原因：`SafGrantStore.add` 需要结果 Intent 的 **flags** 才能判断这个目录能不能写，
     * 而 `OpenDocumentTree` 契约只回传 Uri、把 flags 丢掉了。为了拿到写权限位，
     * 这里自己拼 Intent、自己读 `data.flags`。
     */
    private val safTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uri = data?.data
            if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
            viewModel.registerSafGrant(uri, data.flags)
        }

    // ── 生命周期 ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storageManager = StorageAccessManager(this)
        twoPane = resources.getBoolean(CoreR.bool.two_pane)
        transferHidden = savedInstanceState?.getBoolean(STATE_TRANSFER_HIDDEN) ?: false

        setupInsets()
        setupPanes()
        setupToolbar()
        setupBreadcrumb()
        setupList()
        setupTree()
        setupNavEntries()
        setupSelectBar()
        setupBackHandling()
        observeViewModel()
        observeTransfer()

        binding.permissionAction.setOnClickListener { requestStorageAccess() }
        binding.permissionSaf.setOnClickListener { viewModel.requestSafDirectory() }

        applySystemBarIconAppearance(lightBackground = !isNightModeActive())

        viewModel.initialize(readLaunchTarget())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TRANSFER_HIDDEN, transferHidden)
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        // 用户可能绕过应用、直接从系统设置里改了权限
        viewModel.refreshStorageAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // `singleTask`：从桌面图标再点进来不会重建 Activity，只走这里
        readLaunchTarget()?.let(viewModel::initialize)
    }

    // ── 初始化 ──────────────────────────────────────────────────────────

    /**
     * 系统栏适配。
     *
     * 内容列与导航窗格各吃一次 insets：它们是两个独立的区域，
     * 只给根视图加 padding 会让抽屉里的树钻进状态栏底下。
     */
    private fun setupInsets() {
        binding.contentRoot.padForSystemBars(top = true, bottom = true)
        binding.navPane.padForSystemBars(top = true, bottom = true)
    }

    private fun setupPanes() {
        if (!twoPane) return
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN, binding.navPane)
        // ⚠️ 必须同时把**触摸**放行。
        //
        // 原生 DrawerLayout 判断"点内容区该关闭抽屉"用的是 `onScreen`
        // （抽屉露出屏幕的比例），常开的抽屉恒为 1.0，于是它会把内容列上的每一个
        // ACTION_DOWN 都当成"点在遮罩上"截走；而锁定态下它自己又不做任何处理 ——
        // 结果就是右半边列表看得见、点不动、也滑不动（真机反馈第 4 条）。
        // 详见 TwoPaneDrawerLayout 的类注释。
        binding.drawerLayout.allowTouchPassthrough = true
        // 常开时不该有遮罩与投影——那是"抽屉被拉开"的视觉，会让人以为能关掉
        binding.drawerLayout.setScrimColor(Color.TRANSPARENT)
        binding.drawerLayout.setDrawerElevation(0f)
        // DrawerLayout 的抽屉是覆盖在内容之上的。锁定常开后必须让内容主动让出窗格宽度，
        // 否则左树会盖住列表最左一列（这是把抽屉当静态窗格用时唯一的额外处理）。
        val paneWidth = resources.getDimensionPixelSize(CoreR.dimen.nav_pane_width)
        (binding.contentRoot.layoutParams as ViewGroup.MarginLayoutParams).marginStart = paneWidth
        binding.contentRoot.requestLayout()
    }

    private fun setupToolbar() {
        // 竖屏：左侧汉堡 → 拉出抽屉（左树）；横屏：左树常显，左侧就是「上一级」。
        // 把「上一级」放到右侧第一个动作位（竖屏）是资源管理器的惯例，
        // 也让两种形态下"回到上层"的位置都在最容易点的地方。
        binding.toolbar.setNavigationOnClickListener {
            if (twoPane) viewModel.goUp() else binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.toolbar.inflateMenu(R.menu.browser)
        binding.toolbar.setOnMenuItemClickListener(::onToolbarItem)
        setupSearch()
    }

    /**
     * 搜索入口（M6 · P1-5）。
     *
     * ## 为什么用代码建 `SearchView`，而不是在 XML 里写 `app:actionViewClass`
     * `actionViewClass` 是把类名当**字符串**交给 AppCompat 反射构造，与方案 §12.5
     * 记下的那类静默失效（M5.1 的 MBassador）是同一个形状：编译期全绿，
     * 只有真机上"点了没反应"。这里 `SearchView(this)` 是直接引用，构造器一定活得下来。
     *
     * ## 为什么进度显示在 Toolbar 的副标题上，而不是状态栏
     * 状态栏（`status_bar`）只在**双栏形态**（横屏/平板）可见。手机竖屏是搜索最主要的
     * 使用场景，把"正在搜索 / 已扫描多少"放进一个竖屏根本看不见的控件里等于没有反馈 ——
     * 用户会以为搜索没反应，然后反复点。副标题在两种形态下都在。
     */
    private fun setupSearch() {
        val item = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        searchItem = item
        val view = SearchView(this).apply {
            queryHint = getString(R.string.browser_search_hint)
            // 搜索是**边打边搜**的：多一颗要用户再点一次的提交箭头，只会挡住结果。
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    viewModel.updateSearchQuery(query.orEmpty())
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.updateSearchQuery(newText.orEmpty())
                    return true
                }
            })
        }
        item.actionView = view
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                // 收起 = 退出搜索。**不能**只收起控件而留着关键词——那样列表会被一个
                // 用户已经看不见的词继续过滤，现象是"关掉搜索之后文件夹里少了一半文件"。
                viewModel.exitSearch()
                return true
            }
        })
    }

    /**
     * 退出搜索。
     *
     * 先收控件再兜底：控件展开时 `collapseActionView()` 会回调
     * [MenuItem.OnActionExpandListener.onMenuItemActionCollapse]，那条路上已经调过
     * `exitSearch`；控件没展开时（转屏重建、或从桌面快捷方式直接落到某个目录）
     * 不会发回调，而 VM 里可能仍有搜索态，所以这里必须**无条件**再调一次。
     */
    private fun exitSearch() {
        val item = searchItem
        if (item != null && item.isActionViewExpanded) {
            item.collapseActionView()
        } else {
            viewModel.exitSearch()
        }
    }

    private fun setupBreadcrumb() {
        binding.breadcrumb.onSegmentClick = { path -> viewModel.openPath(path) }
    }

    /**
     * 预览能力（类型探测 / 缩略图 / 落盘缓存）。
     *
     * 用 `by lazy` 而不是在字段初始化时就建：它本身只创建目录对象、不产生 IO，
     * 但**延迟到第一次真正需要**（列表要出缩略图时）能保证冷启动路径上不多做任何事
     * ——这与 M1 定下的"首屏不做无谓工作"是同一条规则。
     */
    private val previews: PreviewEnvironment by lazy {
        PreviewEnvironment.of(this, Authorities.DOCUMENTS)
    }

    private fun setupList() {
        adapter = FileListAdapter().apply {
            onItemClick = { item, _ -> viewModel.openEntry(item.entry) }
            onItemLongClick = { item, _ ->
                if (rendered.selectionMode) {
                    viewModel.toggleSelection(item.path)
                } else {
                    viewModel.enterSelection(item.path)
                }
                true
            }
            onMoreClick = { item, anchor -> showRowMenu(item, anchor) }
            // M3：列表缩略图。用 `ThumbnailProvider` 这一层薄接口把"怎么解码"挡在 UI 之外
            // （`:core:ui` 因此不需要依赖带 Media3 的 `:core:preview`）。
            // 传 null 就退回纯类型图标——这是"预览模块缺席时列表依然完全可用"的保证。
            thumbnails = ThumbnailProvider { entry, edgePx -> previews.thumbnails.load(entry, edgePx) }
            thumbnailScope = lifecycleScope
        }
        binding.fileList.adapter = adapter
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.setHasFixedSize(true)

        // 列头即排序按钮：点当前列翻转方向，点其他列切过去
        binding.detailsHeader.headerName.setOnClickListener { viewModel.sortBy(FsSortField.NAME) }
        binding.detailsHeader.headerSize.setOnClickListener { viewModel.sortBy(FsSortField.SIZE) }
        binding.detailsHeader.headerTime.setOnClickListener { viewModel.sortBy(FsSortField.MODIFIED) }
        binding.detailsHeader.headerType.setOnClickListener { viewModel.sortBy(FsSortField.TYPE) }
    }

    private fun setupTree() {
        binding.navTree.onNodeClick = { path ->
            viewModel.openPath(path)
            closeDrawerIfOpen()
        }
        // 树只负责展示，加载由宿主完成——View 不该持有协程作用域
        binding.navTree.onRequestChildren = { parent -> loadTreeChildren(parent) }
        // 侧栏长按 = 收藏/取消收藏（M7.12）：侧栏是唯一同时能看到收藏与
        // 未收藏目录的地方，长按即切换；结果由 toast 反馈。
        binding.navTree.onNodeLongClick = { path -> viewModel.toggleFavorite(path) }
    }

    /**
     * 导航窗格底部的三个功能入口：无线分享 / 网络连接 / 设置（M7.2 起）。
     *
     * 无线分享与多选动作走**同一个** [openSharePanel]；网络连接与设置此前
     * 藏在溢出菜单里，M7.2 挪到侧栏底部后入口唯一化，菜单里的分支已删。
     */
    private fun setupNavEntries() {
        binding.navShare.setOnClickListener {
            openSharePanel()
            closeDrawerIfOpen()
        }
        binding.navConnections.setOnClickListener {
            if (!FeatureRoutes.openConnections(this)) {
                toast(getString(R.string.browser_connections_unavailable))
            }
            closeDrawerIfOpen()
        }
        binding.navSettings.setOnClickListener {
            if (!FeatureRoutes.openSettings(this)) {
                toast(getString(R.string.browser_settings_unavailable))
            }
            closeDrawerIfOpen()
        }
    }

    private fun setupSelectBar() {
        // M7.16 上/下双栏：顶栏发 SELECT_ALL（全选/取消选择）与关闭，
        // 底栏发其余全部操作动作 —— 两栏共用同一条 onSelectAction 分派。
        binding.selectBar.onClose = { viewModel.exitSelection() }
        binding.selectBar.onAction = { action -> onSelectAction(action) }
        binding.selectBottomBar.onAction = { action -> onSelectAction(action) }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        // 搜索优先于一切：搜索态下按返回 = 退出搜索，而不是"退回上一级"。
                        // 后者会让用户丢掉刚敲进去的关键词，而这一步他往往只是想
                        // 从搜索结果回到原来的列表。
                        //
                        // 注意这条分支不是"截图式补丁"：SearchView 展开且获得焦点时，
                        // 返回键会被它的 `onKeyPreIme` 先吃掉（那条路走
                        // onMenuItemActionCollapse → exitSearch）；这里管的是
                        // **控件已展开但没焦点**（用户点了列表又回来）的那一类。
                        rendered.search?.active == true -> exitSearch()

                        // ⚠️ 双栏形态下必须跳过抽屉分支。
                        // 锁为常开的抽屉 `isDrawerOpen` **恒为 true**，而 `closeDrawer`
                        // 对锁定的抽屉是空操作 —— 于是返回键会在这里被无声吃掉，
                        // 用户会发现"横屏/平板上返回键完全没用"。
                        !twoPane && binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                            binding.drawerLayout.closeDrawer(GravityCompat.START)

                        rendered.selectionMode -> viewModel.exitSelection()

                        !rendered.isHome -> viewModel.goUp()

                        else -> {
                            // 交回系统（退出应用）。先自禁用，否则会递归回到自己
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.messages.collect(::showMessage) }
                launch { viewModel.requests.collect(::handleRequest) }
            }
        }
    }

    // ── 搬运进度 ────────────────────────────────────────────────────────

    /**
     * 订阅搬运进度。
     *
     * 订阅的是 [TransferCenter] 而不是 ViewModel：进度还可能来自"用户切走之后
     * 又切回来"的那一次——那时 Activity 是新建的，而搬运一直在前台服务撑着的
     * 进程里跑。挂在 ViewModel 上就会漏掉这一段（`viewModelScope` 还在，
     * 但 `state` 流里根本没有搬运这一项）。
     *
     * 刻意用 `repeatOnLifecycle(STARTED)`：Activity 不可见时不该弹对话框
     * （Android 会抛 `BadTokenException`），而回到前台时必须立刻补上——这一条
     * 正好就是"隐藏之后还能再看到"的另一种形式。
     */
    private fun observeTransfer() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TransferCenter.state.collect(::renderTransfer)
            }
        }
    }

    private fun renderTransfer(progress: TransferProgress?) {
        if (progress == null) {
            // 一次搬运结束：忘掉"隐藏"，下一次搬运必须重新可见。
            transferHidden = false
            dismissTransferDialog()
            return
        }
        if (transferHidden) return
        val dialog = transferDialog ?: showTransferDialog(progress.kind) ?: return
        val view = transferBinding ?: return

        // 建对话框时（`showTransferDialog`）已经把标题设进 Builder 了，这里是**幂等**的补充：
        // 标题必须在 `show()` **之前**给，见 `showTransferDialog` 的注释。
        // 保留这一句是为了让"种类变了"这种（目前不可能的）情况也能自愈，
        // 而不是留着一条"只有第一次是对的"的隐式约定。
        dialog.setTitle(titleResOf(progress.kind))

        // 取消请求发出之后立刻改文案。用户点了「取消」还看到文件名在跳，会以为按钮没生效。
        view.transferName.text = if (progress.cancelRequested) {
            getString(R.string.browser_transfer_cancelling)
        } else {
            progress.currentName.ifEmpty { getString(R.string.browser_transfer_preparing) }
        }

        // `currentPercent < 0` 表示大小未知（目录搬运，或后端不给大小）。
        // 进度条切到不确定模式而不是画 0%——0% 会被读成"卡住了"。
        val percent = progress.currentPercent
        view.transferBar.isIndeterminate = percent < 0
        if (percent >= 0) view.transferBar.progress = percent

        view.transferDetail.text = if (percent >= 0) {
            getString(
                R.string.browser_transfer_dialog_progress_sized,
                progress.doneItems,
                progress.totalItems,
                percent,
                Formatters.size(progress.currentBytes),
                Formatters.size(progress.currentTotal),
            )
        } else {
            getString(
                R.string.browser_transfer_dialog_progress,
                progress.doneItems,
                progress.totalItems,
            )
        }

        // 取消按钮在"正在取消"期间不该还能再点（重复点没有额外效果，但会让用户
        // 怀疑自己第一次到底点到没有）。
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = !progress.cancelRequested
    }

    /** 搬运种类 → 标题文案。抽出来是为了让"建对话框"与"刷新对话框"两处用的是同一个来源。 */
    private fun titleResOf(kind: TransferKind): Int =
        if (kind == TransferKind.MOVE) {
            R.string.browser_transfer_dialog_title_move
        } else {
            R.string.browser_transfer_dialog_title_copy
        }

    /**
     * @param kind 只用来定标题。**必须在 `show()` 之前设进 Builder**，见下。
     * @return 新建的对话框；已经有一个在显示时返回 `null`。
     */
    private fun showTransferDialog(kind: TransferKind): AlertDialog? {
        if (transferDialog?.isShowing == true) return transferDialog
        val dialogBinding = DialogTransferProgressBinding.inflate(layoutInflater)
        transferBinding = dialogBinding

        val dialog = AlertDialog.Builder(this)
            // ⚠️ 标题必须在这里给，不能等 `show()` 之后再 `setTitle()`。
            // AppCompat 的 `AlertController.setupTitle()` 在 `installContent()` 时按
            // "此刻有没有标题"决定标题栏的去留：用的是 `setView()` 给的自定义内容、
            // 且 Builder 上没设标题 → 标题栏被判为"没有"，`mTitleView` 被
            // `setVisibility(GONE)`（**不是**移除，所以后面 setText 也不会报错）。
            // 之后再 `setTitle()` 只会改 `mTitle` 与那个已 GONE 的 TextView 的文字，
            // **永远不会让它重新可见**。
            // 实测证据（M6 取证截图 /tmp/m6-b2-evidence/screen-p2.png）：
            // 对话框里只有「bigfile-60mb.bin / 进度条 / 已完成 0 / 1 项 · 58%（35 MB / 60 MB）
            // / 隐藏后搬运继续…/ 隐藏 取消」，**没有「正在复制」这一行**。
            .setTitle(titleResOf(kind))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.browser_transfer_dialog_hide, null)
            .setPositiveButton(R.string.browser_transfer_dialog_cancel, null)
            .create()

        // 「隐藏」= 关掉对话框、**不停**搬运。所以不能给按钮传回调，
        // 让它走默认的"关闭"行为，再由 onDismiss 记下"用户选择隐藏"。
        dialog.setOnDismissListener {
            if (!suppressHideOnDismiss) transferHidden = true
            transferDialog = null
            transferBinding = null
        }
        // 点对话框外部与返回键同样是"隐藏"：这是 Android 上关弹窗的通用手势，
        // 用户不会预期它等于"取消传输"（那是一个不可逆的破坏性动作，
        // 必须由明确的按钮触发）。
        dialog.setCanceledOnTouchOutside(true)

        dialog.show()

        // 正按钮要自己接管点击：AlertDialog 的默认行为是"点击即关闭"，
        // 而这里需要**保持打开**并显示"正在取消…"，否则用户看不到任何反馈。
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            TransferCenter.requestCancel()
        }

        transferDialog = dialog
        return dialog
    }

    /** 程序主动关闭对话框（搬运结束 / 转屏重建）。 */
    private fun dismissTransferDialog() {
        val dialog = transferDialog
        transferDialog = null
        transferBinding = null
        if (dialog == null) return
        suppressHideOnDismiss = true
        runCatching { dialog.dismiss() }
        suppressHideOnDismiss = false
    }

    // ── 渲染 ────────────────────────────────────────────────────────────

    private fun render(state: BrowserUiState) {
        rendered = state
        renderToolbar(state)
        renderBreadcrumb(state)
        renderTree(state)
        renderList(state)
        renderSelection(state)
        renderStatus(state)
        renderEmpty(state)
        renderPermission(state)
    }

    private fun renderToolbar(state: BrowserUiState) {
        binding.toolbar.title =
            if (state.isHome) getString(R.string.browser_home_title) else titleOf(state)

        // 搜索进度：副标题在竖屏/横屏都可见（理由见 setupSearch）。
        binding.toolbar.subtitle = state.search?.takeIf { it.active }?.let { search ->
            buildString {
                append(getString(R.string.browser_search_progress, state.entries.size, search.scanned))
                if (search.running) append(' ').append(getString(R.string.browser_search_running))
                if (search.truncated) append(' ').append(getString(R.string.browser_search_truncated))
            }
        }

        // 导航图标随形态切换（见 setupToolbar 的说明）
        binding.toolbar.setNavigationIcon(
            if (twoPane) CoreR.drawable.ic_arrow_upward else CoreR.drawable.ic_menu,
        )
        binding.toolbar.navigationContentDescription =
            if (twoPane) {
                getString(CoreR.string.action_up)
            } else {
                getString(R.string.browser_action_storage)
            }
        // 首页没有"上一级"，把图标压暗比留着点了没反应清楚。
        // 注意这里改的是 Drawable 自己的 alpha（0~255），不是 View 的 alpha（0f~1f）。
        val upAlpha = if (state.isHome) 90 else 255
        binding.toolbar.navigationIcon?.alpha = if (twoPane) upAlpha else 255

        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_up)?.apply {
            isVisible = !twoPane
            icon?.alpha = upAlpha
        }
        // 搜索只在目录里有意义：首页（"位置"列表）只有几个卷根，往那里搜没有含义，
        // 而搜索框一旦出现，用户就会期望它灵。
        menu.findItem(R.id.action_search)?.apply { isVisible = !state.isHome }
        menu.findItem(R.id.action_new_folder)?.apply { isVisible = state.canWrite }
        menu.findItem(R.id.action_paste)?.apply {
            isVisible = state.canWrite
            isEnabled = state.clipboard != null
        }
        menu.findItem(R.id.action_select_all)?.apply { isVisible = state.entries.isNotEmpty() }
        menu.findItem(R.id.action_show_hidden)?.apply { isChecked = state.showHidden }
        menu.findItem(R.id.action_pin_current)?.apply { isVisible = !state.isHome }
        menu.findItem(R.id.action_view_mode)?.setIcon(viewModeIconRes(state.viewMode))
    }

    private fun renderBreadcrumb(state: BrowserUiState) {
        val location = state.location
        if (location == null) {
            binding.breadcrumb.visibility = View.GONE
            binding.breadcrumb.setPath(null)
            return
        }
        binding.breadcrumb.visibility = View.VISIBLE
        // 从**当前所在的存储卷**画起，而不是从路径根：后者会露出
        // `local › storage › emulated › 内部存储` 这种内部结构
        binding.breadcrumb.setPath(location, rootAncestorOf(state, location)) { segment ->
            labelOf(state, segment)
        }
    }

    private fun renderTree(state: BrowserUiState) {
        binding.navTree.setQuickAccess(state.quickAccess)
        // 分两组渲染：本机与网络。空组会被导航树自己省掉，
        // 所以没配连接时侧栏不会多出一个空标题。
        binding.navTree.setRootSections(
            listOf(
                RootSection(
                    id = "local",
                    title = getString(R.string.browser_nav_local),
                    entries = state.localRoots,
                ),
                RootSection(
                    id = "network",
                    title = getString(R.string.browser_nav_network),
                    entries = state.networkRoots,
                ),
            ),
        )
        binding.navTree.setCurrentPath(state.location)
        binding.navHint.visibility = if (state.roots.isEmpty()) View.VISIBLE else View.GONE
        binding.navSubtitle.text = when (state.tier) {
            null -> ""
            StorageTier.FULL -> getString(R.string.browser_permission_full)
            StorageTier.SAF_TREE -> getString(R.string.browser_permission_saf, state.grantCount)
            StorageTier.APP_PRIVATE -> getString(R.string.browser_permission_private)
        }
    }

    private fun renderList(state: BrowserUiState) {
        if (adapter.viewMode != state.viewMode) {
            adapter.viewMode = state.viewMode
            binding.fileList.layoutManager = layoutManagerFor(state.viewMode)
        }
        adapter.sortField = state.sort.field
        binding.detailsHeader.root.visibility =
            if (state.viewMode == FileViewMode.DETAILS) View.VISIBLE else View.GONE
        renderSortHeader(state)

        // 剪切中的条目半透明：让用户直观知道"这些一会儿会消失"
        val cutPaths = state.clipboard?.takeIf { it.cut }?.paths?.toHashSet().orEmpty()
        adapter.submitList(
            state.entries.map { entry ->
                FileRowItem(
                    entry = entry,
                    selected = entry.path in state.selection,
                    selectionMode = state.selectionMode,
                    pendingCut = entry.path in cutPaths,
                )
            },
        )
    }

    private fun renderSortHeader(state: BrowserUiState) {
        val active = ContextCompat.getColor(this, CoreR.color.primary)
        val idle = ContextCompat.getColor(this, CoreR.color.text_secondary)
        val header = binding.detailsHeader
        header.headerName.setTextColor(if (state.sort.field == FsSortField.NAME) active else idle)
        header.headerSize.setTextColor(if (state.sort.field == FsSortField.SIZE) active else idle)
        header.headerTime.setTextColor(if (state.sort.field == FsSortField.MODIFIED) active else idle)
        header.headerType.setTextColor(if (state.sort.field == FsSortField.TYPE) active else idle)
    }

    private fun renderSelection(state: BrowserUiState) {
        if (state.selectionMode) {
            // 收藏动作（M7.12）按**当前收藏状态**二选一插入：actionsFor 是纯静态
            // 推导（不知道收藏表），所以这步必须在宿主做。位置贴着「添加到桌面」
            // ——两个都是"单个目录的去处"类动作，视觉上归堆。
            val actions = MultiSelectBar.actionsFor(state.selectedEntries).toMutableList()
            state.selectedDirectories.singleOrNull()?.let { dir ->
                val favorite = if (dir.path in state.favorites) {
                    MultiSelectBar.Action.UNFAVORITE
                } else {
                    MultiSelectBar.Action.FAVORITE
                }
                val anchor = actions.indexOf(MultiSelectBar.Action.ADD_TO_HOME)
                if (anchor >= 0) actions.add(anchor + 1, favorite) else actions.add(favorite)
            }
            // M7.16 上/下双栏：顶栏=计数/全选/取消（选择状态），
            // 底栏=操作瓦片（最多 4 枚主操作 + 更多溢出）。
            binding.selectBar.show(count = state.selectedCount, total = state.entries.size)
            binding.selectBottomBar.show(actions)
        } else {
            binding.selectBar.hide()
            binding.selectBottomBar.hide()
        }
    }

    private fun renderStatus(state: BrowserUiState) {
        binding.statusBar.visibility = if (twoPane) View.VISIBLE else View.GONE
        val directories = state.entries.count { it.isDirectory }
        // 搜索态下状态栏说的是"这份结果是什么"，而不是"这个目录里有多少项"——
        // 后者在搜索时是**误导**：列表已经不是那个目录的内容了。
        binding.statusSummary.text = state.search?.takeIf { it.active }?.let { search ->
            buildString {
                append(getString(R.string.browser_search_progress, state.entries.size, search.scanned))
                if (search.running) append(' ').append(getString(R.string.browser_search_running))
                if (search.truncated) append(' ').append(getString(R.string.browser_search_truncated))
            }
        } ?: FileLabels.statusSummary(state.entries.size, directories, state.selectedCount)
        val clipboard = state.clipboard
        binding.statusClipboard.visibility = if (clipboard == null) View.GONE else View.VISIBLE
        if (clipboard != null) {
            binding.statusClipboard.text = getString(R.string.browser_status_clipboard, clipboard.count)
        }
    }

    private fun renderEmpty(state: BrowserUiState) {
        val show = state.empty != EmptyKind.NONE && state.entries.isEmpty()
        binding.emptyState.emptyRoot.visibility = if (show) View.VISIBLE else View.GONE
        binding.fileList.visibility = if (show) View.GONE else View.VISIBLE
        if (!show) return

        val titleRes = when (state.empty) {
            EmptyKind.LOADING -> CoreR.string.loading
            EmptyKind.EMPTY -> CoreR.string.empty_folder
            EmptyKind.NO_MATCH -> R.string.browser_empty_no_match
            EmptyKind.NO_PERMISSION -> CoreR.string.empty_no_permission
            EmptyKind.ERROR -> R.string.browser_empty_error
            EmptyKind.NONE -> return
        }
        // 失败时**优先显示具体原因**，而不是恒定的「请检查权限或稍后重试」。
        // `toFsException` 已经把它翻成了可读文案（"连接超时""用户名或密码不正确"
        // "路径不存在"…），只显示一句套话等于把最有用的信息丢掉——用户看完仍然不知道
        // 该改什么，排障时也拿不到任何线索（M2 实测就吃过这个亏：SMB 目录第二次访问失败，
        // 屏幕上和日志里都只有那一句套话）。
        val errorHint = state.errorText?.takeIf { state.empty == EmptyKind.ERROR && it.isNotBlank() }
        val hintRes = when {
            errorHint != null -> null
            state.empty == EmptyKind.EMPTY -> CoreR.string.empty_folder_hint
            state.empty == EmptyKind.NO_MATCH -> R.string.browser_empty_no_match_hint
            state.empty == EmptyKind.NO_PERMISSION -> R.string.browser_empty_home_hint
            state.empty == EmptyKind.ERROR -> R.string.browser_empty_error_hint
            else -> null
        }
        binding.emptyState.emptyTitle.text = getString(titleRes)
        val hintText = errorHint ?: hintRes?.let { getString(it) }
        if (hintText == null) {
            binding.emptyState.emptyHint.visibility = View.GONE
        } else {
            binding.emptyState.emptyHint.visibility = View.VISIBLE
            binding.emptyState.emptyHint.text = hintText
        }
    }

    private fun renderPermission(state: BrowserUiState) {
        val degraded = state.degraded
        binding.permissionBar.visibility = if (degraded) View.VISIBLE else View.GONE
        if (!degraded) return
        binding.permissionHint.text = when (state.tier) {
            StorageTier.SAF_TREE -> getString(R.string.browser_permission_saf, state.grantCount)
            StorageTier.APP_PRIVATE -> getString(R.string.browser_permission_private)
            else -> getString(R.string.browser_permission_full)
        }
        binding.permissionAction.visibility =
            if (canRequestBroaderAccess()) View.VISIBLE else View.GONE
    }

    // ── 消息与请求 ──────────────────────────────────────────────────────

    private fun showMessage(message: BrowserMessage) {
        val text = when (message) {
            is BrowserMessage.Pinned -> getString(R.string.browser_msg_pinned, message.name)

            // M7.12 收藏反馈
            is BrowserMessage.FavoriteAdded ->
                getString(R.string.browser_msg_fav_added, message.name)
            is BrowserMessage.FavoriteRemoved ->
                getString(R.string.browser_msg_fav_removed, message.name)
            BrowserMessage.PinUnsupported -> getString(R.string.browser_msg_pin_unsupported)
            is BrowserMessage.PinFailed -> getString(R.string.browser_msg_pin_failed, message.reason)
            is BrowserMessage.Created -> getString(R.string.browser_msg_created, message.name)

            is BrowserMessage.Deleted ->
                if (message.failed == 0) {
                    getString(R.string.browser_msg_deleted, message.ok)
                } else {
                    getString(R.string.browser_msg_deleted_partial, message.ok, message.failed)
                }

            is BrowserMessage.Renamed -> getString(R.string.browser_msg_renamed, message.name)

            is BrowserMessage.Pasted ->
                if (message.failed == 0) {
                    getString(R.string.browser_msg_pasted, message.ok)
                } else {
                    getString(R.string.browser_msg_pasted_partial, message.ok, message.failed)
                }

            is BrowserMessage.PasteCancelled ->
                getString(R.string.browser_msg_paste_cancelled, message.ok)

            BrowserMessage.TransferBusy -> getString(R.string.browser_msg_transfer_busy)

            is BrowserMessage.ClipboardSet -> getString(
                if (message.cut) R.string.browser_msg_cut else R.string.browser_msg_copied,
                message.count,
            )

            is BrowserMessage.Failed -> getString(R.string.browser_msg_failed, message.reason)
        }
        toast(text)
    }

    private fun handleRequest(request: BrowserRequest) {
        when (request) {
            BrowserRequest.GrantAllFilesAccess -> launchAllFilesAccessPage()

            BrowserRequest.GrantRuntimePermission ->
                runtimePermissionLauncher.launch(storageManager.runtimePermissions())

            BrowserRequest.PickSafDirectory -> safTreeLauncher.launch(buildOpenTreeIntent())

            is BrowserRequest.PinDirectory -> showInputDialog(
                titleRes = R.string.browser_dialog_pin_name,
                initial = request.suggestedName,
            ) { label -> viewModel.pinToDesktop(request.path, label) }

            is BrowserRequest.OpenPreview -> openPreview(request.args)
        }
    }

    /**
     * 打开预览页。
     *
     * 走 [FeatureRoutes] 的字符串路由而不是直接 import `PreviewActivity`：
     * `feature:browser` 与 `feature:preview` 是平级模块，互相依赖会让"能不能单独编译
     * 某个功能模块"这条底线失效。代价是目标类可能不存在，所以这里显式判空并给出提示
     * ——这比 `ClassNotFoundException` 崩溃好得多，也让"删掉预览模块"成为一种可接受的裁剪。
     */
    private fun openPreview(args: PreviewArgs) {
        val target = FeatureRoutes.preview(this)
        if (target == null) {
            toast(getString(R.string.browser_msg_not_previewable, args.current.displayName))
            return
        }
        PreviewArgs.putExtras(target, args)
        startActivity(target)
    }

    // ── 菜单与对话框 ────────────────────────────────────────────────────

    private fun onToolbarItem(item: MenuItem): Boolean = when (item.itemId) {
        // 搜索项带 `collapseActionView`：点图标之后由框架展开 actionView
        // （`OnActionExpandListener.onMenuItemActionExpand` 已经返回 true），
        // 这里只需要承认"这一项我处理了"。查询的订阅在 setupSearch 里。
        R.id.action_search -> true

        R.id.action_up -> {
            viewModel.goUp()
            true
        }

        R.id.action_new_folder -> {
            showInputDialog(R.string.browser_dialog_new_folder, initial = "") { name ->
                viewModel.createFolder(name)
            }
            true
        }

        R.id.action_sort -> {
            showSortDialog()
            true
        }

        R.id.action_view_mode -> {
            showViewModeDialog()
            true
        }

        R.id.action_paste -> {
            viewModel.paste()
            true
        }

        R.id.action_select_all -> {
            viewModel.toggleSelectAll()
            true
        }

        R.id.action_show_hidden -> {
            viewModel.toggleShowHidden()
            true
        }

        R.id.action_pin_current -> {
            rendered.location?.let(viewModel::requestPinDirectory)
            true
        }

        else -> false
    }

    private fun onSelectAction(action: MultiSelectBar.Action) {
        val state = rendered
        val paths = state.selection.toList()
        when (action) {
            MultiSelectBar.Action.SELECT_ALL -> viewModel.toggleSelectAll()

            MultiSelectBar.Action.RENAME -> {
                val entry = state.selectedEntries.singleOrNull() ?: return
                showInputDialog(R.string.browser_dialog_rename, entry.name) { name ->
                    viewModel.rename(entry.path, name)
                }
            }

            MultiSelectBar.Action.COPY -> viewModel.clipboardSelect(paths, cut = false)

            MultiSelectBar.Action.CUT -> viewModel.clipboardSelect(paths, cut = true)

            MultiSelectBar.Action.DELETE -> confirmDelete(paths)

            MultiSelectBar.Action.ADD_TO_HOME ->
                state.selectedDirectories.singleOrNull()
                    ?.let { viewModel.requestPinDirectory(it.path) }

            // M7.12：收藏/取消收藏（图标与标签随状态二选一，见 renderSelection）
            MultiSelectBar.Action.FAVORITE,
            MultiSelectBar.Action.UNFAVORITE,
            -> state.selectedDirectories.singleOrNull()
                ?.let { viewModel.toggleFavorite(it.path) }

            MultiSelectBar.Action.INFO ->
                state.selectedEntries.singleOrNull()?.let(::showProperties)

            // M4：无线分享。选中项交给面板，由它推导共享根
            // （单选一个目录 → 根就是它；多选 → 取共同父目录 + 根级过滤）。
            // 这里**只传路径**，不传"应该怎么分享"——那属于分享面板的职责，
            // 浏览器不该知道"共享根"这个概念。
            MultiSelectBar.Action.SHARE -> {
                val entries = state.selectedEntries
                openSharePanel(
                    selected = entries.map { it.path },
                    selectedIsDir = entries.size == 1 && entries[0].isDirectory,
                )
            }
        }
    }

    /**
     * 进入无线分享面板（方案 §10.4「入口一致」的三处入口都收敛到这里）。
     *
     * @param selected 选中项；空表示"分享当前所在的目录"
     */
    private fun openSharePanel(
        selected: List<FsPath> = emptyList(),
        selectedIsDir: Boolean = false,
    ) {
        val location = rendered.location
        if (location == null) {
            toast(getString(R.string.browser_share_no_location))
            return
        }
        val ok = FeatureRoutes.openShare(
            context = this,
            root = location.toString(),
            selected = selected.map { it.toString() },
            selectedIsDir = selectedIsDir,
        )
        if (!ok) toast(getString(R.string.browser_share_unavailable))
    }

    /** 行尾「⋯」菜单：针对单条的快捷操作，免去"先进多选再点动作"两步。 */
    private fun showRowMenu(item: FileRowItem, anchor: View) {
        val entry = item.entry
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_RENAME, 0, CoreR.string.action_rename)
        popup.menu.add(0, MENU_COPY, 1, CoreR.string.action_copy)
        popup.menu.add(0, MENU_CUT, 2, CoreR.string.action_cut)
        if (entry.isDirectory) {
            popup.menu.add(0, MENU_PIN, 3, R.string.browser_action_pin_current)
            // 收藏/取消收藏（M7.12）：标签随当前状态走，点了就知道发生了什么
            val favorited = entry.path in rendered.favorites
            popup.menu.add(
                0,
                MENU_FAVORITE,
                4,
                if (favorited) CoreR.string.action_unfavorite else CoreR.string.action_favorite,
            )
        }
        popup.menu.add(0, MENU_INFO, 5, CoreR.string.action_info)
        popup.menu.add(0, MENU_DELETE, 6, CoreR.string.action_delete)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RENAME -> showInputDialog(R.string.browser_dialog_rename, entry.name) { name ->
                    viewModel.rename(entry.path, name)
                }

                MENU_COPY -> viewModel.clipboardSelect(listOf(entry.path), cut = false)
                MENU_CUT -> viewModel.clipboardSelect(listOf(entry.path), cut = true)
                MENU_PIN -> viewModel.requestPinDirectory(entry.path)
                MENU_FAVORITE -> viewModel.toggleFavorite(entry.path)
                MENU_INFO -> showProperties(entry)
                MENU_DELETE -> confirmDelete(listOf(entry.path))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showSortDialog() {
        val sort = rendered.sort
        val options = listOf(
            getString(CoreR.string.sort_by_name) to FsSortField.NAME,
            getString(CoreR.string.sort_by_size) to FsSortField.SIZE,
            getString(CoreR.string.sort_by_modified) to FsSortField.MODIFIED,
            getString(CoreR.string.sort_by_type) to FsSortField.TYPE,
        )
        // 当前排序列带上箭头，一眼能看出"按什么排、朝哪排"
        val arrow = if (sort.ascending) " ↑" else " ↓"
        val labels = options
            .map { (label, field) -> if (field == sort.field) label + arrow else label }
            .toTypedArray()
        val toggleLabel = getString(
            if (sort.ascending) CoreR.string.sort_descending else CoreR.string.sort_ascending,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_dialog_sort_title)
            .setItems(labels) { _, which -> viewModel.sortBy(options[which].second) }
            .setNeutralButton(toggleLabel) { _, _ -> viewModel.toggleSortDirection() }
            .show()
    }

    private fun showViewModeDialog() {
        val options = listOf(
            getString(CoreR.string.view_list) to FileViewMode.LIST,
            getString(CoreR.string.view_grid) to FileViewMode.GRID,
            getString(CoreR.string.view_details) to FileViewMode.DETAILS,
        )
        val labels = options
            .map { (label, mode) -> if (mode == rendered.viewMode) "✓ $label" else label }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_dialog_view_title)
            .setItems(labels) { _, which -> viewModel.setViewMode(options[which].second) }
            .show()
    }

    private fun confirmDelete(paths: List<FsPath>) {
        if (paths.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_dialog_delete_title)
            .setMessage(getString(R.string.browser_dialog_delete_message, paths.size))
            .setNegativeButton(R.string.browser_dialog_cancel, null)
            .setPositiveButton(R.string.browser_dialog_delete_title) { _, _ ->
                viewModel.delete(paths)
            }
            .show()
    }

    private fun showProperties(entry: FsEntry) {
        val none = getString(R.string.browser_properties_none)
        val text = buildString {
            appendLine("${getString(CoreR.string.column_name)}：${entry.name}")
            appendLine("${getString(CoreR.string.column_size)}：${FileLabels.sizeText(entry) ?: none}")
            appendLine("${getString(CoreR.string.column_modified)}：${FileLabels.timeColumn(entry)}")
            appendLine("${getString(CoreR.string.column_type)}：${FileLabels.typeLabel(entry)}")
            append("${getString(R.string.browser_properties_path)}：${entry.path}")
        }
        AlertDialog.Builder(this)
            .setTitle(CoreR.string.action_info)
            .setMessage(text)
            .setPositiveButton(R.string.browser_dialog_ok, null)
            .show()
    }

    private fun showInputDialog(titleRes: Int, initial: String, onConfirm: (String) -> Unit) {
        val dialogBinding = DialogInputBinding.inflate(layoutInflater)
        dialogBinding.input.setText(initial)
        dialogBinding.input.setSelection(dialogBinding.input.text?.length ?: 0)
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.browser_dialog_cancel, null)
            .setPositiveButton(R.string.browser_dialog_ok) { _, _ ->
                onConfirm(dialogBinding.input.text?.toString().orEmpty())
            }
            .show()
    }

    // ── 权限 ────────────────────────────────────────────────────────────

    /** 降级提示条上的「去授权」：按当前系统版本挑最合适的通路。 */
    private fun requestStorageAccess() {
        when {
            storageManager.needsAllFilesAccess() -> viewModel.requestAllFilesAccess()
            storageManager.runtimePermissions().isNotEmpty() -> viewModel.requestRuntimePermission()
            else -> viewModel.requestSafDirectory()
        }
    }

    private fun canRequestBroaderAccess(): Boolean =
        storageManager.needsAllFilesAccess() || storageManager.runtimePermissions().isNotEmpty()

    private fun launchAllFilesAccessPage() {
        val intent = storageManager.allFilesAccessIntent()
        val opened = intent != null && runCatching { allFilesAccessLauncher.launch(intent) }.isSuccess
        if (opened) return
        // 少数 ROM 不认带 package: 的直达页；退到应用详情页，再不行就明说
        val fallback = runCatching { startActivity(storageManager.appDetailsIntent()) }
        if (fallback.isFailure) toast(getString(R.string.browser_permission_unavailable))
    }

    private fun buildOpenTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }

    // ── 杂项 ────────────────────────────────────────────────────────────

    /** 导航树懒加载子目录。加载必须由宿主发起——View 不该持有协程作用域。 */
    private fun loadTreeChildren(parent: FsPath) {
        lifecycleScope.launch {
            val children = runCatching {
                withContext(Dispatchers.IO) {
                    FileSystems.registry.resolve(parent).list(parent)
                }
            }.getOrDefault(emptyList())
            binding.navTree.submitChildren(parent, children)
        }
    }

    private fun layoutManagerFor(mode: FileViewMode): RecyclerView.LayoutManager =
        if (mode == FileViewMode.GRID) {
            GridLayoutManager(this, GridSpec.spanCount(this, availableListWidth()))
        } else {
            LinearLayoutManager(this)
        }

    /**
     * 列表可用宽度。
     *
     * 首次渲染时 `listContainer.width` 还是 0（尚未布局），而此时就要决定网格列数，
     * 所以退化用屏幕宽度减去窗格宽度估算。布局完成后 width 才是权威值。
     */
    private fun availableListWidth(): Int {
        val measured = binding.listContainer.width
        if (measured > 0) return measured
        val screen = resources.displayMetrics.widthPixels
        return if (twoPane) {
            screen - resources.getDimensionPixelSize(CoreR.dimen.nav_pane_width)
        } else {
            screen
        }
    }

    private fun viewModeIconRes(mode: FileViewMode): Int = when (mode) {
        FileViewMode.LIST -> CoreR.drawable.ic_view_list
        FileViewMode.GRID -> CoreR.drawable.ic_view_grid
        FileViewMode.DETAILS -> CoreR.drawable.ic_view_details
    }

    /** 路径段 → 展示名；卷根用侧栏的标签（"内部存储"），其余用末段名。 */
    private fun labelOf(state: BrowserUiState, segment: FsPath): String =
        state.roots.firstOrNull { it.path == segment }?.name ?: segment.displayName

    /**
     * 当前路径所属的存储卷（也就是最深的那个匹配的根）。
     *
     * 面包屑用它作为起始段，避免把 `local:///storage/emulated` 这类后端结构画给用户看。
     * 没有任何根匹配时返回 `null`，面包屑退化为从路径根画起——这是安全的方向：
     * 宁可多显示两段，也不要漏掉定位信息。
     */
    private fun rootAncestorOf(state: BrowserUiState, path: FsPath): FsPath? =
        state.roots
            .map { it.path }
            .filter { it == path || it.isAncestorOf(path) }
            .maxByOrNull { it.depth }

    private fun titleOf(state: BrowserUiState): String =
        state.location?.let { labelOf(state, it) }.orEmpty()

    /** 从启动 Intent 还原桌面快捷方式目标；普通启动返回 `null`。 */
    private fun readLaunchTarget(): FsPath? {
        val target = FolderShortcuts.readTarget(intent) ?: return null
        return FsPath.parse(target.path) ?: run {
            toast(getString(R.string.browser_msg_failed, target.path))
            null
        }
    }

    private fun closeDrawerIfOpen() {
        if (twoPane) return
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val MENU_RENAME = 0x10
        const val MENU_COPY = 0x11
        const val MENU_CUT = 0x12
    const val MENU_PIN = 0x13
    const val MENU_INFO = 0x14
    const val MENU_DELETE = 0x15
    const val MENU_FAVORITE = 0x16

        /** 进度对话框的「已隐藏」标记（跨旋转保留，见 [transferHidden]）。 */
        const val STATE_TRANSFER_HIDDEN = "mimi.transfer.hidden"
    }
}
