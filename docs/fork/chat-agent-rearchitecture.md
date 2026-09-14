# Chat Agent 架构重构设计（基于 CCB / dsh / OpenCode / Pi 四家对照分析）

> 版本：v1.5.4（2026-09-14）
> 状态：**P0 / P1 / P2 已全部实施并基本验证通过**（§4.2.1 / §4.3.3 / §4.3.4）。剩余待真机项见 §4.3 验收表。
> 分支：`feature/chat-agent-rearchitecture`（从 `dev` 出）
> 目录：`docs/fork/`（fork 新增文件，上游无此文件，冲突归属**我方**）
>
> **v1.5 是决策定稿版**：经历一轮 19 问拷问后，推翻了三处结构性假设（详见文末修订史）。
> 正文只讲**当前结论**；「谁推翻了谁」的演进过程收在文末，供将来避免重蹈。
>
> **实施期修正**：
> - v1.5.1：求值口径改为**并集两轮求值**（`CallFunctionModule` 反例）
> - v1.5.2：`load_skill` 必须显式**纳入常驻**（否则被工具路由过滤）
> - v1.5.3：**第二批完成**；技能从「瘦身到 3–4 个」改为**清空**（核对后 70 行正文仅 6 行独有）；
>   `ChatAgentSkillRouter.kt` 847 → 193 行；工具数 72 → 16
> - v1.5.4：**第二批真机验证**（工具数 16 ✅、"关不掉手电筒"已治愈 ✅）；
>   修复 `query_module_schema` 丢失字段语义的缺陷（`inputHints` 等 3 个字段共 67–97 模块受影响）；
>   删除无效的 `operator` 参数
>
> **已真机通过**：§4.2.1（P0 三项）、§4.3.3（第二批第 1/2 项）、§4.3.4 的字段语义修复。
> **待真机**：§4.3 验收表第 3 项（`call_module` 审批）、第 8 项（缓存命中）。

---

## 本地源码参考（读本文前先看这节）

本文引用的四家源码**已克隆到本地**，位于 **`D:/develop/references/`**（在 vFlow 仓库**之外**，不进 git）：

| 本地目录 | 项目 | 本文中的路径前缀 | Commit 锚点 |
|---|---|---|---|
| `D:/develop/references/ccb/` | Claude Code Best（Claude Code 复刻） | `ccb/` | `77a7934e`（2026-08-24） |
| `D:/develop/references/dsh/` | DeepSeek Harness | `dsh/` | `c291e796`（2026-09-10） |
| `D:/develop/references/opencode/` | OpenCode | `opencode/` | `95daf906`（2026-09-11） |
| `D:/develop/references/pi/` | Pi Agent Harness | `pi/` | `71dca871`（2026-09-11） |

> ⚠️ **行号会漂移**。这四个是 `--depth 1` 浅克隆，锚定在上述 commit。若本地已重新克隆而 commit 变了，
> **本文所有行号引用都需要复核**。核对命令：`git -C D:/develop/references/<dir> rev-parse HEAD`。
>
> 完整索引（含关键文件清单、重建脚本、更新策略）见 **`D:/develop/references/README.md`**。

**本文引用格式示例**：`ccb/src/constants/tools.ts:137` 表示
`D:/develop/references/ccb/src/constants/tools.ts` 第 137 行。

