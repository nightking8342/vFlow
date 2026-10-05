# Switch 模块设计（`vflow.logic.switch.*`）

> **状态**：设计定稿，**未实现** ｜ **日期**：2026-10-05
> **目录归属**：fork 独有（以新增文件为主，见 §6 的实现清单与冲突归属）
> **用途**：按一个值的**相等匹配**，在多条分支中选一条执行
> **相关**：`surveys/`（触发器等现状梳理目录）、`docs/fork/log-module-design.md`（同类「新增模块」设计的体例先例）

---

## 0. 需求与定案

一句话：给工作流加一个「多路分支」块——把一个值和若干候选值逐一比较，命中哪条就走哪条。

**用户已拍板的四条**（2026-10-05）：

| # | 决策 | 说明 |
|---|---|---|
| 1 | **匹配方式 = 相等匹配** | 不做范围匹配、不做正则分支、不做条件分支 |
| 2 | **没有 `break`** | 每个分支执行完自动跳出整个块，**不存在**「穿透到下一个分支」的语义 |
| 3 | **不支持多值** | 一个 Case 只带**一个**匹配值，不写 `case 1, 2, 3:` |
| 4 | **名字直接用英文 `Switch` / `Case`** | 中文没有成熟且广泛的译名（见 §1.3），不硬翻 |
| 5 | **分支在 Switch 卡片的 sheet 里集中管理** | 即 §7.1 的方案 C；卡片上不放 `[＋]` |

### 0.1 与「穿透」的关系（为什么不要 break 反而是好事）

编程里的 `break` 是为「有意穿透」（fall-through）服务的，而**穿透是 C 系语言里最著名的坑之一**。
去掉它之后，一个分支卡片 = 一个互斥的执行段，用户不需要理解「不加 break 会继续往下跑」。
本项目的块模块（If/Loop）也**没有** fall-through 概念，去掉 break 让 Switch 与它们保持一致的心智模型。

---

## 1. 与既有块模块的对照

### 1.1 三种块模块的定位

| | **If / Else** | **从菜单中选取**（`ChooseFromMenuModule`） | **Switch / Case**（本设计） |
|---|---|---|---|
| 分支依据 | 条件表达式（19 种操作符） | **运行时弹 UI，用户点选** | **值的相等匹配** |
| 分值数量 | 2（真 / 假） | N（菜单项） | N + 1（Case × N，可选的 Default） |
| 谁来分支 | 执行期自动 | **人** | 执行期自动 |
| 有无 UI 弹窗 | 无 | **有**（阻塞等用户点） | 无 |
| 适用 | 「是否满足某条件」 | 「让用户当场选一个」 | 「值是哪个」 |

**⚠️ 与「从菜单中选取」的区别必须在命名与文案上说清**：两者都长成「N 个分支块」，
但一个是**人**选、一个是**程序**按值选。若文案含混，用户会以为 Switch 也会弹窗。

### 1.2 `If` 链与 `Switch` 的等价关系

`Switch` 本质是 `If / Else-If / Else-If / Else` 链的**语法糖**，但在「同一个值比多个常量」时：

- If 链：N 个分支 = N 个 If 卡片，每个都要**重复填一次**那个被比较的值 → 改一处要改 N 处
- Switch：被比较的值**只写一次**（在 Switch 卡片上），分支只写各自的值

⇒ Switch 不只是「更短」，它把「同一个源值」这件事**结构化了**——这是选它而不是堆 If 的理由。

### 1.3 为什么用英文名（决策留痕）

中文技术文献里 `switch` 的译法有「开关语句」「多分支选择语句」「选择结构」等，但**没有一个是广泛共识**：
教材之间不一致，检索「开关语句」的人和在检索「多分支」的人找不到同一批内容。
与之相反，`if` / `for` / `while` / `switch` 这几个词在中国的实际工程交流中**基本不翻译**。
⇒ 直接用 `Switch` / `Case` / `Default`，与 `Case` 在编程语境里已是个稳定的借词这一点一致。

> ⚠️ 本仓已有先例：模块名并不强制中文（`AiModuleMetadata`、`RiskLevel` 等在代码里就是英文）。
> 而**模块显示名**走 `nameStringRes`，三语**都写英文**（`Switch` / `Case` / `Default` / `End Switch`）——
> 这是**有意**的：一个语言显示英文、另一个语言显示自造中文，会让三语用户看到的**不是同一个东西**。

