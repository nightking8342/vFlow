# 工作流日志等级（日志详细度）设计

**状态**：设计定稿，待实现 ｜ **日期**：2026-10-04
**上位文档**：`docs/fork/log-module-design.md`（日志模块本体）。
本文只设计**元数据里的「日志等级」选项**，它是那个模块的**总闸**。

---

## 1. 需求

在工作流「更多选项」里加一个**日志等级**下拉，运行时**低于该级别的日志不打印**。
⇒ 即使工作流里放了日志模块，也能一键把日志关掉。

---

## 2. ⚠️ 先说清它**管不到**什么（否则会做成误导性的开关）

这一节是本设计的核心。写之前逐条 grep 核实过（数字见 §8）。

### 2.1 它只管「执行日志」，管不到 logcat 与崩溃上报

| 通道 | 是否受本开关影响 | 说明 |
|---|---|---|
| `detailedLog`（首页「最近日志」看到的） | ✅ **受** | 这就是本开关的作用对象 |
| 交给 Agent 的临时工作流日志 | ✅ **受**（同源） | |
| **设置页「导出调试日志」** | ❌ **不受** | ⚠️ 见下 —— 它是**两处不一致**的来源 |
| 超级岛 / 执行通知的实时状态 | ❌ 不受 | 见下 |
| logcat（`adb logcat`、logcat 调试工具、logcat 触发器） | ❌ 不受 | `DebugLogger` 仍会 `android.util.Log.d/i/w/e` |
| 崩溃上报（`CrashReportManager.recentLogs`） | ❌ 不受 | 读的是 `DebugLogger.crashBuffer` |

#### ⚠️ 「导出调试日志」为什么**也**不受影响（刻意，但代价要认）

设置页那个导出（`SettingsRoute.kt:72` → `DebugLogger.getLogs()`）的内容是
**设备信息头 + `DebugLogger.logBuffer`（内存，上限 5000）+ shell 日志文件**。
执行器的每一条日志都经由 `GlobalDebugLogger.*` 进了 `logBuffer`
⇒ **导出里含完整的、未经过滤的工作流日志**。

⚠️ **前提**：`logBuffer` 只在设置里打开「调试日志」开关时才累加
（`DebugLogger.recordLog` 里的 `if (isLoggingEnabled)`）。开关没开 ⇒ 导出里
**一条 app 日志都没有**，只剩设备信息头与 shell 日志。这是既有行为，与本开关无关。

**为什么刻意不过滤它**：排障时需要的正是**全量证据**。
用户把某个工作流设成「仅错误」，往往是嫌它吵 —— 但它一旦出问题，
我们第一件事就是让对方导出调试日志。那张闸如果也管这里，
「日志太吵所以我关掉了、于是故障现场也没了」就成了必然结局。

⚠️⚠️ **由此产生的一处不一致，必须知道**：
「仅错误」档下，**首页「最近日志」里没有 `D` 级上下文，而设置页导出的调试日志里有**。
这不是 bug，是本设计的取舍（§4.2 的过滤点位置就是为此定的）。
但它会让第一次遇到的人以为是缺陷 ⇒ **真机验证时要专门确认这一点**，
别把「导出里有 D 行」当成过滤失效。

⚠️ **通知侧不受影响（已逐行核实，不是推断）**：模块进度在 `WorkflowExecutor.kt:723-726`
是**两次独立调用**：

```kotlin
DebugLogger.d("WorkflowExecutor", "[进度] ${module.metadata.name}: ${progressUpdate.message}")  // ← 被本开关过滤
if (!isSubWorkflow) {
    ExecutionNotificationManager.updateState(workflow, …Running(progress, progressUpdate.message))  // ← 不受影响
}
```

⇒ 「仅错误」档下：首页日志里没有 `[进度]` 行了，但**超级岛照常显示模块实时状态**。
这是好的结果（降噪不影响实时性），但**必须在文案或文档里说清** ——
否则用户会以为「日志等级也管通知」，去调错东西。

