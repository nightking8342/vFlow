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
| `services/island/`（`IslandCapability.kt` + `IslandNotificationSpec.kt` + `IslandParamsBuilder.kt` + `IslandIcons.kt` + `IslandNotificationDispatcher.kt` + `IslandRemoteViews.kt`，均新增） | fork 独有：**小米澎湃 OS 超级岛装配层**。能力探测（`notification_focus_protocol >= 3` + `canShowFocus`，异步一次 + 进程缓存）、厂商中立的通知语义（含 `stepName` / `statusText` / `progressText`）、`miui.focus.param.custom` JSON 装配（纯函数；**只写 custom，不写模板 key**——两者并存会让 SystemUI 走模板分支、忽略 RemoteViews）、`miui.focus.pics` 图标装配（key 必须带 `miui.focus.pic_` 前缀，否则 SystemUI 解析不到）、**展开态 RemoteViews**（浅/深/岛展开三实例，⚠️ **每次派发都新建**）、对外唯一入口。厂商私有协议完全收敛在本目录内，业务侧不出现任何 `miui.*` 字符串 | 我方 |
| `services/island/IslandNotificationDispatcher.kt` + `IslandRemoteViews.kt`（改，**修 `TransactionTooLargeException` 崩溃**，2026-10-04） | ⚠️⚠️ **删掉 RemoteViews 的按 `workflowId` 缓存复用，改为每次派发新建**；同时删掉 `viewsByWorkflow` 表与 `releaseViews()`（连 `ExecutionNotificationManager` 里的调用点一并去掉）。**起因是真机必现崩溃**：2026-09-29 的崩溃日志（小米 2308CPXD0C / Android 17）第 **84** 次 `notify` 抛 `TransactionTooLargeException: data parcel size 1035216 bytes`。<br/>**根因（探针实测确证，非推断）**：`RemoteViews` 的**每个 setter 都是「追加一条动作」，从不替换**；而 `notify()` 每次都是**完整**事务（把增量应用到已有视图的入口是 `RemoteViews.reapply()`，**不是** `notify`）。原注释写的「复用后只传差异」来自对 `reapply` 的直觉，**用错了 API**。复用 ⇒ 动作列表随更新次数单调增长 ⇒ 通知体积线性上升。<br/>**实测数据**（`scripts/probe/island-probe/`，本机复现同一崩溃机型）：复用 + 每轮 8 个 setter，**9,492 B →（200 轮后）217,252 B，每轮 +1,044 B，严格线性**；**每轮新建则 9,492 → 9,496 B，完全不涨**；普通通知（无 RemoteViews）2,396 → 2,400 B。按 vFlow 真实节奏（`applyToCard` 约 20 setter × 3 实例 × 84 次更新）反推 **0.58–0.71 MiB**，与崩溃实测 1.01 MiB **同量级** ⇒ 复现吻合。<br/>⚠️ 代价可忽略：新建只是本地构造对象（布局在 SystemUI 侧 inflate），且**每次携带的正是必要的全量动作**。⚠️ 删 `releaseViews()` 后**没有内存泄漏**——原来那个缓存表就是唯一的持有者，现在没有跨次持有 | **手动合并**（dispatch 内 1 处构造 + 删表/删方法 + 1 处调用点 + 类注释重写） |
| `scripts/probe/island-probe/`（新增，探针） | fork 独有：**通知体积探针**（`MainActivity.java` + `AndroidManifest.xml` + `res/layout/probe_island.xml` + `build.sh` + `verify.sh` + `README.md` + `.gitignore`）。⚠️ **不跑 vFlow 的代码**，只复现同一套 RemoteViews 用法，5 个场景各 200 轮、**每次只动一个变量**（复用/新建 RV × 写/no 写位图 × 附/no 附 pics）+ 一个无 RV 的基线。判据是**整条采样曲线的形态**（`linear` / `plateau` / `flat`）——只看首末分不出「递增」与「早涨后平」，而两者结论相反。⚠️ 线性分支把外推值与崩溃真值 **1,033,192 字节**做**计算比较**并如实报「同量级」或「差 N 倍」。⚠️ 三条安全约定：**独立包名 `com.vflow.islandprobe` + debug 签名**（与已装 vFlow 无关 ⇒ 可在日常设备上跑；**不要**改成同签）、自建渠道与通知 ID 并在结束时全部清掉、不碰 vFlow 任何数据。⚠️ 写它时踩到四个坑并已写进代码：**必须先 `aapt2 link --java` 生成 `R.java` 再 `javac`**（既有探针不用 `R.` 故没踩过）、**必须先 grant `POST_NOTIFICATIONS`**（否则 notify 静默失败 ⇒ 全 0 字节 ⇒ **假阴性**）、**Windows 下 Python stdout 默认 GBK** 会让判定文案乱码且 `✓` 抛 `UnicodeEncodeError`、**adb 必须固定第一条 serial** | **我方** |
| `res/layout/island_execution_expand_{dark,light}.xml`（新增） | fork 独有：超级岛展开态 RemoteViews 布局（五行：头部 / 进度行 / 进度条 / 状态行 / 底部）。两份布局 **view id 完全一致**（仅颜色资源不同），故同一套操作代码通用。浅色版是必需的——该 RemoteViews 被通知栏与锁屏预览复用，那两处有浅色模式。**未提供 `rv.tiny`**：那是小折叠（flip）机型的折叠态视图，大折叠不需要，mindfs 的实现也不写 | 我方 |
| `res/drawable/island_rv_*.xml`（12 个新增） | fork 独有：展开态配套 drawable（状态胶囊底 / 进度条 / 结束按钮，各深浅两套）。图标用应用图标（运行时裁剪为圆形位图），故无图标圆底资源 | 我方 |
| `docs/fork/island-expand-ui.html` | fork 独有：超级岛展开态**可交互 UI 规格原型**（方案 B · 进度条整行 + 状态独占一行，含浅/深双配色、状态切换、色值 token 表与交付映射） | 我方 |
| `test/services/island/IslandParamsBuilderTest.kt`（新增） | fork 独有：装配单测 25 例（JSON 结构、A 区「进度 · 步骤名」拼接与分隔符省略、B 区模块实时状态、状态映射、浮出策略、强调色差异） | 我方 |
| `test/services/island/IslandCustomParamsTest.kt`（新增） | fork 独有：自定义路径（`param.custom`）装配单测 14 例。关键断言：扁平结构无 `param_v2` 包裹、**自定义模式必须携带完整 `param_island`**、**两条路径的岛数据逐字段一致**（保证改用 RemoteViews 不影响大岛/小岛） | 我方 |
| `test/services/ExecutionNotificationIdTest.kt`（新增） | fork 独有：通知 ID 派生回归测试（区间合法性、与既有通知 ID 不冲突、稳定性、负 hashCode、空 ID） | 我方 |
| `services/ExecutionNotificationManager.kt` | ① `ExecutionNotificationState` 新增 `Failed` 子类（此前失败复用 `Cancelled`，无法区分「失败」与「用户停止」）；② 通知 ID 由固定 1998 改为 `executionNotificationIdFor(workflowId)` 派生到 `[100000, 150000)`（修复并发执行互相覆盖 + 取消误删）；③ `cancelNotification()` 改为 `cancelNotification(workflowId)`；④ 两个 build 方法补 `setContentIntent`（此前点击通知本体无反应）；⑤ `notify` 经 `IslandNotificationDispatcher.dispatch` 附加岛参数；⑥ `initialize` 加一次异步能力探测；⑦ 新增 `islandSpecOf` 状态映射与 `isStepTransitionMessage`（区分「步骤切换」的导航文案与模块自报的实时状态，前者丢弃）；⑧ 新增按 `workflowId` 的计时基准表与当前步骤名表（`Chronometer` 与步骤名用，终态清理）；⑨ 终态时调 `IslandNotificationDispatcher.releaseViews` 释放 RemoteViews 缓存（⚠️ **2026-10-04 已移除** —— `releaseViews` 与那套缓存随 `TransactionTooLargeException` 崩溃一并删掉，见上一条 `IslandNotificationDispatcher` 的登记）；⑩ **有岛能力时不再调 `setRequestPromotedOngoing(true)`**——AOSP 活体通知提升与超级岛是互斥的渲染路径，真机实测同时存在时 SystemUI 走 AOSP 渲染、忽略焦点通知的自定义视图（表现为只有终态能显示 RemoteViews）。无岛能力时保留提升，那是 API 36+ 非小米设备上唯一的活体通知能力。上游若改这几个方法需逐块判断 | **手动合并**（改动集中在 `notify` 调用与状态分支） |
| `core/execution/WorkflowExecutor.kt` | ① 失败与超时改用 `ExecutionNotificationState.Failed`（两处）；② finally 块的「3 秒后取消通知」加条件——失败与超时保留通知给用户查看，不再无条件取消；③ `executeWorkflowInternal` 增加 `isSubWorkflow` 参数，**子工作流不再发通知**（修复：子工作流的通知收尾不在主工作流的 finally 里，会以 Running 状态永久残留；该缺陷此前被固定通知 ID 掩盖）。改动集中在 `:326-328`、子工作流调用点与三处通知加条件，`updateState` 对外签名未变，9 个 `updateState` 调用点均未改动。上游若改执行器收尾逻辑需逐块判断 | **手动合并** |
| `core/execution/WorkflowExecutor.kt`（改，**失败弹窗可关闭**，2026-10-02） | ④ `execute()` 加带默认值参数 `showErrorDialog: Boolean = true`；⑤ `executeWorkflowInternal()` 同名参数（**必须带默认值**，它有 **3 个**调用点：`execute()` 的两个分支 + `executeSubWorkflow()`，第 3 处刻意走默认值）；⑥ `execute()` 的两个分支透传；⑦ 失败分支的 `uiService?.showError(...)` 整段包进 `if (showErrorDialog)`。⚠️⚠️ **起因是一个真实缺陷**：`showError` 是 suspend 且排在 `postState(Failure)` **之前** —— 用户不点弹窗则终止状态永不广播、协程 `finally`（WakeLock 释放 / 工作目录清理 / 工作流摘除）永不执行。Agent 调试时用户在看聊天卡片（与交给模型的文本**同源**），弹窗纯属重复且会盖住卡片 ⇒ 该场景传 `false`。⚠️ **识别方式刻意用调用参数而非给 `Workflow` 加字段**：`buildWorkflowForSave`（Agent 的「存为工作流」）会把它变成正式工作流，加字段有**静默泄漏**风险（用户保存后的工作流从此不弹错误弹窗且无提示）。⚠️ 实测核过：`execute()` 全仓 **13** 个调用点，全部靠默认值不受影响 | **手动合并**（签名+两处透传+一处判断，均在失败分支与入口附近） |
| `docs/fork/chat-agent-enhancement-plan.md` | fork 独有：Chat Agent 四点改造方案（技能目录化/Prompt 缓存/catalog 全量化+模块查询工具/悬浮窗），上游无此文件 | 我方 |
| `docs/fork/agent-debug-failure-visibility.md` | fork 独有：**Agent 调试失败可见性方案**（**已实施**）。解决两个问题：① `WorkflowExecutor` 的错误弹窗排在 `postState(Failure)` 之前且 suspend ⇒ 用户不点则**终止状态永不广播、`finally` 永不执行**（WakeLock / 工作目录 / 摘除登记）；Agent 调试改为不弹窗；② `SKIP` 策略下 `Finished` 被误判为 `SUCCESS` ⇒ 卡片与 Agent **同时**看到「completed successfully」。含 4 条决策（为何用调用参数而非 `Workflow` 字段、为何 SKIP 判据必须是 SKIP 专属日志行而非失败行、摘要为何必须在截断前抽取）、§6 一份**子代理独立核实结论表**（抓出并修掉 5 处错误，含 2 处事实错误：调用点 13 不是 15、`executeWorkflowInternal` 3 处不是 2 处）。上游无此文件 | 我方 |
| `docs/fork/chat-agent-rearchitecture.md` | fork 独有：**Chat Agent 架构重构设计**（基于 **CCB / dsh / OpenCode / Pi 四家**源码对照）。v1.5.3 为决策定稿版：三病症诊断、四家技能注入位置与工具暴露策略对照（含"内建工具 vs 扩展工具"的关键区分）、目标架构（**工具 72→16**：撤出 59 个模块工具，改由 `query_module_schema` + `call_module` + `load_skill` 按需）、**查询域≠调用域**的设计 B、两批执行契约与验收项（§4）、长期分叉的接管范围（§5.1）、决策台账（§6）。上游无此文件 | 我方 |
| `docs/fork/workflow-read-write-tools.md` | fork 独有：**工作流读写工具设计**（`get_workflow` / `update_workflow`）。v2.1（**P0 已实现并验证**），40 项决策台账（§9）：**操作原语补丁**入参形态（`metadata` / `steps{update,insert,delete,move}` / `triggers{update,insert,delete}`）、token 成本对比、固定执行顺序与原子性、按结果状态重算风险等级。**核心是五处静默失效点的处理**（§3）：`vflow.logic.jump` 的 1-based 显示序号在增删步骤后错位（含变量型 jump 无法静态重映射）、步骤 id 锚定禁掉 `step_N` 兜底、**块结构破损的执行期失败模式以静默跑错为主**（If 缺 END 时条件不成立照样往下执行）、函数签名改动静默废掉引用方、触发器 id 变化断 `{{triggerId.outputId}}` 引用（输出按 id 键控）。**块结构校验与逆补丁回滚降级到 P2（只探测/警告，不拦截）**。上游无此文件 | 我方 |
| `docs/fork/trigger-label-design.md` | fork 独有：**触发器标签设计**（v1.0，**设计阶段未实现**）。给同一工作流的多个触发器各打一个标签，工作流内以固定变量判断「本次是哪个触发器触发的」（现状：`seedTriggerOutputs` 会给**所有同 moduleId 且 schema 相同**的触发器写同一份输出 ⇒ 下游拿不到「谁触发的」）。**★ 命名定案：存储 / 引用 / AI 三处同名 `__trigger_label`**（双下划线对齐既有保留参数 `__error_policy` / `__retry_count`；引用为 `[[__trigger_label]]`，未设置时是**空串而非 `VNull`**）。12 项决策台账、8 条静默失效点清单。**两处必须一并修的既有缺陷**：① `WorkflowEditorActivity.kt:990` 的 `onSave` **else 分支整表替换 parameters** ⇒ 用户设好标签、再点开改一次触发条件就**静默丢失**（同分支还吃掉存量的 `__error_policy` / `__retry_count`）；② AI 侧 `buildParameters` / `applyParameterPatch` 把未知 key 判 reject ⇒ **`update_workflow` 会整份补丁不落地**，故须在 `resolveModuleInputDefinitions` 对 `vflow.trigger.*` 统一注入该 key（**只此一处**，不改 `getInputs()`——那会把标签塞进编辑器通用表单区）。AI 能力与人工等价（schema 说明即可，**不做友好字段名渲染**）。上游无此文件 | 我方 |
| `core/workflow/WorkflowPatch.kt`（新增） | fork 独有：`update_workflow` 的**纯函数补丁层**（参数按 key 合并 + `null` 删键、`insert`/`delete`/`move` 列表手术、jump 序号重映射、块成员判定）。无 Android 依赖，故 22 例语义单测可跑纯 JVM。**关键语义**：`move.to_index` 按「补丁前」列表解释（流水线里 move 在 delete 之后）、遗漏≠删除、jump 目标被删必须拒绝 | 我方 |
| `test/core/workflow/WorkflowPatchTest.kt`（新增） | fork 独有：上述 22 例。锁「改错了不报错、只静默变差」的语义；关键用例已做**反证**（改回 bug 版本确认变红） | 我方 |
| `core/workflow/WorkflowJumpReferenceUpdater.kt` | ⚠️ **P0 未改动**——新增的 `remapJumpReferences` 放在新文件 `WorkflowPatch.kt` 里，既有 `remapAfterReorder` 原样保留（编辑器拖拽调用点依赖它的数量相等前置条件）。见 `docs/fork/workflow-read-write-tools.md` §10 | （暂未分歧） |
| `ui/chat/ChatAgentToolRegistry.kt`（改） | 新增 4 个常量 + 2 个工具定义（`get_workflow` / `update_workflow`）+ 4 个 schema 构造；`toolsByName` 注册 2 项。该文件已认长期分叉 | **我方**（认长期分叉） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改） | ① `ChatPreparedToolItem` 追加 `UpdateWorkflow` 子类（含 `workflowId` / `warnings`）+ 补齐 5 处 `when` 分支；② 新增 `prepareGetWorkflow` 一系（含**共用错误构造 `workflowNotFoundResult`**，`get_workflow` 与 `update_workflow` 同用，避免口径漂移；**刻意不做「最近似 id」**——id 间无近似关系，模糊匹配等于用名字选目标）；③ 新增 `prepareUpdateWorkflow` / `applyMetadataPatch` / `applyStepPatch` / `applyParameterPatch`（**以步骤现有参数为 base**，非模块默认值）/ `collectTouchedStepIds`（`validate` 只跑本次触及的步骤，避免卡住存量工作流）/ `executeUpdateWorkflow`（**落盘异常时回写原件**——本项目无版本历史）/ `buildUpdatedWorkflowResultText`。改动集中在这几处 | **手动合并** |
| `ui/chat/ChatAgentModuleExecutor.kt`（改，**Agent 调试失败可见性**，2026-10-02） | ④ `executeTemporaryWorkflow` 调 `WorkflowExecutor.execute` 时传 `showErrorDialog = false`（1 行）；⑤ `buildTemporaryWorkflowResult` 改为委托给两个纯函数（`temporaryWorkflowStatus` + `buildTemporaryWorkflowOutputText`）；⑥ **删除**私有 `truncateMultiline`（下移到 `ChatMessagePatch.kt`）。⚠️⚠️ **同时修掉一个既有缺陷**：原实现 `status = if (Finished) SUCCESS else ERROR`，而 `Finished` 只说明「**跑完了**」——某步失败但错误策略为 `SKIP` 时工作流会继续跑完 ⇒ 卡片与 Agent **同时**看到 `completed successfully.`，失败详情只埋在日志里。现改为额外检查 SKIP 专属日志行后降级为 `ERROR` | **手动合并**（调用点 1 行 + 结果构造重构） |
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
| `ui/chat/ChatMessagePatch.kt`（改，**追加失败可见性纯函数层**，2026-10-02） | fork 自有文件内扩充：新增 `extractFailureSummary` / `hasSkippedFailure` / `temporaryWorkflowStatus` / `buildTemporaryWorkflowOutputText` / `truncateMultiline`（后者由 `ChatAgentModuleExecutor` 下移）。⚠️⚠️ **核心是 SKIP 判据的锚点**：**不能用失败行**（`E/模块执行失败`）判「有步骤被跳过」——`WorkflowExecutor` 的重试循环里 `finalResult` 每轮被重新赋值、`when` 只在最终结果上执行，故 `RETRY` 重试途中抛异常会留下 `E/模块执行异常` 行，用「含 E 行」判会**把正确执行误判成失败**。正确锚点是 SKIP 分支自己打的 `W/…根据策略，跳过错误继续执行。`（只在最终 Failure 且策略为 SKIP 时出现）。⚠️ 另两条契约：**摘要在截断前抽取**（`truncateMultiline` 取开头 4000 字符，而失败行在末尾 ⇒ 先截断会静默取不到）、**状态与正文成对产出**（只抽状态会丢 `Execution log` 段）。⚠️ `lastLogLineAfter` 刻意**不**加 `takeIf { isNotBlank() }` —— SKIP 标记行的正文就是前缀本身，剥掉后是空串，加了会把「命中」误判成「没找到」（实现期实际踩到） | 我方 |
| `test/ui/chat/ChatStream{Assembler,Normalizer}Test.kt`、`ChatSseStreamTest.kt`、`ChatSseFixtureTest.kt`、`ChatMessagePatchTest.kt`（均新增）+ `app/src/test/resources/chat-sse/`（8 条 fixture） | fork 独有：**流式层 84 例**。fixture **可信度分级**（Anthropic 3 条=官方报文**规范级**；OpenAI/Responses 4 条=官方 SDK 的 OpenAPI 生成类型定义**字段级**；DeepSeek 1 条=dsh 实测**单 provider 级**）。⚠️ 分级不可混同——这是这类测试最危险的误用方式 | 我方 |
| `test/ui/chat/ChatTemporaryWorkflowResultTest.kt`（新增，20 例） | fork 独有：失败可见性纯函数层。重点是**反向断言**：「重试耗尽 ≠ 有步骤被跳过」（用失败行做判据时必红）、「STOP 失败不算跳过」、前缀出现在行中时不误匹配（防堆栈回溯误命中）、长日志（>4000 字符、失败在末尾）下摘要仍能取到。⚠️ **反证已实际做过**：把 SKIP 判据退回失败行 ⇒ 3 条变红；把摘要改成从截断后文本提取 ⇒ **恰好 1 条**变红 | 我方 |
| `test/ui/chat/AgentErrorDialogWiringTest.kt`（新增，6 例） | fork 独有：**源码扫描型接线锚定**（形态照 `TriggerServiceXposedNoticeWiringTest`）。⚠️ **存在理由**：纯函数单测再全也测不出「调用点有没有真的传参数」——本仓库已三次踩过（`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor` 零调用点）。⚠️⚠️ **必须剥注释**：本改动要在源码里加 `showErrorDialog` 字样，而注释/文档里到处都是，只做 `contains` 的话「把 `if` 删掉改回无条件调用」照样绿。故剥掉块注释/行注释后再断言，并加**防空转**断言。锁定：Agent 侧**恰好一处**传 `false`、执行器的 `if (showErrorDialog)` 与 `showError` 在**同一函数体内且顺序正确**、`executeWorkflowInternal` **调用点恰 3 处**（排除函数定义）+ 透传**恰 2 处**。⚠️ **反证已实际做过**：拆掉 `if` ⇒ 1 条变红；Agent 侧不传参数 ⇒ 2 条变红 | 我方 |
| `docs/fork/chat-streaming-design.md` | fork 独有：**Chat 流式输出设计** v5（含 §5.3 的 U1 更正、§6 的 19 条静默失效清单、§12 的 v3 撤回项），上游无此文件 | 我方 |
| `ui/chat/ChatCompletionClient.kt` | **v5 流式改造**：① `ChatProviderAdapter` 加 `stream()`（带默认实现退化）；② 新增公共 `streamReply()`；③ **`Accept` 头流式改 `text/event-stream`**；④ `buildChatCompletionPayload` / `buildResponsesPayload` 加 `streaming` 参数（后者**不再退化**，两套协议都走真流式）；⑤ Anthropic 的 payload 构造抽出 `buildMessagesPayload` 与 `buildHeaders`/`anthropicHeaders`（两条路径**共用**，因 `cache_control` 断点位置对缓存命中是关键）；⑥ `stripInlineToolMarkup`/`normalizeAssistantReply`/`firstNonBlank` **迁出**到 `ChatReplyNormalizer.kt`（净减 31 行）。⚠️ 该文件此前已认手动合并 | **手动合并** |
| `ui/chat/ChatViewModel.kt` | ⚠️ **行为语义变更**（`chat-streaming-design.md` §4.4/§4.7）：① 模型调用由 `generateReply`（一次性）改为 **`streamReply` 事件流 + 原位增长**（消息 id 从占位活到成品，不再 `replacePendingMessage` 换 id）；② 流式期间只写 `_uiState.update{}`、**不走 `updateUiStateAndPersist`**（避免每 delta 全量序列化落盘）；③ 收尾改走纯函数 `finalizeStreamingMessage`（`isPending` **从「靠整条被替换而消失」改为显式置 false**，这是本次最易漏的一处）；④ `stopAgent` 的 target 由 `activeConversationId` 改为 **`currentAgentConversationId`**（且在清空**之前**取出），并**保留**已生成文本（不再换成「已停止。」）；⑤ 新增 `finalizeOnInterruption` / `finishWithError`（失败时保留半截正文 + 错误作为**独立消息**追加）；⑥ 新增 `patchStreamingMessage`；⑦ **空内容兜底只在「无工具调用」时加**（与上游 `:906-909` 的两分支结构一致）——工具调用轮 `content` 本就为空是**正常情况**，无条件兜底会**错报**「模型返回了空内容。」 | **手动合并** |
| `ui/chat/ChatScreen.kt` | ⚠️ **行为语义变更**（滚动策略 + IME 布局，`chat-streaming-design.md` §4.6）：① 自动滚动的触发条件由 `messages.size` 改为 **`FollowSignal` 内容指纹**（`ChatMessagePatch.kt`，含 `lastContentLength`／`lastHasToolCalls`／`lastUserMessageId`）——流式改为原位增长后 id 恒定，只看 id/size 的旧判据**永不触发**（0.1 审批卡片被吞会复发）；② 「是否跟随」由**用户滚动结束时记录的意图**（`followTail`）决定，**不在内容到达时重算**；③ 用户手势用 `DragInteraction` 识别，抬手时按「位置是否变化」区分轻点与上翻；④ IME 触发贴底用 **`scroll { scrollBy(大值) }`**（`animateScrollToItem` 会被 effect 重启取消 ⇒ 不跟手；`requestScrollToItem` 只有「项顶部对齐视口顶部」语义 ⇒ 无法到底）；⑤ 三个底部 padding 由 `if (imeVisible)` 布尔改为**按 `padPx` 连续插值**（布尔会在某一帧跳变整个导航栏高度，与 `imePadding()` 的逐帧 inset 错位 ⇒ 回弹）；⑥ **移除**这三个 padding 的 `animateDpAsState`（上游默认 `spring()` 会过冲，且 `WindowInsets.ime` 本身已逐帧插值，再叠动画必然错位）；⑦ 「pending 且有内容」时渲染正文而非只显示「正在思考…」（否则流式全程 `isPending=true` ⇒ 一个字都看不见）；⑧ 「跳到底部」按钮的 `derivedStateOf` 去掉 `messages.size` key（流式下该值不变 ⇒ 可见性不更新） | **手动合并** |
| `app/build.gradle.kts` + `gradle/libs.versions.toml`（未动） | 新增 `com.squareup.okhttp3:okhttp-sse:4.12.0`（SSE 分帧，**不手写 `split("\n\n")`**）+ `mockwebserver:4.12.0`（testImplementation，**F18/F19 那些失败路径的唯一可测手段**）。⚠️ okhttp 系为**硬编码字面量**（`app/build.gradle.kts:237` 不走 catalog）⇒ 升级时**四处**要同改（okhttp/okhttp-sse/mockwebserver 三个坐标 + 同 minor 约束） | **手动合并** |
| `docs/fork/chat-float-window-ui.html` | fork 独有：Chat 悬浮窗可交互 UI 原型（折叠/展开/审批/输入/状态一致性五组演示），上游无此文件 | 我方 |
| `ui/workflow_list/WorkflowCompactGridSpec.kt`（新增） | fork 独有：**紧凑模式（瀑布流）的列数 / 密度 / 文件夹 Tab 筛选纯函数层**。列数按可用宽度分档 **3 / 4 / 5**、间距 12dp、内容内边距 12dp。⚠️ **不是「按目标单列宽反算」**：手机要 3 列 ⇒ 目标宽须落在 `(101, 134.7]`，展开要 5 列 ⇒ 须落在 `(162.3, 194.8]`，**无单值能同时满足** ⇒ 那条路只能靠「钳到 6」把展开压成 6 列，而那正是用户实测不符的地方。**分档不是偷懒 —— 这个映射本来就不是连续函数**。密度档按**单列宽度**（≥150/≥118/更窄）而非列数分级：展开 5 列的单列宽（约 165dp）比手机 3 列的（约 127dp）更宽，按列数分级会把大屏卡片错误缩小。另有 15 例单测，锁死两个真机锚点与「下限 3 不退化」「上限 5」「列数关于宽度单调不减」三条反向约束。上游无此文件 | 我方 |
| `ui/workflow_list/WorkflowFolderTabBar.kt`（新增） | fork 独有：**文件夹 Tab 栏**（替代原「文件夹卡片 + `FolderContentSheet` 底部弹窗」）。⚠️ 右端**不放假任何按钮**：上一版放过「新建文件夹」圆钮（与顶栏 `folder_create` 重复）与选中态下的 ⋮（用户反馈「不美观」），均已删除。文件夹的「重命名 / 导出 / 删除」改为**长按该 Tab** 唤出。⚠️ 长按检测**不能**用 `combinedClickable` / `detectTapGestures` —— 它们建的是消费型手势检测器，会把指针事件一并吃掉，里层 `FilterChip` 的 `onClick` 再也收不到（点 Tab 完全没反应）；这里用 `awaitEachGesture` + `withTimeout(longPressTimeout)` 自判，超时前抬手则原样返回让事件继续下传。⚠️ 菜单弹层挂在 Tab 栏**根 Box** 上，不在某个 Tab 内部 —— 否则会被 `horizontalScroll` 的裁剪区框住、靠右时被切一半 | 我方 |
| `ui/workflow_list/WorkflowListScreen.kt`（改，紧凑模式重做） | ① **紧凑模式整卡重做成瀑布流**（`WorkflowCompactCard`，替换原正方形 `WorkflowCardCompact`）：图标 / ⋮ / 状态控件不再分三行，**取消底部独立操作行**（省 48dp，是卡片能变矮的主因）；正文「名称 → 说明 → 徽标」；说明为空时**整行不渲染**（用户要求，不留占位）。**列表模式（`WorkflowCard`）一行未改**。② **⋮ 从卡片头行挪到右下角**（用户指出「卡片的右下角基本都是空的，放这里最好」），并压到 `onSurfaceVariant` 的 0.7 alpha 以免盖过徽标。③ **卡片背景由纯色改为竖直渐变**（`Brush.verticalGradient`；`Card` 的 `containerColor` 只收纯色，故容器设 `Color.Transparent`、渐变画在内层 `Column` 的 `background(brush, shape)` 上）。④ 卡片内边距 12/11/10 → **14/13/12**，段间距同步加大（对 ShortX 截图调）。⑤ 列数改由 `WorkflowCompactGridSpec` 推导（见上）。⑥ **两处拖拽排序的索引映射改为按 item key 反查**（原来用 `from.index - N` 固定偏移）：列表模式 N 因 Tab 栏可能显示而不再恒定，瀑布流更是多了搜索栏/Tab 栏这些 FullLine 项 —— 固定偏移会**搬错项且不报错**。⑦ `persistOrder()` 改为只落**当前 Tab 可见**的那些（`saveAllWorkflows` 是合并语义，安全）。⑧ 拖拽缩放由硬切改为 `animateFloatAsState` + `spring`；卡片条目用 `Modifier.animateItem()`（**不用 `animateContentSize`** —— 它会自己触发重组，见 `ChatMarkdown.kt` 记的同类坑）。⑨ 删除原 `WorkflowGridContent` / `FolderCard` / 文件夹弹窗接线 | **手动合并** |
| `ui/workflow_list/WorkflowListRoute.kt`（改） | 文件夹相关 action 收敛：删掉 `onOpenFolder` / `onCloseFolder` / `onMoveWorkflowOutOfFolder` / `onMoveWorkflowToFolderByDrop`（文件夹不再有「打开」与「拖进去」的交互，只靠 Tab 筛选 + 卡片菜单里的「移动到文件夹」），保留并接线 `onRenameFolder` / `onExportFolder` / `onDeleteFolder`（搬进 Tab 栏的长按菜单）。`loadData` 里同步「当前打开文件夹内容」的那段也一并删除 | **手动合并** |
| `ui/viewmodel/WorkflowListViewModel.kt`（改） | 删除 `WorkflowListUiState.openFolder` / `folderWorkflows` 两个字段与 `openFolder()` / `updateFolderWorkflows()` / `closeFolder()` 三个方法 —— 它们服务的「点文件夹卡片 → 弹底部弹窗」那条链路已整体作废 | **手动合并** |
| `ui/workflow_list/FolderContentSheet.kt`（**删除**） | 文件夹底部弹窗已由 Tab 栏取代，该文件无任何引用后删除 | **手动合并**（删除） |
| `core/workflow/WorkflowVisuals.kt`（改） | `CardColors` 追加 `cardBackgroundEnd`（带默认值 = `cardBackground`，故旧调用点行为不变）。新增的是**渐变底端色**（`blend(surface, base, 0.06)`，顶端是 0.18）—— 参考 ShortX：卡片不是纯色，顶带主题色、越往下越贴近 surface。**列表模式仍只用 `cardBackground`（单色），未动** | **手动合并**（追加字段 + 一处赋值） |
| 三语 `strings*.xml`（改） | 追加 `workflow_tab_all`（全部 / All / すべて）与 `workflow_item_menu_favorite`（收藏 / Favorite / お気に入り）。三语键名集合一致 | **手动合并**（追加条目） |
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
| `core/execution/JsExecutor.kt`、`core/execution/JsTimeout.kt`（新增） | **JS 引擎超时能力（指令级）**——`docs/fork/xposed-architecture-v2.md` §5.7 明列的**开工前置条件**（Xposed JS 模块会把同一引擎放进 system_server，崩溃半径 = 整机，不能在没有超时的引擎上叠）。① 新增 `JsTimeout.kt`（fork 独有文件，**零 Android 依赖**）—— `JsTimeoutContextFactory`（单例 `ContextFactory` 子类）+ `JsScriptTimeoutException` + **ThreadLocal 预算栈**。`execute()` 改 **6 处**（全在函数内）：签名加 `timeoutMs: Long? = null`；`Context.enter()` → `JsTimeoutContextFactory.enter()`；`evaluateString` 前 `beginBudget(context, timeoutMs)`；catch 链**最前**加 `catch (e: JsScriptTimeoutException) { throw e }`（**不包装**，否则会落到下面的 `catch(Exception)` 变成 `Execution failed: ...`，上层分不出「超时」与「脚本报错」）；`finally` 里 `endBudget(context)` **先于** `exit()`（`endBudget` 要读 `context.instructionObserverThreshold`，`exit()` 后 Context 已释放）。<br/>⚠️⚠️ **两个静默失效点**（都是实测，且都无任何报错）：**① 全局默认 factory 的 `observeInstructionCount` 是空实现**（字节码方法体只有 `return`）⇒ 用 `Context.enter()` 时**即便设了阈值也永不回调**（实测：死循环 16784ms 自然跑完 vs 自定义 factory 108ms 被中断）；**② 阈值 `<= 0` 会关闭观察器**（`setInstructionObserverThreshold` 内部调 `setGenerateObserverCount(N > 0)`）⇒ **忘设阈值即静默失效**（实测阈值 0 + 死循环 >3s 不中断）。<br/>⚠️ **嵌套执行是设计的主因**：脚本内调模块 → 模块参数含内联 `{% %}` → `InlineScriptEvaluator` 再建一个 `JsExecutor`。实测嵌套语义（决定了整个设计）：内层 `enter()` **返回同一个 Context** 且 **factory 保持为最外层那个**（内层想换 factory 会被**忽略** ⇒ 每实例一个 factory 只是幻觉隔离）；内层 `setInstructionObserverThreshold(0)` 会让**外层失去超时**。⇒ 故用**单例 factory + 预算栈取最紧 deadline + 阈值开关由「栈中有无活跃预算」决定**（不是由「本次调用有没有超时」决定）。<br/>⚠️⚠️ **限制（如实记录，不得美化成「脚本可中断」）**：指令级观察器**只在「执行下一条指令」时触发** ⇒ **只覆盖纯计算死循环**，对**阻塞的 Java 调用**（`Thread.sleep` / IO / 等锁）**无效**。实测 `while(true){ Thread.sleep(2000); }` + 800ms 预算 ⇒ **中断发生在 164680ms**（等阻塞自己返回）。<br/>⚠️ **与工作流级超时的关系**：`WorkflowExecutor` 的 `withTimeout` 是**协程级**取消、在挂起点生效，**无法打断同步死循环**；脚本死循环只有本机制能救，两者互补。<br/>**默认 `null` = 不超时**，故既有两个调用点（`JsModule`、内联 `{% %}`）**行为与改动前完全等价**（`hasActiveBudget()==false` ⇒ 不调 `setInstructionObserverThreshold` ⇒ 阈值保持 0，正是改动前的状态）；由单测 A10 机器化锁住。<br/>⚠️⚠️ **当前状态：引擎已支持超时，但没有任何生产路径会让它真正生效** —— 两个既有调用点都**不传** `timeoutMs`（走默认 `null` ⇒ 不设阈值、不产生 deadline），且**模块层刻意未暴露超时 UI**（本轮只做引擎层能力；给 `vflow.system.js` 加参数是一次**独立的行为变更** —— 存量里跑得久但正确的脚本升级后会开始失败，不该顺带做）。⚠️⚠️ **本机制当前【没有任何生产消费者】** —— 两个既有调用点都**不传** `timeoutMs`（走默认 `null` ⇒ 不设阈值、不产生 deadline）。<br/>⚠️ **它【不会】被 `vflow.xposed.js` 消费** —— 两者**零代码共享**（已逐文件核实）：App 侧走本文件的 `JsExecutor`，hook 侧走 `xposed/script/ScriptExecutor.kt`（在那份里，**同一个平台问题有它自己的一份解** `ScriptSandbox`，含 `arm()` 与单一 deadline）。hook 侧**从未 import 过本文件的任何符号**。⇒ 「把 App 侧引擎搬进 system_server」这件事**从来不存在**，`§5.7` 把它列为「开工前置条件」的论证前提**不成立**（该节已订正）。<br/>⇒ **本机制当前是「活代码、零生效路径」**。这正是本仓库反复踩过的形态（`CoreDexFingerprint` 的「13 个纯函数单测全绿但集成点缺失」、`XposedDiagnostics.messageFor` 的「写了但零生产调用点」）—— **勿以为超时已经对用户生效**。⚠️ 也**不要**为此加「必须有生产调用点」的测试：那在真有消费者之前**恒红**，而恒红的断言会被下一个实现者直接删掉。<br/>⚠️ **将来若要让它生效**，可选：给 `vflow.system.js` 加超时参数 UI（**独立的行为变更** —— 存量里跑得久但正确的脚本升级后会开始失败，需单独评估）| **手动合并**（`Context.enter()` / `Context.exit()` 是上游那两行；上游若新增 JS 调用路径需自行决定是否传参） |
| `test/core/execution/JsTimeoutTest.kt`（新增） | fork 独有：**13 例**，**全部用真实 Rhino（不 mock）**。⚠️ **测试方法体一律在独立线程里跑**（`join(上限)` + 事后 `interrupt()`）—— 观察器若因回归失效，在测试线程上跑会把整个 Gradle 进程**挂死**（表现为 CI 超时而非「测试失败」）；独立线程把「挂死」变成「断言失败」，副产物是 ThreadLocal 预算栈随之隔离。覆盖：死循环被打断、**阈值 0 = 静默失效的反向锁（断言「未被中断」）**、脚本层 `try/catch` 吞不掉（含循环体内的）、栈取最紧、**内层退出不得关掉外层观察器**、超时后无状态残留、反复超时 3 次、`null`/`0`/`-1` 不超时且**不开观察器**、**阻塞调用不可中断的诚实记录（断言「正常结束、不抛」）**、端到端异常未被包装。<br/>⚠️ **反证已实际做**：把 `observeInstructionCount` 改成空实现 ⇒ **A1/A3/A4/A5/A6 五条 + A12 端到端同时变红**，A2 反向锁与其余保持绿 —— 与预期完全一致。⚠️ 端到端用例需 `StubContext`（覆写 `getClassLoader`）：`ContextWrapper(null)` 会在 `execute()` 第一行抛 `Method getClassLoader ... not mocked`（实测） | 我方 |
| `test/core/execution/JsExecutorTimeoutWiringTest.kt`（新增） | fork 独有：**4 例源码扫描型接线锚定**（照仓库既有形态 `CoreDexFingerprintTest` / `CapabilityInvokerTest`）。⚠️ **存在理由**：`JsTimeoutTest` 虽是端到端的（强度更高），但**证明不了 `finally` 里的顺序** —— `endBudget(context)` 必须在 `exit()` 之前。⚠️ **这条盲区已实测确认**：把两行对调后，`JsTimeoutTest` 的 13 例**全绿**，只有本文件的排序断言变红 ⇒ 端到端对这个契约确实是盲的，只能靠源码扫描。锁定内容：① 用了 `JsTimeoutContextFactory.enter()` 且**无裸 `Context.enter()`**；② 调了 `beginBudget` / `endBudget`；③ **`endBudget` 在 `exit()` 之前**（按大括号配对截取 `finally` 块，不用正则 —— 块内有字符串与嵌套）；④ `JsScriptTimeoutException` 的前置 catch **排在** `RhinoException` / `Exception` 两个 catch 之前 | 我方 |
| `core/execution/LuaExecutor.kt`（**本轮未改动，记录备查**） | ⚠️ **对称短板，本轮有意未处理**：`LuaExecutor` 与 `JsExecutor` **签名完全对称**（同样是 `execute(script, inputs)`、同样无超时、同样无指令数/内存上限），故**同样存在「死循环永久挂住执行线程」的问题**。本轮范围只覆盖 JS（T3 的 Xposed 模块用 Rhino，不用 Lua），且当前**没有调用方需要 Lua 超时**。<br/>⚠️ **不要顺手改它** —— Lua 的超时机制与 Rhino 完全不同（需依赖 LuaJ 侧的 hook/中断点），不是同一套实现可以平移的。将来若要做，**应另开任务**并单独评估 LuaJ 的中断能力。此处留入口 | （暂未分歧） |
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
| `ui/chat/ChatAgentToolRegistry.kt`（改，**临时工作流输出不截断**，2026-10-04） | `buildTemporaryWorkflowToolDefinition` 加 `truncatable = false`（1 行 + 注释）；`ChatAgentToolDefinition.truncatable` 的 KDoc 由「两类工具必须声明 false」改为三类（补「执行结果」）。⚠️ **起因**：默认的 `CHAT_MAX_TOOL_RESULT_INPUT_CHARS = 1600` 截断**砍的是尾部**，而 `buildTemporaryWorkflowOutputText` 的正文顺序是「结论 → 失败摘要 → Steps 条数 → `Execution log`」⇒ 被砍掉的恰是含 `E/` 失败行与模块进度（如「正在延迟 2500ms」「已通过手势成功点击坐标: (542, 1793)」）的日志段 ⇒ 模型只看到 `failed at step N`、**拿不到任何可据以自愈的信息**（该工具的输出正是模型判断「要不要重试/怎么改」的唯一依据）。⚠️ **为什么「不截断」是安全的**：日志段自己有独立预算 `LOG_CHAR_LIMIT = 8000` ⇒ 有界，不会把无界 dump 灌进上下文。⚠️ **刻意不动 `truncatable` 的默认值**（仍为 `true`）：那个默认值是 `observe_ui` 那类「机器 dump、结构重复、长尾无信息量」输出的唯一防线，正确做法是**逐个显式豁免**而非动默认值（有反向锁） | **手动合并**（1 行 + KDoc） |
| `ui/chat/ChatMessagePatch.kt`（改，**Steps 段只给条数 + 日志段独立预算**，2026-10-04） | ① `buildTemporaryWorkflowOutputText` 的 `stepDescriptions: List<String>` 改为 `stepCount: Int`，Steps 段只打 `"Steps: 12"`、**不再逐条列清单**；`maxSteps = 30` 参数与分页逻辑删除；② 新增 `internal const val LOG_CHAR_LIMIT = 8_000`，日志段改走 `truncateMultiline(trimmedLog, logCharLimit)`（**独立预算**，默认取该常量）。<br/>⚠️⚠️ **为什么删清单**：清单每一项 `"延迟 (vflow.device.delay)"` 完全由 `moduleId` + 步骤 id 推得，而**这些正是模型上一轮自己写进 tool 参数的** ⇒ 回传它信息量为 **零**；而代价是 30 条约占 **870 字符**，叠在 1600 的整体预算里会把 `Execution log` **整段挤出去**。模型要判断「跑没跑起来、跑了几步」需要的是**条数**，要排错看的是日志里逐步骤的执行行（`[#3] -> 执行: 延迟`）。<br/>⚠️ **为什么日志预算从 4000 改 8000**：原来 4000 **从未生效过** —— 前两段 + 步骤清单已约 1100 字符，外层 1600 早把日志吃干净了。现在清单缩成一行、外层关闭，4000 才真正成为唯一预算，按真实崩溃日志的密度（92 行 / 6170 字符；**单是「步骤切换」行就有 37 行**）会不够，故放宽到 8000（约 120 行）。<br/>⚠️ **两段截断是分开的**：`LOG_CHAR_LIMIT` 只管日志这一段；`CHAT_MAX_TOOL_RESULT_INPUT_CHARS` 是整条输出的外层兜底（对本工具已关闭）。 | **手动合并**（签名 + Steps 段重写 + 新常量） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改，**同上**，2026-10-04） | `buildTemporaryWorkflowResult` 删掉 `stepDescriptions` 的构造（原为 `preparedSteps.map { "${title} (${moduleId})" }`），改传 `stepCount = workflow.preparedSteps.size` | **手动合并**（1 处参数） |
| `test/.../ui/chat/ChatTemporaryWorkflowTruncationTest.kt`（新增，3 例） | fork 独有：**源码扫描型**。⚠️ **存在理由**：`ChatAgentToolRegistry` 的构造要 `ModuleRegistry.initialize(appContext)`，纯 JVM 单测起不来；而手搓一个 `ChatAgentToolDefinition` 再断言它的字段等于**把要测的那句抄一遍**（改坏了也不会红，本仓库已两次踩过同形坑）。故扫源码，形态照 `AgentErrorDialogWiringTest`。三条断言：① 临时工作流工具块内**必须有** `truncatable = false`（含防空转：块内行数 > 10 且含 `CHAT_TEMPORARY_WORKFLOW_TOOL_NAME`）；② **反向锁** —— `truncatable` 的默认值必须仍是 `= true`（防有人图省事改默认值、让全体工具失去截断兜底）；③ **有界性锁** —— `LOG_CHAR_LIMIT = 8_000` 必须存在**且真的被接上**（`truncateMultiline(trimmedLog, logCharLimit)`）、Steps 段必须是 `append(stepCount)`、清单参数与分页逻辑必须已删。⚠️ **反证已实际做过**：删掉 `truncatable = false` ⇒ ① 变红；日志段改回不带预算的 `truncateMultiline(trimmedLog)` ⇒ ③ 变红。⚠️ **写它时踩到三个坑并已修正**：`maxLength` / `maxSteps` 是**参数默认值**（在函数体的 `{` **之前**），`SourceScan.functionBody` 取不到；`maxSteps` 在 `ChatMessagePatch.kt` 而非 `ChatAgentModuleExecutor.kt`；**裸测 `"more steps"` 会命中函数体里的英文散文** `"one or more steps failed and were skipped."` ⇒ 断言恒红，判据须针对**代码**（`stepDescriptions` / `maxSteps`）而非散文 | **我方** |
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
| `ui/workflow_editor/EditorMoreOptionsSheet.kt`（改，**修「最大执行时长超 120 秒即崩」**，2026-10-04） | 新增顶层纯函数 `maxExecutionTimeSliderUpperBound(valueSeconds, baseUpperBound, stepSize)`；绑定块内改为**先按实际值抬高 `valueTo`、再赋 `value`**。⚠️⚠️ **起因是一个必现崩溃**：`sheet_editor_more_options.xml` 把滑块写死 `valueFrom=0 / valueTo=120 / stepSize=5`，而 `Workflow.maxExecutionTime` 的写入方**都不受它约束** —— AI（`ChatAgentModuleExecutor.MAX_SAVED_WORKFLOW_MAX_SECONDS = 3600`）、远程 API（`api/handler/WorkflowHandler.kt:228` 无校验）、JSON 导入（`WorkflowJsonImportParser.kt:125` 无校验）⇒ AI 建的 300 秒工作流一开「更多选项」就抛 `IllegalStateException`。⚠️ **崩溃点是延迟的**：`BaseSlider.setValue` 只置 `dirtyConfig`，真正的校验在 `onSizeChanged`/`onDraw` 里（已 `javap` 核实 `validateValues` / `validateStepSize`），即「面板绑定完成、首次绘制才崩」，栈上看不到本文件。⚠️ **两条边界**：① 未超范围时**原样返回基准值**（无谓抬高会让普通工作流的 120 秒刻度被压成轨道上一小段，等于改掉既有产品行为）；② 抬高时**必须向上取整**到 `stepSize` 整数倍（截断会让上界落在实际值之下 ⇒ 越界异常原样复发；不取整会命中 `validateStepSize` 的 `valueLandsOnTick`）。⚠️ **刻意不改布局的上限**：提到 3600 会让 `stepSize=5` 出现 720 档、几乎没法拖。⚠️ 抬高 `valueTo` **只影响这一次面板会话**（视图属性，不落盘），不点「保存元数据」则磁盘值原样不变 | **手动合并**（绑定块 3 行 + 新顶层函数 + 1 个 import） |
| `test/.../ui/workflow_editor/MaxExecutionTimeSliderTest.kt`（新增，8 例） | fork 独有。4 例纯函数（范围内外、**向上取整的反向锁**、`valueTo` 必须落在 `stepSize` 格点、退化输入不除零）+ 4 例**源码扫描型接线锚定**（形态照 `CoreDexFingerprintTest`）。⚠️ **存在理由**：纯函数全绿也测不出「调用点没接上」—— 而本改动的失败模式正是那个（滑块照旧崩、测试全绿）。⚠️ 断言**剥注释**后再做，并断言上界/步长参数**取自视图而非硬编码**（硬编码后布局一改两处就静默脱节）。⚠️ **反证已实际做过**：删调用点 ⇒ 1 条变红；`ceil` 换成截断 ⇒ 1 条变红。⚠️ 另**撤掉了一条自己写错的断言**（曾断言「抬高必须排在赋值之前」）—— `BaseSlider` 的校验延迟到 `onDraw`，两种顺序都合法，实测调换顺序后测试照绿，说明那是条「看着更严、实则无据」的断言 | **我方** |
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
| `docs/fork/xposed-channel-design.md`（改） | ① 头部状态更新（v2.2「未实现」→ v3.0「第一层已实现」）；② **决策 11/16 改判**（`TriggerService` 的 `exported` **是必需的**、Core 的 `BIND_ADDRESS` **保留** —— 新增 §5.3.3 记录核实依据与风险定性）；③ 新增 §6 **实施进度对照表**；④ **#15 结案**（实测 `callerUid=1000` + `registerCallback` 成功；故 `android:permission` 防普通 App、token 防伪造上行，**两者不是二选一**）。⚠️⚠️ **2026-09-29 更正此处的机制**：原文写的「**uid < 10000 时签名权限检查豁免**」**是错的** —— 那是 `AppsFilterBase` 的**包可见性**规则，被误当成了权限规则。**真机实测**（`am startservice`，而 `startService`/`bindService` 在 AMS 走**同一个** `checkComponentPermission`，`adb shell` 就是 uid 2000）：uid 2000 → 受 signature 保护的 `HookChannelService` = **`Requires permission …HOOK_CONTROL`**（拒绝）；uid 2000 → 无该权限的 `TriggerService` = 成功（阳性对照）；uid 1000 → 同一 Service 在 `dumpsys` 里 `c:android` **bind 成功**。**⇒ 规则是 `uid == 1000`（及 0），安全性比原文描述的更好**。（另见 `docs/fork/xposed-architecture-v2.md` §3.3 / §8.2-2）；⑤ 记录官方框架 API 实测结果、**「为什么不给 system_server 内执行脚本开口子」**、以及**事件消费者单槽位缺陷**（加第二个 hook 触发器前必修） | **我方** |
| `docs/fork/xposed-executor-design.md`（新增） | fork 独有：**Xposed 执行器通道设计**（**第二条通道**，v1.0 设计阶段未实现）。现有通道是**采集器**（hook 层只报告、不决策、单向异步可丢弃），本文设计的是**执行器**（hook 层接代码、执行、返回结果）。**核心决策**：① **共享 Transport、独立语义层** —— `BinderTransport`（连接/重连/巡检）复用，但**不复用 `EventEnvelope`/`EventQueue`/`HookRuntime`**（含 `seq`/`dropped`，与请求-响应语义相反，共用会互相污染）；② **`executeScript` 必须同步**（不用 `oneway`）—— 沿用 `pushConditions` 的现成范式与同一条纪律「执行失败必须能被感知」；③ **不拆分 `HookChannelController` 单例** —— 一条 binder、一个 token、两套语义，靠信封类型区分而非连接分离（拆分会导致双倍连接管理/双 token/两套巡检）；④ **超时是硬要求**（三层：Rhino 指令级中断 / hook 层总时长 / App 侧 binder 超时），因为「卡住」在 system_server 里是占系统线程而非少采几条事件；⑤ 本期**不做沙箱**（用户即设备主人 + 入口只有一个模块），但记录「若来源扩展到下载/AI 生成则必须补」。**协议**：请求 `{request_id, protocol_version, script, timeout_ms, token}`、响应 `{request_id, ok, result_json, error, elapsed_ms}`。**引用面**（已核实）：`org.mozilla.javascript.*` **两张白名单表都不涉及、无需改动**（判定维度是「是否 Android 类 / 是否 App 侧包」，Rhino 两边都不沾）；但 `JsConsole` 在 `core.` 包下命中 `FORBIDDEN_APP_PACKAGES` ⇒ **必须在 `xposed/script/` 下移植一份**。**§7 给出决策规则**：只有需求落在「UID 1000 专属权限」或「同进程访问 system_server 对象」时才做本通道，其余「构造对象参数 / 读返回值」场景**扩展 `vflow.core.*` 成本低得多**（UID 2000 已能做，且崩溃半径是单进程而非整机）。上游无此文件 | 我方 |
| `docs/fork/xposed-capability-invocation-design.md`（新增） | fork 独有：**Xposed 通道 · App→hook 能力调用设计**（v1.0 设计阶段未实现）。**修正了 executor 文档的划分轴** —— 现有通道并非「单向推送」（核实 AIDL：`pushConditions`/`ping`/`registerCallback` 都是非 oneway 的同步往返），正确的轴是**三问**（谁发起 / 是否等结果 / 失败语义）；据此 **`pushConditions` 与「能力调用」同格**，它是后者的第 0 个成员。**核心划分：三种生命周期**（① 连接 / ② 订阅 / ③ 调用），其中 **③ 不存在**。**③ 与 ② 不能合并的理由是契约**（② 需要「全量替换 + 与连接同生命周期 + 连上必须重下发」，`invoke` 表达不了）—— ⚠️ **不是初版说的「与事件侧对称」**（那个理由是错的，评审已指出）。**③ 该走统一入口的依据**：AIDL 强类型在这里是**负资产**（`ShortcutInfo` 无法跨 binder 强类型化，必然退化为 JSON ⇒ 专用方法在写的那一刻就已丢了强类型）。**框架必须担三件事**：配对表有界、**⚠️ hook 侧 `invoke` 绝不能占 binder 线程**（初版遗漏；这是第 3~5 个能力时最先塌的点，后果是整机）、**未知 capability 必须显式报错**（与事件侧「未知 topic 忽略」方向相反）。**加新能力的真实代价是 2 处注册**（hook 层 + App 侧 capability 注册表），非初版说的 1 处。**⚠️ 实施顺序经评审调整**：「先探针、再抽象」——先用一个专用方法验掉快捷方式路径的**实现细节**（反射读 `ShortcutService` 的取法与内部字段名，**证据等级【推断】**；⚠️ **「必须走 hook」已由 survey §4.2 + AOSP `verifyCaller` 源码确证，不在待验之列** —— `verifyCaller` 唯一豁免是 `isCallerSystem()`（uid 1000），shell(2000) 跨包调 `getShortcuts` 必抛 `SecurityException`，且 AIDL 全部 24 个方法无一能跨包读 `ShortcutInfo` 对象），再从两个真实成员反推 ③ 的形状。**⚠️ 关键认知修正**：**③ 是数据源，不是目的** —— 快捷方式的正确落点是**给选择器换数据源**（`ShortcutPickerSupport`），**不是新增模块**（三条依据：survey §7.1 已因此否决新模块、仓库「控制 diff 面积」原则、**模块解决不了存量工作流**）。另建议 `ping()` 升级为 capability 自描述、版本号收敛为一处。上游无此文件 | 我方 |
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

