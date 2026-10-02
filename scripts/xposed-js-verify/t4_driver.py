#!/usr/bin/env python3
"""T4 用例驱动：建工作流 → 触发 → 采集两侧日志与产物。

由 `scripts/xposed-js-verify.sh run` 调用，不直接给用户用。

## 为什么用 Python 而不是纯 bash

建工作流要把 JS 源码**原样**塞进 JSON 字符串（含换行、引号、花括号），
bash 的引号地狱在这里极容易写错，而**写错的后果是静默的**
（脚本内容被 bash 吃掉一部分 ⇒ 报一个与真实原因无关的语法错）。

## ⚠️ 用例脚本必须用【表达式】形式

Rhino 1.9.0 **不接受顶层 `return`**（`msg.bad.return` =「返回的值无效」），
那是**解析期**错误。返回值取「最后一行表达式」。
"""
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ⚠️ Windows 控制台默认 GBK，而本脚本输出中文与 ✅/❌ ⇒ 不显式改编码会
# `UnicodeEncodeError` 崩在**最后一行打印**上（前面全对，看起来像逻辑错）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass



CORE_HOST = "127.0.0.1"
CORE_PORT = 19999
API = "http://127.0.0.1:8080"
TAG = "T4"

# ⚠️⚠️ adb 可能对**同一台设备**开出多条 transport（USB + 无线 TLS 各一条），
# 此时**不带 `-s`** 的 adb 命令会以 `error: more than one device/emulator` 失败，
# 而且失败是**静默**的（logcat 返回空）。由 shell 侧导出，见脚本头部说明。
SERIAL = os.environ.get("T4_ADB_SERIAL") or None


def adb(*args, **kw):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, encoding="utf-8",
                          errors="replace", **kw)


# ── Core（root 执行）───────────────────────────────────────────

def core(cmd, timeout=120):
    """经 vFlow Core 以 root 执行一条 shell 命令。"""
    req = json.dumps({"target": "system", "method": "exec",
                      "params": {"cmd": cmd, "asRoot": True}})
    s = socket.create_connection((CORE_HOST, CORE_PORT), timeout=timeout)
    s.sendall((req + "\n").encode())
    buf = b""
    while not buf.endswith(b"\n"):
        c = s.recv(65536)
        if not c:
            break
        buf += c
    s.close()
    return json.loads(buf.decode())


def read_file(path):
    """读设备上的文件（走 root）。"""
    r = core("base64 %s | tr -d '\\n'" % path)
    if not r.get("success"):
        return None
    import base64
    try:
        return base64.b64decode(r.get("output", "")).decode("utf-8", "replace")
    except Exception:
        return None


# ── HTTP API ───────────────────────────────────────────────────

def token():
    req = urllib.request.Request(
        API + "/api/v1/auth/token",
        data=json.dumps({"deviceId": TAG}).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=20))["data"]["token"]


