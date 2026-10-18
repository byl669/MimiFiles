#!/usr/bin/env python3
"""
M4.1 · 界面取证工具

## 为什么需要它
"深色模式下有些按键是黑色的" 这句话，人眼一看就懂，但它**没法进冒烟脚本** ——
除非把"黑色"变成一个可以计算的量。这个文件做的就是这件事：
把"图标与它背后的底色差多少"算成 **WCAG 对比度**，再按阈值断言。

阈值取 3.0：WCAG 2.1 对"图形对象与用户界面组件"（non-text contrast）的要求正是 3:1。
取 4.5 会过严（那是正文文字的标准，图标笔画本身较细，抗锯齿会让实测值偏低）。

## 怎么算
给定一个矩形区域，区域内像素分两簇：
  · **底色** = 出现次数最多的那个颜色（背景总是面积最大的）
  · **图形** = **除底色之外出现次数最多的那个颜色**
对比度 = (L_亮 + 0.05) / (L_暗 + 0.05)，L 用 WCAG 的相对亮度。

## ⚠️ 图形色为什么取"众数"而不是"分位数"（这条踩过）
第一版取的是"离底色最远的那 5% 像素的中位色"。它在**粗笔画**图标上没问题，
在**细笔画**上会系统性低估：一个 40dp 按钮里画 3 个点（行尾「更多」⋮），
核心笔画只有 ~207 像素，而它的抗锯齿边缘有上千个各不相同的中间色 ——
"最远 5%"这一撮里绝大多数是边缘色，中位色于是落在 `#595959`，
量出 2.72（未达标），而笔画真实颜色是 `#a0a0a0`，对比度 7.4（完全达标）。

众数没有这个问题：抗锯齿边缘**颜色各不相同**，每个的计数都很小；
而笔画核心是**同一个颜色反复出现**，计数最大。"出现次数最多的非底色"
就是笔画的核心色 —— 与笔画粗细无关。

**前提**：本判据只用于**纯色背景**上的图标区域（工具栏、列表行、空状态）。
背景有渐变或照片时，"非底色众数"可能落在另一个背景色上，结论无意义。

**标签会互换**：`bg=` 是"面积占多数的那个颜色"，`fg=` 是另一个。当一个**实心**字形
占满整个测量区域时（例如空状态那个 56dp 的文件夹图标），两者会互换 ——
`bg=` 打印的其实是图标。这不影响结论：对比度对两个颜色是**对称**的
（分子分母互换，比值相同）。要的是那个比值，不是谁叫背景。

## 子命令
  colors  <png>                    打印独立颜色数（画廊单色色块判据，见 m31-checks.py）
  contrast <png> <l> <t> <r> <b>   打印该矩形里底色/图形亮度与对比度
"""
import sys
from collections import Counter

try:
    from PIL import Image

    HAS_PIL = True
except Exception:  # pragma: no cover
    HAS_PIL = False

# 亮度差小于它就认为"与底色同一档"（量化到 4 位/通道后，同一档的抖动差值约 0.001~0.003）。
# 取 0.004 是为了滤掉背景抖动，又不会把"深色上的黑色图标"这种失败情形滤掉
# （那种差值约 0.005，恰好还在阈值之上）。
MIN_VISIBLE_DELTA = 0.004


def srgb_channel(c: float) -> float:
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(r: int, g: int, b: int) -> float:
    return 0.2126 * srgb_channel(r) + 0.7152 * srgb_channel(g) + 0.0722 * srgb_channel(b)


def contrast_ratio(l1: float, l2: float) -> float:
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def cmd_colors(argv):
    if not HAS_PIL:
        print("PIL 不可用", file=sys.stderr)
        return 2
    if len(argv) < 1:
        return 2
    im = Image.open(argv[0]).convert("RGB")
    if len(argv) >= 5:
        im = im.crop(tuple(int(v) for v in argv[1:5]))
    counts = Counter(im.getdata())
    print(f"独立颜色数={len(counts)} 尺寸={im.width}x{im.height} 最常见={counts.most_common(1)[0]}")
    return 0


def cmd_contrast(argv):
    """
    contrast <png> [l t r b]

    不传矩形就统计整张图。输出一行，便于 shell 解析：

        bg=#1e1e1e(0.0144) fg=#e6e6e6(0.7911) ratio=13.06

    脚本用 `awk` 取 `ratio=` 后面的数字。
    """
    if not HAS_PIL:
        print("PIL 不可用", file=sys.stderr)
        return 2
    if len(argv) < 1:
        return 2
    im = Image.open(argv[0]).convert("RGB")
    if len(argv) >= 5:
        l, t, r, b = (int(v) for v in argv[1:5])
        # 收一格：控件边界常常正好压在相邻控件的分割线上，
        # 把邻格的一像素混进来会让"底色"统计失真。
        im = im.crop((l + 1, t + 1, max(l + 2, r - 1), max(t + 2, b - 1)))
    if im.width < 2 or im.height < 2:
        print("bg=?- fg=?- ratio=0.00")
        return 0

    pixels = list(im.getdata())
    # 量化到 4 位/通道再统计：抗锯齿会产生上百种中间色，
    # 不量化的话"出现次数最多的颜色"可能只是一个中间色，而不是真正的底色。
    quant = Counter(((r >> 4) << 4, (g >> 4) << 4, (b >> 4) << 4) for r, g, b in pixels)
    bg = quant.most_common(1)[0][0]
    bg_l = luminance(*bg)

    # 图形 = 除底色外**出现次数最多**的颜色（理由见文件头「图形色为什么取众数」）。
    # 先滤掉与底色几乎同色的量化噪声，避免把背景抖动当成笔画；过滤后为空时
    # 退回"全部非底色"，这样"图标颜色本身就接近底色"的失败情形仍会被量出来
    # （那正是要抓的情况，不能因为"看不出明显差异"就跳过）。
    others = [(col, n) for col, n in quant.items() if col != bg]
    visible = [(col, n) for col, n in others if abs(luminance(*col) - bg_l) >= MIN_VISIBLE_DELTA]
    picked = max(visible or others or [(bg, 0)], key=lambda item: item[1])
    fg = picked[0]
    fg_l = luminance(*fg)

    print(
        f"bg=#{bg[0]:02x}{bg[1]:02x}{bg[2]:02x}({bg_l:.4f}) "
        f"fg=#{fg[0]:02x}{fg[1]:02x}{fg[2]:02x}({fg_l:.4f}) "
        f"ratio={contrast_ratio(bg_l, fg_l):.2f}"
    )
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    argv = sys.argv[2:]
    table = {"colors": cmd_colors, "contrast": cmd_contrast}
    if cmd not in table:
        print(f"未知子命令 {cmd}；可选：{', '.join(table)}", file=sys.stderr)
        return 2
    return table[cmd](argv)


if __name__ == "__main__":
    sys.exit(main())
