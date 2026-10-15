# 模块异常处理策略与「跳过后可分流」现状调研（module-error-status-survey）

> **版本** v1.1 · **对应分支/日期** `dev`（2026-10-14 工作区）
> **目录归属**：fork 独有文档（上游无此文件）⇒ 冲突归**我方**
> **用途**：回答「想兜住报错、并按结果走不同路径，该选哪种异常处理策略；**哪些模块能兜**；判据怎么写；哪些坑不能踩」。
> **证据来源**：本仓库**源码直读 + 脚本全量扫描**（脚本见 §8），**无真机验证项**。所有行号已回代码核对；所有数量为脚本实测（会随代码漂移，引用前建议重跑 §8 的脚本）。

---

## §0 一页结论（TL;DR）

1. **三种策略里只有 `SKIP` 能让流程继续**，也才可能「兜住后分流」。`STOP` 直接终止；`RETRY` 耗尽后**落 STOP** —— **没有「重试失败再降级为跳过」的机制**（`WorkflowExecutor.kt:798-799`，注释即"`POLICY_STOP (默认) 或 重试耗尽`"）。
2. 跳过时执行器会往该步骤的 `stepOutputs` 注入 `error`（错误文本）与 `success`（`false`）（`WorkflowExecutor.kt:790-796`）。**但这两个键在大多数模块上都不是「声明输出」**（全仓声明 `success` 的 73 个、声明 `error` 的 9 个）⇒ 对**其余模块**，变量选择器不列、AI 侧不知道，且步骤 id 是 UUID、手打不现实（§2）；只有在模块**自己**声明了同名输出时，判据才真正可用（§4.4）。
3. **可判定结论（实测）**：**206** 个注册模块中，
   - **77 个**同时满足「会报错」+「跳过时下游有可靠判据」→ §4.1–§4.4；其中 **1 个是例外**（`vflow.logic.jump`，判据恒真，不可用）⇒ 真正可用 **76 个**；
   - **65 个**「会报错但跳过时下游拿不到判据」→ §4.6（**配了 SKIP 也兜不住**）；
   - **64 个**「代码里没有显式失败出口」→ §4.7。
4. ⚠️ **仓库既有文档推荐的那套写法不成立**：`IF {{某步.error}} 不存在` 恒为 false；`IF {{某步.输出}} 为空` 在 `VNull` 上恒为 false（§3）。`docs/EXCEPTION_HANDLING.md:365-397` 与 `docs/VOBJECT_SEMANTICS.md:338/634/656` 的示例需按本文 §5 修正。

---

## §1 机制现状（事实）

### 1.1 策略的定义与存储

`core/workflow/model/ActionStepExecutionSettings.kt`：

| 项 | 位置 | 内容 |
|---|---|---|
| 保留键 | `:9-11` | `__error_policy` / `__retry_count` / `__retry_interval`，存在 `ActionStep.parameters` 里（**双下划线**前缀，与 `__trigger_label` 同一命名风格） |
| 策略值 | `:13-15` | `POLICY_STOP = "STOP"` / `POLICY_SKIP = "SKIP"` / `POLICY_RETRY = "RETRY"` |
| 默认值 | `:17-18` | `DEFAULT_RETRY_COUNT = 3`、`DEFAULT_RETRY_INTERVAL_MS = 1000L` |
| 读取 | `:20-27` | 键缺失 ⇒ 回落 **`STOP`**（⇒ 历史工作流、AI 生成的工作流默认全是 STOP） |

写回时只有 `RETRY` 才写后两个键，切走时删除它们（`ui/workflow_editor/ActionEditorExecutionSettings.kt:11-21`）。

### 1.2 执行器的判定与分支

`core/execution/WorkflowExecutor.kt`：

| 环节 | 位置 | 说明 |
|---|---|---|
| 结果类型 | `core/module/definitions.kt:643-661` | `ExecutionResult` = `Success(outputs)` / `Failure(errorTitle, errorMessage, partialOutputs)` / `Signal(signal)` |
| 参数解析失败 | `:702-706` | `{{变量}}` 解不出来 ⇒ 预先构造成一个 `Failure`，**在重试循环之外**（重要，见 §1.4） |
| 重试循环 | `:711-755` | 见 §1.4 |
| 成功分支 | `:759-764` | 只写模块自己返回的 `outputs`，**不注入任何状态字段** |
| 失败分支 | `:765-851` | `SKIP` ⇒ 合成跳过输出后 `pc++`；否则 ⇒ 通知栏 Failed + 错误弹窗 + `postState(ExecutionState.Failure)` + `return null` |
| 异常兜底 | `:928-943` | `execute()` 抛出的异常 → `Failure("模块执行异常" / "Inline JavaScript 执行失败")`，**同样走策略** |
| 取消例外 | `:737-738` | `CancellationException` 直接 rethrow，**不受策略影响**（用户停止工作流不该被拦） |
| 空输出兜底 | `:953-968` | `generateDefaultOutputs()`：模块声明的每个输出填 `VNull`（注释指向 `EXCEPTION_HANDLING.md` / `VOBJECT_SEMANTICS.md`） |

**策略只在拿到 `Failure` 时生效**：`Success` 与 `Signal` 都直接进各自分支（`:744-746`），不走策略。

### 1.3 SKIP 的输出合成（四步，顺序固定）

`WorkflowExecutor.kt:768-797`：