---

## 2. 交互设计（本文重点）

### 2.1 卡片形态

新建一个 Switch 块后，编辑器里呈现 4 类卡片（`Switch` 1 张 + `Case` N 张 + `Default` 0~1 张 + `End Switch` 1 张）：

```
┌──────────────────────────────────────┐
│ 🔀 Switch  {{status}}                 │  ← 起始卡片：用 pill 显示被匹配的值
└──────────────────────────────────────┘
  ┌────────────────────────────────────┐
  │ Case  "ok"                    [🗑][⋮] │  ← 分支卡片：显示该分支的匹配值
  └────────────────────────────────────┘
      #3  显示通知
      #4  播放音效
  ┌────────────────────────────────────┐
  │ Case  "error"                 [🗑][⋮] │
  └────────────────────────────────────┘
      #5  发送日志
  ┌────────────────────────────────────┐
  │ Default                       [🗑][⋮] │  ← 默认分支：无匹配值
  └────────────────────────────────────┘
      #6  重试
┌──────────────────────────────────────┐
│ 🔀 End Switch                         │  ← 结束卡片
└──────────────────────────────────────┘
```

**四类卡片的摘要文案**（`getSummary`）：

| 卡片 | 摘要 | 说明 |
|---|---|---|
| Switch | `Switch` + **值 pill** | 值走 `PillUtil.createPillFromParam`，与 If 的 `input1` 同构；可点 pill 直接改 |
| Case | `Case` + **值 pill** | 字面量渲染成 `"ok"`、变量渲染成 pill，与既有 pill 机制一致 |
| Default | `Default`（纯文案） | 无参数 |
| End Switch | `End Switch`（纯文案） | 无参数 |

**缩进**：由既有 `recalculateAllIndentation()` 自动算出，**不需要新代码**——
`BLOCK_MIDDLE`（Case/Default）与 `BLOCK_START` 同级（缩进 0），分支体缩进 1，`BLOCK_END` 回到 0。
这与 If/Else 的观感完全一致。

### 2.2 新建流程

用户在选择器（`ActionPickerSheet`）的「逻辑控制」分类里点 **`Switch`**：

1. 走既有的 `editorTargetStepIndex = 0` 路径 → 弹 **Switch 的管理 sheet**（§2.4）
2. **取消** ⇒ 什么都没发生（不产生半成品块）
3. **保存** ⇒ `createSteps()` 生成 4 张卡片：

```
Switch(匹配值 = <用户填的>, branches = [{id: a, match: ""}])
Case  (caseId = a, match = "")
Default
End Switch
```

4. 新增的 Case 默认值**留空**——用户可以在 sheet 里填，也可以点卡片填（§2.3）

> **为什么默认就带 `Default`**：未匹配时若没有任何兜底分支，Switch 会**静默什么都不做**，
> 而「为什么这个工作流什么都没发生」极难排查（见 §4）。默认给一张 Default 卡片，
> 用户不需要可以显式删掉——**删除是显式动作，不会被忽略**；而「没有 default 所以没执行」
> 是个隐式状态，很容易被忽略。

### 2.3 编辑匹配值（两条路，都通）

| 路径 | 操作 | 适合 |
|---|---|---|
| **卡片上** | 点 Case 卡片本体 → 弹该卡片的参数 sheet（既有 `showActionEditor`）→ 填「匹配值」 | 只想改一条 |
| **sheet 里** | 点 Switch 卡片 → 管理 sheet → 在分支列表里改 | 想一次改几条 / 同时调顺序 |

⚠️ **两条路必须写同一份数据**（`branches[].match` 与 `step.parameters["match"]`），
否则会出现「在 A 处改了、B 处看不见」。实现上让**两处都经 `SwitchBlockSupport`** 读写，
不各自直接碰参数 map（§5 第 10 条）。

| 卡片 | 点开的效果 |
|---|---|
| Switch | 弹**管理 sheet**（不是普通参数 sheet），见 §2.4 |
| Case | 一个输入框「匹配值」（可连魔法变量） |
| Default | **无参数** → 与 Else 卡片一样，弹空 sheet（既有行为，本次不改） |
| End Switch | 同上 |

