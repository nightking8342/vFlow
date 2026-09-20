# 折叠屏触发器设计 —— 折叠 / 展开 / 半折状态触发

> **版本**：v3.0 · 2026-09-20（**已实现并通过真机验证；P0 结论见 §9**）
> **对应分支**：`dev`（基于 `versionName 1.5.4` / `versionCode 50`）
> **目录归属**：fork 独有文档 → 冲突归**我方**（上游无此文件）
> **目标设备**：**小米 MIX Fold 3**（`2308CPXD0C` / Android 17 / API 37 / 澎湃 OS4.0）
> **状态**：✅ **已实现**（`vflow.trigger.fold`，纯新增文件 + 2 处追加注册），25 个单测通过，**真机验证触发器可用**
>
> **⚠️ 实测推翻了一处调研结论**：B4X 论坛「铰链传感器只在折叠方向上报」在 MIX Fold 3 上**不成立**——实测双向完整上报。
> 原方案为应对单向上报而设计的复杂降级链路可大幅简化，但**也实测出调研完全没提到的两个新问题**（§9.2）。

**证据来源**：

- **官方文档（小米）**：大屏设备适配说明 —— `device_posture` 语义（[pId=1484](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1484)）
- **官方文档（小米）**：Android T 无 androidx 实现时的折叠监听完整代码 —— `display_features` 格式与坐标轴交换（[pId=2046](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2046)）
- **AOSP 源码**：`Sensor.java`（`TYPE_HINGE_ANGLE = 36` 确认为公开 API，API 30 加入）、`WindowLayoutComponentImpl.java`、`Context.java`
- **社区实测**：B4X 论坛 Galaxy Z Fold 4/5 四传感器枚举实测（折叠双向性的关键反例，§1.3）
- **同类产品**：MacroDroid「Fold State」触发器（选项 Open / Closed / Half Open / ±90，标注 Android 11+）
- vFlow 侧现状：直读本仓库代码，**行号均已核对**

---

## 0. 一句话方案

**不做「事件监听器」，做「状态推断器」。** 用一个纯函数的状态机 `FoldStateResolver` 融合多路信号（**以铰链角度为主力**、小米 `device_posture` 校验、DisplayListener 兜底）推断当前折叠状态，**只对状态变迁的边沿触发**，并把当前状态作为魔法变量输出给下游引用。厂商私有 key 完全收敛在 Handler 内部，业务侧不出现任何 `miui.*` 或 `device_posture` 字符串。

> **v2.0 实测修订**：主力信号由 `device_posture` **改为铰链角度** —— 实测 `device_posture` 滞后 1–2 秒且半折值几乎不出现（§9.2）。

---

## 1. 从调研中提炼的关键事实

以下均为**已核实**的外部事实（非本项目代码），是设计的约束条件。

### 1.1 小米提供两个不同粒度的 key，别混淆

这两者**分属两份官方文档**，是独立的 `Settings.Global` 项，用途不同：

| key | 来源文档 | 取值 | 用途 |
|---|---|---|---|
| `device_posture` | 大屏适配说明（pId=1484） | `0` UNKNOWN / `1` 折叠 / `2` 半折 / `3` 展开 | **直接给状态枚举** |
| `display_features` | 无 androidx 实现（pId=2046） | `fold-[l,t,r,b]-flat\|half-opened` | **给折痕几何** |

官方原文对 `device_posture` 的注释（逐字）：

```java
// 0 UNKNOWN // 1 折叠 // 2 半折（MIX Fold 2支持） // 3 展开
Settings.Global.getInt(getContentResolver(), "device_posture", 0)
```

**`device_posture` 是触发器的主力信号**：它就是我们要的状态枚举，一个 int 读完，无需几何计算。

`display_features` 的格式（官方正则，逐字）：

```
([a-z]+)-\[(\d+),(\d+),(\d+),(\d+)]-?(flat|half-opened)?
```

- 组 1 → `fold`（`FEATURE_TYPE_FOLD = 1`）或 `hinge`（`FEATURE_TYPE_HINGE = 2`），其它抛 `IllegalArgumentException`
- 组 2-5 → `Rect(left, top, right, bottom)`；**零尺寸矩形抛 "Feature has empty bounds"**
- 组 6 → `flat`（`STATE_FLAT = 1`）/ `half-opened`（`STATE_HALF_OPENED = 2`），缺省 `-1`

`display_features` **本设计的 P0 不需要**——它服务于「按折痕位置分屏布局」，不是状态判定。留作 P2 扩展（§2.2）。

### 1.2 ⚠️ `display_features` 的坐标只在 ROTATION=0 下有效

官方明确警告：**数据库里只写 rotation=0 的坐标**，横屏时必须自己做坐标轴交换。官方示例的判断逻辑是拿 `getMaximumWindowMetrics()` 的宽高与折痕宽高比对，不匹配则交换 `left↔top` / `right↔bottom`。

**这条对本设计的意义**：如果 P2 要接入 `display_features`，必须连同这段转置逻辑一起抄，不能只解析正则。也是「为什么不用它做 P0」的原因之一——多一处状态相关几何计算就多一处误判来源。

### 1.3 ⚠️ 铰链角度传感器可能只在「折叠」方向上报（**已被本机实测推翻，见 §9.1**）

B4X 论坛对 Galaxy Z Fold 4/5 的实测枚举了全部四个候选传感器（65686 `FOLDING_ANGLE_NON_WAKEUP`、65695、65697、标准 36），结论：

