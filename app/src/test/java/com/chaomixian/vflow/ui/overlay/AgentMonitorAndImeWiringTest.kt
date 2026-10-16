package com.chaomixian.vflow.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「虚拟屏输入法上屏」+「运行监视器」两条链路的**接线锚定**（源码扫描型）。
 *
 * ⚠️ 这两条链路的失效模式都是**静默**的：
 *  - `setDisplayImePolicy` 没被调用 / 调用位置在建屏**之前** ⇒ 键盘照样弹在用户主屏，
 *    没有任何行为测试会红（平台 API 也不报错，只返回 false）；
 *  - `IME_POLICY_LOCAL` 写错常量值（**LOCAL 是 0 不是 1**）⇒ 静默变成 FALLBACK/HIDE；
 *  - `AgentOverlayManager` 又长出自己的一份 `pauseSignal` ⇒ 监视器的暂停按钮与悬浮窗不同步；
 *  - manifest 少写 `resizeableActivity` ⇒ 小窗进不去（只在真机上肉眼可见）；
 *  - 三语 strings 漏一个 ⇒ 该语言下显示成另一语言的文案。
 *
 * ⚠️ 断言前先剥注释（源码里到处是这些符号的说明文字）。
 */
class AgentMonitorAndImeWiringTest {

    private fun rawFile(relativeToApp: String): String {
        val file = File(relativeToApp)
        assertTrue("找不到 $relativeToApp（当前目录 ${File(".").absolutePath}）", file.exists())
        return file.readText()
    }

    private fun codeOnly(relative: String): String {
        val text = rawFile("src/main/java/com/chaomixian/vflow/$relative")
        val withoutBlockComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlockComments.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
    }

    // ---- 虚拟屏输入法上屏 ----

    @Test
    fun `virtual display sets the ime policy to local right after creation`() {
        val vdm = codeOnly("core/utils/VirtualDisplayManager.kt")
        assertTrue(
            "⚠️ LOCAL 是 0（FALLBACK 才是 1），写错就静默失效",
            vdm.contains("private const val IME_POLICY_LOCAL = 0")
        )
        val createIdx = vdm.indexOf("ShellManager.createVirtualDisplay(")
        val policyIdx = vdm.indexOf("ShellManager.setVirtualDisplayImePolicy(displayId, IME_POLICY_LOCAL)")
        assertTrue("必须设置输入法策略", policyIdx > 0 && createIdx > 0)
        assertTrue("策略必须在**建屏成功之后**设置（屏还不存在时会被 WMS 拒绝）", policyIdx > createIdx)
        assertTrue("策略失败不得阻断任务（只记 warning）", vdm.contains("输入法策略未设置成功"))

        val shell = codeOnly("services/ShellManager.kt")
        assertTrue(
            shell.contains("suspend fun setVirtualDisplayImePolicy(displayId: Int, policy: Int): Boolean")
        )

        val service = codeOnly("services/ShizukuUserService.kt")
        assertTrue(service.contains("override fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean"))
        // ⚠️ 这里断言的是**源码文本**，所以 `$Stub` 在文件里带反斜杠（Kotlin 转义），
        //    别用带转义的期望值去比 —— 拆成两段更稳。
        assertTrue("隐藏 API 只能反射调（IWindowManager）", service.contains("android.view.IWindowManager"))
        assertTrue("隐藏 API 只能反射调（Stub.asInterface）", service.contains("asInterface"))
        assertTrue("必须从 ServiceManager 拿 window 服务", service.contains("android.os.ServiceManager"))

        val aidl = rawFile("src/main/aidl/com/chaomixian/vflow/services/IShizukuUserService.aidl")
        assertTrue(
            "AIDL 事务码必须显式指定（与既有 1..7 不冲突）",
            aidl.contains("boolean setDisplayImePolicy(int displayId, int policy) = 8;")
        )
    }

    // ---- 虚拟屏中文输入 ----

