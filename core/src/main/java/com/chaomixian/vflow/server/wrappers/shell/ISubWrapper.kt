// 文件: server/src/main/java/com/chaomixian/vflow/server/wrappers/shell/ISubWrapper.kt
// 描述: 订阅管理器 Wrapper —— 只提供「设置默认上网卡（DDS）」这一件必须特权的事。
//
// 设计文档：docs/fork/sim-data-switch-design.md
//
// ## 为什么这里只有 set，没有 get
//
// 「读」不需要特权：卡槽 ↔ subId 的映射、当前默认数据卡，App 侧用公开 API
// `SubscriptionManager` 就能拿到（实测见设计文档 §9.1），比在这里反射 ISub 更可靠。
// 本 wrapper 只承载**唯一必须特权**的操作：`setDefaultDataSubId`。
//
// ⚠️ **教训（曾在这里踩坑）**：初版还实现了 `getActiveSubInfoList` 来做卡槽映射，
// 但反射读方法不稳定 —— 新版 `ISub` 的读方法普遍带 `callingPackage`/`callingFeatureId`
// 参数，而 `ReflectionUtils.findMethodLoose` **只按名字匹配**，匹配到之后
// `invoke(serviceInterface)` 参数不够就抛异常。表现为 App 侧只收到一句含糊的
// 「no subscription in slot N」，真实原因被 catch 吞掉。
// **结论：能用公开 API 就别反射猜 AIDL 签名。**
//
// ## 为什么按方法名反射，而不是 `service call` + 事务码
//
// 事务码是 AIDL 编译产物的方法顺序，逐机型/逐版本漂移，且**调错码会改到相邻的设置项**。
// 反射走 `ISub` 接口，对服务端实现类改名（Android 14 起由 `SubscriptionController`
// 换成 `SubscriptionManagerService`）透明。
package com.chaomixian.vflow.server.wrappers.shell

import com.chaomixian.vflow.server.common.utils.ReflectionUtils
import com.chaomixian.vflow.server.wrappers.ServiceWrapper
import org.json.JSONObject
import java.lang.reflect.Method

class ISubWrapper : ServiceWrapper("isub", "com.android.internal.telephony.ISub\$Stub") {

    private var setDefaultDataSubIdMethod: Method? = null

    override fun onServiceConnected(service: Any) {
        val clazz = service.javaClass
        setDefaultDataSubIdMethod = ReflectionUtils.findMethodLoose(clazz, "setDefaultDataSubId")

        println("=== ISub Methods ===")
        println("setDefaultDataSubId: ${setDefaultDataSubIdMethod != null}")
        // 把参数表打出来 —— 这类反射失败几乎都源于参数不匹配，且异常会被吞掉
        setDefaultDataSubIdMethod?.let {
            println("  signature: ${it.parameterTypes.toList()}")
        }
    }

    override fun handle(method: String, params: JSONObject): JSONObject {
        val result = JSONObject()

        if (!isAvailable) {
            result.put("success", false)
            result.put("error", "ISub service is not available or no permission")
            return result
        }

        when (method) {
            "setDefaultDataSubId" -> {
                val target = params.optInt("subId", -1)
                if (target <= 0) {
                    result.put("success", false)
                    result.put("error", "invalid subId: $target")
                } else {
                    val outcome = setDefaultDataSubId(target)
                    result.put("success", outcome.ok)
                    // 失败时把**真实原因**回传，而不是只给一个 false ——
                    // 这正是初版踩坑的地方：原因被吞在 Core 的 stdout 里，App 侧看不到
                    outcome.error?.let { result.put("error", it) }
                }
            }

            else -> {
                result.put("success", false)
                result.put("error", "Unknown method: $method")
            }
        }
        return result
    }

    private data class SetOutcome(val ok: Boolean, val error: String? = null)

    private fun setDefaultDataSubId(subId: Int): SetOutcome {
        val m = setDefaultDataSubIdMethod
            ?: return SetOutcome(false, "setDefaultDataSubId not found on ISub")

        // 参数表不符时别硬调 —— 直接给出可诊断的信息
        val params = m.parameterTypes
        if (params.size != 1 || params[0] != Int::class.javaPrimitiveType) {
            return SetOutcome(
                false,
                "setDefaultDataSubId signature mismatch: ${params.toList()} (expected [int])",
            )
        }

        return try {
            m.invoke(serviceInterface, subId)
            SetOutcome(true)
        } catch (e: Exception) {
            // 权限不足 / subId 无效 / 设备不支持多卡都会落到这里。
            // 剥掉 InvocationTargetException 才能看到真正的 SecurityException。
            val cause = e.cause ?: e
            SetOutcome(false, "${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
}