> 只有 `SENSOR_TYPE_HINGE_ANGLE` 触发，而且**只在折叠时触发——展开时不触发**。

而「展开」恰恰是最常用的触发方向。**这是整个设计的核心约束，也是不采用单信号监听的理由。**

> **❗ 2026-09-20 实测修订**：该结论在**小米 MIX Fold 3 上不成立** —— 实测完整捕获双向往返
> （`177 → 24 → 2 → 89 → 176`，见 §9.1 #1）。此约束**在本机解除**，但保留原文以记录设计推演过程：
> 方案是在「展开可能不上报」这一最坏假设下设计的，因此引入了多路融合；实测证明多路融合依然有价值，
> 只是理由变了（见 §9.2：`device_posture` 滞后、半折值不可靠）。**跨机型时仍需按此最坏假设做兜底。**

### 1.4 `Sensor.TYPE_HINGE_ANGLE = 36` 是公开 API

- AOSP `Sensor.java` 中该常量**无 `@SystemApi` 标注**，API diff 确认 **Added in API level 30**
- `PackageManager.FEATURE_SENSOR_HINGE_ANGLE = "android.hardware.sensor.hinge_angle"` 可用于能力探测
- HAL 定义（`sensors/2.1/types.hal`）：reporting-mode = **on-change**，wake-up = yes，单位度
- **`getDefaultSensor()` 用 Application Context 即可**；设备无此硬件时返回 null
- `HIGH_SAMPLING_RATE_SENSORS` 权限**不适用**（只约束加速度计/陀螺仪/磁场）

> **⚠️ `Sensor.TYPE_HALL` 不存在。** 调查中出现过「值 25 是霍尔传感器」的说法，这是错的——AOSP 中值 25 是 `TYPE_PICK_UP_GESTURE`。霍尔传感器从来没有公开的 sensor type 常量。**不要在设计中引用它。**

### 1.5 on-change 传感器的后台限制 → 前台服务是硬前提

Android 9+ 对**处于后台**的应用限制 continuous / on-change 传感器上报。`TYPE_HINGE_ANGLE` 是 on-change 模式，因此**没有前台服务就收不到回调**。

**vFlow 天然满足**：`TriggerService` 已是 `specialUse` 类型的前台服务。但存在一个耦合陷阱 → §8.1。

### 1.6 明确不可用的路径（调研已排除）

| 路径 | 排除理由 |
|---|---|
| **厂商折叠广播** | 定向搜索三星 `FOLDING_STATE_CHANGED` 等，**未找到任何可被第三方接收的官方广播**。权威源 `DeviceStateManager` 是 `@SystemApi`，其 `CONTROL_DEVICE_STATE` 权限为 `signature` 级 |
| **`WindowInfoTracker` + Application Context** | **直接抛 `IllegalArgumentException`**（AOSP 强校验 `isUiContext()`），不是返回空流。唯一合法绕过是 `createWindowContext()` 造 UiContext，但**查无任何真机验证报告**，且要求厂商 Extensions `extensionVersion >= 2` |
| **Application 级 `registerComponentCallbacks`** | `ConfigurationController.handleConfigurationChangedInner()` 派发时带 `includeUiContexts=false`，纯后台无窗口时**静默不回调**，极难排查 |
| **宽高比推断** | 旋转屏幕会翻转宽高，必然误判 |
| **logcat 解析** | 需 `READ_LOGS`（ADB 手工授予），且 tag 名随厂商版本漂移（MacroDroid 的 `lid_state_changed` 在 One UI 5.1 上消失过） |

> **`WindowInfoTracker` 的空列表语义**：返回空 `FoldingFeature` 列表有 **8 种以上**成因（窗口不跨折痕 / PIP 模式 / 非默认 display / 完全合上 / 零尺寸 / 无法映射状态 …），无法区分「完全展开」与「完全合上」。这是不选它作主路径的另一理由。

---

## 2. 目标能力与范围

### 2.1 目标

1. 新增触发器模块 `vflow.trigger.fold`，支持三种触发条件：**折叠** / **展开** / **半折**
2. 在后台稳定工作（依赖已有的 `TriggerService` 前台服务）
3. 输出当前状态与铰链角度，供下游用魔法变量引用（详见 §4）
4. 不引入新依赖（**不新增 `androidx.window`**），纯新增文件为主

### 2.2 明确不做（本期）

| 不做 | 理由 |
|---|---|
| `display_features` 折痕几何 / 按折痕分屏 | 需要坐标轴转置逻辑，属布局适配范畴，与触发器目标无关 |
| `WindowInfoTracker` / `FoldingFeature` | §1.6：后台路径无验证，且要求厂商 Extensions 版本 |
| 角度阈值触发（如「展开到 90° 时触发」） | 可作为 P2 扩展：`angle` 输出已预留（§4），届时加一个 NUMBER 参数即可 |
| tent / wedge 模式的独立枚举 | AOSP 直到 Android 16 才正式建模，之前一概归入「半折」，做了也是猜 |
| 折叠状态的**控制**（触发折叠） | 需 `CONTROL_DEVICE_STATE`（signature 级权限），不可能 |

---

## 3. 架构设计

### 3.1 分层

