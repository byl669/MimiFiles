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
#   ⑤ 建 Release（tag=v<versionName>）并上传 APK 资产。同版本号若已存在，
#      仅当已发布资产的 sha256 digest 与本地文件**完全一致**时才续跑
#      （跳过建库与上传，只补跑后续步骤）；不一致则报错要求换版本号，
#      避免覆盖已发布的历史版本。
#   ⑥ purge jsDelivr 缓存
#   ⑦ 自检：raw 清单必须是新版（硬闸门，证明清单真的推上去了）；
#      四个 jsDelivr 服务商（cdn/gcore/testingcf/fastly）逐个探测并退避重试，
#      一个都不新鲜时末行输出 PUBLISH_DEGRADED 而不是 PUBLISH_OK —— 各服务商
#      缓存互相独立（实测同一次 purge 后 cdn=33 而 gcore=35）。
#      Release 资产必须可达（硬闸门）。
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
FASTLY="https://fastly.jsdelivr.net/gh/${REPO}@${BRANCH}/${MANIFEST_IN_REPO}"
GCORE="https://gcore.jsdelivr.net/gh/${REPO}@${BRANCH}/${MANIFEST_IN_REPO}"
TESTINGCF="https://testingcf.jsdelivr.net/gh/${REPO}@${BRANCH}/${MANIFEST_IN_REPO}"
RAW="https://raw.githubusercontent.com/${REPO}/${BRANCH}/${MANIFEST_IN_REPO}"
# 直读仓库内容的 API：**没有 CDN 缓存层**，是"清单有没有真的推上去"的权威判据。
# raw.githubusercontent.com 带约 5 分钟边缘缓存，刚 push 完常仍返回旧文件。
MANIFEST_API="https://api.github.com/repos/${REPO}/contents/${MANIFEST_IN_REPO}?ref=${BRANCH}"
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

# 按 tag 取 Release JSON；不存在则输出空串（不报错、不打印）。
release_by_tag_json() {
  local body code
  body="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 30 \
    -H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/${REPO}/releases/tags/${TAG}" \
    -w '\nHTTP=%{http_code}' 2>/dev/null || true)"
  code="$(printf '%s' "$body" | grep -o 'HTTP=[0-9]*' | tail -1 | cut -d= -f2 || true)"
  [ "$code" = "200" ] && printf '%s' "$body" | sed '$d'
  return 0
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

# ── 同名 tag 已存在：只有"本地 APK 与已发布资产逐字节相同"才允许续跑 ─────────
# 原则仍是"不覆盖已发布的历史版本"。但发布可能在**末尾自检**阶段失败（Release
# 与资产其实都已上线），此时重跑同一版本号是安全的：内容一致就没有任何覆盖。
# 判据用 GitHub 资产自带的 sha256 digest（API 的 digest 字段），不是大小。
EXIST_RELEASE_JSON="$(release_by_tag_json)"
RESUME_OK=""
if [ -n "$EXIST_RELEASE_JSON" ]; then
  echo "== Release $TAG 已存在，核对已发布资产与本地文件是否同源"
  printf '%s' "$EXIST_RELEASE_JSON" | "$PY" -c 'import hashlib, json, os, sys
d = json.load(sys.stdin)
by_name = {a["name"]: a.get("digest") for a in d.get("assets", [])}
ok = True
for p in sys.argv[1:]:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    want = "sha256:" + h.hexdigest()
    base = os.path.basename(p)
    got = by_name.get(base)
    if got == want:
        print(f"   ✓ {base} 与本地逐字节相同，本次只续跑后续步骤")
    else:
        print(f"   ✗ {base} 不一致：已发布 {got or '缺失'}，本地 {want}")
        ok = False
