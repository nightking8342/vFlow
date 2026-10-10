# 「查看数据类型」模块设计

> **状态**：**草稿**（仅需求与设计，**未实现**）｜ **日期**：2026-10-11
> **相关**：`return-type-inference-design.md`（配套文档：返回值类型推导）。
> 该文档的 §4 是本文档的**前提**（soundness 结论），读本文档前先读它。
> 上游无此文件的对应物（fork 独有，冲突归属我方）。

---

## 0. 这份文档回答什么

1. 为什么需要「查看数据类型」模块（现状缺什么）；
2. 模块的**规格**（输入 / 输出 / 命名 / 边界）；
3. ⚠️ **为什么不做**"断言模块"和"转换模块"（至少现在不做）；
4. 与已有模块（「创建变量」/「解析 JSON」）的**分工**；
5. 已知缺口（本文档**不覆盖**什么）。

---

## 1. 需求原文（用户 2026-10-11，逐字）

> 那我们是不是只做一个查看数据类型的模块就行了 在使用时就通过这个模块做判断，
> 然后再使用创建变量实现编译期的引用

> 好像不对，这个断言是运行期才知道的，我们编辑是编译期就要知道了。如果我们不显示判断一下的话，好像不太对

---

## 2. 现状（已核查事实）

### 2.1 类型信息本来就在对象上（**零成本**）

```kotlin
// core/types/VObject.kt:8-13
interface VObject {
    val raw: Any?
    val type: VType          // ← 判断依据
    ...
}
```

`VType` 有 `id`（稳定，如 `vflow.type.image`）与 `getLocalizedName(context)`（本地化）。

### 2.2 没有类型判断的运算符，也没有类型判断模块

```kotlin
// core/workflow/module/logic/IfModule.kt:119-123
OPERATORS_FOR_ANY        = [存在, 不存在]
OPERATORS_FOR_TEXT       = [空, 非空, 等于, 不等于, 包含, 不包含, 开头, 结尾, 正则]
OPERATORS_FOR_NUMBER     = [等于, 严格等于, 不等于, 大于, 大于等于, 小于, 小于等于, 区间]
OPERATORS_FOR_BOOLEAN    = [为真, 为假]
OPERATORS_FOR_COLLECTION = [空, 非空]
```

⇒ **没有任何"类型是"运算符**。全仓也没有类型判断模块（grep 无结果）。
`IfModule` 也**没有 uiProvider**（走自动表单）⇒ 就算加运算符，
右操作数也得先解决"类型下拉"的 UI 问题。

### 2.3 「创建变量」= 转换 + 命名 + 断言（三合一）

```kotlin
// core/workflow/module/data/CreateVariableModule.kt:236-304
val rawValue = context.getVariable("value")        // VObject
val variable: VObject = when (type) {
    TYPE_STRING     -> if (rawValue is VString) rawValue else VString(rawValue.asString())
    TYPE_NUMBER     -> VNumber(rawValue.asNumber() ?: 0.0)            // :251
    TYPE_BOOLEAN    -> VBoolean(rawValue.asBoolean())
    TYPE_DICTIONARY -> coerceDictionary(rawValue)                      // :333-345
    TYPE_LIST       -> coerceList(rawValue)                            // :347-355
    TYPE_IMAGE      -> VImage(rawValue.asString())                     // :263-265
    TYPE_FILE       -> if (rawValue is VFile) rawValue else VFile(rawValue.asString())   // :266-268
    TYPE_COORDINATE -> …字典/列表/字符串 → 坐标…                        // :269-302
    else            -> VString(rawValue.asString())
}
```

| 职责 | 体现 |
|---|---|
| **转换** | 上面的 `when` 分支 |
| **命名** | 写进 `namedVariables`（`:306` 起） |
| **断言** | `getOutputs` 读 `type` 参数决定输出类型（`:133-155`） |

### 2.4 ⚠️ 「创建变量」的转换是**静默失败**的

```kotlin
// :333-345
private fun coerceDictionary(value: VObject): VDictionary = when (value) {
    is VDictionary -> value
    else -> {
        val rawMap = value.raw as? Map<*, *> ?: return VDictionary(emptyMap())   // ← 静默给空字典
        ...
    }
}
// :347-355 coerceList 同款：非 List ⇒ 静默给空列表
// :251      TYPE_NUMBER -> VNumber(rawValue.asNumber() ?: 0.0)   // "abc" ⇒ 静默变 0.0
```

