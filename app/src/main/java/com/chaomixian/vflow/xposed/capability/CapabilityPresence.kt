package com.chaomixian.vflow.xposed.capability

/**
 * ③ 能力可用性：**「连接期一次能力交换的缓存」，不是状态机**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.3。
 *
 * ## ⚠️⚠️ 定位：它与 §6.1 的两组状态位**不是一类东西**
 *
 * 初版曾把它写成「与 `Framework` / `Channel` 并列的第三个状态位」—— **不准确**。
 * 它**不是持续维护的状态机**，而是**连接建立时一次能力交换结果的缓存**。
 *
 * | | 回答的问题 | 来源 | 变化频率 |
 * |---|---|---|---|
 * | `XposedState.Channel.NOT_MOUNTED` | 模块**挂到 system 了吗** | `runningTargets`（L3） | 秒级 |
 * | **`CapabilityPresence.ABSENT`** | hook 层的**代码里有没有这个方法** | `ping()` + `capabilities()` | **只在连接期变一次** |
 *
 * ⚠️ 一个**已挂载的旧版本 hook 层**，`Channel` 判 `READY` 而本枚举判 `ABSENT`。
 * **两者同时成立是正常的，不是矛盾。**
 *
 * ## ⚠️ 物理位置必须是 capability 包（§6.3 的硬要求）
 *
 * 它**是**一个 enum、**有**状态迁移（连接填入 / 断开重置），
 * 所以**放在 `core/xposed/` 的状态模块里一定会被当成第三组状态位** ——
 * 而 §6.1 明说「复用现有两套，**不新造**」。
 *
 * ⇒ 放这里，**不进 `XposedState`，不改那两个枚举的取值集合**。
 *
 * ## ⚠️ 为什么本文件**只有枚举与纯函数**，没有任何持有者
 *
 * 一个可 `collect` 的持有者需要 `kotlinx.coroutines.flow`，
 * 而 `WireLayerPurityTest` 对 `xposed/` 的 import 白名单**不含它**
 * （见 [com.chaomixian.vflow.xposed.wire.ResultBudget] 类注释里的同一约束）。
 *
 * 本包在 `xposed/` 下 ⇒ 这里只放**纯逻辑**。
 * 可订阅的持有者由 App 侧放在 `core/xposed/`（那里能引用 coroutines）。
 *
 * ## ⚠️ 它**不用来判降级**
 *
 * ```
 * 状态：提前决定【要不要试】   （优化，可能错 —— 状态是采样的）
 * 结果：决定【要不要降级】     （权威，每次实测）
 * ```
 *
 * ⇒ **降级判断的依据永远是运行时调用结果**，本枚举只用来**避免白试**
 * （旧 hook 层没有 `invoke` 时，oneway 调用会被 binder **静默丢弃**，
 * 不判就会**每次白等 5 秒超时**）。§7.4 反模式 8 说的就是这个。
 */
enum class CapabilityPresence {

    /** 还没做过能力交换（未连接 / 刚断开）。 */
    UNKNOWN,

    /**
     * hook 层**代码里没有** ③ 的能力交换（太旧）。
     *
     * ⇒ 别去试，直接走降级或明确告知。**不要**把用户引去改 LSPosed 配置
     *（§6.4：这一类指向 **App 侧**，该做的是升级 / 重启 App）。
     */
    ABSENT,

    /** 交换成功（**哪怕清单是空的**）。可以试。 */
    READY,
}

/**
 * 连接期能力交换的结果 → presence。**纯函数，可单测**。
 *
 * ## ⚠️⚠️ 判据的关键：`null` 与 `""` 是**两件不同的事**
 *
 * @param capabilitiesJson `capabilities()` 的返回值。**`null` 表示「拿不到」**，
 *   有两种成因，处理相同：
 *   - 旧 hook 层**根本没有这个方法** ⇒ binder 调用抛
 *     （`TransactionException` / `NoSuchMethod`），调用点必须 `try/catch` 转成 `null`；
 *   - 调用失败 / 连接已断。
 *
 *   ⚠️ 而**空串**（`""`）是**另一回事** —— 它是「有这个方法，但它返回了空」。
 *   本函数对空串判 [CapabilityPresence.READY]，因为方法确实存在。
 *   真正决定「有哪些能力」的是清单内容，不是这里。
 *
 * ## 为什么这条判据必须写死（§3.1 已定案）
 *
 * `capabilities()` **本身就不存在**时，它不能作为「有哪些能力」的**第一判据**。
 * 第一判据是 `ping()` 能否成功（最老、必然存在的方法）：
 * **ping 通但 `capabilities()` 收不到 ⇒ ABSENT（hook 层太旧）**。
 *
 * ⚠️ 所以调用点必须先 `ping()` 成功，再来调本函数 ——
 * 本函数**假定 ping 已经通过**（它无法自己验这一点，那需要 binder 调用）。
 */
fun presenceAfterExchange(capabilitiesJson: String?): CapabilityPresence =
    if (capabilitiesJson == null) CapabilityPresence.ABSENT else CapabilityPresence.READY

/**
 * 断开时**重置**。纯函数。
 *
 * ⚠️ 为什么要重置而不是保留上一次的值：它回答的是「**此刻**连着的那一端有没有这个能力」。
 * 保留旧值会让「重连到一个更旧的 hook 层」时仍显示 `READY` ——
 * 而 §6.3 明说「状态是采样的」，采样的东西过期后**必须显式失效**，不能悄悄沿用。
 */
fun presenceAfterDisconnect(): CapabilityPresence = CapabilityPresence.UNKNOWN
