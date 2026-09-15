# 免打扰模式触发器（Do Not Disturb Trigger）

> **目录归属**：fork 独有功能文档。
> **分支/日期**：`session-0915-01`，2026-09-15。
> **对照代码**：`DoNotDisturbTriggerModule.kt`（264 行）、`DoNotDisturbTriggerHandler.kt`（144 行）。
> **与相邻文档的区别**：本文件记录「这个功能要做成什么样 + 实现状态」，属需求/交接文档；
> 若要了解**整个触发器体系的现状**，见 `docs/fork/surveys/trigger-system-overview.md`（待补）。

---

## 1. 需求

让工作流能在**系统免打扰（Do Not Disturb）开启或关闭时**自动触发。

典型场景：

- 开启勿扰 → 自动静音媒体、调暗屏幕、开启专注类工作流；
- 关闭勿扰 → 恢复音量、同步未读消息、输出「勿扰期间错过的通知」汇总。

## 2. 设计决策

### 2.1 权限：只需勿扰访问，不需通知使用权

这是本设计最值得记录的一点。直觉上容易认为「读勿扰状态需要通知使用权」，**实际不需要**：

| 路线 | 所需权限 |
|---|---|
| `NotificationManager` + `ACTION_INTERRUPTION_FILTER_CHANGED` 广播 | `ACCESS_NOTIFICATION_POLICY`（勿扰访问） |
| `NotificationListenerService` | `BIND_NOTIFICATION_LISTENER_SERVICE`（通知使用权） |

**采纳广播路线**，因为从 Android 10（Q）起该广播只投递给「已获得勿扰访问权限」的包，
授权一次同时解锁**读**（本触发器）与**写**（既有的 `DoNotDisturbModule`），
用户不必再开一个与之无关的通知使用权。

代码落点：`DoNotDisturbTriggerModule.kt:74`
`requiredPermissions = listOf(PermissionManager.NOTIFICATION_POLICY)`

### 2.2 API 选型（源码核实，非记忆）

选型前直接读了 AOSP `main` 分支源码，结论与流传的说法有出入：

| 候选 | 核实结果 | 采纳 |
|---|---|---|
| `NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED` | **未废弃**。注释明确：*"This broadcast is only sent to registered receivers and (starting from Q) receivers in packages that have been granted Do Not Disturb access"* | ✅ 主方案 |
| `NotificationListenerService.onInterruptionFilterChanged()` | **未废弃**，`@UiThread public void`，非 final 可覆写 | 备选（未使用） |
| `INTERRUPTION_FILTER_*` 常量 | **未废弃** | ✅ |
| `ZenDeviceEffects` | 无证据表明 API 36 已替换 | 未采纳 |

> ⚠️ 容易混淆：被 `@Deprecated` 的是 `SUPPRESSED_EFFECT_SCREEN_ON/OFF`，**不是** interruption filter 那组常量。

### 2.3 状态映射

从 AOSP `NotificationManager.java` 的 `ZEN_MODE_*` ↔ `INTERRUPTION_FILTER_*` 对照表确认：

```
ZEN_MODE_OFF                     -> INTERRUPTION_FILTER_ALL      (1) 关
ZEN_MODE_IMPORTANT_INTERRUPTIONS -> INTERRUPTION_FILTER_PRIORITY (2) 开
ZEN_MODE_NO_INTERRUPTIONS        -> INTERRUPTION_FILTER_NONE     (3) 开
ZEN_MODE_ALARMS                  -> INTERRUPTION_FILTER_ALARMS   (4) 开
（监听器未连接时）                -> INTERRUPTION_FILTER_UNKNOWN  (0) 不可用
```

即 **「非 ALL 且非 UNKNOWN」⇒ 开启**。

这条映射决定触发器在每个方向上的行为，且极易被「顺手改写」破坏，因此抽成顶层纯函数
`isDndFilterEnabled(filter: Int): Boolean?`（`DoNotDisturbTriggerModule.kt:168`）并逐值单测。
`UNKNOWN` 返回 `null` 而非 `false`——表示「不可用」，让调用方回退，而不是武断当作关闭。

**由此派生的一条隐式性质**：`PRIORITY` 与 `NONE` 之间切换时，filter 变了但「是否开启」没变。
Handler 的去抖逻辑依赖这一点，否则会产生伪触发。

