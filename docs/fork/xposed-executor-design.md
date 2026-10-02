# Xposed 执行器通道设计（第二条通道）

> ## ⚠️ 本文的**框架部分已被 [`xposed-architecture-v2.md`](xposed-architecture-v2.md) 取代**（2026-09-28）
>
> **核心订正**：本文把「执行脚本」当成**第二条通道**（与事件源并列）。
> **实际上它不是一条通道，是 ③ 能力调用的一个 capability**（`execute_script`）——
> 共享同一条 binder 连接、同一套信封。
>
> | 本文的内容 | 何处为准 | 已收敛到 |
> |---|---|---|
> | **「第二条通道 = 共享 Transport + 独立语义层」** | ❌ **作废** —— 见 V2.0 §3.2/§3.4 | — |
> | 独立 AIDL 方法 `executeScript` | ❌ 作废（③ 走统一入口 `invoke`） | — |
> | **「必须有超时」（三层）** | ✅ **保留**，降级为 `execute_script` 的实现约束 | ✅ V2.0 **§5.7**（三层只对它有第三层 + 阻塞调用不可中断的限制） |
> | **引用面约束 / `JsConsole` 必须移植** | ✅ **保留** | ✅ V2.0 **§5.7** |
> | **「不注入 `vflow.*` 模块树」** | ✅ **保留**，且升格为**执行环境的定义性差别** | ✅ V2.0 **§5.7** |
> | §7「与 `vflow.core.*` 的分工决策规则」 | ✅ **保留** —— 它是**选执行环境的判据** | ✅ V2.0 **§5.7**（含「这条规则是设计的一部分，不是免责声明」） |
> | §6 安全边界（入口收敛 / 不给 AI 直调 / riskLevel） | ✅ **保留** | ✅ V2.0 **§5.7** |
> | §8 实施顺序（P0 只打日志 → …） | ✅ **保留** | ✅ V2.0 **§5.7** |
> | §1.2「单向推送 vs 请求-响应」的划分轴 | ⚠️ **不准确**（见 `capability-invocation-design.md` §1） | — |
>
> > 📌 **收敛的边界**：V2.0 §5.7 收的是**架构判断与约束**；
> > **实现级细节**（请求/响应字段表、文件组织、执行流程、返回值序列化的坑）
> > **仍以本文为准** —— 那些是 V2.0 的粒度装不下的。
>
> **⚠️ 注意**：V2.0 §3.4 定了 ③ 用 **oneway + 配对响应**，而本文 §2.2 主张「**必须同步**」——
> **以 V2.0 为准**（两者不冲突：「失败可感知」≠「调用方原地阻塞」）。
>
> ---
>
> ### ⚠️ 2026-10-01 正文订正（`§5` / `§6`，**以 V2.0 为准**）
>
> 复核「本文是否符合我们讨论的 JS 模块设计」时，发现**四处写于「两个模块」决策之前**，
> 已就地修正（不是只在头部标注）：
>
> | # | 位置 | 初稿 | 订正后 |
> |---|---|---|---|
> | **1** | §5.1 | 孤立的新模块，未提与 `vflow.system.js` 的关系 | ⚠️ **与已有的并列的第二个 JS 模块** + **三个前提**（共享契约骨架 / 能力导向命名 / **互相指路**） |
> | **2** | §5.1 | `id = "vflow.xposed.script"` | ⚠️ **占位** —— 能力导向命名，不带「Xposed」前缀 |
> | **3** | §2.2 / §3.3 / §4.1 / §4.3 / §5.2 | 通篇的 `executeScript(json, token): String`、「必须同步」 | ⚠️ **`invoke(requestJson)` oneway void**（③ 统一入口 + 配对响应）。**§2.2 整节标注作废**（含 `String executeScript(...)` 的 AIDL 片段）；§3.3 的 AIDL 清单、§4.1 的文件组织、§4.3 的执行流程、§5.2 的调用路径**全部就地改成新形态** |
> | **4** | §6 / §5.3 | 「只有一个模块」 | ⚠️ **只有【一个】能进 system_server 的脚本模块**（App 进程那个不在收敛范围）；文案**必须互相指路** |
>
> ⚠️ **一处措辞纠错（记下来避免再犯）**：
> 复核中我曾把「新增一个模块」写成**「已被推翻」** —— **那是错的**。
> 「新增一个模块」（相对现状）与「两个模块」（目标形态）**是同一件事的两种说法**，不冲突。
> **S1 修正的是【命名】与【与已有模块的关系】，不是「新增」这个动作。**
>
> ✅ **本文作为「实现级细节唯一来源」的地位不变** ——
> 请求/响应字段表、文件组织、执行流程七步、返回值序列化的坑、6 条未决项仍以本文为准。

