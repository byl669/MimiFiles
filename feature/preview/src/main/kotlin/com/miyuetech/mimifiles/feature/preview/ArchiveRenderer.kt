package com.miyuetech.mimifiles.feature.preview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miyuetech.mimifiles.core.archive.ArchiveEntry
import com.miyuetech.mimifiles.core.archive.ArchiveException
import com.miyuetech.mimifiles.core.archive.ArchiveExtractor
import com.miyuetech.mimifiles.core.archive.ArchiveFormat
import com.miyuetech.mimifiles.core.archive.ArchiveReader
import com.miyuetech.mimifiles.core.archive.ArchiveReaders
import com.miyuetech.mimifiles.core.archive.ExtractResult
import com.miyuetech.mimifiles.core.common.Formatters
import com.miyuetech.mimifiles.core.common.MimiLog
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.model.FsPath
import com.miyuetech.mimifiles.feature.preview.databinding.ItemArchiveEntryBinding
import com.miyuetech.mimifiles.feature.preview.databinding.ViewArchivePreviewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 压缩包渲染器 —— 列举条目 + 解压。
 *
 * ## 为什么"先落盘再解析"而不是直接流式读
 * zip / 7z 的目录表在**文件尾部**，rar 需要跨条目随机定位。给它们喂顺序流，
 * 每列一次目录就要从头解压一遍。所以远端压缩包先经 `PreviewCache` 落到私有缓存
 * （有容量上限，超限就如实告知并引导外发），之后所有解析都在本地文件上做。
 * 这与 PDF 的处理完全一致，理由也一样：**平台级解析器只吃随机访问的本地文件**。
 *
 * ## 为什么解压目标默认是"压缩包旁边的同名目录"
 * 用户的直觉是"我在这里解压，东西就该在这里"。放到别处（比如下载目录）会让用户
 * 解压完找不到文件 —— 那是文件管理器最常被吐槽的一点。
 * 同名目录已存在时**自动换名**（`name (2)`）而不是合并：静默覆盖用户的文件
 * 比多一个目录糟糕得多。
 */
