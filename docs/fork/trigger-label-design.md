# 触发器标签（trigger label）设计

> 版本：v1.0（设计阶段，**未实现**）
> 目录归属：**fork 独有**（冲突归我方），上游无此文件
> 用途：给「一个工作流的多个触发器」打标签，让工作流内部能判断**本次是哪个触发器触发了它**
> 相关：`surveys/trigger-system-overview.md`（触发器体系现状）、`workflow-read-write-tools.md`（AI 读写工作流）、`log-module-design.md`（固定变量注入的同类先例）

---

## 0. 一句话需求

一个工作流可以挂多个触发器（现有 28 个触发器模块，`Workflow.triggers: List<ActionStep>`）。
目前**任何触发器命中都执行同一套 `steps`，且无法在工作流内分辨是谁触发的**。
本设计给每个触发器打一个标签，工作流内用固定变量读到**命中那个触发器的标签**，从而分支执行。

---

## ★ 1. 命名定案（先钉死这一节，全文不再讨论命名）

**存储、工作流内引用、AI 写入 —— 三处是同一个名字：`__trigger_label`。**

| 场景 | 名字 | 说明 |
|---|---|---|
| 存储位置 | `ActionStep.parameters["__trigger_label"]` | 触发器本身就是 `ActionStep`，标签存在它的 `parameters` 里 |
| 工作流内引用 | `[[__trigger_label]]` | 命名变量语法，值是字符串 |
| AI 写入 / 读取 | `__trigger_label` | 在 `workflow.triggers[].parameters` 里，原样 |
| 变量类型 | `VString` | 未命中 / 未设置时是**空串**，不是 `VNull` |

**双下划线前缀的理由**：与既有的保留参数风格一致 —— `ActionStepExecutionSettings.KEY_ERROR_POLICY = "__error_policy"`、`KEY_RETRY_COUNT = "__retry_count"`、`KEY_RETRY_INTERVAL = "__retry_interval"`
（`core/workflow/model/ActionStepExecutionSettings.kt:9-11`）。这批 key 的共同特征是：**不是模块声明的输入参数**，但存在 `step.parameters` 里。

> ⚠️ **只有一个名字是刻意的**。「存储叫 X、引用叫 Y」会让实现者与用户各记一套，而写错的后果是**静默不匹配**（见 §6.1）。本仓库已多次因「同名不同物 / 同物不同名」踩坑，这里不给它机会。

> ⚠️ **变量名以下划线开头是合法的**：负责校验变量名的 `FunctionParamValidator.isValidName`（`core/workflow/model/FunctionParamValidator.kt:33-39`）只禁 `. [ ] $ { } 空格 / 制表 / 换行`，不看首字符；`VariablePathParser.parsePath` 亦不限制。
> 编辑器选择器里**显示名**是「触发标签」，用户看不到 `__`（§4.1）。

---

## 2. 现状（已核查事实）

### 2.1 触发器就是 ActionStep

| 事实 | 位置 |
|---|---|
| `Workflow.triggers: List<ActionStep>`，与 `steps` 同类型 | `core/workflow/model/Workflow.kt:13` |
| `ActionStep = (moduleId, parameters, isDisabled, indentationLevel, id)` | `core/workflow/model/ActionStep.kt:11-19` |
| 触发器身份 = `ActionStep.id`（UUID） | 同上；`Workflow.getTrigger(id)` 按 `it.id == triggerId` 查（`Workflow.kt:80`） |
| 运行时视图 `TriggerSpec.triggerId = "$workflowId:$stepId"`（**仅 Handler 内部用**） | `core/workflow/model/TriggerSpec.kt:9` |

⇒ **加标签不需要动数据模型**，`parameters` 本来就是 `Map<String, Any?>`，且已由 Gson 整对象序列化（`WorkflowManager.kt:116/143`）、备份走全字段 Gson（`WorkflowScope.kt`）。
⇒ 旧记录没有这个 key ⇒ 读到 `null` ⇒ 归一成空串，**不需要任何兼容映射**。

