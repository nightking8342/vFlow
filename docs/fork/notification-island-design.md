# 通知机制改造设计 —— 适配小米澎湃 OS 超级岛

> **版本**：v1.0 · 2026-09-15
> **对应分支**：`session-0915-02`（基于 `dev`，`versionName 1.5.4` / `versionCode 50`）
> **目录归属**：fork 独有文档 → 冲突归**我方**（上游无此文件）
> **前置文档**：现状见 [`docs/fork/surveys/notification-system-overview.md`](surveys/notification-system-overview.md)（8 处生产者 / 2 处消费者链路全量走查）

**证据来源**：

- **官方接入文档**：`D:/claudebot/mindfs/.mindfs/upload/2026-08-14/小米超级岛开发接入文档.md`（权威）
- **官方模板库**：`D:/develop/android/AndroidStudioProjects/islandSupport/reference-projects/超级岛模板.md`（权威）
- **已落地实现**：mindfs `android/app/src/main/java/com/mindfs/app/FocusIslandSupport.java`（真机验证过的 fork 自研实现，**本文的主要参考**）
- vFlow 侧现状：直读本仓库代码，行号均已核对

---

## 0. 一句话方案

**新增一层「岛参数装配器」，把厂商特定知识完全隔离在新文件里；业务侧只提交结构化状态，装配器决定「岛 / 焦点通知 / 普通通知」走哪条渲染路径。** vFlow 现有 8 处通知生产者中，**只有工作流执行状态和 AI 对话状态值得上岛**，其余保持原样。

---

## 1. 从素材中提炼的关键事实

以下均为**已核实**的外部事实（非本项目代码），是设计的约束条件。

### 1.1 接入方式：纯本地通知 + extras，不需要 MiPush

官方提供两条路径：客户端实现、MiPush 服务实现（接入文档 `:5-9`）。**vFlow 必须走客户端实现**——MiPush 路径要求走小米推送服务端且仅支持 regId 发送（`:89-92`），与本地自动化工具的定位不符。

客户端实现的做法极简：**构建一条普通通知，往 `notification.extras` 塞约定 key，然后正常 `notify()`**（`:51-84`）：

```java
notification.extras.putString("miui.focus.param", islandParams);  // islandParams 是 JSON 字符串
notificationManager.notify(1, notification);
```

> **对 vFlow 的意义**：不需要引入任何 SDK、不需要联网、不需要小米审核。**这完全符合 fork「新增能力、控制 diff 面积」的原则。**

### 1.2 能力探测有三个层次，缺一不可

| 探测项 | 方法 | 返回 |
|---|---|---|
| OS 是否支持岛 | 反射 `SystemProperties.getBoolean("persist.sys.feature.island", false)` | boolean |
| 焦点通知协议版本 | `Settings.System.getInt(cr, "notification_focus_protocol", 0)` | **1=OS1 / 2=OS2 / 3=OS3（3 才有岛）** |
| 应用是否被授权 | `contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, extras)` | `canShowFocus` boolean |

来源：`:443-503`。

**注意第三项是「应用级」权限**——用户在系统设置里逐应用开关，与 `POST_NOTIFICATIONS` 完全不同。mindfs 把三者合成一个 `isEnabled()`（`FocusIslandSupport.java:81-83`）：

```java
static boolean isEnabled() { return protocolVersion >= 2 && focusPermission; }
```

并且明确标注 `canShowFocus` 是**耗时 provider 调用，必须在后台线程执行**（`:54`），探测结果由 `refresh()` 缓存（`:48-50`）。

### 1.3 OS2 与 OS3 是两套渲染，同一份参数

- **OS2**：只显示焦点通知 + 状态栏 ticker，**没有岛**。
- **OS3**：显示超级岛（摘要态大岛/小岛）+ 焦点通知。
- 其余设备：**忽略这些 extras，按普通通知显示**（`FocusIslandSupport.java:24-27` 的设计前提）。

> 这条保证了「零影响降级」——**非小米设备无需任何分支判断**，塞了 extras 也不会坏事。这是整个方案里最重要的安全垫。

### 1.4 ✅ 已确认前提：澎湃 OS 不接管应用通知，必须应用自己适配

**（用户提供，2026-09-15）** 澎湃 OS **不会**把应用的普通通知（包括 AOSP Live Updates / `setRequestPromotedOngoing`）自动接管渲染成超级岛。**要上岛，只能由应用自己写入 `miui.focus.param` 等 extras。**

这条推翻了我最初的一个假设，带来三个直接结论：

1. **项目方向确定**：超级岛适配是**必须做的真实工作量**，不存在「AOSP 路径已被接管、无需适配」的可能。原先列为最大风险的 R8 消除。
2. **两套机制完全独立**：`ExecutionNotificationManager.kt:112` 现有的 `setRequestPromotedOngoing(true)`（AOSP Live Updates）与超级岛**互不相干**，不是竞争关系。在澎湃 OS 上它要么被忽略、要么只产生 AOSP 形态的展示，**与岛无关**。
3. **不需要真机探针**：mindfs 的实现在真机上**已稳定运行**，其字段组合是可直接复用的金标准；无需再从官方文档零散字段拼一份去试错。

### 1.5 数据分四块（以 `param_v2` 承载）

官方给的 JSON 骨架（`:63-71`、`:204-217`）：

```
miui.focus.param = {
  "param_v2": {
    <通知属性数据>,      // isShowNotification / business / timeout / updatable / reopen / enableFloat ...
    <通知内容数据>,      // baseInfo / chatInfo / hintInfo ...（模板决定）
    <岛属性数据>,        // param_island：islandProperty / islandTimeout / highlightColor / bigIslandArea / smallIslandArea
    <息屏显示数据>,      // aodTitle / aodPic
    <状态栏焦点数据>     // ticker / tickerPic（OS2 用）
  }
}
```

图片不走 JSON，而是**单独的 Bundle**（`:509-548`）：`miui.focus.pics` 里 `putParcelable("miui.focus.pic_xxx", Icon)`，JSON 里用 `{"type":1,"pic":"miui.focus.pic_xxx"}` 引用。

### 1.6 `miui.focus.rv` 会切到 `param.custom`，但**岛数据照常生效**

mindfs 标注了这个硬分叉（`FocusIslandSupport.java:187-190`）：

> SystemUI 的 `onNotificationPosted` 以 extras 里**有没有 `miui.focus.rv`** 硬分叉：有则走 `buildNoParamsFocusNotification`（读 `param.custom`），无则走模板（读 `param`）。唯一渲染展开态自定义视图的 `createCustomView` 只在前一条分支里被调用。

**⚠️ 修订（2026-09-17）**：本文档 v1.0–v2.0 曾写「设了 `miui.focus.rv` 会让整份模板作废」，**这是错的**。经核对 mindfs 的实现（`FocusIslandSupport.java:436-455`），其 `param.custom` 里**带着完整的 `param_island`**：

```java
return new JSONObject()
    .put("business", ...)
    .put("updatable", true)
    ...
    .put("param_island", paramIsland)   // ← 大岛/小岛数据照常传递
    .toString();
```

**真实差异只在三点**：

| | 模板路径 | 自定义路径 |
|---|---|---|
| 参数键 | `miui.focus.param` | `miui.focus.param.custom`（**两者可以同时存在**） |
| 结构 | `param_v2` 包裹 | **扁平**：`timeout` / `enableFloat` / `ticker` 直接在根级读，不解包 `param_v2` |
| 展开态卡片 | 由模板渲染 | **由 `miui.focus.rv` 渲染** |

**结论：`param_island`（大岛/小岛/摘要态）在两种路径下都生效，不受影响。** 自定义路径只是**接管展开态**。因此不需要「二选一」，可以同时提供两套参数——见 §6.7。

### 1.7 自定义模式的其它约束（mindfs 实测）

