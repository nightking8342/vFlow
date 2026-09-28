# Xposed 通道架构设计 V2.0

> ## 🚧 草稿 · 架构已定稿，实现级契约已补齐 **但部分必须先验证**
>
> **状态**：S1–S7 **全部完成**（2026-09-28）+ 一轮自复核（S7 补）+ **两轮独立源码评审**（2026-09-28）
> **创建日期**：2026-09-28
> **性质**：fork 独有文档 → 冲突归**我方**（上游无此文件）
>
> ⚠️ **本文仍是草稿，两个理由**：
> 1. ③（能力调用）**尚无一行实现**，其契约（配对表 / 结果大小 / 池满行为 / 中断）**未经真实代码验证**；
> 2. ⚠️ **§10 还有 12 项未决**（8–20），其中 3 项标 ⭐ 的**不回答就不能动手**。
>
> ✅ **§8.2 的三项「先验再改」已于 2026-09-29 全部真机验证完毕** ——
> 结论：**libxposed 重复 `hook()` 是链式叠加**（N 个 hook ⇒ N 倍回调）／
> **uid 2000 被 `signature` 权限拦住**（文档原写的机制是错的）／
> **`ServiceConnection` 回调在 system_server 主线程**。见 §8.2。
>
> **② 订阅侧（P1–P4）已实现并真机验证** —— 但评审 + 实测发现**已实现部分本身有 20 条缺陷**（§8）。
> ✅ **其中 8 条已修复并验证**（2026-09-29）：**19**（hook 每热更新 +1、永不回收 —— 实测已到 N=6）／
> **20**（重连重复 `bindService` + `ConnectionRecord` 只增不减）／
> 以及 §8.3 判定的 **A 组四条 1 / 3 / 12 / 13**（③ 开发的前置）与 **18**（空断言）。
> **其余 12 条待排期**（§8.1 已给每条的最小改法，§8.3 给了优先级）。
>
> 凡标 `【未验证】` / `【推断】` 的结论都是**推断**，不是实测 —— 见 §10 与各节注。

---

## 0. 本文的定位

### 0.1 与现有文档的关系（**边界定案 S7**）

| 文档 | 关系 | 已加头部声明 |
|---|---|---|
| `xposed-channel-design.md`（2492 行，v3.0） | **史料 + 实测记录** —— 架构以本文为准，**实测与归因教训以它为准** | ✅ |
| `xposed-executor-design.md` | ⚠️ **框架已被本文取代**：`execute_script` 不是「第二条通道」，是 ③ 的一个 capability | ✅ |
| `xposed-capability-invocation-design.md` | ⚠️ **框架已被本文取代**：三种生命周期是本文 §2 的子集 | ✅ |
| `xposed-channel-p4-design.md` | P4 落地设计与实现状态。**保留不动**（无冲突） | — |
| `surveys/shortcut-system-overview.md` | 首个用例的背景（数据源需求）。**不动** | — |

#### 三份被取代的文档，为什么**保留而不删**

> **它们的价值不在架构，在实测过程与归因教训** ——
> 那些「连错两次、方向还相反」的记录（§5.1.1）、探针数据、踩过的坑，
> **是不可复现的资产。删掉就没了。**

**每份文档头部已加声明**，明确「哪部分以 V2.0 为准、哪部分以它为准」——
避免读者在两处描述不同的架构之间困惑。

⚠️ **不逐节标注**（「本文取代它的哪一节」）—— 那需要通读 2492 行，成本远高于收益。
头部声明 + 本节的关系表已足够定位。

#### 为什么要开 V2.0

现有 `xposed-channel-design.md` 混了三种东西（**架构决策 / 实测记录 / 实现笔记**），2492 行。
本文只做**架构总纲** —— 结构、契约、扩展规范、边界，**不含实测过程**。

### 0.2 阅读顺序

- **要加一个新触发器** → §7.2（10 处改动）+ §7.4 反模式清单
- **要加一个新能力（③ capability）** → §7.2b（8 处改动）+ §6.2 降级声明 + §3.6 大小契约
- **要改协议 / 连接** → §3 协议 + §4 连接与状态
- **要理解某条为什么这么定** → 各节的「决策理由」；**踩过的坑** → §7.4 / §8

---

## 1. 需求面（S1）

### 1.1 三个消费者

| 消费者 | 方向 | 频率 | 失败的表现 |
|---|---|---|---|
| **触发器**（事件源） | hook → App **推** | ⚠️ **分档**（见 §5） | **静默**（用户不知道） |
| **step 模块** | App → hook **拉** | 低，按需 | 显式（步骤报错） |
| **JS 脚本** | App → hook **拉** | 低，按需 | 显式 |

**共性** ⇒ 骨架的四条：一条连接、一个协议、一套状态与降级、一套扩展规范。
**冲突** ⇒ 划分轴的来源：触发器要「持状态」，模块/脚本「无状态」。

### 1.2 需求面暴露的硬约束

| # | 约束 | 来源 | 证据等级 |
|---|---|---|---|
| 1 | **漏采是静默的** ⇒ hook 层过滤必须**比 App 侧宽松** | 仓库反复记录的失效形态 | 【实测】真机验证过 |
| 2 | **输入/手势类事件频率会跳变**（每秒数十次且连续）⇒ 现有「有界丢弃」模型不成立 | ShortX 手势类触发器对照 | ⚠️ **【推断】** —— 见下 |
| 3 | **执行失败必须可感知** | `pushConditions` 的既有纪律 | 【源码】 |
| 4 | **不能占 system_server 的 binder 线程池** | 后果是整机 | 【推断】但风险量级明确 |
| 5 | **binder 同步调用没有超时参数** | 需自建 | 【源码】 |

> ⚠️ **约束 2 是【推断】，不是实测**（2026-09-28 评审指出）。
> 它的依据只有「ShortX 有手势类触发器」这一条**外部对照** —— 本仓库**没有**任何高频 hook 源，
> 也没有测过「每秒数十次 Activity/输入事件下现有队列的表现」。
> 把它列进「硬约束」表会让读者以为它已被证实。**在第一个高频源真正落地前，它是设计假设。**
>
> ⚠️ **约束 4 的完整形态见 §5.1** —— 不只是「binder 线程池」，
> 还包括**连接建立路径会落在 system_server 主线程上**（S7 评审补，§5.1）。

---

## 2. 抽象出的类型（S1 + S2）

### 2.1 三组类型

| 组 | 取值 | 性质 |
|---|---|---|
| **生命周期** | 连接 / 订阅 / 调用 | 通道内部 |
| **执行环境** | App 进程 / system_server / *（预留）宿主 App 进程* | **开放维度** |
| **暴露面** | 触发器 / step 模块 / 数据源 | 对消费者 |

**区分原则**：**能力调用是基础设施，不是暴露面。**
判据：**用户能否在界面上看见** —— 能 ⇒ 暴露面；不能 ⇒ 基础设施。

> 📌 **编号约定（S7 补，全文适用）**：本文的 **①②③ 一律指「生命周期」**
> —— `① 连接` / `② 订阅` / `③ 调用`。
>
> ⚠️ §1.1 另有一组「三个消费者」（**触发器 / step 模块 / JS 脚本**），
> 那是**暴露面**维度，**不是**生命周期。初版曾在 §2.2 的订正框里写成
> 「② 触发器」「③ step 模块」，**把两套编号混在一句里** ——
> 下文一律用中文名指消费者，不再复用数字。

### 2.2 划分轴（S2 的核心产出）

> ⚠️ **一处对 S1 结论的订正（S5 阶段发现，记在这里避免下游继续引用错的）**
>
> S1 曾写「**调用必须有降级路径，订阅没有 —— 这是结构性差异**」。**方向反了**：
>
> | | 失败时的可见性 |
> |---|---|
> | **触发器**（② 订阅的消费者） | ⚠️ **天然静默** —— 用户配了「打开 X 就做 Y」，Y 没发生，**没有任何信号** |
> | **step 模块**（③ 调用的消费者） | ✅ **天然可见** —— 步骤失败会走进度/错误弹窗、执行通知变 `Failed` |
>
> ⇒ **调用侧的失败本来就更可见，不需要为它专门设计降级。**
> 反倒订阅侧需要补丁 —— 仓库的现有补丁是 `TriggerService` 在缺权限时
> **禁用整个工作流**（`isEnabled = false`），那是「**让静默失败变可见**」的机制，**不是降级机制**。
>
> **正确的降级判据见 §6.2**：它取决于**能力有没有替代实现**，与生命周期无关。

**三组各有其轴，不强行统一。**

#### 轴一：生命周期 —— **状态归属**

| | ① 连接 | ② 订阅 | ③ 调用 |
|---|---|---|---|
| **hook 层持有什么** | 连接自身状态 | **App 侧状态的镜像** | **什么都不持有** |
| **谁是权威** | — | **App 侧** | — |
| **判据** | — | 「hook 层需要持有一份 App 侧权威状态的镜像」 | 「无状态、一次性」 |

**这个轴把 ② 的三条保证还原成一个根因**：

```
存在镜像（hook 层持有 App 侧状态的副本）
    ↓
① 权威在 App 侧 ⇒ 变更必须能覆盖      ⇒ 全量替换
② 镜像的生命周期由连接决定            ⇒ 与连接同生命周期
③ 连接重建 ⇒ 镜像丢失                ⇒ 连上必须重下发
```

⇒ 三条保证**不是设计选择，是「有镜像」的必然推论**。反过来也解释了 ③ 为什么不需要它们。

#### 轴二：③ 的**第二判据**

> **③ 只做 ② 拿不到的东西**（完整 Intent、system_server 内部对象、UID 1000 专属权限）。

**理由**：像「当前前台是哪个 App」这类需求，状态归属上判为 ③，
但 ② 的事件流**已经在推**这件事 ⇒ 做成 ③ 等于每次进一次 system_server 问一个已知答案。

这条**不是状态归属的推论**，是「效率 + 崩溃半径」的推论。**两条判据都要过。**

#### 轴三：执行环境

三问：**需要 UID 1000 权限吗？需要 system_server 内部对象吗？需要 `vflow.*` 编排吗？**

#### 轴四：暴露面

判据是「用户能看见吗」+「给谁用」：

| 给谁用 | 归哪 |
|---|---|
| 工作流步骤 | **模块** |
| 事件源 | **触发器** |
| 编辑器取数据 | **数据源** |

---

## 3. 协议（S3）

### 3.1 AIDL 现状（代码事实，已核实）

```aidl
IHookHost（App 提供，hook 层调）
  ├─ boolean registerCallback(IHookCallback)     ← 非 oneway
  └─ oneway void report(String envelopeJson)     ← oneway

IHookCallback（hook 层实现，App 调）
  ├─ boolean pushConditions(String, String)      ← 非 oneway
  └─ int ping()                                  ← 非 oneway
        ⚠️ 返回类型与 §6.3 冲突，见下
```

**⚠️ 两个接口都没有显式的协议版本字段**；版本只在 `ping()` 返回值和事件信封里。

#### ⚠️⚠️ `ping()` 的契约**自相矛盾**（2026-09-28 评审发现）

| 位置 | 它说 `ping()` 干什么 |
|---|---|
| 本节 + §3.2 `:196` | `int ping()` —— 返回**协议版本号**（「← ① 连接（不动）」） |
| §6.3 `:829` | 「连接建立时（**ping 返回能力清单**）→ 填入 `CapabilityPresence`」 |
| §7.2b 的 8 处改动 | ⚠️ **没有一行是「改 AIDL」** |

而 `CapabilityPresence` 是 §6.5「旧版 hook 层没有某 capability ⇒ oneway 静默丢弃」
的**唯一防线**（文档自己写「是唯一的『调用前信号』」）。

⇒ 实现者只有两条路：**把清单硬塞进 `int`**（不可能），或**自行改签名**
（则 §7.2b 的「8 处」少算一处，且 **AIDL 变更没有版本协商机制**，两端必须同时升级）。

**最终签名待定 —— 见 §10 未决项 8**。在此之前，§6.3 那句「ping 返回能力清单」应读作
「**连接期做一次能力交换**」，而**不预设它由 `ping()` 承载**。

### 3.2 统一入口的**边界** = §2.2 的轴

| | ② 订阅 | ③ 调用 |
|---|---|---|
| 持有镜像？ | **是** | 否 |
| ⇒ 入口 | **专用方法**（契约要能被签名表达） | **统一入口** |

**⇒ `pushConditions` 保留专用方法，③ 走统一入口。**

理由不是「对称」，是**契约**：② 需要「全量替换 + 与连接同生命周期 + 连上必须重下发」，
`invoke` 表达不了。**不必让 `invoke` 去容纳它** —— 强行容纳只会让它背上表达不了的语义。

```aidl
IHookCallback
  ├─ boolean pushConditions(json, token)    ← ② 专用（不动）
  ├─ int ping()                             ← ① 连接（不动）
  └─ String invoke(requestJson)             ← ③ 统一入口（新）
```

**③ 该走统一入口的依据**：AIDL 强类型在这条通道上是**负资产** ——
`ShortcutInfo` 无法跨 binder 强类型化，必然退化为 JSON ⇒ 强类型在「写专用方法那一刻」就已丢了。

### 3.3 信封

```json
// 请求（App → hook 层）
{ "request_id": "uuid", "protocol_version": 1, "capability": "query_shortcut_intents",
  "params": { }, "timeout_ms": 5000, "token": "…" }

// 响应（hook 层 → App）
{ "request_id": "uuid", "ok": true, "result": { }, "error": null, "elapsed_ms": 12, "token": "…" }
```

| 决定 | 理由 |
|---|---|
| `request_id` 配对，**不用 `seq`** | `seq` 是事件侧的丢包检测语义 |
| `ok=false` **必须带 `error`** | 绝不静默 |
| **不复用 `EventEnvelope`** | 含 `seq`/`dropped`，与请求-响应语义相反 |
| **两封信封都带 `token`** | ⚠️ **已定案（S7 补）** —— 见下 |

#### ⭐ 为什么**响应**信封也必须带 `token`（一处必须补的鉴权）

> ⚠️ 本节初版写「信封里无 token（待定）」，**只考虑了请求方向**。S7 复核时发现漏了一半。

`report`（事件上行）靠信封里的 token 校验真伪（`HookChannelController.onReport`，
含「本侧无 token 直接拒绝」的前置闸 + 恒定时间比较）。
而 `resolve` 走的是**同一个 App 侧 binder**（`IHookHost`）——

**若响应信封不带 token，`resolve` 就完全没有凭证** ⇒
任何能 bind 到 `HookChannelService` 的进程都能伪造响应，
把任意 `result` 塞给正在等待的调用方。

这比伪造事件更危险：事件还要过 App 侧 filter 才触发，而 **`resolve` 直接就是 capability 的返回值**
（将来 `query_shortcut_intents` 的结果会被写进工作流）。

⚠️ **不能靠 `android:permission="HOOK_CONTROL"` 兜底** —— 它是 `signature` 级，只挡**绑定**、
不挡**绑定之后的方法调用**；而绑定本身的可信度至今未定论（`HookChannelService` 类注释）。
一旦绑定成功，`resolve` 与 `report` 同等可信 —— 所以两者必须**同规格鉴权**。

⇒ **响应侧照搬 `onReport` 的三段**：① 本侧无 token ⇒ 直接拒绝；② 恒定时间比较；③ 失败只记日志、不抛。

> ⚠️ **诚实边界：token 只是「不让 `resolve` 比 `report` 更弱」，它本身不是完整的防线。**
>
> `registerCallback`（`HookChannelService.kt:59-87`）**不校验任何身份** —— 只判 `callback != null`，
> `callerUid != 1000` **仅告警不拒绝**。而 `onCallbackRegistered` 会**生成新 token 并推给刚注册的那个 callback**
> （`HookChannelController.kt:126-147` → `onConnected` → `syncToChannel` → `pushConditions(json, newToken)`）。
>
> ```
> 任何能 bind 且能 registerCallback 的进程
>     ⇒ 成为 App 侧唯一的 callback（覆盖前者）
>     ⇒ 收到 pushConditions 带来的 token
>     ⇒ 此后它发的 report / resolve 全部「合法」
> ```
>
> ⇒ **真正的闸门是 `signature` 权限**。
> ⚠️ **2026-09-29 真机实测**（小米 MIX Fold 3 / Android 17，用 `am startservice` ——
> `startService` 与 `bindService` 在 AMS 里走**同一个** `checkComponentPermission`，而 `adb shell` 就是 uid 2000）：
>
> | 发起方 | 目标 | 结果 |
> |---|---|---|
> | **uid 2000**（shell） | `HookChannelService`（`signature` 保护） | ❌ `Error: Requires permission …HOOK_CONTROL` |
> | **uid 2000**（shell） | `TriggerService`（**无**该权限，阳性对照） | ✅ 成功 |
> | **uid 1000**（system_server） | 同一个 `HookChannelService` | ✅ bind 成功（`dumpsys` 里 `ServiceRecord{…HookChannelService c:android}`）|
>
> ⇒ **规则是 `uid == 1000`（及 0），不是「uid < 10000」。**
> 与 AOSP `ActivityManager.checkComponentPermission` 一致（`appId == ROOT_UID ‖ SYSTEM_UID`）；
> `uid < FIRST_APPLICATION_UID` 是 **`AppsFilterBase` 的包可见性**规则，**属另一个子系统**，此前被误当成权限规则引用。
> **⇒ 安全性比初版描述的更好**：uid 2000 也进不来。
> ⚠️ `FORK.md` 里那条「uid < 10000 时签名权限检查豁免」**据此已修**。
> token 是**纵深一层**，不是唯一一层。
>
> ⚠️ **上面这条链的根因是 §4.2 的「App 侧没有连接身份」** —— 不是「少一个 token 校验」。
> **加 token 校验解决不了「唯一槽位被抢」**；要解决它，必须让 App 侧能区分「第 N 条连接」。

