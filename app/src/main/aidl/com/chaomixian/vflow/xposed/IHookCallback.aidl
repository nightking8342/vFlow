// hook 层（注入 system_server）实现，由 App 反向调用。
//
// 设计文档：docs/fork/xposed-channel-design.md §4.2.2。
package com.chaomixian.vflow.xposed;

interface IHookCallback {

    /**
     * 下发过滤条件（**全量替换**）+ 鉴权 token。
     *
     * **刻意不用 `oneway`**：下发的成败必须能被 App 感知 ——
     * 「下不去」意味着触发器不会工作，而用户只会看到「没反应」。
     * 这与 `LogcatTriggerHandler` 里那条注释同源：
     * 「不静默：下不去意味着触发器不会工作」。
     *
     * ⚠️ 调用方（App）**不得在主线程上调它**：它最终会跑到 system_server 侧。
     *
     * @param conditionsJson 全量条件；**空串表示「没有触发器了，请卸下 hook」**
     * @param token 下行鉴权凭据。hook 层把它放进后续每一条信封里，
     *              App 侧靠它判定上行真伪。
     * @return 是否接受（hook 层成功记录条件）
     */
    boolean pushConditions(String conditionsJson, String token);

    /**
     * 心跳探活。App 侧周期性调用，用来区分
     * 「hook 层活着但没事件」与「hook 层已死/未注入」。
     *
     * ⚠️ **不能只靠 `onServiceDisconnected` 判活性** ——
     * 进程被杀、socket 不发 FIN 时它不一定触发
     * （Core 的 `LogcatStreamWrapper` 就吃过这个亏）。
     *
     * 返回协议版本号，供 App 侧做版本协商（未知 topic 必须忽略而非崩溃）。
     *
     * ⚠️⚠️ **本方法的签名刻意保持 `int` 不变**（2026-09-29 B 组定案，V2.0 §3.1）：
     * 能力清单**不塞进它**，而是新增独立的 [capabilities]。理由：
     * ① 职责分离（版本号与能力清单是两件事，塞一起就只能一起演进）；
     * ② `ping` 的语义是「你活着吗」，改成返回 JSON 会让一次简单探活变成解析字符串。
     *
     * ⚠️ **它是「能力存在性探测」的第一判据** —— 最老、必然存在的方法。
     * 「ping 通但 [capabilities] 收不到」⇒ 判 `CapabilityPresence.ABSENT`（hook 层太旧）。
     */
    int ping();

    /**
     * 连接期**一次能力交换**：返回这个 hook 层**代码里有哪些 capability**。
     *
     * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.1 / §6.3。
     *
     * ## ⚠️ 它是 `CapabilityPresence` 的唯一数据源，但**不是**第一判据
     *
     * 新版 App + 旧 hook 层时，**本方法本身就不存在** ⇒ 收不到任何东西
     * （旧实现里没有这个导出方法）。所以调用方必须：
     *
     * ```
     * ping() 成功  &&  capabilities() 调用失败/方法不存在  ⇒ ABSENT（hook 层太旧）
     * ping() 成功  &&  capabilities() 返回（哪怕空清单）  ⇒ READY
     * ```
     *
     * 「返回空清单」与「方法不存在」**是两件事**，不能混为一谈。
     *
     * ## ⚠️ 为什么它该存在（否则调用方要白等超时）
     *
     * oneway 调用一个**不存在的 capability** 时，binder **静默丢弃** ——
     * 没有错误、没有返回值、没有回调。App 侧只能等到超时才知道失败。
     * 有了本方法，调用前就能判「要不要试」，不必每次白等 5 秒。
     *
     * ## 形态
     *
     * JSON：`{"protocol_version":1,"capabilities":["query_shortcut_intents", ...]}`
     * 编解码统一走 `CapabilityManifest`（`xposed/wire/`）。
     * 坏 JSON / 解析失败**必须返回空清单而不是抛** —— 见下。
     *
     * **刻意不用 `oneway`**：调用方要拿返回值。
     *
     * @return 能力清单 JSON。**任何异常都必须被实现方吞掉并返回空清单** ——
     *   本方法跑在 system_server 里，抛异常会跨国界且无人处理。
     */
    String capabilities();

    /**
     * ③ 的**统一入口**：发起一次能力调用（严格说：把请求**提交**给 hook 层）。
     *
     * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.2 / §3.4。
     *
     * ## ⚠️ 为什么是 `oneway void` 而不是 `String invoke(...)`
     *
     * V2.0 §3.4 定案「**传输异步，业务等待同步**」：
     *
     * ```
     * ① App 侧调用方        await 结果 —— 语义上【同步】等
     *         ↓ oneway invoke（不阻塞，本方法）
     * ② hook 层 binder 线程  接单 + 登记 waiter + 投递，立即返回 —— 【不占 binder 线程】
     *         ↓ 投递到工作线程
     * ③ hook 层工作线程      真正执行 handler，跑完回 [IHookHost.resolve]
     * ④ App 侧被唤醒         拿到结果
     * ```
     *
     * 若本方法带 `String` 返回值（即非 oneway），App 侧的 binder 线程会**阻塞**
     * 到 handler 跑完 —— 而 handler 是 CPU 密集的（如全量 `ShortcutInfo`），
     * 且跑在 **system_server 的 binder 线程池**上，与系统自己的 binder 调用**共享该池**。
     * 阻塞它的后果是**整机**（§5.1 的崩溃半径表）。
     *
     * **结果不走返回值** —— 由 hook 层经 [IHookHost.resolve] 配对送回。
     *
     * ## ⚠️ 调用方（App 侧）约束
     *
     * **不得在主线程调用**（沿用 [pushConditions] 的同一条纪律），
     * 且必须配合 `HookChannelController` 的配对表 + 超时。
     *
     * ## 请求信封
     *
     * `{"request_id":…, "protocol_version":1, "capability":…, "params":{…},
     *   "timeout_ms":5000, "token":…}` —— 编解码统一走 `CapabilityInvocationCodec`。
     *
     * ⚠️ **请求方向同样要小**（§3.6 契约 5）：本方法的接收方是 **system_server**，
     * 那份 binder 缓冲**与整个系统的所有流量共享**。
     *
     * @param requestJson 请求信封 JSON。
     */
    oneway void invoke(String requestJson);
}
