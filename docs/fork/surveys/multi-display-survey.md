# 折叠屏多显示器能力调研（vFlow 视角）

> 版本：v1.0
> 状态：**真机实测定稿**（Xiaomi MIX Fold 3 / HyperOS / Android 17 · API 37，2026-10-13）
> 目录归属：`docs/fork/surveys/`（fork 新增文件，上游无此文件，冲突归属我方）
> 用途：回答一个问题 —— **「能不能让 AI 在指定的那块屏幕上干活（比如你看内屏、AI 用外屏）」**，并把这轮摸出来的机制、实测结论、以及**尚未解开的疑点**固定成文，避免以后重走一遍。

---

## 0. 动机与结论速览

**动机**：`vflow.ai.agent` / `vflow.ai.autoglm` 支持 `main_screen`（display 0）与 `virtual_screen` 两种执行环境。在折叠屏上，用户希望「AI 占用另一块屏、自己继续用当前这块」—— 那是真实物理屏，不像虚拟屏会被 App 检测。

**结论（一句话）**：**当前 vFlow 侧做不到，且被三层机制同时挡住**；但**硬件层面未必不行** —— 系统相机的「外屏预览」能同时点亮内外屏（用户实测观察），说明**还有我们没找到的通道**。

| # | 结论 | 证据强度 |
|---|---|---|
| 1 | 两块面板是**两个独立 Display**，同在 `displayGroupId 0` | 【实测】`dumpsys display` |
| 2 | **`displayId` 不绑定面板** —— `displayId 0` 永远是"当前活跃面板"，折叠/展开时会换物理背板 | 【实测】两种折叠态对比 |
| 3 | 非活跃面板：`state OFF` + `canHostTasks false` + `Display-off` 睡眠 token | 【实测】 |
| 4 | `cmd display enable-display 1` 能把**逻辑状态**改成 `ON`，但**面板物理上没有亮** | 【实测】+【用户观察】 |
| 5 | 往非活跃面板启动 App 被**无条件拒绝**，**连 root 都不放行** | 【实测】logcat 原文 |
| 6 | 拦路虎是 `canHostTasks`，且它**恒为 false**（唯一更新点被 aconfig 开关挡着） | 【源码】AOSP |
| 7 | **⚠️ 未解**：相机外屏预览能同时点亮两块屏 ⇒ 硬件/驱动支持，通道未知 | 【用户观察】 |

---

## 1. 两块面板的标识体系

### 1.1 物理屏（`DisplayDeviceInfo`）—— 稳定

```
local:4630947024259405955   1916 x 2160   StablePhysical{id=4630947024259405955, port=131}   ← 内屏
local:4630947024259405956   1080 x 2520   StablePhysical{id=4630947024259405956, port=132}   ← 外屏
```

两块面板**始终存在**，折叠态/展开态两次探针输出**完全一致**。【实测】

### 1.2 逻辑屏（`DisplayInfo`）—— ⚠️ **不绑定面板**

| | `displayId 0` | `displayId 1` |
|---|---|---|
| **折叠态** | `state ON`、`uniqueId local:...5956`（**外屏**）、`layerStack 0` | `state OFF`、`uniqueId local:...5955`（内屏）、`layerStack 1` |
| **展开态** | `state ON`、`uniqueId local:...5955`（**内屏**）、`layerStack 0` | `state OFF`、`uniqueId local:...5956`（外屏）、`layerStack 1` |

⇒ **`displayId 0` = 当前活跃面板，`displayId 1` = 另一块**；逻辑屏 0 的物理背板会随折叠状态对调。这与 `displayGroupId 0`（两块同组）完全吻合 —— 那正是 AOSP 为折叠屏做的 display group 内容迁移。【实测】

**工程含义**：**参数里存 `displayId` 去"指定内屏"是错的**；要指面板只能按 `uniqueId` 或尺寸。

### 1.3 ⚠️ 公开 API 拿不到 `uniqueId`

`javap android-36/android.jar android.view.Display` 的公开面只有：

```
getDisplayId()   getState()   getMode()   getName()   getFlags()
```

- **`getUniqueId()` / `getAddress()` 都是 `@hide`** ⇒ 第三方 App 拿不到；
- `getName()` 两块屏**都返回 `"内置屏幕"`** ⇒ 无法区分；
- ⇒ **App 侧识别面板只能靠 `getMode()` 的尺寸**（`1916×2160` = 内屏 / `1080×2520` = 外屏）。

