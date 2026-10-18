#!/usr/bin/env bash
# ==============================================================================
# 密密文件管理 · 冒烟脚本
#   1) 构建 debug APK   2) 启动/复用模拟器   3) 安装   4) 冷启动
#   5) 校验进程存活 + logcat 无 FATAL   6) 抓截图与启动耗时
#   7) 可选：桌面快捷方式端到端（pin → 确认 → 系统校验 → 回执 → 点回目标）
#
# ABI 已收敛为 arm64-v8a 单包，脚本仍保留「按设备 ABI 选包」逻辑以便将来扩 ABI。
#
# 用法：
#   scripts/smoke.sh                     # 全流程
#   scripts/smoke.sh --no-build          # 跳过构建，直接装已有产物
#   scripts/smoke.sh --serial emulator-5554
#   scripts/smoke.sh --boot-time         # 额外跑 5 次冷启动取中位数
#   scripts/smoke.sh --shortcut          # 额外验证桌面快捷方式全链路
# ==============================================================================
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/.cmfa-toolchain/android-sdk}"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
AVD_NAME="${AVD_NAME:-bench34}"

APP_ID="com.miyuetech.mimifiles.debug"
MAIN_ACTIVITY="com.miyuetech.mimifiles.splash.SplashActivity"
OUT_DIR="$PROJECT_DIR/build/smoke"

DO_BUILD=1
DO_BOOT_TIME=0
DO_SHORTCUT=0
SERIAL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)  DO_BUILD=0; shift ;;
    --boot-time) DO_BOOT_TIME=1; shift ;;
    --shortcut)  DO_SHORTCUT=1; shift ;;
    --serial)    SERIAL="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

log()  { printf '\033[1;36m[smoke]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[ OK  ]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[FAIL ]\033[0m %s\n' "$*"; }

# ── UI 自动化小工具（uiautomator dump + 坐标点击）───────────────────
ui_dump() { adb shell uiautomator dump /sdcard/mimi_ui.xml >/dev/null 2>&1 || true; }

# 返回匹配节点的中心坐标 "x y"；找不到返回非 0
node_center() { # $1 = 匹配串（resource-id / text 片段）
  local bounds n
  bounds="$(adb shell cat /sdcard/mimi_ui.xml 2>/dev/null | tr '<' '\n<' \
    | grep -F "$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -n1 || true)"
  [[ -z "$bounds" ]] && return 1
  n="$(printf '%s' "$bounds" | grep -oE '[0-9]+')"
  set -- $n
  printf '%s %s' "$(( ($1 + $3) / 2 ))" "$(( ($2 + $4) / 2 ))"
}

tap_node() { # $1 = 匹配串
  local xy
  xy="$(node_center "$1")" || return 1
  # shellcheck disable=SC2086
  adb shell input tap $xy
}

mkdir -p "$OUT_DIR"

# ── 1. 构建 ────────────────────────────────────────────────────────
if [[ $DO_BUILD -eq 1 ]]; then
  log "构建 debug APK ..."
  (cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug --console=plain -q)
  ok "构建完成"
fi

# ── 2. 设备 ────────────────────────────────────────────────────────
if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  log "无在线设备，启动模拟器 $AVD_NAME ..."
  "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-save -no-boot-anim -no-audio \
    -gpu swiftshader_indirect > "$OUT_DIR/emulator.log" 2>&1 &
  "$ADB" wait-for-device
  SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
adb() { "$ADB" -s "$SERIAL" "$@"; }

log "等待设备就绪 ($SERIAL) ..."
BOOT_WAIT=0
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2; BOOT_WAIT=$((BOOT_WAIT + 2))
  [[ $BOOT_WAIT -ge 240 ]] && { fail "设备启动超时"; exit 1; }
done
DEV_ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
ok "设备就绪：$SERIAL  (abi=$DEV_ABI, API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

# ── 3. 按设备 ABI 选包 ─────────────────────────────────────────────
APK_DIR="$PROJECT_DIR/app/build/outputs/apk/debug"
APK="$(find "$APK_DIR" -maxdepth 1 -name "*-${DEV_ABI}-debug.apk" | head -n 1)"
if [[ -z "$APK" ]]; then
  APK="$(find "$APK_DIR" -maxdepth 1 -name '*.apk' | sort | head -n 1)"
  warn "未找到匹配 $DEV_ABI 的包，回退到 $(basename "${APK:-无}")"
fi
[[ -z "${APK:-}" || ! -f "$APK" ]] && { fail "未找到可用 APK（${APK_DIR}）"; exit 1; }
ok "产物：$(basename "$APK")  ($(du -h "$APK" | cut -f1))"