### 3.4 ③ 的传输形态：**oneway + 配对响应**（S4 定案）

> ⚠️ **本节初版把这里写成了「两条约束正面冲突」——那是个假两难（S4 已纠正）。**

**先纠正一个混淆**：

```
「失败可感知」  ≠  「调用方原地阻塞」
```

前者要的是「**结果能回来**」，后者是「**调用方线程被占住**」。**用 oneway + 配对响应，两者可以同时拿到。**

| | 同步（非 oneway） | **oneway + 配对（选定）** |
|---|---|---|
| 调用方拿到结果 | ✅ | ✅（经 `resolve` 回来） |
| 失败可感知 | ✅（返回值） | ✅（`resolve` 里带 `error`） |
| **占 binder 线程** | ⚠️ **占**（handler 跑在 binder 线程上） | ✅ **不占**（接单即返回，handler 在工作线程） |

⇒ **不需要**把方案 b 记录为「对既有纪律的例外」—— 纪律**没有被放弃**，只是换了承载方式。

#### 形态

**⚠️ 不新增 AIDL 接口** —— `IHookHost` 是 App 提供、hook 层已在调的，**方向本来就存在**：

```aidl
IHookHost
  ├─ boolean registerCallback(IHookCallback)   ← 不动
  ├─ oneway void report(String)                ← ① 事件上行（不动）
  └─ oneway void resolve(String responseJson)  ← ★ ③ 应答（新）
```

#### 三层语义（**这是最容易误解的地方**）

```
① 调用方（App 侧）        await 结果 —— 语义上【同步】等
        ↓ oneway invoke（不阻塞）
② hook 层 binder 线程      接单 + 登记 waiter + 投递，立即返回 —— 【不阻塞】
        ↓ 投递到工作线程
③ hook 层工作线程          真正执行 handler —— 【同步执行】，跑完回 resolve
        ↓ oneway resolve
④ 调用方被唤醒              拿到结果
```

| 层 | 同步还是异步 |
|---|---|
| **App 侧拿结果** | **同步**（`await` + 超时）—— 与 `pushConditions` 的调用形态一致 |
| **binder 层** | **异步**（oneway，不占线程） |
| **handler 执行本身** | **同步**（在工作线程上跑完） |

> **准确表述**：**传输异步，业务等待同步。**
> ⚠️ 不要说成「hook 执行能力是异步的」—— 那会推成「fire-and-forget、失败静默」，
> 而**失败恰恰是必须可感知的**。

#### ⚠️ 串行语义：**只保证登记顺序，不保证执行顺序**

AOSP 源码逐字（`IBinder.java`）：

> The system provides special ordering semantics for multiple oneway calls being made to the same IBinder object: these calls will be dispatched in the other process one at a time, with the same order as the original calls. **… the next one will not be dispatched until the previous one completes.**

> ⚠️ **本节初版把这里读成了「一个慢 handler 会挡住后面的请求」—— 那是错的，且与 §5.1 的设计自相矛盾。**
> 它正是本节开头在纠正的那种混淆的又一次重演：把「binder 层的派发」当成「业务层的执行」。
>
> `completes` 指的是**接收端 `onTransact` 返回**，不是「handler 跑完」。
> 而 §5.1 的硬要求恰恰是「binder 线程接单即返回」⇒ 每个 `invoke` 的 `onTransact` 都是**瞬间返回**的。
>
> 若「慢 handler 挡住后续」成立，则 binder 线程被占住 ⇒ **§5.1「不占 binder 线程」的设计当场失效**。
> **两者只能取一** —— 本文取 §5.1：**慢 handler 不挡后续调用。**

正确的表述是把两件事分开：

| | 保证 | 由谁决定 |
|---|---|---|
| **派发顺序** | 与调用顺序**一致** | binder（oneway 串行） |
| **执行顺序** | ⚠️ **不保证** —— 由工作线程池调度决定 | §5.1 的线程池 |

⇒ **结果可能乱序 `resolve`** —— 这是正常的，**必须靠 `request_id` 配对**（§3.3 已如此设计）。
⇒ 于是「两个并发调用同一 capability，后发先至」是**允许**的 ——
前提是 ③ **无状态**。这一句把 §2.2 的「无状态」从分类属性变成了**有可观测后果的约束**。

**真正需要担心的不是「串行」**，是 §5.1 已覆盖的**工作线程池有界**。

#### ⚠️ 工作线程池**满了**怎么办（本节初版未定，S7 补）

§5.1 只写了「必须有界」，**没说满了之后的行为** —— 而三个选项的后果差异极大：

| 选项 | 后果 |
|---|---|
| 阻塞 binder 线程直到池有空位 | ❌ **= 整机风险**，正是 §1.2 约束 4 要避免的 |
| **立即回 `error`（选定）** | ✅ 安全，且失败可感知 |
| 无界排队 | ❌ OOM（跑在 system_server 里） |

⇒ **选定：池满时立即 `resolve` 一个 `ok=false` 的响应，绝不阻塞 binder 线程。**

### 3.5 版本协商

| 事实 | 值 |
|---|---|
| 常量定义处 | **只有一处**（`EventEnvelope.kt:98`） |
| 对外暴露点 | **设计上两个**（`ping()` 返回值 + 信封的 `protocol_version`）；⚠️ **实际只有一个在用** —— `ping()` 零调用者，见 §8 缺陷 4 |
| 实际行为 | ⚠️ **不符时「不拒绝、只 warn」**，之后照常分派 |

**未知 capability** 与 **版本不符** 是**两件不同的事**：

| | 处理 |
|---|---|
| 未知 **topic**（事件侧） | ✅ **忽略** |
| 未知 **capability**（调用侧） | ❌ **必须报错** —— 调用方在等结果，超时会把排查引向错误方向 |
| **版本不符** | ⚠️ 现状只 warn（见上表）。**③ 沿用此口径**（S7 定）：payload 只加不改不删，拒绝对用户无益、只会多一条静默失败路径 |

### 3.6 结果大小契约：Binder 事务缓冲（S7 补）

⚠️ **这是本仓库反复踩过的坑，而 ③ 的首个用例必然撞上它。**

先例：`LogcatCommands.buildFilteredCount` **只回传一个数字、不回传位置索引**（命中上万条时索引会撞上限）；
logcat 调试器的 shell 侧限流；`ShortcutInfo` 全量。

而 §5.1 自己写着 handler「CPU 密集（**如全量 ShortcutInfo**）」——
`capability-invocation-design.md` 的量化是 B 263 / A 44 / D 74 / E 23 / C 4。
**⇒ 返回路径必然撞上限。**

#### 先说清「1MB」到底是什么（S7 复核时查证）

> ⚠️ 初版写成「Binder 1MB 上限」，**说得太干净了** —— 它既不是「每个事务 1MB」，
> 也不是一个能直接拿来当护栏的常数。真实模型如下。

| 事实 | 值 | 出处 |
|---|---|---|
| 谁定的 | AOSP `libbinder` 的 **`ProcessState.cpp`**：进程 `mmap` `/dev/binder` 的大小 | 【源码】`#define BINDER_VM_SIZE ((1*1024*1024) - (4096*2))`（原为 `1*1024*1024`） |
| 归谁 | ⚠️ **接收方进程**，且**该进程内所有在途事务共享这一份** | 【文档】官方 `TransactionTooLargeException`：「currently 1 MB, **shared by all transactions in progress for the process**」 |
| 是不是「每事务 1MB」 | ❌ **不是** —— 它是一份会**被别人占用**的池子。单个事务再小，并发多了照样失败 | 【文档】同上 |
| **oneway 走哪半边** | ⚠️⚠️ **异步事务用单独记账的「一半」空间**（内核 `free_async_space = buffer_size / 2`） | 【源码】内核 `binder_alloc` |
| 实际边界 | **低于 1016 KiB**（1MB − 2 页），还要扣掉 interface token / 长度前缀 / 对齐填充 / 对方已占用的部分 | 【文档】实测分析 |
| 能不能调 | ⚠️ **不是应用可配项**。`libbinder` 有 `ProcessState::initWithMmapSize()`，但要在**任何 binder 调用之前**、且走 native | 【源码】|

#### ⚠️ 这解释了本项目**当年看到的那条报错**

`docs/fork/logcat-debug-tool.md` §2.1 的实测（2026-09-17）：

```
android.os.DeadObjectException: Transaction failed on small parcel;
remote process probably died, but this could also be caused by
running out of binder buffer space
```

**为什么报的是 `DeadObjectException` 而不是 `TransactionTooLargeException`？**

`signalExceptionForError()`（`android_util_Binder.cpp`）拿到 `FAILED_TRANSACTION` 后**只能猜**：
**出站 parcel > 200 KiB** ⇒ 报 `TransactionTooLargeException`；**否则** ⇒ 报 `DeadObjectException`
（消息里承认「也可能是 binder 缓冲耗尽」）。**driver 不告诉你是哪一种。**

⇒ **排查含义**：见到 `DeadObjectException` **不要**直接判定「对端死了」——
先看是不是缓冲耗尽。这条与本仓库「`null`/`false`/异常必须先做对照再下结论」的纪律同源。

#### ⭐ 对 ③ 的直接后果：**oneway 的 `resolve` 超限 = 静默丢弃**

因为 §3.4 把 `resolve` 定成了 **oneway**：

| | 超限时的表现 |
|---|---|
| 同步调用 | 抛异常（虽然可能被误报成 `DeadObjectException`） |
| **oneway**（我们的 `resolve`） | ⚠️ **静默丢弃** —— 异步缓冲耗尽时事务被直接扔掉，**不通知发送方** |

⇒ 与 §6.5 是**同一类风险的另一半**：§6.5 说的是「方法不存在 ⇒ 静默」，这里说的是
「**载荷太大 ⇒ 静默**」。两者都只能靠 App 侧**等到超时**才发现。

> 📌 **业界现成的解法（值得照抄）**：AOSP 的 `NotificationManager` 发通知监听回调时也是 oneway，
> 大 notification 对象一多就撞异步缓冲、**监听方的消息被丢弃**。
> 官方修法是 —— **oneway 只发一个「句柄」，内容由对端再用双向接口取回**。
> 这正是下面契约 4（分页）在系统里的原型。

#### 契约（**所有 capability 一律适用**）

| # | 契约 |
|---|---|
| 1 | **每个 capability 声明自己的响应大小上限**，且**远低于**半缓冲（建议量级 **≤ 256 KiB**）—— 留足余量，因为 oneway 那半边是共享的 |
| 2 | **hook 侧主动截断**，**不能等 binder 报错** —— oneway 下报错根本回不来 |
| 3 | **截断必须带标志位**（照 `ActivityPayload.truncated` 的先例）—— 少了几项时用户不能误以为「本来就没有」 |
| 4 | **需要全量时走分页**：`request` 带 `cursor`，响应回 `next_cursor`（参照 logcat 调试器的翻页游标）；**或**照上面的「句柄 + 双向取回」 |
| 5 | ⚠️ **请求方向同样要小** —— 请求的缓冲是**对方进程的**。`invoke` / `pushConditions` 的接收方是 **system_server**，那份缓冲**与整个系统的所有 binder 流量共享** |

> ⚠️ **为什么必须写在框架层而不是各 handler 自己管**：与 §5.3（分级背压）同一条理由 ——
> 各写各的必然出现「有的截了有的没截、有的带标志有的不带」，
> 而失败形态是**静默的数据缺失**。
>
> ⚠️ **截断发生在「产出」而不是「序列化后」** —— handler 组结果时就按上限收，
> 不是拼完大对象再砍（后者内存已经占过，且跑在 system_server 里）。

> ⚠️ **若将来真的需要传大块数据**：**不要加大上限**，改用带外通道
> （`ParcelFileDescriptor` / 共享内存 / 上面的「句柄 + 双向取回」）。
> 本仓库的 `artifact://` 就是同类思路。
> **「提高上限」这条路在 Android 上本来就不通** —— 它不是应用可配项。

#### ⚠️ 契约 1–4 也必须覆盖 **`report` 上行**（S7 评审补）

上面的契约标题写的是「所有 capability 一律适用」 —— 但 **`report`（事件上行）不是 capability**，
于是**被排除在契约之外**。而它恰恰是**唯一无法重试**的东西：

| | ③ 的响应 | **① `report` 事件** |
|---|---|---|
| 超时后 | App 侧可以**重试** | ❌ **不能重试** —— `drainLoop`（`HookRuntime.kt:268-280`）失败只打日志、不重试不重排（事件的时效性） |
| 丢了的表现 | 显式（步骤报错） | ⚠️ **静默** —— 用户只能从「事件序号跳跃」（`HookChannelController.kt:254-257`）间接发现 |

⇒ **契约 1–4 一律同时适用于 `report`**。

#### ⚠️⚠️ 现有实现的预算按 **char** 计，而契约按 **byte** 立 —— 口径不一致（S7 评审补）

```kotlin
// ActivityPayload.kt
const val MAX_INTENT_URI_CHARS  = 64 * 1024      // :51
const val MAX_EXTRAS_JSON_CHARS = 128 * 1024     // :58
val cost = probeStr.length - 2 + …               // :163  ← .length 是【字符】数
```

**最坏情况** `64K + 128K = 192K 字符`。而 `report` 走的是 **oneway 的异步半缓冲（≈508 KiB）**：

| 载荷构成 | 字节数 | 与半缓冲的关系 |
|---|---|---|
| 全 ASCII（1 字节/字符） | ≈ 188 KiB | 勉强够，但**与所有其他 oneway 事务共享** |
| **全 CJK（3 字节/字符）** | ⚠️ **≈ 576 KiB** | ❌ **超限 ⇒ 整条事件静默丢弃** |
| 叠加 emoji（4 字节/字符） | 更糟 | ❌ |

⇒ 表现是「**打开某些 App 不触发、换一个就正常**」—— 正是 §6.5 说最难上报的那类。
而 §3.6 立了字节契约却**没有回头审计现有字符预算**。

**最小改法**：`ActivityPayload` 的预算改为**按字节**（`toByteArray().size`，或 ×3 保守估算），
上限下调留足余量（如 payload ≤ 64 KiB）；在 `ActivityPayloadTest` 里加
**「中文 / emoji 载荷不超字节上限」的反向断言**。

---

## 4. 连接与状态归属（S3）

### 4.1 连接的定义

> **连接 = hook 层进程对 App 端子的一次成功 `registerCallback`。**

**方向（已核实）**：`bindService` 的**发起方是 hook 层**，被 bind 的是 App 的 `HookChannelService`。

```
hook 层所在进程（客户端）  ──bindService──►  App 的 HookChannelService（服务端）
```

⇒ **连接的主体是「hook 层所在的进程」**，不是「hook 目标进程」。
⇒ **hook 点不是连接粒度** —— 多个 `HookSource` 共享一条连接。

### 4.2 ⚠️ 现状：App 侧**没有连接身份**

| 状态 | 形态 | 后果 |
|---|---|---|
| `callback` | **单槽位** | 后注册者覆盖 |
| `token` | **单份** | 第二条连接一到就整体换新 |
| `lastSeq` | **单份** | 序号基线被抹掉 |

**⇒ App 侧根本无法表示「第 N 条连接」。实际语义是「最后注册者独占」。**

**这不是「某几个字段是单槽位」，是整个模型如此。**

### 4.3 ⚠️ 「一个进程最多一条连接」**没有代码保证**

已核实的多连接路径：

| # | 路径 | 状态（2026-09-28 评审） |
|---|---|---|
| 1 | **热更新换代**：`VFlowHookEntry` 的守卫用**实例字段** `runtime`，而热更新是新 classloader（实例字段全 null）⇒ 拦不住 ⇒ 又一次 `bindService` | ✅ **成立** —— 新代际必然新建 `BinderTransport` 并重连 |
| 2 | 旧代际的 `connectWithWaits()`（等待上限 **150 秒**）在新代际之后才 `doBind()` | ⚠️ **当前不可达** —— 见下 |
| 3 | **`scheduleReconnect` 的幂等守卫只挡重连线程之间** | ✅ **成立**（守卫见 `BinderTransport.kt:227`） |

