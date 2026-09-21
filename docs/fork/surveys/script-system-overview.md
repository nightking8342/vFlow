# vFlow 脚本体系梳理（JS / Lua 能力边界）

> 版本：v1.1
> 状态：代码走查 + 本机 Rhino 实测 + **真机验证**（2026-09-21）
> v1.1 修订：JS 侧三项注入（Context / `importClass` / ClassLoader）**已实施并真机验证通过**
> —— §0 结论、§1 对照表、§4.3（加历史标注）、§10.1（新增实现与验证记录）已同步；
> 原文中「JS 未注入 Context」的描述为**改动前的状态**，保留于 §4.3 并显式标注。
> 目录：`docs/fork/surveys/`（fork 新增文件，上游无此文件，冲突归属我方；同目录另见 [`README.md`](README.md) 索引）
> 用途：**梳理 vFlow 两套脚本引擎的现状与能力边界**——脚本能看到什么、能触达什么、边界在哪、与同类的差距在哪，供后续开放能力与写脚本时对照。
>
> **写作起因**：围绕「能否用脚本实现 ShortX 的 `shortx-Fluid_Cloud_Island`（流体云）」展开的一次完整探讨。探讨中反复出现误判（把「vFlow 内部有某能力」当成「脚本能用」），因此把结论固定成文。

---

## 0. 这份文档回答什么

三个层次，**必须分开看**（混在一起是本文所有误判的根源）：

| 层次 | 问题 | 谁决定 |
|---|---|---|
| **语言层** | 脚本能写什么语法、能触达什么 Java 类 | 引擎配置（Rhino/Luaj 的初始化方式） |
| **环境层** | 脚本拿到什么对象（Context、模块树、变量） | 注入模型 |
| **能力层** | 脚本能完成什么系统操作 | 进程身份（UID）+ 上两层的组合 |

**核心结论预告**：

1. **语言层，JS 已经全开**——实测 `Packages`、`java`、反射均可达（§4.1）。
2. **环境层，已补齐**——`context`／`importClass`／ClassLoader 三项注入已实施并于真机验证（见 §10 状态标注；遗留问题见 §4.3 的历史说明）。
3. **能力层，脚本很强**——实测的流体云脚本证明「小窗 + 前台检测 + 超级岛」纯脚本可实现（§6）。
4. 「vFlow 有此能力」**不等于**「脚本能用」——绝大多数 vFlow 内部能力（island、核心模块）**对脚本不可见**（§4.6）。

---

## 1. 两套引擎总览

| | **JavaScript** | **Lua** |
|---|---|---|
| 依赖 | `org.mozilla:rhino:1.9.0`（`app/build.gradle.kts:204`） | `org.luaj:luaj-jse:3.0.1`（`app/build.gradle.kts:201`） |
| 执行器 | `core/execution/JsExecutor.kt` | `core/execution/LuaExecutor.kt`（188 行） |
| 值转换 | `JsValueConverter.kt` | `LuaValueConverter.kt` + `LuaProxyTables.kt` |
| 宿主模块 | `JsModule`（`vflow.system.js`，动作） | `ScriptedModule`（用户脚本模块） |
| **注入真 Context** | ✅ **是**（`JsExecutor.kt`，2026-09-21 起） | ✅ **是**（`LuaExecutor.kt:36-37`） |
| `importClass` 语法糖 | ✅ 是（`ImporterTopLevel`，2026-09-21 起） | N/A |
| 模块树 `vflow.*` | ✅ | ✅ |
| 内联表达式 | ✅ `{% ... %}` | ❌ |

> ⚠️ **JS 侧的三项注入（Context / `importClass` / ClassLoader）是 2026-09-21 新增的**，
> 此前 JS 的 `context` 是空壳 JS 对象。改动细节与验证见 §10.2。**§4.3 保留了改动前的
> 原始分析，用于理解差异来源**（Lua 一直有、JS 一直缺），阅读时请对照本节的状态。

### 1.1 三个 JS 落点

| # | 落点 | 载体 |
|---|---|---|
| 1 | **动作模块** | `JsModule`（`id = vflow.system.js`） |
| 2 | **内联表达式** | `{% code %}`，由 `InlineScriptEvaluator` 求值 |
| 3 | **条件/参数中嵌入** | 同 2，复用 `InlineScriptEvaluator` |

