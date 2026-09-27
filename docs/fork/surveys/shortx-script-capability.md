# ShortX 脚本能力与执行机制（外部调研）

> **性质**：fork 独有文档 → 冲突归**我方**（上游无此文件）。
> **日期**：2026-09-27（v1.1 同日复核修订）。
> **材料**：ShortX 反编译源码 `D:/develop/references/shortx/decompiled/`
> （APK rev `95a632b6`，4 个 dex / 24147 类，反向产物 17991 个 `.java`；**在仓库之外，不进 git**）。
> 行号引用相对 `decompiled/sources/`。
>
> **v1.1 修订（2026-09-27，与用户逐项复核后）**：修三处
> —— ① §4.3 表格「`executeAction` 一行记 vFlow 更强」**判断不成立**，改为两维度并列（§4.3.1）；
> ② §6-① **「shell 可替代大部分 system_server 能力」的说法被推翻**（`service call` 传不了对象参数、
> 读不了返回值），结论不变但理由改为「固定模块更省」；
> ③ §6-③ **「ShortX 主动克制」的解读改为「技术必然」**（hook 表达式所在进程没有 JS 引擎）。
>
> **与相邻文档的分工**：
> - `script-system-overview.md` —— **vFlow 侧**脚本体系现状（JS / Lua 对照、能力边界三层、威胁模型）；
> - **本文** —— **ShortX 侧**的脚本机制深度调研（引擎、位置、落点、防御缺失）。
>
> **为什么写它**：决定「vFlow 要不要为『在 system_server 内执行脚本』开口子」时，
> 需要知道 ShortX 到底怎么做的、它为此付出了什么代价。**结论见 §6。**
>
> **证据标注**：`【源码】文件:行号` / `【文档】` / `【推断】`。未验证的**明说「未能确定」**。

---

## 0. 结论速览

1. **引擎与 vFlow 同级**：ShortX 也是 **Rhino**（未改造，只装了 `NoSecurityController` 关掉检查）+ 第二引擎 **MVEL**（重打包）。**没有 QuickJS / V8 / Duktape。**
   ⇒ **「vFlow 的 JS 引擎不够强」这个缺口不存在。**
2. **脚本跑在 system_server（uid 1000）内**，无超时、无中断、无沙箱。
3. **脚本有五个落点**，但**hook 表达式只有 MVEL、且在被 hook 的进程内求值** —— 与其余四个落点的位置不同，这是本文最关键的一处发现（§4.2）。
4. ShortX 脚本的额外能力**大部分不是「引擎更强」，而是「跑在 system_server 里」+「有一个能操作引擎自身的 `shortx` API」**。
   前者搬过去代价极大，**后者在 App 侧就能补**（§5 的分类表）。
5. ⇒ **不建议为「在 system_server 内执行任意脚本」开口子**（§6）。

---

## 1. 脚本引擎本身

### 1.1 JS 引擎：Rhino，未改造（除装了一个「不检查」的安全控制器）

| 项 | 值 | 证据 |
|---|---|---|
| 引擎 | Mozilla Rhino，**类名未混淆** | `org/mozilla/javascript/Context.java` 全套在位 |
| Android 封装 | `com.faendir.rhino_android` | `RhinoAndroidHelper.java` |
| 优化级别 | **1（编译模式）**，非解释 | `MP.java:40` |
| 编译产物缓存 | `<ShortX 私有目录>/js` | `C0563Fo.java:153` 一带 |
| 入口类 | **`MP`**（`kaa.tjo.ufanjca.MP`），求值 `MP.OooO0O0(...)` | `MP.java:32-102`，求值在 `:86` |
| 作用域构造 | `ImporterTopLevel(cx)`（**不是** `initStandardObjects()`） | `MP.java:42` —— 故 `importClass` / `importPackage` 可用 |
| 脚本文件名 | 固定 `"<shortx_js>"` | `MP.java:86` |
| **唯一改造** | ⚠️ 全局安装 `NoSecurityController`（关掉安全检查） | `RhinoAndroidHelper.java:19-22`【源码】 |