> ⚠️ **Case 的值能连魔法变量是刻意的**：工作流里「值」常常来自上游步骤输出，
> 而每条分支各连一个变量（如 `{{s1.type}}` / `{{s2.type}}`）是完全合法的用法。
> 与 If 的 `value1` 一致，不额外限制。

### 2.4 分支管理（**在 Switch 卡片的 sheet 里集中管理**）

> **用户 2026-10-05 定案：采用方案 C** —— 所有 Case 都在 **Switch 起始卡片的 sheet** 里管理，
> 分支卡片上**不放 `[＋]`**。理由见 §7.1。
> ⚠️ 由此产生两个必须想清楚的问题（用户当场提的两个），见 §2.4.3 —— **它们是本方案能不能成立的关键**。

#### 2.4.1 交互形态

点 **Switch 起始卡片** → sheet，分两段：

```
┌─────────────────────────────────────────┐
│              ───  (handle)              │
│  Switch                                 │
│  匹配值  [ {{s1.status}}        ]  🪄   │   ← 上段：被比较的值（可连魔法变量）
│                                         │
│  分支                                   │
│  ═══ 拖动 ═══                            │
│  ⠿  Case   [ ok      🪄 ]  🗑            │   ← 下段：分支列表，每行一条
│  ⠿  Case   [ error   🪄 ]  🗑            │
│  ⠿  Default                      🗑      │   ← Default 无输入框（无匹配值）
│                                         │
│  ＋ 添加分支        ＋ 添加默认分支        │
│                                         │
│              [ 取消 ]   [ 保存 ]         │
└─────────────────────────────────────────┘
```

- **上段**：Switch 自己的参数（`value`），复用既有 `StandardControlFactory.createTextInputLayout` + 魔法变量按钮
- **下段**：分支列表，**行序 = 执行顺序**。每行：拖拽手柄 `⠿` + 类型（Case/Default）+ 匹配值输入框 + `🗑`
- **添加**：两个文字按钮（Default 已存在时「添加默认分支」置灰）
- **保存**：一次落盘（一次 `pushUndoSnapshot()`、一次 `recalculateAndNotify()`）

**卡片上的样子不变**：Case / Default 卡片照旧显示自己的匹配值 pill，但它们**不再有任何新增按钮**——
`[＋]` 不进卡片，`⋮` 保持原样（见 §2.4.4）。

#### 2.4.2 数据结构

**Switch 卡片多存一个参数 `branches`**，形态照抄 `ChooseFromMenuModule` 的 `items`：

| 模块 | 参数 key | 形态 |
|---|---|---|
| `ChooseFromMenuModule` | `items` | `[{id, title}]` |
| **`SwitchModule`（本设计）** | **`branches`** | **`[{id, match}]`**（`match` 可为空串 ⇒ 该分支的匹配值留空） |

- `id`：建分支时生成的 UUID，**稳定不变**（改名/改匹配值都不动它）
- Case 卡片自己的参数里**也存** `caseId`（与 Switch 的 `branches[].id` 对应）

⇒ **这正是「双份真相」，是刻意的**，理由见 §2.4.3。

#### 2.4.3 ⚠️⚠️ 两个关键问题的定案（**本方案成立的前提**）

用户当场提的两个问题，答案都是「**必须跟着走**」，而**方案 C 的数据结构让这件事自动成立**：

> **Q1：排序时，是带着下面的普通步骤一起排序吗？**
> **Q2：删除时，是连着下面的普通步骤一起删除吗？**

**答案：都是「是」。** 而且不是靠「移动时小心翼翼地一起搬」实现的，
而是靠 **`reconcileBranches` 的「按 id 找回分支体」** 从结构上保证：

