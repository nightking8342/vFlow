# 工作流开关设计 —— 触发器 + 设置开关模块

> **状态**：设计定稿，**未实现** ｜ **日期**：2026-10-10
> **目录归属**：fork 独有（以新增文件为主，上游改动见 §9）
> **覆盖两个交付物**：
> - **A. 触发器** `vflow.trigger.workflow_toggle` —— 当**指定工作流**的启用开关变化时触发工作流
> - **B. 动作模块** `vflow.logic.set_workflow_enabled` —— 在工作流内部启用 / 关闭 / 切换另一个工作流
>
> **两者必须一起设计**：B 是 A 最主要的「生产者」，而 B 的写入恰好暴露了 A 判定规则里
> 一个**靠既有字段无法区分**的歧义（§3.3）。分开做会得到一个错误或不可用的 A。
>
> **相关**：`surveys/trigger-system-overview.md`（触发器现状地图 + §9.1 新增清单）、
> `broadcast-trigger-design.md`（新增触发器的体例先例）、
> `quick-settings-tile-design.md`（开关型磁贴，本设计的既有同类写入方）

> ⚠️ 本文行号与调用点清单基于 **2026-10-10 的 `dev` 走查**，会随上游漂移；**引用前以代码为准**。

---

## 0. 需求与定案

| 交付物 | 一句话 |
|---|---|
| **A 触发器** | 「**当工作流 X 被启用 / 被关闭时**，跑我」 |
| **B 动作模块** | 「**把工作流 X 启用 / 关闭 / 切换**」（目前仓库**没有**这个能力，已核实） |

**用户已拍板**（2026-10-10）：

| # | 决策 |
|---|---|
| 1 | 触发器目标 = **指定一个**工作流（不做「任意工作流开关都触发」） |
| 2 | 程序性 `isEnabled` 变化（权限自动禁用 / 权限恢复自动重开 / 回弹）**不触发** |
| 3 | **B 模块的开关动作要触发** A |

> ⚠️ 第 2 条与第 3 条**合起来**才构成完整的判定要求，而它们**互相冲突** ——
> 见 §3.3。这是本设计最核心的一处论证。

典型用法：

- 「关掉 A 时，顺手关掉 B」（A 触发器 + B 模块）
- 「A 被启用时，发一条通知」
- 做一个「总开关」工作流：**批量**启用 / 关闭一批子工作流（B 模块）
- 「每天 22:00 自动关闭 XX 工作流」（B 模块 + 时间触发器）

---

## 1. 它与现有触发器的根本差异

**这是本文最重要的一条：A 没有可注册的系统事件源。**

现有已注册 Handler 的事件源**全部在 App 之外**：

| 事件源 | 代表 |
|---|---|
| 系统广播 | DND、Power、Screen、SimDataSwitch |
| 无障碍事件流 | AppStart、AppSwitch、Element、GKD |
| 传感器 | BackTap、Pose |
| AlarmManager | Interval、Time |
| 剪贴板 / 定位 / 通知回调 / 音频 | Clipboard、Location、Notification、Voice |
| 外部进程 Intent | **KeyEvent** |

> ⚠️ 分类见 `surveys/trigger-system-overview.md` §7.1，**该表锚定在 2026-09-15**，
> 此后已新增 `fold` / `logcat` / `sim_data_switch` / `activity_changed` / `broadcast` 等 fork 触发器。
> 本表只用来说明**事件源的分布**，不要引用它的条数。

A 要监听的是 **App 自己写的 `Workflow.isEnabled`** —— 它**没有 `IntentFilter` 可注册、
没有传感器可订阅、没有无障碍事件可收**。

⇒ A 是**继 `key_event` 之后第二个「事件源不在 Handler 内部」的触发器**，
形态上照抄 `key_event`：由 `TriggerService` 的既有分支**直接调进 Handler**
（`TriggerService.onStartCommand` 的 `KeyEventTriggerModule.ACTION_KEY_EVENT_RECEIVED`
分支就是先例，`TriggerService.kt` 约 :204）。

**这条差异决定了本设计的全部成本**：难点不在写模块，在
「**在哪儿接这条线**」（§2）与「**什么算一次变化**」（§3）。

---

## 2. 投递路径

### 2.1 现状：`isEnabled` 的写入链路

`WorkflowManager.saveWorkflow()`（`WorkflowManager.kt:71`）是**所有写入路径的汇聚点**，
它已经在做两件事：算出 `oldWorkflow`，然后
`TriggerServiceProxy.notifyWorkflowChanged(context, workflowToSave, oldWorkflow)`。

```
saveWorkflow(workflow)                                   WorkflowManager.kt:71
  ├ oldWorkflow = 按 id 从盘上读到的旧版本               ← ★ 旧状态在这里可得
  ├ 写 prefs
  └ TriggerServiceProxy.notifyWorkflowChanged(新, 旧)     TriggerServiceProxy.kt:21
       └ Intent(ACTION_WORKFLOW_CHANGED) + WorkflowTriggerDelta(...)
            └ TriggerService.onStartCommand              TriggerService.kt:186
                 └ handleWorkflowChanged(最新, oldTriggerRefs)   TriggerService.kt:278
```

### 2.2 三个候选

