#!/usr/bin/env bash
# ==========================================================================
# M7.13 · 字幕选择端到端验证（**只跑 release 包**）
#
# 覆盖四项：
#   S1 自动优选：内置三轨字幕（简体中文/繁體中文/English，中文两轨的 MKV
#      language 标签同为 chi）→ 起播自动选中「简体中文」。
#   S2 手动切换：控制条「字幕」按钮 → 弹窗列出 关闭字幕/简体中文/繁體中文/
#      English → 选「繁體中文」→ 重播后面板变繁體、字幕条仍有渲染。
#   S3 关闭字幕：弹窗选「关闭字幕」→ 重播后字幕条全暗 + 面板"已关闭"。
#   S4 换片语义：重开文件后自动优选重新命中简体（手动关闭只属于上一部）。
#
# ## 判据设计（前几版踩坑记录）
# · 字幕 cue 是 CanvasSubtitleOutput 画的，**不进 uiautomator 树** ——
#   "读画面字幕文本"此路不通。
# · 双判据：截图亮像素（渲染事实）+ 信息面板「字幕：」行（选轨身份）。
#   夹具是纯黑视频，cue 判据区域实测标定：开 2.77% / 关 0.00%，零竞态。
#   ⚠️ cue 不在屏幕底部：16:9 视频 fit 后视频带 y 896-1504，cue 渲染在
#   视频带下缘 y≈1433 —— 按"底部字幕条"直觉取 y2150+ 会切到控制条/手势条。
# · 信息面板**只在 ENDED 态读**：播放中控制条 5s 自动隐藏、dump ~7.5s，
#   定位+点击全程竞态。ENDED 态控制条不自动隐藏，同 dump 定位+点击是稳的。
# · 返回键只在确认弹窗已打开（dump 里有标题）时才按 —— 盲按会把播放页退掉。
# · 唤醒点 (540,700)：ENDED 态大播放键实测在 (539,1199)，用屏幕正中唤醒
#   会误触重播，产生"关闭字幕后还在渲染"的假象。
#
# 用法：scripts/m713-subtitle-smoke.sh
# 前置：模拟器在跑；release 包已构建；宿主有 imageio-ffmpeg + Pillow。
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

XML="/tmp/m713.xml"
OUT="/tmp/m713-smoke"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

ui_dump() {
  a shell rm -f /sdcard/m713.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m713.xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/m713.xml > "${XML}" 2>/dev/null
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

locate_in_xml() {
  "${PY}" "${UI_SCRIPT}" center "${XML}" "$1" 2>/dev/null | head -n1
}

has_text() {
  "${PY}" - "$1" "${XML}" <<'PYEOF'
import sys
src = open(sys.argv[2], errors='ignore').read()
print('yes' if f'text="{sys.argv[1]}"' in src else 'no')
PYEOF
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
    # 唤醒点必须在 (540,700)：ENDED 态大播放键就在 (539,1199) —— 实测坐标，
    # 用屏幕正中 (540,1200) 唤醒会直接把视频重播了（本脚本第四版的混根源）。
    a shell input tap 540 700 >/dev/null 2>&1
    sleep 1.2
  done
  return 1
}

# 等 ENDED 稳定态（desc="Play" 出现且控制条可见）；成功后 ${XML} 即当前稳定态
wait_ended() {
  local i
  for i in $(seq 1 20); do
    if dump_controller && grep -q 'desc="Play"' "${XML}"; then return 0; fi
    sleep 3
  done
  return 1
}

# ── 夹具：MKV，30s 纯黑视频 + 三轨 SubRip 字幕 ────────────────
# 简体/繁体两轨 language 都写 chi（真实片源常态），轨道名分别是
# 简体中文/繁體中文/English。简体轨封装在前：打分打平时按顺序取先，
# 无论 label 读没读到，自动优选都必须命中简体。
# cue 每秒一条、首尾相接铺满 30s —— 播放中任意时刻截图都有 cue 在渲染。
make_fixture() {
  local out="$1"
  rm -f "${out}" "${OUT}/sim.srt" "${OUT}/tra.srt" "${OUT}/eng.srt"
  for i in $(seq 1 30); do
    b=$(( (i - 1) / 60 )); r=$(( (i - 1) % 60 ))
    t1=$(printf '00:%02d:%02d,000' "$b" "$r")
    e=$(( i / 60 )); r2=$(( i % 60 ))
    t2=$(printf '00:%02d:%02d,000' "$e" "$r2")
    printf '%d\n%s --> %s\n简体字幕测试 第%d条\n\n' "$i" "$t1" "$t2" "$i" >> "${OUT}/sim.srt"
    printf '%d\n%s --> %s\n繁體字幕測試 第%d條\n\n' "$i" "$t1" "$t2" "$i" >> "${OUT}/tra.srt"
    printf '%d\n%s --> %s\nSUBTITLE TEST LINE %d\n\n' "$i" "$t1" "$t2" "$i" >> "${OUT}/eng.srt"
  done
  "${FF}" -hide_banner -loglevel error -y \
    -f lavfi -i "color=c=black:s=640x360:rate=25:duration=30" \
    -f lavfi -i "sine=frequency=440:duration=30" \
    -i "${OUT}/sim.srt" -i "${OUT}/tra.srt" -i "${OUT}/eng.srt" \
    -map 0:v -map 1:a -map 2 -map 3 -map 4 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p \
    -c:a aac -c:s srt \
    -metadata:s:s:0 title="简体中文" -metadata:s:s:0 language=chi \
    -metadata:s:s:1 title="繁體中文" -metadata:s:s:1 language=chi \
    -metadata:s:s:2 title="English" -metadata:s:s:2 language=eng \
    "${out}"
}

open_player() { # $1=设备上的视频路径
  a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
      --esa preview.paths "local://$1" --ei preview.index 0 >/dev/null 2>&1
  sleep 5
  [[ "$(top_activity)" == *VideoPlayerActivity* ]]
}

# 字幕 cue 亮像素占比（%）。夹具是 16:9 黑视频，fit 进 1080x2400 后视频带
# y 896-1504，cue 渲染在视频带下缘（实测 ref3.png：cue 文本中心 y≈1433）。
# 判据区域 (150,1380)-(930,1520) 只含 cue 文本：ENDED/关闭时实测 0.00%，
# 简体开 2.77% —— 三个真实截图标定，零竞态。阈值取 0.5 / 0.1。
sub_strip_ratio() {
  a shell screencap -p /sdcard/m713-shot.png >/dev/null 2>&1
  a pull /sdcard/m713-shot.png "${OUT}/shot.png" >/dev/null 2>&1
  "${PY}" - "${OUT}/shot.png" <<'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB').crop((150, 1380, 930, 1520))
px = list(im.getdata())
bright = sum(1 for p in px if min(p) > 100)
print(f"{bright * 100.0 / len(px):.2f}")
PYEOF
}

