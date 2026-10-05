# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

## Fork 说明

本仓库是 `ChaoMixian/vFlow` 的长期维护 fork（`origin` = nightking8342/vFlow，主干 `dev`；`upstream` = ChaoMixian/vFlow）。与上游的全部分歧、冲突归属规则、上游同步流程和提交规范见：

@FORK.md

改动上游文件前先查 FORK.md 的敏感点清单；产生新分歧时必须同步登记。

**核心原则：控制 diff 面积。能加新文件就不改上游文件，能走新增模块就不动核心代码。** 本 fork 以「新增能力」为主（新增模块、新增 handler、新增脚本），不是深改上游核心。

## fork 文档导航

- `docs/fork/surveys/` —— **现状调研文档目录**（改代码前的地图）。索引见 `docs/fork/surveys/README.md`，含写作规范与候选梳理方向。
  - `docs/fork/surveys/ai-system-overview.md` —— **AI 体系梳理（本项目现状）**：三套独立 AI 链路（聊天 Agent / AI 生成工作流 / 工作流内 AI 模块）、系统提示词组装、技能路由、工具清单与 scope 判定、执行与审批流程、模块可发现性与 catalog 截断、Prompt 缓存现状、能力与现状评估。**改动任何 AI 相关能力前先读这份。**
  - `docs/fork/surveys/agent-design-comparison.md` —— **外部调研**：头部 Agent 项目（Claude Code / Hermes Agent / Codex CLI）的工具暴露、渐进式披露、上下文压缩与缓存做法，及对 vFlow 的启示。**做 AI 能力优化时作为外部参照。**
- `docs/fork/chat-agent-enhancement-plan.md` —— **Chat Agent 四点改造方案**（技能目录化 / Prompt 缓存 / catalog 全量化 + 模块查询工具 / 悬浮窗）。**做 AI 增强前先读这份**，它含实施顺序与依赖关系。
- `docs/fork/chat-agent-rearchitecture.md` —— **Chat Agent 架构重构设计**（基于 CCB / dsh / OpenCode / Pi **四家**源码对照）。含三病症诊断、四家技能注入位置与工具暴露策略对照、目标架构（**工具 72→16**：撤 59 个模块工具，改用 `query_module_schema` + `call_module` + `load_skill` 按需）、**查询域≠调用域**（设计 B）、改造点依赖排序、**新文件接管策略**（规避上游冲突）。**做大改前先读这份**；它是 `chat-agent-enhancement-plan.md` 的上位文档。
  - **四家源码已克隆到本地 `D:/develop/references/`**（在仓库之外，不进 git；含 ccb / dsh / opencode / pi 四目录 + 索引 README）。上文的路径引用（如 `ccb/src/constants/tools.ts:137`）即相对该目录。**注意**：这四份是 `--depth 1` 浅克隆、锚定在特定 commit，行号会随上游漂移，核对方式见 `D:/develop/references/README.md`。
