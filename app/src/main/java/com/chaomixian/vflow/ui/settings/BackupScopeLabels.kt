// 文件: main/java/com/chaomixian/vflow/ui/settings/BackupScopeLabels.kt
// 描述: 备份范围 id → 显示名（fork 新增）。
package com.chaomixian.vflow.ui.settings

import com.chaomixian.vflow.R

/**
 * scope id → 名称字符串资源。
 *
 * ## ⚠️⚠️ 本文件**不是**第二份「范围清单」
 *
 * 「有哪些 scope」这件事**只有一个来源**：`BackupScopeRegistry.all()`。
 * 这里只负责把已有的 id 翻译成人话。
 *
 * 两者混同（在这里列出范围、UI 只读这里）就是静默失效点 13：
 * 新加的 scope 永远不会出现在任何勾选界面里，而且**没有任何报错** ——
 * 用户只会觉得「怎么没有 xx 的备份选项」。
 *
 * 因此：**新增 scope 忘了加这一行**的后果仅仅是「该 scope 显示成 id 原文」，
 * 它照样会出现、照样能勾、照样会备份。这是刻意设计成**保守失效**的。
 *
 * ⚠️ 单独成文件的原因：让 `BackupRestoreScreen.kt` / `BackupExportUIProvider.kt`
 * 这两个「决定显示什么」的文件里**一个 scope id 字面量都没有**，
 * 从而能被 `BackupRestoreWiringTest` 以机械方式断言（硬编码第二份清单这条）。
 */
internal fun backupScopeLabelRes(id: String): Int = when (id) {
    "folders" -> R.string.backup_scope_folder
    "global_variables" -> R.string.backup_scope_global_variable
    "workflows" -> R.string.backup_scope_workflow
    "secrets" -> R.string.backup_scope_secrets
    // T6 追加的四个范围（else 分支保持不变 ⇒ 漏加只退化成显示「未知范围」）。
    "settings" -> R.string.backup_scope_settings
    "chat" -> R.string.backup_scope_chat
    "modules" -> R.string.backup_scope_modules
    "tiles" -> R.string.backup_scope_tiles
    else -> R.string.backup_scope_unknown
}
