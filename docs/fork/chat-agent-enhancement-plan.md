# Chat Agent 能力增强方案（四点改造）

> 版本：v1.2（方案草案，含实施记录）
> 状态：部分实施 · 2026-09-12
> 目录：`docs/fork/`（fork 新增文件，上游无此文件，冲突归属我方）
> 背景依据：[`surveys/ai-system-overview.md`](surveys/ai-system-overview.md)（本项目的 AI 现状走查）、[`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md)（头部项目外部参照，**证据已升级为官方文档原文**）
> v1.1 修订：§1 增补「保留技能作为工具分组」的目标形态（厘清技能本质 = 工具分组 + 指令，改造只换选择器）；§1.4 修正原"工具不再按技能过滤"的自相矛盾表述，得出**不需要 tool search** 的结论；§2.1.1 增补「两类模块」区分（catalog 是"仅工作流步骤模块"的唯一入口）；§3.1 修正缓存与技能过滤的关系（任务内稳定 vs 跨轮断裂）。
> v1.2 修订（2026-09-12）：**改造二的「去掉 catalog 截断」与「修正不存在 id 报错」已实施**（提交 `cf9ce3d5`，已发真机验证包）。§2.2–§2.7 重排：补实施状态、实测成本（全量完整 catalog ≈5,779 token）、三档文案对照；原"紧凑清单 + describe_module"降级为 §2.6 待评估项；原"拆寄生结构"改为 §2.7。

**本文是需求/方案文档**——记录「要做什么、为什么、怎么做」。现状描述一律引用上述两份 surveys，不在此重复。

---

## 0.0 当前代码基线（新会话先读这段）

**分支**：`feature/function-workflow`

**已完成并提交的 AI 相关改动**：

| 提交 | 内容 | 影响面 |
|---|---|---|
| `cf9ce3d5` | **去除 catalog 截断** + **修正不存在 moduleId 的报错** | `ChatAgentToolRegistry.kt`、`ChatAgentModuleExecutor.kt` |
| `7639b710` | 上述改动的文档记录 | 文档 |

**具体改了什么**（新会话不必重做）：

1. `buildCompactModuleCatalog` 移除了 `maxModules` 参数与 `.take()` → catalog 现为**全量**（步骤 139 条、触发器 23 条）
2. 新增 `ChatAgentToolRegistry.isRegisteredModule(moduleId)`（O(1)）
3. 保存工作流 / 临时工作流两条校验路径改为**「先判存在 → 再判可用」**，报错文案区分"不存在 / 存在但不可用 / 加载失败"
4. ⚠️ **副作用**：保存工作流工具的 `description` 增至 **~5,779 token**（每次请求随 `tools` 重发）

**已发真机的验证包**：`app-arm64-v8a-debug.apk`（2026-09-12 经小米互传发送）

**验证状态**：`compileDebugKotlin` ✅ / `testDebugUnitTest` 411 通过（1 既有环境失败）

**尚未真机确认的**：§8 的 5 个验证场景（catalog 全量化后 AI 能否正确使用 device/core 类模块、新的报错文案是否有效）

---

## 0. 总览

四项改造，目标是把 Chat Agent 从「关键词路由 + 硬截断」升级为**「目录常驻 + 详情按需 + 前缀稳定 + 可缓存」**的架构：

| # | 改造 | 目标 | 状态 |
|---|---|---|---|
| **1** | 技能：粗粒度披露 → 目录常驻 + 详情按需 | **可发现性 + 选择权交还模型**（非 token 节省，见 §1.6） | 待实施（需先做 §1.1 三层拆分） |
| **2** | 开启 Prompt 缓存 | 降低长会话成本 | 待实施（**依赖 1 完成**，见 §3.1） |
| **3** | catalog：截断 → 全量 + 修正报错文案 | 修复"模块不可见"导致的臆造 id | **✅ 已实施**（§2.2、§2.5，提交 `cf9ce3d5`） |
| **4** | Chat 悬浮窗 | 用户可边看屏幕边对话 | **调研完成，待开发**（§4，含最佳模板与必查清单） |

**建议实施顺序：3 → 1 → 2 → 4**（见 §6）。理由：3 收益最直接、风险最低；1 是 2 的前提；4 独立可穿插。

---

## 1. 改造一：技能披露的粗粒度化

### 1.1 前置必要步骤：三层拆分（**必须先做**）

现状（详见 surveys §2.3）：技能相关内容混在一起，且**通用行为纪律硬编码在 `buildSystemPrompt` 函数体里**，不属于任何技能：

```
buildSystemPrompt()
  ├─ 24 条通用准则        ← 硬编码 :82-105，约 3,382 字符（~845 token）
  ├─ 常驻 helper 列表      ← :107-113
  └─ Active skills:       ← :114-127
        └─ title + description + instructions  ← 技能专属
```

**问题**：技能 `instructions` 里**混着大量与通用准则重复的纪律条文**。实测 `screen_observation`（1,417 字符）、`ui_interaction`（1,276）、兜底（1,248）三个技能的 instructions 特别长，内容多为「先观察」「别盲滑」「验证再结束」——与 24 条通用准则重复。

**如果直接把技能 instructions 改成按需加载，会导致**：模型在执行屏幕操作时**没有行为约束**（因为纪律还没"加载"），这是**安全前提，不可接受**。

**因此必须先拆成三层**：

| 层 | 内容 | 加载方式 |
|---|---|---|
| **L1 通用纪律** | 从 24 条通用准则 + 各技能重复部分**提炼去重** | **常驻** |
| **L2 技能目录** | 14 个技能的 `title` + `description` | **常驻** |
| **L3 技能详情** | 各技能的专属 `instructions` | **按需加载** |

**去重后 L1 可能比现状更小**——现在同一条纪律在多个技能里各存一份，选中多个技能时会重复注入。

### 1.2 改造后形态

```
system prompt = basePrompt
              + L1 通用纪律（常驻）
              + L2 技能目录（常驻，全部 14 个）
              + L3 已加载的技能详情（按需，默认 0 个）
```

模型看到全部技能目录后，**主动调用新工具**加载需要的技能详情：

```
load_skill(skillId) → 返回该技能的 instructions
```

### 1.3 新增工具

| 工具 | 参数 | 返回 |
|---|---|---|
| `vflow_agent_load_skill` | `skillId: string`（枚举为 14 个技能 id） | 该技能的完整 `instructions` |

**硬性要求**：必须有这个工具，否则 L3 就是"永久不可获取"——重演 catalog 截断的老问题（surveys §2.4.6）。

### 1.4 目标形态：保留技能作为「工具分组」，只把选择权交给模型

**先厘清技能路由的本质**（surveys §2.4.1）：

```kotlin
// 技能匹配工具的唯一规则
tool.name in skill.toolNames || tool.moduleId in skill.moduleIds
```

即**技能 = 一组工具的命名分组 + 一段专属指令**。`moduleId → 工具` 的映射是技能机制存在的全部理由。

**因此改造的定位是「换选择器」，不是「废除技能」**：

| | 现状 | 改造后 |
|---|---|---|
| 技能目录 | 不常驻（未选中的技能不可见） | **常驻**（全部 14 个的 title+description） |
| 谁选技能 | **代码**（关键词/正则匹配） | **模型**（读目录后主动 `load_skill`） |
| 技能→工具的映射 | 保留 | **保留**（映射结构不变） |
| 工具暴露 | 按选中技能过滤 | **仍按选中技能过滤** |
| 单轮可见工具数 | ≤24 | **≤24（不变）** |

> **关键结论：工具仍然按技能分批暴露，单轮可见数 ≪ 阈值。因此本改造 `不需要` 引入 tool search。**
>
> Claude Code 需要 tool search，是因为它的 MCP 工具可挂数百个**且无分组节流**；vFlow 的技能机制**本身就是节流器**，改造只是把"谁来选"从代码换成模型。

**这也让改造二（缓存）成立**：技能→工具的映射保留，但"技能目录常驻"使 system prompt 稳定；若工具集也随模型选择稳定（一次任务内通常只加载 1–2 个技能），则 `tools` 数组亦稳定 → 前缀可命中。

### 1.5 待决问题

- **技能目录是否带"何时使用"提示**？头部做法（见 [`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md) §2.2）：技能列表只放 `name`+`description`，描述需写清**何时使用**（Claude Code 单条描述上限 1,536 字符、超出时按"使用频率"降级）。建议 L2 的 description 写清适用场景。
- **已加载的技能是否跨轮保持**？建议保持（避免重复加载），但需计入上下文预算。Claude Code 的做法是 compaction 后**重新附加最近调用的技能**（每个保留前 5,000 token，共享 25,000 token 预算）。
- **技能数量增长后怎么办**？vFlow 现 14 个，title+description 仅 413 token，可无条件全量。若将来增长，可参考 Claude Code 的**"预算 + 按使用频率降级 + 保底名称"**策略（而非按位置硬切）。