```kotlin
// 与 ChooseFromMenuModule.MenuBlockSupport.reconcileBranches 同款
// ① 先按 caseId 把「每一条分支的整段体」收进 map
val existingBodies = linkedMapOf<String, List<ActionStep>>()
branchPositions.forEachIndexed { i, branchStart ->
    val branchEnd = branchPositions.getOrElse(i + 1) { endPosition }
    val caseId = steps[branchStart].parameters[CASE_ID] as? String ?: return@forEachIndexed
    existingBodies[caseId] = steps.subList(branchStart, branchEnd).toList()   // ← 卡片 + 整段体
}
// ② 清空整个块的分支区，按 sheet 里的新顺序重建
// ③ 每条分支：从 map 里按 id 取回原分支体；取不到（新加的）⇒ 只建一张空卡片
branches.forEach { b ->
    val body = existingBodies[b.id]?.toMutableList() ?: mutableListOf(ActionStep(SWITCH_CASE_ID, emptyMap()))
    ...
}
```

**为什么必须这样，而不是「在列表里移动那张卡片」**：

分支体**不是独立实体**——它由「分支卡片的位置」隐式定义（下一个分支卡片之前的所有步骤都属于它）。
⇒ 如果只把 `Case "ok"` 那一行挪到 `Case "error"` 下面，而**分支体留在原地**，
那原来的两段体会**整体错位一格**：`"ok"` 的体归给 `"error"`、`"error"` 的体归给 `"ok"`。
表现是「我只调了个顺序，两个分支的执行内容互相换了」——**不报错、难发现**。

按 id 找回则天然正确：
- **排序** ⇒ 顺序变了，每条体跟着自己的 id 走 ✅
- **删除** ⇒ 该 id 不在新列表里 ⇒ 它的整段体一并消失 ✅（正是 Q2 要的）
- **新增** ⇒ 新 id 在 map 里找不到 ⇒ 建空体 ✅

> ⚠️ **与「步骤列表里拖动 Case 卡片」的区别**（用户已确认过这一点）：
> 那是**另一条**路径 —— `WorkflowEditorActivity.moveBlockInList` 会把 `findBlockRange` 圈出的
> **整个 Switch 块**（Switch + 全部分支 + End）当整体搬走。**在 sheet 里拖动分支行不会走到那里**
> —— sheet 操作的是 `branches` 数组的顺序，落盘时走 `reconcileBranches`。
> 两者互不干扰，但**都必须在实现后实测**（见 §7.3）。

#### 2.4.4 卡片上的按钮（**收敛后的定案**）

| 位置 | 按钮 | 说明 |
|---|---|---|
| Case / Default 卡片 | `⋮` | **既有，一行不改**。「在下方插入」用它——那是「往分支体里加执行步骤」的唯一路径（§2.4.5） |
| Case / Default 卡片 | `🗑` | **既有**。删的语义 = 「删这一条分支」；走既有的 `onDeleteClick` → 需要让它对 Case 生效（见下） |
| Case / Default 卡片 | ~~`[＋]`~~ | **不加**。加分支改在 sheet 里 |

⚠️ **卡片上的 `🗑` 与 sheet 里的 `🗑` 要做成同一件事**（都删「分支 + 它的体」），
不能一个删「只卡片」一个删「带体」—— 那是最典型的静默不一致。
实现上让卡片的 `onDelete` 走 `SwitchBlockSupport.deleteBranch(steps, position)`（**共用一份逻辑**）。

#### 2.4.5 「往分支体里加执行步骤」仍然走既有的 `⋮`

**这是 sheet 方案唯一没覆盖的日常操作，且它本来就不该在 sheet 里做**：

点分支体内的某个步骤 → `⋮` → 「在下方插入」（`onInsertBelowClick` → `showActionPickerAtPosition(position + 1)`）。
新建的 Switch 每个分支体是空的，用户点 Case 卡片的 `⋮` → 「在下方插入」 ⇒ `idx + 1` 恰好落在
「该分支体的第一步」⇒ **天然就是「在该分支开头插一步」**，不需要任何特殊处理。

> ⚠️ **`⋮` 绝不能换掉**（用户 2026-10-05 指出）：它是**唯一的**「往分支里加东西」的入口，
> 而「分支里没有步骤」的 Switch 没有任何用处。

#### 2.4.6 保存时机与撤销

- sheet 的「保存」= 一次原子操作：`pushUndoSnapshot()` → 写 `branches` → 写 Switch 的 `value`
  → `SwitchBlockSupport.reconcileBranches(steps, switchPosition)` → `recalculateAndNotify()`
