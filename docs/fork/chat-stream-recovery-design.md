# Chat 流超时与恢复设计（chat-stream-recovery-design.md）

> **状态：设计稿 v1.3，未实现。** 写于 2026-10-10，基线 `44de0785`。
> **目录归属**：`docs/fork/`，fork 独有文件 ⇒ **冲突归我方**。
>
> **上位文档**：`docs/fork/chat-streaming-design.md`。本文补的正是它 §1.2 明列「第一版不做」的
> **「重试 / 断线续传」**，以及 `ChatSseStream.kt:65-68` 注释点名的 **「长思考（reasoning 阶段零字节）超时」独立课题**。
>
> **外部依据（全部源码直读，非二手描述）**：`D:/develop/references/` 四家——
> `ccb`（Claude Code 第三方复刻，`77a7934e`）、`dsh`（DeepSeek Harness，`c291e796`）、
> `opencode`（`95daf906`）、`pi`（`71dca871`）；外加 **Codex CLI 0.162.0**
> （本机安装的 `codex.exe` 字符串 + 上游源码 `openai/codex@cd2760fc`）。
>
> **行号约定**：vFlow 侧行号锚定 `44de0785`；外部行号锚定上述 commit。上游改动后引用需复核。

---

## 0. 一句话方案

把「**一个 `readTimeout` 管全程、超时即整轮作废**」改成：

**两个可独立配置的空闲阈值（首字节 / 流中，默认都取 300 s）→ 超时分类 → 本轮尚未向 UI 提交任何内容则退避重试（默认 2 次）→ 已提交内容则保留半截正文并如实报错。**

一句话记法：**阈值对齐头部（300 s），恢复对齐头部（重试），判据比头部更保守（只在零内容时重试）。**

---

## 1. 现状与问题

### 1.1 三个事实（均已回代码核对）

| # | 事实 | 位置 | 后果 |
|---|---|---|---|
| F1 | **只有一个空闲阈值**：`connectTimeout 30 s` / `readTimeout 120 s` / `writeTimeout 120 s`，**无 `callTimeout`** | `ChatSseStream.kt:62-71`（SSE 路径）、`ChatCompletionClient.kt:210-215`（非流式路径，现仅 benchmark 使用） | 首字节与流中空闲**共用** 120 s，无法分别处置 |
| F2 | **无重试**：流内任何失败都是终局 | `ChatStreamRunner.kt:54`（`Failure` → `IllegalStateException`）、`:61`（未见结束标记 → 截断异常） | 偶发一次超时 ⇒ **整轮回复作废**，用户要重新发一遍 |
| F3 | **不可配**：`ChatPresetConfig` 无任何网络字段 | `ChatModels.kt:73-84`（`temperature` / `useResponsesApi` 是仅有的两个可调项） | 本地 Ollama、中转网关、reasoning 模型共用同一个硬编码值 |

### 1.2 超时分类缺失，是比「数字偏小」更本质的问题

同一个 `SocketTimeoutException` 承载**两种语义完全相反**的情形：

| 情形 | 已收到的内容 | 正确处置 | 现状 |
|---|---|---|---|
| **首字节超时**（服务端没开始生成） | 零 | **重试**（用户无感，重发无损） | ❌ 整轮失败 |
| **流中空闲**（服务端发了一半卡住） | 可能有半截正文 | **保留已读内容 + 报错**（重试会抹掉用户已读到的字） | ❌ 整轮失败（半截正文虽被 `finishWithError` 保留，但没有重试机会） |

⚠️ 这正是 `ChatSseStream.kt:65-68` 注释里写的「若将来要做**首字节超时 / 流中空闲超时**分离，改这里」。
**本轮就是去做那件事**，并且补上它没做的另一半：分类之后的**恢复**。

### 1.3 用户可见的失败形态（现象 → 归因）

| 用户看到 | 归因 | 本轮是否覆盖 |
|---|---|---|
| 「思考了很久，突然跳出 `timeout`，一个字的回复都没有」 | 首字节超时（reasoning 阶段零字节 > 120 s） | ✅ 重试后大概率自愈 |
| 「回复写到一半停住，然后报 `timeout`，后半段没了」 | 流中空闲 | ✅ 保留 + 报错（**不重试**） |
| 「一直转圈，没有任何报错」 | 服务端只发 keep-alive、从不发事件 | ❌ 本轮**不**处理（见 §7 未做项，与头部一致） |
| 「点了停止，连接还占着」 | 取消未 `cancel()` | 已修（`ChatSseStream.kt:146` `awaitClose`），本轮不动 |

### 1.4 ⚠️ 顺带记录的既有缺陷（本文件范围内一并修）

`ChatSseStreamTest.kt:36` 定义了一个 **2 秒超时的 `client`**，注释写着
「⚠️ 超时必须远小于生产环境的 120s readTimeout，否则挂死用例要跑两分钟」——
**但该字段从未被使用**（全文件仅此一处出现），因为 `ChatSse.frames(request)` **只接一个参数**，
内部用的是模块私有的 `ChatSse.client`（120 s）。

⇒ 两件事同时成立：
1. 那条注释**与事实不符**（现有用例之所以能过，是因为 MockWebServer 都立即响应，没有任何用例依赖读超时）；
2. **SSE 超时路径目前无法被测试**（没有注入缝）—— 而本轮要加的恰恰就是超时路径。

**本设计必须提供一个注入缝**，否则 §6 的验证只能靠真机（不可接受）。

---

## 2. 外部依据：五家头部 Agent 怎么决策

### 2.1 对照表

