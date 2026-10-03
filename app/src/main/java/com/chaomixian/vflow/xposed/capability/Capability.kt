package com.chaomixian.vflow.xposed.capability

/**
 * ③ 的一个能力成员（App 侧的**声明**）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.2 / §6.3。
 *
 * ## ⚠️⚠️ 与 `core/xposed/XposedCapability` **语义毫无关系，不得混用**
 *
 * 仓库里**已经有一个** `XposedCapability`（`core/xposed/XposedCapability.kt`），
 * 它是**权限判据**（「框架此刻连着吗」）。而本文件是**③ 能力的声明**（「有哪些能力、要不要降级」）。
 *
 * 两者只是**名字撞了**，`§6.2` 的评审已明确要求消歧：
 *
 * | | `core/xposed/XposedCapability` | **本包 `Capability`** |
 * |---|---|---|
 * | 回答 | 框架环境可用吗 | 有哪些能力 |
 * | 消费者 | `PermissionManager` | ③ 的调用点 |
 * | 状态位 | `XposedState.Framework`（L1） | `CapabilityPresence` |
 *
 * ⇒ **不要合并、不要互相引用、不要把 [CapabilityPresence] 放进 `XposedState`** ——
 * §6.1 明说「复用现有两套状态位，**不新造**」。
 *
 * ## ⚠️ 本文件必须保持「纯声明」
 *
 * 它会被 `xposed/` 包的引用面扫描覆盖（`xposed/` 下全部代码跑在 system_server）。
 * 因此：
 * - 不引任何 App 侧类（`core.*` / `ui.*` / `services.*`）；
 * - **不放在这里**：`fallback` 的具体实现 —— 那些实现必然是 dumpsys 路径，
 *   引用 `ui/shortcut_picker/ShortcutPickerSupport` 会让扫描变红
 *   （`FORBIDDEN_APP_PACKAGES` 含 `com.chaomixian.vflow.ui.`）。
 *   见 [CapabilityRegistry] 类注释里说明的分层。
 *
 * @property name capability 名。⚠️ **一经发布不要改** —— App 与 hook 两侧按名匹配。
 * @property risk 风险等级。用于审计与 AI 侧分级（不是执行时的门禁）。
 * @property fallback **降级实现**。
 *   - `null` ⇒ **独占型**：只有 Xposed 一条实现，不可用时**无从降级**（§6.2）
 *   - 非 null ⇒ **替换型**：Xposed 不可用时静默降级 + 留痕
 *
 *   ⚠️ **必须接收 `params`**（§6.2 的 S7 修正）：契约是
 *   「**同样的入参、同形状的结果、更差的实现**」—— 初版写成无参 `() -> Any`，
 *   那样每个降级实现只能自己去读外部状态，**重演「各消费者只算自己那份」**（§7.4 反模式 2）。
 * @property maxResultBytes §3.6：这个 capability 的响应大小上限（字节）。
 *   `null` ⇒ 用 `ResultBudget.DEFAULT_MAX_RESULT_BYTES`（256 KiB）。
 *   ⚠️ 声明得越小越安全 —— 那半边异步缓冲是**与所有其他 oneway 事务共享**的。
 * @property timeoutMs §5.2：这个 capability 的超时。`null` ⇒ 5000ms。
 *   不同 capability 耗时差异很大（全量 `ShortcutInfo` vs 一次读字段），**必须能逐项覆盖**。
 * @property idempotent §10-#15：调用方**不自动重试**，
 *   但声明出来是为了将来接**写类** capability 时能被审计到
 *   （「无状态」≠「无副作用」—— 重试会生效两次）。首版均为只读 ⇒ 默认 true。
 */
data class Capability(
    val name: String,
    val risk: CapabilityRisk,
    val fallback: (suspend (params: Map<String, Any?>) -> Map<String, Any?>)? = null,
    val maxResultBytes: Int? = null,
    val timeoutMs: Long? = null,
    val idempotent: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "capability 名不能为空" }
    }
}

/**
 * capability 的风险等级。
 *
 * ## ⚠️⚠️ 为什么**自建**这个枚举，而不复用 `core.module.AiModuleRiskLevel`
 *
 * 值域刻意与 `AiModuleRiskLevel` 对齐（`READ_ONLY` / `LOW` / `STANDARD` / `HIGH`），
 * 但**必须是两个类型**：
 *
 * 本包受 `CapabilityContractPurityTest` 的「`capability/` 不得引用 App 侧包」
 * 那条扫描管辖（`FORBIDDEN_APP_PACKAGES`，**含 `com.chaomixian.vflow.core.`**）——
 * 引用 `AiModuleRiskLevel` 会**直接让测试变红**。
 * ⚠️ **不要写成 `WireLayerPurityTest`**：那条管的是 `xposed/wire/` 与
 * `xposed/capabilities/`（复数），与本包（单数）是**两条独立的防线**。
 *
 * 三条出路里选了这个：
 *
 * | 出路 | 代价 | 为什么否决/采纳 |
 * |---|---|---|
 * | 自建枚举（**本选择**） | 多一个枚举 + App 侧一处映射 | ✅ 零放宽度，防线完整 |
 * | 把 `capability/` 排除出扫描 | 少一个枚举 | ❌ 否决：将来真有人在 hook 层引用本包就没防线了 |
 * | 复用 `AiModuleRiskLevel` | — | ❌ 否决：破坏引用面，崩溃半径纪律失效 |
 *
 * ⚠️ **等级本身的语义与 `AiModuleRiskLevel` 一致**，App 侧需要时做一次映射即可。
 */
enum class CapabilityRisk {
    /** 只读、无副作用（如查询快捷方式）。 */
    READ_ONLY,

    /** 有副作用但影响面小且可逆（如写剪贴板）。 */
    LOW,

    /** 常规操作。 */
    STANDARD,

    /** 高风险（如注入输入、关闭 Activity）。**调用前应确认**。 */
    HIGH,
}