    @Test
    fun `virtual display input tries the vflow ime before falling back to shell`() {
        val tools = codeOnly("core/workflow/module/interaction/AgentTools.kt")
        val imeIdx = tools.indexOf("VFlowImeManager.inputText(appContext, text)")
        val shellIdx = tools.indexOf("input \${getDisplayOption()} text")
        assertTrue("虚拟屏分支必须先试 vFlow IME（中文的唯一可行路径）", imeIdx > 0)
        assertTrue("shell `input text` 只能作为回退（仅 ASCII）", shellIdx > imeIdx)

        val ime = codeOnly("services/VFlowIME.kt")
        assertTrue("IME 必须回报『有没有真的拿到 InputConnection』", ime.contains("var lastCommitHadConnection"))
        assertTrue(
            "空连接时不得静默 return",
            ime.contains("lastCommitHadConnection = inputConnection != null")
        )

        val manager = codeOnly("core/utils/VFlowImeManager.kt")
        assertTrue(
            "返回值必须是『真的上屏了』而不是『广播发出去了』",
            manager.contains("return broadcastSent && committed")
        )
    }

    // ---- 运行监视器 ----

    /**
     * ⚠️ 这条锁的是一个**真机实测的崩溃**：AutoGLM 走到 `Type` 指令时整步失败，
     * 原因是 `VFlowImeManager` 直接读 `Settings.Secure.ENABLED_INPUT_METHODS` ——
     * Android 14 起该键对 `targetSdk >= 34` 的 App 不可读（本 App targetSdk 36），
     * 抛 SecurityException 并打断整条工作流。**表现还是时好时坏的**：
     * 当前输入法恰好是 vFlow IME 时不会走到那行，所以另一个智能体看起来"正常"。
     */
    @Test
    fun `ime manager reads restricted secure settings through the shell`() {
        val src = codeOnly("core/utils/VFlowImeManager.kt")
        assertTrue(
            "默认输入法必须走 shell 读",
            src.contains("readSecureSettingViaShell(context, Settings.Secure.DEFAULT_INPUT_METHOD)")
        )
        assertTrue(
            "⚠️ enabled_input_methods 对 targetSdk >= 34 不可读，必须走 shell",
            src.contains("readSecureSettingViaShell(context, Settings.Secure.ENABLED_INPUT_METHODS)")
        )
        assertTrue(
            "input 入口必须有 try/catch 兜底（异常一旦抛出就会打断整条工作流）",
            src.contains("inputTextInternal(context, text, keyAction)") && src.contains("交由调用方回落")
        )
        assertTrue(
            "读不到 enabled_input_methods 时不得阻断（降级为直接尝试切换）",
            src.contains("跳过启用步骤")
        )
    }

    /**
     * ⚠️ 这条锁的是另一个真机现象：**「监视器」按钮没显示出来**。
     * 根因是按钮行宽度溢出 —— 卡片原先 `WRAP_CONTENT` + 三个带图标的按钮合计 ≈406dp，
     * 超过外屏的 ≈392dp ⇒ 卡片居中后两侧被裁，最右边那个按钮直接被挤出屏幕。
     * 现在改成「卡片撑满 + 按钮 weight 分配 + 纯文字」，任何屏宽/文案长度都不会再溢出。
     */
    @Test
    fun `overlay button row cannot overflow the screen`() {
        val src = codeOnly("ui/overlay/AgentOverlayManager.kt")
        assertTrue(
            "卡片必须带边距撑满（`marginStart` 是这条修复的标记）",
            src.contains("marginStart = dp(12f)")
        )
        assertTrue(
            "按钮必须用 weight 分配宽度，而不是各自 WRAP_CONTENT",
            src.contains("LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)")
        )
        assertEquals(
            "每个按钮都必须拿到 weighted params —— 漏一个就会把别的挤出去",
            2,
            Regex("layoutParams = weightedButtonParams\\(\\)").findAll(src).count()
        )
    }