⚠️ **为什么不做成「全局静音」**：`DebugLogger` 是**全局单例**，
它的 `recordLog` 同时喂 `crashBuffer`（上限 200 条，崩溃上报要它）。
在那里加等级过滤 ⇒ 崩溃报告会丢失全部 D 级上下文，**故障定位能力下降**。
⇒ 本开关的作用面**只到「本次执行的 detailedLog」**，实现位置也放在那里（§4）。

⚠️ **文案必须说清**（否则用户会以为「我把日志关了」然后疑惑 logcat 里为什么还有）：
落地文案为 **「只影响本次执行的日志。超级岛与执行通知的实时状态、崩溃报告、系统日志不受影响。」**

### 2.1.1 ⚠️ 它**不是**一道「数据不外泄」的边界

用户提出这个需求的动机是「即使里面有日志模块，也能把它关掉」。**降噪目标达成**，
但有一条**旁路它管不到**（详见 `log-module-design.md` §7 未决项 5）：

脚本用 `vars_api.setGlobalVar("k", 值)` 把值存成**命名/全局变量**之后，
任何步骤都能用 `[[k]]` / `{{vars.k}}` 读回来 —— 这条路**不经过日志**，
也不在 AST 里出现任何「日志」字样，**本开关与静态扫描都堵不住**。

⇒ **如实定位**：本设计解决的是**日志体积与噪音**，不是「值能不能离开工作流」。
把两件事混同会做出一个既挡不住、又碍事的开关。

### 2.2 它**做不到**「保留报错、去掉噪音」—— 因为噪音就是 `D`

逐级别统计 core 模块（`core/**/*.kt` 里 `DebugLogger.d/i/w/e` 的调用）：

| 级别 | 调用数 | 典型内容 |
|---|---|---|
| `D` | **240** | 步骤切换 `[#3] -> 执行: 延迟`、模块进度 `[进度] …`、坐标解析、OCR 选项 |
| `W` | **158** | 跳过错误继续执行、重试、模块未找到、**循环找不到起点** |
| `E` | **99** | **模块执行失败**、执行异常、超时、参数解析失败 |
| `I` | 86 | 重入行为、服务事件 |

⇒ **`D` 既是最吵的、也是唯一能砍的**（砍 `W`/`E` 会丢关键信息，见 §3 的默认值）。
⇒ 本开关的真实名字应该叫**「日志详细度」**而不是「日志等级」——
它实际只有两个有意义的档：**「详细（含 D）」**与**「精简（只留 W/E/I）」**。

---

## 3. 设计：四档，默认「详细」＝**与现状逐字节一致**

### 3.1 档位

| 档位 | 存值 | 保留的级别 | 效果 |
|---|---|---|---|
| **详细**（默认） | `"verbose"` | D + I + W + E | **= 当前行为** |
| 精简 | `"normal"` | I + W + E | 去掉步骤切换与模块进度（约 -240 处调用的产出） |
| 仅警告与错误 | `"warning"` | W + E | 静默重入、循环异常退出等 |
| 仅错误 | `"error"` | E | 只剩失败与异常 |

⚠️ **默认必须是「详细」**：非默认值会改变**存量工作流**的日志内容，
而本项目**没有日志版本历史**，用户只会看到「升级后日志变少了」而不知为什么。

### 3.2 为什么不做 R2 的 `off` 档（用户提出「把日志模块关掉」）

用户的原始诉求是「即使有日志模块也能关掉」。**「仅错误」档已经达成这个效果**
（日志模块默认打 `D`，被 `error` 档滤掉），不需要单独一档。

⚠️ 单独做 `off` 的代价（三处必须同时处理，漏一处就是**误导性开关**）：

1. `E` 级的失败行是 `ChatMessagePatch.extractFailureSummary` 的**唯一**数据源
   （`E/WorkflowExecutor: 模块执行失败: `）⇒ 真静音会让**失败卡片说不出原因**；
2. `WorkflowExecutor` 有一整类**早就只写 detailedLog、本来就不进 logcat** 的日志：
   `addReentryLog`（`:160/167`）直接调 `LogManager.addLog`，**从不过 `DebugLogger`** ——
   静音后「工作流为什么没跑」这一条**彻底查不到出处**；
