package com.chaomixian.vflow.xposed.capability

/**
 * capability 名字符串的**集中登记处**。
 *
 * ## ⚠️ 为什么要有这个文件（而不是各处直接写字符串字面量）
 *
 * capability 名是**跨进程协议的一部分**：App 侧发 `Name`、hook 侧按 `Name` 查表。
 * 写错一个字母的表现是：
 *
 * - App 侧：`ping` 通、`CapabilityPresence` 是 `READY`（方法在，清单也拿到了）
 * - hook 侧：收到一个**不在注册表里**的 name ⇒ 回 `capability_absent`
 *
 * ⇒ 用户看到「这个能力明明装了却说没有」，而两端各自看都没问题。
 * 收敛成常量能让「拼错」在**编译期**就被发现（IDE 会提示未定义引用）。
 *
 * ⚠️ **本包在 `xposed/` 下，会被 hook 层引用** —— 因此只放**纯字符串常量**，
 * 不得引用任何 App 侧类（`WireLayerPurityTest` 管辖）。
 *
 * ## 与 hook 侧注册表的关系
 *
 * hook 层也有自己的 `name → handler` 注册表（任务 3 实现），
 * 它**可以直接引用本文件的常量** —— 同一个 dex、同一个类，
 * 复用不会有「两份拷贝」的代价（`FORK.md` 里 logcat 那条链路的教训不适用这里）。
 */
object CapabilityNames {

    /**
     * 查询某个 App 的快捷方式**完整 Intent**。
     *
     * 首个 capability（任务 4 实现）。它存在的理由见
     * `docs/fork/surveys/shortcut-system-overview.md` §4.2：
     * dumpsys 路径有 18.1% 的 dat 结构性残缺 + 10.8% 的类型靠猜，
     * **只有拿到 `ShortcutInfo` 对象才能保真**。
     *
     * 类型：**替换型**（dumpsys 是替代实现，但有损）。
     */
    const val QUERY_SHORTCUT_INTENTS = "query_shortcut_intents"

    /**
     * **诊断能力**（开发期自证用）。
     *
     * 存在的理由：池满 / 超时 / 截断 / 异常这**四条失败路径**用真实 capability
     * 很难稳定触发（要凑并发、要造超大数据）。而它们恰恰是**最需要被端到端验证**的
     * —— 失败路径出错时往往**静默**（回一个错误的码、或干脆不回）。
     *
     * 本能力用 `params.mode` 显式选择要走的路径：
     *
     * | `mode` | 行为 |
     * |---|---|
     * | `ok` | 正常返回 |
     * | `slow` | 睡超过 `timeout_ms` ⇒ 触发超时判定 |
     * | `throw` | 抛异常 ⇒ 触发顶层兜底 |
     * | `huge` | 产出超过上限的结果 ⇒ 触发截断 + 分页 |
     *
     * ⚠️ 风险等级是 `READ_ONLY`（**无副作用**）：`slow` 只 sleep、
     * `huge` 只造数据、`throw` 只抛异常。故它留在生产包里是安全的
     * （**不做**「debug 构建才有」的条件编译 —— 那会让真机验证必须在 debug 包上做，
     * 而本项目交付一律用 release，两者冲突）。
     */
    const val DIAGNOSTIC = "diagnostic"
}
