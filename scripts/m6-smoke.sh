#!/usr/bin/env bash
# ==========================================================================
# M6 · 三个真机反馈 bug 的端到端验证（**只跑 release 包**）
#
# ## 为什么必须 release
# 见 `m51-smb-smoke.sh` 文件头：debug 不混淆不裁剪，R8 剪断整条功能链的
# 问题在 debug 上完全看不出来。本轮三个 bug 里有两个（复制粘贴、APK 安装）
# 都横跨 `:feature:browser` / `:core:preview`，没有理由赌一把。
#
# ## release 包的两个可见后果（其中一条上一版写错了，这一版改正）
#
# ① **没有 logcat**。`MimiLog.mirrorToLogcat = BuildConfig.DEBUG`，release 恒为 false。
#    所以任何"靠日志判红绿"的判据在 release 上都是**必然红**——A1 原来就是这么错的，
#    现在改判"产物"（见下面 `ensure_root` 那一段）。
#
# ② **`am start -n <pkg>/<原类名>` 其实**可以**用** —— 这一条上一版写反了。
#    原注释说"R8 会改写类名，`am start` 一定失败"，实测**不是**：
#    `app/build/outputs/mapping/release/mapping.txt` 里四个 Activity 全是**同名映射**
#    （`…SplashActivity -> …SplashActivity:`），因为 AGP 会把清单里的组件类
#    以 `-keep` 形式喂给 R8（`aapt_rules.txt`）。被改名的只有**其余**类
#    （例如 `net.engio.mbassy.bus.error.MessageBusException -> r9.c`）。
#    结论：入口仍然可以用 `am start -n` 精确拉起；**但**任何"按类名字符串跳转"的
#    自家代码（`FeatureRoutes` 那种）必须靠清单/keep 保住，不能想当然。
#    → 写反的代价是"以为必须绕远路"，所以这条纠正记在这里，别再改回去。
#
# ## 断言两条铁律（都是前面几轮踩出来的）
#  ① **文本判定一律交给 python，不用 grep**：界面文案是中文，而本机 shell 的
#     `grep` 在非 UTF-8 locale 下会漏匹配（M5.1 场景 B 实测：dump 里明明有那串字，
#     `grep -q` 却返回不匹配）。判据本身出错比漏检更糟。
#  ② **先拿到"修复前会失败"的证据**，再报"修复后通过"：
#     · 用例 A 的 A1 —— 修复前点击 APK 是"把远端 `content://` 直接交给安装器"，
#       `external-staging/` 下**不会**出现那个文件，所以 A1 在旧代码上必然是红的；
#     · 用例 B 的 B2 —— 修复前根本没有进度对话框，本应用窗口数恒为 1，B2 必红。
#     两条都是"旧代码上必然变红"的判据，符合方案 §12.1 的规矩。
#  ③ **判据不能用会被"正在刷新的界面"打垮的通道**（M6 新增）：
#     搬运期间进度条每 256 KiB 刷一次，`uiautomator dump` 取不到 idle 状态、
#     且失败时**不覆盖**目标文件 → 读到的是上一轮的界面，还返回成功。
#     所以"对话框在不在"这类判据走 `dumpsys window`（见 `app_window_count`）。
#
# ## 用法
#   scripts/m6-smoke.sh [--case all|copy|install] [--port 8460]
#
#   包名默认取 release（`com.miyuetech.mimifiles`）；`MIMI_APP_ID=…​.debug` 仅用于排障。
#   注意夹具包会**跟着包名一起换**（见 `ensure_fixtures`），否则 A3 必然假红。
#
#   前置：本机 SMB 服务端在跑，且**与本次要验的变体同源**的 APK 已经构建过
#   （release 验收前必须先跑一次 `./gradlew :app:assembleRelease`）。
#   共享目录与两个夹具由本脚本**自动准备**（见 `ensure_fixtures`）：
#     ${PY} scripts/m51-smb-server.py 8460 true mimi testpass123 SHARE /tmp/m6-smbshare
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="${HOME}/.cmfa-toolchain/android-sdk/platform-tools:${PATH}"
ADB="${ADB:-adb}"
# ⚠️ release 的 applicationId **没有** `.debug` 后缀。
# 允许用环境变量覆盖，是为了**排障**时能临时切到 debug 包看 logcat
# （release 的 `MimiLog.mirrorToLogcat` 是 false，出问题时一条日志都没有）。
# 正式验收必须用默认值跑一遍。
APP_ID="${MIMI_APP_ID:-com.miyuetech.mimifiles}"

PY="${HOME}/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="${PROJECT_DIR}/scripts/m2-ui.py"
IME="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

# 变量名必须带 SMB_ 前缀：`USERNAME` / `HOST` 这类名字**在 shell 环境里本来就存在**，
# `${USERNAME:-mimi}` 会取到宿主机的登录名，于是脚本以为在测 A、实际发出的是 B（M4.1 踩过）。
SMB_HOST="${SMB_HOST:-10.0.2.2}"
SMB_PORT="${SMB_PORT:-8460}"
SMB_SHARE="${SMB_SHARE-SHARE}"
SMB_USER="${SMB_USER-mimi}"
SMB_PASS="${SMB_PASS-testpass123}"

# 「抽屉真的开了」的锚点文案（`open_drawer` 用）：必须是**只存在于侧栏**的字符串。
# 选「无线分享」：它只在抽屉的分享入口行上，主界面与工具栏都不会出现。
# 改抽屉布局时若动到这一行，记得同步这里 —— 锚点失效的表现是
# `open_drawer` 连点三次「存储位置」后仍然报"侧栏打不开"。
DRAWER_ANCHOR="${DRAWER_ANCHOR:-无线分享}"

# ⚠️ 夹具名**不带变体信息**，因为夹具的**来源**随 `APP_ID` 走（见 `ensure_fixtures`）。
#
# 上一版这里叫 `mimi-test-21mb.apk`、且恒取 **debug** 包 —— 那是个会产出假红的组合：
# debug 包的 applicationId 是 `…​.debug`，而正式验收盯的是 `…`（release）的
# `lastUpdateTime`。装的是 A 包、看的是 B 包，A3 永远红，红得还像"安装失败"。
# 名字里写死"21mb"也一样有害：release 包只有 ~6 MB，名字会开始撒谎。
APK_NAME="mimi-under-test.apk"
APK_BYTES=0
# ⚠️ 大文件是 **240 MB 而不是 60 MB**，这是判据时序逼出来的，不是随手定的。
#
# 用例 B 要在**搬运仍在进行**的时候把对话框收起来，并证明"收起 ≠ 取消"。
# 本机 SMB 走的是回环地址，第二次起 `bigfile` 已在宿主机页缓存里，
# 实测 60 MB 只要 5~12 秒就搬完 —— 而 B2（窗口计数）+ 截图 + 返回键 + 6 次为 1 秒的
# 轮询验证加起来就要 ~10 秒，60 MB 会让"搬运还在跑"变成一个**碰运气**的前提。
# 240 MB 把窗口拉到 20~50 秒，判据才是在测功能，而不是在测运气。
BIG_NAME="bigfile-240mb.bin"
BIG_BYTES=$((240 * 1024 * 1024))
SHARE_DIR="/tmp/m6-smbshare"
# 落地点选 Download：它在 `MANAGE_EXTERNAL_STORAGE` 下必定可写，
# 而且不需要先滚列表就能看到（本机存储根目录里排位靠前）。
DEST_DIR="Download"

