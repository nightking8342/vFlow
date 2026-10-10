# 返回值类型推导（函数工作流 / 普通子工作流）设计

> **状态**：**草稿**（仅需求与设计，**未实现**）｜ **日期**：2026-10-11
> **相关**：`function-workflow.md`（函数工作流的原始需求文档，§14.14 是返回值键展开那一批）、
> `quick-settings-tile-design.md`（同款「分阶段 + 静默失效点清单」写法）
> 上游无此文件的对应物（fork 独有，冲突归属我方）。

---

## 0. 这份文档回答什么

三件事，按优先级排列：

1. **【第一需求】调用方要能知道返回值的类型** —— 无论被调方是**函数工作流**（`vflow.logic.call_function`）
   还是**普通子工作流**（`vflow.logic.call_workflow`）。
2. **【锚点】这个类型的唯一来源是「停止并返回」步骤** —— 两者在运行期走的是**同一条路**（见 §2.4）。
3. **【未来】** 字典类型可以**定义数据结构**（键 + 键类型）；有了它之后，
   函数签名里的 `returnDef`（`FunctionSignature.returnDef`）**可能就不再需要**（见 §5）。

⚠️ 本文档**不含实现**。所有「现状」结论都是**代码直读**并附 `file:line`；
所有「设计」都标注了**是否已与需求方确认**。

---

## 1. 需求原文（用户 2026-10-11，逐字）

> 我们目前的第一需求是：无论是函数工作流还是子工作流的返回值，在调用时都可以知道它的类型。
>
> 更进一步，我们可能才需要定义这个返回值的结构。如果需要定义结构，应该也只有字典类型需要吧。
>
> 那么，我们可能需要在"停止并返回"这一个模块做修改，增加一个结构的定义。
> 这样的话，是不是函数工作流的那个返回值定义就不需要了？

同一轮对话中的补充：

> 一个工作流中，可能根据不同条件有多个"停止并返回"步骤。

> 运行期判断断言，然后下方的编辑器就能把它按照对应的类型来使用。

---

## 2. 现状（已核查事实）

### 2.1 数据模型

```kotlin
// core/workflow/model/FunctionSignature.kt
data class ReturnKey(val name: String, val type: String)                              // :35
data class FunctionReturn(val type: String = DICTIONARY, val keys: List<ReturnKey>)   // :48
data class FunctionSignature(val params: List<FunctionParam>, val returnDef: FunctionReturn?)  // :61

// core/workflow/model/Workflow.kt
var functionSignature: FunctionSignature? = null                                      // :51
val isFunction: Boolean get() = functionSignature != null                             // :57
```

⚠️ **`functionSignature` 同时是"返回值声明"和"这是不是函数"的判据**。后者牵连三处：
「调用函数工作流」选择器（`CallFunctionModuleUIProvider.kt:166` `filter { it.isFunction }`）、
卡片上的「函数」徽标（`WorkflowListScreen.kt:876`、`:1758`）、
AI 的 `function` / `regular` 过滤（`ChatAgentModuleExecutor.kt:2604-2605`）。
⇒ **不能拿它给普通子工作流复用**（一加就改变工作流语义）。

### 2.2 类型是怎么推导出来的（**保存时**，且只对函数工作流）

```kotlin
// core/workflow/WorkflowManager.kt:289
private fun aggregateFunctionSignature(steps: List<ActionStep>, existing: FunctionSignature?): FunctionSignature? {
    val defineStep = steps.firstOrNull { it.moduleId == "vflow.logic.define_function" }
        ?: return existing                                  // :290 ← 没有「定义函数」卡片就直接返回
    ...
    val derivedReturn = FunctionSignatureHelper.deriveReturnDef(steps)   // :303
    val returnDef = derivedReturn ?: existing?.returnDef                 // :304 ← 推不出来就沿用旧值
    return FunctionSignature(params = params, returnDef = returnDef)
}
```

⚠️ 两处关键：

1. **`:290` 是"普通工作流没有 returnDef"的真正原因** —— 不是推不出来，是**门控**把整个推导跳过了。
   普通工作流**没有「定义函数」卡片** ⇒ 直接 `return existing`（= `null`）。
2. **`:304` 的 `?: existing?.returnDef`** ⇒ 推导失败时**静默沿用上一次的键**（见 §6 第 2 条）。

### 2.3 `deriveReturnDef` 现在能推到什么程度

```kotlin
// core/workflow/model/FunctionSignatureHelper.kt:24
fun deriveReturnDef(steps: List<ActionStep>): FunctionReturn? {
    val returnStep = steps.firstOrNull { it.moduleId == "vflow.logic.return" } ?: return null   // :26
    val value = returnStep.parameters["value"] ?: return null
    val keys = extractDictionaryKeys(value, steps) ?: return null                               // :29
    return FunctionReturn(type = VTypeRegistry.DICTIONARY.id, keys = keys)                      // :30-33
}
```

| 维度 | 现状 | 位置 |
|---|---|---|
| 只取**第一个**「停止并返回」 | `firstOrNull`，其余静默忽略 | `:26` |
| 命中路径只有两条 | ① 字面量 `Map`；② 整串 `{{stepId.x}}` → 该步 `moduleId == "vflow.variable.create"` 且 `type == "dictionary"` 且初始值是 `Map` | `:44-72` |
| **只认「创建变量」一种模块** | 其它模块（JS / HTTP / 截图…）一律 `null` | `:59` |
| **类型硬编码 `DICTIONARY`** | 成功时永远标字典；失败时整个 `null` | `:30-33` |
| **`outputId` 被丢弃** | `substringBefore(".")` 只取 `stepId`（`{{步骤.outputs}}` 的 `outputs` 扔了） | `:56` |
| 「推类型」与「推键」**未解耦** | 推不出键 ⇒ 整体 `null` ⇒ **"类型已知、键未知"表达不出来** | `:29` |

