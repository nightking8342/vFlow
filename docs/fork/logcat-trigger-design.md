# logcat 触发器设计文档

> **目录归属**：fork 独有功能文档（冲突归我方），上游无此文件。
> **分支/日期**：`session-0915-01`，2026-09-16 首版 / **2026-09-17 修订**。
> **状态**：设计定稿，**尚未实现**。
> **前置调研**：可行性已真机实测，见 [`surveys/logcat-readability-survey.md`](surveys/logcat-readability-survey.md)。
> **前置工具**：实现本功能前**先做** [`logcat-debug-tool.md`](logcat-debug-tool.md)（logcat 调试工具）。
> 它的 `LogcatParser` 就是本文 §5.1 要用的那一份，且能提前验证 §6.4 的缓冲问题与 §9 风险 3（长驻进程存活）。
> **与相邻文档的区别**：本文件记录「要做成什么样 + 为什么这么设计」；可行性证据在 surveys 那份。
> ⚠️ 所有 `file:line` 引用基于 `session-0915-01`（2026-09-16）实测，上游合并后需重新核对。

> 🔴 **2026-09-17 修订摘要**：本次修订修正了三处**会导致方案做不出来**的硬伤，均经代码核实：
> 1. `updateTriggers` 的通信机制**在现有协议下不存在** → 新增 §6.5「协议扩展」
> 2. 「在 `addTrigger/removeTrigger` 里同步条件」**不成立**（方法被 `final` 锁死）→ 见 §7.1
> 3. 条件结构未定义，但数据流依赖它携带 `triggerId` → 新增 §4.2
>
> 另修正降级语义（§5.1）、补 `F` 级别（§5.2）、补背压与隐私风险（§9）。

---

## 0. 一句话方案

**Core 起一条 `logcat` 长驻进程，用一个线程阻塞读；每行解析后遍历所有 logcat 触发器的条件匹配，只把命中行推给 App。**

进程数与线程数**不随触发器数量增长**。

---

## 1. 为什么是这个方案

### 1.1 关键约束（来自实测）

| 约束 | 来源 |
|---|---|
| 走 Shell 身份（uid 2000）可读全量日志 | [`surveys/logcat-readability-survey.md`](surveys/logcat-readability-survey.md) §3.1 |
| **无需** `READ_LOGS` 权限（shell 在 `log` 组） | 同文件 §2 |
| 应用进程内直读**只能读自己** | 同文件 §3.2 |
| 现有 shell 执行是**请求-响应**式，无流式能力 | `ShellManager` 内 `stream` 关键词命中 0 处 |

### 1.2 被否掉的三个方案

| 方案 | 为什么否掉 |
|---|---|
| **App 侧匹配**（所有日志跨进程） | 每行都要 JSON 序列化 + socket 往返，成本与日志量成正比 |
| **grep 预过滤**（`logcat \| grep -E 'A\|B'`） | ① 多触发器条件要并成一条命令，`regex` 条件因**锚点问题**无法可靠并入（`^WeChat$` 匹配不到带时间戳的整行）→ 假阴性 ② 增删触发器要**重启管道** ③ grep 变体方言差异 |
| **C 独立进程**（照抄 Tasker） | 收益递减：从「每行跨进程」到「只传命中行」是**数量级**改善；从 Core(Kotlin) 到 C 只是**常数级**（省 JVM 开销）。代价是 C + CMake + 四 ABI + 长期维护，且要用 C 重写正则引擎 |

### 1.3 Tasker 的对照（**已反编译核实**）

> 本节结论来自对 Tasker 6.7.3-beta 的**反编译**（jadx + **dexdump 字节码级还原**），
> 不是社区文档转述。详见 `D:/develop/references/tasker/notes/logcat-implementation.md`
>
> ⚠️ 该笔记 2026-09-17 做过一次订正：首版误判「Tasker 不做 TAG 下推」，
> 实际它**做了**。下面的命令是订正后的完整版本。

**Tasker 实际生成的命令**：

```bash
logcat -v epoch "<TAG>" *:S | grep --line-buffered -v " <myPid> " | grep --line-buffered <filter>
```

**启动前先单独执行一次** `logcat -c`（清全局缓冲），再起上面这条长驻命令。

**关键事实**（纠正四处常见误读）：

| 常见说法 | 实际 |
|---|---|
| 「Tasker 用 native C 代码读日志并匹配」 | ❌ **错**。用 shell 起 `logcat` 进程，匹配**外包给系统的 `grep` 二进制**。Tasker 的 7 个 `.so` 全是 Matter/CHIP、图像处理、AndroidX，**无日志相关** |
| 「Tasker 用一条流服务所有 profile」 | ❌ **错**。按 `(TAG, grepFilter)` 去重的 **monitor 池**（`activeMonitors: ConcurrentHashMap<LogcatIdentifier, LogcatMonitor>`），条件不同则各起一个进程 |
| 「它不需要 Shizuku」 | ❌ **错**。它**专门实现了 `LogcatFlowShizuku`**（见 §1.4），说明普通路径受限 |
| 「它不做 TAG 下推、始终全量采集」 | ❌ **错**（首版本文档采信了这条）。`"<TAG>" *:S` 就是 TAG 下推 |

**三级过滤的分工**（这是理解 Tasker 的关键）：

