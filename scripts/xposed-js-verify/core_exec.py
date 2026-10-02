#!/usr/bin/env python3
"""经 vFlow Core 的 root 通道执行一条 shell 命令。

## 为什么需要它

第 9 项要构造真实的 `channel_down`：禁用 App 的 `HookChannelService`
（hook 层的 `bindService` 端点）。而 **shell（uid 2000）改不动组件状态** ——
`pm disable` 会抛 `SecurityException: Shell cannot change component state`。
vFlow Core 是 root 进程（本机实测 `context=u:r:ksu:s0`），能改。

## ⚠️ 协议（照 `BIND_ADDRESS:PORT_MASTER` 的 TCP 帧）

一行 JSON 进去、一行 JSON 出来；`target=system` + `method=exec` + `asRoot=true`。
`adb forward tcp:19999 tcp:19999` 之后连 `127.0.0.1:19999`。
"""
import json
import socket
import sys

# ⚠️ Windows 控制台默认 GBK，而本脚本输出中文与 ✅/❌ ⇒ 不显式改编码会
# `UnicodeEncodeError` 崩在**最后一行打印**上（前面全对，看起来像逻辑错）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass



HOST, PORT = "127.0.0.1", 19999


def core_exec(cmd, timeout=120):
    req = json.dumps({"target": "system", "method": "exec",
                      "params": {"cmd": cmd, "asRoot": True}})
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    s.sendall((req + "\n").encode())
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
    s.close()
    return json.loads(buf.decode())


def main():
    if len(sys.argv) < 2:
        print("用法：core_exec.py '<shell 命令>'", file=sys.stderr)
        return 2
    r = core_exec(sys.argv[1])
    out = (r.get("output") or "").strip()
    if out:
        print(out)
    if not r.get("success"):
        print("执行失败（exitCode=%s）" % r.get("exitCode"), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
