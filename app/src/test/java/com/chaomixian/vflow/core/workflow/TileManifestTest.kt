package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.TileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **manifest 的 40 条磁贴声明**（设计 §8.2 第 3 / 6 条）。
 *
 * ## 为什么必须扫源码
 *
 * 这 40 条是**手写**的 `<service>` 块，没有任何编译期检查能覆盖它们：
 * - 少写一条 ⇒ 对应的槽位**在系统「添加磁贴」面板里根本不存在**，而 App 侧
 *   一切正常（`TileSlot` 照样拼出那个类名、`requestListeningState` 静默失败）；
 * - 元数据写错 ⇒ 执行型**继续常亮**（用户要修的正是这个），而代码里
 *   `state = STATE_INACTIVE` 写得完全正确；
 * - label 写错 ⇒ **面板里 40 个磁贴名字一模一样**，用户随便挑一个，
 *   不知道自己加的是执行还是开关（只能在真机上肉眼发现）。
 *
 * ⚠️ **本文件的断言全部带「防空转」计数**（先断言块内确实有那么多条，
 * 再逐条断言内容）—— 否则一次「块被删空」会让所有 `all { … }` 型断言**恒真**。
 */
class TileManifestTest {

    private val manifest: String by lazy {
        SourceScan.file("src/main/AndroidManifest.xml").readText()
    }

    // ── 三个 label 前缀的条数（§8.2 第 6 条）────────────────

    @Test
    fun `exactly 20 execute tiles and 20 toggle tiles are declared`() {
        assertEquals(
            "必须恰有 20 条 `vFlow Execute N`（执行池 0–19）",
            20,
            SourceScan.countOccurrences(manifest, """android:label="vFlow Execute """)
        )
        assertEquals(
            "必须恰有 20 条 `vFlow Toggle N`（开关池 20–39）",
            20,
            SourceScan.countOccurrences(manifest, """android:label="vFlow Toggle """)
        )
    }

    @Test
    fun `the old indistinguishable label is gone`() {
        // ★ 反向锁：本次改动的**用户诉求**就是「区分这两个池」。
        //    留着任何一条 `vFlow Tile N` 都意味着面板里仍有分不出的一组。
        assertEquals(
            "旧的 `vFlow Tile N` 必须一条不剩（否则面板里仍有分不出的磁贴）",
            0,
            SourceScan.countOccurrences(manifest, """android:label="vFlow Tile """)
        )
    }

