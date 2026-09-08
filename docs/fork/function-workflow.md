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
- 工作区当前干净。

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

| # | 待办项 | 对应决策 | 说明 |
|---|---|---|---|
| 1 | **魔法变量选择器「函数参数」分组** | 决策 2 | 改 `WorkflowEditorMagicVariableCatalogBuilder`，需要传入 `Workflow` 或从 `actionSteps` 反查（架构约束：`buildNamedVariables` 拿不到 Workflow） |
| 2 | `EditorMoreOptionsSheet` 函数状态行 | 决策 11.5 | 加只读签名展示 |
| 3 | **「定义函数」UI 完整化** | 决策 21 | 当前只有参数名输入框；缺：类型下拉、默认值随类型切换、必填开关、返回值配置区、snake_case/去重校验 |
| 4 | `CallFunctionModule.getDynamicInputs` 渲染真机验证 | 决策 5/13 | 已写代码，但未在编辑器验证动态参数框是否真实渲染 |
| 5 | 删除「定义函数」卡片清空签名 + 提示 | 决策 23 | 未做 |
| 6 | 移动「定义函数」卡片需阻止非首位 | 决策 16 | 未做 |
| 7 | 嵌套调用 / 递归检测专项测试 | 决策 25 | 执行器逻辑已支持，未测试 |

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

| 场景 | 你实际会看到 |
|---|---|
| **B1. 变量选择器自动列出函数参数** | ❌ 选择器里**没有**独立「函数参数」分组。只能手敲 `[[url]]`，不能点选 |
| **B2. 「定义函数」卡片配置完整字段** | ❌ 只有「参数名」输入框 + 添加/删除。**没有**类型下拉、默认值、必填开关、返回值配置区 |
| **B3. 更多选项弹窗显示函数状态行** | ❌ 工作流「更多选项」里**没有**函数签名状态行 |
| **B4. 返回值选择器自动展开 `result` 的键** | ❌ 选择器**不会**自动列出 `code`/`msg` 等键，只能手动写 `{{...result.code}}` |
| **B5. 「定义函数」固定为第一步 / 删除清理签名** | ❌ 卡片可被拖到任意位置、可删除，编辑器不阻止、不清空 `functionSignature` |
| **B6. 嵌套调用函数工作流** | ⚠️ 执行器逻辑已支持，但未专项测试，不保证 |

#### C. 已知坑（测试时注意）

- **返回值静态推导只在保存时触发**：你编辑 A 时看 `functionSignature.returnDef` 可能是旧的，**保存后**才更新。
- **参数名校验**：未做 snake_case/去重校验，输入非法参数名不会报错（但 `CallFunctionModule` 生成输入框时仍会生成，只是没校验）。

### 14.5 环境注意

- **单测已通过**：`FunctionSignatureHelperTest`（5 个用例）。
- **全部单测有 1 个既有失败**：`VObjectPropertyTest > VFile properties from absolute path`——是 `android.net.Uri.parse` not mocked 的环境问题，**与函数工作流改动无关**（事先存在）。
