# Chat 流式输出设计

> 状态：**设计稿 v3，未实现**。v1 写于 2026-09-23，基线 `5f6fa0da`；
> v2 为**双人审查后修订版**（事实核查 + 对抗式审查），v3 为**第三轮评审修订**
> （跨协议错误路径 / 权威值定义 / 归一化签名），修订理由见 §11 与 §12。
> v3 新增的四项（B2–B5）**均不依赖真机验证即可定案**。
> 所有行号锚定 `5f6fa0da`；上游若改动 `ui/chat/` 需重新核对。
> 本文是 `docs/fork/chat-agent-enhancement-plan.md` 与 `chat-agent-rearchitecture.md` 的下位文档
> （那两份讲 AI 能力与工具暴露，本文讲**传输与渲染**）。

## 0. 一句话方案

**把 `ChatProviderAdapter.complete()` 从「一次性返回」扩展出一条 `Flow<ChatStreamEvent>` 的流式旁路，
消息改为「稳定 id + 原位增长」，流式期间不落盘、文本经流式安全的规范化后再上行，
滚动改为「接近底部才跟随」；Anthropic 与 OpenAI chat/completions 两条路径先落地，
Responses 路径第一版保持非流式。**

### 0.1 这个需求是从一个真实 bug 来的

起点不是「想要打字机效果」，而是**审批卡片被吞**（本仓 2026-09-22 排查）：

- `ChatScreen.kt:373` 的自动滚动以 `messages.size` 为 key；
- AI 回复走 `replacePendingMessage`（`ChatViewModel.kt:1063-1088`）**替换同一槽位**，条数不变；
- ⇒ key 不变 ⇒ 不重新发射 ⇒ 不滚动，而新气泡远高于被替换的「正在生成…」占位，
  于是顺着屏幕底下长出去，只剩顶部一条缝。被吞掉的恰是「批准 / 拒绝」按钮所在的下半部分。

⚠️ **这个 bug 与流式不是同一个问题**，两者的修法甚至冲突（见 §4.6 的「P6/P4 顺序」）。
v1 把它们混为一谈，v2 拆开了。

---

## 1. 范围与非目标

### 1.1 第一版做

| # | 内容 |
|---|---|
| S1 | 流式事件模型（新的纯函数层，可纯 JVM 单测） |
| S2 | SSE 读取层（新增 `okhttp-sse`，**不手写 `split("\n\n")`**） |
| S3 | `deepseek` / `openrouter` / `ollama` / `openai`(chat/completions) 的流式 |
| S4 | `anthropic`(messages) 的流式 |
| S5 | 消息原位增长（稳定 id，patch `content` / `reasoningContent`） |
| S6 | 流式文本的**流式安全规范化**（§4.9，v2 新增） |
| S7 | 流式期间不落盘；轮次结束落一次 |
| S8 | 滚动重做（修 0.1 + 「接近底部才跟随」） |
| S9 | 取消语义（保留已生成文本；中断在途连接） |
| S10 | 浮窗的流式表现（摘要节流在 Service 层） |

### 1.2 第一版不做

| 不做 | 理由 |
|---|---|
| **OpenAI Responses API 的流式** | ✅ **v5 起已纳入**（原写「事件名未经权威源验证」，该判断**是错的**——见 §5.3 的更正）。两套协议都走真正的流式 |
| 逐 token 的工具参数渲染 | 三家一致反对（CCB `unparsedToolInput` / dsh `argsRaw` / OpenCode 累积但**不解析**）。流式期间只累积字符串，不解析 JSON |
| 改用 `StreamingMarkdownState` 增量解析 | 库**确实提供**（§3.4），但它是 append-only，与「末尾规范化改写」语义冲突；且 `ChatMarkdown.kt` 的三处 fork 定制需在增量路径上重验。**作为独立优化另立项** |
| 重试 / 断线续传 | 现状无重试（`ChatCompletionClient.kt` 内 `retry` 零命中）。流式会把「断了怎么办」变成新问题，但那是独立课题 |
| `ChatBenchmarkRunner` 走流式 | 第二消费方（`ChatBenchmarkRunner.kt:352`），基准测试不需要中间态，继续用 `complete()` |

### 1.3 硬约束（不可违反）

- **`vflowCoreVersion` 不在此次改动范围**（`core/build.gradle.kts`，注释明写「🚫 不要在开发过程中改它」）。
- **`ChatBenchmarkRunner` 的调用签名不能破**（§5.1 的设计天然满足）。
- **`ChatMessage` 的序列化兼容**：新增字段必须带默认值（会话是持久化的 JSON）。

---

## 2. 现状约束（调研结论，带行号）

### 2.1 网络层：唯一接缝，但零 SSE 基础设施

`ChatCompletionClient.kt`（1001 行）的收敛点：

```kotlin
// :191-193
private interface ChatProviderAdapter {
    suspend fun complete(request: ChatProviderRequest): ChatCompletionResult
}
```

`generateReply`（`:140-167`）按 provider 选 adapter，`:161` 是唯一调用点。

**没有任何流式基础设施**：全仓 `EventSource` / `okhttp-sse` / `ResponseBody.source()` / `BufferedSource` 零命中；
唯一的 HTTP 出口 `executeJsonRequest`（`:628-663`）是**整块同步读取**：

```kotlin
// :646
val rawBody = response.body?.string().orEmpty().trim()
```

⚠️ **`ChatCompletionClient` 没有任何 public 流式入口**（v1 的架构图缺了这个箭头，§5.1 已补）。

`stream` 唯一的出现是 `:246` 的 `put("stream", false)`；**Responses 路径（`:265-286`）与
Anthropic 路径（`:535-574`）连 `stream` 字段都没有**。

OkHttp 配置（`:176-182`）：`connectTimeout 30s` / `readTimeout 120s` / `writeTimeout 120s`，
**无 `callTimeout`**、无拦截器、无自定义连接池。

> 对 SSE 的含义：`readTimeout` 是**相邻两次读之间的空闲超时**，长输出下不会误触发；
> 但没有 `callTimeout` 意味着**永不整体超时**——流式挂死时只能靠用户点停止。

### 2.2 消息模型：全量替换，无增量通道

`ChatMessage`（`ChatModels.kt:186-198`）是**不可变 data class**，`id` 默认随机 UUID（`:188`）。
现有三种更新形状**全都是整条替换**：

1. `replacePendingMessage`（`ChatViewModel.kt:1063-1088`）——占位换成真消息（**新 id**）
2. `List<ChatConversation>.updateMessage(id, transform)`（`:1192-1209`）——最接近补丁的东西，
   但**仍构造新实例**，且 `:1205` 无条件刷新 `updatedAtMillis` 并重排会话；只用于 `toolApprovalState`
3. 追加（`:648`、`:675`、`:826`、`:857`）

`isPending` 只在 `:852` 置 true，**从无任何地方改回 false**——它靠整条消息被替换而消失。

> ⚠️ `ChatScreen.kt:464` 的 `LazyColumn` 用 `key = { it.id }`。
> **流式若继续用「替换」模型（换 id），会导致每帧销毁重建条目**——所以 S5 的「稳定 id」不是优化，是前提。

### 2.3 持久化：全量序列化，流式下必须绕开

```kotlin
// ChatViewModel.kt:1136-1139
private fun updateUiStateAndPersist(transform: (ChatUiState) -> ChatUiState) {
    _uiState.update(transform)
    persistSessionState()      // ← 每次全量
}
```

`persistSessionState`（`:1141-1154`）把**整个 `ChatSessionState`**（全部会话 + 全部消息 + 完整工具输出）
序列化成一个大 JSON，写一次 SharedPreferences（`ChatPresetRepository.kt:77-84`，`edit{}` 即 `apply()`）。

当前频率是每轮 2-4 次，可接受。**流式若每个 delta 都走这条路径，就是每秒十几次全量序列化**，
成本随历史长度线性增长（长会话 + 大工具输出时单次 encode 可达数百 KB）。

**好消息**：`_uiState` 是 `MutableStateFlow`（`:67`），`_uiState.update{}` **本身可直接用**
（`:1137`），且已有直调先例（`:187`、`:1108`）。`updateUiStateAndPersist` 有 13 个调用点。

> ⚠️ **本文 v1 漏算的一项内存成本**：每个 delta 走 `_uiState.update{}` 意味着对
> `conversations` 全表 + 每条会话的 `messages` 全表做**列表拷贝**（O(总消息数)），
> 这与「不落盘」是两回事。§4.5 的节流点正是为此而设。

`prefsListener`（`:87-94`）只监听 `chat_presets_json` / `chat_provider_configs_json` /
`chat_default_preset_id`，**不监听会话状态**，所以高频内存更新不会引发自激循环。

### 2.4 滚动：两处写法不一致，且都依赖 Compose 内部钳制

```kotlin
// :373-378  自动滚动：lastIndex + 1 越界
LaunchedEffect(activeConversation?.messages?.size) {
    val lastIndex = activeConversation?.messages?.lastIndex ?: -1
    if (lastIndex >= 0) listState.animateScrollToItem(lastIndex + 1)
}

// :640  跳到底部按钮：合法索引
scope.launch { listState.animateScrollToItem(lastIndex) }
```

`lastIndex + 1 == messages.size`，是**不存在的索引**。

⚠️ **实际解析到的是 Compose foundation `1.11.0-beta02`，不是 `libs.versions.toml:16` 写的 `1.10.6`。**
`./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.compose.foundation:foundation` 实测：

```
androidx.compose.foundation:foundation:1.11.0-beta02
  By constraint: foundation-layout is in atomic group androidx.compose.foundation
  By conflict resolution: between versions 1.11.0-beta02, 1.10.6, 1.10.3, 1.10.0, 1.7.1 and 1.7.0
```

（BOM `2025.12.01` 声明 1.10.0 + atomic group 约束 + conflict resolution 抬升。）

在 **1.11.0-beta02** 的 sources 里核对：`LazyListMeasure.kt:134-139` 把
`currentFirstItemIndex >= itemsCount` 钳到 `itemsCount - 1`（1.10.6 中该分支在 `:131-136`），
再经「内容不足视口则回填」分支，净效果是**滚到内容末端贴齐**——即
「依赖内部钳制兜底的、语义上说不通的滚到底」。

**跳到底部**按钮的 `canScrollForward` 判据在 `:285`（`showJumpToBottom`）。
⚠️ 该判据的 `remember` key 里也有 `messages?.size`（`:283`）——与自动滚动同属「key 取错维度」一族。

> ⚠️ **v3 补：本节代码块是 `5f6fa0da` 的原始形态。工作区已有一处未提交改动**
> （`git status` 显示 `M ChatScreen.kt`，14 行注释 + 1 行改动），已把 key 改为
> `LaunchedEffect(activeConversation?.id, activeConversation?.messages?.lastOrNull()?.id)`
> ——**即 §4.6(1) 已完成，只是尚未提交**。实现时以 HEAD 为准，勿重复劳动。
>
> 该改动的注释还记录了一次**失败实验**（目标 0.1「审批卡片被遮挡」）：
> 「曾试图『修正』这个越界写法并重写落点，改出两个新 bug 且真机验证无效（已回退）」。
> ⇒ **`lastIndex + 1` 的越界写法是刻意保留的**：对矮于一屏的卡片，钳制后的净效果是
> 「卡片底部贴住视口底部」，审批按钮因而可见；改掉它会破坏这一点。
> ⚠️ 该实验与 §4.6(2)（流式长回复的跟随滚动）**不是同一场景**，不可外推 —— 详见 §12 的「v3 撤回项」。

> 📌 **教训**：写死库版本号前先跑 `dependencyInsight`。本文 v1 就是在这里错了。

### 2.5 取消：取消不中断在途连接

`stopAgent`（`ChatViewModel.kt:303-367`）：`currentAgentJob?.cancel()`（`:308`），
**紧接着 `currentAgentConversationId = null`（`:310`）**，然后用一次 `updateUiStateAndPersist`
把 `isPending` 消息换成 `ERROR "已停止。"`（`:326-334`）、PENDING/RUNNING 的审批标 `REJECTED`。

⚠️ **`ui/chat/` 链路无 `invokeOnCancellation` / `Call.cancel()`**（其它模块有，可参照：
`AgentModule.kt:651`、`AutoGLMModule.kt:656`）。HTTP 是阻塞式 `execute()`（`ChatCompletionClient.kt:645`），
**协程取消不会中断 socket 读取**——会一直挂到响应返回或 120s 超时。

⚠️ **`stopAgent` 的 transform 只处理 `state.activeConversationId`（`:315`、`:322`）**，
而 `:310` 已经把 `currentAgentConversationId` 清成 null。这个组合在流式下会造成内容永久搁浅，见 §4.7。

### 2.6 文本规范化（v1 完全漏掉的一层，见 §4.9）

`normalizeAssistantReply`（`:970-995`）在**三条路径上都调用**（`:426` / `:497` / `:612`），
它做两件语义转换：

```kotlin
// :975
val thinkRegex = Regex("(?is)<(?:think|thinking)>(.*?)</(?:think|thinking)>")
// → 把  thinking... 抽出当作 reasoningContent，并从正文移除
// → 再走 stripInlineToolMarkup（:28-37）：6 个正则剥掉 <tool_call> / <function_calls> / <tool_calls> 标记、折叠空行
```