### 1.6 预期收益

| 项 | 现状 | 改造后 |
|---|---|---|
| 常驻技能相关 | 24 准则(845) + 选中技能 instructions(399~798) | L1(去重后估 ~600) + L2(413) |
| 按需 | — | L3（仅模型主动加载的） |
| **可发现性** | 未选中的技能模型不可见 | **全部 14 个可见** |
| **选择权** | 代码关键词匹配 | **模型自主** |

> **收益的准确定位**：主要收益是**可发现性 + 模型自主权**，不是 token 节省（实测选中 3 个技能的 instructions 仅 ~400 token）。改造的正当理由是"关键词没命中时，模型无从补救"（surveys §5.4 的 toast 案例即此类失败）。

---

## 2. 改造二：catalog 全量化（已实施）+ 报错文案修正（已实施）

### 2.1 现状问题

`catalog` 把 **139 个步骤模块硬截断到 48**（surveys §2.4.6），被切掉的 91 个中 **device 39 + core 23** 整体消失——包括 toast、振动、shell、剪贴板、音量等高频操作。

**更严重的是「能力被系统性扭曲」**：窗口内 48 个是 interaction 全 12、logic 全 9、data 全 23、file 4；窗口外是 device/core/ui/network 全切。模型看到的 vFlow 是一个「能算 AES 能解析 XML、但不能弹 toast 不能 shell」的数据处理工具。

