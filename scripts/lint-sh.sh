#!/usr/bin/env bash
# ==============================================================================
# 密密文件管理 · shell 脚本自检
#
# 本项目所有验证脚本都必须能在 **macOS 自带的 bash 3.2.57** 上跑（2007 年的版本，
# 装不了新版，也不该要求开发者先装新版）。3.2 的解析器有两个会静默出错的坑，
# 它们在 `bash -n` 里**查不出来**，只在运行时炸，而且报错信息指向文件末尾：
#
#   坑 1｜双引号命令替换 + 内层转义双引号 + 同行后接 `||`
#         f() { x="$(g "a=\"$1\"") || return 1; ... }      ← unexpected EOF
#         正确写法：让外层引号紧贴 `)` →  x="$(g "a=\"$1\"")" || return 1
#
#   坑 2｜中文紧跟变量名，变量名会被吞掉
#         ok "亮度 $L（RGB $RGB）"   →  bash 把 `（` 的字节当成变量名的一部分
#         正确写法：ok "亮度 ${L}（RGB ${RGB}）"
#
#   坑 3｜同一条 `local` 语句里的多个赋值**互相看不见**
#         local want="$1" timeout="$2" deadline=$(( SECONDS + timeout ))
#         → 展开 deadline 时报 `timeout: unbound variable`；`set -u` 下直接退出整个脚本，
#           而报错行号指向那一行，看起来像"变量拼错了"。bash 4+ 才支持这种依赖。
#         正确写法：一条语句一个赋值。
#
# 用法：scripts/lint-sh.sh [文件...]      # 不传参数则检查 scripts/ 下所有 .sh
# ==============================================================================
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -gt 0 ]]; then
  FILES=("$@")
else
  # `scripts/` 与 `tools/` 都要查：发布链路（publish-update.sh / check-r8-keeps.sh）
  # 才是"出错代价最高"的那批脚本，漏检它们等于把 lint 放在最不需要的地方。
  FILES=()
  while IFS= read -r f; do FILES+=("$f"); done < <(
    find "$PROJECT_DIR/scripts" "$PROJECT_DIR/tools" -name '*.sh' 2>/dev/null | sort
  )
fi

BAD=0
for f in "${FILES[@]}"; do
  [[ -f "$f" ]] || { echo "跳过不存在的文件：$f"; continue; }
  printf '\033[1;36m[lint]\033[0m %s\n' "${f#"$PROJECT_DIR"/}"

  # ── 1. 语法 ──────────────────────────────────────────────────────────────
  if bash -n "$f" 2>/tmp/lintsh.err; then
    printf '   \033[1;32m[ OK ]\033[0m 语法通过\n'
  else
    printf '   \033[1;31m[FAIL]\033[0m 语法错误\n'
    sed 's/^/          /' /tmp/lintsh.err
    BAD=$(( BAD + 1 ))
    continue            # 语法都过不了，后面的检查没有意义
  fi

  # ── 2. 坑 2：中文紧跟变量名 ──────────────────────────────────────────────
  # 只查**未加大括号**的 `$VAR`：`${VAR}（` 是安全的，大括号已把名字定死。
  # 用 python3 做 Unicode 判定；没有 python3 就跳过（不做误报更多的粗略匹配）。
  PY="$(command -v python3 || true)"
  if [[ -n "$PY" ]]; then
    HITS="$("$PY" - "$f" <<'PYEOF'
import re, sys
p = sys.argv[1]
pat = re.compile(r'\$([A-Za-z_][A-Za-z0-9_]*)'
                 r'(?=[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef])')
n = 0
for i, line in enumerate(open(p, encoding='utf-8'), 1):
    s = line.strip()
    if s.startswith('#'):
        continue                       # 注释里怎么写都无所谓
    for m in pat.finditer(line):
        n += 1
        name = m.group(1)
        print(f"   {i}: $%s  ← 变量名后紧跟中文，请写成 ${{{name}}}" % name)
        print(f"        {s[:100]}")
