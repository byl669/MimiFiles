#!/usr/bin/env bash
# ==============================================================================
# 密密文件管理 · M3 冒烟（预览体系）
#
# ## 这一版冒烟与 M2 最大的不同：**证据不再来自服务端**
# M2 的判据是"服务端录到了什么请求"（对端取证）。M3 的预览是**纯本地渲染**，
# 没有对端可问，所以判据换成三类同样硬的证据：
#
#   1. **前台 Activity**：`dumpsys activity` 里 `topResumedActivity` 是不是预览页
#      —— 它同时证明了"点了有反应"和"没崩"；
#   2. **进程内日志**：分块解码（`MimiImage region decoded`）、缩略图落盘等
#      只在真正执行时才会打印 —— 界面上"变清晰了"没法从 UI dump 观测，
#      而日志可以；
#   3. **磁盘副产物**：缩略图缓存目录里真的出现了文件（`run-as … ls cache/thumbnails`）。
#
# ## 用法
#   scripts/m3-smoke.sh                    # 全部
#   scripts/m3-smoke.sh --only N4,N5       # 只跑指定项（N1–N14）
#   scripts/m3-smoke.sh --keep-data        # 跑完不清 app 数据（留着手动把玩）
#
# ## 兼容性
# 必须能在 macOS 自带的 bash 3.2 上跑。两条已经踩过并固定的写法：
#   · 变量名**不能紧跟中文**（`$x（` 会被当成变量名 `x（` 的一部分）；
#   · 双引号命令替换 + 内层转义双引号 + 后接 `||` 会让 3.2 报 unexpected EOF，
#     统一写成 `xy="$(...)" || return 1`。
# ==============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/.cmfa-toolchain/android-sdk}"
ADB="$SDK_DIR/platform-tools/adb"
VENV_PY="$HOME/.workbuddy/binaries/python/envs/default/bin/python"

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

SERIAL="${SERIAL:-emulator-5554}"
DEV_DIR="/sdcard/MimiM3"
OUT_DIR="$PROJECT_DIR/build/m3-smoke"
FIX_DIR="$OUT_DIR/fixtures"
UI_DEV="/sdcard/m3_ui.xml"
UI_XML="$OUT_DIR/_ui.xml"
UI_PY="$VENV_PY"

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

# ── UI 辅助（与 M2 同一套，见 scripts/m2-ui.py）─────────────────────────────

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
  printf '       [warn] uiautomator dump 连续 3 次失败，沿用上一份界面快照\n' >&2
  return 1
}

screen_text() { "$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" dump-text "$UI_XML" 2>/dev/null; }

# 定位控件中心；用法：xy_of "text=内部存储" [轮询次数]
xy_of() {
  local key="$1" tries="${2:-6}" i=0 out
  while [[ $i -lt $tries ]]; do
    ui_dump
    out="$("$UI_PY" "$PROJECT_DIR/scripts/m2-ui.py" center "$UI_XML" "$key" 2>/dev/null | head -n1)"
    if [[ -n "$out" ]]; then
      printf '%s\n' "$out" | awk '{print $(NF-1)" "$NF}'
      return 0
    fi
    i=$(( i + 1 ))
    sleep 0.6
  done
  return 1
}

tap_key() { # $1=定位键 $2=等待秒
  local xy x y wait_s
  xy="$(xy_of "$1" 8)" || return 1
  wait_s="${2:-1.2}"
  x="$(printf '%s\n' "$xy" | awk '{print $1}')"
  y="$(printf '%s\n' "$xy" | awk '{print $2}')"
  a shell input tap "$x" "$y" >/dev/null 2>&1
  sleep "$wait_s"
  return 0
}

has_text() { ui_dump; screen_text | grep -qF -- "$1"; }

wait_text() { # $1=文本 $2=超时秒
  local i=0
  while [[ $i -lt $(( ${2:-8} * 2 )) ]]; do
    if has_text "$1"; then return 0; fi
    sleep 0.5
    i=$(( i + 1 ))
  done
  return 1
}

