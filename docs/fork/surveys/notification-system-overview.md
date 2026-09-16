# 通知体系梳理（本项目现状）

> **版本**：v1.0 · 2026-09-15 代码走查定稿
> **对应分支**：`session-0915-02`（基于 `dev`，`versionName 1.5.4` / `versionCode 50`）
> **目录归属**：fork 独有文档 → 冲突归**我方**（上游无此文件）
> **用途**：改造通知机制（尤其是适配小米澎湃 OS 超级岛）之前的现状地图。
> **证据**：本文所有行号均为 2026-09-15 直读代码所得；数字为人工走查，会随代码漂移，引用前以代码为准。

---

## 1. 总览：先分清「两套通知」

项目里「通知」是**两条方向相反、几乎不共享代码**的链路。谈改造前必须先说清改哪一条：

| | 方向 | 角色 | 核心文件 |
|---|---|---|---|
| **A. 发出通知** | App → 系统通知栏 | **生产者**，共 **8 处**独立实现 | `services/*`、`ui/chat/ChatFloatWindowService.kt`、`core/workflow/module/notification/SendNotificationModule.kt` |
| **B. 收取通知** | 系统通知栏 → App | **消费者**，单点监听 | `services/VFlowNotificationListenerService.kt` + `triggers/handlers/NotificationTriggerHandler.kt` |

两条链路唯一的交汇点是 `NotificationTriggerHandler`——B 链路把监听服务实例挂在静态变量上（`NotificationTriggerHandler.kt:22`），供 A 链路之外的「查找/移除通知」模块复用（`FindNotificationModule.kt:97`、`RemoveNotificationModule.kt:64`）。

**关键判断：超级岛适配属于 A 链路。** B 链路是读别人的通知，与「自己的通知怎么显示在岛上」无关（除非做「岛上回消息」这类闭环，见 §7）。

---

## 2. A 链路：发出通知（8 处生产者）

### 2.1 全量清单

| # | 位置 | Channel ID | 通知 ID | 重要性 | 性质 | 用途 |
|---|---|---|---|---|---|---|
| 1 | `ExecutionNotificationManager.kt:32,34` | `workflow_execution_channel` | **1998**（固定） | `IMPORTANCE_DEFAULT` | **动态/可变** | 工作流执行进度 |
| 2 | `TriggerService.kt:58-59` | `trigger_service_channel` | 2（固定） | `IMPORTANCE_LOW` | 常驻（前台服务） | 后台触发器服务 |
| 3 | `VoiceTriggerService.kt:43-44` | `voice_trigger_service_channel` | 2002（固定） | `IMPORTANCE_LOW` | 常驻（前台服务，microphone） | 语音触发监听 |
| 4 | `PermissionGuardianService.kt:37-38` | `permission_guardian_channel` | 1001（固定） | `IMPORTANCE_LOW` | 常驻（前台服务） | 权限守护轮询 |
| 5 | `ChatFloatWindowService.kt:89-90` | `vflow_chat_float` | 97010（固定） | `IMPORTANCE_LOW` | 常驻（前台服务） | AI 对话悬浮窗 |
| 6 | `ExecutionUIService.kt:39-42` | `vflow_interactive_notifications` | 1000 起自增 | `IMPORTANCE_HIGH` | 一次性 | 后台交互回退（见 2.4） |
| 7 | `CoreManagementService.kt:185,219` | `core_autostart_channel` | 3001 / 3002 | `IMPORTANCE_DEFAULT` | 一次性 | Core 自启失败 / 无偏好提示 |
| 8 | `SendNotificationModule.kt:49` | `vflow_custom_notifications` | `System.currentTimeMillis()`（唯一化，`:136`） | `IMPORTANCE_DEFAULT` | 一次性 | 用户可编排的「发送通知」模块 |

### 2.2 唯一有「动态性」的一处：ExecutionNotificationManager

这是整个项目里**唯一**用到活体/进度通知 API 的地方，也是超级岛改造的主战场。