> 前置阅读：
> - [`surveys/ai-system-overview.md`](surveys/ai-system-overview.md) —— 现状地图
> - [`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md) —— 头部 Agent 外部对照
> - [`chat-agent-enhancement-plan.md`](chat-agent-enhancement-plan.md) —— 上一轮的四点增量改造（本文是其**上位替代**）

---

## 0. 为什么要重写而不是修补

现有的 `chat-agent-enhancement-plan.md` 是**增量修补**思路：加缓存、改 catalog、补工具。那四点方向都不错，但**它们都建立在现有骨架之上**，而现有骨架有两个结构性问题，补不出来：

1. **"技能"被当成了万能容器**——它同时承担「工具可见性分组」和「任务指令载体」两个不相干的职责（`ai-system-overview.md` §2.3/§2.4.7）。
2. **该有状态的地方全是无状态函数**——技能选择、参数 schema、catalog 生成，全部是"每轮拿最新输入现算"，没有任何会话级记忆（`ai-system-overview.md` §2.3.3、§2.4.8）。

---

## 1. 现状：三个可证伪的病症

全部有代码位置与真机证据，明细见 `ai-system-overview.md`。这里只列结论。

| # | 病症 | 直接成因 | 证据 |
|---|---|---|---|
| **A** | 技能会凭空消失：刚建完工作流，下一轮追问细节，模块清单就没了 | `selectSkills` 每轮只读**最新一条 USER 消息**（`ChatAgentSkillRouter.kt:35-37`），跨轮延续仅认硬编码短语表 `CONTINUATION_SIGNALS`（`:749`） | 一次 24 条消息的真实会话：第 10 轮命中 `saved_workflow_creation`，第 14 轮起丢失，第 22 轮用户手动复述才回来 |
| **B** | 模块字段残缺：`If` 的 `value1`/`value2` 不在清单里，AI 填 `input2` 被静默丢弃，编辑器显示为空 | catalog 生成（`ChatAgentToolRegistry.kt:734`）与执行校验（`ChatAgentModuleExecutor.kt:1266`）**都用默认值 step** 调 `getDynamicInputs`；键不匹配则 `?: return@forEach`（`:1270`）丢弃 | 8 个条件分支式模块受影响；反直觉的是远程 API（`ModuleHandler.kt:172`）用静态全量 `getInputs()`，**字段反而更完整** |
| **C** | 手电筒成了"技能" | 没有"模型主动发现工具"的通道 → 只能由代码按关键词决定下发哪些工具 → 需要分组单位 → 复用了"技能"这个词 | `flashlight_control`（`ChatAgentSkillRouter.kt:352-364`）的全部内容是 1 个 `moduleId` + 2 行防呆指令 |

**三个病症同一个根**：**vFlow 没有"模型主动索取"的通道。**

- 因为不能索取 → 只能预置 → catalog 全量铺开（还字段残缺）
- 因为不能索取 → 只能按关键词猜 → 猜错就丢
- 因为要分组 → 借用了"技能"这个词 → 概念污染

### 1.1 补充：`selectSkills` 实际在过滤什么（v1.5 实测）

这一点是理解全篇的钥匙，v1.4 漏了。

`ALWAYS_EXPOSED_NATIVE_HELPERS`（`ChatAgentSkillRouter.kt:751-762`）列了 **11 个** helper——而 `ChatAgentNativeHelperId` 枚举（`ChatAgentNativeTooling.kt:41-53`）**总共只有 11 个**。

**即：11 个 helper 全部无条件常驻，`selectSkills` 对 helper 侧恒等于"不过滤"。**

它过滤的只有模块工具。技能块里 `moduleIds` 去重后覆盖 **52 个**模块 ID。

> **结论：`selectSkills` 的全部作用 = 从 52 个模块工具里挑几个发出去。**
> 它保护的唯一东西，是 context 不被工具表撑爆。

这个事实决定了它何时该死（见 §4 第二批）。

---

## 2. 四家参考实现的对照

**四家在"技能怎么进上下文、工具怎么让模型看见"上走了不同的路。**

### 2.0 CCB vs dsh 逐项对照

| | **CCB**（Claude Code 复刻） | **dsh** |
|---|---|---|
| **核心机制** | 模型主动搜工具 | scope 分层 + allow/deny 过滤 |
| **常驻工具** | 28 个 `CORE_TOOLS`（`ccb/src/constants/tools.ts:137`），**Skill 工具在列** | 按 scope 继承，无固定白名单概念 |
| **其余工具** | 全部 `defer_loading: true`，不进 `tools` 数组 | 由 scope chain 决定，`ToolRestriction{allow, deny}` 求交集（`dsh/docs/subsystems/tools.md:153-166`） |
| **模型怎么找到** | 两步：`SearchExtraTools(query)` → `ExecuteExtraTool(tool_name, params)` | **不搜**——该 scope 能看见的就在 `tools` 里 |
| **搜索实现** | **关键词打分**（`ccb/packages/builtin-tools/src/tools/SearchExtraToolsTool/SearchExtraToolsTool.ts`：名字命中 +12/+10、`searchHint` +4、描述 +2），非语义检索 | 无 |
| **阈值** | 延迟工具描述超**上下文 10%** 才启用（`ccb/src/utils/searchExtraTools.ts:44`）；默认模式是 `tst`（**总是延迟非核心工具**） | 无阈值概念 |
| **技能正文注入** | `createUserMessage({content, isMeta: true})`，一条独立 **user message** | `skill` 工具返回 `<skill_content>` + `<skill_resources>` + `<skill_instructions>`（`dsh/docs/subsystems/skills.md:227`） |
| **技能清单** | `skill_listing` 附件，预算 = **上下文 1%**，单条上限 **1536 字符**，溢出按调用频率降级（`ccb/packages/builtin-tools/src/tools/SkillTool/prompt.ts:21-30`） | durable user-role `<system-reminder>`，描述上限 **500** 字符，**digest 比对 + 变更时追加完整替换**（`dsh/packages/skill/tool-skill/src/index.ts:213-277`） |
| **状态表** | `invokedSkills: Map`（`ccb/src/bootstrap/state.ts:178`），跨轮重注入，压缩时保留 | 技能目录是**会话历史事件**，不是独立状态表 |
| **压缩** | 摘要 + 文件重挂，注意 tool-call/result 配对 | **裁剪工具输出 → 重测 token → 再决定摘要**（`dsh/docs/subsystems/compaction.md:60`） |

### 2.1 四家横向对照（含 OpenCode / Pi）

**四家的技能注入位置 —— 没有唯一解，但有一条共同底线：**

| | 技能**清单** | 技能**正文** | 有 `isMeta` 类标记吗 |
|---|---|---|---|
| **CCB** | user message（`skill_listing` 附件 + `<system-reminder>`） | **user message**（`createUserMessage({isMeta:true})`） | ✅ 有 |
| **dsh** | user message（durable `<system-reminder>`，digest 比对） | **tool result**（模型调 `skill`）/ **user message**（用户 `/name`） | 部分（dsh 靠 `source` 字段） |
| **OpenCode** | **system prompt**（`<available_skills>` XML，`opencode/packages/opencode/src/session/system.ts:107-119`） | **tool result**（`<skill_content>`，`opencode/packages/opencode/src/tool/skill.ts:45-66`） | ❌ 无 |
| **Pi** | **system prompt**（`<available_skills>` XML，`pi/packages/coding-agent/src/core/system-prompt.ts:160-163`） | **user message**（`<skill name=... location=...>`，`pi/packages/agent/src/harness/skills.ts:39-42`） | ❌ 无（与真实用户输入无法区分） |

> **共同底线：四家的技能正文全部落在对话层**（tool result 或 user message），**没有一家放进每轮重算的 system prompt 段落**。vFlow 是唯一把正文写进 system prompt 的（`ChatCompletionClient.kt:242`/`:314`）——这正是病症 A 的根源。

**清单的位置是有分歧的**：CCB / dsh 放**消息**（进历史、可留存），OpenCode / Pi 放**system prompt**（每轮重建）。这一点上 vFlow 与 OpenCode / Pi 同侧——但后两家只把**轻量清单**放那里，正文不放。

**工具暴露策略 —— 关键要区分「内建工具」与「扩展工具」：**

| | 内建工具 | 内建策略 | **扩展工具（MCP 等）** | **扩展策略** |
|---|---|---|---|---|
| **CCB** | 28 个 `CORE_TOOLS` | 全量常驻 | MCP（可成百上千） | **按需搜索**（`SearchExtraTools` → `ExecuteExtraTool`） |
| **dsh** | 无固定白名单 | scope 继承 | 插件注册 | **scope 过滤**（`ToolRestriction{allow,deny}`） |
| **OpenCode** | ~15 个 | 全量下发 | **MCP（用户可配任意多）** | **默认全量下发**（无搜索机制）；唯一 defer 是实验开关 `OPENCODE_EXPERIMENTAL_CODE_MODE`（默认关） |
| **Pi** | 8 个 | 全量下发 | **不支持 MCP**（README 明写 "**No MCP.**"） | 不适用 |

**证据**：

| 结论 | 证据 |
|---|---|
| CCB 的搜索专为 MCP 而做 | `ccb/packages/builtin-tools/src/tools/SearchExtraToolsTool/prompt.ts`：*"This tool is for discovering additional capabilities like **MCP tools**, cron scheduling, worktree management..."* |
| OpenCode 对 MCP **默认全量** | `opencode/packages/opencode/src/session/tools.ts:389-390`：`if (flags.experimentalCodeMode) return tools` —— **仅当实验开关开启才跳过**；默认走下面的 `for (const [key, entry] of Object.entries(yield* mcp.tools()))` **逐个转成 tool** |
| OpenCode 的 `McpCatalog` **不是截断层** | `opencode/packages/opencode/src/mcp/catalog.ts` 的 `paginate`（`MAX_LIST_PAGES = 1_000`）只是**分页拉取**，最终仍全量收集 |
| Pi 不支持 MCP | `pi/packages/coding-agent/README.md:499`：*"**No MCP.** Build CLI tools with READMEs (see Skills), or build an extension that adds MCP support."* |

**Pi 的 `addedToolNames` 机制**（`pi/packages/ai/src/utils/deferred-tools.ts` 的 `splitDeferredTools`）**不是为 MCP 设计的**——它是给「扩展在会话中途注册的工具」用的：扩展工具执行后，对比执行前后活跃工具集，把**新激活**的工具名挂在该 tool result 上，再由各 provider 落地（Anthropic 用 `defer_loading: true` + `tool_reference`；OpenAI Responses 用 `additional_tools`；Kimi 用带 `tools` 字段的 system 消息）。**注意：模型不能主动发起搜索**，这是编排层自动检测。且用过就永久激活（`pi/packages/agent/src/harness/runtime/drive/tool-placement.ts:200-213`）。

**vFlow 的对应关系**：59 个模块工具，性质上**对应的是「扩展工具」列**——因为：

1. 它们由 `ModuleRegistry` **自动生成**，随模块数量增长（不是固定表）
2. 数量（59）远超 OpenCode 内建（~15）、CCB `CORE_TOOLS`（28）
3. 它们**不在 agent 核心能力之内**（不像 `read`/`edit` 是感官手脚），而是领域能力

**所以「OpenCode/Pi 用 15/8 个工具全量下发」不能类比到 vFlow 的 59 个。** 正确的参照是 CCB 对 MCP 的处理——**做按需机制**。

**技能存续的三种手法：**

| | 手法 |
|---|---|
| CCB | `invokedSkills` Map，每轮重注入 |
| dsh | 目录作为会话历史事件 + digest 比对 |
| **OpenCode** | **无状态表**，靠 `PRUNE_PROTECTED_TOOLS = ["skill"]`（`opencode/packages/opencode/src/session/compaction.ts:31`）在修剪时跳过 |
| **Pi** | **无状态表**，正文作为普通 user 消息永久留在历史 |

> ⚠️ **OpenCode 手法的缺口**：prune 保护技能，但 **overflow 触发的全量压缩不特殊对待**——技能正文会与普通 tool 输出一样被截断到 `TOOL_OUTPUT_MAX_CHARS = 2_000` 或清空为 `[Old tool result content cleared]`（`opencode/packages/opencode/src/session/compaction.ts:55,79-80`）。即"日常修剪保得住，真撞上限保不住"。

### 2.2 该抄哪一家

| 抄谁 | 抄什么 | 对症 |
|---|---|---|
| **dsh** | **状态化**——用过的技能与目录当前版本都当会话状态，不凭空消失 | 病症 A |
| **CCB** | **分层 + 按需机制**——清单只放路由信息、详情按需取；扩展工具走两步式 | 病症 B、C |

**为什么 CCB 的路线是对的**：vFlow 的 59 个模块工具**性质等同 CCB 面对的 MCP 工具**——由系统自动生成、数量随模块增长、超出 agent 核心能力范畴。而 **CCB 正是为这类工具做了 `SearchExtraTools`**（其 prompt 原文点名 MCP）。**参照对象应该是 CCB 对扩展工具的处理。**

**关于"工具表压小"**：方向对（去重能减到 40 出头），但**它不足以解决问题**——即使压到 40，仍超 CCB 提示的 30–50 阈值，且**模块数还会继续增长**。所以**按需机制（P1-1/P1-1b/P1-1c）是必需项，不是可选优化**。

**不抄的部分**：

- dsh 的 scope/Cordis 分层是为**多租户插件系统**设计的，vFlow 是单用户单进程 App，引入 scope chain 是过度设计。
- CCB 的 28 个 `CORE_TOOLS`、OpenCode 的 `read/glob/grep/edit`、Pi 的 `bash/read/edit` 都是**代码 agent 形态**，与 vFlow 的手机自动化工具面无关。**可以借鉴的是它们的"机制"，不是它们的"工具清单"。**
- **Pi 整体参考价值下调**：它明确不支持 MCP，因此从未面对"工具数量爆炸"问题。**它的 `addedToolNames` 是给会话中途注册的扩展工具用的，不是给批量扩展用的**。

**仍然值得抄的两处**：

1. **Pi 的 prompt 缓存三处落点**：`cache_control: {type:"ephemeral"}` 打在 system prompt 文本块、**tools 数组最后一个工具**、最后一条 user 消息的最后一个 block（`pi/packages/ai/src/api/anthropic-messages.ts:1071/1430-1459/1373-1397`）；且**压缩/摘要请求本身显式 `cacheRetention: "none"`**（`pi/packages/coding-agent/src/core/compaction/compaction.ts:591`）。
2. **Pi 的 `splitDeferredTools` 有一个思路可借**：它**从对话历史反推**哪些工具该延迟——扫 `assistant` 消息里的 `toolCall` 收集"已用过的名字"，再扫 `toolResult` 的 `addedToolNames`（`pi/packages/ai/src/utils/deferred-tools.ts:14-45`）。**"历史即状态"**，不维护额外状态表。这与 §3.3 同源。

---

## 3. 目标架构

### 3.1 核心变更：把"技能"拆成三个东西

| 现在（一个概念干三件事） | 目标（三个概念各司其职） |
|---|---|
| **技能** = 工具可见性分组 + 任务指令 + 关键词触发 | **① 工具可见性**——由按需发现机制接管，不再需要手工分组 |
| | **② 任务指令**——保留，但只在**真正有指令**的场景存在（设计时估约 3–4 个，不是 14 个；<br>**实施后实测为 0**——见 §4.3.1） |
| | **③ 触发**——由模型判断，不由关键词表猜测 |

**③ 的载体选择 —— 四家的分歧与共同底线**（详见 §2.1）：

- **共同底线**：正文**必须在对话层**（tool result 或 user message），**不能进每轮重算的 system prompt 段**。四家无一例外。
- **分歧**：清单放哪。CCB / dsh 放**消息**（进历史、可留存）；OpenCode / Pi 放 **system prompt**（每轮重建但只是轻量清单）。

**vFlow 的目标态**：

| | 载体 | 理由 |
|---|---|---|
| 正文 | **tool result**（新增 `load_skill(name)` 工具） | 与 OpenCode 同构；实现最简单，且天然进历史、可留存；无需 `isMeta` 之类的新标记机制 |
| 清单 | **system prompt**（`<available_skills>` XML） | 与 OpenCode / Pi 同构；清单很轻，重建成本可忽略；且解决"技能消失"靠的是**正文在历史里**，而非清单。<br>**实施后技能清空，此段不再输出**（见 §4.3.1） |

> **为什么正文选 tool result 而不是 user message**：CCB / dsh 用 user message 是因为它们需要注入"不是用户说的"这类**指令性内容**，故要 `isMeta` 标记。vFlow 的技能是**按需加载的参考资料**（与 OpenCode 的定位一致），走 tool result 更自然，也避免了"user 消息里混入非用户内容"的语义污染。

> **副作用提醒**：清单放 system prompt 意味着**每轮重建**，对 prompt 缓存不友好——这一点 vFlow 与 OpenCode / Pi 同病。优化方式见 P2-2。

### 3.2 三层信息结构

```
┌─ 第一层：常驻 ────────────────────────────────────┐
│ 11 个原生 helper（现状已如此，保持不变）            │
│ + 2 个工作流工具（save / run_temporary）           │
│ + 技能清单（仅 name+description，Ø275 token）      │
│ + 3 个按需入口：                                    │
│     query_module_schema / call_module / load_skill │
└───────────────────────────────────────────────┘
              ↓ 模型主动索取
┌─ 第二层：按需 ────────────────────────────────────┐
│ query_module_schema(moduleId, operator?)           │
│   → 完整输入定义（用 getInputs() 静态全量）        │
│   → 带 scopes[] 与 callable 标记（见 §3.4）        │
│ load_skill(name) → 完整 instructions               │
└───────────────────────────────────────────────┘
              ↓
┌─ 第三层：执行 ────────────────────────────────────┐
│ call_module(moduleId, params)                      │
│   → 复用现有 ChatAgentModuleExecutor 执行链        │
│   → 现有执行层（189 模块 / 无障碍 / OCR / Shizuku）│
│     **完全不动**                                    │
└───────────────────────────────────────────────┘
```

**为什么用 `call_module` 而不是动态注入**（对比 CCB 的 `ExecuteExtraTool` 与 Pi 的 `addedToolNames`）：

| 方案 | 做法 | 对 vFlow 的适配 |
|---|---|---|
| CCB 两步式 | `search_tools` 只给**名字** → `execute_tool` 执行 | ⚠️ vFlow 模块参数复杂（`If` 有 operator/value1/value2 三维），**光有名字不够，必须先拿 schema** |
| Pi 动态注入 | 编排层检测"新激活"并注入下一轮 `tools` | ❌ 需要编排层判断时机，vFlow 没有这套逻辑 |
| **vFlow：`call_module`** | **固定工具，永远在**；查询拿 schema → 用 `call_module` 执行 | ✅ 实现最简单，无编排层负担 |

**所以 vFlow 的两步是「查 schema → 调用」，而不是 CCB 的「搜名字 → 调用」。** 这个差异源于参数复杂度。

### 3.3 会话状态：**不加字段，历史即状态**

**结论**：**不在 `ChatConversation` 加 `activatedSkillIds` 之类的字段。**

**理由**：技能正文经 `load_skill` 进 **tool result** 后，它作为消息**永久留在对话历史**里。而 `buildChatCompletionHistoryMessages` 本来就是**全量重发历史**（`ChatCompletionClient.kt:249`）。**技能不会消失，因为它已经是历史的一部分。**

**这正是 dsh 说的 "session history, not World State"**，也与 Pi 的 `splitDeferredTools` 同源。

> ⚠️ **关键限定（v1.5 补）**：以上**只对"正文"成立**。**工具可见性（`availableTools`）仍然每轮重算**——
> `selectSkills` 每轮只读最新一条 USER 消息（`:35-37`），它决定哪些**模块工具**进入 `tools` 数组。
>
> 这意味着 **P0-2 单独落地后，"工具会消失"依然存在**：
> ```
> 第 1 轮「开手电筒」 → 关键词命中 → vflow_device_flashlight 发出去 ✅
> 第 2 轮「把它关了」 → "手电筒"不出现，且不在 CONTINUATION_SIGNALS
>                     → 技能为 0 → 工具表只剩 11 个 helper
>                     → 模型手里没有手电筒工具，关不掉 ❌
> ```
> 更微妙的是：工作流工具常驻且 catalog 里有手电筒，模型可能**退化去建临时工作流来关灯**——正是 system prompt 要防的事。
>
> **这个断点的唯一解是 P1-1c**（撤走 59 个模块工具，`selectSkills` 随之失去全部语义 → 删除）。
> 故 **P0-2 的验收范围必须明确排除"工具可见性"**，见 §4 第一批验收。

### 3.4 查询域 ≠ 调用域（设计 B）

`query_module_schema` 和 `call_module` 的**可访问集合不同**。

**三个集合的实际边界**（`ChatAgentToolRegistry.kt:89-95`）：

| 集合 | 数量 | 判定方式 | 用途 |
|---|---|---|---|
| `DIRECT_TOOL` | **59** | 白名单 | 可作为独立工具直接调用 |
| `TEMPORARY_WORKFLOW` | ~100 | 白名单 | 可作临时工作流步骤 |
| `SAVED_WORKFLOW` | **~184** | **黑名单**（默认放行，仅排除 template / snippet / 3 个 AI 模块） | 可写入保存的工作流 |

**关系**：

```
        query_module_schema 的域（184，能进保存工作流）
    ┌────────────────────────────────────────────────┐
    │                                                │
    │    ┌──────────────────────────┐                │
    │    │ 59 个 DIRECT_TOOL        │ ← call_module 的域
    │    │（也能进工作流）           │                │
    │    └──────────────────────────┘                │
    │      另外 125 个：仅工作流步骤                    │
    └────────────────────────────────────────────────┘
```

**关键**：**写工作流能用的模块（184）远多于能直接调的（59）。** 所以两个工具**不能共用一个域**。

**采纳方案 B（查询不分区 + 结果带标记）**：

```
query_module_schema(moduleId, operator?) → {
    moduleId, name, description,
    inputs: [...完整 InputDefinition...],      // 用 getInputs() 静态全量（见 P0-1）
    outputs: [...],
    scopes: ["saved_workflow", "temporary_workflow"],   // 能进哪些工作流
    callable: true | false,                              // 能否 call_module 直调
    riskLevel: "read_only" | "low" | "standard" | "high"
}
```

**被否决的方案**：

| 方案 | 做法 | 否决理由 |
|---|---|---|
| A. 加 `for` 参数 | `query_module_schema(id, operator?, for="workflow"\|"direct")` | ❌ 模型得自己判断该填哪个——**它不知道目标模块属于哪个域** |
| C. 拆两个查询工具 | `query_module_schema` / `query_callable_module` | ❌ 语义重叠，模型同样要判断"该查哪个" |

**四个设计要点**：

1. **查询域 = 184**（能进保存工作流的全部），覆盖"写工作流时查任意模块"的需求。
2. **`callable: false` ≠ 不可用**——它只表示"不能直接调"，**写进工作流完全合法**。例如 `vflow.logic.if.start` 是 `callable: false`，但写工作流必需。**这一点必须在工具描述里向模型讲清**，否则它会误以为该模块不可用。
3. **`call_module` 独立校验**——不信任模型从查询结果里读到的 `callable`（它可能凭记忆直接调）。校验不通过时**明确报错**：`"vflow.logic.if.start 只能用于工作流，不能直接调用"`，而不是静默失败。
4. **查询工具**本身**不在查询域内**——它们的 moduleId 是虚拟的（如 `vflow.agent.save_workflow`），不在 `ModuleRegistry` 里。**要在工具描述里说明**，避免模型去查它们然后困惑。

**对于 `scopes` 的处理**：临时工作流（~100）是保存工作流（~184）的子集。**用更大的那个（184）作为查询域，靠 `scopes[]` 让模型看到差异**——比让模型选 `for` 参数更稳。

### 3.5 helper 与模块工具的关系（澄清）

| 层面 | 情况 |
|---|---|
| **集合** | **完全独立**——helper 的 moduleId（`vflow.agent.*`）在 `ModuleRegistry` 里**零命中**，不是模块 |
| **能否进工作流** | helper **不能**；模块能 |
| **执行路径** | helper 直调 Android API；模块走 `ActionModule.execute()` |
| **设计目标** | helper 服务**对话中的连续操作**（有 observation epoch、swipe direction 等会话状态，返回 ScreenElement 句柄）；模块服务**工作流的可序列化复用** |
| **功能** | **部分相似**（都能点击/输入/启动 App）——**但这不是冗余**，是同一能力在两种场景下的两种实现 |

**类比**：手动驾驶与自动驾驶——都能到目的地，但不是重复。

**真正的缺口不是"重复"，而是"模型不知道分工边界"**：

`vflow_agent_tap_screen`（helper）和 `vflow.device.click`（模块）在 schema 层面长得一样，而 system prompt 只有一句"优先用 helper"——那是**行为指导**，没解释**为什么**、更没说**写工作流时必须用模块**。

**处理方式（不是删减，是补边界说明）**：

- helper 的 description 里说明"**仅用于当前对话操作**"
- `query_module_schema` 返回模块时，`callable` 标记天然区分了"能直调"与"仅工作流"
- 在 `load_skill` 的技能正文里说清"写工作流必须用模块，helper 用不上"

---

## 4. 执行契约

> **本节起为执行契约**。改造点按**两批**组织，每批独立可交付、可回滚。
> 批次边界 = **提交边界，不是发布边界**——第一批 commit + `test` 全绿后直接接第二批，**不设暂停点、不加 feature flag**。

### 4.0 工具数量总览

**改造前 72 个 → 改造后 16 个。**

| 类别 | 改造前 | 改造后 | 说明 |
|---|---|---|---|
| 原生 helper | 11 | **11** | 不变（与模块工具集合独立，见 §3.5） |
| 工作流工具 | 2 | **2** | `save_workflow` / `run_temporary_workflow` |
| 模块工具（`DIRECT_TOOL`） | **59** | **0** | **全部撤出 `tools` 数组**，改由 `query` + `call_module` 按需 |
| `query_module_schema` | — | **1** | 新增（P1-1） |
| `call_module` | — | **1** | 新增（P1-1b） |
| `load_skill` | — | **1** | 新增（P0-2） |
| `search_tools` | — | （0） | 默认不做（P1-3C），待工具表再增长时启用 |
| **合计** | **72** | **16** | 落在 OpenCode 内建量级（~15）内 |

**收益**：

1. **工具数进入被验证过的区间**（16 ≈ OpenCode 的 15）
2. **token 大降**——两个工作流工具的 description 从数万字符降到几百字符（清单只留 moduleId + 中文名）
3. **消除"技能=可见性开关"**——不再需要手写 `moduleIds` 白名单

**代价与前置条件**：

| 代价 | 说明 | 缓解 |
|---|---|---|
| 模型多一次调用 | 简单模块（如手电筒）也要先查 schema | 接受；统一路径比"部分直连"更易学 |
| **schema 不参与 API 校验** | `call_module` 的 `params` 只能是通用 object，provider 的 `strict` 失效 | **必须由 `call_module` 自行校验** |
| **`call_module` 是新攻击面** | "万能入口"，任何模块都能调 | **审批逻辑必须覆盖它**，不能绕过现有风险评估 |
| **静默失败会更危险** | 无 schema 约束，参数错了更难发现 | **P0-1 是 `call_module` 的硬前置** |

> ⚠️ **依赖关系**：**P0-1 必须先于 `call_module` 落地**。否则 `call_module` 会把参数错误静默吞掉（`ChatAgentModuleExecutor.kt:1270` 的 `?: return@forEach`），比现状更糟。

---

### 4.1 通用工程约定

| 项 | 定案 |
|---|---|
| **新包** | **P0 阶段不建新包**。`ui/chat/agent/` 推迟到 **P1-1c** 开工时创建 |
| **接管范围** | `ChatAgentSkillRouter` + `ChatAgentToolRegistry` 整体搬入新包；`ChatAgentModuleExecutor` / `ChatCompletionClient` **留原文件最小改动** |
| **长期分叉** | **认**。上游若继续迭代 Chat Agent，fork 的实现不跟随，冲突手工搬运或放弃上游实现 |
| **回归网** | `ChatAgentToolingTest.kt`（**707 行**）是唯一可在 `./gradlew test` 下运行的回归网 |
| **fail 归因** | 因"错误可见化"失败 → **改用例**；因"逻辑退化"失败 → **改代码**；归因不明 → **停下来问** |
| **新增单测** | **是交付物**，不是可选项 |

> ⚠️ **为什么 P0 不建新包**：P0 三项全部加起来只动 **4 个文件、约 5 个函数体**，没有一处需要"重写"。
> 真正的重写（撤 59 个工具、按需机制接管）在 P1-1c。现在建新包 = **先付长期分叉的账，却还什么都没拿到**。
> 另一个理由：`ChatAgentToolDefinition` 被 **7 个生产文件 + 2 个测试**引用，搬进新包会让这 9 个文件全要改 import——纯噪音 diff。

> ⚠️ **`ChatBenchmarkRunner` 不是回归网**：它需要 provider 与 apiKey（`ChatBenchmarkRunner.kt:201` 检查 `apiKey.isNotBlank()`），`./gradlew test` **跑不了**，只能真机跑。

---

### 4.2 第一批：P0 三项

#### P0-3：工具输出截断改为「按输出性质声明」★ 先行

> **P0-2 / P1-1 的共同前置。** 两项都需要新增「按需加载」型工具，而这两类工具的输出都不该被截断。

**问题**：`ChatToolResultInputFormatter.format()`（`ChatCompletionClient.kt:40`）对**所有**工具输出统一施加 `CHAT_MAX_TOOL_RESULT_INPUT_CHARS = 1_600`（`:26`）限制，**没有区分输出的性质**。

该限制的来历：与 `ChatAgentNativeTooling.kt` 同在提交 `13c38fae feat(ChatAgent): 提供完整的屏幕操作能力` 引入，**是为 `observe_ui` 那类「机器 dump 无障碍节点树」的输出兜底的**——`ai-system-overview.md` §2.10.3 记了真实会话里单条 assistant 消息达 40K token。它的适用面是「机器生成、结构重复、长尾无信息量」的输出，**不是通用约束**。

**三类输出的应然归属**：

| 输出性质 | 例子 | 该不该限 |
|---|---|---|
| 机器 dump，长尾冗余 | `observe_ui` 节点树、OCR 全文、`read_page_content` | ✅ 限 |
| 人写的、按需加载的知识 | `load_skill` 正文 | ❌ **不限** |
| 结构化的、精确的元数据 | `query_module_schema` 的字段定义 | ❌ **不限**（截断的可能是字段名，即病症 B 复发） |

**四家参考的实测 —— 正文层零限制**：

| | 正文加载方式 | 正文限长 | 限长的位置 |
|---|---|---|---|
| CCB | `createUserMessage({content, isMeta:true})` | ❌ 无 | 清单：上下文 1%，单条 1536 字符 |
| dsh | `renderSkillContent()` 原样拼入 `skill.content` | ❌ 无 | 清单 description：500 字符（`tool-skill/src/index.ts:391`） |
| OpenCode | `<skill_content>` 包 `info.content.trim()` | ❌ 无 | 文件清单 `limit: 10` |
| Pi | `formatSkillInvocation()` 原样拼 `skill.content` | ❌ 无 | frontmatter：name 64 / description 1024 |

**四家的限长一律打在清单层**：清单每轮重发所以要压；正文按需加载、只取一次、之后留在历史里——**加载它就是为了拿到全部内容，截断等于让这次调用白做**。

> vFlow 现状等于**用 CCB 的清单预算（1536）去卡自己的正文**。正确做法是**正文层不设限**，而不是调大数字。

**方案（定案）**：

```
ChatAgentToolDefinition.truncatable: Boolean = true      // 显式声明，默认放行截断
    ↓ 注册时声明
load_skill               → truncatable = false
query_module_schema      → truncatable = false
其余（含 observe_ui 等）  → 默认 true
```

**不按 backend 推导**的理由：今天 `NATIVE_HELPER` 恰好都该截断，但这是巧合——显式声明 + 默认 `true` 保证新工具默认安全，只有明确声明的才豁免。

**实现要点（v1.5 简化）**：`ChatToolResult` **自带 `name` 字段**（`ChatModels.kt:177-185`），`format()` 可直接读 `toolResult.name` 查表——**不需要扩充签名、不需要重放 assistant 消息**。

**改动位置**：`ChatCompletionClient.kt` 的三条 TOOL 路径**无一例外**走 `format()`（`:302` OpenAI / `:347` OpenAI Responses / `:838` Anthropic），需全部覆盖。

**截断告知**：本次**不做**（推迟到 P2-1）。P2-1 做时必须补——否则就是病症 A 换个地方复发（模型拿到半份说明书却以为拿到全份）。

**正文限长**：**不限**。实现时打点记录单个技能正文长度，作为将来加护栏的依据（当前最大 `screen_observation` 为 1,417 字符）。

---

#### P0-1：模块 schema 非退化求值 ★最关键

**问题**：`getDynamicInputs(默认值 step)` 按算子裁剪字段，导致参数被静默丢弃（病症 B）。

**根因**：**执行侧把「UI 渲染指引」当成了「参数合法性白名单」。** 动态定义本来是为「用户下一步该填哪格」服务的，它天然是子集；拿它当校验依据，模型填的合法参数就全被裁掉了。

> **v1.5 精确化**：`ChatAgentModuleExecutor.kt:1263-1265` 的 baseStep **不是空白 step**，是
> `createSteps().firstOrNull()?.parameters`——即**模块默认值已预填**。`IfModule` 的 `operator` 默认 `"exists"`，
> 落进 `OPERATORS_REQUIRING_ONE_INPUT` 之外 → **`value1`/`value2` 都不加** → 全被丢。
> 痛点在于 `IfModule.kt:165-208` 的**分支裁剪**，不是"没输入"。

**方案（定案：并集两轮求值）**：

```
一次性求值 = getInputs() ∪ getDynamicInputs(step)      ← 「只多不少」
    ↓
第一轮：step0 = 默认值 step → d0
预合并：模型给的、且 d0 认得的键（如 workflow_id）放进 step
第二轮：step1 = 合并后的 step → d1
    ↓
最终定义表 = d0 ∪ d1（同 id 时后一轮覆盖前一轮，保留算子相关的类型改写）
    ↓
判定：arguments 的键 ∈ 最终定义表 → 收下并 coerce；否则 → 显式报错
```

**三个要点**：

1. **为什么用并集而不是纯静态全集**：`CallFunctionModule` 的 `getInputs()` 只声明 `workflow_id`，
   各函数参数由 `getDynamicInputs` 依据签名**凭空生成**。纯静态全集会让它的函数参数全丢。
   并集的性质是**只会「多收」不会「少收」**，不可能丢掉纯静态方案能通过的参数。
2. **为什么需要两轮**：`CallFunctionModule` 的参数依赖 `step` 里已有的 `workflow_id`，
   一次求值看不到。
3. **为什么不是纯 `getInputs()`**：`IfModule.kt:191-196` 当 operator 是数值比较时，把 `value1`
   的 `staticType` 从 `ANY` 改写成 `NUMBER`。丢掉这个，数值比较会按字符串比较。
   并集把动态结果排在后面，同 id 时它覆盖静态定义，故类型改写得以保留。

**兜底**：`getDynamicInputs()` 抛异常 → 该轮回退静态全集，绝不因动态求值出错而丢参数。

**实现落点**：抽取为顶层函数 `resolveModuleInputDefinitions(module, step)`，
**catalog 生成（`ChatAgentToolRegistry`）与执行校验（`ChatAgentModuleExecutor`）共用**，
保证「模型看得见的字段」与「执行时收下的字段」口径一致。

**`getDynamicInputs` 本身零改动**——它是 `ActionModule.kt:78` 的核心接口方法，编辑器 UI 依赖它。
**不新增重载、不包装、不往 `ActionModule` 上加方法**（那是往上游接口上加东西，正是 FORK.md 要防的）。

**改动位置（两个调用点）**：

| 位置 | 现在 | 改为 |
|---|---|---|
| `ChatAgentModuleExecutor.kt:1263-1271`（执行校验） | `inputs = getDynamicInputs(baseStep)` 当作白名单 | 白名单换 `getInputs()`；`getDynamicInputs` 降级为**类型精度来源**，只读一次 |
| `ChatAgentToolRegistry.kt:734`（catalog 生成） | 同上 | 直接改用 `getInputs()` 静态全集 |

**执行路径优先级最高**——它决定参数是否被 `?: return@forEach`（`:1270`）静默吞掉。

**同时**：把静默丢弃改成**显式报错回传**。

**改动量**：小（约 9 行，两处函数体内部）。**性质是接线级改动，不是重写**（与 §4.1 的"留原文件最小改动"自洽）。

---

#### P0-2：技能正文改走 tool result ★治病症 A（部分）

**问题**：技能**正文**随话题切换掉出上下文——**根因是正文写进了每轮重算的 system prompt**（`ChatCompletionClient.kt:242`/`:314`）。

> ⚠️ **范围限定**：P0-2 **只解决正文**。"工具会消失"（§3.3 限定框）**不在本项范围**，由 P1-1c 解决。

**方案**：

| # | 改动 | 说明 |
|---|---|---|
| 1 | **技能正文不再拼进 system prompt** | `buildSystemPrompt` 去掉 `"Active skills:"` 段（`ChatAgentSkillRouter.kt:82-116` 内） |
| 2 | **新增 `load_skill(name)` 工具** | 正文作为其 tool result 返回；进历史后永久留存 |
| 3 | **清单常驻 system prompt** | `<available_skills>` XML，仅 `name` + `description`（~275 token） |
| 4 | **技能集合保持 14 个不变** | 瘦身留给 P1-3B，与 P0-2 **解耦** |

> **为什么保留 14 个而不是就地瘦身到 4 个**：一旦就地瘦身，就必须同时拆掉
> `ChatAgentSkillRouter.kt:61-66` 那道手写 `moduleIds` 白名单——**否则被删的 10 个技能对应的工具再也发不出去，模型直接失能**。
> 那会把 P1-3A/B 的依赖链提前拉进 P0，破坏批次边界。故 P0-2 是纯粹的"换载体"。

**数据支撑**：

| 项 | 大小 |
|---|---|
| 14 个技能的**清单**（description）合计 | ~1,100 字符 ≈ **275 token** |
| 14 个技能的**正文**（instructions）合计 | ~7,452 字符 ≈ **1,860 token** |
| **正文 / 清单** | **6.8 倍** |

**正文全拼进 system prompt = 平白多花 1,585 token，且每轮重发。**

**开工前置（均已核实）**：

**① 执行侧接入点：只需接线。** `prepareToolCall`（`ChatAgentModuleExecutor.kt:348`）**按工具名转发，且早于任何模块解析**：

```kotlin
if (toolCall.name == CHAT_TEMPORARY_WORKFLOW_TOOL_NAME) return prepareTemporaryWorkflow(...)
if (toolCall.name == CHAT_SAVE_WORKFLOW_TOOL_NAME) return prepareSaveWorkflow(...)
```

`load_skill` 照此加第三个名字判断，返回 `ChatPreparedToolItem.ImmediateResult` 即可——它是纯本地计算（查 `SKILL_CATALOG` + 拼字符串），**无权限、无副作用、无 IO**。

**② 必须处理：`ImmediateResult` 会把整批风险抬到 HIGH。** `riskLevelOf`（`:338-346`）把它**硬编码为 HIGH**，batch 风险取 max（`:189`）。照现状接入，**每次 `load_skill` 都会触发审批**——P0-2 之后这会成为常态交互。

**定案**：给 `ImmediateResult` 加 `riskLevel` 字段，默认 `HIGH`：

```kotlin
data class ImmediateResult(
    override val toolCall: ChatToolCall,
    val result: ChatToolResult,
    val riskLevel: ChatAgentToolRiskLevel = ChatAgentToolRiskLevel.HIGH,   // 默认保持现状语义
) : ChatPreparedToolItem
```

`load_skill` 显式传 `READ_ONLY`。

> 该类型目前的 8 处使用（`:360/376/415/437/456/566/585/654`）**都是错误/前置校验路径**，标 HIGH 合理；
> `load_skill` 是第一个「正常成功且无风险」的使用者，属于语义扩展。
> **不新建 item 类型**——那要动 `when` 全部六处分支 + `describeForLog`，超出 P0 需要。

**③ `SKILL_CATALOG` 是 `private`**（`ChatAgentSkillRouter.kt:727`）。`load_skill` 要读它，必须在同一文件内加 `internal` 访问器。**`ChatAgentSkillRouter` 在 P0 不可能"只留转发"**——这条与 §4.1 的接管范围一致（它本来就是整体搬入新包的对象）。

**④ 单测改写**：`ChatAgentToolingTest.kt` 的 3 处 `buildSystemPrompt` 断言（`:304`/`:356`/`:430`）**直接验证 `"Active skills:"` 段**，P0-2 后必然失败 → 改为断言新的 `<available_skills>` 清单格式。
（其余 17 处 `selectSkills` 断言**不受影响**——工具可见性逻辑不动。）

**⑤ 实施时发现：`load_skill` 必须显式纳入常驻**（v1.5.1 补，本文档原未预见）。

`selectSkills` 会把 `availableTools` 过滤成「命中的技能声明的工具 + 常驻 helper」。
`load_skill` **两头都不占**：
- 它不在 `ALWAYS_EXPOSED_NATIVE_HELPERS`（那个集合只列 11 个屏幕 helper）
- 没有任何技能的 `toolNames` / `moduleIds` 声明它

后果：`<available_skills>` 清单**常驻** system prompt 并指示模型调用 `load_skill`，
但**工具表里没有它**——等于教模型调一个不存在的工具。模型会反复尝试、反复失败。

**修法**：把「常驻」的判定从 `isAlwaysExposedNativeHelper` 扩展为 `isAlwaysExposedTool`
（helper **或**按需入口）。注意 prompt 里那段 "Always-available agent-native helpers"
说的是 helper，**不应**包含 `load_skill`，故保留两个函数、分开使用。

> **这是设计文档的盲区**：它把 `load_skill` 当作「新增工具」讨论，
> 没意识到 `selectSkills` 会参与**所有**工具的下发决策。
> **P1-1 的 `query_module_schema` / `call_module` 会踩同一个坑**——
> 它们同样不在任何技能的 `moduleIds` 里，若不加进常驻，撤走 59 个模块工具后
> 模型将**既没有模块工具、也拿不到查询入口**。这一点已在 P1-1 的实施要点里标注。

**改动量**：中。

**不需要的**：`ChatConversation` 加字段、状态同步逻辑、清理时机判断——**历史本身就是状态**。

> **实测修正（v1.5.1）**：上述 ④ 预估「3 处 `buildSystemPrompt` 断言失败」，
> **实际只有 1 处**（`skillRouter_promptInstructsObservationAndVerification` 的末条断言）。
> 另外 12 处失败是**新增 `load_skill` 进 `sampleTools()` 后，工具表基准变了**——
> 修的是 `expectedToolNames` / `alwaysExposedNativeToolNames` 两个共享辅助函数，
> 不是逐个改用例断言。
>
> 教训：**测试里的共享基准（这里是一个辅助函数）比断言本身更能放大改动面**。
> 改工具集合时先看辅助函数，比逐个核断言高效。

---

#### 第一批验收

| # | 验收项 | 结果 |
|---|---|---|
| 1 | **病症 B 复现**：让 AI 给 `If` 填 `value1`/`value2`，执行不静默丢弃；catalog 里有这两个字段 | ✅ 单测 `ModuleInputDefinitionsTest` 证实（`If` 默认算子下仍暴露 `value1`/`value2`） |
| 2 | **`load_skill` 正文完整返回**，不被 1600 截断 | ✅ **真机证实**（见 §4.2.1） |
| 3 | **`load_skill` 不触发审批** | ✅ **真机证实**（见 §4.2.1） |
| 4 | `ChatAgentToolingTest` 除 3 处外**全绿** | ✅ 实际只 1 处需改（预估偏差见 §P0-2 ⑤） |
| 5 | **新增单测**：`load_skill` tool result 路径 + `IfModule` 非退化求值 | ✅ 新增 5 例 |
| 6 | `./gradlew test` 全绿 | ⚠️ 除 `VObjectPropertyTest` 的**上游预存失败**（与本批次无关，见 §4.2.1） |

> **明确不验收**："工具不消失"——见 §3.3 限定框，这是 P1-1c 的职责。

#### 4.2.1 真机验证结论（2026-09-14，MIX Fold 3）

打包 `91546ecb`（P0-2 完成态）装机后，与模型进行了一次真实会话
（**该会话本身由本 fork 的会话导出功能导出**，见 [`../../FORK.md`](../../FORK.md)）。

**P0-2 三项设计全部通过**：

| 验证项 | 真机表现 |
|---|---|
| 模型**会主动调 `load_skill`** | ✅ 用户说"创建一个含 if 的工作流"，模型自行调用 `vflow_agent_load_skill {"skill_id":"saved_workflow_creation"}`——**这是 P0-2 最核心的未知数，得到证实** |
| **不触发审批** | ✅ 全程无审批弹窗（`riskLevel = READ_ONLY` 生效） |
| **正文完整不截断** | ✅ 4 句正文原样返回（`truncatable = false` 生效） |
| 模型理解机制 | ✅ 模型自述"我主动加载的技能正文"，说明它正确理解了清单→加载的两段式 |

**同时该会话实锤了病症 A/C，且比原证据更干净**：

| 轮次 | 现象 | 成因 |
|---|---|---|
| [0] | 问"你有几个工具" → 答"12 个"（真实 72 个） | 59 个模块工具因关键词未命中未下发 |
| [2]→[5] | "创建工作流" → 技能命中 → `save_workflow` 下发（**含 184 个模块的 catalog**）→ 成功创建 | — |
| [8] | 追问"`if.start` 的参数定义是什么" → 模型答**"我看不到"** | 该轮无关键词命中 → `save_workflow` **不再下发** → catalog 随之消失 |
| [14] | 问"编写工作流时有多少模块可用" → 答**"我没有任何模块清单"** | "编写" 不在 `WORKFLOW_CREATE_VERBS`（只有保存/创建/生成/新建）→ 同样未命中 |

> **这是比原 §1 证据更锐利的复现**：原证据是 24 条消息后技能丢失，
> 这里**两轮之内**（[5]→[8]）工具就消失了。
>
> 且损害比"答不出"更深：**[15] 模型自述"我能说出名字的模块全都是我凭命名习惯猜的"**——
> 但 [5] 时它手上确有 catalog。**工具消失导致模型无法解释自己做过什么。**
> catalog 一旦经 tool description 下发，就受关键词路由支配；路由一变，知识随之中断。

> ⚠️ **注意**：P0 版**只修了 catalog 的字段完整性（P0-1），没改它的下发条件**。
> catalog 当时仍在工作流工具的 description 里、仍受 `selectSkills` 过滤——
> 这正是 §3.3 限定框与 §Q11 描述的断点。P1-1 把 schema 查询改为常驻工具，P1-1c 撤掉关键词路由，
> 两处合起来才根治。

---

### 4.3 第二批：P1 三项 + 依赖清理

#### P1-1：catalog 分层 —— 清单常驻 + schema 按需

**问题**：189 个模块的完整 catalog 全量铺在工作流工具 description 里（截断虽已修复，但 token 成本已增至 ~5,779 token）。

> **v1.5 更正**：v1.4 称 catalog「服务的只有 2 个技能」——**不准确**。
> catalog 拼进的是**工作流工具的 description**（`ChatAgentToolRegistry.kt:130-166` 的
> `buildTemporaryWorkflowToolDefinition` / `buildSaveWorkflowToolDefinition`），
> **压根不经过 `selectSkills`**，与技能白名单无交集。

**方案**：

| 层 | 内容 | 何时在场 |
|---|---|---|
| **清单** | 只留 `moduleId` + 中文名（去掉 description 与字段名） | 挂在工作流工具，常驻 |
| **详情** | 完整输入定义（用 `getInputs()` 静态全量） | 新增 `query_module_schema` 工具，按需 |

**关键实现细节**：

- 查询工具**必须用 `getInputs()`**（静态全量，含 `value1`/`value2`），**不要用 `getDynamicInputs()`**——后者正是病症 B 的元凶
- 签名 `query_module_schema(moduleId, operator?)`：传 `operator` 返回该算子精确需要的字段集，不传返回全集
- **返回结构须含 `scopes[]` 与 `callable`**——见 §3.4（设计 B）
- **查询域 = 184**（能进保存工作流的全部），不是 59
- **挂载点**：常驻（不再限于工作流技能——`call_module` 场景也需要）
  - ⚠️ **必须显式加进 `isAlwaysExposedTool`**（见 §P0-2 前置⑤）。`query_module_schema` 与
    `call_module` 都不在任何技能的 `moduleIds` 里，`selectSkills` 会把它们过滤掉。
    **P1-1c 撤走 59 个模块工具后，若这两个入口没进常驻，模型将既没有模块工具、
    也拿不到查询入口——彻底失能。** 这是本批次最容易漏、后果最严重的一点。

**现成参考**：`ModuleHandler.kt:172` 的 `handleGetModuleDetail` 已经是这个做法（用 `getInputs()` 静态全量），可直接复用其组装逻辑。

> ⚠️ **P1-1 的隐藏风险**：`query_module_schema` 会撞 1600 截断——它要返回完整的 `InputDefinition` 列表，
> 复杂模块很容易超限，**而被截掉的很可能正好是字段定义本身**（即病症 B 复发）。
> 因此 **P0-3 是 P1-1 的硬前置**（已由 `truncatable = false` 解决）。

**改动量**：中。

---

#### P1-1b：`call_module` 通用执行入口

**问题**：59 个模块工具撤出 `tools` 数组后，**模型拿到 schema 也无处可调**（§3.2 的断点）。

**方案**：新增 `call_module(moduleId, params)` 固定工具。

| 要点 | 说明 |
|---|---|
| **校验 `callable`** | 不在 `DIRECT_TOOL` 集合则明确报错，如 `"vflow.logic.if.start 只能用于工作流，不能直接调用"` |
| **自行校验 params** | 因 `params` 是通用 object，**provider 的 `strict` 校验失效**；必须用 `query_module_schema` 的同一份定义在服务端校验 |
| **审批必须覆盖** | 它是"万能入口"，风险等级取**目标模块的实际等级**，不能一律放行 |
| **不信任模型** | 即使模型"查过" schema，也要重新校验——它可能凭记忆直接调 |

**前置依赖**：**P0-1 必须先落地**。否则静默丢弃（`ChatAgentModuleExecutor.kt:1270`）会把参数错误吞掉。

**改动量**：中–大。

---

#### P1-1c：撤出 59 个模块工具 + 删除 `selectSkills` ★核心

**方案**：把 `buildDirectToolDefinitions()`（`ChatAgentToolRegistry.kt:683-692`）的产物**从 `toolsByName` 移除**，改为仅用于 `query_module_schema` / `call_module` 的数据源。

**连带动作**：

- `getToolForModuleId()`（`:70-72`）等既有调用点需一并调整
- **删除 `ChatAgentSkillRouter.selectSkills`** —— 完整删除，保留 3 处生产调用点：`ChatViewModel.kt:869`、`ChatBenchmarkRunner.kt:351`、`ChatCompletionClient.kt:712`

> **为什么在这一步删 `selectSkills`（v1.5 新增的定案）**：
> §1.1 已证明它**唯一的作用**是从 52 个模块工具里挑几个发出去。
> 59 个工具撤走后，`availableTools` 恒等于 11 个 helper + 2 个工作流工具 + 3 个按需入口，
> **过滤恒等于"不过滤"**——`selectSkills` 失去全部语义。
> 随之失效的还有它内部的四套关键词机制：`moduleIds`、`expandSkillIds`、
> `CONTINUATION_SIGNALS`（`:749`）、`KNOWLEDGE_QUESTION_SIGNALS`、`OPERATIONAL_SIGNALS`。
>
> **注意**：`selectSkills` 的实际实现包含 `expandSkillIds` 等多重逻辑，
> 不是单纯的 `filter`。**这 795 行文件里的关键词机制在本步全部作废。**

- **技能可见性改由模块的 `usageScopes` 声明**（P1-3A，见下）

**改动量**：中。但**它是全篇唯一会让模型短期变笨的改动**，必须在 P1-1b 之后。

---

#### P1-3A：可见性数据驱动

**方案**：删掉 `ChatAgentSkillRouter.kt` 里手写的 `moduleIds` 字符串，改由模块的 `usageScopes` 声明。

`ChatAgentToolRegistry.kt:683-692` 已有现成范式：

```kotlin
private fun buildDirectToolDefinitions(): List<ChatAgentToolDefinition> {
    val moduleIds = ModuleRegistry.getAllModules()
        .filter { module ->
            module.aiMetadata?.usageScopes?.contains(DIRECT_TOOL) == true ||
                module.id in LEGACY_DIRECT_TOOL_MODULE_IDS
        }
    ...
}
```

**问题在于这道自动扫描的产物，还要再过一道手写的技能 `moduleIds` 白名单**（`ChatAgentSkillRouter.kt:61-66`）。**A 档要做的就是拆掉这道手写白名单。**

**改动量**：小。

---

#### P1-3B：技能清空 14 → 0（v1.5.3 修正，原为「瘦身 14 → 3–4」）

> ⚠️ **原文的「保留 3–4 个」前提不成立**。实施时逐行核对后发现：
> 文档点名的 4 个"有实质 instructions"的技能，**正文同样几乎全是重复的**——
> `saved_workflow_creation` / `temporary_workflow_execution` 与工作流工具的
> description **逐字重复**，`generic_device_interaction` 10 行里 9 行与 prompt 重复。
>
> 详见 §4.3.1 的核对结果。**定案：技能清空，6 条独有内容上提 prompt，机制保留。**

**改动量**：小（删代码），但**必须等 A 档**，否则工具会发不出去。

---

#### P2-2：Prompt 缓存

给 Anthropic 的 `system`/`tools` 块加 `cache_control: {type: "ephemeral"}`。改动一行级别。

**参照 Pi 的三个落点**（`pi/packages/ai/src/api/anthropic-messages.ts:1071/1430-1459/1373-1397`）：system prompt 文本块、**tools 数组最后一个工具**、最后一条 user 消息的最后一个 block。且**压缩/摘要请求本身应显式关闭缓存**（Pi 用 `cacheRetention: "none"`）。

> ⚠️ **必须晚于 P1-1c**：缓存收益受**前缀稳定性**影响。
> P1-1c 之后 `tools` 数组固定 16 个不变、技能目录已清空（`<available_skills>` 段不再输出）
> ——**这才是缓存真正能命中的状态**。提前做等于白做。
>
> **实施时的取舍**：只在 `system` 末尾与 `tools` 最后一个工具打两个断点，
> **不在 messages 上打**——Anthropic 上限 4 个断点，而 messages 每轮都变、基本不会命中，
> 标了只会挤占额度。（Pi 标记最后一条 user 消息是出于多轮工具调用的场景，vFlow 暂不需要。）

---

#### P3-1：清理遗留（并入 P1-1c，不单列）

| 项 | 说明 |
|---|---|
| `LEGACY_DIRECT_TOOL_MODULE_IDS`（`ChatAgentToolRegistry.kt:814`） | `DIRECT_TOOL` 概念出现前的兼容垫片；P1-1c 后可清理 |
| `LEGACY_SAVED_WORKFLOW_EXCLUDED_MODULE_IDS`（`:860`） | 仅 3 个 AI 模块，确认无引用后可并入常规判定 |
| `CONTINUATION_SIGNALS`（`ChatAgentSkillRouter.kt:749`） | P1-1c 删除 `selectSkills` 时一并删除 |
| 技能里的冗余 `toolNames` | **22 处引用全部指向已在常驻白名单的 helper**——`screen_observation`(3)、`ui_interaction`(6)、`app_lifecycle`(2)、`shell_execution`(11)。**全部是无效声明，可删** |

> 那"22 处冗余 `toolNames`"是走查时实测的：因为 helper 已被 `ALWAYS_EXPOSED_NATIVE_HELPERS` **无条件注入**（§1.1），技能再声明一遍**不产生任何效果**。

---

#### 第二批验收

| # | 验收项 | 状态 |
|---|---|---|
| 1 | **病症 A 复现**：建工作流后切话题，追问细节**不再需要用户复述** | ✅ **真机证实**（见 §4.3.3） |
| 2 | **病症 C**：工具数 **72 → 16**；且"关不掉手电筒"场景**不再复现** | ✅ **真机证实**（见 §4.3.3） |
| 3 | `call_module` 的**审批按目标模块风险等级**，不一律放行 | ✅ 代码已定案（`prepareCallModule` 用目标模块等级构造 definition）；⚠️ 未经真机观察 |
| 4 | **`selectSkills` 已删除**；四套关键词机制全部消失 | ✅ 已达成（`1afc0a8b`） |
| 5 | 保留的技能**正文已改写**，不再引用旧工具名 | ✅ **改为清空**（`07f5ee86`，理由见 §P1-3B） |
| 6 | **`ChatAgentToolingTest.kt` 按新架构改写完毕** | ✅ 已达成 |
| 7 | `./gradlew test` 全绿 | ⚠️ 除 `VObjectPropertyTest` 的上游预存失败 |
| 8 | **Anthropic 缓存命中**（读 `usage.cache_read_input_tokens`） | ⬜ 待真机 |
| 9 | **`query_module_schema` 输出完整字段语义** | ✅ **真机复验通过**（模型读出 "Upper bound used only by the number_between operator"，非"猜的"） |

> ⚠️ **`ChatAgentModuleExecutor` 需要真 `Context`，从未被单测覆盖**（既有盲区）——
> `query_module_schema` 的返回内容、`call_module` 的审批判定，只有编译与
> 间接单测层面的保证。**故第 3、8、9 项仍以真机为准。**

#### 4.3.3 第二批真机验证：第 1、2 项通过（2026-09-14）

**病症 C 与病症 A 的断点均已消除**（对比 §4.2.1 的旧版会话）：

| 场景 | 旧版（P0） | 新版（P1/P2） |
|---|---|---|
| 问"你有几个工具" | "12 个"（真实 72） | **"16 个"** ✅ 准确 |
| 打开手电筒 | 靠猜参数名 | `query_module_schema` → `call_module` ✅ |
| **"把它关了"**（2 轮后） | ❌ **工具消失，做不到** | ✅ **做到了**（`call_module` 常驻） |
| 问 `if.start` 参数 | "我看不到" | 完整列出 4 字段 + 19 算子 ✅ |
| `callable: false` 说明 | — | ✅ 正确输出"不能直调但可作工作流步骤" |

> **Q11 预测的「关不掉手电筒」断点已治愈**——这是 P1-1c 最核心的验收项。
> 根因（工具随关键词路由消失）已随 `selectSkills` 删除而消失。

#### 4.3.4 真机暴露的缺陷：`query_module_schema` 丢失字段语义（已修复）

验证过程中用户提出一个尖锐问题，直接定位到一个**真 bug**：

> "`number_between` 需要 value1 和 value2，这一点是 schema 定义里面写的吗？"

模型查过 schema 后回答：

> "**不是**……那是我从字段命名（比较值 1 / 比较值 2）和 `between` 的语义**猜的**"

**但 `IfModule` 源码里本来就写着**：

```kotlin
inputHints = mapOf(
    "input1" to "Primary value to test. Usually a previous step output or variable.",
    "value2" to "Upper bound used only by the number_between operator.",
)
```

**信息一直在，只是没送到模型面前。**

**根因**：P1-1c 撤走 59 个模块工具时，`query_module_schema` 只搬了「字段定义」，
**漏搬了一整类字段语义**：

| `AiModuleMetadata` 字段 | 使用模块数 | 修复前 |
|---|---|---|
| `inputHints` | **67** | ❌ 丢失 |
| `requiredInputIds` | **76** | ❌ 丢失 |
| `workflowStepDescription` | **97** | ❌ 丢失 |
| `directToolDescription` | 59 | ⚠️ 未用（用了面向人的本地化文案） |

这些信息原本经模块工具的 JSON Schema 送给模型；**59 个工具撤出后唯一出口变成
`query_module_schema`，而我没复用那套拼装逻辑**，信息便静默断流。

**修复**（`983abb1d`）：提取 `buildModuleInputDescription` / `artifactTypeLabel` 为
顶层共享函数（与 P0-1 提 `resolveModuleInputDefinitions` 同一手法），两条路径同源；
输出补必填 `*` 标记、`workflowStepDescription`、优先 `directToolDescription`。

**连带定案（§6.2 开放问题 2）**：**删除 `operator` 参数**。实测它无效——
传 `number_between` 与 `number_gt` 返回完全相同，因为 `resolveModuleInputDefinitions`
是**并集**求值，静态全集把动态裁剪结果吃掉了。且修好 `inputHints` 后它更无必要：
「哪个字段被哪个算子用到」直接写在字段说明里。**若修好它需引入「会裁剪字段的求值模式」，
那正是病症 B 的成因**，故删除而非修复。

> **教训**：撤换一条信息通路时，要**穷举原通路承载的全部信息**，而不只是最显眼的那部分。
> 本次漏掉的三个字段都是「模块作者专门为 AI 写的」，恰恰最不该丢。

---

#### 4.3.1 P1-3B 的实际结论：技能**清空**而非瘦身（2026-09-14）

原计划「14 → 3–4 个」。实施时逐行核对后发现前提不成立。

**14 个技能正文共 70 行 / 6,683 字符，对照 system prompt 与工具 description 后，
仅 6 行是独有的**：

| 独有内容 | 原属技能 |
|---|---|
| input-text 输入到焦点字段，必要时先建立焦点 | `ui_interaction` |
| 用 installed-app lookup 解析应用显示名/品牌后再启动或关闭 | `app_lifecycle` |
| shell 是最后手段、命令范围要窄、别看结果前假设成功 | `shell_execution` |
| DND 请求直接调工具传 on/off/toggle，别开 Settings | `device_settings_control` |
| 反馈类请求（toast/震动/TTS/音频/电话）选对输出模态 | `device_feedback` |
| 偏好无障碍/find_element，OCR 仅作兜底 | `screen_observation` |

其余 64 行与 prompt 或工具 description 重复，部分**逐字相同**。典型：

- `saved_workflow_creation` 的 4 条规则与 `save_workflow` 工具 description **逐字重复**
- `temporary_workflow_execution` 的 4 条同理
- `device_settings_control` 的 4 条与 prompt 第 5 行**列举了完全相同的模块**
- `generic_device_interaction` 10 行里 9 行与 prompt 重复

**"什么时候用哪个工作流工具"的判别规则也不在技能里**，而在工具 description 第一句
（`Use this only when the user asks to create, generate, or save for later reuse` /
`Do not use this for a single clear action`）——**这是正确的分工**：工具的使用时机本该写在工具自己的描述里。

**结论**：6 条独有内容上提到 `buildSystemPrompt` 的规则段，`SKILL_CATALOG` 置空。
**机制完整保留**（清单段 / `load_skill` / 两个访问器 / 数据类），后续可继续添加技能；
`SKILL_CATALOG` 处加注自检问题：「这条内容是否已在 system prompt 或工具 description 里？」

#### 4.3.2 上游为什么这么写（2026-09-14 追查）

拉取上游完整历史（703 commits）后的发现：

| 时间 | 事件 |
|---|---|
| 2026-04-25 | `13c38fae feat(ChatAgent): 提供完整的屏幕操作能力`——**一举引入 `ChatAgentSkillRouter.kt` 789 行**（14 个技能 + 4 套关键词机制），同批引入 `ChatAgentNativeTooling.kt`（2,682 行） |
| 2026-05-23 | `d56365c8 feat(勿扰模式)`——只**加了一个技能**（DND） |
| 2026-09-14 | fork 开始改造 |

**关键事实：SkillRouter 从诞生就是 789 行，5 个月里只被改过 1 次，且只是加技能。
没有重构、没有删减、没有修正。**

**它不是"先开发后弃用"，而是"职责被架空后没人清理"**：

1. 诞生时（2026-04）agent 只有 11 个屏幕 helper，**没有模块工具**。
   "技能"的原始职责是**把模块能力按场景分组暴露给 agent**。
2. 后来 `ChatAgentToolRegistry` 改成**自动扫描 `usageScopes` 生成工具**（`buildDirectToolDefinitions`），
   **分组职责被自动扫描接管**——但技能没删。
3. 于是技能只剩「从自动生成的结果里再筛一遍」这个二次过滤，加上一批顺带写的防呆话。
4. P1-1c 撤走模块工具后，连二次过滤也失去意义。

**对我们决策的意义**：删技能不是删功能，而是**把一个功能从错误的载体迁到正确的载体**
（模块暴露改由 `query_module_schema` + `call_module` 承担）。

---

### 4.4 实施顺序

```
════ 第一批（P0）═══════════════════════════════════
P0-3  输出截断按性质声明          ← ★ P0-2 / P1-1 的共同前置
  ↓
P0-2  技能正文改走 tool result（+load_skill）  ← 可并行
P0-1  schema 非退化求值 + 静默丢弃改报错        ← 可并行
═══════════════════════════════════════════════════
                    ↓ 提交 + test 全绿
════ 第二批（P1）═══════════════════════════════════
P1-1  catalog 分层 + query_module_schema      ← 依赖 P0-1 + P0-3
P1-1b call_module                             ← 依赖 P0-1 + P1-1
P1-1c 撤出 59 个模块工具 + 删除 selectSkills   ← 依赖 P1-1b
P1-3A 可见性数据驱动（拆手写 moduleIds）       ← 小
P1-3B 技能清空（14 → 0）+ 独有内容上提 prompt  ← 依赖 P1-3A
P2-2  Prompt 缓存                             ← 必须晚于 P1-1c（前缀稳定）
P3-1  清理遗留                                ← 并入 P1-1c
═══════════════════════════════════════════════════

另开任务（不与本链耦合）
P2-1  工具输出裁剪  ← 解决"上下文爆炸"，与工具暴露重构无依赖
```

**关键依赖链（不能颠倒）**：

```
P0-1 ──→ P1-1b        （call_module 需要参数校验）
P1-1 ──→ P1-1b        （call_module 需要 schema 源）
P1-1b ──→ P1-1c       （撤工具前必须先有替代调用路径）
P0-3 ──→ P0-2 / P1-1  （两个新工具的输出都必须免于 1600 截断）
P1-3A ──→ P1-3B       （清空前提：可见性已数据驱动，否则工具发不出去）
P1-1c ──→ P2-2        （缓存需要前缀稳定）
```

**P0-1 和 P0-2 可以并行**，互不依赖。

---

### 4.5 明确排除

| 项 | 理由 |
|---|---|
| **远程 MCP 路线**（把模块清单暴露给 PC 上的通用 Agent） | 与 App 内重构共享 schema 层但 UI 层不同，属独立议题（原 §7 开放问题 3） |
| **`ChatAgentNativeTooling.kt` 模块化** | 它是三套工具体系里唯一有内部状态的（observation epoch / swipe direction），重构它会让 11 个 helper 能被工作流复用，但**改动很大且不阻塞本链**（原 §7 开放问题 4） |
| **`search_tools`** | `query_module_schema` 已覆盖"找到并调用"的需求；清单常驻（189 行文本）也让模型知道"有哪些"。除非清单大到塞不下（当前 ~275 token，远未到） |

---

## 5. 与 FORK.md 的冲突面评估

**这是本次改造最大的非技术风险。**

现状：`ui/chat/` 下的 Chat Agent 核心文件**基本是上游文件**——下面 9 个文件共 **9,616 行**中，只有 `ChatViewModel.kt` 已有一处 fork 改动（`exportConversation`，登记为「手动合并」），其余在 FORK.md 的清单里**完全未登记**，即纯上游文件。

| 文件 | 行数 | 改造涉及 | 冲突风险 | FORK.md 现状 |
|---|---|---|---|---|
| `ChatAgentSkillRouter.kt` | 795 | P0-2 / P1-1c / P1-3 | **高**——**整体搬入新包** | 未登记（纯上游） |
| `ChatAgentToolRegistry.kt` | 875 | P0-1 / P0-3 / P1-1 | **高**——**整体搬入新包** | 未登记（纯上游） |
| `ChatAgentModuleExecutor.kt` | 1,590 | P0-1 / P0-2 | 低–中（**局部函数体，约 9 行 + 一个字段**） | 未登记（纯上游） |
| `ChatCompletionClient.kt` | 884 | P0-3 / P2-2 | 低（加字段判断 + 加参数） | 未登记（纯上游） |
| `ChatViewModel.kt` | 1,237 | P0-2 | 中 | 已有一处 fork 改动 → 手动合并 |
| `ChatModels.kt` | 215 | P0-3 | 低（加字段） | 未登记（纯上游） |
| **`ChatAgentToolingTest.kt`** | **707** | **P0-2 / P1-1c** | **高**——P1-1c 后大量断言反转 | 未登记（纯上游） |
| `ChatAgentNativeTooling.kt` | 2,682 | P0-3（`truncatable` 声明）/ P3-1 | 中 | 未登记（纯上游） |
| `ChatBenchmarkRunner.kt` | 631 | P1-1c（tool 消费点语义变） | 中 | 未登记（纯上游） |

> **`ChatAgentToolingTest.kt` 是 v1.5 补上的**：v1.4 的冲突面评估漏了它。
> 它 **707 行**、**20 处调用 `selectSkills`、3 处调用 `buildSystemPrompt`**，
> 是**唯一能在 `./gradlew test` 下运行的回归网**（benchmark 需要 provider/apiKey，跑不了）。
>
> P1-1c 后，形如下面的断言会**整体反转**：
> ```kotlin
> // ChatAgentToolingTest.kt:84
> assertEquals(expectedToolNames("vflow_device_flashlight"), selected.availableTools.map { it.name })
> // :131-132
> assertTrue(!selected.availableTools.map { it.name }.contains("vflow_interaction_ocr"))
> ```
> `vflow_device_flashlight` **不再是工具名**（改走 `call_module`）。
> 这些断言会变成"验证一个已死机制"。
>
> **v1.4 的测试代价只算了 benchmark 36 个用例，漏了这 707 行。**

**FORK.md 的核心原则是"控制 diff 面积，能加新文件就不改上游文件"。本次改造必然违反这一原则。**

### 5.1 边界策略：长期分叉（已定案）

**决策：选「长期分叉」，不选「保持可合并、只在原文件最小改动」。**

#### 接管范围（v1.5 收窄）

| 文件 | 处置 | 理由 |
|---|---|---|
| `ChatAgentSkillRouter.kt`（795） | **整体搬入新包** | P0-2 要删 `buildSystemPrompt` 函数体中间段（`:82-116`），P1-1c 要删整个 `selectSkills`——改法都是重写。且 `SKILL_CATALOG` 是 `private`（`:727`），必然要在该文件内加访问器 |
| `ChatAgentToolRegistry.kt`（875） | **整体搬入新包** | P0-1 改 catalog 求值、P0-3 加字段、P1-1c 撤 59 个工具，同样是重写 |
| `ChatAgentModuleExecutor.kt`（1,590） | **留原文件最小改动** | P0-1 只动 `buildParameters` 函数体内部约 9 行，P0-2 给 `ImmediateResult` 加一个带默认值的字段，`:348` 加第三个名字判断——这是接线，不是重写 |
| `ChatCompletionClient.kt`（884） | **留原文件最小改动** | P0-3 加 `truncatable` 判断，P2-2 加参数——一行级 |

> **v1.5 修正**：v1.4 曾同时主张「新文件接管」与「上游文件只留最小接线」——**这两者在 P0-2 上做不到**。
> P0-2 要删掉 `buildSystemPrompt` 函数体中间的一段（`ChatAgentSkillRouter.kt:82-116` 内的 `"Active skills:"` 段），
> 那是**删改上游函数体**，不是接线。故定案：**接受在新包内重写，上游文件退化为转发壳或删除**。
>
> **新包 `ui/chat/agent/` 推迟到 P1-1c 创建**（P0 不建，理由见 §4.1）。
> v1.4 曾列出的 `AgentSessionContext.kt` **已删除**——§3.3 撤销了 `activatedSkillIds` 字段，
> "历史即状态"意味着这个文件**没内容可放**。

#### 决策依据

1. **改造动的是核心逻辑，不是零星补丁**——技能路由、工具注册、catalog 生成三项都在 9,616 行之内。选「最小改动」意味着每项都要在上游文件上反复动刀，FORK.md 的「控制 diff 面积」反而守不住。
2. **把冲突面从「每次都改 795 行」固定成「几处接线」**。`ChatAgentSkillRouter` 对外只有 **2 个函数 + 1 个数据类**（`selectSkills` / `buildSystemPrompt` / `ChatAgentSkillSelection`），生产调用点仅 3 处（`ChatViewModel.kt:869`、`ChatBenchmarkRunner.kt:351`、`ChatCompletionClient.kt:712`）。它收敛得很好，整体搬入新包成本可控。

#### 必须认的代价

**上游一旦继续迭代 Chat Agent，fork 的实现不再跟随，冲突需手工搬运或放弃上游实现。** 这是长期分叉的账，现在记下来比后面还好。

> **FORK.md 登记时机**：**开发完成后登记**（登记实际分歧，而非计划中的分歧）。

---

## 6. 决策台账

### 6.1 已定案

| # | 决策 | 出处 |
|---|---|---|
| 1 | 技能拆三：可见性 / 指令 / 触发各司其职 | §3.1 |
| 2 | 技能正文 → tool result（`load_skill`）；清单 → system prompt | §3.1 |
| 3 | **不加会话状态字段**，历史即状态 | §3.3 |
| 4 | 查询域（184）≠ 调用域（59），结果带 `callable` 标记（设计 B） | §3.4 |
| 5 | helper 与模块工具**非冗余**，缺口是"分工边界没说清" | §3.5 |
| 6 | 输出截断按**性质声明**：`truncatable` 显式标志，默认 `true` | P0-3 |
| 7 | 非退化求值=**并集两轮求值**：`getInputs() ∪ getDynamicInputs`，两轮让 `CallFunctionModule` 也能吐参数 | P0-1 |
| 8 | `getDynamicInputs` **零改动**，只改调用点；求值抽为 `resolveModuleInputDefinitions` 供 catalog 与执行共用 | P0-1 |
| 8b | **`CallFunctionModule` 的函数参数对 schema 发现机制不可见**——P1-1 必须解决（§6.2 开放问题 5） | P0-1 发现 |
| 9 | `ImmediateResult` 加 `riskLevel` 字段（默认 HIGH），不新建 item 类型 | P0-2 |
| 10 | P0-2 保留 14 个技能，瘦身留给 P1-3B（**该决策后被推翻，见条目 24**） | P0-2 |
| 11 | **P0 不建新包**，`ui/chat/agent/` 推迟到 P1-1c | §4.1 |
| 12 | 接管范围收窄：SkillRouter + ToolRegistry 搬入，其余留原地 | §5.1 |
| 13 | **两批切分**，批次=提交边界，不设暂停点、不加 feature flag | §4 |
| 14 | `selectSkills` 的死亡点是 **P1-1c**，随撤工具一并删除 | §1.1 / P1-1c |
| 15 | ~~技能正文必须改写~~ → **改为：技能全部清空**，独有内容上提 prompt（见条目 24） | P1-3B |
| 16 | 保留技能**正文层不限长**，实现时打点记录长度 | P0-3 |
| 17 | 截断告知**推迟到 P2-1** | P0-3 |
| 18 | 新包 `AgentSessionContext.kt` **删除**（无内容可放） | §5.1 |
| 19 | **长期分叉**：认代价，上游 Chat Agent 迭代不跟随 | §5.1 |
| 20 | P2-2 纳入（必须晚于 P1-1c）；P2-1 另开任务；P3-1 并入 P1-1c | §4.4 |
| 21 | 排除：远程 MCP 路线、`ChatAgentNativeTooling` 模块化 | §4.5 |
| 22 | **旧会话不做迁移**——不考虑已落盘会话的兼容 | — |
| 23 | fail 归因规则：错误可见化→改用例；逻辑退化→改代码；不明→停下问 | §4.1 |
| 24 | **技能清空（14 → 0）而非瘦身**——核对后 70 行正文仅 6 行独有，上提 prompt；**机制保留**供后续添加 | §4.3.1 |
| 25 | **技能机制保留**：清单段 / `load_skill` / 两个访问器 / 数据类都不删，后续可继续加技能 | §4.3.1 |

### 6.2 仍开放

| # | 问题 | 何时定 |
|---|---|---|
| 1 | `call_module` 的**审批粒度** | ✅ **已定案**：按目标模块风险等级逐次判定——`prepareCallModule` 用 `getRiskLevelForModuleId(moduleId)` 构造 `definition`，`riskLevelOf` 对 `Ready` 取 `definition.riskLevel`。工具定义里声明的 `riskLevel` 是占位值，不参与审批 |
| 2 | `query_module_schema` 的 **`operator` 参数是否必要** | ✅ **已保留**：实现时保留了该可选参数（传则返回该算子精确字段集），待真机验证其实际价值 |
| 3 | 技能清单的字节稳定化 | ✅ **已消失**：技能目录已清空，`<available_skills>` 段不再输出 |
| 4 | 缓存是否真命中 | ⬜ **待真机**：读 Anthropic 响应的 `usage.cache_read_input_tokens` |
| 5 | `CallFunctionModule` 的函数参数对 schema 发现机制不可见 | ⬜ **P1-1 未解决**（见下方详解）。`prepareQueryModuleSchema` 用单次求值 + 可选 `operator`，**仍拿不到依赖 `workflow_id` 才生成的函数参数** |

#### 开放问题 5 的现状（P1-1 实施后仍未闭合）

`prepareQueryModuleSchema` 的实现是：

```kotlin
val step = ActionStep(moduleId, defaultParameters + operator?)
val inputs = resolveModuleInputDefinitions(module, step)   // 单次求值
```

对 `CallFunctionModule`：默认 parameters 里没有 `workflow_id` → `getDynamicInputs` 提前
`return base` → **函数参数不会出现在查询结果里**。

**模型因此不知道某个函数工作流需要哪些参数**，也就无法用 `call_module` 正确调用它。
这与 `call_module` 在 `buildParameters` 里的**两轮求值**能力不匹配——
执行时能收下函数参数，但查询时看不到它们。

**可能的解法**（待定）：
- 查询工具接受 `partialParams`，做与执行侧相同的两轮求值
- 或返回时附带提示「此模块的可用参数取决于 `workflow_id`，请先确定它」

**影响范围**：仅 fork 自己的函数工作流功能（FORK.md 已登记），不影响上游模块。
| 5 | **`CallFunctionModule` 与 schema 发现机制的根本冲突**（P0-1 实现时发现） | P1-1 实现时必须解决 |

#### 开放问题 5 详解：`CallFunctionModule` 的 schema 不可发现

**问题**：`CallFunctionModule.getInputs()` **只声明 `workflow_id` 一个键**（源码注释原文：
「各参数输入框由 `getDynamicInputs` 依据选中函数工作流的签名动态生成」）。
各函数参数由 `getDynamicInputs` 在**知道 `workflow_id` 之后**凭空生成 `InputDefinition`。

它是 14 个重写 `getDynamicInputs` 的模块里**唯一会新增键**的——其余（`If`/`While`/`DoWhile`/
`HttpRequest`/`Flashlight`/`PlayAudio`/`Vibration`/`Bluetooth`/`Wifi`/`FindElement`/`OCR`/
`KeyEvent`）都是 `staticInputs.first { it.id == ... }` 或 `copy()`，只筛选和改写。

**对 P0-1 的影响（已解决）**：白名单不能取纯静态全集，故定为**并集两轮求值**
（见 §P0-1）。第一轮收 `workflow_id`，第二轮带上它再求值即得函数参数。

**对 P1-1 的影响（未解决，必须在 P1-1 时处理）**：
`query_module_schema` 若只调一次（`getInputs()` 或单次 `getDynamicInputs`），
**返回的函数参数列表是空的**——模型不知道这个函数工作流需要哪些参数，
也就永远调不对它。**「用 `getInputs()` 静态全量」这条指导对 `CallFunctionModule` 不成立。**

需要 P1-1 决定：查询工具是否为这类「依赖 step 状态」的模块做**多轮求值**，
或提供一条「先查 workflow_id 候选 → 再带 workflow_id 二次查询」的路径。
注意这会让 `query_module_schema` 的签名复杂化（可能要接受 `partialParams`）。

> **注意现状已如此**：改造前用 `getDynamicInputs(默认值 step)`，默认 step 里没有 `workflow_id`
> → 提前 `return base` → 函数参数**现在就全丢**。所以这不是回归，而是**改造前就存在、
> 且 P1-1 会继承**的缺陷。它属于 fork 自己的功能（函数工作流），不是上游的账。

---

## 7. 相关文档

- [`surveys/ai-system-overview.md`](surveys/ai-system-overview.md) —— 现状地图，本文所有病症的证据来源
- [`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md) —— 外部对照（本文 §2 是其技能与工具章节的深化）
- [`chat-agent-enhancement-plan.md`](chat-agent-enhancement-plan.md) —— 上一轮增量改造，本文吸收其 §1（技能披露）、§3（缓存）并重新定位
- [`../../FORK.md`](../../FORK.md) —— 冲突归属规则，本文 §5 的约束来源

