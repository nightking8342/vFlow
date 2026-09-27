# Xposed 通道 P4 设计 —— 状态位、授权引导与首页状态卡

> **性质**：fork 独有文档 → 冲突归**我方**（上游无此文件）。
> **日期**：2026-09-27。
> **上位文档**：`xposed-channel-design.md`（架构与决策台账，本文只做 P4 的落地设计）。
> **依赖的实测**：同目录 §6 前的「官方框架信息 API 实测通过」块 —— **本文的判据全部基于它**。
>
> **P4 要解决的三件事**（前两件是**功能缺口**，第三件才是 UI）：
> 1. **授权引导坏了** —— 点「授予」什么都不发生（§2）
> 2. **状态判据要改用官方 API**（§3）
> 3. **首页状态展示**（§4）

---

## 0. 结论速览

| # | 项 | 结论 |
|---|---|---|
| 1 | **授权引导** | `XPOSED_HOOK.createRequestIntent()` 返回 `null` ⇒ 两个 UI 入口**静默走错分支**。改为「弹引导对话框」 |
| 2 | **状态判据** | 用官方 `XposedServiceHelper` + `getScope()` + `getRunningTargets()` 替代 `XposedCapability` 自造方案 |
| 3 | **首页布局** | Xposed 状态卡**取代** Core 卡右侧两小卡的位置；两小卡**下移并排**（用户已定） |
| 4 | **「曾经连上过」语义** | **保留**，但判据换成「`onServiceBind` 至少触发过一次」（防异步窗口期误禁工作流） |
| 5 | **丢弃计数** | **不在状态卡内**，改为条件显示的 `HomeInfoCard`（> 0 才出现） |
| 6 | **`MISSING` 状态** | 由 `runningTargets` 能否列出 `system` 判定，**不需要新增上行协议** |

---

## 1. 现状与缺口

### 1.1 三个已实现但没接上的接口

| 接口 | 所在 | 主进程可达 | 归属 |
|---|---|---|---|
| `isLiveConnected()` | `XposedCapability` | ✅ | 与 `HookChannelController.isConnected()` 等价 |
| `lastReportedDroppedCount()` | `HookChannelController` | ✅ | 丢弃计数 |
| `sourceCount()` | **`HookRuntime`** | ❌ **不可达** | 它活在 **system_server** 进程里（`VFlowHookEntry` 是唯一实例化点） |

> ⚠️ `sourceCount()` **跨进程读不到**，且已决定**不补**上行协议。
> ⇒ 它保持「只能打日志」的现状，**不进 UI**。

### 1.2 缺口：授权入口静默失效

`PermissionManager.xposedHookStrategy.createRequestIntent()` 返回 `null`，而**两个 UI 入口都把 null 当成「那就走别的路」**：

| 入口 | 位置 | `intent == null` 时的行为 | 实际结果 |
|---|---|---|---|
| 权限管理页 | `PermissionActivity.kt:181-195` | 转 `autoGrantPermission` | 它需 Shizuku/Root；**失败**。且有 Shizuku 时会去「自动授予」一个**根本无法用 shell 授予的**权限 |
| 引导页 | `OnboardingActivity.kt:889-894` | **当成运行时权限**，用 `permission.id` 去 `requestPermissions` | `vflow.permission.XPOSED_HOOK` **不是系统权限** ⇒ 对话框弹不出来，系统直接拒绝 |

⇒ **用户点「授予」时什么都不发生、也没有任何提示**，而他真正该做的是去 LSPosed 里勾选。

---

## 2. 修复：授权引导（功能缺口）

### 2.1 设计原则

**不能只靠 manifest 或权限表的静态声明解决** —— 因为「授予」这个动作**发生在 vFlow 之外**（LSPosed 管理器里）。
所以需要一条**明确的引导路径**。

### 2.2 方案：给 `Permission` 加「外部授权」语义

不改 `createRequestIntent` 的返回值（它返回 `null` 是对的 —— 确实没有可跳转的系统页面），
而是**让 UI 能区分「null = 运行时权限」与「null = 外部平台权限」**。

在 `Permission` 上加一个字段：

```kotlin
// permissions/Permission.kt
/**
 * 该权限的授予动作是否发生在 vFlow 之外（如 LSPosed / 第三方框架）。
 *
 * ⚠️ 为什么需要它：`createRequestIntent()` 返回 null 时，两个 UI 入口都会
 * **静默走错分支**（当成运行时权限去 requestPermissions，或转去 autoGrant）。
 * 本字段让 UI 能识别这种情况并给出**引导**而不是静默失败。
 */
val grantedExternally: Boolean = false
```

