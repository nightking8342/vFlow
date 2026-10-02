# Xposed 脚本执行模式（三档）设计

> 状态：**设计阶段，未实现**。上位文档 `docs/fork/xposed-architecture-v2.md`（§5.1 / §5.2 / §10 #21）。
> 外部参照：`D:/develop/references/shortx/decompiled/`（仓库外，归档）。
> 起因：用户拍板「照搬 ShortX 的三种模式，并用『超时 + 无界队列』」——
> 本方案把这句话拆成可实施的东西，并指出**照搬不到的部分**。

---

## 1. 为什么做，以及一个必须先说的结论

### 1.1 ShortX 那三档的**真实构成**（已用字节码核实）

| 档 | 落到哪 | 容量 | 谁提供 |
|---|---|---|---|
| `Default` | `Dispatchers.Default` | core = `max(2, CPU核数)`，max = 2,097,150（弹性） | **kotlinx-coroutines 库** |
| `IO` | `Dispatchers.IO` | 上限 64（`limitedParallelism`） | **同一个库** |
| `UI` | `HandlerThread("SX-ShortXJS")` | **1** | 它自建（3 行） |

> ⚠️ **`UI` 档「为了什么」我们没有证据**。能确定的只有实现事实：
> 它是一个**带 `Looper` 的、懒加载单例的专属线程**（`MP.java:24` + `G00.java:3016`）。
> 它的 i18n 说明写「界面更新、显示弹窗、处理用户点击事件」，但**没有任何代码证据**
> 表明它和 `UiAutomationApi` 绑定（见 §2.1）。

⚠️ **两条容易搞错的事实**（都查过字节码）：

1. **`Default` 与 `IO` 是同一个线程池** —— `DefaultIoScheduler` 有一个静态字段
   `default = UnlimitedIoScheduler.limitedParallelism(N)`，而 `UnlimitedIoScheduler`
   转发到 `DefaultScheduler`（= `Dispatchers.Default` 的池）。
   ⇒ **两档的差别只有「有没有 64 那个并发信号量」，不是两条隔离的泳道。**
2. **`UI` 的 1 不是「配置成 1」，是「本来就是 1」** —— `HandlerThread` 内部就是一个
   `Looper`，而一个 `Looper` 只能挂 1 个线程。且它是**懒加载单例**（`MP.java:24`）
   ⇒ 所有选 `UI` 的脚本**共享同一个线程**。

### 1.2 协程库其实**已经在 APK 里**

`./gradlew :app:dependencies` 的 `releaseRuntimeClasspath` 实测：

```
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 -> 1.11.0
  └── kotlinx-coroutines-core:1.11.0
        └── kotlinx-coroutines-core-jvm:1.11.0
```

⇒ **不是「引新依赖」，是「让 hook 层能 import 它」**。⚠️ 但 `WireLayerPurityTest`
的 import 白名单**只管 `xposed/wire/`**，而 `capabilities/` 不在其中
⇒ **连白名单都不用改**（`kotlinx.*` 那条前缀下本来也不是 App 侧包）。
（`CapabilityInvoker` 的 KDoc 说「该白名单不含 `kotlinx.coroutines.` 所以本文件必须放外面」
—— 那句话**依据错了**，那个白名单管不到它。见 §9 待修。）

### 1.3 本方案的取舍（**已定：用协程**）

| 档 | 实现 |
|---|---|
| `default` | `Dispatchers.Default` |
| `io` | `Dispatchers.IO` |
| `ui` | **自建 `HandlerThread`**（协程没有 Looper 原语） |

- ✅ 与 ShortX **同构**（它也是 `Default` / `IO` / 自建 `HandlerThread`）
- ✅ **零自建线程池代码** ⇒ 也就**没有**「无界队列下 `maxPoolSize` 被忽略」那个坑
- ⚠️ **唯一的新风险**：`kotlinx.coroutines` 进 system_server 的类初始化
  （`MainDispatcherLoader` 的静态块做 `Class.forName("android.os.Build")` + 硬编码类名加载
  `AndroidDispatcherFactory`）。**必须真机验**，理由见 §9-1。

### 1.4 ⚠️⚠️ 「池满 ⇒ `handler_error`」的消失——**原因是「无界」，不是「协程」**

这一条我此前写反过，纠正：

```
ThreadPoolExecutor.execute 的字节码：
  workerCount < corePoolSize → addWorker
  else → workQueue.offer(task)
          ⚠️ 无界队列的 offer() 【恒返回 true】⇒ 走不到 reject()
  reject() 只在：offer 返回 false（有界满 / SynchronousQueue 无空闲 worker）或 已 shutdown
```