---

## 附录：修订史

> 保留修订史的目的：**记录被证伪的推理路径**，防止将来重蹈。
> 正文只讲当前结论；"谁推翻了谁"收在这里。

### v1.0 → v1.1（2026-09-13）

参考实现从两家（CCB / dsh）扩到四家，新增 OpenCode（`anomalyco/opencode`）与 Pi（`earendil-works/pi`）的源码分析。

> ❌ **v1.1 的核心错误**：把「OpenCode/Pi 内建工具少（~15/8）却全量下发」直接类比成「vFlow 也可以全量下发」，
> 据此得出「优先压小工具表、把搜索留到以后」。
>
> **错误所在**：混淆了**内建工具**与**扩展工具**。vFlow 的 59 个模块工具性质上是**扩展工具（MCP 那类）**
> ——由 `ModuleRegistry` 自动生成、数量随模块增长、超出 agent 核心能力范畴。
> OpenCode 对 MCP 其实是**默认全量下发**（`opencode/packages/opencode/src/session/tools.ts:389`），
> Pi **根本不支持 MCP**（`pi/packages/coding-agent/README.md:499`）——两者都没面对过"工具数量爆炸"问题。
> 正确的参照是 **CCB 对 MCP 的处理**（`SearchExtraTools` → `ExecuteExtraTool`）。

