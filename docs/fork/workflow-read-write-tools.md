# 工作流读写工具（get_workflow / update_workflow）设计

> **版本**：v2.1 · P0 实现完成（2026-09-22）
> **分支**：`feature/workflow-read-write-tools`（基于 `dev`）
> **日期**：2026-09-22
> **目录归属**：fork 独有 → 冲突归**我方**
> **用途**：让 Chat Agent 从「只能新建工作流」扩展到「能查看并修改已有工作流」。
> **上游同步影响**：改动集中在 `ChatAgentToolRegistry` / `ChatAgentModuleExecutor` 两个已认长期分叉的文件，冲突面与本表既有条目同级；另新增两个纯函数文件。见 §10。
>
> **实现状态**：P0 已完成并通过验证（952 例单测仅 1 例既有失败；release 构建 + R8 混淆通过；签名 `CN=vFlow Fork` 正确）。
> P1（函数签名引用方检查、`step_range` 分段）与 P2（结构探测、逆补丁、审批 diff）未做。

---

## 1. 问题

Chat Agent 目前**只有保存工作流的能力，没有修改已存在工作流的能力**。

| 能力 | 现状 | 证据 |
|---|---|---|
| 列出工作流 | ✅ `list_workflows` | 只给 id / 名字 / 文件夹 / 函数签名，**看不到步骤**（`ChatAgentModuleExecutor.kt:1632-1655`） |
| 查看工作流详情 | ❌ 无 | 无任何工具能展开 `steps` / `triggers` |
| 新建工作流 | ✅ `save_workflow` | `ChatAgentModuleExecutor.prepareSaveWorkflow`（`:645`） |
| 修改工作流 | ❌ 无 | `save_workflow` 的 id 恒为 `chat_saved_${UUID}`（`:698`），永远造新条目 |

后果：用户说「把那个工作流的延时改成 3 秒」，模型只能新建一个几乎相同的工作流，旧工作流变成垃圾。用户手工删。多轮下来工作流列表被副本淹没。

**一个容易误判的事实**：数据层**已经支持覆盖保存**。`WorkflowManager.saveWorkflow()` 按 `workflow.id` 匹配，找到就替换、找不到才新增：

```kotlin
// WorkflowManager.kt:106-110
if (index != -1) {
    workflows[index] = workflowToSave
} else {
    workflows.add(workflowToSave)
}
```

所以缺的**不是存储能力，而是两个工具**：把完整详情读出来，以及用已有 id 写回去。这决定了本设计的实现量比看起来小。

---

## 2. 核心决策：操作原语补丁

`update_workflow` 的入参形态有三条路，本设计选**操作原语补丁**。

### 2.1 三个候选

| 方案 | 入参 | 实现量 | token 成本 | 审批可读性 |
|---|---|---|---|---|
| **A. 操作原语补丁** ✅ | `{workflow_id, metadata, steps: {update, insert, delete, move}}` | 最重 | 最省 | 可渲染成 diff |
| B. 局部覆盖 + 整表替换 | `{workflow_id, name?, steps?: [...]}` | 中 | 高 | 不可读 |
| C. 全量替换（同 save） | `{workflow_id, workflow: {...}}` | 最省 | 高 | 不可读 |

### 2.2 为什么选 A

**token 成本是量级差异。** 一个 20 步的工作流序列化约 4–6k 字符（≈1500 token）。改一个 `duration` 参数：

- 方案 A：输入 `{step_id:"delay_1", parameters:{duration:3000}}` + 输出确认 ≈ **30 token**
- 方案 B/C：把 20 步重发一遍 ≈ 输入+输出各 1500 token

**约 50 倍差距**，且随工作流长度线性放大。AI 对话的成本里，这类「读全文→改一处→写全文」是最浪费的一种。

**A 天然规避了「重写未提及内容」这一类坑。** 因为 A 从不重写未提及的步骤：

- 未 update 的步骤保持原对象，**id 不变**，`{{stepId.outputId}}` 引用自然不断
- 未传的元数据字段不在原语里，**不可能被重置**
- 未提及的步骤不会因为「模型列清单时漏了一条」而被删除

**但要诚实说明边界：A 并没有规避 §3 里最严重的两个坑。** §3.1（jump 序号错位）与 §3.3（块结构破损）在方案 A、B、C 下**同样存在，都要新写代码处理**——它们源于「步骤位置变了」这一事实，与入参形态无关。A 的优势是 token 成本、可审性和「不误删未提及内容」，**不是**自动免疫引用完整性问题。

**A 的「不动就不动」语义还能防住一个 B/C 致命的缺陷**：整表提交时，「模型列清单时漏了一条」会被解释成「删除这一条」。补丁式没有这个问题——缺席即不变，删除必须显式。

**审批 must-have 能做得可读。** 改已有资产比新建更需要可审，而当前审批 UI 渲染的是 `prettyFormatToolArguments(toolCall.argumentsJson)`（`ChatScreen.kt:1714`）——方案 B/C 下用户看到一大坨 JSON。方案 A 的入参结构天然适合渲染成「第 3 步 `duration` 2000 → 3000」。

### 2.3 代价与取舍

实现最重，需要设计操作原语、按 id 定位、定义位置语义。**这是本设计的主要工作量**，也是选它必须付的账。

**A 不擅长的事**：整工作流重写（用户说「照着这个思路重做一版」）。这类需求应引导走 `save_workflow` 造新工作流，而不是硬塞给 `update_workflow`。工具 description 里要写清这个边界。

---

## 3. 引用完整性：必须处理的静默失效点

以下每一条的失败模式都是**不报错、只静默变差**——本项目测试惯例里最忌讳的一类。§3.1–§3.4 是必须主动修的，§3.5 是设计成结构性规避的。

### 3.1 `vflow.logic.jump` 序号错位

#### 3.1.1 它是什么

`JumpModule`（`core/workflow/module/logic/JumpModule.kt:22`）是「跳转步骤」模块，工作流执行到它时**无条件跳回另一个步骤**：

```kotlin
// JumpModule.kt:123
return ExecutionResult.Signal(ExecutionSignal.Jump(targetIndex))
// WorkflowExecutor.kt:729-731
is ExecutionSignal.Jump -> { pc = signal.pc; continue }
```

它只有一个参数 `target_step_index`（`:34-45`），默认 `1`。**这不是 step id，是步骤在列表里的显示序号（1-based）**：

```kotlin
// JumpModule.kt:112-113
val displayStepNumber = targetIndexDouble.toInt()
val targetIndex = displayStepNumber - 1
```

口径已核对三处，一致：

| 位置 | 口径 |
|---|---|
| 编辑器列表 | 触发器显示 `#0`，第一个动作步骤 `#1`（`WorkflowEditorMagicVariableCatalogBuilder.kt:313-318`） |
| 执行器 | `context.allSteps = workflow.steps`（`WorkflowExecutor.kt:211, 363`）—— **不含触发器** |
| 所以 | `target_step_index = N` → 跳「触发之后」的第 N 个动作步骤 |

**用途**：配合 `If` 做**循环 / 重试**（元素没出现就跳回去重试）。

#### 3.1.2 为什么会错位

参数是位置序号而不是 id，所以增删步骤后，那个数字**字面不变、含义已变**：

```
原： #1 延时    #2 提示    #3 跳转到 #1      ← 意思是"跳回延时"
AI 在 #1 前插一步：
新： #1 新步骤  #2 延时    #3 提示    #4 跳转到 #1  ← 现在指向"那条新步骤"
```

不报错，跑起来才跳错地方。现有 `WorkflowJumpReferenceUpdater.remapAfterReorder()`（`:13`）处理不了，因为它**开头就拒绝数量变化**：

```kotlin
// WorkflowJumpReferenceUpdater.kt:17-19
if (originalSteps.size != reorderedSteps.size || originalSteps.isEmpty()) {
    return reorderedSteps    // ← 增删步骤时直接不处理
}
```

它只为编辑器的**纯拖拽重排**设计（唯一调用点 `WorkflowEditorActivity.kt:1428`，那里数量恒等）。

#### 3.1.3 修法

新增 `remapAfterStructuralChange(original, new)`，按 **step id** 而非位置建立映射：

1. 对每个 jump 步骤，读出原列表里的 `target_step_index`（1-based）→ 反查原列表该位置的 step id
2. 用该 step id 在新列表里查新序号
3. 目标步骤被删 → 报错（见下），不静默保留旧序号

**保留 `remapAfterReorder` 不动**（编辑器调用点依赖它的数量相等前置条件），新增函数独立测试。顺带把它从「只能纯重排」升级为「纯重排也能走」，将来编辑器可切过来。

