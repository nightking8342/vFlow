# Xposed 通道通信探针 —— 实测结论

> **日期**：2026-09-26
> **设备**：小米 MIX Fold 3（Android 17 / API 37 / 澎湃 OS，LSPosed 1.0 已运行）
> **目的**：验证 `docs/fork/xposed-channel-design.md` §4.2 通信链路的地基假设
>
> **⚠️ 本文的写作纪律**（因为它经过多轮返工）：
> 每条结论**必须标注证据类型**：
> - 【文档】= 官方文档逐字引用
> - 【实测】= 本机受控实验，可复现
> - 【推断】= 尚未直接验证，明确标出
>
> **教训**：前几轮我用「实测 + 推理」替代「查文档定机制」，导致每被追问一句就翻一次结论。
> 正确顺序是**先查文档定机制，再用实验验细节**。

---

## 0. 结论汇总

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 1 | 包可见性会不会拦住广播投递 | ✅ **不会** | 【实测】A 组 |
| 2 | `broadcastPermission` 的方向 | ✅ 「发送方需持有」 | 【实测】B 组 |
| 3 | signature 权限能否挡住异签发送方 | ✅ **能** | 【实测】B 组 |
| 4 | bindService / startService 可用吗 | ❌ **需包可见性 ⇒ 基本不可用** | 【文档】+【实测】A/C 组 |
| 5 | hook 层能否读 `/sdcard/vFlow/` | ❌ 读不到 | 【实测】 |
| 6 | `<uses-permission>` ⇒ 对方可见？ | ⚠️ **有，但官方未记载，且绑定「权限定义方」** | 【实测】v2/v4/v5 |

---

## 1. 地基结论：广播不受包可见性影响 ⭐

**这是整个方案的地基。** 因为 bindService 出局后，广播是唯一希望。

**受控实验**（A 组：无 `<queries>`、无 `<uses-permission>` ⇒ 对 vFlow 完全不可见）：

```
A 组自报:  包可见性: vFlow 对我【不可见】
           bindService() 返回 false          ← 显式组件确实被拦
           ③ 已发【无权限】广播

fake-vflow 侧:
           ★★★ 收到【无权限】广播 | senderUid=10702   ← ⭐ 广播送达了
```

**结论**：**包可见性拦的是「查询类 API」与「显式组件调用」，不拦广播投递。**
hook 层即使在目标 App 进程里（用目标 App 的可见性规则、看不见 vFlow），**照样能把广播发给 vFlow**。

### 1.1 官方文档对「显式组件被拦」的表述

