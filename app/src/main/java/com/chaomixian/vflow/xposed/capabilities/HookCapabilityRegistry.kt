package com.chaomixian.vflow.xposed.capabilities

import java.util.concurrent.ConcurrentHashMap

/**
 * **hook 侧**的 capability 注册表（`name → handler`）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §7.2b-2 / §7.2b-3。
 *
 * ## ⚠️⚠️ 与 App 侧 [com.chaomixian.vflow.xposed.capability.CapabilityRegistry] 是**两份**
 *
 * 两个进程，各持一份 —— **不是拷贝关系，是「跨进程各一处」**（§7.2b）：
 *
 * | | App 侧 `capability/CapabilityRegistry` | **本对象** |
 * |---|---|---|
 * | 进程 | App | **system_server**（hook 层） |
 * | 存什么 | `Capability`（**声明**：风险等级 / 降级方案 / 上限） | `CapabilityHandler`（**执行体**） |
 * | 回答 | 「有哪些能力、要不要降级」 | 「怎么执行」 |
 * | 谁读 | `CapabilityInvoker` 的调用入口 | `IHookCallback.invoke` 的分发 |
 *
 * ⚠️ **风险分级只在 App 侧**（§6.2）—— hook 层不管分级，
 * 这是「Hook 层不知道工作流的存在」（§3.2 硬约束）在 ③ 上的延伸。
 * 故本表**刻意不存** `CapabilityRisk` 之类的字段。
 *
 * ## ⚠️ 两表的键必须对得上，但**没有编译期保证**
 *
 * 名字是**跨进程协议的一部分**。App 侧发了 `X` 而本表没有 ⇒ 回 `capability_absent`
 * ⇒ 用户看到「这能力明明装了却说没有」。⇒ 两边都引用 `CapabilityNames` 的常量
 * （同一个 dex、同一个类，复用无两份拷贝的代价），让拼错在**编译期**暴露。
 *
 * ## 加新 capability = **这里追加一行**（§7.2b-2）
 *
 * 不在 `BinderTransport` 加方法、不在 AIDL 加签名 —— 见 [HookCapabilityRuntime] 的说明。
 *
 * ## 线程模型
 *
 * 注册在 `object` 初始化时（单线程），读取来自 **binder 线程**（`onInvoke`）
 * ⇒ 用 `ConcurrentHashMap`。
 */
object HookCapabilityRegistry {

    private val handlers = ConcurrentHashMap<String, CapabilityHandler>()

    init {
        // ── 加新 capability：**只在这里追加一行** ──
        // ⚠️ 应用内注册顺序无关（按 name 去重），但**别重排**已有行 ——
        // 让每次上游合并的冲突面停留在「尾部追加」这一种形态。
        register(DiagnosticCapabilityHandler())
        // 首个真实 capability（③ 存在的理由）：拿 ShortcutInfo 的**完整 Intent + extras 类型**。
        // 见 `QueryShortcutIntentsHandler` 的类注释（含「为什么 LocalServices 取不到」的真机结论）。
        register(QueryShortcutIntentsHandler())
    }

    /**
     * 注册一个 handler。**幂等**（同名重复注册会替换）。
     *
     * ⚠️ 按 `name` 去重而不是「无脑 append」：列式的注册表里同名两条会让
     * 「查表返回哪个」变成注册顺序的**隐式**结果 —— 本仓库已因这类隐式覆盖
     * 踩过三次坑（三处单槽位），措辞与 App 侧 `CapabilityRegistry.register` 一致。
     *
     * ⚠️ 空名直接拒绝：一个「名字为空的能力」永远查不到，
     * 而它会静静地待在 `names()` 里污染 `capabilities()` 清单。
     */
    fun register(handler: CapabilityHandler) {
        if (handler.name.isBlank()) return
        handlers[handler.name] = handler
    }

    /** 按名查。查不到返回 null —— **由调用方显式报 `capability_absent`**（§4.3）。 */
    fun find(name: String): CapabilityHandler? = handlers[name]

    /**
     * 已注册的名字集合。**这是 `capabilities()` 该上报的内容**（§3.1）。
     *
     * ⚠️ 是「**代码里有没有**」，不是「此刻能不能用」—— 后者由每次调用的
     * 实测结果决定（§6.3：「状态是采样的，结果才是权威」）。
     */
    fun names(): Set<String> = handlers.keys.toSet()

    /** 全部 handler（**按名排序**，输出稳定，便于展示与断言）。 */
    fun all(): List<CapabilityHandler> = handlers.values.sortedBy { it.name }

    /** 是否注册过某个名。 */
    fun contains(name: String): Boolean = handlers.containsKey(name)

    /**
     * **仅供测试**：清空注册表。
     *
     * ⚠️ 存在理由与 App 侧 `CapabilityRegistry.resetForTest` 相同：本对象是进程级单例，
     * 测试之间会串状态。测试必须在 `@After` 里调它，否则用例顺序会影响结果。
     *
     * ⚠️ 清空后**不会**自动重跑 `init` 的默认注册 —— 需要默认集时显式再注册。
     * 这是有意的：让「测试是不是依赖了生产注册表」这件事显式可见。
     */
    internal fun resetForTest() {
        handlers.clear()
    }
}
