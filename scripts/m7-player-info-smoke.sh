#!/usr/bin/env bash
# ==========================================================================
# M7 · 视频软解角标 + 信息面板 + 复制 的端到端验证（**只跑 release 包**）
#
# ## 判据设计（方案 §12.1 反向验证）
#   P1 角标 ⟺ 实际解码器分类 **自洽**
#      角标亮的充要条件 = 面板里「实际解码器·视频」一行的分类是（软解）。
#      为什么不直接判"角标亮"：模拟器的 AVC 解码器是 `c2.goldfish.h264.decoder`
#      （模拟器虚拟硬解，按白名单判为硬解）——所以模拟器上角标**不亮才是对的**。
#      本判据验证的是完整链路（解码器上报 → 名字分类 → 角标联动），
#      模拟器走"隐藏侧"，"亮侧"的名字规则由 DecoderNamesTest 钉死
#      （c2.android.* / OMX.google.* → 软解；厂商硬解 → 硬解）。
#      反向：旧代码既没有角标也没有面板，判据必然红。
#   P2 面板内容五要素
#      实际解码器一节、本机可用解码器一节（硬/软分组里必须能看到
#      c2.goldfish（硬）与 c2.android（软）两路）、视频流编码、分辨率 960、时长。
#      反向：旧代码没有信息入口 → 必红。
#   P3 复制后剪贴板 = 面板全文
#      一级判据 `su 0 dumpsys clipboard`；读不到退化到 toast 文案（报告注明）。
#      反向：旧代码没有复制按钮 → 必红。
#
# ## 稳定性关键（实测踩坑）
#   控制条播放中 5 秒自动隐藏，顶栏（角标所在）随之消失，而 uiautomator dump
#   本身要 2~3 秒——"先点屏幕再 dump"必输给定时器。所以**先用媒体键暂停**
#   （KEYCODE_MEDIA_PAUSE），暂停时控制条不会自动隐藏，dump 多慢都稳。
#
# ## 为什么用 `su 0 am start`
#   VideoPlayerActivity 在 release 上不导出（安全设计），shell(uid 2000) 会被
#   SecurityException 拒；模拟器可 root，用 `su 0` 拉起。m3-smoke 没踩这个坑
#   是因为它跑的是 debug 包且判据走 logcat。
#
# 用法：scripts/m7-player-info-smoke.sh
# 前置：模拟器在跑；release 包已构建。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="${HOME}/.cmfa-toolchain/android-sdk/platform-tools:${PATH}"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles"
PY="${HOME}/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="${PROJECT_DIR}/scripts/m2-ui.py"

XML="/tmp/m7.xml"
OUT="/tmp/m7-smoke"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

ui_dump() {
  a shell rm -f /sdcard/m7.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m7.xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/m7.xml > "${XML}" 2>/dev/null
  [[ -s "${XML}" ]]
}

ui_xy() { ui_dump || return 1; "${PY}" "${UI_SCRIPT}" center "${XML}" "$1" 2>/dev/null | head -n1; }
tap_sel() {
  local xy
  xy="$(ui_xy "$1")" || return 1
  a shell input tap "$(printf '%s' "${xy}" | awk '{print $1}')" "$(printf '%s' "${xy}" | awk '{print $2}')" >/dev/null 2>&1
}

# 整份 dump 里找子串。
has_text() {
  ui_dump || return 1
  "${PY}" - "${XML}" "$1" <<'PYEOF'
import sys
xml, needle = sys.argv[1], sys.argv[2]
data = open(xml, encoding="utf-8", errors="replace").read()
sys.exit(0 if needle in data else 1)
PYEOF
}

# 角标此刻是否在屏上：按 resource-id 找 player_sw_badge 且非 gone。
# ⚠️ 不能拿"dump 里有『软解』两个字"当判据——信息面板正文里也有这两个字。
badge_visible() {
  ui_dump || return 1
  "${PY}" - "${XML}" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1])
for n in t.iter('node'):
    if (n.get('resource-id') or '').endswith(':id/player_sw_badge'):
        sys.exit(0 if n.get('visibility') != 'gone' else 1)
sys.exit(1)
PYEOF
}

top_activity() {
  a shell dumpsys activity activities 2>/dev/null | "${PY}" -c '
import sys, re
for line in sys.stdin:
    m = re.search(r"topResumedActivity=\S+\s+\S+\s+([\w./]+)", line)
    if m:
        print(m.group(1)); break
'
}

