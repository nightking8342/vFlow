# vFlow 的 AI 体系梳理（fork 参考文档）

> 版本：v1.2
> 状态：代码走查定稿（对应 `feature/function-workflow` 分支，2026-09-11）
> v1.1 修订：修正 §5.2/§5.3/§6 的 `call_function` 根因（原文写反）、附录 A 三处统计数字、§4 模块 id 与遗漏、§2.6 示例值、§2.1 行号，以及若干措辞/文件归属问题。**本文数字为人工走查所得，非脚本自动生成**（见 §0）。
> v1.2 修订：重写 §0 定位；新增 §2.10「链路 A 能力与现状评估」、§3.1「链路 B 能力与现状评估」（含 A/B 对比表）、§4.1「链路 C 现状评估」、§7「优化方向汇总」；§1 补阅读指引。
> 目录：`docs/fork/surveys/`（fork 新增文件，上游无此文件，冲突归属我方；同目录另见 [`README.md`](README.md) 索引）
> 用途：**梳理当前项目 AI 系统的现状**——三条链路各自是什么、能看到什么、通过什么机制、强在哪、短在哪，**方便后续优化与扩展**。

---

## 0. 为什么写这份文档

本文的目标是**给项目现有的 AI 系统画一张准确的现状图**，作为后续优化和扩展的起点。

起因是一次具体排查：本 fork 新增「函数工作流」后，发现 **AI 聊天面板完全感知不到新模块**。追查过程中需要反复确认「AI 到底能看到什么、通过什么机制、为什么这里行那里不行」。这类问题每次都要重走一遍代码，成本很高，因此把整套机制固定成文。

本文回答三个问题：

1. **有哪些 AI 链路**（§1）——彼此独立，最易混淆。
2. **每条链路具体能做什么、怎么看世界**（§2–§4）——提示词、工具、scope、执行、审批、参数转换。
3. **每条链路的强项与短板在哪**（§2.10、§3.1、§5）——供后续优化选点。

**边界**：本文描述**现状**，不写需求决策、不写改造方案。凡属「现状如此」的结论都附代码位置；凡属「可以更好」的判断集中在 §2.10/§3.1，与事实描述分开。

**重要**：本文数字为**人工走查**代码后统计得出，口径见附录 A。仓库内**没有**配套的自动统计脚本，因此数字会随代码漂移；引用前请以代码为准。（v1.0 曾声称"由脚本静态解析得出"，与实际不符，v1.1 已更正。）

**范围声明**：本文的「AI」指**调用 LLM 出网**的链路。项目内的本地模型能力——OCR（`ocr/PpOcrV5*`，PP-OCRv5/ncnn）、语音（`speech/SherpaNcnn*`、Silero VAD）——**不属本文范围**（本地推理、无 LLM 出网），仅在作为工具被聊天 Agent 调用时出现在 §2.4 的模块清单里。全仓库 LLM 出网点共 4 处：`ChatCompletionClient`（链路 A）、`WorkflowAiGenerator`（链路 B）、`AIModule` + `AgentModule`/`AutoGLMModule`（链路 C），**不存在第四条链路**。

---

## 1. 三套互相独立的 AI 链路

项目里有三条 AI 链路，**彼此不共享提示词、不共享工具定义**，最容易混淆：

| # | 链路 | 入口文件 | 谁驱动 | 能力范围 |
|---|---|---|---|---|
| **A** | 聊天 Agent | `ui/chat/` | LLM 自主多轮调工具 | 观察屏幕、点按、读写剪贴板、跑工作流 |
| **B** | AI 生成工作流 | `ui/workflow_editor/WorkflowAiGenerator.kt` | 单轮 LLM 生成 JSON | 生成一份工作流配置（生成动作本身不执行工作流；生成结果写入编辑器后由用户保存、可正常执行） |
| **C** | 工作流内 AI 模块 | `core/workflow/module/{network,interaction,integration}/*.kt` | 工作流执行时调 LLM（或外部 AI 助手） | 文本问答、视觉 Agent 操作手机、调用外部 AI 助手（Operit） |

**关键区别**：链路 A 和 B 完全不同 —— A 用 `aiMetadata`（模块自声明），B 用 `metadata.description`（遍历全模块）。给 A 加能力**不会**自动让 B 看到，反之亦然。

> **阅读指引**：§2 逐节描述链路 A 的机制（事实），§2.10 是其**能力与现状评估**（主观判断）；§3 描述链路 B，§3.1 是其评估与 A/B 对比；§4 是链路 C。

---

## 2. 链路 A：聊天 Agent（重点）

### 2.1 组件依赖

```
ChatViewModel(application)                                  ChatViewModel.kt:58
  ├─ ChatAgentToolRegistry(application)                     ChatAgentToolRegistry.kt:48
  ├─ ChatAgentModuleExecutor(application, toolRegistry)     ChatAgentModuleExecutor.kt:162
  │     └─ ChatAgentNativeToolExecutor(appContext)          ChatAgentModuleExecutor.kt:168
  ├─ ChatAgentSkillRouter（object）                          ChatAgentSkillRouter.kt:27
  └─ ChatCompletionClient                                    ChatCompletionClient.kt:89
```

> 另有 `ChatBenchmarkRunner`（`ui/chat/ChatBenchmarkRunner.kt`）是 `ChatCompletionClient` 的第二个消费方，仅用于非生产基准测试（见 §2.8）。

### 2.2 系统提示词的组装（三段拼接）

入口：`ChatCompletionClient.buildSystemPrompt()` (`:711-716`) → `ChatAgentSkillRouter.buildSystemPrompt()` (`:74-133`)

```
最终 system prompt = 用户预设 basePrompt  +  "\n\n"  +  动态技能提示词
```

**第一段：用户预设 basePrompt**
默认值（`ChatModels.kt:86`）：

```
You are a helpful assistant. Keep responses concise and clear.
```

用户可在「设置 → 模型配置」中覆盖。持久化在 **`ChatProviderConfig.systemPrompt`**（写入 `chat_provider_configs_json`）；`ChatPresetConfig.systemPrompt` 只是由 `ChatPresetRepository` 从 provider 配置镜像而来。

**第二段：动态技能提示词**（`ChatAgentSkillRouter.kt:81-128`）
仅当 `skills` 或 `availableTools` 非空时注入。以固定 24 条全局行为准则开头，核心原则：

- 身份：`You are the vFlow chat agent inside an Android automation app.`
- 工具优先级：优先最窄的直接工具；屏幕操作用 `vflow_agent_*` 原生 helper，人类向模块仅作兜底
- 无障碍优先：节点树是 primary source of truth；截图/OCR 仅作**显式兜底**
- 能一步做的别建工作流：`If one direct tool can complete a simple request such as dark mode, flashlight, wifi, brightness, clipboard, volume, or app launch, call that direct tool instead of navigating system UI or building a workflow.`
- 不得臆造参数：`Use canonical module parameters and step IDs; never invent localized parameter keys.`
- 不得谎报成功：`Never claim a tool succeeded until you receive the tool result.`
- artifact 句柄复用：`If a tool result includes artifact:// handles, preserve and reuse them in later tool arguments when needed.`
- 屏幕操作纪律（约 10 条）：先观察再动手、禁止连续同向盲滑、feed 列表按视口顺序取用、点击后先重观察再决定滑动、完成前必须做最终只读验证

**第三段：技能清单**（`ChatAgentSkillRouter.kt:114-127`）

```
Active skills:
- <title> (<id>): <description>
<instructions>
```

### 2.3 技能路由（Skill Router）—— 按关键词动态挑工具

核心节流设计：**不把所有工具塞给模型**，而是根据用户消息动态选技能 → 暴露「技能关联的工具 + 11 个永远暴露的原生 helper」（见本节末）（`ChatAgentSkillRouter.kt:28-72`）。

**14 个技能**（`SKILL_CATALOG`，`:727-742`）：