    @Test
    fun `overlay exposes a monitor entry and delegates run control`() {
        val overlay = codeOnly("ui/overlay/AgentOverlayManager.kt")
        assertTrue("必须能打开监视器", overlay.contains("AgentMonitorActivity::class.java"))
        assertTrue("activity 模式必须自动打开监视器", overlay.contains("openMonitor()"))
        assertTrue(
            "⚠️ 监视器入口必须**只**由参数驱动 —— 悬浮窗上那个按钮已按用户要求移除",
            !overlay.contains("btnMonitor")
        )
        assertTrue(
            "⚠️ 运行控制状态必须已委托给总线 —— 自己再长一份 pauseSignal 就会与监视器不同步",
            !overlay.contains("pauseSignal")
        )
        assertTrue(overlay.contains("suspend fun awaitState() = AgentRunMonitor.awaitState()"))
        assertTrue(
            "show/dismiss 必须登记/结束本轮（监视器靠它判断有没有在跑）",
            overlay.contains("AgentRunMonitor.begin(") && overlay.contains("AgentRunMonitor.finish()")
        )
        assertTrue(
            "模型原文必须进总线（去标签后）—— 且走**流式替换**那条路",
            overlay.contains("AgentRunMonitor.publishStreamingThought(stripped)")
        )
        assertTrue(
            "模块名与步数上限必须由模块传进来",
            overlay.contains("private val moduleLabel: String") && overlay.contains("private val maxSteps: Int")
        )
    }

    /**
     * ⚠️ 这条锁的是**集成点**（纯函数测试全绿也拦不住的那一类）：
     *
     * 流式正文的「同轮就地替换」语义只在 `AgentRunMonitor` 里成立 —— 宿主**没在每轮开头调**
     * `beginThoughtRound()` 的话，第 2 轮的流式正文会把第 1 轮那条**顶掉**（旧条目凭空消失）。
     * 反过来，`AgentRunMonitor` 的替换语义写得再对，只要宿主忘了调，真机上就是另一副样子。
     *
     * 两个宿主的轮次边界位置：
     *  - `AgentModule`：观察装配（`AgentObservationProvider`）**每轮开头**（引擎每轮先观察再往返）；
     *  - `AutoGLMModule`：`while (currentStep < maxSteps)` 的**每步开头**（它不走共享引擎）。
     */
    @Test
    fun `both agent modules open a thought round every round`() {
        val agent = codeOnly("core/workflow/module/interaction/AgentModule.kt")
        val observationIdx = agent.indexOf("AgentObservationProvider {")
        val agentRoundIdx = agent.indexOf("overlayManager.beginThoughtRound()")
        assertTrue(
            "AgentModule 的观察装配（每轮开头）必须调 beginThoughtRound()",
            observationIdx > 0 && agentRoundIdx > observationIdx
        )

        val autoglm = codeOnly("core/workflow/module/interaction/AutoGLMModule.kt")
        val loopIdx = autoglm.indexOf("while (currentStep < maxSteps)")
        val glmRoundIdx = autoglm.indexOf("overlayManager.beginThoughtRound()")
        assertTrue(
            "AutoGLM 的每步开头必须调 beginThoughtRound()",
            loopIdx > 0 && glmRoundIdx > loopIdx
        )

        val overlay = codeOnly("ui/overlay/AgentOverlayManager.kt")
        assertTrue(
            "悬浮窗必须把 beginThoughtRound 转发到总线",
            overlay.contains("fun beginThoughtRound()") &&
                overlay.contains("AgentRunMonitor.beginThoughtRound()")
        )
    }

