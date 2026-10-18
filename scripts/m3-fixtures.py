#!/usr/bin/env python3
"""M3 冒烟用的测试素材生成器。

## 为什么要"生成"而不是"找几个现成文件"
冒烟里的每条判据都需要**特定的**文件特性，随手拿一个真实文件往往同时缺好几样：

| 素材 | 为什么需要它 |
|---|---|
| 大图（4200×4200） | 「分块解码」只在图**大于** `ImageMeta.LARGE_EDGE`（4096）时才触发 |
| 小图（640×480） | 对照组：走"降采样整图"路线 |
| GBK 文本 | 证明编码探测没把中文读成乱码（最容易翻车的一条） |
| 纯 ASCII 文本 | 对照组：应判为 UTF-8 而不是 GBK |
| 最小 PDF | `PdfRenderer` 只认**合法**的 PDF |
| 改错扩展名的文本（.bin） | 证明魔数判定生效，而不是只看后缀 |
| 随机二进制 | 证明"不支持的类型"会明确告知，而不是静默无反应或崩溃 |
| 动态照片（JPEG + 尾部 MP4） | 证明 MotionPhoto 的尾部扫描能认出内嵌视频 |
| 大日志（> 512 KB） | 触发 TextReader 的分段加载，验证"不 OOM 那条线" |

合成素材的好处是**每个文件的特性都是已知最小的**：出问题时能立刻区分
"是应用错了"还是"素材不符合预期"。

## 依赖策略
JPEG 用 PIL（位图编码手写太容易出错，一个坏表就变成"应用打不开图片"的假故障）；
PNG 与 PDF 走标准库手写（结构简单、字节可控）。PIL 缺失时自动回退：
JPEG 素材用 PNG 替代并在输出里标注，脚本不会整体失败。
"""

import os
import struct
import sys
import zlib

try:
    from PIL import Image, ImageDraw  # type: ignore

    HAS_PIL = True
except Exception:  # pragma: no cover - 环境缺库时的降级路径
    HAS_PIL = False


def ensure_dir(path):
    os.makedirs(path, exist_ok=True)


def write_png(path, width, height, rgb):
    """写一个纯色 PNG（标准库手写，不依赖 PIL）。

    PNG 的最小结构只有 IHDR / IDAT / IEND 三段，手写不到 20 行；
    而它作为"尺寸可控"的素材非常合适——4200×4200 就是为了逼出分块解码。
    """
    row = bytes(rgb) * width
    raw = b"".join(b"\x00" + row for _ in range(height))  # 每行前置一个 filter 字节

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


def write_jpeg(path, width, height, rgb, quality=85):
    if HAS_PIL:
        Image.new("RGB", (width, height), rgb).save(path, "JPEG", quality=quality)
    else:
        # 降级：PIL 缺失时用 PNG 顶上（内容仍是一张能解码的图片，
        # 只是扩展名与真实格式不一致——脚本会把这件事打印出来，不静默）。
        write_png(path.replace(".jpg", ".png"), width, height, rgb)
        return False
    return True


def write_minimal_pdf(path, text):
    """手写最小可用 PDF（一页，Helvetica 一行字）。

    不引 reportlab：这条素材要验证的是 `PdfRenderer` 能否渲染，
    而不是"某个库能否生成 PDF"。结构最小意味着出问题时一眼能看出
    是不是素材本身不合法（这也是"素材特性已知最小"这条原则的实例）。
    """
    content = f"BT /F1 24 Tf 72 700 Td ({text}) Tj ET".encode("latin-1")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for index, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{index} 0 obj\n".encode() + body + b"\nendobj\n"

    xref_pos = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode() + b"0000000000 65535 f \n"
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF\n"
    ).encode()

    with open(path, "wb") as f:
        f.write(bytes(out))


