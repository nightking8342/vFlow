package com.chaomixian.vflow.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标库的**一致性体检**。
 *
 * ## 为什么需要它
 *
 * `MaterialSymbolNames.ALL` 是自动生成的清单，而**资源文件是另一套东西**。
 * 两者脱节的方式是**静默的**：清单里有个名字、但 `res/drawable` 里没有对应
 * 资源 ⇒ `getIdentifier` 返回 0 ⇒ 选择器里点一下**什么都不发生、也不报错**
 * （`WorkflowIconPickerAdapter` 会保留原图，所以连视觉异常都没有）。
 *
 * ⚠️ 本仓库有过一个更早的同形事故：`IconSelectorAdapter` 的候选里列着
 * `rounded_download_24`，但 R8 的 `shrinkResources` 把它剥掉了 ——
 * 同样是"能选、选了没用"。那次是**构建产物**层面的脱节（source 里明明有），
 * 由 `res/raw/keep.xml` 解决；本测试管的是**源码**层面的脱节。
 *
 * ## 本测试能覆盖什么、不能覆盖什么
 *
 * | | 覆盖 |
 * |---|---|
 * | 清单里的名字在源码里有对应文件 | ✅ 本测试 |
 * | 那些文件在 **release APK** 里还在 | ❌ 测不了 —— 需要 `aapt2 dump resources`。改 `keep.xml` 后**必须 clean 构建**再手工核 |
 *
 * ⚠️ 单测跑在**纯 JVM** 上，`R.drawable` 只是编译期常量、没有真实资源表，
 * 所以这里读的是**源码目录**（`app/src/main/res/drawable/`），不是 `R`。
 * 路径按 Gradle 的工作目录（`:app`）解析。
 */
class MaterialSymbolNamesTest {

    private val drawableDir = java.io.File("src/main/res/drawable")

    private val filesOnDisk: Set<String> by lazy {
        drawableDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.toSet()
            ?: error("读不到 drawable 目录：${drawableDir.absolutePath}（工作目录应为 :app）")
    }

    @Test
    fun `drawable directory is reachable`() {
        // 防空转：目录读不到时上面会抛，这里再显式锁一道，
        // 免得将来路径变了、其余用例静默退化。
        assertTrue(
            "drawable 目录里应有大量图标，实际 ${filesOnDisk.size} 个",
            filesOnDisk.size > 1000
        )
    }

    @Test
    fun `every name has a line variant on disk`() {
        val missing = MaterialSymbolNames.ALL
            .map { "rounded_${it}_24" }
            .filterNot(filesOnDisk::contains)
        assertTrue("缺少线框资源：${missing.take(10)}（共 ${missing.size} 个）", missing.isEmpty())
    }

    @Test
    fun `every name has a fill variant on disk`() {
        val missing = MaterialSymbolNames.ALL
            .map { "rounded_${it}_fill_24" }
            .filterNot(filesOnDisk::contains)
        assertTrue("缺少填充资源：${missing.take(10)}（共 ${missing.size} 个）", missing.isEmpty())
    }

    @Test
    fun `names are unique`() {
        assertEquals(
            "清单里有重复名字",
            MaterialSymbolNames.ALL.size,
            MaterialSymbolNames.ALL.toSet().size
        )
    }

    @Test
    fun `names are valid android resource identifiers`() {
        val pattern = Regex("^[a-z0-9_]+$")
        val invalid = MaterialSymbolNames.ALL.filterNot(pattern::matches)
        assertTrue("非法资源名：${invalid.take(10)}", invalid.isEmpty())
    }

    @Test
    fun `no name makes fill naming ambiguous`() {
        // ⚠️ 这条**不是**「名字里不许出现 fill」—— Material Symbols 里本来就有
        //    `format_color_fill`、`grain_fill` 这类名字（按名字含 fill 过滤会误报，
        //    第一版就是这么写的、被实测打红）。
        //
        //    真正要防的是**歧义**：若清单里同时有 `X` 与 `X_fill`，那么
        //    `rounded_X_fill_24` 既可读作「X 的填充版」也可读作「X_fill 的线框版」，
        //    两者是**不同的资源名**、却只对应一个文件 ⇒ 其中一个永远取不到。
        //    实测当前清单里以 `_fill` 结尾的名字为 **0 个**，故无歧义；
        //    这条断言锁住这个前提（将来上游加了这类名字就要改命名方案）。
        val ambiguous = MaterialSymbolNames.ALL
            .filter { it.endsWith("_fill") && it.removeSuffix("_fill") in MaterialSymbolNames.ALL.toSet() }
        assertTrue("名字造成 fill 歧义：$ambiguous", ambiguous.isEmpty())
    }

    @Test
    fun `the list is not vacuous`() {
        // 反向锁：清单若因为生成脚本的 bug 变成空表或只剩几个，
        // 上面所有用例都会"通过"。
        assertTrue("清单太小（${MaterialSymbolNames.ALL.size}），像是生成脚本出了问题",
            MaterialSymbolNames.ALL.size > 4000)
    }

    @Test
    fun `known icons are present`() {
        // 抽查几个确定存在的（这些名字在 fonts.google.com 上都能查到）
        for (name in listOf("home", "settings", "play_arrow", "wifi", "download")) {
            assertTrue("清单里缺少 $name", name in MaterialSymbolNames.ALL)
        }
    }

    @Test
    fun `picker candidates interleave line and fill`() {
        // ⚠️ 交替排列是**用户可发现性**的要求（见 `iconPickerCandidates` 的注释）：
        //    顺序排列的话，找"填充版的 home"要滚过 4150 项。
        val candidates = WorkflowVisuals.iconPickerCandidates()
        assertEquals(MaterialSymbolNames.ALL.size * 2, candidates.size)
        assertFalse("第一项应是线框", candidates[0].endsWith("_fill_24"))
        assertTrue("第二项应是同一图标的填充版", candidates[1].endsWith("_fill_24"))
        assertEquals(
            IconSearchFilterNamePair.firstOf(candidates[0]),
            IconSearchFilterNamePair.firstOf(candidates[1])
        )
    }

    /** 把 `rounded_x_24` / `rounded_x_fill_24` 归一成 `x`，用于上面的配对断言。 */
    private object IconSearchFilterNamePair {
        fun firstOf(res: String): String =
            res.removePrefix("rounded_").removeSuffix("_fill_24").removeSuffix("_24")
    }
}