```
1. 模块提供了 partialOutputs ⇒ 以它为基础
   否则                      ⇒ generateDefaultOutputs()：所有声明输出填 VNull
2. 模块声明但上面没给的输出   ⇒ 补 VNull
3. put("error",   VString(errorMessage))   ← 无条件覆盖
4. put("success", VBoolean(false))         ← 无条件覆盖
   ↓
stepOutputs[step.id] = skipOutputs；pc++ 继续下一步
```

`stepOutputs` 的类型是 `MutableMap<String, Map<String, VObject>>`（`core/execution/ExecutionContext.kt:64`），下游取值的路径是
`VariableResolver.kt:174-181`（`stepOutputs[stepId]?.get(outputId)`）——**只看这个 map 里有没有这个 key，不看模块声明**。

### 1.4 RETRY 的次数语义（与直觉不同）

```
attempt = 0
while (attempt <= retryCount || policy != RETRY) {
    if (attempt > 0) { 通知栏"重试 (attempt/N)"；delay(interval) }
    执行 module.execute()
    成功/信号 ⇒ break
    RETRY 且失败 ⇒ attempt++；attempt > retryCount ⇒ break
    其它策略 ⇒ break
}
```

- **总执行次数 = `retryCount + 1`**（1 次初始 + N 次重试），等待 N 次。
- **等待发生在重试之前**。
- **重试耗尽 ⇒ 走 STOP 分支**（终止 + 弹窗），**不会**自动降级为 SKIP。
- ⚠️ **参数解析失败的重试是空转**：`preExecutionFailure` 在循环**外**算好（`:702-706`），循环内 `finalResult = preExecutionFailure ?: ...` 直接复用它 ⇒ 每次重试返回同一份 Failure，只是白等 `N × interval`。**配置类错误永远不会因重试而自愈。**
- 重试不重新解析参数，用的是同一份已解析参数与同一个 `ExecutionContext`。

### 1.5 配置入口

| 入口 | 位置 | 说明 |
|---|---|---|
| 单步 | `res/layout/sheet_action_editor.xml:128-180` → `ActionEditorSheet.buildErrorHandlingUi()`（`ActionEditorSheet.kt:275-376`） | 可折叠区「异常处理策略」：RadioGroup 三选一；选 RETRY 时展开两个滑块（次数 1–10，间隔 100–5000 ms） |
| 全局默认 | `SettingsScreen.kt:938-1001`（prefs 键 `defaultErrorPolicy` / `defaultRetryCount` / `defaultRetryInterval`，见 `SettingsViewModel.kt:40/86/275`） | **只在新建步骤时**灌入（`ActionEditorSheet.kt:108-110`：`existingStep == null` 才 `applyDefaultExecutionSettings`），已有步骤不受影响 |
| AI 生成 | —— | `ui/chat` 与 `api` 目录下**没有任何**写入 `__error_policy` 的代码 ⇒ AI 生成的步骤恒为 `STOP` |

---

## §2 判据链路上的四处「读不到」（事实）

这四条共同导致「注入的 `error`/`success` 实际上用不了」，是本调研的核心背景。

### 2.1 变量选择器只列模块**声明**的输出

`ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` 的 `buildPickerModel()`（`:198`）为每个前置步骤生成条目时，遍历的是
`module.getDynamicOutputs(step, allSteps)`（`:225`），引用串形如 `{{${step.id}.${outputDef.id}}}`（`:237`）。

⇒ `error` / `success` 不在其中（除非模块自己声明了同名输出）。实测：声明 `success` 的有 **73** 个模块，声明 `error` 的有 **9** 个（§4.4）。

> ⚠️ **本次踩过的统计陷阱（记在这里防止重犯）**：`error` 的声明**全部是位置参数写法** `OutputDefinition("error", …)`，没有一处写成 `id = "error"` ⇒ 只用 `id = "error"` 去 grep，会得出「全仓一个都没有」的**错误结论**（第一版就是这么错的）。`success` 则是两种写法都有（`id = "success"` 与 `OutputDefinition("success", …)`），只匹配具名写法会把 73 误算成 24。

### 2.2 步骤 id 是 UUID

`core/workflow/model/ActionStep.kt:18`：`val id: String = UUID.randomUUID().toString()`。

引用语法是 `{{<uuid>.error}}`，而 UI 上没有任何地方把该 id 展示给用户 ⇒ 手打不现实。手打的后果是胶囊**退化成原文**：`PillVariableResolver.resolveVariable` →
`VariableInfo.fromMagicVariableWithProperty` 要求 `outputDefs.find { it.id == outputId }` 命中（`core/execution/VariableInfo.kt:210`），否则返回 null ⇒ 只显示底层语法（**运行时仍能解析**，只是 UI 上看不出是变量）。

### 2.3 成功路径不注入任何状态

`:759-764` 只写 `result.outputs`。⇒ `{{某步.success}}` 在**成功**时不存在。

而引用解析不到时的回落行为是「**不设** `magicVariables`，回落到 `variables[key]`，即**原始字符串**」（`WorkflowExecutor.kt:678-682`；`LogModule.kt:226-249` 的表格把这条语义写全了）⇒ 下游拿到的是 `VString("{{uuid.success}}")`，**非 null**。这正是 §3 里「判据恒真」的根因。

### 2.4 AI / Agent 侧不知情

