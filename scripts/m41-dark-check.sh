#!/usr/bin/env bash
# ==========================================================================
# M4.1 · 深色模式图标对比度取证
#
# ## 判据
# 每个"图标按钮"与它背后底色的 WCAG 对比度必须 ≥ 3.0
# （WCAG 2.1 对非文本图形对象的要求）。
#
# ## 为什么必须"截图 + 逐控件算"，而不是"看代码有没有写 tint"
# 因为 tint 有三条互相独立的生效路径：
#   ① 布局 XML 的 `android:tint`；
#   ② 布局 XML 的 `app:navigationIconTint` —— **只管导航图标**；
#   ③ 代码里的 `imageTintList`；
#   ④ 图标自身 XML 里的 `android:tint`（本项目 M4.1 采用的统一做法）。
# 只看代码很容易得出"我写过 tint 了"的错觉，而真实成因恰恰是"以为 ② 能覆盖菜单图标"
# —— `MaterialToolbar` 对菜单项图标一概不管（已反编译核对）。
#
# 另一半理由更硬：**整块区域的平均对比度测不出这种问题**。
# 顶栏整条量出来是 9.08（因为标题文字是浅色），单独量那三个图标才露出 1.10。
# 所以这里逐个控件量。
#
# ## 用法
#   scripts/m41-dark-check.sh            # 深色模式（正式判据）
#   scripts/m41-dark-check.sh --light    # 浅色对照：这些图标在浅色下本来就该是深色，
#                                        # 所以浅色必须同样通过 —— 它是**对照组**，
#                                        # 用来证明"改了之后浅色没被改坏"
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="$HOME/.cmfa-toolchain/android-sdk/platform-tools:$PATH"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles.debug"
ENTRY="$APP_ID/com.miyuetech.mimifiles.splash.SplashActivity"
UI_PY="$HOME/.workbuddy/binaries/python/envs/default/bin/python"
UI_SCRIPT="$PROJECT_DIR/scripts/m2-ui.py"
CHECK_PY="$PROJECT_DIR/scripts/m41-checks.py"

MIN_RATIO="${MIN_RATIO:-3.0}"

MODE="dark"
[[ "${1:-}" == "--light" ]] && MODE="light"

# ⚠️ `cmd uimode night` 只认 `yes` / `no` / `auto` / `custom`。
# 传 `dark` / `light` 这类值**不会报错**，它只是不生效 —— 于是"浅色对照组"其实
# 还在深色下跑，两次结果一模一样，而"一样"很容易被当成"没问题"。
# 这是 M4.1 踩到的坑：对照组必须**先证明自己真的切过去了**，否则它只是第二遍实验。
NIGHT_ARG="yes"
[[ "$MODE" == "light" ]] && NIGHT_ARG="no"

PASS=0
FAIL=0
SKIP=0
FAILED_ITEMS=()

a() { "$ADB" "$@"; }

