#!/usr/bin/env bash
# ==========================================================================
# M7.5 · 内置 FFmpeg 音频软解（杜比/DTS 系）端到端验证（**只跑 release 包**）
#
# ## 背景
#   AC3 / E-AC-3 / DTS / TrueHD 是杜比/DTS 专利编码，安卓平台不自带解码器——
#   M7.5 前这类片"无声"且信息面板音频整行消失。M7.5 引入 NextLib（FFmpeg
#   扩展，EXTENSION_RENDERER_MODE_ON）后，平台没有的编码由内置 FFmpeg 软解兜底。
#
# ## 判据（反向验证）
#   夹具 = 宿主 ffmpeg 生成 60s H.264 + **E-AC-3** 音频 mp4。
#   模拟器上 E-AC-3 没有任何平台解码器（连 c2.android 都没有）：
#   · 旧代码（无 FFmpeg）：音频轨道不会被选中 → 无声 + 面板无「音频流」行；
#   · 新代码：FfmpegAudioRenderer 顶上 → 面板「实际解码器·音频」= ffmpeg（软解）。
#   P1 面板含「音频流」且标签为 E-AC-3；
#   P2 「实际解码器」音频一行含 ffmpeg 且标（软解）；
#   P3 面板**不含**"⚠ 本机无可用解码器（静音播放）"（该标记只在 FFmpeg 也
#      解不了时出现）。
#   反向：三条判据在 M7.4 及以前的包上全红。
#
# ## 稳定性关键（沿用 m7 的两条教训）
#   · 60 秒夹具：短片会中途 ENDED，控制条状态机不可控；
#   · 等"自然播完"的 ENDED 稳定态再 dump（暂停键/媒体键两条路都不稳）。
#
# 用法：scripts/m75-ffmpeg-audio-smoke.sh
# 前置：模拟器在跑；release 包已构建；宿主有 imageio-ffmpeg（生成夹具）。
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

XML="/tmp/m75.xml"
OUT="/tmp/m75-smoke"
mkdir -p "${OUT}"
OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

