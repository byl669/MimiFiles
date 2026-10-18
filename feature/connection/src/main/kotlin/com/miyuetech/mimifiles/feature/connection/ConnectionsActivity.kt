package com.miyuetech.mimifiles.feature.connection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.miyuetech.mimifiles.core.datastore.ConnectionRepository
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.ConnectionProfile
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.ui.enableEdgeToEdge
import com.miyuetech.mimifiles.core.ui.padForSystemBars
import com.miyuetech.mimifiles.feature.connection.databinding.ActivityConnectionsBinding
import kotlinx.coroutines.launch

/**
 * 网络连接列表。
 *
 * ## 数据流
 * 列表内容永远来自 [ConnectionRepository.profiles] 这个 Flow，控件不自己持有数据。
 * 于是"编辑页保存后返回，列表自动更新"这件事不需要任何回调或 `onActivityResult`——
 * 数据库变了，Flow 就推新值。这类"返回后忘了刷新"的 bug 在结构上被消除了。
 *
 * ## 为什么放在 `:feature:connection` 而不是设置页里
 * 连接是**常用功能**而不是偏好设置：用户会频繁增删连接、测试连通性。
 * 塞进设置页会让它埋在两级菜单下面，而它值得一个侧栏入口。
 */
class ConnectionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionsBinding

    private lateinit var repository: ConnectionRepository

    private val adapter = ConnectionRowAdapter(
        onOpen = { profile -> openEditor(profile.id) },
        onMenu = ::showRowMenu,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ 起系统强制 edge-to-edge，标题栏会钻到状态栏底下。
        // 只让顶栏吃掉状态栏的高度，列表区不动——它本来就被顶栏挡住，加了反而多一段空白。
        enableEdgeToEdge()
        binding.toolbar.padForSystemBars(top = true, bottom = false)

        repository = ConnectionRepository(this)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.fabAdd.setOnClickListener { openEditor(null) }

        observeConnections()
    }

    private fun observeConnections() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.profiles.collect { profiles ->
                    adapter.submit(profiles)
                    binding.empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    binding.list.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                    // 条数放副标题而不是底部状态栏：这个页面只有一屏列表，
                    // 没有别的信息要跟它争位置，而"我到底配了几条"是进来就想知道的事。
                    binding.toolbar.subtitle = if (profiles.isEmpty()) {
                        null
                    } else {
                        getString(R.string.connections_count, profiles.size)
                    }
                }
            }
        }
    }

    private fun openEditor(id: String?) {
        startActivity(ConnectionEditActivity.intent(this, id))
    }

    /**
     * 条目溢出菜单。
     *
     * 用 `PopupMenu` 而不是 `AlertDialog`：它是"对某一行的动作"，
     * 弹窗会让用户失去"我操作的是哪一行"的上下文（尤其列表滚动过之后）。
     */
    private fun showRowMenu(anchor: View, profile: ConnectionProfile) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_TEST, 0, getString(R.string.connection_action_test))
            menu.add(0, MENU_EDIT, 1, getString(R.string.connection_action_edit))
            menu.add(0, MENU_DELETE, 2, getString(R.string.connection_action_delete))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_TEST -> {
                        testConnection(profile)
                        true
                    }

                    MENU_EDIT -> {
                        openEditor(profile.id)
                        true
                    }

                    MENU_DELETE -> {
                        confirmDelete(profile)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    private fun testConnection(profile: ConnectionProfile) {
        val snackbar = Snackbar.make(
            binding.root,
            getString(R.string.connection_testing),
            Snackbar.LENGTH_INDEFINITE,
        ).setAnchorView(binding.fabAdd)
        snackbar.show()

        lifecycleScope.launch {
            val password = repository.passwordOf(profile.id)
            val outcome = ConnectionTester.test(profile, password)
            snackbar.dismiss()
            val text = when (outcome) {
                is TestOutcome.Success -> getString(R.string.connection_test_success, outcome.detail)
                is TestOutcome.Failure -> getString(R.string.connection_test_failed, outcome.reason)
            }
            Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.fabAdd)
                .show()
        }
    }

    private fun confirmDelete(profile: ConnectionProfile) {
        val label = ConnectionFormats.fallbackLabel(profile)
        AlertDialog.Builder(this)
            .setTitle(R.string.connection_delete_title)
            .setMessage(getString(R.string.connection_delete_message, label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.connection_action_delete) { _, _ ->
                lifecycleScope.launch {
                    repository.delete(profile.id)
                    // 关键一步：注册表里缓存的实例还握着旧凭据与旧会话。
                    // 不释放的话，用户"删了再新建同名连接"会拿到上一个的会话，
                    // 表现为"改了密码却还是旧密码能连上"——极难归因。
                    FileSystems.registry.release(FsPath.of(profile.scheme, profile.id))
                    Snackbar.make(
                        binding.root,
                        getString(R.string.connection_deleted, label),
                        Snackbar.LENGTH_SHORT,
                    ).setAnchorView(binding.fabAdd).show()
                }
            }
            .show()
    }

    companion object {

        private const val MENU_TEST = 1
        private const val MENU_EDIT = 2
        private const val MENU_DELETE = 3

        /** 供浏览页侧栏与应用内其它入口跳转。 */
        fun intent(context: Context): Intent =
            Intent().setClassName(context, ConnectionsActivity::class.java.name)
    }
}
