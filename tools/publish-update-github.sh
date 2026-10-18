#!/bin/bash
# ==============================================================================
# 密密文件管理 · GitHub Release + jsDelivr CDN 更新通道发布脚本（主力通道）
#
# 用法:
#   bash tools/publish-update-github.sh "<更新说明>" dist/mimifiles-x.y.z-arm64.apk
#
# 架构（GitHub 与 CDN 分流）:
#   清单（小文件）  update/manifest.json 提交进仓库，
#                   客户端经 jsDelivr CDN 拉取:
#                     https://cdn.jsdelivr.net/gh/<REPO>@main/update/manifest.json
#                   jsDelivr 对文件内容有小时级缓存，所以发布末尾会主动 purge。
#   安装包（大文件）挂 GitHub Release，**不进仓库**；清单里的 url 字段是
#                   Release 资产的绝对地址:
#                     https://github.com/<REPO>/releases/download/<tag>/<apk>
#                   下载时 302 到 GitHub 自己的对象 CDN；客户端
#                   HttpURLConnection instanceFollowRedirects=true 原生支持。
#
# 做的事（顺序不可换）:
#   ① 版本号取自 gradle.properties（单一事实源），并用 aapt2 校验 APK 内
#      真实版本号与之一致（防"包是新版、清单声明旧版"的静默事故）
#   ② R8 keep 闸门（tools/check-r8-keeps.sh）
#   ③ 生成新清单（含 sha256 / size / 绝对下载地址）
#   ④ 提交 update/manifest.json 并推送 main —— **先推清单再建 Release**，
#      让 tag 快照里同时包含与之匹配的清单
#   ⑤ 建 Release（tag=v<versionName>）并上传 APK 资产
#   ⑥ purge jsDelivr 缓存
#   ⑦ 自检：jsDelivr 清单 200 且 versionCode 是新的；Release 资产可达
#
# 令牌:
#   环境变量 GITHUB_TOKEN，缺省读 ~/WorkBuddy/keys/github-pat.txt。
#   细粒度 PAT 需 Contents: Read/Write（提交清单、建 Release 都在 Contents）。
#   令牌只在本机出现，绝不写进仓库。
# ==============================================================================
set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROPS_FILE="$PROJ_DIR/gradle.properties"
REPO="${GITHUB_REPO:-byl669/MimiFiles}"
BRANCH="${GITHUB_BRANCH:-main}"
MANIFEST_IN_REPO="update/manifest.json"
JSDELIVR="https://cdn.jsdelivr.net/gh/${REPO}@${BRANCH}/${MANIFEST_IN_REPO}"
PURGE_URL="https://purge.jsdelivr.net/gh/${REPO}@${BRANCH}/${MANIFEST_IN_REPO}"

PY="$(command -v python3 || true)"
[ -z "$PY" ] && PY="/usr/bin/python3"

# ── 令牌 ─────────────────────────────────────────────────────────────────────
TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$HOME/WorkBuddy/keys/github-pat.txt" ]; then
  TOKEN="$(tr -d '[:space:]' < "$HOME/WorkBuddy/keys/github-pat.txt")"
fi
[ -z "$TOKEN" ] && { echo "PUBLISH_FAIL 找不到 GitHub 令牌（GITHUB_TOKEN 或 ~/WorkBuddy/keys/github-pat.txt）"; exit 1; }

gh_api() { # $1=method $2=path $3=datafile(optional) → body + 最后行 HTTP=code
  local method="$1" path="$2" data="${3:-}" code body
  if [ -n "$data" ]; then
    body="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 300 \
      -X "$method" -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" -d @"$data" \
      "https://api.github.com$path" -w '\nHTTP=%{http_code}')"
  else
    body="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 300 \
      -X "$method" -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
      "https://api.github.com$path" -w '\nHTTP=%{http_code}')"
  fi
  code="$(printf '%s' "$body" | grep -o 'HTTP=[0-9]*' | tail -1 | cut -d= -f2)"
  printf '%s' "$body" | sed '$d'
  [ "$code" = "200" ] || [ "$code" = "201" ] || { echo "PUBLISH_FAIL GitHub API $method $path → HTTP $code"; return 1; }
}

