#!/usr/bin/env python3
"""一致性核对：同一段脚本在 `vflow.system.js` 与 `vflow.xposed.js` 里都可用。

需求原文：「确认模块与 `vflow.system.js` 在编辑器里**行为一致**（同款脚本在两边都能编、
能跑），差别只在执行环境（一个有 `vflow.*`、一个没有）」。

## ⚠️ 脚本刻意只用两边都有的东西

`console.log` 与纯 JS 计算。**不用** `vflow.*` —— 那在 `vflow.system.js` 里有、
在 `vflow.xposed.js` 里没有，而**那正是要验的差别本身**（第 7 项）。

判据：两边产物**逐字相同**，且两边都打出了 `VFLOW_CONSIST 2.0`。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import t4_driver as d  # noqa: E402

# ⚠️ Windows 控制台默认 GBK，而本脚本输出中文与 ✅/❌ ⇒ 不显式改编码会
# `UnicodeEncodeError` 崩在**最后一行打印**上（前面全对，看起来像逻辑错）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass



SCRIPT = 'console.log("VFLOW_CONSIST", 1+1);\n({sum: 1+1})'

MODULES = [
    ("vflow.xposed.js", "xposed", "t4_cons_xposed.txt"),
    ("vflow.system.js", "system", "t4_cons_system.txt"),
]


def main():
    tok = d.token()
    results = {}

    for mod, tag, out in MODULES:
        body = {
            "name": "T4 consistency %s" % tag, "isEnabled": True,
            "description": "T4",
            "triggers": [{"id": "manual_trigger",
                          "moduleId": "vflow.trigger.manual", "parameters": {}}],
            "steps": [
                {"id": "s1", "moduleId": mod,
                 "parameters": {"script": SCRIPT, "timeout_ms": 5000}},
                {"id": "s2", "moduleId": "vflow.data.file_operation",
                 "parameters": {"mode": "local", "operation": "write",
                                "file_path": "/sdcard/vFlow/exports/" + out,
                                "content": "{{s1.outputs}}", "overwrite": True}},
            ],
        }
        r = d.api("POST", "/api/v1/workflows", body, tok)
        wfid = r.get("data", {}).get("id")
        if not wfid:
            print("  ❌ %s 建工作流失败：%s" % (mod, r))
            results[mod] = None
            continue

        d.core("rm -f /sdcard/vFlow/exports/" + out)
        d.adb("logcat", "-c")
        d.trigger(wfid)
        time.sleep(8)

        logs = d.adb("logcat", "-d").stdout or ""
        marked = any("VFLOW_CONSIST 2" in l for l in logs.splitlines())
        product = d.read_file("/sdcard/vFlow/exports/" + out)

        print("  %-18s 模块脚本执行 marker=%s  产物=%r"
              % (mod, "有" if marked else "**无**", product))
        results[mod] = (marked, product)

        d.delete_workflow(tok, wfid)
        d.core("rm -f /sdcard/vFlow/exports/" + out)

    a = results.get("vflow.xposed.js")
    b = results.get("vflow.system.js")
    if not a or not b:
        print("\n  ⚠️ 两边没有都跑通 ⇒ 判【未验证】")
        return 1
    if not (a[0] and b[0]):
        print("\n  ❌ 有一边没打出 console marker")
        return 1
    if a[1] != b[1]:
        print("\n  ❌ 产物不一致：\n     xposed = %r\n     system = %r" % (a[1], b[1]))
        return 1
    print("\n  ✅ 一致：同款脚本两边都能编、能跑，产物逐字相同（%r）" % a[1])
    print("     差别只在执行环境：system 侧有 vflow.* 模块树，xposed 侧没有（见第 7 项）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
