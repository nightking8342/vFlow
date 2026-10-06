package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.BroadcastTriggerData
import com.chaomixian.vflow.core.workflow.module.triggers.BroadcastTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.BroadcastTriggerSupport
import com.chaomixian.vflow.xposed.wire.ExtrasJsonCodec
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `vflow.trigger.broadcast` 触发器处理器 —— 动态注册 `BroadcastReceiver`。
 *
 * 设计文档：`docs/fork/broadcast-trigger-design.md`。
 *
 * ## ⚠️⚠️ 为什么必须继承 [BaseTriggerHandler]，**不能**用 `ListeningTriggerHandler`
 *
 * 与 `LogcatTriggerHandler` / `ActivityChangedTriggerHandler` 同一条教训：
 *
 * `ListeningTriggerHandler` 把 `start` / `stop` / `addTrigger` / `removeTrigger`
 * 四个方法全部 `final override` 了，子类只能实现 `startListening` / `stopListening`，
 * 而这两个**只在「空↔非空」的边界触发**。
 *
 * 后果很具体：**已有 1 个广播触发器时再加第 2 个，它的 receiver 永远不会被注册**
 * —— 表现是「新加的触发器不触发」，而用户会怀疑自己的 action 写错了。
 *
 * ## ⚠️⚠️ 每个触发器**各注册一个** receiver（与 ActivityChangedTriggerHandler 的
 * 「单 receiver + 全量条件下发」形态**刻意不同**）
 *
 * 原因不是「并集做不到」——`addDataScheme("")` 能把「无 data」那条腿补回来，
 * 并集方案**在功能上完全做得到**。真正的理由有两条：
 *
 * 1. **让 `IntentFilter` 成为唯一的判定点。** 并集方案必须在 `onReceive` 里
 *    手写一遍等价判定，而平台的匹配语义**不平凡**：三类条件的组合规则各不相同
 *    （action/scheme 是 OR、**category 是 AND**）、`scheme == null` 会被折成 `""`、
 *    未声明 scheme 时有 `content`/`file` 两个硬编码例外、data 还有
 *    type/ssp/authority/path 四层匹配。复刻一次就有漂移风险，而两者一旦不一致，
 *    「收到了却不触发」**没有任何日志**（filter 那层没有可观测点）。
 *    精确注册把这个风险面**从设计上去掉**，而不是靠纪律维持。
 * 2. **`actions` 为空时的「不注册」分支只有精确注册才做得出。**
 *    并集方案下 filter 永远非空（别的触发器的 action 在里面），
 *    没法「跳过这个触发器」——只能注册后在 `onReceive` 里判空并跳过，
 *    那是「明知无效还白注册一个 `EXPORTED` receiver 并白接管一堆广播」。
 *
 * 代价（如实记录）：N 个广播触发器 ⇒ N 个 receiver 注册在同一 Context
 * （N 通常是个位数，可忽略）；增删改要逐个重建（[registerFor] 幂等）。
 *
 * ## ⚠️ 一律 `RECEIVER_EXPORTED`
 *
 * 用户会配**第三方应用**发的 action，而 `NOT_EXPORTED` 只收同应用/系统定向投递
 * （既有实测：跨应用广播在 `NOT_EXPORTED` 下 3/3 收不到）。
 *
 * ⚠️ 与 `SimDataSwitchTriggerHandler` 的 `EXPORTED` **理由不同**，
 * **不要把这条推广到** DND / Power / Screen 那些既有触发器
 * （它们收的是系统广播，用 `NOT_EXPORTED` 是对的）。
 *
 * ## ⚠️ 不做发送方鉴权
 *
 * `Intent.getSentFromPackage()` / `getSentFromUid()` 是 **API 34+**
 * （本仓库 minSdk 29），且只有发送方 `BroadcastOptions.setShareIdentityEnabled(true)`
 * （**默认 false**）才给值 ⇒ 输入本身就不可得。用户 2026-10-07 已明确否掉鉴权层。
 * ⇒ 也**不暴露 `sender_package` 输出**（永远是 null，暴露即误导）。
 */