### Xposed 通道**架构总纲 V2.0**（2026-09-28）

> ⚠️ **本条是文档层面的分歧，不是代码改动** —— V2.0 本身尚未产生任何代码改动。
> 但它是**后续所有 Xposed 改动的上位依据**，且它**重新划定了三份既有文档的地位**，
> 因此必须登记，否则下次读 `xposed-channel-design.md` 的人会以为那里的架构仍然生效。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `docs/fork/xposed-architecture-v2.md`（新增） | fork 独有：**Xposed 通道架构总纲 V2.0**。把「架构决策」从 2492 行的 `xposed-channel-design.md` 里剥离出来，只做**结构 / 契约 / 扩展规范 / 边界**，不含实测过程。**三组类型各有其轴**（生命周期按**状态归属**／执行环境按**能力需要**／暴露面按**用户可见性**）；**划分轴 = 状态归属**，把订阅侧的三条保证（全量替换 / 与连接同生命周期 / 连上必重下发）还原成「**hook 层持有 App 侧状态的镜像**」这一个根因。**统一入口的边界**：`pushConditions` 保留专用方法、③ 走 `invoke` 统一入口（理由是**契约要能被签名表达**，不是「对称」）。**③ 的传输形态定案 oneway + 配对响应**（`resolve` 加在 `IHookHost` 上）—— 澄清「失败可感知 ≠ 调用方原地阻塞」。**hook 点不做空闲卸载，改为规范早退 + 反射缓存**。**降级判据 = 能力有无替代实现**（不是生命周期属性）。**§7.4 反模式清单 10 条** + **§8 现有缺陷 20 条 + §8.1 修复清单 + §8.2 已验证结论**（均带 `file:line` 证据）。⚠️ **2026-09-28 两轮独立源码评审（不采信任何文档）后**：文档引用代码事实的准确度极高（§8 十三条、§5.4 行号图、28/25 全部成立），但**已实现的 ② 侧本身有 20 条缺陷**，其中两条属「写了调用点、实际没有调用点」形态（**丢弃计数链路未闭合**：`EventQueue.drainDropped()` 零生产调用者 ⇒ 计数永不清零、首页横幅永久驻留；**热更新后 hook 挂两遍**：`replaceHook` 返回值被丢弃 + `handle = null`）。另补：**连接建立路径落在 system_server 主线程**（`bindService` 未传 Handler）／**`ping()` 契约自相矛盾**（§3.1 为 `int`、§6.3 要能力清单）／**权限判据只取 L1 未合成 L0**（「框架在、通道断」被判已授权、无缺权限提示）／**`ActivityPayload` 预算按 char 计而契约按 byte 立**（CJK 下超 oneway 半缓冲 ⇒ 整条事件静默丢弃）。**§10 未决项扩到 20 条**。⚠️⭐ **2026-09-29 真机验证（小米 MIX Fold 3 / Android 17，纯 adb，无探针）**：三项「先验再改」**全部有结论** —— **① libxposed 重复 `hook()` = 链式叠加**（临时计数日志做同操作 A/B：N=5 → 20 次回调、N=6 → 24 次，比值 1.2 = 6/5）⇒ **缺陷 19 从「挂第二遍」升级为「N 倍放大且单调增长」**（实测 `旧 hook 句柄数` 1→2→3→4→5→6，**每热更新 +1、永不回收**；默认 1000ms 冷却一直掩盖着症状）；**② `signature` 权限能拦住 uid 2000**（见上条更正）；**③ `ServiceConnection` 回调确在 system_server 主线程**（`pid == tid`，含 `loadClass`+`hook()`，热态 ≈7ms）。另**新增 §8 缺陷 20**：**重连时重复 `bindService` + ConnectionRecord 只在增不减**（`scheduleReconnect` 里 `doBind()` 后立刻查异步的 `host`）。**测试用临时改动已全部还原**并重装干净版本。上游无此文件 | **我方** |
| `docs/fork/xposed-channel-design.md`（改） | 加**头部声明**：架构部分由 V2.0 取代，**实测记录与归因教训仍以本文为准**。保留不删（那些「连错两次、方向还相反」的记录是不可复现的资产） | **我方** |
| `docs/fork/xposed-executor-design.md`（改） | 加**头部声明**：⚠️ **框架已被 V2.0 取代** —— `execute_script` **不是「第二条通道」，是 ③ 的一个 capability**。逐条标注哪部分作废（独立 AIDL 方法 `executeScript`）、哪部分保留（三层超时 / 引用面约束 / `JsConsole` 移植 / §7 执行环境判据） | **我方** |
| `docs/fork/xposed-capability-invocation-design.md`（改） | 加**头部声明**：框架已被 V2.0 取代（三种生命周期是 V2.0 §2 的子集）。逐条标注推进/订正处，保留仍有效的部分（§6.2 快捷方式实现细节、§6.1 量化、§0.3 两处预埋缺陷的原始记录） | **我方** |
| `docs/fork/surveys/shortcut-system-overview.md`（改，**v1.5**） | ① **§5.5 补「路径 vs 实现方式」的区分** —— 起因是下游 `xposed-capability-invocation-design.md` §6.2 把「反射读 `ShortcutService`」误写成「四条之外的**第 5 条路径**」，实为 Xposed hook（第 4 条）的**一种实现方式**（另一种是 hook `requestPinItem`）；② 补 **AOSP 源码依据**否证「换身份到 shell 后直接 binder 取对象」：`ShortcutService.verifyCaller` 唯一豁免是 `isCallerSystem()`（uid 1000），shell(2000) 跨包调 `getShortcuts` 必抛 `SecurityException`，穷举 `IShortcutService.aidl` **24 个方法无一能跨包读出 `ShortcutInfo` 对象** ⇒ **「必须走 hook」不再是待验项**；③ 记录**一个反直觉点**：`cmd shortcut get-shortcuts` 能工作**不是 shell 有权限**，而是 `ShortcutManagerShellCommand` 跑在 system_server（uid 1000）里，这也解释了它为何与 `dumpsys` 输出逐字一致 | **我方** |