> **删了跳转目标怎么办**：这是用户可见的破坏，不能静默。定为**报错拒绝**——提示模型「步骤 X 被 `vflow.logic.jump` 引用为跳转目标，请先处理该 jump 步骤或换个删法」。比静默让触发器跳飞更安全。

#### 3.1.4 变量引用的 jump：不重映射，但必须显式标注

`target_step_index` **不只有字面数字一种形态**。它声明了变量支持：

```kotlin
// JumpModule.kt:41-42
acceptsMagicVariable = true,
acceptsNamedVariable = true,
```

且 `validate()` 对变量引用直接放行：

```kotlin
// JumpModule.kt:63-65
if (targetIndex is String && (targetIndex.isMagicVariable() || targetIndex.isNamedVariable())) {
    return ValidationResult(true)      // 执行时再解析
}
```

即该参数可以是 `{{vars.target}}` 这类**运行时才求值**的写法。

**静态重映射对它在原理上做不到**——编不出新序号，因为原值要跑起来才知道。现有 `remapAfterReorder` 的策略是「非数字原样返回」，测试锁住了这个行为（`WorkflowJumpReferenceUpdaterTest.remapAfterReorder_ignoresNonNumericJumpTarget`）。

| 形态 | 处理 |
|---|---|
| 字面数字 | 正常重映射到新序号 |
| 变量引用 | **原样不动**（不尝试、不猜测） |

**但「不动」必须让模型知道**——否则它会以为改好了。两处显式标注：

1. **`get_workflow` 输出**：变量型 jump 标注为「运行时变量目标，静态不可重映射」，与字面数字的 `(→ delay_1)` 标注区分开
2. **`update_workflow` 警告**：若本次改动增删或移动了步骤，**且**工作流里存在变量型 jump，在成功回执里附一条警告——「存在 N 个运行时变量目标的 jump 步骤，序号未自动调整；若本次改动影响了它们的语义，请人工确认」

> **为什么不用「报错拒绝」**：会拦住本来无害的修改（只改提示文字、没动顺序）。变量型 jump 是否真的错位，取决于运行时那个变量算出几，静态判断不出来。标注 + 警告比误伤更合适。

### 3.2 步骤 id 锚定

`parseWorkflowStepSpecs` 在 id 缺失时兜底成位置相关 id：

```kotlin
// ChatAgentModuleExecutor.kt:909-912
val stepId = step["id"]?.jsonPrimitive?.contentOrNull
    ?.trim()?.takeIf { it.isNotBlank() }
    ?: "step_${index + 1}"        // ← 位置相关，重排即变
```

`update_workflow` 里这个兜底必须**禁掉**：

- `update` / `delete` / `move` 三类原语的 `step_id` **必填**，且必须在原工作流里存在 → 不存在就**报错**，不兜底
- `insert` 的 id 必填，且必须**不与现有 id 冲突**（冲突报错）
- 错误信息要附上该工作流的**真实 step id 列表**，让模型能自愈

理由：按 id 寻址是方案 A 的地基。兜底成 `step_N` 会让 `{{stepId.outputId}}` 静默断裂。

**补充事实（已核实）**：`WorkflowNormalizer` 的 `.distinctBy(ActionStep::id)` **只作用于触发器列表**（`:35`），`steps` 完全不去重（`:44`）。所以重复 id 检测对 steps 是必需的。

### 3.3 块结构破损（**P2 · 只探测不拦截**）

> **⚠️ 分期决策（2026-09-22）**：本节描述的校验器与拦截行为**降级到 P2，且只做警告、不拒绝写入**。
> 理由：在「信任模型能力」的前提下，硬拦截的收益低于它在**存量破损工作流**上造成的僵局（连改个工作流名字都会被拒）。
> 本节内容保留为**问题诊断与实现依据**——P2 落地时按此实现，但出口是警告而非拒绝。

这是本设计发现的**最严重缺口**，且是**既有的、对称的**——`save_workflow` 现在同样不校验。

#### 3.3.1 机制

`BlockType { NONE, BLOCK_START, BLOCK_MIDDLE, BLOCK_END }`（`core/module/definitions.kt:667-672`），模块通过 `blockBehavior`（`ActionModule.kt:37`）声明自己在块里的角色，`pairingId` 关联同块成员。声明了块行为的模块共 9 组（`if` / `loop` / `while` / `foreach` / `do_while` / `choose_from_menu` / `ui_activity` / `ui_float_window` / `ui_on_event`）。

#### 3.3.2 失败模式实测（逐场景核实）

**执行器没有任何 preflight 结构校验**（`WorkflowExecutor.execute:133` → `executeWorkflowInternal:446` 直接进主循环；`WorkflowManager.saveWorkflow:74-114` 也不拒绝；`WorkflowNormalizer.normalize:15-48` 不修复结构）。

| 破损形态 | 后果 | 性质 |
|---|---|---|
| If 缺 `EndIf` | 条件为假时 `findNextBlockPosition` 返回 -1 → **不跳转、继续往下执行**（`IfModule.kt:271-276`） | **静默跑错** |
| Loop 缺 `EndLoop` | 循环体跑一遍、`loopStack` 状态永不 pop | **静默跑错** |
| `Loop.end` 坐标查找失败 | `DebugLogger.w` + `pc++`（`WorkflowExecutor.kt:742-744`） | **静默跳过** |
| `Break` 找不到结束块 | `DebugLogger.w` + `pc++`（`WorkflowExecutor.kt:756-761`） | **静默跳过** |
| `Continue` 找不到循环起点 | `DebugLogger.w` + `pc++`（`WorkflowExecutor.kt:775-777`） | **静默跳过** |
| 孤立 `EndLoop` | 栈空 → `Success()`（`LoopModule.kt:168`） | **静默** |
| 循环外 `BreakLoop` | **无**——模块自身守卫 `Failure("'跳出循环'模块必须放置在循环块内")`（`BreakLoopModule.kt:48-52`） | 显式报错 ✓ |
| 循环外 `ContinueLoop` | 模块**无守卫**（`ContinueLoopModule.kt:39-46` 只发信号），静默发生在执行器（见上行 `Continue`） | **静默**（与 `Break` 不对称） |
| `While` / `ForEach` / `DoWhile` 坐标查找失败 | `Failure("执行错误", "找不到配对的结束循环块")`（`WhileModule.kt:222`、`ForEachModule.kt:141`、`DoWhileModule.kt:217-219`） | 显式报错 ✓ |
| 栈类型不匹配（`EndWhile` 配 `loop`） | `ExecutionResult.Failure`（`WhileModule.kt:273-276` 等） | 显式报错 ✓ |
| 孤立 `EndForEach` / `EndWhile` / `EndDoWhile` | `Failure`（`ForEachModule.kt:180-181` 等） | 显式报错 ✓ |
| 菜单分支找不到 | `Failure("菜单结构错误")`（`ChooseFromMenuModule.kt:218, 229-231, 262-264`） | 显式报错 ✓ |
| 坐标 -1 被当跳转目标 | `pc = -1` → `IndexOutOfBoundsException` → 通知层「执行异常」（`UiListenerModules.kt:74-75, 373-374`） | 显式但不透明 |

> **修正记录（2026-09-22 评审）**：本表初版把「Loop/While/ForEach 坐标查找失败」统一归为静默跳过，**归因错误**——静默的三处实际是 `Loop.end` / `Break` / `Continue` 路径（都在 `WorkflowExecutor` 的信号处理分支里）；`While` / `ForEach` / `DoWhile` 的坐标失败是**模块内的显式 `Failure`**。已按逐条核实结果重写。「静默占多数」的总判定不变。

**结论**：破损形态里**静默占多数**，且失败信息（若有）无法指出是结构问题。

**AI 侧现状**：唯一的结构约束是提示词里的一句话（`ChatAgentToolRegistry.kt:1098`：`Loop.start/Loop.end and If.start/If.middle/If.end must be paired.`）——解析时**完全不校验配对**（`ChatAgentModuleExecutor.kt:919, 996` 只做 `indentationLevel.coerceIn`）。

#### 3.3.3 修法：提取可复用的结构校验器

编辑器里**已经有现成的实现**——`WorkflowEditorActivity.isBlockStructureValid`（`:1557-1580`）。它做两件事：括号栈配对校验，**外加上 define_function 必须在首位**（决策 16）：

```kotlin
if (!blockStack.isEmpty()) return false
// 决策 16：define_function 必须在首位
val defineIndex = list.indexOfFirst { it.moduleId == DEFINE_FUNCTION_MODULE_ID }
if (defineIndex != -1 && defineIndex != 0) return false
```