> **与 vFlow 的对照**：vFlow 用 Rhino **1.9.0**（`app/build.gradle.kts:204`），优化级别 **-1（解释）**（`JsExecutor.kt:31`），
> 作用域自 2026-09-21 起也是 `ImporterTopLevel`。
> ⇒ **语言层两者等价**（vFlow 已实测 `Packages` / `java` / 反射全可达）。
> 差异**不在引擎**。优化级别的差别是**速度**（编译 vs 解释），不是能力。

### 1.2 第二引擎：MVEL

`org.mvel2` 被整体重打包进 `kaa/tjo/ufanjca/`（`UN0` 是引擎门面）。
侧证：`CodeType` 枚举只有两个值 —— `MVEL(0)` / `JAVASCRIPT(1)`，**MVEL 编号在前**，说明是先用起来的那个。

**用途**：轻量表达式求值（条件复合、JSON 取值、hook 表达式）。
**注意**：ShortX 用 MVEL 是因为**轻**（不必起一个 Rhino Context），**不是因为它能做 JS 做不到的事**。

### 1.3 进程与线程

**进程 = system_server（uid 1000）**，逐字证据链：

```java
// AMSHook.java:35-41
Context context = (Context) AbstractC9649tz1.OooOO0(thisObject, "mContext");  // AMS 实例的 mContext
C7546oY1 c7546oY1 = C7546oY1.OooOO0o;
c7546oY1.OooO0oo(context);   // 用 system_server 的 Context 初始化规则引擎/动作执行器
```

> ⚠️ **一处易混点**：i18n 里 `ui.settings.rule.engine.shell.summary` 写「shell 引擎在 SystemUI 中执行」
> —— 那是 **Shell 执行引擎**的位置，**与脚本引擎无关**。脚本引擎随规则引擎在 system_server。

**线程**：`MP.OooO0o0` 是协程方法，先按动作字段 `ExecuteJS.context` 选 dispatcher，再调同步的 `MP.OooO0O0`：

| 配置项 | dispatcher | 证据 |
|---|---|---|
| `Default` | **`Dispatchers.Default`**（CPU 线程池） | `MP.java:159-160`、`C1346Nv.java` |
| `IO` | IO 线程池（≥64 并行） | `MP.java:161-163`、`ExecutorC2344Xu.java:12-25` |
| `UI` | `Handler("ShortXJS")` 专用线程 | `MP.java:164-165`、`C0563Fo.java:154` |

求值本身**同步**（`MP.java:85-86` 的 `cx.evaluateString(...)`）。

> **纠正一处既有分析的错误**：`docs/JS脚本能力分析与开发方案.md` §2.3 写
> 「`Context.exit()` 只在异常路径显式调用，正常路径依赖 Rhino 线程绑定」——
> **源码不符**：成功路径（`MP.java:90-92`）与失败路径（`:94-97`）**都**显式调了。
> 属良性差异（两边都退出，无泄漏），但引用时以源码为准。

### 1.4 超时 / 中断：**完全没有**

| 检索项 | 结果 |
|---|---|
| `setInstructionObserverThreshold` / `observeInstructionCount` | 全树仅命中 Rhino 库自身，**无调用方** |
| `ClassShutter` / `setClassShutter` | 同上，**无调用方** |
| `withTimeout`（协程超时）in `kaa/` `tornaco/` `shortx/` | **0 处** |
| `MP.java` / `LP.java` 内 Timeout | **0 处** |

`MP` 对异常的处理是 catch Throwable → 记日志 → 吞掉/重抛（`MP.java:87-98`），**不做中断**。

**死循环的后果**【推断，依据充分但**未实测**】：脚本永久占住那个线程。
选 `Default` 时占的是 system_server 进程里 `Dispatchers.Default` 的一个线程；
选 `UI` 时占 `ShortXJS` Handler 线程。**不会一次拖垮整个 system_server**（不走主线程），
但**该线程池容量被逐步吃掉**，且**没有任何恢复途径**（用户只能重启）。

### 1.5 注入的绑定对象（能力边界的核心）