print("SAME" if ok else "DIFFERENT")' "${APK_ARGS[@]}" > /tmp/mimi-resume.txt 2>&1 || true
  cat /tmp/mimi-resume.txt | sed '$d'
  VERDICT="$(tail -1 /tmp/mimi-resume.txt)"
  rm -f /tmp/mimi-resume.txt
  if [ "$VERDICT" != "SAME" ]; then
    echo "PUBLISH_FAIL Release $TAG 已存在且内容不同，换个版本号，别覆盖历史版本。"
    exit 1
  fi
  RESUME_OK=1
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
# Release 有两种来路：新发（POST 创建），或同版本续跑（复用前面已核对过
# "资产逐字节相同"的那一个）。续跑时不再上传资产——内容本来就一样。
if [ -n "$RESUME_OK" ]; then
  REL_ID="$(printf '%s' "$EXIST_RELEASE_JSON" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
else
  REL_ID="$(gh_api POST "/repos/${REPO}/releases" "$REL_JSON" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  echo "== Release $TAG 已创建 (id=$REL_ID)"
fi
for f in "${APK_ARGS[@]}"; do
  if [ -n "$RESUME_OK" ]; then
    echo "== 资产 $(basename "$f") 已在位且逐字节相同，跳过上传"
    continue
  fi
  UP="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 600 \
    -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/octet-stream" \
    --data-binary @"$f" \
    "https://uploads.github.com/repos/${REPO}/releases/${REL_ID}/assets?name=$(basename "$f")" \
    -o /dev/null -w '%{http_code}')"
  case "$UP" in
    201) echo "== 已上传 $(basename "$f")" ;;
    # 422 = 同名资产已存在（例如上次上传成功但后面步骤失败）。内容是否一致
    # 由前面那道 sha256 digest 核对负责，这里不重复判断。
    422) echo "== 资产 $(basename "$f") 已存在，跳过上传" ;;
    *) echo "PUBLISH_FAIL 上传资产 $(basename "$f") HTTP=$UP"; exit 1 ;;
  esac
done

# ── purge jsDelivr ───────────────────────────────────────────────────────────
sleep 2
PURGE_CODE="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 60 \
  "$PURGE_URL" -o /dev/null -w '%{http_code}')"
echo "== jsDelivr purge → HTTP $PURGE_CODE"

# ── 自检 ─────────────────────────────────────────────────────────────────────
#
# 这里的"新鲜度"分两类，判据必须分开，否则会互相误导：
#
#   ① 清单有没有**真的提交到分支** —— 用 GitHub API 直读（无缓存层）。
#      硬闸门。push 失败、tag 指错，只有它会暴露。
#      ⚠️ 不能用 raw.githubusercontent.com 做这件事：它带约 5 分钟边缘缓存，
#      刚推完常常仍返回旧文件。本轮发布 0.7.24 时就被它误判过一次
#      （仓库里已是 36，raw 还发 35）。
#   ② CDN 节点有没有跟上 —— jsDelivr 的 cdn./gcore./testingcf./fastly. 是
#      **不同 CDN 服务商、不同缓存命名空间**，同一次 purge 后可能停在不同代际
#      （2026-10-29 实测：cdn=33、fastly=33、gcore=35、testingcf=35）。
#      给退避重试；一个都不新鲜时降级为 DEGRADED 而不判死 —— 此刻 Release 与
#      清单都已上线、缓存会自行过期，脚本没有任何可执行的补救动作，硬判失败
#      只会留下半完成状态。
sleep 3
# 读某个 URL 上的 versionCode；拿不到 / 解析失败都输出空串，由调用方判断。
probe_manifest() { # $1=url
  local body code only
  body="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 60 \
    -H 'Cache-Control: no-cache' "$1" -w '\nHTTP=%{http_code}' 2>/dev/null || true)"
  # `|| true`：镜像连不上时 grep 找不到 HTTP= 会返回 1，
  # 在 `set -e -o pipefail` 下会让这句赋值直接杀脚本——而这里要的是"记 WARN"。
  code="$(printf '%s' "$body" | grep -o 'HTTP=[0-9]*' | tail -1 | cut -d= -f2 || true)"
  if [ "$code" != "200" ]; then printf ''; return 0; fi
  # 尾行是 HTTP=code，先剥掉再喂 JSON 解析
  only="$(printf '%s' "$body" | sed '$d')"
  printf '%s' "$only" | "$PY" -c 'import json,sys
try:
    print(json.load(sys.stdin).get("versionCode", ""))
except Exception:
    print("")' 2>/dev/null || true
}