```
┌─ 信号层（Handler 内部，厂商知识收敛于此）────────────────┐
│  A. 小米 device_posture（ContentObserver）   ← 主力      │
│  B. 铰链角度传感器 TYPE_HINGE_ANGLE (36)     ← 补充      │
│  C. DisplayListener 增删/变更                ← 兜底      │
└──────────────────────┬──────────────────────────────────┘
                       ↓  原始信号（int / float / displayId）
┌─ 推断层（纯函数，可单测）───────────────────────────────┐
│  FoldStateResolver                                      │
│    · 信号融合 → FOLDED / HALF_OPENED / UNFOLDED         │
│    · 迟滞（hysteresis）防抖                             │
│    · 去抖（连续 N 次一致才确认）                         │
│    · 边沿检测（只对状态变迁触发，启动基线不触发）         │
└──────────────────────┬──────────────────────────────────┘
                       ↓  FoldState + angle + source
┌─ 分发层 ────────────────────────────────────────────────┐
│  匹配 fold_event → executeTrigger(trigger, FoldTriggerData) │
└─────────────────────────────────────────────────────────┘
```

**为什么是「状态推断」而不是「事件监听」**：因为单信号不可靠。做事件监听会继承每个信号源的缺陷；做状态推断则可以在任一信号缺失时降级。

> **v2.0 实测修订**：原设计的理由是「展开方向可能不上报」（§1.3），该理由**已被实测推翻**（§9.1 #1）。
> 但「状态推断」这个决策**依然成立，且理由更强**了 —— 因为实测发现 `device_posture` 滞后 1–2 秒
> 且半折值不可靠（§9.2），**没有任何单一信号能直接给出可信的三态判定**，必须自己推断。

### 3.2 信号优先级与降级（**v2.0 已按实测对调 1/2 位**）

按可用性依次尝试，**运行时决定**（不是编译期分支）：

| 优先级 | 信号 | 条件 | 说明 |
|---|---|---|---|
| **1** | **铰链角度** | `SDK_INT >= 30` 且 `getDefaultSensor(36) != null` | **实测实时连续可信**（§9.1 #1），三态判定以它为准 |
| **2** | `device_posture` | 小米设备（读取非 0 即视为可用） | **实测滞后 1–2 秒**（§9.2 问题 1），降为**校验/兜底** |
| 3 | DisplayListener | 总是可用 | 只知「变了」，需交叉验证 |

> **⚠️ 这是 v2.0 最重要的修订。** v1.0 把 `device_posture` 当作「直接给状态，最可靠」放在了首位，
> 实测证明**恰恰相反**：它滞后且半折值几乎不出现。角度才是主力。

**降级语义**：
- 有信号 1 → **以角度为准**做三态判定；信号 2 用于**校验与补边沿**（若角度缺失或突变异常）
- 无信号 1、有信号 2 → 用 `device_posture` 判定，但需**接受 1–2 秒延迟**，且**半折判定不可靠**（§9.2 问题 2）
- 只有信号 3 → 用 display 指纹变化做粗判定（内/外屏切换，§9.1 #4 证实严格对称），**标记 `posture_source = "display"`**

> 信号 3 单独工作的判定逻辑容易误判（分屏、PIP 都会改尺寸），因此**必须把来源暴露给用户**（§4），让用户知道自己的工作流建立在低可靠信号上。

### 3.3 迟滞与去抖参数

角度判定需要迟滞，否则角度在阈值附近抖动会反复触发。

**已按实测校准（2026-09-20）** —— 实测角度分布：完全展开 **172–179** / 半折约 **80–110** / 完全折叠 **2–10**，稳定期抖动仅 **7°**（§9.1 #6、§9.3）：

| 判定 | 阈值（**实测校准值**） | 说明 |
|---|---|---|
| → `FOLDED` | 角度 **< 40°** | 折叠态实测 2–10°，留足余量 |
| → `UNFOLDED` | 角度 **> 150°** | 展开态实测 172–179° |
| → `HALF_OPENED` | **40°–150° 且持续 ≥ 500ms** | 半折实测 80–110°，落在区间中部 |

- **去抖**：连续 3 次采样一致才确认状态切换（参照 `PoseTriggerHandler.kt:20` 的 `MAX_RECENT_SAMPLES` 模式）
- **角度噪声门**：0.3°（社区经验值）。实测稳定期抖动 7° 远大于此，故 0.3° 足够
- **半折必须由角度判定**，不可依赖 `device_posture == 2`（§9.2 问题 2：实测仅 2/150 采样出现）

> **已知取舍**：`TENT` 实测出现在 **31°–36°**（§9.1 #5），落在 `FOLDED` 阈值内 —— 用户折到 35° 立起来会被判为「折叠」。
> P1 接受此取舍；独立枚举见 §10。

### 3.4 边沿检测

**服务启动时先读当前状态作为基线，不触发任何工作流**——否则每次重启服务（开机、崩溃恢复）都会误触发一轮。只对「基线之后的状态变迁」触发。

这与 `PoseTriggerHandler.kt:110-125` 的 `isWithinMatchWindow` 进入/退出窗口模式一致：进入沿触发一次，退出沿只重置标记。

---

## 4. 输出与魔法变量（可引用的返回值）

### 4.1 引用方式

触发器输出在**魔法变量选择器里可见可引用**（`WorkflowEditorMagicVariableCatalogBuilder.kt:180` 明确处理触发器步骤，展示为 `#0 <模块名>` 分组）。引用格式：

