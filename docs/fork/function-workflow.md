# 函数工作流（Function Workflow）需求文档

> 版本：v3.1
> 状态：设计定稿（已与需求方确认 28 项决策 + 5 个 UI 原型）
> 目录：`docs/fork/`（fork 新增文件，上游无此文件，冲突归属我方）

---

## 0. 文档定位

仅描述**产品与交互需求**。聚焦两个场景：
1. 把某个工作流**声明成函数**（定义参数）。
2. 调用方**按参数赋值**地调用一个函数工作流。

---

## 1. 背景与现状问题

当前唯一的「调用工作流」方式是 `vflow.logic.call_workflow`，通过**共享命名变量**传参，存在四个问题：

1. **没有参数声明**：被调工作流不能声明「我接受哪些参数」，只能靠约定变量名。
2. **先开发被调方很难**：开发被调工作流时，调用方变量还不存在，无法预览/校验参数。
3. **命名冲突**：被调方不能用「创建变量」预声明同名变量，否则触发冲突报错。
4. **作用域泄漏**：参数反向写回调用方，无法隔离。

---

## 2. 核心概念

- **函数工作流**：一个普通工作流，声明了「函数签名」（参数 + 返回值）后即成为函数，可被其他工作流按参数调用。
- **函数签名（FunctionSignature）**：`(params: List<FunctionParam>, returnDef: FunctionReturn?)`，存于 `Workflow.functionSignature`。
- **函数参数（FunctionParam）**：`(name, type, defaultValue, isRequired)`。
- **函数返回值（FunctionReturn）**：只对「返回字典」场景需要，`(type=DICTIONARY, keys: List<ReturnKey>)`。
- **调用函数模块**：`vflow.logic.call_function`，按参数传参、隔离作用域、取回返回值。

---

## 3. 数据模型（后续实现参考，不阻塞交互）

```kotlin
data class FunctionParam(val name: String, val type: String, val defaultValue: Any? = null, val isRequired: Boolean = false)
data class ReturnKey(val name: String, val type: String)
data class FunctionReturn(val type: String = DICTIONARY, val keys: List<ReturnKey> = emptyList())
data class FunctionSignature(val params: List<FunctionParam> = emptyList(), val returnDef: FunctionReturn? = null)

// Workflow 上新增
var functionSignature: FunctionSignature? = null   // null = 普通工作流，非 null = 函数
```

---

## 4. 配置入口：工作流「新增动作」里的「定义函数」卡片

函数声明**不是**藏在设置弹窗里，而是作为工作流的**第一个动作卡片**存在（呼应「参数要在逻辑里被引用，应该最先定义」）。

### 4.1 「定义函数」卡片

- 位于工作流编辑区，**必须是函数工作流的第一步**（编辑器阻止将其移到其他位置）。
- 归入现有**「逻辑控制」**分类（不新增分类）。
- 是**空操作模块**：运行时无副作用（执行时跳过，不调 `execute()` 的业务逻辑）。
- **只作 UI 入口**：签名唯一存于 `Workflow.functionSignature`，卡片不重复存储。编辑卡片时**实时写入** `functionSignature`，变量选择器立即可见。
- **UIProvider 拆分**：参数列表的增删改容器由 `DefineFunctionModuleUIProvider` 新做；每个数据类型的编辑框复用 `StandardControlFactory` 标准控件（类型下拉、富文本/数字/开关/字典/列表/文件picker 等）。
- **摘要**：显示函数签名，如 `定义函数: url(文本), method(文本) → 返回{code,msg}`（从 `Workflow.functionSignature` 读取渲染）。
- **嵌套调用**：允许函数工作流内再调用其他函数工作流，复用 `executeSubWorkflow` 嵌套机制 + `workflowStack` 递归检测。
- 功能：声明该工作流是函数，配置**参数**和**返回值**。

#### 4.1.1 参数配置区
- 「+ 添加参数」→ 弹出参数编辑表单：
  - **参数名**：`snake_case` 校验，去重。
  - **类型**：下拉（8 种，见映射表）。
  - **默认值**：随类型切换编辑框（见 4.2）。
  - **是否必填**：开关。
- 参数卡片列表：参数名 + 类型徽标 + 必填/可选 + 默认值预览；每项可编辑/删除。

#### 4.1.2 返回值配置区
- **只对「返回字典」场景需要**。基础类型的返回值无需定义。
- 返回值定义**自动从「停止并返回」的 value 派生**：
  - 用户在函数末尾放「停止并返回」，value 填一个字典。
  - **保存工作流时**，系统扫描该字典的键，自动写入 `functionSignature.returnDef.keys`。
  - **支持追溯**：若 value 是字面量字典，直接读键；若 value 引用某个「创建字典变量」步骤，则追踪该步骤读取其键名。
  - 用户无需手工重复填。
- 返回值配置区展示自动派生的结果（只读展示，来自「停止并返回」的字典键）。

### 4.2 参数类型 → 编辑框映射表（两端一致）

| 参数类型 | 编辑框 | 说明 |
|---|---|---|
| 文本 | 富文本输入框 | 支持 `{{}}`/`[[]]` 药丸 |
| 数字 | 数字键盘输入框 | `TYPE_CLASS_NUMBER + DECIMAL + SIGNED` |
| 布尔 | `MaterialSwitch` 开关 | |
| 列表 | 列表编辑器 | 逐项增删 |
| 字典 | 键值对编辑器 | 逐键值增删 |
| 图片 | 路径输入 + 「选择图片」picker | 存路径字符串 |
| 文件 | 路径输入 + 「选择文件」picker | 存路径字符串 |
| 坐标 | X、Y 两个数字输入框 | 或屏幕区域选择器 |

> 同一个类型，在「定义参数侧」和「调用方赋值侧」用**同一套控件**。

---

## 5. 调用方：「调用函数工作流」模块

### 5.1 选择函数工作流
- 弹窗（复用 `SearchableWorkflowDialog`）**只列函数工作流**（`functionSignature != null`）。
- 每项显示：函数名 + **「函数」徽标** + **参数签名预览**（如 `url: 文本(必填)`）。
- 选中后，卡片**动态生成**参数赋值区。

