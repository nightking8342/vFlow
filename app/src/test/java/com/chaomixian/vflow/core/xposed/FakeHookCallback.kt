package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.xposed.IHookCallback
import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityManifest
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec

/**
 * 手写的假 `IHookCallback`（③ 调用路径的单测替身）。
 *
 * ## ⚠️ 为什么**不**用 AIDL 生成的 `IHookCallback.Stub`
 *
 * `Stub` 继承 `android.os.Binder`，而 `Binder` 的构造器会走
 * `attachInterface(...)` —— 那是 **native 实现**，在纯 JVM 单测里会抛
 * `RuntimeException: Stub!`（android.jar 的桩方法）。
 *
 * ⇒ 测试构造不出 `Stub`。本类**直接实现接口、不继承 Binder**，
 * 只覆写测试真正需要的方法；`asBinder()` 返回一个**每实例不同的**哑对象
 * （`HookChannelController.sameCallback` 会调它，而它只用于比较）。
 *
 * ⚠️ 这与 `HookChannelControllerTest` 里那条注释同源：
 * > `IHookCallback.Stub` 继承 `android.os.Binder`，纯 JVM 测试里**构造不出来**
 * —— 所以那里测「连接建立回调」只能直接调 `notifyOnConnected()`。
 *
 * ## 它记录什么 / 能配置什么
 *
 * | 能力 | 用途 |
 * |---|---|
 * | [receivedRequests] | 断言「请求真的提交了」（`invoke` 被调用过几次、内容是什么） |
 * | [autoRespond] | 收到请求后自动回一个响应（走**真实的** `CapabilityInvocationCodec` 编解码） |
 * | [capabilitiesThrows] | 模拟「旧 hook 层**没有** `capabilities()` 方法」（判据必须 `try/catch`） |
 * | [pingThrows] | 模拟「连不上」（判 UNKNOWN，不是 ABSENT） |
 */
class FakeHookCallback(
    /** `capabilities()` 是否抛异常 —— 模拟「旧 hook 层代码里没这个方法」。 */
    var capabilitiesThrows: Boolean = false,
    /** `ping()` 是否抛异常 —— 模拟「连接不可用」。 */
    var pingThrows: Boolean = false,
    /** `ping()` 的返回值。 */
    var pingResult: Int = EventEnvelopeCodec.PROTOCOL_VERSION,
    /** `capabilities()` 的返回值（清单 JSON）。默认空清单。 */
    var manifest: String = CapabilityManifest.encode(emptyList()),
) : IHookCallback {

    /** 收到的全部请求信封串（原始）。 */
    val receivedRequests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** `invoke` 被调用的次数。 */
    val invokeCount: Int get() = receivedRequests.size

    /** 解析后的请求（`request_id` / `capability` / `params`）。 */
    val receivedRequestIds: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /**
     * 收到请求后**自动回一个成功响应**（走真实的 codec + 真实的 Controller 分发）。
     *
     * ⚠️ 它让用例穿过**完整的**「提交 → hook 侧回 resolve → App 侧三段鉴权 →
     * 配对表分发 → 唤醒 waiter」链路 —— 而不是直接把结果塞给 deferred。
     * 后者测不出鉴权与配对是否接通（本仓库的教训：测试要经过调用点）。
     */
    var autoRespond: ((requestJson: String) -> Unit)? = null

    /** 记录 `ping()` 的调用次数（用于断言「没白试」）。 */
    var pingCount: Int = 0
        private set

    /** 记录 `capabilities()` 的调用次数。 */
    var capabilitiesCount: Int = 0
        private set

    override fun pushConditions(conditionsJson: String?, token: String?): Boolean = true

    override fun ping(): Int {
        pingCount++
        if (pingThrows) throw IllegalStateException("模拟：连接不可用")
        return pingResult
    }

    override fun capabilities(): String {
        capabilitiesCount++
        if (capabilitiesThrows) {
            // ⚠️ 用 `IllegalStateException` 而不是 `RemoteException`：
            // 判据侧 catch 的是 `Throwable`（binder 层真实抛的是
            // TransactionException / NoSuchMethod 一类，无法在纯 JVM 里构造）。
            // 本类只验证「**抛 ⇒ 判 ABSENT**」这条语义，不验证异常的具体类型。
            throw IllegalStateException("模拟：旧 hook 层没有 capabilities() 方法")
        }
        return manifest
    }

    override fun invoke(requestJson: String?) {
        if (requestJson == null) return
        receivedRequests.add(requestJson)
        CapabilityInvocationCodec.decodeRequest(requestJson)
            ?.let { receivedRequestIds.add(it.requestId) }
        autoRespond?.invoke(requestJson)
    }

    override fun asBinder(): android.os.IBinder = binderStub

    /**
     * 一个**仅用于身份比较**的哑 binder。
     *
     * ## ⚠️ 为什么用 `Proxy` 而不是写一个 `IBinder` 实现类
     *
     * `android.os.IBinder` 是**平台接口**，其方法集随 API 等级变化：
     * 写实现类时「多写一个方法」在编译期无害，但**少写一个**（或签名与目标 SDK 不一致）
     * 会直接在纯 JVM 单测的编译期报 `overrides nothing` ——
     * 本节第一版就是这么失败的（`dumpAsync` / `linkToDeath` 的签名与 android.jar 不符）。
     *
     * ⇒ 用动态代理：**它对接口的方法集完全免疫**（编译期不需要知道有哪些方法），
     * 而 `sameCallback` 只用 `asBinder()` 做一次 `==`，我们对那些方法做什么都无所谓。
     *
     * ⚠️ `equals` **必须**按身份返回：`HookChannelController.sameCallback` 比的是
     * `a.asBinder() == b.asBinder()`（Kotlin 的 `==` 调 `equals`），
     * 两个不同的假实例必须判「不是同一连接」，否则「旧连接迟到通知」那条路径
     * 会把新连接也清掉。
     */
    private val binderStub: android.os.IBinder by lazy {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "equals" -> args != null && args.isNotEmpty() && args[0] === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeBinder"
                "isBinderAlive" -> true
                "pingBinder" -> true
                "unlinkToDeath" -> true
                "transact" -> true
                "getInterfaceDescriptor" -> "fake"
                else -> null
            }
        }
        java.lang.reflect.Proxy.newProxyInstance(
            android.os.IBinder::class.java.classLoader,
            arrayOf(android.os.IBinder::class.java),
            handler,
        ) as android.os.IBinder
    }

    companion object {
        /** 造一个「成功响应」的编码串（供 `autoRespond` 用）。 */
        fun successResponse(requestJson: String, resultJson: String = "{}"): String? {
            val req = CapabilityInvocationCodec.decodeRequest(requestJson) ?: return null
            return CapabilityInvocationCodec.encodeResponse(
                requestId = req.requestId,
                ok = true,
                resultJson = resultJson,
                elapsedMs = 7L,
                token = req.token,
            )
        }

        /** 造一个「失败响应」的编码串。 */
        fun failureResponse(
            requestJson: String,
            code: CapabilityErrorCode = CapabilityErrorCode.HANDLER_ERROR,
            detail: String = "模拟失败",
        ): String? {
            val req = CapabilityInvocationCodec.decodeRequest(requestJson) ?: return null
            return CapabilityInvocationCodec.encodeResponse(
                requestId = req.requestId,
                ok = false,
                error = CapabilityError(code, detail),
                token = req.token,
            )
        }
    }
}
