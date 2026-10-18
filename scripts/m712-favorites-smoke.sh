#!/usr/bin/env bash
# ==========================================================================
# M7.12 · 收藏文件夹功能端到端验证（**只跑 release 包**，竖屏 + 抽屉）
#
#   ⚠️ 教训（2026-10-15）：user_rotation 在这台模拟器上对启动后的本应用不生效，
#   App 实际一直竖屏 —— 竖屏下侧栏抽屉是隐藏的，验证侧栏必须先拉抽屉。
#   竖屏入口：工具栏左上角导航按钮（横屏才是"上一级"，竖屏开抽屉）。
#
#   F0 准备：装包 + 授权 + 造测试目录 m712-fav/{alpha,beta}（alpha 内放探针文件）
#   F1 进到 m712-fav，长按行 alpha 进多选 → 点「收藏」瓦片
#   F2 拉出抽屉 → 侧栏「快速访问」分组出现且含 alpha
#   F3 点侧栏里的 alpha → 抽屉收起、右侧直达该目录（探针文件可见）
#   F4 再拉抽屉 → 长按侧栏 alpha 节点 → 取消收藏 → 分组消失
#
# 用法：scripts/m712-favorites-smoke.sh
# 前置：模拟器在跑；release 包已构建。
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

export PATH="${HOME}/.cmfa-toolchain/android-sdk/platform-tools:${PATH}"
ADB="${ADB:-adb}"
APP_ID="com.miyuetech.mimifiles"
PY="${HOME}/.workbuddy/binaries/python/envs/default/bin/python"
XML="/tmp/m712.xml"

OK=0
BAD=0
ok()  { echo "  ✓ $*"; OK=$((OK + 1)); }
bad() { echo "  ✗ $*"; BAD=$((BAD + 1)); }

a() { "${ADB}" "$@"; }

# uiautomator dump 偶发抓错窗口/陈旧快照：校验 dump 里能看到本应用特征文本，
# 不合格重抓，最多 4 轮
ui_dump() {
  for attempt in 1 2 3 4; do
    a shell rm -f /sdcard/m712.xml >/dev/null 2>&1
    a shell uiautomator dump /sdcard/m712.xml >/dev/null 2>&1
    rm -f "${XML}"
    a shell cat /sdcard/m712.xml > "${XML}" 2>/dev/null
    if [[ -s "${XML}" ]] && grep -qE 'text="(m712-fav|内部存储|快速访问|alpha)"' "${XML}" 2>/dev/null; then
      return 0
    fi
    sleep 1.5
  done
  return 1
}