好消息：`getState()` 是公开的，能直接读出 `ON/OFF`。

---

## 2. 非活跃面板的完整状态（折叠态 / 展开态**都一样**）

```
state OFF, committedState OFF, canHostTasks false
mSleeping=true   mAllSleepTokens=[{"Display-off", acquire at 76880 (46574419 ms ago)}]
mCurrentFocus=null   mFocusedApp=null   mImeWindow=null
mHoldScreenWindow=null   mObscuringWindow=null
```

- `Display-off` 睡眠 token 已持有 **12.9 小时**（从未释放）⇒ 不是"刚好熄了"，是**被持续按住**；
- `mCurrentFocus=null`、无窗口、无 IME ⇒ 上面什么都没有。

---

## 3. `cmd display enable-display` 实测（**能改逻辑状态，点不亮面板**）

### 3.1 命令确实存在，且 shell 路径绕过了权限

`DisplayManagerShellCommand.java`：

```java
            case "enable-display":   return setDisplayEnabled(true);    // :107
            case "disable-display":  return setDisplayEnabled(false);
            case "power-reset":      return requestDisplayPower(Display.STATE_UNKNOWN);
            case "power-off":        return requestDisplayPower(Display.STATE_OFF);
```

```java
    private int setDisplayEnabled(boolean enable) {                     // :603
        ...
        mService.enableConnectedDisplay(displayId, enable);             // 内部方法，无权限注解
        return 0;
    }
```

⚠️ **注意权限差异**：binder 版的两个方法带 `@EnforcePermission(MANAGE_DISPLAYS)`（`DisplayManagerService.java:5481`），而 **`cmd display` 这条 shell 路径调的是内部方法，不走那个校验** ⇒ Shizuku（uid 2000）可以直接用。

### 3.2 实测结果

```sh
cmd display enable-display 1        # → exit=0
```

| 项 | 之前 | 之后 |
|---|---|---|
| `displayId 1` 的 `state` | `OFF` | **`ON`** ✅ |
| `mAllSleepTokens` | `[{"Display-off", ...}]` | **`[]`**（token 已释放）✅ |
| **面板物理背光** | 灭 | **仍然是灭** ❌【用户观察】 |

**⇒ 逻辑状态 ≠ 物理点亮。** 厂商的 foldable 驱动只驱动当前活跃的那块面板，`cmd display` 改不动这一点。

### 3.3 顺带纠正一处 vFlow 自己的 bug（`screencap -d` 的语义）

| 命令 | 结果 |
|---|---|
| `screencap -d 1`（**逻辑** id） | ❌ `Failed to take take screenshot. Display Id '1' is not valid.` |
| `screencap -d 4630947024259405956`（**物理** id） | ✅ `exit=0`，16345 bytes（≈ 一片空白） |

`screencap -h` 原文：*"specify the **physical** display ID to capture"*。

⚠️ **vFlow 的 `AgentUtils.captureShellScreenshot` 传的是逻辑 displayId**：

```kotlin
val command = "screencap -d $displayId -p \"$path\""   // displayId 来自 _target_display_id（逻辑 id）
```

⇒ 虚拟屏截图的那条**回退路径是坏的**（主路径走 `ImageReader` 内存帧，所以一直没暴露）。失败时表现为代码里那句 `DebugLogger.w(TAG, "无法获取虚拟屏幕 (ID: $displayId) 的截图。")`。**这条与折叠屏无关，可独立修**（要拿到虚拟屏的物理 id，或干脆去掉这条回退）。

---

## 4. 往非活跃面板启动 App —— 被**无条件**拒绝

```sh
am start --display 1 -n com.chaomixian.vflow/.ui.main.MainActivity
```

```
java.lang.SecurityException: Permission Denial: starting Intent { ... } from null (pid=..., uid=0)
    with launchDisplayId=1
    at com.android.server.wm.SafeActivityOptions.checkPermissions(SafeActivityOptions.java:303)
```

### 4.1 确切原因（logcat 原文）

开了 ProtoLog 后复跑，拿到的是：

```
W ActivityTaskManager: Launch on display check: activity launch is not allowed
                      on a display that cannot host tasks
```

⇒ **是 `canHostTasks`，不是 `FLAG_REAR`**（`dumpsys display` 里也找不到任何 REAR 标记）。

### 4.2 AOSP 调用链