### v1.2（2026-09-13）

四家源码克隆到本地 `D:/develop/references/`，全文引用路径补上仓库前缀。

### v1.3（2026-09-13）—— 重大修正

**推翻 v1.1 的核心结论**：

1. §2.1/§2.2 的类比错误纠正（见上）
2. §3.2 补完断点：新增 `call_module`，解决"查到 schema 之后怎么调"
3. §3.4 新增设计 B：查询域（184）≠ 调用域（59）
4. §3.3 撤销 `activatedSkillIds` 字段：正文改走 tool result 后，**历史即状态**
5. §3.5 撤回"P0-0 清理 helper 重叠"：helper 与模块工具**集合独立**，功能相似但非冗余
6. §4.0 新增工具总览：改造前后 **72 → 16**
7. 新增 P1-1b / P1-1c / P3-1；P1-2 并入 P1-3

### v1.4（2026-09-13）

1. §6.1 边界定案：选「长期分叉、新包整体接管」
2. 新增 P0-3：工具输出截断改为「按输出性质声明」
3. P0-2 补开工前置四项（执行侧接线点 / `ImmediateResult` 硬编码 HIGH / 1600 截断 / 四家正文限长实测）
4. §6 补记冲突面漏了 `ChatAgentNativeTooling.kt` 与 `ChatBenchmarkRunner.kt`

