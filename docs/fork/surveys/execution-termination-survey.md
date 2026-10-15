# 工作流执行终止语义调研（停止 / 覆盖取消 / 超时）

> **版本**：v1.0 · 2026-10-14
> **对应分支**：`dev`（工作区含未提交改动；行号以本文件写作时的代码为准）
> **目录归属**：fork 独有（上游无此文件）→ **冲突归我方**
> **用途**：把「工作流跑起来之后，**怎么被终止、终止到什么程度、留下什么**」固定成文。
> 改动停止 / 取消 / 超时相关逻辑前先读这份；`AGENTS.md` 的 fork 文档导航已登记。
>
> **证据类型标注**（沿用 `xposed-channel-design.md` 决策 21 的约定）：
> 【源码】= 代码直读 · 【实测】= 本机实跑/真机复现 · 【推断】= 由源码推出的结论（**未经实跑**） · 【判断】= 主观评估，非事实
>
> ⚠️ **本文档中标注【推断】的条目，落笔为结论前必须真机复现**（见 §9.4）。
> 这是本仓库的既有教训：写进文档的库行为断言，先实跑再落笔。

---

## §0 一句话结论

1. **「停止工作流」有两种完全不同的机制**：`当前` = 在**步骤边界**结束（不打断任何东西）；`指定` = 真正的 `job.cancel()`（协作式取消）。把两者当成同一件事，是理解这条链路最大的障碍。
2. **协作式取消打不断阻塞调用**。shell/Core 命令、OkHttp 阻塞 `execute()`、JS/Lua 脚本（`runBlocking` 桥）、纯计算 —— 这些步骤对「覆盖 / 手动停止 / 超时」**全部免疫**，只能等它自己跑完。
3. **超时的口径最乱**：通知说「失败」、状态总线说「取消」、编辑器当「正常结束」、日志说「超时」，且**不带步骤索引** ⇒ 用户无法定位卡在哪一步。

---

## §1 总览：三种终止机制（不是一种）

| 机制 | 用户入口 | 代码入口 | 终止粒度 | 能打断"正在跑的那一步"吗 | 通知 | 状态总线 |
|---|---|---|---|---|---|---|
| **停止工作流 → 当前** | `vflow.logic.stop_workflow`（`target=current`） | `StopWorkflowModule.kt:101-104` 返回 `Signal(Stop)` → `WorkflowExecutor.kt:906-910` | **当前这一次执行的步骤循环** | ❌ **不能**（它本身就是一步，见 §2.1） | `Completed("执行完毕")` | `Finished` |
| **停止工作流 → 指定** | 同上（`target=other` + `workflow_id`） | `StopWorkflowModule.kt:115-122` → `WorkflowExecutor.stopExecution` | 该 `workflowId` 的**全部已登记实例** | ⚠️ 仅"可取消点"（§3.2） | `Cancelled("已停止")` | `Cancelled` |
| **覆盖**（重复触发） | 工作流「重复触发」= 覆盖 | `WorkflowExecutor.kt:208-214` → `stopExecution` | 同上 | ⚠️ 同上 | 同上 | 同上 |
| **手动停止** | 通知栏「结束」/ 悬浮窗 / 首页 | `WorkflowActionReceiver.kt:24` 等，同一个 `stopExecution` | 同上 | ⚠️ 同上 | 同上 | 同上 |
| **超时** | 「更多选项」→ 最大执行时长 | `WorkflowExecutor.kt:307-347`（`withTimeout`） | 当前这一次执行 | ⚠️ 同上 | `Failed("执行超时（N秒）")`，**常驻** | `Cancelled` |

> **后四种走的是同一条 `stopExecution` → `job.cancel()`**（`WorkflowExecutor.kt:163-169`）。
> 也就是说：**只有「当前」那一种是"另一种机制"**，其余全部同源。

### 三条推论（记住这三条能省掉大部分排查）

- **`isRunning()` 只认顶层执行**：`registerExecution` 只在 `execute()` 里调（`WorkflowExecutor.kt:453`），子工作流不登记（§2.4）。
- **状态与日志按 `workflowId` 键控，但执行是 per-instance 的**（`executionInstanceId = "${workflow.id}_${UUID}"`，`:241`）⇒ 并发/覆盖场景下会串扰（§3.4）。
- **没有任何机制能"从外部打断一个正在阻塞的步骤"**（§3.3）。

