# 日志模块设计（`vflow.data.log`）

**状态**：设计定稿，待实现 ｜ **日期**：2026-10-04
**上位背景**：AI 调试侧提出的「vFlow 日志能力增强需求 v1.0」中的 **R2**。
R1（每步 outputs 自动输出）经评审**不做**，理由见 §1。
**配套文档**：`docs/fork/workflow-log-level-design.md`（工作流元数据里的「日志等级」，
本模块的总闸）。

---

## 1. 为什么只做日志模块，不做 R1

| | R1（每步自动输出 outputs） | R2（日志模块） |
|---|---|---|
| 对**存量**工作流 | 全局改变每一个工作流的日志体积与内容 | 零。不主动加就不产生 |
| 对**循环**工作流 | 每轮每步加一行；100 轮 × 20 步 = 2000 行 | 只在使用者自己写的那几处产生 |
| 对**敏感数据** | 剪贴板 / OCR 全文 / 用户参数里的 `api_key` **无差别落进日志** | 使用者显式选择打什么 |
| 实现面 | 改 `WorkflowExecutor` 热路径（本项目最高冲突风险区） | 新增模块文件 + `ExecutionContext` 加一个字段 |

**R1 的真实代价不是体积，是「敏感数据持久化变成默认行为」**：

`LogEntry.detailedLog` 会被 `LogManager` 写进 `SharedPreferences`（`vFlowLogPrefs`，保留 50 条），
并随 `CrashReportManager`（`recentLogs = DebugLogger.getRecentLogsForCrash()`）**上传到友盟**。
R1 等于让「剪贴板内容」「OCR 全文」「用户的 API key」默认进这两条通道。

而 R2 把「打什么」交回使用者，同一套能力，风险面完全不同。

### ⚠️ 需求文档一处事实错误（已核实）

需求 §2.4 的「评审重点」写：「整场 4000 字符的硬上限会直接导致看不到报错」。
**当前不存在这个约束**：

- `WorkflowExecutor.executionLogs` 是无界的 `StringBuilder`（`WorkflowExecutor.kt:61`），
  只在执行结束时整体交给 `ExecutionStateBus`；
- `SharedPreferences` 侧的约束是 `LogManager.MAX_LOGS = 50`（**条数**，不是字符数）；
- 那个 4000 只作用于 **AI 看到的那一段**（`truncateMultiline`），且已在 2026-10-04 按要求**去掉**。

⇒ 该节批判的对象不存在，据此推出的「分文件 + 报错豁免 + 重复折叠」是对着空气设计。
**若将来要控制整场日志体积，那是另一个独立问题**（见 §7 未决项）。

---

## 2. 模块定义

| 项 | 值 | 说明 |
|---|---|---|
| moduleId | **`vflow.data.log`** | ⚠️ 需求建议的是 `vflow.system.log`，但 `vflow.system.*` 全是**真实系统操作**（蓝牙/亮度/截屏）；本模块无副作用，与同分类的 `vflow.data.comment` 同类 ⇒ 放 `data` |
| 名称 | 输出日志 | |
| 分类 | `data`（数据处理） | 与 `CommentModule` 同处 |
| 图标 | `rounded_log_24`（新增） | 见 §6.1 |
| 风险等级 | `AiModuleRiskLevel.LOW` | 无副作用、无权限。**不得**声明成 `HIGH`——那会让整条工作流被抬升到 high risk 并触发审批（见 §5.2） |
| usageScopes | `{ TEMPORARY_WORKFLOW }` | 见下 |
| `allowSavedWorkflow` | **不设**（`null` ⇒ 允许） | |
| 权限 | `emptyList()` | |

### 2.1 使用范围（用户 2026-10-04 定案）

```kotlin
override val aiMetadata = AiModuleMetadata(
    usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
    riskLevel = AiModuleRiskLevel.LOW,
    workflowStepDescription = "Print a value into the workflow execution log for debugging.",
    inputHints = mapOf(
        "content" to
            "Value to print. Use a magic variable like {{stepId.output}} or {{vars.name}}. " +
                "Writing just the step id ({{stepId}}, with no output name) dumps " +
                "EVERY output of that step at once — this shorthand works only in this module.",
    ),
)
```

⚠️ **不给 `DIRECT_TOOL`**：
- 直调时没有工作流上下文 ⇒ **没有能写入的执行日志**（`logSink` 在直调路径上也能注入，但值只能来自入参、日志也没人在看），价值极低；
- 本项目 v2.0 的原则是**精简直调工具表**（59 个模块工具已撤出，改走 `call_module` 按需）——加一个进去与那个方向相反。

⚠️ **`allowSavedWorkflow` 不设为 false** ⇒ `isSavedWorkflowModuleAllowed` 通过 ⇒
保存的工作流里也能用（用户手动在编辑器里加，或 AI 用 `save_workflow` 加）。
`usageScopes` 只管 AI 的临时工作流白名单与直调白名单，**不拦编辑器**。

---

## 3. 输入 / 输出

### 3.1 输入

| 参数 | id | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|---|
| 内容 | `content` | `ANY` | 是 | `""` | 要打印的值。`acceptsMagicVariable` + `acceptsNamedVariable` 均 `true` |
| 标签 | `label` | `STRING` | 否 | `""` | 用于区分同一工作流里的多处日志 |
| 级别 | `level` | `ENUM` | 否 | `info` | `info` / `warn` / `error` |

