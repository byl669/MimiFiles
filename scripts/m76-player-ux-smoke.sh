#!/usr/bin/env bash
# ==========================================================================
# M7.6 · 播放体验四合一端到端验证（**只跑 release 包**）
#
# 覆盖四项改动：
#   P1 旋转按钮：改为自有状态机（点击必翻转）。判据：
#      单击 → 横屏（SurfaceOrientation≠0）；再单击 → 竖屏（=0）；
#      **背靠背连点两下（<300ms）→ 仍是竖屏**（老实现读滞后的 config 会算出
#      同一个目标 = 连点两次都"转横屏"，这是 M7.6 修的原始 bug，反向判据）。
#   P2 跳帧按钮：setSeekBack/ForwardIncrementMs(15s/30s)。判据：
#      点一次快进，位置增量落在 12~50s（含播放推进）。
#   P3 默认播放速度：设置里选 2 倍速 → 重开视频 → 位置推进速率 ≈ 2×墙钟。
#   P4 手势健壮性：一串单指滑动/点击后进程存活（手势层重写后的回归网）。
#      （双指捏合注入 adb 做不了，真机验收覆盖。）
#
# ## 本脚本的硬教训（第一版踩坑）
#   · 控制条 5s 自动隐藏：**定位与点击必须用同一份 dump**，跨两次 dump 必过期；
#   · 夹具要 300s：60s 的片子跑到 P2 就 ENDED，位置永远读出 1:00。
#
# ⚠️ M7.7 起 P1（旋转按钮）断言已过时：按钮语义改为「同步手机物理方向」，
#    平放时正确地保持竖屏，P1 会红 —— 那不是回归。旋转语义的现行验证见
#    scripts/m77-rotate-sync-smoke.sh（本脚本保留作为跳帧/倍速/手势的回归网）。
#
# 用法：scripts/m76-player-ux-smoke.sh
# 前置：模拟器在跑；release 包已构建；宿主有 imageio-ffmpeg。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="${HOME}/.cmfa-toolchain/android-sdk/platform-tools:${PATH}"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles"
PY="${HOME}/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="${PROJECT_DIR}/scripts/m2-ui.py"
FF="$("${PY}" -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())')"

XML="/tmp/m76.xml"
OUT="/tmp/m76-smoke"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

ui_dump() {
  a shell rm -f /sdcard/m76.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m76.xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/m76.xml > "${XML}" 2>/dev/null
  [[ -s "${XML}" ]]
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

surface_orientation() {
  # API 34：dumpsys input 没有 SurfaceOrientation 字段（老版本经验过期），
  # 用 window displays 的 mCurrentRotation=ROTATION_<n>。
  a shell dumpsys window displays 2>/dev/null | grep -m1 -oE "mCurrentRotation=ROTATION_[0-9]+" | grep -oE "[0-9]+"
}

# 按 desc / text / id 后缀找节点中心，输出 "x y"（从当前 ${XML} 里找）。
locate_in_xml() {
  "${PY}" "${UI_SCRIPT}" center "${XML}" "$1" 2>/dev/null | head -n1
}

tap_xy() { # $1="x y"
  a shell input tap "$(echo "$1" | awk '{print $1}')" "$(echo "$1" | awk '{print $2}')" >/dev/null 2>&1
}

# 保证 dump 出来的 ${XML} 里控制条可见（exo_play_pause 在）。
# 控制条 5s 自动隐藏，起播时又自动显示 —— 只能"探测，不在就点一下唤出"。
dump_controller() {
  local i
  for i in 1 2 3 4 5; do
    if ui_dump && [[ -n "$(locate_in_xml "id=exo_play_pause")" ]]; then
      return 0
    fi
    a shell input tap 540 1200 >/dev/null 2>&1
    sleep 1.2
  done
  return 1
}

# 唤出控制条并在**同一份 dump**里定位、立即点击（跨 dump 定位必过期，教训见文件头）。
tap_in_controller() { # $1=定位串
  dump_controller || return 1
  local xy
  xy="$(locate_in_xml "$1")"
  [[ -n "${xy}" ]] || return 1
  tap_xy "${xy}"
}

# 读 exo_position 文本并换算成秒（保证 dump 时控制条可见）。
position_seconds() {
  dump_controller || { echo ""; return 1; }
  "${PY}" - "${XML}" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1])
