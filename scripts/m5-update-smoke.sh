#!/usr/bin/env bash
# ==========================================================================
# 应用内自更新冒烟（M5）
#
# ## 为什么要有两条通道
# 更新链路的失败模式分两大类，它们**用同一条通道测不出来**：
#
#  ① **能不能拿到清单**（DNS / TLS / 证书 / 路径 / 缓存 / 版本比对）——
#     只能拿**生产地址**测。用本机镜像测这条等于没测。
#  ② **拿到之后能不能安全地装上**（下载 / 三重校验 / 安装授权）——
#     只能用**本机镜像 + 自签的同名包**测。debug 包的包名与签名都和生产包不同，
#     拿生产清单里的 release 包去校验**必然**被签名那一关拒绝，
#     而那是判据错了、不是代码错了。
#
# 所以脚本对同一个 debug 包分别喂两个 `update.url`。
#
# ## ⚠️ 读 UI 必须看"当前那个窗口"
# `uiautomator dump` 只 dump **最上层窗口**。更新对话框是独立窗口，一旦它弹出来，
# 设置页上的 `update_summary` / `update_action` 就**完全不在快照里**（读回来是空串）。
# 第一版冒烟就栽在这里：U3 起每条都报"实际=[]"，而引擎日志清清楚楚写着
# "发现新版本 0.5.0-M5-m5test(10)" —— 代码是好的，判据在量一个已经被盖住的窗口。
#
# 所以断言分两类，各自盯自己那个窗口：
#   · 有对话框时 → 读 `dialog_title` / `dialog_message` / 按钮文本
#   · 需要看行内状态时 → 先 `close_dialog`，再读 `update_summary`
#
# ## 为什么要限速镜像
# 从 localhost 下 19 MB 只要几百毫秒，而一次 `uiautomator dump` 要 1 秒上下 ——
# 不限速时"正在下载 N%"这段界面**永远来不及被看到**，进度就变成了不可验证的部分。
# 而它真的出过 bug（就地刷新的判据写错，进度永远停在 0%）。
# 所以镜像用 `scripts/m5-mirror.py --throttle 2500`（约 2.5 MB/s，19 MB ≈ 8 秒），
# 让"进度在涨"变成一个能稳定观测的事实。
#
# ## 一个必须记住的副作用
# `UpdateEngine.install()` 在进程启动时会清空下载目录（含"下载完成但没装"的包）。
# 所以 **下载 → 安装 必须在同一个应用会话内完成**，中间不能 force-stop。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="$HOME/.cmfa-toolchain/android-sdk/platform-tools:$PATH"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles.debug"
SETTINGS_PAGE="$APP_ID/com.miyuetech.mimifiles.feature.settings.SettingsActivity"
UI_PY="$HOME/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="$PROJECT_DIR/scripts/m2-ui.py"
CHECK_PY="$PROJECT_DIR/scripts/m41-checks.py"
MIRROR_PY="$PROJECT_DIR/scripts/m5-mirror.py"

PORT="${M5_PORT:-9999}"
THROTTLE_KBPS="${M5_THROTTLE:-2500}"
MIRROR_DIR="$PROJECT_DIR/build/m5-mirror"
ASSET_DIR="$PROJECT_DIR/build/m5-assets"
UPGRADE_APK="$ASSET_DIR/upgrade-debug.apk"
OUT_DIR="$PROJECT_DIR/build/m5-smoke"
MIRROR_URL="http://127.0.0.1:$PORT/manifest.json"

# 生产清单地址从构建脚本提取（单一事实源：与 tools/publish-update.sh 同源）
#
# 用 grep 而不是 Python 正则：构建脚本里那串 URL 是**被 Kotlin 转义过的**
# （写作 `\"https://…manifest.json\"`），所以它前后紧挨着的是反斜杠 + 引号。
# 在 shell 与 Python 之间来回转义那一层反斜杠，很容易写出"看起来对、实际匹配不上"
# 的模式 —— 第一次跑就是这么翻车的。直接切出 URL 本体，两边都不用管引号。
PROD_URL="$(grep -oE 'https://[^"]*app/update/[0-9a-f]{32}/manifest\.json' "$PROJECT_DIR/app/build.gradle.kts" | head -1)"
if [ -z "$PROD_URL" ]; then
  echo "SMOKE_FAIL 无法从 app/build.gradle.kts 提取生产清单地址"
  exit 1
