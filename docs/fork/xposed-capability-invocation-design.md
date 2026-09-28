# Xposed 通道 · App→hook 能力调用设计（第三种生命周期）

> ## ⚠️ 本文的**框架部分已被 [`xposed-architecture-v2.md`](xposed-architecture-v2.md) 取代**（2026-09-28）
>
> V2.0 落 S1–S6 时吸收并**推进**了本文的结论。以 V2.0 为准的部分：
>
> | 本文的结论 | V2.0 的推进 |
> |---|---|
> | 三种生命周期（① 连接 / ② 订阅 / ③ 调用） | ✅ 保留，但**划分轴换成「状态归属」**，并给出第二判据（V2.0 §2.2） |
> | 「`pushConditions` 与 ③ 同格」 | ✅ 保留，但**结论不同** —— 两者**契约不同，就该有两个入口**，不必让 `invoke` 容纳它（V2.0 §3.2） |
> | 「框架必须担三件事」 | ✅ 保留（V2.0 §3 扩展为「hook 侧不占 binder 线程」+ §5 执行模型） |
> | 「加新能力 = 2 处注册」 | ✅ 保留（V2.0 §7.2 扩展为 10 处，其中 3 处是 hook 特有） |
> | 「③ 是数据源，不是目的」 | ⚠️ **措辞不准** —— ③ 是「数据源」这个 **App 侧抽象**的一种可选实现（V2.0 §2.1） |
> | §5.2 把 `token` 写进请求信封 | ⚠️ **已订正** —— token 是连接级凭据，现状由 ① 层经下行下发 |
> | §5.4 「`ping()` 升级成本极低」 | ⚠️ **已订正** —— 它**零调用者**，用途未定前不构成可排期项（V2.0 §6.3 定为「连接期能力交换」） |
>
> **✅ 仍然有效、V2.0 未覆盖的部分**：
> - **§6.2** 快捷方式路径（`LocalServices.getService(ShortcutService)` 的取法与字段名，【推断】待探针）
> - **§6.1** 快捷方式的量化（B 263 / A 44 / D 74 / E 23 / C 4）
> - **§0.3** 那两处预埋缺陷的**原始记录**

---

> **版本**：v1.0（2026-09-28，**设计阶段，未实现**）
> **目录归属**：fork 独有 → 冲突归**我方**（上游无此文件）
> **上位文档**：[`xposed-architecture-v2.md`](xposed-architecture-v2.md)（**架构以它为准**）
> **相邻**：[`xposed-executor-design.md`](xposed-executor-design.md)（执行脚本，**本文的同类成员**）、
> [`surveys/shortcut-system-overview.md`](surveys/shortcut-system-overview.md)（首个用例的背景）
>
> **一句话**：通道目前只有「**事件上报**」与「**订阅条件下发**」两种沟通方式。
> 本文设计第三种 —— **App 主动发起、要结果、按需调用**的能力（查询 / 执行），
> 并给出它该有的框架形状。
>
> **⚠️ 本文是「修正后的方案」**：初版提案（先建通用框架再落地用例）经评审后**调整了顺序**，
> 详见 §0。

---

## 0. 本文的由来与被修正之处

### 0.1 起因

用户提出：能否复用 Xposed 通道，拿到**快捷方式的完整 Intent**（现有 `dumpsys` 路径的
`dat` 被省略、extras 类型丢失，导致米家「无账号权限」等故障）。

进一步地，用户指出长期方向：

> 「我是希望从 xposed 通道下面**抽象出几个模块**。目前是只有触发器，
> 后面还会接借助 xposed 能力的模块，也就是步骤。」

**⇒ 这不是一个用例，是一类能力。**

### 0.2 初版提案（经评审**部分推翻**）

初版主张「先建通用能力框架（统一 `invoke` + 注册表），再实现快捷方式查询」。
评审后的修正：