后果：模型知道 id 合法（在 `enum` 里）却看不到说明，遂臆造 id 被拒（surveys §5.4 实证）。

### 2.1.1 关键前提：catalog 是「仅工作流步骤模块」的唯一入口

模块分两类（surveys §2.4.2）：

| 类别 | 数量 | 能否直接调 | 唯一入口 |
|---|---|---|---|
| **直接工具** | **59** | ✅ 可（有 `directToolMetadata`） | 路径 1（技能匹配到工具，**自带 schema**） |
| **仅工作流步骤** | 白名单内其余 | ❌ 不可 | **路径 3/4（catalog）** |

**实测**：技能引用的 52 个模块中，51 个本身已是"直接工具"——即技能暴露的模块模型本就能直接调。

**所以**：`vflow.data.aes`、`vflow.logic.loop.start` 这类模块**没有别的路径可发现**。catalog 一旦截断，它们就**彻底不可见**。这是 §2 的必要性所在。

> 另注：catalog 只在**路径 3/4（工作流工具被技能选中）**时才发送；路径 1/2 拿到的是工具 schema，不需要 catalog。catalog 的"寄生"结构（挂在两个工作流工具的 `description` 里）本身也是待拆重点——详见 §2.5。

### 2.2 改造方案

**分两步走**（第一步已实施，第二步待定）：

```
① 去掉截断，catalog 变全量          ← ✅ 已实施（2026-09-12）
② 新增 vflow_agent_describe_module  ← 待评估（见 §2.6）
```

**① 去掉截断**（已实施，提交 `cf9ce3d5`）：

