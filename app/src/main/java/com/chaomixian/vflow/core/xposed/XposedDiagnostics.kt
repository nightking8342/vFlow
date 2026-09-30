package com.chaomixian.vflow.core.xposed

import android.content.Context
import androidx.annotation.StringRes
import com.chaomixian.vflow.R
import com.chaomixian.vflow.xposed.wire.CapabilityErrorAction
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.userAction

/**
 * 一条失败对应的用户可见文案（**只给资源 ID**，交给调用方拿 `Context` 去取）。
 *
 * ⚠️ 刻意**不持 `Context`、不持字符串**：文案层因此是纯函数，
 * 可纯 JVM 单测（本仓库的既有先例：`LogMessageFormatterTest` 用资源 ID 做断言）。
 */
data class CapabilityErrorMessage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
)

/**
 * Xposed 通道的**文案映射层** —— 把状态与错误码翻译成用户可读的资源 ID。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.1（可见性义务）与 §6.4（失败分类表）。
 *
 * ## 本类与 [XposedReadiness] 的分工
 *
 * [XposedReadiness] 只回答「**要不要提示**」；本类只回答「**提示什么**」。
 * 分开的理由是它们各自可被独立单测，且「要不要」是纯逻辑、「说什么」是资源映射 ——
 * 混在一个函数里会让「文案漏了三语」这类问题淹没在逻辑分支里。
 *
 * ## ⚠️⚠️ 为什么不在这里放「首屏能力清单」之类的东西
 *
 * v2.0 §6.3 明确建议**先不做**（能力会越来越多，30 个时列不下）。
 *
 * ## ⚠️⚠️ 生产消费者现状（如实记录 —— 这里**曾经写错过**，改正后留痕）
 *
 * - [channelNoticeRes]：生产消费者是 `TriggerService`（「通道未就绪」的前台通知正文）。
 * - [messageFor]：**当前在生产代码里没有任何调用点**。
 *
 * ### ⚠️ 关于 [messageFor] 的一个必须知道的事实
 *
 * 我第一版在这里写的是「唯一有生产消费者的是 `CHANNEL_DOWN` 那格（`failAllWaiters`
 * 生成该码）」—— **那是错的**：`failAllWaiters` 只是把该码**放进** `CapabilityResponse`，
 * 它并不调用本类。而 ③ 的 App 侧调用运行时（`CapabilityInvoker` / `CapabilityInvokeOutcome`）
 * **在本分支上并不存在**（它属于 T1，尚未合入）。
 *
 * ⇒ 后果已在 `assembleRelease` 的产物上**实测确认**：
 * `aapt2 dump resources` 里**只有 `trigger_xposed_notice_*` 两条**，
 * 九条 `capability_error_*` **全部被 R8 + `shrinkResources` 剥掉了**
 * （类与方法连同其引用的资源一起消失）。
 *
 * ⚠️ **这不是缺陷、不需要修** —— 死代码消除是正确行为，接入首个 capability 后
 * 资源会**自动回来**（`messageFor` 一旦被调用，R8 就保留）。
 * 但**必须知道**它，否则会陷入两种误判：
 *  ① 在真机上找这几条文案，找不到，以为映射层写错了；
 *  ② 以为「写进 `strings.xml` 就等于会出现在 release 包里」。
 *
 * ⚠️ **不要**为它加一条「`messageFor` 必须有生产调用点」的测试 —— 那在接入首个
 * capability 之前**恒红**，而本仓库的既有教训是「恒红的断言会被下个实现者删掉」。
 *
 * ⚠️ 也不要因为「暂时没被调用」就删掉这五格：§6.4 要求**覆盖全部五个枚举值**
 *（将来加第六个码时 `bodyResOf` 的穷尽 `when` 要在编译期就红）。
 */
object XposedDiagnostics {

    /**
     * 错误码 → 用户可见文案（§6.4 那张表的**文案面**）。
     *
     * ## ⚠️⚠️ 标题按 [userAction] 派生，正文按 `code` 逐值给
     *
     * 这样「多个码指向同一个处置」这件事**在代码结构上就成立** ——
     * 不是靠两条 `when` 恰好写得一致。将来有人只改了正文、忘了标题，
     * 也不会让两个同处置的码显示成不同的标题。
     *
     * ## ⚠️ 本方法**不接受** `detail`
     *
     * `detail` 是自由文本、会被三语本地化（§6.4 约束 2）。
     * 调用方要显示它，请**另外**拼接（见 [formatBodyWithDetail]），
     * 绝不要拿它做分支 —— `if (detail.contains("…"))` 等于埋一个「切语言就坏」的雷。
     */
    fun messageFor(code: CapabilityErrorCode): CapabilityErrorMessage = CapabilityErrorMessage(
        titleRes = titleResOf(code.userAction()),
        bodyRes = bodyResOf(code),
    )

