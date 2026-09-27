# Fork 维护说明

本仓库是 [ChaoMixian/vFlow](https://github.com/ChaoMixian/vFlow) 的长期维护 fork：

- `origin`   → https://github.com/nightking8342/vFlow （本 fork，主干 `dev`）
- `upstream` → https://github.com/ChaoMixian/vFlow （上游）

维护原则：**控制 diff 面积**。能加新文件就不改上游文件，能走新增模块就不动核心代码，让每次上游合并的冲突尽量少。

本项目与 mindfs fork 不同：**以「新增能力」为主**（新增模块、新增 handler、新增工具脚本），而非深改上游核心文件。因此本表的分歧形态大多是「新增文件 / 新增模块」，冲突面天然较小。

---

## 与上游的分歧清单

每一处与上游的故意分歧都记录在这里。合并上游遇到冲突时，按「归属」列决定保留哪边；新增分歧时必须同步更新本清单。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/src/main/java/.../server/logcat/`（`LogcatLineParser.kt` + `LogcatMatcher.kt` + `LogcatConditionCodec.kt` + `LogcatEventQueue.kt`，均新增） | fork 独有：**logcat 触发器的 Core 侧纯函数层**（解析 / 匹配 / 条件解码 / 有界事件队列）。是 app 侧同名纯函数的移植版——Core 没有测试目录的说法已不成立（见下），两份实现各自有测试保护。⚠️ **任何语义改动必须同时改两处，且以 app 侧为准**；不一致的表现是「调试工具里看着能匹配的日志，触发器匹配不到」。⚠️ `LogcatEventQueue.clear()` **故意只丢积压事件、不清 `dropped`/`accepted` 计数** —— 丢弃数要留到 `drainDropped()` 上报为止（它是用户知道"丢过日志"的唯一途径），在 clear 里清零会让那次上报永远发不出去。有测试锁住这个语义；需要连计数一起清时用 `resetAll()` | 我方 |
| `core/src/main/java/.../server/wrappers/shell/LogcatStreamWrapper.kt`（新增） | fork 独有：logcat 触发器的 Core 侧流式实现（长驻 `logcat -T 1` + 双线程：泵线程读 stdout 解析匹配、主线程读控制帧）。走文档 §6.3 方案 A 注册进 `serviceWrappers`（不包装系统服务）。含背压保护：有界队列 + 独立写线程，丢弃计数定期上报。**2026-09-21 改为「代次隔离」**：原先泵/写线程共用一个 `@Volatile running` 布尔 + `if (pumpThread?.isAlive) return` 守卫 —— 装包时 App 被强杀、socket 不发 FIN，旧泵仍活着，新订阅被守卫挡住而不启动新泵 → **触发器失灵，重启 App 或重存工作流才恢复**。现在用 `AtomicLong generation` + `@Volatile activeGen`，泵只认自己那一代的代号，新流订阅必定建新泵、旧泵下一轮自行退出。⚠️ **已知残留**：写线程阻塞在 `writer.println` 时 `interrupt()` 无效（Java `soTimeout` 只管读、无 `SO_SNDTIMEO`）、被取代连接的 `controlLoop` 无 FIN 时永久卡在 `readLine`、`conditions` 不按代隔离 —— 三者同一根因（无断流检测），根治需协议层心跳并动 `StreamingWrapper`/`BaseWorker`（上游文件，见敏感点清单） | **手动合并** |
| `core/src/main/java/.../server/wrappers/StreamingWrapper.kt`（改） | `handleStream` 增加 `reader` 参数，把单向推送升级为**双工**。原先只有 writer，流建立后这条连接再无上行数据，`updateTriggers` 无处投递。唯一既有实现 `IClipboardWrapper` 忽略该参数 | **手动合并**（签名变更，上游若加新的 StreamingWrapper 实现需同步） |
| `core/src/main/java/.../server/worker/BaseWorker.kt`（改） | `tryHandleStreamRequest` 透传 `reader` 给 `handleStream` | **手动合并**（1 行） |
| `services/VFlowCoreBridge.kt`（改） | 新增 logcat 流式 API（`streamLogcatEvents` + `updateLogcatTriggers`）与 **dex 指纹机制**（`packagedDexFingerprint` / `isCoreDexNewerThanRunning` / `recordLaunchedDexFingerprint` + 顶层纯函数 `coreDexFingerprint` / `shouldPromptCoreRestart`）。流式 API 的关键是**保留 writer** 供后续控制帧使用（双工） | **手动合并**（新增方法为主） |
| `services/CoreLauncher.kt`（改） | **启动验证成功后**调用 `recordLaunchedDexFingerprint(context)` —— 这是"改了 core 需重启"判据的另一半。⚠️ **2026-09-23 补**：该行在 `ef248ab6` 引入指纹机制时**漏了**（`CoreLauncher.kt` 当时根本没被改动），此后一直无调用方 → SharedPreferences 里那个 key 恒为 null → `shouldPromptCoreRestart` 的「从未记录」分支恒真 → **永远不提示重启，用户静默跑旧 Core 代码**。而 13 个纯函数单测全绿（它们只喂纯函数、不覆盖集成点）—— 本仓库因此踩过三次同类坑，故补了源码扫描型测试锁住调用点。⚠️ 必须放在**确认 Core 真的起来了**之后，部署成功但启动失败时记录会让下次不再提示 | **手动合并**（新增 1 行 + 2 例测试） |
| `ui/home/HomeScreen.kt`、`ui/settings/CoreManagementActivity.kt`（改） | `coreNeedsUpdate` 的判据由"版本号落后"改为 **dex 指纹变化**（`versionStatus.needsUpdate \|\| isCoreDexNewerThanRunning()`） | **手动合并**（各 1 处判断 + 文案） |
| `ui/home/HomeScreen.kt`（改） | ② `LogDetailDialog`（点「最近日志」里任一条弹出的详情弹窗）加长按选择复制 + 复制按钮：`SelectionContainer` 包在该弹窗 `text` 槽的滚动 `Column` **外面**（跨块连续选中）；`dismissButton` 槽改为「复制」（全量纯文本，取值与 `text` 槽渲染内容逐字一致，为此把 `basicInfo` / `executionDetails` 提为局部变量共用），复制后**不关弹窗**。⚠️ 只包这一个弹窗，**不包日志列表本身**——列表行的单击语义是「进详情」，在里面加选择手势会和它抢同一个长按超时点 | **手动合并**（1 处包裹 + dismissButton 改写 + 2 个提为局部变量） |
| `res/layout/dialog_execution_error.xml`（改） | ① 原「工作流名 / 出错模块 / 错误原因」三个 `TextView` **合并为单个 `text_error_detail`** —— `textIsSelectable` 的手势与选区都收在单个 TextView 的 Layout 内部，拆成多个 View 就只能一行一行选、拖不出跨行选区（首版拆三段，用户反馈"只能一行一行复制"）；层级改由 `Spannable` 表达，代码侧见 `OverlayUIActivity.buildErrorDetailSpanned` / `buildErrorDetailPlainText`（两者必须逐行对应）。② 加 `common_copy` 复制按钮 | **手动合并**（整个卡片内层重写 + 2 个新方法） |
| `core/build.gradle.kts`（改） | ① 加 `testImplementation("junit:junit:4.13.2")` —— **core 是纯 JVM 模块，可以放 src/test 跑 JUnit**（文档里"core 没有测试目录"只是现状描述）；② `vflowCoreVersion` 注释重写，明确 **🚫 不要在开发过程中改它**（只在发版时改） | **手动合并** |
| `ui/chat/ChatMarkdown.kt`（改） | **三处 fork 定制**：① `ChatMarkdownContent` 内层包 `SelectionContainer`，使 assistant 正文 / 思考过程 / 工具结果 / 错误提示**四处一次覆盖**均支持长按选中复制。⚠️ `modifier` 给 `SelectionContainer` 而**不给里面的 `Markdown`**（调用点用它做折叠态 `heightIn` + `clip` 裁剪，须作用在最外层）。⚠️ mikepenz renderer 内部是 `Column` 而非 Lazy，选区不会因项回收失稳；但**选择范围限于单条消息内**（消息列表本身是 LazyColumn，跨条拖选会与滚动抢手势）。各消息卡片的复制按钮 / 审批按钮 / `MessageFooterRow` 都在 `ChatMarkdownContent` **之外**，不受影响。② **表格单元格不截断**：库默认 `maxLines = 1` + `TextOverflow.Ellipsis`（`MarkdownTable.kt:95/132/161`），即**每格单行、超出截成 `...`**。走库留的 `table` 组件钩子 + `MarkdownTable` 的 `headerBlock`/`rowBlock`，只改 `maxLines = Int.MAX_VALUE` + `overflow = Clip`，其余布局（横滚/分隔线/圆角/背景色）全部复用。**对照过四家头部 Agent（ccb/dsh/opencode/pi）：没有任何一家截断单元格，全部折行**。③ **标题字号覆盖**：库默认把标题映射到 M3 的 display/headline 档（`MarkdownTypography.kt:17-22`），`h2 = displayMedium = 45sp` 而正文仅 16sp，**2.8 倍落差**（用户实际反馈）。改走 `markdownTypography()` 压回合理台阶（h1=24sp / h2=22sp / h3-h6 递减）。⚠️ **一律不设 `color`**：`MarkdownText` 把 `style` 经 `pushStyle` 写进 span（`MarkdownText.kt:63-64`），其 color 会**覆盖** `markdownColor(text=)` 的主题色；曾试图用 `Color.Unspecified.copy(alpha=)` 做层级，实际会得到**半透明黑**（深色主题下变黑字）。 | **手动合并**（3 处定制 + 注释） |
| `ui/chat/ChatScreen.kt`（改） | ① `UserMessageBubble` 的用户消息是纯 `Text`（不走 `ChatMarkdownContent`），单独包一层 `SelectionContainer`。② **消息卡片宽度上限按可用宽度推导**：原先 5 个硬编码常量（340/560/540/520dp），折叠屏展开态（实测 MIX Fold 3 **871dp × 982dp**）下卡片只占一半宽、右侧大片留白。改为 `BoxWithConstraints` 取 `maxWidth`，经 `chatMessageMaxWidths()` 推导。⚠️ **两个踩过的坑**：(a) **别借屏幕尺寸断点**——最初复用 `MainComposeShell.kt:249` 的 `840.dp`，但那是「是否显示侧边导航栏」的判据（还要求 `宽 > 高`）；展开态 871dp × 982dp 过不了那个 `宽 > 高`，`availableWidth = 839dp` 恰好差 1dp 落回窄屏分支，**改动完全不生效**。(b) **门槛必须统一**——曾逐角色比较（`available > 该角色基准` 才放宽），导致 340dp 的用户气泡在普通手机（412dp 屏）上被放宽到 380dp，**悄悄改掉窄屏行为**。现在只在可用宽度超过**最大**基准值（560dp）时整体放宽。**验证**：手机各形态（328/361/380/448dp）全部返回原值（与改动前逐像素一致），展开态 assistant 560 → 839dp。 | **手动合并**（宽度策略 + 参数下传） |
| `ui/main/MainActivity.kt`（改） | `onStop()` 里 `task.taskInfo.baseActivity?...` 补安全调用 `?.`。**起因**：`compileSdk 37` 把 `RecentTaskInfo.taskInfo` 收紧为可空（此前隐式非空），编译失败。语义不变：取不到 `taskInfo` 时与「拿不到 `baseActivity`」一样跳过该任务 | **手动合并**（1 处安全调用 + 注释） |
| `test/services/CoreDexFingerprintTest.kt`（新增） | fork 独有：**15 例**。锁住"该提示却返回 false"（会让用户静默跑旧代码）与"不该提示却返回 true"（提示不可信）两个方向。**2026-09-23 补 2 例源码扫描型测试**，锁住集成点：`CoreLauncher` 必须真的调用 `recordLaunchedDexFingerprint`、且必须在 `deployDex` 之后。这两例的存在理由见上一行那个缺陷——纯函数测试再全也测不出「调用方缺失」 | 我方 |
| `core/src/main/java/.../server/VFlowCore.kt`（改） | `tryRouteStreamRequest` 的流式白名单由**硬编码 clipboard** 改为查 `Config.STREAM_METHODS`。⚠️ 这是上游核心文件，且坑很深：Core 有**两条路由路径**（普通请求查 `ROUTING_TABLE`、流式请求查这张白名单），只改前者时流式请求会被拦下后**落到普通路径转发**，而那条路承载不了长连接 —— 表现为 `ping` 正常但流报 `ECONNREFUSED`（已实际踩过） | **手动合并**（改动集中在 `tryRouteStreamRequest` 一个方法） |
| `core/src/main/java/.../server/common/Config.kt`（改） | ① `ROUTING_TABLE` 追加 `"logcat" to WorkerType.SHELL`；② 新增 `STREAM_METHODS`（流式订阅方法 → target，与 `ROUTING_TABLE` 同桌以便同时看到）；③ `init` 加一致性校验 —— 流式表里的 target 必须在路由表里，否则启动时报错 | **手动合并**（追加） |
| `core/src/test/java/.../server/common/RoutingTableConsistencyTest.kt`（新增） | fork 独有：**在构建期**拦住"新增流式能力漏改一张表"（5 例）。这类错误的表现是流静默建立不起来，靠读代码很难发现 | 我方 |
| `core/src/main/java/.../server/common/Config.kt`（改） | `ROUTING_TABLE` 追加 `"logcat" to WorkerType.SHELL`。⚠️ **只注册 `serviceWrappers` 不够**——那张表是"谁能处理"，这张才是"转发给谁"；漏了请求会回 `{"error":"No route"}` 且流建立不起来（已实际踩过） | **手动合并**（追加一行） |
| `core/src/main/java/.../server/worker/ShellWorker.kt`（改） | 追加 `serviceWrappers["logcat"] = LogcatStreamWrapper()` | **手动合并**（追加一行） |
| `core/build.gradle.kts`（改） | ① 加 `testImplementation("junit:junit:4.13.2")`——**core 是纯 JVM 模块（java-library + kotlin jvm），可以放 src/test 跑 JUnit**，文档里"core 没有测试目录"只是现状描述而非限制。② `vflowCoreVersion` 19 → 22（见下方敏感点） | **手动合并** |
| `core/src/test/java/.../server/logcat/`（新增） | fork 独有：**本仓库 core 模块的首个测试目录**（66 例）。覆盖索引扫描解析、条件匹配与级别位掩码、条件编解码、有界事件队列的丢弃策略。这些类跑在热路径上且失败模式是"触发器静默不触发"，必须有测试 | 我方 |
| `FORK.md`、`AGENTS.md`、`CLAUDE.md` | fork 独有文件，上游没有 | 我方 |
| `docs/fork/function-workflow.md`、`docs/fork/function-workflow-ui.html` | fork 独有：函数工作流需求文档 + 可交互 UI 原型（上游无此文件） | 我方 |
| `docs/fork/do-not-disturb-trigger.md` | fork 独有：**免打扰模式触发器**功能文档（需求/设计决策/实现状态/真机验证待办/自触发环风险）。含 AOSP 源码核实的 API 选型结论，上游无此文件 | 我方 |
| `docs/fork/logcat-trigger-design.md` | fork 独有：**logcat 触发器设计文档**（Core 侧匹配架构、多触发器共享一条流、`LogcatCondition` 条件数据结构、**§6.5 双工协议扩展**、**§7.1 必须继承 `BaseTriggerHandler`**、降级语义、背压/隐私风险、14 项真机场景）。2026-09-17 修订过三处硬伤；尚未实现；上游无此文件 | 我方 |
| `docs/fork/xposed-channel-design.md` | fork 独有：**Xposed 通道架构设计**（**v2.0**，设计阶段未实现）。第四条通道 = 事件源 + `MethodHook`。基于 **ShortX 反编译源码逐动作判定** + vFlow 侧代码直读 + **本机真机探针实测**。核心结论：**不照抄 ShortX**（权威进程在 system_server，搬走会丢权限分档与崩溃隔离，§1.0）；**撤回初稿「无界 hook 导致 AI 契约失效」的论证**（`shell_command`/`js` 已是反例，§1.1）；Xposed 真正独占的只有四类，仅 ②（IME/输入内部状态，**是触发器的事件源**）与 ③（系统栏/窗口内部方法）值得做（§2.1/2.2）。**v2.0（探针实测驱动，§4.2 整体重写）**——本轮修正了一处**方向性错误**并定案通道选型：① ⚠️ **此前把「hook 目标 App（5a）」的结论套到了「hook system_server（5b）」上**，而我们要做的 `activity_changed` 是 5b。**AOSP 源码** `AppsFilterBase.shouldFilterApplication` 首行 `callingAppId < FIRST_APPLICATION_UID ⇒ return false`（不过滤）⇒ **uid 1000 完全豁免包可见性**；② **主通道定为 `bindService`（AIDL）**，广播降为备选——理由是 5b 不受可见性限制（源码）、**连接状态天然就是 §3.3 要的「hook 挂载态」判据**、双向且无广播的频率/大小限制；③ **实测证实**：`broadcastPermission` 方向 =「发送方需持有」（v1.6 曾标「未逐字核实」）、`signature` 权限**能挡住异签方**（对照矩阵）、**广播不受包可见性影响**（对 vFlow 完全不可见的 App 发广播，vFlow 照收）、**共享文件读不到**（`EACCES`）、`bindService` 在 5a 需可见性（A 组 false / C 组 true）；④ 更正两处仓库侧论据（`postWindowChangeEvent` 第三参数**未进事件流**、`AppStart` 去重**不是缺陷**，§4.1）与一处风险低估（**hook 目标 App 可读其全部内存，信息面高于 root**，§5.3）；⑤ 新增 §4.4.1 **Xposed API 选型**（ShortX 实测两套并存：传统 `xposed_init` vs 新 `libxposed.service.XposedProvider`，**文档此前写死了前者，需修正**）；⑥ §4.2.8 记录一处**官方未记载的行为**（声明某权限 ⇒ 其**定义方**可见，v4/v5 决定性证据：名字前缀不算、`sourcePackage` 才算——**不要当规则用**）。**§8 地基项由 3 条收敛为 2 条**：**system_server 内能否 `bindService`**（含主线程重入/阻塞风险）、**system_server 能否持有 vFlow 的 `signature` 权限**——两者都**必须真注入 system_server 才能验，有整机软重启风险**，§6 给出渐进推进方案（1a 只打日志 → 1b 加通信 → 1c 验权限 → 1d 换真 hook 点）。**教训入册（决策 19/20/21）**：① **必须区分 5a/5b 的可见性处境，不得混用结论**（混用导致通道排序整体错了一轮）；② **平台行为结论必须先查文档/源码，再用实验验细节**——前几轮用「实验 + 推理」替代查文档，导致每被追问一次就翻一次结论（实验只回答「此条件下发生什么」，不是普适规律）；③ **每条结论标注证据类型**（【文档】/【源码】/【实测】/【推断】）。**新增 §3.4 可扩展性架构（决策 22–26）**——**`activity_changed` 只是引子**，长期要加很多触发器/模块，评价标准是「加到第 30 个时还站不站得住」：① **三层层级**（适配器 HookSource / 统一信封 / 可替换 Transport，§3.4.1）；② **事件信封** `{topic, seq, ts, payload}`，`seq` 使丢包可检测（§3.4.2）；③ **新增触发器 = 框架 + 适配器**，**复用现有 `TriggerHandlerRegistry` 规范不另立平行体系**（§3.4.3）；④ **背压/丢弃必须在框架层统一**（各写各的必然出现「阻塞拖垮 system_server / 无界 OOM / 静默丢」，按 `LogcatEventQueue` 语义，§3.4.4）；⑤ **协议版本协商**（App 与 hook 层生命周期独立、hook 无热更新 ⇒ 版本错配是常态，未知 topic 必须忽略而非崩溃，§3.4.5）。§3.1 架构图同步重画（旧图引用的是已废弃的「方案 A/B」，且只画了单触发器结构，扩展不了）。**§4.4.1 定 API 选型**（决策 27）：用 **`io.github.libxposed:api:102.0.0`**（Maven Central 最新），**非传统 `xposed_init`**——反编译 ShortX（用 101）实证新 API 是主路径，且**其入口分工（`onModuleLoaded` 初始化运行时 + 可替换 adapter / `onSystemServerStarting` 进 system_server）与 §3.4 三层架构天然吻合**。**102 已在目标设备验证可用**（【实测】HyperCeiler / InxLocker 均使用 102 独有符号 `HotReloadedParam`/`getOldHookHandles`/`replaceHook` 并正常运行）。**⚠️ v2.1 推翻一处旧结论**：旧版 §4.6 断言「`MethodHook` **无**热更新」，依据仅是 ShortX（101）的 `MethodHookLifecycle` 只有两值、全仓无 reload API——**那是「反编译一家实现没做 X」，被我当成了「平台做不到 X」**。实际 **102 原生支持热更新**（源码逐字 `Hot reload allows modules to be updated without restarting the process`，回调 `onHotReloading`/`onHotReloaded` + `replaceHook`）⇒ **§4.6 重写**，并连带修正 §3.3（`PENDING_RESTART` → `PENDING_APPLY`）与 §3.4.5（协议版本前提）。新增约束：**选 102 就不能混用传统 `de.robv.android.xposed` API**（官方行为变更，决策 29）。§6 阶段 0 重新定位为验 **hot reload 实际行为** + `onSystemServerStarting` 注册形态（「102 能否加载」已不必验）；§8 新增两条待验（**hot reload 对 system_server 是否适用**、**`onSystemServerStarting` 在 Android 17 是否触发**）。**v2.2（P0 探针实测，新增 §4.2.9）**：**通道核心能力已实证** —— ⭐ **能拿到 Activity 的 Intent（含 extras）**，这是本通道存在的理由（前三条通道做不到）。hook 点 = `com.android.server.wm.ActivityRecord.activityResumedLocked(IBinder, boolean)`，经 **`ActivityRecord.forToken(IBinder)`** 反查实例后读 `intent`。**两条实现级坑（都靠实测才发现）**：**类名必须是 `com.android.server.wm.*`**（`android.app.*` 是错的 ⇒ `ClassNotFoundException`）；该方法是 **`static`、没有 `this`** ⇒ **不能用 `getThisObject()` 取值**（曾误判为「hook 失败」）。**实测事实（非规则）**：`onSystemServerStarting` 时**系统服务尚未就绪**（`PackageManager`/`IActivityManager` 均为 null），**约 11 秒后**才可用 ⇒ **启动期做 IPC 要带重试/等待**（⚠️ 注意：这是「要等就绪」的事实，**不是「禁止 IPC」**——探针实测在启动期做 IPC 完全可行，见下文「三次模块不加载」）。<br/>⚠️ **另有一条必须做的**：hook 层要等 **`UserManager.isUserUnlocked()`** 再通信（目标组件 `directBootAware=false`，未解锁时解析不到）。**调试方法（能省大量时间）**：**`adb logcat` 读不到启动期日志** —— 真因是**开机洪流把 2 MiB 环形缓冲填满**、启动日志第一个被挤掉（实测：启动 14 分钟后，18:54 的事件日志都在、启动期的 `════` 标记一条不剩；量化：开机 15 秒内约 10400 条）；抓启动期日志**必须重启后立刻抓**或用 LSPosed 管理器导出的 verbose 日志（**唯一正路**，实测 `logcat -G 16M` 重启即失效），**不要为绕过它而改探针代码**。**⚠️ 三次「模块不加载」（真因未查明，勿当规则用）**：探针 P0 期间改三次、每次都不加载（写文件 / 静态缓存+回调 flush / 写 `Settings.Global`）。⚠️ **我曾据「共性都在启动期」写成铁律「启动期整条路径不要新增 IPC / I/O」——该结论【已被反证】**：当前已实测可用的探针版本，启动期路径上就有**四处跨进程调用**（含 `isUserUnlocked()`），运行正常。**真因未查明**——三次都只做整体回退、**从未二分定位**（第 2 轮还一次改了三处）。**保留的唯一纪律是「一次只改一处 + 始终保留可回退版本」**（依据是三次失败的直接教训，约束的是改动方式而非平台行为）。§4.4.1 补齐**两个必需依赖**（`api` + **`service`**，后者提供 `XposedProvider` 实现类、必须打进 dex）与 **`module.prop` 的 `minApiVersion` 要写 101** 的坑。§8 中 **#1/#18 转为已答**。**v2.5（主通道已实证）**：① ✅✅ **`bindService` 主通道成立** —— system_server 内 `bindService` 到第三方 App **拿到 `BinderProxy@1adc174`**（P0 期间**唯一没实测过、只凭 AOSP 源码推断**的环节，现已实证）。**v2.3/v2.4 曾写的「实测推翻核心论据」「选型进入重评」【都是错的，已撤回】**。**真因是探针跑得比设备解锁早**（约早 47 秒），而目标 Service `directBootAware=false` ⇒ **未解锁时组件在 package 解析阶段就被排除**，表现与「包不可见」**一模一样**（`resolveService` 返 `null`、`getServiceInfo` 抛 `NameNotFoundException`、`bindService` 返 `false`）；加 `UserManager.isUserUnlocked()` 判据后通过。⚠️ **这条同时是正式实现的必需环节**：hook 层必须等用户解锁再通信，否则得到**假的「连不上」**。② ❌ **#15 取不到有效结论** —— 目标权限 + 两个无关第三方 signature 权限**全 GRANTED** ⇒「自己查自己一律放行」，`checkPermission(自己pid, 自己uid)` 方法本身无效。③ ✅ **读取途径定案**：`adb logcat` 拿不到（实测 `main` 缓冲仅覆盖约 **50 秒**、开机高峰 1220 条/秒），`logcat -G 16M` 重启即失效 ⇒ **唯一正路是 LSPosed 管理器导出的 verbose 日志**（**文档 §4.1 早就写着，我却绕了三轮才用上**）。④ ❌❌ **归因反面教材（本轮连错两次、方向还相反）**：先据一次**无对照**的 `null` 就写成「推翻核心论据」并改了选型；后又怀疑「组件没装」。**两次都没排除时序变量。** 教训入册：**排查顺序应为「先时序/状态（解锁、stopped、进程存活）→ 再能力/权限 → 最后平台行为」；`null`/`false`/异常必须先做对照项框范围再下结论**。另记几个陷阱：`getServiceInfo` 的 `NameNotFoundException` **不代表组件不存在**（被过滤/未解锁时也抛）；`dumpsys | grep` 判断组件存在性不可靠（`Service Resolver Table` **只列带 `<intent-filter>` 的组件**）；`query-services -p <包>` 的 `-p` 是「按 ACTION 查并限定包」，查具体组件要用 `-n`。**⑤ 方法收获（来自用户提醒而非我的推理）**：本轮能查清靠的是**用户提出「能不能延迟一下」**的**时序**方向 —— **用户对系统行为的直觉多次比我的源码推理准**。**v2.6（#17 热更新实测通过）**：✅✅ **`autoHotReload=true` + 重装 APK ⇒ 在 system_server 里触发热更新、无需重启手机**；`HookHandle.replaceHook()` **原子替换成功**，之后 hook 立刻恢复命中。**推翻 §4.6 旧「推断」**（曾怀疑框架对 system_server 策略不同）。⚠️ **两条实现要点**（实测才发现）：① **`HotReloadedParam` 没有 `getClassLoader()`**（全 API 仅 `PackageReadyParam`/`SystemServerStartingParam` 有）；② **不能用静态字段传跨代际状态** —— 热更新是**新 classloader 加载新代码，静态字段在新代际是全新的**（实测缓存 ClassLoader 到静态字段 ⇒ 新代际读到 `null`，印证官方「saved state must not contain objects created under the old module classloader」）。**正解：`replaceHook()` 不需要 ClassLoader；回调需的类从 `chain.getExecutable().getDeclaringClass()` 推；且回调必须 `proceed()`**（否则破坏整机行为）。**P0 五条验证项全部有结论：#1/#14/#17/#18 通过，#15 方法无效。** **v2.7（`getRemotePreferences` 已查清）**：① ❌ **它不能做事件上行** —— 官方源码逐字「**read-only in hooked apps**」（`XposedInterface`），**只有模块 App 能写**（`XposedService` 侧）⇒ 数据流是「App 写 → hook 读」，**与事件上行方向相反**（新增 §4.2.2.1）。② ⚠️ **新增 §4.6.1「热更新 ≠ 配置通道」**（官方明文：「Hot reload is intended for loading a new module generation after the module app is updated. **It should not be used to propagate configuration changes.**」）⇒ **两条链路必须分开**：hook **定义**变了走热更新（低频）、hook **规则**变了走通信通道（高频）；本方案架构天然避开此坑（§3.4.1 硬约束「Hook 层不知道工作流的存在」）。③ ✅ **独立印证 §4.2.2 选型**：`bindService` 仍是 Xposed 通道里**唯一的双向通道**。④ ⚠️ **一处未能判定**（两份来源冲突、未实测）：system_server 里 `RemotePreferences` 的变更回调是否投递（项目方实测断言「不投递」vs 源码分析「会投递」）—— **仅影响配置下行实时性，不影响事件上行**。上游无此文件 | 我方 |
| `docs/fork/logcat-debug-tool.md` | fork 独有：**logcat 调试工具设计文档**（前置工具，用于采集日志调试触发器）。核心是「开关式后台采集（shell 侧写文件、`-r`/`-n` 轮转）+ 快照兜底」，**全程走普通 `exec`、不碰流式协议**。含**两条已真机实测证实**的硬约束（Binder 传输上限；后台进程不切断 stdio 会永久挂起）、三态采集状态机（`IDLE`/`CAPTURING`/`STALE`）、单一刷新按钮与 TAG 统计绕过过滤、降级行与日志头标记行处理、与触发器的纯函数复用关系。**§4.2.3 记录实现期踩出的三个静默失效坑**（`StateFlow` 等值去重不发射、`timeout` 自然收尾同样留 `STALE` 导致假异常提示、自动停止不可在 ticker 协程内取消自己）。**部分实现**：纯函数层 + 超级岛层 + 采集控制器 + 岛按钮接收器已完成，查看器 UI 与导出待做；上游无此文件 | 我方 |
| `core/workflow/module/triggers/DoNotDisturbTriggerModule.kt`、`.../handlers/DoNotDisturbTriggerHandler.kt`（均新增） | fork 独有：免打扰触发器。走 `ACTION_INTERRUPTION_FILTER_CHANGED` 广播，只需 `NOTIFICATION_POLICY` 权限（不需通知使用权）。含顶层纯函数 `isDndFilterEnabled` / `isDndEnabled` / 日志探针 `probeDndState` | 我方 |
| `res/drawable/rounded_do_not_disturb_on_24.xml`（新增） | fork 独有：免打扰触发器图标（上游无勿扰图标，`DoNotDisturbModule` 借用的是 `rounded_notifications_unread_24`） | 我方 |
| `test/.../triggers/DoNotDisturbTriggerMathTest.kt`、`DoNotDisturbTriggerModuleTest.kt`（均新增） | fork 独有：上述纯函数逐值锁定 + 枚举规范化 + 权限/输出声明体检 | 我方 |
| `core/workflow/module/ModuleRegistry.kt` | `initialize()` 按分类追加注册 `DoNotDisturbTriggerModule`（不重排已有注册） | 手动合并（追加一行，取上游 + 追加） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 按分类追加注册 `DoNotDisturbTriggerHandler`（不重排已有注册） | 手动合并（追加一行，取上游 + 追加） |
| 字符串资源 `strings_module.xml`（中/英/日） | 追加免打扰触发器文案 9 条 ×3 语言 | 手动合并（追加条目） |
| 字符串资源 `strings_module.xml`（中/英/日） | 追加数据卡切换文案：触发器段 13 条 + 模块段 20 条，各 ×3 语言 | 手动合并（追加条目） |
| `core/logcat/`（`LogcatLine.kt` + `LogcatParser.kt` + `LogcatCommands.kt`，均新增） | fork 独有：**logcat 纯函数层**（解析 + 命令构造），供 logcat 调试工具与 logcat 触发器共用。含三处真机实测得出的硬约束：可能返回大数据的命令必须 shell 侧限流（否则撞 Binder 上限打死 UserService）、后台采集命令必须切断三个 stdio（否则 `exec` 永久挂起）、状态判定必须按 pidfile 精确匹配。**未改动任何上游文件** | 我方 |
| `test/core/logcat/`（`LogcatParserTest.kt` + `LogcatCommandsTest.kt` + `LogcatCaptureUiTest.kt`，均新增） | fork 独有：上述纯函数的 68 个单测。重点是"改错了不报错、只静默变差"的地方：降级继承链不被日志头标记行污染、连续续行不链式继承、三个 stdio 重定向齐全、TAG 统计绕过过滤、shell 元字符转义、岛存活必须长于采集上限、计时不显示负数、无法计时时不误判超时 | 我方 |
| `core/logcat/LogcatCaptureUi.kt`（新增） | fork 独有：logcat 采集的展示层纯函数（岛的图标/缓存 key/存活时长、正计时与时长格式化）。与 `LogcatCommands` 一起构成"改错了不报错"的那一层，全部有单测 | 我方 |
| `core/logcat/LogcatCommands.kt`（改） | **2026-09-21 大幅扩充**（本地调试器七项修复的 command 层）。新增：`buildFilteredCount` / `parseFilteredCount`（全量统计当前条件命中数，**只回传一个数字不回传位置索引**——命中上万条时索引会撞 Binder 上限）、`capturePatternForPs`（查杀特征串，**不带 `/system/bin/` 前缀**——`ps -A -o PID,ARGS` 的 ARGS 里没有路径）、`nextPageSkipBytes` + `contentBytesOf`（翻页按实际内容量推进）、`TIMEOUT_EXIT_CODE` + `isTimeoutResult`、`MIN_READ_WINDOW_KB`、`killCapturedProcesses(rotateKb, rotateCount)`（参数化）。⚠️ 改动集中在命令构造函数，若上游改同区域需逐块判断 | **我方** |
| `core/logcat/LogcatViewerState.kt`（改） | fork 独有文件的扩充（`buildViewerResult` / `diagnoseEmpty` / `buildCoverage` 等纯函数层）。上游无此文件 | 我方 |
| `ui/settings/LogcatViewerActivity.kt`（改） | fork 独有文件（logcat 调试器 UI）。本批七项修复集中在此：超时链路接通、翻页游标、窗口口径统一、刷新提示、下拉菜单锚点、滚动轴重做（命中区 24→40dp、**删掉时间预览气泡**）、静默吞异常。上游无此文件 | 我方 |
| `test/core/logcat/LogcatCommandsTest.kt`（改） | 本批新增约 40 例，重点锁**"改错了不报错、只静默变差"**的地方：查杀特征串必须是 `ps` 输出的连续子串（且**不能带绝对路径**）、级别字符类必须带方括号（逐级别验证 + 裸写反向断言）、`wc -l` 而非 `grep -c`、翻页衔接。⚠️ 多数关键测试做过**反证**（把代码改回 bug 版本确认变红）| 我方 |
| `test/core/logcat/LogcatViewerStateTest.kt`（改） | 同上，覆盖 `buildViewerResult` 的派生逻辑 | 我方 |

| `services/LogcatCaptureController.kt`（新增） | fork 独有：logcat 采集状态机 + 计时 + 超级岛联动。进程级单例（Activity 与 Receiver 都要用）。**判定一律走 pidfile 探测，内存状态只用于显示**——App 被杀后 logcat 仍在跑，靠内存标志会误判为空闲并起第二个进程。含三处实现期踩出的坑（`StateFlow` 等值去重不发射、`timeout` 自然收尾同样留 `STALE`、自动停止不可在 ticker 协程内取消自己），详见设计文档 §4.2.3 | 我方 |
| `services/LogcatActionReceiver.kt`（新增） | fork 独有：超级岛「结束」按钮的广播接收器。**必须 Manifest 静态注册**——岛按钮可能在通知发出后很久才被点击，动态注册的接收器届时已注销，表现为"按钮渲染正常、点着没反应"（已实际踩过）。用 `goAsync()` 延长生命周期 | 我方 |
| `AndroidManifest.xml`（改） | 追加 `LogcatActionReceiver` 声明（`exported="false"`，无 intent-filter——调用方走显式 Component 意图） | **手动合并**（追加声明） |
| `services/island/Island{Template,TemplateBuilder,Notifier}.kt`（均新增） | fork 独有：**通用「模板态」超级岛通知层**（走 `miui.focus.param` + `param_v2`，与现有 `IslandNotificationDispatcher` 的 RemoteViews 通道并存不互斥）。提供系统原生计时器与内置 Lottie 动图——这是 RemoteViews 通道给不了的。`IslandTemplate` 不含业务语义，任何需要岛上计时器的功能可复用。已真机验证（动图/正计时/按钮回传全部通过） | 我方 |
| `test/services/island/IslandTemplateBuilderTest.kt`（新增） | fork 独有：上述 24 个单测，逐字段对齐小米官方 stopWatch 模板的运行态与暂停态 | 我方 |
| `docs/fork/notification-island-design.md` | fork 独有：**通知机制改造设计（适配小米澎湃 OS 超级岛）**。基于官方接入文档 + 官方模板库 + mindfs 已落地实现。v2.0 定稿：26 项决策台账（§6）、三层架构（能力探测 / 参数装配 / 图标装配）、状态×样式对照表、决策理由与已知限制（§8.1–8.4）。上游无此文件 | 我方 |
| `services/island/`（`IslandCapability.kt` + `IslandNotificationSpec.kt` + `IslandParamsBuilder.kt` + `IslandIcons.kt` + `IslandNotificationDispatcher.kt` + `IslandRemoteViews.kt`，均新增） | fork 独有：**小米澎湃 OS 超级岛装配层**。能力探测（`notification_focus_protocol >= 3` + `canShowFocus`，异步一次 + 进程缓存）、厂商中立的通知语义（含 `stepName` / `statusText` / `progressText`）、`miui.focus.param.custom` JSON 装配（纯函数；**只写 custom，不写模板 key**——两者并存会让 SystemUI 走模板分支、忽略 RemoteViews）、`miui.focus.pics` 图标装配（key 必须带 `miui.focus.pic_` 前缀，否则 SystemUI 解析不到）、**展开态 RemoteViews**（浅/深/岛展开三实例，**按 workflowId 缓存复用**，只传变化字段）、对外唯一入口。厂商私有协议完全收敛在本目录内，业务侧不出现任何 `miui.*` 字符串 | 我方 |
| `res/layout/island_execution_expand_{dark,light}.xml`（新增） | fork 独有：超级岛展开态 RemoteViews 布局（五行：头部 / 进度行 / 进度条 / 状态行 / 底部）。两份布局 **view id 完全一致**（仅颜色资源不同），故同一套操作代码通用。浅色版是必需的——该 RemoteViews 被通知栏与锁屏预览复用，那两处有浅色模式。**未提供 `rv.tiny`**：那是小折叠（flip）机型的折叠态视图，大折叠不需要，mindfs 的实现也不写 | 我方 |
| `res/drawable/island_rv_*.xml`（12 个新增） | fork 独有：展开态配套 drawable（状态胶囊底 / 进度条 / 结束按钮，各深浅两套）。图标用应用图标（运行时裁剪为圆形位图），故无图标圆底资源 | 我方 |
| `docs/fork/island-expand-ui.html` | fork 独有：超级岛展开态**可交互 UI 规格原型**（方案 B · 进度条整行 + 状态独占一行，含浅/深双配色、状态切换、色值 token 表与交付映射） | 我方 |
| `test/services/island/IslandParamsBuilderTest.kt`（新增） | fork 独有：装配单测 25 例（JSON 结构、A 区「进度 · 步骤名」拼接与分隔符省略、B 区模块实时状态、状态映射、浮出策略、强调色差异） | 我方 |
| `test/services/island/IslandCustomParamsTest.kt`（新增） | fork 独有：自定义路径（`param.custom`）装配单测 14 例。关键断言：扁平结构无 `param_v2` 包裹、**自定义模式必须携带完整 `param_island`**、**两条路径的岛数据逐字段一致**（保证改用 RemoteViews 不影响大岛/小岛） | 我方 |
| `test/services/ExecutionNotificationIdTest.kt`（新增） | fork 独有：通知 ID 派生回归测试（区间合法性、与既有通知 ID 不冲突、稳定性、负 hashCode、空 ID） | 我方 |
| `services/ExecutionNotificationManager.kt` | ① `ExecutionNotificationState` 新增 `Failed` 子类（此前失败复用 `Cancelled`，无法区分「失败」与「用户停止」）；② 通知 ID 由固定 1998 改为 `executionNotificationIdFor(workflowId)` 派生到 `[100000, 150000)`（修复并发执行互相覆盖 + 取消误删）；③ `cancelNotification()` 改为 `cancelNotification(workflowId)`；④ 两个 build 方法补 `setContentIntent`（此前点击通知本体无反应）；⑤ `notify` 经 `IslandNotificationDispatcher.dispatch` 附加岛参数；⑥ `initialize` 加一次异步能力探测；⑦ 新增 `islandSpecOf` 状态映射与 `isStepTransitionMessage`（区分「步骤切换」的导航文案与模块自报的实时状态，前者丢弃）；⑧ 新增按 `workflowId` 的计时基准表与当前步骤名表（`Chronometer` 与步骤名用，终态清理）；⑨ 终态时调 `IslandNotificationDispatcher.releaseViews` 释放 RemoteViews 缓存；⑩ **有岛能力时不再调 `setRequestPromotedOngoing(true)`**——AOSP 活体通知提升与超级岛是互斥的渲染路径，真机实测同时存在时 SystemUI 走 AOSP 渲染、忽略焦点通知的自定义视图（表现为只有终态能显示 RemoteViews）。无岛能力时保留提升，那是 API 36+ 非小米设备上唯一的活体通知能力。上游若改这几个方法需逐块判断 | **手动合并**（改动集中在 `notify` 调用与状态分支） |
| `core/execution/WorkflowExecutor.kt` | ① 失败与超时改用 `ExecutionNotificationState.Failed`（两处）；② finally 块的「3 秒后取消通知」加条件——失败与超时保留通知给用户查看，不再无条件取消；③ `executeWorkflowInternal` 增加 `isSubWorkflow` 参数，**子工作流不再发通知**（修复：子工作流的通知收尾不在主工作流的 finally 里，会以 Running 状态永久残留；该缺陷此前被固定通知 ID 掩盖）。改动集中在 `:326-328`、子工作流调用点与三处通知加条件，`updateState` 对外签名未变，9 个 `updateState` 调用点均未改动。上游若改执行器收尾逻辑需逐块判断 | **手动合并** |
| `docs/fork/chat-agent-enhancement-plan.md` | fork 独有：Chat Agent 四点改造方案（技能目录化/Prompt 缓存/catalog 全量化+模块查询工具/悬浮窗），上游无此文件 | 我方 |
| `docs/fork/chat-agent-rearchitecture.md` | fork 独有：**Chat Agent 架构重构设计**（基于 **CCB / dsh / OpenCode / Pi 四家**源码对照）。v1.5.3 为决策定稿版：三病症诊断、四家技能注入位置与工具暴露策略对照（含"内建工具 vs 扩展工具"的关键区分）、目标架构（**工具 72→16**：撤出 59 个模块工具，改由 `query_module_schema` + `call_module` + `load_skill` 按需）、**查询域≠调用域**的设计 B、两批执行契约与验收项（§4）、长期分叉的接管范围（§5.1）、决策台账（§6）。上游无此文件 | 我方 |
| `docs/fork/workflow-read-write-tools.md` | fork 独有：**工作流读写工具设计**（`get_workflow` / `update_workflow`）。v2.1（**P0 已实现并验证**），40 项决策台账（§9）：**操作原语补丁**入参形态（`metadata` / `steps{update,insert,delete,move}` / `triggers{update,insert,delete}`）、token 成本对比、固定执行顺序与原子性、按结果状态重算风险等级。**核心是五处静默失效点的处理**（§3）：`vflow.logic.jump` 的 1-based 显示序号在增删步骤后错位（含变量型 jump 无法静态重映射）、步骤 id 锚定禁掉 `step_N` 兜底、**块结构破损的执行期失败模式以静默跑错为主**（If 缺 END 时条件不成立照样往下执行）、函数签名改动静默废掉引用方、触发器 id 变化断 `{{triggerId.outputId}}` 引用（输出按 id 键控）。**块结构校验与逆补丁回滚降级到 P2（只探测/警告，不拦截）**。上游无此文件 | 我方 |
| `core/workflow/WorkflowPatch.kt`（新增） | fork 独有：`update_workflow` 的**纯函数补丁层**（参数按 key 合并 + `null` 删键、`insert`/`delete`/`move` 列表手术、jump 序号重映射、块成员判定）。无 Android 依赖，故 22 例语义单测可跑纯 JVM。**关键语义**：`move.to_index` 按「补丁前」列表解释（流水线里 move 在 delete 之后）、遗漏≠删除、jump 目标被删必须拒绝 | 我方 |
| `test/core/workflow/WorkflowPatchTest.kt`（新增） | fork 独有：上述 22 例。锁「改错了不报错、只静默变差」的语义；关键用例已做**反证**（改回 bug 版本确认变红） | 我方 |
| `core/workflow/WorkflowJumpReferenceUpdater.kt` | ⚠️ **P0 未改动**——新增的 `remapJumpReferences` 放在新文件 `WorkflowPatch.kt` 里，既有 `remapAfterReorder` 原样保留（编辑器拖拽调用点依赖它的数量相等前置条件）。见 `docs/fork/workflow-read-write-tools.md` §10 | （暂未分歧） |
| `ui/chat/ChatAgentToolRegistry.kt`（改） | 新增 4 个常量 + 2 个工具定义（`get_workflow` / `update_workflow`）+ 4 个 schema 构造；`toolsByName` 注册 2 项。该文件已认长期分叉 | **我方**（认长期分叉） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改） | ① `ChatPreparedToolItem` 追加 `UpdateWorkflow` 子类（含 `workflowId` / `warnings`）+ 补齐 5 处 `when` 分支；② 新增 `prepareGetWorkflow` 一系（含**共用错误构造 `workflowNotFoundResult`**，`get_workflow` 与 `update_workflow` 同用，避免口径漂移；**刻意不做「最近似 id」**——id 间无近似关系，模糊匹配等于用名字选目标）；③ 新增 `prepareUpdateWorkflow` / `applyMetadataPatch` / `applyStepPatch` / `applyParameterPatch`（**以步骤现有参数为 base**，非模块默认值）/ `collectTouchedStepIds`（`validate` 只跑本次触及的步骤，避免卡住存量工作流）/ `executeUpdateWorkflow`（**落盘异常时回写原件**——本项目无版本历史）/ `buildUpdatedWorkflowResultText`。改动集中在这几处 | **手动合并** |
| `ui/chat/ChatAgentNativeTooling.kt`（改） | `ChatAgentToolBackend` 枚举追加 `UPDATE_WORKFLOW`（1 行）。该文件已认长期分叉 | **手动合并**（追加枚举值） |
| `ui/shortcut_picker/ShortcutPickerSupport.kt`（改） | **多 Intent 取末项 + 正则界定修正 + 补 `pkg` 分支**（`buildLaunchCommand`，改动集中在该函数）。① 一个快捷方式可携带多个 Intent，语义是「前面的负责堆栈回退，**最后一个才是启动目标**」（`ShortcutInfo.getIntent()` 返回 `mIntents[length - 1]`），上游取首项会启动到错误目标——真机 15 条多 Intent 里 12 条的**首项清一色是「主界面 + `flg=0x1000c000`」兜底项**，取首项等于「打开 App 主界面」，功能完全不生效；② 正则收尾 `\]` 改为 `\}` 界定——多 Intent 时数组的 `]` 落在整段末尾、中间项后面是逗号，原写法会让 `.*?` 吞掉整段、把多个 Intent 的键值混成一个；③ `pkg=` 此前被整键丢弃（53 条含该键，其中小米「垃圾清理」等**只有 `act`+`pkg`、无 `cmp`**，丢掉即无任何定位信息），补 `-p` 分支。⚠️ **未改动之处**：dat 驱动的快捷方式（美团扫一扫等）**依然打不开**，因为 `dumpsys` 打印的 dat 结构性残缺（AOSP 疏漏，`ShortcutInfo.java:2681` 打印 `intents=` 时漏传 `secure`），非解析层可解（本机量化：`cmp` 可靠 74.5% / dat 残缺致失败 **18.1%** / 先天无定位信息 6.4%）。根因、量化与方案对照见 `docs/fork/surveys/shortcut-system-overview.md` | **手动合并**（改动集中在 `buildLaunchCommand`） |
| `test/ui/shortcut_picker/ShortcutPickerSupportTest.kt`（改） | 新增 3 例锁住上述语义：多 Intent 取末项（**反向断言**首项的 `MainActivity`/`shortcuts` extras/`flg` 都不出现）、只有 `act`+`pkg` 时保留 `-p`、`pkg` 与 `cmp` 并存时两者都留。两处修复均做过**反证**（改回 `firstOrNull`、删 `pkg` 分支，对应测试分别变红） | **我方** |

### Chat Agent 架构重构（第二批，2026-09-14）—— 本表冲突面最大的一批

> ⚠️ 这批改动**直接修改上游核心文件**，与此前「只加新文件」的 fork 风格不同。
> 已在 `docs/fork/chat-agent-rearchitecture.md` §5 做过冲突面评估，决策是**认长期分叉**。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `ui/chat/ChatAgentSkillRouter.kt` | **大幅重写**（847 → 193 行）：删除 `selectSkills` 及四套关键词机制（`SkillRule` / `CONTINUATION_SIGNALS` / `KNOWLEDGE_QUESTION_SIGNALS` / `OPERATIONAL_SIGNALS` / `moduleIds` 白名单 / 12 个辅助函数）；`ChatAgentSkillDefinition` 去掉 `toolNames`/`moduleIds`/`relatedSkillIds` 三字段；`SKILL_CATALOG` 清空；`ChatAgentSkillSelection` 去掉 `skills` 字段；新增 `availableTools()` 与 `skillListing()`/`skillInstructions()` 访问器；`buildSystemPrompt` 重写（去 "Active skills:" 段，加 `<available_skills>` 段与 6 条上提的规则，再加 1 条工具输出截断契约） | **我方**（认长期分叉） |
| `ui/chat/ChatAgentToolRegistry.kt` | `toolsByName` 撤出 59 个模块工具（`buildDirectToolDefinitions()` 不再进常驻表）；新增 **5 个**工具定义 `load_skill` / `query_module_schema` / `call_module` / `list_workflows` / `get_environment`；`ChatAgentToolDefinition` 加 `truncatable` 字段；`buildCompactModuleCatalog` 精简为只留 moduleId；`buildModuleOutputCatalog` 停用；新增 `getUsageScopesForModuleId()` / `getQueryableModuleIds()`；**`buildInputDescription` / `artifactTypeLabelFromTypeId` 提为顶层函数**（`buildModuleInputDescription` / `artifactTypeLabel`）供 executor 复用 | **我方**（认长期分叉） |
| `ui/chat/ChatAgentModuleExecutor.kt` | `buildParameters` 重写（并集两轮求值 + 显式报错）；新增 `resolveModuleInputDefinitions` 顶层函数、`ChatParameterBuildResult` 数据类、`prepareLoadSkill` / `prepareQueryModuleSchema` / `prepareCallModule` / `prepareListWorkflows` / `prepareGetEnvironment` / `buildRejectedKeysMessage`、**`visibleInputsForAgent` 顶层函数**（AI 侧不读 `isHidden`，只认 `visibility`）；`ImmediateResult` 加 `riskLevel` 字段（默认 HIGH）；`riskLevelOf` 改读 `item.riskLevel`；`prepareToolCall` 加五个按需入口分流 | **手动合并**（改动集中在这几个函数，若上游改同区域需逐块判断） |
| `ui/chat/ChatCompletionClient.kt` | `ChatToolResultInputFormatter.format()` 加 `toolDefinitions` 参数（按 `truncatable` 决定是否截断），三处调用点传参；截断标记由 `... truncated` 字面量改为 `buildTruncationNotice()`（结构化三段告知 + 按工具收窄建议，P2-1a）；Anthropic 路径的 `system` 由字符串改为块数组并加 `cache_control`，`buildAnthropicToolDefinitions` 最后一个工具加 `cache_control`；新增 `ephemeralCacheControl()` | **手动合并**（局部改动） |
| `ui/chat/ChatViewModel.kt` | `selectSkills(...)` 调用改为 `availableTools(...)`；日志去掉已删的 `skills` 字段（此处已有 `exportConversation` 的 fork 改动） | **手动合并** |
| `ui/chat/ChatBenchmarkRunner.kt` | `selectSkills(...)` 调用改为 `availableTools(...)` | **手动合并** |
| `test/ui/chat/ChatAgentToolingTest.kt` | 删除 15 个验证已死机制的 `skillRouter_*` 测试；改写 4 个；新增 `availableToolsPassesThroughEverythingWithoutKeywordFiltering` / `systemPromptOmitsSkillListingWhenCatalogIsEmpty` / `systemPromptCarriesTheContentSalvagedFromDeletedSkills` / `toolResultFormatter_truncationNoticeReportsOmittedSizeAndRecoveryHint` / `toolResultFormatter_truncationNoticeGivesGenericHintForOtherTools`；删除 3 个已无调用者的辅助函数 | **我方** |
| `test/ui/chat/ModuleInputDefinitionsTest.kt`（新增） | fork 独有：`resolveModuleInputDefinitions` 的回归测试（含对全部已注册模块的「静态键不丢失」体检） | **我方** |
| `test/core/workflow/module/logic/DefineFunctionAiParametersTest.kt`（新增） | fork 独有：`normalizeAiParameters` 的回归测试（简写→全限定类型转换、默认值保留、空名丢弃、JSON 字符串原样放行、零参数函数、ANY 声明、无默认值） | **我方** |
| `test/core/execution/VariableResolverTest.kt` | 补 1 例：锁定「`{{vars.<name>}}` 解析成功、裸写 `{{<name>}}` 不解析」两条分支语义。防止将来有人"放宽"裸写法时无意改变行为 | **我方**（新增用例） |
| `docs/fork/chat-float-window-design.md` | fork 独有：Chat 悬浮窗需求设计 + 实施交接（折叠/展开双形态、Application 作用域共享 VM、窗内审批 + 透明中转 Activity；§9.1 P0 验证结论、§9.2 P1 实现状态与动画失败结论），上游无此文件 | 我方 |
| `ui/chat/ChatStreamEvent.kt`、`ChatStreamAssembler.kt`、`ChatStreamNormalizer.kt`、`ChatSseStream.kt`、`ChatStreamRunner.kt`、`ChatMessagePatch.kt`、`ChatReplyNormalizer.kt`（均新增） | fork 独有：**Chat 流式输出的纯函数层 + 传输层**。事件模型 / 三条协议解码（OpenAI chat、OpenAI **Responses**、Anthropic messages）/ 流式安全文本规范化 / SSE 读取（`okhttp-sse`，三个终止回调均 `close()`）/ 帧流→事件流桥接（含 F7 截断判据）/ 消息原位补丁 / **规范化共享源**。全部无 Android 依赖、可纯 JVM 单测 | 我方 |
| `test/ui/chat/ChatStream{Assembler,Normalizer}Test.kt`、`ChatSseStreamTest.kt`、`ChatSseFixtureTest.kt`、`ChatMessagePatchTest.kt`（均新增）+ `app/src/test/resources/chat-sse/`（8 条 fixture） | fork 独有：**流式层 84 例**。fixture **可信度分级**（Anthropic 3 条=官方报文**规范级**；OpenAI/Responses 4 条=官方 SDK 的 OpenAPI 生成类型定义**字段级**；DeepSeek 1 条=dsh 实测**单 provider 级**）。⚠️ 分级不可混同——这是这类测试最危险的误用方式 | 我方 |
| `docs/fork/chat-streaming-design.md` | fork 独有：**Chat 流式输出设计** v5（含 §5.3 的 U1 更正、§6 的 19 条静默失效清单、§12 的 v3 撤回项），上游无此文件 | 我方 |
| `ui/chat/ChatCompletionClient.kt` | **v5 流式改造**：① `ChatProviderAdapter` 加 `stream()`（带默认实现退化）；② 新增公共 `streamReply()`；③ **`Accept` 头流式改 `text/event-stream`**；④ `buildChatCompletionPayload` / `buildResponsesPayload` 加 `streaming` 参数（后者**不再退化**，两套协议都走真流式）；⑤ Anthropic 的 payload 构造抽出 `buildMessagesPayload` 与 `buildHeaders`/`anthropicHeaders`（两条路径**共用**，因 `cache_control` 断点位置对缓存命中是关键）；⑥ `stripInlineToolMarkup`/`normalizeAssistantReply`/`firstNonBlank` **迁出**到 `ChatReplyNormalizer.kt`（净减 31 行）。⚠️ 该文件此前已认手动合并 | **手动合并** |
| `ui/chat/ChatViewModel.kt` | ⚠️ **行为语义变更**（`chat-streaming-design.md` §4.4/§4.7）：① 模型调用由 `generateReply`（一次性）改为 **`streamReply` 事件流 + 原位增长**（消息 id 从占位活到成品，不再 `replacePendingMessage` 换 id）；② 流式期间只写 `_uiState.update{}`、**不走 `updateUiStateAndPersist`**（避免每 delta 全量序列化落盘）；③ 收尾改走纯函数 `finalizeStreamingMessage`（`isPending` **从「靠整条被替换而消失」改为显式置 false**，这是本次最易漏的一处）；④ `stopAgent` 的 target 由 `activeConversationId` 改为 **`currentAgentConversationId`**（且在清空**之前**取出），并**保留**已生成文本（不再换成「已停止。」）；⑤ 新增 `finalizeOnInterruption` / `finishWithError`（失败时保留半截正文 + 错误作为**独立消息**追加）；⑥ 新增 `patchStreamingMessage`；⑦ **空内容兜底只在「无工具调用」时加**（与上游 `:906-909` 的两分支结构一致）——工具调用轮 `content` 本就为空是**正常情况**，无条件兜底会**错报**「模型返回了空内容。」 | **手动合并** |
| `ui/chat/ChatScreen.kt` | ⚠️ **行为语义变更**（滚动策略 + IME 布局，`chat-streaming-design.md` §4.6）：① 自动滚动的触发条件由 `messages.size` 改为 **`FollowSignal` 内容指纹**（`ChatMessagePatch.kt`，含 `lastContentLength`／`lastHasToolCalls`／`lastUserMessageId`）——流式改为原位增长后 id 恒定，只看 id/size 的旧判据**永不触发**（0.1 审批卡片被吞会复发）；② 「是否跟随」由**用户滚动结束时记录的意图**（`followTail`）决定，**不在内容到达时重算**；③ 用户手势用 `DragInteraction` 识别，抬手时按「位置是否变化」区分轻点与上翻；④ IME 触发贴底用 **`scroll { scrollBy(大值) }`**（`animateScrollToItem` 会被 effect 重启取消 ⇒ 不跟手；`requestScrollToItem` 只有「项顶部对齐视口顶部」语义 ⇒ 无法到底）；⑤ 三个底部 padding 由 `if (imeVisible)` 布尔改为**按 `padPx` 连续插值**（布尔会在某一帧跳变整个导航栏高度，与 `imePadding()` 的逐帧 inset 错位 ⇒ 回弹）；⑥ **移除**这三个 padding 的 `animateDpAsState`（上游默认 `spring()` 会过冲，且 `WindowInsets.ime` 本身已逐帧插值，再叠动画必然错位）；⑦ 「pending 且有内容」时渲染正文而非只显示「正在思考…」（否则流式全程 `isPending=true` ⇒ 一个字都看不见）；⑧ 「跳到底部」按钮的 `derivedStateOf` 去掉 `messages.size` key（流式下该值不变 ⇒ 可见性不更新） | **手动合并** |
| `app/build.gradle.kts` + `gradle/libs.versions.toml`（未动） | 新增 `com.squareup.okhttp3:okhttp-sse:4.12.0`（SSE 分帧，**不手写 `split("\n\n")`**）+ `mockwebserver:4.12.0`（testImplementation，**F18/F19 那些失败路径的唯一可测手段**）。⚠️ okhttp 系为**硬编码字面量**（`app/build.gradle.kts:237` 不走 catalog）⇒ 升级时**四处**要同改（okhttp/okhttp-sse/mockwebserver 三个坐标 + 同 minor 约束） | **手动合并** |
| `docs/fork/chat-float-window-ui.html` | fork 独有：Chat 悬浮窗可交互 UI 原型（折叠/展开/审批/输入/状态一致性五组演示），上游无此文件 | 我方 |
| `docs/fork/fold-trigger-design.md` | fork 独有：**折叠屏触发器设计**（`vflow.trigger.fold`）v3.0，**已实现并通过真机验证**（小米 MIX Fold 3，结论见 §9）。核心决策：做「状态推断」而非事件监听；**以铰链角度为主力信号**（实测 `device_posture` 滞后 1–2 秒且半折值不可靠，信号优先级已对调）；不引入 `androidx.window`；输出 6 项魔法变量（含 `posture_source` 诊断项）。**实测推翻了外部调研的悲观结论**——铰链传感器双向完整上报，非「只在折叠方向」。§8.1 记录一处既有缺陷：关闭后台服务通知会使服务降级、所有传感器类触发器静默失效。上游无此文件 | 我方 |
| `core/workflow/module/triggers/FoldTriggerModule.kt`、`FoldTriggerData.kt`、`FoldStateResolver.kt`、`handlers/FoldTriggerHandler.kt`（均新增） | fork 独有：**折叠屏触发器实现**。`FoldTriggerModule`=模块定义（1 个 ENUM 参数 + 6 个输出）；`FoldTriggerData`=`@Parcelize` 载荷；`FoldStateResolver`=**纯函数状态机**（信号融合/迟滞/去抖/边沿检测，无 Android 依赖，可纯 JVM 单测）；`FoldTriggerHandler`=继承 `BaseTriggerHandler`，融合铰链角度（主力）+ 小米 `device_posture`（校验）两路信号。**厂商私有 key `device_posture` 完全收敛在 Handler 内**。阈值经真机实测校准：折叠 <40° / 展开 >150° / 半折 40–150° | 我方 |
| `res/drawable/rounded_fold_24.xml`（新增） | fork 独有：折叠屏触发器图标（双屏对折造型，上游无此 drawable） | 我方 |
| `test/core/workflow/module/triggers/FoldStateResolverTest.kt`（新增） | fork 独有：状态机单测 25 例（三态判定、迟滞防抖、去抖计时、信号降级、角度优先于 posture、枚举序列化稳定性）。含「实测抖动 7° 不触发变迁」的回归用例 | 我方 |
| `scripts/fold-trigger-verify.sh`（新增） | fork 独有：折叠屏 P0 真机验证脚本（adb，零代码）。`snapshot` 快照 / `watch` 多信号实时对比 / `raw` 全量转储；采集 `device_posture` + 铰链角度 + 小米私有 `fold_status` + DeviceStateManager + 内外屏状态五路信号。**可复用于其他折叠屏机型复测** | 我方 |
| `core/workflow/module/ModuleRegistry.kt` | 触发器段追加 1 行 `register(FoldTriggerModule(), context)`（不重排既有注册） | 手动合并（追加一行，取上游 + 追加） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 追加 1 行 `register(FoldTriggerModule().id) { FoldTriggerHandler() }`（不重排既有注册） | 手动合并（追加一行） |
| `docs/fork/surveys/script-system-overview.md`（新增） | fork 独有：**脚本体系现状与能力边界梳理**（JS / Lua）。两引擎对照（**两侧均已注入真 Context**）、模块树注入、内联 `{% %}`；**能力边界三层拆解**（语言层已全开 / 环境层已补齐 / 能力层受 UID 限制，含本机 Rhino 实测）；与 ShortX 逐项对照（含其 context 来源的源码直读）与 **~80% 覆盖率结论**；**流体云可行性实证**（含 `service call` + `138` 事务码绕过特权链路的方案）；威胁模型与分级策略；**已实施改动与真机验证记录**（§10.1），并记录一处已确认的 release 缺陷（`proguard-rules.pro:97` 的 Shizuku keep 规则包名写错）。上游无此文件 | 我方 |
| `core/execution/JsExecutor.kt` | **JS 环境装配三处改动**（`execute()` 头部）：① 新增 `setApplicationClassLoader(...)`（修 `Packages.<应用内部类>` 退化成 `NativeJavaPackage` 导致的「xxx 不是函数，它是 object」）；② `initStandardObjects()` 改为 `ImporterTopLevel(context)`（提供 `importClass`/`importPackage`，使 Auto.js / ShortX 风格脚本可原样粘贴）；③ `context` 由**空壳 JS 对象**改为 `Context.javaToJS(executionContext.applicationContext, scope)` **注入真实 Android Context**（此前 `context.getSystemService(...)` 等调用全部失效）。**仅此三处，均在函数头部**，不改变对外签名。改动理由与能力边界分析见 `docs/fork/surveys/script-system-overview.md` | **手动合并**（三处局部改动） |
| `core/execution/JsExecutor.kt`、`core/execution/JsConsole.kt`（新增） | **JS 能力补齐（P0）**：① 新增 `JsConsole.kt`（fork 独有文件）—— 注入浏览器习语 `console` 对象（12 个方法：`log`/`info`/`warn`/`error`/`debug`/`println`/`group`/`groupEnd`/`time`/`timeEnd`/`count`/`countReset`）。**此前 vFlow 完全没有这个对象**，从 ShortX / Auto.js 移植的脚本会抛 `ReferenceError` 或被 `try/catch` 静默吞掉（表现为「脚本跑了但什么都没发生」）。⚠️ **对象渲染实测踩坑**：`Context.toString()` 对 JS 对象返回 `[object Object]`，必须走 `JSON.stringify`（实测对照见类注释）；因此 `stringify` 引用在 `install` 时取一次持有，**不能每次调用都 `initStandardObjects()`**。② `JsExecutor` 新增 `injectVariableWriters()` —— 注入 `vars_api`（`setGlobalVar` / `removeGlobalVar` / `reloadGlobalVars`）。**此前脚本无任何变量写路径**（`global` 是只读快照，改它不影响存储）。**两处均为新增，不改既有签名**。⚠️ release 构建已验证这些名字未被 R8 混淆（`defineFunction` 里的函数名是字符串字面量，R8 不动） | **手动合并**（`JsExecutor` 追加调用 + 新方法；`JsConsole.kt` 为我方新增文件） |
| 字符串资源 `strings_module.xml`（values / values-en / values-ja 三份） | 追加折叠屏触发器文案块（模块名/描述/参数/选项/6 个输出名/摘要前缀/进度消息，中英日齐全） | 手动合并（追加条目） |
| `ui/chat/ChatFloatWindowService.kt`、`ChatFloatPanelContent.kt`、`ChatFloatSummary.kt`、`ChatFloatWindowLauncher.kt`、`ChatFloatGeometry.kt`（均新增） | fork 独有：Chat 悬浮窗实现（P1 折叠态）。Service=窗口/拖动/吸附/展开折叠；PanelContent=折叠态 Compose UI；Summary=文案推导；Launcher=权限与启动；Geometry=锚定边计算 | 我方 |
| `ui/main/MainComposeShell.kt` | ① Chat 顶栏 actions 新增「悬浮窗」按钮；② ChatViewModel 获取由 `viewModel()` 改为 `ChatViewModelHolder.get()`（共享给悬浮窗 Service）；③ `ChatTopBarAction` 枚举新增 `ShowFloatWindow` | **手动合并**（4 处追加/替换，若上游改同区域需逐块判断） |
| `AndroidManifest.xml` | 追加 `ChatFloatWindowService` 声明（`foregroundServiceType="specialUse"`） | **手动合并**（追加声明） |
| `res/values/strings.xml`、`values-en/`、`values-ja/` | 追加悬浮窗文案（中/英/日） | 手动合并（追加条目） |
| `ui/chat/ChatViewModelHolder.kt`（新增） | Chat 悬浮窗：Application 作用域唯一 `ChatViewModel` 持有者（App 与悬浮窗 Service 共用同一实例，避免会话分裂） | 我方 |
| `ui/chat/ChatConversationExport.kt`（新增） | fork 独有：会话导出（完整 JSON 保真，落 `/sdcard/vFlow/exports/` + FileProvider 分享）。导出 UI 上游只有 benchmark 日志，无会话导出 | 我方 |
| `test/ui/chat/ChatConversationExportTest.kt`、`ChatFloatGeometryTest.kt`、`ChatFloatSummaryTest.kt`（均新增） | fork 独有：上述 fork 能力的单测。上游无对应测试文件 | 我方 |
| `ui/chat/ChatViewModel.kt` | 新增 `exportConversation(conversationId)` 方法（复用既有 `_events` 提示通道，不改变现有签名） | 手动合并（新增方法） |
| `ui/main/MainComposeShell.kt` | `ChatHistorySideSheet` / `ChatHistorySideSheetItem` 追加导出图标与 `onExportConversation` 回调（含调用点接线） | 手动合并（追加参数/按钮） |
| 字符串资源 `strings*.xml` | 新增会话导出文案 `chat_export_conversation` / `chat_export_share_title` / `chat_export_failed`（中/英/日） | 手动合并（追加条目） |
| `docs/fork/surveys/`（`README.md` + `ai-system-overview.md` + `agent-design-comparison.md` + `trigger-system-overview.md` + `logcat-readability-survey.md` + `notification-system-overview.md`） | fork 独有：**现状调研文档目录**（改代码前的地图）。`README.md` 为索引+写作规范；`ai-system-overview.md` 为 AI 体系梳理（三套链路/提示词/工具/技能/执行流程/模块可发现性/缓存 + 能力评估）；`agent-design-comparison.md` 为头部 Agent 项目外部对照调研；`trigger-system-overview.md` 为触发器体系梳理（两层注册/两条链路/三种 Handler 范式/24 个触发器清单与权限矩阵/排障顺序）；`logcat-readability-survey.md` 为 logcat 可读性真机实测结论；`notification-system-overview.md` 为通知体系梳理（8 处生产者 + 监听消费者、活体通知分支、渠道/权限/开关、超级岛改造落点与风险）；`shortcut-system-overview.md` 为快捷方式能力梳理（dumpsys 解析链路实测 408/408 全中、**dat 结构性残缺的 AOSP 根因定位**、多 Intent 取末项语义、ShortX `ACTION_CREATE_SHORTCUT` 方案对照）。上游均无此文件 | 我方 |
| `core/workflow/model/Workflow.kt` | 新增 `functionSignature` 字段（Parcelable，带默认值 null，向后兼容） | 手动合并（fork 追加字段，若上游也改需逐块判断） |
| `core/workflow/model/FunctionSignature.kt`（新增） | 新增 `FunctionParam`/`ReturnKey`/`FunctionReturn`/`FunctionSignature` 数据类 | 我方 |
| `core/execution/WorkflowExecutor.kt` | `executeSubWorkflow` 增加可选参数 `injectedVariables: Map<String, VObject> = emptyMap()`（带默认值，不破坏现有调用） | 手动合并（向上游方法加带默认值参数，若上游改了该方法需逐块判断） |
| `core/workflow/module/logic/DefineFunctionModule.kt`、`CallFunctionModule.kt` + UIProvider（新增） | 新增函数工作流模块（定义函数+调用函数） | 我方 |
| `core/module/AiParameterNormalizer.kt`（新增） | fork 独有：模块可选的「AI 入参规范化」钩子。用于参数形态是结构化数据、但存储形态是 JSON 字符串的模块——AI 与编辑器走两条写入路径，本接口让模块把 AI 的值收敛到与编辑器一致。目前只有 `DefineFunctionModule` 实现 | 我方 |
| `core/workflow/module/logic/DefineFunctionModule.kt` | `getInputs()` 由 `emptyList()` 改为声明 `functionParams`（`ParameterType.ANY`）——AI 只认 `getInputs()`，缺声明就会把该键判为非法而拒绝写入；实现 `AiParameterNormalizer`（简写类型 → 全限定 ID、丢弃空名项、Gson 序列化）；补 `aiMetadata`（`workflowStepDescription` + `inputHints`，**不设** `requiredInputIds`）；新增 `FUNCTION_PARAMS_KEY` 常量 | 我方（fork 新增文件内完善） |
| `ui/chat/ChatAgentModuleExecutor.kt` | `buildParameters` 尾部接入 `AiParameterNormalizer` 钩子（模块可选用 `parameters +` 规范化结果，未实现者原样透传） | **手动合并** |
| `ui/chat/ChatAgentToolRegistry.kt` | `save_workflow` / `temporary_workflow` 的 `parameters` description 补充两种引用形式（`{{stepId.outputId}}` 与 `{{vars.paramName}}`），并点明裸写不解析 | **手动合并**（两处同文替换） |
| `ui/chat/ChatCompletionResult.kt` | 加三个可空字段 `cacheCreationTokens` / `cacheReadTokens` / `cacheDeletedTokens`（默认 null，其余三处构造点不传，符合设计——OpenAI 系无此概念）。用于观测 Anthropic prompt 缓存是否命中 | **手动合并**（追加带默认值字段） |
| `ui/chat/ChatCompletionClient.kt` | Anthropic 解析补提取上述三个缓存字段（`extractAnthropicCacheTokens`） | **手动合并**（追加提取） |
| `ui/chat/ChatViewModel.kt` | `Model reply` 日志追加 `cacheCreate` / `cacheRead` / `cacheDeleted` 三项 | **手动合并**（日志字符串追加） |
| `ui/chat/ChatAgentToolRegistry.kt`、`ui/chat/ChatAgentSkillRouter.kt`、`core/module/AiParameterNormalizer.kt`、`core/workflow/module/logic/DefineFunctionModule.kt` | 函数参数引用语法修复：`parameters` description 与 `inputHints` 与 system prompt 三处补充 `{{vars.<name>}}` 说明 | **手动合并** |
| `core/workflow/module/logic/DefineFunctionParamEditorSheet.kt`（新增） | 新增「定义函数」参数编辑底部弹窗（参数名/类型/默认值/必填） | 我方 |
| `core/workflow/model/FunctionParamValidator.kt`（新增） | 新增参数名校验工具（snake_case + 去重，纯 Kotlin 可单测） | 我方 |
| `core/workflow/module/logic/FunctionParamTypeMapper.kt`（新增） | 新增参数类型「简写值↔完整类型ID」映射（修正保存后类型退化成「任意」的 bug） | 我方 |
| `core/module/definitions.kt` | `InputDefinition` 追加 `isRequired` + `getDisplayName`（带默认值，向后兼容，驱动必填 `*` 标记） | 手动合并（追加字段/方法，若上游也改需逐块判断） |
| `core/module/definitions.kt` | 新增 `OutputKeyDefinition` 数据类 + `OutputDefinition` 追加 `dictionaryKeys`（带默认空列表，向后兼容，驱动选择器展开函数返回值键） | 手动合并（追加字段，若上游也改需逐块判断） |
| `ui/workflow_editor/MagicVariablePickerSheet.kt` | `MagicVariableItem` 追加 `dictionaryKeys`；导航页新增「函数返回值」声明键分组；无属性但有声明键时也进入导航页 | 手动合并（追加字段/渲染分支） |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | `buildPickerModel` 透传 `outputDef.dictionaryKeys` 到选择器项 | 手动合并（追加参数传递） |
| `core/module/ModuleRegistry.kt` | `initialize()` 按分类追加注册 `DefineFunctionModule`、`CallFunctionModule`（不重排已有注册） | 手动合并（追加两行，取上游 + 追加） |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt` | `buildNamedVariables()` 追加「函数参数」分组；`buildPickerModel` 透传 `outputDef.dictionaryKeys` | 手动合并（追加逻辑） |
| `ui/workflow_editor/EditorMoreOptionsSheet.kt` | 加函数签名状态行 | 手动合并（追加只读展示） |
| `ui/workflow_editor/WorkflowEditorActivity.kt` | 删除「定义函数」卡片清空签名+提示；移动时约束其必须为首位 | 手动合并（追加逻辑） |
| `ui/workflow_editor/DefineFunctionModule.kt` | `validate()` 校验参数名（snake_case + 去重）；摘要展示类型/必填 | 我方（fork 新增文件内完善） |
| `ui/workflow_editor/DefineFunctionModuleUIProvider.kt` | 参数列表改为可编辑行（类型徽标/必填/默认值预览）+ 返回值只读区 | 我方（fork 新增文件内完善） |
| `res/layout/sheet_define_function_param_editor.xml`（新增） | 「定义函数」参数编辑弹窗布局 | 我方 |
| `res/layout/partial_call_function_params.xml`（新增） | 「调用函数工作流」参数赋值区容器 | 我方 |
| `res/layout/partial_call_function_param.xml`（新增） | 「调用函数工作流」单个参数行布局（header+值区+魔法按钮） | 我方 |
| `res/layout/item_function_param.xml`（新增） | 「定义函数」参数列表卡片行布局 | 我方 |
| `res/layout/view_define_function_add_param_button.xml`（新增） | 「定义函数」添加参数按钮（Material3 TextButton + ic_add） | 我方 |
| `ui/workflow_editor/ActionEditorSheet.kt` | `input_name` 改用 `getDisplayName`（显示必填 `*`） | 手动合并（追加逻辑） |
| `ui/workflow_editor/DictionaryKVAdapter.kt` | 新增 `updateValueForKey(key, value)` 方法（函数参数字典元素引用变量） | 手动合并（新增方法，不改变现有签名） |
| `core/workflow/module/logic/CallFunctionModule.kt` / `DefineFunctionModule.kt` | 摘要实时读 `functionParams`；必填校验空值；动态类型分派 | 我方（fork 新增文件内完善） |
| `res/layout/sheet_editor_more_options.xml` | 追加函数签名状态卡片 | 手动合并（追加卡片） |
| 字符串资源 `strings*.xml`、`strings_module.xml` | 新增函数工作流 UI 文案（中/英/日） | 手动合并（追加条目） |
| `docs/fork/sim-data-switch-design.md`（新增） | fork 独有：**数据卡切换设计**（默认上网卡 DDS 切换模块 + 切换监听触发器）。含**一个必须先问清的需求分歧**（「切默认上网卡」vs「切某卡数据启停」是两套 API）；**三条实测排除的路径**（`svc data` 无 prefer / `cmd phone data` 同 / `settings put multi_sim_data_call` 无效——服务端**无 ContentObserver**）；唯一可行路径的权限链（`setDefaultDataSubId` 要 `MODIFY_PHONE_STATE`，而 **shell(UID2000) 恰好持有**）；**§2.3 一个误导性极强的报错**（`cmd phone disable-physical-subscription` 报 Permission denied 与该权限无关）；**§9 真机验证结论**（Redmi K60 至尊版 / Android 17）；**§9.2 实测新问题**（该广播必须 `RECEIVER_EXPORTED`，`NOT_EXPORTED` 静默收不到）；§10 决策台账 13 项。上游无此文件 | 我方 |
| `core/workflow/module/system/SimDataSwitchModule.kt` + `SimDataSwitchSupport.kt`（均新增） | fork 独有：**切换默认上网卡模块**（`vflow.system.sim_data_switch`）。不 exec shell 命令（做不到），只有「写」走 Core 的 `isub` wrapper。`SimDataSwitchSupport`=**App 侧解析层**（卡槽 ↔ subId 映射、读当前默认卡），走公开 `SubscriptionManager`（实测 `getDefaultDataSubscriptionId()` 连权限都不需要）。⚠️ **读操作刻意不放 Core**：初版把卡槽映射放进 Core 反射 ISub，被 AIDL 签名变化坑了（新 `ISub` 读方法带 `callingPackage`/`callingFeatureId`，`findMethodLoose` 只按名字匹配 → invoke 参数不够抛异常，catch 只 `println` 到 Core stdout，App 只收到含糊的 `no subscription in slot N`）。**能用公开 API 就别反射猜 AIDL 签名。** 失败态用密封类 `SimSlotLookup` 三分（`Unavailable`/`EmptySlot`/`NotSupported`）——初版混成一句「无可用 SIM 卡，或 Core 未运行」，排障时分不清是权限还是硬件。执行流程失败语义明确：卡槽无卡→明确失败不猜 subId；已是目标卡→短路；成功后回读但**回读不一致不改判失败**（切换异步、期间断网是正常现象） | 我方 |
| `core/workflow/module/triggers/SimDataSwitchTriggerModule.kt`、`SimDataSwitchMath.kt`、`SimDataSwitchTriggerData.kt`、`handlers/SimDataSwitchTriggerHandler.kt`（均新增） | fork 独有：**数据卡切换触发器**（`vflow.trigger.sim_data_switch`）。`target_slot` 三值 `any`/`slot1`/`slot2`（默认 `any`）。`SimDataSwitchMath`=**纯函数层**（subId↔卡槽映射 + `shouldTriggerForAny`，无 Android 依赖，可纯 JVM 单测）；`SimDataSwitchTriggerData`=`@Parcelize` 载荷（`card_label` 随载荷传递而非下游现查——下游现查可能已再次切卡）；Handler 继承 `ListeningTriggerHandler`，含 `probeSimState` 诊断探针（免改上游 Manifest）。⚠️ **必须用 `RECEIVER_EXPORTED` 注册，是全仓库唯一的例外**——用惯用的 `RECEIVER_NOT_EXPORTED` 会静默收不到（实测 3/3 复现），表现是「能选能配、后台永不触发」。⚠️ **权限声明 `READ_PHONE_STATE`（与 `CallTriggerModule` 对齐）——初版误判为「零权限」已修**：接收广播确实无需权限，但把 subId 映射成卡槽需要读订阅列表，而 `TriggerService` 会在缺权限时**静默禁用整个工作流**。该缺陷在「权限齐全的设备上测不出来」（真机已持有该权限），属最坏的一类 bug；初版注释还描述了不存在的降级行为。有测试锁住（§8.6） | 我方 |
| `core/src/main/java/.../server/wrappers/shell/ISubWrapper.kt`（新增） | fork 独有：**Core 侧 isub wrapper，只承载唯一必须特权的操作 `setDefaultDataSubId`**（读操作已移到 App 侧，见 `SimDataSwitchSupport`）。**按方法名反射而非 `service call` 事务码**——事务码是 AIDL 编译产物的方法顺序、逐机型漂移，且**调错码会改到相邻设置项**；反射走 `ISub` 接口，对 Android 14 起实现类由 `SubscriptionController` 换成 `SubscriptionManagerService` 透明。⚠️ 调用前**先校验参数表形状**（期望恰好 `[int]`），不符时直接回可诊断错误而非硬调；失败时**把真实原因回传**（剥掉 `InvocationTargetException` 才看得到 `SecurityException`）——初版只回一个 `false`，原因被吞在 Core stdout 里。⚠️ `setDefaultDataSubId` 的反射**尚未在真机上成功执行过**（真机只走到读路径就失败了），若报签名不符，`onServiceConnected` 会把真实参数表打进 Core 日志 | 我方 |
| `res/drawable/rounded_swap_sim_24.xml`（新增） | fork 独有：数据卡切换图标（双卡 + 双向对调箭头，上游无此 drawable） | 我方 |
| `test/core/workflow/module/triggers/SimDataSwitchMathTest.kt`、`SimDataSwitchTriggerModuleTest.kt`、`sim_data_switch/SimDataSwitchSupportTest.kt`（均新增） | fork 独有：**33 例**（22 + 11 + 5，含「任意」语义与 `SimSlotLookup` 三态）。重点是**反向断言**：未知 subId 必须返回 null 而非回退到卡1（否则切到不存在的卡会误触发卡1 的工作流）、配在卡2 的工作流在切到卡1 时绝不能触发、空卡列表不触发、**`subId` 不是「卡槽+1」**（防有人用算式替代查表）、`slotIndexOf(SLOT_ANY)` 必须 null（否则「任意」退化成「只认卡1」）、触发器**必须声明 `READ_PHONE_STATE`**（声明少了会被 `TriggerService` 静默禁用整个工作流——初版误判为「零权限」已修） | 我方 |
| `core/workflow/module/ModuleRegistry.kt` | 触发器段追加 1 行 `register(SimDataSwitchTriggerModule(), context)`；设备段追加 1 行 `register(SimDataSwitchModule(), context)`（均不重排既有注册） | 手动合并（追加两行，取上游 + 追加） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 追加 1 行 `register(SimDataSwitchTriggerModule().id) { SimDataSwitchTriggerHandler() }`（不重排既有注册） | 手动合并（追加一行） |
| `services/VFlowCoreBridge.kt` | 新增 1 个方法 `setDefaultDataSubId`（走 `target=isub`）。⚠️ 初版还加了 `getDefaultDataSubId` / `getSubIdForSlot`，**已删除**——读操作不需要特权，改用 App 侧公开 API（见 `SimDataSwitchSupport`）。失败时把 Core 回传的 `error` 一并记进日志（初版只回 `false`，真实原因被吞） | 手动合并（新增方法） |
| `core/.../server/common/Config.kt` | `ROUTING_TABLE` 追加 `"isub" to WorkerType.SHELL`。⚠️ **只加 `serviceWrappers` 不够**——那张是「谁能处理」，这张才是「转发给谁」，漏了会回 `{"error":"No route"}`（已实际踩过三次） | 手动合并（追加一行） |
| `core/.../server/worker/ShellWorker.kt` | 追加 `serviceWrappers["isub"] = ISubWrapper()` | 手动合并（追加一行） |
| `core/src/test/.../server/common/RoutingTableConsistencyTest.kt` | 追加 3 例：isub 必须登记在 `ROUTING_TABLE`、应路由到 SHELL worker（shell 恰好持有 `MODIFY_PHONE_STATE`，走 root 会无谓抬高门槛）、**不应出现在 `STREAM_METHODS` 里**（它是普通一问一答请求，进流式表会挂住） | 我方（新增用例） |
| `scripts/sim-data-verify.sh`（新增） | fork 独有：数据卡切换跨机型验证脚手架（全 adb 零代码）。`snapshot` 快照 / `broadcast` 清日志→切卡→抓广播（含 extras 实测）/ `raw` 全量转储。**刻意不实现 `service call` 事务码扫描**——事务码逐机型漂移、调错码会改到相邻设置项 | 我方 |
| `scripts/probe/`（`SimDataProbe.java` + `SimDataReceiverProbe.java` + `apk/`） | fork 独有：本次真机验证用的一次性探针（切换探针 / 广播探针 / 最小接收 APK 源码与打包链路）。⚠️ 只提交 `.java` / `.xml` 源码与 `.gitignore`（编译产物不入库）。其中**打包链路可直接复用**：javac(带 platform android.jar) → d8（⚠️ **必须显式列出所有 `*.class`**，否则匿名内部类丢失）→ aapt2 link → 注入 classes.dex → zipalign → apksigner | 我方 |

