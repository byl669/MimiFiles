package com.miyuetech.mimifiles.core.shareserver.http

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 地址 → 二维码。
 *
 * ## 全程本地，不联网
 * 方案 §10.4「二维码一致」要求"本地生成，扫码打开网页"。这一条其实是个**安全要求**
 * 而不是实现细节：调第三方 QR 接口（`api.qrserver.com/?data=…` 之类）会把
 * `192.168.2.5:2121` 这样确切的内网地址送到公网服务器上，等于向外部报告了
 * "这个时间点、这个网段有一台设备在开分享服务"。所以只用 zxing 在本地算。
 *
 * ## 为什么画成纯黑白 + 小边距
 * 二维码是被**另一台设备用摄像头**扫的，可能出现：屏幕反光、相机分辨率不足、
 * 手机屏幕自动亮度偏低。色彩化的二维码（有些库支持前景/背景色）在这些条件下
 * 识别率显著下降，而它换来的"好看"在分享面板里没有价值。
 * 边距取 1 个模块（zxing 默认 4）：4 会让二维码在本就不大的卡片里缩得更小，
 * 而"卡片内自带内边距"已经起到了同样的静区作用。
 */
object QrCodeUtil {

    /** 生成二维码位图。内容非法（超长）时返回 `null`，由 UI 退化成"只显示地址文本"。 */
    fun encode(content: String, sizePx: Int = DEFAULT_SIZE): Bitmap? {
        if (content.isBlank()) return null
        val side = sizePx.coerceIn(128, 1024)
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, side, side, hints)
            val w = matrix.width
            val h = matrix.height
            // 逐点 setPixel 在 512² 上是 26 万次调用，实机要几十毫秒；
            // 用 IntArray + createBitmap 一次成型，等价但快一个量级——
            // 这个函数在面板每次启动/切 Tab 时都会被调用。
            val pixels = IntArray(w * h)
            val black = Color.BLACK
            val white = Color.WHITE
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) black else white
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            // 内容过长（> 约 2900 字节）时 zxing 抛 WriterException。
            // 我们的地址最长也就几十字节，真到这一步说明是异常输入，不该崩面板。
            null
        }
    }

    /** 512：在旗舰手机上扫码距离 20~30 cm 都能稳定识别，同时不至于撑爆面板布局。 */
    const val DEFAULT_SIZE = 512
}
