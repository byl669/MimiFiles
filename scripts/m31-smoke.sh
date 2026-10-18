#!/usr/bin/env bash
# ==============================================================================
# 密密文件管理 · M3.1.1 修复验证（四组用例：V / G / P / T）
#
# ## 这一版冒烟要回答的问题
# M3.1 真机测出四条问题，它们的**共同点是"没法只靠 UI dump 判定"**：
#
# | 用例 | 现象 | 为什么必须用这种证据 |
# |---|---|---|
# | V | 横屏按钮与"上一个/下一个"挤在一起 | 要看**几何**：谁挨着谁。中心点距离不够，必须比边界 |
# | G | 翻几页后照片变成单色色块 | UI dump 里 1 像素的位图与正常画面**完全一样**，只有数像素颜色数才分得开 |
# | P | 捏合/平移时抖动闪频 | 双指捏合没法用 `input` 模拟，但"平移被算了两次"用单指拖动就能对账 |
# | T | 双栏右栏看得见却点不动 | 只有"操作之后界面状态真的变了"才算证据 |
#
# 因此判据分别是：**控件矩形相邻性**、**截图取样区的独立颜色数**、**手势位移对账**、
# **点击/滚动后的界面状态变化**。每一条都能在修复前的版本上复现失败（见交付报告）。
#
# ## 用法
#   scripts/m31-smoke.sh                 # 全部
#   scripts/m31-smoke.sh --only G1,G2    # 只跑指定项
#   scripts/m31-smoke.sh --keep-data     # 跑完不清 app 数据
#
# ## 兼容性
# 必须能在 macOS 自带的 bash 3.2 上跑（与 m2/m3 冒烟同一套约束）：
#   · 变量名不能紧跟中文；· 双引号命令替换 + 内联转义引号 + 后接 `||` 会让 3.2 报错，
#   统一写成 `xy="$(...)" || return 1`。
# ==============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/.cmfa-toolchain/android-sdk}"
ADB="$SDK_DIR/platform-tools/adb"
VENV_PY="${VENV_PY:-$HOME/.workbuddy/binaries/python/envs/default/bin/python}"

VARIANT="debug"
ONLY=""
KEEP_DATA=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant)   VARIANT="$2"; shift 2 ;;
    --only)      ONLY="$2";    shift 2 ;;
    --keep-data) KEEP_DATA=1;  shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

if [[ "$VARIANT" == "debug" ]]; then
  APP_ID="com.miyuetech.mimifiles.debug"
else
  APP_ID="com.miyuetech.mimifiles"
fi
ENTRY="$APP_ID/com.miyuetech.mimifiles.splash.SplashActivity"
PREVIEW="$APP_ID/com.miyuetech.mimifiles.feature.preview.PreviewActivity"
PLAYER="$APP_ID/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity"

SERIAL="${SERIAL:-emulator-5554}"
DEV_DIR="/sdcard/MimiM3"
OUT_DIR="$PROJECT_DIR/build/m31-smoke"
FIX_DIR="$PROJECT_DIR/build/m31-fixtures"
UI_DEV="/sdcard/m31_ui.xml"
UI_XML="$OUT_DIR/_ui.xml"
SHOT="$OUT_DIR/_shot.png"
UI_PY="$VENV_PY"
CHECK_PY="$PROJECT_DIR/scripts/m31-checks.py"
FIXTURES="$PROJECT_DIR/scripts/m3-fixtures.py"
# "G" 组的判据：正常渲染的多色素材在取样区里至少这么多独立颜色；
# 退化成 1×1 位图铺满屏幕时只有 1（实测纯色素材 = 1，多色素材 = 9~10）。
MIN_COLORS=4
# "P" 组允许的位移误差：手指走多少、内容就该走多少，比例必须落在 [1-e, 1+e]。
PAN_TOLERANCE=0.20

mkdir -p "$OUT_DIR"

# ── 统计 ────────────────────────────────────────────────────────────────────
PASS=0
FAIL=0
SKIP=0
FAILED_ITEMS=""