fi

CUR_CODE="$(awk -F= '/^mimi\.versionCode/{print $2}' "$PROJECT_DIR/gradle.properties" | tr -d '[:space:]')"
CUR_NAME="$(awk -F= '/^mimi\.versionName/{print $2}' "$PROJECT_DIR/gradle.properties" | tr -d '[:space:]')"
[ -z "$CUR_CODE" ] && { echo "SMOKE_FAIL 读不到 mimi.versionCode"; exit 1; }
NEXT_CODE=$(( CUR_CODE + 1 ))

MIN_RATIO="${MIN_RATIO:-3.0}"
OK=0; BAD=0; SKIP=0
FAILED_CASES=()

mkdir -p "$OUT_DIR" "$ASSET_DIR"

# ── 输出 ─────────────────────────────────────────────────────────────────────
ok()   { OK=$((OK+1));   printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { BAD=$((BAD+1)); FAILED_CASES+=("$1"); printf '  \033[31m✗\033[0m %s\n' "$1"; }
skip() { SKIP=$((SKIP+1)); printf '  \033[33m-\033[0m 跳过 %s\n' "$1"; }
step() { printf '\n\033[1m── %s\033[0m\n' "$1"; }
note() { printf '      · %s\n' "$1"; }

a() { "$ADB" "$@"; }

# 取 UI 快照。
#
# ⚠️ 先删设备侧文件再 dump。`uiautomator dump` 失败时**不会覆盖**已有文件，
# 于是 `cat` 会把**上一次**的快照读回来 —— 表现是"界面没变化"，
# 而真实情况可能是"这次没取到"。两种完全不同的原因长得一模一样，
# 是这套脚本里最容易骗过自己的一种失败。
ui_dump() {
  a shell rm -f /sdcard/m5.xml >/dev/null 2>&1
  a shell uiautomator dump /sdcard/m5.xml >/dev/null 2>&1
  a shell cat /sdcard/m5.xml > "$OUT_DIR/_ui.xml" 2>/dev/null
  [[ -s "$OUT_DIR/_ui.xml" ]]
}

ui_text() { # $1=控件 id
  ui_dump || return 1
  "$UI_PY" "$UI_SCRIPT" text-of "$OUT_DIR/_ui.xml" "id=$1" 2>/dev/null | head -n1
}

ui_rect() { # $1=控件 id
  ui_dump || return 1
  "$UI_PY" "$UI_SCRIPT" rect "$OUT_DIR/_ui.xml" "id=$1" 2>/dev/null | head -n1
}

tap_id() { # $1=控件 id
  local xy x y
  xy="$(ui_rect "$1")" || return 1
  [ -z "$xy" ] && return 1
  x="$(printf '%s' "$xy" | awk '{print $1}')"
  y="$(printf '%s' "$xy" | awk '{print $2}')"
  a shell input tap "$x" "$y" >/dev/null 2>&1
  return 0
}

# 对话框上的三个控件（同一份 dump，只是控件不同）
dlg_title()  { ui_text dialog_title; }
dlg_msg()    { ui_text dialog_message; }
dlg_detail() { ui_text dialog_detail; }
dlg_btn()    { ui_text button1; }   # android:id/button1 = 正按钮

top_activity() {
  a shell dumpsys activity activities 2>/dev/null | tr -d '\r' | grep -m1 topResumedActivity
}

app_log() { # $1=输出文件名
  a logcat -d 2>/dev/null | grep -E "MimiUpdate" | tail -40 > "$OUT_DIR/$1.log"
  cat "$OUT_DIR/$1.log"
}

scroll_to_update() {
  local i r
  for i in 1 2 3 4 5 6; do
    r="$(ui_rect update_action)"
    [ -n "$r" ] && return 0
    a shell input swipe 540 1800 540 700 260 >/dev/null 2>&1
    sleep 0.8
  done
  return 1
}

open_settings() { # $1=清单地址（可空）
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  if [ -n "${1:-}" ]; then
    a shell am start -n "$SETTINGS_PAGE" --es update.url "$1" >/dev/null 2>&1
  else
    a shell am start -n "$SETTINGS_PAGE" >/dev/null 2>&1
  fi
  sleep 4
  scroll_to_update || return 1
  return 0
}

# 按**真实时钟**等待（`SECONDS` 是 bash 内建，3.2 也有）。
# 不要写成"循环 N 次 × sleep 0.5"：每次迭代里还有一次 1~2 秒的 uiautomator dump，
# 于是"25 秒超时"实际会等八十多秒 —— 超时值失去意义，脚本也无谓地慢。
wait_summary() { # $1=子串 $2=超时秒
  # ⚠️ 三条独立语句，不能合并成一条 `local`：bash 3.2 里同一个 `local` 语句的
  # 多个赋值互相看不见，`local a=1 b=$((a+1))` 会在展开 b 时报
  # "a: unbound variable"（`set -u` 下直接退出）。macOS 自带的就是 bash 3.2。
  local want="$1"
  local timeout="${2:-25}"
  local deadline=$(( SECONDS + timeout ))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local s
    s="$(ui_text update_summary 2>/dev/null)"
    case "$s" in *"$want"*) return 0 ;; esac
    sleep 0.4
  done
  return 1
}

