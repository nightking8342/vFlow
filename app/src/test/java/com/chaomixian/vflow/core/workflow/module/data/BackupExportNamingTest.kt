// 文件: test/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportNamingTest.kt
package com.chaomixian.vflow.core.workflow.module.data

import com.chaomixian.vflow.core.backup.SecretFieldScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **参数命名锁** —— 为什么口令参数必须叫 `backup_password` 而不是 `passphrase`。
 *
 * ## 背景（这不是风格问题，是数据泄漏）
 *
 * 工作流模块的参数会随 `WorkflowScope` 一起进备份文件。T2 的
 * [SecretFieldScrubber] 在「不包含密钥」时把命中的参数**清成空串**，
 * 它的判定规则是：
 *
 * - 子串命中集：`token` / `secret` / `password` / `device_key` / `api_key`
 * - 排除集：`page_token` / `key_code` / `key_encoding` / `key_action` / `auth_mode`
 * - 精确集：`key`
 *
 * ⇒ `passphrase` **一个都不命中**（`pass` 不在子串集里），于是会出现极讽刺的失效：
 * **用来加密别人口令的那个口令，自己明文躺进同一份备份里**。
 *
 * ⇒ `backup_password` 含子串 `password`、不在排除集 ⇒ 被清洗。
 *
 * ## ⚠️ 这两条断言是成对的，别删第二条
 *
 * 第二条是**反向锁**：它证明「为什么必须避开 passphrase」不是凭空的说法。
 * 若哪天有人放宽了 `SecretFieldScrubber` 让它认 `passphrase`，第二条会变红 ——
 * 那正是应该回来重新评估这条命名约束的信号，而不是默默让它过去。
 */
class BackupExportNamingTest {

    @Test
    fun `the passphrase parameter id is scrubbed by the secret scrubber`() {
        val input = BackupExportModule().getInputs().first { it.id.contains("password") }
        assertEquals("backup_password", input.id)
        assertTrue(
            "❌ 模块的口令参数 id 必须命中 SecretFieldScrubber.shouldScrub —— " +
                "否则口令会明文进备份文件",
            SecretFieldScrubber.shouldScrub(input.id),
        )
    }

    @Test
    fun `a passphrase named parameter would not be scrubbed`() {
        // ⚠️ 反向锁：把 id 写成 passphrase 就会被漏掉。
        //     这条断言让「为什么必须叫 backup_password」有机器化证据。
        assertFalse(
            "SecretFieldScrubber 现在会清洗 passphrase 了？" +
                "若是刻意放宽，请重新评估 BackupExportModule 的命名约束与这条测试",
            SecretFieldScrubber.shouldScrub("passphrase"),
        )
    }
}