---

> **性质**：fork 独有文档 → 冲突归**我方**（上游无此文件）。
> **版本**：v1.0（2026-09-27，设计阶段，**未实现**）
> **上位文档**：[`xposed-channel-design.md`](xposed-channel-design.md)（第一条通道 = 事件源）
> **相邻**：[`surveys/script-system-overview.md`](surveys/script-system-overview.md)（脚本能力边界）、
> [`surveys/shortx-script-capability.md`](surveys/shortx-script-capability.md)（ShortX 对照）
>
> **一句话**：现有 Xposed 通道是**采集器**（hook 层只报告，不决策）；
> 本文设计的是**执行器**（hook 层接收代码、执行、返回结果）。
> 两者共享传输层，但**语义层相反，不能共用**。

---

## 1. 为什么要第二条通道

### 1.1 现有通道定位（引自 `xposed-channel-design.md` §4.2.2）

```
下行（App → Hook）：过滤条件  —— 「在哪个包的哪个 hook 点上报告事件」
上行（Hook → App）：事件      —— 单向、异步、有界队列、满时丢弃并计数
```

**核心约束**（§3.2 硬约束，原文）：

> **Hook 层只持有「过滤条件」，不持有任何业务语义**——它不知道有几个工作流、
> 不知道触发后做什么。**这正是「Hook 层不读工作流配置」的落实方式**。

文档还明确定了性两者的差别：

> - **ShortX 下行「完整规则」**（含动作、条件、变量绑定）——因为它的**执行也在 hook 进程**
> - **vFlow 下行「过滤条件」**（监听哪个包的哪个 hook 点）——因为**执行在 App 侧**

### 1.2 新需求正好落在这条线的另一侧

| 维度 | 事件通道（现有） | 执行通道（本文） |
|---|---|---|
| 下行传什么 | **过滤条件**（数据） | **可执行代码**（逻辑） |
| Hook 层角色 | **采集器** | **执行器** |
| 谁做判断 | **App 侧** | hook 层 |
| 通信模式 | 单向推送 | **请求-响应**（要等结果） |
| 可靠性要求 | **有界丢弃 + 计数** | **不可丢**（丢了用户不知） |

**结论：不是加个 API，是新增一条语义相反的通道。**

### 1.3 为什么需要它（收益）

去掉「调模块」的前提后，这条通道能提供的是**别的路径拿不到的能力**：

| 能力 | vFlow 现状 | 本通道 |
|---|---|---|
| **构造对象参数**调系统服务 | ❌ `service call` 只能传基本类型 | ✅ Kotlin/Kotlin 侧 `new Rect(...)` |
| **读返回值 / 链式调用** | ❌ `service call` 拿不到有意义的返回 | ✅ |
| **读/改 system_server 内部对象** | ❌ | ✅ 同进程直接引用 |
| **UID 1000 签名级权限** | ⚠️ shell（2000）覆盖大部分 | ✅ 全量 |

> ⚠️ **与 `vflow.core.*` 的分工**（见 §7）：Core 走 UID 2000，已能做「构造对象参数 + 读返回值」。
> 本通道的**独有价值只在「UID 1000」与「同进程访问 system_server 对象」**两处。
> 若需求不含这两者，**应走 Core，不开本通道**。

---

## 2. 架构决策

### 2.1 决策 1：共享 Transport，独立语义层