### 5.2 参数赋值区
- 每个参数一行，控件类型见 4.2 映射表（两端一致）。
- **所有参数都支持绑定变量**（`[[]]`/`{{}}`）。
- 必填参数带 `*`；未赋值且无默认值时，摘要提示「缺少参数 xxx」。
- 可选参数未赋值 → 用被调方声明的默认值。
- 参数顺序 = 被调方声明顺序。

### 5.3 步骤卡片摘要
```
调用函数: [函数工作流名称]
  ├─ url: {{step1.url}}
  ├─ count: "3"
  ├─ enabled: 开
  └─ 返回: result
```

---

## 6. 返回值（`result`）

- `call_function` **只输出 `result`**，不额外暴露函数工作流内部命名变量（黑盒语义：只进参数、出一个结果）。

### 6.0 参数注入作用域

- `executeSubWorkflow` 的 `namedVariables = parentContext.namedVariables + injectedVariables`。
- 即：被调工作流可读父工作流的全部命名变量，且**注入的函数参数覆盖优先**。
- 参数注入为私有种子，调用方传入的实参通过 `injectedVariables` 覆盖同名命名变量。

- 输出单一 `result`。
- **基础类型**：`result` 直接标对应类型（如 `vflow.type.number`），调用方选择器自动展开该类型的属性（如 `result.int`/`result.round`）。
- **字典类型**：`result` 标为 `vflow.type.dictionary`，调用方选择器能：
  - 列出内置属性（`count`/`keys`/`values`）。
  - **点击展开**自动派生的键（`code`/`msg`/`data`）直接点选，生成 `{{调用步骤.result.code}}`。
  - 或手动输入键。
- 底层属性访问引擎支持任意深度链式访问（`{{result.data.name}}`），无需额外声明。

---

## 7. 校验与提示

| 场景 | 校验/提示 |
|---|---|
| 参数名重复 | 阻止保存，提示「参数名已存在」 |
| 参数名非法 | 提示非法字符，仅允许 `snake_case` |
| 必填参数未赋值（调用方） | 摘要提示「缺少参数 xxx」；运行时 `call_function.execute()` 校验，未传且无默认值返回 `Failure("缺少必填参数 'xxx'")` |
| 函数工作流参数变更（调用方已建） | 编辑器提示「函数参数已变化，请重新映射」 |
| 删除「定义函数」卡片 | 清空 `functionSignature`，提示「此工作流不再是函数，引用它的其他工作流将失效」 |
| 递归调用 | 运行时返回错误「检测到循环调用」 |
| 复杂类型参数（图片/文件） | 赋值时保真类型，不降级为字符串 |

---

## 8. 边界情况

- 旧工作流未声明 `functionSignature` → 不进入函数工作流选择器。
- 参数变更后旧调用方 → 新声明生效，缺省用默认值。
- 布尔/坐标等没有合理默认值时，标记为必填。

---

## 9. 兼容性说明

- **保留现有 `vflow.logic.call_workflow`**（共享命名变量），不改造。
- **新增 `vflow.logic.call_function`**（按参数传参、隔离作用域）。
- 两者并存，用户按需选择。

## 9.1 执行入口

- 函数工作流（`functionSignature != null`）**既可被 `call_function` 调用，也可手动/触发器直接启动**。
- 直接启动时，参数没有调用方注入，使用声明的**默认值**（若未声明默认值，则使用空值）。

---

## 10. 仍需确认（不阻塞主体）

1. 「定义函数」卡片在工作流里的**位置约束**：必须为第一步（已在 4.1 确定）。
2. 保存时「返回值自动派生」与「定义函数」卡片配置的时序关系。

---

## 11. UI 原型（基于 XML View 系统，贴近 vFlow 编辑器真实结构）

> 工作流编辑器的步骤卡片走 **XML View**（`item_action_step.xml` + `ActionStepViewHolder`），参数编辑走 `StandardControlFactory`。以下原型以此为准，标注真实控件类型，可直接指导实现。

### 11.1 「定义函数」—— 参数列表（工作流第一步卡片）

```
┌─────────────────────────────────────────────────────────────────┐
│  item_action_step.xml（MaterialCardView）                          │
├──────────────────────────────────────┬──────────────────────────┤
│  [图标f(x)]  定义函数                  │  分类: 逻辑控制            │
│  ────────────────────────────────────│──────────────────────────│
│  函数参数:                             │                          │
│  ┌─────────────────────────────────┐ │                          │
│  │ [TextInputLayout] url            │ │                          │
│  │   类型: 文本    ●必填   [✎][🗑]   │ │                          │
│  ├─────────────────────────────────┤ │                          │
│  │ [TextInputLayout] method         │ │                          │
│  │   类型: 文本    ○可选   [✎][🗑]   │ │                          │
│  └─────────────────────────────────┘ │                          │
│  [MaterialButton: + 添加参数]         │                          │
│                                        │                          │
│  ▶ 返回值（自动派生·只读）             │                          │
│    类型: [TextInputLayout: 字典]       │                          │
│    ┌───────────────────────────────┐ │                          │
│    │ code    数字                 │ │                          │
│    │ msg     文本                 │ │                          │
│    │ data    列表                 │ │                          │
│    └───────────────────────────────┘ │                          │
└─────────────────────────────────────────────────────────────────┘
```

**真实控件映射：**
- 卡片容器：`MaterialCardView`（`item_action_step.xml`）
- 参数行：`TextInputLayout` + `TextInputEditText`（参数名）
- 类型：`MaterialAutoCompleteTextView`（下拉，`StandardControlFactory.createSpinner`）
- 必填：`MaterialSwitch`
- 编辑/删除：`ImageButton`（`button_delete_kv` 样式）
- 添加按钮：`MaterialButton`

### 11.2 「定义函数」—— 添加/编辑参数弹窗（底部弹窗）