- `param.custom` 是**扁平结构**：`timeout` / `enableFloat` / `ticker` / `outEffectSrc` 直接从根级读，**不像模板那样解包 `param_v2`**（`:435-437`）。
- 自定义模式下通知卡片由 `miui.focus.rv` 提供，模板的**展开态模块**不会被渲染（岛数据不受影响，见 §1.6）。
- **`miui.focus.rv` 必须给**：`createCustomView` 在它为空时会提前 return，**连带跳过 `rv.tiny` / `rv.deco`**（`:205-207`）。
- 光效相关三个 key 位置不同、各自生效（`:36-46`、`:193-203`、`:426-431`）：
  - `miui.effect.src` → 展开态外圈光（只判非空，值本身不被解析为资源，社区约定填 `outer_glow`）
  - `miui.effect.color` → 光色，**自定义模式下必须直接写在 extras**（`fillCustomViewNotifiParams` 只搬 `outEffectSrc`，不读颜色）
  - `param_island.outEffectSrc` → 大岛自己的光（**与根级是两个不同位置**）
  - `miui.bigIsland.effect.src` → 大岛光，但**不在 `FocusNotificationController` 的白名单拷贝里**，普通应用能否送达待实测（写上无副作用）
- **RemoteViews 白名单严格**（`island_expand.xml:10-13`）：只能用 `LinearLayout` / `FrameLayout` / `RelativeLayout` / `ImageView` / `TextView` / `Chronometer` / `ProgressBar`。**`ConstraintLayout`、`Space`、自定义 View 一律远端 inflate 失败。**
- **根容器不设背景**：卡片底由 SystemUI 绘制，自带背景会叠成嵌套卡片。
- **动画 drawable 无效**：SystemUI 对 `miui.focus.pics` 里的动画 WebP **只取静态首帧**（FORK.md:39 记录，模板与 RemoteViews 两个通道都试过）。所以要动图必须走别的路。

### 1.8 可交互：两种 Action 通道

| 通道 | 做法 | 引用方式 |
|---|---|---|
| 原生 `Notification.Action` | `Bundle actions; actions.putParcelable("miui.focus.action_1", action); bundle.putBundle("miui.focus.actions", actions)` | JSON 里 `"actions":[{"action":"miui.focus.action_1"}]` |
| `ActionInfo` 自定义 | 模板字段里写 `actionInfo.action` / `actionIntent` / `actionIntentType` | 直接内联 |

来源：`:550-611`、模板库模板 8。**官方提示**：触发广播的 PendingIntent 需要 `FLAG_RECEIVER_FOREGROUND`（`:566-567`）。

### 1.9 模板库规模

- **展开态模板 22 个**（模板 1–22），组件可组合：文本组件 / 识别图形组件 / **进度组件** / 按钮组件 / IM 图文组件 / 通道组件 / 倒计时组件（模板库 `:3-712`）
- **大岛 9 种组合**（`:712-960`）：图文组件 + 文本/图文/进度文本/等宽数字/定宽数字/大图
- **小岛 3 种**（`:962-1011`）：图标组件 / 图标组合组件（带环形进度）/ 图标文本组件

**与 vFlow 最相关的两个**：

- **模板 5（文本组件1 + 识别图形组件1 + 进度组件2）**——`progressInfo.progress` + `colorProgress`/`colorProgressEnd`，**这是工作流进度条的直接对应物**。
- **大岛组合 5（图文组件1 + 进度文本组件）**——`progressTextInfo` 支持 `frontTitle`/`title`/`content` + 进度 + `colorReach`/`colorUnReach`，**这是「步骤 3/8」这类展示的直接对应物**。

---

## 2. 目标能力与范围

### 2.1 改造目标

在**不破坏非小米设备体验**的前提下，让 vFlow 的关键状态显示在澎湃 OS 的超级岛上：

| # | 场景 | 岛上的样子 | 优先级 | 本次范围 |
|---|---|---|---|---|
| **S1** | **工作流执行中** | 大岛：vFlow 图标 + 工作流名 + 「步骤 3/8」 | **P0** | ✅ 做 |
| **S2** | **工作流完成 / 失败** | 大岛：状态文案（已完成 / 失败）+ 结果摘要 | **P0** | ✅ 做 |
| ~~S3~~ | ~~AI 对话进行中~~ | —— | —— | ❌ **本次不做** |
| ~~S4~~ | ~~AI 对话等待审批 / 输入~~ | —— | —— | ❌ **本次不做** |

> **S3/S4 已于 2026-09-15 移出范围**（用户决定）。理由见 §2.3。

### 2.2 明确不做

- **不改 B 链路**（监听别人的通知）。读别人的通知与「自己的通知怎么显示在岛上」无关。
- **不给 4 处前台服务保活通知上岛**（`TriggerService` / `VoiceTriggerService` / `PermissionGuardianService` / `ChatFloatWindowService`）。它们是「满足契约」的存在感越低越好的通知；上岛会带来不相称的视觉权重与用户困惑。
- **不做 MiPush**。
- **不引入任何第三方依赖**（JSON 用项目已有的 Gson，见 §4.2）。

### 2.3 S3/S4（AI 对话上岛）移出范围

**结论：本次不做。** 记录理由与将来若要做的前置条件，避免重复评估。

#### 关键事实：AI 对话目前完全没有通知

本仓库 `ui/chat/` 下唯一的通知代码是 `ChatFloatWindowService.kt`，那是**悬浮窗的前台服务保活通知**（渠道 `vflow_chat_float`、ID 97010、`IMPORTANCE_LOW`，文案固定为「AI 对话悬浮窗运行中」）。它**与对话状态无关**——不显示回复进度、不随对话开始/结束更新。

因此 S3/S4 与 S1/S2 的**性质不同**：

| | S1/S2 工作流执行 | S3/S4 AI 对话 |
|---|---|---|
| 现有通知 | ✅ 有（`ExecutionNotificationManager`，含活体分支） | ❌ **完全没有** |
| 改造性质 | **增强**——给已有通知加岛参数 | **新建**——从零造一套通知体系 |
| 新增文件 | 仅装配层 | 装配层 **+ 独立的对话通知 Manager** |
| 渠道 | 复用现有的 | **须新建**（渠道重要性一旦创建不可改） |

**即 S3/S4 的工作量不是"加岛参数"，而是"先造一条通知，再让它上岛"**——成本显著高于 S1/S2。

#### 本次不做的三个理由

1. **需求未验证**：AI 对话的后台运行能力**未经真机验证**。`ChatViewModel` 虽然已被 `ChatViewModelHolder` 提升到 Application 作用域（fork 的悬浮窗改造），但当 App 退到后台、进程仅靠 `TriggerService` 保活时，Agent 是否真能跑完，尚无实测结论。**若 Agent 离开前台就中断，则岛通知失去意义**——用户根本不会离开 App。
2. **S1/S2 已覆盖最典型场景**：工作流是"用户配置好、希望它自动跑、跑完想被通知"的形态，与岛的能力最匹配。这是本项目的主价值场景。
3. **控制范围**：先交付 S1/S2，验证「vFlow 在岛上能否正常显示」这一核心假设，再决定是否扩展到 AI 对话。

#### 将来若要做的前置条件

1. **先验证 AI 对话的后台运行能力**（真机：发消息后按 Home，看 Agent 是否继续到完成）；
2. 若验证通过，需要新建独立的对话通知渠道，并按 mindfs 的形态设计（「回复中（带秒表）」→「已完成」/「需要你确认」，后者高亮 + 自动浮出）；
3. 复用本次建好的 `services/island/*` 装配层——**这是本次改造的附带价值：装配层设计成通用的，S3/S4 将来只需接入，不必重做**。

---

## 3. 架构设计

### 3.1 分层