class BroadcastTriggerHandler : BaseTriggerHandler() {

    companion object {
        private const val TAG = "BroadcastTriggerHandler"

        /** 未配置冷却时的默认值，与模块的 `DEFAULT_COOLDOWN_MS` 一致。 */
        private const val FALLBACK_COOLDOWN_MS = 1000L

        /**
         * extras JSON 的字节预算（**8 KiB**）。
         *
         * ## ⚠️⚠️ **不要照抄 [com.chaomixian.vflow.xposed.wire.ActivityPayload.MAX_EXTRAS_JSON_BYTES]（48 KiB）**
         *
         * 那个常量是**为 Binder oneway 半缓冲（≈508 KiB）标定的** ——
         * activity 载荷要跨进程发给 App 侧。
         * 而**本载荷是同进程传递的**：`executeTrigger` → `Parcelable` → `ExecutionContext`，
         * **根本不经过 Binder**（唯一经 PendingIntent/AMS 的是种子输出那一笔，
         * 内容是几十字节的 `triggerId`）⇒ 按 48 KiB 设限等于**为一个不存在的上限**付代价：
         * 用户看不到后半段 extras，而收益为零。
         *
         * ## 那为什么仍然必须设上限（与 Binder 无关的两条）
         *
         * 1. extras 是**第三方应用可完全控制**的内容 —— 不设限就是让外部决定我们的内存占用；
         * 2. 它会进 `VString` → `executionLogs` → **`SharedPreferences`**，
         *    而这个 App **没有版本历史、没有撤销**（`FORK.md` 记过卸载即全灭）。
         *
         * ## 8 KiB 的依据
         *
         * 这类广播的 extras 通常就是几个毫秒级时间戳 + 少量 key/value（实测远小于 1 KiB）。
         * 超出部分走 `truncated = true`，用户能看出来 —— 不会被误当成「那个应用没传」。
         *
         * `BroadcastTriggerWiringTest` 有一条源码扫描断言把这里钉在 `8 * 1024`，
         * 并检查本段 KDoc 里写着「不经过 Binder」——
         * 防的是有人「统一口径」把它改回 48 KiB。
         */
        private const val MAX_EXTRAS_JSON_BYTES = 8 * 1024
    }

    /** 当前监听中的触发器。每次增删都重建，因此用写时复制容器。 */
    private val listeningTriggers = CopyOnWriteArrayList<TriggerSpec>()

    /** triggerId → 已注册的 receiver。**每个触发器一个**（见类注释）。 */
    private val receivers = ConcurrentHashMap<String, BroadcastReceiver>()

    /**
     * 冷却记录。**按触发器 id 独立计数**。
     *
     * ⚠️ 每个触发器的 `cooldown_ms` 可以不同，所以不能用全局窗口
     * （那样配了 `0` = 不冷却的触发器会被默认窗口挡住，等于配置没生效）。
     */
    private val lastTriggeredAt = ConcurrentHashMap<String, Long>()

    private var appContext: Context? = null

    // ── 生命周期 ────────────────────────────────────────────────

    override fun start(context: Context) {
        super.start(context)
        appContext = context.applicationContext
        DebugLogger.d(TAG, "广播触发器处理器已启动")
    }

    override fun stop(context: Context) {
        // ⚠️ 顺序：先把 receiver 全部注销（要读 appContext），再 super.stop() ——
        // 后者会 cancel triggerScope 并做基类清理
        receivers.keys.toList().forEach { unregisterReceiver(it) }
        listeningTriggers.clear()
        lastTriggeredAt.clear()
        appContext = null
        super.stop(context)
    }

    // ── 触发器增删 ★ 每次都要重建各自的 receiver ──────────────

    override fun addTrigger(context: Context, trigger: TriggerSpec) {
        appContext = context.applicationContext

        listeningTriggers.removeAll { it.triggerId == trigger.triggerId }
        listeningTriggers.add(trigger)

        // 冷却记录也要清 —— 改配置后旧的窗口不该继续挡着
        lastTriggeredAt.remove(trigger.triggerId)

        DebugLogger.d(TAG, "addTrigger: ${trigger.triggerId}，当前共 ${listeningTriggers.size} 个")
        registerFor(context.applicationContext, trigger)
    }

