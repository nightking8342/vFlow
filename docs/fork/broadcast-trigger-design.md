# 广播触发器设计 —— 用户可自由配置的任意广播监听

> 版本：v1.2（2026-10-07 回写实现状态 + 首轮真机验证）
> 状态：**已实现 + 首轮真机验证通过（14 项里 4 项已做，见 §11.1）** —— 代码已合入 `dev`；
> ⚠️ 其余各项的结论仍是【源码】级，不是【实测】级。
> 目录归属：**fork 独有**（冲突归我方），上游无此文件
> 关联：`core/workflow/module/triggers/`（模块声明）+ `.../triggers/handlers/`（Handler）
> 上位文档：`docs/fork/surveys/trigger-system-overview.md`（触发器体系现状）
>
> 实现方案的偏差记录见 §9–§12；交付说明（文件清单 / 反证 / 遗留问题）在提交信息与 `FORK.md`。

---

## 0. 一句话方案

新增触发器 `vflow.trigger.broadcast`：用户在编辑器里填**若干 action（可增删列表）**，
可选地再填 **data scheme** 等过滤条件；后台 `TriggerService` 里动态注册一个
**`RECEIVER_EXPORTED`** 的 `BroadcastReceiver`，收到匹配广播时把
`action / data / extras 等`打包成输出，供工作流下游以魔法变量引用。

**不引入 token、不做发送方鉴权、不引入 Xposed / Core** —— 纯 App 层动态注册。

---

## 1. 需求澄清（一处必须先说清的方向）

讨论初期走过一次弯路，记录下来避免后来者再走：

| 提议的方向 | 为什么是错的 |
|---|---|
| 「做一个广播沙箱：限制**哪些 App 可以触发**用户的自动化」（包名白名单 + 令牌） | ❌ **方向反了**。触发器的价值是**让用户的工作流灵活监听各种广播**，不是**限制谁**。用户配置了 `com.xxx.ACTION_Y`，任何发出该 action 的应用都应当触发——这正是预期行为（Tasker / ShortX 同理） |

**用户 2026-10-07 的原话**：「不是所有 APP 都能触发用户的自动化，而是用户的工作流可以灵活地配置各种广播。」

⇒ 本设计的**唯一主题**是「**怎么让用户配得动、收得到、拿得到数据**」，不含鉴权层。
字段层面直接后果：**不设 token 字段**（讨论稿里一度有，已删）。

---

## 2. 为什么走 App 层（三通道对照）

本项目已有四条特权/半特权通道，逐一排除：

| 通道 | 能否收广播 | 结论 |
|---|---|---|
| **App（`TriggerService` 动态注册）** | ✅ 原生能力，无需任何特权 | ✅ **选它** |
| Core（`app_process`，UID 2000 / 0） | ⚠️ **反例**：`shell` 进程里注册的 receiver **什么广播都收不到**（连自定义广播都收不到） | ❌ 已实测否决 |
| Xposed（注入 system_server） | ✅ 能收，但代价是**整机崩溃半径** + 需要用户装 LSPosed | ❌ 收益不抵风险 |
| 无障碍服务 | ✅ 能收，但要求用户开无障碍 | ❌ 与广播无关，白拉高门槛 |

> **Core 那条结论是本仓库的既有实测**（`docs/fork/surveys/trigger-system-overview.md` §9.2 第 8 条末尾）：
> 「shell 进程（`app_process`）里注册的 receiver **什么广播都收不到**……拿它做探针会得出完全相反的结论。」
> ⇒ **不要**试图把广播触发移到 Core 侧。

---

## 3. 关键平台约束（全部经源码核实）

这一节是本设计的核心。**每一条都会让「看起来能用」的实现静默失效**。

### 3.1 ⚠️⚠️ action 必须显式列出，**没有任何通配写法**

**【源码】** `IntentFilter.matchAction`（AOSP `IntentFilter.java`）：

```java
if (wildcardSupported && WILDCARD.equals(action)) {   // 检查的是「收到的 intent 的 action 是不是 "*"」
    ...
}
return hasAction(action);                              // hasAction = mActions.contains(action)
```

两个方向都堵死：

| 想法 | 实际行为 |
|---|---|
| `filter.addAction("*")` 想匹配一切 | ❌ `mActions.contains(action)` 对真实 action 返回 **false**（列表里存的是字面量 `"*"`）。而 `WILDCARD.equals(action)` 检查的是**收到的 intent 的 action 是否为 `"*"`**，正常广播都不是 |
| 干脆**不声明任何 action** | ❌ 类级 javadoc 逐字：「**if the filter specifies no actions, then it will only match Intents that do not contain an action**」——只匹配**没有 action 的广播** |

> ⚠️ **注意一处文档自相矛盾（会导致写错实现）**：`addAction` 的**方法级** javadoc 写的是
> 「If no actions are included, the Intent action is ignored.」——
> **与方法级/类级实际行为相反**。以**类级 javadoc + `matchAction` 源码**为准。
> ⇒ 编辑器**必须**保证至少一个 action，且**不能用「留空 = 任意」的惯例**（本项目其它触发器普遍这么做）。

**UI 层面的直接后果**：action 输入是**必填**。空列表时应当**拒绝保存**或在运行时**明确报错**，
而不是静默不触发（详见 §7 静默失效点 1）。

### 3.2 ⚠️⚠️ data scheme：两个反直觉行为

**【源码】** `IntentFilter.matchData` 的顶层结构：

```java
if (!wildcardWithMimegroups && types == null && schemes == null) {
    return ((type == null && data == null)
            ? (MATCH_CATEGORY_EMPTY + MATCH_ADJUSTMENT_NORMAL) : NO_MATCH_DATA);   // -2
}
if (schemes != null) { ... } else {
    // "Special case: match either an Intent with no data URI, or with a scheme: URI."
    if (scheme != null && !"".equals(scheme)
            && !"content".equals(scheme)
            && !"file".equals(scheme)
            && !(wildcardSupported && WILDCARD.equals(scheme))) {
        return NO_MATCH_DATA;
    }
}
```

| 情形 | 行为 | 后果 |
|---|---|---|
| filter **没声明 scheme**，收到的 intent **带** data（且 scheme 不是 `content`/`file`） | ❌ **`NO_MATCH_DATA` ⇒ 整条广播收不到** | 用户想监听带 data 的广播（如媒体挂载的 `file://`、包事件的 `package:`），**必须显式填 scheme** |
| `filter.addDataScheme("*")` 想匹配任意 scheme | ❌ **无效**。`WILDCARD.equals(scheme)` 检查的是**收到的 intent 的 scheme 是否为 `"*"`**，`schemes.contains(...)` 检查的是字面量 `"*"` | **IntentFilter 没有「任意 scheme」的能力** |

⚠️ 上表说明：**「scheme」这个参数不能做成「留空 = 任意」的语义**。留空的实际含义是
「**只收不带 data 的广播**」——这是个非常反直觉的默认值，UI 上必须写清楚。