print(f"__COUNT__{n}")
PYEOF
)"
    CNT="$(printf '%s' "$HITS" | grep -o '__COUNT__[0-9]*' | grep -o '[0-9]*')"
    if [[ "${CNT:-0}" -eq 0 ]]; then
      printf '   \033[1;32m[ OK ]\033[0m 无「变量名紧跟中文」\n'
    else
      printf '   \033[1;31m[FAIL]\033[0m 发现 %s 处「变量名紧跟中文」（bash 3.2 会把中文吞进变量名）\n' "$CNT"
      printf '%s\n' "$HITS" | grep -v '__COUNT__'
      BAD=$(( BAD + 1 ))
    fi
  else
    printf '   \033[1;33m[SKIP]\033[0m 无 python3，跳过中文邻接检查\n'
  fi

  # ── 3b. 坑 3：同一条 `local` 语句内部的赋值依赖 ────────────────────────
  # 只在**同一行**内查（跨行语句这里识别不了，会漏；宁可漏也不误报）。
  # 遇到第一个 `;` 就截断：`local a=1; local b=$a` 是两条语句，不该报。
  if [[ -n "$PY" ]]; then
    HITS3="$("$PY" - "$f" <<'PYEOF'
import re, sys
p = sys.argv[1]
start = re.compile(r'^\s*local\s+(.*)$')
assign = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)=')
n = 0
for i, line in enumerate(open(p, encoding='utf-8'), 1):
    m = start.match(line)
    if not m:
        continue
    body = m.group(1)
    if ';' in body:
        body = body.split(';', 1)[0]
    names = [(a.group(1), a.start()) for a in assign.finditer(body)]
    if len(names) < 2:
        continue
    for name, pos in names:
        # 从**自己的赋值之后**开始找：`body[pos:]` 会把 `name=` 本身也算进去，
        # 于是每个名字都会"引用自己"，报出一堆指错变量的诊断。
        rest = body[pos + len(name) + 1:]
        # 两种引用形式都要查：
        #   ① `$name` / `${name}`  —— 普通展开
        #   ② **裸名**：`$(( SECONDS + name ))` 与命令替换里的算术变量是不带 `$` 的。
        #      只查 ① 会漏掉最常见的那个形态（`local a=$1 b=$((a+1))`），
        #      我第一版就是这么漏的。
        # 裸名用词边界（前后不是 [A-Za-z0-9_]），避免把 `foo_x` 里的 x 当引用。
        hit = re.search(r'\$\{' + name + r'\}|\$' + name + r'\b', rest) \
            or re.search(r'(?<![A-Za-z0-9_$])' + name + r'(?![A-Za-z0-9_])', rest)
        if hit:
            n += 1
            print(f"   {i}: `local` 语句里的 {name} 在自己的赋值之后又被引用")
            print(f"        {line.strip()[:100]}")
            break
print(f"__COUNT__{n}")
PYEOF
)"
    CNT3="$(printf '%s' "$HITS3" | grep -o '__COUNT__[0-9]*' | grep -o '[0-9]*')"
    if [[ "${CNT3:-0}" -eq 0 ]]; then
      printf '   \033[1;32m[ OK ]\033[0m 无「local 语句内部赋值依赖」
'
    else
      printf '   \033[1;31m[FAIL]\033[0m 发现 %s 处「local 语句内部赋值依赖」（bash 3.2 会报 unbound variable）\n' "$CNT3"
      printf '%s\n' "$HITS3" | grep -v '__COUNT__'
      BAD=$(( BAD + 1 ))
    fi
  fi

  # ── 4. 坑 1：本文件是否直接用的 /bin/bash（3.2）──────────────────────────
  # 提示性检查：如果 shebang 用的是 env bash，在 macOS 上解析器就是 3.2。
  if head -n1 "$f" | grep -q 'env bash'; then
    printf '   \033[1;32m[ OK ]\033[0m shebang 为 env bash（macOS 上即 bash 3.2，本文件须保持 3.2 兼容）\n'
  fi
done

echo
if [[ $BAD -eq 0 ]]; then
  printf '\033[1;32m[lint] 全部通过\033[0m\n'
else
  printf '\033[1;31m[lint] %d 个文件有问题\033[0m\n' "$BAD"
fi
[[ $BAD -eq 0 ]]
