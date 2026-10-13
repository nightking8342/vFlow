package com.chaomixian.vflow.ui.overlay

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * `AgentRunMonitor` 的行为测试（纯 JVM，不碰 Android）。
 *
 * ⚠️ 为什么必须测：这个单例是**思考流与运行控制的唯一真值来源** ——
 *  - 列表无界 ⇒ 长时间任务吃内存（**静默**，只在真机上表现为越跑越卡）；
 *  - `finish()` 把快照清空 ⇒ "跑完还能回看刚才 AI 想了什么"这个需求**当场失效**；
 *  - `requestCancel()` 忘了先解锁 ⇒ 处于暂停态时**永远走不到取消检查**，表现为"点了结束没反应"
 *    （原 `AgentOverlayManager` 的注释里专门记过这条）。
 */
class AgentRunMonitorTest {

    @Before
    fun setUp() = AgentRunMonitor.resetForTest()

    @After
    fun tearDown() = AgentRunMonitor.resetForTest()

    @Test
    fun `begin clears previous entries and marks running`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.publishThought("上一轮的想法")
        assertEquals(1, AgentRunMonitor.state.value.entries.size)

        AgentRunMonitor.begin("AutoGLM", 30)
        val snapshot = AgentRunMonitor.state.value
        assertEquals(AgentRunMonitor.Phase.RUNNING, snapshot.phase)
        assertEquals("AutoGLM", snapshot.moduleLabel)
        assertEquals(30, snapshot.maxSteps)
        assertTrue("begin 必须清空上一轮的思考流", snapshot.entries.isEmpty())
    }

    @Test
    fun `thoughts are kept in full and the list is bounded`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        val long = "思".repeat(3000)
        AgentRunMonitor.publishThought(long)
        assertEquals("思考文本必须原样保留（截断只发生在 UI 侧）", long, AgentRunMonitor.state.value.entries.single().text)

        repeat(250) { AgentRunMonitor.publishThought("第 $it 条") }
        assertTrue(
            "思考流必须有界（否则长任务吃内存）",
            AgentRunMonitor.state.value.entries.size <= 200
        )
        assertEquals("第 249 条", AgentRunMonitor.state.value.entries.last().text)
    }

    @Test
    fun `blank thoughts are ignored`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.publishThought(null)
        AgentRunMonitor.publishThought("   ")
        assertTrue(AgentRunMonitor.state.value.entries.isEmpty())
    }

    /**
     * ⚠️ 这条锁的是一个**真机反馈**：监视器里的"思考流"大半是"分析屏幕…""生成计划"这类
     * **模块写死的阶段文案**，用户以为那就是模型的思考过程。
     * 根因是当时把 `updateStatus` 的第一个参数**无条件**当思考发布了，而那个参数一半是硬编码。
     */
    @Test
    fun `status text does not pollute the thought stream`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.publishStatus("分析屏幕...")
        AgentRunMonitor.publishAction("准备截屏")

        val snapshot = AgentRunMonitor.state.value
        assertEquals("分析屏幕...", snapshot.statusText)
        assertEquals("准备截屏", snapshot.currentAction)
        assertTrue(
            "⚠️ 阶段状态**不得**进思考流 —— 否则流里混满「分析屏幕…」「生成计划」这类噪音",
            snapshot.entries.isEmpty()
        )
    }

    @Test
    fun `finish keeps the last snapshot as ended`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.publishThought("最后一条想法")
        AgentRunMonitor.finish()

        val snapshot = AgentRunMonitor.state.value
        assertEquals(AgentRunMonitor.Phase.ENDED, snapshot.phase)
        assertEquals(
            "跑完必须还能回看最后一份思考快照",
            "最后一条想法",
            snapshot.entries.single().text
        )
    }

    @Test
    fun `awaitState suspends while paused and resumes on toggle`() = runBlocking {
        AgentRunMonitor.begin("AI 智能体", 15)
        assertTrue(AgentRunMonitor.togglePause())

        var resumed = false
        val job = launch {
            AgentRunMonitor.awaitState()
            resumed = true
        }
        delay(100)
        assertFalse("暂停时主循环必须被挂住", resumed)

        assertFalse(AgentRunMonitor.togglePause())
        job.join()
        assertTrue("继续后必须放行", resumed)
    }

    @Test
    fun `request cancel makes awaitState throw`() = runBlocking {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.requestCancel()

        var thrown = false
        try {
            AgentRunMonitor.awaitState()
        } catch (e: CancellationException) {
            thrown = true
        }
        assertTrue("取消后主循环必须抛 CancellationException", thrown)
    }

    @Test
    fun `request cancel also unlocks a paused loop`() = runBlocking {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.togglePause() // 进入暂停
        AgentRunMonitor.requestCancel()

        // ⚠️ 若 requestCancel 忘了先解锁，这里会永久挂住（正是原实现注释里记的那条坑）
        var thrown = false
        val job = launch {
            try {
                AgentRunMonitor.awaitState()
            } catch (e: CancellationException) {
                thrown = true
            }
        }
        job.join()
        assertTrue("暂停态下取消必须能走通（先解锁再置位）", thrown)
    }

    @Test
    fun `request cancel is immediately visible in the state flow`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        assertFalse(AgentRunMonitor.state.value.cancelling)

        AgentRunMonitor.requestCancel()
        assertTrue(
            "取消必须**立刻**反映到 StateFlow —— 否则用户点了「结束」看不到任何变化（真机反馈的 bug）",
            AgentRunMonitor.state.value.cancelling
        )
    }

    @Test
    fun `finish clears the cancelling flag`() {
        AgentRunMonitor.begin("AI 智能体", 15)
        AgentRunMonitor.requestCancel()
        AgentRunMonitor.finish()

        val snapshot = AgentRunMonitor.state.value
        assertEquals(AgentRunMonitor.Phase.ENDED, snapshot.phase)
        assertFalse("跑完了就不该再显示「正在停止…」", snapshot.cancelling)
    }
}