### Xposed 通道（第四条通道，P1a，2026-09-27）

> 设计文档 `docs/fork/xposed-channel-design.md`（v2.7 定稿），探针结论 `scripts/probe/xposed-channel/`。
> **路线 1：hook 层与主 App 同一 APK**（它是 `signature` 级 `HOOK_CONTROL` 成立的前提）。
> 本批只做 **P1a（打包与加载骨架）**——不挂 hook、不通信、不采事件。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `app/src/main/resources/META-INF/xposed/{module.prop,scope.list,java_init.list}`（均新增） | fork 独有：**Xposed 模块的三个谱文件**（仓库首次创建 `src/main/resources`）。入口由 `java_init.list` 里的**类名字符串**指定；`scope.list` 为 `system`（system_server 的特殊虚拟包名）。⚠️ **三文件必须零注释、LF 行尾**——注释会让模块**静默不加载**（探针 `build.sh` 里有对应断言）；已加 `.gitattributes` 锁 LF。⚠️ `minApiVersion` **必须写 101**（写 102 会被 API 等级 101 的框架拒载），实测 `targetApiVersion=102` | 我方 |
| `.gitattributes`（新增） | fork 独有文件（上游无此文件）。目前只有一条用途：**锁上述三个谱文件为 `text eol=lf`** —— Windows 下极易被编辑器改成 CRLF，而框架读错时不会友好报错 | 我方 |
| `app/src/main/java/.../xposed/VFlowHookEntry.kt`（新增） | fork 独有：Xposed 模块入口（`io.github.libxposed.api.XposedModule` 子类）。**运行在 system_server 进程里**，故引用面有硬约束：只允许 `io.github.libxposed.*` / `java.*` / `org.json` / `android.util.Log` / `xposed.**` 内部；**禁止** `core.*` / `services.*` / Gson / `DebugLogger`（同 dex 里 App 类都在，但**只有被引用才会在 system_server 里被解析加载**，引用 App 重类会**把崩溃半径扩大到整机**；日志刻意用 `android.util.Log` 而非 `DebugLogger` 正是这条的后果）。⚠️ 必须用普通 `class`（**不是 Kotlin `object`**，object 的 JVM 名带 `INSTANCE`）。⚠️ 入口被 `java_init.list` 按名加载 ⇒ **必须 R8 keep** | 我方 |
| `app/build.gradle.kts`（改） | **本文件首个 `compileOnly`**：`compileOnly("io.github.libxposed:api:102.0.0")`（运行期由框架提供、**不打包**）+ `implementation("io.github.libxposed:service:102.0.0")`（⚠️ **必须打进 dex**——它提供 `XposedProvider` 的**实现类**；只声明 provider 不打包实现 ⇒ 框架加载失败 ⇒ **模块静默不加载、零报错**，已实际踩过）。选 102 而非 101：102 原生支持热更新 + `onSystemServerStarting` 是一等公民；⚠️ 选 102 就**不能混用传统 `de.robv.android.xposed` API** | **手动合并**（追加） |
| `app/proguard-rules.pro`（改） | 新增第 31 节（3 条规则，**都是「静默失效」的防线**）：① ⚠️ **`-keep class io.github.libxposed.service.**`**——`XposedProvider` **只被合并后的 manifest 字符串引用、代码零引用**，R8 默认当死代码剥掉 ⇒ 模块静默不加载；探针（未混淆）踩不到这条，**只有 release 才暴露**，而 `service` aar 自带的 `proguard.txt` 只有一条 `-dontwarn`、**不含任何 keep**。② keep 入口类（`java_init.list` 按类名加载）。③ ⚠️ `-dontwarn io.github.libxposed.api.**`——`api` 是 `compileOnly`，R8 阶段看不到它、会报 missing class | **手动合并**（追加） |
| `app/src/main/AndroidManifest.xml`（改） | ① 首个自定义 `<permission>`：`com.chaomixian.vflow.permission.HOOK_CONTROL`（`protectionLevel="signature"`）——**下行鉴权的根**（hook 层与 App 同签，故能挡住异签方冒充下发）。⚠️ 但它**不能**用于上行真伪：hook 层跑在 system_server，「uid 1000 能否持有本 App 的 signature 权限」**至今未定论**（探针的 `checkPermission(自己pid,自己uid)` 方法无效，恒 GRANTED）。② `<provider io.github.libxposed.service.XposedProvider>`，`authorities="${applicationId}.XposedService"`（照 `service` aar 自带 manifest） | **手动合并**（追加 2 处） |