```
┌─ 业务层（现有调用点，改动最小）─────────────────────┐
│  WorkflowExecutor  → ExecutionNotificationManager   │
│  （S3/S4 已移出范围，见 §2.3）                       │
└────────────────────────┬───────────────────────────┘
                         │ 只传业务语义（状态 + 文案 + 进度）
                         ▼
┌─ 装配层（新）────────────────────────────────────┐
│  IslandNotificationDispatcher                    │
│   ├─ IslandCapability   能力探测 + 缓存           │
│   ├─ IslandParamsBuilder  JSON 参数装配           │
│   └─ IslandIcons         图片 Bundle 装配          │
└────────────────────────┬───────────────────────────┘
                         │ extras
                         ▼
                  NotificationManager.notify()
```

### 3.2 核心接口（建议）

业务侧只提交一个中立的**状态对象**，不碰任何 `miui.*` 字符串：

```kotlin
/** 业务侧提交的通知语义。与厂商无关。 */
data class IslandNotificationSpec(
    val id: Int,                          // 通知 ID（调用方负责唯一化）
    val channelId: String,
    val state: State,                     // 见下
    val title: String,                    // 主标题（工作流名 / 会话名）
    val subtitle: String? = null,         // 副标题（项目名 / 步骤名）
    val content: String? = null,          // 正文 / 摘要
    val progress: Int? = null,            // 0..100，null 表示不显示进度
    val statusLabel: String? = null,      // 岛上的短标签（「执行中」「已完成」「需要确认」）
    val highlight: Boolean = false,       // 是否用强调色（等待用户操作时置真）
    val chronometerBase: Long? = null,    // 秒表基准，null 表示不显示计时
    val actions: List<IslandAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
) {
    enum class State { RUNNING, WAITING_USER, COMPLETED, FAILED, CANCELLED }
}
```

装配器对外只暴露两个方法：

```kotlin
object IslandNotificationDispatcher {
    /** App 启动时调用一次，异步探测能力。 */
    fun initialize(context: Context)

    /**
     * 构建通知。内部决定岛 / 焦点通知 / 普通通知三条路径。
     * @param builder 调用方已配好的 NotificationCompat.Builder（渠道、图标、文案）
     * @param spec    业务语义
     */
    fun dispatch(
        context: Context,
        builder: NotificationCompat.Builder,
        spec: IslandNotificationSpec,
    ): Notification
}
```

> **关键设计点**：`dispatch` 接收调用方**已经构建好的 builder**，只负责「附加参数后 build」。这样即使岛能力不可用，通知本身也完全正常——**降级路径零成本，不需要业务侧写任何 if**。

### 3.3 参数装配示意（S1 工作流执行中，走模板路径）

```jsonc
// miui.focus.param
{
  "param_v2": {
    "business": "vflow_workflow",
    "isShowNotification": true,
    "updatable": true,           // 持续性通知 → 岛不随更新重新弹出
    "enableFloat": false,        // 执行中不自动展开（避免每步都弹，骚扰）
    "islandFirstFloat": false,
    "timeout": 720,
    "ticker": "正在执行：每日签到",
    "tickerPic": "miui.focus.pic_app",
    "aodTitle": "正在执行：每日签到",
    "aodPic": "miui.focus.pic_app",
    "param_island": {
      "islandProperty": 1,       // 1=信息展示为主
      "islandTimeout": 3600,
      "highlightColor": "#4A90D9",
      "bigIslandArea": {
        "imageTextInfoLeft": {
          "type": 1,
          "picInfo": { "type": 1, "pic": "miui.focus.pic_app" },
          "textInfo": { "title": "每日签到" }
        },
        "textInfo": { "title": "步骤 3/8", "showHighlightColor": false }
      },
      "smallIslandArea": {
        "picInfo": { "type": 1, "pic": "miui.focus.pic_app" }
      }
    }
  }
}
```

配套的 images Bundle：

```kotlin
val pics = Bundle().apply {
    putParcelable("miui.focus.pic_app", roundedAppIcon(context))  // 圆形应用图标
}
notification.extras.putBundle("miui.focus.pics", pics)
```

> ⚠️ 上面是**按官方文档与模板库拼的最小可用样例**，字段名已核对。**§1.4 已确认无需探针**——可把 mindfs `FocusIslandSupport.buildParams`（`param.custom` 路径）与模板路径按 §1.9 的模板库结构逐项对齐，实现时以模板库字段表为准。

---

## 3.4 通知样式的具体设计

本节是 **S1 / S2（工作流执行）** 的视觉设计。基于三份既有资产：

- **vFlow 的配色**：`res/values/colors.xml` 的绿色系主色（浅色 `md_theme_light_primary = #3B6939`、深色 `md_theme_dark_primary = #A1D39A`、容器色 `#BCF0B4`）；
- **vFlow 的图标**：`R.drawable.ic_workflows`（四宫格，语义正是「工作流」，比 launcher 图标更贴切）；
- **mindfs 的排版经验**：大岛「应用图标 + 主文本 + 状态词」的三段式（`FocusIslandSupport.java:412-421`）、小岛「仅图标」（`:420-421`）、展开态「头部图标 + 双行标题 + 状态胶囊」（`island_expand.xml:24-79`）。

> 容器的几何约束（模板库 `:714`）：**大岛以摄像头为中心分 A、B 两区，A 区 2 种组件、B 区 9 种，共 9 种组合**。A 区恒为「图文组件1」（图标 + 大/小字），所以**左图标 + 右文本**是固定骨架。

### 3.4.1 视觉骨架

```
摘要态（大岛）
┌──────────────────────────────────────┐
│ [◯vF]  每日签到        步骤 3/8       │
│  ↑        ↑              ↑           │
│ A区图标  A区大字      B区大字（可选强调色）│
└──────────────────────────────────────┘

小岛（胶囊）
┌──────────┐
│  [◯vF]   │   ← 仅图标；进度环可选
└──────────┘

展开态（点岛后，模板路径）
┌──────────────────────────────────────┐
│ [◯vF]  每日签到                       │
│        步骤 3/8: 打开应用              │
│ ──────────────────────────────────── │
│  正在执行                    [结束]    │
└──────────────────────────────────────┘
```

### 3.4.2 状态 × 样式对照表

这是本节的核心。**同一套骨架，靠 4 个变量切换状态**：B 区文本、强调色、`enableFloat`、`islandTimeout`。

| 状态 | A区（左） | B区（右） | 强调色 | 自动浮出 | 岛超时 | 通知行为 |
|---|---|---|---|---|---|---|
| **执行中** | `[vF]` + 工作流名 | `3/8` | 无（灰） | ❌ 否 | 3600s | `setOngoing(true)`，静默更新 |
| **已完成** | `[✓]` + 工作流名 | `已完成` | **绿色高亮** | ✅ 是 | 900s | `setAutoCancel`，3s 后取消 |
| **失败** | `[✕]` + 工作流名 | `失败` | **红色高亮** | ✅ 是 | **1800s** | **不取消**，留 30 分钟（Q21） |
| **已取消** | `[✕]` + 工作流名 | `已停止` | 无 | ❌ 否 | 900s | 3s 后取消 |
| **超时** | `[✕]` + 工作流名 | `执行超时` | **红色高亮** | ✅ 是 | **1800s** | **不取消**，留 30 分钟（Q21） |

设计要点：

1. **只有「需要用户注意」的状态才自动浮出**（`enableFloat = true`）。执行中每步更新都浮出会变成骚扰——mindfs 在代码注释里明确踩过这个坑（`FocusIslandSupport.java:444-446`：*"回复中每 5s 轮询更新一次，自动展开会变成骚扰；完成与等待输入的那次更新要展开提示"*）。
2. **失败/超时不做 3 秒取消**（Q21）——否则通知一没、岛大概率同步消失，Q3=B 的「常驻 30 分钟」就形同虚设（见 §8.4）。
3. **A 区图标承载状态**：`ic_workflows` → 完成时换 `rounded_save_24` → 失败/取消换 `rounded_close_small_24`。**这三个 drawable 项目里已经存在**，与现有 `ExecutionNotificationManager` 的做法一致（`:133`、`:144`）。
4. **`narrowFont` 只用于数字位**：`3/8` 这类会跳字宽的文本，置 `true` 避免数字变化时整行抖动。
5. **不做耗时展示**（Q25）、**不做进度条**（§6.6）、**小岛仅图标**（§6.6）。