### 2.4 ⚠️ 关键事实：两个模块的返回值来自**同一个锚点**

```kotlin
// core/workflow/module/logic/StopAndReturnModule.kt:51-52
val returnValue = context.getVariable("value")
return ExecutionResult.Signal(ExecutionSignal.Return(returnValue))

// core/execution/WorkflowExecutor.kt:912-916 —— returnValue 只被这一个分支赋值
is ExecutionSignal.Return -> {
    returnValue = signal.result
    pc = workflow.steps.size            // 立刻结束执行
}
// :925
return returnValue
```

两个调用模块在**执行期完全同路**：

- `CallWorkflowModule.kt:117` → `WorkflowExecutor.executeSubWorkflow(workflowToCall, context)`
- `CallFunctionModule.kt:163` → `WorkflowExecutor.executeSubWorkflow(workflowToCall, context, injectedVariables)`

⇒ **普通子工作流的 `result` 也来自「停止并返回」**；没有那个步骤时它是 **`null`**
（`returnValue` 初值），**不是**"最后一步的值"。
⇒ 所以"推导返回值类型"这件事，**两个模块可以共用同一份实现**。

### 2.5 调用方现在看到什么

| 模块 | 输出 | 静态类型 | 位置 |
|---|---|---|---|
| `call_function` | `result` | `returnDef?.type ?: ANY`，并携带 `returnDef.keys` | `CallFunctionModule.kt:69-84` |
| `call_workflow` | `result` | **硬编码 `ANY`** | `CallWorkflowModule.kt:41-43`、`:60-64` |
| `call_workflow` | `变量: xxx`（子工作流里每个「创建变量」的命名变量） | 由该步 `type` 映射（`resolveNamedVariableType`） | `CallWorkflowModule.kt:49-84` |

⚠️ `call_workflow` 的 `getDynamicOutputs` **只读 `type` / `variableName`，不读 `value`**
⇒ 命名变量的字典键一个都没拿。

### 2.6 选择器（魔法变量）的判定

```kotlin
// ui/workflow_editor/MagicVariablePickerSheet.kt:318
if (properties.isEmpty() && item.dictionaryKeys.isEmpty()) {
    dispatchSelection(item)      // ← 直接插入 {{步骤.result}}，连属性导航页都进不去
}
```

- `ANY` 的 `properties` 是**空列表**（`VTypeRegistry.kt:20`，`SimpleVType` 默认 `emptyList()`）
  ⇒ **`ANY` 点不开任何属性**。
- 声明的键渲染在「函数返回值」分组（`:467-492`，文案 `magic_variable_section_declared_keys`）。
- `OutputDefinition.dictionaryKeys: List<OutputKeyDefinition>`（`core/module/definitions.kt:555/579`）
  —— **机制已经存在**。

### 2.7 类型引擎已有的属性（**不用新做**）

| 类型 | 已注册的属性 | 位置 |
|---|---|---|
| 图片 | 宽度 / 高度 / 路径 / URI / 大小 / 文件名 / Base64 | `VImage.kt:79-109` |
| 文件 | 路径 / URI / 文件名 / 扩展名 / 大小 / MIME / Base64 / 内容 | `VFile.kt:30-60` |
| 列表 | 数量 / 第一个 / 最后一个 / 是否为空 / 随机一项 / 可用索引 + 动态「选择列表索引值」 | `VList.kt:84-106` |
| 字典 | 数量 / 键 / 值 + 动态「选择字典键」 | `VDictionary.kt` |

⇒ **类型推对了，选择器零改动就会展开这些属性。**

### 2.8 「停止并返回」步骤本身

```kotlin
// core/workflow/module/logic/StopAndReturnModule.kt:27-36
InputDefinition(id = "value", staticType = ParameterType.ANY,
    acceptsMagicVariable = true, acceptsNamedVariable = true)   // ⚠️ 没有 supportsRichText
```

⚠️ 该模块**没有 uiProvider**，`supportsRichText` 未设 ⇒ 自动表单落到**普通文本框**
（`StandardControlFactory.kt:161`），**魔法变量不渲染成胶囊** —— 这违反 AGENTS.md 那条规则。
改这个模块时要顺手补上。

⚠️ `StopAndReturnModule.kt` 是**上游文件**（`FORK.md` 未登记过），改它属于小改动但要登记。

### 2.9 ⚠️ 脚本模块的返回值形态边界（**影响 `return_type` 的必要性判断**）

三个环节都堵死 ⇒ **JS / Lua 的返回值永远只可能是「字典 / 列表 / 字符串 / 数字 / 布尔」的嵌套组合**，
**不可能是图片 / 文件 / 坐标 / 日期**：

