#!/usr/bin/env bash
# ==============================================================================
# 密密文件管理 · M4 冒烟（无线分享）
#
# ## 这一版冒烟的证据来自**另一台机器**
# M2 的证据是"服务端录到的请求"，M3 是"前台 Activity + 进程日志 + 磁盘副产物"，
# 而 M4 的验收判据天然就是**对端视角**：方案 §11 的 M4 一行写的是
# "手机启动 HTTP 分享 → PC 扫码 → 浏览/下载/上传（含中文文件名）→ 在线播放视频可拖动；
#  FTP 分享 → Windows 资源管理器可读写"。所以这里的每个用例都真的让
# **宿主机上的 curl** 去连手机：
#
#   · HTTP 走 `curl`（能验 `--path-as-is` 越狱、`-r` Range、`-T` 上传）；
#   · FTP 走 `curl`（`--ftp-skip-pasv-ip` 绕过 PASV 回包里的内网地址）。
#
# ## 宿主机怎么连到模拟器里的监听端口
# 模拟器是 QEMU 用户态网络（slirp）：宿主机**没有**一条能路由到 `10.0.2.15` 的路径。
# `adb forward` 也救不了它——adbd 在设备侧连的是 `127.0.0.1:port`，
# 而我们的服务端**刻意只绑局域网地址**（绑 `0.0.0.0` 会把蜂窝/VPN 网卡一起暴露）。
# 两头都对、但连不上。
#
# 正确工具是模拟器控制台的 `redir add tcp:<宿主机端口>:<设备端口>`：
# 它在 **slirp 层**做映射，转发目标是设备的 `10.0.2.15`，因此能命中我们的监听。
# 见 [emu_redir]。
#
# ## 用法
#   scripts/m4-smoke.sh                 # 全部（W1–W18）
#   scripts/m4-smoke.sh --only W5,W7    # 只跑指定项
#   scripts/m4-smoke.sh --keep-data     # 跑完不清测试目录
#
# ## 兼容性
# 必须能在 macOS 自带的 bash 3.2 上跑（同 M3）：变量名不能紧跟中文；
# `xy="$(...)" || return 1` 这种写法是固定的（见 m3-smoke.sh 顶部说明）。
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
PANEL="$APP_ID/com.miyuetech.mimifiles.feature.share.SharePanelActivity"
BROWSER="$APP_ID/com.miyuetech.mimifiles.feature.browser.DocumentsActivity"

SERIAL="${SERIAL:-emulator-5554}"
EMU_CONSOLE_PORT="${EMU_CONSOLE_PORT:-5554}"

DEV_DIR="/sdcard/MimiShare"
OUT_DIR="$PROJECT_DIR/build/m4-smoke"
UI_DEV="/sdcard/m4_ui.xml"
UI_XML="$OUT_DIR/_ui.xml"
UI_PY="$VENV_PY"
UI_PY_SCRIPT="$PROJECT_DIR/scripts/m2-ui.py"

# ── 端口映射（宿主机 → 设备）────────────────────────────────────────────────
# 18080 是宿主机侧用的高位端口，避开本机可能已有的 8080 服务。
HOST_HTTP=18080
GUEST_HTTP=8080
FTP_PORT=2121
# 被动数据端口段，必须与 `FtpShareServer.PASSIVE_PORTS` 一致。
FTP_PASSIVE_LOW=40000
FTP_PASSIVE_HIGH=40009

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

# ── UI 辅助（与 M2/M3 同一套，见 scripts/m2-ui.py）────────────────────────────

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

ui_text() { "$UI_PY" "$UI_PY_SCRIPT" dump-text "$UI_XML" 2>/dev/null; }

ui_field() { # $1=控件 id → 打印其 text
  ui_dump || return 1
  "$UI_PY" "$UI_PY_SCRIPT" text-of "$UI_XML" "id=$1" 2>/dev/null | head -n1
}

ui_rect() { # $1=控件 id → 打印 "left top right bottom"
  ui_dump || return 1
  "$UI_PY" "$UI_PY_SCRIPT" rect "$UI_XML" "id=$1" 2>/dev/null | head -n1
}

# 读开关的勾选状态：true / false / -（不在视口内或找不到）
# 用它而不是"再点一次"来复位：M3 的教训是"点一下就反了"会在默认值变化时
# 静默地测出相反的东西。
ui_checked() {
  ui_dump || return 1
  "$UI_PY" "$UI_PY_SCRIPT" checked "$UI_XML" "id=$1" 2>/dev/null | head -n1
}

