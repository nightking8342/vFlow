package com.chaomixian.vflow.xposed.wire

/**
 * 执行模式（三档）的**协议词汇表**。
 *
 * 三档照搬 ShortX 的 `ExecuteJS`：`default`（CPU 密集）/ `io`（阻塞型）/ `ui`（需要 Looper）。
 * 取值随 [CapabilityRequest] 跨进程下行，由 hook 侧的信封层读取并据此**选择执行器**。
 *
 * ## ⚠️ 为什么放在 `xposed/wire/` 而不是 App 侧
 *
 * 两侧都要用它，而**两侧的引用面是单向不对称的**：
 *
 * | 方向 | 允许？ | 依据 |
 * |---|---|---|
 * | App 侧 → `xposed/` | ✅ 允许 | 仓库既有做法（`XposedJsModule` 引 `CapabilityNames`） |
 * | `xposed/` → `com.chaomixian.vflow.core.*` | ❌ **禁止** | `WireLayerPurityTest.FORBIDDEN_APP_PACKAGES` |
 *
 * ⇒ hook 侧（`InvokePolicy.threadModeOf`，跑在 system_server）**够不到**放在
 * `core/workflow/module/xposed/XposedJsSupport.kt` 里的实现。
 * 两处各写一份是 `FORK.md` 记过的「双份实现」缺陷形态（logcat 那次：
 * 语义改动必须同时改两处，不一致的表现是「调试工具里看着能匹配的日志，触发器匹配不到」）。
 * ⇒ **这里是一处、两侧共用。**
 *
 * ## 依赖白名单
 *
 * 本文件**零 import**（纯常量与纯函数），故天然通过 `WireLayerPurityTest` 的引用面扫描。
 */
object ThreadModes {
    const val DEFAULT = "default"
    const val IO = "io"
    const val UI = "ui"

    /** 三档的**唯一**定义处。加第四档只需改这里。 */
    val KNOWN: Set<String> = setOf(DEFAULT, IO, UI)

    /**
     * 归一：**未知 / null / 空白一律降级为 [DEFAULT]，绝不抛异常**。
     *
     * ## ⚠️⚠️ 为什么「静默降级」而不是报错
     *
     * 这是本字段的**硬约束**。场景：新 App 发了 `io`，而设备上的 hook 层还是旧版、不认识它。
     *
     * | 处置 | 后果 |
     * |---|---|
     * | 报错 | 整个调用**失败** —— 用户看到的是「功能坏了」 |
     * | **降级 `default`**（本函数） | 脚本照常执行，只是**资源画像不准**（本该走 IO 池的走了默认池） |
     *
     * 后者远轻于前者，且**不会让用户察觉不到的功能消失**。
     *
     * ## ⚠️ 大小写敏感（协议值一律小写）
     *
     * `"IO"` 属于**未知值**，回落 `default`。不额外做 `lowercase()`：
     * 那会让「协议里出现了大写」这个真实的编码缺陷被静默抹平 ——
     * 而协议值全小写是 `ThreadModes.KNOWN` 里字面量本身定的，没有歧义。
     */
    fun normalize(raw: String?): String =
        if (raw != null && raw in KNOWN) raw else DEFAULT
}
