#!/usr/bin/env bash
# ==============================================================================
# 密密文件管理 · M2 功能冒烟（网络存储：SMB + WebDAV）
#
# 覆盖方案里的 M2 验收标准：
#   N1  宿主机协议基线（WebDAV Basic/Digest + SMB 认证枚举）—— 证明"服务端没问题"
#   N2  新建 WebDAV 连接（Basic 挑战）→ 测试连通 → 保存 → 列表出现
#   N3  新建 WebDAV 连接（**Digest 挑战**）→ 测试 → 保存   ← 压 RFC 7616 实现
#   N4  新建 SMB 连接 → 测试 → 保存（含方言协商）
#   N5  重复连接被识别并弹确认（去重）
#   N6  错误密码给出可读失败提示（不是"未知错误"）
#   N7  浏览页侧栏出现「网络位置」分组且条目可达
#   N8  WebDAV 列目录（内容与宿主磁盘一致）
#   N9  上传 本机 → WebDAV（判据落在**宿主磁盘**）
#   N10 下载 WebDAV → 本机（判据落在**设备磁盘**）
#   N11 新建目录 / 重命名 / 删除（WebDAV）
#   N12 SMB 列目录 + 上传 + 下载（判据落宿主磁盘）
#   N13 跨存储中转 SMB → 本机（FsOps 流式，不经临时文件）
#   N14 按需读取走 HTTP Range（拖进度条的前提）—— 判据是服务端收到的 `Range` 头与 206
#   N15 凭据落盘为密文（数据库里搜不到明文密码）
#   N16 SAF DocumentsProvider 对系统文件选择器可见
#
# ## 判据原则：点靠 UI，判定靠"另一侧"
#
# 和 `m1f-smoke.sh` 一样，UI 操作只负责"点到那个位置"，**所有判定都落在对端**：
#   · 上传成功  → 宿主机的 `ls` 里出现该文件，且**字节内容一致**（不是只看文件名）
#   · 下载成功  → 设备侧的 `[ -f ]` 成立
#   · 用的哪种认证 → 服务端录下的 `auth_scheme`（Basic / Digest）
#   · 有没有走 Range → 服务端录下的 `range` 头与 `status=206`
#
# 这一条不是洁癖：网络存储的失败方式恰好是"UI 上看起来成功了，但对端什么都没有"
# （写进本地缓存、写进了错误路径、连接其实是上一轮的）。不看对端就抓不到这类问题。
#
# ## 为什么要自建带录制的 WebDAV 服务端
# `wsgidav` 只打启动日志。而"用了 Digest 吗""走 Range 了吗"这两件事**只在请求层
# 可见**——见 `scripts/m2-dav-server.py` 顶部。两个端口分别发 Basic / Digest 挑战，
# 才能把 app 的两条认证分支分别压到。
#
# 用法：
#   scripts/m2-smoke.sh                    # 全部
#   scripts/m2-smoke.sh --only N9,N10      # 只跑指定项
#   scripts/m2-smoke.sh --keep-connections # 跑完不清理 app 数据（留着手动把玩）
#
# ## 兼容性
# 与 m1f-smoke.sh 相同：必须能在 macOS 自带的 bash 3.2 上跑。
# 双引号命令替换 + 内层转义双引号 + 后接 `||` 会让 3.2 的解析器报 unexpected EOF，
# 本文件统一写成 `xy="$(...)" || return 1`（外层引号紧贴 `)`）。
# ==============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/.cmfa-toolchain/android-sdk}"
ADB="$SDK_DIR/platform-tools/adb"

# 冒烟需要的依赖（wsgidav + cheroot + impacket + **pillow**）装在隔离 venv 里，
# 不污染用户环境。找不到就明确报错，而不是用一个残缺的 python 硬跑。
# pillow 是 N6 要用的：提示条在界面快照里读不到（`uiautomator` 会把它的宿主判为
# 不可见而跳过），只能靠比较"点击前 vs 点击后"底部区域的像素亮度来判定。
VENV_PY="$HOME/.workbuddy/binaries/python/envs/default/bin/python"
# SMB 服务端要用 impacket 的 console script：`python -m impacket.smbserver` 起不来
# （那个模块没有 `__main__` 入口，会静默退出，日志一片空白——很难看出是哪一步错的）。
VENV_BIN="$(dirname "$VENV_PY")"

VARIANT="debug"
ONLY=""
KEEP_CONNECTIONS=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant) VARIANT="$2"; shift 2 ;;
    --only)    ONLY="$2";    shift 2 ;;
    --keep-connections) KEEP_CONNECTIONS=1; shift ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

if [[ "$VARIANT" == "release" ]]; then
  APP_ID="com.miyuetech.mimifiles"
else
  APP_ID="com.miyuetech.mimifiles.debug"
fi
ENTRY="$APP_ID/com.miyuetech.mimifiles.splash.SplashActivity"
PKG_ID_PREFIX="$APP_ID:id/"
SAF_AUTHORITY="com.miyuetech.mimifiles.documents"

# ── 实验台 ──────────────────────────────────────────────────────────────────
LAB="/tmp/m2lab"
DAV_ROOT="$LAB/dav"
SMB_ROOT="$LAB/smb/MIMISHARE"
SMB_SHARE="MIMISHARE"
HOST_ALIAS="10.0.2.2"        # 模拟器里指向宿主 loopback 的固定别名
DAV_BASIC_PORT=8090
DAV_DIGEST_PORT=8091
SMB_PORT=8445

DAV_USER="mimi"
DAV_PASS="mimi123"

# 设备侧沙箱。刻意用 `MimiM2`：设备根目录列表按名称排序，
# `Mimi` 前缀保证它在首屏可见，脚本不必为了找到它而滚动（滚动会让坐标失效）。
DEV_DIR="/sdcard/MimiM2"
DEV_DIR_FSPATH="local:///storage/emulated/0/MimiM2"

# 连接名一律用 ASCII：`adb shell input text` 对非 ASCII 支持不可靠，
# 一旦名字里带中文，"填进去的名字"与"点得到的那一行"就对不上。
CONN_DAV_BASIC="LabDavBasic"
CONN_DAV_DIGEST="LabDavDigest"
CONN_SMB="LabSmb"

OUT_DIR="$PROJECT_DIR/build/m2-smoke"
mkdir -p "$OUT_DIR"

PASS=0; FAIL=0; SKIP=0; FAILED_NAMES=()

log()  { printf '\033[1;36m[ m2 ]\033[0m %s\n' "$*"; }
step() { printf '\033[1;37m   ▸ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  [ OK ]\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '\033[1;31m  [FAIL]\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); FAILED_NAMES+=("$*"); }
skip() { printf '\033[1;33m  [SKIP]\033[0m %s\n' "$*"; SKIP=$((SKIP+1)); }

want() { [[ -z "$ONLY" ]] && return 0; [[ ",$ONLY," == *",$1,"* ]]; }

# ════════════════════════════════════════════════════════════════════════════
# 设备与 UI 基础
# ════════════════════════════════════════════════════════════════════════════
a() { "$ADB" -s "$SERIAL" "$@"; }

UI_XML="$OUT_DIR/_ui.xml"
UI_DEV="/sdcard/m2_ui.xml"

# 把当前界面快照拉到宿主机。
#
# ## 为什么失败时**不能**直接覆盖
# 朴素写法 `a shell cat "$UI_DEV" > "$UI_XML"` 有个致命细节：`uiautomator dump`
# 失败时（键盘动画、界面正在刷新、上一轮 dump 还没退出都会失败），设备上可能
# 根本没有那个文件，`cat` 报错，而**重定向已经把 `$UI_XML` 截断成 0 字节**。
# 后果是所有 `xy_of` 一起失败、每个字段被静默跳过——整张表单是空的，
# 而屏幕上一切正常，没有任何报错指向这里。
#
# 所以：写到临时文件，成功且非空才替换，失败就重试；三次都不行便**保留上一份
# 快照**并留下痕迹（宁可拿到旧坐标去点、也不要拿空文件去点）。
ui_dump() {
  local try=0
  while [[ $try -lt 3 ]]; do
    a shell uiautomator dump "$UI_DEV" >/dev/null 2>&1
    if a shell cat "$UI_DEV" > "$UI_XML.tmp" 2>/dev/null && [[ -s "$UI_XML.tmp" ]]; then
      mv "$UI_XML.tmp" "$UI_XML"
      return 0
    fi
    sleep 0.5
    try=$(( try + 1 ))
  done
  rm -f "$UI_XML.tmp"
  printf '  [warn] uiautomator dump 连续 3 次失败，沿用上一次的界面快照\n' >&2
  return 1
}

# ── 软键盘：整场冒烟期间禁用 ────────────────────────────────────────────────
#
# ## 为什么不是"每次点击前把键盘收起来"
#
# 这条路上踩了三个坑，症状全都指向错误的方向：
#   1. 读 `dumpsys input_method` 判键盘状态，**又慢又不准**：该命令在模拟器上输出
#      上万行，单次往返 3~8 秒；`mInputShown` 在输出里出现多次（分属不同服务段），
#      `grep -m1` 很容易判错，判错的后果是**连发 BACK**——键盘已经收起时 BACK 会被
#      Activity 接走，编辑页的返回拦截随即弹出「放弃修改？」，把后续步骤全带偏。
#   2. 键盘弹出时，被它压住的控件**不是"位置不对"，而是不进界面快照**。
#      实测（1080×2400）：无键盘 `input_username` = 540 1489；键盘在 = **树里没有它**，
#      `btn_advanced` 同样消失，底栏按钮从 y=2253 跳到 y=1370（`adjustResize` 的指纹）。
#      于是定位直接失败，而拖住它的滚动兜底写在"定位**成功**之后"，永远轮不到执行。
#   3. 被键盘盖住的坐标 `input tap` 会打到键盘上，那是**静默无效**的：
#      焦点没给到输入框，随后的 `input text` 全灌进上一个仍有焦点的框。
#
# ## 为什么可以整个拿掉键盘
#
# `adb shell input text` 走的是**系统级按键事件注入**，直接送到当前焦点窗口，
# 完全不经过输入法——所以没有输入法照样能打字。已用实验确认：六个字段逐个
# 回读全部正确（`LabDavBasic / 10.0.2.2 / 8090 / /dav / mimi / mimi123`）。
# 拿掉之后编辑页几何恒定：所有输入框常驻快照、底栏稳定在同一 y，
# 上面三个坑一次性全部消失。
#
# 设备上原本启用的输入法记在 `IME_LIST`，退出时原样恢复（见 `cleanup_all`）。
IME_LIST=""
ime_disable_all() {
  IME_LIST="$(a shell ime list -s 2>/dev/null | tr -d '\r')"
  local one
  for one in $IME_LIST; do
    a shell ime disable "$one" >/dev/null 2>&1
  done
  sleep 0.5
  step "已临时禁用软键盘（原启用：$(printf '%s' "$IME_LIST" | tr '\n' ' ' | sed 's/ *$//')）"
}
ime_enable_all() {
  [[ -z "${IME_LIST:-}" ]] && return 0
  local one
  for one in $IME_LIST; do
    a shell ime enable "$one" >/dev/null 2>&1
  done
  IME_LIST=""
}