> **原「等待用户」状态已移除**：那是为 AI 对话审批（S3/S4）预留的，S3/S4 已移出范围（§2.3）。将来若做，此表再加回。

### 3.4.2.1 「结束」按钮

沿用现有实现（`ExecutionNotificationManager.kt:117-121`）：

- 图标 `R.drawable.rounded_close_small_24`，文案「结束」
- `PendingIntent.getBroadcast` → `WorkflowActionReceiver` 的 `ACTION_STOP_WORKFLOW` + `EXTRA_WORKFLOW_ID`
- **语义注意**：`stopExecution(workflowId)` 会取消该工作流**全部并发实例**（`WorkflowExecutor.kt:119-123` 对 List 里每个 job 都 cancel）。这是现状语义，本次不改——与 Q17 选 (a) 的「同工作流并行实例合并为一个岛」正好自洽。

### 3.4.3 图片资源设计

岛上的图标一律走 `miui.focus.pics`（Bundle 传 `Icon`）+ JSON 引用（`{"type":1,"pic":"miui.focus.pic_xxx"}`）。

| key | 内容 | 说明 |
|---|---|---|
| `vflow.pic.app` | **圆形应用图标** | 必须自己裁圆——launcher 图标在 API 26+ 是自适应图标 XML，直接塞给 SystemUI 会渲染成**方形**（mindfs `FocusIslandSupport.java:554-579` 专门写了 `roundAppIcon` 处理）。**复用这个思路。** |
| `vflow.pic.workflow` | `ic_workflows` 状态图标 | 按状态取 `ic_workflows` / `rounded_save_24` / `rounded_close_small_24` |

> **为什么不用 launcher 图标做 A 区？** launcher 图标是品牌标识（vFlow 的图形），而 `ic_workflows` 是功能语义（"这是个工作流"）。岛的空间里**功能语义更有信息量**，且与通知小图标一致。品牌标识通过应用名（`ticker` 里的「vFlow」）体现。

**为什么不用动态图标**：mindfs 试过动画 WebP 星芒呼吸动画，实机确认 **SystemUI 对 `miui.focus.pics` 里的动画 drawable 只取静态首帧**（其 FORK.md:39 记录，RemoteViews 与模板 picInfo 两个通道都不播放），最后放弃并改用静态图标。**我们直接不做动态图标**，省一轮试错。

### 3.4.4 强调色取值

```
执行中（无强调）  ──  不用 highlightColor
正常/完成/等待    ──  #3B6939（vFlow 浅色主色）→ 深色模式 #A1D39A
失败/超时（红）   ──  #BA1A1A（vFlow error 色）→ 深色模式 #FFB4AB
```

**直接复用项目已有的 MD3 语义色**（`colors.xml`），不另造一套——岛上的绿与 App 内的绿保持一致。

> ⚠️ 注意明暗模式：`highlightColor` 是单个值，而 vFlow 有浅/深两套。浅色主色 `#3B6939` 在深色岛背景上偏暗。**建议取深色模式的值 `#A1D39A`（较亮的绿）**——岛背景恒为深色（mindfs 的展开态 `island_expand.xml:213` 注释明确"恒为深色背景"），所以应该用 **dark 主题色**。

### 3.4.5 文案

| 位置 | 文案 | 来源 |
|---|---|---|
| 大岛 A 区大字 | 工作流名 | `workflow.name` |
| 大岛 B 区大字 | `3/8` | **已定**——见 §6.6 |
| 大岛 B 区大字（终态） | `已完成` / `失败` / `已停止` / `执行超时` | 新增字符串资源 |
| 展开态第二行 | `步骤 3/8: 打开应用` | 现有 `progressMessage`（`WorkflowExecutor.kt:507`） |
| ticker（OS2 状态栏） | `vFlow · 每日签到` | 新增 |
| aodTitle（息屏） | `每日签到 · 步骤 3/8` | 新增 |

**必须补中/英/日三语文案**（`AGENTS.md` 的约定），与现有 `trigger_service_notification_*` 的风格一致。

### 3.4.6 不做的设计（明确排除）

| 想法 | 为什么不做 |
|---|---|
| 岛上的进度条（模板 5 的 `progressInfo`） | 需要 `picForward` / `picMiddle` / `picEnd` 等**一整套进度图形资源**（模板库 `:118-125`），而且现有百分比按顶层步骤算，遇到 If/Loop 会卡住不动。**先用文本 `3/8`，后续需要再加。** |
| 动态/动画图标 | 已证实 SystemUI 只取静态首帧（§3.4.3） |
| 工作流名过长时截断策略 | 模板没有对外开放截断控制，交给系统默认（`ellipsize=end`）。**需真机看效果**。 |
| 每个工作流一套配色 | 多个工作流并发时会有多个岛同屏（左右滑动切换），多色会造成视觉噪音；且工作流数量无上限，无法穷举。**统一用 vFlow 绿。** |

### 3.4.7 需真机确认的样式问题

1. **B 区文本宽度上限**——`步骤 3/8` 够短，但工作流名可能很长，看系统如何截断；
2. **A 区 `content` 字段**（后置小字）能否用于放耗时——模板允许，但大岛空间有限；
3. **浅色主色 vs 深色主色**在岛上的实际观感（§3.4.4 的判断需验证）；
4. **完成态 `setAutoCancel` 与岛超时的交互**——通知自动消失时岛是否同步消失。

---

## 4. 文件级改动计划

### 4.1 新增文件（首选，符合 fork 原则）

| 文件 | 职责 | 归属 |
|---|---|---|
| `services/island/IslandCapability.kt` | 三层能力探测 + 后台线程刷新 + 结果缓存 | 我方 |
| `services/island/IslandNotificationDispatcher.kt` | 对外唯一入口；降级决策 | 我方 |
| `services/island/IslandParamsBuilder.kt` | JSON 参数装配（模板路径 & 自定义路径） | 我方 |
| `services/island/IslandIcons.kt` | 圆形图标绘制 + `miui.focus.pics` Bundle 装配 | 我方 |
| `services/island/IslandNotificationSpec.kt` | 业务中立的状态数据类 | 我方 |
| `res/layout/island_expand.xml` | 展开态 RemoteViews（仅自定义路径需要） | 我方 |
| `test/.../IslandParamsBuilderTest.kt` | JSON 装配纯函数单测（**可测，不需要真机**） | 我方 |

### 4.2 JSON 用什么

项目已依赖 **Gson**（`ExecutionUIService.kt:21,58`、`DefineFunctionModule` 等在用），**直接用 Gson 构建**，不引 `org.json`（那是 Android 平台 API，与 mindfs 用 `JSONObject` 只是习惯差异）。

> `IslandParamsBuilder` 应设计成**纯函数**：输入 `IslandNotificationSpec`，输出 JSON 字符串。这样单测可以完整覆盖字段装配，把「真机只能验证渲染、不能验证装配」的问题拆开。

### 4.3 修改的上游文件（力争最少）

| 文件 | 改动 | 冲突归属 |
|---|---|---|
| `services/ExecutionNotificationManager.kt` | 在两个 `notificationManager.notify(...)` 前插入 `dispatch(...)` 调用；`initialize` 里加一次 `IslandNotificationDispatcher.initialize` | 手动合并 |
| `ui/main/MainActivity.kt` | 初始化岛能力探测（或挂在已有 `ExecutionNotificationManager.initialize` 之后） | 手动合并 |
| `services/WorkflowActionReceiver.kt` | 新增 action 常量与分支（**不改既有签名**） | 手动合并 |
| `AndroidManifest.xml` | 无需新增权限（岛能力用系统设置读取 + provider 调用，**不需要额外声明权限**） | —— |
| `res/values*/strings.xml` | 岛状态文案（中/英/日） | 手动合并 |