| # | 技能 id | 标题 | 触发关键词（示例） | 关联 moduleIds（示例） | 关联工具 |
|---|---|---|---|---|---|
| 1 | `temporary_workflow_execution` | Temporary Workflow Execution | 临时工作流、执行工作流 | `vflow.agent.temporary_workflow` | `vflow_agent_run_temporary_workflow` |
| 2 | `saved_workflow_creation` | Saved Workflow Creation | 保存/创建/生成 + 工作流；定时触发 | `vflow.agent.save_workflow` | `vflow_agent_save_workflow` |
| 3 | `flashlight_control` | Flashlight Control | 手电、flashlight、torch | `vflow.device.flashlight` | — |
| 4 | `clipboard_and_share` | Clipboard And Share | 剪贴板、复制、粘贴、分享、预览 | 6 个剪贴板/分享模块 | — |
| 5 | `device_settings_control` | Device Settings Control | wifi、蓝牙、亮度、移动数据、深色模式、免打扰、音量 | 12 个系统设置模块 | — |
| 6 | `screen_state_control` | Screen State Control | 亮屏、熄屏、锁屏、解锁、唤醒 | 6 个屏幕状态模块 | — |
| 7 | `screen_observation` | Screen Observation | 截图、当前页面、有什么控件、当前activity | `get_current_activity`、`find_element` | observe_ui / read_page_content / verify_ui |
| 8 | `visual_screen_fallback` | Visual Screen Fallback | ocr、截屏、识图（**显式兜底层**） | 截图/OCR 模块 | — |
| 9 | `ui_interaction` | UI Interaction | 点击、长按、滑动、输入、按键 | 7 个交互模块 | tap / long_press / input_text / swipe / press_key / wait |
| 10 | `app_lifecycle` | App Lifecycle | 打开应用、启动、关闭、force stop | 5 个应用模块 | lookup_installed_app / launch_app |
| 11 | `notifications` | Notifications | 通知、notification | 3 个通知模块 | — |
| 12 | `device_feedback` | Device Feedback | toast、振动、朗读、TTS、语音识别、播放音频、打电话 | 6 个反馈模块 | — |
| 13 | `shell_execution` | Shell Execution | shell、终端命令、adb、shizuku命令（**最后手段**） | `vflow.shizuku.shell_command`、`vflow.core.shell_command` | — |
| 14 | `generic_device_interaction` | Generic Device Interaction | （兜底）任何操作类请求 | 7 个模块 | 全部 11 个原生 helper |

> 表中「触发关键词」列是**归并后的示意**，非逐字关键词列表。实际匹配项多为组合词或正则（如剪贴板技能里是「复制到剪贴板」而非单独的「复制」；屏幕状态技能是「唤醒屏幕」而非「唤醒」；应用技能是「启动应用/关闭应用」，单独的「启动/关闭」由 `OPERATIONAL_SIGNALS` 正则兜底）。逐字核对请以 `ChatAgentSkillRouter.kt` 为准。

**路由流程**（`selectExplicitSkillIds` `:140-175`）：

1. 取最近一条 USER 消息 → 归一化（小写、`wi-fi`→`wifi`、`蓝芽`→`蓝牙`）
2. 先判两个高优先技能：`needsSavedWorkflowSkill` / `needsTemporaryWorkflowSkill`
3. 命中则**直接返回**，不再匹配其它技能
4. 否则遍历 `SKILL_CATALOG` 关键词/正则匹配
5. 仍未中且是操作类请求 → **工具元数据反查**（`routingHints` 与文本包含关系，`:177-196`）
6. 仍未中且像应用生命周期请求 → `app_lifecycle`
7. 仍未中且是操作类请求 → 兜底 `generic_device_interaction`
8. **技能扩散**（`expandSkillIds` `:198-218`，走 `relatedSkillIds`，如 UI 交互 → 屏幕观察）
9. **多轮延续**：若用户说「继续/再来/改成/换成」等 → 沿用上一轮工具所属技能（`:220-237`）

**操作类请求判定**（`looksLikeOperationalRequest` `:274-285`）：命中 `OPERATIONAL_SIGNALS`（打开/关闭/设置/读取/发送/点击/截图/播放…）或英文动词正则。

**永远暴露的 11 个原生 helper**（`ALWAYS_EXPOSED_NATIVE_HELPERS` `:751-763`）**不受技能路由影响**。

### 2.4 工具清单（四类，共 72 个）

构造点 `ChatAgentToolRegistry.kt:59-63`：

```kotlin
toolsByName = (
    listOf(buildTemporaryWorkflowToolDefinition(), buildSaveWorkflowToolDefinition()) +
        ChatAgentNativeToolExecutor.buildDefinitions(appContext) +
        buildDirectToolDefinitions()
    ).associateBy { it.name }
```

| 类别 | 数量 | 构造入口 |
|---|---|---|
| 临时工作流工具 | 1 | `:116-146` |
| 保存工作流工具 | 1 | `:148-186` |
| 原生 helper（NATIVE_HELPER） | 11 | `ChatAgentNativeTooling.kt:2260-2421` |
| 直接工具（从模块生成） | **59** | `buildDirectToolDefinitions()` `:673-683` |
| **合计** | **72** | |

工具名由 `chatToolNameFromModuleId()` 生成（`:40-46`）：小写、非 `[a-z0-9_]` 替换为 `_`、`trim('_')` 去首尾下划线、加 `vflow_` 前缀（若已以 `vflow_` 开头则不重复加）。

#### 2.4.1 原生 helper 工具（11 个，直连无障碍服务）

全部 `backend = NATIVE_HELPER`，不走模块引擎（`ChatAgentNativeTooling.kt:2260-2421`）：

| 工具名 | 标题 | 风险 | 说明 |
|---|---|---|---|
| `vflow_agent_observe_ui` | 观察当前界面 | READ_ONLY | dump 无障碍节点树 + 当前 activity + 可操作控件，高亮主内容目标 |
| `vflow_agent_read_page_content` | 读取页面内容 | LOW | 读当前可见页面节点文本，**不滚动** |
| `vflow_agent_verify_ui` | 验证界面状态 | READ_ONLY | 用可见文本/包名/activity 校验最终状态 |
| `vflow_agent_tap_screen` | 点击屏幕 | LOW | 点 ScreenElement 句柄或坐标 |
| `vflow_agent_long_press_screen` | 长按屏幕 | LOW | |
| `vflow_agent_input_text` | 输入文本 | LOW | 聚焦后输入 |
| `vflow_agent_swipe_screen` | 滑动屏幕 | LOW | 方向滑动或精确手势 |
| `vflow_agent_press_key` | 执行按键操作 | LOW | back/home/recents/通知栏/快捷设置/enter/search |
| `vflow_agent_wait` | 等待界面变化 | READ_ONLY | |
| `vflow_agent_lookup_installed_app` | 查询本机应用 | READ_ONLY | 按名称/包名解析 |
| `vflow_agent_launch_app` | 打开应用 | LOW | |

对应枚举 `ChatAgentNativeHelperId`（`ChatAgentNativeTooling.kt:41-53`），名称常量 `:55-65`。

#### 2.4.2 内置复合工具（2 个）

| 工具名 | moduleId | backend | 风险 | 说明 |
|---|---|---|---|---|
| `vflow_agent_run_temporary_workflow` | `vflow.agent.temporary_workflow` | TEMPORARY_WORKFLOW | 动态（各步最大值） | 一次审批跑短时多步工作流，≤80 步 |
| `vflow_agent_save_workflow` | `vflow.agent.save_workflow` | SAVED_WORKFLOW | HIGH | 保存可复用工作流到列表 |

#### 2.4.3 直接工具（59 个，从模块自动生成）

判定式（`:673-678`）：

```kotlin
module.aiMetadata?.usageScopes?.contains(AiModuleUsageScope.DIRECT_TOOL) == true ||
    module.id in LEGACY_DIRECT_TOOL_MODULE_IDS
```

**完整 59 个 moduleId**：