# ── 参数 ─────────────────────────────────────────────────────────────────────
NOTES="${1:-}"; shift || true
[ -z "$NOTES" ] && { echo "PUBLISH_FAIL 用法: publish-update-github.sh <更新说明> <apk...>"; exit 1; }
APK_ARGS=("$@")
[ ${#APK_ARGS[@]} -eq 0 ] && { echo "PUBLISH_FAIL 缺少 APK 参数"; exit 1; }
for f in "${APK_ARGS[@]}"; do [ -f "$f" ] || { echo "PUBLISH_FAIL 文件不存在: $f"; exit 1; }; done

# ── 版本号（gradle.properties 单一事实源）────────────────────────────────────
read -r VERSION_CODE VERSION_NAME < <("$PY" - "$PROPS_FILE" <<'PYEOF'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
code = re.search(r'^mimi\.versionCode\s*=\s*(\d+)', s, re.M)
name = re.search(r'^mimi\.versionName\s*=\s*(\S+)', s, re.M)
print((code.group(1) if code else ""), (name.group(1) if name else ""))
PYEOF
)
[ -z "$VERSION_CODE" ] && { echo "PUBLISH_FAIL 读不到 mimi.versionCode"; exit 1; }
TAG="v${VERSION_NAME}"
echo "== 版本: $VERSION_NAME($VERSION_CODE)  tag=$TAG  repo=$REPO"

# ── APK 版本一致性（aapt2 读 APK 自己的清单）────────────────────────────────
AAPT2=""
for cand in "${ANDROID_HOME:-}/build-tools"/*/aapt2 "$HOME/.cmfa-toolchain/android-sdk/build-tools"/*/aapt2; do
  [ -x "$cand" ] && AAPT2="$cand" && break
done
if [ -z "$AAPT2" ]; then echo "PUBLISH_FAIL 未找到 aapt2"; exit 1; fi
for f in "${APK_ARGS[@]}"; do
  BADGING="$("$AAPT2" dump badging "$f" 2>/dev/null | head -n1)"
  APK_CODE="$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
  APK_NAME="$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
  [ "$APK_CODE" = "$VERSION_CODE" ] && [ "$APK_NAME" = "$VERSION_NAME" ] || {
    echo "PUBLISH_FAIL $(basename "$f") 版本号($APK_NAME($APK_CODE))与 gradle.properties($VERSION_NAME($VERSION_CODE))不一致"; exit 1; }
  echo "== 版本一致性 ✓ $(basename "$f")"
done

# ── R8 keep 闸门 ─────────────────────────────────────────────────────────────
for f in "${APK_ARGS[@]}"; do
  bash "$PROJ_DIR/tools/check-r8-keeps.sh" "$f" || { echo "PUBLISH_FAIL R8 keep 自检未通过"; exit 1; }
done

# ── 同名 tag 已存在则拒绝（防覆盖历史版本）───────────────────────────────────
if gh_api GET "/repos/${REPO}/releases/tags/${TAG}" >/dev/null 2>&1; then
  echo "PUBLISH_FAIL Release $TAG 已存在，换个版本号，别覆盖历史版本。"; exit 1
fi

# ── 生成新清单 ───────────────────────────────────────────────────────────────
TMP_MANIFEST="$(mktemp /tmp/mimi-gh-manifest.XXXXXX.json)"
trap 'rm -f "$TMP_MANIFEST"' EXIT
"$PY" - "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "$REPO" "$TAG" "${APK_ARGS[@]}" > "$TMP_MANIFEST" <<'PYEOF'
import datetime, hashlib, json, sys
code, name, notes, repo, tag = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
files = []
for p in sys.argv[6:]:
    base = p.rstrip('/').split('/')[-1]
    low = base.lower()
    if "arm64" in low:
        abi = "arm64-v8a"
    elif "v7a" in low or "armv7" in low:
        abi = "armeabi-v7a"
    else:
        sys.stderr.write(f"PUBLISH_FAIL 无法判断 ABI: {base}\n"); sys.exit(2)
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    files.append({
        "abi": abi,
        # 绝对地址：Release 资产没有稳定目录前缀；客户端 resolveUrl 对
        # http(s) 开头的 url 原样使用（UpdatePlannerTest 有断言）。
        "url": f"https://github.com/{repo}/releases/download/{tag}/{base}",
        "sha256": h.hexdigest(),
        "size": __import__("os").path.getsize(p),
    })
print(json.dumps({
    "versionCode": code, "versionName": name,
    "publishedAt": datetime.date.today().isoformat(),
    "notes": notes, "files": files, "minSupported": 0,
}, ensure_ascii=False, indent=2))
PYEOF
echo "== 清单预览 =="; cat "$TMP_MANIFEST"

# ── 提交清单并推送（先于 Release，让 tag 快照含匹配的清单）───────────────────
mkdir -p "$PROJ_DIR/$(dirname "$MANIFEST_IN_REPO")"
cp "$TMP_MANIFEST" "$PROJ_DIR/$MANIFEST_IN_REPO"
git -C "$PROJ_DIR" add "$MANIFEST_IN_REPO"
if git -C "$PROJ_DIR" diff --cached --quiet; then
  echo "== 清单无变化（同版本重发？）——继续发 Release"
else
  git -C "$PROJ_DIR" commit -q -m "update: manifest $VERSION_NAME($VERSION_CODE)" 
  git -C "$PROJ_DIR" push -q origin "$BRANCH"
  echo "== 清单已推送 $BRANCH"
fi

# ── 建 Release + 上传资产 ────────────────────────────────────────────────────
REL_JSON="$(mktemp /tmp/mimi-gh-rel.XXXXXX.json)"
trap 'rm -f "$TMP_MANIFEST" "$REL_JSON"' EXIT
"$PY" - "$TAG" "$VERSION_NAME" "$NOTES" > "$REL_JSON" <<'PYEOF'
import json, sys
tag, name, notes = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "tag_name": tag, "target_commitish": "main",
    "name": f"{name}", "body": notes,
    "draft": False, "prerelease": False,
}, ensure_ascii=False))
PYEOF
REL_ID="$(gh_api POST "/repos/${REPO}/releases" "$REL_JSON" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
echo "== Release $TAG 已创建 (id=$REL_ID)"
for f in "${APK_ARGS[@]}"; do
  UP="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 600 \
    -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" \
    "https://uploads.github.com/repos/${REPO}/releases/${REL_ID}/assets?name=$(basename "$f")" \
    -o /dev/null -w '%{http_code}')"
  [ "$UP" = "201" ] || { echo "PUBLISH_FAIL 上传资产 $(basename "$f") HTTP=$UP"; exit 1; }
  echo "== 已上传 $(basename "$f")"