---

## §2 「停止工作流」模块

### §2.1 `target=current`：Signal(Stop)，不是取消

【源码】模块不取消任何东西，只**返回一个信号**：

```
StopWorkflowModule.kt:101-104   →  ExecutionResult.Signal(ExecutionSignal.Stop)
WorkflowExecutor.kt:906-910     →  stoppedWorkflows[executionInstanceId] = true
                                   pc = workflow.steps.size      // 程序计数器越界
```

由此得出三个**必然**行为：

1. **它不可能打断"当前步骤"** —— 因为它自己就是一个步骤，只有当执行流走到它时才生效。**放在「延迟 60 秒」后面，那 60 秒必须等完**。
2. **它不能当看门狗**：长任务在它之前，它永远轮不到执行。想限时只能用 `maxExecutionTime`（§4），而后者同样打不断阻塞步骤。
3. **在循环体里执行 Stop，停的是整个工作流，不是跳出循环**（`pc = steps.size` vs `Break` 信号走 `findEndBlockPosition`，`:879-889`）。想只跳出循环要用「跳出循环」模块。

### §2.2 `target=other`：真取消

```
StopWorkflowModule.kt:115      →  if (!WorkflowExecutor.isRunning(workflowId))  → 报"未在运行"，什么都不做
StopWorkflowModule.kt:122      →  WorkflowExecutor.stopExecution(workflowId)
WorkflowExecutor.kt:163-169    →  runningWorkflows[workflowId] 里每个实例 job.cancel()
```

**这是唯一能从外部打断一个正在运行的执行的手段**（另一个实例 / 另一个触发器 / 通知栏按钮都走它）。但它照样打不断阻塞步骤。

### §2.3 状态口径：Stop 报"执行完毕"，取消报"已停止"

| 路径 | 通知（`ExecutionNotificationManager`） | 总线（`ExecutionStateBus`） |
|---|---|---|
| Stop 信号 | `Completed("执行完毕")`（`WorkflowExecutor.kt:368-370`） | `Finished`（`:431-439`，注释明写"正常结束或 Stop 信号都视为 Finished"） |
| `job.cancel()` | `Cancelled("已停止")`（`:373-376`） | `Cancelled`（`:423-430`） |

⚠️ **这个区分是有价值的，不建议抹平**：`Stop` 的语义是"我要在这里收工"（正常结束），`cancel` 是"被中止"。
但**名字在误导**：叫「停止工作流」会让人以为它能"中止"某个正在跑的东西（见 §7 决策 2、§8 开放问题 5）。

### §2.4 三个边界

| 场景 | 行为 | 依据 |
|---|---|---|
| **Stop 在子工作流里** | **只停子工作流**，父工作流继续（拿到的返回值是 `null`） | `executeSubWorkflow` 把 `executionInstanceId` 传成**子工作流自己的 id**（`:487-492`），`Signal(Stop)` 由最内层那个 `executeWorkflowInternal` 的循环消费（`:906`），**不向调用方传播**；`CallWorkflowModule` 只读 `subResult.returnValue` |
| **并行实例（`ALLOW_PARALLEL`）** | 各有独立 `executionInstanceId`（`:241`）⇒ A 里的 Stop **不影响** B | `stoppedWorkflows` 按实例 id 键控（`:58`、`:399`） |
| **「指定」指向子工作流** | `isRunning(子id)` 恒 false ⇒ 报「未在运行」并**什么都不做** | 子工作流不登记（`registerExecution` 只在 `:453` 调） |

> **「子工作流不可被外部停止」是既定决策，不是缺陷** —— 见 §7 决策 3。
> ⚠️ 但"子工作流里的 Stop 只停子工作流"这半句**没有任何文档说明**，作者只能靠试 —— 见 §8 开放问题 2。

---

## §3 覆盖 / 手动停止：协作式取消

### §3.1 代码路径

```
WorkflowExecutor.kt:200-220     reentryBehavior 三分支（BLOCK_NEW :201 / STOP_CURRENT_AND_RUN_NEW :208 / ALLOW_PARALLEL :215）
WorkflowExecutor.kt:212         → stopExecution(workflow.id)
WorkflowExecutor.kt:163-169     → 逐个 job.cancel()          ← 关键：只标记，不 join、不等待
WorkflowExecutor.kt:242-454     新实例立刻 launch（新旧重叠窗口）
```