| | 甲：复用 `ACTION_WORKFLOW_CHANGED` | 乙：新建进程内 `SharedFlow` 总线 | 丙：Handler 内轮询 |
|---|---|---|---|
| 侵入面 | 上游 3 行（见 §9.2） | 上游 2 行 | 0 行 |
| 服务未运行时 | ✅ `startService` 会把服务拉起来 | ❌ 无订阅者，事件静默丢失 | ❌ 轮询本身就要求服务在跑 |
| 与既有设计一致 | ✅ 这条 Intent 的语义**本来就是**「工作流变了」 | ⚠️ 与 `WorkflowDataChangeBus` 并列，多一套 | ❌ 反模式 |
| 旧状态可得 | ✅ Proxy 手上就有 `oldWorkflow` | ✅ 可放进载荷 | ⚠️ 靠自持缓存 |

**定案：走甲。** 理由不是「省事」，而是**语义本来就重合** ——
`ACTION_WORKFLOW_CHANGED` 存在的意义就是「某个工作流被改了」，而 `isEnabled` 是工作流的一部分。
新建总线（乙）会造出第二条「工作流变了」的通道，将来必然有人只发一条、忘发另一条。

> ⚠️ **乙被否的具体理由**：`WorkflowDataChangeBus`（fork 已有）是**无载荷的 UI 刷新信号**，
> 且**只由磁贴发布**（列表页自己改自己不发，避免多付一趟读盘 —— 见该文件的 KDoc）。
> 拿它承载触发器事件会立刻踩到这条纪律：列表页里点开关**不会**发这个信号，
> 而那恰恰是 A 最主要的入口之一。**不要复用 `WorkflowDataChangeBus`。**

### 2.3 载荷要带两个新字段

`WorkflowTriggerDelta`（`services/WorkflowTriggerDelta.kt`，**上游文件**）目前只有
`workflowId` + `oldTriggerRefs`（triggerId/type）。A 需要两样东西，它都没有：

| 字段 | 为什么需要 |
|---|---|
| `oldIsEnabled: Boolean?` | ① 判断「真的变了」而非「又保存了一次」；② 填输出的 `was_enabled`。**`null` = 保存前该工作流不存在**（新建 / 导入 / API 创建） |
| `writeOrigin: WorkflowWriteOrigin` | 判断这次写入是「有人显式要求的」还是「系统自动的」—— §3.3 的全部内容 |

两者在 `TriggerServiceProxy.notifyWorkflowChanged` 里**都是现成可取的**
（它手上已经有 `oldWorkflow`；`writeOrigin` 由 §3.3 的机制带下来），
不需要往上游更深的地方传参。

> ⚠️ `oldIsEnabled` 用 **`Boolean?`** 而不是「额外的 `isNew` 布尔」：
> `null` 同时承担「不存在」与「首次」两个语义，两者必然同真同假，
> **不要**加第二个字段。

### 2.4 接线点：`onStartCommand`，**不是** `handleWorkflowChanged`

`handleWorkflowChanged` 有**两个调用方**：

1. `onStartCommand` 的 `ACTION_WORKFLOW_CHANGED` 分支（真实变更）
2. `loadAllActiveTriggers`（`TriggerService.kt:264`，**服务启动时逐个装载**）

第 2 条是**开机装载，不是变更**。把派发写在 `handleWorkflowChanged` 内部，
就必须再给它加一个「我是不是启动装载」的参数（签名变更 + 两个调用点都改）；
写在 `onStartCommand` 的分支里则**天然只覆盖真实变更**，`handleWorkflowChanged` 的签名一个字不用动。

**定案：接线点在 `onStartCommand` 的 `ACTION_WORKFLOW_CHANGED` 分支。**

### 2.5 派发时机：放在 `handleWorkflowChanged` **之后**

同一分支里两个动作有先后：先 `handleWorkflowChanged`（增删该工作流自己的触发器），
再派发开关事件。**顺序不能反，而且「放后面」是功能性的，不只是风格**：

| 情形 | 放在**后面**（本设计） | 放在前面 |
|---|---|---|
| 用户**启用** Y，而 Y 自己带着 A 触发器 | ✅ Y 的触发器刚注册好，能立刻响应 | ❌ 漏掉这一次 |
| 用户**关闭** Y，而 Y 自己带着 A 触发器 | ✅ Y 的触发器**刚被移除** ⇒ 不会执行 Y | ❌ 会把刚关掉的 Y 再跑一次 |

⇒ **「关闭自己」这类自指用法天然不会自触发**（§5）。

---

## 3. 什么算「开关变化」（本文核心）

### 3.1 全部 `isEnabled` 写入点清单

（2026-10-10 走查，行号会漂移）

| # | 位置 | 方向 | 谁发起的 | 应否触发 A |
|---|---|---|---|---|
| 1 | `ui/workflow_list/WorkflowListRoute.kt:570` | 双向 | **用户**（列表卡片开关） | ✅ |
| 2 | `ui/tile/BaseToggleTileService.kt:99` | 双向 | **用户**（QS 磁贴） | ✅ |
| 3 | `api/handler/WorkflowHandler.kt:314` / `:343` | 双向 | **用户**（远程 API 启用 / 禁用端点） | ✅ |
| 4 | `ui/chat/ChatAgentModuleExecutor.kt:1522` | 双向 | **用户**（AI 的 `update_workflow`） | ✅ |
| 5 | **B 模块（本次新增）** | 双向 | **用户**（工作流步骤） | ✅ **（用户第 3 条要求）** |
| 6 | `services/TriggerService.kt:360` | 开→关 | 程序（缺权限自动禁用） | ❌ |
| 7 | `services/TriggerService.kt:556` | 开→关 | 程序（Core 不可用批量暂停） | ❌ |
| 8 | `core/workflow/WorkflowPermissionRecovery.kt:38` | 关→开 | 程序（权限恢复自动重开） | ❌ |
| 9 | `ui/tile/BaseToggleTileService.kt:145` | 开→关 | 程序（磁贴权限回弹） | ❌ |
| 10 | `ui/workflow_list/WorkflowListRoute.kt:590` | 开→关 | 程序（列表权限回弹） | ❌ |
| 11 | `core/workflow/WorkflowManager.kt:198`（复制） | 新建 | — | ❌（`oldIsEnabled == null` 挡掉） |
| 12 | `ImportExportHandler.kt:157`、`WorkflowHandler.kt:222` / `:285` | 新建 | — | ❌（同上） |