```kotlin
// ① 进：脚本拿到的是**拆箱后**的值
//   core/execution/JsValueConverter.kt:19-103 coerceToJs
//   只对 VString/VNumber/VBoolean/VList/VDictionary/VScreenElement/VCoordinate 有专门分支，
//   VImage/VFile/VDate/VTime 一律走 else -> value.toString()
//   而 core/types/BaseVObject.kt:9  override fun toString() = asString()
//   ⇒ 图片进脚本时**已经变成 URI 字符串**，类型信息在脚本侧就丢了

// ② 出：只认基础类型与集合，其它一律字符串化
//   JsValueConverter.kt:108-171 coerceToKotlin
//   NativeArray → List、NativeObject / ScriptableObject → Map、
//   NativeJavaObject → unwrap 后递归（Java 对象仍会落到 else -> toString()）

// ③ 包装：core/workflow/module/system/JsModule.kt:116-117
//   resultTable.mapValues { VObjectFactory.from(it.value) }
//   ⇒ 只可能是 VString / VNumber / VBoolean / VList / VDictionary
```

⚠️ **两个层次别混**（容易误读）：

| 层次 | 静态类型 | 依据 |
|---|---|---|
| 模块输出 `outputs` 本身 | **字典** | `JsModule.kt:68-70`（Lua / Xposed JS 同形） |
| `outputs` **里某个键的值** | **ANY** | 用户走「选择字典键」手输，手输生成的新 item 类型**硬编码为 ANY**（`MagicVariablePickerSheet.kt:565-569`） |

⚠️⚠️ **由此推出一条重要结论**：**"值已经是图片、但静态推不出来"这个场景几乎不存在** ——
图片 / 文件只能由**声明了 IMAGE / FILE 的模块**产出（截图 `CaptureScreenModule.kt:110`、
文件操作 `FileOperationModule.kt:241`、导入图片 `ImportImageModule.kt:41`…），
而那些声明是**准的**。只要 §3 第一步（查输出定义推类型）做出来，图片 / 文件就都能推对。

⇒ 剩下的"静态 `ANY`"场景，运行期值只能是**基本类型之一**（字典 / 列表 / 字符串 / 数字 / 布尔）
—— 而那用**「创建变量」**就能做，且对字典 / 列表是**保真**的
（`CreateVariableModule.kt:335` `is VDictionary -> value`）。
**这是 §3.3 里"`return_type` 手动指定建议不做"的主要依据。**

---

## 3. 设计（分阶段）

### 第一步：**类型推导**（零模型改动，覆盖第一需求）

把 `deriveReturnDef` 的判据从"这一步是不是「创建变量」"改成"**这一步声明的那个输出是什么类型**"：

```kotlin
val outputDef = ModuleRegistry.getModule(step.moduleId)
    ?.getDynamicOutputs(step, allSteps)
    ?.firstOrNull { it.id == outputId }          // ← outputId 现在被 substringBefore(".") 丢了
val typeName = outputDef?.typeName ?: VTypeRegistry.ANY.id
```

覆盖效果（全部是**模块自己声明好的类型**，不是猜的）：

| 「停止并返回」的 `value` | 那一步声明的类型 | 来源 |
|---|---|---|
| `{{js步骤.outputs}}` | **字典** | `JsModule.kt:68-70`（Lua `LuaModule.kt:63-65`、Xposed JS `XposedJsModule.kt:239` 逐字同形） |
| `{{HTTP步骤.response_headers}}` | **字典** | `HttpRequestModule.kt:132` |
| `{{截图步骤.image}}` | **图片** | `CaptureScreenModule.kt:110` |
| `{{文件操作步骤.file}}` | **文件** | `FileOperationModule.kt:241` |
| `{{HTTP步骤.status_code}}` | **数字** | `HttpRequestModule.kt:131` |

配套两件**必须一起做**的事：

1. **解耦"推类型"与"推键"** —— 类型推出来就返回
   `FunctionReturn(type = 查到的类型, keys = 推得出来就给、推不出来就空)`；
   类型也推不出来才 `null`。否则"类型已知、键未知"这个状态表达不出来。
2. **两个模块共用同一条推导** —— `CallFunctionModule.getOutputs` 已经是
   `returnDef?.type ?: ANY`（**不用改**），但 `CallWorkflowModule.getDynamicOutputs`
   的 `result` 要接上（现在硬编码 `ANY`）。

### 第二步：**多「停止并返回」的合并规则**（✅ 已定案）

`workflow.steps` 是**扁平列表 + `indentationLevel`** ⇒
`filter { it.moduleId == "vflow.logic.return" }` 天然覆盖分支/循环里的所有返回点
（**不需要控制流分析**）。

```kotlin
val returns = steps.filter { it.moduleId == "vflow.logic.return" }

// ① 类型：逐点求，全部一致才认
val types = returns.map { resolveType(it.parameters["value"]) }.distinct()
val typeName = types.singleOrNull() ?: VTypeRegistry.ANY.id

// ② 键：类型一致为字典时取并集
val keys = returns.flatMap { resolveKeys(it) }.distinctBy { it.name }
```

| 各分支返回类型 | 结果 | 理由 |
|---|---|---|
| 全一致 | 该类型 | 最常见 |
| 有分歧 | **`ANY`** | 给错类型比给 `ANY` **更糟**（选择器会展开**错误的属性集**） |
| 含未知（`ANY`） | **`ANY`**（保守） | 与本仓库既有取向一致（`filterByFolderTab` / `TileGate` 都不做兜底） |

✅ **已定案（用户 2026-10-11）**：

1. 类型**有分歧 ⇒ 退 `ANY`**（不给"最像主路径"的那个 —— 给错类型比给 `ANY` 更糟）；
2. 键取**并集**；
3. `ANY` 分支**参与合并**（即含未知 ⇒ 结果 `ANY`，保守）。