step() { printf '\n\033[1;36m════ %s ════\033[0m\n' "$1"; }
ok()   { PASS=$(( PASS + 1 )); printf '  \033[32m[ OK ]\033[0m %s\n' "$1"; }
bad()  { FAIL=$(( FAIL + 1 )); FAILED_ITEMS="${FAILED_ITEMS}${FAILED_ITEMS:+$'\n'}  - $1"
         printf '  \033[31m[FAIL]\033[0m %s\n' "$1"; }
skip() { SKIP=$(( SKIP + 1 )); printf '  \033[33m[SKIP]\033[0m %s\n' "$1"; }
note() { printf '       %s\n' "$1"; }

want() {
  [[ -z "$ONLY" ]] && return 0
  case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac
}

a() { "$ADB" -s "$SERIAL" "$@"; }

# ── UI 辅助（定位器统一走 scripts/m2-ui.py，见那里的匹配规则说明）────────────

ui_dump() {
  local tries=0
  while [[ $tries -lt 3 ]]; do
    if a shell uiautomator dump "$UI_DEV" >/dev/null 2>&1; then
      if a shell cat "$UI_DEV" > "$UI_XML.tmp" 2>/dev/null && [[ -s "$UI_XML.tmp" ]]; then
        mv "$UI_XML.tmp" "$UI_XML"
        return 0
      fi
    fi
    tries=$(( tries + 1 ))
    sleep 0.4
  done
  return 1
}

# 取控件矩形：rect_of <spec> → 输出 "x1 y1 x2 y2"；找不到返回 1
# spec 里可以用 `under:<rid>/` 前缀限定子树，例如 `under:file_list/text=MimiM3`
rect_of() {
  local spec="$1" under="" tries=0 out
  if [[ "$spec" == under:* ]]; then
    under="${spec%%/*}"
    spec="${spec#*/}"
  fi
  while [[ $tries -lt 4 ]]; do
    ui_dump || true
    if [[ -n "$under" ]]; then
      out="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" rect "$UI_XML" "$under" "$spec" 2>/dev/null | head -n1)"
    else
      out="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" rect "$UI_XML" "$spec" 2>/dev/null | head -n1)"
    fi
    if [[ -n "$out" ]]; then
      printf '%s\n' "$out"
      return 0
    fi
    tries=$(( tries + 1 ))
    sleep 0.5
  done
  return 1
}

center_of() {
  local rect out
  rect="$(rect_of "$1")" || return 1
  out="$(printf '%s\n' "$rect" | awk '{print int(($1+$3)/2)" "int(($2+$4)/2)}')"
  printf '%s\n' "$out"
}

tap_spec() { # $1=spec $2=等待秒
  local xy x y
  xy="$(center_of "$1")" || return 1
  x="$(printf '%s\n' "$xy" | awk '{print $1}')"
  y="$(printf '%s\n' "$xy" | awk '{print $2}')"
  a shell input tap "$x" "$y" >/dev/null 2>&1
  sleep "${2:-1.2}"
  return 0
}

text_of() { # $1=spec → 打印该控件的 text
  local spec="$1" under=""
  if [[ "$spec" == under:* ]]; then
    under="${spec%%/*}"
    spec="${spec#*/}"
  fi
  ui_dump || return 1
  if [[ -n "$under" ]]; then
    "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" text-of "$UI_XML" "$under" "$spec" 2>/dev/null | head -n1
  else
    "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" text-of "$UI_XML" "$spec" 2>/dev/null | head -n1
  fi
}

texts_of() { # $1=spec → 打印所有匹配控件的 text（每行一个）
  local spec="$1" under=""
  if [[ "$spec" == under:* ]]; then
    under="${spec%%/*}"
    spec="${spec#*/}"
  fi
  ui_dump || return 1
  if [[ -n "$under" ]]; then
    "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" texts "$UI_XML" "$under" "$spec" 2>/dev/null
  else
    "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" texts "$UI_XML" "$spec" 2>/dev/null
  fi
}

