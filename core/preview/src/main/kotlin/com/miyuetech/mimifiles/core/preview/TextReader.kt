package com.miyuetech.mimifiles.core.preview

import com.miyuetech.mimifiles.core.fs.FileSystemRegistry
import com.miyuetech.mimifiles.core.fs.FileSystems
import com.miyuetech.mimifiles.core.fs.SeekableSource
import com.miyuetech.mimifiles.core.model.FsPath
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码探测与解码 —— 方案 §6.4 第一行。
 *
 * ## 为什么不能"一律按 UTF-8 读"
 * 中文用户的 txt 里 GBK 占比很高（老编辑器、Windows 记事本默认 ANSI）。
 * 用 UTF-8 硬读 GBK 的典型症状是**满屏问号或乱码方块**，而且用户往往归因于
 * "这个应用不支持中文"，而不是"编码猜错了"。
 *
 * ## 判定顺序（从强到弱）
 * 1. **BOM**：最强证据，`EF BB BF` / `FF FE` / `FE FF`。有 BOM 就不再猜。
 * 2. **严格 UTF-8 校验**：以"能完整解码且不出现替换字符"为准。
 *    UTF-8 的结构约束很强（多字节序列的前缀位固定），所以**能通过校验的字节流
 *    几乎一定是 UTF-8**，而 GBK 文本通过校验的概率很低（这就是"先试 UTF-8"可行的原因）。
 * 3. **GBK**：UTF-8 校验失败时的首选。判据是"解码结果里 CJK 字符 + ASCII 可打印
 *    字符占比足够高"。这一步不能直接用"解码不抛异常"——GBK 几乎能解任何字节串。
 * 4. **Latin-1**：兜底，保证任何字节流都能显示（至少不会丢字节）。
 */
object TextDecoder {

    enum class Encoding(val label: String, val charsetName: String) {
        UTF_8("UTF-8", "UTF-8"),
        UTF_8_BOM("UTF-8 (BOM)", "UTF-8"),
        UTF_16_LE("UTF-16 LE", "UTF-16LE"),
        UTF_16_BE("UTF-16 BE", "UTF-16BE"),
        GBK("GBK", "GBK"),
        LATIN_1("Latin-1", "ISO-8859-1"),
    }

    /** 探测编码所需的最小样本；样本太短时只做 BOM 判断。 */
    const val SAMPLE_BYTES = 16 * 1024

    fun detect(sample: ByteArray): Encoding {
        if (sample.size >= 3 &&
            sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()
        ) {
            return Encoding.UTF_8_BOM
        }
        if (sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) {
            return Encoding.UTF_16_LE
        }
        if (sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) {
            return Encoding.UTF_16_BE
        }
        if (sample.isEmpty()) return Encoding.UTF_8
        if (isStrictUtf8(sample)) return Encoding.UTF_8
        if (looksLikeGbk(sample)) return Encoding.GBK
        return Encoding.LATIN_1
    }

    fun charsetOf(encoding: Encoding): Charset = Charset.forName(encoding.charsetName)