> ⚠️ **第 2 条经复核后不属于「可达路径」，属防御性设计**（S7 评审降级）。
> 原因：`VFlowHookEntry.onHotReloading` 会先 `stopChannelOnly()`
> （`VFlowHookEntry.kt:272-287` → `:296-302` → `BinderTransport.stop():206-215`），
> 它**置 `stopped = true` + `interrupt()` 三条线程 + `unbindQuietly()`**，
> 而 `connectWithWaits()` 每轮循环都检查 `stopped`（`:315-335`）⇒ **旧线程会及时退出**。
> 另：`scheduleReconnect` 只由 `onServiceDisconnected` / 存活性巡检触发，
> 而巡检在 `host == null` 时 `continue`（`:288`）⇒ 也推不出「首次连接线程与重连线程重叠」的时序。
>
> **⇒ 结论不变，但论据要换**：真正撑住「必须有连接身份」的是**第 1 条**与
> **多进程 hook 落地后每进程一条连接**（§4.4）—— 这两条**不需要**「150 秒窗口」这个假设。

### 4.4 状态归属（**S3 决策 2**）

| | 归属 |
|---|---|
| 连接**身份** | **binder 实例**（协议里**不加** `connection_id`） |
| 连接**状态** | ⚠️ 见 §8 —— 现状由 `BinderTransport`（实例）与 `HookChannelController`（单例）**各持一遍** |

⚠️ **一处曾经的误判**（记下来避免重犯）：
本文讨论中曾认为「hook 层在 system_server 时可同进程直调 `HookChannelController`」——
**做不到**：hook 层跑在 LSPosed 的模块 ClassLoader 里，
引用它会在**该 ClassLoader 里加载成另一个类实例**，静态字段**不共享**。
**真正的隔离来自 ClassLoader 边界，不是 `WireLayerPurityTest`。**

#### ⚠️ 「连接身份 = binder 实例」**何时必须落地**（S7 补）

现在只有 system_server 一个 hook 位置，所以 §4.2 的「无连接身份」**还只是潜在问题** ——
`callback` 被覆盖的后果暂时看不出来（只有一个消费者）。

但 §2.1 的「执行环境」是**开放维度**，§9/§10 又提到位置 3（目标 App 进程的 hook）。
**因果链必须写明**：

```
hook 层从「只有 system_server」扩到「每个目标 App 一个进程」
    ⇒ 每个进程一个模块实例、一条 binder 连接
    ⇒ App 侧 callback / token / lastSeq 被反复覆盖
    ⇒ ⚠️ 只有【最后注册的那个进程】能工作，其余全静默失效
```

⇒ **多进程 hook 落地前，App 侧连接身份必须先实现。** 这是位置 3 的**前置依赖**，
与 §10 未决项 5（传输路径选型）不是同一件事 —— 后者是选型，这条是**无论选哪种都躲不掉**的改造。
本决策的形态（身份由 binder 实例承载、协议不加 `connection_id`）**对两种传输方案都成立**，故可先定它。

---

## 5. 执行模型（S4）

### 5.1 线程模型

**三条硬约束**：

| # | 约束 | 来源 |
|---|---|---|
| 1 | **绝不占 system_server 的 binder 线程池** | 后果是**整机** |
| 2 | hook 回调（`report` 路径）**只入队、立即返回** | 已有实现（`HookRuntime.emit`） |
| 3 | ③ 的 handler **不能跑在主线程** | CPU 密集（如全量 ShortcutInfo） |

**线程划分**：

```
binder 线程池（system_server 系统共享，⚠️ 不是我们的）
  ├─ IHookCallback.invoke
  │     只做「校验 + 登记 waiter + 投递给工作线程」，立即返回
  └─ IHookCallback.pushConditions
        已有实现：解析 + 分发给 source
        ⚠️ 它现在在 binder 线程上做全量分发 —— 条件变更低频，可接受；
           **新增 source 时不要让它的 applyConditions 变重**

⚠️ system_server 【主线程】（第四格 —— S7 评审补，初版漏了）
  └─ ServiceConnection.onServiceConnected（bindService 未传 Handler）
       → 同步 binder 调 registerCallback（非 oneway！）
       → onConnectedSink → remountSources（loadClass + 反射 + hook()）
       ⚠️⚠️ 禁止在这一格做同步 IPC / 类加载 / 安装 hook

工作线程池（fork 自己创建，⚠️ 必须有界）
  └─ 执行 capability handler
       超时后放弃并回 error
```

⚠️ **工作线程池必须是我们自己创建的** —— 不能借 system_server 的。
但它跑在 system_server 进程里，**线程数必须有上限**，否则后果是整机。

⚠️ **池满时的行为已定（S7 补）**：**立即 `resolve` 一个 `ok=false` 的响应**，
**绝不阻塞 binder 线程** —— 见 §3.4 末的对照表。

#### ⚠️⚠️ 第四格：**连接建立路径落在 system_server 主线程**（S7 评审补）

初版的三条硬约束 + 两个线程池**漏了这条路径**，而它恰好同时违反约束 1 与 3：

```
BinderTransport.kt:392   bindService(intent, connection, BIND_AUTO_CREATE)
                          ↑ 【源码】未传 Handler ⇒ 回调经 ActivityThread.H 落到 main
:410-446  onServiceConnected
   :423      h.registerCallback(callback)     ← 同步（非 oneway）跨进程调用
   :435      onConnectedSink?.invoke()
                → VFlowHookEntry.kt:221 → HookRuntime.remountSources()
                     → ActivityChangedSource.mount()
                          → loadClass + declaredMethods + hook() + intercept{}   ← 反射 + 类加载
```

**两个后果**：

> ⚠️ **2026-09-29 真机实测**（小米 MIX Fold 3 / Android 17）：上面这条链**确实全在主线程** ——
> logcat `threadtime` 的 `pid tid` 两列显示 `onServiceDisconnected` / `bindService 成功` /
> `registerCallback 成功` / `hook 点已找到` / `✅ hook 已挂上` **全部 `pid == tid`（= system_server 主线程）**。
> **实测占用 ≈ 7ms**（`bindService 成功` → `hook 已挂上`）。
> ⚠️ **但那是热态**（类已加载、App 已在跑）。**未测的尾部是「设备重启后 system_server 首次挂载」** ——
> 那一次才真正做类加载与方法扫描，也是这条风险真正所在的位置。

| # | 后果 |
|---|---|
| 1 | `registerCallback` 是**同步**的 ⇒ 若对端（App）正冷启动 / 被冻结 / GC，**system_server 主线程被占住**数百 ms~数秒 |
| 2 | ⚠️ **嵌套往返**：主线程 → App → App 的 binder 线程 → **再回 system_server** 处理 `pushConditions`（`HookChannelController.onCallbackRegistered:141-145` → `onConnected` → `syncToChannel`）⇒ 这条链要跑完，**还需要本端 binder 池有空闲线程** |

⇒ **这是「禁止在主线程做同步 IPC」的第二条理由**（第一条是「对端可能卡」，第二条是「**本端 binder 池可能满**」）。

**最小改法**：`bindService` 传一个自建 Handler，或把 `onServiceConnected` 收缩为「只置一个 `@Volatile` 标记 + 唤醒工作线程」，把 `registerCallback` 与重挂全部移出主线程。

> ⚠️ **前提待验**：「回调必在主线程」是平台常识（`LoadedApk.ServiceDispatcher`），
> 本次评审**未本地核对 AOSP**，标【推断】；而「`bindService` 未传 Handler」是【源码】确凿。
> 见 §8.2-3。

### 5.2 超时（三层）

| 层 | 防什么 | 手段 |
|---|---|---|
| **App 侧** | hook 层完全不响应 | 配对表 + 超时丢弃 waiter |
| **hook 侧总时长** | handler 正常但极慢 | 工作线程上计时，超时回 `error` |
| **Rhino 指令级** | 脚本死循环 | `setInstructionObserverThreshold`（**已有实测**：`while(true)` 1002ms 被中断） |

#### ⚠️ 必须记录的限制：**非脚本 handler 无法被中断**

第三层**只对 `execute_script` 有效**。对 `query_shortcut_intents` 这种纯 Kotlin handler ——
**一个卡在系统调用里的 handler 无法被中断**。

⇒ 只能靠「工作线程池有界 + 总时长上限」兜，并**接受「少数 handler 可能永久占用一个工作线程」**。

**这点必须写进文档** —— 否则会以为超时是万能的。

> ⚠️ **同一根因的第二种形态**（S7 评审补）：**脚本里一次阻塞的 Java 调用同样不可中断** ——
> `execute_script` 里的 `someJavaCall()` 若卡住，Rhino 的指令级观察器**根本没机会跑**
> （它只在「执行下一条指令」时触发）。所以「脚本可中断」只覆盖**纯计算死循环**，
> 不覆盖**阻塞调用**。文档此前只写了「非脚本 handler」，漏了这一半。

#### ⚠️ 中断的**实现契约**：异常不能逃逸（S7 评审补）

Rhino 的指令级中断是靠**抛异常**打断的。这个异常必须在 hook 侧 handler 内被**捕获并转成 `resolve(ok=false)`**：

```
❌ 让它逃逸 ⇒ 落在跑 handler 的【工作线程】上
   ⇒ 在 system_server 里是一次未捕获异常 ⇒ 崩溃半径 = 整机（§5.6）
```

⇒ 契约写死：**工作线程的顶层必须 `try/catch(Throwable)`，任何异常都转成 `resolve` 响应**，
绝不逃逸。这与 §6.1「hook 层所有回调都包 `try/catch`」是同一条纪律，但要**额外覆盖工作线程** ——
现有实现的 `try/catch` 只在回调与适配器层，工作线程池还是空的。

#### ⚠️ 三层超时的**实现现状**（S7 评审补）

| 层 | 实现现状 |
|---|---|
| App 侧配对表 | ⚠️ **零代码**（③ 未实现） |
| hook 侧总时长 | ⚠️ **零代码** |
| Rhino 指令级 | ⚠️ **只有 survey 里的实验记录** —— 全仓 grep **无** `InstructionObserver` / `observeInstructionCount` |

⇒ **不要把三层超时当成「已具备的能力」**。§5.2 描述的是**契约**，不是**现状**；
它必须与 ③ 一起从零实现，并**先把 survey 的实验封装成可复用件**。

#### ⚠️ App 侧配对表：容量、超时、断连唤醒（S7 补）

§3.4 只写了「配对表 + 超时丢弃 waiter」，三格没定：

| 项 | 决定 | 理由 |
|---|---|---|
| **容量上限** | **必须有界**，与 §5.1 的工作线程池上限对应 | 否则 waiter 会无界累积（hook 层长时间不响应时） |
| **超时值** | 逐调用给定（`timeout_ms`，§3.3），兜底默认值 | 不同 capability 耗时差异大（全量 ShortcutInfo vs 一次读字段） |
| **断连时** | ⭐ **立即唤醒全部 waiter、回 `ok=false`** | 比等超时好得多 —— 且 §4.2 **已经有** `HookChannelController.connected: StateFlow` 可作触发源，是现成的 |
| **迟到 / 无配对的 `resolve`** | **丢弃 + 告警**，不能静默 | 否则**伪造响应会被无痕接受**（见 §3.3 的鉴权） |

### 5.3 分级背压

#### 为什么现有模型不成立

现状：**有界队列（容量 256）+ 满了丢弃并计数**。这套是为 **activity 切换**（秒级）设计的。

而**输入/手势类事件是另一个量级**（每秒数十次且连续）：

```
activity 切换   ──┬────┬────┬──→   秒级，天然稀疏
输入/手势       ─┬─┬─┬─┬─┬─┬─┬─┬─→  每秒数十次，连续
                 ↑ 丢 1 条无所谓        ↑ 丢掉中间态 = 丢掉轨迹
```

⇒ **「有界队列 + 丢弃计数」在高频源上语义不成立**。

#### 分档方案

| 源类型 | 频率 | 策略 | 理由 |
|---|---|---|---|
| **离散事件**（activity 切换、包名） | 秒级 | **有界队列 + 丢弃计数**（现状） | 丢一条不影响语义 |
| **连续/高频**（输入、手势） | 每秒数十 | **保序 + 丢弃计数**（按键）／**采样**（手势，保留起止 + 关键拐点） | 丢中间态会破坏语义 |

⚠️ **具体的采样策略不在此硬定** —— 它取决于具体事件语义，
留给**第一个高频源**的实现。本节只定「**必须分档**」。

> ⚠️ **「必须分档」本身是【推断】**（S7 评审指出）：本仓库**没有**任何高频 hook 源，
> 也没测过现有队列在每秒数十次输入下的表现，依据只有 ShortX 的外部对照。
> 见 §1.2 约束 2 的注。

#### ⚠️⚠️ 现状：「有界队列 + 丢弃计数」**这条链路本身没闭合**（S7 评审补）

上表把「有界队列 + 丢弃计数」写成**现状策略** —— 但**丢弃计数在 Xposed 侧从未被清零**：

```
EventQueue.drainDropped()（wire/EventQueue.kt:75）  = 唯一清零入口
      ⇒ 全仓【零生产调用者】（只有 EventQueueTest 调）
HookRuntime.emit:252  塞进信封的是 queue.peekDropped()   ← 累计值，只读不清
HookChannelController.kt:264-271  只在 >0 时赋值 lastDroppedReported，从不复位
ui/home/HomeScreen.kt:381  if (xposedDroppedCount > 0) ⇒ 横幅常驻
```

⇒ **一次丢弃 = 永久「已丢弃 N 条」**（直到 App 进程被杀），且此后每条事件都刷一条 warning。
**对照**：Core 的 logcat 路径**有**真实调用者（`LogcatStreamWrapper.kt:503`）—— 同一模式只在 Xposed 侧漏了。
`HookRuntime.kt:250` 的注释还逐字写着「清零由发送成功后的 `drainDropped` 负责」。
**详见 §8 缺陷 14 与 §8.1-1 的最小改法。**

### 5.4 hook 点挂载 / 卸载

#### 决策：**不做空闲卸载**，改为**规范早退**

⚠️ **本节初版曾倾向「补上空闲卸载」，S4 推翻。** 理由如下（**性能不是理由**）：

| 理由 | 成立吗 |
|---|---|
| 省性能 | ❌ **不成立** —— 见下 |
| 降崩溃风险 | ⚠️ 边角 —— 风险在「回调里做什么」，不在「挂着」 |
| 少一次跨进程回调 | ✅ 但可忽略 |

**性能为什么不成立**：Activity 切换频率极低（用户操作时才发生，0–2 次/秒），
空闲时省掉的那几次反射在 `activityResumedLocked` 本身的成本（窗口切换、Surface 创建）
面前**在噪声里**。

**而空闲卸载的真实代价**：

| | 空闲卸 | 常驻 |
|---|---|---|
| 性能 | 略好（可忽略） | 略差（可忽略） |
| **重挂时机** | ⚠️ **不可控**（热更新后尤其） | — |
| 复杂度 | 需要订阅→挂卸的联动 | — |

⇒ **「卸下去挂不上来」比「挂着浪费」严重得多。**

#### ⚠️ 本决策与两处既有表述**冲突**（S7 评审补）

| 位置 | 它怎么说 | 冲突点 |
|---|---|---|
| `core/xposed/HookChannelController.kt:195-196` | 「空串的语义是『**没有触发器了，把 hook 卸掉**』，不下发会让 hook 层白挂着 hook 点」 | 而实际行为（`ActivityChangedSource.applyConditions`）只是把 `conditions` 置空 ⇒ **早退**，不卸载 |
| `IHookCallback.aidl:18` | 「**空串表示『没有触发器了，请卸下 hook』**」 | 同上 —— 这是 AIDL 上的**契约文案**，不是注释 |
| `HookRuntime.stop():106-117` | 真的会 `unmount()` 全部 hook | 该分支在 §5.4 的策略下**即使接上（缺陷 10）也与它冲突** |

⇒ **三处都要对齐**：空条件的语义改为「**让 hook 早退，不卸载 hook 点**」，
并明确 `HookRuntime.stop()` 的 unmount 分支是**进程退出专用**（不是「无触发器」路径）。
⚠️ 这也是 §8 缺陷 9/10 那条死链**修之前要先厘清**的事 —— 否则接上它会得到与 §5.4 相反的行为。

#### 规范：早退必须在**最前**

**现状的问题**（`ActivityChangedSource.kt`）：最便宜、最决定性的判断**排在三次反射之后**：

