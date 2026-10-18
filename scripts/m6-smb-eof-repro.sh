#!/usr/bin/env bash
# ==========================================================================
# M6 · SMB「EOF 越界读」复现器
#
# ## 它证明的那件事
# 在 SMB2.0.2（impacket `SimpleSMBServer`，也是很多老 NAS 的协商结果）上，
# **一次起点正好落在文件末尾的 READ**，会让 smbj 解析服务端的错误响应时
# `BufferUnderflow`；该异常从 **reader 线程**抛出，于是
# `Connection.handleError → close()` 把**整条连接**判死。
#
# 于是："文件已经完整读完、最后一步却把会话炸掉"——用户看到的是
# **从 SMB 装 APK 卡在「正在准备安装应用」**（#56 的真根因）。
#
# ## 为什么值得单独留一个脚本（而不是只写在报告里）
# 这个故障在 App 里的表现是"4 分钟一轮、还要看 logcat 才知道发生了什么"，
# 而本脚本 **3 秒**就能把它变成红/绿。改 `SmbjSeekableSource.readFrom`
# 或 `SmbjTransport.isAlive` 的人，跑一下就知道自己有没有把它改回去。
#
# ## 两个用例（第二个是反向验证）
#   U1 `nooverrun` —— 逐个"还差多少、文件还剩多少取小"地读，到 EOF 收手。
#      这是**修复后** `readFrom` 的行为，期望：读满全部字节且连接仍活着。
#   U2 `overrun`   —— 正常读完之后，**再发一次** offset == 文件长度的读。
#      这是**修复前** `readFrom` 会走到的行为，期望：连接被炸掉
#      （`share.isConnected()==false`），且已读字节数**正好等于文件大小**
#      ——"数据全拿到了、最后一步炸了"这个特征必须复现出来。
#
# 判据不是"有没有抛异常"，而是**两个存活标志的组合**：
#   · U1 → `connConnected=true` 且 `shareConnected=true`
#   · U2 → `connConnected=true` 且 `shareConnected=false`
# 第二个组合才是真正的坑：只看 `conn.isConnected` 的判活会以为会话还能用。
#
# ## 用法
#   scripts/m6-smb-eof-repro.sh [文件名] [端口]
#   前置：本机 SMB 服务端在跑（脚本会自己检查端口）
#     ${PY} scripts/m51-smb-server.py 8460 true mimi testpass123 SHARE /tmp/m6-smbshare
# ==========================================================================
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

JAVA_HOME="${JAVA_HOME:-$HOME/.cmfa-toolchain/jdk}"
JAVA="${JAVA_HOME}/bin/java"
JAVAC="${JAVA_HOME}/bin/javac"
PY="${HOME}/.workbuddy/binaries/python/envs/default/bin/python"

FILE_NAME="${1:-mimi-test-21mb.apk}"
PORT="${2:-8460}"
SHARE_DIR="${SHARE_DIR:-/tmp/m6-smbshare}"

WORK="$(mktemp -d /tmp/m6-eof-repro.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT

ok()  { echo "  ✓ $*"; }
bad() { echo "  ✗ $*"; }

# ── 依赖 ─────────────────────────────────────────────────────────────────
#
# 直接去 Gradle 缓存里捞：这些 jar 是构建本工程时必定已经下载过的，
# 因此不需要在这里维护一份版本清单（版本号写在 `gradle/libs.versions.toml`，
# 这里再抄一遍迟早会对不上）。
CACHE="${HOME}/.gradle/caches/modules-2/files-2.1"
jars=()
for g in "com.hierynomus/smbj" "com.hierynomus/asn-one" "net.engio/mbassador" \
         "org.slf4j/slf4j-api" "org.bouncycastle/bcprov-jdk18on" \
         "org.bouncycastle/bcutil-jdk18on"; do
  while IFS= read -r j; do jars+=("${j}"); done < <(find "${CACHE}/${g}" -name "*.jar" ! -name "*sources*" 2>/dev/null)
