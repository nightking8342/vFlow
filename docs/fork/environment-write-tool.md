# 环境写工具（`update_environment`）设计

> **状态**：设计定稿（2026-10-06），**未实现**。本文只做需求、决策与实现约束，不含代码。
> **上位文档**：`docs/fork/chat-agent-rearchitecture.md`（工具表）、
> `docs/fork/workflow-read-write-tools.md`（`folderId` 校验与「名字不作选择目标」原则）。
> **上游影响**：改动全部落在 fork 已认「手动合并 / 长期分叉」的文件上
> （`ChatAgentToolRegistry.kt` / `ChatAgentModuleExecutor.kt` / `ChatAgentNativeTooling.kt`），
> **不新增上游文件的分歧**。登记条目见 §9（**实施时才落 FORK.md**）。

---

## 1. 背景

当前 AI 侧的工具面是**读得到、写不进去**的一半：

| 能力 | 现状 | 本设计是否补 |
|---|---|---|
| 读文件夹 | ✅ `get_environment`（**只给名字 + 计数**） | 补 `id`（决策 2） |
| 读全局变量 | ✅ `get_environment`（只给名字 + 类型，**不给值**） | 不动 |
| 写工作流 / 改工作流 | ✅ `save_workflow` / `update_workflow` | 不动 |
| 建 / 删文件夹 | ❌ 完全没有 | ✅ 建 / 改名 / 解散 |
| 建 / 删全局变量 | ❌ 完全没有 | ✅ 建 / 删 |

而且这里有一个**已经断掉的引用链**：`update_workflow.metadata.folderId` 与
`save_workflow.folderId` **只收 id**（`ChatAgentModuleExecutor.kt:1420-1437` 显式校验，
未知 id 直接报错，因为工作流会从列表上消失），而模型**唯一**的信息来源
`get_environment` 只输出名字 ⇒ 模型拿不到 id，就永远走不通「把工作流放进文件夹」这条需求。

**本设计要解决的就是这条链**，顺带补齐文件夹与全局变量的写入能力。

---

## 2. 本轮决策

> 全部已定案（2026-10-06）。实施时以本节为准。

| # | 决策 | 理由 |
|---|---|---|
| 1 | **给 delete** | 写工具同时提供删除能力：文件夹走「**解散**」语义、全局变量走删除 |
| 2 | **补 ID** | `get_environment` 的 folders 段**补上 `id`** |
| 3 | **去掉** | `get_environment` **去掉** `under <父文件夹>` 那一行（及一切 `parentId` 展示） |
| 4 | **拒绝** | 任何传入 `parentId` / 嵌套语义的调用**显式拒绝**（不是静默忽略） |
| 5 | 全局变量 | **新增时可给值，但不提供「修改」**（同名已存在 ⇒ 拒绝） |
| 6 | 文件夹改名 | **支持 `rename_folder`**（改名不动 `folderId` ⇒ 不打断任何引用，完全可逆） |
| 7 | **拒绝重名** | `create_folder` / `rename_folder` 都不允许与既有文件夹重名（UI 允许重名，AI 侧不跟——同名会让 `list_workflows` 的按名筛选变歧义） |
| 8 | **没有嵌套** | 本 App **不存在**嵌套文件夹（UI 无入口）。相关处理一律按「防御性、正常数据不会命中」对待，**不得**按缺陷去修 UI（用户 2026-10-06 定） |

### 2.1 决策 1 的精确边界：只做「解散」，不做「删除」

UI 侧（`WorkflowListRoute.kt`）在 2026-10-05 把「删除文件夹」拆成了两个动作：

| UI 动作 | 语义 | 本工具是否暴露 |
|---|---|---|
| **解散**（`showDissolveFolderConfirmationDialog`） | 删掉文件夹本身，里面的工作流 `folderId = null` **回到根目录** | ✅ **只暴露这个** |
| **删除**（`showDeleteFolderConfirmationDialog`） | 文件夹**连同里面的工作流**一起删 | ❌ **不暴露** |

理由：本项目**没有版本历史、没有撤销**。让 AI 有能力一次抹掉用户全部工作流，
和「用户自己点两次确认」不是同一个风险量级。工具名用
`dissolve_folder`（而不是 `delete_folder`），从命名上就杜绝「模型以为等价于 UI 的删除」。

### 2.2 决策 5 的精确边界：新增 ≠ 覆盖

⚠️ **这不是参数差别，是必须显式写检查的逻辑**：