> ⚠️ **V2.0 的「草稿」状态与本条的关系**：V2.0 的**架构部分已定稿**（S1–S7 完成 + 一轮全面复核），
> 但 ③（能力调用）**尚无一行实现**，其实现级契约（配对表 / 结果大小 / 池满行为）刚补齐、未经真实代码验证。
> **故：② 订阅侧的改动可以按 V2.0 做；③ 的改动前先确认 §10 未决项里没有阻断项。**

---

### Xposed 通道**缺陷修复**（2026-09-29，**真机验证通过**）

> 两个文件都是 fork 新增文件（见上面 P1b 的条目），改动属**我方**，
> 但**必须登记** —— 它们改变了 hook 层与传输层的既有行为，
> 且 `BinderTransport` 此前已被本表记录过一次缺陷修复（P4 的缺陷①）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `app/src/main/java/.../xposed/wire/EventQueue.kt`、`xposed/HookRuntime.kt`（改，修 **缺陷 14**） | ⚠️⚠️ **丢弃计数链路从未闭合**：`EventQueue.drainDropped()` 是**唯一**清零入口，而它**零生产调用者** ⇒ 信封里的 `droppedCount` 恒为**累计值** ⇒ App 侧 `lastReportedDroppedCount` 恒 > 0 ⇒ 首页「已丢弃 N 条」横幅**永久驻留**、且此后每条事件都刷一条 warning（`HookRuntime.kt:250` 的注释**逐字**写着「清零由发送成功后的 `drainDropped` 负责」——注释描述的设计没错，**只是调用点没接上**）。<br/>**修法**：新增 `EventQueue.drainDroppedAtMost(reported)` —— ⚠️ **不能直接用 `drainDropped()`**：`emit()` 在**回调线程**、`drainLoop` 在**发送线程**，「装信封 → 发送 → 清零」之间存在窗口，直接 `drainDropped()` 会把窗口内新发生的丢弃**读走并抹掉**，而那正是本条设计的全部意义。改为按「**已上报的那个值**」精确扣除（`accumulateAndGet` 原子钳位）。<br/>另两处同批修正：**①** `emit` 里原先 `peekDropped()` **读了两次**（装信封一次、日志一次）⇒ 改为只读一次并记进信封；**②** 扣减值**从信封反解**而非另存一份（信封是唯一真实来源）。<br/>**新增 5 例**：3 例语义测试 + **2 例源码扫描型集成点测试**（照 `CoreDexFingerprintTest` 形态）—— ⚠️ **纯函数测试全绿恰是它当初没被发现的原因**。**反证**：真删调用点 ⇒ 两条守卫变红；退化成 `drainDropped()` ⇒ 两条语义测试变红。⚠️ 测试还抓出我自己的一个 bug（`coerceAtLeast` 只钳返回值、计数器已变负） | **我方** |
| `app/src/main/java/.../xposed/sources/ActivityChangedSource.kt`（改，修 **缺陷 7 / 8**） | **7**：`conditions.isEmpty` 早退提到 `chain.getArg(0)` **之前** —— 原实现把它排在**三次反射之后**（`forToken` 查找 + 调用 + `getDeclaredField`），于是「**没有任何触发器**」这个**最常见**的情形下，每次 Activity 切换仍白付 3 次反射；⚠️ `conditions` 必须是 `@Volatile`（已是）—— 早退读它、`applyConditions` 写它，普通字段会读到旧值 ⇒ 条件已下发却永远早退。**8**：全部反射查找走缓存，三个设计点 —— **缓存键带类**（`"${cls.name}#$name"`；只按名缓存会在「目标 App 进程的 hook」落地后**跨类复用 `Field`**）／**只缓存在实例字段**（热更新换 classloader 后静态字段全新）／**失败不缓存**（否则字段名改对了也会被旧结果挡住）。<br/>⭐ **实施中发现一个真问题**：写测试时 `readComponentClassName` 红了 —— 追下去发现 `getMethod` 只保证方法 public，**类本身可能非 public**（子类 / OEM 实现）⇒ `invoke` 抛 `IllegalAccessException` 被吞成空串。三处（`getClassName` / `toUri` / `getExtras`）都补了 `isAccessible = true`。<br/>**新增 7 例纯 JVM 测试**（`ActivityChangedSourceReflectionTest`，用假类覆盖缓存语义：命中幂等、**跨类不复用**、失败不缓存、缺字段不抛）；**反证**：去掉类键 ⇒ 两条变红。<br/>✅ **真机验证**：⚠️ 设备不在同一局域网 ⇒ 由用户手动装包，经**首页「最近日志」**（`DebugLogger` 内存缓冲的出口）确认**正常触发** —— **功能级**（证明没把路径改坏），**非定量级**（未数反射查找次数，因为无 adb；定量需 LSPosed 导出 verbose 日志，已判定为非必要） | **我方** |
| `core/xposed/HookChannelController.kt`、`services/HookChannelService.kt`、`xposed/BinderTransport.kt`、`xposed/VFlowHookEntry.kt`、`.../ActivityChangedTriggerHandler.kt`（改，修 **缺陷 1 / 3 / 12 / 13 / 18**） | ⚠️⚠️ **修的是 ③（能力调用）的前置地基**（§8.3 判定的 A 组）。**13**：`onUnbind`/`onDestroy` 原先**无条件**清 `callback` + `token`，而**断开是异步投递的** —— 实测旧连接的 `unbindService` 比新连接建立**早约 1.1 秒**且由 `ManagedServices$1.onBindingDied` 触发，一旦投递落到新 `registerCallback` 之后，就会把**刚建立的新连接**清掉（hook 层自认连着、App 侧 `callback == null` ⇒ **事件全丢**）。这是部署流程里的常态时序。改为 `onCallbackUnregistered(which)` 带身份，**不是同一个 binder 就忽略并留日志**（⚠️ 比较比 `asBinder()`，`asInterface` 对同一 binder 可能返回新代理）。**3**：`deathRecipient` 声明了却从未 `linkToDeath`（死字段）—— 接上它，③ 才能在**断开瞬间**唤醒 waiter（而不是等 15 秒巡检）。⚠️ **先解回执再 unbind**，否则 `host` 已是 null、拿不到 binder；⚠️ 挂新回执前先解旧的，避免「只增不减」。**1 / 12**：`HookChannelController.onConnected` 与 `BinderTransport.onConnectedSink` **两处单槽位**改**按 key 的注册表**（加第二个 hook 消费者时不会再互相挤掉）；`setOnConnectedListener(null)` → `removeOnConnectedListener(key)`（前者在注册表下**会误伤别人**）。**18**：`HookChannelControllerTest` 三处**空断言**补真断言。✅ 验证：单测 16 例全绿 + **6 条断言逐条反证全部变红**；真机确认 `deathRecipient` 即时触发、重连仍 **1 次 bind** | **我方** |
| `app/src/main/java/.../xposed/sources/ActivityChangedSource.kt`（改，修 **缺陷 19**） | ⚠️⚠️ **热更新后 hook 累积、每代 +1、永不回收**。`remountAfterHotReload` 原先对**每个**旧句柄都 `replaceHook()`、**丢弃返回值**并置 `handle = null` —— 而那个返回值**就是本代际该持有的句柄**（已 `javap` 读 aar 确认签名是 `(Hooker) → HookHandle`）。`handle = null` 使下一轮 `remountSources()` 的 `unmount()` 变 **no-op**、`mount()` 的幂等守卫同时失效 ⇒ 多挂一个，且旧代际句柄再也拿不到。**实测已累积到 7 个**（`旧 hook 句柄数` 1→2→3→4→5→6）；而 libxposed 的 `hook()` 是**链式叠加**（实测 N=5 → 20 次回调、N=6 → 24 次，严格按 N 倍）⇒ **每次 Activity 切换跑 N 遍**，默认 1000ms 冷却一直掩盖着症状。**修法**：只接手 1 个（返回值存进 `handle`）、**其余全部 `unhook()`** —— 既治根因，也**自我修复历史累积**。✅ 实测 7 → 保留 1 + 清 6；其后连续 5 次热更新稳定 = 1。 | **我方** |
| `app/src/main/java/.../xposed/BinderTransport.kt`（改，修 **缺陷 20**） | ⚠️⚠️ **重连时重复 `bindService` + `ConnectionRecord` 只增不减**。**(a)** `scheduleReconnect` 在 `doBind()` 之后**立刻**查 `host != null`，而 `host` 由 `onServiceConnected` 在 system_server 主线程上**异步**设置 ⇒ 循环会再 bind 一次（实测每轮 **2 次**）。修法：`doBind()` 改为返回 `Boolean`，新增 `awaitConnected(timeoutMs)`（有界轮询，步长 `AWAIT_STEP_MS = 100ms`），**提交后等 `host` 落地再判成败**。**(b)** ⚠️ **只修 (a) 不够** —— 实测 `ConnectionRecord` 仍在 **+1/轮**（distinct 7→8→9），因为 `unbindQuietly()` 只在 `stop()` 里调 ⇒ **重新 bind 前先 `unbindQuietly()`**。✅ 两半都修：每轮提交 **2 → 1**；`ConnectionRecord` 连续 3 轮断连 **6 → 6 → 6 → 6 持平**；未引入额外延迟（约 200ms 完成重连）。 | **我方** |

> ⚠️ **上表两个缺陷的详细论证与实测数据在 `docs/fork/xposed-architecture-v2.md` §8（缺陷 19/20）与 §8.1（修复清单）。**
> 该文档另列 **18 条未修缺陷**，每条都有最小改法。

### Xposed 通道 ③（能力调用）**契约层**（2026-09-29，已合入 `dev`）

> 这是 `docs/fork/xposed-architecture-v2.md` 里 ③ 的**第一段落地**（原计划 6 个任务里的 task1）。
> 背景：曾用 mindfs「蓝图」模板把 ③ 拆成 6 个串行任务编排执行，
> **只有 task1 真正交付**（靠父会话手动验收 + 合并），task2–6 未完成。
> ⇒ **本段登记的是 task1 的成果；③ 的其余部分（App 侧运行时 / hook 侧运行时 / 首个 capability / 可见状态）尚未实现。**

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `app/src/main/aidl/.../xposed/IHookCallback.aidl`（改） | 新增 `String capabilities()` 与 `oneway void invoke(String requestJson)`；**`int ping()` 保持不动**。⚠️ `invoke` 必须是 **`oneway void`** —— 文档 §3.1 写的是 `String invoke(...)`（非 oneway），与 §3.4 的「不占 binder 线程」硬约束**互斥**（AIDL 的 oneway 不允许返回值）；**以 §3.4 为准**，推导已写进 AIDL 的 KDoc | **我方** |
| `app/src/main/aidl/.../xposed/IHookHost.aidl`（改） | 新增 `oneway void resolve(String responseJson)` —— ③ 的应答通道。⚠️ 与 `report` 同理是 oneway（hook 层可能在 system_server 任何线程上调，不能阻塞等待） | **我方** |
| `app/src/main/java/.../xposed/wire/CapabilityInvocation.kt`（新增） | 调用信封 codec + `CapabilityRequest` / `CapabilityResponse` / `CapabilityError`。⚠️ **绝不复用 `EventEnvelope`**（含 `seq`/`dropped`，与请求-响应语义相反） | **我方** |
| `app/src/main/java/.../xposed/wire/CapabilityErrorCode.kt`（新增） | §6.4 的**五值枚举**（`capability_absent`/`timeout`/`handler_error`/`channel_down`/`payload_too_large`）+ 「该码对应什么用户动作」的纯函数。⚠️ 两条硬约束写进注释：每行映射到**恰好一个**码；`detail` **绝不参与判断**（会被三语本地化） | **我方** |
| `app/src/main/java/.../xposed/wire/CapabilityManifest.kt`（新增） | `capabilities()` 返回清单的 codec | **我方** |
| `app/src/main/java/.../xposed/wire/ResultBudget.kt`（新增） | §3.6 大小契约原语（**按字节**收 + 截断标志 + cursor）。⚠️ 单元素超限仍收下（否则分页死循环） | **我方** |
| `app/src/main/java/.../xposed/capability/{Capability,CapabilityRegistry,CapabilityNames,CapabilityPresence}.kt`（新增） | 能力注册表与 `CapabilityPresence`（`UNKNOWN`/`ABSENT`/`READY`）。⚠️ **与既有 `core/xposed/XposedCapability.kt`（那是权限判据）语义无关**，不得混用；⚠️ **物理位置不进 `XposedState`**（§6.1 明说不新造第三组状态位）；`CapabilityRisk` 自建枚举而非复用 `AiModuleRiskLevel`（后者会命中 `WireLayerPurityTest` 的引用面扫描） | **我方** |
| `app/src/main/java/.../xposed/wire/ActivityPayload.kt`（改，**byte 预算修正**） | ⚠️⚠️ **原预算按【字符】计而契约按【字节】立**：`MAX_INTENT_URI_CHARS = 64K` + `MAX_EXTRAS_JSON_CHARS = 128K`（`.length` 是字符数）⇒ 全 CJK 时 192K 字符 ≈ **576 KiB > oneway 半缓冲（≈508 KiB）⇒ 整条事件静默丢弃**（表现是「打开某些 App 不触发、换一个就正常」）。改为 `MAX_INTENT_URI_BYTES = 16 KiB` / `MAX_EXTRAS_JSON_BYTES = 48 KiB` 并按字节截断。<br/>⚠️⚠️ **且 `truncateToBytes` 必须按「码点」推进**（`codePointAt` + `Character.charCount`）—— 初版按 `Char` 遍历 + `byteSizeOf(ch.toString())`，而**单个代理 Char 编码成 UTF-8 只有 1 字节**（孤立代理退化成替换符、不抛异常）⇒ 一个 4 字节 emoji 被算成 `1+1=2`，**预算低估一半**。实测 `limit=16384` 时**实际 32768 字节 = 2 倍上限**，即**截断完全没生效**（静默）。<br/>⚠️ **测试载荷也必须用 emoji 而非「中」** —— 「中」在 BMP 内只占一个 `Char`，**恰好绕过该 bug**（改回旧实现时测试仍全绿）。已改为 emoji + 补「字节数必须落在上限内」的断言 | **我方** |
| `app/src/main/java/.../core/xposed/HookChannelController.kt`（改） | **配对表**（`registerWaiter` / `unregisterWaiter` / `bucketKey` 按连接代次分桶 / `MAX_WAITERS = 64` 硬闸）**+ 断连唤醒**（`failAllWaiters`）+ 迟到响应丢弃 + `resolve` 的 token 鉴权（照 `onReport` 三段：本侧无 token 直拒 / **恒定时间比较** / 失败只记日志不抛）。⚠️ 注释明确写了「**超时丢弃与 await 语义属于下一段**」（当前尚无调用入口） | **我方**（该文件此前已认手动合并） |
| `app/src/main/java/.../services/HookChannelService.kt`（改） | 接线 `resolve` 入口 | **我方** |
| `app/src/main/java/.../xposed/BinderTransport.kt`（改） | ③ 所需的非实现能力应答（`respondUnimplemented` 回 `capability_absent` 且带回 `req.token`） | **我方** |
| 测试（新增 6 个 + 改 3 个） | `CapabilityInvocationCodecTest` / `CapabilityErrorCodeTest` / `CapabilityManifestTest` / `ResultBudgetTest` / `CapabilityRegistryTest` / `CapabilityContractPurityTest`（源码扫描：capability 包不引用 App 侧、`CapabilityPresence` 不在 `XposedState`、AIDL 形状锁定）+ `ActivityPayloadTest`（byte 口径与 emoji 反向断言）/ `HookChannelControllerTest` / `WireLayerPurityTest`（白名单登记） | **我方** |

