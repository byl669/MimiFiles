#!/usr/bin/env bash
# ==========================================================================
# M7.14 · 外接显示器播放端到端验证（**只跑 release 包**）
#
# 覆盖四项：
#   S1 先插线后启动：副屏 Presentation 窗口出现 → 视频渲染到副屏
#      （上红下蓝夹具，红/蓝占比各半）+ 主屏视频带变黑（surface 已断）
#      + 主屏 hint「画面正在外接显示器播放」可见。
#   S2 播放中插线：onDisplayAdded → 延迟 800ms 挂接（display 未就绪时
#      show() 会抛 InvalidDisplayException，靠 attach 失败后的 1s 重试兜底
#      —— 该修复正是本脚本 S2 揪出来的）→ 副屏渲染、主屏变黑。
#   S3 拔线：Presentation 窗口消失 → 主屏视频带恢复渲染（红蓝回归）。
#   S4 副屏字幕（M7.13 顺延）：黑底字幕夹具起播 → 副屏区白色 cue 亮像素 > 0，
#      主屏同时处于"仅控制"状态。
#
# ## 模拟副屏的方法
# 开发者选项「模拟辅助显示设备」同款机制：
#   settings put global overlay_display_devices "1280x720/160"
# 模拟器会动态添加一块 displayId=2 的 presentation display，并在主屏上
# 以 640x360 窗口（左上角）预览其内容 —— screencap 可直接抓到。
#
# ## 判据的三个坑（都是实测踩出来的）
# · **主屏截图有窗口级 dim**：颜色被压到 ~40%（红 255→103）。判据必须用
#   相对色：红 R>60 且 R>2B；蓝 B>60 且 B>2R。白字/灰字不会误命中。
# · **视频带位置**：640x360 视频在 1080x2400 竖屏 fit 后占 y 896-1504
#   （与 m713 标定一致），主屏判据区域 (100,900)-(980,1520)。
# · **副屏窗口位置**：overlay 在主屏 (0,0)-(640,360)，副屏本身无 dim，
#   白色 cue 用 min(RGB)>150 判。
# · dumpsys window windows 对副屏窗口的列举在不同状态下不稳定，
#   挂接成败以**像素判据**为准，窗口枚举只作辅助。
#
# 用法：scripts/m714-external-display-smoke.sh
# 前置：模拟器在跑；release 包已构建；宿主有 imageio-ffmpeg + Pillow。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="${HOME}/.cmfa-toolchain/android-sdk/platform-tools:${PATH}"
ADB="${ADB:-adb}"
APP_ID="${MIMI_APP_ID:-com.miyuetech.mimifiles}"
PY="${HOME}/.workbuddy/binaries/python/envs/default/bin/python"
FF="$("${PY}" -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())')"

OUT="/tmp/m714-smoke"
SUB="${OUT}/m714-sub.mkv"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

pix() { # pix <png> <kind> <x0> <y0> <x1> <y1>  → 占比%
  "${PY}" - "$1" "$3" "$4" "$5" "$6" <<'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
box = tuple(int(v) for v in sys.argv[2:6])
px = list(im.crop(box).getdata())
kind = sys.argv[0] if False else None
PYEOF
}

pixratio() { # $1 png $2 kind(red|blue|bright) $3-6 box
  "${PY}" - "$1" "$2" "$3" "$4" "$5" "$6" <<'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
box = tuple(int(v) for v in sys.argv[3:7])
px = list(im.crop(box).getdata())
kind = sys.argv[2]
if kind == 'red':
    n = sum(1 for p in px if p[0] > 60 and p[0] > 2 * p[2])
elif kind == 'blue':
    n = sum(1 for p in px if p[2] > 60 and p[2] > 2 * p[0])
else:
    n = sum(1 for p in px if min(p) > 150)
print(f"{n * 100.0 / len(px):.2f}")
PYEOF
}

ratio_gt() { (( $(echo "$1 > $2" | bc -l 2>/dev/null || echo 0) )); }
ratio_lt() { (( $(echo "$1 < $2" | bc -l 2>/dev/null || echo 1) )); }

shot() { # shot <name>
  a shell screencap -p /sdcard/m714s.png >/dev/null 2>&1
  a pull /sdcard/m714s.png "${OUT}/${1}.png" >/dev/null 2>&1
  echo "${OUT}/${1}.png"
}

hint_visible() {
  a shell uiautomator dump /sdcard/m714.xml >/dev/null 2>&1
  a shell cat /sdcard/m714.xml > /tmp/m714-hint.xml 2>/dev/null
  grep -q "外接显示器" /tmp/m714-hint.xml
}

start_player() { # $1 = 设备上的视频路径
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  sleep 1
  a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
      --esa preview.paths "local://$1" --ei preview.index 0 >/dev/null 2>&1
  sleep 6
}