```
:132  onActivityResumed                        ← 定义行
:136    resolveRecord(chain, token)            ← 调用（定义在 :159）
:162        recordCls.getDeclaredMethod("forToken")   ⚠️ 反射【查找】
:170        forToken.invoke(null, token)              ⚠️ 反射【调用】
:138    emitIfInteresting(runtime, record)     ← 调用（定义在 :178）
:179        readString(record, "packageName")         ⚠️ 反射 getDeclaredField
:183        val cond = conditions
:184        if (cond.isEmpty) return    ★★ 早退在这里
        ↑ ★ 从 :134 的 chain.getArg(0) 到这里，中间【没有任何 conditions 判断】
```

> ⚠️ 行号口径：`:159`（`resolveRecord`）与 `:178`（`emitIfInteresting`）是**定义行**，
> 实际调用在 `:136` / `:138`。初版把两种口径混在图上，照抄会找错位置。

⇒ **空闲时每次 Activity 切换仍要付 3 次反射**，而 `conditions.isEmpty` 本身**几乎免费**。

**规范**：

```kotlin
private fun onActivityResumed(chain: XposedInterface.Chain, runtime: HookRuntime): Any? {
    // ★ 最便宜且最决定性的判断放最前 —— 空闲时直接放行
    if (conditions.isEmpty) return chain.proceed()
    try { ... }
    // ⚠️ proceed() 必须在【所有】分支调用 —— 不调用会破坏整机行为
    return chain.proceed()
}
```

⚠️ **`chain.proceed()` 必须在所有分支调用**（现有注释已明确：不调用是**破坏整机行为**，不是「功能不生效」）。

> ⚠️ **`conditions` 必须是 `@Volatile`**（现有实现已是，`ActivityChangedSource.kt:60-61`）。
> 早退判断读它、`applyConditions` 写它，**两个线程**；用普通字段会读到旧值 ⇒
> **hook 点已挂、条件已下发，却永远早退** —— 又是一次静默失效。
> 照示例实现时别省掉这个修饰符。

#### 附带规范：反射句柄应缓存

`resolveRecord` 与 `readString` 里每次回调都做 `getDeclaredMethod` / `getDeclaredField`（**反射查找**）。

⇒ **这些 `Method` / `Field` 对象应缓存**，但：

> ⚠️ **只能缓存在实例字段，不能静态字段** ——
> P0 实测：热更新换 classloader 后**静态字段是全新的**。

**收益比早退上移更大**（省掉的是真正的反射查找），且**不影响任何生命周期语义**。

> ⚠️ 缓存 `readString` 的 `Field` 时注意：它现在用 `target.javaClass.getDeclaredField(...)`
> —— 键是**字段名**，而目标是 `ActivityRecord` 的**运行时类**。缓存键要带上类，
> 否则将来「目标 App 进程的 hook」（执行环境维度）会跨类复用同一个 `Field`。

### 5.5 ⚠️ 「引用捕获型」执行能力：**不能空闲卸载**（预留）

> ⚠️ **当前一个都没有** —— `query_shortcut_intents` 与 `execute_script` **都是纯调用型**。
> 本节是**反模式预警**：将来做 `CloseActivity` 这类能力时，不要把它当成 ③ 的普通成员。

**引用捕获型** = hook 层趁方法被调用的机会，把对象「抓」出来存住，之后反复用
（如：hook `StatusBarManagerService` 抓 `mInternalService`；hook `ActivityRecord` 抓实例）。

| | 触发器 | 引用捕获型 |
|---|---|---|
| hook 卸下后 | 不再收事件（**用户没配，本来就不该收**） | ⚠️ **新对象捕获不到**，且旧引用会过期 |
| 恢复 | 重挂即可 | ⚠️ **要等目标方法下次被调用** —— **不可控** |

⇒ **卸载对触发器是「恢复」，对引用捕获型可能是「永久丢失一次机会」。**

**三种失效模式**（记下来避免将来踩）：

| 模式 | 说明 |
|---|---|
| **僵尸引用** | 对象销毁后引用**不是 null**，但用它无效/崩溃 —— `if (ref != null)` **拦不住** |
| **捕获窗口** | hook 刚挂上时引用还是 null，要等目标方法被调用（**多久不可控**） |
| **多对象该留哪个** | 同一方法每次调用都是一个新对象 ⇒ ⚠️ **会重演单槽位坑**（见 §7.4 反模式 1） |

### 5.6 崩溃半径与降级

> ⚠️ **本表含「不做」的位置** —— 第二行（目标 App 进程的 hook）在 §9 已定**不做**。
> 列出它只为说明半径的**量级差**（整机 vs 单进程），不是实施计划。

**分档**（从大到小）：

| 位置 | 崩溃半径 | 做不做 |
|---|---|---|
| **system_server 内的代码** | **整机** | ✅ 现状 |
| 目标 App 进程内的 hook | 那个 App | ❌ **不做**（§9） |
| App 进程侧 | 单进程 | ✅ 现状 |

⇒ **引用面约束**（`WireLayerPurityTest` 锁住的）不是洁癖，是**崩溃半径**的直接后果：
在 system_server 里引用 App 侧重类，会把它们的**静态初始化**拖进来。

---

## 6. 可用性与降级（S5）

### 6.1 状态：**复用现有两套，不新造**

P4 已实现两套状态位（`core/xposed/XposedState.kt`），设计正确，**不要动**：

| 状态位 | 取值 | 回答 |
|---|---|---|
| **Framework** | `UNAVAILABLE` / `DEGRADED` / `ACTIVE` | 框架环境可用吗 |
| **Channel** | `DISCONNECTED` / `NOT_MOUNTED` / `READY` | 事件能流过来吗 |

**核心设计（P4 已确立）**：

> **`Channel` 不依赖 `Framework`** —— 耦合成「框架不在 ⇒ 通道必然断」会让
> **`ACTIVE + DISCONNECTED` 这一格永远判不出来**，而那是最容易被误判成「框架问题」的一格。

#### ⚠️⚠️ 但这个「两组合起来看」**没有贯彻到权限判据**（2026-09-28 评审发现）

同一份状态，**两个消费点口径不同**：

| 消费点 | 取哪些状态位 | 代码 |
|---|---|---|
| **首页状态卡** | ✅ **合成** —— `monitor.evaluate(HookChannelController.isConnected())` | `ui/home/HomeScreen.kt`（`evaluate(channelConnected)` 见 `XposedFrameworkMonitor.kt:212`） |
| ⚠️ **权限判据** | ❌ **只取 L1** —— `XposedFrameworkMonitor.state.value.frameworkConnected` | `core/xposed/XposedCapability.kt:55-56` |

**后果**：**框架在跑、但通道断了**（缺陷 1/13，或 App 刚重启还没等到解锁/服务就绪连上）⇒

```
权限判「已授予」 ⇒ TriggerService 不禁用工作流
              ⇒ ⚠️【没有任何缺权限提示】，而触发器确实不工作
```

这正是本节开头说的「**最容易被误判成框架问题**」那一格 —— 而权限层会报告「一切正常」。

> ⚠️ 这不是「忘了改」，是**一个未决定的设计取舍**：
> P4 特意把判据改成**实时**（修「永久授权掩盖真实失效」），
> 而实时判据 + **异步连接** ⇒ 开机后**必然**存在「框架已连、通道未连」的窗口。
> `XposedCapability` 的 KDoc 承认了这个窗口，但把它交给 `PermissionGuardianService` 每 10 秒重试兜。
>
> ⇒ **必须在文档里明确回答「权限判据取 L1 还是 L0 ∪ L1」并给理由**；
> 若取 L1，则 §6.4 的失败分类**必须新增一类可见状态**「已授权但通道断」，
> 不能只靠首页卡片（那是「用户得自己去看」）。**见 §10 未决项 10。**

### 6.2 降级判据：**取决于「能力有没有替代实现」**

> ⚠️ **本节初版曾写「③ 必须有降级路径」——已推翻（见 §2.2 的订正）。**
> 「要不要降级」**是能力的属性，不是生命周期的属性**。

**判据**：

```
dumpsys 路径（已存在） ──┐
                        ├─→ 快捷方式【有两条实现】⇒ 替换型
Xposed 能力  ───────────┘

新模块 X（只有 Xposed）──→ 【只有一条实现】⇒ 独占型 ⇒ 无从降级
```

⇒ Xposed 对前者是**升级**，对后者是**唯一**。

| 能力类型 | Xposed 不可用时 | 用户视角 |
|---|---|---|
| **替换型**（有替代实现） | **静默降级** | 功能仍在，结果可能略差 |
| **独占型**（只有 Xposed 实现） | **无从降级** —— 与订阅侧同侧 | 功能不可用 |

**⇒ 这个属性声明在 App 侧的能力注册表里**：

```kotlin
data class Capability(
    val name: String,
    val risk: RiskLevel,
    /** ★ null ⇒ 独占型，没有降级 */
    val fallback: (suspend (Map<String, Any>) -> Any)?,
    /** ★ §3.6：这个 capability 的响应大小上限；null ⇒ 用全局默认 */
    val maxResultBytes: Int? = null,
)
```

> ⚠️ **`fallback` 必须接收 `params`（S7 修正）**：初版写成无参 `() -> Any` ——
> 那样每个降级实现只能自己去读外部状态，**重演「各消费者只算自己那份」**（§7.4 反模式 2）。
> 降级的契约是「**同样的入参、同形状的结果、更差的实现**」，签名必须让这一点能被表达。

> ⚠️⚠️ **命名冲突必须消歧（2026-09-28 评审发现）**：仓库里**已经有一个** `XposedCapability`
> （`core/xposed/XposedCapability.kt`），它是**权限判据**，与此处的 `Capability`（③ 能力）**语义毫无关系**。
> 而本文说「放进能力注册表（§6.2 的 `Capability` 旁边）」—— 实现者按这句去找，
> 极可能撞上那个同名文件、把两件事混在一个包里。
>
> **⇒ 先定包与文件名**（建议 `xposed/capability/Capability.kt` + `CapabilityRegistry.kt`），
> 并在 §6.3 明确写出与 `XposedCapability` 的消歧。**详见 §10 未决项 13。**

#### 两种形态的降级/失败表现

| 类型 | 不可用时的形态 |
|---|---|
| **替换型** | **静默降级 + 留痕** —— 用户视角无变化，但排查时能看到「走的是降级路径」 |
| **独占型** | **明确告知 + 引导** —— 复用 `XposedState.tapAction()` 的 `GUIDE` 路径 |

⚠️ **独占型的形态不是「空列表」也不是「错误弹窗」**：

```
❌ 空列表  ⇒ 看起来像「你没有快捷方式」（误导）
❌ 错误弹窗 ⇒ 它不是一个错误
✅ 「需要 Xposed 才能用 → 点击配置」
```

#### ⚠️ 替换型的一处**必须接受的语义差**

```
hook 路径：完整 Intent（dat 完整 + extras 类型正确）
dumpsys：  有损（18.1% dat 缺失 + 类型靠猜）
```

⇒ **同一台设备上，降级前后结果不同。** 这不是 bug，
但**必须留痕** —— 否则「昨天能用的快捷方式今天不能用了」会被当成回归。

### 6.3 能力可用性：`CapabilityPresence`（**连接期交换的缓存**，不是状态机）

> ⚠️ **定位订正**：本节初版把它写成「与 Framework/Channel 并列的第三个状态位」——**不准确**。
> 它**不是持续维护的状态机**，而是**连接建立时的一次能力交换结果的缓存**。
>
> ⚠️ **S7 补：形态上必须与 §6.1 的两组状态位区分开。**
> 它**是**一个 enum、**有**状态迁移（连接填入 / 断开重置），所以**放在 `core/xposed/` 的状态模块里
> 一定会被当成第三组状态位** —— 而 §6.1 明说「复用现有两套，不新造」。
> **做法：物理位置上放进能力注册表（§6.2 的 `Capability` 旁边），不进 `XposedState`。**

```kotlin
enum class CapabilityPresence { UNKNOWN, ABSENT, READY }
// 连接建立时（一次能力交换）→ 填入；断开时 → 重置为 UNKNOWN
// ⚠️ 数据源「由谁承载」未定 —— §3.1 指出 ping() 现为 int，与本节契约冲突，见 §10 未决项 8
```

> ⚠️ **与 P4 的 `Channel.NOT_MOUNTED` 不是一回事**，必须消歧，否则读者一定混：
>
> | | 回答的问题 | 来源 |
> |---|---|---|
> | `Channel.NOT_MOUNTED` | 模块**挂到 system 了吗** | `runningTargets`（L3） |
> | `CapabilityPresence.ABSENT` | hook 层的**代码里有没有这个方法** | `ping()` 的能力清单 |
>
> 前者是「挂载」，后者是「**代码版本**」—— 一个已挂载的旧版本 hook 层，
> `Channel` 判 `READY` 而 `CapabilityPresence` 判 `ABSENT`。**两者同时成立是正常的。**

> ⚠️ **初版的 `DEGRADED` 已删除**，原因：
> 它被描述为「引用捕获型专用（引用还没抓到）」，但 ——
> ① 引用捕获型**现在一个都没有**（§5.5）；
> ② **「引用有没有抓到」不是连接级属性** —— 它随对象生死变化、秒级抖动，
> 放在「连接期交换一次」的缓存里**根本不准**。
> 真要表达它，应是**能力自己返回的状态**（`ok=true, result={ready:false}`），不是通道级状态。

#### 它**唯一不可替代的用途**：调用前判断「要不要试」

**问题**：hook 层可能是**旧版本代码**（running in system_server，热更新完成前仍是上次加载的）。

```
旧版本没有 IHookCallback.invoke 这个方法
    ↓
App 侧 oneway 调用它
    ↓
⚠️ binder 对「不存在的 oneway 事务」【静默丢弃】——
   没有错误、没有返回值、没有回调（oneway 的语义就是不等应答）
    ↓
App 侧只能等到【超时】才知道失败
```

⇒ **没有这个状态，用户每次都要白等 5 秒**（然后才降级）。

**这个场景在别处检测不出来**：

| 机制 | 为什么不够 |
|---|---|
| §3.2「未知 capability 必须报错」 | 要 hook 层**能收到并应答**才谈得上报错 —— 旧版本根本收不到 |
| 协议版本比对（`HookChannelController.kt:274`） | **被动**的，只在**收到信封时**才比 |

⇒ **`CapabilityPresence` 是唯一的「调用前信号」。**

#### ⚠️ 它**不用来判降级**

```
状态：提前决定【要不要试】        （优化，可能错 —— 状态是采样的）
结果：决定【要不要降级】          （权威，每次实测）
```

**⇒ 降级判断的依据永远是运行时调用结果**，状态位只用来**避免白试**。

#### 消费点只有两个

| # | 消费点 | 用途 |
|---|---|---|
| **1** | **③ 的调用前判断** | `ABSENT` ⇒ 直接走降级，不白等超时 |
| **2** | **错误提示的分类** | 见 §6.4 |

> **是否在首页状态卡展示？⇒ 可选，建议先不做。**
> 它的主要用途是运行时优化，用户不需要知道；且能力会越来越多（30 个时列不下）。
> 若要做，是在现有卡片上加**聚合指示**（「部分能力不可用」），不是新增能力列表。

### 6.4 可诊断性：失败原因必须分类

**现状问题**：四种处置完全不同的失败，**都表现为「没反应」**。

| 失败原因 | 用户该做什么 |
|---|---|
| `ABSENT`（hook 层没这个能力） | **升级 / 重启 App**（**不是**去改 LSPosed 配置） |
| 超时 | 报告问题（可能是 bug） |
| handler 报错 | 看具体能力 |
| 通道断 | 检查 LSPosed（**复用现有 `tapAction()` 的 `RECONNECT_HINT`**） |
| ⚠️ **已授权但通道断**（S7 评审补） | 等自动重连；持续则检查 LSPosed —— 见 §6.1 的权限判据问题 |

⚠️ **关键**：前三（或四）条**指向 App 侧**，其余指向**框架配置** ——
混在一起会让用户去白折腾错误的方向（P4 已踩过同类坑：`TriggerService` 加载比 hook 连接早 1.6 秒，
旧文案却让用户「检查 LSPosed 配置」，而他的配置完全正确）。

#### ⚠️ 分类必须**机器可判**（S7 评审补）：要枚举，不要自由字符串

§3.3 的信封只规定 `ok=false` **必须带 `error`**，而那是个**自由字符串**。
若照此实现，上表的分类只能靠**匹配中文文案** ⇒ 改一次文案就分类错，
且第 4/5 类（通道断）本可在**调用前**判定，却也只能等超时后从文案里认。

⇒ **定枚举**（建议）：

```
capability_absent      → 升级/重启 App
timeout                → 报告问题
handler_error          → 看具体能力
channel_down           → 检查 LSPosed / 等重连
payload_too_large      → §3.6 契约 2（截断/分页，属实现缺陷或数据异常）
```

`error` 拆成 `{ code: <枚举>, detail: <可读串> }`，
§6.4 上表的映射**直接落在枚举值上**，UI 侧不再做字符串判断。

### 6.5 ⭐ oneway 的静默丢弃（**③ 特有的新风险**）