`MP.OooO0O0` 在求值前 `putProperty` / `putConstProperty` 到作用域（`MP.java:39-72`）：

| 符号 | 内容 | 可变性 |
|---|---|---|
| `context` | Android Context（**system_server 的系统 Context**） | 只读 |
| `shortx` | `ScriptShortXApiImpl`（约 15 个方法，见下） | 只读 |
| `console` | 11 个浏览器习语方法（`log`/`info`/`time`/`count`/`group`…） | 只读 |
| `globalVarOf$<名>` / `localVarOf$<名>` / `argOf$<名>` | 各变量与函数入参 | **const** |
| `Packages` / `java` / `importClass` / `importPackage` | Rhino 标准 + `ImporterTopLevel` | 只读 |

**`shortx` 对象的方法**（`ScriptShortXApi.java`，接口未混淆）：

- **变量**：`readGlobalVar` / `writeGlobalVar` / `writeGlobalVarWithOp`
- **执行**：`executeAction(Message)` / `executeActions(List)` / `executeDAById(id)`
- **查询**：`queryRuleById` / `queryRuleIdByTitle` / `queryRuleIdByTitleAndDescription` /
  `queryDAById` / `queryDAIdByTitle` / `queryDAIdByTitleAndDescription`
- **开关**：`enableDisableRuleById(id, enable)`
- **环境**：`getShortXDir()`
- **UI**：`getUiAutomation()` → 内嵌 `UiAutomationApi`（**14 个方法**，
  含 `injectInputEvent` / `dispatchGesture` / `performGlobalAction` / `getRootInActiveWindow`）

> ⭐ **这一组 `query*` + `enableDisableRuleById` 值得单独注意**：
> 它们让脚本能**读工作流清单、按标题查 ID、改启用态**。
> **vFlow 没有等价物** —— 但这条**在 App 侧就能补**（见 §5.2）。

### 1.6 沙箱：**没有，且是主动关闭的**

- `NoSecurityController` 由 `RhinoAndroidHelper.enterContext()` 作为**全局单例**安装
  （`RhinoAndroidHelper.java:19-22`），类文件在位。
- 无 `ClassShutter`、无指令数上限、无内存上限、无超时（§1.4）。
- **唯一收敛的是「变量写入」**：变量以 **const 属性**注入（脚本内不能直接赋值改），
  要改必须走 `shortx.writeGlobalVar(...)`。⚠️ **这个设计值得学**。
- ⇒ 脚本在 system_server 里可 `Runtime.getRuntime()`、可反射任意 AOSP 内部类，
  **与 App 同权限（uid 1000）**。

**⚠️ 另一处已知缺陷（vFlow 这里做得更好）**：
变量预处理**不转义** —— 先按正则 `%(\w+)(?:@(\w+))?%` 替换占位符（`C0451Ej2.java:11`），
再把值以 `'"' + value + '"'` 拼进脚本正文（`MP.java:79`）。
即文献里常说的 **SSTI 面**，**已核实**。
vFlow 用 `{{}}` / `{% %}` 且用 `gson.toJson` 转义，**此处更稳**（`script-system-overview.md` §3.1 已记）。

---

## 2. 脚本在哪几种地方被使用（五类落点）

| # | 类别 | 载体 | 引擎 | **求值位置** | 调用点 |
|---|---|---|---|---|---|
| 1 | **动作** | `ExecuteJS.expression` | JS | system_server | `MP.OooO0o0` → `LP.java:43` |
| 1' | 动作 | `ExecuteMVEL` | MVEL | system_server | `C4804jO0.java` |
| 2 | **条件** | `MatchJS.expression` | JS | system_server | `C0477Eq0.java:357-362` |
| 2' | 条件 | `MatchMVEL` | MVEL | system_server | `C1941Tm0.java:243-247` |
| 3 | 规则实例 ID 生成 | `RuleInstanceIdGenerator_JS` / `_MVEL` | 两者 | system_server | `AbstractC5733nG1.java:46`/`:54-71` |
| 4 | **hook 表达式** | `MethodHookExpressions.expressionMVEL` | **仅 MVEL** | ⚠️ **hook 目标进程内** | `CustomHookUtil.java:50-71`、`:173-190` |
| 5 | 代码库片段 | `CodeLibraryItem`（`type` ∈ {MVEL, JS}） | 两者 | 编辑期 / 被 1–4 引用 | — |