```
vflow.core.bluetooth              vflow.core.bluetooth_state       vflow.core.capture_screen
vflow.core.force_stop_app         vflow.core.get_clipboard         vflow.core.input_text
vflow.core.press_key              vflow.core.screen_operation      vflow.core.screen_status
vflow.core.set_clipboard          vflow.core.shell_command         vflow.core.sleep_screen
vflow.core.volume                 vflow.core.volume_state          vflow.core.wake_screen
vflow.core.wifi                   vflow.core.wifi_state            vflow.data.input
vflow.data.quick_view             vflow.device.call_phone          vflow.device.click
vflow.device.delay                vflow.device.flashlight          vflow.device.play_audio
vflow.device.send_key_event       vflow.device.speech_to_text      vflow.device.text_to_speech
vflow.device.toast                vflow.device.vibration           vflow.integration.clash_meta
vflow.integration.flclash         vflow.integration.ithome_check_in vflow.interaction.find_element
vflow.interaction.get_current_activity vflow.interaction.input_text vflow.interaction.ocr
vflow.interaction.screen_operation vflow.notification.remove       vflow.notification.send_notification
vflow.shizuku.shell_command       vflow.system.bluetooth           vflow.system.brightness
vflow.system.capture_screen       vflow.system.close_app           vflow.system.darkmode
vflow.system.do_not_disturb       vflow.system.find_installed_app  vflow.system.get_clipboard
vflow.system.get_screen_state     vflow.system.launch_app          vflow.system.launch_shortcut
vflow.system.mobile_data          vflow.system.screen_rotation     vflow.system.set_clipboard
vflow.system.share                vflow.system.sleep_screen        vflow.system.wake_and_unlock_screen
vflow.system.wake_screen          vflow.system.wifi
```

**工具定义生成**（`buildToolDefinition` `:89-114`）：

```kotlin
val baseStep = module.createSteps().firstOrNull() ?: ActionStep(module.id, emptyMap())
val inputs = module.getDynamicInputs(baseStep, listOf(baseStep))
    .filterNot { it.isHidden }
    .filter(::isInputSupported)
```

JSON Schema（`buildToolSchema` `:556-585`）：
- `additionalProperties: false` —— **严格**，参数键必须是 `getDynamicInputs` 声明的
- 只收 `isInputSupported` 类型：STRING / NUMBER / BOOLEAN / ENUM；ANY 需 `acceptedMagicVariableTypes` 非空（`:634-643`）
- `isHidden` 的输入被过滤掉
- `required` 数组来自 `aiMetadata.requiredInputIds`（`:560-583`）

### 2.5 三个 usageScope 的判定规则（**判定方式各不相同**）

| Scope | 判定方式 | 含义 |
|---|---|---|
| `DIRECT_TOOL` | **白名单**：`aiMetadata.usageScopes` 含之 或 在 `LEGACY_DIRECT_TOOL_MODULE_IDS` | 可当独立工具直接调用 |
| `TEMPORARY_WORKFLOW` | **白名单**：同上机制 | 可作临时工作流步骤 |
| `SAVED_WORKFLOW` | **黑名单**：默认放行，排除模板/snippet/3 个 AI 模块 | 可写入保存的工作流 |

> ⚠️ **SAVED_WORKFLOW 不在 `aiMetadata` 里声明** —— `AiModuleUsageScope` 枚举只有 `DIRECT_TOOL` 和 `TEMPORARY_WORKFLOW` 两个值（`AiModuleMetadata.kt:3-6`），`SAVED_WORKFLOW` 由注册表隐式判定。

**声明辅助函数**（`core/module/AiModuleMetadata.kt`）：

```kotlin
directToolMetadata(...)            // :25-45  → DIRECT_TOOL + TEMPORARY_WORKFLOW
temporaryWorkflowOnlyMetadata(...) // :47-62  → 仅 TEMPORARY_WORKFLOW
```

`AiModuleMetadata` 字段（`:15-23`）：`usageScopes` / `riskLevel` / `directToolDescription` / `workflowStepDescription` / `inputHints` / `requiredInputIds` / `allowSavedWorkflow`。

**判定函数原文**：

```kotlin
// DIRECT_TOOL — ChatAgentToolRegistry.kt:673-683
private fun buildDirectToolDefinitions(): List<ChatAgentToolDefinition> {
    val moduleIds = ModuleRegistry.getAllModules()
        .filter { module ->
            module.aiMetadata?.usageScopes?.contains(AiModuleUsageScope.DIRECT_TOOL) == true ||
                module.id in LEGACY_DIRECT_TOOL_MODULE_IDS
        }
        .map { it.id }.distinct().sorted()
    return moduleIds.mapNotNull(::buildToolDefinition)
}

// TEMPORARY_WORKFLOW — :685-693
private fun buildTemporaryWorkflowModuleIds(): List<String> {
    return ModuleRegistry.getAllModules()
        .filter { module ->
            module.aiMetadata?.usageScopes?.contains(AiModuleUsageScope.TEMPORARY_WORKFLOW) == true ||
                module.id in LEGACY_TEMPORARY_WORKFLOW_MODULE_IDS
        }
        .sortedWith(compareBy<ActionModule> { ModuleCategories.getSortOrder(it.metadata.getResolvedCategoryId()) }.thenBy { it.id })
        .map { it.id }
}

// SAVED_WORKFLOW — 清单构建 :695-700，判定函数 :702-709
private fun isSavedWorkflowModuleAllowed(module: ActionModule): Boolean {
    val category = module.metadata.getResolvedCategoryId()
    if (category == ModuleCategories.TEMPLATE) return false
    if (module.id.startsWith("vflow.snippet.")) return false
    if (module.id in LEGACY_SAVED_WORKFLOW_EXCLUDED_MODULE_IDS) return false
    if (module.aiMetadata?.allowSavedWorkflow == false) return false
    return true
}
```

**运行时强校验**（执行链使用，`:76-82`）：

```kotlin
fun isTemporaryWorkflowModuleAllowed(moduleId: String): Boolean = moduleId in temporaryWorkflowModuleIds
fun isSavedWorkflowModuleAllowed(moduleId: String): Boolean = moduleId in savedWorkflowModuleIds
```

**三个 LEGACY 常量**：

- `LEGACY_DIRECT_TOOL_MODULE_IDS`（`:806-840`，33 项）
- `LEGACY_TEMPORARY_WORKFLOW_MODULE_IDS`（`:842-850`）= 上述 33 项 + 7 个逻辑块（`loop.start/loop.end/if.start/if.middle/if.end/break_loop/continue_loop`）
- `LEGACY_SAVED_WORKFLOW_EXCLUDED_MODULE_IDS`（`:852-856`）= `vflow.ai.agent`、`vflow.ai.autoglm`、`vflow.interaction.operit`

> **发现：`LEGACY_DIRECT_TOOL_MODULE_IDS` 已完全冗余。** 该 33 项全部已通过 `directToolMetadata(...)` 显式声明 `DIRECT_TOOL`，当前不会改变直接工具集合，仅作历史兼容残留。

### 2.6 提示词里的「变量传参指南」

注入到临时/保存工作流的工具描述（`buildVariablePassingGuide` `:764-780`）：

```
To pass data from one step to another, give each step a meaningful `id` and use magic variable syntax in parameters: {{STEP_ID.OUTPUT_ID}}.
- References must point to earlier steps only. Do not reference future steps or output ids that are not listed for that module.
- Property access: {{STEP_ID.OUTPUT_ID.PROPERTY}}.
- Available properties by output type: Image(.width,.height,.path,.size,.name,.uri,.base64), File(...), ScreenElement(...), Coordinate(.x,.y), List(.count,.first,.last,.random,.isempty), String(.length,.uppercase,...), Number(.int,.round,.abs,.length), Dictionary(.count,.keys,.values).
...
Block structure rules:
- Loop.start/Loop.end and If.start/If.middle/If.end must be paired. Set indentationLevel=1 for steps inside a loop or if block.
- Loop.start outputs "loop_index" (1-based) and "loop_total"...
```

同时注入**模块目录**（`buildCompactModuleCatalog` `:716-752`）与**输出目录**（`buildModuleOutputCatalog` `:754-762`）：

```
 Module catalog: vflow.device.flashlight(手电筒: ...; inputs: mode=toggle/on/off); ...
Available step outputs: vflow.device.flashlight(success).
```

- 临时工作流目录：`maxModules = 40`，用 `workflowStepDescription`
- 保存工作流目录：触发器 24 + 步骤 48；两者措辞来源不同——**步骤**目录用 `workflowStepDescription`（`preferWorkflowDescriptions = true`），**触发器**目录用 `directToolDescription`（`preferWorkflowDescriptions = false`）

### 2.7 一次工具调用的完整执行流程