    @Test
    fun `monitor mode is a step parameter and gates the overlay preview`() {
        val overlay = codeOnly("ui/overlay/AgentOverlayManager.kt")
        assertTrue(overlay.contains("const val MONITOR_MODE_OVERLAY = \"overlay\""))
        assertTrue(overlay.contains("const val MONITOR_MODE_ACTIVITY = \"activity\""))
        assertTrue(
            "activity 模式下必须关掉悬浮窗预览（否则白拷一份帧）",
            overlay.contains("val usesOverlayPreview: Boolean get() = monitorMode != MONITOR_MODE_ACTIVITY")
        )
        // ⚠️ 用户明确要求：`activity` 模式下**连那张只有文字的卡片也不要**。
        //    断言"打开监视器"发生在建窗**之前** —— 也就是提前 return 了，一个悬浮窗都没建。
        val showIdx = overlay.indexOf("fun show()")
        val openIdx = overlay.indexOf("openMonitor()", showIdx)
        val addViewIdx = overlay.indexOf("windowManager.addView(borderRoot", showIdx)
        assertTrue(
            "activity 模式必须提前返回、不建任何悬浮窗（openMonitor 必须在建窗之前）",
            showIdx >= 0 && openIdx in showIdx until addViewIdx
        )
        listOf(
            "core/workflow/module/interaction/AgentModule.kt",
            "core/workflow/module/interaction/AutoGLMModule.kt",
        ).forEach { relative ->
            val src = codeOnly(relative)
            assertTrue("$relative 必须有 monitor_mode 参数", src.contains("\"monitor_mode\""))
            assertTrue("$relative 必须把 monitorMode 传给悬浮窗", src.contains("monitorMode = monitorMode,"))
            assertTrue(
                "$relative 的预览注册必须被 usesOverlayPreview 把关",
                src.contains("targetDisplayId > 0 && overlayManager.usesOverlayPreview")
            )
        }
        listOf("values", "values-en", "values-ja").forEach { dir ->
            val xml = rawFile("src/main/res/$dir/strings_module.xml")
            listOf(
                "param_vflow_ai_agent_monitor_mode_name",
                "option_vflow_ai_agent_monitor_overlay",
                "option_vflow_ai_agent_monitor_activity",
            ).forEach { key ->
                assertTrue("$dir/strings_module.xml 缺少 `$key`", xml.contains("name=\"$key\""))
            }
        }
    }

    /**
     * ⚠️ 这三条锁的是用户真机反馈的两个 bug + 一处布局要求：
     *  - 悬浮窗的「确定?」原先 **3 秒后自动回退** ⇒ 用户犹豫超过 3 秒再点，第二次点击被当成
     *    第一次重新起确认，表现就是「再点一次仍然没反应，按钮又变回结束」；
     *  - `requestCancel()` 原先只改一个普通字段、**不进 StateFlow** ⇒ 点了「结束」UI 毫无变化，
     *    看起来像"没反应"（主循环要等下一次 `awaitState()` 才退出，可能还在一次几十秒的调用里）；
     *  - 思考流原先与预览各占 `weight(1f)`（半屏）⇒ 用户要求"两三行就够"。
     */
    @Test
    fun `stop confirm cannot silently expire and cancel is visible`() {
        val overlay = codeOnly("ui/overlay/AgentOverlayManager.kt")
        assertTrue(
            "⚠️ 「确定?」不得再自动回退 —— 倒计时会把用户的第二次点击吃掉（回退是唯一会把它写回「结束」的地方）",
            !overlay.contains("btnCancel?.text = \"结束\"")
        )

        val monitor = codeOnly("ui/overlay/AgentRunMonitor.kt")
        assertTrue(
            "取消必须立刻反映到 StateFlow（否则点了「结束」UI 毫无变化）",
            monitor.contains("it.copy(cancelling = true)")
        )

        val content = codeOnly("ui/overlay/AgentMonitorContent.kt")
        assertTrue("取消受理后按钮必须显示「正在停止…」并置灰", content.contains("snapshot.cancelling"))
        assertTrue(
            "ENDED 之后不得再显示控制按钮（主循环已退出，点了不会有任何反应）",
            content.contains("val controllable = snapshot.phase == AgentRunMonitor.Phase.RUNNING")
        )
        assertTrue(
            "思考流必须是固定高度（原来和预览各占半屏，太高）",
            content.contains("height(THOUGHT_STREAM_HEIGHT)")
        )
        assertTrue(
            "⚠️ 自动跟滚必须礼让用户 —— 只有追加前贴着底部时才滚，" +
                "否则用户往上翻历史时每来一条新思考就被拽回底部（等于不能往上滚）",
            // ⚠️ 实现已改：原先用"追加前是否贴底"判断，实测**恒为 false、永远不跟滚**；
            //    现在是显式的开关 —— 默认跟到最新，用户一碰就停，滚回底部再恢复。
            content.contains("var autoFollow by remember") &&
                content.contains("autoFollow = false") &&
                content.contains("autoFollow = last >= info.totalItemsCount - 1")
        )
    }