ui_dump() {
  local i
  for i in 1 2 3 4 5; do
    a shell uiautomator dump /sdcard/dk.xml >/dev/null 2>&1
    a shell cat /sdcard/dk.xml > /tmp/dk.xml 2>/dev/null
    if [[ -s /tmp/dk.xml ]] && grep -q "hierarchy" /tmp/dk.xml 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

rect_of() { # $1=定位键 → "left top right bottom"
  ui_dump || return 1
  "$UI_PY" "$UI_SCRIPT" rect /tmp/dk.xml "$1" 2>/dev/null | head -n1
}

tap_spec() { # $1=定位键 $2=等待秒
  local xy
  ui_dump || return 1
  xy="$("$UI_PY" "$UI_SCRIPT" center /tmp/dk.xml "$1" 2>/dev/null | head -n1)"
  [[ -z "$xy" ]] && return 1
  a shell input tap "$(printf '%s' "$xy" | awk '{print $1}')" "$(printf '%s' "$xy" | awk '{print $2}')" >/dev/null 2>&1
  sleep "${2:-1.5}"
  return 0
}

# 量一个控件。$1=显示名 $2=定位键 $3=缺失时算通过(1)/算失败(0)
measure() {
  local name="$1" spec="$2" optional="${3:-1}" rect
  rect="$(rect_of "$spec")" || rect=""
  if [[ -z "$rect" ]]; then
    if [[ "$optional" -eq 1 ]]; then
      printf '  %-22s 跳过  （当前界面没有它）\n' "$name"
      SKIP=$((SKIP + 1))
    else
      printf '  %-22s FAIL  （应存在却找不到）\n' "$name"
      FAIL=$((FAIL + 1))
      FAILED_ITEMS+=("$name(缺失)")
    fi
    return 0
  fi
  local l t r b
  l="$(printf '%s' "$rect" | awk '{print $1}')"; t="$(printf '%s' "$rect" | awk '{print $2}')"
  r="$(printf '%s' "$rect" | awk '{print $3}')"; b="$(printf '%s' "$rect" | awk '{print $4}')"

  a exec-out screencap -p > /tmp/dk.png 2>/dev/null
  local out ratio ok
  out="$("$UI_PY" "$CHECK_PY" contrast /tmp/dk.png "$l" "$t" "$r" "$b" 2>/dev/null | tail -n1)"
  ratio="$(printf '%s' "$out" | sed -n 's/.*ratio=\([0-9.]*\).*/\1/p')"
  if [[ -z "$ratio" ]]; then
    printf '  %-22s ?     （测量失败）\n' "$name"
    FAIL=$((FAIL + 1)); FAILED_ITEMS+=("$name(测量失败)")
    return 1
  fi
  ok="$(awk -v v="$ratio" -v m="$MIN_RATIO" 'BEGIN { print (v + 0 >= m) ? "yes" : "no" }')"
  if [[ "$ok" == "yes" ]]; then
    printf '  %-22s OK    %s\n' "$name" "$out"
    PASS=$((PASS + 1))
  else
    printf '  %-22s FAIL  %s\n' "$name" "$out"
    FAIL=$((FAIL + 1)); FAILED_ITEMS+=("$name($ratio)")
  fi
}

echo "════════ 深色模式图标对比度取证（模式 ${MODE}，下限 ${MIN_RATIO}:1）════════"
echo

a shell cmd uimode night "$NIGHT_ARG" >/dev/null 2>&1
sleep 2
# 自查：模式真的切过去了吗？不查的话"没切成功"会伪装成"两种模式表现一致"。
ACTUAL="$(a shell cmd uimode night 2>/dev/null | tr -d '\r' | sed -n 's/.*: *//p' | head -n1)"
EXPECT="yes"; [[ "$MODE" == "light" ]] && EXPECT="no"
if [[ "$ACTUAL" != "$EXPECT" ]]; then
  echo "✗ 系统夜间模式没有切换成功：期望 ${EXPECT}，实际 ${ACTUAL:-（读不到）}"
  echo "  （实测：`cmd uimode night` 对非法取值不报错也不生效，必须用 yes/no/auto）"
  exit 2
fi
echo "系统夜间模式：${ACTUAL} ✓"
echo

a shell am force-stop "$APP_ID" >/dev/null 2>&1
a shell am start -n "$ENTRY" >/dev/null 2>&1
sleep 7

echo "── ① 浏览页顶栏（菜单图标：MaterialToolbar 只给导航图标上色）──"
measure "顶栏·上一级"   "id=action_up"        0
measure "顶栏·视图模式" "id=action_view_mode"  0
measure "顶栏·排序"     "id=action_sort"       1

echo
echo "── ② 文件列表行尾「更多」──"
measure "行尾·更多"     "id=rowMore"           0

echo
echo "── ③ 空状态大图标 ──"
# ## 为什么造一个叫 `AAAEmpty` 的目录，而不是走"拉开抽屉 → 导航树 → 逐层点进去"
# 竖屏下导航树在抽屉里，而"从屏幕最左缘滑开抽屉"会与系统的返回手势抢事件
# （实测：edge-to-edge + 手势导航下，从 x=5 起滑不打开抽屉）。
# 用一个**排序最靠前**的空目录，就只需要在列表里点一下 —— 一步到位，
# 而且不依赖抽屉、不依赖滚动位置。
# 前提：应用启动后停在 `内部存储`（/sdcard）根，且默认按名称升序、目录在前。
a shell mkdir -p /sdcard/AAAEmpty >/dev/null 2>&1
a shell am force-stop "$APP_ID" >/dev/null 2>&1
a shell am start -n "$ENTRY" >/dev/null 2>&1
sleep 6
if tap_spec "under:file_list/text=AAAEmpty" 2.5; then
  measure "空状态·图标" "id=emptyIcon" 1
else
  printf '  %-22s 跳过  （列表里点不到 AAAEmpty）\n' "空状态·图标"; SKIP=$((SKIP + 1))
fi

echo
echo "════════ 汇总 ════════"
echo "通过 $PASS / 失败 $FAIL / 跳过 $SKIP"
if [[ "$FAIL" -gt 0 ]]; then
  echo "未达标：${FAILED_ITEMS[*]}"
  exit 1
fi
if [[ "$PASS" -eq 0 ]]; then
  echo "⚠️ 一个都没量到 —— 判据没有生效，不要当成通过"
  exit 1
fi
echo "全部达标（≥ ${MIN_RATIO}:1）"