> ⚠️ **③ 尚未实现的部分**（后续如需继续）：
> hook 侧执行运行时（**自建有界工作线程池** + `invoke` 分发 + 三层超时 + 引用面约束）、
> 首个 capability（`query_shortcut_intents` + 快捷方式选择器换数据源）、
> `capabilityPresence` 的 UI 与 §6.1「已授权但通道断」可见状态。
> ✅ **App 侧调用运行时已于 2026-09-30 落地**（见下一段）；
> ✅ 分页三键的半闭环**已补齐**（同见下一段）。

### Xposed 通道 ③（能力调用）**App 侧调用运行时**（2026-09-30）

> ③ 的第二段落地。让 App 能「**发起一次能力调用、拿到结果、失败时按能力类型降级**」。
> 设计依据：`docs/fork/xposed-architecture-v2.md` §3.4（传输形态）/ §5.2（超时与断连）/
> §6.2（降级判据）/ §6.3（presence 只避免白试）/ §3.6（结果大小契约）。
> ⚠️ **hook 侧执行运行时（T2）与首个 capability（T3）仍**未实现**** ——
> `BinderTransport.onInvoke` 的占位（回 `capability_absent`）**刻意保留**，
> `CapabilityFallbacks.registerAll()` 是**空实现**（首个能力由 T3 注册）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `xposed/capability/CapabilityInvokeOutcome.kt`（新增） | fork 独有：③ 一次调用的**结果类型**（App 侧调用方的返回面）。**密封三态** `Success` / `Failed` / `Degraded` + `CapabilityFailure`（`code` + `detail` + **派生**的 `userAction`）+ `CapabilityFallbackPlan`（`NativeCode` / `Unavailable`）。⚠️⚠️ **为什么必须密封而非抛异常**：替换型能力静默降级这个**中间态**在 `try/catch` 模型里**无处安放**，而它必须留痕（否则「昨天能用今天不能用」会被当成回归去查，真实原因是 Xposed 掉线）。⚠️ **纯数据层**（不引 coroutines、不引 App 侧包）—— 放在 `xposed/capability/` 与 `Capability` 同处，仍受 `CapabilityContractPurityTest` 白名单管辖；真正需要协程的实现落在 `core/xposed/` | 我方 |
| `core/xposed/CapabilityInvoker.kt`（新增） | fork 独有：③ 的**单一调用入口**。`suspend invoke(capability, params, timeoutMs)`（查表 → 判 presence → 连接闸 → registerWaiter → **代次复查** → oneway 提交 → `withTimeout` await → 分类）+ `invokeOrFallback`（按**运行时结果**分派降级）+ `buildRequestJson` / `classifyResponse` 两个纯函数 + `Hooks` 接口（与 Controller 的**唯一**耦合点，抽出来是为了让「注册前/后断连」两个方向可单测）。⚠️ **落点在 `core/xposed/` 而非 `xposed/capability/`** —— 后者在 `xposed/` 下、import 白名单不含 `kotlinx.coroutines.`，而本文件要 `suspend`/`withTimeout`。⚠️⚠️ **步骤 3.5 的「代次复查」堵的是 waiter 泄漏**：`failAllWaiters` 只唤醒**已注册**的 waiter，而「断连恰好落在注册前后之间」会让那条 waiter **永久残留** ⇒ `MAX_WAITERS = 64` 满后**所有**调用都回 `handler_error`（表现是「功能突然全坏了」）。选了纯读的代次快照对比，而不是在 `onCallbackUnregistered` 里挂钩子（那个函数**没有对外注册接口**，它由 `HookChannelService` 调用；现造一个只有单一消费者的注册表是 §7.4 反模式 1）。⚠️ **`params` 必须走 `JSONObject(params).toString()`** —— Kotlin `Map.toString()` 产出 `{obj={k=v}}` 是**非法 JSON**（已实测），hook 侧解析必抛。⚠️ KDoc 写死「**不得在主线程调用**」（它最终是一次同步 binder 事务到 system_server），且**刻意不做线程切换** —— 加了 `withContext` 会让主线程调用「看起来能用」 | 我方 |
| `core/xposed/CapabilityPresenceHolder.kt`（新增） | fork 独有：**连接期能力交换**结果的持有者（`StateFlow<CapabilityPresence>` + `start()`/`stop()`）。⚠️ **探测触发点是「连接建立」的推送**（`setOnConnectedListener`），**不是 Application 生命周期钩子** —— `HookChannelService` 与 hook 层**谁先启动是不确定的**，挂在生命周期上就要求本类先于连接启动，而那个顺序没有东西保证。⚠️⚠️ **回退开关必须是「运行时判定」**：`onConnectNotified()` 里**每次自检** `CapabilityRuntime.isEnabled()`。写这个自检的理由是一个具体的静默劣化模式 —— 若只在 `start()` 时判一次，开关关着时**不注册监听** ⇒ 那一次连接建立被永久错过（`notifyOnConnected` 是推送的、**不重放**）⇒ presence 停在 `UNKNOWN` ⇒ 调用侧每次白等满 5 秒，而唯一的恢复办法是重启 App。⚠️ 探测**不在 binder 线程原地做**（`onCallbackRegistered` 由 binder 线程调用，而 `ping()`/`capabilities()` 是同步 binder 往返）⇒ 起裸线程（仓库既有做法，`BinderTransport` 同款）。⚠️ `resetIfDisconnected()` 由 `HookChannelController.onCallbackUnregistered` 在**所有早退分支之前**调 —— 方向是 `PresenceHolder → Controller`，与既有的 `ActivityChangedTriggerHandler → Controller` 同向；反过来的直写会让 Controller 单向依赖上层模块 ⇒ 成环 | 我方 |
| `core/xposed/CapabilityExchange.kt`（新增） | fork 独有：能力交换的**判据层**（`ManifestProbe` fun interface + `exchangeOnConnect` / `presenceOnDisconnect` 两个函数）。抽接口是为了让「清单为空」「方法不存在（抛）」「连接断了（抛）」三种情形可纯 JVM 分开测 —— 它们的**用户处置完全不同**（② 指向「升级/重启 App」，③ 指向「等重连」）。⚠️ **判据顺序是定案的**：`ping()` 是**第一判据**（最老、必然存在），`capabilities()` **本身不存在**时不能当第一判据（那时收不到任何东西，无法区分「方法不存在」与「连不上」）。⚠️ **「方法不存在」在 AIDL 层面就是抛异常，不是返回空串** —— 必须 `try/catch`；把「空清单」误判成 `ABSENT` 会把用户引去「升级 App」，而 App 其实是最新的 | 我方 |
| `core/xposed/CapabilityFallbacks.kt`（新增） | fork 独有：**降级实现的注册处**（在 `xposed/` **之外**）。⚠️ 存在的理由是 `CapabilityRegistry.kt` 类注释写死的那条分层：`fallback` 是函数类型（纯 Kotlin），但**它的实现必然引用 App 侧代码**（如快捷方式的降级路径是 dumpsys，实现体在 `ui/shortcut_picker/`），而 `capability/` 包在 `xposed/` 下 ⇒ **装 lambda 的注册动作必须发生在 `xposed/` 之外**。⚠️ **T1 阶段是空实现**（首个 capability 是 T3 的交付物）—— 往这里塞「测试用假能力」会让它进 **release 的生产启动路径**。单测直接调 `CapabilityRegistry.register(...)` + `@After` reset | 我方 |
| `core/xposed/CapabilityRuntime.kt`（新增） | fork 独有：③ 的**启动接线点**（`attach(context)`）+ `isEnabled()` 回退开关。⚠️⚠️ **默认 `true`**（父会话 2026-09-30 定案）：T1 加的是**同一条连接建立路径上的第三个监听器**（前两个已存在且更重：`VFlowHookEntry` 的 `remountSources` 做 `loadClass`+`hook()`、`ActivityChangedTriggerHandler` 做**同步** `pushConditions`），新增成本只是「一次 `ping()` + 一次 `capabilities()` 两次 binder 往返」，**不是**「新增一条启动期跨进程路径」。取 `false` 的那个失败模式见 `CapabilityPresenceHolder` 条目。⚠️ **接线无条件做，开关只控制「探测」** —— 初版在 `attach` 里 `if (!enabled.get()) return`，那会让「关掉再打开」永远不生效（除非重启 App），由 `re-enabling the runtime takes effect on the next connection` 一例抓出；开关管的是「要不要真的去 ping/capabilities」 | 我方 |
| `core/xposed/HookChannelController.kt`（改） | 追加四个**只读 / 可注销**访问器：`callbackOrNull()` / `currentToken()` / `disconnectGeneration()` / `setOnDisconnectedListener` + `removeOnDisconnectedListener` + `notifyOnDisconnected()`；`onCallbackUnregistered` 开头加一行 `CapabilityPresenceHolder.resetIfDisconnected()`、`failAllWaiters` 后加 `notifyOnDisconnected()`。⚠️ 加访问器而不让调用方自建连接管理：`callback`/`token` 是 private，③ 要提交请求与写鉴权信封；另造一套连接管理是「双份连接管理 + 双 token」（`FORK.md` 记过这类双份的代价）。⚠️ **`setOnDisconnectedListener` 与 `setOnConnectedListener` 是两个方向**，**刻意不合并** —— 前者由 `onCallbackRegistered` 触发、后者由 `onCallbackUnregistered` 触发，合并会让「连上时要做的事」与「断开时要清理的事」挤在一个槽里。形态与既有的 `eventSinks` / `onConnectedListeners` 完全一致（`ConcurrentHashMap` + key、逐个 `try/catch`）—— 本类已有三处单槽位缺陷的教训，不新增第四处 | **手动合并**（该文件此前已认手动合并） |
| `xposed/wire/CapabilityInvocation.kt`（改，**只加不改**） | 补齐分页三键的字段与编解码：`CapabilityRequest.cursor` + `CapabilityResponse.nextCursor` / `truncated`，四个函数（`encodeRequest`/`encodeResponse`/`decodeRequest`/`decodeResponse`）对称补齐。⚠️ **三个 key 字符串一个字都没改**（跨进程协议），⚠️ **既有参数的相对顺序与名字一个都不动**，只插入带默认值的新参数。⚠️ 缺省值向后兼容：读不到键 ⇒ `null`/`null`/`false`；⚠️ **不写键的编码路径保留**（`cursor` 空白 / `truncated=false` 时都不写键）—— 「不存在的键」比「值为 null 的键」更明确，且旧端不需要认识它。⚠️ `nextCursor` **原样回传字符串、不在信封层解析成 Int**（契约里它只是「一个不透明游标」，用 `Int` 会把「将来换非整数游标」变成协议变更） | **我方** |
| `VFlowApplication.kt`（改） | 加 **1 行** `CapabilityRuntime.attach(applicationContext)`（放在既有的 `XposedFrameworkMonitor.start(...)` 之后）。⚠️ 它是本仓库「启动期改动」的敏感文件（三次「模块不加载」的历史，真因未查明、已被后续探针反证并非平台限制）⇒ **只加一行、不改既有顺序**，并由 `CapabilityRuntimeWiringTest` 的源码扫描锁住调用点真的存在（防反模式 6「写了调用点注释但没有调用点」） | **手动合并**（新增 1 行） |
| 测试（新增 5 个 + 改 3 个） | `CapabilityInvokerTest`（24 例）/ `CapabilityPresenceHolderTest`（16 例）/ `CapabilityRuntimeWiringTest`（6 例，源码扫描锁调用点）/ `CapabilityInvokeOutcomeTest`（9 例）/ `FakeHookCallback`（手写假 `IHookCallback`，**不走 `Stub`** —— 它继承 `android.os.Binder`，纯 JVM 里构造不出来）；`HookChannelControllerTest` +7 例、`CapabilityInvocationCodecTest` +6 例、`CapabilityContractPurityTest` **翻面**（见下） | 我方 | 
| `test/.../xposed/CapabilityContractPurityTest.kt`（改） | 那条 `capability package still has no production call sites` **翻成正面断言** `capability package has production call sites`。⚠️⚠️ **不是删掉，而是翻面** —— 那条断言的注释自己就要求「接入后改成正面断言」。`CoreDexFingerprint` 那次（13 个纯函数单测全绿、集成点缺失 ⇒ 用户静默跑旧 Core 代码）证明这道防线必须留着。翻面后强度**不比原来弱**：原来断言「零引用」（否命题，容易被无关引用满足），现在断言**具体哪几个符号有引用**（正命题，缺一个就红）。⚠️ 表里**只列 T1 已接上的三个符号**（`CapabilityRegistry` / `CapabilityPresence` / `presenceAfter`）；`CapabilityNames` **刻意不在表里** —— 它唯一的成员是 T3 首个能力的名字，现在**确实**没有生产消费者（预期状态），列进来会让断言在 T1 恒红，而恒红的断言会被下一个实现者直接删掉 | 我方 |

> ✅ **本批两条已闭合的旧缺口**：
> ① 上面「契约层」段末尾记的「`KEY_CURSOR`/`KEY_NEXT_CURSOR`/`KEY_TRUNCATED` 三键已定义但无字段与编解码路径」（分页半闭环）—— **已补齐**；
> ② 同段记的「`HookChannelController` 注释写着『超时丢弃与 await 语义属于下一段』（尚无调用入口）」—— **调用入口已落地**。
>
> ⚠️ **真机验证状态**（2026-09-30）：**未做**。方案 §9.4 父会话定案只做第 ① 项（能力交换真的发生），
> 且取证需要设备在手（本机与设备不在同一局域网，无法 adb）；本批**只到「编译 + 单测 + release 打包」这一层**。
> 故「T1 已实现」目前**只有编译与单测支撑**，尚无真机证据。

---

### Xposed 通道 ③（能力调用）· **hook 侧执行运行时**（2026-09-30，已合入本分支）

> 这是 ③ 的**第二段落地**（原 6 任务编排里的 task2）。契约层见上一段。
> ⚠️ **与 T1（App 侧运行时）的 `CapabilityInvocation.kt` 补齐是同一批的跨 worktree 改动** ——
> 分页三键的编解码路径由 T1 实现，本段**只使用**（见下表的说明）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `app/.../xposed/capabilities/`（`CapabilityHandler.kt` + `InvokePolicy.kt` + `HookCapabilityRegistry.kt` + `DiagnosticCapabilityHandler.kt` + `HookCapabilityRuntime.kt`，均新增） | fork 独有：③ 的 **hook 侧执行运行时**。binder 线程只做「解码+校验+查表+投递」；**自建有界线程池**（`ThreadPoolExecutor` + `SynchronousQueue`，容量 2，**不排队**）——池满立刻回 `handler_error`（口径见 `CapabilityErrorCode.kt:68-87`，**不新增第六个码**），`detail` 写明「工作线程池已满」；⚠️ **拒绝的两种成因必须分开**（池满 vs 运行时已停，否则 `detail` 指向错误排查方向）。超时为**事后判定**（非脚本 handler 不可中断，故不做看门狗、不做强制抢答；**一个永久卡住的 handler 永远不产生响应**，而那个工作线程**永久被占用** —— 这是「池有界 + 总时长上限」的兜底方式，也是容量必须小的原因）。截断走 `ResultBudget.collectWithin`（**产出阶段**，不是序列化后）；**`truncated` / `next_cursor` 走信封顶层**（`encodeResponse` 的两个新参数，T1 已落地）—— ⚠️ **不是塞进 `result` 内部**：T1 的 `Success.nextCursor`/`.truncated` 从 `CapabilityResponse` 读，塞进 `result` 会让两字段**永远填不上、恒为缺省值**。`result` 只装 `{"items":[…元素对象…]}`。⚠️ **本任务不做自动翻页**（`next_cursor` 一旦非空就交给调用方）。工作线程顶层 `try/catch(Throwable)` —— 在 system_server 里异常逃逸 = 整机。⚠️ 与 App 侧 `xposed/capability/`（单数）**只差一个 s**，两者是**跨进程的两份**，不是拷贝 | 我方 |
| `app/.../xposed/capabilities/HookCapabilityRuntime.kt`（同上一行，**单独登记两处已实测的口径修正**） | ① ⚠️⚠️ **余量必须是「随预算缩放」的**：`RESULT_ENVELOPE_MARGIN_BYTES = 4 KiB` 是**绝对字节数**，而 `Capability.maxResultBytes` 可以是任意正值 ⇒ 一个把上限声明成 512 字节的 capability，`上限 − 余量` 变负、**永远收不下任何元素**。已改为 `min(4 KiB, 预算 / 8)`（大预算时仍是那条实测标定的 4 KiB）。<br/>② ⚠️⚠️ **发送前的最终校验必须拿 [maxBytes] 比，不能拿 `elementBudget` 比**：`collectWithin` 会一直收到「再加一项就超 `elementBudget`」为止 ⇒ 收下的部分**已把 `elementBudget` 用满**，而序列化还要额外付 `(项数−1)` 个逗号 + 信封固定键 ⇒ 与 `elementBudget` 比会**必然判超限**，`huge` 会回 `payload_too_large` 而**不是**「截断成功」。余量的真实作用是「预留出逗号与转义占的那几百字节，使结果仍落在 `maxBytes` 内」。⚠️ 这两处**都是实测（单测）抓出来的**，纯代码审查看不出。<br/>⚠️⚠️ **本行 ② 的判据已于 2026-10-01 被真机实测推翻并取代**（见下方该日的新登记行）——「拿 `maxBytes` 比」这个结论**只在一个口径内成立**；真正的问题是**口径本身选错了**（量的是 `result` 字段而非发出去的信封）。 | 我方 |
| `app/.../xposed/capability/CapabilityNames.kt`（改） | 追加 `DIAGNOSTIC = "diagnostic"` 常量（只加不改） | 我方（fork 新增文件内完善） |
| `app/.../xposed/capabilities/DiagnosticCapabilityHandler.kt`（新增） | **诊断能力**（`params.mode` 取 `ok`/`slow`/`throw`/`huge`），用于端到端自证四条失败路径。⚠️ **风险等级 `READ_ONLY`**（无副作用：`slow` 只 sleep、`huge` 只造数据、`throw` 只抛异常），故**留在生产包**里 —— 刻意不做「debug 构建才有」的条件编译（那会让真机验证必须在 debug 包上做，而本项目交付一律 release）。⚠️ `huge` 的 `pad` 用**纯 ASCII 的 `x` 是有意的**：它是不会被 JSON 转义的字符 ⇒ 转义开销为 0、只有约 1210 个数组逗号需要余量覆盖 ⇒「截断成功」是**稳定可断言**的结果，而不是对余量取值敏感的结果 | 我方 |
| `HookCapabilityRuntime.kt`、`InvokePolicy.kt`、`wire/ResultBudget.kt`、`VFlowHookEntry.kt`（改，修 **真机实测缺陷：校验对象错位**，2026-10-01） | ⚠️⚠️ **`resolve` 发不出去：截断成功、发送失败，而 App 侧只看到超时**。真机（小米 MIX Fold 3 / Android 17 / LSPosed 2.2.0）实测：`结果超预算，已截断：收下 1228/2000 项，nextCursor=1228` 紧跟 `resolve 异常：TransactionTooLargeException  data parcel size 533700 bytes`。<br/>**根因**：旧终检量的是 **`result` 字段的 UTF-8 字节**（259,237 < 262,144 判通过），而实际发出去的是**外层信封** —— 响应是双层 JSON，`result` 是一个**字符串**装进信封。信封的 **parcel 字节**实测 533,700（≈ 其 UTF-8 的 **2 倍**）。⚠️ **「翻倍」的主因不是二次转义**（对全 ASCII 载荷只占 1.03×），而是 **binder 的 AIDL 字符串按 UTF-16 代码单元 × 2 字节计费**（`writeString16`）——按「转义翻倍」理解会在纯 ASCII 载荷上算错。<br/>**修法（结构性，非调参）**：① 编码**移进** `HookCapabilityRuntime`，出口 `CapabilityResponder` 由「收七个字段」改为「**收一个已编好的信封串**」⇒ 「量的对象」与「发的对象」**在类型上就是同一个**；② `ResultBudget` 新增 **`MAX_ENVELOPE_PARCEL_BYTES = 384 KiB`**（**传输上限**，旧实现只声明了 256 KiB 的内容上限，缺的正是这一条）；③ 预算按信封 parcel 算（`InvokePolicy.itemEnvelopeCost` / `estimatedParcelBytes` / `PARCEL_PER_CODE_UNIT`），受传输上限封顶。<br/>⚠️ **改第一版时踩的坑（已被单测抓住）**：`collectWithin` 会把给它的上限**用满**，而序列化还要额外付信封固定键 ⇒ **「内容预算」与「终检上限」必须是两个数**（`contentBudget` vs `contentBudget + ENVELOPE_FIXED_OVERHEAD_BYTES`），拿前者去终检会**必然判超限**（3 条用例同时变红）。⚠️ 固定开销**另加**而非从预算里扣 —— 扣了会让小预算能力（声明 512 字节）永远装不下（`ENVELOPE_FIXED_OVERHEAD_BYTES` 取代了按比例缩放的 `carrierBytes`）。<br/>✅ **真机复验通过（同日）**：五条路径全过；`huge` 由「1228 项 / parcel 533,700 / 崩溃」变为「**886 项 / parcel 384,712 ≤ 393,728 / Success(truncated=true, nextCursor=886)**」。<br/>⚠️ **为什么 71 个单测没抓到**：它们「经过的是**类型**，不是**传输**」—— 断言字段内容测不出字节口径差 2 倍。已补按**信封 parcel 字节**断言的回归用例（`the check measures the envelope not the result field` + `a huge truncation that fits the transport still succeeds end to end`），并把终检改回 `byteSizeOf(resultJson)` 的反证确认**恰好那一条变红** | **我方**（该文件此前已认手动合并） |
| `core/xposed/CapabilityFallbacks.kt`（**本任务新建**，与 T1 **同名**） | ⚠️⚠️ **add/add 冲突，集成时必须产出第三版**（两个 worktree 各自新建同名文件）。本任务版：`registerAll()` 幂等骨架 + `CapabilityRegistry.register(diagnostic)`（`risk = READ_ONLY`、`fallback = null` ⇒ **独占型**，§6.2）。**本任务版刻意不含 `planOf`** —— 它依赖 T1 的 `CapabilityInvoker` / `CapabilityFallbackPlan`，本 worktree 没有那两个符号 ⇒ 带上会**编译不过**。<br/>⇒ **合并目标 = 幂等骨架 + T1 的 `planOf` + 本任务的 `diagnostic` 注册行**（并把 T1 那句「T1 的预期状态：注册表为空」的日志改掉）。⚠️ 误取本任务版 ⇒ `CapabilityInvokerTest` 里对 `CapabilityFallbacks.planOf` 的两条断言编译不过。<br/>⚠️ **不注册的后果**：T1 的调用入口**第一步** `CapabilityRegistry.find(name)` 未注册 ⇒ `Failed(HANDLER_ERROR, "未注册的 capability：…")`，而**这一步在 App 侧、在提交给 hook 层之前** ⇒ hook 侧永远收不到请求，「池满/超时/截断/未知名」四条路径**一条都验不了** | **手动合并**（两 worktree 各自新建同名文件） |
| `app/.../xposed/BinderTransport.kt`（改） | 新增 `resolve(responseJson): Boolean`（③ 的应答出口；此前该类只有 `send`/`report`，**没有**回 `resolve` 的方法）。`respondUnimplemented` 保留为「executor 未注入」时的降级路径（**不是死代码**：删掉会让那种情形变成静默无响应、调用方白等超时），KDoc 更新 | **手动合并**（新增 1 个方法 + 注释） |
| `app/.../xposed/VFlowHookEntry.kt`（改） | 创建/注入/停止 `HookCapabilityRuntime`（`onInvoke` / `onCapabilities` 两处注入，`stopChannelOnly`/`stopChannel` 两处停止）。⚠️ **这里是全部 ③ 响应的唯一出口**（分页两键的编码规则由 codec 负责；在别处再编一次会出现「有的带标志有的不带」）。⚠️ 热更新换代必须停旧池 —— 旧代际的池与新代际的池并存会抢 system_server 资源 | **我方**（fork 新增文件内完善） |
| `app/.../xposed/wire/CapabilityInvocation.kt`（改） | ⚠️ **本任务使用、非本任务实现**：补 `encodeRequest(cursor)` / `encodeResponse(nextCursor, truncated)` 两个参数 + `CapabilityRequest.cursor` / `CapabilityResponse.nextCursor` / `.truncated` 三个字段。编码侧**有值才写键**；解码侧**缺失归一成 null / false**。它是 T1 worktree 的改动、尚未提交到 `dev` ⇒ 本 worktree 需要它才能实现需求里的「截断标志位与 `next_cursor` 必须一起回」。**纯增量、新字段均带默认值** ⇒ 唯一的生产构造点（`HookChannelController.kt:492`）不受影响、无需改动。⚠️ **合并时若 T1 也带了同一份改动，取任一版本即可**（内容同源） | **手动合并**（与 T1 同源） |
| `app/.../test/.../xposed/capabilities/`（4 个新测试，共 71 例） | fork 独有：执行运行时单测（正常 / 超时 / 异常 / 截断 / 池满 / 未知名 / **停止后迟到调用** / 协议版本不符 / 坏请求）。⚠️ **执行器把「回响应」抽成函数类型**，故**整个运行时纯 JVM 可测** —— §7.2b-9 说 handler 跑在 LSPosed ClassLoader 里 App 侧测不到，但**框架层可以**。⚠️ 测试收的是**经真 codec 编解码的 `CapabilityResponse`**（非自拼 JSON）—— 否则测不到「分页两键究竟有没有写进信封顶层」 | 我方 |
| `test/.../xposed/CapabilityContractPurityTest.kt`（改） | 原缺口断言 `capability package still has no production call sites` **翻面成正面断言**（`capability package has production call sites` + `the call site scan is not vacuous`）。该测试自己的 KDoc 明确要求「接入后改成正面断言，**不是删掉**」。⚠️ 翻面后强度**不比原来弱**：原断言是个否命题（容易被无关引用满足），现在是「具体哪几个符号有引用」的正命题 | 我方 |
| `test/.../xposed/WireLayerPurityTest.kt`（改） | 文件存在性列表追加 5 个新文件（`capabilities/` 复数包） | 我方 |
| `docs/fork/xposed-architecture-v2.md`（改） | §5.2 三层超时的实现现状表：hook 侧总时长由「零代码」改为「已实现（`HookCapabilityRuntime`）」、并补「为什么是事后判定而不是看门狗」（三条理由 + 必须承认的代价）；§10 未决项 #14（工作线程池容量 + 默认超时）标记定案（容量 2 / 默认 5000ms / 队列容量 0）；头部状态与「进展」段更新 | 我方 |
| `scripts/xposed-capability-hook-runtime-verify.sh`（新增） | fork 独有：**真机端到端验证脚手架**（纯 adb + 一张判据表）。第 0 步做三项前置检查 —— 其中 ⚠️ **`CapabilityFallbacks.kt` 必须同时含 T2 的 `DIAGNOSTIC` 注册行与 T1 的 `planOf`**（缺前者每步都失败在 App 侧、缺后者编译不过），并显式判「T1 的 `CapabilityInvoker` 在不在」——**不在就没有发起方，四路径验证不可能做**，脚本据此输出「未验证」而不是假装跑过。⚠️ 脚本里写明了「`adb logcat` 读不到启动期日志、必须用 LSPosed 的 verbose 导出」与「两端用同一个 `request_id` 串联」 | 我方 |
| `test/.../xposed/CapabilityInvocationCodecTest.kt`（改，**随 T1 的 codec 补齐**） | 追加分页三键的 round-trip 用例。⚠️ 与 `CapabilityInvocation.kt` 同源、同样属于 T1 的改动 | **手动合并**（与 T1 同源） |

