package com.miyuetech.mimifiles.feature.browser

import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.PreviewArgs

/**
 * 一次性消息（Toast 文案的**类型化**形式）。
 *
 * ViewModel 不产出中文：文案全部落在 `res/values/strings.xml`，由 UI 层 `when` 渲染。
 * 代价是多了一个 sealed 层级，换来的是"提示语可翻译、可被 lint 检查漏译、
 * 改文案不需要动业务代码"。
 */
sealed interface BrowserMessage {

    data class Pinned(val name: String) : BrowserMessage

    /** 已收藏（M7.12）。侧栏「快速访问」会立即出现该目录。 */
    data class FavoriteAdded(val name: String) : BrowserMessage

    /** 已取消收藏（M7.12）。 */
    data class FavoriteRemoved(val name: String) : BrowserMessage

    data object PinUnsupported : BrowserMessage

    data class PinFailed(val reason: String) : BrowserMessage

    data class Created(val name: String) : BrowserMessage

    /** 删除结束：`failed > 0` 时 UI 应提示部分失败。 */
    data class Deleted(val ok: Int, val failed: Int) : BrowserMessage

    data class Renamed(val name: String) : BrowserMessage

    data class Pasted(val ok: Int, val failed: Int, val cut: Boolean) : BrowserMessage

    /** 用户主动取消了搬运，[ok] 为取消前已经完成的项数。不算失败，文案不要带"错误"。 */
    data class PasteCancelled(val ok: Int) : BrowserMessage

    /** 已有一次搬运在跑。不做排队：两条传输同时写同一个目标目录会各自算重名，结果多出一份副本。 */
    data object TransferBusy : BrowserMessage

    data class ClipboardSet(val count: Int, val cut: Boolean) : BrowserMessage

    /** 通用失败（权限、IO、不可达），[reason] 为技术串，UI 可选择性附带。 */
    data class Failed(val reason: String) : BrowserMessage
}

/**
 * 需要**宿主 Activity 参与**的请求（VM 做不了、也不该做的事）。
 *
 * 典型的三类：跳系统设置页、拉起系统目录选择器、弹对话框。
 * 用事件流下发而不是让 VM 持有 Activity 引用——后者在旋转屏幕后就是内存泄漏与崩溃。
 */
sealed interface BrowserRequest {

    /** 跳「所有文件访问权限」页（Android 11+）。 */
    data object GrantAllFilesAccess : BrowserRequest

    /** 申请运行时读写权限（Android 10 及以下）。 */
    data object GrantRuntimePermission : BrowserRequest

    /** 拉起 `ACTION_OPEN_DOCUMENT_TREE` 授权一个目录。 */
    data object PickSafDirectory : BrowserRequest

    /** 请求把某个目录钉到桌面（Activity 先弹改名框再回传）。 */
    data class PinDirectory(val path: FsPath, val suggestedName: String) : BrowserRequest

    /**
     * 打开预览（M3）。
     *
     * 同级列表由 **ViewModel** 决定而不是 Activity：只有它知道用户当前的排序与过滤
     * （隐藏文件开关、排序字段），而"预览里左右滑的顺序"必须与列表里看到的一致。
     * 让 Activity 自己从 `state.entries` 重新拼一遍，迟早会出现两处规则不一致。
     */
    data class OpenPreview(val args: PreviewArgs) : BrowserRequest
}