**这不是可有可无的清理**：本仓 Ollama 默认模型是 `qwen3:8b`（`ChatModels.kt:42`），会吐 ` thinking`；
OpenRouter 走 R1 类模型同理。流式下若只拼接 delta 而不做规范化，用户会**逐字看到标签原文**，
且 finalize 后**原样落盘**。§4.9 是为此新增的设计节。

### 2.7 其它消费方

- `ChatFloatSummary.derive`（`ChatFloatSummary.kt:49`）→ `lastAssistantText`（`:93`）→
  `singleLine()`（`:100`）跑 `Regex("""\s+""")`。
  **`ChatFloatWindowService.observeViewModel`（`:378-383`）对每次 `uiState` 发射都调 `updatePanelState()`**，
  后者调 `derive`（`:353`）⇒ 每个 delta 一次 O(n) 正则、整轮 O(n²)，且在 service 的 Main 上。
- `chat-float-window-design.md:70` 明确写了「❌ 不引入流式输出」——**本设计推翻这条决策，该文档需修订。**
- `ChatBenchmarkRunner.kt:352`：第二消费方，保持非流式（§1.2）。

---

## 3. 外部参考（四家源码对照）

四份源码锚定 commit 已与 `D:/develop/references/README.md` 逐一核对一致。
**四家均为 CLI/TUI/Web，无一是 Compose**，故「节流/重组」是类比映射而非可直接照抄的代码。

### 3.1 共识（可视为行业做法）

| # | 共识 | 证据 |
|---|---|---|
| C1 | **流式期间不解析工具参数 JSON，只累积原始字符串** | CCB `unparsedToolInput`（`messages.ts:3276`）；dsh `argsRaw`（`partial.ts:65-76`）；OpenCode 累积 `input` 原始串但 `parseToolInput` 仅在 finish 时调一次（`tool-stream.ts:156`）。**唯一例外是 Pi**，每 delta 重解析整个累积串（`anthropic-messages.ts:692`）——CCB 明确注释避开了这个 O(n²) |
| C2 | reasoning 是独立块类型，绝不混进正文 | 四家一致 |
| C3 | **reasoning 默认折叠，折叠态固定一行**，避免流式布局跳动 | OpenCode 注释原文：`"Collapsed by default in hide mode: a single line throughout, so the layout never shifts."`（`index.tsx:1589`）；dsh 折叠态只渲染 `latestLine` |
| C4 | **取消时保留已生成的文本** | CCB `REPL.tsx:2600-2606`（显式保证顺序 `[user, partial-assistant, [interrupted]]`）、dsh `interruptedBlocks()`、OpenCode `cleanup()`、Pi `stopReason === "aborted"` |
| C5 | **usage 在流式中可能缺失/迟到，必须容忍 `undefined`** | dsh getter 返回 `undefined`（`assistant-stream.ts:127`）；OpenCode 各协议层 `mapUsage`（**5 处**）在 **usage 缺席时返回 `undefined`**，调用方以空值兜底；Pi 有 `supportsUsageInStreaming` 兼容开关（`types.ts:576`） |
| C6 | **判断「是否在底部」要处理布局异步性** | dsh `FOLLOW_THRESHOLD = 24`（`ChatView.tsx:19`）+ 滚动台账；CCB 的 `getPendingDelta()` 注释（`scrollBy` 累积但 `scrollTop` 不立即反映）；OpenCode 的 `setTimeout(..., 50)`（`index.tsx:427`） |

### 3.2 分歧（需按场景取舍，本文的选择见括号）

| 维度 | 四家做法 | 本文选择 |
|---|---|---|
| UI 节流 | 只有 dsh 认真做了（**三级优先级** `none`/`animation-frame`/`immediate`，`contract/conversation.ts:179`；帧数见 `assembler.ts:56`）；其余三家无节流 | **第一版做粗粒度节流**（§4.5，v2 修正：v1 说不做，是错的） |
| 中断时的工具调用 | dsh **整块丢弃**（理由：`"interruption precedes dispatch; retaining one would require a fabricated result"`）；OpenCode 保留但标 `error` + `metadata.interrupted` | **未 closure 或 args 不可解析的丢弃，其余保留**（§4.7，v2 加了 parse 判据） |
| 状态模型 | CCB 可变 `contentBlocks[index]`；dsh `Map<index, PartialBlock>`（`assembler.ts:16,39`）+ 事件流压缩双层；Pi block 内联 index + `findIndex` | **事件流 + 归约**（Flow），归约器放纯函数层（§4.1） |

### 3.3 高价值的坑（直接约束设计）

1. **`message_delta` 的 usage 是迟到的**，且**在 `content_block_stop` 之后才来**。
   CCB 注释（`claude.ts:2335-2340`）：
   > `Messages are created at content_block_stop from partialMessage, which was set at message_start before any tokens were generated (output_tokens: 0, ...). message_delta arrives after content_block_stop with the real values.`

2. **delta 不带 message id**，只有 `message_start` 带。必须自己按作用域追踪当前流式消息
   （CCB `ccrClient.ts:103-114`，注释在 `:109`）。

3. **`content_block_start` 可能已经带内容**。Pi 有专门测试保留它（`anthropic-sse-parsing.test.ts:330`）；
   CCB 反而清空（`claude.ts:2114-2125`），理由是
   `"the sdk sometimes returns text as part of a content_block_start message, then returns the same text again in a content_block_delta"`。
   **两家面对同一现象结论相反** → 必须实测本条链路的真实行为（§5.3-U3）。

4. **DeepSeek 流的第一个 chunk 是 `content: null, reasoning_content: ""`**，
   必须忽略，否则凭空开一个空 reasoning 块（dsh `translate.ts:2-3` + 实测数据 `tests/translate.spec.ts:20`）。

5. **工具的 `id`/`name` 是「身份」不是「累积」**。dsh `acceptIdentity`（`translate.ts:74-87`）：
   > `id and name are identity, not accumulation: the wire sends each once, on the call's first delta. A continuation delta that re-sends the field empty — or null, which some OpenAI-compatible gateways fill in — means "no update", never "clear".`

6. **`stream_options: {include_usage: true}` 必须显式请求**，且**要按 provider 做开关**——
   有些 provider 不认这个字段会报错（Pi `openai-completions.ts:818-821` + `types.ts:576` 的
   `supportsUsageInStreaming`）。⚠️ 开了之后**最后一帧是 `choices: []` 的纯 usage 帧**（§4.3.1）。

7. **Anthropic 的 `partial_json` 可能是畸形 JSON**（非法转义 `\H`、裸制表符），
   provider 真的会发（Pi 测试 `anthropic-sse-parsing.test.ts:243`，畸形串在 `:259`）。
   本文不解析它（C1），但 §4.7 的取消判据要用到 parse 能力。

8. **不要手写 SSE 分帧**。dsh 的注释直白（`llm-deepseek/src/sse.ts:24`）：
   > `raw SSE bytes; reads may split anywhere, including mid-UTF-8 sequence.`
   dsh 把分帧外包给 `eventsource-parser`（`llm-deepseek/package.json:47`）。Android 侧对应 OkHttp 的 `EventSources`。

### 3.4 mikepenz 0.45.0 的既有能力

**（a）`rememberMarkdownState` 已内建 conflate**（`MarkdownState.kt:73-84`）：

```kotlin
// Use snapshotFlow with conflate to prevent parse thrashing during rapid updates.
// Without conflate, LaunchedEffect(input) cancels and restarts on every change,
// causing nothing to render if updates arrive faster than parsing completes.
// With conflate, parsing always completes and then picks up the latest value.
LaunchedEffect(Unit) {
    snapshotFlow { currentInput }.conflate().collect { newInput ->
        state.updateInput(newInput); state.parse()
    }
}
```

`ChatMarkdown.kt:98` 用的正是收 `String` 的那个重载。

⚠️ **v1 在此处写错了一句，v2 更正**：v1 说解析「在 `LaunchedEffect`（**非 UI 线程**）里异步做」。
`LaunchedEffect` 跑在 composition 的 `applyCoroutineContext`，在 Android 上就是 **main（AndroidUiDispatcher，帧对齐）**。
所以 markdown 解析是**主线程上做的**；`conflate` 只保证「每帧至多解析一次、收敛到最新值」，
**不能把解析成本移出主线程**。这正是 §4.5 需要节流层的原因。

**（b）库另有 `StreamingMarkdownState`（append-only 增量解析）**：

```kotlin
// StreamingMarkdownState.kt:117-134
override suspend fun append(chunk: String) = appendMutex.withLock {
    streamingFile.append(chunk)
    val nextSnapshot = Snapshot(
        stableAst = streamingFile.stableChildren.toList(),   // ← 稳定前缀不再重解析
        unstableAstTail = streamingFile.unstableTail.toList(),
    )
```

用 `org.intellij.markdown` 的 `StreamingMarkdownFile`，把 AST 分成 `stableAst` + `unstableAstTail`——
正是 CCB「只重解析最后一个 block」的思路。另有 `Flow<String>.collectAsStreamingMarkdownState`（`:38`）。

⚠️ **但它与本文的规范化需求冲突**：`append` 是 append-only，而 `normalizeAssistantReply`
会让正文**变短**（抽走 thinking）或**改写末尾**（剥标签、折叠空行）。
⇒ 第一版不采用（§1.2），待 §4.9 的规范化解决后再评估。

**（c）两处观感风险（v1 未提）**：
- `Markdown` 的 `loading` 默认是**空 `Box`**，`retainState` 默认 `false`，`ChatMarkdown.kt` 未传参
  ⇒ 每次输入变更会**瞬时回落到空态**，流式下可能引起闪烁。需纳入验收（§8）。
- `conflate` 意味着**屏幕文字滞后于累积内容**，叠加 §4.9 的 finalize 规范化，
  最终呈现是「先滞后、末尾再跳变一次」。这条必须在验收项里显式接受或显式设计掉。

---

## 4. 目标架构

```
                    ┌──────────────────────────────────────┐
                    │  ChatStreamEvent（新，纯 Kotlin）       │
                    └──────────────┬───────────────────────┘
                                   │ Flow<ChatStreamEvent>
   ┌───────────────────────────────┴───────────────────────────────┐
   │  ChatProviderAdapter（:191-193）扩展                          │
   │    suspend fun complete(...)               ← 保留（Benchmark）  │
   │    fun stream(...): Flow<ChatStreamEvent>  ← 新增，默认退化     │
   └───────┬───────────────────────────────────┬───────────────────┘
           │                                   │
   ┌───────┴────────┐                  ┌───────┴────────┐
   │ OpenAICompat   │                  │ Anthropic      │
   │ (chat/compl.)  │                  │ (messages)     │
   │ ⚠️ Responses 走 │                  └────────────────┘
   │    complete()  │
   └────────────────┘
           │
   ┌───────┴────────────────────────────────────────────────────────┐
   │  新增纯函数层（可纯 JVM 单测）                                    │
   │  ChatSseFraming.kt       字节流 → (event, data) 行             │
   │  ChatStreamAssembler.kt  事件 → ChatStreamEvent（增量归约）      │
   │  ChatStreamNormalizer.kt 流式安全的文本规范化（§4.9，v2 新增）    │
   │  ChatMessagePatch.kt     patch/finalize 的纯函数变换（§4.4）    │
   └────────────────────────────────────────────────────────────────┘
           │
   ┌───────┴────────────────────────────────────────────────────────┐
   │  ChatViewModel：稳定 id 原位增长 + 节流 + 不落盘 + finalize      │
   └────────────────────────────────────────────────────────────────┘
           │
   ┌───────┴────────────────────────────────────────────────────────┐
   │  ChatScreen：接近底部才跟随（§4.6，含内容指纹定义）               │
   └────────────────────────────────────────────────────────────────┘
```

### 4.1 事件模型（新文件，纯函数，可纯 JVM 单测）

```kotlin
// ChatStreamEvent.kt
sealed interface ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    /**
     * 工具调用的增量分片。
     * ⚠️ index 是「本轮流内的块序号」，不是 toolCalls 列表下标——
     * 两者在「模型先发 text 再发 tool_call」时不一致，必须按 index 归约后再落到列表。
     * ⚠️ id/name 是身份不是累积（§3.3-5），null 表示「本次无更新」。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : ChatStreamEvent

    data class Usage(
        val totalTokens: Int?,
        val cacheCreationTokens: Int?,
        val cacheReadTokens: Int?,
        val cacheDeletedTokens: Int?,
    ) : ChatStreamEvent

    /** 流正常结束。携带归约后的最终结果。 */
    data class Completed(val result: ChatCompletionResult) : ChatStreamEvent
}
```

归约器 `ChatStreamAssembler` 只做拼接，不做 JSON 解析（C1）。
⚠️ **`Completed` 只让「类型」可复用，不让「收尾逻辑」可复用**（v1 的措辞误导）：
`replacePendingMessage` 是整条替换，与 §4.4 的原位增长结构性冲突，收尾必须重写（§4.4 给了完整清单）。

### 4.2 SSE 读取层

**引入 `okhttp-sse:4.12.0`**（与现有 `okhttp:4.12.0` 同版本；已实测 Maven Central 200）。