    @Test
    fun `every label matches what TileSlot displayName produces`() {
        // ★ 这是「面板里的名字」与「运行时未绑定态的名字」**必须逐字一致**的机器化锚定。
        //    ⚠️ 两者的漂移是**静默**的：面板里叫「vFlow Execute 3」、加进控制中心后
        //       变成另一个名字，用户以为是两个不同的东西（§7 第 19 条）。
        val executeLabels = (0 until 20).map { TileSlot.displayName(TileKind.EXECUTE, it) }
        val toggleLabels = (0 until 20).map { TileSlot.displayName(TileKind.TOGGLE, it) }

        for (label in executeLabels + toggleLabels) {
            assertTrue(
                "manifest 里必须有 `android:label=\"$label\"`（由 TileSlot.displayName 拼出）",
                manifest.contains("""android:label="$label"""")
            )
        }
        // 防空转：40 个名字两两不同，若 displayName 退化成「都返回同一个」，
        // 上面的循环会退化为「检查同一个名字 40 遍」而全部通过。
        assertEquals(40, (executeLabels + toggleLabels).toSet().size)
    }

    // ── 服务类名（§4.2）───────────────────────────────────

    @Test
    fun `all 40 service class names are declared`() {
        for (kind in TileKind.entries) {
            for (slot in 0 until TileSlot.tileCountOf(kind)) {
                // manifest 用相对形式 `.ui.tile.XxxN`
                val simple = TileSlot.serviceClassName(kind, slot)
                    .removePrefix("com.chaomixian.vflow")
                assertTrue(
                    "manifest 必须声明 `$simple`",
                    manifest.contains("""android:name="$simple"""")
                )
            }
        }
    }

    @Test
    fun `the execute pool keeps its legacy class names`() {
        // ⚠️⚠️ 这条锁的是「执行型 service 类名不得改」（设计 §4.2 的硬约束）：
        //      SystemUI 按 `ComponentName` 记住用户已添加到控制中心的磁贴，
        //      改名会让它们**全部消失**，而 App 侧不会收到任何提示。
        assertTrue(
            manifest.contains("""android:name=".ui.tile.WorkflowTileService0"""")
        )
        assertTrue(
            manifest.contains("""android:name=".ui.tile.WorkflowTileService19"""")
        )
    }

    // ── 授权与 intent-filter（每条都不能少）──────────────────

    @Test
    fun `every tile service keeps the binder permission and the QS intent filter`() {
        val blocks = tileServiceBlocks()
        assertEquals("必须抓到 40 个磁贴 service 块", 40, blocks.size)

        val missingPermission = blocks.filter {
            !it.contains("""android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"""")
        }
        assertTrue(
            "每个磁贴 service 都必须带 BIND_QUICK_SETTINGS_TILE（少了会被系统忽略）：$missingPermission",
            missingPermission.isEmpty()
        )

        val missingFilter = blocks.filter {
            !it.contains("""android:name="android.service.quicksettings.action.QS_TILE"""")
        }
        assertTrue(
            "每个磁贴 service 都必须带 QS_TILE intent-filter（少了不会出现在面板里）：$missingFilter",
            missingFilter.isEmpty()
        )
    }

    // ── 元数据：CTRL-C 那一格（§3 / §8.2 第 3 条）───────────

    @Test
    fun `execute tiles declare ACTIVE_TILE and NOT TOGGLEABLE_TILE`() {
        val execute = blocksOfClassPrefix(".ui.tile.WorkflowTileService")
        assertEquals("执行池必须恰有 20 条", 20, execute.size)

        val wronglyToggleable = execute.filter { it.contains(TOGGLEABLE_TILE) }
        assertTrue(
            // ⚠️ 这条是 §3 的落点之一：`TOGGLEABLE_TILE` 是**无障碍语义**，
            //    「执行一次」不是开关。读屏会把它播报成「开关，已关闭」。
            //    ⚠️ 但它**不控制高亮**（那是 `Tile.state` 的事）——
            //    只去掉元数据、不改 state 的话磁贴**照样常亮**，而那正是用户要修的。
            "执行型**不得**带 TOGGLEABLE_TILE（那是无障碍语义，执行一次不是开关）：$wronglyToggleable",
            wronglyToggleable.isEmpty()
        )

        val missingActive = execute.filter { !it.contains(ACTIVE_TILE) }
        assertTrue(
            // ⚠️ 声明 ACTIVE_TILE ⇒ 系统不主动绑、更新必须靠 requestListeningState 推。
            //    少了它的表现是「我方推的刷新不一定生效、系统绑的时机又不受控」。
            "执行型必须带 ACTIVE_TILE（让刷新由我方 requestListeningState 控制）：$missingActive",
            missingActive.isEmpty()
        )
    }

    @Test
    fun `toggle tiles declare BOTH ACTIVE_TILE and TOGGLEABLE_TILE`() {
        val toggle = blocksOfClassPrefix(".ui.tile.WorkflowToggleTileService")
        assertEquals("开关池必须恰有 20 条", 20, toggle.size)

        val missingToggleable = toggle.filter { !it.contains(TOGGLEABLE_TILE) }
        assertTrue(
            // ⚠️ 开关型**保留** TOGGLEABLE_TILE（§3.2 的反面）：
            //    它确实是个开关，读屏播报成「开关」是**正确**的语义。
            "开关型必须保留 TOGGLEABLE_TILE（它确实是开关）：$missingToggleable",
            missingToggleable.isEmpty()
        )

        val missingActive = toggle.filter { !it.contains(ACTIVE_TILE) }
        assertTrue(
            "开关型也必须带 ACTIVE_TILE（与执行型对称，见设计 §4.4）：$missingActive",
            missingActive.isEmpty()
        )
    }

    @Test
    fun `the execute and toggle blocks are disjoint sets of class names`() {
        // 防空转：两个前缀若重叠（例如开关池写成 `WorkflowTileService20`），
        // 上面两条按前缀取块的断言会互相看到对方的条目、各自「通过」。
        val execute = blocksOfClassPrefix(".ui.tile.WorkflowTileService")
        val toggle = blocksOfClassPrefix(".ui.tile.WorkflowToggleTileService")
        val executeNames = execute.map { classNameOf(it) }.toSet()
        val toggleNames = toggle.map { classNameOf(it) }.toSet()
        assertTrue(
            "两池的类名不得重叠",
            executeNames.intersect(toggleNames).isEmpty()
        )
        assertEquals(40, executeNames.size + toggleNames.size)
    }

    // ── helper ─────────────────────────────────────────────

    /**
     * 取出所有磁贴 `<service>` 块（按 `</service>` 切）。
     *
     * ⚠️ 不用正则跨行匹配整个 `<service …>…</service>` —— 块里有 `<intent-filter>`
     * 与 `<meta-data>` 两种子元素，贪婪/非贪婪都会踩到边界。按「下一个 `</service>`
     * 截断」是最稳的（本文件里没有嵌套的 `<service>`）。
     */
    private fun tileServiceBlocks(): List<String> {
        val out = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = manifest.indexOf("<service", cursor)
            if (start < 0) break
            val end = manifest.indexOf("</service>", start)
            if (end < 0) break
            val block = manifest.substring(start, end)
            // 只收磁贴服务（按是否含 QS_TILE action 判定）
            if (block.contains("android.service.quicksettings.action.QS_TILE")) out += block
            cursor = end + 1
        }
        return out
    }

    private fun blocksOfClassPrefix(prefix: String): List<String> =
        tileServiceBlocks().filter { it.contains("""android:name="$prefix""") }

    private fun classNameOf(block: String): String =
        Regex("""android:name="([^"]+)"""").find(block)!!.groupValues[1]

    private companion object {
        const val TOGGLEABLE_TILE = "android.service.quicksettings.TOGGLEABLE_TILE"
        const val ACTIVE_TILE = "android.service.quicksettings.ACTIVE_TILE"
    }
}
