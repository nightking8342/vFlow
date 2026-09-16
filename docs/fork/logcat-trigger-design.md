# logcat 触发器设计文档

> **目录归属**：fork 独有功能文档（冲突归我方），上游无此文件。
> **分支/日期**：`session-0915-01`，2026-09-16。
> **状态**：设计定稿，**尚未实现**。
> **前置调研**：可行性已真机实测，见 [`surveys/logcat-readability-survey.md`](surveys/logcat-readability-survey.md)。
> **与相邻文档的区别**：本文件记录「要做成什么样 + 为什么这么设计」；可行性证据在 surveys 那份。
> ⚠️ 所有 `file:line` 引用基于 `session-0915-01`（2026-09-16）实测，上游合并后需重新核对。

---

## 0. 一句话方案

**Core 起一条 `logcat` 长驻进程，用一个线程阻塞读；每行解析后遍历所有 logcat 触发器的条件匹配，只把命中行推给 App。**

进程数与线程数**不随触发器数量增长**。

---

## 1. 为什么是这个方案

### 1.1 关键约束（来自实测）

| 约束 | 来源 |
|---|---|
| 走 Shell 身份（uid 2000）可读全量日志 | `surveys/logcat-readability-survey.md` §3.1 |
| **无需** `READ_LOGS` 权限（shell 在 `log` 组） | 同上 §2 |
| 应用进程内直读**只能读自己** | 同上 §3.2 |
| 现有 shell 执行是**请求-响应**式，无流式能力 | `ShellManager` 内 `stream` 关键词命中 0 处 |

### 1.2 被否掉的三个方案

| 方案 | 为什么否掉 |
|---|---|
| **App 侧匹配**（所有日志跨进程） | 每行都要 JSON 序列化 + socket 往返，成本与日志量成正比 |
| **grep 预过滤**（`logcat \| grep -E 'A\|B'`） | ① 多触发器条件要并成一条命令，`regex` 条件因**锚点问题**无法可靠并入（`^WeChat$` 匹配不到带时间戳的整行）→ 假阴性 ② 增删触发器要**重启管道** ③ grep 变体方言差异 |
| **C 独立进程**（照抄 Tasker） | 收益递减：从「每行跨进程」到「只传命中行」是**数量级**改善；从 Core(Kotlin) 到 C 只是**常数级**（省 JVM 开销）。代价是 C + CMake + 四 ABI + 长期维护，且要用 C 重写正则引擎 |

### 1.3 Tasker 的对照（澄清一个常见误读）

Tasker 用 native + grep 的原因**不是性能优化本身**，而是：

- 它的 native 层**没有正则引擎** → 把匹配「外包」给系统 `grep` 二进制
- 它**不做结构化参数** → 用户直接写 grep，所以没有「条件翻译」问题（代价是把 shell 细节泄漏给用户，官方文档明确警告「不懂 shell 就别用」）

**vFlow 的处境更好**：Core 是 JVM，`kotlin.text.Regex` 现成，不需要外包给 grep，因此**不承担方言、锚点、引号转义等问题**。

---

## 2. 架构与数据流

```
① Core：ProcessBuilder("logcat","-v","threadtime").start()      ← 长驻，不带 -d
            ↓ stdout 持续输出
② Core：单线程 readLine() 阻塞读（无日志时挂起，不耗 CPU）
            ↓
③ Core：parseThreadtime(line) 解析字段
            ↓
④ Core：遍历 conditionList（N 个触发器的条件）
            ├─ 命中 → 构造 JSON → socket write 推给 App
            └─ 未命中 → 就地丢弃（不跨进程）★ 性能关键
            ↓
⑤ App：LogcatTriggerHandler 读 socket → 按 triggerId 定位 TriggerSpec
            ↓
⑥ App：冷却判定 → executeTrigger(trigger, triggerData)
```

