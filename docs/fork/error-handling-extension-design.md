# 异常处理策略扩展设计（error-handling-extension-design）

> **版本** v1.0 · **对应分支/日期** `dev`（2026-10-15）
> **目录归属**：fork 独有文档（上游无此文件）⇒ 冲突归**我方**
> **用途**：把「异常处理策略扩展」的四项改动固定成**可实现、可交接**的规格：语义定案、实现落点、集成点、决策台账（含否决项）、测试清单。
> **前置阅读**：
> ① [`surveys/module-error-status-survey.md`](surveys/module-error-status-survey.md)（现状与 206 个模块的判据矩阵，本设计的问题背景全在那份里）；
> ② [`surveys/execution-termination-survey.md`](surveys/execution-termination-survey.md)（终止语义：停止 / 覆盖取消 / 超时 —— 本设计的 P0-4 与它的「终态口径」是同一片领地，**改动前先读它**）。
> **实现状态**：**四项 P0 全部已实现**（2026-10-15；P0-4 / P0-3 / P0-1 / P0-5，逐项实现记录见 §9.1）。
> 所有行号均已回代码核对，但会随上游漂移；引用前以代码为准。

---

## §0 一页结论

| 编号 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| **P0-3** | **状态可见**：成功路径也注入 `success`，并让它成为「声明输出」 | 无 | **✅ 已实现**（真机未验证） |
| **P0-1** | **重试耗尽后可跳过**（`RETRY` 不再必然落 `STOP`） | 无 | **✅ 已实现**（真机未验证） |
| **P0-4** | **静默终止**：新增第 4 种策略「终止但不弹窗」 | 无 | **✅ 已实现**（真机未验证） |
| **P0-5** | **跳过时跳转到指定步骤**（错误分支的轻量版） | 建议在 P0-3 之后 | **✅ 已实现**（真机未验证） |
| ~~P0-2~~ | ~~重试退避（指数 + 抖动）~~ | —— | **已否决**（§7.2 X1） |
| P1 / P2 | 工作流级错误处理器 / 步骤超时 / 错误分类 / 幂等标记 / 补偿步骤 | P0 | 仅登记（§9.3） |

**实施顺序**：P0-4（最小、独立、当天可验）→ P0-3（地基）→ P0-1 → P0-5。

**两个必须一起做的集成点**（否则「改了但没生效」，见 §4）：AI 侧保留键注入、编辑路径保留键保护。

---

## §1 需求与动机

### 1.1 用户原话（2026-10-15）

1. 「P0-1 重试耗尽后可支持走跳过，这个我同意。」
2. 「P0-2 的退避策略我感觉没有必要……就是把重试时间加了降级，然后封顶，还有抖动，我觉得没有什么大用。」
3. 「P0-3 这个状态可见我也同意。」
4. 「新增一个新的策略是**终止，但不弹窗报错**。」
5. 「有没有可能我们给跳过路径，**直接支持指定跳转到某一步**呢？」
6. （对「只允许向后跳」的反馈）「这有待商榷，其实有很多需要往前跳的场景吧。」

### 1.2 现状痛点（对应 survey 的结论）

| 痛点 | 依据 |
|---|---|
| 兜底状态**读不到** ⇒ 下游无法分流 | survey §2：注入的 `error`/`success` 不是声明输出；选择器不列、pill 渲染不出、AI 不知道 |
| `RETRY` 耗尽**必然终止**，无法降级 | `WorkflowExecutor.kt:798-799` 注释：「`POLICY_STOP (默认) 或 重试耗尽`」 |
| 失败**必然弹阻塞式弹窗** | `WorkflowExecutor.kt:810-829`；且 `showError` 是 suspend ⇒ **用户不点弹窗则终态不广播、`finally` 不执行**（`:805-809` 的注释记录了 Agent 路径踩过这个坑） |
| 想「失败后跳到收尾/补偿步骤」只能手拼 `IF` + `vflow.logic.jump` | 多两个步骤、多一处序号漂移点、且依赖 P0-3 |

---

## §2 语义定案

### 2.1 策略模型（4 值 + 1 个后续动作）

```
__error_policy        ∈ { STOP, STOP_SILENT, SKIP, RETRY }   // 新增 STOP_SILENT
__on_retry_exhausted  ∈ { STOP, STOP_SILENT, SKIP }          // 新增；仅 RETRY 时有效，缺省 STOP
__skip_jump_target    : String?                              // 新增；step id；仅 SKIP 时有效，缺省 null
```

| 策略 | 失败后行为 |
|---|---|
| `STOP`（缺省） | 通知栏 Failed + **弹窗** + 广播 `ExecutionState.Failure` + 终止 |
| **`STOP_SILENT`（新）** | 同上，**但不弹窗** |
| `SKIP` | 合成输出（`error` / `success=false`）→ 继续下一步**或**跳到 `__skip_jump_target` |
| `RETRY` | 重试 `__retry_count` 次（间隔 `__retry_interval`）→ 成功后正常；耗尽后按 `__on_retry_exhausted` 处理 |

**向后兼容硬约束**：两个新键**缺省值必须等于今天的行为**（`STOP` / 无目标）⇒ 存量工作流行为逐字不变。必须有单测锁死（§8 #2）。

### 2.2 P0-3 状态可见：`success` 的注入规则

| 情形 | `{{该步骤.success}}` | 谁写的 |
|---|---|---|
| 成功，模块**未**声明 `success` | `true`（**新注入**） | 执行器成功分支 |
| 成功，模块**声明了** `success` | 模块自己的值（可能是 `false`，如 `ClickModule` 的落点结果） | 模块（执行器**不覆盖**） |
| 被 `SKIP` | `false` | 执行器 SKIP 分支（已有） |
| 被 `STOP` / `STOP_SILENT` | 无（工作流已终止） | —— |
| 返回 `Signal` 的模块（如 `vflow.logic.jump`） | 仍读不到（Signal 分支不写 `stepOutputs`） | 记录为例外，见 §6 B6 |

**载体选择（定案）**：把合成的 `success` 加在 **`BaseModule.getDynamicOutputs` 的默认实现**里（`core/module/BaseModule.kt:62-64`），而不是只改 UI 侧。

理由：**变量选择器、pill 解析、触发器 `outputSchema` 都调 `getDynamicOutputs`** ⇒ 一处改动三处点亮；只改 UI 侧则 pill 仍会退化成原文（`VariableInfo.fromMagicVariableWithProperty` 要求输出被声明，`core/execution/VariableInfo.kt:210`）。

> ⚠️ **实现期更正（2026-10-15）**：本稿原先写「AI catalog 也调 `getDynamicOutputs`」是**错的** —— AI 侧拼 `outputs:` 段读的是 **`getOutputs`**（`ui/chat/ChatAgentModuleExecutor.kt:2819`）。所以**必须在那处也显式接同一个供给层**，否则会造成「人工能选、AI 看不见」的口径分裂。P0-3 实现里已补（见 §9.1 的 P0-3 实现记录）。

**已知边界**：

- 3 个模块 override 了 `getDynamicOutputs`（`TextProcessingModule` / `CallWorkflowModule` / `ForEachModule`）⇒ 不会自动获得该项（**可接受，文档记录**）；
- 73 个模块自己声明了 `success` ⇒ 靠**去重**避免重复项；
- `generateDefaultOutputs`（`:953-968`）用的是 `getOutputs`，不受影响。

### 2.3 P0-4 静默终止：静默到哪一层

**定案：只静默「弹窗」，其它一律照旧。**

| 通道 | 是否保留 |
|---|---|
| 通知栏 `Failed: <错误信息>`（`:800`） | ✅ 保留 |
| `ExecutionStateBus.postState(ExecutionState.Failure(...))` | ✅ 保留（首页「最近日志」/ 磁贴 / Chat 卡片都靠它） |
| 执行日志的 `E/WorkflowExecutor: 模块执行失败:` 行 | ✅ 保留 |
| 阻塞式错误弹窗 | ❌ **跳过** |