class ArchiveRenderer(
    private val host: PreviewHost,
    private val path: FsPath,
) : PreviewRenderer {

    private var binding: ViewArchivePreviewBinding? = null

    private var reader: ArchiveReader? = null

    private var entries: List<ArchiveEntry> = emptyList()

    private var format: ArchiveFormat = ArchiveFormat.UNKNOWN

    private var openJob: Job? = null

    private var extractJob: Job? = null

    private val adapter = EntryAdapter()

    override val fullBleedContent: Boolean get() = false

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val created = ViewArchivePreviewBinding.inflate(inflater, parent, false)
        binding = created
        created.archiveEntries.layoutManager = LinearLayoutManager(host.activity)
        created.archiveEntries.adapter = adapter
        created.archiveEntries.setItemViewCacheSize(CACHE_ROWS)
        created.archiveAction.setOnClickListener { onActionClicked() }
        start()
        return created.root
    }

    override fun release() {
        openJob?.cancel()
        extractJob?.cancel()
        reader?.let { runCatching { it.close() } }
        reader = null
        binding?.archiveEntries?.adapter = null
        binding = null
    }

    override fun infoRows(): List<InfoRow> = buildList {
        add(InfoRow(R.string.preview_info_path, path.toString()))
        add(InfoRow(0, format.label))
        if (entries.isNotEmpty()) {
            add(InfoRow(0, host.activity.getString(R.string.preview_info_entries, entries.size)))
        }
        val unpacked = entries.filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
        if (unpacked > 0) {
            add(InfoRow(0, host.activity.getString(R.string.preview_info_unpacked, Formatters.size(unpacked))))
        }
    }

    // ── 打开与列举 ──────────────────────────────────────────────

    private fun start() {
        openJob?.cancel()
        openJob = host.scope.launch {
            // ① 判格式：扩展名优先，文件头兜底。
            val head = runCatching {
                FileSystems.registry.resolve(path).openRead(path).use { it.readBytes(0L, HEAD_BYTES) }
            }.getOrNull()
            format = ArchiveFormat.detect(path.displayName, head)

            if (!format.readable) {
                showHint(
                    host.activity.getString(
                        R.string.preview_archive_unsupported,
                        format.label,
                    ) + "\n\n" + host.activity.getString(R.string.preview_archive_unsupported_hint),
                    allowExternal = true,
                )
                return@launch
            }

            // ② 落盘（本机文件会直接返回自己，不产生拷贝）。
            val local = materialize() ?: run {
                showHint(
                    host.activity.getString(R.string.preview_archive_too_large) + "\n\n" +
                        host.activity.getString(R.string.preview_archive_too_large_hint),
                    allowExternal = true,
                )
                return@launch
            }

            // ③ 打开并列举。
            val opened = runCatching { ArchiveReaders.open(format, local) }.getOrNull()
            if (opened == null) {
                showHint(host.activity.getString(R.string.preview_archive_failed), allowExternal = true)
                return@launch
            }
            reader = opened
            val listed = runCatching { opened.list() }.getOrElse { error ->
                MimiLog.w(TAG, "列举压缩包失败：$path", error)
                if (error is ArchiveException) {
                    showHint(error.message ?: host.activity.getString(R.string.preview_archive_failed), allowExternal = true)
                } else {
                    showHint(host.activity.getString(R.string.preview_archive_failed), allowExternal = true)
                }
                return@launch
            }
            entries = listed
            showSummary()
            binding?.archiveProgress?.visibility = View.GONE
            adapter.submit(entries.take(MAX_ROWS))
            if (entries.size > MAX_ROWS) {
                // 条目过多时只渲染前面一部分：列表是虚拟化的，但 20 万个条目的
                // 列表数据本身也要内存，而且没人会靠滚动找第 10 万个文件。
                binding?.archiveFooter?.visibility = View.VISIBLE
                binding?.archiveFooter?.text = host.activity.getString(
                    R.string.preview_archive_truncated,
                    MAX_ROWS,
                )
            }
            binding?.archiveAction?.visibility = View.VISIBLE
            binding?.archiveAction?.setText(R.string.preview_archive_extract)
        }
    }

    /**
     * 把压缩包变成本地可读文件。
     *
     * 本机文件直接返回；远端走预览落盘缓存（有上限）。用 `expectedSize` 让缓存
     * 提前知道能不能装下，避免"下到一半才发现超限"。
     */
    private suspend fun materialize(): File? {
        if (path.scheme == "local") {
            val direct = File(path.path)
            if (direct.isFile) return direct
        }
        val size = runCatching { FileSystems.registry.resolve(path).stat(path).size }.getOrDefault(-1L)
        return host.environment.cache.materialize(path, size)
    }

    private fun showSummary() {
        val created = binding ?: return
        val unpacked = entries.filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
        created.archiveSummary.text = if (!format.multiEntry) {
            host.activity.getString(
                R.string.preview_archive_summary_flat,
                format.label,
                entries.firstOrNull()?.name ?: path.displayName,
            )
        } else {
            host.activity.getString(
                R.string.preview_archive_summary,
                format.label,
                entries.count { !it.isDirectory },
                if (unpacked > 0) Formatters.size(unpacked) else "—",
            )
        }
    }

    private fun showHint(text: String, allowExternal: Boolean) {
        val created = binding ?: return
        created.archiveProgress.visibility = View.GONE
        created.archiveHint.visibility = View.VISIBLE
        created.archiveHint.text = text
        created.archiveAction.visibility = if (allowExternal) View.VISIBLE else View.GONE
        created.archiveAction.setText(R.string.preview_action_open_with)
    }

    // ── 解压 ────────────────────────────────────────────────────

    private fun onActionClicked() {
        // 三个状态共用同一个按钮：解压中 → 取消；不可解析 → 外发；可解析 → 解压。
        if (extractJob?.isActive == true) {
            extractJob?.cancel()
            return
        }
        if (reader == null) {
            host.openWithExternalApp(null)
            return
        }
        extractAll()
    }

    private fun extractAll() {
        val current = reader ?: return
        val created = binding ?: return
        created.archiveAction.setText(R.string.preview_archive_cancel)
        created.archiveFooter.visibility = View.VISIBLE
        created.archiveHint.visibility = View.GONE

        extractJob = host.scope.launch {
            val targetFs = runCatching { FileSystems.registry.resolve(path) }.getOrNull()
            if (targetFs == null) {
                finishWith(getStringSafe(R.string.preview_archive_failed))
                return@launch
            }
            val root = uniqueTargetDir()
            val extractor = ArchiveExtractor(current, targetFs)
            try {
                val result = extractor.extract(root, onProgress = { p ->
                    created.archiveFooter.text = host.activity.getString(
                        R.string.preview_archive_working,
                        p.done,
                        p.total,
                        Formatters.size(p.bytesWritten),
                    )
                })
                finishWith(describe(result))
            } catch (e: CancellationException) {
                finishWith(getStringSafe(R.string.preview_archive_cancelled))
                throw e
            } catch (e: ArchiveException) {
                MimiLog.w(TAG, "解压失败：$path", e)
                finishWith(e.message ?: getStringSafe(R.string.preview_archive_failed))
            } catch (e: Throwable) {
                MimiLog.w(TAG, "解压异常：$path", e)
                finishWith(e.message ?: getStringSafe(R.string.preview_archive_failed))
            }
        }
    }

    private fun finishWith(text: String) {
        val created = binding ?: return
        created.archiveAction.setText(R.string.preview_archive_extract)
        created.archiveFooter.text = text
        host.toast(text)
    }

    private fun describe(result: ExtractResult): String = if (result.skippedUnsafe > 0) {
        host.activity.getString(
            R.string.preview_archive_done_skipped,
            result.files,
            result.targetDir.displayName,
            result.skippedUnsafe,
        )
    } else {
        host.activity.getString(
            R.string.preview_archive_done,
            result.files,
            result.targetDir.displayName,
        )
    }

    private fun getStringSafe(res: Int): String = host.activity.getString(res)

    /**
     * 目标目录：压缩包同级的同名目录；已存在则依次尝试 `name (2)`、`name (3)`…
     *
     * ## 为什么不做"合并进已存在的目录"
     * 合并会**静默覆盖**用户的文件。文件管理器里最不可接受的失败就是"我解压了一下，
     * 原来的东西没了"。多一个 `(2)` 目录虽然不够聪明，但它不会造成任何损失。
     * 上限 20 次是防止目录名生成陷入死循环；到上限时退回带时间戳的名字。
     */
    private suspend fun uniqueTargetDir(): FsPath {
        val parent = path.parent ?: path
        val base = baseName()
        val fs = FileSystems.registry.resolve(path)
        var candidate = parent.child(base)
        if (!fs.exists(candidate)) return candidate
        for (n in 2..MAX_RENAME_TRIES) {
            candidate = parent.child("$base ($n)")
            if (!fs.exists(candidate)) return candidate
        }
        return parent.child("$base ${System.currentTimeMillis()}")
    }

    /** 去掉压缩后缀作为目录名：`photos.zip` → `photos`，`logs.txt.gz` → `logs.txt`。 */
    private fun baseName(): String {
        val name = path.displayName
        val ext = name.substringAfterLast('.', "")
        val stripped = if (ext.isNotEmpty() && format.extensions.any { it.equals(ext, true) }) {
            name.dropLast(ext.length + 1)
        } else {
            name.substringBeforeLast('.', name)
        }
        return stripped.ifEmpty { "解压" }
    }

    // ── 列表 ────────────────────────────────────────────────────

    private inner class EntryAdapter : RecyclerView.Adapter<EntryHolder>() {

        private var rows: List<ArchiveEntry> = emptyList()

        fun submit(next: List<ArchiveEntry>) {
            rows = next
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder =
            EntryHolder(
                ItemArchiveEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            holder.bind(rows[position])
        }
    }

    private inner class EntryHolder(
        private val binding: ItemArchiveEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var entry: ArchiveEntry? = null

        init {
            binding.root.setOnClickListener {
                val target = entry ?: return@setOnClickListener
                onEntryClicked(target)
            }
        }

        fun bind(item: ArchiveEntry) {
            entry = item
            binding.entryIcon.setImageResource(
                if (item.isDirectory) R.drawable.ic_archive_folder else R.drawable.ic_archive_file,
            )
            binding.entryName.text = item.displayName
            // ⚠️ 危险路径要**当场**告诉用户，而不是等解压完再报"跳过了 2 个"。
            // 一个名为 `../evil.txt` 的条目是压缩包在试图往外写文件，
            // 用户看到列表里出现它本身就是有用信息（这个包不可信）。
            val unsafe = ArchiveExtractor.safeSegments(item.name) == null
            val parent = item.parentPath
            val hint = if (unsafe) {
                host.activity.getString(R.string.preview_archive_unsafe_path)
            } else {
                parent
            }
            binding.entryPath.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
            binding.entryPath.text = hint
            binding.entryPath.setTextColor(
                if (unsafe) {
                    androidx.core.content.ContextCompat.getColor(
                        host.activity,
                        com.miyuetech.mimifiles.core.ui.R.color.status_error,
                    )
                } else {
                    androidx.core.content.ContextCompat.getColor(
                        host.activity,
                        com.miyuetech.mimifiles.core.ui.R.color.text_secondary,
                    )
                },
            )
            binding.entrySize.text = when {
                item.isDirectory -> ""
                item.size > 0 -> Formatters.size(item.size)
                else -> ""
            }
        }
    }

    /**
     * 点条目 = 只解压这一项。
     *
     * 为什么不做"点开条目内的文件直接预览"：那需要把条目流出来再走一遍预览路由，
     * 而 zip 内的图片/视频往往是几十张一组，逐张解到临时目录再预览会让缓存迅速膨胀。
     * **先解压、再从正常路径打开**是更可预期也更省事的方式。
     */
    private fun onEntryClicked(item: ArchiveEntry) {
        val current = reader ?: return
        if (extractJob?.isActive == true) {
            host.toast(host.activity.getString(R.string.preview_archive_working, 0, 0, "…"))
            return
        }
        val title = if (item.isDirectory) {
            host.activity.getString(R.string.preview_archive_entry_action_dir)
        } else {
            host.activity.getString(R.string.preview_archive_entry_action)
        }
        androidx.appcompat.app.AlertDialog.Builder(host.activity)
            .setTitle(item.name)
            .setMessage(title)
            .setPositiveButton(title) { _, _ -> extractOne(item) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun extractOne(item: ArchiveEntry) {
        val current = reader ?: return
        val created = binding ?: return
        // 目录条目要连它的子项一起解；文件条目就解它自己。
        val selection = if (item.isDirectory) {
            entries.filter { it.name == item.name || it.name.startsWith(item.name) }
        } else {
            listOf(item)
        }
        if (selection.isEmpty()) return

        created.archiveAction.setText(R.string.preview_archive_cancel)
        created.archiveFooter.visibility = View.VISIBLE
        extractJob = host.scope.launch {
            val targetFs = runCatching { FileSystems.registry.resolve(path) }.getOrNull()
            if (targetFs == null) {
                finishWith(getStringSafe(R.string.preview_archive_failed))
                return@launch
            }
            val root = if (item.isDirectory) uniqueTargetDir() else (path.parent ?: path)
            val extractor = ArchiveExtractor(current, targetFs)
            try {
                val result = extractor.extract(root, entries = selection, onProgress = { p ->
                    created.archiveFooter.text = host.activity.getString(
                        R.string.preview_archive_working,
                        p.done,
                        p.total,
                        Formatters.size(p.bytesWritten),
                    )
                })
                finishWith(describe(result))
            } catch (e: CancellationException) {
                finishWith(getStringSafe(R.string.preview_archive_cancelled))
                throw e
            } catch (e: Throwable) {
                MimiLog.w(TAG, "解压单项失败：${item.name}", e)
                finishWith(e.message ?: getStringSafe(R.string.preview_archive_failed))
            }
        }
    }

    private companion object {
        const val TAG = "MimiArchive"

        /** 判格式用的文件头长度：必须 ≥ 262（tar 的 `ustar` 在 257）。 */
        const val HEAD_BYTES = 512

        /** 列表最多渲染多少个条目（解压不受此限制）。 */
        const val MAX_ROWS = 5000

        const val CACHE_ROWS = 16

        const val MAX_RENAME_TRIES = 20
    }
}