### Xposed 通道 P1b（双向心跳，2026-09-27）—— **已真机验证打通**

> P1a 的验证：`isSystemServer = true`、`apiVersion = 102`、uid 1000（截图证据）。
> P1b 的验证：`bindService` 成功 + `registerCallback` 成功 + App 侧 `callerUid=1000`。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `app/src/main/aidl/.../xposed/IHookHost.aidl` + `IHookCallback.aidl`（均新增） | fork 独有：Xposed 通道的**双向 AIDL 面**。`IHookHost`（App 提供：`registerCallback` / `report`）+ `IHookCallback`（hook 层实现：`pushConditions` / `ping`）。⚠️ **`report` 是 `oneway`**（hook 层可能在 system_server 任何线程上调，绝不能阻塞等待 App 处理完）；而 **`registerCallback` / `pushConditions` 刻意不用 `oneway`** —— 它们的成败必须能被感知（失败通常是鉴权没放行，oneway 会把原因静默吞掉，只剩心跳超时可推）。⚠️ `Binder.getCallingUid()` **只在 binder 事务内有效**，必须在 `Stub` 实现里第一行读、赋给局部变量（进协程/跨 suspend 点就取不到；`onBind` 里读到的更是**自己**的 uid，探针实测踩过） | 我方 |
| `app/src/main/java/.../xposed/wire/EventEnvelope.kt` + `EventQueue.kt`（均新增） | fork 独有：**hook 层与 App 侧共享的统一信封 + 有界队列**（`{topic,seq,ts,payload,dropped,token,protocol_version}`）。⚠️⚠️ **共享一份实现，而非照 logcat 做两份拷贝** —— `FORK.md` 已记录 logcat 双份的代价（「语义改动必须同时改两处，不一致的表现是调试工具能匹配而触发器匹配不到」）；本通道同 APK 同 dex，不必重复该代价。**但共享带来新风险**：wire 层跑在两个进程里（App + 注入 system_server 的 hook 层），**只有被引用才会在 system_server 里被解析加载** ⇒ 它**禁止**引用 `android.*` / Gson / `DebugLogger` / `core.*` / `services.*`（否则把 App 侧重类的静态初始化拖进 system_server，**崩溃半径从「那个 App」扩大到整机**）。这条由 **`WireLayerPurityTest` 源码扫描**锁住，且已做反证（塞一个 `android.util.Log` import ⇒ 三条同时变红）。⚠️ `EventQueue.clear()` **故意只丢积压、不清 `dropped` 计数**（丢弃数是用户知道「丢过事件」的唯一途径，留到 `drainDropped()` 上报），有测试锁住；`seq` 用连接级全局计数、缺失时解码为 **-1 而非 0**（0 是合法首序号，用 0 会让「缺失」与「第一条」混同，丢包检测失效） | 我方 |
| `app/src/main/java/.../xposed/{HookRuntime,BinderTransport}.kt`（均新增） | fork 独有：hook 层骨架。`HookRuntime`=信封装配 + **`emit()` 只入队、立即返回**（发送由独立线程 `drainLoop` 做）+ topic 订阅集合（**只认 topic、不认业务字段**——「Hook 层不知道工作流的存在」在数据层的落实）。`BinderTransport`=**两个等待 + bind**：① `UserManager.isUserUnlocked()`（目标 Service `directBootAware=false`，未解锁时组件在 package 解析阶段就被排除，表现与「包不可见」**一模一样**——这是 P0 那个「假的连不上」的真因）；② 系统服务就绪（`onSystemServerStarting` 时 `PackageManager` 为 null，约 11 秒后可用）。等待上限 **150 秒**（实测探针比设备解锁早约 47 秒，30 秒上限会过早放弃）。⚠️ **绝不用 `directBootAware=true` 来「修好连不上」**——那会掩盖真因。⚠️ `start()` **立即返回**（真实连接在后台线程），因为它跑在 `onSystemServerStarting` 调用栈上，**在那里阻塞会拖住 system_server 启动** | 我方 |
| `app/src/main/java/.../services/HookChannelService.kt`（新增） | fork 独有：App 侧端点，**本仓库第一个真正返回 binder 的 Service**（现有全部 Service 含 `TriggerService` 的 `onBind` 都返回 null，它们靠 `startService` 存活）。⚠️ **刻意不设 `foregroundServiceType`、不做 `startForeground`** —— 被 system_server bind 这一事实本身就让 AMS 保着 App 进程，加 FGS 只会白引入常驻通知 + 额外权限负担。⚠️ `registerCallback` 里读到的 `callerUid` **只用于诊断日志、不作放行依据**——它区分不了「system_server 本人」与「任何 uid 1000 的东西」 | 我方 |
| `app/src/main/java/.../core/xposed/HookChannelController.kt`（新增） | fork 独有：App 侧控制器（连接 / token / 丢包检测 / 路由）。⚠️⚠️ **实测抓出的真实漏洞**：未连接时本侧 token 是空串，而伪造者送 `token:""` 也是空串 ⇒ **空串比空串恒等**，无凭证信封会被放行。已加「本侧无 token 直接拒绝」的前置闸，并做反证确认变红。⚠️ token 用 `SecureRandom`（可预测的 token 等于没有）、比较用**恒定时间**（逐字符 `==` 会泄漏前缀信息）。⚠️ 丢包检测与丢弃计数上报都在这里（**必须显式告知用户**，否则他只看到「触发器偶尔没反应」） | 我方 |
| `app/src/main/java/.../xposed/VFlowHookEntry.kt`（改） | 接入 `startChannel()` / `stopChannel()`。⚠️⚠️ **`onHotReloaded` 必须显式重建通道**（**实测踩出来的**）：官方明文「Hot reload **does not automatically replay** this callback or package lifecycle callbacks」⇒ `onSystemServerStarting` **不会**在新代际里被重放；而热更新是新 classloader 加载新代码，新代际的 `runtime`/`transport` 字段**全是 null**。第一版只打日志 → 重装 APK 后通道**永久消失**（表现「触发器再也不工作」，日志里只有一行 onHotReloaded、看起来一切正常）。⚠️ `onHotReloading` 里必须 `stopChannel()`（官方要求停掉自己的线程）——否则旧代际的等待线程与新代际的并存，两个线程抢同一个 Service | **我方**（fork 新增文件内完善） |
| `app/src/main/AndroidManifest.xml`（改） | 追加 `HookChannelService` 声明（`exported="true"` + `android:permission="com.chaomixian.vflow.permission.HOOK_CONTROL"`） | **手动合并**（追加声明） |
| `app/proguard-rules.pro`（改） | 第 31 节追加 ④⑤：keep `IHookHost`/`IHookCallback`（含 `$Stub`，形状照第 10 节 Shizuku & AIDL）+ `HookChannelService` + `keepnames` wire 层。⚠️ 其余 hook 层类**不逐个 keep**——只有「按名字被外部找到的」才需要（入口类 / AIDL / provider）；`BinderTransport`、`HookRuntime` 实测被重命名但功能正常（线程名 `VFlowHook-connect` / `VFlowHook-drain` 在 dex 里可证仍在） | **手动合并**（追加） |
| `test/.../xposed/{EventEnvelopeCodecTest,EventQueueTest,WireLayerPurityTest}.kt` + `test/.../core/xposed/HookChannelControllerTest.kt`（均新增） | fork 独有：**30 例**。重点是「改错了不报错、只静默变差」的地方：坏 JSON 必须返回 null 而非抛（binder 线程上抛异常无人处理）、未知 topic 保留给上层忽略、信封层不解析 payload（键顺序/转义都不能变）、`clear()` 不清丢弃计数、容量 0 不崩（构造期崩溃发生在 system_server 里）、token 恒定时间比较与长度安全、**wire 层依赖白名单**（含「注释剥离不能剥过头」的元测试）。关键用例均已**反证**（改回 bug 版本确认变红） | 我方 |