CASE="all"
SKIP_PREPARE="no"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --case) CASE="$2"; shift 2 ;;
    --port) SMB_PORT="$2"; shift 2 ;;
    --no-prepare) SKIP_PREPARE="yes"; shift ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

a() { "${ADB}" "$@"; }

TAG="m6"
XML="/tmp/${TAG}.xml"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

# ⚠️ **先删远端文件再 dump**，这是这一版修掉的一个静默失效。
#
# `uiautomator dump` 失败时（界面持续刷新、取不到 idle 状态）**不会覆盖**目标文件。
# 旧实现只看"本地 XML 非空"，于是 dump 一失败就把**上一轮**读到的界面当成这一轮的，
# 而且返回值还是 0（"成功"）。M6 取证里抓到了实证：连打三轮的 XML
# 与粘贴**之前**那份溢出菜单 dump 的 md5 完全一致，而截图显示菜单早已关掉
# （`/tmp/m6-b2-evidence/ui-p1.xml` ≡ `ui-p2.xml` ≡ `ui-04-menu.xml`）。
# 删掉远端文件之后，"dump 失败"退化为"文件不存在"→ 返回 1，不再可能读到陈旧内容。
ui_dump() {
  a shell rm -f /sdcard/"${TAG}".xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/"${TAG}".xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/"${TAG}".xml > "${XML}" 2>/dev/null
  [[ -s "${XML}" ]]
}

# 本应用当前在窗口管理器里占用的 **DocumentsActivity** 窗口个数。
# `1` = 只有主界面；`2` = 主界面 + 搬运进度对话框（`AlertDialog` 是独立窗口，
# 它的窗口名与主界面**同名**，只是 token 不同，所以按 token 去重后计数即可）。
#
# ## 为什么进度对话框的"存在性"判据用这个，而不是 `uiautomator dump`
# 两条独立的原因叠在一起，实测后果就是"对话框明明在屏上、dump 却连续三轮读到菜单"：
#  ① 进度条每 256 KiB 刷新一次，传输期间界面**从不进入 idle**，`uiautomator dump`
#     取不到稳定状态（失败后还会留下陈旧文件，见 `ui_dump` 的注释）；
#  ② `uiautomator dump` 只挑**一个**"活动窗口"，溢出菜单（`ListPopupWindow`）
#     还未散场时它挑的是菜单，而不是 Dialog。
# 窗口管理器与界面刷新频率无关，而且一次列出**所有**窗口——这里是唯一稳的信号。
app_window_count() {
  a shell dumpsys window windows 2>/dev/null | "${PY}" -c '
import sys, re
pat = re.compile(r"Window\{([0-9a-f]+) u\d+ ([\w./]+)\}")
seen = set()
for line in sys.stdin:
    m = pat.search(line)
    if m and m.group(2).endswith("feature.browser.DocumentsActivity"):
        seen.add(m.group(1))
print(len(seen))
'
}

# 传输进度通知（channel = `mimi_transfer`）在不在。
#
# 这是"搬运还在跑"的**唯一**进程外证据：界面与对话框都会被人为收起/结束，
# 只有前台服务的这条常驻通知与 `TransferCenter` 的非空状态同生共死。
# 用例 B 的 B3 靠它把「隐藏成功」与「搬运自己结束了」区分开——
# 这两者都会让窗口数回到 1，只数窗口必然误判为通过。
has_transfer_notification() {
  a shell dumpsys notification --noredact 2>/dev/null | "${PY}" -c '
import sys
sys.exit(0 if "mimi_transfer" in sys.stdin.read() else 1)'
}

ui_text() { ui_dump || return 1; "${PY}" "${UI_SCRIPT}" text-of "${XML}" "id=$1" 2>/dev/null | head -n1; }
ui_xy()   { ui_dump || return 1; "${PY}" "${UI_SCRIPT}" center  "${XML}" "$1" 2>/dev/null | head -n1; }

tap_xy() { a shell input tap "$1" "$2" >/dev/null 2>&1; }

# 点一个用 `<key>=<value>` 描述的控件；找不到就报错（返回 1）。
tap_sel() {
  local xy
  xy="$(ui_xy "$1")" || { echo "  ✗ 找不到控件 $1"; return 1; }
  tap_xy "$(printf '%s' "${xy}" | awk '{print $1}')" "$(printf '%s' "${xy}" | awk '{print $2}')"
}

# 搬运进行中时 `uiautomator dump` 会间歇性失败（进度条让界面进不了 idle，
# 实测原始输出：`ERROR: could not get idle state.` / `ERROR: null root node
# returned by UiTestAutomationBridge.`，且失败时**不写文件**）。
# 调用方因此必须**自己**写重试循环（见用例 B 的 B3）——这里不再包一层通用重试：
# 通用重试会把"重试多少次、每次隔多久"从判据里藏起来，而这一段恰恰是
# "搬运只有 ~30 秒、dump 一次要 1.5~3 秒"的时间预算问题，藏起来只会重蹈覆辙。

# 长按：`input swipe` 起止同点并给一个较长时长，就是长按。
long_press_sel() {
  local xy x y
  xy="$(ui_xy "$1")" || { echo "  ✗ 找不到控件 $1"; return 1; }
  x="$(printf '%s' "${xy}" | awk '{print $1}')"
  y="$(printf '%s' "${xy}" | awk '{print $2}')"
  a shell input swipe "${x}" "${y}" "${x}" "${y}" 900 >/dev/null 2>&1
}

# 当前屏幕上是否出现过某个**子串**（在整份 dump 里找，不限于可见控件）。
has_text() {
  ui_dump || return 1
  "${PY}" - "${XML}" "$1" <<'PYEOF'
import sys
xml, needle = sys.argv[1], sys.argv[2]
data = open(xml, encoding="utf-8", errors="replace").read()
sys.exit(0 if needle in data else 1)
PYEOF
}