**顺带收益（独立于「不打扰」的健壮性改进）**：弹窗是 `suspend` 的，用户不点 ⇒ 终态广播与 `finally`（WakeLock / 工作目录 / 执行登记清理）都不执行（`:805-809` 的注释记录了 Agent 路径踩过这个坑）。静默终止让失败路径变成**不依赖 UI 交互**的纯状态机路径。

**不做的变体**：「连通知栏也不标失败」——会重演「静默失败」反模式（用户彻底无从知道）。若将来确实需要，再单独加更彻底的变体。

**与既有「静默执行」的关系（正交，且遵循同一条原则）**：

| 机制 | 层级 | 静默什么 | 失败豁免 |
|---|---|---|---|
| `Workflow.silentExecution`（**已有**，`core/workflow/model/Workflow.kt:34-41`；判定在 `services/ExecutionNotificationManager.kt:83-88`） | **工作流级** | 过程性噪音（执行进度通知 + 「开始执行」Toast） | ✅ **刻意豁免**——注释原文：「静默的语义是『别播报过程』，不是『炸了也别告诉我』」 |
| **`STOP_SILENT`（本设计）** | **步骤级** | 阻塞式错误弹窗 | ✅ 通知栏 / 终态 / 日志全部保留 |

⇒ 两者**互不覆盖**，可以同时开启（静默工作流 + 静默终止 = 全程无打扰，但失败仍留在通知栏与日志里）。本设计的取舍**正是既有原则的自然延伸**，实现时不要把它做成"顺带把通知也静默掉"。

### 2.4 P0-5 跳过跳转：目标怎么存

**定案：存 step id（`__skip_jump_target`），不存显示序号。**

| 方案 | 插入/删除步骤后 | 最坏情况 |
|---|---|---|
| 存显示序号（`vflow.logic.jump` 的做法） | 目标漂移 | **静默跳错步骤** |
| **存 step id（本设计）** | 天然不漂移 | 目标被删 ⇒ 反查不到 ⇒ **降级为 `pc++`** + W 日志 + 编辑器警告（可接受） |

执行时反查：`workflow.steps.indexOfFirst { it.id == targetId }`；UI 显示「#7 输出日志」给人看，**存的是 id**。AI 侧本来就按 id 锚定（`docs/fork/workflow-read-write-tools.md` §3.2）⇒ 无缝。

### 2.5 P0-5 跳转规则（含「允许向前跳」）

**允许任意方向**（用户诉求：失败后回到开头重跑、回到上一步重做、状态机式编排），用**三层防护**替代「禁止向前」：

| 防护 | 机制 | 触发后行为 |
|---|---|---|
| 单步骤跳转上限 | 执行器内记 `stepId → 本次执行内的跳转次数`（上限 **10**，常量 `MAX_JUMPS_PER_STEP`） | 超限 ⇒ **不跳转**，改为 `pc++` + `W` 日志（**降级必须可见**） |
| 执行级跳转总预算 | 全局计数器（上限 **200**，常量 `MAX_JUMPS_PER_EXECUTION`） | 超限 ⇒ **终止并报「疑似死循环」**（比静默继续安全） |
| 编辑器静态环检测 | 步骤 → 目标建图查环（`detectCycles`） | **警告**（卡片标红 + toast），**不阻止保存** |
| 兜底 | `workflow.maxExecutionTime`（`WorkflowExecutor.kt:308-312`，**可选**） | 检测到环时提示用户去设置它。⚠️ **它不是硬超时**（只在挂起点检查，见 `execution-termination-survey.md` §4.5）：对"跳转死循环"这类**每步都有挂起点**的循环**有效**（`module.execute` 是 suspend），对"卡在阻塞模块内部"无效 ⇒ **不能只靠它**，故前面三层才是主力 |

> ⚠️ **实现期更正（2026-10-15）**：
> 1. 两个阈值（10 / 200）**已定案**并落成常量（原先标的是"建议值"）；
> 2. 第三层防护**只做了 toast 警告**，没做「卡片标红」—— 后者要动上游
>    `WorkflowEditorActivity` 的列表绑定，与「控制 diff 面积」相悖。**登记为未做项**（§9.1 P0-5 实现记录）。

**唯一保留的禁止项：向内跳**（跳到块体内部）。与方向无关，是**结构约束**：

- 跳到 `Loop` 体内 ⇒ 该循环 `loopStack` 没 push ⇒ 执行到 End Loop 时 `loopState == null` ⇒ **静默 `Success()`**（`LoopModule.kt:166-168`）；
- 跳到 `ForEach` 体内 ⇒ End ForEach ⇒ **`Failure("执行错误", "结束重复模块不在一个'重复每一项'循环内。")`**（`ForEachModule.kt:179-182`）；
- 跳到 `If` 的中间分支 ⇒ 分支配对失配，行为未定义。

**判据**：目标 `indentationLevel` **≤** 当前步骤的 `indentationLevel`（`ActionStep.kt:16`，编辑器维护，`WorkflowEditorActivity.kt:2279-2294`），且目标**不是**块中间/结束模块（`module.blockBehavior.type == NONE`）。UI 候选过滤与运行期校验**都用它**（UI 少给错选项，运行期才是防线）。

> ⚠️ **实现期澄清（2026-10-15）**：上面那句「不是块中间/结束模块（`type == NONE`）」的括号是**笔误** ——
> 「不是 MIDDLE / END」**不等于**「必须是 NONE」：块**开始**模块（`Loop` / `If` 的 start）**可以**当目标
> （「回到循环开头重跑」是合法诉求）。实现里判的是 `role != MIDDLE && role != END`（`JumpTargetSupport.isAllowedTarget`）。

**向外跳时必须对齐 `loopStack`**：现状 `ExecutionSignal.Jump` 只做 `pc = signal.pc`（`:855-857`），**不碰 `loopStack`** ⇒ 从循环体内跳到循环外会残留 stale 条目（`vflow.logic.jump` 今天就有这个问题，见 §6 B3）。

`LoopState` **不记录自身起始位置**（`ExecutionContext.kt:25-35`），故用推断法对齐：`enclosingLoopCount(steps, pos)` 反复从 `pos` 与 `s - 1` 往外找（`BlockNavigator.findCurrentLoopStartPosition`），然后 `while (loopStack.size > enclosingLoopCount(...)) loopStack.pop()`。

> ⚠️ **实现期更正（2026-10-15）**：往外找时必须**从 `position - 1` 起搜**，不能从 `position` 起 ——
> 循环状态是「循环**开始步骤执行时**」才压栈的（`LoopModule.execute`），所以「站在循环开始步骤上」时
> 该循环**还没进栈**，它不算包住自己。差这一格会让「从循环体内跳回自己的循环开头」多压一份状态
> ⇒ 循环结束后 `loopStack` 里留一个 stale 条目。实现见 `JumpTargetSupport.findEnclosingLoopStart` 的注释。

### 2.6 `[[__error]]` 错误上下文（定案）

**问题**：一个收尾步骤通常被**多个**失败步骤指向 —— 没有上下文，它就不知道「是谁跳过来的、错在哪」。（对应 n8n 的 `$json.error`、Step Functions `Catch` 的 `ResultPath`。）

**定案**：**恒注入**一个命名变量 `[[__error]]`（**VDictionary**），正常路径是**空字典**：

| 键 | 内容 |
|---|---|
| `step_index` | 失败步骤的显示序号（1-based，给人看） |
| `step_id` | 失败步骤的 step id（稳定锚） |
| `module_id` | 模块 id |
| `title` | `Failure.errorTitle`（如「参数错误」/「权限不足」） |
| `message` | `Failure.errorMessage` |

**为什么载体必须是字典**：`为空 / 不为空` 作用在**真 VDictionary** 上是**可靠**的（`ConditionEvaluator.kt:119`：`is VDictionary -> input1.raw.isEmpty()`），恰好**绕开** survey §3 记录的那整套 `VNull` 陷阱（`存在/不存在` 恒常量、`VNull` 参与 `为空` 恒 false）。用字符串载体则判据立刻失效。