⚠️ 具体后果：HTTP 的 `response_body` 是 **STRING**（`HttpRequestModule.kt:130`），
用「创建变量(字典)」接它会得到一个**空字典 `{}`**，**不报错、不提示**，
接着点 `code` 得到 `VNull`。

### 2.5 ⚠️ 「创建变量」本身就是一个**不 sound 的断言**

```kotlin
TYPE_IMAGE -> VImage(rawValue.asString())   // 值不是图片路径？照样构造出一个 VImage
```

- **静态类型**：选"图片" ⇒ 输出标 `vflow.type.image` ⇒ 选择器展开宽度/高度；
- **运行期**：值可能是 `VNull` 或一个无效 URI ⇒ 属性访问**静默 `VNull`**。

⇒ "断言不 sound"**不是新模块才会引入的**，是既有行为。这也是本文档 §4 的出发点。

（另一处**保真口径不一致**值得登记：`TYPE_FILE` / `TYPE_COORDINATE` 保真原对象，
`TYPE_IMAGE` **总是从字符串重建**。对当前 `VImage`（只包一个 `uriString`）无害，
但将来 VImage 带上 bitmap 就会变成缺陷。）

### 2.6 「解析 JSON」已经存在，但语义是"按路径提取"

```kotlin
// core/workflow/module/data/ParseJsonModule.kt:68-81
OutputDefinition("first_value", "第一个匹配值", typeName = ANY)
OutputDefinition("all_values",  "所有匹配值",   typeName = LIST)
```

⇒ 「JSON 文本 → **某个值**」已有；「JSON 文本 → **整份字典**（然后点任意键）」**没有**。

### 2.7 脚本侧拿不到细粒度类型（为什么不能用 JS 判断）

`JsValueConverter.coerceToJs` 只对
`VString/VNumber/VBoolean/VList/VDictionary/VScreenElement/VCoordinate` 有专门分支，
其余走 `else -> value.toString()`；而 `BaseVObject.toString() = asString()`（`BaseVObject.kt:9`）。

⇒ 脚本里：**图片 / 文件 / 日期 / 时间都变成字符串**，`typeof` 分不出来。
这是「查看数据类型」模块**精确性**的价值所在。

⚠️ **反向也堵死**：脚本的**返回值**同样只能是
`String / Number / Boolean / List / Map`（`JsValueConverter.coerceToKotlin` 的 `else -> toString()`，
`:108-171`；再由 `JsModule.kt:116-117` 用 `VObjectFactory.from` 包装）
⇒ **JS / Lua 模块永远产不出图片 / 文件 / 坐标**。
完整论证见 `return-type-inference-design.md` §2.9。

---

## 3. 设计：只做一个模块

### 3.1 规格

```kotlin
id = "vflow.data.inspect_type"
metadata: 分类「数据」，名字「查看数据类型」

输入:
  value: ANY            // ⚠️ supportsRichText = true（否则魔法变量不渲染成胶囊，AGENTS.md 那条规则）
  expected_type: ENUM   // **默认「仅查看」**；选了具体类型才输出 matched（见下）

输出（选「仅查看」时只有前两个；选了具体类型才有第三个）:
  type_id:   STRING     // "vflow.type.image" —— 稳定，供比较 / 日志
  type_name: STRING     // "图片" —— 本地化，仅供显示
  matched:   BOOLEAN    // **仅当 expected_type 是具体类型时才存在**（✅ 已定案）
```

### 3.2 `expected_type` 枚举的设计

**枚举是模块自己的输入参数**（在该步骤的编辑界面里下拉选），**不是**在 If 里引用的东西。

**选项 = 「仅查看」（默认）+「创建变量」的 8 种 + 「空」**：

| 选项 | 对应 | 说明 |
|---|---|---|
| **仅查看**（默认） | 哨兵串 `vflow.inspect_type.view_only` | ✅ **默认值**（最无害：刚加模块、还没选类型时，**不该**给出一个看起来合理的判断结果）。⚠️ 选它时**不输出 `matched`**（见下） |
| 文本 / 数字 / 布尔 / 字典 / 列表 / 图片 / 文件 / 坐标 | 对齐 `CreateVariableModule.TYPE_OPTIONS`（`:31-40`） | 这 8 种是用户能**主动构造 / 声明**的类型，语义明确 |
| **空** | `VTypeRegistry.NULL` | ⚠️ **必须加**，见下 |
| 其余类型（屏幕控件 / 通知 / 日期 / 时间 / UI事件…） | — | 第一版**不给**（枚举太长不好用）；要判它们走 `type_id` 文本比较（§3.4 的次用法） |