- 模块 schema 给模型看的输出清单同样来自声明式 `getOutputs()` ⇒ 模型不知道 `error`/`success` 约定；
- `ui/chat` 的提示词里没有「异常处理策略」字样（实测 grep 无命中）；
- 但 `update_workflow` 对变量引用**原样放行**（见 `docs/fork/workflow-read-write-tools.md` §3.1.4）⇒ 模型**能**写 `{{stepId.error}}`，只是不会主动写。
- AI 侧真正感知 SKIP 的方式是**日志行**：`ui/chat/ChatMessagePatch.kt:252` 用
  `W/WorkflowExecutor: 根据策略，跳过错误继续执行。` 作为唯一锚点，把「跑完了但有步骤被跳过」映射成 `ChatToolResultStatus.ERROR`（`:295-309`）。判据理由见该文件 `:235-251` 的注释与单测 `test/ui/chat/ChatTemporaryWorkflowResultTest.kt`。

---

## §3 运算符语义陷阱（事实 + 代码依据）

`core/workflow/module/logic/ConditionEvaluator.kt` + `IfModule.kt` 决定了下游能写出什么判据。

### 3.1 `存在 / 不存在` 是常量

- `evaluateCondition(input1: Any?, ...)`：只有 **Kotlin `null`** 才判"不存在"（`:39-42`）；
- `evaluateConditionVObject()`：`OP_EXISTS -> return true` / `OP_NOT_EXISTS -> return false`，注释明写「VNull 是对象实例，视为存在」（`:59-63`）；
- `IfModule.execute()` 取的是 `context.getVariable("input1")`（`IfModule.kt:259`），而
  `ExecutionContext.getVariable` **永远返回非空 `VObject`**（缺失时给 `VNull`，`:101-112`）。

⇒ 在 IF 模块里 **`存在` 恒 true、`不存在` 恒 false**。既有单测已锁死这条语义：`ConditionEvaluatorTest.kt:359`（`VNull → exists == true`）、`:367`（`VNull → not_exists == false`）。

### 3.2 `为空 / 不为空` 在 `VNull` 上恒 false

`:76-78` 有一句 `if (input1 is VNull) return false`，位于**所有其他运算符之前** ⇒ `VNull` 参与 `为空` 也返回 false。

⇒ 「跳过时输出全填 `VNull`」这件事，**用 `为空` 检测不到**。

### 3.3 判据可用性总表

| 判据 | 被 SKIP 时 | 成功时 | 可用？ |
|---|---|---|---|
| `IF {{id.error}} 不存在` | `error` 存在（非空串）→ **false** | 引用解析不掉 → `VString("{{id.error}}")`，仍是"存在" → **false** | ❌ 恒 false |
| `IF {{id.error}} 不为空`（模块**未**声明 `error`） | 注入的错误文本非空 → true | 回落成非空字符串 → **true** | ❌ 恒 true |
| `IF {{id.success}} 为假` | `VBoolean(false)` → **true** ✅ | 声明了 `success` 的模块 → `VBoolean(true)` → false ✅；**未声明的模块** → `VString` → 非布尔串按"不为真"处理 → **true** ❌ | ⚠️ 只对声明了 `success` 的模块可靠 |
| `IF {{id.error}} 不为空`（模块**自己声明了** `error`） | 注入的错误文本非空 → **true** ✅ | 成功路径填空串 → `""` → false ✅ | ⚠️ 只对 §4.3 / §4.4 的 4 个模块可靠 |
| `IF {{id.count}} == 0` | 模块提供 `partialOutputs(count=0)` → `VNumber(0)` → true ✅ | `>0` → false ✅ | ⚠️ 只对提供语义化 `partialOutputs` 的模块可靠 |
| `IF {{id.count}} 为空` | `VNull` → **false** | —— | ❌ 恒 false |

> 「非布尔字符串按"不为真"处理」的依据：`ConditionEvaluator.kt:92-113`——`VString` 分支里 `normalizeToBoolean` 返回 null 时，`OP_IS_FALSE -> true`。

---

## §4 全量清单（实测，206 个注册模块）

### 4.0 口径与统计

以 `ModuleRegistry.kt` 的 `register(...)` 调用为全集（**206** 个类名，全部能在 `core/workflow/module/` 下找到对应类），逐个类判定：

| 条件 | 判定标准 | 说明 |
|---|---|---|
| ① 会报错 | 类文本里出现 `ExecutionResult.Failure`，**或**有 `throw` | 后者抛出的异常会被 `executionExceptionToFailure` 转成 Failure，**同样走策略**。⚠️ 静态口径：没有显式失败出口 ≠ 绝不会失败（例如外部 API 抛异常而模块未捕获时仍会失败） |
| ② 跳过时下游有可靠判据 | 满足三者之一：① 声明了 `success` 输出（BOOLEAN）；② 提供了语义化 `partialOutputs`；③ 声明了 `error` 输出且成功路径填空串 | 三者的输出都能在 🪄 选择器里选到，见 §2.1 |

统计：

| 集合 | 数量 | 含义 |
|---|---|---|
| 有显式 `ExecutionResult.Failure` | 142 | —— |
| 声明了 `success` 输出 | 73 | 80 处 `OutputDefinition`，**全部 BOOLEAN** |
| 声明了 `error` 输出 | 9 | **全部是位置参数写法**（见 §2.1 的统计陷阱）；9 个都可作判据（成功路径填空串） |
| 使用 `partialOutputs` | 7 | 全部同时声明了对应输出 |
| **A：① ∧ ②** | **77** | 本文主体，§4.1–§4.5（其中 1 个是例外，可用 76） |
| B：① ∧ ¬② | 65 | §4.6，**配 SKIP 也兜不住** |
| C：¬① | 64 | §4.7 |
| 合计 | 206 | 77 + 65 + 64 |