ui_dump() {
  a shell rm -f /sdcard/m75.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m75.xml >/dev/null 2>&1
  rm -f "${XML}"
  a shell cat /sdcard/m75.xml > "${XML}" 2>/dev/null
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

# ── 夹具：H.264 + E-AC-3，60 s（教训：短片会中途 ENDED）─────────────────
FIXTURE="${OUT}/m75-eac3.mp4"
DEV_VIDEO="/sdcard/Movies/m75-eac3.mp4"
make_fixture() {
  if [[ -s "${FIXTURE}" ]]; then
    echo "  · 复用本地夹具 ${FIXTURE}"
    return 0
  fi
  echo "  · 宿主 ffmpeg 生成 E-AC-3 夹具（60s）…"
  "${FF}" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc=size=960x540:rate=30:duration=60" \
    -f lavfi -i "sine=frequency=440:duration=60" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p \
    -c:a eac3 -b:a 192k -shortest "${FIXTURE}" || return 1
  # 自证夹具：音频流必须是 eac3，否则后面全白跑。
  # ⚠️ ffmpeg 只带 -i 探测时会 exit 1（"no output file"），pipefail 会把
  # grep 的成功吞掉——所以探测命令单独包一层吞掉它的退出码。
  ("${FF}" -hide_banner -i "${FIXTURE}" 2>&1 || true) | grep -q "Audio: eac3" || {
    echo "  ✗ 夹具音频流不是 eac3"; return 1
  }
  return 0
}

prepare() {
  echo "── 环境准备 ──"
  a install -r -g app/build/outputs/apk/release/app-arm64-v8a-release.apk >/dev/null 2>&1 \
    || { echo "  ✗ release 包安装失败"; return 1; }
  make_fixture || return 1
  a shell mkdir -p /sdcard/Movies >/dev/null 2>&1
  a push "${FIXTURE}" "${DEV_VIDEO}" >/dev/null 2>&1
  # 清数据防断点续播把起播位置压在片尾（m7 同款教训）。
  a shell pm clear "${APP_ID}" >/dev/null 2>&1
  a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  a shell am force-stop "${APP_ID}" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  echo "  · 已装包 / 推夹具 / 清数据放权限"
}

open_player() {
  a shell su 0 am start -n "${APP_ID}/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
      --esa preview.paths "local://${DEV_VIDEO}" --ei preview.index 0 >/dev/null 2>&1
  sleep 6
  [[ "$(top_activity)" == *VideoPlayerActivity* ]]
}

# 等自然播完的 ENDED 稳定态（m7 同款：零点击，静态界面）。
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

has_text() {
  ui_dump || return 1
  "${PY}" - "${XML}" "$1" <<'PYEOF'
import sys
xml, needle = sys.argv[1], sys.argv[2]
data = open(xml, encoding="utf-8", errors="replace").read()
sys.exit(0 if needle in data else 1)
PYEOF
}

open_info_panel() {
  local i
  for i in 1 2 3; do
    local xy
    xy="$(ui_dump && "${PY}" "${UI_SCRIPT}" center "${XML}" "desc=视频信息" 2>/dev/null | head -n1)" || { sleep 2; continue; }
    [[ -n "${xy}" ]] || { sleep 2; continue; }
    a shell input tap "$(printf '%s' "${xy}" | awk '{print $1}')" "$(printf '%s' "${xy}" | awk '{print $2}')" >/dev/null 2>&1
    sleep 2
    has_text "实际解码器" && return 0
  done
  return 1
}

echo "════════ M7.5 · 内置 FFmpeg 音频软解（release）════════"
[[ "$(a get-state 2>/dev/null)" == "device" ]] || { echo "✗ 模拟器不在跑"; exit 2; }

prepare || exit 1

echo
echo "──────── P0 · 播放页起来 + 稳定态 ────────"
open_player || { bad "P0 播放页没起来（$(top_activity)）"; exit 1; }
echo "  · 等待片子播完进入稳定态（≤180s）…"
wait_settled || { bad "P0 等不到稳定态"; exit 1; }
ok "P0 播放完成进入稳定态"

echo "──────── P1~P3 · 信息面板 FFmpeg 软解三判据 ────────"
open_info_panel || { bad "P0 信息面板弹不出来"; exit 1; }
a shell screencap -p /sdcard/m75-info.png >/dev/null 2>&1
a pull /sdcard/m75-info.png "${OUT}/m75-info.png" >/dev/null 2>&1

# P1 音频流行存在且标签为 E-AC-3（透明化：不再整行消失）。
if has_text "音频流" && has_text "E-AC-3"; then
  ok "P1 面板显示「音频流：E-AC-3」"
else
  bad "P1 面板没有「音频流/E-AC-3」（音频行又消失了？）"
fi

# P2 实际解码器·音频 = ffmpeg 系（软解）。
# ⚠️ FFmpeg 上报的解码器名带具体实现，形如 "ffmpegLavc63.1.101-eac3"
# （FfmpegLibrary.name() + lavc 解码器标识），所以只断言 "音频：ffmpeg" 前缀。
if has_text "音频：ffmpeg" && has_text "（软解）"; then
  ok "P2 「实际解码器·音频」= ffmpeg 系（软解）"
else
  bad "P2 音频解码器不是 ffmpeg（软解）——FFmpeg 扩展没接上？"
fi

# P3 无"静音播放"警示标记（FFmpeg 应能解 E-AC-3）。
if has_text "静音播放"; then
  bad "P3 出现了「静音播放」警示——FFmpeg 也没解出来？"
else
  ok "P3 无静音警示（音频正常渲染）"
fi

# 交叉证据：NextRenderersFactory 的加载日志（release 不镜像 MimiLog，
# 但 NextLib 用原生 Log.i 打加载信息，logcat 直出）。
sleep 1
if a logcat -d 2>/dev/null | grep -q "Loaded FfmpegAudioRenderer"; then
  ok "P4 logcat 交叉证据：NextRenderersFactory Loaded FfmpegAudioRenderer"
else
  bad "P4 logcat 没看到 FfmpegAudioRenderer 加载记录"
fi

echo
echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "${BAD}" -eq 0 ]]