```
模型回复 → tool_calls
  ChatViewModel.requestAssistantReply              ChatViewModel.kt:820-939
    └─ ChatAgentSkillRouter.selectSkills           ChatAgentSkillRouter.kt:28
  assistant 消息 toolApprovalState = PENDING        ChatViewModel.kt:870-888
    ├─ 自动审批 shouldAutoApproveToolCalls          ChatViewModel.kt:941-950
    ├─ 人工 approveToolCalls → prepareBatch         ChatViewModel.kt:519-571
    └─ reject / rerun                               ChatViewModel.kt:573-689
  executeBatch → appendToolResultsAndContinue → 下一轮 requestAssistantReply
```

> 上图省略了 `rerun` 分支的差异：`rerunToolCalls`（`:596-689`）是**用户修改参数后重跑同一批**，它直接 `executeBatch` 并追加重跑结果，**不调用 `appendToolResultsAndContinue`、不触发下一轮 `requestAssistantReply`**；只有 `reject`（`:573-594`）与 approve 走「→ 下一轮」链路。

> **行号文件归属提示**：本节的 `:870-888`、`:941-950`、`:519-571`、`:573-689`、`:526`、`:64` 属 `ChatViewModel.kt`；而 `:348-394`、`:1233-1249`、`:1286-1299`、`:396-449`、`:198-221`、`:1136-1231`、`:1068-1134`、`:978-1020`、`:76`、`:102-111`、`:1301-1309`、`:189`、`:551`、`:839-851` 属 **`ChatAgentModuleExecutor.kt`**；`:858-865`（`riskLevelForModuleId`）属 **`ChatAgentToolRegistry.kt`**。三文件行号混排，引用时请以函数名为准。

**prepareToolCall 分派**（`ChatAgentModuleExecutor.kt:348-394`）：

1. 临时工作流工具名 → `prepareTemporaryWorkflow`
2. 保存工作流工具名 → `prepareSaveWorkflow`
3. `toolRegistry.getTool(name)`；未知 → `ImmediateResult(ERROR "Unknown tool")`
4. `backend == NATIVE_HELPER` → `nativeToolExecutor.prepare(...)`
5. 否则 `ModuleRegistry.getModule(definition.moduleId)`
6. `prepareModuleStep(...)`

**参数映射**（`buildParameters` `:1233-1249`）：

```kotlin
val defaults = module.createSteps().firstOrNull()?.parameters?.toMutableMap() ?: mutableMapOf()
val baseStep = ActionStep(moduleId = module.id, parameters = defaults)
val inputs = module.getDynamicInputs(baseStep, listOf(baseStep))
val arguments = parseArguments(rawArgumentsJson)
arguments.forEach { (key, value) ->
    val input = inputs.firstOrNull { it.id == key } ?: return@forEach   // ← 未知参数键：静默丢弃
    defaults[key] = coerceInputValue(input, value, artifactStore)
}
return defaults
```

> ⚠️ **注意这里用的是 `baseStep = createSteps()`，即参数里没有用户的运行时选择**。这就是 `CallFunctionModule` 在 AI 侧拿不到参数的根本原因（详见 §5）。

**按类型转换**（`coerceInputValue` `:1286-1299`）：

| ParameterType | 转换 |
|---|---|
| STRING | `artifactValue ?: rawValue?.toString().orEmpty()` |
| NUMBER | `coerceNumber(rawValue, input.defaultValue)`（按默认值类型转 Int/Long/Float/Double） |
| BOOLEAN | `"true"`（忽略大小写）→ true，否则原值 |
| ENUM | `input.normalizeEnumValue(...)`（走 `legacyValueMap` 兼容旧值） |
| ANY | `artifactValue ?: rawValue` |

> ⚠️ **artifact 句柄解析只覆盖 STRING / ANY**（`:1293`、`:1297`）—— NUMBER/BOOLEAN/ENUM 传入 `artifact://...` **不会解析**。

**校验**（`prepareModuleStep` `:396-449`）：

```kotlin
val validation = module.validate(step, listOf(step))       // :413
if (!validation.isValid) → ImmediateResult(ERROR, validation.errorMessage)
else → Ready(..., missingPermissions = module.getRequiredPermissions(step)
        .filterNot { PermissionManager.isGranted(appContext, it) })
```

> ⚠️ **`requiredInputIds` 只在 JSON Schema 层生效，运行时不校验**。AI 漏传必填参数时，静默落回模块默认值，唯一拦截是 `module.validate()`。

**执行**（`executeBatch` `:198-221`，**顺序、串行、无并行**）：

| item | 执行函数 |
|---|---|
| `ImmediateResult` | 直接返回 |
| `Ready` | `executeReadyTool` `:1136-1231` |
| `NativeReady` | `nativeToolExecutor.execute` |
| `TemporaryWorkflow` | `executeTemporaryWorkflow` `:1068-1134` |
| `SaveWorkflow` | `executeSaveWorkflow` `:978-1020` |

**artifact 句柄机制**（`ChatAgentArtifactStore` `:56-100`）：
- 句柄格式 `artifact://$callId/$key`（`:76`）
- 类型标签 `chatArtifactTypeLabel`（`:102-111`）：`VImage→image`、`VCoordinate→coordinate`、`VCoordinateRegion→coordinate region`、`VScreenElement→screen element`、`ChatAgentUiSnapshot→ui snapshot`（其它类型不建句柄）
- 反向解析：模块侧 `resolveArtifactValue`（`:1301-1309`）、native 侧 `resolveElement`（`ChatAgentNativeTooling.kt:2173-2182`）
- 会话级 store：`ChatViewModel.artifactStores`（`:64`）

**风险与审批**：

- 风险映射 `riskLevelForModuleId`（**`ChatAgentToolRegistry.kt:858-865`**）：`AiModuleRiskLevel READ_ONLY/LOW/HIGH/STANDARD(null)` → `ChatAgentToolRiskLevel`
- **批量/工作流风险取最大值**（`:189`、`:551`、`riskLevelForSavedWorkflow` `:839-851`）
- 保存工作流：**非 manual 触发器一律 HIGH**
- 审批状态机 `ChatToolApprovalState`：`PENDING → RUNNING → APPROVED / REJECTED`
- 默认 `autoApprovalScope = OFF`（**所有工具都要人工点批准**）
- 档位 `ChatToolAutoApprovalScope`：`OFF / READ_ONLY / LOW_RISK / STANDARD / ALL`（按 rank 比较，`ChatModels.kt:136-160`）
- 同一时刻仅一个待执行批次（`:526`）

### 2.8 轮数、并发、批量

- **生产环境无工具轮数上限**。只要模型继续返回 tool_calls，`appendToolResultsAndContinue` → `requestAssistantReply` 会无限续轮。全仓库无 `MAX_ROUND/TURNS/TOOL_CALLS` 类生产常量。
  - 注意区分：链路 C 的工作流内 AI 模块确有 `max_steps` 常量（`AgentModule.kt:137` 默认 15、`AutoGLMModule.kt:102` 默认 30），那是**视觉 Agent 自身的步数上限**，与聊天 Agent 的续轮次数无关。
- **禁用模型并行工具调用**：`parallel_tool_calls = false`（`ChatCompletionClient.kt:200`，Responses API 同样 `:223`）。
- 一条 assistant 消息的多个 tool_calls = **一个批次，一次审批**；`executeBatch` **顺序执行**。
- 基准测试另有预算（非生产）：`maxToolCalls = 24`（`ChatBenchmarkModels.kt:31,67`）。

### 2.9 临时工作流 vs 保存工作流

| 维度 | 临时工作流 | 保存工作流 |
|---|---|---|
| 准备 | `prepareTemporaryWorkflow` `:451-566` | `prepareSaveWorkflow` `:568-654` |
| 执行 | **真跑**（`WorkflowExecutor.execute`），超时自动停（等待阈值 = `maxExecutionTime` + 5s 缓冲） | **不执行**，只 `saveWorkflow(...)` |
| 触发器 | 强制 `vflow.trigger.manual` | 可带 ≤12 个，非 manual 判 HIGH |
| `artifact://` | ✅ **允许**透传 | ❌ **禁止**（`containsArtifactHandle` 拦截） |
| 模块白名单 | TEMPORARY 列表 | SAVED 列表 |
| 上限 | 展开后 ≤80 步；`maxExecutionTime` 1–300s | steps ≤200、triggers ≤12、tags ≤12 |
| 特殊展开 | 支持 `repeat`(1..50) 并自动插 `vflow.device.delay` | 无展开 |
| 嵌套 | 禁止嵌套工作流工具 | — |