> ⚠️ 顺带发现：`ModuleRegistry.kt` 触发器段注册 **30** 个 `vflow.trigger.*` 模块，而 `AGENTS.md` 记「触发器 24 个」。两者不一致，**引用前以代码为准**。

### 4.1 A-1：`success` 恒 true 型（53 个）—— 判据语义「这步是否被跳过/失败」

成功路径一律返回 `VBoolean(true)`，被 SKIP 时被注入的 `false` 覆盖 ⇒ **`IF {{该步骤.success}} 为假` 即"这步没成"**。

| 分类 | 模块 id | 名称 |
|---|---|---|
| **Core**（16，⚠️ 依赖 Core 进程 / Shizuku / Root，失败率最高，最该配） | `vflow.core.capture_screen` | 截屏 |
| | `vflow.core.bluetooth` | 蓝牙控制 |
| | `vflow.core.hotspot` | 热点控制 |
| | `vflow.core.wifi` | WiFi控制 |
| | `vflow.core.nfc` | NFC控制 |
| | `vflow.core.volume` | 音量控制 |
| | `vflow.core.get_clipboard` | 读取剪贴板 |
| | `vflow.core.set_clipboard` | 设置剪贴板 |
| | `vflow.core.input_text` | 输入文本 |
| | `vflow.core.press_key` | 按键 |
| | `vflow.core.screen_operation` | 屏幕操作 |
| | `vflow.core.uinput_screen_operation` | uinput屏幕操作 |
| | `vflow.core.touch_replay` | 触摸回放 |
| | `vflow.core.sleep_screen` | 关闭屏幕 |
| | `vflow.core.wake_screen` | 唤醒屏幕 |
| | `vflow.core.force_stop_app` | 强制停止应用 |
| **系统**（16） | `vflow.system.brightness` | 屏幕亮度设置 |
| | `vflow.system.close_app` | 关闭应用 |
| | `vflow.system.darkmode` | 深色模式 |
| | `vflow.system.do_not_disturb` | 勿扰模式 |
| | `vflow.system.find_installed_app` | 查找本机应用 |
| | `vflow.system.get_usage_stats` | 获取应用使用情况 |
| | `vflow.system.invoke` | 通用调用 |
| | `vflow.system.launch_app` | 启动应用/活动 |
| | `vflow.system.launch_shortcut` | 启动快捷方式 |
| | `vflow.system.mobile_data` | 移动数据 |
| | `vflow.system.screen_rotation` | 屏幕旋转 |
| | `vflow.system.set_clipboard` | 写入剪贴板 |
| | `vflow.system.sim_data_switch` | 切换数据卡 |
| | `vflow.system.sleep_screen` | 息屏 |
| | `vflow.system.wake_and_unlock_screen` | 唤醒屏幕并解锁 |
| | `vflow.system.wake_screen` | 唤醒屏幕 |
| **设备**（8） | `vflow.device.call_phone` | 拨打电话 |
| | `vflow.device.delay` | 延迟 |
| | `vflow.device.flashlight` | 手电筒 |
| | `vflow.device.play_audio` | 播放音频 |
| | `vflow.device.send_key_event` | 执行全局操作 |
| | `vflow.device.text_to_speech` | 朗读文本 |
| | `vflow.device.toast` | 显示Toast |
| | `vflow.device.vibration` | 振动 |
| **交互**（4） | `vflow.interaction.input_text` | 输入文本 |
| | `vflow.interaction.screen_operation` | 屏幕操作 |
| | `vflow.interaction.ocr` | 文字识别 (OCR)（**另有语义化输出**，见 §4.3） |
| | `vflow.interaction.operit` | Operit 交互 |
| **文件/集成/数据/通知/AI/UI**（9） | `vflow.file.save_image` | 保存图片 |
| | `vflow.integration.clash_meta` | Clash Meta |
| | `vflow.integration.flclash` | FlClash |
| | `vflow.integration.ithome_check_in` | IT之家签到 |
| | `vflow.data.quick_view` | 快速查看 |
| | `vflow.notification.remove` | 移除通知 |
| | `vflow.ai.completion` | AI 对话 |
| | `vflow.ui.interaction.update` | 更新组件 |
| | `vflow.ui.screen_flash` | 屏幕闪烁 |

### 4.2 A-2：`success` 反映业务结果型（16 个）—— 判据语义「这步最终成没成」

这类模块的 `success` **不是恒 true**，取值来自业务结果 ⇒ 更贴合「根据结果走不同路径」的本意，也意味着**成功也可能给出 `false`**（例如"点击执行了但落点失败"）。