`GlobalVariableStore.put(context, key, value)` 是 **upsert**（`GlobalVariableStore.kt:38-42`）——
存进去就叫「修改成功」。若工具直接调 `put`，模型一次误调就会**静默改掉用户已有的值**，
而且返回 `success`。故工具层必须：

1. `GlobalVariableStore.getAll(appContext).containsKey(name)` ⇒ 已存在则**拒绝**；
2. 错误文案里给出出路：改用 `delete_global_variable` 删除后重建，
   或让用户到设置页 (`GlobalVariableConfigActivity`) 修改。

**「先删再建」是允许的**——它有两次显式操作、两次工具结果留痕，与「一次 `put` 悄悄改掉」
在可追溯性上完全不同。

### 2.3 决策 6 的精确边界：改名，不是重建

`rename_folder` 改的是 `WorkflowFolder.name`，**`id` 一个字节都不动**。

这决定了两件事：

- **不打断任何引用**：工作流的归属是 `workflow.folderId` → 文件夹 `id`，
  与名字无关。改名后工作流仍在同一个文件夹里，且没有任何下游需要重新指向。
  ⇒ 它是本工具里**唯一无条件可逆**的写操作（改回原名即完全复原，
  且**不触碰工作流**——`create_folder` 也可逆，代价是「解散」要动工作流）。
- **与 `list_workflows` 的按名筛选的关系**：那条筛选是 `folderName.equals(folder, true)`
  （`ChatAgentModuleExecutor.kt:2425-2428`）—— 改名字**会让此前基于旧名的筛选失效**，
  但那是调用方（模型）自己的查询习惯，不是数据损坏。唯一要防的是**改成同名**
  （会让按名筛选变歧义），故与 `create_folder` 同一条规则：**新名字已存在（且不是自己）⇒ 拒绝**。

---

## 3. 现状核对（写文档时逐条读源码确认，非推断）

| 事实 | 位置 |
|---|---|
| `get_environment` 输出只有 `folder.name` + 计数，无 id | `ChatAgentModuleExecutor.kt:2507-2514` |
| `folderId` 写路径**只收 id**，且未知 id 硬报错 | `ChatAgentModuleExecutor.kt:1420-1437` |
| 工作流列表渲染只认「命中已存在文件夹」与 `folderId == null`；未知 id ⇒ 工作流**从列表消失** | `WorkflowListRoute.kt:142-180` |
| `FolderManager` 只有 `saveFolder` / `getFolder` / `deleteFolder` / `getAllFolders`，**没有**「把工作流移出去」的方法（那是调用方自己 `saveWorkflow(copy(folderId = null))`） | `FolderManager.kt` |
| UI 的「解散」**只处理工作流**，不处理子文件夹——⚠️ **但本 App 没有嵌套文件夹**（决策 8），UI 无创建入口、`parentId` 实际恒为 `null`，故**这不构成缺陷** | `WorkflowListRoute.kt:975-984` |
| `WorkflowFolder.parentId` 是**为未来预留**的字段，当前所有创建路径都留空 | `WorkflowFolder.kt:11`；`WorkflowListRoute.kt:877` 新建时不传 |
| UI 允许**重名**文件夹（无查重），且 UI 的重命名也不查重 | `WorkflowListRoute.kt:874-877`、`:903-906` |
| `list_workflows` 的 `folder` 筛选是 `ignoreCase` 的按名匹配 | `ChatAgentModuleExecutor.kt:2425-2428` |
| 全局变量只支持 `string` / `number` / `boolean`；其余 `VObject` 存储层一律压成字符串 | `GlobalVariableStore.kt:64-71` |
| 全局变量的**值**是字面量，不走 `{{...}}` 模板解析（设置页只能填纯文本/数字/布尔） | `GlobalVariableConfigActivity.kt:194-197` |
| `get_environment` / `GlobalVariableStore` **目前没有任何写入口**被 AI 侧调用 | `ChatAgentModuleExecutor.kt:2494` 是唯一读取点 |
| `prepareBatch` 会被 `shouldAutoApproveToolCalls` 调用 ⇒ **审批之前**就跑 | `ChatViewModel.kt:1050-1058` |
| `vflow.agent.*` 这些 moduleId **不是**注册模块（`ModuleRegistry` 里没有），只是工具标签 | 全仓 grep 无对应模块类 |

---

## 4. 设计

### 4.1 工具形态

**一个**工具、**一次调用一个操作**：