**先例**：`[[__trigger_label]]` 就是同样的做法（恒注入、缺省给空值而非缺键，`WorkflowExecutor.kt:288-294`；注释解释了「缺键会解析成字面量」），picker 侧有「固定分组」机制（`WorkflowEditorMagicVariableCatalogBuilder.buildNamedVariables` 的 `editor_group_trigger_label`）。

**收敛条件**：只在工作流**确实存在「失败跳转」**时才在变量选择器里出现该分组（与 `wantsWholeStepOutputItem` 那条「按提供方 → 消费者精确配对」的既有哲学一致，`WorkflowEditorMagicVariableCatalogBuilder.kt:33-49`）。

**与 P1 的关系**：这个对象**步骤级跳转与工作流级错误处理器共用**。字段集合现在定死，P1 只追加（如 `log_tail` / `executed_step_count`），不改已有字段。

---

## §3 实现落点

### 3.1 文件清单与 diff 面积

| 文件 | 归属 | 改什么 | 估计 |
|---|---|---|---|
| `core/workflow/model/ActionStepExecutionSettings.kt` | 上游 | +`POLICY_STOP_SILENT` / `KEY_ON_RETRY_EXHAUSTED` / `KEY_SKIP_JUMP_TARGET` 常量与字段 + `fromParameters` 解析 | ~15 行 |
| `core/execution/WorkflowExecutor.kt` | **上游热路径** | ① 成功分支注入 `success`（`:759-764`）；② `effectivePolicy`（`:768` 处判断改用有效策略）；③ 静默判断（`:810`）；④ SKIP 分支支持跳转 + `loopStack` 对齐（`:796-797`）；⑤ 跳转三层防护 | ~40 行 |
| `core/module/BaseModule.kt` | 上游 | `getDynamicOutputs` 默认实现追加合成 `success`（去重） | ~6 行 |
| `core/module/StepStatusSupport.kt` | **新增（我方，P0-3）** | 纯函数供给层：`SUCCESS_OUTPUT_ID` / `successOutputDefinition()` / `withSuccessOutput()` / `withSuccessFlag()` | 新文件 |
| `ui/settings/SettingsScreen.kt` | 上游（P0-4 / P1） | 全局默认策略由 3 选项补成 4（`DefaultErrorPolicyRow` 的选项列表 + 1 个 `stopSilentLabel` 参数 + 1 条文案） | ~5 行 |
| `core/workflow/JumpTargetSupport.kt` | **新增（我方）** | 纯函数：`resolveTargetIndex` / `isAllowedTarget` / `enclosingLoopCount` / `detectCycles` | 新文件 |
| `ui/workflow_editor/ActionEditorSheet.kt` | 上游 | 策略 4 选 1；`RETRY` 时显示「用尽后」下拉；`SKIP` 时显示「跳到步骤」选择器 | ~35 行 |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | 上游（fork 已改多处） | 注入 `[[__error]]` 固定分组（仅在存在失败跳转时） | ~15 行 |
| `core/module/definitions.kt` | 上游 | `PickerType` 新增 `STEP`（既有扩展点，`PickerHandler.kt:57-66` 是集中分发） | ~2 行 + 新 sheet |
| `ui/chat/ChatAgentModuleExecutor.kt` | 上游（fork 已改多处） | ① `resolveModuleInputDefinitions`（`:3159-3175`）注入全部保留键；② **P0-3**：拼 `outputs:` 段（`:2819`）改走 `StepStatusSupport.withSuccessOutput(module.getOutputs(step))` | ~13 行 |
| 测试（4~5 个文件） | **新增（我方）** | 见 §8 | ~18 例 |

> ⚠️ 执行器改动落在**上游核心热路径**上 ⇒ 必须按 `FORK.md` 登记为「手动合并」。

#### 实现期与本文档的偏差（2026-10-15 落笔时回填）

| 项 | 本文档原计划 | 实际实现 | 为什么 |
|---|---|---|---|
| 错误上下文的落点 | 未列文件 | **新增 `core/workflow/ErrorContext.kt`** | 常量 + 构造器 + `EMPTY` 需要一处**唯一**的家（编辑器选择器与执行器共用）；塞进 `ActionStepExecutionSettings` 会把「步骤设置」与「执行期变量」混在一起 |
| 编辑器选目标 | 新增 `PickerType.STEP` + 新建 sheet（`core/module/definitions.kt` 改） | **`StandardControlFactory.createSpinner` 下拉**（`options` = step id，`displayOptions` = `#序号 模块名`） | 既有的 `createSpinner` **天然支持「值/显示分离」**（`displayOptions` 参数），一个下拉就够；新增 picker 类型要动上游 `definitions.kt` + `PickerHandler` 分发 + 新 sheet ⇒ diff 面积大得多。**`PickerType.STEP` 登记为未做项** |
| 变量选择器的错误上下文 | 「注入 `[[__error]]` 固定分组」 | 同左，但用**一个条目 + `dictionaryKeys`** 表达五个字段 | `MagicVariablePickerSheet` 已有「已声明的键」机制（`dictionaryKeys`，为函数工作流返回值做的），用户点进条目即可选键，比列 6 个平级条目更清楚 |
| 编辑器环检测 | 「卡片标红 + toast」 | **只有 toast**（+ `W` 日志） | 卡片标红要动上游 `WorkflowEditorActivity` 的列表绑定 ⇒ 与「控制 diff 面积」相悖。**登记为未做项** |

### 3.2 执行器三段代码草案

```kotlin
// ① 策略链：循环结束后算一次「有效策略」，SKIP 分支一行不改
val effectivePolicy = if (errorPolicy == ActionStepExecutionSettings.POLICY_RETRY)
    executionSettings.onRetryExhausted else errorPolicy

// ② 成功分支：注入状态，但不覆盖模块自己给的 success
is ExecutionResult.Success -> {
    val merged = if (result.outputs.containsKey(SUCCESS_OUTPUT_ID)) result.outputs
                 else result.outputs + (SUCCESS_OUTPUT_ID to VBoolean(true))
    if (merged.isNotEmpty()) stepOutputs[step.id] = VObjectFactory.fromMapAny(merged)
    pc++
}

// ③ 静默终止：只多一个与条件
val shouldShowDialog = showErrorDialog &&
    executionSettings.policy != ActionStepExecutionSettings.POLICY_STOP_SILENT
if (shouldShowDialog) { /* 原样 */ }

// ④ SKIP 分支：写输出后决定 pc
stepOutputs[step.id] = skipOutputs
writeErrorContext(executionContext, step, result)          // 恒注入 [[__error]]
val target = resolveSkipJumpTarget(workflow.steps, step, executionSettings)
pc = if (target != null && allowJump(step.id)) {
    alignLoopStack(loopStack, workflow.steps, target)
    target
} else pc + 1
```

---

## §4 ⚠️ 三个「不做就静默失效」的集成点

### 4.1 AI 侧对未知参数键**整单拒绝** —— ✅ **已解决（2026-10-15，提前于 P0-1）**

`ui/chat/ChatAgentModuleExecutor.kt` 的 `resolveModuleInputDefinitions` 是 **catalog 与执行校验的共用口径**（被 `call_module` 的 `buildParameters`、`update_workflow` 的 `applyParameterPatch`、工具 schema、`query_module_schema` 四处共用）。它原先只对 `vflow.trigger.*` 注入了 `__trigger_label`。

**修补前的后果（实测）**：`__error_policy` / `__retry_count` 没被注入 ⇒ **AI 完全无法为步骤配置异常处理策略**（写进去会被 `rejectedKeys` 拒绝，`update_workflow` 甚至整份补丁不落地）—— 模型只能说「我改不了，请你在编辑器里点」。

**已做的修补**：新增 `stepExecutionSettingInputDefinitions()`（同文件顶层私有函数），在 `resolveModuleInputDefinitions` 里对**所有**模块注入 `__error_policy`（ENUM，`options` = 4 种策略）/ `__retry_count` / `__retry_interval`：