### 2.10 链路 A 能力与现状评估

> 本节是**评估**（主观判断），与前面的**事实描述**区分开。前面各节的结论均有代码位置支撑；本节的「强/弱」是走查后的判断，供后续优化选点。

#### 2.10.1 它是什么

**一个真·Agent 循环**：多轮 LLM + 工具调用 + 审批 + 执行结果回灌，直到模型不再调工具。代码规模约 7000 行（`ChatViewModel` 1219 + `ChatAgentModuleExecutor` 1564 + `ChatAgentNativeTooling` 2682 + `ChatAgentToolRegistry` 867 + `ChatAgentSkillRouter` 795），是整个项目 AI 投入最重的部分。

#### 2.10.2 端到端能力清单

| 能力 | 支撑 |
|---|---|
| 观察与操作手机 | 11 个原生 helper（无障碍直连） |
| 单点设备操作 | 59 个模块直接工具（手电/网络/剪贴板/音量/亮度/启动应用…） |
| 多步自动化 | `vflow_agent_run_temporary_workflow`（≤80 步、可 repeat） |
| 沉淀自动化 | `vflow_agent_save_workflow`（可带 ≤12 触发器） |
| 多轮对话 | 会话持久化、可恢复（`chat_session_prefs`） |
| 多供应商 | OPENAI / DEEPSEEK / ANTHROPIC / OPENROUTER / OLLAMA 五档，另支持自定义 baseUrl；三套 API 形态适配（Chat Completions / Responses / Anthropic Messages） |
| 安全审批 | 风险四级 + 自动审批五档（默认 OFF） |
| 富对象传递 | artifact 句柄（图像/坐标/元素/UI 快照） |
| 停止生成 | `stopAgent()` **真取消**（对比链路 B 不能中断 HTTP） |
| 提示词节流 | 技能路由，按消息动态选工具（14 技能 + 11 常驻 helper） |

#### 2.10.3 短板（按严重度）

1. **上下文无限增长**（工程风险最高）：`buildChatCompletionHistoryMessages` 对 `request.history` **全量** `forEach`（`ChatCompletionClient.kt:249`、`:318`、`:782` 三套 adapter 均无裁剪），无 `takeLast` / token 预算 / 摘要压缩。Agent 单次节点树 dump 就很大，长会话迟早撞上下文上限且无降级策略。`ChatMessage.tokenCount` 字段存在但未用于裁剪。
2. **静默失败**（可信度风险最高）：未知参数键被丢弃（`buildParameters` `?: return@forEach`）、`requiredInputIds` 运行时不校验、NUMBER/BOOLEAN/ENUM 不解析 `artifact://`（§2.7）。三者叠加 → **模型以为成功、用户看到成功，实际参数被丢或传错**。
3. **能力发现层缺基础设施**：AI 看不到**用户已有资产**（已保存工作流、函数工作流）。`call_workflow` 不知 `workflow_id` 填什么，`call_function` 的函数参数键进不了 schema（§5）。
4. **生产无轮数上限**（§2.8）：基准有 24 上限，生产聊天路径零限制，安全默认反了。
5. **能力视图碎片化**：链路 A 用 `aiMetadata` + `getDynamicInputs`，链路 B 用 `metadata.description` + `getInputs`，同一批模块两套描述；且 `aiMetadata` 本身只覆盖 188 个模块中的 103 个。
6. **配置割裂**：链路 A 用 `ChatProviderConfig`，链路 B 用 SharedPreferences `ai_config`，同一 API key 要填两遍。
7. **无多模态、无流式**：不能给模型发图片（截图只能转文本）；`stream=false`，长回复整段等待。

---

## 3. 链路 B：AI 生成工作流

`ui/workflow_editor/WorkflowAiGenerator.kt`（215 行）—— **单轮、无工具调用**。

> 调用链：`EditorMoreOptionsSheet` 的「AI 生成」入口 → `WorkflowEditorActivity.showAiCreationSheet()` → `AiGenerationSheet.performGeneration()` → `WorkflowAiGenerator.generateWorkflow()`。生成结果经 `applyGeneratedWorkflow()` 写入编辑器，用户可保存、由 `WorkflowExecutor` 正常执行（含触发器）。所谓「不执行」指**生成动作本身不运行工作流**，不是生成结果不能跑。

- `generateSystemPrompt()`（函数体 `:33-149`，提示词字符串自 `:36` 起）拼一份**自包含**提示词：
  - JSON 结构规则（Workflow / ActionStep / id / moduleId / parameters）
  - 触发器与动作分离规则
  - 块结构约束（Start 必须有 End）
  - 魔法变量属性表（写死在提示词里）
  - **全部模块定义**：遍历 `ModuleRegistry.getAllModules()`，按分类逐个渲染 `metadata.name` / `module.id` / `metadata.description` / `getInputs()` / `getOutputs(null)`
  - 输出格式：`Return ONLY valid JSON. No Markdown code blocks.`
- 请求：单条 system + 单条 user（`Requirement: $requirement`），`response_format = json_object`
- 过滤：跳过 `TEMPLATE` 分类与含 `snippet` 的模块（`:104`）
- 输出经 `sanitizeGeneratedWorkflow`（`:211-`）清洗成 `Workflow`

> **与链路 A 的本质差异**：
> - 用 `metadata.description` 而非 `aiMetadata`（**不读 AI 元数据**）
> - 用 `getInputs()` 而非 `getDynamicInputs()` → **动态参数一律看不到**
> - 一次性列出全部模块（无技能节流、无目录裁剪）

### 3.1 链路 B 能力与现状评估

> 同 §2.10，本节为**评估**，非事实描述。

#### 3.1.1 它是什么

**一个"自然语言 → 工作流配置"的单轮生成器，不是 Agent。** 全文仅 215 行，一次 HTTP 调用，无工具、无多轮、无审批。

**定位澄清**：它对标的是「自然语言 → DSL」这类配置生成任务，**不需要** function calling —— 因此「无工具调用」不是缺陷。用 Agent 的标准去衡量它才是不公平的。

#### 3.1.2 完整闭环

```
编辑器「AI 生成」→ AiGenerationSheet 填需求/provider/key
  → POST {baseUrl}/chat/completions（response_format=json_object）
  → content → WorkflowJsonImportParser.parse() → Workflow
  → onWorkflowGenerated → 灌入编辑器（triggers + steps）
  → 用户保存后由 WorkflowExecutor 正常执行
```

提示词自包含（`:33-149`）：JSON 结构、触发器/动作分离、块结构约束、魔法变量属性表、全部模块定义、纯 JSON 输出要求。

#### 3.1.3 短板

**硬伤（限制它做不好）：**

| 问题 | 位置 | 后果 |
|---|---|---|
| 用 `getInputs()` 而非 `getDynamicInputs()` | `:117` | 动态参数模块（如 `call_function`）的参数 AI 完全看不到 |
| 用 `metadata.description` 而非 `aiMetadata` | `:114` | 与链路 A 能力视图不一致；`inputHints`/`requiredInputIds`/风险全丢失 |
| 看不到用户已有资产 | 全文无 `getAllWorkflows` | 无法复用已保存工作流/函数，每个需求从零拼 |
| **无验证/反馈闭环** | — | 生成对错只能保存后手工试跑；不能 dry-run、不能拿报错回头修 |
| 无迭代 | 单轮 `messages`（`:161-164`） | 不满意只能重填需求重来 |

**打磨缺口（能跑但糙）：**