**`WorkflowExecutor.kt` 几乎不动**——这是刻意的：9 个通知调用点全在里面，而 `AGENTS.md` 明列它为高风险区。**只要 `ExecutionNotificationManager` 的对外签名不变，9 个调用点里有 8 个一行都不用改。**

> **唯一例外**：修通知 ID 冲突（R4）时，`cancelNotification()` 必须带上 workflowId，所以 `WorkflowExecutor.kt:328` 会有**一行改动**（`cancelNotification()` → `cancelNotification(workflow.id)`）。详见 §7.2 第 11 项的说明。这是本方案对执行器的全部侵入。

---

## 5. 分阶段实施

### 阶段 0（已取消）

原计划是「真机探针先行」，用于回答「澎湃 OS 是否已接管 AOSP 通知」。**§1.4 已给出否定答案，且 mindfs 实现已稳定运行，探针环节取消。** 实现时直接以 mindfs 的字段组合为金标准 + 模板库字段表核对。

### 阶段 1：S1 + S2 —— 工作流执行状态

- 新增 §4.1 全部文件。
- 接入 `ExecutionNotificationManager`（仅两处 `notify` 前加一行 + `initialize` 加一行）。
- **`setRequestPromotedOngoing(true)` 的既有逻辑保留不动**：按 §1.4，它与超级岛互不相干，是 API 36+ 非小米设备上的独立能力（见 §6.5）。
- **前置修复通知 ID 硬编码**（`ExecutionNotificationManager.kt:34` 的固定 1998，**详述见 §8.1**）——这一步是 S1 的前置，否则多工作流并发时岛状态会错乱。注意该修复含 `WorkflowExecutor.kt:328` 的 1 行改动。
- 验证门禁：`./gradlew test`（新增的装配器单测）+ 真机。

### 阶段 2（可选）：展开态自定义 RemoteViews

**原「阶段 2：S3+S4 AI 对话」已移出范围**（见 §2.3）。

只有在模板表达力不够时才做（Q12 已定为**本次不做**）。做的话要注意 §1.6 的互斥性与 §1.7 的白名单约束。

---

## 6. 决策台账（已定稿）

本节记录本设计经过逐条确认的全部决策（2026-09-15/16，共 26 项）。**实施时以此为准**，不再重新评估。

### 6.1 范围与形态

| # | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| Q1 | 验收标准 | **岛能出现 + 状态/进度正确**，样式细节后续迭代 | 先验证「vFlow 能否上岛」这个核心假设 |
| Q2 | 岛显示范围 | **全部工作流无条件上岛**（自动触发也上） | 不做时长门槛（项目无耗时统计，新建成本高）；不做「仅手动触发」 |
| Q3 | 失败/超时的岛 | **常驻一段时间后自动消失**（不永久常驻，也不跟其他状态一样瞬失） | 常驻会积压，瞬失会漏看 |
| Q5 | 能力不可用时 | **静默回退到普通通知**，不做任何引导提示 | 免打扰；代价是「支持但未授权」的用户不会被告知（见 §8.3） |
| Q6 | 能力门槛 | **`protocol >= 3`**（严格只做岛） | 需求字面是「适配超级岛」；OS2 路径本机无法验证 |
| Q7 | 用户开关 | **不加开关**——发通知时判断能力，有则走岛逻辑，无则静默降级 | 岛 extras 对非小米设备零影响，用户无理由需要关闭；也免去 7 处设置页改动 |
| Q10 | 「常驻一段时间」 | **失败/超时 = 1800s（30 分钟）** | 失败需要用户采取行动，给更长；终究会消失 |
| Q11 | 短工作流闪烁 | **不处理** | 现有普通通知本就有同样问题（`WorkflowExecutor.kt:189` 立即发通知），非岛引入 |
| Q12 | 本次范围 | **只做摘要态**，展开态用系统默认；**不做**自定义 RemoteViews | 最小成本让岛出现 |

> **Q8/Q9 作废**：Q7 定为不加开关后，开关的默认值与置灰表现均无意义。

### 6.2 视觉设计

| # | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| Q19 | 文案风格 | **摘要态极简**（A 区工作流名 + B 区 `3/8`）；**展开态信息丰富**（含当前模块名） | 摘要态空间有限；展开态是用户主动展开，此时才值得给全信息 |
| Q25 | 完成态耗时 | **不显示** | Q11 选了不处理闪烁，耗时记录就只剩展示价值，不值得为一行文案引入跨工作流共享的 Map（Q2=D 下并发是日常，多一处竞态） |

> 完整的状态 × 样式对照表见 **§3.4.2**。

### 6.3 交互

| # | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| Q13 | 岛上的按钮 | **只保留现有的「结束」按钮**，**不加「打开」按钮** | 岛按钮牵动 `WorkflowActionReceiver` 与新广播回路；「打开」的跳转成本意外地高（见 Q18） |
| Q22 | 通知/岛本体的点击 | **补 `contentIntent`，打开 APP**（`MainActivity.createAppLaunchIntent`） | 顺手补齐既有缺陷——**现状点击通知本体完全无反应**（两个 build 方法都没调 `setContentIntent`） |

> **Q18 作废**（Q13 去掉「打开」按钮后无跳转目标）。但记录其调查结论备查：跳转到**特定工作流**成本极高——`MainActivity` 无 `onNewIntent`、导航是 Navigation3 类型安全路由且**全仓库只有 `MainRoute.Main` 一个**、无带参路由先例、无「目标工作流」的外部可驱动状态。唯一低成本路径是直接用 `WorkflowEditorActivity` 的 `EXTRA_WORKFLOW_ID`，但语义是「打开编辑器」而非「看执行结果」。

### 6.4 技术实现

| # | 决策 | 结论 | 理由摘要 |
|---|---|---|---|
| Q4 | 通知 ID 并发 bug | **单独 commit 先修**，与岛改造解耦 | 既有 bug，独立可验证（看通知栏是否出现多条独立通知，不需岛参与）；必须是岛改造的前置，否则真机验收时无法归因 |
| Q17 | ID 派生维度 | **按 `workflowId` 派生**（不按 `executionInstanceId`） | 满足「多个工作流 → 多个岛」的需求；按实例派生需改 `updateState` 签名 → **推翻 `WorkflowExecutor.kt` 全部 9 个调用点**（高风险区）。代价：`ALLOW_PARALLEL` 下同工作流的并行实例仍合并为一个岛，记为已知限制 |
| Q24 | ID 派生公式 | **`100000 + abs(workflowId.hashCode()) % 50000`**，隔离到 `[100000, 150000)` | `hashCode()` 是任意 32 位整数（含负数），必然撞上现有 ID 空间（2 / 1001 / 1998 / 2002 / 3001 / 3002 / 97010 / 1000+）。取模一次即彻底隔离，无状态、无持久化负担 |
| Q21 | 失败态真正常驻 | **要**——失败时不执行「3 秒后取消」，让它留 30 分钟 | 否则通知 3 秒就没了，岛大概率同步消失，Q3=B 形同虚设 |
| Q23 | 能力探测时机 | **App 启动时异步探测一次 + 进程级缓存**；探测完成前发通知走普通路径 | `canShowFocus` 是耗时 provider 调用，必须后台执行。代价：刚启动就手动执行工作流的**首次不上岛**（概率低、可接受） |
| Q26 | 诊断日志 | **`DebugLogger.d` 最小记录**（探测结果 + extras 是否写入），**不记录值** | Q1=A 意味着必然要真机调试，需要最小可观测性；不记录值是因为**工作流名可能含用户隐私** |

### 6.5 与现有 AOSP 路径的关系（原决策 3）

`ExecutionNotificationManager.kt:112` 已在 API 36+ 用 AOSP Live Updates（`setRequestPromotedOngoing`）。加上岛之后是**三套并行机制**：