```
┌──────────────────────────────────────────────────────────┐
│ ① 执行适配器层（新）                                      │
│    · ScriptExecutor：接脚本 → 跑 → 回收结果                │
│    · 与 HookSource 平级（都是「一层业务」），但职责不同      │
├──────────────────────────────────────────────────────────┤
│ ② 语义层（新，与事件语义层**并列**）                       │
│    · 请求-响应信封（含 requestId，一对一配对）              │
│    · **不复用 EventEnvelope**（它含 seq/dropped，语义不符） │
├──────────────────────────────────────────────────────────┤
│ ③ Transport（复用现有 BinderTransport）                    │
│    · 连接管理 / 重连退避 / 存活性巡检 / 解锁等待            │
│    · 只认「发一条字符串」，不关心背后是什么                 │
└──────────────────────────────────────────────────────────┘
```

**复用边界**：

| 层 | 复用 | 理由 |
|---|---|---|
| `BinderTransport`（连接/重连/巡检） | ✅ **复用** | 与「传什么」正交 |
| `EventEnvelope` / `EventQueue` | ❌ **不复用** | 含 `seq`（丢包检测）、`dropped`（丢弃计数）——执行请求丢弃是错误，不是常态 |
| `HookRuntime` | ❌ 不复用 | 它的职责是「装配信封 + 有界队列」，与请求-响应冲突 |
| token 鉴权 / 恒定时间比较 | ✅ 复用逻辑 | |

### 2.2 决策 2：~~**同步阻塞**（不用 `oneway`）~~ → ⚠️ **已推翻，见 V2.0 §3.4**

> ⚠️⚠️ **本节整节作废（2026-10-01）。** 原决策是「必须同步（非 oneway）」，
> 理由是「脚本执行要等结果」——**那是把「等结果」当成了「原地阻塞」**。
> **V2.0 §3.4 定案：oneway + 配对响应**（`oneway invoke` + `IHookHost.resolve`），
> 两者不冲突 —— **「失败可感知」≠「调用方原地阻塞」**。
>
> ✅ **本节保留的只有一条**：**「执行失败必须能被感知」这条纪律**（它仍然成立，
> 只是承载方式从「同步返回值」换成了「配对响应」）。下面的论证请当作**史料**读。

```aidl
// ⚠️ 初稿（已作废）—— 正确形态见 V2.0 §3.4
String executeScript(String requestJson, String token);
```

**必须同步** —— 脚本执行要等结果。这与 `report` 的 `oneway` 相反，但是**已有的先例**：

`pushConditions(String, String): Boolean` **就是同步的**，且注释写明了理由：

> **刻意不用 `oneway`**：下发的成败必须能被 App 感知 ——
> 「下不去」意味着触发器不会工作，而用户只会看到「没反应」。

同一条纪律适用于执行：**「执行失败」必须能被感知。**

**⚠️ 调用方约束**（沿用 `pushConditions` 的）：

> 调用方（App）**不得在主线程上调它**：它最终会跑到 system_server 侧。

### 2.3 决策 3：**不改 `HookChannelController` 的单例语义**

现有 `HookChannelController` 是 `object`（单例），这是**有意的** ——
一条 binder 连接、一个 token、一份丢包检测状态。

**本设计不拆分它**。执行通道复用同一条 binder（同一个 `IHookCallback`），
只是**多加一个方法**。理由：

| 若拆成两个 Controller | 后果 |
|---|---|
| 两条 binder 连接 | 双倍连接管理、双倍重连、可能不同步 |
| 两个 token | 鉴权面翻倍 |
| 两套存活性巡检 | 与现有 `startLivenessWatchdog` 冲突 |

**⇒ 一条连接、一个 token、两套语义。** 语义分离靠**信封类型**，不靠连接分离。

### 2.4 决策 4：执行**必须有超时**（硬要求）

这是本通道与事件通道最大的工程差别：

| | 事件通道 | 执行通道 |
|---|---|---|
| 卡住的后果 | 少采几条事件 | **system_server 线程被占** |
| 兜底 | 有界队列丢弃 | **无** —— 必须主动中断 |

**手段**（本机实测有效，见 `script-system-overview.md` §9.3）：