| 项目 | 机制 | 默认值 | 超时后 | 可配 |
|---|---|---|---|---|
| **Codex CLI** | `stream_idle_timeout`，注释：*"Idle timeout to wait for activity on a streaming response before treating the connection as lost"* | **300 s**（`DEFAULT_STREAM_IDLE_TIMEOUT_MS`） | **重连这条流**，`stream_max_retries = 5`；另有 `request_max_retries = 4`、`retry_5xx = true`、`retry_429 = false`、`base_delay = 200 ms` | `stream_idle_timeout_ms`（**按 provider**） |
| **DeepSeek Harness** | `idleWatchdog`：**只在 iterator `next()` 未完成时**计时，`pulse()` 在「有传输活动但产不出值」时重新计时 | **300 s**（`DEFAULT_STREAM_IDLE_TIMEOUT_MS`） | 归类 `TIMEOUT` → **默认可重试**（5 次，500 ms→10 s，**10% 抖动**）；SDK 场景 bundle 覆写为 48 h | `streamIdleTimeoutMs`（必须正有限，上限 `MAX_TIMER_DELAY_MS`） |
| **OpenCode** | `chunkTimeout`（SSE body 逐块计时）+ **独立的** `headerTimeout` | **各 300 s** | 抛 retryable stream error → 重试（`base * 2^attempt * [0.8, 1.2]`，有上限） | `chunkTimeout` / `headerTimeout` / `timeout`，`false` 可关 |
| **Pi** | 自己**不做**流级看门狗，把一个 `timeoutMs` 透传给 provider SDK；另设独立的连接超时 | 跟随 SDK（注释写明 OpenAI/Anthropic SDK 默认 **10 min**）；WS 连接超时另算 | SDK 自行 abort；重试只覆盖到响应头为止 | `timeoutMs` / `websocketConnectTimeoutMs` |
| **Claude Code**（复刻 `ccb`） | 主动 `setTimeout` 看门狗（45 s 先 warn）+ 被动 30 s stall **仅记日志** | **90 s**（`CLAUDE_STREAM_IDLE_TIMEOUT_MS`） | **降级为非流式请求**（`executeNonStreamingRequest`），整体仍在 `withRetry` 内；遥测 `fallback_cause = 'watchdog'` | 环境变量；⚠️ 看门狗挂在 `CLAUDE_ENABLE_STREAM_WATCHDOG` 之后 |

### 2.2 六条共同决策（这才是要抄的东西）

1. **是「空闲」而不是「总时长」。** 五家没有一家用 `callTimeout` 语义卡整轮。OpenCode 的 `headerTimeout` 文档特意写明 *"This timer stops once headers arrive and does not limit the streamed response body"*。
2. **只在「读还没返回」时计时。** dsh 把这条写进了库文档：*"The timer is armed only while an iterator `next()` is outstanding … so consumer think time between reads never counts as idle"*。
3. **任何传输字节都算「活着」，哪怕它不产生事件。** dsh 的 `pulse()` 专为「有传输活动但产不出值」而设，并有测试 `keeps an idle provider read alive through SSE comments`——用 `: keep-alive\n\n` 喂进去，100 ms 看门狗**不**触发（`dsh/packages/llm/llm-deepseek/tests/adapter.spec.ts:1603-1638`）；OpenCode 的 `wrapSSE` 同样按**字节块**重置，不按解析出的事件。
4. **首字节与流中分开处理。** `ccb` 的被动 stall 检测故意用 `lastEventTime = null` 跳过第一个 chunk（注释：*"Set after first chunk to avoid measuring TTFB as a stall"*）；OpenCode 拆成 `headerTimeout` / `chunkTimeout` 两个旋钮；Codex / Pi 另有独立的**连接**超时。
5. **超时是「可恢复错误」，不是终局。** Codex 重连流、`ccb` 降级非流式、dsh / OpenCode 重试——**没有一家在第一次超时时就把这轮判死**。
6. **值按 provider 可配。** 本地模型与云端网关的行为差太远，硬编码必然有人踩。

### 2.3 证据可信度分级（引用时不得混同）

| 级别 | 内容 |
|---|---|
| **源码级**（最高） | dsh / OpenCode / Pi / `ccb` 的四个仓库源码；Codex 的上游 `model-provider-info/src/lib.rs` |
| **二进制字符串级** | Codex CLI 0.162.0 本机 `codex.exe`（确认 `stream_idle_timeout_ms` / `stream_max_retries` / `request_max_retries` 字段与 `idle timeout waiting for SSE` 文案存在，**默认值来自上游源码**） |
| **文档级** | OpenCode `packages/web/src/content/docs/config.mdx:394-396`（官方文档明确写了三个默认值 300000） |
| ⚠️ **不可当权威** | `ccb` 是**第三方复刻**，不是 Anthropic 官方仓库；其 90 s 与 `CLAUDE_ENABLE_STREAM_WATCHDOG` 开关**是否与真实 Claude Code 一致，无法从本地证据确认** |

---

## 3. 数值决策

### 3.1 取值表

| 参数 | 取值 | 依据 | 为什么不是别的 |
|---|---|---|---|
| `streamIdleTimeoutSeconds` | **300 s**（**单一阈值**，同时覆盖首字节与流中两个阶段） | **Codex / dsh / OpenCode 三家一致 300 s**（五家里三家、且是三个独立团队） | `ccb` 的 90 s 是**唯一**异类，且它背后有「降级非流式」这条我们暂不做的兜底（§3.4）。⚠️ **为什么只有一个阈值**见 §3.2（v1.1 修正） |
| 空闲判据 | **字节级**（socket 读），不是事件级 | dsh `pulse()` 语义 + 其 `: keep-alive` 测试；OpenCode `wrapSSE` 按 chunk 字节重置 | 事件级判据会把「服务端在发 keep-alive 但没内容」误判成超时 |
| `streamMaxRetries` | **2**（= 最多 3 次尝试） | **刻意偏离**头部的 5，理由见 §3.3 | 常量独立，**改一行即可对齐头部** |
| `streamMaxRetries` 的夹取范围 | **0..5** | 上限 = Codex `DEFAULT_STREAM_MAX_RETRIES`；`0` = 关闭重试 | v1.0 写的「按 `MAX_STREAM_MAX_RETRIES = 100` 夹住」是抄了 Codex 的**硬上限宽度**（那是防呆值），手机上无意义（v1.1 修正） |
| 退避 | **1 s 起步，×2，上限 10 s，±10% 抖动** | dsh 的 500 ms→10 s + 10% 抖动；Codex `base_delay` 200 ms；OpenCode `base * 2^n * [0.8, 1.2]` | 落在三家区间的并集内 |
| `connectTimeout` | **保持 30 s** | 与头部**不可比**：Codex 的 15 s 是 **WebSocket 握手**，Pi 的 `websocketConnectTimeoutMs` 同理 | 本轮不动（TCP 建连与本课题无关） |
| 总时长上限（`callTimeout`） | **不加** | Codex / dsh / `ccb` **都没有**；只有 OpenCode 有（`timeout` 默认 300 s） | 见 §3.4 与 §7 |

