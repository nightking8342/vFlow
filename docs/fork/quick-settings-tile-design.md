# 快捷设置磁贴（QS Tile）优化设计

**状态**：设计定稿，待实现 ｜ **日期**：2026-10-06
**上位背景**：用户 2026-10-05 提出，2026-10-06 补齐核心要点（见 §1）。
**相关**：`surveys/trigger-system-overview.md`（触发器体系）、`backup-webdav-design.md` §TileScope（备份语义）。
上游无此文件的对应物。

---

## 0. 一句话需求

磁贴现在只有一种：**执行型**，点一下就执行工作流，且**常亮**。
本设计做三件事：

1. **磁贴要显示它绑定的工作流的图标**（内置 Material Symbols 或用户选的照片）；
2. **执行型磁贴改成「不倒翁不高亮」的样子**（现在它常亮，因为状态取自 `isEnabled`）；
3. **新增 20 个「开关型」磁贴**，绑定**有 auto trigger 的工作流**，
   点一下 **开/关这条自动触发**（不是执行）。

---

## 1. 需求原文（用户 2026-10-06，逐字）

> 1. 状态磁贴要支持图标，也就是和它绑定的工作流的图标要一样。
>
> 2. 目前只有那种执行的（即没有 auto trigger 的）工作流的绑定，并且它的机制是常亮的状态栏，
>    因为它好像是根据 is enabled 这个字段来判断开和关状态的。虽然它即使不是一个开关，
>    这种也要改成官方推荐的样式，也就是没有高亮、只是可以点击的那种。
>
> 3. 我们要新增 20 个磁贴，用看的字段来区分。它是用来绑定那种有 auto trigger 的工作流的，
>    并且是有开关状态的。它的开关，也就是触发这个工作流的开关，而不是执行。

**补充口径（对话中确认）**：

> 「确实，我只区分：只要有自动化 trigger 的，就属于这种自动化的工作流；
> 没有 auto trigger 的，就属于执行一次的那种效果。」

⇒ **判据定案：`Workflow.hasAutoTriggers()`。** 两池互补、**互斥**、穷尽：

| 池 | 判据 | 手势 | 状态 |
|---|---|---|---|
| 执行型（原有 20 个） | `!hasAutoTriggers()` | 点 = **执行一次** | **恒不高亮**（§3） |
| 开关型（新增 20 个） | `hasAutoTriggers()` | 点 = **开/关自动触发** | 高亮 = 已启用 |

⚠️⚠️ **「互斥」是强制的，不只是 UI 选项**（用户 2026-10-06 补充）：

> 执行型磁贴不允许绑有 auto trigger 的工作流

⇒ 绑定时按判据**拒绝**（菜单项不显示 / 选择面板不列 / service 侧再兜一层，见 §4.7），
不是一个「两种都可以，只是推荐」的划分。**同一个工作流不会同时出现在两个池里。**

⚠️ 这与现有的 `hasManualTrigger()` 判据**不是一回事**，且现有代码里多处混用
（见 §2.4）—— 那正是要统一的地方。

---

## 2. 现状（已核查事实）

### 2.1 数据模型

```kotlin
// core/workflow/model/WorkflowTile.kt:6-14
@Parcelize
data class WorkflowTile(
    val tileIndex: Int,
    val workflowId: String? = null
) : Parcelable {
    companion object { const val TILE_COUNT = 20 }
}
```

- 存在 `SharedPreferences("vflow_tiles")` 的 `tile_list` 键，Gson 序列化（`TileManager.kt:8-13`）。
- `TileManager.saveTile` 按 `tileIndex` 去重；`removeTileByWorkflowId` 删掉该工作流的**全部**磁贴（`:57-61`）。
- 备份 scope：`core/backup/scopes/TileScope.kt`，按 `tileIndex` 做 MERGE，走**文本层** JSON 合并
  ⇒ KDoc 明写「天然容忍元素里将来新增的字段」⇒ **加字段不会破坏旧备份**。

### 2.2 Service 与 Manifest

`ui/tile/BaseWorkflowTileService.kt`（基类，129 行）+ `ui/tile/WorkflowTileServices.kt`
（`WorkflowTileService0..19`，每个只覆写 `getTileIndex()`）。

`AndroidManifest.xml:508-728` 是 20 个 `<service>`，**逐个手写**，每个都是：

```xml
<service android:name=".ui.tile.WorkflowTileService0" android:exported="true"
    android:icon="@drawable/ic_workflows" android:label="vFlow Tile 1"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE" /></intent-filter>
    <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true" />
</service>
```

已核实的四点：

| 事实 | 证据 |
|---|---|
| 20 个**全部**带 `TOGGLEABLE_TILE=true` | 20 处，逐行核对 |
| **全部**共用同一个图标 `@drawable/ic_workflows` | 同上 |
| **没有任何** `ACTIVE_TILE` 元数据 | 全仓 grep 零命中 |
| 标签统一 `vFlow Tile N`（无区分） | 同上 |

### 2.3 运行时行为（现状）

`BaseWorkflowTileService.kt`：

