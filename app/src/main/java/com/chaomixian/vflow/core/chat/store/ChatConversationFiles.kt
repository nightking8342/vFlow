// 文件: main/java/com/chaomixian/vflow/core/chat/store/ChatConversationFiles.kt
package com.chaomixian.vflow.core.chat.store

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 会话文件的**文件级原语**（纯 JVM，无 `android.*`）—— 枚举 / 原子写 / 删。
 *
 * ## 为什么单独一个对象而不是让 store 与备份 scope 各写一份
 *
 * 会话文件有**两个**写入点：`ui/chat/ChatConversationStore`（日常落盘）与
 * `core/backup/scopes/ChatScope`（备份导入）。两处都必须：
 * ① 写临时文件再 rename（否则掉电/被杀进程会留下半个 JSON ⇒ 整个会话读不出来）；
 * ② 用 `java.nio.file.Files.move` 而**不是** `File.renameTo`
 * （Windows 上 `renameTo` 到已存在目标**静默失败**，而本项目在 Windows 上跑单测）。
 *
 * 各写一份的话，「哪一份没做原子写」只能靠人肉审查发现，而后果是**数据损坏**。
 * 收敛到一处 ⇒ 只有一个地方可能写错，且它被单测直接覆盖。
 *
 * ⚠️ 本文件**不认识** `ChatConversation`（那是 `ui/chat` 的类型）：它只搬文本。
 * 备份 scope 因此可以把会话文件当**不透明 JSON** 搬运，不必 import `ui.chat`。
 */
object ChatConversationFiles {

    /**
     * 会话目录下全部 `<id>.json`（**按 id 字典序**，让导出/索引顺序稳定可断言）。
     *
     * - `.json.tmp`（原子写临时文件）与一切非 `.json` 结尾的文件都被忽略；
     * - 目录不存在 ⇒ 空列表（**不是** null）；
     * - **不做** id 安全判定 —— 那是调用方的事（判定不通过时调用方要记 W 而不是静默丢弃）。
     */
    fun conversationFiles(filesRoot: File): List<Pair<String, File>> {
        val dir = ChatConversationLayout.conversationsDir(filesRoot)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                ChatConversationLayout.conversationIdOf(file.name)?.let { id -> id to file }
            }
            .sortedBy { (id, _) -> id }
            .toList()
    }

    /**
     * **原子写**：写 `<name>.tmp` → `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`。
     *
     * 不支持原子移动（跨文件系统 / 个别文件系统）时降级为
     * `Files.move(REPLACE_EXISTING)` —— 它仍然覆盖目标，只是少了「要么旧要么新」的保证，
     * 比直接失败更符合用户预期（写不进去 = 静默丢数据）。
     *
     * @return 成功与否。**失败不抛**（调用方要记 W 并保留旧数据，而不是让整次保存崩掉）。
     */
    fun writeAtomically(target: File, text: String): Boolean {
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return false
        }
        val temp = File(dir, target.name + ChatConversationLayout.TEMP_SUFFIX)
        return try {
            temp.writeText(text)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: IOException) {
            temp.delete()
            false
        }
    }

    /**
     * 删除文件（**不抛**）。
     *
     * 「本来就不存在」也算成功 —— 删除的语义是「结束之后它不该在」，
     * 而调用方（会话 diff）需要的是这个结果，不是「我删了几个」。
     */
    fun deleteQuietly(file: File): Boolean = !file.exists() || file.delete()
}
