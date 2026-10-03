// 文件: test/java/com/chaomixian/vflow/core/backup/SourceScan.kt
package com.chaomixian.vflow.core.backup

import java.io.File

/**
 * **源码扫描型测试**的共用 helper（fork 新增）。
 *
 * 为什么单独抽一个对象而不是各测试各写一份：本仓库已有两个地方需要
 * 「剥注释 + 大括号配对截取函数体」，而这两个动作**写错的方式很隐蔽** ——
 * 剥注释剥过头会让断言全在空转（恒绿），大括号配对用正则写会在第一个 `}`
 * 就断（拿到半个函数体、断言强度无声消失）。共用一份可验证的实现，
 * 比让每个测试各写一遍可靠。
 *
 * ⚠️ 测试工作目录是 `app/`（Gradle 默认），故路径从这里起算。
 */
object SourceScan {

    /** 读仓库里的源码文件（不存在直接断言失败，避免「文件改名 ⇒ 断言恒真」）。 */
    fun file(relativePath: String): File {
        val f = File(relativePath)
        check(f.isFile) { "源码文件不存在（测试工作目录应为 app/）：${f.absolutePath}" }
        return f
    }

    /** 读源码并剥掉注释。 */
    fun stripped(relativePath: String): String =
        stripCommentsPreservingStructure(file(relativePath).readText())

    /**
     * 剥掉注释，但**保留换行与长度结构**（用空格顶替注释内容）。
     *
     * ⚠️ 不能用「整行丢弃」的做法：本对象要做**大括号配对**截取函数体，
     * 而注释里的 `{` / `}` 会打乱配对。这里改成「原地抹成空格」，
     * 同时用状态机跳过字符串字面量，避免把 `"{"` 当成真括号。
     *
     * ⚠️⚠️ 存在的核心理由：本 fork 的改动要在源码里写大量含
     * `BackupScopeRegistry.all()` / `BackupPipeline.import(` 字样的 KDoc ——
     * 只做 `contains` 的话，把真实调用删掉、只留注释也一样绿
     * （本仓库在 `AgentErrorDialogWiringTest` 上踩过完全相同的坑）。
     */
    fun stripCommentsPreservingStructure(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTripleString = false
        var escaped = false

        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'

            when {
                inLineComment -> {
                    if (c == '\n') {
                        inLineComment = false
                        sb.append('\n')
                    } else {
                        sb.append(' ')
                    }
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') {
                        inBlockComment = false
                        sb.append("  ")
                        i++
                    } else {
                        sb.append(if (c == '\n') '\n' else ' ')
                    }
                }
                inTripleString -> {
                    sb.append(c)
                    if (c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"') {
                        inTripleString = false
                        sb.append("\"\"")
                        i += 2
                    }
                }
                inString -> {
                    sb.append(c)
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        inLineComment = true
                        sb.append("  ")
                        i++
                    }
                    c == '/' && next == '*' -> {
                        inBlockComment = true
                        sb.append("  ")
                        i++
                    }
                    c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                        inTripleString = true
                        sb.append("\"\"\"")
                        i += 2
                    }
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 按**大括号配对**截取函数体（正文，不含最外层 `{}`）。
     *
     * ⚠️ **不用正则** —— 函数体里有字符串与嵌套 lambda，正则会在第一个 `}` 就断。
     *
     * @param signature 函数签名里**足够独特**的一段（如 `fun replaceAllWorkflows(`）。
     */
    fun functionBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
        if (start < 0) return null

        var i = source.indexOf('{', start)
        if (i < 0) return null

        var depth = 0
        val bodyStart = i + 1
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i)
                }
            }
            i++
        }
        return null
    }

    fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
