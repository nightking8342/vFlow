package com.chaomixian.vflow.core.workflow.model

/**
 * 磁贴的**种类**（两池划分的判据）。
 *
 * | kind | tileIndex 区间 | 手势 | 状态 |
 * |---|---|---|---|
 * | [EXECUTE] | 0–19 | 点 = **执行一次** | **恒不高亮** |
 * | [TOGGLE] | 20–39 | 点 = **开/关自动触发** | 高亮 = 已启用 |
 *
 * ## 为什么是独立的枚举而不是「tileIndex 落在哪个区间」
 *
 * 语义写在数据里，读代码时不必记「0–19 是什么」；
 * 且备份（`TileScope` 是**文本层合并**）与 `TileManager` 的 Gson 反序列化
 * 都**天然容忍新字段** ⇒ 旧记录缺 `kind` 时落默认值 [EXECUTE]，**零迁移**。
 *
 * ⚠️ **两池不再互斥**（用户 2026-10-10 修订，此前是「强制互斥」）：
 * 执行型只要求**有手动触发器**，开关型只要求**有自动触发器** ⇒ 同时挂着两种触发器的工作流
 * **可以同时**占一个执行槽与一个开关槽。判据**只有一处** —— 见 `TileGate`，
 * 任何地方自己写 `hasAutoTriggers()` / `hasManualTrigger()` 都会让「三道闸判据一致」失效。
 *
 * ⚠️ 独立成文件（而非塞进 `WorkflowTile.kt`）是为了**纯 JVM 单测**能单独引用它 ——
 * `WorkflowTile` 带 `@Parcelize`、依赖 android，引它会连 `Parcel` 一起拖进测试。
 */
enum class TileKind {
    /** 执行型：点一下执行工作流。 */
    EXECUTE,

    /** 开关型：点一下开/关工作流的自动触发。 */
    TOGGLE,
}
