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
| `services/CoreLauncher.kt`（改） | `deployDex` 成功后调用 `recordLaunchedDexFingerprint(context)` —— 这是"改了 core 需重启"判据的另一半。⚠️ 必须在**部署成功之后**调用，提前记录的话部署失败就再也不会提示 | **手动合并**（新增 1 行） |
| `ui/home/HomeScreen.kt`、`ui/settings/CoreManagementActivity.kt`（改） | `coreNeedsUpdate` 的判据由"版本号落后"改为 **dex 指纹变化**（`versionStatus.needsUpdate \|\| isCoreDexNewerThanRunning()`） | **手动合并**（各 1 处判断 + 文案） |
| `core/build.gradle.kts`（改） | ① 加 `testImplementation("junit:junit:4.13.2")` —— **core 是纯 JVM 模块，可以放 src/test 跑 JUnit**（文档里"core 没有测试目录"只是现状描述）；② `vflowCoreVersion` 注释重写，明确 **🚫 不要在开发过程中改它**（只在发版时改） | **手动合并** |
| `test/services/CoreDexFingerprintTest.kt`（新增） | fork 独有：13 例。锁住"该提示却返回 false"（会让用户静默跑旧代码）与"不该提示却返回 true"（提示不可信）两个方向 | 我方 |
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
| `docs/fork/logcat-debug-tool.md` | fork 独有：**logcat 调试工具设计文档**（前置工具，用于采集日志调试触发器）。核心是「开关式后台采集（shell 侧写文件、`-r`/`-n` 轮转）+ 快照兜底」，**全程走普通 `exec`、不碰流式协议**。含**两条已真机实测证实**的硬约束（Binder 传输上限；后台进程不切断 stdio 会永久挂起）、三态采集状态机（`IDLE`/`CAPTURING`/`STALE`）、单一刷新按钮与 TAG 统计绕过过滤、降级行与日志头标记行处理、与触发器的纯函数复用关系。**§4.2.3 记录实现期踩出的三个静默失效坑**（`StateFlow` 等值去重不发射、`timeout` 自然收尾同样留 `STALE` 导致假异常提示、自动停止不可在 ticker 协程内取消自己）。**部分实现**：纯函数层 + 超级岛层 + 采集控制器 + 岛按钮接收器已完成，查看器 UI 与导出待做；上游无此文件 | 我方 |
| `core/workflow/module/triggers/DoNotDisturbTriggerModule.kt`、`.../handlers/DoNotDisturbTriggerHandler.kt`（均新增） | fork 独有：免打扰触发器。走 `ACTION_INTERRUPTION_FILTER_CHANGED` 广播，只需 `NOTIFICATION_POLICY` 权限（不需通知使用权）。含顶层纯函数 `isDndFilterEnabled` / `isDndEnabled` / 日志探针 `probeDndState` | 我方 |
| `res/drawable/rounded_do_not_disturb_on_24.xml`（新增） | fork 独有：免打扰触发器图标（上游无勿扰图标，`DoNotDisturbModule` 借用的是 `rounded_notifications_unread_24`） | 我方 |
| `test/.../triggers/DoNotDisturbTriggerMathTest.kt`、`DoNotDisturbTriggerModuleTest.kt`（均新增） | fork 独有：上述纯函数逐值锁定 + 枚举规范化 + 权限/输出声明体检 | 我方 |
| `core/workflow/module/ModuleRegistry.kt` | `initialize()` 按分类追加注册 `DoNotDisturbTriggerModule`（不重排已有注册） | 手动合并（追加一行，取上游 + 追加） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 按分类追加注册 `DoNotDisturbTriggerHandler`（不重排已有注册） | 手动合并（追加一行，取上游 + 追加） |
| 字符串资源 `strings_module.xml`（中/英/日） | 追加免打扰触发器文案 9 条 ×3 语言 | 手动合并（追加条目） |
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
| `docs/fork/chat-float-window-ui.html` | fork 独有：Chat 悬浮窗可交互 UI 原型（折叠/展开/审批/输入/状态一致性五组演示），上游无此文件 | 我方 |
| `docs/fork/fold-trigger-design.md` | fork 独有：**折叠屏触发器设计**（`vflow.trigger.fold`）v3.0，**已实现并通过真机验证**（小米 MIX Fold 3，结论见 §9）。核心决策：做「状态推断」而非事件监听；**以铰链角度为主力信号**（实测 `device_posture` 滞后 1–2 秒且半折值不可靠，信号优先级已对调）；不引入 `androidx.window`；输出 6 项魔法变量（含 `posture_source` 诊断项）。**实测推翻了外部调研的悲观结论**——铰链传感器双向完整上报，非「只在折叠方向」。§8.1 记录一处既有缺陷：关闭后台服务通知会使服务降级、所有传感器类触发器静默失效。上游无此文件 | 我方 |
| `core/workflow/module/triggers/FoldTriggerModule.kt`、`FoldTriggerData.kt`、`FoldStateResolver.kt`、`handlers/FoldTriggerHandler.kt`（均新增） | fork 独有：**折叠屏触发器实现**。`FoldTriggerModule`=模块定义（1 个 ENUM 参数 + 6 个输出）；`FoldTriggerData`=`@Parcelize` 载荷；`FoldStateResolver`=**纯函数状态机**（信号融合/迟滞/去抖/边沿检测，无 Android 依赖，可纯 JVM 单测）；`FoldTriggerHandler`=继承 `BaseTriggerHandler`，融合铰链角度（主力）+ 小米 `device_posture`（校验）两路信号。**厂商私有 key `device_posture` 完全收敛在 Handler 内**。阈值经真机实测校准：折叠 <40° / 展开 >150° / 半折 40–150° | 我方 |
| `res/drawable/rounded_fold_24.xml`（新增） | fork 独有：折叠屏触发器图标（双屏对折造型，上游无此 drawable） | 我方 |
| `test/core/workflow/module/triggers/FoldStateResolverTest.kt`（新增） | fork 独有：状态机单测 25 例（三态判定、迟滞防抖、去抖计时、信号降级、角度优先于 posture、枚举序列化稳定性）。含「实测抖动 7° 不触发变迁」的回归用例 | 我方 |
| `scripts/fold-trigger-verify.sh`（新增） | fork 独有：折叠屏 P0 真机验证脚本（adb，零代码）。`snapshot` 快照 / `watch` 多信号实时对比 / `raw` 全量转储；采集 `device_posture` + 铰链角度 + 小米私有 `fold_status` + DeviceStateManager + 内外屏状态五路信号。**可复用于其他折叠屏机型复测** | 我方 |
| `core/workflow/module/ModuleRegistry.kt` | 触发器段追加 1 行 `register(FoldTriggerModule(), context)`（不重排既有注册） | 手动合并（追加一行，取上游 + 追加） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | `initialize()` 追加 1 行 `register(FoldTriggerModule().id) { FoldTriggerHandler() }`（不重排既有注册） | 手动合并（追加一行） |
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
| `docs/fork/surveys/`（`README.md` + `ai-system-overview.md` + `agent-design-comparison.md` + `trigger-system-overview.md` + `logcat-readability-survey.md` + `notification-system-overview.md`） | fork 独有：**现状调研文档目录**（改代码前的地图）。`README.md` 为索引+写作规范；`ai-system-overview.md` 为 AI 体系梳理（三套链路/提示词/工具/技能/执行流程/模块可发现性/缓存 + 能力评估）；`agent-design-comparison.md` 为头部 Agent 项目外部对照调研；`trigger-system-overview.md` 为触发器体系梳理（两层注册/两条链路/三种 Handler 范式/24 个触发器清单与权限矩阵/排障顺序）；`logcat-readability-survey.md` 为 logcat 可读性真机实测结论；`notification-system-overview.md` 为通知体系梳理（8 处生产者 + 监听消费者、活体通知分支、渠道/权限/开关、超级岛改造落点与风险）。上游均无此文件 | 我方 |
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

> **新增分歧时**：必须写清「文件/范围」「分歧内容」「冲突归属」三列。冲突归属一般是：
> - **我方**：fork 独有的新增文件/新增模块，保留我方。
> - **上游**：上游改动的文件，取上游版本。
> - **手动合并**：两边都改了同一文件，需逐块判断（例如 fork 只是在某文件追加了几行，而上游也改了该文件）。

---

## 暂未分歧、但日后改动时须登记的敏感点

以下是上游的核心区。目前 fork **尚未改动**它们；一旦改动（尤其是结构性改动），必须在上表登记，并评估合并成本：

- `app/build.gradle.kts` —— 编译配置、签名、ABI、依赖。上游可能频繁变更，改动时冲突面大。
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
