// App 侧（权威进程，com.chaomixian.vflow）提供，由 hook 层（system_server）调用。
//
// 设计文档：docs/fork/xposed-channel-design.md §4.2.2。
package com.chaomixian.vflow.xposed;

import com.chaomixian.vflow.xposed.IHookCallback;

interface IHookHost {

    /**
     * hook 层连上来后交出它自己的回调 binder。
     *
     * **刻意不用 `oneway`**：hook 层需要同步知道「注册是否成功」——
     * 失败的原因通常是 `android:permission` 的 signature 权限没放行
     * （system_server 能否持有本 App 的 signature 权限至今未定论，见 §8-#15）。
     * 若这里也是 oneway，那个失败会被静默吞掉，hook 层只能靠心跳超时反推，
     * 排查成本高得多。
     *
     * ⚠️ 调用方（hook 层）**不得在 system_server 主线程上调它**。
     */
    boolean registerCallback(IHookCallback callback);

    /**
     * 上行事件（**统一信封 JSON**，含 `topic` / `seq` / `ts` / `payload` / `dropped` / `protocol_version`）。
     *
     * **`oneway`**：hook 层可能在 system_server 的任何线程上调用，
     * 绝不能让它阻塞等待 App 处理完。代价是 App 侧不返回结果、异常被吞 ——
     * 所以上行真伪**不靠返回值**，靠信封里的 `token` 由 App 校验。
     *
     * ⚠️ 它只在绑定期间有效：App 进程被杀后调用会静默失败，
     * hook 层靠 `onServiceDisconnected` + 心跳超时感知。
     */
    oneway void report(String envelopeJson);
}
