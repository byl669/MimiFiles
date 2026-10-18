#!/usr/bin/env python3
"""
限速的静态文件服务 —— 更新冒烟专用的本机镜像。

## 为什么不能用 `python3 -m http.server`
更新链路里有一段**只在下载过程中存在**的界面：进度条与"正在下载 N%"。
从 localhost 下一个 19 MB 的 debug 包只要几百毫秒，而 `uiautomator dump`
一次就要 1 秒上下 —— 也就是说，用不 限速 的镜像，那段界面**永远来不及被看到**，
断言只能写成"下载成功"，而"进度有没有在动"完全没被验证。

这不是假设：这一段真的出过一个 bug（下载开始后进度永远停在 0%，
因为就地刷新的判据写成了"上一个状态也得是下载中"，那个条件在第一次转换时恒不成立）。
限速之后，"进度在涨"变成一个能被稳定观测的事实。

## 用法
    python3 scripts/m5-mirror.py --port 9999 --dir build/m5-mirror --throttle 2500

`--throttle` 单位是 **KB/s**（默认 2500 即约 2.5 MB/s）。0 表示不限速。
"""
import argparse
import functools
import http.server
import os
import time

ARGS = None


class ThrottledHandler(http.server.SimpleHTTPRequestHandler):
    def copyfile(self, source, outputfile):
        """
        按字节数 sleep，把吞吐压到目标值。

        按**实际写出的字节**计时而不是"每块固定 sleep"：后者在块大小变化时
        限速值会漂，而我们要的是一个稳定的、可预期的下载时长。
        """
        throttle_bps = (ARGS.throttle or 0) * 1024
        if throttle_bps <= 0:
            return super().copyfile(source, outputfile)

        chunk = 64 * 1024
        while True:
            data = source.read(chunk)
            if not data:
                break
            outputfile.write(data)
            time.sleep(len(data) / throttle_bps)

    def log_message(self, fmt, *args):
        # 默认实现每个请求往 stderr 打一行。冒烟脚本会往这个进度里塞几百行，
        # 把它压掉，好让真正的失败信息留在可见的位置。
        if ARGS and ARGS.verbose:
            super().log_message(fmt, *args)


def main():
    global ARGS
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--dir", required=True)
    p.add_argument("--throttle", type=int, default=2500, help="KB/s，0 表示不限速")
    p.add_argument("--verbose", action="store_true")
    ARGS = p.parse_args()

    handler = functools.partial(ThrottledHandler, directory=os.path.abspath(ARGS.dir))
    with http.server.ThreadingHTTPServer(("127.0.0.1", ARGS.port), handler) as httpd:
        httpd.serve_forever()


if __name__ == "__main__":
    main()