def write_striped_png(path, width, height, palette, bands=2):
    """
    写一张**多色块**PNG（竖条 × 横带），用于画廊翻页用例。

    ## 为什么必须是"多色"而且必须用 PNG
    这条用例的判据是"截图上这块区域的**独立颜色数**"。两个前提：
      · 图案必须真的有很多种颜色 —— 一张纯色图，无论正确渲染还是退化成 1 个像素，
        颜色数都是 1，**区分不出来**；
      · 必须用 PNG。JPEG 的块效应会让一块纯色区域也出现成百上千种颜色，
        判据立刻失去意义（这条踩过：先写了 JPEG，量出来"颜色数 3000+"，
        而退化版本同样报 3000+）。
    用 `ImageDraw` 画矩形而不是逐像素赋值：1080×2160 逐像素在纯 Python 里要几十秒，
    而这里每张图只需要十几个矩形。

    ## 为什么每张图的主色不同
    翻页时"现在显示的是哪一张"要能一眼看出（颜色家族不同），
    于是"翻到第 6 张却还是第 5 张的画面"这类问题也抓得到。
    """
    if not HAS_PIL:
        return False
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    columns = len(palette)
    band_h = height // bands
    for row in range(bands):
        for col in range(columns):
            r, g, b = palette[col]
            # 上排用原色、下排整体压暗：两排颜色也不同，纵向平移时也有变化可测。
            if row == 1:
                r, g, b = r // 2, g // 2, b // 2
            x1 = col * width // columns
            x2 = (col + 1) * width // columns
            draw.rectangle([x1, row * band_h, x2, (row + 1) * band_h], fill=(r, g, b))
    # 再叠一条细细的中线：给"渲染分辨率是否退化"留一个高频信号
    # （整幅图被降采样到几十像素时，细线会最先糊掉或消失）。
    draw.rectangle(
        [0, height // 2 - max(1, height // 400), width, height // 2 + max(1, height // 400)],
        fill=(255, 255, 255),
    )
    image.save(path, "PNG")
    return True


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/m3fixtures"
    ensure_dir(out_dir)

    # ① 大图：触发分块解码。
    #
    # ⚠️ 尺寸不是随手定的。要让"放大后补一块更清晰的"这件事真的发生，
    # 必须满足：**放大后的视口所需采样 < 基准位图的采样**。
    # 基准采样由 `baseDecodeTarget = min(2 × 屏幕长边, 2560)` 决定：
    #   · 4200 px → 采样 2，双击 2.5× 后的视口需要采样 1 → 按理该补块，
    #     但 4200 的视口算出来恰好等于 2（四舍五入到 2 的幂），于是"不需要补"——
    #     逻辑正确，只是这条素材**验证不到分块链路**（M3 第一轮就卡在这里）。
    #   · 8000 px → 采样 4，双击后视口需要采样 2 → 明确需要补块 ✓
    # 这也更贴近真实场景：8000 px 是"单反/扫描件"的量级，正是分块解码存在的理由。
    write_png(os.path.join(out_dir, "huge.png"), 8000, 8000, (40, 120, 200))

    # ② 普通图
    write_png(os.path.join(out_dir, "small.png"), 640, 480, (200, 90, 60))

    # ③ JPEG
    jpeg_ok = write_jpeg(os.path.join(out_dir, "photo.jpg"), 320, 240, (60, 180, 90))

    # ④ GBK 文本
    with open(os.path.join(out_dir, "gbk.txt"), "wb") as f:
        f.write("中文编码测试\n第二行：这是 GBK。\n".encode("gbk"))

    # ⑤ ASCII 文本（对照组）
    with open(os.path.join(out_dir, "ascii.txt"), "wb") as f:
        f.write(b"plain ascii line 1\nline 2\n")

    # ⑥ 最小 PDF
    write_minimal_pdf(os.path.join(out_dir, "doc.pdf"), "MimiFiles M3 smoke")

    # ⑦ 改错扩展名的文本（魔数校正用例）
    with open(os.path.join(out_dir, "renamed.bin"), "wb") as f:
        f.write("这个文件其实是一段文本，但扩展名是 .bin。\n".encode("utf-8"))

    # ⑧ 纯二进制（应判为"不支持"）
    with open(os.path.join(out_dir, "blob.dat"), "wb") as f:
        f.write(bytes([0x00, 0x01, 0x02, 0x03, 0xFF, 0xFE, 0x00, 0x10]) * 64)

    # ⑨ 动态照片：JPEG + 尾部"MP4"（带 ftyp box，长度超过 MIN_EMBEDDED_VIDEO_BYTES）
    if jpeg_ok:
        with open(os.path.join(out_dir, "photo.jpg"), "rb") as f:
            jpeg_bytes = f.read()
    else:
        with open(os.path.join(out_dir, "small.png"), "rb") as f:
            jpeg_bytes = f.read()
    fake_mp4 = (
        struct.pack(">I", 24)
        + b"ftypisom"
        + b"\x00\x00\x02\x00"
        + b"isomiso2"
        + b"\x00" * 150000
    )
    with open(os.path.join(out_dir, "motion.jpg"), "wb") as f:
        f.write(jpeg_bytes + fake_mp4)

    # ⑩ 大日志（> 512 KB，触发分段加载）
    with open(os.path.join(out_dir, "large.log"), "w", encoding="utf-8") as f:
        for i in range(20000):
            f.write(f"2026-10-14 13:00:{i % 60:02d} INFO line {i} 中文日志内容\n")

    # ⑪ 画廊翻页素材：8 张多色图（M3.1.1 的"翻几页后变单色色块"回归用例）
    #
    # 尺寸与手机屏同比例（1080×2160），这样图片铺满屏幕、截图取样区域不会落在黑边上。
    # 八张的主色刻意分属不同色相，翻页时"现在是第几张"一眼可辨。
    palettes = [
        [(220, 60, 60), (240, 120, 60), (250, 200, 60), (200, 220, 80)],
        [(60, 180, 90), (60, 220, 160), (80, 200, 220), (60, 140, 200)],
        [(120, 80, 220), (170, 90, 230), (220, 110, 210), (240, 150, 200)],
        [(240, 200, 40), (250, 160, 40), (245, 120, 40), (235, 80, 40)],
        [(40, 90, 160), (60, 130, 200), (90, 170, 230), (130, 200, 240)],
        [(200, 40, 120), (230, 70, 150), (240, 110, 180), (250, 160, 210)],
        [(90, 120, 60), (130, 160, 80), (170, 190, 100), (200, 210, 130)],
        [(230, 230, 230), (200, 200, 200), (160, 160, 160), (120, 120, 120)],
    ]
    gallery_ok = True
    for index, palette in enumerate(palettes, start=1):
        name = f"gallery-{index:02d}.png"
        if not write_striped_png(os.path.join(out_dir, name), 1080, 2160, palette):
            gallery_ok = False
            break

    print(f"PIL={'yes' if HAS_PIL else 'no（JPEG 已降级为 PNG）'}")
    if not gallery_ok:
        print("  [warn] 缺 PIL，画廊翻页素材未生成（M3.1.1 的 G 系列用例会跳过）")
    for name in sorted(os.listdir(out_dir)):
        full = os.path.join(out_dir, name)
        print(f"  {name:16} {os.path.getsize(full):>10} bytes")


if __name__ == "__main__":
    main()