### §3.2 协作式取消能做什么、不能做什么

**能**（下一个挂起点即生效）：

- `delay`（「延迟」模块、`FindTextUntil` 的轮询、重试间隔）
- 可取消的 suspend IO
- **自己实现了取消回调的模块**（§5）—— 但只在"直接调用"时有效

**不能**（要等它自己返回）：

| 类别 | 具体模块 / 调用 | 位置 |
|---|---|---|
| 阻塞式 HTTP | `HttpRequestModule`（`client.newCall(...).execute()`）；Bark / Discord / Telegram / Webhook / Feishu* 等推送模块同形 | `HttpRequestModule.kt:266` |
| shell / Core 命令 | `process.waitFor()` + `readText()`；`VFlowCoreBridge.sendRaw` 的 `reader.readLine()`（`soTimeout = 0`） | `ShellManager.kt:344-359`、`VFlowCoreBridge.kt:569`（超时设置见 `:353-354`、`:371`） |
| 脚本步骤 | `JsModule` / `LuaModule` / 外部 `ScriptedModule` —— 执行器**用 `runBlocking` 调模块**，切断了结构化并发 | `JsExecutor.kt:356`、`LuaExecutor.kt:159` |
| 纯计算 | `FindImageModule` 的像素滑窗匹配（循环内无挂起点） | `FindImageModule.kt:387-491` |
| 等用户输入 | 选择菜单 / 选择列表 / 输入文本 / 快速查看 / 分享：`startActivityAndAwaitResult` 返回**普通 `CompletableDeferred`**，取消 await 不会关掉 `OverlayUIActivity` | `ExecutionUIService.kt:77-88` 及各 `requestXxx` |
| 故意脱离作用域 | `PlayAudioModule` 在 `await = false` 时用 `GlobalScope.launch` 等播放结束 | `PlayAudioModule.kt:263-278` |

### §3.3 「打不断」的三类成因（不要混为一谈）

| 成因 | 例子 | 性质 |
|---|---|---|
| **物理不可达** | shell / Core 命令：app 侧只有"写命令 + 等结果"，Core 侧没有 per-command 的取消/杀进程接口（`VFlowCore.kt:131-134` 只有 shutdown hook） | 【判断】**合理**：做不到，且中途杀命令风险更大 |
| **能做而没做** | OkHttp 阻塞 `execute()`：换成 `enqueue` + `invokeOnCancellation { call.cancel() }` 即可 —— 仓库里**已有正例**（`AgentModule.kt:713-715`、`AutoGLMModule.kt:705-707`） | 【判断】**实现缺口**，不是设计 |
| **结构性切断** | 脚本路径的 `runBlocking`（§5.3）：它让**脚本里调用的任何模块**（哪怕实现了 `invokeOnCancellation`）都收不到取消 | 【判断】**实现缺口** |

> ⚠️ 关键判断依据：**代码与 `docs/fork/` 里没有任何一处声明过"这些操作故意不可取消"**。
> 现状是"**没实现**"，不是"**决定不做**"。这两者在维护成本上完全不同 —— 前者会被反复当成 bug 报，后者是契约（需写进文档 + 给出替代方案）。

### §3.4 覆盖特有的串扰（同一根因：状态没有实例维度）

