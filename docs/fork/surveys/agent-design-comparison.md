# 头部 Agent 项目的工具暴露与上下文设计对照（外部调研）

> 版本：v1.0
> 状态：外部调研（非本项目代码走查），2026-09-11
> 目录：`docs/fork/surveys/`（fork 新增文件，上游无此文件，冲突归属我方；索引见 [`README.md`](README.md)）
> 用途：给 vFlow 的 AI 能力优化提供**外部参照**——头部 Agent 项目在「让模型知道有什么工具、给多少细节、怎么控制上下文」上是怎么做的。

**证据来源**：Claude Code 官方文档 + 社区抓包分析；Hermes Agent（`NousResearch/hermes-agent`）仓库源码；Codex CLI 仓库公开材料。
**可信度提示**：Claude Code 部分含社区逆向结论（原文自述为「结构性观察」）；Hermes 部分为直接读源码；Codex 公开材料有限。凡引用均标注来源，未证实处会明说。

---

## 0. 为什么做这份对照

本项目 `ai-system-overview.md` 走查出一个具体缺陷：**模型能拿到全量合法 moduleId（schema `enum`），但只有前 48 个有说明（catalog 截断）**，导致它会去猜一个"看起来合理"的 id（如 `vflow.ui.toast`）而被拒。

这不是 vFlow 独有的问题——「工具多到塞不下上下文」是所有 Agent 都要面对的。本文看头部项目怎么解，作为 vFlow 的设计参照。

---

## 1. 问题定义：工具规模增长带来的两难