`content` 支持**两种写法**（同一参数，见 §3.3）：
1. 任意值 / 变量引用 —— `{{cls.state}}`、`{{vars.name}}`、`"静态文本"`；
2. **裸步骤 id `{{<步骤id>}}`（不带输出名）** —— 一次性打印该步的**全部输出**。

⇒ 没有新增参数需要学。写法和普通魔法变量一致，只是**少写一段**就是「全部」。


### 3.2 输出

| id | 类型 | 说明 |
|---|---|---|
| `success` | `BOOLEAN` | 是否成功写入日志 |
| `text` | `STRING` | **实际写入日志的那段内容**（序列化后的文本），用于链路核对 |

⚠️ `text` **不含** `label` 前缀、不含 `[日志] ` 标记 —— 它只等于**值的序列化结果**。
理由：下游 `{{log1.text}}` 的语义应当是「把这个值再拿回来用」，
带上 `label="x" ` 会让它不可用。整行长什么样由执行器拼。

### 3.3 整步输出：裸写步骤 id `{{<步骤id>}}`

**它解决什么**：需求 §5.1 写的 `log("cls.out", {{cls.outputs}})` **解析不出任何东西**。

`VariableResolver.resolveExistingVariableObject`（`VariableResolver.kt:139-186`）要求路径至少两段
`[stepId, outputId]`，`{{cls.outputs}}` 被解读为「步骤 `cls` 的输出 `outputs`」——
而那个键在输出表里通常不存在 ⇒ 返回 `VNull`。

#### 写法演进（用户 2026-10-04 两轮定案）

| 版本 | 写法 | 为什么被推翻 |
|---|---|---|
| v1 | `step_id: "cls"` 参数 | **不见名知意**：读不出「这会打印全部输出」，得先读文档才知道填了它语义就变了 |
| v2 | `{{cls.all}}` | 仍是**要多记一个词**（`all`）。而且 `JsModule` 真的输出一个叫 `outputs` 的键（`JsModule.kt:69`），`all` 也完全可能被将来的模块占用 |
| **v3（定稿）** | **`{{cls}}`** | 裸写步骤 id 本身就是「这个步骤的全部输出」——**不用记任何保留字** |

#### 用法

```
content = {{cls}}          → [日志] {state:2, xy:"540,1200", hit:"K2", streak:"0"}
content = {{cls.state}}    → [日志] 2
content = label: {{cls}}   → [日志] label: {state:2, …}
```

#### ⚠️ 为什么这不会与「既有语义」打架

关键事实：**当前 `{{cls}}`（单段路径）本来就什么都解析不出来**
（`resolveExistingVariableObject` 要求 `path.size >= 2` 才查 `stepOutputs`；
`path.size == 1` 时只回落查 `magicVariables`，那里只有 `index` 之类的循环变量）。
⇒ 裸步骤 id 目前是**死写法**，本模块赋予它语义**不会覆盖任何在用行为**。

（这正是 v2 的 `all` 不如它的地方：`all` 是个**活的**输出名空间，占了就可能撞车。）

#### ⚠️ 硬约束：不能动 `VariableResolver`

按常规做法，这个分派该写在 `resolveExistingVariableObject` 里（`path.size == 1` 且
`stepOutputs` 有这个 key 时返回整表）—— **但那样会污染全局语义**：

- 所有模块的**静态**输入跑的是同一个解析器。裸写 `{{cls}}` 会**全局**变成「整步输出」，
  而其它模块的参数（比如传给 `vflow.data.parse_json` 的一个名字恰好等于某步骤 id 的字符串）
  会**静默**收到一个字典而不是空值；
- 更糟的是它**不可撤销**：将来不可能再把 `{{cls}}` 的语义改回去（已有工作流会依赖它）。

⇒ 分派写在**日志模块内部**，`VariableResolver` **一行不动**。

#### 模块怎么拿到「原始串」（实现的关键落点，已逐条核实）

⚠️ **不能直接用 `getVariable("content")`** —— 执行器在步骤开始时已经**替我们解析过**了
（`WorkflowExecutor.kt:611-628`）：

| `content` 的值 | 执行器的处理 | `getVariable("content")` 得到 |
|---|---|---|
| `{{cls.state}}`（正常魔法变量） | `resolveSingleVariableReference` **成功** ⇒ 写进 `magicVariables` | **已解析的 VObject**（拿不到原始串了） |
| `{{cls}}`（本模块专有） | 解析**失败**返回 null ⇒ `magicVariables` **不设置** | 回落 `variables` ⇒ `VString("{{cls}}")` **字面量** |
| `label: {{cls}}`（混写） | `isComplex` ⇒ 走 `VariableResolver.resolve` | 已解析的 `VString` |

⇒ 两种情况拿到的东西**形态不同**（一个是 VObject、一个是字面量），
若照原样打印，`{{cls}}` 会输出成字符串 `"{{cls}}"`。

**正解：优先用 `getParameterRaw("content")`**（`ExecutionContext.kt:252`）——
它读 `variables[key]`，即 `step.parameters` 的**原始值**（`:598-605` 转成 `VString`），
**永远不含解析结果**，两次解析互不干扰。