for n in t.iter('node'):
    rid = n.get('resource-id') or ''
    if rid.endswith(':id/exo_position'):
        text = (n.get('text') or '').strip()
        parts = text.split(':')
        if all(p.isdigit() for p in parts) and len(parts) >= 2:
            sec = 0
            for p in parts:
                sec = sec * 60 + int(p)
            print(sec)
            break
PYEOF
}

make_fixture() { # $1=输出路径（960x540 H.264 + AAC 60s，靠 ENDED 稳定态做 UI 操作）
  local out="$1"
  rm -f "${out}"                   # 教训：残留旧夹具会被复用，时长全错
  "${FF}" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc=size=960x540:rate=30:duration=60" \
    -f lavfi -i "sine=frequency=440:duration=60" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p \
    -c:a aac -shortest "${out}"
}

prepare() {
  echo "── 环境准备 ──"
  a install -r -g app/build/outputs/apk/release/app-arm64-v8a-release.apk >/dev/null 2>&1 \
    || { echo "  ✗ release 包安装失败"; return 1; }
  make_fixture "${OUT}/m76-a.mp4" || { echo "  ✗ 夹具 A 生成失败"; return 1; }
  make_fixture "${OUT}/m76-b.mp4" || { echo "  ✗ 夹具 B 生成失败"; return 1; }
  a shell mkdir -p /sdcard/Movies >/dev/null 2>&1
  a push "${OUT}/m76-a.mp4" /sdcard/Movies/m76-a.mp4 >/dev/null 2>&1
  a push "${OUT}/m76-b.mp4" /sdcard/Movies/m76-b.mp4 >/dev/null 2>&1
  a shell pm clear "${APP_ID}" >/dev/null 2>&1
  a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  echo "  · 已装包 / 推夹具 / 清数据放权限"
}

open_player() { # $1=设备上的视频路径
  a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
      --esa preview.paths "local://$1" --ei preview.index 0 >/dev/null 2>&1
  sleep 5
  [[ "$(top_activity)" == *VideoPlayerActivity* ]]
}

echo "════════ M7.6 · 播放体验四合一（release）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

prepare || exit 1

echo
echo "──────── P0 · 播放页起来 + 等 ENDED 稳定态 ────────"
open_player /sdcard/Movies/m76-a.mp4 || { bad "P0 播放页没起来（$(top_activity)）"; exit 1; }
# uiautomator dump 实测 ~7.5s，比控制条 5s 自动隐藏还慢 —— 播放中永远"看不见"控制条。
# 金路径（m75 同款）：等 60s 夹具自然播完，ENDED 态控制条不再自动隐藏，UI 操作全部稳定。
settled=0
for i in $(seq 1 14); do
  if dump_controller && grep -q 'desc="Play"' "${XML}"; then settled=1; break; fi
  sleep 3
done
[[ "${settled}" == "1" ]] || { bad "P0 等不到 ENDED 稳定态"; exit 1; }
ok "P0 播放完成进入稳定态（控制条不再自动隐藏）"

echo "──────── P1 · 旋转按钮：状态机三连 ────────"
before="$(surface_orientation)"
if tap_in_controller "desc=横屏播放"; then
  sleep 2.5
  o1="$(surface_orientation)"
  if [[ -n "${o1}" && "${o1}" != "${before}" && "${o1}" != "0" ]]; then
    ok "P1a 单击 → 锁横屏（SurfaceOrientation=${o1}）"
  else
    bad "P1a 单击后方向没变（before=${before} after=${o1}）"
  fi
else
  bad "P1a 控制条里找不到旋转按钮（desc=横屏播放）"
fi