- `buildCompactModuleCatalog` 移除 `maxModules` 参数与 `.take(maxModules)`
- 三处调用点全改为全量：临时工作流（原 40）、保存·触发器（原 24）、保存·步骤（原 48）
- 保留单条内部的 `inputs.take(6)` 与 `buildModuleOutputCatalog` 的 `take(8)`——**不同维度的限制**，与模块截断无关

### 2.3 成本实测（含实施后真实值）

| 方案 | 字符 | 估算 token |
|---|---|---|
| 改前：截断到 48 条 | ~4,000 | ~1,330 |
| **改后：全量完整 catalog（已实施）** | 步骤 14,989 + 触发器 2,348 | **步骤 ~4,996 + 触发器 ~782 ≈ 5,779** |
| 备选：全量紧凑清单（id+名称） | 5,571（162 个模块） | ~1,857 |

**关键权衡**：已实施方案（全量完整）比紧凑清单**多约 3,900 token**，但**保留了每条模块的名称、描述、参数键**——即模型不用再调 `describe_module` 就能直接生成工作流。

> **这是有意的取舍**：先以最小改动（纯删除）验证"全量可见"能否解决问题。若 token 成本不可接受，再降级到紧凑清单 + §2.6 的按需详情。

### 2.4 实现提示：`describe_module` 的数据源

**可直接复用 API 层已有能力**——本地 Web 服务已有 `GET /modules/{id}/input-schema` 与 `/modules/{id}`（`api/handler/ModuleHandler.kt`）。**不必重写**，抽出共用服务即可。

### 2.5 配套修正（已实施）：修正「不存在的模块 ID」报错（2026-09-12）

**问题**：模型编造 moduleId（如 `vflow.ui.toast`）时，报错为

```
Saved workflow step 1 uses module `vflow.ui.toast`, which is not exposed to the chat agent for saved workflows.
```

**这会让模型误以为模块存在、只是未授权**，从而反复重试同一个无效 id。

**根因不只是文案**——`isSavedWorkflowModuleAllowed` 用白名单集合查询，**不存在的 id 同样返回 false**，导致"不存在"永远走"未暴露"分支，其后的 `unregistered` 分支**根本不可达**。

**修法**（提交 `cf9ce3d5`）：

1. 新增 `ChatAgentToolRegistry.isRegisteredModule()`（O(1)，委托 `ModuleRegistry.getModule()`）
2. 两条路径（保存工作流 / 临时工作流）均改为**「先判存在 → 再判可用」**
3. 文案区分三种情况：

| 情况 | 新提示 |
|---|---|
| **不存在** | ``...does not exist. No such module is registered in vFlow. Do not retry this id; pick a real module id from the enum in the tool schema.`` |
| 存在但不可用 | ``...exists but is not available for saved workflows. Pick a different module id from the enum...`` |
| 存在但加载失败 | ``...could not be loaded. Pick a different module id...`` |

**设计要点**：明确告知"不存在"+"不要重试这个 id"+"从 enum 里另选"——把错误的**可操作性**给模型。

### 2.6 待评估：是否是 `describe_module` 与「紧凑清单」

若 §2.3 的全量完整 catalog（~5,779 token）成本偏高，可降级为：

- **紧凑清单**（id + 名称，~1,857 token）常驻
- 新增 `vflow_agent_describe_module(moduleId)` 按需取详情（描述、参数、必填、输出）

**待定**：先用真机验证"全量可见"是否已解决问题，再决定是否需要这一步。

### 2.7 建议：拆掉 catalog 的「寄生」结构

**现状**：catalog 不是独立机制，而是**拼在两个工作流工具的 `description` 字符串里**（surveys §2.4.1）：

```
vflow_agent_run_temporary_workflow.description = "...说明..." + stepCatalog(100条)
vflow_agent_save_workflow.description          = "...说明..." + triggerCatalog(23) + stepCatalog(139)
```

**三个问题**：

| 问题 | 说明 |
|---|---|
| **粒度错配** | "有哪些模块"是**全局知识**，却被绑在**特定工具**上 |
| **可见性依赖** | 只有这两个工具被技能选中时，模块清单才可见 |
| **改造牵动全局** | catalog 的体积直接撑大工具 `description`，且随模块数增长 |