top_activity() {
  a shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | grep -m1 "topResumedActivity" | sed 's/.*u0 \([^ ]*\).*/\1/'
}

app_log() { # $1=标签
  local pid
  pid="$(a shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -z "$pid" ]]; then
    printf '       （应用进程不在，取不到日志）\n'
    return 1
  fi
  a logcat -d --pid "$pid" 2>/dev/null | tr -d '\r' > "$OUT_DIR/log-$1.txt"
  printf '       应用日志 → log-%s.txt（%s 行）\n' "$1" "$(grep -c . "$OUT_DIR/log-$1.txt" 2>/dev/null | tr -d ' ')"
}

crash_check() { # $1=标签：返回 0 表示"没有崩溃"
  local crashes
  crashes="$(a logcat -d 2>/dev/null | tr -d '\r' | grep -c "FATAL EXCEPTION")"
  if [[ "${crashes:-0}" -gt 0 ]]; then
    a logcat -d 2>/dev/null | tr -d '\r' | grep -A 12 "FATAL EXCEPTION" | tail -30 \
      > "$OUT_DIR/crash-$1.txt"
    return 1
  fi
  return 0
}

open_file_in_browser() { # $1=文件名
  # 每次从浏览页根部重走一遍：这样每个用例的初始状态一致，
  # 不会因为上一条用例把界面留在了预览页而互相影响。
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  a shell am start -n "$ENTRY" >/dev/null 2>&1
  sleep 3.5
  tap_key "text=内部存储" 2.0 || return 1
  # 目标目录可能在首屏之下，所以找不到就往下翻一屏再找（最多 4 次）。
  local i=0
  while [[ $i -lt 4 ]]; do
    tap_key "text=MimiM3" 2.0 && break
    a shell input swipe 540 1600 540 700 280 >/dev/null 2>&1
    sleep 1.2
    i=$(( i + 1 ))
  done
  [[ $i -ge 4 ]] && return 1
  # 文件也一样：`huge.png` 在按名称排序里也是中间位置。
  i=0
  while [[ $i -lt 4 ]]; do
    tap_key "text=$1" 4.0 && return 0
    a shell input swipe 540 1600 540 700 280 >/dev/null 2>&1
    sleep 1.0
    i=$(( i + 1 ))
  done
  return 1
}

# ── 直接以 Intent 启动预览页（内容类用例主用这条路）─────────────────────────
#
# ## 为什么不全部走"浏览页点击"
# 浏览页导航本身已经由 M1/M2 的冒烟覆盖（列表、排序、进入目录、返回）。
# 在这里再走一遍 UI 路径，会让"预览坏了"与"导航点偏了"两种失败长得一模一样——
# 而它们要修的地方完全不同。所以内容类用例**直接把 FsPath 作为 Intent extra
# 交给预览页**：它测的就是"给定一个文件，预览链路能不能跑通"，
# 同时也顺带验证了契约本身（extras 的键与格式）。
launch_preview() { # $1=设备上的绝对路径
  local uri="local://$1"
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  if a shell am start -n "$APP_ID/com.miyuetech.mimifiles.feature.preview.PreviewActivity" \
      --esa preview.paths "$uri" --ei preview.index 0 2>&1 | grep -qi "Error"; then
    return 1
  fi
  sleep 3.5
  return 0
}

echo "密密文件管理 · M3 冒烟"
echo "  变体：$VARIANT    设备：$SERIAL"
echo "  输出：$OUT_DIR"

# ── N1 准备：安装 / 素材 / 权限 ─────────────────────────────────────────────