    fun decode(bytes: ByteArray, encoding: Encoding): String {
        val payload = when (encoding) {
            Encoding.UTF_8_BOM -> if (bytes.size >= 3) bytes.copyOfRange(3, bytes.size) else bytes
            else -> bytes
        }
        // 解码失败（比如我们只拿到半页、尾部正好切在一个多字节字符中间）时
        // 用"替换不可映射字符"重解一次，而不是把异常抛给 UI。
        return runCatching {
            charsetOf(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        }.getOrElse { String(payload, Charsets.ISO_8859_1) }
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * GBK 的"像不像"判据。
     *
     * 统计三类字符的占比：ASCII 可打印、CJK 汉字、CJK 标点。
     * 求"占比高"而不是"没有非法字符"，因为 GBK 双字节区几乎覆盖了 `0x81~0xFE`，
     * 任意二进制都能被"成功"解码成一堆生僻字——那种结果里生僻字占比会很高，
     * 但**常见汉字与 ASCII 的占比会很低**，正好被这个判据排除。
     */
    private fun looksLikeGbk(bytes: ByteArray): Boolean {
        val text = runCatching {
            Charset.forName("GBK").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return false
        if (text.isEmpty()) return false
        var good = 0
        var total = 0
        for (ch in text) {
            total++
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> good++
                ch.code in 0x20..0x7E -> good++
                // CJK 统一表意文字（含扩展 A）
                ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF -> good++
                // CJK 标点与全角形式
                ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF -> good++
                ch.code in 0x0400..0x04FF -> good++ // 西里尔：GBK 里有，且不算乱码
                ch.code in 0x0370..0x03FF -> good++ // 希腊字母同理
                else -> Unit
            }
        }
        return total > 0 && good.toDouble() / total >= 0.90
    }
}

/**
 * 分段文本读取器 —— "1 GB 文件预览不 OOM"这条验收门槛的实现。
 *
 * ## 关键设计：把"切页"做在**字节**上，而不是字符上
 * 多字节编码里"第 5000 个字符"与"第 5000 个字节"不是一个位置，而且
 * 反算要遍历整段文本。这里改成：按字节预算读一块，然后**在字节数组里找最后一个
 * `0x0A`** 作为切点，只解码切点之前的部分。
 *
 * 这个做法成立的前提是：`0x0A` 不可能是 UTF-8 多字节序列的一部分（UTF-8 的续字节
 * 是 `0x80~0xBF`），GBK 的尾字节范围是 `0x40~0xFE` 且**排除 `0x7F`**（因此也排除
 * `0x0A`）。所以找到的每个 `0x0A` 都是真正的行尾，下一页一定从一个字符边界开始。
 *
 * ## 兜底：没有换行的巨型单行文件
 * minified JSON、整段 base64 这类内容没有换行，按预算硬切。此时下一页可能落在
 * 字符中间，用 [alignToCharBoundary] 往后挪 1~3 个字节修正——
 * 代价是这一页末尾可能少一个字，收益是不会出现乱码方块。
 */
class TextReader private constructor(
    private val source: SeekableSource,
    val encoding: TextDecoder.Encoding,
    val size: Long,
) : Closeable {

    /**
     * 读一页。
     *
     * @param fromOffset 起始字节偏移（必须是上一次返回的 [TextPage.nextOffset]）
     * @param byteBudget 本页最多读多少字节（默认 128 KB，约 2000~4000 行）
     */
    suspend fun page(fromOffset: Long, byteBudget: Int = DEFAULT_BUDGET): TextPage =
        withContext(PreviewDispatchers.io) {
            val start = fromOffset.coerceAtLeast(0)
            val empty = TextPage("", start, start, start, encoding.label, true, 0)
            if (size >= 0 && start >= size) return@withContext empty

            val chunk = source.readBytes(start, byteBudget)
            if (chunk.isEmpty()) return@withContext empty

            val slice = sliceAtLineBoundary(chunk, byteBudget)
            val text = TextDecoder.decode(slice.bytes, encoding)
            val consumed = slice.headSkip + slice.bytes.size
            val end = start + consumed
            val eof = (size >= 0 && end >= size) || chunk.size < byteBudget
            TextPage(
                text = text,
                startOffset = start,
                endOffset = end,
                nextOffset = end,
                encodingLabel = encoding.label,
                eof = eof,
                lineCount = countLines(text),
            )
        }

    private class Slice(val bytes: ByteArray, val headSkip: Int)

    /**
     * 把读到的字节块切成"以字符边界起止"的一段。
     *
     * 两种情况：
     *  1. **块里有换行**（正常文本）：在最后一个 `0x0A` 处切。
     *     因为 `0x0A` 既不可能是 UTF-8 的续字节，也不可能是 GBK 的尾字节，
     *     所以切点两侧必然都是字符边界 —— 不需要任何修正。
     *  2. **块里没有换行**（minified JSON、base64 大块）：只能按预算硬切。
     *     此时块首可能落在某个字符的中间（上一页硬切留下的），
     *     于是往后跳过续字节；块尾的半个字符**留着不处理**——
     *     它会在解码时变成一个替换字符，出现在屏幕最后一列。
     *     这是刻意的取舍：为了一个字去回退/重读整页不划算，
     *     而"最后一列偶尔有个小方块"比"整页错位"轻得多。
     */
    private fun sliceAtLineBoundary(chunk: ByteArray, byteBudget: Int): Slice {
        val lastNewline = chunk.lastIndexOf(LF)
        if (lastNewline >= 0 && chunk.size >= byteBudget) {
            // 保留换行本身，让它在新的一页开头形成独立的一行。
            return Slice(chunk.copyOf(lastNewline + 1), 0)
        }
        if (lastNewline == chunk.size - 1) return Slice(chunk, 0)

        var headSkip = 0
        if (encoding == TextDecoder.Encoding.UTF_8 || encoding == TextDecoder.Encoding.UTF_8_BOM) {
            // UTF-8 续字节是 `10xxxxxx`；跳过它们直到落在一个首字节上。
            while (headSkip < 3 && headSkip < chunk.size &&
                (chunk[headSkip].toInt() and 0xC0) == 0x80
            ) {
                headSkip++
            }
        }
        return if (headSkip == 0) {
            Slice(chunk, 0)
        } else {
            Slice(chunk.copyOfRange(headSkip, chunk.size), headSkip)
        }
    }

    override fun close() {
        runCatching { source.close() }
    }

    companion object {
        private const val LF = 0x0A.toByte()

        /** 128 KB：在 1080p 屏上大约能填 2000~4000 行，足够"一屏滚得停不下来"却不至于卡住主线程。 */
        const val DEFAULT_BUDGET = 128 * 1024

        /** 超过这个大小就走分段加载（否则一次性读进来更快也更简单）。 */
        const val INLINE_LIMIT = 512 * 1024

        suspend fun open(
            path: FsPath,
            registry: FileSystemRegistry = FileSystems.registry,
        ): TextReader {
            val fileSystem = registry.resolve(path)
            val source = fileSystem.openRead(path)
            // 用前 16 KB 探测编码。样本必须来自文件开头：GBK/UTF-8 的统计特征
            // 在任意偏移处取样都可能失真（例如正巧取到一段纯 ASCII）。
            val sample = source.readBytes(0, TextDecoder.SAMPLE_BYTES)
            return TextReader(source, TextDecoder.detect(sample), source.size)
        }

        private fun countLines(text: String): Int {
            if (text.isEmpty()) return 0
            var lines = 1
            for (ch in text) if (ch == '\n') lines++
            return lines
        }
    }
}

/** 一页文本。 */
data class TextPage(
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
    val nextOffset: Long,
    val encodingLabel: String,
    val eof: Boolean,
    val lineCount: Int,
)