**不手写 `split("\n\n")`**（§3.3-8）。`EventSources.createFactory(client).newEventSource(request, listener)`
负责分帧、UTF-8 跨 chunk、CRLF/BOM、多 `data:` 行合并。

写一个 `suspend fun streamReply(...)` **作为 `ChatCompletionClient` 的公共流式入口**（v1 缺这个）：

```kotlin
suspend fun streamReply(
    preset: ChatPresetConfig,
    history: List<ChatMessage>,
    skillSelection: ChatAgentSkillSelection = ChatAgentSkillSelection.EMPTY,
): Flow<ChatStreamEvent>
```

回调式 listener → `Flow` 的桥接用 `callbackFlow`：

```kotlin
// ⚠️ 取消用 eventSource.cancel()，不是 Call.cancel()——
// EventSources 这条路拿不到 Call 对象（v1 的 F8 措辞与所选架构对不上，已更正）
//
// ⚠️⚠️ v3（B2）：**三个终止回调都必须 close()**。`EventSourceListener` 有两个终止入口，
// 只处理 `onClosed` 会让失败路径永久挂起 —— 详见下方 F18。
private fun EventSource.awaitAsFlow(): Flow<...> = callbackFlow {
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySend(data)                     // 累积/归约在归约器里做
        }

        override fun onClosed(eventSource: EventSource) {
            close()                           // ← 正常收尾
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            // t == null 且 response != null ⇒ **HTTP 层失败**（非 2xx，或 content-type 不是
            // `text/event-stream`）。此时**响应体是 JSON 错误体、不是 SSE**，必须自己读出来
            // 复用 `extractServiceError`（:655），否则用户只剩一个 `HTTP 400`（原有能力丢失）。
            close(t ?: IllegalStateException(extractServiceErrorBody(response)))
        }
    }
    awaitClose { cancel() }   // ← 必须：否则连接泄漏
}
```

> `extractServiceErrorBody(...)` 是**新增的小工具**（读 `response.body` 后复用既有
> `extractServiceError`，读不到时回退 `HTTP <code>`），**读完必须关闭响应体**。
> ⚠️ 有一处待 P2 实测确认：`t == null` 这条路径下 okhttp-sse 是否**已经**关掉了
> `response.body`。若已关闭则读不到错误体，此分支只能回退 `HTTP <code>`——
> 不影响「必须 `close()`」这个结论，只影响错误文案的丰富度。

> CCB 的教训（`claude.ts:2938-2942`）：`"Must be in the finally block: if the generator is terminated early
> ... the Response object's native TLS/socket buffers leak until the generator itself is GC'd"`。
> 注意这是**类比**（它讲的是生成器提前终止，不是 OkHttp），但「必须显式释放」的结论一致。

⚠️ **结束标记**：OpenAI 系以 `data: [DONE]` 结束，Anthropic 系以 `message_stop` 结束。
两者都要显式识别并 `close()`，不能等 socket 自然关闭。
dsh 的教训：**「未终止的尾部 = 截断，不是可 flush 的载荷」**（`sse.spec.ts:62-67`）——
EOF 前没见到结束标记，应视为异常而非正常收尾。

### 4.3 两条路径的映射

#### 4.3.1 OpenAI chat/completions（`deepseek` / `openrouter` / `ollama` / `openai` 默认）

请求在 `buildChatCompletionPayload`（`:242-263`）基础上改**三处**（v3 补了请求头）：

```kotlin
put("stream", true)
// ⚠️ 按 provider 开关，不是无条件加（§3.3-6，未验证项 U4）
if (supportsUsageInStreaming(provider)) {
    put("stream_options", buildJsonObject { put("include_usage", true) })
}
```

⚠️ **请求头也必须改**（v3 / B3，v1-v2 只说了「请求体改两处」）：
`buildHeaders`（`:231`）与 Anthropic 路径（`:580`）都硬编码

```kotlin
"Accept" to "application/json",
```

SSE 的契约是 `Accept: text/event-stream`，且 okhttp-sse 会**校验响应的 content-type**。
多数服务端不校验 `Accept`（所以现状可用），但严格的会拒绝或返回非流式响应 ——
后者的表现是**静默无输出**（无 `data:` 行可解析），退化成 F18 的挂死形态。
⇒ 流式路径把该头改为 `text/event-stream`。**仅流式路径改**，`complete()` 保持原样。

响应：`choices[0].delta` 下取 `content`（→ `TextDelta`）、`reasoning_content` / `reasoning`（→ `ReasoningDelta`）、
`tool_calls[]`（→ `ToolCallDelta`，**按 `index` 归约**）。

⚠️ **两个必须处理的形状**（v1 漏掉，会导致「全文显示完后抛异常」）：
1. **开了 `include_usage` 后，最后一帧是 `choices: []` 的纯 usage 帧**（§3.3-6）。
   归约器**不得无条件取 `choices[0]`**，要显式识别「空 choices = usage-only 帧」。
2. **DeepSeek 首 chunk 是 `content: null, reasoning_content: ""`**（§3.3-4）——
   空串**不得开块**，否则 UI 凭空多一个空的「思考过程」折叠区。

#### 4.3.2 Anthropic messages

请求体（`:535-574`）加 `put("stream", true)`，其余（`cache_control` 两个断点、块数组 system）不动。

事件映射（`content_block_start` → N× `content_block_delta` → `content_block_stop`）：

| 事件 | 处理 |
|---|---|
| `message_start` | 记录 message id；`usage.input_tokens` / `cache_creation_input_tokens` / `cache_read_input_tokens` 在此（待 U2 实测） |
| `content_block_start` | `type=text` / `thinking` / `tool_use`（`tool_use` 时 `id`+`name` 在此确定）；**若已带内容则保留**（§3.3-3，待 U3 实测） |
| `content_block_delta` | `delta.type` 分派：`text_delta`→`TextDelta`、`thinking_delta`→`ReasoningDelta`、`input_json_delta`→`ToolCallDelta`（字段是 **`partial_json`**） |
| `content_block_stop` | 该块 closure，**冻结原始串**（是否可解析留到 §4.7 判定） |
| `message_delta` | ⚠️ **usage 迟到**（§3.3-1）：`output_tokens` 在此；**在 `content_block_stop` 之后** |
| `message_stop` | 收尾 → `Completed` |
| `ping` | 忽略 |
| `error` | 抛出 |

### 4.4 消息状态模型：稳定 id + 原位增长

**这是对现有模型的唯一结构性改动**，也是 §2.2 那条 warning 的直接解。

现状：`pendingMessage`（新 UUID）→ 被 `replacement`（另一个新 UUID）替换。
改为：**同一个 id 从占位到成品，内容原位增长**。

⚠️ **v2 关键修订：patch/finalize 必须是纯函数，否则 P4 的门禁是空头支票。**
理由（事实核查确认）：`app/src/test` 下**零个**测试引用 `ChatViewModel` / `ChatPresetRepository`；
工程**没有** Robolectric / mockk / Mockito / androidx.test.core（只有 `androidTestImplementation`）；
`ChatViewModel` 是 `AndroidViewModel`，`repository` 无注入缝。现有 8 个 chat 测试**全是纯函数层测试**。

```kotlin
// ChatMessagePatch.kt（新文件，无 Android 依赖，可纯 JVM 单测）
// 照 core/workflow/WorkflowPatch.kt 的做法
internal fun List<ChatConversation>.patchStreamingMessage(
    conversationId: String, messageId: String,
    content: String, reasoning: String?,
): List<ChatConversation>          // 只改 content/reasoning，不刷 updatedAt、不重排

internal fun List<ChatConversation>.finalizeStreamingMessage(
    conversationId: String, messageId: String,
    patch: StreamingFinalizePatch,
): List<ChatConversation>          // 见下方完整清单
```

**finalize 的完整收尾清单**（v1 只写了三个字段，漏了后六项）：

| # | 项 | 漏掉的后果 |
|---|---|---|
| 1 | `isPending = false` | 永久显示「正在生成…」，且**永不落盘**（`:1147` 过滤 pending） |
| 2 | `content`（含**空内容兜底**，**取值见下方 B4**） | 流式下会变成「只有时间戳的空气泡」（现状兜底见 `ChatViewModel.kt:909`） |
| 3 | `reasoningContent` | 思考过程丢失 |
| 4 | `tokenCount` | 页脚不显示 token |
| 5 | `toolCalls` + `toolApprovalState` | 审批卡片不出现 |
| 6 | **`timestampMillis`** | 停在请求发起时刻（`:851`），长回复偏差数分钟；`MessageFooterRow` 显示随之改变 |
| 7 | **`conversation.updatedAtMillis`** | 会话**不会被排到最前**（重排在 `:1080`/`:1082`）——侧边栏里刚答完的会话沉在旧的下面 |
| 8 | **`deriveConversationTitle`** | 标题不更新 |
| 9 | **`isSending = false` + `isAgentRunning`** | 输入框状态错 |
| 10 | **`pendingPermissionRequest = null`** | 残留权限请求 |
| 11 | **`processNextQueuedPromptIfIdle()`** | 排队消息不触发 |

> ⚠️ **`isPending` 语义变更**：现在它「靠整条被替换而消失」，改动后必须**显式置 false**。
> 这是最容易漏的一处——漏了会永远显示「正在生成…」。
> 好消息：`persistSessionState`（`:1147`）与 `sanitizeForRestore`（`:1159`）都
> `filterNot { it.isPending }`，所以**只要 finalize 正确置 false，落盘逻辑无需改动**。

#### ⚠️ B4（v3 补）：`content` 的**权威值**必须是 `Completed.result.content`

v2 只写了「`content`（含空内容兜底）」，**没说取自哪个来源**。流式期间有两个来源且**不保证相等**：

| 来源 | 内容 | 用途 |
|---|---|---|
| 累积的 `TextDelta` | UI 逐字显示的过程值 | **仅过程显示** |
| `Completed.result.content` | 经 `normalizeAssistantReply`（`:970-995`）的最终值 | **权威值** |

**定义：finalize 一律写 `Completed.result.content`，累积 delta 不参与落盘。**

理由：§8 的验收项「流式最终呈现 == 非流式路径的 `normalizeAssistantReply` 结果」与
「工具调用逐字段一致」，**只有在取 `Completed` 时才可能成立**——
累积 delta 是未经（或经流式简化版）规范化的串，落盘它等于让同一轮对话在
「流式 / 非流式」两条路径下产出不同数据。§4.9 也承认二者会有末尾跳变。

代价（显式接受）：finalize 那一瞬间已显示的文本可能整体改写一次。
这与 §3.4(c)「先滞后、末尾再跳变」是同一现象，§8 已有对应验收项。

⚠️ **空内容兜底也挂在 `Completed.result.content` 上**——沿用 `ChatViewModel.kt:909` 的
`result.content.ifBlank { "模型返回了空内容。" }`，**不要在新路径上重造一份兜底逻辑**
（两份兜底文案会漂移）。

### 4.5 节流（v2 新增，v1 说不做是错的）

v1 的依据是「mikepenz 已 conflate」，但 §3.4 更正后可知：**conflate 只解决解析频次，不解决**

- 每 delta 一次 `_uiState.update{}` 的**全表列表拷贝**（O(总消息数)，§2.3）；
- 每 delta 一次 `ChatScreen` 整体重组，其中
  `hasPendingToolApproval = remember(activeConversation?.messages){ ... any{} }`（`:299`）
  会因 `messages` 每次是新对象而**每个 delta 重算**，且带 O(消息数) 扫描；
- 浮窗侧每次发射都 `updatePanelState()` → `derive` → `singleLine()` 正则（§2.7）。

**做法**：在 `_uiState` 写入层做**粗粒度合并**——按 ~50ms 或按「累积字符数增量」合并 delta 后再写一次状态。
这一层是纯函数（可测），且不影响 §4.7 的取消语义（取消时先 flush 再 finalize）。

**浮窗摘要节流放在 `ChatFloatWindowService.updatePanelState`**（不是 `ChatFloatSummary`——
那是纯函数，无法自节流；v1 的 §9 把改动记在 `ChatFloatSummary.kt` 是目标与意图不一致）。

**第一版不上 dsh 那样的三级优先级调度**，但保留测量点（§8 性能项）。

### 4.6 滚动（含内容指纹定义）

**(1) key 换成「最后一条的身份」**——不再用 `size`：

```kotlin
LaunchedEffect(activeConversation?.id, activeConversation?.messages?.lastOrNull()?.id)
```

⚠️ **但这条在流式下对「成长中的消息」永不触发**（稳定 id 不变）。它只负责
「**新消息出现**」这一种情况。§4.6 (3) 负责另一种。

**(2) 落点语义写正确**——不再依赖越界索引。用**库 API `requestScrollToItem`**
（`LazyListState.kt:412` @1.11.0-beta02；**非 suspend**；⚠️ 项目内当前无调用点），
并**等布局落定**：触发时新项可能尚未测量，`animateScrollToItem` 会用旧 `layoutInfo`
估算距离（同类问题见 §3.1 C6 的 `getPendingDelta`）。
做法：先 `snapshotFlow` 等目标项进入 `layoutInfo.visibleItemsInfo`（带超时兜底），再滚。