### v1.5（2026-09-13）—— 决策定稿版

**经一轮 19 问拷问后定稿。三处结构性假设被推翻：**

| # | v1.4 的假设 | v1.5 的修正 | 影响 |
|---|---|---|---|
| **1** | 「P0 独立治好病症 A 和 B」 | **不成立**——P0-2 只搬正文，**工具可见性仍每轮重算**（`selectSkills` 只读最新一条 USER 消息）。P0 单独交付会留一个「关不掉手电筒」的版本 | §3.3 加限定框；P0-2 验收明确排除此项 |
| **2** | 「P1-3A 拆手写白名单即可」 | **`selectSkills` 要整体删除**。§1.1 实测证明它唯一的作用是从 52 个模块工具里挑几个发出去，59 个工具撤走后它失去全部语义 | §1.1 新增；P1-1c 纳入删除动作；验收项 #4 |
| **3** | 「P0 就建新包 `ui/chat/agent/`」 | **P0 不建**。P0 三项只动 4 个文件约 5 个函数体，没有一处需要重写；新包推迟到 P1-1c | §4.1 / §5.1 |

**其他修正**：

4. **P0-3 实现简化**——`ChatToolResult` 自带 `name` 字段（`ChatModels.kt:177`），`format()` 可直接查表，**不需扩充签名**
5. **冲突面补漏**——`ChatAgentToolingTest.kt` **707 行**、20 处 `selectSkills`、3 处 `buildSystemPrompt`，是**唯一能在 `./gradlew test` 下跑的回归网**（v1.4 只算了 benchmark，而 benchmark 需要 provider/apiKey，跑不了）
6. **catalog 挂载点更正**——它拼进的是**工作流工具的 description**，不经过 `selectSkills`，与技能白名单无交集
7. **`AgentSessionContext.kt` 删除**——§3.3 撤销字段后它无内容可放
8. **P0-1 精确化**——baseStep 不是"空白 step"而是 `createSteps()` 默认值 step，痛点在 `IfModule.kt:165-208` 的分支裁剪；解法定为**两段式**（接受性看静态全集，coerce 精度动态优先）
9. **旧会话明确不迁移**