# 找 text= 的坐标；$2 xmax 过滤；$3 "left"=返回左端+25（避开展开箭头）
find_text_xy() {
  "${PY}" - "$1" "${2:-99999}" "${XML}" "${3:-center}" <<'PYEOF'
import sys, re
text, xmax, path, mode = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
src = open(path, errors='ignore').read()
hits = []
for m in re.finditer(r'<node[^>]*text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', src):
    x1, y1, x2, y2 = map(int, m.groups())
    x = (x1 + x2) // 2
    if x < xmax:
        hits.append((x1, y1, x2, y2))
# 抽屉打开时 dump 里同名文本有两个（列表行在前、抽屉树节点在后——nav_pane 是
# 布局的最后一个子视图）。抽屉交互必须取**最后一个**，取第一个会点到被
# 抽屉盖住的列表行上（点了个寂寞，M7.12 冒烟三次假红的根源）。
if mode == 'left':
    x1, y1, x2, y2 = hits[-1]
    print(x1 + 25, (y1 + y2) // 2)
elif hits:
    x1, y1, x2, y2 = hits[-1]
    print((x1 + x2) // 2, (y1 + y2) // 2)
PYEOF
}

has_text() {
  "${PY}" - "$1" "${XML}" <<'PYEOF'
import sys
src = open(sys.argv[2], errors='ignore').read()
print('yes' if f'text="{sys.argv[1]}"' in src else 'no')
PYEOF
}

# 找 content-desc 节点（工具栏按钮）
find_desc_xy() {
  "${PY}" - "$1" "${XML}" <<'PYEOF'
import sys, re
src = open(sys.argv[2], errors='ignore').read()
m = re.search(r'content-desc="' + re.escape(sys.argv[1]) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', src)
if not m:
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="' + re.escape(sys.argv[1]) + r'"', src)
if m:
    print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
PYEOF
}

echo "──────── F0 · 准备：装包 / 授权 / 造目录 ────────"
a install -r -g "${PROJECT_DIR}/app/build/outputs/apk/release/app-arm64-v8a-release.apk" >/dev/null 2>&1 \
  && ok "release 包已安装" || bad "安装失败"
a shell appops set "${APP_ID}" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1
a shell mkdir -p /sdcard/m712-fav/alpha /sdcard/m712-fav/beta
a shell "echo probe > /sdcard/m712-fav/alpha/probe.txt" >/dev/null 2>&1
a logcat -c >/dev/null 2>&1
a shell am start -n "${APP_ID}/.splash.SplashActivity" >/dev/null 2>&1
sleep 8

nav()
{
  ui_dump || return 1
  local xy
  xy=$(find_text_xy "$1" "${2:-99999}")
  if [[ -n "$xy" ]]; then
    a shell input tap $xy
    sleep 2.5
    return 0
  fi
  return 1
}

open_drawer()
{
  for i in 1 2 3; do
    ui_dump || true
    # 竖屏：工具栏导航按钮（content-desc「上一级」）就是抽屉开关
    local xy
    xy=$(find_desc_xy "存储位置")
    [[ -z "$xy" ]] && xy=$(find_desc_xy "上一级")
    if [[ -n "$xy" ]]; then
      a shell input tap $xy
      sleep 2
      ui_dump || true
      # 抽屉特征：nav_title 应用名 或 侧栏分组标题；两者都在窗格左侧
      [[ "$(has_text "快速访问" "${XML}")" == "yes" || "$(has_text "本机存储" "${XML}")" == "yes" ]] && return 0
    fi
    sleep 1.5
  done
  return 1
}

echo "──────── F1 · 长按行 alpha 进多选 → 点「收藏」瓦片 ────────"
if ! nav "内部存储"; then echo "  (已在内部存储内，跳过)"; fi
nav "m712-fav" || { bad "导航失败：找不到 m712-fav"; exit 1; }

ui_dump || exit 1
XY=$(find_text_xy "alpha")
if [[ -z "$XY" ]]; then bad "列表里没有 alpha"; exit 1; fi
X=$(echo "$XY" | awk '{print $1}'); Y=$(echo "$XY" | awk '{print $2}')
# 长按进多选（应用真实交互）；M7.16 起双栏化，收藏在底栏「更多」溢出菜单里：
# 点「更多」→ 弹出菜单里点「收藏」；标签随状态二选一；最多重试三轮
MENU=""
for i in 1 2 3; do
  a shell input swipe "$X" "$Y" "$X" "$Y" 900
  sleep 2
  ui_dump || true
  MORE=$(find_text_xy "更多")
  if [[ -n "$MORE" ]]; then
    a shell input tap $MORE
    sleep 1.5
    ui_dump || true
  fi
  MENU=$(find_text_xy "收藏")
  [[ -n "$MENU" ]] && break
  a shell input keyevent 4 >/dev/null 2>&1; sleep 1
done
if [[ -n "$MENU" ]]; then
  a shell input tap $MENU
  sleep 3.5   # 等 DataStore 写入 → collect → 状态回灌
  ok "F1 底栏「更多」→「收藏」完成"
else
  bad "F1 多选里没有「收藏」入口（底栏更多菜单）"
fi

echo "──────── F2 · 抽屉侧栏「快速访问」出现且含 alpha ────────"
if open_drawer; then
  if [[ "$(has_text "快速访问" "${XML}")" == "yes" ]] && [[ -n "$(find_text_xy "alpha" 720)" ]]; then
    ok "F2 侧栏「快速访问」分组出现且含 alpha"
  else
    bad "F2 侧栏没有出现收藏项"
  fi
else
  bad "F2 抽屉拉不出来"
fi

echo "──────── F3 · 点侧栏 alpha → 直达该目录 ────────"
ui_dump || true
XY=$(find_text_xy "alpha" 720 left)
if [[ -n "$XY" ]]; then
  a shell input tap $XY
  sleep 3
  ui_dump || true
  if [[ "$(has_text "probe.txt" "${XML}")" == "yes" ]]; then
    ok "F3 右侧列表已切到 alpha（探针文件可见）= 收藏直达成立"
  else
    bad "F3 点击收藏后右侧没有切到 alpha"
  fi
else
  bad "F3 侧栏里找不到 alpha 节点"
fi

echo "──────── F4 · 拉抽屉 → 长按侧栏 alpha → 取消收藏 ────────"
if open_drawer; then
  ui_dump || true
  XY=$(find_text_xy "alpha" 720)
  if [[ -n "$XY" ]]; then
    X=$(echo "$XY" | awk '{print $1}'); Y=$(echo "$XY" | awk '{print $2}')
    a shell input swipe "$X" "$Y" "$X" "$Y" 900
    sleep 2.5
    ui_dump || true
    if [[ "$(has_text "快速访问" "${XML}")" == "no" ]]; then
      ok "F4 长按侧栏节点已取消收藏（快速访问分组消失）"
    else
      bad "F4 取消收藏后侧栏仍有快速访问分组"
    fi
  else
    bad "F4 侧栏里找不到 alpha 节点"
  fi
else
  bad "F4 抽屉拉不出来"
fi

echo "════════ 结果 ════════"
echo "通过 ${OK} · 失败 ${BAD}"
[[ "$BAD" == "0" ]] && echo "M7.12 SMOKE ALL GREEN" || echo "M7.12 SMOKE FAILED"
exit "$BAD"