这是 §3.4 选 oneway 带来的、**必须单独记的一条**。⚠️ **它有两个来源，别只记一个**：

| 来源 | 表现 | 缓解 |
|---|---|---|
| **调用一个不存在的 capability** | ⚠️ binder **静默丢弃** —— 无错误、无返回值、无回调 | §6.3 的 `CapabilityPresence`（调用前判断） |
| ⚠️ **载荷超出异步缓冲**（S7 查证补充） | ⚠️ **同样静默丢弃** —— oneway 不通知发送方（内核异步缓冲耗尽时直接扔） | §3.6 的**声明上限 + 主动截断 + 分页** |

**两者的共同后果都是「App 侧只能等超时」** —— 所以 §6.4 的**分类提示**对两者都适用。

⚠️ **注意第一条与 §3.2「未知 capability 必须报错」不矛盾** ——
后者是**hook 层收到了请求但不会做**（能应答，所以能报错）；
前者是**hook 层根本没这个方法**（收不到，只能静默）。**两者是不同层次的失败。**

> ⚠️ **第二条尤其危险的理由**：它**与能力是否正确无关** ——
> 同一段代码、同一个 capability，**数据量大就静默失败、数据量小就正常**。
> 用户会看到「有时能用、有时不能用」，而这是最难被当成 bug 上报的形态。

---

## 7. 扩展规范（S6）

### 7.1 本文的边界：只管 **hook 特有** 的部分

⚠️ **这是 S6 最容易跑偏的地方。** 触发器体系（28 个）里，**大部分问题与 Xposed 无关**：

```
触发器体系（28 个，其中 1 个是 hook 的）
    ├─ 类型化 / 通用契约 / 体检   ← ⚠️ 项目级话题，影响全部 28 个
    │                                  【不属于本文】→ 见 §7.5
    └─ hook 消费者特有部分          ← 【本文范围】
          · 条件下发（schema 按 topic 分 / 汇总）
          · 连接重建重下发
          · 分级背压（按源类型分档）
```

**契约要切分，不是整个划出去**：

| 契约内容 | 归哪 | 为什么 |
|---|---|---|
| 条件下发的**全量替换 + 汇总** | **本文** | hook 独有的语义 |
| 连接重建后**重下发** | **本文** | hook 独有（条件不落盘） |
| **分级背压** | **本文** | 通道层的能力 |
| ⚠️「**每次增删都要通知 Handler**」 | ❌ **触发器体系** | 对全部 28 个成立，不只 hook |

> 最后一条是 `ListeningTriggerHandler` 那个坑的**通用形态** —— 它是触发器体系的问题，不是通道的。

### 7.2 加一个 hook 消费者要改哪几处（**从代码数出来**）

> ⚠️ **本表只数「功能改动」** —— **不含测试与构建配置**。那两类另见下面的补充行（S7 评审补）。

⚠️ **7/8/9 是「hook 消费者特有」的三处，也是当前设计最弱的三处。**

| # | 要改 | 说明 |
|---|---|---|
| 1 | 模块类（`XxxTriggerModule.kt`） | 输入/输出定义 |
| 2 | `ModuleRegistry` +1 行 | 现 28 个 |
| 3 | Handler 类 | ⚠️ **必须继承 `BaseTriggerHandler`**，不能 `ListeningTriggerHandler` |
| 4 | `TriggerHandlerRegistry` +1 行 | 现 25 个 |
| 5 | 三语文案 ×3 | — |
| 6 | 图标 drawable | — |
| **7** | ⚠️ **`HookConditionWire` 的 schema** | 若过滤维度不是「包名」，**必须改 schema**（两端共享） |
| **8** | ⚠️ **订阅汇总** | 各 Handler 各算各的 → 谁后跑谁赢（**§7.4 反模式 2**；现状只有 1 个 hook 消费者，故尚未显现） |
| **9** | ⚠️ **挂载点**（`HookSource`） | mount / applyConditions / remountAfterHotReload |
| 10 | `FORK.md` 登记 | — |
| **11** | ⚠️ **测试**（S7 评审补） | 照同构补 `XxxTriggerModuleTest`（声明体检）+ `XxxTriggerHandlerTest`（触发语义）—— 现有 hook 触发器两个都有，漏了就不是 10 处 |
| **12** | ⚠️ **引用面白名单登记**（S7 评审补） | `WireLayerPurityTest` 按白名单扫**整个 `xposed/` 包**；新 source 若引入新的 `android.*` 引用**必须登记**，否则测试变红 |
| **13** | ⚠️ **proguard**（S7 评审补） | 若新类**按名字被外部找到**（`java_init.list` / AIDL / provider），必须 keep —— 否则 **release 下静默不加载**（仓库已踩过） |

> **注**：注册数 28 vs 25 的差集是 **`ManualTriggerModule` / `ReceiveShareTriggerModule` / `VoiceTriggerModule`** ——
> **均为设计内的例外**（手动 = 用户点；分享 = Intent；语音 = 由 `VoiceTriggerService` 自己 `new`，
> 见 `VoiceTriggerService.kt:234`），**不是漏注册**。

### 7.2b 加一个 **③ capability** 要改哪几处（S7 补）

> §0.2 写着「要加一个新触发器 / **新能力** → §6」，而 §7.2 只兑现了前半句。
> ③ 已是与 ② 并列的一等生命周期，**必须有等价表**。
> ⚠️ 同样**只数功能改动**；测试与构建配置见行 9–10。

| # | 要改 | 说明 |
|---|---|---|
| 1 | **hook 侧 handler**（`app/.../xposed/capabilities/`，脚本类在 `xposed/script/`） | 纯执行体；⚠️ **不得引用 App 侧重类**（§5.6）；⚠️ `JsConsole` 若在 `core/` 包下命中 `FORBIDDEN_APP_PACKAGES`，**必须移植一份到 `xposed/script/`** |
| 2 | **hook 侧 capability 注册表** +1 行 | `name → handler`。⚠️ **未知 name 必须显式报错**（§3.2），不能静默 |
| 3 | **App 侧 capability 注册表** +1 行 | `Capability(name, risk, fallback)`（§6.2）—— `fallback == null` ⇒ 独占型。⚠️ **命名落点未定**（与既有 `XposedCapability` 冲突），见 §6.2 的消歧框 |
| 4 | ⚠️ **结果大小上限**的声明 | §3.6：这个 capability 的响应上限是多少、要不要分页 |
| 5 | ⚠️ **超时时长** | 逐 capability 给 `timeout_ms`（§5.2）—— 差异很大 |
| 6 | **调用点**（工作流模块 / JS 脚本 / 数据源） | 三者是**消费方式**，不是注册处（§2.1 的暴露面） |
| 7 | 三语文案（若对用户可见） | 数据源 / 模块才需要 |
| 8 | `FORK.md` 登记 | — |
| **9** | ⚠️ **测试**（S7 评审补） | ⚠️ **③ 的 handler 跑在 system_server 的 LSPosed ClassLoader 里，App 侧单测跑不到** —— 需要一条**新的验证路径**（探针式真机验证 / hook 侧纯函数抽出）。见 §10 未决项 19 |
| **10** | ⚠️ **改 AIDL + 两端**（S7 评审补） | 若最终签名不是现成的 `int ping()`，则**这一步本身**要算进改动面（且 AIDL 变更**无版本协商机制**，两端必须同时升级）—— 见 §10 未决项 8 |

**与 ② 的对照**（**这是本表的价值**）：

| | ② 订阅（13 处） | ③ 调用（10 处） |
|---|---|---|
| **注册处** | 2 处（`ModuleRegistry` / `TriggerHandlerRegistry`） | **2 处**（hook 侧 + App 侧能力表），但**跨进程各一处** |
| **hook 侧要改的** | `HookConditionWire` schema + 订阅汇总 + 挂载点（3 处） | handler + capability 注册 + 大小/超时声明（4 处） |
| **共同项** | 三语文案 / 图标 / 测试 / `FORK.md` | 三语文案 / 调用点 / 测试 / `FORK.md` |
| ⚠️ **失败形态** | **静默**（用户看不到） | **显式**（步骤报错）—— 见 §6.2 |

> ⚠️ **③ 的注册分散在两个进程里**，而 ② 的两处注册都在 App 侧 ——
> 这是 §7.2 那句「加新能力的真实代价是 2 处注册」在**多进程**下的真实含义：
> 两处**不能一起改、不能一起测**，且 hook 侧那半要等热更新换代才生效（§3.5）。

### 7.3 三类暴露面的成熟度对照

| | 接口 | 注册 | 规范 | 体检 | 判定 |
|---|---|---|---|---|---|
| **step 模块** | ✅ `ActionModule` 完整 | ✅ `ModuleRegistry` | ✅ `AGENTS.md` 8 步 | ⚠️ 部分 | **成熟** |
| **触发器** | ⚠️ 有接口**无类型** | ✅ 两处 | ❌ **没有** | ❌ **没有** | ⚠️ **防错能力缺失** |
| **数据源** | ❌ 无抽象 | ❌ 无 | ❌ 无 | ❌ 无 | ❌ **未建立** |

**关键区分**（S1 审查时纠正过）：

> 触发器**扩展机制**是成熟的（加一个是 10 处机械改动、路径清晰），
> 缺的是**正确性保障层** —— **错配、漏注册、口径分裂全都静默**。

**触发器体系缺的三层**（**不属本文**，见 §7.5）：

| 层 | 内容 |
|---|---|
| **类型** | 让「这是一个触发器」在类型上可识别（现状：`id` 前缀 + `categoryId` 字符串，**且口径有三套**） |
| **通用契约** | 框架该收上去的（每次增删都通知、源生命周期） |
| **体检** | 照 `RoutingTableConsistencyTest` 的形态扫全部触发器 |

> ⚠️ **顺序**：**类型是前提** —— 有类型才能枚举，能枚举才能体检。

### 7.4 ⭐ 反模式清单（**本文的核心资产**）

以下是本仓库**反复踩过**的模式，**不是偶发 bug**：

| # | 反模式 | 表现 | 出处 |
|---|---|---|---|
| 1 | **共享状态用单槽位**（而非按 key 的注册表） | 「新加的能用，原来那个静默失效」 | `onConnected` |
| 2 | **全量替换语义，但各消费者只算自己那份** | 谁后跑谁赢，双向且不确定 | `conditions` |
| 3 | **绕过共享编解码器另行解析** | 格式一变就漏改，表现是「配置都对但不生效」 | `HookRuntime.parseTopics` |
| 4 | **约定的类型配对用 `as?` 静默退化** | 类型错配不抛不报，下游拿到空值 | 触发数据载荷 |
| 5 | **约束只写在注释里、类型系统不表达** | 「必须继承 X 不能继承 Y」写三处注释 | `BaseTriggerHandler` |
| 6 | **写了调用点注释，但没有调用点** | 纯函数单测全绿，集成点缺失 | `CoreDexFingerprint` 教训 |
| **7** | ⚠️ **昂贵的前置计算排在早退之前** | 空闲时白付反射成本 | `ActivityChangedSource:179`（S4） |
| **8** | ⚠️ **用「状态位」替代运行时判断** | 状态是**采样**的，`READY` 也可能失败 | §6.3（S5） |
| **9** | ⚠️ **把「某用例的性质」当成「机制的性质」** | 「③ 必须有降级」就是从快捷方式那一个用例泛化来的 | §6.2 的订正（S5） |
| **10** | ⚠️ **把「传输层的语义」当成「业务层的语义」** | 读一眼 AOSP 的 oneway 串行就推出「慢 handler 挡后续」，而它说的是「`onTransact` 返回」 | §3.4 的订正（S7） |

> **第 9/10 条都是本轮讨论中我们自己犯的错** —— 它们值得单列，因为这类错误**看起来特别合理**：
> · 从「快捷方式需要降级」直接推出「③ 必须有降级」，中间少了一步
>   「**这个性质属于用例，还是属于机制？**」
> · 从「oneway 调用串行」直接推出「慢 handler 挡后续」，中间少了一步
>   「**这句话的主语是 binder 还是我的 handler？**」
>
> **共同的形态：把某个层次的事实，平移到另一个层次用。**

### 7.5 📌 指针：触发器体系的改动设计（**不属本文**）

**本文讨论中连带发现**：触发器体系缺类型、缺通用契约、缺体检（§7.3），
**影响全部 28 个触发器**，与 Xposed 通道无关。

| 项 | 落点 |
|---|---|
| **现状**（只说明现状，**不写改动**） | `docs/fork/surveys/trigger-system-overview.md` —— **本次不动** |
| **改动设计** | ⚠️ **新建 `docs/fork/trigger-system-rearchitecture.md`**（照 `chat-agent-rearchitecture.md` 的范式）—— **本次不写** |
| **本次只做** | 留本指针 + 在 `FORK.md` 登记待办 |

**为什么不写进 survey**：`surveys/` 是**现状说明**，只说明现状。
把「要改什么」写进去会变成「现状 + 计划」杂糅，下次读的人分不清哪句是事实。

---

## 8. 现有缺陷清单（**当前代码的问题，不是 V2.0 引入的**）

> ⚠️ **本节记录的是现状缺陷。** 写进设计文档**不等于**它们被修掉 ——
> 它们需要在某个时刻变成实际改动。
>
> 📌 **缺陷 1–13 来自 S7 复核；14–19 来自 2026-09-28 的两轮独立源码评审**
> （评审方式：全仓 grep + 逐行读码，**不采信任何文档**）；
> **19（升级）与 20 由 2026-09-29 真机实测确认**（小米 MIX Fold 3 / Android 17）。