| # | 现象 | 依据 | 证据类型 |
|---|---|---|---|
| 1 | **新旧实例重叠**：`stopExecution` 不 join，旧实例可能正卡在 30 秒的 shell 里，新实例已开始跑 ⇒ 两次执行同时操作设备 | `:163-169`（无等待）、`:242`（立刻 launch） | 【推断】 |
| 2 | **旧实例打掉新实例的通知**：通知 ID 只按 `workflowId` 派生（`ExecutionNotificationManager.kt:79-80`），而旧实例的 `finally` 里有 `delay(3000); cancelNotification(workflow.id)`（`WorkflowExecutor.kt:445-448`）⇒ 3 秒后它取消的是**新实例正在用的那条通知**（直到新实例下一次 `updateState` 才重发） | 同上 | 【推断】 |
| 3 | **新执行的详细日志会丢**：`executionLogs` 是按 `workflowId` 共享的单个 `StringBuilder`（`:63`），旧实例 `finally` 里 `if (!hasActiveExecutions) executionLogs.remove(workflow.id)`（`:408-411`）可能摘掉新实例正在写的缓冲区；而缓冲区只在启动时 `getOrPut` 一次（`:231`），之后写入遇到空槽直接 `return`（`:118-121`） | 同上 | 【推断】 |
| 4 | **`isRunning` 窗口**：旧实例直到 `finally` 才 `unregisterExecution`（`:405`）⇒ 这个窗口里 `BLOCK_NEW` 策略会静默丢弃新触发（`REENTRY_BLOCKED_NEW_EXECUTION`，`:204`） | 同上 | 【推断】 |
| 5 | **编辑器窄竞态**：`shouldHandleExecutionState` 在 `executionInstanceId == null`（即 `execute()` 尚未返回实例 id）时**不校验实例**（`WorkflowEditorExecutionTracker.kt:78-82`）⇒ 旧实例的 `Cancelled` 若在此刻到达会清掉追踪，表现为"刚点执行按钮就复位" | 同上 | 【推断】 |

> ⚠️ 1–5 全部是【推断】。**下最终结论前须真机复现**（§9.4 给了复现配方）。

---

## §4 超时（`maxExecutionTime`）

### §4.1 配置与值域

| 项 | 值 | 位置 |
|---|---|---|
| 字段 | `Workflow.maxExecutionTime: Int?`（**秒**；`null` = 不限） | `Workflow.kt:31` |
| 编辑器 UI | 开关 + 滑块，布局写死 `valueFrom=0 / valueTo=120 / stepSize=5` | `EditorMoreOptionsSheet.kt:543-547`、`sheet_editor_more_options.xml:504-506` |
| 值域（其它写入方） | AI `save_workflow`/`update_workflow` 1–3600；远程 API 与 JSON 导入**不校验上限** | `EditorMoreOptionsSheet.kt:618-626`、`ChatAgentModuleExecutor.kt:3107` |
| ⚠️ 陷阱 | **滑块拉到 0 并保存 = 实际不限时**（判定条件是 `!= null && > 0`，`:310`），而开关是开着的 ⇒ UI 上看不出来 | `WorkflowExecutor.kt:310` |

### §4.2 实现范围

```
WorkflowExecutor.kt:310-320   withTimeout(maxExecutionTime * 1000L) {
                                  seedTriggerOutputs(...)        // 触发器输出播种也算在内
                                  executeWorkflowInternal(...)   // 整个步骤循环
                              }
```

- **包含**：所有步骤、`delay` 等待、循环、**以及 `call_workflow` 调起的子工作流**（子工作流在同一协程里嵌套执行，共享父的这一份预算，**没有自己的超时**）。
- **不包含**：进入 `try` 之前的准备（并发策略判定、日志缓冲、注册）；`finally` 里的收尾（`withContext(NonCancellable)`，`:385-449`）。
- **每次触发各一份**：预算是 per 实例的（每次 `execute()` 新建 job）⇒ 并行实例、重复触发各算各的；步骤级"重试"的 `delay(retryInterval)` 也**计入**这份预算（`:727`）。

### §4.3 到点时发生了什么（逐条）

| # | 动作 | 位置 |
|---|---|---|
| 1 | 日志 `E/WorkflowExecutor: 工作流执行超时（最大 N 秒）` | `:322` |
| 2 | 通知 `Failed("执行超时（N秒）")` —— `setAutoCancel(false)`，**不自动消失** | `:323`、`ExecutionNotificationManager.kt:375-382`（legacy）/ `:434-440`（status chip） |
| 3 | `isTimeout = true`（后续分支都看它） | `:324` |
| 4 | 主线程 Toast `工作流执行超时（N秒）` | `:327-337` |
| 5 | **不弹错误弹窗**（与模块失败不同，超时分支不调 `uiService.showError`） | 对比 `:810-829` |
| 6 | **不走步骤级错误策略**：「跳过 / 重试」对超时无效，直接终止整个工作流 | `:321-338` |
| 7 | `finally`（`NonCancellable`）：释放 WakeLock、删 `exec_<instanceId>` 临时目录、`unregisterExecution`、清日志缓冲 | `:385-411` |
| 8 | **总线广播 `ExecutionState.Cancelled`**（不是 `Failure`） | `:414-422` |
| 9 | **通知不去除**：`if (!wasFailureHandled && !isTimeout)` 跳过 `delay(3000)+cancelNotification` ⇒ 常驻 | `:443-448` |
| 10 | `failedExecutions` **不置位**（那是模块失败分支干的） | 对比 `:835` |

