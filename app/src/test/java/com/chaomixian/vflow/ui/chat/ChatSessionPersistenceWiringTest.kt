// 文件: test/java/com/chaomixian/vflow/ui/chat/ChatSessionPersistenceWiringTest.kt
package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线测试**：锁住 `ChatPresetRepository` 的会话持久化**确实接到了**
 * 新的会话存储层，且**没有再退回**那个既有缺陷。
 *
 * ## 为什么必须有这一条（而不是只测 `ChatConversationStore`）
 *
 * `ChatConversationStore` 的行为测试全绿**证明不了**接线接上了 ——
 * `ChatPresetRepository.getSessionState()` 完全可以继续写着
 * `runCatching { … }.getOrDefault(ChatSessionState())`，而所有 store 测试照样绿。
 * 本仓库在这类「纯函数测试全绿 ≠ 集成点接上了」上踩过两次
 * （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor` 零生产调用点）。
 *
 * ## ⚠️ 断言必须锚在**新增的表达式**上
 *
 * `store.load(` / `store.save(` / `loadIssues` / `R.string.toast_load_failed` 都是本次**新加**的；
 * 「不得出现 `getOrDefault(ChatSessionState())`」锚的是**被删掉的**缺陷写法。
 * 若锚在改造前就存在的标识符上（例如 `KEY_CHAT_SESSION_STATE_JSON`），断言会**恒真**。
 *
 * ⚠️ 扫描前**必须剥注释**（[SourceScan.stripped]）：本仓库的改动会在 KDoc 里大段引用
 * 被删掉的旧写法，只做 `contains` 的话「把真实调用删掉、只留注释」也一样绿。
 */
class ChatSessionPersistenceWiringTest {

    private val repositorySource = SourceScan.stripped(
        "src/main/java/com/chaomixian/vflow/ui/chat/ChatPresetRepository.kt"
    )

    private fun body(signature: String): String {
        val body = SourceScan.functionBody(repositorySource, signature)
        assertNotNull(
            "❌ 在 ChatPresetRepository 里找不到 `$signature` —— 函数改名了？" +
                "（此时下面的断言会全部落空）",
            body,
        )
        return body!!
    }

    @Test
    fun `getSessionState delegates to the conversation store`() {
        val body = body("fun getSessionState(")
        assertTrue(
            "getSessionState() 必须委托给 store.load()（每会话一文件 + 轻量索引的入口）",
            body.contains("store.load("),
        )
        assertTrue(
            "必须读 loadIssues —— 解析失败要能被看见，而不是静默返回空",
            body.contains("loadIssues"),
        )
        assertTrue(
            "解析失败必须走到「给用户可见提示」的那个出口",
            body.contains("notifySessionLoadIssueOnce("),
        )
    }

    @Test
    fun `a parse failure becomes a user visible toast that fires at most once`() {
        val notifier = body("private fun notifySessionLoadIssueOnce(")
        assertTrue(
            "提示文案必须复用既有三语字符串（本任务不得新增 res/ 资源）",
            notifier.contains("R.string.toast_load_failed"),
        )
        assertTrue("必须是 Toast（`ChatViewModel`/`ChatScreen` 属任务 ④，改不了）", notifier.contains("Toast.makeText"))
        assertTrue(
            "同一进程内最多弹一次（Activity 与悬浮窗会各建一个 repository）",
            notifier.contains("compareAndSet"),
        )
    }

    @Test
    fun `getSessionState no longer silently swallows a parse failure`() {
        val body = body("fun getSessionState(")
        assertFalse(
            "❌ 不得再用 getOrDefault(ChatSessionState()) —— " +
                "那正是「一处坏 JSON ⇒ 返回空 ⇒ 启动时把空状态写回 ⇒ 全部会话被抹掉」的写法",
            body.contains("getOrDefault(ChatSessionState())"),
        )
        assertFalse(
            "❌ 不得再直接读那个单键 blob（读路径已整体交给 store）",
            body.contains("KEY_CHAT_SESSION_STATE_JSON"),
        )
        assertFalse("❌ 不得再手写 Json 解码（交给 store）", body.contains("decodeFromString"))
    }

    @Test
    fun `saveSessionState delegates to the conversation store`() {
        val body = body("fun saveSessionState(")
        assertTrue(
            "saveSessionState() 必须委托给 store.save()（按会话 diff + 原子写）",
            body.contains("store.save("),
        )
        assertFalse(
            "❌ 不得再把整包状态序列化进单个 prefs 键（O(全部) 写放大）",
            body.contains("putString(KEY_CHAT_SESSION_STATE_JSON"),
        )
        assertFalse(
            "❌ 不得再无条件 remove 旧 blob —— 清空只能由 store 在「迁移已提交」后决定，" +
                "否则「解析失败但还没救回来」的数据会被直接删掉",
            body.contains("remove(KEY_CHAT_SESSION_STATE_JSON"),
        )
    }

    @Test
    fun `the repository builds the store from filesDir and the two prefs lambdas`() {
        assertTrue(
            "必须构造 ChatConversationStore（否则会话文件永远不会落盘）",
            repositorySource.contains("ChatConversationStore("),
        )
        assertTrue(
            "filesRoot 必须来自 filesDir（与备份 scope 读的是同一个目录）",
            repositorySource.contains("filesDir"),
        )
        assertTrue(repositorySource.contains("readLegacyBlob"))
        assertTrue(repositorySource.contains("clearLegacyBlob"))
        assertTrue(
            "清空旧 blob 必须用 commit() 的返回值判成败（edit{} 返回 Unit，判不了）",
            repositorySource.contains(".commit()"),
        )
    }

    @Test
    fun `the scan is not vacuous`() {
        assertTrue(
            "源码扫描没读到 ChatPresetRepository（路径变了？）",
            repositorySource.contains("class ChatPresetRepository"),
        )
        assertTrue(
            "getSessionState() 的函数体短得不像话 —— 大括号配对可能截错了",
            body("fun getSessionState(").length > 100,
        )
        assertTrue(
            "saveSessionState() 的函数体短得不像话 —— 大括号配对可能截错了",
            body("fun saveSessionState(").length > 100,
        )
    }
}