| 因素 | 是否影响「池满报错」 |
|---|---|
| **队列有界 / 无界** | ✅ **决定性的**（无界 ⇒ 恒不拒绝） |
| 协程 / 自建池 | ❌ **无关** |

⇒ 既然队列已定 **无界**，`CapabilityErrorCode` 里那段「池满也归 `HANDLER_ERROR`」
的 KDoc **本来就不可达了**，与选不选协程无关。**它要改成「历史/防御」或删除**（见 §9-2）。

⚠️ 两边「满了」的行为仍有差别（如实记录）：

| | 满时行为 |
|---|---|
| `Dispatchers.Default` | **弹性建线程**（并发不设上限）—— 与 ShortX 一致 |
| `Dispatchers.IO` | 64 并发信号量，超了**排队等** |
| `Dispatchers.UI`（自建） | **无界排队**（`Handler.post` 不拒绝） |

---

## 2. 三档的定义

| 档 | 稳定常量 | 语义（用户视角） | 实现 | 容量 |
|---|---|---|---|---|
| 默认 | `default` | CPU 密集（算） | `Dispatchers.Default` | 弹性（`max(2, 核数)` 起，无上限）—— **照搬，不拍数字** |
| IO | `io` | 阻塞型（等） | `Dispatchers.IO` | 64 并发 —— **照搬，不拍数字** |
| UI | `ui` | 需要 Looper | 自建 `HandlerThread` + `Handler` | **1**（照搬；`Looper` 的物理必然） |

⚠️ **不拍任何新数字** —— `default` / `io` 的容量就是 kotlinx 库的默认值
（与 ShortX 拿到的**是同一个**，因为用的是同一份库），`ui` 是 `Looper` 的物理必然。
⇒ 此前「三档容量待压测」那条未决项**自动消解**：我们没有可调的数字。
（若要调，那是「给用户配 `limitedParallelism(N)`」——**另一个功能**，见 §9-3。）

### 2.1 ⚠️ `UI` 档给的是什么能力，以及它的风险

- ✅ **能用 `new Handler()`** —— 现在做不到（我们的池线程**没有 `Looper.prepare()`**，
  脚本里 `new Handler()` 必然抛 `Can't create handler inside thread ... that has not called Looper.prepare()`，
  被顶层 `catch(Throwable)` 兜成 `handler_error`，用户只看到「脚本错误」）。
- ⚠️ **能 `WindowManager.addView`** —— 在 system_server 里 = **往整块屏幕上贴窗口**，
  且系统窗口没有「应用权限」兜底（用户关不掉，除非去 LSPosed 禁用模块）。
  ⇒ **本方案不禁止它**（做不到：脚本是黑盒），但要求**文案里写明**。
- ⚠️ **一处曾被我写错、现更正的事实**：ShortX 的 `UiAutomationApi`（`getRootInActiveWindow` /
  `dispatchGesture`…）**与 mode 无关** —— `script` 作用域里 `shortx` 对象的构造参数
  （`MP.java:23`）**不含任何 mode**，三种档位下 `shortx.getUiAutomation()` 都拿得到；
  它自身也**没有任何线程判据**（`C5718nC2.java` 里 `Looper|assert|checkThread` 零命中）。
  ⇒ **不要把它们绑成因果**：`UI` 档的**定义**只是「给一个带 `Looper` 的专属线程」；
  那个 API 是**正交**的既有能力。
- ⚠️ **我们据此仍然只做「给 Looper」**（不做配套 API），但理由是**我们没打算做**，
  **不是**「ShortX 这么做所以我们也这么做」——后者没有依据。

---

## 3. 出队判过期（**本方案的另一半，不可省**）

### 3.1 为什么必须做

**没有它，「超时 + 无界队列」会给用户一个假的失败提示**：

```
T+0     App 提交，① 开始计时
T+0s    入队（前面有 2 个在跑）
T+5s    ① 超时 → 工作流按错误策略继续/终止（用户看到「失败」）
        ⚠️ 而那个任务【还在队列里】
T+30s   出队、执行 → 脚本真的跑了（改系统状态、发广播、开窗口）
T+30s+  resolve 回来 → App 侧无配对 waiter → 丢弃 + 告警
```

⇒ **用户看到「超时失败」，副作用却发生了。** 对 `risk = HIGH` 的 `vflow.xposed.js` 不可接受。

### 3.2 实现

