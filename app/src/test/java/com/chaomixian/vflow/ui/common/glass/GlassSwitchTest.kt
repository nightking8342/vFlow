package com.chaomixian.vflow.ui.common.glass

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玻璃开关的**尺寸锚定 + 滑块定位几何 + 全项目接线**。
 *
 * ## ⚠️ 为什么这三样都必须机器化
 *
 * 三个失败模式**全是静默的**：
 * 1. **尺寸抄错**（轨道 52×32、滑块 24/16）—— 看一眼不觉得，但「保留 M3 形态」
 *    的承诺已经破了，且没有任何行为断言会红；
 * 2. **滑块算出界** —— 按下时滑块变宽，锚定方向搞反会顶出轨道边缘
 *    （`Box` 默认**不裁剪**，所以不报错，只是看起来「滑块探出来了」）；
 * 3. **有调用点漏换** —— 表现为「有的开关是玻璃、有的还是实色」，
 *    而这**比全不玻璃更扎眼**，却完全编译得过。
 *
 * ## 尺寸的核对方式（不是照文档抄的）
 *
 * 从 `material3` 字节码里读出来的：`javap -c -p` 解 `SwitchTokens`
 * 静态初始化块 ⇒ `TrackWidth 52 / TrackHeight 32 / SelectedHandleWidth 24 /
 * UnselectedHandleWidth 16 / TrackOutlineWidth 2`，
 * `ThumbPadding = (TrackHeight − SelectedHandleWidth) / 2 = 4`。
 * 而 `SwitchKt` 的静态块显示它读的就是这几个 token（不是另有一套常量）。
 */
class GlassSwitchTest {

    private val trackWidth: Dp get() = GlassSwitchTokens.TrackWidth

    /**
     * 滑块宽度 = **生产代码的**直径函数 + 按下撑开。
     *
     * ⚠️ 直径那一半**必须直接调 `thumbDiameterDp`**，不能在这里复刻插值公式 ——
     * 本仓库在 `ScallopedBadgeShapeTest` 上踩过：测试自己抄一份公式，
     * 把生产代码改坏时**测试照绿**（抄的那份没变）。
     */
    private fun thumbWidth(fraction: Float, pressed: Boolean): Dp =
        thumbDiameterDp(fraction) +
            if (pressed) GlassSwitchTokens.ThumbPressGrowth else 0.dp

    // ------------------------------------------------------------------
    // 一、尺寸锚定 M3 的 SwitchTokens
    // ------------------------------------------------------------------

    @Test
    fun `尺寸与 Material 3 的 SwitchTokens 逐值一致`() {
        assertEquals("SwitchTokens.TrackWidth", 52f, trackWidth.value, 0.001f)
        assertEquals("SwitchTokens.TrackHeight", 32f, GlassSwitchTokens.TrackHeight.value, 0.001f)
        assertEquals("SwitchTokens.SelectedHandleWidth", 24f, GlassSwitchTokens.ThumbDiameter.value, 0.001f)
        assertEquals(
            "SwitchTokens.UnselectedHandleWidth",
            16f,
            GlassSwitchTokens.UncheckedThumbDiameter.value,
            0.001f,
        )
        assertEquals(
            "(TrackHeight − SelectedHandleWidth) / 2",
            4f,
            GlassSwitchTokens.ThumbPadding.value,
            0.001f,
        )
        assertEquals(
            "SwitchTokens.TrackOutlineWidth",
            2f,
            GlassSwitchTokens.TrackOutlineWidth.value,
            0.001f,
        )
    }

    @Test
    fun `轨道比滑块高一档才留得出内边距`() {
        assertTrue(
            "轨道高度应大于选中态滑块直径（32 > 24），否则滑块贴死上下边缘",
            GlassSwitchTokens.TrackHeight > GlassSwitchTokens.ThumbDiameter,
        )
    }

    @Test
    fun `按下撑开量必须小于两侧内边距之和`() {
        // 关态滑块左边距只有 ThumbPadding = 4dp，撑开量的一半从中心往外扩。
        // 超过它就顶出左端 —— 本文件里最容易踩的一个约束。
        assertTrue(
            "ThumbPressGrowth/2 = ${GlassSwitchTokens.ThumbPressGrowth.value / 2} 必须 ≤ " +
                "ThumbPadding = ${GlassSwitchTokens.ThumbPadding.value}",
            GlassSwitchTokens.ThumbPressGrowth / 2 <= GlassSwitchTokens.ThumbPadding,
        )
    }

    // ------------------------------------------------------------------
    // 二、滑块定位几何
    // ------------------------------------------------------------------

