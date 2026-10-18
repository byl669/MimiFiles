#!/usr/bin/env python3
"""本机 SMB 服务端（M5.1 端到端验证用）。

## 为什么不直接用 impacket 自带的 CLI
本机 `impacket` 是 pip 装的**库**，`site-packages/impacket/smbserver.py` 里
只有 `SMBSERVER` / `SimpleSMBServer` 两个类，**没有 `__main__` / argparse**
（报错可见：`python -m impacket.smbserver` 静默退出、什么都不做）。
上游那份带 CLI 的 `examples/smbserver.py` 不在 wheel 里，所以这里按
`SimpleSMBServer` 的公开 API 自己包一层，避免再依赖外部脚本。

## 关键参数：`smb2`
- `smb2=false` → **纯 SMB1**（NT LM 0.12）。这是本轮验证的核心场景：
  应用走 jcifs-ng，也就是**被 R8 删掉 MBassador 时必然崩溃**的那条路。
- `smb2=true`  → SMB2/3，验主选路径（smbj）。

## 认证
`addCredential` 要的是 LM/NT 哈希而不是明文，所以这里现算。
"""
import sys

from impacket.smbserver import SimpleSMBServer
from impacket.ntlm import compute_lmhash, compute_nthash


def main() -> int:
    if len(sys.argv) < 7:
        print(
            "usage: m51-smb-server.py <port> <smb2:true|false> "
            "<user> <password> <shareName> <sharePath>",
            file=sys.stderr,
        )
        return 2

    port = int(sys.argv[1])
    smb2 = sys.argv[2].strip().lower() == "true"
    user = sys.argv[3]
    password = sys.argv[4]
    share_name = sys.argv[5]
    share_path = sys.argv[6]

    server = SimpleSMBServer(listenAddress="0.0.0.0", listenPort=port)
    server.setSMB2Support(smb2)
    server.addShare(share_name, share_path, "m51 verification share", "0", "no")
    server.addCredential(user, 0, compute_lmhash(password), compute_nthash(password))

    mode = "SMB2/3" if smb2 else "SMB1 only"
    print(f"[m51-smb] listening :{port} share={share_name} mode={mode} user={user}",
          flush=True)
    server.start()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