**为什么「未命中就地丢弃」是关键**：日志可达每秒数百上千行，跨进程是全链路最贵的环节。在 Core 内判掉 99% 的行，才把成本压到可控。

---

## 3. 多触发器的处理机制

**一条流服务 N 个触发器**，与 `ElementTriggerHandler` 同构（该 Handler 用一条无障碍事件流服务多个触发器，见 `handlers/ElementTriggerHandler.kt:70-87` 的 `syncTriggerStates`）。

```
每读到一行 → 解析 → for (每个触发器条件) { if (matches) push }
```

| 问题 | 答案 |
|---|---|
| 10 个触发器 = 10 个进程？ | ❌ **1 个进程 + 1 条流** |
| 每行匹配 N 次？ | ✅ 是，但都是内存内的廉价比较 |
| 一行命中多个触发器？ | ✅ 逐个推送，App 侧各自触发各自工作流 |
| 没命中？ | 在 Core 丢弃，**不跨进程** |

**依据**：`TriggerService` 按**模块 id** 注册 Handler（`services/TriggerService.kt:219-224`），不是按触发器。所以 logcat 只会有一个 Handler 实例，持有一个 `listeningTriggers` 列表（`ListeningTriggerHandler.kt:15`）。

---

## 4. 模块参数（全部结构化）

| 参数 id | 名称 | 类型 | 选项 | 默认 |
|---|---|---|---|---|
| `target_package` | 目标应用 | STRING | — | 空 = 全部（**建议警告**） |
| `tag_filter_type` | TAG 匹配方式 | ENUM CHIP_GROUP | `any` / `equals` / `contains` / `regex` | `any` |
| `tag_filter_value` | TAG 匹配值 | STRING | — | — |
| `message_filter_type` | 消息匹配方式 | ENUM CHIP_GROUP | `any` / `contains` / `regex` | `any` |
| `message_filter_value` | 消息匹配值 | STRING | — | — |
| `min_level` | 最低级别 | ENUM CHIP_GROUP | `V` / `D` / `I` / `W` / `E` | `I` |
| `cooldown_ms` | 冷却时间 | NUMBER | — | `1000` |

**设计要点**：

1. **`tag` 与 `message` 是两套独立条件，AND 组合**。OR 由「配多个触发器」在架构层提供（多触发器共享同一套 steps，见 `docs/fork/surveys/trigger-system-overview.md`）。
2. **`message` 不提供 `equals`**——整行含时间戳/pid，精确匹配无意义。
3. **显隐联动**：`*_filter_value` 用 `InputVisibility.notEquals(..., ANY)` 控制，照抄 `SmsTriggerModule.kt:77,97`。
4. **`legacyValueMap`**：新模块无需预设（`AGENTS.md` 第 7 条）。
5. **校验（`validate()`）**：
   - `tag` 与 `message` 同时为 `any` 且 `min_level=V` → 匹配全部日志，**警告**（不阻塞）
   - `regex` 条件尝试编译，失败则拒绝保存（避免静默失效）

### 4.1 输出

| 输出 | 类型 | 说明 |
|---|---|---|
| `message` | STRING | 消息正文 |
| `tag` | STRING | 解析出的 TAG |
| `level` | STRING | `V`/`D`/`I`/`W`/`E` |
| `pid` | NUMBER | 进程号 |
| `raw` | STRING | 完整原始行（调试/兜底用） |

⚠️ **实现契约**：`execute()` **必须读 `context.triggerData`** 才能产出上述输出。
若像 `PowerTriggerModule` 那样返回 `ExecutionResult.Success()` 不带 outputs，**下游引用会静默为空且不报错**。
（机制见 `WorkflowExecutor.seedTriggerOutputs`，`core/execution/WorkflowExecutor.kt:374-407`）

---

## 5. 字段解析

`threadtime` 格式（`logcat` 默认 `-v` 值，官方定义）：