```
name:      vflow_agent_update_environment
title:     修改用户环境
moduleId:  vflow.agent.update_environment
backend:   ChatAgentToolBackend.UPDATE_ENVIRONMENT   （新增枚举值）
riskLevel: 按 operation 动态计算（见 §4.4）
usageScopes: { DIRECT_TOOL }
truncatable: false   （输出是操作回执，短且**尾部有意义**——操作名、id、受影响条数都在末行）
```

与 `update_workflow` 同形：**一个工具承载若干原语**，靠 `operation` 分派。
不做「一次调用多个操作」的批量形态——单操作让校验、错误定位、审批粒度都简单一档；
将来确有需要（如「建文件夹 + 把工作流放进去」）再扩，**不预先设计**。

### 4.2 入参 schema

```jsonc
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "operation": {
      "type": "string",
      "enum": ["create_folder", "rename_folder", "dissolve_folder",
               "create_global_variable", "delete_global_variable"]
    },

    // create_folder / create_global_variable
    "name":  { "type": "string", "description": "New folder name / global variable name." },

    // rename_folder / dissolve_folder
    "folder_id": { "type": "string", "description": "Folder id from get_environment." },

    // rename_folder
    "new_name": { "type": "string", "description": "New folder name." },

    // create_global_variable
    "value": { "type": "string", "description": "Initial value as a literal. Not a {{template}}." },
    "type":  { "type": "string", "enum": ["string", "number", "boolean"] }
  },
  "required": ["operation"]
}
```

> `type` 刻意**不给默认值**，且执行层把它当**必填**（§4.3）：
> 字段级 `default` 会让模型倾向于**省略**它；而省略时「它想要什么类型」就无从判断，
> 只能我方替它猜一个。让它必须从 enum 里选，选错也看得见。

**刻意不做的两件事**：

1. **不出现 `parent_id` 字段**（决策 4）。schema 里根本没有这个键；
   `additionalProperties: false` 之外，执行层再按 `update_workflow` 的同款
   **显式白名单**逐键校验（`ChatAgentModuleExecutor.kt:1555-1567` 是现成范式）——
   **schema 是给模型看的建议，不是执行层的屏障**，光靠它拦不住手写 JSON 的模型。
2. **不做 `oneOf` 条件 schema**。本仓库的 schema 是手搓 `buildJsonObject`，
   条件校验一律走运行时：每个 `operation` 只认自己那几个字段，多给/少给都报错，
   错误里说明「`dissolve_folder` 需要 `folder_id`」。

> `required` 只放 `operation`：其余字段的必填性**按 operation 判断**，
> 是真必填而不是「声明成必填」。

### 4.3 各操作的语义与校验

#### `create_folder`

- **入参**：`name`（trim 后非空）。
- **校验**：
  - 空 / 全空白 ⇒ 拒绝（对齐 UI 的 `toast_folder_name_empty`）。
  - **同名已存在 ⇒ 拒绝（决策 7）**，错误里附上已存在的 `id`。
    > 为什么这里比 UI 严：① `list_workflows` 的 `folder` 筛选是**按名字**匹配的
    > （`ChatAgentModuleExecutor.kt:2425-2428`），同名会让该路径变歧义；
    > ② 同名通常是「模型忘了自己刚建过」，而附 id 的错误恰好是**自愈**的——
    > 模型读到 id 就能直接用。UI 允许重名这件事**不在本工具的对齐范围内**。
    > ⚠️ 大小写差异**也算重名**：判据用 `equals(name, ignoreCase = true)`
    > （与 `list_workflows` 那条筛选的 `ignoreCase = true` 对齐），
    > 否则「自动化」与「自动化」(大小写不同) 仍会让按名筛选变歧义。
- **落盘**：`FolderManager.saveFolder(WorkflowFolder(name = name))`。
  不传 `parentId`（保持默认 `null`）、不传 `order`（默认 0，与 UI 的新建一致）。
- **输出**：`Created folder "<name>" (id: <uuid>).`

#### `rename_folder`

- **入参**：`folder_id`（只收 id）、`new_name`（trim 后非空）。
- **校验**：
  - `folder_id` 不存在 ⇒ 拒绝 + 真实 id 列表（同 §4.3 其余操作）。
  - `new_name` 空白 ⇒ 拒绝。
  - `new_name` 已被**另一个**文件夹占用 ⇒ 拒绝（决策 7，判据同 `create_folder` 的
    **忽略大小写**）。
    ⚠️ **判断必须排除自己**：`getAllFolders().any { it.id != folderId && it.name.equals(newName, true) }`
    —— 写成 `any { it.name == newName }` 会让「把名字改成自己当前的名字」被**自己**挡住
    （报错说「名字已存在」，指向错误方向）。
    ⚠️ **判据的两半方向相反，别只测一半**：忽略大小写（决策 7）与排除自己（上面这条）
    合起来才有下面这个**看起来矛盾**的正确行为 ——
    既有 `{A}` 时：新建 `"a"` ⇒ 拒绝；把 `A` 改名成 `"a"` ⇒ **允许**（用户修大小写是合理需求）。
    只测前一条会把实现推向「一律拒绝」，只测后一条会推向「一律放行」。