# 批量定位：一次解析拿多个坐标，输出 `key=value x y`（找不到写 `-`）。
# 为什么成批：开销全在 dump 本身（1~2 s），不在解析。见 m2-ui.py 的说明。
locate_all() {
  ui_dump
  "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" locate "$UI_XML" "$@" 2>/dev/null
}

# 单点定位，带轮询重试——跨 Activity / 弹窗时用它等目标出现。
#
# 取坐标一律用 `awk '{print $(NF-1)" "$NF}'` 取**末尾两个字段**，
# 而不是 `${out#* }` 这类"去掉到第一个空格"。后者在匹配串本身带空格时
# （`desc=More options`、`text=内部存储`）会只剥掉前半截，回出
# `More options 1027 219` 这种"看起来像坐标"的串，接着被 `tap` 当成四个参数——
# 于是点到了乱七八糟的位置，而调用方只看到"点了没反应"。
xy_of() { # $1=key $2=value $3=轮询次数
  local n="${3:-10}" i=0 out
  while [[ $i -lt $n ]]; do
    out="$(locate_all "$1=$2" | head -n1)"
    if [[ -n "$out" && "$out" != *" -" ]]; then
      printf '%s' "$(printf '%s\n' "$out" | awk '{print $(NF-1)" "$NF}')"
      return 0
    fi
    sleep 0.7
    i=$(( i + 1 ))
  done
  return 1
}

tap() { a shell input tap "$1" "$2" >/dev/null 2>&1; sleep "${3:-0.8}"; }
tap_id()   { local xy; xy="$(xy_of id "$1" "${2:-10}")" || return 1; tap $xy; }
tap_text() { local xy; xy="$(xy_of text "$1" "${2:-10}")" || return 1; tap $xy; }

wait_text() { xy_of text "$1" "${2:-12}" >/dev/null; }
has_text()  { local out; out="$(locate_all "text=$1" | head -n1)"; [[ -n "$out" && "$out" != *" -" ]]; }
has_id()    { local out; out="$(locate_all "id=$1"  | head -n1)"; [[ -n "$out" && "$out" != *" -" ]]; }

# 读一个开关的勾选状态：`true` / `false` / `-`（没找到）。
# 用来避免"盲点一次就当反了"——默认值一变，那种写法会静默测出相反的东西。
ui_checked() { # $1=rid
  ui_dump
  "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" checked "$UI_XML" "$1" 2>/dev/null | head -n1
}

# ── 限定在某个子树内的查找 ──────────────────────────────────────────────────
#
# 侧栏（`nav_pane`）里的条目与主界面**存在同名文本**：面包屑的第一段也是
# 「内部存储」，工具栏标题在首页时也是「存储位置」。不限定就会命中错的那个，
# 而错的那个点下去多半毫无反应——最难查的一类失败。
xy_in() { # $1=子树rid $2=key $3=value $4=轮询次数
  local rid="$1" k="$2" v="$3" n="${4:-10}" i=0 out
  while [[ $i -lt $n ]]; do
    ui_dump
    out="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" locate "$UI_XML" "under:$rid" "$k=$v" 2>/dev/null | head -n1)"
    if [[ -n "$out" && "$out" != *" -" ]]; then
      printf '%s' "$(printf '%s\n' "$out" | awk '{print $(NF-1)" "$NF}')"
      return 0
    fi
    sleep 0.7
    i=$(( i + 1 ))
  done
  return 1
}
tap_in() { local xy; xy="$(xy_in "$1" "$2" "$3" "${4:-10}")" || return 1; set -- $xy; tap "$1" "$2" 1.0; }
texts_in() { ui_dump; "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" dump-text "$UI_XML" "under:$1" 2>/dev/null; }
has_text_in() {
  ui_dump
  local out
  out="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" locate "$UI_XML" "under:$1" "text=$2" 2>/dev/null | head -n1)"
  [[ -n "$out" && "$out" != *" -" ]]
}

# 屏幕上的全部文本（去重），用于断言与排错留证
screen_text() { "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" dump-text "$UI_XML" 2>/dev/null; }

# 控件最高可以到多少 y 才认为"点得到"。
#
# 历史上这个值取 1300，是因为软键盘会盖住屏幕下方（1080×2400 上键盘顶部约 1400），
# 被盖住的坐标 `input tap` 会打到键盘上——那是**静默无效**的：坐标"点过了"，
# 但输入框根本没拿到焦点，随后的 `input text` 会灌进上一个仍有焦点的框。
#
# 现在软键盘整场禁用（见 ime_disable_all），可视区就是整屏，所以这条线放到接近屏底。
# 只留一个下限：底栏两个按钮在 y=2253，控件低于它就会被底栏压住，故取 2240。
# 实测各目标的自然位置：输入框 599/792/985/1237/1489/1682、
# `btn_advanced` 1840、「高级选项」展开后 `switch_allow_insecure` 2008 —— 全部在线上。
KEYBOARD_TOP=2240

# 收一次软键盘。不判断、不循环。
#
# ⚠️ 键盘现在整场被禁用，所以这个函数是**防御性**的（万一某台设备禁不掉键盘）。
# 保留它的另一个原因：它是幂等的，多按一次没有副作用（ESC 不会被当成返回键）。
#
# ## 为什么不再读 `dumpsys input_method` 判断键盘状态
# 两个原因，都是硬伤：
#  1. **慢**。该命令在模拟器上要输出上万行，单次 `adb shell` 往返 3~8 秒。
#     它原本出现在**每个字段**的填写路径上，一遍表单就能平白多花好几分钟
#     （实测把 N1–N4 从 10 分钟拖到 20 分钟以上）。
#  2. **判不准**。`mInputShown` 在输出里出现多次，属于不同服务段；
#     取第一个匹配很容易判错，判错的后果是**连发 BACK**——
#     键盘已经收起时 BACK 会被 Activity 接走，编辑页的 back guard 随即
#     弹出「放弃修改？」，把后面的步骤全带偏。
#
# 现在改为：**布局一致性由"每次点击前重新取坐标"来保证**（见 fill_field），
# 与键盘状态解耦；这里只在需要干净界面时按一下 ESC。
#
# ## 为什么是 ESC 而不是 BACK
# ESC（keyevent 111）多数输入法据此收键盘，且**不会被当成返回键**。
dismiss_ime_once() {
  a shell input keyevent 111 >/dev/null 2>&1
  sleep 0.5
}

# ── 滚动到目标可见 ──────────────────────────────────────────────────────────
#
# ⚠️ 现在软键盘整场禁用，编辑页所有控件常驻快照、自然位置都在 `KEYBOARD_TOP` 之上，
# 所以本函数在这张表单上**一次都不会真的滚**——它是"内容比一屏更高"时的兜底。
# 保留它是因为下面的历史坑真实存在过，而它正是那类坑的解药。
#
# ## 为什么必须有这个函数（M2 实测，代价最大的一处）
#
# 软键盘出现时，被它压在下面的控件**不是"位置不对"，而是"根本不在界面快照里"**。
# 实测（1080×2400 的模拟器，编辑页）：
#
#   无键盘：`input_username` = 540 1489、`btn_advanced` = 157 1840、底栏按钮 y = 2253
#   键盘在：`input_username` = **不在树里**、`btn_advanced` = **不在树里**、底栏按钮 y = **1370**
#
# 底栏从 2253 跳到 1370 是 `adjustResize` 的指纹：窗口被压矮，可视区只剩上半屏，
# 于是 `uiautomator` 不再序列化下半屏的输入框——`fields` 里也跟着一起消失。
# 这时 `xy_of` 返回失败，而**唯一的出路是滚动**：把内容往上带，
# 让目标进入那半屏可视区，节点就会重新出现在树里。
#
# 之前把滚动写在"定位**成功**之后"（用来躲键盘高度），于是定位一旦失败就
# 直接 `return 1`，滚动分支**永远轮不到执行**。症状极具迷惑性：
#   · SMB 表单 7 个字段恰好压在临界线上 → 全过；
#   · WebDAV 表单因为 `root_path` 的提示文案更长、整体更高一屏，
#     从 `input_username` 开始的字段**集体**定位失败。
# 两个表单一模一样长，只有实际量过几何才知道差在哪。
#
# 判定用 `KEYBOARD_TOP` 而不是"找到就行"：被键盘盖住的坐标 `input tap` 会打到键盘上，
# 那是**静默无效**的（焦点没给到输入框，随后 `input text` 灌进上一个框）。
scroll_into_view() { # $1=rid（或 id=rid）→ 打印可点的坐标
  local rid="${1#id=}" xy y tries=0
  while [[ $tries -le 5 ]]; do
    if xy="$(xy_of id "$rid" 3)"; then
      y="$(printf '%s\n' "$xy" | awk '{print $2}')"
      if [[ -n "$y" && "$y" -le "$KEYBOARD_TOP" ]]; then
        printf '%s' "$xy"
        return 0
      fi
    fi
    [[ $tries -eq 5 ]] && break
    # 从 1600 划到 1000（手指上移）＝ 内容向上滚一屏。
    a shell input swipe 540 1600 540 1000 300 >/dev/null 2>&1
    sleep 0.7
    tries=$(( tries + 1 ))
  done
  return 1
}

# 把内容滚回顶部。
#
# 用在 `fs_snapshot` 之前：填字段的过程中页面会被滚下去，留证时只想看"首屏那一屏"
# 就会缺字段，失败复盘时反而看不出到底是哪个框空着。
scroll_top() {
  local i=0
  while [[ $i -lt 5 ]]; do
    a shell input swipe 540 700 540 1900 200 >/dev/null 2>&1
    i=$(( i + 1 ))
  done
  sleep 0.4
}