> ⚠️ **v3 风险注记（不是否证，但实现前必读）**：这个**代码位置**历史上有一次失败实验
> （§2.4 末尾引用的工作区注释）：曾试图修正越界写法并重写落点，**改出两个新 bug、真机无效、已回退**。
> 那次的目标是 **0.1（审批卡片被遮挡）**、场景是**旧 replace 模型 + 矮于一屏的卡片**，
> 与本节的「流式长回复跟随」**不是同一场景**，因此**不构成对本方案的否证**（详见 §12 撤回项）。
> 但有一点必须带进实现：**越界的 `lastIndex + 1` 被钳制后对矮卡片是有用的**
> （净效果「卡片底部贴住视口底部」⇒ 审批按钮可见）。本节的 `requestScrollToItem`
> **没有这个副作用**——它按语义正确落点，因此**对矮卡片的行为会改变**。
> ⇒ 实现时必须**单独回归 0.1 的验收项**（§8「审批按钮无需手动滚动即可点到」），
> 不能假定「落点写对了，0.1 一定不复发」。

> ✅ **v6 实现时的决定：本节的 `requestScrollToItem` 方案「未采用」**，仍用
> `animateScrollToItem(lastIndex + 1)`。理由是上面那条风险注记本身：
> `requestScrollToItem` 按**语义正确**落点，因此**没有**那个「对矮卡片贴住视口底部」的
> 钳制副作用 ⇒ 采用它等于**主动放弃** 0.1 的修复。
> 而那个副作用正是审批按钮可见的原因。
>
> 本节 (2) 描述的痛点是「新项尚未测量时 `animateScrollToItem` 用旧 `layoutInfo` 估算距离」。
> 但**实测未复现该问题**（真机流式跟随正常；且 0.1 的矮卡片场景本就依赖这个估算的净效果）。
> ⇒ 权衡结论：**保留越界写法**，把 (2) 留作「若日后真出现落点偏差再回来处理」。
> 落点的事不在本次范围内（与工作区那句「落点的事另行评估」一致）。

**(3) 「跟随」的内容指纹（v1 缺失的定义，此处补上）**：

```kotlin
/**
 * 内容指纹：决定「是否该跟随滚动」的信号。
 * 只包含「结构性 + 末尾内容」两个维度，刻意不包含「距底部的距离」——
 * 后者变化不得触发滚动（见下方陷阱）。
 */
data class FollowSignal(
    val conversationId: String,
    val lastMessageId: String?,
    val messageCount: Int,
    val lastContentLength: Int,      // 末尾消息的 content + reasoning 总长
)
```

跟随条件是 `FollowSignal` **变化** 且 **当前距底部 ≤ 阈值**。

⚠️ **关键陷阱**（dsh `ChatView.tsx:532-533` 的注释，整份调研里对 Compose 最重要的一条）：
> `Follow new flow content while pinned; do NOT re-pin on every render merely because atBottomRef is true (scroll threshold → setState → snap).`

即：**「是否在底部」这个判定本身不能触发滚动**，否则形成「滚动 → 判定在底部 → 触发重组 → 又滚到底」
的反馈环，把用户的惯性滚动直接吸到底部。实现上：用 `derivedStateOf` 派生「是否在底部」，
**不要用 `LaunchedEffect` 监听它**；只在 `FollowSignal` 变化时跟随。

**阈值与参考系**（v1 只写了个数，v2 写死口径）：

- dsh 的 `24` 是 **CSS px**（Web `clientHeight` 单位），≈40dp。**`24.dp.toPx()` 不是换算，是换了个数。**
- **必须定义「距底部」的参考系**：列表底部有 `contentPadding.bottom = 132.dp + IME`
  （`ChatScreen.kt:272`、`:455`）。用 `viewportEndOffset` 算会把这 132dp 也算成「还在底部附近」；
  用最后一项的 bottom 算则相反。**两种口径给出相反结论，且都不会报错。**
  本文取：**以最后一项的 bottom 与 `viewportEndOffset - contentPadding.bottom` 的差为准**，
  阈值取 **40dp**（由 dsh 的 24 CSS px 折算，真机可调）。

**⚠️ P6 与 P4 的顺序依赖（v1 完全没提）**：

- **若先做 P6（在旧的 replace 模型下）**：`lastOrNull()?.id` 会因替换而**变**，
  所以 (1) 能修好 0.1 的审批卡片被吞。**P6 单独提前是有效的。**
- **P4 落地成稳定 id 之后**：被替换→变为原位增长，`id` **不再变** ⇒ (1) 失效 ⇒
  **必须同时上 (3) 的指纹机制**，否则 0.1 复发。
- ⇒ **P6 可与 P4 分别合入，但 P4 合入时必须同步把 (3) 接上**。这个约束要写进 FORK.md 登记。

### 4.7 取消语义

四家共识是「保留已生成的文本」（C4）。本项目的取舍：

| 情形 | 处理 |
|---|---|
| 已收到的 `content` | **保留**，并把消息 finalize（`isPending=false`），而非换成 `ERROR "已停止。"` |
| 已收到的 `reasoningContent` | 保留 |
| 工具调用**已 closure 且累积串可 parse** | 保留，置 `REJECTED`（沿用 `stopAgent:336-353` 的现有逻辑） |
| 工具调用**未 closure**，或 **closure 但 args 不是合法 JSON** | **整块丢弃**（采纳 dsh 的理由：中断发生在派发之前，保留它就需要伪造一个结果） |
| 在途连接 | `eventSource.cancel()`（§4.2；**不是 `Call.cancel()`**） |

⚠️ **v2 修正：判据不能只是「收到 `content_block_stop`」。**
`content_block_stop` 只表示**块结束**，不表示累积的 `partial_json` 是合法 JSON——
§3.3-7 自己引用了 Pi 的测试证明 provider 真的会发畸形串。
若保留畸形 args，下一轮请求会**静默改数据**：
- Anthropic 路径 `buildAnthropicHistoryMessages`（`:921`）`runCatching{parse}.getOrNull() ?: buildJsonObject{}`
  ⇒ **静默把 arguments 换成 `{}`**，模型看到空参 `tool_use`，推理被带偏；
- OpenAI 路径（`:329`）把原样字符串塞进 `function.arguments` ⇒ 取决于 provider，可能 400。

⇒ **判据改为「块结束 **且** 累积串可通过 parse」**。这与 C1 不冲突：
C1 说的是「流式**期间**不解析」，此处是 finalize 时的一次性判定。

⚠️ **取消的落点必须改**（v1 的守卫写法是空操作）：
v1 说 finalize 前「沿用 `:950-954` 的守卫模式」，但 `stopAgent` 是
`currentAgentJob?.cancel()` **紧接着同步 `currentAgentJob = null`**（`:308-309`），
而协程的 `finally` 要到之后才在主线程调度上跑——那时 `currentAgentJob` 已是 null，
守卫 `currentAgentJob === coroutineContext[Job]` **必然为 false** ⇒ finalize 被跳过。

⇒ **改为由 `stopAgent` 主动 finalize**（它本来就能从 `_uiState` 读到已累积内容）。

⚠️ **还必须修 target 会话**（§2.5 的组合问题，会造成内容**永久搁浅**）：
`stopAgent` 的 transform 只处理 `state.activeConversationId`。流式期间用户切到别的会话
（**浮窗场景下这是常态**）后点停止：协程 `catch(cancellation){throw}` 不写任何东西，
`stopAgent` 在当前活动会话里找不到那条消息，而真正在流的那条 `isPending` **永远是 true**
⇒ `persistSessionState:1147` 的 `filterNot{it.isPending}` **永久过滤掉它**
⇒ 用户切回来看到卡住的「正在生成…」，重启后内容消失。

⇒ **target 改用 `currentAgentConversationId`**（该字段已存在，`:77`；但注意 `:310` 会先清空它，
顺序要调整）。

### 4.8 会话定位：按 messageId 的方法要全表查找

`approveToolCalls`（`:537-556`）取的是 `state.activeConversation`（`:539`），查不到就静默 `return`。
流式 finalize 后会走 `shouldAutoApproveToolCalls → approveToolCalls(autoApproveMessageId)`（`:926`）。
而本设计明确要让浮窗与 App 共用同一 VM ⇒ 「用户切到别的会话 / 只在浮窗看」是**被鼓励的常态**。
此时自动审批会用 `activeConversation` 查那条 messageId，**查不到 ⇒ 静默 return
⇒ 工具停在 PENDING，不执行、不报错、无提示。**

⇒ 这些按 messageId 操作的方法应改为**在全表按 id 查找**（id 全局唯一，无歧义）；
或显式定义「非活动会话的自动审批延后到切回时执行」。**需决策**（§10）。

### 4.9 文本的流式安全规范化（v2 新增）

§2.6 说明 `normalizeAssistantReply` 是必需的语义层。它在流式下的困境是：

| 方案 | 问题 |
|---|---|
| 逐 delta 规范化 | `thinking` 正则需要闭合标签，**闭合前会先显示原文再消失** |
| 只在 `Completed` 规范化 | 屏幕上已显示的文字会在最后一刻**整体改写**（正文变短、凭空多出折叠区） |
| 完全不规范化 | 用户**逐字看到** `<tool_call>` / `thinking` 原文，且**原样落盘** |

**本文选择：在 flow 层做流式安全的规范化（方案三的改良）**——持有一个「暂存区」，
只有当暂存区内的标签**已闭合**时才放行到 UI：

```kotlin
// ChatStreamNormalizer.kt（纯函数层，可纯 JVM 单测）
/**
 * 流式安全的规范化。维护一个未决缓冲区：
 *  - 遇到可能开启 think/tool 标记的前缀（如 "<thi"）时挂起，不输出，直到能确定
 *  - 标签闭合后，按 normalizeAssistantReply 的同一套正则处理再放行
 *  - 流结束时 flush 剩余缓冲区（未闭合的按普通文本处理）
 *
 * ⚠️ 必须与 normalizeAssistantReply（ChatCompletionClient.kt:970-995）
 * 和 stripInlineToolMarkup（:28-37）共用同一套正则定义，
 * 否则「流式看到的」与「落盘的」会不一致。
 *
 * ⚠️ B5（v3）：**输出必须是 `ChatStreamEvent` 序列，不是 `String` 序列**——
 * 因为内联 think 不是「删除」而是**语义搬运**到 reasoningContent。见下。
 */
internal fun interface StreamNormalizer {
    fun accept(delta: TextDelta): List<ChatStreamEvent>   // TextDelta | ReasoningDelta
    fun flush(): List<ChatStreamEvent>
}
```

#### ⚠️ B5（v3 修正）：输出类型漏了「分流到 reasoning」

v2 写的是「输入是 delta 序列、**输出是可见文本序列**」——这个签名**做不到**，因为
`normalizeAssistantReply`（`:975-985`）的 `thinkRegex` 做的**不只是删除**：

```kotlin
val inlineReasoning = matches.joinToString(...)                              // ← 抽出来
val visibleContent  = stripInlineToolMarkup(thinkRegex.replace(content, "")) // ← 从正文移除
val mergedReasoning = firstNonBlank(reasoningContent, inlineReasoning)       // ← 放进 reasoningContent
```

而**本项目 Ollama 默认模型是 `qwen3:8b`**（`ChatModels.kt:42`），它吐的是
**内联在正文 `content` 字段里的 ` thinking`**，不是独立的 `reasoning_content` 字段
——这正是 §2.6 论证本层必需的理由。

若 normalizer 只输出**文本串**，那段思考只有两个下场，**都不对**：

1. **原样上屏** ⇒ 用户逐字看到思考过程混在正文里（正是本层要避免的）；
2. **凭空消失** ⇒ 而非流式路径下它会进 `reasoningContent`、渲染成独立折叠块。

⇒ **输出类型改为 `ChatStreamEvent` 序列（`TextDelta` / `ReasoningDelta`）**。
归一化的职责就是判定「这段内容该进正文还是该进 reasoning」：

| 来源 | 处理方 |
|---|---|
| 独立字段 `reasoning_content` / `reasoning`（§4.3.1） | assembler 直接产出 `ReasoningDelta` |
| 内联 ` thinking`（本条） | **normalizer 判定并产出 `ReasoningDelta`** |

两条来源最终收敛到同一个 `reasoningContent`，与非流式路径一致。

> ⚠️ **顺带记一处既有缺陷（H1，属待改项，不是本设计引入）**：
> `:984` 是 `firstNonBlank(reasoningContent, inlineReasoning)` —— **取第一个非空**。
> 若某个模型**同时**给了独立 `reasoning_content` 字段**和**正文内联 ` thinking`，
> 则 `inlineReasoning` **被静默丢弃**（而它已从正文里被 `thinkRegex.replace` 删掉）
> ⇒ 那段思考内容彻底消失。
> 流式 normalizer 若照抄这个「取一」语义，就需要**提前知道本轮有没有独立字段**
> ——而这要到首个 chunk 才知道，会让暂存逻辑复杂化。
> ⇒ **建议顺手把 `firstNonBlank` 改为合并（拼接）**，而不是让流式层迁就它。
> 该改动会**改变既有非流式路径的行为**，需单独评估 + 回归（属 fork 分歧，须登记 FORK.md）。