| 机制 | 触发条件 | 载体 | 生效范围 |
|---|---|---|---|
| AOSP Live Updates | API 36+ | `setRequestPromotedOngoing` | **非小米设备**（澎湃 OS 不接管，见 §1.4） |
| 小米超级岛 | 澎湃 OS + `protocol >= 3` + 有权限 | `miui.focus.param` extras | 澎湃 OS |
| 普通通知 | 其余 | 标准 API | 兜底 |

**§1.4 已确认三者互不相干**：澎湃 OS 不接管 AOSP 通知，所以 `setRequestPromotedOngoing` 与超级岛**不是竞争关系**——它们服务不同设备群。

**结论：两条路径并存，互不冲突。**

- 保留现有 `setRequestPromotedOngoing`——它是 API 36+ 非小米设备上唯一的活体通知能力，删了就退化。
- 岛的 extras 由装配器**附加**到同一条通知上——澎湃 OS 读它，其它设备忽略它（§1.3 的零影响降级）。
- **不需要写「检测到岛就走 A、否则走 B」的分支**：两条路径的参数写在同一个通知对象上，各设备各取所需。

> 唯一需留意的：同一通知同时带 AOSP 提升请求与岛 extras，在澎湃 OS 上是否产生副作用（如被拒发）。属真机验证项（§7.2 第 5 项），**不阻塞实施**——若真有冲突，只需在检测到岛能力时跳过 `setRequestPromotedOngoing`（一行条件判断）。

### 6.6 进度表达（原决策 2 的落点）

模板的进度是**一个 int**（`progressInfo.progress`）；vFlow 现有进度是 `(pc * 100) / steps.size`（`WorkflowExecutor.kt:506`），按**顶层步骤数**算、不感知 If/Loop 内部步骤。

**结论：大岛 B 区用文本 `3/8`**（而非百分比或进度条）。理由：

1. 语义与现有 `progressMessage`（`WorkflowExecutor.kt:507` 的 `"步骤 ${pc+1}/${steps.size}"`）一致，**改动最小**；
2. 绕开「百分比不精确」——长工作流里 If/Loop 内部跑很多步时百分比会卡住不动；
3. **不做进度条**：模板 5 的 `progressInfo` 需要 `picForward`/`picMiddle`/`picEnd` 一整套图形资源，本阶段不值得。

**小岛仅显示图标**（不做环形进度）——与 Q12「只做摘要态」的基调一致，减少需验证的字段。

### 6.7 展开态改 RemoteViews（2026-09-17 追加决策）

**背景**：工作流执行时通知更新频繁（每推进一步一次）。RemoteViews 的**复用实例、只传变化字段**机制比每次都传完整参数包更省。同时展开态此前没有设计（Q12 定为「用系统默认」），本次一并补齐。

#### 决策：双路径并存，不做二选一

依据 §1.6 的修订结论（`miui.focus.rv` 只接管展开态、不影响 `param_island`），改为**同时提供两套参数**：

| extras key | 内容 | 作用 |
|---|---|---|
| `miui.focus.param` | 现有 `param_v2` 结构 | 大岛 / 小岛 / 通知栏卡片（**不变**） |
| `miui.focus.param.custom` | 扁平结构 + `param_island` | 配合 rv 使用；岛数据照常传递 |
| `miui.focus.rv` | **浅色** RemoteViews | 通知栏浅色；也是 `createCustomView` 入口，**必须给** |
| `miui.focus.rvNight` | **深色** RemoteViews | 通知栏深色 |
| `miui.focus.rv.island.expand` | 恒深色 | 岛展开卡片 |
| `miui.focus.rv.tiny` | 恒深色 | 状态栏紧凑胶囊（缺省会回落到 `miui.focus.rv`，整张卡片塞进胶囊会压变形） |

#### 为什么需要浅色版本

**这套 RemoteViews 不只用于岛展开态**——它被通知栏与锁屏预览复用，而那两处**有浅色模式**。岛展开态本身恒为深色，但同一份布局必须以两种配色交付。色值取自 vFlow 既有的 `colors.xml`（`md_theme_*`），见设计稿。

#### 展开态视觉方案（方案 3 · 进度主导）

```
[◯] 每日签到                    [执行中]   ← 图标 + 工作流名 + 状态胶囊
3/8  ██████░░░░░░░░░░░░░░░░              ← 大号进度 + 进度条
     正在执行：打开应用                    ← 当前模块
00:42 已运行                       [结束]  ← 耗时 + 操作
```

- 设计稿（可交互，含浅/深双配色 + 全部状态）：`docs/fork/island-expand-ui.html`
- 终态差异仅在状态胶囊颜色、图标色、底部按钮（终态隐藏「结束」）

#### 实现约束（**必须遵守**）

1. **复用 `RemoteViews` 实例**，只调 `setTextViewText` / `setProgress` 改变化字段——**不要每次 `new RemoteViews`**。后者要重新 inflate + 传整棵树，比模板还贵，会把本改造的收益抹掉。这是本决策成立的前提。
2. **每次更新只改 3 个字段**：大号进度、进度条宽度、当前模块名。标题/图标/胶囊在 `RUNNING` 期间不变。
3. **`Chronometer` 承载计时**（系统自走），只需传一次 `SystemClock.elapsedRealtime()` 基准，**不需要为计时重发通知**。
4. 遵守 RemoteViews 白名单（§1.7）：`LinearLayout` / `FrameLayout` / `RelativeLayout` / `ImageView` / `TextView` / `Chronometer` / `ProgressBar`。
5. 根容器不设背景（卡片底由 SystemUI 绘制）。

> **对 Q25 的影响**：Q25 曾定「不做耗时展示」——理由是需新建启动时刻 Map 而只服务一行文案。改用 `Chronometer` 后该成本降为「传一个 long」，故**改为展示耗时**。

---

## 7. 验证方案

### 7.1 单元测试（可离线）

`IslandParamsBuilder` 是纯函数，可完整单测：

- 各 `State` 枚举产出正确的 `business` / `updatable` / `enableFloat` 组合；
- `progress` 为 null 时**不**出现 `progressInfo`（避免岛渲染空进度条）；
- `highlight = true` 时 `showHighlightColor` 与 `highlightColor` 同时置真；
- JSON 结构与模板库字段名逐项对齐（可用模板库的样例 JSON 做金标准比对）；
- **能力不可用时 `dispatch` 返回的通知与不调用它时完全一致**（降级等价性，这条最重要）。

### 7.2 真机验证（不可替代）

参考 `docs/fork/chat-float-window-design.md:618` 的既有基线：**Xiaomi MIX Fold 3 / Android 17 / API 37 / HyperOS V816**。

对照检查项：

| # | 验证点 | 通过标准 |
|---|---|---|
| 1 | 能力探测 | 三个探测值符合设备实际 |
| 2 | 岛是否出现 | 执行工作流时状态栏出现岛 |
| 3 | 大岛内容 | 图标 + 标题 + 状态文案正确 |
| 4 | 进度表达 | 按决策 2 的结论 |
| 5 | **岛 extras 与 AOSP 提升共存** | 同一通知同时带 `miui.focus.param` 与 `setRequestPromotedOngoing` 时，澎湃 OS 上岛正常显示、通知不被拒发（否则按决策 3 的兜底加条件判断） |
| 6 | 更新行为 | 每步更新时不反复弹开（`enableFloat=false` 生效） |
| 7 | 完成态 | 转为「已完成」，超时后按 `islandTimeout` 消失 |
| 8 | 光效 | 按需验证（`miui.effect.*` 三处 key 的实际生效情况） |
| 9 | **非小米设备降级** | 用任意非小米设备/模拟器验证：通知与改造前**完全一致** |
| 10 | **无焦点权限降级** | 关闭应用的「焦点通知」权限后，通知仍正常显示为普通通知 |
| 11 | 多工作流并发 | 两工作流同时执行，**两条通知各自独立**、岛状态不串（依赖 R4 的前置修复，见 §8.1） |

