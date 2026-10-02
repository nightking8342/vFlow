package com.chaomixian.vflow.core.workflow.module.xposed

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.module.system.JsModuleUIProvider
import com.chaomixian.vflow.core.xposed.CapabilityInvoker
import com.chaomixian.vflow.core.xposed.XposedDiagnostics
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome
import com.chaomixian.vflow.xposed.capability.CapabilityNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「Xposed JavaScript」模块 —— 在 **system_server** 里执行 JavaScript。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §5.7（两个 JS 模块）；
 * 实现级细节：`docs/fork/xposed-executor-design.md` §5。
 *
 * ## ⚠️⚠️ 与 `vflow.system.js`（App 进程那个）的**定义性**差别
 *
 * | | `vflow.system.js` | **本模块** |
 * |---|---|---|
 * | 跑在哪 | App 进程 | **system_server**（hook 层内） |
 * | UID | 2000 | **1000** |
 * | 注入 `vflow.*` 模块树 | ✅ 是 | ❌ **否**（脚本里 `vflow.device.toast(...)` 会抛 `ReferenceError`） |
 * | 崩溃半径 | 单进程 | ⚠️ **整机** |
 *
 * 「不注入模块树」是**执行环境的定义性差别，不是省事**：
 * 本模块的定位是「借道 UID 1000 用系统级能力」，
 * 而「调 vFlow 模块」是 App 进程那个模块的活儿。
 *
 * ## ⚠️ 失败绝不静默
 *
 * `CapabilityInvoker` 的结果是**密封三态**，本模块把 `Failed` 原样转成
 * `ExecutionResult.Failure`，文案走 [XposedDiagnostics]（复用既有的九条
 * `capability_error_*` 三语文案，**不新造**）。
 *
 * ## ⚠️ 命名依据
 *
 * `xposed` 是**通道名**（与 `shizuku` / `core` 同级，表示「经哪条特权通道执行」），
 * **不是厂商名**。先例：`vflow.shizuku.shell_command` / `vflow.core.shell_command`。
 */
class XposedJsModule : BaseModule() {

    /** ⚠️ 一经发布不改（走 Xposed 通道执行 JS）。 */
    override val id = "vflow.xposed.js"

    override val metadata = ActionMetadata(
        name = "Xposed JavaScript",                                     // fallback，三语同形
        nameStringRes = R.string.module_vflow_xposed_js_name,
        description = "在系统进程中执行 JavaScript，可访问系统内部接口；脚本出错会影响整个系统。",   // fallback
        descriptionStringRes = R.string.module_vflow_xposed_js_desc,
        iconRes = R.drawable.rounded_xposed_js_24,
        category = "Xposed",
        categoryId = ModuleCategories.XPOSED,
    )