**取值/表达式类的其它落点**（MVEL 为主，属「求一个值」而非「做一件事」）：
- HTTP 响应 JSON 取值（i18n `ui.action.http.request.json.expression` 写明「填写多个 MVEL 表达式」）
- 多条件布尔组合（`ui.condition.op.mvel`，变量名 `c1, c2, c3…cN`）
- 函数入参（`FuncParameterInput`，即 `argOf$` 注入源）
- 远程执行（`RemoteExecuteMVEL`，处理者 `C10273wb1.java`）

> ### ⚠️ 必须点明：**hook 表达式只有 MVEL，没有 JS**
>
> `MethodHookExpressions` 只声明了两个字段（`MethodHookExpressions.java:23-24`）：
> `CONTEXTKEY_FIELD_NUMBER=2` 与 **`EXPRESSIONMVEL_FIELD_NUMBER=1`**。
> **没有 `expressionJS`。**
>
> ⇒ **ShortX 自己也没在 hook 表达式里放 JS。** 这个克制很有信息量（见 §6 理由③）。

---

## 3. 与 vFlow 的基线对照

| | ShortX | vFlow |
|---|---|---|
| JS 引擎 | Rhino（+ rhino-android） | Rhino **1.9.0** |
| 优化级别 | 1（编译） | **-1（解释）** |
| 第二引擎 | **MVEL** | **Lua**（LuaJ，用途不同） |
| 作用域 | `ImporterTopLevel` | `ImporterTopLevel` |
| 内联表达式 | `%name%` 占位符（**不转义** ⚠️ SSTI 面） | `{% %}` / `{{expr}}`（**gson 转义，做对了**） |
| 注入 Context | system_server 的系统 Context | App 的 `applicationContext` |
| 沙箱 | **无**（主动关掉 `NoSecurityController`） | **无**（未装 `ClassShutter`） |
| 超时 | **无** | **无**（已知短板） |

⇒ **语言层等价。差异不在引擎。**

---

## 4. 能力差距的**分类**（本文最重要的产出）

### 4.1 【A 类】只有「进 system_server」才能解决的差异

| 能力 | ShortX 怎么做 | vFlow 为何做不到 | 证据 |
|---|---|---|---|
| **读/改 hook 宿主进程内的对象** | hook 表达式绑 `param`（`thisObject`/`args`/`result`），可读可改可 `returnAndSkip()` | 脚本在 App 进程，**没有宿主对象的引用**；反射也需要先有对象 | `CustomHookUtil.java:53-56`、`ShortXHookParam.java` |
| **拿 hook 目标进程的 Application** | `helper` 绑 `InjectedAndroidAppHelper` | 同上，跨进程拿不到对象 | `CustomHookUtil.java:53`、`InjectedAndroidAppHelper.java` |
| 底层输入注入 | `shortx.getUiAutomation().injectInputEvent(...)` | ⚠️ **有等价物**：Shizuku/shell 的 `input`，或 Core 侧 | `ScriptShortXApi.java:44` |
| system uid 专属窗口 / SystemUI 内部 | 脚本在 system_server 可直接开 `TYPE_STATUS_BAR_ADDITIONAL` | 连 root 都开不出来（窗口类型要求 system uid） | `xposed-channel-design.md` §2.1-④ |

> ⚠️ **A 类里真正不可替代的只有前两条**，而前两条的性质是
> **「读别的进程的内存」—— 那是 hook 能力，不是脚本能力**。
> 且它们**必须先有 hook 才谈得上**：价值随 hook 走，
> 不为它单开脚本通道也照样能用「hook 表达式」实现（ShortX 的 `MethodHookExpressions` 就是这么设计的）。

### 4.2 ⭐ 位置发现：ShortX 的 MVEL 有**两套运行位置**