# ── 4. 安装 ────────────────────────────────────────────────────────
log "安装 APK ..."
if ! adb install -r -t "$APK" > "$OUT_DIR/install.log" 2>&1; then
  cat "$OUT_DIR/install.log"; fail "安装失败"; exit 1
fi
ok "安装成功"

# ── 5. 冷启动 ──────────────────────────────────────────────────────
adb shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

log "冷启动 $MAIN_ACTIVITY ..."
START_OUT="$(adb shell am start -W -n "$APP_ID/$MAIN_ACTIVITY" 2>&1 || true)"
echo "$START_OUT" > "$OUT_DIR/start.txt"
echo "$START_OUT" | sed 's/^/    /'

TOTAL_TIME="$(echo "$START_OUT" | awk -F': ' '/TotalTime/ {print $2}' | tr -d '\r')"

sleep 3

# ── 6. 校验 ────────────────────────────────────────────────────────
PID="$(adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$PID" ]]; then
  fail "进程未存活（启动即崩溃）"
  adb logcat -d -b crash 2>/dev/null | tail -60
  exit 1
fi
ok "进程存活 (pid=$PID)"

adb logcat -d > "$OUT_DIR/logcat.txt" 2>/dev/null || true
if grep -qE "FATAL EXCEPTION" "$OUT_DIR/logcat.txt"; then
  fail "logcat 中发现 FATAL EXCEPTION"
  grep -A 25 -E "FATAL EXCEPTION" "$OUT_DIR/logcat.txt" | head -60
  exit 1
fi
ok "logcat 无 FATAL EXCEPTION"

TOP="$(adb shell dumpsys activity activities 2>/dev/null \
  | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -d '\r' || true)"
echo "$TOP" > "$OUT_DIR/top_activity.txt"
if echo "$TOP" | grep -q "DocumentsActivity"; then
  ok "前台界面 = DocumentsActivity"
else
  warn "前台界面未确认：${TOP:-（空）}"
fi

# ── 7. 截图 ────────────────────────────────────────────────────────
adb exec-out screencap -p > "$OUT_DIR/screenshot.png" 2>/dev/null || true
[[ -s "$OUT_DIR/screenshot.png" ]] && ok "截图：$OUT_DIR/screenshot.png"

# ── 8. 记忆（内存占用 + 可选多轮冷启动）────────────────────────────
MEM="$(adb shell dumpsys meminfo "$APP_ID" 2>/dev/null | awk '/TOTAL PSS/ {print $3; exit}' | tr -d '\r' || true)"
[[ -n "${MEM:-}" ]] && ok "常驻内存 PSS ≈ ${MEM} KB"
echo "pss_kb=${MEM:-unknown}" > "$OUT_DIR/meminfo.txt"

if [[ $DO_BOOT_TIME -eq 1 ]]; then
  log "冷启动计时 ×5 ..."
  TIMES=()
  for i in 1 2 3 4 5; do
    adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    T="$(adb shell am start -W -n "$APP_ID/$MAIN_ACTIVITY" 2>/dev/null \
      | awk -F': ' '/TotalTime/ {print $2}' | tr -d '\r')"
    TIMES+=("$T"); echo "    round $i: ${T} ms"
    sleep 1
  done
  MEDIAN="$(printf '%s\n' "${TIMES[@]}" | sort -n | sed -n '3p')"
  printf '%s\n' "${TIMES[@]}" > "$OUT_DIR/boot_times.txt"
  ok "冷启动中位数 = ${MEDIAN} ms（阈值 ≤ 400 ms）"
fi

# ── 9. 桌面快捷方式端到端（可选）──────────────────────────────────
# 这一段原本自己走一遍 pin → 确认 → 点回，但那是 M0 的写法：
# 它依赖当时界面上的演示按钮 `btn_add_to_desktop` 和一句早已删掉的
# 「由桌面快捷方式启动」文案，且把快捷方式 extras 的 path 当成裸路径传
# （真实契约是 `FsPath.toString()`，即 `local:///storage/emulated/0/…`）。
# M1 起这件事由 `scripts/m1f-smoke.sh` 的 T9 专职负责，且断言落在
# 文件系统与 `dumpsys shortcut` 上。这里只做委派，不再重复实现。
if [[ $DO_SHORTCUT -eq 1 ]]; then
  log "桌面快捷方式全链路交给 scripts/m1f-smoke.sh T9 ..."
  if "$PROJECT_DIR/scripts/m1f-smoke.sh" --variant debug --only T9; then
    ok "桌面快捷方式全链路通过（详见 build/m1f-smoke/）"
  else
    fail "桌面快捷方式全链路未通过（详见 build/m1f-smoke/）"
    exit 1
  fi
fi

echo
ok "冒烟通过  |  冷启动 TotalTime = ${TOTAL_TIME:-?} ms  |  证据目录：$OUT_DIR"