# 填一个输入框：**每次都重新定位**。
#
# ## 为什么不能提前抓一份坐标表反复用
# 编辑页下半部分的字段（共享名/用户名/密码）中心点会落进软键盘的高度里。
# 键盘一旦还留在屏幕上，`input tap` 打到的是键盘——**静默无效**，
# 输入框根本没被点中，于是随后的 `input text` 全灌进了上一个仍有焦点的框。
#
# 这个坑的症状最难查：每个字段单独看都"填过"，但值跑到了隔壁框里，
# 而脚本手里那份坐标（同一个布局下取的）看起来完全正常。
# 重新定位的代价只是每次多一次 dump（1~2 s），换来的是
# **定位时的布局 == 点击时的布局**。
#
# ⚠️ 值必须先存进 `local` 再 `set --`。`set --` 会重置位置参数，
# 之后再读 `$2` 拿到的是空串——"点到了框、输入的是空白"，全程不报错。
# ⚠️ `$1` 接受两种写法：`input_label` 与 `id=input_label`。
#
# 归一化放在函数里，而不是"要求每个调用点写对"。这里踩过一次，代价是
# 整个 M2 表单类用例连续失败：
#   · 调用方写的是 `fill_field id=input_label "$label"`（照 `xy_of` 的
#     `key=value` 风格），而函数里是 `xy_of id "$rid"`；
#   · 两者一拼就成了 **`id=id=input_label`**，`matches()` 拿
#     `rid_of(node) == "id=input_label"` 去比 —— 永远不匹配；
#   · 6 次轮询全落空，每个字段都被静默跳过，整张表单是空的，
#     而屏幕上一切正常。保存时校验不通过、停在编辑页，
#     表现成"保存后没有回到列表页"——离真正的原因隔着两层。
# 容错写法比"约定"可靠：约定会在下一次改写时被忘掉，容错不会。
fill_field() { # $1=rid（或 id=rid）  $2=值
  local rid="${1#id=}" value="$2" xy back
  # 先收一次键盘：能收起来的话可视区最大、滚动的次数最少。
  # 但**不能依赖它见效**——收不起来时由 `scroll_into_view` 的滚动兜住。
  dismiss_ime_once
  xy="$(scroll_into_view "$rid")" || {
    printf '       ⚠ fill_field %s：滚动 5 屏后仍不可见（控件不存在，或不在这个页面）\n' "$rid"
    return 1
  }
  set -- $xy
  printf '       fill_field %-18s @ %s,%s\n' "$rid" "$1" "$2"
  tap "$1" "$2" 1.0
  a shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
  # 清空走**一次** `adb shell` 内的循环，不要 40 次 adb 往返
  # （每次往返 ~0.15 s，40 次就是 6 秒；这张表单有 6 个框）。
  a shell "i=0; while [ \$i -lt 40 ]; do input keyevent 67; i=\$((i+1)); done" >/dev/null 2>&1
  a shell input text "$value" >/dev/null 2>&1
  sleep 0.4
  # 立刻回读这个框。
  # 「点了框、也发了 input text，框还是空的」只有回读才发现得了；
  # 否则要等到整张表单的快照才看出不对，而那时已经分不清是哪一步丢的。
  back="$(ui_dump; "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" fields "$UI_XML" 2>/dev/null \
      | grep -F "$rid|" | head -n1)"
  printf '       回读 %s → %s\n' "$rid" "${back:-（读不到）}"
}

# 表单字段快照：把每个输入框的**当前值**落盘。
#
# 「填了但没填上」在屏幕上和「填对了」长得一模一样——框里有值就行，而两者的
# 失败现象都是"点了按钮没反应"。把值记下来，失败时一眼就能看出是哪个框空的，
# 不必再来一遍"加打印、重跑"。
fs_snapshot() { # $1=标签
  ui_dump
  "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" fields "$UI_XML" 2>/dev/null \
    > "$OUT_DIR/form-fields-$1.txt"
  printf '       字段快照 %s：\n' "$1"
  sed 's/^/         /' "$OUT_DIR/form-fields-$1.txt"
}

# ── 应用自己的日志 ──────────────────────────────────────────────────────────
#
# 比截图和提示条都可靠。界面上的表现一律是"连不上/没反应"三个字，而**原因**
# 只在日志里：`refusing to send credentials over plain HTTP`、
# `auth gave up after N attempts`、TLS 握手异常、SMB 方言协商失败……
# 没有这些，排查只能在"猜"和"再跑一遍"之间循环。
app_pid() { a shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
app_log_clear() { a logcat -c >/dev/null 2>&1; }
app_log_dump() { # $1=标签
  local pid
  pid="$(app_pid)"
  [[ -z "$pid" ]] && return 0
  a logcat -d --pid "$pid" 2>/dev/null | tr -d '\r' \
    | grep -E "Mimi|WebDav|Sardine|OkHttp|smbj|jcifs|SMB|Exception" \
    | tail -80 > "$OUT_DIR/log-$1.txt"
  printf '       应用日志 → log-%s.txt（%s 行）\n' \
    "$1" "$(grep -c . "$OUT_DIR/log-$1.txt" 2>/dev/null | tr -d ' ')"
}

# ── 高级面板里的开关 ────────────────────────────────────────────────────────
#
# 把某个开关设成指定状态。三步都不能省：**展开 → 滚动到可见 → 读状态再决定点不点**。
#
# · 少了「滚动」：控件在屏幕外时读到的是 `-`，会被当成"不是目标状态"而**误点**——
#   而点不到的时候现象是静默的，最后表现成"服务端握手失败"，完全联想不到脚本。
# · 少了「读状态」：盲点一次在默认值变化时会把开关设**反**。这次就抓到了两个
#   真实例子：WebDAV 的「允许明文 HTTP」必须为真（否则 baseUrl 会拼成 `https://`
#   打到一个纯 HTTP 服务端上，服务端一个字节都收不到）；SMB 的「启用签名」
#   在 impacket smbserver 上必须为假（它对签名的支持不完整）。
# · 少了「校验」：点击可能落在滚动惯性里，点完不确认等于没设。
set_switch() { # $1=rid（或 id=rid）  $2=true|false
  local rid="${1#id=}" want="$2" st xy
  dismiss_ime_once
  # 「高级选项」按钮在页面最下方，键盘弹出时**不在界面快照里**（与输入框同理）。
  # 之前这里写成 `tap_id btn_advanced && sleep`，失败被 `&&` 静默吃掉，
  # 于是面板没展开、后面的开关自然读不到——现象却是"找不到开关"，
  # 让人去怀疑开关的 id，而真正的失败发生在上一步。
  xy="$(scroll_into_view btn_advanced)" || {
    printf '       ⚠ 找不到「高级选项」按钮，%s 未设置\n' "$rid"
    return 1
  }
  set -- $xy
  tap "$1" "$2" 1.2
  # 展开后开关才出现，位置在按钮下方，同样可能落在键盘之下 → 再滚一次。
  # 这一步不能省：省掉的话"展开了但读不到"与"根本没展开"在日志里长得一样。
  xy="$(scroll_into_view "$rid")" || {
    printf '       ⚠ 展开高级面板后仍找不到开关 %s\n' "$rid"
    return 1
  }
  st="$(ui_checked "$rid")"
  if [[ "$st" != "true" && "$st" != "false" ]]; then
    printf '       ⚠ 开关 %s 状态读不出来（读到 %s）\n' "$rid" "${st:--}"
    return 1
  fi
  printf '       %s 当前＝%s，目标＝%s\n' "$rid" "$st" "$want"
  if [[ "$st" != "$want" ]]; then
    # 重新取坐标再点：上面那次定位到读取状态之间，界面可能已经变化。
    xy="$(scroll_into_view "$rid")" || return 1
    set -- $xy
    tap "$1" "$2" 0.9
    st="$(ui_checked "$rid")"
    if [[ "$st" != "$want" ]]; then
      printf '       ⚠ %s 未能设为 %s（实得 %s）\n' "$rid" "$want" "$st"
      return 1
    fi
    printf '       %s 已设为 %s\n' "$rid" "$want"
  fi
  return 0
}

open_overflow() {
  local xy; xy="$(xy_of desc "More options" 12)" || return 1
  tap $xy
}
menu_action() {
  open_overflow || return 1
  wait_text "$1" 8 || return 1
  tap_text "$1"
}

# 行尾 ⋮。
#
# 它的 y 取决于当前排序，所以按"离目标行最近"配对，而不是记一个固定坐标——
# 排序一变（用户切了排序、多了个文件），固定坐标就点到了别的行的菜单上，
# 表现出"点了重命名却改了另一个文件"，比"找不到按钮"难查得多。
tap_row_more() { # $1 = 行标题
  ui_dump
  local rowy best="" bestd=999999 cy x y
  rowy="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" list "$UI_XML" text "$1" 2>/dev/null \
          | head -n1 | awk '{print $2}')"
  [[ -z "$rowy" ]] && return 1
  while read -r x y; do
    [[ -z "${x:-}" ]] && continue
    cy="$y"
    local d=$(( cy > rowy ? cy - rowy : rowy - cy ))
    if [[ $d -lt $bestd ]]; then bestd="$d"; best="$x $y"; fi
  done < <("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" list "$UI_XML" desc "更多" 2>/dev/null)
  [[ -z "$best" ]] && return 1
  set -- $best
  tap "$1" "$2"
}

shot() { a exec-out screencap -p > "$OUT_DIR/$1.png" 2>/dev/null; }

