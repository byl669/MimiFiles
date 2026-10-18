#!/usr/bin/env bash
# ==========================================================================
# M5.1 · SMB 双通道 release 端到端冒烟
#
# ## 为什么必须在【release 包】上跑（这是本轮事故的直接教训）
# M4.1 的 SMB 复现脚本（`m41-smb-repro.sh`）连的是 **debug 包**
# （`applicationId = com.miyuetech.mimifiles.debug`）。debug **不混淆、不裁剪**，
# 于是 jcifs-ng 的**传递依赖** MBassador 被 R8 整类删除这件事
# （`proguard-rules.pro` 里缺 `-keep class net.engio.mbassy.**`）在那一轮
# 完全看不出来 —— 结论写的是"已修好"，而用户装的是 release，
# 一进 SMB 目录就报：
#
#   java.lang.RuntimeException: r9.c: The provided handler invocation
#     did not specify the necessary constructor h(SubscriptionContext)
#
# 其中 `r9.c` = 混淆后的 `MessageBusException`、`h` = 被改名的 `MessageHandler`。
#
# 所以本脚本：
#   ① 只跑 release 包；
#   ② 专门覆盖 **SMB1 通道**（jcifs-ng → MBassador），也就是踩雷的那条路；
#   ③ 断言的是**日志文案**，不是"界面闪了一下"。
#
# ## 两个场景
#   A. SMB2 服务端 + 默认设置     → 断言目录列表里能看到真实文件名
#   B. 强制 SMB1                  → 分两种情况：
#      ① `--expect-listing`（连**真 SMB1 服务端**）：断言同样能看到真实文件名。
#         这是"jcifs 通道可用"的完整证据：NTLM 认证（需要 MD4）+ 目录列举都过了。
#      ② 不加（连 SMB2-only 服务端）：SMB1 协商注定失败，只验失败文案可读。
#
# ## 三条硬断言（任一命中即失败）
#   ① `SubscriptionContext` / `MessageBusException`
#      → `net.engio.mbassy` 被 R8 剪掉（M5.1 事故 #1）；
#   ② `NoSuchAlgorithmException` / `no such algorithm: MD4`
#      → BC 的 `digest.MD4$Mappings` 被剪掉（M5.1 事故 #2）；
#   ③ 界面上出现 `SmbCrypto` 给的那句"MD4 算法不可用"
#      → 说明 `core/fs-smb` 的建连前自检拦下了失败（自检本身工作正常，
#        但**根因仍在**，所以同样算失败）。
#
# ## 为什么不用日志断言
# release 变体的 `MimiLog.mirrorToLogcat` 是 false（侧载分发不该把主机名写进
# logcat），所以证据链只能是 **UI dump 出来的真实内容**。
#
# ## 场景 B 的环境（真 SMB1 服务端）
#   python3 scripts/m51-smb-server.py 4450 false mimi testpass123 SHARE /tmp/m51-smbshare
# 第二个参数 `false` = 关掉 SMB2，只提供 SMB1（jcifs 走的那条路）。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="$HOME/.cmfa-toolchain/android-sdk/platform-tools:$PATH"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles"

PY="$HOME/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="$PROJECT_DIR/scripts/m2-ui.py"
IME="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

SMB_HOST="${SMB_HOST:-10.0.2.2}"
SMB_PORT="${SMB_PORT:-4451}"
SMB_SHARE="${SMB_SHARE-SHARE}"
SMB_USER="${SMB_USER-mimi}"
SMB_PASS="${SMB_PASS-testpass123}"

