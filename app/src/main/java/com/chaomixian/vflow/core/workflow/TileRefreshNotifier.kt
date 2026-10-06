package com.chaomixian.vflow.core.workflow

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.model.TileKind

/**
 * 把「App 内改了工作流」这件事**推**给 SystemUI（`TileService.requestListeningState`）。
 *
 * ## 为什么必须有这一层
 *
 * 磁贴的 `onStartListening()` 只在**磁贴被绑定**或**下拉面板**时触发 ——
 * 「App 内改动」**不通知 SystemUI**。不加这条链路的表现是：
 * **进 App 改了工作流的图标 / 名字 / 启用状态，退出后磁贴还是旧的**。
 *
 * ⚠️ 这与 `ACTIVE_TILE` 元数据是一对：声明了它 ⇒ 标准模式下系统**不会**主动绑定，
 * **必须**靠 `requestListeningState` 才会更新（好处是不必每次下拉面板都绑一次）。
 * **只加元数据不补调用点**，表现是「改了图标磁贴没变」，**看起来像系统缓存**。
 *
 * ## 四处在调（缺一处就有一条路径不生效）
 *
 * | 场景 | 位置 |
 * |---|---|
 * | 保存工作流（改名 / 改图标 / 改触发器） | `WorkflowManager.saveWorkflow` 尾部 |
 * | 删除工作流 | `WorkflowManager.deleteWorkflow` 尾部 |
 * | 磁贴绑定 / 解绑 | `WorkflowListRoute` 的 `onSelect` |
 * | **备份导入覆盖** | `BackupRestoreScreen` 的 `ImportOutcome.Done` 分支 |
 *
 * ⚠️ 最后一处**不在 `saveWorkflow` 路径上**：REPLACE 走 `replaceAllWorkflows`、
 * MERGE 走 `saveAllWorkflows`，两者都绕过 `saveWorkflow` ⇒ 必须单独补。
 *
 * ## 两条实现纪律
 *
 * 1. **必须投到主线程** —— `requestListeningState` 是官方静态方法，要求在**有 Looper 的线程**
 *    上调用；而调用方之一（备份导入）在 `Dispatchers.IO` 里。⇒ 本类**自己**投递，
 *    调用方**不必也不能**替它切线程（「谁调用谁负责线程」会让四个调用点各写一遍）。
 * 2. **必须去抖** —— 它是**跨进程的系统调用**，40 个组件逐个发本身就不便宜；
 *    而 `saveWorkflow` 会被**循环批量调用**（`WorkflowListRoute` 的「解散文件夹」是
 *    `.forEach { saveWorkflow(...) }`，一个文件夹 N 个工作流 = N 次）
 *    ⇒ 不去抖就是 N × 40 次系统调用。
 *
 * ⚠️ **去抖只在 App 侧单进程内做** —— 本类与 `saveWorkflow` 同在 App 进程，
 * 而磁贴 service 虽然也和 App 同进程（manifest 未声明 `android:process`），
 * 但它们各自的调用时机不同，合并的收益只在这条写入路径上。
 */
object TileRefreshNotifier {

    private const val TAG = "TileRefreshNotifier"

    /** 去抖窗口：这段时间内的多次请求合并成一轮。 */
    private const val DEBOUNCE_MS = 500L

    /**
     * ⚠️ **必须 lazy** —— 纯 JVM 单测里 `Looper.getMainLooper()` 抛
     * `ExceptionInInitializerError`（"not mocked"），而它在**类初始化**里执行的话，
     * 会让整个 `TileRefreshNotifier` 无法被加载 ⇒ [dispatchAll] 这个纯函数
     * **一条用例都跑不起来**。
     */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val pending = Runnable { dispatchNow(latestContext) }

    @Volatile
    private var latestContext: Context? = null

    private var scheduled = false

    /** 组件存在性只核对一次（见 [verifyComponentsResolvableOnce]）。 */
    @Volatile
    private var componentsVerified = false

    /**
     * 请求刷新全部 40 个磁贴（带去抖）。可从任意线程调用。
     *
     * ⚠️ 传进来的 `context` 会被 `applicationContext` 化并持有到下一轮派发 ——
     * 这是刻意的（`Runnable` 需要一个活着的 context），且只活 500ms。
     */
    fun requestAll(context: Context) {
        val appContext = context.applicationContext
        latestContext = appContext
        if (scheduled) return
        scheduled = true
        mainHandler.postDelayed(pending, DEBOUNCE_MS)
    }

    /** 立刻派发（跳过去抖窗口）。仅用于需要即时生效的场景。 */
    fun requestAllNow(context: Context) {
        mainHandler.removeCallbacks(pending)
        scheduled = false
        latestContext = context.applicationContext
        mainHandler.post { dispatchNow(latestContext) }
    }