### 3.2 程序性变化必须过滤 —— 理由不是洁癖，是调用时机

第 8 类（权限恢复自动重开）挂在 **6 个入口**上：

| 入口 | 时机 |
|---|---|
| `services/TriggerService.kt:120`（`onCreate`） | **每次服务启动**（含开机） |
| `ui/main/MainActivity.kt:224` | **每次进 App** |
| `permissions/PermissionActivity.kt:126` | 每次进权限页 |
| `services/PermissionGuardianService.kt:195` | 权限守护巡检 |
| `ui/workflow_list/WorkflowListRoute.kt:475` / `:493` | 列表页生命周期 |

不过滤的后果很具体：**用户每次打开 App 都可能看到「我没碰任何开关，工作流自己跑了」**。
这是最难自查的一类行为 —— 数据是对的、日志是有的，只有用户没做过那个动作。

### 3.3 判定机制：为什么**不能**借用 `wasEnabledBeforePermissionsLost`

#### 第一版方案（**已被证伪，留档**）

初版设计打算借用既有的 `wasEnabledBeforePermissionsLost`（下称 `wabpl`）字段当判据 ——
它的写入点恰好与「谁改的」高度重合：

```
关→开：程序性 ⟺ oldWabpl == true      ← 用来识别第 8 类
开→关：程序性 ⟺ newWabpl == true      ← 用来识别第 6/7/9/10 类
```

**这个方案在用户提出第 3 条要求后当场失效。** 反例：

| 场景 | `isEnabled` 变化 | `oldWabpl` | `newWabpl` | 借用字段的判定 | 正确判定 |
|---|---|---|---|---|---|
| 用户授权后，系统**自动重开** A（第 8 类） | false→true | `true` | `false` | ❌不触发 | ❌不触发 ✅ |
| **B 模块**重新启用同一个 A（第 5 类） | false→true | `true` | `false` | ❌不触发 | ✅**要触发** |

两行的**字段状态逐字节相同** —— 借用字段**在原理上就分不开**它们。
⇒ 必须引入**显式来源标记**。

#### 定案：显式来源 + 默认值 = 显式

```kotlin
// 新增 fork 文件：core/workflow/WorkflowWriteOrigin.kt
enum class WorkflowWriteOrigin {
    /** 有人**显式要求**这次变更：用户（列表/磁贴/API/AI）或工作流步骤（B 模块）。 */
    EXPLICIT,
    /** **系统自动**改的：权限丢失禁用、权限恢复重开、回弹。 */
    AUTOMATIC,
}
```

```kotlin
// WorkflowManager.kt（上游文件，签名追加一个**带默认值**的参数）
fun saveWorkflow(
    workflow: Workflow,
    origin: WorkflowWriteOrigin = WorkflowWriteOrigin.EXPLICIT,
)
```

**只有 5 处程序性写入传 `AUTOMATIC`**（上表第 6–10 行），其余 15+ 个既有调用点
**一行都不用改**，B 模块也自动落在 `EXPLICIT`。

判定规则退化成一条：**`origin == EXPLICIT && oldIsEnabled != null && oldIsEnabled != newIsEnabled` ⇒ 触发。**

#### 为什么默认值必须是 `EXPLICIT`（而不是 `AUTOMATIC`）

默认值决定了「**将来有人忘了标记**」时的失败方向：

| 默认值 | 忘标记的后果 | 可见性 |
|---|---|---|
| **`EXPLICIT`（本设计）** | 多触发几次 | ✅ **用户看得见**（工作流莫名其妙跑了一次），能被报上来 |
| `AUTOMATIC` | **该触发的不触发** | ❌ **静默失效** —— 本仓库记录过至少三次同类事故的形态 |

⇒ 按本仓库的既有纪律（**可见的失败优于静默失效**），默认值取 `EXPLICIT`。

#### 被否的替代方案

| 方案 | 为什么否 |
|---|---|
| 借用 `wabpl` | §3.3 上表 —— 原理上分不开，**已证伪** |
| 继续借用 + 让第 8 类写盘时保留 `wabpl = true` | ❌ 破坏回弹判据：`recoverEligibleWorkflows` 清 `wabpl` 正是「已经恢复过了」的依据（见 `BaseToggleTileService` 类注释第 1 条） |
| 用进程内「临时标记」（ThreadLocal / 全局 flag） | ❌ 时序脆弱：`saveWorkflow` 一旦异步化就**静默失效**，且读代码看不出来 |
| 让 `recoverEligibleWorkflows` 自己写盘、不走 `saveWorkflow` | ❌ 会绕过 `normalizeWorkflow` / 函数签名聚合 / `TileRefreshNotifier` —— 复制一份必然漂移 |

### 3.4 已知漏洞：回弹的「第一跳」

用户「按了开 → 权限不够 → 自动回弹为关」会**连写两次**：

```
第 1 跳  用户意图   origin=EXPLICIT,  isEnabled=true    ← 触发「已启用」
第 2 跳  程序回弹   origin=AUTOMATIC, isEnabled=false   ← 过滤
```