### 7.3 日志与诊断

直接借鉴 mindfs 的做法（`FocusIslandSupport.java:224-262`）：

- **记录键名 / 长度 / Parcelable 类型，不记录值**——避免工作流名、AI 对话内容进 logcat。
- 记录关键节点：能力探测结果、`attach` 前后 extras 的 key 集合、最终 `notify` 是否成功。
- 这个诊断日志在阶段 0 是**必需的**（否则黑盒）；正式实现里可保留为 `DebugLogger.d` 级别。

---

## 8. 风险清单

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| R1 | 字段组合细节仍需真机调（官方文档只给字段说明） | 岛显示不完整 | 以 **mindfs 的实现为金标准**（已稳定运行）；模板库字段表逐项核对 |
| R2 | ~~与 AOSP Live Updates 打架~~ | ~~岛不出现~~ | **已消除**（§1.4：两者互不相干，见决策 3） |
| R3 | **焦点通知权限是应用级、用户可关** | 静默降级 | 检测 `canShowFocus`；必要时在设置页加入口引导（参考 chat-float 的 `HyperOS 拦截 overlay` 处理思路，`chat-float-window-design.md:820`） |
| **R4** | **通知 ID 硬编码导致并发互相覆盖 + 取消误伤**（**既有 bug，非岛引入**） | **通知栏错乱 / 通知凭空消失** | **阶段 1 前置修复**，详见 §8.1 |
| R5 | ~~`miui.focus.rv` 设错导致模板作废~~ | ~~岛完全不显示~~ | **已消除**（§1.6 修订：该 key 只接管展开态，`param_island` 照常生效）。改走双路径方案，见 §6.7 |
| R6 | **RemoteViews 白名单踩坑**（本次不做，无需关注） | 展开态空白 | 严格遵守 §1.7 白名单；根容器不设背景 |
| R7 | **厂商行为随版本漂移** | 升级后失效 | 能力探测做在运行时（不写死版本号）；文档记录基线设备 |
| R8 | **岛 extras 与 `setRequestPromotedOngoing` 同存的副作用** | 澎汓 OS 上可能被系统拒发 | 真机验证（§7.2 第 5 项）；若有冲突，加一行「有岛能力则跳过 AOSP 提升」 |

> 原「项目可能整个白做」的风险（AOSP 路径已被接管）**已由 §1.4 消除**。当前最大的实际风险是 **R4**，但它是已定位、可修的既有 bug，不构成方向性障碍。

### 8.1 R4 详述：通知 ID 硬编码的并发问题（**既有 bug，非岛引入**）

**这不是超级岛带来的问题，而是现在就存在、只是不易察觉的缺陷。** 做岛会把它从"偶尔跳一下"放大成"岛状态明显错乱"。

#### 机制

`ExecutionNotificationManager` 是单例 object，通知 ID 硬编码（`ExecutionNotificationManager.kt:34`）：

```kotlin
private const val NOTIFICATION_ID = 1998 // 使用一个固定的ID
```

所有工作流的所有状态更新，最终都落到同一个 `notificationManager.notify(1998, ...)`（`:147`、`:181`）。而 Android 的 `notify(id, n)` 语义是**同 ID 覆盖**，不是追加。

#### 后果一：状态互相覆盖

```
工作流A 开始    → notify(1998, "A 正在开始")     → 通知栏：A
工作流B 开始    → notify(1998, "B 正在开始")     → 通知栏：B   ← A 被覆盖
工作流A 步骤推进 → notify(1998, "A 步骤 2/3")    → 通知栏：A   ← B 被覆盖
工作流B 完成    → notify(1998, "B 执行完毕")     → 通知栏：B   ← A 又被覆盖
```

用户看到标题与进度在**两个工作流之间反复跳变**。

#### 后果二：取消误伤（更严重）

`WorkflowExecutor.kt:326-328`：

```kotlin
// 延迟后取消通知，给用户时间查看最终状态
delay(3000)
ExecutionNotificationManager.cancelNotification()
```

`cancelNotification()` 是 `notificationManager.cancel(NOTIFICATION_ID)`（`:188-190`），**不带任何工作流维度**。

于是：**A 执行完毕 → 3 秒后把 ID 1998 整个取消掉 —— 而此时 B 还在跑。** B 的通知凭空消失，要等到 B 下一次 `updateState`（下一个步骤推进）才会重新出现。若 B 正卡在一个耗时步骤（如等待网络、等待元素出现），这段时间它**完全没有通知**，用户会以为工作流已经结束。

#### 触发条件：并发是常态，不是边缘情况

`WorkflowExecutor.execute()` 有 `reentryBehavior` 门禁（`:138-158`），但它**只约束同一个 workflowId**——`runningWorkflows` 是 `ConcurrentHashMap<String, MutableList<ActiveExecution>>`，按工作流分组（`:53`）：

| 场景 | 是否并发 |
|---|---|
| **两个不同工作流各自被触发**（如「每日签到」定时 + 「到家开灯」通知触发同时命中） | ✅ **并发**——各自的 `isRunning` 都是 false，门禁完全不拦 |
| 同一工作流 + `BLOCK_NEW` | ❌ 忽略新请求 |
| 同一工作流 + `STOP_CURRENT_AND_RUN_NEW` | ❌ 停旧的跑新的 |
| 同一工作流 + `ALLOW_PARALLEL` | ✅ 并发（`:153-157`） |

第一行是最普通的日常场景。**所以这个问题随时会发生。**

#### 修复方案

**阶段 1 前置**（在接入岛之前完成，否则岛上的表现会先一步错乱）：

1. `updateState` / `cancelNotification` 增加 workflow 维度参数；
2. 通知 ID 改为**按工作流派生**：`100000 + abs(workflow.id.hashCode()) % 50000`（Q24），隔离到 `[100000, 150000)` 区间，避开现有全部通知 ID；
3. `WorkflowExecutor.kt:328` 改为 `cancelNotification(workflow.id)`；
4. 补充单测：两个 workflowId 产出不同通知 ID、取消 A 不影响 B 的 ID。

### 8.2 R5 详述：失败态的 3 秒取消冲突（**本次改造引入**）

§3.4.2 定下的「失败/超时常驻 30 分钟」（Q3=B / Q10 / Q21）与现有代码**直接冲突**：

```kotlin
// WorkflowExecutor.kt:326-328 —— finally 块内，无条件执行
delay(3000)
ExecutionNotificationManager.cancelNotification()
```

这段代码在**所有**终态路径后都跑，包括失败。所以必须加失败分支：

```kotlin
// 仅失败/超时保留通知，其余维持 3 秒取消
if (!isFailure) {
    delay(3000)
    ExecutionNotificationManager.cancelNotification(workflow.id)
}
```

**这是本方案对 `WorkflowExecutor.kt` 的第二处改动**（第一处是 `cancelNotification` 带参）。两处都在 `:326-328` 这个局部，合计改动仍很小，但 §9 原先写的"仅 1 行"需修正为"两处、均在 `:326-328`"。

> 另注：失败时执行器**并未使用独立的失败状态**——它复用 `ExecutionNotificationState.Cancelled`（`WorkflowExecutor.kt:645`：`Cancelled("失败: ${result.errorMessage}")`）。而 §3.4.2 要求失败态有**红色高亮 + 30 分钟常驻**，与「已停止」完全不同。因此需要**新增一个 `Failure` 状态**（或等价的区分手段）到 `ExecutionNotificationState`。这是对 `ExecutionNotificationManager` 的内部改动，不影响调用点签名。

### 8.3 Q5（静默降级）的已知代价

Q5 定为「能力不可用时静默回退、不做任何提示」。这带来一个**用户可感知的缺口**：

> **澎湃 OS3 用户，若未在系统设置里开启 vFlow 的「焦点通知」权限，岛不会显示，且我们不会告诉他为什么。**

