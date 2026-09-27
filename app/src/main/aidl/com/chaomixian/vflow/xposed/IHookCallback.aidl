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
     */
    int ping();
}
