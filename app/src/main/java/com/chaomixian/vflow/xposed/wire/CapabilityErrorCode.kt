package com.chaomixian.vflow.xposed.wire

/**
 * ③ 能力调用的**失败原因枚举**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.4。
 *
 * ## ⚠️⚠️ 为什么必须是枚举而不是自由字符串
 *
 * §3.3 的信封只规定 `ok=false` **必须带 `error`**，而那**曾经**是个自由字符串。
 * 若照此实现，§6.4 那张「失败原因 → 用户该做什么」的分类表就只能靠
 * **匹配中文文案** ⇒
 *
 * ① 改一次文案就分类错；
 * ② 第 4/5 类（通道断）本可在**调用前**判定，却也只能等超时后从文案里认。
 *
 * ⇒ 定枚举（2026-09-29 B 组定案，不再是「建议」）。
 *
 * ## ⚠️ 两条硬约束（定案时一并写下，**实现时必须遵守**）
 *
 * | # | 约束 |
 * |---|---|
 * | 1 | §6.4 那张分类表的**每一行必须能映射到恰好一个**枚举值 —— 若某行映射不到，说明分类漏了一类；若某行要映射两个，说明它该拆行 |
 * | 2 | **`detail` 只给人看，绝不参与判断** —— 它是自由文本，将来会被本地化（三语），拿它做分支等于埋一个「切语言就坏」的雷 |
 *
 * 约束 2 在本文件里的落实方式：`detail` **完全不在本文件出现** ——
 * 它是 [CapabilityError] 的一个字段，而本枚举只负责 `code`。
 * 任何 `if (detail.contains("…"))` 都应当被 review 拒绝。
 *
 * ## `payload_too_large` 的归属（§6.4 特别说明）
 *
 * 它**不是**用户能处理的失败，而是**实现缺陷或数据异常** ——
 * §3.6 的契约要求 hook 侧**主动截断**并带标志位 ⇒ 正常路径下**不该出现这个码**。
 *
 * ⇒ 出现它时走「**报告问题**」（与 [TIMEOUT] 同类），
 * **不要**把用户引去改配置。见 [userAction]。
 */