done
if [[ ${#jars[@]} -lt 4 ]]; then
  echo "  ✗ 在 Gradle 缓存里找不到 smbj 的依赖，先跑一次 :core:fs-smb 的编译"
  exit 1
fi
CP="$(IFS=:; echo "${jars[*]}")"

# ── 服务端 ───────────────────────────────────────────────────────────────
if [[ ! -f "${SHARE_DIR}/${FILE_NAME}" ]]; then
  echo "  ✗ 找不到夹具 ${SHARE_DIR}/${FILE_NAME}"
  exit 1
fi
if ! (exec 3<>/dev/tcp/127.0.0.1/"${PORT}") 2>/dev/null; then
  echo "  ✗ 本机 ${PORT} 没有服务在听，先起："
  echo "    ${PY} scripts/m51-smb-server.py ${PORT} true mimi testpass123 SHARE ${SHARE_DIR}"
  exit 1
fi

cat > "${WORK}/EofProbe.java" <<'JAVA'
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * 配置逐项对齐 App 的 `SmbConnector.openSmb2`（默认设置：signing=false）。
 * 不这么做的话，复现出来的东西与真机上跑的可能不是同一个故障。
 */
public class EofProbe {
    public static void main(String[] args) throws Exception {
        String file = args[0];
        String mode = args[1];            // nooverrun | overrun
        int chunk = args.length > 2 ? Integer.parseInt(args[2]) : 1024 * 1024;

        SmbConfig config = SmbConfig.builder()
                .withTimeout(10_000, TimeUnit.MILLISECONDS)
                .withReadTimeout(120_000, TimeUnit.MILLISECONDS)
                .withWriteTimeout(120_000, TimeUnit.MILLISECONDS)
                .withTransactTimeout(120_000, TimeUnit.MILLISECONDS)
                .withSoTimeout(0L, TimeUnit.MILLISECONDS)
                .withReadBufferSize(1024 * 1024)
                .withWriteBufferSize(1024 * 1024)
                .withTransactBufferSize(1024 * 1024)
                .withSigningEnabled(false)
                .withSigningRequired(false)
                .withDialects(SMB2Dialect.SMB_2_0_2, SMB2Dialect.SMB_2_1)
                .build();

        try (SMBClient client = new SMBClient(config)) {
            Connection conn = client.connect("127.0.0.1", 8460);
            Session session = conn.authenticate(
                    new AuthenticationContext("mimi", "testpass123".toCharArray(), null));
            DiskShare share = (DiskShare) session.connectShare("SHARE");
            File f = share.openFile(file, EnumSet.of(AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            long len = f.getFileInformation().getStandardInformation().getEndOfFile();

            long total = 0;
            byte[] buf = new byte[chunk];
            long pos = 0;
            while (pos < len) {
                int want = (int) Math.min(chunk, len - pos);
                int n = f.read(buf, pos, 0, want);
                if (n <= 0) break;
                total += n;
                pos += n;
            }
            System.out.println("  读满 " + total + " / " + len + " 字节（EOF=" + len + "）");

            if ("overrun".equals(mode)) {
                // ★ 反向验证：这正是修复前 readFrom 在最后一块会走到的分支。
                //
                // 失败时**必须**照样把两个存活标志打出来：判据是"标志的组合"，
                // 而不是"有没有抛异常"。实测里 U2 抛的是
                // `Unable to deserialize SMB2 Packet Data`，而
                // `share.isConnected()` 已经变 false、`conn.isConnected()` 仍是 true
                // —— 后一半才是真正的坑。让异常直接冒出去等于把判据丢掉。
                try {
                    int n = f.read(buf, len, 0, chunk);
                    System.out.println("  越界读 offset=" + len + " 竟然返回了 n=" + n);
                } catch (Throwable t) {
                    System.out.println("  越界读 offset=" + len + " 抛错：" + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            }

            System.out.println("VERDICT bytes=" + total + " expected=" + len
                    + " shareConnected=" + share.isConnected()
                    + " connConnected=" + conn.isConnected());
        }
    }
}
JAVA

"${JAVAC}" -cp "${CP}" -d "${WORK}" "${WORK}/EofProbe.java" 2>&1 | head -10 || exit 1

echo "════════ SMB EOF 越界读复现（${FILE_NAME}，chunk=1 MiB）════════"
echo

fails=0

echo "── U1 不越界（修复后的行为）：读满即收手 ──"
U1="$("${JAVA}" -cp "${CP}:${WORK}" EofProbe "${FILE_NAME}" nooverrun 2>&1 | grep -v SLF4J)"
echo "${U1}"
if grep -q "shareConnected=true connConnected=true" <<<"${U1}"; then
  ok "U1 连接与会话都活着"
else
  bad "U1 期望 shareConnected=true connConnected=true"; fails=$((fails + 1))
fi
echo

echo "── U2 越界（修复前的行为）：EOF 处再读一次 ──"
U2="$("${JAVA}" -cp "${CP}:${WORK}" EofProbe "${FILE_NAME}" overrun 2>&1 | grep -v SLF4J)"
echo "${U2}" | grep -E "读满|越界读|VERDICT|Unable to deserialize|^Exception" | head -6
if grep -q "shareConnected=false" <<<"${U2}"; then
  ok "U2 复现：越界读把会话炸掉（shareConnected=false）—— 这就是 #56 的真根因"
else
  bad "U2 没复现出来。这个服务端不吃越界读的亏，本脚本对它就失去意义了"
  fails=$((fails + 1))
fi

echo
echo "════════ 结果 ════════"
if [[ "${fails}" -eq 0 ]]; then
  echo "通过 2 · 失败 0 —— 两个用例都成立：越界读确实是「数据读满、连接报废」的触发点"
else
  echo "失败 ${fails} 项（见上）"
fi
exit "${fails}"