### 2.2 触发链路（现状）

```
系统事件 → Handler 过滤 → BaseTriggerHandler.executeTrigger(context, trigger, triggerData)
  → TriggerExecutionCoordinator.executeTrigger(...)
       → WorkflowExecutor.execute(workflow, ctx, triggerData, triggerStepId = trigger.stepId)   ← :55-60
            → seedTriggerOutputs(workflow, initialContext, triggerStepId)                      ← :304 / :331
            → executeWorkflowInternal(...)
```

- **只传 `stepId`，复合 `triggerId` 不进执行上下文**（`TriggerExecutionCoordinator.kt:59`）。
- 触发器输出以 `stepOutputs[triggerStep.id]` 为键存放（`WorkflowExecutor.kt:517`），下游用 `{{<stepId>.<outputId>}}` 引用（`VariableResolver.kt:172-182`）。
- ⚠️ `seedTriggerOutputs` 会给**所有同 moduleId 且 output schema 相同**的触发器都写同一份输出（`:511-518`）⇒ **下游拿不到「到底是谁触发的」**。这正是本需求要解决的问题。

### 2.3 命名变量的现有通路（本设计复用它）

| 环节 | 位置 | 说明 |
|---|---|---|
| 解析 | `VariableResolver.kt:146-160` | `[[name]]` 与 `{{vars.name}}` 都查 `context.namedVariables[name]` |
| 构造引用 | `VariablePathParser.buildNamedVariableReference(name)` → `{{vars.<name>}}`（`[]` 形式由 `TemplateParser` 归一） | `VariablePathParser.kt:180-186` |
| 分组来源 | `WorkflowEditorMagicVariableCatalogBuilder.buildNamedVariables()`：扫 `CreateVariableModule` / 载入变量 / **函数参数** / 全局变量 | `WorkflowEditorMagicVariableCatalogBuilder.kt:64-145` |
| 选择器渲染 | 命名变量分组**排在最前**，与 `#N` 步骤分组分离 | `MagicVariablePickerSheet.kt:186-194` |

⇒ **复用「函数参数」分组的既有做法**（fork 新增的 `buildFunctionParamsGroup`）即可获得一个固定分组，无需新造机制。

### 2.4 已核查的两处「会静默吃掉标签」的路径（本设计必须处理）

**① 编辑器保存触发器时整表替换参数**（`WorkflowEditorActivity.kt:982-1001`）

```kotlin
editor.onSave = { newStepData ->
    if (position != -1) {
        if (focusedInputId != null) {
            val updatedParams = triggerSteps[position].parameters.toMutableMap()   // ← 合并
            updatedParams.putAll(newStepData.parameters)
            triggerSteps[position] = triggerSteps[position].copy(parameters = updatedParams)
        } else {
            triggerSteps[position] = triggerSteps[position].copy(parameters = newStepData.parameters)  // ⚠️ 整表替换
        }
    }
    ...
}
```

`onSave` 拿到的是 `currentParameters.toActionStep(module.id)`（`ActionEditorSheet.kt:185/916`），而 `currentParameters` 只由**模块声明的输入**构成（`ActionEditorSessionState.kt` + `ActionEditorUiModel.kt:43-61`）——**不包含 `__trigger_label`**。
⇒ **else 分支会把标签整表丢掉**：用户设好标签 → 再点开改一下触发条件 → 标签没了。不报错、无提示。

**② AI 侧把未知参数判为 reject**

- `buildParameters`（`ChatAgentModuleExecutor.kt:2168-2177`）：`definitionsById[key] == null ⇒ rejected += key`
- `applyParameterPatch`（`:1776-1784`）：unknown ⇒ 直接返回错误、**整个补丁不落地**
⇒ AI 若不认识 `__trigger_label`，**既写不进、也改不了**，且 `update_workflow` 会因此整份失败。

---

## 3. 决策台账