# 定位控件中心；用法：xy_of "id=action_primary" [轮询次数]
xy_of() {
  local key="$1" tries="${2:-8}" i=0 out
  while [[ $i -lt $tries ]]; do
    ui_dump
    out="$("$UI_PY" "$UI_PY_SCRIPT" center "$UI_XML" "$key" 2>/dev/null | head -n1)"
    if [[ -n "$out" ]]; then
      printf '%s\n' "$out" | awk '{print $(NF-1)" "$NF}'
      return 0
    fi
    i=$(( i + 1 ))
    sleep 0.5
  done
  return 1
}

# 点一个"可能在滚动区下方"的控件：先原地找，找不到再滑动。
#
# 为什么不统一先滑动：HTTP 面板带二维码、控件靠下；FTP 面板没有二维码、控件
# 本来就靠上。无条件滑动在 FTP 上会把目标**滑过视口**，症状是"控件找不到"——
# 而那看起来像 UI 出问题了，其实只是脚本多滑了一下。
tap_key_or_scroll() { # $1=定位键 $2=等待秒
  local key="$1" wait_s="${2:-1.2}"
  if xy_of "$key" 3 >/dev/null 2>&1; then
    tap_key "$key" "$wait_s"
    return $?
  fi
  a shell input swipe 540 1800 540 900 300 >/dev/null 2>&1
  sleep 1.2
  tap_key "$key" "$wait_s"
}

# 读一个"可能在滚动区下方"的开关状态：先原地找，找不到再滑动。
checked_or_scroll() { # $1=控件 id
  local v
  v="$(ui_checked "$1")"
  if [[ "$v" == "true" || "$v" == "false" ]]; then
    printf '%s\n' "$v"
    return 0
  fi
  a shell input swipe 540 1800 540 900 300 >/dev/null 2>&1
  sleep 1.2
  ui_checked "$1"
}

tap_key() { # $1=定位键 $2=等待秒
  local xy x y wait_s
  xy="$(xy_of "$1" 10)" || return 1
  wait_s="${2:-1.2}"
  x="$(printf '%s\n' "$xy" | awk '{print $1}')"
  y="$(printf '%s\n' "$xy" | awk '{print $2}')"
  a shell input tap "$x" "$y" >/dev/null 2>&1
  sleep "$wait_s"
  return 0
}

top_activity() {
  a shell dumpsys activity activities 2>/dev/null | tr -d '\r' \
    | grep -m1 "topResumedActivity" | sed 's/.*u0 \([^ ]*\).*/\1/'
}

app_log() { # $1=标签
  local pid
  pid="$(a shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -z "$pid" ]]; then
    note "（应用进程不在，取不到日志）"
    return 1
  fi
  a logcat -d --pid "$pid" 2>/dev/null | tr -d '\r' > "$OUT_DIR/log-$1.txt"
  note "应用日志 → log-$1.txt"
}

# 从日志里取"扫码地址 http://…:8080/?t=<令牌>"中的令牌
qr_ticket() {
  a logcat -d 2>/dev/null | tr -d '\r' \
    | grep -o "扫码地址 http://[0-9.]*:[0-9]*/?t=[A-Za-z0-9_-]*" | tail -n1 \
    | sed 's/.*?t=//'
}

# ── 宿主机侧 curl ───────────────────────────────────────────────────────────
# `--noproxy '*'` 是必须的：开发机上常有 http_proxy 环境变量，
# 而它会把 `http://127.0.0.1:18080` 也送去代理（M2 就是被这个咬过，
# 见 `core:network` 的 PrivateNetworkProxy 注释）。
CURL=(curl -sS --noproxy '*' --max-time 20)

# ── 模拟器控制台：slirp 端口映射 ────────────────────────────────────────────
emu_redir() { # $1=宿主机端口 $2=设备端口
  if [[ ! -f "$HOME/.emulator_console_auth_token" ]]; then
    note "找不到 $HOME/.emulator_console_auth_token，无法建立端口映射"
    return 1
  fi
  local token out
  token="$(cat "$HOME/.emulator_console_auth_token" 2>/dev/null)"
  out="$({
    printf 'auth %s\n' "$token"; sleep 0.2
    printf 'redir add tcp:%s:%s\n' "$1" "$2"; sleep 0.3
    printf 'quit\n'
  } | nc -w 4 localhost "$EMU_CONSOLE_PORT" 2>/dev/null | tr -d '\r')"
  # "host port already active" 是**幂等**的另一种表述（上一次跑留下的映射），
  # 不当作失败——重跑冒烟不该因为"上次已经建过"就挂。
  if printf '%s' "$out" | grep -q "^OK$"; then
    return 0
  fi
  if printf '%s' "$out" | grep -q "already active"; then
    return 0
  fi
  note "redir 失败：$(printf '%s' "$out" | tail -n2 | tr '\n' ' ')"
  return 1
}

