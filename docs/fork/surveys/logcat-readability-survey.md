# logcat 可读性调研（能否做 logcat 触发器）

> 版本：v1.0
> 状态：真机实测定稿（2026-09-15，`session-0915-01` 分支）
> 目录归属：**fork 独有**（冲突归我方），上游无此文件
> 用途：回答「vFlow 能否做一个监听 logcat 的触发器」，为后续实现提供事实依据。
> 证据强度：**全部结论来自真机实测**（小米 MIX Fold 3 / Android 17 / API 37 / HyperOS），非源码推断。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 无 root、纯 Shizuku 下能读 logcat 吗？ | ✅ **能** |
| 需要 `READ_LOGS` 权限吗？ | ❌ **不需要** |
| 能读到系统日志吗？ | ✅ 能（ActivityManager / WindowManager / AndroidRuntime 均实测可读） |
| 能读到第三方应用日志吗？ | ✅ 能（微信 41 行、QQ 13 行） |
| 应用进程内（不走 Shell）能读别人日志吗？ | ❌ 不能，只能读自己 |

**一句话**：走 Shizuku/Core 的 Shell 通道跑 `logcat` 完全可行，**无需任何额外授权**。
根因是 `shell` 用户（uid 2000）本就在 `log` 组（gid 1007）里。

> ⚠️ 本文只回答「能不能读」，**不涉及触发器的设计**（过滤、冷却、性能）。
> 那部分待讨论，见 §6。

---

## 1. 为什么曾误判为「做不到」

调研初期我依据 `READ_LOGS` 的 `protectionLevel` 下了「普通应用拿不到」的结论，**这是错的**。

AOSP `frameworks/base/core/res/AndroidManifest.xml`：

```xml
<permission android:name="android.permission.READ_LOGS"
            android:protectionLevel="signature|privileged|development" />
```

错在哪：我漏读了 **`development`** 的语义。

- `signature` / `privileged` —— 确实拿不到
- **`development`** —— **可由 `adb shell pm grant` 授予**

而 Shizuku 本质上就是 ADB 权限。这也是 Tasker 官方文档给出的做法：

```
adb shell pm grant net.dinglisch.android.taskerm android.permission.READ_LOGS
adb shell am force-stop net.dinglisch.android.taskerm
```

**教训**：只读 `protectionLevel` 而不理解每个 flag 的含义，会得出相反的结论。
（这一点由用户指出后才纠正。）

---

## 2. 实测环境

| 项 | 值 |
|---|---|
| 设备 | Xiaomi MIX Fold 3（日志内码 `2308CPXD0C`） |
| 系统 | Android 17（API 37）/ HyperOS |
| vFlow | 1.5.4 |
| 构建类型 | `ro.build.type=user`，`ro.debuggable=0` |
| SELinux | Enforcing |
| **权限环境** | **纯 Shizuku，无 root**（`su: Permission denied`；Core `ping` 返回 `uid=2000, mode=SHELL`） |

**关键身份信息**（实测输出）：

```
uid=2000(shell) gid=2000(shell)
groups=2000(shell),1004(input),1007(log),1011(adb),1015(sdcard_rw),1028(sdcard_r),...
                                    ^^^^^^^^^
```

**`1007(log)` 就是能读日志的根本原因。** 这是 Android 给 `shell` 用户的固有组成员资格，
不需要任何运行时授权。

> 注：早期一次测试中该设备的 Shizuku 曾由 root 启动（`context=u:r:ksu:s0`），
> 那次的结论**只代表 root 场景**，不可外推。本文所有数据均来自「无 root」那次。

---

## 3. 实测数据

### 3.1 Shell 通道（Shizuku / uid 2000）

| 采样目标 | 结果 |
|---|---|
| 执行身份 | `uid=2000(shell)` ✅ 确认非 root |
| `logcat -d` 总行数 | 49 行，`exit=0` |
| 权限判定 | ✅ 未被拒 |
| 读回本进程刚打的 marker | ✅ 能 |
| **自己进程**（`--pid`） | 273 行 ✅ |
| **系统 TAG** `ActivityManager`（全量缓冲） | **1341 行** ✅ |
| **系统 TAG** `WindowManager`（全量缓冲） | **3298 行** ✅ |
| **系统 TAG** `AndroidRuntime`（全量缓冲） | **81 行** ✅ |
| 对照 TAG `LogcatProbe`（必然有输出） | 65 行 ✅ 通道本身可用 |
| **第三方** `com.tencent.mm`（pid=11694） | **41 行** ✅ |
| **第三方** `com.tencent.mobileqq`（pid=14349） | **13 行** ✅ |

### 3.2 应用进程内直读（不走 Shell）

| 场景 | 结果 |
|---|---|
| 未授权（`granted=false`） | 只能读到自己（他进程行数 = 0） |
| `pm grant` 后（未重启） | 仍然 `granted=false`，只读到自己 |

**即：应用进程内直读无实用价值**，必须走 Shell 通道。

---

## 4. 三个已澄清的陷阱

### 4.1 采样窗口陷阱（曾导致误判「系统日志读不到」）

**现象**：首轮测试 `logcat -d -s ActivityManager -t 200` 返回 **0 行**，
一度被解读为「系统日志无权限读取」。

**真相**：`-t 200` 是**先截取最近 200 行、再按 TAG 过滤**。
这 200 行里恰好没有该 TAG，于是 0 行——**与权限无关**。

**修正做法**：改用 `logcat -d -s TAG`（不加 `-t`），对全量缓冲区过滤。
同一批 TAG 立刻从 0 行变为 1341 / 3298 / 81 行。