1. **"停止"不真停**：`generatingJob.cancel()`（`AiGenerationSheet.kt:191`）取消协程，但 OkHttp 走阻塞式 `execute()`（`WorkflowAiGenerator.kt:185`），协程取消**不中断 HTTP 请求**，只是不再处理结果。
2. **payload 无 `max_tokens`**（`:166-171`）：大工作流可能被截断，截断后解析失败只报笼统错误。
3. **错误处理粗糙**：只特判 `401`（`AiGenerationSheet.kt:172`），不解析 API error body。
4. **配置割裂**：另用 SharedPreferences `ai_config`，与链路 A 的 `ChatProviderConfig` 不通。
5. **提示词硬编码**在 Kotlin 字符串，不可外置、无版本管理。
6. **残留开发痕迹**：`:101` 有注释掉的 `// if (module.id.startsWith("vflow.ai")) continue`。
7. **触发器/步骤边界仅靠提示词约束**（`:47-51`），无代码强校验。

#### 3.1.4 A / B 对比

| 维度 | 链路 A（聊天 Agent） | 链路 B（生成工作流） |
|---|---|---|
| 形态 | 多轮 Agent | 单轮生成器 |
| 工具调用 | ✅ 72 个 | ❌ 无（定位使然，非缺陷） |
| 审批 | ✅ 风险四级 | ❌ 无 |
| 可迭代 | ✅ 多轮修正 | ❌ 重填重来 |
| 停止生成 | ✅ 真取消 | ⚠️ 不中断 HTTP |
| 反馈闭环 | ✅ 执行结果回灌 | ❌ 无 |
| 看用户资产 | ❌ | ❌ |
| 能力视图 | `aiMetadata` + `getDynamicInputs` | `metadata.description` + `getInputs` |
| 代码量 | ~7000 行 | ~215 行 |
| 定位 | 执行任务 | 生成配置 |

#### 3.1.5 小结

链路 B 是**能跑通的最小可用版本（MVP）**，不是断线的半成品：主流程端到端闭环，简单需求可用。真正卡住它的是**没有"生成 → 校验 → 试跑 → 修正"的反馈闭环**——这是它从"能用"到"好用"的分水岭。若要补强，**补反馈闭环比给它加工具调用更贴合定位**（后者会把链路 B 变成第二条链路 A，加剧碎片化）。

---

## 4. 链路 C：工作流内 AI 模块

四个模块，各自独立的提示词，与聊天面板无关：

| 模块 id | 类 | 风格 | 提示词特点 |
|---|---|---|---|
| `vflow.ai.completion` | `AIModule` | 纯 API 调用 | 用户自定义 `system_prompt`（默认 `You are a helpful assistant.`），OpenAI 兼容接口 |
| `vflow.ai.agent` | `AgentModule` | 视觉 Agent | 每步截图 + `<think>` 伪代码 + **原生 Function Calling** + 最近 30 天常用应用上下文 |
| `vflow.ai.autoglm` | `AutoGLMModule` | 视觉 Agent | 中文 `do(action="Tap", element=[x,y])` 协议，坐标 0–999 归一化 |
| `vflow.interaction.operit` | `OperitModule` | 外部 AI 助手 | 通过 `EXTERNAL_CHAT` Intent 调用 Operit AI 助手，回收 `ai_response`（不自带提示词，提示词由外部应用管理） |

后三者被排除在 `SAVED_WORKFLOW` 之外（见 `LEGACY_SAVED_WORKFLOW_EXCLUDED_MODULE_IDS`；`AIModule` 不在排除名单内）。

> `AIModule` 的源码位于 `core/workflow/module/network/` 目录，但其模块 id 命名空间是 `vflow.ai.*`——目录名与 id 前缀不一致，容易被误导。

### 4.1 链路 C 现状评估

> 同 §2.10/§3.1，本节为**评估**。

#### 4.1.1 它是什么

**工作流执行期调用的 AI 步骤**——不是独立的聊天入口，而是工作流里的一个模块节点。四个模块共约 2089 行，风格差异极大：

| 模块 | 行数 | 自带的"Agent 能力" |
|---|---|---|
| `AIModule` (`vflow.ai.completion`) | 214 | 无，纯一次 LLM 问答 |
| `AgentModule` (`vflow.ai.agent`) | 693 | 自带 8 个 function schema（`click_point`/`click_element`/`input_text`/`scroll`/`press_key`/`launch_app`/`wait`/`finish_task`）+ 每步截图 + `<think>` 规划 + `max_steps`（默认 15）+ 近 30 天应用上下文 |
| `AutoGLMModule` (`vflow.ai.autoglm`) | 682 | 中文 `do(action=...)` 协议 + 坐标 0–999 归一化 + `max_steps`（默认 30） |
| `OperitModule` (`vflow.interaction.operit`) | 500 | 无，转发给外部 Operit 应用 |

#### 4.1.2 定位与特点

- **两套独立的视觉 Agent 实现**（`AgentModule` 与 `AutoGLMModule`）**并存且互不复用**：提示词、动作协议、坐标尺度、终止条件全是各写各的。这是链路 C 最大的结构问题——同一类能力维护了两份。
- **提示词、模型、动作 schema 全部硬编码**在模块里。例如 `AIModule` 默认 `model = "gpt-3.5-turbo"`（`AIModule.kt:74`），与链路 A 的默认模型（`gpt-5.4`）严重不同步；且 `AIModule` 同样没有 `max_tokens` 兜底。
- **AgentModule 的 function schema 写死英文描述 + 硬编码归一化坐标**（0–1000），与 AutoGLM 的 0–999 是两套尺度。
- 这三/四个模块**与聊天链路（A/B）零共享**：既不共享提示词、工具，也不共享模型配置与密钥——用户在聊天里配好的 provider 在这里要重新填。
- `AgentModule`/`AutoGLMModule`/`OperitModule` 被排除在 `SAVED_WORKFLOW` 之外（防止保存的工作流嵌套 AI Agent），`AIModule` 未排除。

#### 4.1.3 短板小结

1. 同类能力双实现（Agent vs AutoGLM），维护成本翻倍且行为不一致。
2. 模型/提示词/schema 硬编码，且默认模型疑似过时（`gpt-3.5-turbo`）。
3. 与聊天链路完全割裂：密钥、provider、提示词各配各的。
4. 无 `max_tokens`/超时兜底（`max_steps` 是步数上限，不是输出长度限制）。

---

---

## 5. 「AI 能否使用某模块」的判定：两个案例 + 通则

### 5.0 通则（先看这个）

**AI 能否把一个模块当工具用，取决于两个门槛，且两者性质完全不同：**

| 门槛 | 内容 | 判定方式 |
|---|---|---|
| **门槛 1（硬）** | 模块有没有声明 `aiMetadata`（`DIRECT_TOOL` / `TEMPORARY_WORKFLOW`） | 二值：声明即可见，不声明即不可见 |
| **门槛 2（软）** | 声明之后，AI 能否拿到**完整且有意义**的参数信息 | 取决于参数是怎么定义的 |

**关键纠正**：门槛 1 是唯一的硬开关。**参数复杂（如动态参数）本身不是「AI 不可用」的理由** —— 它只影响门槛 2 的达成难度。常见的误判是把「参数需运行时决定」当成「不支持 AI」，这是错的。

门槛 2 的三种情形：

| 情形 | 例子 | schema 能否生成 | AI 能否正确调用 |
|---|---|---|---|
| 参数集**静态** | 绝大多数模块、`call_workflow` | ✅ | ✅ 声明后即可用 |
| 参数**值**需运行时决定 | 「调用哪个工作流」的 `workflow_id` | ✅ | ⚠️ 需喂目录，否则不知填什么值 |
| 参数**键**需运行时决定 | `call_function`（重写 `getDynamicInputs` 且依赖步骤参数） | ⚠️ 能生成，但只含静态键、缺动态键 | ❌ 需特殊处理 |

---

### 5.1 案例一：`vflow.logic.call_workflow`（上游既有模块）

**事实（逐条已验证）**：

| 查证项 | 结果 |
|---|---|
| 是否声明 `aiMetadata` | ❌ 没有 |
| 是否在 `LEGACY_DIRECT_TOOL_MODULE_IDS` | ❌ 不在 |
| 是否被 `ui/chat/` 引用（任何形式） | ❌ 零命中 |
| 是否重写 `getDynamicInputs` | ❌ 没有（0 处） |
| `getInputs()` 返回什么 | **静态**：只有 `workflow_id`（`ParameterType.STRING`） |
| `getOutputs()` 返回什么 | `result`（`ANY`）；`getDynamicOutputs()` 额外扫子工作流的 `vflow.variable.create` 步骤，暴露 `var_<name>` |
| 全仓库是否有代码写 `"inputs"` 参数键 | ❌ `call_workflow` 无（命中的只有 Js/Lua 模块各自的 `inputs`） |
| 上游 `master` 是否有 `inputs` 实现 | ❌ 没有，只有一句注释 |