setup_ports() {
  emu_redir "$HOST_HTTP" "$GUEST_HTTP" || return 1
  emu_redir "$FTP_PORT" "$FTP_PORT" || return 1
  local p
  for (( p = FTP_PASSIVE_LOW; p <= FTP_PASSIVE_HIGH; p++ )); do
    emu_redir "$p" "$p" || return 1
  done
  return 0
}

# ── 场景辅助 ────────────────────────────────────────────────────────────────

prepare_content() {
  a shell "rm -rf $DEV_DIR" >/dev/null 2>&1
  a shell "mkdir -p $DEV_DIR/sub" >/dev/null 2>&1
  a shell "echo 'hello from mimi' > $DEV_DIR/readme.txt" >/dev/null 2>&1
  a shell "printf '0123456789abcdefghijklmnopqrstuvwxyz' > $DEV_DIR/range.txt" >/dev/null 2>&1
  a shell "echo '中文内容测试' > '$DEV_DIR/中文 文件名.txt'" >/dev/null 2>&1
  a shell "echo inner > $DEV_DIR/sub/inner.txt" >/dev/null 2>&1
}

dev_has() { a shell "[ -e '$1' ] && echo Y" 2>/dev/null | tr -d '\r' | grep -q Y; }

dev_ls() { a shell "ls $DEV_DIR" 2>/dev/null | tr -d '\r' | tr '\n' ' '; }

# 启动面板并（可选）拉起分享。
# 用法：panel_open [类型] [有效期毫秒]
panel_open() {
  local type="${1:-HTTP}" ttl="${2:-}"
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1
  local args=(--es share.root "local://$DEV_DIR" --es share.type "$type")
  if [[ -n "$ttl" ]]; then
    args+=(--el share.ttl_ms "$ttl")
  fi
  a shell am start -n "$PANEL" "${args[@]}" >/dev/null 2>&1
  sleep 3.5
}

# 读面板显示的账号密码（"mimi / xxxxxxxx"）
panel_cred() {
  ui_field info_cred | head -n1
}

# 拉起分享并等"运行中"；成功时把凭据写进全局 PANEL_USER / PANEL_PASS
share_start() {
  tap_key "id=action_primary" 6 || return 1
  local text
  text="$(panel_cred)"
  PANEL_USER="${text%% / *}"
  PANEL_PASS="${text##* / }"
  [[ -n "$PANEL_USER" && -n "$PANEL_PASS" ]] || return 1
  return 0
}

# 宿主机侧按设备端口构造 URL
http_base() { printf 'http://127.0.0.1:%s' "$HOST_HTTP"; }

# ── 准备 ────────────────────────────────────────────────────────────────────

step "准备"

if ! a get-state >/dev/null 2>&1; then
  printf '\033[31m连不上设备 %s（先起模拟器）\033[0m\n' "$SERIAL" >&2
  exit 2
fi
note "设备：$SERIAL  应用：$APP_ID"

# 通知权限：**先在安装侧授好**，否则首次点\"启动分享\"会弹系统权限框，
# 后续所有 UI 定位都会打在对话框上。用 pm grant 而不是点对话框：
# 系统权限框的层级在各版本上不同，点它的脚本极脆。
a shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

if ! setup_ports; then
  bad "宿主机到设备的端口映射建立失败（模拟器控制台不可用？）"
  printf '  没有端口映射，所有对端用例都会失败。\n' >&2
  exit 1
fi
note "端口映射就绪：HTTP ${HOST_HTTP}→${GUEST_HTTP}，FTP ${FTP_PORT}，被动 ${FTP_PASSIVE_LOW}-${FTP_PASSIVE_HIGH}"

prepare_content
note "测试目录：$DEV_DIR → $(dev_ls)"

# ════════════════════════════════════════════════════════════════════════════
# W1 面板打开并显示共享路径与账号
# ════════════════════════════════════════════════════════════════════════════
if want W1; then
  step "W1 面板打开（未启动态）"
  panel_open HTTP
  ui_dump
  TOP="$(top_activity)"
  PATH_TXT="$(ui_field info_path)"
  CRED="$(ui_field info_cred)"
  STATE="$(ui_field status_text)"
  note "前台=${TOP:-（空）} 状态=${STATE:-（空）} 路径=${PATH_TXT:-（空）}"
  if [[ "$TOP" == *SharePanelActivity* && "$PATH_TXT" == *MimiShare* && -n "$CRED" ]]; then
    ok "W1 面板打开并显示共享路径与账号密码"
  else
    bad "W1 面板未正确打开（前台=$TOP 路径=${PATH_TXT}）"
    ui_text | head -20 > "$OUT_DIR/W1-screen.txt"
  fi