并集的依据：`VDictionary.getProperty` 对缺键**就返回 `VNull`**（`VDictionary.kt:45-59`），
失败模式温和且是既定语义；交集会让"if 成功返回 `data` / else 返回 `msg`"这种最常见形态
只剩 `code`，功能几乎没用。

### 第三步：**在「停止并返回」上声明字典的键**（✅ 已定案）

✅ **已定案（用户 2026-10-11）**：

1. **只加一个参数 `return_keys`**（键值编辑器，复用 `DictionaryKVAdapter`，**可留空**）；
2. **不加 `return_type`** —— **不支持手动指定类型**（理由见 §3.3.1）；
3. 结构定义**只给字典**（图片/文件/列表等类型的属性由类型引擎固定提供，用户没什么可声明的）；
4. **类型只有推导一个来源**（用户改不了），`return_keys` **只在该 return 点的 `value`
   推导出字典时**才显示（§3.3.4）。

⚠️ 声明是**逐点覆盖**，不是"必须全部填" —— 多个 return 各自可声明，不声明就止步于类型。

⚠️ 与 `returnDef` 的关系：**键搬到步骤上之后，`FunctionReturn` 的两个字段都失去了来源**
（`type` 改现场推导、`keys` 改步骤声明）⇒ 见 §5 与 §3.3.5。

#### 3.3.1 为什么**不做** `return_type` 手动指定（✅ 已定案：不做）

> 这一节保留完整论证，作为"为什么不做"的记录 —— 将来若有人想加，先读这里。

**Q1：手动指定有场景吗？** ⇒ 只有"值**已经是**目标类型，但**静态推不出来**"这一个，
而它**在实践中几乎不存在**（见 §2.9 的完整论证）：

⚠️⚠️ **而这个场景在实践中几乎不存在**（见 §2.9 的完整论证）：

- 图片 / 文件 / 坐标这些类型，**只能由声明了该类型的模块产出**（截图 / 文件操作 / 导入图片…），
  而那些声明是**准的** ⇒ 只要 §3 第一步做出来，它们都能推对；
- 脚本模块（JS / Lua）**永远产不出**这些类型（只能产出字典 / 列表 / 字符串 / 数字 / 布尔）；
- 剩下的"静态 `ANY`"场景（`{{js步骤.outputs.xxx}}`、`{{解析JSON步骤.first_value}}`），
  运行期值只能是**基本类型之一** ⇒ 用**「创建变量」**就能做，且对字典 / 列表**保真**
  （`CreateVariableModule.kt:335` `is VDictionary -> value`）。

⚠️ 它**也不能**解决"值不是目标类型"（那需要**转换**，见 `type-inspection-module-design.md` §4.2）。
而"值是字符串路径、要当图片用"恰恰是**转换**，不是断言 —— 也是「创建变量(图片)」在做的事。

**Q2：若做了，手动指定"图片"而实际值是"字典"怎么办？** 三个选项（都不理想）：

| 方案 | 运行期表现 | sound？ | 代价 |
|---|---|---|---|
| **A. 只声明、不校验** | 引用 `{{result.width}}` ⇒ `VDictionary.getProperty("width")` ⇒ **静默 `VNull`**（`VDictionary.kt:45-59`） | ❌ **说谎** | 零成本 |
| **B. 声明 + 校验 + `Failure`** | 主工作流：可见的错误弹窗 + 状态 `Failure`（`WorkflowExecutor.kt:800-848`） | ⚠️ **半 sound** | 见 §3.3.2 的子工作流约束 |
| **C. B + 可配策略**（默认中断，可切"仅日志"） | 同 B | ⚠️ 同 B | 多一个参数 |

⚠️⚠️ **A 比 `ANY` 更糟**：编辑器承诺"是图片"，用户就会放心写 `{{x.width}}`，运行期静默拿 `VNull`。
这与 `type-inspection-module-design.md` §4.1 的 soundness 结论是同一件事 —— 只不过这里的
"断言"是**落盘**的，会跟着工作流导出/备份/被别人打开，**谎言会被传播**。

#### 3.3.2 ⚠️ 关键约束：**子工作流里的失败不会中断父工作流**

这条是"失败即中断"（方案 B/C）能不能用的**前提**，必须在实现前知道：

```kotlin
// core/execution/WorkflowExecutor.kt:765-850 —— 模块 Failure + 默认 STOP 策略
is ExecutionResult.Failure -> {
    ...
    ExecutionStateBus.postState(ExecutionState.Failure(...))   // :841-848
    return null                                               // :849 ← 不抛异常
}

// :487-492 —— executeSubWorkflow 传给内部的是**子工作流自己的 id**
val returnValue = executeWorkflowInternal(
    workflow, subWorkflowContext,
    workflow.id,                 // :490 ← 子工作流的 id
    isSubWorkflow = true
)
```

⇒ 子工作流里的模块失败：`executeWorkflowInternal` **`return null`** ⇒
`SubWorkflowResult.returnValue = null` ⇒ 父工作流的 `call_workflow` / `call_function`
**拿到 `null` 并继续执行**（父工作流本身**不会**失败）。

⚠️ 失败标记 `failedExecutions[...]` 用的是**子工作流的 id**（`:490`），而顶层
`execute()` 判的是**顶层**那个 id ⇒ 顶层的"这次到底成没成"判据**看不到子工作流的失败**
（`failedExecutions` 的用途见 `:349-364` 的注释）。