### Xposed 通道 P3（触发器闭环，2026-09-27）—— 已接入现有触发器体系

> P3 的目标：让 `vflow.trigger.activity_changed` 能在编辑器里配、真正触发工作流。
> 设计文档 §3.4.3 要求：**新增 hook 触发器 = 框架 + 适配器**，
> 而「触发器模块 / Handler / 两处注册 / 文案」这几步与**非 Xposed 触发器完全一致**
> —— 刻意不另立平行注册体系，否则将来会有两个触发器体系。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/workflow/module/triggers/ActivityChangedTriggerModule.kt`（新增） | fork 独有：Activity 切换触发器模块定义（`vflow.trigger.activity_changed`）。4 个输入（包名 / Activity 类名 / 匹配方式 / 冷却）+ 6 个输出（含 `intent_uri` / `extras_json` / `truncated`）。⚠️ **空参数 = 任意**（不是「都不匹配」）——反过来会让「新建触发器还没填条件」表现为「配了但永不触发」。⚠️ **`extras_json` 不逐键建输出**：extras 的键**不可枚举**（任何 App 都能塞任意键），故只给一个 JSON 字符串由下游解析（与 `OutputDefinition.dictionaryKeys` 机制一致）。⚠️ 冷却**默认 1000ms**：`activityResumedLocked` 每次 resume 都触发（含返回、锁屏解锁、同 Activity 重入），默认不冷却会瞬间刷爆工作流。⚠️ 匹配方式存**稳定常量** `contains`/`exact`（不存本地化文案，否则切语言后已保存的工作流失配） | 我方 |
| `core/workflow/module/triggers/ActivityChangedTriggerData.kt`（新增） | fork 独有：`@Parcelize` 载荷。⚠️ `truncated` 字段**必须传下去** —— 载荷可能因 Binder 1 MB 事务上限被截断，不告诉用户的话「extras 少了几个键」会被当成「那个 App 本来就没传」，他会去查错的地方 | 我方 |
| `core/workflow/module/triggers/handlers/ActivityChangedTriggerHandler.kt`（新增） | fork 独有：Handler。⚠️⚠️ **必须继承 `BaseTriggerHandler`，不能用 `ListeningTriggerHandler`** —— 后者四个方法全 `final`、只在「空↔非空」边界触发，会导致**已有 1 个触发器时再加第 2 个、条件永远下不去**（第 2 个静默不触发）。这是 `LogcatTriggerHandler` 类注释里记的同一条教训。⚠️ 条件下发是**全量替换**（每次增删都重发）。⚠️ **过滤分两层**：hook 层只按「包是否被关心」粗筛（避免 hook 所有进程/洪泛），App 侧按 `TriggerSpec.parameters` 精确匹配 —— **判定权在 App 侧**（§3.2 硬约束）。⚠️ 冷却**窗口内的命中不记账**（若每次都记账，持续高频切换会让窗口无限顺延、触发器永远不再触发）。⚠️ `normalizeMatchMode` 手工兜旧本地化值：模块的 `legacyValueMap` 只在**编辑器**路径生效，**读参数不走它** —— 不兜的话「用户选了精确匹配、实际按包含匹配」且不报错 | 我方 |
| `core/xposed/XposedCapability.kt`（新增） | fork 独有：Xposed 通道的**能力探测**。⚠️⚠️ 判据是「**曾经**成功连上过」而不是「此刻连着」—— 这是本文件唯一需要解释的决策：`TriggerService.handleWorkflowChanged`（`:275`）在权限缺失时会**静默把整个工作流置为 `isEnabled=false`**（`:330-341`），而 hook 层的连接是**异步**的（要等系统服务就绪 + 用户解锁，可能几秒到几十秒）。判「此刻连着」会让「开机后 hook 还没连上」那个窗口期把用户的工作流关掉，表现为「明明装好了，开机后触发器就是不工作，手动重开一次才行」。⚠️ 代价：**首次配好 LSPosed 后需要连上一次**（重启设备）才算具备能力，这一步已写进权限描述 | 我方 |
| `permissions/PermissionManager.kt`（改） | 新增 `XPOSED_HOOK` 能力权限常量 **+ `xposedHookStrategy` 策略**（两处缺一不可）。⚠️⚠️ **只加常量不加策略是本改动最容易漏的一半**：`strategies` 是不可变 map，`isGranted` 对未登记的权限会**回落到 `runtimeStrategy`** ⇒ 恒判「缺权限」⇒ 又是静默禁用整个工作流。形态照 `shizukuStrategy`（判「Shizuku 服务是否在跑」），本权限判「XposedCapability」 | **手动合并**（追加常量 + 策略 + map 一行） |
| `core/workflow/module/ModuleRegistry.kt` / `.../triggers/handlers/TriggerHandlerRegistry.kt`（改） | 按分类**各追加一行**注册（不重排既有注册） | **手动合并**（追加一行） |
| `res/values{,-en,-ja}/strings_module.xml`（改） | 追加 Activity 切换触发器文案 18 条 ×3 语言 | **手动合并**（追加条目） |
| `res/values{,-en,-ja}/strings.xml`（改） | 追加权限文案 `permission_name_xposed_hook` / `permission_desc_xposed_hook` ×3 语言 | **手动合并**（追加条目） |
| `core/xposed/HookChannelController.kt`（改） | ① 移除 P2 的「无条件全订阅」（那是当时的临时手段）—— 订阅权改由 Handler 按触发器实际配置下发，否则两者打架（Handler 想「没有触发器就卸下 hook」，兜底却坚持「全订阅」⇒ 永远在采）；② 新增 `setOnConnectedListener` + `attach(context)`：连接建立时通知业务层**重下发条件**（hook 层重启后内存里的条件是空的 —— 决策 14 条件不落盘），不做的话表现为「重启后触发器再也不触发」而通道看着是活的 | **我方**（fork 新增文件内完善） |
| `test/.../triggers/ActivityChangedTriggerHandlerTest.kt`（新增，19 例） | fork 独有。重点：**同包不同 Activity 能区分**（本触发器存在的理由）、空参数=任意、旧本地化 match_mode 值**端到端**生效（防「归一化函数对了但没被调用」）、冷却窗口不被窗口内命中顺延、负数冷却钳到 0 | 我方 |
| `test/.../triggers/ActivityChangedTriggerModuleTest.kt`（新增，12 例） | fork 独有：**声明体检**。⚠️ 其中两例专门锁「静默禁用整个工作流」的两个半：必须声明 `XPOSED_HOOK`、且 `strategies` 表里**必须有**它（第二例走反射读私有 `strategies`，已做反证：去掉登记即变红） | 我方 |

### Xposed 通道 P4（状态展示与授权引导，2026-09-27）—— 含 **6 处实测暴露的缺陷修复**

> 上游文档：`docs/fork/xposed-channel-design.md`（架构）、
> `docs/fork/xposed-channel-p4-design.md`（P4 落地设计）、
> `docs/fork/surveys/shortx-script-capability.md`（外部调研）。
> **P4a 状态判据 / P4b 授权引导 / P4c 首页展示 / P4d 移探针**，均已真机验证。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/xposed/XposedState.kt`（新增） | fork 独有：**状态判定纯函数层**（两组状态位 + 引导判定 + **点击行为分类** + scope 描述），无 Android 依赖、可纯 JVM 单测。⚠️⚠️ **为什么是两组状态位而非一个枚举**（设计文档 §3.3 的硬要求）：「框架在不在」与「事件能不能流过来」是**两件独立的事**，压成一个会让 `ACTIVE + DISCONNECTED` 这一格**表示不出来** —— 而那是最容易被误判成「框架问题」的一格（用户会去改本来正确的配置）。⚠️ `Channel` 的判定**不依赖 `Framework`**（耦合了那一格就永远判不出）。⚠️ **`scope` 不参与 `Channel` 判定**，以 `runningTargets`（实际结果）为准而非 `scope`（配置）。⚠️ `tapAction()` 区分 `GUIDE` / `RECONNECT_HINT` —— 见缺陷 ④ | 我方 |
| `core/xposed/XposedFrameworkMonitor.kt`（新增） | fork 独有：官方框架信息的**状态源**（**由临时探针改写而来**，不是新建第二个文件 —— `XposedServiceHelper.registerListener` **无注销方法**，并存会双回调）。持有 `XposedService`，暴露 `StateFlow` + `evaluate()`。⚠️ 提供 L1（`onServiceBind`/`onServiceDied`）/ L2（`getScope`）/ L3（`getRunningTargets`）**三层**，**不含 L0**（那是 App 自己与 `HookChannelService` 的连接）。⚠️ `requestSystemScope()` 封装官方 `XposedService.requestScope()` —— **这是「请求授权」的正确做法**，官方**没有**「打开 LSPosed 管理器」的 API | 我方 |
| `core/xposed/XposedCapability.kt`（**重写**） | ⚠️⚠️ **判据由「曾经连上过（持久化）」改为「框架此刻连着（实时）」** —— 与 `shizukuStrategy`（判 `isShizukuActive`）同源。**推翻自己的设计**，理由记在类注释里：原判据是为绕开「启动时序导致误禁工作流」而设，但**解法用错了地方** —— 它让权限变成永久授权，导致 ① 用户在 LSPosed 里关掉模块后**权限页仍显示「已授权」**（实测确认）；② 而 vFlow **其他所有权限判据都是实时的** ⇒ XPOSED 成了体系里的**异类**；③ 更糟：「已授权」**掩盖真实失效**。⚠️ 删掉 SharedPreferences 写入（`Monitor` / `HookChannelController` 两处）与 `markConnected()`。**决定（C 方案）：不加「不禁用工作流」的豁免** —— 因为那会引入**又一个写死 id 的后门**（`TriggerService` 里已有一个给无障碍的），等于再造异类；已核实恢复闭环是**通用的**（`WorkflowPermissionRecovery`，6 处触发点含每 10 秒轮询） | **我方** |
| `core/xposed/HookChannelController.kt`（改） | ① **加 `StateFlow<Boolean> connected`** —— UI 无法 `collect` 同步的 `isConnected()`；且它与框架状态**独立**（「框架正常但通道断」是单独一格）。② 去掉 `markConnected` 调用（判据改实时）。③ **修掉一个真实漏洞**：未连接时本侧 token 是空串，而伪造者送 `token:""` 也是空串 ⇒ **空串比空串恒等** ⇒ 无凭证信封被放行（已加前置闸 + 反证） | **我方** |
| `core/xposed/BinderTransport.kt`、`VFlowHookEntry.kt`（修 **缺陷 ①**） | ⚠️⚠️ **实测暴露：`onServiceDisconnected` 只清 `host`、不重连** ⇒ 断开后**永久失联**，Activity 触发器再也不工作、**只能靠重启 App 恢复**。而触发断开最常见的场景恰恰是**我们自己的部署流程**（重装 APK ⇒ App 被杀）与**热更新换代** —— 用户看到「装了新版本之后触发器就不灵了」。修复：**退避重连**（1s→2s→…→封顶 30s，最多 20 次 ≈6 分钟）+ **存活性巡检**（每 15s 查 `binder.isBinderAlive`，兜底 `onServiceDisconnected` **不触发**的情况 —— `LogcatStreamWrapper` 踩过同一个坑）。⚠️ 重连后必须用 **`remountSources()`（先 unmount 再挂）** 而非 `mountSources()` —— 后者的幂等守卫 `if (handle != null) return` 在换代后是**错的**（旧句柄非 null 但已失效）⇒ **hook 点永远挂不上**，表现为「连上了、状态正常，但什么都不触发」。⚠️ 退避计算抽成纯函数以便单测（「退避算错」是**静默**的） | **我方** |
| `core/xposed/HookRuntime.kt`（改） | 上述重连的重挂支持（`remountSources()`）。⚠️ **我一度加的 `requestConditionResend()` 已删** —— 它调 `emit()` 而 `emit()` 在 token 为空时 return，**重连那一刻 token 恰是空的**（鸡生蛋）；且 App 侧**早就**在连接建立时重下发了（P3 的 `onConnected` → `syncToChannel()`）。两个理由都记在原地注释，避免将来有人再加一遍 | **我方** |
| `permissions/Permission.kt`、`PermissionManager.kt`（改，修 **缺陷 ②**） | 新增 `grantedExternally` 字段（带默认值，向后兼容）+ `XPOSED_HOOK` 置 `true`。⚠️⚠️ **修的是一个功能缺口**：`createRequestIntent()` 返回 `null` 有**两种完全不同的含义**（「运行时权限」vs「没法在 App 内授予」），而两个 UI 入口**都当成前者** ⇒ 对 `XPOSED_HOOK`：`PermissionActivity` 转 `autoGrantPermission`（要 Shizuku/Root，失败）、`OnboardingActivity` 当运行时权限去 `requestPermissions`（**弹不出对话框**）⇒ **用户点「授予」什么都不发生、也没有提示** | **手动合并**（新增字段 + 常量标记 + 2 处 UI 分支） |
| `ui/settings/XposedGuideDialog.kt`（新增） | fork 独有：授权引导对话框。⚠️ **措辞必须能力导向** —— 本通道是**通用**的，后续会有很多触发器/模块接入，所以文案**不出现具体触发器名**（早期版本写过「用于获取当前 Activity 与启动 Intent」，那是把通道当成单一功能，已改；权限描述同步改）。⚠️ **两条路并存**：框架已在跑 → 「请求授权」按钮调 `requestScope(["system"])`；框架未启用 → 文字步骤（此时 `requestScope` 调不了）。⚠️ 带**实时状态行 + 当前作用域**，让用户能自我核对（LSPosed 里没有任何反馈）。⚠️ 状态文案**按状态分流**，尤其 `ACTIVE + DISCONNECTED` 必须说「正在重连」而非「去检查 LSPosed」 | 我方 |
| `ui/home/HomeScreen.kt`（改，修 **缺陷 ③**） | ① `HomeUiState` 加 4 个 Xposed 字段；② **布局改版**（用户定）：Xposed 状态卡**占据 Core 卡右侧大卡位**，两个统计小卡**下移并排**（原为右列堆叠）；③ 新增 `XposedStatusCard`（与 `CoreStatusCard` 同构，**常驻显示不做条件显隐** —— 条件显隐会让 Core 卡突然变宽、布局跳动，且「存在本身即信息」）；④ **配色只用两种**（正常/异常），三种失败态靠**图标 + 文案**区分；⑤ 丢弃提示走 `HomeInfoCard` **条件显示**（`> 0` 才出现，出现/消失不影响网格）；⑥ 刷新**走推送**（订阅 `Monitor.state` + `Controller.connected` **两条**，因为两组状态位独立），**Core 那套轮询保持不动**（它无推送源）。⚠️ **缺陷 ③**：点击分类原本写 `if (needsGuidance) 引导 else 重连提示`，而 `needsGuidance` 对**正常**也返回 false ⇒ **一切正常时点卡片会弹「通道正在重连」**（莫名其妙的提示）。已抽成 `XposedState.tapAction()` + 4 例单测 + **反证**（改回 bug 版本即变红） | **手动合并** |
| `res/drawable/rounded_{extension_off,rule}_24.xml`（新增） | fork 独有：Xposed 状态卡的两个失败态图标（`rounded_sync_problem_24` 已有）。⚠️ 照既有 `rounded_*` 形态做 | 我方 |
| `VFlowApplication.kt`（改） | **一行**：启动时注册框架监听。⚠️ 必须**尽早**（`onServiceBind` 是推送的、不重放，晚了永远收不到）。⚠️ 放在 `DebugLogger.initialize` 之后（Monitor 要打日志）。⚠️ 只注册、不做耗时操作 | **手动合并**（新增 1 行） |
| `test/.../core/xposed/XposedStateTest.kt`（新增，22 例） | fork 独有。重点：**`ACTIVE` + `DISCONNECTED` 必须可达且不健康**（本设计的全部意义）、**且不引导用户改框架配置**、`scope` 不参与判定、`everConnected` 只在断开时起作用（**我写错过这条断言**：把持久化历史标记误当成当前状态的一部分；测试改了、代码没改）、点击分类四例 | 我方 |
| `test/.../xposed/ReconnectPolicyTest.kt`（新增，6 例） | fork 独有：退避策略（翻倍、封顶 30s、5 步到顶、尝试上限）+ **反向约束**「20 次总等待 > 3 分钟」（上限太小会被一次较长重启耗尽） | 我方 |
| `test/.../permissions/ExternallyGrantedPermissionTest.kt`（新增，6 例） | fork 独有。**含反向断言**：不能为了修 XPOSED 就把所有 SPECIAL 权限都标成外部授予（那会把「能跳设置页」的权限也改成弹对话框，把好用的路径改坏）。已反证 | 我方 |
| `ui/settings/SettingsScreen.kt`、`SettingsRoute.kt`（改） | **移除临时探针入口**（P4 前置期的调试按钮，职责已由状态卡取代）。`core/xposed/XposedFrameworkProbe.kt` 在 P4a 被**改写**为 `XposedFrameworkMonitor`（非新建，理由见上） | **手动合并**（移除） |
| 三语 `strings*.xml` | 追加 Xposed 状态卡文案 11 条 ×3 + 引导文案 11 条 ×3；**并把权限描述从「用于获取当前 Activity」改为能力导向**（通道是通用的） | **手动合并**（追加/改写条目） |
| `docs/fork/xposed-channel-p4-design.md`（新增） | fork 独有：**P4 落地设计**（状态位判据的四层信息 L0–L3、组合判定表、授权引导、首页布局与卡片设计、实施顺序与验收项） | 我方 |
| `docs/fork/surveys/shortx-script-capability.md`（新增） | fork 独有：**ShortX 脚本机制深度调研**（引擎 = Rhino + MVEL、跑在 system_server、无超时无沙箱、五类落点；⭐ **关键位置发现**：`MethodHookExpressions` **只有 MVEL 没有 JS**、且其求值在**被 hook 的进程内** ⇒「能摸到宿主对象」是 **hook 能力而非脚本能力**；能力差距分 A/B 两类；§6 给出**不建议为 system_server 内执行脚本开口子**的五条依据）。该结论已写入 `xposed-channel-design.md` | 我方 |
| `docs/fork/xposed-channel-design.md`（改） | ① 头部状态更新（v2.2「未实现」→ v3.0「第一层已实现」）；② **决策 11/16 改判**（`TriggerService` 的 `exported` **是必需的**、Core 的 `BIND_ADDRESS` **保留** —— 新增 §5.3.3 记录核实依据与风险定性）；③ 新增 §6 **实施进度对照表**；④ **#15 结案**（实测 `callerUid=1000` + `registerCallback` 成功；机制是 **uid < 10000 时签名权限检查豁免**，故 `android:permission` 防普通 App、token 防伪造上行，**两者不是二选一**）；⑤ 记录官方框架 API 实测结果、**「为什么不给 system_server 内执行脚本开口子」**、以及**事件消费者单槽位缺陷**（加第二个 hook 触发器前必修） | **我方** |
| `docs/fork/surveys/README.md`（改） | 索引追加 `shortx-script-capability.md` 一行 | **手动合并**（追加） |