fi

# ════════════════════════════════════════════════════════════════════════════
# W2 HTTP 启动 + W3 通知 + W4 扫码令牌 + W5..W10 HTTP 对端用例
# ════════════════════════════════════════════════════════════════════════════
if want W2 || want W3 || want W4 || want W5 || want W6 || want W7 || want W8 || want W9 || want W10; then
  step "W2 HTTP 启动与对端访问"
  panel_open HTTP
  if share_start; then
    ok "W2 HTTP 分享已启动（${PANEL_USER} / ${PANEL_PASS}）"
  else
    bad "W2 HTTP 分享未能启动"
    ui_text | head -20 > "$OUT_DIR/W2-screen.txt"
  fi

  if want W2; then
    DETAIL="$(ui_field status_detail)"
    note "状态详情：${DETAIL:-（空）}"
    if [[ "${DETAIL:-}" == *"$GUEST_HTTP"* ]]; then
      ok "W2 状态卡显示实际端口 $GUEST_HTTP"
    else
      bad "W2 状态卡未显示端口（${DETAIL}）"
    fi
  fi

  if want W3; then
    # 判据：`dumpsys notification` 里出现我们的通知通道 id。
    # 不用"通知栏有图标"这类视觉判据：它没法在无头环境里被观察。
    NOTIF="$(a shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | grep -c 'NotificationRecord(.*channel=mimi_share')"
    if [[ "${NOTIF:-0}" -gt 0 ]]; then
      ok "W3 前台服务通知已发布（NotificationRecord $NOTIF 条）"
    else
      bad "W3 没有找到分享通知（前台服务没起来？）"
      a shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | head -40 > "$OUT_DIR/W3-notification.txt"
    fi
  fi

  AUTH=(-u "$PANEL_USER:$PANEL_PASS")
  BASE="$(http_base)"

  if want W4; then
    # 扫码即用：二维码链接里带 `?t=<令牌>`，服务端认它之后下发会话 Cookie。
    # 这条链路错了的表现是"扫码进去只能看到外壳、列表全是 401"，很难自查。
    TICKET="$(qr_ticket)"
    if [[ -z "$TICKET" ]]; then
      bad "W4 日志里没有取到扫码令牌"
    else
      BY_TICKET="$("${CURL[@]}" "$BASE/api/list?t=$TICKET" -w '\n%{http_code}')"
      CODE="$(printf '%s' "$BY_TICKET" | tail -n1)"
      BODY="$(printf '%s' "$BY_TICKET" | sed '$d')"
      if [[ "$CODE" == "200" ]] && printf '%s' "$BODY" | grep -q 'readme.txt'; then
        ok "W4 扫码令牌可直接访问（无需 Basic 认证）"
      else
        bad "W4 扫码令牌未生效（code=${CODE}）"
        printf '%s\n' "$BODY" > "$OUT_DIR/W4-ticket-body.txt"
      fi
    fi
  fi

  if want W5; then
    HOME_HTML="$("${CURL[@]}" "${AUTH[@]}" "$BASE/" -w '\n%{http_code}')"
    CODE="$(printf '%s' "$HOME_HTML" | tail -n1)"
    if [[ "$CODE" == "200" ]] && printf '%s' "$HOME_HTML" | grep -q '密密文件管理'; then
      ok "W5 内置网页返回正常（HTTP ${CODE}）"
    else
      bad "W5 内置网页异常（HTTP ${CODE}）"
      printf '%s\n' "$HOME_HTML" > "$OUT_DIR/W5-home.html"
    fi
  fi

  if want W6; then
    LIST="$("${CURL[@]}" "${AUTH[@]}" "$BASE/api/list")"
    printf '%s\n' "$LIST" > "$OUT_DIR/W6-list.json"
    if printf '%s' "$LIST" | grep -q '中文 文件名.txt' \
       && printf '%s' "$LIST" | grep -q '"dir":true'; then
      ok "W6 目录列表含中文名与子目录"
    else
      bad "W6 目录列表内容不符（见 W6-list.json）"
    fi
  fi

  if want W7; then
    BODY="$("${CURL[@]}" "${AUTH[@]}" "$BASE/f/%E4%B8%AD%E6%96%87%20%E6%96%87%E4%BB%B6%E5%90%8D.txt")"
    if [[ "$BODY" == *"中文内容测试"* ]]; then
      ok "W7 中文文件名下载内容一致"
    else
      bad "W7 中文文件名下载内容不符：[$BODY]"
    fi
  fi

  if want W8; then
    "${CURL[@]}" "${AUTH[@]}" -r 0-9 -D "$OUT_DIR/W8-headers.txt" \
      -o "$OUT_DIR/W8-body.bin" "$BASE/f/range.txt" >/dev/null 2>&1
    CR="$(grep -i '^content-range' "$OUT_DIR/W8-headers.txt" | tr -d '\r' | head -n1)"
    CODE="$(head -n1 "$OUT_DIR/W8-headers.txt" | tr -d '\r' | awk '{print $2}')"
    BODY="$(cat "$OUT_DIR/W8-body.bin" 2>/dev/null)"
    note "$CR  正文=[$BODY]"
    if [[ "$CODE" == "206" && "$CR" == *"bytes 0-9/36"* && "$BODY" == "0123456789" ]]; then
      ok "W8 Range 请求返回 206 且范围正确（Content-Range: bytes 0-9/36）"
    else
      bad "W8 Range 响应不正确（code=$CODE $CR body=[$BODY]）"
    fi
  fi

  if want W9; then
    printf 'uploaded-by-smoke' > "$OUT_DIR/up.txt"
    CODE="$("${CURL[@]}" "${AUTH[@]}" -T "$OUT_DIR/up.txt" "$BASE/f/uploaded.txt" -o /dev/null -w '%{http_code}')"
    CONTENT="$(a shell "cat $DEV_DIR/uploaded.txt" 2>/dev/null | tr -d '\r')"
    if [[ "$CODE" == "200" && "$CONTENT" == "uploaded-by-smoke" ]]; then
      ok "W9 上传成功且设备侧内容一致"
    else
      bad "W9 上传失败（code=$CODE 设备侧=[$CONTENT]）"
    fi
  fi

  if want W10; then
    # 越狱必须用 `--path-as-is`：默认 curl 会在客户端就把 `..` 规范化掉，
    # 那样测到的是 curl 的行为，不是服务端的防护。
    CODE="$("${CURL[@]}" --path-as-is "${AUTH[@]}" "$BASE/f/../../../../etc/hosts" -o /dev/null -w '%{http_code}')"
    note "越狱响应码：$CODE"
    if [[ "$CODE" != "200" ]]; then
      ok "W10 路径越狱被拒（HTTP ${CODE}）"
    else
      bad "W10 路径越狱竟然成功返回 200"
    fi
  fi

  app_log W-http