### §4.4 四处口径不一致

| 面 | 说法 |
|---|---|
| 通知 | `Failed("执行超时")`，常驻 |
| 状态总线 | `Cancelled` |
| 编辑器 | 当"普通结束"处理（`Finished` 与 `Cancelled` 同一分支：清高亮、按钮复位，`WorkflowEditorExecutionTracker.kt:28`） |
| 日志 | `E/…工作流执行超时` |

且 `ExecutionState.Cancelled` **无 `stepIndex`**（`ExecutionStateBus.kt:29`）⇒ 编辑器**不会**高亮"卡在哪一步"，而这恰恰是超时最需要的信息。
另：`ExecutionNotificationState` 里**没有 `Timeout` 状态**，只能借 `Failed` 的文案 —— 这是"状态机缺一个状态"的信号。

### §4.5 它不是硬超时

`withTimeout` 只在**挂起点**检查。当前步骤若是 §3.2 那批阻塞调用，超时**不会**立刻生效，要等该调用返回后才在下一次挂起点抛出 —— 该步骤会**跑完并产生副作用**。

### §4.6 与其它机制的交互

| 场景 | 行为 | 位置 |
|---|---|---|
| 静默工作流 | **超时依然会**发通知（`Failed` 豁免静默） | `ExecutionNotificationManager.kt:94-97` |
| Chat Agent 跑临时工作流 | 临时工作流默认 **120s**、AI 最多 **300s**；Agent 侧等待终态用 `maxExecutionTime*1000 + 5000` 宽限 ⇒ 被阻塞步骤卡住时，Agent 会先到点并返回"超时且拿不到错误详情" | `ChatAgentModuleExecutor.kt:2182`、`:3100-3101` |
| 等用户输入的步骤 | 等待时间**计入**预算；到点时 `CompletableDeferred` 不会被取消 ⇒ **悬浮 Activity 留在屏幕上**，而工作流已结束 | §3.2 同一处 |
| 与「覆盖」叠加 | 超时实例走 `Cancelled` 分支，`finally` 同样会 `unregisterExecution` / 可能清 `executionLogs` ⇒ §3.4 的串扰同样发生 | `:405-411` |

---

## §5 `invokeOnCancellation` 的实际能力边界

> 这一节回答一个常见误判：**"模块实现了 `invokeOnCancellation` 就能被停"** —— 答案是**要看两层**，且有三个前提会失效。

### §5.1 实跑结论【实测】

环境：本仓库测试环境（`./gradlew :app:testDebugUnitTest`），kotlinx-coroutines **1.10.2**。
探针形如「挂起点 + 异步回调 resume + 注册取消处理器」，即真实模块的形态（OkHttp `enqueue`、`MediaPlayer` 监听器）：

```kotlin
private suspend fun cancellableWait(ms: Long, onCancel: () -> Unit) =
    suspendCancellableCoroutine<Unit> { cont ->          // ⚠️ 该 block 是**非 suspend** 的
        cont.invokeOnCancellation { onCancel() }
        CoroutineScope(Dispatchers.Default).launch {     // 模拟"回调式完成"
            delay(ms)
            if (cont.isActive) cont.resume(Unit)
        }
    }
```

| 探针 | 结果 | 含义 |
|---|---|---|
| P1 父 job 取消 | `handler=true` | 挂起点确实会被取消命中 |
| P2 **正常 resume** | `handler=false` | **它是"取消处理器"，不是 `finally`** ⇒ 清理必须写在自己模块的 `try/finally` 里 |
| P3 取消后 `isActive` / 迟注册 | `isActive=false`；迟注册 ⇒ `handler=true` | 取消不会被丢失（prompt cancellation guarantee） |
| P4 取消后再 `resume(42)` | `resumeAccepted=false` | **resume 被忽略** ⇒ 即使回调什么都不做，工作流也不会"假装成功继续" |
| P5 **`runBlocking` 包裹** | `handler=false`、`afterRunBlocking=true`、耗时 1360ms（等待跑满） | ⚠️ **取消传不进去，且 `runBlocking` 之后的同步语句仍执行** |
| P6 直接调用（对照） | `handler=true`、耗时 1ms | 直接调用时可被立即打断 |