| 决定 | 理由 |
|---|---|
| **`isHidden = true`** | 对「直接调用」而言这些键没有意义（`call_module` 没有「步骤」这一层）⇒ 不进 `ToolSchemaGenerator` 生成的 per-tool JSON Schema；而 `query_module_schema` 的 `inputs:` 段**不按 isHidden 过滤** ⇒ 模型仍能发现它们 |
| `name` / `hint` 用**英文** | `query_module_schema` 输出整体是面向模型的英文，且这里没有 `nameStringRes`（不给人看），与模块作者写的 `inputHints` 同一惯例 |
| 同时更新 `update_workflow` 的工具描述 | 那里是「写方」的发现面：新增一段说明这些键是**步骤级设置**、取值与默认值、以及 `STOP_SILENT` / `SKIP` 的语义 |
| **不碰编辑器** | 编辑器读的是 `module.getInputs()`，与这个 AI 侧口径无关 ⇒ 保留键仍不会出现在编辑器的通用参数表单里（与触发器标签同因同果） |

**顺带解锁的能力**：模型现在能配 `STOP` / `STOP_SILENT` / `SKIP` / `RETRY`，**并且能给临时工作流的步骤设策略** ⇒ 真机验证「静默停止」可以由 Agent 自己完成 A/B 对比（默认策略会弹窗 vs `STOP_SILENT` 不弹窗），不必等人手动点。

**新增测试**：`ReservedStepKeyInjectionTest`（4 例，纯 JVM）—— 普通模块也接受保留键 / 策略键是 ENUM 且**自动枚举** `POLICY_*` 常量核对取值 / 保留键必须 `isHidden` / **自动枚举** `KEY_*` 常量核对注入表（P0-1 加 `__on_retry_exhausted`、P0-5 加 `__skip_jump_target` 时漏注入会当场变红）。

### 4.2 编辑路径的「整表替换」会吃掉保留键

`ui/workflow_editor/WorkflowEditorActivity.kt:1066-1073`（动作编辑器）在 `focusedInputId == null` 分支是**整表替换**（`copy(parameters = newStepData.parameters)`）。

**今天安全**：`ActionEditorSheet` 从 `existingStep.parameters` 起步（`ActionEditorSheet.kt:112`）并整表回吐（`:185` `currentParameters.toActionStep(...)`）⇒ 保留键会被带回。但**触发器路径**正是栽在这里（`FORK.md` 记录：整表替换吃掉 `__error_policy`/`__retry_count`，已修为合并式 `:1172-1185`）。

⇒ 每加一个保留键，所有「整表替换 `parameters`」的代码路径都多一份风险。**必须加一条源码扫描型断言**（本仓库已有此测试范式，如 `SwitchEditorSheetWiringTest` / `TriggerLabelWiringTest`）。

### 4.3 日志行文案必须复用（否则 AI 卡片**静默误报**）

`ui/chat/ChatMessagePatch.kt:252` 的 `SKIPPED_FAILURE_LINE_PREFIX = "W/WorkflowExecutor: 根据策略，跳过错误继续执行。"` 是 AI 侧判定「有步骤被跳过」的**唯一锚点**（该文件 `:235-251` 的注释解释了为什么不能用失败行做判据）。

⇒ 跳转分支**必须继续打同一行**（跳转信息作为额外一行 `D` 级日志），否则卡片会把「有步骤失败被跳过」**误报成 SUCCESS**。若确实要改文案，须同步改常量 + `test/ui/chat/ChatTemporaryWorkflowResultTest.kt`。

---

## §5 上游为什么用序号存（我们为什么不照抄）

> 本节记录**推断 + 证据**，供将来合并上游时理解上下文 —— **不要把它当成「上游的 bug」顺手改掉**。

### 5.1 四个理由

1. **执行层的「原生货币」就是序号**（最强）：`ExecutionSignal.Jump(pc: Int)`、执行器 `pc: Int`、`BlockNavigator` 全部按 position 索引；且该信号被 If / Switch / Loop / ForEach / Menu / Break / Continue 共用，块模块算出来的目标本来就是位置（`findEndBlockPosition` 返回 `Int`）。
2. **参数与 UI 天然对应**：`target_step_index` 是 `NUMBER` 输入框（`JumpModule.kt:34-45`），所见即所得；用 id 则需要「步骤选择器」控件，而上游当时**没有**这个 picker 类型。
3. ⭐ **序号支持「动态跳转」，id 做不到**：该参数声明了 `acceptsMagicVariable` + `acceptsNamedVariable`（NUMBER 类型）⇒ 用户可在运行时算出目标（`{{vars.x}}`、循环变量）。**id 是编译期常量，算不出来**。`workflow-read-write-tools.md` §3.1.4 专门讨论过这类「变量型目标」（静态算不出，只能原样保留 + 警告）。
4. **当时的假设是「步骤列表基本不动」**（时间线见 §5.2）。

### 5.2 时间线（硬证据）

```
2025-09-11  e42e6ff9  feat: Add While, Jump, BreakLoop modules and icon updates   # JumpModule 诞生
2025-09-14  c17be0aa  feat(logic, editor): Implement For Each loop ...            # 拖拽重排 moveBlockInList 诞生
2026-04-26  a457d5ff  feat(JumpModule): 自动更新跳转序号                            # 引用更新器诞生（上游作者）
```

⇒ **先写位置引用 → 3 天后才有重排 → 7 个月后才补一个「只覆盖拖拽」的更新器**。漂移问题在 jump 被写下时**还不存在**；出现后上游选择的是「给位置引用打补丁」，而不是「把引用改成身份」—— 这解释了它为什么只覆盖等长重排（它是为「拖拽」这个具体功能写的）。

### 5.3 两套实现并存（现状）

| 实现 | 位置 | 覆盖 |
|---|---|---|
| 上游 `remapAfterReorder` | `core/workflow/WorkflowJumpReferenceUpdater.kt:13-46` | **仅等长重排**（开头就 `if (size != size) return`），唯一调用点 `WorkflowEditorActivity.kt:1631` |
| fork `remapJumpReferences` | `core/workflow/WorkflowPatch.kt:99-136` | **支持增删**（按 step id 建映射）；目标被删 ⇒ 返回 `null` ⇒ 调用方 `Rejected`（`:335-341`） |

**三条编辑路径的实际覆盖**：

| 路径 | 用哪套 | 覆盖 |
|---|---|---|
| 编辑器拖拽 | 上游版 | ✅ 等长重排 |
| 编辑器插入 / 删除 | **无**（只调 `module.onStepDeleted`，`:1431`；`JumpModule` 未实现该钩子） | ❌ **B1 就在这里** |
| AI 补丁 | fork 版 | ✅ 增删移 + 目标被删拒绝 |

> 成因不是「谁写漏了」，而是**两套实现并存、能力不同、谁也没接另一边**。`workflow-read-write-tools.md:149` 已经预留了修法：「**将来编辑器可切过来**」。

### 5.4 定案：双字段 + 双写（若给 jump 也上 id）

| 字段 | 用途 | 谁写 |
|---|---|---|
| `target_step_id` | **静态目标**（优先），插入/删除不漂移 | UI 的步骤选择器（`PickerType.STEP`） |
| `target_step_index` | **动态目标**（保留 `{{}}`/`[[]]` 支持）+ 存量兼容 | 用户手填数字/变量 |

解析顺序：**有 id → 用 id 反查序号；无 id → 用序号**。

**迁移用「双写」**：保存时同时写 id 与序号 ⇒ 老版本/上游版本打开新工作流**不会崩**（未知键被忽略、序号字段仍在、退化为现状行为）；代价是老版本仍会漂移（可接受，那是既有行为）。

**本设计的 P0-5 只做静态目标（存 id）**，把「动态跳转」留给 `vflow.logic.jump`（它本来就支持）—— 两条路各司其职。

---