if want N1; then
  step "N1 安装 APK 与准备素材"
  APK="$(ls -t "$PROJECT_DIR"/app/build/outputs/apk/"$VARIANT"/*.apk 2>/dev/null | head -n1)"
  if [[ -z "$APK" ]]; then
    bad "N1 找不到 $VARIANT APK，请先 assemble"
  else
    note "APK：$(basename "$APK")"
    if a install -r -d "$APK" 2>&1 | grep -q "Success"; then
      ok "N1 APK 安装成功"
    else
      bad "N1 APK 安装失败"
    fi
  fi

  if [[ ! -d "$FIX_DIR" ]]; then
    "$VENV_PY" "$PROJECT_DIR/scripts/m3-fixtures.py" "$FIX_DIR" > "$OUT_DIR/fixtures.txt" 2>&1
  fi
  if [[ -f "$FIX_DIR/huge.png" ]]; then
    a shell rm -rf "$DEV_DIR" >/dev/null 2>&1
    a shell mkdir -p "$DEV_DIR" >/dev/null 2>&1
    a push "$FIX_DIR/." "$DEV_DIR/" > "$OUT_DIR/push.txt" 2>&1
    # 判据用**结果**而不是 adb 的输出文本：`adb push` 的摘要行不保证是最后一行
    # （每个文件都有自己的进度行），grep 它会出现"推送成功却报失败"这种假故障。
    CNT="$(a shell ls "$DEV_DIR" 2>/dev/null | tr -d '\r' | grep -c .)"
    if [[ "${CNT:-0}" -ge 8 ]]; then
      ok "N1 素材已推送到 ${DEV_DIR}（${CNT} 个文件）"
    else
      bad "N1 素材推送失败（设备上只有 ${CNT:-0} 个文件，见 push.txt）"
    fi
  else
    bad "N1 素材生成失败（见 fixtures.txt）"
  fi
fi

# ── N2 清数据 + 授权 + 启动 ─────────────────────────────────────────────────

if want N2; then
  step "N2 清数据、授予全盘访问、启动到浏览页"
  if [[ "$KEEP_DATA" -eq 0 ]]; then
    a shell pm clear "$APP_ID" >/dev/null 2>&1
  fi
  # 「所有文件访问」是特殊权限，`pm grant` 对它无效，必须走 appops。
  a shell appops set "$APP_ID" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
  a shell pm grant "$APP_ID" android.permission.READ_MEDIA_IMAGES >/dev/null 2>&1
  a shell pm grant "$APP_ID" android.permission.READ_MEDIA_VIDEO >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  a shell am start -n "$ENTRY" >/dev/null 2>&1
  sleep 4.0
  TOP="$(top_activity)"
  if [[ "$TOP" == *"DocumentsActivity"* ]]; then
    ok "N2 已启动到浏览页（top=${TOP}）"
  else
    bad "N2 启动后前台不是浏览页，而是：${TOP:-（取不到）}"
  fi
  if wait_text "内部存储" 8; then
    ok "N2 首页列出了本机存储入口"
  else
    bad "N2 首页没有「内部存储」入口（可能是权限未生效）"
  fi
fi

# ── N3 缩略图：磁盘缓存出现文件 ─────────────────────────────────────────────

if want N3; then
  step "N3 列表缩略图落到磁盘缓存"
  if launch_preview "$DEV_DIR/huge.png"; then
    # 缩略图是异步生成的：给解码与编码留时间，但要等的是"文件出现"而不是固定时长。
    count=0
    i=0
    while [[ $i -lt 40 ]]; do
      # 用 `find -name '*.webp'` 而不是 `ls 目录/*/`：后者依赖设备 sh 的 glob 与 `ls`
      # 的输出格式（多列/单列随 tty 变化），在自动化里是脆的。
      count="$(a shell run-as "$APP_ID" find cache/thumbnails -name '*.webp' 2>/dev/null | tr -d '\r' | grep -c .)"
      [[ -n "$count" && "$count" -gt 0 ]] && break
      sleep 1
      i=$(( i + 1 ))
    done
    a shell run-as "$APP_ID" find cache/thumbnails -type f 2>/dev/null | tr -d '\r' > "$OUT_DIR/N3-cache-list.txt"
    if [[ -n "$count" && "$count" -gt 0 ]]; then
      ok "N3 缩略图缓存已生成 ${count} 个文件"
    else
      bad "N3 缩略图缓存为空（cache/thumbnails 里没有 webp）"
      app_log N3-thumbs
    fi
  else
    bad "N3 无法启动大图预览"
  fi