【文档】[package-visibility](https://developer.android.com/training/package-visibility)：

> "The limited visibility also affects **explicit interactions** with other apps, such as **starting another app's service**."

### 1.2 官方文档对「startActivity 不被拦」的表述

【文档】[use-cases](https://developer.android.com/training/package-visibility/use-cases)：

> "Because the `startActivity()` method **doesn't require package visibility** to start another application's activity, you don't need to add a `<queries>` element..."

> ⚠️ **注意**：官方文档**没有**明确写过「广播是否受可见性影响」。
> 结论 1 是**实测**得出的，不是文档写的。

---

## 2. 鉴权方案成立（Q2/Q3）

**受控矩阵**（fake-hook 声明并申请了两个权限，构成对照）：

| 广播 | receiver 要求 | fake-hook 持有 | 结果 |
|---|---|---|---|
| ① `PUSH_CONDITIONS` | `HOOK_CONTROL`（**signature**） | `checkSelfPermission = -1` **DENIED** | ❌ 没收到 |
| ② `PUSH_OPEN`（阳性对照） | `OPEN_CONTROL`（**normal**） | `checkSelfPermission = 0` **GRANTED** | ✅ **收到** `senderUid=10470` |

**② 收到证明投递链路正常 ⇒ ① 没收到只能是权限拦的。** 干净的因果分离。

**排除「接收器坏了」的佐证**：让 fake-vflow **自己发广播给自己**（同应用广播不受 receiver 权限限制），
两个接收器**都收到了**。证明接收器/清单/广播机制均正常。

**结论**：
- ✅ `registerReceiver(receiver, filter, **broadcastPermission**, scheduler)` 的语义
  **确实是「发送方必须持有该权限」**——设计文档里那条**未逐字核实**的引用，**实测证实**
- ✅ `signature` 级权限**能挡住异签应用**
- ⭐ **「下行广播带 `HOOK_CONTROL` 保护」的鉴权方案成立**

> ⚠️ 【文档】缺口：官方文档同样**没有**明写 `broadcastPermission` 的方向。
> 这是**实测**结论。`registerReceiver` 与 `sendBroadcast(intent, receiverPermission)` 方向相反，易记混。

---

## 3. `<uses-permission>` 与可见性（一个官方未记载的行为）

### 3.1 实测结果

| 组 | 声明的权限 | 目标 | 结果 |
|---|---|---|---|
| v1 | 无 | xiaomi.smarthome | ❌ 不可见 |
| v2 | `com.xiaomi.smarthome.permission.PUSH_WRITE_PROVIDER` | xiaomi.smarthome | ✅ **可见** |
| v3 | `com.xiaomi.smarthome.permission.THIS_PERMISSION_DOES_NOT_EXIST` | xiaomi.smarthome | ❌ 不可见 |
| v4 | `com.miui.misight.permission.BIND_SERVICE` | com.miui.misight / **misightservice** | ❌ / ✅ |
| v5 | 同 v4 | misightservice | ✅ **可见** |

### 3.2 机制（修正后的解释）

**声明某权限 ⇒ 该权限的 `sourcePackage`（定义方）对你可见。**

v4/v5 是决定性证据：申请的权限名叫 `com.miui.**misight**.permission.BIND_SERVICE`，
但它的 `package:` 是 `com.miui.**misightservice**`——
结果 **misightservice 可见、misight 不可见**。**名字前缀不算，定义方才算。**

v3 反向证实：申请不存在的权限 ⇒ 没有定义方 ⇒ 无人可见。

### 3.3 ⚠️ 重要限定

1. **官方文档完全没有记载这个行为**
   【文档】[uses-permission 元素页](https://developer.android.com/guide/topics/manifest/uses-permission-element)
   对包可见性**零提及**；`package-visibility` 系列页面**也没提** `<uses-permission>`。
2. **它对 vFlow 的场景基本没用**——因为受益的必须是**发起查询的那个 uid 的 manifest**。
   hook 层在目标 App 进程里 ⇒ 受益的 manifest 是**目标 App 的**，改不了。
3. **不要**把它当作 `<queries>` 的替代品。<queries> 适用面更广（不要求对方定义任何东西）。

---

## 4. 已证否 / 已出局的通道

### 4.1 bindService / startService —— 出局

【实测】同一 Intent，只改调用方：

| 调用方 | uid | bindService 结果 |
|---|---|---|
| 普通应用（A 组） | 10702 | ❌ `false`；AMS: `U=0: not found` |
| 普通应用（C 组，有 `<queries>`） | — | ✅ `true`，拿到 `BinderProxy` |
| shell | 2000 | ✅ start 成功 |

**reason**：显式组件调用需包可见性；而可见性按**发起方 uid 的 manifest** 判定，
hook 层用的是目标 App 的 ⇒ **改不了** ⇒ 出局。

> ⚠️ 例外：若 hook 的是 **system_server**（5b），uid 1000 不受可见性限制。
> 但 5b 是独立高危机型，不因此改变 5a 的结论。

### 4.2 共享文件 —— 读不到（【实测】）

| 操作 | 结果 |
|---|---|
| `list()` 目录名 | ✅ 返回 5 项 |
| **读文件内容** | ❌ **`EACCES`**（4/4 失败） |
| 写文件 | ❌ `EPERM` |

**关键**：**能列出目录名 ≠ 能读文件内容**。

---

## 5. 最终方案

```
上行（hook → vFlow）：广播（隐式 action）
    ✅ 不受包可见性影响        【实测】
    ⚠️ 无法用 uid 鉴权（发送方 uid = 目标 App）⇒ 靠 token

下行（vFlow → hook）：广播 + signature 权限
    ✅ broadcastPermission 方向正确，能挡住异签方  【实测】

鉴权：token
    下行广播带权限保护 ⇒ 第三方收不到 ⇒ 拿不到 token
    上行带 token ⇒ vFlow 校验
```

**排除项**：bindService / startService（包可见性）、共享文件（读不到）。

---

## 6. 附带发现

### 6.1 `onBind` 里读 `Binder.getCallingUid()` 得到的是自己

第一次探针在 `onBind` 里读，得到 `10236`（**假 vFlow 自己**），真实调用方是 `10470`。
**原因**：`onBind()` 由 AMS 调用，非 binder 事务，`getCallingUid()` 只在事务内有效。
**正确做法**：在真正被客户端调用的方法（如自定义 binder 的方法）里读。

### 6.2 探针打包链路的三个坑（已在 `build.sh` 注释）

| 坑 | 症状 | 解法 |
|---|---|---|
| `-bootclasspath` 在 JDK 21 废弃 | `目标 17 需要 --boot-class-path`，编译失败 | Android 类只放 `-classpath` |
| 密码含 `*` 时 `--ks-pass "pass:..."` 被 shell 展开 | `keystore password was incorrect`，但 keytool 能解开 | 用 `--ks-pass env:VAR` |
| `signing.properties` 是 CRLF | `tr -d '\r'` 会弄坏含特殊字符的密码 | bash `${line%$'\r'}` |

---

## 7. 验证资产与复现

```
scripts/probe/xposed-channel/
├── fake-vflow/      「假 vFlow」（vFlow.jks 同签，SHA-256 = 28d55086… 与真 vFlow 一致）
│   ├── FakeVFlowService.java   返回真 binder（Q1/Q4）
│   ├── GuardedReceiver.java    signature 权限保护（Q2/Q3）
│   ├── OpenReceiver.java       normal 权限阳性对照
│   ├── FreeReceiver.java       无权限接收器（地基结论 ⭐）
│   └── FakeVFlowActivity.java  前台占位 + 自检
├── fake-hook/       「假 hook 层」（debug 签名 = 第三方身份）
│   └── ProbeActivity.java      四条验证 + autorun
├── build.sh
└── 本文件
```

**复现**：

```bash
./build.sh verify
adb shell "am start -n com.vflow.hookprobe.vflow/com.vflow.hookprobe.FakeVFlowActivity"
adb shell "am start -n com.vflow.hookprobe.hook/com.vflow.hookprobe.ProbeActivity --ez autorun true"
adb logcat -s "HookProbe/Hook:*" "HookProbe/VFlow:*"
```

**未解决问题**：hook 层在**真实注入**场景（代码寄居目标 App 进程）下的可见性归属，
仍未直接验证——但结论 1 表明**这一点已不影响方案**（广播不依赖可见性）。