| # | 缺陷 | 证据 | 影响 |
|---|---|---|---|
| 1 | ✅ **`onConnected` 曾是单槽位**（**2026-09-29 已修**） | `HookChannelController.kt:185` 注释自述「后注册的覆盖先注册的」 | 第二个 hook 触发器会挤掉第一个的「重连后重下发」；`stop()` 时 `setOnConnectedListener(null)` 会**误伤其他消费者** |
| 2 | **token 换代有静默丢弃窗口** | 新 token 生成（`:129`）后、重下发完成前，hook 层用旧 token 发的事件被**静默丢弃**（`:247`，只 warn） | 窗口靠「`TriggerService` 比 hook 连接早 1.6 秒」这个**巧合**关闭，不是设计保证；日志无法区分「攻击」与「换代延迟」 |
| 3 | ✅ **`deathRecipient` 曾是死字段**（**2026-09-29 已修**） | `BinderTransport.kt:161-162` 声明后从未 `linkToDeath` | App 侧无死亡检测 |
| 4 | **`ping()` 的 AIDL 注释承诺了不存在的机制** | `IHookCallback.aidl:25-34` 写「App 侧周期性调用」，实际**零调用者** | 误导后续实现者以为活性问题已被覆盖 |
| 5 | **App 侧无连接身份** | §4.2 | 多连接在 App 侧只表现为「覆盖」 |
| 6 | **同进程可能多条连接** | §4.3 | 与缺陷 2、5 叠加 |
| 7 | **hook 回调的早退排在三次反射之后** | `ActivityChangedSource.kt:179-184` | 空闲时每次 Activity 切换仍付 3 次反射；见 §5.4 |
| 8 | **反射句柄未缓存** | `ActivityChangedSource.kt:162,202` | 每次回调都 `getDeclaredMethod` / `getDeclaredField`；见 §5.4 |
| 9 | **「无触发器时卸下 hook」的意图未实现** | `HookRuntime.stop()` 无生产调用者 | 注释写着意图，实现不存在。⚠️ **S4 决定不实现**（见 §5.4）—— 改为规范早退。**S7 复核：比这更严重，见缺陷 10** |
| **10** | ⚠️ **缺陷 9 是「两层死链」** | `HookRuntime.stop()` ← `VFlowHookEntry.stopChannel()`（`:305`）**本身也无人调用** | 所以「无触发器时 unmount」不是「意图未实现」，是**整条链从未接上**。⚠️ 与缺陷 13 叠加后更危险 |
| **11** | ⚠️ **`currentProtocolVersion()` 是死函数** | `HookRuntime.kt:400`，全仓**零调用者** —— `BinderTransport.ping()`（`:181`）直接用了 `EventEnvelopeCodec.PROTOCOL_VERSION` | 又一个「统一出口没人走」——**正是 §7.4 反模式 3**。同类：`HookRuntime.kt:403` 的 `IBinder?.isAlive()` 也零调用者 |
| **12** | ✅ ⚠️ **第三个单槽位**（**2026-09-29 已修**） | `BinderTransport.kt:129-135` 的 `onConnectedSink` | 文档此前只数了 App 侧两处（`eventSinks` 已修 / `onConnected` 未修），**hook 侧这处同样是单槽位覆盖** |
| **13** | ✅ ⚠️ **`onUnbind` 会清掉「新」连接**（**2026-09-29 已修**；**已实测确认为真实竞态**） | `HookChannelService.kt:112-116` → `HookChannelController.kt:150-156`（`callback = null; token = ""`） | 解绑到 0 客户端时无条件清空。若旧连接的 unbind 事件在**新连接 `registerCallback` 之后**才到达 ⇒ hook 层 `host != null`（自认连着）而 App 侧 `callback == null` ⇒ **事件全丢**。这是 §4.2「无连接身份」的**一条具体可测后果** |
| **14** | ⚠️⚠️ **丢弃计数链路从未闭合** | `EventQueue.drainDropped()`（`wire/EventQueue.kt:75`）是**唯一**清零入口，**零生产调用者**（只有 `EventQueueTest`）；`HookRuntime.kt:252` 塞进信封的是 `peekDropped()`（**累计值**）；`HookChannelController.kt:264-271` 只在 `>0` 时赋值、**从不复位** | ⚠️ **一次丢弃 ⇒ 永久「已丢弃 N 条」**：首页横幅（`HomeScreen.kt:381` `if (xposedDroppedCount > 0)`）**驻留到 App 进程被杀**；且此后**每条事件**都刷一条 warning。与 §6.4「不要误导用户」的立意直接冲突。**对照：Core 的 logcat 路径有真实调用者**（`LogcatStreamWrapper.kt:503`）⇒ 同一模式只在 Xposed 侧漏了。⚠️ `HookRuntime.kt:250` 的注释逐字写着「清零由发送成功后的 `drainDropped` 负责」——**反模式 6 的又一实例，而纯函数单测全绿恰是它没被发现的原因** |
| **15** | ⚠️ **`HookLog.kt:16` 引用一个不存在的测试** | 注释称约束由 `WireLayerPurityTest` **与 `HookPackagePurityTest`** 锁住；全仓**无**该文件 | 断言了一条不存在的保障 ⇒ 后续实现者以为「hook 层不引用 App 侧类」有双保险。实际只有 `WireLayerPurityTest` |
| **16** | ⚠️ **`PermissionManager` 注释与实现相反** | `permissions/PermissionManager.kt:203-206` / `:520` 仍写「判据是**『曾经成功连上过』**」；而 `XposedCapability.isGranted`（`core/xposed/XposedCapability.kt:55-56`）读的是 `frameworkConnected` —— **实时** | **反模式 5 的同型**：后人读 `PermissionManager` 会得到与实现相反的结论，可能照注释把持久化判据恢复回来 |
| **17** | **`appContext` / `attach()` 是残留死字段** | `HookChannelController.kt:117` 声明、`:121` 写入，**全类零读取**；唯一调用点 `HookChannelService.kt:101` | 是 P4「判据实时化」时删掉 `markConnected()` 后的遗留（其注释还写着「用于记录『曾经连上过』」） |
| **18** | ✅ ⚠️ **三处测试曾是空断言**（**2026-09-29 已修**） | `test/.../core/xposed/HookChannelControllerTest.kt`：`registerSink is idempotent per topic`（`:204-212`）**无任何 assert**；`lastReportedDroppedCount starts at zero`（`:215-218`）只断言 `>= 0L`（恒真）；`setEventSink null clears the consumer`（`:113-119`）无实质断言 | ⚠️ 缺陷 8 的修复**正依赖** `registerSink` 的幂等语义，而锁它的用例是空的 ⇒ **改回 bug 版本不会变红**。同文件的分发组测试（`:128-201`）用了 `injectTokenForTest`、是合格的对照 |
| **19** | ⚠️⚠️ **热更新后 hook 累积，每次 +1，永不回收**（真机已验） | `ActivityChangedSource.kt:296` `old.replaceHook { … }` 的**返回值被丢弃**，`:306-309` 随后置 `handle = null` —— 而注释写「接管成功的句柄**现在归本代际持有**」（**注释与代码相反**） | 本代际 `handle == null` ⇒ `mount()` 的幂等守卫（`:63-68`）失效；而 `remountSources()`（每次连接建立都调）是「先 unmount（此刻 no-op）再挂」⇒ **同一方法上多挂一个，且旧代际的句柄再也拿不到 ⇒ 永不回收**。<br/>⭐ **2026-09-29 真机实测**：`旧 hook 句柄数` 从重启后的 **1** 单调涨到 **2→3→4→5→6**（每次热更新 **+1**，`replaceHook` 打印次数与之一致）。<br/>⚠️ **而 libxposed 是【链式叠加】不是幂等**（§8.2-1 已验）⇒ **N 个 hook = 每次 Activity 切换触发 N 次完整回调**：同一操作 N=5 → **20** 次、N=6 → **24** 次（严格按 N 比例）。即 **N 次反射取值 + N 次 extras 序列化 + N 条事件**。<br/>表现：默认 1000ms 冷却把重复事件挡在 App 侧 ⇒ **症状被掩盖**；冷却设 0 时「进一次 App 跑 N 遍工作流」。<br/>连带：`HookRuntime.stop()` 的 unmount 分支在本代际**变成 no-op**。<br/>⇒ **开发期每装一次包就放大一档**，长期不重启会持续劣化。**重启设备可清零**（重新注入 = 1 个）。修法见 §8.1-2（**顺手 `unhook()` 掉历史累积可自我修复**）。<br/>✅ **2026-09-29 已修复并真机验证**：`replaceHook()` 的返回值存进 `handle`，**只接手 1 个、其余全部 `unhook()`**。实测：装包后 `旧 hook 句柄数` **7 → 保留 1 + 清掉 6**；其后连续 5 次热更新均稳定读到 **1**（单调增长终止）。 |
| **20** | ⚠️ **重连时重复 `bindService` + ConnectionRecord 泄漏**（真机已验） | `BinderTransport.scheduleReconnect`：`doBind()` 后立刻查 `if (host != null) return`，而 `host` 由 `onServiceConnected` **异步**（post 到 system_server 主线程）设置 ⇒ 循环会**再 bind 一次** | 实测每轮重连 **2 次 `bindService()`**（日志：`⟳ 尝试重连（1000ms）→ 提交` → `★★★★ bindService 成功` → `⟳ 尝试重连（2000ms）→ 又提交`），而只收到 **1 次** `onServiceConnected`。<br/>⚠️ **`ConnectionRecord` 只增不减**：`dumpsys activity services` 里 3 → 5 → …，且**全部共用同一个 `ServiceConnection`**（同一个 `LoadedApk$ServiceDispatcher$InnerConnection@…`）—— 因为 `unbindQuietly()` **只在 `stop()` 里调**，而重连路径**从不调** `stop()`。<br/>⇒ 每次 App 被杀/重装都在 **system_server 的 AMS 里**留下永不释放的连接记录。<br/>⚠️ **同时要诚实说明**：**没有**连带产生重复 `registerCallback`（实测 2 次 bind → 1 次回调）、没有二次 remount、没有二次换 token —— 与缺陷 19 不是同一条路径。<br/>**最小改法**：`doBind()` 后等一个短超时再判 `host`，或把「重连成功」的判定改由 `onServiceConnected` 回调驱动（而不是轮询 `host`）。<br/>⚠️ **2026-09-29 修复时发现它有【两半】，只修一半不够**：<br/>**(a) 重复 bind** —— `doBind()` 后加 `awaitConnected(AWAIT_TIMEOUT_MS)`（有界轮询，步长 100ms、**上限 5 秒**）再判成败。⚠️ 等待窗口必须**与退避值解耦** —— 首次退避只有 1 秒，若拿它当窗口，「设备刚开机时落地慢于 1 秒」仍会被误判为失败并再 bind 一次（等于把 bug 以更低频率留着）；<br/>**(b) 只 bind 不 unbind** —— 修完 (a) 后 `ConnectionRecord` **仍在 +1/轮**（实测 distinct 7→8→9），因为 `unbindQuietly()` 只在 `stop()` 里调 ⇒ **重新 bind 前必须先 `unbindQuietly()`**。<br/>✅ **两半都修并真机验证**：每轮 `bindService()` 提交次数 **2 → 1**；`ConnectionRecord` 连续 3 轮断连 **6 → 6 → 6 → 6 完全持平**（且首次重连时从 7 掉到 6 —— 那条陈旧记录也被释放了）。且未引入额外延迟（提交 → `onServiceConnected` → 「重连成功」实测 **100 / 201 / 200ms**）。 |

> **另有一处不对称**：`eventSinks` 已改为按 topic 的注册表（`:91`），
> 而 `onConnected`（缺陷 1）仍是单槽位 —— **同一个缺陷只修了一半**。
> ⚠️ **S7 复核：不是「一半」，是三处里的两处** —— 见缺陷 12（hook 侧的 `onConnectedSink`）。
>
> **缺陷 10 与 13 叠加**：`HookRuntime.stop()` 的整条链没接上 ⇒
> 即使将来有人接上它，缺陷 13 会让「断言开连接」变成清掉**别人**的连接。
> 修缺陷 9 之前必须先修 §4.2 的连接身份。

### 8.1 修复清单（**最小改法**，按可执行性排序）

> 2026-09-28 两轮评审产出。**每条都给了最小改法**，可直接排期。
> 「依赖」列非空者表示**必须等某个实验或某个前置改动**，不要提前动手。
>
> ✅ **2026-09-29 已完成 8 条**（真机 + 单测 + **逐条反证**）：**19 / 20**，以及 §8.3 推荐的 A 组 **1 / 3 / 12 / 13** 与 **18**，
> 均按本表的最小改法实施（缺陷 20 实施时发现它有**两半**，见该行注）。

| # | 缺陷 | 最小改法 | 依赖 |
|---|---|---|---|
| 1 | 14 丢弃计数 | 二选一并写进 §5.3：**(a)** 语义 = 「自上次成功发送以来」⇒ `drainLoop` 发送成功后调 `queue.drainDropped()`，同步改 `HookRuntime.kt:250` 注释与 `EventQueueTest`；**(b)** 语义 = 「自连接以来累计」⇒ App 侧带 `epoch` 去重 + UI 加「最近一次丢弃时间」以过期隐藏。**推荐 (a)**（与 logcat 路径一致）。**另加一条源码扫描型测试**断言 `drainDropped()` 有生产调用者（照 `CoreDexFingerprint` 的集成点测试形态） | — |
| 2 | ✅ **19 热更新累积 hook（已完成）** | ⚠️ **已验：框架叠加**（§8.2-1）⇒ 不只是「存返回值」，**还要顺手清掉历史累积**：<br/>```kotlin\nvar taken = false\nfor (old in oldHandles) {\n    val exec = old.executable ?: continue\n    if (exec.name != METHOD_NAME) continue\n    if (!taken) { handle = old.replaceHook { c -> onActivityResumed(c, runtime) }; taken = true }\n    else old.unhook()      // ★ 清掉历史累积（N → 1），自我修复\n}\n```<br/>即：**只接手 1 个、其余全部 `unhook()`**。这样每次热更新都回落到 1，而不是 +1。再补单测「连续两次 remount 只产生一个 hook」 | ✅ **2026-09-29 已实施并真机验证**（7 → 保留 1 + 清 6；其后 5 次热更新稳定 = 1） |
| 3 | 15 死引用 | 删掉 `HookLog.kt:16` 的 `HookPackagePurityTest`，或补建该测试 | — |
| 4 | 16 陈旧注释 | 改写 `PermissionManager.kt:203-206` / `:520` 为「判据是**框架此刻连着**（实时），非持久化」 | — |
| 5 | 17 死字段 | 删 `appContext` + `attach()` + `HookChannelService.kt:101` 调用点；或注明保留理由 | — |
| 6 | ✅ 18 空断言（**已完成**） | 三处补真实断言：`registerSink` 幂等（同 topic 二次注册后 sink 仍是后者）、`lastReportedDroppedCount` 初值精确为 `0`、注销后该 topic 无消费者 | — |
| 7 | 11 死函数 | 决策并落地：**推荐接线** —— 让 `BinderTransport.ping()` 走 `currentProtocolVersion()`，否则「统一出口」永远没人走。`IBinder?.isAlive()` 无用途则删 | — |
| 8 | 4 `ping()` 契约 | 第一步只改文档（§3.1/§6.3 标「签名冲突，最终签名待定」）；实现随 ③ 一起做 | ⚠️ 见 §10-8 |
| 9 | 7 / 8 早退与反射缓存 | 把 `conditions.isEmpty` 判断提到 `chain.getArg(0)` 之前；`Method`/`Field` 缓存到**实例字段**（不可静态） | — |
| 10 | ✅ 3 `deathRecipient`（**已完成**） | 要么 `linkToDeath`（得到 App 侧死亡检测），要么删字段 | — |

### 8.2 ✅ 三处「先验再改」——**已于 2026-09-29 真机验证完毕**

> 小米 MIX Fold 3 / Android 17 / 澎湃。**三条全部有结论**，结论均已回写到正文各节。

| # | 原待验项 | ✅ 结论 | 实测方式 |
|---|---|---|---|
| **1** | libxposed 102 对「同一方法重复 `hook()`」是叠加还是幂等 | ⭐ **链式叠加** —— **同一方法上的 N 个 hook ⇒ 每次 resume 触发 N 次完整回调** | 临时在 `onActivityResumed` 最前加计数日志，**同一操作 A/B**：N=5 → **20** 条、N=6 → **24** 条（比值 1.2 = 6/5）；且 `ActivityChangedSource` 实例数 = **1**（同一源实例挂 N 个 hook） |
| **2** | `android:permission` 能否拦住 **uid 2000** | ✅ **能拦住** —— 规则是 `uid == 1000`（及 0），**不是「uid < 10000」** | `am startservice`（uid 2000）：受保护的 `HookChannelService` → `Requires permission …HOOK_CONTROL`；无保护的 `TriggerService`（阳性对照）→ 成功；uid 1000 在同一 Service 上 `dumpsys` 显示 `c:android` bind 成功 |
| **3** | `ServiceConnection` 回调是否必在主线程 | ✅ **是**，且**比预期更广** —— `registerCallback`（同步 IPC）+ `loadClass` + `getDeclaredMethods` + `hook()` **全在主线程** | logcat `threadtime` 的 `pid tid` 两列：`onServiceDisconnected` / `bindService 成功` / `registerCallback 成功` / `hook 点已找到` / `✅ hook 已挂上` **全部 `pid == tid`**（= system_server 主线程）。热态实测耗时 ≈ **7ms** |

> ⚠️ **第 1 条的连带结论**：既然框架叠加，则**缺陷 19 不是「卸不干净」，而是「事件与开销按 N 倍放大且单调增长」** ——
> 见 §8 缺陷 19 的实测数据。
>
> ⚠️ **第 2 条纠正了仓库的一处自相矛盾**：`FORK.md:268` 曾据「uid < 10000 豁免」把 #15 **「结案」**，
> 而 `docs/fork/xposed-channel-design.md:2234` 在同一仓库里写着「❌ 取不到有效结论」。
> 该规则的来源实验（`checkPermission(自己pid, 自己uid)`）恒 GRANTED 的真因是 AOSP 的
> `if (pid == MY_PID) return GRANTED;` 便捷分支 —— **拿已被判无效的实验去支撑一条平台规则**，
> 结果这条规则本身也是错的。**已修 `FORK.md`。**
>
> ⚠️ **第 3 条的残留未知**：上面测的是**热态**（类已加载）。**设备重启后 system_server 首次挂载**
> 才是真正加载类的那一次，耗时未测 —— 那才是这条风险的尾部。

### 8.3 修复优先级：**哪些必须先于 ③ 的开发**

> ⚠️ **本节是判断，不是文档此前已有的内容**（§8.1 只给了每条的改法，没给排序）。
> 判据只有一条：**不修它，③ 的设计就落不了地**，或者③ 上线后故障会**伪装成「能力实现有 bug」**。

#### A 组 · **必须先修**（否则 ③ 的地基是空的）

| 缺陷 | 为什么卡在架构前面 |
|---|---|
| **4** `ping()` 契约自相矛盾 | `CapabilityPresence` 的**唯一**数据源就是它，而它是 §6.5「旧 hook 层没有某 capability ⇒ oneway 静默丢弃」的**唯一防线**。签名不定 ⇒ ③ 的「调用前判断」是空的。（连带 §10-8） |
| **13** `onUnbind` 无条件清 `callback`+`token` | ② 现在只影响事件；③ 上线后：§5.2 定了「**断连立即唤醒全部 waiter 回 `error`**」，一次误清的 unbind 会让在途 waiter **全部挂到超时**；且 §3.3 要求「响应信封带 token」，token 被清后**合法的 `resolve` 也会被拒**。**⚠️ 已实测确认这是真实竞态，不是理论风险** —— 见下方「13 的实测时序」。 |
| **3** `deathRecipient` 死字段 | 与 13 同族：③ 需要一个**可靠**的断开信号来唤醒 waiter，而现在只有 `onServiceDisconnected`（已知**不一定触发**）+ 15 秒巡检。字段声明了却从没 `linkToDeath`。 |
| **1 + 12** 两处单槽位（`onConnected` / `onConnectedSink`） | 只要再加**任何**一个 hook 消费者，两者就会互相挤掉；而 §7.2 把「加一个 hook 消费者」定义为**常规操作**。不先修 ⇒ **第二个 hook 触发器一加就静默失效**。 |