### 2.4 参数与输出

| 参数 id | 名称 | 类型 | 选项（默认首个） |
|---|---|---|---|
| `target_state` | 触发条件 | ENUM / CHIP_GROUP | `on` 开启时 / `off` 关闭时 / `any` 任意变化 |

`legacyValueMap` 收录 `开启时/打开时/开启`→`on`、`关闭时/关闭`→`off`、`任意/任意变化`→`any`。
参数设为 `acceptsMagicVariable = false` / `acceptsNamedVariable = false`——触发器参数是配置而非运行时求值。

输出（供下游 `{{<triggerStepId>.enabled}}` 引用）：

| id | 名称 | 类型 |
|---|---|---|
| `enabled` | 免打扰已开启 | BOOLEAN |
| `previous_enabled` | 变化前是否开启 | BOOLEAN |

> 注：`previous_enabled` 在「基线未知」（`previous == null`）时用 `!current` 兜底——
> 此时唯一可知的事实是「与当前相反」。

**不设 `aiMetadata`**——与现有全部触发器模块一致（触发器一律不走 Agent 直调）。

### 2.5 状态基线：本 Handler 与同类触发器的关键差异

`PowerTriggerHandler` 这类「广播型」触发器只需读 `intent.action` 就知道事件方向。
免打扰**不同**：广播只告知「过滤器变了」，**不告知方向**。因此必须自行缓存上一次状态。

```
注册监听时：lastKnownEnabled = isDndEnabled(context)   ← 建立基线
收到广播时：current = isDndEnabled(context)
            if (previous == current) return            ← 去抖
            lastKnownEnabled = current
            按 target_state 与 current 决定是否触发
```

`lastKnownEnabled` 是**实例变量**：Handler 是 `TriggerService` 的持久成员，跨 add/remove 存活，
否则每次 `addTrigger` 都会丢基线、首次变化判错方向。在 `stopListening` 里清空，避免下次 start 拿到陈旧状态。

## 3. 实现状态

**已完成**（构建与测试均通过）：

| 文件 | 行数 | 状态 |
|---|---|---|
| `core/workflow/module/triggers/DoNotDisturbTriggerModule.kt` | 264 | 新增 ✅ |
| `core/workflow/module/triggers/handlers/DoNotDisturbTriggerHandler.kt` | 144 | 新增 ✅ |
| `res/drawable/rounded_do_not_disturb_on_24.xml` | — | 新增 ✅ |
| `test/.../DoNotDisturbTriggerMathTest.kt` | 65 | 新增 ✅（5 用例） |
| `test/.../DoNotDisturbTriggerModuleTest.kt` | 74 | 新增 ✅（6 用例） |
| `module/ModuleRegistry.kt` | +1 行 | 追加注册 ✅ |
| `triggers/handlers/TriggerHandlerRegistry.kt` | +1 行 | 追加注册 ✅ |
| `res/values{,-en,-ja}/strings_module.xml` | +9×3 行 | 追加文案 ✅ |

触发器总数 24（其中 21 个有 Handler，Manual/ReceiveShare 为用户主动触发，Voice 走独立 Service）。

**自动化验证**：

- `./gradlew testDebugUnitTest --tests "...DoNotDisturb*"` → 11 用例全绿
- `./gradlew assembleDebug` → BUILD SUCCESSFUL
- `./gradlew test` → 467 用例，1 失败（`VObjectPropertyTest > test VFile properties from absolute path`，
  `android.net.Uri.parse not mocked`）。**已确认是既有失败**：把本次改动 `git stash` 后在干净基线上同样复现，
  与上游 VFile 提交 `6c672aa7` 相关，与本次改动无关。

**真机验证**：已在 Xiaomi 2308CPXD0C（Android 17 / API 37）上触发成功，取值策略验证通过。
详见 §4。

> ⚠️ **交付注意**：worktree 中缺少被 `.gitignore` 排除的签名文件（`vFlow.jks`、`signing.properties`）。
> 若未从主仓库补齐，`hasReleaseSigning = false`，release 产物将**未签名或签名不符**，无法覆盖安装。
> 已补齐后构建的 `app-arm64-v8a-release.apk` 签名为 `CN=vFlow Fork, O=nightking8342`。