**已知需要显式 scheme 的常见广播**：

| 广播 | data scheme | 备注 |
|---|---|---|
| `ACTION_PACKAGE_ADDED` / `_REMOVED` / `_REPLACED` | **`package`** | 本项目 `AppPackageTriggerHandler.kt:35` 就是这么写的，且靠 `intent.data?.schemeSpecificPart` 取包名 |
| `ACTION_MEDIA_MOUNTED` 等媒体广播 | `file` | `file` 是白名单例外，**不填 scheme 也能收** |

### 3.3 ⚠️⚠️ 必须用 `RECEIVER_EXPORTED` 注册（本项目多数 Handler 的写法在这里是错的）

Android 14+ 要求动态注册时显式声明 flag。本项目既有广播 Handler **惯用 `RECEIVER_NOT_EXPORTED`**，
那是**官方推荐写法，但只适用于「只收系统定向广播」**的场景。

**【本仓库实测】** `docs/fork/surveys/trigger-system-overview.md` §9.2 第 8 条：
> 产生于 `sendBroadcastAsUser(intent, UserHandle.ALL)` 的**跨应用广播**在 `NOT_EXPORTED` 下**收不到**；
> 同一进程内两个 receiver 同时注册、`EXPORTED` 收到而 `NOT_EXPORTED` **3/3 收不到**。

⚠️ **本触发器必须用 `RECEIVER_EXPORTED`**，原因是设计目标本身：

- 用户会配**任意** action，其中大量是**第三方应用**发的（Tasker / 自动化生态的典型用法）；
- 而 `NOT_EXPORTED` 的语义是「**只收本应用或系统定向投递的**」——收不到第三方应用发的广播。

**代价（如实记录）**：`EXPORTED` 意味着**任何应用**都能给这个 receiver 发广播。
但这不是安全漏洞 —— 收到广播只是「看一眼要不要触发工作流」，且用户**自己**配置了 action。
（这是 Tasker / ShortX 的既有模型。用户 2026-10-07 明确否掉了鉴权层。）

> ⚠️ 与 `SimDataSwitchTriggerHandler` 的关系：它也是 `EXPORTED`，但**理由不同**
> （那条是「系统广播走了 `UserHandle.ALL`」）。本触发器是「用户会配任意第三方 action」。
> 两处都写 `EXPORTED`，**不要**因此把这条规律推广到 DND / Power / Screen 那些 ——
> 它们目前用 `NOT_EXPORTED` **是对的**（只收系统定向广播），改动它们是**另一件事**。

### 3.4 动态注册 vs 静态注册：后台限制不适用于本设计

**【官方文档】** Android 8.0（API 26）起的**隐式广播限制**只约束**清单静态注册**
（`<receiver>` + `<intent-filter>`），**动态注册不受限制**。

⇒ 本设计走动态注册，**天然覆盖**那些「不在豁免清单里」的广播
（例如 `ACTION_PACKAGE_REPLACED` 是**不**豁免的）。
**这是 App 层动态注册相对静态注册的关键优势，值得在文档里写明**——否则后来者可能
误以为「要收 XX 广播必须静态注册」。

**代价**：动态注册依赖 **`TriggerService` 进程存活**。
⚠️ 关联既有缺陷：**关闭后台服务通知会让 `TriggerService` 降级**，所有监听类触发器静默失效
（`docs/fork/surveys/trigger-system-overview.md` §8.4 与 `fold-trigger-design.md` §8.1 均记过）。
本触发器同样会踩 —— **排障时先查这个**。

### 3.5 发送方身份：**拿不到**（所以本设计不设 token）

讨论稿曾设想过「用发送方包名做鉴权 / 白名单」。核实后确认**这条路的输入本身就不可得**：

**【官方文档】** `BroadcastReceiver.getSentFromPackage()` / `getSentFromUid()` 均为 **API 34+**，
且 javadoc 明写「**returns … or `null` / `Process.INVALID_UID` if the current receiver
cannot access the identity of the broadcasting app**」。

**【源码】** `BroadcastOptions.setShareIdentityEnabled(boolean)` 的 javadoc：
> "**Defaults to false** if not set." …… 只有设为 true 时 「the receiver will have access to
> the broadcasting app's package name and uid」。

`BroadcastController` 里能看到 identity 是按 `shareIdentity ? callingPackage : null` 传递的。

**结论**：

1. **发送方默认为 null**（要发送方**主动 opt-in** 才给），⇒ 绝大多数广播取不到发送方；
2. 与 `minSdk 29` 也不兼容（该 API 是 34+）；
3. **因此**：不做 token、不做包名白名单、不暴露 `sender_package` 输出。

> ⚠️ **不要因为它「在 API 签名表里存在」就以为能用** —— 这是典型的「签名存在 ≠ 运行期有值」。
> 若将来有人要加「按发送方过滤」，**先按 §3.5 的三条证据复核**，别直接写。

### 3.6 权限：多数广播零权限，少数需要声明

| 广播族 | 接收所需权限 |
|---|---|
| `ACTION_PACKAGE_*`、`ACTION_BATTERY_*`、`ACTION_POWER_*`、`ACTION_SCREEN_*`、耳机插拔、USB 插拔 | **无** |
| `ACTION_PHONE_STATE_CHANGED` | `READ_PHONE_STATE`（与 `CallTriggerModule` 对齐） |
| `SMS_RECEIVED` / `WAP_PUSH_RECEIVED` | `RECEIVE_SMS` |
| 需要 `signature` 级权限的（`ACTION_INTERNAL_*` 等） | ❌ **第三方应用原理上收不到**，配了也不会触发 |

⚠️⚠️ **`requiredPermissions` 不能按「接收本身要不要权限」写**，而要按
「**缺了它这个触发器还能不能工作**」写 —— 这是本仓库在 `SimDataSwitchTriggerModule` 上
踩过的坑：`TriggerService.handleWorkflowChanged` 会在**注册前**检查权限，缺失时
**静默把整个工作流置为 `isEnabled = false`**。权限齐全的测试设备上**完全测不出来**。

**本触发器的处理**：action 是用户自由填的，**无法静态推导需要什么权限**。
⇒ 见 §4「权限模型」的设计决策。

---

## 4. 配置 schema（输入）

模块 id：`vflow.trigger.broadcast`
图标：`rounded_settings_input_antenna_24`（「天线发射」造型，不与既有触发器撞车）