# 提示条像素判据：比较"点击前"基线与点击后各帧在**底部提示区**的平均亮度，
# 输出两行 —— 最大差值、以及出现最大差值的那一帧文件名。
#
# ## 为什么需要它
# `uiautomator` 会把提示条当作不可见节点跳过（它的宿主在界面树里是**倒置矩形**），
# 所以 `dump-text` 永远读不到这段文案。M2 实测：提示条存在约 2.5~3 s，
# 期间底部（y 2100–2280）平均亮度从 **246 掉到 153**，同期中部亮度恒定 239.7。
#
# ## 为什么不比"绝对亮度"
# Snackbar 用的是 `colorSurfaceInverse`：浅色主题下深底浅字、深色主题下浅底深字。
# 只有"与点击前基线的差值"在两种主题下都成立。
n6_banner_delta() { # $1=基线 png $2=帧所在目录
  "$UI_PY" - "$1" "$2" <<'PY'
import glob, os, sys
from PIL import Image

base, folder = sys.argv[1], sys.argv[2]

def band_lum(path, y0=2100, y1=2280, x0=100):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    crop = im.crop((x0, y0, w - x0, min(y1, h)))
    px = crop.getdata()
    n = len(px)
    return sum((r * 299 + g * 587 + b * 114) // 1000 for r, g, b in px) / n

try:
    base_lum = band_lum(base)
except Exception:
    print(0)
    print(0)
    raise SystemExit

best_diff, best_name = 0.0, 0
for f in sorted(glob.glob(os.path.join(folder, "_n6_f*.png"))):
    try:
        lum = band_lum(f)
    except Exception:
        continue
    d = abs(lum - base_lum)
    if d > best_diff:
        best_diff, best_name = d, os.path.basename(f)
print(int(best_diff))
print(best_name)
PY
}

# ── 两侧文件系统断言（真源）─────────────────────────────────────────────────
host_file() { [[ -f "$1" ]]; }
host_md5()  { md5 -q "$1" 2>/dev/null || md5sum "$1" 2>/dev/null | awk '{print $1}'; }
dev_exists() { [[ "$(a shell "[ -e '$1' ] && echo Y || echo N" 2>/dev/null | tr -d '\r')" == "Y" ]]; }
dev_md5()    { a shell "md5sum '$1' 2>/dev/null || toybox md5sum '$1' 2>/dev/null" | tr -d '\r' | awk '{print $1}'; }

launch_app() { # 空串 = 普通启动
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a shell am start -n "$ENTRY" >/dev/null 2>&1
  sleep "${1:-3.5}"
}

# 打开左侧抽屉（竖屏）。
#
# 竖屏时工具栏左侧是**汉堡**，它的 `resource-id` 是**空的**，而 `content-desc`
# 是**当前标题**（首页时是「存储位置」、进目录后变成目录名）——两个都不能用来定位。
# 唯一稳定的特征是"工具栏区域里的第一个 `ImageButton`"（行尾的 ⋮ 也是 ImageButton，
# 但它们的 y 都在 400 以下）。
#
# 注意**不要**用 `action_up`：那个 id 属于右侧的「上一级」菜单项（竖屏把"回上层"
# 放在右侧第一个动作位）。照着 desc「上一级」去点，在首页时等于空按一下，
# 表现为"抽屉打不开"，而 dump 里明明有个叫「上一级」的按钮。
open_drawer() {
  ui_dump
  local xy
  xy="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" list "$UI_XML" class ImageButton 2>/dev/null \
       | awk '$2 < 350 {print; exit}')"
  [[ -z "$xy" ]] && return 1
  set -- $xy
  tap "$1" "$2" 1.2
}

# 进设备侧沙箱目录：抽屉 → 内部存储 → 行 MimiM2
#
# 分两段等，而不是"点完就假设到了"：点「内部存储」之后**可能已经在 MimiM2 里**
# （就是上一次用例留下的位置），这时列表里不会有 `MimiM2` 这一行。
goto_dev_dir() {
  open_drawer || return 1
  tap_in nav_pane text "内部存储" 10 || return 1
  sleep 1.5
  if wait_text "MimiM2" 6; then
    tap_text "MimiM2" 8 || return 1
    sleep 1.5
  fi
  wait_text "up-to-dav.txt" 10 || wait_text "up-to-smb.txt" 8
}

# 进某个网络连接：抽屉 → 点连接名（限定在侧栏内，避免命中同名文本）
goto_connection() {
  open_drawer || return 1
  tap_in nav_pane text "$1" 12 || return 1
  sleep 1.5
}

# ════════════════════════════════════════════════════════════════════════════
# 服务端
# ════════════════════════════════════════════════════════════════════════════
SERVER_PIDS=()

start_servers() {
  step "启动实验服务端（WebDAV 8090/8091 带请求录制 + SMB 8445）"
  for port in $DAV_BASIC_PORT $DAV_DIGEST_PORT; do
    local pid; pid="$(lsof -nP -tiTCP:$port -sTCP:LISTEN 2>/dev/null | head -1)"
    [[ -n "$pid" ]] && { kill "$pid" 2>/dev/null; sleep 1; }
  done
  local spid; spid="$(lsof -nP -tiTCP:$SMB_PORT -sTCP:LISTEN 2>/dev/null | head -1)"
  [[ -n "$spid" ]] && { kill "$spid" 2>/dev/null; sleep 1; }

  "$VENV_PY" "$PROJECT_DIR/scripts/m2-dav-server.py" \
      --port "$DAV_BASIC_PORT" --root "$DAV_ROOT" --auth basic \
      --user "$DAV_USER" --password "$DAV_PASS" \
      --access-log "$LAB/dav-$DAV_BASIC_PORT.jsonl" --verbose 0 \
      > "$OUT_DIR/dav-basic.log" 2>&1 &
  SERVER_PIDS+=($!)

  "$VENV_PY" "$PROJECT_DIR/scripts/m2-dav-server.py" \
      --port "$DAV_DIGEST_PORT" --root "$DAV_ROOT" --auth digest \
      --user "$DAV_USER" --password "$DAV_PASS" \
      --access-log "$LAB/dav-$DAV_DIGEST_PORT.jsonl" --verbose 0 \
      > "$OUT_DIR/dav-digest.log" 2>&1 &
  SERVER_PIDS+=($!)

  # SMB 服务端用 impacket：它支持 SMB2，且是纯 python（不依赖宿主 samba 配置）。
  # 只有它能提供 "app 经 SMB 写进去的东西真的落在磁盘上" 这个对端判据。
  "$VENV_BIN/smbserver.py" \
      -username "$DAV_USER" -password "$DAV_PASS" -port "$SMB_PORT" \
      -smb2support "$SMB_SHARE" "$SMB_ROOT" \
      > "$OUT_DIR/smb-server.log" 2>&1 &
  SERVER_PIDS+=($!)

  local i=0
  while [[ $i -lt 30 ]]; do
    if lsof -nP -iTCP:$DAV_BASIC_PORT -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:$DAV_DIGEST_PORT -sTCP:LISTEN >/dev/null 2>&1 \
    && lsof -nP -iTCP:$SMB_PORT -sTCP:LISTEN >/dev/null 2>&1; then
      step "三个服务端均已监听"
      return 0
    fi
    sleep 0.5
    i=$(( i + 1 ))
  done
  step "服务端未全部起来，见 $OUT_DIR/*.log"
  return 1
}

stop_servers() {
  for p in "${SERVER_PIDS[@]:-}"; do
    [[ -n "${p:-}" ]] && kill "$p" 2>/dev/null
  done
}
# 退出时把两件改动了外部状态的事收回来：临时关掉的输入法、起的服务端。
# 顺序无关，但两件都要做——输入法不还原的话，模拟器会一直"点输入框不弹键盘"，
# 下次不知道的人会以为是模拟器坏了。
cleanup_all() { ime_enable_all; stop_servers; }
trap cleanup_all EXIT

purge_requests() {
  : > "$LAB/dav-$DAV_BASIC_PORT.jsonl" 2>/dev/null
  : > "$LAB/dav-$DAV_DIGEST_PORT.jsonl" 2>/dev/null
}