    override fun removeTrigger(context: Context, triggerId: String) {
        val removed = listeningTriggers.removeAll { it.triggerId == triggerId }
        lastTriggeredAt.remove(triggerId)

        // ⚠️ 无论 removed 与否都要注销 —— 否则表里可能残留一个「触发器已不在
        // listeningTriggers 里、receiver 却还注册着」的悬挂接收器，
        // 它会继续被系统调用（onReceive 里查不到 trigger 直接 return，不触发，
        // 但白占一份注册）。
        unregisterReceiver(triggerId)

        if (removed) DebugLogger.d(TAG, "removeTrigger: $triggerId，当前共 ${listeningTriggers.size} 个")
    }

    // ── 过滤规格（internal，供纯 JVM 单测）──────────────────────

    /**
     * 单个触发器的 filter 规格。
     *
     * ⚠️ 它只产出**纯数据规格**，`IntentFilter` 的构造被隔离在 [buildFilter] 里 ——
     * `IntentFilter` 是 Android 框架类，纯 JVM 单测里构造会抛 "not mocked"
     * （本项目既没开 `unitReturnDefaultValues` 也没上 Robolectric）。
     */
    internal data class FilterSpec(
        val actions: List<String>,
        val schemes: List<String>,
        val categories: List<String>,
    )

    /**
     * 推导某个触发器的 filter 规格。
     *
     * ⚠️⚠️ **`actions` 归一化后为空时返回 null（= 不注册 receiver）**，
     * 而不是「注册一个空 filter」。
     *
     * 两种做法的**命中行为其实是等价的** —— 平台层面，未声明 action 的 filter
     * 只匹配「没有 action 的 intent」（`matchAction` ⇒ `mActions.contains(action)`，
     * 空表恒 false；`IntentResolver` 的 action 索引里也没有它的 bucket）。
     *
     * 差别在于**代价**：本模块的 receiver 是 `EXPORTED` 的（任意应用可发），
     * 注册一个永远不会命中的 receiver 等于**白接管一堆广播**。
     * 而那正是「不注册」这一分支的价值 —— 它只在**精确注册**下才做得出
     * （并集方案下 filter 永远非空，没法跳过某个触发器）。
     *
     * ⚠️ 日志级别是 **WARN 而非 ERROR**：该分支在任何「被 `validate()` 拦到过」的
     * 路径上都不可达；能走到它的只有 JSON 导入 / 直接改 prefs —— 对那两种情形，
     * WARN 比 ERROR 更准确（不是我们的代码出错）。
     */
    internal fun filterSpecOf(trigger: TriggerSpec): FilterSpec? {
        val p = trigger.parameters
        val actions = BroadcastTriggerSupport.normalizeActions(
            BroadcastTriggerSupport.stringListOf(p[BroadcastTriggerModule.PARAM_ACTIONS])
        )
        if (actions.isEmpty()) {
            DebugLogger.w(
                TAG,
                "触发器 ${trigger.triggerId} 未配置有效 action，已跳过注册（不会触发）。" +
                    "⚠️ 广播不支持「留空 = 监听全部」，必须填写完整的 action 字符串。",
            )
            return null
        }
        return FilterSpec(
            actions = actions,
            schemes = BroadcastTriggerSupport.normalizeSchemes(
                BroadcastTriggerSupport.stringListOf(p[BroadcastTriggerModule.PARAM_DATA_SCHEMES])
            ),
            categories = BroadcastTriggerSupport.normalizeCategories(
                BroadcastTriggerSupport.stringListOf(p[BroadcastTriggerModule.PARAM_CATEGORIES])
            ),
        )
    }