# ══════════════════════════════════════════════════════════════════════════
# A1 的取证通道（M6 第二轮改掉）
#
# A1 要回答的是**一个问题**：交给系统安装器的，到底是不是一个刚落好的本地文件。
#
# ## 为什么不能只用日志（上一版的做法，已废）
# 上一版判的是 logcat 里的 `MimiPreview: 已暂存：…`。它有两个毛病，第二个是致命的：
#  ① 隔了一层。日志证明的是"代码走到了那一行"，不是"文件真在磁盘上、大小对得上"。
#  ② **它只在 debug 包上存在**。`MimiLog.mirrorToLogcat = BuildConfig.DEBUG`，
#     release 上恒为 false —— 而本脚本的默认值与本项目的验收要求**都是 release**。
#     拿它当判据，release 上必然读 0 条、A1 必然红，而红的原因是"判据读错了通道"。
#     这正是方案 §12.3 第三类（判据自身出错）里最贵的一种：它让人去改一段好代码。
#
# ## 换成直接看**产物**
# 落盘路径是 `<cacheDir>/external-staging/<内容指纹>-<原文件名>`（见 `PreviewCache`），
# 于是判据变成：应用私有目录下出现一个
#   · 文件名以 `-<原文件名>` 结尾、
#   · 大小**等于远端原文件**、
#   · mtime **不早于点击时刻**
# 的文件。这一条与构建类型无关、与耗时无关、与缓存命中与否无关，
# 是**产物级**证据，比"代码执行过某一行"强。
#
# ## 代价：读 /data/data 需要 root
# `shell` 用户读不到别的应用的私有数据目录。模拟器是 userdebug，两条路可用：
# 先试 `su 0`（**不改 adbd 身份**，因而对后面的 UI 自动化零影响），
# 再退到 `adb root`（会重启 adbd）。两条都不通时才放弃文件通道，
# 并且**明确打印用的是哪一条** —— 判据用哪条通道取证，读结果的人必须知道。
#
# 读操作对被测应用零影响；A1 的判据不依赖它是否可用（不可用时退回日志通道）。
FILE_EVIDENCE="no"
ROOT_CMD=""

ensure_root() {
  if [[ "$(a shell su 0 id -u 2>/dev/null | tr -d '\r\n')" == "0" ]]; then
    ROOT_CMD="su 0"; FILE_EVIDENCE="yes"; return 0
  fi
  a root >/dev/null 2>&1
  a wait-for-device >/dev/null 2>&1
  sleep 1
  if [[ "$(a shell id -u 2>/dev/null | tr -d '\r\n')" == "0" ]]; then
    ROOT_CMD=""; FILE_EVIDENCE="yes"; return 0
  fi
  FILE_EVIDENCE="no"
  return 1
}

# 暂存目录里与 $1 同名（后缀匹配）的文件：输出 `<字节> <mtime秒>`；没有就输出空。
#
# ## ⚠️ 这里踩过一个坑，写下来免得下一个人再踩：`adb shell` 会**整体转义**参数
#
# 第一版写的是：
#
#     adb shell ${ROOT_CMD} "stat -c '%s %Y %n' ${dir}/* 2>/dev/null"
#
# 在宿主上看着完全正确，实测**永远返回空**（同一时刻手工 `ls -l` 明明看得见文件）：
#   · 引号与通配符都被 adb 当成"要传给设备侧 shell 的普通字符"整体转义了
#     （`adb shell su 0 ls /data/.../cache/*` 在设备上报的错是
#      `ls: /data/.../cache/*: No such file or directory` —— 通配符根本没展开）；
#   · `stat -c '%s %Y %n'` 于是变成 `stat -c %s %Y %n <字面星号>`，`stat` 自己报错，
#     而 `2>/dev/null` 把错误吞得干干净净。
# 后果：A1 在**修复生效**的情况下报红，还写着"修复未生效"——方案 §12.3 那类
# "判据自身出错"里最贵的一种，它让人去改一段好代码。
# 那一轮是 A2（点击后 9 秒起安装器 = 真的先落地了）把它顶回来的。
#
# 现在的写法只用**不需要引号也不需要通配符**的形式：先 `ls` 拿文件名，再对
# 每个完整路径单独 `stat`。多两次 adb 往返，换掉一整类静默失效。
staged_stat() { # $1 = 原文件名
  [[ "${FILE_EVIDENCE}" == "yes" ]] || return 0
  local dir="/data/data/${APP_ID}/cache/external-staging"
  local names name size mtime
  names="$(a shell ${ROOT_CMD} ls "${dir}" 2>/dev/null | tr -d '\r')"
  [[ -n "${names}" ]] || return 0
  while IFS= read -r name; do
    [[ "${name}" == *"-${1}" ]] || continue
    size="$(a shell ${ROOT_CMD} stat -c %s "${dir}/${name}" 2>/dev/null | tr -d '\r\n')"
    mtime="$(a shell ${ROOT_CMD} stat -c %Y "${dir}/${name}" 2>/dev/null | tr -d '\r\n')"
    [[ -n "${size}" && -n "${mtime}" ]] || continue
    printf '%s %s\n' "${size}" "${mtime}"
    return 0
  done <<< "${names}"
}

# 暂存产物是否已就绪：大小 == 远端原文件，且 mtime 不早于 $1（设备侧 epoch 秒）。
# `prepare()` 里的 `pm clear` 已经保证起点是空的，这条 mtime 判断是第二重保险
# （防止"上一轮留下的同名文件"被当成本轮证据 —— 那会是一条永远绿的假判据）。
staged_ok() { # $1 = 点击时刻（设备侧 epoch 秒）
  local st size mtime
  st="$(staged_stat "${APK_NAME}")"
  [[ -n "${st}" ]] || return 1
  size="${st%% *}"; mtime="${st##* }"
  [[ "${size}" == "${APK_BYTES}" ]] || return 1
  [[ -z "$1" || "${mtime}" -ge "$1" ]] || return 1
  return 0
}

# 「已暂存」这条日志（**只有 debug 包有**）—— 只作旁证打印，不再决定红绿。
staged_evidence() {
  a logcat -d -s MimiPreview 2>/dev/null | "${PY}" -c '
import sys
lines = [l.rstrip() for l in sys.stdin if "已暂存" in l]
print(len(lines))
if lines:
    print(lines[-1].strip())
'
}
stage_count() { staged_evidence | head -n1; }
stage_line()  { staged_evidence | tail -n +2 | head -n1; }

# 当前前台 Activity 的完整类名。
top_activity() {
  a shell dumpsys activity activities 2>/dev/null | "${PY}" -c '
import sys, re
for line in sys.stdin:
    m = re.search(r"topResumedActivity=\S+\s+\S+\s+([\w./]+)", line)
    if m:
        print(m.group(1))
        break
'
}

# 我们自己包的 lastUpdateTime —— 用来判"安装真的完成了"。
#
# ⚠️ 必须取**整行的值**（`2026-10-15 01:17:48`），不能只取第一个空白分隔的词。
#
# 旧实现写的是 `lastUpdateTime=(\S+)`，于是拿到的是 `2026-10-15` —— **只有日期**。
# 而安装前后都在同一天时，两个值**完全相同**，`now != before` 永远不成立，
# 判据必然报"没有装完"。M6 实测就是这样：安装其实成功了
# （`installer` 页面被点掉、我们的应用被"打开"按钮拉了回来、包时间戳也确实变了），
# 而 A3 一路红着 —— 又一次"判据自身出错"，又一次把人往"功能坏了"引。
pkg_last_update() {
  a shell dumpsys package "${APP_ID}" 2>/dev/null | "${PY}" -c '
import sys, re
for line in sys.stdin:
    m = re.search(r"lastUpdateTime=(.+)$", line.strip())
    if m:
        print(m.group(1).strip())
        break
'
}

# 等待前台出现安装器；成功返回 0。$1 = 超时秒数
wait_installer() {
  local deadline=$((SECONDS + $1))
  while (( SECONDS < deadline )); do
    case "$(top_activity)" in
      *packageinstaller*) return 0 ;;
    esac
    sleep 2
  done
  return 1
}

