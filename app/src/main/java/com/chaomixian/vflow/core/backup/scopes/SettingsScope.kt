// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/SettingsScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.AbstractPrefsScope
import com.chaomixian.vflow.core.backup.BackupPrefsSpec
import com.chaomixian.vflow.core.backup.ScopeGroup

/**
 * 备份/恢复**应用设置**（`vFlowPrefs`）。
 *
 * ## 键 → 收 / 不收 → 原因
 *
 * ### 收（20 键）
 *
 * | 键 | 类型 | 原因 |
 * |---|---|---|
 * | `dynamicColorEnabled` | B | 外观：动态取色 |
 * | `colorfulWorkflowCardsEnabled` | B | 外观：彩色工作流卡片 |
 * | `appScale` | F | 外观：界面缩放 |
 * | `liquidGlassNavBarEnabled` | B | 外观：导航栏液态玻璃 |
 * | `workflow_sort_mode` | S | 列表偏好：排序方式 |
 * | `workflow_layout_mode` | S | 列表偏好：布局方式 |
 * | `hideFromRecents` | B | 外观/隐私偏好（从最近任务隐藏） |
 * | `enableTypeFilter` | B | 编辑器行为：类型过滤 |
 * | `allowShowOnLockScreen` | B | 悬浮窗行为 |
 * | `allowPopupKeepScreenOn` | B | 悬浮窗行为 |
 * | `keepDeviceAwakeDuringWorkflow` | B | 执行行为 |
 * | `defaultErrorPolicy` | S | 工作流默认错误策略 |
 * | `defaultRetryCount` | I | 工作流默认重试次数 |
 * | `defaultRetryInterval` | I | 工作流默认重试间隔 |
 * | `progressNotificationEnabled` | B | 通知偏好 |
 * | `backgroundServiceNotificationEnabled` | B | 通知偏好 |
 * | `autoCheckUpdatesEnabled` | B | 更新偏好 |
 * | `telemetryEnabled` | B | 隐私偏好（用户显式选择过） |
 * | `accessibilityDisguiseEnabled` | B | 无障碍服务的**显示名伪装**，纯显示偏好，与授权无关 |
 * | `sherpa_ncnn_download_source` | S | 模型下载源偏好 |
 *
 * ### 不收
 *
 * | 键 | 原因 |
 * |---|---|
 * | `is_first_run` | ⚠️ **硬排除**：首次运行标记是「本设备本用户」的状态，跨设备复制等于跳过新设备用户的引导 |
 * | `disclaimer_accepted` | ⚠️ **硬排除**：免责声明的「已接受」是**对当前设备/用户**的确认。跨设备复制 = 替别人接受了一份法律声明。**合规问题，非偏好问题** |
 * | `default_shell_mode` | 与设备的 root/Shizuku 可用性强绑定；换机后可能指向不存在的通道 ⇒ 静默失效 |
 * | `preferred_core_launch_mode` | Core 进程启动方式，设备本地 |
 * | `core_auto_start_enabled` | Core 自启，设备本地 |
 * | `mutual_keep_alive_enabled` | Core 互保活，设备本地 |
 * | `core_manual_stop_requested` | Core 运行态 |
 * | `core_unix_socket_enabled` | Core 通信方式，设备本地 |
 * | `core_last_launched_dex_fingerprint` | ⚠️ **硬排除**：设备本地的 dex 指纹。恢复它会让「Core 代码变了需重启」的提示**永远不出现** ⇒ 用户静默跑旧 Core 代码（本仓库已为此踩过三次） |
 * | `forceKeepAliveEnabled` | 保活开关依赖设备上的电池优化白名单/权限，跨设备恢复会得到「开了但不生效」 |
 * | `autoEnableAccessibility` | 开机自动开无障碍；新设备未授权 ⇒ 静默失效 |
 * | `accessibilityGuardEnabled` | 无障碍守护；同上，与授权态绑定 |
 * | `debugLoggingEnabled` | 诊断态开关，不是用户配置；跨设备无意义（且会无谓地放大日志量） |
 * | `vFlowLogPrefs` 的 `execution_logs` | 执行日志是运行产物不是配置，且可能很大 ⇒ **整个 `vFlowLogPrefs` 不进本 scope** |
 * | `vflow_api` 的 `port` / `enabled` | 本地 Web 服务的运行态端口/开关 |
 * | **任何与设备标识绑定的键** | ⚠️ **已实测核查：本仓库目前不存在这类键** —— 全仓 grep `device_id` / `device_token` / `push_token` / `registration_id` / `install_id` / `fcm_token` / `oaid` / `imei` / `android_id` / `machine_id`（含 `-i`）**零命中**；友盟（`TelemetryManager`）的设备标识由其 SDK 自己管，**不落本 App 的 prefs**。⇒ 本类**当前为空**。**但保留这条规则**：`BackupPrefsLeakTest` 里有一条「不含 `device_id`/`device_token`/`push_token`/`install_id`/`oaid` 形状的键」的断言，将来新增集成时由它拦下（这类键的读写点通常在远程推送 SDK 里，最容易顺手写进 prefs） |
 */
class SettingsScope : AbstractPrefsScope() {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.CONFIG
    override val sensitive: Boolean = false
    override val defaultIncluded: Boolean = true
    override val importOrder: Int = 40
    override val dependsOn: List<String> = emptyList()

    override val scopeTag: String = TAG

    override val specs: List<BackupPrefsSpec> = listOf(
        BackupPrefsSpec(PREFS_NAME, EXACT_KEYS)
    )

    companion object {
        const val ID = "settings"

        private const val TAG = "SettingsScope"

        /** ⚠️ 与 `ThemeUtils` / `AppearanceManager` 等处的裸字面量逐字一致（已逐处 grep 核实）。 */
        private const val PREFS_NAME = "vFlowPrefs"

        val EXACT_KEYS: Set<String> = setOf(
            "dynamicColorEnabled",
            "colorfulWorkflowCardsEnabled",
            "appScale",
            "liquidGlassNavBarEnabled",
            "workflow_sort_mode",
            "workflow_layout_mode",
            "hideFromRecents",
            "enableTypeFilter",
            "allowShowOnLockScreen",
            "allowPopupKeepScreenOn",
            "keepDeviceAwakeDuringWorkflow",
            "defaultErrorPolicy",
            "defaultRetryCount",
            "defaultRetryInterval",
            "progressNotificationEnabled",
            "backgroundServiceNotificationEnabled",
            "autoCheckUpdatesEnabled",
            "telemetryEnabled",
            "accessibilityDisguiseEnabled",
            "sherpa_ncnn_download_source",
        )
    }
}