## 4. 真机验证结论（已收口）

**验证环境**：Xiaomi 2308CPXD0C，**Android 17（API 37）**，HyperOS，vFlow 1.5.4，Core 以 root 运行。

### 4.1 取值策略验证通过 ✅

原先的**核心不确定项**是：Android 16+ 引入 Modes 后勿扰被泛化为多个具名 mode，
第 2 层回退（`getAutomaticZenRuleState(vFlow 自建 ruleId)`）可能只反映 vFlow 规则本身、
读不到用户在系统设置里开启的勿扰。`probeDndState` 实测数据：

| 时间 | `filter` | `vflowRuleState` | `resolved` | 实际状态 |
|---|---|---|---|---|
| 10:14:49 | `PRIORITY(2)` | `FALSE` | `true` | 勿扰开 ✓ |
| 10:15:16 | `ALL(1)` | `FALSE` | `false` | 勿扰关 ✓ |
| 10:15:19 | `PRIORITY(2)` | `FALSE` | `true` | 勿扰开 ✓ |
| 10:15:10 | `PRIORITY(2)` | `TRUE` | `true` | vFlow 规则激活后 ✓ |

**结论：风险未发生。** 第 1 层 `currentInterruptionFilter` 在 API 37 上始终有效，
第 2 层回退**从未被触发**。分层策略保留（作为防御），但主路径已足以覆盖系统设置开启的勿扰。

### 4.2 一个重要的观测：`vflowRuleState` 与全局状态不一致

上表第 1、3 行显示：**系统勿扰开着，但 `vflowRuleState=FALSE`**。这不是 bug，而是平台语义的必然结果：

- 从**系统设置**开启勿扰 → 走系统自己的 mode/rule，与 vFlow 规则无关；
- `DoNotDisturbModule` 在 API 35+ **只能操作 vFlow 自建的自动规则**（见 §6.1）。

实测还确认了去抖有效（日志出现 `免打扰状态未发生实质变化（true），忽略。`）。

### 4.3 验证手段：`probeDndState` 诊断探针

刻意**不新增 Activity**（那要改上游 `AndroidManifest.xml`），改为把原始读数写进 vFlow 日志
（`DebugLogger` → 应用内日志页）。生产开销可忽略（仅在勿扰变化时调用一次）。

`probeDndState`（`DoNotDisturbTriggerModule.kt:220`）输出格式：

```
DndProbe: sdk=37 accessGranted=true filter=PRIORITY(2) vflowRule=<id> vflowRuleState=FALSE resolved=true
```

**该探针建议保留**——排查 OEM 差异（MIUI/OnePlus 等）时是最快的一手证据。

### 4.4 尚未覆盖的场景

| # | 场景 | 状态 |
|---|---|---|
| 1 | 未授权 → 工作流被拦下并停用 | ⬜ 未测 |
| 2 | 授权后手动开关勿扰，方向正确 | ✅ 已测（开/关两向） |
| 3 | `any` 选项两个方向都触发 | ⬜ 未测 |
| 4 | 快速连点不重复触发 | ✅ 已测（去抖日志） |
| 5 | 灭屏 / 应用在后台仍能收到广播 | ⬜ 未测 |
| 6 | 仅「开启时」问，关闭时不触发 | ✅ 已测 |

## 5. 已澄清：自触发环在本设备上不会发生

原判定「`ALLOW_PARALLEL` 下会持续自触发」经真机验证**不成立**，原因见 §6.1：
`DoNotDisturbModule` 改不动全局 filter，因此它的写入**不会产生本触发器能观察到的状态变化**。

```
工作流内执行「免打扰模式」→ 仅改 vflowRuleState，filter 不变
                          → 不发出有效的状态变化 → 本触发器不响应 → 无环
```

**但这个「安全」是上游模块局限的副产品，不是本触发器做的防护。**
若上游将来把 `DoNotDisturbModule` 改为操作全局状态（或新增 root 路径，见 §6.2），
环就会真实存在。届时应补防护，候选方案（按推荐度）：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 不处理 | 依赖默认 `BLOCK_NEW` | 现状。多数场景安全，但 `ALLOW_PARALLEL` 下埋雷 |
| **B. 触发器侧忽略自己触发的变更** | Handler 维护短窗口标记，`DoNotDisturbModule` 写入后打标，窗口内（如 500ms）的变化不触发 | 改动小、语义正确 |
| C. 加 `ignore_self_trigger` 选项 | 暴露给用户 | 把复杂度推给用户 |