pkg_ready() { [[ -n "$(pkg_last_update)" ]]; }

# 等到**文件浏览界面真的成为前台**，而不是"睡固定的几秒"。
#
# ## 为什么必须是轮询而不是 sleep
# `enter_share_dir` 的第一件事是发 BACK 键。如果此刻应用还停在 `SplashActivity`，
# 那个 BACK 会**把应用关掉**，之后每一次 `tap_sel` 都落在桌面上 ——
# 现象是"所有用例一起红"，看起来像功能全崩，实际是用例起手就错了。
# 而"启动要多久"这件事没有一个可靠的常数：`pm clear` 后的冷启动、R8 包、
# 模拟器当前的负载都在影响它（M6 实测：`--no-prepare` 分支里 8 秒不够，
# 完整流程里因为多两次「More options」尝试凑够了 ~12 秒才没暴露）。
#
# 所以判据换成**观察得到的事实**：前台是本应用的某个**主页面**。
#
# ## 为什么不能只认 `DocumentsActivity`（release 全量跑时抓到的判据缺陷）
# `wait_browser` 原来是"前台必须是 DocumentsActivity"。但**「添加 SMB 连接」
# 流程走完时应用就停在 `ConnectionsActivity`**（连接列表页），此时调用
# `am start …/SplashActivity`，系统会把 intent 投递给**顶层实例**
# （实测输出：`Warning: Activity not started, intent has been delivered to
# currently running top-most instance`），应用原地不动 —— 前台永远是
# ConnectionsActivity，按旧判据"启动"永远失败，恢复循环烧光 5 次重试，
# 表现是 B0/A0 一起红，而应用其实活得好好的（截图与 `mCurrentFocus` 都能看到
# 连接列表页）。判据必须回答"应用在不在前台"，而不是"在哪个页面"。
#
# 仍然**排除** `SplashActivity`：它还可能只是一个透明的转发页，
# 这时 `enter_share_dir` 起手的 BACK 会把应用整个关掉。
wait_browser() { # $1 = 超时秒数
  local deadline=$((SECONDS + $1))
  while (( SECONDS < deadline )); do
    case "$(top_activity)" in
      *feature.browser.DocumentsActivity) return 0 ;;
      *feature.connection.ConnectionsActivity) return 0 ;;
    esac
    sleep 1
  done
  return 1
}

# 进 SMB 目录，并**确认真的进去了**。
#
# ## 为什么必须确认（M6 实测踩到三次）
# 旧实现是 `tap_sel "text=${SMB_HOST}" || return 1; sleep 9` —— 点完就睡，不验证。
# 抽屉里那一行显示的是 **`10.0.2.2:8460/SHARE`**，而这里按 `10.0.2.2` 匹配（子串匹配），
# 于是它也会命中，但"命中"不等于"点进去了"：抽屉动画没结束、点击落在遮罩上、
# 或者列表还没加载完，都会退化成一个**空目录**。
# 后果不是"这条用例红"，而是**后面某条用例红**，报的是
# 「A0/B1 看不到 xxx.bin」—— 看起来像"文件不见了"，而不是"没进目录"。
# 这正是方案 §12.3 说的判据自身出错。
#
# ## 判据选"期望的那个文件在不在"
# 调用方本来就要用某个夹具，所以直接拿它当"我到了"的证据 ——
# 这比"面包屑里有没有 SHARE"更贴近用例的语义，也顺带把"列表还没加载完"
# 这个时间因素变成了重试而不是固定 sleep。
#
# ## M6 第二轮补的三件事（`--case all` 上实测红过一次，A0 连烧五轮）
# 那一轮 A 组整段起不来，报的是「A0 进不了 SMB 目录」，而单独跑 `--case install`
# 一次就过 —— 差别是**用例 B 结束时停在 `Download` 里**，比 A 单独起手深一层。
# 三处不够结实的地方：
#  ① **只按一次 BACK**。从 Download 只退到「内部存储」，而从卷根退一次会到「位置」首页；
#     调用点的深度在流程里不固定，"固定次数"必然在某一侧出错。
#     现在按重试次数递增，并且在起手先确认**应用还活着**（BACK 打过头会把应用退掉，
#     那之后每次点击都落在桌面上，报出来是"所有控件都找不到"——又是一条假红）。
#  ② **点抽屉里那一行只点一次**。界面还在加载时 `uiautomator dump` 取不到 idle，
#     `tap_sel` 会**返回失败而不点击**；旧写法把它 `>/dev/null 2>&1` 吞掉，
#     于是"没点"和"点了没进去"看起来一模一样。现在给 3 次机会并区分这两种情况。
#  ③ **全败时不留现场**。现在把屏幕上的文字打出来 —— 这条比任何日志都直接。
open_drawer() {
  local i
  for i in 1 2 3; do
    # `center` 只认**在屏上**的节点（`m2-ui.py` 的 `visible_nodes`），
    # 而「无线分享」只存在于侧栏里 —— 用它当"抽屉真的开了"的判据。
    # ⚠️ 不能用 `has_text`：抽屉关着时它的内容**仍在 XML 里**（只是被平移出屏），
    # 于是"XML 里有这串字"和"抽屉开着"是两件事。
    if ui_xy "text=${DRAWER_ANCHOR}" >/dev/null 2>&1; then return 0; fi
    tap_sel "desc=存储位置" >/dev/null 2>&1
    sleep 1.5
  done
  ui_xy "text=${DRAWER_ANCHOR}" >/dev/null 2>&1
}

enter_share_dir() { # $1 = 期望在列表里看到的文件名
  local expect="$1"
  local attempt backs i tapped
  for attempt in 1 2 3 4 5; do
    # ① 应用还活着吗？（BACK 打过头会退出应用）
    # 与 wait_browser 同一条教训：「添加连接」走完应用会停在 ConnectionsActivity，
    # 那也是"活得好好的本应用前台"，不能当成掉线去重新拉起 ——
    # am start 会被投递给顶层实例、原地不动，白烧一次重试。
    case "$(top_activity)" in
      *feature.browser.DocumentsActivity | *feature.connection.ConnectionsActivity) ;;
      *)
        echo "  · 应用不在前台，重新拉起"
        a shell am start -n "${APP_ID}/com.miyuetech.mimifiles.splash.SplashActivity" >/dev/null 2>&1
        wait_browser 25 || { echo "  · 第 ${attempt} 次：应用拉不起来"; continue; }
        ;;
    esac
    # ② 退到"抽屉一定可达"的深度（见上面的 ①）
    backs=$(( attempt <= 2 ? 1 : 2 ))
    for ((i = 0; i < backs; i++)); do
      a shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 0.8
    done
    if ! open_drawer; then
      echo "  · 第 ${attempt} 次：侧栏打不开"
      continue
    fi
    tapped="no"
    for i in 1 2 3; do
      if tap_sel "text=${SMB_HOST}" >/dev/null 2>&1; then tapped="yes"; break; fi
      sleep 2
    done
    if [[ "${tapped}" != "yes" ]]; then
      echo "  · 第 ${attempt} 次：点不动 ${SMB_HOST}（界面未进入 idle，dump 取不到）"
      continue
    fi
    sleep 3
    # ③ 列表是异步加载的：给最多 30 秒（15 次 × 2 秒）让它出来。
    for i in $(seq 1 15); do
      if ui_dump && "${PY}" "${UI_SCRIPT}" center "${XML}" "text=${expect}" >/dev/null 2>&1; then
        return 0
      fi
      sleep 2
    done
    echo "  · 第 ${attempt} 次没进到 SMB 目录，重试"
  done
  local screen
  screen="$(ui_dump && "${PY}" "${UI_SCRIPT}" dump-text "${XML}" | tr '\n' ' ' | head -c 400)"
  echo "  · 进不去 SMB 目录；当前屏幕：${screen:-（连屏幕都读不到）}"
  return 1
}