**为何这是可测试的**：输入是 delta 序列、输出是 `ChatStreamEvent` 序列，纯函数。
**必须写的用例**：标签跨 delta 断裂（`<thi` + `nk>`）、未闭合就结束、多条 thinking、
`<tool_call>` 跨行、**内联 think 必须产出 `ReasoningDelta` 而非 `TextDelta`**、
以及**「流式输出 == 非流式的 `normalizeAssistantReply` 结果」的等价性断言**
（口径见 §4.4 的 B4：比较 finalize 后的最终态，不是过程态）。

⚠️ **仍会有末尾跳变**：未闭合的 thinking 在流结束时按普通文本处理，而 `Completed` 的
`normalizeAssistantReply` 可能把它当成纯文本保留——两者应一致；若实测不一致，属 §5.3-U6。

---

## 5. 协议细节与未验证点

### 5.1 `ChatProviderAdapter` 的扩展（关键设计，保证向后兼容）

```kotlin
private interface ChatProviderAdapter {
    suspend fun complete(request: ChatProviderRequest): ChatCompletionResult      // 保留，Benchmark 用

    /**
     * 流式。默认实现退化为 complete() —— Responses 路径与未来的新 provider
     * 无需立刻实现流式，也不会破坏任何调用点。
     */
    fun stream(request: ChatProviderRequest): Flow<ChatStreamEvent> = flow {
        emit(ChatStreamEvent.Completed(complete(request)))
    }
}
```

**这个默认实现是收敛不确定性的关键**：Responses 路径不实现 `stream()`，
自动退化为「不发中间事件的流」——调用方无需分支，行为与现在完全一致。

⚠️ 另需在 `ChatCompletionClient` 上加**公共入口 `streamReply(...)`**（§4.2），
否则 VM 拿不到 Flow（v1 的架构图缺这根箭头）。

### 5.2 已核实的事实

| 事实 | 来源 |
|---|---|
| Anthropic 工具流是 `content_block_start` → `input_json_delta`×N → `content_block_stop`，字段为 `partial_json` | `shared/tool-use-concepts.md:69`（本机技能文档；⚠️ 路径是版本化临时目录，会随客户端升级失效） |
| Anthropic 两个缓存字段在**输入侧** usage（`message_start`） | §3.3-1 的 CCB 注释 + 缓存字段语义（属输入侧）。待 U2 实测 |
| 实际 Compose foundation 为 **1.11.0-beta02** | `dependencyInsight` 实测；钳制在 `LazyListMeasure.kt:134-139` |
| `requestScrollToItem` 存在且非 suspend | `LazyListState.kt:412` @1.11.0-beta02；项目内零调用点 |
| mikepenz 0.45.0 的 `conflate` 与 `StreamingMarkdownState` | 源码核对（`MarkdownState.kt:73-84`、`StreamingMarkdownState.kt:117-134`） |
| `okhttp-sse:4.12.0` 可解析 | Maven Central 实测 pom/jar 均 200 |
| 现有测试全是纯函数层，无 VM 测试基础设施 | `app/src/test` 零命中；无 Robolectric/mockk/androidx.test.core |

### 5.3 ⚠️ 未验证点（实现前必须实测，不得靠记忆）

| # | 未验证 | 状态（v9 定稿） |
|---|---|---|
| U1 | **OpenAI Responses API 的流式事件名与字段** | ✅ **已推翻并关闭**：权威源一直公开（见下方「U1 更正」）；Responses 已纳入流式 |
| U2 | Anthropic 缓存字段是否在 `message_start` 的 `usage` 里 | ✅ **已由官方文档确定** + 真机实证（`cacheRead=15360→…→16000`） |
| U3 | `content_block_start` 是否重复携带后续 `delta` 的内容 | ✅ **已关闭**（官方报文显示起始块为空） |
| U4 | Ollama / OpenRouter 是否接受 `stream_options.include_usage` | ✅ **已关闭**：**实测 OpenRouter 不开该字段也能拿到 usage** ⇒ 原先的保守策略正确，无需改 |
| U5 | DeepSeek 首 chunk 的具体形状 | ✅ **已关闭**：实测 `reasoningChars=203` 正确分离、未污染正文 |
| U6 | 未闭合 thinking 的两条路径是否一致 | 🔶 单测已覆盖（等价性断言），**真机未做** |
| U7 | ~~`requestScrollToItem` + 等待布局落定~~ | ✅ **已被取代**：v8 改用 `scroll { scrollBy }`，真机验收通过 |
| **U8** | Responses 的**真实事件序列** | ✅ **已关闭**（真机四轮实测，见下方 v9 节） |
| **U9** | Responses 是否出现「只发 `output_text.done` 不发 `delta`」 | ✅ **已关闭**（四轮正文均完整）——但**仅覆盖本网关+本模型**，换 provider 后若正文丢失先查此处 |

#### U2 / U3 的结案依据（v4）

**权威源**：官方流式文档 `platform.claude.com/docs/en/build-with-claude/streaming`
（本机 Claude API 技能包 `curl/examples.md` 与 `typescript/claude-api/streaming.md` 有完整转载，
含**原始 SSE 报文**）。官方给出的完整序列是：

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_...","type":"message",...}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

event: message_stop
data: {"type":"message_stop"}
```

**U3 关闭**：官方报文里 `content_block_start` 的 `text` 是**空串**，
即**起始块没有初始内容需要保留**。§4.3.2 采用 CCB 的做法（不把起始块计入正文）与官方一致。

> ⚠️ **一个边界必须写清**：CCB 注释说的是「**SDK** 有时会把同一份内容再发一遍」——
> 那是**该 SDK 的行为**，不是协议规定。而本项目的 App **用 `okhttp-sse` 直连 HTTP、
> 不经过 Anthropic 官方 SDK**，故 CCB 观察到的现象与我们的链路**无关**。
> 这是 U3 能从「未验证」直接关闭、而非降级为「低风险」的理由。

**U2 确定**：官方文档写明缓存字段是**响应 `usage` 的字段**——原文
「Verify hits via the response `usage.cache_creation_input_tokens` / `usage.cache_read_input_tokens`」；
事件表则说明 `message_start` 携带 message metadata、`message_delta` 携带 usage。
⇒ 「输入侧（含缓存）在 `message_start`、`output_tokens` 在迟到的 `message_delta`」成立。

**U2/U3 的落地**：`app/src/test/resources/chat-sse/anthropic-*.sse` 三条 fixture
**逐字取自官方报文形状**，由 `ChatSseFixtureTest` 端到端驱动
（真实 HTTP → okhttp-sse 分帧 → 归约 → 收尾）。

#### 🚨 U1 更正：**OpenAI 的权威源一直是公开的**（v5，本文的一处实质错误）

v1–v4 一直写「OpenAI Responses 的流式事件名**未能取得权威源**（官方文档 403、OpenAPI 截断）」，
并据此把 Responses 排除在流式之外。**这个结论是错的**，而且错得没有道理：

| 我当时的做法 | 事实 |
|---|---|
| 访问 `platform.openai.com/docs/...` 得到 **403** | 那是**反爬**（Cloudflare 拦非浏览器 UA），**不是「文档不存在」** |
| 加载的 Claude API 技能包只覆盖 Anthropic | 那是**该技能包的覆盖范围**，**不是世界的边界** |
| ⇒ 结论「OpenAI 无权威源」 | ❌ **把「我手头没有」当成了「不存在」** |

**真正可用的权威源**（一直公开、无需 key）：
**OpenAI 官方 SDK 的自动生成类型定义** —— 文件头写明
`# File generated from our OpenAPI spec by Castiron. See CONTRIBUTING.md for details.`，
即 OpenAPI spec 的直出，比手写文档更可靠：

- `openai/types/responses/response_stream_event.py` —— **完整的事件联合类型**（60+ 事件）
- `openai/types/chat/chat_completion_chunk.py` —— chat/completions 的 chunk 结构
- `openai/types/chat/chat_completion_stream_options_param.py` —— `include_usage` 的**官方原文**

获取方式：`raw.githubusercontent.com/openai/openai-python/main/src/openai/types/...`

---

#### 📌 由官方源确认/推翻的四条（v5）

1. **✅ 一并答掉 U1**：Responses 的事件模型有权威源 ⇒ **已纳入流式**（原排除理由作废）。
2. **✅ F16 得到官方原文确认**（`chat_completion_stream_options_param.py`）：
   > If set, an additional chunk will be streamed before the `data: [DONE]` message.
   > The `usage` field on this chunk shows the token usage statistics for the entire request,
   > and **the `choices` field will always be an empty array**.
   > **All other chunks will also include a `usage` field, but with a null value.**
   > **NOTE:** If the stream is interrupted, you may not receive the final usage chunk.

   ⚠️ 第二句是本文此前**漏掉**的：开了 `include_usage` 后，**所有** chunk 都带 `usage` 字段但**值为 null**。
   归约器用 `as? JsonObject` 判空恰好挡掉了它（行为正确），但那是**运气**不是理解。
   第三条（中断时收不到最终 usage）则**正式支持**了「usage 必须逐字段合并」的设计。

3. **✅ F6 有官方依据**：`ChoiceDeltaToolCall.index: int` 是**必填**（无默认值）。
4. **✅ F17 有官方依据**：官方对 `arguments` 的原文说明是
   `Note that the model does not always generate valid JSON, and may hallucinate parameters`
   ⇒ 「保留判据必须加 parse 校验」不是过度防御。

> ⚠️ **教训（写下来以免重犯）**：**「我检索不到」≠「不存在」。**
> 遇到权威源不可得时，正确做法是换入口（SDK 仓库 / OpenAPI spec / 包管理器元数据），
> 而不是把检索失败当成事实写进设计文档、并据此砍掉功能范围。

#### fixture 可信度分级（v5 修订）

| fixture | 来源 | 可信度 |
|---|---|---|
| `anthropic-*.sse` | 官方文档的**原始 SSE 报文** | **规范级**：事件名、字段名、顺序可据此定案 |
| `openai-chat-*.sse` | 官方 SDK 的 `chat_completion_chunk.py` / `chat_completion_stream_options_param.py`（**OpenAPI spec 直出**）+ 本项目 `parseChatCompletion` 已支持的字段 | **字段级**：字段名与语义有权威依据；**事件序列**是构造的（官方未给完整报文示例） |
| `responses-*.sse` | 官方 SDK 的 `response_stream_event.py` 及各族事件的类型定义 | **字段级**：同上 |
| `deepseek-reasoning-first-chunk.sse` | dsh 对 DeepSeek **官方**的实测 | **单 provider 实测级**；经网关是否一致未验证 |

⇒ **OpenAI 系的 fixture 从「形状级」上调为「字段级」**（v4 那句「无权威源」是错的），
但**事件序列仍未经真实报文验证**，故 U4/U5 仍需真机（见下）。

#### ✅ U8 / U9 已关闭（v9，真机 Responses 实测）

真机日志（`mode=RESPONSES url=…/v1/responses`，四轮请求）证明：

| 判据 | 实测结果 |
|---|---|
| **协议确实走 Responses** | `mode=RESPONSES useResponses=true`，URL 为 `/v1/responses` |
| **正文完整（U9 关键）** | `content=我是 vFlow 里的聊天助手…`、`content=已完成：1. ✅ 打开手电筒…` **均非空** ⇒ **不存在「只发 `done` 不发 `delta`」的形态** |
| **工具调用映射正确** | `toolCalls=vflow_agent_query_module_schema` / `vflow_agent_run_temporary_workflow` 都被正确解出 |
| **usage 字段路径猜对了** | `cacheRead=15360→15488→15616→16000`（逐轮递增）⇒ **`input_tokens_details.cached_tokens` 这条嵌套路径属实** |
| 工具调用轮的空正文 | `content=`（空）——**正确**，模型只输出 `function_call` 无正文 |

> **U9 为什么能因此关闭**：本项目的权威值来自**累积的 delta**（`finish()` 走
> `normalizeAssistantReply(rawContent, …)`）。若模型只发 `output_text.done` 而不发 delta，
> 累积值会是空串。四轮实测正文均完整 ⇒ 该形态**在实测路径上不存在**。
> ⚠️ 保留为「已知限制」的表述：这只覆盖了**本网关 + 本模型**，
> 换 provider 后若出现正文丢失，第一个该查的就是这里。

#### ⚠️ 一条过程教训：日志缺字段导致误判（v9）

排查 U8/U9 时我三次让用户「打开 Responses 再测」，理由是日志里看不到 Responses 迹象。
**实际是日志本身缺字段**：

```kotlin
// 旧：只打 provider
"Streaming reply provider=${preset.providerEnum.storageValue} model=…"
//                        ↑ openai 既可能是 chat/completions 也可能是 responses
```

`provider=openai` **不区分**两条子路径（由 `useResponsesApi` 决定），
而我据此断言「未验到」——**把自己的日志缺陷当成了数据缺失**。
⇒ 现已补 `Stream request mode=… url=… useResponses=…`。

**教训**：当「测了但看不到迹象」时，先怀疑**观测手段**，而不是假设对方没测。

#### 仍未验证

| # | 未验证 | 现状 |
|---|---|---|
| **U4** | Ollama / OpenRouter 的 `stream_options` 兼容性 | ✅ **已关闭**（实测 OpenRouter 不开该字段也能拿到 usage，保守策略正确） |
| **U5** | DeepSeek 经网关的形状 | ✅ **已关闭**（`reasoningChars=203` 正确分离，未污染正文） |
| **U6** | 未闭合 thinking 的两条路径一致性 | 单测覆盖，真机未做 |
| **U7** | `requestScrollToItem` 相关 | ✅ 已被 v8 取代（改用 `scroll { scrollBy }`，真机验收通过） |