**结论**：**"失败即中断"在子工作流场景下不彻底** —— 它会报错（`showErrorDialog` 默认为
`true`，`:810`），但**父工作流继续跑**。所以：

- 方案 B/C 在主工作流里是可靠的；
- 在**子工作流**里只是"报了错但没停" —— 而"被调用"恰恰是本文档的主场景。

⇒ **要么接受这个局限并写进文案，要么先把"子工作流失败传播"单独修好**
（那是影响**所有模块**的更大改动，需单独评估 + 真机验证）。

#### 3.3.3 取舍结论（✅ 已定案 2026-10-11）

| 做法 | 结论 |
|---|---|
| **只做 `return_keys`（字典的键）** | ✅ **做**。它是**纯静态提示**：键写错了运行期就是 `VNull`（与手输键同款失败模式），**不会说谎**，而且**正好解决 JS 返回字典点不开键**这个实际痛点 |
| `return_type` 手动指定 | ✅ **不做**。它唯一的场景（值已是目标类型但推不出来）**几乎不存在**（§2.9），剩下的用「创建变量」也能解决；且**不引入新的运行期行为**、不牵扯 §3.3.2 |
| ~~只当提示（方案 A）~~ / ~~校验（方案 B/C）~~ | 随"不做"一并作废（论证保留在 §3.3.1，供将来复查） |

#### 3.3.4 `return_keys` 的显示判据与读取侧兜底（**必须成对做**）

**显示判据**：`return_keys` 输入区**只在"该 return 点的 `value` 推导出来是字典"时显示**
（用户 2026-10-11 提出，本文档采纳）。

⚠️ **它依赖 §3 第一步先落地** —— 现在 `deriveReturnDef` 只认「创建变量」
（`FunctionSignatureHelper.kt:59`），`{{js步骤.outputs}}`（明明是字典）也推不出来
⇒ 输入区**永远不显示**，而它恰恰是最需要的场景。**顺序不能颠倒。**

| `value` | 推导类型 | `return_keys` 有入口吗 |
|---|---|---|
| `{{js步骤.outputs}}` | **字典** | ✅ 有（**主要场景**） |
| `{{截图步骤.image}}` | 图片 | ❌ 无（正确） |
| `{{js步骤.outputs.result}}`（手输字典键） | **ANY** | ❌ **无** |
| 循环里累积的值 | **ANY** | ❌ 无 |

⚠️ **`ANY` 的场景没有入口**（✅ 已定案：不做 `return_type` 手动指定 ⇒ 没有"手动选字典"这条路）：
它的运行期值只能是基本类型之一（见 §2.9），用「创建变量(字典)」更合适且**保真**；
为 `ANY` 开入口会引入下面那个假信息风险。**这是有意的取舍，不是遗漏。**

⚠️⚠️ **读取侧必须兜底**（少它就会出现"选择器列出了运行期不存在的键"）：

```kotlin
// MagicVariablePickerSheet.kt:470-492 —— declaredKeyRows **无条件渲染**，不检查类型
val declaredKeyRows = item.dictionaryKeys.filter { ... }.map { ... }
if (declaredKeyRows.isNotEmpty()) { rows += Header(...); rows += declaredKeyRows }
```

⇒ 用户先填字典并声明了键、**后来把 `value` 改成 `{{截图步骤.image}}`** ⇒ 类型变图片，
但 `return_keys` 还留在参数里 ⇒ 导航页会**真的列出**那些键，点选后运行期取到 `VNull`。

**两处都要做**：

1. **UI**：只有该 return 点推导出来是字典时才显示 `return_keys`；
2. **读取侧（防御）**：`CallFunctionModule.getOutputs` / `CallWorkflowModule.getDynamicOutputs`
   里**只在 `typeName == DICTIONARY` 时才把 keys 填进 `dictionaryKeys`**。

**另外两个细节**：

- `return_keys` **必须可留空** —— 自动推导（字面量字典 / 创建变量步骤）优先，推不出来才用手填的；
- **多 return 的交互**：显示判据按**该 return 点自己的类型**，而合并规则不变
  （分歧 ⇒ `ANY` ⇒ 调用方**看不到**键）⇒ 会出现"我填了键、调用方却没显示"的情况。
  这是**正确但反直觉**的，建议将来加一条 UI 提示
  （"本工作流有多个返回值且类型不一致，调用方按「任意」处理"）。

#### 3.3.5 键的来源：**只靠声明**（✅ 已定案 2026-10-11）

用户决定：**删掉"推导键"的逻辑，键完全依赖「停止并返回」步骤的声明；不声明就止步于类型。**

⚠️ **先确认这件事的范围**（全链路唯一，已核对）：

```
FunctionSignatureHelper.extractDictionaryKeys (:44)   ← 唯一实现
  ↑ 唯一调用者 deriveReturnDef (:24)
    ↑ 唯一调用者 WorkflowManager.aggregateFunctionSignature (:303)
      → functionSignature.returnDef.keys
        → CallFunctionModule.getOutputs (:81)         ← 唯一消费者
```

其他出现 `dictionaryKeys` 的地方都是**空列表**（`ActivityChangedTriggerModule.kt:171`、
`BroadcastTriggerModule.kt:231`）或**透传 / 消费**（`WorkflowEditorMagicVariableCatalogBuilder.kt:241`、
`MagicVariablePickerSheet.kt`）—— **没有任何第二处会产出键**。