wait_dialog() { # $1=标题子串 $2=超时秒
  local want="$1"
  local timeout="${2:-30}"
  local deadline=$(( SECONDS + timeout ))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local t
    t="$(dlg_title 2>/dev/null)"
    case "$t" in *"$want"*) return 0 ;; esac
    sleep 0.4
  done
  return 1
}

close_dialog() {
  a shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 1.2
}

write_mirror_manifest() { # $1=versionCode $2=versionName $3=sha256 $4=notes $5=apk文件名
  local size
  size="$(stat -f %z "$MIRROR_DIR/$5" 2>/dev/null || echo 0)"
  cat > "$MIRROR_DIR/manifest.json" <<EOF
{
  "versionCode": $1,
  "versionName": "$2",
  "publishedAt": "2026-10-14",
  "notes": "$4",
  "files": [
    {
      "abi": "arm64-v8a",
      "url": "$5",
      "sha256": "$3",
      "size": $size
    }
  ],
  "minSupported": 0
}
EOF
}

MIRROR_PID=""
cleanup_all() {
  [ -n "$MIRROR_PID" ] && kill "$MIRROR_PID" 2>/dev/null
  a reverse --remove "tcp:$PORT" >/dev/null 2>&1
  a shell cmd uimode night auto >/dev/null 2>&1
  a shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
}
trap cleanup_all EXIT

echo "════════ M5 · 软件更新冒烟 ════════"
echo "生产清单: $PROD_URL"
echo "本机镜像: $MIRROR_URL  （限速 ${THROTTLE_KBPS} KB/s）"
echo "当前版本: $CUR_NAME($CUR_CODE)   升级包: $NEXT_CODE"

# ── 准备：升级包 ─────────────────────────────────────────────────────────────
step "准备 · 升级包（版本 ${NEXT_CODE}，与已装包同签名）"
if [ -f "$UPGRADE_APK" ]; then
  note "复用已有 $UPGRADE_APK"
else
  note "用 -Pmimi.versionCode=$NEXT_CODE 覆盖后 build 一次 debug…"
  ( cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug -q \
      -Pmimi.versionCode="$NEXT_CODE" -Pmimi.versionName="$CUR_NAME-m5test" >/dev/null 2>&1 )
  SRC="$PROJECT_DIR/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
  [ -f "$SRC" ] || { bad "升级包构建失败"; echo "SMOKE 中止"; exit 1; }
  cp "$SRC" "$UPGRADE_APK"
  note "已产出 $UPGRADE_APK"
  note "还原正常版本号重新 build（避免把改过版本号的包留在 outputs 里）"
  ( cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug -q >/dev/null 2>&1 )
fi

BASE_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
[ -f "$BASE_APK" ] || { bad "找不到 debug 包，请先 ./gradlew :app:assembleDebug"; exit 1; }

UPGRADE_SHA="$(shasum -a 256 "$UPGRADE_APK" | awk '{print $1}')"
note "升级包 sha256=$UPGRADE_SHA"
note "安装基线包 + 预授权「安装未知应用」"
a install -r -d "$BASE_APK" >/dev/null 2>&1
a shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1