- 单例 object，需先 `initialize(context)`；调用点共 4 处：`MainActivity.kt:197`、`ShortcutExecutorActivity.kt:21`、`TriggerService.kt:87`、`VoiceTriggerService.kt:149`。
- 状态模型（`ExecutionNotificationManager.kt:20-24`）：
  ```kotlin
  sealed class ExecutionNotificationState {
      data class Running(val progress: Int, val message: String)
      data class Completed(val message: String)
      data class Cancelled(val message: String)
  }
  ```
- 用户开关：读 `vFlowPrefs` 的 `progressNotificationEnabled`（`:70-73`），默认 true。
- **版本二分**（`:77-82`）：
  ```kotlin
  if (Build.VERSION.SDK_INT >= 36) buildStatusChipNotification(...)
  else buildLegacyNotification(...)
  ```
- **API 36+ 分支**（`buildStatusChipNotification`，`:88-148`，标注 `@RequiresApi(36)`）：
  - `Running` 态（`:107-122`）：`setOngoing(true)` + **`setRequestPromotedOngoing(true)`**（`:112`，请求提升为 Status Chip / 活体通知）+ `setProgress(100, progress, false)`（`:115`）+ 一个「结束」action 按钮（`:117-121`，图标 `rounded_close_small_24`）。
  - `Completed` 态：`setOngoing(false)`、`setRequestPromotedOngoing(false)`、`setAutoCancel(true)`、`setProgress(0,0,false)`，小图标临时换成 `rounded_save_24`（`:133`）。
  - `Cancelled` 态：同上，小图标换 `rounded_close_small_24`。
- **旧版本分支**（`buildLegacyNotification`，`:153-182`）：标准 `setProgress`，无 action 按钮、无提升请求。
- 收起时的图标固定为 `R.drawable.ic_workflows`（`:103`）。
- `cancelNotification()`（`:188-190`）由 `WorkflowExecutor.kt:328` 在**延迟 3 秒后**调用（`:327`），给用户看最终状态的时间。

**调用点全在 `WorkflowExecutor.kt`**（这是「改执行器风险高」的又一体现）：

| 行 | 状态 |
|---|---|
| `:189` | `Running(0, "正在开始...")` |
| `:231` | `Cancelled("执行超时（N秒）")` |
| `:253` | `Completed("执行完毕")` |
| `:259` | `Cancelled("已停止")` |
| `:263` | `Cancelled("执行异常")` |
| `:508` | `Running(progress, "步骤 x/y: 模块名")` — 每步推进 |
| `:573` | `Running(progress, "重试 (n/m): 模块名")` |
| `:580` | `Running(progress, progressUpdate.message)` — 模块自定义进度 |
| `:328` | `cancelNotification()` |

> 进度语义：`progress = (pc * 100) / workflow.steps.size`（`:506`），**按顶层步骤数算，不感知 If/Loop 内部步骤**。

### 2.3 常驻前台服务通知（4 处，模式高度雷同）

`TriggerService` / `VoiceTriggerService` / `PermissionGuardianService` / `ChatFloatWindowService` 四者的实现几乎是一个模板的复制：

1. `onCreate`（或 `startForegroundSafely`）里 `createNotificationChannel()` + `createNotification()` + `startForeground(...)`；
2. 渠道一律 `IMPORTANCE_LOW`（避免打扰）；
3. 通知内容一律「标题 + 文本 + `ic_workflows` 小图标 + `setOngoing(true)`」，点按回 `MainActivity`。

两处值得注意的**特例**：

