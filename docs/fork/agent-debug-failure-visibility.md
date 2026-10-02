# Agent 调试的失败可见性改造方案

> 状态：**待实施**（方案已定稿，待子代理审查）
> 起因排查：2026-10-02 会话。设计依据来自源码直读，所有行号均为当时 HEAD（`d6f427a9`）。

---

## 1. 问题

### 1.1 现状（已核实）

`WorkflowExecutor` 失败时会在广播终止状态**之前**弹一个阻塞式错误弹窗：

```
WorkflowExecutor.kt:693   uiService?.showError(...)        ← suspend，挂起
WorkflowExecutor.kt:707   val fullLog = ...
WorkflowExecutor.kt:715   postState(ExecutionState.Failure)  ← 才广播
WorkflowExecutor.kt:723   return null                        ← 才终止
```

`ExecutionUIService.showError`（`:328`）末尾是 `startActivityAndAwaitResult(...).await()`（`:344`），
那个 `deferred` 只由 `OverlayUIActivity.complete()` / `cancel()`（`:828-838`）完成。

⇒ **用户不点弹窗，`postState(Failure)` 永远不执行**，该协程的 `finally`（`:270-339`）也永不执行。

### 1.2 两个后果

**后果 A：结果取决于用户是否点了弹窗**

- 用户点了 → `Failure` 广播（带 `detailedLog`）→ Agent 与卡片都拿到完整错误 ✅
  （真机证据：`.mindfs/upload/2026-10-02/Screenshot_...png`，卡片内 `Execution log:` 段含
  `E/WorkflowExecutor: 模块执行失败: ...` 与 `脚本错误（第 3 行第 0 列）：正则表达式不可用。`）
- 用户始终没点 → 临时工作流在 120 秒后超时（`ChatAgentModuleExecutor.kt:1984`），
  Agent 只拿到硬编码的 `"...timed out."`（`:2018`），**错误详情从未广播过** ❌

**后果 B：`SKIP` 策略被误报为成功**

`ChatAgentModuleExecutor.kt:2765`：

```kotlin
val status = if (terminalState is ExecutionState.Finished) SUCCESS else ERROR
```

某步失败但策略为 `SKIP` 时，工作流继续跑完 → 终态是 `Finished` → **卡片显示
`completed successfully.`**，而失败只在 `detailedLog` 里。Agent 与用户**同时**被误导。

### 1.3 附带问题（本次不在范围）

协程永久挂起导致 `finally` 不执行 ⇒ `WorkflowExecutionWakeLockManager.release()`
（`:276`）与 `workDir.deleteRecursively()`（`:280`）不执行、状态不广播、工作流不摘除。
影响 `WorkflowExecutor.execute` 的**全部 13 个**调用点（已核实：13，不是 15），
含 `TriggerExecutionCoordinator`（触发器，后台）与 `ExecutionManager`（远程 API）——
这两条连 `maxExecutionTime` 兜底都没有（`Workflow.kt:31` 默认 `null`）。

⚠️ **但 Agent 场景不受此项影响**：临时工作流有外层 `withTimeout`（`:1984`），
120 秒后走 catch 返回，**弹窗从未阻塞 Agent 的兜底**。故「删弹窗会让问题更容易暴露」
这个推论**对 Agent 不成立**。

⚠️ **更隐蔽的一条**：`OverlayUIActivity.onDestroy()`（`:856-862`）**没有兜底
complete/cancel**，只清 speech dialog。若 Activity 因进程死亡/配置变更被销毁，
`inputCompletable` **永不完成**（比「用户不点」更难排查）。

**根治手段是让 `showError` 非阻塞**，但那是独立改动，需单独评估。

---

## 2. 设计决策

### 决策 1：Agent 场景**删除**弹窗，而非改为非阻塞

理由：Agent 调试时用户在看聊天卡片，卡片已承载同一份文本
（`ChatScreen.kt:1761` 渲染 `message.content`；`ChatCompletionClient.kt:40`
交给模型的是 `message.content.ifBlank { toolResult.outputText }` —— **同源**）。
弹窗是重复报告，且会盖在卡片上。

### 决策 2：用 **`execute()` 参数**识别 Agent 调用，不用 `Workflow` 字段