    /**
     * ⚠️ 这条锁的是折叠屏的布局诉求：**展开态内屏 ≈ 两块手机屏并排**，用户希望
     * "左侧虚拟屏画面、右侧思考过程"；普通手机与折叠态外屏则维持上下堆叠。
     *
     * ⚠️ 断点**必须**是这里自己的常量，不能借 `MainComposeShell` 的 840dp
     * （那是"是否显示侧边导航栏"的判据，还要求宽大于高；`ChatScreen` 踩过这个坑，见 FORK.md）。
     */
    @Test
    fun `monitor layout adapts to the available width`() {
        val content = codeOnly("ui/overlay/AgentMonitorContent.kt")
        assertTrue("必须按可用宽度分支", content.contains("BoxWithConstraints"))
        assertTrue(
            "必须有独立的双栏阈值常量",
            content.contains("MONITOR_TWO_PANE_MIN_WIDTH = 600.dp")
        )
        assertTrue(
            "宽屏必须左右分栏",
            content.contains("if (maxWidth >= MONITOR_TWO_PANE_MIN_WIDTH)")
        )
        assertTrue(
            "刻意不复用侧边栏的 840dp 断点",
            !content.contains("840.dp")
        )
    }

    @Test
    fun `both agent modules pass module label and max steps`() {
        listOf(
            "core/workflow/module/interaction/AgentModule.kt",
            "core/workflow/module/interaction/AutoGLMModule.kt",
        ).forEach { relative ->
            val src = codeOnly(relative)
            assertTrue("$relative 必须传 moduleLabel", src.contains("moduleLabel = metadata.getLocalizedName("))
            assertTrue("$relative 必须传 maxSteps", src.contains("maxSteps = maxSteps,"))
        }
    }