| 模块 id | 名称 | `success` 的取值来源 |
|---|---|---|
| `vflow.core.shell_command` | 执行Shell命令（Core） | `VBoolean(result.success)` |
| `vflow.shizuku.shell_command` | 执行Shell命令（Shizuku） | `VBoolean(result.success)` |
| `vflow.data.file_operation` | 文件操作 | 按操作分支给 `true` / `false` 字面量 |
| `vflow.device.click` | 点击 | `VBoolean(clickSuccess)`（`ClickModule.kt:213`） |
| `vflow.interaction.find_element` | 查找控件 | 未命中 ⇒ `false`（另带 `found` / `count`） |
| `vflow.interaction.find_until` | 查找直到出现 | 未出现 ⇒ `false` |
| `vflow.interaction.ui_selector` | UI选择器 | 未命中 ⇒ `false`（另带 `found` / `count`） |
| `vflow.network.bark_push` | Bark 推送 | 推送失败分支 ⇒ `false` |
| `vflow.network.discord_push` | Discord 推送 | 同上 |
| `vflow.network.webhook_push` | Webhook 推送 | 同上 |
| `vflow.network.webdav` | WebDAV | `VBoolean(success)` |
| `vflow.system.bluetooth` | 蓝牙设置 | `VBoolean(success)` |
| `vflow.system.wifi` | Wi-Fi 设置 | `VBoolean(success)` |
| `vflow.system.share` | 分享 | `VBoolean(success ?: false)` |
| `vflow.ai.agent` | AI 智能体 | `VBoolean(suc)` / 失败分支 `false` |
| `vflow.ai.autoglm` | AutoGLM 智能体 | 失败分支 `false` |

### 4.3 A-3：无 `success`，靠语义化 `partialOutputs`（4 个）

全仓只有 **7 个**模块实现了 `partialOutputs`，其中 3 个同时有 `success`（`find_element` / `ocr` / `ui_selector`，已计入 §4.2），其余 4 个只能靠语义化输出：

| 模块 id | 名称 | 推荐判据 | 跳过时的值 |
|---|---|---|---|
| `vflow.device.find.text` | 查找文本 | `IF {{id.count}} == 0` | `count=0`、`all_results=[]`、`first_result=VNull` |
| `vflow.interaction.find_image` | 查找图片 | `IF {{id.count}} == 0` | 同上（`FindImageModule.kt:237-245`） |
| `vflow.notification.find` | 查找通知 | `IF {{id.notifications}} 为空` | `notifications=VList([])` |
| `vflow.feishu.get_message_history` | 获取飞书会话历史 | `IF {{id.error}} 不为空` | `error="错误码: x, 消息: y"`；成功路径给 `error=""`（`FeishuGetMessageHistoryModule.kt:406`、`:443`） |

> ⭐ 最后一个是「读 `error` 字段做判据」的模块之一 —— `error` 是它**自己声明的输出**（`OutputDefinition("error", …)`），不是执行器注入的那个读不到的 `error`。全仓共 **4 个**这类模块，另 3 个见 §4.4。

### 4.4 A-4：靠 `error` 字段型（3 个）

这三个模块**没有** `success`、**没有** `partialOutputs`，但**自己声明了 `error` 输出并在成功路径填空串** ⇒ `IF {{id.error}} 不为空` 成立（失败/跳过时被注入的错误文本覆盖成非空）。

| 模块 id | 名称 | 成功路径给 `error` 的位置 |
|---|---|---|
| `vflow.network.http_request` | HTTP 请求 | `HttpRequestModule.kt:274-278`（`"error" to VString("")`） |
| `vflow.network.telegram_push` | Telegram 推送 | `TelegramPushModule.kt:263-270` |
| `vflow.feishu.send_message` | 发送飞书消息 | `FeishuSendMessageModule.kt:306-317` |

> 加上 §4.3 里的 `vflow.feishu.get_message_history`，全仓**一共 4 个**模块的判据是「读 `error` 字段」。其余 5 个声明了 `error` 的模块（`bark_push` / `discord_push` / `webhook_push` / `webdav` / `operit`）都另有 `success`，判据以 `success 为假` 更直接。

### 4.5 例外（1 个）：`vflow.logic.jump`（跳转步骤）

它**声明了 `success`**（`JumpModule.kt:48`）却**从不返回它** —— 成功路径返回 `Signal(ExecutionSignal.Jump)`（`:123`），而 `Signal` 分支**不写 `stepOutputs`**（`WorkflowExecutor.kt:852`）。

⇒ `{{id.success}}` 永远解析不到 ⇒ 回落成字符串 ⇒ `为假` **恒 true**（无论成功还是被跳过）。**判据不可用**，不要按 §4.1 的用法套。

### 4.6 B：会报错，但跳过时下游**拿不到**判据（65 个）

这些模块配了 `SKIP` 也**兜不住** —— 错误会被静默吞掉，下游无法分流（只能看日志 / 用 §5.3 的兜底姿势）。

| 分组 | 数量 | 模块 |
|---|---|---|
| **块结构**（⚠️ 见 §5.2，别配 SKIP） | 15 | `vflow.logic.loop.start` 循环 / `.loop.end` 结束循环 / `.foreach.start` 重复每一项 / `.foreach.end` 结束重复 / `.while.start` 条件循环 / `.while.end` 结束循环 / `.do_while.end` 结束循环 / `.switch.start` Switch / `.switch.case` Case / `.switch.default` Default / `.menu.start` 从菜单中选取 / `.menu.item` 菜单项目、`vflow.ui.activity.show` 显示界面(事件循环) / `vflow.ui.float.show` 显示悬浮窗(事件循环) / `vflow.ui.on_event.start` 当组件被操作 |
| **Core 状态读取** | 6 | `vflow.core.{bluetooth_state, hotspot_state, nfc_state, screen_status, volume_state, wifi_state}` |
| **数据 / 逻辑** | 18 | `vflow.data.{base64, calculation, export_backup, get_current_time, input, math_expression, parse_json, parse_xml, text_extract, text_processing, text_replace, text_split}`、`vflow.logic.{break_loop, call_function, call_workflow, list.choose, set_workflow_enabled, stop_workflow}` |
| **网络** | 2 | `vflow.network.{get_ip, feishu_upload}` |
| **文件 / 图像** | 5 | `vflow.file.{adjust_image, apply_mask, import_image, rotate_image, scale_image}` |
| **Shizuku 快捷方式** | 3 | `vflow.shizuku.{alipay_shortcuts, wechat_shortcuts, coloros_shortcuts}` |
| **系统 / 设备** | 7 | `vflow.system.{capture_screen, get_battery_status, js, lua, systeminfo, location_range_check}`、`vflow.device.speech_to_text` |
| **UI / 变量 / 其它** | 9 | `vflow.ui.interaction.{exit, get_value}`、`vflow.variable.{create, get, modify, random}`、`vflow.interaction.get_current_activity`、`vflow.trigger.share`、`vflow.xposed.js` |