但它被 private 锁在 Activity 里，且**唯一调用点是拖拽结束**（`:1427`）——新增、粘贴、AI 生成、模板插入、JSON 导入全都不过它。

**做法（P2 · 只探测不拦截）**：新建 `core/workflow/WorkflowStructureValidator.kt`（符合 fork「能加新文件就不改上游文件」的原则），把该函数提取为独立校验器，编辑器改一行调用。然后：

- `update_workflow`：**补丁含 `insert`/`delete`/`move` 时**对最终步骤列表**探测**；只改参数（`update` 且不带 `is_disabled`）时结构必然不变，可跳过。⚠️ **`is_disabled` 不能靠这里兜底**——它由 §3.3.4 单独处理
- `save_workflow`：同样只探测

**出口是警告，不是拒绝**：结构破损时在回执里说明「第 N 步 `If` 缺少 `EndIf`」，但**照样写入**。这样既让模型/用户知道问题，又不制造「存量破损工作流无法编辑」的僵局。

> **为什么只探测**：硬拦截的收益（防止静默跑错）在信任模型能力的前提下变低，而它的代价是具体的——一条**已经**破损的存量工作流，ai 连改个名字都会被拒，甚至无法用 `insert` 去补缺失的 `EndIf`（补的动作本身也要过校验）。
>
> **为什么 `save_workflow` 与 `update_workflow` 口径一致**：只补一边会不一致——同一个 AI 通过 `save_workflow` 能造出破损工作流、通过 `update_workflow` 却被警告，没道理。
>
> **副产物**：`define_function` 首位检查与配对校验本来就在同一个函数里（`WorkflowEditorActivity.kt:1576-1577`），提取时一并带出，因此 §3.6 记录的「仅文案警告」在实现后可升级为**探测式警告**。

### 3.3.4 `isDisabled` 打在块边界上：结构校验抓不到的静默破口

这是 §3.3.3 那套静态校验**原理上覆盖不了**的场景，必须在原语层拦住。

#### 机制

执行器对禁用步骤的跳过逻辑**按块角色分派**（`WorkflowExecutor.kt:471-487`）：

```kotlin
if (step.isDisabled) {
    val behavior = module.blockBehavior
    val nextPc = when (behavior.type) {
        BlockType.BLOCK_START -> {
            val endPos = BlockNavigator.findEndBlockPosition(workflow.steps, pc, behavior.pairingId)
            if (endPos != -1) endPos + 1 else pc + 1
        }
        BlockType.BLOCK_MIDDLE -> { /* 同上，跳到配对 END 之后 */ }
        else -> pc + 1        // ← BLOCK_END 落到这里
    }
    pc = nextPc
    continue
}
```

- `BLOCK_START` / `BLOCK_MIDDLE` 被禁用 → 跳过**整个块**（到配对 END 之后）。push 与 pop 都跳过，**平衡**。
- **`BLOCK_END` 被禁用 → 只跳过那一步模块逻辑**。而 `EndLoop` / `EndWhile` / `EndForEach` / `EndDoWhile` 全都是 `BLOCK_END`。

#### 具体后果

禁用 `EndLoop`：

1. 循环体照常执行，`Loop.start` 的 `loopStack.push(...)` 正常发生（`LoopModule.kt:132-133`）
2. `EndLoop` 的模块逻辑被跳过 → **END 信号永不发出** → `loopStack.pop()`（`WorkflowExecutor.kt:747`）永不执行
3. 结果：**循环只跑一遍**（本该跑 N 遍），且 `loopStack` 状态泄漏

**而静态结构校验完全看不出来**——步骤列表的 moduleId 序列是完整的，配对检查必然通过。这是典型的「不报错、只静默变差」。

#### 修法：原语层拒绝

**`steps.update` 的 `is_disabled` 只允许作用于 `blockBehavior.type == BlockType.NONE` 的步骤；打在块成员（START / MIDDLE / END）上报错拒绝。**

理由：块成员的「禁用」语义在现有执行器里是**不自洽**的（START 跳整块、END 只跳一步），本设计不去改执行器（那是上游核心，冲突面大），而是**不暴露这个能力**。错误信息要说明「要停用整个块，请显式 delete 块的全部成员」。

> **【待确认】** 另一条更彻底的修法是给执行器的 `BLOCK_END` 分支补配对处理（让禁用 END 也跳过整个块）。**本文档不做此建议**——它改上游核心文件，与本 fork「控制 diff 面积」的原则相悖，且会改变既有行为。列为独立议题。

`insert` 不受此限（新增块成员是合法的，只要结构校验通过）。

### 3.4 函数签名改动的引用完整性

#### 3.4.1 机制

`Workflow.functionSignature` 是**从 define_function 步骤聚合出的产物**（`WorkflowManager.aggregateFunctionSignature:269-287`）——扫描 `steps.firstOrNull { moduleId == "vflow.logic.define_function" }`，把 `parameters["functionParams"]`（JSON 字符串）解析成 `FunctionSignature`（`:270-286`）。所以**改 define_function 的参数 = 改整个函数工作流的签名**。

引用方通过 `CallFunctionModule.getDynamicInputs` 依赖它——参数输入框**完全由被调工作流当前签名派生**（`CallFunctionModule.kt:46-67`，`lookupSignature` 每次实时查 `:91-96`）。

#### 3.4.2 实测失败模式

| 签名改动 | 引用方工作流的后果 | 性质 |
|---|---|---|
| 删除 / 改名参数 | 旧实参变**死键**，永不读取（`CallFunctionModule.kt:148` 只遍历**当前**签名 params） | **静默失效** |
| 新增必填参数 | 运行时 `Failure("缺少参数")`（`:150-158`） | 显式失败 ✓ |
| 静态校验 | **完全没有**（`CallFunctionModule` 未重写 `validate`，沿用恒真的 `BaseModule.validate:135-137`） | — |

**这是本设计能造成的最大破坏**：`update_workflow` 一旦能改 define_function 的参数，就**能静默废掉所有引用方工作流**。

#### 3.4.3 修法：静态查引用方 + 警告

改签名时**扫描所有工作流**，找出 `moduleId == vflow.logic.call_function` 且 `workflow_id` 指向本工作流的步骤，逐参数比对：

- 引用了已删除/已改名的参数 → 在回执里**警告**并指名是哪条工作流
- 引用方本来就有必填参数没传 → 同样警告

**不动手改引用方**（超出本工具职责），但必须让用户知道——否则用户不会发现某条工作流已经不工作了。

**纯本地扫描**（遍历 `getAllWorkflows()`），代价小。

### 3.5 触发器引用：设计成结构性规避

#### 3.5.1 关键事实：触发器输出**按 trigger.id 键控**

```kotlin
// WorkflowExecutor.kt:411-417（写入在 :415）
workflow.triggers
    .filter { sameTypeTrigger -> sameTypeTrigger.moduleId == triggerStep.moduleId && ... }
    .forEach { sameTypeTrigger ->
        executionContext.stepOutputs[sameTypeTrigger.id] = triggerOutputs   // ← 按 id 写入
    }
```

而步骤引用它写 `{{triggerId.outputId}}`（`VariableResolver.kt:174-181` 只查 `stepOutputs[stepId]`）。触发器**本来就是设计来被引用的**——`NotificationTriggerModule` 输出 `notification_object / package_name / title / content`，`AppSwitchTriggerModule` 输出 `package_name / app_name` 等。

**所以触发器 id 一旦变化，所有 `{{旧triggerId.title}}` 全部静默失效**。用户的通知触发器突然读不到标题，无任何报错。

> **附带澄清**（曾误判，已核实）：`ExecutionContext.allSteps` 只传 `workflow.steps`（`WorkflowExecutor.kt:211, 363`）**不是缺陷**。变量解析根本不读 `allSteps`（`VariableResolver.kt:172-182` 只查 `stepOutputs`），而触发器输出在 `seedTriggerOutputs`（`:390-423`）里就按 id 播进去了。`allSteps` 只用于块导航与「取当前步骤」。

#### 3.5.2 决策：triggers 用与 steps 对称的原语

**不采用 `triggers.replace` 整表提交。** 整表提交有「遗漏 = 删除」的固有缺陷：

| 模型的行为 | 整表提交的后果 |
|---|---|
| id 拼错一个字符 | 旧 id「未出现」→ **删除真触发器**；新 id「不存在」→ **新增假触发器** |
| 列清单时漏了一条 | 该条「未出现」→ **静默删除** |