**这条决定顺带解决三件事**：

1. ✅ **消掉"推导的键 ∪ 声明的键"这个合并问题** —— 键只有一个来源（原待拍板项 10 作废）；
2. ✅ **让 `returnDef` 彻底失去存在理由** —— 类型改现场推导、键改步骤声明
   ⇒ 与 §5「后续函数工作流的返回值定义就不需要了」的结论一致，且可以**一起做**；
3. ✅ **删掉最脆弱的一段逻辑** —— "追踪创建变量步骤"依赖三个隐式约定
   （`moduleId == "vflow.variable.create"`、`type == "dictionary"`、`value is Map`），
   最容易随上游改动失效。

⚠️ **"字面量字典"那条的实际损失比看上去小**：`value` 是普通文本框（§2.8），
用户手打 `{"code":200}` 存进去是**字符串**（不是 `Map`），根本走不到那条路径 ——
它只在 AI/Agent 生成或导入 JSON 时成立。

⚠️⚠️ **必须一起处理的三件事**：

| # | 问题 | 说明 |
|---|---|---|
| ① | **存量 `returnDef.keys` 会被静默清空** —— ✅ **已定案：接受清空，不写迁移**（用户 2026-10-11）。`WorkflowManager.kt:304` 是 `derivedReturn ?: existing?.returnDef` ⇒ 一旦不再推导键，用户**下次保存**该工作流时旧 keys 就没了，调用方选择器里的「函数返回值」分组**消失且不报错**。不迁移的理由：该功能 2026-09-10 才上线、用的人少；而迁移要解决"多 return 时旧 keys 对应哪个步骤"（**无解**）。⚠️ **这是有意的行为变化，不是 bug**，实现时要在提交信息里写明 |
| ② | **删 `returnDef` 字段牵扯 5 个读取点 + 备份 wire 格式** | 见 §5 —— 顺序仍是"先让 5 处都走现场推导 ⇒ 字段变死数据 ⇒ 再删" |
| ③ | **`FunctionSignatureHelperTest` 的 3 个键用例要删 / 改** | 字面量字典 / 追踪创建变量 / 非字典返回 `null` |

⚠️ **`return_keys` 的显示判据与读取侧兜底仍然成立**（§3.3.4）—— 判据只看**类型**（推出来是字典），
与"键从哪来"无关。

### 第四步（可选）：**`ANY` 的兜底**

选择器里给 `ANY` 的导航页加一组「按类型查看属性」（列出所有有属性的类型，点进去按该类型展开）。
这是**不落盘的手动断言**：只影响编辑器怎么展开，不改运行期的值；断言错 ⇒ 属性访问 `VNull`
（与"手打路径写错"同一个温和失败模式）。落点只在 `MagicVariablePickerSheet`。

---

## 4. 与「运行期判断 + 断言」方案的关系（**重要，避免误做**）

需求方提出过一条思路：**运行期判断类型 → 在真分支内用「创建变量」断言 → 下方编辑器按该类型使用**。

**结论：这条思路成立，而且它是 sound 的**，但它需要的是**另一个模块**
（见 `type-inspection-module-design.md`），**不改变本文档的结论**：

| 目标 | 该靠什么 |
|---|---|
| **编辑期**知道类型（选择器点开属性） | ✅ 本文档的**推导**（第一步）或**显式断言** |
| **运行期**判断类型并分流 | ❌ 推导做不到；靠「查看数据类型」模块 + If |

⚠️ **一条必须写进文档的 soundness 结论**：**"类型断言"本身是不 sound 的**
（编辑器说"是图片"，运行期可能是 `VNull` ⇒ **比 `ANY` 更糟，因为它在说谎**）。
只有三种做法是 sound 的：

1. **真的转换**（构造出目标类型的值）；
2. **断言 + 失败即中断**（`ExecutionResult.Failure`）；
3. **分支收窄**（判断 + 分支内使用，需要编辑器做流敏感分析 —— **大工程，不做**）。

⇒ 而"**判断 + 真分支内用「创建变量」断言**"组合起来是 sound 的
（判断为真 ⇒ 值确实是该类型）。⚠️ 前提：**断言必须放在"判断为真"的分支内**，
否则假分支也会执行它。

---

## 5. `returnDef` 的去留（**未来**，本文档只登记不实施）

需求方的判断：**如果结构定义搬到「停止并返回」上，函数签名的返回值定义就不需要了。**

**分析**：

- **作为"结构定义的存放处"** ⇒ 是，不需要了（定义搬到步骤上后它就成了第二处副本）。
- **作为字段** ⇒ **建议分阶段删，别现在删**：它现在有 **5 个读取点**：

| # | 读取点 | 用途 | 能改成现场推导吗 |
|---|---|---|---|
| 1 | `CallFunctionModule.kt:74-81` | 调用方的选择器 | ✅ 已读被调工作流 |
| 2 | `DefineFunctionModuleUIProvider.kt:174-178` | 「定义函数」卡片只读区 | ✅ |
| 3 | `EditorMoreOptionsSheet.kt:330-333` | 编辑器「更多选项」sheet 的返回值摘要 | ✅ |
| 4 | `ChatAgentModuleExecutor.kt:935`、`:2658` | **AI 看到的函数描述** | ✅ |
| 5 | `WorkflowFunctionSignatureCodec.kt:74-83` | **wire 格式**（备份/导出/导入） | ⚠️ 改字段 = 改备份格式 |