**建议**：把模块目录**独立出来**，不再寄生。可选：

- **方案 A（推荐）**：模块清单**常驻系统提示**，`describe_module` 按需取详情。好处：无论走哪条路径，模型都完整知道有哪些模块。
- **方案 B**：完全按需，用 `search_modules` / `describe_module` 取。更省 token，但多一轮往返。

> **对照头部做法**：Anthropic 官方对**技能**是"name+description 全量预载 + 正文按需"（见 [`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md) §2.2）。vFlow 的模块可类比技能——**轻量清单常驻，重量详情按需**。
>
> ⚠️ 但注意：全量完整 catalog 现在让保存工作流工具的 `description` 达到 ~5,779 token，**每次请求都要重发**（工具描述随 `tools` 数组下发）。若后续要控制成本，§2.6/§2.7 是主要方向。

---

## 3. 改造三：开启 Prompt 缓存

### 3.1 与改造一的关系（**关键）**

```
缓存命中 = 前缀字节稳定
技能路由 = 可能每轮切换 tools 数组 → 前缀断裂
```

**当前实现下缓存命中率低是结构性的**：用户每发一句新消息，可能命中不同技能 → `tools` 变化 → 前缀从 `tools` 处断裂（OpenAI 系请求体中 `tools` 位于 `messages` 之前，见 surveys §2.10.4）。

**改造一完成后情况改变**（注意 §1.4：**工具仍按技能过滤**，只是选择权交给模型）：

- L1/L2 常驻 → system prompt 的技能段**不再随轮次变化** ✅
- 一次任务内通常只加载 1–2 个技能 → `tools` 数组**在任务内稳定** ✅
- 但**跨"用户新消息"仍可能切换技能** → 跨轮仍会断裂

> ⚠️ **所以改造二是改造一的下游**：改造一让**任务内**前缀稳定（这是主要收益），但**跨轮**的断裂是"技能分组"这一设计的固有代价——除非将来取消技能分组、让工具全量常驻（但那样会撞 30-50 阈值，见 §1.4 讨论）。

### 3.2 具体动作

| 供应商 | 动作 |
|---|---|
| **Anthropic** | **必须显式加 `cache_control: {type: "ephemeral"}`**——当前全仓库零命中（surveys §2.10.4），这是最大的一块白扔。建议标记 `system` 与 `tools` 两个块 |
| OpenAI Chat Completions / Responses | 自动前缀缓存，无需显式标记；但需保证前缀稳定（依赖改造一） |
| 其它（DeepSeek/Ollama 等） | 视各家支持情况，多数兼容 OpenAI 自动前缀缓存 |

> ⚠️ **若将来引入"按需加载工具 schema"**（类似 tool search），注意官方约束：**`defer_loading: true` 的工具不能同时带 `cache_control`**（API 返回 400）。缓存断点要放在**非延迟**工具上（见 [`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md) §2.1）。
> 本方案 §1.4 已论证**暂不需要 tool search**，故当前无此冲突；但若后续技能数量或工具数增长到需要，需一并考虑。

### 3.3 验证方式

用响应里的 `usage.cached_tokens`（或 Anthropic 的 `cache_read_input_tokens`）确认命中率，做改造前后对比。

---

## 4. 改造四：Chat 悬浮窗

> **本节于 2026-09-12 重写**：补充实现层关键事实（数据/状态承载、IME、Compose 宿主），供新会话直接开发。原版仅列了复用资产，不足以开工。

### 4.1 需求

当前调试页面时必须离开 Chat 页，无法边看屏幕边对话。需要一个悬浮窗形态的 Chat。

**验证场景**：用户让 AI 操作某个 App（如"帮我把设置里的深色模式打开"），需要**边看 AI 操作、边补充指令**，而不是在 Chat 页和设置页之间来回切。

### 4.2 核心难点：Chat 状态如何跨 Activity 存活（**先解决这个**）

这是本改造的**真正难点**，不是窗口绘制。