FORCE_SMB1="no"
LABEL="default"
# 是否断言"目录列表里能看到真实文件"。
#   · 场景 A（SMB2 服务端）→ 必须看得到；
#   · 场景 B 若连的是**真 SMB1 服务端**（`--expect-listing`）→ 也必须看得到
#     （这才是 SMB1 通道真正可用的证据：过了 NTLM 认证 + 列得动目录）；
#   · 场景 B 连 SMB2-only 服务端 → 只验"失败文案可读"，不要求列表。
LIST_EXPECT="no"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force-smb1) FORCE_SMB1="yes"; shift ;;
    --expect-listing) LIST_EXPECT="yes"; shift ;;
    --label) LABEL="$2"; shift 2 ;;
    --port) SMB_PORT="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done
[[ "$FORCE_SMB1" == "no" ]] && LIST_EXPECT="yes"

a() { "$ADB" "$@"; }
ROUND_TAG="m51-$LABEL"
XML=/tmp/"$ROUND_TAG".xml

ui_dump() {
  a shell uiautomator dump /sdcard/"$ROUND_TAG".xml >/dev/null 2>&1
  a shell cat /sdcard/"$ROUND_TAG".xml > "$XML" 2>/dev/null
  [[ -s "$XML" ]]
}

ui_text() { ui_dump || return 1; "$PY" "$UI_SCRIPT" text-of "$XML" "id=$1" 2>/dev/null | head -n1; }
ui_xy()   { ui_dump || return 1; "$PY" "$UI_SCRIPT" center  "$XML" "$1" 2>/dev/null | head -n1; }

tap_xy() { a shell input tap "$1" "$2" >/dev/null 2>&1; }

# 点一个用 `<key>=<value>` 描述的控件；找不到就报错（返回 1）。
tap_sel() {
  local xy
  xy="$(ui_xy "$1")" || { echo "  ✗ 找不到控件 $1"; return 1; }
  tap_xy "$(printf '%s' "$xy" | awk '{print $1}')" "$(printf '%s' "$xy" | awk '{print $2}')"
}

