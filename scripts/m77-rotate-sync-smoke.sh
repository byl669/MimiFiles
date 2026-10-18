#!/usr/bin/env bash
# ==========================================================================
# M7.8 · 旋转按钮「同步式 + 请求升级链」端到端验证（**只跑 release 包**）
#
# 语义（M7.7 按产品需求重定）：点按钮 = 把画面转到**手机此刻的物理方向**。
# 不再是翻转式。手机平放/传感器不可用时退化为翻转兜底；长按恢复跟随系统。
#
# 判据（全部基于 mCurrentRotation，见 surface_orientation）：
#   先关掉系统自动旋转（accelerometer_rotation=0，复刻真机场景：
#   开关开着的话屏幕早自己转了，根本轮不到按按钮）——
#   T1 注入"顶朝左"重力 (+g,0,0) → 点按钮 → 期望 ROTATION_90
#   T2 注入"顶朝右"重力 (-g,0,0) → 点按钮 → 期望 ROTATION_270
#   T3 注入"竖直拿"重力 (0,+g,0) → 点按钮 → 期望 ROTATION_0
#   T4 长按按钮恢复跟随系统 → 再注入横屏重力 → 期望**不动**（ROTATION_0）
#      （自动旋转关着 + UNSPECIFIED，系统不会自己转 —— 证明长按出口还在）
#
# 重力→旋转映射表 M7.7 已用系统自身行为实测（Settings 前台 + 自动旋转开启）：
#   (0,+g,0)→0  (+g,0,0)→90  (-g,0,0)→270
#
# 用法：scripts/m77-rotate-sync-smoke.sh
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

XML="/tmp/m77.xml"
OUT="/tmp/m77-smoke"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

ui_dump() {
  a shell rm -f /sdcard/m77.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m77.xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/m77.xml > "${XML}" 2>/dev/null
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
  a shell dumpsys window displays 2>/dev/null | grep -m1 -oE "mCurrentRotation=ROTATION_[0-9]+" | grep -oE "[0-9]+"
}

set_gravity() { # $1="x:y:z"
  a emu sensor set acceleration "$1" >/dev/null 2>&1
}

locate_in_xml() {
  "${PY}" "${UI_SCRIPT}" center "${XML}" "$1" 2>/dev/null | head -n1
}

tap_xy() {
  a shell input tap "$(echo "$1" | awk '{print $1}')" "$(echo "$1" | awk '{print $2}')" >/dev/null 2>&1
}

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

# 旋转按钮：desc 随 config 在「横屏播放/竖屏播放」之间变，两个都试。
tap_rotate() {
  dump_controller || return 1
  local xy
  xy="$(locate_in_xml "desc=横屏播放")"
  [[ -n "${xy}" ]] || xy="$(locate_in_xml "desc=竖屏播放")"
  [[ -n "${xy}" ]] || return 1
  tap_xy "${xy}"
}

long_press_rotate() {
  dump_controller || return 1
  local xy
  xy="$(locate_in_xml "desc=横屏播放")"
  [[ -n "${xy}" ]] || xy="$(locate_in_xml "desc=竖屏播放")"
  [[ -n "${xy}" ]] || return 1
  a shell input swipe "${xy}" "${xy}" 900 >/dev/null 2>&1
}

make_fixture() {
  local out="$1"
  rm -f "${out}"
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
  make_fixture "${OUT}/m77.mp4" || { echo "  ✗ 夹具生成失败"; return 1; }
  a shell mkdir -p /sdcard/Movies >/dev/null 2>&1
  a push "${OUT}/m77.mp4" /sdcard/Movies/m77.mp4 >/dev/null 2>&1
  a shell pm clear "${APP_ID}" >/dev/null 2>&1
  a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  # 复刻真机：自动旋转关着
  a shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  echo "  · 已装包 / 推夹具 / 清数据 / 自动旋转=关"
}

echo "════════ M7.7 · 旋转按钮同步式（release）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