- `docs/fork/chat-float-window-design.md` —— **Chat 悬浮窗需求设计**：折叠/展开双形态、Application 作用域共享 `ChatViewModel`、窗内审批 + 透明中转 Activity、`ComposeView` 在 Service 中的承载。**§9.1 = P0 真机验证结论**（七项，含 3 个必踩的坑）；**§9.2 = P1 实现状态**（当前可用实现、动画失败的实测结论）。**做悬浮窗功能前先读这份。**
- `docs/fork/chat-float-window-ui.html` —— Chat 悬浮窗**可交互 UI 原型**（浏览器打开）：折叠/展开/审批/输入/状态一致性五组演示，配套上文的交互对齐稿。
- **已实现**：`ui/chat/ChatFloat{WindowService,PanelContent,Summary,WindowLauncher,Geometry}.kt` + `ChatViewModelHolder.kt`（P1 折叠态已可用）。Caveat：`ChatFloatGeometry` 及其单测目前仍在使用（Service 的锚定计算），暂勿删除。
- `docs/fork/function-workflow.md` —— 函数工作流功能的需求文档 + 实现状态/交接（含真机测试场景）。
- `docs/fork/backup-webdav-design.md` —— **全局备份/恢复 + WebDAV 设计 + 实现状态**（**已实现；真机验证未做**）。八个备份范围（folders/global_variables/workflows/modules/tiles/settings/chat/secrets）由**范围注册表**驱动，设置页勾选、模块勾选、导入分发三处均从 `BackupScopeRegistry.all()` 派生；密钥走**用户口令加密**（PBKDF2 210k + AES-GCM，可跨设备解密）；WebDAV 配置转档进 `secrets`。含 **§1.4 三段式清洗规则**（决定「导出备份」模块参数 id 必须叫 `backup_password`）、**§1.5 一处被推翻的 OkHttp 断言**（PROPFIND 恰是唯一不降级的方法 —— **写进文档的库行为断言先实跑再落笔**）、§6 的 16 条静默失效点逐条核对、§7 的 22 项决策台账、§8.2 未做项清单。**改动备份/WebDAV 前先读这份。**
- `docs/fork/fold-trigger-design.md` —— **折叠屏触发器设计 + 实现状态**（`vflow.trigger.fold`，**已实现并真机验证可用**）。核心决策：做「状态推断」而非事件监听；**以铰链角度为主力信号**（真机实测 `device_posture` 滞后 1–2 秒且半折值几乎不出现，不能作主力）；阈值经实测校准（折叠 <40° / 展开 >150° / 半折 40–150°）；输出 6 项魔法变量供下游引用。
  - **§9 = P0 真机验证结论**（小米 MIX Fold 3）：推翻了一处外部调研的悲观结论（铰链传感器实测双向完整上报，非「只在折叠方向」），并发现两个调研未提及的新问题。
  - **实现文件**：`FoldTriggerModule` / `FoldStateResolver`（纯函数状态机，可单测）/ `FoldTriggerHandler`。
  - ⚠️ §8.1 记录一处**既有缺陷**：关闭后台服务通知会使 `TriggerService` 降级，**所有传感器类触发器**（本功能、姿态、敲击）都会静默失效 —— 排查「触发器不触发」时先查这个。
  - 跨机型复测用 `scripts/fold-trigger-verify.sh`（adb 零代码）。

---

## 项目定位

vFlow 是一款 Android 端可视化自动化工具。核心价值：把手机上的「点击、识别、判断、系统操作」串成可配置、可自动触发的工作流。

- **技术栈**：Kotlin 2.3.20、Compose（Material 3）、Gradle。
- **两个模块**：
  - `:app` —— 主应用（UI 层 `com.chaomixian.vflow.ui.*` + 业务逻辑层 `com.chaomixian.vflow.core.*`），约 624 个 Kotlin 文件。
  - `:core` —— vFlow Core 独立进程（`com.chaomixian.vflow.server.*`），通过 app_process 以子进程运行做高权限操作，Master-Worker + 本地 Socket。
- **规模**：200+ 功能模块（其中**触发器 24 个**，注册在 `ModuleRegistry.kt` 的「触发器」段），76 个测试文件。
- **版本**：`versionName = "1.5.4"`，`compileSdk 36` / `minSdk 29`。

---

## 常用命令

> **⭐ 打包一律用 release。** 本项目**不使用 `assembleDebug` 作为交付产物** —— 需要装到真机或分发时，统一 `./gradlew assembleRelease`。
> 原因：debug 构建复用 release 签名（见 `app/build.gradle.kts:41-49`），产物行为与 release 一致，但没有 R8 混淆/资源压缩，
> 体积与运行时表现都不代表真实交付形态。**用 release 构建验证，才能暴露混淆（ProGuard）相关问题。**