`XPOSED_HOOK` 置 `true`。UI 侧：

```
if (permission.grantedExternally && !isGranted) {
    弹「引导对话框」   ← 见 §2.3
} else if (intent != null) {
    launcher.launch(intent)
} else {
    requestPermissions(...)   // 真正的运行时权限
}
```

### 2.3 引导对话框内容

**必须包含三条**（缺一条用户就会卡住）：

1. **装 LSPosed**（若没装）—— 我们探测不到「装没装」，只能列出来
2. ⭐ **在 LSPosed 里勾选 vFlow，并在作用域里勾「系统框架」** ——
   ⚠️ **这是最容易搞错的一步**：用户十有八九以为「勾选 vFlow = hook vFlow 自己」，
   而实际上 `scope.list=system`，**模块只作用于 system_server**（`xposed-channel-design.md` §0.1 / 决策 12）
3. **重启设备一次** —— 首次必须重启（热更新只换代码、不加载 scope）

再加一个**实时状态行**（数据来自 §3 的官方 API）：

```
当前状态：已启用 / 未启用
作用域：system          ← 直读，让用户能自我核对
```

### 2.4 落地位置

| 文件 | 改动 |
|---|---|
| `permissions/Permission.kt` | 加 `grantedExternally` 字段（**带默认值**，向后兼容） |
| `permissions/PermissionManager.kt` | `XPOSED_HOOK` 置 `grantedExternally = true` |
| `permissions/PermissionActivity.kt` | 分支改造（§2.2） |
| `ui/onboarding/OnboardingActivity.kt` | 同上 |
| 新增 `ui/settings/XposedGuideDialog.kt` | 引导对话框 |
| 三语 `strings.xml` | 引导文案 |

---

## 3. 状态判据：改用官方 API

### 3.1 三层信息（**这是 P4 判据的核心**）

来自实测（`xposed-channel-design.md` §6 前的探针块）：

| 层 | 来源 | 回答什么问题 | 可靠性 |
|---|---|---|---|
| **L1 框架层** | `XposedServiceHelper.onServiceBind` / `onServiceDied` | **框架在不在** | ⭐ **官方权威**（`onServiceDied` 是权威的负信号） |
| **L2 作用域层** | `XposedService.getScope()` | **勾了对的作用域没** | ⭐ 直读，不用猜 |
| **L3 挂载层** | `XposedService.getRunningTargets()` | **模块真的注入到 system 了吗** | ⭐ 权威（含 `state=UP_TO_DATE`） |

外加一条**我们自己的一层**：

| 层 | 来源 | 回答什么 | 说明 |
|---|---|---|---|
| **L0 通道层** | `HookChannelController.isConnected()` | App 与自己的 `HookChannelService` 连上了没 | ⚠️ **这里**才是「事件能不能流过来」。**它和 L1/L2/L3 是独立的** |

> ⚠️⚠️ **L1/L2/L3 都对，L0 也可能是断的** —— 例如 App 被 force-stop 后重建、
> 或 hook 层刚换代还没重连。**事件链路走的是 L0**，所以状态展示**必须同时看这两组**。
> 这是本设计最容易做错的地方：把「框架正常」当成「功能正常」。

### 3.2 状态机（**两组独立状态位**，不是三态枚举）

`xposed-channel-design.md` §3.3 明确要求**不能压成一个枚举**。落地为：

```kotlin
// core/xposed/XposedState.kt（新增）

/** 状态位 A：框架活性 —— 「Xposed 环境可用吗」 */
enum class FrameworkState {
    /** 从未连上过 —— 没装 LSPosed / 没勾选 / 没重启 */
    UNAVAILABLE,
    /** 连过但现在断开 —— 框架被停 / 被卸载 */
    DEGRADED,
    /** 当前连着 */
    ACTIVE,
}

/** 状态位 B：通道与挂载 —— 「事件真的能流过来吗」 */
enum class ChannelState {
    /** hook 层没连上（L0 断）—— 事件不会产生 */
    DISCONNECTED,
    /** 连上了，但 `runningTargets` 里没有 system（L3 缺）—— 注入了但没挂上 */
    NOT_MOUNTED,
    /** 连上且 system 在 runningTargets 里 —— 正常 */
    READY,
}
```

**两者的组合决定 UI 文案**（§4.3）——单看任一个都会给出误导性结论：

