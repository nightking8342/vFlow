package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **接线锚定**（设计 §8.2）。⚠️ 全部为**源码扫描型**。
 *
 * ## 为什么不能靠行为测试
 *
 * 本改动有六处「纯函数全绿但调用点缺失」的风险，而它们的失败模式**全是静默**的：
 *
 * | # | 漏了会怎样 | 行为测试能不能发现 |
 * |---|---|---|
 * | 1 | 菜单项判据没改 ⇒ 纯自动工作流没有入口 / 或显示着却点不动 | ❌ 要起 Compose |
 * | 2 | `saveWorkflow` 没调刷新 ⇒ 改了图标磁贴不变（像系统缓存） | ❌ 要真机 |
 * | 3 | manifest 元数据写错 ⇒ 执行型继续常亮 | ❌ 要真机（由 `TileManifestTest` 覆盖） |
 * | 4 | **闸 3 不存在** ⇒ 绑定时手动、后来加了定时触发的磁贴**继续按执行型跑** | ❌ 要真机 + 复现时序 |
 * | 5 | 三闸判据不统一 ⇒ 菜单显示着、点了却被拒 | ❌ 同上 |
 * | 6 | 两池默认名没分开 ⇒ 面板里 40 个名字一样 | ❌ 要真机（由 `TileManifestTest` 覆盖） |
 *
 * 本仓库在 `CoreLauncher` / `XposedDiagnostics.messageFor` 上踩过**三次**同一形态
 * （纯函数测试全绿、集成点缺失）。故这里逐条扫描。
 *
 * ⚠️ **所有断言都先 `stripped(...)` 剥注释** —— 本改动的源码里到处是
 * 「`TileGate.accepts`」「`hasAutoTriggers()`」这样的字样（说明为什么要这么写），
 * 只做裸 `contains` 的话，把真实调用删掉、只留注释也一样绿。
 */
class TileWiringTest {

    private companion object {
        const val LIST_SCREEN = "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
        const val LIST_ROUTE = "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"
        const val WORKFLOW_MANAGER = "src/main/java/com/chaomixian/vflow/core/workflow/WorkflowManager.kt"
        const val BASE_WORKFLOW_TILE = "src/main/java/com/chaomixian/vflow/ui/tile/BaseWorkflowTileService.kt"
        const val BASE_EXECUTE_TILE = "src/main/java/com/chaomixian/vflow/ui/tile/BaseExecuteTileService.kt"
        const val BASE_TOGGLE_TILE = "src/main/java/com/chaomixian/vflow/ui/tile/BaseToggleTileService.kt"
        const val TILE_MANAGER = "src/main/java/com/chaomixian/vflow/core/workflow/TileManager.kt"
        const val BACKUP_SCREEN = "src/main/java/com/chaomixian/vflow/ui/settings/BackupRestoreScreen.kt"
    }

    // ── 1. 菜单项判据（§8.2 第 1 条）────────────────────────

    @Test
    fun `both list-screen menus judge each pool through TileGate`() {
        val source = SourceScan.stripped(LIST_SCREEN)

        // 列表模式与紧凑模式各有一个 `regularMenuActions` ⇒ 各两处判据（共 4 处）。
        assertEquals(
            "两处菜单（列表 / 紧凑）各按两池判 ⇒ 必须有 4 处 `TileGate.accepts(`",
            4,
            SourceScan.countOccurrences(source, "TileGate.accepts(")
        )
        assertTrue(source.contains("TileGate.accepts(TileKind.EXECUTE, workflow)"))
        assertTrue(source.contains("TileGate.accepts(TileKind.TOGGLE, workflow)"))

        // ★ 反向锁：`hasManualTrigger()` 不能再出现在 `regularMenuActions` 里
        //   —— 它**只剩下 2 处合法用法**：「添加到主屏幕」菜单项（快捷方式确实
        //   只能执行手动型）与两处卡片徽标的显示逻辑。
        assertEquals(
            "`hasManualTrigger()` 的用法数必须恰为 4（2 个「添加到主屏幕」菜单 + 2 处徽标），" +
                "多出来的很可能就是没改干净的控制中心判据",
            4,
            SourceScan.countOccurrences(source, "hasManualTrigger()")
        )
    }

    @Test
    fun `the two tile menu items use two distinct string resources`() {
        val source = SourceScan.stripped(LIST_SCREEN)
        // ⚠️ 两个菜单项各按自己那一池显隐 ⇒ 必须是**两条独立的项**，
        //    而不是「一个菜单项、内部再分流」（后者没法让两个项各自消失）。
        //    两处（列表 / 紧凑）× 两池 = 4 处文案引用。
        assertEquals(
            "两处菜单各须引用「执行」文案 ⇒ 共 2 处",
            2,
            SourceScan.countOccurrences(source, "R.string.workflow_item_menu_add_to_execute_tile")
        )
        assertEquals(
            "两处菜单各须引用「开关」文案 ⇒ 共 2 处",
            2,
            SourceScan.countOccurrences(source, "R.string.workflow_item_menu_add_to_toggle_tile")
        )
    }

