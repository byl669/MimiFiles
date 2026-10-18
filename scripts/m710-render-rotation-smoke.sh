#!/usr/bin/env bash
# ==========================================================================
# M7.10 · 渲染旋转兜底端到端验证（**只跑 release 包**）
#
# 背景：折叠屏内屏把升级链两级请求（SENSOR_* / 精确常量）全部拦截
#（0.7.9 真机实锤：toast"系统没响应"画面不动），只放行 FULL_SENSOR（=自动旋转，
# 已禁用）。M7.10 的兜底：系统不转，App 自己把内容层旋转 90°/270° 画出来。
#
# 模拟"系统拦截"的官方机制：`wm set-ignore-orientation-request true` ——
# 显示级忽略 App 的转向请求（部分平板/折叠屏内屏正是这个 policy）。
#
# 判据：
#   S1 ignore=true + 顶朝左 + 点按钮 → mCurrentRotation 仍为 0（系统没转）
#      且截图**右红左蓝**（内容旋转 +90°：视频上半→屏幕右侧）
#   S2 长按恢复 → 截图回**上红下蓝**（内容复原，画面仍在竖屏系统方向）
#   S3 顶朝右 + 点按钮 → 截图**左红右蓝**（-90° 方向正确）
#   夹具：上半红 / 下半蓝（drawbox），静态帧，像素断言用 Pillow。
#
# ⚠️ 2026-10-15 实测结论（重要，别再踩）：
# 模拟器**无法**模拟"请求被完全丢弃"——wm set-ignore-orientation-request /
# fixed-to-user-rotation 都会把请求转成 letterbox 兼容窗口（config 假翻转 +
# 窗口真变横，App 侧按窗口几何判定"请求成功"），升级链走不到兜底，S1/S2 会
# 停在"系统方向未变但画面没转"——那不是 bug。
# 兜底路径的端到端验证方法：临时把升级链验收条件改成恒 false（debug 包），
# 强制走 failure 分支，logcat -s MimiPlayer 应看到
# 「渲染旋转兜底启用 → ROTATION_x」且截图内容旋转（2026-10-15 已验证通过）。
# 本脚本保留给**真拦截 ROM 的真机**跑。
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

XML="/tmp/m710.xml"
OUT="/tmp/m710-smoke"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