| A | B | 真实情况 | 该说什么 |
|---|---|---|---|
| UNAVAILABLE | DISCONNECTED | 从没用过 | 引导安装（§2.3） |
| ACTIVE | DISCONNECTED | 框架好好的，但我们的信道断了 | 「正在重连」——**最容易被误判成框架问题** |
| ACTIVE | NOT_MOUNTED | 连上了但 hook 没挂上 | 「作用域没勾对」或「需要重启」 |
| ACTIVE | READY | 正常 | 不显示 / 极简 |

### 3.3 「曾经连上过」语义的保留

`XposedCapability` 现在用 SharedPreferences 记「曾经连上过」，用途是**防止权限判定的异步窗口期误禁工作流**（其类注释详述）。

**P4 保留这个语义，但换判据**：

| | 原 | 新 |
|---|---|---|
| 判据 | 我们自己 bind 成功过 | **`onServiceBind` 至少触发过一次** |
| 理由 | 我们那个 bind 反映的是 L0，而 L0 可能比 L1 晚很久 | L1 是官方权威信号，更早也更可靠 |

⇒ `XposedCapability` 保留，内部改为读写**新的**标记（由 `FrameworkState` 的观测结果驱动）。

### 3.4 新增/改动的文件

> **⚠️ 探针已经把「能不能拿到」验掉了**（`XposedFrameworkProbe.kt` 实测通过，见上位文档）。
> 所以本节的增量**只剩三件**：**保存** / **推导** / **推送** —— 探针是「读完就打日志、读完就丢」。
> 实施时**把探针改写成 Monitor**（方案 A），而不是新建 + 删除两个都注册
> —— 因为 `registerListener` **无注销方法**，并存会收到两份回调。

| 文件 | 类型 | 说明 | 探针已探明的部分 |
|---|---|---|---|
| `core/xposed/XposedState.kt` | 新增 | 两个状态位枚举 + 组合判定（**纯函数，可单测**） | — （全新） |
| `core/xposed/XposedFrameworkMonitor.kt` | 新增 | 持有 `XposedService`，暴露 `StateFlow` | ⭐ **回调骨架 / `describeTarget` / `readMember` 可直接从探针搬** |
| `core/xposed/XposedCapability.kt` | 改 | 判据换成「`onServiceBind` 触发过」 | — |
| `core/xposed/XposedFrameworkProbe.kt` | **改写** | 升级为 Monitor（**不新建第二个文件**） | 全部 |
| `ui/settings/SettingsScreen.kt` + `SettingsRoute.kt` | 改 | 移除探针按钮 | — |
| `core/xposed/HookChannelController.kt` | 改 | **加 `StateFlow`**（L0 现在是同步 `isConnected()`，UI 无法 collect） | — |

> ⚠️ **P4d（删探针）因此不再单列** —— 它并入 P4a 的「改写」动作。

> ⚠️ **`XposedFrameworkMonitor` 必须处理「`registerListener` 无对应的注销方法」** ——
> 这是 API 的限制（探针代码里已记录）。⇒ 用**进程级单例**，只注册一次。

### 3.5 单测要锁什么

`XposedState.kt` 做成**纯函数**（输入：框架状态 + 通道状态 + scope + runningTargets；输出：两个枚举 + UI 文案 key），这样能纯 JVM 测：

- 组合判定表（§3.2 那张表的每一格）
- **`ACTIVE` + `DISCONNECTED` 不能被判成「框架问题」**（最易误判的一格）
- `scope` 里**没有** `system` 但 `runningTargets` 里有 ⇒ 自相矛盾时以 `runningTargets` 为准（它是实际结果，scope 是配置）

---

## 4. 首页状态展示

### 4.1 布局（用户已定）

```
【现状】
┌──────────────┬──────────────┐
│              │  总工作流     │
│  Core 状态   ├──────────────┤
│  （左，大）   │  自动任务     │
└──────────────┴──────────────┘

【P4 之后】
┌──────────────┬──────────────┐
│  Core 状态   │  Xposed 状态 │
│  （大卡）     │  （大卡）     │
├──────────────┴──────────────┤
│   总工作流   │   自动任务     │   ← 两小卡下移、并排
└─────────────┴───────────────┘
```

**卡片形态与 `CoreStatusCard` 同构**（复用视觉语言）：`RoundedCornerShape(20.dp)`、
右下角大图标（`alpha 0.2f`）、`titleLarge` 标题 + `headlineMedium` 状态词 + 副文案。

**⭐ 固定展示，不做条件显隐。**

卡片是**固定网格的一部分**（占据 Core 卡右侧那个大卡位），所以**四种状态都占位**，
只变内容与配色 —— 与 `CoreStatusCard` 的行为完全一致（Core 停了也照样显示那张红的错误卡）。

