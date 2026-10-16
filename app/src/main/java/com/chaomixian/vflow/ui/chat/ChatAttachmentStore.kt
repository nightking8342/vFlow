package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.chat.store.ChatConversationFiles
import com.chaomixian.vflow.core.chat.store.ChatConversationLayout
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 附件目录的索引（**唯一的「句柄 -> 文件」映射**）。
 *
 * `files` 的 key 是句柄（`artifact://<callId>/<key>`），value 是**文件名**
 * （不是路径 —— 路径随 `filesDir` 变化，文件名不会）。
 */
@Serializable
private data class ChatAttachmentIndex(
    val version: Int = ChatAttachmentStore.INDEX_VERSION,
    val files: Map<String, String> = emptyMap(),
)

/**
 * 附件**落盘 + 句柄索引**（fork：AI 能力统一 §3.9「引用即保存」）。
 *
 * ## 落什么
 *
 * | 来源 | 何时落盘 | 为什么要落 |
 * |---|---|---|
 * | 用户发的图（`source = USER`） | 发送前 | 相册选择器给的 `content://` 读权限是**临时**的（进程结束即失效）⇒ 不落盘则重启后既渲染不出来也发不出去 |
 * | 正文引用过的图（`artifact://…`） | assistant 收尾时 | 句柄只活在会话内存的 artifact store 里 ⇒ 不落盘则重启后正文里的引用永久破图 |
 *
 * **未被引用的工具产出图不落盘**（设计稿 §3.9.2）——它们只该待在会话内存里。
 *
 * ## 命名与去重
 *
 * 文件名 = sha256(bytes) 前 32 位十六进制 + 由 mime 决定的扩展名 ⇒ **内容寻址**，
 * 同一张图重复发送 / 重复引用只存一份。文件名只由 hex 与已知扩展名组成 ⇒ 天然无目录穿越；
 * 句柄只作索引的 **key**，**绝不**做文件名。
 *
 * ## 线程模型
 *
 * 写路径都在 IO 线程（`ChatViewModel` 的落盘点），但**读路径可能在 Main 的组合期**
 * （渲染解析）。故索引用 `@Volatile` 的**不可变快照**换入，调用方无需加锁；
 * 索引文件本身用 `synchronized` 串行化读-改-写。
 *
 * ## 失败姿态（照 `ChatConversationStore` 的教训）
 *
 * 索引解析失败 ⇒ 记 W、**当空表**，**绝不删文件** —— 「解析失败静默清空」是事故形态。
 * 落盘失败 ⇒ 返回路径（调用方拿到的是「可能读不到的 uri」）⇒ 模型侧退化为
 * `UNREADABLE` 占位文案，**不阻断发送**。
 *
 * ## 本任务不做清理（D22 未落地）
 *
 * 磁盘只增不减；删会话不删文件（内容寻址的文件可能被多个会话共享）。
 * 将来做淘汰时的依据就是本索引：它是唯一的「句柄 -> 文件」映射。
 */