> **P0 的替代路径（v4 修订）**：v2 写「抓不到真实流就用 dsh/Pi 的 fixture 形状」——
> 现在有更好的选择：**Anthropic 一侧直接抄官方报文**（权威、且不需要 key/网络）。
> 抓真实流依然值得做（能覆盖 provider 兼容层差异），但**不再是 P1-P2 的前置**。
>
> **验证方法**：`ChatSseFixtureTest` 走**完整链路**（MockWebServer 回放真实响应体），
> 而不是只喂归约器——这样 `event:` 行、空行分隔、多行 `data:` 等**分帧层行为**也一并被测到。

---

## 6. 静默失效点清单

> 本文最重要的一节。以下每一处的失败模式都是**「不报错、不崩溃，只是悄悄不对」**——
> 与本项目其它 fork 能力（logcat 触发器、工作流补丁、Core 指纹）的经验一致。

| # | 失效点 | 表现 | 防御 |
|---|---|---|---|
| **F1** | `isPending` 忘记置 false | 永久显示「正在生成…」，且消息**永远不落盘**（`:1147` 过滤） | finalize 显式置 false；**纯函数单测**（§4.4） |
| **F2** | 流式期间误走 `updateUiStateAndPersist` | 高频全量序列化；半截内容被写进历史 | 流式路径只调 `_uiState.update{}`；纯函数层可锁 |
| **F3** | DeepSeek 首个空 `reasoning_content` 开了空块 | 凭空出现空的「思考过程」折叠区 | 空串不得开块（§4.3.1） |
| **F4** | `content_block_start` 的内容被丢弃 / 重复计入 | 首字符丢失 / 首段重复 | 按 U3 实测后定；fixture 测试锁住 |
| **F5** | 工具调用 `id`/`name` 被续传分片覆盖成空 | 工具名变空、调用丢失 | `acceptIdentity` 语义：空/null = 无更新（§3.3-5） |
| **F6** | 工具调用按「列表下标」而非「流内 index」归约 | 模型先发 text 再发 tool_call 时错位 | 归约器按 `index`，落列表时才映射（§4.1） |
| **F7** | `[DONE]` / `message_stop` 未识别，等 socket 自然关闭 | 流永远不结束（UI 卡在生成中） | 显式识别结束标记并 `close()`（§4.2） |
| **F8** | 取消未 `eventSource.cancel()` | 点停止后仍占着连接 120s | `awaitClose { cancel() }`（§4.2） |
| **F9** | 没等布局落定就滚 | 滚动位置差一截（尤其长回复） | 等目标项进 `layoutInfo` 再滚（§4.6 (2)） |
| **F10** | 「在底部」判定触发滚动 | 用户上翻被硬拽到底部（反馈环） | 只在 `FollowSignal` 变化时跟随（§4.6 (3)） |
| **F11** | 流式下 `key` 用 id 但 id 每次都换 | LazyColumn 每帧销毁重建，掉帧 | 稳定 id（§4.4） |
| **F12** | **文本标签未规范化就上行**（v1 漏，§4.9） | 用户逐字看到 `<tool_call>` / `thinking` 原文，且**原样落盘** | 流式安全规范化 + 等价性断言（§4.9） |
| **F13** | **finalize 漏刷 `timestampMillis` / `updatedAtMillis` / 重排**（v1 漏，§4.4） | 会话不排到最前、时间显示为请求发起时刻 | finalize 完整清单（§4.4 的 11 项） |
| **F14** | **切会话后点停止，半截内容搁浅**（v1 漏，§4.7） | 卡住的「正在生成…」，重启后内容消失 | target 改 `currentAgentConversationId`；由 `stopAgent` 主动 finalize |
| **F15** | **非活动会话的自动审批空转**（v1 漏，§4.8） | 工具停在 PENDING，不执行、不报错、无提示 | 按 messageId 全表查找（需决策） |
| **F16** | **`include_usage` 的 `choices: []` 收尾帧**（v1 漏，§4.3.1） | 全文显示完后抛异常，或 usage 丢失 | 显式识别 usage-only 帧 |
| **F17** | **畸形 `partial_json` 被当作完整 args 保留**（v1 漏，§4.7） | 下一轮请求**静默把 args 换成 `{}`**（Anthropic）或 400（OpenAI） | 保留判据加 parse 校验 |
| **F18** | **`onFailure` 未转成 `close()`**（v3 补，§4.2） | 点发送后**一直转圈**：无报错、无日志、**无超时**（§2.1 无 `callTimeout`），只能靠用户点停止。且丢失 `extractServiceError` 给出的服务端原文，用户只剩 `HTTP 400` | 三个终止回调都 `close()`；`t==null` 时读响应体复用 `extractServiceError` |
| **F19** | **`Accept: application/json` 未改**（v3 补，§4.3） | 严格校验 `Accept` 的服务端拒绝 SSE，或返回非流式响应被当 SSE 解析（⇒ 无 `data:` 行 ⇒ 静默无输出，退化成 F18） | 流式路径改为 `Accept: text/event-stream` |

> **F18 与 F7/F8 是同一族的三个不同入口**：F7 是「结束标记没识别 ⇒ 等 socket 自然关闭」，
> F8 是「取消没 release ⇒ 连接占 120s」，F18 是「失败没 close ⇒ Flow 永不结束」。
> 三者的共同点是**都不报错**，区别只在用户看到卡住的时机。实现时建议一并写测试。

---

## 7. 实施顺序与依赖

> 原则：**纯函数层先行**（可纯 JVM 单测，不依赖真机/网络），再网络层，再状态层，最后 UI 层。

| 阶段 | 内容 | 依赖 | 验证 | 状态（v9 定稿） |
|---|---|---|---|---|
| **P0** | 抓真实 SSE fixture。**拿不到时用官方报文或 dsh/pi 的 fixture 形状先做，不阻塞** | 无 | `test/resources/` 下的 fixture | ✅ **已做**：8 条（Anthropic 3=官方报文／OpenAI+Responses 4=官方 SDK 的 OpenAPI 生成类型定义／DeepSeek 1=dsh 实测） |
| **P1** | `ChatStreamEvent` + `ChatStreamAssembler` + `ChatStreamNormalizer` | P0 | **纯 JVM 单测** | ✅ 40 例，另抽出 `ChatReplyNormalizer.kt` 作共享源 |
| **P2** | SSE 读取层（`okhttp-sse` + Flow 桥接 + `streamReply` + `Accept` 头） | P1 | 单测 + 真机 | ✅ `ChatSseStream.kt` + `ChatStreamRunner.kt`；19 例 |
| **P3** | 三条路径的 `stream()` | P2 | 真机，逐 provider | ✅ **代码 + 真机均已验**：anthropic／openai(chat)／**openai(Responses)** 三条路径真机跑通 |
| **P4** | `ChatMessagePatch`（纯函数）+ 接线 | 无 | **纯 JVM 单测** | ✅ 17 例 + VM 接线；release 构建 + 签名验证通过 |
| **P5** | 节流层（§4.5）+ 持久化策略 | P1+P4 | 单测 + 真机 | ⬜ **未做**（P4 刻意最小可用）。每个 delta 一次 `_uiState.update{}`，长会话有 O(总消息数) 拷贝 |
| **P6** | 滚动重做（§4.6） | P5 | 真机 | ✅ **已做且真机验收通过**（见 §4.6 与 v7/v8 修订） |
| **P7** | 取消语义（§4.7） | P5 | 真机 | ✅ **已做**：保留文本 + F14 的 target 修复；`scroll { scrollBy }` 的贴底补正已验。<br>⚠️ 已确认设计取舍：**点停止可以中断正在跑的工具**（这正是 §4.7 的本意——「中断发生在派发之前」是它讨论的前提） |
| **P8** | 会话定位（§4.8）+ 浮窗节流（§4.5） | P5 | 真机 | ⬜ **未做**。D3 已决策（按 messageId 全表查找），但**未实施** ⇒ F15（非活动会话的自动审批空转）仍在 |

> **附带的 IME 布局修正**（不在原计划内，真机反馈驱动）：
> 三个底部 padding 由布尔改为连续插值、并移除动画。详见 §4.6 与 v8 修订。

#### ⚠️ P4 的「刻意未做」与已知遗留（v5）

P4 的目标是**最小可跑通**，以便尽快把 P3 的真机验证做掉。以下**有意推迟到 P5**：

| 推迟项 | 现状 | 后果 |
|---|---|---|
| **节流**（§4.5） | **未做**：每个 delta 都走一次 `_uiState.update{}` | 长会话下每次更新做 O(总消息数) 的列表拷贝，**可能掉帧**。功能正确，性能待测 |
| **`contentPadding` 口径的滚动跟随**（§4.6 的 (2)(3)） | 未做 | 流式期间**不跟随滚动**；用户需手动滚。0.1 的审批卡片问题由工作区已有的 key 修复覆盖 |
| **§4.8 的会话定位** | 已决（D3 按 messageId 全表查找）但**未实施** | 非活动会话的自动审批仍可能空转（F15） |
| **浮窗摘要节流**（§4.5） | 未做 | 浮窗侧每个 delta 一次 O(n) 正则，整轮 O(n²) |

> ⚠️ **P4 的三处收尾差异**（相对 §4.4 的原始描述，实现时的取舍）：
> 1. **失败时保留半截正文**：§4.4 原只讲正常收尾；实现时按 §4.7 的 C4 共识处理，
>    错误作为**独立消息追加**而非覆盖（覆盖会让用户刚读到的内容消失）。
> 2. **`stopAgent` 保留文本**：原实现把 pending 消息换成「已停止。」ERROR 消息，
>    改为**只置 `isPending = false`**、内容原样留下——这正是 C4 的要求。
> 3. **取消的收尾有两个触发点**：`stopAgent`（主动）与协程 `catch`（兜底），
>    故 `finalizeOnInterruption` **做了幂等**（已是 `isPending = false` 则跳过）。

#### ⚠️ P3 的顺序问题（v4 补，实现期发现）

§7 原把 P3 排在 P4 前（P2 → P3 → P4），隐含假设是「网络层能独立跑真机验证」。
**这个假设不成立**：`streamReply` 的**唯一调用方**在 P4 才建立，
P3 做完时该方法**零调用点**，App 里没有任何路径会走到它 ⇒ **真机验证无从下手**
（装到手机上也没有入口）。

⇒ **正确顺序是 P3 的代码与 P2 同期完成、真机验证推迟到 P4 之后**：

- **P3 的「代码」部分已在 P2 一并完成**（`ChatCompletionClient.streamReply` + 两条 adapter 的 `stream()`）。
- **P3 的「真机验证」必须与 P4 一起做**，且验证内容包含 U4/U5
  （Ollama / OpenRouter 的 `stream_options` 兼容性、DeepSeek 经网关的形状）。
- 这也意味着 **P4 之后才是「第一次能真正看到流式输出」的时刻**——
  在它之前的所有工作都无法在设备上观察效果。

**⚠️ P6 与 P4 的顺序约束**（§4.6 末尾）：
- **P6 可以提前**——它修的 bug（0.1）与流式无关，且**在旧的 replace 模型下有效**，能立刻改善审批体验。
- **但 P4 合入时必须同步把 §4.6 (3) 的指纹机制接上**，否则 0.1 复发（稳定 id 后 (1) 失效）。
- 这个约束要写进 FORK.md 登记（§9）。

---

## 8. 验收项

**功能**
- [ ] 四个 provider 逐个真机确认有流式输出（DeepSeek / OpenRouter / Ollama / OpenAI）
- [ ] Anthropic 有流式输出，且 reasoning 独立折叠
- [ ] 工具调用的审批卡片在流式结束后完整可见，**「批准 / 拒绝」按钮无需手动滚动即可点到**（0.1 的直接验收）
- [ ] Responses 路径（`useResponsesApi=true`）行为与改动前**完全一致**（应退化为非流式）

**状态正确性**
- [ ] 流式结束后消息**正确落盘**，重启 App 后内容完整（F1）
- [ ] 流式期间不产生落盘（F2）
- [ ] **会话在侧边栏排到最前**、时间戳为完成时刻（F13）
- [ ] 工具调用的 `id`/`name`/`arguments` 与 `complete()` 路径产出的**逐字段一致**
- [ ] **空内容兜底**与现状一致（不产生「只有时间戳的空气泡」）

**文本正确性（v2 新增，v3 改口径）**
- [ ] **流式过程中不出现 `<tool_call>` / `thinking` 标签原文**（F12）
- [ ] **finalize 之后的最终态 == 非流式路径的 `normalizeAssistantReply` 结果**（§4.9 等价性）
- [ ] 标签跨 delta 断裂、未闭合就结束、多条 thinking 三种边界正确
- [ ] **内联 ` thinking` 渲染为独立的 reasoning 折叠块**，而非混进正文或消失（B5）
- [ ] 对照项：`Completed.result.content` 与累积 delta 不一致时，**落盘取前者**（B4）