> 注：`vflow.logic.call_workflow` 属于此类，且它还有一个额外问题 —— **子工作流内部失败不会冒泡**：`executeWorkflowInternal` 在 STOP 失败时 `return null`（`:849`），`executeSubWorkflow` 把 null 当返回值包进 `SubWorkflowResult`，而 `CallWorkflowModule.execute` 一律 `return ExecutionResult.Success(outputs)`（`CallWorkflowModule.kt:143`）⇒ 父步骤**仍是 Success**，父级策略无从生效。

### 4.7 C：代码里没有显式失败出口（64 个）

「没写失败出口」≠ 绝不会失败（外部 API 抛异常仍会失败），但**没有可依赖的状态判据**，因此同样不适合配 `SKIP` 分流。

| 分组 | 数量 | 模块 |
|---|---|---|
| 触发器 | 29 | `vflow.trigger.*`（除 `vflow.trigger.share`，它在 B） |
| 块结构（起始/中间/结束） | 11 | `vflow.logic.if.start` 如果 / `.if.middle` 否则 / `.if.end` 结束如果、`vflow.logic.do_while.start` 循环直到、`vflow.ui.activity.start` 创建界面 / `.end` 结束界面、`vflow.ui.float.start` 创建悬浮窗 / `.end` 结束悬浮窗、`vflow.logic.menu.end` 结束菜单、`vflow.ui.on_event.end` 结束监听、`vflow.logic.switch.end` End Switch |
| 纯数据 / 编解码 | 9 | `vflow.data.{aes, des, rc4, sm4, hash, url_codec, comment, inspect_type, log}` |
| UI 组件 | 5 | `vflow.ui.component.{text, input, button, switch, listpicker}` |
| 逻辑控制 | 3 | `vflow.logic.{continue_loop, define_function, return}` |
| 系统只读 | 3 | `vflow.system.{get_clipboard, get_screen_state, read_sms}` |
| 其它 | 4 | `vflow.notification.send_notification`、`vflow.shizuku.gemini_shortcut`、`vflow.snippet.find_until`、`vflow.variable.load` |

---

## §5 怎么用

### 5.1 标准模板（A 组模块）

```
[步骤 A]  异常处理策略 = 忽略错误继续（SKIP）
    ↓
[IF]  {{A.success}} 为假          ← A-1 / A-2
   或 {{A.count}} == 0            ← A-3
   或 {{A.error}} 不为空          ← A-4（§4.3 / §4.4 的 4 个模块）
    ├─ 真 → 备用路径（换目标 / 记录并继续 / 提前返回）
    └─ 假 → 主路径
```

- A-1 的 `为假` 只说明「这步被跳过或失败了」；
- A-2 的 `为假` 可能是**业务未命中**（`find_element` 找不到、`click` 落点不对）—— 这才是"按结果分流"。
- 判据**必须**用 `为假` / `== 0` / `为空`（仅对 VList 有效），**不要**用 `不存在`（§3.1）。

### 5.2 块模块一律不要配 SKIP

SKIP 分支只做 `pc++`（`WorkflowExecutor.kt:797`），**没有块跳转逻辑** ⇒ 对会失败的块起始模块（`LoopModule` 参数错误、`ForEach` 找不到配对结束块、Switch 结构错误…）用 SKIP，执行流会**顺序进入块体**而不是整块跳过。且块模块全在 B 组（无判据）。

### 5.3 兜不住时的替代姿势

1. **日志模块的「全部输出」项**：编辑一个 `vflow.data.log` 步骤的 `content` 时，选择器会为每个前置步骤多给一项「全部输出」，引用是裸 `{{<步骤id>}}`
   （`WorkflowEditorMagicVariableCatalogBuilder.kt:49` 的 `wantsWholeStepOutputItem` 只对该模块 + 该输入生效）。它会把整表展开（含注入的 `error`），因此**至少能把错误文本写进日志**（`LogModule.kt:260-337`）。注意这是**唯一支持裸步骤 id 的模块**，别的模块里裸写只会得到空值。
2. **把关键步骤拆进子工作流**，在子工作流内部做 SKIP + 分流 —— 但别指望父层兜子层的失败（§4.6 注）。
3. **改用 STOP + 看日志/聊天卡片**：失败详情在 `ExecutionState.Failure.detailedLog` 里，Chat Agent 卡片会解析 `E/WorkflowExecutor: 模块执行失败: ` 行（`ChatMessagePatch.kt:233`）。

### 5.4 想「先重试再兜底」？

**当前做不到**：没有 `RETRY → SKIP` 的降级。变通只有自己在流程里搭（`Delay` + 计数器 + IF），或接受"重试耗尽即终止"。