internal class ChatAttachmentStore(
    private val attachmentsDir: File,
    /**
     * 复用批 1 的字节读取（`ChatImageResolver.kt` 的 [ChatImageByteSource]；
     * 生产实现 = [UriChatImageByteSource]，`file://` 与 `content://` 都吃）。
     */
    private val byteSource: ChatImageByteSource = UriChatImageByteSource,
    private val log: (String) -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 索引的读-改-写串行化（写路径可能来自多个 IO 协程）。 */
    private val indexLock = Any()

    @Volatile
    private var handleToFileName: Map<String, String> = emptyMap()

    init {
        loadIndex()
    }

    /**
     * 从 uri 读字节 -> 落盘 -> 返回**绝对路径**；读不到返回 `null`（调用方保留原 uri）。
     *
     * `handle` 非空时同时登记「句柄 -> 文件」，使正文里的 `artifact://…` 引用
     * 在**重启后**仍能解析到真实文件。
     */
    fun persistFromUri(uri: String, mimeType: String, handle: String? = null): String? {
        val bytes = byteSource.readBytes(
            ChatImageAttachment(uri = uri, mimeType = mimeType)
        ) ?: run {
            log("Could not read image bytes; keeping the original uri so the model sees an unreadable placeholder.")
            return null
        }
        return persistBytes(bytes, mimeType, handle)
    }

    /** 已知字节：落盘并返回绝对路径（**已有同名文件时直接复用** —— 内容寻址去重）。 */
    fun persistBytes(bytes: ByteArray, mimeType: String, handle: String? = null): String {
        val fileName = contentFileName(bytes, mimeType)
        val target = File(attachmentsDir, fileName)
        synchronized(indexLock) {
            if (!target.isFile) {
                if (!attachmentsDir.isDirectory && !attachmentsDir.mkdirs() && !attachmentsDir.isDirectory) {
                    log("Could not create the attachments directory: " + attachmentsDir.absolutePath)
                } else {
                    val written = runCatching { target.writeBytes(bytes) }.isSuccess
                    if (!written) {
                        log("Could not write the attachment file: " + target.absolutePath)
                    }
                }
            }
            if (handle != null && handleToFileName[handle] != fileName) {
                handleToFileName = handleToFileName + (handle to fileName)
                writeIndex()
            }
        }
        return target.absolutePath
    }

    /** 句柄 -> 已落盘文件的绝对路径；没落过盘 / 索引坏了 / 文件已不在了 ⇒ `null`。 */
    fun resolveHandle(handle: String): String? {
        val fileName = handleToFileName[handle] ?: return null
        val file = File(attachmentsDir, fileName)
        return if (file.isFile) file.absolutePath else null
    }

    /**
     * uri 是否已经在本附件目录内（**幂等判据**）。
     *
     * 用它避免「同一条消息每轮请求都被复制一次」：`persistFromUri` 是内容寻址的，
     * 重复调用虽然不会产生新文件，但会白读一遍字节。
     */
    fun isInside(uri: String): Boolean {
        val path = uri.trim().removePrefix("file://")
        if (path.isBlank()) return false
        val dir = attachmentsDir.absolutePath.trimEnd(File.separatorChar)
        return File(path).absolutePath.startsWith(dir + File.separatorChar)
    }

    fun fileFor(fileName: String): File = File(attachmentsDir, fileName)

    // ── 索引 ────────────────────────────────────────────────

    private fun indexFile(): File = File(attachmentsDir, INDEX_FILE_NAME)

    private fun loadIndex() {
        val file = indexFile()
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) {
            log("Attachment index is missing or empty; starting from an empty mapping: " + file.absolutePath)
            return
        }
        val parsed = runCatching { json.decodeFromString<ChatAttachmentIndex>(text) }.getOrNull()
        if (parsed == null) {
            // 只记 W、当空表，**绝不删文件**（照 ChatConversationStore 的教训）。
            log("Attachment index could not be parsed; starting from an empty mapping and keeping every file: " + file.absolutePath)
            return
        }
        if (parsed.version > INDEX_VERSION) {
            log("Attachment index version " + parsed.version + " is newer than " + INDEX_VERSION + "; loading it best-effort.")
        }
        handleToFileName = parsed.files.toMap()
    }

    private fun writeIndex() {
        val text = json.encodeToString(
            ChatAttachmentIndex(version = INDEX_VERSION, files = handleToFileName)
        )
        if (!ChatConversationFiles.writeAtomically(indexFile(), text)) {
            log("Could not write the attachment index; it will be rewritten on the next persist.")
        }
    }

    companion object {
        /**
         * 附件目录名。与 `core/chat/store/ChatConversationLayout` 的布局注释一致
         * （`filesDir/chat/attachments/`，与会话目录平级）—— 那个文件本任务只读，
         * 故常量落在这里。
         */
        const val ATTACHMENTS_DIR = "attachments"

        const val INDEX_FILE_NAME = "index.json"

        const val INDEX_VERSION = 1

        /** 文件名里保留的 sha256 十六进制字符数（32 字符 = 128 bit，碰撞概率可忽略）。 */
        private const val CONTENT_HASH_HEX_CHARS = 32

        /** 纯函数：sha256 前 32 位十六进制 + 扩展名（内容寻址 ⇒ 天然去重）。 */
        fun contentFileName(bytes: ByteArray, mimeType: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            return hex.take(CONTENT_HASH_HEX_CHARS) + "." + extensionFor(mimeType)
        }

        /** 由 mime 决定扩展名（未知 ⇒ `jpg`，与 `ChatImageResolver` 的重编码出口一致）。 */
        fun extensionFor(mimeType: String): String =
            when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }

        /** 生产入口：`filesDir` ⇒ `filesDir/chat/attachments/`。 */
        fun open(filesRoot: File): ChatAttachmentStore =
            ChatAttachmentStore(File(ChatConversationLayout.root(filesRoot), ATTACHMENTS_DIR))
    }
}
