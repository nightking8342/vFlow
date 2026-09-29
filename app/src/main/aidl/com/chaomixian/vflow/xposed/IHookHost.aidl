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

    /**
     * ③ 的**配对响应**：hook 层执行完 capability 后，把结果送回 App 侧。
     *
     * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.3 / §3.4。
     *
     * ## ⚠️ 它**不是**新增的「方向」，而是既有方向的一次新用途
     *
     * `IHookHost` 是 **App 提供、hook 层调用**的 —— 这个方向本来就存在
     *（[report] 走的就是它），所以**不新增 AIDL 接口**，只加一个方法。
     *
     * ## ⚠️ 为什么必须 `oneway`
     *
     * 与 [report] 同理：hook 层可能在 system_server 的**任何线程**上调它
     *（工作线程池里的 handler 跑完后回包），绝不能让它阻塞等待 App 处理完。
     *
     * ⚠️ 代价与 [report] 相同：**超限时静默丢弃**。
     * 这正是 §3.6 要求「主动截断 + 声明上限」而不依赖 binder 报错的原因 ——
     * oneway 下 binder 报错**回不来**。
     *
     * ## ⚠️⚠️ 必须鉴权（与 [report] 同规格，不是可选）
     *
     * `report` 靠信封里的 `token` 校验真伪，而本方法走的是**同一个 App 侧 binder**。
     * 若它不带凭证，**任何能 bind 到 `HookChannelService` 的进程都能伪造响应**，
     * 把任意 `result` 塞给正在等待的调用方 —— 这比伪造事件更危险：
     * 事件还要过 App 侧 filter 才触发，而本方法的 `result` **直接就是 capability 的返回值**
     *（将来 `query_shortcut_intents` 的结果会被写进工作流）。
     *
     * ⇒ **不能靠 `android:permission="HOOK_CONTROL"` 兜底** ——
     * 它是 `signature` 级，只挡**绑定**、不挡**绑定之后的方法调用**。
     * 所以响应信封**也带 `token`**，App 侧照 `onReport` 的三段校验。
     *
     * ## 响应信封
     *
     * `{"request_id":…, "ok":true, "result":{…}, "error":null|{code,detail},
     *   "elapsed_ms":12, "token":…}` —— 编解码统一走 `CapabilityInvocationCodec`。
     *
     * ⚠️ **无配对 / 迟到的响应必须被丢弃并告警**（§5.2），不能静默接受 ——
     * 否则上面那条鉴权就有了绕过面。
     *
     * @param responseJson 响应信封 JSON。
     */
    oneway void resolve(String responseJson);
}
