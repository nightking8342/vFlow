// 文件: test/java/com/chaomixian/vflow/core/security/SecretLayerPurityTest.kt
package com.chaomixian.vflow.core.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住 T2 新增的加密层保持 Android-free。
 *
 * ## 为什么扫 `core/security/` 而不只扫 `core/backup/`
 *
 * T1 的 `BackupPurityTest` 只覆盖 `core/backup` 整个子树。而本任务按方案把
 * `AesGcmEngine.kt` 放在 **`core/security/`** —— 那个包**不在**任何既有
 * 纯度扫描的范围内。若不额外检查，将来有人往这里塞 Android Keystore 依赖
 * 或 `Context` 时不会被拦，而后果是**全部加密单测在纯 JVM 上跑不起来**，
 * 且是「编译能过、运行时 not mocked」那种最难查的形态。
 *
 * ⚠️ **边界**：它检查的是源码文本里的 `android.` 引用，拦不住 R8 内联这类
 * 二进制层的事。但它能拦住本任务真实存在的那类错误。
 */
class SecretLayerPurityTest {

    private companion object {
        /**
         * `core/backup/` 下**允许**碰 android 的文件 —— 与 T1 的
         * `BackupPurityTest.ANDROID_ENV_FILE` 保持同一口径。
         *
         * ⚠️ 该文件的 android import 白名单由 **T1 的** `BackupPurityTest` 管
         * （本文件不重复那条检查，避免两处白名单漂移）。
         */
        const val ANDROID_ENV_FILE = "AndroidBackupEnvironment.kt"

        /**
         * `core/security/` 下**允许**碰 android 的唯一文件。
         *
         * `AndroidKeyStore` 天然是平台能力，无法纯 JVM 实现 —— 它必须 import
         * `android.security.keystore.*`。它的**可测性**由「可注入的 engine 接缝」保证
         * （[KeystoreCryptoBox.engine] + `KeystoreCryptoBoxTest` 里的假实现），
         * 而不是靠「不碰 android」。
         *
         * ⚠️ 这与上面 `ANDROID_ENV_FILE` 是**同一范式**：把「唯一允许碰 android 的
         * 文件」显式登记并附带理由，而不是靠把文件搬到别的包去绕开扫描
         * （`BackupPurityTest` 与本文都按目录**递归**扫，换子目录无效）。
         */
        const val KEYSTORE_ENGINE_FILE = "KeystoreGcmEngine.kt"

        /**
         * T2 在 `core/backup/` 下**新增**的文件（不含 T1 的）。
         *
         * ⚠️ 这份名单**同时是防空转的判据**：若这些文件被改名/搬走，
         * 下面的扫描会变成「扫了个不存在的目录」而假绿。
         */
        val T2_BACKUP_FILES = listOf(
            "BackupCrypto.kt",
            "SecretEnvelope.kt",
            "SecretFieldScrubber.kt",
            "SecretStore.kt",
            "BackupPipeline.kt",
            "scopes/SecretsScope.kt",
        )
    }

    /** 剥掉块注释 / 行注释，保留行号。与 `BackupPurityTest` / `WireLayerPurityTest` 同一思路。 */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlockComment = false