done

# ── purge jsDelivr ───────────────────────────────────────────────────────────
sleep 2
PURGE_CODE="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 60 \
  "$PURGE_URL" -o /dev/null -w '%{http_code}')"
echo "== jsDelivr purge → HTTP $PURGE_CODE"

# ── 自检 ─────────────────────────────────────────────────────────────────────
sleep 3
BODY="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 60 -H 'Cache-Control: no-cache' "$JSDELIVR" -w '\nHTTP=%{http_code}')"
CODE="$(printf '%s' "$BODY" | grep -o 'HTTP=[0-9]*' | tail -1 | cut -d= -f2)"
[ "$CODE" = "200" ] || { echo "PUBLISH_FAIL jsDelivr 清单 HTTP=$CODE"; exit 1; }
# 注意：BODY 尾行是 HTTP=code，先剥掉再喂 JSON 解析；解析失败按"旧清单"处理
MANIFEST_ONLY="$(printf '%s' "$BODY" | sed '$d')"
REMOTE_CODE="$(printf '%s' "$MANIFEST_ONLY" | "$PY" -c 'import json,sys
try:
    print(json.load(sys.stdin).get("versionCode", ""))
except Exception:
    print("")' 2>/dev/null || true)"
if [ "$REMOTE_CODE" != "$VERSION_CODE" ]; then
  echo "PUBLISH_FAIL jsDelivr 清单不是新版（读到的 versionCode=‘${REMOTE_CODE:-空}’，期望 $VERSION_CODE）"
  exit 1
fi
echo "== 自检 jsDelivr 清单 ✓ versionCode=$REMOTE_CODE"
for f in "${APK_ARGS[@]}"; do
  H="$(curl -sIL --noproxy '*' --connect-timeout 15 --max-time 60 \
    "https://github.com/${REPO}/releases/download/${TAG}/$(basename "$f")" -o /dev/null -w '%{http_code}')"
  echo "== 自检 Release 资产 $(basename "$f") → HTTP $H"
  [ "$H" = "200" ] || { echo "PUBLISH_FAIL Release 资产不可达"; exit 1; }
done

echo "PUBLISH_OK"
echo "PUBLIC_MANIFEST=$JSDELIVR"