    @Test
    fun `monitor activity is declared resizable and not pip`() {
        val manifest = rawFile("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains(".ui.overlay.AgentMonitorActivity"))
        assertTrue(
            "必须显式声明可调整大小（否则进不了小窗/分屏）",
            manifest.contains("android:resizeableActivity=\"true\"")
        )
        // ⚠️ 比的是**属性形式**而不是裸名字 —— manifest 里有一句注释也提到了 PiP（注释不是声明）。
        assertTrue(
            "刻意不声明 PiP（小窗交给系统手势/最近任务）",
            !manifest.contains("android:supportsPictureInPicture")
        )
    }

    /**
     * ⚠️ 这条锁的是一个**真机日志实证**（2026-10-14）：`tool_choice: "required"` 下
     * glm-4.6v-flash **不输出任何文字**（日志里 `AI Output:` 是空白，多数步骤连这一行都没有），
     * 于是监视器与日志里都看不到模型说了什么；而同一个工作流里的 **AutoGLM（没有 tool_choice）
     * 却有完整的中文推理** —— 用户原话「GLM 反而有、AI Agent 反而没有」。
     *
     * 强制工具调用约束的只是"必须产出 tool_calls"，**并不禁止 content**；但这个模型把它理解成了
     * "你的输出就是那个调用"。
     *
     * ## fork 批 4 起：断言**跟着实现搬家**
     *
     * `AgentModule` 不再自己拼请求体（改走共享传输层）⇒ 原来那两条「模块源码里必须写
     * `tool_choice: auto` / 必须有 `noToolCallCount` 兜底」的锚点已随实现搬走。不变量本身
     * **仍在**，只是换了锚点：
     * 1. **任何一方都不得强制 `required`** —— 模块与传输层都不许出现它；
     * 2. 共享传输层**根本不发** `tool_choice` ⇒ 服务端默认就是 `auto`（与 fork 的修复同效，
     *    而且比「显式写 auto」更少一层可漂移的字段）；
     * 3. 「只说不做」不再是失败路径：模型不调工具 ⇒ 由共享引擎按 **D17-②** 正常收尾
     *    （`Success(finalText, success=true)`），故 `noToolCallCount` 兜底**已按设计删除**。
     */
    @Test
    fun `agent module must not force tool choice`() {
        val moduleSrc = codeOnly("core/workflow/module/interaction/AgentModule.kt")
        val transportSrc = codeOnly("ui/chat/ChatCompletionClient.kt")

        assertTrue(
            "⚠️ 模块不得再用 tool_choice=required —— 那个模型会因此完全不写文字（真机日志实证）",
            !moduleSrc.contains("\"tool_choice\", \"required\"")
        )
        assertTrue(
            "⚠️ 传输层同样不得强制 required（它现在是唯一的请求装配点）",
            !transportSrc.contains("\"tool_choice\", \"required\"")
        )
        assertTrue(
            "共享传输层**根本不发** tool_choice ⇒ 服务端默认 auto（fork 的修复意图仍成立）",
            !transportSrc.contains("tool_choice")
        )
        assertTrue(
            "「只说不做」的兜底已按 D17-② 删除（模型不调工具 = 正常收尾，不再是 3 次后失败）",
            !moduleSrc.contains("noToolCallCount")
        )
        assertTrue(
            "收尾语义现在由共享引擎负责 ⇒ 模块必须真的走引擎",
            moduleSrc.contains("engine.runLoop(")
        )
    }
    /**
     * ⚠️ 这两条锁的是「监视器里只有状态文案、看不到模型说了什么」这个真机反馈：
     *  - 硬编码的阶段文案**不得**进思考流（走 `publishStatus`）；
     *  - 模型原文**整段**进思考流（不再只抽 think 标签内部 —— `AgentModule` 会丢掉闭合标签之后的
     *    文字，`AutoGLMModule` 更是"模型不用标签就什么都不显示"）。
     *
     * ⚠️ 标签字面量用**拼装**构造，不直接写 —— `<...>` 形态的标签在工具输出/输入里会被渲染层吞掉，
     *    直接写会得到假文本（写这条测试时实测踩过一次）。
     */

    @Test
    fun `only model output goes into the thought stream`() {
        val openTag = "<" + "think" + ">"
        val closeTag = "<" + "/think" + ">"

        val overlay = codeOnly("ui/overlay/AgentOverlayManager.kt")
        assertTrue(
            "阶段状态必须走 publishStatus（**不进**思考流）",
            overlay.contains("AgentRunMonitor.publishStatus(status)")
        )
        assertTrue(
            "模型原文必须走**流式替换**那条（`publishStreamingThought`）—— " +
                "引擎发来的是**累积正文**，走 `publishThought`（逐条追加）会渲染成三角形（真机症状）",
            overlay.contains("AgentRunMonitor.publishStreamingThought(stripped)")
        )
        assertTrue(
            "去标签必须覆盖 XML 形态的 think 标签（不是 ` think`）",
            // ⚠️ 不带 `raw.` 前缀 —— 两个 replace 是**链式**的，只有第一个带 `raw.`
            overlay.contains("replace(\"$closeTag\", \" \")") &&
                overlay.contains("replace(\"$openTag\", \" \")")
        )

        listOf(
            "core/workflow/module/interaction/AgentModule.kt",
            "core/workflow/module/interaction/AutoGLMModule.kt",
        ).forEach { relative ->
            val src = codeOnly(relative)
            assertTrue(
                "$relative 必须把**模型原文**交给 publishModelOutput",
                src.contains("publishModelOutput(content,")
            )
            assertTrue(
                "$relative 不得再用 substringAfter 抽 think（那会丢掉标签之外的文字）",
                !src.contains("content.substringAfter(")
            )
        }
    }

    @Test
    fun `monitor strings exist in all three languages`() {
        val keys = listOf(
            "agent_monitor_title",
            "agent_monitor_empty",
            "agent_monitor_pause",
            "agent_monitor_resume",
            "agent_monitor_stop",
            "agent_monitor_confirm",
            "agent_monitor_stopping",
            "agent_monitor_no_preview",
        )
        listOf("values", "values-en", "values-ja").forEach { dir ->
            val xml = rawFile("src/main/res/$dir/strings.xml")
            keys.forEach { key ->
                assertTrue("$dir/strings.xml 缺少 `$key`（三语必须同步）", xml.contains("name=\"$key\""))
            }
        }
    }
}
