#!/usr/bin/env bash
# ==========================================================================
# SMB 连接复现（M4.1）—— 一次性排查用
#
# ## 为什么需要它
# 用户报告"填完服务器信息点测试有报错"，而这句描述对应至少四种完全不同的原因：
#   ① 应用真的有 bug；② 密码错；③ 共享名错；④ 网络不通。
# 光看截图分不出来。所以这里在宿主机上跑一个**已知可用**的 SMB2 服务端
# （impacket 的 smbserver），让应用去连它 —— 把"服务端到底配了什么"这个
# 无法排除的变量固定下来，剩下的差异就只能是应用。
#
# ## 两个环境坑（都踩过，已固化在脚本里）
#  1. **必须禁用输入法**。AVD 里是 Gboard，它的自动纠错会把 `SHARE` 补成 `SHARED`，
#     于是"应用连不上"其实是脚本把共享名打错了。禁用之后既无纠错、也无软键盘遮挡。
#     禁用不影响 `adb shell input text`：它走 `InputManager.injectInputEvent`
#     直接注入按键，不经过 IME。
#  2. **每个字段都要回读**。只发 `input text` 不看结果，会让"文本没进去"伪装成
#     "应用连不上"（第一次跑就是这么翻车的：用户名是空的，于是走了 guest，
#     报的是 `SMB2GuestSigningRequiredException` —— 一个看上去很吓人、
#     却与真实问题无关的错误）。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="$HOME/.cmfa-toolchain/android-sdk/platform-tools:$PATH"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles.debug"
PAGE="$APP_ID/com.miyuetech.mimifiles.feature.connection.ConnectionEditActivity"
IME="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
UI_PY="$HOME/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="$PROJECT_DIR/scripts/m2-ui.py"

# ⚠️ 变量名必须带 `SMB_` 前缀（M4.1 踩过）。
# 原来叫 `USERNAME` / `HOST` / `PORT` —— 它们**在 shell 环境里本来就存在**
# （`USERNAME` 通常是本机登录名），于是 `${USERNAME:-mimi}` 取到的是环境里那个值：
# 脚本以为在测"用户名 mimi"，实际发出去的是 "miyue"，而失败信息看起来像应用的问题。
# 参数名一旦可能与环境撞车，就必须加前缀。
SMB_HOST="${SMB_HOST:-10.0.2.2}"
SMB_PORT="${SMB_PORT:-4450}"
SMB_SHARE="${SMB_SHARE-SHARE}"
SMB_USER="${SMB_USER-mimi}"
SMB_PASS="${SMB_PASS-testpass123}"

a() { "$ADB" "$@"; }

ui_dump() {
  a shell uiautomator dump /sdcard/m41.xml >/dev/null 2>&1
  a shell cat /sdcard/m41.xml > /tmp/m41.xml 2>/dev/null
  [[ -s /tmp/m41.xml ]]
}

ui_text() { # $1=控件 id
  ui_dump || return 1
  "$UI_PY" "$UI_SCRIPT" text-of /tmp/m41.xml "id=$1" 2>/dev/null | head -n1
}

ui_xy() { # $1=控件 id
  ui_dump || return 1
  "$UI_PY" "$UI_SCRIPT" center /tmp/m41.xml "id=$1" 2>/dev/null | head -n1
}

dump_all_text() {
  ui_dump || return 1
  "$UI_PY" "$UI_SCRIPT" dump-text /tmp/m41.xml
}

fill() { # $1=控件 id $2=内容
  local xy x y got
  xy="$(ui_xy "$1")" || { echo "  ✗ 找不到 $1"; return 1; }
  x="$(printf '%s' "$xy" | awk '{print $1}')"
  y="$(printf '%s' "$xy" | awk '{print $2}')"
  a shell input tap "$x" "$y" >/dev/null 2>&1
  sleep 0.5
  # 先清空：编辑已有连接时字段里有旧值，直接追加会拼出个四不像
  a shell input keyevent --longpress KEYCODE_DEL >/dev/null 2>&1
  a shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
    a shell input keyevent KEYCODE_DEL >/dev/null 2>&1
  done
  a shell input text "$2" >/dev/null 2>&1
  sleep 0.5
  got="$(ui_text "$1")"
  if [[ "$got" == "$2" ]]; then
    echo "  ✓ $1 = $got"
    return 0
  fi
  echo "  ✗ $1 期望 [$2]，实际 [$got]"
  return 1
}

echo "════════ SMB 连接复现 ════════"
echo "目标 smb://${SMB_HOST}:${SMB_PORT}/${SMB_SHARE}  账号=${SMB_USER}  签名=要求"
echo

# 环境固化：禁用输入法（理由见文件头）
a shell ime disable "$IME" >/dev/null 2>&1
sleep 1

a shell am force-stop "$APP_ID" >/dev/null 2>&1
a logcat -c >/dev/null 2>&1
a shell am start -n "$PAGE" >/dev/null 2>&1
sleep 3

echo "── 填表（逐字段回读）──"
fill input_host "$SMB_HOST"
fill input_port "$SMB_PORT"
fill input_share "$SMB_SHARE"
fill input_username "$SMB_USER"
fill input_password "$SMB_PASS"

echo
echo "── 点「测试」──"
xy="$(ui_xy btn_test)" || { echo "找不到测试按钮"; exit 1; }
a shell input tap "$(printf '%s' "$xy" | awk '{print $1}')" "$(printf '%s' "$xy" | awk '{print $2}')" >/dev/null 2>&1
sleep 10

echo
echo "── 应用日志 ──"
a logcat -d 2>/dev/null | grep -E "MimiConn|MimiSmb" | tail -25

echo
echo "── 界面上的提示（Snackbar 不进 UI dump，故同时靠上面日志）──"
dump_all_text | head -5 || true