    /**
     * ⚠️⚠️ **只给 `TEMPORARY_WORKFLOW`，绝不给 `DIRECT_TOOL`**（V2.0 §5.7 安全边界）。
     *
     * 理由：`DIRECT_TOOL` 意味着 AI 可以**未经人审**直接调用它去 system_server 里跑脚本。
     * 崩溃半径是整机 ⇒ 必须走「生成工作流 → 用户看得见 → 再执行」这条路。
     * `riskLevel = HIGH` 是配套的第二道：它会走审批流程。
     */
    override val aiMetadata = temporaryWorkflowOnlyMetadata(
        riskLevel = AiModuleRiskLevel.HIGH,
        workflowStepDescription = "在系统进程（system_server）内执行一小段 JavaScript。" +
            "脚本能访问系统内部接口，但没有 vFlow 模块树（不能调用 vflow.* —— 需要调模块请用 " +
            "vflow.system.js）。脚本执行出错可能影响整个系统，且不可撤销。",
        inputHints = mapOf(
            // ⚠️ 这份 hints 是**给 LLM 读的**（它随 query_module_schema 一起进上下文），
            // 比 `defaultValue` 更权威 —— Agent 通常先读 hints、再去看 [default: …]。
            // 所以「怎么写」这件事写在这里，`defaultValue` 只留「给人看的最小可用示例」。
            "script" to buildString {
                append("要执行的 JavaScript 源码。")
                // ── 这个模块**是什么**（Agent 判断「该不该用它」的依据）──
                append("运行在系统进程（system_server）内，UID 1000 —— ")
                append("能拿到 shell（UID 2000）拿不到的签名级权限、也能同进程访问系统内部对象。")
                append("判据：需要 UID 1000 专属权限、或要读 system_server 内部对象时才用它；")
                append("只是「构造对象参数 / 读返回值」的话，vflow.core.* 更省且崩溃半径只有单进程。")
                // ── 环境：没有模块树（与 vflow.system.js 的分工）──
                append("⚠️ 环境里【没有】vflow.* 模块树，`vflow.device.toast(...)` 之类一律 ReferenceError；")
                append("要用 vFlow 模块请改用 vflow.system.js。")
                // ── 环境：有什么（Agent 推不出来的）──
                append("可用：Java 互操作（`importClass` / `Packages` / `JavaAdapter` / `getClass`）、")
                append("`console.log(...)`（调试输出的唯一手段，会进系统日志）、")
                append("全局 `inputs`（见 inputs 字段）。标准内建（String/Array/JSON/Math/Date/Map/Set/Promise/RegExp）齐全。")
                // ── ⚠️⚠️ 三条写法契约（踩过坑的，不写 Agent 必错）──
                append("⚠️ 返回值必须用【末行表达式】，**不能用顶层 `return`** —— ")
                append("Rhino 把顶层代码当表达式求值，顶层 return 直接报「返回的值无效」（解析期错误，脚本一行都不执行）。")
                append("例：`var r = {}; r.sum = 1 + 1; r;`（`return` 只在函数体内合法）。")
                append("返回值是字典时，它整体成为 outputs；返回非对象则包成 `{result: …}`。")
                // ── ⚠️ 已知限制 ──
                append("⚠️ 超时会中断纯计算死循环（`while(true){}`），但**阻塞的 Java 调用不可中断** —— ")
                append("`Thread.sleep(...)` / 卡住的 IO 会把工作线程占满整个阻塞时长，而池只有 2 个线程。")
                append("⚠️ 脚本出错可能影响整个系统，且不可撤销。")
            },
            "inputs" to "可选字典，作为脚本的输入。每个键会以**同名全局变量**注入脚本（如 `inputs.my_var`），" +
                "值支持 {{变量}} 魔法变量引用。",
            "timeout_ms" to "脚本执行上限（毫秒）。默认 5000、上限 30000，越界自动钳位。" +
                "⚠️ 配得过长会占住通道：它跑在**容量只有 2** 的共享工作线程池上，" +
                "两个被占满的调用会让后续调用直接失败。",
        ),
        requiredInputIds = setOf("script"),
    )

    /**
     * ⚠️ 复用 `vflow.system.js` 的 UIProvider（**零改动上游文件**）。
     *
     * 两者的 `script` / `inputs` 语义**本就设计为一致**（需求原话），
     * 故复用是有意的耦合，不是意外。
     *
     * `timeout_ms` 不在它的 `getHandledInputIds()` 里 ⇒ 由**自动表单**渲染
     * （`ActionEditorUiModel` 按 handledInputIds 过滤）。
     * 且 `ActionEditorSheet` 的写入是 `currentParameters.putAll(readFromEditor(...))`，
     * 而 `readFromEditor` 只返回 `script` / `inputs` ⇒ `timeout_ms` **原样保留、不被覆盖**。
     */
    override val uiProvider: ModuleUIProvider? = JsModuleUIProvider()