```kotlin
// onInvoke（binder 线程）—— 记到达时刻，这是全链路最早的点
val arrivedAtMs = System.nanoTime()

// runOnWorker（工作线程）—— 出队后【第一件事】
val queuedMs = (System.nanoTime() - arrivedAtMs) / 1_000_000L
val budget = InvokePolicy.effectiveTimeoutMs(request.timeoutMs, handler.timeoutMs)
if (budget != null && queuedMs > budget) {
    HookLog.e("$TAG  排队已超预算：${request.capability}（queuedMs=$queuedMs > budget=$budget）→ 不执行")
    emitFailure(request, InvokePolicy.timeoutError(queuedMs, budget), queuedMs)
    return   // ★★ 绝不调用 handler.handle
}
```

⚠️ **`budget == null`（不超时）时这个判据是 no-op** —— 与 §5.1 的语义一致
（「不超时」意味着没有预算可超）。

### 3.3 ⚠️ 顺带修掉一处**现存的两侧不一致**

| | 计时起点 | 现状 |
|---|---|---|
| App 侧 ① | **提交请求那一刻** | 含排队时间 |
| hook 侧 ② | **出队那一刻**（`runOnWorker` 首行 `val started = System.nanoTime()`） | **不含排队时间** |

⇒ 出现「App 在 T+5s 判超时，hook 侧认为只跑了 3 秒、没超」——**两侧对同一次调用是否超时判断相反**，
hook 侧会回一个 `Success`，而 App 早已按失败处理完。

⚠️ 本方案**只加出队判定，不改 ② 的起点**（那是另一处改动，见 §6 未决）。

---

## 4. 参数如何传到 hook 层

### 4.1 ⚠️ 不能走 `params`（分层约束）

`CapabilityRequest.paramsJson` 是**不透明**的（「信封层不认识任何业务字段」），
而**分发**发生在信封层（`onInvoke` 要选池）。⇒ 模式**必须在信封层可读**。

### 4.2 做法：给 `CapabilityRequest` 加一个字段（**同 `cursor` 的性质**）

`cursor` 已经是「信封层里的 capability 专用字段」的既有先例。照它加：

```kotlin
data class CapabilityRequest(
    …,
    val timeoutMs: Long? = null,
    val cursor: String? = null,
    /**
     * 执行模式（`default` / `io` / `ui`）。`null` ⇒ `default`。
     * ⚠️ 与 [cursor] 同类：信封层只**搬运**它，不解释它的业务含义。
     */
    val threadMode: String? = null,
    val token: String,
)
```

- **`encodeRequest`**：`threadMode` 非空才写键（照 `cursor` 的「不存在的键比 null 更明确」）。
- **`decodeRequest`**：缺失 / 无法识别的值 ⇒ **`null`**（= `default`）。
  ⚠️ **未知值必须静默降级为默认档**，不能报错 —— 那会让「新 App 发 `io`、旧 hook 层不认识」
  变成一次**调用失败**。而降级到 `default` 的后果只是「资源画像不准」，远轻于失败。
- ⚠️ **AIDL 不动**（`CapabilityRequest` 是 codec 类，不是 AIDL 面）。

---

## 5. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `XposedJsModule.kt` | 加 `thread_mode` 输入（`ParameterType.ENUM`，稳定常量，三语标签）；取参后塞进 `params` |
| 2 | `XposedJsSupport.kt` | 加 `normalizeThreadMode(raw: String?): String` 纯函数（未知值 → `default`） |
| 3 | `CapabilityInvocation.kt` | `CapabilityRequest.threadMode` + codec 两个方向 |
| 4 | `CapabilityInvoker.kt` | `invoke(...)` 加 `threadMode` 参数并透传 |
| 5 | **`HookCapabilityRuntime.kt`** | `Dispatchers.Default` / `IO` + 自建 `HandlerThread`（`asCoroutineDispatcher`）+ **出队判过期** + 按 mode 分发；`pool` → `CoroutineScope`，`shutdownNow` → `cancel` |
| 6 | `InvokePolicy.kt` | 新增 `threadModeOf(request)`（纯函数，未知 → `default`） |
| 7 | 三语 `strings_module.xml` | `param_vflow_xposed_js_thread_mode_name` + 3 个选项标签 |
| 8 | 测试 | 见 §7 |

⚠️ **不改**：`IHookCallback.aidl` / `IHookHost.aidl` / `HookCapabilityRegistry`（注册表不认识 mode）。

### 5.1 `HookCapabilityRuntime` 的执行器结构（协程）

