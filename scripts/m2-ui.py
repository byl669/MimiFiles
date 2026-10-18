#!/usr/bin/env python3
"""
密密文件管理 · 冒烟脚本的 UI 定位辅助。

## 为什么单独成一个文件
`uiautomator dump` 产出的是 XML，而 bash 里解析 XML 只有两条路：`grep`+`sed`
（属性顺序不保证、转义一碰就碎），或者内联一段 python heredoc（bash 3.2 下
嵌套引号极易踩坑，见 `m1f-smoke.sh` 顶部那段血泪注释）。

抽成独立文件后，bash 侧只剩一行命令调用，两边都干净。

## 匹配规则
- `--id`：**后缀**匹配。`resource-id` 的真身是 `com.miyuetech.mimifiles.debug:id/input_host`，
  写全了会在 debug/release 两个变体之间来回改；按 `/` 后那段匹配就够了。
- `--text` / `--desc`：**包含**匹配，且大小写不敏感。UI 文案里常有
  "已保存「客厅 NAS」"这种带变量的句子，精确匹配等于自找麻烦。
- 命中多个时取**第一个**（XML 的遍历顺序即屏幕从上到下），这符合
  "点第一个匹配项"的直觉。

退出码：0 = 找到并打印中心坐标；1 = 没找到（bash 侧据此判失败）。
"""

import re
import sys
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")

# `under:<rid>` 限定的子树根。模块级变量的理由见 `load()`：
# 所有 handler 都经由 `load()` 拿树，把"切子树"做在那里，五个命令一次性全部生效，
# 不必给每个 handler 都加一个参数（加了还容易漏）。
SUBTREE_RID = None