    // ── 2. 刷新调用点（§8.2 第 2 条）────────────────────────

    @Test
    fun `saveWorkflow and deleteWorkflow both request a tile refresh`() {
        val source = SourceScan.stripped(WORKFLOW_MANAGER)

        val save = SourceScan.functionBody(source, "fun saveWorkflow(")
        assertNotNull("必须能截到 saveWorkflow 的函数体（防空转）", save)
        assertTrue(
            "saveWorkflow 尾部必须调 TileRefreshNotifier（否则改了图标磁贴不变）",
            save!!.contains("TileRefreshNotifier.requestAll(")
        )

        val delete = SourceScan.functionBody(source, "fun deleteWorkflow(")
        assertNotNull("必须能截到 deleteWorkflow 的函数体（防空转）", delete)
        assertTrue(
            "deleteWorkflow 尾部必须调 TileRefreshNotifier（否则磁贴仍显示已删的工作流名）",
            delete!!.contains("TileRefreshNotifier.requestAll(")
        )

        assertEquals(
            "本文件应恰有 2 处刷新调用（保存 / 删除）",
            2,
            SourceScan.countOccurrences(source, "TileRefreshNotifier.requestAll(")
        )
    }

    @Test
    fun `the backup import path refreshes tiles too`() {
        // ⚠️ 备份导入是唯一**不经过** `WorkflowManager.saveWorkflow` 的写入路径
        //    （REPLACE 走 `replaceAllWorkflows`、MERGE 走 `saveAllWorkflows`）
        //    ⇒ 必须单独补。漏了的表现是「导入一份备份后磁贴还是旧的」。
        val source = SourceScan.stripped(BACKUP_SCREEN)
        assertTrue(
            "备份导入完成后必须刷磁贴",
            source.contains("TileRefreshNotifier.requestAll(")
        )
        assertTrue(
            "且必须同时覆盖 Done 与 PassphraseRequired（后者已真的导入了非加密段）",
            source.contains("ImportOutcome.Done") && source.contains("ImportOutcome.PassphraseRequired")
        )
    }

    @Test
    fun `the binding path in the list route refreshes tiles`() {
        val source = SourceScan.stripped(LIST_ROUTE)
        assertTrue(
            "绑定 / 解绑磁贴后必须刷（它不经过 saveWorkflow）",
            source.contains("TileRefreshNotifier.requestAll(")
        )
    }

    // ── 4. 闸 3 真的存在（§8.2 第 4 条）★ 最关键的一条 ─────

    @Test
    fun `the execute tile service enforces gate 3 in onClick`() {
        val source = SourceScan.stripped(BASE_EXECUTE_TILE)
        val onClick = SourceScan.functionBody(source, "override fun onClick(")
        assertNotNull("必须能截到 onClick 的函数体（防空转）", onClick)
        assertTrue(
            // ★★ 少了这一句，一个「绑定时是手动型、后来加了定时触发」的工作流
            //    会**继续按执行型跑**，而它的 isEnabled 开关在卡片上显示着、
            //    用户以为那个开关管用。⚠️ **没有任何行为测试会因此变红**。
            "执行型 onClick 必须按 TileGate.accepts 做闸 3 兜底",
            onClick!!.contains("TileGate.accepts(")
        )
        assertTrue(
            "闸 3 拒绝时必须只打开 App（不能静默、也不能照跑）",
            onClick.contains("openApp()")
        )
        assertTrue(
            "闸 3 拒绝时打日志（越界是可见的，不是静默的）",
            onClick.contains("DebugLogger.")
        )
    }

    @Test
    fun `the toggle tile service enforces gate 3 in onClick`() {
        val source = SourceScan.stripped(BASE_TOGGLE_TILE)
        val onClick = SourceScan.functionBody(source, "override fun onClick(")
        assertNotNull("必须能截到 onClick 的函数体（防空转）", onClick)
        assertTrue(
            "开关型 onClick 必须按 TileGate.accepts 做闸 3 兜底（对称于执行型）",
            onClick!!.contains("TileGate.accepts(")
        )
        assertTrue("闸 3 拒绝时必须只打开 App", onClick.contains("openApp()"))
    }

    // ── 5. 三闸判据一致（§8.2 第 5 条）──────────────────────