| 初版主张 | 评审结论 | 状态 |
|---|---|---|
| 「③ 该与**事件侧**对称」 | ❌ **不对称**（`report` 是 oneway/可丢，③ 是同步/不可丢）。**该对称的是 `pushConditions`** | **已修正**（§2.2） |
| 「加第 N 个 = 1 文件 + 1 行注册」 | ❌ 是 **2 处注册**，且框架要担 3 件事 | **已修正**（§5.1） |
| 「先建框架，再落用例」 | ❌ **顺序反了**。本仓库成功的部分全是「先探针、再抽象」 | **已修正**（§7） |
| 「③ 是目的」 | ❌ **把手段当成了目的**。③ 是数据源，不是目的 | **已修正**（§6.3） |
| 「反射读 `ShortcutService`」 | ⚠️ **证据等级仅【推断】**，survey 从未验证 —— 但**待验的是「取法」，不是「路径」**：它属 survey §5.5 第 4 条（Xposed hook），「必须走 hook」已确证 | **已标注**（§6.2） |
| （未提及） | ✅ **新增**：hook 侧 `invoke` **绝不能占 binder 线程** | **已补**（§4.2） |

### 0.3 评审意外发现的两个既有缺陷

评审「扩展性」时，核对出**两处与本文需求无关、但会影响本文落地的预埋缺陷**
（`onConnected` 单槽位、`conditions` 单写者）。

**已记入 [`xposed-channel-design.md`](xposed-channel-design.md) §5.2「已知未修」** —— 不在此重复。

> ⚠️ **加第二个 hook 触发器之前必须先修那三处**（含协议层的包过滤不按 topic 分）。
> 本文的能力调用**不会**踩它们（③ 走独立入口，不进 `conditions`），
> 但若本文的用例被做成 hook 触发器，就会撞上。

---

## 1. 现状：通道现在有什么沟通方式

**先纠正一个流传的说法**：`xposed-executor-design.md` §1.2 把现有通道描述为
「**单向推送**的采集器」——**不准确**。

核实 AIDL（`app/src/main/aidl/.../IHookHost.aidl`、`IHookCallback.aidl`）：

```
IHookHost（App 提供，hook 层调）
  ├─ boolean registerCallback(IHookCallback)     ← 非 oneway，有返回
  └─ oneway void report(String envelope)         ← oneway，无返回

IHookCallback（hook 层实现，App 调）
  ├─ boolean pushConditions(String, String)      ← 非 oneway，有返回 ★
  └─ int ping()                                  ← 非 oneway，有返回
```

**`pushConditions` 本身就是一次同步往返**（App 发起 → hook 层处理 → 返回是否接受）。
所以「单向推送」只描述了 `report` **那一条流**，被升格成了整条通道的性质。

> 讽刺的是 `xposed-channel-design.md` §4.2.0 **自己写着**「通信必须是双向的」——
> **文档内部就不一致**。

### 1.1 正确的划分轴：三问

不要用「单向/双向」，用**三个正交问题**：

| 维度 | 事件上报 `report` | `pushConditions` | **③（本文）** |
|---|---|---|---|
| **谁发起** | hook 推 | App 拉 | **App 拉** |
| **是否要等结果** | 否（fire-and-forget） | **是** | **是** |
| **失败语义** | **可丢**（有界队列 + 计数） | **不可丢** | **不可丢** |

**这张表立刻显示两件事**：

1. **`pushConditions` 与 ③ 同格** —— 只在「生命周期」上不同（见 §2.1）。
   **它是 ③ 的第 0 个成员**，只是当年为它单独写了方法。
2. **③ 与 `report` 相反** —— 所以 `xposed-executor-design.md` §2.1
   「不复用 `EventEnvelope`」的判断是**对的**（理由它给对了：`seq`/`dropped` 语义不符）。

---

## 2. 三种生命周期（本文的核心划分）

**⚠️ 注意措辞**：初版说「三层」，**不准确**——它们是**三种生命周期**，
且是**嵌套/独立**关系，不是并列的层。

| | ① 连接 | ② 订阅 | ③ 调用 |
|---|---|---|---|
| **活多久** | 一次 `bindService` 连接 | 用户配了触发器的那段时间 | **一次调用** |
| **谁驱动** | `HookChannelService` 的 bind/unbind | 触发器的启用/禁用 | **调用方**（按需） |
| **份数** | 1 | 1（**全量**视图） | **N**（每次独立） |
| **现有产物** | `callback` / `token` / `lastSeq` / `_connected` / 重连退避 / 存活性巡检 / 解锁等待 | `eventSinks` / `conditions` / `pushConditions` | ❌ **不存在** |

### 2.1 三者关系