```kotlin
val raw = executionContext.getParameterRaw("content")
val value: VObject = if (raw != null) {
    // 字符串参数：走本模块自己的逐段解析（含 {{cls}} 分派）
    resolveContent(raw, executionContext)
} else {
    // ⚠️ 非字符串参数（如 AI 直接给数字 42）时 getParameterRaw 返回 null
    //    （它的实现是 `is VString -> raw; else -> null`），此时直接用已解析的值
    executionContext.getVariable("content")
}
```

⚠️ **为什么必须带这个 `else` 分支**：`content` 声明为 `ParameterType.ANY`，
所以 `step.parameters["content"]` **可以不是 String**（数字 / 布尔 / 甚至已包装的 VObject）。
漏了它 ⇒ 传数字时 `resolveContent("")` 拿到空串 ⇒ **打印出 `(空)`**，
而使用者明明填了 `42`。

### 3.4 ⭐ 用户侧怎么选中它：**魔法变量选择器里加一项**

⚠️ 需求没写这一层，但它决定「人工调试到底用不用得上」——
`step.id` 是 **`UUID.randomUUID()`**（`ActionStep.kt:18`），编辑器 UI 里**不显示、也没法复制**
（工作流列表的「复制 ID」复制的是**工作流** id，不是步骤 id）。让用户手打一个 UUID 不现实。

⇒ 在 `WorkflowEditorMagicVariableCatalogBuilder.buildPickerModel` 里，
**当且仅当正在编辑 `vflow.data.log` 的 `content`** 时，给每个步骤分组
**追加一条「全部输出」项**（插在该步的具体输出项**之前**）：

```kotlin
// ⚠️ 只在日志模块的 content 上出现 —— 裸 {{cls}} 在别的模块里仍是空值，
//    全局放开会让用户选了却没效果（比不给更糟）。
val wantsWholeStep = editingModule.id == "vflow.data.log" && targetInputId == "content"
```

| | |
|---|---|
| 位置 | 该步骤分组下的**第一条**（在 `text` / `count` 等具体输出之前） |
| 显示名 | 「全部输出」（新增字符串资源，三语） |
| `originDescription` | 空（与数字/文本类型不同，它不是一个类型） |
| `variableReference` | `{{${step.id}}}` —— **裸步骤 id** |
| `typeId` | `VTypeRegistry.DICTIONARY.id` |

⚠️ **必须挂 `enableTypeFilter`**：`buildPickerModel` 会按
`targetInputDef.acceptedMagicVariableTypes` 过滤（`:172-178`）。`content` 是 `ANY`
⇒ 现在不会被滤掉；但若将来给它加了类型约束，这条要跟着走，
否则用户在选择器里看不到它（**静默消失**）。

⚠️ **代价**：这要改上游的编辑器文件（`WorkflowEditorMagicVariableCatalogBuilder.kt`），
按本仓库原则需在 `FORK.md` 登记为**手动合并**。改动量很小（一个布尔判断 + 一处 `if` 加一项），
但它是本设计**唯一**必须动上游 UI 的地方 —— 若想避免，就只能退回到「手打 UUID」（不现实）。

#### `resolveContent(raw, ctx)` 的逐段规则

`TemplateParser(raw).parse()` 拆段，逐段处理：

- **变量段**且 `path.size == 1` 且 `ctx.stepOutputs.containsKey(path[0])`
  ⇒ 段落值 = 该步的 `stepOutputs[stepId]` 字典（`ExecutionContext.kt:64`，`public`）；
- 其余变量段 / 脚本段 / 文本段 ⇒ 交回 `VariableResolver.resolve(原文, ctx)` 处理整串
  （**一次调用处理全部剩余段落**，不为每段各调一次 —— 那样会把混写串拆坏）。

⚠️ **`takeIf { raw.isNotBlank() }`**：`getParameterRaw` 对「字符串参数但值为空」也返回
非 null 的 `""`（只有**非字符串**才返回 null），故空串要当作「没填」走 §3.5 的 `(空)` 分支。

⚠️ **判据必须包含「这个 id 真的是一个步骤」**，不能只看 `path.size == 1`：

- 否则 `{{index}}`（循环变量，落在 `magicVariables` 里）、`{{path}}`（`VFile` 的属性名）
  这类**单段但隶属于别处**的引用会被误判成步骤 id；
- 做法：`path.size == 1 && executionContext.stepOutputs.containsKey(path[0])`。
  不在 `stepOutputs` 里 ⇒ 落回 `VariableResolver` 的既有行为（该返回什么就返回什么），
  **不做兜底改写**。

⇒ **已知边界（如实记录）**：`{{cls}}` 这个「展开成整表」的语义是**本模块专有**。
同一个字符串用在**别的模块**的参数里仍然是 `VNull`（既有行为，本设计不改变）。
文档与 `inputHints` 里必须说清这一点。

#### 未命中的情况（都不算失败）

| 情况 | 输出 |
|---|---|
| `{{cls}}` 里的 id 不是任何步骤 | 按 `VariableResolver` 既有行为 ⇒ `VNull` ⇒ 序列化成 `null` |
| 步骤存在但还没执行到 / 无输出 | `{}(step "cls" 无输出)` |
| 该步输出全为 `VNull` | 正常打印，如 `{success:false, error:"…"}`（SKIP 策略下会填这两键） |
| 与其它文本混写 | 逐段替换，如 `label: {{cls}}` ⇒ `label: {…}` |