```
09-16 21:04:13.040  9278 15081 I WeChat: 具体消息
└─日期─┘└──时间──┘  └pid┘└tid┘ └级┘└─TAG─┘└message┘
```

解析正则：

```kotlin
Regex("""^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s?(.*)$""")
```

**降级原则**：解析失败时**不能丢行**——把整行作为 `message`、`tag` 置空、`level` 置 `V`，让条件仍有机会命中。
（多行堆栈、非标准 TAG 都会走到这里。）

---

## 6. Core 侧新增：`LogcatStreamWrapper`

### 6.1 接口

实现 `StreamingWrapper`（`core/.../wrappers/StreamingWrapper.kt`），方法：

| method | 作用 |
|---|---|
| `subscribeLogcatStream` | 建立长连接，开始推流 |
| `updateTriggers` | **全量替换**当前所有 logcat 触发器的条件 |
| `stopLogcatStream` | 停止并释放 logcat 进程 |

### 6.2 为什么用「全量替换」而非增删

```
App 侧触发器任一变化（增/删/改条件）
  → TriggerService 汇总「当前所有 logcat 触发器的条件」
  → updateTriggers(全部)
  → Core 整体替换 conditionList
```

**好处**：无状态同步问题，且**不需要重启 logcat 进程**（因为 logcat 本身不带过滤参数，过滤是 Core 内部逻辑）。这是本方案相对 grep 方案的核心优势之一。

### 6.3 ⚠️ 一个待解决的架构问题：如何注册

`BaseWorker` 的流式路由只查 `serviceWrappers`：

```kotlin
// core/.../worker/BaseWorker.kt:177
val wrapper = serviceWrappers[target]      // 类型为 ConcurrentHashMap<String, ServiceWrapper>
if (wrapper is StreamingWrapper) { wrapper.handleStream(...) }
```

而 `serviceWrappers` 的值类型是 `ServiceWrapper`，其构造函数会 `connect()` 去 `ServiceManager` 找系统服务（`wrappers/ServiceWrapper.kt`）。**logcat 不包装任何系统服务**，所以有两条路：

| 选项 | 做法 | 评价 |
|---|---|---|
| **A. 继承 `ServiceWrapper`，用一个不存在的服务名** | `connect()` 会失败但被 catch（只打印警告），`serviceInterface` 为 null，但 `handleStream` 不依赖它 | ⚠️ 能跑，但语义别扭 |
| **B. 放宽 `serviceWrappers` 的约束** | 让注册表接受「非 ServiceWrapper 的 StreamingWrapper」 | 需改 `BaseWorker`（**上游文件**，按 `FORK.md` 需登记评估） |

**倾向 A**（零上游改动，符合 fork「控制 diff 面积」原则），但**需实现时验证** `connect()` 失败是否真的不影响流式处理。

### 6.4 进程生命周期

```kotlin
while (isActive) {
    val process = startLogcat()          // ProcessBuilder
    try {
        while (true) {
            val line = reader.readLine() ?: break   // 返回 null = 进程结束
            handleLine(line)
        }
    } finally { process.destroy() }
    delay(1000)                          // 退避后重启
}
```

照抄 `VoiceTriggerHandler` 的重试模式（`handlers/VoiceTriggerHandler.kt:89-98` 的 `while(isActive){ try{...}catch{delay(1000)} }`）。

---

## 7. App 侧改动

| 文件 | 改动 |
|---|---|
| `triggers/LogcatTriggerModule.kt`（新增） | 参数/输出/摘要/校验 |
| `triggers/handlers/LogcatTriggerHandler.kt`（新增） | 继承 `ListeningTriggerHandler`；读 socket；冷却；`executeTrigger` |
| `core/workflow/module/ModuleRegistry.kt` | 追加注册一行 |
| `triggers/handlers/TriggerHandlerRegistry.kt` | 追加注册一行 |
| `services/VFlowCoreBridge.kt` | 新增 `streamLogcatEvents()` + `updateLogcatTriggers()`（照 `streamClipboardEvents`，`:653`） |
| `res/values{,-en,-ja}/strings_module.xml` | 文案 |