- **落盘**：`folderManager.saveFolder(existing.copy(name = newName))` —— 照
  `WorkflowListRoute.kt:906` 的既有写法（`saveFolder` 按 id 覆盖，就是 upsert 的正确用法）。
  **只改 `name`**：`parentId` / `order` / `id` / `createdAt` 全部保留。
- **输出**：`Renamed folder "<old>" -> "<new>".`
  （**必须报出旧名**：模型可能在多轮之间改了别的文件夹，回执里只有新名它无法确认改的是哪一个。）

#### `dissolve_folder`

- **入参**：`folder_id`（**只收 id，不收名字**）。
  > 与 `update_workflow.folderId` 同一条原则：名字不作选择目标。
  > 且这里比 `folderId` 更硬——改错一个工作流的归属是可修的，删错一个文件夹不可修。
- **校验**：
  - `folder_id` 不存在 ⇒ 拒绝，附**真实文件夹 id 列表**（照
    `ChatAgentModuleExecutor.kt:1428-1434` 的同款自愈文案）。
  - **若该文件夹有子文件夹 ⇒ 拒绝**（形式化防御，**正常数据不会命中**，见下）。
    > ⚠️ **本 App 不存在嵌套文件夹（决策 8）** —— UI 没有任何创建嵌套的入口。
    > `WorkflowFolder.parentId` 只是一个**为未来留的、当前恒为 `null`** 的字段。
    > 因此这一条**不是**在修一个缺陷，而是**防御性**的：若哪天数据里真出现了子文件夹
    > （导入、外部改 prefs、将来加了嵌套 UI），AI 侧的「解散」会留下悬空的 `parentId`。
    > **代价极低**（一次 `parentId` 计数），**不必**为它做更多。
    > ⇒ **不要**因此去改 UI 的「解散」（用户的判断：那不算缺陷）。
- **执行（必须放在 execute 阶段）**：
  1. `workflowManager.getAllWorkflows().filter { it.folderId == folderId }`
     → 逐个 `saveWorkflow(copy(folderId = null))`；
  2. `folderManager.deleteFolder(folderId)`。
  顺序不可反：先删文件夹会让第 1 步的筛选结果仍能拿到（数据在内存），
  但中途崩溃就会留下「文件夹没了、工作流还指着它」的状态。
- **输出**：`Dissolved folder "<name>". N workflow(s) moved to the root.`

#### `create_global_variable`

- **入参**：`name`（trim 非空）、`value`（字面量字符串）、
  `type`（`string` / `number` / `boolean`，**必填**——schema 里刻意不给 `default`，见 §4.2）。
- **校验**：
  - 空名 ⇒ 拒绝。
  - **同名已存在 ⇒ 拒绝**（见 §2.2）。
  - `type` 缺失或不在三个值里 ⇒ 拒绝（存储层只认这三种，见 §3）。
  - `type = number` 而 `value` 解析不出数字 ⇒ 拒绝（对齐
    `GlobalVariableConfigActivity.kt` 的 `toDoubleOrNull` 分支）。
  - `type = boolean` ⇒ `value` 必须能解释成 `true`/`false`，否则拒绝。
- **值按字面量处理，不做模板解析**——与设置页一致；
  务必写进字段 `description`，否则模型会写 `"{{now.date}}"` 并期待它被执行时求值。
- **落盘**：`GlobalVariableStore.put(appContext, name, VString/VNumber/VBoolean)`。
- **输出**：`Created global variable "<name>" (<type>).` —— 类型**按实际写入的那个报**，
  不写死 `string`（模型据此核对它要的类型有没有落对）。

#### `delete_global_variable`

- **入参**：`name`。
  > ⚠️ 这是本工具里**唯一按名字定位的写操作**——全局变量的存储键**就是名字**
  > （`GlobalVariableStore` 无 id 概念），没有第二个选择。
  > 故要求该名字必须来自 `get_environment` 的输出，并在 `description` 里写死这句。