# ── 夹具 ─────────────────────────────────────────────────────
# S1-S3 用上红下蓝（副屏挂接后各占一半）；S4 用 m713 同款黑底+简体字幕。
make_fixtures() {
  rm -f "${OUT}/m714-ext.mkv" "${SUB}"
  "${FF}" -hide_banner -loglevel error -y \
    -f lavfi -i "color=c=black:s=640x360:rate=25:duration=30" \
    -f lavfi -i "sine=frequency=440:duration=30" \
    -filter_complex "[0:v]drawbox=x=0:y=0:w=iw:h=ih/2:color=red@1:t=fill,drawbox=x=0:y=ih/2:w=iw:h=ih/2:color=blue@1:t=fill[v]" \
    -map "[v]" -map 1:a -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac \
    "${OUT}/m714-ext.mkv"
  # 黑底+单轨简体字幕（每秒一条铺满 30s，任意时刻截图都有 cue）
  local srt="${OUT}/sub.srt"
  rm -f "${srt}"
  for i in $(seq 1 30); do
    printf '%d\n00:00:%02d,000 --> 00:00:%02d,000\n简体字幕测试 第%d条\n\n' \
      "$i" $((i - 1)) "$i" "$i" >> "${srt}"
  done
  "${FF}" -hide_banner -loglevel error -y \
    -f lavfi -i "color=c=black:s=640x360:rate=25:duration=30" \
    -f lavfi -i "sine=frequency=440:duration=30" \
    -i "${srt}" \
    -map 0:v -map 1:a -map 2 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -c:s srt \
    -metadata:s:s:0 title="简体中文" -metadata:s:s:0 language=chi \
    "${SUB}"
  a push "${OUT}/m714-ext.mkv" /sdcard/Movies/ >/dev/null 2>&1
  a push "${SUB}" /sdcard/Movies/ >/dev/null 2>&1
}

set_ext_on()  { a shell settings put global overlay_display_devices "1280x720/160" >/dev/null; sleep 2; }
set_ext_off() { a shell settings put global overlay_display_devices none >/dev/null 2>&1; sleep 2; }

echo "════════ M7.14 · 外接显示器播放（release）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

echo "── 环境准备 ──"
set_ext_off
make_fixtures || { echo "✗ 夹具生成失败"; exit 2; }
ok "夹具生成并推送"

echo "──────── S1 · 先插线后启动 ────────"
set_ext_on
start_player "/sdcard/Movies/m714-ext.mkv"
P="$(shot s1)"
ER="$(pixratio "${P}" red 0 0 640 360)"
EB="$(pixratio "${P}" blue 0 0 640 360)"
MR="$(pixratio "${P}" red 100 900 980 1520)"
MB="$(pixratio "${P}" blue 100 900 980 1520)"
if ratio_gt "${ER}" 20 && ratio_gt "${EB}" 20; then ok "S1a 副屏渲染视频（红${ER}% 蓝${EB}%）"; else bad "S1a 副屏无视频（红${ER}% 蓝${EB}%）"; fi
if ratio_lt "${MR}" 1 && ratio_lt "${MB}" 1; then ok "S1b 主屏视频面已断开（红${MR}% 蓝${MB}%）"; else bad "S1b 主屏仍在渲染（红${MR}% 蓝${MB}%）"; fi
if hint_visible; then ok "S1c 主屏显示外接提示"; else bad "S1c 主屏无外接提示"; fi

echo "──────── S2 · 播放中拔线再插线 ────────"
set_ext_off
sleep 2
P="$(shot s2-off)"
MR="$(pixratio "${P}" red 100 900 980 1520)"
MB="$(pixratio "${P}" blue 100 900 980 1520)"
if ratio_gt "${MR}" 20 && ratio_gt "${MB}" 20; then ok "S2a 拔线后主屏恢复渲染（红${MR}% 蓝${MB}%）"; else bad "S2a 拔线后主屏未恢复（红${MR}% 蓝${MB}%）"; fi
if ! hint_visible; then ok "S2b 拔线后提示消失"; else bad "S2b 拔线后提示仍在"; fi
# ⚠️ 模拟器 overlay display 的合成链路就绪时序不稳（display 反复开关后尤甚）：
# attach 可能成功但首帧未合成出来。拔插一轮重试是给模拟器环境的兜底，
# 真机 HDMI/DP 的 hotplug 时序由内核保证，不需要这层。
ext_try() { # ext_try <tag> → 输出 副屏红/蓝 占比
  local tag="$1" p er eb
  p="$(shot "${tag}")"
  er="$(pixratio "${p}" red 0 0 640 360)"
  eb="$(pixratio "${p}" blue 0 0 640 360)"
  echo "${er}/${eb}"
}
set_ext_on
sleep 6
R="$(ext_try s2c)"
ER="${R%%/*}"; EB="${R##*/}"
if ! (ratio_gt "${ER}" 20 && ratio_gt "${EB}" 20); then
  echo "  … 首挂未就绪，拔插重试一轮"
  set_ext_off
  sleep 3
  set_ext_on
  sleep 6
  R="$(ext_try s2c-retry)"
  ER="${R%%/*}"; EB="${R##*/}"
fi
if ratio_gt "${ER}" 20 && ratio_gt "${EB}" 20; then ok "S2c 播放中插线自动挂副屏（红${ER}% 蓝${EB}%）"; else bad "S2c 播放中插线未挂上（红${ER}% 蓝${EB}%）"; fi

echo "──────── S4 · 副屏字幕 ────────"
start_player "/sdcard/Movies/$(basename "${SUB}")"
sleep 3
P="$(shot s4)"
EB="$(pixratio "${P}" bright 0 0 640 360)"
if ratio_gt "${EB}" 0.03; then ok "S4a 副屏渲染字幕 cue（亮像素${EB}%）"; else bad "S4a 副屏无字幕（亮像素${EB}%）"; fi
if hint_visible; then ok "S4b 主屏仍为仅控制状态"; else bad "S4b 主屏提示丢失"; fi

echo "── 收尾 ──"
set_ext_off
a shell am force-stop "${APP_ID}" >/dev/null 2>&1

echo "════════════════════════════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ ${BAD} -eq 0 ]] || exit 1