```kotlin
cx.setInstructionObserverThreshold(N)          // 每 N 条指令回调一次
// + 自定义 ContextFactory 重写 observeInstructionCount，检查截止时间后抛异常
```

实测：`while(true){}` 在 **1002ms** 被中断。

**三层超时**：
1. Rhino 指令级中断（防死循环）
2. hook 层执行总时长上限（防正常但极慢的脚本）
3. **App 侧 binder 调用超时**（防 hook 层完全不响应 —— binder 同步调用默认无超时）

### 2.5 决策 5：不做沙箱（**本期**），但记录理由

| 项 | 决策 |
|---|---|
| `ClassShutter` 白名单 | ❌ 本期不做 |
| 依据 | 本通道与「用户手写脚本」同侧 —— 用户即设备主人；且**入口只有 `vflow.xposed.script` 一个模块**，能配它的前提是用户已装 LSPosed |
| ⚠️ 风险 | 若将来脚本来源扩展到「远程仓库 / AI 生成」，**必须补沙箱** |
| 备注 | hook 层的 `WireLayerPurityTest` 仍是**引用面**防线（防 App 重类被拖进 system_server），与「沙箱」是两件事 |

---

## 3. 协议设计

### 3.1 请求信封（App → Hook）

```json
{
  "request_id": "a1b2c3d4-...",     // UUID，用于配对响应
  "protocol_version": 1,
  "script": "return 1 + 1;",
  "timeout_ms": 5000,
  "token": "..."                     // 鉴权（与事件通道同一个 token）
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `request_id` | ✅ | 配对响应。**不用 `seq`** —— 那个是事件通道的丢包检测语义 |
| `protocol_version` | ✅ | 版本协商（与事件通道同一套纪律：未知版本必须拒绝而非崩溃） |
| `script` | ✅ | 脚本正文 |
| `timeout_ms` | ✅ | 由 App 侧给定，hook 层据此设中断阈值 |
| `token` | ✅ | 与事件通道**同一个** token（一条连接一个凭据） |

### 3.2 响应信封（Hook → App）

```json
{
  "request_id": "a1b2c3d4-...",
  "ok": true,
  "result_json": "{\"outputs\":{\"result\":2}}",
  "error": null,
  "elapsed_ms": 12
}
```

| 字段 | 说明 |
|---|---|
| `request_id` | 与请求配对 |
| `ok` | 成功与否。**失败必须带 `error`**，绝不静默 |
| `result_json` | 脚本返回值的 JSON 序列化（复用 `JSON.stringify` 思路，见下） |
| `error` | 失败原因（超时 / 语法错误 / 运行时异常） |
| `elapsed_ms` | 便于排查「为什么这么慢」 |

**返回值序列化的坑**（已在 JsConsole 踩过）：

> `Context.toString()` 对 JS 对象返回 `[object Object]`，**必须走 `JSON.stringify`**。
> 且 `stringify` 引用要在作用域建立时取一次持有，不能每次调用都 `initStandardObjects()`。

### 3.3 与事件通道的关系

**两者共用一条 binder，但信封类型不同**：

```
IHookHost（App 提供）
  ├─ registerCallback(cb)                  ← 连接建立
  ├─ report(envelopeJson)  oneway          ← 上行：事件
  └─ resolve(responseJson)  oneway         ← ★ 新增：③ 的应答（V2.0 §3.4）

IHookCallback（hook 层实现）
  ├─ pushConditions(json, token)           ← 下行：过滤条件（同步，不变）
  ├─ ping(): Int / capabilities(): String  ← 心跳 / 能力清单（不变）
  └─ invoke(requestJson)  oneway void      ← ★ 新增：③ 统一入口（**非同步**）
