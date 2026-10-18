#!/usr/bin/env python3
"""
密密文件管理 · M3.1.1 修复验证用的**图像取证**工具。

## 为什么需要"数颜色"这种奇怪的手段
真机反馈里最要命的一条是"图片预览翻几页后变成单色色块"。这个现象**没法用 UI dump 观测**：
`uiautomator` 只能告诉你"这里有一个 View，尺寸是 1080×2160"，而它的内容是 1 个像素
被拉伸出来的纯色，还是一个正常渲染的画面 —— 在 XML 里完全一样。

能区分这两件事的唯一外部信号就是**像素本身**：正常渲染的多色素材会有很多种颜色，
而退化成 1×1 的位图铺满屏幕时，取样区域里只会有一种颜色（加上边缘抗锯齿的寥寥几种）。
所以这里直接读 `screencap` 出来的 PNG，数取样区域里的**独立颜色数**。

`numpy` 不是必需依赖：没有它时退化为纯 Python 的 `set` 统计（慢一点，但结论一致）。
独立成文件而不是塞进 bash：PNG 解码、区域裁剪、去噪都需要真正的图像代码，
而在 bash 里做这件事没有任何好处。
"""

import sys

try:
    from PIL import Image  # type: ignore

    HAS_PIL = True
except Exception:  # pragma: no cover
    HAS_PIL = False

try:
    import numpy as np  # type: ignore

    HAS_NUMPY = True
except Exception:  # pragma: no cover
    HAS_NUMPY = False


def region_of(width, height, spec):
    """
    把 `0.2,0.4,0.8,0.6` 这种相对比例解析成像素矩形（左,上,右,下）。

    用相对比例而不是绝对像素：屏幕尺寸随设备变（模拟器 1080×2400、平板 1600×2560），
    写死像素的脚本换台机器就要改，而"取中间 60% 宽、中间 20% 高"这个意图是稳定的。
    取中间而不是全屏，是为了避开 `fit` 布局必然留下的黑边 —— 黑边对颜色数没有贡献，
    但它会让"图片区域"的边界变得不确定。
    """
    left, top, right, bottom = (float(v) for v in spec.split(","))
    return (
        int(width * left),
        int(height * top),
        int(width * right),
        int(height * bottom),
    )


def cmd_colors(argv):
    """
    统计取样区域的独立颜色数。

    用法：`m31-checks.py colors <png> [l,t,r,b]`
    输出：`colors=<n> size=<w>x<h>`
    """
    if not argv:
        print("usage: m31-checks.py colors <png> [l,t,r,b]", file=sys.stderr)
        return 2
    if not HAS_PIL:
        print("colors=- size=- (缺 PIL)", file=sys.stderr)
        return 3
    path = argv[0]
    spec = argv[1] if len(argv) > 1 else "0.2,0.4,0.8,0.6"
    image = Image.open(path).convert("RGB")
    box = region_of(*image.size, spec)
    cropped = image.crop(box)
    if HAS_NUMPY:
        array = np.asarray(cropped, dtype=np.uint8).reshape(-1, 3)
        count = int(np.unique(array, axis=0).shape[0])
    else:
        count = len(set(cropped.getdata()))
    print(f"colors={count} size={cropped.width}x{cropped.height}")
    return 0


def main():
    if len(sys.argv) < 2:
        print("usage: m31-checks.py <colors> ...", file=sys.stderr)
        return 2
    if sys.argv[1] == "colors":
        return cmd_colors(sys.argv[2:])
    print(f"unknown command: {sys.argv[1]}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