- `TriggerService.kt:194-211` `updateForegroundState()`：**先无条件 `startForeground`** 满足契约，再读 `vFlowPrefs.backgroundServiceNotificationEnabled`；若用户关掉了通知，则 `stopForeground(STOP_FOREGROUND_REMOVE)` 降级为后台服务（注释自陈：服务仍在跑，但「容易被杀」）。
- `VoiceTriggerService.kt:289-303`：用 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`，失败则 `stopSelf()`（麦克风前台服务类型受系统限制）。

### 2.4 三层回退里的「通知」：ExecutionUIService

`ExecutionUIService`（`:26-33` 注释）是「工作流需要用户交互」时的兜底通道，三层回退：

1. 直接 `startActivity`（前台场景）；
2. Shizuku `am start`（后台强制启动）；
3. **回退到发一条高优先级通知**（`:163-187` `postNotification`）。

第 3 层的特征：`IMPORTANCE_HIGH` 渠道 + `PRIORITY_HIGH` + **`CATEGORY_CALL`**（`:179`，借通话类通知的高优先级）+ 点击 Intent 直达 `OverlayUIActivity`。通知 ID 从 1000 起自增（`:42`）。

所有对外交互入口都走这条路：`requestInput` / `requestMenuChoice` / `requestSpeechToText` / `showQuickView` / `showQuickViewImage` / `requestImage` / `showWorkflowChooser` / `requestShare` / `requestMediaProjectionPermission` / `showError`。

### 2.5 通知栏按钮 → 回调

`WorkflowActionReceiver.kt`（注册于 `AndroidManifest.xml:298-303`，`exported=false`）是唯一的通知交互回调：

- 常量：`ACTION_STOP_WORKFLOW = "com.chaomixian.vflow.action.STOP_WORKFLOW"`、`EXTRA_WORKFLOW_ID = "workflow_id"`（`:15-16`）；
- 收到后调 `WorkflowExecutor.stopExecution(workflowId)`（`:24`）。
- 目前**只有** `ExecutionNotificationManager` 的 `Running` 态用了它（`:90-99` 构造 PendingIntent，`:117-121` 挂按钮）。

> 也就是说：8 处生产者里，**只有 1 处有可交互按钮**。

---

## 3. B 链路：收取通知（监听）

### 3.1 结构

```
VFlowNotificationListenerService (NotificationListenerService，系统绑定)
   │  静态转发
   ▼
NotificationTriggerHandler (ListeningTriggerHandler 子类，单例 + 静态实例)
   │  逐条匹配 listeningTriggers
   ▼
NotificationTriggerModule (触发器模块，配置载体)
   │  execute() 装配 outputs
   ▼