```
ShortX 的危险面 = JS @ system_server          （引擎能力，uid 1000）
                + MVEL @ hook 目标进程内      （进程内对象完全访问）← 信息面 > root
```

**这是本次调研最关键的一处发现**：

- 普通动作/条件里的 MVEL 在 **system_server**（`C4804jO0` 用 system_server Context）
- **hook 表达式里的 MVEL 在【被 hook 的进程里】** ——
  `CustomHookUtil` 本身就是注入进宿主进程的代码（`CustomHookUtil.java:50-71`）

⇒ **JS 脚本（在 system_server）读不到 hook 目标进程的对象**。
`MP` 注入的 `context` 是 system_server 的 Context，**与 hook 目标进程无关**。

**这一条直接决定了 §6 的判断**：`system_server 内跑 JS` 与
`hook 表达式` 是**两个不同位置的能力**，而后者才是「能摸到宿主对象」的那个。

### 4.3 【B 类】引擎/API 设计差异 —— **在 App 侧就能补，代价小**

| 能力 | ShortX | vFlow 现状 | 补齐方式 |
|---|---|---|---|
| ⭐ **规则自省 / 自操作 API** | 6 个 `query*` + `enableDisableRuleById` + `executeDAById` | ❌ **没有**。脚本只能通过模块树间接做事，**读不到工作流清单、改不了启用态** | **App 侧可补**（读 `WorkflowManager` + 新增脚本 API） |
| 脚本内构造动作交回引擎执行 | `executeAction(Message)` / `executeActions(List)` | ⚠️ **两者不同、不可互相推导**（见下方订正） | **部分缺** —— 缺「运行时构造」与「批量」 |
| **`console` 对象** | 11 方法（`log`/`time`/`count`/`group`…） | ❌ **完全没有** | App 侧，约几十行 |
| **代码库（片段级复用）** | `CodeLibraryItem`（id/name/desc/type/content/tags）+ 编辑器内试跑 | ⚠️ 只有**模块粒度**的 `scripted/` | App 侧可补 |
| **轻量表达式引擎** | ✅ MVEL（条件复合、JSON 取值） | ❌ 无（`{% %}` 每次求值**新建 Rhino Context**） | App 侧可补，或优化 Context 复用 |
| 变量命名空间隔离 | const + `globalVarOf$` / `localVarOf$` / `argOf$` | ✅ 有（`inputs`/`sys`/`vars`/`global` 四个具名对象） | 无需补 |
| 变量注入转义 | ❌ **未转义**（SSTI 面） | ✅ **已做对**（`gson.toJson`） | **勿退化** |

### 4.3.1 ⚠️ 订正：「vFlow 模块树更强」不能推出「编排能力已具备」

**本表此前把 `executeAction` 一行记为「✅ vFlow 更强」，这个判断不成立。**
2026-09-27 复核时发现两者是**不同维度**的能力，不能互相推导：

| 维度 | ShortX `executeAction(Message)` | vFlow `vflow.*` 模块树 |
|---|---|---|
| 动作从哪来 | **运行时构造**（`newBuilder()` 拼 protobuf） | **编译期注册**（`ModuleRegistry` 里的静态路径） |
| 能否拼类名动态调用 | ✅ 可 `Class.forName` + 反射构造 | ❌ 路径是死的，不能拼字符串变出来 |
| 能否批量提交 | ✅ `executeActions(List)` 一次交一批 | ❌ 每个都是一次独立调用 |
| 能否嵌套（动作里带动作） | ✅ 流程类 proto 内嵌子动作列表 | ❌ 需预先在编辑器里排好 |
| 动作总数 | 208（预定义 proto） | **195（vFlow 更多）** |

**结论**：vFlow 的**覆盖面更广**（模块更多），但 ShortX 的**表达方式更灵活**
（运行时构造 + 批量 + 嵌套）。这是「静态路径 vs 动态构造」的差异，不是「谁更强」。