```
时间 ─────────────────────────────────────────────►
① 连接  [====== bindService 连接存活期间 ======]
             ↑ 断开重连 ⇒ 重置整段

② 订阅       [== 用户配了触发器期间 ==]
                ↑ 增删触发器时全量重下发
                ↑ 生命周期 ⊂ 连接（连不上就下发不了，但配置不随连接消失）

③ 调用             [=]     [=]     [=]
                   ↑ 每次独立，按需发起
```

- ② **依赖** ①（连不上下发不了，但 ② 的数据在 App 侧、不随 ① 消失）
- ③ **依赖** ①，但**与 ② 无关** —— **查询不该进 `conditions`**

### 2.2 为什么 ③ 不能与 ② 合并（**这条理由最关键**）

初版主张「分开」，但理由说错了（说的是「对称」）。**真正的理由是契约**：

> **②需要「全量替换 + 与连接同生命周期 + 连上必须重下发」三条保证，
> 而 `invoke` 的语义是「一次事务」——这三条它表达不了。**

证据：`HookRuntime.onConditions` 之所以要 `queue.clear()` + 分发给所有 source，
正是这三条的落实（`xposed/HookRuntime.kt:288-313`）。

**⇒ 不合并，但把 ③ 设计成「能容纳 `pushConditions` 的形状」**（§5.2）——**不合并但留门**。

### 2.3 为什么 ③ 该走统一入口

**不是因为「对称」，而是因为 AIDL 强类型在这条通道上是负资产**：

> **判据**：如果一个方法的参数/返回值**能用 AIDL 强类型表达，就用专用方法**；
> 如果它**必然退化成 `String`（JSON）**，那强类型本来就没有，统一入口**零成本**。

③ 的每个成员都落在后者：

- 「查快捷方式完整 Intent」—— `ShortcutInfo` **无法跨 binder 强类型化**，必然 JSON
- 「执行脚本」—— 脚本正文 + 结果字典，同为 String

**⇒ 写成 `String invoke(String requestJson, String token)` 没有丢失任何东西 ——
强类型在「写专用方法那一刻」就已经丢了。**

---

## 3. 架构位置

```
┌────────────────────────────────────────────────────────────┐
│  ① 连接（复用现有）                                          │
│     BinderTransport：连接/重连退避/存活性巡检/解锁等待        │
├────────────────────────────────────────────────────────────┤
│  ② 订阅（不动）                                              │
│     EventEnvelope / EventQueue / eventSinks / pushConditions │
├────────────────────────────────────────────────────────────┤
│  ③ 调用（新建）                                              │
│     ├─ 入口：invoke(requestJson, token): String              │
│     ├─ 框架：分派 / 超时 / 配对 / 错误 / 风险分级             │
│     └─ 成员：CapabilityHandler 注册表                        │
│            ├─ query_shortcut_intents   ← 首个用例（§6）      │
│            └─ execute_script           ← 见 executor 文档    │
└────────────────────────────────────────────────────────────┘
```

**加第 N 个能力的真实代价**（评审修正，非初版说的「1 文件 + 1 行」）：

| # | 要动 | 说明 |
|---|---|---|
| 1 | hook 层 `CapabilityHandler` **+1 文件** | 实现类 |
| 2 | hook 层注册 **+1 处** | `BinderTransport` 的 `IHookCallback.Stub`（`:168-182`） |
| 3 | **App 侧 capability 注册表 +1 处** | 见 §5.3，**不能省** |
| 4 | AIDL | ❌ **不动** |
| 5 | Transport / 订阅层 | ❌ **不动** |

---

## 4. 框架层必须担的三件事（**不能让每个 handler 各写一遍**）

### 4.1 配对表有界 + 过期清理

每个 in-flight 请求一条 `requestId → waiter`。

- **必须有界** —— 若 hook 侧卡死而 App 侧超时放弃，waiter 不清理就是**泄漏 + 错配**
- 这是 `EventQueue` 的同一条教训（`FORK.md` 记着 `LogcatStreamWrapper` 因无断流检测
  留下的同类残留）

### 4.2 ⚠️⚠️ hook 侧 `invoke` **绝不能占用 binder 线程**（初版完全没提）

`BinderTransport` 的 `IHookCallback.Stub`（`:168`）跑在 **system_server 的 binder 线程池**上，
**且与系统自己的 binder 调用共享该池**。

「查全量 ShortcutInfo」可能几百 ms 到秒级。**并发几次就阻塞**
—— 而阻塞的后果是**整机**（§5.1 的崩溃半径）。

**硬约束**：