    /**
     * ⚠️⚠️ 必须声明 —— 漏了会让权限体系判为「缺权限」。
     *
     * 与 `ActivityChangedTriggerModule` 同一条纪律：本仓库在 `SimDataSwitch` 上踩过
     * 「不声明权限 = 配上就静默失效」的坑（权限齐全的设备上**测不出来**，属最坏的一类 bug）。
     */
    override fun getRequiredPermissions(step: ActionStep?): List<Permission> =
        listOf(PermissionManager.XPOSED_HOOK)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "script",
            name = "JavaScript 脚本",
            staticType = ParameterType.STRING,
            // ⚠️ 示例脚本**必须**体现「没有 vflow.*」—— 照抄 JsModule 的默认脚本
            //（它调 `vflow.device.toast`）会让用户以为这里也能调模块，
            // 而本模块里那是 ReferenceError。
            defaultValue = """
                // 这段脚本跑在系统进程（system_server）里，UID 1000。
                // ⚠️ 这里【没有】vflow 模块树 —— vflow.* 一律不可用。
                //    需要调用 vFlow 模块请改用「JavaScript 脚本」模块。

                // inputs 以全局变量形式注入：
                // var myVar = inputs.my_variable

                // 返回一个字典作为下游可见的 outputs —— 用【末行表达式】，
                // ⚠️ 不要用顶层 `return`：Rhino 把**顶层代码当表达式求值**，
                //    顶层 `return` 直接报「返回的值无效」（实测，行列号指不到真正原因）。
                //    `return` 本身没被禁，**只在函数体内合法**：`function f(){ return {...}; } f();`
                //    是可行的。
                var r = {};
                r.sum = 1 + 1;
                r;
            """.trimIndent(),
            acceptsMagicVariable = true,
            acceptsInlineScript = false,
            nameStringRes = R.string.param_vflow_xposed_js_script_name,
        ),
        InputDefinition(
            id = "inputs",
            name = "脚本输入",
            staticType = ParameterType.ANY,
            defaultValue = emptyMap<String, Any>(),
            acceptsMagicVariable = true,
            nameStringRes = R.string.param_vflow_xposed_js_inputs_name,
        ),
        InputDefinition(
            id = "timeout_ms",
            // ⚠️⚠️ 「（默认 5000，上限 30000）」**必须写在标签里，不能只写进 hint**。
            // `hint` 在自动表单里只是**输入框占位符**（`StandardControlFactory` →
            // `createTextInputLayout(hint = …)`），而字段**预填了 5000** ⇒ 占位符
            // **永远不显示** ⇒ 用户既看不到上限、也可能以为它必填。
            // （实测路径：该字段不在 `JsModuleUIProvider.getHandledInputIds()` 里
            //  ⇒ 由自动表单渲染；`ActionEditorSheet.createViewForInputDefinition`
            //  → `StandardControlFactory` → `createTextInputLayout`。）
            // ⚠️ 位置参 `name` 只是**未本地化时的 fallback** —— 真正渲染的是
            // `nameStringRes` 指向的三语文案，两处必须同步改（否则某语言下退回旧标签）。
            name = "超时（毫秒，默认 5000，上限 30000）",
            staticType = ParameterType.NUMBER,
            defaultValue = DEFAULT_TIMEOUT_MS,
            acceptsMagicVariable = true,
            nameStringRes = R.string.param_vflow_xposed_js_timeout_name,
            hintStringRes = R.string.param_vflow_xposed_js_timeout_hint,
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "outputs",
            name = "脚本返回值",                                              // fallback
            typeName = VTypeRegistry.DICTIONARY.id,
            // ⚠️ 第二位置参是 `name: String`（**字面量**），不是资源 ID ——
            // 本地化必须走具名参数 `nameStringRes`。把资源名塞进位置参会得到
            // 一个字面量 `output_vflow_xposed_js_outputs_name`。
            nameStringRes = R.string.output_vflow_xposed_js_outputs_name,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val script = step.parameters["script"] as? String ?: "..."
        val firstLine = script.trim().lines()
            .firstOrNull { it.isNotBlank() && !it.trim().startsWith("//") }
            ?: context.getString(R.string.summary_empty_script)

        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_xposed_js_prefix),
            PillUtil.Pill(firstLine, "script"),
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val appContext = context.applicationContext

        // ── 取参（⚠️ 走 context.getVariable*，不读 step.parameters —— 前者处理魔法变量展开）──
        val script = context.getVariableAsString("script", "")
        if (script.isBlank()) {
            return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_xposed_js_script_empty),
                appContext.getString(R.string.error_vflow_xposed_js_script_empty_desc),
            )
        }

        val rawTimeout = context.getVariableAsNumber("timeout_ms")?.toLong()
        val timeoutMs = clampTimeoutMs(rawTimeout)
        if (rawTimeout != null && rawTimeout != timeoutMs) {
            // ⚠️ 钳位发生时**必须留痕**：否则用户看到的是「脚本超时」，
            // 而真相是他配的数值被钳了 —— 两者的排查方向完全不同。
            DebugLogger.w(TAG, "timeout_ms=$rawTimeout 越界，已钳位为 $timeoutMs")
        }

        val scriptInputs = scriptInputsOf(
            entries = rawInputEntries(context),
            hasReference = { VariableResolver.hasVariableReference(it) },
            resolve = { VariableResolver.resolveValue(it, context) },
        )

        return try {
            onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_xposed_js_executing)))

            // ⚠️⚠️ 必须切到 IO —— `CapabilityInvoker.invoke` 的 KDoc 写死
            // 「不得在主线程调用」（它最终是一次同步 binder 事务到 system_server）。
            // 虽然 WorkflowExecutor 通常不在主线程，但那是**调用方的实现细节**；
            // 本模块自己保证，不让它依赖上层将来怎么调度。
            val outcome = withContext(Dispatchers.IO) {
                CapabilityInvoker.invokeOrFallback(
                    capability = CapabilityNames.XPOSED_JS,
                    // ⚠️ 交给 buildRequestJson 走 JSONObject(params).toString() 序列化。
                    // Kotlin Map.toString() 产出的是**非法 JSON**，绝不能自己拼串。
                    params = mapOf("script" to script, "inputs" to scriptInputs),
                    timeoutMs = timeoutMs,
                )
            }

            when (outcome) {
                is CapabilityInvokeOutcome.Success -> successOf(outcome.result)
                is CapabilityInvokeOutcome.Degraded -> {
                    // ⚠️ 本 capability 是**独占型**（`fallback = null`）⇒ 理论不可达。
                    // 但仍要处理（穷尽 when），且处置原则明确：`Degraded` 的契约是
                    // 「同样的入参、同形状的结果、**更差的实现**」⇒ **有结果就不能丢**。
                    // 取同样的值 + 留痕（绝不静默），比把它降级成失败更符合契约。
                    DebugLogger.w(
                        TAG,
                        "xposed_js 走了降级路径（${outcome.reason.code.wire}）—— " +
                            "⚠️ 本能力声明为独占型，正常情况不该出现，请报告问题",
                    )
                    successOf(outcome.result)
                }

                is CapabilityInvokeOutcome.Failed -> {
                    // ⚠️⚠️ 失败**绝不静默**。文案复用既有的三层：
                    // `messageFor` 只吃 code（detail 只给人看、绝不参与判断），
                    // detail 只能经 `formatBodyWithDetail` 拼上去。
                    val failure = outcome.failure
                    val msg = XposedDiagnostics.messageFor(failure.code)
                    DebugLogger.w(
                        TAG,
                        "xposed_js 失败：code=${failure.code.wire} detail=${failure.detail}",
                    )
                    ExecutionResult.Failure(
                        appContext.getString(msg.titleRes),
                        XposedDiagnostics.formatBodyWithDetail(appContext, msg, failure.detail),
                    )
                }
            }
        } catch (e: CancellationException) {
            // ⚠️ 必须重抛 —— 它是协程的正常生命周期事件，不是失败
            // （照 WorkflowExecutor 的既有纪律）。
            throw e
        } catch (e: Exception) {
            // `CapabilityInvoker.invoke` 自己的契约是「不抛」，所以这里兜的是
            // 取参与 `VObjectFactory.from` 路径上的意外。
            DebugLogger.e(TAG, "xposed_js 执行异常：${e.javaClass.simpleName} ${e.message}")
            ExecutionResult.Failure(
                appContext.getString(R.string.capability_error_title_report_problem),
                e.localizedMessage ?: e.javaClass.simpleName,
            )
        }
    }

    /** result → `outputs` 字典。抽出来是为了让两条分支（Success / Degraded）**取同一份值**。 */
    private fun successOf(result: Map<String, Any?>): ExecutionResult =
        ExecutionResult.Success(
            mapOf(
                "outputs" to VDictionary(
                    rawOutputsOf(result).mapValues { VObjectFactory.from(it.value) },
                ),
            ),
        )

    /**
     * `inputs` 参数 → 键值文本表。
     *
     * ⚠️ 先 `asString()` 再判引用：`hasVariableReference` 收的是文本，
     * 而 `resolveValue` 返回的可能是 `Double` / `Map` 等原始值（保留类型）。
     */
    private fun rawInputEntries(context: ExecutionContext): Map<String, String> =
        when (val inputsObj = context.getVariable("inputs")) {
            is VDictionary -> inputsObj.raw.mapValues { it.value.asString() }
            else -> emptyMap()
        }

    private companion object {
        const val TAG = "XposedJsModule"
    }
}