fi

# ── N4 打开大图 ─────────────────────────────────────────────────────────────

if want N4; then
  step "N4 打开 4200×4200 大图"
  if launch_preview "$DEV_DIR/huge.png"; then
    TOP="$(top_activity)"
    if [[ "$TOP" == *"PreviewActivity"* ]]; then
      sleep 3.5
      # ⚠️ 只判"前台是预览页 + 没崩"是不够的：**图片加载失败时页面仍停在这个
      # Activity 上**（渲染器会把错误显示在页面中央，Activity 一动不动）。
      # 也就是说那个判据会把"图根本没出来"报成 OK —— M3 第一轮就差点这样放过去。
      # 所以必须同时确认"没有出现打不开的提示"。
      #
      # 另外：`! cmd | grep` 在 bash 里不成立（`!` 只作用于紧接着的那条命令，
      # 于是 `!screen_text` 被当成一个不存在的命令名）。取反要写在外层。
      ui_dump
      if screen_text | grep -qF "打不开这个文件"; then
        bad "N4 预览页在，但图片没有加载出来（页面提示「打不开」）"
      elif crash_check N4; then
        ok "N4 预览页已打开、图片已渲染且进程未崩溃（top=${TOP}）"
      else
        bad "N4 打开大图时进程崩溃（见 crash-N4.txt）"
      fi
    else
      bad "N4 前台不是预览页，而是：${TOP:-（取不到）}"
    fi
    app_log N4-huge
    a exec-out screencap -p > "$OUT_DIR/N4-huge.png" 2>/dev/null
  else
    bad "N4 无法启动预览：huge.png"
  fi
fi

# ── N5 双击放大 → 分块解码 ──────────────────────────────────────────────────

if want N5; then
  step "N5 双击放大后补一块更高清的区域（分块解码）"
  # 上一条用例已经停在大图上；这里直接双击画面中心。
  # ⚠️ 间隔必须**短于** GestureDetector 的双击超时（默认 300 ms）。
  # 写成 sleep 0.4 时两次 tap 会被当成"两次单击"，永远不会触发 onDoubleTap ——
  # 表现为"脚本明明点了两下，应用像没收到"。
  # ⚠️ 双击的两次 tap 必须满足 **40 ms ≤ 间隔 ≤ 300 ms**，两端都是硬约束：
  #   · `GestureDetector.isConsideredDoubleTap` 里 `deltaTime < DOUBLE_TAP_MIN_TIME`
  #     （**40 ms**）直接判否 —— 两次 tap 贴太近反而不算双击；
  #   · 超过 `DOUBLE_TAP_TIMEOUT`（**300 ms**）当然也不算。
  # 本工程实测踩过这个坑：写成 `input tap A; input tap B` 时，两次间隔只有 **9 ms**，
  # 于是"双击"从来没被识别过，而现象看起来完全像"应用没实现双击"（N5 长期未通过
  # 就是被这个假象骗了）。所以这里显式插入 120 ms —— 落在窗口正中。
  #
  # 另外两次 tap 要放进**同一次** adb shell：一次 `adb shell input tap` 的进程启动
  # 开销本身就可能有几十毫秒，分成两次调用时无法精确控制间隔。
  a shell "input tap 540 1200; sleep 0.12; input tap 540 1200" >/dev/null 2>&1
  sleep 3.5
  if app_log N5-region; then
    if grep -q "region decoded" "$OUT_DIR/log-N5-region.txt"; then
      HIT="$(grep -m1 "region decoded" "$OUT_DIR/log-N5-region.txt" | sed 's/.*region decoded //')"
      ok "N5 分块解码已发生：$HIT"
    else
      bad "N5 放大后没有出现分块解码日志（没进入放大状态，或视口未越过基准采样）"
    fi
  else
    bad "N5 取不到应用日志"
  fi