- **「取消」不产生任何副作用**（`branches` 只改 sheet 内的临时副本）
- ⚠️ **`pushUndoSnapshot()` 必须在 reconcile 之前**（与仓库其它删除一致）

### 2.5 Default 分支

- **最多一张**。已有 Default 时，「添加默认分支」按钮置灰（防「两张 Default，只有第一张生效」的静默错误）
- Default **永远排在所有 Case 之后**：sheet 里它的行被固定在列表末尾（不可拖到 Case 之前）
- 语义：所有 Case 都不匹配时执行它；没有 Default 时，不匹配 = **整块跳过**

### 2.6 选择器（`ActionPickerSheet`）里的样子

- 分类：**逻辑控制**（`categoryId = "logic"`），与 If / 循环 / 菜单同组
- 只列**起始模块** `Switch`（`ModuleRegistry` 的列表本就过滤掉 `BLOCK_END` / `BLOCK_MIDDLE`，见 `ModuleRegistry.kt:44`）
- 图标：**新增** `rounded_switch_24`（分叉箭头造型）
  ⚠️ **不复用 `rounded_alt_route_24`**——那是 If 的图标，两个逻辑模块图标相同会让用户分不清

### 2.7 预览态 / 其它

- 卡片配色、圆角、`category_color_bar` 等**全部走既有机制**（`PillTheme.getCategoryColor("logic")`），不需要为 Switch 单独定制
- Switch 块整体被禁用（`isDisabled`）→ 由既有 `BlockStructureHelper.isStepEffectivelyDisabled` 覆盖整段，**不需要新代码**
- 折叠/选择模式、步骤列表里的拖拽排序等全部继承既有行为（sheet 内的拖拽是新增的，见 §2.4.1）

---

## 3. 数据结构

### 3.1 模块定义（4 个类，同一文件 `SwitchModule.kt`）

| 类 | moduleId | `blockBehavior` | 参数 |
|---|---|---|---|
| `SwitchModule` | `vflow.logic.switch.start` | `BLOCK_START`, pairing `"switch"` | `value`（`ANY`）+ **`branches`**（`ANY`，`[{id, match}]`） |
| `SwitchCaseModule` | `vflow.logic.switch.case` | `BLOCK_MIDDLE`, pairing `"switch"`, `isIndividuallyDeletable = false` | `match`（`ANY`）+ `caseId`（`STRING`，隐藏） |
| `SwitchDefaultModule` | `vflow.logic.switch.default` | `BLOCK_MIDDLE`, pairing `"switch"`, `isIndividuallyDeletable = false` | 无（`getInputs() = emptyList()`） |
| `EndSwitchModule` | `vflow.logic.switch.end` | `BLOCK_END`, pairing `"switch"` | 无 |

**为什么不继承 `BaseBlockModule`**：它把 `createSteps()` 与 `onStepDeleted()` 都设成 `final`，
而 Switch 两者都必须重写（`createSteps` 要带默认值、`onStepDeleted` 要调 `deleteBranch`）。
⇒ `SwitchModule` 直接继承 `BaseModule`，自建块逻辑（与 `ChooseFromMenuModule` 同款）。

### 3.2 参数 key

| key | 归属 | 类型 | 说明 |
|---|---|---|---|
| `value` | Switch | `ANY` | 被匹配的值 |
| `branches` | Switch | `ANY` | `[{id, match}]`，**行序 = 执行顺序**（§2.4.2） |
| `match` | Case | `ANY` | 该分支的匹配值（与 `branches[].match` **同步**） |
| `caseId` | Case | `STRING` | 与 `branches[].id` 对应的 UUID。⚠️ 见下 |

**⚠️ `caseId` 是隐藏参数**：不进编辑器的通用表单（由 `getHandledInputIds()` 收走），
用户看不到、编辑不了。它的唯一作用是让 `reconcileBranches` 能把「卡片」与「分支条目」对上。

### 3.3 与 `ChooseFromMenuModule` 的关系（**本设计是照抄 + 一处改动**）