### §5.2 两层语义（必须分开问）

| 层 | 问题 | 结论 |
|---|---|---|
| **第 1 层** | 工作流会不会继续往下跑？ | ✅ **库保证**：只要用 `suspendCancellableCoroutine`（而非裸 `suspendCoroutine`），取消一定在挂起点生效，`execute()` 抛 `CancellationException`，执行器 rethrow（`WorkflowExecutor.kt:737-738`）⇒ 后续步骤不执行。**即使回调什么都不做**（P1/P4） |
| **第 2 层** | 底层那件事会不会真的停？ | ❌ **完全取决于回调内容**：`call.cancel()` 真中断；只置标志 ⇒ **副作用照发生**，只是结果被丢弃 |

### §5.3 三个失效前提

1. **它不是 `finally`**（P2）⇒ 清理写在自己模块里；它只在"挂起点被取消"时触发。
2. ⚠️ **脚本调用路径把取消整个吃掉**（P5，本仓库特有）：`JsExecutor.kt:356` 与 `LuaExecutor.kt:159` 用 `runBlocking { module.execute(...) }`，而 `runBlocking` 起的是**独立 Job**（不挂到工作流 job 的父链上）⇒ 取消传不进去。
   **后果**：**脚本里调用的模块，即使实现了 `invokeOnCancellation`，也不会被「停止 / 覆盖 / 超时」打断**，脚本会一路跑完；工作流只在这**一个步骤返回之后**才停下（下一轮循环条件 `coroutineContext.isActive` 为 false，`:575`）。
   > 顺带解释了一个更早的观察：JS/Lua 步骤是超时/停止的"黑洞"，**根因不是脚本引擎，而是这层 `runBlocking` 切断了结构化并发**。
3. **同步准备段不可打断**：`suspendCancellableCoroutine` **之前**的代码（构建 Request、`setDataSource`/`prepare()`、权限检查）不在挂起点上。

### §5.4 全仓现状（人工走查，会随代码漂移，引用前以代码为准）

| 项 | 数量 / 位置 |
|---|---|
| `core/workflow/module/` 下显式检查 `isActive`/`ensureActive` 的文件 | **8 个**（其中 **4 个是模块**：`AgentModule` / `AutoGLMModule` / `OperitModule` / `LocationRangeCheckModule`；另 4 个是触发器 Handler，不属执行链路） |
| 用 `invokeOnCancellation` 做真中断的正例 | `AgentModule.kt:713-715`（`call.cancel()`）、`AutoGLMModule.kt:705-707`、`PlayAudioModule.kt:255-258`（catch → `stop()` → rethrow，见 `:281`） |
| `core/` 内的 `runBlocking` | `JsExecutor.kt`、`LuaExecutor.kt`（模块桥，§5.3）、`GKDTriggerHandler.kt`（触发器侧，不在本链路） |

---

## §6 评估（**【判断】**，非事实）

> 判断标准：① 可预期性（名字与行为是否一致）② 内部一致性（通知/日志/编辑器/总线四面对不对得上）③ 可达性（用户想做的事有没有"根本做不到且无提示"）④ 可诊断性（能否定位卡在哪一步）

### §6.1 ✅ 合理，不建议动

| 项 | 理由 |
|---|---|
| 协作式取消机制本身 | 中断阻塞调用要么不可能（shell 无接口），要么危险（中途 abort 已发出的推送/POST 会留下"服务端做了、app 不知道"的不确定态）。这是 Android/Kotlin 的标准做法 |
| Stop = 步骤边界结束（不打断） | Stop 是一个步骤，无法"回到过去"；改成 cancel 会把"正常收工"降级成"异常取消"，**制造**新问题 |
| Stop→`Finished` / cancel→`Cancelled` 的语义区分 | 两者对用户含义不同，有保留价值 |
| 超时通知常驻 | 超时需要用户介入，不该被 3 秒后自动清掉 |
| shell 命令不可中断 | 无接口 + 中途杀命令风险更大 |
| **子工作流不可被外部停止** | **既定决策**，理由见 §7 决策 3 |