Claude Code 官方文档把问题讲得最直白（[Scale to many tools with tool search](https://code.claude.com/docs/en/agent-sdk/tool-search)）：

- **上下文效率**：50 个工具的定义就要占 **10–20K token**，挤占真正干活的空间。
- **选择准确率**：**工具超过 30–50 个后，模型的选择准确率会下降**。

所以核心矛盾是：**给全了塞不下，不给全模型选不对。**

---

## 2. Claude Code：按需搜索加载（Tool Search）

**这是三家唯一把「渐进式披露」做成一等机制的。**

### 机制

| 维度 | 做法 |
|---|---|
| 默认行为 | **工具定义不下发上下文**；模型只拿到**工具名清单 + 可用工具类别说明** |
| 发现方式 | 模型调用专门的 `ToolSearch` 工具，默认加载**最相关的 5 个** |
| 查询语法 | 至少三种模式：`+keyword`（关键词）、`select:name1,name2`（按名精确选）等 |
| 时序 | `tool_reference` 返回的工具**当轮不可用**，进**下一轮请求**的 `tools` 数组 |
| 触发阈值 | 可配 `ENABLE_TOOL_SEARCH=auto:5`——可延迟定义达上下文 **5%** 时启用 |
| 官方定位 | `ToolSearch` 是**守门人**："deferred tools are NOT available until you load them" |

### 关键设计判断（值得抄）

1. **有阈值，不是无条件用**：官方明确说「**少于 ~10 个工具、定义放得下时，全量加载更快**」。渐进式披露是为「工具成百上千」准备的。
2. **目录 + 详情分层**：系统提示里放**工具类别说明**（"You can search for tools to interact with Slack, GitHub, and Jira"），让模型知道"该搜什么关键词"——**这是缺失的中间层**。
3. **可获取性保证**：被延迟的工具**永远能通过搜索拿到**，不是丢弃。

### 实测收益

官方文章给的对比：50+ MCP 工具场景下，全量加载约 **77K token** → 按需约 **8.7K token**（ToolSearch 自身 ~500 + 按需 3-5 个工具 ~3K），**降低 85%+**。

### 与 vFlow 的差异（关键）

社区抓包分析指出一个有意思的细节：Claude Code **没有用** Anthropic 官方的服务端 tool search（`defer_loading: true`），而是**自己在编排层实现**——候选 schema 不进初始 `tools` 数组，由编排层在 `ToolSearch` 返回后注入下一轮。这个「编排层负责展开」的模式，对 vFlow 这种自建请求层的项目更有参考价值。

---

## 3. Hermes Agent（NousResearch）：工具集分组 + 双层上下文压缩

`NousResearch/hermes-agent`，Python 实现，体量很大（`run_agent.py` 单文件 ~79KB）。

### 3.1 工具暴露：Toolset 分组 + 显式启用

```python
_BASIC_TOOLSETS = {"web", "terminal", "vision", "creative", "reasoning"}
_COMPOSITE_TOOLSETS = {"research", "development", "analysis", "content_creation", "full_stack"}
```

- 用 `--enabled_toolsets=research` / `--disabled_toolsets=terminal` **显式选择暴露哪些工具组**；
- 有 **`context_engine`** 工具组："Runtime tools exposed by the active context engine"——**工具随运行上下文动态挂载**；
- 有 **subagent 工具**："Spawn subagents with isolated context for complex subtasks"——**上下文隔离是一等能力**；
- 工具集解析带 **memo 缓存**，注释特意写明"cache entry is valid for as long as the generation is unchanged"——**明确考虑缓存失效边界**。

### 3.2 上下文压缩：服务端 + 本地双层（`agent/native_compaction.py`）

```python
# 原生压缩：借用 OpenAI Responses 的服务端能力
# context_management=[{"type": "compaction", "compact_threshold": N}]
DEFAULT_COMPACT_THRESHOLD = 200_000
LOCAL_TRIGGER_SAFETY_MARGIN = 8_192   # 本地触发线比服务端阈值低这么多，让服务端先出手
```

设计要点：

1. **服务端原生压缩优先**，本地压缩器作为**兜底**；
2. 本地阈值**特意钳制在服务端阈值之下**，保证服务端"先开第一枪"；
3. **能力探测而非硬赌**：只对**验证过的模型**启用（gpt-5.6 + 直连 OpenAI 路由 / Codex 后端），其它情形明确不走；
4. 有 kill switch 可关（`agent.codex_responses_native_compaction = False`）。

---

## 4. Codex CLI：工具少而稳 + 上下文精打细算

Codex 的公开 `docs/config.md` 主要暴露配置层，工具设计偏内部。从其仓库行为可见的路线：

- **AGENTS.md 感知环境**（有 commit 专门"make AGENTS.md react to environment changes"）——把项目约定注入上下文；
- 检索命中大量 **compaction / 上下文压缩** 相关提交——这是其核心议题；
- 工具集**相对固定、数量可控**（非 MCP 那种爆炸式扩张），因此**不需要工具搜索**。

**对照意义**：Codex 代表另一条路线——**"工具少而稳 + 上下文精打细算"**。说明渐进式披露**不是必需品**，取决于工具规模。

---

## 5. 三家的共识与分歧

| 维度 | Claude Code | Hermes Agent | Codex CLI |
|---|---|---|---|
| 工具暴露 | 按需搜索加载 | toolset 分组 + 显式启用 | 固定精简集 |
| 渐进式披露 | ✅ 一等机制 + 阈值 | ✅ 分组 + context_engine | ❌ 不需要 |
| 目录/详情分层 | ✅ 类别说明 + 按需详情 | ✅ toolset 描述 | — |
| 上下文压缩 | ✅ 有 compaction | ✅ **服务端 + 本地双层** | ✅ 核心议题 |
| 能力探测/降级 | ✅ 有 unsupported 列表 + 回退 | ✅ 验证模型白名单 + kill switch | — |
| Prompt 缓存 | ✅（Anthropic 原生） | ✅ 显式考虑失效边界 | — |

### 三条提炼出的共识

1. **「目录」与「详情」要分层**，且**详情必须可获取**。
   Claude Code 用"类别说明 → 搜索 → 加载"；Hermes 用"toolset 描述 → 启用"。**没有任何一家把详情永久截断**。

2. **上下文压缩是必备基础设施**，不是可选项。
   三家都有 compaction。成熟做法是**分层**（服务端 + 本地兜底），而不是单一策略。

3. **能力探测 + 优雅降级**是通用模式。
   平台不支持就回退到全量加载（Claude Code）、模型没验证过就不启用原生压缩（Hermes）——**不硬赌、不静默失败**。

---

## 6. 对 vFlow 的启示

对照本项目现状（详见 [`ai-system-overview.md`](ai-system-overview.md) §2.3、§2.10）：

| vFlow 现状 | 头部做法 | 差距性质 |
|---|---|---|
| 技能路由（14 组）动态选工具 | Hermes 的 toolset 思路同源 | ✅ 方向一致 |
| 技能路由是**系统替模型猜**，模型无法主动索取 | Claude Code 是**模型主动搜** | ⚠️ 交还控制权 |
| **enum 全量 id / catalog 截断到 48** | 目录与详情分层，详情可获取 | ❌ **缺中间层，且详情永久丢失** |
| 无按需获取模块详情的工具 | `ToolSearch` / `context_engine` | ❌ 缺关键机制 |
| 上下文**全量 forEach，零裁剪** | 三家均有 compaction | ❌ 缺基础设施 |
| 未启用 `cache_control` | Anthropic 原生 + Hermes 显式考虑 | ❌ 白扔收益 |

### 可直接借鉴的三点

1. **补「目录」这一层**：给全量模块一份**轻量摘要**（id + 一句说明），需要时再取详情。这直接修掉 vFlow 的 catalog 截断问题。
2. **加一个 `describe_modules` 类工具**（或让现有工作流工具支持"查询模块详情"），让模型能主动获取被省掉的参数信息——这是 vFlow 最缺的机制。
3. **上上下文压缩**：至少先做本地裁剪（token 预算 + 历史摘要），参照 Hermes 的"本地兜底 + 后续可接服务端"路径。

> 注意 Claude Code 的提醒：**<10 个工具时全量加载更快**。vFlow 直接把 72 个工具全量下发也未必是最优——但**渐进式披露要配套"可获取"机制**，否则就退化成现在的硬截断。

---

## 附录：来源

- [Scale to many tools with tool search — Claude Code Docs](https://code.claude.com/docs/en/agent-sdk/tool-search)
- [How Claude Code Tool Search Works: On-Demand Tool Loading into Context — Weng Jialin](https://wengjialin.com/blog/claude-code-tool-search/)
- [Tool search in the API — Anthropic Platform Docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
- [NousResearch/hermes-agent — GitHub](https://github.com/nousresearch/hermes-agent)
  - `run_agent.py`、`toolsets.py`、`model_tools.py`、`agent/native_compaction.py`
- [OpenAI Codex — GitHub](https://github.com/openai/codex)
