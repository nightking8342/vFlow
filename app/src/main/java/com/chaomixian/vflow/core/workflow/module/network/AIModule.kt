// 文件: main/java/com/chaomixian/vflow/core/workflow/module/network/AIModule.kt
package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.ai.AiModelConfigResolution
import com.chaomixian.vflow.core.ai.AiModelConfigResolver
import com.chaomixian.vflow.core.ai.ManualAiConfig
import com.chaomixian.vflow.core.ai.chatProviderForModuleProvider
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.chat.ChatAgentSkillSelection
import com.chaomixian.vflow.ui.chat.ChatCompletionClient
import com.chaomixian.vflow.ui.chat.ChatCompletionResult
import com.chaomixian.vflow.ui.chat.ChatImageBudget
import com.chaomixian.vflow.ui.chat.ChatMessage
import com.chaomixian.vflow.ui.chat.ChatMessageRole
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatPresetRepository
import com.chaomixian.vflow.ui.chat.ChatStreamTimeouts
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import kotlinx.coroutines.CancellationException

/**
 * **本模块的传输层配置**（手动档的规范形态；fork：AI 能力统一批 4 的 T5）。
 *
 * `useResponsesApi = false` / `streamMaxRetries = 0` 与改造前**逐条等价**
 * （改造前就是一次非流式 POST，没有任何重试）。
 *
 * ⚠️ 生产路径走 [AiModelConfigResolver.resolve]（它的 `manual` 分支产出的形状与本函数
 * **逐字段相同**）；本函数是那份形状的**具名规范**，由 `AIModuleTransportTest` 拿它
 * 与 `resolve` 的 manual 结果做等价比对 —— 两条路径漂移时用例变红。
 */
internal fun aiCompletionPreset(
    provider: String,
    model: String,
    apiKey: String,
    baseUrl: String,
    systemPrompt: String,
    temperature: Double,
): ChatPresetConfig = ChatPresetConfig(
    provider = chatProviderForModuleProvider(provider).storageValue,
    model = model,
    apiKey = apiKey,
    baseUrl = baseUrl,
    systemPrompt = systemPrompt,
    temperature = temperature,
    useResponsesApi = false,
    streamMaxRetries = 0,
)

/**
 * **一轮纯文本问答**（共享传输层）。
 *
 * ⚠️ `AIModule` **不进引擎**（§3.6：纯文本一次问答，没有工具、没有循环、没有观察）——
 * 它只复用「请求怎么发」这一层，自己的 `system_prompt` / `prompt` 参数语义一字不改。
 *
 * system 消息经 `preset.systemPrompt` 发出（传输层把它放在 `messages[0]`）；
 * `history` 里只有一条 user 消息。`tools` 显式传 `null` + `skillSelection = EMPTY`
 * ⇒ 请求体里**没有** `tools` 段（与改造前一致）。
 */
internal suspend fun runAiCompletionTurn(
    preset: ChatPresetConfig,
    userPrompt: String,
    client: ChatCompletionClient,
): ChatCompletionResult = client.generateReply(
    preset = preset,
    history = listOf(
        ChatMessage(
            role = ChatMessageRole.USER,
            content = userPrompt,
            timestampMillis = System.currentTimeMillis(),
        ),
    ),
    skillSelection = ChatAgentSkillSelection.EMPTY,
    tools = null,
    imageBudget = ChatImageBudget.DEFAULT,
)

/**
 * "AI 对话" 模块。
 * 支持 OpenAI、DeepSeek 等兼容 OpenAI 接口的大模型服务。
 *
 * ## fork（AI 能力统一批 4 的 T5）
 *
 * 请求改走 **ChatAgent 的共享传输层**（`ChatCompletionClient`）⇒「一次改动处处生效」：
 * 端点规整、认证头风格、错误分类、超时口径全部与 ChatAgent 同源。
 * 另新增**配置引用**：`config_source = reference` 时活引用「模型配置」里的
 * provider / model / api_key / base_url，并把 system_prompt / temperature
 * 作为**值拷贝快照**写进步骤（默认 `manual` ⇒ 老步骤零迁移）。
 */