    @Test
    fun `三个静止锚点与 M3 逐值一致`() {
        // M3 的滑块中心：关 = 4 + 16/2 = 12，开 = 52 − 4 − 24/2 = 36。
        // 这两个数直接来自 SwitchTokens，改任一个都要重算。
        val off = glassThumbXDp(0f, baseThumbWidthDp(false))
        assertEquals("关态左边距 = ThumbPadding = 4dp", 4f, off.value, 0.001f)

        val onW = baseThumbWidthDp(true)
        val onX = glassThumbXDp(1f, onW)
        assertEquals(
            "开态右边缘 = TrackWidth − ThumbPadding = 48dp",
            48f,
            (onX + onW).value,
            0.001f,
        )

        // ⚠️ 中间态：这是老写法（if checked 右对齐 else 左对齐）唯一塌掉的地方 ——
        //    它会算出中心 12dp（等于关态），即滑块在动画中途往左弹一下。
        val midW = thumbWidth(0.5f, pressed = false)
        val midX = glassThumbXDp(0.5f, midW)
        assertEquals(
            "中间态中心应落在 (12 + 36) / 2 = 24dp —— 线性插值的中点",
            24f,
            (midX + midW / 2).value,
            0.001f,
        )
    }

    @Test
    fun `位置关于 fraction 单调不减`() {
        // 反向锁：滑块**不能**在动画中途往回走。老写法在 0.5 处回退到 12dp
        // 就是这条会红的地方。
        var prev = Float.NEGATIVE_INFINITY
        for (i in 0..40) {
            val f = i / 40f
            val x = glassThumbXDp(f, thumbWidth(f, pressed = false))
            assertTrue("fraction=$f 时滑块位置 $x 比上一帧 $prev 更靠左", x.value >= prev - 0.001f)
            prev = x.value
        }
    }

    @Test
    fun `任意状态下滑块都完整落在轨道内`() {
        // ⚠️ 按下撑开最容易出事的地方：`Box` 默认不裁剪，
        //    滑块探出边缘不会报错、也不会被切，只是看起来不对。
        for (checked in listOf(false, true)) {
            for (pressed in listOf(false, true)) {
                val f = if (checked) 1f else 0f
                val w = thumbWidth(f, pressed)
                val x = glassThumbXDp(f, w)
                assertTrue(
                    "checked=$checked pressed=$pressed: 左边缘 ${x.value} 越过了轨道左端",
                    x.value >= -0.001f,
                )
                assertTrue(
                    "checked=$checked pressed=$pressed: 右边缘 ${(x + w).value} 越过了轨道右端 " +
                        "${trackWidth.value}",
                    (x + w).value <= trackWidth.value + 0.001f,
                )
            }
        }
    }

    @Test
    fun `按下撑开从中心均分到两侧`() {
        for (f in listOf(0f, 0.5f, 1f)) {
            val rest = thumbWidth(f, pressed = false)
            val pressedW = thumbWidth(f, pressed = true)
            val centerRest = glassThumbXDp(f, rest) + rest / 2
            val centerPressed = glassThumbXDp(f, pressedW) + pressedW / 2
            assertEquals(
                "fraction=$f 按下时中心不应移动（撑开量左右各一半）",
                centerRest.value,
                centerPressed.value,
                0.001f,
            )
        }
    }

    @Test
    fun `滑块宽高由同一个值驱动 —— 不会出现竖条`() {
        // ⚠️ 这条锁的是用户实测反馈过的那只 bug：「关闭时中间那个圆的白的
        //    东西都不贴合」。当时的实现只动宽度、高度写死成轨道内高，
        //    关态渲染出 16×24 的竖条。
        for (i in 0..20) {
            val f = i / 20f
            val d = thumbDiameterDp(f)
            assertTrue(
                "fraction=$f 时直径 ${d.value} 应落在两档之间（16..24）",
                d.value >= 16f - 0.001f && d.value <= 24f + 0.001f,
            )
        }
        assertEquals("fraction=0 应是未选中档", 16f, thumbDiameterDp(0f).value, 0.001f)
        assertEquals("fraction=1 应是选中档", 24f, thumbDiameterDp(1f).value, 0.001f)
        // 关态滑块的高度必须**小于**轨道内高（32 − 4×2 = 24），否则就是竖条。
        assertTrue(
            "关态直径 16dp 必须小于轨道内高 24dp —— 相等正是那只竖条 bug",
            thumbDiameterDp(0f) < GlassSwitchTokens.TrackHeight - GlassSwitchTokens.ThumbPadding * 2,
        )
    }