### 3.2 ⚠️ 为什么**只留一个**阈值：首字节 / 流中是**分类**，不是两个可配数值

第一直觉是「首字节给短一点（比如 60 s），流中给长一点」——**这是错的**，而且**恰好错在本需求的起点上**：

> 「长思考」在协议上就是**首字节之前的一段长时间静默**。把首字节阈值设短，等于**专门去杀**我们本来想救的那个场景。

⚠️⚠️ **v1.1 修正（初稿的实质缺陷）**：v1.0 说「取同一个默认值，但**保留两个独立的配置项**」——**这条落不了地**。
OkHttp 每个 client 只有**一个** `readTimeout`，两个旋钮必须组合成一个值，而任何组合规则都会**静默忽略一个方向**：

| 组合规则 | 静默失效的方向 | 后果 |
|---|---|---|
| 取 **min** | 「把首字节放大」 | **正是本需求要修的方向** —— 用户以为放宽了，实际没放宽（reasoning 网关场景失效） |
| 取 **max** | 「把流中空闲调小」 | 想「快点失败」的收紧意图被忽略 |
| **真两阶段**（首事件前用 A、之后切 B） | —（唯一无静默失效的形态） | 要改 **Okio 层 body source 的 timeout**（超出 §5.2 的改动清单）；若改用应用层看门狗，会**丢掉「keep-alive 注释也算活跃」这条字节级语义**（dsh 有测试锁它） |

⇒ **决定：只留一个阈值 + 保留分类。**

- 配置面收敛为 **一个** `streamIdleTimeoutSeconds`（默认 300 s），它就是那个 `readTimeout`；
- `sawFirstEvent` **仍然保留**，但只用于**分类与诊断**（决定「可重试 vs 保留半截正文」，§4.3），**不再是一个独立配置项**；
- 「reasoning 网关要等更久」这个诉求由**重试**兜住（§4.3），不需要第二个旋钮。

头部佐证：Codex / dsh / `ccb` 本来就是**单值**；OpenCode 的双值（`headerTimeout` / `chunkTimeout`）是**真两阶段**——
它的 `headerTimeout` 在响应头到达时即停，不是 min/max 组合。**没有任何一家做组合规则。**

「两阶段真独立」列入 §7 未做项（独立课题，需 Okio 层手术 + 真机验证）。

### 3.3 为什么重试次数取 2 而不是头部的 5

三家的 5 次都是**CLI / 常驻进程**的语境，且 Codex 的 `stream_max_retries` 语义是 **「重连」**（`Number of times to retry reconnecting a dropped streaming response`），不是「重发一次完整请求」。我们的重试是**整段上下文重发**：

| 理由 | 说明 |
|---|---|
| ① 触发条件已被收窄 | 只在「零内容」时重试（§4.3），这本身已是罕见路径；300 s 静默 × 3 次 ≈ 最坏 15 分钟，再往上加收益趋零 |
| ② 成本结构不同 | 移动端按 token / 流量计费，每次重发都是完整 system prompt + 历史（本项目的 skill 目录 + 工具定义并不小） |
| ③ 用户在场 | CLI 可以挂着跑；手机用户面对的是一个「已经等了 5 分钟」的界面 |

⚠️ **这是一处有意偏离，不是遗漏**：`ChatStreamRecoveryPolicy.DEFAULT_MAX_RETRIES = 2` 是独立常量，
若日后要完全对齐头部，**改这一个数**即可。字段的夹取范围取 **0..5**（上限 = Codex `DEFAULT_STREAM_MAX_RETRIES`；
`0` = 关闭重试）——**不是** Codex 那个 `MAX_STREAM_MAX_RETRIES = 100`，那是它的防呆硬上限，不是推荐区间。

### 3.4 明确**不取**的值与理由

| 候选 | 来源 | 为什么不取 |
|---|---|---|
| **90 s** | `ccb` | 它是**唯一**异类；且其 90 s 之所以敢这么短，是因为背后有「降级非流式」兜底。我们本轮不做非流式降级（§7），只取 90 s 会得到「更早失败 + 没有兜底」的最差组合 |
| **600 s（10 min）** | Pi（跟随 SDK 默认） | Pi 是把控制权交给 SDK 的**被动**做法，不是主动决策；且它是四家里唯一**没有**自己的流级看门狗的 |
| **15 s** | Codex `websocket_connect_timeout_ms` | 语义是 WebSocket 握手，**不是** SSE 空闲，不可比 |
| **`callTimeout`（总时长）** | OpenCode `timeout` 默认 300 s | 见 §7 未做项：我们有「多轮工具调用 + 长回答」，总时长上限会误杀；且三家没有 |

---

## 4. 设计

### 4.1 分层（每层只做一件事，全部可纯 JVM 单测）

```
ChatSseStream.kt        传输层：把 HTTP 长连接变成 SseFrame 流
   └─ 【新增】超时分类：谁超的（首字节 / 流中 / 连接）
ChatStreamRunner.kt     归约层：帧流 → 事件流；【改】把超时类失败映射成带类型的异常
ChatStreamRecovery.kt   【新增】恢复层：重试判据 / 退避 / 预算（纯 Kotlin，零 Android 依赖）
ChatViewModel.kt        编排层：【改】把 streamReply 包一层，重试期间发一条用户可见提示
```