fi

# ── N6 GBK 文本 —— 编码探测 ─────────────────────────────────────────────────

if want N6; then
  step "N6 打开 GBK 编码的中文文本"
  if launch_preview "$DEV_DIR/gbk.txt"; then
    if wait_text "中文编码测试" 12; then
      if has_text "GBK"; then
        ok "N6 中文按 GBK 正确解码（屏上有正文且标注了编码）"
      else
        ok "N6 中文正确显示（未读到编码标签，但正文已可见）"
      fi
    else
      bad "N6 文本没有显示出来（或出现乱码）"
      ui_dump
      screen_text | head -20 > "$OUT_DIR/N6-screen.txt"
    fi
    app_log N6-text
  else
    bad "N6 无法启动预览：gbk.txt"
  fi
fi

# ── N7 PDF ──────────────────────────────────────────────────────────────────

if want N7; then
  step "N7 打开最小 PDF"
  if launch_preview "$DEV_DIR/doc.pdf"; then
    sleep 3.0
    if has_text "第 1" || has_text "页" || has_text "doc.pdf"; then
      ok "N7 PDF 已渲染（预览页可见页码/标题）"
    else
      bad "N7 PDF 没有渲染出内容"
      ui_dump
      screen_text | head -20 > "$OUT_DIR/N7-screen.txt"
    fi
    app_log N7-pdf
  else
    bad "N7 无法启动预览：doc.pdf"
  fi
fi

# ── N8 魔数校正：.bin 实为文本 ──────────────────────────────────────────────

if want N8; then
  step "N8 扩展名错误（.bin 实为文本）按内容打开"
  if launch_preview "$DEV_DIR/renamed.bin"; then
    if wait_text "这个文件其实是一段文本" 12; then
      ok "N8 魔数判定生效：.bin 被按文本打开"
    else
      bad "N8 没有按内容识别出文本（见 N8-screen.txt）"
      ui_dump
      screen_text | head -20 > "$OUT_DIR/N8-screen.txt"
    fi
    app_log N8-magic
  else
    bad "N8 无法启动预览：renamed.bin"
  fi
fi

# ── N9 不支持的类型 ─────────────────────────────────────────────────────────

if want N9; then
  step "N9 纯二进制文件明确告知「不支持预览」"
  if launch_preview "$DEV_DIR/blob.dat"; then
    if wait_text "不支持预览" 12; then
      ok "N9 明确给出了「不支持预览此类型」的说明"
    else
      bad "N9 没有出现「不支持」提示（静默无反应是最糟的结果）"
      ui_dump
      screen_text | head -20 > "$OUT_DIR/N9-screen.txt"
    fi
  else
    bad "N9 无法启动预览：blob.dat"
  fi
fi

# ── N10 动态照片 ────────────────────────────────────────────────────────────

if want N10; then
  step "N10 动态照片识别（JPEG + 尾部 MP4）"
  if launch_preview "$DEV_DIR/motion.jpg"; then
    if wait_text "动态照片" 12; then
      ok "N10 已识别为动态照片并显示徽标"
    else
      bad "N10 未识别为动态照片（尾部扫描或兜底全扫未命中）"
      ui_dump
      screen_text | head -20 > "$OUT_DIR/N10-screen.txt"
    fi
    app_log N10-motion
    a exec-out screencap -p > "$OUT_DIR/N10-motion.png" 2>/dev/null
  else
    bad "N10 无法启动预览：motion.jpg"
  fi