# ── 夹具 ────────────────────────────────────────────────────────────────
#
# 把夹具的生成放进脚本里，而不是写成"前置步骤"让人自己造。理由是本轮踩到的：
# 夹具是**判据的一部分**（大小决定了搬运时长，而时长决定了判据能不能成立），
# 写在文档里的前置条件没人会因为"跑不通"去核对，写在代码里就跑不掉。
#
# `.bin` 用固定种子的随机字节：可压缩内容会让"文件真的搬完了"这件事看起来更快
# （服务端/客户端的任何一层压缩都会改变实际耗时），固定种子保证每一轮一样。
ensure_fixtures() {
  if [[ -f "${SHARE_DIR}/${BIG_NAME}" && "$(wc -c < "${SHARE_DIR}/${BIG_NAME}" | tr -d ' ')" == "${BIG_BYTES}" ]]; then
    echo "  · 大文件夹具已存在（${BIG_NAME}）"
  else
    echo "  · 生成大文件夹具 ${BIG_NAME}（${BIG_BYTES} 字节）…"
    mkdir -p "${SHARE_DIR}"
    rm -f "${SHARE_DIR}/${BIG_NAME}"
    "${PY}" - "${SHARE_DIR}/${BIG_NAME}" "${BIG_BYTES}" <<'PYEOF'
import random, sys
path, size = sys.argv[1], int(sys.argv[2])
with open(path, "wb") as fh:
    rng = random.Random(1)
    left = size
    while left > 0:
        chunk = min(left, 1 << 20)
        fh.write(rng.randbytes(chunk))
        left -= chunk
PYEOF
  fi

  # 安装包夹具：用**当前工程真实产出、且与本次要验的包同源**的那个 APK。
  #
  # 为什么必须同源：A3 判的是 `APP_ID` 的 `lastUpdateTime` 有没有变。
  # debug 与 release 是**两个不同的包名**（`…​.debug` / `…`），
  # 拿 debug 包去装、却盯着 release 的记录，A3 会永远红 —— 而且红得像"安装失败"。
  # 所以这里按 `APP_ID` 挑源包，夹具名保持中性。
  if [[ "${APP_ID}" == *.debug ]]; then
    apk_src="${PROJECT_DIR}/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
  else
    apk_src="${PROJECT_DIR}/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
  fi
  if [[ ! -f "${apk_src}" ]]; then
    echo "  ✗ 缺少 ${apk_src} —— 先跑一次对应的 assemble 任务（验收要求 release）"
    return 1
  fi
  if [[ ! -f "${SHARE_DIR}/${APK_NAME}" || "${apk_src}" -nt "${SHARE_DIR}/${APK_NAME}" ]]; then
    cp -f "${apk_src}" "${SHARE_DIR}/${APK_NAME}"
    echo "  · 已用 ${apk_src##*/} 生成夹具 ${APK_NAME}"
  else
    echo "  · 安装包夹具已存在（${APK_NAME}）"
  fi
  APK_BYTES="$(wc -c < "${SHARE_DIR}/${APK_NAME}" | tr -d ' ')"
  # 上一版留下的那个名字带着"21mb"的旧夹具清掉：它既不是本轮要验的包，
  # 也会在 A0 的列表判据旁边多出一行让人误判的东西。
  rm -f "${SHARE_DIR}/mimi-test-21mb.apk"
  return 0
}

# ── 环境准备 ────────────────────────────────────────────────────────────
prepare() {
  echo "── 环境准备 ──"
  # Gboard 的自动纠错会把 `SHARE` 补成 `SHARED`（M5.1 踩过），必须先禁。
  a shell ime disable "${IME}" >/dev/null 2>&1
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  # 清数据 → 每轮从"零连接"开始，避免上一轮的连接影响判据。
  a shell pm clear "${APP_ID}" >/dev/null 2>&1
  # clear 会丢掉 MANAGE_EXTERNAL_STORAGE，不补回来应用会停在授权引导页。
  a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  # 安装未知来源：预放行，免得安装流程被"是否允许来自此来源"拦住而误判成失败。
  a shell appops set "${APP_ID}" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
  # 清掉上一轮留下的目标文件。**这是我们自己造的测试数据**（`ensure_fixtures` 造出来），
  # 留着的话"保留两者"策略会把名字改成 `bigfile-240mb (1).bin`，
  # 让"文件名精确出现"的判据在第二轮悄悄失灵。
  a shell rm -f "/sdcard/Download/${BIG_NAME}" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  echo "  · 已禁输入法 / 清数据 / 放行存储与安装权限 / 清理上一轮产物"
}

add_smb_connection() {
  echo "── 添加 SMB 连接 smb://${SMB_HOST}:${SMB_PORT}/${SMB_SHARE} ──"
  # ⚠️ 用**完整类名**，不能写 `$APP_ID/.splash.SplashActivity`。
  # 那一串里的 `.splash.SplashActivity` 是相对**包的 namespace**（`com.miyuetech.mimifiles`）
  # 展开的，而不是相对 applicationId；debug 变体的 applicationId 是
  # `com.miyuetech.mimifiles.debug`，两者不一致时 `am start` 会静默找不到组件，
  # 表现是"脚本点不到任何控件，而应用根本没起来"。
  a shell am start -n "${APP_ID}/com.miyuetech.mimifiles.splash.SplashActivity" >/dev/null 2>&1
  # 用 `wait_browser` 而不是固定 sleep：这里原来是 `sleep 8`，之所以一直没出事，
  # 只是因为下面那两次「More options」尝试（含 4 秒重试）恰好把总等待凑到了 ~12 秒。
  # 那是**巧合**，不是余量 —— 一旦设备更慢或包更大，第一个 BACK 就会把应用关掉。
  wait_browser 30 || { echo "  ✗ 应用启动后 30 秒内没进浏览界面"; return 1; }
  # `pm clear` 之后的**第一次冷启动**要重建数据库、重新探测权限档位，
  # debug 包又不做 AOT，实测比 release 慢一截。所以这里给两次机会，
  # 而不是"一次点不到就判失败"——那会把启动慢误判成 UI 路径变了。
  tap_sel "desc=More options" || { sleep 4; tap_sel "desc=More options"; } || return 1
  sleep 1.5
  tap_sel "text=网络连接"      || return 1; sleep 2.5
  tap_sel "id=fab_add"         || return 1; sleep 2.5
  fill input_host     "${SMB_HOST}"  || return 1
  fill input_port     "${SMB_PORT}"  || return 1
  fill input_share    "${SMB_SHARE}" || return 1
  fill input_username "${SMB_USER}"  || return 1
  fill input_password "${SMB_PASS}"  || return 1
  tap_sel "id=btn_save" || return 1; sleep 3
  echo "  · 连接已保存"
  return 0
}