ratio_gt() { (( $(echo "$1 > $2" | bc -l 2>/dev/null || echo 0) )); }
ratio_lt() { (( $(echo "$1 < $2" | bc -l 2>/dev/null || echo 1) )); }

# ENDED 态（调用前须 wait_ended 成功）打开信息面板读「字幕：」行。
# 稳定态操作无竞态；读完仅在确认弹窗开着时按返回键关它。
info_subtitle_ended() {
  local xy
  xy="$(locate_in_xml "desc=视频信息")"
  [[ -n "${xy}" ]] || return 1
  tap_xy "${xy}"
  sleep 2
  ui_dump || return 1
  "${PY}" - "${XML}" <<'PYEOF'
import sys, re
src = open(sys.argv[1], errors='ignore').read()
m = re.search(r'字幕：([^"&#]*)', src)
print(m.group(1) if m else '')
PYEOF
  # 只在确认弹窗已打开时才关 —— 盲按返回会退掉播放页（S2d 级联教训）
  if [[ "$(has_text "视频信息")" == "yes" ]]; then
    a shell input keyevent 4 >/dev/null 2>&1
    sleep 1
  fi
  return 0
}

replay_from_ended() { # ENDED 态下点播放键重播
  local xy
  xy="$(locate_in_xml "desc=Play")"
  [[ -n "${xy}" ]] || return 1
  tap_xy "${xy}"
  return 0
}

echo "════════ M7.13 · 字幕选择（release）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

echo "── 环境准备 ──"
a install -r -g app/build/outputs/apk/release/app-arm64-v8a-release.apk >/dev/null 2>&1 \
  || { echo "  ✗ release 包安装失败"; exit 1; }
make_fixture "${OUT}/m713-sub.mkv" || { echo "  ✗ 夹具生成失败"; exit 1; }
a shell mkdir -p /sdcard/Movies >/dev/null 2>&1
a push "${OUT}/m713-sub.mkv" /sdcard/Movies/m713-sub.mkv >/dev/null 2>&1
a shell pm clear "${APP_ID}" >/dev/null 2>&1
a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
a shell am force-stop "${APP_ID}" >/dev/null 2>&1
echo "  · 已装包 / 推夹具 / 清数据放权限"

echo "──────── S1 · 自动优选：简体中文 ────────"
open_player /sdcard/Movies/m713-sub.mkv || { bad "S1 播放页没起来（$(top_activity)）"; exit 1; }
sleep 6.5   # 播放中、控制条已隐藏时拍
r="$(sub_strip_ratio)"
if ratio_gt "$r" 0.5; then
  ok "S1a 字幕条有渲染（亮像素 ${r}%，黑画面下只能是 cue 文本）"
else
  bad "S1a 字幕条全暗（${r}%）—— cue 没渲染"
fi
if ! wait_ended; then bad "S1 等不到 ENDED 稳定态"; exit 1; fi
sub_info="$(info_subtitle_ended || true)"
if [[ "${sub_info}" == "简体中文" ]]; then
  ok "S1b 信息面板：字幕：简体中文（自动优选命中简体）"