这正是 §2.2 里方案 A 优于 B/C 的同一个理由。所以 triggers 也用原语（`update` / `insert` / `delete`，**无 `move`**——触发器顺序无语义）：

- 不动就不动，删除必须显式 → 杜绝遗漏即删除
- 要改必须给存在的 id → 杜绝拼错 id 变成删除+新增
- 与 steps 心智一致，模型不用学两套语义
- 触发器通常只有 1–3 个，原语式的额外 token 成本可忽略

#### 3.5.3 触发器联动的已知行为与限制

`WorkflowManager.saveWorkflow` 落盘后调 `TriggerServiceProxy.notifyWorkflowChanged`（`:112-113`），机制是「先按旧 refs 全摘、再按新工作流全挂」（`TriggerService.kt:255-296`），所以增删改都能正确反映，**禁用会注销、启用会重挂**（`:292-295` / `:279-281`）。

需记入文档的两条既有限制（**非本功能引入**，但本功能会提高触发频率）：

1. **语音触发器可能静默丢弃变更通知**：`VoiceTriggerService` 在 App 不在前台时直接跳过（`VoiceTriggerService.kt:103-107`，要求进程 importance ≤ VISIBLE）
2. **调度型（Time/Interval）改 step id 会解绑失败**（`docs/fork/surveys/trigger-system-overview.md:171-173`）

### 3.6 只读字段：结构性规避

`Workflow` 有 AI 看不见的字段（完整清单见 §4.2.3）：`cardIconRes`、`cardThemeColor`、`shortcutName`、`shortcutIconRes`、`order`、`isFavorite`、`author`、`homepage`、`version`、`vFlowLevel`、`wasEnabledBeforePermissionsLost`、`modifiedAt`。

**方案 A 下这些字段不在任何原语里，本来就不会被改**——坑被结构性绕过。**但必须让模型知道**，否则它会试图通过 `metadata` 改它们并发起无效重试。

**处理**：`get_workflow` 输出里显式列出只读字段（见 §4.2.2）。

---

## 4. 工具设计

### 4.1 常量与注册

沿用既有命名约定（`ChatAgentToolRegistry.kt:48` 一带）：

```kotlin
internal const val CHAT_GET_WORKFLOW_TOOL_NAME = "vflow_agent_get_workflow"
internal const val CHAT_GET_WORKFLOW_MODULE_ID = "vflow.agent.get_workflow"

internal const val CHAT_UPDATE_WORKFLOW_TOOL_NAME = "vflow_agent_update_workflow"
internal const val CHAT_UPDATE_WORKFLOW_MODULE_ID = "vflow.agent.update_workflow"
```

两者都进常驻工具表（`toolsByName`），理由同 `list_workflows`：固定工具、不随技能路由变化。

| 工具 | riskLevel | backend | 理由 |
|---|---|---|---|
| `get_workflow` | `READ_ONLY` | `ImmediateResult` | 纯本地读，无副作用。与 `list_workflows` 同级 |
| `update_workflow` | `HIGH`（占位） | 新增 `UpdateWorkflow` | 实际审批走算出来的 item 值，见 §4.3.4 |

> **⚠️ 重要澄清**：`READ_ONLY` **不等于免审批**。`ChatViewModel.kt:900` 收到 tool_calls 时**无条件先设 PENDING**，然后才判断自动审批（`shouldAutoApproveToolCalls:958-967`）；而**用户当前默认档是 `OFF`**（`ChatPresetRepository.kt:59-68`）。所以 `get_workflow` 在默认设置下照样要人工点批准——`READ_ONLY` 只让「自动批准档」能放过它。这与 `list_workflows` / `load_skill` 完全一致。

### 4.2 `get_workflow`

#### 4.2.1 入参与输出形态

**入参**：`{ "workflow_id": "wf_abc123" }`（可选 `step_range`，见 §4.2.4）

**输出用文本，步骤区嵌 JSON 片段**（与现有 5 个新工具 `list_workflows` / `get_environment` / `query_module_schema` / `load_skill` 的文本风格一致；且文本能塞进 JSON 装不下的标注）。

**关键性质：输出结构对齐 `save_workflow` 的入参**，于是**模型可以把输出原样改一改喂给 `save_workflow`**。

> ⚠️ **「对齐」不等于「等价」**：`isDisabled` 只有 `update_workflow` 能写——`save_workflow` 的 step schema 里没有 `is_disabled` 且 `additionalProperties: false`，模型照搬会被 schema 拒绝。所以 `get_workflow` 输出里的 `[disabled]` 标记要**显式注明该字段只对 `update_workflow` 有效**。

#### 4.2.2 输出内容

```
workflow: "每日签到"
id: wf_abc123
isFunction: false
folder: 自动化
description: ...
isEnabled: true
tags: #签到
maxExecutionTime: 120
reentryBehavior: block_new
modifiedAt: 1758499200000

triggers (2):
- manual_trigger  vflow.trigger.manual
- time_trigger    vflow.trigger.time    {hour: 8, minute: 0}

steps (4):
- delay_1   vflow.device.delay       {duration: 2000}
- toast_1   vflow.notification.toast {message: "{{delay_1.elapsed}}"}
- jump_1    vflow.logic.jump         {target_step_index: 1 (→ delay_1)}
- delay_2   vflow.device.delay       {duration: 500}  [disabled]

read-only fields (cannot be changed by tools):
  cardIconRes, cardThemeColor, shortcutName, shortcutIconRes,
  order, isFavorite, author, homepage, version, vFlowLevel,
  wasEnabledBeforePermissionsLost, modifiedAt
```

**每一条都是必需的**：

- **步骤必须打印 id**——`update_workflow` 按 id 寻址的前提
- **参数表必须打印**——`update` 语义是「按 key 合并」，模型得知道现有 key
- **jump 要标注它指向的步骤名**（`target_step_index: 1 (→ delay_1)`），否则模型看不出这是个引用
- **变量型 jump 标注成另一种形态**——`target_step_index: {{vars.x}} (运行时变量目标，无法静态重映射)`，与字面数字区分开
- **`isDisabled` 显示**（`[disabled]`）——这是「停用某一步而不删」的唯一手段，AI 目前完全看不到
- **只读字段显式列出**——防止模型试图通过 `metadata` 改它们

#### 4.2.3 字段可见性对照

| 字段 | `list_workflows` | `get_workflow`（本文档） |
|---|---|---|
| `id` / `name` / `folderId` / `tags` / 函数签名 | ✅ | ✅ |
| `steps` / `triggers`（含全部 ActionStep 字段） | ❌ | ✅ |
| `description` / `isEnabled` / `maxExecutionTime` / `reentryBehavior` | ❌ | ✅ |
| 只读字段（§3.6 全表） | ❌ | ✅（标注为只读） |

**`ActionStep.isDisabled` 值得单独强调**：`save_workflow` 的 step schema 里**零命中**（已核实），即 AI 全套工具都摸不到它。本设计把它纳入 `get_workflow` 输出与 `update_workflow` 的 `update` 原语。

#### 4.2.4 长工作流分段

`list_workflows` 是 `truncatable = false`、刻意不截断的（看不到要找的条目等于白调）。`get_workflow` 同理，但超长时需要分段——**步骤超过 60 时只返回 id + moduleId + 参数摘要行**，完整参数需带 `step_range` 二次查询。避免一次送 200 步的 JSON 进上下文。

### 4.3 `update_workflow`

#### 4.3.1 入参

```json
{
  "workflow_id": "wf_abc123",
  "metadata": {
    "name": "可选，只改传了的字段",
    "description": "...",
    "isEnabled": true,
    "folderId": "...",
    "tags": ["..."],
    "maxExecutionTime": 120,
    "reentryBehavior": "block_new"
  },
  "triggers": {
    "update": [ { "step_id": "time_trigger", "parameters": { "hour": 9 } } ],
    "insert": [ { "id": "new_trigger", "moduleId": "vflow.trigger.wifi", "parameters": {...} } ],
    "delete": [ "old_trigger" ]
  },
  "steps": {
    "update": [
      { "step_id": "delay_1", "parameters": { "duration": 3000 }, "is_disabled": false }
    ],
    "insert": [
      { "after_step_id": "delay_1", "id": "toast_1",
        "moduleId": "vflow.notification.toast", "parameters": {...} }
    ],
    "delete": [ "old_step_2" ],
    "move": [ { "step_id": "jump_1", "to_index": 1 } ]
  }
}
```

#### 4.3.2 语义精确定义

**必须写进工具 description，否则模型会误用。**