**方法论**：判定「有无权限」时，**必须带一个已知必然有输出的对照项**。
本轮加的 `D.2b 对照 TAG` 正是为此——若对照项也是 0 行，才能说明是权限或通道问题。

### 4.2 「被拒」判定的假阴性

**现象**：某轮中 `logcat` 明明返回 49 行（`exit=0`），却被判定为「❌ 被拒」。

**真相**：判定逻辑用的是子串匹配 `"Permission denied"`，
而抓到的样本里恰好含有一行**无关的**日志：

```
W vFlowShellManager: java.io.IOException: Cannot run program "su": error=13, Permission denied
```

这是 vFlow 自己尝试 `su` 失败的日志，与 logcat 无关。

**修正做法**：不要用子串匹配判断权限，应依据 **`exit code` + 实际内容行数**，
或统计「非本进程的日志行数」（更可靠）。

### 4.3 结果代表性的自我标注

**现象**：首轮测试标签写作 `A[SHIZUKU(uid 2000)]`，
但实测身份是 `uid=0(root)`（Shizuku 由 root 启动）——**标签在说谎**。

**修正做法**：探针不预设 uid，实测身份后据实标注；
若「标称 Shizuku、身份却是 root」则主动告警「结论只代表 root 场景」。
更一般地：**任何依赖运行身份的实验，都应显式记录并校验实际身份**。

---

## 5. 对照：其他「类似但不同」的结论

调研中顺带澄清的相邻问题，避免日后重复踩坑：

| 能力 | 可行性 | 依据 |
|---|---|---|
| `READ_LOGS` 权限本身 | ⚠️ 可 `pm grant`，但**本场景并不需要** | §1 |
| 切换默认数据卡（数据 SIM） | ❌ 不可行 | `SubscriptionManager.setDefaultDataSubscriptionId` 是 `@SystemApi @hide`，需 `MODIFY_PHONE_STATE`（签名级） |
| 监听默认数据卡切换 | ⚠️ 存疑 | 公开的只有 `ACTION_DEFAULT_SUBSCRIPTION_CHANGED`（泛称），数据卡专用的 `ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED` 是 `@SystemApi` |
| 修改全局勿扰 | ❌ 不可行（Android 15+） | `targetSdk ≥ 35` 时 `setInterruptionFilter` 不再改全局，只操作应用自建规则；见 `do-not-disturb-trigger.md` §6.1 |
| 监听勿扰变化 | ✅ 可行 | `ACTION_INTERRUPTION_FILTER_CHANGED`，只需 `NOTIFICATION_POLICY`；见 `do-not-disturb-trigger.md` |

---

## 6. 待讨论：触发器的设计问题（本文不决策）

可行性已确认，但**实现方案尚未定**。以下是需要单独讨论的点：

### 6.1 性能与冷却（最大风险）

logcat 是**持续高频流**，与前 24 个触发器的「低频事件」性质完全不同：

- 用户配的关键字可能每秒命中几十次
- 逐行正则匹配本身的 CPU 开销
- 每次命中都执行工作流 → 若无冷却会拖垮设备

**必须设计**：匹配 → 冷却窗口 → 内容去重。

### 6.2 事件源接入方式

两条候选路径：

| 路径 | 说明 |
|---|---|
| **Core 流式通道** | 复用 `StreamingWrapper` + `VFlowCoreBridge.streamClipboardEvents()` 的现成模式（`ClipboardTriggerHandler` 已跑通） |
| Shell 常驻子进程 | 参照 `KeyEventTriggerHandler`：部署二进制 + shell 拉起 + Intent 回传 |

### 6.3 参数形态

过滤器如何暴露给用户？候选：

- 按 **TAG** 过滤（`logcat -s TAG`）
- 按 **关键字 / 正则**匹配整行
- 按 **包名 / pid** 限定
- **logcat 参数**是否由用户完全自定义（灵活但有注入风险）

### 6.4 权限声明

不需 `READ_LOGS`，但需要 Shell 能力：

```
requiredPermissions = listOf(PermissionManager.SHIZUKU)   // 或 ROOT
```

---

## 7. 探针记录（已删除）

为得到上述结论，曾临时加入 `LogcatProbeModule`（放 `shizuku` 分类的诊断模块），
**验证完成后已完全移除**（模块文件 + 注册 + 三语文案共 4 处），工作区已还原。

探针的设计要点对将来做类似调研有参考价值，故记录：

| 段 | 探测内容 |
|---|---|
| E | 环境：`ro.build.type` / `ro.debuggable` / SELinux / **实际执行身份** |
| A | 经 Shell 跑 logcat：身份、行数、样本、是否被拒、能否读回自打 marker |
| B | 应用进程内未授权基线（统计**他进程行数**） |
| C | `pm grant` 后重测（Shizuku 与 root 分别尝试） |
| D | 可读范围采样：自己进程 / 系统 TAG / **第三方应用** / 对照 TAG |

**探针踩过的坑**（均已修正，见 §4）：采样窗口过小、子串匹配假阴性、标签与实际身份不符。

---

## 8. 引用前须知

- **结论基于单台设备**（小米 MIX Fold 3 / Android 17 / HyperOS）。
  其他厂商 ROM 是否同样把 `shell` 放在 `log` 组、是否有额外日志限制，**未验证**。
  如需跨设备结论，应重跑同类探针。
- 本文**未覆盖**：日志频率对耗电的实际影响、长时运行稳定性、Android 各版本差异。
- 上游若调整 Shell/Core 通道实现，§6.2 的路径选择需重新评估。