### §6.2 ⚠️ 有代价、可接受，但**必须声明**

| 项 | 说明 |
|---|---|
| 阻塞步骤不可中断 | 用户必须在文档/UI 里知道"覆盖 ≠ 停干净"，否则会按错误的心智模型写工作流 |
| 子工作流不可外部停止 | 行为正确，但**当前无任何文档说明** ⇒ 用户只会看到"未在运行"这句误导性提示 |

### §6.3 ❌ 真缺陷（值得修）

| 项 | 为什么是缺陷 | 根因 |
|---|---|---|
| 超时口径四处不一致 + 无步骤索引 | 同一件事四种说法；**无法定位卡在哪一步** | 状态机缺 `Timeout`；广播走 `Cancelled` 而通知走 `Failed` |
| 覆盖的三处串扰（通知 / 日志 / 重叠） | 用户可观测的"状态错乱 + 日志丢失" | **执行是 per-instance，通知与日志是 per-workflow** |
| 脚本路径切断取消（§5.3） | 让"脚本里调用的任何模块"都不可取消，影响面大于表面 | `runBlocking` 不继承父 job |

### §6.4 修复优先级（按 收益 ÷ 风险）

| 序 | 动作 | 风险 |
|---|---|---|
| 1 | **文案 + 文档**（三处命名 + 本文件） | 零（不动逻辑） |
| 2 | 超时带上 `stepIndex`、口径统一（建议按失败语义，见 §8 开放问题 1） | 中低（`WorkflowExecutor` 一处，但牵动失败分支收尾） |
| 3 | 通知 / 日志加 `instanceId` 维度 | 中（碰 `ExecutionNotificationManager`） |
| 4 | `JsExecutor`/`LuaExecutor` 桥接取消 | 中高（**会改变行为**，属功能变化） |

---

## §7 决策台账

| # | 决策 | 日期 | 依据 |
|---|---|---|---|
| 1 | 终止手段统一为**协作式取消**，不引入线程级强杀 | —（现状） | §6.1 |
| 2 | **「停止工作流（当前）」= 步骤边界结束**，保持 `Finished` 语义，不改为 cancel | 2026-10-14 | §6.1；命名问题走文案（§8 开放问题 5），不改行为 |
| 3 | **子工作流不可被外部停止**（`isRunning(子id)` 恒 false 是**正确**行为，必须如此） | 2026-10-14（用户裁定） | 子工作流是**调用方的一个步骤**（子程序），生命周期归属调用方。若允许外部单独停它，调用方会把它当作**正常返回**继续往下执行（`CallWorkflowModule` 只读 `returnValue`）⇒ **调用方状态与实际执行不一致**。要停就停它的父 |
| 4 | 超时通知**常驻**（不被 3 秒清理带走） | 2026-10-03（`FORK.md` 已登记） | 超时需要用户介入 |
| 5 | shell / Core 命令**不可中断**（不为此开 kill 通道） | 2026-10-14 | 无接口；中途杀命令风险更大 |
| 6 | 「脚本内调用的模块不可取消」**暂按现状记录**，未声明为设计 | 2026-10-14 | 见 §8 开放问题 4 —— 需先定"要不要让脚本可被停止" |

---

## §8 开放问题（待裁定）

| # | 问题 | 两种取法 | 备注 |
|---|---|---|---|
| 1 | **超时算"失败"还是"取消"？** | 失败 ⇒ 应广播 `Failure(stepIndex)` + 保留常驻通知（与通知现状一致）；取消 ⇒ 应自动清理通知（与现状矛盾） | 【判断】倾向"失败"：代码事实上已按失败处理（通知常驻、静默豁免），只差总线口径与 `stepIndex` |
| 2 | **子工作流里的「停止当前」只停子工作流** —— 是否符合"子工作流 = 函数"的模型？ | 现状 = 提前 `return`（与「停止并返回」等价，自洽）；另一种读法 = "当前"应指最外层被触发的工作流 | 现状自洽，但**无文档**，作者只能靠试 |
| 3 | **覆盖要不要等旧实例结束（join）？** | 不 join（现状）⇒ 有重叠窗口；join ⇒ 覆盖语义变成"排队" | 若 join，需定义"等多久"与超时后的行为 |
| 4 | **脚本路径要不要桥接取消？** | 不桥接（现状）⇒ 脚本是"不可停区"；桥接 ⇒ 脚本内调用的模块也能被停，但**行为变化** | 改动涉及 `runBlocking` 的取消传播，需单独评估 |
| 5 | **三处命名是否改文案？** | 「停止工作流」→「在此处结束工作流」；「覆盖」→ 明示"取消上一次执行（协作式，不保证停干净）"；「最大执行时长」→ 明示"非硬超时，卡在阻塞步骤时可能不生效" | 零风险，收益/风险比最高 |