| 层级 | 位置 | 说明 |
|---|---|---|
| TAG 过滤 | **logcat 自身** | `"<TAG>" *:S`——`*:S` 把其余 TAG 全部静音，只放行一个 |
| 自身 pid 排除 | **grep** | `-v " <myPid> "`，防自触发环 |
| 用户关键字过滤 | **grep**（勾选时）/ **Java** | 默认在 Java，勾了 grep 才下推 |

**Tasker 为什么用 grep 而不是自己在 Kotlin 里匹配**：

- 它**不做结构化参数** —— 用户直接写 grep 语法，所以没有「条件翻译」问题
- 代价是把 shell 细节泄漏给用户：官方文档明确警告「不懂 shell 就别用」，
  并花大量篇幅教引号规则、grep 变体差异

**vFlow 的处境更好**：Core 是 JVM，`kotlin.text.Regex` 现成，不需要外包给 grep，
因此**不承担 grep 方言、锚点、引号转义等问题**；同时用结构化参数避免把 shell 复杂度暴露给用户。

**可借鉴的四点**（已纳入本设计）：

| # | Tasker 的做法 | 采纳情况 |
|---|---|---|
| 1 | `grep --line-buffered` | ⭐ 证实**块缓冲问题真实存在**。我们不走 grep，绕开该问题 |
| 2 | `logcat -v epoch`（而非 `threadtime`） | ⭐ 纯数字时间戳更好解析，**见 §5.3** |
| 3 | 重启前等「服务绑定就绪」再启动 | ⭐ 比固定 `delay` 可靠，**见 §6.4** |
| 4 | **启动前 `logcat -c` 清缓冲** | ⭐ **但我们改用 `-T 1`**——`-c` 有副作用，见 §6.5 |

**我们与 Tasker 的核心分歧**：Tasker 把 TAG 写进命令行，因此**必须**一条件一进程；
我们选择**单条流 + Core 内匹配**，代价是**全量行都要进 Core 判**。
这是主动取舍（换热更新与单进程），不是「Tasker 也这么做」。见 §3.1。

### 1.4 Tasker 的 Shizuku 版（旁证）

`LogcatFlowShizuku extends LogcatFlow`，只覆写了 `waitBeforeRestart`：

```java
// 若 Shizuku 已可用，先等 1 秒确认不是 stale 状态
log("Is already available? Wait a bit just to make sure it wasn't a stale state...");
delay(1000);
// 然后等待 Shizuku 服务绑定
log("Waiting for Shizuku service to be bound before restarting...");
shizukuAvailable.filter(available -> available).first();   // 挂起直到绑定
```

**含义**：Tasker 也为「走 Shizuku」单独做了一条 flow——与我们 §6.4 的处境完全对应。

---

## 2. 架构与数据流

```
① Core：ProcessBuilder("/system/bin/logcat","-v","threadtime","-T","1")   ← 长驻，不带 -d
            ↓ stdout 持续输出
② Core：独立线程 readLine() 阻塞读（无日志时挂起，不耗 CPU）
            ↓
③ Core：parseThreadtime(line) 解析字段（纯函数）
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

⚠️ **注意 ① 与 ② 的线程模型**：读 logcat stdout 是一个**长驻阻塞线程**；
同时还要能接收 §6.5 的控制帧。二者不能在同一个线程上串行——见 §6.5.2。

---

## 3. 多触发器的处理机制

**一条流服务 N 个触发器**。

```
每读到一行 → 解析 → for (每个触发器条件) { if (matches) push }
```

| 问题 | 答案 |
|---|---|
| 10 个触发器 = 10 个进程？ | ❌ **1 个进程 + 1 条流** |
| 每行匹配 N 次？ | ✅ 是，但都是内存内的廉价比较（**前提：Regex 已预编译，见 §4.2**） |
| 一行命中多个触发器？ | ✅ 逐个推送，App 侧各自触发各自工作流 |
| 没命中？ | 在 Core 丢弃，**不跨进程** |

**依据**：`TriggerService` 按**模块 id** 注册 Handler（`services/TriggerService.kt:216-227`），不是按触发器。所以 logcat 只会有一个 Handler 实例，持有一个 `listeningTriggers` 列表（`ListeningTriggerHandler.kt:15`）。

### 3.1 ⚠️ 与 `ElementTriggerHandler` 不同构

首版本文档写「与 `ElementTriggerHandler` 同构」——**在「一条事件流服务 N 个触发器」这个意义上是对的，但继承结构上不能照抄**：

| | `ElementTriggerHandler` | `LogcatTriggerHandler` |
|---|---|---|
| 过滤位置 | App 进程内 | **Core 进程内** |
| 要不要把条件下发 | ❌ 不需要 | ✅ **必须**（Core 得知道匹配什么） |
| 因此的同步需求 | 无 | 每次增删改都要 `updateTriggers` |
| 可用的基类 | `ListeningTriggerHandler` | ❌ **不可用**，见 §7.1 |

`ElementTriggerHandler` 的 `syncTriggerStates()`（`:71-87`）是在 **App 进程内**把 `listeningTriggers` 同步到 `triggerStates`；我们多了一层**跨进程**同步，这是本质差异。

---

## 4. 参数与条件结构

### 4.1 模块参数（用户可见，全部结构化）

| 参数 id | 名称 | 类型 | 选项 | 默认 |
|---|---|---|---|---|
| `target_package` | 目标应用 | STRING | — | 空 = 全部（**建议警告**） |
| `tag_filter_type` | TAG 匹配方式 | ENUM CHIP_GROUP | `any` / `equals` / `contains` / `regex` | `any` |
| `tag_filter_value` | TAG 匹配值 | STRING | — | — |
| `message_filter_type` | 消息匹配方式 | ENUM CHIP_GROUP | `any` / `contains` / `regex` | `any` |
| `message_filter_value` | 消息匹配值 | STRING | — | — |
| `min_level` | 最低级别 | ENUM CHIP_GROUP | `V` / `D` / `I` / `W` / `E` / `F` | `I` |
| `cooldown_ms` | 冷却时间 | NUMBER | — | `1000` |

**设计要点**：

1. **`tag` 与 `message` 是两套独立条件，AND 组合**。OR 由「配多个触发器」在架构层提供（多触发器共享同一套 steps，见 [`surveys/trigger-system-overview.md`](surveys/trigger-system-overview.md)）。
2. **`message` 不提供 `equals`**——整行含时间戳/pid，精确匹配无意义。
3. **显隐联动**：`*_filter_value` 用 `InputVisibility.notEquals(..., ANY)` 控制，照抄 `SmsTriggerModule.kt:77,97`。
4. **`legacyValueMap`**：新模块无需预设（`AGENTS.md` 第 7 条）。
5. **校验（`validate()`）**：
   - `tag` 与 `message` 同时为 `any` 且 `min_level=V` → 匹配全部日志，**警告**（不阻塞）
   - `regex` 条件尝试编译，失败则拒绝保存（避免静默失效）

> **`min_level` 含 `F` 的理由**：logcat 级别字符是 `V/D/I/W/E/F`（`F` = FATAL），
> 而 `Log.level` 的数值序里 `F` 排在 `E` 之后。「最低级别」若不含 `F`，
> 配 `min_level=E` 时会把 Fatal 也放行（因为 E ≤ F），语义正确但用户无从表达「只要 Fatal」。
> 收进枚举更直观。**解析与匹配都必须与 §5.2 的字符类一致。**

### 4.2 ⚠️ Core 侧条件结构（`LogcatCondition`）

首版本文档的数据流（§2 第 ⑤ 步）说「App 按 **triggerId** 定位 TriggerSpec」，
但 §6.1 只写了「全量替换当前所有 logcat 触发器的条件」，**没说 condition 里带 `triggerId`**。
必须显式定义，否则 Core 推给 App 的消息无法路由。

```kotlin
/**
 * Core 侧持有一个该结构的列表，由 updateTriggers 全量替换。
 */