    /**
     * [CapabilityErrorAction] → 标题资源。
     *
     * 穷尽 `when`：加第六个处置时**编译期**就红。
     */
    @StringRes
    private fun titleResOf(action: CapabilityErrorAction): Int = when (action) {
        CapabilityErrorAction.UPGRADE_APP -> R.string.capability_error_title_upgrade_app
        CapabilityErrorAction.REPORT_PROBLEM -> R.string.capability_error_title_report_problem
        CapabilityErrorAction.CHECK_CAPABILITY -> R.string.capability_error_title_check_capability
        CapabilityErrorAction.CHECK_LSPOSED -> R.string.capability_error_title_check_lsposed
    }

    /**
     * [CapabilityErrorCode] → 正文资源。
     *
     * 穷尽 `when` ⇒ 加第六个码时**编译期**就红（`XposedDiagnosticsTest` 还有一条
     * 测试期的「码仍是五个」断言，两道锁）。
     *
     * ⚠️ 五个正文的**指向**必须与 §6.4 一致，尤其这三条：
     *  - `CAPABILITY_ABSENT` 指向 **App 侧**（更新/重启 App），**不是**去改 LSPosed 配置；
     *  - `CHANNEL_DOWN` 才是指向 LSPosed / 等重连的那条；
     *  - `PAYLOAD_TOO_LARGE` 与 `TIMEOUT` **同类**（都指向「报告问题」）——
     *    它是实现缺陷（正常路径下 hook 侧会主动截断），不是配置问题。
     */
    @StringRes
    private fun bodyResOf(code: CapabilityErrorCode): Int = when (code) {
        CapabilityErrorCode.CAPABILITY_ABSENT -> R.string.capability_error_body_absent
        CapabilityErrorCode.TIMEOUT -> R.string.capability_error_body_timeout
        CapabilityErrorCode.HANDLER_ERROR -> R.string.capability_error_body_handler_error
        CapabilityErrorCode.CHANNEL_DOWN -> R.string.capability_error_body_channel_down
        CapabilityErrorCode.PAYLOAD_TOO_LARGE -> R.string.capability_error_body_payload_too_large
    }

    /**
     * 把自由文本 `detail` **附加**到正文后面（只为人看）。
     *
     * ⚠️ 这是本批改动里**唯一**允许碰 `detail` 的地方 —— 且只是拼接，不做任何判断。
     *
     * @param detail 来自 [com.chaomixian.vflow.xposed.wire.CapabilityError.detail]；
     *   空白时**不加**分隔符（否则正文尾巴上会挂一对空行）。
     */
    fun formatBodyWithDetail(context: Context, message: CapabilityErrorMessage, detail: String): String {
        val body = context.getString(message.bodyRes)
        val trimmed = detail.trim()
        return if (trimmed.isEmpty()) body else "$body\n\n$trimmed"
    }

    /**
     * `TriggerService` 的前台通知正文 —— 「通道未就绪」时用哪一条。
     *
     * ## ⚠️ 分支来源是 [XposedState.tapAction]（**复用，不另造一套**）
     *
     * | 分支 | 场合 | 该说什么 |
     * |---|---|---|
     * | `RECONNECT_HINT` | `ACTIVE + DISCONNECTED` | **等自动重连**（框架是好的，去改配置没用） |
     * | `GUIDE` | 其余（未启用 / 框架断开 / 未挂载） | 去检查配置或重启设备 |
     *
     * ⚠️ `RECONNECT_HINT` 那一格**绝不能**写成「去检查 LSPosed」——
     * 那是 P4 踩过的坑（`TriggerService` 加载比 hook 连接早约 1.6 秒，
     * 旧文案却让用户「检查 LSPosed 配置」，而他的配置完全正确）。
     *
     * ⚠️ 判据**不是** `XposedState.needsGuidance` —— 它对 `ACTIVE + DISCONNECTED`
     * 返回 `false`，那是个**布尔**而不是「该弹什么」，用它当分支还得再补一次分类
     * （`XposedState.tapAction` 的 KDoc 记着这个坑：一切正常时点卡片会弹「通道正在重连」）。
     */
    @StringRes
    fun channelNoticeRes(result: XposedState.Result): Int = when (XposedState.tapAction(result)) {
        XposedState.TapAction.RECONNECT_HINT -> R.string.trigger_xposed_notice_reconnect
        XposedState.TapAction.GUIDE -> R.string.trigger_xposed_notice_config
    }
}