**正确的收敛顺序**：先让"推导"成为**唯一路径**（5 处都走它）⇒ `returnDef` 自然变成死数据
⇒ **那时再删**。反过来先删字段、再让各处各自现场推导，中间会出现
**"有的地方读字段、有的地方推导"的不一致窗口**。

⚠️ **2026-10-11 补充（§3.3.5 定案后）**：`returnDef` 的**两个字段都已失去来源** ——
`type` 改由**现场推导**（第一步），`keys` 改由**「停止并返回」的声明**（§3.3.5）。
⇒ 它**彻底**没有存在理由了，可以按下面的顺序收敛后删除：

| 步 | 动作 | 风险 |
|---|---|---|
| 1 | 第一步 + 第二步落地（类型推导 + `return_keys`） | 无（纯新增能力） |
| 2 | **停止写 `returnDef`**（`aggregateFunctionSignature` 不再产出它），5 处读取点全部改走现场推导 | ⚠️ **存量 `returnDef.keys` 在下次保存时静默清空**（§3.3.5 ①） |
| 3 | **删字段**（模型 + codec + 备份 wire 格式） | ⚠️ 老版本读新备份时返回值类型退化为 `ANY`；需单独评估 + 单独一次提交 |

⚠️ **`functionSignature` 整体不能删** —— `params` 是函数工作流的核心，`isFunction` 又是
"这是不是函数"的判据（§2.1）。可删的只有 `returnDef` 这一个子字段。

---

## 6. 静默失效点清单

每条都对应一种「不报错、只是行为不对」的失败模式。**带 ⚠️ 的是既有缺陷**。

| # | 失效点 | 表现 | 防法 |
|---|---|---|---|
| 1 | ⚠️ `deriveReturnDef` 的 `firstOrNull`（`:26`） | 分支里的多个 return，只有**文档顺序最靠前**那个算数；主路径的类型可能永远推错 | 改成 `filter` + 合并（第二步） |
| 2 | ⚠️ `WorkflowManager.kt:304` 的 `?: existing?.returnDef` | 推导失败时**沿用旧键**（静默过期） | 改成现场推导后自然消失 |
| 3 | ⚠️ `:56` 丢弃 `outputId` | `{{步骤.outputs}}` 的 `outputs` 拿不到 ⇒ 类型推不出来 | 第一步 |
| 4 | ⚠️ `:30-33` 硬编码 `DICTIONARY` | 返回图片的函数**必然**落 `ANY` | 第一步 |
| 5 | ⚠️ `:29` 推类型/推键未解耦 | "类型已知、键未知"表达不出来 | 第一步 |
| 6 | ⚠️ `:59` 只认「创建变量」 | `{{js步骤.outputs}}` / `{{HTTP步骤.response_headers}}` 一律推不出 | 第一步 |
| 7 | ⚠️ `WorkflowManager.kt:290` 的 `?: return existing` | 普通工作流**没有「定义函数」卡片** ⇒ 即使有「停止并返回」也永远不推导 | 第一步（子工作流走现场推导，不依赖这一步） |
| 8 | ⚠️ `DefineFunctionModuleUIProvider.kt:115`、`EditorMoreOptionsSheet.kt:331` 的 `keys.isNotEmpty()` | `keys = []`（"类型已知、键未知"）会显示成「（无返回值，或无法静态推导）」 | 改成 keys 为空时显示**类型名** |
| 9 | 属性链断裂 | 写错键/类型不符 ⇒ **静默 `VNull`**（`VariableResolver.kt:202-204`） | 无法机器化守卫，只能文档说明 |
| 10 | `VDictionary` 缺键 | 取到 `VNull`（`VDictionary.kt:45-59`）—— 这是**既定语义**，也是"键取并集"的依据 | — |
| 11 | ⚠️ `StopAndReturnModule` 的 `value` 无 `supportsRichText` | 用户用 🪄 选了变量，输入框里显示的还是 `{{...}}` 裸文本 | 改该模块时补上（AGENTS.md 那条规则） |
| 12 | ⚠️ **子工作流里的模块失败不中断父工作流** | `WorkflowExecutor.kt:849` `return null` + `:490` 传的是**子工作流自己的 id** ⇒ 父工作流拿到 `null` 继续跑（会弹错，但不停）。**这是"失败即中断"方案（§3.3.1 方案 B/C）在子工作流场景下不彻底的原因** | 要么接受并写进文案；要么单独修"子工作流失败传播"（影响所有模块，需单独评估） |
| 13 | ⚠️ `return_type` 手动指定**填错**且不校验（方案 A） | 编辑器承诺"是图片"，运行期值是字典 ⇒ 属性访问**静默 `VNull`**；且该声明**落盘**，会随导出/备份传播 | 要么校验（方案 B/C，但受第 12 条限制）；要么在 UI 上写明"填错会静默取空值" |
| 14 | ⚠️ **`dictionaryKeys` 不按类型兜底** | `MagicVariablePickerSheet.kt:470-492` 的 `declaredKeyRows` **无条件渲染**、不检查类型 ⇒ 给**非字典**输出带上 keys（用户先填字典、后把 `value` 改成图片）会**真的列出**那些键，点选后运行期取到 `VNull` | 读取侧只在 `typeName == DICTIONARY` 时才填 keys（§3.3.4） |

---

## 7. 实施落点（第一步）