# ① 权威判据：直读分支上的清单文件本体
API_CODE="$(curl -sS --noproxy '*' --connect-timeout 15 --max-time 30 \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.github.raw' \
  "$MANIFEST_API" 2>/dev/null \
  | "$PY" -c 'import json,sys
try:
    print(json.load(sys.stdin).get("versionCode", ""))
except Exception:
    print("")' 2>/dev/null || true)"
if [ "$API_CODE" != "$VERSION_CODE" ]; then
  echo "PUBLISH_FAIL 分支 ${BRANCH} 上的清单不是新版（API 直读 versionCode=‘${API_CODE:-不可达}’，期望 ${VERSION_CODE}）——清单没提交成功？"
  exit 1
fi
echo "== 自检 仓库清单（API 直读，无缓存）✓ versionCode=${API_CODE}"

# ② CDN 新鲜度：轮询到有一个节点返回新版为止，最多约 60 秒
CDN_NAMES=(cdn gcore testingcf fastly)
CDN_URLS=("$JSDELIVR" "$GCORE" "$TESTINGCF" "$FASTLY")
FRESH_CDN=""
attempt=1
while [ "$attempt" -le 7 ]; do
  i=0
  while [ "$i" -lt 4 ]; do
    c="$(probe_manifest "${CDN_URLS[$i]}")"
    if [ "$c" = "$VERSION_CODE" ]; then FRESH_CDN="${CDN_NAMES[$i]}"; break; fi
    i=$((i + 1))
  done
  [ -n "$FRESH_CDN" ] && break
  if [ "$attempt" -lt 7 ]; then
    echo "== 还没有新鲜的 CDN 节点（第 ${attempt} 次探测），10 秒后重试…"
    sleep 10
  fi
  attempt=$((attempt + 1))
done

# 逐个报状态，滞后的一律列出来（不静默）
i=0
while [ "$i" -lt 4 ]; do
  c="$(probe_manifest "${CDN_URLS[$i]}")"
  if [ "$c" = "$VERSION_CODE" ]; then
    echo "== 自检 ${CDN_NAMES[$i]} 清单 ✓ versionCode=$c"
  else
    echo "== WARN ${CDN_NAMES[$i]} 清单滞后（versionCode=‘${c:-不可达}’，期望 ${VERSION_CODE}）"
  fi
  i=$((i + 1))
done
if [ -z "$FRESH_CDN" ]; then
  echo "== WARN 没有任何 CDN 节点返回新版：国内用户会暂时看不到这次更新，待缓存过期后自愈。"
fi

# raw 只报状态、不作判据：它带约 5 分钟边缘缓存，刚发布时通常仍是旧值。
# 客户端仍把它排在首位（内容最实时、海外首选），所以值得看一眼。
RAW_NOW="$(probe_manifest "$RAW")"
if [ "$RAW_NOW" = "$VERSION_CODE" ]; then
  echo "== 自检 raw 清单 ✓ versionCode=${RAW_NOW}"
else
  echo "== INFO raw 清单仍滞后（versionCode=‘${RAW_NOW:-不可达}’）——约 5 分钟边缘缓存，属正常，不作判据"
fi

for f in "${APK_ARGS[@]}"; do
  H="$(curl -sIL --noproxy '*' --connect-timeout 15 --max-time 60 \
    "https://github.com/${REPO}/releases/download/${TAG}/$(basename "$f")" -o /dev/null -w '%{http_code}')"
  echo "== 自检 Release 资产 $(basename "$f") → HTTP $H"
  [ "$H" = "200" ] || { echo "PUBLISH_FAIL Release 资产不可达"; exit 1; }
done

if [ -z "$FRESH_CDN" ]; then
  # 末行给 PUBLISH_DEGRADED 而不是 PUBLISH_OK：不是失败（发布已落地、缓存会自愈），
  # 但也不能当成"全绿"——下次发布前值得回看这条。
  echo "PUBLISH_DEGRADED 发布已落地，但 CDN 缓存尚未跟上（见上面 WARN）"
else
  echo "PUBLISH_OK"
fi
echo "PUBLIC_MANIFEST=$JSDELIVR"
