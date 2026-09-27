package com.chaomixian.vflow.core.xposed

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 官方框架信息的**状态源** —— 持有 `XposedService`，把框架状态推给 UI。
 *
 * 设计文档：`docs/fork/xposed-channel-p4-design.md` §3。
 *
 * **它由探针（`XposedFrameworkProbe`）改写而来**，而不是新建第二个文件 ——
 * 理由：`XposedServiceHelper.registerListener` **没有对应的注销方法**
 * （官方 API 只有 register），两个实现并存会**各收到一份回调**。
 *
 * ## 它提供四层信息里的三层
 *
 * | 层 | 本类提供 | 判据来源 |
 * |---|---|---|
 * | L1 框架 | ✅ `onServiceBind` / `onServiceDied` | 官方权威信号 |
 * | L2 作用域 | ✅ `service.scope` | 直读，用户可自我核对 |
 * | L3 挂载 | ✅ `service.runningTargets` | 实际注入结果 |
 * | **L0 通道** | ❌ 不提供 | 那是 [HookChannelController] 的事（App 与自己的 Service 的连接） |
 *
 * ⚠️ **L0 与 L1-L3 独立**，最终判定要把两者合起来看 —— 见 [XposedState.evaluate]。
 *
 * ## ⚠️ 单例 + 只注册一次
 *
 * `registerListener` 无注销 ⇒ 反复注册会累积监听器（收到多份回调）。
 * 故用进程级单例，`start()` 幂等。
 */
object XposedFrameworkMonitor {

    private const val TAG = "XposedMonitor"

    /** 内部观测快照。UI 通过 [state] 订阅。 */
    private val _state = MutableStateFlow(Observation())

    /**
     * 当前观测结果。
     *
     * ⚠️ 它**只是观测**，不是最终状态 —— 最终状态还要合上 L0（通道）：
     * 用 [XposedState.evaluate] 把本快照与 `HookChannelController.isConnected()` 组合。
     */
    val state: StateFlow<Observation> = _state.asStateFlow()

    @Volatile
    private var registered = false

    @Volatile
    private var everConnected = false

    /**
     * 框架侧观测快照。
     *
     * @param frameworkConnected 当前 `XposedService` 是否可用
     * @param scope L2：框架返回的作用域（**空列表 = 拿不到，不是「没勾」**）
     * @param runningTargetNames L3：实际注入的进程名
     */
    data class Observation(
        val frameworkConnected: Boolean = false,
        val scope: List<String> = emptyList(),
        val runningTargetNames: List<String> = emptyList(),
        /** 框架名（如 `LSPosed`）。⚠️ 断开时清空 —— 它是「当前连着」的信息。 */
        val frameworkName: String = "",
        /** 框架版本（如 `2.2.0`）。同上。 */
        val frameworkVersion: String = "",
    )