```
SafeActivityOptions.checkPermissions:286
  └─ ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay(callingPid, callingUid, launchDisplayId, aInfo)
       ├─ displayContent == null / isRemoved        → false
       ├─ (mDisplay.getFlags() & Display.FLAG_REAR) → false   ← 无条件，本机未命中
       ├─ ★ canHostTasks == false                   → false   ← ★ 本机命中的就是这条
       ├─ checkPermission(INTERNAL_SYSTEM_WINDOW) == GRANTED → true
       └─ …（untrusted / isPrivate / 用户可见性等后续分支）
```

> ⚠️⚠️ **关键：`canHostTasks` 这条在权限检查之前，所以 `INTERNAL_SYSTEM_WINDOW`（乃至 root）都绕不过去。** 本轮 `am` 是以 **`uid=0`（root）** 跑的，照样被拒 —— 这条是实测，不是推断。

### 4.3 顺带记录：Android 17 上 `wm logging` 已废弃

```
$ wm logging enable-text WM_DEBUG_TASKS
Command deprecated. Please use 'cmd protolog_configuration' instead.
```

以后要看 WM 的 ProtoLog，走 `cmd protolog_configuration`。

---

## 5. `canHostTasks` 为什么恒为 `false`（AOSP 机制）

### 5.1 字段初值就是 false

```java
// LogicalDisplay.java:255（构造函数尾部）
        mCanHostTasks = (mDisplayId == Display.DEFAULT_DISPLAY);
```

⇒ **非默认屏初值 `false`**，只有被显式设置过才会变 `true`。

### 5.2 唯一会更新它的地方，被 aconfig 开关挡着

```java
// DisplayManagerService.java:2716
    private void updateCanHostTasksIfNeededLocked(LogicalDisplay display,
            boolean shouldSendDisplayChangeEvent) {
        if (display.setCanHostTasksLocked(!mMirrorBuiltInDisplay) && shouldSendDisplayChangeEvent) {
            sendDisplayEventIfEnabledLocked(display, DisplayManagerGlobal.EVENT_DISPLAY_BASIC_CHANGED);
        }
    }
```

两处调用点（`:1275`、`:2388`）**都在 `mFlags.isDisplayContentModeManagementEnabled()` 之内**：

```java
        if (mFlags.isDisplayContentModeManagementEnabled()) {
            mLogicalDisplayMapper.forEachLocked(logicalDisplay -> {
                    updateCanHostTasksIfNeededLocked(logicalDisplay, shouldSendDisplayChangeEvent);
            });
        }
```

⇒ 该 aconfig 开关关闭时，**这段更新逻辑永不执行**，非默认屏的 `canHostTasks` 就**永远停在 `false`**。

### 5.3 而校验逻辑本身对外屏是**会**放行的

```java
// LogicalDisplay.java:975
    private boolean validateCanHostTasksLocked(boolean canHostTasks) {
        if (getDisplayIdLocked() == Display.DEFAULT_DISPLAY) return true;   // 默认屏永远 true
        if (mPrimaryDisplayDevice.shouldOnlyMirror()) return false;          // 只镜像的永远 false
        final boolean isRearDisplay = getDevicePositionLocked() == POSITION_REAR;
        final boolean ownContent =
                ((mPrimaryDisplayDevice.getDisplayDeviceInfoLocked().flags
                        & DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY) != 0) || isRearDisplay;
        if (ownContent) return true;                                         // ← 外屏带 OWN_CONTENT_ONLY
        return canHostTasks;
    }
```

外屏的 flag 里**确实有 `FLAG_OWN_CONTENT_ONLY`**（探针 `###3` 的并集：`FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY / FLAG_ALLOWS_CONTENT_MODE_SWITCH / FLAG_OWN_CONTENT_ONLY / FLAG_PRESENTATION / FLAG_ROTATES_WITH_CONTENT / FLAG_SECURE / FLAG_SUPPORTS_PROTECTED_BUFFERS / FLAG_TRUSTED`，**没有 `FLAG_PRIVATE`**）。

⇒ **不是"被否决"，是"根本没人调用"** —— 这个区分很重要：它意味着**理论上存在把 `canHostTasks` 翻成 true 的可能**。

### 5.4 为什么不建议走旁路

理论上可以用 `device_config` 覆盖那个 aconfig 开关，逼 DMS 调一次 `setCanHostTasksLocked(true)`。**但**：