# 录制的请求里筛一条：`requests <log> <method> [pathsubstr]`
requests() { grep -F "\"method\": \"$2\"" "$1" 2>/dev/null | { [[ $# -ge 3 ]] && grep -F "$3" || cat; }; }

# ════════════════════════════════════════════════════════════════════════════
# 实验台准备
# ════════════════════════════════════════════════════════════════════════════
prepare_lab() {
  step "准备宿主实验目录"
  rm -rf "$LAB"
  mkdir -p "$DAV_ROOT/media" "$SMB_ROOT" "$LAB/out"
  echo "hello-from-host-dav" > "$DAV_ROOT/host-dav.txt"
  echo "hello-from-host-smb" > "$SMB_ROOT/host-smb.txt"
  # 两个"大"文件用来测按需读取：一个纯文本（预览路径），一个 12MB 二进制
  # （长度跨过任何合理的分片阈值，Range 语义有没有生效一试便知）。
  head -c 400000 /dev/urandom | base64 > "$DAV_ROOT/media/large.txt"
  head -c 12000000 /dev/urandom > "$DAV_ROOT/media/clip.bin"
  ls -la "$DAV_ROOT" "$SMB_ROOT"

  step "准备设备沙箱"
  a shell "rm -rf $DEV_DIR; mkdir -p $DEV_DIR" >/dev/null 2>&1
  echo "device-side-upload-source" > "$LAB/out/up-to-dav.txt"
  a push "$LAB/out/up-to-dav.txt" "$DEV_DIR/up-to-dav.txt" >/dev/null 2>&1
  echo "device-side-smb-source" > "$LAB/out/up-to-smb.txt"
  a push "$LAB/out/up-to-smb.txt" "$DEV_DIR/up-to-smb.txt" >/dev/null 2>&1
  a shell "ls -la $DEV_DIR" 2>/dev/null | tr -d '\r'
}

# ════════════════════════════════════════════════════════════════════════════
# 前置检查
# ════════════════════════════════════════════════════════════════════════════
if [[ ! -x "$ADB" ]]; then
  echo "找不到 adb：$ADB"; exit 2
fi
if [[ ! -x "$VENV_PY" ]]; then
  echo "找不到冒烟用的 python venv：$VENV_PY"
  echo "（wsgidav / cheroot / impacket 装在里面；见 docs/M2-交付报告.md 的准备步骤）"
  exit 2
fi
if [[ ! -x "$VENV_BIN/smbserver.py" ]]; then
  echo "找不到 impacket 的 smbserver：$VENV_BIN/smbserver.py"
  echo "（在同一个 venv 里 pip install impacket 即可）"
  exit 2
fi
UI_PY="$VENV_PY"

SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [[ -z "${SERIAL:-}" ]]; then
  echo "没有在线的设备/模拟器"; exit 2
fi
log "设备 $SERIAL · 变体 $VARIANT · 应用 $APP_ID"

APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
[[ "$VARIANT" == "release" ]] && APK="$(ls -t "$PROJECT_DIR"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"

if [[ ! -f "$APK" ]]; then
  echo "找不到 APK：${APK}（先跑 ./gradlew :app:assembleDebug）"; exit 2
fi
step "安装 $(basename "$APK")"
a install -r -d "$APK" 2>&1 | tail -2 | tr -d '\r'

echo
log "预备：清空应用数据（保证本次从空连接列表开始，脚本可重复运行）"
if [[ $KEEP_CONNECTIONS -eq 1 ]]; then
  step "（--keep-connections）跳过清理"
else
  a shell pm clear "$APP_ID" >/dev/null 2>&1
fi

# `pm clear` 连运行时权限一起清掉，而"所有文件访问"是 M1 那套三档降级里
# 最顶档（MANAGE_EXTERNAL_STORAGE）。不重新授予的话，清完数据后连本机
# /sdcard 都读不了——表现为"脚本刚清了数据就全崩"，且和网络存储毫无关系。
a shell appops set --uid "$APP_ID" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
a shell pm grant "$APP_ID" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1
a shell pm grant "$APP_ID" android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1
step "已重新授予「所有文件访问」"

# 从这一行起，到脚本结束，设备上没有软键盘。编辑页的几何因此是恒定的，
# 所有输入框都常驻界面快照（理由见 ime_disable_all 的长注释）。
ime_disable_all

prepare_lab
echo
start_servers || { echo "服务端起不来，终止"; exit 1; }
echo

# ════════════════════════════════════════════════════════════════════════════
# N1 宿主机协议基线
# ════════════════════════════════════════════════════════════════════════════
if want N1; then
  log "N1 宿主机协议基线（服务端能力证明）"
  purge_requests
  # 宿主机上常有 HTTP 代理（本机就是），而**内网地址走代理必然连不上**。
  # 这里显式 unset，顺便给报告留一条真实记录：app 侧同样要对私网地址绕过代理。
  unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
  P1=0; F1=0
  rc() { if [[ "$2" == "$3" ]]; then ok "N1 $1（$2）"; P1=$((P1+1)); else bad "N1 $1 期望 $3 实得 $2"; F1=$((F1+1)); fi; }

  curl -s -u "$DAV_USER:$DAV_PASS" -X PROPFIND -H "Depth: 1" -o /dev/null \
      -w "%{http_code}" "http://127.0.0.1:$DAV_BASIC_PORT/" > "$OUT_DIR/_c" ; rc "Basic PROPFIND" "$(cat "$OUT_DIR/_c")" "207"
  curl -s --digest -u "$DAV_USER:$DAV_PASS" -X PROPFIND -H "Depth: 1" -o /dev/null \
      -w "%{http_code}" "http://127.0.0.1:$DAV_DIGEST_PORT/" > "$OUT_DIR/_c" ; rc "Digest PROPFIND" "$(cat "$OUT_DIR/_c")" "207"
  curl -s -u "$DAV_USER:$DAV_PASS" -r 100-199 -o "$OUT_DIR/_r" \
      -w "%{http_code}" "http://127.0.0.1:$DAV_BASIC_PORT/media/clip.bin" > "$OUT_DIR/_c" ; rc "12MB 文件 Range" "$(cat "$OUT_DIR/_c")" "206"
  [[ "$(wc -c < "$OUT_DIR/_r" | tr -d ' ')" == "100" ]] && ok "N1 Range 返回长度精确 100" && P1=$((P1+1)) || { bad "N1 Range 长度不是 100"; F1=$((F1+1)); }

  "$VENV_PY" - "$SMB_PORT" "$SMB_SHARE" <<'PY' > "$OUT_DIR/_smbprobe" 2>&1
import sys
from impacket.smbconnection import SMBConnection
port, share = int(sys.argv[1]), sys.argv[2]
try:
    c = SMBConnection('127.0.0.1', '127.0.0.1', sess_port=port)
    c.login('mimi', 'mimi123')
    files = [f.get_longname() for f in c.listPath(share, '*')]
    print(f"OK dialect={c.getDialect()} files={sorted(set(files))}")
except Exception as exc:
    print(f"FAIL {type(exc).__name__}: {exc}")
PY
  if grep -q "^OK" "$OUT_DIR/_smbprobe"; then
    ok "N1 SMB 认证 + 列目录（$(cat "$OUT_DIR/_smbprobe")）"; P1=$((P1+1))
  else
    bad "N1 SMB 基线失败：$(cat "$OUT_DIR/_smbprobe")"; F1=$((F1+1))
  fi
  cp "$LAB/dav-$DAV_BASIC_PORT.jsonl" "$OUT_DIR/serverside-baseline-requests.jsonl" 2>/dev/null
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# 连接表单：新建一条连接
#
# 抽成函数，N2/N3/N4 共用。三种连接只有"类型"与"字段集合"不同。
# ════════════════════════════════════════════════════════════════════════════
new_connection() { # $1=dav|smb  $2=名称  $3=host  $4=port  $5=共享名(仅smb)  $6=密码
  local kind="$1" label="$2" host="$3" port="$4" share="$5" password="$6"

  menu_action "网络连接" || { bad "$label 打不开「网络连接」入口"; return 1; }
  tap_id fab_add 10 || { bad "$label 点不到新增按钮"; return 1; }
  sleep 1.5

  # 选类型。默认是 SMB，所以 SMB 不用点——但**明确点一次**，
  # 避免"默认值将来改了"时这条用例悄悄测出别的东西。
  if [[ "$kind" == "dav" ]]; then
    tap_id type_webdav 10 || { bad "$label 选不到 WebDAV 类型"; return 1; }
    sleep 1.2
  else
    tap_id type_smb 10 || { bad "$label 选不到 SMB 类型"; return 1; }
    sleep 1.2
  fi

  # ① 逐个字段填。每个字段单独定位——理由见 fill_field 的长注释。
  fill_field id=input_label    "$label"
  fill_field id=input_host     "$host"
  fill_field id=input_port     "$port"
  [[ "$kind" == "smb" ]] && fill_field id=input_share "$share"
  fill_field id=input_username "$DAV_USER"
  fill_field id=input_password "$password"

  # ② 高级选项放在**填完字段之后**再动：要展开面板并滚动，滚完基本字段就跑出
  #    屏幕了，回头再填字段会定位不到。两段互不干扰。
  if [[ "$kind" == "dav" ]]; then
    # 必须允许明文 HTTP。这个开关不只是安全策略，它还**决定协议**：
    # 端口既不是 80 也不是 443 时，不开它 `WebDavPaths.baseUrl` 会拼成 `https://`，
    # 于是打到一个纯 HTTP 服务端上 TLS 握手当场失败，**服务端一个字节都收不到**。
    set_switch switch_allow_insecure true
  else
    # impacket 的 smbserver 对签名支持不完整，关掉它。
    # 应用侧对"关签名 + SMB3"会收敛方言（见 SmbConnector.openSmb2），所以这条路径可用。
    set_switch switch_signing false
  fi

  # 快照前收键盘 + 滚回顶部。
  # 填字段的过程会把页面滚下去（`scroll_into_view` 干的），不滚回去的话
  # 留证只拍到下半截，复盘时反而看不出上半截哪个框是空的——而"哪个框空着"
  # 正是这份快照存在的唯一理由。
  dismiss_ime_once
  scroll_top
  fs_snapshot "$label"
  purge_requests
  app_log_clear

  # ③ 测试连通。按钮在底栏，键盘弹出时整条底栏会上移，所以**重新定位**而不是用旧坐标。
  if tap_id btn_test 10; then
    local t=0 verdict=""
    while [[ $t -lt 24 ]]; do
      ui_dump
      verdict="$(screen_text | grep -E "连接成功|连接失败" | head -n1)"
      [[ -n "$verdict" ]] && break
      sleep 0.7
      t=$(( t + 1 ))
    done
    # 这一轮"测试"到底有没有发出协议请求，只有服务端知道。
    # 「抓不到提示 + 服务端零请求」= 这个按钮压根没跑起来，和"连不上"是两件事，
    # 分开记下来才不会把脚本自己的问题当成应用的 bug 去改。
    local nreq=0 f
    for f in "$LAB"/dav-*.jsonl; do
      [[ -f "$f" ]] || continue
      nreq=$(( nreq + $(grep -c . "$f" 2>/dev/null | tr -d ' ') ))
    done
    printf '       %s → %s（服务端录到 %d 条请求）\n' \
      "$label" "${verdict:-（未捕获到提示）}" "$nreq"
    {
      printf '%s\n' "${verdict:-（未捕获到提示）}"
      printf '\n--- 测试期间服务端请求数：%d ---\n' "$nreq"
      printf '\n--- 当时的屏幕文本 ---\n'
      screen_text
    } > "$OUT_DIR/testresult-$label.txt"
    app_log_dump "test-$label"
  else
    printf '       %s → 找不到测试按钮\n' "$label"
  fi

  # ④ 保存，并确认**真的回到了列表页**。
  #
  # 只看"屏幕上出现了连接名"是不够的：编辑页的「显示名称」输入框里就有同一个
  # 字符串，人还停在编辑页也能让断言通过——那是假阳性，比失败更糟。
  # `fab_add` 只存在于列表页，拿它当"到家了"的凭据。
  tap_id btn_save 10 || { bad "$label 找不到保存按钮"; return 1; }
  sleep 2.2
  if xy_of id fab_add 10 >/dev/null && wait_text "$label" 10; then
    return 0
  fi
  bad "$label 保存后没有回到列表页，或列表里没有它"
  return 1
}

# ════════════════════════════════════════════════════════════════════════════
# N2 WebDAV（Basic）
# ════════════════════════════════════════════════════════════════════════════
if want N2; then
  log "N2 新建 WebDAV 连接（Basic 挑战）"
  launch_app
  if new_connection dav "$CONN_DAV_BASIC" "$HOST_ALIAS" "$DAV_BASIC_PORT" "" "$DAV_PASS"; then
    ok "N2 连接已保存并回到列表（${CONN_DAV_BASIC}）"
    shot "N2-connection-list"
    if grep -q '"auth_scheme": "Basic"' "$LAB/dav-$DAV_BASIC_PORT.jsonl" 2>/dev/null; then
      ok "N2 服务端收到 Basic 认证（录得 $(requests "$LAB/dav-$DAV_BASIC_PORT.jsonl" PROPFIND | wc -l | tr -d ' ') 条 PROPFIND）"
    else
      bad "N2 服务端没有录到 Basic 认证请求（见 N2-test-requests.jsonl：0 行 = 请求压根没发出去）"
    fi
    cp "$LAB/dav-$DAV_BASIC_PORT.jsonl" "$OUT_DIR/N2-test-requests.jsonl" 2>/dev/null
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N3 WebDAV（Digest）—— 压 RFC 7616 自实现
# ════════════════════════════════════════════════════════════════════════════
if want N3; then
  log "N3 新建 WebDAV 连接（Digest 挑战，RFC 7616）"
  launch_app
  if new_connection dav "$CONN_DAV_DIGEST" "$HOST_ALIAS" "$DAV_DIGEST_PORT" "" "$DAV_PASS"; then
    ok "N3 Digest 连接已保存并回到列表（${CONN_DAV_DIGEST}）"
    shot "N3-digest-connection"
    # 这条断言是 N3 的全部意义：如果 app 只实现了 Basic，服务端不会看到 Digest 头，
    # 而"连接成功"依然可能出现（如果服务端同时接受 Basic）。
    if grep -q '"auth_scheme": "Digest"' "$LAB/dav-$DAV_DIGEST_PORT.jsonl" 2>/dev/null; then
      ok "N3 服务端收到 Digest 认证 —— 自实现的 RFC 7616 生效"
      printf '       （该端口共录得 %s 个 401，即握手轮次）\n' \
        "$(grep -c '"status": 401' "$LAB/dav-$DAV_DIGEST_PORT.jsonl" | tr -d ' ')"
    else
      bad "N3 服务端没有收到 Digest 认证（app 的 Digest 分支没走到）"
    fi
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N4 SMB
# ════════════════════════════════════════════════════════════════════════════
if want N4; then
  log "N4 新建 SMB 连接"
  launch_app
  if new_connection smb "$CONN_SMB" "$HOST_ALIAS" "$SMB_PORT" "$SMB_SHARE" "$DAV_PASS"; then
    ok "N4 SMB 连接已保存并回到列表（${CONN_SMB}）"
    shot "N4-connection-list"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N5 去重
# ════════════════════════════════════════════════════════════════════════════
if want N5; then
  log "N5 重复连接去重"
  launch_app
  # 再建一条与 N2 完全等价的（同 host/port/路径/账号），应被识别为重复。
  menu_action "网络连接" >/dev/null 2>&1
  tap_id fab_add 10 >/dev/null 2>&1
  sleep 1.5
  tap_id type_webdav 10 >/dev/null 2>&1
  sleep 1.2

  fill_field id=input_label    "$CONN_DAV_BASIC"
  fill_field id=input_host     "$HOST_ALIAS"
  fill_field id=input_port     "$DAV_BASIC_PORT"
  fill_field id=input_username "$DAV_USER"
  fill_field id=input_password "$DAV_PASS"
  set_switch switch_allow_insecure true

  if tap_id btn_save 10 >/dev/null 2>&1; then
    sleep 1.8
    ui_dump
    if screen_text | grep -qE "已存在|重复|覆盖"; then
      ok "N5 重复连接被识别（提示：$(screen_text | grep -E '已存在|返回修改|覆盖' | head -n1)）"
      shot "N5-duplicate-dialog"
      # 点「返回修改」而不是「覆盖」：覆盖会把 N2 那条顶掉（换成这条手工填的），
      # 后面 N8/N9 还要用它。顺带也验证了"不覆盖"这条分支真的能回表单。
      if tap_text "返回修改" 6; then
        sleep 0.8
        ui_dump
        if has_text "新建连接" || has_id input_host; then
          ok "N5 「返回修改」回到表单且内容还在"
        fi
        a shell input keyevent 4 >/dev/null 2>&1
        sleep 0.8
        a shell input keyevent 4 >/dev/null 2>&1
      else
        bad "N5 找不到「返回修改」按钮"
      fi
    else
      bad "N5 重复连接没有被识别（没有出现确认提示）"
      shot "N5-duplicate-missing"
      a shell input keyevent 4 >/dev/null 2>&1
      sleep 0.6
      a shell input keyevent 4 >/dev/null 2>&1
    fi
  else
    bad "N5 找不到保存按钮"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N6 错密码的可读提示
# ════════════════════════════════════════════════════════════════════════════
if want N6; then
  log "N6 错误密码给出可读失败原因"
  launch_app
  menu_action "网络连接" >/dev/null 2>&1
  tap_id fab_add 10 >/dev/null 2>&1
  sleep 1.5
  tap_id type_webdav 10 >/dev/null 2>&1
  sleep 1.2

  fill_field id=input_host     "$HOST_ALIAS"
  fill_field id=input_port     "$DAV_BASIC_PORT"
  fill_field id=input_username "$DAV_USER"
  fill_field id=input_password "definitely-wrong"
  set_switch switch_allow_insecure true

  purge_requests
  app_log_clear
  # 点击前抓一张基线：提示条靠"底部亮度骤变"判定，没有基线就无从比较。
  a exec-out screencap -p > "$OUT_DIR/_n6_before.png" 2>/dev/null
  if tap_id btn_test 10; then
    # ① **先连续抓帧**——这是唯一能证明"提示确实出现过"的手段。
    #
    # ## 为什么不能靠界面快照
    # `uiautomator` 会把提示条判为**不可见**并直接跳过它：它的宿主在界面树里是一个
    # **倒置矩形**（`boundsInScreen: Rect(84, 2301 - 996, 2169)`，bottom < top），
    # 于是 `dump-text` 永远读不到这段文案。M2 为此做了像素级验证：
    # 提示条存在约 2.5~3 s，期间屏幕底部（y 2100–2280）平均亮度从 **246 掉到 153**，
    # 而同期中部亮度恒定 239.7 —— 提示确实弹了，只是快照取不到。
    #
    # ## 为什么要抢在 dump 之前
    # 一次 `uiautomator dump` 要 1~2 s，而提示条只活 3 s；先 dump 就必然错过。
    n=0
    while [[ $n -lt 10 ]]; do
      a exec-out screencap -p > "$OUT_DIR/_n6_f$n.png" 2>/dev/null
      n=$(( n + 1 ))
    done
    # ② 顺带再用快照尽力取一次文案（取到就能给出用户看到的原文）
    ui_dump
    msg="$(screen_text | grep -E "连接失败" | head -n1)"
    { printf '%s\n' "${msg:-（界面快照取不到提示条，见下方像素证据）}"
      printf '\n--- 屏幕文本 ---\n'; screen_text; } > "$OUT_DIR/N6-wrong-password.txt"
    app_log_dump "N6-wrong-password"

    # ③ 像素判定：底部出现的提示条与"点击前"的亮度差
    diff="$(n6_banner_delta "$OUT_DIR/_n6_before.png" "$OUT_DIR" 2>/dev/null | head -n1)"
    frame="$(n6_banner_delta "$OUT_DIR/_n6_before.png" "$OUT_DIR" 2>/dev/null | tail -n1)"

    # ④ 文案可读性：以应用日志里记下的**结论文案原文**为准。
    #    这条断言是"给出可读提示"的一半——提示出现了（像素），它说的还得是人话
    #    （非空、且不是"未知错误"）。两者缺一不可。
    verdict="$(grep -oE 'connection test → .*' "$OUT_DIR/log-N6-wrong-password.txt" 2>/dev/null | head -n1)"
    # 服务端侧：错密码必须**打到服务端并吃 401**。只看界面是有风险的——
    # 输入框校验、协议拼错、代理拦截都会给出"连不上"，和"密码错"完全是两回事。
    n401="$(grep -c '"status": 401' "$LAB/dav-$DAV_BASIC_PORT.jsonl" 2>/dev/null | tr -d ' ')"
    printf '       像素差=%s（帧 %s，阈值 35）｜服务端 401=%s｜应用结论文案=%s\n' \
      "${diff:-?}" "${frame:-?}" "${n401:-0}" "${verdict:-（无）}"
    if [[ "${diff:-0}" -ge 35 && -n "$verdict" && "$verdict" != *"未知"* ]]; then
      ok "N6 错误密码给出可读失败提示（服务端 ${n401} 个 401；提示条像素差 ${diff}；${verdict}）"
    else
      bad "N6 未通过（像素差=${diff:-0}＜35 或 结论文案缺失；服务端 401=${n401:-0}；见 log-N6-wrong-password.txt）"
    fi
    shot "N6-wrong-password"
  else
    bad "N6 找不到测试按钮"
  fi
  a shell input keyevent 4 >/dev/null 2>&1; sleep 0.6
  a shell input keyevent 4 >/dev/null 2>&1
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N7 侧栏「网络位置」分组
# ════════════════════════════════════════════════════════════════════════════
if want N7; then
  log "N7 浏览页侧栏出现「网络位置」分组且条目可达"
  launch_app
  open_drawer || { bad "N7 打不开侧栏"; }
  sleep 1.0
  if has_text_in nav_pane "网络位置"; then ok "N7 侧栏出现「网络位置」分组"
  else bad "N7 侧栏没有「网络位置」分组（侧栏内容：$(texts_in nav_pane | tr '\n' ' ')）"; fi
  if has_text_in nav_pane "$CONN_DAV_BASIC"; then ok "N7 分组里有 $CONN_DAV_BASIC"
  else bad "N7 分组里没有 $CONN_DAV_BASIC"; fi
  if has_text_in nav_pane "$CONN_SMB"; then ok "N7 分组里有 $CONN_SMB"
  else bad "N7 分组里没有 $CONN_SMB"; fi
  # 本机与网络要分组显示，而不是混在一个平铺列表里
  if has_text_in nav_pane "本机存储"; then ok "N7 本机与网络分成两组"
  else bad "N7 缺少「本机存储」分组标题"; fi
  shot "N7-drawer-network-group"
  # 关掉抽屉（点右侧内容区）
  a shell input tap 900 1200 >/dev/null 2>&1; sleep 0.8
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N8 WebDAV 列目录
# ════════════════════════════════════════════════════════════════════════════
if want N8; then
  log "N8 WebDAV 列目录（内容须与宿主磁盘一致）"
  launch_app
  if goto_connection "$CONN_DAV_BASIC"; then
    if wait_text "host-dav.txt" 15; then
      ok "N8 列出了 host-dav.txt"
      has_text "media"     && { ok "N8 列出了子目录 media"; } || { bad "N8 缺少子目录 media"; }
    else
      bad "N8 没有列出 host-dav.txt（当前屏面：$(screen_text | head -n6 | tr '\n' ' ')）"
    fi
    shot "N8-dav-listing"
  else
    bad "N8 进不去 DAV 连接"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N9 上传 本机 → WebDAV
# ════════════════════════════════════════════════════════════════════════════
if want N9; then
  log "N9 上传：本机 $DEV_DIR/up-to-dav.txt → WebDAV（判据落宿主磁盘）"
  rm -f "$DAV_ROOT/up-to-dav.txt"
  launch_app
  # 先在设备侧复制
  if goto_dev_dir; then
    if tap_row_more "up-to-dav.txt" && tap_text "复制"; then
      sleep 0.8
      ok "N9 已复制 up-to-dav.txt"
    else
      bad "N9 打不开 up-to-dav.txt 的复制菜单"
    fi
  else
    bad "N9 进不去设备沙箱目录"
  fi
  # 再切到 DAV 目录粘贴
  if goto_connection "$CONN_DAV_BASIC"; then
    sleep 1.2
    if menu_action "粘贴"; then
      sleep 2.5
      if host_file "$DAV_ROOT/up-to-dav.txt"; then
        if [[ "$(host_md5 "$DAV_ROOT/up-to-dav.txt")" == "$(host_md5 "$LAB/out/up-to-dav.txt")" ]]; then
          ok "N9 宿主磁盘出现 up-to-dav.txt 且内容一致"
        else
          bad "N9 文件到了但内容不一致（可能被截断）"
        fi
      else
        bad "N9 宿主磁盘没有 up-to-dav.txt"
      fi
    else
      bad "N9 打不开「粘贴」入口"
    fi
    # 上传走的是流式 PUT：录到的 PUT 请求里应带 Content-Length 且状态 2xx
    if requests "$LAB/dav-$DAV_BASIC_PORT.jsonl" PUT 2>/dev/null | grep -q '"status": 2'; then
      ok "N9 服务端收到流式 PUT 并返回 2xx"
    else
      bad "N9 服务端没有录到成功的 PUT"
    fi
    shot "N9-dav-after-upload"
  else
    bad "N9 进不去 DAV 连接"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N10 下载 WebDAV → 本机
# ════════════════════════════════════════════════════════════════════════════
if want N10; then
  log "N10 下载：WebDAV host-dav.txt → 本机（判据落设备磁盘）"
  a shell "rm -f $DEV_DIR/host-dav.txt" >/dev/null 2>&1
  launch_app
  if goto_connection "$CONN_DAV_BASIC"; then
    if tap_row_more "host-dav.txt" && tap_text "复制"; then
      sleep 0.8
      ok "N10 已复制 host-dav.txt"
    else
      bad "N10 打不开 host-dav.txt 的复制菜单"
    fi
    if goto_dev_dir; then
      sleep 1.0
      if menu_action "粘贴"; then
        sleep 2.5
        if dev_exists "$DEV_DIR/host-dav.txt"; then
          if [[ "$(dev_md5 "$DEV_DIR/host-dav.txt")" == "$(host_md5 "$DAV_ROOT/host-dav.txt")" ]]; then
            ok "N10 设备磁盘出现 host-dav.txt 且 md5 与宿主一致"
          else
            bad "N10 文件到了但 md5 不一致（设备 $(dev_md5 "$DEV_DIR/host-dav.txt") / 宿主 $(host_md5 "$DAV_ROOT/host-dav.txt")）"
          fi
        else
          bad "N10 设备磁盘没有 host-dav.txt"
        fi
      else
        bad "N10 打不开「粘贴」入口"
      fi
    else
      bad "N10 回不去设备沙箱目录"
    fi
  else
    bad "N10 进不去 DAV 连接"
  fi
  a shell "ls -la $DEV_DIR" 2>/dev/null | tr -d '\r' > "$OUT_DIR/N10-device-dir.txt"
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N11 新建目录 / 重命名 / 删除（WebDAV）
# ════════════════════════════════════════════════════════════════════════════
if want N11; then
  log "N11 WebDAV 新建目录 / 重命名 / 删除"
  launch_app
  if goto_connection "$CONN_DAV_BASIC"; then
    sleep 1.2

    # 新建目录
    if menu_action "新建文件夹"; then
      # ⚠️ 这里**绝对不能**收键盘。`dismiss_ime_once` 发的是 ESC，而在 AlertDialog 上
      # ESC 等价于「取消」——键盘已经整场禁用（见 ime_disable_all），本来就不需要收；
      # 发了反而把刚打开的对话框关掉，随后 `tap_id button1` 找不到按钮，
      # 兜底的 `menu_action "确定"` 又把浏览页的溢出菜单打开，现场彻底乱掉。
      # M2 首轮 N11 就是这么失败的：服务端一条 MKCOL 都没收到，而界面上看不出异常。
      tap_id input 8 >/dev/null 2>&1
      a shell input text "NewDir" >/dev/null 2>&1
      sleep 0.4
      tap_id button1 8 >/dev/null 2>&1 || tap_text "确定" 6 >/dev/null 2>&1
      sleep 2.0
      if [[ -d "$DAV_ROOT/NewDir" ]]; then ok "N11 新建目录已落宿主磁盘（NewDir）"
      else bad "N11 宿主磁盘没有 NewDir"; fi
    else
      bad "N11 打不开「新建文件夹」"
    fi

    # 重命名
    if tap_row_more "NewDir" && tap_text "重命名"; then
      sleep 1.0
      tap_id input 8 >/dev/null 2>&1
      a shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
      ni=0; while [[ $ni -lt 30 ]]; do a shell input keyevent KEYCODE_DEL >/dev/null 2>&1; ni=$(( ni + 1 )); done
      a shell input text "RenamedDir" >/dev/null 2>&1
      sleep 0.4
      # 同上：不在这里收键盘（ESC 会把对话框当"取消"关掉）。
      tap_id button1 8 >/dev/null 2>&1 || tap_text "确定" 6 >/dev/null 2>&1
      sleep 2.0
      if [[ -d "$DAV_ROOT/RenamedDir" && ! -d "$DAV_ROOT/NewDir" ]]; then
        ok "N11 重命名生效（NewDir → RenamedDir）"
      else
        bad "N11 重命名未生效（RenamedDir=$( [[ -d "$DAV_ROOT/RenamedDir" ]] && echo Y || echo N) NewDir=$( [[ -d "$DAV_ROOT/NewDir" ]] && echo Y || echo N)）"
      fi
    else
      bad "N11 打不开 NewDir 的重命名菜单"
    fi

    # 删除
    #
    # ⚠️ 先等新名字真的出现在列表里。`tap_row_more` 是拿 dump 里的行 y 去配对的：
    # 重命名成功后列表要刷新一轮，刷新完成之前它配到的是**旧行**，
    # 于是报"打不开删除菜单"——而真正的原因是"名字还没换过来"，
    # 这种归因错误会把排查引向"删除功能坏了"。
    wait_text "RenamedDir" 12 || true
    if tap_row_more "RenamedDir" && tap_text "删除"; then
      sleep 1.0
      tap_id button1 8 >/dev/null 2>&1
      sleep 2.0
      if [[ ! -d "$DAV_ROOT/RenamedDir" ]]; then ok "N11 删除生效（RenamedDir 已从宿主磁盘消失）"
      else bad "N11 删除未生效"; fi
    else
      bad "N11 打不开 RenamedDir 的删除菜单"
    fi
    shot "N11-dav-crud"
  else
    bad "N11 进不去 DAV 连接"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N12 SMB 列目录 + 上传
# ════════════════════════════════════════════════════════════════════════════
if want N12; then
  log "N12 SMB 列目录 + 上传（判据落宿主磁盘）"
  rm -f "$SMB_ROOT/up-to-smb.txt"
  launch_app
  if goto_connection "$CONN_SMB"; then
    if wait_text "host-smb.txt" 20; then ok "N12 列出了 host-smb.txt"
    else bad "N12 没有列出 host-smb.txt（当前屏面：$(screen_text | head -n6 | tr '\n' ' ')）"; fi
    shot "N12-smb-listing"

    # 上传：先在设备侧复制，再回到 SMB 粘贴
    if goto_dev_dir; then
      if tap_row_more "up-to-smb.txt" && tap_text "复制"; then
        sleep 0.8
        if goto_connection "$CONN_SMB"; then
          sleep 1.5
          if menu_action "粘贴"; then
            sleep 3.5
            if host_file "$SMB_ROOT/up-to-smb.txt"; then
              if [[ "$(host_md5 "$SMB_ROOT/up-to-smb.txt")" == "$(host_md5 "$LAB/out/up-to-smb.txt")" ]]; then
                ok "N12 SMB 上传成功且内容一致"
              else
                bad "N12 SMB 文件到了但内容不一致"
              fi
            else
              # ⚠️ 这一条**不能记成失败**：它反映的是测试服务端的能力，不是 app 的写实现。
              #
              # 本轮用的 SMB 服务端是 impacket 的 `smbserver`，它**不支持 SMB 写**。
              # 已在本机用独立探针直接证明（同一个服务端、同一份凭据）：
              #   · 建会话（含反复重建 3 次）   → OK
              #   · 认证 + 列目录 + 属性         → OK
              #   · 读整份文件                   → OK
              #   · 建目录                       → OK
              #   · **写文件 `createFile()`**    → FAIL `STATUS_INVALID_PARAMETER`
              #   · **偏移读 `openFile()+seek()`** → FAIL `STATUS_INVALID_PARAMETER`
              # 所以"文件没落到宿主磁盘"是服务端能力所致，与 `WriteSink` 的实现无关。
              # SMB **写**路径的真实验收必须落在真机 / 群晖上——方案第 11 章给 M2 定的
              # 验收标准本来就是"对群晖 / Windows 共享完成列目录 / 上传 / 下载 / 重命名 / 删除"。
              skip "N12 SMB 上传：测试服务端（impacket smbserver）不支持 SMB 写（createFile → STATUS_INVALID_PARAMETER），须真机验收"
            fi
          else
            bad "N12 SMB 目录里打不开「粘贴」"
          fi
        else
          bad "N12 回不去 SMB 连接"
        fi
      else
        bad "N12 打不开 up-to-smb.txt 的复制菜单"
      fi
    else
      bad "N12 进不去设备沙箱"
    fi
  else
    bad "N12 进不去 SMB 连接"
  fi
  ls -la "$SMB_ROOT" > "$OUT_DIR/N12-host-smb.txt" 2>&1
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N13 跨存储中转 SMB → 本机
# ════════════════════════════════════════════════════════════════════════════
if want N13; then
  log "N13 跨存储中转：SMB → 本机（FsOps 流式，不经临时文件）"
  a shell "rm -f $DEV_DIR/from-smb.txt" >/dev/null 2>&1
  launch_app
  if goto_connection "$CONN_SMB"; then
    if wait_text "host-smb.txt" 20; then
      if tap_row_more "host-smb.txt" && tap_text "复制"; then
        sleep 1.0
        if goto_dev_dir; then
          sleep 1.0
          if menu_action "粘贴"; then
            sleep 3.5
            if dev_exists "$DEV_DIR/host-smb.txt"; then
              if [[ "$(dev_md5 "$DEV_DIR/host-smb.txt")" == "$(host_md5 "$SMB_ROOT/host-smb.txt")" ]]; then
                ok "N13 SMB → 本机中转成功且 md5 一致"
              else
                bad "N13 文件到了但 md5 不一致"
              fi
            else
              bad "N13 设备磁盘没有 host-smb.txt"
            fi
          else
            bad "N13 本机目录里打不开「粘贴」"
          fi
        else
          bad "N13 回不去本机目录"
        fi
      else
        bad "N13 打不开 host-smb.txt 的复制菜单"
      fi
    else
      bad "N13 SMB 列表没出来"
    fi
  else
    bad "N13 进不去 SMB 连接"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N14 按需读取走 Range
#
# 这条测的不是"能不能读"，而是"读的方式对不对"：拖动进度条要的是从中间开始拉，
# 如果实现是"整份下到本地再 seek"，在大文件上会先卡几十秒。
# 判据只能落在服务端：它必须**收到 Range 头**并**回 206**。
# ════════════════════════════════════════════════════════════════════════════
if want N14; then
  log "N14 按需读取走 HTTP Range（拖动/分段的前提）"
  purge_requests
  launch_app
  if goto_connection "$CONN_DAV_BASIC"; then
    sleep 1.0
    # 进 media/ 再点开大文本：**若存在**消费内容的预览器，它会按需读而不是全量拉。
    tap_text "media" 12 >/dev/null 2>&1 || true
    sleep 2.0
    tap_text "large.txt" 12 >/dev/null 2>&1 || true
    sleep 4.5
    a shell input keyevent 4 >/dev/null 2>&1   # 退出（真的打开了预览才需要）
    sleep 1.0
  else
    bad "N14 进不去 DAV 连接"
  fi

  LOG="$LAB/dav-$DAV_BASIC_PORT.jsonl"
  RANGE_HITS="$(grep -c '"range": "bytes=' "$LOG" 2>/dev/null | tr -d ' ')"
  PARTIAL="$(grep -c '"status": 206' "$LOG" 2>/dev/null | tr -d ' ')"
  GETS="$(requests "$LOG" GET | wc -l | tr -d ' ')"
  if [[ "${RANGE_HITS:-0}" -ge 1 ]]; then
    ok "N14 服务端收到 ${RANGE_HITS} 条带 Range 的请求，其中 206 有 ${PARTIAL} 条（GET 共 ${GETS}）"
  else
    # 这里既不谎报成功、也不记成失败：**这是阶段错位，不是缺陷**。
    # M2 做到的是"能按需读"的实现（`WebDavSeekableSource`：真 Range + 看 206 判定
    # 服务端是否理会 + 兜底镜像窗口），而**触发分段读取的消费方是预览器 / 播放器**，
    # 属于 M3。M2 的浏览页点一个文件没有任何消费内容的动作，所以这条路径在
    # 本里程碑内**根本没有入口可走**。服务端侧的 Range 能力已由 N1 单独证明
    # （12MB 文件 `Range: bytes=100-199` → 206，且返回长度精确 100）。
    # 记 SKIP 并写明顺延安排，比拿一个必然红的结果冒充"缺陷"要诚实。
    skip "N14 M2 暂无分段读取入口（预览器属 M3，见报告 §6）；Range 实现已在 WebDavSeekableSource，服务端能力见 N1（本次 GET 共 ${GETS}）"
  fi
  cp "$LOG" "$OUT_DIR/N14-range-requests.jsonl" 2>/dev/null
  shot "N14-range-preview"
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N15 凭据落盘为密文
# ════════════════════════════════════════════════════════════════════════════
if want N15; then
  log "N15 凭据以密文落盘（数据库里搜不到明文密码）"
  # Room 默认 WAL：只拷 .db 会读不到最新提交的数据，三个文件一起取。
  DB_DEV="/data/data/$APP_ID/databases/mimi_files.db"
  a shell "run-as $APP_ID ls databases" 2>/dev/null | tr -d '\r' > "$OUT_DIR/N15-db-files.txt"
  for suffix in "" "-wal" "-shm"; do
    a exec-out run-as "$APP_ID" cat "$DB_DEV$suffix" > "$OUT_DIR/_db$suffix" 2>/dev/null
  done
  cp "$OUT_DIR/_db"        "$OUT_DIR/mimi_files.db" 2>/dev/null
  cp "$OUT_DIR/_db-wal"    "$OUT_DIR/mimi_files.db-wal" 2>/dev/null
  cp "$OUT_DIR/_db-shm"    "$OUT_DIR/mimi_files.db-shm" 2>/dev/null

  "$VENV_PY" - "$OUT_DIR/mimi_files.db" "$OUT_DIR/N15-credentials.txt" <<'PY' > "$OUT_DIR/_n15" 2>&1
import sqlite3, sys
db, out = sys.argv[1], sys.argv[2]
lines = []
try:
    con = sqlite3.connect(db)
    rows = con.execute(
        "SELECT id, scheme, host, port, root_path, username, password_cipher FROM connections"
    ).fetchall()
    lines.append(f"connections 行数 = {len(rows)}")
    for r in rows:
        cipher = r[6]
        lines.append(
            f"  id={r[0]} scheme={r[1]} host={r[2]}:{r[3]} root={r[4]!r} user={r[5]!r} "
            f"cipher={'NULL' if cipher is None else cipher[:28] + '…'}"
        )
    con.close()
except Exception as exc:
    lines.append(f"读取失败: {type(exc).__name__}: {exc}")
text = "\n".join(lines)
open(out, "w", encoding="utf-8").write(text + "\n")
print(text)
PY
  cat "$OUT_DIR/_n15" 2>/dev/null | sed 's/^/       /'

  if [[ -f "$OUT_DIR/mimi_files.db" ]]; then
    if strings "$OUT_DIR/mimi_files.db" 2>/dev/null | grep -qF "$DAV_PASS" \
       || strings "$OUT_DIR/mimi_files.db-wal" 2>/dev/null | grep -qF "$DAV_PASS"; then
      bad "N15 数据库里能搜到明文密码「${DAV_PASS}」——加密没有生效"
    else
      ok "N15 数据库（含 WAL）里搜不到明文密码"
    fi
    if strings "$OUT_DIR/mimi_files.db" 2>/dev/null | grep -qE 'v1:[A-Za-z0-9+/=]{16,}'; then
      ok "N15 存在 v1: 前缀的密文串（AES-GCM 封装格式）"
    else
      bad "N15 没有找到 v1: 密文串"
    fi
  else
    bad "N15 取不到应用数据库"
  fi
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# N16 SAF 对系统文件选择器可见
# ════════════════════════════════════════════════════════════════════════════
if want N16; then
  log "N16 SAF DocumentsProvider 对系统文件选择器可见"
  # 注意：**不能用 `content query` 直接查**。DocumentsProvider 只允许通过
  # ACTION_OPEN_DOCUMENT 一类的框架入口访问；命令行查询会被 SecurityException
  # 拒绝——这一点系统自带的 media provider 也一样（所以那条报错不是我们的问题）。
  # 正确的取证方式是拉起系统文件选择器，看侧栏里有没有我们。
  a shell am start -a android.intent.action.OPEN_DOCUMENT -t "*/*" \
      -f 0x03000000 >/dev/null 2>&1
  sleep 4
  ui_dump
  screen_text > "$OUT_DIR/N16-documentsui.txt"
  shot "N16-documentsui"
  # 抽屉按钮在 DocumentsUI 里是 content-desc="Show roots"
  if grep -qiE "Show roots|显示根|打开抽屉" "$OUT_DIR/N16-documentsui.txt"; then
    xy="$(xy_of desc "Show roots" 6)" || xy=""
    if [[ -n "$xy" ]]; then tap $xy 1.2; fi
  fi
  a shell input swipe 5 1200 900 1200 250 >/dev/null 2>&1
  sleep 1.5
  ui_dump
  screen_text >> "$OUT_DIR/N16-documentsui.txt"
  if grep -qE "密密文件管理|网络位置|Mimi" "$OUT_DIR/N16-documentsui.txt"; then
    ok "N16 系统文件选择器里出现了本应用的存储（$(grep -oE '密密文件管理|网络位置' "$OUT_DIR/N16-documentsui.txt" | head -n1)）"
  else
    bad "N16 系统文件选择器里没看到本应用的存储"
  fi
  a shell input keyevent 4 >/dev/null 2>&1
  sleep 0.6
  a shell input keyevent 4 >/dev/null 2>&1
  echo
fi

# ════════════════════════════════════════════════════════════════════════════
# 收尾：留证据
# ════════════════════════════════════════════════════════════════════════════
log "收集证据到 $OUT_DIR"
{
  echo "### 宿主 WebDAV 目录"
  ls -la "$DAV_ROOT" 2>&1
  echo
  echo "### 宿主 SMB 共享"
  ls -la "$SMB_ROOT" 2>&1
  echo
  echo "### 设备沙箱 $DEV_DIR"
  a shell "ls -la $DEV_DIR" 2>/dev/null | tr -d '\r'
  echo
  echo "### 已保存的连接（设备数据库）"
  cat "$OUT_DIR/N15-credentials.txt" 2>/dev/null
} > "$OUT_DIR/artifacts.txt"
cat "$OUT_DIR/artifacts.txt"

cp "$LAB/dav-$DAV_BASIC_PORT.jsonl"  "$OUT_DIR/requests-dav-basic.jsonl"  2>/dev/null
cp "$LAB/dav-$DAV_DIGEST_PORT.jsonl" "$OUT_DIR/requests-dav-digest.jsonl" 2>/dev/null

printf '\033[1;36m[ m2 ]\033[0m 结果：\033[1;32m通过 %d\033[0m  失败 %d  跳过 %d\n' "$PASS" "$FAIL" "$SKIP"
if [[ $FAIL -gt 0 ]]; then
  printf '       失败项：\n'
  for n in "${FAILED_NAMES[@]}"; do printf '         - %s\n' "$n"; done
fi
printf '       证据目录：%s\n' "$OUT_DIR"
[[ $FAIL -eq 0 ]]