    /**
     * 启动监听。**幂等** —— 重复调用直接返回。
     *
     * ⚠️ 调用时机：尽早（`VFlowApplication.onCreate` 或首次需要状态时）。
     * 晚了会漏掉 `onServiceBind`（它是**推送**的，不重放）。
     */
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (registered) return
        registered = true

        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {

                override fun onServiceBind(service: XposedService) {
                    everConnected = true
                    // ⚠️ 这里**不再**写 SharedPreferences 的「曾经连上过」——
                    // 能力判据已改成**实时**（见 XposedCapability 的类注释：
                    // 「永久授权」会让权限页在模块被停用后仍显示已授权，
                    //  且与 vFlow 其他所有权限判据不一致）。
                    //
                    // `everConnected` 现在只是**本进程内存态**，唯一的用途是
                    // 在 `frameworkConnected == false` 时区分
                    // 「从没连过（UNAVAILABLE）」与「连过又断（DEGRADED）」—— 见 XposedState。

                    update(service)
                    DebugLogger.i(
                        TAG,
                        "框架已绑定：${service.frameworkName} ${service.frameworkVersion} " +
                            "scope=${XposedState.describeScope(safeScope(service))}"
                    )
                }

                override fun onServiceDied(service: XposedService) {
                    // ⚠️ 只清「当前连着」，**不清 `everConnected`** ——
                    // 那个语义是「曾经有过」，用于区分「未启用」与「已断开」
                    _state.value = Observation(frameworkConnected = false)
                    DebugLogger.w(TAG, "框架已断开（onServiceDied）")
                }
            })
        } catch (t: Throwable) {
            // ⚠️ 不静默：注册失败意味着状态永远显示「未启用」，
            // 而用户会以为是自己没配好
            DebugLogger.w(
                TAG,
                "注册框架监听失败：${t.javaClass.simpleName} ${t.message}\n" +
                    "⇒ 若为 NoClassDefFoundError，说明 libxposed service artifact 没打进 dex"
            )
        }
    }

    /**
     * 重新读取框架状态。
     *
     * ⚠️ 官方 API **没有**「主动查询」入口 —— 状态只能通过 `onServiceBind` 拿到。
     * 所以本方法的用途是：**在已经 bind 过之后**重新拉一次 `scope` / `runningTargets`
     * （它们会随用户改配置而变化，而 bind 事件不会重发）。
     *
     * ⚠️ 若从未 bind 过，本方法什么都没得拉 —— 这是 API 的限制，不是我们的 bug。
     */
    fun refresh() {
        val svc = currentService ?: return
        update(svc)
    }

    /** 已绑定的服务实例。`onServiceBind` 时设置、`onServiceDied` 时清空。 */
    @Volatile
    private var currentService: XposedService? = null

    private fun update(service: XposedService) {
        currentService = service
        _state.value = Observation(
            frameworkConnected = true,
            scope = safeScope(service),
            runningTargetNames = safeTargetNames(service),
            frameworkName = safeString { service.frameworkName },
            frameworkVersion = safeString { service.frameworkVersion },
        )
    }

    /**
     * 读一个字符串字段，失败返回空串。
     *
     * ⚠️ 每个字段单独 try/catch：`XposedService` 是对远端 AIDL 的包装，
     * 某个 getter 抛异常（协议不匹配等）不该让整个观测失败。
     */
    private inline fun safeString(read: () -> String?): String = try {
        read().orEmpty()
    } catch (t: Throwable) {
        DebugLogger.w(TAG, "读框架字段失败：${t.javaClass.simpleName}")
        ""
    }

    /** 读 scope —— 单独 try/catch：一个字段读不到不该让整个观测失败。 */
    private fun safeScope(service: XposedService): List<String> = try {
        service.scope ?: emptyList()
    } catch (t: Throwable) {
        DebugLogger.w(TAG, "读 scope 失败：${t.javaClass.simpleName}")
        emptyList()
    }

    /**
     * 读 `runningTargets` 的进程名。
     *
     * ⚠️ 用反射读 `processName` 而不是直接调方法 —— `HookedTarget` 的公开面
     * 在不同 libxposed 版本可能不同（探针里已按这个形态做过），
     * 反射能让「读不到」退化成「少一条信号」而不是整体失败。
     */
    private fun safeTargetNames(service: XposedService): List<String> = try {
        (service.runningTargets ?: emptyList()).mapNotNull { t ->
            (readMember(t, "getProcessName") ?: readMember(t, "processName")) as? String
        }
    } catch (t: Throwable) {
        DebugLogger.w(TAG, "读 runningTargets 失败：${t.javaClass.simpleName}")
        emptyList()
    }

    /** 先试方法、再试字段。读不到返回 null（不抛）。 */
    private fun readMember(target: Any, name: String): Any? = try {
        if (name.startsWith("get")) {
            target.javaClass.getMethod(name).invoke(target)
        } else {
            target.javaClass.getField(name).get(target)
        }
    } catch (_: Throwable) {
        try {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把**框架观测**与**通道状态**合成最终判定。
     *
     * ⭐ 这是本类的对外主入口 —— 调用方不必知道四层信息怎么拼。
     *
     * @param channelConnected L0：`HookChannelController.isConnected()`
     */
    fun evaluate(channelConnected: Boolean): XposedState.Result {
        val obs = _state.value
        return XposedState.evaluate(
            XposedState.Input(
                frameworkConnected = obs.frameworkConnected,
                everConnected = everConnected,
                scope = obs.scope,
                runningTargetNames = obs.runningTargetNames,
                channelConnected = channelConnected,
            )
        )
    }

    // ── 主动请求作用域（官方 API）─────────────────────────────────

    /** system_server 的虚拟包名。`scope.list` 用的就是它。 */
    private const val SCOPE_SYSTEM = "system"

    /**
     * 请求框架把 `system` 加入本模块的作用域。
     *
     * ## ⭐ 这是官方 API，比「跳去 LSPosed 页面让用户自己找」好得多
     *
     * `XposedService.requestScope(scopes, listener)` 的语义是**主动请求授权**，
     * 由框架弹出授权界面，并通过回调告知结果（`onScopeRequestApproved` /
     * `onScopeRequestFailed`）。
     *
     * ⚠️ **没有任何「打开 LSPosed 管理器」的 API** —— 官方给的是这条更直接的路径。
     *
     * @param onResult `true` = 用户批准；`false` = 失败或框架不可用
     *   （失败原因只打日志 —— 用户看不懂 `ServiceException` 的原文）
     */
    fun requestSystemScope(onResult: (Boolean) -> Unit) {
        val svc = currentService
        if (svc == null) {
            // ⚠️ 框架没连上时调不了 —— 这不是错误，是「还没准备好」。
            // 调用方应降级到文字引导
            DebugLogger.w(TAG, "请求作用域失败：框架未连接（降级为手动引导）")
            onResult(false)
            return
        }

        try {
            svc.requestScope(listOf(SCOPE_SYSTEM), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scopes: List<String>) {
                    DebugLogger.i(TAG, "✅ 作用域请求已批准：$scopes")
                    // ⚠️ 批准 ≠ 立刻生效：hook 需要重启目标进程才会挂上
                    // （`scope.list` 是配置，注入发生在进程启动时）。
                    // 所以这里**重新读一次**状态，并把「要重启」这件事交给 UI 说
                    refresh()
                    onResult(true)
                }

                override fun onScopeRequestFailed(message: String) {
                    DebugLogger.w(TAG, "❌ 作用域请求失败：$message")
                    onResult(false)
                }
            })
        } catch (t: Throwable) {
            // 反射/协议不匹配等 —— 不能吞，要降级
            DebugLogger.w(TAG, "requestScope 调用异常：${t.javaClass.simpleName} ${t.message}")
            onResult(false)
        }
    }

    /** 仅供测试重置（进程级单例的状态会串）。 */
    internal fun resetForTest() {
        registered = false
        everConnected = false
        currentService = null
        _state.value = Observation()
    }
}