## §6 `vflow.logic.jump` 既有缺陷清单（7 条）

| 编号 | 缺陷 | 证据 | 本次处理 |
|---|---|---|---|
| **B1** | 插入/删除步骤后目标**静默漂移**（人工路径不重映射） | §5.3 | **本次修**（编辑器调用点切到 fork 版；`null` 返回按 AI 侧同语义处理） |
| **B2** | 跨块/向内跳**无校验** ⇒ 破坏块结构，症状随块类型不同 | `JumpModule.kt:59-73`（validate 只查整数与 `≥1`）、`:110-116`（execute 只查 `in indices`）；`LoopModule.kt:166-168`（静默 Success）vs `ForEachModule.kt:179-182`（Failure） | **本次修**（复用 `JumpTargetSupport.isAllowedTarget`） |
| **B3** | 跨循环跳出后 `loopStack` **不清理**（执行器侧） | `WorkflowExecutor.kt:855-857`；`LoopState` 不记起始位置（`ExecutionContext.kt:25-35`） | **本次修**（`alignLoopStack`） |
| **B4** | **无环保护**（自跳 / A→B→A） | 唯一兜底是可选的工作流超时（`:308-312`） | 本次修（§2.5 三层防护） |
| **B5** | 跳到**已禁用**步骤 ⇒ 目标静默变成「其后第一个可执行步骤」 | `WorkflowExecutor.kt:588-603` | 只登记（影响面小） |
| **B6** | `success` **声明了却从不返回**（Signal 分支不写 `stepOutputs`） | `JumpModule.kt:48` vs `WorkflowExecutor.kt:852` | 只登记（P0-3 不会自动修好它） |
| **B7** | **测试几乎为零**：`JumpModuleTest` 只有 2 例（都只测「接受命名变量」）；`WorkflowJumpReferenceUpdaterTest` 只有 3 例（全在等长重排分支） | 实测 | 本次补（§8） |

**修 B1 的两个选项**：

- **甲（推荐，符合本 fork 做法）**：把编辑器的调用点切到 `WorkflowPatch.remapJumpReferences`（public、在 `core/workflow`、无 Android 依赖）—— 插入/删除路径新增调用 + 拖拽路径一并切（fork 版「纯重排也能走」）；**上游文件 `WorkflowJumpReferenceUpdater.kt` 仍然不动**，保持 `FORK.md` 里的「（暂未分歧）」。
- **乙（彻底）**：jump 也改成存 id（§5.4 双字段 + 双写）—— 属**数据迁移**，且要动上游模块的参数语义，风险更大。建议**单独立项**。

**长期风险**：两份语义相近的实现并存 ⇒ 未来改 jump 引用语义时**极易只改一处**（本仓库吃过「两份实现必须同时改」的亏，见 `FORK.md` 里 core 侧 logcat 移植版那条）。建议加一条 **fork 侧的契约一致性测试**（同样输入，两版对「变量型目标/越界目标」的处理必须一致）—— 纯新增测试文件，不动上游。

**顺带发现（文档漂移，建议下次修订回填）**：`workflow-read-write-tools.md` 里 §3.1.3 的函数名仍是设计期名 `remapAfterStructuralChange`（`:143` / `:778` / `:808` / `:848`），实现叫 `remapJumpReferences`；`:139` 写的调用点 `WorkflowEditorActivity.kt:1428` 实测为 `:1631`。

---

## §7 决策台账

### 7.1 定案

| # | 决策 | 理由 |
|---|---|---|
| D1 | 策略模型 = **4 值策略 + 1 个「重试用尽后」动作** | 成熟实现里「重试」与「兜底」是两件事（Step Functions `Retry`+`Catch`、Airflow `retries`+`on_failure_callback`） |
| D2 | `success` 合成输出加在 **`BaseModule.getDynamicOutputs`**，而非只改 UI | 选择器 / pill / AI catalog / outputSchema 共用同一口径 |
| D3 | 静默终止**只静默弹窗** | 通知栏与终态是用户唯一的知情渠道，不能一起静默 |
| D4 | 跳过跳转目标**存 step id** | 序号 = 位置引用 ⇒ 必然漂移（§5.3）；id 把整类问题消掉 |
| D5 | **允许向前跳**，用三层防护替代方向限制 | 用户诉求真实存在；成熟范式也不禁（Step Functions 允许 Choice 回跳） |
| D6 | **禁止向内跳**（跳进块体内部） | 结构约束，与方向无关（§2.5） |
| D7 | 错误上下文用 **`[[__error]]` 字典**（恒注入，正常路径空字典） | `为空/不为空` 对真 VDictionary 可靠 ⇒ 绕开 `VNull` 陷阱（§2.6） |
| D8 | 日志行文案**复用** SKIP 专属行 | AI 卡片锚点（§4.3） |
| D9 | P0-5 只做**静态目标**；动态跳转留给 `vflow.logic.jump` | 两条路各司其职，互不迁就 |
| D10 | 跳转目标的**存储**只存 id；**UI 用下拉**（值 = id，显示 = `#序号 模块名`），不新增 `PickerType.STEP` | 既有 `createSpinner` 的 `displayOptions` 正好表达「给人看 vs 给机器用」；新增 picker 类型要动上游 `definitions.kt` + 分发 + 新 sheet |
| D11 | 错误上下文在**每次被兜住的失败**上都写（不限于「配了跳转」的失败） | 收尾步骤之外的普通下游步骤同样可能想知道「上游是不是失败过」；写入是覆盖式的，最后一次兜住的失败胜出 |
| D12 | 编辑器里「跳转目标」的可见性与写回判据都是**有效策略**（含「RETRY + 用尽后 = SKIP」），不是裸 `policy` | 否则「重试 3 次，还不行就跳到收尾步骤」这个最自然的组合在 UI 上**配不出来** |
| D13 | 跳转总预算用尽**复用 STOP 分支的终止路径**，只把提示文案换成「疑似死循环」（`stopReason`） | 在失败分支里**再抄一份**终止代码（通知 + 注销 + `postState(Failure)` + `return null`）会与 STOP 分支各自漂移；也不能把这十几行抽成公共函数 —— `AgentErrorDialogWiringTest` 按**函数体**扫描弹窗门禁，搬走就扫不到 |
| D14 | 环检测**只 toast 警告**，不阻止保存；「卡片标红」登记为未做项 | 环也可能是有意的有限循环（运行期两级上限兜底）；卡片标红要动上游编辑器列表绑定 |

### 7.2 已否决

| # | 否决项 | 理由 | 重提条件 |
|---|---|---|---|
| X1 | **重试退避（指数 + 抖动）** | 手机端无服务端重试风暴风险；vFlow 的重试动机以「等界面出现」为主，**固定间隔更贴合**；条件等待已有 `vflow.interaction.find_until`（内部 `while (now - start < timeoutMs)` + `delay(interval)`，`FindTextUntilModule.kt:135`）。且 SSE 侧的成熟实现（`ChatStreamRecovery.kt:27-46`，指数 + 封顶 + ±10% 抖动）已覆盖它真正适用的场景 | 出现「多工作流并发重试同一外部服务导致批量失败」的真实证据（网络类步骤的批量日志） |
| X2 | **`ErrorEquals`**（按错误类型选不同跳转目标） | 会让 UI 从「一个选择器」变成「一张规则表」；而 vFlow 已有更强的通用分流器 | 用 `IF [[__error.title]] 包含 "权限"` 在目标步骤里自己分流即可（把判断留给 IF 模块） |
| X3 | 跳到 **label / 命名锚点** | vFlow 无 label 概念；id 已解决漂移 | —— |
| X4 | 跳转时执行**完整补偿链（Saga）** | 补偿链需要「容器」承载，硬塞进步骤参数会失控 | P1 的**工作流级错误处理器**是更合适的容器（复用 §2.6 的上下文对象） |
| X5 | **「连通知栏也不标失败」的彻底静默** | 会重演「静默失败」反模式（用户彻底无从知道） | 有明确诉求时再单独加变体 |