> ⚠️ `InlineScriptEvaluator.evaluateScript()` 每次求值都 `JsExecutor(context).execute(...)`
> —— **一个含 N 个 `{% %}` 的文本会创建 N 个 Rhino Context**，每次都重建模块树。见 §8 缺陷 3。

### 1.2 Lua 只有两个落点

| # | 落点 | 载体 |
|---|---|---|
| 1 | **用户脚本模块** | `ScriptedModule`（基于 `manifest.json` + `script.lua`） |
| 2 | 模块树调用 | 同 1，脚本内可调 `vflow.*` |

> ⚠️ `ScriptedModule.execute()` **硬编码** `LuaExecutor`（`ScriptedModule.kt:110`），
> `ModuleManifest` **没有 `language` 字段**（`ModuleManifest.kt:15-23`）。
> 即「用户可分享的脚本模块」目前**只能写 Lua**。

---

## 2. 变量与模块树注入（两引擎共有）

### 2.1 JS 注入的全局符号

`JsExecutor.execute()` 注入（行号见 `JsExecutor.kt`）：

| 符号 | 内容 | 行号 | 可变性 |
|---|---|---|---|
| `context` | ⚠️ **空对象占位**（`newObject`） | `:37-40` | — |
| `inputs` | 脚本入参 | `:43-47` | 可读写 |
| `sys` | 魔法变量快照 | `:50-54` | 读 |
| `vars` | 命名变量快照 | `:57-61` | 读 |
| `global` | 持久化全局变量快照 | `:64-72` | 读 |
| `vflow.*` | 自动生成的模块树 | `injectVFlowModules()` | 函数调用 |

### 2.2 Lua 注入的全局符号

`LuaExecutor.execute()` 注入：

| 符号 | 内容 | 行号 | 特性 |
|---|---|---|---|
| `context` | ✅ **真实 Android Context**（`CoerceJavaToLua.coerce(applicationContext)`） | `:36-37` | Java Userdata |
| `inputs` | `MapProxy`（**零拷贝双向同步**） | `:41` | 读写直达 Kotlin Map |
| `sys` | `VObjectMapProxy` | `:45` | 代理魔法变量 |
| `vars` | `VObjectMapProxy` | `:49` | 代理命名变量 |
| `vflow.*` | 模块树 | `injectVFlowModules()` | 函数调用 |

> **Lua 的 `MapProxy` 是设计亮点**（`LuaProxyTables.kt`）：重写 `get`/`set`/`next`/`len`，
> 直接映射到底层 Kotlin MutableMap，**零拷贝、实时同步**。JS 侧无等价物（每次拷贝快照）。

### 2.3 模块树生成逻辑（两引擎同构）

```kotlin
for (module in ModuleRegistry.getAllModules()) {
    if (!module.id.startsWith("vflow.")) continue     // 只处理 vflow.* 命名空间
    val parts = module.id.split('.')                  // ["vflow","device","click"]
    // 逐级创建中间对象，叶子绑定包装函数
}
```

即 **模块 ID 直接映射为脚本路径**：`vflow.device.click(...)`。

**桥接行为**（`JsModuleWrapperFunction` / `ModuleWrapperFunction`）：

| 阶段 | 行为 |
|---|---|
| 入参 | JS：取 `args[0]` 所有属性 → `Map<String,Any?>`；Lua：Table → Map |
| 执行 | `runBlocking { module.execute(...) }` ⚠️ **同步阻塞** |
| 返回 | `Success` → 单输出且键为 `result` 时返回裸值，否则返回整个 Map；`Failure` → 抛脚本异常（可 `try-catch`/`pcall` 捕获） |

**规模**：`ModuleRegistry` 当前约 **192 处** `register(...)` 调用。
即每次脚本执行都要遍历约 192 个模块建树（见 §8 缺陷 3）。

---

## 3. 内联脚本 `{% ... %}`

`core/execution/InlineScriptEvaluator.kt` + `core/types/parser/TemplateParser.kt`：

| 语法 | 含义 |
|---|---|
| `{{expr}}` | 变量引用 |
| `[[name]]` | 命名变量引用 |
| `{% code %}` | **内联 JavaScript**（`TemplateParser.kt:43-50`） |