**净效果：用户收到一次「X 已启用」，但 X 实际是关的。** 下游据此判断会得到错误结论。

修它需要去抖（等 N ms 后回读，仍在新状态才派发），代价是：引入时序竞态、
`TriggerService` 里多一份待定状态、N 取多少无客观依据。

⇒ **定案：不做去抖，如实写进文档与模块描述。** 触发频率低（仅在权限缺失时），
且「用户确实按了那个开关」这一事实并没有被误报。

---

## 4. 启动基线

`TriggerService.onCreate`（`TriggerService.kt:95`）的顺序：

```
registerAndStartHandlers()                      ← Handler.start()
loadAllActiveTriggers()                         ← 逐个装载已启用工作流，不派发（§2.4）
observeXposedChannelState()
WorkflowPermissionRecovery.recoverEligibleWorkflows()   ← 可能立刻改 isEnabled（AUTOMATIC）
```

⚠️ 最后一行发生在**服务刚起来的时候**。这正是 §3.2 那条过滤必须存在的第二个理由：
若用「首次看到就当作变化」的朴素基线策略，**开机即误触发**。

本设计**不需要**在 Handler 里做基线播种（旧状态由 delta 携带，§2.3），
因此天然没有「留 null 会误触发一次」的经典坑（对照 `DoNotDisturbTriggerHandler`
的 `lastKnownEnabled` 基线，survey §4.4）。

**唯一的基线要求**：`oldIsEnabled == null`（旧工作流不存在）⇒ **不派发**。

---

## 5. 环路与重入

### 5.1 自指用法天然不成环

由 §2.5 的「派发在 `handleWorkflowChanged` 之后」保证：

| 用户配置 | 执行轨迹 | 结果 |
|---|---|---|
| A 的触发器 = 「当 A 被**启用**」，A 的步骤 = 「关闭 A」 | 启用 A → A 的触发器刚注册 → 派发 → A 跑 → A 关自己 → 关闭时 A 的触发器**已被移除** ⇒ 不再派发 | ✅ 终止 |
| A 的触发器 = 「当 A 被**关闭**」，A 的步骤 = 「启用 A」 | 关闭 A → A 的触发器**已被移除** → 派发时查不到 ⇒ 不执行 | ✅ 终止 |

### 5.2 跨工作流的环是**有限**的

```
A 被关闭 → 触发 B → B 关掉 A → 触发 B → ⛔ 被 BLOCK_NEW 挡住
```

`WorkflowReentryBehavior` 默认 `BLOCK_NEW`（`WorkflowReentryBehavior.kt`）：
同一个工作流还在跑时，新触发被忽略并记 `REENTRY_BLOCKED_NEW_EXECUTION`
⇒ A↔B 的 ping-pong 在**第二轮**终止。

**要写进文档的两点**：

1. 用户把 `reentryBehavior` 改成 `ALLOW_PARALLEL` 时环会**真正无限**。
   这是既有机制的用户选择，本设计**不做额外防护** —— 与其它触发器一致。
2. **B 模块引入的新环形态**：「总开关」工作流批量启用子工作流时，
   若某个子工作流的 A 触发器又指回总开关，环由 `BLOCK_NEW` 兜住（同上）。

---

## 6. A 触发器：模块定义

| 项 | 值 |
|---|---|
| `id` | `vflow.trigger.workflow_toggle` |
| `categoryId` | `trigger`（必须 —— survey §6：`id` 前缀与 `categoryId` **缺一不可**） |
| `requiredPermissions` | **空**（零权限，不涉及任何系统能力） |
| `uiProvider` | `WorkflowToggleTriggerUIProvider`（§8） |
| `aiMetadata` | **不设** —— 与现有全部触发器一致（survey §8.2 短板 7） |

### 6.1 参数与输出

**参数（2 个）**

| key | 类型 | 说明 |
|---|---|---|
| `workflow_id` | `STRING` | 目标工作流。`acceptsMagicVariable = false`（**必须是静态选择**，与 `CallWorkflowModule` 对齐）。存**工作流 id**，改名不影响绑定 |
| `state` | `ENUM` | `any` / `enabled` / `disabled`，`inputStyle = CHIP_GROUP`，默认 `any`。序列化值用稳定英文标识符，读取**必走 `normalizeEnumValue`**（survey §9.2 坑 2） |

**输出（4 项）**

| id | 类型 | 说明 |
|---|---|---|
| `workflow_id` | `STRING` | 目标工作流 id |
| `workflow_name` | `STRING` | 目标工作流**当时**的名字（改名后历史事件仍拿旧名） |
| `is_enabled` | `BOOLEAN` | 变化**后**的状态 |
| `was_enabled` | `BOOLEAN` | 变化**前**的状态 |

载荷用 `@Parcelize WorkflowToggleTriggerData`（照 `FoldTriggerData` 的形态），
不走 `VDictionary` —— 有明确结构的事件用强类型载荷更不容易写错。

---

## 7. B 模块：`vflow.logic.set_workflow_enabled`（设置工作流开关）

### 7.1 现状核实

**仓库目前没有这个能力** —— 已逐条核对 `ModuleRegistry` 的全部注册项：
与「工作流」相关的只有 `CallWorkflowModule`（调用 / 执行）与
`DefineFunctionModule` / `CallFunctionModule`（函数工作流），
**没有任何一个模块写 `isEnabled`**。✅ 确实是新增能力。