| | 从菜单中选取 | Switch（本设计） |
|---|---|---|
| 分支的值存在哪 | 菜单卡片的 `items: [{id, title}]` | Switch 卡片的 `branches: [{id, match}]` |
| 卡片上存什么 | 分支卡片存 `itemId` + `itemTitle` | 分支卡片存 `caseId` + `match` |
| 保存后同步 | `reconcileBranches` | **同款** `reconcileBranches`（§2.4.3） |
| 「用户会想一起编辑的东西」 | 菜单标题（要一起排序/改名） | **匹配值**（要一起排序/改名） |
| 是否需要 `syncDynamicBlockAfterSave` 挂钩 | **需要**（`MENU_START_ID`） | **需要**（`SWITCH_START_ID`，**新增一处**） |

> ⚠️ **此处与本文档早期版本不同**：早期写的是「Switch 的值就地编辑、不需要 reconcile」，
> 那对应方案 A/B。**改用方案 C 后，Switch 与 `ChooseFromMenu` 的结构完全同构**
> —— `WorkflowEditorActivity.syncDynamicBlockAfterSave`（`:954`）要追加一个分支：
> ```kotlin
> if (module.id == SWITCH_START_ID) {
>     SwitchBlockSupport.reconcileBranches(actionSteps, startPosition)
> }
> ```
> ⚠️ **新增分支必须挂在 `syncDynamicBlockAfterSave` 的 `when` 里，不要新造第二套挂钩机制**
> —— 那是本仓库反复踩过的「双份实现」形态。

---

## 4. 执行语义

### 4.1 三个执行点

**① `SwitchModule.execute`** —— 算出 `value`，逐一比对各 **Case 卡片**的 `match`，跳转：

```
命中第 k 个 Case  →  Jump(第 k 个 Case 卡片的下一个位置)
无一命中且有 Default → Jump(Default 卡片的下一个位置)
无一命中且无 Default → Jump(End Switch 卡片的下一个位置)   // 整块跳过
```

**② `SwitchCaseModule.execute`** —— 走到这里说明**这一条没被选中**（选中的会被 Switch 跳过去）：
`Jump(End Switch 的下一个位置)`。与 `MenuItemModule.execute` 完全同构。

**③ `SwitchDefaultModule.execute`** —— 同上。

`EndSwitchModule.execute` = `ExecutionResult.Success()`（与 `EndIfModule` 一致）。

### 4.2 匹配判据：复用 `OP_EQUALS`（**looseEquals**）

```kotlin
ConditionEvaluator.evaluateCondition(input1 = value, operator = OP_EQUALS, value1 = caseValue, value2 = null)
```

用**与 If 模块「等于」完全相同**的判据，理由：

- **一致性 > 纯净性**：用户已经知道 If 的「等于」是什么意思（忽略大小写、数字/字符串互转）。
  给 Switch 另造一套「严格等于」的语义，等于让用户为同一个词学两种行为。
- `ConditionEvaluator` 是 `public object`，**直接可用**，无需新代码。

⚠️ 代价（如实记录）：`looseEquals` 是 PHP `==` 风格的弱比较，有若干反直觉行为——
`"1" == 1` 为真、忽略大小写、**空字符串等于任何非数字字符串**。**后者对 Switch 是致命的**：
一个没填匹配值的 Case 会匹配**一切**。⇒ §5 的第 1 条。

### 4.3 与「无 break」的一致性

因为命中即跳走、未命中的分支卡片自己再跳走，**执行流永远不会从一个分支体「落进」下一个分支体**。
这正是「没有 break」在实现层的含义，与用户的心智模型一致。

---

## 5. 静默失效点清单（**实现时必须逐条处理**）

本仓库的教训：这类模块的失败模式大多是「改错了不报错、只静默变差」。以下逐条列出。