#### 13 的实测时序（2026-09-29，MIX Fold 3）

```
01:35:38.855  ActivityManager: unbindService … conn=…InnerConnection@27a25d3
              callers: … ManagedServices$1.onBindingDied …
01:35:39.858  ActivityManager: Start proc 6571:com.chaomixian.vflow
              for bound-service {HookChannelService} caller=android
01:35:40.002  HookChannelService: onBind（新进程）
01:35:40.007  HookChannelService: hook 层已连接：callerUid=1000 callerPid=3040
```

⇒ **旧连接的 unbind 比新连接建立早约 1.1 秒，但它是异步投递的** ——
一旦投递顺序反向（`onUnbind` 落到 `registerCallback` 之后），
新连接的 `callback` 与 `token` 会被无条件清空。**这在部署流程里是常态时序**
（重装 APK ⇒ 旧进程被杀 ⇒ 新进程接管），不是边角场景。

> 📌 **顺带记录一处「我自己的改动不要放大它」**：缺陷 20 的修复加了一次
> `unbindQuietly()`（重连前释放旧绑定）。已核实该调用**不会**给 App 侧带来**新的**
> `onUnbind`：只有当服务已有绑定→绑定数归零才投递，而重连场景下旧绑定早已断开。
> **但改动 13 时必须回头确认这一点不成立**（修完后 unbind 会真的落到 `onUnbind`）。

#### B 组 · **决策必须先定**（不动代码，是定口径）

| 未决项 | 为什么现在定 |
|---|---|
| **§10-9** 丢弃计数语义（delta vs 累计） | 它决定**信封字段**语义。③ 要往信封里加 `error.code` 等字段，**一次改完比改两遍好**；顺带把缺陷 14 的接线一起做。 |
| **§10-10** 权限判据取 L1 还是 `L0 ∪ L1` | 决定「框架在、通道断」这一格；§6.4 的失败分类必须与它对齐（否则又是「两个消费点口径不同」）。 |
| **§10-12** ③ 的错误码枚举 | §6.4 的四类失败没有它就是**匹配中文文案**。 |

#### C 组 · **同批顺手**（不阻断，但拖着会变贵）

- **11** `currentProtocolVersion()` 死函数 —— 与缺陷 4 **是同一件事**（统一出口没人走），修 4 时顺手接线。
- **7 / 8** 早退上移 + 反射缓存 —— 19 修完后不再是 N 倍，但 ③ 会在同一回调线程上**再加工作**。
- **14** 丢弃计数接线 —— 用户可见 bug；与 B 组口径一起改最省事。
- **18** 三处空断言 —— 其中 `registerSink` 幂等那例，**加第二个消费者之前**补上更稳。
- **15 / 16 / 17** —— 纯清理（死引用 / 陈旧注释 / 死字段），零风险、随时可做。

#### D 组 · **与本次架构无关，别在这轮做**

| 缺陷 | 原因 |
|---|---|
| **9 / 10** `HookRuntime.stop()` 两层死链 | §5.4 已决定**不做空闲卸载**；③ 的单次调用不需要它。（但 unmount 语义要与 §5.4 对齐，那处契约冲突已记录） |
| **2** token 换代静默窗口 | 是「偶发丢事件」；③ 有配对表超时兜底。定 §10-9 口径时可顺带决定要不要加 `epoch`。 |
| **5 / 6** 无连接身份 / 热更新换代多连接 | §4.4 已定：**多进程 hook 落地前**才必须做。③ 是单连接，不用先做。 |

#### ✅ 已落地（2026-09-29）

**A 组四条 + 缺陷 18 已修**（都在 fork 新增文件内，改动面小）：

| 缺陷 | 修法 | 验证 |
|---|---|---|
| **13** | `onCallbackUnregistered(which)` 带**身份**：与当前 `callback` 不是同一个 binder 时**忽略并留日志**；`HookChannelService` 记 `lastCallback` 并传入。⚠️ 比较不能直接 `===`（`asInterface` 对同一 binder 可能返回新代理）⇒ 比 `asBinder()` | 单测 1 例；**实测确认为真实竞态**（旧 unbind 比新连接早 1.1 秒、异步投递） |
| **3** | `onServiceConnected` 里 `linkToDeath`，`unbindQuietly()` 里 `unlinkToDeath`（⚠️ **顺序**：先解回执再 unbind，否则 `host` 已是 null 拿不到 binder）；挂之前先解旧的，避免「只增不减」 | 真机：`💀 App 侧 binder 死亡（deathRecipient）—— 走重连` 在 `bindService` **之前**触发 |
| **1** | `onConnected` 单槽位 → **按 key 的注册表**（`ConcurrentHashMap`）+ `removeOnConnectedListener(key)`；逐个通知、一个抛异常不影响其他 | 单测 3 例 + **反证**（退化成 `clear()` ⇒ 变红） |
| **12** | `BinderTransport.onConnectedSink` 同样改注册表（`onConnected(key, sink)` / `removeOnConnected(key)`） | 随 1 同批；`VFlowHookEntry` 传 `"VFlowHookEntry.remount"` |
| **18** | 三处空断言补真断言：`unregisterSink` 只摘自己的 topic、`registerSink` 同 topic **只留最后一个且只处理一次**、`lastReportedDroppedCount` 初值**精确为 0** | 单测 + **逐条反证**（5 条全变红；`registerSink` 那条要用「不替换而累积」的写法才逼得出来 —— 直接重复赋值无法表达） |

⚠️ **一处为了让测试能跑到而新增的接缝**：`notifyOnConnected()` 提为 `internal` ——
`IHookCallback.Stub` 继承 `android.os.Binder`，**纯 JVM 测试里构造不出来**
（`attachInterface` 未 mock），所以测试无法经 `onCallbackRegistered` 走这条路径。
与既有的 `injectTokenForTest` 是同一种接缝，已在源码注释里写明理由。

#### 推荐顺序

```
① 定 B 组三个口径        —— 不动代码，成本最低，且它们决定后续怎么写
② 修 A 组四条            —— 都在 fork 新增文件内；1+12 与 13 同族可一起改
③ 顺带 C 组              —— 11 随 4、14 随 B 组口径、18 补断言
④ 然后才是 ③ 的开发
```

> ⚠️ **为什么地基必须先夯**：A 组四条全是**「不修就会以静默失效的形式暴露」**。
> ③ 上线后若出问题，表现是「有时能用有时不能用」——
> 那是最难归因的一类，且会把排查**引向「能力实现有 bug」的错误方向**。

---

## 9. 边界：已知的、暂不支持的能力

| 能力 | 为什么不支持 | 若要支持需要什么 |
|---|---|---|
| **hook 表达式 @ 目标 App 进程**（可读写宿主对象，**信息面 > root**） | **入口无法收敛** —— 每个 hook 点要配一段表达式，与「入口收敛」的安全原则冲突；安全模型完全不同 | ① **`target` 维度** —— ⚠️ 协议里**目前没有**这个字段，所谓「预留」需要一个具体形式（如 capability 名自带命名空间前缀），否则只是口头预留 ② **独立的安全/审计设计** ③ 传输路径选型（见 §10）+ **App 侧连接身份**（§4.4） |
| **不经 Xposed、以 shell 身份直连 `IShortcutService`** | ✅ **已在源码层否证** —— `verifyCaller` 的唯一豁免是 `isCallerSystem()`（uid 1000），shell(2000) 跨包调 `getShortcuts` 必抛 `SecurityException`；AIDL 全部 24 个方法无一能跨包读出 `ShortcutInfo` 对象 | — （**已结案，不要再作为候选重新引入**） |

> 📌 **「AI 直接配置快捷方式步骤」不在本表** —— 它**与 Xposed 无关**，
> 是 §7.3 的「**数据源未建立**」的直接后果（`launchCommand` 在 AI 工具 schema 里可见且必填，
> 但唯一产出路径是 UI 选择器 ⇒ AI 生成该步骤必然失败）。
> 修它的方向是**补一个可被 AI 调用的无损数据源**，与本文讨论的通道无关 ——
> 详见 `surveys/shortcut-system-overview.md` §7.1。

---

## 10. 未决项

| # | 项 | 证据等级 | 何时定 |
|---|---|---|---|
| 1 | ~~**版本不符时 ③ 是否沿用「只 warn」**~~ | 代码事实 | ✅ **S7 收掉** —— 沿用（§3.5） |
| 2 | ~~**token 是否进 ③ 信封**~~ | 代码事实 | ✅ **S7 收掉** —— **请求与响应两个信封都带**（§3.3） |
| 3 | ~~**`ping()` 的用途**~~ | 推断 | ✅ **S5 收掉** —— 连接期能力交换 |
| 4 | **分级背压的具体策略**（采样怎么采） | 【未验证】 | **第一个高频源实现时** |
| 5 | **第三个执行位置的传输路径**（每 App 一条连接 / 经 system_server 中转） | 【未验证】 | 实现时。⚠️ **前置依赖：App 侧连接身份（§4.4）—— 无论选哪种方案都躲不掉** |
| 6 | **能否同时 hook system_server 与目标 App**（`scope.list` 现为 `system`） | 【未验证】 | 探针 |
| 7 | **「订阅从空变非空」时能否可靠重挂**（尤其热更新后） | 【未验证】 | ⚠️ **S4 已决定不做空闲卸载，故本项降级** —— 仅当将来改主意时才需验 |

**2026-09-28 两轮评审新增（全部**未定**，动手前必须回答）**：

| # | 项 | 为什么必须在动手前定 | 相关节 |
|---|---|---|---|
| 8 | ⭐ **`ping()` 的最终签名 + 能力清单格式** | 它是 `CapabilityPresence` 的**唯一数据源**，而现为 `int`；AIDL 变更**无协商机制**，两端须同时升级 | §3.1 / §6.3 / §7.2b-10 |
| 9 | ⭐ **丢弃计数的语义**（delta vs 累计）**与 UI 过期策略** | UI 行为与测试断言都取决于它；现状是「累计且永不清零」⇒ 横幅永久驻留 | §5.3 / §8-14 |
| 10 | ⭐ **权限判据取 L1 还是 `L0 ∪ L1`** | 决定「框架在跑但通道断」时工作流是否被禁用（可发现性 vs 误禁的取舍） | §6.1 |
| 11 | ~~⭐ **`android:permission` 能否拦住 uid 2000**~~ | ✅ **2026-09-29 已验：能拦住**。规则是 `uid == 1000`（及 0），**不是「uid < 10000」** | §3.3 / §8.2-2 |
| 12 | **③ 的错误码枚举** | §6.4 的「分类提示」没有它就退化为文案匹配 | §6.4 |
| 13 | **`Capability` / `CapabilityPresence` 的包与文件名** | 与既有 `core/xposed/XposedCapability.kt`（=权限判据）**重名** | §6.2 / §6.3 |
| 14 | **工作线程池的具体容量 + 默认超时值** | 文档只定「必须有界」「逐 capability 给」，**没有数量级**；而它跑在 system_server 里 | §5.1 |
| 15 | **③ 的幂等 / 重放语义** | §5.2 定了断连即唤醒 waiter 回 error，但**未定调用方是否重试**。「无状态」≠「无副作用」—— 将来接写类 capability 时重试会生效两次 | §5.2 |
| 16 | **hook 层的「发现并升级」路径** | 协议版本 / 能力清单都建立在「hook 层可能落后」这一常态上，但**用户侧的升级触发与提示落点未定**（热更新由**重装 App** 触发，而 ③ 的消费者在 App 内） | §6.4 |
| 17 | **多进程 hook 时配对表的连接维度** | §5.2 的配对表只按 `request_id`；§4.4 已定「连接身份 = binder 实例」，但**没有一句话说配对表必须按连接分桶** | §4.4 / §5.2 |
| 18 | **跨进程调用链的可观测性契约** | hook 层只写 `android.util.Log`、App 侧写 `DebugLogger`，**两者无关联 id**；③ 上线后一次调用横跨两个日志源，从一个 `request_id` 追到两端**目前无手段** | 缺 |
| 19 | **③ 的测试策略** | ③ 的 handler 跑在 system_server 的 LSPosed ClassLoader 里，**App 侧单测跑不到**；现有测试全是纯函数/协议层 | §7.2b-9 |
| 20 | **回滚方案** | 热更新是「新代际加载」；新代码有问题时，**退回旧 APK 是否等价于热更新回滚**？旧句柄已由 `replaceHook` 转移到新代际 ⇒ 路径未定义 | §5.5 / §8-19 |

**S4 已收掉**：

| 原未决项 | 结论 |
|---|---|
| ③ 的 `invoke` 用同步还是 oneway | ✅ **oneway + 配对响应**（§3.4）—— 两者不冲突，是假两难 |
| 线程与超时模型 | ✅ 三层超时 + 自建有界工作线程池（§5.1/5.2）—— ⚠️ **池满时立即回 error**（§3.4 末） |
| hook 点挂载/卸载策略 | ✅ **不做空闲卸载**，规范早退（§5.4） |
| 「同一连接上的调用串行 ⇒ 慢 handler 挡后续」 | ✅ **不成立** —— `completes` 指 `onTransact` 返回，而 §5.1 要求它立即返回（§3.4） |

**S5 已收掉**：

| 原未决项 | 结论 |
|---|---|
| **`ping()` 的用途** | ✅ **连接期能力交换**，产出 `CapabilityPresence`（§6.3）—— 同时确认它与 §3.5 的版本号收敛是**同一个改造项** |
| ③ 的降级判据 | ✅ **取决于能力有无替代实现**，声明在能力注册表（§6.2）—— **不是「③ 必须有降级」** |

**S7 已收掉**：

| 原未决项（S7 复核时新增） | 结论 |
|---|---|
| **`resolve`（响应）的鉴权** | ✅ **响应信封带 token，同 `report` 规格**（§3.3）—— 初版只考虑了请求方向 |
| **③ 的结果大小上限** | ✅ **框架层统一契约：声明上限（≤256 KiB）+ 主动截断 + 标志位 + 分页**（§3.6）—— ⚠️ **同时查清它不是「每事务 1MB」**：是**接收方进程共享的一份缓冲**，且 **oneway 只用其一半**、超限**静默丢弃** |
| **App 侧配对表** | ✅ 有界 + 逐调用超时 + **断连时立即唤醒全部 waiter 回 error** + 迟到 `resolve` 丢弃告警（§5.2） |
| **加一个 ③ capability 的真实代价** | ✅ **8 处，其中注册分散在两个进程**（§7.2b） |
| **连接身份何时必须落地** | ✅ **多进程 hook 的前置依赖**（§4.4） |

> ⚠️ **#5 不预设方案** —— 缺的输入是「位置 3 到底要传什么」。
> 但 §4.4 的形态（连接身份由 binder 承载）**对两种方案都成立**，故可先定它。

---

## 11. 一句话总结

> **通道缺的不是「一种新通道」，而是「一种新生命周期」。**
>
> **三组类型各有其轴**：生命周期按**状态归属**（持镜像 ⇒ 订阅；无状态 ⇒ 调用），
> 执行环境按**能力需要**，暴露面按**用户可见性**。
>
> **③ 只做 ② 拿不到的东西**（第二判据）。
>
> **统一入口的边界 = 状态归属轴**：订阅保留专用方法（契约要能被签名表达），调用走统一入口。
>
> **⚠️ 现状最大的结构问题是「App 侧没有连接身份」** ——
> 不是「几个字段是单槽位」，是整个模型就是「最后注册者独占」。
>
> ---
>
> **⚠️ 两轮评审补的一句**：**这份文档的「架构判断」比它的「实现级契约」成熟得多。**
>
> 架构层（§1/§2/§4/§6.1/§6.3/§7）经逐条核对**站得住**；
> 而 ③ 的契约**一行代码都没有**，且**已实现的 ② 侧本身有 19 条缺陷**（§8）——
> 其中两条（**丢弃计数链路未闭合**、**热更新后 hook 挂两遍**）都属文档自己列的
> **反模式 6（写了调用点、实际没有调用点）**，而**症状都会伪装成「偶发 / 部分 App 不灵」**。
>
> ⇒ **读本文时，请把「架构决策」与「待验证的契约」分开看**：前者可依据，后者须先验（§8.2）。
>
> ---
>
> **⚠️ 2026-09-29 真机验证后补的一句**（这是本轮最该记住的事实）：
>
> **`hook()` 是叠加的，而热更新路径每换一代就多挂一个、永不回收。**
>
> ```
> 每装一次包（含开发期反复重装） ⇒ 同方法 hook 数 N + 1
> 每次 Activity 切换             ⇒ onActivityResumed 跑 N 次
>                                ⇒ N 次反射 + N 次 extras 序列化 + N 条事件
> 至今实测已经走到 N = 6（重启可清零）
> ```
>
> **默认 1000ms 冷却把这个 N 倍放大挡在了 App 侧，所以症状一直被掩盖。**
> 它属于本仓库最爱强调的那一类：**不报错、只静默变差，且随使用时间单调劣化**。
>
> ✅ **已于 2026-09-29 修复**（§8.1-2）：`replaceHook()` 的返回值存进 `handle`，
> **只接手 1 个、其余 `unhook()`** —— 既治根因，也顺手**自我修复**了历史累积
> （实测被毒了一个多月的设备装一次包就从 7 回到 1）。

