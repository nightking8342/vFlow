// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AutoGLMModule.kt
package com.chaomixian.vflow.core.workflow.module.interaction
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.*

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.utils.VirtualDisplayManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.ai.AiModelConfigResolution
import com.chaomixian.vflow.core.ai.AiModelConfigResolver
import com.chaomixian.vflow.core.ai.ManualAiConfig
import com.chaomixian.vflow.ui.chat.ChatAgentSkillSelection
import com.chaomixian.vflow.ui.chat.ChatCompletionClient
import com.chaomixian.vflow.ui.chat.ChatCompletionResult
import com.chaomixian.vflow.ui.chat.ChatImageAttachment
import com.chaomixian.vflow.ui.chat.ChatImageBudget
import com.chaomixian.vflow.ui.chat.ChatImageSource
import com.chaomixian.vflow.ui.chat.ChatMessage
import com.chaomixian.vflow.ui.chat.ChatMessageRole
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatPresetRepository
import com.chaomixian.vflow.ui.chat.ChatStreamTimeouts
import com.chaomixian.vflow.ui.chat.chatImageMimeTypeForUri
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import com.chaomixian.vflow.ui.overlay.AgentOverlayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** AutoGLM 的采样温度（**上游模型契约的一部分**，不要改）。 */
internal const val AUTO_GLM_TEMPERATURE: Double = 0.1

/**
 * AutoGLM 思考段的**包裹标签**（与提示词里的 `{think}` 契约一致）。
 *
 * 单独成常量：`autoGlmAssistantHistoryContent` 要用它**重建** assistant 轮的历史正文
 * （传输层已把内联 think 段搬进 `reasoningContent`）。
 */
internal const val AUTO_GLM_THINK_OPEN: String = "<" + "think" + ">"
internal const val AUTO_GLM_THINK_CLOSE: String = "<" + "/think" + ">"


/**
 * **清掉上一条 user 消息里的图 + 追加 `[Image removed]`**（现状的图片清理意图，搬到
 * [ChatMessage] 模型上）。
 *
 * ⚠️ 与改造前**逐字等价**：原实现无条件把最后一条 user 消息 `content` 数组里的
 * `image_url` 项删掉、再 push 一个 `{"type":"text","text":"[Image removed]"}`。
 * 这里同样**无条件**追加（只要最后一条是 user 消息）。
 */
internal fun stripImagesFromLastUserMessage(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val lastIndex = messages.lastIndex
    val last = messages[lastIndex]
    if (last.role != ChatMessageRole.USER) return messages
    return messages.toMutableList().apply {
        this[lastIndex] = last.copy(
            attachments = emptyList(),
            content = listOf(last.content, "[Image removed]").joinToString("\n"),
        )
    }
}

/**
 * assistant 轮进历史的正文。
 *
 * 现状把**原始** assistant 消息原样入历史（含内联 ` thinking` 段）；而传输层会把内联
 * ` thinking` 搬进 [ChatCompletionResult.reasoningContent]（`normalizeAssistantReply`）。
 * ⇒ 这里按 ` thinking… response` 前缀**重建**，让模型下一轮仍能看到自己的上一轮思考。
 */
internal fun autoGlmAssistantHistoryContent(result: ChatCompletionResult): String {
    val reasoning = result.reasoningContent
    return if (reasoning.isNullOrBlank()) {
        result.content
    } else {
        AUTO_GLM_THINK_OPEN + reasoning + AUTO_GLM_THINK_CLOSE + "\n" + result.content
    }
}

/**
 * **一轮 AutoGLM 往返**（共享传输层）。
 *
 * ⚠️ **不发送 tools** —— AutoGLM 的动作走 `do(action=…)` **文本协议**，不走 function calling
 * （上游模型契约，本批零改动）。
 */
internal suspend fun runAutoGlmTurn(
    preset: ChatPresetConfig,
    history: List<ChatMessage>,
    client: ChatCompletionClient,
): ChatCompletionResult = client.generateReply(
    preset = preset,
    history = history,
    skillSelection = ChatAgentSkillSelection.EMPTY,
    tools = null,
    imageBudget = ChatImageBudget.DEFAULT,
)

class AutoGLMModule : BaseModule() {
    companion object {
        private const val PROVIDER_BIGMODEL = "bigmodel"
        private const val PROVIDER_CUSTOM = "custom"
        private const val DISPLAY_MODE_MAIN = "main_screen"
        private const val DISPLAY_MODE_VIRTUAL = "virtual_screen"
    }