**为什么不能条件显隐**：

1. **会破坏布局** —— 大卡位一旦按条件消失，旁边的 Core 卡会**突然变宽**，界面跳动
2. **「存在本身即信息」** —— 用户看到「未启用」才知道**有这个东西**、才可能去开它。
   条件隐藏会让「没装 LSPosed 的用户」永远不知道有这个能力
3. 与同网格的 `CoreStatusCard` 行为统一（它也是常驻）

> ⚠️ **一处我自己的纠错记录**：布局定案前，我曾建议「正常时不显示」——
> 那是基于「放一条 `HomeInfoCard` 横条」的**旧方案**（横条出现/消失不影响布局）。
> 布局改为「占据大卡位」后**该说法作废**。
> `HomeInfoCard` 那个模式只留给 §4.4 的**丢弃提示**（它确实是条件显示的）。

### 4.2 配色（**两色，不引入第三种容器色**）

| 状态 | 容器色 | 图标 |
|---|---|---|
| `READY` | `primaryContainer` | `rounded_check_circle_24` |
| 其它一切 | `errorContainer` | 按状态选（见下） |

⚠️ 有**三种失败状态**但只有**两种色** —— 这是刻意的：引入第三种容器色会打乱首页既有的视觉秩序，
区分靠**图标 + 文案**，不靠颜色。

| A / B | 图标 | 大词 | 副文案 |
|---|---|---|---|
| UNAVAILABLE | `rounded_extension_off_24`（需新增） | 「未启用」 | 需在 LSPosed 中启用并勾选「系统框架」 |
| ACTIVE / DISCONNECTED | `rounded_sync_problem_24`（需新增） | 「重连中」 | 通道已断开，Activity 触发器暂不触发 |
| ACTIVE / NOT_MOUNTED | `rounded_rule_24`（需新增） | 「未挂载」 | 框架正常，但 hook 未生效——检查作用域或重启 |
| READY | `rounded_check_circle_24`（已有） | 「工作中」 | — |

> ⚠️ 三个新 drawable 要用**现有的 `rounded_*` 命名与风格**（照 `rounded_check_circle_24` / `rounded_cancel_24` 的形态）。

### 4.3 点击行为

| 状态 | 点击去哪 |
|---|---|
| UNAVAILABLE | §2.3 的引导对话框 |
| ACTIVE / DISCONNECTED | **不跳转**，改为「重连」动作（触发一次重连 + toast） |
| ACTIVE / NOT_MOUNTED | 引导对话框（重点讲作用域） |
| READY | 无点击（或进设置页） |

> ⚠️ **不能一律跳设置页** —— `DISCONNECTED` 的用户最需要的是「重连」，而不是看一堆配置。

### 4.4 丢弃计数：**不进状态卡**

`lastReportedDroppedCount() > 0` 时，在卡片区**下方**加一条 `HomeInfoCard`
（与既有的 `coreNeedsUpdate` 那条**同一个模式**）：

```
⚠️ 事件过载，已丢弃 N 条            [了解]
事件产生过快，考虑收窄触发条件或加长冷却
```

**理由**：状态卡回答的是「通道活着吗」，丢弃数回答的是「采得太多」——**性质不同**。
且它是**条件出现**的（> 0 才显示），常驻一个「丢弃 0 条」没有意义。

### 4.4.1 文案与显示的两个取舍（2026-09-27 用户反馈后确定）

**① 状态卡标题不写「通道」** —— `Xposed 通道` → **`Xposed`**。

**② 小字显示框架版本，不显示作用域** —— `作用域：system` → **`LSPosed 2.2.0`**。

理由：用户更常想确认的是「我装的是哪个版」；而**作用域对不对**在**出问题时**才需要核对，
那时看引导对话框里的实时显示就够了（那里是核对配置的合适位置）。

⚠️ 格式化时**任一字段为空则整体为空** —— 不拼出 `LSPosed ` 或 ` 2.2.0` 这种半截串
（半截串会在卡片上显示一行无意义内容）。空同时也用于决定「显示版本」还是「显示操作提示」。

### 4.5 刷新机制

现有 `refreshCoreStatus()` + `startCoreStatusAutoRefresh()`（连不上时 500ms × 16 次重试）是**轮询**。

**P4 改为事件驱动**（对 Xposed 部分）：`XposedFrameworkMonitor` 暴露 `StateFlow`，
首页 `collect` 即可；`onServiceBind` / `onServiceDied` 是**推送**的，不必轮询。