    @Test
    fun `拖动参考宽度为正且小于轨道宽`() {
        // 分母为 0 会让 `fraction + dx/0` 变成 Infinity/NaN（滑块瞬移到边上且不报错）。
        for (checked in listOf(false, true)) {
            val w = dragWidthDp(checked)
            assertTrue("checked=$checked 的拖动参考宽度必须为正，实际 ${w.value}", w.value > 0f)
            assertTrue(
                "拖动参考宽度应当**小于**轨道宽（它扣掉了两侧内边距与滑块）—— " +
                    "等于轨道宽会让跟手感发飘",
                w < GlassSwitchTokens.TrackWidth,
            )
        }
    }

    @Test
    fun `未选中滑块比选中态窄 —— 与 M3 的动画一致`() {
        assertTrue(
            "M3 未选中 16dp、选中 24dp；两者相同时就丢了 M3 那个「长大」的过渡",
            GlassSwitchTokens.UncheckedThumbDiameter < GlassSwitchTokens.ThumbDiameter,
        )
    }

    // ------------------------------------------------------------------
    // 三、实现里必须真的用了 kyant 的折射链路
    // ------------------------------------------------------------------

    @Test
    fun `玻璃本体走 drawBackdrop 而不是手画高光`() {
        // ⚠️⚠️ **这条是本改动最重要的一条**。第一版用 `drawWithCache` 手画
        //    「渐变描边 + 半透明白」冒充玻璃，用户验收原话是
        //    「完全没有液态玻璃的效果」—— 因为真正让玻璃成立的是
        //    **折射**（`lens` 的 RuntimeShader）与库自带的高光/内阴影着色器，
        //    描边和渐变只是它们的粗糙模仿。
        val source = SourceScan.stripped(GLASS_SWITCH)
        for (required in listOf(
            "drawBackdrop(",
            "lens(",
            "Highlight.Ambient",
            "InnerShadow(",
            "rememberLayerBackdrop()",
            "chromaticAberration = true",
        )) {
            assertTrue(
                "GlassSwitch 的实现里必须出现 `$required` —— 缺了它就不是真玻璃，" +
                    "而是手画的仿制品（第一版就是这么被否掉的）",
                source.contains(required),
            )
        }
        // ⚠️⚠️ 这条必须锚**具体那个 lambda 的体内**，不能只断言「源码里有 `1f - p`」——
        //    实测：把 `onDrawSurface` 里的褪白改掉之后，**别的三处 `1f - p`**
        //    （模糊衰减、压扁曲线的 `cos`）会让弱断言照样绿（反证不变红）。
        val surface = SourceScan.functionBody(source, "onDrawSurface = {")
            ?: error("找不到滑块的 onDrawSurface —— 那条「白随按下退掉」的断言会失去依据")
        assertTrue(
            "滑块的白必须**随按下进度退掉**：onDrawSurface 体内应出现 `1f - p`。" +
                "否则按下时看不到底下被折射的轨道色 —— 那是整个观感最「液态」的一帧",
            surface.contains("1f - p"),
        )
        assertFalse(
            "onDrawSurface 里不应出现「不随进度衰减」的纯白常量（那就是第一版的写法）",
            surface.contains("Color.White.copy(alpha = 0.95f)"),
        )
    }

    @Test
    fun `轨道被压扁成滑块的采样源且静止时压扁量为零`() {
        // ⚠️ `scaleY = lerp(0f, 0.75f, progress)` 是 kyant 原实现的做法：
        //    静止时 0 ⇒ 采样里等于没有轨道 ⇒ **避免「滑块采样自己盖住的轨道」
        //    这个自采样回环**（会每帧叠一层）。改成恒定的 1f 就会形成回环，
        //    而表现只是「按下时颜色越来越怪」，很难联想到原因。
        val source = SourceScan.stripped(GLASS_SWITCH)
        val body = SourceScan.functionBody(source, "rememberBackdrop(trackBackdrop)")
            ?: error("找不到轨道被包进 rememberBackdrop 的那一段")
        assertTrue(
            "压扁量必须随按下进度走（`0.75f * p` 这类写法），不能是常量",
            body.contains("0.75f") && body.contains("pressProgress"),
        )
    }