### 另三处**早期修复**（P4 期间一并补记）

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/xposed/HookConditionWire.kt`（改，修 **缺陷 ④**） | ⚠️⚠️ **包下推的判据必须比 App 侧宽松** —— 原实现用**精确匹配**，而 App 侧是**包含 + 忽略大小写**。用户填 `com.android.set`（默认「包含」模式）⇒ App 侧本该匹配 `com.android.settings`，但 hook 层精确匹配失败 ⇒ **事件根本不发出来** ⇒ 静默漏采（用户看到「配了但不触发」，App 侧日志什么都没有，排查会一直往 hook 点或系统版本上找）。已改为包含 + 忽略大小写。✅ **2026-09-27 真机验证通过**（用户实测：填部分包名仍能正常触发）。⚠️ **类名不下推是有意的**：要么紧（把 App 侧「包含」当白名单精确值 ⇒ 漏采）、要么松（子串匹配几乎放行一切），两头不讨好 | **我方** |
| `.../handlers/ActivityChangedTriggerHandler.kt`（改，修 **缺陷 ⑤**） | ⚠️⚠️ **下推白名单的选择性**：只要有**任一**触发器的包过滤推不了（**空 = 任意包**，或**含 `{{变量}}`**），就必须**整体放弃下推**（返回空列表 = 不限包）。原实现是「尽力而为地推一部分」⇒ 推不了的那个触发器**静默漏采**（用户配「任意包」却只在别的触发器列举的包里触发，可发现性极差：部分能用、换包就不灵）。⚠️ **变量的值理论上可静态解析**（`GlobalVariableStore` 是同步读、触发器只能引用全局变量），但**不做** —— 因为该 Store **无变更通知**，用户改了全局变量我们不知道 ⇒ 会下发**过期白名单** ⇒ 又是漏采（决策 17 的镜像）。理由记在 `computePushdownPackages()` 的注释里。✅ **2026-09-27 真机验证通过**（用户实测） | **我方** |
| `.../handlers/ActivityChangedTriggerHandler.kt`（改，修 **缺陷 ⑥**） | 日志措辞：⚠️ `TriggerService` 加载触发器**比 hook 层连接早约 1.6 秒**（实测）⇒ 首次下推**必然失败**，而旧文案说「需在 LSPosed 中启用并重启设备」—— **用户的配置其实完全正确**，会被误导去白折腾。已改为区分「还没连上（会自动重下发，无需处理）」与「持续失败（才检查配置）」。⚠️ **成功路径也加了日志**（原只有失败才打）—— 否则「重连后自动重下发」在日志里**完全不可见**，而它恰是最需要确认的一步 | **我方** |

### 另两处修复（事件消费者单槽位 + 状态卡文案，2026-09-27）

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/xposed/HookChannelController.kt`（改，修 **缺陷 ⑧**） | ⚠️⚠️ **事件消费者从「单槽位」改为「按 topic 分发的注册表」**。原实现是 `var eventSink: ((EventEnvelope) -> Unit)?` + `setEventSink()`，语义是**后注册的覆盖先注册的**。只有一个消费者时看不出问题，但**第二个 hook 触发器一加就出事**：<br/>`ActivityChangedTriggerHandler.start() → setEventSink(activity 的)`<br/>`KeyComboTriggerHandler.start() → setEventSink(组合键的)` ← **覆盖** ⇒ Activity 触发器**静默不再收到任何事件**。<br/>⚠️ 且是**双向的**：`TriggerHandlerRegistry` 按注册顺序建实例再统一 `start()` ⇒ **注册顺序靠后的那个会赢**，改一行顺序就换一个触发器失灵。表现是「新加的触发器能用、原来那个不触发了」，而 `HookSource` / `HookRuntime` 那两层**看起来毫无问题**（它们确实没问题，坏的是 App 侧分发）。<br/>现改为 `ConcurrentHashMap<String, (EventEnvelope) -> Unit>` + `registerSink(topic, sink)` / `unregisterSink(topic)`，`onReport` 按 `envelope.topic` 查表，**未知 topic 忽略**（§3.4.5）。⚠️ **按 topic 做 key 而非「广播给所有回调」**：每个 hook 触发器只关心自己的 topic（§3.4.2 的主题路由），无差别广播会让每个 Handler 都要自己过滤、且容易漏判。⚠️ `stop()` 从 `setEventSink(null)`（清空唯一槽位）改为 `unregisterSink(自己的 topic)` —— 注册表语义下前者会**误伤其他消费者** | **我方** |
| `test/.../core/xposed/HookChannelControllerTest.kt`（改，+4 例） | fork 独有：注册表的**真正走分发路径**的测试（多 topic 并存互不挤掉、注销一个不影响另一个、未知 topic 忽略）。⚠️⚠️ **这里有一个方法论教训**：我第一版写的测试**「反证时不变红」**——因为正常路径的 token 由 `SecureRandom` 生成、**测试拿不到** ⇒ 所有 `onReport` 都在 **token 校验那步 return** ⇒ 测试**永远走不到分发逻辑**。写了「看起来在测分发」的用例、实际什么都没测。<br/>修法是加 `injectTokenForTest(knownToken)` 测试接缝（已注明理由，避免后人以为多余而删）。改后反证成立（把 `registerSink` 退化成 `eventSinks.clear()` 后**两条同时变红**）。<br/>这与本仓库既有的教训同源：**测试要经过调用点，否则反证不会变红** | 我方 |
| `core/xposed/XposedFrameworkMonitor.kt`（改） | `Observation` 新增 `frameworkName` / `frameworkVersion` 两个字段（此前只在日志里用过、快照里没存）—— 首页状态卡要用它们显示「LSPosed 2.2.0」。⚠️ 每个字段单独 `try/catch`（`XposedService` 是对远端 AIDL 的包装，某个 getter 抛异常不该让整个观测失败）。⚠️ **断开时清空**（它是「当前连着」的信息，不是历史） | **我方** |
| `ui/home/HomeScreen.kt`（改） | ① 状态卡标题去掉「通道」二字（三语统一为 **Xposed**）；② 卡内小字由「作用域：system」改为**框架版本**（如 `LSPosed 2.2.0`）—— 用户反馈那一行更有用的是「我装的是哪个版」；作用域信息仍保留在引导对话框里（那里是核对配置的合适位置）。⚠️ 格式化时**任一为空则整体为空**，不拼出 `LSPosed ` 或 ` 2.2.0` 这种半截串（半截串会显示一行无意义内容）。⚠️ 异常时小字仍是「点击查看如何启用」（版本只在框架连着时有意义） | **手动合并** |
| 三语 `strings*.xml`（改） | `home_xposed_title` 去掉「通道」；**删除 `home_xposed_scope`**（改为版本显示后失去引用）。⚠️ **注意区分**：`FORK.md` 里「孤儿字符串故意保留」那条指的是**存量的、用户已确认保留的**那批；**本次改动新造**的孤儿（刚引入就废弃、从无引用场景）应当**删除**，不是套用那条约定 | **手动合并**（改写 + 删除条目） |