1. 是**全局系统改动**，要重启框架才生效；
2. HyperOS 大概率锁了这类覆盖；
3. **最关键：面板物理上不亮（§3.2）—— 开关翻了也没用。**

---

## 6. ⚠️ 未解疑点：相机「外屏预览」能同时点亮两块屏

**用户实测观察（2026-10-13）**：系统相机的「外屏预览」功能下，**内外屏同时亮**。

⇒ **这直接推翻"硬件只能驱动一块面板"的推断** —— 至少对相机这个场景，两块面板是能同时工作的。**说明存在我们还没找到的通道。**

### 6.1 待验证的候选方向（**全部未验证，仅为线索**）

| # | 方向 | 依据 / 为什么值得查 |
|---|---|---|
| A | **相机走的是厂商私有通道** | HyperOS 的折叠屏服务 / 相机专属权限；AOSP 层看不到 |
| B | **`DisplayManager.setMirroringEnabled()`** | AOSP 有这个概念，而 §5.2 的 `canHostTasks = !mMirrorBuiltInDisplay` **正是它的下游** —— 说明"镜像内置屏"是一条与 `canHostTasks` 直接相关的独立状态；若相机是"把内屏镜像到外屏"，那它走的就是这条路 |
| C | **不是"点亮两块屏"，而是"外屏被当成 presentation/副屏"** | 但非活跃面板 `canHostTasks false` 与"能放内容"矛盾，需要解释 |
| D | **厂商的"外屏预览"专用模式** | 小米官方文档（`pId=1484` / `pId=2046`）里有 `display_features` / `device_posture`，但那是布局适配，没提双屏点亮 |
| E | **相机进程持有的特殊权限** | 可对比相机与普通 App 的权限集（`dumpsys package com.android.camera`） |

### 6.2 下次复测的清单（建议按此顺序）

1. **相机开外屏预览时**，跑一遍本轮的探针命令 —— 重点看：非活跃面板的 `state` 是否变成 `ON`（还是仍 `OFF` 却物理亮着 ⇒ 说明逻辑/物理真的解耦）；`canHostTasks` 是否变了；`mAllSleepTokens` 是否被释放；`dumpsys SurfaceFlinger` 里那块面板是否被 composer 启用。
2. `dumpsys package com.android.camera | grep -iE 'permission|fold|display|mirror'` —— 找厂商专属权限。
3. `dumpsys display | grep -iE 'mirror|Mirror'` —— 找 `mMirrorBuiltInDisplay` 的痕迹。
4. 查 HyperOS 的折叠屏服务（`dumpsys activity services | grep -iE 'fold|display'`）。

> ⚠️ **写进文档的库/平台行为断言先实跑再落笔** —— 本轮就踩了一次：先据 `LogicalDisplay` 构造函数推断"`canHostTasks=false` 只是没初始化、不是拦路虎"，**结果 logcat 直接打脸**（它确实是拦路虎）。这条教训与 `backup-webdav-design.md` §1.5 同源。

---

## 7. 事实 / 评估分离

### 7.1 事实（均可复现）

- 两块面板是两个 `Display`，`displayGroupId` 相同；
- `displayId` 是"活跃/非活跃"的代号，不是"内屏/外屏"的代号；
- 非活跃面板 `state OFF` + `canHostTasks false` + 被 `Display-off` token 按住；
- `cmd display enable-display N` 能改逻辑状态，**改不了物理背光**；
- `am start --display N` 对 `canHostTasks=false` 的屏**无条件拒绝，root 也不行**；
- `screencap -d` 要**物理** id，vFlow 现有代码传的是逻辑 id（bug）；
- 相机外屏预览能同时点亮两块屏（用户观察）。

### 7.2 评估（主观判断，供后续选点）

- **vFlow 侧当前不可行** —— 三层机制（驱动 / 显示管理 / 窗口管理）叠加，且没有任何一层是应用侧能碰的；
- **不建议走 `device_config` 旁路** —— 全局改动 + 需重启 + 面板不亮，收益为零；
- **「AI 不占用你的屏」这个需求，在折叠屏上的唯一可行形态是虚拟屏** —— 它不依赖任何物理面板状态；这次调研反而给了它一个反证式支撑：物理副屏连 App 都放不上去，虚拟屏反而是唯一能跑 App 的"第二工作区"；
- **相机那条线索值得保留** —— 它说明"同时点亮"在硬件/驱动层是成立的，**属于"没找到通道"而非"做不到"**，与 `fold-trigger-design.md` §9 推翻外部调研悲观结论的情形同类。