```
binder 线程：只做「接单 + 登记 waiter」，立刻返回（非阻塞）
工作线程：  实际执行 handler
等待：      有界 + 超时
```

> **这是第 3~5 个能力时最先塌的点**，比 AIDL 形态问题更早、更致命。

### 4.3 未知 capability **必须显式报错**（与事件侧方向相反）

⚠️ **注意差异**：

| | 未知 topic | 未知 capability |
|---|---|---|
| 处理 | ✅ **忽略**（`HookChannelController.kt:287-290`，安全） | ❌ **必须报错** |
| 理由 | 事件流多一条少一条无所谓 | 调用方**在等结果**，超时会把排查引向错误方向（用户会往 hook 点/系统版本上找） |

**协议版本的机制只解决「能不能解析」，不解决「这个 hook 层会不会 `query_shortcuts`」。**

> ⚠️ **口径订正（2026-09-28 代码复核）**：本文多处引用「未知版本**必须拒绝**而非崩溃」（引自通道架构文档 §3.4.5），
> 但**代码的实际行为是「不拒绝、只 warn」**（`HookChannelController.kt:273-281`）：
>
> ```kotlin
> if (envelope.protocolVersion != EventEnvelopeCodec.PROTOCOL_VERSION) {
>     // 不拒绝：payload「只加不改不删」，版本不同仍可能能处理。
>     DebugLogger.w(TAG, "协议版本不一致：...")
> }
> ```
>
> 之后**照常继续**：丢包检测 → 丢弃计数 → 按 topic 分派。
> ⇒ 通道架构文档那句的落实是「**不崩溃，也不拒绝**」，**不是「拒绝」**。
>
> **对 ③ 的影响**：③ 的请求/响应信封若沿用同一口径，则「未知 capability」与
> 「版本不符」是**两件不同的事** —— 前者照本节必须报错，后者可以只 warn 继续。
> 两者不要混为一谈（本节初版把它们并提，容易让人以为版本不符也会被拒）。

---

## 5. 协议与注册

### 5.1 入口

```aidl
// IHookCallback 新增（与 pushConditions / ping 并列）
String invoke(String requestJson, String token);
```

**必须同步**（要等结果）—— 与 `report` 的 oneway 相反，但**已有先例**：
`pushConditions` 就是同步的，其注释写明理由

> 「**刻意不用 `oneway`**：下发的成败必须能被 App 感知 ——「下不去」意味着触发器不会工作，
> 而用户只会看到「没反应」。」

同一条纪律适用：**「执行/查询失败」必须能被感知。**

⚠️ **调用方约束**（沿用 `pushConditions` 的）：**不得在主线程调用**。
（模块侧天然满足：`WorkflowExecutor` 的 `executorScope = Dispatchers.Default`，
`core/execution/WorkflowExecutor.kt:52`。）

### 5.2 请求 / 响应信封

```json
// 请求
{ "request_id": "uuid", "protocol_version": 1, "capability": "query_shortcut_intents",
  "params": { }, "timeout_ms": 5000 }

// 响应
{ "request_id": "uuid", "ok": true, "result": { }, "error": null, "elapsed_ms": 12 }
```

**设计要点**：

- `request_id` 用于配对（**不用 `seq`** —— 那是事件侧的丢包检测语义）
- `ok=false` **必须带 `error`**，绝不静默
- **不复用 `EventEnvelope`**（`seq`/`dropped` 语义不符，executor 文档判断正确）
- **信封形状与 executor 文档 §3.1/§3.2 保持兼容** —— 两者是同类成员，
  差别只在 `capability` 名（`query_*` vs `execute_script`）与是否有 `script` 字段

#### ⚠️ 事实订正（2026-09-28 代码复核）

本节初版曾把 `token` 写进请求信封。**但现状并非如此**，核实如下：

| | 现状事实 |
|---|---|
| App 侧生成 | `HookChannelController.kt:129` `token = newToken()`（`SecureRandom`，`:369-373`） |
| 下发方向 | **经下行** `pushConditions(conditionsJson, token)`（`:209`）交给 hook 层 |
| hook 层角色 | **回抄者** —— 存进内存（`HookRuntime.kt:288-289`），放进每条上行信封 |
| 上行校验 | App 侧（`HookChannelController.kt:240-250`）：①本侧无 token 则丢弃 ②恒定时间比较 |