data class LogcatCondition(
    val triggerId: String,          // ★ 必需：App 侧靠它定位 TriggerSpec
    val tagMatcher: Matcher,        // 编译后的 TAG 匹配器
    val messageMatcher: Matcher,    // 编译后的 message 匹配器
    val minLevel: Char              // 已规范化为 V/D/I/W/E/F 之一
)

sealed interface Matcher {
    object Any : Matcher
    data class Equals(val value: String) : Matcher
    data class Contains(val value: String) : Matcher
    data class Regex(val pattern: kotlin.text.Regex) : Matcher   // ★ 预编译，见下
}
```

⚠️ **必须在 `updateTriggers` 时预编译 `Regex`，切不可在 `matches()` 里现编**。
`Regex(...)` 的构造开销远大于匹配，而这段代码在**每行日志**上执行。
`updateTriggers` 的频率是「用户改配置」，可以在那里付编译成本。

`Any` 用 `object` 而非 `data class` 是为了免去每行比较时的字段读取。

### 4.3 输出

| 输出 | 类型 | 说明 |
|---|---|---|
| `message` | STRING | 消息正文 |
| `tag` | STRING | 解析出的 TAG |
| `level` | STRING | `V`/`D`/`I`/`W`/`E`/`F` |
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

### 5.1 解析与降级（**已修订**）

解析正则：

```kotlin
val THREADTIME = Regex("""^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s?(.*)$""")
```

> **级别字符类必须与 §4.1 的枚举一致**（`[VDIWEF]`）。
> 首版本文档枚举是 `V/D/I/W/E` 而正则含 `F`，两端不一致——要么收 `F` 进枚举（本版的选法），要么解析后归一到 `E`。

⚠️ **还需单独识别日志头标记行**（2026-09-17 真机实测新增，见 `logcat-debug-tool.md` §4.4）：

```
--------- beginning of main        ← 类别分隔标记，不是日志行
--------- beginning of system
--------- beginning of crash
```

这些行**不匹配上面的正则**，因此会落入降级路径，进而**污染「上一条成功解析」的继承链**
（§5.1 的降级语义依赖它）。必须先判掉：

```kotlin
private val BUFFER_MARKER = Regex("""^-{5,}\s*beginning of .+$""")
fun isBufferMarker(line: String): Boolean = BUFFER_MARKER.matches(line.trim())
```

**实测依据**：`-T n` **不保证恰好返回 n 行**（实测 `-T 5` 得 7 行、`-t 5` 得 6 行），
多出来的就是这些标记行。凡依赖「取最近 n 行」的逻辑都不能假定精确行数。

**降级原则（修订）**：解析失败时**不能丢行**，但**不能简单地把 `level` 置 `V`**。

首版原文是「把整行作为 `message`、`tag` 置空、`level` 置 `V`，让条件仍有机会命中」——
这**自相矛盾**：`V` 是最低级别，`min_level` 默认 `I`，因此降级行**必然被级别过滤掉**；
`tag` 置空后，配了 `tag equals` 的触发器也永远不匹配。写成「不丢行」，实际效果等同于丢行。

**为什么会走到降级路径**：App 打带换行的日志时，logcat 会输出多行，只有第一行带前缀：

```kotlin
Log.e("MyApp", "上报失败:\nurl=https://x.com/api\ncode=500")
```

```
09-17 10:00:01.100  9278 15081 E MyApp: 上报失败:
url=https://x.com/api          ← 无前缀 → 降级路径
code=500                       ← 无前缀 → 降级路径
```

用户会观察到「我明明配了 `message contains 'code=500'` 却不触发」——因为那行被 `V` 卡掉了。

**修订后的语义**：无前缀行**在 logcat 语义上属于上一条日志**（这就是 logcat 自己的呈现方式）。
因此降级应当**继承上一条成功解析的 `tag` / `level`**，而不是置空 / 置 `V`：

| 字段 | 首版（错） | 本版 |
|---|---|---|
| `tag` | 置空 | **继承上一条成功解析的 tag** |
| `level` | 置 `V` | **继承上一条成功解析的 level** |
| `message` | 整行 | 整行 |
| 参与匹配 | ❌ 实际上被过滤 | ✅ 正常参与 |

这样级别过滤与 tag 过滤都能正确放行，堆栈与长文本也完整。

**状态与边界**：

- 需缓存「上一条成功解析的日志」的 `tag`/`level`（**Handler 或流对象的实例变量**，
  参照 `trigger-system-overview.md` §4.4 的「基线必须是实例变量」）
- ⚠️ **流重启后首条降级行没有可继承的对象**（§6.4 会重启进程，缓冲已清）。
  此时的策略：`tag` 置空、`level` 置 `min_level` 允许的最低值，并标记为降级行——
  **不要**蒙一个中间级别（比置 `V` 更隐蔽）
- 另一种可选实现是**把降级行续接到上一条的 `message` 尾部**（带换行），
  这样下游拿到的是完整多行文本。代价是需要缓存上一条的完整内容。
  **两种都可接受**，实现时二选一并写明。

### 5.2 待定：`threadtime` vs `epoch`

反编译发现 **Tasker 用的是 `logcat -v epoch`**（不是 `threadtime`）：

```
1694876653.040  9278 15081 I WeChat: 具体消息
└─秒.毫秒─────┘ └pid┘└tid┘ └级┘└─TAG─┘
```

| | `threadtime` | `epoch` |
|---|---|---|
| 时间戳 | `09-16 21:04:13.040` | `1694876653.040` |
| 解析 | 需处理月/日/时/分/秒 | **单一数字，无跨月/跨年歧义** |
| 人类可读 | ✅ 好 | ❌ 差 |
| 可直接暴露为变量 | 需转换 | ✅ Tasker 就暴露了 `epochSeconds`/`epochMilliseconds` |

**Tasker 选 epoch 的理由**：时间戳好解析，且能直接给用户用。

**我们的取舍**：本设计需要的是 `tag` / `message` / `level`，**时间戳仅用于调试输出**——
所以 `threadtime` 对用户更友好。但若将来要暴露「日志时间」给下游变量，`epoch` 更合适。

⚠️ **实现时二选一即可**，解析正则相应调整。**建议先用 `threadtime`**（可读性优先），
若真需要时间变量再换 `epoch`。

### 5.3 是否把 `min_level` 下推给 logcat

**本设计有意不下推**（不像 Tasker 那样把 TAG 写进命令行）：

| 面 | 不下推（本设计） | 下推（Tasker 做法） |
|---|---|---|
| 热更新 | ✅ 条件变化只需 `updateTriggers` | ❌ 改条件必须重启进程 |
| 进程数 | ✅ 恒定 1 个 | ❌ 随条件组合数增长 |
| 每行成本 | ⚠️ 全量行都进 Core 解析 | ✅ 只有关心的行进来 |

**若将来需要优化**，可以取所有触发器 `minLevel` 的**最小值**下推（`*:I` 之类），
仅在「该最小值变化」时才重启进程——这样能在保住大部分热更新能力的前提下减负。
**当前不做**，作为已知优化路径记录。

---

## 6. Core 侧新增：`LogcatStreamWrapper`

### 6.1 接口

实现 `StreamingWrapper`（`core/.../wrappers/StreamingWrapper.kt`），方法：

| method | 作用 |
|---|---|
| `subscribeLogcatStream` | 建立长连接，开始推流；**订阅帧携带初始 conditionList** |
| `updateTriggers` | **全量替换**当前所有 logcat 触发器的条件（走同一 socket 的上行控制帧） |
| `stopLogcatStream` | 停止并释放 logcat 进程 |

⚠️ `StreamingWrapper` 接口本身**没有 reader 参数**，`updateTriggers` 无法通过它投递——
见 §6.5。

### 6.2 为什么用「全量替换」而非增删

```
App 侧触发器任一变化（增/删/改条件）
  → TriggerService 汇总「当前所有 logcat 触发器的条件」
  → updateTriggers(全部)
  → Core 整体替换 conditionList