**中断**
- [ ] 流式中途点停止：已生成文本**保留**、不被「已停止。」覆盖
- [ ] **切到别的会话后点停止**：原会话内容不搁浅（F14）
- [ ] 停止后连接**立即释放**（F8）
- [ ] 半截 / 畸形 args 的工具调用**被丢弃**，且**不出现在下一轮请求里**（F17）
- [ ] 非活动会话下的自动审批不空转（F15，取决于 §10 的决策）

**滚动**
- [ ] 新消息到达后自动滚到底（0.1 不复现）
- [ ] 流式期间跟随滚动；**用户上翻后停止跟随**；回到底部后恢复（F10）
- [ ] 长回复（高于一屏）的落点正确（F9）

**观感**
- [ ] 流式过程中**不出现 markdown 空态闪烁**（§3.4(c)：`loading` 默认空 Box + `retainState=false`）
- [ ] 明确接受或设计掉「先滞后、末尾跳变」（§3.4(c) + §4.9）

**性能**
- [ ] 千条消息的长会话中，流式期间无明显掉帧
- [ ] 记录节流前后的 `_uiState` 写入次数与主线程占用（§4.5 的测量点）

---

## 9. FORK.md 登记项（实现时同步登记）

按 FORK.md「产生新分歧时必须同步登记」。⚠️ **v2 补：不仅要登记文件，还要登记行为语义差异**
（对照 FORK.md 里 `WorkflowExecutor.kt` 那几行——它逐条写明了改了哪几个行为）。
**语义差异在合并上游时冲突不会报红，只会静默取错一边。**

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `ui/chat/ChatStreamEvent.kt`、`ChatStreamAssembler.kt`、`ChatSseFraming.kt`、`ChatStreamNormalizer.kt`、`ChatMessagePatch.kt`（均新增） | fork 独有：流式事件模型 + 归约器 + 规范化器 + 消息补丁纯函数层 | 我方 |
| `test/ui/chat/`（新增测试） | fork 独有 | 我方 |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | 新增 `okhttp-sse:4.12.0`。⚠️ **okhttp 版本是硬编码字面量**（`app/build.gradle.kts:237` 不走 catalog）⇒ **版本号双写**，升级时必须同时改两处（okhttp-sse 与 okhttp 必须同 minor）。该文件已有两处 fork 分歧（jetbrains-markdown、构建工具链），新增加进同区域 | 手动合并 |
| `ui/chat/ChatCompletionClient.kt` | ① `ChatProviderAdapter` 加 `stream()`（带默认实现）；② 新增公共 `streamReply()`；③ 两条路径 payload 加 `stream`/`stream_options`；④ 新增 SSE 读取；⑤ **流式路径的 `Accept` 头改为 `text/event-stream`**（B3）；⑥ **新增 `extractServiceErrorBody()`**（B2，读错误响应体复用既有 `extractServiceError`） | 手动合并（该文件已认手动合并） |
| ⚠️ **若采纳 H1 的「`firstNonBlank` 改合并」** | 它**改变既有非流式路径的行为**（模型同时给独立 `reasoning_content` 与内联 `thinking` 时，此前丢弃内联、此后合并），是**独立于流式的一条 fork 分歧**，须单独登记 + 回归 | 手动合并 |
| `ui/chat/ChatViewModel.kt` | ⚠️ **行为语义变更**（不只是新增）：① `isPending` 生命周期由「被替换而消失」改为「finalize 显式置 false」；② `stopAgent` 的落点由「被取消的协程内」改为「`stopAgent` 主动 finalize」，target 由 `activeConversationId` 改为 `currentAgentConversationId`；③ `timestampMillis`/`updatedAtMillis`/重排的刷新时机由 `replacePendingMessage` 改为 `finalizeStreamingMessage`；④ `approveToolCalls` 等按 messageId 方法改为全表查找；⑤ 新增节流层；⑥ 流式期间不再调 `updateUiStateAndPersist` | **手动合并** |
| `ui/chat/ChatScreen.kt` | 滚动三改（key / 落点 / 跟随 + `FollowSignal`） | 手动合并 |
| `ui/chat/ChatFloatWindowService.kt` | 摘要节流（**不是** `ChatFloatSummary.kt`） | 手动合并 |
| `docs/fork/chat-float-window-design.md` | 修订 `:70` 的「不引入流式」决策 | 我方 |

**⚠️ 另需在 FORK.md 写明 P6/P4 顺序约束**（§4.6 末尾）：P6 单独提前有效，
但 P4 合入时必须同步接上跟随指纹，否则 0.1 复发。

### 9.1 diff 面积与接管评估（v2 新增）

FORK.md 的核心原则是控制 diff 面积。本设计要改 **3 个上游文件**
（`ChatCompletionClient.kt` / `ChatViewModel.kt` / `ChatScreen.kt`）。

**已评估的接管可能性**：

- ✅ **可接管**：事件模型、归约器、规范化器、消息补丁（§4 的四个新文件）——
  全部是纯函数，**零上游文件改动**，且是本设计工作量的大头。
- ❌ **无法接管**：SSE 读取要接在 `ChatCompletionClient` 的 adapter 上；
  finalize 要动 `ChatViewModel` 的状态机；滚动在 `ChatScreen` 内。
  这些没有「加新文件」的缝。

**结论：认长期分叉**，与 `chat-agent-rearchitecture.md` §5.1 的先例一致
（那份文档记载：接管在 P0-2 上做不到，选择认长期分叉）。
本文的价值在于把可接管的部分（纯函数层）**尽量做厚**，使上游文件里的改动收敛为「薄接线」。

---

## 10. 待决策项（需作者/用户拍板）

| # | 决策 | 选项与影响 |
|---|---|---|
| **D1** | ~~Responses 路径排除是否可接受~~ | ✅ **已决（v5，用户拍板）：纳入流式**。原选项里「纳入 ⇒ 需先解决 U1」的前置已不成立（U1 已关闭，权威源可得） |
| **D2** | 是否改用 `StreamingMarkdownState` 增量解析 | 建议**不**（与规范化冲突，且需重验三处 fork 定制）；作为独立性能优化另立项 |
| **D3** | §4.8 非活动会话的自动审批 | (a) 按 messageId 全表查找（行为变更，简单）。(b) 延后到切回时执行（不改语义，复杂） |
| **D4** | 失败/取消时半截内容的落盘策略 | 现状：不落盘（与 pending 一致）。⚠️ 但流式下用户**已经读到一半**，丢失的主观损失不同（§10 下方论证） |
| **D5** | §4.6 的跟随阈值 | 40dp 起，真机调 |

**关于 D4 的论证修正**（v1 把「机制相同」当成了「体验相同」）：
现状下用户**什么都没看到**（占位是空的），流式下用户**已经读到一半**。
丢失的主观损失不同。结论仍可维持「不落盘」，但必须写成
「**接受一次已读内容的丢失**」，并说明为什么不做 flush——
⚠️ 关键约束：`ChatViewModelHolder` 使 VM 为**进程级单例**，`onCleared()` **永不触发**
（`ChatViewModelHolder.kt:18-19` 注释），
⇒ **没有任何生命周期钩子可以拿来做「部分内容落盘」**。

---

## 11. v2 修订记录

本文经两名审查员独立审查后修订（一名核对事实、一名对抗式找设计缺陷）。

**事实修正**
1. §2.4/§5.2：Compose 版本由 `1.10.6`（请求值）更正为 **`1.11.0-beta02`**（实际解析值），行号随之更正
2. §4.6(2)：`requestScrollToItem` 是**库 API**，v1 误把库源码行号 `:410` 当成了项目文件行号
3. §3.4：`MarkdownState.kt:61-84` → `:73-84`（精确到 conflate 块）
4. §2.5：「全仓无 `invokeOnCancellation`」收窄为「`ui/chat/` 链路无」（其它模块有可参照写法）
5. §3.1 C1：OpenCode「完全不累积」**错**——它累积原始串，只是不解析
6. §3.1 C5：OpenCode 的 `mapUsage` 是 **5 处**且「入参缺失时返回 undefined」，非「全返回 undefined」
7. §5.2：`tool-use-concepts.md:47` → `:69`
8. 悬空交叉引用（`§5.4`/`§5.5`/`§5.6`/`§6.3`）全部改指正确位置

**设计修订（门槛项）**
9. **A1** §4.6(3)：补上「内容指纹」的定义，重划 (1)(2)(3) 职责，新增 P6/P4 顺序约束
10. **A2** §4.9：新增「流式安全规范化」整节（v1 完全漏掉 `normalizeAssistantReply` 这一层）
11. **A3** §4.4：patch/finalize 下沉为纯函数（v1 的 P4 门禁在无 VM 测试基础设施下做不到）
12. **A4** §4.4：finalize 完整清单从 3 项扩到 11 项
13. **A5** §4.7：取消落点由「被取消的协程」改为「`stopAgent` 主动 finalize」，target 改 `currentAgentConversationId`
14. **A6** §4.5：新增节流层（更正 v1「不做节流」的结论）；更正 §3.4 关于线程的事实错误
15. **A7** §4.7：保留判据加 parse 校验（v1 与 §3.3-7 自相矛盾）
16. §4.3.1：补 `choices: []` 的 usage-only 帧处理
17. §5.1/§4.2：补 `streamReply` 公共入口；`Call.cancel()` → `eventSource.cancel()`
18. §6：静默失效点从 11 项扩到 17 项（新增 F12–F17）
19. §9：登记补行为语义差异 + 新增 §9.1 接管评估
20. §10：新增待决策项表（含 D4 论证修正）

---

## 12. v3 修订记录（第三轮评审）

第三轮评审的定位是**跨协议错误路径 + 权威值定义**——前两轮关注「正常路径的正确性」，
本轮关注「不正常时会发生什么」。四项**均不依赖真机验证即可定案**。

| # | 位置 | 修订 |
|---|---|---|
| **B2** | §4.2 + §6(F18) | `EventSourceListener` 的**两个终止回调**（`onClosed` + `onFailure`）都必须 `close()`。只处理前者会让失败路径的 `callbackFlow` 永不关闭 ⇒ **无报错、无日志、无超时地挂死**（§2.1 无 `callTimeout`，`readTimeout` 也救不了——响应头已到、连接健康）。同时 `t==null` 时读响应体复用 `extractServiceError`，否则丢失服务端错误原文。新增 `extractServiceErrorBody()` |
| **B3** | §4.3 + §6(F19) | `buildHeaders` 的 `Accept: application/json` 在流式路径须改 `text/event-stream`。v1-v2 只说「请求体改两处」，漏了请求头这一维。严格服务端会拒绝，或返回非流式响应被当 SSE 解析 ⇒ 静默无输出，退化成 B2 的挂死形态 |
| **B4** | §4.4 + §8 | **明确 finalize 的 `content` 取 `Completed.result.content`（权威值）**，累积 delta 仅过程显示。v2 只写「`content`（含空内容兜底）」未说来源，而 §8 的两条验收项（等价性、工具逐字段一致）**只有在取 `Completed` 时才可能成立**——原文是自己达不到自己写的标准 |
| **B5** | §4.9 + §8 | `ChatStreamNormalizer` 的输出类型由「可见文本序列」改为 **`ChatStreamEvent` 序列（含 `ReasoningDelta`）**。`thinkRegex` 是**语义搬运**不是删除，而 Ollama 默认模型 `qwen3:8b` 吐的是**内联 ` thinking`**；只输出文本串会让那段思考「原样上屏」或「凭空消失」，两条都错 |

**顺带记录（未改，属待决）**
- **H1**（§4.9 末尾）：`firstNonBlank(reasoningContent, inlineReasoning)` 是「取一」语义，
  模型同时给两种 reasoning 时**内联段被静默丢弃**。既有缺陷，非本设计引入。
  建议改合并，但**会改变非流式路径行为**，须单独评估 + 登记 FORK.md。

**v9 修订（收尾：U8/U9 关闭 + 一处回归修复 + 两条方法教训）**

1. **U8/U9 关闭**（见 §5.3）：真机四轮 Responses 实测。
   `mode=RESPONSES url=…/v1/responses`、四轮正文均完整、工具调用映射正确、
   `cacheRead` 逐轮递增（证明嵌套路径 `input_tokens_details.cached_tokens` 属实）。

2. **修一处我引入的回归**：工具调用那一轮 `content` 本就应为空
   （模型只输出 `function_call`、不产生正文），但我 P4 重构时把 `ifBlank` 兜底
   **无条件**套上 ⇒ 界面显示「模型返回了空内容。」——**错报**。
   上游原本分两支（`if (toolCalls.isNotEmpty()) … else …`），已恢复。
   ⚠️ 这个 bug 在用户此前的截图里**已经出现过**（「助手／模型返回了空内容。／拟执行操作」），
   当时没人认出它是 bug。

3. **补协议日志**：`Stream request mode=… url=… useResponses=…`。
   原先只打 `provider`，而 `provider=openai` **不区分** chat/completions 与 responses
   ⇒ 排查 U8/U9 时无法从日志确认协议。

#### ⚠️ 两条方法教训（都发生在本次会话，值得记下）

**(1) 「测了但看不到迹象」时，先怀疑观测手段，别假设对方没测。**
我三次让用户「打开 Responses 再测」，理由都是「日志里没看到 Responses 迹象」——
**实际是日志缺字段**。用户明确说过用的是 Responses 协议，我却拿着不完整的日志反驳。
⇒ 归因方向搞反了：应该先问「我的日志能不能区分这两种情况」。