### 3.1 一个已做对的地方（勿退化）

`interpolateVariablesAsJavaScript()`（`InlineScriptEvaluator.kt:48-61`）把 `{{var}}` 的值用
**`gson.toJson(value)` 序列化为 JSON 字面量**后嵌入脚本（`:55`）。

**这是正确实现**——对比 ShortX 的同类机制是 `'"' + value + '"'`（**不转义**，存在模板注入面，见 §7.2）。
**vFlow 在此处已避开该缺陷，改造时不要退化。**

---

## 4. 能力边界（**本文核心**）

### 4.1 语言层：JS 已全开（本机实测）

测试方法：本机以 **Rhino 1.9.0（vFlow 实际使用的版本）** 复现 `JsExecutor` 的初始化配置
（`setOptimizationLevel(-1)` + `initStandardObjects()`），跑探针脚本。

| 探针 | 结果 |
|---|---|
| `typeof Packages` | ✅ `object` |
| `typeof java` | ✅ `object` |
| `java.lang.System.currentTimeMillis()` | ✅ 可用 |
| `Packages.java.lang.System.getProperty('java.version')` | ✅ 返回 `21.0.11` |
| `new java.io.File('/etc/hosts').exists()` | ✅ 可用 |
| `java.lang.Class.forName('java.lang.Runtime').getMethod('getRuntime').invoke(null)` | ✅ **反射成功** |
| `new java.lang.StringBuilder().append('a').toString()` | ✅ 返回 `ab` |
| `java.util.Arrays.asList('x','y').size()` | ✅ 返回 `2` |
| `typeof importClass` | ❌ `undefined` |
| `typeof importPackage` | ❌ `undefined` |

**结论：`initStandardObjects()` 并未关闭 Java 互操作。** `Packages` / `java` / 反射**全部可达**。
缺少的只有 `importClass` / `importPackage` 两个语法糖（由 `ImporterTopLevel` 提供，见 §5.1）。

### 4.2 `initStandardObjects()` vs `ImporterTopLevel` 的精确差异

实测对照 40 个全局符号，**实际增量只有两个函数**：

| 作用域 | 有 | 无 |
|---|---|---|
| `Context.initStandardObjects()` | Object Array String Number Math JSON Date RegExp Error Symbol Promise **Packages java javax org com edu net getClass JavaAdapter JavaImporter Continuation** | **importClass importPackage** |
| `ImporterTopLevel(cx)` | 同上 **+ importClass importPackage** | — |

**所以 `ImporterTopLevel` 的收益是「兼容性」而非「能力」**——让 ShortX / Auto.js 风格脚本
（普遍以 `importClass(...)` 开头）能原样粘贴，不必逐行改写。

### 4.3 环境层：`context` 注入的**历史差异**（Lua 一直有，JS 此前缺）

> 📌 **本节描述的是改动前的状态（2026-09-21 前）**。
> JS 侧已在 §10.2 完成修复并真机验证；保留本节是为了说明**差异的来源**——
> 这是两个引擎各自的实现选择，不是整体设计的一部分。

```kotlin
// JsExecutor.kt:37-40 —— JS：空对象
val contextObj = context.newObject(scope)
contextObj.setPrototype(scope)
contextObj.setParentScope(scope)
ScriptableObject.putProperty(scope, "context", contextObj)
```

```kotlin
// LuaExecutor.kt:36-37 —— Lua：真实 Context
val contextLua = CoerceJavaToLua.coerce(executionContext.applicationContext)
globals.set("context", contextLua)
```

**后果**：JS 里 `context.getSystemService(...)` 报错；Lua 里可以直接调。

> **修正一处流传的误判**：不能说「vFlow 的脚本都没有 Context」。
> **Lua 有，JS 没有**。这是两个引擎间的实现差异，不是整体设计。

### 4.4 注入 App Context 后，脚本能拿到哪些 System Service

`ExecutionContext.applicationContext`（`ExecutionContext.kt:57`）是真实的 App Context。
**vFlow 自身代码就在用同一个 Context 做同类操作**（有力旁证）：

| vFlow 自己的代码 | 用的 Context | 做什么 |
|---|---|---|
| `AgentOverlayManager(context.applicationContext)` | applicationContext | 悬浮窗（`TYPE_APPLICATION_OVERLAY`） |
| `ScreenFlashOverlay(context.applicationContext)` | applicationContext | 全屏悬浮层 |
| `ScreenCaptureOverlay(...)` | applicationContext | 截图选区浮层 |