3. `ExecutionLogger` 的终态记录（`LogManager.addLog`）同样不经 `DebugLogger`。

⇒ 结论：**`error` 档是下限**，它已满足「关掉日志模块」的诉求。

---

## 4. 实现

### 4.1 数据模型

`Workflow` 加一个带默认值的字段（与 `silentExecution` 同一套路）：

```kotlin
/**
 * 执行日志的详细度。默认 [WorkflowLogLevel.VERBOSE] = **与改动前逐字节一致**。
 *
 * ⚠️ 只影响本次执行的 `detailedLog`（首页「最近日志」/ Agent 拿到的日志）。
 * 不影响：超级岛与执行通知的实时状态、`adb logcat`、崩溃上报的 recentLogs。
 */
var logLevel: WorkflowLogLevel = WorkflowLogLevel.VERBOSE,
```

```kotlin
enum class WorkflowLogLevel(val storageValue: String, val rank: Int) {
    VERBOSE("verbose", 0),   // D + I + W + E
    NORMAL("normal", 1),     // I + W + E
    WARNING("warning", 2),   // W + E
    ERROR("error", 3);       // E

    /** `level` 是 `appendToLog` 的形参，取值 `"D"` / `"I"` / `"W"` / `"E"`。 */
    fun allows(level: String): Boolean = levelRank(level) >= rank
}

/** ⚠️ 未知级别**一律放行**（返回 0 = 最低）—— 宁可多记，不可漏记。 */
private fun levelRank(level: String): Int = when (level) {
    "E" -> 3
    "W" -> 2
    "I" -> 1
    "D" -> 0
    else -> 0
}
```

⚠️ **存稳定常量**（学习 `ActionChangedTriggerModule` 的 `match_mode` 教训：
存本地化文案 ⇒ 切语言后已保存的工作流失配）。解析时**兜旧值 / 未知值 ⇒ `VERBOSE`**
（向下兼容；未知值当详细，不会静默丢日志）。

### 4.2 过滤点：`WorkflowExecutor` 的内部 `DebugLogger`

**唯一落点**（`WorkflowExecutor.kt:70-104`）：

```kotlin
private object DebugLogger {
    fun d(...) { GlobalDebugLogger.d(...); appendToLog("D", ...) }
    // ...
    private fun appendToLog(level: String, tag: String, message: String, throwable: Throwable?) {
        // ⚠️ 全局 Logger 已经在上面调完了（logcat / 崩溃上报不受影响），
        //    这里只决定「要不要进本次执行的 detailedLog」。
        if (!currentLogLevel.allows(level)) return
        // …原有实现
    }
}
```

#### 等级从哪来（结构上已经解决了，不必新造机制）

`appendToLog` 现有的第一行就是 `val workflowId = currentRootWorkflowId.get() ?: return`
（`:95`）—— **查表拿等级即可**：

```kotlin
private val logLevelsByWorkflow = ConcurrentHashMap<String, WorkflowLogLevel>()

private fun appendToLog(level: String, tag: String, message: String, throwable: Throwable?) {
    val workflowId = currentRootWorkflowId.get() ?: return
    // ⚠️ 默认 VERBOSE：表里没有这个 id 时（如测试直接调、或登记与执行之间的窗口）
    //    必须放行，否则会静默丢日志。宁可多记，不可漏记。
    val logLevel = logLevelsByWorkflow[workflowId] ?: WorkflowLogLevel.VERBOSE
    if (!logLevel.allows(level)) return
    // …原有实现
}
```

- 写入：`executionLogs.getOrPut(workflow.id)` 那处（`:182`，执行开始）
  旁边加 `logLevelsByWorkflow[workflow.id] = workflow.logLevel`；
- 清理：`executionLogs.remove(workflow.id)` 的两处（`:179` 与 `:340-346`）**旁边一并 remove**
  —— 与既有的按 `workflowId` 的表同生命周期，不引入新的清理时机。