fi

# ════════════════════════════════════════════════════════════════════════════
# W11 只读模式：写操作被拒且设备侧无变化
# ════════════════════════════════════════════════════════════════════════════
if want W11; then
  step "W11 HTTP 只读模式"
  # ⚠️ 顺序很关键：**先启动，再切只读**。
  #
  # 反过来（先切开关再去连）会测出一个假失败：服务端根本没启动，
  # 所有 curl 都是 000（连接拒绝），而失败信息看起来像"只读防护失效"。
  # 这个顺序同时覆盖了另一条真实路径——"运行中改配置 → 自动重启服务端"，
  # 用户改只读/匿名时走的就是它。
  panel_open HTTP
  if share_start; then
    note "已以可读写模式启动，准备切换为只读（会触发自动重启）"
  else
    bad "W11 无法启动分享"
  fi
  tap_key_or_scroll "id=switch_readonly" 9 || note "（只读开关定位失败）"
  ACCESS="$(ui_field info_access)"
  CRED="$(panel_cred)"
  PANEL_USER="${CRED%% / *}"
  PANEL_PASS="${CRED##* / }"
  note "面板权限显示：${ACCESS:-（空）}"
  if [[ "$ACCESS" != *只读* ]]; then
    bad "W11 只读开关未生效（面板显示 ${ACCESS:-空}）"
  else
    AUTH=(-u "$PANEL_USER:$PANEL_PASS")
    BASE="$(http_base)"
    BEFORE="$(dev_ls)"
    C1="$("${CURL[@]}" "${AUTH[@]}" -T "$OUT_DIR/up.txt" "$BASE/f/ro-fail.txt" -o /dev/null -w '%{http_code}')"
    C2="$("${CURL[@]}" "${AUTH[@]}" -X POST "$BASE/api/mkdir?path=&name=ro-fail-dir" -o /dev/null -w '%{http_code}')"
    C3="$("${CURL[@]}" "${AUTH[@]}" -X POST "$BASE/api/delete?path=range.txt&recursive=0" -o /dev/null -w '%{http_code}')"
    AFTER="$(dev_ls)"
    note "上传=$C1 新建=$C2 删除=$C3"
    if [[ "$C1" == "403" && "$C2" == "403" && "$C3" == "403" && "$BEFORE" == "$AFTER" ]]; then
      ok "W11 只读模式三个写操作全部 403，且设备侧内容未变"
    else
      bad "W11 只读模式防护不足（$C1/$C2/${C3}，内容变化：[$BEFORE] → [$AFTER]）"
    fi
  fi
  # 关掉只读，避免污染后续用例
  tap_key_or_scroll "id=switch_readonly" 5 >/dev/null 2>&1