```

⚠️ **与初稿的两处不同（V2.0 §3.4 / §3.2）**：
① `executeScript` → **`invoke`**（统一入口，不是专用方法）；
② **`oneway void`**（不是 `String` 返回值）—— 结果与错误码经 `resolve` 配对回来。

**为什么不新开一个 AIDL 接口**：一条连接上多一个方法，比多一条连接简单得多
（见 §2.3）。且 `report` 与 `invoke` 方向相反、互不干扰。

---

## 4. hook 层实现要点

### 4.1 文件组织

```
app/src/main/java/com/chaomixian/vflow/xposed/
├── VFlowHookEntry.kt          （改：接入 executor）
├── HookRuntime.kt             （不动）
├── BinderTransport.kt         （改：callback 加 invoke 分发）
├── capabilities/              （⚠️ 已有目录，V2.0 的 ③ 运行时在这里 —— 本 capability 注册进它）
│   └── <ScriptCapabilityHandler>.kt
├── script/                    （新目录）
│   ├── ScriptExecutor.kt      · 接请求 → 建 Rhino 环境 → 跑 → 序列化结果
│   ├── ScriptSandbox.kt       · 超时/指令数中断（ContextFactory 子类）
│   └── ScriptRequest.kt       · 请求/响应信封的编解码（纯函数，可单测）
└── wire/                      （不动，与事件通道共用）
```

### 4.2 ⚠️ 引用面约束（本设计最容易出问题的地方）

hook 层跑在 system_server，**引用面是硬约束**：

> **禁止**：`com.chaomixian.vflow.core.*` / `services.*` / Gson / `DebugLogger` /
> 任何需要 App Context 初始化的类。

**对执行器的具体影响**：

| 想用的 | 能否用 | 替代 |
|---|---|---|
| Rhino（`org.mozilla.javascript.*`） | ✅ **能**（纯 JVM 库、零依赖） | — |
| 本仓库的 `JsConsole` | ❌ **不能**（在 `core.execution` 包下） | **在 `xposed/script/` 下重写一份精简版** |
| Gson | ❌ | `org.json`（白名单内） |
| `DebugLogger` | ❌ | `android.util.Log`（照 `HookLog` 的现有做法） |

**⇒ `JsConsole` 要「移植」而非「复用」** —— 这与 `logcat` 那条链路的双份是同类代价，
但**无法避免**（那份依赖 `DebugLogger`，它是 App 侧类）。

#### ⚠️ 白名单有**两张表**，要动的是第二张（`WireLayerPurityTest` 实测原文）

```
① ALLOWED_PREFIXES（**仅 wire/ 子目录**的严格档）
   org.json. / java. / kotlin. / com.chaomixian.vflow.xposed.

② ANDROID_ALLOWLIST + FORBIDDEN_APP_PACKAGES（**整个 xposed/ 包**）
   —— 触发条件是「用了 android.* 或 App 侧包」，逐个显式登记
```

**对执行器的具体影响**：

| 想用的 | 检查项 | 处理 |
|---|---|---|
| `org.mozilla.javascript.*` | **两张表都不涉及**（它既不是 `android.*`，也不在 `FORBIDDEN_APP_PACKAGES`） | ✅ **无需改白名单** |
| `org.json` | 在 `ALLOWED_PREFIXES` 里 | ✅ 已允许 |
| `android.util.Log` | 在 `ANDROID_ALLOWLIST` 里 | ✅ 已允许 |
| `android.os.Looper` / `Handler` | **不在表里** | ⚠️ 若脚本要回调，**必须显式登记 + 说明理由** |
| `com.chaomixian.vflow.core.execution.JsConsole` | 命中 `FORBIDDEN_APP_PACKAGES` 的 `core.` | ❌ **必须移植** |

> **好消息**：`org.mozilla.javascript.*` **不需要改任何白名单** ——
> 现有两张表的判定维度是「是不是 Android 类 / 是不是 App 侧包」，Rhino 两边都不沾。
> 这与 `core.*` 被禁的原因（会拖入 App 重类的静态初始化）**性质不同**。

### 4.3 执行流程

> ⚠️ **函数名与签名已按 V2.0 订正（2026-10-01）**：初稿写 `executeScript(requestJson, token)`
> —— **那是「专用方法」的形态，正是 V2.0 §3.2 推翻的**。③ 走**统一入口** `invoke`，
> 且它是 **`oneway void`**（无返回值，结果经 `IHookHost.resolve` 回）。
> ⚠️ `token` **不在参数里** —— 它在信封内（V2.0 §3.3：请求与响应两个信封**都带 token**）。

```
IHookCallback.invoke(requestJson)          ← oneway void，无返回值
  ├─ 1. 校验 token（恒定时间比较，复用逻辑；token 在信封里，不在参数）
  ├─ 2. 解析请求（org.json）
  ├─ 3. 建 Rhino 环境
  │     · Context.enter()
  │     · setOptimizationLevel(-1)          ← 与 App 侧一致
  │     · setInstructionObserverThreshold(N)
  │     · ImporterTopLevel(context)         ← 与 App 侧一致，提供 importClass
  │     · 注入 systemContext（system_server 的 Context，若可取）
  │     · 注入 console（**移植版**）
  │     · **不注入 vflow.* 模块树**（见 §1.2 的前提）
  ├─ 4. 投递到**自建有界工作线程池**执行（⚠️ 不在 binder 线程上跑 —— V2.0 §5.1）
  ├─ 5. 执行（带超时中断）
  ├─ 6. 序列化结果（JSON.stringify 思路）
  └─ 7. 组装响应 → 经 IHookHost.resolve(responseJson) 回（oneway）
