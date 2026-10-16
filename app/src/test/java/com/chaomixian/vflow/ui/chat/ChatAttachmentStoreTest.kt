package com.chaomixian.vflow.ui.chat

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 附件**落盘 + 句柄索引**（临时目录，纯 JVM）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 [ChatAttachmentStore.contentFileName] 改成 `UUID.randomUUID()` 前缀（去掉内容寻址）⇒
 *    [identicalBytes_landInTheSameFile] **变红**；
 * 2. 去掉 [ChatAttachmentStore.writeIndex] 的调用（只留内存表）⇒
 *    [aFreshOpen_stillResolvesTheHandle] **变红**。
 */
class ChatAttachmentStoreTest {

    private lateinit var filesRoot: File

    /** uri -> 字节。生产实现是 `UriChatImageByteSource`，单测里不碰 Android。 */
    private class MapByteSource(private val bytesByUri: Map<String, ByteArray>) : ChatImageByteSource {
        override fun readBytes(attachment: ChatImageAttachment): ByteArray? = bytesByUri[attachment.uri]
    }

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val otherPngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9, 9)

    private fun storeWith(vararg pairs: Pair<String, ByteArray>): ChatAttachmentStore =
        ChatAttachmentStore(
            attachmentsDir = File(filesRoot, "chat/attachments"),
            byteSource = MapByteSource(pairs.toMap()),
        )

    @Before
    fun setUp() {
        filesRoot = Files.createTempDirectory("vflow-chat-attachments").toFile()
    }

    @After
    fun tearDown() {
        filesRoot.deleteRecursively()
    }

    @Test
    fun identicalBytes_landInTheSameFile() {
        val store = storeWith("content://picker/a" to pngBytes, "content://picker/b" to pngBytes)

        val first = store.persistFromUri("content://picker/a", "image/png")
        val second = store.persistFromUri("content://picker/b", "image/png")

        assertNotNull(first)
        assertEquals("内容寻址必须去重：同样的字节只该有一个文件", first, second)
        val files = File(first!!).parentFile!!.listFiles()!!.filter { it.name.endsWith(".png") }
        assertEquals(1, files.size)
    }

    @Test
    fun differentBytes_landInDifferentFiles() {
        val store = storeWith("content://picker/a" to pngBytes, "content://picker/b" to otherPngBytes)

        val first = store.persistFromUri("content://picker/a", "image/png")
        val second = store.persistFromUri("content://picker/b", "image/png")

        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first == second)
    }

    @Test
    fun resolveHandle_worksInTheSameInstance_andSurvivesAReopen() {
        val store = storeWith("file:///tmp/screenshot.png" to pngBytes)
        val path = store.persistFromUri(
            uri = "file:///tmp/screenshot.png",
            mimeType = "image/png",
            handle = "artifact://call_1/screenshot",
        )

        assertNotNull(path)
        assertEquals(path, store.resolveHandle("artifact://call_1/screenshot"))

        // 模拟重启：新实例只靠磁盘上的 index.json 解析
        val reopened = ChatAttachmentStore(
            attachmentsDir = File(filesRoot, "chat/attachments"),
            byteSource = MapByteSource(emptyMap()),
        )
        assertEquals(
            "重启后正文里的 artifact:// 引用必须仍能解析（索引已落盘）",
            path,
            reopened.resolveHandle("artifact://call_1/screenshot"),
        )
        assertNull(reopened.resolveHandle("artifact://call_1/never-stored"))
    }

    @Test
    fun handleWithoutAnIndexEntry_resolvesToNull() {
        val store = storeWith("file:///tmp/screenshot.png" to pngBytes)
        store.persistFromUri("file:///tmp/screenshot.png", "image/png")

        assertNull(
            "没登记过句柄时不得凭空返回路径（用户图不走句柄）",
            store.resolveHandle("artifact://call_1/screenshot"),
        )
    }

    @Test
    fun missingFile_resolvesToNull() {
        val store = storeWith("file:///tmp/screenshot.png" to pngBytes)
        val path = store.persistFromUri("file:///tmp/screenshot.png", "image/png", handle = "artifact://c/s")
        assertNotNull(path)

        assertTrue(File(path!!).delete())

        assertNull("文件被删后必须返回 null（渲染侧才会退化成占位）", store.resolveHandle("artifact://c/s"))
    }

    @Test
    fun corruptIndex_isTreatedAsEmptyAndNeverDeletesFiles() {
        val attachmentsDir = File(filesRoot, "chat/attachments")
        val store = ChatAttachmentStore(attachmentsDir, MapByteSource(mapOf("file:///tmp/s.png" to pngBytes)))
        val path = store.persistFromUri("file:///tmp/s.png", "image/png", handle = "artifact://c/s")
        assertNotNull(path)

        File(attachmentsDir, "index.json").writeText("{ this is not json")

        val reopened = ChatAttachmentStore(attachmentsDir, MapByteSource(emptyMap()))
        assertNull("索引坏了 ⇒ 当空表（返回 null），不抛异常", reopened.resolveHandle("artifact://c/s"))
        assertTrue(
            "索引坏了也**绝不删文件** —— 图片本身还在，将来可以重建索引",
            File(path!!).isFile,
        )
    }

    @Test
    fun unreadableUri_returnsNullInsteadOfThrowing() {
        val store = storeWith()

        assertNull(store.persistFromUri("content://picker/missing", "image/png"))
    }

    @Test
    fun isInside_detectsFilesAlreadyInTheAttachmentsDirectory() {
        val store = storeWith("file:///tmp/s.png" to pngBytes)
        val path = store.persistFromUri("file:///tmp/s.png", "image/png")
        assertNotNull(path)

        assertTrue(store.isInside(path!!))
        assertTrue("带 scheme 的形态也要认", store.isInside("file://$path"))
        assertFalse(store.isInside("content://picker/a"))
        assertFalse(store.isInside("file:///tmp/elsewhere.png"))
        assertFalse(store.isInside(""))
    }

    @Test
    fun contentFileName_isContentAddressedWithAMimeDrivenExtension() {
        val name = ChatAttachmentStore.contentFileName(pngBytes, "image/png")

        assertTrue("必须是 hex.扩展名 形态，实际：$name", Regex("""^[0-9a-f]{32}\.png$""").matches(name))
        assertEquals(
            "同样字节 + 同样 mime ⇒ 同样文件名（去重的根据）",
            name,
            ChatAttachmentStore.contentFileName(pngBytes, "image/png"),
        )
        assertFalse(
            "字节不同 ⇒ 文件名必须不同",
            name == ChatAttachmentStore.contentFileName(otherPngBytes, "image/png"),
        )
        assertTrue(name.startsWith(ChatAttachmentStore.contentFileName(pngBytes, "image/webp").substringBefore('.')))
    }

    @Test
    fun extensionFor_mapsEverySupportedMimeType() {
        assertEquals("png", ChatAttachmentStore.extensionFor("image/png"))
        assertEquals("jpg", ChatAttachmentStore.extensionFor("image/jpeg"))
        assertEquals("webp", ChatAttachmentStore.extensionFor("image/webp"))
        assertEquals("gif", ChatAttachmentStore.extensionFor("image/gif"))
        assertEquals("jpg", ChatAttachmentStore.extensionFor("image/jpeg; charset=binary"))
        assertEquals("jpg", ChatAttachmentStore.extensionFor("image/heic"))
    }

    @Test
    fun open_usesTheChatLayoutDirectory() {
        val store = ChatAttachmentStore.open(filesRoot)
        val path = store.persistBytes(pngBytes, "image/png", handle = "artifact://c/s")

        assertTrue(
            "附件必须落在 filesDir/chat/attachments/（与会话目录平级）：$path",
            path.replace('\\', '/').endsWith("/chat/attachments/" + ChatAttachmentStore.contentFileName(pngBytes, "image/png")),
        )
        assertTrue(File(filesRoot, "chat/attachments/index.json").isFile)
    }
}