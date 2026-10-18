#!/bin/bash
# ==============================================================================
# 密密文件管理 · 应用内自更新发布脚本
#
# 用法:
#   bash tools/publish-update.sh "<更新说明>" dist/mimifiles-x.y.z-arm64-release.apk
#   bash tools/publish-update.sh --dry-run "<更新说明>" dist/xxx.apk      # 只生成本地清单，不上传
#   bash tools/publish-update.sh --check                                  # 只看远端当前是什么
#   bash tools/publish-update.sh --no-build "<更新说明>" <已归档的包>       # 跳过发布前构建（重发历史版本）
#
# 做的事（顺序不可换）:
#   ① 发布前构建一次并确认成功（见下方"发布前构建"）
#   ② 从 gradle.properties / app/build.gradle.kts 提取版本号与 capability 段（单一事实源）
#   ③ 逐个 APK 算 SHA-256、大小，推出 ABI → 生成 manifest.json
#   ④ **先传所有 APK，最后传 manifest** —— 反过来会出现"清单已指向、包还是 404"的窗口期，
#      而客户端在那个窗口里点更新会拿到一个 404，用户看到的是"更新失败"
#   ⑤ 公网侧逐个 HEAD 自检 HTTP 200
#
# 通道（M7.17 起定位变化）:
#   主力通道已迁到 GitHub Release + jsDelivr CDN（tools/publish-update-github.sh）。
#   本脚本只剩一个职责：把最新版同步发布到**自建 NAS 通道**，给还指着旧清单
#   地址的老客户端当一次"跳板"——它们升级到跳板版之后，客户端内置的清单
#   地址就是 GitHub CDN 了。
#
#   自建通道的 WebDAV 凭据/地址**不入库**：全部从 tools/publish-local.env
#   读取（该文件被 .gitignore 排除）。模板见 tools/publish-local.env.example。
# ==============================================================================
set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$PROJ_DIR/app/build.gradle.kts"
PROPS_FILE="$PROJ_DIR/gradle.properties"

# ── 自建通道配置（凭据外置，绝不硬编码进脚本）─────────────────────────────────
ENV_FILE="$PROJ_DIR/tools/publish-local.env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1091
  source "$ENV_FILE"
else
  echo "PUBLISH_FAIL 缺少 $ENV_FILE（自建通道凭据不入库，仅存本机）。"
  echo "             参照 tools/publish-local.env.example 创建后再跑；"
  echo "             只发 GitHub 通道请改用 tools/publish-update-github.sh。"
  exit 1
fi
: "${DAV_BASE:?publish-local.env 缺 DAV_BASE}"
: "${DAV_PREFIX:?publish-local.env 缺 DAV_PREFIX}"
: "${PUBLIC_BASE:?publish-local.env 缺 PUBLIC_BASE}"
: "${DAV_USER:?publish-local.env 缺 DAV_USER}"
: "${DAV_PASS:?publish-local.env 缺 DAV_PASS}"

DRY_RUN=0
CHECK_ONLY=0
SKIP_BUILD=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --dry-run)  DRY_RUN=1 ;;
    --check)    CHECK_ONLY=1 ;;
    --no-build) SKIP_BUILD=1 ;;
    *)          ARGS+=("$a") ;;
  esac
done

PY="$(command -v python3 || true)"
[ -z "$PY" ] && PY="/usr/bin/python3"

# ── capability 段 ─────────────────────────────────────────────────────────────
SEG="$("$PY" - "$GRADLE_FILE" <<'PYEOF'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'app/update/([0-9a-f]{32})/', s)
print(m.group(1) if m else "")
PYEOF
)"
if [ -z "$SEG" ]; then
  echo "PUBLISH_FAIL 无法从 app/build.gradle.kts 提取 capability 段（app/update/<32位hex>/）"
  exit 1
fi
REL_DIR="app/update/$SEG"
MANIFEST_URL="$PUBLIC_BASE/$REL_DIR/manifest.json"