备选是给 `Workflow` 加字段（形态照 `silentExecution`）。**否决**，理由：

`buildWorkflowForSave`（`ChatAgentModuleExecutor.kt:262-301`）会把临时工作流**存为正式工作流**。
当前它**重新构造**了一个 `Workflow`（`:286-297`，带全默认值），所以字段不会泄漏 ——
但这是**巧合而非结构保证**，一旦有人改成 `.copy()` 就会静默泄漏，后果是用户保存的正式工作流
**从此不再弹错误弹窗**且无任何提示。

「这次执行是 Agent 发起的」是**某一次调用**的属性，不是工作流的属性 ⇒ 放参数里，
结构上不可能泄漏。

### 决策 3：`SKIP` 的判据是 **SKIP 专属日志行**，不是失败行

`ExecutionState.Failure`（`ExecutionStateBus.kt:35-40`）只有 `stepIndex` + `detailedLog`，
**没有**模块名与错误原因。故不扩展 `ExecutionState`，改为解析日志文本。

⚠️⚠️ **但「日志含失败行」不能用来判定 SKIP**（初版方案的错误，子代理审查抓出）。
根因如下：

`WorkflowExecutor.kt:613` 的重试循环里 `finalResult` **每轮被重新赋值**，
`:627-629` 任一次成功就 `break`；`:641` 的 `when` **只在最终结果上执行**。所以：

| 场景 | `E/模块执行失败`（`:649`） | 结果 |
|---|---|---|
| 直接失败（STOP/RETRY 耗尽） | **会打** | 判失败 ✓ |
| `RETRY` 重试 N 次后**成功** | **不会打**（走 `:642` 分支） | — |
| `RETRY` 重试途中**抛异常** | 每次尝试都打 **`E/模块执行异常`**（`:623`） | ⚠️ 日志里有 E 行但最终成功 |

⚠️ 最后一行是陷阱：**重试后成功的正常执行，`detailedLog` 里也会留下 `E/` 开头的行**。
若判据是「含 E 行」，就会把正确执行误判成失败。

**因此判据锚点必须是 SKIP 专属行**（`WorkflowExecutor.kt:652`）：

```
[HH:mm:ss.SSS] W/WorkflowExecutor: 根据策略，跳过错误继续执行。
```

它由 SKIP 分支自己打，**只在最终 Failure 且策略为 SKIP 时出现**，
重试成功后不会出现，是唯一可靠的锚点。

### 决策 4：失败摘要**必须在截断前**从原始 `detailedLog` 提取

`ChatAgentModuleExecutor.kt:2801-2805` 把 `detailedLog` 经 `truncateMultiline`
（`:2863-2870`，**取开头 4000 字符**）后拼接。而失败行写在执行**末尾** ——
工作流越长、失败越靠后，越可能落在 4000 字符之外被截掉 ⇒ 摘要取到 `null` ⇒
**静默退化成现有文案**。

⇒ `extractFailureSummary` 必须作用在 `terminalState.detailedLog`（**未截断**）上，
先抽取、后截断。

---

## 3. 改动清单

### 3.1 `WorkflowExecutor.kt`（5 处）

**① `execute()` 签名**（`:133-138`）加带默认值参数：

```kotlin
fun execute(
    workflow: Workflow,
    context: Context,
    triggerData: Parcelable? = null,
    triggerStepId: String? = null,
    showErrorDialog: Boolean = true,        // ← 新增
): String
```

⚠️ 必须带默认值 —— 其余 14 个调用点不改。

**②③ 透传**（`:233` 与 `:255`）：

```kotlin
executeWorkflowInternal(workflow, initialContext, executionInstanceId, showErrorDialog = showErrorDialog)
```

⚠️ `:233` 在 `withTimeout` 内（`:229-234`）、`:255` 在 `else` 分支（超时未配置时）。

**④ `executeWorkflowInternal()` 签名**（`:446-451`）追加同名参数，**带默认值 `= true`**。

⚠️⚠️ **`executeWorkflowInternal` 共有 3 个调用点，不是 2 个**（已核实）：
`:233`、`:255`、以及 **`:376`（在 `executeSubWorkflow` 内）**。
第 3 处**刻意不传**（走默认值 `true`）—— 子工作流是「被调方」，
它的 Agent 归属应继承调用方，且 `executeSubWorkflow` 是 public API，加参数会扩大 diff 面。
默认值 `= true` 保证它行为不变。