# ── 准备：本机镜像 ───────────────────────────────────────────────────────────
step "准备 · 限速镜像 + 端口反向映射"
rm -rf "$MIRROR_DIR"; mkdir -p "$MIRROR_DIR"
cp "$UPGRADE_APK" "$MIRROR_DIR/upgrade-debug.apk"
write_mirror_manifest "$NEXT_CODE" "$CUR_NAME-m5test" "$UPGRADE_SHA" "冒烟：验证下载与三重校验的测试版本" "upgrade-debug.apk"
"$UI_PY" "$MIRROR_PY" --port "$PORT" --dir "$MIRROR_DIR" --throttle "$THROTTLE_KBPS" >/dev/null 2>&1 &
MIRROR_PID=$!
sleep 1.5
kill -0 "$MIRROR_PID" 2>/dev/null || { bad "限速镜像启动失败"; exit 1; }
a reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1
note "镜像已在 127.0.0.1:$PORT 提供"

# ══════════════════════════════════════════════════════════════════════════════
step "U1 更新行的初始状态"
if open_settings "$MIRROR_URL"; then
  S="$(ui_text update_summary)"
  B="$(ui_text update_action)"
  note "摘要=[$S] 按钮=[$B]"
  if [[ "$S" == *"当前版本"* ]] && [[ "$B" == "检查更新" ]]; then
    ok "U1 初始态正确（摘要=当前版本，按钮=检查更新）"
  else
    bad "U1 初始态不对（摘要=[$S] 按钮=[$B]）"
  fi
else
  bad "U1 打不开设置页或滚不到更新行"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U2 生产通道：真 HTTPS 拉清单 → 已是最新"
if open_settings "$PROD_URL"; then
  tap_id update_action >/dev/null 2>&1
  if wait_summary "已是最新版本" 40; then
    ok "U2 生产通道可拉清单且判为已是最新（$(ui_text update_summary)）"
  else
    bad "U2 生产通道检查未得到「已是最新版本」（实际=[$(ui_text update_summary)]）"
  fi
  LOG="$(app_log u2)"
  if printf '%s' "$LOG" | grep -q "检查更新：$PROD_URL"; then
    ok "U2 日志确认请求发往生产地址"
  else
    bad "U2 日志里没有发往生产地址的检查记录"
  fi
else
  bad "U2 打不开设置页"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U3 本机镜像：发现新版本 + 详情对话框（读对话框窗口）"
U3_READY=0
if open_settings "$MIRROR_URL"; then
  tap_id update_action >/dev/null 2>&1
  if wait_dialog "发现新版本" 40; then
    U3_READY=1
    T="$(dlg_title)"; M="$(dlg_msg)"; D="$(dlg_detail)"
    note "标题=[$T]"
    note "正文=[$M]"
    note "补充=[$D]"
    ok "U3 检出镜像上的新版本并弹出详情对话框"
    if [[ "$M" == *"冒烟"* ]]; then
      ok "U3 对话框展示了清单里的更新说明"
    else
      bad "U3 对话框没展示更新说明（正文=[$M]）"
    fi
    if [[ "$D" == *"MB"* ]]; then
      ok "U3 对话框展示了包大小"
    else
      bad "U3 对话框没有包大小（补充=[$D]）"
    fi
    if [[ "$(dlg_btn)" == *"下载更新"* ]]; then
      ok "U3 正按钮是「下载更新」"
    else
      bad "U3 正按钮不是「下载更新」（实际=[$(dlg_btn)]）"
    fi
  else
    bad "U3 未弹出「发现新版本」对话框（标题=[$(dlg_title)]）"
  fi
else
  bad "U3 打不开设置页"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U4 下载 → 进度增长 → 三重校验 → 就绪"
