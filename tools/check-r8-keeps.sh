#!/usr/bin/env bash
# ==========================================================================
# R8 keep 回归闸门 —— 从**已签名的 release APK** 里断言关键反射依赖类真的存在
#
# ## 这个脚本存在的唯一理由
# 「库靠运行期拼出来的字符串去加载类」这个模式，R8 的可达性分析**看不见**，
# 于是类会被安静地剪掉。这类故障有三个共同特征：
#   · 编译期全绿（R8 删除一个"没人引用"的类是正确行为，不报警告）；
#   · debug 包完全正常（不混淆、不裁剪）；
#   · 运行期才炸，而且炸出来的是一句 Java 内部文本。
#
# 本项目已经为它付出两次代价：
#   #1  `net.engio.mbassy`（smbj 与 jcifs-ng 的共同传递依赖）被整类删除
#        → 客户看到 `r9.c: The provided handler invocation did not specify
#           the necessary constructor h(SubscriptionContext)`，**默认设置下 SMB 全废**；
#   #2  `org.bouncycastle.jcajce.provider.digest.MD4$Mappings` 被删除
#        → 强制 SMB1 时报 `NoSuchAlgorithmException: no such algorithm: MD4 for provider BC`。
#
# 两次都是"配了 keep 规则、但漏了某一条"，两次都只能靠真机发现。
# 本脚本把这件事从"靠人记得配规则"变成"构建产物自己证明"。
#
# ## 为什么查 dex 的 class_defs，而不是 grep 文件字节
# grep 只能证明"某个字符串出现过"。dex 里字符串表同时装着**引用**与**定义**，
# 一个仅被引用（却不存在）的类名也会以字符串形式留下 —— 那正是我们要抓的情形。
# 所以这里解析 dex 头、走 `class_defs → type_ids → string_ids`，拿到的才是
# **真正被定义在这个包里**的类集合。
#
# ## 怎么接进流程
# `tools/publish-update.sh` 在发布前对每个待发布 APK 跑一遍，不过就 `PUBLISH_FAIL`。
# 也可以手动跑：`bash tools/check-r8-keeps.sh [apk路径]`
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR="$(pwd)"

PY="${PY:-}"
if [ -z "$PY" ]; then
  for cand in \
    "$HOME/.workbuddy/binaries/python/envs/default/bin/python" \
    "$HOME/.workbuddy/binaries/python/versions/3.13.12/bin/python3" \
    python3
  do
    if command -v "$cand" >/dev/null 2>&1 || [ -x "$cand" ]; then PY="$cand"; break; fi
  done
fi
[ -z "$PY" ] && PY="/usr/bin/python3"

APK="${1:-app/build/outputs/apk/release/app-arm64-v8a-release.apk}"
if [ ! -f "$APK" ]; then
  echo "R8KEEPS_FAIL 找不到 APK：$APK"
  echo "             用法：bash tools/check-r8-keeps.sh [apk路径]"
  echo "             （默认路径需要先跑 ./gradlew :app:assembleRelease）"
  exit 1
fi

MAPPING="${MAPPING:-app/build/outputs/mapping/release/mapping.txt}"

"$PY" - "$APK" "$MAPPING" <<'PYEOF'
import re
import struct
import sys
import zipfile

apk_path = sys.argv[1]
mapping_path = sys.argv[2] if len(sys.argv) > 2 else ""

# ── 必存清单 ────────────────────────────────────────────────────────────────
# ## 收录标准：**只有"靠运行期字符串按名字加载"的类才进来**
# 这一类必须保持**原始全限定名**，否则 R8 一改名就找不回来。
#   · `Class.forName(<拼出来的字符串>)`
#   · `ServiceLoader.load(X)` / `X.class.getName()` 后再反射实例化
#   · 注册表里以类名字符串登记实现（BC 的 `addAlgorithm(key, className)` 就是）
#
# ## 刻意**不**收录"直接引用"的类（例如 `org.bouncycastle.crypto.digests.MD4Digest`）
# 它们被 `new` / 字段声明直接引用，R8 合法改名（实测被改成 `kc.f`）甚至内联，
# 功能不受影响。把它们写进判据会制造**假红**，而且会诱使人去加一条毫无必要、
# 白涨体积的 `-keep`。判断标准不是"这条链上还有谁"，而是"**谁的名字进了字符串**"。
#
# ## ★ 标出本项目已经真实踩过的两个坑
REQUIRED = [
    ("MBassador 事件总线（smbj + jcifs-ng 共同依赖）", "net/engio/mbassy/bus/MBassador"),
    ("  ↳ 反射构造的处理器链", "net/engio/mbassy/listener/MessageHandler"),
    ("  ↳ 用户看到的异常类型 ★坑#1", "net/engio/mbassy/bus/error/MessageBusException"),
    ("jcifs-ng SMB1 客户端（ServiceLoader 加载）", "jcifs/smb/SmbFile"),
    ("  ↳ NTLM 加密入口", "jcifs/util/Crypto"),
    ("smbj SMB2/3 客户端（反射构造传输层）", "com/hierynomus/smbj/SMBClient"),
    ("  ↳ smbj 自带 MD4（按名字取摘要）", "com/hierynomus/security/jce/messagedigest/MD4"),
    ("BC MD4 反射加载器 ★坑#2", "org/bouncycastle/jcajce/provider/digest/MD4$Mappings"),
    ("  ↳ BC MD4 外壳（getName() 进字符串）", "org/bouncycastle/jcajce/provider/digest/MD4"),
    ("  ↳ BC MD4 实现（字符串注册）", "org/bouncycastle/jcajce/provider/digest/MD4$Digest"),
    ("  ↳ BC HMAC-MD4（字符串注册）", "org/bouncycastle/jcajce/provider/digest/MD4$HashMac"),
    ("  ↳ BC MD4 KeyGenerator（字符串注册）", "org/bouncycastle/jcajce/provider/digest/MD4$KeyGenerator"),
]