class AIModule : BaseModule() {
    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_CUSTOM = "custom"

        val PROVIDER_INPUT_DEFINITION = InputDefinition(
            "provider",
            "服务商",
            ParameterType.ENUM,
            PROVIDER_OPENAI,
            options = listOf(PROVIDER_OPENAI, PROVIDER_DEEPSEEK, PROVIDER_CUSTOM),
            nameStringRes = R.string.param_vflow_network_ai_provider,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_completion_provider_openai,
                R.string.option_vflow_ai_completion_provider_deepseek,
                R.string.option_vflow_ai_completion_provider_custom
            ),
            legacyValueMap = mapOf(
                "OpenAI" to PROVIDER_OPENAI,
                "DeepSeek" to PROVIDER_DEEPSEEK,
                "自定义" to PROVIDER_CUSTOM,
                "Custom" to PROVIDER_CUSTOM
            )
        )
    }

    override val id = "vflow.ai.completion"
    override val metadata = ActionMetadata(
        name = "AI 对话",  // Fallback
        nameStringRes = R.string.module_vflow_ai_completion_name,
        description = "调用大模型 API (OpenAI/DeepSeek) 进行智能对话。",  // Fallback
        descriptionStringRes = R.string.module_vflow_ai_completion_desc,
        iconRes = R.drawable.rounded_hexagon_nodes_24,
        category = "网络",
        categoryId = "network"
    )

    override val uiProvider: ModuleUIProvider = AIModuleUIProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        PROVIDER_INPUT_DEFINITION,
        InputDefinition("base_url", "Base URL", ParameterType.STRING, "https://api.openai.com/v1", nameStringRes = R.string.param_vflow_network_ai_base_url),
        InputDefinition("api_key", "API Key", ParameterType.STRING, "", nameStringRes = R.string.param_vflow_network_ai_api_key),
        InputDefinition("model", "模型", ParameterType.STRING, "gpt-3.5-turbo", nameStringRes = R.string.param_vflow_network_ai_model),
        InputDefinition("prompt", "提示词", ParameterType.STRING, "", acceptsMagicVariable = true, supportsRichText = true, nameStringRes = R.string.param_vflow_network_ai_prompt),
        InputDefinition("system_prompt", "系统提示词", ParameterType.STRING, "You are a helpful assistant.", nameStringRes = R.string.param_vflow_network_ai_system_prompt),
        InputDefinition("temperature", "随机性", ParameterType.NUMBER, 0.7, nameStringRes = R.string.param_vflow_network_ai_temperature),
    ) + moduleProxyInputDefinitions() + listOf(
        InputDefinition("show_advanced", "显示高级", ParameterType.BOOLEAN, false, isHidden = true, nameStringRes = R.string.param_vflow_network_ai_show_advanced),
        // fork：配置引用（追加，不重排既有参数）。
        AiModelConfigRef.SOURCE_INPUT,
        AiModelConfigRef.CONFIG_ID_INPUT,
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("result", "回答内容", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_ai_completion_result_name),
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_ai_completion_success_name)
    )

    // 摘要显示逻辑
    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val provider = PROVIDER_INPUT_DEFINITION.normalizeEnumValueOrNull(step.parameters["provider"] as? String) ?: PROVIDER_OPENAI
        val promptText = step.parameters["prompt"] as? String ?: ""
        val providerDisplay = getProviderDisplayName(context, provider)

        // 如果内容是"复杂"的（多变量或长文本），VariableResolver.isComplex 会返回 true
        if (VariableResolver.isComplex(promptText)) {
            val providerPill = PillUtil.createPillFromParam(step.parameters["provider"], PROVIDER_INPUT_DEFINITION, isModuleOption = true)
            val prefix = context.getString(R.string.summary_vflow_network_ai_prefix)
            val suffix = context.getString(R.string.summary_vflow_network_ai_middle)
            return PillUtil.buildSpannable(context, prefix, providerPill, suffix, PillUtil.richTextPreview(promptText))
        } else {
            // 如果内容简单（单变量或短文本），显示完整的摘要（包含 Prompt 药丸）
            val promptPill = PillUtil.createPillFromParam(step.parameters["prompt"], getInputs().find { it.id == "prompt" })
            val prefix = context.getString(R.string.summary_vflow_network_ai_with, providerDisplay)
            return PillUtil.buildSpannable(context, prefix, promptPill)
        }
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 获取并解析参数
        val baseUrl = context.getVariableAsString("base_url", "https://api.openai.com/v1")
        val apiKey = context.getVariableAsString("api_key", "")
        val model = context.getVariableAsString("model", "gpt-3.5-turbo")
        val rawPrompt = context.getVariableAsString("prompt", "")
        val prompt = VariableResolver.resolve(rawPrompt, context)
        val systemPrompt = context.getVariableAsString("system_prompt", "You are a helpful assistant.")
        // 现在 variables 是 Map<String, VObject>，使用 getVariableAsNumber 获取
        val temperature = context.getVariableAsNumber("temperature") ?: 0.7
        val provider = PROVIDER_INPUT_DEFINITION.normalizeEnumValueOrNull(
            context.getVariableAsString("provider", PROVIDER_OPENAI)
        ) ?: PROVIDER_OPENAI
        val proxyAddress = resolveModuleProxyAddress(
            context.getVariableAsString("proxy_mode", ""),
            context.getVariableAsString("proxy", ""),
            context
        )

        // 配置引用（fork：AI 能力统一批 4 的 T5）。
        // ⚠️ 默认 manual ⇒ 老步骤（没有这两个键）走的手动分支与改造前**逐字段相同**。
        val preset = when (
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
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                ),
            )
        ) {
            is AiModelConfigResolution.Resolved -> resolution.preset
            // 引用解析失败 ⇒ **明确报错，不静默**（D8 的兜底分支）。
            is AiModelConfigResolution.Missing ->
                return ExecutionResult.Failure(resolution.title, resolution.message)
        }

        // ⚠️ 校验的是**解析后**的 key：引用模式下步骤里的 `api_key` 恒为空串
        //    （引用即清空），真正的 key 来自被引用的那份配置。
        if (preset.apiKey.isBlank()) return ExecutionResult.Failure("配置错误", "API Key 不能为空")
        if (prompt.isBlank()) return ExecutionResult.Failure("配置错误", "提示词不能为空")

        // 代理仍生效：把「带代理的 OkHttpClient」注入共享 client（超时口径取
        // ChatStreamTimeouts.DEFAULT —— connect 30s / read 300s，比改造前的 30/60 更宽）。
        val client = ChatCompletionClient(
            httpClient = applyProxyIfConfigured(
                ChatStreamTimeouts.DEFAULT.toClient().newBuilder(),
                appContext,
                proxyAddress,
            ).build(),
        )

        onProgress(ProgressUpdate("正在思考 (${preset.model})..."))

        return try {
            val result = runAiCompletionTurn(preset, prompt, client)
            if (result.content.isBlank()) {
                ExecutionResult.Failure("响应为空", "服务器返回了空内容")
            } else {
                ExecutionResult.Success(mapOf(
                    "result" to VString(result.content),
                    "success" to VBoolean(true)
                ))
            }
        } catch (e: CancellationException) {
            // 取消必须原样上抛（工作流/执行器取消的既有契约）。
            throw e
        } catch (e: Exception) {
            // 传输层在 HTTP 非 2xx 时抛 IllegalStateException("Status code: …\nError body:\n…")；
            // 其余（超时 / 连接失败等）保留原有的「网络异常」分类。
            val title = if (e is IllegalStateException) "请求失败" else "网络异常"
            ExecutionResult.Failure(title, e.message ?: "未知错误")
        }
    }

    private fun getProviderDisplayName(context: Context, provider: String): String {
        return when (provider) {
            PROVIDER_DEEPSEEK -> context.getString(R.string.option_vflow_ai_completion_provider_deepseek)
            PROVIDER_CUSTOM -> context.getString(R.string.option_vflow_ai_completion_provider_custom)
            else -> context.getString(R.string.option_vflow_ai_completion_provider_openai)
        }
    }
}