---

## §8 测试清单（纯 JVM，`./gradlew test`）

| # | 用例 | 对应 |
|---|---|---|
| 1 | `RETRY + onExhausted=SKIP`：第 N+1 次成功 ⇒ 正常继续 | P0-1 |
| 2 | **缺省兼容**：无 `__on_retry_exhausted` ⇒ 耗尽终止（存量行为） | P0-1 向后兼容 |
| 3 | `RETRY + onExhausted=SKIP`：全失败 ⇒ 走 SKIP 分支（`stepOutputs` 有 `error` + `success=false`），**不终止** | P0-1 |
| 4 | 成功且模块未声明 `success` ⇒ 注入 `true` | P0-3 |
| 5 | 成功且模块声明 `success=false`（如 `ClickModule`）⇒ **不覆盖** | P0-3 |
| 6 | 跳过注入回归：仍为 `false` | P0-3 |
| 7 | `vflow.logic.jump` 仍读不到 `success`（Signal 分支例外保持） | §6 B6 |
| 8 | `STOP_SILENT` ⇒ 不弹窗，但通知栏/终态/日志照旧（用可注入的 UI 服务断言未调用） | P0-4 |
| 9 | `SKIP + 目标=后置步骤` ⇒ 失败后 `pc` 落在目标步，中间步骤被跳过 | P0-5 |
| 10 | `SKIP + 目标=**前置**步骤` ⇒ 允许；超上限后降级为 `pc++` + W 日志 | §2.5 |
| 11 | **向内跳被拒**（目标 `indentationLevel` > 当前）⇒ 回落 `pc++` | §2.5 |
| 12 | 目标 id 不存在（被删）⇒ 回落 `pc++` + W 日志，不崩 | P0-5 |
| 13 | 向外跳（循环体内 → 循环外）⇒ `loopStack` 深度对齐 | §2.5 |
| 14 | `[[__error]]` 恒注入：正常路径为**空字典**（`不为空` 为 false）；失败跳转时五字段齐全 | §2.6 |
| 15 | **保留键**：`resolveModuleInputDefinitions` 对任意模块都返回全部保留键 | §4.1 |
| 16 | **保留键不丢**（源码扫描型）：两条编辑路径 + 常量表 | §4.2 |
| 17 | 契约一致性：`remapAfterReorder` 与 `remapJumpReferences` 对「变量型目标/越界目标」处理一致 | §6 长期风险 |
| 18 | 静态环检测：A→B→A 能报出环 | §2.5 |
| 19 | `STOP_SILENT` 与既有 `Workflow.silentExecution` **正交**：静默工作流 + 静默终止 ⇒ 无弹窗、无过程通知，但**失败通知仍在** | §2.3 |

> ⚠️ 已知既有失败（与本改动无关）：`VObjectPropertyTest > test VFile properties from absolute path`（`AGENTS.md` 记录）。

---

## §9 实施分期与交接

### 9.1 P0（本设计范围，建议顺序）

| 顺序 | 项 | 验收 | 状态 |
|---|---|---|---|
| 1 | **P0-4 静默终止** | 单测 #8（本仓库无 Robolectric，实际以「纯函数测试 + 接线扫描」替代，见下）；真机：手动跑一条会失败的流程，确认不弹窗但通知栏标失败 | **✅ 已实现**（2026-10-15）：`./gradlew test` 3061 例、仅剩 `AGENTS.md` 记录的既有失败；`assembleRelease` 通过；**真机未验证** |
| 2 | **P0-3 状态可见** | 单测 #4~#7；真机：`IF {{某步.success}} 为假` 能选到、能正确分流 | **✅ 已实现**（2026-10-15）：`./gradlew test` 3074 例、仅剩既有失败；`assembleRelease` 通过；**真机未验证** |
| 3 | **P0-1 策略链** | 单测 #1~#3；真机：`RETRY + 用尽后跳过` 走通 | **✅ 已实现**（2026-10-15）：`./gradlew test` 3091 例、仅剩既有失败；`assembleRelease` 通过；**真机未验证** |
| 4 | **P0-5 跳过跳转** | 单测 #9~#14；真机：失败后跳到收尾步骤并打印 `[[__error.message]]` | **✅ 已实现**（2026-10-15）：`./gradlew test` 3126 例、仅剩既有失败；`assembleRelease` 通过（签名已核对）；**真机未验证** |

**每项独立提交**（便于回滚与单独验证）；集成点（§4.1 / §4.2）随 P0-1 一起做。

#### P0-4 实现记录（2026-10-15）

| 项 | 内容 |
|---|---|
| 判断落点 | 抽成**纯函数** `ActionStepExecutionSettings.shouldShowErrorDialog(policy, showErrorDialog)`（`= showErrorDialog && policy != POLICY_STOP_SILENT`）—— 本仓库无 Robolectric，`WorkflowExecutor` 起不来，判断必须可纯 JVM 单测 |
| 执行器 | `WorkflowExecutor.kt` 失败分支的 `if (showErrorDialog)` → `if (ActionStepExecutionSettings.shouldShowErrorDialog(executionSettings.policy, showErrorDialog))`（**只此一处**） |
| 编辑器 | `ActionEditorSheet.buildErrorHandlingUi()` 新增第 4 个 radio。⚠️ **每新增一种策略都必须同时加 radio**：恢复选中态用的 `when` 有 `else -> rbStop` 兜底，漏加会把存量步骤里已存的值**静默改成 STOP**（已写进该函数的 KDoc） |
| 文案 | `editor_error_policy_stop_silent` 三语（中/英/日） |
| 测试 | `ActionStepExecutionSettingsTest`（6 例：常量互异 / 抑制 / 反向不抑制 / 总开关优先 / 读回 / 缺键兼容）+ `StopSilentPolicyWiringTest`（5 例源码扫描：执行器调用点、编辑器 radio 与文案、三语资源、常量与函数位置，以及**自动枚举守卫** —— 从源码解析出所有 `POLICY_` 常量并逐个要求编辑器有对应选项，防"新增策略忘加 radio ⇒ 存量值被 `when` 的 `else` 分支静默改回 STOP"） |
| ⚠️ **既有守卫被触发并更新** | `ui/chat/AgentErrorDialogWiringTest` 的 `executor guards the error dialog behind the flag` 原本断言裸 `if (showErrorDialog)` ⇒ 本次改动**当场变红**。已升级为断言 `shouldShowErrorDialog(executionSettings.policy, showErrorDialog)`（比原来更强：顺带锁住了策略那一半）。**这正是该测试存在的意义** —— 它是 fork 为「Agent 调试不弹窗」这条接线设的守卫，形态变更必须显式更新而不是绕过 |
| 全局默认策略（P1，**已随 P0-3 同批补上**） | 设置页 `SettingsScreen` 的选项由 3 个补成 4 个 ⇒ 现在可以把「静默停止」设为**新步骤的默认策略**。数据层本来就通（`applyDefaultExecutionSettings` 只读 prefs 字符串、不校验取值），缺的只是这个选项。⚠️ 顺带修掉一个静默陷阱：`checked = localPolicy == value` ⇒ 选项不全时该值会显示为「三个都没选中」，用户随手一点就把全局默认覆盖掉（已由第 4 项解决，并被 `StopSilentPolicyWiringTest` 的自动枚举守卫覆盖） |

#### P0-3 实现记录（2026-10-15）