```kotlin
private val executors: Map<String, CoroutineDispatcher> = mapOf(
    MODE_DEFAULT to Dispatchers.Default,
    MODE_IO      to Dispatchers.IO,
    MODE_UI      to uiDispatcher(),   // 自建 HandlerThread + Handler.asCoroutineDispatcher()
)
```

⚠️ **`UI` 档的两种接法**，二选一：

| 接法 | 说明 |
|---|---|
| `Handler.asCoroutineDispatcher()` | ✅ **推荐** —— `kotlinx-coroutines-android` 提供，统一成 `CoroutineDispatcher`，投递点只有一种写法 |
| 直接用 `Handler.post {}` | 要把投递点写成 `if (isUi) handler.post else scope.launch` —— 分叉，不推荐 |

⚠️ **`Handler.asCoroutineDispatcher()` 只接受 `CoroutineContext` + `Runnable`**，
而 `Handler.post` 的语义是**排队、不拒绝**（`Looper` 死掉时抛 `RejectedExecutionException`）。
⇒ 前端 `try/catch` 要接住它（与现在的 `RejectedExecutionException` 处理同一个位置）。

### 5.1.1 ⚠️ 投递点：**不能再 `pool.execute{}`**

现在的工作线程池是 `pool.execute { runOnWorker(...) }`。改成协程后：

```kotlin
// ⚠️ 不能 scope.launch + 同步等待结果（那会占住 binder 线程 = 违反 §3.4）
// 正确：launch 一个协程，让它【异步】跑完再 resolve（与现在 pool.execute 的语义一致）
val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName("VFlowHook-cap"))
scope.launch { runOnWorker(request, handler) }
```

⚠️ **三个必须处理的点**：

1. **`SupervisorJob`** —— 一个 handler 抛异常不该拖垮同一档的其他协程
   （虽然 `runOnWorker` 已顶层 `try/catch(Throwable)`，但要双保险）
2. **`scope` 的生命周期必须跟着 `stop()`** —— 现在的 `pool.shutdownNow()` 要换成
   `scope.cancel()`（⚠️ 且 `CoroutineName` 便于 logcat 认线程名）
3. **`SynchronousQueue` 的「不排队」语义** —— `Dispatchers.Default` 是**弹性**的
   （不排队，建线程），`Dispatchers.IO` 是**排队**（64 上限）。⇒ 两档的「满」行为**不同**，
   见 §1.4 末表

### 5.2 ⚠️ 由「无界」导致的语义损失（**与协程无关**）

见 §1.4：队列选无界 ⇒ `ThreadPoolExecutor` 永不拒绝 ⇒
`CapabilityErrorCode.HANDLER_ERROR` 里「池满也归这个码」那段 KDoc **不可达**。

⚠️ 但 **`Dispatchers.IO` 那 64 个并发上限仍会「排队」** —— 那与「拒绝」不同：
调用方**等**而不是**失败**（⇒ 最终由 App 侧超时兜）。

⇒ **不能删那段 KDoc 就完事**：要么改成「历史/防御」并注明何时会复活
（将来若改成有界队列），要么删。**见 §9-2。**

## 6. 未决项

| # | 项 | 状态 |
|---|---|---|
| 1 | ~~队列有界/无界~~ | ✅ **无界**（用户拍板）。⚠️ 残留事实：**不超时时真的无界** —— 一个 `Loop` 调 10000 次 `xposed_js` 且第一个脚本阻塞 ⇒ 队列涨到 10000。有 B 方案时过期的会被丢掉，「有超时」时长度 ≤ (提交速率 × 超时)；**「不超时」时无此上界**（用户已知悉并接受） |
| 2 | ~~三档容量~~ | ✅ **自动消解** —— 用协程 ⇒ 容量是库默认值，**没有可拍的数字**（见 §2） |
| 3 | **② 的计时起点要不要一并改成 `onInvoke`** | 本方案只加出队判定；不改起点的话 §3.3 那处「两侧口径不一致」仍在（只是被出队判定拦掉一部分） |
| 4 | ~~`UI` 档 `WindowManager` 风险~~ | ✅ **接受**（用户拍板）。⚠️ 落实要求：文案里**必须写明** |
| 5 | ~~是否给 `UI` 档做配套 API~~ | ✅ **不做**。理由改成「我们没打算做」，不是「照搬」（见 §2.1） |

---

## 6.1 本次改动引入的**待修旧债**（见 §9）


## 7. 验收

### 7.1 单测（纯 JVM，形态照 `HookCapabilityRuntimeTest`）