| # | 参数 id | 类型 | 默认 | 必填 | 说明 |
|---|---|---|---|---|---|
| 1 | `actions` | ANY（`List<String>`） | **空** | ✅ **是** | 要监听的 action 列表。⚠️ 见 §3.1：**没有通配写法，必须至少一条** |
| 2 | `data_schemes` | ANY（`List<String>`） | 空 | ⭕ | 需要匹配的 data scheme。⚠️ 见 §3.2：**留空 = 只收不带 data 的广播**，且**无 `*` 通配** |
| 3 | `categories` | ANY（`List<String>`） | 空 | ⭕ | 需要匹配的 category。`IntentFilter` 的 category 语义是「intent 必须**包含** filter 声明的全部 category」，为空则不约束 |
| 4 | `match_mode` | ENUM | `exact` | — | action 匹配方式。**只有 `exact`**（见下） |
| 5 | `cooldown_ms` | NUMBER | `1000` | — | 冷却窗口，按触发器独立计数 |

### 4.1 为什么没有 `match_mode` 的第二种取值

讨论稿考虑过 `contains`（子串匹配 action）。**不做**，理由：

**【源码】** `matchAction` 是 **exact contains**（`mActions.contains(action)`）。
要做子串匹配就得**放弃 `IntentFilter` 的 action 匹配**，改成
「注册必须宽到能收到 → 在 `onReceive` 里自己筛」。

而「注册得足够宽」这件事**没有合法写法**（§3.1：不能通配、不能不声明）。
⇒ **子串匹配在平台上做不出来**，与其提供一个「看起来能用实际永远收不到」的选项，
不如**只给 `exact`**。

> 保留 `match_mode` 字段本身是为了**与仓库其它触发器的 schema 形态一致**
> （它们普遍有这个字段），且为将来留位置。UI 上不给第二个选项。

### 4.2 为什么是 `List<String>` 而不是逗号分隔字符串

参考 `AppPackageTriggerModule` 的 `packageNames`（`ParameterType.ANY` + `List<String>`）。
action 含 `.` 和 `_`，用逗号分隔虽可行，但：

- 用户常需要**多条**（一个自动化往往同时关心好几个 action）；
- 需要**增删/排序**的交互（列表型 UI 比一行文本可读性好得多）。

⇒ 走 `ParameterType.ANY` + 自绘 UIProvider（形态照 `NotificationTriggerUIProvider`）。
⚠️ **不要**用 `createTextInputLayout` 做这个输入（见 §7 静默失效点 6）。

### 4.3 权限模型：**声明为空，改用运行时诊断**

| 方案 | 评价 |
|---|---|
| 静态声明一个大而全的权限集合 | ❌ 会无条件向用户索要 `RECEIVE_SMS` 等敏感权限，而大多数广播不需要 |
| 按 action 前缀猜权限（`android.provider.Telephony.*` → `RECEIVE_SMS`） | ❌ 猜测不完整且会误判（用户可以配自定义 action） |
| **`requiredPermissions = emptyList()` + 捕获 `SecurityException` 并**在日志里明确报出 | ✅ **选它** |

理由：**接收权限不足时，`registerReceiver` 会抛 `SecurityException`**
（部分场景是静默收不到，见 §7 静默失效点 2）。
Handler 里 `try/catch` 住并把「缺哪个权限」写进日志，比在 `requiredPermissions` 里
瞎声明一堆要诚实得多。

⚠️ **代价如实记录**：这是**唯一**一个「用户可能配了但收不到、且我们无法提前拦」的触发器。
缓解手段是 **§6 的自检按钮**（详见实施清单）。

---

## 5. 输出变量（工作流下游引用）

数据载荷：

```kotlin
@Parcelize
data class BroadcastTriggerData(
    val action: String,
    val dataUri: String,       // Intent.getDataString()，无 data 时为空串
    val scheme: String,        // data 的 scheme，无 data 时为空串
    val mimeType: String,      // Intent.getType()
    val categories: List<String>,
    val extrasJson: String,    // 见 §5.2
    val flags: Int,
    val truncated: Boolean,    // extrasJson 是否被截断
) : Parcelable
```

输出定义（`getOutputs`）：

| # | 输出 id | 名称 | 类型 | 说明 |
|---|---|---|---|---|
| 1 | `action` | 广播 Action | `vflow.type.string` | 实际收到的 action 原文 |
| 2 | `data_uri` | 数据 URI | `vflow.type.string` | 完整 data 字符串，无则空串 |
| 3 | `scheme` | 数据 Scheme | `vflow.type.string` | 便于 `If` 判断而不必自己切字符串 |
| 4 | `mime_type` | MIME 类型 | `vflow.type.string` | `Intent.getType()` |
| 5 | `categories` | 分类 | `vflow.type.list`（元素 string） | 常见用法：`If <categories> 包含 com.android.xxx` |
| 6 | `extras_json` | 附加数据（JSON） | `vflow.type.string` | ⚠️ 见 §5.2 |
| 7 | `flags` | Flags | `vflow.type.number` | 诊断用（FLAG_* 位） |
| 8 | `truncated` | 附加数据是否被截断 | `vflow.type.boolean` | ⚠️ 见 §5.3 |

### 5.1 为什么不提供 `sender_package`

见 §3.5：**取不到**（默认 null）。提供一个「永远是空串」的输出会让用户以为
「这个 App 是匿名的」，而真实原因是平台不给 —— 属误导。

### 5.2 `extras_json` 的形态：**不逐键建输出**

与 `ActivityChangedTriggerModule` 的 `extras_json` **同一决策**：
extras 的**键不可枚举**（任何应用都能塞任意键），因此只给一个 JSON 字符串，
下游用 `vflow.data.parse_json` 取具体键。

**编码规则直接复用 `ActivityPayload` 的既有经验**（`xposed/wire/ActivityPayload.kt`）：

| 规则 | 理由 |
|---|---|
| 按**真实类型**写（String `"1"` ≠ Int `1`） | `dumpsys` 那条路丢类型的教训（米家「无账号权限」的真因） |
| 按**字节**计预算，不按字符 | `ActivityPayload` 实测过：按字符计在全 CJK 下**低估 3 倍** |
| **逐键累加、超限即停**（不是「拼好再砍」） | 砍一半的 JSON 不合法 ⇒ 下游**整个 extras 都拿不到**，而不是「少几个键」 |
| 无法编码的类型（自定义 Parcelable）写 `<unencodable:类名>` | 保留「有这个键」的事实，不静默丢 |

> ⚠️ **不要直接调用 `ActivityPayload.encode`** —— 它的字段是 activity 语义（`intent_uri` /
> 组件名）。应当把「extras 编码」这一段（`encodeExtras` / `putTyped` / `truncateToBytes`）
> **提取成共享的纯函数层**，两处引用同一份。
>
> ⚠️ **提取方向已被核实（2026-10-07 读 `WireLayerPurityTest.kt`）**：
> 白名单是 `org.json.` / `java.` / `kotlin.` / `com.chaomixian.vflow.xposed.` 四项，
> 且另有 `FORBIDDEN_SUBSTRINGS`（`android.util.Log` / `android.content` / `android.os.` …）
> 拦全限定名写法。⇒ **`ActivityPayload` 不能 import `core.*`**，
> 所以只能**反向**：把纯函数提取到 `com.chaomixian.vflow.xposed.` 前缀下
> （或新建一个同前缀的文件），由 App 侧的广播触发器去 import 它。
>
> ⚠️ 语义上「广播触发器依赖 `xposed/wire/`」是别扭的 —— 详见 §10 未决项 1 的三个候选落点。

