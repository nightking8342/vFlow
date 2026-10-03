// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/ChatScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.AbstractPrefsScope
import com.chaomixian.vflow.core.backup.BackupPrefsSpec
import com.chaomixian.vflow.core.backup.ScopeGroup

/**
 * 备份/恢复**聊天与 AI 生成工作流的配置**（不含任何凭证）。
 *
 * ## ⚠️⚠️ 为什么 `defaultIncluded = false`
 *
 * 有**两个独立的理由**，不要合并成一句（它们的处置完全不同）：
 *
 * 1. **体积** —— `chat_session_state_json` 是**会话历史**，是四类里唯一会显著撑大
 *    备份体积的数据。默认勾上会让用户在不知不觉中把几十 MB 的对话写进**每一份**备份。
 * 2. **隐私** —— 对话历史本身敏感，而「换机要不要带过去」因人而异。
 *
 * ⚠️ 这与 `secrets` 的「敏感，需口令」**不是同一件事**：
 * `secrets` 是「**要口令才能带**」，本 scope 是「**默认不带，但带不需要口令**」。
 * 写成一句会让读者以为本 scope 也要口令。
 *
 * ## 键 → 收 / 不收 → 原因
 *
 * ### 收（7 键）
 *
 * | prefs | 键 | 类型 | 原因 |
 * |---|---|---|---|
 * | `module_config_prefs` | `chat_default_preset_id` | S | 默认聊天预设的选择 |
 * | `module_config_prefs` | `chat_auto_approve_tools` | B | 工具自动审批开关（**不含凭证**，是策略） |
 * | `module_config_prefs` | `chat_auto_approval_scope` | S | 自动审批范围（策略） |
 * | `chat_session_prefs` | `chat_session_state_json` | S | 会话历史 |
 * | `ai_config` | `provider` | S | AI 生成工作流的服务商选择 |
 * | `ai_config` | `base_url` | S | 自建端点地址（**不是凭证**） |
 * | `ai_config` | `model` | S | 模型名 |
 *
 * ### 不收
 *
 * | prefs | 键 | 原因 |
 * |---|---|---|
 * | `module_config_prefs` | `chat_presets_json` | ⚠️ **含 `apiKey`** ⇒ 已在 `SecretsScope.SECRET_SLOTS`。放进普通 scope = 绕过「包含密钥」勾选 ⇒ **明文泄漏** |
 * | `module_config_prefs` | `chat_provider_configs_json` | 同上（含 `apiKey`） |
 * | `ai_config` | `api_key` | 同上 |
 * | `module_config_prefs` | `chat_session_state_json` | **旧位置**：`ChatPresetRepository.saveSessionState` 会把 `chat_session_prefs` 写好后**主动 remove 掉 `module_config_prefs` 里的这份**。收它等于把已被迁移掉的旧键重新写回 ⇒ 与读侧的兼容分支打架 |
 */
class ChatScope : AbstractPrefsScope() {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.CONFIG
    override val sensitive: Boolean = false

    /** 体积 + 隐私，两个理由见类 KDoc。 */
    override val defaultIncluded: Boolean = false

    override val importOrder: Int = 50
    override val dependsOn: List<String> = emptyList()

    override val scopeTag: String = TAG

    override val specs: List<BackupPrefsSpec> = listOf(
        BackupPrefsSpec(
            MODULE_CONFIG_PREFS,
            exactKeys = setOf(
                "chat_default_preset_id",
                "chat_auto_approve_tools",
                "chat_auto_approval_scope",
            )
        ),
        BackupPrefsSpec(
            CHAT_SESSION_PREFS,
            exactKeys = setOf("chat_session_state_json")
        ),
        BackupPrefsSpec(
            AI_CONFIG_PREFS,
            exactKeys = setOf("provider", "base_url", "model")
        ),
    )

    companion object {
        const val ID = "chat"

        private const val TAG = "ChatScope"

        /** 与 `ModuleConfigActivity.PREFS_NAME` 逐字一致。 */
        private const val MODULE_CONFIG_PREFS = "module_config_prefs"

        /** 与 `ChatPresetRepository.CHAT_SESSION_PREFS_NAME` 逐字一致。 */
        private const val CHAT_SESSION_PREFS = "chat_session_prefs"

        /** 与 `AiGenerationSheet` 的裸字面量逐字一致。 */
        private const val AI_CONFIG_PREFS = "ai_config"

        val MODULE_CONFIG_KEYS: Set<String> = setOf(
            "chat_default_preset_id",
            "chat_auto_approve_tools",
            "chat_auto_approval_scope",
        )
        val SESSION_KEYS: Set<String> = setOf("chat_session_state_json")
        val AI_KEYS: Set<String> = setOf("provider", "base_url", "model")
    }
}