ui_dump() {
  a shell rm -f /sdcard/m710.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m710.xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/m710.xml > "${XML}" 2>/dev/null
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

set_gravity() { a emu sensor set acceleration "$1" >/dev/null 2>&1; }

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

# 夹具：上半红 / 下半蓝（静态帧 + 静音音轨）
make_fixture() {
  local out="$1"
  rm -f "${out}"
  "${FF}" -hide_banner -loglevel error -y \
    -f lavfi -i "color=c=red:size=960x540:rate=30:duration=60" \
    -f lavfi -i "sine=frequency=440:duration=60" \
    -filter_complex "[0:v]drawbox=x=0:y=ih/2:w=iw:h=ih/2:color=blue:t=fill[v]" \
    -map "[v]" -map 1:a \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p \
    -c:a aac -shortest "${out}"
}

# 像素采样：$1=截图路径，$2..=x比例,y比例,标签 —— 输出 "标签 R G B"
sample_patch() {
  local shot="$1"; shift
  "${PY}" - "$shot" "$@" <<'PYEOF'
import sys
from PIL import Image
img = Image.open(sys.argv[1]).convert("RGB")
W, H = img.size
args = sys.argv[2:]
out = []
for i in range(0, len(args), 3):
    fx, fy, label = float(args[i]), float(args[i+1]), args[i+2]
    cx, cy = int(W*fx), int(H*fy)
    r=g=b=n=0
    for dx in range(-40, 41, 8):
        for dy in range(-40, 41, 8):
            pr,pg,pb = img.getpixel((cx+dx, cy+dy))
            r+=pr; g+=pg; b+=pb; n+=1
    out.append(f"{label} {r//n} {g//n} {b//n}")
print(" | ".join(out))
PYEOF
}

shot() { # $1=输出文件
  a exec-out screencap -p > "$1" 2>/dev/null
  [[ -s "$1" ]]
}

prepare() {
  echo "── 环境准备 ──"
  a install -r -g app/build/outputs/apk/release/app-arm64-v8a-release.apk >/dev/null 2>&1 \
    || { echo "  ✗ release 包安装失败"; return 1; }
  make_fixture "${OUT}/m710.mp4" || { echo "  ✗ 夹具生成失败"; return 1; }
  a push "${OUT}/m710.mp4" /sdcard/Movies/m710.mp4 >/dev/null 2>&1
  a shell pm clear "${APP_ID}" >/dev/null 2>&1
  a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  a shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
  # ★ 关键：让系统忽略 App 的转向请求（复现折叠屏内屏的拦截行为）
  a shell wm set-ignore-orientation-request true >/dev/null 2>&1
  a shell wm get-ignore-orientation-request 2>/dev/null | grep -q "true" \
    || { echo "  ✗ 无法设置 ignore-orientation-request"; return 1; }
  a logcat -c >/dev/null 2>&1
  echo "  · 已装包 / 推夹具 / ignore-orientation-request=true（模拟系统拦截）"
}

echo "════════ M7.10 · 渲染旋转兜底（release，系统拦截场景）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

prepare || exit 1

echo "──────── S0 · 播放页起来 + 等 ENDED 稳定态 ────────"
set_gravity "0:9.81:0"
a shell am force-stop com.android.settings >/dev/null 2>&1
a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
    --esa preview.paths "local:///sdcard/Movies/m710.mp4" --ei preview.index 0 >/dev/null 2>&1
sleep 5
[[ "$(top_activity)" == *VideoPlayerActivity* ]] || { bad "S0 播放页没起来（$(top_activity)）"; exit 1; }
settled=0
for i in $(seq 1 14); do
  if dump_controller && grep -q 'desc="Play"' "${XML}"; then settled=1; break; fi
  sleep 3
done
[[ "${settled}" == "1" ]] || { bad "S0 等不到 ENDED 稳定态"; exit 1; }
o0="$(surface_orientation)"
[[ "${o0}" == "0" ]] || { bad "S0 起手应为竖屏（实际=${o0}）"; exit 1; }
shot "${OUT}/s0.png" || { bad "S0 截图失败"; exit 1; }
s0="$(sample_patch "${OUT}/s0.png" 0.5 0.42 top 0.5 0.58 bottom)"
top_rgb="$(echo "${s0}" | awk -F' \\| ' '{print $1}')"
bot_rgb="$(echo "${s0}" | awk -F' \\| ' '{print $2}')"
tr="$(echo "${top_rgb}" | awk '{print $2}')"; tb="$(echo "${top_rgb}" | awk '{print $4}')"
br="$(echo "${bot_rgb}" | awk '{print $2}')"; bb="$(echo "${bot_rgb}" | awk '{print $4}')"
if [[ "${tr}" -gt 60 && $((tr > 2*tb)) -eq 1 && "${bb}" -gt 60 && $((bb > 2*br)) -eq 1 ]]; then
  ok "S0 基线：上红下蓝（R${tr}/B${tb} | R${br}/B${bb}）"
else
  bad "S0 基线不对：${s0}"
fi

echo "──────── S1 · 拦截场景：顶朝左 + 点按钮 → 渲染旋转 +90° ────────"
set_gravity "9.81:0:0"
sleep 1.5
if tap_rotate; then
  sleep 7   # 升级链两级共 ~4.2s 判失败后才启用兜底
  o1="$(surface_orientation)"
  [[ "${o1}" == "0" ]] && ok "S1 系统方向未变（ROTATION_0，请求确被拦截）" \
    || bad "S1 系统竟然转了（=${o1}）——拦截模拟失效，本用例前提不成立"
  shot "${OUT}/s1.png" || { bad "S1 截图失败"; }
  s1="$(sample_patch "${OUT}/s1.png" 0.25 0.5 left 0.75 0.5 right)"
  lr="$(echo "${s1}" | awk -F' \\| ' '{print $1}' | awk '{print $2}')"
  lb="$(echo "${s1}" | awk -F' \\| ' '{print $1}' | awk '{print $4}')"
  rr="$(echo "${s1}" | awk -F' \\| ' '{print $2}' | awk '{print $2}')"
  rb="$(echo "${s1}" | awk -F' \\| ' '{print $2}' | awk '{print $4}')"
  if [[ "${rr}" -gt 60 && $((rr > 2*rb)) -eq 1 && "${lb}" -gt 60 && $((lb > 2*lr)) -eq 1 ]]; then
    ok "S1 画面已旋转 +90°：右红左蓝（左 R${lr}/B${lb} | 右 R${rr}/B${rb}）"
  else
    bad "S1 画面没旋转：${s1}（期望右红左蓝）"
  fi
else
  bad "S1 找不到/点不到旋转按钮"
fi

echo "──────── S2 · 顶朝右 + 点按钮 → 渲染旋转 -90° ────────"
set_gravity "-9.81:0:0"
sleep 1.5
if tap_rotate; then
  sleep 7
  o2="$(surface_orientation)"
  [[ "${o2}" == "0" ]] && ok "S2 系统方向仍未变" \
    || bad "S2 系统方向变了（=${o2}）"
  shot "${OUT}/s2.png" || { bad "S2 截图失败"; }
  s2="$(sample_patch "${OUT}/s2.png" 0.25 0.5 left 0.75 0.5 right)"
  lr="$(echo "${s2}" | awk -F' \\| ' '{print $1}' | awk '{print $2}')"
  lb="$(echo "${s2}" | awk -F' \\| ' '{print $1}' | awk '{print $4}')"
  rr="$(echo "${s2}" | awk -F' \\| ' '{print $2}' | awk '{print $2}')"
  rb="$(echo "${s2}" | awk -F' \\| ' '{print $2}' | awk '{print $4}')"
  if [[ "${lr}" -gt 60 && $((lr > 2*lb)) -eq 1 && "${rb}" -gt 60 && $((rb > 2*rr)) -eq 1 ]]; then
    ok "S2 画面已旋转 -90°：左红右蓝（左 R${lr}/B${lb} | 右 R${rr}/B${rb}）"
  else
    bad "S2 画面方向不对：${s2}（期望左红右蓝）"
  fi
else
  bad "S2 找不到/点不到旋转按钮"
fi

echo "──────── S3 · 长按恢复 → 画面回到上红下蓝 ────────"
if long_press_rotate; then
  sleep 2.5
  shot "${OUT}/s3.png" || { bad "S3 截图失败"; }
  s3="$(sample_patch "${OUT}/s3.png" 0.5 0.42 top 0.5 0.58 bottom)"
  tr="$(echo "${s3}" | awk -F' \\| ' '{print $1}' | awk '{print $2}')"
  tb="$(echo "${s3}" | awk -F' \\| ' '{print $1}' | awk '{print $4}')"
  br="$(echo "${s3}" | awk -F' \\| ' '{print $2}' | awk '{print $2}')"
  bb="$(echo "${s3}" | awk -F' \\| ' '{print $2}' | awk '{print $4}')"
  if [[ "${tr}" -gt 60 && $((tr > 2*tb)) -eq 1 && "${bb}" -gt 60 && $((bb > 2*br)) -eq 1 ]]; then
    ok "S3 渲染旋转已撤除，画面复原上红下蓝"
  else
    bad "S3 画面没复原：${s3}"
  fi
else
  bad "S3 长按失败（找不到按钮）"
fi

echo "──────── 清理 ────────"
set_gravity "0:9.81:0"
a shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
a shell wm set-ignore-orientation-request false >/dev/null 2>&1

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