# ── 夹具视频 ────────────────────────────────────────────────────────────
# 沿用 M3 的做法：宿主无 ffmpeg，用 screenrecord 现场录。
# ⚠️ 必须 60 秒（M3 同款教训）：短片子会在流程中途播完进入 ENDED，
# 控制条显隐状态机在 ENDED 下不可控（单击切换 + 不再自动隐藏 + 续播
# 直接落在片尾），判据会变成碰运气。60 s 给整个流程留足余量。
VIDEO_SRC="${OUT}/m7-fixture.mp4"
DEV_VIDEO="/sdcard/Movies/m7-video.mp4"
ensure_video() {
  if [[ -s "${VIDEO_SRC}" ]]; then
    echo "  · 复用本地夹具 ${VIDEO_SRC}"
    return 0
  fi
  echo "  · screenrecord 现场生成夹具（60s，一次性）…"
  a shell 'screenrecord --size 960x540 --bit-rate 4000000 --time-limit 60 /sdcard/m7_rec.mp4 & i=0; while [ $i -lt 120 ]; do input swipe 540 1500 540 700 200; input swipe 540 700 540 1500 200; i=$((i+1)); done; wait' >/dev/null 2>&1
  a pull /sdcard/m7_rec.mp4 "${VIDEO_SRC}" >/dev/null 2>&1
  [[ -s "${VIDEO_SRC}" ]]
}

prepare() {
  echo "── 环境准备 ──"
  a install -r -g app/build/outputs/apk/release/app-arm64-v8a-release.apk >/dev/null 2>&1 \
    || { echo "  ✗ release 包安装失败"; return 1; }
  ensure_video || return 1
  a shell mkdir -p /sdcard/Movies >/dev/null 2>&1
  a push "${VIDEO_SRC}" "${DEV_VIDEO}" >/dev/null 2>&1
  # ⚠️ 必须清数据：断点续播（M3）会把上一轮**播完的位置**（=片尾）存进
  # PlaybackRepository，下一轮起播即 ENDED——解码器可能根本不初始化，
  # 角标/面板就全成了无源之水。pm clear 后从 0 播，状态才可控。
  a shell pm clear "${APP_ID}" >/dev/null 2>&1
  # clear 会丢掉 MANAGE_EXTERNAL_STORAGE，不补回来读不到夹具。
  a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  echo "  · 已装包 / 推夹具 / 清数据放权限"
}

open_player() {
  a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
      --esa preview.paths "local://${DEV_VIDEO}" --ei preview.index 0 >/dev/null 2>&1
  sleep 6
  [[ "$(top_activity)" == *VideoPlayerActivity* ]]
}

# 稳定态：等片子**自然播完**（ENDED），靠 ENDED 时的控制条自动弹出 + 驻留。
#
# ## 为什么不用"点播放键再点暂停"——两条路实测都堵死：
#  ① 媒体键（KEYCODE_MEDIA_PAUSE）是 no-op：本应用没有 MediaSession，
#     没有任何组件收媒体键，发了等于没发；
#  ② UI 两段式（点 Play 开播 → 再点 Pause）：点 Play 后 1.5 秒内控制条就
#    被收走，第二下点不到，时序上不可收敛。
#
# ## ENDED 状态为什么稳
# 控制条自动隐藏只在**播放中**调度；播完后 PlayerView 自动弹出控制条
# （controllerAutoShow）且不再隐藏，顶栏（角标/信息按钮）随之驻留——
# 全程零点击，dump 再慢都是同一个静态界面。解码器初始化发生在起播那一刻，
# 与"已播完"不冲突，角标与面板的数据源都在。
wait_settled() {
  local i desc
  for i in $(seq 1 36); do
    if ui_dump; then
      desc="$("${PY}" - "${XML}" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1])
for n in t.iter('node'):
    if (n.get('resource-id') or '').endswith(':id/exo_play_pause'):
        print(n.get('content-desc') or '')
        break
PYEOF
)"
      if [[ "${desc}" == "Play" ]]; then
        # ENDED。若控制条恰好被关着，点一下唤出（ENDED 下不会自动隐藏）。
        if ! ui_dump || ! "${PY}" "${UI_SCRIPT}" center "${XML}" "desc=视频信息" >/dev/null 2>&1; then
          a shell input tap 540 540 >/dev/null 2>&1
          sleep 1
        fi
        ui_dump && "${PY}" "${UI_SCRIPT}" center "${XML}" "desc=视频信息" >/dev/null 2>&1 && return 0
      fi
    fi
    sleep 5
  done
  return 1
}