⚠️ **这个默认值是必需的**：若 `executeWorkflowInternal` 的参数不带默认值，
`:376` 会编译失败；若实施者「顺手」也给它传 `false`，则**子工作流在任意场景下都不弹窗**——
那是个静默的行为变更。

**⑤ 失败分支**（`:687-704`）加判断：

```kotlin
if (showErrorDialog) {
    try {
        val localizedAppContext = LocaleManager.applyLanguage(...)
        val uiService = initialContext.services.get(ExecutionUIService::class)
        uiService?.showError(...)
    } catch (e: Exception) {
        DebugLogger.e("WorkflowExecutor", "显示错误弹窗失败", e)
    }
}
```

⚠️ **`uiService` 的获取与 `showError` 调用要一起放进 `if`** —— 只包住 `showError` 会留下
无意义的 `LocaleManager.applyLanguage` 调用（每次失败都白付一次语言切换开销）。

### 3.2 `ChatAgentModuleExecutor.kt`（2 处）

**⑥ 传入参数**（`:1990-1994`）：

```kotlin
val executionInstanceId = WorkflowExecutor.execute(
    workflow = workflow.workflow,
    context = appContext,
    triggerStepId = workflow.workflow.manualTrigger()?.id,
    showErrorDialog = false,                 // ← 新增：Agent 调试不弹窗
)
```

**⑦ 纯函数层**（新增，放在文件内合适位置）——**两个函数，判据不同**：

```kotlin
/** 从执行日志里取失败摘要（取最后一条）；取不到返回 null。 */
internal fun extractFailureSummary(detailedLog: String): String?

/** 日志里是否存在 SKIP 专属行（「根据策略，跳过错误继续执行。」）。 */
internal fun hasSkippedFailure(detailedLog: String): Boolean
```

⚠️ `extractFailureSummary` 匹配 `E/WorkflowExecutor: 模块执行失败: ` ——
**必须含 `E/` + tag**，不能只找 `模块执行失败`（错误消息本身可能含这四个字）。
⚠️ `hasSkippedFailure` 匹配 `W/WorkflowExecutor: 根据策略，跳过错误继续执行。`
（**不是** `E/模块执行失败`，理由见决策 3）。
⚠️ **逐行扫描、遇行首前缀才取** —— `DebugLogger.e`（`:147-150`）会在 message 后追加
`\n` + 堆栈，堆栈里若出现同样前缀会误匹配。不要对整个 `detailedLog` 做 `substringAfterLast`。
⚠️ 两个函数都必须由调用方**传入未截断的 `terminalState.detailedLog`**（见决策 4）。
⚠️ 抽成 `internal` 便于纯 JVM 单测，不写成私有方法。

### 3.3 `buildTemporaryWorkflowResult`（`:2761-2809`）

⚠️ **契约：抽成的纯函数返回 `ChatToolResult` 本身（`status` **与** `outputText` 一起产出），
不是只返回 `ChatToolResultStatus`。** 若只返回状态，`outputText` 的构造留在原地，
则真机截图里最有价值的 `Execution log:` 段会在重构中丢失，且**静默**（卡片仍是 ERROR，
只是没有详情）—— 与本次改造目的相反。

**⑧ 失败摘要提到最前**：`Failed` 分支下，把 `extractFailureSummary()` 的结果插在首行之后。

**⑨ `SKIP` 误报修复**：判据用 `hasSkippedFailure(detailedLog)`（**不是**「日志含 E 行」）。
命中时状态降级为 `ERROR`，首行改为说明「有步骤失败但按策略跳过」。

推荐形态：

```
Temporary workflow `X` completed, but 1+ step(s) failed and were skipped.
<失败摘要>
```

⚠️ 不要引入 `WARNING` 状态 —— `ChatToolResultStatus`（`ChatModels.kt:110-115`）只有
四值，加值要动 `ChatScreen.kt:1674-1694` 的配色表与序列化。复用 `ERROR`。

### 3.4 不做的改动（明确排除）