def parse_bounds(raw):
    m = BOUNDS_RE.match(raw or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return x1, y1, x2, y2


def center(bounds):
    x1, y1, x2, y2 = bounds
    return (x1 + x2) // 2, (y1 + y2) // 2


def load(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        root = ET.fromstring(handle.read())
    if SUBTREE_RID:
        found = None
        for node in iter_nodes(root):
            if rid_of(node) == SUBTREE_RID:
                found = node
                break
        # 找不到就把整棵树交出去而不是报错：调用方多半是在"等某个界面出现"，
        # 那时目标子树还不存在，返回空结果（→ 调用方重试）比抛异常更合适。
        return found if found is not None else root
    return root


def rid_of(node):
    return (node.get("resource-id") or "").split("/")[-1]


def matches(node, key, value):
    if key == "id":
        return rid_of(node) == value
    if key == "class":
        # 按简名比对（`ImageButton` 而不是 `android.widget.ImageButton`）：
        # 全名在不同 API 级别/ROM 上可能带包名差异，简名是稳定的。
        actual = (node.get("class") or "").split(".")[-1]
        return actual.lower() == value.lower()
    if key == "text":
        return value.lower() in (node.get("text") or "").lower()
    if key == "desc":
        return value.lower() in (node.get("content-desc") or "").lower()
    return False


def iter_nodes(root):
    for node in root.iter():
        if node.tag.endswith("node"):
            yield node


def screen_bounds(root):
    """
    屏幕范围。

    **不能只看根节点**：uiautomator 输出的是 `<hierarchy rotation="0">`，它
    **没有 `bounds` 属性**（不是 `[0,0][w,h]`）。照根节点取会拿到 None，
    进而让 `is_onscreen` 对**所有**节点返回 False——现象是
    `dump-text` 打出空列表、"屏幕上什么都没有"，而其实什么都正常。
    这个坑很隐蔽：所有查找都"返回没找到"，看起来像 UI 没渲染出来。

    改用整棵树的**最大右下角**当屏幕尺寸：层级里所有节点的并集就是屏幕。
    """
    max_x = max_y = 0
    for node in iter_nodes(root):
        bounds = parse_bounds(node.get("bounds"))
        if bounds is None:
            continue
        max_x = max(max_x, bounds[2])
        max_y = max(max_y, bounds[3])
    if max_x <= 0 or max_y <= 0:
        return None
    return (0, 0, max_x, max_y)


def is_onscreen(bounds, screen):
    """
    节点中心是否落在屏幕内。

    ## 为什么必须过滤
    `DrawerLayout` 关闭时，抽屉里的视图**依然在 XML 树里**（只是被移出可视区）。
    不过滤的话，"点侧栏里的某项"会在抽屉没打开时**点到屏幕外**——`input tap`
    对越界坐标静默无效，表现成"点了没反应"，而 dump 里那个节点明明在、
    文本也明明对得上，非常难归因。

    零面积节点（`[x,y][x,y]`）同样排除：那是 `View.GONE` 或还没测量的占位。
    """
    if bounds is None or screen is None:
        return False
    x1, y1, x2, y2 = bounds
    if x2 <= x1 or y2 <= y1:
        return False
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    sx1, sy1, sx2, sy2 = screen
    return sx1 <= cx < sx2 and sy1 <= cy < sy2


def visible_nodes(root):
    """按屏幕从上到下（XML 顺序）的可点击/可见节点。"""
    screen = screen_bounds(root)
    for node in iter_nodes(root):
        if is_onscreen(parse_bounds(node.get("bounds")), screen):
            yield node


def split_key(value):
    """
    把 `text=内部存储` 拆成 `("text", "内部存储")`。

    ## ⚠️ 为什么必须容错（这是一个真实踩过的坑）
    `center` 原本只接受**分开的两个参数**（`center ui.xml text 内部存储`），
    但两个冒烟脚本里的 `xy_of` 传的是**合成的**一个参数（`center ui.xml text=内部存储`）。
    参数个数对不上时 `cmd_center` 直接 `return 1`，于是每一次按文本定位都**静默失败** ——
    表现是"脚本找不到某个控件"，而人去看屏幕时控件明明就在那里。

    这个不一致让 M3 的 N11（浏览页 → 预览页）长期被误判成"导航失败"，
    真因是**定位函数根本没被正确调用**。修法采用与 `fill_field` 同一原则：
    **函数内归一化，两种写法都接受** —— 与其要求调用方记住约定，
    不如让接口自己容错（约定会被忘记，容错不会）。

    @return `(key, value)`；无法拆分时返回 `None`。
    """
    if "=" not in value:
        return None
    key, _, rest = value.partition("=")
    if not key or not rest:
        return None
    return key, rest


def resolve_key_value(argv):
    """把两种入参写法统一成 `(xml_path, key, value)`；无法解析返回 `None`。"""
    if len(argv) >= 3:
        return argv[0], argv[1], argv[2]
    if len(argv) == 2:
        pair = split_key(argv[1])
        if pair is not None:
            return argv[0], pair[0], pair[1]
    return None


def cmd_center(argv):
    spec = resolve_key_value(argv)
    if spec is None:
        print("usage: m2-ui.py center <xml> (<key> <value> | <key>=<value>)", file=sys.stderr)
        return 2
    xml_path, key, value = spec
    root = load(xml_path)
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        x, y = center(parse_bounds(node.get("bounds")))
        print(f"{x} {y}")
        return 0
    return 1


def cmd_count(argv):
    if len(argv) < 3:
        return 1
    xml_path, key, value = argv[0], argv[1], argv[2]
    root = load(xml_path)
    total = sum(1 for node in visible_nodes(root) if matches(node, key, value))
    print(total)
    return 0


def locate(root, spec):
    """在已解析的树上按 `key=value` 找中心坐标，返回 (x, y) 或 None。"""
    key, _, value = spec.partition("=")
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        bounds = parse_bounds(node.get("bounds"))
        if bounds is None:
            continue
        return center(bounds)
    return None


def cmd_locate(argv):
    """
    一次解析，批量定位。

    ## 存在的理由
    取坐标的开销**几乎全在 `uiautomator dump` 本身**（真机 1~2 s），而不在解析。
    一个七字段的表单如果每填一个字段就 dump 一次，光定位就要十几秒；而编辑页在
    键盘收起后布局是稳定的，一次 dump 拿到的坐标可以一直用到下次界面变化为止。

    所以把"解析"与"取坐标"合并成一次调用：

        m2-ui.py locate ui.xml id=input_host id=input_port text=WebDAV

    输出每行 `spec x y`；找不到的写 `spec -`（**不跳过**，否则调用方无法区分
    "没有这个键"和"解析失败"）。
    """
    if len(argv) < 2:
        return 1
    root = load(argv[0])
    for spec in argv[1:]:
        found = locate(root, spec)
        print(f"{spec} -" if found is None else f"{spec} {found[0]} {found[1]}")
    return 0


def cmd_list(argv):
    """
    列出**所有**匹配项的坐标，每行 `x y`（屏幕由上到下）。

    与 `center`（只给第一个）分开是为了行尾 ⋮：列表里每一行都有一个「更多」，
    而"该点哪一个"取决于"离目标行最近"，只拿到第一个匹配项就算不出距离。
    """
    if len(argv) < 3:
        return 1
    root = load(argv[0])
    key, value = argv[1], argv[2]
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        bounds = parse_bounds(node.get("bounds"))
        if bounds is None:
            continue
        x, y = center(bounds)
        print(f"{x} {y}")
    return 0


def cmd_rect(argv):
    """
    打印第一个匹配节点的完整矩形，一行 `x1 y1 x2 y2`；找不到返回 1。

    ## 与 `center` 的分工
    `center` 回答"点哪里"，`rect` 回答"它占哪儿"。判断两个控件是不是
    **挨在一起**（比如"横竖屏按钮是不是贴着齿轮设置按钮"）必须知道边界，
    只看中心点会把"上下叠着"误判成"左右并排"——而这两种情况的中心点
    距离可能完全一样。
    """
    spec = resolve_key_value(argv)
    if spec is None:
        print("usage: m2-ui.py rect <xml> (<key> <value> | <key>=<value>)", file=sys.stderr)
        return 2
    xml_path, key, value = spec
    root = load(xml_path)
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        bounds = parse_bounds(node.get("bounds"))
        if bounds is None:
            continue
        print(f"{bounds[0]} {bounds[1]} {bounds[2]} {bounds[3]}")
        return 0
    return 1


def cmd_texts(argv):
    """
    打印**所有**匹配节点的 `text`，每行一个（屏幕顺序）。

    ## 为什么"列表能不能滚"必须用它来判
    一开始用的是"取列表第一行的 `top` 坐标，滑动后应当变小"。这是错的 ——
    `RecyclerView` 的行高是固定的（紧凑模式 32 dp），于是**第 1 行滚出屏幕后，
    第 2 行会顶到第 1 行原来的位置上**，读到的 `top` 几乎没变（实测 375 → 350），
    而列表其实滚得好好的。这个判据会把"能滚"误判成"滚不动"。

    正确判据是**内容变了**：滑动前后列表里可见的条目集合不同即可。
    所以这里把匹配到的文本全部倒出来，由调用方做集合比较。
    """
    if len(argv) < 2:
        return 1
    root = load(argv[0])
    spec = argv[1]
    key, _, value = spec.partition("=")
    if not value:
        return 1
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        text = (node.get("text") or "").strip()
        if text:
            print(text)
    return 0


def cmd_text_of(argv):
    """
    打印第一个匹配节点的 `text` 属性（可为空行）；找不到返回 1。

    ## 与 `dump-text` 的分工
    `dump-text` 把"屏幕上所有文本"倒出来，适合人看与 grep 排错；
    但要断言"**某个控件**上写的是不是某个值"就不能用它 ——
    同一个字符串可能同时出现在工具栏标题和列表行里（本工程真的会：
    进入 `MimiM3` 之后标题是它，而面包屑上也有它）。按 rid 精确定位才是可靠的。
    """
    spec = resolve_key_value(argv)
    if spec is None:
        print("usage: m2-ui.py text-of <xml> (<key> <value> | <key>=<value>)", file=sys.stderr)
        return 2
    xml_path, key, value = spec
    root = load(xml_path)
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        print((node.get("text") or "").replace("\n", "\\n"))
        return 0
    return 1


def cmd_dump_text(argv):
    """把屏幕上所有非空 text / content-desc 打出来，供 bash 侧断言与排错。"""
    if not argv:
        return 1
    root = load(argv[0])
    seen = []
    for node in visible_nodes(root):
        for attr in ("text", "content-desc"):
            value = (node.get(attr) or "").strip()
            if value and value not in seen:
                seen.append(value)
    for value in seen:
        print(value)
    return 0


def cmd_fields(argv):
    """
    把所有输入框的**当前值**打出来，每行 `rid|class|text`。

    ## 存在的理由
    "字段没填上"和"字段填上了但请求发错了"在界面上看起来完全一样：
    都是"点了保存，没反应"。`dump-text` 抓的是**可见文本**，而 `EditText` 里的
    文字是 `text` 属性、`TextInputLayout` 的浮动标签是另一回事，两者混在一起
    分不清哪个是哪个框。这里直接把 `resource-id` 与值配对，一眼能看出
    "哪个框是空的"——这是排查表单类失败最快的一条路。
    """
    if not argv:
        return 1
    root = load(argv[0])
    for node in visible_nodes(root):
        cls = (node.get("class") or "").split(".")[-1]
        if "EditText" not in cls and "AutoComplete" not in cls:
            continue
        rid = rid_of(node) or "-"
        value = (node.get("text") or "").replace("\n", "\\n")
        print(f"{rid}|{cls}|{value}")
    return 0


def cmd_checked(argv):
    """
    读一个开关的勾选状态，输出 `true` / `false` / `-`（找不到）。

    ## 为什么需要它
    "把开关关掉"这件事**不能靠盲点一次**。默认值哪天变了（或者上一次运行的状态
    被保留下来），"点一下就反了"的写法会静默地测出相反的东西——比如本来要关掉
    SMB 签名，结果把它打开了，然后现象是"服务端握手失败"，看起来完全不像脚本的问题。

    ## ⚠️ 入参写法必须与 `center` / `rect` 一致（M4 修正）
    这里原本是"第二个参数与 `rid_of(node)` **精确**比较"，也就是要求传
    `com.pkg:id/switch_xxx` 这种全名；而 `center` / `rect` 早就通过
    `resolve_key_value` 支持了 `id=switch_xxx` 的简写。两种写法并存的结果是
    M4 的 W16 用 `checked ui.xml id=switch_readonly` 永远读回 `-`，
    看起来像"开关不在界面上"，其实是**参数没被理解**。
    现在统一走 `resolve_key_value` + `matches`：两种写法都收，与其余命令同源。
    """
    spec = resolve_key_value(argv)
    if spec is None:
        # 向后兼容：只给一个"裸 rid"（不含 `=`）时按 id 简名处理。
        # 这与旧实现的语义**完全等价**（旧的 `rid_of(node) != argv[1]` 就是 id 简名
        # 精确比较），所以 `scripts/m2-smoke.sh` 里既有的 `ui_checked "$rid"` 不受影响。
        if len(argv) == 2 and argv[1]:
            spec = (argv[0], "id", argv[1])
        else:
            print("usage: m2-ui.py checked <xml> (<key> <value> | <key>=<value> | <rid>)", file=sys.stderr)
            return 2
    xml_path, key, value = spec
    root = load(xml_path)
    for node in visible_nodes(root):
        if not matches(node, key, value):
            continue
        print(node.get("checked") or "-")
        return 0
    print("-")
    return 0


def cmd_bounds(argv):
    """
    打印界面快照的几何与窗口来源，专用于诊断"软键盘到底在不在"。

    ## 存在的理由
    软键盘是**独立窗口**，它出现时会从两个方向破坏坐标：
      · `adjustResize`：app 窗口被压矮（2400 → ~1300），树的最大右下角随之变小，
        于是 `is_onscreen` 把下半屏的控件判成"不在屏幕上"——现象是
        **从某个字段开始集体定位失败**，而前面几个字段一切正常；
      · `adjustPan`：窗口高度不变，但内容整体平移，之前算好的坐标全部失准。

    这两种情况都没法从"点了没反应"反推出来，只有把几何直接打出来才看得见。
    之所以不读 `dumpsys input_method`：那条命令在模拟器上要输出上万行，
    单次往返 3~8 秒，而且 `mInputShown` 出现在多个服务段里，取错一次就会
    误判成"键盘不在"进而连发 BACK，把编辑页的 back guard 带出来。

    输出两行：
      screen=<width> <height>   树的最大右下角（键盘在时 ≈ 半屏）
      packages=...              树里出现过的所有 package（键盘会露出输入法包名）
    """
    if not argv:
        return 1
    root = load(argv[0])
    screen = screen_bounds(root)
    print(f"screen={screen[2] if screen else '-'} {screen[3] if screen else '-'}")
    packages = []
    for node in iter_nodes(root):
        package = node.get("package") or "-"
        if package not in packages:
            packages.append(package)
    print("packages=" + ",".join(packages))
    return 0


def main():
    global SUBTREE_RID
    if len(sys.argv) < 2:
        print("usage: m2-ui.py "
              "<bounds|center|checked|count|dump-text|fields|locate|list|rect|text-of|texts> <xml> [under:<rid>] ...\n"
              "  例：center ui.xml text 内部存储   ≡   center ui.xml text=内部存储",
              file=sys.stderr)
        return 2
    command = sys.argv[1]
    argv = sys.argv[2:]
    # `under:<rid>` 是全局修饰：限定只在某个子树内查找。
    # 典型场景是抽屉——抽屉里的条目与主界面存在**同名文本**（都是「内部存储」），
    # 不加限定时 `center text=内部存储` 会命中工具栏标题，点下去毫无反应。
    #
    # ## ⚠️ 两种写法都要收（M4.1 补）
    # 这里原先**只**认"独立参数"一种形态：`center ui.xml under:file_list text=AAAEmpty`。
    # 而人（和脚本）很自然地会写成合并形态 `center ui.xml under:file_list/text=AAAEmpty`
    # —— 那个写法的后果是**参数校验直接失败并返回 2**，上层 `$(…)` 拿到空字符串，
    # 现象是"控件定位不到"。四月份 M4 的 `checked` 就因为同类问题静默返回 `-`，
    # 结论一致：**同一个概念只允许一种入参约定，并且让接口自己对另一种形态容错**
    # （约定会被忘记，容错不会）。所以这里把合并形态就地拆成两份。
    if len(argv) >= 2 and argv[1].startswith("under:") and "/" in argv[1]:
        head, spec = argv[1].split("/", 1)
        argv = [argv[0], head, spec] + argv[2:]
    if len(argv) >= 2 and argv[1].startswith("under:"):
        SUBTREE_RID = argv[1][len("under:"):]
        argv = [argv[0]] + argv[2:]
    handlers = {
        "bounds": cmd_bounds,
        "center": cmd_center,
        "checked": cmd_checked,
        "count": cmd_count,
        "dump-text": cmd_dump_text,
        "fields": cmd_fields,
        "locate": cmd_locate,
        "list": cmd_list,
        "rect": cmd_rect,
        "text-of": cmd_text_of,
        "texts": cmd_texts,
    }
    handler = handlers.get(command)
    if handler is None:
        print(f"unknown command: {command}", file=sys.stderr)
        return 2
    return handler(argv)


if __name__ == "__main__":
    sys.exit(main())