**vFlow 现有代码实际使用过的 System Service**（按出现频次）：

| Service | 次数 |
|---|---|
| `CLIPBOARD_SERVICE` | 17 |
| `WINDOW_SERVICE` | 12 + 5 |
| `NOTIFICATION_SERVICE` | 11 + 6 |
| `LauncherApps` | 7 |
| `POWER_SERVICE` | 6 |
| `LOCATION_SERVICE` | 6 |
| `SENSOR_SERVICE` | 4 |
| `WIFI_SERVICE` | 3 |
| `KEYGUARD_SERVICE` | 3 |
| `ALARM_SERVICE` | 3 |
| `USAGE_STATS_SERVICE` | 2 |
| `MEDIA_PROJECTION_SERVICE` | 2 |
| `INPUT_METHOD_SERVICE` | 2 |
| `BLUETOOTH_SERVICE` | 2 |

**权限**：悬浮窗（`SYSTEM_ALERT_WINDOW`，Manifest:18）与通知（`POST_NOTIFICATIONS`，Manifest:20）
**均已声明**，无需新增。

### 4.5 环境层唯一的真限制：`getRunningTasks()`

| | 说明 |
|---|---|
| 能否拿到 `ActivityManager` 对象 | ✅ 能，`getSystemService` 不拦 |
| 能否读到**别的应用** | ❌ **不能**（Android 5.0+ API 语义变更，非 vFlow 限制） |

**绕法**（见 §6 实证）：走 shell 命令 `dumpsys`，或走特权通道（§5.3）。

### 4.6 能力层：脚本碰不到 vFlow 的内部能力

**这是最容易被误判的一点。** 「vFlow 有 X 能力」**不等于**「脚本能用 X」。

| 例子 | 现状 |
|---|---|
| `services/island/`（超级岛） | 仅被 `ExecutionNotificationManager` 与 `LogcatCaptureController` 调用 —— **全是 vFlow 内部功能**，无脚本接口、无模块暴露 |
| `CreateFloatWindowModule` 等 | 是**工作流模块**，脚本只能通过 `vflow.ui.float_start(...)` 间接调，且只能做表单式窗口 |
| `ServiceStateBus.lastActivityPackageName` | 脚本**不可见** |

---

## 5. 与 ShortX 的能力对照

> 参照对象：ShortX `tornaco.apps.shortx`（基于 Xposed 的自动化工具）。
> 外部调研详见 `D:/develop/references/shortx/`（仓库外，不进 git）。

### 5.1 ShortX 的 JS 能力来源：**两个，不是一个**

```
ShortX 的 JS 能力 = 语言层（Rhino） + 环境层（Xposed 注入 system_server）
                                     ↑
                              这半边 vFlow 不可复制
```

### 5.2 ShortX 的 context 是怎么来的（源码直读）

```java
// AMSHook.java:36 —— hook ActivityManagerService.start()
Object thisObject = shortXHookParam.getThisObject();          // AMS 实例
Context context = (Context) AbstractC9649tz1.OooOO0(thisObject, "mContext");
//                              ↑ 反射取 AMS 的私有字段 mContext
C7546oY1.OooOO0o.OooO0oo(context);                            // 传给 RuleService
```

```java
// AbstractC2729ak2.OooO0oo(context)
public void OooO0oo(Context context) {
    this.OooO = context;      // androidContext   ← system_server 的系统 Context
    this.OooOO0 = context;    // androidUiContext ← 同一个
}
```

注入方式则很朴素（`MP.java`）：

```java
ScriptableObject.putProperty(importerTopLevel, "context",
    org.mozilla.javascript.Context.javaToJS(context, importerTopLevel));
```

**结论**：ShortX 的 `context` 是 **`system_server` 的系统 Context（UID 1000）**，
靠 Xposed 从 AMS 里取。

> ⚠️ 一个曾出现的误判：`getSystemUiContext()`（`AbstractC2729ak2.OooO0o0()`）只是
> **context 为 null 时的兜底路径**，正常运行走不到；且它返回的是 SystemUI 的 package context，
> 与 system_server 的系统 Context **不是一回事**。