    @Test
    fun `抬手必须落位到 0 或 1，不能停在中间`() {
        // ⚠️ 这条锁的是「按得亮、拖不动」那条反馈的另一半：光把 `fraction`
        //    跟着手指移、抬手后不落位，滑块会**停在半路**（看起来就像没拖动）。
        //    拖动落位是 `onEnd` 里那两行，用源码扫描锚定 ——
        //    手势回调在纯 JVM 里起不来，只能扫。
        val s = SourceScan.stripped(SWITCH_DRAG_ANIMATION)
        // ⚠️ 判据分两处，不能混：**落位**发生在 `modifier` 属性的
        //    `onEnd` lambda 里（不在手势循环函数体内），而**手势原语**
        //    在手势循环函数体内。第一版两条都往函数体里找，`settleTo`
        //    永远找不到 —— 而这会让人以为是生产代码漏了落位。
        // ⚠️⚠️ **必须锚抬手回调的体内，不能只断言「源码里有 `settleTo(`」** ——
        //    `settleTo` 的方法**定义**本身就含这个字符串，于是把唯一那处**调用**
        //    删掉之后断言照样绿（实测确认，反证不变红）。这正是本仓库
        //    「断言要经过调用点」那条教训的又一例。
        val onEnd = SourceScan.functionBody(s, "onEnd = { dragged ->")
            ?: error("找不到抬手回调 —— 这条断言会失去依据")
        assertTrue(
            "拖过之后必须调 `settleTo(` 把值落到 0 或 1；缺了它滑块会停在半路",
            onEnd.contains("settleTo("),
        )
        assertTrue(
            "落位方向必须按 fraction 与 0.5 的关系判",
            onEnd.contains("0.5f"),
        )
        assertTrue(
            "落位后必须回调 `onSettled(` —— 否则滑块停右边而 `checked` 还是 false（状态脱节）",
            onEnd.contains("onSettled("),
        )
        assertTrue(
            "抬手必须调 `release()`（速度/按压缩放归位）",
            s.contains("release()"),
        )
        assertTrue(
            "`settleTo` 必须清速度 —— 不清的话滑块会永远保持上一次拖动末尾的挤压形变",
            s.contains("velocityAnimation.snapTo(0f)"),
        )

        val body = SourceScan.functionBody(s, "private suspend fun PointerInputScope.inspectPressDragGestures")
            ?: error("找不到手势循环 —— 手势原语那几条断言会失去依据")

        // 手势层必须用本仓库已验证的原语（见 DragGestureInspector 的实测记录）
        for (required in listOf(
            "positionChangeIgnoreConsumed()",
            "PointerEventPass.Initial",
            "consume()",
        )) {
            assertTrue(
                "手势循环里必须出现 `$required` —— 缺它会让「按得亮、拖不动」复发",
                body.contains(required),
            )
        }
    }

    // ------------------------------------------------------------------
    // 四、全项目接线（源码扫描）
    // ------------------------------------------------------------------