⚠️ 但 **L0（`HookChannelController.isConnected()`）没有推送** ——
它只在连接变化时才有值。⇒ 需要给它加一个 `StateFlow`（现在只有同步的 `isConnected()`）。

### 4.6 涉及文件

| 文件 | 类型 |
|---|---|
| `ui/home/HomeScreen.kt` | 改（`HomeUiState` 加字段、布局改动、新卡片、info card） |
| `res/drawable/rounded_{extension_off,sync_problem,rule}_24.xml` | 新增 3 个 |
| 三语 `strings.xml` | 新增约 12 条 |

---

## 5. 实施顺序

| 步 | 内容 | 为什么这个顺序 |
|---|---|---|
| **P4a** | §3 状态判据（`XposedState` + `Monitor`） | **先有判据才能展示**；且它是纯函数 + StateFlow，可先单测 |
| **P4b** | §2 授权引导 | 依赖 P4a 的实时状态（对话框要显示「当前状态/作用域」） |
| **P4c** | §4 首页展示 | 依赖 P4a（状态源）与 P4b（引导对话框作为点击目标） |
| ~~P4d~~ | ~~移除探针~~ | ✅ **并入 P4a** —— 探针被**改写成** Monitor，不新建文件（`registerListener` 无注销，并存会双回调）。设置页按钮在 P4c 完成后移除 |

**每步都可独立验证**：
- P4a：单测（组合判定表）+ 真机日志（Monitor 的 StateFlow 变化）
- P4b：真机点「授予」→ 应弹引导而不是静默
- P4c：真机看四种状态的卡片文案（可通过临时改变 LSPosed 勾选来制造状态）
- P4d：确认移除后功能不回退

---

## 6. 明确不做（本次）

| 项 | 原因 |
|---|---|
| **`MISSING`（hook 点在系统上不存在）** | 已由 `runningTargets` 覆盖（它反映实际注入结果）。**不需要**再补一条上行协议——用户已决定「不补」，而官方 API 让它变得不必要 |
| **主动热更新（`hotReloadModule()`）** | 有实测基础（§6 前探针块），但它是**独立功能**，不与状态展示耦合。单列后续 |
| **编辑器内「置灰」Xposed 触发器** | `xposed-channel-design.md` §3.3 提到过，但它要动编辑器逻辑且与首页展示重复。**先做首页，看实际反馈** |
| **`sourceCount()`** | 跨进程读不到，且不补协议（§1.1） |
| **从 vFlow 内「打开 LSPosed 管理器」** | 官方**没有**这个 API（已核实 aar 全部方法）。只能用包名硬编码拉起，而 LSPosed 包名**历版本变动**、管理器可能无 LAUNCHER intent-filter、Android 11+ 还要加 `<queries>` 声明。**且跳过去也只是把人扔在管理器首页** —— 他还得自己找模块列表与开关。**一句准确的文字指路更有用**（用户已确认） |
| **从 vFlow 内「启用模块开关」** | **平台不提供**，且**符合设计直觉**：那个开关是「用户是否信任这个模块」的授权动作，**框架不该让模块自己打开自己**（自授权）。`requestScope` 只作用于**已启用**模块的作用域 |

---

## 7. 风险

| 风险 | 缓解 |
|---|---|
| ⚠️ **把「框架正常」当成「功能正常」** | §3.1 的 L0/L1 分离是硬要求；单测专门锁 `ACTIVE + DISCONNECTED` 这一格 |
| `registerListener` 无法注销 ⇒ 多次注册 | 进程级单例，只注册一次（探针代码已有先例） |
| 首页轮询与推送混用导致状态抖动 | Xposed 部分走 `StateFlow`（推送），Core 部分**保持原轮询不动**（不在本次范围） |
| 新增 3 个 drawable 与现有风格不一致 | 照 `rounded_check_circle_24` / `rounded_cancel_24` 的形态做 |
| 引导文案写得太短 ⇒ 用户仍不知作用域怎么勾 | §2.3 的三条必须齐全；对话框里加**实时 scope 显示**让用户能自我核对 |

---

## 8. 验收

- [ ] 点「授予 Xposed 通道」弹出引导（**不再静默**），含三条步骤 + 实时状态
- [ ] 首页出现 Xposed 状态卡，两小卡下移并排
- [ ] 四种状态（未启用 / 重连中 / 未挂载 / 工作中）文案正确，**可通过改 LSPosed 勾选来制造**
- [ ] `ACTIVE` 但通道断开时**不显示成「框架问题」**
- [ ] 有丢弃时出现 info card，无丢弃时不出现
- [ ] 探针移除后功能不回退
- [ ] `./gradlew test` 无新增失败
