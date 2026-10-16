package com.chaomixian.vflow.core.ai

import com.chaomixian.vflow.core.backup.SecretFieldScrubber
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **配置引用共享件**（fork：AI 能力统一 §3.5 / D6 / D7 / §1.7，批 4 的 T5）。
 *
 * 纯 JVM（无 Android、无 Robolectric）：`resolve` / `selectionWrites` / `manualWrites`
 * 都是纯函数，生产侧只把 `ChatPresetRepository.getPresets()` 的结果喂进来。
 *
 * ## ⚠️ 必做反证（每条断言都要能变红再改回）
 *
 * 1. 把 `resolve` 的 reference 分支改成直接返回 manual 组装的配置
 *    ⇒ [reference_mode_usesTheLiveReferencedProviderModelApiKeyAndBaseUrl] 变红；
 * 2. 去掉 `resolve` 里的 `copy(systemPrompt = …, temperature = …)`
 *    ⇒ [reference_mode_takesSystemPromptAndTemperatureFromTheStepSnapshot] 变红；
 * 3. 把 `Missing` 改成回落到 manual
 *    ⇒ [reference_mode_withAnUnknownConfigIdReportsMissing] 变红；
 * 4. 去掉 `selectionWrites` 的 `api_key to ""`
 * 5. 把 reference 分支的 `streamMaxRetries = 0` 加回去（或 override `streamIdleTimeoutSeconds`）
 *    ⇒ [reference_mode_followsTheReferencedTimeoutAndRetry_whileManualKeepsTheModuleDefaults] 变红。
 *    ⇒ [selectionWrites_clearsTheApiKey] 变红。
 */
class AiModelConfigResolverTest {

    // ------------------------------------------------------------ 装置

    private fun preset(
        id: String,
        name: String = "配置 $id",
        provider: String = ChatProvider.ANTHROPIC.storageValue,
        model: String = "claude-sonnet-4-6",
        apiKey: String = "sk-referenced",
        baseUrl: String = "https://referenced.example.com",
        systemPrompt: String = "CONFIG-CURRENT-PROMPT",
        temperature: Double = 0.2,
        useResponsesApi: Boolean = false,
        streamIdleTimeoutSeconds: Int = 600,
        streamMaxRetries: Int = 2,
    ) = ChatPresetConfig(
        id = id,
        name = name,
        provider = provider,
        model = model,
        apiKey = apiKey,
        baseUrl = baseUrl,
        systemPrompt = systemPrompt,
        temperature = temperature,
        useResponsesApi = useResponsesApi,
        streamIdleTimeoutSeconds = streamIdleTimeoutSeconds,
        streamMaxRetries = streamMaxRetries,
    )

    /** 步骤里的手动值：与上面那份配置**处处不同**，便于区分「用了谁」。 */
    private fun manual(
        provider: String = "custom",
        model: String = "manual-model",
        apiKey: String = "sk-manual",
        baseUrl: String = "https://manual.example.com/v1",
        systemPrompt: String = "SNAPSHOT-PROMPT",
        temperature: Double = 0.9,
    ) = ManualAiConfig(
        provider = provider,
        model = model,
        apiKey = apiKey,
        baseUrl = baseUrl,
        systemPrompt = systemPrompt,
        temperature = temperature,
    )

    private fun referenceParams(configId: String?) = buildMap<String, Any?> {
        put(AiModelConfigRef.PARAM_SOURCE, AiModelConfigRef.SOURCE_REFERENCE)
        if (configId != null) put(AiModelConfigRef.PARAM_CONFIG_ID, configId)
    }

    private fun resolved(resolution: AiModelConfigResolution): ChatPresetConfig {
        assertTrue(
            "预期 Resolved，实际是 $resolution",
            resolution is AiModelConfigResolution.Resolved,
        )
        return (resolution as AiModelConfigResolution.Resolved).preset
    }

    // ------------------------------------------------------------ 1. 默认 manual（零迁移）

    @Test
    fun missingSourceParameter_fallsBackToManualWithTheStepValues() {
        // 老步骤：**没有** config_source / model_config_id 这两个键。
        val result = AiModelConfigResolver.resolve(
            parameters = emptyMap(),
            presets = listOf(preset("cfg-1")),
            manual = manual(),
        )

        assertEquals("默认必须是 manual（零迁移）", AiModelConfigRef.SOURCE_MANUAL, AiModelConfigRef.sourceOf(emptyMap()))
        assertEquals(
            "SOURCE_INPUT 的默认值就是 manual",
            AiModelConfigRef.SOURCE_MANUAL,
            AiModelConfigRef.SOURCE_INPUT.defaultValue,
        )
        val preset = resolved(result)
        assertEquals("manual 模式用步骤里的 provider（经 provider 映射）", ChatProvider.OPENAI.storageValue, preset.provider)
        assertEquals("manual-model", preset.model)
        assertEquals("sk-manual", preset.apiKey)
        assertEquals("https://manual.example.com/v1", preset.baseUrl)
        assertEquals("SNAPSHOT-PROMPT", preset.systemPrompt)
        assertEquals(0.9, preset.temperature, 1e-9)
        assertNull("manual 模式没有配置名", (result as AiModelConfigResolution.Resolved).configName)
    }