**现状**：

```kotlin
// ChatScreen.kt:171 —— 通过 Activity 作用域的 ViewModel 取
chatViewModel: ChatViewModel = viewModel()

// MainComposeShell.kt:199 —— 同上
val chatViewModel: ChatViewModel = viewModel()

// ChatViewModel 是 AndroidViewModel
class ChatViewModel(application: Application) : AndroidViewModel(application)
```

**问题**：`viewModel()` 默认绑定**最近的 `ViewModelStoreOwner`**（即 Activity）。这意味着：

- Chat 状态**随 Activity 存活**，退出 App 就没了
- 悬浮窗若从 Service 弹出，**拿不到同一个 `ChatViewModel` 实例**
- 盲目新建一个 VM 会造成**两套会话状态**（悬浮窗里说的话，回到 Chat 页看不到）

**可选方案**（需权衡，新会话决策）：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. Service 托管 VM** | 把 `ChatViewModel` 的宿主提到前台 Service，App 与悬浮窗共用 | 改动大；VM 生命周期需重设计 |
| **B. 单例/Application 作用域** | 用 `AndroidViewModelFactory` + 自定义 `ViewModelStoreOwner`（挂在 Application 或 Service） | 中等；需处理清理 |
| **C. 悬浮窗只读 + 转发** | 悬浮窗不持有 VM，通过 `ServiceStateBus` 之类的总线与 App 内 VM 通信 | 最小；但功能受限（详见 §4.5） |
| **D. 独立 VM + 状态同步** | 悬浮窗持有自己的 VM，与主 VM 通过持久层（`ChatPresetRepository` 的会话存储）同步 | 状态一致性难保证，不推荐 |

> **建议先做 C**：悬浮窗作为「**遥控器**」——发指令、显示最近回复，真正的会话状态仍由 App 内的 VM 持有。这样能最快验证交互价值，且不触碰 VM 架构。
>
> 注意：vFlow 已有 `services/ServiceStateBus.kt`，可能可作为通信总线，**需先看它的现有用途**。

### 4.3 可复用资产（已核实，2026-09-12）

**⭐ 最佳参考是 `ui/float/WorkflowsFloatPanelService.kt`（514 行）** —— 一个**功能完备的悬浮窗 Service**，已实现：

| 能力 | 实现 |
|---|---|
| 窗口创建 | `WindowManager` + `TYPE_APPLICATION_OVERLAY`（O 以下回退 `TYPE_PHONE`） |
| **折叠/展开** | `floatView` ↔ `collapsedView` 两套布局（`workflows_float_panel.xml` / `..._collapsed.xml`） |
| **侧边停靠** | `collapseToSidebar()` 吸附左右边缘（`attachToRight` 判断） |
| **拖动** | `setupDragBehavior()` + `observeViewPosition()` |
| **自动收起** | `startAutoCollapseTimer()` |
| **长按关闭** | `setupCloseHoldBehavior()` + 环形进度指示 |
| 生命周期 | 标准 `Service`，`onDestroy` 清理 |

**⚠️ 但它有两个关键限制，不能直接照抄**：

| 限制 | 证据 | 影响 |
|---|---|---|
| **不可输入** | `FLAG_NOT_FOCUSABLE`（`:121`、`:293`） | 聊天窗**必须去掉**，且要处理软键盘 |
| **内容是 XML View** | `LayoutInflater.inflate(R.layout.workflows_float_panel)` + `RecyclerView`（`:110`、`:137`） | 与 Compose 版 `ChatScreen` 不同技术栈 |

**其它资产**：

| 资产 | 说明 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | ✅ 已声明（`AndroidManifest.xml:18`） |
| `ui/overlay/AgentOverlayManager.kt`（374 行） | 链路 C 视觉 Agent 的状态浮窗；也是 `FLAG_NOT_FOCUSABLE`（`:83`、`:183`） |
| `ui/float/DynamicFloatWindowService.kt`（17KB） | 另一个悬浮窗 Service，**待查**（可能支持动态内容） |
| `services/OverlayUIActivity.kt`（820 行） | 一个 Activity（非 Service），**用途待查** |