# 取样区域的独立颜色数（默认取屏幕中间 60% 宽 / 20% 高，避开 fit 布局的黑边）
shot_colors() {
  local region="${1:-0.2,0.4,0.8,0.6}"
  a exec-out screencap -p > "$SHOT" 2>/dev/null
  "$UI_PY" "$CHECK_PY" colors "$SHOT" "$region" 2>/dev/null | sed 's/colors=\([0-9]*\).*/\1/'
}

top_activity() {
  a shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | grep -m1 "topResumedActivity" | sed 's/.*u0 \([^ ]*\).*/\1/'
}

wait_activity() { # $1=期望的 activity 全名片段 $2=超时秒
  local i=0
  while [[ $i -lt $(( ${2:-10} * 2 )) ]]; do
    if top_activity | grep -qF "$1"; then return 0; fi
    sleep 0.5
    i=$(( i + 1 ))
  done
  return 1
}

app_log() { # $1=标签 → 落盘 build/m31-smoke/log-<标签>.txt
  local pid
  pid="$(a shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -z "$pid" ]]; then
    printf '       （应用进程不在，取不到日志）\n'
    return 1
  fi
  a logcat -d --pid "$pid" 2>/dev/null | tr -d '\r' > "$OUT_DIR/log-$1.txt"
  printf '       应用日志 → log-%s.txt\n' "$1"
}

crash_check() { # $1=标签；返回 0 = 没有崩溃
  local crashes
  crashes="$(a logcat -d 2>/dev/null | tr -d '\r' | grep -c "FATAL EXCEPTION")"
  if [[ "${crashes:-0}" -gt 0 ]]; then
    a logcat -d 2>/dev/null | tr -d '\r' | grep -A 12 "FATAL EXCEPTION" | tail -30 \
      > "$OUT_DIR/crash-$1.txt"
    return 1
  fi
  return 0
}

launch_preview() { # $1=逗号分隔的 FsPath 列表 $2=index
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  a shell am start -n "$PREVIEW" --esa preview.paths "$1" --ei preview.index "$2" \
    >/dev/null 2>&1
  wait_activity "PreviewActivity" 12 || true
  sleep 2.0
}

gallery_paths() { # 8 张多色素材的 FsPath 列表
  local list="" i
  for i in 01 02 03 04 05 06 07 08; do
    list="$list,local://$DEV_DIR/gallery-$i.png"
  done
  printf '%s' "${list#,}"
}

set_rotation() { # $1=0 竖屏 / 1 横屏
  a shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
  a shell settings put system user_rotation "$1" >/dev/null 2>&1
  # 旋转是一次窗口重建，等布局稳定（DocumentsActivity 刻意不声明 configChanges，
  # 会走真正的重建 —— 这正是"双栏形态靠资源限定符切换"的实现方式）。
  sleep 3.0
}

echo "密密文件管理 · M3.1.1 修复验证冒烟"
echo "  变体：$VARIANT    设备：$SERIAL"
echo "  输出：$OUT_DIR"

# ── 准备：素材 + 安装 ───────────────────────────────────────────────────────

step "准备：素材与 APK"

if [[ ! -f "$FIX_DIR/gallery-01.png" ]]; then
  "$UI_PY" "$FIXTURES" "$FIX_DIR" > "$OUT_DIR/fixtures.txt" 2>&1
fi
if [[ -f "$FIX_DIR/gallery-01.png" ]]; then
  note "素材已生成（含 8 张多色画廊图）"
else
  bad "准备：素材生成失败（见 fixtures.txt）"
fi

APK="$(ls -t "$PROJECT_DIR"/app/build/outputs/apk/"$VARIANT"/*.apk 2>/dev/null | head -n1)"
if [[ -z "$APK" ]]; then
  bad "准备：找不到 $VARIANT APK，请先 assemble"