> ⚠️ **③ 尚未实现的部分**（后续如需继续）：
> 首个真实 capability（`query_shortcut_intents` + 快捷方式选择器换数据源）、
> `capabilityPresence` 的 UI 与 §6.1「已授权但通道断」可见状态。
> ✅ **分页三键的字段闭环已由 T1 落地**（见上一段）。


### Xposed 通道 ③ · 失败分类与「已授权但通道断」的用户可见性（2026-09-30）

> 上位文档：`docs/fork/xposed-architecture-v2.md` §6.1 / §6.2 / §6.4。
> ⭐ **这一批兑现的是一条「因定案而产生的义务」**，不是可选增强 ——
> §6.1 定案「权限判据**保持 L1（实时）**、不改 `L0 ∪ L1`」，代价是
> **通道断时不会禁用工作流** ⇒ 用户看到的是「**权限全绿 + 触发器不工作**」。
> 该节原文逐字写着「可见性由新状态位承担 …… **不是可选项**」。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/xposed/XposedReadiness.kt`（新增） | fork 独有：**触发器就绪度判定纯函数层**。`needsChannelNotice(result)` = `Channel != READY`；`selectAffectedWorkflows(workflows, permissionsOfModule)` = 挑出「启用 + 有触发器 + 那个触发器声明了 `XPOSED_HOOK`」的工作流。⚠️ **只看 `workflow.triggers` 不看 `steps`** —— 本提示回答的是「**触发器为什么不触发**」；`steps` 里的 Xposed 动作走**执行期显式失败**（用户看得到步骤报错，§6.2），算进来会让提示出现在**根本没坏**的场景。⚠️ **比 `permission.id` 不比 `Permission` 实例**（`@Parcelize data class`，注册表里的实例与常量不保证同一）。⚠️ **纯函数、无 `Context`、无 `WorkflowManager`** ⇒ 结构上**写不进盘**（需求硬约束 2 的落实） | 我方 |
| `core/xposed/XposedDiagnostics.kt`（新增） | fork 独有：**文案映射层**。`messageFor(code)` 的**标题按 `userAction()` 派生**、正文按 `code` 逐值给 —— 这让「多个码指向同一处置」在**代码结构上**成立，而不是靠两条 `when` 恰好写得一致。`channelNoticeRes(result)` 的分支来源是 **`XposedState.tapAction`**（复用 `GUIDE` / `RECONNECT_HINT`，不另造一套）。⚠️ **`messageFor` 的签名里根本没有 `detail`** —— §6.4 约束 2「`detail` 只给人看、绝不参与判断」的落实；要显示它只能走 `formatBodyWithDetail(context, msg, detail)`（**唯一**允许碰 `detail` 的地方，且只拼接不判断）。⚠️⚠️ **生产消费者现状如实记录在类注释里，且这里记着一处我写错过、又被产物实测纠正的事实**：`channelNoticeRes` 由 `TriggerService` 消费；而 **`messageFor` 在生产代码里**没有任何**调用点** —— ③ 的 App 侧调用运行时（`CapabilityInvoker` / `CapabilityInvokeOutcome`）**在本分支上并不存在**（属 T1，未合入）。初稿写的是「`CHANNEL_DOWN` 那格有生产消费者」，**是错的**（`HookChannelController.failAllWaiters` 只是把该码放进 `CapabilityResponse`，并不调用本类）。**后果已在 `assembleRelease` 产物上实测确认**：`aapt2 dump resources` 里**只有 `trigger_xposed_notice_*` 两条**，九条 `capability_error_*` **全被 R8 + `shrinkResources` 剥掉**（类/方法连同其引用的资源一起消失）。⚠️ **这不是缺陷**（死代码消除是正确行为，接入首个 capability 后自动回来），但**必须知道**，否则会 ① 在真机上找这几条文案找不到、以为映射写错了；② 以为「写进 `strings.xml` 就等于会进 release 包」。⚠️ **不要**为此加「`messageFor` 必须有生产调用点」的测试 —— 那在接入首个 capability 前**恒红**，而恒红的断言会被下个实现者删掉 | 我方 |
| `services/TriggerService.kt`（改） | 新增 `observeXposedChannelState()`（订阅 `XposedFrameworkMonitor.state` + `HookChannelController.connected` **两条流**）+ `refreshXposedChannelNotice()`（判定 + 打日志 + 刷通知）+ `describeXposedNoticeAction()`；`@Volatile xposedNoticeRes` 缓存上一次的文案（**只在变化时**才 `updateForegroundState()`，否则每次状态流发射都会 `startForeground`，**通知会闪**）；`createNotification()` 的正文改为按 `xposedNoticeRes` 分流。⚠️⚠️ **三条纪律写进代码注释**：① 判据不改（不碰 `XposedCapability`）；② **不用「禁用工作流」把用户引过来** —— 本块**只读** `getAllWorkflows()`、**从不** `saveWorkflow`（通道断多半是几秒内自己好的**时序**问题，让短暂窗口去改落盘数据代价与收益不成比例）；③ `XposedState` 语义不动（只读不重判）。⚠️ `onCreate` 里**不在同步栈上调** `refresh…`（它会读工作流）—— `StateFlow` 的 `collect` 会立刻收到当前值，订阅本身就已经触发了一次初始刷新 | **手动合并** |
| 三份 `strings*.xml`（改） | 追加 **11 条 ×3 语言**：`trigger_xposed_notice_{reconnect,config}` + `capability_error_title_{upgrade_app,report_problem,check_capability,check_lsposed}` + `capability_error_body_{absent,timeout,handler_error,channel_down,payload_too_large}`。⚠️ 文案的**指向**必须与 §6.4 一致，尤其三条：`CAPABILITY_ABSENT` 指向 **App 侧**（更新/重启 App，**不是**去改 LSPosed 配置 —— 这正是 P4 踩过的坑：加载比 hook 连接早 1.6 秒，旧文案却让用户去检查本来正确的配置）；`PAYLOAD_TOO_LARGE` 与 `TIMEOUT` **同类**（都指向「报告问题」，它是实现缺陷不是配置问题）；只有 `CHANNEL_DOWN` 才提 LSPosed | **手动合并**（追加条目） |
| `test/.../core/xposed/XposedReadinessTest.kt`（新增，11 例） | fork 独有。重点：`ACTIVE + DISCONNECTED` **端到端仍可达**且 `needsChannelNotice == true`、且**仍不健康、仍不引导用户改配置**；`Channel` 恒不依赖 `Framework`（4 组组合逐一验）；**Xposed 模块只在 `steps` 里不算命中**；**权限按 id 匹配**（用改造过无关字段的副本实例做反证）；未知模块 id 不抛 | 我方 |
| `test/.../core/xposed/XposedDiagnosticsTest.kt`（新增，13 例） | fork 独有。重点是**反向断言**（锁「**不要**混到某一类里去」）：`payload_too_large` 的标题/正文都不等于 `channel_down`；`capability_absent` 同理（防用户被引去改本来正确的配置）；**只有 `channel_down` 用 LSPosed 标题**；`PAYLOAD_TOO_LARGE` 与 `TIMEOUT` 标题**必须相同**（同 `userAction()`）；**标题分组粒度必须等于 `userAction()` 分组粒度**（防手写一张「看起来差不多」的表）+ 逐对验证同处置同标题；**码仍是五个**（测试期的第二道锁，`bodyResOf` 的穷尽 `when` 是编译期锁）；**`messageFor` 的签名里不许有 `detail`**（反射断言参数表）；**源码扫描：生产代码不得对 `detail` 做判断**（扫所有提到 `CapabilityErrorCode` 的文件，⚠️ **必须先剥注释** —— 两个文件的注释正文里就写着 `if (detail.contains("…"))`，不剥会恒红）+ **防空转**（≥5 文件、剥注释后仍有代码行）；**三语 11 键齐全**（读三份 xml） | 我方 |
| `test/.../services/TriggerServiceXposedNoticeWiringTest.kt`（新增，6 例） | fork 独有：**源码扫描型接线锚定**（形态照 `CoreDexFingerprintTest`）。锁住 `TriggerService` **真的**订阅了两条流、真的用了 `XposedReadiness` 的两个方法、真的走 `XposedDiagnostics.channelNoticeRes`、**`refreshXposedChannelNotice` 函数体内不出现 `saveWorkflow` / `isEnabled = false`**（需求硬约束 2 的机器化锁）、通知正文是条件式，外加一条**防空转**（函数体按大括号配对截取且跳过字符串字面量 —— 函数体里有 `joinToString { … }` 的插值）。⚠️ **存在理由**：本仓库在 `CoreLauncher` 上踩过「13 个纯函数单测全绿但调用点缺失」的坑 —— `TriggerService` 是 Android `Service`，纯 JVM 单测起不来它，接线只能在源码层锁 | 我方 |

### Xposed 通道 ③ · **首个真实 capability**（`query_shortcut_intents`，2026-10-01）

> ③ 的第三段落地，也是**它存在的理由**：拿 `ShortcutInfo` 的**完整 Intent + extras 真实类型**，
> 把快捷方式选择器的数据源从 dumpsys（有损）换成无损。
> 设计依据：`docs/fork/xposed-capability-invocation-design.md` §6；
> 探针结论见 `.mindfs/tasks/plan-4.md` §9.0-bis（含**原路径被证伪**的 AOSP 源码依据）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `app/src/main/java/.../xposed/capabilities/QueryShortcutIntentsHandler.kt`（新增） | fork 独有：③ 的首个真实 capability。⚠️⚠️ **取 Service 的方式与原设计不同（原设计被真机 + AOSP 源码双重证伪）**：原方案写 `LocalServices.getService(ShortcutService)`，实测**取不到** —— AOSP `ShortcutService.java:502` 只注册 `ShortcutServiceInternal`，**从不注册自己**（`:169` 它是 `IShortcutService.Stub`、`:678` 自己 `publishBinderService`）。✅ 正解是 `ServiceManager.getService("shortcut")`（hook 层与它同进程 ⇒ 拿到**本地对象**）。另含三个**纯读源码就能避免**的坑：① `matchFlags` 传 0 ⇒ `(si.getFlags() & shortcutFlags) != 0` 恒假、**必然 0 条**；② `getIntents()` 是 **`Intent[]` 数组**不是 `List`；③ `ctx.javaClass.classLoader` 是 `ContextImpl` 的 loader（加载不到系统类），必须 `ctx.getClassLoader()`。⚠️ **`package_name` 为空 = 查全部包**（选择器的实际用法），走反射遍历 `mUsers → mPackages → mShortcuts`，**按类型找候选、不写死字段名** | 我方 |
| `app/src/main/java/.../xposed/capabilities/HookCapabilityRegistry.kt`（改） | `init` 追加一行 `register(QueryShortcutIntentsHandler())`（不重排既有注册） | **手动合并**（追加一行） |
| `app/src/main/java/.../core/xposed/CapabilityFallbacks.kt`（改） | `registerAll()` 追加 `QUERY_SHORTCUT_INTENTS` 的注册。⚠️ **替换型**（`fallback != null` ⇒ Xposed 不可用时**静默降级**到 dumpsys + 留痕），`maxResultBytes = 128 KiB` | **手动合并**（追加） |
| `app/src/main/java/.../ui/shortcut_picker/ShortcutPickerSupport.kt`（改） | ① 新增 `loadShortcutsWithFallback(context)`（换源入口，返回 `LoadResult(items, degraded, notice)`）；② 新增 `queryViaDumpsys(packageName)`（**降级实现**，**刻意复用** `loadShortcuts` 的整条解析链 —— 契约是「同入参、同形状、更差的实现」，另写一份会让升降级差异变成两个实现之间的差异）；③ 新增 `LoadResult` 数据类 | **手动合并** |
| `app/src/main/java/.../ui/shortcut_picker/UnifiedShortcutPickerSheet.kt`（改） | ⚠️ **行为变更**：加载链路改走 `loadShortcutsWithFallback`；**前置条件放宽** —— 原先是「没 Shizuku/Root 就直接出提示、**连加载都不试**」，而 ③ 与 shell 权限无关 ⇒ 装了 Xposed 的设备会被**白白挡住**。空结果的三种成因分开处理（降级且无 shell / 降级但数据空 / 无损但数据空） | **手动合并** |
| `test/.../ui/shortcut_picker/ShortcutPickerFallbackTest.kt`（新增） | fork 独有：**源码扫描型**（⚠️ **刻意不用构造字面量的写法** —— 那种测试**永远不会红**：第一版就是构造 `mapOf("source" to "dumpsys")` 断言它等于 dumpsys，反证时删掉源码里的字面量**测试照样全绿**）。改为扫源码后**两条反证均确认变红**：删 `source` 标记 ⇒ 红；把 Sheet 改回「没 shell 就直接 return」⇒ 红 | 我方 |
| `.gitattributes`（改） | 追加新探针的三个谱文件 + 验证脚本的 `text eol=lf`。⚠️ 新建时**漏了这两行**，而本机 `core.autocrlf=true` ⇒ 提交进 git 的 `module.prop` / `java_init.list` 曾是 CRLF，表现是「克隆到别处后构建失败」而**本机测不出来** | **手动合并**（追加） |
| `scripts/probe/xposed-channel/shortcut-probe/`（新增） | fork 独有：**快捷方式探针**（跨机型复测脚手架）。⚠️ 真机结论**已改由热重载路径取得**（用户否掉了「LSPosed + 重启设备」那条路：hook 层本来就跑在 system_server 里，反射读 `ShortcutService` 的能力与独立探针等价）；本工程保留为**跨机型脚手架**（同 `fold-trigger-verify.sh` 之于折叠屏） | 我方 |
| `scripts/probe/xposed-channel/build.sh`（改，纯追加） | 新增 `build_shortcutprobe()` + `shortcutprobe` case；⚠️ 比 `build_hookprobe` **多一道断言**（谱文件不得含 CR —— CRLF 同样让模块静默不加载）。`build_hookprobe` 一字未改 | **手动合并**（追加） |
| `scripts/xposed-shortcut-probe-verify.sh`（新增） | fork 独有：探针一键验证脚本。⚠️ **无设备时打印「未验证」并 `exit 0`** —— 不是报错退出，否则「没设备」看起来像「验证失败」 | 我方 |

| `core/xposed/CapabilityInvoker.kt`、`ui/shortcut_picker/ShortcutPickerSupport.kt`、`xposed/capabilities/QueryShortcutIntentsHandler.kt`（改，修 **真机实测缺陷：无损结果进不了选择器**，2026-10-01） | ⚠️⚠️ **整体验收抓到的缺陷：`itemsFromLossless` 恒返回空 ⇒ 选择器实际没换源 ⇒ 用户看到「没有快捷方式」**。真机证据（`request_id` 逐层串联）：hook 层产出 407 条 ✅ → 信封收缩 ✅ → App 侧收到 `Success` ✅ → **选择器 `itemsFromLossless` 返回 0** ❌。<br/>**根因**：`CapabilityInvoker.jsonObjectToMap` 是**浅层转换**（`out[key] = obj.opt(key)`，值原样保留）⇒ `result["items"]` 是 `org.json.JSONArray`、元素是 `JSONObject`，而消费者写的是 `as? List<*>` / `as? Map<*,*>` —— **两者都不实现 `java.util.List` / `Map`** ⇒ 恒 null ⇒ 空列表。<br/>**修法（结构性）**：在 **codec 层**做**递归**深层转换（`JSONObject`→`Map`、`JSONArray`→`List`、`JSONObject.NULL`→`null`），所有 capability 的消费者一并受益 —— 避免「有的消费者转了有的没转」（`FORK.md` 记过 logcat 双份实现的同类缺陷形态）。⚠️ 已核 `outcome.result` 的生产消费者只有一处。⚠️ 顺带修掉 `itemsFromLossless` 里两处**同源**的 `as? List<*>`（`intent_categories` / `extras`）—— codec 层修好后它们自动正确。<br/>⚠️ **返工中另外发现并修掉两处相邻缺陷**（整体验收未提及，但不修则需求不成立）：**② 双重切片** —— handler 写 `infos.drop(cursor)` + `startIndex = cursor`，而框架 `collectWithin(startIndex = …)` **自己又跳一次** ⇒ **第二页恒为空、176 条永远拿不到**（米家就在被丢的那 176 条里）。改为返回**全量 + `startIndex`**（与 T2 既有的 `DiagnosticCapabilityHandler.MODE_HUGE` 同款写法，其单测 `huge honours the cursor from params` 锁着这个语义）。**③ 选择器不翻页** —— 单次响应装 232 条（信封 parcel 上限），而调用方只调一次 ⇒ **174 条静默丢失且无提示**（`truncated` 回传了但没人消费）。加翻页循环，**三条终止条件**（`nextCursor` 空 / 与请求 cursor 相同（没前进）/ `MAX_PAGES = 8` 硬上界），后两条触发时留 `TRUNCATED_NOTICE`（与降级文案**分开** —— 自救方向不同）。<br/>✅ **真机复验**：第 1 页 232 + 第 2 页 176 = **408 零丢失**（修复前第 2 页为空）；米家「关闭灯与投影仪」出现在选择器，`extra_scene_account` 用 **`--es`**（String，非 Long）。⚠️ `408 → 401` 是 `distinctBy(stableId)` 的**展示层去重**（`stableId = pkg\\|label\\|activity\\|command`，**既有口径**），与分页无关 —— 原始总数 408 与 dumpsys 一致。<br/>⚠️ **为什么 11 例单测没抓到**：它们用**手写的 `mapOf`/`listOf`** 喂（那是真正的 `Map`/`List`），**恰好绕过**这个 bug。⇒ **测试夹具的类型必须等于生产数据的类型**（新用例改为从 JSON 字符串解析）。 | **手动合并**（三个文件） || `scripts/xposed-shortcut-probe-verify.sh`（改，**修一处真实回归**） | ⚠️⚠️ **该文件曾被 `8b8f5ede` 整份清空成 0 字节** —— 那个提交的目的是「给新探针的谱文件与验证脚本锁 LF 行尾」（只加 `.gitattributes` 规则），却在同一次提交里把这份脚本记录成了 `192 deletions(-)`（工作区文件因 `core.autocrlf=true` 与 `text eol=lf` 规则叠加被 git 视作内容变更，提交时只落进了删除）。**影响**：方案 §8.1 的 A3 与 `P0-FINDINGS.md` §4 的三处用法全部失效（文件在 HEAD 里没有内容）。**已从 `f0537d36` 取回并确认落盘为纯 LF**（`tr=0 / LF=192`，逐字节核过）。⚠️ **这个缺陷能潜伏的原因值得记**：**空脚本跑 `logs` 子命令同样返回退出码 0**（`bash -n` 也报 0）⇒ A3 原来「只看退出码」的判据**不足以发现它**，我是靠 `git cat-file -s` 看到 0 才发现的 | **我方** |

> ⚠️ **一处与方案的已知偏差**：方案 §4.2 要求结果带 `total`（截断前全量条数），
> **未实现** —— 框架的 `CapabilityOutcome.Items` 只有 `items` + `startIndex`，
> 且 `InvokePolicy.buildResultJson` **固定**产出 `{"items":[…]}`，没有结果元数据这一层。
> 塞进 `items` 会污染列表。⇒ 取第三条路：**不实现、如实记录**（`truncated` 与 `next_cursor`
> 走信封顶层，未受影响）。若将来要做全量遍历或确有包超预算，**应先扩框架的结果形状**。

---

### Xposed 通道 ③ · 工作流模块 `vflow.xposed.js`（2026-10-02）

> 把 ③ 的 `xposed_js` capability 接到**用户可见的编排面**上（`xposed-architecture-v2.md` §5.7
> 「两个 JS 模块」决策的兑现）。hook 侧执行体由 T2 交付（见上表），本段只做 App 侧模块。
> ⚠️ **文档里的 `execute_script` ≡ 代码里的 `xposed_js`**（同一能力，协议名用户 2026-10-02 拍板）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/workflow/module/xposed/`（新分类目录，与 `shizuku/` / `core/` 同级） | 目录名 `xposed` 是**通道名**（「经哪条特权通道执行」），**不是厂商名**。先例：`vflow.shizuku.shell_command` / `vflow.core.shell_command` | 我方 |
| `.../module/xposed/XposedJsModule.kt`（新增） | fork 独有：模块本体。⚠️⚠️ **与 `vflow.system.js` 是**定义性**差别不是参数差别**：跑在 **system_server**（UID 1000）、**不注入 `vflow.*` 模块树**（脚本里调 `vflow.*` 一律 `ReferenceError`）、**崩溃半径是整机**。⚠️ **`usageScopes` 只给 `TEMPORARY_WORKFLOW`，绝不给 `DIRECT_TOOL`**（V2.0 §5.7 安全边界：AI 不得**未经人审**就往 system_server 投脚本）+ `riskLevel = HIGH` 走审批。⚠️ **`getRequiredPermissions()` 必须返回 `listOf(PermissionManager.XPOSED_HOOK)`** —— 漏声明会让权限体系判「缺权限」（本仓库在 `SimDataSwitch` 上踩过的同类坑，权限齐全的设备上测不出来）。⚠️ 默认示例脚本**刻意不调 `vflow.*`**（照抄 `JsModule` 的示例会误导用户）。⚠️ 复用 `JsModuleUIProvider`（零改动上游，见下条） | 我方 |
| `.../module/xposed/XposedJsSupport.kt`（新增） | fork 独有：**纯函数层**（无 Android 依赖，可纯 JVM 单测）。`clampTimeoutMs` —— ⚠️ **`<= 0` 退回默认 5000 而不是钳到 1ms**（钳到 1 会让用户拿到一个**必然超时**的结果，而他看到的是「脚本超时」会去查脚本）。`rawOutputsOf` —— ⚠️⚠️ **T2 定案的 result 形状是 `{"items":[<outputs 字典本身>]}`，**不是** `{"outputs":{...}}`**（框架 `InvokePolicy.buildResultJson` 固定产出 `items`，`CapabilityOutcome` 无标量通道）。⚠️ **`items = [{}]`（空字典）⇒ 空 map，**不是失败****；⚠️ **必须用 `as? List<*>` / `as? Map<*,*>`，不得改回 `optJSONObject`** —— App 侧 codec 已做**递归深转**，改回 org.json 式取法会**恒返回空**，重演 `itemsFromLossless` 那个真机缺陷（该文件 KDoc 记着与 handler 那句相反说法的方向差异：那是**读请求**的 `inputs`，这是**读响应**的 `result`） | 我方 |
| `.../module/xposed/XposedJsSupportTest.kt`（新增，19 例） | fork 独有：钳位 8 例 + `items[0]` 消费 + 防御分支 + `scriptInputsOf`。⚠️ 含**反向断言**：`MAX_TIMEOUT_MS <= 60s`（池容量只有 2，上限不能形同虚设）、输出**不得有 `outputs` 包装键**。**两条反证均确认变红**：改走 org.json 式取法 ⇒ 6 条红；去掉上限 ⇒ 1 条红 | 我方 |
| `.../module/xposed/XposedJsModuleTest.kt`（新增，22 例） | fork 独有：**声明体检**（形态照 `ActivityChangedTriggerModuleTest`）。锁 id / 分类 spec / 权限双保险 / `usageScopes` 不含 `DIRECT_TOOL` / `riskLevel == HIGH` / hints 点明分工 / 输入输出契约 / **图标 ≠ `rounded_js_24`** / **`timeout_ms` 不在 `getHandledInputIds()`**（它的 UI 归属）/ 能力注册为独占型 + `timeoutMs = null` / **源码扫描锁注册点**（形态照 `CoreDexFingerprintTest`）/ **超时约束写在标签里而非只写 hint**（见下）。**三条反证均确认变红**：改 `directToolMetadata` ⇒ 红；去掉权限声明 ⇒ 红；从三语标签删掉「5000/30000」⇒ 红 | 我方 |
| 三语 `param_vflow_xposed_js_timeout_name/_hint`（改） | ⚠️⚠️ **约束必须写在【标签】里，不能只写 `hint`**：`timeout_ms` 走自动表单，而它的 `hint` 在自动表单里只是**输入框占位符**（`StandardControlFactory.createTextInputLayout(hint = …)`，已核实 NUMBER 类型走这条），而字段**预填了 5000** ⇒ **占位符永远不显示** ⇒ 用户既看不到上限、也可能以为它必填。故标签改为「超时（毫秒，默认 5000，上限 30000）」（三语同步），hint 保留为占位符兜底。**已落成单测锁 + 反证**。⚠️ 位置参 `name`（fallback）与 `nameStringRes` 指向的文案**必须同步改**，否则某语言下退回旧标签 | **手动合并**（改写已有条目） |
| `core/workflow/module/ModuleRegistry.kt`（改） | ① 追加 `import ...module.xposed.*`；② Shizuku 段之后**追加一行** `register(XposedJsModule(), context)`。**不重排任何既有注册**（有测试反向锁住「在 Shizuku 段之后」） | **手动合并**（追加） |
| `core/module/ModuleCategories.kt`（改） | ① 追加 `const val XPOSED = "xposed"`；② `specs` **追加一行** `ModuleCategorySpec(XPOSED, R.string.category_xposed, R.color.category_xposed, 15, "Xposed")`。⚠️ **只在 metadata 写 `categoryId` 不够**：`getSortOrder` 对未登记分类返回 `Int.MAX_VALUE`、`getLocalizedLabel` 回落 `defaultLabel`（显示成小写 `"xposed"`）。⚠️ `sortOrder = 15` = 既有最大 14 + 1，**不改任何既有分类的顺序**（代价：排在「用户模块」之后，用户已确认接受） | **手动合并**（追加） |
| `res/values/colors.xml`（改） | 追加 `<color name="category_xposed">#7E57C2</color>`（单份，无三语） | **手动合并**（追加） |
| `res/drawable/rounded_xposed_js_24.xml`（新增） | fork 独有：JS 字形 + 齿轮（系统进程标识）。⚠️ **不复用 `rounded_js_24`** —— 两个模块名字里都含 "JavaScript"，图标一样会让用户**分不清脚本跑在 App 进程还是系统进程**，而那正是本模块存在意义的全部（崩溃半径差一个数量级）。结构照 `rounded_js_24`（24dp / viewport 960 / `tint`），两个 group 各自缩放定位 | 我方 |
| 三语 `res/values{,-en,-ja}/strings_module.xml`（改） | 追加 12 条 ×3 语言（模块名/描述/三参数名/timeout hint/输出名/摘要前缀/空脚本错误两条/进度消息/分类名）。⚠️ 分类文案**放 `strings_module.xml`**（与 `category_shizuku` 同处），不放 `strings.xml` | **手动合并**（追加条目） |
| `core/xposed/CapabilityFallbacks.kt`（改） | `registerAll()` 追加 `XPOSED_JS` 注册：**独占型**（`fallback = null` —— UID 1000 的权限 App 进程给不了，没有等价物可降）、`risk = HIGH`、`maxResultBytes = 64 KiB`（与 hook 侧 handler 声明一致）、**`timeoutMs = null`**（⚠️ 两端都声明会造成「App 配 30 秒、hook 按小值算」的错配）。⚠️ 本文件是 **App 侧唯一的注册落点**（`CapabilityRegistry` 不得引 App 侧类），且**只由本任务改**（`CapabilityNames.XPOSED_JS` 常量由 T2 加 —— 两任务串行，规避了上一批的 add/add 冲突） | **手动合并**（追加） |