fi

# ════════════════════════════════════════════════════════════════════════════
# W12 二维码可见
# ════════════════════════════════════════════════════════════════════════════
if want W12; then
  step "W12 二维码"
  panel_open HTTP
  if share_start; then
    RECT="$(ui_rect address_qr)"
    note "二维码控件边界：${RECT:-（空）}"
    if [[ -n "$RECT" ]]; then
      W="$(printf '%s\n' "$RECT" | awk '{print $3 - $1}')"
      H="$(printf '%s\n' "$RECT" | awk '{print $4 - $2}')"
      if [[ "${W:-0}" -ge 200 && "${H:-0}" -ge 200 ]]; then
        ok "W12 二维码已渲染（${W}×${H} px）"
      else
        bad "W12 二维码尺寸异常（${W}×${H}）"
      fi
    else
      bad "W12 界面上找不到二维码控件"
      ui_text | head -24 > "$OUT_DIR/W12-screen.txt"
    fi
  else
    bad "W12 无法启动 HTTP 分享，二维码用例跳过"
  fi
fi

# ════════════════════════════════════════════════════════════════════════════
# W13-W15 FTP 启动 / 列目录 / 读写闭环
# ════════════════════════════════════════════════════════════════════════════
if want W13 || want W14 || want W15; then
  step "W13 FTP 启动"
  panel_open FTP
  FTP_OK=0
  if share_start; then
    FTP_OK=1
    ok "W13 FTP 分享已启动（${PANEL_USER} / ${PANEL_PASS}）"
  else
    bad "W13 FTP 分享未能启动"
    ui_text | head -20 > "$OUT_DIR/W13-screen.txt"
  fi

  if [[ "$FTP_OK" -eq 1 ]]; then
    LISTEN="$(a shell "netstat -ltn 2>/dev/null | grep -c ':$FTP_PORT '" | tr -d '\r')"
    note "设备侧 $FTP_PORT 端口监听数：${LISTEN:-0}"
    if [[ "${LISTEN:-0}" -ge 1 ]]; then
      ok "W13 设备侧确实在监听 $FTP_PORT"
    else
      bad "W13 设备侧没有 $FTP_PORT 的监听"
    fi

    # `--ftp-skip-pasv-ip` 不可省：PASV 应答里带的是设备自己的内网地址
    # （10.0.2.15），而宿主机够不到它；跳过之后 curl 会用控制连接的主机
    # （127.0.0.1）去连数据端口，配合 redir 就能通。
    F=(--ftp-skip-pasv-ip -u "$PANEL_USER:$PANEL_PASS")
    FTP_BASE="ftp://127.0.0.1:$FTP_PORT"

    if want W14; then
      LS="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/")"
      printf '%s\n' "$LS" > "$OUT_DIR/W14-ftp-list.txt"
      SUB="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/sub/")"
      note "根目录 $(printf '%s' "$LS" | grep -c .) 行，子目录 $(printf '%s' "$SUB" | grep -c .) 行"
      if printf '%s' "$LS" | grep -q '中文 文件名.txt' \
         && printf '%s' "$LS" | grep -q 'sub' \
         && printf '%s' "$SUB" | grep -q 'inner.txt'; then
        ok "W14 FTP 列目录含中文名、子目录及其内容（UTF-8 端到端）"
      else
        bad "W14 FTP 列目录内容不符（见 W14-ftp-list.txt）"
      fi
    fi

    if want W15; then
      printf 'ftp-uploaded' > "$OUT_DIR/ftpup.txt"
      C_UP="$("${CURL[@]}" "${F[@]}" -T "$OUT_DIR/ftpup.txt" "$FTP_BASE/ftp-up.txt" -o /dev/null -w '%{http_code}')"
      UP_CONTENT="$(a shell "cat $DEV_DIR/ftp-up.txt" 2>/dev/null | tr -d '\r')"
      C_MKD="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" -Q "-MKD ftpdir" -o /dev/null -w '%{http_code}')"
      C_RN="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" -Q "-RNFR ftp-up.txt" -Q "-RNTO ftp-renamed.txt" -o /dev/null -w '%{http_code}')"
      C_DELE="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" -Q "-DELE ftp-renamed.txt" -o /dev/null -w '%{http_code}')"
      C_RMD="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" -Q "-RMD ftpdir" -o /dev/null -w '%{http_code}')"
      note "上传=$C_UP 新建=$C_MKD 改名=$C_RN 删除=$C_DELE 删目录=$C_RMD 上传内容=[$UP_CONTENT]"
      AFTER="$(dev_ls)"
      note "操作后目录：$AFTER"
      if [[ "$C_UP" == "226" && "$UP_CONTENT" == "ftp-uploaded" \
         && "$C_MKD" == "257" && "$C_RN" == "250" && "$C_DELE" == "250" && "$C_RMD" == "250" ]] \
         && [[ "$AFTER" != *ftp-renamed* && "$AFTER" != *ftpdir* ]]; then
        ok "W15 FTP 上传/新建/重命名/删除闭环全部成功"
      else
        bad "W15 FTP 写操作未全部成功（$C_UP/$C_MKD/$C_RN/$C_DELE/${C_RMD}）"
      fi
    fi
    app_log W-ftp
  fi