else
  note "APK：$(basename "$APK")"
  if a install -r -d "$APK" 2>&1 | grep -q "Success"; then
    ok "准备：APK 安装成功"
  else
    bad "准备：APK 安装失败"
  fi
  a shell rm -rf "$DEV_DIR" >/dev/null 2>&1
  a shell mkdir -p "$DEV_DIR" >/dev/null 2>&1
  a push "$FIX_DIR/." "$DEV_DIR/" > "$OUT_DIR/push.txt" 2>&1
  # 12 个子目录：让右栏列表长出屏幕，才测得出"能不能滚"。
  # shellcheck disable=SC2046
  a shell "for i in 01 02 03 04 05 06 07 08 09 10 11 12; do mkdir -p $DEV_DIR/dir\$i; done" \
    >/dev/null 2>&1
  CNT="$(a shell ls "$DEV_DIR" 2>/dev/null | tr -d '\r' | grep -c .)"
  if [[ "${CNT:-0}" -ge 20 ]]; then
    ok "准备：素材已推送（${CNT} 项）"
  else
    bad "准备：素材推送失败（设备上只有 ${CNT:-0} 项）"
  fi
fi

set_rotation 0

# ══════════════════════════════════════════════════════════════════════════
# V 组：视频播放页的横竖屏按钮是否贴着齿轮设置按钮
# ══════════════════════════════════════════════════════════════════════════

if want V1; then
  step "V1 横竖屏按钮紧邻控制条齿轮设置按钮"
  # 素材：现场录一段 2 秒视频（真实的 mp4，能过 Media3 的解析）。
  a shell screenrecord --time-limit 2 --size 320x240 --bit-rate 1000000 \
    "$DEV_DIR/clip.mp4" >/dev/null 2>&1
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  a shell am start -n "$PLAYER" --esa preview.paths "local://$DEV_DIR/clip.mp4" \
    --ei preview.index 0 >/dev/null 2>&1
  if ! wait_activity "VideoPlayerActivity" 12; then
    bad "V1 没能进入视频播放页（前台是 $(top_activity)）"
  else
    sleep 2.5
    # 显示控制条，然后**暂停**：播放中 SurfaceView 一直在刷新，uiautomator dump 会不稳。
    a shell input tap 540 1200 >/dev/null 2>&1
    sleep 1.0
    a shell input tap 540 1200 >/dev/null 2>&1
    sleep 1.0
    ROTATE="$(rect_of id=player_rotate)" || ROTATE=""
    GEAR="$(rect_of id=exo_settings)" || GEAR=""
    if [[ -z "$ROTATE" ]]; then
      bad "V1 界面上找不到横竖屏按钮（id=player_rotate）"
    elif [[ -z "$GEAR" ]]; then
      bad "V1 界面上找不到齿轮设置按钮（id=exo_settings）—— 控制条内部结构可能变了"
    else
      note "横竖屏按钮矩形：$ROTATE"
      note "齿轮设置矩形：  $GEAR"
      eval "$(printf '%s\n' "$ROTATE" | awk '{print "R1="$1" RT="$2" R2="$3" RB="$4}')"
      eval "$(printf '%s\n' "$GEAR"   | awk '{print "G1="$1" GT="$2" G2="$3" GB="$4}')"
      # 判据三条：① 在齿轮左边且紧邻（间隙 ≤ 8 px）；② 纵向大幅重叠（同一排）；
      # ③ 位于屏幕底部（控制条所在区域）。
      GAP=$(( G1 - R2 ))
      OVERLAP=$(( (RB < GB ? RB : GB) - (RT > GT ? RT : GT) ))
      ROT_H=$(( RB - RT ))
      SCREEN_H="$(a shell wm size 2>/dev/null | tr -d '\r' | head -n1 | sed 's/.*: \([0-9]*\)x\([0-9]*\).*/\2/')"
      SCREEN_H="${SCREEN_H:-2400}"
      BOTTOM_LIMIT=$(( SCREEN_H * 7 / 10 ))
      if [[ "$GAP" -lt -8 ]]; then
        bad "V1 横竖屏按钮不在齿轮左侧（间隙 ${GAP} px）"
      elif [[ "$GAP" -gt 8 ]]; then
        bad "V1 横竖屏按钮与齿轮之间有 ${GAP} px 的空隙，不算「旁边」"
      elif [[ "$OVERLAP" -lt $(( ROT_H / 2 )) ]]; then
        bad "V1 横竖屏按钮与齿轮不在同一排（纵向重叠 ${OVERLAP} px / 按钮高 ${ROT_H} px）"
      elif [[ "$RT" -lt "$BOTTOM_LIMIT" ]]; then
        bad "V1 横竖屏按钮没有落在底部控制条区域（top=${RT}，屏幕高 ${SCREEN_H}）"
      else
        ok "V1 横竖屏按钮紧邻齿轮（间隙 ${GAP} px，纵向重叠 ${OVERLAP} px）"
      fi
    fi
    app_log "v1-player"
  fi