---

## 8. 与 vFlow 代码的落点

### 8.1 现状：管道已具备，只差"入口"

`AgentModule.kt:217` 一处决定目标屏：

```kotlin
var targetDisplayId = 0                                    // 0 = 当前默认屏（= 当前活跃面板）
if (displayMode == DISPLAY_MODE_VIRTUAL) {
    targetDisplayId = VirtualDisplayManager.startHeadlessSession()
}
context.setVariable("_target_display_id", VObjectFactory.from(targetDisplayId))
```

下游全部按它路由（都已支持任意 displayId）：

| 环节 | 实现 |
|---|---|
| 输入注入 | `AgentTools` 每个方法开头的 `targetDisplayId > 0` 分支（`input -d <id> ...`） |
| 无障碍读树 | `AgentUtils.dumpHierarchy` / `getCurrentUIInfo` 的 `service.windows.find { displayId }` |
| 截图 | `AgentUtils.captureScreen`（虚拟屏走内存帧，回退走 `screencap -d`） |
| 启动应用 | `AgentTools.launchApp` 的 `am start --display <id>` |

⇒ **「指定屏幕」缺的只有两件工程活**：① 发现屏幕（`DisplayManager.getDisplays()`）；② 给个入口（`display_mode` 目前是 `main_screen` / `virtual_screen` 两个枚举值）。**本轮结论是：暂不做**（没有第二块可用屏可指）。

### 8.2 本轮的结论对 `display_mode` 的实际建议

- **不加「指定屏幕」**；
- 但值得给 `display_mode` 补 **`acceptsMagicVariable = true`** —— 这样折叠触发器（`vflow.trigger.fold`，输出 `fold_state` / `is_folded` / `is_unfolded` / `is_half_opened` / `angle` / `posture_source`）就能驱动「展开 ⇒ 主屏（内屏）；折叠 ⇒ 虚拟屏」的自动分流。⚠️ 现状**不能**：`display_mode` 的 `InputDefinition` 没声明 `acceptsMagicVariable`。

### 8.3 复测方式（本轮用的方法，零代码改动）

工作流两步即可，**不需要改 vFlow 代码**：

1. `vflow.shizuku.shell_command`（执行方式选 Shizuku）—— 粘探针命令；
2. `vflow.data.log` —— `内容` 填 `{{shell步骤.result}}`，跑完在「最近日志 → 详情」里看全文。

⚠️ **两个坑**：

- `ShizukuUserService.execWithStructuredResult` **只在 `exitCode != 0` 时才带出 stderr** ⇒ 命令最后一句若是成功的（如 `rm -f`），**前面所有 stderr 会被静默丢掉**。要抓错误就得显式 `2>&1`。
- `grep`/`cut` 会截断长行 —— `DisplayInfo` 的 `state` / `canHostTasks` 都在**行尾**，`cut -c1-260` 会把它们切掉（本轮踩过）。要看这两项就别 cut。

---

## 附录：本轮探针命令（可直接复用）

```sh
echo "###A 逻辑屏(全量)"; dumpsys display | grep 'mBaseDisplayInfo='
echo "###B 物理屏"; dumpsys display | grep 'DisplayDeviceInfo{' | cut -c1-200
echo "###C flags"; dumpsys display | grep -oE 'FLAG_[A-Z_]+' | sort -u | tr '\n' ' '; echo
echo "###D 每屏窗口数"; W=$(dumpsys window windows); for d in 0 1 2 3; do printf "d%s=" "$d"; echo "$W" | grep -c "mDisplayId=$d"; done
echo "###E 非活跃屏详情"; dumpsys window displays | grep -A28 'Display: mDisplayId=1' | head -32
echo "###F 截图(物理 id)"; screencap -d 4630947024259405956 -p /data/local/tmp/p1.png 2>&1; echo "exit=$?"; wc -c < /data/local/tmp/p1.png 2>&1
echo "###G 试启动"; am start --display 1 -n com.chaomixian.vflow/.ui.main.MainActivity 2>&1 | head -4
echo "###H logcat 原因"; logcat -d -t 400 2>&1 | grep -iE 'Launch on display|rear display|display not found' | tail -8
rm -f /data/local/tmp/p1.png
```

撤销（把外屏逻辑状态改回去）：

```sh
cmd display disable-display 1
```