---

## §9 落点

### §9.1 关键文件与函数

| 要改的东西 | 动哪里 |
|---|---|
| Stop 的语义 / 传播 | `core/workflow/module/logic/StopWorkflowModule.kt`（模块）+ `WorkflowExecutor.kt:906-910`（信号处理） |
| 取消 / 并发策略 | `WorkflowExecutor.kt:200-220`（reentry）、`:163-169`（`stopExecution`）、`:453`/`:970-986`（登记表） |
| 超时 | `WorkflowExecutor.kt:307-347`（判定与广播）、`:443-448`（通知清理条件） |
| 通知口径 | `services/ExecutionNotificationManager.kt`（ID 派生 `:79`、静默豁免 `:94-97`、各状态分支 `:343-448`） |
| 状态总线 | `core/execution/ExecutionStateBus.kt`（`ExecutionState` 家族，加字段/加状态） |
| 脚本取消桥接 | `core/execution/JsExecutor.kt:356`、`LuaExecutor.kt:159` |
| 编辑器侧表现 | `ui/workflow_editor/WorkflowEditorExecutionTracker.kt:78-82` |

### §9.2 现成的坑

1. **`stopExecution` 不 join** ⇒ 任何"取消后立刻假设资源已释放"的写法都是错的。
2. **通知 ID 与日志缓冲按 `workflowId`** ⇒ 改并发/覆盖相关逻辑时，必须同时考虑实例维度，否则引入新的串扰。
3. **`isRunning()` 只看顶层** ⇒ 想判断"某个工作流（含子工作流）是否在跑"没有现成 API。
4. **超时判定条件 `> 0`** ⇒ `maxExecutionTime = 0` 与 `null` 等价（UI 上却显示开关已开）。
5. **改 `WorkflowExecutor` 属于上游高冲突文件**（`FORK.md` 已登记两处改动）⇒ 动手前先看 `FORK.md` 的敏感点清单并同步登记。

### §9.3 待补的守卫（测试，均为新增文件）

| # | 锁什么 | 为什么值得 |
|---|---|---|
| 1 | **`runBlocking` 包裹的模块收不到取消**（§5.1 P5） | 这条现在只存在于推理里，**没有任何守卫**；将来重构 `JsExecutor`/`LuaExecutor` 时会被无声破坏 |
| 2 | **`invokeOnCancellation` 不是 `finally`**（P2） | 防"把清理写进取消回调"这类误用 |
| 3 | 超时广播必须带 `stepIndex`（若采纳 §8 开放问题 1） | 超时的可诊断性 |

### §9.4 真机复现待办【推断 → 待验证】

§3.4 的 1–5 目前是**代码推断**。最小复现配方：

1. 建一个工作流：`延迟 1s` → `shell 命令 sleep 30` → `延迟 1s`，重复触发策略 = **覆盖**，最大执行时间 = 关闭。
2. 触发一次，等它进入 shell 步骤，再触发一次。
3. 观察：① 通知栏是否出现「已停止」后又消失/错乱（§3.4-2）；② 新执行的详细日志是否为空或缺开头（§3.4-3）；③ 两次执行的 shell 是否**同时**在跑（§3.4-1）。

---

## §10 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-14 | 首版。起因：用户问「停止工作流是否只停当前」「覆盖会不会中断前一个」「超时会发生什么」。走查后确认**三种机制被混为一谈**，且超时口径四处不一致。§5 的库行为**实跑验证**（探针跑完即删）；§3.4 与 §4.4 的部分结论仍是【推断】，已列 §9.4 复现配方。**本文件为纯文档，未改任何代码** |