# ── --check：只看远端现状 ────────────────────────────────────────────────────
if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "== capability 段: $SEG =="
  echo "== 清单地址: $MANIFEST_URL =="
  BODY="$(curl -sk --noproxy '*' --connect-timeout 10 --max-time 30 -w '\nHTTP=%{http_code}' \
          -H 'Cache-Control: no-cache' "$MANIFEST_URL" 2>&1)"
  CODE="$(printf '%s' "$BODY" | grep -o 'HTTP=[0-9]*' | tail -1 | cut -d= -f2)"
  echo "== 远端 HTTP $CODE =="
  printf '%s\n' "$BODY" | sed '$d'
  [ "$CODE" = "200" ] || exit 1
  exit 0
fi

NOTES="${ARGS[0]:-}"
if [ -z "$NOTES" ]; then
  echo "PUBLISH_FAIL 用法: publish-update.sh [--dry-run|--no-build] <更新说明> <apk...>"
  exit 1
fi
APK_ARGS=("${ARGS[@]:1}")
if [ ${#APK_ARGS[@]} -eq 0 ]; then
  echo "PUBLISH_FAIL 缺少 APK 参数"
  exit 1
fi
for f in "${APK_ARGS[@]}"; do
  [ -f "$f" ] || { echo "PUBLISH_FAIL 文件不存在: $f"; exit 1; }
done

# ── 发布前构建 ────────────────────────────────────────────────────────────────
#
# ## 为什么必须有这一步（2026-10-14 实测：这是本功能唯一一次差点造成线上事故的地方）
# 一次 `./gradlew :app:assembleRelease` 因为陈旧的 ViewBinding 生成物**编译失败**，
# 而调用它的命令行是用 `;` 连接的，于是发布脚本照旧把 build 目录里**上一次的旧 APK**
# 当成新包发布了 —— 清单里 sha256 与旧包一致、公网自检全绿、输出 `PUBLISH_OK`。
# 结果是：**线上声明的是新版本号，用户下到的却是旧代码的包**。
# 这类错误全链路绿灯，只有把包装到设备上才会发现功能没变。
#
# ## 判据为什么是"再构建一次"而不是"比时间戳"
# 中间写过两版时间戳判据，**两版都不对**：
#   · 第一版比"APK 的 mtime 比源码新" —— `cp` 到 `dist/` 会把目的文件的 mtime 刷成
#     "现在"，于是任何旧包复制一次都显得很新，判据完全失效；
#   · 第二版比"构建产物的 mtime 比源码新" —— 它在"改了源码又原样改回来"时**误报**
#     （Gradle 按内容判 UP-TO-DATE、不会重建，而 mtime 已经变大）。
#
# 而"再构建一次"是唯一**精确**的判据：构建系统本来就掌握"产物与源码是否一致"，
# 直接问它。副作用正好是我们要的语义 —— **发布前必须有一次成功的构建**，
# 而这正是当初那次事故的直接原因（构建失败却被继续发布）。
if [ "$SKIP_BUILD" -eq 0 ] && [ "$DRY_RUN" -eq 0 ]; then
  echo "== 发布前构建（确认产物与源码一致）=="
  if ! ( cd "$PROJ_DIR" && ./gradlew :app:assembleRelease -q ); then
    echo "PUBLISH_FAIL 构建失败，已中止发布。"
    echo "             ⚠️ 编译失败时 build/outputs/apk/release/ 里会留着上一次的旧 APK，"
    echo "                直接发布它会造成「线上是新版本号、用户下到旧代码」这种事故。修掉编译错误后重跑。"
    exit 1
  fi
  # 内容一致性：要发布的文件必须就是当前的构建产物（挡住"复制了一份旧的归档包"）
  CANON_DIR="$PROJ_DIR/app/build/outputs/apk/release"
  for f in "${APK_ARGS[@]}"; do
    matched=0
    for c in "$CANON_DIR"/*.apk; do
      [ -f "$c" ] || continue
      if cmp -s "$f" "$c"; then matched=1; break; fi
    done
    if [ "$matched" -eq 0 ]; then
      echo "PUBLISH_FAIL $(basename "$f") 不是当前的构建产物（内容不一致）"
      echo "             多半是复制了一份旧的归档包。请从 $CANON_DIR 重新复制，或用 --no-build 显式放行。"
      exit 1
    fi
  done
fi

# ── R8 keep 闸门 ──────────────────────────────────────────────────────────────
# 有一类故障**编译期全绿、debug 包完全正常、只在真机 release 上炸**：
# 库用"运行期拼出来的字符串"反射加载类，R8 看不见这条引用就把类安静地剪掉。
# 本项目已经因此付出两次代价（`net.engio.mbassy` 整类删除 → 默认设置下 SMB 全废；
# `...provider.digest.MD4$Mappings` 删除 → 强制 SMB1 认证必失败），两次都只能靠
# 用户装到真机上才发现。
#
# 把"产物自己证明关键类还在"接在**发布出口**，是唯一能挡住第三次的位置：
#   · 放构建期 —— 构建成功不等于类没被剪（剪掉是 R8 的合法行为，不报警告）；
#   · 放客户端自检 —— 那时坏包已经装到用户机器上了。
# 详细判据见 `tools/check-r8-keeps.sh`（直接解析 APK 的 dex class_defs，而非 grep 字节）。
#
# 位置刻意放在"发布前构建"**之外**：`--no-build` 也要跑。
# 它校验的是"即将发出去的这几个文件"，而不是"刚构建出来的那几个"。
for f in "${APK_ARGS[@]}"; do
  if ! bash "$PROJ_DIR/tools/check-r8-keeps.sh" "$f"; then
    echo "PUBLISH_FAIL R8 keep 自检未通过，已中止发布。"
    exit 1
  fi
done

# ── 版本号 ────────────────────────────────────────────────────────────────────
# ⚠️ 来源是 `gradle.properties`，**不是** build.gradle.kts。
#
# 本项目的版本号写成 `mimi.versionCode` / `mimi.versionName` 两个 gradle property
# （app/build.gradle.kts 用 `providers.gradleProperty(...)` 读它们）。
# 从 build.gradle.kts 里正则抓 `versionCode = N` 会抓到那个**兜底值 "1"**
# （`providers.gradleProperty("mimi.versionCode").orNull ?: "1"`），
# 于是清单永远声明 versionCode=1：所有客户端都认为"没有更新"。
# 发布脚本读错版本号是这条链路上最贵的一类错误（服务端一切正常、客户端也一切正常，
# 就是没人能升上去，且没有任何报错），所以这里显式指向唯一的事实源。
read -r VERSION_CODE VERSION_NAME < <("$PY" - "$PROPS_FILE" <<'PYEOF'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
code = re.search(r'^mimi\.versionCode\s*=\s*(\d+)', s, re.M)
name = re.search(r'^mimi\.versionName\s*=\s*(\S+)', s, re.M)
print((code.group(1) if code else ""), (name.group(1) if name else ""))
PYEOF
)
[ -z "$VERSION_CODE" ] && { echo "PUBLISH_FAIL 无法从 gradle.properties 读取 mimi.versionCode"; exit 1; }
[ -z "$VERSION_NAME" ] && { echo "PUBLISH_FAIL 无法从 gradle.properties 读取 mimi.versionName"; exit 1; }

# ── 闸门②：APK 里**真实**的版本号必须与 gradle.properties 一致 ────────────────
#
# ## 这条闸门是 M5.1 发布前 dry-run 实测抓到的隐患
# 出包时若用 `./gradlew -Pmimi.versionCode=10 -Pmimi.versionName=… assembleRelease`
# 临时覆盖，**只有那一次构建**用新版本号；`gradle.properties`（清单的事实源）仍是旧的。
# 实测输出：
#
#     == 版本: 0.5.0-M5(9)          ← 清单要声明的
#     "url": "mimifiles-**0.5.1-M5.1**-arm64-release.apk"   ← 实际发出去的包
#
# 后果是**全链路绿灯**：上传 201、公网 HEAD 200、客户端"检查更新"也正常返回，
# 就是判定为"已是最新"，**没有人能升上去，而且没有任何报错**。
# 这比"构建失败"难查得多 —— 构建失败至少会在发布前拦下你。
#
# ## 为什么 versionName 也要比
# 只比 versionCode 是不够的：它单调递增，模板里写错也常常"恰好"对得上；
# 而 versionName 是人手写、最容易被忘的那一处，且它在设置页「版本」行直接可见。
#
# ## 为什么用 aapt2 而不是读 output-metadata.json
# `output-metadata.json` 是**构建目录里的元数据**，`--no-build` 重发历史归档包时
# 它与手上的 APK 没有任何关系。要防的正是"手上的包与声明的版本不一致"，
# 所以必须读**这个 APK 自己**的清单。
AAPT2=""
for cand in "${ANDROID_HOME:-}/build-tools"/*/aapt2 "$HOME/.cmfa-toolchain/android-sdk/build-tools"/*/aapt2; do
  [ -x "$cand" ] && AAPT2="$cand" && break
done
if [ -z "$AAPT2" ]; then
  echo "== [警告] 未找到 aapt2，跳过「APK 版本号 × gradle.properties」一致性检查 =="
  echo "           本次无法排除「包是新版、清单声明旧版」这种发布事故，请人工确认。"
else
  for f in "${APK_ARGS[@]}"; do
    BADGING="$("$AAPT2" dump badging "$f" 2>/dev/null | head -n1)"
    APK_CODE="$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
    APK_NAME="$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
    if [ -z "$APK_CODE" ] || [ -z "$APK_NAME" ]; then
      echo "PUBLISH_FAIL 无法从 $(basename "$f") 读出版本号（aapt2 输出异常）"
      exit 1
    fi
    if [ "$APK_CODE" != "$VERSION_CODE" ] || [ "$APK_NAME" != "$VERSION_NAME" ]; then
      echo "PUBLISH_FAIL $(basename "$f") 的版本号与 gradle.properties 不一致"
      echo "             APK            : $APK_NAME($APK_CODE)"
      echo "             gradle.properties: $VERSION_NAME($VERSION_CODE)"
      echo "             多半是出包时用了 -Pmimi.versionCode=… 临时覆盖，而没改 gradle.properties。"
      echo "             照此发布，客户端会认为「已是最新」，谁都不会收到这次更新。"
      exit 1
    fi
    echo "== 版本一致性 ✓ $(basename "$f") = $APK_NAME($APK_CODE) =="
  done
fi

echo "== capability 段: $SEG"
echo "== 版本: $VERSION_NAME($VERSION_CODE)"
echo "== 清单地址: $MANIFEST_URL"

# ── 生成清单 ──────────────────────────────────────────────────────────────────
TMP_MANIFEST="$(mktemp /tmp/mimi-manifest.XXXXXX.json)"
trap 'rm -f "$TMP_MANIFEST"' EXIT

"$PY" - "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "${APK_ARGS[@]}" > "$TMP_MANIFEST" <<'PYEOF'
import datetime, hashlib, json, os, sys
code, name, notes = int(sys.argv[1]), sys.argv[2], sys.argv[3]
files = []
for p in sys.argv[4:]:
    base = os.path.basename(p)
    low = base.lower()
    if "arm64" in low:
        abi = "arm64-v8a"
    elif "v7a" in low or "armv7" in low:
        abi = "armeabi-v7a"
    else:
        # 不猜。ABI 猜错的后果是客户端报"清单里没有适配本机架构的包"，
        # 而排查时会先怀疑客户端的选包逻辑 —— 方向完全错了。
        sys.stderr.write(f"PUBLISH_FAIL 无法从文件名判断 ABI（需要 arm64 / v7a）: {base}\n")
        sys.exit(2)
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    files.append({
        "abi": abi,
        "url": base,                      # 相对路径：换域名/轮换段值只改 manifest 地址一处
        "sha256": h.hexdigest(),
        "size": os.path.getsize(p),
    })
print(json.dumps({
    "versionCode": code,
    "versionName": name,
    "publishedAt": datetime.date.today().isoformat(),
    "notes": notes,
    "files": files,
    # 低于它的客户端必须更新。当前**不启用强制**：本应用还没有过"老版本与新服务端
    # 不兼容"或"老版本有安全缺陷"的情况，而强更会挡住用户（不给取消键），
    # 是个不能随便打开的开关。
    "minSupported": 0,
}, ensure_ascii=False, indent=2))
PYEOF

echo "== 清单预览 =="
cat "$TMP_MANIFEST"

if [ "$DRY_RUN" -eq 1 ]; then
  OUT="$(dirname "${APK_ARGS[0]}")/manifest.json"
  cp "$TMP_MANIFEST" "$OUT"
  echo "== DRY-RUN 已写出本地清单: $OUT =="
  echo "PUBLISH_OK (dry-run)"
  exit 0
fi

# ── 上传 ──────────────────────────────────────────────────────────────────────
dav_put() { # $1=本地文件 $2=远程相对路径
  local code
  code="$(curl -sk --noproxy '*' --connect-timeout 15 --max-time 300 --retry 3 --retry-delay 2 \
       -u "$DAV_USER:$DAV_PASS" -T "$1" "$DAV_BASE$DAV_PREFIX/$2" -o /dev/null -w '%{http_code}')"
  case "$code" in
    201|204) return 0 ;;
    *) echo "PUBLISH_FAIL 上传 $2 HTTP=$code"; return 1 ;;
  esac
}

# MKCOL 已存在会返回 405/301，属正常，一律忽略
dav_mkcol() {
  curl -sk --noproxy '*' --connect-timeout 15 --max-time 60 \
       -u "$DAV_USER:$DAV_PASS" -X MKCOL "$DAV_BASE$DAV_PREFIX/$1/" -o /dev/null || true
}

dav_mkcol "app"
dav_mkcol "app/update"
dav_mkcol "$REL_DIR"

# ① 先传包
for f in "${APK_ARGS[@]}"; do
  dav_put "$f" "$REL_DIR/$(basename "$f")"
  echo "== 已上传 $(basename "$f") =="
done

# ② 最后传 manifest
dav_put "$TMP_MANIFEST" "$REL_DIR/manifest.json"
echo "== 已上传 manifest.json =="

# ── 公网自检 ──────────────────────────────────────────────────────────────────
# 必须逐个 HEAD 200 才算成功。少了这一步，"发布成功"只是"上传请求返回了 201"，
# 而公网侧可能因为路径、权限、缓存等原因根本取不到 —— 那时客户端报"下载失败"，
# 而你会以为已经发好了。
sleep 1
FAILED=0
for p in "manifest.json" $(for f in "${APK_ARGS[@]}"; do basename "$f"; done); do
  http="$(curl -skI --noproxy '*' --connect-timeout 10 --max-time 30 \
          "$PUBLIC_BASE/$REL_DIR/$p" -o /dev/null -w '%{http_code}')"
  echo "== 自检 $p → HTTP $http =="
  [ "$http" = "200" ] || FAILED=1
done
[ "$FAILED" -eq 0 ] || { echo "PUBLISH_FAIL 公网自检未全部通过"; exit 1; }

echo "PUBLISH_OK"
echo "PUBLIC_URL=$MANIFEST_URL"