> ⚠️ 但**AI 层已经有了**：`ChatAgentModuleExecutor.kt:1522` 的 `update_workflow`
> 会写 `isEnabled`。⇒ B 模块对 AI 不是「新增权限」，只是把既有能力接到编排面上（§7.5）。

### 7.2 定义

| 项 | 值 |
|---|---|
| `id` | `vflow.logic.set_workflow_enabled` |
| `category` / `categoryId` | 逻辑控制 / `logic`（紧邻 `CallWorkflowModule` —— 同属「对工作流本身操作」） |
| `requiredPermissions` | **空** |
| `uiProvider` | `SetWorkflowEnabledUIProvider`（复用 §8 的选择器） |

**参数（2 个）**

| key | 类型 | 说明 |
|---|---|---|
| `workflow_id` | `STRING` | 目标工作流，静态选择，与 §6.1 同构 |
| `action` | `ENUM` | `enable` / `disable` / `toggle`，`inputStyle = CHIP_GROUP`，默认 `enable` |

> `toggle`（取反）是**有意**提供的：它让「一个工作流做总开关」成为一行配置，
> 而不必先读状态再判断。代价是环路形态多一种 —— 已由 §5 兜住。

**输出（4 项）**

| id | 类型 | 说明 |
|---|---|---|
| `workflow_name` | `STRING` | 目标工作流名 |
| `previous_enabled` | `BOOLEAN` | 写入前的状态 |
| `is_enabled` | `BOOLEAN` | 写入后的状态 |
| `changed` | `BOOLEAN` | **是否真的发生了变化**。`false` = 目标本来就是这个状态（此时**不会**触发 A，见 §3.3 的判定规则） |

### 7.3 实现要点

```kotlin
val target = WorkflowManager(context.applicationContext).getWorkflow(workflowId)
    ?: return ExecutionResult.Failure("执行错误", "找不到 ID 为 '$workflowId' 的工作流。")

val desired = when (action) { "enable" -> true; "disable" -> false; else -> !target.isEnabled }
val changed = desired != target.isEnabled

if (changed) {
    // ⚠️ origin 走默认值 EXPLICIT —— 这正是「B 的动作要触发 A」的实现方式，
    //    **不要**为了「避免打扰」在这里传 AUTOMATIC。
    // ⚠️ wasEnabledBeforePermissionsLost = false 与列表页 / 磁贴逐字一致：
    //    它表达「这是显式意图」，从而**不会**被 recoverEligibleWorkflows 自动重开。
    WorkflowManager(context.applicationContext).saveWorkflow(
        target.copy(isEnabled = desired, wasEnabledBeforePermissionsLost = false)
    )
}
```

`changed == false` 时**不写盘** —— 避免制造一次无意义的 delta（写盘会走一遍
`notifyWorkflowChanged` + `TileRefreshNotifier`，纯浪费）。

### 7.4 目标缺权限时会被回弹（**必须在模块描述里写清**）

模块启用一个**缺权限**的工作流时，链路是：

```
B 模块写 isEnabled=true（EXPLICIT）⇒ 派发「已启用」
  ⇒ TriggerService.handleWorkflowChanged ⇒ 权限检查不过
       ⇒ 异步 recoverWorkflowPermissionsAndApplyState
            ⇒ 仍缺 ⇒ saveWorkflow(isEnabled=false, wabpl=true, AUTOMATIC)
```

⇒ **用户的观感是「模块说启用成功了，但工作流还是关的」**，且**不会**收到「已关闭」事件
（AUTOMATIC 被过滤）。这与列表页 / 磁贴的既有行为**完全一致**（那两处也回弹），
不是本模块的新问题。

**定案：模块不做权限预检。** 理由：那会造出**第二套权限判据**，
与服务侧的权威判据必然漂移（`sim_data_switch` 的教训：权限声明与触发链路的判据只能有一处）。
改为在模块描述文案里如实写明「目标缺权限时可能被自动关闭」。

### 7.5 AI 元数据

建议**给**（与 `BackupExportModule` 同款）：

```kotlin
override val aiMetadata = AiModuleMetadata(
    usageScopes = setOf(AiModuleUsageScope.DIRECT_TOOL, AiModuleUsageScope.TEMPORARY_WORKFLOW),
    riskLevel = AiModuleRiskLevel.STANDARD,
    directToolDescription = "启用 / 关闭 / 切换一个已存在的工作流",
)
```

理由：AI **本来就能**通过 `update_workflow` 改 `isEnabled`（§7.1），
所以这不是新增权限面，只是让 AI 用**更窄**的工具（明确的目标 + 动作）替代
「读整个工作流 → 改一个字段 → 整体写回」的高风险路径。

> ⚠️ 与触发器不同：survey §8.2 短板 7 记着「触发器一律不设 `aiMetadata`」——
> 那是**触发器**的现状，**不适用于本动作模块**。

### 7.6 禁用「自己」或「正在运行的自己」

- **禁用自己**：写盘后 `handleWorkflowChanged` 会移除自己的全部触发器，
  但**当前这次执行不会被打断**（`WorkflowExecutor` 不在步骤间复查 `isEnabled`）。
  ⇒ 语义是「执行完这一步，之后我不再被触发」，**不是**「立刻停止」。
  想立刻停用 `StopWorkflowModule`。
- **禁用正在被 `CallWorkflowModule` 调用的父工作流**：同上，父执行不受影响。

⇒ 写进模块描述，不做特殊处理。

---

## 8. 选择器：**已有现成实现，直接抄**

