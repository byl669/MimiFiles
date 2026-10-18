package com.miyuetech.mimifiles.feature.share

import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareRequest

/**
 * 进入分享面板的入参（Intent extras）。
 *
 * ## 为什么用字符串而不是 Parcelable
 * 路径已经是 [FsPath] 的**文本形式**（`local:///sdcard/DCIM`），它本来就是跨进程、
 * 跨版本稳定的持久化格式（桌面快捷方式与 `DocumentsProvider` 都用它）。
 * 再包一层 Parcelable 只是把"同一个东西的第二种表示"引进来——而两种表示迟早会有
 * 一处忘记同步。这里全部用它，parcel 进 Intent 也直接用字符串。
 *
 * ## 三个入口共用
 * | 入口 | root | selected |
 * |---|---|---|
 * | 侧栏「无线分享」 | 当前目录 | 空 |
 * | 多选 → 分享 | —（由 selected 推导） | 选中项 |
 * | 通知点进面板 | 不传（沿用运行中的请求） | 不传 |
 */
object ShareArgs {

    /** 共享根（`FsPath.toString()`）。 */
    const val EXTRA_ROOT = "share.root"

    /** 选中项的 `FsPath.toString()` 列表。 */
    const val EXTRA_SELECTED = "share.selected"

    /** 选中项里是否包含目录（决定"单选一个目录 → 根就是它"这条分支）。 */
    const val EXTRA_SELECTED_IS_DIR = "share.selected_is_dir"

    /** 要预选的 Tab（`ServerType.name`）。 */
    const val EXTRA_TYPE = "share.type"

    /**
     * 会话有效期的覆盖值（毫秒）。**冒烟测试专用**。
     *
     * ## 为什么需要它（以及为什么它不会成为产品行为）
     * "面板关闭 5 分钟后自动失效"（方案 S-7）是一条**时间**语义，默认值 5 分钟是为了
     * 用户体验，但用它做自动化验证就意味着每条用例要多等 5 分钟——那是不可接受的。
     *
     * 这条链路值得端到端验证，因为它的失效模式正是**安全**失效：
     * "用户以为分享已经停了、实际上监听还开着"。它跨越三个组件
     * （引擎里的定时器 → 引擎停服务 → 前台服务撤通知），只在单元测试里
     * 分别验证每一段，接缝处漏掉一环照样能全绿。
     *
     * 不会成为产品行为的两条理由：
     *  ① 正式变体里面板是 `android:exported="false"`，外部（adb / 别的应用）
     *    **无法**给它塞 extras；能塞进来的只有应用自己，而 `FeatureRoutes` 从不传这个键。
     *  ② 它只会**缩短**会话，不会延长——被凭空塞进来的后果是"分享提前停了"，
     *    而不是"分享跑得比用户以为的更久"。
     */
    const val EXTRA_TTL_MS = "share.ttl_ms"

    /**
     * 从 Intent 还原一次分享请求。
     *
     * 返回 `null` 表示"这次进入没有带任何待分享内容"（例如从通知点进来）——
     * 此时面板会回落到 [ShareSessionHolder.pending] 或引擎里正在跑的请求，
     * 而不是凭空造一个空请求出来。**空请求（root 为空）是没有意义的**：
     * 分享一个不存在的路径只会让用户看到"启动失败"。
     */
    fun readRequest(
        root: String?,
        selected: Array<String>?,
        selectedIsDir: Boolean,
    ): ShareRequest? {
        val selectedPaths = selected
            ?.mapNotNull { FsPath.parse(it) }
            .orEmpty()
        val currentDir = FsPath.parse(root)
        if (currentDir == null) {
            if (selectedPaths.isEmpty()) return null
            // 只有选中项、没有当前目录：用第一个选中项的所在目录推导。
            val first = selectedPaths.first()
            return ShareRequest.ofSelection(first.parent ?: first, selectedPaths, selectedIsDir)
        }
        return ShareRequest.ofSelection(currentDir, selectedPaths, selectedIsDir)
    }

    fun readType(raw: String?): ServerType? =
        raw?.takeIf { it.isNotBlank() }?.let(ServerType::fromName)
}