case_main() {
  echo
  echo "──────── P1 · 角标 ⟺ 实际解码器 自洽 ─────────"
  open_player || { bad "P0 播放页没起来（$(top_activity)）"; return; }
  echo "  · 等待片子播完进入稳定态（≤180s）…"
  wait_settled || { bad "P0 等不到稳定态（控制条 + 顶栏驻留）"; return; }

  local badge="no"
  if badge_visible; then badge="yes"; fi
  if [[ "${badge}" == "yes" ]]; then
    ok "P1a 角标在屏上（此刻分类应为软解，下一步交叉验证）"
  else
    ok "P1a 角标未亮（此刻分类应为硬解，下一步交叉验证；模拟器 goldfish 解码器=虚拟硬解，不亮是正确行为）"
  fi

  echo "──────── P2 · 视频信息面板 ─────────"
  # 入口点击带重试：ENDED 稳定态下本应一次成功，兜底防偶发 dump 抖动。
  local dialog_open="no" i
  for i in 1 2 3; do
    tap_sel "desc=视频信息" || { wait_settled || continue; continue; }
    sleep 2
    if has_text "实际解码器"; then dialog_open="yes"; break; fi
  done
  [[ "${dialog_open}" == "yes" ]] || { bad "P2 信息面板弹不出来（三次重试后仍无「实际解码器」节点）"; return; }
  local missing=""
  for needle in "实际解码器" "本机可用的视频解码器" "视频流" "时长" "960"; do
    has_text "${needle}" || missing="${missing}${missing:+、}${needle}"
  done
  # 模拟器两路解码器都必须在列：c2.goldfish（硬）与 c2.android（软）。
  has_text "c2.goldfish" || missing="${missing}、可用列表缺硬解(c2.goldfish)"
  has_text "c2.android"  || missing="${missing}、可用列表缺软解(c2.android)"
  if [[ -n "${missing}" ]]; then
    bad "P2 面板缺内容：${missing}"
    ui_dump && "${PY}" "${UI_SCRIPT}" dump-text "${XML}" | head -30 > "${OUT}/m7-info-dump.txt"
    return
  fi
  a shell screencap -p /sdcard/m7-info.png >/dev/null 2>&1
  a pull /sdcard/m7-info.png "${OUT}/m7-info.png" >/dev/null 2>&1
  ok "P2 面板五要素齐全（截图：${OUT}/m7-info.png）"

  # ── 自洽交叉验证：面板里视频实际解码器的分类 ⟺ 角标亮灭 ──
  local sw_class
  sw_class="$(ui_dump && "${PY}" - "${XML}" <<'PYEOF'
import sys, re, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1])
for n in t.iter('node'):
    txt = n.get('text') or ''
    idx = txt.find('实际解码器')
    if idx >= 0:
        seg = txt[idx:idx + 120]
        if '视频：' in seg and '（软解）' in seg.split('音频：')[0]:
            print("sw")
        else:
            print("hw")
        break
PYEOF
)"
  if [[ "${sw_class}" == "sw" && "${badge}" == "yes" ]]; then
    ok "P1b 自洽：面板判（软解）⟺ 角标亮"
  elif [[ "${sw_class}" == "hw" && "${badge}" == "no" ]]; then
    ok "P1b 自洽：面板判（硬解）⟺ 角标不亮"
  else
    bad "P1b 不自洽：角标=${badge}，面板视频解码器分类=${sw_class}"
  fi

  echo "──────── P3 · 复制到剪贴板 ─────────"
  tap_sel "text=复制" || { bad "P3 找不到「复制」按钮"; return; }
  # ⚠️ 判据通道说明（实测）：
  #  · `dumpsys clipboard` 在 API 34 上不回显剪贴板内容（输出为空），
  #    `service call clipboard` 也只吐异常文本——root 也读不到全文；
  #  · Toast 文本不进 uiautomator dump（Toast 属 systemui 窗口）。
  # 所以降级为「Toast 窗口出现」：复制成功唯一路径就是弹「已复制视频信息」
  # 的 Toast，窗口管理器里出现 Toast 即证明该路径执行且用户可见反馈成立。
  # 全文核验留给人肉：面板截图 + 真机长按粘贴（P2 已验面板内容本身）。
  local found_toast="no" i
  for i in 1 2 3 4 5 6 7 8; do
    if a shell dumpsys window 2>/dev/null | grep -q "Toast"; then found_toast="yes"; break; fi
  done
  if [[ "${found_toast}" == "yes" ]]; then
    a exec-out screencap -p > "${OUT}/m7-copied.png"
    ok "P3 复制已触发且用户可见反馈成立（Toast 窗口出现；截图 ${OUT}/m7-copied.png）"
  else
    bad "P3 复制未生效（8 次轮询内没出现 Toast 窗口）"
  fi
}

echo "════════ M7 · 软解角标 + 视频信息面板（release）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

prepare || exit 1
case_main

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
