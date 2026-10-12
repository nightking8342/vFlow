package com.chaomixian.vflow.ui.common.glass

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 液态玻璃开关的**回调新鲜度**接线锚定（源码扫描型）。
 *
 * ## 这条断言守的是一个已复现的数据损坏缺陷
 *
 * `LiquidToggleSwitch` 把 `DampedDragAnimation` 建在
 * `remember(animationScope) { ... }` 里 —— **只建一次**。它闭包里捕获的
 * `onCheckedChange` 从此永远是**首帧那一个**，后续重组传进来的新 lambda 被丢弃。
 *
 * 工作流列表的开关恰好传的是**捕获了当帧 `Workflow` 快照**的 lambda：
 * `{ enabled -> actions.onToggleEnabled(workflow, enabled) }` ⇒ 用户
 * 「编辑某个工作流并保存 → 回列表拨一下这个开关」时，`saveWorkflow(stale.copy(...))`
 * 把**首帧那份旧工作流**整份写回磁盘 ⇒ 工作流被**静默还原成旧版本**
 * （不报错、界面上的 `checked` 也对，只有内容退回；重启后看到的仍是旧版本）。
 *
 * 修法是把回调经 `rememberUpdatedState` 转一手（`.value` 每次重组刷新）。
 * 本测试钉住那一行，防止被下一次重构抹掉。
 *
 * ⚠️ 行为测测不出来：本仓库无 Robolectric，Compose 组件在纯 JVM 里起不来
 * （形态照 `SwitchEditorSheetWiringTest` / `TriggerLabelWiringTest`）。
 *
 * ⚠️ 断言一律先**剥注释** —— 源码里写了长注释解释原因，只做 `contains`
 * 的话「删掉真调用、只留注释」照样绿（本仓库在 `AgentErrorDialogWiringTest`
 * 上踩过完全相同的坑）。
 *
 * ⚠️ 路径以 `app/` 为工作目录（`SourceScan.file` 的注释写明）。
 */
class LiquidToggleSwitchWiringTest {

    private val source = "src/main/java/com/chaomixian/vflow/ui/common/glass/LiquidToggleSwitch.kt"

    /** 建 `DampedDragAnimation` 的那个 `remember` 块（闭包捕获发生在这里）。 */
    private fun animationBlock(): String {
        val stripped = SourceScan.stripped(source)
        val body = SourceScan.functionBody(stripped, "val dampedDragAnimation = remember(animationScope)")
        assertNotNull(
            "找不到 `val dampedDragAnimation = remember(animationScope)` 块（被改名/重构了？）",
            body,
        )
        val text = requireNotNull(body)
        // 防空转：确认截到的是真实动画块，而不是半个空体。
        assertTrue("截到的动画块异常短，疑似读错文件", text.length > 200)
        assertTrue("动画块里应当出现 onDragStopped", text.contains("onDragStopped"))
        return text
    }

    @Test
    fun `toggle callback is routed through rememberUpdatedState`() {
        val stripped = SourceScan.stripped(source)
        assertTrue(
            "`onCheckedChange` 必须经 `rememberUpdatedState` 转一手 —— 否则 " +
                "`remember(animationScope)` 闭包会永远用首帧那个回调，" +
                "捕获旧 Workflow 快照的调用方（工作流列表开关）会把旧内容写回磁盘。",
            stripped.contains("rememberUpdatedState(onCheckedChange)"),
        )
    }

    @Test
    fun `animation block never invokes the raw captured callback`() {
        val block = animationBlock()
        assertFalse(
            "动画块里出现了对**原始** `onCheckedChange` 的直接调用 —— 那就是首帧那个回调，" +
                "必须改走 `currentOnCheckedChange`（rememberUpdatedState 的产物）。",
            block.contains("onCheckedChange?.invoke("),
        )
        assertTrue(
            "动画块里应当调用 `currentOnCheckedChange?.invoke(`（最新回调）。",
            block.contains("currentOnCheckedChange?.invoke("),
        )
    }
}