### 5.3 逐项能力对照

| 能力 | ShortX | vFlow 脚本 |
|---|---|---|
| Rhino 语言能力 | ✅ | ✅ **等价**（§4.1 实测） |
| `importClass` 语法糖 | ✅ | ❌ 需换 `ImporterTopLevel` |
| 真 Context | ✅ system_server 级 | ⚠️ **Lua 有 App 级；JS 需补** |
| `context` 是系统 Context | ✅ | ❌ **不可能**（需 Xposed） |
| **Hook / 方法拦截 / 进程注入** | ✅ | ❌ **完全不可能** |
| 系统级 UI（状态栏 / QS / SystemUI） | ✅ | ❌ **不可能** |
| Hidden API 全局豁免 | ✅ | ⚠️ 部分（Core 进程天然豁免，App 进程受限） |
| 特权调用（小窗 / 任务栈） | ✅（UID 1000） | ✅ **可绕**（见 §6，走 shell） |
| 模块生态 | 16 个固定 API | **约 192 个模块**（`vflow.*`） |

### 5.4 覆盖率结论

| 类别 | 覆盖率 |
|---|---|
| Rhino 语言能力、模块树、Java 互操作 | **100%** |
| 系统服务调用（含小窗） | **~95%**（可走 shell / Core 绕） |
| 无障碍自动化 | ~70% |
| **Hook / 进程注入** | **0%** |
| **系统级 UI（SystemUI）** | **0%** |

**整体约 80%。** 剩下 20% 是 Xposed 独有的，`app_process + 无障碍` 路线**物理上不可复制**。

> **要 100% 的唯一路径**：vFlow 自己也做成 Xposed 模块。那意味着用户必须 root + 装 LSPosed，
> 是**产品定位的改变**，不是加功能。

---

## 6. 实证：流体云脚本在 vFlow 上的可行性