## 6. 上游模块的能力边界（实测澄清）

### 6.1 Android 15+ 起第三方应用**无法**修改全局勿扰

AOSP `NotificationManager.setInterruptionFilter` 注释原文：

> Apps targeting `VANILLA_ICE_CREAM`（API 35）and above (with some exceptions, such as companion
> device managers) **cannot modify the global interruption filter**. Calling this method will instead
> **activate or deactivate an `AutomaticZenRule` associated to the app**…

vFlow `targetSdk = 36`，正落在受禁范围。因此：

| 你的操作 | 系统勿扰 | vFlow 规则 |
|---|---|---|
| 系统设置里开勿扰 | ✅ 开 | ❌ 不变 |
| 工作流执行「免打扰模式」模块 | ❌ **不变** | ✅ 开 |

**这不是本触发器的问题，也不是 `DoNotDisturbModule` 的 bug**，是平台语义变更所致。
表现上像「模块失效」，实际是「模块只动自己的规则」。

### 6.2 理论出路：走 Core 直接写 `Settings.Global.ZEN_MODE`（未采纳）

本设备 Core 以 **root** 运行（日志 `I/VFlowCoreBridge: ping: 成功, uid=0, mode=ROOT`），
理论上可绕开平台限制直接写全局设置
（`ZEN_MODE_OFF=0` / `IMPORTANT_INTERRUPTIONS=1` / `NO_INTERRUPTIONS=2` / `ALARMS=3`）。

**已评估但暂不实施**（用户 2026-09-15 决定：当前只需触发器）。若将来要做：

- 应**新增模块**（如 `vflow.system.do_not_disturb_force`），而非改上游 `DoNotDisturbModule`（FORK.md 敏感点）；
- 需先验证 HyperOS 是否拦截写 `ZEN_MODE`；
- 属绕过平台意图，可能有系统 UI 状态不同步等副作用；
- 一旦落地，§5 的自触发环会**真实生效**，必须同时补防护。

**当前未做防护**。候选方案（按推荐度）：

| 方案 | 做法 | 评价 |
|---|---|---|
| A. 不处理 | 依赖默认 `BLOCK_NEW` | 现状。多数场景安全，但 `ALLOW_PARALLEL` 下埋雷 |
| B. 触发器侧忽略自己触发的变更 | Handler 维护短窗口标记，`DoNotDisturbModule` 写入后打标，窗口内（如 500ms）的变化不触发 | 改动小、语义正确 |
| C. 加 `ignore_self_trigger` 选项 | 暴露给用户 | 把复杂度推给用户 |

若后续收到相关反馈，优先考虑 B。

## 7. 同步事项

- **`FORK.md`**：本功能改动了两处**上游文件**（`ModuleRegistry.kt`、`TriggerHandlerRegistry.kt`），
  均为「按分类追加一行、不重排既有注册」，冲突面小但**须登记**。
  其余为新增文件（归「我方」），文案追加在 `strings_module.xml` 末尾以减少冲突。
- **`docs/fork/surveys/README.md`**：触发器体系的**现状梳理**仍列为候选待办
  （「触发器体系：注册与触发链路、权限模型」）。本文件是功能文档，**不能替代**那份梳理；
  README 的「现有文档」表也无需因本文件而更新（该表只收录 `surveys/` 内的文档）。

## 8. 扩展落点

若要新增同类「广播型 + 需要状态基线」的触发器，可参照本实现的三点套路：

1. 顶层纯函数承载「原始读数 → 语义布尔」的映射，逐值单测（`isDndFilterEnabled`）；
2. Handler 内用实例变量存基线，`stopListening` 清空（`lastKnownEnabled`）；
3. 用 `probeDndState` 式的日志探针替代新增 Activity，规避改上游 `AndroidManifest.xml`。

新增触发器必须**同时**注册两处，否则会出现「能选能配、后台永不触发」的静默失效：

- `module/ModuleRegistry.kt` 的 `initialize()`（管 UI 可见性）
- `triggers/handlers/TriggerHandlerRegistry.kt` 的 `initialize()`（管后台实例化）