```
{{<触发器步骤的stepId>.fold_state}}
```

用户实际使用时从编辑器魔法变量面板点选，不需要手写 id。触发器输出**按 schema 去重**（`WorkflowEditorMagicVariableCatalogBuilder.kt:225-237` 的 `triggerSchemaKey`），因此同类型触发器出现多次不会重复展示。

### 4.2 输出清单

| 输出 id | 类型 | 引用示例 | 语义 |
|---|---|---|---|
| `fold_state` | `vflow.type.string` | `{{t1.fold_state}}` | 当前状态，**稳定常量**：`folded` / `half_opened` / `unfolded` |
| `angle` | `vflow.type.number` | `{{t1.angle}}` | 当前铰链角度（度）。**不可用时为 `-1`** |
| `is_folded` | `vflow.type.boolean` | `{{t1.is_folded}}` | 是否折叠态（便捷判断，等价于 `fold_state == "folded"`） |
| `is_unfolded` | `vflow.type.boolean` | `{{t1.is_unfolded}}` | 是否展开态 |
| `is_half_opened` | `vflow.type.boolean` | `{{t1.is_half_opened}}` | 是否半折态 |
| `posture_source` | `vflow.type.string` | `{{t1.posture_source}}` | 数据来源：`miui` / `sensor` / `display`，**诊断用** |

类型 id 取自 `VTypeRegistry`（`VTypeRegistry.kt:20-26`）：`NUMBER = "vflow.type.number"`、`BOOLEAN = "vflow.type.boolean"`、`STRING = "vflow.type.string"`。值构造用 `VString` / `VNumber` / `VBoolean`，与现有触发器一致（如 `AppPackageTriggerModule.kt:119-121`）。

### 4.3 两个设计取舍（需明确记录）

**① `fold_state` 输出稳定常量，不输出本地化文案。**

仓库里两种做法都有：`AppPackageTriggerModule.kt:121` 输出常量 `event`，而 `CallTriggerModule.kt:80` 输出 `displayState`（本地化文案）。**本设计选前者**，理由：输出的主要消费者是下游的**条件判断**（`If` 模块比较字符串），用本地化文案会导致「切换系统语言后工作流判断失效」。可读性由布尔输出和 `getSummary` 的本地化摘要补偿。

**② 输出 6 个是上限，不再加。**

触发器输出会在选择器里占一整组。`Pose` 输出 4 个、`Location` 输出 3 个，6 个已是偏多。`is_half_opened` 保留是因为半折是独立触发条件；`posture_source` 保留是因为低可靠信号必须可诊断。**后续若加「折痕方向」等输出，需先砍掉等量的现有项。**

---

## 5. 模块设计

### 5.1 模块元数据

```kotlin
id = "vflow.trigger.fold"          // 一经发布不可改
categoryId = "trigger"             // 与现有一致；同时写 category = "触发器"
iconRes = R.drawable.rounded_fold_24
```

### 5.2 输入参数

对齐 `ScreenTriggerModule.kt:30-45` 的惯例（ENUM + `CHIP_GROUP`）：

| 参数 id | 类型 | 选项常量 | 默认 |
|---|---|---|---|
| `fold_event` | ENUM + `CHIP_GROUP` | `folded` / `unfolded` / `half_opened` | `unfolded` |

**必须补 `legacyValueMap`**（参照 `PowerTriggerModule.kt:28-31`）。`ScreenTriggerModule` 恰好漏了这个，是新代码应当修正的惯例。

取值一律走 `normalizeEnumValue`（`core/module/definitions.kt`），**Handler 里不得硬编码中文**——照抄 `ScreenTriggerHandler.kt:63` 的写法：

```kotlin
val eventInput = FoldTriggerModule().getInputs().first { it.id == "fold_event" }
val expected = eventInput.normalizeEnumValue(rawValue) ?: rawValue
```

### 5.3 Handler 基类选择

**继承 `BaseTriggerHandler`，不继承 `ListeningTriggerHandler`。**

理由与 `PoseTriggerHandler.kt:16` 完全一致：`ListeningTriggerHandler` 假设的是「第一个触发器加入即开始监听、最后一个移除即停止」的简单模型，而我们需要自己管理**多信号源的启停 + 状态机去抖缓存**，因此自管生命周期（参照 `PoseTriggerHandler.kt:131-153` 的 `reloadTriggers` / `startListening` / `stopListening` 三件套）。

`TriggerSpec` 载荷用新增的 `FoldTriggerData : Parcelable`（照 `PoseTriggerData.kt` 的写法），承载 §4.2 的六个字段。

### 5.4 竖向折叠（flip）的语义差异

flip 机型合上后**外屏仍可用**，与 fold 合上即黑屏不同。同一「折叠」事件在两类设备上能执行的操作不同：

- fold 合上 → 主屏关闭，**涉及 UI 操作的模块大概率失败**
- flip 合上 → 外屏可用但空间极小，UI 操作同样不可靠

**需在模块描述文案里点明**（`module_vflow_trigger_fold_desc`），避免用户误以为折叠后仍能操作界面。这不是代码限制，而是使用边界的告知。

---

## 6. 文件级改动计划

### 6.1 新增文件（首选，符合 fork 原则）