fi

# ── N11 浏览页 → 预览页（唯一的 UI 路径用例）────────────────────────────────
#
# 其余用例直接以 Intent 启动预览页（见 launch_preview 的注释）。
# 但"在浏览页里点一个文件会不会真的跳过去"这条链路是 M3 新增的集成代码
# （ViewModel 组织同级列表 → 发请求 → Activity 起预览页），必须有一条用例覆盖它。
# 单独成项的好处是：它失败时不会与"预览本身坏了"混在一起。

if want N11; then
  step "N11 浏览页点击图片 → 跳转预览页"
  if open_file_in_browser "small.png"; then
    TOP="$(top_activity)"
    if [[ "$TOP" == *"PreviewActivity"* ]]; then
      ok "N11 浏览页点击文件后确实进入了预览页"
    else
      bad "N11 点击后前台不是预览页，而是：${TOP:-（取不到）}"
      ui_dump
      screen_text | head -20 > "$OUT_DIR/N11-screen.txt"
    fi
    app_log N11-browser
  else
    bad "N11 在浏览页里找不到 small.png（导航失败，与预览无关）"
    # 导航失败时把屏幕留证：区分"没进内部存储"与"进了但列表里没有该文件"。
    ui_dump
    screen_text | head -25 > "$OUT_DIR/N11-screen.txt"
  fi
fi

# ── N12 视频播放（起播 + 非顺序随机读）─────────────────────────────────────
#
# ## 素材为什么是"现场录"的
# 宿主没有 `ffmpeg`，合不出合法 MP4。而**设备自己的 `screenrecord` 就能产出**标准
# MP4（`ftyp/mp42` + `avc1`），而且它的 **`moov` 落在文件尾部**——这恰好能验证
# "起播前必须先跳到文件尾读索引"这件事，比手搓一个规整的测试视频更有说服力。
#
# ## 为什么必须录够长（60 s）
# ExoPlayer 的默认缓冲窗口是 **50 s**（视频）。片子比它短时，整个文件会在起播瞬间
# 被一次性读完，于是"拖动 → 重新定位读"这件事**在日志里根本不会发生**——
# M3 收口时就栽在这个细节上：拿 6 s / 17 s 的片子测，怎么都看不到随机读。
#
# ## 判据为什么是"不同的 pos 个数"
# `MimiPreviewSource` 每次被调用都打一行 `open pos=<起点> want=<长度>`。
# 于是：**≥2 个不同的 pos = 真的发生了随机访问**（第一段是文件头，第二段通常是
# 文件尾的 moov，第三段才回到数据区）。只有 1 个 pos 说明是纯顺序读；
# 一个都没有则说明根本没走我们自己的 DataSource。