**重要考古发现**：`CallWorkflowModule.getInputs()` 里有一句注释：

```kotlin
// 输入参数 'inputs' 将由 UIProvider 动态处理
```

**这句话是上游写下但从未兑现的空头承诺。** 证据：
- `git show master:...CallWorkflowModule.kt` 里**同样只有这句注释**，无实现 → 不是被谁删了，是从没写过
- 它的 UIProvider `getHandledInputIds()` 只返回 `{"workflow_id"}`，`readFromEditor` 也只写 `workflow_id`
- 编辑器布局 `partial_call_workflow_editor.xml`（**上游版本**）里**只有「选择工作流」一个按钮**，没有任何参数输入控件。注意：本 fork 工作区已在该布局追加 `<include layout="@layout/partial_call_function_params"/>`（`:31-35`，容器 id `container_call_function_params`）。该容器由 `CallFunctionModuleUIProvider` 在 `refreshParams()` 中填充并切换可见性（`:126-145`）；`CallWorkflowModuleUIProvider` 虽 inflate 同一布局，但不引用该容器，故对 `call_workflow` 保持 XML 默认的 `gone`。**参数赋值区是 fork 给 `call_function` 加的，不是 `call_workflow` 的。**
- 全仓库无一处读写 `"inputs"` 键（对 `call_workflow` 而言）

**结论**：`call_workflow` 的入参**只有 `workflow_id` 一个**，出参是 `result` + 动态 `var_*`。子工作流所需数据靠**共享命名变量**隐式传递（`executeSubWorkflow` 用默认 `injectedVariables = emptyMap()`，继承 `parentContext.namedVariables`）。

**因此**：它属于门槛 2 的「参数值需运行时决定」情形 ——
- 声明 `directToolMetadata(...)` → schema 正确生成 `{workflow_id: string}`，**合法且可用**
- 唯一缺口：AI 看不到工作流清单，**不知道 `workflow_id` 填什么值**（`ui/chat/` 与 `WorkflowAiGenerator` 里搜 `getAllWorkflows` 均零命中）

> ⚠️ **不要**把它归为「参数键需运行时决定 / 不可救」——它的 `getInputs()` 是静态的，无需 `getDynamicInputs`。

### 5.2 案例二：`vflow.logic.call_function`（fork 新增模块）

针对 `vflow.logic.call_function` / `vflow.logic.define_function`：

| 链路 / 通道 | 感知情况 | 原因 |
|---|---|---|
| A-直接工具 | ❌ | 未声明 `aiMetadata`，也不在 `LEGACY_DIRECT_TOOL_MODULE_IDS` |
| A-临时工作流 | ❌ | 未声明，也不在 LEGACY 白名单 |
| A-保存工作流 | ⚠️ 模块 id 可见但不可用 | 黑名单放行 → 进 `buildCompactModuleCatalog`；但参数 `InputDefinition` 依赖 `workflow_id` 才能查到签名 |
| A-技能 | ❌ | 无任何技能 `moduleIds` 包含它们 |
| B-AI 生成 | ⚠️ 会列出但信息不全 | 遍历全部模块会列出；但 `getInputs()` 对 `call_function` 只有 `workflow_id`、对 `define_function` 完全为空 |
| C-工作流内 AI | 无关 | 不生成步骤 |

**根本原因（已定位到行）**：

```kotlin
// ChatAgentToolRegistry.buildToolDefinition :91-93
val baseStep = module.createSteps().firstOrNull() ?: ActionStep(module.id, emptyMap())
val inputs = module.getDynamicInputs(baseStep, listOf(baseStep)).filterNot { it.isHidden }
```

`createSteps()` 只收集**有 `defaultValue` 的参数**（`BaseModule.kt:103-109`），而 `workflow_id` 无默认值 → `baseStep.parameters` 里没有 `workflow_id` → `CallFunctionModule.getDynamicInputs` 在 `workflowId == null` 时提前返回。

注意**返回的不是空列表**：`getDynamicInputs` 是 `getInputs().toMutableList()` 后 `?: return base`（`CallFunctionModule.kt:46-49`），而 `getInputs()` 静态返回 `[workflow_id]`（`:34-44`）。因此：

- 生成的 schema 是 `{"type":"object","additionalProperties":false,"properties":{"workflow_id":{"type":"string"}}}` —— `workflow_id` 会被正常接受；
- 保存工作流目录会输出 `vflow.logic.call_function(调用函数工作流: ...; inputs: workflow_id)`（`buildCompactModuleCatalog` 仅在 `inputs.isBlank()` 时才省略 `inputs:`，`:744-748`）。

**真正的缺口**：`additionalProperties:false` 拒绝的是**函数参数键**。当 AI 想传某个函数参数（如 `name`）时，`getDynamicInputs` 因 baseStep 缺 `workflow_id` 而只返回 `workflow_id`，参数键不在 schema 里 → 被 `additionalProperties:false` 拒掉；`buildParameters` 也会把未知键静默丢弃（见 §2.7）。即 schema 生成**没失败，只是只含 `workflow_id`、缺所有函数参数键**。

**结论**：AI 目前**知道这两个模块存在，但不知道如何使用它们** —— 缺的是「有哪些函数工作流、各自参数名/类型/必填/默认值/返回键」这份信息，外加一条能把签名注入 `buildToolDefinition` 路径的机制。

### 5.3 两个案例对比

| | `call_workflow`（上游） | `call_function`（fork） |
|---|---|---|
| 参数集是否静态 | ✅ 静态（`getInputs()` 只有 `workflow_id`） | ⚠️ 半动态：`workflow_id` 静态存在，**函数参数键**依赖 `getDynamicInputs`（需 `workflow_id` 已填） |
| 门槛 1（aiMetadata） | ❌ 缺 | ❌ 缺 |
| 门槛 2（参数信息） | ✅ 无问题，schema 直接可得 | ⚠️ schema **能生成**但只含 `workflow_id`，函数参数键缺失 → 被 `additionalProperties:false` 拒 |
| 声明后能否被 AI 调用 | ✅ **能**（仅需再喂工作流清单） | ❌ **不能**（需把签名喂进 `buildToolDefinition` 路径，让函数参数键出现在 schema 里） |
| 缺的东西 | 1 条 `aiMetadata` + 工作流目录 | 1 条 `aiMetadata` + 签名注入 + 函数目录 |

**反直觉但重要**：**`call_workflow` 反而比 `call_function` 更容易支持。** 前者声明即基本可用，后者还卡在「`createSteps()` 空参数 → `getDynamicInputs` 拿不到 `workflow_id` → 函数参数键无法进入 schema」这一步上。

两份目录（全部工作流清单 / 函数工作流清单）**应统一设计**，因为 `call_workflow` 与 `call_function` 缺的是同一类信息。

---

## 6. 扩展 AI 能力的落点（供后续改动参考）

新增一个模块让 AI 能用，需要动的地方：

| 目标 | 要做什么 |
|---|---|
| 当**直接工具** | 模块声明 `directToolMetadata(...)`。**唯一的硬门槛。** |
| 进**临时工作流** | 模块声明 `temporaryWorkflowOnlyMetadata(...)` 或 `directToolMetadata(...)` |
| 进**保存工作流** | 默认已放行（除非声明 `allowSavedWorkflow = false`） |
| 被**技能路由**命中 | 在 `ChatAgentSkillRouter` 的技能 `moduleIds`/`toolNames` 里加入（改上游文件） |
| 携带**上下文数据**（如工作流清单） | 新增目录构建器 + 在 `buildTemporaryWorkflowToolDefinition` / `buildSaveWorkflowToolDefinition` 的 `buildString{}` 末尾追加（改上游文件，追加一行） |

**两条门槛（务必区分，别混为一谈）**：