---

## §6 评估（判断，非事实）

- **现状的根因**是「注入字段」与「声明输出」两套体系没打通：执行器往 `stepOutputs` 塞 `error`/`success` 时**没有对应的 schema**，于是编辑器、AI、文档三处都看不见它，只有手写 UUID 引用才能用上。
- **覆盖率（A 组 77/206 ≈ 37%）的实际体感高于这个数字**：声明 `success` 的 73 个模块集中在**最容易失败的类别**（交互、系统、设备、Core、网络推送）；纯数据/逻辑类模块多数落在 B/C，本来就很少需要兜底。
- **代价已经在别处付过**：AI 侧为了判「有步骤被跳过」，只能靠一条**日志字符串**做锚点（`ChatMessagePatch.kt:252`），并为此写了专门的单测与注释解释「为什么不能用失败行做判据」。这是同一根因的另一处症状。
- **文档债**：`docs/EXCEPTION_HANDLING.md:365-397`（与 IF 模块的配合）与 `docs/VOBJECT_SEMANTICS.md:338/634/656` 推荐的 `不存在` 写法，在 IF 模块里**恒为 false**，按本文 §3 应修正为 `为假` / `== 0`。

---

## §7 扩展落点（若要「任意模块都能兜 + 判」）

缺的是**成功路径也留下状态**（当前只有失败路径注入）。三种载体的取舍：

| 方案 | 改动 | 影响面 | 结论 |
|---|---|---|---|
| **A. 成功路径也注入 `success=true`**（不覆盖模块自身给的），并让 `success` 成为**声明输出** | `WorkflowExecutor.kt:759-764`（~4 行）+ **`BaseModule.getDynamicOutputs` 默认实现追加合成项（去重）** | ⚠️ 落在**上游核心热路径**与全局 schema 口径上，需按 `FORK.md` 登记；不新增模块 | ✅ **定案**（设计文档 D2） |
| B. 只改 UI 侧（选择器注入伪项） | `WorkflowEditorMagicVariableCatalogBuilder` + `VariableInfo`（否则 pill 退化成原文） | 3 个文件；**AI 侧仍看不见**该字段 | ❌ 不采用（口径分裂） |
| C. 修「存在/不存在」语义（让 `VNull` 也算「不存在」） | `ConditionEvaluator.kt:39-63` | ⚠️ 影响 `VOBJECT_SEMANTICS.md` 全篇与**存量工作流**的条件结果；单测 `ConditionEvaluatorTest.kt:348-373` 会全红 | ❌ 高风险，不采用 |

> ⚠️ **v1.0 的 A 方案描述已过期**：当时写的是「选择器列出该项」（UI 侧注入），走查后改为**在 `BaseModule.getDynamicOutputs` 注入** —— 因为选择器、pill 解析、AI catalog、触发器 `outputSchema` 四处共用这个口径，只改 UI 侧会让 AI 仍然看不见、pill 仍退化成原文。

**具体实现规格见** [`../error-handling-extension-design.md`](../error-handling-extension-design.md)：四项 P0 的语义定案（策略链 / 静默终止 / 跳过跳转 / `[[__error]]` 错误上下文）、三个静默失效的集成点、`vflow.logic.jump` 的 7 条既有缺陷、决策台账（**14 定案 + 5 否决**）与 19 例测试清单。
**实施状态（2026-10-15）：四项 P0 全部已实现**（真机未验证）—— 见该文档 §9.1 的逐项实现记录。

---

## §8 复算方法

口径变了（模块增删、有人补了 `success` 声明）就要重跑。**以 `ModuleRegistry` 的 `register(...)` 为全集**（不要用目录扫描，会漏 `BaseBlockModule` 子类与外置模块）：

```powershell
# run from repo root (Windows PowerShell)
$strings = @{}
foreach ($m in [regex]::Matches((Get-Content app/src/main/res/values/strings.xml -Raw),
        '<string name="(?<n>[^"]+)"[^>]*>(?<v>.*?)</string>')) { $strings[$m.Groups['n'].Value] = $m.Groups['v'].Value }

$regNames = [regex]::Matches((Get-Content app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt -Raw),
        'register\(\s*(?<c>\w+)\(') | ForEach-Object { $_.Groups['c'].Value } | Select-Object -Unique

# index each class chunk by class name
$byClass = @{}
foreach ($f in Get-ChildItem -Recurse -Include *.kt -File app/src/main/java/com/chaomixian/vflow/core/workflow/module) {
  $t = Get-Content -LiteralPath $f.FullName -Raw
  foreach ($c in [regex]::Split($t, '(?m)^(?=(?:internal |private |abstract |open |sealed )*class\s)')) {
    if ($c -notmatch '(?m)^(?:internal |private |abstract |open |sealed )*class\s+\w+') { continue }
    $cn = [regex]::Match($c, '(?m)^(?:internal |private |abstract |open |sealed )*class\s+(\w+)').Groups[1].Value
    $byClass[$cn] = if ($byClass.ContainsKey($cn)) { $byClass[$cn] + "`n" + $c } else { $c }
  }
}