- ❌ 不给 `Workflow` 加字段（见决策 2）
- ❌ 不改 `ExecutionState` / `ExecutionStateBus`（见决策 3）
- ❌ 不改 `ExecutionUIService`（Agent 侧靠参数跳过，无需改服务）
- ❌ 不动其余调用点的弹窗行为（共 13 个调用点，只改 Agent 那 1 个）
- ❌ 不修「用户不点弹窗 ⇒ 工作流永久卡死」（独立改动，见 §1.3）
- ❌ 不给 `executeSubWorkflow`（`:353`）加参数 —— 它是 public API，走
  `executeWorkflowInternal` 的默认值即可（见 §3.1 ④）

⚠️ **一个已被否决的第三选项，记录以免重提**：曾考虑给 `ExecutionServices` 加 marker
（`services.add(...)`）由 `WorkflowExecutor` 查询。**经核实不可行** ——
`ExecutionServices` 是在 `execute()` **内部** `:201` 创建的，Agent 侧无法提前注入；
要支持它得先改 `execute()` 接受外部容器，那是**更大的**签名变更。
故 `execute()` 参数是正确选择，而非「凑合」。

---

## 4. 测试计划

### 4.1 纯 JVM 单测（新增）

**`extractFailureSummary`**：

| 用例 | 断言 |
|---|---|
| 真机截图里的真实日志 | 提取出 `该功能执行失败 - 系统进程执行该功能时报错...` |
| 多步失败 | 取**最后**一条 |
| 无失败行 | 返回 `null` |
| 错误消息里含「模块执行失败」字样 | **不误匹配**（反向断言，锁完整前缀） |
| 日志含 `E/模块执行异常`（重试途中） | **不被当成**摘要来源，或明确其行为 |
| 空串 / 只有换行 | 返回 `null`，不抛 |

**`hasSkippedFailure`**：

| 用例 | 断言 |
|---|---|
| 含 `W/根据策略，跳过错误继续执行。` | `true` |
| 只含 `E/模块执行失败`（STOP 场景） | **`false`**（反向断言，这是本判据存在的理由） |
| 含 `E/模块执行异常`（RETRY 重试后成功） | **`false`**（反向断言，防误报） |
| 空串 | `false`，不抛 |

**`buildTemporaryWorkflowResult`**（抽纯函数，**返回 `ChatToolResult`**）：

| 用例 | 断言 |
|---|---|
| `Finished` + 含 SKIP 行 | status == `ERROR`，首行含「failed and were skipped」，**且 `outputText` 仍含 `Execution log:` 段** |
| `Finished` + 无 SKIP 行 | status == `SUCCESS`，文案不变 |
| `Failure` + 有摘要 | 摘要出现在**步骤清单之前** |
| `Failure` + 无摘要 | 退化为现有文案，不崩 |
| 长日志（> 4000 字符）且失败在末尾 | **摘要仍能取到**（断言作用在未截断的 `detailedLog` 上） |

### 4.2 源码扫描型测试（必需）

⚠️ **本仓库已三次踩过「纯函数单测全绿但调用点缺失」**（`CoreDexFingerprint`
的 13 例、`XposedDiagnostics.messageFor` 的零调用点）。故必须锁住：

1. `ChatAgentModuleExecutor` 调用 `WorkflowExecutor.execute` 时**真的传了** `showErrorDialog = false`
2. `WorkflowExecutor` 的失败弹窗调用被 `showErrorDialog` 条件包裹

⚠️ **形态照 `TriggerServiceXposedNoticeWiringTest`**（那才是纯源码扫描的先例；
`CoreDexFingerprintTest` 是「源码扫描 + 纯函数」混合）：

- 相对路径 `File("src/main/java/...")`（Gradle test 工作目录 = `app/`）
- ⚠️⚠️ **必须剥注释与字符串字面量**：本方案要在失败分支加 `showErrorDialog` 字样，
  而**文档与注释里会出现大量该字样**。若只做 `text.contains("showErrorDialog")`，
  **把整个 `if` 删掉改回无条件调用后测试照样绿**（`TriggerServiceXposedNoticeWiringTest`
  明确记过这条教训）。
- ⚠️ **按大括号配对截取函数体/失败分支**，不绑定具体行号（行号会随上游漂移）
- ⚠️ **防空转断言**（≥N 文件、剥注释后仍有代码行）
- ⚠️ 断言「**文件中所有 `executeWorkflowInternal(` 调用都在透传路径上**」，
  而不是「`:233` 与 `:255` 两处」—— 后者会把 `:376` 固化成不受监控的盲区

