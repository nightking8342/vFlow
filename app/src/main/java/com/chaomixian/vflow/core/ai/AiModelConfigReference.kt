// 文件: main/java/com/chaomixian/vflow/core/ai/AiModelConfigReference.kt
package com.chaomixian.vflow.core.ai

import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatProvider

/**
 * **三个 AI 模块共用的「配置来源」参数形态**（fork：AI 能力统一 §3.5 / D6 / D7，批 4 的 T5）。
 *
 * 三个模块（`AIModule` / `AgentModule` / `AutoGLMModule`）各自**追加**这两个参数，
 * 默认 `manual` ⇒ **零迁移**：老步骤没有这两个键 ⇒ 走手动配置，行为与改造前等价。
 *
 * | 参数 | 形态 | 说明 |
 * |---|---|---|
 * | [PARAM_SOURCE] | ENUM `{manual, reference}` | 默认 `manual`；由各模块 UIProvider 自绘选择器接管 |
 * | [PARAM_CONFIG_ID] | STRING（`isHidden`） | 引用哪一份「模型配置」（= 设置页的「模型预设」） |
 *
 * ⚠️ **参数 id 已定案，不得改名**（`config_source` / `model_config_id`）：
 * 它们要进工作流 JSON、要跨版本读回、还要与备份/清洗规则对齐。
 *
 * ⚠️ `model_config_id` **不含** `api_key` / `token` / `secret` 等子串 ⇒
 * 不会被 `SecretFieldScrubber` 清空（引用 id 不是密钥，导出备份时应原样保留）。
 * 由 `AiModelConfigResolverTest` 锁住。
 */
internal object AiModelConfigRef {

    const val PARAM_SOURCE = "config_source"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_REFERENCE = "reference"
    const val PARAM_CONFIG_ID = "model_config_id"

    /** 值拷贝（快照）参数 id：模块**拥有**该参数时才写（见 [AiModelConfigResolver.selectionWrites]）。 */
    const val PARAM_SYSTEM_PROMPT = "system_prompt"
    const val PARAM_TEMPERATURE = "temperature"

    /** 引用后**恒清空**的参数 id（§1.7：引用模式下密钥不进工作流）。 */
    const val PARAM_API_KEY = "api_key"

    /**
     * ENUM，默认 [SOURCE_MANUAL] ⇒ **零迁移**（老步骤没有该键 = 手动配置）。
     *
     * `legacyValueMap` 容错历史/本地化写法（「手动」/「引用」/`Manual`/`Reference`）。
     * `acceptsMagicVariable = false`：配置来源是**编辑期决策**，不接受变量
     * （否则会出现「运行期才知道引用哪份配置」这种无法在编辑器里呈现的形态）。
     */
    val SOURCE_INPUT: InputDefinition = InputDefinition(
        id = PARAM_SOURCE,
        name = "配置来源",
        staticType = ParameterType.ENUM,
        defaultValue = SOURCE_MANUAL,
        options = listOf(SOURCE_MANUAL, SOURCE_REFERENCE),
        nameStringRes = R.string.param_ai_config_source_name,
        optionsStringRes = listOf(
            R.string.option_ai_config_source_manual,
            R.string.option_ai_config_source_reference,
        ),
        legacyValueMap = mapOf(
            "手动" to SOURCE_MANUAL,
            "引用" to SOURCE_REFERENCE,
            "Manual" to SOURCE_MANUAL,
            "manual" to SOURCE_MANUAL,
            "Reference" to SOURCE_REFERENCE,
            "reference" to SOURCE_REFERENCE,
        ),
        acceptsMagicVariable = false,
    )

    /**
     * STRING，`isHidden = true`（由各模块 UIProvider 自绘选择器接管，不走通用表单）。
     *
     * `acceptsMagicVariable = false`：它是**配置 id**，不是内容参数 ——
     * 既不该被变量替换，也不该走富文本胶囊（`supportsRichText` 保持默认 `false`，
     * 从根上避开「漏写 supportsRichText ⇒ 胶囊不渲染」那个反复踩的坑）。
     */
    val CONFIG_ID_INPUT: InputDefinition = InputDefinition(
        id = PARAM_CONFIG_ID,
        name = "模型配置",
        staticType = ParameterType.STRING,
        defaultValue = "",
        isHidden = true,
        nameStringRes = R.string.param_ai_model_config_id_name,
        acceptsMagicVariable = false,
    )

    /** 容错读取：缺失 / 未知值一律回落到 [SOURCE_MANUAL]。 */
    fun sourceOf(parameters: Map<String, Any?>): String {
        val raw = parameters[PARAM_SOURCE] as? String
        return SOURCE_INPUT.normalizeEnumValueOrNull(raw) ?: SOURCE_MANUAL
    }