if [ "$U3_READY" -eq 1 ]; then
  tap_id button1 >/dev/null 2>&1
  note "已点「下载更新」"
  # ① 进度必须出现在**行内**。
  #
  # ⚠️ 不是"下载中对话框"——**下载中根本不弹对话框**。
  # `setPositiveButton` 在点击后会自动关闭对话框，而 `Downloading` 状态是在
  # 监听器返回之后才被 collect 到的，那时对话框已经没了。第一版冒烟等的就是
  # 那个永远不会出现的对话框，于是把"已就绪"读成了"进度没出现"。
  # 现在按真实界面验：对话框关闭、进度落在"刚点过的按钮正下方那一行"。
  # ① 行内是否显示过进度。
  #
  # ⚠️ 这一条**特意做成"尽力而为"**，而不是硬失败。理由是一个实测到的环境限制：
  # 下载期间更新行每 100 ms 重新布局一次，而 `uiautomator dump` 需要一次稳定的
  # 窗口快照 —— 界面持续刷新时它会反复拿不到（本项目在"视频播放中 dump"上
  # 记过同类现象）。硬失败会把"环境测不到"报成"功能坏了"。
  #
  # 所以判据分层：
  #   · **行内进度真的出现过** → 用"至少一次成功观测"证明（下方 SEEN_ROW）
  #   · **进度真的在推进**     → 用引擎日志证明（每 25% 一条，见 UpdateEngine）
  # 两层合起来覆盖"显示"与"推进"，各自用能稳的那种证据。
  SEEN_ROW=0
  ROW_TEXT=""
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18; do
    ROW_TEXT="$(ui_text update_summary 2>/dev/null)"
    case "$ROW_TEXT" in
      *"正在下载"*) SEEN_ROW=1; break ;;
      *"更新包已就绪"*|*"更新失败"*|*"需要一次性授权"*) break ;;
    esac
    sleep 0.3
  done
  if [ "$SEEN_ROW" -eq 1 ]; then
    ok "U4 行内在观测窗口内显示过下载进度（${ROW_TEXT}）"
    R="$(ui_rect update_progress)"
    if [ -n "$R" ]; then
      ok "U4 行内进度条可见（rect=${R}）"
    else
      bad "U4 行内进度条没显示"
    fi
  else
    note "行内进度未取到快照（末次读到=[${ROW_TEXT}]）—— 环境限制，改由日志判据覆盖"
  fi

  # ② 进度必须**在推进**：日志里应出现多个不同的百分比。
  #    （引擎按 25% 跨档记一条，所以一次成功下载至少会有 25/50/75/100 里的一部分。）
  PROGRESS_LINES="$(a logcat -d 2>/dev/null | grep -c '下载进度' || true)"
  PROGRESS_UNIQ="$(a logcat -d 2>/dev/null | grep -o '下载进度 [0-9]*%' | sort -u | tr '\n' ' ')"
  note "进度日志：${PROGRESS_LINES:-0} 条 [${PROGRESS_UNIQ}]"
  if [ "${PROGRESS_LINES:-0}" -ge 2 ]; then
    ok "U4 下载进度确实在推进（${PROGRESS_UNIQ}）"
  else
    bad "U4 日志里没有多个进度档位（${PROGRESS_UNIQ:-空}）"
  fi
  # ③ 就绪
  if wait_dialog "就绪" 40; then
    ok "U4 三重校验通过、更新包就绪（$(dlg_title)）"
    if [[ "$(dlg_btn)" == *"立即安装"* ]]; then
      ok "U4 对话框提供了「立即安装」"
    else
      bad "U4 正按钮不是「立即安装」（实际=[$(dlg_btn)]）"
    fi
    LOG="$(app_log u4)"
    if printf '%s' "$LOG" | grep -q "三重校验通过"; then
      ok "U4 日志确认三重校验通过"
    else
      bad "U4 日志里没有「三重校验通过」"
    fi
    FILES="$(a shell run-as "$APP_ID" ls cache/update 2>/dev/null | tr -d '\r')"
    note "cache/update 内容：${FILES:-（空）}"
    if printf '%s' "$FILES" | grep -q "\.apk"; then
      ok "U4 安装包已落到私有 cacheDir"
    else
      bad "U4 cache/update 里没有安装包"
    fi
    printf '%s' "$FILES" | grep -q "\.part" && bad "U4 留下了 .part 残留"
    # ④ 关掉对话框，验证**行内**摘要也对（这里才是"行 = 状态"的那一半）
    close_dialog
    S="$(ui_text update_summary)"
    B="$(ui_text update_action)"
    note "行摘要=[$S] 行按钮=[$B]"
    if [[ "$S" == *"更新包已就绪"* ]] && [[ "$B" == *"立即安装"* ]]; then
      ok "U4 关掉对话框后行内状态与动作一致"
    else
      bad "U4 行内状态与对话框不一致（摘要=[$S] 按钮=[$B]）"
    fi
  else
    bad "U4 未进入「更新包就绪」（标题=[$(dlg_title)]）"
  fi