| 原语 | 语义 |
|---|---|
| `metadata.*` | **按 key 合并**。不传的字段保持原值。传 `null` **真正删除该键**（不是写一个 null 值）。 |
| `steps.update` | **只按 key 合并 `parameters`**，不整表替换。传 `null` = **从参数表里移除该键**（同 `metadata`）。可带 `is_disabled`（**块成员禁用须拒绝**，见 §3.3.4）。 |
| `steps.insert` | `after_step_id` **与** `at_index` **二选一必填**——都不给或都给均报错。`at_index` 越界（超出 `[0, size]`）报错 |
| `steps.delete` | 按 id；若被 jump 引用则**报错拒绝**（§3.1.3） |
| `steps.move` | `to_index` 是 **1-based 显示序号**，且**一律按「补丁执行前」的列表解释**（见下） |
| `triggers.*` | 与 steps 对称，**无 `move`**（顺序无语义） |

**三条必须写死的细则**（初版遗漏，会导致静默错位）：

1. **`null` 的落地语义**：JSON 里「键缺席」与「键值为 `null`」在 `JsonObject` 层面可区分。**缺席 = 不改；`null` = `remove(key)`**。不能在参数表里留下 `key -> null`——那与「键不存在」在执行期对 `isRequired` 校验不等价。

2. **`move.to_index` 的基准**：流水线是 `update → insert → delete → move`（§4.3.3），即 move 在**删除之后**执行。但模型从 `get_workflow` 拿到的是**当前**（补丁前）的显示序号。**若按字面执行，模型的 `#N` 与实际的 `N` 会差一个被删掉的元素数**——静默错位。

   **定义：`to_index` 一律按补丁执行前的列表解释。** 实现时在 delete 之前记录「补丁前列表」的 id 顺序，move 阶段按该基准定位目标位置。这样模型可以直接用 `get_workflow` 看到的序号，无需心算。

3. **`insert` 允许插到块内部**（如 `If.start` 与 `EndIf` 之间）——这是合法且有意义的（往 If 体里加一步），由结构校验兜底。不额外限制。

**step id 字符集**：限定 `[A-Za-z0-9_-]+`。理由：step id 会被拼进 `{{id.output}}` 引用语法，而变量解析按 `.` 分段——含 `.` 或 `{{` 的 id 会**静默破坏引用解析**。`insert` 与 `update` 新增的 id 都要过此校验。

#### 4.3.3 执行流水线与原子性

**固定顺序**，不依赖入参书写次序（否则模型排序不同会产生不同结果，无法复现）：

1. 解析补丁
2. **定位 + 键校验**所有目标 —— 按 id 定位（update/delete 的目标必须存在；insert 的 id 必须不存在**且符合字符集**），**同时对新增/修改步骤的参数键过一遍 `buildParameters` 的键校验**（未知键必须在这里报错，不能漏到第 10 步或静默写成死参数）→ **任一失败则整体拒绝**
3. 应用 `update` → 4. `insert` → 5. `delete` → 6. `move`（`to_index` 按**补丁前**基准，§4.3.2）
7. **`is_disabled` 块成员检查**（§3.3.4）
8. ~~结构校验~~ —— **P2 降级为「只警告不拒绝」**（§3.3）。P0 实现时此步**直接跳过**，不阻塞写入
9. **jump 序号重映射**（§3.1.3）
10. **`folderId` 存在性校验**（见下）
11. **函数签名引用方检查**（仅当改了 define_function，§3.4.3）
12. **逐模块 `validate`（范围受限，见下）** → 13. 权限检查 → 14. 风险等级计算
15. **落盘**：先留存原 workflow，再 `WorkflowManager(appContext).saveWorkflow(workflow)`——**id 保持原样即覆盖**

#### 原子性：三个层次

| 失败点 | 处理 |
|---|---|
| 第 2–14 步任一校验失败 | **不落盘**，把所有错误一次性回传（复用 `save_workflow` 的 `validationErrors` 模式，`ChatAgentModuleExecutor.kt:1080-1095`） |
| 第 15 步落盘抛异常 | **把原 workflow 写回**。`saveWorkflow` 是覆盖式整表写（`WorkflowManager.kt:75, 112`），落盘中途失败会留下半写状态，而本项目**没有任何版本历史或回收站**（§4.3.5）——所以实现时必须先 `getWorkflow(id)` 留原件，异常时回写 |
| 逆补丁（§4.3.5） | **落盘成功之后**才附进回执。落盘失败时附逆补丁没有意义 |

#### `validate` 的范围：只覆盖新增/修改的步骤

**不能沿用 `save_workflow` 的「对全部步骤 validate 并失败即拒」**（`ChatAgentModuleExecutor.kt:684-695`）。原因：一条**存量**工作流只要含任意一个现在 `validate` 不过的历史步骤（例如某个模块改过校验规则），就会导致它**完全无法被编辑**——这与 §4.3.6 已处理的「存量 trigger 残留在 steps」是同一类问题的另一面。

**规则**：`update_workflow` 的 `validate` 只覆盖**本次新增或修改的步骤**；未触及的存量步骤只做「不劣化」判断。

#### `folderId` 必须校验存在性

`metadata.folderId` 是可写的，但**未知 folderId 会让工作流从列表上消失**——列表渲染只区分「命中某个已存在文件夹」与「`folderId == null`（根目录）」，未知 id **既不进文件夹也不进根目录**（`WorkflowListRoute.kt:145, 165`）。

而且这是 §4.4.1「名字不作选择目标」原则的**同一个坑**：模型很容易把文件夹**名字**当 id 传（`folderId: "自动化"`）。

**处理**：`folderId` 非空时必须命中 `FolderManager.getFolder(id)`，否则报错并在错误里**附真实 folder id 列表**——与 §4.4.1 同款自愈路径。

> `save_workflow` 现在有同样的问题（`ChatAgentModuleExecutor.kt:873` 不校验），本设计**不顺手改它**——那是既有的独立缺口，记录待办。若你希望一并修，说一声。

#### 4.3.4 风险等级：按结果状态重算

复用 `riskLevelForSavedWorkflow`（`ChatAgentModuleExecutor.kt:940-952`），**对改动后的完整工作流**重算。

**论点**：风险是**结果状态**的属性，不是**动作**的属性——用户批准的是「让这个工作流变成这样」，不是「执行了一个叫 update 的操作」。所以「删掉那个 shell 步骤」不该触发 HIGH，而「插入一个 shell 步骤」该升级。固定 HIGH 会让改一个错别字也走 HIGH 审批，把审批噪声拉满后用户必然开自动批准——反而更不安全。

**诚实说明（已核实现有实现，收益比直觉窄）**：`riskLevelForSavedWorkflow` 有三个特性——

1. **有 STANDARD 地板**（`+ ChatAgentToolRiskLevel.STANDARD` 并入 max），所以**纯只读步骤也到不了 READ_ONLY**
2. **非 manual 触发器一律 HIGH**，不区分具体类型
3. 所以**带任何自动触发器的工作流恒为 HIGH**

| 改动 | 算出来的风险 | 合理性 |
|---|---|---|
| 删掉一个 shell 步骤 | 降到 STANDARD（地板挡住，降不到 LOW） | 半对 |
| 插入一个 shell 步骤 | HIGH ✓ | 对 |
| 改带定时触发器工作流的错别字 | HIGH ✗ | 不合理 |

第三条是漏判，但固定 HIGH 会让三者全是 HIGH、连信息量都没有。所以按结果重算仍严格更优。

> **已知可优化点（本批不做）**：把「非 manual 一律 HIGH」改成按触发器类型的真实风险。这是既有实现的问题，独立于本设计。

#### 4.3.5 回滚：附逆补丁（**P2**）

> **⚠️ 分期决策（2026-09-22）**：本节的逆补丁**降级到 P2**，且**不再作为可靠的回滚手段**。
> 它在纯 `update` 场景（改参数、改元数据）下准确，但块成员的删除/恢复需要「同一次调用」这个前提（见下）。
> **用户要可靠回滚，走编辑器 undo 或手动备份。**

**背景事实**（逆补丁若要做，这是它存在的理由）：

- 没有版本历史、没有回收站、没有软删除——`deleteWorkflow` 是硬删除，立即 `remove` + 覆盖落盘（`WorkflowManager.kt:134-142`）
- 编辑器 undo 是**纯内存**：`ArrayDeque` 上限 50 步，不进 `onSaveInstanceState`，Activity 一销毁就归零（`WorkflowEditorActivity.kt:98-99, 185, 199-204`）
- 唯一的持久化备份是用户**手动**点「备份工作流」写到自选 URI（`WorkflowListRoute.kt:766-779`）