if want N12; then
  step "N12 视频播放：起播 + 非顺序随机读（走 SeekableSourceDataSource）"
  VIDEO="$FIX_DIR/screen-big.mp4"
  if [[ ! -s "$VIDEO" ]]; then
    note "本地无视频素材，改用设备 screenrecord 现场生成（60 s @ 4 Mbps）"
    a shell 'screenrecord --size 960x540 --bit-rate 4000000 --time-limit 60 /sdcard/m3_big.mp4 & i=0; while [ $i -lt 120 ]; do input swipe 540 1700 540 600 200; input swipe 540 600 540 1700 200; i=$((i+1)); done; wait' >/dev/null 2>&1
    a pull /sdcard/m3_big.mp4 "$VIDEO" >/dev/null 2>&1
  fi
  if [[ ! -s "$VIDEO" ]]; then
    skip "N12 无法获得视频素材（宿主无 ffmpeg 且设备 screenrecord 未产出文件）"
  else
    if a push "$VIDEO" "$DEV_DIR/m3-video.mp4" >/dev/null 2>&1; then
      a shell am force-stop "$APP_ID" >/dev/null 2>&1
      a logcat -c >/dev/null 2>&1
      a shell am start -n "$APP_ID/com.miyuetech.mimifiles.feature.player.VideoPlayerActivity" \
          --esa preview.paths "local://$DEV_DIR/m3-video.mp4" --ei preview.index 0 >/dev/null 2>&1
      sleep 5
      ui_dump
      TOP="$(top_activity)"
      app_log N12-video
      OPENS="$OUT_DIR/N12-opens.txt"
      grep "open pos=" "$OUT_DIR/log-N12-video.txt" > "$OPENS" 2>/dev/null || true
      N_OPEN="$(grep -c "open pos=" "$OPENS" 2>/dev/null | tr -d ' ')"
      N_DISTINCT="$(sed 's/.*open pos=\([0-9]*\).*/\1/' "$OPENS" 2>/dev/null | sort -u | grep -c . | tr -d ' ')"
      if [[ "$TOP" != *"VideoPlayerActivity"* ]]; then
        bad "N12 前台不是播放页，而是：${TOP:-（取不到）}"
      elif ! crash_check N12; then
        bad "N12 播放时进程崩溃（见 crash-N12.txt）"
      elif [[ "${N_OPEN:-0}" -ge 1 && "${N_DISTINCT:-0}" -ge 2 ]]; then
        SECOND="$(sed -n '2p' "$OPENS" | sed 's/.*open //')"
        ok "N12 起播成功，数据源出现 ${N_DISTINCT} 个不同读取位置（非顺序读示例：${SECOND}）"
      elif [[ "${N_OPEN:-0}" -ge 1 ]]; then
        bad "N12 播放页在，但只出现 1 个读取位置——没观察到随机跳读（见 N12-opens.txt）"
      else
        bad "N12 播放页在，却没有 open 日志——说明没走 SeekableSourceDataSource"
      fi
    else
      bad "N12 视频素材推送失败"
    fi
  fi
fi

# ── N13 压缩包列举 ──────────────────────────────────────────────────────────
#
# 判据用"屏幕文本"而不是截图：压缩包的条目名、大小、格式标签都在文本节点上，
# 直接读比像素比对既准确又便宜。
if want N13; then
  step "N13 压缩包列举（zip / tar / gz）"
  ARCH_OK=0
  ARCH_BAD=""
  for spec in "plain.zip:ZIP:readme.txt" "plain.tar:TAR:a.txt" "notes.txt.gz:GZip:notes.txt"; do
    NAME="${spec%%:*}"
    REST="${spec#*:}"
    LABEL="${REST%%:*}"
    ENTRY="${REST#*:}"
    if launch_preview "$DEV_DIR/$NAME"; then
      sleep 3.5
      ui_dump
      TEXT="$(screen_text)"
      if printf '%s' "$TEXT" | grep -q "$LABEL" && printf '%s' "$TEXT" | grep -q "$ENTRY"; then
        ARCH_OK=$(( ARCH_OK + 1 ))
      else
        ARCH_BAD="${ARCH_BAD}${ARCH_BAD:+、}${NAME}"
        screen_text | head -20 > "$OUT_DIR/N13-$NAME.txt"
      fi
      app_log "N13-$NAME"
    else
      ARCH_BAD="${ARCH_BAD}${ARCH_BAD:+、}${NAME}（启动失败）"
    fi
  done
  if [[ -z "$ARCH_BAD" ]]; then
    ok "N13 三种压缩包都能列举出条目（zip / tar / gz）"
  else
    bad "N13 以下压缩包没列举出来：${ARCH_BAD}（见 N13-*.txt）"
  fi
fi