> **新增分歧时**：必须写清「文件/范围」「分歧内容」「冲突归属」三列。冲突归属一般是：
> - **我方**：fork 独有的新增文件/新增模块，保留我方。
> - **上游**：上游改动的文件，取上游版本。
> - **手动合并**：两边都改了同一文件，需逐块判断（例如 fork 只是在某文件追加了几行，而上游也改了该文件）。

---

## 暂未分歧、但日后改动时须登记的敏感点

以下是上游的核心区。目前 fork **尚未改动**它们；一旦改动（尤其是结构性改动），必须在上表登记，并评估合并成本：

- `app/build.gradle.kts` —— 编译配置、签名、ABI、依赖。上游可能频繁变更，改动时冲突面大。
  - ⚠️ **已有分歧（2026-09-23）：构建工具链三级联动（compileSdk 37 / AGP 9.4.1 / Gradle 9.6.0）**。
    起因是升 mikepenz renderer `0.39.2 → 0.45.0`，而它要求依赖方 `compileSdk >= 37`，
    后者又要求 AGP ≥ 9.4（AGP 9.0.1 官方最高推荐 36），AGP 9.4.1 又要求 Gradle ≥ 9.6.0。
    **一处版本号牵出四项联动，缺一不可**：
    | 项 | 原 | 现 | 文件 |
    |---|---|---|---|
    | `multiplatform-markdown-renderer` | 0.39.2 | **0.45.0** | `libs.versions.toml` |
    | `compileSdk` | 36 | **37** | `app/build.gradle.kts` |
    | `agp` | 9.0.1 | **9.4.1** | `libs.versions.toml` |
    | Gradle wrapper | 9.2.1 | **9.6.0** | `gradle/wrapper/gradle-wrapper.properties` |
    | SDK Platform | 无 | **android-37.0** | 本机 SDK（注意包名是 `platforms;android-37.0`，
      **不是** `android-37`——后者 404） |
    **⚠️ `targetSdk` 保持 36 未动**：它决定运行时行为（新 API 兼容开关），与「能否编译」是两件事，
    要动应单独评估 + 真机回归。**⚠️ 改了 `compileSdk` 会引入上游 API 的可空性收紧**：
    实测 `RecentTaskInfo.taskInfo` 由隐式非空变为可空，`ui/main/MainActivity.kt:239` 因此编译失败，
    已补安全调用（见该文件的 fork 注释）。**这类错误只有实际编译才会暴露**，只看依赖版本看不出来。
    **合并上游时**：若上游已提升 AGP/compileSdk/Gradle，取上游；`targetSdk` 两边可能不同，逐块判断。
  - ⚠️ **已有分歧（2026-09-22）**：`implementation(libs.jetbrains.markdown)` —— **显式提升 GFM 解析器
    `org.jetbrains:markdown` 0.7.3 → 0.7.14**（配套 `libs.versions.toml` 新增 `jetbrains-markdown` 版本与
    library 两项）。**起因**：mikepenz 声明依赖的解析器偏旧，而 **0.7.3 的 GFM 表格块前必须有空行**，
    否则整块塌成 `PARAGRAPH`、管道符原样显示（实测：`正文段落` / `**粗体行**` 紧贴表格 → 0 个表格）。
    表现为「AI 回复里表格没渲染成表格，且一部分正常一部分不正常」——**前面是标题或空行的表格正常，
    紧贴段落的被吞**。用一份真实会话（27 条含管道回复）量化：0.7.3 解析出 24 个表格、8 条消息全无表格；
    0.7.14 解析出 40 个表格、0 条消息全无。
    **⚠️ 升级 renderer 不能替代这条覆盖**：实测各版 renderer 声明的解析器版本都偏旧
    （`0.39.2→0.7.3`、`0.42/0.43→0.7.5`、`0.44/0.45→0.7.9`），**0.45.0 声明的也才 0.7.9**，
    表格空行修复在 0.7.14，故本行必须保留。
    **验证方式**：`:app:dependencyInsight --dependency org.jetbrains:markdown` 应显示
    `By conflict resolution: between versions 0.7.14 and 0.7.9` → 选中 0.7.14。
    冲突面小（2 文件各 1-2 行）。
  - ⚠️ **已有分歧（2026-09-15）**：`versionCode 49 → 50`、`versionName "1.5.3-pr1" → "1.5.4"`（提交 `3360e63b`）。
    fork 首次自定版本号——此前 `1.5.3-pr1` 是上游 5 月定的。**合并上游时此处取上游**，
    然后按需重新决定 fork 号段。冲突面小（两行），但每次上游 bump 版本号都会撞上。
  - ⚠️ **签名文件的特殊存放（2026-09-20 记录）**：`vFlow.jks` 与 `signing.properties` 均在 `.gitignore`
    （第 11-12 行）中、**未纳入版本控制**，只存在于本地工作区。影响：
    **新建 worktree 时这两个文件不会带过去**，构建会静默降级并打印 `⚠️ Release 签名文件未找到`，
    产物改用 AGP 默认 debug 签名 → 装到已装正式版的设备上会因签名冲突失败。
    **处理方式：从 `dev` 分支的工作区复制**（`git checkout dev -- vFlow.jks` 无效，文件未被跟踪）。
    签名者应为 `CN=vFlow Fork, OU=Fork, O=nightking8342`。见 `AGENTS.md`「常用命令」。
    这条**不是与上游的分歧**（两边都没跟踪这两个文件），而是本仓库的操作约定，记录于此以免再次踩坑。