    fun configIdOf(parameters: Map<String, Any?>): String =
        parameters[PARAM_CONFIG_ID] as? String ?: ""
}

/**
 * 手动模式下的回退值（各模块从**自己的**参数里取）。
 *
 * ⚠️ 引用模式下只有 [systemPrompt] / [temperature] 会被用到（值拷贝快照）——
 * provider / model / apiKey / baseUrl 全部来自被引用的配置（活引用）。
 */
internal data class ManualAiConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val systemPrompt: String,
    val temperature: Double,
)

/** 配置解析结果（[Resolved] 可直接喂给传输层；[Missing] 必须**明确报错，不静默**）。 */
internal sealed interface AiModelConfigResolution {

    data class Resolved(
        val preset: ChatPresetConfig,
        /** 被引用配置的显示名（手动模式为 null）。仅用于进度文案 / 日志。 */
        val configName: String?,
    ) : AiModelConfigResolution

    /**
     * 解析失败。
     *
     * ⚠️ **D8（被引用配置被删 / 被改）的兜底分支**：无论 D8 最终选
     * (a) 阻止删除 / (c) 提示但不阻止，都必须有这条「运行期明确报错」。
     * 静默回落到手动配置会让用户拿到一份**看起来能跑、实际用错 key** 的请求。
     */
    data class Missing(val title: String, val message: String) : AiModelConfigResolution
}

/**
 * **配置引用解析器**（纯函数 ⇒ 可纯 JVM 单测；生产 = `ChatPresetRepository.getPresets()`）。
 *
 * 引用语义（§3.5 / D6 / D7）：
 *
 * | 项 | 形态 | 理由 |
 * |---|---|---|
 * | `provider` / `model` / `api_key` / `base_url` | **活引用** | 执行时现读 ⇒ 改配置**全仓生效** |
 * | `system_prompt` / `temperature` | **值拷贝（快照）** | 选中那一刻拷进步骤、可在编辑器改；之后改配置**不影响**本步骤 |
 * | `useResponsesApi` | **不参与引用**（恒 `false`） | §3.5 的活引用清单只有 4 项；避免未验证的 Responses 协议切换 |
 * | `stream_idle_timeout_seconds` | **活引用**（引用模式跟随；手动模式恒默认 300） | ⚠️ **只对「流式」路径生效**（`ChatSse` 读 preset）；非流式的 `AIModule` / `AutoGLM` 用的是写死的 `ChatStreamTimeouts.DEFAULT`（connect 30s / read 300s） |
 * | `stream_max_retries` | **活引用**（引用模式跟随；手动模式恒 0） | 2026-10-17 订正：原先两模式都钉 0 ⇒ 「配置里设的重试次数在 AI 模块里静默失效」；手动模式仍 0（模块不暴露该参数，与改造前等价） |
 *
 * 引用粒度 = **「模型配置」（`ChatPresetConfig`，即设置页的「模型预设」）**，不是 provider 级配置：
 * 活引用清单含 `model`，而 provider 级配置不含 model；且 `getPresets()` 返回的已是
 * **解析完整**的配置（apiKey / baseUrl / systemPrompt / temperature 都已归一化拷入）。
 */
internal object AiModelConfigResolver {

    /** 未选配置（`config_source = reference` 但 `model_config_id` 为空）。 */
    private const val MISSING_TITLE = "配置错误"
    private const val MESSAGE_NOT_SELECTED =
        "未选择要引用的模型配置。请在「模型配置」中选择一份，或改回手动配置。"