prepare || exit 1

echo "──────── P0 · 播放页起来 + 等 ENDED 稳定态 ────────"
set_gravity "0:9.81:0"   # 竖直拿起手
a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
    --esa preview.paths "local:///sdcard/Movies/m77.mp4" --ei preview.index 0 >/dev/null 2>&1
sleep 5
[[ "$(top_activity)" == *VideoPlayerActivity* ]] || { bad "P0 播放页没起来（$(top_activity)）"; exit 1; }
settled=0
for i in $(seq 1 14); do
  if dump_controller && grep -q 'desc="Play"' "${XML}"; then settled=1; break; fi
  sleep 3
done
[[ "${settled}" == "1" ]] || { bad "P0 等不到 ENDED 稳定态"; exit 1; }
o0="$(surface_orientation)"
[[ "${o0}" == "0" ]] || { bad "P0 起手应为竖屏（实际=${o0}）"; exit 1; }
ok "P0 播放稳定态就绪，起手竖屏（自动旋转已关）"

echo "──────── T1 · 手机顶朝左 → 点按钮 → 应锁 ROTATION_90 ────────"
set_gravity "9.81:0:0"
sleep 1.5   # 给传感器监听一拍
if tap_rotate; then
  sleep 4
  o1="$(surface_orientation)"
  [[ "${o1}" == "90" ]] && ok "T1 顶朝左 → 横屏 ROTATION_90 ✓" || bad "T1 期望 ROTATION_90，实际=${o1}"
else
  bad "T1 找不到/点不到旋转按钮"
fi

echo "──────── T1b · 锁横屏后手机转回竖屏（不按按钮）→ 画面必须不动 ────────"
# M7.9 荣耀V6内屏症状的直接反义命题：锁了就是锁了，不自动跟随。
set_gravity "0:9.81:0"
sleep 3
o1b="$(surface_orientation)"
[[ "${o1b}" == "90" ]] && ok "T1b 锁横屏后手机转竖，画面不动（无自动跟随）✓" \
  || bad "T1b 锁横屏后画面自己动了（=${o1b}）—— 自动跟随回归！"

echo "──────── T2 · 手机顶朝右 → 点按钮 → 应锁 ROTATION_270 ────────"
set_gravity "-9.81:0:0"
sleep 1.5
if tap_rotate; then
  sleep 4
  o2="$(surface_orientation)"
  [[ "${o2}" == "270" ]] && ok "T2 顶朝右 → 反向横屏 ROTATION_270 ✓" || bad "T2 期望 ROTATION_270，实际=${o2}"
else
  bad "T2 找不到/点不到旋转按钮"
fi

echo "──────── T3 · 手机竖直拿 → 点按钮 → 应回 ROTATION_0 ────────"
set_gravity "0:9.81:0"
sleep 1.5
if tap_rotate; then
  sleep 4
  o3="$(surface_orientation)"
  [[ "${o3}" == "0" ]] && ok "T3 竖直拿 → 竖屏 ROTATION_0 ✓" || bad "T3 期望 ROTATION_0，实际=${o3}"
else
  bad "T3 找不到/点不到旋转按钮"
fi

echo "──────── T4 · 长按恢复跟随系统 → 注入横屏重力应不动 ────────"
a shell settings put system accelerometer_rotation 0 >/dev/null
sleep 1
if long_press_rotate; then
  sleep 2
  set_gravity "-9.81:0:0"
  sleep 3
  o4="$(surface_orientation)"
  [[ "${o4}" == "0" ]] && ok "T4 长按后跟随系统：自动旋转关着，画面不动 ✓" \
    || bad "T4 长按后画面自己转了（=${o4}）—— 跟随系统出口失效"
else
  bad "T4 长按失败（找不到按钮）"
fi

echo "──────── 清理 ────────"
set_gravity "0:9.81:0"
a shell settings put system accelerometer_rotation 1 >/dev/null 2>&1

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
