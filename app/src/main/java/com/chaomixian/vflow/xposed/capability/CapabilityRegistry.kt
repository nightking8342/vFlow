package com.chaomixian.vflow.xposed.capability

import java.util.concurrent.ConcurrentHashMap

/**
 * App 侧的 **capability 注册表**（③ 的权威声明处）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.2 / §7.2b-3。
 *
 * ## 为什么必须有它（§6.2 的理由，不是可选项）
 *
 * 「风险分级**必须落在 App 侧的注册表上，hook 层不管分级**」——
 * 这是「**Hook 层不知道工作流的存在**」（§3.2 硬约束）在 ③ 上的延伸。
 *
 * 若走「一类能力一个专用 AIDL 方法」，分级会散在两端的 30 个方法里、**审计不了**。
 *
 * ## ⚠️⚠️ 关于 `fallback` 的实现放哪里（**任务 4 必须注意的分层**）
 *
 * [Capability.fallback] 声明的是**一个函数类型**（纯 Kotlin，不引任何 App 类），
 * 但**它的实现必然引用 App 侧代码** —— 例如快捷方式的降级路径是 dumpsys，
 * 实现体在 `ui/shortcut_picker/ShortcutPickerSupport`。
 *
 * 而本包在 `xposed/` 下，受「不得引用 `com.chaomixian.vflow.ui.`」的扫描管辖
 * ⇒ **具体的注册动作（把 lambda 装进去）必须发生在 `xposed/` 之外的文件里**。
 *
 * ```
 * xposed/capability/CapabilityRegistry.kt      ← 本文件：只放注册表 + 类型
 * core/xposed/CapabilityFallbacks.kt（任务 4）  ← App 侧：在这里 register(Capability(…, fallback = { … }))
 * ```
 *
 * ⚠️ **不要图省事在本文件里 `import` 那些实现** —— 扫描会红，
 * 且真在 system_server 里加载了它们会**把崩溃半径从「那个 App」扩大到整机**。
 *
 * ## 线程模型
 *
 * 注册发生在 App 启动期（单线程），读取可能来自工作线程 ⇒ 用 `ConcurrentHashMap`。
 */
object CapabilityRegistry {

    private val capabilities = ConcurrentHashMap<String, Capability>()

    /**
     * 注册一个 capability。**幂等**（同名重复注册会替换）。
     *
     * ⚠️ 按 `name` 去重而不是「无脑 append」：列式的注册表里同名两条会让
     * 「查表返回哪个」变成注册顺序的**隐式**结果 ——
     * 本仓库已因这类隐式覆盖踩过三次坑（三处单槽位）。
     */
    fun register(capability: Capability) {
        capabilities[capability.name] = capability
    }

    /** 按名查。查不到返回 null（**由调用方决定怎么报错**，见下）。 */
    fun find(name: String): Capability? = capabilities[name]

    /**
     * 全部已注册的 capability（**按名排序**，便于展示与断言）。
     */
    fun all(): List<Capability> = capabilities.values.sortedBy { it.name }

    /** 已注册的名字集合。**这是 `capabilities()` 该上报的内容**（§3.1）。 */
    fun names(): Set<String> = capabilities.keys.toSet()

    /** 是否注册过某个名。 */
    fun contains(name: String): Boolean = capabilities.containsKey(name)

    /**
     * **仅供测试**：清空注册表。
     *
     * ⚠️ 存在理由：本对象是进程级单例，测试之间会串状态 ——
     * 与 `HookChannelController.injectTokenForTest` 是同一种接缝。
     * 测试必须在 `@Before`/`@After` 里调它，否则用例顺序会影响结果。
     */
    internal fun resetForTest() {
        capabilities.clear()
    }
}