| 项 | 内容 |
|---|---|
| 供给层 | **新增** `core/module/StepStatusSupport.kt`（fork 独有）：`SUCCESS_OUTPUT_ID` / `successOutputDefinition()` / `withSuccessOutput(declared)`（去重）/ `withSuccessFlag(outputs)`（补 true、**不覆盖**模块自己给的 false）。纯函数 ⇒ 可纯 JVM 单测 |
| 模块声明 | `BaseModule.getDynamicOutputs` 默认实现 → `StepStatusSupport.withSuccessOutput(getOutputs(step))`（1 行） |
| 成功路径 | `WorkflowExecutor` 成功分支 → `StepStatusSupport.withSuccessFlag(result.outputs)`（原 `result.outputs`） |
| ⚠️ **AI 侧口径** | 走查发现 **AI 读的是 `getOutputs` 而不是 `getDynamicOutputs`**（`ChatAgentModuleExecutor.kt:2819` 拼 `outputs:` 段）⇒ 只改 `getDynamicOutputs` 会造成「人工能选、AI 看不见」的口径分裂。故那一处也显式接了同一个供给层（1 行 + import）。**设计稿 §2.2 原先写「AI catalog 也共用该口径」是错的**（它不读 outputs），本记录更正 |
| 文案 | `output_common_step_success_name`（「是否成功」，与 73 个自行声明 `success` 的模块同文案）三语 |
| 已知副作用 | 空输出表也会被补成 `{success: true}` ⇒ **每个成功步骤都会写 `stepOutputs`**。连带：`vflow.data.log` 的「裸写步骤 id ⇒ 打印整表」特性（要求该 step 在 `stepOutputs` 里）现在对所有步骤生效 —— 打印 `{success=true}` 比原来"什么都不打"更有信息量，故接受 |
| 被守卫抓到 | `InspectTypeModuleTest > dynamic outputs follow the conditional outputs`（断言 `getDynamicOutputs` 与 `getOutputs` **逐字相等**）当场变红 ⇒ 已按新语义升级为「静态输出全部在前 + 尾部恰好多一项 `success`」（比原来更强） |
| 测试 | `StepStatusSupportTest`（8 例纯函数：id 字面量 / 类型 / 补项 / 去重 / 空表 / 不覆盖 / 无输出模块）+ `StepStatusWiringTest`（4 例源码扫描：三个调用点 + 三语文案） |
| 编辑器文案调整 | 策略名按用户要求改为**「静默停止工作流」**（编辑器 radio：「执行失败时：静默停止工作流」；设置页短标签：「静默停止」） |

#### 集成点 §4.1 提前完成（2026-10-15）

用户要求「先做让 AI 可以自己配策略」⇒ 把原本排在 P0-1 一起做的 §4.1 提前做掉（详见 §4.1 的 ✅ 段）。影响：

- **P0-1 / P0-5 落地时不用再动这一处**，但**必须在 `stepExecutionSettingInputDefinitions()` 里补新键** —— `ReservedStepKeyInjectionTest` 的自动枚举守卫会强制这一点；
- 真机验证从「需要人工在编辑器里配策略」变成「**Agent 可自测**」（临时工作流的步骤也能带 `__error_policy`）。

#### P0-1 实现记录（2026-10-15）

| 项 | 内容 |
|---|---|
| 策略链落点 | `ActionStepExecutionSettings.effectivePolicy(policy, onRetryExhausted)`（**纯函数**）+ `normalizeOnRetryExhausted`。执行器里只多一个 `val effectivePolicy`，**结果处理**（跳过分支 + 弹窗门禁）改用它，重试 `while` 仍看原始 `errorPolicy` |
| 缺省兼容 | `__on_retry_exhausted` 缺省 = `STOP` ⇒ 与改动前逐字相同（原来重试耗尽必然落「终止 + 弹窗」）。由 `ActionStepExecutionSettingsTest` 的 `缺省 onRetryExhausted 等于今天的行为` 锁死 |
| 非法值 | 收敛到 `STOP`（**不是**抛异常、也**不是** `SKIP`）—— 工作流参数来自 JSON，旧版本 / 手改过的文件必须能跑；且失败可见性只能往**更可见**的方向退化 |
| `RETRY` 不在取值里 | 重试用尽后再重试没有意义，且会把策略链绕成无限循环。由 `用尽后动作的取值里没有 RETRY` 锁死 |
| 编辑器 | `ActionEditorSheet` 的 retryContainer 里加「重试次数用尽后」下拉（`ON_RETRY_EXHAUSTED_CHOICES` + `optionsStringRes`）；`ActionEditorExecutionSettings.setExecutionSettings` 只在 `RETRY` 时写该键、其余策略下 `remove` |
| AI 侧 | `stepExecutionSettingInputDefinitions()` 补 `__on_retry_exhausted`（ENUM，取值 = `ON_RETRY_EXHAUSTED_CHOICES`）；`update_workflow` 描述补说明 |
| 测试 | `ActionStepExecutionSettingsTest` +7 例；新增 `RetryExhaustedPolicyWiringTest`（5 例源码扫描：有效策略两处接线 / **反向**断言重试循环没被污染 / 编辑器写回与清理 / **下拉文案顺序与常量表一一对应** / 三语文案） |
| ⚠️ **既有守卫被触发并更新** | `StopSilentPolicyWiringTest` 与 `AgentErrorDialogWiringTest` 都断言弹窗门禁的实参是 `executionSettings.policy` ⇒ 本次改成 `effectivePolicy` 后**当场变红**。两处均按新语义升级（比原来更强：顺带锁住了策略链那一半），并在注释里写明这是**第二次**形态变更 |

#### P0-5 实现记录（2026-10-15）

| 项 | 内容 |
|---|---|
| 纯函数层 | **新增** `core/workflow/JumpTargetSupport.kt`：`targetIdOf` / `resolveTargetIndex` / `isAllowedTarget` / `enclosingLoopCount` / `alignLoopStack` / `detectCycles` + 两个阈值常量。块结构通过**可注入的 `ShapeResolver`** 获取（生产读 `ModuleRegistry`，测试注入假表）—— 本仓库无 Robolectric，不注入就没法单测 |
| 错误上下文 | **新增** `core/workflow/ErrorContext.kt`（`[[__error]]` 字典 + `EMPTY`）；执行器**在 `executeWorkflowInternal` 开头恒注入空字典**（放在这里而不是 `execute()`：子工作流也走这条路，放外面会让子工作流缺键 ⇒ 解析成字面量） |
| 执行器 | SKIP 分支写错误上下文 → 解析目标 → 合法性校验 → 单步上限 → 跳转（含 `alignLoopStack`）。四条分支里**三条降级为 `pc++` 且都打可见日志**（未设置目标 / 目标不可用 / 超单步上限） |
| 总预算 | `jumpCountTotal >= MAX_JUMPS_PER_EXECUTION` ⇒ **不再跳过**，改走 STOP 分支终止（`stopReason` 把通知栏与弹窗文案换成「疑似死循环」）。见 D13 |
| ⚠️ 踩到的坑 | `findEnclosingLoopStart` 必须从 `position - 1` 起搜（循环状态是开始步骤**执行时**才压栈）—— 差这一格会让「跳回自己的循环开头」多压一份状态。已由 `跳到自己的循环开头会先弹掉旧状态` 锁死 |
| 编辑器 | 「失败时跳转到」下拉（`options` = step id，`displayOptions` = `#序号 模块名`）；候选用**与运行期同一把尺**过滤；**存量目标不在候选里时原样保留**（否则一保存就静默清掉）；可见性跟随**有效策略**；保存时 `detectCycles` ⇒ toast 警告（不阻止保存） |
| 变量选择器 | `WorkflowEditorMagicVariableCatalogBuilder` 新增「错误上下文」固定分组（一个 `[[__error]]` 条目 + `dictionaryKeys` 五个字段），**仅当工作流里真的存在失败跳转时**出现 |
| AI 侧 | `stepExecutionSettingInputDefinitions()` 补 `__skip_jump_target`（STRING，hint 里写明「是 step id 不是序号」「只能 SKIP 用」「不能跳进块体」「目标步骤可用 `[[__error]]` 读错误」）；`update_workflow` 描述同步 |
| 测试 | `JumpTargetSupportTest`（18 例纯函数：解析 / 四类合法性 / 嵌套深度 8 个位置 / `loopStack` 对齐 4 例 / 环检测 5 例）+ `ErrorContextTest`（7 例，含**实跑 `ConditionEvaluator`** 验证「为空/不为空」真的可用）+ `SkipJumpWiringTest`（7 例源码扫描，含**跨文件**断言「跳过日志行与 `ChatMessagePatch` 锚点逐字一致」）+ `ActionStepExecutionSettingsTest` +3 例 |
| 未做项（登记） | ① 「卡片标红」的环警告（只做了 toast）；② `PickerType.STEP` 选择器类型；③ 目标被删时的**引用重映射**（现降级为 `pc++` + W 日志；`WorkflowPatch.remapJumpReferences` 只认 `vflow.logic.jump` 的序号字段）—— 与 §9.2 的 B1 一起做更合适 |
| 验证 | `./gradlew test` **3126 例**，仅剩 `AGENTS.md` 记录的既有失败；`./gradlew assembleRelease` 通过，`apksigner` 显示 `CN=vFlow Fork, O=nightking8342`。**真机未验证** |

