#!/usr/bin/env python3
"""
密密文件管理 · M2 冒烟用的 WebDAV 服务端（带请求录制）。

## 为什么不用 `wsgidav -c x.yaml` 直接起

`wsgidav` 只打印启动信息，**不记录每个请求**。而 M2 里最需要取证的两件事恰好
都在请求层：

  1. **app 到底发了哪种认证**——Basic 还是 Digest（RFC 7616）。
     只看"连接成功"是不够的：一个只实现了 Basic 的客户端，在只发 Basic 挑战的
     服务端上同样会"成功"。必须看到 `Authorization: Digest ...` 才算数。
  2. **拖动进度条有没有真的走 Range**。
     文件小的时候，"整份读下来再本地 seek"与"发 Range 分片拉"在用户看来一模一样，
     但前者对 4K 视频是致命的。判据只能是 `HTTP_RANGE` 头与 **206** 状态码。

所以这里把 `WsgiDAVApp` 当成普通 WSGI app 包一层 recorder，再用 cheroot 起服务。
**不碰 wsgidav 的 `middleware_stack` 配置**——那个列表里 `RequestResolver` 有
"必须是最后一项"的硬约束，插错位置会静默丢掉认证或目录浏览器，很难查。
包在最外层还有个好处：**被拒的请求（401/404）也一并记下来**，
"试了错密码"这条路径才能被验证。

## 用法

  scripts/m2-dav-server.py --port 8090 --root /tmp/m2lab/dav \
      --user mimi --password mimi123 --auth basic \
      --access-log /tmp/m2lab/dav-8090.jsonl

`--auth` 取 `basic` 或 `digest`：前者只发 Basic 挑战，后者只发 Digest 挑战。
分成两个实例（两个端口）是刻意的——一台服务端只会用一种挑战，
只有两种都存在，才能分别压到 app 的 Basic 与 Digest 两条代码路径。
"""

import argparse
import json
import os
import sys
import threading
import time

from cheroot import wsgi
from wsgidav.wsgidav_app import WsgiDAVApp


class AccessRecorder:
    """把每个请求的关键字段追加成一行 JSON。"""

    def __init__(self, app, log_path):
        self.app = app
        self.log_path = log_path
        self.lock = threading.Lock()
        # 先截断：多次运行之间不混在一起，"这次跑了什么"才能一眼看清
        with open(self.log_path, "w", encoding="utf-8"):
            pass

    def __call__(self, environ, start_response):
        rec = {
            "method": environ.get("REQUEST_METHOD", ""),
            "path": environ.get("PATH_INFO", ""),
            # 这三项是取证重点：Range 决定"能不能拖动"，Authorization 决定"用了哪种认证"
            "range": environ.get("HTTP_RANGE") or "",
            "auth_scheme": (environ.get("HTTP_AUTHORIZATION") or "").split(" ")[0],
            "depth": environ.get("HTTP_DEPTH") or "",
            "destination": environ.get("HTTP_DESTINATION") or "",
            "content_length": environ.get("CONTENT_LENGTH") or "",
        }

        def recording_start_response(status, headers, exc_info=None):
            rec["status"] = int(status.split(" ", 1)[0])
            rec["time"] = round(time.time(), 3)
            line = json.dumps(rec, ensure_ascii=False)
            with self.lock:
                with open(self.log_path, "a", encoding="utf-8") as handle:
                    handle.write(line + "\n")
            return start_response(status, headers, exc_info)

        return self.app(environ, recording_start_response)


def build_config(args):
    return {
        "host": "0.0.0.0",
        "port": args.port,
        # 空目录也允许写入，冒烟要建目录
        "provider_mapping": {"/": args.root},
        "http_authenticator": {
            "domain_controller": None,
            "accept_basic": args.auth == "basic",
            "accept_digest": args.auth == "digest",
            # 这一项决定服务端发哪种挑战，是区分两条代码路径的关键开关
            "default_to_digest": args.auth == "digest",
        },
        "simple_dc": {
            "user_mapping": {"*": {args.user: {"password": args.password}}}
        },
        "dir_browser": {"enable": False},
        # 0 = 安静；出了问题看日志时再调大
        "verbose": args.verbose,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--root", required=True)
    parser.add_argument("--user", default="mimi")
    parser.add_argument("--password", default="mimi123")
    parser.add_argument("--auth", choices=["basic", "digest"], default="basic")
    parser.add_argument("--access-log", required=True)
    parser.add_argument("--verbose", type=int, default=1)
    args = parser.parse_args()

    os.makedirs(args.root, exist_ok=True)

    app = WsgiDAVApp(build_config(args))
    wrapped = AccessRecorder(app, args.access_log)

    server = wsgi.Server(("0.0.0.0", args.port), wrapped)
    # 打印这一行当作"已就绪"信号，脚本据此判断起没起来（比 sleep 猜要可靠）
    print(f"M2DAV-READY port={args.port} auth={args.auth} log={args.access_log}",
          flush=True)
    try:
        server.start()
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