### 3.5 `content` 为空

打一行 `[日志] label="x" (空)`，`success = true`。
**不报错** —— AI 生成步骤时常先占位，报错会让整条工作流中断，代价远大于收益。

---

## 4. 序列化规则

**一套规则，模块内私有实现**（见 §4.4：与 R1 的复用要求已无意义）。

### 4.0 ⚠️ 不截断（用户 2026-10-04 定案）

**第一版设计写了四条限制，其中三条是截断，已全部推翻。**

| 原设计 | 处置 | 理由 |
|---|---|---|
| 单字符串 200 字符 | ❌ **去掉** | 这是**截断**。使用者显式写了一条日志，就是要把那个值看到底；砍到 200 字符正是需求 §2.3 第 4 条批判的「静默截断」（`{{ocr.full_text}}` 实测最长 2450 字，而失败往往在后面） |
| 每容器 5 项 | ❌ **去掉** | 同上。`{{cls}}` 的全部意义就是「一次看到全部输出」——若只给前 5 项，这个功能等于没做 |
| 递归深度 3 | ❌ **去掉** | 它原本是**防无限递归**（不是防体积），但**这个风险到不了本模块**（见下） |
| `max_length` 参数 | ❌ **去掉** | 有了上面三条的结论，再留一个「默认 0 的不截断开关」是**死参数**——默认值就是唯一合理值 |

**「原来那三条担心的是 R1 场景」——这个判断是对的。** R1 是**自动**给每一步加输出，
体积要乘上「步骤数 × 循环轮数 × 每次执行 × 历史 50 条」⇒ 必须截断。
本模块是**使用者（人或 AI）显式写的几处**，量级完全不同：打印一个 2450 字的
`full_text` 是他要的东西，不是噪音。

**关于「递归深度」这个风险为什么不成立（已核实，不是推断）**：

- `VObjectFactory.from`（`VObjectFactory.kt:28-33`）对 `Collection` / `Map` **无条件递归**
  ⇒ 一个**循环引用**的 Kotlin `Map` 在**构造 VObject 时**就已经无限递归了
  （`StackOverflowError`），根本走不到本模块。⇒ **`VObject` 图天然无环**。
- 从 JS / Lua 来的值呢？`JsValueConverter.coerceToVObject` 的
  `NativeObject` / `ScriptableObject` 分支（`:131-161`）**同样没有 visited 集合** ——
  JS 对象里塞一个指向自己的属性，会在**转换阶段**就爆栈，也在到达本模块之前。
- ⇒ **不是"我们不需要深度限制"，而是"深度限制在这里防不住任何东西"**（要爆栈的早在别处爆了）。

**唯一保留的一条「不打印内容」红线不是截断**（§4.2）：
它拦的是「**去读** 2 MB 的 base64 / 整个文件文本」这个动作本身。
「读进来再截断」仍然要先把 2 MB 读进内存（可能 OOM），而且给使用者看半个 base64
没有任何用处 —— 所以它和截断是两件事。

⚠️ **如实记录的代价**：日志现在**没有体积上界**。极端情况（在循环里打大对象、
或 `{{cls}}` 打一个几百项的列表）会让单条日志达到几百 KB，
进而影响 `LogManager` 写 `SharedPreferences`。**本轮不为此加限制**，
记录在 §7 未决项里。

### 4.1 按类型分派

| 类型 | 输出形态 |
|---|---|
| `VNull` | `null` |
| `VBoolean` / `VNumber` | 原样（`true` / `42` / `3.14`） |
| `VString` | `"…"`（**必须加引号**，否则 `"1234"` 与 `1234` 分不出） |
| `VList` | `[3项: "a","b","c"]` |
| `VDictionary` | `{k1:v1, k2:v2}` |
| `VCoordinate` | `{x:540, y:1200}` |
| `VCoordinateRegion` | `{left:0, top:0, right:1080, bottom:2400}` |
| `VScreenElement` | `{text:"…", center:"540,1200", id:"…", class:"…"}` |
| `VImage` | `{width:1916, height:2160, size:123456, name:"…"}` |
| `VFile` | `{name:"…", size:1234, mime:"…"}` |
| `VNotification` | `{title:"…", content:"…"}` |
| `VEvent` | `{type:"…", elementId:"…"}` |
| `VUiComponent` | `{type:"…", label:"…"}` |
| 其它 `VObject` | `"<asString()>"` |

### 4.2 ⚠️ 硬性红线：图片 / 文件**绝不打印内容**

`VImage` 与 `VFile` 都有 `base64` 与 `content` 属性（`VImage.kt:107`、
`VFile.kt:60`）——**一律不碰**。这条不是优化，是防炸：

- 一张 1080p PNG 的 base64 约 **2–5 MB**，单条日志就能撑爆 `SharedPreferences`，
  并让 `CrashReportManager` 的上报体积失控；
- `VFile.content` 读的是**整个文件文本**，可能是任意大小的日志/JSON。

⇒ 只取**元数据属性**（width/height/size/name/mime），**永不调用 `base64` / `content`**。
有单测锁住（§8）。

### 4.3 为什么是「手写序列化」而不是 JSON

输出形如 `{state:2, xy:"540,1200"}`（键**不加引号**）而不是合法 JSON：

- 目的是**给人/模型看**，不是给解析器 —— 键加引号会让每行多出十几个字符的噪音，
  而调试时一屏能多看到两三个字段；