> **⚠️ worktree 场景：三个文件不在 git 里（不是两个）。**
> `vFlow.jks`、`signing.properties`、**`local.properties`** 都在 `.gitignore` 中、**未纳入版本控制**
> （`git ls-files --error-unmatch <文件>` 对三者均返回非零）。因此新建的 worktree（`git worktree add`）
> 里**三个都不会有**，后果各不相同：
>
> | 文件 | 缺了会怎样 | 表现 |
> |---|---|---|
> | `vFlow.jks` + `signing.properties` | 构建**静默降级**、改用 AGP 默认 debug 签名 | 日志出现 `⚠️ Release 签名文件未找到`；产物装不上已装正式版的设备 |
> | **`local.properties`** | ⚠️ **`./gradlew test` 直接跑不起来** | `BUILD FAILED` / `SDK location not found` —— **不是测试失败，是根本没开始跑** |
>
> **开工第一件事：三个都从 `dev` 分支的工作区取。**
>
> ```bash
> # 在 worktree 里执行；把 <主仓库路径> 换成本地 dev 分支工作区的路径
> cp <主仓库路径>/vFlow.jks .
> cp <主仓库路径>/signing.properties .
> cp <主仓库路径>/local.properties .
> ```
>
> ⚠️ **`git worktree add` 会把 HEAD 置为 detached**（不挂任何分支），
> 于是提交完成后 `git push` 不知道往哪推。两条正路（**都要显式指定分支名**）：
>
> ```bash
> # 甲：临时挂一个分支（推荐 —— worktree 里 `git status` 也会显示 ahead/behind）
> git switch -c feature/<名字>
> # 乙：完全不建分支，直接在 detached HEAD 上推
> git push origin HEAD:refs/heads/<目标分支>
> ```
>
> ⚠️ **别用 `git push origin dev`** —— 在当前仓库里它会被解析成 refspec，
> 推的是**本地那个 `dev` 分支**，而不是你刚提交的 worktree HEAD。表现是
> 「push 说成功，但远端内容没变」（已实际踩过：`git push origin dev` 报
> `Everything up-to-date`，而 worktree 里的新提交一个都没上去）。
>
> 注意：`git checkout dev -- vFlow.jks` 之类的做法**不行** —— 文件未被跟踪，git 里没有这个对象。
> 必须从文件系统复制。取到后用下面的命令确认签名者是否为 `CN=vFlow Fork, O=nightking8342`。
>
> ⚠️ **基线数字要按 worktree 重取**：worktree 基于的 commit 不同，`./gradlew test` 的用例总数也不同
> （实测同一批任务：`dev` 基线 **1356** 例，含未合入改动的工作区可到 **1427** 例）。
> 拿别的分支的数字当基线，会把「变多了」误判成「我改坏了」。

```bash
# 打包（⭐ 默认就该用这个；产物在 app/build/outputs/apk/release/）
./gradlew assembleRelease

# 运行单元测试（app 模块）
./gradlew test

# 只跑某个测试类（注意：聚合的 test 任务不支持 --tests，需用模块级任务）
./gradlew :app:testDebugUnitTest --tests "com.chaomixian.vflow.xxx.TestClass"

# 验证 release APK 签名（应显示 CN=vFlow Fork, O=nightking8342）
"$ANDROID_HOME/build-tools/<版本>/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk

# core 模块单独构建 dex（app 的 preBuild 会自动依赖它）
./gradlew :core:buildDex
```

> `assembleDebug` 仍然可用，但仅限「快速编译检查」这类不关心产物的场景。

**Windows 环境注意**：
- 需要 Android SDK + JDK 17 就绪（`gradle.properties` 里应有 sdk 路径或 `ANDROID_HOME`）。
- 原生 OCR（PP-OCRv5 / ncnn / CMake / JNI）在 `app/src/main/cpp`，涉及 `externalNativeBuild`，需要 NDK/CMake。改到原生代码时构建会慢，谨慎。
- `gradlew.bat` 在 Windows 使用；本说明的命令在 Git Bash 下可直接用 `./gradlew`。

---

## 高层架构

### `app` 模块

```
com.chaomixian.vflow/
├── ui/        —— UI 层。workflow_editor(工作流编辑器)、chat(AI聊天面板)、
│                  settings、overlay(悬浮窗)、main、home 等
├── core/      —— 业务逻辑层
│   ├── module/     —— 模块系统。ModuleRegistry 集中注册所有 ActionModule
│   ├── workflow/   —— 工作流引擎。Workflow / ActionStep / WorkflowExecutor /
│   │                  WorkflowJsonImportParser / WorkflowManager
│   ├── execution/  —— 执行上下文。ExecutionContext / VariableResolver
│   ├── types/      —— 类型系统。VString/VNumber/VImage/VScreenElement/VCoordinate 等
│   ├── logging/    —— 日志
│   └── telemetry/  —— 埋点/崩溃上报(Umeng)
├── api/       —— 本地 Web 服务器(NanoHTTPD, :8080)。REST API + WebSocket(占位)
├── services/  —— AccessibilityService / NotificationListenerService / TriggerService /
│                 VFlowIME / VFlowCoreBridge 等系统服务
├── permissions/ —— 权限管理
├── ocr/       —— OCR(ML Kit + PP-OCRv5)
├── speech/    —— 语音(Silero VAD + Sherpa ONNX)
├── integration/ —— 第三方集成(飞书、GeekZed 等)
└── extension/  —— 外部扩展
```