⚠️ **哨兵值必须带前缀**（`vflow.inspect_type.view_only`），避免与 `VTypeRegistry` 的 id 撞车 ——
对齐仓库既有惯例（`WORKFLOW_TAB_ALL = "vflow.tab.all"`、`vflow.icon.category.` 前缀）。

⚠️ **"可选参数"在 UI 上表达不出来**：自动表单的 ENUM 走 `createSpinner`，
它没有"空选项"的概念（`currentValue ?: defaultValue`，一定会选中一项）⇒
"不比较"只能靠**选项里有一项**来表达。

#### 3.2.1 ⚠️ 「仅查看」时**不输出 `matched`**（而不是输出一个恒假布尔）

`getOutputs` **按参数决定输出**（有先例：`CreateVariableModule.getOutputs` 按 `type` 参数决定类型）：

- 选了具体类型 ⇒ 三个输出（含 `matched: BOOLEAN`）；
- 选「仅查看」⇒ **只有 `type_id` / `type_name`**。

| 做法 | 编辑期看起来 | 运行期 |
|---|---|---|
| 输出恒 `false` | `matched` 存在、能用 | 用户判"为真" ⇒ **永远走假分支**，还以为是"类型不匹配" |
| 输出恒 `true` | 同上 | 更糟：把"没选类型"当成"类型匹配" |
| **不输出 `matched`**（✅ 定案） | **选择器里根本没有这一项** ⇒ 用户被迫去选一个期望类型 | 无陷阱 |

⇒ **把错误暴露在编辑期**，而不是运行期静默走错分支（符合本仓库"宁可退化也不要静默错误"的取向）。
执行器侧一致：`generateDefaultOutputs` 读的也是 `module.getOutputs(step)`（`WorkflowExecutor.kt:782`）。

#### 3.2.2 ⚠️ 为什么「空」必须加 —— 这是个现有缺口

```kotlin
// core/workflow/module/logic/ConditionEvaluator.kt:59-63
// OP_EXISTS 和 OP_NOT_EXISTS 只检查对象本身是否存在
// VNull 是对象实例，视为"存在"
OP_EXISTS     -> return true    // 只有 Kotlin null 视为不存在
OP_NOT_EXISTS -> return false   // VObject 永远"存在"
```

而 `VariableResolver` 在"取不到值 / 键不存在"时返回的是 **`VNull`**
（`VariableResolver.kt:204 return VNull`）⇒ **If 的「存在 / 不存在」判不出"空值"**。

⇒ 所以本模块的枚举里**必须有「空」**，否则用户**没有任何办法**判断"这个值是不是空"。
这算顺带补上一个既有缺口。

### 3.3 ⚠️ 两个输出都要，不能只给 `type_name`

如果只输出本地化文案（"图片"/"Image"/"画像"），用户在 If 里做文本比较会**跟着语言变**。
⇒ `type_id`（稳定、可比）与 `type_name`（本地化、仅显示）**都要给**。

### 3.4 与 If 的配合（**关键：If 里不引用枚举**）

**主用法：If 判 `matched` 布尔** —— 枚举在**模块自己的编辑器**里选，If 里只引用**输出**：

```
① 「查看数据类型」步骤：
     value         = {{调用步骤.result}}
     expected_type = 图片          ← 枚举在这里选

② If 步骤：
     条件   = {{查看数据类型步骤.matched}}     ← 引用的是**输出**
     运算符 = 为真                             ← 不需要右操作数
   ├─ 真分支 → 创建变量(断言成图片) → 后续点 width/height
   └─ 假分支 → …
```

**为什么判布尔最顺**：`ConditionEvaluator.kt:89-110` —— `为真` / `为假` 对布尔直接取
`bool1` / `!bool1`，**不需要比较值** ⇒ If 里只选运算符，不用填任何东西。

**为什么不让 If 直接比较类型**：If 的**右操作数现在是文本框 / 数字框**
（自动表单，`IfModule` 没有 uiProvider），**没有枚举下拉** ⇒ 想比较类型只能：