```
┌─────────────────────────────────────────────────────────────────┐
│  BottomSheetDialogFragment（类似 MagicVariablePickerSheet）      │
│  标题: 添加参数                                                    │
├─────────────────────────────────────────────────────────────────┤
│  [row_editor_input.xml] 参数名                                    │
│    ┌───────────────────────────────────┐                         │
│    │ TextInputLayout:  url             │                         │
│    └───────────────────────────────────┘                         │
│                                                                    │
│  [row_editor_input.xml] 类型                                       │
│    ┌───────────────────────────────────┐                         │
│    │ MaterialAutoCompleteTextView: 文本▾│                         │
│    └───────────────────────────────────┘                         │
│                                                                    │
│  [row_editor_input.xml] 默认值  ← 随「类型」切换控件                │
│    ┌───────────────────────────────────┐                         │
│    │ 文本→RichTextView / 数字→数字输入 /│                         │
│    │ 布尔→MaterialSwitch / 列表→ListItem│                         │
│    │ 字典→DictionaryKVAdapter / 文件→picker│                        │
│    └───────────────────────────────────┘                         │
│                                                                    │
│  [row_editor_input.xml] 是否必填                                   │
│    ┌───────────────────────────────────┐                         │
│    │ MaterialSwitch:   ⬤ 必填   ○ 可选  │                         │
│    └───────────────────────────────────┘                         │
│                                                                    │
│            [TextButton: 取消]  [Button: 保存]                      │
└─────────────────────────────────────────────────────────────────┘
```

**关键交互**：切换「类型」时，默认值编辑框**立即替换**为对应控件（复用 `StandardControlFactory` 的分派逻辑）。

### 11.3 「调用函数工作流」—— 选择 + 参数赋值

```
┌─────────────────────────────────────────────────────────────────┐
│  item_action_step.xml（MaterialCardView）                          │
│  [图标▶]  调用函数工作流         分类: 逻辑控制                    │
├─────────────────────────────────────────────────────────────────┤
│  [row_editor_input.xml] 函数工作流                                 │
│    ┌───────────────────────────────────┐  [ImageButton 🔮]       │
│    │ MaterialAutoCompleteTextView:       │                        │
│    │   天气查询  (函数) ▾                │                        │
│    └───────────────────────────────────┘                        │
│    └─ 签名: city(文本●必填), days(数字○可选)                      │
│                                                                    │
│  参数赋值:                                                         │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ [row_editor_input.xml] city *                                 │ │
│  │   ┌───────────────────────────────────┐  [ImageButton 🔮]   │ │
│  │   │ RichTextView: {{step1.city}}       │                      │ │
│  │   └───────────────────────────────────┘                      │ │
│  ├─────────────────────────────────────────────────────────────┤ │
│  │ [row_editor_input.xml] days           │                        │
│  │   ┌───────────────────────────────────┐  [ImageButton 🔮]   │ │
│  │   │ 数字输入框: 3                      │                      │ │
│  │   └───────────────────────────────────┘                      │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  → 返回: result                                                   │
└─────────────────────────────────────────────────────────────────┘
```

**控件映射：**
- 函数选择：`MaterialAutoCompleteTextView`（`SearchableWorkflowDialog` 只列函数工作流 + 函数徽标）
- 参数赋值：每参数一行 `row_editor_input.xml`，值编辑框按类型分派（同 4.2 映射表）
- 魔法变量按钮：`ImageButton`（`button_magic_variable`）
- `*` = 必填参数

### 11.4 魔法变量选择器 —— 「函数参数」分组

```
┌─────────────────────────────────────────────────────────────────┐
│  MagicVariablePickerSheet（BottomSheetDialog）                    │
│  标题: 选择变量                                                    │
├─────────────────────────────────────────────────────────────────┤
│  [ClearAction] 清除                                                │
│                                                                    │
│  ▼ 函数参数   ← 新增独立分组（决策 2）                             │
│    ├─ url        (文本·必填)                                      │
│    └─ method     (文本·可选)                                      │
│                                                                    │
│  ▼ 命名变量                                                       │
│    ├─ my_var     (数字)                                          │
│    └─ counter    (数字)                                          │
│                                                                    │
│  ▼ # 1 查找文本                                                   │
│    └─ {{step1.text}}                                             │
└─────────────────────────────────────────────────────────────────┘
```

**实现要点：**
- 在 `WorkflowEditorMagicVariableCatalogBuilder.buildNamedVariables()` 里，额外把 `Workflow.functionSignature.params` 作为「函数参数」分组插入。
- 引用格式：`[[参数名]]`（沿用命名变量解析路径）。

### 11.5 函数工作流的「更多选项」弹窗 —— 函数状态行