    @Test
    fun `the raw predicate never appears inside a menu-actions block`() {
        // ⚠️ 闸 1（菜单项显隐）**不得**直接调 `hasAutoTriggers()` —— 必须走 `TileGate`。
        //    各写各的会出现「菜单项显示着、点了却被拒」（§7 第 15 条）。
        //
        // ⚠️⚠️ **判据必须锚在 `regularMenuActions { … }` 块内，不能用「文件里出现过」** ——
        //    `WorkflowListScreen.kt` 别处本来就有 `hasAutoTriggers()`（卡片徽标的显示逻辑），
        //    文件级断言会**恒红**，而恒红的断言会被下一个实现者直接删掉。
        val source = SourceScan.stripped(LIST_SCREEN)
        val blocks = menuActionBlocks(source)
        assertEquals("两个模式各有一个 regularMenuActions 块 ⇒ 恰好截到 2 个", 2, blocks.size)

        for ((index, block) in blocks.withIndex()) {
            assertFalse(
                "第 ${index + 1} 个 regularMenuActions 块内不得出现裸 `hasAutoTriggers()`" +
                    "（磁贴判据必须走 TileGate）",
                block.contains("hasAutoTriggers()")
            )
            assertFalse(
                "第 ${index + 1} 个 regularMenuActions 块内不得用 `hasManualTrigger()` 判磁贴" +
                    "（它仍是「添加到主屏幕」的判据，但那两个 if 必须能分辨出来）",
                block.contains("hasManualTrigger()") &&
                    !block.contains("workflow_item_menu_add_shortcut")
            )
            // 防空转：块必须是真有内容的
            assertTrue("第 ${index + 1} 个块不应为空", block.length > 200)
        }
    }

    @Test
    fun `the route-side gate also goes through TileGate`() {
        // ⚠️ 闸 1.5（Route 侧再判一次）：`onAddToTile` 必须走 `TileGate`。
        //    该文件别处确有 `hasAutoTriggers()`（启用开关的权限恢复），
        //    故这里只锚「那个 lambda 里的 TileGate 调用」这一具体形状。
        val route = SourceScan.stripped(LIST_ROUTE)
        assertTrue(
            "Route 的 onAddToTile 必须走 TileGate（且是带 kind 的两参形态）",
            route.contains("TileGate.accepts(kind, workflow)")
        )
        assertTrue(
            "被拒时必须提示（不能静默不响应）",
            route.contains("TileGate.mismatchMessageRes(kind)")
        )
    }

    /**
     * 按大括号配对截取 `regularMenuActions = buildList { … }` 的正文。
     *
     * ⚠️ 不用正则 —— 块里有嵌套的 `add( WorkflowMenuItemAction( … ) )` 与
     * 字符串，正则会在第一个 `}` 就断（拿到半个块、断言强度无声消失）。
     */
    private fun menuActionBlocks(source: String): List<String> {
        val out = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = source.indexOf("val regularMenuActions", cursor)
            if (start < 0) break
            val body = SourceScan.functionBody(source.substring(start), "buildList")
            if (body != null) out += body
            cursor = start + 1
        }
        return out
    }

    @Test
    fun `the wiring scan is not vacuous`() {
        // ⚠️ 防空转：上面几条断言全基于「文件读得到、剥注释后仍有内容」。
        //    若路径写错，`SourceScan.file` 会直接失败（它 check(isFile)），
        //    但「剥注释剥过头」会让文件变成空串 ⇒ 所有 contains 恒 false、
        //    **所有 assertFalse 恒真**。这里逐文件断言剥注释后仍有可观内容。
        for (path in listOf(
            LIST_SCREEN, LIST_ROUTE, WORKFLOW_MANAGER,
            BASE_EXECUTE_TILE, BASE_TOGGLE_TILE, TILE_MANAGER, BACKUP_SCREEN
        )) {
            val stripped = SourceScan.stripped(path)
            assertTrue("$path 剥注释后不应为空（防空转）", stripped.length > 500)
            assertTrue("$path 应含有真实的代码行", stripped.contains("fun "))
        }
    }

    // ── 6. 判据归一化（`kind` 可空的落实）───────────────────

    @Test
    fun `tile reads normalize the nullable kind through one place`() {
        // ⚠️⚠️ Gson **不填 Kotlin 默认值** ⇒ 旧记录读出的 `kind` 是 `null`；
        //      而 `null` 赋给非空 `val` 后**首次读取**才抛 NPE，被
        //      `TileManager.getAllTiles()` 的 catch 吞成空列表 ⇒
        //      表现是「用户 20 个磁贴绑定全部消失」且**无任何报错**。
        val source = SourceScan.stripped(TILE_MANAGER)
        assertTrue(
            "getAllTiles 的返回值必须过 TileFieldNormalizer（所有读取路径的共同上游）",
            source.contains("TileFieldNormalizer.normalizeAll(")
        )
        val allTiles = SourceScan.functionBody(source, "fun getAllTiles(")
        assertNotNull(allTiles)
        assertTrue(
            "归一化必须在 getAllTiles 的函数体**内**（不是只写在别的函数里）",
            allTiles!!.contains("normalizeAll")
        )
    }

    @Test
    fun `tile gate tolerates a null kind without relying on the caller`() {
        // ⚠️ `TileGate` 是三道闸共用的判据，不能依赖调用方先归一 ——
        //    一条绕过 `TileManager` 的构造路径就会让 `!!` 抛 NPE。
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/workflow/TileGate.kt"
        )
        assertTrue(
            "TileGate.isOutOfKind 必须容得下 null kind（内部再归一一次）",
            !source.contains("tile.kind!!")
        )
        assertTrue(
            "且必须有一个按槽位归一的入口（kindOf）",
            source.contains("fun kindOf(tile: WorkflowTile)")
        )
    }
}
