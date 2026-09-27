package com.chaomixian.vflow.xposed

import io.github.libxposed.api.XposedInterface

/**
 * hook 点位**适配器**：一类事实一个实现。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.1 的第一层。
 *
 * ## 分层职责（这是「加第 30 个触发器时改动面积不变」的关键）
 *
 * ```
 *   HookSource（本接口）      ← 只做「挂点 + 取值 + 序列化」
 *          │ rt.emit(topic, payload)
 *          ▼
 *   HookRuntime               ← 信封装配、有界队列、发送线程
 *          │
 *          ▼
 *   HookTransport             ← 只管搬信封
 * ```
 *
 * ## ⚠️⚠️ 本接口**刻意不提供的**东西（缺了这些，第一层才能保持轻薄）
 *
 * | 不提供 | 理由 |
 * |---|---|
 * | ❌ 序列化 / 通信 | 那是 [HookRuntime] 与 [HookTransport] 的事。适配器**不知道 `seq`** |
 * | ❌ 背压策略 | 必须框架层统一。各适配器自己写「满了怎么办」必然出现「有的阻塞拖垮 system_server / 有的无界 OOM / 有的静默丢」（§3.4.4） |
 * | ❌ 业务规则 | [applyConditions] 收到的**只是过滤项**（哪个包的哪个 hook 点），不是「该不该触发哪个工作流」 |
 *
 * 最后一条是 §3.2 的硬约束：**Hook 层不知道工作流的存在**。
 * 所以改规则根本不需要碰 hook 层 —— 判定全在 App 侧。
 *
 * ## ⚠️ 实现约束（比接口本身更重要）
 *
 * 1. **所有方法都可能被任意线程调用**（hook 回调在 system_server 的线程上）。
 * 2. **`mount` 必须幂等**，且**整体 `try/catch(Throwable)`** —— 它跑在 system_server 里，
 *    未捕获异常的危险性是「整机」（§5.1）。
 * 3. **hook 回调内只做「取值 + `rt.emit()`」**，绝不做 IPC / I/O。
 *    这不只是性能：P0 期间三次「模块不加载」中嫌疑最大的一条就是
 *    「hook 回调里新增调用」（`P0-FINDINGS.md` §7）。
 */
interface HookSource {

    /**
     * 该适配器产出的事件主题，如 `hook.activity.changed`。
     *
     * ⚠️ **一经发布不要改** —— App 侧按它路由（§3.4.2 的 topic 契约）。
     */
    val topic: String

    /**
     * 挂载。**幂等**；由 [HookRuntime.start] 统一调用。
     *
     * @param runtime 用于 `emit(topic, payloadJson)`
     * @param classLoader 目标进程（system_server）的 ClassLoader。
     *   ⚠️ **不要把它缓存到静态字段** —— 热更新是新 classloader 加载新代码，
     *   静态字段在新代际里是全新的（实测读到 null）。
     */
    fun mount(runtime: HookRuntime, classLoader: ClassLoader)

    /** 卸下。幂等；由 [HookRuntime.stop] 调用。 */
    fun unmount()

    /**
     * 应用过滤条件（App 下行）。
     *
     * ⚠️ 收到的 `conditionsJson` 里**只有过滤项**（如「只关心这些包」），
     * **不含任何业务语义**。实现可以据此少 emit，但**不得**据此决定「该触发谁」。
     *
     * @param conditionsJson 全量条件；空串表示「没有订阅者了，可以停掉采集」
     */
    fun applyConditions(conditionsJson: String)

    /**
     * 热更新后重挂 hook。
     *
     * ## ⚠️⚠️ 为什么这个方法**必须有**（不是可选的优化）
     *
     * 热更新**不会自动重放**任何回调（官方明文），而且
     * `onHotReloaded` 的**默认实现会 unhook 全部旧 hook**。
     * 也就是说：热更新之后，**原来的 hook 会消失**。
     *
     * 而本项目的部署方式恰恰是「重装 APK」（`autoHotReload=true`）——
     * 不重挂的话，表现是**「重装后心跳正常、但事件永远不再产生」**：
     * 通道看起来是活的，功能却是死的。这正是本仓库反复记录的静默失效形态。
     *
     * ## 正解（P0 实测确证）
     *
     * 用 `oldHandles` 里每个句柄的 `replaceHook(新 Hooker)` **原子替换** ——
     * **不需要 ClassLoader**（`HotReloadedParam` 根本没有 `getClassLoader()`，实测）。
     * 回调需要的类从 `chain.getExecutable().getDeclaringClass()` 推，**不要缓存**。
     *
     * @param oldHandles 旧代际的句柄（来自 `HotReloadedParam.getOldHookHandles()`）
     * @return 是否成功接手（false 表示该 source 在热更新后不可用）
     */
    fun remountAfterHotReload(
        oldHandles: List<XposedInterface.HookHandle>,
        runtime: HookRuntime,
    ): Boolean
}