### 4.4 关键约束

| 约束 | 细节 | 应对 |
|---|---|---|
| **必须可输入** | 现有两个浮窗都是 `FLAG_NOT_FOCUSABLE` | 去掉该 flag；**但去掉后悬浮窗会抢焦点**，需权衡是否用「点击才聚焦」策略 |
| **IME 弹出时位置** | 悬浮窗默认不随软键盘上移 | 监听 `WindowInsets.ime` 手动调整，或用 `adjustResize` |
| **遮挡被调试界面** | 本质矛盾：要能看又不能挡 | 已有「折叠+侧边吸附+半透明」的现成实现可复用 |
| **Compose vs View** | `ChatScreen` 是 Compose（2,223 行），浮窗示例都是 XML | 二选一：① `ComposeView` + 手动提供 `ViewTreeLifecycleOwner`/`SavedStateRegistryOwner`；② 做精简 View 版聊天条 |
| **拖动 vs 点击** | 浮窗需可拖动，内部控件要能点击 | `WorkflowsFloatPanelService` 已有实现可参考 |

### 4.5 建议的最小可用形态（MVP）

**不要一上来做完整聊天窗**。先做「**悬浮状态条 + 快速输入框**」：

```
┌─────────────────────────────────┐
│ ● AI: 正在观察界面…        ⚙ 展开 │  ← 窄条，显示最近一条消息/状态
├─────────────────────────────────┤
│ [ 输入补充指令…           ] [发送] │  ← 输入框
└─────────────────────────────────┘
```

- **默认折叠**：只显示状态条（不遮挡）—— 直接复用 `WorkflowsFloatPanelService` 的折叠+吸附逻辑
- **可拖动**：同上，已有实现
- **点击展开**：展开完整对话（复用 `ChatScreen` 或简化版）
- **半透明**：进一步减少遮挡

### 4.6 潜在价值（超出原始需求）

如果 Chat 能在悬浮窗运行，它就从「App 内功能」变成「**全系统助手**」——配合现有无障碍能力，可边看任何 App 边操作。这比前三点更接近产品差异化。

### 4.7 开发前必查清单（给新会话）

**先看这几个（按优先级）**：

1. **`ui/float/WorkflowsFloatPanelService.kt`** —— **最佳模板**。重点看 `showFloatWindow()`、`setupDragBehavior()`、`collapseToSidebar()`、`setAutoCollapseTimer()`
2. **`ui/float/DynamicFloatWindowService.kt`（17KB）** —— 待查，可能支持动态内容（更接近聊天窗需求）
3. **`ChatScreen.kt:166-190`** —— 现有 Chat 的入参与状态订阅方式（`chatViewModel.uiState.collectAsState()`）
4. **`MainComposeShell.kt:199`** —— Chat 的 VM 获取与入口
5. `services/OverlayUIActivity.kt`（820 行）—— 待查用途
6. `AgentOverlayManager.kt` —— 次级参考（`WindowManager` 用法）

**必做的技术验证**（开工前）：

- [ ] 去掉 `FLAG_NOT_FOCUSABLE` 后，悬浮窗能否正常接收输入、软键盘是否正常弹出
- [ ] `ComposeView` 在 Service 中能否正常渲染（Lifecycle/SavedStateRegistry 手动提供的坑）
- [ ] VM 共享方案（§4.2）选定并验证状态一致

---

## 5. 架构关系图

```
改造一（技能：L1/L2 常驻 + L3 按需）
    │
    ├──> 让 system prompt 稳定 ──┐
    │                            ├──> 改造二（缓存生效）
    └──> 让 tools 数组稳定 ──────┘
    
改造三（catalog 全量 + describe_module）
    └── 与改造一的"按需加载"机制同构，可共用实现

改造四（悬浮窗）
    └── 完全独立，基础设施已备
```

---

## 6. 实施顺序建议