**做法**：成功回执里附**逆补丁**（由「原工作流 + 补丁」静态算出），用户说「撤销」时模型再调一次 `update_workflow`。零新增存储，放回执文本里即可，不需要新会话状态（历史即状态）。

**⚠️ 硬约束：逆补丁必须在同一次 `update_workflow` 调用里全部提交。**

原因是块结构校验（§3.3.3）作用在**全部变更之后**的列表上。若一次前向补丁删掉了一整块（`If.start` + `Else` + `EndIf`），逆补丁需要三条 `insert`；**一旦被拆成多次调用**，第一次插入后的中间状态是「开块无闭合」→ 结构非法 → **被拒绝，回滚路径断裂**。

单次提交则中间态不暴露，校验只看最终结果（此时块已完整）。所以逆补丁的回执里必须带这条提示：**「若被拒，请把全部 insert 放在同一次调用」**。

**局限（必须写清）**：

| 原语 | 逆 | 是否准确 |
|---|---|---|
| `update`（改参数/元数据） | 换回原值（`null` 逆为原值） | ✅ 完全准确 |
| `insert` | `delete` 该 id | ✅ |
| `delete` **单个非块成员** | 用原步骤完整参数 + 原位置 `insert` 回去 | ✅（`to_index` 按补丁前基准，§4.3.2） |
| `delete` **块成员** | 用**原参数** `insert` 回去，位置记原值 | ✅ 但**必须与同块其它成员的 insert 同批提交**（见上） |
| `move` | `move` 回原序号 | ✅ |

**对最常见的 update 场景完全准确**；对块成员的删除/恢复，前提是「单次原子调用」。

#### 4.3.6 参数值必须归一化后再进参数表（**真机缺陷，已修**）

> **2026-09-22 真机缺陷**：`update_workflow` 首版把**未归一化的 `JsonElement`** 直接交给了
> `coerceInputValue`，造成三个同时出现的写入污染。已在 P0 内修复。

#### 缺陷表现

| # | 症状 | 实际存储 | 运行期后果 |
|---|---|---|---|
| A | 字符串多包一层引号 | `content: "ABC"` → `""ABC""` | `targetText` 永远匹配不到，权限弹窗分支失效 |
| B | 数字落成对象 | `duration: 6000` → `{a:false,b:"6000"}` | 延迟/初值/比较值全部失效 |
| C | 多行文本被二次转义 | 真换行变字面 `\n`；`/\s+/` 变 `/\\s+/` | 正则匹配「反斜杠+s」，去空格功能彻底失效 |

**B 的 `{a,b}` 是决定性证据**：那是 release 构建里 `kotlinx` 的 `JsonLiteral` 被 R8 混淆后的字段名
（`isString`/`body` → `a`/`b`）。也就是说**整个 `JsonElement` 对象本身被当参数值存进了库**。

#### 根因

不是「序列化器二次编码」，而是**漏了一步归一化**。`coerceInputValue` 期望收到归一化后的
Kotlin 值，三种声明类型拿到 `JsonElement` 时分别出错：

| 声明类型 | 直传 `JsonElement` 的结果 |
|---|---|
| `STRING` | `rawValue.toString()` → `"\"ABC\""`（缺陷 A） |
| `NUMBER` | `coerceNumber` 的 `is Number` / `is String` 都不匹配 → 元素原样落库（缺陷 B） |
| `ANY` | 元素落库后再序列化一遍 → 二次转义（缺陷 C） |

三条路径坏在同一行。

#### 为什么 `save_workflow` 没这个问题

它走 `buildParameters(stepSpec.parameters.toString())`——传的是 **JSON 字符串**，
内部 `parseArguments` 会归一化。`update_workflow` 首版直传了 `JsonObject` 子树，**跳过了那一步**。

#### 修法与防回归

1. 新增顶层函数 `normalizeParameterPatchJson(rawJson: String?)`，`update_workflow` 的两个分支
   （`update` / `insert`）都必须经它。**与 `save_workflow` 同源**（内部都走 `normalizeJsonElement`）
2. `normalizeJsonElement` 从私有方法提为**顶层 internal**——原先是 private，测试够不到，
   这是缺陷能溜出去的直接原因
3. 新增 `ChatAgentParameterNormalizationTest`（18 例）。**关键设计**：测试走
   `normalizeParameterPatchJson` 这个**真实入口**而非内部函数。

> ⚠️ **测试策略上的一个教训**：最初只测 `normalizeJsonElement`（内部函数）时，
> **反证不成立**——把调用点退回 bug 版本，测试依然全绿，因为测试压根不经过调用点。
> 这个缺陷的形态正是「函数写对了，但调用点漏了归一化」。改测入口后反证才变红。
> 教训：**测试要覆盖调用链，而不是函数**。

#### 附带修正：`insert` 分支的参数基准

同批自查发现另一个缺陷：`insert` 分支传了 `base = emptyMap()`，而 `save_workflow` 是
`defaults + accepted`。后果是**新插入的步骤会丢掉模块的默认参数**（模型没显式给的字段变成缺失，
而不是默认值）。已改为 `module.createSteps().firstOrNull()?.parameters.orEmpty()`。

与 `update` 分支的基准**正好相反**，两者都要对：

| 原语 | base | 理由 |
|---|---|---|
| `steps.update` | **步骤现有参数** | 否则未提及的参数被重置成默认值 |
| `steps.insert` | **模块默认值** | 否则未提及的参数变成缺失 |

### 4.3.7 复用现有链路

| 环节 | 复用 |
|---|---|
| 参数校验 | `buildParameters`（`ChatAgentModuleExecutor.kt:1334`）——**注意它是 `defaults + accepted`**，update 场景要改成「以**步骤现有参数**为 base，而非模块默认值」，否则未提及的参数会被重置成默认值 |
| 模块合法性 | `isRegisteredModule` → `isSavedWorkflowModuleAllowed` 两步判定，**顺序不可颠倒**（见 `:743-746` 注释） |
| 模块 `validate()` | `candidate.module.validate(candidate.step, allSteps)` |
| 结构校验 | 新建 `WorkflowStructureValidator`（§3.3.3） |
| 权限检查 | `PermissionManager.getMissingPermissions(appContext, workflow)` |
| 风险等级 | `riskLevelForSavedWorkflow`（`:940`） |
| 落盘 | `WorkflowManager(appContext).saveWorkflow(workflow)` |

**一个边界**：`WorkflowNormalizer.splitLeadingTriggerSteps`（`:63-69`）**只把 steps 列表开头的 trigger 提升为触发器**，出现在中间的会残留。所以存量工作流的 `steps` 里**可能含有 trigger 模块**（导入的、历史遗留的）。`save_workflow` 现在会拒绝这种（`:783-789`）。`update_workflow` **沿用该约束但只对新增/修改的步骤生效，存量残留原样保留**——不能因为一处历史遗留就让整个工作流不可编辑。

### 4.4 错误处理：id 不存在 / 空操作

#### 4.4.1 只认 id，但名字可用于生成错误信息

**唯一性始终由 id 保证；名字绝不用于选择写入目标**（工作流允许重名，用名字选会静默改错对象——而这是不可逆破坏）。

但错误信息里可以用名字帮忙定位——这是精确匹配、不是模糊猜测：

1. **明确「该 id 不存在，不要用同一 id 重试」**——防止模型陷入无效重试
2. **若传入值精确等于某个工作流的 name**（说明模型把名字当 id 传了），直接告知它的 id；重名则列出全部 id，**不替它选**
3. **总是引导调 `list_workflows`**（带 `query` 可按名字过滤）——这才是真正的自愈路径

> ⚠️ **不做「附最近似 id」**。id 之间没有近似关系（`chat_saved_550e8400-...` 与 `wf_abc` 算不出相似度），模糊匹配等于用名字选目标，违反第 1 条。

**`get_workflow` 查不到 id 时走完全相同的处理**——写成一个**共用的错误构造函数**，不要两处各写一遍。

#### 4.4.2 空操作

补丁为空、或对工作流无实际变化 → **成功，但回执注明「无变化」**。

- 报错会让模型以为工具坏了并重试
- 静默成功会掩盖模型的理解错误（它以为改了、其实参数名写错了）

注明「无变化」能让模型自己发现补丁没生效。

### 4.5 审批 UI（P2）

当前审批渲染 `prettyFormatToolArguments`（`ChatScreen.kt:1714`）。方案 A 的入参结构可渲染成 diff：

```
修改工作流「每日签到」
  名称：每日签到 → 每日打卡
  第 1 步 delay：duration 2000 → 3000
  新增第 4 步：toast（提示「完成」）
  删除第 5 步：delay_2
```