```

**好处**：无状态同步问题，且**不需要重启 logcat 进程**（因为 logcat 本身不带过滤参数，过滤是 Core 内部逻辑）。这是本方案相对 grep 方案的核心优势之一。

### 6.3 如何注册到 `serviceWrappers`

`BaseWorker` 的流式路由只查 `serviceWrappers`：

```kotlin
// core/.../worker/BaseWorker.kt:177
val wrapper = serviceWrappers[target]      // 类型为 ConcurrentHashMap<String, ServiceWrapper>
if (wrapper is StreamingWrapper) { wrapper.handleStream(...) }
```

而 `serviceWrappers` 的值类型是 `ServiceWrapper`，其构造函数会 `connect()` 去 `ServiceManager` 找系统服务（`wrappers/ServiceWrapper.kt`）。**logcat 不包装任何系统服务**，所以有两条路：

| 选项 | 做法 | 评价 |
|---|---|---|
| **A. 继承 `ServiceWrapper`，用一个不存在的服务名** | `connect()` 内的 `getService` 返回 null → **提前 return**，只打印一行 stderr，**不抛异常、不打 stack trace**；`serviceInterface` 为 null，但 `handleStream` 不依赖它 | ✅ **推荐**，零上游改动 |
| **B. 放宽 `serviceWrappers` 的约束** | 让注册表接受「非 ServiceWrapper 的 StreamingWrapper」（或新增独立的 `streamWrappers` 表） | ⚠️ 语义更清晰，但需改 `BaseWorker`（**上游文件**，按 `FORK.md` 需登记评估）；可作为长期选项 |

> **对方案 A 的订正**：首版本文档写「`connect()` 会失败但被 catch（只打印警告）」。
> 实际比这更干净：`getService` 返回 null 时是**提前 `return`**，
> 不会走到异常分支（`ServiceWrapper.kt:30-33`）。因此 A 的副作用仅有一行 stderr 输出。

**结论：选 A**（零上游改动，符合 fork「控制 diff 面积」原则）。
若将来 A 的语义别扭感成为负担，再考虑 B。

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

**改进（借鉴 Tasker）**：重启前应先**等服务就绪**，而不是死等固定时长。

Tasker 的 `LogcatFlowShizuku.waitBeforeRestart` 做法：

```
1. 若 Shizuku 已可用 → 先等 1 秒，确认不是 stale 状态
2. 然后挂起等待「Shizuku 服务已绑定」的信号
3. 再启动 logcat
```

**为何重要**：vFlow Core 依赖 Shizuku/root 提供 shell 身份。若 Core 刚被杀、Shizuku 尚未重连，
固定 `delay(1000)` 后启动会**再次失败**，陷入「启动→失败→等待→再失败」的循环。

**建议改为**：`delay` 之前先检查 `ShellManager.isShizukuActive()` / `VFlowCoreBridge.ping()`，
不就绪则继续等待（带上限），比固定退避可靠。

**另需处理：缓冲里的历史日志**。

启动 `logcat` 时会吐出缓冲区里的历史日志，可能一次性匹配几百条 → **连环触发**。

| 方案 | 说明 | 评价 |
|---|---|---|
| `-T 1` 从最近一行开始 | logcat 原生参数 | ✅ **推荐**，无副作用 |
| `logcat -c` 先清缓冲 | Tasker 的做法（**已反编译核实**，在启动前单独执行） | ⚠️ 有效但粗暴：**`-c` 清的是全局缓冲区**，会连带清掉用户 / 其他调试工具正在看的内容 |

**结论：用 `-T 1`**，把 `-c` 只当兜底（若实测某 ROM 上 `-T 1` 有问题再用）。

### 6.5 ⚠️ 协议扩展：`updateTriggers` 怎么送达

**首版本文档最大的空洞**：把 `updateTriggers` 列为 wrapper 方法，却没有对应的通信机制。
现有 `StreamingWrapper` 协议**没有任何回传通道**。

#### 6.5.1 现状事实（已核实）

| 环节 | 现状 |
|---|---|
| 接口签名 | `handleStream(method, params, writer)` —— 只有 `writer`，**没有 reader**（`wrappers/StreamingWrapper.kt:14`） |
| Worker 侧 | `handleStream` 收订阅请求后进 `while(true)` 独占循环（`wrappers/shell/IClipboardWrapper.kt:104-121`） |
| Master 侧 | 流式请求命中后 `handleClientLoop` 直接 `return`，该 socket 从此归这条流独占（`VFlowCore.kt:267-269`、`:334-344`） |
| App 侧 | `consumeClipboardStream` 是**纯读**循环，从不向 socket 写入（`VFlowCoreBridge.kt:705-726`） |

**即**：订阅帧发出后，这条连接就再也没有上行数据了。`updateTriggers` **无处投递**。

#### 6.5.2 需要的改动

| # | 改动 | 说明 |
|---|---|---|
| 1 | **订阅帧携带初始 conditionList** | App 首帧就带上全部条件。这样 Core 不必等第二次通信才开始工作 |
| 2 | **App 侧双工** | `VFlowCoreBridge` 需在流连接上**保持 writer**，供后续 `updateTriggers` 写入 |
| 3 | **Core 侧双输入源** | Worker 的 `handleStream` 必须同时阻塞在 **logcat stdout** 与 **socket reader** 上。单线程做不到（读 logcat 会阻塞），需**读 logcat 用独立线程**，主循环用 `BufferedReader.readLine()` 阻塞等控制帧 |
| 4 | **断线重连语义** | App 重连时**重发订阅帧（含当时的 conditionList）**，Core 整体替换。详见下 |

**线程模型**（关键）：

```
handleStream(method, params, writer):
  conditions = parse(params.conditionList)      // 来自订阅帧
  startLogcatPump { line ->                     // ← 独立线程，阻塞读 stdout
      matchAndPush(line, conditions, writer)    //   命中即 write
  }
  loop {                                        // ← 主线程，阻塞读控制帧
      val frame = reader.readLine() ?: break    //   App 关闭连接 → 退出
      when (frame.method) { "updateTriggers" -> conditions = parse(...) }
  }
  stopLogcatPump()