### 5.3 为什么必须有 `truncated`

同 `ActivityChangedTriggerData` 的理由：载荷超限被截断时，**必须让用户知道**。
不然「extras 少了几个键」会被当成「那个应用本来就没传」，他会去查错的地方。

---

## 6. 与既有触发器的关系

| 既有触发器 | 粒度 | 本触发器的差异 |
|---|---|---|
| `vflow.trigger.app_package` | 仅 `ACTION_PACKAGE_*` 三个 action | 本模块能监听**任意** action |
| `vflow.trigger.power` / `screen` / `battery` / `bluetooth` / `wifi` / `call` / `sms` / `do_not_disturb` | 每个**硬编码一组** action | 本模块是它们的**超集** |
| `vflow.trigger.sim_data_switch` | 硬编码单条 action（且必须 `EXPORTED`） | 同上 |

⚠️ **本模块不取代任何一个既有触发器**，理由：

1. 既有触发器的**参数是结构化的**（如电源的「已连接/已断开」是枚举、短信号码过滤是**语义化**的），
   本模块只能给原始 `extras_json`，下游要自己解析；
2. 既有触发器**已发布**，改动它们等于破坏存量工作流；
3. 既有触发布局里，用户**找得到**「电源触发」但不会想到去配 `ACTION_POWER_CONNECTED`。

⇒ **定位**：本模块是**逃生舱**——覆盖那些「既有触发器没覆盖到、且不值得单独做一个模块」的广播
（如某个第三方 App 的自定义 action、`ACTION_MEDIA_MOUNTED`、`ACTION_HEADSET_PLUG` 等）。

### 6.1 与既有 10 个广播型触发器的逐条对照（实现期实测，2026-10-07）

上面那张表是定性描述。实现期逐 Handler 读了 `IntentFilter` 构造，这里是**逐字**的 action 集合：

| 既有触发器 | 监听的 action（逐字取自 Handler） | 本模块能否替代 |
|---|---|---|
| `app_package` | `ACTION_PACKAGE_ADDED` / `_REMOVED` / `_REPLACED` + `addDataScheme("package")` | ✅ 能（但要自己填 scheme=`package` + 自己解析 `data.schemeSpecificPart` + 自己判 `EXTRA_REPLACING`） |
| `battery` | `ACTION_BATTERY_CHANGED`（**sticky**，注册即回调一次） | ✅ 能（但要自己算百分比与跨阈值方向） |
| `bluetooth` | `ACTION_STATE_CHANGED` / `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` | ✅ 能 |
| `call` | `ACTION_PHONE_STATE_CHANGED` | ✅ 能（但要自己解 `EXTRA_STATE` 三态） |
| `do_not_disturb` | `ACTION_INTERRUPTION_FILTER_CHANGED` | ✅ 能（但要自己读 `NotificationManager` 判方向） |
| `power` | `ACTION_POWER_CONNECTED` / `_DISCONNECTED` | ✅ 能 |
| `screen` | `ACTION_SCREEN_ON` / `_OFF` / `ACTION_USER_PRESENT` | ✅ 能 |
| `sms` | `SMS_RECEIVED_ACTION` | ⚠️ 形式上能，但**需要 `RECEIVE_SMS` 权限**，而本模块 `requiredPermissions` 为空 ⇒ 配了也收不到（这正是 §4.3「无法静态推导权限」的代价） |
| `wifi` | `WIFI_STATE_CHANGED_ACTION` | ✅ 能（但要自己解 `EXTRA_WIFI_STATE` 四态） |
| `sim_data_switch` | `ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED` | ✅ 能（但要自己解 subId、自己映射卡槽） |

⇒ **本模块在能力上确是这 10 个的超集**，但**每一个替代都需要用户在下游重写该触发器已内置的解析逻辑**
（方向判定 / 枚举归一 / 权限 / sticky 基线）。这就是「不取代」的**量化依据**，也是
`FORK.md` 只能写「不取代」而不能写「可替代」的原因。

⚠️ **存量工作流里没有任何东西需要迁移** —— 本模块是纯新增，既有 10 个 Handler **一行都不动**。

---

## 7. 静默失效点清单（**逐条核对，这是本文档最有价值的部分**）

| # | 失效点 | 表现 | 对策 |
|---|---|---|---|
| 1 | **`actions` 为空** | 配了但**永不触发**，无任何报错 | §3.1：UI 必填校验 + 运行时显式报错，**不许沿用「空 = 任意」的仓库惯例** |
| 2 | **`RECEIVER_NOT_EXPORTED`** | 第三方应用发的广播**收不到**；系统定向广播正常 ⇒ 表现为「有的能用有的不能」 | §3.3：一律 `EXPORTED` |
| 3 | **该填 scheme 没填** | 带 data 的广播**整条收不到**（§3.2） | UI 上把「留空 = 只收无 data 的广播」写清楚；`ACTION_PACKAGE_*` 等预置一条「需要 scheme=package」的提示 |
| 4 | **`addDataScheme("*")` 当通配用** | 静默收不到任何带 data 的广播（§3.2） | 代码里禁止 `"*"`（加断言 / 单测锁） |
| 5 | **两处注册漏一处** | 能选能配、后台永不触发（`trigger-system-overview.md` §8.2 短板 1） | `ModuleRegistry` + `TriggerHandlerRegistry` 各追加一行 |
| 6 | **action 输入框用了普通文本控件** | 正常文本能存，但**增删/排序交互丢失**，用户看不出「这是一个列表」 | 自绘 UIProvider（§4.2） |
| 7 | **`TriggerService` 被降级**（后台通知关闭） | 所有监听类触发器一并失效 | 既有缺陷（§3.4），排障时先查 |
| 8 | **权限不足** | `registerReceiver` 抛异常 / 静默收不到 | §4.3：`try/catch` + 明确日志；自检按钮 |
| 9 | **`onReceive` 里做耗时操作** | ANR（`onReceive` 在主线程，10 秒超时） | 照 `SmsTriggerHandler` 的范式：`triggerScope.launch { ... }` |
| 10 | **`unregisterReceiver` 未配对** | 反复启停后 `IllegalArgumentException: Receiver not registered` | 照既有 Handler：`finally` 里置 `null` + `try/catch` |
| 11 | **冷却窗口被窗口内命中顺延** | 高频广播下**永不触发** | 照 `ActivityChangedTriggerHandler` 的既有结论：**命中不记账** |
| 12 | **`extras` 直接 `toString` 塞进输出** | 类型丢失（`1` 和 `"1"` 混同）；巨型 Bundle 撑爆内存 | §5.2：按真实类型 + 字节预算 |
| 13 | **把 `extras_json` 当 `VDictionary` 直接给下游** | 下游 `{{id.extras_json.xxx}}` 取不到键（它是 JSON **字符串**，不是字典） | 与 `activity_changed` 一致：给字符串，让用户走 `vflow.data.parse_json` |

