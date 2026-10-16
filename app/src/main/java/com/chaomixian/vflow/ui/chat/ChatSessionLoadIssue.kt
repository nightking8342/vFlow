// 文件: main/java/com/chaomixian/vflow/ui/chat/ChatSessionLoadIssue.kt
package com.chaomixian.vflow.ui.chat

/**
 * 会话加载期**发生了什么问题** —— 存在的理由是「**不再静默**」。
 *
 * ## 它修的是哪个既有缺陷
 *
 * 改造前 `ChatPresetRepository.getSessionState()` 是
 * `runCatching { decode(raw) }.getOrDefault(ChatSessionState())`：
 * 一处 JSON 坏掉 ⇒ 返回**空状态** ⇒ 紧接着 `ChatViewModel` 的
 * `init → persistSessionState()` 把空状态**写回** ⇒ 全部会话被抹掉，
 * 而用户**看不到任何提示**（本项目没有版本历史、不可恢复）。
 *
 * 现在：坏数据**原样留存**（复制一份进 `filesDir/chat/quarantine/`，不改写原文），
 * 加载**尽力而为**（好的会话照常返回），并产出一条 [ChatSessionLoadIssue]。
 * 调用方（`ChatPresetRepository.getSessionState`）据此记日志 + 弹一次可见提示。
 */
enum class ChatSessionLoadIssueKind {
    /** 迁移前的旧 blob（`chat_session_prefs.chat_session_state_json`）解析失败。**绝不据此清空**。 */
    LEGACY_BLOB_UNPARSEABLE,

    /** `chat/conversations/<id>.json` 之一解析失败。该文件被隔离，其余照常加载。 */
    CONVERSATION_FILE_UNPARSEABLE,

    /** `chat/index.json` 解析失败。**不丢任何会话** —— 索引本来就不是加载真源。 */
    INDEX_UNPARSEABLE,

    /**
     * `chat/benchmark_runs.json` 解析失败。
     *
     * ⚠️ 方案 §4.2 的枚举只列了前三条；这一条是**补充**：benchmark 记录与旧 blob
     * 里的会话**同属一份被静默清空过的数据**，对它「解析失败 ⇒ 安静地当成空」
     * 与本次要修的缺陷是同一个形态（只是体积小一号）。
     */
    BENCHMARK_RUNS_UNPARSEABLE,
}

/**
 * 一次加载中产生的一条问题记录。
 *
 * @param kind 问题类别（决定日志级别与用户提示文案）。
 * @param detail 给人看的一句话（含文件名/原因），进日志与提示。
 * @param quarantinedPath 坏数据留存副本的绝对路径；留存失败时为 null。
 */
data class ChatSessionLoadIssue(
    val kind: ChatSessionLoadIssueKind,
    val detail: String,
    val quarantinedPath: String? = null,
)