### 9.2 P0 之后的既有缺陷修复（单独立项）

- 修 `vflow.logic.jump` 的 **B1 / B2 / B3 / B4**（复用 `JumpTargetSupport`）—— 与新功能**分开提交**，但**设计一次做完**（工具函数的形状要同时满足两边）。
- 补 **B7** 的测试。

### 9.3 仅登记（P1 / P2）

| 项 | 说明 |
|---|---|
| **P1-1 工作流级错误处理器** | 复用 §2.6 的 `[[__error]]`；触发方式与主流程的关系（是否中断）需另做设计 |
| **P1-2 步骤超时** | `__timeout_ms`；对「等界面出现」比重试更有效 |
| **P1-3 步骤级 fallback 分支** | P0-5 的完整版（图形化出边） |
| **P2-1 错误分类** | `Failure` 加 `kind`（TRANSIENT/PERMANENT/CONFIG/CANCELLED），或先做 `errorTitle → kind` 映射表（survey §4.0：76 个自由文本标题） |
| **P2-2 幂等标记** | 模块 metadata 加 `isIdempotent`；**副作用类默认不允许自动重试**（survey 记录：`device.click` / `interaction.input_text` / `network.*_push` 都在可重试集合里） |
| **P2-3 补偿步骤（Saga 简化版）** | 失败时执行登记的清理步骤 |

---

## §10 文件归属登记（供 FORK.md）

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `docs/fork/error-handling-extension-design.md`（**新增**） | fork 独有：异常处理策略扩展设计（4 项 P0 + 9 条定案 + 5 条否决 + 3 个集成点 + jump 7 条缺陷 + 18 例测试清单） | 我方 |
| `docs/fork/surveys/module-error-status-survey.md`（改） | §7 扩展落点改为指向本文档 | **手动合并**（追加） |
| `core/workflow/JumpTargetSupport.kt`（**新增**，P0-5 时） | fork 独有：跳转目标纯函数层（`resolveTargetIndex` / `isAllowedTarget` / `enclosingLoopCount` / `detectCycles`） | 我方 |
| `core/workflow/ErrorContext.kt`（**新增**，P0-5 时） | fork 独有：`[[__error]]` 错误上下文（常量 + 构造器 + `EMPTY`）—— 步骤级跳转与 P1 工作流级错误处理器**共用** | 我方 |
| `core/execution/WorkflowExecutor.kt`（改，P0 时） | 成功路径注入 `success`、有效策略链、静默判断、跳过跳转 + `loopStack` 对齐 + 三层防护 | **手动合并**（上游热路径） |
| `core/module/BaseModule.kt`（改，P0 时） | `getDynamicOutputs` 默认实现追加合成 `success`（去重） | **手动合并** |
| `core/workflow/model/ActionStepExecutionSettings.kt`（改，P0 时） | 新增 `POLICY_STOP_SILENT` / `KEY_ON_RETRY_EXHAUSTED` / `KEY_SKIP_JUMP_TARGET` | **手动合并** |
| `ui/workflow_editor/ActionEditorSheet.kt`（改，P0 时） | 策略 4 选 1 + 「用尽后」下拉 + 「跳到步骤」选择器 | **手动合并** |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt`（改，P0 时） | 注入 `[[__error]]` 固定分组（仅存在失败跳转时） | **手动合并**（追加） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改，P0 时） | `resolveModuleInputDefinitions` 注入全部保留键 | **手动合并**（追加） |
| `core/workflow/WorkflowJumpReferenceUpdater.kt` | ⚠️ **本设计不改它**（保持「暂未分歧」）；B1 走「编辑器调用点切到 fork 版」的甲方案 | （暂未分歧） |

---

## §11 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.4 | 2026-10-15 | **P0-1 策略链 + P0-5 跳过跳转已实现**（§9.1 实现记录）⇒ **四项 P0 全部落地**。新增两个 fork 文件（`core/workflow/JumpTargetSupport.kt`、`core/workflow/ErrorContext.kt`）+ 9 条新决策（D10~D14 等）；回填三处实现期更正：① 「不是块中间/结束」≠「必须是 NONE」（块**开始**可以当目标）；② `enclosingLoopCount` 必须从 `position - 1` 起搜；③ 编辑器用**下拉**而不是新 `PickerType.STEP`。⚠️ 触发并更新了 `StopSilentPolicyWiringTest` / `AgentErrorDialogWiringTest` / `RetryExhaustedPolicyWiringTest` 三处既有守卫断言 |
| v1.3 | 2026-10-15 | **集成点 §4.1 提前完成**：AI 侧现在认识 `__error_policy` / `__retry_count` / `__retry_interval`（对**所有**模块注入、`isHidden = true`、`update_workflow` 描述补说明）⇒ 模型能自己配策略、也能给临时工作流步骤配策略。新增 `ReservedStepKeyInjectionTest`（4 例，含两条自动枚举守卫） |
| v1.2 | 2026-10-15 | **P0-3 状态可见已实现**（§9.1 实现记录）+ **设置页全局默认策略补第 4 项**（原 P1，随本批一起做掉）。新增 fork 文件 `core/module/StepStatusSupport.kt`；`BaseModule` / `WorkflowExecutor` / `ChatAgentModuleExecutor` 三处接线；策略名按用户要求改为**「静默停止工作流」**。⚠️ 更正本稿一处事实错误：AI 读的是 `getOutputs` 而非 `getDynamicOutputs`；⚠️ 被守卫抓到 `InspectTypeModuleTest` 的「逐字相等」断言并已按新语义升级 |
| v1.1 | 2026-10-15 | **P0-4 静默终止已实现**（见 §9.1 实现记录）：纯函数 `shouldShowErrorDialog` + 执行器 1 处调用点 + 编辑器第 4 个 radio + 三语文案 + 11 例测试；`./gradlew test` 3062 例通过（仅剩既有失败）、`assembleRelease` 通过（签名已核对）。⚠️ 触发并更新了既有守卫测试 `AgentErrorDialogWiringTest`（它锁的正是被改的那一行） |
| v1.0 | 2026-10-15 | 首版：4 项 P0 语义定案、3 个集成点、上游用序号的原因分析（含时间线证据）、jump 7 条既有缺陷、9 条定案 + 5 条否决、18 例测试清单、分期与交接 |

**待确认 / 未定**：

- ~~跳转防护的具体阈值~~ ⇒ **已定案**（单步 10 / 执行级 200，落成 `JumpTargetSupport` 的常量）。
- ~~`PickerType.STEP` 的选择器 UI 形态~~ ⇒ **改为不新增该 picker 类型**（用既有下拉，见 D10）；
  `PickerType.STEP` 本身**登记为未做项**。
- **未做项（登记，不阻塞 P0）**：① 环警告的「卡片标红」（现只有 toast）；② 目标被删时的引用重映射
  （现降级为 `pc++` + `W` 日志）—— 建议与 §9.2 的 B1 一起做。
- B1 的甲方案要处理的 `null` 返回语义（目标被删时：提示用户 / 拒绝删除）与编辑器交互细节未细化。
- **真机验证未做**：四项 P0 全部只有单测 + 打包证据。