- **校验**：不存在 ⇒ 拒绝并列出真实名字（同样给自愈路径）。
- **落盘**：`GlobalVariableStore.remove`。
- **输出**：附**受影响工作流计数**（见 §4.5）。

### 4.4 风险等级与审批

`ChatAgentToolDefinition.riskLevel` 是**静态字段**（一个工具一个值），
但本工具的五个操作风险不同。做法与 `update_workflow` 一致：
**按运行时 `operation` 计算，填进 prepared item 的 `riskLevel`**，
由 `riskLevelOf()` 取用（`ChatAgentModuleExecutor.kt:405-414` 已是这个结构）。

| operation | riskLevel | 说明 |
|---|---|---|
| `create_folder` | `LOW` | 建一个空文件夹，无破坏性 |
| `create_global_variable` | `LOW` | 同名会被拒 ⇒ 不改动任何既有数据 |
| `rename_folder` | `LOW` | **完全可逆**（`id` 不动、引用不断，见 §2.3），且改名本身不改任何数据归属 |
| `dissolve_folder` | `STANDARD` | 有破坏性（工作流从文件夹里出来，归位信息丢失）；不应静默执行 |
| `delete_global_variable` | `STANDARD` | 可能静默打断既有工作流的引用（见 §4.5） |

> ⚠️ 别把删除类操作标 `HIGH`：`ChatAgentToolRiskLevel` 的档位与
> `ChatToolAutoApprovalScope` 一一对应，标 `HIGH` 等于「除非开 ALL 否则永远弹窗」——
> 而用户审批的是「删一个空文件夹」，与提示不成比例（本仓库在 `LogModule` 上
> 记过一模一样的教训：`riskLevel = LOW` 而不是 `HIGH`，
> 见 `core/workflow/module/data/LogModule.kt:61-72`）。

### 4.5 `delete_global_variable` 的引用扫描（**P1，不在本批**）

删掉一个全局变量后，既有工作流里的 `{{global.x}}` 会**静默**变成字面量
（`VariableResolver.kt:133` 的兜底：解析不到就原样返回 `{global.x}`），
用户看到的是「工作流跑起来了但值不对」。

**理想做法**：执行前扫一遍所有工作流的步骤参数，统计引用该名字的工作流与名字，
写进工具结果（形如 `Warning: 3 workflow(s) still reference {{global.deleted}}: A, B, C.`）
—— 与 `update_workflow` 的 `warnings` 字段同款语义（「改动成功但用户需要知道」）。

⚠️ **为什么本批不做**（写文档时逐条核实过，没有现成的遍历器）：

| 需要的能力 | 现状 |
|---|---|
| 遍历工作流全部步骤参数 | 有**一份**：`SecretFieldScrubber.forEachStepParameter`（`core/backup/SecretFieldScrubber.kt:151`），但它是 **`private`**，且食入 **`JsonArray`**（备份管线形状），不是 `Workflow` 对象 |
| 识别 `{{global.<name>}}` | 有：`TemplateParser(str).parse()` 取 `TemplateSegment.Variable`，再判 `path.first() == VariablePathParser.GLOBAL_VARIABLE_NAMESPACE` 且 `path.getOrNull(1) == name` |
| 深入**集合型**参数 | ❌ **没有**。参数值可以是 `List<Map<String, String>>`（`ChooseFromMenuModule.toParameters`、`SwitchModule.toParameters`），全局变量引用可能埋在里层；`SecretFieldScrubber` 对它只处理裸 `String` 值 |

⇒ 要做得**不漏**，得新写一个「递归下钻参数值（String / List / Map）」的遍历器。
这超出「顺带做」的量级，故：

- **P0 只做**：输出里写死一句
  `Existing {{global.<name>}} references in workflows will no longer resolve.`
  —— 保证**不静默**，但不声称扫过。
- **P1 再做**：真正的引用扫描（新遍历器 + 「N 个受影响工作流」的清单）。
  那时应把遍历器放在 `core/workflow/` 下（纯 Kotlin，可单测），
  **不要**复制 `SecretFieldScrubber` 那份 JsonArray 版（两份遍历器迟早漂移，
  本仓库在 logcat 的 app/core 双份实现上记过这个代价）。

⚠️ 无论做不做，工具 `description` 里都要写清「删除会打断引用」——
模型据此才可能在删之前先问一句。

---

## 5. 静默失效点清单

本仓库判定「静默失效」的标准是：**改错了不报错、只静默变差**。本设计共 7 处：