**本批可先不做**（沿用现有 JSON 渲染，至少比方案 B/C 短一个量级），但结构已为它留好。

---

## 5. 一致性：不防陈旧读取（记已知限制）

**决策：不做 `if_modified_since` 之类的乐观锁。**

理由：单用户本地 App，同窗口极小。

**但必须记录一个已核实的真实风险**：`saveWorkflow` 的读改写是 `getAllWorkflows()` → 改一条 → **整个列表写回**（`WorkflowManager.kt:74-114`），所以两个写入者交错时**后写的会覆盖前者的改动**。这不是本功能引入的（编辑器手动保存同样如此），但 `update_workflow` 会提高它的触发频率。将来若需要，可选方案是 `get_workflow` 回传 `modifiedAt`、`update_workflow` 接受 `if_modified_since`。

---

## 6. 实施分期

| 批次 | 内容 | 依赖 |
|---|---|---|
| **P0** | `get_workflow`（工具定义 + `prepareGetWorkflow` + 输出格式含 step id / isDisabled / 只读字段） | 无 |
| **P0** | `update_workflow` 骨架：常量、工具定义、`UpdateWorkflow` prepared item、`prepareUpdateWorkflow` / `executeUpdateWorkflow` | 无 |
| **P0** | §3.2 步骤 id 锚定 + §4.4 共用错误构造函数（含 `get_workflow`） | 无 |
| **P0** | §3.1 `remapAfterStructuralChange` + jump 目标被删时拒绝 + 变量型 jump 标注与警告 | 无 |
| **P2** | §3.3 `WorkflowStructureValidator` 提取，接入 `update_workflow` **与 `save_workflow`**（**降级：只探测、不拦截**，见下） | 无 |
| **P0** | §3.3.4 块成员 `is_disabled` 拒绝 + §4.3.2 三条细则（null 语义 / move 基准 / insert 位置二选一）+ step id 字符集 | 无 |
| **P0** | §4.3.3 `folderId` 存在性校验 + 落盘异常回写 | 无 |
| **P0** | `buildParameters` 的 update 变体（以步骤现有参数为 base） | 无 |
| **P0** | steps 四类原语（update/insert/delete/move）+ `is_disabled` | 无 |
| **P0** | triggers 三类原语（update/insert/delete） | 无 |
| **P0** | §4.3.4 风险等级重算 + §4.3.3 原子性（含落盘回写） | 无 |
| ~~**P0**~~ **P2** | §4.3.5 逆补丁（**降级：只生成、不指望它能回滚块成员**） | 无 |
| **P1** | §3.4 函数签名引用方静态检查 + 警告 | P0 |
| **P1** | §4.2.4 长工作流 `step_range` 分段 | P0 |
| **P2** | §4.5 审批 UI 的 diff 渲染 | P1 |

### P2 降级后的行为（重要）

**块完整性校验**：P2 只做**探测**，不做**拦截**——即结构破损时在回执里**警告**（「该工作流当前存在结构问题：第 N 步 `If` 缺少 `EndIf`」），但**不拒绝写入**。理由：信任模型能力的前提下，硬拦截的收益低于它在存量破损工作流上造成的僵局（连改名都做不了）。

**逆补丁**：P2 仍在回执里生成，但**不再把它当作可靠的回滚手段**——块成员的删除/恢复需要多次原子调用才能成立（§4.3.5），单次调用下的正确性有前提。用户要可靠回滚，走编辑器 undo 或手动备份。

**P0 即为可用形态**：能查看详情、能用原语改步骤与触发器与元数据；jump 序号重映射、步骤 id 锚定、触发器 id 稳定性、`folderId` 校验、落盘回写这些**引用完整性保障仍在 P0**（它们防止的是「静默改错对象/断引用」，不是「结构不合法」）。

---

## 7. 测试要点

新增/修改测试文件（fork 独有，归我方）：

| 文件 | 覆盖 |
|---|---|
| `test/core/workflow/WorkflowStructureValidatorTest.kt`（**新增 · P2**） | 括号栈配对（9 组块）、缺 START / 缺 END / 孤立 END、`pairingId` 不匹配、define_function 首位。**注意**：校验器对 `BLOCK_MIDDLE` 只校验「栈顶配对 ID 相同」，**不校验 MIDDLE 是否存在或是否重复**——所以「If 缺 Else 合法」「重复 MIDDLE 合法」都是**预期行为**，测试要锁住这个语义（而不是去测「缺 MIDDLE 报错」，那与实现不符）。⚠️ **P2 出口是警告**，所以测试要断言「破损时产生警告」而**不是**「破损时抛错」 |
| `test/core/workflow/WorkflowJumpReferenceUpdaterTest.kt`（**改**，追加） | `remapAfterStructuralChange` 的增删场景；**反证**：删掉 jump 目标时报错而非静默保留旧序号；纯重排场景与原 `remapAfterReorder` 结果一致；**变量型 jump 原样返回** |
| `test/ui/chat/WorkflowPatchTest.kt`（**新增**） | 四类原语语义（update 是合并非替换、delete 的引用检查、move 的 1-based 口径、insert 的 id 冲突）；triggers 三类原语；**「遗漏 = 不变」**（不得解释成删除）；执行顺序固定；原子性（一个失败全不落盘）。**新增细则用例**：`null` = 删键而非写 null 值；`move.to_index` 按补丁前基准（含「同批有 delete」的场景）；`after_step_id` 与 `at_index` 同给/都不给均报错；`at_index` 越界报错；step id 字符集校验；**块成员 `is_disabled` 报错拒绝**；`folderId` 不存在时报错并附真实 id 列表；落盘异常时原 workflow 回写 |
| `test/ui/chat/ChatAgentToolingTest.kt`（**改**，追加） | `get_workflow` 输出含全部 step id / `isDisabled` / 只读字段标注；`update_workflow` 工具定义存在；错误信息不含「最近似 id」 |

**重点锁「改错了不报错、只静默变差」的地方**（本项目测试惯例）：

- **块结构破损必须被探测到（P2）**——If 缺 END 时条件不成立照样往下执行，**不做探测时完全静默**（出口是警告，不阻塞写入）
- **块成员 `is_disabled` 必须被拒**——静态结构校验**抓不到**它（步骤序列完整），但执行期会静默少跑循环（§3.3.4）
- **`move.to_index` 的基准**——写错会静默移到错位置，且这是主用例（整理步骤）
- **`folderId` 不存在** → 工作流从列表消失，**必须报错**
- **落盘异常时原 workflow 回写**——本项目无任何版本历史，不写回就是永久损坏
- jump 目标被删 → **必须报错**
- 变量型 jump（`{{vars.x}}`）→ **原样保留**，既不重映射也不当无效值丢弃；存在时要触发警告
- `update` 语义是**按 key 合并**（写成整表替换会静默丢参数）
- 未提及的步骤 id **必须保持不变**（写错会静默断引用）
- 未传的 metadata 字段 **必须保持原值**（写错会静默重置图标/快捷方式）
- 触发器 id **必须保持不变**（写错会静默断 `{{triggerId.outputId}}` 引用）
- 补丁**遗漏某步骤 ≠ 删除**（写成整表语义会静默删数据）

关键用例应做**反证**：把代码改回 bug 版本，确认测试变红。

---

## 8. 与既有设计文档的关系

- **上位文档**：本设计是 `chat-agent-rearchitecture.md` 目标架构的延续——那个文档把工具从 72 压到 16，本设计新增 2 个，总数 18，仍在同一量级。
- **`list_workflows` 的注释早已预留**：
  ```kotlin
  // ChatAgentToolRegistry.kt:90
  // 也是将来「修改工作流」工具的前置：要改某个工作流，先得知道它的 id。
  ```
  本设计正是那个「将来的工具」。`list_workflows`（找 id）→ `get_workflow`（看详情）→ `update_workflow`（改）构成完整链条。

---

## 9. 决策台账