**关键约束：恢复层不认识 HTTP，也不认识 UI。** 它只接收「一个能开出事件流的工厂」+「策略」，输出「事件流」。
这样它 100% 可纯 JVM 单测（`ChatStream{Assembler,Normalizer}Test` 的既有形态）。

### 4.2 超时分类（传输层，`ChatSseStream.kt`）

`ChatSse.frames()` 内部已有 `onEvent` / `onFailure` 两个回调。分类只需**一个本地布尔**：

```kotlin
var sawFirstEvent = false      // 在 onEvent 里置 true（唯一写入点）
// onFailure 里：
//   t is SocketTimeoutException && response == null            -> NO_RESPONSE_TIMEOUT（v1.2 改名，见下）
//   t is SocketTimeoutException && !sawFirstEvent              -> FIRST_BYTE_TIMEOUT
//   t is SocketTimeoutException &&  sawFirstEvent              -> IDLE_TIMEOUT
//   其余                                                        -> TRANSPORT
```

三个要点：

1. **不需要自己写看门狗。** OkHttp 的 `readTimeout` 就是 socket 级空闲计时器，**任何字节都会重置它**——
   于是 §2.2-③「keep-alive 也算活着」**自动成立**，无需在应用层解析注释行
   （`okhttp-sse` 的 `ServerSentEventReader` 会把 `: keep-alive` 读掉丢弃，应用层根本看不到它）。
   ⚠️ 这一点已核对字节码：`RealEventSource` **没有**覆写任何 timeout，`ServerSentEventReader` 走 `readUtf8LineStrict()`，
   即 120 s（现）/ 300 s（改后）原样作用于 socket 读。
2. **首字节的定义是「收到第一个 SSE 事件之前」**，由同一个 `readTimeout` 实现，
   因此它覆盖「响应头来了但 body 一直空」这一形态；而「**响应头都没到**」（`response == null`）
   落在 `NO_RESPONSE_TIMEOUT` —— **v1.2 起它同样可重试**，见下方 ⚠️。
3. `SseFrame.Failure` **加一个 `cause` 字段**（默认值保证既有构造点不变）：

```kotlin
data class Failure(
    val message: String,
    val httpCode: Int? = null,
    val cause: SseFailureCause = SseFailureCause.TRANSPORT,   // 新增，带默认值
) : SseFrame
```

⚠️⚠️ **v1.2 修正：`response == null` 也必须可重试，且不该叫 `CONNECT_TIMEOUT`。**

方案阶段指出，并用 okhttp-sse 4.12.0 的字节码核实：`response == null` ⇔ **压根没拿到响应**，
它同时覆盖「TCP 建连阶段超时」与「**连上了、但响应头一直没来**」——后者在**缓冲型网关 / 反代**上很常见，
而它恰恰就是「长思考」在本客户端的表现。v1.1 把它归到「不重试」的 `CONNECT_TIMEOUT`，
与本节第 2 点「首字节之前也包括响应头未到」**自相矛盾**，会让本需求要救的场景**有一半救不回来**。

⇒ 改名 **`NO_RESPONSE_TIMEOUT`**（名字不再撒谎）+ **映射为 `ChatStreamTimeoutException`（可重试）**。

代价（如实记录）：真·黑洞网络下会多花最多 2 次重试（每次最长 `connectTimeout` 30 s）；
但 DNS 失败 / `connection refused` 这类**快速失败**不是 `SocketTimeoutException`，走 `TRANSPORT`、不重试，不受影响。
头部先例：Codex 的 `ApiRetryConfig { retry_transport: true }`。

### 4.3 恢复判据：**本轮尚未向 UI 提交任何内容**

这是本设计里**唯一**新增的语义判断，也是最需要写死的一条：

> **可重试 ⟺ 本轮没有发出过 `TextDelta` / `ReasoningDelta` / `Completed`。**

依据（已回代码核对）：

- `ChatViewModel.kt:907-925`：只有 `TextDelta` / `ReasoningDelta` 会 `patchStreamingMessage`（**唯一的 UI 写入点**）；
- `ChatViewModel.kt:929-934`：`ToolCallDelta` / `ToolCallCompleted` / `Usage` **一律 `-> Unit`**，不进 UI、不进任何局部变量；
- `ChatViewModel.kt:927`：`Completed` 只写 `result` 一个局部变量。

⇒ 「未发出过这三类事件」等价于「**UI 与 VM 状态都可无损丢弃**」——重试不需要回滚任何东西，
既不用清 `streamedContent` / `streamedReasoning`，也不用撤销消息补丁。**这条判据本身就是可重试性的证明。**

⚠️ 顺带覆盖了用户的原始问题：**「长思考超过两分钟没有输出」= 零内容 = 最该重试的那一类**。

### 4.4 恢复流程（新增 `ChatStreamRecovery.kt`）

```kotlin
internal class ChatStreamTimeoutException(
    val kind: SseFailureCause,        // FIRST_BYTE_TIMEOUT / IDLE_TIMEOUT
    val elapsedHintMs: Long?,
    message: String,
) : IllegalStateException(message)

internal data class ChatStreamRecoveryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,   // 2
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 10_000,
    val jitterRatio: Double = 0.10,
) { companion object { const val DEFAULT_MAX_RETRIES = 2 } }

internal data class ChatStreamRetryNotice(
    val attempt: Int,          // 1-based：第几次重试
    val maxAttempts: Int,
    val kind: SseFailureCause,
    val delayMs: Long,
)

/**
 * @param openStream ⚠️ **必须是「每次调用开一条新流」的工厂**，不能是 Flow 实例（见 §4.5）。
 */
internal fun streamWithRecovery(
    policy: ChatStreamRecoveryPolicy,
    onRetry: (ChatStreamRetryNotice) -> Unit,
    openStream: () -> Flow<ChatStreamEvent>,
): Flow<ChatStreamEvent> = flow {
    var attempt = 0
    while (true) {
        var committed = false          // 本轮是否已向 UI 提交过内容
        try {
            openStream().collect { event ->
                if (event.commitsToUi()) committed = true
                emit(event)
            }
            return@flow
        } catch (timeout: ChatStreamTimeoutException) {
            if (committed || attempt >= policy.maxRetries) throw timeout
            attempt++
            val delayMs = policy.backoffFor(attempt)
            onRetry(ChatStreamRetryNotice(attempt, policy.maxRetries + 1, timeout.kind, delayMs))
            delay(delayMs)
        }
    }
}

/** 判据见 §4.3。⚠️ 只有这三类算「已提交」。 */
private fun ChatStreamEvent.commitsToUi(): Boolean = when (this) {
    is ChatStreamEvent.TextDelta, is ChatStreamEvent.ReasoningDelta, is ChatStreamEvent.Completed -> true
    else -> false
}
```