> ⚠️ **同时要降低这条的实际价值预期**：
> ① 它构造的是**内存里的动作树**、执行完即消失，**不是「用脚本创建工作流」**（那是编辑器的职责，ShortX 也没有）；
> ② 大多数「循环里调模块」的场景，vFlow 用 JS 循环即可达到类似效果，只是每次一次独立调用；
> ③ 它的真实收益是**批量提交**与**运行时分支**，属长尾。
> ④ 但它的**风险是实打实的**：能构造注册表外的动作 = **绕过模块白名单**，
> 若将来要做，必须与沙箱分级绑定。

### 4.4 定性结论

```
ShortX 脚本能力 = 语言层（Rhino，vFlow 等价）
               + 环境层（shortx API + system_server Context）
               + 能力层（system_server 的 uid 1000）
                  ↑ 这半边才是差距，而它里面
                    ├─ 大头是「hook/进程内对象」= hook 能力，不是脚本能力  → A 类
                    └─ 其余是「规则自省 API / console / 代码库」= App 侧可补 → B 类
```

---

## 5. 危险形态的证据

### 5.1 ⚠️ 脚本侧**完全没有**超时/熔断（不要把 hook 侧的防御当成脚本的）

**这是本节最容易混淆的一点。**

既有 `xposed-channel-design.md` §5.1 列的六条防御
（180ms 拦截上限、1200ms 去重窗口、连按熔断、全局 `UncaughtExceptionHandler` 等）
**全部属于 hook 回调路径**（`InputManagerHook` / `ProcessListHook`），
**没有一条属于脚本执行路径**。源码证据见 §1.4 的四条 grep。

⇒ **ShortX 对「自己的 hook 回调」极度克制，对「用户脚本」完全不设防。**

**⚠️ 但这条不可平移到 vFlow**：ShortX 的威胁模型是「脚本是用户自己写的」；
vFlow 的威胁模型不同（有**远程仓库** + **AI 生成**），所以「用户自己负责」这个前提在 vFlow 不成立
（`script-system-overview.md` §9 的威胁分级）。

### 5.2 「脚本读别的进程内存」的实证

**成立，但归属要分清**（这正是 §4.2 的内容）：

- ⚠️ **JS 脚本（system_server）读不到** hook 目标进程的对象
- ✅ **hook 表达式的 MVEL（hook 目标进程内）能读**
  —— 可 `param.thisObject.xxx` 直接读，可 `setResult(...)` / `returnAndSkip(...)` 改写并跳过原实现

#### ⚠️ 「宿主」一词有两种含义，讨论时必须先说清是哪种

2026-09-27 复核时发现，此前文档与讨论中「读 hook 宿主对象」这一说法**含糊**，
它实际对应两类**机制完全不同**的情况：

| | 宿主意指 | 表达式在哪求值 | 谁能读 |
|---|---|---|---|
| **A** | **任意普通 App 的进程** | **被 hook 的那个 App 进程内** | hook 表达式（MVEL） |
| **B** | **system_server / SystemUI** | **system_server 内** | **JS 脚本与 MVEL 都能** |

- **A 类的信息面高于 root** —— root 读别的进程内存要走 ptrace，
  而**同进程内是直接对象引用**。但代价是：**该能力只在被 hook 的那个 App 里存在**，不是全局的。
- **B 类**才是「跑在 system_server」真正买到的东西。

**关键推论**：`scope.list` 只有 `system` + `com.android.systemui`
⇒ **ShortX 的代码只注入这两个地方**。
要 hook 普通 App（A 类），意味着把代码注入**用户手机上的每一个 App** —— 完全不同的量级。
**这是 vFlow 目前不做、也不应轻率做的事。**

### 5.3 其它形态问题

| 形态 | 证据 | 严重度 |
|---|---|---|
| 变量预处理不转义（SSTI） | `MP.java:79` | 单机可控；**共享规则时是 RCE 面** |
| JS 引擎开关的 key 叫 `mvel` | `MP.java:146-148` 抛「MVEL engine is disabled」但管的是 JS 动作 | 体验缺陷，非安全 |
| 无脚本日志查看页 | ShortX 有 `JSLogActivity`；vFlow 有行号/列号但无独立查看页 | 体验差距，vFlow 可低成本补 |