- 值**仍必须正确转义**（`"` / `\` / 换行），否则一条日志会被拆成多行
  （需求 §2.3 第 3 条，同意）—— 这个由 §8.1 的第 4 条测试锁住。

⚠️ 注意这与 `VDictionary.asString()`（`VDictionary.kt:26`）**不同**：那个实现
把**所有值**都包上引号且**完全不转义**，嵌引号的文本会直接破坏输出。
本模块不复用它（§4.4）。

### 4.4 ⚠️ 为什么**不**复用 R1 的序列化器

需求 §3.4 要求「必须复用 R1 的序列化器」。**这条前提已不成立** ——
R1 不做，那个序列化器不存在；而且仓库里现有的两处类似物**都不能复用**：

| 现有实现 | 位置 | 为什么不能用 |
|---|---|---|
| `summarizeOutputValue` | `ChatAgentModuleExecutor.kt:2803` | **私有**，且在 `ui/chat/` 包；`core/workflow/module/` **不能依赖 UI 层**（分层倒置） |
| `VObject.asString()` | 各类型实现 | 太弱：`VDictionary.asString()` 把**所有值都加引号**且**不转义**（`VDictionary.kt:26`），嵌引号的文本会直接把序列化输出破坏成非法形态；`VList.asString()` 无项数限制 |

⇒ 本模块**自带一套**，放在一个**零 Android 依赖**的新文件里，可纯 JVM 单测。
`VObject.asString()` 那条「字典不转义」的缺陷由本模块的测试反向锁住（§8）。

---

## 5. 实现方式

### 5.1 上行通路：给 `ExecutionContext` 加日志回调（用户 2026-10-04 定案）

**问题**：模块拿不到写入执行日志的通路。
`WorkflowExecutor` 内部那个 `DebugLogger` 是 **`private object`**（`WorkflowExecutor.kt:70`），
且它靠 `private val currentRootWorkflowId`（ThreadLocal，`:64`）找当前工作流 ——
模块在别的类里读不到这两者中的任何一个。

**方案**：`ExecutionContext` 加一个**带默认值 null** 的字段：

```kotlin
// ExecutionContext.kt —— data class 主构造尾部
/**
 * 执行日志写入回调。
 *
 * ⚠️ 默认 `null` = **不写日志**（与不接之前行为一致）。
 * 只有 `WorkflowExecutor` 会注入它；直调（`ChatAgentModuleExecutor` 的
 * `call_module`）也注入一份，使日志模块在直调时同样有输出（虽然没人看）。
 *
 * ⚠️ 实现方必须**不得阻塞** —— 它会被模块在执行线程上同步调用。
 */