fill() { # $1=控件 id  $2=内容
  local xy x y got
  xy="$(ui_xy "id=$1")" || { echo "  ✗ 找不到 $1"; return 1; }
  x="$(printf '%s' "${xy}" | awk '{print $1}')"
  y="$(printf '%s' "${xy}" | awk '{print $2}')"
  tap_xy "${x}" "${y}"; sleep 0.5
  a shell input keyevent --longpress KEYCODE_DEL >/dev/null 2>&1
  a shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    a shell input keyevent KEYCODE_DEL >/dev/null 2>&1
  done
  a shell input text "$2" >/dev/null 2>&1
  sleep 0.5
  got="$(ui_text "$1")"
  if [[ "${got}" == "$2" ]]; then
    echo "  ✓ $1 = ${got}"
    return 0
  fi
  echo "  ✗ $1 期望 [$2]，实际 [${got}]"
  return 1
}

# ══════════════════════════════════════════════════════════════════════════
# 用例 B · 复制粘贴进度（bug #1）
#   B1 SMB 目录里能看到 240 MB 大文件（前置）
#   B2 粘贴后出现**进度对话框**（修复前这里必然是红的 —— 根本没有对话框）
#   B3 对话框里点「隐藏」能收起（用户要求的能力）
#   B4 通知栏有传输进度通知（用户要求的第二个能力）
#   B5 搬运结束后文件真的到位
# ══════════════════════════════════════════════════════════════════════════
case_copy() {
  echo
  echo "──────── B · 复制粘贴进度（bug #1）────────"
  enter_share_dir "${BIG_NAME}" || { bad "B0 进不了 SMB 目录"; return; }

  if has_text "${BIG_NAME}"; then
    ok "B1 SMB 目录里看到 ${BIG_NAME}"
  else
    bad "B1 SMB 目录里看不到 ${BIG_NAME}（列表：$(ui_dump && "${PY}" "${UI_SCRIPT}" dump-text "${XML}" | tr '\n' ' '))"
    return
  fi

  echo "  · 长按 → 复制"
  long_press_sel "text=${BIG_NAME}" || { bad "B1 长按失败"; return; }
  sleep 1.5
  tap_sel "desc=复制" || { bad "B1 找不到「复制」按钮"; return; }
  sleep 1

  echo "  · 导航到本机 ${DEST_DIR} 并粘贴"
  a shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1
  tap_sel "desc=存储位置" || { bad "B1 打不开抽屉"; return; }
  sleep 1.5
  tap_sel "text=内部存储" || { bad "B1 抽屉里没有「内部存储」"; return; }
  sleep 3.5
  tap_sel "text=${DEST_DIR}" || { bad "B1 进不了 ${DEST_DIR}"; return; }
  sleep 3
  tap_sel "desc=More options" || { bad "B1 打不开溢出菜单"; return; }
  sleep 1.2
  tap_sel "text=粘贴" || { bad "B1 溢出菜单里没有「粘贴」（剪贴板丢了？）"; return; }

  # ⚠️ 这一版把 B2 的判据从 `uiautomator dump` 换成了**窗口管理器**，理由是实测出来的，
  # 不是嫌 dump 麻烦：
  #
  #  · 传输期间进度条每 256 KiB 刷一次，界面**从不进入 idle**。原始 dump 输出实证：
  #      [1] ERROR: null root node returned by UiTestAutomationBridge.
  #      [2] ERROR: could not get idle state.
  #      文件: <不存在>
  #    而且 dump 失败时**不覆盖**目标文件 —— 上一版的 `ui_dump` 会把**粘贴前**那份
  #    溢出菜单 dump 当成这一轮的读走，还返回成功（md5 实证：ui-p1 ≡ ui-p2 ≡ ui-04-menu）。
  #  · 即使 dump 成功，它也只挑**一个**"活动窗口"；溢出菜单（`ListPopupWindow`）
  #    还没散场时挑中的是菜单，不是对话框。
  #
  # 结果就是上一版 B2 报"没有进度对话框"，而**同一时刻的截图里对话框完整在屏上**
  # （`/tmp/m6-b2-evidence/screen-p1.png` / `screen-p2.png`）。这是方案 §12.3 说的
  # "判据自身出错" —— 比漏检更糟，因为它会让人去改一段本来就是好的代码。
  #
  # 窗口管理器不受刷新频率影响、且一次列出所有窗口：主界面 1 个 + AlertDialog 1 个 = 2。
  # 反向校验：旧代码（根本没有对话框）这个数恒为 1，B2 必红。
  local found="no"
  for _ in 1 2 3 4 5 6 7 8; do
    if [[ "$(app_window_count)" -ge 2 ]]; then found="yes"; break; fi
    sleep 1
  done
  if [[ "${found}" == "yes" ]]; then
    ok "B2 粘贴后出现进度对话框（本应用窗口数 ≥ 2）"
  else
    bad "B2 粘贴后没有进度对话框（本应用窗口数=$(app_window_count)，期望 ≥ 2）"
    return
  fi

  # ── B3：对话框能收起、而且收起之后搬运继续 ───────────────────────────
  #
  # ⚠️ 这里**不**用 `uiautomator dump` 去点「隐藏」按钮，原因是实测出来的硬事实：
  #   搬运期间 dump **从来不会成功**，一次都不会。
  # 逐次取证（25 轮，见下方数据）显示：
  #   · 搬运进行中的几轮：`ERROR: could not get idle state.` / `ERROR: null root node
  #     returned by UiTestAutomationBridge.` → 文件根本没被写；
  #   · 搬运一结束：立刻 `UI hierchary dumped to: …`，但那时对话框已经收掉了，
  #     读到的是主界面列表。
  # 机理是清楚的：进度条在传输期间持续刷新，界面**永远进不了 idle**，
  # 而 `uiautomator dump` 的默认行为就是"等界面静下来"。
  # 所以"搬运期间的点按"只能走**不依赖无障碍树**的通道。
  #
  # 用返回键收起的正当性（不是"退而求其次"）：
  # 代码里「隐藏」按钮、返回键、点对话框外部**是同一个出口** ——
  # `setNegativeButton(…, null)` 让负按钮走默认的"关闭"行为，
  # `setCanceledOnTouchOutside(true)` 让返回键/外部点击也走关闭，
  # 三者最终都落到同一个 `setOnDismissListener { transferHidden = true }`。
  # （`DocumentsActivity.showTransferDialog` 的注释里写明了为什么刻意这样做。）
  # 「隐藏」按钮**在屏上确实存在**，证据是同一时刻的截图
  # `/tmp/m6-dialog.png`（本轮脚本自己抓的）与 `/tmp/m6-b2-evidence/screen-p1.png`
  # —— 上面「隐藏」「取消」两个按钮与「隐藏后搬运继续…」的说明都清晰可见。
  a shell screencap -p /sdcard/m6-dialog.png >/dev/null 2>&1
  a pull /sdcard/m6-dialog.png /tmp/m6-dialog.png >/dev/null 2>&1
  echo "  · 对话框截图已存 /tmp/m6-dialog.png"

  echo "  · 用返回键收起对话框（与「隐藏」按钮同一个 onDismiss 出口）"
  local notif_before
  if has_transfer_notification; then notif_before="yes"; else notif_before="no"; fi
  a shell input keyevent KEYCODE_BACK >/dev/null 2>&1

  # ⚠️ 判据必须能区分「隐藏成功」与「搬运自己结束了」——**两者都会让窗口数回到 1**，
  # 只看窗口数就是一个必然误报为通过的判据（上一版正是这样）。
  # 所以要求**同时**满足：窗口数 ≤ 1 且传输通知仍在。
  # 「取消」被排除在外也靠它：点了取消会整流收尾，通知随之消失。
  local hidden="no" why=""
  for _ in 1 2 3 4 5 6; do
    local win notif
    win="$(app_window_count)"
    if has_transfer_notification; then notif="yes"; else notif="no"; fi
    if [[ "${win}" -le 1 && "${notif}" == "yes" ]]; then hidden="yes"; break; fi
    why="窗口数=${win} 传输通知=${notif}"
    sleep 1
  done
  if [[ "${hidden}" == "yes" ]]; then
    ok "B3 隐藏生效：对话框已收起，而搬运仍在继续（通知还在 = 「隐藏」≠「取消」）"
  else
    if [[ "${notif_before}" == "no" ]]; then
      bad "B3 无法判定：收起对话框之前搬运就已经结束了（通知本来就不在）——这是用例时序问题，不是功能失败"
    else
      bad "B3 收起对话框后无法证明「搬运仍在继续」（最后一次观测：${why}）"
    fi
    return
  fi

  # 收起之后界面必须**真的能用**（用户的原话是"好继续使用其他功能"）。
  # 传输期间 dump 不工作，此刻对话框已收但搬运还在跑 —— 所以这里同样要重试。
  local usable="no"
  for _ in $(seq 1 10); do
    if ui_dump && [[ -n "$("${PY}" "${UI_SCRIPT}" center "${XML}" "desc=存储位置" 2>/dev/null | head -n1)" ]]; then
      usable="yes"; break
    fi
    sleep 0.3
  done
  if [[ "${usable}" == "yes" ]]; then
    ok "B3 收起后主界面仍可操作（抽屉按钮可定位）"
  else
    bad "B3 收起后主界面读不到（连抽屉按钮都定位不到）"
  fi

  if has_transfer_notification; then
    ok "B4 通知栏里有传输进度通知（channel=mimi_transfer）"
  else
    bad "B4 通知栏里没有传输进度通知"
  fi

  echo "  · 等搬运结束…"
  local deadline=$((SECONDS + 300))
  while (( SECONDS < deadline )); do
    has_transfer_notification || break
    sleep 3
  done
  sleep 2
  # 回到目录再看一眼：列表不会自己刷新，重进一次是需要的（也是用户会做的动作）。
  tap_sel "desc=存储位置" >/dev/null 2>&1; sleep 1
  tap_sel "text=内部存储" >/dev/null 2>&1; sleep 2
  tap_sel "text=${DEST_DIR}" >/dev/null 2>&1; sleep 3
  if has_text "${BIG_NAME}"; then
    ok "B5 240 MB 文件已落到 ${DEST_DIR}"
  else
    bad "B5 ${DEST_DIR} 里没有 ${BIG_NAME}（搬运失败或被取消）"
  fi
}