fill() { # $1=控件 id  $2=内容
  local xy x y got
  xy="$(ui_xy "id=$1")" || { echo "  ✗ 找不到 $1"; return 1; }
  x="$(printf '%s' "$xy" | awk '{print $1}')"
  y="$(printf '%s' "$xy" | awk '{print $2}')"
  tap_xy "$x" "$y"; sleep 0.5
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

echo "════════ SMB release 冒烟 · 场景 $LABEL ════════"
echo "目标 smb://${SMB_HOST}:${SMB_PORT}/${SMB_SHARE}  账号=${SMB_USER}  强制SMB1=${FORCE_SMB1}"
echo

# 输入法必须先禁：Gboard 的自动纠错会把 `SHARE` 补成 `SHARED`
a shell ime disable "$IME" >/dev/null 2>&1

a shell am force-stop "$APP_ID" >/dev/null 2>&1
# 清数据让每轮从"零连接"开始；MANAGE_EXTERNAL_STORAGE 随之丢失，立刻补回，
# 否则应用会停在授权引导页，跑不到浏览页的溢出菜单。
a shell pm clear "$APP_ID" >/dev/null 2>&1
a shell appops set "$APP_ID" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
a logcat -c >/dev/null 2>&1

echo "── 导航：主界面 → 溢出菜单 → 网络连接 → + ──"
a shell am start -n "$APP_ID/.splash.SplashActivity" >/dev/null 2>&1
sleep 5
tap_sel "desc=More options" || exit 1; sleep 1.5
tap_sel "text=网络连接"      || exit 1; sleep 2.5
tap_sel "id=fab_add"         || exit 1; sleep 2.5

echo
echo "── 填表（逐字段回读）──"
fill input_host "$SMB_HOST" || exit 1
fill input_port "$SMB_PORT" || exit 1
fill input_share "$SMB_SHARE" || exit 1
fill input_username "$SMB_USER" || exit 1
fill input_password "$SMB_PASS" || exit 1

if [[ "$FORCE_SMB1" == "yes" ]]; then
  echo
  echo "── 展开高级选项 → 滚动 → 勾「强制 SMB1」──"
  tap_sel "id=btn_advanced" || exit 1; sleep 1.2
  # 展开后面板在折叠线以下，内容还躺在屏幕外（`advanced_panel` 只有十几像素高）。
  # 不滚动就直接找开关会「找不到控件」——而人去看屏幕时它明明已经展开了。
  a shell input swipe 540 1800 540 800 300 >/dev/null 2>&1; sleep 1.5
  tap_sel "id=switch_force_smb1" || exit 1; sleep 0.8
  # 回读开关状态；读不到即视为失败，避免"以为勾上了"
  ST="$(ui_dump && "$PY" "$UI_SCRIPT" checked "$XML" "id=switch_force_smb1" 2>/dev/null | head -n1)"
  echo "  switch_force_smb1 checked=[$ST]"
fi

echo
echo "── 点「测试」──"
tap_sel "id=btn_test" || exit 1
sleep 10

# 截图留证。**不要以为日志够用**：release 变体的 `MimiLog.mirrorToLogcat`
# 由 `BuildConfig.DEBUG` 决定（见 `FileApp.onCreate`），也就是 **false** ——
# 侧载分发时不该把主机名/路径写进 logcat。所以本脚本的证据链是
# 「UI dump 出来的真实内容」，不是日志。
a shell screencap -p /sdcard/"$ROUND_TAG"-test.png >/dev/null 2>&1
a pull /sdcard/"$ROUND_TAG"-test.png /tmp/"$ROUND_TAG"-test.png >/dev/null 2>&1

echo
echo "── 保存 → 返回主界面 → 抽屉 → 进入该连接的根目录 ──"
tap_sel "id=btn_save" || exit 1
sleep 3
# 连接列表里点一条是**编辑**（`openEditor`），不是进入浏览。
# 真正能"进目录"的入口在主界面抽屉的「网络位置」分组下——这也是用户的路径。
a shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 2
tap_sel "desc=存储位置" || { echo "  ✗ 打不开抽屉"; exit 1; }
sleep 1.5
tap_sel "text=$SMB_HOST" || { echo "  ✗ 抽屉里没有这条连接（保存失败？）"; exit 1; }
sleep 8

dump_now="$(ui_dump && "$PY" "$UI_SCRIPT" dump-text "$XML" 2>/dev/null)"
printf '%s\n' "$dump_now" | head -25

echo
echo "── 断言 ──"
FAIL=0

# ⚠️ 文本判定用 python，不用 grep。
# 界面文案里有中文（「无法读取此位置」），而本机 shell 的 grep 在非 UTF-8 locale 下
# **会漏匹配** —— 场景 B 实测：dump 里明明有那串字，`grep -q` 却返回不匹配，
# 于是断言报"抓不到可读文案"。判据本身出错比漏检更糟，所以统一交给 python 做。
DUMP_FILE=/tmp/"$ROUND_TAG"-dump.txt
printf '%s\n' "$dump_now" > "$DUMP_FILE"

VERDICT="$("$PY" - "$DUMP_FILE" "$LIST_EXPECT" <<'PYEOF'
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
expect_listing = sys.argv[2] == "yes"

# ① R8 剪断反射依赖的**两类**特征串（M5.1 的两次真实事故）
crypto = {
    "SubscriptionContext": "MBassador 反射链被剪（事故 #1）",
    "MessageBusException": "MBassador 反射链被剪（事故 #1）",
    "NoSuchAlgorithmException": "BC MD4 被剪（事故 #2）",
    "no such algorithm: MD4": "BC MD4 被剪（事故 #2）",
    "MD4 算法": "SmbCrypto 建连前自检拦下了 MD4 缺失（根因仍在）",
}
# ② "已越过认证"的协议层特征。
#    jcifs 的调用顺序是 **会话建立(NTLM，需要 MD4) → tree connect → Trans2 目录列举**。
#    只要报的是 Trans2 阶段的错，就证明前面两步都成功了，也就是 MD4 真的可用。
#    三类文案都算：
#      · `服务端未能完成该操作…` —— `NT_UNSUCCESSFUL(0xC0000001)` 的映射文案，
#        impacket 的 SMB1 服务端在目录列举这一步实测就返回这个状态；
#      · `不支持目录列举` —— 同一件事在别处的说法；
#      · `Trans2` / `Invalid operation for SHARE` —— 映射表还没覆盖时的原始文案
#        （保留它们是为了"文案改版不许把这个判据改瞎"）。
#    反过来说：如果 MD4 坏了，会话建立阶段就会抛 CIFSUnsupportedCryptoException，
#    界面会是上面 `crypto` 里的那句，**永远到不了这里** —— 所以这个判据有区分力。
past_auth = ["服务端未能完成该操作", "不支持目录列举", "Trans2", "Invalid operation for SHARE"]

crypto_hit = False
for key, why in crypto.items():
    if key in text:
        crypto_hit = True
        print("CRYPTO\t%s\t%s" % (key, why))

if expect_listing:
    pymissing = [f for f in ("readme.txt", "photos", "sub") if f not in text]
    if pymissing:
        print("MISSING_FILE\t%s" % ",".join(pymissing))
elif not crypto_hit and not any(k in text for k in past_auth):
    # 只在**没有**命中加密错误时才报这条：两者都报等于同一件事说两遍，
    # 而"命中加密错误"本身已经把结论说清楚了（认证没过去）。
    print("NOT_PAST_AUTH\t界面既没有协议层错误，也没有加密错误，无法判定走到了哪一步")
PYEOF
)"