val logSink: ((level: String, tag: String, message: String) -> Unit)? = null,
```

`WorkflowExecutor` 在构造 `initialContext`（`:223`）时注入 ——
**只需一处**：每个步骤的 `executionContext` 是 `initialContext.copy(...)`（`:598`），
`copy` 会带上 `logSink`，不必重复写。

```kotlin
logSink = { level, tag, message -> DebugLogger.logForLevel(level, tag, message) },
```

并在内部 `DebugLogger` 上补一个按字符串级别分派的方法
（`d` / `i` / `w` / `e` 已存在，只是不能按变量选）：

```kotlin
fun logForLevel(level: String, tag: String, message: String) = when (level) {
    "E" -> e(tag, message)
    "W" -> w(tag, message)
    "I" -> i(tag, message)
    else -> d(tag, message)
}
```

⚠️ **为什么不用 `onProgress`**（另两条候选通路的具体后果）：
`onProgress` 的落点是 `[进度] ${module.metadata.name}: ${message}`（`WorkflowExecutor.kt:667`）——
带上 `[进度] 输出日志: ` 前缀；且**每条都调一次 `ExecutionNotificationManager.updateState`**
（`WorkflowExecutor.kt:669`，`Running` 且非子工作流时），
即**每打一条日志就刷一次超级岛 + 一次通知重建**。日志模块可能一次打十几条，这是明显的浪费。

**生产代码里共 3 个构造点**（已核实 `grep "ExecutionContext("`）：

| # | 位置 | 是否注入 | 理由 |
|---|---|---|---|
| 1 | `WorkflowExecutor.kt:223`（`initialContext`，被步骤 `copy` 继承） | ✅ | 主路径 |
| 2 | `ChatAgentModuleExecutor.kt:2043`（`call_module` 直调） | ✅ | 直调时日志模块也要能跑 |
| 3 | `ChatAgentNativeTooling.kt:2191`（屏幕 helper 的上下文） | ❌ | 那里只跑无障碍 helper，不可能出现日志模块 |

⚠️ 新增字段**必须有默认值**，因为全仓 **20+ 处测试**也构造它（`DelayModuleTest` /
`LoopModuleTest` / `VariableResolverTest` 等）。已核实这些**全部用命名参数**，
故尾部加带默认值的参数不会破坏任何调用点。

### 5.2 日志行格式

```
[日志] label="cls.out" {state:2, xy:"540,1200", hit:"K2"}
[日志] {result:42}
[日志] label="warn项" (空)
```

- TAG 用 **`LogModule`**（不是 `WorkflowExecutor`）—— 便于按 TAG 过滤，
  且 `logcat` 调试工具的 TAG 统计能单独看到它们。
- 不加 `[进度] ` 前缀。

### 5.3 `level` 映射

| 输入 | 日志级别 | 落进 `detailedLog` 的行前缀 |
|---|---|---|
| `info` | D | `D/LogModule: ` |
| `warn` | W | `W/LogModule: ` |
| `error` | E | `E/LogModule: ` |

⚠️⚠️ **`error` 级别有一个必须锁住的坑**：
`extractFailureSummary` 与 `hasSkippedFailure`（`ChatMessagePatch.kt:233/252`）的判据是
**「剥掉时间戳后的行首前缀」**：

- `E/WorkflowExecutor: 模块执行失败: `
- `W/WorkflowExecutor: 根据策略，跳过错误继续执行。`

两者**都带 `WorkflowExecutor` 这个 TAG** ⇒ 本模块用 `LogModule` 时**不会误命中**。
但这属于「靠 TAG 恰好不同而成立」的巧合，**必须有测试锁住**：
`E/LogModule: 出错了` 不得让 `temporaryWorkflowStatus` 把「正常完成」判成失败。

这正是本仓库踩过的同类坑（`ChatMessagePatch` 的注释里记着：
用「含 E 行」当判据会把重试成功的执行误判成失败）。

### 5.3.1 ⭐ 与「工作流日志等级」的关系（见 `workflow-log-level-design.md`）

本模块的 `level` 参数（`info`/`warn`/`error`）映射到日志级别 D/W/E，
而**工作流元数据里的「日志等级」是一道更上位的过滤闸**（在
`WorkflowExecutor.appendToLog` 里，按级别决定要不要写进本次执行的 `detailedLog`）。

| 工作流等级 | 日志模块 `info` | `warn` | `error` |
|---|---|---|---|
| 详细（默认） | ✅ 打出 | ✅ | ✅ |
| 精简 | ✅ | ✅ | ✅ |
| 仅警告与错误 | ❌ **被滤掉** | ✅ | ✅ |
| 仅错误 | ❌ | ❌ | ✅ |

⇒ **「把日志模块关掉」不需要本模块提供开关** —— 把工作流设成「仅错误」即可
（本模块默认打 `info`）。用户 2026-10-04 的原始诉求由此满足，故**本模块不加 `off` 级别的配置**。

⚠️ 那条过滤在**执行器内部**，模块拿不到、也不该知道 —— 模块只管按 `level` 打，
「打出来没有」由用户的等级设置决定。这是正确的分层。

### 5.4 风险等级：为什么 `LOW` 而不是 `HIGH`

`ChatAgentModuleExecutor.riskLevelForSavedWorkflow`（`:1174`）取工作流内**所有步骤**的
风险等级的 **max**。若本模块声明 `HIGH`，任何含它的工作流都会被抬到 high risk ⇒
触发人工审批 —— 而此时用户审批的是一个「打印一行字」的操作，与真实风险完全不成比例。

`LOW` 的依据：无权限、无副作用、不写盘（除日志首行）、不联网、不碰 UI。

> ⚠️ 但**日志内容本身可能触及隐私**（用户可能打剪贴板）。这一点在 §7 记录为未决项，
> 不在本轮解决。

---

## 6. 交付物清单

> ⚠️ 本节在**实现完成后**回填过一次（初稿写的名字与实际落地有出入，已逐条对齐）。
> 另有若干处**实现期的偏差**，如实记在表下的「与设计稿的偏差」里。

| 文件 | 类型 |
|---|---|
| `core/workflow/module/data/LogModule.kt` | 新增：模块本体 |
| `core/workflow/module/data/VObjectLogSerializer.kt` | 新增：**纯函数层**（零 Android 依赖，可纯 JVM 单测） |
| `core/execution/ExecutionLogLevel.kt` | 新增：**日志级别**枚举（模块侧只认它，不认识执行器内部的 `"D"/"I"/"W"/"E"` 字面量） |
| `core/execution/ExecutionContext.kt` | 改：加 `logSink` 字段（带默认值，向后兼容） |
| `core/execution/WorkflowExecutor.kt` | 改：`initialContext` 注入 `logSink`；内部 `DebugLogger` 加 `log(level, …)`；新增 `internal fun appendModuleLog`（**唯一**对外口子 —— 模块够不到 `private object DebugLogger`） |
| `ui/chat/ChatAgentModuleExecutor.kt` | 改：直调路径也注入一份 `logSink` |
| `core/workflow/module/ModuleRegistry.kt` | 改：数据段**追加一行**注册 |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | 改：§3.4 的选择器项（**唯一动上游 UI 的地方**） |
| `res/drawable/rounded_log_24.xml` | 新增：图标 |
| `res/values{,-en,-ja}/strings_module.xml` | 改：追加文案（模块名/描述/3 个参数 + 2 个 hint/3 个级别选项/2 个输出 = **12 条 ×3**） |
| `res/values{,-en,-ja}/strings.xml` | 改：追加「全部输出」选择器项文案 `magic_variable_whole_step_output` |
| 测试 **4** 个文件 | `VObjectLogSerializerTest`（17）/ `LogModuleTest`（24）/ `WholeStepOutputContractTest`（5，**新增，设计稿未列**）/ `WorkflowLogLevelTest`（属另一份文档） |

### 6.1 图标

`rounded_log_24`（新增）。⚠️ 不复用 `rounded_add_comment_24`（注释模块的）——
两者都是"无副作用的信息步骤"，图标一样会让用户在编辑器里分不清。
造型：文档 + 三条横线（区别于注释的对话气泡）。

### 6.2 ⚠️ 与设计稿的偏差（实现期发现，逐条如实记录）

| # | 设计稿说的 | 实际落地 | 原因 |
|---|---|---|---|
| 1 | 内部方法叫 `logForLevel` | `DebugLogger.log(level, …)` + 顶层 `internal fun appendModuleLog` | 模块侧**够不到** `private object DebugLogger`（§5.1 自己就写了这一点），必须有一个顶层口子 |
| 2 | §8.3-5 要有 `buildPickerModel` 的**行为**断言 | 改成**源码扫描**（`WholeStepOutputContractTest`） | 真正的链路是 `Fragment → ComposeView → 选择器 → LogModule.execute`，前几段纯 JVM 起不来；而「构造一个 item 再断言它的字段」等于**把要测的那句抄一遍**（改坏了不会红）。扫源码后**反证成立**（把限定条件改成恒 `true` ⇒ 1 条变红） |
| 3 | §3.5 的「空输出提示」是可达分支 | **不可达**（`WorkflowExecutor.kt:751` 只在 `outputs.isNotEmpty()` 时登记该步） | 保留为防御性代码并在 KDoc 注明；**刻意不为它写测试**（恒红的断言会被下一个实现者删掉） |
| 4 | 未提 | `VObjectLogSerializer.centerOfBounds` 抽成独立纯函数 | `android.graphics.Rect` 在纯 JVM 单测里**字段恒为 0**（mockable jar 不执行构造函数体）⇒ 「中心算得对不对」从外面**观察不到**，只能把算术提出来单独测 |
| 5 | §3.3 的「先 resolve 再回填空位」 | 改为**哨兵替换 → 交回 `VariableResolver` → 按 `split` 填回** | ⚠️ **常规路径刻意不自己再跑一遍 `resolve`**：`VariableResolver.kt:133` 在寻址失败时返回 `VObjectFactory.from("{${rawExpression}}")`，会把 `{{nosuchstep}}` 包成 `{{{nosuchstep}}}` 再递归重试，**每层多一对括号**。用户打错一个 id 就会在日志里看到几十个括号的噪声。执行器那条路刻意**不设** `magicVariables`、回落原始串 —— 尊重它的结论即可 |

---

## 7. 未决项（如实记录，不在本轮解决）

| # | 项 | 现状 |
|---|---|---|
| 1 | **敏感数据无脱敏** | 用户/AI 可以把剪贴板、API key 打进日志，而日志进 `SharedPreferences` 并上传友盟。**本轮不做脱敏**（做的话要么误伤、要么挡不住），记录为已知风险 |
| 2 | **循环里打大字符串会使 `vFlowLogPrefs` 膨胀** | `LogManager` 每次 `addLog` 都全量读+解析+写回（`LogManager.kt:74-80`）。50 条 × 每条几 KB 可接受；若单条到 50 KB，一次执行结束就要序列化 ~2.5 MB。**不在本轮解决**（§4.0 定案不截断，代价如实记录在这里） |
| 3 | **日志体积完全无上界** | §4.0 定案不截断之后的直接代价。极端情况（循环里打大对象、`{{cls}}` 打几百项）单条可达几百 KB。**本轮不加限制**（理由见 §4.0：显式打的日志就该完整看到），若将来出问题，应先看是不是「在循环里打大对象」这个用法本身该改 |
| 4 | 整场日志体积控制 | 需求 §2.4 想要的东西（分文件 / 报错豁免 / 重复折叠）。**当前无此约束**（§1 已核实），若将来需要，应作为**独立需求**，不要塞进本模块 |
| 5 | **「日志等级」管不住藏在变量引用里的日志** | ⚠️ 见下。**本轮不做**，但它是「把日志关掉」这个诉求的一个真实漏洞 |

#### ⚠️ 未决项 5 的说明

**「把日志关掉」堵不住「命名变量」这条旁路。**

真机观察到的用法（AI 自己在 `vflow.system.js` 里写的）：

```js
vars_api.setGlobalVar("<某个 key>", JSON.stringify(某个值))   // 脚本把值写进变量
```

⇒ 值被**存成命名变量 / 全局变量**，而不是打进日志。此后：

- 任何步骤都能用 `[[key]]` / `{{vars.key}}` / `{{global.key}}` 把它**读回来**
  （`VariableResolver.resolveExistingVariableObject` 的 `isNamedVariable` 与
  `GLOBAL_VARIABLE_NAMESPACE` 两条分支 **完全不经过步骤执行**，只要求变量存在）；
- ⇒ 即使工作流**没有任何日志模块**、日志等级设成「仅错误」，
  这条通道**照样能把值送出来**。

⚠️ **本设计的所有机制（日志等级、日志模块的 level）都管不住它** ——
它走的根本不是日志路径。**静态扫描「AST 里有没有日志模块」也堵不住**
（值是从变量里读出来的，源码里看不到任何日志相关的东西）。

**本轮不做**，理由：要堵它就得给变量加上「来源标记 + 引用时校验」，
那是**变量系统的改动**，不是日志系统的改动；而且**它本身是一个合法的通用能力**
（脚本往变量里写值 → 后续步骤读回来是正常用法），把它当成漏洞封掉会伤到正经用法。

**结论：如实记录为「日志降噪不是一道安全边界」。** 谁要「绝对不让某个值离开工作流」，
需要对变量系统另做设计 —— 不要指望这里。

（这也是为什么 §2 开头要写清「它只管 detailedLog」：本设计的目标是**降噪与体积**，
**不是**「数据不外泄」。把两件事混为一谈会做出一个既挡不住、又碍事的开关。）

---

## 8. 验收标准

### 8.1 纯函数层（`VObjectLogSerializer`，纯 JVM 可测）

1. 各类型分派逐类型锁定（§4.1 每一行一条用例）；
2. **反向锁：`VImage` / `VFile` 序列化结果中不得出现 base64 或文件内容**
   （喂一个大字符串文件，断言输出里不含它）；
3. **不截断的反向锁**（§4.0）：喂 5000 字符的字符串、50 项的列表、5 层嵌套字典 ⇒
   三者输出都**必须完整**，不得出现 `…` 或 `len=`。
   ⚠️ 这是「谁把截断加回来就变红」的那道闸；
4. **转义**：含 `"` / `\` / 换行的字符串 ⇒ 输出仍是**一行**，
   且把输出里的值段解回后等于原字符串（用 JSON 解析 `"…"` 段来验证，不靠肉眼）；