### v1.5.1（2026-09-14）—— P0 实施期修正

**P0-1 实施时发现两处需要更正，均已反映到正文**：

1. **求值口径由「静态全集」改为「并集两轮求值」**。原定「接受性看 `getInputs()` 静态全集」
   有一个反例：`CallFunctionModule` 的 `getInputs()` 只声明 `workflow_id`，
   各函数参数由 `getDynamicInputs` 依据签名凭空生成——纯静态全集会让它全丢。
   改为 `getInputs() ∪ getDynamicInputs(step)`，并做两轮（第二轮带上已接受的 `workflow_id`）。
   并集**只会多收不会少收**，不引入回归。详见 §P0-1 与 §6.2 开放问题 5。
2. **新增 §6.2 开放问题 5**：`CallFunctionModule` 的函数参数对 schema 发现机制不可见，
   P1-1 的 `query_module_schema` 必须为此设计多轮求值或二次查询路径。
   这是**改造前就存在**的缺陷（默认 step 无 `workflow_id` → 提前返回 → 参数全丢），
   不是本次引入的回归；它属于 fork 自己的功能（函数工作流）。

**P0-3 / P0-1 已实施**（提交见 `git log`）。实施细节与预期一致的：

- P0-3 比预估更简单——`ChatToolResult` 自带 `name` 字段，无需扩充 `format()` 的调用方签名
- P0-1 的静默丢弃（`?: return@forEach`）已改为显式错误，并在消息里附上可用键供模型自愈