        file.readLines().forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false
                return@forEachIndexed
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) inBlockComment = true
                return@forEachIndexed
            }
            if (trimmed.startsWith("//")) return@forEachIndexed
            out += (index + 1) to raw
        }
        return out
    }

    private fun ktFiles(root: String): List<File> {
        val dir = File(root)
        assertTrue("目录不存在：${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    /** 收集 `android.` 引用（剥注释后）。 */
    private fun androidReferences(files: List<File>, skip: (File) -> Boolean = { false }): List<String> {
        val violations = mutableListOf<String>()
        for (file in files) {
            if (skip(file)) continue
            for ((lineNo, raw) in codeLines(file)) {
                if (raw.contains("android.")) {
                    violations += "${file.name}:$lineNo  ${raw.trim()}"
                }
            }
        }
        return violations
    }

    @Test
    fun `core security package is android free apart from the keystore engine`() {
        // ⚠️⚠️ 本文件存在的主要理由。`core/security/` 不在任何既有纯度扫描范围内。
        // 唯一放行的是 KEYSTORE_ENGINE_FILE（AndroidKeyStore 的平台依赖，理由见其 KDoc）。
        val violations = androidReferences(ktFiles("src/main/java/com/chaomixian/vflow/core/security")) {
            it.name == KEYSTORE_ENGINE_FILE
        }
        assertTrue(
            "❌ core/security 下只有 $KEYSTORE_ENGINE_FILE 可以碰 android.* —— 其余加密层必须保持纯 JVM。\n" +
                "一旦引入 Android 依赖，全部加密单测就会在纯 JVM 上抛 " +
                "`Method ... not mocked`，而**编译仍然能过**（最难查的形态）。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `keystore engine is the only file allowed and it really does use android`() {
        // 防空转 + 防「白名单指向一个不存在的文件」：若 KEYSTORE_ENGINE_FILE 被改名，
        // 上面那条的 skip 就永远不生效 —— 而白名单条目会安静地变成一个死名字。
        val root = "src/main/java/com/chaomixian/vflow/core/security"
        val files = ktFiles(root)
        val engine = files.firstOrNull { it.name == KEYSTORE_ENGINE_FILE }
        assertTrue("$KEYSTORE_ENGINE_FILE 应当存在（它是 AndroidKeyStore 实现）", engine != null)
        assertTrue(
            "剥注释后看不到 $KEYSTORE_ENGINE_FILE 的 android import —— 剥离逻辑可能剥过头了，\n" +
                "那会让上面那条扫描**空转通过**",
            codeLines(engine!!).any { it.second.trim().startsWith("import android.") },
        )
    }

    @Test
    fun `T2 backup files are android free apart from the environment seam`() {
        val root = "src/main/java/com/chaomixian/vflow/core/backup"
        val violations = androidReferences(ktFiles(root)) { it.name == ANDROID_ENV_FILE }
        assertTrue(
            "❌ core/backup 下只有 $ANDROID_ENV_FILE 可以碰 android.*。\n" +
                "T2 新增的加密/清洗/密钥 scope 全部必须纯 JVM（方案 §3.1）。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `android seam is the only file allowed and it really does use android`() {
        // 防空转 + 防「白名单指向一个不存在的文件」：
        // 若 ANDROID_ENV_FILE 被改名，上面那条的 skip 就永远不生效，
        // 而它表现为「突然开始报一个合法文件的错」—— 不是静默，但仍属配置漂移。
        val root = "src/main/java/com/chaomixian/vflow/core/backup"
        val files = ktFiles(root)
        val env = files.firstOrNull { it.name == ANDROID_ENV_FILE }
        assertTrue("$ANDROID_ENV_FILE 应当存在（它是 BackupEnvironment 的生产实现）", env != null)
        assertTrue(
            "剥注释后看不到 $ANDROID_ENV_FILE 的 android import —— 剥离逻辑可能剥过头了，\n" +
                "那会让上面两条扫描**空转通过**",
            codeLines(env!!).any { it.second.trim().startsWith("import android.") },
        )
    }

    @Test
    fun `T2 backup files are present so the checks above are not vacuous`() {
        val root = File("src/main/java/com/chaomixian/vflow/core/backup")
        assertTrue("core/backup 目录不存在", root.isDirectory)

        val present = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toSet()

        for (relative in T2_BACKUP_FILES) {
            assertTrue(
                "❌ 缺少 T2 文件 core/backup/$relative —— 是否改名/搬走了？\n" +
                    "本文件上方的扫描按目录走，文件没了会**静默空转通过**。",
                relative in present,
            )
        }
    }

    @Test
    fun `comment stripping does not swallow real code`() {
        // 防「剥注释剥过头 ⇒ 上方三条全绿」。
        // 拿一个已知含真实代码的文件验证：剥完后应仍能看到 import 与 class 定义。
        val sample = File("src/main/java/com/chaomixian/vflow/core/security/AesGcmEngine.kt")
        assertTrue("AesGcmEngine.kt 应当存在", sample.isFile)
        val code = codeLines(sample)
        assertTrue(
            "剥注释后看不到 import —— 剥离逻辑可能剥过头了",
            code.any { it.second.trim().startsWith("import ") },
        )
        assertTrue(
            "剥注释后看不到 interface AesGcmEngine —— 剥离逻辑可能剥过头了",
            code.any { it.second.contains("interface AesGcmEngine") },
        )
        assertTrue(
            "防空转：扫描确实覆盖到了本包的文件",
            ktFiles("src/main/java/com/chaomixian/vflow/core/security").any { it.name == "AesGcmEngine.kt" },
        )
    }
}