| # | 失效点 | 表现 | 处置 |
|---|---|---|---|
| 1 | ⚠️⚠️ **Case 匹配值为空** | `looseEquals("", 任意非数字串)` **返回 true** ⇒ 空值 Case **匹配一切**，后面所有分支永远不可达 | **`validate()` 硬拦**：Case 的 `match` 为空 / 全空白 ⇒ 校验不通过。**不能只靠"用户不会留空"** |
| 2 | ⚠️ **Case 匹配值重复**（两个 `"ok"`） | 只有**第一个**生效，第二个的分支体是死代码，且**无任何提示** | `validate()` 报错（照 `ChooseFromMenuModule` 的「不能重名」处理）。比较用**归一化后**的值（trim + 大小写不敏感） |
| 3 | ⚠️ **无 Default 且未匹配** | 整块静默跳过 | **合法**语义，**不报错**；§2.2 让新建时默认带 Default，降低发生概率 |
| 4 | ⚠️⚠️ **`reconcileBranches` 没能把分支体认回来** | 排序/改名后**分支体整体错位**（`"ok"` 的体归给 `"error"`） | **按 `caseId` 找回**（§2.4.3）；**并加单测**：调序后断言每段体仍跟着原 id |
| 5 | ⚠️ **`caseId` 丢失/重复** | 同上（认不回 ⇒ 建空体 ⇒ **分支体静默消失**） | `reconcileBranches` 开头做**完整性体检**：id 缺失或重复 ⇒ **直接放弃 reconcile 并留日志**（宁可不同步，也不要毁掉用户的分支体） |
| 6 | ⚠️ **嵌套 Switch 时配对错乱** | 内层 `End Switch` 被当成外层结束 ⇒ 执行跳到错误位置 | 分支定位一律走 `BlockNavigator` 的**配对 ID + 嵌套计数** |
| 7 | ⚠️ **`Default` 出现两张** | 只有第一张生效 | sheet 里置灰按钮（§2.5）；`validate()` 再兜一道 |
| 8 | **魔法变量解析失败** | 值原样成 `{{...}}` 字面量 ⇒ 匹配不上 | 与 If 一致，**不额外处理** |
| 9 | **匹配值含首尾空格** | 用户看着一样、实际不等 | `validate()` 对 `match` 做 `trim` 后再校验；**执行期不 trim**（与 If 一致） |
| 10 | ⚠️⚠️ **`branches[].match` 与 `step.parameters["match"]` 不同步** | 在 sheet 改的、卡片上看不见（或反之） | **两处读写都经 `SwitchBlockSupport`**；reconcile 时以 `branches` 为准写回卡片；有单测锁「两条路写同一个值」 |
| 11 | ⚠️ **卡片 `🗑` 与 sheet `🗑` 行为不一致** | 一个删「卡片」、一个删「卡片 + 体」 | **共用 `deleteBranch`**（§2.4.4） |

---

## 6. 实现清单与冲突归属

### 6.1 新增文件（**我方**）

| 文件 | 内容 |
|---|---|
| `core/workflow/module/logic/SwitchModule.kt` | 4 个模块类 + `SWITCH_PAIRING_ID` 等常量 + `SwitchBlockSupport`（`reconcileBranches` / `findBranchPositions` / `deleteBranch` / `readBranches` / `toParameters` / 校验辅助，**均为纯函数**以便单测） |
| `core/workflow/module/logic/SwitchEditorSheet.kt`（新增） | 管理 sheet（分支列表 + 拖拽 + 增删 + 上段的 `value` 输入） |
| `res/layout/sheet_switch_editor.xml`（新增） | sheet 布局（上段输入 + 下段 `RecyclerView` + 两个添加按钮 + 底部按钮） |
| `res/layout/item_switch_branch.xml`（新增） | 分支行布局（`⠿` + 类型标签 + 输入框 + 🪄 + 🗑） |
| `res/drawable/rounded_switch_24.xml` | Switch 图标（分叉造型，24dp / viewport 960 / `?attr/colorControlNormal`） |
| `test/.../module/logic/SwitchBlockSupportTest.kt` | 纯函数测试（**调序后分支体跟着 id 走**、删除带体、新增建空体、id 缺失/重复时放弃 reconcile、空值/重复校验、嵌套） |
| `test/.../module/logic/SwitchModuleTest.kt` | 声明体检（id / pairing / 权限空 / 四类卡片齐全 / **`syncDynamicBlockAfterSave` 挂钩的源码扫描**） |
| `docs/fork/switch-module-design.md` | 本文档 |

### 6.2 改动的上游文件（**手动合并**）