⇒ **token 是连接级凭据，现状由 ① 层（连接）负责，不是 ③ 的请求级字段。**
本节初版的信封示例把「future design」写成了既成事实，已改为与现状一致的描述。

> ⚠️ **若要把它改成「注册时由调用方提供」，需先注意一处风险**：
> `registerCallback` 是 App 侧实现、hook 层调用的方法 —— token 若挪到这里就
> **必须由 hook 层提供**，凭据变成调用方自选，而身份判据已被本仓库标为不可靠
> （`HookChannelService.kt:41-42`）。**这是设计取舍，留待架构文档决定，本文不预设。**

> **推论（供架构文档参考）**：鉴权属 ① 层职责，③ 只管业务 ——
> 这与 §2.1「三种生命周期」的分层一致。

### 5.3 App 侧 capability 注册表（**新增，不能省**）

风险分级**必须落在 App 侧的注册表上，hook 层不管分级** ——
这是「**Hook 层不知道工作流的存在**」（§3.2 硬约束）在 ③ 上的延伸。

核对了模块的权限机制：它是按 step 参数**动态**算的
（`LaunchShortcutModule.kt:62-71` 的 `getRequiredPermissions(step)` 按 `mode` 分支）。

30 个能力里必然混着「**读数据**」（快捷方式）与「**改系统**」（关 Activity、注入输入）。
**若走「一类能力一个专用 AIDL 方法」，分级会散在两端的 30 个方法里、审计不了。**

注册表内容建议：

```
capability 名 → 风险等级 / 所需权限 / 参数 schema / 结果 → vFlow 语义的映射
```

### 5.4 ⭐ `ping()` 升级为 capability 自描述（**建议加**）

> ⚠️ **本节初版的「成本极低、收益明确」判断已被推翻（2026-09-28 代码复核）**：
> **`ping()` 当前零调用者** —— 全仓仅两处提及，都不是调用：
>
> | 位置 | 性质 |
> |---|---|
> | `BinderTransport.kt:181` | hook 层**实现** |
> | `HookRuntime.kt:399` | 仅 doc 注释 |
>
> App 侧的存活性判定走的是 **`binder.isBinderAlive`**（`BinderTransport.kt:151-153`
> 的 `startLivenessWatchdog`），**不依赖 `ping()`**。
>
> ⇒ 这不是「改造现用接口」，而是「**给一个从未被调用的方法定用途**」。
> 且 `IHookCallback.aidl:25-34` 的注释写着「App 侧**周期性调用**」，**承诺了一个不存在的机制** ——
> 会误导后续实现者以为活性问题已被覆盖。

**因此升级前必须先回答一个更前置的问题：谁、在什么时候调它？**

**在用途未定之前，「升级它」不构成一个可排期的改造项。**

> **收益的逻辑仍然成立**：App 侧能在**调用前**判「这个 hook 层会不会 `query_shortcuts`」，
> 而不是**等超时**再猜。但它取决于用途选择，不再是「成本极低、随手可加」。

### 5.5 版本号收敛（**现在就做，别等 ③ 加进来**）

> ⚠️ **事实订正（2026-09-28 代码复核）**：本节初版说「常量 1 **放在两处**」—— **不准确**。
>
> | | 实际情况 |
> |---|---|
> | **常量定义** | **只有一处**：`EventEnvelope.kt:98`（`const val PROTOCOL_VERSION = 1`） |
> | 引用点 | 7 处（含测试）：编码默认值 `:56`、`ping()` 返回值 `BinderTransport.kt:181`、收端比较 `HookChannelController.kt:274/279`、`HookRuntime.kt:400`、2 处测试 |
> | 其中一处是死代码 | `HookRuntime.currentProtocolVersion()`（`:400`）**无任何调用者** |
>
> ⇒ 真正的形态是「**一处常量 + 两个对外暴露点**」，**不是「两处定义」**。
> 漂移风险比初版描述的**小**（不存在两个常量各写一个值的情况），
> 但「暴露点会随 ③ 增加」这半句**成立** —— 加 ③ 后信封形态若不共用，暴露点会变多。

加了 ③ 之后，版本会出现在**更多暴露点**（③ 的请求/响应信封也要版本）。
**暴露点会漂移，而漂移的表现是「某条链路的行为莫名其妙」**（§3.4.5 已点过此坑）。

⇒ **收敛成一处常量 + 一次连接期交换。**