NotificationObject / VNotification (类型系统)
```

- `VFlowNotificationListenerService.kt`：极薄的一层。`onListenerConnected/Disconnected` 转发给 Handler 的静态方法（`:17,23`），`onNotificationPosted` 转发 `sbn`（`:29`）。**`onNotificationRemoved` 是空的**（`:32-35`，注释说「未来需要时可加」）。
- `NotificationTriggerHandler.kt:22` 持有 `notificationListener` 静态引用——这是 B 链路的服务实例被其他模块复用的唯一桥梁。
- `startListening`（`:54-72`）：若服务未连接，主动 `NotificationListenerService.requestRebind(...)` 请求系统重绑。

### 3.2 匹配逻辑（`NotificationTriggerHandler.kt:79-121`）

- 只取 `EXTRA_TITLE` 与 `EXTRA_TEXT` 两个字段（`:83-84`）。**不读 `EXTRA_BIG_TEXT`、`EXTRA_SUB_TEXT`、`EXTRA_TEXT_LINES`**——富通知（如聊天应用的多行/展开态）会漏内容。
- 过滤维度三组，组间 AND：
  - 应用：`app_filter_type`（include/exclude，`:91`）× `packageNames`（列表，兼容旧的单值 `app_filter`，`:93-95`）；
  - 标题：`title_filter_type` × `title_filter`；
  - 内容：`content_filter_type` × `content_filter`。
- 文本匹配统一走 `matchesTextFilter`（`:123-127`）：`contains(ignoreCase = true)`，空/空白视为「不过滤（恒真）」。
- 命中后组一个 `VDictionary` 传给触发器（`:111-116`），键为 `package_name` / `title` / `content` / `id`（`id` 取 `sbn.key`）。
- ⚠️ **执行上下文取自静态 `notificationListener?.applicationContext`**（`:117`），拿不到时 `return@forEach` 静默跳过。

### 3.3 复用同一 listener 的两个模块

- `FindNotificationModule.kt:110-126`：遍历 `listener.activeNotifications`，按 `app_filter`（**精确包名相等**，非包含）/ `title_filter` / `content_filter` 过滤，产出 `VList<VNotification>`。服务不可用时返回 Failure（`:97-101`）。
- `RemoveNotificationModule.kt:82-84`：对目标列表逐个 `listener.cancelNotification(it.id)`。

> 这两个模块与触发器**共用同一套字段抽取方式**（同样只取 `EXTRA_TITLE` / `EXTRA_TEXT`），所以 §3.2 的「富通知漏字段」问题在这里同样存在。

### 3.4 类型系统

- `NotificationObject`（`core/workflow/module/notification/NotificationObject.kt:16-21`）：`@Parcelize data class(id, packageName, title, content)`——**只有 4 个字段**。
- `VNotification`（`core/types/complex/VNotification.kt:15-45`）：基于 `PropertyRegistry` 暴露属性 `title` / `content` / `package` / `id`。`asString()` 返回 `"title: content"`，`asBoolean()` 恒 true。
- `VTypeRegistry.NOTIFICATION` 是类型 ID 的来源（`NotificationObject.kt:24`）；`api/model/WorkflowModels.kt:17` 另有 `TYPE_NOTIFICATION = "notification"`（REST API 的类型名，与上者不同层）。

---

## 4. 渠道与权限清单

### 4.1 通知渠道（8 个，全部在代码里就地创建，无集中注册表）

| Channel ID | 声明位置 | 重要性 |
|---|---|---|
| `workflow_execution_channel` | `ExecutionNotificationManager.kt:32` | DEFAULT |
| `trigger_service_channel` | `TriggerService.kt:59` | LOW |
| `voice_trigger_service_channel` | `VoiceTriggerService.kt:43` | LOW |
| `permission_guardian_channel` | `PermissionGuardianService.kt:37` | LOW |
| `vflow_chat_float` | `ChatFloatWindowService.kt:89` | LOW |
| `vflow_interactive_notifications` | `ExecutionUIService.kt:39` | HIGH |
| `core_autostart_channel` | `CoreManagementService.kt:185,219`（两处内联字面量） | DEFAULT |
| `vflow_custom_notifications` | `SendNotificationModule.kt:49` | DEFAULT |

渠道名只有部分走字符串资源（`trigger_service_notification_channel_name` / `voice_trigger_service_notification_channel_name` / `permission_guardian_service_channel` / `core_autostart_notification_channel_name` / `channel_vflow_notification_custom` / `chat_float_channel_name`），`ExecutionUIService.kt:40` 的「交互式请求」与 `ExecutionNotificationManager.kt:33` 的「工作流执行状态」是**硬编码中文**。

### 4.2 权限（`AndroidManifest.xml`）

| 权限 | 行 | 用途 |
|---|---|---|
| `POST_NOTIFICATIONS` | `:20` | Android 13+ 发通知基础权限 |
| **`POST_PROMOTED_NOTIFICATIONS`** | `:21` | **活体通知/Status Chip 提升**——超级岛最相关 |
| `ACCESS_NOTIFICATION_POLICY` | `:22` | 勿扰模式（DoNotDisturbModule） |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | `:34-35` | B 链路 |
| `FOREGROUND_SERVICE(_SPECIAL_USE/_MICROPHONE)` | `:13,14,19` | 四处前台服务 |

`PermissionManager.kt` 中对应三个 Permission 对象：`NOTIFICATIONS`（`:76`）、`NOTIFICATION_LISTENER_SERVICE`（`:105`）、`NOTIFICATION_POLICY`（`:114`）；其中前两者在「可守护权限」列表里（`PermissionGuardianService.kt:49`）。

### 4.3 用户可见开关（2 个）

| 设置项 | prefs key | UI | 作用 |
|---|---|---|---|
| 执行进度通知 | `progressNotificationEnabled` | `SettingsScreen.kt:493-501` | `ExecutionNotificationManager` 总闸（`:70-73`） |
| 后台服务通知 | `backgroundServiceNotificationEnabled` | `SettingsScreen.kt:502-511` | `TriggerService.updateForegroundState`（`:196`） |

---

## 5. 模块注册与 AI 暴露面

`ModuleRegistry.kt` 中与本体系相关的注册（**按分类追加，勿重排**）：

- `:85` `NotificationTriggerModule()`（触发器分类）
- `:193` `SendNotificationModule()` / `:204` `FindNotificationModule()` / `:205` `RemoveNotificationModule()`（device 分类）

AI 元数据（`AiModuleMetadata`）现状：

- `SendNotificationModule.kt:34-43`：`directToolMetadata(...)` → 可作 **DIRECT_TOOL**，风险 `STANDARD`，必填 `title`/`message`。
- `RemoveNotificationModule.kt:27-35`：`directToolMetadata(...)` → **DIRECT_TOOL**，`STANDARD`，必填 `target`。
- `FindNotificationModule.kt:28-37`：**`TEMPORARY_WORKFLOW`**，`READ_ONLY`。
- `NotificationTriggerModule`：**未设 `aiMetadata`**，AI 不可见。

> 若改造要新增模块（如「超级岛通知」），按 `AGENTS.md` 的姿势新增文件 + 追加注册即可，不必动上述既有模块。

---

## 6. 其余相关旁支

- **勿扰模式**：`core/workflow/module/system/DoNotDisturbModule.kt` 用 `NotificationManager.INTERRUPTION_FILTER_*` 与 `automatic_zen_rule_id`（`:45`）管理 ZEN 规则（`:222-267`），属「通知策略」而非「发/收通知」。有单测 `DoNotDisturbModuleTest.kt`。
- **测试覆盖**：`app/src/test` 下**无任何通知相关测试**（`ExecutionNotificationManager`、`SendNotificationModule` 等均无单测）。`DoNotDisturbModuleTest` 是唯一的邻近测试。
- **无原生/第三方通知适配**：全仓库 grep `miui|xiaomi|hyperos|超级岛|FocusNotif|miPush` 的命中只出现在文档、issue 模板、以及 `AppStartTriggerHandler.kt:41,57` 的 MIUI 桌面/安全中心包名黑名单里。**代码中不存在任何小米通知适配。**

---

## 7. 面向超级岛改造的落点与风险（判断，非事实）

### 7.1 现状与超级岛的距离

超级岛（澎湃 OS 的焦点通知/活体通知形态）需要的是**持续更新的活体通知 + 结构化状态 + 可交互**。本项目现状：

| 能力 | 现状 | 差距 |
|---|---|---|
| 活体通知提升 | ✅ 仅 `ExecutionNotificationManager` API 36+ 分支用了 `setRequestPromotedOngoing(true)`，已声明 `POST_PROMOTED_NOTIFICATIONS` | 只此一处；且**没有任何厂商特定适配**，全靠 AOSP Live Updates |
| 进度/状态更新 | ✅ 执行态有 `setProgress`，其余 7 处都是静态文本 | 触发服务、语音监听、悬浮窗、权限守护都没有状态可展示 |
| 可交互 | ⚠️ 仅「结束」一个按钮，回调只有 `STOP_WORKFLOW` | 无暂停/重试/查看等；`WorkflowActionReceiver` 是唯一回调入口，扩展它成本低 |
| 图标/视觉 | ⚠️ 全用小图标 `ic_workflows` / `rounded_*`，无大图、无颜色 | 岛上的展示元素未做设计 |
| 结构化内容 | ❌ `NotificationCompat.Builder` 的常规字段 | 岛需要厂商约定的 extras / 特定 API |
| 多任务并发 | ❌ **通知 ID 固定为 1998**，多个工作流并发执行会互相覆盖 | 已存在的问题，做岛时会被放大 |

### 7.2 改造时应动哪里（按侵入性排序）

1. **新增层（首选，符合 fork「控制 diff 面积」原则）**：新增一个 `NotificationDispatcher` / `NotificationEnvelope` 之类的新文件，把 8 处生产者收拢到统一入口，厂商特定逻辑（超级岛 extras）收在新文件内部，由 `Build.MANUFACTURER` / 系统版本判定走哪条渲染路径。**上游文件只做最小替换**。
2. **扩展现有回调**：`WorkflowActionReceiver` 加 action 常量（新增分支，不改既有签名），成本低、收益直接。
3. **`ExecutionNotificationManager` 内部改造**：它是唯一已有活体通知逻辑的地方，但它是上游文件、且直接改不产生新文件——按 fork 惯例应优先考虑「新增文件接管」，仅在必要时改它。
4. **模块层**：新增「超级岛通知」模块（`vflow.notification.*`），走 `ModuleRegistry` 追加注册；AI 侧按需给 `directToolMetadata`。

### 7.3 已知坑（来自本文档已核实的事实）

- `WorkflowExecutor.kt` 是高风险区（`AGENTS.md` 明列），而 9 个通知调用点全在里面——**改造执行态通知无法绕开它**。若只是替换 `ExecutionNotificationManager` 内部实现，调用点可不动（这是最省冲突的做法）。
- 通知 ID 硬编码 1998 → 并发执行覆盖（§7.1）。
- `NotificationTriggerHandler.kt:117` 依赖静态 `notificationListener`，为 null 时静默跳过——调试时容易误判为「触发器不工作」。
- 渠道重要性在代码里写死，**渠道一旦以某重要性创建过，改代码不会生效**（Android 渠道持久化特性）——改造时若调整重要性，需要新 channel id 或引导用户手动改。
- `ExecutionUIService` 第 3 层回退用了 `CATEGORY_CALL`，在部分国产 ROM 上会触发「类通话」的特殊展示/拦截行为，与超级岛可能互相干扰。

### 7.4 待调研（外部，本文档未覆盖）

- 超级岛/焦点通知的**具体接入方式**：是走小米推送（MiPush）的焦点通知能力，还是本地通知 + 厂商约定 extras？是否要求应用在小米白名单/申请权限？不同澎湃 OS 版本（V816 / 后续）差异如何？
- AOSP Live Updates（API 36）与小米超级岛的**关系与优先级**：本项目 targetSdk 36，走 AOSP 路径在澎湃 OS 上是否已被厂商接管渲染为岛形态？
- 真机验证基线：参考 `docs/fork/chat-float-window-design.md:618` 的记录，现有验证机为 Xiaomi MIX Fold 3 / Android 17 / **API 37** / HyperOS V816。

> 以上三项建议单独出一份外部调研文档（`docs/fork/surveys/` 或 `docs/fork/`），补进本目录索引。

---

## 8. 一页速查：改动前的自检清单

- [ ] 我要改的是 **A（发出）** 还是 **B（收取）** 链路？
- [ ] 改的是 8 处生产者中的哪一处？要不要顺手收拢成统一入口？
- [ ] 是否触碰 `WorkflowExecutor.kt`？（是 → 高冲突风险，优先考虑只换 `ExecutionNotificationManager` 内部实现）
- [ ] 新增渠道还是复用已有渠道？（渠道重要性不可变）
- [ ] 通知 ID 固定值会不会在多任务并发时冲突？
- [ ] 是否需要在 `WorkflowActionReceiver` 加 action？（新增分支，不改签名）
- [ ] 是否要在 `ModuleRegistry.kt` 追加注册？（追加，勿重排）
- [ ] 用户开关是否需要一个对应的设置项？（参考 §4.3）
- [ ] 改动是否产生了新的上游分歧？→ 同步登记 `FORK.md`

---

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-15 | 首版。8 处生产者 + 2 处消费者链路全量走查；渠道/权限/开关/AI 暴露面清点；超级岛改造落点与风险初判 |