    @Test
    fun `全项目不再有直接使用 M3 Switch 的调用点`() {
        val offenders = mutableListOf<String>()
        val root = File("src/main/java/com/chaomixian/vflow")
        check(root.isDirectory) { "源码目录不存在（测试工作目录应为 app/）：${root.absolutePath}" }
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            // ⚠️ 必须先剥注释：本组件的 KDoc 里到处是 `Switch(...)` 字样
            //    （解释「为什么不给 M3 Switch 换颜色槽」），不剥的话恒红。
            val s = SourceScan.stripCommentsPreservingStructure(f.readText())
            Regex("(?<![\\w.])(?<!VFlow)Switch\\(").findAll(s).forEach { m ->
                // 合法例外：**两个**玻璃开关实现文件里各自的 M3 回退分支。
                // ⚠️ 漏了新增的那个（LiquidToggleSwitch.kt）会让这条恒红，
                //    而恒红的断言会被下一个实现者直接删掉。
                if (f.name != "GlassSwitch.kt" && f.name != "LiquidToggleSwitch.kt") {
                    offenders += "${f.path}:${s.take(m.range.first).count { it == '\n' } + 1}"
                }
            }
        }
        assertEquals(
            "这些调用点还在直接用 M3 Switch —— 液态玻璃打开后它们会是实色的，" +
                "而「有的开关是玻璃、有的不是」比全不玻璃更扎眼。改用 VFlowSwitch：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `VFlowSwitch 覆盖到了两个工作流卡片调用点`() {
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "两张卡片（列表模式 + 瀑布流）的启用开关都必须走 VFlowSwitch",
            2,
            SourceScan.countOccurrences(source, "VFlowSwitch("),
        )
    }

    @Test
    fun `接线扫描不是空转`() {
        val root = File("src/main/java/com/chaomixian/vflow")
        var total = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            total += Regex("VFlowSwitch\\(").findAll(
                SourceScan.stripCommentsPreservingStructure(f.readText())
            ).count()
        }
        assertTrue("全项目应至少有一批 VFlowSwitch 调用点，实际 $total", total >= 10)
    }

    // ------------------------------------------------------------------
    // 五、照抄库示例的那一版（LiquidToggleSwitch）
    // ------------------------------------------------------------------

    @Test
    fun `轻点由手势层处理并消费，不再用 toggleable`() {
        // ⚠️⚠️ 这条锁的是「关闭状态下点开关，很大几率点进工作流内部」那只 bug。
        //    根因是**两个手势检测器抢同一个 down**：`toggleable` 内部的
        //    `detectTapAndPress` 会消费它 ⇒ 拖动侧饿死；而当 `toggleable`
        //    判断「这次算点击」时又未必消费干净 ⇒ 事件冒泡到卡片的
        //    `combinedClickable`。
        //    ⇒ 正解是**一处承担**：手势层自己判「有没有拖过」，且抬手一律消费。
        val switchSrc = SourceScan.stripped(GLASS_SWITCH)
        assertFalse(
            "GlassSwitch 不得再用 `toggleable(` —— 它的 detectTapAndPress 会消费 down，" +
                "把拖动侧饿死，而点击又可能冒泡进卡片",
            switchSrc.contains("toggleable("),
        )
        assertTrue(
            "无障碍语义要显式补上（原来由 toggleable 自带）：Role.Switch + toggleableState + onClick",
            switchSrc.contains("Role.Switch") &&
                switchSrc.contains("toggleableState") &&
                switchSrc.contains("onClick("),
        )

        val dragSrc = SourceScan.stripped(SWITCH_DRAG_ANIMATION)
        val body = SourceScan.functionBody(dragSrc, "private suspend fun PointerInputScope.inspectPressDragGestures")
            ?: error("找不到手势循环")
        // ⚠️⚠️ 判据必须锚**抬手那个分支体内**，不能只断言「body 里有 `change.consume()`」——
        //    拖动分支里本来就有一处 `consume()`，所以把抬手那处删掉后
        //    弱断言**照样绿**（反证 Y 实测确认）。
        val upBranch = body.substringAfter("if (!change.pressed)", "")
            .substringBefore("val delta")
        assertTrue(
            "抬手时必须无条件 `change.consume()` —— 轻点不消费会冒泡到宿主卡片",
            upBranch.contains("change.consume()"),
        )
        assertTrue(
            "没拖过时应回调 `onTap(`（轻点切换由手势层驱动）",
            dragSrc.contains("onTap("),
        )
    }

    @Test
    fun `液态玻璃开关必须每次组合都读，不能 remember 缓存`() {
        // ⚠️⚠️ 用户反馈「玻璃效果似乎没有判断液态玻璃开关是否启用，好像不管启没启用
        //    都是这个玻璃效果」。真因是 `remember(context) { … }` —— 它只在
        //    **首次组合**时读一次，之后哪怕设置页把开关关掉，这里拿到的仍是首帧值。
        //
        //    `SharedPreferences` 的读是内存缓存，每次组合读一次的代价可忽略。
        val source = SourceScan.stripped(LIQUID_TOGGLE)
        val body = SourceScan.functionBody(source, "fun VFlowSwitch(") ?: error("找不到 VFlowSwitch")
        assertFalse(
            "VFlowSwitch 里不得对 `isLiquidGlassNavBarEnabled` 做 remember 缓存 —— " +
                "那会让「关掉液态玻璃」当场不生效",
            body.contains("remember(context)") ||
                body.contains("remember {") &&
                body.contains("isLiquidGlassNavBarEnabled"),
        )
        assertTrue(
            "VFlowSwitch 必须直接调用 AppearanceManager.isLiquidGlassNavBarEnabled(context)",
            body.contains("AppearanceManager.isLiquidGlassNavBarEnabled(context)"),
        )
    }

    @Test
    fun `开关的手势挂在最外层而不是滑块上`() {
        // ⚠️⚠️ 用户反馈「点击开关还是会穿透进入到工作流页面」。
        //    滑块只有 26×20dp，手势挂在它上面时**只覆盖那一小块**，
        //    点到轨道其余地方事件就冒泡到卡片的 combinedClickable。
        //    正解：手势挂最外层 + minimumInteractiveComponentSize 抬到 48dp 下限。
        val source = SourceScan.stripped(LIQUID_TOGGLE)
        val body = SourceScan.functionBody(source, "internal fun LiquidToggleSwitch(")
            ?: error("找不到 LiquidToggleSwitch")
        assertTrue(
            "最外层 Box 必须挂手势（dampedDragAnimation.modifier）",
            body.contains("dampedDragAnimation.modifier"),
        )
        assertTrue(
            "必须调 minimumInteractiveComponentSize() —— 轨道只有 24dp 高，" +
                "裸放达不到 Material 的 48dp 可点下限",
            body.contains("minimumInteractiveComponentSize()"),
        )
        // 手势只能挂一次（挂两处会收到两遍事件、透镜位置跳成两处）
        assertEquals(
            "手势只应挂一次",
            1,
            SourceScan.countOccurrences(body, "dampedDragAnimation.modifier"),
        )
    }

    @Test
    fun `卡片调用点不得给开关传 requiredSize 或 scale`() {
        // ⚠️⚠️ 这条锁的是用户 2026-10-06 反馈的「开启状态时滑块会超出轨道边界」。
        //    真因**不在开关里**，而在调用点传了 `requiredSize(48.dp, 28.dp)`：
        //    它覆盖父约束，轨道画出来是 48dp，而滑块的位移/宽度仍按常量的
        //    55dp 算 ⇒ 右边缘跑到轨道外面；关闭态的右侧缝隙也被挤掉。
        //
        //    `scale` 同样不能传：它缩的是**玻璃层**，会让 `drawBackdrop`
        //    的采样区与绘制区错位（M3 时代缩的是真实控件，换玻璃后语义变了）。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        val calls = Regex("""VFlowSwitch\(""").findAll(source)
        var checked = 0
        calls.forEach { m ->
            // 取到本调用的右括号
            var i = m.range.last + 1
            var depth = 1
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            val block = source.substring(m.range.first, i)
            assertFalse(
                "工作流卡片上的 VFlowSwitch 不得传 requiredSize —— 它会覆盖父约束，" +
                    "而滑块的位移/宽度仍按常量算 ⇒ 开启态滑块超出轨道边界",
                block.contains("requiredSize"),
            )
            assertFalse(
                "工作流卡片上的 VFlowSwitch 不得传 scale —— 它缩的是玻璃层，" +
                    "会让 drawBackdrop 的采样区与绘制区错位",
                block.contains(".scale("),
            )
            checked++
        }
        assertTrue("应至少扫到 2 个卡片调用点（列表模式 + 瀑布流），实际 $checked", checked >= 2)
    }

    @Test
    fun `VFlowSwitch 的玻璃态走的是示例版（第三次定版）`() {
        // ⚠️ 这条锁的是 2026-10-06 的**最终决策**。中间曾回退到
        //    `GlassSwitch`（M3 尺寸），原因是把三件事混在一起判断了：
        //    ① 示例轨道偏宽 ② 关闭时柱位看不清 ③ 点不亮/拖不动。
        //    复核后：② 的真因是 `pressedScale = 1.5f`（不是宽度）、
        //    ③ 的真因是 `toggleable`（示例原版根本没有它）。
        //    ⇒ 只保留一处偏离（轨道 64 → 60），其余照抄示例。
        val source = SourceScan.stripped(LIQUID_TOGGLE)
        assertTrue(
            "VFlowSwitch 的玻璃分支必须调 `LiquidToggleSwitch(`（照抄库示例那版）",
            Regex("""if \(glassEnabled\)[\s\S]{0,400}LiquidToggleSwitch\(""").containsMatchIn(source),
        )
        // 示例原版**没有** toggleable —— 点击全在 DampedDragAnimation 里判 didDrag。
        assertFalse(
            "不得给示例版加回 `toggleable(` —— 它内部的 detectTapAndPress 与拖动" +
                "抢同一个 down，正是「点不亮、要拖才动」的根因",
            source.contains("toggleable("),
        )
        assertTrue(
            "轻点分支**不能**用闭包捕获的 `checked`（在 remember 里捕获一次 ⇒ 第二次" +
                "点击算出的目标与第一次相同 ⇒ 点一次能关、再点开不了）；要用同一帧的 fraction",
            source.contains("val next = if (fraction >= 0.5f) 0f else 1f"),
        )
    }

    @Test
    fun `示例版的尺寸与库内示例逐值一致`() {
        // 用户 2026-10-06 明确要求「完全按照库里的示例实现一版」，
        // 这三个尺寸就是那次要求的直接落点，改动即偏离示例。
        // ⚠️⚠️ **唯一**的偏离是「整体等比缩小」：轨道 64 → 55，**其余尺寸同乘一个系数**。
        //    只收轨道而让滑块原地不动，会让滑块占比从 62.5% 升到 66.7% ——
        //    关闭态两侧露出的柱位更窄（用户上一版反馈的「看不到槽位」会被放大）。
        assertEquals("示例 64dp，本项目等比缩到 55dp", 55f, LiquidToggleTokens.defaultTrackWidth.value, 0.001f)
        assertEquals("示例 28dp", 24f, LiquidToggleTokens.defaultTrackHeight.value, 0.001f)
        // ⚠️⚠️ 滑块宽是**唯一**比「等比缩放」还小的一项：等比应为 34，
        //    而用户 2026-10-06 反馈「滑块应该再小一点」+ 关闭态右侧缝隙太少。
        assertEquals("示例 40dp，本项目收到 26（比等比的 34 还小，见 Token KDoc）",
            26f, LiquidToggleTokens.defaultThumbWidth.value, 0.001f)
        assertEquals("示例 24dp", 20f, LiquidToggleTokens.defaultThumbHeight.value, 0.001f)
        assertTrue(
            "滑块占轨道的比例应接近 M3 的 46%（24/52）—— 太大则关闭态看不到柱位",
            LiquidToggleTokens.defaultThumbWidth.value / LiquidToggleTokens.defaultTrackWidth.value in 0.4f..0.55f,
        )
        assertEquals("示例 padding = 2f.dp", 2f, LiquidToggleTokens.paddingDp.value, 0.001f)
        // ⚠️⚠️ **pressedScale 必须照抄 1.5f，不许收回**。它一度被我误判成
        //    「关闭时那个圆变小」的真因而改成 1f，用户随后明确指出：
        //    原版就是「拖动时滑块浮起并变大覆盖掉轨道」—— 那正是本控件的核心观感。
        assertEquals("示例 pressedScale = 1.5f（不可收回）", 1.5f, LiquidToggleTokens.PressedScale, 0.001f)
    }

    @Test
    fun `滑块可移动距离与轨道宽同步（收窄轨道后自动变小）`() {
        // ⚠️ 示例里直接写死 `dragWidth = 20f.dp`，而 64 − 40 − 2×2 恰好是 20。
        //    生产代码**按公式算**（不是抄常量），动机是「改尺寸后仍自洽」。
        //
        // ⚠️⚠️ **必须说清这条断言的强度**：它锁的是**数值**（= 20），
        //    而不是「是否按公式算」—— 实测把实现换成硬编码 `20.dp` 时
        //    这条**照样绿**（两者等价，反证 T 已实际做过）。
        //    真正被它抓住的是**改尺寸**：把 `TrackWidth` 改成 52 会让它红
        //    （反证 V 实测 3 条红），而那正是「忘了同步 travel」的后果。
        //    ⇒ 「按公式写」这一层是**约定**，没有机器化守卫（写在这里以免高估它）。
        // 55 − 26 − 2×2 = 25（示例是 64 − 40 − 4 = 20）
        assertEquals(25f, liquidToggleTravelDp().value, 0.001f)
        assertTrue(
            "可移动距离必须为正，否则拖动时分母为 0（fraction 变 NaN 且不报错）",
            liquidToggleTravelDp().value > 0f,
        )
    }

    @Test
    fun `缩放后各尺寸的相对比例与示例一致`() {
        // ⚠️ 这条锁的是「等比缩小」这个约定本身 —— 只改其中一两个数值会让
        //    滑块占比漂移，而观感变化（柱位露多少）是**没有报错**的。
        val s = LiquidToggleTokens.Scale
        assertEquals("轨道高 / 轨道宽 的比例", 28f / 64f, LiquidToggleTokens.defaultTrackHeight.value / LiquidToggleTokens.defaultTrackWidth.value, 0.005f)
        // ⚠️ **滑块宽刻意不参与等比** —— 收到 26 让占比降到 47%（近 M3 的 46%），
        //    目的是关闭态两侧各露 27dp、柱位一眼可见。这条断言只锁「别涨回去」。
        assertTrue(
            "滑块占轨道比例应明显低于示例的 62.5%（那是「关闭时看不到柱位」的根源）",
            LiquidToggleTokens.defaultThumbWidth.value / LiquidToggleTokens.defaultTrackWidth.value < 0.55f,
        )
        // ⚠️ 高度方向**做不到精确等比**：24 × 0.859 = 20.63，而 dp 只能取整数
        //    （取 20 ⇒ 比例 0.833，取 21 ⇒ 0.875，都比 0.857 偏）。
        //    容差按这个取值粒度给，不假装它是精确的。
        assertEquals(
            "滑块高 / 轨道高 的比例（20/24 是取整结果，容差按 1dp 粒度给）",
            24f / 28f,
            LiquidToggleTokens.defaultThumbHeight.value / LiquidToggleTokens.defaultTrackHeight.value,
            0.025f,
        )
        assertTrue("缩放系数应小于 1（我们是缩小）", s in 0.5f..1f)
    }

    @Test
    fun `滑块位置在两端恰好贴合且不越界`() {
        assertEquals(
            "fraction=0 时滑块左边距 = padding = 2dp",
            2f,
            liquidToggleThumbXDp(0f, isLtr = true).value,
            0.001f,
        )
        // ⚠️ 端点位置 = padding + travel = 2 + 20 = 22dp，**不是** 64 − 40 − 2 = 22
        //    （同一个数，但写成 62 是把「右边缘」当成了「左边距」—— 第一版就这么错的）。
        val endX = liquidToggleThumbXDp(1f, isLtr = true).value
        assertEquals("fraction=1 时左边距 = padding + travel = 27dp", 27f, endX, 0.001f)
        assertEquals(
            "于是右边缘 = 27 + 26 = 53dp，距轨道右端恰好也是 padding",
            53f,
            endX + LiquidToggleTokens.defaultThumbWidth.value,
            0.001f,
        )
        assertTrue(
            "滑块右边缘必须落在轨道内（64dp）",
            endX + LiquidToggleTokens.defaultThumbWidth.value <= LiquidToggleTokens.defaultTrackWidth.value + 0.001f,
        )
        assertEquals(
            "RTL 下应向左偏移",
            -liquidToggleThumbXDp(0.5f, isLtr = true).value,
            liquidToggleThumbXDp(0.5f, isLtr = false).value,
            0.001f,
        )
    }

    @Test
    fun `示例版必须真的用上折射链路与示例的三个关键参数`() {
        val s = SourceScan.stripped(LIQUID_TOGGLE)
        // 折射链路（与第一版手画高光的区别就在这几个符号上）
        for (required in listOf("drawBackdrop(", "lens(", "Highlight.Ambient", "InnerShadow(", "chromaticAberration = true")) {
            assertTrue("示例版实现里必须出现 `$required`", s.contains(required))
        }
        // 示例的三处「运镜」
        assertTrue("示例：滑块的白随 progress 退到 0", s.contains("1f - progress"))
        assertTrue("示例：静止时轨道采样压扁量为 0 的起点", s.contains("THUMB_SAMPLE_SCALE_Y_MAX"))
        assertTrue("pressedScale 须由常量传入", s.contains("LiquidToggleTokens.PressedScale"))
        assertTrue(
            "示例：轨道采样横向压扁范围 lerp(2/3, 0.75)",
            s.contains("THUMB_SAMPLE_SCALE_X_MIN") && s.contains("THUMB_SAMPLE_SCALE_X_MAX"),
        )
        // 拖动落位与轻点切换（示例的两条分支）
        assertTrue("拖动结束必须按 targetValue 落位", s.contains("targetValue >= 0.5f"))
        // ⚠️ 判据不能写 `if (checked) 0f else 1f` —— 那正是**有 bug 的那版**；
        //    正确写法是用同一帧的 `fraction` 判（闭包捕获的 `checked` 只在
        //    首次 `remember` 时取到值 ⇒ 第二次点击目标不变）。
        assertTrue(
            "轻点必须按同一帧的 fraction 取反（不能用闭包捕获的 checked）",
            s.contains("val next = if (fraction >= 0.5f) 0f else 1f"),
        )
    }

    @Test
    fun `对外入口只有一个 VFlowSwitch（避免同名重载歧义）`() {
        // ⚠️ 上一版把 `VFlowSwitch` 写在 GlassSwitch.kt 里，本版写在
        //    LiquidToggleSwitch.kt 里 —— 两个同签名顶层函数会直接编译失败
        //    （Conflicting overloads）。这条断言把它变成一条清晰的失败信息。
        var count = 0
        val root = File("src/main/java/com/chaomixian/vflow/ui/common/glass")
        root.listFiles()?.filter { it.extension == "kt" }?.forEach { f ->
            val s = SourceScan.stripCommentsPreservingStructure(f.readText())
            count += Regex("""(?<![\w.])fun VFlowSwitch\(""").findAll(s).count()
        }
        assertEquals("`fun VFlowSwitch(` 应恰好定义一次", 1, count)
        assertTrue(
            "定义应在 LiquidToggleSwitch.kt（示例版）里",
            SourceScan.stripped(LIQUID_TOGGLE).contains("fun VFlowSwitch("),
        )
    }

    private companion object {
        const val GLASS_SWITCH = "src/main/java/com/chaomixian/vflow/ui/common/glass/GlassSwitch.kt"
        const val LIQUID_TOGGLE =
            "src/main/java/com/chaomixian/vflow/ui/common/glass/LiquidToggleSwitch.kt"
        const val WORKFLOW_LIST_SCREEN =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
        const val SWITCH_DRAG_ANIMATION =
            "src/main/java/com/chaomixian/vflow/ui/common/glass/SwitchDragAnimation.kt"
    }
}