---

## 6. 取舍依据：**不建议为「system_server 内执行脚本」开口子**

**建议走两条腿**：① 「hook 表达式」**限定形态**；② **App 侧补 B 类 API**。

理由按证据强度排序：

**① 收益端要重新算 —— 但结论不变。**
§4.1 的 A 类里，此前认为「真正不可替代的只有读 hook 宿主对象」。
2026-09-27 复核后**修正为三类**（见下方补记）：另有「需要**构造对象参数的调用**」
与「需要**读返回值/链式调用**的系统服务操作」—— 这两类**不是 hook 能力**，
是真正的脚本能力缺口。

> #### ⚠️ 2026-09-27 补记：此前低估了 shell 与 JS 的差距
>
> 本文此前隐含一个判断：「system_server 的能力大部分能用 `shell_command` 替代」。
> **这个判断是错的。** `service call` 的实际能力面是：
>
> | 能力 | `service call` |
> |---|---|
> | 调系统服务方法 | ✅ |
> | 传**基本类型**参数（int / long / float / String） | ✅ |
> | **构造对象参数**（`Rect` / `ComponentName` / `Intent`） | ❌ **只能传 null** |
> | **读返回值**（拿到对象做判断） | ❌ 只回 Parcel 的十六进制文本 |
> | **链式调用**（A 的结果喂给 B） | ❌ |
>
> **实证**：小窗脚本那行
> `service call activity_task 138 i32 $TID i32 $flag s16 '' i32 0`
> —— 第 4 个参数 `Rect` 传的是 `0`（null），**不是不想传，是构造不出来**。
>
> **⇒ shell 与 JS 不是「弱一点/强一点」，是「单向 RPC」与「完整编程语言」的关系。**
> 小窗能成，是因为它恰好只需要「发一个 int 参数」；换个需要构造对象或读返回值的场景，shell 就废了。
>
> **但这不改变 §6 的结论** —— 改的是**理由**：不是因为「有替代」，
> 而是因为那几类需求**用 Kotlin 在 hook 层做固定模块更省**（如 `CloseActivity` 那样），
> 不必为此开通用脚本通道。

**② 成本端是量级跃变。**
`xposed-channel-design.md` §5.1 给 vFlow 定的纪律是「hook 点内只做取值 + 入队」。
**在 system_server 里跑用户脚本直接推翻这条**：一个死循环、一次反射进 AOSP 内部的不兼容调用，
崩溃半径都是**整机软重启**。而 §5.1 证明 **ShortX 自己在这一层没有任何超时/熔断**，
**可参考的防御为零**。

**③ ⭐ ShortX 的 hook 表达式只放 MVEL —— 但理由是「位置」，不是「克制」。**
它在 `MethodHookExpressions` 里只放 MVEL（无 `expressionJS` 字段）。

> ⚠️ **2026-09-27 订正**：此前把这解读为「ShortX 主动克制」。
> 复核后更可能的解释是**技术必然**：hook 表达式在**被 hook 的目标进程内**求值，
> 而 **Rhino 只在 system_server 初始化过** —— JS 引擎在那个位置根本不存在。
> 即「不是不想放 JS，是放不了」。
>
> **结论不变**（确实不该在此处放 JS），但**论据要换**：
> 不是因为「ShortX 都觉得该克制」，而是因为**那个位置与 JS 引擎不在一起**。

**④ 大头缺口在 App 侧就能补。**
§4.3 的 B 类（规则自省 API、`console`、代码库、轻量表达式）**全部 App 侧可补**，
且对照结果是 vFlow 的模块树（~192）远强于 ShortX 的 API（~15）。
> 但注意 §4.3.1 的订正：**模块数多 ≠ 编排能力等价**。

**⑤ 与既有文档的关系。**
`xposed-channel-design.md` §5.3 已论证「恶意规则导入不是新增威胁」
（`shell_command` / `js` 已可通过同一导入通道传播）。
**本文补充的是**：把脚本推进 system_server **不是**「多一个同类条目」——
因为 `shell_command` / `js` 的崩溃半径都是**自己的进程**，
而 system_server 内脚本的半径是**整机**。这恰好是 `xposed-channel-design.md`
§1.2「差异 1（本质）」在脚本维度上的重演。