def api(method, path, body=None, tok=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        API + path, data=data, method=method,
        headers={"Authorization": "Bearer " + tok,
                 "Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req, timeout=40))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "message": e.read().decode()[:400]}


def make_workflow(tok, name, script, timeout_ms, out_file=None, inputs=None,
                  probes=None):
    s1 = {"id": "s1", "moduleId": "vflow.xposed.js",
          "parameters": {"script": script, "timeout_ms": timeout_ms,
                         "__error_policy": "STOP"}}
    if inputs is not None:
        s1["parameters"]["inputs"] = inputs
    steps = [s1]
    if out_file:
        steps.append({"id": "s2", "moduleId": "vflow.data.file_operation",
                      "parameters": {"mode": "local", "operation": "write",
                                     "file_path": out_file,
                                     "content": "{{s1.outputs}}",
                                     "overwrite": True,
                                     "__error_policy": "STOP"}})
    # 额外的「探针」落盘步骤：把任意魔法变量表达式（如 {{s1.outputs.a.b}}）
    # 写成文件，用于**硬断言嵌套结构**。
    # ⚠️ 为什么需要它：模块的 outputs 是 VDictionary，而 VDictionary.asString()
    #    把**每个值都包了引号**（`{"a": "{"b": "1, 2"}"}`）⇒ 光看整串
    #    **区分不出**「嵌套结构对了」与「值被字符串化成 [object Object]」。
    #    但 `{{s1.outputs.a.b}}` 若取到 2，就证明 `a` 确实是**可按键导航的字典**。
    for i, (expr, path) in enumerate(probes or []):
        steps.append({"id": "p%d" % i,
                      "moduleId": "vflow.data.file_operation",
                      "parameters": {"mode": "local", "operation": "write",
                                     "file_path": path, "content": expr,
                                     "overwrite": True,
                                     "__error_policy": "STOP"}})
    body = {"name": name, "isEnabled": True, "description": TAG,
            "triggers": [{"id": "manual_trigger",
                          "moduleId": "vflow.trigger.manual", "parameters": {}}],
            "steps": steps}
    return api("POST", "/api/v1/workflows", body, tok).get("data", {}).get("id")


def delete_workflow(tok, wfid):
    if wfid:
        api("DELETE", "/api/v1/workflows/" + wfid, None, tok)


def trigger(wfid):
    adb("shell", "am", "start", "-a",
        "com.chaomixian.vflow.EXECUTE_WORKFLOW_SHORTCUT",
        "--es", "workflow_id", wfid)


def logcat():
    # ⚠️ 必须显式 utf-8：Windows 上 subprocess 默认按本地代码页（GBK）解码，
    # 而设备发的是 UTF-8 ⇒ 中文全部变 mojibake，判定用的 grep 一律失配。
    return adb("logcat", "-d").stdout or ""


def hook_lines(text):
    return "\n".join(l for l in text.splitlines() if "VFlowHook" in l)


def app_lines(text, *tags):
    out = []
    for l in text.splitlines():
        if any(t in l for t in tags):
            out.append(l)
    return "\n".join(out)


# ── 用例定义 ───────────────────────────────────────────────────
#
# timeout_ms / out_file / 额外步骤在此声明；脚本正文在 cases/NN_*.js。

CASES = {
    "01": dict(file="01_basic.js", timeout=5000, out="t4_out_01.txt",
               settle=6),
    "02": dict(file="02_nested.js", timeout=5000, out="t4_out_02.txt",
               settle=6,
               # ⚠️ 为什么需要探针：模块 outputs 是 VDictionary，而它的 asString()
               # 把**每个值都包引号**（`{"a": "{"b": "1, 2"}"}`）⇒ 光看整串
               # 区分不出「嵌套结构对了」与「值被字符串化了」。而 `{{s1.outputs.a.b}}`
               # 若取到 `1, 2`，就证明 `a` 确实是**可按键导航的字典**（不是字符串）。
               probes=[("{{s1.outputs.a.b}}", "t4_probe_02.txt")]),
    "03": dict(file="03_null.js", timeout=5000, out="t4_out_03.txt",
               settle=6,
               # null 的硬断言：
               #  probe0：**兄弟键** b 能按 key 取到 1 ⇒ 字典本身是好的；
               #  probe1：`a` 这个键**存在**（取到空串；不存在的键取到的是字面 `{{…}}`）。
               probes=[("{{s1.outputs.b}}", "t4_probe_03.txt"),
                       ("{{s1.outputs.a}}", "t4_probe_03a.txt")]),
    "04": dict(file="04_timeout.js", timeout=1500, out="t4_out_04.txt",
               settle=8),
    "05": dict(file="05_timeout_trycatch.js", timeout=1500,
               out="t4_out_05.txt", settle=8),
    # 06 是**并发**用例，不是重复用例：三次触发必须**同时在途**才能压满池（容量 2）。
    # ⚠️ 脚本的 sleep(3000) 一过工作线程就自然释放 ⇒ 间隔必须 **< 3 秒**，
    #    否则池早就空了、第 3 次会成功 —— 那不是「不可中断」，是验证方法错。
    #    第 4 次在 6 秒后（两个线程都已释放）用于确认**池会恢复**。
    "06": dict(file="06_block.js", timeout=1000, out="t4_out_06.txt",
               burst=[0, 1, 2], tail=6, tail_expect_ok=True),
    "07": dict(file="07_no_vflow.js", timeout=5000, out="t4_out_07.txt",
               settle=6),
    "08": dict(file="08_stack.js", timeout=5000, out="t4_out_08.txt",
               settle=6, expect_fail=True),
    # 09 需要在**通道断开**的状态下跑（先执行 `bash scripts/xposed-js-verify.sh break`）。
    # 脚本内容随便，因为在 App 侧就被拦下（hook 层收不到请求）。
    "09": dict(file="01_basic.js", timeout=5000, settle=6),
    # 10：无返回值 ⇒ outputs = **空字典**（对应 protocol 的 `items = [{}]`）。
    # 交付要求点名要验：「脚本无返回值 ⇒ items = [{}]（空字典，**不是**空 items）——
    # 断言时别误判为失败」。工作流**不能失败**。
    "10": dict(file="10_noreturn.js", timeout=5000, settle=6),
    # 11：正则可用性 —— 修 `RhinoServiceWarmUp`（TCCL/ServiceLoader）之后的回归。
    # ⚠️ 它**只能真机跑**：单测 JVM 的 TCCL 是对的，正则一直是好的，测不出这个缺陷。
    # 期望产物含 lit=true / grp=12 / rep=a#b# / typeof=function。
    "11": dict(file="11_regexp.js", timeout=5000, settle=6),
}


def run_case(tok, cid, spec, cases_dir, out_dir):
    src = open(os.path.join(cases_dir, spec["file"]), encoding="utf-8").read()
    cdir = os.path.join(out_dir, "cases")
    os.makedirs(cdir, exist_ok=True)

    print("\n  ── case %s ──" % cid)
    probes = spec.get("probes") or []
    probe_paths = {}
    for i, (expr, _name) in enumerate(probes):
        probe_paths[i] = "/sdcard/vFlow/exports/t4_probe_%s_%d.txt" % (cid, i)
    wfid = make_workflow(tok, "%s case%s" % (TAG, cid), src,
                         spec["timeout"],
                         out_file=("/sdcard/vFlow/exports/" + spec["out"])
                         if spec.get("out") else None,
                         probes=[(e, probe_paths[i])
                                 for i, (e, _n) in enumerate(probes)])
    if not wfid:
        print("    ❌ 建工作流失败")
        return
    print("    工作流 id=%s（timeout_ms=%d）" % (wfid, spec["timeout"]))

    # 清旧产物 + 清日志
    if spec.get("out"):
        core("rm -f /sdcard/vFlow/exports/" + spec["out"])
    for pp in probe_paths.values():
        core("rm -f " + pp)
    adb("logcat", "-c")

    repeats = spec.get("repeats", 1)
    burst = spec.get("burst")
    if burst:
        # 并发式：按给定时刻（相对于首次触发的秒数）连发，逼出「池满」。
        #
        # ⚠️⚠️ **每个并发点必须用【不同的工作流】** —— 同一个工作流连发会被
        # `WorkflowExecutor` 的**重入保护**挡住（`block_new` ⇒ 日志「已在运行，
        # 忽略新的执行请求」），请求**根本到不了 capability 层** ⇒ 池永远不会满，
        # 于是第 6 项会被误判成「不可中断不成立」。实测踩过这一条。
        extra_ids = []
        for k in range(1, len(burst)):
            eid = make_workflow(tok, "%s case%s-%d" % (TAG, cid, k), src,
                                spec["timeout"],
                                out_file=("/sdcard/vFlow/exports/" +
                                          spec["out"].replace(".txt", "_%d.txt" % k))
                                if spec.get("out") else None)
            extra_ids.append(eid)
        all_ids = [wfid] + extra_ids
        t0 = time.time()
        for i, off in enumerate(sorted(burst)):
            while time.time() - t0 < off:
                time.sleep(0.05)
            trigger(all_ids[i % len(all_ids)])
            print("    已触发（+%.2fs，工作流 %d）" % (time.time() - t0, i + 1))
        time.sleep(spec.get("settle", 6))
        for eid in extra_ids:
            delete_workflow(tok, eid)
    else:
        for i in range(repeats):
            trigger(wfid)
            print("    已触发 %d/%d" % (i + 1, repeats))
            time.sleep(spec.get("settle", 6))

    text = logcat()
    if not text.strip():
        # ⚠️ 空采集是**静默**的（曾经因为 adb 多 transport 全空，看起来像「功能不对」）。
        # 必须显式报出来。
        print("    ⚠️ logcat 采集为空 —— 检查 adb 是否有多条 transport（本脚本已固定 -s）")
    open(os.path.join(cdir, cid + ".hook.txt"), "w", encoding="utf-8").write(
        hook_lines(text))
    open(os.path.join(cdir, cid + ".app.txt"), "w", encoding="utf-8").write(
        app_lines(text, "XposedJs", "CapabilityInvoker", "CapabilityPresence",
                  "WorkflowExecutor", "HookChannelController"))

    # 池恢复确认：等工作线程自然释放后再发一次（预期**成功**）。
    # ⚠️ 用**新的**工作流：burst 期间被 `block_new` 挡下的那些执行请求可能
    #    仍让原工作流处于「运行中」，直接重发会被重入保护挡掉、什么都没测到。
    if spec.get("tail"):
        time.sleep(spec["tail"])
        adb("logcat", "-c")
        tail_id = make_workflow(tok, "%s case%s-tail" % (TAG, cid), src,
                                spec["timeout"])
        trigger(tail_id)
        time.sleep(8)
        t2 = logcat()
        open(os.path.join(cdir, cid + ".tail.hook.txt"), "w",
             encoding="utf-8").write(hook_lines(t2))
        open(os.path.join(cdir, cid + ".tail.app.txt"), "w",
             encoding="utf-8").write(app_lines(
                 t2, "XposedJs", "CapabilityInvoker", "WorkflowExecutor"))
        delete_workflow(tok, tail_id)

    if spec.get("out"):
        content = read_file("/sdcard/vFlow/exports/" + spec["out"])
        path = os.path.join(cdir, cid + ".out.txt")
        open(path, "w", encoding="utf-8").write(content or "")
        print("    产物：%s" % ((content or "<空>")[:160].replace("\n", " ")))

    # 探针落盘结果（见 CASES 的 probes 说明）
    for i, (expr, _n) in enumerate(probes):
        c = read_file(probe_paths[i])
        open(os.path.join(cdir, "%s.probe%d.txt" % (cid, i)), "w",
             encoding="utf-8").write(c or "")
        print("    探针 %-22s = %r" % (expr, (c or "<空>")[:80]))

    delete_workflow(tok, wfid)


def main():
    argv = sys.argv[1:]
    mode = argv[0] if argv else "run"
    if mode != "run":
        print("驱动只支持 run", file=sys.stderr)
        return 2

    rest = argv[1:]
    cases_dir = rest[-2]
    out_dir = rest[-1]
    wanted = rest[:-2]

    tok = token()
    print("  token ok（%d 字符）" % len(tok))
    open(os.path.join(out_dir, "workflow_ids.txt"), "a").write("")

    ids = wanted or sorted(CASES.keys())
    for cid in ids:
        spec = CASES.get(cid)
        if not spec:
            print("  ⚠️ 未知用例编号 %s" % cid)
            continue
        run_case(tok, cid, spec, cases_dir, out_dir)

    print("\n  采集完成 → %s/cases/" % out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