### `core` 模块（独立进程）

```
com.chaomixian.vflow.server/
├── VFlowCore.kt     —— Master 进程入口(端口 19999)，请求路由
├── worker/          —— ShellWorker(uid 2000) / RootWorker(uid 0)
├── wrappers/        —— Android 系统服务封装(clipboard/input/wifi/bluetooth/power/activity...)
└── common/          —— Config(端口/路由表)/FakeContext/Logger/utils
```

App 与 Core 通过本地 Socket 通信（支持 TCP 和 Unix Domain Socket）。

---

## 模块系统（改代码的重点）

所有能力都通过 **`ModuleRegistry`** 注册成模块。新加能力优先考虑「新增一个模块」，而不是改现有模块。

### 新增一个模块的姿势

1. 在 `app/src/main/java/com/chaomixian/vflow/core/workflow/module/` 下选合适分类（`interaction/`、`logic/`、`network/`、`system/`、`data/` 等）创建模块类。
2. 普通模块继承 `BaseModule`；成对/成块的（If/Loop/UI 容器）参考 `BaseBlockModule` 和现有块模块。
3. 定义稳定的 `id`、`metadata`、`InputDefinition`、`OutputDefinition` 和 `execute()`。
   - `id` 用 `vflow.<category>.<name>` 形式（如 `vflow.interaction.my_op`），**一经发布不要改**。
   - 枚举型参数里保存**稳定常量值**，不要直接存本地化文案。
4. 若参数界面特殊，提供 `uiProvider`；通用表单则直接根据 `InputDefinition` 自动生成。
5. 在 `ModuleRegistry.kt` 的 `initialize()` 中**追加**注册（按分类位置追加一行，**不要重排已有注册**）。
6. 如果模块需要权限、触发器联动、Core/Shizuku 能力、字符串资源或图标，同步补齐，而不只是注册类本身。
7. 若替换过历史上已发布的参数值，在定义层加兼容映射（如 `legacyValueMap`）；全新模块不要预先加无依据的兼容逻辑。
8. 涉及解析、执行、类型或兼容行为变化时，在 `app/src/test/java` 补充单元测试。

#### ⚠️⚠️ 文本参数必须用 `RichTextView`，否则魔法变量**不渲染成胶囊**

**这是本仓库反复踩的坑（截至 2026-10-05 已发生至少三次，最近一次是 Switch 管理 sheet）。**
症状是「用 🪄 选了变量，输入框里显示的还是 `{{step.output}}` 这种底层语法，
而不是一个彩色胶囊」，**不报错、不崩溃**，所以只有真机肉眼能发现，测试全绿也拦不住。

**机制** —— 编辑器里有两套文本输入控件，只有一套支持胶囊：

| 工厂方法 | 产出 | 支持胶囊？ |
|---|---|---|
| `StandardControlFactory.createRichTextEditor(context, initialText, allSteps, tag, hint)` | `RichTextView`（`TextInputEditText` 子类） | ✅ |
| `StandardControlFactory.createTextInputLayout(context, isNumber, currentValue, hint)` | 普通 `TextInputEditText` | ❌ **只显示纯文本** |

自动表单（`createParameterInputRow`）**只在该 `InputDefinition` 声明了
`supportsRichText = true` 时**才走富文本分支；没声明就走 `createViewForInput` → `createTextInputLayout`。
**自绘 UI（`uiProvider` 的 `createEditor`、`BottomSheetDialogFragment` 等）则完全不受
`supportsRichText` 约束** —— 那里选哪个工厂由你写，**没有任何东西会提醒你选错**。

**新增/修改文本参数时逐条核对**：