---

## 附：变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-28 | 创建草稿。落 S1（需求面 + 三组类型）、S2（划分轴）、S3（协议 + 连接与状态）。S4–S7 标为待讨论。 |
| 2026-09-28 | 落 **S4（执行模型）**：③ 定案 oneway + 配对响应（纠正 §3.4 的「假两难」）／线程模型／三层超时（含「非脚本 handler 无法中断」限制）／分级背压「必须分档但策略后定」／**hook 点不做空闲卸载，改为规范早退 + 反射缓存**／引用捕获型预留。§8 缺陷清单补 3 条（7/8/9）。§10 未决项收敛。S5–S7 仍待讨论。 |
| 2026-09-28 | 落 **S5（可用性与降级）**：**订正 S1 的一处错误结论**（「③ 必须有降级」——方向反了，③ 的失败天然可见，② 才静默）／降级判据改为**取决于能力有无替代实现**（声明在能力注册表）／`CapabilityPresence` 定位改为**连接期交换的缓存**（删 `DEGRADED`）／新增 **oneway 静默丢弃**这一 ③ 特有风险。§10 未决项再收敛 2 项。S6–S7 待讨论。 |
| 2026-09-28 | 落 **S6（扩展规范）** + **S7（文档边界）**：§7 重写（边界切分／10 处改动／成熟度对照／**反模式清单扩到 9 条**／触发器体系的指针）—— 明确「**契约要切分**」（hook 特有的留本文，通用的划出去）。§0.1 定案三份被取代文档的**头部声明**（架构以本文为准、实测以它们为准；**保留不删**，因为实测与归因教训不可复现）。⚠️ **S6 讨论中纠正了自己两处**：① 曾想把触发器体系三层写进 `surveys/trigger-system-overview.md` —— **错**（survey 只说明现状，不写改动）；② 曾误判 `VoiceTriggerModule` 是「漏注册」—— 实为**设计内的例外**（由 `VoiceTriggerService` 自己 `new`）。**S1–S7 全部完成。** |
| 2026-09-28 | **全面复核（S7 补）**。对全文档引用的代码事实做了逐条核对（§8 的 9 条缺陷、§4.2/§4.3 的连接事实、§7.2 的 28 vs 25、§3.5 的常量位置 —— **均属实**），并修出以下问题：<br/>⭐ **一处实质性技术错误**：§3.4「同一连接上的调用串行 ⇒ 慢 handler 挡后续」—— `completes` 指 `onTransact` 返回，而 §5.1 要求它立即返回 ⇒ **该「代价」不成立**（原写作与 §5.1 互斥）。改写为「派发顺序 ≠ 执行顺序」，并升格为**反模式 10**（把传输层语义当业务层语义）。<br/>⭐ **一处鉴权缺口**：`resolve`（响应）此前**无任何凭证** ⇒ 可被伪造。§3.3 改为**请求与响应两个信封都带 token**。<br/>⭐ **补三处实现级契约**：§3.6 **Binder 事务缓冲契约**（声明上限 + 主动截断 + 标志位 + 分页）／§3.4 末 **工作线程池满时立即回 error**／§5.2 **配对表容量·超时·断连唤醒·迟到响应**。<br/>⭐ **补 §7.2b**：加一个 ③ capability 的 8 处改动（此前 §7 只覆盖订阅型扩展）。<br/>⭐ **§8 缺陷 9→13**：新增 4 条（缺陷 9 实为**两层死链**／`currentProtocolVersion()` 死函数／`onConnectedSink` 第三个单槽位／`onUnbind` 会清掉**新**连接）。<br/>一致性修订：**①②③ 编号约定**（§2.1 声明：一律指生命周期）／§7.2 交叉引用错（§8 缺陷 2 → §7.4 反模式 2）／§3.5 与缺陷 4 的 `ping()` 表述／§6.2 `fallback` 加 `params`／§9 把「AI 配快捷方式」移出 Xposed 边界／§5.4 行号口径（定义行 vs 调用行）+ `@Volatile` 提示／§5.6 表标注「含不做的位置」／§6.3 与 P4 `NOT_MOUNTED` 消歧 + 不进 `XposedState`／§4.4 写明连接身份是**多进程 hook 的前置依赖**。 |
| 2026-09-28 | **§3.6 改写：查清「Binder 1MB」的真实含义**（初版说得太干净）。① **它不是「每事务 1MB」** —— 是**接收方进程** `mmap` `/dev/binder` 的一份缓冲，**该进程内所有在途事务共享**（官方 `TransactionTooLargeException` 逐字）；实际边界低于 1016 KiB，且**不是应用可配项**（`libbinder` 的 `ProcessState::initWithMmapSize` 只在 native、且须在任何 binder 调用前）。② ⚠️ **oneway 只用其中的「一半」**（内核 `free_async_space = buffer_size/2`），且**超限时静默丢弃、不通知发送方** —— 这条**直接加剧 §3.4 选 oneway 的代价**：`resolve` 载荷过大 = 又一次静默失败（与 §6.5 的「方法不存在」是同类风险的两半）。③ **顺带解释了本项目当年的实测报错**：`logcat-debug-tool.md` §2.1 那条例外为何是 `DeadObjectException` 而不是 `TransactionTooLargeException` —— `signalExceptionForError` 是**猜**的（出站 parcel > 200 KiB 才报后者），driver 不区分。**排查含义：`DeadObjectException` ≠ 对端已死。** ④ 契约扩到 5 条（新增「请求方向同样要小，且 `invoke`/`pushConditions` 的接收方是 system_server、缓冲与全系统共享」），上限建议量级改为 **≤ 256 KiB**，并给出 AOSP `NotificationManager` 的「句柄 + 双向取回」现成范式。 |
| 2026-09-28 | **两轮独立源码评审（不采信任何文档）后的第一批落地**。评审方式：两个子代理分别做「代码事实核验」与「架构与契约」，全程只读源码、全仓 grep、代码注释只当线索。结论：**文档引用代码事实的准确度极高**（§8 十三条、§5.4 行号图、§7.2 的 28/25 全部成立，未发现事实错误）；**风险在文档没去核的地方**。本批**只落「纯事实」项**，不依赖任何实验：<br/>① **§8 新增缺陷 14–19**：⭐ **丢弃计数链路从未闭合**（`EventQueue.drainDropped()` 零生产调用者 ⇒ 计数永不清零 ⇒ 首页横幅永久驻留 + 每事件刷 warning；对照 Core logcat 路径**有**调用者）／`HookLog.kt:16` 引用**不存在的** `HookPackagePurityTest`／`PermissionManager` 注释与实时判据**相反**／`appContext`·`attach()` 残留死字段／`HookChannelControllerTest` **三处空断言**／⭐ **热更新后 hook 被挂第二遍**（`replaceHook` 返回值被丢弃 + `handle = null`，注释与代码相反）。<br/>② **新增 §8.1 修复清单**（每条给**最小改法**，按可执行性排序）+ **§8.2 三处「先验再改」**（重复-hook 语义 / `android:permission` 能否拦 uid 2000 / `ServiceConnection` 是否必在主线程）。<br/>③ **§5.1 补第四格**：连接建立路径落在 **system_server 主线程**（`bindService` 未传 Handler ⇒ 回调在 main ⇒ 同步 `registerCallback` + 反射挂 hook + 嵌套往返要本地 binder 池有空位）。<br/>④ **§5.2 补中断契约**（Rhino 异常**不得逃逸**，工作线程须顶层 try/catch）+ 「脚本里的阻塞 Java 调用同样不可中断」+ **三层超时两层零代码**的事实。<br/>⑤ **§5.3 补**：文档把「有界队列+丢弃计数」当现状能力写，而该链路**本身没闭合**。<br/>⑥ **§5.4 补**：本决策与 `IHookCallback.aidl:18`、`HookChannelController.kt:195` 的 AIDL/代码**契约文案冲突**（空条件应读作「早退」而非「卸下」）。<br/>⑦ **§3.6 补**：契约 1–4 必须**覆盖 `report` 上行**（事件是唯一无法重试的）+ ⚠️ **现有预算按 char 计、契约按 byte 立**（`MAX_EXTRAS_JSON_CHARS=128K` 字符，CJK 下达 ~576 KiB > oneway 半缓冲，**整条事件静默丢弃**）。<br/>⑧ **§3.1 补**：⭐ **`ping()` 契约自相矛盾**（§3.1 为 `int`、§6.3 要能力清单、§7.2b 未列改 AIDL）⇒ 它作为 §6.5 静默丢弃的**唯一防线**却无法承载。<br/>⑨ **§6.1 补**：⭐ **「两组合起来看」没有贯彻到权限判据** —— 首页用 `evaluate(isConnected())` 合成，而 `XposedCapability.isGranted` **只取 L1** ⇒ 「框架在、通道断」被判「已授权」、**没有任何缺权限提示**而触发器确实不工作。<br/>⑩ **§6.2 补** `Capability` 与既有 `core/xposed/XposedCapability.kt`（=权限判据）**重名**的消歧。<br/>⑪ **§6.4 补**「已授权但通道断」一类 + **错误码必须机器可判**（枚举，不用自由字符串）。<br/>⑫ **§7.2/§7.2b 补漏项**：测试 / 引用面白名单登记 / proguard（②→13 处；③→10 处），并标注「本表只数功能改动」。<br/>⑬ **§4.3 论证降级**：第二条多连接路径（150 秒窗口）**当前不可达**（`onHotReloading → stopChannelOnly()` 会置 `stopped`+interrupt），结论不变、**论据换成第 1 条与多进程 hook**。<br/>⑭ **§1.2 约束 2 降为【推断】**（分级背压无代码/实测支撑，只有 ShortX 外部对照）。<br/>⑮ **§10 新增未决项 8–20**（`ping` 最终签名 / 丢弃计数语义 / 权限判据取哪几位 / uid 豁免 / 错误码 / `Capability` 落点 / 池容量与默认超时 / ③ 幂等语义 / hook 层升级路径 / 配对表按连接分桶 / 可观测性契约 / ③ 测试策略 / 回滚方案）。 |
| 2026-09-29 | **真机验证批次（小米 MIX Fold 3 / Android 17，纯 adb，无探针）。** §8.2 三项「先验再改」**全部有结论**，并各自回写正文：<br/>① ⭐ **libxposed 重复 `hook()` = 链式叠加**（不是幂等）—— 临时在 `onActivityResumed` 最前加计数日志做**同操作 A/B**：N=5 → **20** 条、N=6 → **24** 条（比值 1.2 = 6/5），且 `ActivityChangedSource` 实例数 = 1。**⇒ 缺陷 19 从「挂第二遍」升级为「N 倍放大 + 单调增长」**（实测 `旧 hook 句柄数` 1→2→3→4→5→6，每次热更新 +1）。§8.1-2 的修法随之加强：**只接手 1 个、其余 `unhook()` 掉**（顺手自我修复历史累积）。<br/>② ⭐ **`android:permission` 能拦住 uid 2000** —— `am startservice`（`startService` 与 `bindService` 在 AMS 走**同一个** `checkComponentPermission`，而 `adb shell` 就是 uid 2000）：受保护 Service → `Requires permission …HOOK_CONTROL`；无保护对照 → 成功；uid 1000 → `dumpsys` 里 `c:android` bind 成功。**⇒ 规则是 `uid == 1000`（及 0），「uid < 10000 豁免」是错的**（那是 `AppsFilterBase` 的**包可见性**规则，属另一子系统）。§3.3 与 §10-11 据此改写。<br/>③ **`ServiceConnection` 回调确在 system_server 主线程** —— logcat `threadtime` 的 `pid tid` 两列全等（含 `loadClass` + `hook()`）；热态 ≈7ms，**尾部（重启后首次挂载）未测**已标注。<br/>⭐ **新增 §8 缺陷 20（两个子代理都没发现）**：**重连时重复 `bindService` + ConnectionRecord 泄漏** —— `scheduleReconnect` 里 `doBind()` 后立刻查 `host != null`，而 `host` 是异步设的 ⇒ 每轮 **2 次 bind**；`dumpsys` 里 `ConnectionRecord` 只增不减且全部共用同一个 `ServiceConnection`（`unbindQuietly()` 只在 `stop()` 里调）。**重启后稳定复现。**⚠️ 同时诚实记录：**没有**连带产生重复 `registerCallback`／二次 remount／二次换 token。<br/>另：实测确认 **App 侧 `DebugLogger` 确实进 logcat**（`ActivityChangedTrigger` tag 可见），后续排查可用。**测试用的临时改动已全部还原**（`ActivityChangedSource.kt`、`HookProbeEntry.java`），并重新构建安装了干净版本。 |
| 2026-09-29 | **修复缺陷 19 与 20（代码改动，真机验证通过）。** ① **缺陷 19**：`ActivityChangedSource.remountAfterHotReload` 改为**只接手 1 个**（`replaceHook()` 的返回值存进 `handle` —— 此前丢弃它并置 `handle = null`，那正是根因）、**其余全部 `unhook()`**。实测：装包后 `旧 hook 句柄数` **7 → 保留 1 + 清掉 6**；其后连续 5 次热更新稳定读到 **1**（单调增长终止）。⚠️ 顺带核实 `replaceHook` 的签名确实是 `(Hooker) → HookHandle`（`javap` 读 aar 确认），这才让「存返回值」可行。<br/>② **缺陷 20**：实施时发现它有**两半**，只修一半不够 —— **(a)** `BinderTransport.doBind()` 改为返回 `Boolean`，并新增 `awaitConnected(timeoutMs)`（有界轮询，步长 `AWAIT_STEP_MS = 100ms`），**提交后等 `host` 落地再判成败**；**(b)** ⚠️ 修完 (a) 后 `ConnectionRecord` **仍在 +1/轮**（实测 distinct 7→8→9），因为 `unbindQuietly()` 只在 `stop()` 里调 ⇒ **重新 bind 之前先 `unbindQuietly()`**。实测：每轮 `bindService()` 提交次数 **2 → 1**；`ConnectionRecord` 连续 3 轮断连 **6 → 6 → 6 → 6 完全持平**；且未引入额外延迟（提交 → 连接成功共约 200ms）。<br/>**门禁**：`./gradlew assembleRelease` 通过；`./gradlew test` 1237 例，仅 1 例失败 —— 即 `FORK.md` 已记录的既有失败 `VObjectPropertyTest`（`Uri.parse` 未 mock 的纯 JVM 环境限制），与本次改动无关。 |
| 2026-09-29 | **修 A 组四条 + 缺陷 18，并补 §8.3 修复优先级。** §8.3 是**判断而非文档既有内容** —— 按「不修它，③ 的设计就落不了地」分四档：**A 组（必须先修）**= 4 `ping()` 契约／13 `onUnbind` 误清／3 `deathRecipient` 死字段／1+12 两处单槽位；**B 组（先定口径）**= §10-9/10/12；**C 组（同批顺手）**= 11/7/8/14/18/15/16/17；**D 组（与本轮无关）**= 9/10/2/5/6。<br/>⭐ **13 已实测确认为真实竞态**（不是理论风险）：日志显示旧连接的 `unbindService` 比新连接建立**早约 1.1 秒**、且由 `ManagedServices$1.onBindingDied` 触发 —— **异步投递一旦落到新 `registerCallback` 之后就会把新连接清掉**。这在部署流程里是常态（重装 ⇒ 旧进程死 ⇒ 新进程接管）。<br/>**已修五条**：**13** 改为带身份比较（比 `asBinder()`，不能直接 `===`）／**3** 接上 `linkToDeath`（**先解旧回执再 unbind**，否则拿不到 binder）／**1** 与 **12** 两处单槽位改按 key 的注册表（逐个通知、一个抛异常不影响其他）／**18** 三处空断言补真断言。<br/>**验证**：单测 4 例新增（`HookChannelControllerTest` 16 例全绿），并对 6 条断言**逐条反证、全部变红**（`registerSink` 那条要用「不替换而累积」的写法才逼得出来）；真机确认 `💀 deathRecipient` 在 `bindService` 前即时触发、重连仍是 **1 次 bind**。**门禁**：`assembleRelease` 通过；`test` 1240 例，仅既有失败 1 例。<br/>⚠️ 新增一个**测试接缝** `notifyOnConnected()`（`internal`）：`IHookCallback.Stub` 继承 `android.os.Binder`，纯 JVM 测试构造不出来，与既有 `injectTokenForTest` 同源。 |