```

⚠️ `conditions` 被两个线程读写 → 需 `@Volatile` 或 `AtomicReference`（整体替换，无部分更新）。

#### 6.5.3 断线重连

**设计选择：订阅帧自带 conditionList，`updateTriggers` 只作增量。**

这样重连语义**天然正确**——App 重连时按当时状态重发完整条件，Core 无需保留上次状态。
若让 Core 保留条件，就要处理「Core 重启但 App 没重连」等一堆边界，不划算。

重连流程照 `ClipboardTriggerHandler`（`:70-105`）：断开后 `delay(1_000)` 重连。

#### 6.5.4 ⚠️ 背压与断流检测

**背压链条**（首版§9 未提）：

```
日志高频 → App 消费慢 → socket 发送缓冲满
  → Core 的 writer.println 阻塞
  → 停止读 logcat stdout
  → logcat 自身缓冲满 → 丢日志
```

**不能只靠「未命中就地丢弃」解决**——命中率高时这一层就失效了。
需要：命中率过高时**限流**（合并或丢弃并计数），或 Core 侧按 `triggerId` 做**粗冷却**。
（§8 的冷却是 App 侧精确冷却；Core 侧的粗冷却只为自保，两者可共存。）

**断流检测**（首版未提）：

现有模式靠 `writer.checkError()` 判对端断开，但**只有发生写才置位**。
日志稀少时 App 断开了 Core 也不知道，线程 + logcat 子进程一起挂着。
需补**心跳写**或独立的连接检测。

> 注：`checkError()` 之所以能被用作探测，是因为 `PrintWriter` 会吞掉 `IOException`，
> 仅在 autoFlush 写入失败时置错误位——**这意味着它对「对端关闭但没写」完全无感**。

### 6.6 `logcat` 可执行路径

写 `ProcessBuilder("logcat", ...)` 有隐患：app_process 环境下的 `PATH` 不确定。
Core 里的现存先例都是 `sh -c`（`BaseWorker.kt:297`）或**绝对路径**（`SystemUtils.kt:41`）。

**用 `/system/bin/logcat`**，或经 `sh -c`。推荐前者（少一层 shell，便于 `destroy()` 精确管进程）。

---

## 7. App 侧改动

| 文件 | 改动 |
|---|---|
| `triggers/LogcatTriggerModule.kt`（新增） | 参数/输出/摘要/校验 |
| `triggers/handlers/LogcatTriggerHandler.kt`（新增） | **继承 `BaseTriggerHandler`**（见 §7.1）；读 socket；冷却；`executeTrigger` |
| `core/workflow/module/ModuleRegistry.kt` | 追加注册一行 |
| `triggers/handlers/TriggerHandlerRegistry.kt` | 追加注册一行 |
| `services/VFlowCoreBridge.kt` | 新增 `streamLogcatEvents()` + `updateLogcatTriggers()`（照 `streamClipboardEvents`，`:653`），**注意需支持上行控制帧**（§6.5.2） |
| `res/values{,-en,-ja}/strings_module.xml` | 文案 |

### 7.1 ⚠️ 基类选择：必须继承 `BaseTriggerHandler`

首版本文档写：「**条件同步时机**：`LogcatTriggerHandler.addTrigger/removeTrigger` 时汇总并调用 `updateLogcatTriggers()`」。

**这做不到**——`ListeningTriggerHandler` 把四个方法全部 `final override`：

```kotlin
// core/workflow/module/triggers/handlers/ListeningTriggerHandler.kt
final override fun start(context: Context) { ... }
final override fun stop(context: Context) { ... }
final override fun addTrigger(context: Context, trigger: TriggerSpec) { ... }
final override fun removeTrigger(context: Context, triggerId: String) { ... }
```

子类**只能**实现 `startListening` / `stopListening`，而这两个只在**不为空↔空的边界**触发。
后果：已有 1 个触发器时再加第 2 个，Core 永远不知道 →
**第 2 个触发器静默不触发**（正是 `trigger-system-overview.md` §8.2 说的「最难查的静默失效」）。

**修法**：照 `VoiceTriggerHandler` / `KeyEventTriggerHandler` 的做法**直接继承 `BaseTriggerHandler`**，
自行实现 `addTrigger` / `removeTrigger`（两处先例：`VoiceTriggerHandler.kt:24`；`KeyEventTriggerHandler` 是唯一不走 `TriggerService` 分发的特例）。

```kotlin
class LogcatTriggerHandler : BaseTriggerHandler() {
    private val listeningTriggers = CopyOnWriteArrayList<TriggerSpec>()

