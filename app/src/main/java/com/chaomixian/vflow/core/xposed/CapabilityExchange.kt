package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.xposed.capability.CapabilityPresence
import com.chaomixian.vflow.xposed.capability.presenceAfterDisconnect
import com.chaomixian.vflow.xposed.capability.presenceAfterExchange

/**
 * ③ **连接期能力交换**的判定逻辑（纯函数 + 一个可替身的接口）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.3；判据来源 `IHookCallback.aidl`。
 *
 * ## ⚠️ 为什么把「调 capabilities()」抽成 [ManifestProbe] 而不是直接写 [HookChannelController]
 *
 * 判据本身是**三段顺序 + 异常语义**（「方法不存在」= 抛，不是返回空串），
 * 而这一层**必须可纯 JVM 单测** —— 否则如下三种情形就分不开了：
 *
 * ```
 * ① 清单是空数组        ⇒ READY   （方法在，只是没注册任何能力）
 * ② 方法不存在（抛）    ⇒ ABSENT  （hook 层代码太旧）
 * ③ 连接断了（抛）      ⇒ UNKNOWN （拿不到答案，不是版本旧）
 * ```
 *
 * 而 ② 与 ③ 的**用户处置完全不同**（§6.4：② 指向 App 侧「升级/重启」，
 * ③ 指向 LSPosed「等重连」）—— 混了就会让用户白折腾错误的方向。
 *
 * ⇒ 用 `fun interface` 把「拿清单」这一动作替换掉：单测可以造「抛异常的探针」
 * 与「返回空清单的探针」，而调用点（[CapabilityPresenceHolder]）传真实实现。
 *
 * ⚠️ **本文件刻意不 import `HookChannelController`** —— 它只依赖
 * `xposed/capability/` 的三个纯类型。连接相关的判定在持有者里做。
 */
fun interface ManifestProbe {

    /**
     * 调一次 `IHookCallback.capabilities()`。
     *
     * @return 清单 JSON。
     * @throws Throwable **拿不到时必须抛** —— 两种成因（旧 hook 层没有这个方法 /
     *   连接已断）在 AIDL 层面都是**异常**，不是空返回值。实现方**不要**把异常
     *   吞成空串：空串与「方法不存在」会被判成两个不同的 presence。
     */
    fun probe(): String
}

/**
 * 连接建立时的能力交换判定。
 *
 * ## ⚠️⚠️ 前置条件：**调用方必须先 `ping()` 成功**
 *
 * 本函数**假定 ping 已经通过**（它无法自己验这一点，那需要另一次 binder 调用）。
 * 这是 §3.1 的定案：`capabilities()` **本身就不存在**时不能当第一判据，
 * 第一判据是 `ping()`（最老、必然存在的方法）。
 *
 * 唯一调用点：[CapabilityPresenceHolder.probeNow] 里
 * `callback.ping()` 之后紧接 `exchangeOnConnect { callback.capabilities() }`。
 *
 * ## 判据（三段）
 *
 * | probe 的结果 | 判为 | 用户该做什么 |
 * |---|---|---|
 * | 返回 JSON（**含空数组**） | [CapabilityPresence.READY] | 可以试 |
 * | 抛异常 | [CapabilityPresence.ABSENT] | **升级 / 重启 App**（不是改 LSPosed 配置） |
 *
 * ⚠️ **「空清单」与「方法不存在」是两件事**（本模块最容易搞混的地方）：
 * 前者是「有这个方法，但它返回了空」⇒ `READY`（决定「有哪些能力」的是**清单内容**，
 * 不是这里）；后者是**调用抛**⇒ `ABSENT`。把前者误判成 `ABSENT` 会让用户
 * 被引去「升级 App」，而 App 其实是最新的。
 *
 * ⚠️ 返回 `""`（空串）判 `READY` —— 与 `presenceAfterExchange` 的语义一致：
 * 空串是「有这个方法，但它返回了空」。真正的坏 JSON 由清单解码那一步处理
 * （见 `CapabilityManifest.decode` 返回 null 的情形），**不影响 presence**
 * ——因为 presence 回答的是「方法在不在」，不是「清单能不能解」。
 */
fun exchangeOnConnect(probe: ManifestProbe): CapabilityPresence = try {
    // ⚠️ 用 presenceAfterExchange 而不是自己写 `if (json == null) …`：
    // 那段判据（null ⇒ ABSENT，其余 ⇒ READY）是 §6.3 的**定案语义**，
    // 已在 `CapabilityPresence.kt` 有单测。这里再写一遍 = 两处实现会漂移。
    presenceAfterExchange(probe.probe())
} catch (_: Throwable) {
    // ⚠️ 「调用抛异常」= 方法不存在（旧 hook 层）⇒ ABSENT。
    // 这一支**必须**存在：AIDL 的「方法不存在」就是抛，不是返回空串。
    presenceAfterExchange(null)
}

/**
 * 断开时的判定。
 *
 * ⚠️ 包一层而不是直接暴露 `presenceAfterDisconnect()`：让调用点读起来是同一套 API
 * （`exchangeOnConnect` / `presenceOnDisconnect`），且将来若要加「断开时还要清理什么」
 * 时只需改一处。
 *
 * ⚠️ **必须重置而不是保留旧值**（`presenceAfterDisconnect` 的类注释）：
 * 它回答的是「**此刻**连着的那一端有没有这个能力」。保留旧值会让
 * 「重连到一个更旧的 hook 层」时仍显示 `READY` —— 而 §6.3 明说
 * 「状态是采样的，采样的东西过期后**必须显式失效**」。
 */
fun presenceOnDisconnect(): CapabilityPresence = presenceAfterDisconnect()