1. 参数是**文本**（`ParameterType.STRING` / `ANY`）且**接受变量**（`acceptsMagicVariable` 或
   `acceptsNamedVariable` 为真）⇒ **`InputDefinition` 里必须写 `supportsRichText = true`**。
   漏了这条，自动表单会退化成普通输入框，用户从 🪄 选的变量**看起来没生效**。
2. **自绘编辑器里不要用 `createTextInputLayout`** 去做可填变量的文本框 ——
   用 `createRichTextEditor(...)`（或自己组 `TextInputLayout` + `RichTextView`）。
3. **必须把工作流步骤传给编辑器**（`allSteps`，通常是 `getAllEditableSteps()`）。
   不传的话 `PillRenderer` 解析不出显示名 ⇒ 胶囊**退化成原始引用文本**，与没接胶囊长得一样。
4. **读回值一律走 `RichTextView.getRawText()`**，**绝不能拿 `text` / `editText.text`** ——
   那里面装的是**胶囊化的显示文本**（如「上一步 · 结果」），直接存会**静默写坏参数**
   （存进去的不是引用语法，运行时解析不到）。
5. 变量胶囊点一下应该能换/清（接 `onVariablePillEditRequested`），与其它编辑器保持一致。

> ⚠️ `supportsRichText` **只影响自动表单的渲染与读回**（`findValueInViewTree` 里也按它选分支），
> 不影响执行期解析 —— 所以漏了它**只有 UI 上看不出，工作流照样能跑**（如果参数本来就被别处填对的话）。
> 这让它更难被发现。
>
> ⚠️ **这条规则目前没有全仓的机器化守卫，只能靠上面这份清单人工核对**。原因是它**不可廉价判定**：
> 实测全仓有 **79 处** `STRING`/`ANY` 且接受变量、却没声明 `supportsRichText` 的 `InputDefinition`，
> 其中大量是**合法例外**（`packageNames` 逗号分隔列表、`time`、`script` 用自绘编辑器、
> 各种走 `pickerType` / 下拉的参数…）。写成「必须为 true」的断言会**当场全红**，
> 而恒红的断言会被下一个实现者直接删掉（本仓库记过这条教训）。
> ⇒ 唯一的**定点**守卫是 `test/.../logic/SwitchEditorSheetWiringTest.kt`（Switch 专用）。
> **改任何文本参数时，请按上面 5 条自查。**

### 模块元数据（AI 相关）

`AiModuleMetadata` 由模块声明（usageScopes / riskLevel / directToolDescription / workflowStepDescription / inputHints / allowSavedWorkflow）。新增模块若想被 AI 对话面板识别为工具，需设置 `usageScopes`（DIRECT_TOOL / TEMPORARY_WORKFLOW）。

---

## 我该改哪一层？

| 你想做的事 | 应该改哪里 | 注意 |
|---|---|---|
| 新增一个自动化能力 | 新增模块类 + ModuleRegistry 追加注册 | 新增为主，别改核心 |
| 改工作流编辑器 UI | `ui/workflow_editor/` | 上游改动多，控制 diff |
| 加 AI 调试/生成能力 | `ui/chat/` + `ModuleRegistry` 鉴权 | 参考 mindfs 思路「新增文件接管」 |
| 加远程 API | `api/` 新增 handler 文件 | 不要改既有接口签名 |
| 改 Core 能力 | `core/src/main` | 独立 patch，单独评估 |
| 改工作流执行逻辑 | `core/execution/WorkflowExecutor.kt` | 高风险，谨慎 |

---

## 验证门禁

- **改 `app` 业务逻辑 / 新模块**：`./gradlew test`（有 76 个测试文件，涉及解析/执行/类型的改动必须跑）。
  - ⚠️ 已知既有失败：`VObjectPropertyTest > test VFile properties from absolute path` 因 `android.net.Uri.parse` 未 mock 而失败（纯 JVM 测试环境限制，与业务改动无关）。**判断"我是不是改坏了"时先排除它。**
- **改 Core**：`./gradlew :core:buildDex` + 真机验证（Core 是独立进程，单测覆盖有限）。
- **改前端 UI**（Compose）：构建 `./gradlew assembleRelease`（**见「常用命令」——打包统一用 release**），跑真机/模拟器看效果。
- **改原生代码**（cpp）：`./gradlew assembleRelease` 会触发 externalNativeBuild，可能很慢；尽量先确认需要再改。

