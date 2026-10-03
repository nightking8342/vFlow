// 文件: test/java/com/chaomixian/vflow/core/backup/BackupPurityTest.kt
package com.chaomixian.vflow.core.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住 `core/backup/` 的 Android-free 纯度。
 *
 * ## 为什么这是必需的
 *
 * 全部 scope 语义测试都跑在纯 JVM 上。若某个 scope 文件 import 了 `android.*`，
 * 那些测试会**直接编译不过**（更容易发现）；但更危险的是有人把 `Context` 类型的
 * 成员**从环境接缝里透传进 scope** —— 那会让 scope 依赖 `Context` 却不 import
 * `android.content.Context`（Kotlin 的全限定名内联写法是合法的），
 * 编译能过、单测却会在没有 Android 运行时的 JVM 里抛 `RuntimeException: not mocked`。
 *
 * ## 边界（写清楚，免得被当成万能的）
 *
 * 它检查的是**源码文本里的 `android.` 引用**，拦不住「R8 把常量内联」这类二进制层的事。
 * 但它能拦住本任务**真实存在**的那类错误：新 scope 顺手用了 `Context`。
 */
class BackupPurityTest {

    private companion object {
        /**
         * **唯一**允许 import `android.*` 的文件。
         *
         * 它是 [BackupEnvironment] 的生产实现，天然需要 `Context` / `PackageManager`。
         * 用**显式单文件白名单**而不是「放进 `android/` 子包」——
         * 后者多一层目录却没有任何额外隔离收益。
         */
        const val ANDROID_ENV_FILE = "AndroidBackupEnvironment.kt"

        /** 允许的 android import 逐个登记（防有人顺手引入 `android.widget.*`）。 */
        val ANDROID_ALLOWLIST = setOf(
            "android.content.Context",
            "android.content.pm.PackageInfo",
        )
    }

    private fun backupFiles(): List<File> {
        val root = File("src/main/java/com/chaomixian/vflow/core/backup")
        assertTrue("core/backup 目录不存在：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()
    }

    /**
     * 剥掉块注释 / 行注释，保留行号。
     *
     * ⚠️ **必须剥注释** —— 本包多个文件的 KDoc 正文里就写着 `android.*` 这个词
     * （例如 `AndroidBackupEnvironment` 的 KDoc 说它是「唯一允许 import android 的文件」），
     * 不剥会**恒红**。这与 `WireLayerPurityTest` 踩过的假阳性同源。
     */
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

    @Test
    fun `only AndroidBackupEnvironment may reference the android framework`() {
        val violations = mutableListOf<String>()

        for (file in backupFiles()) {
            if (file.name == ANDROID_ENV_FILE) continue
            for ((lineNo, raw) in codeLines(file)) {
                if (raw.contains("android.")) {
                    violations += "${file.name}:$lineNo  ${raw.trim()}"
                }
            }
        }

        assertTrue(
            "❌ core/backup 下只有 $ANDROID_ENV_FILE 可以碰 android.*。\n" +
                "其余文件必须保持纯 JVM —— 否则 ./gradlew test 就证明不了任何 scope 语义。\n" +
                "若确实需要平台能力，请把它加进 BackupEnvironment 接缝。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `AndroidBackupEnvironment only uses allowlisted android imports`() {
        val file = backupFiles().first { it.name == ANDROID_ENV_FILE }
        val violations = mutableListOf<String>()

        for ((lineNo, raw) in codeLines(file)) {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("import android.")) continue
            val fqcn = trimmed.removePrefix("import ").removeSuffix(";").trim()
            if (fqcn !in ANDROID_ALLOWLIST) {
                violations += "${file.name}:$lineNo  $fqcn"
            }
        }

        assertTrue(
            "❌ $ANDROID_ENV_FILE 引入了未登记的 android 类。\n" +
                "这些应在 ANDROID_ALLOWLIST 里逐个显式登记，理由见类注释。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `scope files are pure and present so the checks above are not vacuous`() {
        val files = backupFiles()
        // 防空转：目录被改名/清空 ⇒ 上面两条会全部空转通过
        assertTrue("core/backup 下文件数异常（${files.size}），检查是否被搬走", files.size >= 5)
        for (expected in listOf(
            "BackupScope.kt",
            "BackupEnvironment.kt",
            "BackupScopeRegistry.kt",
            "BackupEnvelope.kt",
            "LegacyBackupAdapter.kt",
            ANDROID_ENV_FILE,
        )) {
            assertTrue("core/backup 下缺少 $expected —— 是否改名/搬走了？", files.any { it.name == expected })
        }
        // 三个 scope 在 scopes/ 子目录（walkTopDown 会递归到）
        for (expected in listOf("FolderScope.kt", "GlobalVariableScope.kt", "WorkflowScope.kt")) {
            assertTrue("scopes/ 下缺少 $expected", files.any { it.name == expected })
        }
    }

    @Test
    fun `comment stripping does not swallow real code`() {
        // 防空转第二重：若剥注释剥过头（把整份文件都当注释），上面两条会假绿。
        val env = backupFiles().first { it.name == ANDROID_ENV_FILE }
        val code = codeLines(env)
        assertTrue(
            "剥注释后看不到 AndroidBackupEnvironment 的 import —— 剥离逻辑可能剥过头了",
            code.any { it.second.trim().startsWith("import android.") },
        )
        assertTrue(
            "剥注释后看不到类定义",
            code.any { it.second.contains("class AndroidBackupEnvironment") },
        )
    }
}
