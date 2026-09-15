# Fork 维护说明

本仓库是 [ChaoMixian/vFlow](https://github.com/ChaoMixian/vFlow) 的长期维护 fork：

- `origin`   → https://github.com/nightking8342/vFlow （本 fork，主干 `dev`）
- `upstream` → https://github.com/ChaoMixian/vFlow （上游）

维护原则：**控制 diff 面积**。能加新文件就不改上游文件，能走新增模块就不动核心代码，让每次上游合并的冲突尽量少。

本项目与 mindfs fork 不同：**以「新增能力」为主**（新增模块、新增 handler、新增工具脚本），而非深改上游核心文件。因此本表的分歧形态大多是「新增文件 / 新增模块」，冲突面天然较小。

---

## 与上游的分歧清单

每一处与上游的故意分歧都记录在这里。合并上游遇到冲突时，按「归属」列决定保留哪边；新增分歧时必须同步更新本清单。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `FORK.md`、`AGENTS.md`、`CLAUDE.md` | fork 独有文件，上游没有 | 我方 |
| `docs/fork/function-workflow.md`、`docs/fork/function-workflow-ui.html` | fork 独有：函数工作流需求文档 + 可交互 UI 原型（上游无此文件） | 我方 |
| `docs/fork/do-not-disturb-trigger.md` | fork 独有：**免打扰模式触发器**功能文档（需求/设计决策/实现状态/真机验证待办/自触发环风险）。含 AOSP 源码核实的 API 选型结论，上游无此文件 | 我方 |
| `core/workflow/module/triggers/DoNotDisturbTriggerModule.kt`、`.../handlers/DoNotDisturbTriggerHandler.kt`（均新增） | fork 独有：免打扰触发器。走 `ACTION_INTERRUPTION_FILTER_CHANGED` 广播，只需 `NOTIFICATION_POLICY` 权限（不需通知使用权）。含顶层纯函数 `isDndFilterEnabled` / `isDndEnabled` / 日志探针 `probeDndState` | 我方 |
| `res/drawable/rounded_do_not_disturb_on_24.xml`（新增） | fork 独有：免打扰触发器图标（上游无勿扰图标，`DoNotDisturbModule` 借用的是 `rounded_notifications_unread_24`） | 我方 |
| `test/.../triggers/DoNotDisturbTriggerMathTest.kt`、`DoNotDisturbTriggerModuleTest.kt`（均新增） | fork 独有：上述纯函数逐值锁定 + 枚举规范化 + 权限/输出声明体检 | 我方 |
| `core/workflow/module/ModuleRegistry.kt` | `initialize()` 按分类追加注册 `DoNotDisturbTriggerModule`（不重排已有注册） | 手动合并（追加一行，取上游 + 追加） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 按分类追加注册 `DoNotDisturbTriggerHandler`（不重排已有注册） | 手动合并（追加一行，取上游 + 追加） |
| 字符串资源 `strings_module.xml`（中/英/日） | 追加免打扰触发器文案 9 条 ×3 语言 | 手动合并（追加条目） |
| `docs/fork/chat-agent-enhancement-plan.md` | fork 独有：Chat Agent 四点改造方案（技能目录化/Prompt 缓存/catalog 全量化+模块查询工具/悬浮窗），上游无此文件 | 我方 |
| `docs/fork/chat-agent-rearchitecture.md` | fork 独有：**Chat Agent 架构重构设计**（基于 **CCB / dsh / OpenCode / Pi 四家**源码对照）。v1.5.3 为决策定稿版：三病症诊断、四家技能注入位置与工具暴露策略对照（含"内建工具 vs 扩展工具"的关键区分）、目标架构（**工具 72→16**：撤出 59 个模块工具，改由 `query_module_schema` + `call_module` + `load_skill` 按需）、**查询域≠调用域**的设计 B、两批执行契约与验收项（§4）、长期分叉的接管范围（§5.1）、决策台账（§6）。上游无此文件 | 我方 |

### Chat Agent 架构重构（第二批，2026-09-14）—— 本表冲突面最大的一批

> ⚠️ 这批改动**直接修改上游核心文件**，与此前「只加新文件」的 fork 风格不同。
> 已在 `docs/fork/chat-agent-rearchitecture.md` §5 做过冲突面评估，决策是**认长期分叉**。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `ui/chat/ChatAgentSkillRouter.kt` | **大幅重写**（847 → 193 行）：删除 `selectSkills` 及四套关键词机制（`SkillRule` / `CONTINUATION_SIGNALS` / `KNOWLEDGE_QUESTION_SIGNALS` / `OPERATIONAL_SIGNALS` / `moduleIds` 白名单 / 12 个辅助函数）；`ChatAgentSkillDefinition` 去掉 `toolNames`/`moduleIds`/`relatedSkillIds` 三字段；`SKILL_CATALOG` 清空；`ChatAgentSkillSelection` 去掉 `skills` 字段；新增 `availableTools()` 与 `skillListing()`/`skillInstructions()` 访问器；`buildSystemPrompt` 重写（去 "Active skills:" 段，加 `<available_skills>` 段与 6 条上提的规则，再加 1 条工具输出截断契约） | **我方**（认长期分叉） |
| `ui/chat/ChatAgentToolRegistry.kt` | `toolsByName` 撤出 59 个模块工具（`buildDirectToolDefinitions()` 不再进常驻表）；新增 **5 个**工具定义 `load_skill` / `query_module_schema` / `call_module` / `list_workflows` / `get_environment`；`ChatAgentToolDefinition` 加 `truncatable` 字段；`buildCompactModuleCatalog` 精简为只留 moduleId；`buildModuleOutputCatalog` 停用；新增 `getUsageScopesForModuleId()` / `getQueryableModuleIds()`；**`buildInputDescription` / `artifactTypeLabelFromTypeId` 提为顶层函数**（`buildModuleInputDescription` / `artifactTypeLabel`）供 executor 复用 | **我方**（认长期分叉） |
| `ui/chat/ChatAgentModuleExecutor.kt` | `buildParameters` 重写（并集两轮求值 + 显式报错）；新增 `resolveModuleInputDefinitions` 顶层函数、`ChatParameterBuildResult` 数据类、`prepareLoadSkill` / `prepareQueryModuleSchema` / `prepareCallModule` / `prepareListWorkflows` / `prepareGetEnvironment` / `buildRejectedKeysMessage`、**`visibleInputsForAgent` 顶层函数**（AI 侧不读 `isHidden`，只认 `visibility`）；`ImmediateResult` 加 `riskLevel` 字段（默认 HIGH）；`riskLevelOf` 改读 `item.riskLevel`；`prepareToolCall` 加五个按需入口分流 | **手动合并**（改动集中在这几个函数，若上游改同区域需逐块判断） |
| `ui/chat/ChatCompletionClient.kt` | `ChatToolResultInputFormatter.format()` 加 `toolDefinitions` 参数（按 `truncatable` 决定是否截断），三处调用点传参；截断标记由 `... truncated` 字面量改为 `buildTruncationNotice()`（结构化三段告知 + 按工具收窄建议，P2-1a）；Anthropic 路径的 `system` 由字符串改为块数组并加 `cache_control`，`buildAnthropicToolDefinitions` 最后一个工具加 `cache_control`；新增 `ephemeralCacheControl()` | **手动合并**（局部改动） |
| `ui/chat/ChatViewModel.kt` | `selectSkills(...)` 调用改为 `availableTools(...)`；日志去掉已删的 `skills` 字段（此处已有 `exportConversation` 的 fork 改动） | **手动合并** |
| `ui/chat/ChatBenchmarkRunner.kt` | `selectSkills(...)` 调用改为 `availableTools(...)` | **手动合并** |
| `test/ui/chat/ChatAgentToolingTest.kt` | 删除 15 个验证已死机制的 `skillRouter_*` 测试；改写 4 个；新增 `availableToolsPassesThroughEverythingWithoutKeywordFiltering` / `systemPromptOmitsSkillListingWhenCatalogIsEmpty` / `systemPromptCarriesTheContentSalvagedFromDeletedSkills` / `toolResultFormatter_truncationNoticeReportsOmittedSizeAndRecoveryHint` / `toolResultFormatter_truncationNoticeGivesGenericHintForOtherTools`；删除 3 个已无调用者的辅助函数 | **我方** |
| `test/ui/chat/ModuleInputDefinitionsTest.kt`（新增） | fork 独有：`resolveModuleInputDefinitions` 的回归测试（含对全部已注册模块的「静态键不丢失」体检） | **我方** |
| `test/core/workflow/module/logic/DefineFunctionAiParametersTest.kt`（新增） | fork 独有：`normalizeAiParameters` 的回归测试（简写→全限定类型转换、默认值保留、空名丢弃、JSON 字符串原样放行、零参数函数、ANY 声明、无默认值） | **我方** |
| `test/core/execution/VariableResolverTest.kt` | 补 1 例：锁定「`{{vars.<name>}}` 解析成功、裸写 `{{<name>}}` 不解析」两条分支语义。防止将来有人"放宽"裸写法时无意改变行为 | **我方**（新增用例） |
| `docs/fork/chat-float-window-design.md` | fork 独有：Chat 悬浮窗需求设计 + 实施交接（折叠/展开双形态、Application 作用域共享 VM、窗内审批 + 透明中转 Activity；§9.1 P0 验证结论、§9.2 P1 实现状态与动画失败结论），上游无此文件 | 我方 |
| `docs/fork/chat-float-window-ui.html` | fork 独有：Chat 悬浮窗可交互 UI 原型（折叠/展开/审批/输入/状态一致性五组演示），上游无此文件 | 我方 |
| `ui/chat/ChatFloatWindowService.kt`、`ChatFloatPanelContent.kt`、`ChatFloatSummary.kt`、`ChatFloatWindowLauncher.kt`、`ChatFloatGeometry.kt`（均新增） | fork 独有：Chat 悬浮窗实现（P1 折叠态）。Service=窗口/拖动/吸附/展开折叠；PanelContent=折叠态 Compose UI；Summary=文案推导；Launcher=权限与启动；Geometry=锚定边计算 | 我方 |
| `ui/main/MainComposeShell.kt` | ① Chat 顶栏 actions 新增「悬浮窗」按钮；② ChatViewModel 获取由 `viewModel()` 改为 `ChatViewModelHolder.get()`（共享给悬浮窗 Service）；③ `ChatTopBarAction` 枚举新增 `ShowFloatWindow` | **手动合并**（4 处追加/替换，若上游改同区域需逐块判断） |
| `AndroidManifest.xml` | 追加 `ChatFloatWindowService` 声明（`foregroundServiceType="specialUse"`） | **手动合并**（追加声明） |
| `res/values/strings.xml`、`values-en/`、`values-ja/` | 追加悬浮窗文案（中/英/日） | 手动合并（追加条目） |
| `ui/chat/ChatViewModelHolder.kt`（新增） | Chat 悬浮窗：Application 作用域唯一 `ChatViewModel` 持有者（App 与悬浮窗 Service 共用同一实例，避免会话分裂） | 我方 |
| `ui/chat/ChatConversationExport.kt`（新增） | fork 独有：会话导出（完整 JSON 保真，落 `/sdcard/vFlow/exports/` + FileProvider 分享）。导出 UI 上游只有 benchmark 日志，无会话导出 | 我方 |
| `test/ui/chat/ChatConversationExportTest.kt`、`ChatFloatGeometryTest.kt`、`ChatFloatSummaryTest.kt`（均新增） | fork 独有：上述 fork 能力的单测。上游无对应测试文件 | 我方 |
| `ui/chat/ChatViewModel.kt` | 新增 `exportConversation(conversationId)` 方法（复用既有 `_events` 提示通道，不改变现有签名） | 手动合并（新增方法） |
| `ui/main/MainComposeShell.kt` | `ChatHistorySideSheet` / `ChatHistorySideSheetItem` 追加导出图标与 `onExportConversation` 回调（含调用点接线） | 手动合并（追加参数/按钮） |
| 字符串资源 `strings*.xml` | 新增会话导出文案 `chat_export_conversation` / `chat_export_share_title` / `chat_export_failed`（中/英/日） | 手动合并（追加条目） |
| `docs/fork/surveys/`（`README.md` + `ai-system-overview.md` + `agent-design-comparison.md`） | fork 独有：**现状调研文档目录**（改代码前的地图）。`README.md` 为索引+写作规范；`ai-system-overview.md` 为 AI 体系梳理（三套链路/提示词/工具/技能/执行流程/模块可发现性/缓存 + 能力评估）；`agent-design-comparison.md` 为头部 Agent 项目外部对照调研。上游均无此文件 | 我方 |
| `core/workflow/model/Workflow.kt` | 新增 `functionSignature` 字段（Parcelable，带默认值 null，向后兼容） | 手动合并（fork 追加字段，若上游也改需逐块判断） |
| `core/workflow/model/FunctionSignature.kt`（新增） | 新增 `FunctionParam`/`ReturnKey`/`FunctionReturn`/`FunctionSignature` 数据类 | 我方 |
| `core/execution/WorkflowExecutor.kt` | `executeSubWorkflow` 增加可选参数 `injectedVariables: Map<String, VObject> = emptyMap()`（带默认值，不破坏现有调用） | 手动合并（向上游方法加带默认值参数，若上游改了该方法需逐块判断） |
| `core/workflow/module/logic/DefineFunctionModule.kt`、`CallFunctionModule.kt` + UIProvider（新增） | 新增函数工作流模块（定义函数+调用函数） | 我方 |
| `core/module/AiParameterNormalizer.kt`（新增） | fork 独有：模块可选的「AI 入参规范化」钩子。用于参数形态是结构化数据、但存储形态是 JSON 字符串的模块——AI 与编辑器走两条写入路径，本接口让模块把 AI 的值收敛到与编辑器一致。目前只有 `DefineFunctionModule` 实现 | 我方 |
| `core/workflow/module/logic/DefineFunctionModule.kt` | `getInputs()` 由 `emptyList()` 改为声明 `functionParams`（`ParameterType.ANY`）——AI 只认 `getInputs()`，缺声明就会把该键判为非法而拒绝写入；实现 `AiParameterNormalizer`（简写类型 → 全限定 ID、丢弃空名项、Gson 序列化）；补 `aiMetadata`（`workflowStepDescription` + `inputHints`，**不设** `requiredInputIds`）；新增 `FUNCTION_PARAMS_KEY` 常量 | 我方（fork 新增文件内完善） |
| `ui/chat/ChatAgentModuleExecutor.kt` | `buildParameters` 尾部接入 `AiParameterNormalizer` 钩子（模块可选用 `parameters +` 规范化结果，未实现者原样透传） | **手动合并** |
| `ui/chat/ChatAgentToolRegistry.kt` | `save_workflow` / `temporary_workflow` 的 `parameters` description 补充两种引用形式（`{{stepId.outputId}}` 与 `{{vars.paramName}}`），并点明裸写不解析 | **手动合并**（两处同文替换） |
| `ui/chat/ChatCompletionResult.kt` | 加三个可空字段 `cacheCreationTokens` / `cacheReadTokens` / `cacheDeletedTokens`（默认 null，其余三处构造点不传，符合设计——OpenAI 系无此概念）。用于观测 Anthropic prompt 缓存是否命中 | **手动合并**（追加带默认值字段） |
| `ui/chat/ChatCompletionClient.kt` | Anthropic 解析补提取上述三个缓存字段（`extractAnthropicCacheTokens`） | **手动合并**（追加提取） |
| `ui/chat/ChatViewModel.kt` | `Model reply` 日志追加 `cacheCreate` / `cacheRead` / `cacheDeleted` 三项 | **手动合并**（日志字符串追加） |
| `ui/chat/ChatAgentToolRegistry.kt`、`ui/chat/ChatAgentSkillRouter.kt`、`core/module/AiParameterNormalizer.kt`、`core/workflow/module/logic/DefineFunctionModule.kt` | 函数参数引用语法修复：`parameters` description 与 `inputHints` 与 system prompt 三处补充 `{{vars.<name>}}` 说明 | **手动合并** |
| `core/workflow/module/logic/DefineFunctionParamEditorSheet.kt`（新增） | 新增「定义函数」参数编辑底部弹窗（参数名/类型/默认值/必填） | 我方 |
| `core/workflow/model/FunctionParamValidator.kt`（新增） | 新增参数名校验工具（snake_case + 去重，纯 Kotlin 可单测） | 我方 |
| `core/workflow/module/logic/FunctionParamTypeMapper.kt`（新增） | 新增参数类型「简写值↔完整类型ID」映射（修正保存后类型退化成「任意」的 bug） | 我方 |
| `core/module/definitions.kt` | `InputDefinition` 追加 `isRequired` + `getDisplayName`（带默认值，向后兼容，驱动必填 `*` 标记） | 手动合并（追加字段/方法，若上游也改需逐块判断） |
| `core/module/definitions.kt` | 新增 `OutputKeyDefinition` 数据类 + `OutputDefinition` 追加 `dictionaryKeys`（带默认空列表，向后兼容，驱动选择器展开函数返回值键） | 手动合并（追加字段，若上游也改需逐块判断） |
| `ui/workflow_editor/MagicVariablePickerSheet.kt` | `MagicVariableItem` 追加 `dictionaryKeys`；导航页新增「函数返回值」声明键分组；无属性但有声明键时也进入导航页 | 手动合并（追加字段/渲染分支） |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | `buildPickerModel` 透传 `outputDef.dictionaryKeys` 到选择器项 | 手动合并（追加参数传递） |
| `core/module/ModuleRegistry.kt` | `initialize()` 按分类追加注册 `DefineFunctionModule`、`CallFunctionModule`（不重排已有注册） | 手动合并（追加两行，取上游 + 追加） |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | `buildNamedVariables()` 追加「函数参数」分组；`buildPickerModel` 透传 `outputDef.dictionaryKeys` | 手动合并（追加逻辑） |
| `ui/workflow_editor/EditorMoreOptionsSheet.kt` | 加函数签名状态行 | 手动合并（追加只读展示） |
| `ui/workflow_editor/WorkflowEditorActivity.kt` | 删除「定义函数」卡片清空签名+提示；移动时约束其必须为首位 | 手动合并（追加逻辑） |
| `ui/workflow_editor/DefineFunctionModule.kt` | `validate()` 校验参数名（snake_case + 去重）；摘要展示类型/必填 | 我方（fork 新增文件内完善） |
| `ui/workflow_editor/DefineFunctionModuleUIProvider.kt` | 参数列表改为可编辑行（类型徽标/必填/默认值预览）+ 返回值只读区 | 我方（fork 新增文件内完善） |
| `res/layout/sheet_define_function_param_editor.xml`（新增） | 「定义函数」参数编辑弹窗布局 | 我方 |
| `res/layout/partial_call_function_params.xml`（新增） | 「调用函数工作流」参数赋值区容器 | 我方 |
| `res/layout/partial_call_function_param.xml`（新增） | 「调用函数工作流」单个参数行布局（header+值区+魔法按钮） | 我方 |
| `res/layout/item_function_param.xml`（新增） | 「定义函数」参数列表卡片行布局 | 我方 |
| `res/layout/view_define_function_add_param_button.xml`（新增） | 「定义函数」添加参数按钮（Material3 TextButton + ic_add） | 我方 |
| `ui/workflow_editor/ActionEditorSheet.kt` | `input_name` 改用 `getDisplayName`（显示必填 `*`） | 手动合并（追加逻辑） |
| `ui/workflow_editor/DictionaryKVAdapter.kt` | 新增 `updateValueForKey(key, value)` 方法（函数参数字典元素引用变量） | 手动合并（新增方法，不改变现有签名） |
| `core/workflow/module/logic/CallFunctionModule.kt` / `DefineFunctionModule.kt` | 摘要实时读 `functionParams`；必填校验空值；动态类型分派 | 我方（fork 新增文件内完善） |
| `res/layout/sheet_editor_more_options.xml` | 追加函数签名状态卡片 | 手动合并（追加卡片） |
| 字符串资源 `strings*.xml`、`strings_module.xml` | 新增函数工作流 UI 文案（中/英/日） | 手动合并（追加条目） |

> **新增分歧时**：必须写清「文件/范围」「分歧内容」「冲突归属」三列。冲突归属一般是：
> - **我方**：fork 独有的新增文件/新增模块，保留我方。
> - **上游**：上游改动的文件，取上游版本。
> - **手动合并**：两边都改了同一文件，需逐块判断（例如 fork 只是在某文件追加了几行，而上游也改了该文件）。

---

## 暂未分歧、但日后改动时须登记的敏感点

以下是上游的核心区。目前 fork **尚未改动**它们；一旦改动（尤其是结构性改动），必须在上表登记，并评估合并成本：

- `app/build.gradle.kts` —— 编译配置、签名、ABI、依赖。上游可能频繁变更，改动时冲突面大。
  - ⚠️ **已有分歧（2026-09-15）**：`versionCode 49 → 50`、`versionName "1.5.3-pr1" → "1.5.4"`（提交 `3360e63b`）。
    fork 首次自定版本号——此前 `1.5.3-pr1` 是上游 5 月定的。**合并上游时此处取上游**，
    然后按需重新决定 fork 号段。冲突面小（两行），但每次上游 bump 版本号都会撞上。
- `settings.gradle.kts` —— 模块声明（`:app` `:core`）。
- `app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt` —— 模块注册表。**新增模块时在 `initialize()` 里按分类追加一行即可，不要重排已有注册**，否则每次上游合并都在这个文件解冲突。
- `core/src/main` —— vFlow Core 独立进程（Master-Worker）。改动独立，应单独评估、单独 patch。
- `app/src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt` —— 工作流执行器核心循环。改动风险高，须谨慎。
- `app/src/main/java/com/chaomixian/vflow/api/` —— 远程 API。**新增 handler 时新增文件，不要改既有接口签名**。
- `app/src/main/java/com/chaomixian/vflow/core/workflow/model/Workflow.kt` / `ActionStep.kt` —— 工作流数据模型。上游改动会波及大量解析/序列化代码。

---

## 上游同步流程

跟着上游的 **release tag** 合并（而不是追每个 commit），因为上游节奏是「低频发版」。步骤：

1. 工作区必须干净（`git status` 无未提交改动）。
2. `git fetch upstream --tags`
3. `git checkout master` 并 `git fetch upstream`
4. `git merge <tag>`（如 `git merge v1.5.2`；merge 而非 rebase，保留独立 merge commit，不 squash）
5. 解冲突：按上表「冲突归属」处理；表里没有的冲突按常规判断并考虑是否登记。
6. 门槛检查：`./gradlew test`（app 模块有单元测试）+ `./gradlew assembleDebug` 构建通过。
   - 注意：Android 项目在 Windows 本地跑 `./gradlew test` 需要 Android SDK + JDK 17 就绪；若环境缺失，以 CI 的构建结果为权威门槛。
   - 涉及 `core/` 或原生 OCR（ncnn/CMake/JNI）时，还需确认 `:core:buildDex` 与 `externalNativeBuild` 通过。
7. 回归扫描：`git grep -nE "(chaomixian|vflow)\.(com|net|app)" -- ':!*.md'` 对比合并前后，确认上游没有引入意料之外的硬编码地址；有则评估处理并登记。
8. 全部通过后 `git checkout dev` 并 `git rebase master`（把 dev 重基于最新上游），再 `git push -u origin dev`。
   - rebase 时若你的 fork 改动与上游冲突，按「冲突归属」处理；这也是一次检查 diff 面积的机会——冲突越多，说明改动太贴近上游核心，应回头评估是否拆成新增文件。

---

## 提交规范

- **fork 自己的改动**：提交信息加 `fork:` 前缀（如 `fork: 新增 AI 调试生成工作流模块`），方便 `git log` 区分来源。
- **新增模块 / 新增文件**：`fork:` 前缀 + 说明分类与用途。
- **上游合并**：保留默认 merge commit 信息（`Merge tag 'v1.5.2' ...`）。

---

## 分支约定

- `master` → 只追踪上游，永远只从上游同步，不改。
- `dev` → fork 主干，承载所有 fork 改动；上游同步后 rebase 到 master。
- `feature/*` → 每次开发用的临时分支，完成后合并回 dev。
- 不建议直接在 `master` 上开发。