⚠️ **为什么不用第二个 ThreadLocal**：`currentRootWorkflowId` 是裸 `ThreadLocal`
配 `withContext(...asContextElement(value = ...))`（`:196`，import 来自
`kotlinx.coroutines.*`，即 Kotlin 为 `ThreadLocal` 提供的 `ThreadContextElement` 扩展）。
再加一个 `ThreadLocal` 就要再加一次 `withContext` 或改那次调用，**diff 更大、收益为零** ——
而 `workflowId → 等级` 本来就是一次查表。

⚠️ **必须放在 `appendToLog` 而不是 `d/i/w/e` 里**：那样 `GlobalDebugLogger` 的调用
（logcat + 崩溃缓冲）会被一起滤掉，正是 §2.1 要避免的。

⚠️ **子工作流**：`executeSubWorkflow` 走的是同一个 `initialContext` 链路、
`currentRootWorkflowId` 也仍是**根工作流** id（既有语义，ThreadLocal 只在根设置）
⇒ **子工作流的日志按根工作流的等级过滤**。这是正确且唯一合理的行为
（用户看到的是一份合并日志）。

### 4.3 UI：「更多选项」里加下拉

布局 `sheet_editor_more_options.xml` 的**配置卡片**
（`@string/workflow_config` 组，`switch_silent_execution` 之后）追加：

```xml
<TextView android:text="@string/workflow_log_level_title" ... />
<AutoCompleteTextView / 或 MaterialButtonToggleGroup>
```

- 选项文案三语齐全；**默认选「详细」**；
- 保存路径：`EditorMoreOptionsSheet` 构造 `Workflow` 时带上（与 `silentExecution` 同一处，
  `:423-438`）；
- 读取路径：绑定处（`:205-210`）读 `wf.logLevel`。

⚠️ **用下拉/toggle 而不是滑块**：四个具名档位，滑块表达不了被跳过的档。

---

## 5. 与其它特性的关系

| 特性 | 关系 |
|---|---|
| `silentExecution` | **正交**。那个管「通不通知」，这个管「日志记多细」。两者都要能独立开关 |
| 日志模块（`vflow.data.log`） | 本开关是它的**总闸**。模块的 `level` 参数（`info`/`warn`/`error`）映射到 D/W/E ⇒ 被本开关按级别过滤 |
| 临时工作流（Agent 调试） | **Agent 建的工作流默认也是「详细」** ⇒ 不受影响。⚠️ 但若用户在编辑器里改过某工作流的等级，Agent 跑它时会拿到被过滤的日志 —— 这是**正确**行为（用户的设置应当生效） |
| `save_workflow` / `update_workflow` | **本轮不给 AI 开放这个字段**。理由：AI 把它调成「仅错误」会把调试信息砍掉、而它自己正是要靠那些信息自愈；且 `update_workflow` 的 metadata 白名单越短越好 |
| 备份 / 恢复 | **自动覆盖，无需改动**。`Workflow` 是整体 Gson 序列化进 `workflow_list` 的，新字段自然跟着走。⚠️ 但**跨版本导入要能容错**：旧备份里没有这个键 ⇒ `VERBOSE`（§4.1 的解析兜底）。⚠️ `SecretFieldScrubber` **不需要**为它加条目（名字里不含 token/secret/password） |

---

## 6. 验收标准

1. **默认档 = 现状**：不设置时，同一次执行的 `detailedLog` 与改动前**逐字节一致**
   （能做的机器化验证：默认档下 `appendToLog` 对所有级别都放行，有单测）；
2. 四档各自保留的级别正确（纯函数单测，四档 × 四级 = 16 个组合）；
3. **反向锁：`GlobalDebugLogger` 的调用不得被过滤** ——
   源码扫描 `appendToLog` 的实现，断言「全局调用在过滤之前」；
4. **`W` / `E` 在任何档位下都不被丢**（`ERROR` 档仍有 `E`；`WARNING` 档仍有 `W`+`E`）——
   这是 §2.2 那条「报错永不丢失」的机器化锁；