    /**
     * 步骤参数 + 当前模型配置列表 → 本次请求要用的 [ChatPresetConfig]。
     *
     * - `manual`：用 [ManualAiConfig] 组一份配置（`useResponsesApi = false`，
     *   `streamMaxRetries = 0`）—— **与改造前等价**（模块现状本就不重试）。
     * - `reference`：按 [AiModelConfigRef.PARAM_CONFIG_ID] 在 [presets] 里查；
     *   活引用 provider / model / apiKey / baseUrl，值拷贝覆盖 systemPrompt / temperature；
     *   查不到 ⇒ [AiModelConfigResolution.Missing]。
     */
    fun resolve(
        parameters: Map<String, Any?>,
        presets: List<ChatPresetConfig>,
        manual: ManualAiConfig,
    ): AiModelConfigResolution {
        if (AiModelConfigRef.sourceOf(parameters) != AiModelConfigRef.SOURCE_REFERENCE) {
            return AiModelConfigResolution.Resolved(
                preset = ChatPresetConfig(
                    provider = chatProviderForModuleProvider(manual.provider).storageValue,
                    model = manual.model,
                    apiKey = manual.apiKey,
                    baseUrl = manual.baseUrl,
                    systemPrompt = manual.systemPrompt,
                    temperature = manual.temperature,
                    useResponsesApi = false,
                    // 手动模式没有「重试」参数 ⇒ 恒 0（与改造前等价；**引用模式才跟随配置**）。
                    streamMaxRetries = 0,
                ),
                configName = null,
            )
        }

        val configId = AiModelConfigRef.configIdOf(parameters)
        if (configId.isBlank()) {
            return AiModelConfigResolution.Missing(MISSING_TITLE, MESSAGE_NOT_SELECTED)
        }
        val referenced = presets.firstOrNull { it.id == configId }
            ?: return AiModelConfigResolution.Missing(
                MISSING_TITLE,
                "引用的模型配置不存在（id=$configId），可能已被删除。请重新选择，或改回手动配置。",
            )

        return AiModelConfigResolution.Resolved(
            preset = referenced.copy(
                // 值拷贝（快照）：优先于配置现值 —— 步骤里那份是「选中那一刻」的，
                // 之后用户改配置**不**应影响本步骤（D7）。
                systemPrompt = manual.systemPrompt,
                temperature = manual.temperature,
                // 活引用：`streamIdleTimeoutSeconds` / `streamMaxRetries` 都**不 override** ⇒ 跟随被引用配置。
                // 2026-10-17 订正：原先把 `streamMaxRetries` 钉成 0，会让「配置里设的重试次数」在模块里静默失效。
                // `useResponsesApi` 仍恒 false —— 避免未验证的 Responses 协议切换。
                useResponsesApi = false,
            ),
            configName = referenced.name.ifBlank { null },
        )
    }

    /**
     * 选中一份配置那一刻的**写入集合**（选择器与 UIProvider 共用；纯函数 ⇒ 可单测）。
     *
     * 恒含三项：
     * 1. `config_source = reference`
     * 2. `model_config_id = <id>`
     * 3. `api_key = ""` —— §1.7 的收益：**引用后工作流 JSON 里不再有明文密钥**
     *    （也让 `SecretFieldScrubber` 的兜底变成「无事可做」）。
     *
     * 再按 [ownedValueCopyKeys] 写值拷贝（`system_prompt` / `temperature`）——
     * **只写模块确实拥有的参数**：`AutoGLM` 两者都不拥有（提示词是模型契约、
     * temperature 固定 0.1）⇒ 传空集即可，不会凭空给它造出参数。
     */
    fun selectionWrites(
        config: ChatPresetConfig,
        ownedValueCopyKeys: Set<String>,
    ): Map<String, Any?> {
        val writes = linkedMapOf<String, Any?>(
            AiModelConfigRef.PARAM_SOURCE to AiModelConfigRef.SOURCE_REFERENCE,
            AiModelConfigRef.PARAM_CONFIG_ID to config.id,
            AiModelConfigRef.PARAM_API_KEY to "",
        )
        if (AiModelConfigRef.PARAM_SYSTEM_PROMPT in ownedValueCopyKeys) {
            writes[AiModelConfigRef.PARAM_SYSTEM_PROMPT] = config.systemPrompt
        }
        if (AiModelConfigRef.PARAM_TEMPERATURE in ownedValueCopyKeys) {
            writes[AiModelConfigRef.PARAM_TEMPERATURE] = config.temperature
        }
        return writes
    }

    /**
     * 切回手动配置时的写入集合。
     *
     * ⚠️ **只改来源，不动任何既有值**：`model_config_id` 也**不**清空 ——
     * 用户来回切换来源时不该丢掉上次选的配置（再次点「引用」还能选回同一份），
     * 而手动模式下这个键根本不参与解析（[resolve] 只看 `config_source`）。
     */
    fun manualWrites(): Map<String, Any?> =
        mapOf(AiModelConfigRef.PARAM_SOURCE to AiModelConfigRef.SOURCE_MANUAL)
}

/**
 * 模块「服务商」枚举 → 传输层 [ChatProvider]（只影响**端点规整**与**认证头风格**）。
 *
 * 三个模块的服务商词表（`openai` / `deepseek` / `custom` / `dashscope` / `bigmodel`）
 * 与 [ChatProvider] 的 `storageValue` **不是同一套**：除 `openai` / `deepseek` 外，
 * 其余全部落到 [ChatProvider.fromStorage] 的兜底（`OPENAI`）——这正是想要的行为：
 * 那些模块的手动模式 base_url 都是**用户/预设填好的完整路径**，
 * 端点规整只需「不额外加 `/v1`」这一档（`custom` 场景）与「OpenAI 兼容」的认证头。
 */
internal fun chatProviderForModuleProvider(provider: String): ChatProvider =
    ChatProvider.fromStorage(provider)