⚠️ 这一环最初被误判为「要自绘 `BottomSheetDialogFragment`」（因为 `PickerType` 枚举里
没有工作流选项：只有 NONE/APP/ACTIVITY/FILE/DIRECTORY/MEDIA/DATE/TIME/DATETIME/SCREEN_REGION）。

实际上 **`vflow.logic.call_workflow`（调用工作流）已有完整实现**，A 与 B **共用同一套**：

| 部件 | 位置 |
|---|---|
| `SearchableWorkflowDialog.show(...)` | `ui/common/SearchableWorkflowDialog.kt`（带搜索的 `AlertDialog` + `ListView`） |
| `WorkflowDialogItem(id, name)` | 同上 |
| UIProvider 范式（`getHandledInputIds` / `createEditor` / `readFromEditor` / `createPreview`） | `core/workflow/module/logic/CallWorkflowModuleUIProvider.kt` |
| 摘要 pill 范式（含 `summary_unknown_workflow` / `summary_no_workflow_selected` 兜底） | `core/workflow/module/logic/CallWorkflowModule.kt` 的 `getSummary` |

⇒ 两个 UIProvider 都是 `CallWorkflowModuleUIProvider` 的**同构复制**，改两处文案即可。
**不要**新造 `PickerType` 枚举值（那要改上游的 `definitions.kt` + `PickerHandler.kt` +
`StandardControlFactory.kt`，diff 面大得多）。

### 8.1 候选过滤：只有「有开关状态」的工作流可选

> ⚠️ **本节是 2026-10-10 真机反馈后补的** —— 首版两个选择器都直接喂 `getAllWorkflows()`，
> 于是**手动触发的工作流也能被选中**。用户实测指出：那种工作流**没有开关**。

**判据**：`TileGate.accepts(TileKind.TOGGLE, workflow)`（= `workflow.hasAutoTriggers()`）。

| 事实 | 出处 |
|---|---|
| 卡片上的开关**只在** `hasAutoTriggers` 时绘制；手动型画的是 ▶ 执行按钮 | `ui/workflow_list/WorkflowListScreen.kt:933` / `:944` |
| 手动型的 `isEnabled` **毫无作用**（不阻止手动执行） | `WorkflowExecutor` / `ManualTriggerModule` 都不读它 |
| `TileGate` 是「这一池接不接受这个工作流」的**唯一判据落点** | `core/workflow/TileGate.kt` 的 KDoc 明写「任何一处自己写 `hasAutoTriggers()`，都会让『菜单项显示着、点了却被拒绝』这类不一致出现」 |

⇒ **不要在 UIProvider 里自己写 `hasAutoTriggers()`**，调 `TileGate`。

**为什么 B（动作模块）也要过滤**（它技术上能写手动型的 `isEnabled`）：那个字段对手动型
**没有任何可见效果**，却会在用户**之后**给该工作流加自动触发器时留下一个存量
`isEnabled = false`（编辑器保存时 `isEnabled = currentWorkflow?.isEnabled ?: true` 会**保留**它）
⇒ **新触发器静默不注册**。与其让用户踩这个坑，不如根本不提供这个选项。

**空态必须说出来**：候选为空时直接弹空列表，用户看到的是 `workflow_search_no_results`
（「没有找到相关工作流」）—— 那是「**搜不到**」，而这里是「**一个都没有**」，观感是「功能坏了」。
⇒ 提前 `Toast` 一条 `toast_no_toggleable_workflow`（三语）并**不打开**对话框。

**锚定**：`test/.../core/workflow/module/WorkflowTogglePickerWiringTest.kt`（4 例，含反向锁
「不得再出现 `getAllWorkflows().map` 直接喂对话框」与三语键存在性）。三条反证已实做。

**已知未处理（如实记录）**：目标工作流**绑定时**合法、**之后**被删掉自动触发器（越界态）时，
A 永远不命中（无害），B 仍会写那个隐藏的 `isEnabled`。与磁贴的「闸 3」同形，
但 B 的目标是**每次执行时读到的存量 id**，加运行时守卫会引入「存量步骤开始报错」这一独立行为变更，
**本次不做**。

### 8.2 布局

**新建** `partial_workflow_picker_editor.xml`（A 与 B 共用，约 25 行），
**不要复用** `partial_call_workflow_editor.xml` —— 后者标题硬编码为
`text_workflow_to_call`（「要调用的工作流」），且含一个本模块用不上的函数参数 `include` 块。

### 8.3 目标工作流被删除

`workflow_id` 指向已删除的工作流时：

- **A**：永远不会命中（没有工作流会再改它的状态）。摘要按既有做法显示 `summary_unknown_workflow`。
- **B**：执行时返回 `ExecutionResult.Failure`（照 `CallWorkflowModule` 的既有文案）。

**不做**「自动清理绑定」—— 用户可能只是暂时删掉又用备份恢复回来（id 不变）。

---

## 9. 实现清单

### 9.1 新增文件（fork 独有，零冲突面）