**条件同步时机**：`LogcatTriggerHandler.addTrigger/removeTrigger` 时汇总并调用 `updateLogcatTriggers()`。

---

## 8. 冷却

```kotlin
if (now - lastTriggerAtMs < cooldownMs) return   // 丢弃，不补发
lastTriggerAtMs = now
```

- **单触发器独立计数**（与 `ElementTriggerState` 一致）
- 默认 `1000ms`，`0` = 不冷却
- 冷却期命中**直接丢弃**（已与用户确认，不做 pending 补发）

---

## 9. 风险与未验证项

| # | 风险 | 状态 |
|---|---|---|
| 1 | **`ServiceWrapper` 注册方式**（§6.3） | ⚠️ 倾向方案 A，**需实现时验证** |
| 2 | **高频日志压垮推送** | ⚠️ 需保护：命中率过高时告警/限流；或 Core 侧也做冷却（但冷却需状态，会复杂化） |
| 3 | **Core 跑长驻进程 + 高频推流的稳定性** | ⚠️ **未验证**。`IClipboardWrapper` 证明 Core 能做流式推送，但那是**低频**事件；logcat 是**高频**，压力大得多 |
| 4 | **多行堆栈的解析** | 堆栈行不以 `threadtime` 格式开头 → 走降级路径（整行作 message） |
| 5 | **`pm grant READ_LOGS` + 重启**是否可行 | ⚠️ **未验证**。若可行，可改为**在 App 进程内直读**，完全省掉 Core wrapper 与 IPC —— 方案会大幅简化 |

> 第 5 项是唯一可能**颠覆本设计**的未知数。探针曾报 `pm grant` 成功但 `granted=false`
> （因未重启应用），故当时无法定论。若日后验证通过，应优先采用该路线。

---

## 10. 验证与门禁

**实现阶段**：

```bash
./gradlew testDebugUnitTest --tests "*Logcat*"   # 解析/匹配/冷却的纯函数单测
./gradlew test                                   # 全量回归
./gradlew :core:buildDex                         # Core 改动必须
./gradlew assembleDebug                          # 构建
```

**真机场景**（触发器必须真机验证，`AGENTS.md` 验证门禁）：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 单触发器，tag equals | 只在该 tag 时触发 |
| 2 | `message contains` | 包含即触发 |
| 3 | tag + message 同时配 | **AND**（都满足才触发） |
| 4 | 多个触发器共存 | 各自独立触发；**确认 logcat 进程只有一个** |
| 5 | 运行时增删触发器 | 立即生效，**无需重启 logcat** |
| 6 | 冷却期连续命中 | 只触发一次 |
| 7 | `min_level` 过滤 | 低于该级别的日志不触发 |
| 8 | 目标 App 未运行（`target_package`） | 明确报错/提示，**不静默** |
| 9 | `regex` 语法错误 | 保存时即拒绝 |
| 10 | 长时间运行（数小时） | 无内存泄漏、logcat 进程不异常退出 |

---

## 11. 需要先决策的两点

1. **`target_package` 是否强制必填？**
   空值 = 全设备日志，量极大。倾向**可选但校验时警告**。

2. **两个匹配条件都为 `any` 且 `min_level=V` 时是否允许？**
   = 匹配全部日志。倾向**允许但警告**（有人确实想做全量统计），并在文案里说明性能风险。

---

## 12. 实现顺序建议

1. **先做最小闭环**：Core wrapper 跑通 `logcat` → 固定条件 → 推送 → App 触发。
   验证 §9 风险 3（稳定性）与 §6.3（注册方式）。
2. **再加完整匹配**：条件数据结构、`updateTriggers`、全部匹配方式。
3. **最后补打磨**：冷却、校验、文案、单测、十项真机场景。