$rows = foreach ($cn in $regNames) {
  $c = $byClass[$cn]; if (-not $c) { continue }
  [pscustomobject]@{
    Class   = $cn
    Id      = [regex]::Match($c, 'override val id\s*(:\s*String\s*)?=\s*"?(?<id>[A-Za-z0-9_.]+)"?').Groups['id'].Value
    Fail    = ($c -match 'ExecutionResult\.Failure')
    Throw   = ([regex]::Matches($c, '(?m)^\s*throw\s')).Count
    Success = ($c -match 'OutputDefinition\(\s*(id\s*=\s*)?"success"')
    Partial = ($c -match 'partialOutputs\s*=')
    # judge 3: declares an "error" output AND writes an empty string on the success path
    ErrorJudge = ($c -match 'OutputDefinition\(\s*(id\s*=\s*)?"error"') -and ($c -match '"error"\s*to\s*(VString\()?""')
  }
}
"total=$($rows.Count)  fail=$(($rows|?{$_.Fail}).Count)  success=$(($rows|?{$_.Success}).Count)  partial=$(($rows|?{$_.Partial}).Count)  errorJudge=$(($rows|?{$_.ErrorJudge}).Count)"
$A = $rows | ? { ($_.Fail -or $_.Throw -gt 0) -and ($_.Success -or $_.Partial -or $_.ErrorJudge) }
$B = $rows | ? { ($_.Fail -or $_.Throw -gt 0) -and -not $_.Success -and -not $_.Partial -and -not $_.ErrorJudge }
$C = $rows | ? { -not $_.Fail -and $_.Throw -eq 0 }
"A=$($A.Count)  B=$($B.Count)  C=$($C.Count)"
$A | Sort-Object Id | Format-Table Id, Class, Success, Partial, ErrorJudge -AutoSize   # review item by item
```

> ⚠️ **要把这段存成 `.ps1` 文件再跑，必须存为 UTF-8 *带 BOM***：Windows PowerShell 5.1 读**无 BOM** 的 `.ps1` 时按系统 ANSI（本机 GBK）解码 ⇒ 脚本里的**中文注释会变乱码并破坏解析**，报错还指向完全无关的一行（实测报 `Cannot index into a null array`，指向 `$strings` 那行）。直接粘进控制台执行没有这个问题。
>
> 本脚本在 2026-10-14 工作区实测输出：`total=206 fail=142 success=73 partial=7 errorJudge=9` / `A=77 B=65 C=64`（与 §4.0 一致）。

⚠️ 已知坑（本次踩过）：

1. **别用「目录里的 class 扫描」当全集** —— 会漏掉 `BaseBlockModule` 子类（`IfModule` / `LoopModule` / `SwitchModule` …）与外置模块，实测少 18 个类（189 vs 206）。
2. **`override val id` 有两种写法** —— `= "vflow.x"` 与 `: String = "vflow.x"`（例：`vflow.data.base64`），正则要兼容。
3. `success` 的声明也有两种写法 —— `id = "success"`（具名）与 `OutputDefinition("success", …)`（位置参数）。只匹配具名写法会把 73 误算成 24（**本次实际踩过并已在上一版口述中报错，已更正**）。
4. **`error` 的声明只有位置参数写法** —— 全仓 `OutputDefinition("error", …)`，没有一处 `id = "error"`。统计它时若只匹配具名写法会得到「一个都没有」（**本次实际踩过**，导致 A 组先报 74、实为 77）。

---

## §9 修订记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.2 | 2026-10-15 | §7 方案 A **已实施**（连同另外三项 P0）；§7 指向的设计稿已从「9 定案」扩到「14 定案」。⚠️ **§2「四处读不到」的现状变了**：§2.3（成功路径不注入状态）**已不成立**；§2.1 与 §2.4 对 **`success`** 已修复（选择器与 AI 侧都能看到它），但对 **`error`** 仍然成立（`error` 仍不是声明输出，只在跳过时注入）；§2.2（UUID 不可手打）仍然成立 |
| v1.1 | 2026-10-14 | **修正统计口径**：`error` 输出的声明**全部是位置参数写法**，v1.0 用 `id = "error"` 匹配误得「全仓无模块声明 `error`」⇒ A 组 74 → **77**（新增 §4.4 的 3 个：`http_request` / `telegram_push` / `feishu.send_message`），B 68 → **65**；§4.0 判据②补第三条；§3.3 补 `error 不为空` 一行；§8 的脚本同步补 `ErrorJudge` 判据（否则脚本会印出与 §4.0 不一致的 74），并把脚本内注释改为 ASCII + 补「存 .ps1 必须带 BOM」的说明；§8 补第 4 条扫描坑 |
| v1.0 | 2026-10-14 | 首版。机制现状（§1）、四处「读不到」（§2）、运算符语义陷阱（§3）、206 模块全量清单（§4）、用法模板（§5）、评估与落点（§6/§7）、复算脚本与三个扫描坑（§8） |

**待办 / 未覆盖**：

- 无真机验证项：本文所有结论均为源码直读 + 静态扫描。
- 未覆盖 `:core` 模块（独立进程）自身的异常处理；未覆盖触发器执行路径（`seedTriggerOutputs` 在触发器返回 `Failure` 时直接 `throw IllegalStateException`，`:529-531`，与步骤级策略无关）。
- ~~§7 方案 A 尚未实施~~ ⇒ **已实施**（2026-10-15）：`BaseModule.getDynamicOutputs` 追加合成 `success` + 执行器成功分支注入 `true`（不覆盖模块自己给的 `false`）+ AI 侧同一口径。四项 P0 均已实现，**真机未验证**；剩余未做项（环警告卡片标红 / `PickerType.STEP` / 目标被删时的引用重映射 / `vflow.logic.jump` 的 B1–B4）记在设计稿 §9.1。