| # | 决策 | 理由 |
|---|---|---|
| 1 | 标签存在 `ActionStep.parameters["__trigger_label"]`，**不新增数据模型字段** | 零序列化成本、零迁移成本；`ActionStep.parameters` 已是 `Map<String, Any?>`，Gson 整对象已覆盖 |
| 2 | 每触发器**最多一个**标签（字符串） | 用户拍板。多个标签会让引用变成列表、If 不能用「等于」，成本不成比例 |
| 3 | 三处同名 `__trigger_label` | §1 |
| 4 | 下游引用走**命名变量**（`[[__trigger_label]]`），不走触发器输出 | 输出方式要写 `{{<触发器UUID>.label}}`，而 UUID 在编辑器里根本不显示，且多触发器会被 picker 合并成一组（`WorkflowEditorMagicVariableCatalogBuilder.kt:209`）⇒ 等于没解决原问题 |
| 5 | 注入点在 `WorkflowExecutor.execute` 构造 `initialContext` 时，**由 `triggerStepId` 反查标签** | 该处已持有 `workflow` + `triggerStepId`，且 `executeSubWorkflow` 的 `triggerStepId` 传的是 `manualTrigger()?.id`（`:473`），语义自洽 |
| 6 | 未设置 / 未命中 ⇒ **空串 `VString("")`**，不是 `VNull` | `VNull` 在 If 里与 `equals` 比较恒 false，且用户无法区分「没打标签」与「变量名写错」。空串可被 `is_empty` 判出，语义清晰 |
| 7 | 编辑器交互：触发器卡片上**一个标签按钮**，点开小 sheet 输入；**不放进触发器的参数编辑 sheet** | 用户拍板。参数编辑 sheet 是模块定义驱动的，塞非模块参数进去会与 §2.4① 的语义打架 |
| 8 | 标签输入**纯自由输入**，不做已用标签快捷选 | 用户拍板 |
| 9 | AI 侧在 `resolveModuleInputDefinitions` **统一注入**该 key 的 `InputDefinition` | 一处改动同时解决 §2.4② 的两个判定（save / update）。编辑器路径**不经过它**，故不受影响 |
| 10 | AI 侧 **不做友好字段名渲染**，`describeStepLine` 原样输出 | 用户拍板。「原样就行」 |
| 11 | 手动触发器也可打标签，但不特殊处理 | 直接运行时 `triggerStepId = manualTrigger()?.id`（`:473`、`WorkflowEditorActivity.kt:147`），标签自然生效；`autoTriggerSteps()` 排除 manual，无需额外分支 |
| 12 | 标签**无唯一性约束** | 用户自己管理命名；重复标签只会让 `If` 命中多处，属用户意图 |

---

## 4. 交互设计

### 4.1 触发器卡片（编辑器）

落点：`ActionStepAdapter.TriggerGroupViewHolder.bind`（`ActionStepAdapter.kt:349-379`），每个触发器卡片是 `item_action_step` 的内嵌实例，内容区在 `content_container`（`res/layout/item_action_step.xml:57-68`，`LinearLayout`，垂直）。

```
┌──────────────────────────────────────────┐
│▌ 日志触发                        🏷  🗑  │   ← 🏷 = 标签按钮（新增），在 layout_step_actions 里
│  包含 "FATAL"                            │      与既有删除按钮同区
│  🏷 每日巡检                             │   ← 回显行（新增），在 content_container 内
└──────────────────────────────────────────┘
```

- **按钮位置**：`layout_step_actions`（`item_action_step.xml:70-92`）里，删按钮之前。与删按钮同为 `ImageButton` + `?attr/selectableItemBackgroundBorderless`。
- **回显**：`content_container` 末尾追加一行 `TextView`，仅当标签非空时 `VISIBLE`。文案 `🏷 每日巡检`（`string` 资源，三语）。
  - ⚠️ 回显**不能走 `module.getSummary()`** —— 那是模块自己的摘要（`ActionStepAdapter.kt:357`），加不进去。
- **点击**：弹一个极小的 `BottomSheetDialog`（新文件），标题「触发器标签」+ 一个 `TextInputLayout` + 两个按钮（清空 / 确定）。
- 保存后与既有路径一致：`pushUndoSnapshot()` + 写 `triggerSteps[position]` + `recalculateAndNotify()`。