- 手打 `vflow.type.image` 这种 id（难看、易错、无补全）；
- 或用本地化的 `type_name`（**跟着语言变**，脆弱）。

⇒ 「枚举放在模块里 + If 判 `matched`」是**唯一顺的用法**。

**次用法：一个模块实例判多种类型**（用 `type_id` 文本比较）：

```
「查看数据类型」步骤：value = {{调用步骤.result}}，expected_type = 不比较
If: {{该步骤.type_id}} 等于 "vflow.type.dictionary"     ← 手打 id
```

| 用法 | 模块实例数 | If 里写什么 | 适合 |
|---|---|---|---|
| **主：`matched`** | 每种类型一个 | 选「为真」，不用填值 | 常见分支（是图片 / 是字典） |
| 次：`type_id` | **一个** | 文本等于 `vflow.type.xxx` | 一个值要判很多种类型（⚠️ 要手打 id） |

### 3.5 实现要点

- **纯函数层**：`(VObject, expectedTypeId?) -> (typeId, matched)` 抽出来，**纯 JVM 可测**
  （`VImage("x")` / `VString("a")` / `VNull` 可直接构造）；
- `type_name` 用 `value.type.getLocalizedName(context)`，**只在模块层**做
  （需要 Context，不进纯函数）；
- ⚠️ **不设 `AiModuleMetadata.usageScopes`** ⇒ 不进 AI 工具清单（避免污染 catalog）；
- 三语文案 + 图标 + `ModuleRegistry` **追加**注册（不重排已有注册）。

---

## 4. ⚠️ 为什么不做"断言模块"和"转换模块"

### 4.1 断言模块（"输出一个带目标类型的值"）—— **不做**

**它在说谎**：

| 方案 | 编辑器承诺 | 运行期实际 | 一致吗 |
|---|---|---|---|
| `ANY` | "我不知道" | 任意 | ✅ 不撒谎 |
| 断言模块（`value` 标成图片） | "**这是图片**" | 可能是 `VNull` | ❌ **撒谎** |

**"说谎"比"不知道"更糟**：用户看到编辑器说是图片，就会放心写 `{{x.width}}`，
运行期静默拿 `VNull`。

要让静态类型 **sound**，只有三条路：

| 方案 | sound？ | 为什么 |
|---|---|---|
| **真的转换**（构造出目标类型的值） | ✅ | 转换**真的产生了** `VDictionary`/`VImage` ⇒ 静态类型 = 运行期类型 |
| **断言 + 失败即中断**（`ExecutionResult.Failure`） | ✅ | 不匹配就失败 ⇒ 走到后续步骤时值一定是目标类型 |
| **分支收窄**（判断 + 分支内使用） | ✅ | 需编辑器流敏感分析 ⇒ **大工程，不做** |
| ❌ 断言 + 失败给 `VNull` | ❌ | 正是"说谎"那一行 |

⚠️ 而且**断言要解决的场景几乎不存在**：

| 场景 | 该用什么 | 断言有用吗 |
|---|---|---|
| 值是 `{{截图步骤.image}}`（模块声明了 IMAGE） | **静态推导**（`return-type-inference-design.md` 第一步） | ❌ 不需要 |
| 值是 `{{js步骤.outputs}}`（声明了 DICTIONARY） | **静态推导** | ❌ 不需要 |
| 值是 `{{js步骤.outputs.result}}`（手输字典键 ⇒ 静态 `ANY`；⚠️ 运行期**只可能是基本类型**，不可能是图片，见 §2.7） | 用**「创建变量」**即可（字典 / 列表保真） | ⚠️ 能"断言"，但**不 sound**，而且没必要 |
| 多 return 类型不一致 ⇒ 合并成 `ANY` | 改工作流统一类型 / 判断+转换 | ⚠️ 同上 |

⇒ **需要它的地方它不 sound，它 sound 的地方静态推导就解决了。**

### 4.2 转换模块（文本 → 整份字典/列表等）—— **现在不做，但缺口要登记**

缺口是真实的（§2.4、§2.6），但：

1. 它**不是本文档的目标**（本文档只解决"运行期判断"）；
2. 做它要**抽公共转换层** —— `coerceDictionary`(`:333`)、`coerceList`(`:347`)、
   `resolveCoordinateComponent`(`:357`)、`resolveCoordinateFromString`(`:377`)
   现在都是 **private**，必须抽出来两边共用（本仓库记过双份实现的代价）；