> ⚠️ 注意 §5.4 与本节涉及**同一个暴露点**（`ping()` 的返回值），
> 两者不应分别排期。

---

## 6. 首个用例：快捷方式完整 Intent

### 6.1 问题（已实测确证）

`docs/fork/surveys/shortcut-system-overview.md` 的量化：

| 类别 | 条数 | 占比 | 现有 dumpsys 路径 |
|---|---|---|---|
| 有 `cmp`、无数字 extras | 263 | 64.5% | ✅ 可用 |
| 有 `cmp`、有数字 extras | 44 | 10.8% | ⚠️ 值完整、**类型可能猜错** |
| 靠 dat、dat 被省略 | 74 | 18.1% | ❌ 退化/报错 |
| 无 `cmp` 无 dat | 23 | 5.6% | ❌ 先天无解 |

**已穷尽 7 条出口全堵**（含 `LauncherApps.getShortcuts()` 的 Intent 恒为 null、
`dumpsys --proto` 的 `secure=true` 硬编码）。**只有拿到 `ShortcutInfo` 对象才能保真。**

### 6.2 实现方式：反射读 `ShortcutService` 内部对象 ⚠️【推断】

> **⚠️ 先纠正一处框定错误（2026-09-28）**：本节初版把这条写成
> 「survey §5.5 的**四条之外**，本文**新增第 5 条**路径」——**这是错的**。
>
> 它**不是第 5 条路径**，而是 **survey §5.5 第 4 条（Xposed hook）的一种实现方式**。
> survey §4.2 早已把结论说死：「**要修那 29%，只有加通道或换身份**」，
> 而「路径本身还能不能多一条」并不开放。本节的待验项是**实现细节**，不是路径存在性。

**「必须走 hook」本身已确证，且我这轮补上了源码依据：**

`ShortcutService.getShortcuts` 的**唯一**检查是 `verifyCaller(packageName, userId)`：

```java
private void verifyCaller(String packageName, @UserIdInt int userId) {
    Preconditions.checkStringNotEmpty(packageName, "packageName");
    if (isCallerSystem()) {
        return; // no check                    ← 唯一豁免：system uid
    }
    final int callingUid = injectBinderCallingUid();
    if (UserHandle.getUserId(callingUid) != userId)             throw new SecurityException("Invalid userId");
    if (injectGetPackageUid(packageName, userId) != callingUid) throw new SecurityException("Calling package name mismatch");
    ...
}
```

- `isCallerSystem()` = `UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)`
- ⇒ **shell(uid 2000) 调 `getShortcuts("com.xiaomi.mihome", …)` 必抛 `SecurityException`**
  （`getPackageUid(米家) != 2000`）
- ⇒ 穷举 `IShortcutService.aidl` **全部 24 个方法**：**无一能跨包读出 `ShortcutInfo` 对象**
  （跨包的 `getShareTargets` / `hasShareTargets` / `isSharingShortcut` 另加
  `MANAGE_APP_PREDICTIONS`，signature 级）

**⇒ 「不经 Xposed、以 shell 身份直接 binder 取」这条路在源码层已否证**，
不再需要真机验证。这与 survey §4.2「不存在『换一个更好的源』这种解法」**一致**。

⚠️ 注意由此产生的**一个反直觉点**：`cmd shortcut get-shortcuts <pkg>` 能工作，
**不是因为 shell 有权限** —— 它是 `ShortcutManagerShellCommand`，跑在 **system_server（uid 1000）**
里，binder 调用是 system_server 做的，`verifyCaller` 走 `isCallerSystem()` 放行。
**shell 只是发起方。** 这也解释了为什么它的输出与 `dumpsys` 逐字一致（都走 `toDumpString`）。

#### 待验证的实现细节（本文档中证据等级最低的环节）

```
LocalServices.getService(ShortcutService) → ShortcutUser → 各 ShortcutPackage
    → 各 ShortcutInfo.getIntents()      ← 完整 Intent，类型完好
```

按 §7 决策 21「结论必须标注证据类型」，以下均为【推断】：

- `LocalServices.getService(ShortcutService)` 在 hook 层（system_server 内）可取性
- **hook 层调用时 `injectBinderCallingUid()` 返回什么**（★ 唯一必须真机验的项）
- 内部字段名（`mShortcutUsers` / `mUsers` 之类）随版本漂移
- `getIntents()` 在 system_server 内的稳定性
- `ShortcutPackage` 是否已按当前用户过滤