enum class CapabilityErrorCode(
    /**
     * 线上的字符串值。
     *
     * ⚠️ **一经发布不要改** —— 它是跨进程协议的一部分，改了会让新旧两端对不上。
     */
    val wire: String,
) {

    /**
     * hook 层**没有**这个 capability（代码版本太旧，或名字拼错）。
     *
     * ⚠️ 与「未知 topic 忽略」**方向相反**（§3.2/§4.3）：
     * 事件流多一条少一条无所谓，而调用方**在等结果** ——
     * 静默会让超时把排查引向错误方向（用户会往 hook 点/系统版本上找）。
     *
     * 生产者：App 侧（`CapabilityPresence.ABSENT` 时**本地生成，不发给 hook 层**）；
     *        hook 侧的 capability 注册表（收到未知 name 时显式报错）。
     */
    CAPABILITY_ABSENT("capability_absent"),

    /** App 侧配对表超时（hook 层完全不响应），或 hook 侧工作线程超时。 */
    TIMEOUT("timeout"),

    /**
     * handler 自己抛了异常。
     *
     * ⚠️ hook 侧工作线程的**顶层 `try/catch(Throwable)`** 把它转成这个码 ——
     * 异常绝不允许逃逸（在 system_server 里逃逸 = 整机，§5.2）。
     */
    HANDLER_ERROR("handler_error"),

    /** 通道断了（连接被清掉）。由 App 侧**断连时立即唤醒 waiter** 生成，不等超时。 */
    CHANNEL_DOWN("channel_down"),

    /**
     * 载荷超出声明上限。
     *
     * ⚠️ **不是用户能处理的失败** —— 见类注释的「归属」一节。
     */
    PAYLOAD_TOO_LARGE("payload_too_large");

    companion object {

        /**
         * 线上字符串 → 枚举。
         *
         * ⚠️ **未知值返回 null 而不是回落到某个默认码** ——
         * 调用方必须自己决定怎么兜（通常是 [HANDLER_ERROR] + 把原始串放进 detail）。
         * 静默回落会让「新 hook 层引入了新错误码」这件事在旧 App 上**看不出来**。
         *
         * ⚠️ 用 `entries` 线性查找而非 `valueOf`：`valueOf` 按**枚举常量名**匹配，
         * 而线协议用的是 [wire] 字符串（两者可能不同）。
         */
        fun fromWire(value: String): CapabilityErrorCode? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 该错误码对应的**用户处置**（§6.4 那张表**直接落在枚举上**，UI 侧不再做字符串判断）。
 *
 * ## ⚠️ 这个枚举存在的理由
 *
 * §6.4 的立意是：**混合失败原因会让用户去白折腾错误的方向** ——
 * 该表的原话举了 P4 踩过的坑：`TriggerService` 加载比 hook 连接早 1.6 秒，
 * 旧文案却让用户「检查 LSPosed 配置」，而他的配置完全正确。
 *
 * 把「用户该做什么」与「错误码」**分开成两个枚举**（而非塞进一个），
 * 是为了让「多个码指向同一个处置」这件事**能被表达** ——
 * 例如 [CapabilityErrorCode.TIMEOUT] 与 [CapabilityErrorCode.PAYLOAD_TOO_LARGE]
 * 都指向 [REPORT_PROBLEM]，因为两者**都不是用户能处理的**。
 */
enum class CapabilityErrorAction {

    /**
     * **升级 / 重启 App**。
     *
     * ⚠️ 刻意**不是**「去改 LSPosed 配置」—— 这条指向 **App 侧**，
     * 是 §6.4 强调的「前三/四类指向 App 侧、其余指向框架配置」里的前者。
     */
    UPGRADE_APP,

    /**
     * **报告问题**（很可能是 bug）。
     *
     * ⚠️ [CapabilityErrorCode.PAYLOAD_TOO_LARGE] **也在这一类** ——
     * 见该码的「归属」说明：正常路径下不该出现，是**实现缺陷**。
     * 把它引向「改配置」会让用户白折腾。
     */
    REPORT_PROBLEM,

    /** **看具体能力**（handler 自己的错误，得看它自己的文档/日志）。 */
    CHECK_CAPABILITY,

    /**
     * **检查 LSPosed / 等自动重连**。
     *
     * 复用现有 `XposedState.tapAction()` 的 `RECONNECT_HINT` 路径 ——
     * 不另造一套提示机制。
     */
    CHECK_LSPOSED,
}

/**
 * §6.4 那张表的**逐行映射**。
 *
 * ⚠️ 约束 1 的落实点：**每个枚举值恰好映射一个处置**，
 * 且**每个用例都不遗漏**（`CapabilityErrorCodeTest` 会断言五个值全部有映射 ——
 * 将来加第六个码时，`when` 若写全就不编译不了，写不全则测试变红）。
 */
fun CapabilityErrorCode.userAction(): CapabilityErrorAction = when (this) {
    // 前三/四类指向 App 侧
    CapabilityErrorCode.CAPABILITY_ABSENT -> CapabilityErrorAction.UPGRADE_APP
    CapabilityErrorCode.TIMEOUT -> CapabilityErrorAction.REPORT_PROBLEM
    CapabilityErrorCode.HANDLER_ERROR -> CapabilityErrorAction.CHECK_CAPABILITY
    CapabilityErrorCode.CHANNEL_DOWN -> CapabilityErrorAction.CHECK_LSPOSED
    // ⚠️ 与 TIMEOUT 同类（都是「报告问题」），刻意**不**引向改配置
    CapabilityErrorCode.PAYLOAD_TOO_LARGE -> CapabilityErrorAction.REPORT_PROBLEM
}