fi

# ══════════════════════════════════════════════════════════════════════════
# G 组：图片画廊翻页后不能退化成单色色块
# ══════════════════════════════════════════════════════════════════════════

if want G1 || want G2; then
  step "G1/G2/G3 画廊翻页（截图颜色数 + 解码目标契约）"
  launch_preview "$(gallery_paths)" 0
  if ! top_activity | grep -qF "PreviewActivity"; then
    bad "G 组 没能进入预览页"
  else
    # G1：首屏
    if want G1; then
      C="$(shot_colors)"
      note "首屏 colors=${C:-?}"
      if [[ "${C:-0}" -ge "$MIN_COLORS" ]]; then
        ok "G1 首屏渲染正常（colors=${C}）"
      else
        bad "G1 首屏就是单色色块（colors=${C:-0}）"
      fi
    fi
    # G2：连续后翻 5 页，每翻一页取一次样；
    # 真机复现的条件是"手势滑动 3~4 张之后"，所以这里必须一页一页地翻。
    if want G2; then
      BROKEN=0
      BROKEN_PAGES=""
      N=1
      while [[ $N -le 5 ]]; do
        a shell input swipe 900 1300 180 1300 180 >/dev/null 2>&1
        sleep 1.6
        C="$(shot_colors)"
        note "第 $(( N + 1 )) 页 colors=${C:-?}"
        if [[ "${C:-0}" -lt "$MIN_COLORS" ]]; then
          BROKEN=$(( BROKEN + 1 ))
          BROKEN_PAGES="${BROKEN_PAGES}${BROKEN_PAGES:+,}第$(( N + 1 ))页"
        fi
        N=$(( N + 1 ))
      done
      # 再回退 3 页、前进 3 页 —— 真机反馈里"往前滑几页再往后翻就正常"，
      # 这条回退路径也必须验证（若修复不彻底，回退会重新出现色块）。
      M=1
      while [[ $M -le 3 ]]; do
        a shell input swipe 180 1300 900 1300 180 >/dev/null 2>&1
        sleep 1.2
        M=$(( M + 1 ))
      done
      while [[ $M -le 6 ]]; do
        a shell input swipe 900 1300 180 1300 180 >/dev/null 2>&1
        sleep 1.6
        C="$(shot_colors)"
        note "回退后再前进 colors=${C:-?}"
        if [[ "${C:-0}" -lt "$MIN_COLORS" ]]; then
          BROKEN=$(( BROKEN + 1 ))
          BROKEN_PAGES="${BROKEN_PAGES}${BROKEN_PAGES:+,}回退后第$(( M - 2 ))页"
        fi
        M=$(( M + 1 ))
      done
      if [[ "$BROKEN" -eq 0 ]]; then
        ok "G2 连续 8 次翻页取样全部正常（无单色色块）"
      else
        bad "G2 有 ${BROKEN} 页退化成单色色块：${BROKEN_PAGES}"
      fi

      # ── G3 解码目标契约（这条判据与"截图时机"无关，是根因的直接取证）────────
      #
      # 为什么截图判据 G2 不够：图像解码是异步的，色块可能只显示几百毫秒就被
      # 下一张正常位图替换掉 —— 而"修复前的版本"在模拟器上恰好能靠这点遮过去
      # （真机遮不过去：邻页解出来的 1×1 位图会被**缓存**在页面源里，
      # 而 `RecyclerView` 不会重绑同一位置，于是那块纯色一直在）。
      #
      # 所以这里直接查根因：**解码目标不允许退化成个位数**。
      #   · 修复前：视图未测量 → viewPixelSize 读到 1 → target=1 → sample=512/2048
      #   · 修复后：未测量时改用屏幕长边 → target≈2201 → sample=1
      # 一行日志就能把这条契约钉死，且完全不依赖"截图那一刻画面是什么"。
      app_log "g-gallery" >/dev/null 2>&1
      TARGETS="$(grep -oE "decode target=[0-9]+" "$OUT_DIR/log-g-gallery.txt" 2>/dev/null \
        | sed 's/decode target=//' | sort -n | uniq)"
      if [[ -z "$TARGETS" ]]; then
        bad "G3 日志里没有 decode target（预览页可能没走图片画廊渲染器）"
      else
        SMALLEST="$(printf '%s\n' "$TARGETS" | head -n1)"
        note "解码目标取值集合：$(printf '%s' "$TARGETS" | tr '\n' ' ')"
        # 屏幕长边 2201（1080×2400 去掉系统栏）的一半 ≈ 1100，留足余量取 1000。
        if [[ "$SMALLEST" -ge 1000 ]]; then
          ok "G3 解码目标恒不回退（最小 ${SMALLEST}px ≥ 1000）"
        else
          bad "G3 出现了退化的解码目标（最小 ${SMALLEST}px）→ 必然解出 1~2 像素的位图"
        fi
      fi
    fi
    # G3 已经取过日志；这里只在"没跑 G2"时补取一次，供排障用。
    if want G2; then :; else app_log "g-gallery"; fi
  fi