- `settings.gradle.kts` —— 模块声明（`:app` `:core`）。
- `gradle/wrapper/gradle-wrapper.properties` —— Gradle 版本。
  - ⚠️ **已有分歧（2026-09-23）**：`9.2.1 → 9.6.0`。是 AGP 9.4.1 的硬性要求
    （AGP 9.4.1 启动时报 `Minimum supported Gradle version is 9.6.0`）。见上方
    `app/build.gradle.kts` 的「构建工具链三级联动」条目。**合并上游时取上游**，
    但需确认取到的 Gradle 版本仍满足 AGP 的下限。
- `app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt` —— 模块注册表。**新增模块时在 `initialize()` 里按分类追加一行即可，不要重排已有注册**，否则每次上游合并都在这个文件解冲突。
- `core/src/main` —— vFlow Core 独立进程（Master-Worker）。改动独立，应单独评估、单独 patch。
  - ⚠️ **已有分歧（2026-09-21）**：`LogcatStreamWrapper.kt` 改为**代次隔离**（`AtomicLong generation` + `@Volatile activeGen` 替代共用的 `running` 布尔）。修的是「装包后 logcat 触发器失灵」——见分歧清单。该文件是 fork 新增文件，但**改动了 Core 的流式行为**，且 `LogcatEventQueue.kt` 的 `clear()` 语义被明确（只丢事件、不清计数，新增 `resetAll()`）。
  - ⚠️ **改 Core 需重启才生效**：Core 是独立进程，装包不会杀掉它。`vflowCoreVersion`（`core/build.gradle.kts`）**不在开发过程中改**，只在发版时改。