1. ⚠️⚠️ **把写操作放在 prepare 阶段** —— `prepareBatch` 会被
   `shouldAutoApproveToolCalls` 调用（`ChatViewModel.kt:1050-1058`），也就是**用户还没点批准时就跑**。
   若 `prepareUpdateEnvironment` 里直接落盘 ⇒ **审批形同虚设**，
   而且 `previewRisk`（拿 risk 决定要不要弹窗）本身就会触发这次写。
   ⇒ **写必须在 `executeBatch` 阶段**，prepare 只做校验 + 组装。

2. ⚠️⚠️ **`create_global_variable` 直接调 `GlobalVariableStore.put`** —— 它是 upsert，
   同名会被**静默覆盖**，返回 `success`。必须显式查存在性并拒绝（§2.2）。

3. ⚠️ **`dissolve_folder` 只置空工作流的 `folderId`、漏了子文件夹** ——
   子文件夹的 `parentId` 会指向一个不存在的 id。
   ⚠️ **正常数据不会命中**（本 App 没有嵌套，`parentId` 恒为 `null`，决策 8），
   故这是**防御性**检查而非缺陷修复。⇒ 执行前显式拒绝有子文件夹的情形，
   不做「尽力而为」的遍历保护（那要处理任意深度的树，为一个不会发生的场景付太多成本）。

4. ⚠️ **`dissolve_folder` 按名字定位** —— 若实现里接受 `folder.name`，
   同名文件夹会**随机删掉一个**。必须只收 `folder_id`（§4.3）。

5. ⚠️ **`parentId` 靠 schema 拦** —— `additionalProperties: false` 是**给模型的建议**，
   不是执行层的屏障。必须有**显式白名单**逐键校验（照 `update_workflow` 的
   `"name", "description", "folderId", ... ->` 分支），否则模型塞 `parent_id` 会被静默忽略，
   而它以为嵌套建成了。

6. ⚠️ **`get_environment` 补了 id 但没改 `list_workflows` 的 `folder` 筛选** ——
   后者仍按**名字**匹配。两条读路径的口径不一致本身不是 bug，但**描述里必须写明**：
   `list_workflows` 的 `folder` 是筛选用的名字，写路径要的是 `get_environment` 给的 id。
   否则模型会拿 `list_workflows` 输出里的名字去填 `folderId`，撞上 §4.3 的硬报错。

7. ⚠️ **`rename_folder` 的「同名」判据漏了排除自己** —— 写成
   `any { it.name.equals(newName, true) }` 时，「改名成当前名」会被**自己**挡住
   （报错说「名字已存在」，指向错误方向）。
   ⇒ 判据必须是 `any { it.id != folderId && it.name.equals(newName, true) }`，
   且**有反向锁**（§7 反向锁 ②）。
   ⚠️ 同类坑：若日后有人把「同名拒绝」实现成 `existingFolders.size > 1` 之类的间接判据，
   在多文件夹场景会**放行重名**——判据必须直接比名字。

---

## 6. 实现清单

> 全部落在 fork 已认「手动合并 / 长期分叉」的文件上，**不新增上游分歧面**。

| 文件 | 改动 |
|---|---|
| `ui/chat/ChatAgentToolRegistry.kt` | 新增 `CHAT_UPDATE_ENVIRONMENT_TOOL_NAME` / `_MODULE_ID` 两个常量；新增 `buildUpdateEnvironmentToolDefinition()`（**description 必须写清三点**：写文件夹要的是 `id` 不是名字、删全局变量会打断引用、**文件夹不能重名**）+ `buildUpdateEnvironmentSchema()`；`toolsByName` 注册 1 项 |
| `ui/chat/ChatAgentNativeTooling.kt` | `ChatAgentToolBackend` 追加 `UPDATE_ENVIRONMENT`（1 行） |
| `ui/chat/ChatAgentModuleExecutor.kt` | ① `ChatPreparedToolItem` 追加 `UpdateEnvironment` 子类（含 `plan` / `validationErrors` / `riskLevel`）；② **补齐 6 处穷尽 `when`**（`ChatPreparedToolItem` 是 `sealed interface`，漏一处**编译不过**，这是本改动最省心的一半）：`prepareBatch` 的 missingPermissions（`:218`）/ `executeBatch`（`:250`）/ `buildPermissionRequiredResults`（`:330`）/ `riskLevelOf`（`:406`）/ `describeForLog`（`:2239`）/ `prepareTemporaryWorkflow` 的嵌套 `when`（`:642`，返回「环境写操作不能嵌在临时工作流里」）。⚠️ **`buildRejectedResults`（`:311`）不需要加**——它只吃 `List<ChatToolCall>`、不看 prepared item；盲目加一档会显得像漏了什么；③ `prepareToolCall`（`:416`）加分流；④ 新增 `prepareUpdateEnvironment`（**纯校验**）/ `parseEnvironmentOperation` / `executeUpdateEnvironment`（**落盘**）/ `buildUpdateEnvironmentResultText` |
| `docs/fork/chat-agent-rearchitecture.md` | 工具表加一行。⚠️ **该文档的「18」是旧数**（写文档时实测：常驻表 = 9 个工作流工具 + 11 个 screen helper = **20**），更新时**顺手改正**，不要沿用文档里的旧数再加一 |
| `FORK.md` | 登记条目（见 §9） |