3. ⚠️ **绝不能直接改「创建变量」的失败语义** —— 现在是静默给空值，改成中断 =
   **行为破坏**，而因为它是静默的，我们不知道有多少存量工作流依赖这个行为。
   ⇒ 要承载"显式失败"就得**新增模块**（带 `success` 输出），不动「创建变量」。

---

## 5. 使用范式（判断 + 断言，**sound**）

```
If: {{查看数据类型({{调用步骤.result}}, 期望类型=图片).matched}} == true
├─ 真分支:
│    创建变量(变量名 = img, 变量类型 = 图片, 值 = {{调用步骤.result}})
│    后续步骤引用 {{img}}（或 {{该步骤.variable.width}}）   ← 选择器按图片展开属性
└─ 假分支: …
```

为什么 sound：判断为真 ⇒ 运行期值**确实是图片**；断言在真分支内
⇒ 静态类型与运行期类型一致。

⚠️ **前提（必须写进文案/文档）**：**断言必须放在"判断为真"的分支内**。
放在分支外的话，假分支也会执行它 —— 那时值不是图片，`VImage(rawValue.asString())`
会构造出一个假图片（属性访问静默 `VNull`）。

⚠️ 代价：**多两步**（判断 + 断言），且**编辑器不会检查**"判断的类型"与"断言的类型"
是否一致（判断图片、断言成文件 —— 没有任何守卫）。

---

## 6. 分工表

| 需求 | 用哪个 |
|---|---|
| 运行期判断值是什么类型 / 分类型走分支 | **本模块**（`matched` / `type_id`） |
| 编辑期把 `ANY` 当具体类型用（点开属性） | **「创建变量」**（或本文档 §5 的组合） |
| 值**已经是**目标类型，只是编辑器不知道 | **静态推导**（`return-type-inference-design.md` 第一步） |
| 值**不是**目标类型，需要真的变成它 | ⚠️ **缺口**：现在只能用「解析 JSON」按路径取，或走 JS |

---

## 7. 静默失效点清单

| # | 失效点 | 表现 | 防法 |
|---|---|---|---|
| 1 | ⚠️ `expected_type` 只影响 `matched`，**不影响 `value` 的类型** | 若本模块的 `value` 输出被标成目标类型 ⇒ **说谎** | **本模块不输出"带类型的 value"**；`value` 由「创建变量」给 |
| 2 | ⚠️ 断言放在判断分支**之外** | 假分支也执行 ⇒ 构造出假图片、静默 `VNull` | 文案/文档明确；**无机器化守卫** |
| 3 | ⚠️ 用户拿 `type_name`（本地化文案）做比较 | 切换语言后判断失效 | 输出 `type_id`（稳定）；`type_name` 仅显示 |
| 4 | ⚠️ `value` 未设 `supportsRichText` | 用户用 🪄 选了变量，输入框显示裸 `{{...}}` | 设 `supportsRichText = true` |
| 5 | ⚠️ 模块设了 `usageScopes` | 进了 AI 工具清单 ⇒ catalog 膨胀 | **不设** `AiModuleMetadata.usageScopes` |
| 6 | 属性链断裂（判断为真、但断言的类型与判断的不一致） | 静默 `VNull` | 文档说明；将来若做选择器收窄可缓解 |
| 7 | ⚠️ **If 的「存在 / 不存在」判不出空值**（**既有缺陷**，不是本模块引入的） | `ConditionEvaluator.kt:59-63` 明写「VNull 是对象实例，视为**存在**」⇒ 用户无法判断"取到的值是不是空" | **本模块的枚举加「空」选项**（§3.2.2）是唯一现成手段 |
| 8 | ⚠️ 用户用 `type_id` 文本比较时手打 id | 打错 ⇒ `matched` 恒假，**不报错** | 主用法走 `matched`（不用手打）；`type_id` 定位为次用法 |
| 9 | ⚠️ **先选了具体类型、引用 `matched` 后又改成「仅查看」** | `matched` 输出消失 ⇒ 引用**悬空**（运行期 `VNull` ⇒ If 条件**恒假**，静默走假分支） | 这是"改配置导致引用失效"的常见形态（与改 `workflow_id` 后参数区变化同类）；⚠️ **无机器化守卫**，只能文档说明 |
| 10 | ⚠️ 哨兵值与 `VTypeRegistry` 的 id 撞车 | 「仅查看」被当成一个真实类型去比较 ⇒ `matched` 恒假 | 哨兵串**带前缀**（`vflow.inspect_type.view_only`） |