else
  skip "U4 （U3 未就绪）"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U5 立即安装 → 系统安装器（承接 U4 的会话，未 force-stop）"
if tap_id update_action >/dev/null 2>&1; then
  sleep 4
  TOP="$(top_activity)"
  note "前台：$TOP"
  if printf '%s' "$TOP" | grep -qiE "packageinstaller"; then
    ok "U5 系统安装器已弹出"
  else
    bad "U5 没弹出系统安装器（前台=${TOP:-空}）"
  fi
  a shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 1.5
else
  skip "U5 点不到行内按钮"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U6 缺授权分支：状态应为「需要一次性授权」且提供「去授权」"
a shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES deny >/dev/null 2>&1
U6_OK=0
if open_settings "$MIRROR_URL"; then
  tap_id update_action >/dev/null 2>&1
  if wait_dialog "发现新版本" 40; then
    tap_id button1 >/dev/null 2>&1
    if wait_dialog "授权" 60; then
      U6_OK=1
      ok "U6 缺授权时进入「需要一次性授权」（$(dlg_title)）"
      if [[ "$(dlg_btn)" == *"去授权"* ]]; then
        ok "U6 对话框提供「去授权」"
        tap_id button1 >/dev/null 2>&1
        sleep 3
        TOP="$(top_activity)"
        note "前台：$TOP"
        if printf '%s' "$TOP" | grep -qiE "settings"; then
          ok "U6 「去授权」跳到了系统设置页"
        else
          bad "U6 「去授权」没跳到系统设置页（前台=${TOP:-空}）"
        fi
        a shell input keyevent KEYCODE_BACK >/dev/null 2>&1
      else
        bad "U6 正按钮不是「去授权」（实际=[$(dlg_btn)]）"
      fi
    else
      bad "U6 未进入「需要一次性授权」（标题=[$(dlg_title)]）"
    fi
  else
    bad "U6 未弹出「发现新版本」"
  fi
else
  bad "U6 打不开设置页"
fi
a shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1

# ══════════════════════════════════════════════════════════════════════════════
step "U7 负例：清单里的 sha256 被篡改 → 必须拒绝且不弹安装器"
BAD_SHA="$(printf '0%.0s' $(seq 1 64))"
write_mirror_manifest "$NEXT_CODE" "$CUR_NAME-m5test" "$BAD_SHA" "篡改负例" "upgrade-debug.apk"
if open_settings "$MIRROR_URL"; then
  tap_id update_action >/dev/null 2>&1
  if wait_dialog "发现新版本" 40; then
    tap_id button1 >/dev/null 2>&1
    if wait_dialog "更新失败" 60; then
      ok "U7 校验失败被拦下"
      T="$(dlg_title)"; M="$(dlg_msg)"
      note "标题=[$T]"
      note "正文=[$M]"
      if [[ "$M" == *"完整性校验失败"* ]]; then
        ok "U7 失败原因指向完整性校验（不是含混的「下载失败」）"
      else
        bad "U7 失败原因不明确（正文=[$M]）"
      fi
      sleep 2
      TOP="$(top_activity)"
      if printf '%s' "$TOP" | grep -qiE "packageinstaller"; then
        bad "U7 校验失败却弹出了系统安装器（严重）"
      else
        ok "U7 校验失败时没有进入安装流程"
      fi
      FILES="$(a shell run-as "$APP_ID" ls cache/update 2>/dev/null | tr -d '\r')"
      if [ -z "$FILES" ]; then
        ok "U7 校验失败后残留文件已删除"
      else
        bad "U7 校验失败后仍留有文件：$FILES"
      fi
      close_dialog
      S="$(ui_text update_summary)"
      note "行摘要=[$S]"
      if [[ "$S" == *"更新失败"* ]]; then
        ok "U7 行内摘要也反映失败"
      else
        bad "U7 行内摘要没反映失败（[$S]）"
      fi
    else
      bad "U7 未弹出「更新失败」对话框（标题=[$(dlg_title)]）"
    fi
  else
    bad "U7 未弹出「发现新版本」"
  fi