**建议的纯函数切分**（可纯 JVM 单测，不依赖 Android）：

- `parseEnvironmentOperation(arguments): EnvironmentOperation?` —— 键白名单 + 按 operation 取字段 + 校验；
- `describeEnvironmentOperation(plan): String` —— 回执文本；
- 全局变量的 `type` → `VObject` 构造（`string`/`number`/`boolean` 三分支）；
- **存在性判定与「同名」判定**抽成纯函数（`validateCreateFolder(name, existingFolders)` /
  `validateRenameFolder(id, newName, existingFolders)` / `validateCreateGlobal(name, existingKeys)`），
  **食入数据、不食入 Manager** —— 这样 §7 那几条反向锁（同名必须拒绝、改名要排除自己）
  才能在纯 JVM 里喂假数据测，不必起 Android 环境。

`FolderManager` / `GlobalVariableStore` / `WorkflowManager` 的调用留在
`executeUpdateEnvironment` 里（那部分不可单测，靠源码扫描型接线测试锁）。

---

## 7. 测试清单

| 测试 | 类型 | 重点 |
|---|---|---|
| `EnvironmentOperationParseTest`（新增） | 纯函数 | 键白名单（**`parent_id` 必须被拒**）、按 operation 的必填校验、`name`/`new_name` 空白、`type` 越界、`number` 解析失败、`boolean` 解析失败、未知 `operation` |
| `EnvironmentOperationParseTest`（反向锁 ①） | 纯函数 | **`create_global_variable` 遇到已存在的名字必须回错，绝不能回「成功」** —— 喂一个已存在的名字，断言结果是拒绝（这条直接锁 §2.2 的 upsert 陷阱） |
| `EnvironmentOperationParseTest`（反向锁 ②） | 纯函数 | **`rename_folder` 改同名不能被自己挡住** —— 只有一个文件夹 `A`，`folder_id = A.id`、`new_name = "A"` ⇒ 结果**不能**是「名字已存在」。⚠️ 这条锁的是 §4.3 里 `it.id != folderId` 那个排除条件；漏了它，用户会收到指向错误方向的报错 |
| `EnvironmentOperationParseTest`（大小写） | 纯函数 | **重名判定必须忽略大小写（决策 7）** —— 既有文件夹 `A`，新建 `"a"` ⇒ **拒绝**。⚠️ 反向锁：改成 `equals(ignoreCase = false)` 时这条必须变红 |
| `EnvironmentOperationParseTest`（大小写 · 自改） | 纯函数 | ⚠️ **只有自己时改大小写要放行** —— 只有一个文件夹 `A`，把 `A` 改名为 `"a"` ⇒ **允许**（排除自己后没有冲突；用户想修大小写是合理需求）。这条与上一条**方向相反**，正是「排除自己」那一半的锁：把判据写成不排除自己，它会变红 |
| `UpdateEnvironmentWiringTest`（新增） | 源码扫描 | 存在理由同 `AgentErrorDialogWiringTest`：**纯函数全绿也测不出「写操作被放进了 prepare」**。断言：`prepareUpdateEnvironment` 函数体内**不出现** `FolderManager(appContext).saveFolder` / `deleteFolder` / `GlobalVariableStore.put` / `remove` / `WorkflowManager(...).saveWorkflow`（剥注释后再断言 + 防空转）；这些调用**只出现在 `executeUpdateEnvironment`**。⚠️ **不锁「6 处 `when` 都齐」**——`sealed interface` 漏一处**编译不过**，那是编译器的事，不是测试的事；写进去只会变成一条恒绿的装饰 |
| `UpdateEnvironmentWiringTest`（反向锁） | 源码扫描 | `get_environment` 的 folders 段**含 `folder.id`**、**不含 `parentId`**（决策 2 / 3）。⚠️ 剥注释后断言，且判据指向**代码表达式**而非散文（中文注释里就写着「父文件夹」） |