fi

# ══════════════════════════════════════════════════════════════════════════
# P 组：PDF 缩放/平移不应抖动（判据：位移对账 + 缩放日志）
# ══════════════════════════════════════════════════════════════════════════

if want P1 || want P2; then
  step "P1/P2 PDF 双击放大与放大后平移"
  launch_preview "local://$DEV_DIR/doc.pdf" 0
  if ! top_activity | grep -qF "PreviewActivity"; then
    bad "P 组 没能进入预览页"
  else
    # 双击：两次点击必须间隔 > 40 ms（`input tap` 之间不 sleep 只有 ~9 ms，
    # 会被系统判成两个独立单击 —— 这条在 M3 冒烟里踩过）。
    a shell input tap 540 1200 >/dev/null 2>&1
    sleep 0.12
    a shell input tap 540 1200 >/dev/null 2>&1
    sleep 1.5
    if want P1; then
      if app_log "p-pdf" >/dev/null && grep -q "zoom scale=2" "$OUT_DIR/log-p-pdf.txt"; then
        ok "P1 双击放大生效（日志出现 zoom scale=2）"
      else
        bad "P1 双击没有放大（日志里没有 zoom scale=2）"
      fi
    fi
    if want P2; then
      # 一次"慢速"上滑：`input swipe` 会生成一串 MOVE，正好走完整条平移链路。
      # 位移取 300 px 而不是大幅滑动：距离小才不会撞到 clamp 边界
      # （撞到边界时内容位移会小于手指位移，那是正确行为，但会干扰这里的对账）。
      a shell input swipe 540 1400 540 1100 400 >/dev/null 2>&1
      sleep 0.8
      app_log "p-pan"
      LINE="$(grep "gesture end" "$OUT_DIR/log-p-pan.txt" 2>/dev/null | tail -n1)"
      if [[ -z "$LINE" ]]; then
        bad "P2 没有取到手势对账日志（MimiZoomContainer: gesture end …）"
        note "可能原因：平移没有走 ZoomableContainer（见 log-p-pan.txt）"
      else
        note "$LINE"
        # ⚠️ 不能用 `eval` 把日志行当 shell 代码执行：行首是时间戳与 pid，
        # 行内还有括号，shell 会直接报 `syntax error near unexpected token '('`。
        # 用 sed 把四个数字抠出来 —— 这也顺带把"解析"与"执行"彻底分开。
        NUMBERS="$(printf '%s\n' "$LINE" \
          | sed -n 's/.*finger=(\(-\{0,1\}[0-9][0-9]*\),\(-\{0,1\}[0-9][0-9]*\)) pan=(\(-\{0,1\}[0-9][0-9]*\),\(-\{0,1\}[0-9][0-9]*\)).*/\1 \2 \3 \4/p')"
        if [[ -z "$NUMBERS" ]]; then
          bad "P2 手势日志格式与预期不符，解析失败"
          note "$LINE"
        else
          FX="$(printf '%s\n' "$NUMBERS" | awk '{print $1}')"
          FY="$(printf '%s\n' "$NUMBERS" | awk '{print $2}')"
          PX="$(printf '%s\n' "$NUMBERS" | awk '{print $3}')"
          PY="$(printf '%s\n' "$NUMBERS" | awk '{print $4}')"
          ABS_F=$(( FY < 0 ? -FY : FY ))
          ABS_P=$(( PY < 0 ? -PY : PY ))
          if [[ "$ABS_F" -lt 50 ]]; then
            bad "P2 手指位移太小（${ABS_F} px），这条用例没测到东西"
          else
            # 比例判据：|pan| / |finger| 必须接近 1。
            # 老版本会被算两次（≈2.0），这正是"放大后拖动画面抖动"的根因。
            RATIO="$(awk -v p="$ABS_P" -v f="$ABS_F" 'BEGIN { printf "%.2f", p / f }')"
            RATIO_OK="$(awk -v r="$RATIO" -v tol="$PAN_TOLERANCE" \
              'BEGIN { print (r > 1 - tol && r < 1 + tol) ? "yes" : "no" }')"
            if [[ "$RATIO_OK" == "yes" ]]; then
              ok "P2 平移与手指 1:1（finger=${ABS_F}px pan=${ABS_P}px 比值=${RATIO}）"
            else
              bad "P2 平移与手指不成比例（finger=${ABS_F}px pan=${ABS_P}px 比值=${RATIO}）"
            fi
          fi
        fi
      fi
    fi
  fi