def _uleb128(buf, off):
    """dex 的 LEB128（无符号）。"""
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def _u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


def dex_defined_classes(buf):
    """返回这个 dex 里 class_defs 定义的类描述符集合（形如 `Lcom/foo/Bar;`）。"""
    if len(buf) < 0x70 or buf[:4] != b"dex\n":
        return None

    string_ids_size = _u32(buf, 0x38)
    string_ids_off = _u32(buf, 0x3C)
    # ⚠️ 0x40 是 type_ids_**size**，偏移在 0x44。写成 0x40 会静默读出垃圾
    # （第一次实现就踩了：把 7322 当偏移，结果"全包只定义了 1 个类"，
    # 12 条判据全部误报为缺失 —— 判据本身的错误比漏检更危险）。
    type_ids_size = _u32(buf, 0x40)
    type_ids_off = _u32(buf, 0x44)
    class_defs_size = _u32(buf, 0x60)
    class_defs_off = _u32(buf, 0x64)

    def string_at(idx):
        if idx < 0 or idx >= string_ids_size:
            return ""
        data_off = _u32(buf, string_ids_off + idx * 4)
        if data_off <= 0 or data_off >= len(buf):
            return ""
        _, p = _uleb128(buf, data_off)
        # MUTF-8：长度前缀是**UTF-16 码元数**，不是字节数，所以直接读到 NUL 为止。
        end = buf.find(0, p)
        if end < 0:
            return ""
        return buf[p:end].decode("utf-8", "replace")

    def type_desc(idx):
        return string_at(_u32(buf, type_ids_off + idx * 4))

    out = set()
    for i in range(class_defs_size):
        # class_def_item 固定 32 字节，首字段是 class_idx
        out.add(type_desc(_u32(buf, class_defs_off + i * 32)))

    # 自检：解析出来的类数太少说明偏移读错了。宁可"闸门自己报错"，
    # 也不能让它拿着一份空集合把 12 条判据全部误报成缺失（那样的红是假红）。
    if len(out) < 64:
        return None
    return out


# ── 扫描 APK 里所有 classes*.dex ────────────────────────────────────────────
defined = set()
dex_count = 0
try:
    with zipfile.ZipFile(apk_path) as z:
        dex_names = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        if not dex_names:
            print("R8KEEPS_FAIL APK 里没有 classes*.dex：%s" % apk_path)
            sys.exit(1)
        for name in dex_names:
            got = dex_defined_classes(z.read(name))
            if got is None:
                print("R8KEEPS_FAIL 无法解析 %s（magic 异常，或解析出的类数不合理）" % name)
                print("             判据本身不可信时不能给出结论 —— 先修解析，别把假红当成真问题。")
                sys.exit(1)
            defined |= got
            dex_count += 1
except zipfile.BadZipFile:
    print("R8KEEPS_FAIL 不是合法的 zip/APK：%s" % apk_path)
    sys.exit(1)

print("== R8 keep 闸门 ==")
print("   APK : %s" % apk_path)
print("   dex : %d 个，已定义类 %d 个" % (dex_count, len(defined)))
print()

missing = []
width = max(len(label) for label, _ in REQUIRED)
for label, slash in REQUIRED:
    descriptor = "L%s;" % slash
    ok = descriptor in defined
    print("   %s %-*s %s" % ("\u2713" if ok else "\u2717", width, label, slash))
    if not ok:
        missing.append(slash)

# ── 第二层：mapping.txt 交叉印证（**仅供参考，不参与判定**）─────────────────
# ⚠️ 实测结论：mapping.txt **不能**当判据。新包里 `MD4`、`MD4$Digest`、
# `MD4$HashMac` 三个类在 dex 里明确按原名定义着，却在 mapping.txt 里**根本没有行**
# （而同一批 `-keep` 的 `MD4$Mappings` / `MD4$KeyGenerator` 又有行）。
# 拿它当闸门会同时产生假红（明明在包内却报缺失）和假绿的余地，
# 所以这里只打印、不参与退出码 —— 判定只认 dex 的 class_defs。
mapping_note = "（mapping.txt 不存在，已跳过）"
if mapping_path:
    try:
        with open(mapping_path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
        renamed = []
        for _, slash in REQUIRED:
            cls = slash.replace("/", ".")
            m = re.search(r"(?m)^\s*%s\s*->\s*([^:\s]+):" % re.escape(cls), text)
            if m and m.group(1) != cls:
                renamed.append("%s -> %s" % (cls, m.group(1)))
        mapping_note = "无改名记录" if not renamed else "有改名记录 %d 个：%s" % (len(renamed), ", ".join(renamed))
    except OSError as exc:
        mapping_note = "（读取失败：%s）" % exc
print()
print("   mapping.txt（参考）：%s" % mapping_note)

print()
if missing:
    print("R8KEEPS_FAIL 有 %d 个关键类不在包内：" % len(missing))
    for m in missing:
        print("               - %s" % m)
    print("             这类类由**运行期字符串**加载，R8 看不见引用就会剪掉；")
    print("             请到 app/proguard-rules.pro 补对应的 -keep，然后重新打包。")
    sys.exit(1)

print("R8KEEPS_OK %d/%d 关键类全部在包内" % (len(REQUIRED), len(REQUIRED)))
PYEOF