> ⚠️ **反证必须实际做**：每条扫描断言改回缺陷版本确认变红。本仓库已记录过
> 「断言自己写的字面量 ⇒ 永远不会红」的教训（`ShortcutPickerFallbackTest`）。

---

## 8. 未决项

1. **`delete_global_variable` 的引用扫描**已定 P1（§4.5，缺递归遍历器）。
2. 工具名 `update_environment` vs `manage_environment`：
   倾向 `update_environment`，与读工具 `get_environment` 成对。

> 已定案、不再是未决项的：**拒绝同名**（决策 7）、**没有嵌套**（决策 8，
> 包括「UI 解散不处理子文件夹」——**那不是缺陷，不要去修**）。

---

## 9. FORK.md 登记条目（草稿）

> ⚠️ **本设计尚未实现，FORK.md 此刻不要写入** —— 登记的是「与上游的分歧」，
> 而分歧只有落到代码上才存在。实施那一步再逐行加进 `FORK.md` 的对应表。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `docs/fork/environment-write-tool.md`（新增） | fork 独有：本设计。上游无此文件 | **我方** |
| `ui/chat/ChatAgentToolRegistry.kt`（改） | 新增 `update_environment` 工具（1 个常量对 + schema + 定义 + `toolsByName` 注册 1 项）：文件夹建 / 改名 / 解散 + 全局变量建 / 删。**不暴露** `parentId`（本 App 无嵌套文件夹），**不暴露**「连同工作流一起删文件夹」，**拒绝重名**（决策 7） | **我方**（认长期分叉） |
| `ui/chat/ChatAgentNativeTooling.kt`（改） | `ChatAgentToolBackend` 追加 `UPDATE_ENVIRONMENT`（1 行） | **手动合并**（追加枚举值） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改） | `ChatPreparedToolItem` 追加 `UpdateEnvironment` 子类 + 补齐 6 处穷尽 `when`（编译器强制）+ `prepareUpdateEnvironment`（**纯校验，不落盘**）/ `executeUpdateEnvironment`（落盘）。⚠️ 写操作**必须**在 execute 阶段：`prepareBatch` 会被 `shouldAutoApproveToolCalls` 在**审批前**调用，写在 prepare 等于绕过审批 | **手动合并** |
| `ui/chat/ChatAgentModuleExecutor.kt`（改，`get_environment` 输出） | folders 段补 `folder.id`、去掉 `under <父文件夹>` 行（决策 2/3）。**理由不是可读性**：`save_workflow` / `update_workflow` 的 `folderId` 只收 id，而此前模型唯一的文件夹信息来源只有名字 ⇒ 「把工作流放进文件夹」这条需求**走不通** | **手动合并**（1 段字符串拼接） |
| `test/.../ui/chat/EnvironmentOperationParseTest.kt`、`UpdateEnvironmentWiringTest.kt`（新增） | fork 独有：纯函数语义 + 源码扫描型接线锚定（锁「写不在 prepare 阶段」） | **我方** |

---

## 10. 真机验证清单（**全部未做**）

⚠️ 本任务禁止触碰真机，下列均**只有编译与单测支撑，不得声称可用**：

1. 新建文件夹后，工作流列表页（返回该页触发 `LaunchedEffect(isActive)` → `loadData`）
   是否**立刻**出现新文件夹；
2. 改名后：Tab 栏显示新名，**里面的工作流一条不少**（§2.3 的核心 —— 改名不动 id）；
3. 解散文件夹后，里面的工作流是否出现在根目录、且数量正确；
4. 新建文件夹时**给一个已存在的名字**（含仅大小写不同）⇒ 确认被拒绝、且**没有建出第二个**；
5. 新建全局变量后，设置页「全局变量」是否显示新条目、值是否正确；
6. 新建全局变量时**给一个已存在的名字** ⇒ 确认被拒绝、且**用户原有的值没有被改动**
   （§2.2 的核心，**这条最值得亲手验**：它是全工具唯一能「静默改坏用户数据」的路径）；
7. 删除全局变量后，引用它的工作流运行结果（预期：`{{global.x}}` 变成字面量，不报错）；
8. 模型传 `parent_id` ⇒ 确认被显式拒绝并有可读错误；
9. 审批链路：`create_folder` / `rename_folder`（LOW）在 LOW_RISK 自动批准档下**不弹窗**、
   `dissolve_folder`（STANDARD）**要**弹窗；批内混合时按 max 走。