---

## 8. 决策台账

| # | 决策 | 依据 |
|---|---|---|
| 1 | 走 **App 层动态注册**，不用 Core / Xposed | §2（Core 收不到广播是既有实测） |
| 2 | **不设 token、不做发送方鉴权** | 用户 2026-10-07 明确否掉；且发送方身份默认取不到（§3.5） |
| 3 | **不暴露 `sender_package`** | §3.5（永远是 null，暴露即误导） |
| 4 | **`actions` 必填，无通配** | §3.1（平台没有通配写法） |
| 5 | `match_mode` **只给 `exact`** | §4.1（子串匹配在平台上做不出来） |
| 6 | **一律 `RECEIVER_EXPORTED`** | §3.3（用户会配第三方应用的 action） |
| 7 | `data_schemes` 留空 = **只收无 data 的广播** | §3.2（平台语义，不能做成「任意」） |
| 8 | `requiredPermissions = emptyList()` + 运行时诊断 | §4.3（无法静态推导） |
| 9 | `extras_json` **不逐键建输出** | §5.2（键不可枚举，同 `activity_changed`） |
| 10 | 必须提供 `truncated` 输出 | §5.3（同 `activity_changed`） |
| 11 | **不取代**任何既有触发器 | §6（参数结构化 vs 原始 JSON） |
| 12 | 冷却窗口内命中**不记账** | §7-11（同 `ActivityChangedTriggerHandler`） |
| 13 | 图标用 `rounded_settings_input_antenna_24` | 不与既有触发器图标撞车（既有 `rounded_broadcast_on_*_24` 是别的语义） |

---

## 9. 实施清单

| # | 文件 | 内容 | 状态 |
|---|---|---|---|
| 1 | `core/workflow/module/triggers/BroadcastTriggerModule.kt` | 模块定义 + schema + outputs | ✅ |
| 2 | `core/workflow/module/triggers/BroadcastTriggerData.kt` | `@Parcelize` 载荷 | ✅ |
| 3 | `core/workflow/module/triggers/handlers/BroadcastTriggerHandler.kt` | 动态注册 + 过滤 + 冷却 | ✅ |
| 4 | `core/workflow/module/triggers/BroadcastTriggerUIProvider.kt` | action / scheme 列表编辑 | ✅ |
| 5 | `core/.../triggers/handlers/TriggerHandlerRegistry.kt` | **追加一行**注册（不重排） | ✅ |
| 6 | `core/workflow/module/ModuleRegistry.kt` | **追加一行**注册（不重排） | ✅ |
| 7 | 三语 `strings_module.xml` | 模块名/描述/参数名/选项/hints/输出名/摘要/错误 | ✅（33 键 ×3 语言） |
| 8 | `res/drawable/rounded_settings_input_antenna_24.xml` | 已存在（`MaterialSymbolNames` 全量库里有） | ✅ |
| 9 | `test/.../triggers/BroadcastTriggerModuleTest.kt` | 声明体检（形态照 `ActivityChangedTriggerModuleTest`） | ✅（24 例） |
| 10 | `test/.../triggers/BroadcastTriggerHandlerTest.kt` | 过滤/冷却/归一化语义 | ✅（21 例） |
| 11 | 纯函数层单测 | 「`"*"` 必须被拒绝」「空 action 必须拒绝」等反向锁 | ✅（22 例） |
| 12 | **`FORK.md` 登记** | 新增文件 + 两处注册行 | ✅ |
| 13 | 自检按钮（可选，P2） | 配好后点一下，用 `sendBroadcast` 自发一条自测广播，确认链路通 | ❌ **未做**（见下） |

### 9.0 实施期新增的（设计文档原清单里没有的）四类文件

| 文件 | 内容 | 例数 |
|---|---|---|
| `core/workflow/module/triggers/BroadcastTriggerSupport.kt` | **纯函数层**（归一化 / 校验 / 列表解构）。无 Android 依赖，可纯 JVM 单测 | — |
| `res/layout/partial_broadcast_trigger_editor.xml` | UIProvider 的三段式布局（三个 RecyclerView + 三个添加按钮 + 提示） | — |
| `test/.../triggers/BroadcastTriggerSupportTest.kt` | 上述纯函数的单测（含四类坑的反向锁） | 22 |
| `test/.../triggers/BroadcastTriggerWiringTest.kt` | **源码扫描型接线锚定**（注册行 / EXPORTED / 空 actions 不注册 / 委托未留第二份实现 / 预算未被改回 48 KiB） | 13 |

> ⚠️ **2026-10-07 独立复核后的收紧**：`BroadcastTriggerWiringTest` 里那条
> `the module overrides validate so empty actions cannot be saved` 原本**只断言**
> 「源码里有 `override fun validate(` 且两个错误文案键在文件里」—— 实测把 `validate()` 的
> 两处 `ValidationResult(false, …)` 改成 `true`，**全部 95 例新测试照旧全绿**。
> 现追加逐分支断言（截取 `ValidationResult(` 之后的实参、断言第一个实参是 `false`），
> 三条反证（EMPTY 改 true / WILDCARD 改 true / 删掉 override 断言）**均确认变红**。
> ⚠️ 行为测试（直接调 `validate()` 断言 `isValid`）在**纯 JVM 里做不到**：
> 错误分支要读 `appContext.getString(...)`，而 `Context.getString` 是 `final`、
> `Resources` 构造器要 package-private 的 `AssetManager`、本项目无 Robolectric/mockito。
| `test/.../xposed/ExtrasJsonCodecTest.kt` | 提取出来的编码层单测（从 `ActivityPayloadTest` 平移 + 直调 `ExtrasJsonCodec`） | 15 |
| `xposed/wire/ExtrasJsonCodec.kt` | **提取**自 `ActivityPayload` 的 extras 编码层（见 §10.1 的决定） | — |

**第 13 项（自检按钮）为什么不做**：它需要额外的自测 action + 一套 UI 入口，
会显著扩大 diff 面积；而它要解决的「配好了却不知道通不通」有一个**零 UI 的等价物** ——
`registerFor` 成功时打 INFO 日志（含 action / scheme / category 三个数量），
失败时打 ERROR（含 actions 列表与异常）。用户可在设置页「查看日志」里自查。
⇒ 用日志替代自检按钮。

### 9.1 ⚠️ 实施期发现：`validate()` 的真实作用面（比设计时以为的窄）

实测全仓 `module.validate(...)` 的调用点共 **5 处**：