    private fun dispatchNow(context: Context?) {
        scheduled = false
        if (context == null) return

        verifyComponentsResolvableOnce(context)

        val outcome = dispatchAll { className ->
            TileService.requestListeningState(context, ComponentName(context, className))
        }

        if (outcome.failures.isEmpty()) {
            DebugLogger.d(TAG, "磁贴刷新已派发：${outcome.succeeded} 个")
        } else {
            // ⚠️ 逐条记，但**不中断整轮** —— 见 [dispatchAll] 的注释
            DebugLogger.d(
                TAG,
                "磁贴刷新：成功 ${outcome.succeeded}，失败 ${outcome.failures.size}：" +
                    outcome.failures.joinToString(", ")
            )
        }
    }

    /**
     * **一次性**核对 40 个组件名在 manifest 里真的存在，只打日志、不改变行为。
     *
     * ⚠️ 存在的理由：`TileSlot.serviceClassName` 是**字符串拼**出来的
     * （`"...WorkflowToggleTileService$slot"`），而 R8 只看得见 `R.drawable.` 那类
     * **符号**引用，**看不见字符串**。一旦某个 service 被 shrinker 判为无用（例如
     * 它只被 manifest 字符串引用、代码里没人直接 `new`），组件就会**静默消失** ——
     * 表现正是「磁贴存在、能点、但状态永远不刷新」。
     *
     * ⚠️ 本仓库在图标选择器上踩过**完全同形**的坑（`MaterialSymbolNames` 的 KDoc
     * 记着 `rounded_download_24` 曾「选择器里可选、release 包里不存在」）。
     * 那次靠 `res/raw/keep.xml` 兜住；这次没有任何白名单，
     * 所以**必须留一条可观测的判据**。
     */
    private fun verifyComponentsResolvableOnce(context: Context) {
        if (componentsVerified) return
        componentsVerified = true

        val missing = mutableListOf<String>()
        for (kind in TileKind.entries) {
            for (slot in 0 until TileSlot.tileCountOf(kind)) {
                val className = TileSlot.serviceClassName(kind, slot)
                val resolvable = runCatching {
                    context.packageManager.getServiceInfo(
                        ComponentName(context, className),
                        0
                    )
                }.getOrNull() != null
                if (!resolvable) missing += className
            }
        }

        if (missing.isEmpty()) {
            DebugLogger.d(TAG, "40 个磁贴组件均已注册（不含被 shrinker 剥掉的）")
        } else {
            // ⚠️ E 级：这一条指向**构建配置**问题，不是运行时问题 ——
            //    用户按提示去找 App 侧代码是找不到的。
            DebugLogger.e(
                TAG,
                "有 ${missing.size} 个磁贴组件在 manifest 里不存在 ⇒ 它们的状态**永远不会刷新**。" +
                    "这是 R8 / shrinkResources 把 service 剥掉了（组件名是字符串拼的、R8 看不见），" +
                    "需要 proguard 白名单。缺失：$missing"
            )
        }
    }

    /** 一轮派发的结果。 */
    internal data class DispatchOutcome(
        val succeeded: Int,
        val failures: List<String>,
    )

    /**
     * 逐个把 40 个磁贴的组件名交给 [request]。
     *
     * ⚠️⚠️ **单个失败绝不能中断整轮** —— 对「尚未添加到控制中心」的磁贴调用
     * `requestListeningState` 的行为官方未定义（可能抛）。若让异常冒出去，
     * 排在前面的失败会让**后面所有磁贴永远不刷新**，而用户只看到「有些磁贴是新的、
     * 有些是旧的」，几乎不可能归因到这里。
     *
     * ⚠️ **抽成内部函数是为了让上面那条契约可机器化验证** —— 在纯 JVM 单测里
     * `TileService.requestListeningState` 是 stub（不可调用），只能靠注入 [request]
     * 才能构造「某一个槽抛异常」这个场景。生产路径传入的 lambda 就是那一行真实调用。
     */
    internal fun dispatchAll(request: (String) -> Unit): DispatchOutcome {
        var succeeded = 0
        val failures = mutableListOf<String>()
        for (kind in TileKind.entries) {
            val count = TileSlot.tileCountOf(kind)
            for (slot in 0 until count) {
                val className = TileSlot.serviceClassName(kind, slot)
                try {
                    request(className)
                    succeeded++
                } catch (e: Exception) {
                    failures.add(className)
                }
            }
        }
        return DispatchOutcome(succeeded, failures)
    }

    /** 仅供测试重置去抖状态。 */
    internal fun resetForTest() {
        mainHandler.removeCallbacks(pending)
        scheduled = false
        latestContext = null
    }

    /** 仅供测试读去抖窗口。 */
    internal const val DEBOUNCE_MILLIS = DEBOUNCE_MS
}
