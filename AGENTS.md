# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

## Fork 说明

本仓库是 `ChaoMixian/vFlow` 的长期维护 fork（`origin` = nightking8342/vFlow，主干 `dev`；`upstream` = ChaoMixian/vFlow）。与上游的全部分歧、冲突归属规则、上游同步流程和提交规范见：

@FORK.md

改动上游文件前先查 FORK.md 的敏感点清单；产生新分歧时必须同步登记。

**核心原则：控制 diff 面积。能加新文件就不改上游文件，能走新增模块就不动核心代码。** 本 fork 以「新增能力」为主（新增模块、新增 handler、新增脚本），不是深改上游核心。

## fork 文档导航

- `docs/fork/surveys/` —— **现状调研文档目录**（改代码前的地图）。索引见 `docs/fork/surveys/README.md`，含写作规范与候选梳理方向。
  - `docs/fork/surveys/ai-system-overview.md` —— **AI 体系梳理**：三套独立 AI 链路（聊天 Agent / AI 生成工作流 / 工作流内 AI 模块）、系统提示词组装、技能路由、工具清单与 scope 判定、执行与审批流程、能力与现状评估。**改动任何 AI 相关能力前先读这份。**
- `docs/fork/function-workflow.md` —— 函数工作流功能的需求文档 + 实现状态/交接（含真机测试场景）。

---

## 项目定位

vFlow 是一款 Android 端可视化自动化工具。核心价值：把手机上的「点击、识别、判断、系统操作」串成可配置、可自动触发的工作流。

- **技术栈**：Kotlin 2.3.20、Compose（Material 3）、Gradle。
- **两个模块**：
  - `:app` —— 主应用（UI 层 `com.chaomixian.vflow.ui.*` + 业务逻辑层 `com.chaomixian.vflow.core.*`），约 600 个 Kotlin 文件。
  - `:core` —— vFlow Core 独立进程（`com.chaomixian.vflow.server.*`），通过 app_process 以子进程运行做高权限操作，Master-Worker + 本地 Socket。
- **规模**：200+ 功能模块，63 个测试文件。
- **版本**：`versionName = "1.5.3-pr1"`，`compileSdk 36` / `minSdk 29`。

---

## 常用命令

```bash
# 构建（debug APK）
./gradlew assembleDebug

# 运行单元测试（app 模块）
./gradlew test

# 只跑某个测试类
./gradlew test --tests "com.chaomixian.vflow.xxx.TestClass"

# 构建 release（需要签名，见 FORK.md 敏感点）
./gradlew assembleRelease

# core 模块单独构建 dex（app 的 preBuild 会自动依赖它）
./gradlew :core:buildDex
```

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

- **改 `app` 业务逻辑 / 新模块**：`./gradlew test`（有 63 个测试文件，涉及解析/执行/类型的改动必须跑）。
- **改 Core**：`./gradlew :core:buildDex` + 真机验证（Core 是独立进程，单测覆盖有限）。
- **改前端 UI**（Compose）：构建 `./gradlew assembleDebug`，跑真机/模拟器看效果。
- **改原生代码**（cpp）：`./gradlew assembleDebug` 会触发 externalNativeBuild，可能很慢；尽量先确认需要再改。

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