| 文件 | 内容 |
|---|---|
| `core/workflow/module/triggers/FoldTriggerModule.kt` | 模块定义、`getInputs` / `getOutputs` / `getSummary` / `execute` |
| `core/workflow/module/triggers/FoldTriggerData.kt` | `@Parcelize` 载荷（六字段） |
| `core/workflow/module/triggers/FoldStateResolver.kt` | **纯函数状态机**（融合 / 迟滞 / 去抖 / 边沿），无 Android 依赖 |
| `core/workflow/module/triggers/handlers/FoldTriggerHandler.kt` | 三路信号注册 + 调用 resolver + 分发 |
| `res/drawable/rounded_fold_24.xml` | 图标 |
| `app/src/test/.../triggers/FoldStateResolverTest.kt` | 纯 JVM 单测（见 §6.3） |

### 6.2 修改的上游文件（力争最少，需登记 FORK.md）

| 文件 | 改动 | 行号锚点 |
|---|---|---|
| `core/workflow/module/ModuleRegistry.kt` | 触发器段追加 1 行注册 | 触发器段 **L67-90**，`register(VoiceTriggerModule(), context)` 为最后一行，追加于其后 |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 追加 1 行 | 注册块 **L28-48**，最后一条是 L47 `register(PoseTriggerModule().id)`，追加于其后 |
| `res/values/strings_module.xml` | 追加 fold 文案块 | screen 块之后（**L716-724** 之后） |
| `res/values-en/strings_module.xml` | 同上 | screen 块之后（**L698-705** 之后） |
| `res/values-ja/strings_module.xml` | 同上 | screen 块之后（**L640-647** 之后） |

> **⚠️ 两个注册表必须都改。** 漏 `ModuleRegistry` → 模块不出现在选择器；漏 `TriggerHandlerRegistry` → 模块能选但**永远不触发**（`TriggerService.kt:216-227` 从注册表工厂创建 handler）。这是最容易漏的一处。

**不需要改** `AndroidManifest.xml`（无新 Activity / Service），**不需要**新增权限（`Settings.Global` 读取与传感器均无需权限）。

### 6.3 字符串资源清单（按现有命名惯例）

```
module_vflow_trigger_fold_name          折叠屏触发
module_vflow_trigger_fold_desc          当设备折叠、展开或半折时触发工作流
param_vflow_trigger_fold_event_name     触发条件
summary_vflow_trigger_fold_prefix       折叠屏
msg_vflow_trigger_fold_triggered        折叠屏状态已触发
option_vflow_trigger_fold_folded        折叠时
option_vflow_trigger_fold_unfolded      展开时
option_vflow_trigger_fold_half_opened   半折时
output_vflow_trigger_fold_state_name    当前状态
output_vflow_trigger_fold_angle_name    铰链角度
output_vflow_trigger_fold_is_folded_name         已折叠
output_vflow_trigger_fold_is_unfolded_name       已展开
output_vflow_trigger_fold_is_half_opened_name    半折
output_vflow_trigger_fold_posture_source_name    信号来源
```

三份文件（`values/` / `values-en/` / `values-ja/`）**都要补齐**，否则英文环境下会 fallback 到中文。

### 6.4 测试策略

项目**只配了纯 JVM 测试**（`junit:junit:4.13.2`，无 Robolectric / Mockito），因此：

- **可单测**：`FoldStateResolver` 的全部逻辑（融合、迟滞、去抖、边沿、降级）。照 `PoseTriggerMathTest.kt` / `AlarmTriggerSchedulerTest.kt` 的抽法——把纯逻辑抽成无 Android 依赖的文件。
- **不可单测**：`SensorManager` / `ContentObserver` / `DisplayManager` 的注册与回调，**必须真机验证**。
- **建议追加**：在 `TriggerHandlerEnumNormalizationTest.kt` 补一段 `fold_event` 归一化用例，与现有 Wifi / Bluetooth / Call 等保持一致。

---

## 7. P0 真机验证清单 —— ✅ 已完成（2026-09-20，结论见 §9）

> **状态**：第 1–6 项已通过 adb 实测完成（`scripts/fold-trigger-verify.sh`，零代码）。
> **第 7 项（前台服务降级）与「应用内回调是否触发」未验证**，转列 §9.4，需在 P1 实现时补。

原验证清单（按信号优先级排序）保留如下，作为**跨机型复测**时的对照：

| # | 验证项 | 方法 | 结论 |
|---|---|---|---|
| 1 | `device_posture` 是否可读 | `settings get global device_posture` | ✅ 可读，无权限问题（§9.1 #2） |
| 2 | `device_posture` 的 ContentObserver 是否触发 | 需应用内注册 | ⏳ 转 §9.4（P1 补） |
| 3 | `device_posture` 是否双向 | 观察完整往返 | ⚠️ 双向但**滞后 1–2 秒**（§9.2 问题 1） |
| 4 | **铰链角度是否双向上报** | `dumpsys` 观察角度序列 | ✅ **双向**，推翻悲观假设（§9.1 #1） |
| 5 | 角度阈值实际落在哪 | 观察完整角度曲线 | ✅ 已校准为 40°/150°（§9.3） |
| 6 | DisplayListener 是否双向 | `dumpsys display` 观察 | ✅ 内外屏切换严格对称（§9.1 #4） |
| 7 | 前台服务降级后是否失效 | 关闭服务通知开关重测 | ⏳ 转 §9.4（P1 补） |

**验证脚本**：`scripts/fold-trigger-verify.sh`（`snapshot` / `watch` / `raw` 三个模式），可复用于其他机型。