# ── N14 压缩包解压（含 zip slip 防护）──────────────────────────────────────
#
# ## 判据为什么落在**设备文件系统**上
# "解压成功"这句话必须由产物证明：`find` 出真实文件、`cat` 出真实内容。
# 只看界面上的"已解压 N 个文件"是不够的 —— 那句话在写入全部失败时也可能出现。
#
# ## evil.zip 为什么要专门测
# 它里面有 `../evil.txt` 与 `/abs.txt` 两条**路径穿越**条目（zip slip）。
# 正确行为是：跳过它们、如实报告跳过数、**且一个字节都不写到目标目录之外**。
# 这条用例把"安全"从口号变成可执行的验收。
if want N14; then
  step "N14 压缩包解压与路径穿越防护"
  a shell rm -rf "$DEV_DIR/plain" >/dev/null 2>&1
  if launch_preview "$DEV_DIR/plain.zip"; then
    sleep 3.5
    ui_dump
    XY="$(xy_of "text=解压到此处" 8)" || XY=""
    if [[ -n "$XY" ]]; then
      set -- $XY
      a shell input tap "$1" "$2" >/dev/null 2>&1
      sleep 6
      FOUND="$(a shell find "$DEV_DIR/plain" -type f 2>/dev/null | tr -d '\r' | grep -c .)"
      CONTENT="$(a shell cat "$DEV_DIR/plain/readme.txt" 2>/dev/null | tr -d '\r' | head -n1)"
      if [[ "${FOUND:-0}" -ge 3 && -n "$CONTENT" ]]; then
        ok "N14 解压落盘成功：${FOUND} 个文件，内容可读（readme.txt 首行：${CONTENT}）"
      else
        bad "N14 解压后设备上只有 ${FOUND:-0} 个文件（期望 3 个），或内容读不到"
        ui_dump
        screen_text | head -20 > "$OUT_DIR/N14-screen.txt"
      fi
    else
      bad "N14 找不到「解压到此处」按钮"
    fi
    app_log N14-extract
  else
    bad "N14 无法启动 plain.zip 预览"
  fi

  # 路径穿越：先清干净可能被写到的位置，再解压 evil.zip
  a shell rm -rf /sdcard/evil.txt /abs.txt "$DEV_DIR/evil.txt" >/dev/null 2>&1
  if launch_preview "$DEV_DIR/evil.zip"; then
    sleep 3.5
    ui_dump
    XY="$(xy_of "text=解压到此处" 8)" || XY=""
    if [[ -n "$XY" ]]; then
      set -- $XY
      a shell input tap "$1" "$2" >/dev/null 2>&1
      sleep 5
      LEAK=0
      for probe in /sdcard/evil.txt /abs.txt "$DEV_DIR/evil.txt"; do
        if a shell "[ -e $probe ] && echo Y" 2>/dev/null | grep -q Y; then
          LEAK=$(( LEAK + 1 ))
        fi
      done
      SAFE="$(a shell "[ -e $DEV_DIR/evil/ok.txt ] && echo Y" 2>/dev/null | tr -d '\r')"
      if [[ "$LEAK" -eq 0 && "$SAFE" == "Y" ]]; then
        ok "N14 路径穿越被拦下：越界文件 0 个，合法条目正常解出"
      else
        bad "N14 路径穿越防护失败：越界文件 ${LEAK} 个（ok.txt=${SAFE}）"
        ui_dump
        screen_text | head -20 > "$OUT_DIR/N14-evil-screen.txt"
      fi
    else
      bad "N14 evil.zip 找不到「解压到此处」按钮"
    fi
    app_log N14-evil
  else
    bad "N14 无法启动 evil.zip 预览"
  fi
fi

# ── 汇总 ────────────────────────────────────────────────────────────────────

printf '\n\033[1m[ m3 ] 结果：通过 %s  失败 %s  跳过 %s\033[0m\n' "$PASS" "$FAIL" "$SKIP"
if [[ -n "$FAILED_ITEMS" ]]; then
  printf '失败项：\n%s\n' "$FAILED_ITEMS"
fi
printf '证据目录：%s\n' "$OUT_DIR"

exit $(( FAIL > 0 ? 1 : 0 ))