# ══════════════════════════════════════════════════════════════════════════
# 用例 A · 从 SMB 安装 APK（bug #3）
#   A1 点击后**先落地**：日志里必须出现 `MimiPreview: 已暂存：…`
#      —— 修复前必然是红的（直接把远端 content:// 流交给安装器，不产生这条日志）
#   A2 落地完成后安装器出现
#   A3 在安装器上点确定能真正装完（lastUpdateTime 变化）
#      —— 修复前卡在「正在准备安装应用」，这一条永远是红的
# ══════════════════════════════════════════════════════════════════════════
case_install() {
  echo
  echo "──────── A · 从 SMB 安装 APK（bug #3）────────"
  local before
  before="$(pkg_last_update)"
  pkg_ready || { bad "A0 拿不到 lastUpdateTime，后面判不了"; return; }
  echo "  · 安装前 lastUpdateTime=${before}"

  enter_share_dir "${APK_NAME}" || { bad "A0 进不了 SMB 目录"; return; }
  if ! has_text "${APK_NAME}"; then
    bad "A0 SMB 目录里看不到 ${APK_NAME}"
    return
  fi

  echo "  · 点 ${APK_NAME}"
  local t0=${SECONDS}
  # 点击**之前**取一次设备时钟：A1 要用它做 mtime 下界，两个时间必须同源
  # （宿主与模拟器的时钟差多少不是这里该赌的量）。
  local tap_epoch
  tap_epoch="$(a shell date +%s 2>/dev/null | tr -d '\r\n')"
  tap_sel "text=${APK_NAME}" || { bad "A0 点不动 ${APK_NAME}"; return; }

  # ── A1：证明"先落地"这一步真的发生了 ────────────────────────────────
  #
  # 判据是**产物**：`<cacheDir>/external-staging/<指纹>-<原文件名>` 里出现一个
  #   · 大小与远端原文件**完全相同**（`APK_BYTES`，从宿主侧同一份夹具量出来）、
  #   · mtime 不早于点击时刻
  # 的文件。通道为什么从 logcat 换成"看文件"，见脚本上方 `ensure_root` 那一段
  # （一句话：release 上 `mirrorToLogcat=false`，日志通道根本不存在）。
  #
  # ## 屏幕文案为什么也不行（上一版踩过）
  # 「正在把安装包下载到本机」只在真的在下载的那十几秒里存在，而
  # `materializeForShare` 对同一文件有内容指纹缓存，第二次点会瞬时命中 ——
  # 按文案判会把"缓存命中的正确行为"判成"没有先落地"（M6 实测假红一次）。
  # 凡是"判据依赖耗时"的地方都有这个毛病。产物判据与耗时、缓存都无关。
  #
  # ## 反向验证（方案 §12.1）
  # 修复前 `openExternally` 把远端 `content://` 直接交给安装器，
  # `external-staging/` 下**不会**出现任何东西 —— A1 在旧代码上必然为红。
  local staged="no"
  local deadline1=$((SECONDS + 90))
  while (( SECONDS < deadline1 )); do
    if staged_ok "${tap_epoch}"; then staged="yes"; break; fi
    case "$(top_activity)" in
      *packageinstaller*)
        # 落地发生在拉起安装器**之前**，所以安装器一起来就说明该落完了；
        # 仍留 4 秒收尾（文件写完到 `stat` 能看见之间的可见性抖动）。
        sleep 4
        staged_ok "${tap_epoch}" && staged="yes"
        break
        ;;
    esac
    sleep 1
  done
  if [[ "${staged}" == "yes" ]]; then
    local ev
    ev="$(staged_stat "${APK_NAME}")"
    ok "A1 点击后先落地再外发（产物证据：$(printf '%s' "${ev}" | awk '{print $1}') 字节 = 远端 ${APK_BYTES} 字节）"
  elif [[ "${FILE_EVIDENCE}" == "yes" ]]; then
    bad "A1 external-staging 下没有落地文件（或大小对不上远端 ${APK_BYTES} 字节）—— 修复未生效"
  elif [[ "$(stage_count)" -gt 0 ]]; then
    # 文件通道不可用（真机无 root）时的退路：debug 包的日志。
    ok "A1 点击后先落地再外发（退路证据：logcat $(stage_line)）"
  else
    bad "A1 取不到落地证据（文件通道不可用，logcat 里也没有「已暂存」）—— 本轮无法判定，不记通过"
  fi

  echo "  · 等安装器出现（≤180s，含落地时间）"
  if wait_installer 180; then
    ok "A2 安装器已起来（点击后 $((SECONDS - t0))s）"
  else
    bad "A2 180 秒内安装器没起来"
    return
  fi
  a shell screencap -p /sdcard/"${TAG}"-installer.png >/dev/null 2>&1
  a pull /sdcard/"${TAG}"-installer.png /tmp/"${TAG}"-installer.png >/dev/null 2>&1

  echo "  · 安装器内容：$(ui_dump && "${PY}" "${UI_SCRIPT}" dump-text "${XML}" | tr '\n' ' ' | head -c 300)"
  echo "  · 点「安装」"
  # ⚠️ 必须用 `id=button1`，**不能**用文案匹配。这里叠着两个坑，第二个才是致命的：
  #
  #  ① 文案语言不确定。判据原来只写「安装」「更新」两个中文串，而本机安装器是英文：
  #       密密文件管理 Do you want to update this app? Cancel Update
  #
  #  ② **`m2-ui.py` 的 `text=` 是"包含匹配 + 取 XML 树序第一个"**。
  #     提示语里的 "update" 会**先于按钮**被命中 —— 实测 `center <xml> text=Update`
  #     返回 `432 1209`（那句提示文字的中心），而按钮的真实中心是 `888 1383`。
  #     于是点击落在页面中部，按钮从来没被点到，而"找控件"这一步**是成功的**。
  #
  # 现象就是这一轮实测：A2 报"安装器已起来"，A3 却永远装不完 ——
  # 看起来像功能坏，实际是判据把一个**错误的坐标**当成了正确答案。
  # 方案 §12.3 的第三类（判据自身出错）里，这一类最贵：它让人去改一段好代码。
  #
  # `button1` / `button2` 是平台 `AlertDialog` 的确定/取消按钮 id，与语言无关、
  # 也不会被提示文字误命中。文案匹配留在后面作兜底。
  tap_installer_button() {
    tap_sel "id=button1" ||
      tap_sel "text=安装" || tap_sel "text=更新" ||
      tap_sel "text=Install" || tap_sel "text=Update"
  }
  tap_installer_button || bad "A2 安装器上找不到确定按钮（id=button1 与四种文案都试过）"
  sleep 3

  local deadline=$((SECONDS + 180))
  local done="no"
  while (( SECONDS < deadline )); do
    local now
    now="$(pkg_last_update)"
    if [[ -n "${now}" && "${now}" != "${before}" ]]; then
      done="yes"
      break
    fi
    # 安装确认页/权限页可能还挡着，再试一次点按钮
    tap_installer_button >/dev/null 2>&1 || tap_sel "text=打开" >/dev/null 2>&1 ||
      tap_sel "text=Open" >/dev/null 2>&1
    sleep 4
  done
  if [[ "${done}" == "yes" ]]; then
    ok "A3 安装完成（lastUpdateTime → $(pkg_last_update)）"
  else
    bad "A3 180 秒内没有装完 —— 仍然卡住"
    a shell screencap -p /sdcard/"${TAG}"-stuck.png >/dev/null 2>&1
    a pull /sdcard/"${TAG}"-stuck.png /tmp/"${TAG}"-stuck.png >/dev/null 2>&1
  fi
}