- `:49-68 updateTileState()` —— **只读 `isEnabled`，从不写**：
  ```kotlin
  qsTile.state = if (workflow != null && workflow.isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
  qsTile.label = workflow?.name ?: "Tile ${tileIndex + 1}"
  ```
  ⇒ **这就是用户看到的「常亮」**：默认启用的工作流永远 `STATE_ACTIVE`。
- `:70-107 executeWorkflow()` —— 只启动 `ShortcutExecutorActivity`，无写入路径。
- `:83-92` 第二道闸：`if (!workflow.hasManualTrigger())` ⇒ Toast「此工作流不是手动触发器，无法通过Tile执行」。
- 全文件**没有** `qsTile.icon =`、**没有** `requestListeningState()`（全仓均零命中）。

### 2.4 判据现况：三处不一致

| 位置 | 现用判据 | 语义是否对 |
|---|---|---|
| `WorkflowListScreen.kt:499`、`:1228`（「添加到主屏幕」菜单项） | `hasManualTrigger()` | ✅ 快捷方式确实只能执行手动型 |
| `WorkflowListScreen.kt:522`、`:1251`（「添加到控制中心」菜单项） | `hasManualTrigger()` | ❌ **要改成 `!hasAutoTriggers()`** |
| `BaseWorkflowTileService.kt:84`（执行闸） | `hasManualTrigger()` | ❌ 同上 |

⚠️ **「有 auto trigger 的工作流没有手动触发器」是错的** —— 编辑器新建工作流时默认就带一个
`vflow.trigger.manual`，用户之后再加自动触发器时**不会**把它删掉。所以实践中多数自动化工作流
**同时**有 manual + auto ⇒ 现在两处判据**碰巧都成立**，缺陷被掩盖着。
但 `WorkflowNormalizer.normalize`（`core/workflow/WorkflowNormalizer.kt:29-38`）在
**已有任意触发器**时**不会**补手动触发器 ⇒ Agent 建的、或从外部导入的纯自动工作流
**没有** manual trigger ⇒ 那类工作流现在的「添加到控制中心」菜单项**根本不显示**。

### 2.5 开关写入的正确姿势（已核实）

列表页的 `onToggleEnabled`（`WorkflowListRoute.kt:489-528`）是**唯一**正确范式：

```kotlin
val updatedWorkflow = workflow.copy(
    isEnabled = enabled,
    wasEnabledBeforePermissionsLost = false      // ← 必须清
)
workflowManager.saveWorkflow(updatedWorkflow)     // ← 内部会 notifyWorkflowChanged
loadData()
if (!enabled || !workflow.hasAutoTriggers()) return
scope.launch {                                    // 开启时异步补权限
    val remaining = TriggerExecutionCoordinator.recoverMissingPermissions(appContext, latest)
    if (remaining.isEmpty()) return@launch
    // 仍缺 ⇒ 回弹
    workflowManager.saveWorkflow(current.copy(isEnabled = false, wasEnabledBeforePermissionsLost = true))
}
```

两条**必须照抄**的原因：

1. **`wasEnabledBeforePermissionsLost = false`** —— 不清的话，
   `WorkflowPermissionRecovery.recoverEligibleWorkflows`（`core/workflow/WorkflowPermissionRecovery.kt:20-21`）
   filter 的是 `it.wasEnabledBeforePermissionsLost && !it.isEnabled` ⇒
   用户从磁贴**关掉**的工作流会被权限恢复**自动重新打开**，而用户不知道。
2. **`saveWorkflow` 而非直接写 prefs** —— `WorkflowManager.saveWorkflow`（`:117`）内部调
   `TriggerServiceProxy.notifyWorkflowChanged(context, new, old)`，
   那才是让 `TriggerService` 挂/卸触发器的入口。绕过它 = **界面变了但触发器还挂着**。

---

## 3. ⚠️ 一处必须纠正的认知（否则会做错）

用户的需求 2 原文：

> 这种也要改成官方推荐的样式，也就是**没有高亮、只是可以点击的那种**

**结论正确，但对「怎么做到」的归因需要纠正** —— 这直接决定改哪里。

### 3.1 `TOGGLEABLE_TILE` **不控制高亮**