5. **字典转义的反向锁**：喂 `{"k": "a\"b"}` ⇒ 输出必须与 `VDictionary.asString()`
   的产物**不同**（证明没在复用那个有缺陷的实现）。

### 8.2 模块声明体检（形态照 `ActivityChangedTriggerModuleTest`）

1. id / 分类 / 权限（空）/ `usageScopes` 恰为 `{TEMPORARY_WORKFLOW}`；
2. `riskLevel == LOW`（**反向锁**：不是 `HIGH`，否则整条工作流被抬升）；
3. `content` 参数声明正确（`acceptsMagicVariable = true`、`acceptsNamedVariable = true`）；
4. **不存在 `step_id` / `max_length` 参数**（防止有人把那两个被推翻的设计加回来）；
5. 源码扫描锁注册点（形态照 `CoreDexFingerprintTest`）。

### 8.3 端到端 / 接线锚定

1. **`E/LogModule: …` 不得让 `temporaryWorkflowStatus` 判失败**（§5.3 的坑）；
2. `initialContext`（`WorkflowExecutor.kt`）与直调路径（`ChatAgentModuleExecutor.kt`）
   **都**注入了 `logSink`（源码扫描，形态照 `AgentErrorDialogWiringTest` ——
   ⚠️ 必须**剥注释**后断言，且加防空转断言）；
