package com.miyuetech.mimifiles.feature.preview

import androidx.appcompat.app.AppCompatActivity
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.PreviewArgs
import com.miyuetech.mimifiles.core.preview.PreviewEnvironment
import kotlinx.coroutines.CoroutineScope
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * 预览页宿主能力（渲染器能用到的全部东西）。
 *
 * 用接口而不是直接把 `PreviewActivity` 递给渲染器：渲染器只需要这六件事，
 * 拿到整个 Activity 就等于拿到 `setContentView`、`finish`、`window` 等一切，
 * 于是"渲染器偷偷改了 Activity 的行为"这种事迟早会发生。
 */
interface PreviewHost {

    val activity: AppCompatActivity

    val args: PreviewArgs

    val environment: PreviewEnvironment

    /**
     * `:provider` 的 authority。
     *
     * 渲染器自己不用它，但它要**往下传**给 `PreviewImageSource` / `ThumbnailLoader`：
     * 远端文件的 HEIC/AVIF 只能由平台的 `ImageDecoder` 解，而它只认 `content://`。
     */
    val documentAuthority: String

    /** 渲染器的协程作用域（跟随 Activity 生命周期）。 */
    val scope: CoroutineScope

    /** 顶栏副标题（文件名 / 计数 / 编码…）。 */
    fun setSubtitle(text: CharSequence?)

    /** 显隐工具栏与底栏（图片/视频的"点一下沉浸"）。 */
    fun setChromeVisible(visible: Boolean)

    /** 顶栏/底栏当前是否可见。"点一下切换"需要知道当前状态，否则会变成"只能隐藏、不能恢复"。 */
    val isChromeVisible: Boolean

    fun toast(text: CharSequence)

    /** 交给外部应用打开。`null` 的 [mime] 表示按扩展名推断。 */
    fun openWithExternalApp(mime: String?): Boolean

    /**
     * 播放一个文件（可指定"只播文件里的某一段"，动态照片用）。
     *
     * 用"文件"而不是"同级列表里的第几项"，是因为 Apple Live Photo 的视频在**另一个文件**里，
     * 它不在浏览页传来的同级列表里。
     */
    fun playFile(path: FsPath, sliceOffset: Long = -1L, sliceLength: Long = -1L)
}

/** 详情行：左标签（字符串资源）右值。 */
data class InfoRow(val labelRes: Int, val value: String)

/**
 * 一个"渲染器" = 一种预览形态。
 *
 * ## 为什么不用 Fragment
 * 四种形态共享同一个 Activity（同一套工具栏、底栏、系统栏处理），而 Fragment 在这里
 * 只会带来三样东西：额外的生命周期回调顺序、`onCreateView` 里的 inflate 样板、
 * 以及 `commit` 与状态恢复相关的一类时序 bug。渲染器只需要
 * 「造视图 / 开始 / 停止 / 释放 / 给详情行」五个钩子，用普通类表达更短也更直白。
 */
interface PreviewRenderer {

    /** 是否用深色背景。图片/视频为 `true`（相册惯例），文本/PDF/音频为 `false`。 */
    val darkBackdrop: Boolean get() = false

    /** 是否支持"点一下隐藏工具栏"。 */
    val supportsChromeToggle: Boolean get() = false

    /**
     * 内容是否**铺满全屏**（含状态栏与顶栏之下）。
     *
     * ## 默认值是 `false`，这不是随手定的
     * 预览页是 edge-to-edge 的：`preview_container` 铺到状态栏与手势条底下，
     * 只有工具栏/底栏自己躲开 insets。于是**任何不是"整幅画面"的渲染器**
     * （文本、PDF、列表…）如果自己不躲，第一行内容就会被状态栏吃掉、并被顶栏压住 ——
     * 真机实测正是如此（TXT 正文顶部与 PDF 首页都顶到状态栏里）。
     *
     * 所以宿主默认给渲染器视图补"状态栏 + 顶栏"高度的顶部内边距，**要铺满的必须显式
     * 声明**。默认值取"补内边距"而不是"铺满"，是因为漏补的后果（内容不可读）
     * 比多补的后果（多一条空白）严重得多。
     */
    val fullBleedContent: Boolean get() = false

    fun createView(inflater: LayoutInflater, parent: ViewGroup): View

    /** 是否支持缩放（决定底栏"复位缩放"按钮显隐）。 */
    val supportsZoom: Boolean get() = false

    fun onStart() = Unit

    fun onStop() = Unit

    fun release() = Unit

    /** 底栏"复位缩放"是否有效。 */
    fun onResetZoom(): Boolean = false

    /** 详情面板内容。 */
    fun infoRows(): List<InfoRow>
}