fi

# ════════════════════════════════════════════════════════════════════════════
# W16 FTP 只读
# ════════════════════════════════════════════════════════════════════════════
if want W16; then
  step "W16 FTP 只读模式"
  # 同 W11：必须先启动再切只读，否则连的是一个不存在的端口。
  panel_open FTP
  FTP_RO_OK=0
  if share_start; then
    FTP_RO_OK=1
    note "已以可读写模式启动，准备切换为只读（会触发自动重启）"
  else
    bad "W16 无法启动 FTP 分享"
  fi
  tap_key_or_scroll "id=switch_readonly" 9 || note "（只读开关定位失败）"
  # FTP 面板比 HTTP 短（没有二维码那一块），下滑之后 `info_access` 那一行会被
  # 推出视口、从 UI dump 里消失。所以这里读**开关自身的勾选状态**——
  # 它就在刚点过的位置上，一定还在屏幕里。
  RO_CHECKED="$(checked_or_scroll switch_readonly)"
  CRED="$(panel_cred)"
  PANEL_USER="${CRED%% / *}"
  PANEL_PASS="${CRED##* / }"
  note "只读开关状态：${RO_CHECKED:-（空）}"
  if [[ "$FTP_RO_OK" -eq 0 ]]; then
    : # 已在上面报过"无法启动"
  elif [[ "$RO_CHECKED" != "true" ]]; then
    bad "W16 只读开关未生效（checked=${RO_CHECKED}）"
  else
    F=(--ftp-skip-pasv-ip -u "$PANEL_USER:$PANEL_PASS")
    FTP_BASE="ftp://127.0.0.1:$FTP_PORT"
    BEFORE="$(dev_ls)"
    # 列表应仍然可用（只读 ≠ 不能读）
    LS="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" 2>/dev/null)"
    printf 'ro-fail' > "$OUT_DIR/ro.txt"
    C_UP="$("${CURL[@]}" "${F[@]}" -T "$OUT_DIR/ro.txt" "$FTP_BASE/ro-fail.txt" -o /dev/null -w '%{http_code}')"
    C_MKD="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" -Q "-MKD ro-fail-dir" -o /dev/null -w '%{http_code}')"
    C_DELE="$("${CURL[@]}" "${F[@]}" "$FTP_BASE/" -Q "-DELE range.txt" -o /dev/null -w '%{http_code}')"
    AFTER="$(dev_ls)"
    note "列表含 readme=$([[ "$LS" == *readme.txt* ]] && echo Y || echo N) 上传=$C_UP 新建=$C_MKD 删除=$C_DELE"
    if [[ "$LS" == *readme.txt* && "$C_UP" != "226" && "$C_MKD" != "257" \
       && "$C_DELE" != "250" && "$BEFORE" == "$AFTER" ]]; then
      ok "W16 FTP 只读：可读、三个写操作全部被拒、设备侧未变"
    else
      bad "W16 FTP 只读防护不足（$C_UP/$C_MKD/${C_DELE}；内容 [$BEFORE] → [$AFTER]）"
    fi
  fi
  tap_key_or_scroll "id=switch_readonly" 5 >/dev/null 2>&1