```

### 4.4 线程与阻塞

| 项 | 处理 |
|---|---|
| 执行线程 | **不能是 system_server 主线程** —— Rhino 执行是 CPU 密集，会拖住系统 |
| 方案 | 在 binder 线程上执行（binder 线程池与主线程隔离），但**要有超时兜底** |
| ⚠️ 风险 | 若脚本卡住，占用的是一个 binder 线程 —— 好过主线程，但仍有限 |
| 建议 | 先按「binder 线程 + 超时」做，实测后再评估是否要独立线程池 |

---

## 5. App 侧实现要点

### 5.1 新模块

> ⚠️ **本节已按 V2.0 §5.7 订正（2026-10-01）。** 三处与初稿不同，**动手前必须照此实现**：
>
> | 项 | v1.0 初稿 | **订正后（V2.0 为准）** |
> |---|---|---|
> | 模块定位 | 孤立的新模块 | ⚠️ **与已有的 `vflow.system.js` 并列的第二个 JS 模块**（「新增一个模块」是对的，但**必须共享契约骨架**） |
> | `id` | `vflow.xposed.script` | ⚠️ **能力导向命名，不带「Xposed」前缀**（初稿的 id 是占位） |
> | 调用形态 | `HookChannelController.executeScript(...)` 返回 `Boolean` | ⚠️ **③ 的 oneway + 配对响应** ⇒ 返回**结果对象**（含 `error` / 错误码），不是 `Boolean` |
> | 文案 | 只说代价 | ⚠️ **必须互相指路**（告诉用户「若需要 xxx 请用另一个模块」） |

#### 三个前提（缺一不可，V2.0 §5.7）

| # | 前提 | 说明 |
|---|---|---|
| **1** | **共享契约骨架** | 参数（`script` / `inputs` / `timeout_ms`）、输出（`outputs`）、编辑器布局**与 `vflow.system.js` 完全一致** ⇒ 抽基类或共享 UIProvider。**真实差异只有三处**：执行器 / `riskLevel`+权限 / 文案 |
| **2** | **能力导向命名与文案** | ❌ 不叫「JS 脚本 (Xposed)」；✅ 要叫出**能力差别**（能在哪跑、能做什么） |
| **3** | **互相指路** | 两个模块的描述里都要写清分工 —— 这是化解「AI 面板出现两个近同名工具」的**唯一手段**（`survey §7.1` 的担忧是真的） |

#### 模块定义

```
core/workflow/module/**（按能力命名选目录）**/XxxScriptModule.kt
  id = "vflow.???.???"          ← ⚠️ 占位，能力导向命名（V2.0 §5.7）
  riskLevel = HIGH（在 system_server 执行 + UID 1000）
  requiredPermissions = listOf(PermissionManager.XPOSED_HOOK)
  usageScopes = TEMPORARY_WORKFLOW（**不给 DIRECT_TOOL** —— 见 §6）