#### 与同路径下另一种实现方式的对比

**两条都是「Xposed hook」这一路径的实现方式，不是两条路径**：

| 实现方式（均属 §5.5 第 4 条） | 救 dat 残缺（74） | 救 extras 类型（44） | 前置条件 |
|---|---|---|---|
| hook `requestPinItem`（ShortX「固定的快捷方式」） | ✅ | ✅ | **仅 pin 之后新增**，需用户重 pin |
| **反射读 `ShortcutService` 内部对象** | ✅ | ✅ | **全量 + 含历史 pin**（唯一不需要用户重 pin 的） |

**⇒ 它优于 `requestPinItem`（覆盖更全、不要求用户重 pin），但证据等级最低。
这正是 §7「先探针」的理由。**

> **另一条已否决的路径**（供对照，见 survey §5.5）：
> `ACTION_CREATE_SHORTCUT` ✅类型完整、零权限，但**目标 App 须响应**（实测仅 28 个，
> 且与 dat 残缺那批几乎不相交，净收益 ≈ 0，已被 survey §7.2 否决）。

### 6.3 ⚠️ 落点：**不是新模块，是给选择器换数据源**

**评审最想让本文修正的认知**：

> **③ 是数据源，不是目的。**

需求 A 的痛点在**用户选快捷方式的那一刻**，不是执行那一步：

- `LaunchShortcutModule.execute()`（`core/workflow/module/system/LaunchShortcutModule.kt:152`）
  执行的是**已经存好的** `launchCommand` **字符串**，它是 `isHidden` 输入
  ⇒ **执行时回天无力**
- 所以 **③ 的正确产出是「给 `ShortcutPickerSupport.buildLaunchCommand` 与
  `UnifiedShortcutPickerSheet` 换一个有损 → 无损的数据源」**

这与 survey §6 的改动落点表一致（`:633`「修 dat 残缺 → **必须换数据源**」）。

**且不新模块的三条依据**（均在仓库既有文档里）：

1. survey §7.1 **就是拿这条否决了「新建快捷方式模块」**（`shortcut-system-overview.md:648`）
   —— 理由是「会让 AI 面板出现两个近同名工具」（模块可发现性是 `ai-system-overview.md`
   记的既知痛点）
2. 仓库原则「控制 diff 面积」—— 30 个能力 = 30 个模块 + 30 处注册 + 30×3 语言文案
3. **存量工作流问题模块解决不了**：survey §7.1 原话「存量工作流里的旧步骤依然坏」，
   而「**改在选择器层，所有入口一次全好**」

### 6.4 建议的落点分层（若将来能力变多）

| 层 | 谁 | 职责 |
|---|---|---|
| ③ 框架 | hook 层 + `HookChannelController` | capability 分派 / 超时 / 配对 / 错误 |
| **App 侧 capability 注册表** | 新文件 | 名 → 风险等级 / 权限 / schema / 结果映射 |
| **少数通用模块** | `XposedCapabilityModule`（**一个**） | 选 capability + 填参数；schema 由注册表动态提供（与 `DefineFunctionModule` 的动态参数机制同源） |
| **够格的能力** | 单独做模块 | 走完整规范，**逐个评估**，不是默认 |

**⇒ 「一个能力一个模块」是错的方向。**

---

## 7. 实施顺序（**经评审调整**）

### 7.1 为什么不「先建框架」

本仓库**所有成功的部分都是「先探针，再抽象」**：

```
P0（只打日志） → P1a（打包加载） → P1b（心跳） → P3（首个触发器） → P4（状态/UI）
```

而 ③ 是**纯抽象**——**运行时风险为零、验证价值也为零**。
且**只有一个成员时，抽象的错误边界无法被暴露**（没有第二个成员来证伪它）。

### 7.2 建议顺序

| 阶段 | 内容 | 为什么 |
|---|---|---|
| **P-1** | **先修那三处预埋缺陷** | 与本文无关，但「加第二个 hook 触发器就炸」（架构文档 §5.2） |
| **P0** | **用一个专用同步方法**验证 §6.2 的未知 | 那是**唯一的真未知**，且不依赖任何框架 |
| **P1** | 跑通后，手上有**两个真实成员**（`pushConditions` + `query`） | 此时**从真实需求反推 ③ 的形状** |
| **P2** | ③ 框架：配对表 + 超时 + 工作线程 + 未知 capability 报错 | 由两个成员的实际需要驱动 |
| **P3** | App 侧 capability 注册表 + 风险分级 | §5.3 |
| **P4** | `ping()` 升级为 capability 自描述 | §5.4 |

