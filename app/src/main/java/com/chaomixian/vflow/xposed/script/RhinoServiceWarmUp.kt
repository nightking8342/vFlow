package com.chaomixian.vflow.xposed.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.RegExpProxy
import java.util.ServiceLoader

/**
 * Rhino 的 **ServiceLoader 服务发现预热**。
 *
 * ## ⚠️⚠️ 它修的是什么（真机实测缺陷，2026-10-02）
 *
 * 在 `vflow.xposed.js` 里写**任何**正则（字面量 `/abc/` 或 `new RegExp(...)`）都会失败：
 *
 * ```
 * 脚本错误（第 3 行第 0 列）：正则表达式不可用。
 * ```
 *
 * 同时 `typeof RegExp === "undefined"`、`String.prototype.match` 还在但**造不出正则**。
 * 而同一 App 的 `vflow.system.js` 一切正常。
 *
 * ## 根因：Rhino 的正则引擎是**可插拔**的，靠 ServiceLoader 发现
 *
 * `org.mozilla.javascript.regexp.RegExpLoaderImpl` 通过这个服务文件注册：
 *
 * ```
 * META-INF/services/org.mozilla.javascript.RegExpLoader
 *   → org.mozilla.javascript.regexp.RegExpLoaderImpl
 * ```
 *
 * `Context` 的**静态初始化块**里有：
 *
 * ```java
 * regExpLoader = ScriptRuntime.loadOneServiceImplementation(RegExpLoader.class);
 * //   → java.util.ServiceLoader.load(RegExpLoader.class)   ← 单参版，走 TCCL
 * ```
 *
 * 而 `ServiceLoader.load(Class)` 用的是**线程上下文 ClassLoader（TCCL）**，
 * **不是**定义 Rhino 的那个 ClassLoader。
 *
 * | 进程 | TCCL | 能否找到服务文件 |
 * |---|---|---|
 * | App（`vflow.system.js`） | 应用 ClassLoader（能看到 APK 内 `META-INF/services`） | ✅ |
 * | **hook 层**（本模块） | LSPosed 给的模块 ClassLoader | ❌ **false** |
 *
 * 找不到 ⇒ `Context.regExpProxy` 恒为 null ⇒ 任何正则用法抛 `msg.no.regexp`。
 * （「正则表达式不可用。」是 Rhino 自带的 `Messages_zh_CN.properties` 文案，
 * **不是**谁主动拦截；全仓 grep `setRegExpProxy` / `ClassShutter` 均为零命中。）
 *
 * ⚠️ **这个查找只在类初始化时做一次，失败后没有重试。**
 * 且 Rhino 1.9.0 **没有公开 API** 能补救 —— `Context` / `ContextFactory` 的
 * 全部 public 方法里没有 `setRegExpProxy`，也没有重跑查找的入口。
 * ⇒ **只能让「第一次查找」成功。**
 *
 * ## 修法：把 TCCL 临时换成模块 ClassLoader，触发类初始化，再还原
 *
 * 既然是「一次性查找」，就只需要在触发 `<clinit>` 的那一瞬间让 TCCL 是对的：
 *
 * ```kotlin
 * val original = Thread.currentThread().contextClassLoader
 * try {
 *     Thread.currentThread().contextClassLoader = moduleClassLoader   // 只在窗口内
 *     Class.forName("org.mozilla.javascript.Context", true, moduleClassLoader)  // ← 触发查找
 * } finally {
 *     Thread.currentThread().contextClassLoader = original             // ★ 立刻还原
 * }
 * ```
 *
 * ### ⭐ 实测：还原之后正则**仍然可用**
 *
 * 这是本方案成立的关键（不能用推理代替）。用一个「parent 看不到 Rhino」的
 * 隔离 ClassLoader 模拟 hook 层，实测结果：
 *
 * ```
 * 外层 TCCL = AppClassLoader
 * warm-up 完成，TCCL 已还原为 AppClassLoader     ← 已还原
 * [还原后] 正则字面量  -> OK => object            ← 仍然能用
 * [还原后] exec 捕获组 -> OK => 12
 * [还原后] new RegExp  -> OK => true
 * ```
 *
 * ⇒ **不需要永久改 TCCL**，改动窗口收窄到一个线程、一次类加载、无挂起点。
 *
 * ## ⚠️ 副作用评估（为什么它足够安全）
 *
 * TCCL 是**线程级**的全局状态，理论上换掉它会影响同线程的
 * `ServiceLoader` / `ClassLoader.getResource` 等。本方案把它压到最小：
 *
 * | 维度 | 本方案 | 若永久改 |
 * |---|---|---|
 * | 生效时间 | 一次 `Class.forName` 的时长（无挂起点） | 直到显式还原 |
 * | 生效范围 | 当前线程 | 当前线程（且会被线程复用带走） |
 * | 还原保证 | `try/finally` | 靠自觉 |
 * | 之后 | TCCL 即原值，系统的其他部分**看不见任何变化** | 遗留 |
 *
 * **残留的窄边界**（如实记录，不夸大也不隐瞒）：若某个系统组件**恰好**在
 * 这个纳秒级窗口内、在同线程调 ServiceLoader，会看到模块 ClassLoader。
 * 窗口内不调用任何回调/挂起点，实践中不可达。
 *
 * ## ⚠️⚠️ 触发点：**两个**，缺一不可（真机实测得出）
 *
 * 本来是只挂在 `onSystemServerStarting`。**真机跑下来发现不够** ——
 * 装包后跑正则用例仍报「正则表达式不可用」，而设备 `uptime` 是 `up 3 days`
 * （从未重启）。原因：
 *
 * - 那个回调**只在 system_server 启动时跑一次**，且**官方不会在热更新里重放它**
 *   （本仓库早已记录过这条，我却没应用到自己身上）；
 * - 而 `Context` 是**框架的类**、由 LSPosed 持有 ⇒ **热更新不会重置它的静态字段**
 *   ⇒ 若不重新预热，`regExpLoader` 永远是当初那次失败留下的 null。
 *
 * ⇒ 现在挂在**两处**（见 `VFlowHookEntry`）：
 *
 * | 时机 | ClassLoader 来源 | 说明 |
 * |---|---|---|
 * | `onSystemServerStarting` | `param.classLoader` | 开机首次 |
 * | `onHotReloaded` | `javaClass.classLoader` | **重装 APK 后**（实测补上才有用） |
 *
 * ⚠️ `onHotReloaded` 里只能用 `javaClass.classLoader` —— `HotReloadedParam`
 * **没有** `getClassLoader()`（实测），而 `systemServerClassLoader()`
 * 拿到的是 system_server 的 PathClassLoader、未必能解析到模块的 `META-INF/services`。
 *
 * ✅ **真机验证（2026-10-02，小米 MIX Fold 3 / Android 17）**：
 * `REGEXP_PROBE lit=true ctor=true grp=12 rep=a#b# typeof=function` ——
 * 字面量、构造函数、捕获组、`replace` + `g` 标志全部恢复。
 *
 * ## ⚠️ 为什么热更新那一次能生效（`<clinit>` 不是早就跑过了吗）
 *
 * 好问题 —— 按「静态初始化只跑一次」推，热更新后再调应该是个 no-op。
 * **但实测它确实修好了**（上面那条 PROBE 就是装包之后跑的）。
 *
 * 可能的原因是 LSPosed 的 hot reload 用的是**新的 classloader**，
 * 而 hook 层可达的 `org.mozilla.javascript.Context` 也随之是新的那一份
 * （静态状态全新、`regExpLoader` 为 null）⇒ 预热在**新代际**上确实有效。
 *
 * ⚠️ **这一点我只验到「行为上成立」，没有验到「机制上为何成立」** ——
 * 如实记录，不把推断写成结论。**这不影响修复的有效性**（真机已证），
 * 但若将来有人要动这里，先按上面那条 PROBE 复验，别只看推导。
 *
 * ## ⚠️ 为什么要用反射而不是直接 import Rhino
 *
 * 本文件在 `xposed/` 包下 ⇒ 受 `WireLayerPurityTest` 管辖。
 * 它只 import `org.mozilla.javascript.*`（两张白名单表都不涉及，同类注释
 * 在 [ScriptExecutor] 与 `ScriptRequest` 里已有先例），
 * **不引入任何 `android.*`** ⇒ 不需要改 `ANDROID_ALLOWLIST`。
 */