> **产物**：实测结论已回填 §9，并据此**修订了 §3.2 信号优先级（对调 1/2 位）与 §3.3 参数**。
> 这与 `docs/fork/chat-float-window-design.md` §9.1 的做法一致 —— 先验证再设计。

---

## 8. 已知限制与风险

### 8.1 ⚠️ 前台服务降级会导致传感器静默失效

`TriggerService.kt:194-211` 的 `updateForegroundState()`：当用户关闭 `backgroundServiceNotificationEnabled` 时，会调 `stopForeground(STOP_FOREGROUND_REMOVE)` **把服务降级为后台服务**。此时 Android 9+ 的后台传感器限制生效 → `TYPE_HINGE_ANGLE`（on-change 模式）**收不到回调**。

**影响范围不止本功能**：现有的**姿态触发器**（`PoseTriggerHandler`）、**敲击触发器**（`BackTapTriggerHandler`）同样依赖传感器，会一并静默失效。

**建议**：这是一个既有缺陷，本设计不负责修复，但应：
1. 在 `FoldTriggerHandler` 启动时检测前台状态，降级时打 warning 日志
2. 考虑在文档/UI 上提示用户「关闭服务通知会影响传感器类触发器」
3. 作为独立议题评估（不要塞进本功能的改动里）

### 8.2 信号 3 单独工作时的可靠性

只有 DisplayListener 可用时（非小米设备且无铰链传感器），判定依赖 display 指纹 + 尺寸变化，**分屏 / PIP 会污染尺寸**。因此：
- 输出 `posture_source = "display"` 明确标注
- 不使用宽高比，改用 `Display.getUniqueId()` / `getName()` 做指纹
- 建议在 `getSummary` 或 UI 上对低可靠来源给出提示（待定，P1 决策）

### 8.3 半折语义的机型差异

- 官方注明 `device_posture = 2`（半折）标注了「MIX Fold 2 支持」——**不保证所有机型都有半折状态**
- AOSP 直到 Android 16 才正式建模 tent / wedge，之前一概归入 `HALF_OPENED`
- 因此 `half_opened` 触发的可用性**依赖机型**，需在 P0 验证中确认目标设备是否上报

### 8.4 `device_posture` 的读取安全性未实测

调研的推断是：`SettingsProvider.checkReadableAnnotation` 对**不在 `Settings.*` 中定义的 key 一律视为可读**，而 `device_posture` 不在 AOSP `Settings.java` 中，故不触发 `SecurityException`。**但这是源码推断，非实测**，且小米可能自行加了管控。→ **列为 P0 第 1 项验证。**

### 8.5 flip 机型的操作能力限制

见 §5.4。折叠后（无论 fold 还是 flip）涉及 UI 操作的模块大概率失败，需在文案中告知。

---

## 8.5 决策台账

| # | 决策 | 理由 | 状态 |
|---|---|---|---|
| 1 | 做状态推断，不做事件监听 | 单信号不可靠（§1.3） | 定稿 |
| 2 | 小米 `device_posture` 为主力信号 | 直接给状态枚举，无需阈值计算 | 定稿（待 P0 验证可读性） |
| 3 | 不引入 `androidx.window` 依赖 | 后台路径无验证 + 要求厂商 Extensions 版本（§1.6） | 定稿 |
| 4 | 继承 `BaseTriggerHandler` 而非 `ListeningTriggerHandler` | 需自管多信号源生命周期（§5.3） | 定稿 |
| 5 | `fold_state` 输出稳定常量而非本地化文案 | 避免语言切换导致下游判断失效（§4.3） | 定稿 |
| 6 | 输出 6 项，含 `posture_source` 诊断项 | 低可靠信号必须可诊断（§4.3） | 定稿 |
| 7 | 不做 `display_features` 折痕几何 | 需坐标轴转置，属布局适配（§1.2） | 定稿（P2 可扩展） |
| 8 | 不做角度阈值触发 | `angle` 输出已预留，后续加参数即可 | 定稿（P2） |
| 9 | 迟滞阈值 30°/60°/150° + 3 次去抖 | 防角度抖动反复触发 | **已按 §9.3 实测校准** |
| 10 | 服务启动读基线不触发 | 避免重启服务误触发（§3.4） | 定稿 |
| 11 | 优先做 P0 真机验证，再写实现 | 关键结论无跨机型证据 | ✅ 已完成（§9） |
| 12 | **`device_posture` 降为辅助信号，角度升为主力** | 实测 `device_posture` 明显滞后（§9.2 问题 1） | 定稿（v2.0 修订） |
| 13 | **迟滞阈值改为 40°/150°** | 实测角度分布（§9.3） | 定稿（v2.0 修订） |
| 14 | **`half_opened` 判定必须自己算，不依赖 `device_posture=2`** | 实测 `device_posture=2` 仅短暂出现（§9.2 问题 2） | 定稿（v2.0 修订） |

---

## 9. P0 真机验证结论（2026-09-20 完成）