# ══════════════════════════════════════════════════════════════════════════
echo "════════ M6 · 真机 bug 修复验证（release）════════"
echo "SMB   smb://${SMB_HOST}:${SMB_PORT}/${SMB_SHARE}"
echo "用例  ${CASE}"
echo

echo "── 夹具 ──"
ensure_fixtures || exit 1
if ! (exec 3<>/dev/tcp/127.0.0.1/"${SMB_PORT}") 2>/dev/null; then
  echo "  ✗ 本机 ${SMB_PORT} 端口没有服务在听 —— 先起 SMB 服务端："
  echo "    ${PY} scripts/m51-smb-server.py ${SMB_PORT} true ${SMB_USER} ${SMB_PASS} ${SMB_SHARE} ${SHARE_DIR}"
  exit 1
fi
echo "  · SMB 服务端在 :${SMB_PORT}"
echo

if ensure_root; then
  echo "  · A1 取证通道：应用私有目录（root 可用，${ROOT_CMD:-adb root}）"
else
  echo "  · ⚠️ A1 取证通道：拿不到 root，退化到 logcat（**release 包上取不到任何东西**）"
fi
echo

if [[ "${SKIP_PREPARE}" == "no" ]]; then
  prepare
  add_smb_connection || { echo "连接添加失败，终止"; exit 1; }
else
  # ⚠️ 这条捷径**已停用**（`--no-prepare` 会直接退出）。
  #
  # 它当初是为了省掉 prepare 那 ~40 秒（清数据 + 重建连接 + 放行权限），
  # 在"连跑七八次改判据"的时候确实诱人。但实测它**不可靠**，连烧了四轮：
  #
  #   · `force-stop` 之后应用恢复的是**上一次所在的目录**，而不是全新状态；
  #     于是 `enter_share_dir` 起手的 BACK 与之后的抽屉点击落在完全不同的界面上；
  #     实测四次重试都进不去 SMB 目录，抽屉停在"本地存储 + 抽屉展开"的中间态。
  #   · 而它报出来的红**长得和真失败一模一样**（`A0 进不了 SMB 目录`），
  #     于是我把时间花在怀疑应用上，而应用在这条路径上没有错。
  #
  # 判断依据很简单：**一个会产出可信外观的假红的功能，比没有这个功能糟糕得多**
  # （方案 §12.3 说的"判据自身出错"最贵的正是这一点——它让人去改一段好代码）。
  # 完整流程 3~6 分钟，这个代价该付。
  echo "  ✗ --no-prepare 已停用：它依赖「应用上一次所在的目录」，会产出假红。请跑完整流程。" >&2
  exit 2
fi

case "${CASE}" in
  copy)    case_copy ;;
  install) case_install ;;
  all)     case_copy; case_install ;;
  *) echo "unknown --case ${CASE}"; exit 2 ;;
esac

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