| 文件 | 改动 | 归属 |
|---|---|---|
| `core/workflow/model/FunctionSignatureHelper.kt`（改） | 判据从"是不是「创建变量」"改成"查输出定义类型"；推类型/推键解耦；保留字面量字典那条 | **手动合并**（fork 已有文件） |
| `core/workflow/module/logic/CallWorkflowModule.kt`（改） | `getDynamicOutputs` 的 `result` 接上同一条推导 | **手动合并** |
| `core/workflow/module/logic/CallFunctionModule.kt`（**不改**） | `typeName = returnDef?.type ?: ANY` 已经是对的写法，helper 改完自动生效 | — |
| `core/workflow/module/logic/DefineFunctionModuleUIProvider.kt`、`ui/workflow_editor/EditorMoreOptionsSheet.kt`（改） | keys 为空时显示类型名 | **手动合并** |
| `test/.../model/FunctionSignatureHelperTest.kt`（改） | 见 §8 | 我方 |

---

## 8. 测试计划

⚠️ **§3.3.5 定案后**（键只靠声明）测试的形态变了：

| 用例 | 归属 |
|---|---|
| **类型推导**：`{{js步骤.outputs}}` → 字典；`{{截图步骤.image}}` → 图片；`{{HTTP步骤.response_headers}}` → 字典 | `FunctionSignatureHelperTest`（现有 3 个键用例**删 / 改**） |
| **带路径**：`{{步骤.dict.data}}` → 沿路径用 `VTypeRegistry.getPropertyType` 求类型 | 同上 |
| **多 return**：全一致 ✅ / 分歧 ⇒ `ANY` / 含未知 ⇒ `ANY` | 同上 |
| 零触发器 / 无「停止并返回」⇒ 无类型（`ANY`） | 同上 |
| **声明的键并集**（多 return 各自声明的键合并、去重） | 新增（`return_keys` 合并逻辑） |
| **读取侧按类型兜底**：类型不是字典 ⇒ `dictionaryKeys` 必须为空（§3.3.4） | 新增（`CallFunctionModule` / `CallWorkflowModule`） |
| **`return_keys` 的显示判据**：只在该点推导出字典时才显示 | 新增（UI 层，可能需要源码扫描型断言） |

⚠️ 需要**反证**：把类型判据退回 `firstOrNull` + 只认「创建变量」，上述用例应变红。

---

## 9. 决策台账 / 明确不做

### ✅ 已定案（用户 2026-10-11，**已无待拍板项**）

| # | 结论 | 出处 |
|---|---|---|
| 1 | 多 return 类型**有分歧 ⇒ 退 `ANY`**（不给"最像主路径"的） | §3 第二步 |
| 2 | **键取并集** | §3 第二步 |
| 3 | `ANY` 分支**参与合并**（含未知 ⇒ 结果 `ANY`，保守） | §3 第二步 |
| 4 | 结构定义**只给字典** | §3 第三步 |
| 5 | **`return_keys` 是唯一新增参数**，可留空 | §3 第三步 |
| 6 | **不做 `return_type` 手动指定** ⇒ **类型只有推导一个来源**，用户改不了 | §3.3.1 / §3.3.3 |
| 7 | `return_keys` 的显示判据 = **该 return 点的 `value` 推导出来是字典** | §3.3.4 |
| 8 | **`ANY` 场景没有声明键的入口**（有意取舍，用「创建变量」替代） | §3.3.4 |
| 9 | **键的来源：只靠「停止并返回」的声明**；**删掉推导键的逻辑**（`extractDictionaryKeys` 两条路径全删）；不声明就止步于类型 | §3.3.5 |
| 10 | 读取侧**按类型兜底**：`typeName != DICTIONARY` ⇒ `dictionaryKeys` 必须为空 | §3.3.4 |
| 11 | 存量 `returnDef.keys` **接受静默清空，不写迁移**（该功能 2026-09-10 才上线；迁移在多 return 时无解） | §3.3.5 ① |
| 12 | 删 `returnDef` 字段的时机 = **第一步 + 第二步真机确认后**，再单独一次提交 | §5 |

### 明确不做

| 不做 | 原因 |
|---|---|
| ❌ 编辑器**分支自动收窄**（"在 `if 类型是图片` 的分支里自动按图片展开"） | 需要改 If（加"类型是"运算符）+ 编辑器流敏感分析 + 按引用路径匹配 + 处理 else/嵌套 ⇒ **本质是给编辑器加一个迷你类型系统** |
| ❌ 给 `Workflow` 加"返回值声明"字段 | 见 §5：`returnDef` 已经在，且方向是**收敛到一条推导**，不是再加一个来源 |
| ❌ 复用 `functionSignature` 给普通子工作流 | 它是 `isFunction` 的判据（§2.1），一加就改变工作流语义 |

---

## 10. 引用前须知

- 本文档是**草稿**，所有"设计"都**未实现、未真机验证**。引用前先核对代码是否已变。
- **§2.4 是本文档最重要的一条**：两个调用模块的返回值来自**同一个锚点**（「停止并返回」）。
  任何"只给函数工作流做"的方案都是错的 —— 普通子工作流是**同一件事**。
- **§4 的 soundness 结论**（断言不 sound、只有三条路 sound）是后续所有讨论的前提，不要跳过。
- `file:line` 锚定在 `dev` @ `be840a91`（2026-10-11 工作区）。上游漂移后需重新核对。