> **装真机前先确认签名**。若构建日志出现 `⚠️ Release 签名文件未找到`，说明产物**没有用 fork 的签名**，
> 装到已装过正式版的设备上会因签名不一致而失败。处理方式见「常用命令」的 worktree 说明。

### ⛔ 破坏性操作：禁止对「日常使用的设备」执行

> ⚠️⚠️ **这一节是事故后补的（2026-10-03）：曾因此清空过用户设备上的全部工作流与会话记录。**
> 直接原因是 `connectedAndroidTest` 跑完后 AGP 默认会卸载被测包，而
> **工作流与会话都存在 `SharedPreferences` 里**（`WorkflowManager.kt:66` 的 `vflow_workflows`、
> `ChatPresetRepository` 的 `chat_session_prefs`）——**卸载 = 数据全灭，不可恢复**。
> 设备上现在装的是哪个包、谁装的、什么时候，事后**无法从设备侧判断**，只能靠仓库规矩拦。

| ⛔ 禁止 | 为什么 |
|---|---|
| `./gradlew connectedAndroidTest` / `:app:connectedDebugAndroidTest` | AGP 以 `-Dandroid-test.uninstall-after-tests=true` 运行 ⇒ **测试后卸载 `com.chaomixian.vflow`**（实测 `firstInstallTime` 被重置、`/data/data` 被删）。跑一次 = 抹掉全部工作流/会话/设置 |
| `./gradlew installDebug` / `installRelease` | 装的是 **debug** 变体（本项目 debug 复用 release 签名 ⇒ 会**覆盖**正式版），且打断用户正在用的版本 |
| `adb uninstall com.chaomixian.vflow` / `adb shell pm clear …` | 同上，直接抹数据 |
| 任何会**改动设备组件状态**的操作（`pm disable/enable`） | 会让 App 静默失效（`TriggerService` 依赖组件启用态） |

**要跑 instrumented 测试时**：用**模拟器**或**专门的测试机**，或先确认该设备上没有你不想丢的数据。

**装真机验证**（本项目正常流程）只用这一条，它是**更新、不卸载**：

```bash
adb -s <serial> install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

> ⚠️ **`-r` 是保留数据的关键**。签名不符时它会**直接拒绝**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
> **不会**清数据 —— 这是安全的失败方式。装完可用下面这条确认数据没丢（`firstInstallTime` 应**保持不变**）：
>
> ```bash
> adb -s <serial> shell dumpsys package com.chaomixian.vflow | grep -E 'firstInstallTime|lastUpdateTime'
> ```

⚠️ **这条规矩适用于所有 agent 与所有任务，包括被编排的子任务** ——
不要把「要不要碰设备」交给子任务自行判断：破坏性操作的判断依据往往在任务书之外
（本例中子任务正确地想到了「装 debug 会覆盖 release」，但**没想到 AGP 会先卸后装**）。

---

## 代码风格约定

- Kotlin 遵循项目既有风格（Kotlin 2.3.20，JetBrains 默认）。
- 模块命名：`XxxModule`，构造/注册见现有模块。
- 参数 key 用 snake_case（`base_url`、`api_key`、`max_steps`），与现有模块一致。
- 日志用 `DebugLogger.i/d/w/e`（`core/logging`），带模块 TAG。
- 字符串资源集中放在 `res/values/`（含 `values-en` / `values-ja`），新模块的中英文/日文文案都要补。
- 图标资源：新模块需要图标时，参考现有 drawable 命名。

---

## 其它

- **AI Chat 目前不支持流式**（`ChatCompletionClient.kt` 里 `put("stream", false)`）。若要做流式，需改 OkHttp 事件流 + StateFlow 推送，是大改动。
- **WebSocket 是占位**（`WebSocketSupport.kt` 全 TODO），无实时推送。
- **远程 API 需手动开启**「远程 Web 服务」（默认 8080）才可用。
- **AI 调试→生成工作流目前未闭环**：`WorkflowAiGenerator` 只吃 `requirement: String`，不吃调试数据；`artifact://` 句柄不允许写入工作流。这是在 fork 上可做的方向。
- 合并上游前先读 **FORK.md**，尤其「敏感点」和「同步流程」。