| 文件 | 改动 | 面积 |
|---|---|---|
| `core/workflow/module/ModuleRegistry.kt` | 逻辑段**追加 4 行** `register(...)`（不重排既有注册） | 4 行 |
| `ui/workflow_editor/WorkflowEditorActivity.kt` | ① `syncDynamicBlockAfterSave` **追加一个分支**（`SWITCH_START_ID`）；② Switch 卡片的点击改为**打开管理 sheet**（与 `showActionEditor` 分流）；③ 卡片 `🗑` 对 Case 走 `deleteBranch` | 3 处，约 25 行 |
| `ui/workflow_editor/ActionStepAdapter.kt` | Switch 卡片点击的**分流**（普通卡片走 `onEditClick`，Switch 走新回调）—— **加一个可选回调**（默认 `null` ⇒ 既有卡片一行不受影响） | **+8 / −1** |
| `res/values{,-en,-ja}/strings_module.xml` | 追加模块名/描述/参数名/摘要前缀/分支行文案（**模块名等三语同为英文**，见 §1.3） | 追加条目 |
| `res/values{,-en,-ja}/strings.xml` | 追加 sheet 文案（标题 / 分支段标题 / 两个添加按钮 / 拖拽无障碍描述） | 追加条目 |

**没有 `[＋]` 按钮 ⇒ `item_action_step.xml` 一行不动**（方案 C 省掉了这一处上游改动）。

⚠️ **对上游的冲突面**：上述两个 Kotlin 文件都已有 fork 的分歧记录（`FORK.md` 已认「手动合并」），
改动落在小函数上，冲突面与「触发器标签按钮」同量级。

### 6.3 实现后必须做的登记

- `FORK.md`：新增 `SwitchModule.kt` / `SwitchEditorSheet.kt` / 两个 layout / 图标 / 测试 / 本文档（我方）
  + `ModuleRegistry` / `WorkflowEditorActivity` / `ActionStepAdapter` / 三语 strings（手动合并）
- `AGENTS.md`：无（不属于任何既有敏感点清单的新条目）

---

## 7. 待用户拍板 / 未决项

### 7.1 `[＋]` 交互形态 —— **已定案：方案 C**

| 方案 | 形态 | 结论 |
|---|---|---|
| A | 卡片操作区一个 `[＋]`：短按加 Case、长按加 Default | 弃 |
| B | `[＋]` 短按弹小菜单 | 弃 |
| **C（**已采纳**）** | **全部在 Switch 卡片的 sheet 里集中管理** | ✅ 用户 2026-10-05 拍板 |

选 C 的理由：**排序**（§2.4.3 Q1）在 A/B 下无处安放 —— 卡片上只有 `[＋]`，没有「把这条分支挪到前面」的入口，
而分支顺序**是有语义的**（虽然 Switch 的匹配是互斥的，顺序不影响结果，但用户仍会想「按什么顺序读」，
且与 `ChooseFromMenu` 的形态一致）。sheet 一次给出「看全、调序、改名、增删」，比在卡片间反复点更省事。

⚠️ **C 的两个已知代价**（如实记录，均已给出处置）：
1. **必须与 `ChooseFromMenu` 一样引入 `branches` + `reconcileBranches` + 卡片 `caseId`**
   ⇒ 比 A/B 多一层「双份真相」，需 §5 的第 4/5/10 条防守。**这是选 C 的主要成本。**
2. **`⋮` 必须保留**（§2.4.5）—— 不能把卡片上的 `⋮` 换成「分支管理」，否则分支体里加不了步骤。

### 7.2 其余未决项

| # | 事项 | 现状 / 倾向 |
|---|---|---|
| 1 | sheet 里**拖拽**的实现 | 复用 `ItemTouchHelper`（仓库已用，见 `WorkflowEditorActivity:1362`），**只允许上下、不跨 Default** |
| 2 | Default 行能否拖动 | 倾向**锁定在末尾**（`ItemTouchHelper` 的 `onMove` 里拦截） |
| 3 | `aiMetadata`（AI 侧） | 倾向 `usageScopes = { TEMPORARY_WORKFLOW }`（**不给 `DIRECT_TOOL`**，与 If/菜单一致）、`riskLevel = LOW`。AI 要能**按顺序**生成 `switch.start → case → ... → end` 四段，`workflowStepDescription` 需写清 `branches` / `caseId` 的配对关系 |
| 4 | 真机验证清单 | 卡片形态 / sheet 交互 / **拖拽调序后分支体归属** / 删除带体 / 两条编辑路径同步 / 嵌套 Switch / 撤销 —— **均只有编译与单测支撑前，不得声称可用** |