| # | 决策 | 值 | 理由 |
|---|---|---|---|
| 1 | `update_workflow` 入参形态 | **操作原语补丁** | token 成本低 50 倍；天然规避 3/5 的静默失效点；审批可读 |
| 2 | jump 序号重映射 | **本批修**（`remapAfterStructuralChange`） | 增删步骤后静默跳错，是最隐蔽的坑 |
| 3 | 变量型 jump（`{{vars.x}}`） | **不重映射 + 显式标注 + 警告** | 静态重映射原理上做不到；报错会误伤无害修改；静默不动又会让模型以为改好了 |
| 4 | 步骤 id 锚定 | **强制**，禁掉 `step_N` 兜底 | 按 id 寻址是补丁式的地基 |
| 5 | jump 目标被删 | **报错拒绝** | 静默保留会让触发器跳飞，比报错更难排查 |
| 6 | 块结构校验 | **P2，且只探测/警告、不拦截** | 信任模型能力的前提下，硬拦截收益低于它在存量破损工作流上造成的僵局（连改名都被拒）。诊断结论保留（§3.3），落地时出口为警告 |
| 7 | 函数签名改动的引用完整性 | **静态查引用方 + 警告**，不动手改引用方 | 引用方搜索代价小；后果（静默失效）用户不会自己发现 |
| 8 | triggers 形态 | **与 steps 对称的原语**（无 `move`） | 整表提交有「遗漏 = 删除」缺陷；对称原语还杜绝 id 拼错变删除+新增 |
| 9 | 触发器 id | **必须保持不变** | 触发器输出按 id 键控（`WorkflowExecutor.kt:410-413`），id 变即断引用 |
| 10 | `isDisabled` | **纳入**，但**只对非块成员**（`get_workflow` 显示 + `steps.update` 可写） | 是「停用一步而不删」的唯一手段；AI 目前完全看不到。⚠️ **块成员必须拒绝**——执行器对 `BLOCK_END` 的禁用只跳一步（`WorkflowExecutor.kt:471-487`），禁用 `EndLoop` 会让循环静默只跑一遍且 `loopStack` 泄漏（§3.3.4） |
| 11 | 只读字段白名单 | **方案 A 天然规避**；`get_workflow` 输出中标注为只读 | 这些字段不在任何原语里，结构上改不到；标注防止无效重试 |
| 12 | define_function 首位保护 | **由结构校验器覆盖**（决策 6 顺带升级） | 位置保护与配对校验本来就在同一个函数里 |
| 13 | 执行顺序 | **固定**：update → insert → delete → move | 顺序影响结果，必须可复现 |
| 14 | 原子性 | **全失败则不落盘** | 半改状态比不改更难恢复 |
| 15 | `metadata` 语义 | **按 key 合并** | 与「不丢字段」的目标一致 |
| 16 | `steps.update` 的 parameters | **按 key 合并**，非整表替换 | 避免模型只给一个 key 就抹掉其余参数 |
| 17 | 风险等级 | **按改动后的完整工作流重算** | 风险是结果状态的属性，不是动作的属性 |
| 18 | 回滚 | **P2**：成功回执附逆补丁（不落新存储），**但不作为可靠回滚手段** | 零新增存储；对纯 update 场景准确，块成员场景有前提（§4.3.5）。可靠回滚走编辑器 undo 或手动备份 |
| 19 | 陈旧读取 | **不做**（记已知限制） | 单用户本地 App 同窗口极小；但记录 `saveWorkflow` 整列表写回的风险 |
| 20 | 只认 id | **是**；名字仅用于生成错误信息，绝不用于选目标 | 工作流允许重名，用名字选会静默改错对象 |
| 21 | id 不存在 | **报错 + 名字精确匹配时告知 id + 引导 `list_workflows`** | id 之间无「近似」，模糊匹配等于用名字选目标；**不做「最近似 id」** |
| 22 | 空操作 / 无变化 | **成功 + 回执注明「无变化」** | 报错会让模型重试；静默成功会掩盖参数名写错 |
| 23 | `get_workflow` 输出形态 | **文本 + 步骤区嵌 JSON** | 与现有 5 个工具风格一致；能塞进 JSON 装不下的标注 |
| 24 | `get_workflow` 与 `save_workflow` 输出结构 | **对齐** | 模型可直接把详情改一改喂给 save，两工具互通 |
| 25 | 工具形态 | **独立 `get_workflow`**，不给 `list_workflows` 加参数 | 职责分离；后者刻意不截断，塞步骤会让查 id 的调用也暴涨 |
| 26 | `get_workflow` 风险等级 | `READ_ONLY`（但默认档 OFF 下仍需人工点批准） | 纯读不弹审批只在自动批准档生效 |
| 27 | 审批 diff 渲染 | **P2 后置** | 现有 JSON 渲染已比方案 B/C 短一个量级 |
| 28 | 长工作流 | **P1 加 `step_range` 分段** | 防 200 步 JSON 一次进上下文 |
| 29 | 存量 trigger 残留在 steps | **沿用 save 约束但只对新步骤生效** | 不能因一处历史遗留让整个工作流不可编辑 |
| 30 | `validate` 范围 | **只覆盖新增/修改的步骤** | 同 #29 的理由：存量工作流可能含历史不过检的步骤，全量校验会让它彻底不可编辑 |
| 31 | `null` 的语义 | **= `remove(key)`**，不写 null 值 | 参数表里的 `key -> null` 与「键不存在」在执行期对 `isRequired` 校验不等价 |
| 32 | `move.to_index` 基准 | **一律按补丁执行前的列表** | 流水线里 move 在 delete 之后，按字面执行会与模型从 `get_workflow` 看到的序号差一个删除量，静默错位 |
| 33 | `insert` 的位置参数 | **`after_step_id` 与 `at_index` 二选一，同给/都不给均报错**；越界报错 | 未定义的行为会导致不可复现 |
| 34 | step id 字符集 | **`[A-Za-z0-9_-]+`** | id 会被拼进 `{{id.output}}`，含 `.` 或 `{{` 会**静默破坏引用解析** |
| 35 | `folderId` 校验 | **必须命中已存在文件夹**，否则报错 + 附真实 id 列表 | 未知 folderId 会让工作流**从列表上消失**（既不进文件夹也不进根目录） |
| 36 | 落盘原子性 | **落盘异常时把原 workflow 写回** | `saveWorkflow` 是覆盖式整表写，且本项目**无任何版本历史/回收站**，不写回即永久损坏 |
| 37 | 逆补丁的提交方式 | **必须在同一次调用里全部提交** | 块成员的恢复若分多次，中间态「开块无闭合」会被结构校验拒绝，回滚路径断裂 |
| 38 | 逆补丁时机 | **落盘成功之后**才附进回执 | 落盘失败时附逆补丁无意义 |
| 39 | 参数键校验时机 | **第 2 步（定位阶段）**就过 `buildParameters` 键校验 | 不能漏到 validate 阶段，更不能静默写成死参数 |
| 40 | 块删除 | **无需专用原语**——一次 `delete` 数组里列出块的全部成员即可 | 结构校验在全部变更之后执行，最终态合法即通过 |

---

## 10. 文件归属登记（供 FORK.md）

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/workflow/WorkflowPatch.kt`（**新增**） | fork 独有：`update_workflow` 的**纯函数补丁层**（参数按 key 合并、列表手术、jump 重映射、块成员判定）。刻意不含 Android 依赖，故 22 例语义单测可跑纯 JVM。⚠️ 与 `WorkflowJumpReferenceUpdater` 并存不重复：那个只处理「数量恒等的纯重排」（编辑器拖拽），本文件按 step id 建映射、支持增删 | **我方** |
| `test/core/workflow/WorkflowPatchTest.kt`（**新增**） | fork 独有：22 例。锁「改错了不报错、只静默变差」的语义——`null` = 删键（非写 null）、`to_index` 按补丁前基准、遗漏≠删除、jump 目标被删必须拒绝、变量型 jump 原样保留 + 警告。关键用例已做**反证**（改回 bug 版本确认变红） | **我方** |
| `ui/chat/ChatAgentNativeTooling.kt`（改） | `ChatAgentToolBackend` 枚举追加 `UPDATE_WORKFLOW`（1 行）。该文件已认长期分叉 | **手动合并**（追加枚举值） |
| `ui/chat/ChatAgentToolRegistry.kt`（改） | 新增 4 个常量 + 2 个工具定义（`get_workflow` / `update_workflow`）+ 4 个 schema 构造函数；`toolsByName` 注册 2 项。该文件已认长期分叉 | **我方**（认长期分叉） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改） | ① `ChatPreparedToolItem` 追加 `UpdateWorkflow` 子类（含 `workflowId` / `warnings`），随之补齐 5 处 `when` 分支；② 新增 `prepareGetWorkflow` / `buildGetWorkflowOutputText` / `describeStepLine` / `renderParameterValue` / **共用错误构造 `workflowNotFoundResult`**；③ 新增 `prepareUpdateWorkflow` / `applyMetadataPatch` / `applyStepPatch` / `applyParameterPatch` / `collectTouchedStepIds` / `executeUpdateWorkflow` / `buildUpdatedWorkflowResultText`；④ `prepareToolCall` 加 2 个分流。改动集中在这几处 | **手动合并** |