| 位置 | 覆盖触发器？ | 说明 |
|---|---|---|
| `ui/workflow_editor/ActionEditorSheet.kt` | ✅ | `showTriggerEditor` 用的就是这个 sheet ⇒ **在触发器卡片上保存时会拦** |
| `ui/workflow_editor/WorkflowEditorActivity.kt`（`saveWorkflow`） | ❌ | 循环的是 `actionSteps`，`triggerSteps` 是另一个列表、**一个都不校验** |
| `ui/chat/ChatAgentModuleExecutor.kt`（`temporary_workflow`） | ✅ | |
| `ui/chat/ChatAgentModuleExecutor.kt`（`save_workflow`） | ✅ | |
| `ui/chat/ChatAgentModuleExecutor.kt`（`update_workflow`） | ✅ | `allSteps = updated.triggers + updated.steps` |

⇒ 兑现「actions 必填」的**第二道防线**是 Handler 的 `filterSpecOf()` 返回 null
（**不注册 receiver** + WARN 日志），那一道覆盖所有路径（含 JSON 导入与直接改 prefs）。
**本批不改 `WorkflowEditorActivity.saveWorkflow`**（那会动既有保存路径、扩大 diff 面积）。

### 9.2 建议的纯函数层（可纯 JVM 单测）

照本项目「把易错的语义提成纯函数」的惯例（`FoldStateResolver` / `SimDataSwitchMath`）：

```kotlin
object BroadcastTriggerSupport {
    /** 归一化 action 列表：去空白 / 去重 / 丢弃空串。⚠️ 不得接受 "*"（§3.1） */
    fun normalizeActions(raw: List<String>): List<String>

    /** 校验：空列表 → 报错文案；含 "*" → 报错文案（§3.1 / §7-1 / §7-4） */
    fun validateActions(raw: List<String>): BroadcastValidation

    /** 归一化 scheme：同样禁 "*"（§3.2） */
    fun normalizeSchemes(raw: List<String>): List<String>
}
```

**这些是「改错了不报错、只静默变差」的地方，必须有测试 + 反证。**

#### 9.2.1 实施期对这段骨架的三处调整（都是「纯函数层不该依赖 Android 资源」）

1. `validateActions` 的返回类型由 `BroadcastValidation` 改为**纯枚举**
   `ActionsValidation { OK, EMPTY, WILDCARD }` —— 文案由模块层翻，纯函数层不认识 Android 资源。
2. 新增 `stringListOf(raw: Any?)` —— `parameters` 里存的是 `Any?`，
   可能是 `List<*>` / 单个 `String` / `null`（JSON 导入与 AI 写入会给不同形状）。
   ⚠️ `List<*>` 里的**非 String 元素被丢弃而不是 `toString()`**：
   把 `123` 变成 `"123"` 会造出一个**永远不会匹配**的 action，而用户看到列表里那一项「长得没问题」。
3. `stripBlank` **不丢弃 `"*"`**（与 `normalizeActions` 分工）——
   编辑期要留给 `validateActions` 拦并给出文案。若在 `stripBlank` 里悄悄丢掉，
   用户输入 `"*"` 后点保存会看到那一项**凭空消失**、既没报错也不知道为什么。

⚠️ 另核：`trim()` 必须用 **Kotlin 默认语义**（`Char.isWhitespace()`，覆盖全角空格 U+3000）。
用 `trim(' ')` 会漏掉全角空格 ⇒ 保存出一个「看起来没填、实际是 `"　"`」的项，
而它在 `IntentFilter` 里是个合法字面量（永不命中、无报错）。有测试锁住。

---

## 10. 未决项（**六条全部已定案**，2026-10-07）

| # | 问题 | 影响 | **决定** |
|---|---|---|---|
| 1 | `extras` 编码层与 `ActivityPayload` 如何共享 | ⚠️ **已核实（读 `WireLayerPurityTest.kt`）**：wire 层白名单只有 `org.json.` / `java.` / `kotlin.` / `com.chaomixian.vflow.xposed.`，且有 `FORBIDDEN_SUBSTRINGS` 拦全限定名。⇒ `ActivityPayload` **不能**依赖 App 侧代码；只能 App 侧反向依赖它 | ✅ **候选甲**：新文件 `xposed/wire/ExtrasJsonCodec.kt`，`ActivityPayload` 改为委托它。乙被白名单排除（wire 层不能 import `core.*`）、丙违反仓库「不重复」纪律。见 §10.1 |
| 2 | 是否需要「按 extras 键值过滤」 | 会显著复杂化 schema（嵌套的可增删键值对） | ✅ **不做**。用户在下游用 `If` + `vflow.data.parse_json`（`ParseJsonModule` 已存在）自己判即可 |
| 3 | 自检按钮的形态 | 需要一个不打扰用户的自测广播 action | ✅ **不做**（P2 之外）。替代：`registerFor` 成功打 INFO（含三个数量）、失败打 ERROR（含 actions 与异常），用户在「查看日志」里自查 —— **零额外 UI** |
| 4 | `categories` 是否值得暴露 | 用得少，但白白增加一个输入 | ✅ **保留**。成本极低（一个列表参数 + 一个 `addCategory` 循环），且是排除噪声的实用手段 |
| 5 | sticky 广播 | `isInitialStickyBroadcast()` 在注册时会**立刻回调一次**（如 `ACTION_BATTERY_CHANGED`）。本设计**不主动注册 sticky 广播**，但用户若配了会踩到「注册瞬间触发一次」 | ✅ **代码不做特殊处理，仅 UI hint 提示**（`editor_vflow_trigger_broadcast_sticky_hint`）。⚠️ 用 `ContextCompat.registerReceiver` 四参形式（有 flag 参数、能声明 EXPORTED；平台两参形式做不到） |
| 6 | 是否需要「进程重启后恢复」 | 动态注册依赖 `TriggerService` 存活；服务重启后由既有链路重新 `addTrigger` | ✅ **复用既有链路，不额外做**。真实链路：`TriggerService.onCreate` → `TriggerHandlerRegistry.initialize()` → `registerAndStartHandlers()` → 逐个 `addTrigger` → 我们的 `registerFor` 重建 receiver |

### 10.1 未决项 1 的三个候选落点（**已选甲**）

| 候选 | 优点 | 缺点 |
|---|---|---|
| **甲**：把纯函数提取到 `com.chaomixian.vflow.xposed.wire.` 下新文件（实现时命名 `ExtrasJsonCodec.kt`），广播触发器 import 它 | 改动最小；`ActivityPayload` 保持纯度测试通过 | ⚠️ **语义别扭**：App 层的广播触发器要 import `xposed.wire.*`，与「xposed 包只服务 hook 层」的既有认知相悖，后来者会困惑 |
| **乙**：新建一个与两者都无关的纯 JVM 工具包（如 `core/util/JsonBundleCodec.kt`），两处都依赖它 | 语义最干净（名实相符） | ⚠️ `ActivityPayload` **不能** import 它（白名单不允许 `core.`）⇒ **必须把 `ActivityPayload` 里的实现改成调用它**，而 `ActivityPayload` 本身在 wire 白名单下 ⇒ **行不通**，除非同时把该工具包放进白名单（等于放宽约束） |
| **丙**：**各写一份**（广播触发器自己实现 extras 编码） | 零约束冲突，改动面最小 | ⚠️ 本仓库**明确记过**「双份实现」的代价（`FORK.md` logcat 条：「语义改动必须同时改两处，不一致的表现是调试工具能匹配而触发器匹配不到」） |