fi

# ════════════════════════════════════════════════════════════════════════════
# W17 会话有效期：面板关闭 → 超时 → 服务端停止 + 通知消失
# ════════════════════════════════════════════════════════════════════════════
if want W17; then
  step "W17 会话有效期（面板关闭后自动停服）"
  # 用 12 秒的有效期代替默认 5 分钟（见 ShareArgs.EXTRA_TTL_MS 的长注释）
  panel_open HTTP 12000
  if share_start; then
    LISTEN_BEFORE="$(a shell "netstat -ltn 2>/dev/null | grep -c ':$GUEST_HTTP '" | tr -d '\r')"
    note "超时前 $GUEST_HTTP 监听数：${LISTEN_BEFORE:-0}"
    # 离开面板 → 开始计时
    a shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    sleep 22
    LISTEN_AFTER="$(a shell "netstat -ltn 2>/dev/null | grep -c ':$GUEST_HTTP '" | tr -d '\r')"
    NOTIF_AFTER="$(a shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | grep -c 'NotificationRecord(.*channel=mimi_share')"
    note "超时后 $GUEST_HTTP 监听数：${LISTEN_AFTER:-0}，通知命中：${NOTIF_AFTER:-0}"
    if [[ "${LISTEN_BEFORE:-0}" -ge 1 && "${LISTEN_AFTER:-0}" -eq 0 && "${NOTIF_AFTER:-0}" -eq 0 ]]; then
      ok "W17 超时后监听与通知都被回收"
    else
      bad "W17 超时回收不完整（监听 ${LISTEN_BEFORE:-0} → ${LISTEN_AFTER:-0}，通知 ${NOTIF_AFTER:-0}）"
    fi
    app_log W17-ttl
  else
    bad "W17 无法启动分享"
  fi
fi

# ════════════════════════════════════════════════════════════════════════════
# W18 手动停止
# ════════════════════════════════════════════════════════════════════════════
if want W18; then
  step "W18 手动停止分享"
  panel_open HTTP
  if share_start; then
    LISTEN_BEFORE="$(a shell "netstat -ltn 2>/dev/null | grep -c ':$GUEST_HTTP '" | tr -d '\r')"
    # 主按钮这时是"停止分享"
    TAP_TEXT="$(ui_field action_primary)"
    note "主按钮文案：${TAP_TEXT:-（空）}"
    tap_key "id=action_primary" 5
    STATE="$(ui_field status_text)"
    LISTEN_AFTER="$(a shell "netstat -ltn 2>/dev/null | grep -c ':$GUEST_HTTP '" | tr -d '\r')"
    NOTIF_AFTER="$(a shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' | grep -c 'NotificationRecord(.*channel=mimi_share')"
    note "停止后状态=${STATE:-（空）} 监听 ${LISTEN_BEFORE:-0}→${LISTEN_AFTER:-0} 通知=${NOTIF_AFTER:-0}"
    if [[ "$STATE" == *未启动* && "${LISTEN_AFTER:-0}" -eq 0 && "${NOTIF_AFTER:-0}" -eq 0 \
       && "${LISTEN_BEFORE:-0}" -ge 1 ]]; then
      ok "W18 停止后状态归位、端口释放、通知撤销"
    else
      bad "W18 停止不彻底（状态=$STATE 监听=${LISTEN_AFTER:-0} 通知=${NOTIF_AFTER:-0}）"
    fi
    app_log W18-stop
  else
    bad "W18 无法启动分享"
  fi
fi

# ── 清理 ────────────────────────────────────────────────────────────────────
if [[ "$KEEP_DATA" -eq 0 ]]; then
  a shell am force-stop "$APP_ID" >/dev/null 2>&1
  a shell "rm -rf $DEV_DIR" >/dev/null 2>&1
fi

# ── 汇总 ────────────────────────────────────────────────────────────────────

printf '\n\033[1m[ m4 ] 结果：通过 %s  失败 %s  跳过 %s\033[0m\n' "$PASS" "$FAIL" "$SKIP"
if [[ -n "$FAILED_ITEMS" ]]; then
  printf '失败项：\n%s\n' "$FAILED_ITEMS"
fi
printf '证据目录：%s\n' "$OUT_DIR"

exit $(( FAIL > 0 ? 1 : 0 ))