```
┌─────────────────────────────────────────────────────────────────┐
│  EditorMoreOptionsSheet（BottomSheetDialogFragment）              │
│  天气查询                                    [函数徽标][f(x)]    │
│  ID: xxx      修改: 2026-09-08                                    │
├─────────────────────────────────────────────────────────────────┤
│  … 现有元数据区块（版本/描述/作者/图标/重入行为…）                   │
│                                                                    │
│  函数签名状态行（只读，提示「本工作流是函数」）                     │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ 函数参数: url(文本必填), method(文本可选)                     │ │
│  │ 返回值:   {code, msg, data}                                  │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 12. 已确认决策清单（设计定稿）

以下是本次评审确认的全部决策，实现时以此为准：

| # | 决策 | 结论 |
|---|---|---|
| 1 | 函数参数在被调方内部读取 | 私有 `namedVariables` 初始种子，内部用魔法变量选择器点选 `[[参数名]]` |
| 2 | 函数参数在选择器归类 | 独立分组「函数参数」，插入 `[[参数名]]`，底层复用 `namedVariables` |
| 3 | 参数类型集合 | 8 种：文本/数字/布尔/列表/字典/图片/文件/坐标 |
| 4 | 定义参数侧默认值框 | 文本=富文本；图片/文件=路径+选择器；数字=数字键盘；布尔=开关；列表/字典=专用编辑器；坐标=X/Y |
| 5 | 调用方赋值侧 | 与定义参数侧一致；调用方所有参数支持绑定变量 |
| 6 | 返回值 | `result`（基础类型直接标类型；字典标 `DICTIONARY` 并可点选键） |
| 7 | 返回值定义 | 只对字典类型需要；基础类型不需要 |
| 8 | 返回值定义来源 | 自动从「停止并返回」的 `value` 字典派生 |
| 9 | 同步时机 | 保存工作流时扫描「停止并返回」的 value 提取键 |
| 10 | 配置入口 | 工作流「新增动作」→「定义函数」卡片 |
| 11 | 签名存放 | `Workflow.functionSignature`（Parcelable，含 params + returnDef） |
| 12 | 定义函数卡片 | 空操作模块；配置参数实时写入 `functionSignature` |
| 13 | 调用方识别函数 | 弹窗只列函数工作流，带「函数」徽标 + 参数预览 |
| 14 | 新旧模块 | 并存：保留 `call_workflow`，新增 `vflow.function.call` |
| 15 | 分类 | 归入现有「逻辑控制」分类 |
| 16 | 定义函数位置 | 必须工作流第一步，编辑器阻止移动 |
| 17 | 执行入口 | 既可被调用，也可手动/触发器启动 |
| 18 | 必填参数校验 | 调用方侧 `execute()` 检查 |
| 19 | 参数注入作用域 | `parentContext.namedVariables + injectedVariables`（参数覆盖） |
| 20 | 签名数据流 | 卡片只作 UI，签名唯一存 `Workflow.functionSignature` |
| 21 | UI 复用 | 参数列表新做，编辑框复用 `StandardControlFactory` |
| 22 | 输出 | `call_function` 只输出 `result` |
| 23 | 删除定义函数 | 清空签名 + 提示引用方警告 |
| 24 | 返回值派生 | 可静态追踪引用字典变量的结构 |
| 25 | 嵌套调用 | 允许，复用 `executeSubWorkflow` + `workflowStack` 递归检测 |
| 26 | 定义函数摘要 | 带参数/返回值签名 |
| 27 | 动态传参 | 调用方参数支持魔法变量动态传参（循环内可传不同值） |
| 28 | 参数名冲突 | 函数参数与命名变量同名时参数优先，不报错 |

---

## 13. 实现改动清单（供方向 B 使用）

| 文件 | 改动 | 性质 | 风险 |
|---|---|---|---|
| `core/workflow/model/Workflow.kt` | 加 `functionSignature` 字段 | 改上游 | ⚠️ 敏感点 |
| `core/workflow/model/FunctionSignature.kt` | 新增 | 新增 | 低 |
| `core/execution/WorkflowExecutor.kt` | `executeSubWorkflow` 加 `injectedVariables` | 改上游 | ⚠️ 执行核心 |
| `core/workflow/module/logic/DefineFunctionModule.kt` | 新增 | 新增 | 低 |
| `core/workflow/module/logic/CallFunctionModule.kt` | 新增 | 新增 | 低 |
| `core/workflow/module/logic/DefineFunctionModuleUIProvider.kt` | 新增 | 新增 | 低 |
| `core/workflow/module/logic/CallFunctionModuleUIProvider.kt` | 新增 | 新增 | 低 |
| `core/module/ModuleRegistry.kt` | `initialize()` 追加注册 | 改上游 | 低 |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | 加「函数参数」分组 | 改上游 | 低 |
| `ui/workflow_editor/EditorMoreOptionsSheet.kt` | 加函数状态行 | 改上游 | 低 |
| 字符串资源 | 新增中英日文案 | 已做 | 低 |
| 单元测试 | 新增 `FunctionSignatureHelperTest` | 已做 | 低 |

---

## 14. 实现状态 / 交接（截至 2026-09-08）

> 本节为开发交接记录。**新开会话后先读本节**，结合 `git log`/`git status` 即可无缝接管。

### 14.1 当前分支与 commit

- **分支**：`feature/function-workflow`（从 `dev` 派生）
- **已提交 commit**：
  - `571f4afc` 需求文档与 UI 原型 + FORK.md 登记
  - `9b96ffc8` 函数工作流核心功能（14 文件，828 insertions）
  - `（本次）` 函数工作流 UI 完整化 + 实机 bug 修复 + 类型分派 + 动态刷新（详见 §14.6–§14.13）
- 最终状态见 **§15**。

### 14.2 已完成（编译通过 + 单元测试通过）

| 项 | 状态 |
|---|---|
| 数据模型 `FunctionSignature.kt`（FunctionParam/ReturnKey/FunctionReturn/FunctionSignature） | ✅ |
| `Workflow.kt` 加 `functionSignature` + `isFunction` | ✅ |
| `WorkflowExecutor.executeSubWorkflow` 加 `injectedVariables` | ✅ |
| `FunctionSignatureHelper.kt`（保存时返回值静态推导） | ✅ 单测通过 |
| `WorkflowManager` 保存时聚合签名 + 解析 `functionSignature` + 返回值推导 | ✅ |
| `CallFunctionModule` + UIProvider（按参数传参、只返回 result） | ✅ |
| `DefineFunctionModule` + UIProvider（简化版：仅参数名增删） | ✅ |
| `ModuleRegistry` 注册两个新模块 | ✅ |
| 字符串资源（中/英/日） | ✅ |

### 14.3 未完成清单（新会话待办，按优先级）

> 更新（2026-09-09）：本会话已完成待办 1/2/3/5/6，并对 7 补充了可单测的校验辅助与测试。剩余以「真机/集成验证」为主。

| # | 待办项 | 对应决策 | 状态 |
|---|---|---|---|
| 1 | **魔法变量选择器「函数参数」分组** | 决策 2 | ✅ 已完成（`WorkflowEditorMagicVariableCatalogBuilder.buildNamedVariables` 从「定义函数」卡片反查 `functionParams`，插入独立分组） |
| 2 | `EditorMoreOptionsSheet` 函数状态行 | 决策 11.5 | ✅ 已完成（新增只读签名卡片，非函数时隐藏） |
| 3 | **「定义函数」UI 完整化** | 决策 21 | ✅ 已完成（参数行可编辑：类型徽标/必填/默认值预览；新增 `DefineFunctionParamEditorSheet` 底部弹窗：参数名/类型下拉/默认值随类型切换/必填开关；返回值只读区；snake_case + 去重校验） |
| 4 | `CallFunctionModule.getDynamicInputs` 渲染真机验证 | 决策 5/13 | ⏳ 未验证（代码已实现，需真机确认动态参数框渲染） |
| 5 | 删除「定义函数」卡片清空签名 + 提示 | 决策 23 | ✅ 已完成（`WorkflowEditorActivity.onDeleteClick` 删除时清空 `functionSignature` 并 Toast 提示） |
| 6 | 移动「定义函数」卡片需阻止非首位 | 决策 16 | ✅ 已完成（`moveBlockInList` + `isBlockStructureValid` 校验「定义函数」必须在首位） |
| 7 | 嵌套调用 / 递归检测专项测试 | 决策 25 | ⚠️ 部分完成（递归检测执行器逻辑已实现；因无 Robolectric，JVM 单测无法覆盖 `WorkflowExecutor`；补充了 `FunctionParamValidator` 纯逻辑测试；递归/嵌套需真机集成验证） |

### 14.4 真机测试场景与预期效果

以下按「应通过」「应失败/缺失」分组。**测试时请用这两个场景逐一验证，并对照预期看是否符合。**

#### A. 应通过的核心场景

**场景 A1：声明一个函数工作流**
1. 新建工作流 A，命名为「天气查询」。
2. 在动作选择器「逻辑控制」分类里找到 **「定义函数」** 卡片（图标 `call_to_action`，不是「调用函数工作流」）。
3. 添加到工作流，编辑它 → 填一个参数名（如 `url`）。
4. 保存。

**预期**：能添加「定义函数」卡片、能输入参数名、保存不报错。

**场景 A2：调用这个函数工作流**
1. 新建工作流 B。
2. 添加 **「调用函数工作流」** 模块 → 点「选择」→ 弹窗**只列出**工作流 A（因为 A 是函数）。
3. 选中 A 后，卡片下方**动态生成**参数赋值区（`url` 输入框）。
4. 给 `url` 赋值，保存。

**预期**：选择器只列函数工作流；选中后能看到参数输入框。

**场景 A3：传参进函数 + 返回值**
1. 在工作流 A 里，第一步骤是「定义函数」（参数 `url`），后续加一个「停止并返回」`value` 填一个字典，如 `{ code: 200, msg: "ok" }`。
2. 工作流 B 调用 A，保存并运行。
3. B 里用一个模块引用 `{{调用步骤.result.code}}`（**手动输入**，选择器不会列出）。
4. 运行 B。

**预期**：
- 函数 A 内可用 `[[url]]` 读调用方传入的 `url`。
- B 的 `result` 能取到 `200`（通过 `{{...result.code}}`），底层字典动态 Key 访问支持。
- 必填参数未传时，运行时返回 `Failure("缺少必填参数 'xxx'")`。

#### B. 预期「缺失/不完整」的场景（不是 bug，是未实现）

> 更新（2026-09-09）：B1/B2/B3/B5 已实现，B4/B6 仍待验证。

| 场景 | 你实际会看到 |
|---|---|
| **B1. 变量选择器自动列出函数参数** | ✅ 已实现：选择器新增「函数参数」分组，可点选 `[[url]]`（从「定义函数」卡片反查 `functionParams`） |
| **B2. 「定义函数」卡片配置完整字段** | ✅ 已实现：参数行可编辑（类型徽标/必填/默认值预览），点击行或「+ 添加参数」打开编辑弹窗（类型下拉/默认值随类型切换/必填开关），并显示返回值只读区 |
| **B3. 更多选项弹窗显示函数状态行** | ✅ 已实现：工作流「更多选项」出现函数签名状态卡片（非函数则隐藏） |
| **B4. 返回值选择器自动展开 `result` 的键** | ⚠️ 未实现：选择器仍不会自动列出 `code`/`msg` 等键，只能手动写 `{{...result.code}}` |
| **B5. 「定义函数」固定为第一步 / 删除清理签名** | ✅ 已实现：拖拽被阻止使其保持首位；删除卡片会清空 `functionSignature` 并提示引用方警告 |
| **B6. 嵌套调用函数工作流** | ⚠️ 执行器逻辑已支持，但未专项测试，需真机集成验证 |

#### C. 已知坑（测试时注意）

- **返回值静态推导只在保存时触发**：你编辑 A 时看 `functionSignature.returnDef` 可能是旧的，**保存后**才更新。
- **参数名校验**：已做 snake_case/去重校验（`FunctionParamValidator`），非法/重复参数名会阻止保存并提示。
- **编辑弹窗宿主限制**：`DefineFunctionParamEditorSheet` 依赖 `FragmentActivity` 作为宿主；如果从非 Activity 的 Context 调用，弹窗无法打开（此时仅 Toast 提示）。

### 14.5 环境注意

- **单测已通过**：`FunctionSignatureHelperTest`（5 个用例）+ `FunctionParamValidatorTest`（7 个用例）+ `FunctionParamTypeMapperTest`（5 个用例）。
- **全部单测有 1 个既有失败**：`VObjectPropertyTest > VFile properties from absolute path`——是 `android.net.Uri.parse` not mocked 的环境问题，**与函数工作流改动无关**（事先存在）。
- **递归/嵌套调用**：`WorkflowExecutor` 依赖 Android Context/SharedPreferences，无 Robolectric 环境故无法 JVM 单测；需真机集成验证。

### 14.6 本轮实机测试发现的 bug 及修复（2026-09-09）

> 用户实机测试反馈多个问题，其中「致命 bug」已修复。核心根因是**参数类型存储用简写值（"string"）而下游按完整类型 ID（"vflow.type.string"）匹配**，导致类型退化成「任意」、调用侧控件变文本框。

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 保存后所有参数类型显示「任意」 | `FunctionParam.type` 存简写值 `"string"`，下游 `VTypeRegistry.getType("string")` 匹配失败回退 ANY | 新增 `FunctionParamTypeMapper`，保存时把简写值转成完整类型 ID |
| 2 | 必填参数调用时不填也不报错 | 调用侧留空时 `raw` 是空字符串（非 null），跳过必填校验 | `CallFunctionModule.execute` 用 `isBlankValue()` 把空字符串/空列表/空字典视为「未赋值」 |
| 3 | 调用侧参数框不区分类型（全是文本框），无 `*` 必填标记 | 类型匹配失败全落到 `STRING`；`InputDefinition` 无必填标记 | 类型修复后控件自动分派；`InputDefinition` 追加 `isRequired` + `getDisplayName`（带 `*`） |
| 4 | 定义函数卡片可在非第一步添加 | 添加步骤时始终追加到末尾，仅移动有约束 | `addStepsWithDefineFunctionRule`：含定义函数时强制插入首位 |
| 5 | 返回值选择器不能展开 `result` 的键（code/msg）；步骤输出无法手动输入 | `result` 输出为 `ANY` 类型；选择器不展开字典键 | ⚠️ **未修复**（B4 增强功能，需改魔法变量选择器展示 `returnDef.keys`） |
| 6 | 布尔默认值开关文案错写成「必填」 | `createDefaultEditor` 布尔分支误用「必填」文案 | 改用 `editor_define_function_param_default_hint` 文案 |

**本次修复文件**：
- `FunctionParamTypeMapper.kt`（新增）
- `DefineFunctionParamEditorSheet.kt`（类型转完整ID + 布尔文案）
- `CallFunctionModule.kt`（必填校验 `isBlankValue` + `getDynamicInputs` 标 `isRequired`）
- `definitions.kt`（`InputDefinition` 加 `isRequired`/`getDisplayName`）
- `ActionEditorSheet.kt`（`input_name` 用 `getDisplayName` 显示 `*`）
- `WorkflowEditorActivity.kt`（`addStepsWithDefineFunctionRule` 强制首位）

**仍待验证/待做**：
- 问题5（返回值键展开）—— B4 增强，需单独实现 + 真机验证
- 问题3 的 `*` 必填标记真机确认
- 定义函数强制首位的真机确认

### 14.7 第二轮修复：调用侧参数框类型分派（2026-09-09）

> 根因：`CallFunctionModule.getDynamicInputs` 把所有参数都设 `supportsRichText=true`，
> 而 `StandardControlFactory.createParameterInputRow` 的 `when` 首个分支命中富文本，
> 导致数字/布尔等也全部渲染成富文本输入框（用户反馈「一律大编辑框」）。

| 改动 | 说明 |
|---|---|
| `CallFunctionModule.getDynamicInputs` | `supportsRichText` 改为仅 `param.type == VTypeRegistry.STRING.id`；数字/布尔走 `createViewForInput` 类型分派（数字键盘/开关） |
| `DefineFunctionParamEditorSheet.createDefaultEditor` | 列表→`partial_list_editor`（`ListItemAdapter`）、字典→`partial_dictionary_editor`（`DictionaryKVAdapter`）；布尔默认值开关去重文案（`text=""`，避免与「默认值」标题重复） |
| `DefineFunctionParamEditorSheet.readDefaultValue` | 支持从列表/字典 adapter 读回默认值 |

**关键约束（已向用户确认）**：
- 原项目引用变量**默认不校验类型**（`enableTypeFilter` 默认 false），所以调用侧任何类型都能通过 🔮 选变量，且选中后 `isVariableReference` 分支渲染成药丸——**与 `supportsRichText` 无关**。
- 调用侧列表/字典/图片/文件目前仍走 `ParameterType.ANY` → 文本框（因 `ParameterType` 枚举无这些类型）。**用户决定先测这版，暂不做列表/字典专用编辑器的调用侧**。

**单测**：`FunctionParamTypeMapperTest` 新增 5 个用例，全部 410 个通过（仅 1 个既有 VObjectPropertyTest 环境失败）。

### 14.8 第三轮：调用侧参数赋值区由 UIProvider 接管（2026-09-09）

> 背景：调用侧列表/字典/图片/文件无法用专用编辑器，因为 `ParameterType` 枚举只有
> `STRING/NUMBER/BOOLEAN/ENUM/ANY`（上游基础类型，166 个文件依赖，不可改动）。
> 项目范式是：复杂类型（列表/字典/图片/文件/坐标）统一声明为 `ParameterType.ANY`，
> 由模块自己的 `ModuleUIProvider` 接管渲染（参照 `VariableModuleUIProvider`）。

| 改动 | 说明 |
|---|---|
| `CallFunctionModuleUIProvider.kt`（重写） | `createEditor` 选中函数后，在参数赋值容器内按 `FunctionParam.type` 分派控件（文本富文本/数字键盘/布尔开关/列表编辑器/字典编辑器）；`readFromEditor` 读回全部参数 |
| `CallFunctionModule.getDynamicInputs` | 改为只返回 `workflow_id`，**不再为参数生成 InputDefinition**（否则会与 UIProvider 的参数区重复渲染） |
| `partial_call_function_params.xml`（新增） | 参数赋值区容器 |
| `partial_call_workflow_editor.xml` | 追加 `<include>` 参数赋值容器 |

**关键架构点（已向用户确认）**：
- `getHandledInputIds()` 无参，无法动态声明参数名，所以参数渲染全部放在 `createEditor` 内运行时处理。
- `readParametersFromUi` 先调 `mergeCustomEditorParametersFromUi()`（`uiProvider.readFromEditor`），再遍历通用字段，所以 UIProvider 读回的值会覆盖——参数读取正确。
- `getDynamicInputs` 不再生成参数字段，避免与 UIProvider 重复渲染；但这也意味着 chat/AI 工具注册等依赖参数 `InputDefinition` 的场景需要评估（README 待补充）。

**待验证**：调用侧参数赋值区（文本/数字/布尔/列表/字典）真实渲染 + 变量药丸 + 读回。

### 14.9 崩溃修复：选择变量后 ClassCastException（2026-09-09）

> 用户实测：调用侧选择变量后页面崩溃。已通过无线 adb 抓到真实堆栈。

**崩溃堆栈**：
```
java.lang.ClassCastException: android.widget.LinearLayout cannot be cast to RichTextView
    at ActionEditorRichTextLocator.findRichTextView(ActionEditorRichTextLocator.kt:27)
    at ActionEditorSheet.findRichTextView(ActionEditorSheet.kt:924)
    at ActionEditorSheet.updateInputWithVariable(ActionEditorSheet.kt:960)