> **验证方式**：`scripts/fold-trigger-verify.sh`（adb 采集，零代码）+ 两轮人工操作实测。
> **验证设备**：小米 MIX Fold 3（`2308CPXD0C`，Android 17 / API 37，澎湃 OS4.0，MIUI V816）。
> **原始数据**：本轮采集了 270 个采样点（第一轮 150 + 第二轮 120，含旋转），存放于 `.fold-verify/session/`。
> ⚠️ 该目录**未纳入版本控制**（`.gitignore:45`），因此上面的关键数据已摘录到本节正文中，无需查阅原始文件。
> 需要重新采集时跑 `scripts/fold-trigger-verify.sh watch`（脚本会自行创建该目录）。

### 9.1 实测证实的事实

| # | 结论 | 证据 |
|---|---|---|
| 1 | ✅ **铰链角度双向上报** —— **推翻了调研的核心悲观结论** | 完整捕获折叠往返：`177 → 24 → 2 → 89 → 176`。B4X 论坛「只在折叠方向触发」在 MIX Fold 3 上不成立 |
| 2 | ✅ **`device_posture` 可读**，无需特殊权限 | 全程读到 `1` / `2` / `3`，未抛 `SecurityException`（§8.4 的担忧解除） |
| 3 | ✅ **`FEATURE_SENSOR_HINGE_ANGLE` 存在**，`oem_hinge_angle` (type 36) 可用 | `pm list features` + `dumpsys sensorservice` 双重确认 |
| 4 | ✅ **内外屏是两个逻辑 display，切换严格对称** | display 0（1916×2160 内屏）/ display 1（1080×2520 外屏）交替 ON/OFF，折叠展开完全对称 |
| 5 | ✅ **`TENT` 状态真实存在**（DeviceStateManager） | 实测角度 31°–36° 窗口内 devState 变为 `TENT`（§1.6 判断「tent 要等 Android 16」在本机不成立） |
| 6 | ✅ **角度稳定期抖动仅 7°**（172–179） | 124 个稳定采样，无需复杂滤波，简单阈值即可 |

### 9.2 ⚠️ 实测发现的**新问题**（调研完全未提及）

**问题 1：`device_posture` 明显滞后，不能作为判定主力。**

```
15:21:53  angle= 24.0  posture= 3(展开)  ← 已折到 24°，posture 仍说展开
15:22:01  angle= 79.0  posture= 3(展开)  ← 已折到一半，posture 仍说展开
15:22:02  angle= 83.0  posture= 3(展开)  ← 同上，devState 已是 HALF_OPENED
15:22:03  angle= 82.0  posture= 2(半折)  ← 迟了约 1–2 秒才更新
```

**`device_posture` 的更新滞后于物理动作约 1–2 秒**，而角度是**实时连续**的。
→ **决策修订**：角度升为主力信号，`device_posture` 降为辅助/校验。原方案（§3.2）把优先级搞反了。

**问题 2：`device_posture = 2`（半折）只短暂出现，不可依赖。**

全 150 采样中 `posture=2` 仅出现 **2 次**，而 `posture=3` 有 146 次。
半折过程（角度 80–110）期间大部分时间 `posture` 仍报 `3`。
→ **决策修订**：`half_opened` 必须**由角度自行判定**，不能依赖 `device_posture == 2`。

**问题 3：`fold_status` 事件字段不连续（未采用该传感器）**

小米私有 `xiaomi.sensor.fold_status(33171087)` 虽同时提供状态与角度，但实测采样中其角度字段跳变较大（`163 → ... → 179`，中间存在缺失）。且为私有 type id，跨机型不可用。
→ **决策**：不采用，仅记录为小米机型潜在增强项（§10）。

### 9.3 按实测校准的参数（替代 §3.3 初值）

实测角度分布：**完全展开 172–179** / **半折约 80–110** / **完全折叠 2–10**，稳定期抖动 7°。

| 判定 | 原初值 | **实测校准值** | 依据 |
|---|---|---|---|
| → `FOLDED` | < 30° | **< 40°** | 折叠态实测 2–10°，留足余量 |
| → `UNFOLDED` | > 60° | **> 150°** | 展开态实测 172–179°，阈值下调留抖动余量 |
| → `HALF_OPENED` | 30–150° | **40°–150° 且持续 ≥ 500ms** | 半折实测 80–110°，落在区间中部 |
| 角度噪声门 | 0.3° | **保持 0.3°**（足够） | 稳定期抖动 7°，远大于噪声 |
| 去抖次数 | 连续 3 次 | **连续 3 次**（保持） | 未观察到误触发，保持 |

> **注意**：`TENT`（31°–36°）落在 `FOLDED` 阈值（<40°）内 —— 若用户折到 35° 立起来，会被判为「折叠」而非「半折」。这是 P1 的已知取舍（§10 记录了独立枚举的后续可能）。

### 9.4 未验证项

| # | 待验证 | 状态 |
|---|---|---|
| 1 | **前台服务内 `SensorManager` 回调是否真触发**（§9.4 核心门槛） | ✅ **已验证可用**（2026-09-20 真机：`vflow.trigger.fold` 触发器正常工作） |
| 2 | **关闭服务通知降级后是否静默失效**（§8.1） | ⏳ 未测。需关掉「后台服务通知」开关后复测触发器 |
| 3 | 旋转屏幕是否误判 | ⛔ **不测**。见下方说明 |
| 4 | 跨机型行为 | ⏳ 仅 MIX Fold 3。其他机型（尤其 flip 竖折）未验证 |