### v1.5.2（2026-09-14）—— P0-2 实施期修正，**第一批完成**

**新增发现（本文档原未预见，已补入 §P0-2 前置⑤）**：

**`load_skill` 会被 `selectSkills` 过滤掉。** `selectSkills` 把 `availableTools` 过滤成
「命中技能声明的工具 + 常驻 helper」，而 `load_skill` 两头都不占。后果是
`<available_skills>` 清单常驻 system prompt 却调不到对应工具。

**修法**：新增 `isAlwaysExposedTool`（helper **或**按需入口），与 `isAlwaysExposedNativeHelper`
并存——后者仍用于 prompt 里 "Always-available agent-native helpers" 那段（那里说的是 helper）。

> ⚠️ **对 P1-1 的强约束**：`query_module_schema` / `call_module` 会踩**同一个坑**，
> 且后果更严重——P1-1c 撤走 59 个模块工具后，若这两个入口没进常驻，
> 模型将既没有模块工具、也拿不到查询入口。已写入 P1-1 实施要点。

**测试代价的实测修正**：原预估 3 处 `buildSystemPrompt` 断言失败，**实际只有 1 处**。
其余 12 处是**共享辅助函数**（`expectedToolNames` / `alwaysExposedNativeToolNames`）
随新工具进 `sampleTools()` 而失配，改辅助函数即可，不必逐个改断言。