**✅ 决定：选甲**（2026-10-07 定案）。新文件 `ExtrasJsonCodec.kt` 的 KDoc 首段就把
「它被 App 侧的广播触发器复用，不是 hook 专用」写清楚，把「语义别扭」**在代码里解释掉**。

**先例（不是首创）**：`core/xposed/HookChannelController.kt`、
`core/workflow/module/triggers/handlers/ActivityChangedTriggerHandler.kt`
都已经在 import `xposed.wire.*` —— wire → App 的单向引用本就是本仓库既有常态。

**搬迁纪律（实现时逐条核过）**：
1. `encodeExtras` 的函数体一字不改搬进 `ExtrasJsonCodec.encode`，
   只把 `MAX_EXTRAS_JSON_BYTES` 换成 `maxBytes` 参数；
2. `ActivityPayload.encodeExtras(extras)` 保留为**委托**（返回类型改为 `ExtrasJsonCodec.Result`）；
3. `ActivityPayload.encode` 里的 `truncateToBytes(...)` 改 `ExtrasJsonCodec.truncateToBytes(...)`；
4. 被搬走的两个 `private` 实现与 `internal data class ExtrasResult` **不得留残影**；
5. `MAX_INTENT_URI_BYTES` / `MAX_EXTRAS_JSON_BYTES` **原地保留**
   （`ActivityPayloadTest` 引用了它们，搬走会让测试编译失败、扩大 diff）。
   ⚠️ 另外给 `MAX_EXTRAS_JSON_BYTES` 补了一句 KDoc：「它的依据是 Binder oneway 半缓冲，
   **不要套到同进程传递的场景上**」—— 正是这个数值被误用过一次（见 §12.1）；
6. `WireLayerPurityTest` 的文件存在性清单追加 `ExtrasJsonCodec.kt`。

⚠️ 设计时写的「**无论选哪个，都必须补一条「两处行为一致」的测试**」——
**选甲之后不需要**那条测试：两处**共用同一份实现**，行为**结构上不可能漂移**。
（那条要求是给候选丙准备的。）改为一条**源码扫描断言**：`ActivityPayload` 必须
委托 `ExtrasJsonCodec`、且不得留下第二份 `putTyped` / `truncateToBytes` ——
盖住的是「提取之后有人把实现拷回来」这一种回退路径。已实现在 `BroadcastTriggerWiringTest`。

---

## 11. 真机验证清单（**4 项已做 / 10 项待做**）

⚠️ **本设计的全部平台行为结论来自源码 / 官方文档核实**。
**2026-10-07 首轮真机验证（用户手工）已过 4 项**，见 §11.1；其余各项仍待上机确认。

| # | 验证项 | 判据 |
|---|---|---|
| 1 | 自定义 action 广播能收到 | 用 `am broadcast -a com.test.XXX` 触发 | ✅ **已做**（§11.1） |
| 2 | **第三方应用**发的广播能收到（证明 `EXPORTED` 必要） | 用一个独立 App 发；对照 `NOT_EXPORTED` 必须收不到 |
| 3 | 带 data 的广播：不填 scheme 收不到（`test://` 这种非例外 scheme） | `am broadcast -a X -d "test://a"` |
| 3b | ⭐ **`content://` / `file://` 是例外，不填 scheme 也收得到** | `am broadcast -a X -d "content://a/b"` 与 `-d "file:///sdcard/x"` 都应触发（§3.2 源码核实的例外，见下方「未验证」提醒） |
| 3c | ⭐ **显式填了 scheme 之后，例外消失** | 把 3b 的触发器 `data_schemes` 改成 `["content"]` ⇒ `content://` 仍触发、**`file://` 变成收不到**（`{"content"}.contains("file")` = false） |
| 4 | 带 data 的广播：填了正确 scheme 能收到 | 同上 + `addDataScheme("test")` |
| 5 | `addAction("*")` 确实收不到 | 反向验证 §3.1 |
| 6 | `ACTION_PACKAGE_ADDED` + `scheme=package` 能收到 | 装一个 APK |
| 7 | extras 各类型（String/Int/Bool/List/Parcelable）编码正确 | `extras_json` 解回来逐类型核对 | ⚠️ **部分已做**：String / Int 已验（逐类型正确）；Bool / List / Parcelable **未验** |
| 8 | 超大 extras 被截断且 `truncated=true` | 塞一个 60 KiB 的 String |
| 9 | 关闭后台服务通知后失效（既有缺陷） | 复现 §7-7 |
| 10 | 冷却窗口内高频广播不刷爆 | 循环发 100 条 | ⚠️ **部分已做**：1 秒内连发 2 条只触发一次、内容是先到的那条 ⇒ **窗口内命中不记账**的语义成立；「循环 100 条」未做 |

**实现期（2026-10-07）新增的 4 项**：

| # | 验证项 | 判据 |
|---|---|---|
| 11 | `match_mode` 的中文/日文显示是否正确（**低成本确认**） | 编辑器里「匹配方式」应显示「精确匹配」，**不是**裸常量 `exact`。已核我们的模块走的是**会传 `optionsStringRes`** 的那条渲染路径（`ActionEditorSheet` 的 CHIP_GROUP 分支），此条只是确认；真的显示裸常量时的处置是**把这个参数从 schema 里去掉**（它只有唯一取值），**不是**换 `inputStyle` |
| 12 | UIProvider 三个列表的增删/编辑真的能落盘 | 加 3 条 action → 保存 → 重开编辑器回显一致；删一条 → 保存 → 回显一致；确认 `ListItemAdapter` 的 RecyclerView 复用后**输入框文本没写错行** |
| 13 | **两个广播触发器并存时不互相干扰** | 触发器 A 配 `ACT_A` + scheme `test`，触发器 B 配 `ACT_B`（无 scheme）；`am broadcast -a ACT_A -d test://x` 只触发 A；`am broadcast -a ACT_B` 只触发 B。⚠️ **本条验的是「各管各的」**，价值在于**确认 `registerFor` 的重建逻辑没有串台**（增删改各触发器后 filter 仍与各自参数对应） |
| 14 | **空 `actions` 的存量触发器不误触发** | 手工往工作流 JSON 塞一个 `actions: []` 的广播触发器 → 后台**不应**对任意无 action 广播触发（日志里应有 `未配置有效 action，已跳过注册` 的 WARN）。⚠️ 编辑器路径保存不出这种配置（`validate()` 拦了），只有导入/AI 路径能造出来 |