> ⚠️ **订正一处文档前提（T4 核实，2026-10-02）**：`xposed-architecture-v2.md` §5.7
> 「开工前的前置条件」一节把 **T1（App 进程 `JsExecutor` + `JsTimeout`）** 列为
> **本 capability 的前置条件**，理由是「不先把连超时都没有的引擎修好，就是把它原样搬进系统进程」。
> **那个理由不成立** —— **两份实现零代码共享**（已逐文件核实）：
>
> | | App 侧（T1） | hook 侧（本 capability） |
> |---|---|---|
> | 文件 | `core/execution/JsExecutor.kt` + `JsTimeout.kt` | `xposed/script/ScriptExecutor.kt` + `ScriptSandbox.kt` |
> | 超时机制 | `ContextFactory` 子类 + **ThreadLocal 预算栈**（可嵌套） | `ContextFactory` 子类 + **单一 deadline + `arm()`** |
> | 有没有「搬过去」这回事 | — | ❌ **没有**。hook 侧从来没引用过 T1 的任何符号（`grep` 到 5 处 `JsExecutor` 命中**全是 KDoc 对照表**，无 `import`、无调用） |
>
> ⇒ T1 与本 capability 是**两条独立的执行路径**，T1 挂了也不影响本 capability 成立。
> 两处**同款但独立**的设计（都用 `ContextFactory` 子类挂 `observeInstructionCount`）是
> 「同一个平台问题在两种处境下的两次解」，不是代码复用。
> ⚠️ **T1 的当前状态**：`timeoutMs` 的**两个**既有调用点（`JsModule.execute`、`InlineScriptEvaluator`）**都不传参** ⇒ 走默认 `null` ⇒ **对既有行为零影响**（引擎有超时能力、但没有任何生产路径让它生效）。

> ✅ **已修缺陷 —— hook 层的正则（`RegExp`）曾整体不可用（2026-10-02，当日修复并真机验证）**
>
> **现象**：在 `vflow.xposed.js` 里写**任何**正则都失败 ——
> `脚本错误（第 3 行第 0 列）：正则表达式不可用。`；`typeof RegExp === "undefined"`。
> **同一个 App 的 `vflow.system.js` 一切正常**。
>
> **根因（已实验确证，非推断）**：Rhino 的正则引擎是**可插拔**的，靠 ServiceLoader 发现 ——
> `META-INF/services/org.mozilla.javascript.RegExpLoader → …regexp.RegExpLoaderImpl`。
> 而 `Context` 静态初始化里用的是 **`ServiceLoader.load(Class)`（单参版）**，
> 它走**线程上下文 ClassLoader（TCCL）**、**不是**定义 Rhino 的那个：
>
> | 进程 | TCCL | 能否找到服务文件 |
> |---|---|---|
> | App（`vflow.system.js`） | 应用 ClassLoader（能看 APK 内 `META-INF/services`） | ✅ |
> | **hook 层** | LSPosed 给的模块 ClassLoader | ❌ |
>
> ⚠️ **该查找只在类初始化时做一次、失败不重试**，且 Rhino 1.9.0 **没有公开 API** 能补救
> （`Context`/`ContextFactory` 全部 public 方法里没有 `setRegExpProxy`）⇒ 只能让第一次成功。
>
> ⚠️ **一句本地化文案曾被误当成「主动拦截」**：「正则表达式不可用。」是 Rhino 自带的
> `Messages_zh_CN.properties` 文案（`msg.no.regexp`），**不是**我方拦截 ——
> 全仓 grep `setRegExpProxy` / `ClassShutter` 均零命中。
>
> **修法**：`xposed/script/RhinoServiceWarmUp.kt`（新增）。把 TCCL **临时**换成模块
> ClassLoader → `Class.forName("…Context", true, cl)` 触发查找 → **`finally` 立刻还原**。
> ⚠️ **实测「还原之后正则仍可用」**（这正是敢临时改的前提）：静态字段已把结果缓存住，此后与 TCCL 无关。
> 副作用被压到「一个线程 + 一次类加载」的瞬时窗口，窗口内不调用任何回调。
>
> ⚠️⚠️ **触发点必须两处，缺一不可（真机踩出来的）**：
> 起初只挂 `onSystemServerStarting` ⇒ **装包后正则仍然坏**（设备 `uptime` 是 `up 3 days`，从未重启）
> —— 那个回调**只在 system_server 启动时跑一次、官方不在热更新里重放**，而 `Context` 是**框架的类**、
> 热更新不重置它 ⇒ 不重新预热就永远救不回来。
> ⇒ 补挂 `onHotReloaded`（用 `javaClass.classLoader`；`HotReloadedParam` **没有** `getClassLoader()`）。
>
> ✅ **真机验证（小米 MIX Fold 3 / Android 17）**：
> `REGEXP_PROBE lit=true ctor=true grp=12 rep=a#b# typeof=function`
> —— 字面量 / 构造函数 / 捕获组 / `replace` + `g` 全部恢复；用例 01/07 回归通过
> （`vflow` 仍为 `undefined`，定义性约束未被破坏）。
>
> ⚠️ **一处只验到「行为」没验到「机制」的地方**（如实记录）：热更新后再预热**为什么**有效，
> 按「静态初始化只跑一次」推本该是 no-op，但真机确实修好了 —— 推测是 LSPosed 换代后
> hook 层可达的 `Context` 也跟着是新的一份（静态状态全新）。**行为已实证，机制未验**
> ⇒ 将来若要动这里，**先跑上面那条 PROBE 复验**，别只看推导。
>
> ⚠️ **只能真机验，单测测不出**：单测 JVM 的 TCCL 是对的（能看到 Rhino jar 的 service 文件），
> 正则一直是好的 ⇒ `RhinoServiceWarmUpTest` 必须**人为造一个隔离 ClassLoader** 才能复现。
> **反证已做**：去掉「换 TCCL」那一步 ⇒ 用例变红。
>
> ✅ **已修缺陷 —— 本模块的【默认示例脚本】曾用顶层 `return`（T4 查出，当日修复，2026-10-02）**
>
> **原缺陷**：`XposedJsModule.kt` 的 `script` 输入 `defaultValue` 曾写
> `return { sum: 1 + 1 };` —— 而 **Rhino 1.9.0 不接受顶层 `return`**
>（`脚本错误（第 9 行第 7 列）：返回的值无效`）。
>
> ⚠️⚠️ **它是【解析期】错误，脚本一行都不执行**（T4 用 `console.log` 打点验证过：
> 连那个 log 都不出现）。用户新建模块 → 直接点运行 → **立即失败**，
> 而报错信息完全指不到真正的原因。
>
> **修法（已落地）**：改为末行表达式 `var r = {}; r.sum = 1 + 1; r;`，
> 与既有的 `vflow.system.js`（`JsModule.kt:50`，注释写 "Return a dictionary" 而正文用 `r;`）**同一种方言**。
>
> ⚠️ **为什么原 41 例单测没抓到**：它们测的是**纯函数**与**元数据声明**，
> **没有一条真的把默认脚本跑一遍**（T2 的 `ScriptExecutorTest` 用的是 `({result: 2})` 表达式形式
> ⇒ **测试用的方言 ≠ 交付给用户的方言**）。这是一条可复用的教训 → 见下。
>
> **已补回归（`XposedJsModuleTest > the default script actually runs`）**：
> ⚠️ 它**从 `getInputs()` 取 `defaultValue`**（不在测试里另抄一份字面量 ——
> 抄一份的话，改了默认脚本而忘了改测试，两边各自漂移、测试照样绿），
> 把它喂给 `ScriptExecutor.run`，断言产出 `sum == 2`。
> **反证已实际做过**：改回顶层 `return` ⇒ 该用例**变红**。

> ⚠️ **本批兑现了一处既有缺口**：`XposedDiagnostics.messageFor(code)` 此前**在生产代码里零调用点**，
> 导致九条 `capability_error_*` 被 R8 + `shrinkResources` 剥掉（上一段有如实记录）。
> 本模块的失败路径接上它之后，那些文案**自动回到 release 包**。
> ⚠️ **两处已知偏差**（如实记录，非缺陷）：
> ① 模块方案的 `result` 形状与 `xposed-executor-design.md` §3.2 的旧形态（`{"outputs":…}`）不同，
>    实际是 `{"items":[<outputs>]}` —— 用户 2026-10-02 拍板接受**形状 A**；
> ② `CapabilityInvoker.invoke` 的 KDoc 写死「不得在主线程调用」，本模块**自己包
>    `withContext(Dispatchers.IO)`** —— 不依赖 `WorkflowExecutor` 的调度实现细节。

---

### Xposed 通道 ③ · `xposed_js` 真机端到端验证（2026-10-02）

> 本段**不改任何业务代码** —— 它登记的是**验证脚手架**与**验证结论**，
> 以及验证过程中查出的、属他人任务的缺陷。
> 上位文档：`docs/fork/xposed-architecture-v2.md` §5.7（已同步更新为「已实现 + 已验证」）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `scripts/xposed-js-verify.sh`（新增） | fork 独有：**真机端到端验证脚手架**（形态照 `xposed-shortcut-probe-verify.sh`）。子命令 `setup / base / run / logs / judge / scope / consistency / break / restore / full`；三态判定 `pass / fail / unknown`（`unknown` 必须说清为什么判不了，**不得当成 pass**）；无设备 ⇒ 打印「未验证」并 `exit 0`。⭐ **本脚本踩掉的三个静默坑**：① ⚠️ **adb 对同一台设备开出多条 transport**（USB + 无线 TLS 各一条）⇒ **不带 `-s`** 的命令全部以 `error: more than one device/emulator` 失败，而失败是**静默**的（logcat 返回空 ⇒ 采集文件全空 ⇒ 判定全判「未验证」，看起来像「设备没问题但功能不对」）⇒ 全脚本固定第一条 serial。② ⚠️ Windows 下 `subprocess` 默认按 GBK 解码，而设备发 UTF-8 ⇒ 中文全变 mojibake、判定用 grep 一律失配 ⇒ 显式 `encoding="utf-8"`。③ ⚠️⚠️ **`set -o pipefail` + `echo "$bigvar" \| grep -q` 会【假阴性】**：`grep -q` 一命中就退出 ⇒ `echo` 收到 SIGPIPE（退出码 141）⇒ pipefail 让整条管道返回非零 ⇒ **明明匹配上了却走 else 分支**。全量 logcat 有数 MiB 时**必现**（实测 3 MiB 复现）。修法：先 `printf '%s\n' "$log" > file`，再 `grep -q PATTERN file`。⚠️ 这个坑**本任务的 `scope` 子命令真的踩了** —— 它先报「作用域未勾选，需人工重启设备」，而实际是抓到了日志的（判据被假阴性吃掉了）。**排查手法**：故意把「通过」分支写成打印行数，发现日志在、行为却相反 ⇒ 才定位到 pipefail | 我方 |
| `scripts/xposed-js-verify/`（`t4_driver.py` / `consistency_check.py` / `core_exec.py` / `cases/` + `cases/README.md`，均新增） | fork 独有：驱动与用例。`core_exec.py` 经 vFlow Core 的 **root 通道**执行 shell —— 第 9 项要禁用 App 组件来构造 `channel_down`，而**shell（uid 2000）改不动组件状态**（`SecurityException: Shell cannot change component state`），必须借 Core（本机实测 `context=u:r:ksu:s0`）。`consistency_check.py` 做与 `vflow.system.js` 的行为一致性核对 | 我方 |
| `docs/fork/xposed-architecture-v2.md` §5.7（改） | ① 状态行「代码未实现」→「**已实现 + 已真机端到端验证通过**」；② 新增**真机验证结论表**（10 项 + 一致性核对，逐项带证据）；③ 实施顺序表的 P0 行标记「2026-10-02 真机通过」；④ 新增「命名对照」说明（**文档的 `execute_script` ≡ 代码的 `xposed_js`**，同一能力，协议名用户 2026-10-02 拍板） | 我方 |
| `.mindfs/upload/t4/` | 证据归档：逐用例两侧日志与产物（`verdict/cases/`）、判定文本、逐项结论（`verdict.txt`）、`channel_down` 弹窗截图 | 我方 |

#### ⭐ 真机验证结论（小米 MIX Fold 3 / Android 17 / LSPosed 2.2.0）

**10 项判据全部通过，0 失败 0 未验证。** 头号未知项 —— **Rhino / `ImporterTopLevel`
能否在 system_server 的 LSPosed ClassLoader 下初始化** —— **已关闭**：
hook 侧日志出现脚本里的 `console.log` 输出（`[XposedJs] VFLOW_JS_MARK 2.0`），
这不只证明「通道通」，还证明**脚本真的被执行了**。

| # | 判据 | 结果 |
|---|---|---|
| 1 | ★ Rhino 在 system_server 起来 | ✅ hook 日志 `VFLOW_JS_MARK 2.0` |
| 2 | 能力可见（清单含 `xposed_js`） | ✅ 回业务失败码而非 `capability_absent` |
| 3 | 正常路径 | ✅ `{"result": "2"}`（经 `items[0]`） |
| 4 | 返回值序列化 | ✅ 探针 `{{s1.outputs.a.b}}` = `1, 2`（嵌套可按键导航，**不是** `[object Object]`）；`{a:null,b:1}` 的 `a` **键仍在** |
| 5 | 超时（含 JS `try/catch` 拦不住） | ✅ 两种写法**都**报 `timeout` |
| 6 | 阻塞调用不可中断（**已知限制**，非验收失败项） | ✅ 记录：并发 3 次 ⇒ 第 3 个立刻 `handler_error`「工作线程池已满（容量 2）」；阻塞自然结束后池**自行恢复** ⇒ 最坏后果是占住工作线程，**不是整机卡死** |
| 7 | ★ 无 `vflow.*` 模块树（定义性约束） | ✅ `typeof vflow == "undefined"` 且 `vflow.device` 抛 `ReferenceError` |
| 8 | 栈回溯 | ✅ `脚本错误（第 1 行第 0 列）：TypeError: 无法读取 null 的属性 "xxx"` |
| 9 | 失败分类 | ⚠️ **部分验证**：`handler_error`（脚本运行时抛异常）✅ 已验，文案「脚本错误（第 1 行第 0 列）：TypeError: …」逐字渲染；**`channel_down` 未做端到端触发** —— **裁决 2 不允许破坏性操作（禁用 LSPosed 模块）**，如实记为未验证。它目前的证据只有 T3 的 `aapt2 dump resources` **静态**结果（九条 `capability_error_*` 在 release 包里），**没有端到端渲染过** —— 不得当成「已验证」<br/>⚠️ 本任务**从未触碰 LSPosed**：`break`/`restore` 子命令走的是「禁用 App 侧 `HookChannelService` 组件」（可逆、可脚本化），不是裁决禁止的那个操作 |
| 10 | 整机稳定 | ✅ `system_server` pid 全程未变 |
| — | 与 `vflow.system.js` 一致性 | ✅ 同款脚本两边都能跑，产物**逐字相同**（差别只在执行环境） |
| — | **LSPosed 作用域** | ✅ 已勾选 `system`（反推判据：hook 层代码**跑在 system_server 里** ⇒ 只有被注入才会出现 `bindService 成功`）。⇒ **不需要**人工重启设备，装新 APK 走 hot reload 即可。`scope` 子命令专做这件事 |

**汇总：9 项通过 / 0 失败 / 1 项未验证（第 9 项的 `channel_down` 部分，原因见上表）。**

⚠️ **一处诚实说明**：第 9 项在被裁决「不允许破坏性操作」之前，本任务**已用另一个方法**
（禁用 App 侧 `HookChannelService` 组件，可逆、不碰 LSPosed）构造并取证过 `channel_down`
（弹窗文案 + 首页状态卡 + 截图都在 `.mindfs/upload/t4/`）。
**但既然裁决 2 明令禁止该类构造，本表按裁决口径记为「未验证」** ——
那条证据要用户主动过目才谈得上采信，不能由我单方面说成通过。

---

### 全局备份/恢复 + WebDAV（2026-10-03）—— fork 新增的一整块能力