| # | 文件 | 内容 |
|---|---|---|
| 1 | `core/workflow/WorkflowWriteOrigin.kt` | §3.3 的来源枚举 |
| 2 | `core/workflow/module/triggers/WorkflowToggleTriggerModule.kt` | A 的模块定义 |
| 3 | `core/workflow/module/triggers/WorkflowToggleTriggerData.kt` | A 的 `@Parcelize` 载荷 |
| 4 | `core/workflow/module/triggers/WorkflowToggleTriggerUIProvider.kt` | §8 |
| 5 | `core/workflow/module/triggers/handlers/WorkflowToggleTriggerHandler.kt` | 继承 `BaseTriggerHandler`；暴露 `onWorkflowSaved(context, newWorkflow, oldIsEnabled, origin)` |
| 6 | `core/workflow/module/logic/SetWorkflowEnabledModule.kt` | B 的模块定义 + 执行逻辑 |
| 7 | `core/workflow/module/logic/SetWorkflowEnabledUIProvider.kt` | §8 |
| 8 | `res/layout/partial_workflow_picker_editor.xml` | A / B 共用 |
| 9 | `res/drawable/rounded_*.xml` | 两个模块的图标 |
| 10 | `test/.../triggers/WorkflowToggleTriggerTest.kt` | §11.2 |
| 11 | `test/.../logic/SetWorkflowEnabledModuleTest.kt` | §11.2 |

### 9.2 上游文件改动

| # | 文件 | 改动 | 该文件此前是否已有分歧 |
|---|---|---|---|
| 1 | `core/workflow/module/ModuleRegistry.kt` | **追加 2 行**（触发器段 1 行 + 逻辑段 1 行，均不重排） | ✅ 已有（多次追加） |
| 2 | `.../triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` **追加 1 行** | ✅ 已有 |
| 3 | `core/workflow/WorkflowManager.kt` | `saveWorkflow` 签名**追加 1 个带默认值的参数**；内部透传给 Proxy | ✅ 已有 |
| 4 | `services/WorkflowTriggerDelta.kt` | 追加 2 个字段（均带默认值） | ⚠️ **尚无** |
| 5 | `services/TriggerServiceProxy.kt` | 填这 2 个字段 | ⚠️ **尚无** |
| 6 | `services/TriggerService.kt` | ① `onStartCommand` 分支追加派发调用；② 2 处自动禁用传 `AUTOMATIC` | ✅ 已有（`observeXposedChannelState`） |
| 7 | `core/workflow/WorkflowPermissionRecovery.kt` | 1 处传 `AUTOMATIC` | ⚠️ **尚无** |
| 8 | `ui/workflow_list/WorkflowListRoute.kt` | 回弹那 1 处传 `AUTOMATIC` | ✅ 已有 |

> ✅ **`ui/tile/BaseToggleTileService.kt` 是 fork 自有的新增文件**（`5333676c`），
> 那处回弹的标记**零冲突面**。

**净新增冲突面 = 3 个上游文件各 1 行**（#4 / #5 / #7）。其余 5 个都已在 `FORK.md` 的分歧清单里。

### 9.3 文案

三语**同步**（`values` / `values-en` / `values-ja`）：

- A：模块名 / 描述 / 2 个参数名 / 3 个枚举选项 / 4 个输出名 / 摘要前缀 / 进度消息 / 选择对话框标题
- B：模块名 / 描述 / 2 个参数名 / 3 个枚举选项 / 4 个输出名 / 摘要前缀 / 进度消息 / **「目标缺权限时可能被自动关闭」的说明**

> ⚠️ 追加前先 `grep` 键名 —— **重复键会直接构建失败**（`FORK.md` 敏感点段已记录）。
> `summary_unknown_workflow` / `summary_no_workflow_selected` **复用既有键，不要新建**。

### 9.4 登记

`FORK.md` 分歧清单追加：§9.1 的 11 个新文件 + §9.2 的 8 处改动 + §9.3 的文案块。
**特别登记**：

- `saveWorkflow` 的**签名变更**（上游若新增调用点，默认值 = `EXPLICIT`，是安全方向）；
- §3.3 证伪 `wabpl` 借用方案的**理由**（防将来有人「简化」回去）；
- 5 处 `AUTOMATIC` 标记点的清单（防漏改）。

---

## 10. 决策台账

| # | 决策 | 依据 |
|---|---|---|
| 1 | A 的触发目标 = 指定单个工作流 | 用户 2026-10-10 拍板 |
| 2 | 新增 B 模块（启用/关闭/切换） | 用户 2026-10-10 要求；仓库确实没有（§7.1 已核实） |
| 3 | **引入显式 `WorkflowWriteOrigin`，默认 `EXPLICIT`** | §3.3 —— 借用 `wabpl` 被 B 模块证伪 |
| 4 | 默认值取 `EXPLICIT`（忘标记 ⇒ 多触发，可见） | §3.3「可见的失败优于静默失效」 |
| 5 | 不破坏 `wabpl` 的既有语义 | §3.3 被否方案表（它同时是回弹判据） |
| 6 | 不做去抖，接受回弹「第一跳」 | §3.4（修它的代价大于收益） |
| 7 | 走既有 `ACTION_WORKFLOW_CHANGED` Intent 链路 | §2.2（语义本来就重合；服务未运行也能拉起） |
| 8 | 不复用 `WorkflowDataChangeBus` | §2.2 注（它只由磁贴发布，覆盖不了列表页开关） |
| 9 | 旧状态 + 来源都由 delta 携带，不用 Handler 自持缓存 | §2.3（缓存会漏掉服务冷启动那一次） |
| 10 | 接线点在 `onStartCommand` 分支，不改 `handleWorkflowChanged` 签名 | §2.4（该方法有「开机装载」第二个调用方） |
| 11 | 派发放在 `handleWorkflowChanged` **之后** | §2.5（兼作自指环路的天然防护，§5.1） |
| 12 | A 继承 `BaseTriggerHandler` | 无监听生命周期；`ListeningTriggerHandler` 四方法 `final` 且只在「空↔非空」边界触发（survey §9.2 坑 5） |
| 13 | A 零 `requiredPermissions` | 不涉及系统能力。⚠️ 但**不要**因此以为它不受权限体系影响 —— §3 |
| 14 | B 的 `action` 提供 `toggle` | §7.2（让「总开关」成为一行配置） |
| 15 | B 在 `changed == false` 时不写盘 | §7.3（避免无意义的 delta 与磁贴刷新） |
| 16 | B **不做**权限预检 | §7.4（避免第二套权限判据漂移） |
| 17 | B 的写入保持 `origin = EXPLICIT` | §7.3 + 用户第 3 条要求 |
| 18 | B 给 `aiMetadata`，A 不给 | §7.5（AI 本来就能改 `isEnabled`；触发器一律不给是既有纪律） |
| 19 | 目标选择器复用 `SearchableWorkflowDialog` | §8 |
| 20 | 新布局，不复用 `partial_call_workflow_editor.xml` | §8.2（标题文案与结构都不匹配） |
| 21 | 目标工作流被删除时不自动清理绑定 | §8.2（备份恢复后 id 不变） |
| 22 | 禁用「自己」不打断当前执行 | §7.6（与执行器既有行为一致） |