| 顺序 | 改造 | 理由 |
|---|---|---|
| **1** | **改造三**（catalog + `describe_module`） | 收益最直接（修掉正在踩的坑）、改动最小、风险最低 |
| **2** | **改造一**（三层拆分 + 技能目录化 + `load_skill`） | 与改造三共用"按需加载"机制；是改造二的前提 |
| **3** | **改造二**（缓存） | 前两步做完，前缀才真正稳定 |
| **4** | **改造四**（悬浮窗） | 独立，可随时穿插 |

**四项合并看**，是一次统一的架构升级：**「目录常驻 + 详情按需 + 前缀稳定 + 缓存开启」**——与头部项目方向一致（surveys/agent-design-comparison.md）。

---

## 7. 风险与开放问题

| # | 问题 | 说明 |
|---|---|---|
| 1 | **工具全量常驻的副作用** | §1.4 若取消技能过滤，`tools` 从按技能子集变为全量 72 个。头部经验：工具超 30-50 个后**选择准确率下降**（surveys §2.4）。需实测评估 |
| 2 | **L1 纪律提炼的正确性** | 从 24 条 + 各技能 instructions 去重提炼，**不能丢任何安全约束**。建议逐条对照现有准则，改动需人工评审 |
| 3 | **按需加载的往返成本** | 每次 `load_skill`/`describe_module` 增加一轮往返。头部经验（Claude Code）：工具少时全量反而更快（surveys §2.4）。需设"值不值得按需"的阈值 |
| 4 | **已加载内容的生命周期** | 加载过的技能/模块详情是否跨轮保持？如何计入上下文预算？需定义 |
| 5 | **悬浮窗的 Compose 承载** | 见 §4.3，是已知的 Android 实现难点 |
| 6 | **与现有 API 的关系** | `describe_module` 应复用 API 层能力（§2.4），避免"API 与 Agent 各写各的"重演（surveys §2.10 已记录该浪费） |
| 7 | **全量 catalog 的 token 成本**（v1.2 新增） | 已实施的全量完整 catalog ≈ **5,779 token**（步骤 4,996 + 触发器 782），比改前多约 4,449 token，且随 `tools` 数组**每次请求重发**。若成本不可接受，降级方案见 §2.6 |

---

## 8. 已实施改动的真机验证清单（2026-09-12）

**提交 `cf9ce3d5`** 对应的验证点（构建 `app-arm64-v8a-debug.apk` 已发真机）：

| # | 验证场景 | 预期 |
|---|---|---|
| 1 | 让 AI「建个工作流，里面弹个 toast」 | catalog 现含 `vflow.device.toast`（原排第 66 被切）→ 应能正确识别并使用，不再臆造 `vflow.ui.toast` |
| 2 | 观察 AI 若仍臆造 id 时的错误提示 | 应显示 ``does not exist. No such module is registered in vFlow. Do not retry this id; pick a real module id from the enum...`` 而非原来的 "not exposed" |
| 3 | 检查 AI 收到错误后能否自我纠正 | 期望下一轮改用 enum 中的合法 id，而非重复同一个无效 id |
| 4 | 让 AI 建含**高频设备操作**的工作流（振动、打电话、shell） | 这些原本全被切掉（device 39 + core 23），现应可见 |
| 5 | 长对话（多轮）观察 token/成本 | 全量 catalog 使工具描述变大，需确认是否带来可感知的成本或超限问题 |

> **注**：debug 包与 release 包签名可能不同，安装前可能需卸载旧版（会清空数据，建议先备份工作流）。

---

## 9. 相关文档

| 文档 | 关系 |
|---|---|
| [`surveys/ai-system-overview.md`](surveys/ai-system-overview.md) | **本项目 AI 现状**——本方案的事实依据（§2.3 技能、§2.4.6 catalog 截断、§2.10.3 短板、§2.10.4 缓存） |
| [`surveys/agent-design-comparison.md`](surveys/agent-design-comparison.md) | **外部参照**——头部项目的工具暴露/渐进披露/缓存做法 |
| [`function-workflow.md`](function-workflow.md) | 函数工作流（已完成，非本方案范围） |