    override val id = "vflow.ai.autoglm"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_ai_autoglm_name,
        descriptionStringRes = R.string.module_vflow_ai_autoglm_desc,
        name = "AutoGLM 智能体",
        description = "复刻 AutoGLM 项目。基于思维链(CoT)和自定义指令协议，执行复杂的手机操作任务。",
        iconRes = R.drawable.rounded_hexagon_nodes_24,
        category = "界面交互",
        categoryId = "interaction"
    )
    override val aiMetadata = AiModuleMetadata(
        allowSavedWorkflow = false,
    )

    override val uiProvider: ModuleUIProvider = AutoGLMModuleUIProvider()

    override fun getRequiredPermissions(step: ActionStep?): List<Permission> {
        return listOf(
            PermissionManager.ACCESSIBILITY,
            PermissionManager.STORAGE,
            PermissionManager.USAGE_STATS,
            PermissionManager.OVERLAY
        ) + ShellManager.getRequiredPermissions(LogManager.applicationContext)
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "provider",
            "服务商",
            ParameterType.ENUM,
            PROVIDER_BIGMODEL,
            options = listOf(PROVIDER_BIGMODEL, PROVIDER_CUSTOM),
            nameStringRes = R.string.param_vflow_ai_agent_provider_name,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_agent_provider_zhipu,
                R.string.option_vflow_ai_completion_provider_custom
            ),
            legacyValueMap = mapOf(
                "智谱" to PROVIDER_BIGMODEL,
                "Zhipu" to PROVIDER_BIGMODEL,
                "自定义" to PROVIDER_CUSTOM,
                "Custom" to PROVIDER_CUSTOM
            )
        ),
        InputDefinition("base_url", "Base URL", ParameterType.STRING, "https://open.bigmodel.cn/api/paas/v4", nameStringRes = R.string.param_vflow_ai_agent_base_url_name),
        InputDefinition("api_key", "API Key", ParameterType.STRING, "", nameStringRes = R.string.param_vflow_ai_agent_api_key_name),
        InputDefinition("model", "模型", ParameterType.STRING, "autoglm-phone", nameStringRes = R.string.param_vflow_ai_agent_model_name),
        InputDefinition("instruction", "指令", ParameterType.STRING, "", acceptsMagicVariable = true, supportsRichText = true, nameStringRes = R.string.agent_instruction_label),
        InputDefinition("max_steps", "最大步数", ParameterType.NUMBER, 30.0, nameStringRes = R.string.agent_max_steps_label),
        InputDefinition(
            "display_mode",
            "执行环境",
            ParameterType.ENUM,
            DISPLAY_MODE_MAIN,
            options = listOf(DISPLAY_MODE_MAIN, DISPLAY_MODE_VIRTUAL),
            nameStringRes = R.string.param_vflow_ai_agent_display_mode_name,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_agent_display_mode_main,
                R.string.option_vflow_ai_agent_display_mode_virtual
            ),
            legacyValueMap = mapOf(
                "主屏幕" to DISPLAY_MODE_MAIN,
                "Main Screen" to DISPLAY_MODE_MAIN,
                "虚拟屏幕 (后台)" to DISPLAY_MODE_VIRTUAL,
                "Virtual Screen (Background)" to DISPLAY_MODE_VIRTUAL
            ),
            // fork：同 AgentModule —— 允许折叠触发器驱动「主屏 / 虚拟屏」分流。
            acceptsMagicVariable = true,
            isHidden = false
        ),
        // fork：运行界面 —— 同 AgentModule（悬浮窗 / 监视器页面）。
        InputDefinition(
            "monitor_mode",
            "运行界面",
            ParameterType.ENUM,
            AgentOverlayManager.MONITOR_MODE_OVERLAY,
            options = listOf(
                AgentOverlayManager.MONITOR_MODE_OVERLAY,
                AgentOverlayManager.MONITOR_MODE_ACTIVITY
            ),
            nameStringRes = R.string.param_vflow_ai_agent_monitor_mode_name,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_agent_monitor_overlay,
                R.string.option_vflow_ai_agent_monitor_activity
            ),
            legacyValueMap = mapOf(
                "悬浮窗" to AgentOverlayManager.MONITOR_MODE_OVERLAY,
                "Overlay" to AgentOverlayManager.MONITOR_MODE_OVERLAY,
                "监视器页面" to AgentOverlayManager.MONITOR_MODE_ACTIVITY,
                "Monitor screen" to AgentOverlayManager.MONITOR_MODE_ACTIVITY
            ),
            isHidden = false
        ),
        // fork：配置引用（追加，不重排既有参数）。
        AiModelConfigRef.SOURCE_INPUT,
        AiModelConfigRef.CONFIG_ID_INPUT
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("result", "最终结果", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_ai_agent_result_name),
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_ai_agent_success_name)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val rawInstruction = step.parameters["instruction"]?.toString() ?: ""
        val displayMode = step.parameters["display_mode"] as? String ?: DISPLAY_MODE_MAIN
        val prefix = if (displayMode == DISPLAY_MODE_VIRTUAL) context.getString(R.string.summary_vflow_agent_background_prefix) else ""

        // 如果指令复杂，只显示标题，避免与预览框重复
        if (VariableResolver.isComplex(rawInstruction)) {
            return PillUtil.buildSpannable(
                context,
                prefix + metadata.getLocalizedName(context),
                PillUtil.richTextPreview(rawInstruction)
            )
        }

        val instructionPill = PillUtil.createPillFromParam(step.parameters["instruction"], getInputs().find { it.id == "instruction" })
        return PillUtil.buildSpannable(context, prefix + metadata.getLocalizedName(context) + ": ", instructionPill)
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val inputsById = getInputs().associateBy { it.id }
        val baseUrl = context.getVariableAsString("base_url", "")
        val apiKey = context.getVariableAsString("api_key", "")
        val model = context.getVariableAsString("model", "autoglm-phone")
        val instruction = VariableResolver.resolve(context.getVariableAsString("instruction", ""), context)
        // 现在 variables 是 Map<String, VObject>，使用 getVariableAsInt 获取
        val maxSteps = context.getVariableAsInt("max_steps") ?: 30
        val rawDisplayMode = context.getVariableAsString("display_mode", DISPLAY_MODE_MAIN)
        val displayMode = inputsById["display_mode"]?.normalizeEnumValue(rawDisplayMode) ?: rawDisplayMode
        // fork：运行界面（悬浮窗 / 监视器页面）
        val monitorMode = inputsById["monitor_mode"]?.normalizeEnumValue(
            context.getVariableAsString("monitor_mode", AgentOverlayManager.MONITOR_MODE_OVERLAY)
        ) ?: AgentOverlayManager.MONITOR_MODE_OVERLAY

        val provider = inputsById["provider"]?.normalizeEnumValue(
            context.getVariableAsString("provider", PROVIDER_BIGMODEL)
        ) ?: PROVIDER_BIGMODEL

        // 配置引用（fork：AI 能力统一批 4 的 T5）。AutoGLM **只做活引用 4 项**
        // （provider / model / api_key / base_url）：提示词是模型契约、temperature 固定 0.1
        // ⇒ 值拷贝不适用（§3.6.3 C）。
        // ⚠️ 放在这里（原 apiKey 校验的位置）⇒ 出错时机与改造前一致（虚拟屏/悬浮窗之前）。
        val resolvedPreset = when (
            val resolution = AiModelConfigResolver.resolve(
                parameters = mapOf(
                    AiModelConfigRef.PARAM_SOURCE to context.getVariableAsString(
                        AiModelConfigRef.PARAM_SOURCE,
                        AiModelConfigRef.SOURCE_MANUAL,
                    ),
                    AiModelConfigRef.PARAM_CONFIG_ID to
                        context.getVariableAsString(AiModelConfigRef.PARAM_CONFIG_ID, ""),
                ),
                presets = ChatPresetRepository(context.applicationContext).getPresets(),
                manual = ManualAiConfig(
                    provider = provider,
                    model = model,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    // 值拷贝不适用：systemPrompt 稍后由整块 AutoGLM 提示词覆盖。
                    systemPrompt = "",
                    temperature = AUTO_GLM_TEMPERATURE,
                ),
            )
        ) {
            is AiModelConfigResolution.Resolved -> resolution.preset
            // 引用解析失败 ⇒ **明确报错，不静默**（D8 的兜底分支）。
            is AiModelConfigResolution.Missing ->
                return ExecutionResult.Failure(resolution.title, resolution.message)
        }

        // ⚠️ 校验的是**解析后**的 key：引用模式下步骤里的 `api_key` 恒为空串，
        //    真正的 key 来自被引用的那份配置。
        if (resolvedPreset.apiKey.isBlank()) {
            return ExecutionResult.Failure("配置错误", "API Key 不能为空")
        }

        // 虚拟屏幕逻辑
        var targetDisplayId = 0
        if (displayMode == DISPLAY_MODE_VIRTUAL) {
            if (!ShellManager.isShizukuActive(context.applicationContext)) {
                return ExecutionResult.Failure("权限不足", "使用虚拟屏幕需要 Shizuku 权限。")
            }
            onProgress(ProgressUpdate("正在创建虚拟屏幕..."))
            targetDisplayId = VirtualDisplayManager.startHeadlessSession()
            if (targetDisplayId < 0) {
                return ExecutionResult.Failure("环境错误", "虚拟屏幕创建失败。")
            }
            context.setVariable("_target_display_id", VObjectFactory.from(targetDisplayId))
            VirtualDisplayManager.getCurrentSessionSize()?.let { (width, height) ->
                context.setVariable("_target_display_width", VObjectFactory.from(width))
                context.setVariable("_target_display_height", VObjectFactory.from(height))
            }
            DebugLogger.i("AutoGLM", "AutoGLM 将运行在 Display ID: $targetDisplayId")
        }

        // fork：把模块名与步数上限交给悬浮窗，它会登记进 AgentRunMonitor（监视器要显示这两个）
        val overlayManager = AgentOverlayManager(
            context.applicationContext,
            moduleLabel = metadata.getLocalizedName(context.applicationContext),
            maxSteps = maxSteps,
            monitorMode = monitorMode,
        )
        withContext(Dispatchers.Main) { overlayManager.show() }

        try {
            // fork：虚拟屏预览（方案 A）。虚拟屏模式下用户在主屏上看不到 AI 正在操作的屏幕，
            // 这里把每一帧推给悬浮窗。只在虚拟屏启动；停止由 finally 里的 release() 负责。
            // ⚠️ 必须放在 try 内：放在 show() 与 try 之间的话，那段窗口里被取消就没人收尾了。
            // fork：`activity` 模式下画面交给监视器页面，这里**不再注册**预览监听者（省每帧 ≈10MB 拷贝）
            if (targetDisplayId > 0 && overlayManager.usesOverlayPreview) {
                // fork：预览帧流是**多消费者**的（监视 Activity 可能同时在看），所以这里是
                // 「注册监听者」而不是「启动唯一预览」。注销由 finally 里的 release() 统一做。
                VirtualDisplayManager.addPreviewListener { frame ->
                    overlayManager.updatePreview(frame)
                }
            }

            val agentTools = AgentTools(context)
            // 共享传输层：超时口径取 ChatStreamTimeouts.DEFAULT
            // （connect 30s / read 300s；改造前是 60/120 —— **有意变更**，见交付说明）。
            val client = ChatCompletionClient(httpClient = ChatStreamTimeouts.DEFAULT.toClient())

            val date = Date()
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val dateString = dateFormat.format(date)

            val systemPromptContent = """
                今天的日期是: $dateString
                你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
                你必须严格按照要求输出以下格式：
                <think>{think}</think>
                <answer>{action}</answer>
                
                其中：
                - {think} 是对你为什么选择这个操作的简短推理说明。
                - {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。
                
                操作指令及其作用如下：
                - do(action="Launch", app="xxx")  
                    Launch是启动目标app的操作，这比通过主屏幕导航更快。此操作完成后，您将自动收到结果状态的截图。
                - do(action="Tap", element=[x,y])  
                    Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
                - do(action="Tap", element=[x,y], message="重要操作")  
                    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
                - do(action="Type", text="xxx")  
                    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前，请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。重要提示：手机可能正在使用 ADB 键盘，该键盘不会像普通键盘那样占用屏幕空间。要确认键盘已激活，请查看屏幕底部是否显示 'ADB Keyboard {ON}' 类似的文本，或者检查输入框是否处于激活/高亮状态。不要仅仅依赖视觉上的键盘显示。自动清除文本：当你使用输入操作时，输入框中现有的任何文本（包括占位符文本和实际输入）都会在输入新文本前自动清除。你无需在输入前手动清除文本——直接使用输入操作输入所需文本即可。操作完成后，你将自动收到结果状态的截图。
                - do(action="Type_Name", text="xxx")  
                    Type_Name是输入人名的操作，基本功能同Type。
                - do(action="Interact")  
                    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
                - do(action="Swipe", start=[x1,y1], end=[x2,y2])  
                    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。可用于滚动内容、在屏幕之间导航、下拉通知栏以及项目栏或进行基于手势的导航。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。滑动持续时间会自动调整以实现自然的移动。此操作完成后，您将自动收到结果状态的截图。
                - do(action="Note", message="True")  
                    记录当前页面内容以便后续总结。
                - do(action="Call_API", instruction="xxx")  
                    总结或评论当前页面或已记录的内容。
                - do(action="Long Press", element=[x,y])  
                    Long Pres是长按操作，在屏幕上的特定点长按指定时间。可用于触发上下文菜单、选择文本或激活长按交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
                - do(action="Double Tap", element=[x,y])  
                    Double Tap在屏幕上的特定点快速连续点按两次。使用此操作可以激活双击交互，如缩放、选择文本或打开项目。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
                - do(action="Take_over", message="xxx")  
                    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
                - do(action="Back")  
                    导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。使用此操作可以从更深的屏幕返回、关闭弹出窗口或退出当前上下文。此操作完成后，您将自动收到结果状态的截图。
                - do(action="Home") 
                    Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。使用此操作可退出当前应用并返回启动器，或从已知状态启动新任务。此操作完成后，您将自动收到结果状态的截图。
                - do(action="Wait", duration="x seconds")  
                    等待页面加载，x为需要等待多少秒。
                - finish(message="xxx")  
                    finish是结束任务的操作，表示准确完整完成任务，message是终止信息。 
                
                必须遵循的规则：
                1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
                2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
                3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
                4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
                5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
                6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
                7. 在做小红书总结类任务时一定要筛选图文笔记。
                8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
                9. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
                10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
                11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将"群"字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
                12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
                13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
                14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish message说明点击不生效。
                15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish message说明但没找到要求的项目。
                16. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
                17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish(message="原因")。
                18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。 
                
                # 用户任务
                $instruction
            """.trimIndent()

            // ⚠️ system 消息走 `preset.systemPrompt`（传输层把它作为第一条 role:system 消息发出），
            //    history 里**不含** system 消息 —— 与改造前的线格式逐字等价。
            val preset = resolvedPreset.copy(systemPrompt = systemPromptContent)

            // AutoGLM 自己的历史（不含 system 消息）。
            var messages: List<ChatMessage> = emptyList()

            var currentStep = 0
            var taskResult: ExecutionResult? = null
            var errorCount = 0

            val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            // 默认主屏尺寸
            var screenWidth = displayMetrics.widthPixels
            var screenHeight = displayMetrics.heightPixels

            // 如果使用虚拟屏幕，使用真实会话尺寸，保证 AutoGLM 归一化坐标映射正确。
            if (targetDisplayId > 0) {
                val size = VirtualDisplayManager.getCurrentSessionSize()
                screenWidth = size?.first ?: screenWidth
                screenHeight = size?.second ?: screenHeight
                // fork：这里**不能**调 `hideForScreenshot()`。悬浮窗挂在主屏、被截的图来自虚拟屏，
                // 两者互不干扰；而 `hideForScreenshot()` 之后本分支**永远没有对应的 restore**
                // （restore 只写在 `targetDisplayId == 0` 那条路上）⇒ 整场任务里悬浮窗
                // （彩虹边框 + 控制卡片 + 暂停/结束按钮 + 虚拟屏预览）全程不可见。
            }

            while (currentStep < maxSteps) {
                try { overlayManager.awaitState() } catch (e: CancellationException) { return ExecutionResult.Failure("任务取消", "用户手动停止了任务。") }

                // fork：**本步（轮）开始** —— 监视器的流式条目在此换行（同轮内替换、跨轮追加）。
                // ⚠️ 必须在**本轮第一条流式正文之前**调用，否则本轮正文会把上一轮那条顶掉。
                overlayManager.beginThoughtRound()
                overlayManager.updateStatus("分析屏幕...", "准备截屏")
                onProgress(ProgressUpdate("步骤 ${currentStep + 1}/$maxSteps: 观察中..."))

                // 主屏幕才需要隐藏悬浮窗
                if (targetDisplayId == 0) {
                    overlayManager.hideForScreenshot()
                }

                val screenshotResult = AgentUtils.captureScreen(context.applicationContext, context)

                // 获取 UI 结构时传入 displayId
                val currentUI = AgentUtils.getCurrentUIInfo(context.applicationContext, targetDisplayId)
                DebugLogger.d("AutoGLM", "Step ${currentStep + 1} Context: $currentUI")

                if (targetDisplayId == 0) {
                    overlayManager.restoreAfterScreenshot()
                }

                // 本轮观察：文本 + 可选截图（截图只挂在本条 USER 消息上）。
                // ⚠️ 判据从 `base64 != null` 改成 `path != null`：`ChatImageAttachment` 只存
                //    路径（D9），而 `AgentUtils.ScreenshotResult` 的这两个字段**同生同灭**
                //    （成功 ⇒ 都非 null；失败 ⇒ 都是 null）⇒ 语义等价。
                val attachments = screenshotResult.path
                    ?.let { path ->
                        listOf(
                            ChatImageAttachment(
                                uri = path,
                                source = ChatImageSource.AGENT,
                                // ⚠️ **不得硬编码 image/jpeg**：主屏截图是 **PNG**
                                // （`CaptureScreenModule` 写 `screenshot_<ts>.png`）⇒ 把 PNG 字节
                                // 标成 JPEG 是**静默的 media_type 不符**（Anthropic 按 media_type
                                // 解码 ⇒ 硬失败）。仓库既有教训见 `ChatAgentToolImages.kt` 的 KDoc。
                                mimeType = chatImageMimeTypeForUri(path),
                            ),
                        )
                    }
                    .orEmpty()
                messages = messages + ChatMessage(
                    role = ChatMessageRole.USER,
                    content = "Current Step: ${currentStep + 1}\nCurrent Interface: $currentUI",
                    timestampMillis = System.currentTimeMillis(),
                    attachments = attachments,
                )

                overlayManager.updateStatus("AutoGLM 思考中...", "生成计划")

                val result = try {
                    runAutoGlmTurn(preset, messages, client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // **有意变更**：错误正文从固定「网络请求失败」变成传输层的异常信息
                    // （HTTP 非 2xx ⇒ "Status code: …\nError body:\n…"）——**响亮**，不静默。
                    return ExecutionResult.Failure("API Error", e.message ?: "网络请求失败")
                }
                val content = result.content
                DebugLogger.d("AutoGLM: ", content)

                // 清理历史图片消息（等价搬迁：清掉上一条 user 消息的图 + 追加 [Image removed]）
                messages = stripImagesFromLastUserMessage(messages)

                messages = messages + ChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    content = autoGlmAssistantHistoryContent(result),
                    reasoningContent = result.reasoningContent,
                    timestampMillis = System.currentTimeMillis(),
                )

                // --- 解析 AutoGLM 格式 ---
                var answer = extractTag(content, "answer")

                // 容错提取
                if (answer.isEmpty()) {
                    val commandRegex = Regex("""((?:do\(action=|finish\()(?:[^)]|"(?:[^"\\]|\\.)*")*\))""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    val match = commandRegex.find(content)
                    if (match != null) {
                        answer = match.value.trim()
                        // 括号平衡检查
                        if (answer.count { it == '(' } > answer.count { it == ')' }) {
                            answer += ")"
                        }
                        DebugLogger.w("AutoGLM", "未找到标准 XML 标签，通过正则回退提取到指令: $answer")
                    }
                }

                // fork：**整段原文**进思考流。原先只取 think 标签内部 —— 模型不用那个标签就什么都不显示
                // （`extractTag` 找不到标签返回空串），这是"监视器里只剩状态文案"的直接原因之一。
                if (content.isNotEmpty()) overlayManager.publishModelOutput(content, "决策中...")

                if (answer.isNotEmpty()) {
                    errorCount = 0
                    onProgress(ProgressUpdate("指令: $answer"))
                    DebugLogger.d("AutoGLM", "Answer: $answer")

                    val command = parseCommand(answer)
                    val actionName = command["action"] ?: "unknown"

                    overlayManager.updateStatus(null, "执行: $actionName")

                    var actionFeedback = "Action '$actionName' executed."

                    when (actionName) {
                        "Tap" -> {
                            val rawPos = command["element"] as? List<*>
                            if (rawPos != null && rawPos.size == 2) {
                                val normX = (rawPos[0] as? Number)?.toInt()
                                val normY = (rawPos[1] as? Number)?.toInt()
                                if (normX != null && normY != null) {
                                    val realX = (normX / 999.0 * screenWidth).toInt()
                                    val realY = (normY / 999.0 * screenHeight).toInt()
                                    agentTools.clickPoint(realX, realY)
                                }
                            }
                        }
                        "Type", "Type_Name" -> { // 处理 Type 和 Type_Name
                            val text = command["text"] as? String ?: ""
                            agentTools.inputText(text)
                        }
                        "Launch" -> {
                            val app = command["app"] as? String ?: ""
                            agentTools.launchApp(app)
                        }
                        "Swipe" -> {
                            val start = command["start"] as? List<*>
                            val end = command["end"] as? List<*>
                            if (start != null && end != null && start.size == 2 && end.size == 2) {
                                // 解析归一化坐标 (0-1000)
                                val normSX = (start[0] as? Number)?.toInt()
                                val normSY = (start[1] as? Number)?.toInt()
                                val normEX = (end[0] as? Number)?.toInt()
                                val normEY = (end[1] as? Number)?.toInt()

                                if (normSX != null && normSY != null && normEX != null && normEY != null) {
                                    // 映射到真实屏幕坐标
                                    val realSX = (normSX / 1000.0 * screenWidth).toInt()
                                    val realSY = (normSY / 1000.0 * screenHeight).toInt()
                                    val realEX = (normEX / 1000.0 * screenWidth).toInt()
                                    val realEY = (normEY / 1000.0 * screenHeight).toInt()

                                    // 调用精确滑动
                                    actionFeedback = agentTools.swipe(realSX, realSY, realEX, realEY)
                                } else {
                                    actionFeedback = "Failed: Invalid swipe coordinates."
                                }
                            }
                        }
                        "Double Tap" -> {
                            val rawPos = command["element"] as? List<*>
                            if (rawPos != null && rawPos.size == 2) {
                                val normX = (rawPos[0] as? Number)?.toInt()
                                val normY = (rawPos[1] as? Number)?.toInt()
                                if (normX != null && normY != null) {
                                    val realX = (normX / 999.0 * screenWidth).toInt()
                                    val realY = (normY / 999.0 * screenHeight).toInt()
                                    agentTools.doubleTap(realX, realY)
                                }
                            }
                        }
                        "Long Press" -> {
                            val rawPos = command["element"] as? List<*>
                            if (rawPos != null && rawPos.size == 2) {
                                val normX = (rawPos[0] as? Number)?.toInt()
                                val normY = (rawPos[1] as? Number)?.toInt()
                                if (normX != null && normY != null) {
                                    val realX = (normX / 999.0 * screenWidth).toInt()
                                    val realY = (normY / 999.0 * screenHeight).toInt()
                                    agentTools.longPress(realX, realY)
                                }
                            }
                        }
                        "Note" -> {
                            val msg = command["message"] as? String ?: ""
                            DebugLogger.i("AutoGLM", "Note recorded: $msg")
                            actionFeedback = "Note saved locally."
                        }
                        "Call_API" -> {
                            val instr = command["instruction"] as? String ?: ""
                            DebugLogger.i("AutoGLM", "Internal Call_API: $instr")
                            actionFeedback = "Sub-task '$instr' processed (simulated)."
                        }
                        "Interact" -> {
                            // 通知用户并短暂等待
                            val msg = "Interact triggered. Waiting for user choice..."
                            overlayManager.updateStatus("请协助选择", "等待用户...")
                            agentTools.wait(5)
                            actionFeedback = "Waited 5 seconds for user interaction."
                        }
                        "Take_over" -> {
                            val msg = command["message"] as? String ?: "Manual help needed"
                            overlayManager.updateStatus("需要人工接管", msg)
                            // 等待较长时间让用户操作
                            agentTools.wait(10)
                            actionFeedback = "Paused 10s for manual takeover."
                        }
                        "Back" -> agentTools.pressKey("back")
                        "Home" -> agentTools.pressKey("home")
                        "Wait" -> {
                            val durationStr = command["duration"] as? String ?: "5"
                            val sec = durationStr.filter { it.isDigit() }.toIntOrNull() ?: 5
                            agentTools.wait(sec)
                        }
                        "finish" -> {
                            val msg = command["message"] as? String ?: "Done"
                            taskResult = ExecutionResult.Success(mapOf(
                                "result" to VString(msg),
                                "success" to VBoolean(true)
                            ))
                        }
                        else -> {
                            if (answer.startsWith("finish")) {
                                val msg = command["message"] as? String ?: answer
                                taskResult = ExecutionResult.Success(mapOf(
                                    "result" to VString(msg),
                                    "success" to VBoolean(true)
                                ))
                            }
                        }
                    }

                    messages = messages + ChatMessage(
                        role = ChatMessageRole.USER,
                        content = "Action '$actionName' executed.",
                        timestampMillis = System.currentTimeMillis(),
                    )

                } else {
                    errorCount++
                    DebugLogger.w("AutoGLM", "未找到指令，AI 回复: $content")
                    if (errorCount >= 3) return ExecutionResult.Failure("AI 响应异常", "连续 3 次未输出有效指令。AI 最后回复: $content")
                    messages = messages + ChatMessage(
                        role = ChatMessageRole.USER,
                        content = "SYSTEM ERROR: No valid action found. Output <answer>do(...)</answer>.",
                        timestampMillis = System.currentTimeMillis(),
                    )
                    onProgress(ProgressUpdate("格式错误，重试 ($errorCount/3)..."))
                    continue
                }

                if (taskResult != null) break
                currentStep++
            }

            return taskResult ?: ExecutionResult.Success(mapOf(
                "result" to VString("Max steps reached"),
                "success" to VBoolean(false)
            ))

        } finally {
            // 销毁前清理虚拟屏幕上的应用
            // 现在 variables 是 Map<String, VObject>，使用 getVariableAsInt 获取
            val targetDisplayId = context.getVariableAsInt("_target_display_id") ?: 0
            if (targetDisplayId > 0) {
                withContext(NonCancellable) {
                    // 先杀应用，防止跳回主屏
                    AgentUtils.killTopAppOnDisplay(context.applicationContext, targetDisplayId)
                    // 再释放屏幕
                    DebugLogger.i("AutoGLM", "释放虚拟屏幕...")
                    VirtualDisplayManager.release()
                }
            }

            // 关闭悬浮窗
            withContext(NonCancellable) { withContext(Dispatchers.Main) { overlayManager.dismiss() } }
        }
    }

    private fun extractTag(text: String, tag: String): String {
        val startTag = "<$tag>"
        val endTag = "</$tag>"
        val startIndex = text.indexOf(startTag)
        val endIndex = text.indexOf(endTag)
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return text.substring(startIndex + startTag.length, endIndex).trim()
        }
        return ""
    }

    private fun parseCommand(cmd: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var cleanCmd = cmd.trim()

        // 提取参数时开启 DOT_MATCHES_ALL 以支持参数内的换行符
        val extractionOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        if (cleanCmd.startsWith("finish")) {
            result["action"] = "finish"

            // 从后往前查找 ") 组合，避免中间的引号导致截断
            val msgTag = "message=\""
            val startIdx = cleanCmd.indexOf(msgTag)
            // 查找最后一个 ") 组合
            val endIdx = cleanCmd.lastIndexOf("\")")

            if (startIdx != -1 && endIdx > startIdx) {
                // 截取 message=" 和 ") 之间的所有内容
                val rawMsg = cleanCmd.substring(startIdx + msgTag.length, endIdx)
                result["message"] = unescape(rawMsg)
            } else {
                // 如果格式极不标准，回退到正则（通常不会执行到这里）
                Regex("message=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let {
                    result["message"] = unescape(it.groupValues[1])
                }
            }
            return result
        }

        if (cleanCmd.startsWith("do(")) {
            cleanCmd = cleanCmd.removePrefix("do(").removeSuffix(")")
        } else {
            // 容错处理：提取最外层括号内容
            val start = cleanCmd.indexOf('(')
            val end = cleanCmd.lastIndexOf(')')
            if (start != -1 && end > start) {
                cleanCmd = cleanCmd.substring(start + 1, end)
            }
        }

        Regex("action=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let { result["action"] = it.groupValues[1] }
        Regex("element=\\[(.*?)\\]", extractionOptions).find(cleanCmd)?.let { match ->
            val parts = match.groupValues[1].split(",")
            if (parts.size == 2) {
                result["element"] = listOf(parts[0].trim().toIntOrNull() ?: 0, parts[1].trim().toIntOrNull() ?: 0)
            }
        }

        Regex("start=\\[(.*?)\\]", extractionOptions).find(cleanCmd)?.let { match ->
            result["start"] = match.groupValues[1].split(",").map { it.trim().toIntOrNull() ?: 0 }
        }
        Regex("end=\\[(.*?)\\]", extractionOptions).find(cleanCmd)?.let { match ->
            result["end"] = match.groupValues[1].split(",").map { it.trim().toIntOrNull() ?: 0 }
        }

        // 使用增强正则提取文本类参数，并反转义
        Regex("text=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let { result["text"] = unescape(it.groupValues[1]) }
        Regex("app=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let { result["app"] = unescape(it.groupValues[1]) }
        Regex("duration=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let { result["duration"] = unescape(it.groupValues[1]) }
        Regex("message=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let { result["message"] = unescape(it.groupValues[1]) }
        Regex("instruction=\"((?:[^\"\\\\]|\\\\.)*)\"", extractionOptions).find(cleanCmd)?.let { result["instruction"] = unescape(it.groupValues[1]) }

        return result
    }

    // 简单的反转义函数，处理 \" -> " 和 \\ -> \
    private fun unescape(str: String): String {
        return str.replace("\\\"", "\"").replace("\\\\", "\\")
    }

}