### 4.3 验证门禁

- `./gradlew test`（app 模块）。⚠️ 已知既有失败：`VObjectPropertyTest > test VFile
  properties from absolute path`（`android.net.Uri.parse` 未 mock），排除它。
  ⚠️ **基线数字按当前 worktree 重取**（AGENTS.md 记录 `dev` 基线 1356 例、
  含未合入改动可到 1427 例）。
- `./gradlew assembleRelease`（改动涉及业务逻辑，release 才能暴露 R8 问题）。

### 4.4 真机验证

1. Agent 调试一个必然失败的临时工作流 → 确认**无弹窗**、卡片**立即**（非 120 秒后）
   出现失败详情
2. 手动跑一个必然失败的正式工作流 → 确认**弹窗仍在**（回归检查）
3. 构造一个含 `SKIP` 策略失败步骤的临时工作流 → 确认卡片**不再显示成功**

---

## 5. 风险与登记

| 项 | 说明 |
|---|---|
| **FORK.md 登记（必需）** | `WorkflowExecutor.kt` 已有登记条目，**追加**本次改动（签名 + 两处透传 + 失败分支判断）；`ChatAgentModuleExecutor.kt` 已有登记条目，追加 |
| **无分歧** | `ChatAgentModuleExecutor.kt` 的 3 处 `ExecutionServices` 注入点**都不动**（Agent 侧靠参数识别，不靠不注入） |
| **上游合并** | `WorkflowExecutor.kt` 是核心敏感文件。签名变更是「加带默认值参数」，上游若改同区域需逐块判断 |
| **行为变更** | 正式工作流的弹窗行为**不变**；只有 Agent 场景删除弹窗 |
| **已知残留** | §1.3 的永久卡死未修 |

---

## 6. 审查结论（2026-10-02 子代理独立核实，已回源码验证）

### 已修正的错误

| # | 初版断言 | 核实结果 | 处置 |
|---|---|---|---|
| 1 | `execute()` 调用点 **15** 个 | ❌ **实际 13** | 已改（§1.3 / §3.4） |
| 2 | `executeWorkflowInternal` 调用点 **2** 处 | ❌ **实际 3**（漏 `:376`） | 已改（§3.1 ④） |
| 3 | SKIP 判据 = 日志含**失败行** | ❌ **判据错**，会误报 | 已改为 **SKIP 专属行**（决策 3） |
| 4 | 摘要从日志提取，未定作用对象 | ❌ 会落在 4000 字符截断区 | 已定契约：**截断前**（决策 4） |
| 5 | 纯函数返回类型未定 | ❌ 若只返回 status 会**静默丢详情** | 已定为返回 `ChatToolResult`（§3.3） |

### 核实为「成立」的初版判断

- ✅ 弹窗阻塞链路（`:693` 早于 `:715`；`ExecutionUIService.kt:344 await()`；
  `OverlayUIActivity.kt:828-838` 是唯一 complete 路径）
- ✅ `buildWorkflowForSave`（`:286-297`）**重新构造**而非 `copy()` ⇒ 否决加字段成立
- ✅ `ChatToolResultStatus` 四值（`ChatModels.kt:110-115`）
- ✅ 卡片与模型**同源**（`ChatScreen.kt:1761` / `ChatCompletionClient.kt:40`）
- ✅ `load_skill` 那类「不可截断」工具与本次无关

### 一处初版误判

初版 §6.3 推断「Agent 侧删弹窗后，非 Agent 的卡死会更容易暴露」—— **不成立**。
临时工作流有外层 `withTimeout`（`:1984`），120 秒后走 catch 返回，
**弹窗从未阻塞 Agent 的兜底**。故本改动**不会恶化**任何现有问题。

### 补充记录的真问题（本次不修）

`OverlayUIActivity.onDestroy()`（`:856-862`）**没有兜底 complete/cancel** ——
Activity 因进程死亡/配置变更被销毁时，`inputCompletable` **永不完成**。
这比「用户不点」更隐蔽，建议在后续的非阻塞改动里一并处理。