1. **硬门槛 —— 有无 `aiMetadata`。** 这是唯一的开关。没声明就完全不可见，声明了才进入候选集。
2. **次门槛 —— 参数信息能否被 AI 看到。** 只有在通过门槛 1 之后才有意义，且分三种情形：
   - **参数集静态**（绝大多数模块，如 `call_workflow`）：`buildToolDefinition` 直接读 `getInputs()` 就得到，**声明即可用，无需额外工作**。
   - **参数值需运行时才知**（如「选哪个工作流」）：schema 合法，但 AI 不知道参数该填什么**值**，需模块自备目录/上下文注入。
   - **参数「键」本身需运行时才知**（重写 `getDynamicInputs` 且依赖步骤参数，如 `call_function`）：`baseStep = createSteps()` 里没有前置参数 → `getDynamicInputs` 只返回**静态键**（如 `workflow_id`）、动态键缺失 → 在 `additionalProperties: false` 下，AI 填的**动态参数键会被拒**，必须特殊处理（把签名注入 schema 生成路径）。

> [`../function-workflow.md`](../function-workflow.md) 主要记录函数工作流本身的需求与实现状态；本文 §5 关于「AI 如何感知 call_function」的结论**仅存在于本文**，`function-workflow.md` 中无对应章节。

---

## 7. 优化方向汇总（供后续扩展选点）

> 汇总 §2.10 / §3.1 / §4.1 的短板，按「改动量 vs 收益」给一个建议顺序。**本节是建议，不是定稿方案**；具体设计另行评估。

| 优先级 | 方向 | 涉及链路 | 为什么 |
|---|---|---|---|
| **P0** | **让失败可见**：`buildParameters` 遇未知键不再静默丢弃，改为回传模型错误；`requiredInputIds` 接入运行时校验 | A | 改动小、直接决定 Agent 可信度。当前「模型以为成功、实际参数被丢」是最危险的失败模式 |
| **P0** | **上下文预算管理**：按 token 计数裁剪/摘要历史，替代全量 `forEach` | A | 长会话最先崩的地方；Agent 单次节点树 dump 就很大 |
| **P1** | **资产目录注入层**：统一的「工作流清单 + 函数签名」目录，供 `call_workflow`/`call_function` 共用 | A（B 亦可复用） | 一次投入修掉两个模块，也是「让 AI 知道有什么」的地基 |
| **P1** | **生产轮数上限**：给聊天路径加可配置的最大工具轮数（对齐基准的 24） | A | 当前生产零限制，安全默认反了 |
| **P1** | **schema 生成与 `createSteps()` 解耦**：动态参数模块提供专门的 AI 视图输入 | A | 根治 `call_function` 类「动态键进不了 schema」 |
| **P2** | **链路 B 补反馈闭环**：生成结果接 `WorkflowValidator` + dry-run，带报错回炉 | B | 比给 B 加工具调用更贴合其「配置生成器」定位 |
| **P2** | **统一能力视图**：让 B 复用 A 的 `aiMetadata` + `getDynamicInputs` | A/B | 消除「同一模块两套描述、加能力改两处」 |
| **P2** | **统一密钥/Provider 配置**：链路 B 的 `ai_config` 与链路 C 的硬编码模型接入 `ChatProviderConfig` | A/B/C | 同一 API key 用户填三遍 |
| **P2** | **收敛链路 C 的双视觉 Agent**：`AgentModule` 与 `AutoGLMModule` 合并或抽公共层 | C | 同类能力两份实现，维护成本翻倍、行为不一致 |
| **P3** | **统一 scope 判定极性**：三套规则（两白一黑）收敛，或把 `SAVED_WORKFLOW` 纳入枚举 | A | 纯认知负担，无功能影响 |
| **P3** | **清理遗留**：冗余的 `LEGACY_DIRECT_TOOL_MODULE_IDS`、死代码 `ToolSchemaGenerator.kt` | A | 降低后续走查成本 |
| **P3** | **提示词外置 + 版本化**：A/B/C 的提示词目前都硬编码在 Kotlin | A/B/C | 可维护性 |

---

## 附录 A：统计口径与数字

**口径**：人工走查 `app/src/main/java/**/*.kt`，以 `ModuleRegistry.register(...)` 为模块全集，统计 `aiMetadata` 声明形式。**仓库内无配套自动脚本**，数字会随代码漂移，引用前请以代码为准。

| 项 | 数值 |
|---|---|
| `ModuleRegistry.initialize()` 注册实例总数 | **188** |
| 声明了 `aiMetadata` 的注册模块 | **103**（188 − 85） |
| 声明 `DIRECT_TOOL`（`directToolMetadata`）的模块 | **59** |
| 声明 `TEMPORARY_WORKFLOW` 的模块 | **100**（59 个 direct 隐含 + 41 个仅临时） |
| 可用于 `SAVED_WORKFLOW` 的模块 | 约 **184**（188 − template/snippet − 3 个 AI 模块） |
| 工具总量（`toolsByName`） | **72**（1 + 1 + 11 + 59） |
| 技能数（`SKILL_CATALOG`） | **14** |

> **口径细节**：上表「声明了 `aiMetadata` 的模块」按**声明处**计数，共 103 处，分布在 100 个文件（98 文件各 1 处；`LoopModule.kt` 2 处；`IfModule.kt` 3 处）。这 103 处均落在已注册模块类内。

**仅临时（仅声明 `TEMPORARY_WORKFLOW`、不含 `DIRECT_TOOL`）的 41 个模块**：

```
vflow.data.aes              vflow.data.base64             vflow.data.calculation
vflow.data.des              vflow.data.get_current_time   vflow.data.hash
vflow.data.math_expression  vflow.data.parse_json         vflow.data.parse_xml
vflow.data.rc4              vflow.data.sm4                vflow.data.text_extract
vflow.data.text_processing  vflow.data.text_replace       vflow.data.text_split
vflow.data.url_codec        vflow.device.find.text        vflow.file.scale_image
vflow.interaction.find_image vflow.logic.break_loop       vflow.logic.continue_loop
vflow.logic.if.end          vflow.logic.if.middle         vflow.logic.if.start
vflow.logic.loop.end        vflow.logic.loop.start        vflow.network.get_ip
vflow.network.http_request  vflow.network.webhook_push    vflow.notification.find
vflow.system.get_battery_status vflow.system.get_usage_stats vflow.system.invoke
vflow.system.js             vflow.system.lua              vflow.system.read_sms
vflow.system.systeminfo     vflow.variable.create         vflow.variable.get
vflow.variable.modify       vflow.variable.random
```

> v1.0 曾漏列 `vflow.data.base64` 与 `vflow.device.find.text`（故写作 39 个）。

---

## 附录 B：关键文件索引

| 关注点 | 文件 |
|---|---|
| 技能定义与提示词组装 | `ui/chat/ChatAgentSkillRouter.kt` |
| 工具注册表 / scope 判定 / schema | `ui/chat/ChatAgentToolRegistry.kt` |
| 原生 helper 定义与执行 | `ui/chat/ChatAgentNativeTooling.kt` |
| 工具批次准备与执行 | `ui/chat/ChatAgentModuleExecutor.kt` |
| 对话状态机 / 审批 / 续轮 | `ui/chat/ChatViewModel.kt` |
| 请求构造 / 供应商适配 | `ui/chat/ChatCompletionClient.kt` |
| 预设与默认提示词 | `ui/chat/ChatModels.kt`、`ui/chat/ChatPresetRepository.kt` |
| AI 元数据模型 | `core/module/AiModuleMetadata.kt` |
| AI 生成工作流 | `ui/workflow_editor/WorkflowAiGenerator.kt` |
| 工作流内 AI 模块 | `core/workflow/module/network/AIModule.kt`（id `vflow.ai.completion`）、`interaction/AgentModule.kt`、`interaction/AutoGLMModule.kt`、`integration/OperitModule.kt` |
| 基准测试（非生产） | `ui/chat/ChatBenchmark*.kt` |
| **遗留/死代码** | `core/utils/ToolSchemaGenerator.kt`——把任意模块转成 OpenAI Function Definition 的旧实现，全仓库除自身外**零引用**。与 §2.4 的 `ChatAgentToolRegistry.buildToolDefinition` 是两套并行实现，**当前无人使用**，勿误当作在用链路。 |