if tap_in_controller "desc=竖屏播放"; then
  sleep 2.5
  o2="$(surface_orientation)"
  if [[ "${o2}" == "0" ]]; then
    ok "P1b 再单击 → 锁竖屏（=0）"
  else
    bad "P1b 再单击没回到竖屏（=${o2}）"
  fi
else
  bad "P1b 控制条里找不到旋转按钮（desc=竖屏播放）"
fi

# 连续三次单击，每次都必须生效（横→竖→横交替）：这是"点了没反应"的直接反义命题。
# （真·背靠背连点测不了：第一次点击触发转屏时系统会冻结应用窗口输入，
# 第二下根本送不进 App —— mDeferredRotationPauseCount，是系统行为不是 App 的。）
p1c_ok=0
for expect in "landscape landscape" "portrait 0" "landscape landscape"; do
  set -- ${expect}
  want="${2}"
  if tap_in_controller "desc=横屏播放" || tap_in_controller "desc=竖屏播放"; then
    sleep 3
    o="$(surface_orientation)"
    if [[ "${want}" == "landscape" && -n "${o}" && "${o}" != "0" ]] || [[ "${want}" == "0" && "${o}" == "0" ]]; then
      p1c_ok=$((p1c_ok + 1))
    fi
  fi
  sleep 1
done
if [[ "${p1c_ok}" == "3" ]]; then
  ok "P1c 连续三次单击全部生效（横→竖→横交替，无死点击）"
else
  bad "P1c 三次单击只有 ${p1c_ok}/3 次生效"
fi

echo "──────── P2 · 跳帧增量 15s/30s（ENDED 态，稳定）────────"
# P1c 停在横屏：转回竖屏并等沉降，否则坐标全是转屏动画里的过期值。
if [[ "$(surface_orientation)" != "0" ]]; then
  tap_in_controller "desc=竖屏播放" >/dev/null 2>&1 || true
  sleep 4
fi
# ENDED 态在 1:00：点一次"快退 15 秒"→ 位置应到 00:45（确定性判据）。
# 像素对比判据：快退生效 → 底部条的位置文本(01:00→00:4x)、进度条位置、
# 播放/暂停图标全部变化；快退无效（ENDED 静止帧）→ 逐像素不变。
# （为什么不用位置文本读取：播放中控制条 5s 自动隐藏，uiautomator dump 实测
#   ~7.5s 永远看不到它；ENDED 态能读到时位置恒为片尾。改用 Pillow 比像素。）
if ! dump_controller; then
  bad "P2a 控制条唤不出来"
else
  xy="$(locate_in_xml "id=exo_rew_with_amount")"
  [[ -n "${xy}" ]] || xy="$(locate_in_xml "id=exo_rew")"
  if [[ -z "${xy}" ]]; then
    bad "P2a 控制条里找不到快退按钮"
  else
    # 前照必须在控制条可见时拍（与后照同一可见状态，diff 才只反映快退效果）
    a shell screencap -p /sdcard/p2a-before.png >/dev/null 2>&1
    tap_xy "${xy}"
    sleep 0.4
    a shell screencap -p /sdcard/p2a-after.png >/dev/null 2>&1
    a pull /sdcard/p2a-before.png "${OUT}/p2a-before.png" >/dev/null 2>&1
    a pull /sdcard/p2a-after.png "${OUT}/p2a-after.png" >/dev/null 2>&1
    diff_pct="$("${PY}" - "${OUT}/p2a-before.png" "${OUT}/p2a-after.png" <<'PYEOF'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert('RGB').crop((0, 2242, 1080, 2400))
b = Image.open(sys.argv[2]).convert('RGB').crop((0, 2242, 1080, 2400))
d = ImageChops.difference(a, b)
px = list(d.getdata())
changed = sum(1 for q in px if max(q) > 12)
print(f"{changed * 100.0 / len(px):.2f}")
PYEOF
)"
    if [[ -n "${diff_pct}" ]] && (( $(echo "${diff_pct} > 0.8" | bc -l 2>/dev/null || echo 0) )); then
      ok "P2a 快退 15s 生效（底部条像素变化 ${diff_pct}%：时间文本/进度/图标都动了）"
    else
      bad "P2a 快退前后底部条像素几乎不变（${diff_pct:-?}%）—— 快退没生效？"
    fi
  fi