    override fun addTrigger(context: Context, trigger: TriggerSpec) {
        listeningTriggers.removeAll { it.triggerId == trigger.triggerId }
        listeningTriggers.add(trigger)
        syncConditionsToCore()          // ★ 每次增删改都同步
    }

    override fun removeTrigger(context: Context, triggerId: String) {
        listeningTriggers.removeAll { it.triggerId == triggerId }
        syncConditionsToCore()
    }

    private fun syncConditionsToCore() {
        // 汇总 listeningTriggers 的 parameters → updateTriggers(全部)
        // 空列表也要下发（Core 据此停掉 logcat 进程，省资源）
    }
}
```

⚠️ 注意 `BaseTriggerHandler.stop()` 会 `triggerScope.cancel()`，
子类 `stop` 里不能再用它起协程（`trigger-system-overview.md` §9.2 第 3 条）。
需异步清理时要先 `triggerScope.launch { ... }` 再 `super.stop()`。

**`listeningTriggers` 为空时**：应下发空条件让 Core 停掉 logcat 进程，
而不是让 Core 空转（日志量大的设备上白耗 CPU）。

---

## 8. 冷却

```kotlin
if (now - lastTriggerAtMs < cooldownMs) return   // 丢弃，不补发
lastTriggerAtMs = now
```

- **单触发器独立计数**（与 `ElementTriggerState` 一致）
- 默认 `1000ms`，`0` = 不冷却
- 冷却期命中**直接丢弃**（已与用户确认，不做 pending 补发）

> **冷却 vs 去重**：`ClipboardTriggerHandler` 用的是**签名去重**（`lastStandardSignature`，
> 且是 **handler 级共享**的，对所有 STANDARD 触发器是同一个）。logcat 场景下**每次命中
> 都是不同内容**（哪怕 TAG 相同），所以签名去重不适用，用**时间窗冷却**。
> 另注意 Core 侧若也做粗冷却（§6.5.4），两层冷却的语义要有主次：**App 侧为准**，
> Core 侧只是过载自保。

---

## 9. 风险与未验证项

| # | 风险 | 状态 |
|---|---|---|
| 1 | **`ServiceWrapper` 注册方式**（§6.3） | ✅ 倾向方案 A（零上游改动），**需实现时验证** |
| 2 | **高频日志压垮推送 / 背压**（§6.5.4） | ⚠️ 需保护：命中率过高时告警/限流；见 §6.5.4 的完整反压链 |
| 3 | **Core 跑长驻进程 + 高频推流的稳定性** | ⚠️ **未验证**。`IClipboardWrapper` 证明 Core 能做流式推送，但那是**低频**事件；logcat 是**高频**，压力大得多 |
| 4 | **多行堆栈的解析** | ✅ 已定语义（§5.1 降级 = 继承上一条的 tag/level） |
| 5 | **双工协议的实现复杂度**（§6.5） | ⚠️ **本设计最贵的未解项**。需改 `VFlowCoreBridge`（上行）+ Worker（双输入源线程模型） |
| 6 | **断流检测**（日志稀少时 `checkError()` 无感） | ⚠️ 需补心跳写，见 §6.5.4 |
| 7 | **隐私**：全设备日志可能进工作流变量 / 日志文件 / AI 对话 / 远程 API | ⚠️ 见下 |

### 9.1 隐私说明（新增）

`target_package` 留空 = 全设备日志；`raw` 输出是完整原始行。这些内容的流向：

| 去向 | 风险 |
|---|---|
| 工作流变量 | 可能被写入文件 / 发到网络 |
| `DebugLogger` 日志 | 落盘 `vFlow` 日志目录 |
| AI 对话面板 | 若用户把日志内容喂给 AI（`docs/fork/chat-agent-*`），会离开设备 |
| 远程 API（8080） | 若开启，工作流结果可被外部拉取 |

**措施**：在 §11 的文案里提示「留空将捕获全部应用日志，可能包含敏感信息」；
真机场景里补一条「确认日志未写入非预期位置」。

### 9.2 「App 进程内直读」已被实测否决 ✅ 已定论

原第 5 项（App 直读可简化方案）**已通过真机探针彻底否定**，不再是未知数。

**首轮失败的真实原因**（与当时推测不同，值得记录）：

```bash
pm grant com.chaomixian.vflow android.permission.READ_LOGS
# → 输出 "Command executed successfully"
# → 但 granted=false，且重启后仍为 false
```

**根因**：vFlow 的 `AndroidManifest.xml` **未声明** `READ_LOGS`。
`pm grant` 对未在清单声明的权限**不报错但也不生效**。补上声明后：

```
dumpsys package: android.permission.READ_LOGS: granted=true     ← 授权成功
```

**但即便 `granted=true`，仍然读不到其他进程的日志**：

| 指标 | 实测 |
|---|---|
| 本进程日志 | ✅ 能读 |
| 系统日志 | ❌ 读不到 |
| 第三方应用日志（微信/QQ） | ❌ **他进程行数=0** |

**结论：`READ_LOGS` 权限不是决定因素。** 普通应用（uid 10684）读不到其他进程日志，
是因为 Android 对 logcat 有**多层访问控制**，权限只是其中一层：

| 层 | 普通应用 |
|---|---|
| 权限层 `READ_LOGS` | ✅ 可授予（`protectionLevel` 含 `development`） |
| **SELinux 层**（能否访问 `logd` socket） | ❌ **被拒** |
| **logd 白名单**（部分 ROM 只允许特定 uid） | ❌ 被拒 |

**而 shell 用户能读，靠的是组成员资格而非权限**：

```
uid=2000(shell) groups=...,1007(log),...
                        ^^^^^^^^^^ ← log 组