---

## 11. 风险与验证

### 11.1 风险

| # | 风险 | 处置 |
|---|---|---|
| R1 | **回弹「第一跳」误报**：用户「开→权限不够→回弹关」会派发一次「已启用」 | §3.4 定案接受，写进模块描述 |
| R2 | **将来新增程序性写入点忘了标 `AUTOMATIC`** ⇒ 多触发 | 默认值方向选对了（可见失败）；§11.2 用源码扫描测试锚定既有 5 处 |
| R3 | **`reentryBehavior = ALLOW_PARALLEL` 下环路无限** | §5.2 不额外防护，与既有机制一致 |
| R4 | **B 模块的 `toggle` 与 A 触发器组合出环** | §5.2 由 `BLOCK_NEW` 兜住；文档记录 |
| R5 | B 启用一个缺权限的工作流会被回弹，用户观感是「没生效」 | §7.4 不做预检，靠文案 + 日志 |
| R6 | `saveWorkflow` 签名变更与上游冲突 | §9.2 已按「带默认值的追加参数」实现，冲突面 1 行 |
| R7 | 服务冷启动时 `onCreate` 的 `recoverEligibleWorkflows` 与首次 `onStartCommand` 的时序 | 旧状态 + 来源随 Intent 到达，不依赖内存基线 ⇒ 天然正确；§11.3 场景 7 专门覆盖 |

### 11.2 单测（纯 JVM，不需要设备）

| 组 | 用例 |
|---|---|
| A 判定纯函数 | `EXPLICIT` + 状态翻转 → 派发；`AUTOMATIC` → 过滤（**双向**）；`oldIsEnabled == null` → 不派发；同值 → 不派发；`state` 过滤（`enabled` / `disabled` / `any`） |
| B 判定纯函数 | `enable` / `disable` / `toggle` 三态的目标值推导；`changed` 的计算；`changed == false` 时不调用 `saveWorkflow` |
| 枚举归一化 | 两个模块的 ENUM 参数都走 `normalizeEnumValue`；未知值回落默认档 |
| **源码扫描锚定** | ⚠️ 必做，四组：① 两个 Registry 各有注册行（漏一处 = **能选能配、后台永不触发**，survey §8.2 短板 1）；② `onStartCommand` 的派发调用在 `handleWorkflowChanged` **之后**；③ **`saveWorkflow` 的 5 处 `AUTOMATIC` 标记点逐处存在**（§3.3 —— 漏一处的后果是「自动禁用也触发」）；④ `TriggerServiceProxy` 真的填了 `oldIsEnabled` 与 `origin`（防「字段加了但没人填」—— 本仓库在 `CoreDexFingerprint` 上踩过同类坑） |

### 11.3 真机场景（零破坏性操作：只 `adb install -r`，见 `AGENTS.md`）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 列表页关掉 A | 触发器工作流跑一次，`is_enabled=false` / `was_enabled=true` |
| 2 | QS 磁贴开关 A | 同上 |
| 3 | **B 模块关闭 A** | **触发**（用户第 3 条要求） |
| 4 | **B 模块对已是该状态的目标再设一次** | **不触发**，输出 `changed=false` |
| 5 | A 因缺权限被自动禁用 | **不触发**（§3.2 核心场景） |
| 6 | 进 App / 重启服务（触发 `recoverEligibleWorkflows`） | **不触发**（§3.2 核心场景） |
| 7 | 强杀进程后直接开关 A | 服务被拉起后**仍能正确触发**（§11.1 R7） |
| 8 | 用户开 A 但权限不够（回弹） | 派发一次「已启用」——**确认这是已知行为**，不是 bug |
| 9 | A 的触发器 =「A 被启用」，A 步骤 =「关闭 A」 | 跑一次即终止，**不循环**（§5.1） |
| 10 | B 模块 `toggle` 一个已启用工作流 | 变成关闭，且触发 |
| 11 | B 模块启用一个缺权限的工作流 | 返回成功，随后被回弹为关闭，**无**「已关闭」事件（§7.4） |
| 12 | 目标工作流被删除 | A：摘要显示「未知工作流」；B：执行失败并给出明确原因。均不崩 |

> ⚠️ 场景 5/6 是**必须不触发**的核心断言；场景 3 是**必须触发**的核心断言。
> 这三条同时成立，才证明 §3.3 的显式来源机制是对的 —— 而借用 `wabpl` 的方案
> **无法同时满足场景 3 与 6**。