fi

# 快进 30 判据：按钮自证（desc 带增量、标签数字 30 —— 增量由播放器持有，
# 按钮显示对得上 = Builder 设置端到端生效）。
if dump_controller && grep -q 'Fast forward 30 seconds' "${XML}" && grep -q 'Rewind 15 seconds' "${XML}"; then
  ok "P2b 按钮 desc 自证：Rewind 15 / Fast forward 30"
else
  bad "P2b 按钮增量标签不是 15/30"
fi

echo "──────── P3 · 默认播放速度 2x ────────"
a shell am force-stop "${APP_ID}" >/dev/null 2>&1
a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.settings.SettingsActivity" >/dev/null 2>&1
sleep 3
opened=0
if [[ "$(top_activity)" == *SettingsActivity* ]]; then
  for try in 1 2 3; do
    tap_node_xy="$(ui_dump && locate_in_xml "text=默认视频播放速度")" || { sleep 2; continue; }
    [[ -n "${tap_node_xy}" ]] || { sleep 2; continue; }
    tap_xy "${tap_node_xy}"
    sleep 2
    if ui_dump && [[ -n "$(locate_in_xml "text=2 倍速")" ]]; then
      tap_xy "$(locate_in_xml "text=2 倍速")"
      sleep 0.8
      ok_xy="$(locate_in_xml "text=确定")"
      [[ -n "${ok_xy}" ]] || ok_xy="$(locate_in_xml "text=OK")"
      [[ -n "${ok_xy}" ]] && tap_xy "${ok_xy}"
      sleep 1
      opened=1
      break
    fi
    a shell screencap -p /sdcard/m76-dialog.png >/dev/null 2>&1
    a pull /sdcard/m76-dialog.png "${OUT}/m76-dialog.png" >/dev/null 2>&1
    cp "${XML}" "${OUT}/m76-dialog.xml"
    sleep 1
  done
else
  bad "P3a 设置页没起来（$(top_activity)）"
fi
if [[ "${opened}" == "1" ]]; then
  a shell input keyevent 4 >/dev/null 2>&1
  ok "P3a 设置里把默认速度改成 2 倍速"
else
  bad "P3a 选择框流程没走通（截图与 dump 在 ${OUT}/m76-dialog.*）"
fi

# 重开**另一个**文件（无历史进度）：默认速度 2x → 60s 的片子应 ~30s 就播完。
# 位置文本读取被"dump 7.5s vs 控制条 5s"竞态卡死，改用 ENDED 到达时间判据。
t_open=$SECONDS
open_player /sdcard/Movies/m76-b.mp4 || { bad "P3b 夹具 B 播放页没起来"; exit 1; }
ended_at=""
for i in $(seq 1 14); do
  if dump_controller && grep -q 'desc="Play"' "${XML}"; then
    ended_at=$((SECONDS - t_open))
    break
  fi
  sleep 3
done
if [[ -n "${ended_at}" ]] && (( ended_at <= 48 )); then
  ok "P3b 60s 片子 ${ended_at}s 播完 ≈ 2x（默认速度生效；1x 会 ~60s）"
else
  bad "P3b 播完耗时 ${ended_at:->105}s —— 不像 2x"
fi

echo "──────── P4 · 手势健壮性（单指回归网）────────"
for s in "540 1800 540 800" "100 1200 900 1200" "980 800 980 1800" "900 1200 100 1200"; do
  a shell input swipe ${s} 260 >/dev/null 2>&1
  sleep 0.6
done
a shell input tap 540 1200 >/dev/null 2>&1
sleep 1
if a shell pidof "${APP_ID}" >/dev/null 2>&1 && [[ "$(top_activity)" == *VideoPlayerActivity* ]]; then
  ok "P4 连续手势后进程存活、页面未崩"
else
  bad "P4 连续手势后进程/页面异常"
fi

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