⚠️⚠️ **只 catch `ChatStreamTimeoutException`，绝不 `catch (e: Throwable)`**：
用户点「停止」时 `stopAgent()` 会 `cancel()` 本协程，取消异常**必须原样冒泡**——
写成宽 catch 的后果是「点了停止，它自己又偷偷重试一次」。
`delay()` 可取消，因此退避期间点停止同样立即生效。

### 4.5 ⚠️⚠️ 为什么 `openStream` 必须是工厂，不能传 Flow 实例

这是本设计里**最容易写错、且错了不报错**的一处：

```kotlin
// ChatCompletionClient.kt:324-326（OpenAI 路径）与 :691-693（Anthropic 路径）
return ChatStreamRunner.run(
    frames = ChatSse.frames(httpRequest),
    assembler = ChatStreamAssembler(protocol),   // ⚠️ 在 stream() 调用时创建，**不在 flow 构建器内**
)
```

`ChatStreamAssembler` 是**有状态**的（累积 delta、`sawTerminator`、usage 合并）。
它在 `stream()` 被调用的那一刻就 new 出来了，**位于 `flow { }` 之外**。

⇒ 如果恢复层**重复 collect 同一个 Flow 实例**，第二次会拿到**同一个、已经跑脏的归约器**：
`sawTerminator` 已是 true（F7 截断判据失效）、`content` 里叠着上一轮的半截正文
⇒ 表现为「回复里出现两遍开头」或「截断的流被判成正常收尾」，**两条都不报错**。

⇒ 因此 API 形状定为 **`openStream: () -> Flow<ChatStreamEvent>`**，
每次尝试都重新调 `chatClient.streamReply(...)`，从而得到**新的 adapter、新的 assembler、新的 HTTP 请求**。

⚠️ 这也是**为什么本次不改 `ChatStreamRunner` 的签名**（把 assembler 改成工厂）：
工厂放在调用侧就能解决，改动全部落在 fork 自有文件里，`ChatCompletionClient` 只需超时接线那 2 行（§5.2）。

### 4.6 用户可见性

| 时点 | 用户看到 | 实现 |
|---|---|---|
| 首次超时、准备重试 | 占位气泡仍显示「正在思考…」（**不变**），另发一条瞬时提示：「网络空闲超时，正在重试（1/2）…」 | `ChatViewModel._events.tryEmit(...)`（`ChatViewModel.kt:73`，`MutableSharedFlow<String>`，已有通道，**零模型改动**） |
| 重试成功 | 正常流式输出（用户只多等了 1 s 退避） | 无需特殊处理 |
| 重试耗尽 | 现状行为：半截正文保留 + 错误作为**独立消息**追加（`finishWithError`，`ChatViewModel.kt:1307`） | 错误文案里带上「已重试 N 次」 |

⚠️ **刻意不做**「倒计时 / 阶段进度条」：那需要动 `ChatMessage` 模型（上游文件）与 `ChatMessagePatch` 纯函数层，
收益是「让 5 分钟的等待更好看」，与本课题的「别把能救的轮次救回来」相比优先级低。列入 §7 未做项。

### 4.7 配置项（`ChatPresetConfig`）

```kotlin
@Serializable
data class ChatPresetConfig(
    // …既有字段不动…
    val streamIdleTimeoutSeconds: Int = 300,  // 新增（单一阈值，见 §3.2）
    val streamMaxRetries: Int = 2,            // 新增
)
```

**兼容性**（已核对 `ChatPresetRepository.kt:32-35`：`ignoreUnknownKeys = true` + `encodeDefaults = true`）：

- 旧数据无这两个键 ⇒ 走默认值 ✅；
- 新数据多这两个键 ⇒ 旧版本读时被 `ignoreUnknownKeys` 忽略 ✅；
- 备份/恢复走同一份序列化 ⇒ **自动随 `chat` 范围一起备份**，无需改 `ChatScope`。

**取值校验**（放在 `ChatStreamTimeouts` 的构造函数里，不放在 UI）：超时 `1..3600` 秒、重试 `0..5`；
越界时**夹住并记日志**，不抛异常——「用户在设置页手输 99999 导致聊天直接不可用」是比「用了不理想的值」更糟的失败。

⚠️ **UI 落点是「预设编辑弹窗 `ModelEditorDialog`」，不是供应商配置卡**。v1.0 给的六个行号是**误标**，见 §5.2。

### 4.8 与既有机制的关系

| 机制 | 关系 |
|---|---|
| **工具调用轮**（`approveToolCalls` → 下一轮 `streamReply`） | 每轮**独立**计时与重试。重试预算**不跨轮共享**（否则「第 5 轮偶发一次超时」会被第 1 轮的账吃掉） |
| **`stopAgent()`**（`ChatViewModel.kt:303`） | 取消必须穿透恢复层（§4.4）。重试期间点停止 = 立即停，不重试 |
| **会话队列**（`processNextQueuedPromptIfIdle`） | 重试发生在 `currentAgentJob` 内部，`isSending` 全程为 true ⇒ 队列行为不变 |
| **非流式路径**（`generateReply`，仅 `ChatBenchmarkRunner.kt:352`） | 两个 client 的超时值改为**同源常量**（§5.2-②），消除「两处各硬编码一个 120 s」的漂移源。基准测试因此最长等待也变成 300 s，行为无害 |
| **F7 截断判据**（`ChatStreamRunner.kt:60-63`） | 不受影响：截断仍是截断（不重试）；只有 `SocketTimeoutException` 走恢复层 |