### 11.1 2026-10-07 首轮真机验证结果（用户手工）

| # | 用例 | 结果 | 证据 |
|---|---|---|---|
| 1 | 正常触发 + extras 传递 | ✅ | `am broadcast -a com.vflow.TEST_BROADCAST --es msg hello --ei num 42` ⇒ 命中并触发，`extras_json={"msg":"hello","num":42}`，**字符串与整型都保持原类型**（未被猜成同一类型 —— 这正是 `ExtrasJsonCodec.putTyped` 要防的，对照 `dumpsys` 那条有损链路） |
| — | action 不匹配 | ✅ | 发 `com.vflow.OTHER_ACTION` **完全不触发** ⇒ filter 的 action 维度生效 |
| 10（部分） | 冷却 | ✅ | 1 秒内连发 aaa / bbb，只触发一次且内容是 **aaa**（先到的） ⇒「窗口内命中不记账（窗口不滑动）」的语义在真机成立 |
| 7（部分） | extras 类型 | ⚠️ 部分 | 仅 String / Int 验过；Bool / List / Parcelable 未验 |

#### ⚠️ 首轮报告里「`truncated` 标志不可靠」的疑似 bug —— **已实跑证伪，不是缺陷**

报告称「发 5000 字节的 `--es big`，`extras_json` 只留了约 1000 字符但 `truncated=false`」。
按它给的参数在 `ExtrasJsonCodec.encode`（8 KiB 预算）上实跑：

```
n=5000  → bytes=5010  truncated=false   ← 报告里的用例
n=8170  → bytes=8180  truncated=false
n=8190  → bytes=2     truncated=true    ← 越过预算，如实置位
n=20000 → bytes=2     truncated=true
```

⇒ **5000 字节本来就没超 8192 的预算**，`truncated=false` **是正确的**；
`JSONObject(r.json).getString("big").length == 5000`，值完整。

报告看到的「约 1000 字符」来自**它的观测通道**：`SendNotificationModule` 用的是
`NotificationCompat.setContentText(message)` 且**没有 `setStyle(BigTextStyle)`**
⇒ **通知正文本身就只显示开头一屏**。被砍的是**展示**，不是数据；
而 `truncated` 反映的是**数据**完整性，与通知能显示多少无关。

⚠️ 顺带记一处**非缺陷**的既定行为：**单键超预算时该键被整体丢弃**、`extras_json` 变 `{}`
（「逐键累加、超限就停」，保证截断后仍是**合法 JSON** —— 先拼好再砍会得到半截 JSON、
下游 `JSONObject()` 抛 ⇒ **整条 extras 都拿不到**）。`truncated=true` 正确反映了它。

⚠️ **方法论教训（比结论本身更值得记）**：**不能用有损的展示层当数据的证据** ——
「通知只显示 1000 字」推不出「数据只有 1000 字」。判据是**字节**（8192），
而 5000 字符的 ASCII 是 5010 字节、远未越线；报告既没量字节、也没做对照项
（补发一条 9000 字节会立刻看到 `truncated=true`）。这正是 `FORK.md` 记过的
「`null`/`false`/异常必须先做对照项框范围再下结论」。

---

⚠️⚠️ **上面第 3b / 3c 两项的证据等级是【源码逐行读】，不是【实测】**（实现期无设备）。
按本仓库「写进文档的库行为断言先实跑再落笔」的教训，
**在真机上跑过之前，`content`/`file` 例外不得当成已证实**。

---

## 12. 引用前须知

### 12.1 ⚠️ extras 预算定为 **8 KiB**（而非照抄 `ActivityPayload` 的 48 KiB）

**这是实现期改动本设计原始意图的唯一一处**，留痕在此。

设计文档 §5.2 只说「照 `ActivityPayload` 的经验」，**没写具体数值**。实现初版照抄了它的
`MAX_EXTRAS_JSON_BYTES = 48 KiB`，理由是「那条值已实测标定」。**该理由不成立**：

| | `ActivityPayload`（hook 链路） | 广播触发器 |
|---|---|---|
| 传输方式 | **跨进程**（hook 层在 system_server，App 在应用进程） | **同进程**（`executeTrigger` → `Parcelable` → `ExecutionContext`） |
| 经过 Binder 吗 | ✅ 经过 | ❌ **不经过**（唯一经 PendingIntent/AMS 的是种子输出那一笔，内容是几十字节的 `triggerId`） |
| 48 KiB 的依据 | Binder **oneway 半缓冲 ≈508 KiB**，留足余量 | **没有对应物** |

⇒ 48 KiB 是**为一个不存在的上限**付的代价：用户看不到后半段 extras，而收益为零。

**但仍然必须设上限**（与 Binder 无关的两条，这两条才是真正成立的）：
1. extras 是**第三方应用可完全控制**的内容 —— 不设限就是让外部决定我们的内存占用；
2. 它会进 `VString` → `executionLogs` → **`SharedPreferences`**，
   而这个 App **没有版本历史、没有撤销**（`FORK.md` 记过卸载即全灭）。

**取值 8 KiB 的依据**：这类广播的 extras 通常就是几个毫秒级时间戳 + 少量 key/value
（实测远小于 1 KiB）。超出走 `truncated = true`，用户能看出来，不会被误当成「那个应用没传」。

⚠️ **只改广播触发器这一处**：`ActivityPayload.MAX_EXTRAS_JSON_BYTES` **不动**
（那条是跨进程传输，48 KiB 有实测依据）。同时在它的 KDoc 里补了一句
「它的依据是 Binder oneway 半缓冲，**不要套到同进程传递的场景上**」——
防的正是这次的误用被下一个人重演。

⚠️ 有**源码扫描反向锁**（`BroadcastTriggerWiringTest`）：广播 Handler 的**代码里**
不得出现 `48 * 1024`，且 `MAX_EXTRAS_JSON_BYTES` 的 KDoc 必须写着「不经过 Binder」。

- **所有源码引用基于 2026-10-07 的 AOSP `main` 分支**（`IntentFilter.java` /
  `BroadcastReceiver.java` / `BroadcastOptions.java` / `BroadcastController.java`），
  行号会漂移，**引用前以源码为准**。
- **本文档已于 2026-10-07 回写实现状态**（v1.1）：§6.1 / §9 / §9.0 / §9.1 / §9.2 / §10 / §11 / §12.1
  是实现期补的。**设计部分（§0–§8）未改**，仍是原始的判断依据。
- **实现已完成；首轮真机验证过了 4 项**（§11.1），其余各项见 §11 的待做清单。
  文档里凡标【源码】【官方文档】的是核实过的；标【推断】【待验证】的**不得当成结论使用**。
- 实施时请连同 §7 静默失效点清单一起过一遍。