```

**这个身份无法通过任何授权获得**，只能由 Shizuku/root 以 uid 2000 运行进程。

**旁证**：Tasker 同样为 Shizuku 单独实现了 `LogcatFlowShizuku`——
说明它也遇到了同样的限制，并选择了同样的解法。

**因此：Core 方案不是权宜之计，而是此问题在 Android 上的唯一可行解。**
本设计（§2 起的全部内容）保持不变，且依据更充分。

---

## 10. 验证与门禁

**实现阶段**：

```bash
./gradlew testDebugUnitTest --tests "*Logcat*"   # 纯函数单测（见 §12 第 1 步）
./gradlew test                                   # 全量回归
./gradlew :core:buildDex                         # Core 改动必须
./gradlew assembleDebug                          # 构建
```

> ⚠️ **Core 模块没有测试目录**（`core/src/` 下只有 `main/` 和 `cpp/`，0 个测试文件）。
> 因此「解析 / 匹配 / 条件构造」这些逻辑若要单测，**必须放在 app 模块**并保持纯函数、
> 不依赖 `core` 的类。这是 §12 第 1 步要先把纯函数抽出来的原因之一。

**真机场景**（触发器必须真机验证，`AGENTS.md` 验证门禁）：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 单触发器，tag equals | 只在该 tag 时触发 |
| 2 | `message contains` | 包含即触发 |
| 3 | tag + message 同时配 | **AND**（都满足才触发） |
| 4 | 多个触发器共存 | 各自独立触发；**确认 logcat 进程只有一个** |
| 5 | 运行时增删触发器 | 立即生效，**无需重启 logcat**（★ 验证 §6.5 协议） |
| 6 | 冷却期连续命中 | 只触发一次 |
| 7 | `min_level` 过滤 | 低于该级别的日志不触发 |
| 8 | 目标 App 未运行（`target_package`） | 明确报错/提示，**不静默** |
| 9 | `regex` 语法错误 | 保存时即拒绝 |
| 10 | 长时间运行（数小时） | 无内存泄漏、logcat 进程不异常退出 |
| 11 | **多行日志（堆栈 / 带 `\n` 的消息）** | 续行命中 message 条件（★ 验证 §5.1 降级） |
| 12 | **启动瞬间不连环触发** | 缓冲历史日志不触发（★ 验证 `-T 1`） |
| 13 | **日志稀少时杀掉 App** | Core 能察觉断流、回收 logcat 进程（★ 验证 §6.5.4） |
| 14 | **高频日志下的表现** | 不丢关键事件、不卡死（★ 验证 §6.5.4 背压） |

---

## 11. 需要先决策的两点

1. **`target_package` 是否强制必填？**
   空值 = 全设备日志，量极大。倾向**可选但校验时警告**（文案需含隐私提示，见 §9.1）。

2. **两个匹配条件都为 `any` 且 `min_level=V` 时是否允许？**
   = 匹配全部日志。倾向**允许但警告**（有人确实想做全量统计），并在文案里说明性能风险。

---

## 12. 实现顺序建议（**已调整**）

首版把单测排在最后（「3. 最后补打磨……单测」），但 §10 又要求跑 `--tests "*Logcat*"`。
解析 / 匹配 / 降级 / 冷却**都是纯函数**，应当**先写、先测**——这既符合项目「改解析/执行必须补单测」
的门禁，也让后续的真机验证成本更低（纯函数错了，真机上很难定位）。

| 步 | 内容 | 验证 |
|---|---|---|
| **0** | **先做 [`logcat-debug-tool.md`](logcat-debug-tool.md)（调试工具）**：产出 `LogcatParser` / `LogcatCommands` 纯函数 + 单测，并实测缓冲行为与长驻进程存活 | 调试工具的 13 项真机场景 |
| 1 | **复用第 0 步的纯函数**：`parseThreadtime`（含降级）、`Matcher` 匹配、`conditionList` 构造、冷却判定。放 app 模块（Core 无测试目录，见 §10） | `./gradlew testDebugUnitTest --tests "*Logcat*"` |
| 2 | Core wrapper **最小闭环**：跑通 `logcat` → 固定条件 → 推送 → App 触发。**同时验证 §6.5 双工协议**与 §6.3 注册方式 | 真机场景 1、4 |
| 3 | **完整匹配**：`updateTriggers`、全部匹配方式、`-T 1` | 真机场景 2、3、5、7、12 |
| 4 | **健壮性**：背压限流、断流检测、重启退避（§6.4） | 真机场景 10、13、14 |
| 5 | **打磨**：冷却、校验、文案、降级边界 | 真机场景 6、8、9、11 |

> **第 0 步为何前置**：调试工具与触发器共用同一份解析纯函数，且它**不涉及 §6.5 双工协议**——
> 等于用很低的成本先把「解析对不对、降级语义对不对、长驻 logcat 稳不稳」这三件事问清楚，
> 让第 2 步（本设计里风险最高的一步）只剩双工协议一个未知数。
>
> 第 2 步是**风险最高**的一步（双工协议 + Core 长驻进程），建议先只做单触发器的硬编码条件，
> 确认链路通了再上完整匹配。