---

## 5. 实现清单

### 5.1 新增文件（**冲突归我方**）

| 文件 | 内容 |
|---|---|
| `ui/chat/ChatStreamTimeouts.kt` | `ChatStreamTimeouts(idleMs, connectMs)` + `DEFAULT` + 校验/夹取 + `fromPreset(preset)` + `toClient()`。**两个 client 的唯一真值来源**（⚠️ v1.3：构造参数由三个收敛为两个，`firstByteMs` 已随 §3.2 一并作废） |
| `ui/chat/ChatStreamRecovery.kt` | `ChatStreamTimeoutException` / `ChatStreamRecoveryPolicy` / `ChatStreamRetryNotice` / `streamWithRecovery(...)` / `commitsToUi()`（§4.4）。**纯 Kotlin，零 Android 依赖** |
| `test/ui/chat/ChatStreamRecoveryTest.kt` | 恢复层单测（§6.1） |
| `test/ui/chat/ChatSseTimeoutTest.kt` | 超时分类单测（§6.1，MockWebServer + 注入缝） |
| `docs/fork/chat-stream-recovery-design.md` | 本文件 |

### 5.2 改动文件

| 文件 | 改动 | 归属 |
|---|---|---|
| `ui/chat/ChatSseStream.kt` | ① `client` 由硬编码改为**按超时配置构造**（缓存 `ConcurrentHashMap<ChatStreamTimeouts, OkHttpClient>`）；② `frames(request, timeouts)` 加参数（**带默认值** ⇒ 既有调用点与测试不必改）；③ `onEvent` 置 `sawFirstEvent`；④ `onFailure` 做分类并写进 `SseFrame.Failure.cause`；⑤ `SseFrame.Failure` 加 `cause` 字段（带默认值） | **我方**（fork 新增文件） |
| `ui/chat/ChatStreamRunner.kt` | 失败分支按 `cause` 分流：**`NO_RESPONSE_TIMEOUT`（v1.2 并入）/ `FIRST_BYTE_TIMEOUT` / `IDLE_TIMEOUT` → `ChatStreamTimeoutException`**，其余保持 `IllegalStateException`（**既有错误文案不变**，避免影响既有测试与用户可见文案） | **我方** |
| `ui/chat/ChatCompletionClient.kt` | **2 行**：`:325` 与 `:692` 的 `ChatSse.frames(httpRequest)` → `ChatSse.frames(httpRequest, ChatStreamTimeouts.fromPreset(request.preset))`；`sharedHttpClient`（`:210-215`）改为从 `ChatStreamTimeouts.DEFAULT` 取数 | **手动合并**（已认长期分叉） |
| `ui/chat/ChatViewModel.kt` | `:901-905` 的 `chatClient.streamReply(...).collect { }` 包成 `streamWithRecovery(policy, onRetry = { _events.tryEmit(...) }) { chatClient.streamReply(...) }.collect { }`；`finishWithError` 的错误文案带上重试次数 | **手动合并**（此处已有 fork 改动） |
| `ui/chat/ChatModels.kt` | `ChatPresetConfig` 加**两个**字段（带默认值，见 §4.7） | **手动合并** |
| `ui/settings/ModelConfigActivity.kt` | **预设编辑弹窗 `ModelEditorDialog`** 加「网络」分组：流中空闲超时（秒）+ 最大重试次数。⚠️⚠️ **v1.1 修正**：v1.0 给的六个行号（`133-134`/`257-258`/`349-350`/`445-446`/`610-619`/`717`）经方案阶段回代码核实，**全部是供应商配置卡**（`ProviderDraft` + `ProviderConfigurationCard`，编辑的是 `ChatProviderConfig`），与「字段落 `ChatPresetConfig`」自相矛盾 —— 是初稿把 `ProviderDraft` 误写成 `PresetDraft`。**实现以预设弹窗为准，精确插入点由方案阶段回代码确定并写进 plan** | **手动合并** |
| 三语 `strings*.xml` | 新增 **5 条**文案（分组标题 ×1 + 两个标签 + 两个说明），中/英/日同步。⚠️ 追加前先 `grep` 键名防重复 —— 方案阶段已发现 **`label_network`（「网络」）已存在**，复用会**构建失败** | **手动合并** |
| `test/ui/chat/ChatSseStreamTest.kt` | ⚠️ 删掉那个**从未被使用**的 `client` 字段与**与事实不符**的注释（§1.4），改用新的注入缝 | **我方** |
| `FORK.md` | 在「Chat Agent 架构重构（第二批）」表末尾**追加**本轮各行（新增文件 3 行 + 改动文件 5 行） | **我方** |

### 5.3 ⚠️ 两处「看起来该改但不改」

1. **`ChatStreamRunner` 的签名不动**（assembler 不改成工厂）—— 理由见 §4.5。
2. **`ChatAgentModuleExecutor` 的 120 s 不动** —— 那是**临时工作流执行**的 `withTimeout`（`:2176`，`maxExecutionTime` 默认 120 s），与网络无关。本轮**只**在文档里点破，避免下次又有人把两个 120 s 混为一谈。

---

## 6. 验证

### 6.1 单测

