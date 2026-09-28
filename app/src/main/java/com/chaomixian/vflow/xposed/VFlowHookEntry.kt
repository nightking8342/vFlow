package com.chaomixian.vflow.xposed

import android.content.ComponentName
import android.util.Log
import com.chaomixian.vflow.xposed.sources.ActivityChangedSource
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Xposed 模块入口（vFlow 「第四条通道」的 hook 层）。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md`。
 * 探针实测结论：`scripts/probe/xposed-channel/P0-FINDINGS.md`。
 *
 * ## ⚠️ 本文件运行在 system_server 进程里
 *
 * 它被 LSPosed 注入 system_server（uid 1000）执行，**不是** vFlow 自己的进程。
 * 因此引用面上有硬约束：
 *
 * **允许**：`io.github.libxposed.*` / `java.*` / `kotlin` / `org.json` / `android.util.Log` /
 *         以及 `com.chaomixian.vflow.xposed.**` 内部类。
 *
 * **禁止**：`com.chaomixian.vflow.core.*` / `services.*` / Gson / `DebugLogger` /
 *         任何需要 App Context 初始化的类。
 *
 * 理由：同一个 APK 的 dex 里 App 类都在，但**只有被引用才会在 system_server 里被解析加载**。
 * 一旦 hook 层引用到 App 侧的重类，那些类的静态初始化就会在 system_server 里跑 ——
 * 那不只是「拖慢」，而是**把崩溃半径从「那个 App」扩大到整机**。
 * （日志刻意用 `android.util.Log` 而非本仓库的 `DebugLogger`，正是这条约束的直接后果。）
 *
 * ## ⚠️ 入口形态
 *
 * - 入口由 APK 根目录的 `META-INF/xposed/java_init.list` 按**类名字符串**指定，
 *   因此本类**必须被 R8 keep**（见 `proguard-rules.pro` 第 31 节）。被混淆的表现是
 *   **「模块装了但完全不生效」** —— 又一个静默失效形态。
 * - 必须用普通 `class`（能无参构造），**不要改成 Kotlin `object`** ——
 *   object 的 JVM 名带 `INSTANCE`，与框架按名实例化的语义不符。
 *
 * ## 为什么所有回调都包 `try/catch(Throwable)`
 *
 * `io.github.libxposed:api` 是 `compileOnly`（运行期由框架提供）。若用户装的框架版本
 * 与编译期 API 签名不符，会在**运行期**抛 `NoSuchMethodError` / `NoClassDefFoundError`
 * —— 这类错误**编译期发现不了**。而它在 system_server 里的后果不确定，故全部吞掉、
 * 降级为日志（设计文档 §4.4 的 `compileOnly` 运行期兜底）。
 *
 * ## 本阶段范围（P1a）
 *
 * **只验证「能被加载、能注入 system_server」**，不挂 hook、不通信、不做任何初始化。
 * 连接与采集在后续阶段接入（P1b / P2）。
 */
class VFlowHookEntry : XposedModule() {

    companion object {
        private const val TAG = "VFlowHook"

        /**
         * App 侧端点。
         *
         * ⚠️ 硬编码包名/类名（**不引用 App 侧常量**）—— 运行在 system_server 里，
         * 引用 App 常量会把那个类拖进 system_server（见类注释的引用面约束）。
         */
        private const val HOST_PACKAGE = "com.chaomixian.vflow"
        private const val HOST_SERVICE = "com.chaomixian.vflow.services.HookChannelService"
    }

    /**
     * 通道运行时。**实例字段而非静态字段** ——
     * 热更新是**新 classloader 加载新代码**，静态字段在新代际里是全新的（实测为 null）。
     *
     * ⚠️ 实例字段在新代际里同样是 null（整个对象都是新 classloader 里的新实例）——
     * 所以 [onHotReloaded] 必须**显式重建**通道，不能指望它还在。
     */
    private var runtime: HookRuntime? = null

    /** 传输层。持有它只为在热更新/停止时能断开连接。 */
    private var transport: BinderTransport? = null

    /**
     * 取 system_server 的 Context。
     *
     * 用反射拿 `ActivityThread.getSystemContext()` —— 探针实测可行。
     * ⚠️ 失败返回 null 而不是抛：拿不到时 [BinderTransport] 会继续轮询重试
     * （`onSystemServerStarting` 时系统服务尚未就绪，约 11 秒后才可用）。
     */
    private fun systemContext(): android.content.Context? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getDeclaredMethod("currentActivityThread").apply {
            isAccessible = true
        }.invoke(null)
        val getSystemContext = activityThread.getDeclaredMethod("getSystemContext").apply {
            isAccessible = true
        }
        getSystemContext.invoke(current) as? android.content.Context
    } catch (t: Throwable) {
        null
    }

    /**
     * 统一日志出口。
     *
     * ⚠️ **双通道**：`log()`（框架日志，LSPosed 只持久化 Error 级）+
     * `android.util.Log`（进 logcat，但启动期会被开机洪流冲掉）。
     * 两者都套 try/catch —— 早期回调里框架 API 可能尚未就绪。
     *
     * ⚠️ **只打日志，绝不写文件**。探针实测：加过「每次写文件到 `/sdcard/vFlow/`」的版本，
     * **模块完全不被加载**（装回不带写文件的版本即正常）。
     * 而且启动期的日志**读不到不能靠改代码绕过** ——
     * `adb logcat` 的 `main` 缓冲实测只覆盖约 50 秒，正确做法是**重启后立刻抓**，
     * 或用 LSPosed 管理器导出的 verbose 日志（持久化、不丢）。
     */
    private fun say(msg: String) {
        try {
            log(Log.ERROR, TAG, msg)
        } catch (_: Throwable) {
        }
        try {
            Log.e(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun warn(msg: String, t: Throwable? = null) {
        try {
            log(Log.ERROR, TAG, msg, t)
        } catch (_: Throwable) {
        }
        try {
            Log.e(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }

    /**
     * 模块加载完成。这是**最早的**回调，此时框架自己的 API 可能都没就绪。
     *
     * ⚠️ 官方要求不要在 `onModuleLoaded` 之前做初始化；本回调里也**只打日志**。
     * 真正的初始化放在 [onSystemServerStarting]（时机明确、能拿到 ClassLoader）。
     *
     * `ModuleLoadedParam` 提供 `processName` / `isSystemServer`（已对 102 的 aar 核实）。
     */
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        try {
            say("════ onModuleLoaded ════")
            say("  processName = ${param.processName}")
            say("  isSystemServer = ${param.isSystemServer}")
            say("  apiVersion = $apiVersion")
            say("  framework = $frameworkName / $frameworkVersion")
        } catch (t: Throwable) {
            warn("onModuleLoaded 失败（框架 API 不匹配？）", t)
        }
    }

    /**
     * 作用域进程（system_server）即将就绪。
     *
     * ⚠️ `SystemServerStartingParam` **只提供 `getClassLoader()`**
     * （已对 102 的 aar 核实 —— 它没有 `processName`，别想当然）。
     *
     * ⚠️ 实测硬约束：**此时系统服务尚未就绪** ——
     * `PackageManager` / `IActivityManager` 均为 null，**约 11 秒后**才可用。
     * 所以后续阶段要连 vFlow 时，必须自己带「等服务就绪」的等待，
     * **不要把这个回调返回当成「可以通信了」**。
     *
     * ⚠️ 另一条：hook 层还要等 `UserManager.isUserUnlocked()` 再通信 ——
     * 目标 Service 是 `directBootAware=false`，未解锁时组件在 package 解析阶段
     * 就被排除，表现与「包不可见」**一模一样**（`P0-FINDINGS.md` §5.1）。
     *
     * ⚠️ 不要把这里的 ClassLoader 缓存到静态字段 ——
     * 热更新是**新 classloader 加载新代码**，静态字段在新代际里是全新的（实测为 null）。
     * 本阶段不用 ClassLoader，故不持有。
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        try {
            say("════ onSystemServerStarting ════")
            say("  classLoader = ${param.classLoader}")
        } catch (t: Throwable) {
            warn("onSystemServerStarting 日志失败", t)
        }

        // ── P1b：建立与 App 的双向通道 ──
        // ⚠️ 全部包 try/catch：本回调跑在 system_server 里，
        // 未捕获异常的危险性是「整机」（§5.1）
        try {
            startChannel(param.classLoader)
        } catch (t: Throwable) {
            warn("建立通道失败（不影响系统运行）", t)
        }
    }

    /**
     * 建立 hook 层运行时 + binder 通道。
     *
     * ⚠️ **幂等**：热更新、或框架重放回调时可能被调多次。
     *
     * ⚠️ 注意这里**不等**「系统服务就绪」也不等「用户解锁」——
     * 那两个等待在 [BinderTransport] 的后台线程里做。
     * 本回调必须**立刻返回**，阻塞会拖住 system_server 启动。
     */
    private fun startChannel(classLoader: ClassLoader?) {
        if (runtime != null) {
            say("  通道已存在，跳过重复初始化")
            return
        }

        val transport = BinderTransport(
            component = ComponentName(HOST_PACKAGE, HOST_SERVICE),
            contextProvider = ::systemContext,
        )
        val rt = HookRuntime(transport, xposed = this)
        transport.onConditions { conditionsJson, token -> rt.onConditions(conditionsJson, token) }

        // ⚠️ 每次**连接（重新）建立**都要重挂 hook 点。
        //
        // 为什么需要：重连的场景通常是「hook 层刚换代」或「App 被强杀后重建」——
        // 那时 hook 层的内存里 hook 点**可能没挂上**（热更新不会自动重放回调）。
        // 只重连通信而不重挂，会得到「连上了但什么都不触发」——通道看着是活的。
        //
        // ⚠️ ClassLoader 现取（`ActivityThread` 的 classLoader 是模块自己的、
        // 加载不到系统类 —— 必须用 system context 的，见 systemServerClassLoader 的注释）。
        // ⚠️ 带 key 注册（修缺陷 12）—— 这里原来是单槽位，第二个注册方会
        // 静默覆盖本回调。key 用类名，将来加第二件事时不会互相挤掉。
        transport.onConnected("VFlowHookEntry.remount") {
            try {
                val cl = systemServerClassLoader()
                if (cl != null) {
                    // ⚠️ 用 remountSources 而非 mountSources —— 后者会被
                    // HookSource 的幂等守卫挡住（旧句柄非 null 但已失效），
                    // 导致「连上了却什么都不触发」。详见 remountSources 的注释
                    rt.remountSources(cl)
                    say("  重连后已重挂 hook 点")
                } else {
                    say("  ⚠️ 重连后拿不到 ClassLoader，hook 点未重挂")
                }
                // ⚠️ 条件**不用**在这里重下发 —— 那是 App 侧的职责：
                // `HookChannelController.onCallbackRegistered` → `onConnected`
                // → `ActivityChangedTriggerHandler.syncToChannel()` 已经做了。
                // （我一度在这里加过 `requestConditionResend()`，但它在 token 为空时发不出去，
                //  而且 App 侧本来就做 —— 已删，理由见 HookRuntime 的对应注释。）
            } catch (t: Throwable) {
                warn("重连后重挂失败", t)
            }
        }

        // ── 注册适配器（加新触发器时**只在这里追加一行**，§3.4.3）──
        rt.register(ActivityChangedSource())

        runtime = rt
        this.transport = transport
        rt.start()
        transport.start()   // 立即返回，连接在后台线程里做
        say("  通道已启动（后台等待「服务就绪 + 用户解锁」后 bind）")

        // ⚠️ 挂 hook 需要目标进程的 ClassLoader。热更新路径下
        // `HotReloadedParam` **没有 getClassLoader()**（实测），故传 null 时跳过 ——
        // 那时旧 hook 由 `replaceHook` 接管，不走这条路径
        if (classLoader != null) {
            rt.mountSources(classLoader)
        } else {
            say("  ⚠️ 无 ClassLoader，跳过挂载（热更新路径应由 replaceHook 处理）")
        }
    }

    /**
     * 热更新即将发生（运行在**旧**代码上）。返回 `true` 放行。
     *
     * ⚠️ 后续阶段若起了采集线程，**必须在这里停掉**并释放资源（官方要求）。
     * 本阶段无线程，故直接放行。
     *
     * ⚠️ 官方还明确：**热更新不应用于传配置**
     * （「It should not be used to propagate configuration changes」）——
     * 它只用于「模块代码换代」。配置下行走通信通道。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        try {
            say("════ onHotReloading（旧代码）════")
            // ⚠️ 官方要求：**必须在这里停掉自己的线程/回调**，否则旧代际的连接线程
            //   （还在轮询「等解锁 / 等服务就绪」）会与新代际的那个并存 ——
            //   两个线程抢同一个 Service，表现为「连接反复断开重连」。
            //
            // ⚠️⚠️ 但**不要在这里 unmount hook** —— hook 的交接由新代际的
            //   `onHotReloaded` → `replaceHook()` 完成（P0 实测的正解）。
            //   在这里 unhook，`getOldHookHandles()` 就会是空的，新代际无从接手。
            stopChannelOnly()
        } catch (t: Throwable) {
            warn("onHotReloading 清理失败", t)
        }
        return true
    }

    /**
     * 只停通道（线程 + 连接），**不动 hook 点** —— 热更新路径专用。
     *
     * ⚠️ 与 [runtime.stop] 不同：那个会 unmount 所有 hook。
     * 在 `onHotReloading` 里绝不能 unmount —— 否则新代际的
     * `getOldHookHandles()` 是空的，`replaceHook()` 无从接手。
     */
    private fun stopChannelOnly() {
        transport?.stop()
        transport = null
        // 只停发送线程，不 unmount
        runtime?.stopDrainOnly()
        runtime = null
    }

    /** 彻底停掉（含 unmount hook）。进程退出等场景用。 */
    private fun stopChannel() {
        transport?.stop()
        transport = null
        runtime?.stop()
        runtime = null
    }

    /**
     * 新代码已接管（运行在**新**代码上）。
     *
     * ⚠️ 官方明确：热更新**不会自动重放**回调 —— 新代码必须自己重新挂 hook。
     * 本阶段不挂 hook，故只记录旧句柄数以便观测（P4 再实现 `replaceHook()`）。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        try {
            val old: List<XposedInterface.HookHandle>? = param.oldHookHandles
            say("════ onHotReloaded（新代码）════ 旧 hook 句柄数 = ${old?.size ?: 0}")
        } catch (t: Throwable) {
            warn("onHotReloaded 读旧句柄失败", t)
        }

        // ⚠️⚠️ 这段是**实测踩出来的**（不是推断）。
        //
        // 官方明文：「Hot reload **does not automatically replay** this callback
        // or package lifecycle callbacks」—— 也就是 `onSystemServerStarting`
        // **不会**在新代际里被重放。
        //
        // 而热更新是「新 classloader 加载新代码」⇒ 新代际的 `runtime` / `transport`
        // 字段**全是 null**。所以必须在这里**自己把通道重建起来**，
        // 否则热更新之后通道就永久消失了 —— 表现是「重装 App 后触发器再也不工作」，
        // 而日志里只有一行 onHotReloaded，看起来一切正常。
        //
        // 这正是本仓库反复记录的静默失效形态。
        try {
            say("  热更新后重建通道（官方不会重放 onSystemServerStarting）")
            // ⚠️ 传 null ClassLoader：`HotReloadedParam` **没有** getClassLoader()（实测）。
            // hook 点本身不在这里重挂 —— 见下面的 replaceHook 路径
            startChannel(classLoader = null)

            // ⚠️⚠️ **必须接手旧 hook**：`onHotReloaded` 的默认实现会 unhook 全部旧 hook，
            // 而热更新不重放任何回调。不接手的话，重装 APK 后通道活着、**事件却永远不再产生**
            // —— 本仓库反复记录的那类静默失效。
            //
            // 正解是 P0 实测确证过的 `replaceHook()`：**不需要 ClassLoader**，
            // 回调需要的类从 `chain.getExecutable().getDeclaringClass()` 推。
            val old = try {
                param.oldHookHandles
            } catch (t: Throwable) {
                warn("读 oldHookHandles 失败", t)
                null
            }

            val remounted = if (old != null && old.isNotEmpty()) {
                val n = runtime?.remountAfterHotReload(old) ?: 0
                say("  热更新接手：成功 ${n}/${old.size} 个 hook 句柄")
                n > 0
            } else {
                // ⚠️ 旧句柄为 0 的**来源有两种**，必须区分：
                //  ① 上一代**没挂过** hook（例如从「还没有 source」的旧版本升上来）
                //  ② 上一代挂了但已被 unhook
                // 两种情况下新代际都得自己挂。而 `HotReloadedParam` **没有
                // getClassLoader()**（实测），所以要另找 ClassLoader —— 见下。
                say("  无旧句柄可接手（上一代未挂 hook？）")
                false
            }

            if (!remounted) {
                // ⚠️ 兜底：**自己重新挂**。这是实测驱动的改进 ——
                // 不加它的话，每次「从无 hook 状态」升级都要求用户**重启设备**，
                // 而热更新的意义恰恰是「不用重启」。
                //
                // ClassLoader 来源：`ActivityThread` 的 classLoader 就是
                // system_server 的 PathClassLoader（探针取 system context 用的
                // 也是这条路径）。**不缓存到静态字段** —— 跨代际会失效。
                val cl = systemServerClassLoader()
                if (cl != null) {
                    runtime?.mountSources(cl)
                    say("  已用 ActivityThread 的 ClassLoader 自行挂载 hook")
                } else {
                    say("  ⚠️ 拿不到 ClassLoader，也无可接手句柄 ⇒ 事件不会产生，需重启设备")
                }
            }
        } catch (t: Throwable) {
            warn("热更新后重建通道失败", t)
        }
    }

    /**
     * 取 system_server 的 ClassLoader。
     *
     * ⚠️⚠️ **踩过的坑（第一版写错了）**：
     * `Class.forName("android.app.ActivityThread").classLoader` 拿到的是
     * **模块自己的** classloader —— 它加载不到 `com.android.server.wm.ActivityRecord`
     * （那是系统 jar 里的类）。表现是 `loadClass` 全失败、
     * 日志打出「找不到 hook 点（系统版本不兼容？）」，**误导性极强**。
     *
     * **正解：用 system context 的 classLoader** —— 它就是 system_server 的
     * `PathClassLoader`（探针取 system context 走的也是这条路径）。
     *
     * ⚠️ **不要缓存**（静态字段跨代际失效）。每次现取，代价只是一次类查找。
     */
    private fun systemServerClassLoader(): ClassLoader? {
        // 首选：system context 的 classLoader
        val fromContext = try {
            systemContext()?.classLoader
        } catch (_: Throwable) {
            null
        }
        if (fromContext != null) return fromContext

        // 退回：ActivityThread 实例的 classLoader（与上面同源，但少一层 Context）
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }.invoke(null)
            current?.javaClass?.classLoader
        } catch (t: Throwable) {
            warn("取 system_server ClassLoader 失败", t)
            null
        }
    }
}
