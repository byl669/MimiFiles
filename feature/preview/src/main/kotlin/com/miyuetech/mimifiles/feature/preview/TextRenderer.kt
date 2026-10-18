package com.miyuetech.mimifiles.feature.preview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.core.preview.TextPage
import com.miyuetech.mimifiles.core.preview.TextReader
import com.miyuetech.mimifiles.feature.preview.databinding.ItemTextPageBinding
import com.miyuetech.mimifiles.feature.preview.databinding.ViewTextPreviewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 文本预览 —— 编码自动识别 + 分段加载。
 *
 * ## 为什么"读到哪算哪"而不是一次读完
 * 规划里 fenced 住了"1 GB 文件预览不 OOM"。文本文件最容易违反这条：1 GB 的日志
 * 全读进来就是 1~2 GB 的字符串（UTF-16），必然 OOM。所以：
 *
 *  - 按 32 KB 一页切（[PAGE_BYTES]），一页一个列表项 → RecyclerView 只测量可见的几页；
 *  - 滚动到最后一页时**自动追加下一页**（不需要用户点"加载更多"）；
 *  - 但**最多加载 [MAX_PAGES] 页**。到顶之后底部固定一行提示，说明"只加载了前 N MB"。
 *    这个上限是必须的：用户按住往下一路滚到底，不设限就等于把整个文件读进内存，
 *    而"我以为它在分段加载"是最容易骗过自己的地方。
 */
class TextRenderer(
    private val host: PreviewHost,
    private val path: FsPath,
) : PreviewRenderer {

    private var binding: ViewTextPreviewBinding? = null

    private val adapter = PageAdapter()

    private var reader: TextReader? = null

    private val pages = mutableListOf<TextPage>()

    private var nextOffset = 0L

    private var loading = false

    private var reachedEnd = false

    /** 因为达到 [MAX_PAGES] 而停止（与"文件真的读完了"是两回事，提示语不同）。 */
    private var truncated = false

    private var loadJob: Job? = null

    private var encodingLabel: String = ""

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val created = ViewTextPreviewBinding.inflate(inflater, parent, false)
        binding = created
        created.textPages.layoutManager = LinearLayoutManager(host.activity)
        created.textPages.adapter = adapter
        created.textPages.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val last = (recyclerView.layoutManager as? LinearLayoutManager)
                        ?.findLastVisibleItemPosition() ?: return
                    // 提前一页开始加载：等用户真的滑到底再加载，会看到一次明显停顿。
                    if (last >= adapter.itemCount - 2) loadNextPage()
                }
            },
        )
        start()
        return created.root
    }

    override fun onStop() {
        loadJob?.cancel()
    }

    override fun release() {
        loadJob?.cancel()
        reader?.close()
        reader = null
        binding?.textPages?.adapter = null
        binding = null
    }

    override fun infoRows(): List<InfoRow> {
        val current = reader
        return buildList {
            add(InfoRow(R.string.preview_info_path, path.toString()))
            if (current != null) {
                add(InfoRow(R.string.preview_info_bytes, Formatters.size(current.size)))
                add(InfoRow(R.string.preview_info_encoding, current.encoding.label))
            }
        }
    }

    private fun start() {
        if (reader != null || loading) return
        loading = true
        loadJob = host.scope.launch {
            val opened = runCatching { TextReader.open(path) }.getOrNull()
            loading = false
            if (opened == null) {
                showError()
                return@launch
            }
            reader = opened
            encodingLabel = opened.encoding.label
            binding?.textEncoding?.text =
                host.activity.getString(R.string.preview_text_encoding, encodingLabel)
            host.setSubtitle(path.displayName)
            loadNextPage()
        }
    }

    private fun loadNextPage() {
        if (loading || reachedEnd || truncated) return
        val current = reader ?: return
        if (pages.size >= MAX_PAGES) {
            truncated = true
            showFooter()
            return
        }
        loading = true
        loadJob = host.scope.launch {
            val page = runCatching { current.page(nextOffset, PAGE_BYTES) }.getOrNull()
            loading = false
            if (page == null) {
                reachedEnd = true
                showFooter()
                return@launch
            }
            pages += page
            nextOffset = page.nextOffset
            adapter.notifyItemInserted(pages.size - 1)
            if (page.eof) reachedEnd = true
            binding?.textProgress?.text = host.activity.getString(
                R.string.preview_counter,
                pages.size,
                MAX_PAGES,
            )
            if (reachedEnd || pages.size >= MAX_PAGES) {
                if (!reachedEnd && pages.size >= MAX_PAGES) truncated = true
                showFooter()
            } else if (pages.size == 1) {
                // 首屏一页可能填不满屏幕（短文件就是这样），此时 RecyclerView 不会触发滚动回调，
                // 于是"自动加载下一页"永远等不到。主动再取一页。
                loadNextPage()
            }
        }
    }

    private fun showFooter() {
        val footer = binding?.textFooter ?: return
        val loadedBytes = nextOffset
        footer.visibility = View.VISIBLE
        footer.text = when {
            truncated -> host.activity.getString(
                R.string.preview_text_truncated,
                Formatters.size(loadedBytes),
            )

            else -> host.activity.getString(
                R.string.preview_text_all_loaded,
                Formatters.size(loadedBytes),
            )
        }
    }

    private fun showError() {
        val created = binding ?: return
        created.textPages.visibility = View.GONE
        created.textFooter.visibility = View.VISIBLE
        created.textFooter.text = host.activity.getString(R.string.preview_failed_hint)
        created.textEncoding.text = ""
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(ItemTextPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.binding.pageText.text = pages[position].text
        }
    }

    private class PageHolder(val binding: ItemTextPageBinding) :
        RecyclerView.ViewHolder(binding.root)

    private companion object {

        /**
         * 一页 32 KB。
         *
         * 取值理由：按中文 UTF-8 约 3 字/10 字节算，32 KB ≈ 1000 行；
         * 单页 `TextView` 的 measure 在 5 ms 量级，滚动时不会掉帧。
         * 调到 128 KB 时单页测量能到 30 ms 以上，会出现肉眼可见的卡顿。
         */
        const val PAGE_BYTES = 32 * 1024

        /**
         * 最多加载的页数（200 × 32 KB ≈ 6.4 MB 文本）。
         *
         * 这个上限对应"用户按住一路滚到底"的极端情况，是防止把整个文件读进内存的闸门。
         */
        const val MAX_PAGES = 200
    }
}