---

## 8. 测试计划

纯函数层（`(VObject, expectedTypeId?) -> (typeId, matched)`）：

- 各类型 → `type_id` 正确（图片/文件/字典/列表/数字/文本/布尔/坐标/`VNull`）；
- `expected_type` 匹配 ⇒ `matched = true`；不匹配 ⇒ `false`；
- **防空转**：`type_id` 必须来自 `VObject.type.id`，不是硬编码。

⚠️ **反证**：把 `type_id` 改成硬编码 `vflow.type.string`，上述用例应变红。

模块层（`getInputs` / `getOutputs` 体检）：

- `value` 有 `supportsRichText = true`；
- **`expected_type` 的选项**：首项是「仅查看」且为 `defaultValue`；哨兵串带前缀
  （断言它**不在** `VTypeRegistry` 的任何类型 id 里）；
- ⚠️ **条件输出**（最易错、也最易静默）：
  - `expected_type = 仅查看` ⇒ `getOutputs` **不含 `matched`**；
  - `expected_type = 具体类型` ⇒ **含 `matched`，且 `typeName = BOOLEAN`**；
  - 建议用**源码扫描 + 纯函数**双保险（`getOutputs(step)` 需要构造 `ActionStep`，纯 JVM 可测）；
- `aiMetadata.usageScopes` **为空**（源码扫描型断言）。

---

## 9. 决策台账（**已无待拍板项**）

### ✅ 已定案（用户 2026-10-11）

| # | 结论 | 出处 |
|---|---|---|
| 1 | **枚举是模块的输入参数**（在模块编辑器里选），**If 里不引用枚举** —— If 判 `matched` 布尔 | §3.4 |
| 2 | 枚举选项 = 「创建变量」的 **8 种 + 「空」**；**「空」必须加**（If 的「存在/不存在」判不出 `VNull`） | §3.2 / §3.2.2 |
| 3 | `type_id`（稳定）+ `type_name`（本地化）**都要输出**；`type_id` 兼作"一个实例判多种类型"的次用法 | §3.3 / §3.4 |
| 4 | **"不比较"用 ENUM 里的一项「仅查看」表达**（自动表单的 `createSpinner` 没有"空选项"），且**它作为默认值**（最无害） | §3.2 |
| 5 | 该哨兵值**带前缀**（建议 `vflow.inspect_type.view_only`），避免与 `VTypeRegistry` 的 id 撞车 | §3.2 |
| 6 | ⚠️ **「仅查看」时不输出 `matched`**（`getOutputs` 按参数决定输出）—— 把"没选期望类型"暴露在**编辑期**，而不是输出恒假布尔让用户在运行期静默走错分支 | §3.2 / §7 第 9 条 |
| 7 | 模块名**保留「查看数据类型」**（动宾结构符合仓库命名；与需求方叫法一致；叫"判断"反而更窄、更误导） | §9 |
| 8 | 枚举**第一版只给 8 种 + 「空」**；长尾（屏幕控件 / 通知 / 事件）走 `type_id`，⚠️ **追加选项向后兼容**，将来随时可加 | §3.2 |

---

## 10. 明确不做

| 不做 | 原因 |
|---|---|
| ❌ 断言模块（`value` 标目标类型） | §4.1 不 sound |
| ❌ 转换模块（文本→整份字典/列表） | §4.2 本文档不覆盖；缺口登记，另行立项 |
| ❌ 改 `IfModule` 加"类型是"运算符 | 只为了让用户少写一步，性价比低；且 `IfModule` 无 uiProvider，右操作数要改自绘 UI |
| ❌ 选择器的「按类型查看属性」 | 有了判断 + 断言只是"少一步"的便利，可后补 |
| ❌ 编辑器分支自动收窄 | 大工程（迷你类型系统） |

---

## 11. 引用前须知

- 本文档是**草稿**，**未实现、未真机验证**。
- **§4 是本文档最重要的一节**：它解释了为什么"只做一个查看数据类型的模块"是**正确的最小方案**，
  以及为什么"断言模块"看似省事、实际是在说谎。
- `file:line` 锚定在 `dev` @ `be840a91`（2026-10-11 工作区）。上游漂移后需重新核对。