    /**
     * 规格 → `IntentFilter`。
     *
     * ⚠️ **不得**出现 `addDataScheme("")` —— 空串 scheme 是**并集方案**用来补
     * 「无 data 广播」那条腿的手段；本模块走精确注册，一旦它出现在这里，
     * 说明有人改回了并集思路（那必须连同「`onReceive` 里要重判一遍」一起重新评估）。
     * 有源码扫描断言锁住。
     */
    internal fun buildFilter(spec: FilterSpec): IntentFilter = IntentFilter().apply {
        spec.actions.forEach { addAction(it) }
        spec.schemes.forEach { addDataScheme(it) }
        spec.categories.forEach { addCategory(it) }
    }

    // ── 注册 / 注销 ────────────────────────────────────────────

    private fun registerFor(context: Context, trigger: TriggerSpec) {
        // 幂等：先清掉可能存在的旧 receiver（改配置 / 重复 addTrigger）
        unregisterReceiver(trigger.triggerId)

        val spec = filterSpecOf(trigger) ?: return
        val appCtx = context.applicationContext

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) =
                dispatch(trigger.triggerId, intent)
        }

        try {
            // ⚠️ 必须 RECEIVER_EXPORTED：用户会配第三方应用发的 action；
            // NOT_EXPORTED 只收同应用/系统定向投递（既有实测：跨应用广播 3/3 收不到）。
            // 与 SimDataSwitchTriggerHandler 的 EXPORTED **理由不同**，
            // 不要把这条推广到 DND / Power / Screen 那些。
            ContextCompat.registerReceiver(
                appCtx,
                receiver,
                buildFilter(spec),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receivers[trigger.triggerId] = receiver
            DebugLogger.i(
                TAG,
                "已注册广播接收：「${trigger.workflowName}」" +
                    "${spec.actions.size} 个 action，${spec.schemes.size} 个 scheme，" +
                    "${spec.categories.size} 个 category",
            )
        } catch (e: Exception) {
            // ⚠️⚠️ **这是本模块唯一的「运行时诊断」落点**（`requiredPermissions` 刻意留空，
            // 见 BroadcastTriggerModule 的类注释）：缺权限 / 非法 action 都会在这里炸。
            //
            // 不能吞掉 —— 用户配了却不工作，唯一的线索就是这条日志
            // （用户可在设置页「查看日志」里自查）。
            DebugLogger.e(
                TAG,
                "注册广播接收失败（可能缺权限或 action 非法）：actions=${spec.actions}",
                e,
            )
        }
    }

    private fun unregisterReceiver(triggerId: String) {
        val r = receivers.remove(triggerId) ?: return
        try {
            appContext?.unregisterReceiver(r)
        } catch (e: Exception) {
            // 未注册时 unregister 会抛 IllegalArgumentException —— 反复启停时可能发生，
            // 不是错误，但也留个痕
            DebugLogger.w(TAG, "注销广播接收出错（可忽略）: ${e.message}")
        }
    }

    // ── 事件处理 ────────────────────────────────────────────────

    private fun dispatch(triggerId: String, intent: Intent) {
        val trigger = listeningTriggers.firstOrNull { it.triggerId == triggerId } ?: return
        val ctx = appContext ?: return

        // ⚠️ onReceive 跑在**主线程**（10s ANR 上限）⇒ 立刻切到 IO，
        // 编码与投递都在协程里做。与 SmsTriggerHandler 同一范式。
        triggerScope.launch { handleBroadcast(ctx, trigger, intent) }
    }

    private fun handleBroadcast(context: Context, trigger: TriggerSpec, intent: Intent) {
        val now = System.currentTimeMillis()

        // 冷却**先判** —— 被丢弃的命中不必白付一次 extras 编码
        if (!tryAcquireCooldown(trigger.triggerId, cooldownOf(trigger), now)) return

        val payload = buildPayload(intent)
        DebugLogger.i(
            TAG,
            "触发工作流 '${trigger.workflowName}'（action=${payload.action}）",
        )
        executeTrigger(context, trigger, payload)
    }

    /**
     * 把 `Intent` 打包成载荷。
     *
     * ⚠️ **每一步都包在 `try/catch(Throwable)` 里** —— 广播的 extras 由发送方
     * 完全控制，任何一环都可能因为「对方塞了我们不认识的类」而抛。
     * 一条这样的广播**不能崩掉 `TriggerService`**。
     */
    private fun buildPayload(intent: Intent): BroadcastTriggerData {
        val categories = try {
            intent.categories?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        var truncated = false
        val extrasJson = try {
            // ⚠️ `Bundle.get(key)` 对自定义 Parcelable 会触发类加载；
            // 发送方的类在本进程不可见时抛 `BadParcelableException`。
            val extras = intent.extras?.let { b ->
                b.keySet().associateWith { b.get(it) }
            }.orEmpty()
            val r = ExtrasJsonCodec.encode(extras, MAX_EXTRAS_JSON_BYTES)
            truncated = r.truncated
            r.json
        } catch (e: Throwable) {
            // ⚠️ 这里**不能**静默给一个看起来完整的 "{}" —— 那会让用户
            // 「extras 少了几个键」被当成「那个应用本来就没传」。
            // 用 truncated = true 如实告知「extras 不完整」。
            DebugLogger.w(TAG, "读取广播 extras 失败，本次载荷不含 extras: ${e.message}")
            truncated = true
            "{}"
        }

        return BroadcastTriggerData(
            action = try {
                intent.action.orEmpty()
            } catch (_: Throwable) {
                ""
            },
            dataUri = try {
                intent.dataString.orEmpty()
            } catch (_: Throwable) {
                ""
            },
            scheme = try {
                intent.data?.scheme.orEmpty()
            } catch (_: Throwable) {
                ""
            },
            mimeType = try {
                intent.type.orEmpty()
            } catch (_: Throwable) {
                ""
            },
            categories = categories,
            extrasJson = extrasJson,
            flags = try {
                intent.flags
            } catch (_: Throwable) {
                0
            },
            truncated = truncated,
        )
    }

    /**
     * 冷却判定 + 记账。**一次调用同时完成两件事**，不拆开 ——
     * 拆开会出现「判断完忘了记账」或「记了账但没触发」的错配。
     *
     * ⚠️ 冷却期内的命中**不记账**。若每次都记账，高频广播
     * 会让窗口无限顺延，触发器在持续收到广播时**永远不会再触发**。
     *
     * ⚠️ `nowMs` 是参数而非内部取 `System.currentTimeMillis()` ——
     * 这样纯 JVM 单测能精确控制时间。
     *
     * @return true = 允许触发（并已记账）
     */
    internal fun tryAcquireCooldown(triggerId: String, cooldownMs: Long, nowMs: Long): Boolean {
        if (cooldownMs <= 0L) return true

        val last = lastTriggeredAt[triggerId]
        if (last != null && nowMs - last < cooldownMs) return false

        lastTriggeredAt[triggerId] = nowMs
        return true
    }

    internal fun cooldownOf(trigger: TriggerSpec): Long {
        val raw = trigger.parameters[BroadcastTriggerModule.PARAM_COOLDOWN_MS]
        return when (raw) {
            is Number -> raw.toLong().coerceAtLeast(0L)
            is String -> raw.toLongOrNull()?.coerceAtLeast(0L) ?: FALLBACK_COOLDOWN_MS
            else -> FALLBACK_COOLDOWN_MS
        }
    }

    /**
     * 仅登记触发器、**不触碰 Context 与 receiver** —— 供纯 JVM 单测用。
     *
     * ⚠️ 单独开一个方法而不是让测试调 [addTrigger]：后者会
     * `ContextCompat.registerReceiver(...)`，需要 Android 环境，纯 JVM 里会炸。
     * **这个方法的唯一用途是测试。**
     */
    internal fun addTriggerForTest(trigger: TriggerSpec) {
        listeningTriggers.removeAll { it.triggerId == trigger.triggerId }
        listeningTriggers.add(trigger)
    }

    /** 当前登记数，供单测断言。 */
    internal fun listeningTriggerCount(): Int = listeningTriggers.size
}
