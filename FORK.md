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
| `FORK.md`、`AGENTS.md`、`CLAUDE.md` | fork 独有文件，上游没有 | 我方 |
| `docs/fork/function-workflow.md`、`docs/fork/function-workflow-ui.html` | fork 独有：函数工作流需求文档 + 可交互 UI 原型（上游无此文件） | 我方 |
| `docs/fork/chat-agent-enhancement-plan.md` | fork 独有：Chat Agent 四点改造方案（技能目录化/Prompt 缓存/catalog 全量化+模块查询工具/悬浮窗），上游无此文件 | 我方 |
| `docs/fork/surveys/`（`README.md` + `ai-system-overview.md` + `agent-design-comparison.md`） | fork 独有：**现状调研文档目录**（改代码前的地图）。`README.md` 为索引+写作规范；`ai-system-overview.md` 为 AI 体系梳理（三套链路/提示词/工具/技能/执行流程/模块可发现性/缓存 + 能力评估）；`agent-design-comparison.md` 为头部 Agent 项目外部对照调研。上游均无此文件 | 我方 |
| `core/workflow/model/Workflow.kt` | 新增 `functionSignature` 字段（Parcelable，带默认值 null，向后兼容） | 手动合并（fork 追加字段，若上游也改需逐块判断） |
| `core/workflow/model/FunctionSignature.kt`（新增） | 新增 `FunctionParam`/`ReturnKey`/`FunctionReturn`/`FunctionSignature` 数据类 | 我方 |
| `core/execution/WorkflowExecutor.kt` | `executeSubWorkflow` 增加可选参数 `injectedVariables: Map<String, VObject> = emptyMap()`（带默认值，不破坏现有调用） | 手动合并（向上游方法加带默认值参数，若上游改了该方法需逐块判断） |
| `core/workflow/module/logic/DefineFunctionModule.kt`、`CallFunctionModule.kt` + UIProvider（新增） | 新增函数工作流模块（定义函数+调用函数） | 我方 |
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
- `settings.gradle.kts` —— 模块声明（`:app` `:core`）。
- `app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt` —— 模块注册表。**新增模块时在 `initialize()` 里按分类追加一行即可，不要重排已有注册**，否则每次上游合并都在这个文件解冲突。
- `core/src/main` —— vFlow Core 独立进程（Master-Worker）。改动独立，应单独评估、单独 patch。
- `app/src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt` —— 工作流执行器核心循环。改动风险高，须谨慎。
- `app/src/main/java/com/chaomixian/vflow/api/` —— 远程 API。**新增 handler 时新增文件，不要改既有接口签名**。
- `app/src/main/java/com/chaomixian/vflow/core/workflow/model/Workflow.kt` / `ActionStep.kt` —— 工作流数据模型。上游改动会波及大量解析/序列化代码。

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