### 4.2 魔法变量选择器

在 `buildNamedVariables` 的返回里**追加一个固定分组**：

```
分组名：触发器标签
  └─ 触发标签          →  [[__trigger_label]]     类型：文本
```

- 与「函数参数」分组同构（`WorkflowEditorMagicVariableCatalogBuilder.kt:147+` 的 `buildFunctionParamsGroup`）。
- **仅当工作流有非手动触发器时才显示**（单手动触发器的工作流显示它没有意义）。
- `variableName` = 「触发标签」（用户看到的），`variableReference` = `[[__trigger_label]]`（§1）。
- ⚠️ 归属：应放在 `namedVariables` 而不是 `stepVariables` —— `stepVariables` 会按 `#N` 排序并把 `#0` 挤到最后（`MagicVariablePickerSheet.kt:196-204`），而这是全局变量，不该被当成某一步的输出。

### 4.3 AI

**AI 的打标签能力**与人工完全等价，走同一条存储 key：

| 工具 | AI 看到的 |
|---|---|
| `save_workflow` | `workflow.triggers[].parameters.__trigger_label`（字符串）。schema description 说明「可选的触发器标签」 |
| `update_workflow` | `triggers.update[].parameters.__trigger_label`（同 §2.4② 的补丁语义，`null` = 删标签） |
| `get_workflow` | 触发器行原样列出 `__trigger_label: 每日巡检`（`describeStepLine` 已经会输出所有非 null 参数，`ChatAgentModuleExecutor.kt:885-891`，**不需要改**） |
| `query_module_schema` | 触发器模块的输入表里**会多出一项** `__trigger_label`（因为 §3.9 的注入） |

**AI 的引用能力**：在 `save_workflow` / `update_workflow` / `get_workflow` 三个工具的 schema 与 system prompt 里说明 `[[__trigger_label]]` 可读「本次命中触发器的标签」。措辞要包含三点：

1. 这是**命名变量**（`[[...]]`），不是魔法变量（`{{...}}`）；
2. 值是**字符串**，未命中/未设置时是**空串**；
3. 典型用法：`If [[__trigger_label]] equals "每日巡检"`。

⚠️ **只改 schema 文本与 `resolveModuleInputDefinitions` 的注入，不改任何渲染、不做别名**。

---

## 5. 实现落点