else
  bad "S1b 信息面板读到「${sub_info:-（空）}」，不是简体中文"
  cp "${XML}" "${OUT}/s1-fail.xml"
fi

echo "──────── S2 · 手动切换：繁體中文 ────────"
# 上一节关信息弹窗后 ${XML} 还是弹窗的旧 dump，先刷新到控制条稳定态
dump_controller || { bad "S2 控制条唤不出来"; exit 1; }
sub_xy="$(locate_in_xml "desc=字幕")"
if [[ -z "${sub_xy}" ]]; then
  bad "S2 找不到字幕按钮（desc=字幕）"
  cp "${XML}" "${OUT}/s2-nobtn.xml"
  exit 1
fi
tap_xy "${sub_xy}"
sleep 2
ui_dump || { bad "S2 弹窗后 dump 失败"; exit 1; }
if [[ "$(has_text "关闭字幕")" == "yes" && "$(has_text "简体中文")" == "yes" \
   && "$(has_text "繁體中文")" == "yes" && "$(has_text "English")" == "yes" ]]; then
  ok "S2a 弹窗列出 关闭字幕 + 简体中文 + 繁體中文 + English（轨道名→label 端到端）"
else
  bad "S2a 弹窗条目不全（dump 在 ${XML}）"
  cp "${XML}" "${OUT}/s2a-fail.xml"
fi
tra_xy="$(locate_in_xml "text=繁體中文")"
[[ -n "${tra_xy}" ]] || { bad "S2b 找不到「繁體中文」条目"; exit 1; }
tap_xy "${tra_xy}"
sleep 1.5
dump_controller || { bad "S2c 控制条唤不出来"; exit 1; }   # 弹窗旧 dump 刷新
replay_from_ended || { bad "S2c 重播失败"; exit 1; }
sleep 6.5
r="$(sub_strip_ratio)"
if ratio_gt "$r" 0.5; then
  ok "S2c 繁體字幕在渲染（亮像素 ${r}%）"
else
  bad "S2c 字幕条全暗（${r}%）—— 手动选轨没生效？"
fi
if ! wait_ended; then bad "S2d 等不到 ENDED 稳定态"; exit 1; fi
sub_info="$(info_subtitle_ended || true)"
if [[ "${sub_info}" == "繁體中文" ]]; then
  ok "S2d 信息面板：字幕：繁體中文"
else
  bad "S2d 信息面板读到「${sub_info:-（空）}」，不是繁體中文"
  cp "${XML}" "${OUT}/s2d-fail.xml"
fi

echo "──────── S3 · 关闭字幕 ────────"
dump_controller || { bad "S3 控制条唤不出来"; exit 1; }
sub_xy="$(locate_in_xml "desc=字幕")"
[[ -n "${sub_xy}" ]] || { bad "S3 找不到字幕按钮"; exit 1; }
tap_xy "${sub_xy}"
sleep 2
ui_dump || { bad "S3 弹窗后 dump 失败"; exit 1; }
off_xy="$(locate_in_xml "text=关闭字幕")"
[[ -n "${off_xy}" ]] || { bad "S3 找不到「关闭字幕」条目"; exit 1; }
tap_xy "${off_xy}"
sleep 1.5
dump_controller || { bad "S3 控制条唤不出来"; exit 1; }   # 弹窗旧 dump 刷新
replay_from_ended || { bad "S3 重播失败"; exit 1; }
sleep 6.5
r="$(sub_strip_ratio)"
if ratio_lt "$r" 0.1; then
  ok "S3a 字幕条全暗（${r}%）—— 关闭生效"
else
  bad "S3a 字幕条仍有亮像素（${r}%）—— 没关掉"
fi
if ! wait_ended; then bad "S3b 等不到 ENDED 稳定态"; exit 1; fi
sub_info="$(info_subtitle_ended || true)"
if [[ "${sub_info}" == "已关闭" ]]; then
  ok "S3b 信息面板：字幕：已关闭"
else
  bad "S3b 信息面板读到「${sub_info:-（空）}」，不是已关闭"
  cp "${XML}" "${OUT}/s3-fail.xml"
fi

echo "──────── S4 · 换片语义：重开文件后自动优选重新生效 ────────"
a shell am force-stop "${APP_ID}" >/dev/null 2>&1
open_player /sdcard/Movies/m713-sub.mkv || { bad "S4 重开播放页没起来"; exit 1; }
if ! wait_ended; then bad "S4 等不到 ENDED 稳定态"; exit 1; fi
sub_info="$(info_subtitle_ended || true)"
if [[ "${sub_info}" == "简体中文" ]]; then
  ok "S4 重开后自动优选再次命中简体（上一次的手动关闭不带到新片）"
else
  bad "S4 信息面板读到「${sub_info:-（空）}」，不是简体中文"
  cp "${XML}" "${OUT}/s4-fail.xml"
fi

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