while IFS=$'\t' read -r kind f1 f2; do
  [ -n "$kind" ] || continue
  case "$kind" in
    CRYPTO)
      echo "  ❌ 命中「${f1}」—— ${f2}"
      FAIL=1
      ;;
    MISSING_FILE)
      echo "  ❌ 目录列表缺少：${f1}"
      FAIL=1
      ;;
    NOT_PAST_AUTH)
      echo "  ❌ ${f1}"
      FAIL=1
      ;;
  esac
done <<< "$VERDICT"

if [[ "$VERDICT" != *"CRYPTO"* ]]; then
  echo "  ✓ 未出现任何 R8 剪断反射依赖的痕迹（MBassador / BC MD4）"
fi

if [[ "$LIST_EXPECT" == "yes" ]]; then
  if [[ "$VERDICT" != *"MISSING_FILE"* ]]; then
    echo "  ✓ 目录列表完整（readme.txt / photos / sub 都在）"
  fi
elif [[ "$VERDICT" != *"NOT_PAST_AUTH"* ]]; then
  # 这一句才是场景 B 的**核心判据**：失败点落在协议层，说明 NTLM 认证（需要 MD4）
  # 已经成功 —— 也就是本轮修复真正生效的证据。
  echo "  ✓ 失败点已越过认证、落在协议层（Trans2 目录列举）→ NTLM/MD4 可用"
  # 只把**报错那几行**打出来。原来打 `head -n 4` 打的是界面顶部的「存储位置/上一级」，
  # 正好把要看的那句漏掉 —— 打印证据也要按"证据在哪"去取，而不是按前几行。
  "$PY" - "$DUMP_FILE" <<'PYEOF'
import sys
lines = [l.strip() for l in open(sys.argv[1], encoding="utf-8", errors="replace") if l.strip()]
marks = ("无法读取此位置", "无法打开", "连接失败", "连不上")
start = next((i for i, l in enumerate(lines) if any(m in l for m in marks)), None)
if start is not None:
    print("    界面文案：" + " ｜ ".join(lines[start:start + 2]))
PYEOF
fi

echo
if [[ $FAIL -eq 0 ]]; then echo "场景 ${LABEL}：通过 ✅"; else echo "场景 ${LABEL}：失败 ❌"; fi
exit $FAIL