| # | 文件 | 改动 | 性质 |
|---|---|---|---|
| 1 | `core/workflow/model/TriggerLabel.kt`（**新增**） | 常量 `KEY = "__trigger_label"` + `VARIABLE_NAME` + 纯函数 `labelOf(step: ActionStep): String`（读参数、trim、非 String 视为空）/ `withLabel(step, label): ActionStep`（空标签 ⇒ **删键**，不写空串） | 我方新增 |
| 2 | `core/execution/WorkflowExecutor.kt` | `execute()` 构造 `initialContext` 时，在 `namedVariables` 上追加 `["__trigger_label"] = VString(labelOf(命中触发器))`。反查用既有的 `workflow.getTrigger(triggerStepId)` | **手动合并**（`namedVariables` 初始化处，约 5 行） |
| 3 | `ui/workflow_editor/TriggerLabelSheet.kt`（**新增**） | 标签输入 bottom sheet | 我方新增 |
| 4 | `ui/workflow_editor/ActionStepAdapter.kt` | `TriggerGroupViewHolder`：加标签按钮回调 + 回显行 | **手动合并** |
| 5 | `res/layout/item_action_step.xml` | `layout_step_actions` 里加 🏷 按钮（`visibility="gone"`，仅触发器卡片用） | **手动合并**（追加） |
| 6 | `ui/workflow_editor/WorkflowEditorActivity.kt` | ① 接线标签 sheet；② ⚠️ **修 §2.4① 的整表替换**（见下） | **手动合并**（关键） |
| 7 | `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | `buildNamedVariables` 追加「触发器标签」分组 | **手动合并** |
| 8 | `ui/chat/ChatAgentModuleExecutor.kt` | `resolveModuleInputDefinitions` 对 `vflow.trigger.*` 注入该 `InputDefinition` | **手动合并**（关键） |
| 9 | `ui/chat/ChatAgentToolRegistry.kt` | 三处 schema / prompt 文案 | **手动合并** |
| 10 | 三语 `strings*.xml` | 卡片按钮、回显前缀、sheet 文案、选择器分组名与条目名 | **手动合并**（追加） |

### 5.1 §2.4① 的修法

`WorkflowEditorActivity.kt:990` 的 else 分支必须从「整表替换」改为「**以旧参数为基、合并新参数**」：

```kotlin
} else {
    // ⚠️ 必须合并而不是整表替换：newStepData.parameters 只含模块声明的输入，
    //    整表替换会静默丢掉 __trigger_label（保留参数）。
    val merged = triggerSteps[position].parameters.toMutableMap()
    merged.putAll(newStepData.parameters)
    triggerSteps[position] = triggerSteps[position].copy(parameters = merged)
}
```

> 这与 `focusedInputId != null` 分支（`:986-988`）同语义 —— 两个分支本就该一致，现状是**既有不对称**。
> ⚠️ 存量工作流里的 `__error_policy` / `__retry_count` **同样在被这个 bug 吃掉**（只要有触发器被「点卡片」方式编辑过）。本改动顺带修复它。

### 5.2 §2.4② 的修法

`resolveModuleInputDefinitions`（`ChatAgentModuleExecutor.kt:2951-2961`）末尾追加：

```kotlin
val base = (staticInputs + dynamicInputs).distinctBy { it.id }
// 触发器可带一个保留参数：标签。它不由模块声明，故在此统一补齐——
// 不补的话 buildParameters / applyParameterPatch 会把它判成 unknown 并拒绝整个调用。
return if (module.id.startsWith("vflow.trigger.")) base + triggerLabelInputDefinition() else base
```

`triggerLabelInputDefinition()`：`ParameterType.STRING`、`isHidden = false`（让 `query_module_schema` 能展示给 AI）、`defaultValue = null`、`acceptsMagicVariable = false`。

⚠️ **不要**改 `module.getInputs()` 或给 28 个触发器模块各加一行 —— 那会同时把标签塞进编辑器的通用表单区（`ActionEditorUiModel.kt:52-62`），与 §3.7「不放参数 sheet」冲突。

---

## 6. 静默失效点清单

| # | 失效形态 | 后果 | 防线 |
|---|---|---|---|
| 1 | 变量名拼写不一致（`[[trigger_label]]` vs `[[__trigger_label]]`） | `VariableResolver` 解析不到 ⇒ 返回 `VObjectFactory.from("{...}")` 字面量（`VariableResolver.kt:133-135`）⇒ If 比较**恒 false**，无任何报错 | §1 单一名字 + 选择器直接插入 + 测试锁引用字符串 |
| 2 | 编辑器保存触发器时标签被吃掉（§2.4①） | 用户设了标签，改一次触发条件就没了 | §5.1 修复 + 源码扫描测试 |
| 3 | AI 写标签被判 unknown（§2.4②） | `save_workflow` 丢参数、`update_workflow` **整份补丁不落地** | §5.2 注入 + 测试锁「触发器模块的定义表含此 key」 |
| 4 | 注入时漏了某个触发器模块前缀判断 | 部分触发器打不了标签 | 测试遍历 `ModuleRegistry` 全部 `vflow.trigger.*` 模块 |
| 5 | `WorkflowManager.saveWorkflow` 的 `copy(...)` 白名单 | 标签在 `parameters` 里，`ActionStep` 整体被 `copy`，**不受影响**（与 `logLevel` 那类 Workflow 级字段不同） | 测试锁「保存后标签仍在」 |
| 6 | 空标签写成 `""` 而非删键 | 卡片回显成空行；`get_workflow` 输出 `__trigger_label: `；与「没标签」不可区分 | `withLabel` 对空串走删键 + 测试 |
| 7 | 多触发器同名标签 | 不是缺陷，是用户意图 | 文档说明 |
| 8 | 子工作流继承父的 `trigger_label`（`WorkflowExecutor.kt:470` 的 `parentContext.namedVariables + injectedVariables`） | 子工作流里读到的是**父**的标签 | 文档说明为**预期语义**（子流程由谁引发） |

---

## 7. 测试计划

**纯函数层**（`TriggerLabelTest`，纯 JVM）：
- `labelOf`：无键 / 空串 / 带空格（trim）/ 非 String 值 ⇒ 都返回空串
- `withLabel`：置值 / 空串 ⇒ **删键** / 覆盖已有

**端到端语义**（在 `WorkflowExecutor` 起不来的前提下，用纯函数 + 源码扫描组合）：
- 注入的变量名与 `TriggerLabel.VARIABLE_NAME` **是同一个常量**（不出现第二份字面量）
- 选择器插入的 `variableReference` 能被 `VariableResolver` 解析成该命名变量（**喂真实字符串给解析器**，不构造 `mapOf`）

**源码扫描型接线锚定**（形态照 `CoreDexFingerprintTest` / `AgentErrorDialogWiringTest`）：
- `WorkflowEditorActivity` 的 else 分支**必须是合并而非整表替换**（⚠️ 已实际踩过形态：这条反证必须做，把代码改回整表替换要变红）
- `resolveModuleInputDefinitions` 必须对触发器注入（防「函数写了但没被调用」）
- 三个 AI 工具的 schema 文本里必须出现 `__trigger_label` 与 `[[__trigger_label]]`

**声明体检**：遍历 `ModuleRegistry` 全部 `vflow.trigger.*` 模块，断言注入后的定义表都含该 key。

> ⚠️ **反向断言**（本仓库的既有纪律）：不得存在 `"trigger_label"`（单下划线）字面量 —— 防「改了个名字看着更顺眼」。

---

## 8. 工作量评估

| 部分 | 量 | 风险 |
|---|---|---|
| 存储 + 执行期注入 | 小（1 新文件 + 5 行） | 低 |
| 编辑器交互（按钮 + sheet + 回显） | 中（1 新文件 + 3 处改 + 布局 + 三语文案） | 中（Compose/XML 混用的既有风格） |
| **§5.1 的整表替换修复** | 极小（3 行） | **高** —— 是既有 bug，不修则无法真正可用 |
| AI 侧（注入 + 3 处文案） | 小-中 | 中（`resolveModuleInputDefinitions` 是共用函数，注入要限定在触发器前缀内） |
| 测试 | 中（约 15-25 例） | 低 |

**总评：中等偏小的独立需求**，但它**必须带上 §5.1 与 §5.2 两处修复**才成立 —— 这两处都是「改错了不报错、只静默变差」的类型，且 §5.1 是能被真机直接观察到的（设备上设个标签、改一下触发条件、标签消失）。

---

## 9. 未决项

1. **标签长度上限**：未设。理论上一段超长文本会成为 `parameters` 里的负担，但 `parameters` 本来就没有长度约束（模块参数可能更长）。暂不限制。
2. **标签能否引用变量**（如 `[[__trigger_label]]` 里放 `{{vars.x}}`）？**否** —— 标签就是静态字符串，注入时直接 `VString`。若将来要支持，是独立需求。
3. **是否提供「按标签批量管理触发器」的 UI**（如列出所有同类标签）：不做。
4. **AI 是否需要「查询工作流内所有标签」的专门工具**：不需要，`get_workflow` 已经原样列出触发器的全部参数。

---

## 10. 引用前须知

- 本文行号基于 2026-10-05 的 `dev` 分支实测，上游合并后会漂移，**引用前以代码为准**。
- §2 为**已核查事实**（均有 file:line）；§3 之后为**设计决策**。
- 本设计**未实现**。实现后须在本文件补「实现状态」段，并在 `FORK.md` 登记代码分歧。