3. `{{cls}}` 各态：命中 ⇒ 整表；`{{cls.state}}` ⇒ 单值（**不得**被整表分支吃掉）；
   id 不是任何步骤 ⇒ `null`（落回既有行为，不兜底改写）；
   与其它文本混写 ⇒ 逐段替换；
   ⚠️ **反向断言**：`{{index}}`（循环变量）、`{{path}}` 这类**单段但非步骤 id** 的引用
   必须**不**被当成整表 —— 机器化锁住 §3.3 的「判据必须含 `stepOutputs.containsKey`」；
4. **分派不得写进 `VariableResolver`**（源码扫描：`VariableResolver.kt` 的函数体内
   不得出现 `stepOutputs` 的整表返回）—— 机器化锁住 §3.3 的硬约束；
5. **选择器里「全部输出」项的可见性**（§3.4）：编辑 `vflow.data.log` 的 `content` 时，
   `buildPickerModel` 的结果里**必须**有 `variableReference == "{{<该步id>}}"` 的项；
   编辑**别的模块**时必须**没有**（反向锁，防全局放开）。

### 8.4 真机

在**临时工作流**里跑 `vflow.data.log`，经首页「最近日志」或聊天卡片确认
`[日志] …` 行可见（这是需求 §3.5 的核心验收项）。

> ✅ **2026-10-04 用户真机验证通过**（小米 MIX Fold 3 / Android 17）。
> 验证方式是手工在真机上跑工作流并查看日志，**未走 adb 脚本**（写文档时本机
> `adb devices` 为空），故没有逐项取证文件。
> ⚠️ 本行记的是**用户的验证结论**，不是自动化的产物 ——
> 将来若这些路径被改动，按「无自动化覆盖」对待，需重跑 §8.1–§8.3 的单测 + 真机。

---

## 9. 实施顺序

1. `VObjectLogSerializer` + 单测（纯函数，无依赖，先做完做透）；
2. `ExecutionContext.logSink` + `WorkflowExecutor` 注入（**此时还没有消费者**，
   用源码扫描测试先锁住接线存在）；
3. `LogModule` + 注册 + 图标 + 三语文案；
4. `content` 的裸步骤 id（`{{cls}}`）分派（模块内私有解析，不动 `VariableResolver`）；
5. `ChatAgentModuleExecutor` 直调路径的注入（与 2 同批或紧随）；
6. **§3.4 的选择器项** + `FORK.md` 登记（唯一动上游 UI 的地方，单独一步便于回退）；
7. 声明体检测试 + 端到端测试；
8. `./gradlew test` + `./gradlew assembleRelease` + 真机。