> 对象：[`shortx-Fluid_Cloud_Island`](https://github.com/nightking8342/shortx-Fluid_Cloud_Island)（约 2800 行 JS）
> —— 剪贴板/链接识别 → 超级岛展示 → 点击全屏/小窗打开。

### 6.1 逐功能判定

| 功能 | 纯脚本可实现 | 依据 |
|---|---|---|
| **超级岛** | ✅ **能** | 就是 `NotificationManager.notify()` + `notification.extras.putString("miui.focus.param", json)`（原脚本 `core.js:2119`）。**公开 Notification API，无特权要求** |
| **悬浮胶囊窗** | ✅ 能 | `WindowManager.addView` + `TYPE_APPLICATION_OVERLAY`（原脚本 `onOpen.js` 已实现） |
| **前台应用检测** | ✅ 能 | `dumpsys activity activities`（shell 命令，不受 `getRunningTasks` 限制） |
| **小窗** | ✅ **能** | 见 §6.2 |
| 剪贴板读取 / 正则匹配 / Intent 启动 | ✅ 能 | 公开 API / 纯 JS |

**七项功能里六项纯脚本可做。**

### 6.2 小窗的实证方案：`service call` 绕过整条特权链路

**已实现的脚本**（用户提供，vFlow 当前代码下可用）：

```bash
# 取前台 taskId
TID=$(dumpsys activity activities | grep -m1 'topResumedActivity' | grep -oE ' t[0-9]+' | tr -d ' t')
# 发隐藏事务（138 = launchMiniFreeFormWindowVersion2 的事务码）
service call activity_task 138 i32 $TID i32 $flag s16 '' i32 0
# 回读验证
dumpsys activity activities | grep 'Task{' | grep -q 'mode=freeform'
```

脚本侧只需：

```javascript
var r = vflow.shizuku.shell_command({ mode: 'auto', command: cmd });
// r.result / r.success / r.exit_code
```

**为什么成立**：

| 要素 | 说明 |
|---|---|
| `service call` | Android 自带 shell 工具，**直接向系统服务发 binder 事务** |
| `138` | `launchMiniFreeFormWindowVersion2` 的**事务码**（MIUI 私有 AIDL 编译产物） |
| 身份 | 由 `vflow.shizuku.shell_command` 提供（UID 2000），**权限校验自然通过** |

**这一方案绕过了**：Core wrapper、`VFlowCoreBridge.rawCall`、新模块、R8 混淆、ClassLoader —— **全部不需要**。

**已核实的依赖**（全部存在）：

| 脚本调用 | 核实结果 |
|---|---|
| `vflow.shizuku.shell_command` | ✅ `ShellCommandModule.kt:30` |
| `vflow.system.launch_app` | ✅ `LaunchAppModule.kt:25` |
| `vflow.device.delay` | ✅ `DelayModule.kt:32` |
| `mode: 'auto'` | ✅ 合法值（`modeOptions`） |
| `r.result` | ✅ 输出字段（另有 `success` / `exit_code`） |

**代价（诚实标注）**：

| 项 | 说明 |
|---|---|
| **事务码硬编码 `138`** | ⚠️ **关键脆弱点**：系统升级可能改事务码。失效时会由 `mode=freeform` 回读报 FAIL（不会静默） |
| 依赖 `dumpsys` 输出格式 | ⚠️ 同样可能随版本变化 |
| 依赖 Shizuku / Root | 需要 `shell_command` 可用 |
| 机型限制 | `launchMiniFreeFormWindowVersion2` 是 MIUI / 澎湃私有接口 |

### 6.3 一个重要的方法论结论

**与其为每个能力造模块，不如用已有的 shell 逃生舱。**

| 思路 | 成本 |
|---|---|
| 为「小窗」新增 Kotlin 模块 | 1 个 Core wrapper 方法 + 1 个 App 模块 + 桥接公开 |
| 用 `vflow.shizuku.shell_command` + `service call` | **0 行代码** |

`shell_command` 本身就是一个**通用特权逃生舱**——这个结论应作为后续「脚本要新能力」时的首选评估路径。

---

## 7. ShortX 的可借鉴点（评估，非事实）

> 以下为**判断**，与前面的现状描述分开。

### 7.1 `console` 对象（低成本高收益）

ShortX 的 `KotlinConsole` 提供 11 个浏览器习语方法：
`log` / `println` / `info` / `warn` / `error` / `group` / `groupEnd` / `time` / `timeEnd` / `count` / `countReset`。

**vFlow 完全没有。** 实现成本极低（三个 Map + 计数器）。

### 7.2 ShortX 的一处**缺陷**（vFlow 应避免）

ShortX 的变量预处理是**字符串级替换**：值被简单包在双引号里（`'"' + value + '"'`），**不转义**。

- 若某变量值是 `a" + maliciousCode + "b`，会**逃出字符串字面量** → 经典模板注入（SSTI）
- 在 ShortX 的单机语境下影响有限，但在**共享规则**场景会成为远程代码执行面

**vFlow 已用 `gson.toJson()` 正确转义（§3.1），勿退化。**

### 7.3 代码库 / 脚本片段资产化

ShortX 把「脚本片段」作为一级功能（`CodeLibraryItem`：id/name/description/type/content/tags），
支持编辑器内试跑、按类型筛选。

vFlow 的 `scripted/` 目录是**模块粒度**的（`ScriptedModule`），**没有片段粒度**。
但 vFlow 有更完整的 `MODULE_STORE` 远程分发（`RepositoryScreen.kt`）。

---

## 8. vFlow 脚本体系的已知短板（评估）

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| 1 | `JsModuleWrapperFunction.call()` | `runBlocking { module.execute(...) }` **在脚本线程内同步阻塞** | UI 线程执行时 ANR 风险 |
| 2 | `JsExecutor.kt:31` | `optimizationLevel = -1`（解释模式）且标 `@Suppress("DEPRECATION")` | Rhino 1.9.0 已废弃该 API，实际行为需核实 |
| 3 | `injectVFlowModules()` | **每次执行**都遍历约 192 个模块重建对象树 | 固定开销；含 N 个 `{% %}` 的文本会放大 N 倍 |
| 4 | `JsExecutor` 全局 | **无超时、无指令数上限、无内存限制** | 死循环脚本永久挂住线程 |
| 5 | `JsExecutor` 全局 | 未装 `ClassShutter`，Java 互操作全开 | ⚠️ 见 §9 威胁分析 |

---

## 9. 威胁模型与分级策略（评估）

### 9.1 vFlow 与 ShortX 的根本差异

| | ShortX | vFlow |
|---|---|---|
| 脚本来源 | 用户自己写 | 自己写 **+ 远程仓库下载 + AI 生成** |
| 分享机制 | 有远程规则仓库 | ✅ `MODULE_STORE` + `RepositoryScreen` |
| AI 生成 | 无 | ✅ `WorkflowAiGenerator` |

**两者叠加 ⇒ 恶意脚本是真实威胁面。** ShortX 的「不设防」姿态（`NoSecurityController` 全局关闭
`SecurityController`）**不可平移到 vFlow**。

### 9.2 分级策略建议

| 脚本来源 | Java 互操作 | 超时 | 依据 |
|---|---|---|---|
| **用户手写** | ✅ 完全放开 | 30s（可调） | 用户即设备主人 |
| **远程仓库下载** | ❌ 白名单（`ClassShutter`） | 10s | 别人的脚本会进设备 |
| **AI 生成** | ❌ 白名单 | 10s | 未人审即可能执行 |

### 9.3 沙箱手段（已实测有效）

| 手段 | 实测结果 |
|---|---|
| `Context.setClassShutter(...)` | ✅ **有效**：`Class.forName` / `Packages.*` / `new File()` **全部被拦**，白名单内正常放行 |
| `setInstructionObserverThreshold` + `ContextFactory.observeInstructionCount` | ✅ **有效**：`while(true){}` 在 **1002ms 后**被中断 |
| `setApplicationClassLoader(...)` | ✅ API 存在（Rhino 1.9.0） |

---

## 10. 改造落点（评估）

> 原则：遵循 fork 约定「能加新文件就不改上游文件」。

| # | 改动 | 位置 | 性质 | 状态 |
|---|---|---|---|---|
| 1 | **注入真 Context** | `JsExecutor.kt` | 改上游（~5 行） | ✅ **已完成**（2026-09-21） |
| 2 | **换 `ImporterTopLevel`** | `JsExecutor.kt` | 改上游（~1 行） | ✅ **已完成**（2026-09-21） |
| 3 | **绑 ClassLoader** | `JsExecutor.kt` | 改上游（~1 行） | ✅ **已完成**（2026-09-21） |
| 4 | **`console` 对象** | 新建文件 | 新增 | ⏸ 未做 |
| 5 | **沙箱 + 超时** | 新建文件 | 新增 | ⏸ 未做（**建议优先**，见下） |
| 6 | **来源分级** | `JsModule` / `ScriptedModule` | 改上游（小改） | ⏸ 未做 |
| 7 | **Shizuku keep 规则修正** | `proguard-rules.pro:97` | 改（2 行） | ⏸ 未做 |

> ⚠️ **第 5、6 项在第 1–3 项完成后变得更重要**：Java 互操作放开后，
> 从远程仓库下载或 AI 生成的脚本可触达任意 Java 类。**1–3 是「让能力可用」，
> 5–6 是「让能力可控」，两者应视为一批。**

### 10.1 已实施改动（2026-09-21）与验证记录

**改动位置**：`app/src/main/java/com/chaomixian/vflow/core/execution/JsExecutor.kt` 的 `execute()` 头部，三处：

```kotlin
// ① 绑 ClassLoader
context.setApplicationClassLoader(executionContext.applicationContext.classLoader)

// ② 换 ImporterTopLevel（替代 initStandardObjects）
val scope = ImporterTopLevel(context)

// ③ 注入真实 Context（替代空壳 JS 对象）
ScriptableObject.putProperty(scope, "context",
    Context.javaToJS(executionContext.applicationContext, scope))
```

**验证记录**：

| 阶段 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ 无错误 |
| `:app:testDebugUnitTest --tests "*ChatAgentToolingTest*"` | ✅ BUILD SUCCESSFUL |
| 本机 Rhino 1.9.0 复现初始化序列 | ✅ `importClass` 可用、`context.getPackageName()` 返回包名、原有 `inputs`/`vflow` 注入未破坏 |
| `assembleRelease` | ✅ BUILD SUCCESSFUL（3m26s），**无签名文件警告** |
| APK 签名 | ✅ `CN=vFlow Fork, OU=Fork, O=nightking8342` |
| **真机（Xiaomi MIX Fold 3）** | ✅ 六项全通（见下表） |

**真机实测结果**（`arm64-v8a` release APK）：

| 探针 | 结果 |
|---|---|
| `context.getPackageName()` | ✅ `com.chaomixian.vflow` |
| `importClass(java.util.ArrayList)` | ✅ `size=0` |
| `context.getSystemService("notification")` | ✅ 非 null |
| `context.getContentResolver()` | ✅ 非 null |
| `typeof inputs / vflow / sys` | ✅ 均为 `object`（**回归通过**） |
| `java.lang.Class.forName("java.lang.System")` | ✅ OK |

### 10.2 一个已确认的 release 缺陷

`app/proguard-rules.pro:97` 当前是：

```proguard
-keep class dev.rikka.shizuku.** { *; }
```

**包名写错了**——Shizuku 的真实包名是 `rikka.shizuku.**`（**无 `dev.` 前缀**，
`dev.rikka.shizuku` 是 Maven 坐标而非 Java 包名）。后果：**release 构建会裁剪掉 Shizuku 类**，
脚本里 `Class.forName("rikka.shizuku.Shizuku")` 报 `ClassNotFoundException`（真机已复现）。

修正：

```proguard
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
```

> 注意：**debug 构建不暴露此问题**（无 R8）。这也是 `AGENTS.md` 要求「打包一律用 release」的一个实例。

---

## 11. 一句话总结

| 问题 | 答案 |
|---|---|
| JS 能调 Android 内部 API 吗 | ✅ **能**（实测 `Class.forName` + 反射可达） |
| 之前为什么用不了 | 缺 `importClass`、`context` 空壳、ClassLoader 未绑 —— **三件小事，已修复**（§10.1） |
| Lua 也这样吗 | ❌ **不**，Lua 一直就注入了真 Context（`LuaExecutor.kt:36`） |
| 能 100% 达到 ShortX 吗 | ❌ **不能，约 80%**。Hook / 进程注入 / SystemUI 是 Xposed 独有 |
| 流体云能实现吗 | ✅ **能**，七项功能六项纯脚本可做（§6） |
| 需要新模块吗 | ❌ **多数不需要** —— `shell_command` + `service call` 是通用逃生舱（§6.3） |
| 还有什么没做 | ⚠️ **沙箱与来源分级（§10 第 5、6 项）** —— Java 互操作已放开，需配套管控 |

---

## 附：本机实测的复现方法

```bash
# Rhino jar（来自 Gradle 缓存）
RHINO=/c/Users/WHY/.gradle/caches/modules-2/files-2.1/org.mozilla/rhino/1.9.0/*/rhino-1.9.0.jar

# 复现 §4.1 的语言层探针、§4.2 的作用域差异、§9.3 的沙箱验证
# 测试代码要点：
#   Context cx = Context.enter();
#   cx.setOptimizationLevel(-1);          // 与 JsExecutor.kt:31 一致
#   Scriptable scope = cx.initStandardObjects();   // 与 JsExecutor.kt:34 一致
#   cx.evaluateString(scope, probe, "p", 1, null);
```

> ⚠️ 本文所有行号基于走查时的 `dev` 分支。**引用前请以代码为准。**
> Rhino 实测结论锚定 `org.mozilla:rhino:1.9.0`，升级 Rhino 后需重测。

## 附：修订记录

- **v1.0**（2026-09-21）：初稿。基于一次围绕「用脚本实现 ShortX 流体云」的完整探讨整理。
  含本机 Rhino 实测（语言层可达性、作用域差异、沙箱有效性）。
  记录了三处此前讨论中的误判修正：① 「vFlow 未暴露 Packages」→ 实测已暴露；
  ② 「vFlow 的脚本都没有 Context」→ Lua 有、JS 没有；
  ③ 「小窗必须走 Core 加模块」→ 可用 `service call` 纯脚本绕过。
- **v1.1**（2026-09-21）：**JS 侧三项注入已实施并真机验证**（`JsExecutor.kt`）。
  §0 结论、§1 对照表改为当前状态；§4.3 保留改动前分析并加历史标注（说明差异来源）；
  新增 §10.1「已实施改动与验证记录」（含真机六项探针结果与 APK 签名校验）；
  §10 落点表加状态列；§11 总结表补「已修复」「还有什么没做」两行。
  同步登记到 `FORK.md`（冲突归属：手动合并）。