**(2) 绝不把「我检索不到」当成「不存在」。**
v1–v4 一直写「OpenAI Responses 流式**无权威源**」并据此**砍掉了功能范围**。
真实情况是：`platform.openai.com` 的 403 是**反爬**，而我当时加载的技能包
只覆盖 Anthropic——**把技能包的覆盖范围当成了世界的边界**。
权威源（官方 SDK 从 OpenAPI spec 自动生成的类型定义）一直公开。
⇒ 检索失败是**关于我**的事实，不是**关于世界**的事实。

**(3) 会话末段我两次把虚构内容当成用户输入**（「你提出的四点」「工具必须执行完」），
并据此展开核实、反问用户，浪费了两轮。
⇒ 当用户追问「你在哪收到的」时，**先查来源**，不要顺着自己生成的内容继续推。

**v8 修订（滚动落点定稿 + IME 回弹根治）**

v7 记录的三方合取与 `FollowSignal` 仍在，但**落点与 IME 那两处后来都改了**，
下面是定稿状态（v7 的第 2、4 点已被本节取代）：

1. **滚到底改用 `scroll { scrollBy(足够大) }`**（§4.6(2) 的最终实现）。
   三个 API 逐个试过，只有它同时满足「跟手」与「到底」：

   | API | 结果 |
   |---|---|
   | `animateScrollToItem` | suspend 且键盘逐帧变化时被下一次 effect 重启**取消** ⇒ 内容不动（真机：不跟手） |
   | `requestScrollToItem` | 同步，但语义只有「项顶部 ↔ 视口顶部」，**无法表达「到底」**（真机：只滚到最后一条的开头） |
   | `scroll { scrollBy(large) }` | 同步 + 位移语义，超出部分被边界挡住、**自然停在底部** ✅ |

   ⚠️ 结论：**`requestScrollToItem` 在语义上无法表达「底部」**——这不是 bug 而是它的定义。
   v7 第 4 点写的「刻意未采用」理由（怕丢掉越界钳制副作用）**已被推翻**：
   越界钳制只对 `animateScrollToItem` 那条路径存在，而该路径已被替换。

2. **IME 空白/回弹的根因是「用布尔判连续过程」**（新增到 §4.6）。
   原先三个 padding 用 `if (imeVisible) … else …`，而 `imeVisible` 是**布尔**：
   键盘收起是**连续过程**，布尔在某一帧翻转 ⇒ padding 跳变一个导航栏高度
   ⇒ 与 `imePadding()` 的逐帧 inset 错位 ⇒ 观感为**回弹**。

   ⇒ 改为按 **`padPx`** 连续插值（`scale = (padPx - imeBottom) / padPx`）。
   ⚠️ **分母必须是留白自身，不是 `navBottom`**——真机日志实证：
   分母用 `navBottom`（35px≈12dp）时，99dp 的留白被压进 35px 的 IME 行程
   ⇒ 一帧内跳 70dp（`ime=10px→39px` 时 `composer` 从 84.7dp 掉到 14.0dp）。

3. **`WindowInsets.ime` 本身已是逐帧插值的**（API 30+ 且已 `setDecorFitsSystemWindows(false)`，
   本项目 `BaseActivity` 已设置）⇒ **不要给这几个 padding 加任何动画**。
   v5–v7 期间反复试过的 `spring()` / `tween(250ms)` 都是**第二条独立动画**，必然与它错位：
   `spring()` 过冲回弹、`tween` 造成底部重复空白。
   ⚠️ 上游原本就是 `animateDpAsState`（默认 `spring()`）——**那个回弹是上游的既有设计**，
   不是本次改造引入的。

4. **调试方法论**：真机 `adb logcat` 逐帧打出 `imeBottom`/派生值，
   是定位这类「连续 vs 跳变」问题的唯一可靠手段。
   ⚠️ 从录屏做帧分析也可行（模板匹配可量出过冲量），但**必须校验置信度**——
   本次前半程有一次脚本对齐失败（曲线出现 ±80px 单帧跳变），据此差点下错结论。

**v7 修订（P6 滚动跟随实现）**

修掉 v6 记录的那处回归（流式下不跟随滚动 / 0.1 复发），实现方式与 §4.6 的原始描述**有两处偏离**：

1. **触发条件改为「内容指纹」**（§4.6(3) 的 `FollowSignal`，落在 `ChatMessagePatch.kt`）：
   `conversationId` + `lastMessageId` + `messageCount` + `lastContentLength`。
   `lastContentLength` 是**替代旧 `lastOrNull()?.id` 判据的关键**——流式原位增长时 id 恒定，
   只有长度会变。有 6 例单测（含反证：去掉内容维度后恰好 3 条变红）。

2. **「是否接近底部」用 `!listState.canScrollForward`，不自己算像素距离。**
   §4.6 原文推演了「`viewportEndOffset` vs 最后一项 bottom」两种口径，并指出
   「两种口径给出相反结论、且都不会报错」。实现时发现**两者都不必算**：
   LazyList 自己已回答「还能不能往前滚」，这正是「是否在底部」的定义，
   且它天然把 `contentPadding`（底部 132dp + IME）算在内——用像素差反而要手工扣掉它。

3. **三方合取才跟随**：指纹变化 **且** 不在用户手势中（`isScrollInProgress`）
   **且** 接近底部。第二项是实测需要补的：仅靠「接近底部」判定，用户**向上拖拽的过程中**
   仍被判定为「在底部」而被拽回（dsh 的反馈环警告在此的具体形态）。

4. **`requestScrollToItem` 刻意未采用**（理由见 §4.6(2) 末尾的实现注记）。

5. 顺带修一处**同类缺陷**：「跳到底部」按钮的 `derivedStateOf` 原先带
   `remember(id, messages.size)` 的 key，流式下 `size` 不变 ⇒ 可见性**不更新**。
   删掉 key 改由快照依赖驱动。

**v6 修订（真机首测后的修复与一处回归）**

1. **🚨 修复渲染层闪烁**（`ChatMarkdown.kt`，真机首测发现）：库默认值组合导致
   **每个 delta 整段清空重绘**，观感是「文字反复闪烁」而非逐字追加。两处默认值：
   - `MarkdownState.kt:195` 的 `if (!newInput.retainState) stateFlow.value = State.Loading(...)`
     ⇒ **输入一变就打回 Loading**，而 `loading` 槽位默认是**空 `Box`**
     ⇒ 内容 → 空白 → 内容 → 空白…（`retainState` 默认 `false`）
   - `MarkdownAnimations.kt:47` 的 `animateTextSize = { animateContentSize() }`
     ⇒ 高频变更下动画互相叠加，且 `animateContentSize` **自身触发重组**
   ⇒ 修法：`retainState = true` + `animations = markdownAnimations(animateTextSize = { this })`
   + `loading` 槽位给一行最小高度（防布局塌陷）。
   ⚠️ **§3.4(c) 早已预警这一条**（列为验收项），但 P4 实现时只关注数据流、**漏了渲染层**。

2. **🚨 修复「pending 时不渲染正文」**（`ChatScreen.kt:1256`）：原判据 `if (message.isPending)`
   直接只显示「正在思考…」、**不渲染 content**。在「整条替换」模型下没问题
   （pending 期间内容本就是空的），但流式下消息**全程 `isPending = true`**
   ⇒ **一个字都看不见**，直到收尾整段跳出——流式白做。
   改为 `if (message.isPending && message.content.isBlank())`。

3. **⚠️ 引入一处回归（已记录，待修）**：§4.6(1) 的 key 修复（工作区已有的
   `lastOrNull()?.id`）在 **P4 落地后失效**——流式改为原位增长、**id 恒定**，
   故该 effect 在流式期间与收尾时都不触发 ⇒ ① 不跟随滚动；②
   **审批卡片出现时不滚动，0.1 复发**。
   ⚠️ 这正是 §4.6 末尾**预先警告过的**组合（「P4 之后 (1) 失效 ⇒ 必须同时上 (3)」），
   本次**未接上 (3)**。⇒ **P6 优先级上调**，修法见 §4.6(3) 的 `FollowSignal`。

**v5 修订（Responses 纳入流式 + 一处实质错误更正）**

1. **🚨 更正一处实质错误**（§5.3 的「U1 更正」）：v1–v4 写的
   「OpenAI Responses 流式**无权威源**」是**错的**。真实情况是
   `platform.openai.com` 的 **403 是反爬**、而**技能包的覆盖范围被当成了世界边界**。
   权威源一直公开：**官方 SDK 从 OpenAPI spec 自动生成的类型定义**。
   ⚠️ 教训：**「我检索不到」≠「不存在」**——检索失败不该写进设计文档、更不该据此砍功能。
2. **D1 已决：Responses 纳入流式**（用户拍板）。新增 `ChatStreamProtocol.OPENAI_RESPONSES`
   与 `decodeResponses`；`buildResponsesPayload` 加 `streaming` 参数；原「退化为非流式」的分支删除。
3. **fixture 分级修订**：OpenAI 系从「形状级」**上调为「字段级」**（字段名与语义有官方依据，
   事件序列仍是构造的）；新增 `responses-text.sse` / `responses-tool-call.sse`。
4. **新增 U8 / U9**：Responses 的**真实事件序列**（U8）、以及
   「是否会出现只发 `output_text.done` 而不发 `delta` 的形态」（U9，
   若有则本实现会丢内容，因为权威值来自累积 delta 而非 `output` 数组重建）。
5. **三条既有设计获得官方原文支撑**：F16（`choices` 恒为空数组 + **所有 chunk 都带 null usage**）、
   F6（`index` 必填）、F17（官方明说 `arguments` 不保证是合法 JSON）。

**v4 修订（P1/P2 实现落地）**

实现期发生的事，回填进设计文档：

1. **U3 关闭、U2 确定**（§5.3）：找到官方流式文档的**原始 SSE 报文**，
   `content_block_start` 的 `text` 是**空串** ⇒ 无需保留初始内容，与 §4.3.2 的实现一致。
   并写明边界：CCB 描述的「SDK 重复发送」是**该 SDK 的行为**，
   而本项目 **不经过 Anthropic SDK**（用 `okhttp-sse` 直连 HTTP），故与 U3 无关。
2. **新增 §5.3 的「fixture 可信度分级」**：Anthropic 三条取自官方报文（**规范级**）、
   OpenAI/DeepSeek 三条无权威源（**形状级**）。
   ⚠️ 混为一谈是这类测试最危险的误用方式——已在 fixture 测试的类注释与 §5.3 两处标注。
3. **新增「P3 的顺序问题」**（§7）：P3 的真机验证**无法先于 P4 完成**，
   因为 `streamReply` 的调用方在 P4 才建立。原顺序（P2→P3→P4）隐含的
   「网络层能独立跑真机」假设不成立。
4. **§7 补状态列**：P0/P1/P2 已做，P3 代码已完成、真机未做。
5. **实现期新增的两处设计缺口**（已修，记录以免重犯）：
   - **`ToolCallCompleted` 事件**（§4.1 原事件模型没有）：§4.7 的保留判据要区分
     「已 closure」与「未 closure」，而这个信息**无法从 `ToolCallDelta` 推断**
     ——分片之间与分片之后，累积串看起来一样。
   - **`sawTerminator`**（§4.2）：F7 的截断判据必须有依据，
     而「是否见过结束标记」只有解码层知道（两种协议的标记不同）。
6. **实现期发现的自身缺陷**（由测试逼出，均已在代码注释中留痕）：
   - `finish_reason` 判据一度写成「空 delta **或** finish_reason」——
     会让**心跳帧**把半截 args 判成已 closure。已收紧（F17 的延伸）。
   - 标签扫描的关闭态名字表一度收窄成只有 think，
     导致 `</tool_calls>` **既不被识别也不被扣留**，原样漏给用户（F12 的变体）。
   - OpenAI 的 `reasoning_content` 与 `reasoning` 两个键一度**拼接**，
     而非流式是 `firstNonBlank` **取一** ⇒ 静默多出内容。已拆成两条独立通道。

**v3 撤回项（重要，避免后人误读）**
- 评审曾把工作区 `ChatScreen.kt` 的一处未提交改动（key 由 `messages.size` 改为
  `(conversationId, lastOrNull()?.id)`，附注释记录「曾试图重写落点、改出两个新 bug 已回退」）
  判定为**对 §4.6(2) 的预先否证**，并列为阻塞项。
  **该判定已撤回**：那次实验的目标是 **0.1（审批卡片被遮挡）**，场景是**旧的 replace 模型 +
  矮于一屏的卡片**——与 §4.6(2) 面向的「流式长回复跟随滚动」不是同一场景，
  且越界 `lastIndex + 1` 被 LazyList 钳制后对矮卡片的效果（贴住视口底部）**恰是该场景想要的**，
  「修掉它反而破坏审批按钮可见性」正是那次回退的原因。二者不可外推。
  ⇒ §4.6(2) 保持为**待验证方案（U7）**，不降级。
  ⚠️ 但该处注释自陈「这个位置只需要修 key，不要动落点」，**§4.6(2) 动的正是这个位置**，
  实现时须先确认 `lastIndex + 1` 的钳制行为对矮卡片的作用是否仍然需要保留。