| 文件 | 用例（重点） |
|---|---|
| `ChatStreamRecoveryTest.kt`（纯 JVM，假流） | ① 零内容 + 首字节超时 → **重试且事件不重复**；② 已发 `TextDelta` 后超时 → **不重试**、原异常上抛；③ 预算耗尽（第 3 次）→ 上抛；④ `onRetry` 收到递增 attempt 与正确的 `kind`；⑤ 退避时长落在 `[base*0.9, cap*1.1]`；⑥ **`CancellationException` 必须穿透**（反证：改成宽 catch 会红）；⑦ 非超时异常（HTTP 400 / 截断）**不重试**；⑧ `Completed` 后不再有任何事件 |
| `ChatSseTimeoutTest.kt`（MockWebServer + 注入缝，超时设 **200 ms**） | ① **响应头已到 + 首块不含事件** → `FIRST_BYTE_TIMEOUT`（⚠️ v1.2：`SocketPolicy.NO_RESPONSE` **落不到**这个 cause —— 方案阶段已用字节码核实，原写法互斥）；② 先发一个事件再 `STALL` → `IDLE_TIMEOUT`；③ 服务端只发 `: keep-alive\n\n` → **不超时**（对齐 dsh 的 `pulse` 测试）；④ `SocketPolicy.NO_RESPONSE` → `NO_RESPONSE_TIMEOUT`，并断言它**可重试**；⑤ 非 2xx → `TRANSPORT`（既有行为不变） |

⚠️ 用例 ③ 是**「字节级 vs 事件级」判据的唯一机器化守卫**——它红了说明有人把判据改成了「收到事件才算活着」。

### 6.2 反证要求（本仓库惯例：断言必须实际变红过）

| 反证动作 | 期望变红 |
|---|---|
| 把 `commitsToUi()` 的 `TextDelta` 分支去掉 | 用例 ② |
| 把 `catch (timeout: ChatStreamTimeoutException)` 改成 `catch (e: Throwable)` | 用例 ⑥ |
| 把 `openStream` 工厂改成复用同一个 Flow 实例 | 用例 ①（事件重复）或新增的「assembler 状态污染」用例 |
| 把 `sawFirstEvent` 恒置 false | 用例 ②（分类错） |
| ~~把 keep-alive 判据改成事件级~~ —— ⚠️ **v1.3 作废：本实现下无落点** | — |

⚠️ **v1.3：上一条反证作废，理由如实记录。** 「keep-alive 也算活跃」这条字节级语义在本实现里
**由 OkHttp 的 socket 级 `readTimeout` 天然提供**（§4.2 第 1 点），代码里**没有应用层判据可改**——
所以这条反证**改不出红**。真正守卫该语义的是**用例②与用例③的组合**：
③（只发 `: keep-alive` ⇒ 不超时）单独看是**弱断言**（把 `readTimeout` 设成 0 也照样通过），
必须配上 ②（发一个事件后 `STALL` ⇒ 报 `IDLE_TIMEOUT`，它要求 `readTimeout` 非 0）才能把
「字节级、且阈值非 0」这个组合钉死。⇒ 守卫保留为**用例②+③**，反证条目作废。

### 6.3 真机场景（release 构建）

1. **长思考首字节**：接一个 reasoning 网关，把首字节超时改小到 10 s（设置页），发一条会长时间思考的消息 ⇒ 应看到「正在重试（1/2）」，随后正常出结果；**日志里应有两条 `ChatSse` 请求**。
2. **流中卡住**：把流中空闲超时改小到 10 s，用 MockWebServer / 本地网关发一半后停住 ⇒ 应**保留半截正文** + 报错（**不得**重试、**不得**清空已显示的文字）。
3. **点停止**：在重试退避的 1 s 窗口内点停止 ⇒ 立即停止，日志里**不得**出现第二次请求。
4. **工具轮**：让 Agent 连续跑 3 轮工具调用，每轮都在 10 s 阈值下正常完成 ⇒ 重试预算**不得**跨轮累积（第 3 轮仍应有完整的 2 次预算）。
5. **备份往返**：改过超时值的预设 → 导出 → 恢复 ⇒ 三个字段原样回来。

---

## 7. 风险与未做项

| 项 | 说明 |
|---|---|
| ⚠️ **重试 = 重复计费** | 每次重试都是完整上下文重发。已用「仅零内容时重试」把触发面收窄，但仍存在；`_events` 的提示文案必须让用户知道发生了什么（§4.6） |
| ⚠️ **黑洞网络下的重试代价** | v1.2 起 `NO_RESPONSE_TIMEOUT` 也重试 ⇒ 真·黑洞网络（丢包而非拒连）会多花最多 2 次重试、每次最长 `connectTimeout` 30 s 才报错。这是换取「缓冲型网关不 flush 响应头时本需求能救回来」的必要代价（§4.2）；DNS 失败 / `connection refused` 不受影响 |
| ⚠️ **不做总时长上限的洞** | 理论上「每 299 s 发 1 个字节」可以无限拖住连接。三家头部（Codex / dsh / `ccb`）同样不设，OpenCode 设了 300 s。**本轮不设**，理由：多轮工具调用 + 长回答会误杀；且有「用户点停止」兜底。若日后要补，按 OpenCode 的 `timeout` 语义做成**可关闭**的独立字段 |
| ⚠️ **只发 keep-alive、永不发内容** | 字节级判据会让这种连接**永不超时**（与 dsh 的 `pulse` 行为一致）。用户看到「一直转圈」。本轮的缓解只有「用户点停止」；**主动的 no-progress 看门狗**（忽略 keep-alive、只看事件）属独立课题——`ccb` 也只把它做成**被动记日志**（30 s stall），没有一家主动杀 |
| **首字节 / 流中两个**独立**阈值（真两阶段）** | v1.1 撤回（§3.2）。要真独立必须改 Okio 层 body source 的 timeout（保持字节级 keep-alive 语义）或加应用层看门狗（会丢该语义），且需真机验证 —— 属独立课题。本轮用「单值 + 分类 + 重试」覆盖同一诉求 |
| **不做非流式降级** | `ccb` 的兜底手段。我们不做：`generateReply` 路径仍在（`ChatBenchmarkRunner` 在用），但把它接进用户路径需要重做「非流式结果如何复用同一占位消息 id」的收尾逻辑（`finalizeStreamingMessage` 的既有契约），属独立课题 |
| **不做倒计时 / 阶段进度 UI** | 需动 `ChatMessage` 模型（上游文件），收益低于本课题 |
| **`connectTimeout` 未动** | 30 s 保留。头部无可比数值（Codex 的 15 s 是 WS 握手） |
| **Lua / 其他 HTTP 路径** | 本设计只覆盖 Chat 流式链路；`AIModule`（60 s）、`AgentModule`（120 s）、WebDAV 等的超时**不在本轮范围**，且它们的失败形态不同（无用户在场的长时间等待） |