- `app/src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt` —— 工作流执行器核心循环。改动风险高，须谨慎。
- `app/src/main/java/com/chaomixian/vflow/api/` —— 远程 API。**新增 handler 时新增文件，不要改既有接口签名**。
- `app/src/main/java/com/chaomixian/vflow/core/workflow/model/Workflow.kt` / `ActionStep.kt` —— 工作流数据模型。上游改动会波及大量解析/序列化代码。
- `app/src/main/res/values*/strings.xml`（中/英/日） —— 字符串资源。fork 一直以**追加条目**的方式改，冲突面小但**每次上游合并都会撞**（同文件同区域）。⚠️ 追加时注意：
  - 三语言**必须同步**（漏一个会导致该语言下显示成另一个语言的文案）
  - 键名重复会**构建失败**（`Resource and asset merger: Found item String/x more than one time`）——追加前先 `grep` 一次，已实际踩过
  - ⚠️ 本仓库有一批**孤儿字符串**（定义了但零引用，用户已确认"确实不要"那些提示）。它们**故意保留**，作为将来可能的引用来源，不要当成死代码清理。

---

## 上游同步流程

跟着上游的 **release tag** 合并（而不是追每个 commit），因为上游节奏是「低频发版」。步骤：

1. 工作区必须干净（`git status` 无未提交改动）。
2. `git fetch upstream --tags`
3. `git checkout master` 并 `git fetch upstream`
4. `git merge <tag>`（如 `git merge v1.5.2`；merge 而非 rebase，保留独立 merge commit，不 squash）
5. 解冲突：按上表「冲突归属」处理；表里没有的冲突按常规判断并考虑是否登记。
6. 门槛检查：`./gradlew test`（app 模块有单元测试）+ `./gradlew assembleDebug` 构建通过。
   - 注意：Android 项目在 Windows 本地跑 `./gradlew test` 需要 Android SDK + JDK 17 就绪；若环境缺失，以 CI 的构建结果为权威门槛。
   - 涉及 `core/` 或原生 OCR（ncnn/CMake/JNI）时，还需确认 `:core:buildDex` 与 `externalNativeBuild` 通过。
7. 回归扫描：`git grep -nE "(chaomixian|vflow)\.(com|net|app)" -- ':!*.md'` 对比合并前后，确认上游没有引入意料之外的硬编码地址；有则评估处理并登记。
8. 全部通过后 `git checkout dev` 并 `git rebase master`（把 dev 重基于最新上游），再 `git push -u origin dev`。
   - rebase 时若你的 fork 改动与上游冲突，按「冲突归属」处理；这也是一次检查 diff 面积的机会——冲突越多，说明改动太贴近上游核心，应回头评估是否拆成新增文件。

---

## 提交规范

- **fork 自己的改动**：提交信息加 `fork:` 前缀（如 `fork: 新增 AI 调试生成工作流模块`），方便 `git log` 区分来源。
- **新增模块 / 新增文件**：`fork:` 前缀 + 说明分类与用途。
- **上游合并**：保留默认 merge commit 信息（`Merge tag 'v1.5.2' ...`）。

---

## 分支约定

- `master` → 只追踪上游，永远只从上游同步，不改。
- `dev` → fork 主干，承载所有 fork 改动；上游同步后 rebase 到 master。
- `feature/*` → 每次开发用的临时分支，完成后合并回 dev。
- 不建议直接在 `master` 上开发。