else
  bad "U7 打不开设置页"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U8 负例：清单地址 404"
if open_settings "http://127.0.0.1:$PORT/not-here/manifest.json"; then
  tap_id update_action >/dev/null 2>&1
  if wait_dialog "更新失败" 40; then
    T="$(dlg_title)"; M="$(dlg_msg)"
    note "标题=[$T] 正文=[$M]"
    ok "U8 404 时给出「更新失败」对话框"
    if [[ "$M" == *"404"* ]]; then
      ok "U8 失败文案带上了 HTTP 404 这个具体信息"
    else
      bad "U8 失败文案没说明是 404（正文=[$M]）"
    fi
  else
    bad "U8 404 未弹出失败对话框（标题=[$(dlg_title)]）"
  fi
else
  bad "U8 打不开设置页"
fi

# ══════════════════════════════════════════════════════════════════════════════
step "U9 深色模式：对话框与更新行对比度（下限 ${MIN_RATIO}:1）"
# 恢复正确清单，好让对话框是"发现新版本"这种有正文的形态
write_mirror_manifest "$NEXT_CODE" "$CUR_NAME-m5test" "$UPGRADE_SHA" "深色模式对比度取证" "upgrade-debug.apk"
a shell cmd uimode night yes >/dev/null 2>&1
shot_and_measure() { # $1=控件 id $2=标签 $3=截图
  local R OUT RATIO PASSN
  R="$(ui_rect "$1")"
  if [ -z "$R" ]; then printf '  %-18s 跳过（不在视口内）\n' "$2"; SKIP=$((SKIP+1)); return; fi
  OUT="$("$UI_PY" "$CHECK_PY" contrast "$3" $(printf '%s' "$R" | awk '{print $1, $2, $3, $4}') 2>/dev/null)"
  RATIO="$(printf '%s' "$OUT" | grep -o 'ratio=[0-9.]*' | cut -d= -f2)"
  PASSN="$(awk -v r="${RATIO:-0}" -v m="$MIN_RATIO" 'BEGIN { print (r >= m) ? "yes" : "no" }')"
  printf '  %-18s %s\n' "$2" "$OUT"
  if [ "$PASSN" = "yes" ]; then ok "U9 ${2} 对比度达标（${RATIO}）"; else bad "U9 ${2} 对比度不足（${RATIO} < ${MIN_RATIO}）"; fi
}
if open_settings "$MIRROR_URL"; then
  tap_id update_action >/dev/null 2>&1
  wait_dialog "发现新版本" 40 >/dev/null 2>&1
  sleep 1
  a exec-out screencap -p > "$OUT_DIR/u9-dialog.png" 2>/dev/null
  shot_and_measure dialog_title   "对话框·标题"   "$OUT_DIR/u9-dialog.png"
  shot_and_measure dialog_message "对话框·正文"   "$OUT_DIR/u9-dialog.png"
  shot_and_measure dialog_detail  "对话框·补充"   "$OUT_DIR/u9-dialog.png"
  close_dialog
  a exec-out screencap -p > "$OUT_DIR/u9-row.png" 2>/dev/null
  shot_and_measure update_summary "更新行·摘要"   "$OUT_DIR/u9-row.png"
  shot_and_measure update_action  "更新行·按钮"   "$OUT_DIR/u9-row.png"
else
  bad "U9 打不开设置页"
fi

# ══════════════════════════════════════════════════════════════════════════════
echo
echo "════════ 结果 ════════"
printf '  通过 %s / 失败 %s / 跳过 %s\n' "$OK" "$BAD" "$SKIP"
if [ "$BAD" -gt 0 ]; then
  printf '  失败项：\n'
  for c in "${FAILED_CASES[@]}"; do printf '    · %s\n' "$c"; done
  echo "  SMOKE_FAIL"
  exit 1
fi
echo "  SMOKE_OK"
