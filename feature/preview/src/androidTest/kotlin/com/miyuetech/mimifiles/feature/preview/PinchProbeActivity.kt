package com.miyuetech.mimifiles.feature.preview

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 双指捏合测试的宿主页 —— 结构刻意复刻 PDF 预览的真实层级：
 *
 * ```
 * LinearLayout
 *   └─ ZoomableContainer（weight=1，占满剩余空间）
 *        └─ RecyclerView（LinearLayoutManager，纵向，若干 400px 高的"页"）
 * ```
 *
 * 之所以不用一个普通 View 凑合：捏合 bug 的两个根因（平移交棒、子树 CANCEL）
 * 只有在"容器包着一个会滚动的列表"时才暴露。探针页与 PDF 页同构，
 * 测试结论才能平移到真机症状上。
 */
class PinchProbeActivity : Activity() {

    lateinit var container: ZoomableContainer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // test APK 不跑主 App 的初始化，logcat 镜像默认是关的——打开它，
        // 容器的事件级取证日志才能进 adb logcat。
        com.miyuetech.mimifiles.core.common.MimiLog.mirrorToLogcat = true
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PinchProbeActivity)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(
                        TextView(parent.context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                400,
                            )
                            textSize = 28f
                            text = "page"
                        },
                    ) {}

                override fun getItemCount(): Int = 40

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }
        container = ZoomableContainer(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        container.addView(
            list,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(container)
        setContentView(root)
    }
}