```

**根因**：`CallFunctionModuleUIProvider.createParamRow` 给整行 `row` 设了 `row.tag = param.name`（第158行）。而 `View.findViewWithTag<RichTextView>(inputId)` 做深度优先查找，**先命中根 LinearLayout（row）**而非内部 RichTextView，强转崩溃。

**修复**：删除 `row.tag = param.name`。参数行的富文本控件 tag 由 `createRichTextEditor(tag = param.name)` 正确设置在内部 `rich_text_view` 上，`findViewWithTag` 会命中正确的 RichTextView。

**经验**：在自定义 UIProvider 里用 `findViewWithTag` 定位控件时，**不要给作为容器的根 View 设相同的 tag**，否则深度优先查找会先命中容器导致类型强转崩溃。

### 14.10 修复：列表/布尔/字典引用变量无效 + 数字非药丸（2026-09-09）

> 用户实测：文本/图片/文件/坐标引用变量正常；列表/布尔/字典点了没反应；数字显示为 `{{uuid.属性}}` 纯文本。

**根因**：`updateInputWithVariable` 对非富文本类型（数字/列表/布尔/字典）走 `setPath` 存引用文本，但 `CallFunctionModuleUIProvider.createParamValueEditor` 未处理「值是变量引用」的情况——列表/字典仍渲染 adapter、布尔仍渲染 switch，导致值存了但控件不显示。

**修复**（对齐 `VariableModuleUIProvider`）：
- `createParamValueEditor` 对**数字/列表/布尔/字典**，若 `currentValue` 是变量引用（`{{..}}`/`[[..]]`），渲染 `magic_variable_pill` 药丸（可点击重选）；未选变量时才显示各自类型控件。
- 药丸 `tag` 存原始引用，`readParamValue` 读回药丸 tag 作为参数值。
- 文本/图片/文件/坐标仍走富文本编辑框（不受影响）。

**关键边界**：药丸分支只作用于 `NUMBER/BOOLEAN/LIST/DICTIONARY`。文本走富文本；图片/文件/坐标用户已确认正常，不拦截（否则会从可编辑药丸退化为只读 pill）。

### 14.11 修复：列表/字典**元素**引用变量（2026-09-09）

> 用户实测：列表的**某一个元素**点 🔮 引用变量没反应（整个列表引用正常了，但元素级不行）。

**根因**：列表/字典元素的魔法变量按钮传 `inputId = "items.0"` / `config.key`（子路径），而 `updateInputWithVariable` 走 `setPath` 更新 `currentParameters["items.0"]`，但 `ListItemAdapter`/`DictionaryKVAdapter` 的数据源是 adapter 内部的 `data`，两者不同步 → 值没写进列表元素。

**修复**：
- `CallFunctionModuleUIProvider.ViewHolder.insertVariable` 覆写：解析子路径（`Index`→列表项、`Key`→字典键），找到对应 adapter，`updateItem(pos, ref)` / `updateValueForKey(key, ref)` 更新。
- `DictionaryKVAdapter` 新增 `updateValueForKey(key, value)` 方法（新增方法，不改变现有签名）。

**关键**：`insertVariable` 属于 `CustomEditorViewHolder`，须在 `ViewHolder` 类内覆写（而非 UIProvider 类），否则 `overrides nothing`。

### 14.12 UI 打磨 + 校验放宽（2026-09-09）

用户反馈的 UI 打磨项（均已实现）：

| # | 调整 | 实现 |
|---|---|---|
| 1 | 调用侧必填标记改红色 | `CallFunctionModuleUIProvider.createParamRow` 用 `ForegroundColorSpan` 把「必填」标红（`R.color.md_theme_light_error`） |
| 2 | 调用侧参数行加类型 | label 改为 `类型 · 参数名 · 必填` |
| 3 | 定义侧删默认值徽章 | `DefineFunctionModuleUIProvider` 参数行不再展示默认值预览 |
| 4 | 定义侧参数列表 UI 重做 | 改用 `item_function_param.xml`（MaterialCardView 卡片）+ `FunctionParamListAdapter`（RecyclerView），每行：参数名+类型+必填(红)+编辑/删除 |
| 5 | 参数名校验放宽 | `FunctionParamValidator` 去掉 snake_case 强制，改为非空 + 去重 + 排除 `.[]${}空格`（会破坏 `[[参数名]]` 引用） |

**关键说明**：
- 参数名**不强制 snake_case**（与项目创建变量一致，`CreateVariableModule.validate` 也只查重名）。
- `FunctionParamValidator.isValidSnakeCase` 已移除，改为 `isValidName`（非空 + 禁止特殊字符）。
- 新增/更新单测：`FunctionParamValidatorTest`（6 个用例），全部通过；409 个单测仅 1 个既有 VObjectPropertyTest 环境失败。

### 14.13 崩溃修复 + 动态刷新 + UI 统一（2026-09-10）

用户反馈 4 项，均已修复并真机确认：

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 打开调用侧编辑器闪退 | `createParamRow` 复用 `row_editor_input` 做 `removeAllViews()` + 重复 `addView` 容器，触发 `IllegalStateException: child already has a parent` | 新建独立布局 `partial_call_function_param.xml`（header + 值区 + 魔法按钮），不再嵌套 addView |
| 2 | 调用侧参数行布局 | 星号在右边 | 改为 `参数名 · 必填(红星) · 类型`，左对齐点分隔 |
| 3 | 定义侧新增参数后列表不动态刷新 | 编辑弹窗保存后只调 `onParametersChanged`（更新 session），未刷新 UIProvider 列表 | UIProvider 新增 `onEditorSaved()`：`onParametersChanged()` + `holder.render()` |
| 4 | 定义函数卡片摘要需保存工作流才更新 | `getSummary` 从保存后才聚合的 `Workflow.functionSignature` 读取 | `getSummary` 改为**直接从 `step.parameters["functionParams"]` 实时解析** |
| 5 | 定义侧「添加参数」按钮样式不统一 | 代码用 `android.R.attr.borderlessButtonStyle` 拿不到 Material3 TextButton 样式 | 改用 XML 布局 `view_define_function_add_param_button.xml`（`Widget.Material3.Button.TextButton` + `ic_add`） |

**新增文件**：`partial_call_function_param.xml`、`view_define_function_add_param_button.xml`
**移除**：`DefineFunctionModule.findOwningWorkflowId`（不再需要）+ `WorkflowManager` import

### 14.14 返回值键展开（B4 / 决策 6、24）—— 实现（2026-09-10）

> 原待办：调用方引用返回值时，选择器不会列出 `code`/`msg` 等键，只能手动输入 `{{步骤.result.code}}`。
> 本次打通「签名键 → 输出定义 → 选择器目录 → 导航页点选」全链路。

**改动链路**：

| 层 | 文件 | 改动 |
|---|---|---|
| 输出定义模型 | `core/module/definitions.kt` | 新增 `OutputKeyDefinition`（Parcelable）；`OutputDefinition` 追加 `dictionaryKeys`（带默认空列表，向后兼容） |
| 模块输出 | `CallFunctionModule.getOutputs` | 从被调工作流签名解析：`returnDef != null` 时 `result` 标为 `returnDef.type`（字典）并携带声明的键；无 `returnDef` 回退 `ANY`（基础类型走底层类型引擎） |
| 选择器数据 | `MagicVariablePickerSheet.MagicVariableItem` | 追加 `dictionaryKeys` 字段（Parcelable，默认空） |
| 目录构建 | `WorkflowEditorMagicVariableCatalogBuilder.buildPickerModel` | 步骤输出项透传 `outputDef.dictionaryKeys` |
| 选择器 UI | `MagicVariablePickerSheet.renderNavigationList` | 字典类型下新增「函数返回值」分组，直接列出声明的键；点选生成 `{{步骤.result.code}}`；删除该 item 的键并清空 `dictionaryKeys`（键值非字典时不再展开） |
| 进入条件 | `MagicVariablePickerSheet.handleVariableSelection` | 无属性但**有声明键**时也进入导航页（而非直接选中变量本身） |
| 文案 | `strings*.xml`（中/英/日） | 新增 `magic_variable_section_declared_keys` |

**关键设计点**：
- 键行复用既有 `PropertyEntry` 渲染（`VPropertyDef(name=键名, type=键类型)`），因此**不需要新增布局或 adapter 分支**，与「可用属性」行样式一致。
- **过滤与内置属性同名的键**（`count`/`keys`/`values`/`availableKeys`）：`VDictionary.getProperty` 优先命中内置属性，此类键无法通过属性访问取到，故不展示。
- 运行时无需改动：`VariableResolver` 对 `{{stepId.result.code}}` 先查 `stepOutputs[stepId]["result"]` 再走 `traverseProperties` → `VDictionary.getProperty` 动态键查找（决策 24 的底层能力早已支持）。
- `getOutputs(step)` 现在读取 `appContext`（lateinit，注册时注入）。**模块 Context 未初始化时必须安全回退**：用 `runCatching` 包裹，回退 `ANY`。

**测试**：
- 新增 `CallFunctionModuleTest`（2 用例）：无 step / Context 未初始化时 `getOutputs` 均返回 `result` + `ANY`、无声明键、不崩溃。

### 14.15 待办状态更新

- §15.2 待办 1（返回值键展开）→ ✅ 已实现（见 §14.14）。
- 仍需真机验证：进入 `result` 的属性导航页能看到「函数返回值」分组与声明的键，点选后生成 `{{步骤.result.code}}` 且运行取值正确。

---

## 15. 最终状态（截至 2026-09-10）

### 15.1 已完成并真机验证通过
- 函数工作流核心闭环：声明函数 → 调用 → 传参 → 返回值 → 递归检测
- 定义侧参数编辑（卡片列表、类型下拉、默认值、必填、snake_case 放宽校验、动态刷新）
- 调用侧参数赋值（按类型分派控件、变量药丸、列表/字典元素引用、必填红色星号、动态输入）
- 魔法变量选择器「函数参数」分组、更多选项函数状态行
- 定义函数卡片首位约束、删除清空签名提示

### 15.2 已知待办（真机未覆盖 / 未实现）
| # | 待办 | 说明 |
|---|---|---|
| 1 | ~~返回值选择器自动展开 `result` 的键（决策 6/24，B4）~~ | ✅ **已实现**（§14.14）：选择器新增「函数返回值」分组，可点选 `code`/`msg` 等声明键；待真机确认 |
| 2 | 调用侧列表/字典/图片/文件的**整体**变量引用 | 元素级已支持；整体引用的控件分派受 `ParameterType` 枚举限制（仅 ANY） |
| 3 | 参数变更后旧调用方提示「函数参数已变化，请重新映射」 | 未实现 |
| 4 | 修复 `VObjectPropertyTest`（既有环境问题，非本功能） | `android.net.Uri.parse not mocked` |

### 15.3 测试基线
- 单测：`./gradlew :app:testDebugUnitTest` → 411 通过，仅 1 个既有环境失败（VObjectPropertyTest）
- 构建：`./gradlew :app:assembleDebug` 通过
- 真机：小米 MIX Fold 3（arm64），函数工作流全流程验证通过（返回值键展开为 2026-09-10 新增，待真机确认）