### 6.1 若将来确实要开口子，建议的**限定形态**（把无界压成有界）

| 建议 | 依据 |
|---|---|
| **只放「表达式」不放「完整脚本」** | 与 ShortX 一致 —— 它自己也没在 hook 表达式里放 JS |
| **进 system_server 只做「取值」，不做「执行」**：白名单绑定对象，禁止反射 / `Runtime` / 文件 IO | 对齐「hook 点内只做取值 + 入队」的纪律 |
| **保留权限分档**：独立模块 id、不与「hook 目标 App」混用 | vFlow 的核心资产是「能力可选不同身份执行」；system_server 内脚本会让所有调用一律 uid 1000，**分档失效** |
| **超时必须做**，且要比 vFlow 现在的 JS 更强 | vFlow 现在连 App 侧 JS 都**没有超时**（既有短板）；进 system_server 后没有超时不可接受 |

---

## 7. 证据强度与未确定项

**强证据（源码直读）**：引擎与版本、优化级别、`NoSecurityController`、进程归属（`AMSHook` + `C7546oY1`）、
线程选择、无超时 / 无沙箱、注入符号清单、约 15 个 `shortx` 方法、14 个 `UiAutomationApi` 方法、
五类落点、`MethodHookExpressions` 只有 MVEL、hook 表达式绑定 `param` / `helper`、
`MP` 的 `%…%` 预处理不转义。

**中证据（文档/字符串，均已在源码侧交叉验证）**：ShortX 自己的分析文档与 i18n 文案。
**一处与源码不符已纠正**：`Context.exit()` 的调用路径（§1.3 末）。

**未确定（不猜）**：

1. **死循环的真实表现未实测**（只做了源码推断，§1.4）
2. `MP.OooO0O0` 接收的 `TX1 shortXContext` 完整类型未展开
3. **`InjectedAndroidAppHelper.currentApplication()` 在 system_server hook 时返回什么**未核实
4. 既有文档称 `shortx` 有 16 个方法，本次数出 **15 个**（含嵌套 `UiAutomationApi` 另计 14）；
   差异可能是数法不同，**未逐一对齐**
5. 编辑器 `assets/textmate/languages.json` 里 49KB 的 `java` 语法用途（ShortX 无 Java 脚本动作）——
   既有文档也列为待查

---

## 8. 关键文件速查

均在 `D:/develop/references/shortx/decompiled/sources/`：

| 用途 | 文件 |
|---|---|
| JS 引擎入口 / 求值 | `kaa/tjo/ufanjca/MP.java`、`kaa/tjo/ufanjca/LP.java` |
| MVEL 执行器 / 门面 | `kaa/tjo/ufanjca/C4804jO0.java`、`kaa/tjo/ufanjca/UN0.java` |
| 条件求值（含 MatchJS / MatchMVEL） | `kaa/tjo/ufanjca/C0477Eq0.java` |
| 规则实例 ID 生成 | `kaa/tjo/ufanjca/AbstractC5733nG1.java` |
| Rhino Android 封装（含 `NoSecurityController`） | `com/faendir/rhino_android/RhinoAndroidHelper.java` |
| `shortx` 脚本 API 实现 / 接口 | `tornaco/apps/shortx/services/rule/actor/impl/js/ScriptShortXApiImpl.java`、`.../core/rule/script/api/ScriptShortXApi.java` |
| **hook 表达式求值（宿主进程内）** | `tornaco/apps/shortx/services/xposed/hooks/hook/CustomHookUtil.java` |
| hook 参数绑定对象 | `.../hooks/hook/ShortXHookParam.java`、`.../InjectedAndroidAppHelper.java` |
| 引擎初始化（拿 AMS 的 mContext） | `.../hooks/hook/AMSHook.java` |
| hook 表达式 proto | `tornaco/apps/shortx/core/proto/fact/MethodHookExpressions.java` |