```

**输入**：

| key | 类型 | 说明 | 与 `vflow.system.js` |
|---|---|---|---|
| `script` | STRING | 脚本正文 | ✅ **同名同义** |
| `inputs` | ANY | 传给脚本的 inputs（JSON 序列化后下行） | ✅ **同名同义** |
| `timeout_ms` | NUMBER | 默认 5000，上限 30000 | ⚠️ **本模块独有**（App 侧那个没有超时，见 §8 开工前置条件） |

**输出**：`outputs`（DICTIONARY）—— 脚本返回的字典。**与 `vflow.system.js` 同名同义。**

### 5.2 调用路径

⚠️ **初稿写「与 `pushConditions` 同构」是错的** —— 那个形态（同步返回 `Boolean`）
正是 V2.0 §3.4 推翻的。**③ 是 oneway + 配对响应**：

```kotlin
// ③ 的统一入口（V2.0 §3.4）；结果经 IHookHost.resolve 配对回来
val outcome = CapabilityInvoker.invoke(
    capability = <本模块的 capability 名>,
    params = mapOf("script" to script, "inputs" to inputs),
    timeoutMs = timeoutMs,
)
// ⚠️ 返回的是【结果对象】而非 Boolean —— 失败要能区分错误码
//    （CAPABILITY_ABSENT / TIMEOUT / HANDLER_ERROR / CHANNEL_DOWN / PAYLOAD_TOO_LARGE）
when (outcome) {
    is Success -> ExecutionResult.Success(...)
    is Failed  -> ExecutionResult.Failure(...)   // **失败绝不静默**
    is Degraded -> ...                            // 见 V2.0 §6.2（本 capability 是独占型 ⇒ 无降级）
}
```

**⚠️ 不能在主线程调用** —— 它最终是一次到 system_server 的调用，且有超时等待。

### 5.3 模块文案（三语）

**必须说清代价**，否则用户在 system_server 里跑死循环会导致整机问题：

> 在系统进程中执行 JavaScript。
> 脚本运行在 system_server（系统进程）内，可访问系统内部接口，
> 但**脚本出错会影响整个系统**。请谨慎使用。

⚠️ **且必须互相指路**（V2.0 §5.7 前提 3）—— 文案里要写清与 App 进程那个 JS 模块的分工：

| 该用哪个 | 判据 |
|---|---|
| **App 进程的 JS 模块** | 需要 `vflow.*` 模块树编排、读写全局变量 |
| **本模块** | 需要 UID 1000 权限、或访问 system_server 内部对象 |

> ⚠️ **默认不要让用户「猜」** —— 两个都叫 JS，用户/AI 唯一的依据就是这段文案。
> 具体措辞在实现时定（三语同步），但**分工说明不能省**。

---

## 6. 安全边界

| 面向 | 措施 |
|---|---|
| **入口收敛** | ⚠️ **只有【一个】能进 system_server 的脚本模块**（需 `XPOSED_HOOK` 权限，用户须已配 LSPosed）。⚠️ **App 进程那个 JS 模块不在收敛范围内** —— 它跑在单进程、崩溃半径不是整机（V2.0 §5.7） |
| **不给 AI 直调** | `usageScopes` 只给 `TEMPORARY_WORKFLOW`，**不给 `DIRECT_TOOL`** —— 避免 AI 未经人审就写 system_server 脚本 |
| **riskLevel = HIGH** | 走审批流程。⚠️ **这也正是「两个模块」而非「一个模块 + 环境参数」的理由之一** —— 否则 App 进程那个模块会被迫一起标 HIGH（V2.0 §5.7） |
| **超时** | 三层（§2.4）。⚠️ **但阻塞的 Java 调用不可中断** —— 指令级观察器只覆盖纯计算死循环（V2.0 §5.7） |
| **无沙箱** | ⚠️ 本期不做，理由是「用户即设备主人」；**若来源扩展到下载/AI，必须补** |

---

## 7. 与既有路径的对照（**动手前必读**）

**本通道不是「更全的 Core」，两者解决不同问题**：

| 需求 | `vflow.core.*`（UID 2000） | `vflow.xposed.script`（UID 1000） |
|---|---|---|
| 基本类型参数调系统服务 | ✅ | ✅ |
| **构造对象参数** | ✅ **能**（Kotlin 侧 `new Rect()`） | ✅ |
| **读返回值 / 链式调用** | ✅ **能** | ✅ |
| 读 system_server 内部对象 | ❌ | ✅ |
| 签名级权限（UID 1000 专属） | ❌ | ✅ |
| **崩溃半径** | **一个进程** | **整机** |
| 改造量 | 中（加 wrapper + 模块） | **大**（新通道 + 超时 + 白名单 + 移植） |

**⇒ 决策规则**：

> **只有当需求落在「UID 1000 专属权限」或「同进程访问 system_server 对象」时，才做本通道。**
> 其余「构造对象 / 读返回值」的场景，**扩展 `vflow.core.*` 成本低得多、风险小得多**。

---

## 8. 实施顺序

| 阶段 | 内容 | 验证 |
|---|---|---|
| **P0** | hook 层能在 system_server 里跑通 Rhino（只打日志，不接通信） | 真机：`Log` 里有脚本结果；无崩溃 |
| **P1** | 超时机制（三层） | 单测：死循环在 N ms 内中断 |
| **P2** | 协议编解码（纯函数） | 单测：round-trip、坏 JSON 返回 null 而非抛 |
| **P3** | AIDL + App 模块接线 | 真机：工作流里跑脚本、拿到结果 |
| **P4** | 文案 + 权限 + 白名单复核 | `WireLayerPurityTest` 通过；三语文案 |

> **白名单说明**：Rhino 本身**不需要改白名单**（见 §4.2）。
> 但**若脚本要回调 / 用 `Handler` / `Looper`**，需在 `ANDROID_ALLOWLIST` 显式登记 ——
> 那条规则是「加新的 android import 必须显式登记」，故意设的一道人工闸。

> **P0 必须最先做** —— 它是「Rhino 能否在 system_server 里跑」的**唯一验证点**，
> 且**失败代价最小**（不接通信、不挂 hook）。
> 这与事件通道 P0 的思路一致（先证明能加载，再加东西）。

---

## 9. 未决项 / 待验证

| # | 项 | 为什么未决 | 怎么验 |
|---|---|---|---|
| 1 | **Rhino 在 system_server 里能否正常初始化** | 未实测 | P0 |
| 2 | **`ImporterTopLevel` 在 hook 层是否可用**（它依赖 `Context`，而 `Context` 需要 ClassLoader 正确） | 未实测 | P0 |
| 3 | ~~`system_server` 的 Context 怎么取~~ | ✅ **已有实现** | `VFlowHookEntry.systemContext()`（反射 `ActivityThread.currentActivityThread()` 取）—— **直接复用**，不需新设计 |
| 4 | **binder 线程执行是否够**（还是需要独立线程池） | 未实测 | P3 |
| 5 | **超时中断能否可靠终止 Rhino**（`while(true)` 实测有效，但深度递归/大内存分配呢） | 部分实测 | P1 |
| 6 | 脚本能否调 `vflow.*` | **设计上明确不做**（§1.2），但若将来要做，需重新评估架构 | — |

---

## 10. 一句话总结

**第二条通道 = 共享 Transport + 独立语义层（请求-响应信封）+ 强制超时。**

- **复用**：`BinderTransport`（连接/重连/巡检）、token 鉴权逻辑
- **新建**：执行适配器、请求-响应信封、超时中断、`console` 移植版
- **不动**：事件通道的 `EventEnvelope` / `EventQueue` / `HookRuntime`（语义相反，共用会互相污染）
- **不开沙箱**（本期），但**入口收敛 + 不给 AI 直调 + 超时**三条必须落地

**⚠️ 动手前先过 §7 的决策规则** —— 若需求不含「UID 1000 专属权限」或
「同进程访问 system_server 对象」，**扩展 `vFlow.core.*` 是更省的路径**。