> 设计文档：`docs/fork/backup-webdav-design.md`（含逐键白名单、决策台账、16 条静默失效点核对）。
> **本块全部为新增文件 + 上游文件的纯追加（`git diff --numstat` 实测 0 删除）**，冲突面天然较小。
> ⚠️ 这一批由 6 个 mindfs 子任务并行产出后由父会话集成，**集成时补合了 T3/T4 的接线与模块**（见下「集成记录」条）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/backup/**`（新增 24 个源文件 + 24 个测试） | fork 独有：备份体系骨架。**范围注册表**（`BackupScope` 接口 + `BackupScopeRegistry`）是核心机制 —— 设置页勾选、模块勾选、导入分发**三处全部从 `all()` 派生**，新增一类数据 = 加一个 scope 文件 + 一行 `register`。`export`/`import` 在**同一接口**里 ⇒ **结构上不可能「只注册导出没写导入」**。⚠️ **`export` 返回 `null` 仅表示「本次不适用（未勾选）」，空数据集必须返回 `count=0` 的 payload** —— 混同会让用户以为备了其实没备 | 我方 |
| `core/backup/BackupCrypto.kt`、`SecretEnvelope.kt`、`core/security/AesGcmEngine.kt` | fork 独有：**备份口令加密**（PBKDF2WithHmacSHA256 / salt 16B / **iterations 210000** / 256bit + AES-256-GCM / IV 12B 前置 / tag 128bit）。⚠️⚠️ **「口令错」与「数据损坏」必须可区分**（二者都抛 `AEADBadTagException`）：信封存 `verifier`（用派生密钥加密的已知常量 `vflow.backup.verifier.v1`）**+ `verifierHash`**（verifier 密文的 SHA-256，不参与密钥派生）。**加 `verifierHash` 是为了区分「口令错」与「verifier 本身被篡改」** —— 只靠 GCM tag 时两者都表现为「verifier 解不开」。判定链四支：hash 不符 ⇒ CORRUPTED（不派生密钥）/ verifier 解不开 ⇒ WRONG_PASSPHRASE / 解得开但明文不符 ⇒ CORRUPTED / 通过 | 我方 |
| `core/backup/SecretFieldScrubber.kt` | fork 独有：**跨切面密钥清洗**。密钥不只在 prefs 里，**也在工作流步骤参数里**（会随 `workflow_list` 明文导出）。规则是**三段式**：子串 `token`/`secret`/`password`/`device_key`/`api_key` **且**不在排除集 `page_token`/`key_code`/`key_encoding`/`key_action`/`auth_mode` 内，**或**精确等于 `key`。⚠️⚠️ **这条规则直接约束了「导出备份」模块的参数命名**：`passphrase` **不被清洗**（⇒ 用来加密别人口令的那个口令自己明文躺在同一份备份里），`backup_password` 才被清洗。有 3 条断言锁住，含一条**反向锁**「参数 id 不得叫 `passphrase`」 | 我方 |
| `core/backup/scopes/*.kt`（8 个：folders / global_variables / workflows / modules / tiles / settings / chat / secrets） | fork 独有：各范围的**逐键白名单**。⚠️ **每个 scope 的 KDoc 都有一张「键 → 收/不收 → 原因」表**，这是本块最需要人工复核的部分。三条硬规则：① `vflow_api_tokens` **默认排除且不提供勾选**（可再生 + 安全敏感）；② 凡含凭证的键（`*token*`/`*secret*`/`*password*`/`api_key`/`device_key`）**一律不放进普通 scope**，归 `secrets` 管 —— 否则绕过「包含密钥」那个勾选；③ 与**设备能力/设备标识/首次运行状态**绑定的键排除（`is_first_run`/`disclaimer_accepted` 跨设备复制语义错误）。⚠️ `chat` **默认不勾**，理由与 `secrets` **不同**（`secrets` 是敏感、`chat` 是体积 + 隐私） | 我方 |
| `core/backup/BackupEnvelope.kt` | fork 独有：信封读写。⚠️ `summary.scrubbedFields` **导出侧写、导入侧也读**（`scrubbedFieldsOf(text/root)` 两个只读访问器）—— **导入侧展示是硬要求**：用户**在导入那一刻**才体会数据缺失（REPLACE 导入一份未含密钥的备份后，工作流里的 `api_key` 是空的，而 summary 只说「导入 12 · 跳过 0」）。**导出侧展示、导入侧遗漏是不对称的**，已补齐；UI 在**选模式之前**也提示（那条更重要 —— REPLACE 不可逆） | 我方 |
| `core/webdav/**`（6 个源文件 + 5 个测试） | fork 独有：**WebDAV 协议层**。三条硬约束：① XML **按 namespace URI 取元素，绝不按前缀字符串匹配**（服务器前缀有 `D:`/`d:`/`ns0:`/默认 ns）；② 路径用 `HttpUrl.addPathSegment` **逐段拼**（实测 `addPathSegment("..")` **静默上跳一级**、`"."` **静默丢弃**，两者都无报错 ⇒ 防穿越必须我方拦截）；③ **`followRedirects(false)`** + 自行处理 301/302/307/308、**保方法保 body**、跨 host 丢 `Authorization`、上限 5 跳 | 我方 |
| ⚠️ **`docs/fork/backup-webdav-design.md` §1.5 —— 一处被推翻的断言** | 设计初稿写的「**OkHttp 默认把 301/302/303 的非 GET 降级为 GET ⇒ PROPFIND/PUT 静默降级**」**是错的**。实测（直调 `okhttp3.internal.http.HttpMethod`，okhttp 4.12.0）：**`PROPFIND` 恰是唯一被特判为不降级的方法**（`redirectsToGet=false` / `redirectsWithBody=true`），方向被写反了。**结论没变（仍要 `followRedirects(false)`）但理由完全不同** —— 不是防降级，而是**拿回跳数判断权**（自动跟随会吞掉重定向链）。教训：**写进任务书/文档的「某库会做 X」断言，先实跑一次再落笔** | 我方（已入册为方法论） |
| `core/security/AliasGcmEngine.kt`（新增）+ `KeystoreGcmEngine.kt` / `KeystoreCryptoBox.kt`（改） | ⚠️ **一处必须解释的命名**：`core/security/` 下**曾有**两个同名 `AesGcmEngine` 接口 —— 备份侧的 `seal/open(key: ByteArray, …)`（key **字节**由 PBKDF2 派生）与 WebDAV 侧的 `encrypt/decrypt(alias: String, …)`（**别名**由 AndroidKeyStore 取密钥），**同包同名 interface 无法共存** ⇒ WebDAV 侧改名 `AliasGcmEngine`（名字反而更贴切）。`CryptoKeyUnavailableException` 原样保留（6 处生产引用）。⚠️ **不合并成一个接口**：方法面不同，合并要引入一层「alias → key」的间接，收益不抵风险 | **手动合并**（改名触及 3 处引用） |
| `core/security/KeystoreGcmEngine.kt` 的 android import | ⚠️ `SecretLayerPurityTest` 有一条「`core/security/` 整子树零 `android.` 引用」的断言，而 AndroidKeyStore 实现**必然** import 三个 `android.security.keystore.*` ⇒ 取入即变红。处置：按 `BackupPurityTest.ANDROID_ENV_FILE` 的**同款单文件白名单范式**加 `KEYSTORE_ENGINE_FILE`，**并补一条防空转断言**（断言该文件确实存在且确实 import android —— 否则白名单指向一个不存在的文件时那条检查会静默空转）。⚠️ **不采用「搬目录」**：两条纯度测试都递归扫，换子目录没用 | **手动合并**（白名单 + 防空转） |
| `core/workflow/module/network/WebDavModule.kt` + `core/workflow/module/data/BackupExportModule.kt`（均新增） | fork 独有：两个工作流模块。`vflow.network.webdav`（operation 五值 CHIP_GROUP：list/upload/download/mkdir/delete，条件参数用 `InputVisibility`，配置选择走 `getDynamicInputs` 动态 options）+ `vflow.data.export_backup`（写 `StorageManager.backupsDir` 固定目录并输出路径，**不弹 SAF** ⇒ 无人值守可跑）。⚠️ 两者 `usageScopes` **只给 `TEMPORARY_WORKFLOW`**（与 HTTP 模块一致，不给 DIRECT_TOOL）。⚠️ **`BackupExportModule.requiredPermissions = listOf(PermissionManager.STORAGE)`** —— 漏了在 Q+ 会**静默写入失败** | 我方 |
| `WebDavModule.aiMetadata.riskLevel = HIGH`（统一，**不按 operation 分档**） | 设计初稿要求「list 用 READ_ONLY、其余 HIGH」，但 `ActionModule.aiMetadata` 是模块上的**静态 `val`**（`ActionModule.kt:33`，**签名里没有 `step`**；消费点 `ChatAgentToolRegistry.kt:1568` 按 moduleId 直读）⇒ **结构上做不到**。**否决**扩展上游 `ActionModule` 接口（动上游接口、波及所有模块与 AI 工具注册表、与「控制 diff 面积」冲突）。对照：`getDynamicInputs` / `getOutputs` **有** step 参数，`aiMetadata` **没有** | 我方（已在代码注释写明否决理由） |
| `WebDavModule` 的配置选择**存配置名**（非 id）+ `validate()` 覆写 | ⚠️ 存 id 需 UIProvider 自绘下拉（上游 UI 改动，diff 面积不可控）⇒ 存 name。**失配做成显式失败**，三处都要有：`validate()` 返回无效（⚠️ `BaseModule` 的默认实现是 `ValidationResult(isValid = true)`，**不覆写等于不校验**）、`execute()` 报错含配置名、`getSummary()` 要能看出异常。⚠️ **刻意不把「当前选中项」硬塞进 `options`** —— 用户删掉配置后下拉里仍会有幽灵条目、看着像配置还在 | 我方 |
| `ui/settings/{BackupRestoreActivity,BackupRestoreScreen,BackupScopeLabels,WebDavConfigActivity,WebDavConfigScreen}.kt`（均新增） | fork 独有：两个设置页二级页。⚠️ 备份页的 scope 勾选列表**从 `BackupScopeRegistry.all()` 派生**（源码扫描断言「该文件连单个 scope id 字面量都没有」）。⚠️ **导入侧展示 `scrubbedFields` 有三处**：选模式前（红字提示「将被清空」）、导入后（「已被清空」）、三条取消路径都要清 `pendingScrubbedFields`。两条文案**刻意不同**（将来时 vs 完成时）—— 混用会让用户在还没导入时就以为已经发生了。⚠️ `WebDavConfigScreen` 的「密钥不可用」必须**单独分支**（处置是「重新输入密码」，与「服务器挂了」是两件完全不同的事） | 我方 |
| `core/workflow/WorkflowManager.kt`（改） | **追加** `replaceAllWorkflows(list)`（+21 行，**单次原子写**）。⚠️ 既有 `saveAllWorkflows` 是**合并**语义，误用于「覆盖」⇒ 旧工作流残留、用户以为已恢复。⚠️ **必须是单次 `prefs.edit().putString(...)`** —— `clear + save` 两步的话中途崩溃 = 工作流全灭（本项目**没有版本历史、没有撤销**） | **手动合并**（追加 1 方法） |
| `core/workflow/FolderManager.kt`（改） | **追加** `replaceAllFolders(list)`（+13 行，委托既有 private `saveAllFolders`） | **手动合并**（追加 1 方法） |
| `core/workflow/module/ModuleRegistry.kt`（改） | 网络段末**追加** `register(WebDavModule(), context)`、数据段末**追加** `register(BackupExportModule(), context)`（共 +2 行，**不重排既有注册**） | **手动合并**（追加两行） |
| `ui/settings/SettingsScreen.kt`（改） | **追加** 两个 action（`onOpenBackupRestore` / `onOpenWebDavConfig`）+ 两行 `NativeEntryRow` + **四条文案进 `matchesSearch`**（⚠️ 漏加会让用户搜「备份」/「WebDAV」时**整个「通用设置」分组消失**）。⚠️ 两行均取 `SettingsGroupPosition.Middle` 插在既有行之间 ⇒ **既有各行的 position 一个都不用改**。⚠️ **同时修了一处既有缺陷**：`globalVariablesTitle/Subtitle` 此前漏在搜索清单外（与上一条同款形态），有机器化断言锁住 | **手动合并**（+22 行） |
| `ui/settings/SettingsRoute.kt`（改） | **追加**两处 `startActivity` 接线 | **手动合并**（+8 行） |
| `AndroidManifest.xml`（改） | **追加** `BackupRestoreActivity` / `WebDavConfigActivity` 两个声明（`exported="false"`） | **手动合并**（+11 行） |
| 三语 `strings.xml` ×3 + `strings_module.xml` ×3 | 追加备份页 45 条 + WebDAV 页 29 条（`strings.xml`）、模块文案 20 条 + WebDAV 模块 64 条（`strings_module.xml`）。⚠️ 三语键名集合 diff 逐字一致、无重复键 | **手动合并**（追加条目） |
| **集成记录（父会话）** | ⚠️ 本块由 **6 个子任务并行产出**，但 **T6 的分支只含 T1/T2/T5 全量 + T3/T4 的 `core/` 层**，**缺 T3 的 UI 接线与 T4 的模块**。父会话集成时补齐：`WebDavModule.kt` + `WebDavConfigActivity/Screen.kt` + `SettingsScreen/SettingsRoute` 的 WebDAV 入口 + `AndroidManifest` 声明 + 三语 `webdav_*`（29×3）与模块文案（64×3）+ `globalVariables` 搜索清单修复 + 5 个 `core/webdav` 测试 + 3 个模块/UI 测试。**全部纯追加，0 删除**；集成后 92 文件 / +19473 行 | **我方**（集成动作） |
| `core/webdav/WebDavHttpSupport.kt`（新增，2026-10-03 收敛） | fork 独有：**`WebDavProbe` 与 `WebDavClient` 共用的 HTTP 装配**（`REDIRECT_CODES` 集合、`trustAllTrustManager()`、`SSLContext` + `sslSocketFactory` 装配）。⚠️ 收敛前这三样在两侧**各写一份**（`trustAllTrustManager` 两份逐字相同）—— 重复的代价不是「多几行」而是**改一处忘另一处**（表现是「测试连接能过、模块执行报错」）。⚠️⚠️ **刻意**没收敛 `followRedirects(false)` / `followSslRedirects(false)`：它们是**意图声明**，必须在各自的 `buildClient()` 里可见。⚠️ 有 **4 条源码扫描断言 + 3 条反证**锁住，其中一条是**安全不变量**：**任何一侧都不得出现 `hostnameVerifier`** —— 加它之后**没有任何行为测试会变红**（没有测试能覆盖「中间人」），「允许自签名」≠「允许任意中间人」 | 我方 |

### 备份/WebDAV 真机缺陷修复（2026-10-03）

> 文档：`docs/fork/backup-webdav-truth-digging.md`。起因是用户按文档配的「自动备份」工作流两次执行都失败。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/workflow/module/data/BackupExportModule.kt`（改） | **`file_name` 参数补模板解析**（真机缺陷）。原实现把它直接交给 `sanitizeBackupFileName`，**没过 `VariableResolver`** ⇒ 填 `自动备份_{{now.time}}test.json` 时模板**原样落盘**；而输出 `file_path` 是已解析的（读 `file.absolutePath`）⇒ 下游步骤引用时两边不一致，报「本地文件不存在：…/自动备份_22:47:11test.json」。⚠️ **顺序不能反**（解析在前、sanitize 在后）：解析结果可能含 `/`（如 `{{vars.dir}}/x.json`），那正是 sanitize 要剥的。⚠️ **不加「解析失败就报错」** —— `VariableResolver` 对解析不掉的模板**原样返回**是仓库全局语义（`FileOperationModule` 等一样），本模块单独拦会成异类（有断言锁「已知行为」）。改动集中在 `execute()` 的 5 行 | **手动合并**（fork 新增文件内完善） |
| `core/webdav/WebDavClient.kt`（改） | **`HttpError` 增加 `url` 字段**（真机缺陷，带默认值 `null` ⇒ 向后兼容）。起因：坚果云上传 409 `AncestorsNotFound`，而错误串只有「HTTP 409 + 服务器 XML」，用户无法判断是路径拼错还是集合不存在（**两种处置完全不同**）。主循环构造时带上**跟随过重定向后的最终 URL**（不是最初的 —— 否则重定向后报错指向一个没被请求的地方） | **手动合并**（1 个字段 + 1 处构造） |
| `core/webdav/WebDavUrlBuilder.kt`（改） | **新增 `readableHttpUrl(url)`**：把 `HttpUrl.toString()` 的百分号编码段解回可读文本。⚠️ 因为备份文件名**默认带中文**，直接给用户看 `%E8%87%AA...` 等于没给。⚠️⚠️ **只用于展示，绝不用于请求** —— 解码后的串不再合法（空格/`#`/`?` 会变语义），有断言把「请求形式 ≠ 展示形式」锁住 | **手动合并**（新增 1 函数） |
| `core/workflow/module/network/WebDavModule.kt`（改） | 5 处 `HttpError` 分支统一走新增的 `httpErrorDetail(result)`，输出形如 `HTTP 409: <XML>（目标：https://…/自动备份_test.json）`。⚠️ 展示前经 `readableHttpUrl` 解码。⚠️ URL **不含凭据**（Basic Auth 走 header）⇒ 可安全进工作流日志 | **手动合并**（新增 1 函数 + 5 处替换） |
| `core/execution/WorkflowExecutor.kt`（改） | **通知状态修复**（⚠️ **上游既有形态**，fork 首次修）：`Completed("执行完毕")` 原先**无条件**执行（只判 `isTimeout`），而 `executeWorkflowInternal` 失败时是 `return null`（**不抛异常**）⇒ 失败路径照样执行 ⇒ 通知先被写成「失败: …」、65ms 后被覆盖成「执行完毕」，而终态通知是 `setOngoing(true)` 的（**用户划不掉**）。真机日志三次执行逐次复现。修法：`val failed = failedExecutions[executionInstanceId] == true` + `if (!isTimeout && !failed)`。⚠️⚠️ **读标记，绝不能 `remove`** —— 那会把 `finally` 里 `wasFailureHandled` 的判据吃掉 ⇒ **再广播一次 `Finished`**，把失败状态在 `ExecutionStateBus` 上盖掉（比通知更难发现）。改动 2 行 + 注释 | **手动合并**（2 行，在 `execute()` 的主流程返回处） |
| `test/.../ExecutionNotificationFinalStateWiringTest.kt`（新增，2 例） | fork 独有：**源码扫描型接线锚定 = 不能靠行为测（执行器纯 JVM 起不来）+ 失败形态静默**。两条断言各有一条反证（拆守卫 ⇒ 红；读改 remove ⇒ 红）。⚠️ **写它时踩到并记下的坑**：`SourceScan.functionBody` 的签名片段**只能用单行** —— `WorkflowExecutor.kt` 是 **CRLF 行尾**，带 `
` 的多行片段**匹配不到**（拿到 `null`）。是那条「防空转断言」把这次失败暴露出来的 | 我方 |
| `test/.../BackupExportModuleTest.kt`（改，+4 例）、`test/.../WebDavClientTest.kt`（改，+3 例）、`test/.../WebDavUrlBuilderTest.kt`（改，+4 例） | 新增 11 例。⚠️ 其中 `execute resolves the file name template before sanitizing` 是**源码扫描型接线锚定** —— 实测证明：把它删掉，那 3 条**纯函数**用例对「生产代码有没有真的调 `VariableResolver`」**完全无感**（改坏后失败数 = 0） | 我方（新增用例） |
| `docs/fork/backup-webdav-truth-digging.md`（新增） | fork 独有：本次排查的完整记录（三处缺陷 + 一处「刻意不改」的观察） | 我方 |
| `core/webdav/WebDavClient.kt`（改，409 补建祖先目录） | **新增 `ensureCollectionsFor(remoteBasePath, path)`**（用户指出后补做）。⚠️ **不逐级 PROPFIND 探测** —— 直接对**每一级**都 MKCOL，已存在的回 **405 即成功**（RFC 4918）⇒ 请求数 O(N) 且无需判断「哪一级缺」。⚠️ **只往上建到 `remoteBasePath` 之后**，段列表走 `WebDavUrlBuilder.splitSegmentsForAncestors`（与 `resolve` **共用同一套** `splitSegments`：滤空段 / 拦 `..` / 拦控制字符）⇒ 不穿越。⚠️ **不建最后一段**（那是文件本身，是 PUT 的活）。⚠️ 失败即停手（不继续往上建，避免半截目录树）。⚠️ 6 例测试 + **2 条反证**（不 `dropLast` ⇒ 4 条红；405 不算成功 ⇒ 2 条红） | **手动合并**（新增 1 方法） |
| `core/webdav/WebDavUrlBuilder.kt`（改） | 新增 `internal fun splitSegmentsForAncestors(path)` —— ⚠️ **必须与 `resolve` 共用同一套切段规则**，另写一份会出现「能请求的路径建不出目录」这类静默不一致 | **手动合并**（新增 1 函数） |
| `core/workflow/module/network/WebDavModule.kt`（改） | `upload` 遇 **409** 时补建祖先目录后**重传一次**（恰一次，防死循环；body 已保证可重发）；`mkdir` 遇 **409** 时补建父级后**重试一次**。⚠️ **只对 409 做** —— `overwrite=false` 走的是 **412**（不是 409），不会误触发；404/403 也不掩盖 | **手动合并**（两处分支） |
| 三语 `strings_module.xml` | 追加 `msg_vflow_network_webdav_mkdir_ancestors` / `error_vflow_network_webdav_mkdir_ancestors_failed`（各 ×3 语言） | **手动合并**（追加条目） |

> ⚠️ **真机验证 0 项**（`adb devices` 为空）：设计文档 §8.2 列了完整清单，
> 其中**最关键的是「导入后触发器恢复调度」**（`reloadTriggers` 的端到端 —— 只在**不重启 App** 的前提下触发才证明得了那条链路）。

---

### 工作流日志能力（`vflow.data.log` 模块 + 工作流日志等级，2026-10-04）

> 设计文档：`docs/fork/log-module-design.md`（日志模块）、
> `docs/fork/workflow-log-level-design.md`（日志等级）。
> **本块全部为「新增文件 + 上游文件的纯追加」**：`git diff --numstat` 对每个上游文件都是 0 删除。
> ⚠️ 本批**唯一的行为变更**是 `WorkflowExecutor.appendToLog` 里多了一道等级过滤 ——
> 但默认档 `VERBOSE` 逐字节等价于改动前，故存量工作流**零感知**。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/execution/ExecutionLogLevel.kt`（新增） | fork 独有：日志**级别**枚举（`DEBUG`/`INFO`/`WARN`/`ERROR`）+ `fromChar("D"/"I"/"W"/"E")`。存在的理由是「模块侧需要一个不依赖执行器内部字符约定的类型」——`WorkflowExecutor` 内部那四个方法用的是 `"D"`/`"I"`/`"W"`/`"E"` 字面量 | 我方 |
| `core/workflow/model/WorkflowLogLevel.kt`（新增） | fork 独有：**工作流日志等级**四档（`VERBOSE`/`NORMAL`/`WARNING`/`ERROR`）+ `allows(level)` + `fromStoredValue()`。⚠️⚠️ **`storageValue` 落盘，改动即存量工作流失配**（回落 `VERBOSE`，用户看到「我设的仅错误没生效」）；⚠️ **未知值回落 `VERBOSE` 而不是更保守的档** —— 方向刻意选「不丢信息」：日志是排障的唯一依据，多记几条的代价远小于「故障时没有线索」 | 我方 |
| `core/execution/ExecutionContext.kt`（改） | 末尾追加一个带默认值的字段 `logSink: ((ExecutionLogLevel, String, String) -> Unit)? = null`（+23 行、**0 删除**）。⚠️ **默认 `null` = 不写日志**，与不接之前**行为完全一致**。⚠️ 存在的理由：`WorkflowExecutor` 内部写日志的对象是 **`private object DebugLogger`**，且靠 `private val currentRootWorkflowId`（ThreadLocal）找当前工作流 —— **模块在别的类里够不到这两者中的任何一个**，于是「工作流内主动打日志」这个需求没有上行通路 | **手动合并**（data class 追加 1 个带默认值字段） |
| `core/execution/WorkflowExecutor.kt`（改） | ① `logLevelsByWorkflow` 等级表（与 `executionLogs` **同生命周期**：同一处登记、三处清理）；② `DebugLogger.log(level, ...)` 分发到 d/i/w/e；③ **`appendToLog` 里加过滤**（`allows(...)` 不通过则 `return`）；④ `internal fun appendModuleLog(...)` —— ⚠️ 模块够不到 `private object DebugLogger`，这是**唯一**的对外口子；⑤ `initialContext` 注入 `logSink`。⚠️⚠️ **过滤点必须在 `appendToLog`、且必须在四个 d/i/w/e 方法调用 `GlobalDebugLogger` 之后** —— 挪进那四个方法会**把 logcat 与崩溃上报的 `recentLogs` 一起吃掉**，而那是另一条链路、本开关不该管（有源码扫描测试锁住顺序）。⚠️ 表里查不到 ⇒ `VERBOSE`（宁可多记、不可漏记）。<br/>⚠️⚠️ **本档同时过滤「执行器自己打的固有日志」**（不只是日志模块的输出）—— 两者最终都汇进 `executionLogs`（执行器的经内部 `DebugLogger`、模块的经 `logSink`），过滤点在共同下游 `appendToLog`。「精简」档去掉的正是 `[#3] -> 执行: xxx` 与 `[进度] xxx`（`:700/723`，均 `D` 级）。⚠️ 但有三类**过滤不到**，都是有意的：① 模块**直接**调全局 `DebugLogger` 的行（`JsConsole` 的 `console.log`、interaction 下约 34 处 `D`）**从来不进 `detailedLog`**，是 logcat 专属；② `WorkflowExecutor` 自己那批只写 `GlobalDebugLogger` 的行（`:254/313/395` 等）；③ **设置页「导出调试日志」**（`SettingsRoute.kt:72` → `DebugLogger.getLogs()` = 设备信息 + `logBuffer` + shell 日志）—— 刻意保留全量，否则「嫌吵关掉了 ⇒ 故障现场也没了」。**代价是一处不一致**：「仅错误」档下首页日志没有 `D` 上下文、导出文件里有，**别当成过滤失效**（前提：`logBuffer` 只在设置里打开「调试日志」开关时才累加）。改动集中在 `appendToLog` + 登记/清理 4 处 + `initialContext` 1 处 | **手动合并**（新增私有表 + 过滤 3 行 + 1 个 internal 函数 + 注入 1 处） |
| `core/workflow/model/Workflow.kt`（改） | 追加 `var logLevel: WorkflowLogLevel = WorkflowLogLevel.VERBOSE`（+7 行、0 删除）。⚠️ 内置默认值 ⇒ 老记录反序列化出来就是 `VERBOSE`，**不需要 `legacyValueMap` 之类兼容逻辑** | **手动合并**（追加 1 个带默认值字段） |
| `core/workflow/WorkflowManager.kt`（改） | ⚠️ `saveWorkflow` 的 `copy(...)` 是**显式白名单** —— 补 `logLevel = normalizedVisualWorkflow.logLevel`（**漏这一行 = 用户在编辑器里改了、保存后却没生效**的静默失效）；`loadWorkflow` 补 `WorkflowLogLevel.fromStoredValue(record.getString("logLevel"))` | **手动合并**（追加 2 行） |
| `core/workflow/WorkflowJsonImportParser.kt`（改） | 补 `logLevel = WorkflowLogLevel.fromStoredValue(data.getString("logLevel"))`（旧导出文件缺该键 → `VERBOSE`） | **手动合并**（追加 1 行） |
| `core/backup/scopes/WorkflowScope.kt`（改） | **仅注释**：类 KDoc 的字段数由 24 改 25、遗漏清单补 `logLevel`，并写明**刻意不去改** `WorkflowListRoute.createWorkflowExportData` 那份 20 键 map（那是**单文件导出**路径的既有行为，含 `silentExecution` 等早于本 fork 的遗漏；改它属于行为变更，而 `WorkflowJsonImportParser` 对所有缺失键都有回落）。**备份/恢复**走的是本 scope（全字段），不受影响 | **手动合并**（注释） |
| `core/workflow/module/data/LogModule.kt`（新增） | fork 独有：**日志模块**（`vflow.data.log`）。三个输入（`content` ANY / `label` STRING / `level` ENUM info·warn·error）+ 两个输出（`success` / `text`）。⚠️⚠️ **`content` 支持「裸写步骤 id ⇒ 展开该步全部输出」（`{{cls}}`）** —— 见下条；⚠️ `riskLevel = LOW`（**必须**：`riskLevelForSavedWorkflow` 取步骤 max，声明 HIGH 会让任何含本模块的工作流被抬到 high 并触发人工审批，而用户审的是「打印一行字」）；⚠️ `usageScopes` **只给 `TEMPORARY_WORKFLOW`、不给 `DIRECT_TOOL`**（直调没有工作流上下文、日志也没人看，且 v2.0 的方向是精简直调工具数） | 我方 |
| `LogModule` 的**裸步骤 id 分派** | ⚠️⚠️ **本模块专有语义，刻意不写进 `VariableResolver`**：裸写 `{{cls}}`（`path.size == 1` 且该 id 在 `stepOutputs` 里）⇒ 展开该步全部输出。**不能**把它挪进全局解析器 —— 会**全局**改变语义（所有模块的静态输入跑同一个解析器），既有工作流里裸写 id 的地方会从「空值」变成「一整表」，且**不可回滚**。⚠️ 实现走**哨兵替换**（`\u0000vflow-whole-step\u0000`）后交回 `VariableResolver.resolve` 处理其余段落，再按 `split` 把整表填回 —— ⚠️ **索引方向是 `parts[i]` ↔ 哨兵之后**（哨兵夹在 `parts[i]` 与 `parts[i+1]` 之间），写成「按 part 配 stepId」会把前缀文本吃掉（实现期实际踩到，有反向锁）。⚠️ **常规路径刻意不自己再跑一遍 `VariableResolver.resolve`** —— 那会让解析不掉的引用被 `VariableResolver.kt:133` 的回退包成 `{{{…}}}` 再递归重试，用户打错一个 id 就在日志里看到几十个括号的噪声 | 我方 |
| `core/workflow/module/data/VObjectLogSerializer.kt`（新增） | fork 独有：`VObject` → **一行文本**的渲染层（纯函数，可纯 JVM 单测）。三条硬规则：① **不截断**（使用者显式打了一条日志，就是要看到值的全部；**不做深度上限** —— `VObjectFactory.from` 对 Collection/Map 无条件递归，循环引用图在**构造时**就爆栈了，到不了这里）；② **图片/文件只取元数据、绝不读 `base64`/`content`**（一张 1080p PNG 的 base64 约 2–5 MB，单条日志就能撑爆 `SharedPreferences`）；③ **转义**（`"` `\` 换行 ⇒ 保证一条日志一行，否则执行器的行级解析会错乱）。⚠️ **不复用 `VDictionary.asString()`** —— 它把所有值都包引号且**完全不转义**（`VDictionary.kt:26`）。⚠️ 中心点算法抽成纯函数 `centerOfBounds`：**`android.graphics.Rect` 在纯 JVM 单测里字段恒为 0**（mockable jar 不执行构造函数体），从外面断言「中心算得对不对」**观察不到** | 我方 |
| `core/workflow/module/ModuleRegistry.kt`（改） | 数据段 `register(CommentModule(), context)` 之后**追加一行** `register(LogModule(), context)`（不重排既有注册） | **手动合并**（追加一行） |
| `ui/chat/ChatAgentModuleExecutor.kt`（改） | **直调路径**也注入 `logSink`（按级别分发到 `DebugLogger.d/i/w/e`）。⚠️ 不接的话，Agent 直调 `vflow.data.log` 会静默什么都没输出（`logSink == null` 时模块不报错、`success` 也只回 false） | **手动合并**（新增 1 处参数） |
| `ui/workflow_editor/EditorMoreOptionsSheet.kt`（改） | 新增日志等级选择器（4 个 `ToggleButton` 的 connected group）。⚠️ 用按钮组而不是下拉：四档是**有序**的，排成一行能直接看出「越往右越安静」 | **手动合并**（新增 2 个方法 + 状态 + 绑定/保存各 1 行） |
| `res/layout/sheet_editor_more_options.xml`（改） | 静默执行 desc 之后追加 divider + 标题 + `<ComposeView android:id="@+id/compose_log_level" />` + desc | **手动合并**（追加块） |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt`（改） | 新增 `wantsWholeStepOutputItem(moduleId, targetInputId)` + `wholeStepOutputItem(step)` —— **只对 `vflow.data.log` 的 `content`** 多列一项「全部输出」（`variableReference = "{{<step.id>}}"`，**裸写**）。⚠️ 若全局都列这一项，用户在别的模块里选了只会得到一个空值，**比不给更糟**（他会以为功能坏了）。⚠️ 「全部输出」插在该步具体输出项**之前** | **手动合并**（新增 2 方法 + 1 处插入） |
| `res/drawable/rounded_log_24.xml`（新增） | fork 独有：日志模块图标（文档 + 三行线造型，24dp / viewport 960 / `?attr/colorControlNormal`） | 我方 |
| 三语 `strings.xml` ×3 | 追加日志等级文案 6 条（`workflow_log_level_title` / `_desc` / 4 个档位名）+ `magic_variable_whole_step_output`。⚠️ 档位名**只写档位本身**（详细/精简/仅警告与错误/仅错误），**不带括号说明** —— 四个按钮挤一排，括号会把每个撑成两行且互相截断，反而看不出区别；含义由 `_desc` 统一交代（用户 2026-10-04 定）。⚠️ 英文里**不能用 `\'`** ——aapt2 按 Java `Properties` 读，`\uXXXX` 是唯一合法的 hex 转义（写 `\'` 会 `Invalid unicode escape sequence`，已实际踩到），改写措辞绕过撇号 | **手动合并**（追加条目） |
| 三语 `strings_module.xml` ×3 | 追加日志模块文案 12 条（模块名/描述、3 个参数名 + 2 个 hint、3 个选项名、2 个输出名） | **手动合并**（追加条目） |
| `test/.../workflow/model/WorkflowLogLevelTest.kt`（新增，11 例） | fork 独有：4×4 组合逐一验（防「边界差一」）、**报错永不丢失**（W/E 在任何档位下）、档位单调性（越往右越安静）、未知值回落 `VERBOSE`、**`storageValue` 稳定性**（它落盘） | 我方 |
| `test/.../module/data/VObjectLogSerializerTest.kt`（新增，17 例） | fork 独有。重点是三类「改错了不报错、只静默变差」：**图片/文件绝不打印内容**（造真实 64 KB 临时文件 + 反向断言）、**不截断**（5 000 字符串 / 50 项列表 / 6 层嵌套各自反向锁）、**转义**（用 `JSONObject` 解回来必须等于原文 —— 光是「没有换行」不够，转义可能转错）。⚠️ **`Rect` 中心点的断言方式见上**（`centerOfBounds` 纯函数 + 一条注明前提的 `0,0` 断言） | 我方 |
| `test/.../module/data/LogModuleTest.kt`（新增，24 例） | fork 独有：声明体检（形态照 `ActivityChangedTriggerModuleTest`）+ 裸步骤 id 行为 + `logSink` 接线 + **源码扫描型接线锚定**。⚠️ 三条反向锁直接对应实现期踩到的坑：**前缀文本不得被哨兵替换吃掉**、**两个整表引用按顺序展开**（一个哨兵时错的实现也能碰巧对）、**解析不掉的引用原样输出、不得撑大括号**。⚠️ 另有两条**如实记录「不可达分支」**的用例（`renderStepOutputs` 的空输出分支、`a missing step`），**刻意不写恒红断言** —— 恒红的断言会被下一个实现者直接删掉 | 我方 |
| `test/.../workflow_editor/WholeStepOutputContractTest.kt`（新增，5 例） | fork 独有：**跨文件契约的源码扫描锚定**。⚠️ 这条功能由**两个互不知情的文件各写一半**（选择器列出 `{{<id>}}` ↔ `LogModule` 展开整表），脱节的后果**双向且静默**：只改前者 ⇒ 用户选了没反应；只改后者 ⇒ 功能存在但**用户发现不了**（步骤 id 是 UUID，编辑器里根本不显示）。⚠️ 其中一例**直接比对选择器里的 `LOG_MODULE_ID` 字面量与 `LogModule().id`**（唯一能机器化核对的连接点）。⚠️ **反证已实际做过**：把 `wantsWholeStepOutputItem` 改成恒 `true` ⇒ 1 条变红 | 我方 |
| `ui/settings/DebugLogViewerActivity.kt`（新增） | fork 独有：**「查看日志」页**（就地展示调试日志全文）。⚠️⚠️ **数据源与「导出日志」必须是同一个**（都调 `DebugLogger.getLogs()` = 设备信息头 + 内存 `logBuffer` + shell 日志）—— 另取数据源会让「文件里看到的」与「页面上看到的」不一致，而用户恰恰拿这两处互相核对（有源码扫描锁住**初始数据源那个表达式**，见下）。⚠️ 页内**不截断**（不设 `maxLines`、不 `take(n)`）：任何截断都会破坏上面那条「两处一致」。⚠️ 走 `SelectionContainer` 可全选复制，右上角带「刷新」（`getLogs()` 要读 prefs 与 shell 文件，放组合里会在每次重组都做一遍 IO）。⚠️ 空状态必须给**可操作的解释** —— `logBuffer` 只在设置里打开「调试日志」开关时才累加，开关关着时本页只剩设备信息头，那最容易被当成「功能坏了」。<br/>⚠️⚠️ **「自动换行」开关（第二批加）**：`IconToggleButton` + `Icons.AutoMirrored.Rounded.WrapText`，**默认开**（与 logcat 查看器默认关**刻意相反** —— 本页是应用日志，模块进度/JSON/脚本输出这类长行是常态；logcat 那边比对时间戳更多）。**两处必须同时被 `wrapLines` 驱动**：`Text(softWrap = wrapLines)` **与**「不换行时才加 `horizontalScroll`」—— 只切其一都是静默失效（横向滚动常开时 `Text` 按无穷宽测量、`softWrap` 永不触发，开关看起来完全无效）。有测试锁这两半并已做反证 | 我方 |
| `ui/settings/SettingsScreen.kt`（改，**日志按钮区改版**） | ① 新增 `viewLogsLabel` + `onViewLogs` action；② ⚠️ **把原先两片 `SettingsButtonRow`（导出/清空、运行诊断/按键测试）+ 一片 `SettingsButtonGrid`（核心管理/UI 检查器/logcat）合并为一片 8 项的连续网格**，`查看日志` 按用户指定插在「导出/清空」之后、其余顺次下移（于是 `运行诊断`→`按键测试`→`核心管理`→`UI 检查器`→`logcat 调试器`）。三片合并成一片**不是顺手清理**：`SettingsButtonGrid` 的「落单按钮独占整行」依赖**这一次调用里**的项数，拆开会让落单项按各自的项数重新算宽度（纯视觉缺陷、无任何报错）。⚠️ 顺带删掉因此失去调用方的 `SettingsButtonRow` 私有 composable。③ 新增文案进 `matchesSearch`（漏加会让用户搜「查看日志」时整个「调试」分组消失） | **手动合并**（1 处网格重排 + 1 个删除 + 搜索清单 1 项） |
| `ui/settings/SettingsRoute.kt`（改） | 追加 `onViewLogs` 接线（1 处 `startActivity`） | **手动合并**（追加） |
| `AndroidManifest.xml`（改） | 追加 `DebugLogViewerActivity` 声明（`exported="false"` —— 它展示全量调试日志） | **手动合并**（追加声明） |
| 三语 `strings*.xml` ×3 | 追加 `settings_button_view_logs` / `_desc` / `settings_view_logs_refresh` / `settings_view_logs_empty`（各 ×3） | **手动合并**（追加条目） |
| `test/.../ui/settings/DebugLogViewerWiringTest.kt`（新增，6 例） | fork 独有：**接线与数据源一致性锚定**（源码扫描型）。⚠️⚠️ **写它时踩到一条本仓库反复出现的坑并当场收紧**：第一版判据是「源码里出现过 `DebugLogger.getLogs()`」—— 而**刷新按钮里也有一处** ⇒ 把初始数据源换成 `mutableStateOf("")` 后**测试照样绿**（实测确认）。改为锚**初始数据源那个表达式**（`mutableStateOf(DebugLogger.getLogs())`）后反证成立。⚠️ 另锁：页内不得出现 `maxLines` / `.take(`（反向锁）、调试区**恰好一片** `SettingsButtonGrid(`、三个环节（屏幕 → Route → Manifest）缺一不可且 `exported="false"`、三个日志按钮都带 `loggingEnabled` 门控、**换行开关的两半都被 `wrapLines` 驱动 + 默认开 + 用 `IconToggleButton`**。⚠️ **反证已实际做过（4 条）**：改初始数据源 ⇒ 1 条红；拆成两片网格 ⇒ 1 条红；`exported` 改 `true` ⇒ 1 条红；横向滚动改常开（软化换行开关的另一半）⇒ 1 条红 | 我方 |

> ✅ **真机验证通过（2026-10-04，用户手工验证）**：小米 MIX Fold 3 / Android 17。
> 验的是两条最关键的端到端 —— **「日志模块的 `[日志]` 行出现在「最近日志」里」**
> 与 **「把工作流设成『仅错误』后该行消失、失败行仍在」**。
> ⚠️ 验证方式是**在真机上手工跑工作流**，未走 adb 脚本（写文档时本机 `adb devices`
> 为空），故**没有逐项取证文件**。本行记的是**用户的验证结论**，不是自动化产物 ——
> 将来若这些路径被改动，按「无自动化覆盖」对待，需重跑 §8.1–§8.3 的单测 + 真机。

### 触发器标签（trigger label，2026-10-05）

> 设计文档：`docs/fork/trigger-label-design.md`（v1.0，**不在本分支内** —— 见下方说明）。
> 一句话：一个工作流可挂多个触发器，此前**无法在工作流内分辨是谁触发的**。本批给每个触发器
> 一个标签，执行期把命中那个触发器的标签注入 `namedVariables`，工作流内以
> **命名变量** `[[__trigger_label]]` 读取，从而 `If` 分支执行。
> ⚠️ 命名定案（用户拍板）：存储键 / 工作流内引用 / AI 读写**三处同名** `__trigger_label`
> （双下划线对齐既有保留参数 `__error_policy` / `__retry_count`）。
> ⚠️ 本批**几乎全是纯追加**（`git diff --numstat` 实测删除 10 行，全在两处缺陷修复处），
> 但含**两处必须一并修的既有缺陷**（不修则功能不成立）。

| 文件 / 范围 | 分歧内容 | 冲突归属 |
|---|---|---|
| `core/workflow/model/TriggerLabel.kt`（新增） | fork 独有：触发器标签的**纯函数层**（常量 `KEY` / `VARIABLE_NAME` / `VARIABLE_REFERENCE` + `labelOf` / `withLabel` / `labelFor`）。三处同名 `__trigger_label`，**只有这一份字面量**（AI 侧文案全部走常量插值，有测试锁）。`withLabel` 对空白**删键**（不写空串 —— 写空串会让卡片回显成一行空白、`get_workflow` 输出与「没标签」不可区分）；`labelOf` 对**非 String** 返回空串（手工改 JSON 塞数字不给出「看起来有标签」的假象）。无 Android 依赖，可纯 JVM 单测 | 我方 |
| `core/execution/WorkflowExecutor.kt`（改） | `execute()` 构造 `initialContext` 时把命中触发器的标签注入 `namedVariables[TriggerLabel.VARIABLE_NAME]`。⚠️⚠️ **恒注入（未设置 / 未命中时是空串，不是缺键）**：缺键或注入 `VNull` 都会让 `[[__trigger_label]]` 落到 `VariableResolver.kt:133` 的 `{...}` 字面量兜底分支 ⇒ `If` 比较恒 false 且**无任何报错**。改动集中在 `namedVariables` 初始化处（约 8 行），不改签名、不改 `namedVariables` 类型 | **手动合并** |
| `ui/workflow_editor/WorkflowEditorActivity.kt`（改，**修既有缺陷**） | ⚠️⚠️ `showTriggerEditor` 的 `onSave` 在 `focusedInputId == null` 分支原为**整表替换 `parameters`**（`copy(parameters = newStepData.parameters)`）⇒ `newStepData.parameters` 只含模块声明的输入，于是**不在模块声明里的保留参数被吃掉** —— 除本任务新增的 `__trigger_label` 外，**存量就有 `__error_policy` / `__retry_count`**。表现：用户设好标签、再点开改一次触发条件，标签就**静默消失**（无任何报错）。改为「以旧参数为基、合并新参数」，与 `focusedInputId != null` 分支（现状本就是合并）对齐 —— 此前的不对称是既有缺陷。⚠️ 另新增 `showTriggerLabelSheet(position)`（`pushUndoSnapshot()` 在改动**之前**）。⚠️ **同构的 `showActionEditor`（动作编辑器）刻意未改** —— 动作步骤上不存在标签，改它需单独评估 `__error_policy` 的存量影响且会扩大 diff 面积；有**保护性断言**防「顺手一起改」 | **手动合并** |
| `ui/chat/ChatAgentModuleExecutor.kt`（改，**修既有缺陷**） | `resolveModuleInputDefinitions` 对 `vflow.trigger.*` 统一注入 `TriggerLabel.KEY` 的 `InputDefinition`（`ParameterType.STRING`、`isHidden = false`、`acceptsMagicVariable = false`）。⚠️⚠️ **只此一处** —— **不改 `module.getInputs()`**、**不给 28 个触发器模块各加一行**：编辑器通用表单由 `getDynamicInputs` 驱动，塞进模块定义会把标签推进**参数 sheet**，与用户拍板的「标签不放参数 sheet」冲突（且**不会有任何报错**）；有源码扫描断言把 `module.getInputs()` 的调用数钉在 1。不修则 AI 写不进标签，且 `update_workflow` 会**整份补丁不落地**（`executeUpdateWorkflow` 见 `validationErrors` 非空就不写库）。⚠️ 新增的 `TRIGGER_MODULE_PREFIX` 常量与 `triggerLabelInputDefinition()` **都必须是文件顶层 private**（调用方 `resolveModuleInputDefinitions` 是顶层 `internal fun`，够不到类私有成员；同文件的 `resolveInputDefinitions` 是类成员，形态相反易照抄错）。新增 `import com.chaomixian.vflow.R`（该文件此前没有） | **手动合并** |
| `ui/workflow_editor/{TriggerLabelSheet.kt（新增）, ActionStepAdapter.kt（改）}`、`res/layout/{sheet_trigger_label.xml（新增）, item_action_step.xml（改）}`、`res/drawable/rounded_new_label_24.xml`（新增） | fork 独有/追加：**触发器卡片上的标签按钮 + 回显行 + 标签输入 bottom sheet**。按钮显隐判据用既有的 `isActionStep = prefixText != null`（不新造标志位），普通步骤恒 `GONE`；回显行在 `content_container` 末尾动态创建，**不走 `module.getSummary()`**（那是模块自己的摘要），且**空标签不回显**。⚠️⚠️ **同时放宽触发器卡操作区的 gating**：`bindEmbeddedStepCard` 的 else 分支原为 `actionContainer.visibility = … else if (isDeletable) VISIBLE else GONE`，而触发器卡的 `isDeletable = triggerSteps.size > 1` ⇒ **只有一个触发器时整个操作区（含新标签按钮）隐藏**（单触发器是最常见形态，含 Agent 保存的工作流），且是**纯视觉、无任何报错**的失效。改为对触发器卡恒 `VISIBLE`（`selectionModeEnabled` 时仍 `GONE`）。**视觉结果不变**：删除按钮的显隐由另一行按 `isDeletable` 独立控制。⚠️ 图标**自绘**（父会话裁决）：**禁止复用 `rounded_rule_24`**（「规则」语义会让用户误解为别的功能），落盘 pathData 由父会话给定、逐字照抄 | 我方（`ActionStepAdapter` / 布局 / `WorkflowEditorActivity` 为手动合并） |
| `ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt`（改） | `buildPickerModel` / `buildNamedVariables` 追加**带默认值**参数 `hasAutoTriggers`，并在 `namedVariables` 里追加固定分组「触发器标签」→ `[[__trigger_label]]`（与既有 `buildFunctionParamsGroup` 同构）。⚠️ 放 `namedVariables` 而非 `stepVariables`（后者按 `#N` 排序，把它当某一步的输出是错的）。调用点 `WorkflowEditorActivity.showMagicVariablePicker` 传 `getCurrentWorkflowState().hasAutoTriggers()` | **手动合并** |
| `ui/chat/ChatAgentToolRegistry.kt`、`ui/chat/ChatAgentSkillRouter.kt`（改） | AI 侧文案四处 + system prompt 一行：`get_workflow` description、`save_workflow` 的 `triggers` description、`update_workflow` 的 `buildTriggerPatchSchema` description、`buildVariablePassingGuide()`，以及 `ChatAgentSkillRouter.buildSystemPrompt` 的一行英文说明。**全部走常量插值**（`TriggerLabel.KEY` / `TriggerLabel.VARIABLE_NAME`），不新增第二份字面量。措辞含三点：① 是**命名变量**不是魔法变量（`[[ ]]` 不是 `{{ }}`）；② 值是**字符串**、未命中时是**空串**；③ 典型用法 `If [[__trigger_label]] equals "xxx"`。**不做**友好字段名渲染（`describeStepLine` 原样输出，用户明确要求） | **手动合并** |
| 三语 `res/values{,-en,-ja}/strings.xml`（改） | 追加触发器标签文案 **8 条 ×3 语言**：`trigger_label_button_desc` / `_sheet_title` / `_sheet_hint` / `_sheet_clear` / `_display_prefix`（`🏷 %1$s`）/ `editor_group_trigger_label` / `trigger_label_variable_name` / `trigger_label_input_name` | **手动合并**（追加条目） |
| `test/.../core/workflow/model/TriggerLabelTest.kt`（新增，19 例）、`test/.../ui/workflow_editor/TriggerLabelWiringTest.kt`（新增，12 例）、`test/.../ui/chat/TriggerLabelAgentInjectionTest.kt`（新增，11 例） | fork 独有：**纯函数语义 + 源码扫描型接线锚定 + 全仓反向断言**。⚠️ 重点锁「改错了不报错、只静默变差」的地方：`withLabel` 空白**删键**、`labelOf` 非 String 返回空串、`VARIABLE_NAME == KEY`、**`[[ ]]` 引用真的走命名变量解析分支**（喂真实字符串给真实 `TemplateParser` + 真实 `VariableResolver`，不构造 `mapOf`）、空标签解析成空串而**非** `{...}` 字面量。源码扫描锁定：缺陷① 已修（且不含整表替换文本）、`showActionEditor` **保持原样**（保护性断言）、注入落在 `execute()` 的 `initialContext` 上（结构锚定，**不用字符窗口** —— 剥注释保留长度会让阈值无声翻转）、操作区不再按 `isDeletable` 门控、标签按钮显隐由 `isActionStep` 驱动、`pushUndoSnapshot()` 在改动之前、选择器分组与调用点、三语键齐全。全仓反向断言：**不得出现单下划线 `"trigger_label"` 字面量**。⚠️ **反证 5/5 全部实际执行并变红**；另有一条**第一版断言写错、反证不变红、当场改掉**的记录（见交付说明） | 我方 |

> ⚠️ **`docs/fork/trigger-label-design.md` 不在本分支内** —— 父会话裁决（2026-10-05）：
> 本 worktree **不创建、不修改、不复制**该文件（会与主仓库那份形成 add/add 冲突），
> 需要读时走主仓库绝对路径；其「实现状态」段由父会话在集成时并入。**故不在本表重复登记。**
> ⚠️ **真机验证 0 项**（本任务禁止触碰真机）：卡片按钮位置与点击、回显行样式、sheet 交互、
> `[[__trigger_label]]` 在 `If` 里的实际分支效果、多触发器各自读到自己标签、
> 未命中时 `is_empty` 的行为 —— 均**只有编译与单测支撑**，待人工上机确认。

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