fi

# ══════════════════════════════════════════════════════════════════════════
# T 组：双栏（横屏/平板）形态下右栏必须能点、能滚
# ══════════════════════════════════════════════════════════════════════════

if want T1 || want T2; then
  step "T1/T2 双栏形态右栏可点、可滚"
  set_rotation 1
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  a shell am start -n "$ENTRY" >/dev/null 2>&1
  wait_activity "DocumentsActivity" 15 || true
  sleep 3.0

  # 先用**左栏导航树**进入本机存储。
  #
  # 这一步同时是对照组：左栏在修复前后都正常，所以"能进到根目录"这件事说明
  # 界面没卡死、定位没偏 —— 于是后面的失败只可能来自**右栏**。
  LEFT_OK=0
  if tap_spec "under:nav_tree/text=内部存储" 2.5; then LEFT_OK=1; fi
  if [[ "$LEFT_OK" -eq 0 ]]; then
    note "左栏定位失败，改用「内部存储」任意位置"
    tap_spec "text=内部存储" 2.5 || true
  fi

  # ── T1 先测滚动 ────────────────────────────────────────────────────────
  #
  # ⚠️ 顺序不能反。滚动必须在**还没进子目录**时测：横屏下右栏只有 4 行可见，
  # 而根目录有 73 项；一旦点了某一项进去（Alarms 之类的空目录），
  # 列表就短到滚不动，用例会失去意义。
  #
  # 判据：**可见条目的集合变了**。
  #
  # 为什么不用"首行的 top 坐标变小"：行高固定（紧凑模式 32 dp），第 1 行滚出屏幕后
  # 第 2 行会顶到它原来的位置，读到的 top 几乎不变（实测 375 → 350）——
  # 这个判据会把"滚得好好的"误判成"滚不动"（第一版就是这么错的）。
  if want T1; then
    ROWS_BEFORE="$(texts_of "under:file_list/id=rowTitle")"
    if [[ -z "$ROWS_BEFORE" ]]; then
      bad "T1 右栏里读不到任何一行（前台 $(top_activity)）"
    else
      note "滑动前可见条目：$(printf '%s' "$ROWS_BEFORE" | tr '\n' ' ')"
      a shell input swipe 1600 900 1600 300 300 >/dev/null 2>&1
      sleep 1.5
      ROWS_AFTER="$(texts_of "under:file_list/id=rowTitle")"
      note "滑动后可见条目：$(printf '%s' "$ROWS_AFTER" | tr '\n' ' ')"
      if [[ -z "$ROWS_AFTER" ]]; then
        bad "T1 右栏里读不到任何一行"
      elif [[ "$ROWS_BEFORE" != "$ROWS_AFTER" ]]; then
        ok "T1 右栏可滚（可见条目已变化）"
      else
        bad "T1 右栏滚不动（滑动前后可见条目完全相同）"
      fi
    fi
  fi

  # ── T2 再测点击 ────────────────────────────────────────────────────────
  #
  # 判据用**状态栏的项目统计**而不是工具栏标题：
  # MaterialToolbar 的标题是个没有 id 的内部 TextView，按 id 取不到；
  # 而按文本 grep 会与列表里同名的行撞车（进 `DCIM` 之后标题是 DCIM，
  # 列表里也有 DCIM 这一行）。`status_summary` 有 id、且进入目录后必然变化。
  # 另外要求**还留在浏览页**：万一点到了文件会跳去预览页，那种"变化"不算数。
  if want T2; then
    COUNT_BEFORE="$(text_of id=status_summary)"
    XY="$(center_of "under:file_list/id=rowTitle")" || XY=""
    if [[ -z "$XY" ]]; then
      bad "T2 右栏里读不到任何一行"
    else
      X="$(printf '%s\n' "$XY" | awk '{print $1}')"
      Y="$(printf '%s\n' "$XY" | awk '{print $2}')"
      a shell input tap "$X" "$Y" >/dev/null 2>&1
      sleep 2.2
      COUNT_AFTER="$(text_of id=status_summary)"
      TOP="$(top_activity)"
      note "点击右栏首行：统计 [${COUNT_BEFORE:-（空）}] → [${COUNT_AFTER:-（空）}]，前台=${TOP##*/}"
      if ! printf '%s' "$TOP" | grep -qF "DocumentsActivity"; then
        bad "T2 点击右栏后离开了浏览页（前台 ${TOP##*/}）—— 点到的可能不是目录"
      elif [[ -z "$COUNT_AFTER" ]]; then
        bad "T2 点击右栏后读不到状态栏统计，无法判定"
      elif [[ "$COUNT_AFTER" != "$COUNT_BEFORE" ]]; then
        ok "T2 右栏可点（目录统计已变化：${COUNT_AFTER}）"
      else
        bad "T2 右栏点不动（统计未变化：${COUNT_AFTER}）"
      fi
    fi
  fi
  app_log "t-twopane"
  set_rotation 0
fi

# ── 崩溃检查与收尾 ──────────────────────────────────────────────────────────

step "崩溃检查"
if crash_check "m31"; then
  ok "全程无 FATAL EXCEPTION"
else
  bad "出现崩溃，详见 crash-m31.txt"
fi

if [[ "$KEEP_DATA" -eq 0 ]]; then
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
fi

printf '\n\033[1m════ 结果 ════\033[0m\n'
printf '  通过 %d / 失败 %d / 跳过 %d\n' "$PASS" "$FAIL" "$SKIP"
if [[ -n "$FAILED_ITEMS" ]]; then
  printf '\n  失败项：\n%s\n' "$FAILED_ITEMS"
fi
printf '\n  产物：%s\n\n' "$OUT_DIR"

[[ "$FAIL" -eq 0 ]]