5. 未知 / 旧值解析 ⇒ `VERBOSE`（不静默丢日志）；
6. 真机：把一个含日志模块的工作流设为「仅错误」，确认 `[日志]` 行消失、失败行仍在。

---

## 7. 交付物

| 文件 | 类型 |
|---|---|
| `core/workflow/model/WorkflowLogLevel.kt` | 新增：枚举 + `allows(level)` 纯函数 |
| `core/workflow/model/Workflow.kt` | 改：加 `logLevel` 字段（带默认值） |
| `core/execution/WorkflowExecutor.kt` | 改：`appendToLog` 加过滤（1 处）+ 等级随工作流设置 |
| `core/workflow/WorkflowJsonImportParser.kt`（**1 处**，`:125` 旁）/ `WorkflowManager.kt`（**2 处**，`:102` 的 `copy` 白名单 + `:280` 的读回） | 改：序列化往返，缺失 ⇒ VERBOSE。⚠️ **`copy` 那处漏了会静默丢设置** —— 与 `silentExecution` 同款陷阱：`copy(...)` 显式列出要保留的字段，漏一个就是「保存后设置没了」 |
| `ui/workflow_editor/EditorMoreOptionsSheet.kt` | 改：绑定 + 保存（各 1 处） |
| `res/layout/sheet_editor_more_options.xml` | 改：追加一个控件容器 |
| 三语 `strings*.xml` | 改：4 条档位文案 + 标题 + 说明（共 6 条 ×3）。⚠️ 档位名**只写档位本身**，括号说明由 `_desc` 统一交代 —— 四个按钮挤一排时括号会把每个撑成两行且互相截断（用户 2026-10-04 定） |
| `core/workflow/WorkflowPatch.kt` + `ChatAgentModuleExecutor.applyMetadataPatch` | **不动**（§5：不开放给 AI） |
| 测试 | `WorkflowLogLevelTest`（11 例，纯函数）+ `LogModuleTest` 里 2 例源码扫描（过滤点位置 + 执行器真的注入了 `logSink`） |

### 7.1 ⚠️ 与设计稿的偏差（实现期发现，如实记录）

| # | 设计稿说的 | 实际落地 | 原因 |
|---|---|---|---|
| 1 | 「更多选项」里加一个**下拉**（§4.3 标题也写着下拉） | 4 个 `ToggleButton` 的 **connected group** | 四档是**有序**的（详细 → 精简 → 仅警告 → 仅错误），排成一行能直接看出「越往右越安静」；下拉只看得到当前值。这一条是 §4.3 正文里就写了的意图，只有标题没跟着改 |
| 2 | `workflow_log_level_*` 文案共「4 条档位 + 标题 + 说明」 | 6 条（4 档 + 标题 + 说明），**英文不能用 `\'`** | aapt2 按 Java `Properties` 读资源，`\uXXXX` 是唯一合法的 hex 转义；写 `\'` 会 `Invalid unicode escape sequence`（已实际踩到）。改写措辞绕过撇号 |
| 3 | 验收 3：「源码扫描 `appendToLog`，断言全局调用在过滤之前」 | 实现为**断言过滤块里真的调了 `allows()` 且按 `workflowId` 查表** + 防空转 | 逐行比较「调用顺序」的断言**写死缩进**、上游一格式化就假红（本仓库已有同形教训，见 `MaxExecutionTimeSliderTest` 撤掉的那条）。而真正要防的失效模式是「有人把过滤挪进 d/i/w/e 里」—— 那个由「过滤**块在 `appendToLog` 函数体内**」直接锁住 |

---

## 8. 核实过的数字（§2.2 那张表的来源）

```
grep -rn "DebugLogger\.\(d\|i\|w\|e\)(" app/src/main/java/com/chaomixian/vflow/core/
  D: 240   E: 99   I: 86   W: 158
```

⚠️ 这是**调用点**计数，不是运行时行数（一次调用可能产出多行，如带 throwable 的会附堆栈）。
但它足以说明 §2.2 的结论：**D 是唯一可砍的级别**。