---

## 8. 决策台账

| # | 决策 | 依据 | 备选与否决理由 |
|---|---|---|---|
| D1 | 做「空闲超时」而非「总时长」 | 五家一致 | 总时长会误杀长回答（§7） |
| D2 | **单一阈值** `streamIdleTimeoutSeconds` = 300 s，同时覆盖首字节与流中两个阶段 | Codex / dsh / OpenCode 三家一致 300 s；三家头部均为单值 | 首字节设短会**专杀**长思考场景（§3.2） |
| D3 | 首字节 / 流中**只做分类，不做两个配置项** | OkHttp 一个 client 只有一个 `readTimeout`；min / max 都会**静默忽略一个方向** | 「保留两个独立旋钮」是 v1.0 的过度设计，**v1.1 撤回**（§3.2）；真两阶段列 §7 |
| D4 | 判据 = **字节级**（不自己写看门狗） | OkHttp `readTimeout` 天然重置；dsh `pulse` 测试 | 事件级判据会误杀「只发 keep-alive」的连接 |
| D5 | 可重试 ⟺ **未发出过 TextDelta / ReasoningDelta / Completed** | 代码核对（§4.3）：其余事件一律不进 UI/VM 状态 | 更宽的判据需要回滚 UI，得不偿失 |
| D6 | 重试 **2** 次（头部为 5） | 成本结构不同（§3.3） | **有意偏离**，常量独立可改 |
| D7 | 退避 1 s → ×2 → 10 s，±10% | dsh 500 ms–10 s / 10%；Codex 200 ms base；OpenCode `2^n × [0.8,1.2]` | — |
| D8 | 恢复层用 **`openStream` 工厂** | `ChatStreamAssembler` 在 `stream()` 时创建、有状态（§4.5） | 复用 Flow 实例 ⇒ 静默状态污染 |
| D9 | 用户可见性走既有 `_events` 通道 | 零模型改动 | 倒计时 UI 需动上游模型 |
| D10 | 只 catch `ChatStreamTimeoutException` | 取消必须穿透（§4.4） | 宽 catch ⇒ 点停止后偷偷重试 |
| D11 | 两个 client 共用 `ChatStreamTimeouts` | 消除「两处各硬编码 120 s」的漂移源 | 分开写迟早不一致 |
| D12 | 不做非流式降级 / 不做总时长上限 / 不做 no-progress 看门狗 | 三家头部同样不做 | 见 §7 |
| D13 | `response == null` 的 **`NO_RESPONSE_TIMEOUT` 可重试**（原 `CONNECT_TIMEOUT` 改名） | v1.1 与 §4.2 第 2 点**自相矛盾**；okhttp-sse 字节码核实 `response == null` ⇔ 压根没拿到响应；Codex `retry_transport: true` | 不重试 ⇒ 缓冲型网关下**本需求要救的场景救不回来** |

---

## 9. 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1 | 2026-10-10 | 初稿。基线 `44de0785`。数值全部取自五家头部 Agent 的源码/文档实测值（§2.1）；六条共同决策见 §2.2；有意偏离项（重试次数 2）见 §3.3 与 D6。顺带记录 `ChatSseStreamTest.kt:36` 的死字段与不符注释（§1.4） |
| v1.1 | 2026-10-10 | **由方案阶段（codex）的提问触发的三处修正**：① §3.2 **撤回「两个独立配置项」** —— OkHttp 只有一个 `readTimeout`，min / max 都会静默忽略一个方向，改为「单值 + 分类」（D2/D3 同步改）；② §5.2 修正**六处行号误标** —— 它们是供应商配置卡（`ProviderDraft`）的位置，不是预设编辑弹窗；UI 落点明确为 `ModelEditorDialog`，字段收敛为**两个**；③ §3.3 重试夹取范围由「100 的宽度」改为 **0..5**（Codex 的 `DEFAULT_STREAM_MAX_RETRIES`）。另记：`label_network` 键已存在（复用会构建失败） |
| v1.2 | 2026-10-10 | **方案阶段第二次提问触发的修正（第四处文档错）**：`response == null` 由「`CONNECT_TIMEOUT`、不重试」改为「**`NO_RESPONSE_TIMEOUT`、可重试**」（§4.2 / §5.2 / D13）。理由：它与 §4.2 第 2 点自相矛盾，且会让缓冲型网关下的长思考**救不回来**。同时修正 §6.1 用例①与用例④的构造（`NO_RESPONSE` 落不到 `FIRST_BYTE_TIMEOUT`，已字节码核实），并在 §7 如实记录黑洞网络下的重试代价 |
| v1.3 | 2026-10-10 | **验收段独立复核触发的两处修正（第五、六处文档错）**：① §5.1 的构造函数签名仍写着三个参数 `ChatStreamTimeouts(firstByteMs, idleMs, connectMs)`，与 §3.2 的「单值」结论**自相矛盾**（§4.7 已改、§5.1 漏改），收敛为两个；② §6.2 的「把 keep-alive 判据改成事件级」这条反证**在本实现下无落点**（字节级语义来自 OkHttp 的 socket 级 `readTimeout`，无应用层判据可改）⇒ 作废，改为由「用例②+③ 的组合」守卫该语义。⚠️ 另记：验收段发现 MindFS 把**另一个任务（TriggerLabel）的交付说明**串进了本任务的 `{previous_input}`，导致交付说明整段错位——**这是编排层的问题，不是实现缺陷** |
