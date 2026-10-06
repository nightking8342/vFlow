package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TileRefreshNotifier.dispatchAll` 的**逐个派发契约**。
 *
 * ⚠️ 为什么不测 `requestAll(context)`：
 * - 它要 `android.content.Context`（纯 JVM 里没有）且**必须投到主线程**
 *   （`Handler(Looper.getMainLooper())` 在单测 JVM 里拿不到 Looper）；
 * - 而它真正有价值的**契约**只有一条：**单个槽抛异常不能中断整轮**。
 *   那条契约由 [TileRefreshNotifier.dispatchAll] 承载（`request` 是可注入的），
 *   可以直接测。生产路径传进去的 lambda 就是那一行真实的
 *   `TileService.requestListeningState`。
 *
 * ⚠️⚠️ 这条契约**值得单独测**，因为它的失败模式是**静默的半失效**：
 * 对「尚未添加到控制中心」的磁贴调用 `requestListeningState` 的行为官方未定义
 * （设计 §9 未决项 1 —— 可能抛）。若异常冒出去，**排在前面的失败会让后面所有
 * 磁贴永远不刷新**，而用户只看到「有些磁贴是新的、有些是旧的」，
 * 几乎不可能归因到「第一个磁贴没被添加过」。
 */
class TileRefreshDispatchTest {

    @Test
    fun `dispatchAll covers all 40 slots`() {
        val seen = mutableListOf<String>()
        val outcome = TileRefreshNotifier.dispatchAll { seen.add(it) }

        assertEquals(WorkflowTile.TILE_COUNT, seen.size)
        assertEquals(WorkflowTile.TILE_COUNT, outcome.succeeded)
        assertTrue(outcome.failures.isEmpty())

        // ⚠️ 类名必须与 `TileSlot` 同源（`ComponentName` 拼错 ⇒ 组件不存在 ⇒
        //    **磁贴永不刷新**，且不报错）。这里直接比对拼出来的集合。
        val expected = TileKind.entries.flatMap { kind ->
            (0 until TileSlot.tileCountOf(kind)).map { TileSlot.serviceClassName(kind, it) }
        }
        assertEquals(expected.toSet(), seen.toSet())
    }

    @Test
    fun `one throwing slot does not stop the rest`() {
        // ★ 核心契约。
        val attempted = mutableListOf<String>()
        val boom = TileSlot.serviceClassName(TileKind.EXECUTE, 3)

        val outcome = TileRefreshNotifier.dispatchAll { className ->
            attempted.add(className)
            if (className == boom) throw IllegalStateException("尚未添加到控制中心")
        }

        assertEquals("全部 40 个槽都必须被尝试过", WorkflowTile.TILE_COUNT, attempted.size)
        assertEquals(WorkflowTile.TILE_COUNT - 1, outcome.succeeded)
        assertEquals(listOf(boom), outcome.failures)
    }

    @Test
    fun `every slot throwing still reports all failures instead of crashing`() {
        val outcome = TileRefreshNotifier.dispatchAll { throw RuntimeException("nope") }
        assertEquals(0, outcome.succeeded)
        assertEquals(WorkflowTile.TILE_COUNT, outcome.failures.size)
    }

    @Test
    fun `the first and last slots are both reached even when the middle throws`() {
        // ⚠️ 防「只遍历到第一个异常就 break」这类写法的**边界**验证：
        //    失败点选在正中间（第 20 个），两端的槽必须都被访问过。
        val boom = TileSlot.serviceClassName(TileKind.TOGGLE, 0)
        val attempted = mutableListOf<String>()

        TileRefreshNotifier.dispatchAll { className ->
            attempted.add(className)
            if (className == boom) throw IllegalStateException("boom")
        }

        assertEquals(TileSlot.serviceClassName(TileKind.EXECUTE, 0), attempted.first())
        assertEquals(TileSlot.serviceClassName(TileKind.TOGGLE, 19), attempted.last())
    }
}