这个权限是**逐应用的、默认关闭的**，且与 `POST_NOTIFICATIONS` 完全无关——用户在常规权限页找不到它。后果是用户可能看到别人的 vFlow 有岛、自己的没有。

**当前决策：接受这个代价**（避免打扰 + 免去设置页 7 处改动）。记录在此，若将来这类困惑变多，回头补一个引导入口即可。

### 8.4 Q17（按 workflowId 派生）的已知限制

Q17 选 (a)：通知 ID 按 `workflowId` 派生。因此：

> **同一工作流在 `ALLOW_PARALLEL` 模式下并发执行多个实例时，这些实例共用同一个通知 ID，仍会互相覆盖，最终只呈现一个通知/一个岛。**

触发条件很窄：`reentryBehavior` 默认 `BLOCK_NEW`、AI 创建的工作流是 `STOP_CURRENT_AND_RUN_NEW`（会先停旧的），只有**用户显式选择 `ALLOW_PARALLEL`** 才可能触发。

**当前决策：接受，记为已知限制。** 彻底解决需按 `executionInstanceId` 派生 ID，而那要求改 `updateState` 签名 → 改动 `WorkflowExecutor.kt` 全部 9 个调用点（高风险区），收益与成本不匹配。

> 与 §3.4.2.1 的「结束」按钮语义自洽：`stopExecution(workflowId)` 本就取消该工作流的全部实例，与「一个工作流一个岛」一致。

---

## 9. 与 fork 规范的对齐

- **控制 diff 面积**：核心逻辑全在新文件 `services/island/*`；上游文件只加「一行调用 + 一行初始化」。
- **最小侵入高风险区**：`WorkflowExecutor.kt` 共 **2 处改动，均在 `:326-328` 局部**——① `cancelNotification()` 带 workflowId（§8.1）；② 失败态跳过 3 秒取消（§8.2）。**其余调用点（含 9 个 `updateState` 与 8 个其余调用点）全部不动**，因为 `updateState` 的对外签名保持不变。若要把第 ① 处也省掉，可让 `ExecutionNotificationManager` 内部自行追踪「哪个 workflowId 还在执行」——但会引入跨模块状态同步，得不偿失。
- **模块化**：若后续要把「岛通知」做成用户可编排的模块（如 `vflow.notification.island`），按 `AGENTS.md` 的姿势新增模块 + 在 `ModuleRegistry.kt` **追加**注册。
- **FORK.md 登记**：本方案落地后，需在 `FORK.md` 的「与上游的分歧清单」新增条目（新增文件归我方；`ExecutionNotificationManager.kt` / `WorkflowActionReceiver.kt` 属手动合并）。
- **文档索引**：落地后更新 `docs/fork/surveys/README.md` 的「现有文档」表，或在 `docs/fork/` 下与本文件并列。

---

## 10. 待确认问题

**无。** 全部 26 项决策已于 2026-09-15/16 逐条确认并记入 **§6 决策台账**。实施时以 §6 为准。

以下原列问题均已裁定，保留索引备查：

| 原问题 | 结论 | 位置 |
|---|---|---|
| S1/S2 岛文案风格 | 摘要态极简 / 展开态丰富 | Q19 · §6.2 |
| 多工作流并发时岛只有一个？ | **前提有误**——岛可多个，左右滑动切换；按 workflowId 派生 ID 即可各自成岛 | Q17 · §6.4 |
| 岛上是否加操作按钮 | 只保留现有「结束」，**不加「打开」** | Q13 · §6.3 |
| 自定义展开态是否在范围内 | 不在 | Q12 · §6.1 |
| S3/S4（AI 对话上岛） | 不做 | §2.3 |

---

## 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-15 | 首版。基于官方接入文档 + 官方模板库 + mindfs 已落地实现，给出分层架构、文件级改动计划、分阶段实施、三个待决策项与风险清单 |
| v1.1 | 2026-09-15 | 依据用户确认更新：① 新增 §1.4「澎湃 OS 不接管应用通知，必须应用自己适配」（**推翻原假设**，消除最大项目风险 R8）；② **取消阶段 0 真机探针**——mindfs 已稳定运行，字段组合可直接复用；③ 重写决策 3——AOSP Live Updates 与超级岛**互不相干、并存不冲突**，不再需要二选一；④ 风险清单 R2 消除、R8 换为「岛 extras 与 AOSP 提升同存的副作用」；⑤ 明确 **R4（通知 ID 冲突）为阶段 1 的前置修复**；⑥ 章节编号顺延（原 §1.4–1.8 → §1.5–1.9） |
| v1.2 | 2026-09-15 | 新增 **§8.1「R4 详述」**：经代码核实，多工作流并发时通知**不会**各自独立——ID 硬编码 1998 导致状态互相覆盖，且 **A 完成后的 `cancelNotification()` 会误删仍在运行的 B 的通知**（`WorkflowExecutor.kt:326-328`）。补充触发条件（不同工作流并发时 `reentryBehavior` 门禁完全不拦，是日常场景）、修复方案四步、以及对执行器 1 行侵入的说明。定位为**既有 bug、非岛引入** |
| v1.3 | 2026-09-15 | 新增 **§3.4「通知样式的具体设计」**：基于 vFlow 现有配色（绿色系 MD3，`colors.xml`）与图标（`ic_workflows` / `rounded_save_24` / `rounded_close_small_24`）给出视觉骨架、**状态 × 样式对照表**（6 状态 × 4 变量）、图片资源设计（含圆形图标裁剪、**不做动态图标**的理由）、强调色取值（**应用深色主题色**，因岛背景恒为深色）、三语文案清单、明确排除的 4 项设计、4 项待真机确认 |
| v1.4 | 2026-09-15 | **S3/S4（AI 对话上岛）移出本次范围**（用户决定）。新增 **§2.3** 记录理由与将来若要做的前置条件：核实到 **AI 对话目前完全没有通知**（`ui/chat/` 下唯一通知是悬浮窗保活通知，与对话状态无关），故 S3/S4 的性质是「先造一条通知再上岛」而非「加岛参数」，成本显著高于 S1/S2；且 AI 对话的后台运行能力未经真机验证。同步清理：§2.1 范围表、§3.1 架构图、§5 阶段划分（原阶段 2 改为可选的展开态改造）、§6 决策 1、§10 待确认问题 |
| **v2.0** | **2026-09-16** | **设计定稿**。经 5 轮逐条评审（26 项决策）后，**§6 由「待决策」改写为「已定稿的决策台账」**，§10「待确认问题」清空。主要结论与变更：① **不加用户开关**（Q7）——发通知时判断能力即可，免去设置页 7 处改动；② 能力门槛定为 **`protocol >= 3`**（Q6）；③ 岛显示范围为**全部工作流无条件上岛**（Q2=D）；④ **只保留「结束」按钮，不加「打开」按钮**（Q13），但**给通知本体补 `contentIntent` 打开 APP**（Q22）——修正「现状点击通知本体完全无反应」的既有缺陷；⑤ 通知 ID 按 `workflowId` 派生，公式定为 **`100000 + abs(hash) % 50000`**（Q24），避开现有 ID 空间；⑥ 失败/超时常驻 **1800s** 且**不执行 3 秒取消**（Q3/Q10/Q21）；⑦ 能力探测为**启动时异步一次 + 进程缓存**（Q23）；⑧ 新增 **§8.2**（失败态 3 秒取消冲突，对 `WorkflowExecutor.kt` 的**第二处**改动）、**§8.3**（Q5 静默降级的代价）、**§8.4**（Q17 按 workflowId 派生的已知限制）；⑨ **修正 v1.0–v1.4 的错误表述**——原先多处写「岛是全局唯一展示位」，实际**多个工作流可同时有多个岛、左右滑动切换**；⑩ §9「最小侵入」表述由「仅 1 行」修正为「**2 处，均在 `:326-328` 局部**」 |