**代价**：将来若不加第二个能力，那个专用方法就是最终形态 ——
**那也没错**（只有一个成员时，专用方法本来就是对的）。

### 7.3 P0 的最小验证

**目标**：验掉 §6.2 的四个【推断】项。**不接通信、不建框架。**

```
在 hook 层加一个临时探针：
  · LocalServices.getService(ShortcutService) 能否取到
  · 遍历出 ShortcutInfo 的数量（与 dumpsys 的 408 条对比）
  · 取一条（米家）的 getIntents()，打印 dat 是否完整、extras 的 javaClass
```

**这是唯一能证伪 §6.2 的实验，且失败代价最小。**

---

## 8. 未决项

| # | 项 | 证据等级 | 怎么验 |
|---|---|---|---|
| 1 | 反射读 `ShortcutService` 的**取法**（§6.2 五项；★ 其中「hook 调用时的 `injectBinderCallingUid()`」是唯一必须真机验的） | 【推断】 | P0（§7.3） |
| 2 | hook 侧工作线程模型（专用线程池 vs binder 线程 + 转派） | 【推断】 | P2 |
| 3 | App 侧 binder 同步调用的超时手段 | 【推断】 | 见下 |
| 4 | `ping()` 返回 capability 清单的协议形态 | 设计 | P4 |

> ✅ **已结案（2026-09-28）：「不经 Xposed、以 shell 身份直连 `IShortcutService`」这条路。**
> 源码层否证 —— `verifyCaller` 的唯一豁免是 `isCallerSystem()`（uid 1000），
> 且 AIDL 全部 24 个方法无一能跨包读出 `ShortcutInfo` 对象。详见 §6.2。
> **「必须走 hook」不在待验之列**，不要再把它当未决项重新引入。

> ⚠️ **#3 是 executor 文档也存在的缺口**：binder 同步调用**没有超时参数**。
> 只能靠「另起 watchdog 线程 + App 侧放弃等待」或「`oneway` + 回调配对」。
> **必须明确选哪个**，否则实现时会发现「这条做不到」。它在第 3 个能力时就会咬人。

---

## 9. 与相邻文档的关系

| 文档 | 关系 |
|---|---|
| `xposed-channel-design.md` | **上位**。本文的 ① 层与 ② 层完全复用其设计；§5.2「已知未修」列了必须先修的三处 |
| `xposed-executor-design.md` | **同类成员**。`execute_script` 是 ③ 的另一成员。⚠️ 它 §1.2 的「单向推送 vs 请求-响应」划分轴**不准确**（本文 §1），但**工程要点（不复用 `EventEnvelope`、必须同步、必须有超时、必须结构化报错）都站得住** |
| `surveys/shortcut-system-overview.md` | **首个用例的背景**。量化、7 条出口、改动落点均引自该文 |
| `surveys/trigger-system-overview.md` | 若本文用例被做成 hook 触发器，须遵循其 §9.1 清单 |
| `FORK.md` | 落地后每处改动都要登记（尤其 AIDL / Manifest / proguard） |

---

## 10. 一句话总结

> **通道缺的不是「一种新通道」，而是「一种新生命周期」** ——
> 「App 主动发起、要结果、一次性」的能力调用。
>
> **它该走统一入口**（因为 AIDL 强类型在这里本就是负资产），
> **但与 `pushConditions` 不能合并**（后者需要「全量替换 + 与连接同生命周期」的契约，
> `invoke` 表达不了）。
>
> **框架必须担三件事**：配对表有界、**hook 侧不占 binder 线程**、未知 capability 显式报错。
>
> **⚠️ 别先建框架** —— 先用一个专用方法验掉快捷方式那条路径的**实现细节**
> （取法与字段名；**「必须走 hook」已确证，不在待验之列**，见 §6.2），
> 再从两个真实成员反推 ③ 的形状（本仓库的历史证明了这个顺序）。
>
> **⚠️ ③ 是数据源，不是目的** —— 快捷方式的正确落点是**给选择器换数据源**，
> 不是新增模块。