internal object RhinoServiceWarmUp {

    private const val TAG = "RhinoServiceWarmUp"

    /** 用 `Class.forName` 而不是 `Context.enter()` —— 前者能**精确控制**用哪个 ClassLoader 触发 `&lt;clinit&gt;`。 */
    private const val CONTEXT_CLASS = "org.mozilla.javascript.Context"

    /**
     * 当前线程做一次 warm-up。**幂等**：`Context` 已初始化时是 no-op
     *（`Class.forName` 发现类已加载便直接返回，不再执行 `<clinit>`）。
     *
     * ⚠️ **必须在第一次执行脚本之前调用**，且**越早越好**
     *（一旦有别的代码用错误的 TCCL 触发过 `Context` 的类初始化，就再也救不回来）。
     *
     * @param moduleClassLoader 能加载到 Rhino 的 ClassLoader（hook 层用
     *   `onSystemServerStarting(param.classLoader)` 的那个）。
     * @return `true` = 本次做了预热；`false` = 无需预热或预热失败（都**不抛**）
     *
     * ⚠️ **绝不抛异常** —— 它跑在 system_server 里，异常逃逸的后果是「整机」（§5.1）。
     * 失败时降级为「正则不可用」（与修复前一致），不影响其他任何功能。
     */
    fun warmUp(moduleClassLoader: ClassLoader?): Boolean {
        if (moduleClassLoader == null) return false

        val thread = Thread.currentThread()
        val original = try {
            thread.contextClassLoader
        } catch (t: Throwable) {
            // 极少数安全策略下 getContextClassLoader 可能抛 —— 那就放弃（不冒风险）
            return false
        }

        // ⚠️ 不预检「Context 是否已初始化」。
        //
        // 曾经想用 `Class.forName(name, /*initialize=*/false, cl)` 来预检 —— **那行不通**：
        // 该重载只保证「不初始化」，**无法反查是否已经初始化过**，
        // 想拿到那个信息只能调 `Class.forName(name, true, …)`（那就已经触发初始化了），
        // 或者读内部状态（不可靠）。
        //
        // 好在**不需要**预检：`Class.forName(name, true, cl)` 对**已经初始化**的类
        // 是廉价的 no-op —— JVM 只在首次主动使用时跑 `<clinit>`，之后直接返回 Class 对象。
        // ⇒ 直接「设 TCCL → forName → 还原」，重复调用天然幂等，且不会二次执行 `<clinit>`。
        //
        // ⚠️ 唯一要避免的是「本来就不需要预热还去扰动 TCCL」——
        // 下面的 `original === moduleClassLoader` 分支正是为此：TCCL 已经对了就别动它。
        if (original === moduleClassLoader) {
            return try {
                Class.forName(CONTEXT_CLASS, true, moduleClassLoader)
                true
            } catch (t: Throwable) {
                false
            }
        }

        return try {
            thread.contextClassLoader = moduleClassLoader
            // ★ 这一行触发 Rhino 的 Context 静态初始化 ⇒ ServiceLoader 用**当前 TCCL** 查找。
            //   若该类已初始化过，这里是 no-op（不会二次执行 `<clinit>`），但**无害**。
            Class.forName(CONTEXT_CLASS, true, moduleClassLoader)
            true
        } catch (t: Throwable) {
            // 失败不抛：预热只是「让正则能用」，不该因此影响任何别的东西
            false
        } finally {
            // ★★ 无论成败都还原 —— TCCL 绝不能留在被改动的状态
            try {
                thread.contextClassLoader = original
            } catch (_: Throwable) {
                // 还原失败也只能吞掉（能做的已经做完了）
            }
        }
    }

    /**
     * **仅供测试/诊断**：`Context` 的 RegExpProxy 当前是否可用。
     *
     * ⚠️ 这个函数**会**触发 `Context` 初始化（用当前 TCCL）——
     * 生产代码**不要**在 warm-up 之前调它，否则等于用错误的 TCCL 把状态固化。
     * 它存在的意义是让测试能断言「预热前后 proxy 的有无」。
     */
    internal fun regExpProxyAvailableForTest(): Boolean = try {
        val m: java.lang.reflect.Method = Context::class.java.getDeclaredMethod("getRegExpProxy")
        m.isAccessible = true
        m.invoke(null) != null
    } catch (t: Throwable) {
        false
    }

    /** 编译期锁：确保上面那个反射方法名在 Rhino 里确实存在（改版/改名时立刻暴露）。 */
    @Suppress("unused")
    private val proxyTypeAnchor: Class<RegExpProxy> = RegExpProxy::class.java

    /** 编译期锁：ServiceLoader 这个类会被用到（避免「写了注释但没引用」）。 */
    @Suppress("unused")
    private val serviceLoaderAnchor: Class<ServiceLoader<*>> = ServiceLoader::class.java
}