    @Test
    fun unknownSourceValue_fallsBackToManual() {
        val result = AiModelConfigResolver.resolve(
            parameters = mapOf(AiModelConfigRef.PARAM_SOURCE to "something-else"),
            presets = emptyList(),
            manual = manual(),
        )
        assertEquals("未知来源值一律回落 manual", "manual-model", resolved(result).model)
    }

    @Test
    fun legacySourceValues_areNormalized() {
        assertEquals(
            "「引用」是历史/本地化写法，必须映射到 reference",
            AiModelConfigRef.SOURCE_REFERENCE,
            AiModelConfigRef.sourceOf(mapOf(AiModelConfigRef.PARAM_SOURCE to "引用")),
        )
        assertEquals(
            "「手动」映射到 manual",
            AiModelConfigRef.SOURCE_MANUAL,
            AiModelConfigRef.sourceOf(mapOf(AiModelConfigRef.PARAM_SOURCE to "手动")),
        )
    }

    // ------------------------------------------------------------ 2. reference：活引用 4 项

    @Test
    fun reference_mode_usesTheLiveReferencedProviderModelApiKeyAndBaseUrl() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams("cfg-1"),
            presets = listOf(preset("cfg-1", name = "我的 Claude")),
            manual = manual(),
        )

        val preset = resolved(result)
        assertEquals("provider 必须是活引用", ChatProvider.ANTHROPIC.storageValue, preset.provider)
        assertEquals("model 必须是活引用", "claude-sonnet-4-6", preset.model)
        assertEquals("api_key 必须是活引用（步骤里的 sk-manual 不得参与）", "sk-referenced", preset.apiKey)
        assertEquals(
            "base_url 必须是活引用",
            "https://referenced.example.com",
            preset.baseUrl,
        )
        assertEquals("配置名要带出来（供进度文案 / 日志）", "我的 Claude", (result as AiModelConfigResolution.Resolved).configName)
        assertEquals("useResponsesApi 不参与引用，恒 false", false, preset.useResponsesApi)
        assertEquals("streamMaxRetries 必须是活引用（配置里设的 2 要跟过来）", 2, preset.streamMaxRetries)
        assertEquals("streamIdleTimeoutSeconds 必须是活引用（配置里设的 600 要跟过来）", 600, preset.streamIdleTimeoutSeconds)
    }

    @Test
    fun reference_mode_ignoresTheReferencedUseResponsesApi() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams("cfg-1"),
            presets = listOf(preset("cfg-1", useResponsesApi = true)),
            manual = manual(),
        )
        assertEquals(
            "被引用配置开了 Responses 也不跟着切（活引用清单只有 4 项）",
            false,
            resolved(result).useResponsesApi,
        )
    }

    @Test
    fun reference_mode_followsTheReferencedTimeoutAndRetry_whileManualKeepsTheModuleDefaults() {
        // 引用：两项都跟随被引用配置（2026-10-17 订正 —— 原先 streamMaxRetries 被钉成 0）
        val referenced = resolved(
            AiModelConfigResolver.resolve(
                parameters = referenceParams("cfg-1"),
                presets = listOf(preset("cfg-1", streamIdleTimeoutSeconds = 600, streamMaxRetries = 2)),
                manual = manual(),
            ),
        )
        assertEquals("引用模式下超时跟随被引用配置", 600, referenced.streamIdleTimeoutSeconds)
        assertEquals("引用模式下重试次数跟随被引用配置", 2, referenced.streamMaxRetries)

        // 手动：模块不暴露这两项 ⇒ 超时走默认 300、重试恒 0（与改造前等价）
        val manualMode = resolved(
            AiModelConfigResolver.resolve(
                parameters = emptyMap(),
                presets = emptyList(),
                manual = manual(),
            ),
        )
        assertEquals(
            "手动模式下超时是默认值",
            ChatPresetConfig.DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS,
            manualMode.streamIdleTimeoutSeconds,
        )
        assertEquals("手动模式下重试恒 0（与改造前等价）", 0, manualMode.streamMaxRetries)
    }

    // ------------------------------------------------------------ 3. reference：值拷贝

    @Test
    fun reference_mode_takesSystemPromptAndTemperatureFromTheStepSnapshot() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams("cfg-1"),
            presets = listOf(preset("cfg-1", systemPrompt = "CONFIG-CURRENT-PROMPT", temperature = 0.2)),
            manual = manual(systemPrompt = "SNAPSHOT-PROMPT", temperature = 0.9),
        )

        val preset = resolved(result)
        assertEquals(
            "值拷贝优先于配置现值（步骤里那份是选中那一刻的快照，D7）",
            "SNAPSHOT-PROMPT",
            preset.systemPrompt,
        )
        assertEquals(0.9, preset.temperature, 1e-9)
    }

    // ------------------------------------------------------------ 4. reference：明确报错

    @Test
    fun reference_mode_withoutAConfigIdReportsMissing() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams(configId = null),
            presets = listOf(preset("cfg-1")),
            manual = manual(),
        )

        assertTrue("必须明确报错，不静默回落", result is AiModelConfigResolution.Missing)
        val missing = result as AiModelConfigResolution.Missing
        assertEquals("配置错误", missing.title)
        assertEquals(
            "未选择要引用的模型配置。请在「模型配置」中选择一份，或改回手动配置。",
            missing.message,
        )
    }

    @Test
    fun reference_mode_withABlankConfigIdReportsMissing() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams(configId = "   "),
            presets = listOf(preset("cfg-1")),
            manual = manual(),
        )
        assertTrue("空白 id 与缺失 id 同等对待", result is AiModelConfigResolution.Missing)
    }

    @Test
    fun reference_mode_withAnUnknownConfigIdReportsMissing() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams("cfg-gone"),
            presets = listOf(preset("cfg-1")),
            manual = manual(),
        )

        assertTrue("配置被删 ⇒ 必须明确报错（D8 的兜底分支）", result is AiModelConfigResolution.Missing)
        val missing = result as AiModelConfigResolution.Missing
        assertEquals("配置错误", missing.title)
        assertTrue(
            "报错文案必须带上是哪个 id（否则用户无从排查）",
            missing.message.contains("id=cfg-gone"),
        )
    }

    @Test
    fun reference_mode_withAnEmptyPresetListReportsMissing() {
        val result = AiModelConfigResolver.resolve(
            parameters = referenceParams("cfg-1"),
            presets = emptyList(),
            manual = manual(),
        )
        assertTrue("一份配置都没有时同样是 Missing（不得回落到 manual）", result is AiModelConfigResolution.Missing)
    }

    // ------------------------------------------------------------ 5. 写入集合

    @Test
    fun selectionWrites_clearsTheApiKey() {
        val writes = AiModelConfigResolver.selectionWrites(
            config = preset("cfg-1", systemPrompt = "FROM-CONFIG", temperature = 0.35),
            ownedValueCopyKeys = setOf(
                AiModelConfigRef.PARAM_SYSTEM_PROMPT,
                AiModelConfigRef.PARAM_TEMPERATURE,
            ),
        )

        assertEquals(
            "引用后步骤里的 api_key 必须是空串（§1.7：密钥不再进工作流）",
            "",
            writes[AiModelConfigRef.PARAM_API_KEY],
        )
        assertEquals(AiModelConfigRef.SOURCE_REFERENCE, writes[AiModelConfigRef.PARAM_SOURCE])
        assertEquals("cfg-1", writes[AiModelConfigRef.PARAM_CONFIG_ID])
        assertEquals("FROM-CONFIG", writes[AiModelConfigRef.PARAM_SYSTEM_PROMPT])
        assertEquals(0.35, writes[AiModelConfigRef.PARAM_TEMPERATURE])
        assertEquals("只写这五个键（模块自己的其它参数一概不动）", 5, writes.size)
    }

    @Test
    fun selectionWrites_onlyWritesTheValueCopyKeysTheModuleOwns() {
        val writes = AiModelConfigResolver.selectionWrites(
            config = preset("cfg-1", systemPrompt = "FROM-CONFIG", temperature = 0.35),
            ownedValueCopyKeys = emptySet(),
        )

        assertEquals(
            "AutoGLM 两者都不拥有 ⇒ 不得凭空给它造出 system_prompt / temperature",
            3,
            writes.size,
        )
        assertFalse(writes.containsKey(AiModelConfigRef.PARAM_SYSTEM_PROMPT))
        assertFalse(writes.containsKey(AiModelConfigRef.PARAM_TEMPERATURE))
        assertEquals("", writes[AiModelConfigRef.PARAM_API_KEY])
    }

    @Test
    fun selectionWrites_writesOnlyTheOwnedSubsetWhenPartlyOwned() {
        val writes = AiModelConfigResolver.selectionWrites(
            config = preset("cfg-1", systemPrompt = "FROM-CONFIG", temperature = 0.35),
            ownedValueCopyKeys = setOf(AiModelConfigRef.PARAM_SYSTEM_PROMPT),
        )

        assertEquals(4, writes.size)
        assertEquals("FROM-CONFIG", writes[AiModelConfigRef.PARAM_SYSTEM_PROMPT])
        assertFalse(
            "AgentModule 没有 temperature 参数 ⇒ 不得写入",
            writes.containsKey(AiModelConfigRef.PARAM_TEMPERATURE),
        )
    }

    @Test
    fun manualWrites_onlyTouchesTheSource() {
        val writes = AiModelConfigResolver.manualWrites()
        assertEquals(
            "切回手动只改来源：不动任何既有值（含 model_config_id，便于来回切换）",
            mapOf(AiModelConfigRef.PARAM_SOURCE to AiModelConfigRef.SOURCE_MANUAL),
            writes,
        )
    }

    // ------------------------------------------------------------ 6. 参数形态 / 备份清洗

    @Test
    fun sourceInput_isAnEnumDefaultingToManualAndRejectsMagicVariables() {
        assertEquals(AiModelConfigRef.PARAM_SOURCE, AiModelConfigRef.SOURCE_INPUT.id)
        assertEquals("manual", AiModelConfigRef.SOURCE_INPUT.defaultValue)
        assertEquals(
            listOf(AiModelConfigRef.SOURCE_MANUAL, AiModelConfigRef.SOURCE_REFERENCE),
            AiModelConfigRef.SOURCE_INPUT.options,
        )
        assertFalse(
            "配置来源是编辑期决策，不接受变量",
            AiModelConfigRef.SOURCE_INPUT.acceptsMagicVariable,
        )
    }

    @Test
    fun configIdInput_isHiddenAndPlain() {
        assertEquals(AiModelConfigRef.PARAM_CONFIG_ID, AiModelConfigRef.CONFIG_ID_INPUT.id)
        assertEquals("", AiModelConfigRef.CONFIG_ID_INPUT.defaultValue)
        assertTrue("由 UIProvider 自绘选择器接管 ⇒ 通用表单里隐藏", AiModelConfigRef.CONFIG_ID_INPUT.isHidden)
        assertFalse(
            "配置 id 不是内容参数 ⇒ 不走富文本胶囊（从根上避开 supportsRichText 那个坑）",
            AiModelConfigRef.CONFIG_ID_INPUT.supportsRichText,
        )
    }

    @Test
    fun configIdOf_readsOnlyStringsAndToleratesMissingKeys() {
        assertEquals("", AiModelConfigRef.configIdOf(emptyMap()))
        assertEquals("", AiModelConfigRef.configIdOf(mapOf(AiModelConfigRef.PARAM_CONFIG_ID to 42)))
        assertEquals(
            "cfg-1",
            AiModelConfigRef.configIdOf(mapOf(AiModelConfigRef.PARAM_CONFIG_ID to "cfg-1")),
        )
    }

    @Test
    fun modelConfigIdIsNotScrubbedByTheBackupScrubber() {
        assertFalse(
            "引用 id 不是密钥 ⇒ 导出备份时必须原样保留（被清空会让引用静默失效）",
            SecretFieldScrubber.shouldScrub(AiModelConfigRef.PARAM_CONFIG_ID),
        )
        assertTrue(
            "对照：api_key 仍必须被清洗（证明这条断言的判据是活的）",
            SecretFieldScrubber.shouldScrub(AiModelConfigRef.PARAM_API_KEY),
        )
    }

    // ------------------------------------------------------------ 7. provider 映射

    @Test
    fun moduleProviderVocabularyMapsOntoTheTransportVocabulary() {
        assertEquals(ChatProvider.OPENAI, chatProviderForModuleProvider("openai"))
        assertEquals(ChatProvider.DEEPSEEK, chatProviderForModuleProvider("deepseek"))
        assertEquals(
            "模块自有的服务商词（custom / dashscope / bigmodel）一律落到 OpenAI 兼容档",
            ChatProvider.OPENAI,
            chatProviderForModuleProvider("custom"),
        )
        assertEquals(ChatProvider.OPENAI, chatProviderForModuleProvider("dashscope"))
        assertEquals(ChatProvider.OPENAI, chatProviderForModuleProvider("bigmodel"))
    }

    @Test
    fun manualModeKeepsTheModuleProviderVocabularyOutOfTheRequest() {
        val result = AiModelConfigResolver.resolve(
            parameters = mapOf(AiModelConfigRef.PARAM_SOURCE to AiModelConfigRef.SOURCE_MANUAL),
            presets = emptyList(),
            manual = manual(provider = "dashscope"),
        )
        val preset = resolved(result)
        assertNotNull(preset)
        assertEquals(
            "dashscope 不是传输层词表里的值 ⇒ 必须归一化（否则端点规整/认证头会走错分支）",
            ChatProvider.OPENAI.storageValue,
            preset.provider,
        )
    }
}
