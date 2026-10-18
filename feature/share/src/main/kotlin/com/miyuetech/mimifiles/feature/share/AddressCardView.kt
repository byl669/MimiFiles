package com.miyuetech.mimifiles.feature.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import com.miyuetech.mimifiles.core.shareserver.AuthMode
import com.miyuetech.mimifiles.core.shareserver.ServerType
import com.miyuetech.mimifiles.core.shareserver.ShareAddress
import com.miyuetech.mimifiles.feature.share.databinding.ItemShareAddressBinding
import com.miyuetech.mimifiles.feature.share.databinding.ViewShareAddressCardBinding

/**
 * 「在电脑上访问」地址卡 —— FTP 与 HTTP **共用一个实现**（方案 §10.4「地址展示一致」）。
 *
 * ## 为什么是自定义 View 而不是一段布局 + 一堆 findViewById
 * 卡片的形状是"一个标题 + N 行地址 + 一块二维码"，N 由运行期决定
 * （WiFi / 热点 / 以太网会各贡献一个，还可能同时存在两个）。用 `<include>` +
 * 在 Activity 里手工增删子 View，会把"行怎么长"这件事泄漏到 Activity；
 * 封成 View 之后，面板那侧只有 `bind(...)` 一个调用，两个 Tab 完全共用。
 *
 * ## 地址为什么不带凭据（以及"看到的"与"复制到的"必须一致）
 * 行上显示与复制的是**同一个**串 —— 不含密码的 [ShareAddress.url]。理由有三条：
 *  1. 这一行的大字是给用户"照着自己念/敲"的；密码在面板的另一行单独显示，
 *     混在一起会让最重要的那个串长得没法一眼看完；
 *  2. 地址卡可能被截图分享（"帮我看看这个地址对不对"），而屏幕上单独一行
 *     `mimi / k7fq2x9p` 比"混在 URL 里的密码"更容易被用户意识到是敏感的；
 *  3. **"复制到的"与"看到的"必须是同一个东西**。让显示用干净地址、复制却塞进带密码的
 *     版本，用户粘出去之后会发现多了一段自己没看见的字符 —— 那比多按一次复制糟糕得多。
 *
 * [ShareAddress.urlWithCredentials] 是给"一步粘贴"准备的（FTP 客户端要填地址+账号+密码
 * 三格，带账号的 URL 能省两次输入），但**当前 UI 没有调用它**：
 * 在地址卡上再加一行"带账号地址 + 复制"会把这张卡的信息密度推到过高，
 * 而"复制密码"那一行已经覆盖了实际需要。留在 `core:share-server` 是因为它的语义明确、
 * 有单测钉死（含 `@`/`:` 的百分号编码），M5 若要把接入点做成"长按地址 → 复制带账号版本"
 * 时可以直接用，不需要重新推导一遍编码规则。
 */
class AddressCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = ViewShareAddressCardBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        orientation = VERTICAL
    }

    /** 复制地址后的回调（地址，复制到的文本）。用于埋日志与埋冒烟取证。 */
    var onCopy: ((ShareAddress, String) -> Unit)? = null

    /** 在电脑浏览器打开（HTTP 才有意义）。 */
    var onOpen: ((ShareAddress) -> Unit)? = null

    /**
     * 绑定数据。
     *
     * @param qr 二维码位图；`null` 或非 HTTP 协议时整块二维码区隐藏
     */
    fun bind(
        addresses: List<ShareAddress>,
        protocol: ServerType,
        credentials: AuthMode.Credentials?,
        qr: Bitmap?,
    ) {
        binding.addressRows.removeAllViews()
        binding.addressEmpty.isVisible = addresses.isEmpty()

        binding.addressHint.text = context.getString(
            if (protocol == ServerType.HTTP) {
                R.string.share_address_open
            } else {
                R.string.share_address_open_ftp
            },
        )

        for (address in addresses) {
            val row = ItemShareAddressBinding.inflate(
                LayoutInflater.from(context),
                binding.addressRows,
                false,
            )
            row.addressIface.text = address.interfaceLabel.ifBlank { address.host }
            row.addressText.text = address.url
            // 地址本身就是最常用的复制对象：把整行做成可点，比逼用户去点那个小图标好。
            row.addressText.setOnClickListener { copy(address) }
            row.addressCopy.setOnClickListener { copy(address) }
            if (protocol == ServerType.HTTP) {
                row.addressOpen.isVisible = true
                row.addressOpen.setOnClickListener { openInBrowser(address) }
            } else {
                // FTP 没有可靠的 ACTION_VIEW 处理器：某些客户端会抢注 ftp://，
                // 但结果多半是"打开一个空窗口"。藏掉比让它看起来能用更诚实。
                row.addressOpen.isVisible = false
            }
            binding.addressRows.addView(row.root)
        }

        val showQr = protocol == ServerType.HTTP && qr != null && addresses.isNotEmpty()
        binding.addressQrBox.isVisible = showQr
        if (showQr) binding.addressQr.setImageBitmap(qr)

        // `credentials` 目前只用于"是否需要登录"这一处判断（见类注释：地址卡不展示凭据）。
        // 保留这个参数是为了让调用点显式说明当前模式 —— 将来要加"带账号版本的复制入口"
        // 时不必再改签名，也不必回头去找"这个卡当初是按哪种认证模式渲染的"。
        binding.addressHint.isEnabled = credentials != null
    }

    private fun copy(address: ShareAddress) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(address.url, address.url))
        toast(context.getString(R.string.share_address_copy_ok))
        onCopy?.invoke(address, address.url)
    }

    private fun openInBrowser(address: ShareAddress) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address.url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (!ok) toast(context.getString(R.string.share_address_empty))
        onOpen?.invoke(address)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