**第一批状态**：P0-3 / P0-1 / P0-2 全部实施并提交，`./gradlew test` 452 通过 / 1 失败
（失败为上游预存问题，与本批次无关）。**待真机验证**：`load_skill` 是否真被模型调用、
审批是否确实不弹。

### v1.5.3（2026-09-14）—— **第二批完成**，含三处定案修正

**P1 / P2 全部实施并提交**：

| 提交 | 内容 |
|---|---|
| `579c097a` | P1-1 catalog 分层 + `query_module_schema` |
| `c7778500` | P1-1b `call_module` |
| `1afc0a8b` | P1-1c 撤 59 个模块工具 + 删 `selectSkills` |
| `07f5ee86` | P1-3B 技能清空（14 → 0） |
| `a278cbe6` | P2-2 Anthropic prompt 缓存 |

`./gradlew test`：441 通过 / 1 失败（`VObjectPropertyTest`，上游预存问题）。

**成果**：工具数 72 → 16；`ChatAgentSkillRouter.kt` 847 → 193 行；技能 14 → 0（机制保留）。

#### 修正 1：P1-3B 从「瘦身到 3–4 个」改为「清空」

原表点名保留 4 个"有实质 instructions"的技能。逐行核对后发现**它们同样是重复的**：
`saved_workflow_creation` / `temporary_workflow_execution` 的 8 条规则与工作流工具
description **逐字重复**，`generic_device_interaction` 10 行里 9 行与 prompt 重复。
14 个技能共 70 行正文，**仅 6 行独有**。详见 §4.3.1。

#### 修正 2：P1-1c 原本只写「拆手写白名单」，实际要**整体删除 `selectSkills`**

§1.1 的实测（`selectSkills` 全部作用 = 从 52 个模块工具里挑几个发出去）决定了：
59 个工具撤走后它失去全部语义。删除时连带清掉四套关键词机制共约 600 行。

#### 修正 3：P0 真机验证完成，三项设计全部通过

`load_skill` **被模型主动调用**、**不弹审批**、**正文完整**——P0-2 最核心的未知数得到证实。
同一次会话还实锤了病症 A/C：**两轮之内**（成功创建工作流 → 追问模块 schema）工具就消失，
模型自述"我能说出名字的模块全都是我凭命名习惯猜的"。详见 §4.2.1。

#### 另记：上游设计意图的追查结论

拉取上游完整历史后发现，`ChatAgentSkillRouter.kt` **从 2026-04-25 诞生就是 789 行**，
5 个月里只被改过 1 次（加 DND 技能）。**不是"先开发后弃用"，而是"职责被架空后没人清理"**——
「按场景分组暴露模块」的职责先被 `ToolRegistry` 的自动扫描接管，P1-1c 后连二次过滤也失去意义。
详见 §4.3.2。

#### 仍未闭合

**§6.2 开放问题 5**：`CallFunctionModule` 的函数参数在 `query_module_schema` 里仍不可见
（单次求值拿不到依赖 `workflow_id` 才生成的参数）。影响 fork 自己的函数工作流功能，待定解法。

**真机待验**：病症 A 复现、工具数 16、"关不掉手电筒"、`call_module` 审批、
Anthropic 缓存命中——这五项都需要真机，且 `ChatAgentModuleExecutor` 需要真 `Context`，
**从未被单测覆盖**（既有盲区）。

### v1.5.4（2026-09-14）—— 第二批真机验证 + 一个字段语义缺陷的修复

**第 1、2 项验收真机通过**（详见 §4.3.3）：

- **工具数 72 → 16**，模型自报"16 个"与代码一致
- **"关不掉手电筒"断点已治愈**——Q11 预测的场景在新版不再复现，
  根因（工具随关键词路由消失）随 `selectSkills` 删除而消失
- `query_module_schema` → `call_module` 路径完整可用
- 模型能查出 `if.start` 的 4 字段 + 19 算子（旧版答"我看不到"）

**新发现并修复一个真 bug**（`983abb1d`，详见 §4.3.4）：

P1-1c 撤走 59 个模块工具时，`query_module_schema` **只搬了字段定义，漏搬了一整类字段语义**——
`inputHints`（67 模块）、`requiredInputIds`（76）、`workflowStepDescription`（97）、
`directToolDescription`（59）。这些原本经模块工具的 JSON Schema 送给模型，
换通路后我没复用那套拼装逻辑，信息静默断流。

**真机如何暴露的**：用户问「`number_between` 需要 value1 和 value2 是 schema 写的吗」，
模型答「那是我**猜的**」——而 `IfModule` 源码里明确写着
`"value2" to "Upper bound used only by the number_between operator."`。

**修复手法**：提取 `buildModuleInputDescription` / `artifactTypeLabel` 为顶层共享函数，
两条路径同源（与 P0-1 提 `resolveModuleInputDefinitions` 一致）。

**连带定案 §6.2 开放问题 2**：**删除 `operator` 参数**。实测无效（传不同算子返回相同，
因为并集求值吃掉了动态裁剪），且修好 `inputHints` 后更无必要；
修好它反而要引入会裁剪字段的求值模式——**那正是病症 B 的成因**。

> **教训（已写入 §4.3.4）**：换信息通路时须**穷举原通路承载的全部信息**，
> 而非只搬最显眼的那部分。本次漏掉的三个字段都是「模块作者专门为 AI 写的」，
> 恰恰最不该丢。
