// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/ModuleScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.AbstractPrefsScope
import com.chaomixian.vflow.core.backup.BackupPrefsSpec
import com.chaomixian.vflow.core.backup.ScopeGroup

/**
 * 备份/恢复**模块级配置**（触发器灵敏度、声纹模板、扩展模块启用开关）。
 *
 * ⚠️ 本 scope **不含飞书的任何键** —— 凭证在 `SecretsScope`，非凭证的
 * `feishu_app_id` / `*_expires_at` / `*_redirect_uri` / `*_scope` 也**整组不收**。
 * 理由：避免「一半在 secrets、一半在 modules」的割裂（排障时看不出飞书配置到底备没备）。
 *
 * ## 键 → 收 / 不收 → 原因
 *
 * ### 收（13 精确键 + 1 前缀）
 *
 * | prefs | 键 | 类型 | 原因 |
 * |---|---|---|---|
 * | `module_config_prefs` | `backtap_sensitivity` | I | 敲击模块灵敏度 |
 * | `module_config_prefs` | `app_start_close_check_delay` | L | 应用启动触发器的判定延迟 |
 * | `module_config_prefs` | `app_start_verification_delay` | L | 同上 |
 * | `module_config_prefs` | `app_start_min_check_interval` | L | 同上 |
 * | `module_config_prefs` | `screen_operation_pointer_enabled` | B | 屏幕操作指针显示开关 |
 * | `module_config_prefs` | `screen_operation_pointer_style` | S | 指针样式 |
 * | `module_config_prefs` | `voice_trigger_download_source` | S | 语音触发器模型下载源 |
 * | `module_config_prefs` | `voice_trigger_audio_source` | S | 语音触发器音频源 |
 * | `module_config_prefs` | `voice_trigger_retrigger_cooldown_ms` | I | 语音触发器冷却 |
 * | `module_config_prefs` | `voice_trigger_similarity_threshold_percent` | I | 语音触发器相似度阈值 |
 * | `module_config_prefs` | `voice_trigger_template_1/2/3` | S | 用户录制的声纹模板（**用户数据**，不是凭证） |
 * | `external_module_providers` | `known_providers` | Set\<String\> | 已安装的扩展模块包名集合 |
 * | `external_module_providers` | 前缀 `provider_enabled_` | B | 各扩展模块的启用开关。**键名动态（含包名）** ⇒ 用前缀规则；键空间有界（= 已安装扩展数），人工判定过 |
 *
 * ### 不收
 *
 * | prefs | 键 | 原因 |
 * |---|---|---|
 * | `module_config_prefs` | `network_proxy` | ⚠️ **可能内嵌 `user:pass@host` 凭据**，而现有 `SecretFieldScrubber` 只覆盖工作流参数、**不覆盖 prefs** ⇒ 明确排除。若要收，必须先在导出侧剥掉 userinfo（属独立改动） |
 * | `module_config_prefs` | `feishu_app_secret` / `feishu_app_access_token` / `feishu_access_token` / `feishu_user_access_token` / `feishu_user_refresh_token` / `feishu_user_auth_code` / `feishu_user_code_verifier` | **凭证** ⇒ 已在 `SecretsScope` |
 * | `module_config_prefs` | `feishu_app_id` / `feishu_app_access_token_expires_at` / `feishu_access_token_expires_at` / `feishu_user_access_token_expires_at` / `feishu_user_refresh_token_expires_at` / `feishu_user_redirect_uri` / `feishu_user_scope` | 非凭证，但整组属飞书集成，且 `expires_at` 是无意义的衍生值。**整组不收**（见类 KDoc）。⚠️ 注意 `feishu_app_id` 与 `feishu_user_scope` **不含**「token/secret/password/api_key/device_key」任一子串，故不是靠形状规则排除的，而是靠「整组不收」这条明确决策 |
 * | `recent_modules_prefs` | `recent_modules` | 「最近使用」缓存，**可再生** |
 * | `flashlight_prefs` | `last_flashlight_state` | 手电筒**当前开关状态**，是运行态不是配置（恢复它会在导入瞬间点亮/熄灭手电筒） |
 * | `vflow_do_not_disturb` | `automatic_zen_rule_id` | 本机 Zen rule 的 id，**跨设备无效**；且由 vFlow 自己创建/清理，属运行态 |
 * | `location_trigger_geofence_states` | `fenceId`（动态键名） | 围栏**进出状态**，运行态 + 键空间无界 ⇒ 整个 prefs 不进本 scope |
 */
class ModuleScope : AbstractPrefsScope() {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.CONFIG
    override val sensitive: Boolean = false
    override val defaultIncluded: Boolean = true
    override val importOrder: Int = 8
    override val dependsOn: List<String> = emptyList()

    override val scopeTag: String = TAG

    override val specs: List<BackupPrefsSpec> = listOf(
        BackupPrefsSpec(MODULE_CONFIG_PREFS, exactKeys = MODULE_KEYS),
        BackupPrefsSpec(
            EXTERNAL_PROVIDER_PREFS,
            exactKeys = setOf("known_providers"),
            keyPrefixes = setOf("provider_enabled_")
        ),
    )

    companion object {
        const val ID = "modules"

        private const val TAG = "ModuleScope"

        private const val MODULE_CONFIG_PREFS = "module_config_prefs"
        private const val EXTERNAL_PROVIDER_PREFS = "external_module_providers"

        val MODULE_KEYS: Set<String> = setOf(
            "backtap_sensitivity",
            "app_start_close_check_delay",
            "app_start_verification_delay",
            "app_start_min_check_interval",
            "screen_operation_pointer_enabled",
            "screen_operation_pointer_style",
            "voice_trigger_download_source",
            "voice_trigger_audio_source",
            "voice_trigger_retrigger_cooldown_ms",
            "voice_trigger_similarity_threshold_percent",
            "voice_trigger_template_1",
            "voice_trigger_template_2",
            "voice_trigger_template_3",
        )
    }
}