> **第 3 项为何不测**：原担心「旋转会翻转窗口宽高比导致误判」。实测发现本机**内屏 `ignoreOrientationRequest=true`**
> （Android 16+ 大屏策略），内屏根本不会因旋转而改变朝向 —— 该风险在内屏不存在。
> 且触发器的三态判定**完全基于铰链角度**，不依赖窗口尺寸或宽高比，旋转与之无关。
> 故此项对方案无决策价值，确定不做。外屏仍会正常旋转，但同样不影响角度判定。

> **第 2 项为何仍需关注**：这不是本功能引入的问题，而是 `TriggerService.kt:194-211` 的既有设计 ——
> 用户关闭后台服务通知会使服务降级，Android 9+ 的后台传感器限制随之生效，
> **所有传感器类触发器**（本功能、姿态触发器、敲击触发器）都会受影响。属独立议题，见 §8.1。

### 9.5 对方案的净影响

**简化**：原为应对「展开可能不上报」而设计的三路降级链路（§3.2）**可大幅简化** —— 主力用角度，`device_posture` 仅作校验，DisplayListener 降为可选兜底。

**复杂化**：必须自己实现完整的状态判定（角度 → 三态），因为 `device_posture` 滞后且半折值不可靠。这**反而印证了「做状态推断而非事件监听」的核心决策**（§0）。

**结论**：方案架构方向正确，但**信号优先级需对调**（角度 > `device_posture`），参数需按 §9.3 校准。已在上文 §3 与 §8.5 决策台账（#12–14）登记修订。

---

## 10. 后续（P2+ 候选）

- **角度阈值触发**：`展开到 90° 时触发`，MacroDroid 有对应能力。追加一个 NUMBER 参数 + 比较模式即可，`angle` 输出已预留
- **折痕几何**：接入 `display_features`（含坐标轴转置），用于「展开后自动分屏」类场景
- **Tent 独立枚举**：§9.1 已证实 MIX Fold 3 会上报 `TENT`（31°–36° 窗口），但窗口太窄，P1 暂归入「折叠」，P2 可评估独立
- **前台服务降级问题**（§8.1）：独立议题，影响所有传感器类触发器
- **`fold_status` 私有传感器**（§9.1）：小米私有，含状态+角度+多路冗余字段，未采用（跨机型风险），但可作为小米机型的增强信号
- **跨机型复测**：用 `scripts/fold-trigger-verify.sh` 在其他折叠屏（尤其 flip 竖折）上复测，验证 §9 结论的普适性

---

## 11. 实现状态与交接（2026-09-20 收尾）

**状态：✅ 开发完成。** 本功能到此为止，后续按 §10 的 P2 候选另行评估。

### 11.1 交付清单

| 文件 | 性质 | 说明 |
|---|---|---|
| `core/workflow/module/triggers/FoldTriggerModule.kt` | 新增 | 模块定义：1 个 ENUM 参数 + 6 个输出 |
| `core/workflow/module/triggers/FoldStateResolver.kt` | 新增 | **纯函数状态机**（无 Android 依赖，可纯 JVM 单测） |
| `core/workflow/module/triggers/FoldTriggerData.kt` | 新增 | `@Parcelize` 载荷（六字段） |
| `core/workflow/module/triggers/handlers/FoldTriggerHandler.kt` | 新增 | 融合角度 + `device_posture`，厂商私有 key 收敛于此 |
| `res/drawable/rounded_fold_24.xml` | 新增 | 图标（一体机身 + 中央折痕线） |
| `test/.../triggers/FoldStateResolverTest.kt` | 新增 | 25 例单测，全通过 |
| `ModuleRegistry.kt` | **追 1 行** | 注册模块（否则不出现在选择器） |
| `TriggerHandlerRegistry.kt` | **追 1 行** | 注册 Handler（否则收不到事件） |
| `strings_module.xml` ×3 | **追 1 段** | 中/英/日文案 |
| `FORK.md` / `AGENTS.md` / `TODO.md` / `surveys/README.md` | 文档 | 分歧登记 + 导航 + 待办 + 数字修正 |

### 11.2 验证覆盖

| 项 | 状态 |
|---|---|
| 单测（状态机 25 例） | ✅ 全通过 |
| release 构建 | ✅ 签名正确（`CN=vFlow Fork`） |
| 真机功能验证 | ✅ **触发器可正常触发**（MIX Fold 3） |
| 应用内传感器回调 | ✅ 随真机验证一并确认 |

### 11.3 遗留项（不阻塞收尾）

1. **关闭服务通知后是否失效** —— 未测。属既有缺陷（§8.1），影响所有传感器类触发器，非本功能引入
2. **跨机型** —— 仅 MIX Fold 3。flip 竖折未验证
3. **TENT 归入 FOLDED** —— 已知取舍，P2 可独立

### 11.4 维护提示（给未来的自己）

- **阈值在 `FoldStateResolver` 的伴生对象里**，改动后跑 `FoldStateResolverTest` 即可回归
- **调阈值时注意迟滞**：进入与退出用不同值（如 `FOLDED_ENTER=40` / `FOLDED_EXIT=55`），
  只改一个会导致状态在边界抖动 —— 单测里有专门的抖动回归用例
- **`device_posture` 是小米私有 key**，只允许出现在 `FoldTriggerHandler` 内；
  若将来要支持别的厂商，应新增一个信号源而非在该文件里加分支
- **`posture_source` 输出只有 `sensor` / `miui` 两个值**（设计时计划的 `display` 兜底路径未实现，因为实测证明前两路够用）