| 事实 | 证据 |
|---|---|
| 官方文档对它的说明**只有无障碍**：「helps provide information about the behavior of the tile to the operating system and improve overall accessibility」 | [developer.android.com · Create a custom tile](https://developer.android.com/develop/ui/views/quicksettings-tiles) |
| AOSP 里它只影响**无障碍类名与 `value` 字段**：`newTileState()` 返回 `BooleanState`（否则普通 `State`），`handleUpdateState()` 据此设 `expandedAccessibilityClassName = Switch.class.getName()`（否则 `Button`） | AOSP `CustomTile.java`（`handleInitialize` / `newTileState` / `handleUpdateState`） |
| 该文件里**没有任何绘图调用**与 `isToggleableTile()` 相关 | 同上 |

⇒ **高亮与否完全由 `Tile.state` 决定**，与元数据无关。
⚠️ 我曾把设备控制的 `TYPE_STATELESS` 张冠李戴成磁贴元数据
（见 memory `quicksettings-tile-metadata-keys`）—— **不存在 `STATELESS_TILE`**。

### 3.2 所以两件事要分开做

| 目标 | 做法 |
|---|---|
| **视觉上不高亮** | 执行型磁贴**恒设 `Tile.STATE_INACTIVE`**（不再读 `isEnabled`） |
| **无障碍上不报成开关** | **去掉** `TOGGLEABLE_TILE` 元数据 |

两者都要做，但**理由不同、改的地方不同**。只做其中一件都会留下问题：
- 只改 state ⇒ 读屏仍把「执行一次」播报成「开关，已关闭」；
- 只去元数据 ⇒ 磁贴照样常亮。

反过来，**开关型磁贴恰恰两个都要保留**（有 state 双态 + 有 TOGGLEABLE_TILE）。

---

## 4. 设计

### 4.1 两池划分：**显式 `kind` 字段**（用户 2026-10-06 定案）

```kotlin
// core/workflow/model/WorkflowTile.kt
enum class TileKind { EXECUTE, TOGGLE }

@Parcelize
data class WorkflowTile(
    val tileIndex: Int,                                  // 0..39
    val workflowId: String? = null,
    val kind: TileKind = TileKind.EXECUTE,               // ← 新增，带默认值
) : Parcelable {
    companion object {
        const val EXECUTE_TILE_COUNT = 20
        const val TOGGLE_TILE_COUNT = 20
        const val TILE_COUNT = EXECUTE_TILE_COUNT + TOGGLE_TILE_COUNT   // 40
    }
}
```

**为什么是字段而不是索引区间**：语义写在数据里，不用记「0–19 是什么」；
且 `TileScope` 是文本层合并、`WorkflowTile` 走 Gson 反序列化 ——
**旧记录缺 `kind` 时会落默认值 `EXECUTE`**，零迁移。

⚠️ **`@Parcelize` 不能加字段？** 可以 —— 加的是**带默认值**的参数，
Kotlin 生成的 `CREATOR` 仍按新布局读写；本字段事实上只在进程内传（`TileSelectionItem`
替身），跨版本 Parcel 兼容不是问题。

⚠️ **`TILE_COUNT` 由 20 改 40 会改变三处行为**，逐处核对：

| 位置 | 影响 |
|---|---|
| `TileManager.getAllTilesWithEmpty()`（`:44-50`） | 返回 40 项 ⇒ 选择面板显示 40 行（**这是要的**） |
| `TileScope` KDoc 里「`TILE_COUNT = 20` 是槽位总数」 | 注释要改；合并逻辑按 `tileIndex` 走，**不受影响** |
| 备份 MERGE | 见上，文本层合并不看这个常量 |

### 4.2 槽位分配

| kind | tileIndex 区间 | 命名 |
|---|---|---|
| `EXECUTE` | 0–19 | `WorkflowTileService0..19`（**保持原名**） |
| `TOGGLE` | 20–39 | `WorkflowToggleTileService0..19` |

⚠️ **执行型的类名保持 `WorkflowTileServiceN` 不变** —— 改名会让**已添加到控制中心的磁贴
全部失效**（SystemUI 按 `ComponentName` 记住已添加的磁贴，改名 = 那个组件不存在了）。
新池必须是**新增**的类与**新增**的 Manifest 条目。

⚠️ `tileIndex` 与 service 的映射要显式写死（`EXECUTE_INDEX_OFFSET = 0` /
`TOGGLE_INDEX_OFFSET = 20`），**不要靠类的后缀数字去推** —— 两个池子各有 0..19，
只看后缀分不清是哪一池。

### 4.3 图标

**目标**：磁贴图标 = 它绑定的工作流的 `cardIconRes`。

`cardIconRes` 有三种形态（`core/workflow/WorkflowIconValue.kt`）：
内置资源名 / 绝对路径 / `file://`。三种的取法：

| 形态 | 磁贴图标的做法 | 注意 |
|---|---|---|
| 内置资源名 `rounded_home_24` | `Icon.createWithResource(context, resId)` | resId 走 `WorkflowVisuals.resolveIconDrawableResOrZero`（**可能返回 0**，见下） |
| 绝对路径 / `file://` | 解码 → 中心裁剪 → 缩放到目标尺寸 → `Icon.createWithBitmap` | 见下 |
| **未绑定工作流 / 解析失败** | `Icon.createWithResource(context, R.drawable.ic_workflows)` | 回落，**绝不能传 0** |

⚠️⚠️ **`resolveIconDrawableResOrZero` 返回值可能是 0**（老工作流存了已下线的图标名、
从别处导入的工作流）。`Icon.createWithResource` 传 0 **不抛异常**，但磁贴会显示空白
—— 与「图标坏了」不可区分。必须显式回落。

⚠️⚠️ **自定义图片必须缩放到小尺寸再交给 `Icon`** —— `Icon` 要跨 Binder 传给 SystemUI，
不缩放时一张 4K 照片的 Bitmap 会撞上 `TransactionTooLargeException`（与
`IslandRemoteViews` 那次崩溃同源）。目标尺寸取 **192px**（与 `ShortcutHelper` 一致）。

**解码逻辑复用**：`ui/common/ShortcutHelper.kt:147 loadCenterCroppedBitmap(filePath)`
已经做了「`inJustDecodeBounds` 探尺寸 → `inSampleSize` 降采样 → 中心裁剪成正方形 →
缩放到 192 → 逐级回收」。**抽到新文件复用，不复制**（本仓库记过双份实现的代价）。

落点：新增 `core/workflow/CardIconBitmap.kt`，把该函数移过去并放宽可见性；
`ShortcutHelper` 改为调用它。⚠️ 这是改上游文件（`ShortcutHelper.kt` 是上游的），需在 FORK.md 登记。

### 4.4 刷新时机（现在完全缺失）

**问题**：在 App 里改了工作流的图标 / 名字 / 启用状态，磁贴**不会**跟着变 ——
因为 `onStartListening()` 只在磁贴被绑定或下拉面板时触发，而
**「App 内改动」不通知 SystemUI**。

**做法**：App 侧写完后调 `TileService.requestListeningState(context, ComponentName)`，
强制该 service 走一次 `onStartListening()`。

⚠️ 这与 `ACTIVE_TILE` 元数据有关：
- **声明 `ACTIVE_TILE`** ⇒ 标准模式下系统不会主动绑定，**必须**靠 `requestListeningState`
  才会更新；好处是「即使磁贴不可见也能更新一次」、且不必每次下拉面板都绑一次。
- **不声明** ⇒ 每次下拉面板时都会绑一次并调 `onStartListening()`，刷新自然发生，
  但更新时机**不受我方控制**，且系统负担更大。

⇒ **两个池都加 `ACTIVE_TILE`**，并在 App 侧补齐 `requestListeningState` 的调用点。
⚠️ 若只加元数据不补调用点，表现是「进了 App 改了图标，退出后磁贴还是旧的」——
**静默、且看起来像系统缓存**（见 §7 第 4 条）。

**调用点清单**（枚举成一处 `TileRefreshNotifier.requestAll(context)`，避免各写各的）：

| 触发场景 | 位置 |
|---|---|
| 保存工作流（改名 / 改图标 / 改触发器） | `WorkflowManager.saveWorkflow` 尾部（**所有写入路径的汇聚点**） |
| 删除工作流 | `WorkflowManager.deleteWorkflow` 尾部 |
| 磁贴绑定 / 解绑 | `WorkflowListRoute` 的 `onAddToTile` 分支（`:665-700`） |
| 备份导入覆盖 | 导入流程尾部（`TileScope` 写入后） |

⚠️ **`requestListeningState` 必须在主线程调用**（官方签名是静态方法 + 需要 `ComponentName`），
且它是**一次系统调用**、不是廉价的本地操作 ⇒ 上面四处都做**去抖**
（例如 `saveWorkflow` 连续调 5 次只发一次）。

⚠️⚠️ **不要试图在 `onStartListening` 里读 `isEnabled` 之外的东西** ——
`requestListeningState` 只保证「会调一次 `onStartListening`」，
在那之前 SystemUI 显示的是**上一次的 Tile 对象**。所以刷新必须**推**，不能等拉。

### 4.5 点击行为

```kotlin
// 执行型（BaseExecuteTileService）
onClick() {
    未绑定 → openApp()
    已绑定 → executeWorkflow()          // 现逻辑，判据改 !hasAutoTriggers()
}

// 开关型（BaseToggleTileService）
onClick() {
    未绑定 → openApp()
    已绑定 → toggleWorkflowEnabled()    // §2.5 的范式
}
```

⚠️ **绕过 `onClick` 的两种情形**（`STATE_UNAVAILABLE` 时系统不派发点击、
锁屏时需 `unlockAndRun`）—— 见 §7 第 6 条。

### 4.6 绑定的**三道闸**（两池互斥的落实）

用户 2026-10-06 定案「执行型不允许绑 auto 工作流」⇒ 互斥是**强制的**。
但它要落在**三个地方**，缺一处就有一条路径能绕过：

| # | 闸 | 位置 | 作用 |
|---|---|---|---|
| 1 | 菜单项显隐 | `WorkflowListScreen` 的 `regularMenuActions` | 有 auto 的工作流**不显示**「添加到控制中心（执行）」；无 auto 的**不显示**「添加到控制中心（开关）」 |
| 2 | 选择面板分段 | `TileSelectionSheet` | 面板**按 kind 分段显示两组槽位**，且只列**属于这一池**的那个（另一池的槽位置灰或整段不显示） |
| 3 | Service 侧兜底 | 两个 `BaseXxxTileService` | 绑定关系可能在**闸 1/2 之后**失效（用户后来给工作流加了自动触发器，见 §9 第 4 条）⇒ 点击时**再判一次**，不匹配就弹 Toast 并 `openApp()`，**不执行** |

⚠️⚠️ **闸 3 不是冗余** —— 前两道闸判的是「绑定的那一刻」，而
`hasAutoTriggers()` 的结果**会随用户编辑而变**。少了闸 3，一个「绑定时是手动型、
后来加了定时触发」的工作流会**继续按执行型跑**，而它的 `isEnabled` 开关
在卡片上显示着、用户以为那个开关管用 —— 实际磁贴每次点击都在**绕过它执行**。

⚠️ **闸 3 的表现要刻意设计**：不匹配时**不能静默**（用户会以为磁贴在执行），
也不能直接执行（那是把自动工作流当手动跑）。做法是**磁贴直接进「越界态」**
（`STATE_UNAVAILABLE` + subtitle 说明要去哪一池，见 §4.7），
且**点击时只 `openApp()`**，不再执行也不切换。
⇒ 越界是**可见**的，用户一眼看出「这个磁贴需要重新绑定」，而不是点下去才发现没用。

⚠️ 三个闸用的**必须是同一个判据**（`hasAutoTriggers()`）——
各写各的（例如闸 1 用 `hasManualTrigger()`）就会出现「菜单项显示着、点了却被拒绝」。

### 4.7 状态与文案

| kind | 情况 | state | label | subtitle | icon |
|---|---|---|---|---|---|
| EXECUTE | 已绑定 | **恒 `STATE_INACTIVE`** | `workflow.name` | — | 工作流图标 |
| EXECUTE | 未绑定 | `STATE_INACTIVE` | `vFlow Tile N` | 「尚未绑定工作流」 | `ic_workflows` |
| EXECUTE | **越界**（§4.6 闸 3） | `STATE_UNAVAILABLE` | `workflow.name` | 「含自动触发器，请重新绑定到开关磁贴」 | `ic_workflows` |
| TOGGLE | 已绑定 | `isEnabled ? ACTIVE : INACTIVE` | `workflow.name` | `isEnabled ? "已启用" : "已暂停"` | 工作流图标 |
| TOGGLE | 未绑定 | `INACTIVE` | `自动化 N` | 「尚未绑定工作流」 | `ic_workflows` |
| TOGGLE | **越界**（已无 auto） | `STATE_UNAVAILABLE` | `workflow.name` | 「已无自动触发器，请重新绑定到执行磁贴」 | `ic_workflows` |

⚠️ 越界态用 `STATE_UNAVAILABLE` 是**唯一**允许用它的一格 —— 它恰恰满足官方说的
「could be put into an available state later」（用户去改绑就恢复）。
其余格子一律 `INACTIVE` 或按 `isEnabled` 双态。

⚠️ `setSubtitle` 是 **API 29** 起（本机 `api-versions.xml` 核实），`minSdk = 29` ⇒ 可用。
⚠️ `setStateDescription` 是 **API 30** ⇒ 用它必须判版本，或干脆不用（subtitle 已够）。

## 5. 决策台账

| # | 决策 | 理由 |
|---|---|---|
| 1 | 两池判据 = `hasAutoTriggers()` | 用户 2026-10-06 定案；与「有开关 / 无开关」的 UI 直觉一致 |
| 2 | 用**显式 `kind` 字段**而非索引区间 | 用户定案；语义写在数据里，且备份/反序列化天然兼容 |
| 3 | `TOGGLE` 用**新增**的 20 个 service（类名区别于执行型） | 执行型改名会让已添加的磁贴全部失效 |
| 4 | 执行型 `Tile.state` **恒 `INACTIVE`** | §3：高亮由 state 决定，与元数据无关 |
| 5 | 执行型**去掉** `TOGGLEABLE_TILE` | §3.1：那是无障碍语义；「执行一次」不是开关 |
| 6 | 开关型**保留** `TOGGLEABLE_TILE` 且**新增** `ACTIVE_TILE` | 真开关 + 用 `requestListeningState` 推送 |
| 7 | 未绑定 = `INACTIVE` + subtitle 提示，**不用** `UNAVAILABLE` | 用户定案；`UNAVAILABLE` 会让系统不派发点击，「点一下打开 App」会失效 |
| 8 | 图标支持**自定义图片** | 用户定案（「真正一致」）；落到 Bitmap + 192px 缩放 |
| 9 | 磁贴开关**连权限恢复一起做** | 用户定案；与列表页开关逐字一致 |
| 10 | 解码逻辑**抽取复用**而非复制 | 避免 `ShortcutHelper` / 磁贴两份实现漂移 |
| 11 | 两个池都加 `ACTIVE_TILE` | 让刷新可控（`requestListeningState` 推）而非等系统绑 |
| 12 | `wasEnabledBeforePermissionsLost` **必须清零** | §2.5：不清会被权限恢复自动重开 |
| 13 | 两池**强制互斥** —— 执行型不允许绑 auto 工作流 | 用户 2026-10-06 定案。落实为**三道闸**（§4.7），且三闸**共用同一判据** |
| 14 | 闸 3（service 侧兜底）不匹配时 **`openApp()` + Toast**，不静默也不执行 | §4.7：`hasAutoTriggers()` 会随用户编辑而变；静默会让用户以为磁贴在执行 |

---

## 6. 实现落点

### 6.1 新增文件

| 文件 | 内容 |
|---|---|
| `core/workflow/model/TileKind.kt` | `enum class TileKind { EXECUTE, TOGGLE }`（独立文件便于纯 JVM 单测） |
| `core/workflow/TileSlot.kt` | **纯函数层**：`kindOf(tileIndex)` / `indexInKind(tileIndex)` / `tileIndexOf(kind, slot)` / `displayName(kind, slot)`。无 Android 依赖 |
| `core/workflow/TileGate.kt` | **纯函数层（互斥判据的唯一落点）**：`accepts(kind, workflow)` / `isOutOfKind(tile, workflow)` / `mismatchMessageRes(kind)` / `outOfKindMessageRes(kind)`。⚠️ §4.6 的三道闸**全部调它**，任何一处自己写 `hasAutoTriggers()` 都会让「三闸判据一致」失效 |
| `ui/tile/BaseExecuteTileService.kt` | 从 `BaseWorkflowTileService` 拆出（或保留基类 + 加 `tileKind()` 抽象） |
| `ui/tile/BaseToggleTileService.kt` | 开关型基类 |
| `ui/tile/WorkflowToggleTileServices.kt` | `WorkflowToggleTileService0..19` |
| `core/workflow/CardIconBitmap.kt` | 图片解码（从 `ShortcutHelper` 抽出的 `loadCenterCroppedBitmap`） |
| `core/workflow/TileRefreshNotifier.kt` | `requestAll(context)` + 去抖 |
| `ui/workflow_list/TileSelectionSheet.kt`（**改**） | 分两段显示两池 |

### 6.2 改动清单

| 文件 | 改动 |
|---|---|
| `core/workflow/model/WorkflowTile.kt` | 加 `kind` 字段 + `TILE_COUNT` 40 + 两个分池常量 |
| `core/workflow/TileManager.kt` | 新增按 kind 查/存/删的方法；`getAllTilesWithEmpty` 返回 40 |
| `core/backup/scopes/TileScope.kt` | KDoc 更新（`TILE_COUNT` 20→40）；**合并逻辑不动**。⚠️ 见 §6.4 —— 但它的**测试**会被本改动搞红 |
| `ui/tile/BaseWorkflowTileService.kt` | `updateTileState` 改（state 策略按 kind 分派 + `TileGate.isOutOfKind` 的越界态 + 设 icon + subtitle）；`onClick` 按 `TileGate.accepts` 兜底（**闸 3**） |
| `AndroidManifest.xml` | 20 处执行型改元数据（去 `TOGGLEABLE_TILE`、加 `ACTIVE_TILE`）+ **新增 20 条**开关型 |
| `ui/workflow_list/WorkflowListScreen.kt` | `:522`、`:1251` 判据 `hasManualTrigger()` → `TileGate.accepts(...)`（**两个菜单项各按自己那一池判**）。⚠️ 现在只有一个「添加到控制中心」菜单项，要拆成两个、各自按池显隐 |
| `ui/workflow_list/WorkflowListRoute.kt` | `onAddToTile` 按 `TileGate.accepts` 分流到两池；`tileItems` 分两段（只列这一池的槽位） |
| `ui/common/ShortcutHelper.kt` | `loadCenterCroppedBitmap` 改为委托新文件 |
| `core/workflow/WorkflowManager.kt` | `saveWorkflow` / `deleteWorkflow` 尾部调 `TileRefreshNotifier` |
| 三语 `strings*.xml` | 新增巢式文案（见 §6.3） |

### 6.3 新增文案

```
tile_execute_pool_title        执行磁贴
tile_toggle_pool_title         开关磁贴
tile_kind_mismatch_execute     该工作流含自动触发器，请添加到「开关磁贴」
tile_kind_mismatch_toggle      该工作流没有自动触发器，请添加到「执行磁贴」
tile_unbound_subtitle          尚未绑定工作流
tile_toggle_enabled            已启用
tile_toggle_disabled           已暂停
tile_toggle_failed_permission  缺少权限，无法启用
tile_out_of_kind_execute       含自动触发器，请重新绑定到开关磁贴
tile_out_of_kind_toggle        已无自动触发器，请重新绑定到执行磁贴
```

⚠️ 后两条（`tile_out_of_kind_*`）是 §4.7 的**越界态** subtitle。
与 `tile_kind_mismatch_*`（§4.6 闸 1/2 的**绑定被拒**提示）**刻意分开** ——
一个发生在「绑定时」、一个发生在「已经绑了但条件变了」，
用户要做的事不同（前者是换一池，后者是**重新**绑）。混用会让用户以为
「我明明绑上过，怎么又说不行」。

---

### 6.4 ⚠️ 一处**会被本改动搞红**的既有测试

`app/src/test/java/com/chaomixian/vflow/core/backup/scopes/TileScopeTest.kt:221`
`the wire shape matches what TileManager writes` 断言：

```kotlin
assertEquals(
    "键名必须与 `WorkflowTile` 的字段名逐字一致（Gson 走反射）",
    setOf("tileIndex", "workflowId"),      // ← 加 kind 后会变成三个
    parsed.entrySet().map { it.key }.toSet(),
)
```

⚠️ 它**不是**该修的 bug —— 它正是设计来拦住「模型加了字段但没人意识到备份形状变了」的，
**它的变红就是它工作正常的证明**。处理方式：把期望集合改成三个键，并在注释里写清
「`kind` 是 2026-10-06 新增的；旧备份缺它时 Gson 落默认值 `EXECUTE`（见 §4.1）」。
⛔ **不要**把它改成「包含」断言（`assertTrue(containsAll)`）—— 那会让它再也拦不住下一次加字段。

## 7. 静默失效点清单

每条都对应一种「不报错、只是行为不对」的失败模式。

| # | 失效点 | 表现 | 防法 |
|---|---|---|---|
| 1 | `Icon.createWithResource(context, 0)` | 磁贴**空白**（不抛异常），看起来像图标坏了 | 显式回落 `ic_workflows` |
| 2 | 自定义图片不缩放就交给 `Icon` | `TransactionTooLargeException`，磁贴完全不更新 | 缩放 192px |
| 3 | 忘了 `wasEnabledBeforePermissionsLost = false` | 用户从磁贴关掉的自动工作流**被权限恢复悄悄重开** | 照抄列表页范式 |
| 4 | 加了 `ACTIVE_TILE` 但没补 `requestListeningState` | 进 App 改了图标，**磁贴仍是旧的**（像系统缓存） | 四处调用点 + 单测锁 |
| 5 | 直接写 prefs 绕过 `saveWorkflow` | 磁贴显示已启用，**但触发器没挂上**（工作流永不触发） | 只用 `saveWorkflow` |
| 6 | 执行型磁贴误设 `STATE_UNAVAILABLE` | 系统**不派发** `onClick` ⇒ 「点一下打开 App」失效 | 未绑定也用 `INACTIVE` |
| 7 | 执行型 service 改名 | **已添加的磁贴全部消失**（SystemUI 按 ComponentName 记） | 新池用新类名 |
| 8 | `TILE_COUNT` 改了但 `getAllTilesWithEmpty` 没跟着改 | 选择面板只显示 20 行，后 20 个槽**永远选不到** | 单测锁 40 |
| 9 | 两池的 `tileIndex` 映射靠类后缀推 | 两个池都有 0..19，**看起来一样**，点开关型却执行了工作流 | 显式偏移常量 + 单测 |
| 10 | 开关型磁贴对「纯自动、无 manual」工作流执行 | 现有闸 `hasManualTrigger()` 会**静默拒绝**（只弹 Toast） | 判据改 `hasAutoTriggers()` |
| 11 | `setStateDescription` 未判版本 | API 29 上 `NoSuchMethodError` | 不用它，或判 `SDK_INT >= 30` |
| 12 | 去抖做错（只在同一个实例内去抖） | 磁贴 service 与 App 不同进程，**去抖跨不过去** | 去抖只在 App 侧单进程内做 |
| 13 | `WorkflowIconValue.isCustomImage` 判定顺序写反（先看 `/` 再看 `file://`） | `file://...` 落到资源名路径 ⇒ `getIdentifier` 返回 0 ⇒ 磁贴**空白** | 判定集中在 `WorkflowIconValue`（已有），磁贴侧只调它 |
| 14 | 图片文件已被删 / 换机后路径失效 | 解码返回 null ⇒ 若直接 `createWithBitmap(null)` 会 **NPE 崩 service** | null 时回落 `ic_workflows`（与 `WorkflowCardIcon` 同一策略） |
| 15 | 三闸判据不统一（闸 1 用 `hasManualTrigger()`、闸 3 用 `hasAutoTriggers()`） | 菜单项**显示着**，点了却被拒 —— 用户认为「功能坏了」 | 三闸共用同一判据 + 源码扫描锁 |
| 16 | 漏掉闸 3（只做 UI 两道闸） | 绑定时是手动型、后来加了定时触发 ⇒ 磁贴**继续按执行型跑**，绕过用户以为管用的 `isEnabled` 开关 | service 侧再判一次 + 单测锁 |
| 17 | 给工作流加了自动触发器后，**另一个**「开关型」磁贴也指向它 | 两个磁贴同时控制同一个 `isEnabled`，看不出谁是谁 | 与 §9 第 4 条同源，需定案 |

---

## 8. 测试计划

### 8.1 纯函数单测（可纯 JVM）

- `TileSlotTest` —— 两池的 index ↔ (kind, slot) 往返、边界（0/19/20/39）、越界、
  `kindOf` 与 `tileIndexOf` 互为逆。
- `TileKindBackwardCompatTest` —— Gson 反序列化**缺 `kind` 的旧 JSON** ⇒ 落 `EXECUTE`；
  往返不丢字段。
- `TileGateTest` —— **互斥判据逐格验**（§4.6）：`EXECUTE + 无 auto` ✅ /
  `EXECUTE + 有 auto` ❌ / `TOGGLE + 有 auto` ✅ / `TOGGLE + 无 auto` ❌ /
  `TOGGLE + 无 auto + 有 manual` ❌（**反向锁**：别把 manual 也当成 auto）/
  **未绑定（`workflowId = null`）不算越界**（它只是空槽，不该显示「请重新绑定」）。
- `CardIconBitmapTest` —— 缩放目标、中心裁剪、文件不存在返回 null（**要造真实临时文件**）。

### 8.2 源码扫描型接线锚定

⚠️ 本仓库反复踩过「纯函数全绿但调用点缺失」（`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`）。
本改动有**五处**同类风险，各需一条扫描断言（带反证）：

1. `WorkflowListScreen` 的两处判据**真的**改成了 `!hasAutoTriggers()`（且 `hasManualTrigger` 不再出现在「添加到控制中心」邻近）；
2. `WorkflowManager.saveWorkflow` / `deleteWorkflow` **真的**调了 `TileRefreshNotifier`；
3. `AndroidManifest.xml` 里执行型 20 条**没有** `TOGGLEABLE_TILE`、**有** `ACTIVE_TILE`；
   开关型 20 条**两者都有**（逐条数数，防空转）；
4. **闸 3 真的存在** —— 两个 `BaseXxxTileService` 的 `onClick` 里都要有按 `hasAutoTriggers()`
   的判定（§4.7）。⚠️ 少了它，一条命令就能改回的「只做 UI 两道闸」**没有任何行为测试会红**；
5. **三闸判据一致** —— 三个闸（菜单 / 面板 / service）**都调 `TileGate`**，
   源码里**不得**直接出现 `hasAutoTriggers()` 与磁贴判据相邻（§7 第 15 条）。
   ⚠️ 这与第 1 条是**两件事**：第 1 条锁「改对了」，第 5 条锁「只有一处这么判」。

### 8.3 真机验证清单（**当前 0 项已做**）

- [ ] 执行型磁贴**不再常亮**（启用状态下也是暗的）
- [ ] 执行型磁贴显示工作流的**内置图标**
- [ ] 执行型磁贴显示工作流的**自定义照片**（不崩、不是空白）
- [ ] 开关型磁贴显示工作流图标 + 双态高亮
- [ ] 点开关型磁贴 ⇒ 工作流 `isEnabled` 翻转，且**触发器真的挂上/卸下**（看日志）
- [ ] 从磁贴关掉后再触发权限恢复 ⇒ **不应被自动重开**
- [ ] 在 App 里改工作流图标 ⇒ 下拉面板，磁贴**立刻**是新图标
- [ ] 未绑定槽位的 subtitle 文案
- [ ] 系统「添加磁贴」面板里，40 个磁贴**能区分**两个池（图标 / 标签）
- [ ] 长按磁贴 ⇒ 打开 App 详情页（不是控制中心面板）
- [ ] **互斥闸 1**：有 auto 的工作流，菜单里**没有**「添加到控制中心（执行）」
- [ ] **互斥闸 2**：选择面板按 kind 分段，且不列出不属于这一池的工作流
- [ ] **互斥闸 3**：把已绑在**执行型**上的工作流**加上**一个定时触发器 ⇒
      磁贴变 `UNAVAILABLE` + subtitle 提示；再点它 ⇒ **不执行**、只打开 App
      （**不是静默、也不是照跑**）

---

## 9. 未决项

| # | 问题 | 影响 |
|---|---|---|
| 1 | `requestListeningState` 在**磁贴尚未被添加到控制中心**时调用会怎样？ | 官方未明说。若会抛异常需 try/catch（可能是静默 no-op） |
| 2 | 40 个磁贴对 SystemUI 的负担 | 每个 `TileService` 都是一个独立组件。`ACTIVE_TILE` 已减轻绑定频率，但「添加磁贴」面板会列 40 项 |
| 3 | 开关型磁贴是否支持「`isEnabled=false` 时也允许执行一次」 | 现设计不允许（关了就是关了）。但用户可能期望长按执行 |
| 4 | 已绑定的工作流**后来加了自动触发器**（或删光了）怎么办 | 两池互斥（§1），但用户的编辑会让已绑的那个磁贴**越界**。本设计的处置是 **§4.7 的越界态**（`STATE_UNAVAILABLE` + subtitle 提示重新绑定），**未选**「自动迁移到对面池的空槽」——迁移会**背着用户改数据**，且目标槽可能已占。⚠️ 若将来要做迁移，须先解决「迁移后原来的槽空出来了，用户还以为是同一个磁贴」 |
| 5 | `TileManager.removeTileByWorkflowId` 是否该按 kind 限定 | 两池互斥后「同一工作流两个磁贴」不再可能，但该方法现在仍是**无差别**删全部 —— 将来若放开互斥会打架 |

> ⚠️ 原本记在这里的「执行型是否允许绑 auto 工作流」**已由用户 2026-10-06 定案：不允许**（§1）。
> 它带来的**新**未决项是第 4 条 —— 「不允许」是绑定时的一次性判定，
> 而 `hasAutoTriggers()` 的结果**会随用户编辑而变**。

---

## 10. 引用前须知

- **§3 是本文档最容易被忽略的一节** —— 它纠正了一个直觉错误（元数据控制高亮），
  直接决定「改哪里」。若有人只改了元数据、没改 `Tile.state`，
  磁贴**照样常亮**，而他会以为「改了没用」。
- **§4.6 / §4.7 是本文档最容易被做少的一节** —— 两池互斥**不是 UI 选项**，
  是用户定案的硬约束；它要落在**三道闸**上，而且**判据必须只有一处**（`TileGate`）。
  只做 UI 两道闸 = 「绑定时是手动型、后来加了定时触发」的磁贴会**继续按执行型跑**，
  绕过用户以为管用的 `isEnabled` 开关，且**没有任何行为测试会红**。
- 本文档写于 2026-10-06，`file:line` 引用锚定在 `64c229b7` / `ef34ec5d`。
- 本文档**全部结论来自代码直读 + 官方文档 + AOSP 源码**，
  **不含任何真机实测**（§8.3 是待办清单，不是已验证清单）。
  尤其「`ACTIVE_TILE` + `requestListeningState` 的实际刷新时机」
  与「40 个磁贴的面板表现」**必须上机确认**后再当作结论引用。