| 用例 | 断言 |
|---|---|
| 未知 mode | 降级为 `default`，**不报错** |
| mode 缺失 | = `default` |
| 出队判过期 | **handler 未被调用**（用一个记录调用的假 handler）+ 回 `timeout` |
| 出队未过期 | handler 正常执行，结果正常回 |
| `budget == null` | 出队判定是 no-op（**不**因为等待久而丢弃） |
| 三档分发 | `default`/`io` 落对应池、`ui` 落 Looper 线程 |
| `ui` 线程有 Looper | 断言 `Looper.myLooper() != null`（唯一能证明 `UI` 档**真的**给了 Looper 的方式） |
| codec round-trip | `threadMode` 为 null 时不写键；有值时可解出 |
| 旧端兼容 | 解码一个**没有该键**的请求 ⇒ `threadMode == null`（不抛） |

### 7.2 真机（**必做**，因为是 system_server 里的线程行为）

| 项 | 判据 |
|---|---|
| 三档真的落不同线程 | hook 日志打 `Thread.currentThread().name` |
| `ui` 档 `new Handler()` 可用 | 脚本里 `new java.lang.Handler()`（Java 互操作）不抛 |
| 出队判过期 | 并发压满后，第 3 个请求的日志里出现「排队已超预算」且**没有**脚本执行痕迹 |
| 池满（若选有界） | 与现在一致 |

---

## 8. 实施顺序

1. **纯函数层**（`normalizeThreadMode` / `threadModeOf`）+ 单测 —— 无 Android 依赖，先做
2. **codec + 数据类**（`threadMode` 字段）+ round-trip 测试
3. ⚠️ **协程探针**（§9-1）—— **必须先做**：`MainDispatcherLoader` 在 hook 层init 不了的话，
   整个方案要退回自建池（那是另一份设计）。**探针不过就别往下写。**
4. **出队判过期**（`HookCapabilityRuntime`，**不动池结构**）+ 单测 + 真机
   —— 它是三档的语义前提，且能独立验证
5. **换成协程执行器**（`pool` → 三个 dispatcher）+ 按 mode 分发 + 单测 + 真机
6. **模块参数 + 三语文案**
7. 真机全量验证（§7.2）

⚠️ 第 4、5 步**刻意分开**：真机出问题时能分清是「出队判定」还是「执行器替换」的锅。
⚠️ 第 3 步是**闸门** —— 它不过，第 5 步的方案整个作废。

---

## 9. 本方案连带发现的旧债（**都不阻断本方案，但应一并修**）

| # | 项 | 说明 |
|---|---|---|
| 1 | **`MainDispatcherLoader` 的真机验证** | 协程方案的**唯一新风险**。`Dispatchers` 类的静态块会跑 `MainDispatcherLoader.loadMainDispatcher()`：`ANDROID_DETECTED = try { Class.forName("android.os.Build") } catch { false }`，为真则走**硬编码类名** `Class.forName("kotlinx.coroutines.android.AndroidDispatcherFactory", true, MainDispatcherFactory::class.java.classLoader)`（⚠️ **不是** Rhino 那个 TCCL 单参 `ServiceLoader.load(Class)`）。⇒ 机制上**不同**，但仍**必须真机验**（Rhino 那次也是「按推导应该没事」，结果错了两轮）。**验法**：hook 层临时探针 `Log.e(TAG, "Default=${Dispatchers.Default} IO=${Dispatchers.IO}")` + 一次 `launch{}` 确认真的执行 |
| 2 | **`CapabilityErrorCode.HANDLER_ERROR` 的 KDoc 与实现不符** | 见 §1.4 —— 「池满也归这个码」那段在无界队列下**不可达**。⚠️ 而且 `InvokePolicy.poolExhaustedError()` 也**变成死代码**（它由 `RejectedExecutionException` 触发） |
| 3 | **`CapabilityInvoker` 的落点理由不成立** | 它的 KDoc 写「`xposed/capability/` 的白名单不含 `kotlinx.coroutines.` ⇒ 带 `suspend` 的文件必须放 `xposed/` 之外」。⚠️ **那个白名单只管 `xposed/wire/`**（`WireLayerPurityTest` 里 5 个方法各查什么目录，已逐一核对），管不到 `capability/`。⇒ 该理由作废；文件**可以**挪回去（**但不是必须** —— 挪动有成本，别为「理由错了」而改代码）。⚠️ **只改注释，别改位置** |

⚠️ 第 2 条有个**连带影响**要一并想清：无界队列下，`handler_error` 仍会由
「handler 自己抛异常」产生（那条路不受影响），所以**这个码不会消失**，
只是**「池满」那个成因消失了**。
