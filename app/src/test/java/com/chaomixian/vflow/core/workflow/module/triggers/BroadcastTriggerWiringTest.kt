package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广播触发器的**接线锚定**（源码扫描型）。
 *
 * ## ⚠️ 为什么只能扫源码 —— 本批的失败模式**全是静默的**
 *
 * | 失效 | 症状 | 有行为测试会红吗 |
 * |---|---|---|
 * | 少一行 `register(...)` | 能选能配、后台永不触发 | ❌（触发器注册发生在 `TriggerService` 里，纯 JVM 起不来） |
 * | 写 `RECEIVER_NOT_EXPORTED` | 第三方应用的广播收不到 | ❌（要真机发广播） |
 * | 空 actions 时照常注册 | 白注册一个 EXPORTED receiver | ❌（`IntentFilter` 在纯 JVM 里 "not mocked"） |
 * | `ActivityPayload` 留了第二份实现 | 两处编码行为漂移 | ❌（两处都「能跑」） |
 * | extras 预算被人「统一口径」改回 48 KiB | 用户看不到后半段 extras | ❌（没有任何断言在量它） |
 *
 * ⇒ 本仓库已**三次**踩过「纯函数全绿但集成点缺失」（`CoreDexFingerprint` 的
 * 13 个单测全绿、`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`；
 * `XposedDiagnostics.messageFor` 零生产调用点）。这道防线按同一形态建。
 *
 * ⚠️ **全部先剥注释再断言** —— 本批的 KDoc 里到处写着 `RECEIVER_EXPORTED` /
 * `filterSpecOf` / `8 * 1024` 这些字样，不剥注释的话
 * 「把真实调用删掉、只留注释」照样绿（本仓库在 `AgentErrorDialogWiringTest` 上踩过）。
 *
 * ## ⚠️ 断言强度：**反证已实际做过**
 *
 * 见 `FORK.md` 与本批交付说明的「反证」段：每条关键断言都做过
 * 「把生产代码改回 bug 版本 ⇒ 确认变红」。
 */
class BroadcastTriggerWiringTest {

    private val moduleRegistryPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"
    private val handlerRegistryPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt"
    private val handlerPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/handlers/BroadcastTriggerHandler.kt"
    private val modulePath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/BroadcastTriggerModule.kt"
    private val supportPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/BroadcastTriggerSupport.kt"
    private val uiProviderPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/BroadcastTriggerUIProvider.kt"
    private val activityPayloadPath =
        "src/main/java/com/chaomixian/vflow/xposed/wire/ActivityPayload.kt"
    private val extrasCodecPath =
        "src/main/java/com/chaomixian/vflow/xposed/wire/ExtrasJsonCodec.kt"

    // ═══ ① 两处注册（漏一处 = 静默失效）═══

    @Test
    fun `the module is registered in ModuleRegistry`() {
        val src = SourceScan.stripped(moduleRegistryPath)

        assertTrue(
            "ModuleRegistry 里没有注册 BroadcastTriggerModule —— 触发器会「配不了」",
            src.contains("register(BroadcastTriggerModule(), context)"),
        )
        // 防空转：确认剥注释后仍看得到足够多的 register（文件没被拆走 / 剥离没过火）
        // ⚠️ 实测当前是 203 次，取 100 留足余量又不至于因文件被拆而失效
        val count = SourceScan.countOccurrences(src, "register(")
        assertTrue("ModuleRegistry 里的 register( 只有 $count 次，断言可能在空转", count >= 100)
    }

    @Test
    fun `the handler is registered in TriggerHandlerRegistry`() {
        val src = SourceScan.stripped(handlerRegistryPath)

        assertTrue(
            "TriggerHandlerRegistry 里没有注册 BroadcastTriggerHandler —— " +
                "触发器会「能选能配、后台永不触发」",
            src.contains("register(BroadcastTriggerModule().id) { BroadcastTriggerHandler() }"),
        )
        // 防空转
        assertTrue(
            "TriggerHandlerRegistry 的 register( 少于 10 次，断言可能在空转",
            SourceScan.countOccurrences(src, "register(") >= 10,
        )
    }

    // ═══ ② RECEIVER_EXPORTED（第三方应用广播的唯一通路）═══

    @Test
    fun `the handler registers with RECEIVER_EXPORTED and never NOT_EXPORTED`() {
        val src = SourceScan.stripped(handlerPath)

        assertTrue(
            "必须用 RECEIVER_EXPORTED —— 用户会配第三方应用发的 action，" +
                "NOT_EXPORTED 只收同应用/系统定向投递（既有实测：跨应用广播 3/3 收不到）",
            src.contains("ContextCompat.RECEIVER_EXPORTED"),
        )
        // ⚠️ 反向锁：出现 NOT_EXPORTED 说明有人「照抄了别的触发器」
        // （DND / Power / Screen 那些用 NOT_EXPORTED 是对的，但本模块相反）
        assertTrue(
            "BroadcastTriggerHandler 不得使用 RECEIVER_NOT_EXPORTED",
            !src.contains("RECEIVER_NOT_EXPORTED"),
        )
    }

    // ═══ ③ 空 actions 时不注册（§4.2.3 第二条理由的唯一机器化锁）═══

    @Test
    fun `an empty action list skips registration instead of registering an empty filter`() {
        val src = SourceScan.stripped(handlerPath)

        // ⚠️ 用 SourceScan.functionBody 按**大括号配对**截取函数体，
        // **不用字符窗口** —— 剥注释保留长度会让阈值无声翻转
        val body = SourceScan.functionBody(src, "private fun registerFor(")
        assertTrue("没找到 registerFor 的函数体，断言在空转", body != null)

        val specIdx = body!!.indexOf("filterSpecOf(")
        val registerIdx = body.indexOf("ContextCompat.registerReceiver(")
        assertTrue("registerFor 里应当调 filterSpecOf", specIdx >= 0)
        assertTrue("registerFor 里应当调 ContextCompat.registerReceiver", registerIdx >= 0)

        // ⚠️⚠️ **这条是本批唯一能锁住「精确注册」第二条理由的断言**：
        // `filterSpecOf` 返回 null（空 actions）时必须**提前 return**，
        // 否则会白注册一个 EXPORTED receiver 并白接管一堆广播。
        // 纯 JVM 测不了 `IntentFilter`（见 BroadcastTriggerHandlerTest 的类注释），
        // 所以只能扫源码。
        //
        // 判据：`?: return` 出现在 `filterSpecOf(` 之后、`registerReceiver(` 之前
        val guardIdx = body.indexOf("?: return", specIdx)
        assertTrue(
            "registerFor 里必须对 filterSpecOf 的 null 结果提前 return（不注册不可达的 receiver）",
            guardIdx in specIdx until registerIdx,
        )
    }

    @Test
    fun `buildFilter never declares an empty data scheme`() {
        val src = SourceScan.stripped(handlerPath)
        val body = SourceScan.functionBody(src, "internal fun buildFilter(")
        assertTrue("没找到 buildFilter 的函数体", body != null)

        // ⚠️⚠️ **反向锁**：`addDataScheme("")` 是**并集方案**用来补
        // 「无 data 广播」那条腿的手段（`schemes.contains("")`）。
        // 本模块走**精确注册**，一旦它出现在 buildFilter 里，
        // 说明有人改回了并集思路 —— 那必须连同「onReceive 里要重判一遍」一起重新评估，
        // 不能只改这一处。
        assertTrue(
            "buildFilter 里不得出现 addDataScheme(\"\") —— 那是并集方案的手段",
            !body!!.contains("addDataScheme(\"\")"),
        )
    }

    // ═══ ④ 两个纯函数层不得依赖 Android（纯 JVM 可测的前提）═══

    @Test
    fun `the support layer and the codec stay pure JVM`() {
        for (path in listOf(supportPath, extrasCodecPath)) {
            val src = SourceScan.stripped(path)
            assertTrue(
                "$path 不得 import 任何 android.*（否则纯 JVM 单测跑不起来）",
                !src.contains("import android."),
            )
            // 防空转
            assertTrue("$path 剥注释后过短", src.count { it == '\n' } > 30)
        }
    }

    // ═══ ⑤ ActivityPayload 委托 ExtrasJsonCodec（防第二份实现）═══

    @Test
    fun `ActivityPayload delegates to the shared codec and keeps no second copy`() {
        val src = SourceScan.stripped(activityPayloadPath)

        assertTrue(
            "ActivityPayload.encodeExtras 必须委托给 ExtrasJsonCodec",
            src.contains("ExtrasJsonCodec.encode(extras, MAX_EXTRAS_JSON_BYTES)"),
        )
        assertTrue(
            "intent_uri 的截断也必须走共享实现",
            src.contains("ExtrasJsonCodec.truncateToBytes("),
        )
        // ⚠️ 反向锁：第二份实现会让两处行为漂移，而两边都「能跑」
        assertTrue(
            "ActivityPayload 不得保留自己的 putTyped（否则是第二份实现）",
            !src.contains("private fun putTyped("),
        )
        assertTrue(
            "ActivityPayload 不得保留自己的 truncateToBytes（否则是第二份实现）",
            !src.contains("private fun truncateToBytes("),
        )
        assertTrue(
            "ActivityPayload 不得保留自己的 ExtrasResult（已被 ExtrasJsonCodec.Result 取代）",
            !src.contains("internal data class ExtrasResult"),
        )
    }

    @Test
    fun `the shared codec actually owns the implementation`() {
        val src = SourceScan.stripped(extrasCodecPath)

        assertTrue("ExtrasJsonCodec 必须自己实现 putTyped", src.contains("private fun putTyped("))
        assertTrue(
            "ExtrasJsonCodec 必须自己实现 truncateToBytes",
            src.contains("fun truncateToBytes("),
        )
        // ⚠️ 预算必须是**参数**而不是写死常量 —— 写死之后
        // 广播触发器的 8 KiB 就再也传不进去了（而那不会报错）
        assertTrue("encode 的预算必须是参数 maxBytes", src.contains("maxBytes: Int"))
    }

    // ═══ ⑥ UIProvider 接管三个列表（漏了会退化成普通文本框）═══

    @Test
    fun `the ui provider handles all three list parameters`() {
        val src = SourceScan.stripped(uiProviderPath)

        assertTrue(
            "UIProvider 必须接管 actions/data_schemes/categories —— " +
                "漏一个会让那个参数退化成自动表单的文本框（用户失去「列表增删」的交互）",
            src.contains("getHandledInputIds()"),
        )
        for (param in listOf("PARAM_ACTIONS", "PARAM_DATA_SCHEMES", "PARAM_CATEGORIES")) {
            assertTrue(
                "UIProvider 的 getHandledInputIds 里缺少 $param",
                src.contains("BroadcastTriggerModule.$param"),
            )
        }
        // ⚠️ 不接受变量的列表必须隐藏魔棒（否则用户会去点一个没有意义的按钮）
        assertTrue("列表项必须隐藏魔棒按钮", src.contains("showMagicControls = false"))
        // ⚠️ 读回必须走 stripBlank（而非 normalize）—— 保留 `"*"` 让 validate 给文案
        assertTrue(
            "readFromEditor 必须用 stripBlank（不能丢 `*`，否则用户输入会凭空消失）",
            src.contains("BroadcastTriggerSupport.stripBlank("),
        )
        val body = SourceScan.functionBody(src, "override fun readFromEditor(")
        assertTrue("readFromEditor 不得做运行时归一化", body != null &&
            !body.contains("normalizeActions(") && !body.contains("normalizeSchemes("))
    }

    // ═══ ⑦ 模块必须有 validate（防退回 BaseModule 的恒真实现）═══

    @Test
    fun `the module overrides validate so empty actions cannot be saved`() {
        val src = SourceScan.stripped(modulePath)

        assertTrue(
            "BroadcastTriggerModule 必须 override validate —— " +
                "BaseModule 的默认实现恒 true，空 actions 会被静默保存成「永不触发」的配置",
            src.contains("override fun validate("),
        )
        // 两个报错分支都要接上（否则拦下来了但没说原因）
        assertTrue(src.contains("error_vflow_trigger_broadcast_no_action"))
        assertTrue(src.contains("error_vflow_trigger_broadcast_wildcard"))

        // ⚠️⚠️ **必须断言 `false`** —— 独立复核实测：把两处 `ValidationResult(false, …)`
        // 改成 `true`，**全部 95 例新测试照旧全绿**（旧版只断言「字符串在文件里」，
        // 那只能证明「写了」，证明不了「真的会拒绝」）。
        //
        // 试过写行为测试直接调 `validate()` 断言 `isValid`，**纯 JVM 里做不到**：
        // 错误分支要读 `appContext.getString(...)`，而 `Context.getString` 是
        // `final`（无法覆写）、`Resources` 的构造器要一个 package-private 的
        // `AssetManager`（造不出来）、本项目没有 Robolectric / mockito
        // （`app/build.gradle.kts` 的 testImplementation 只有 junit / json / mockwebserver）。
        //
        // ⇒ 退回源码层，但**把判据收紧到「拒绝」本身**：逐分支截取
        // `ValidationResult(` 之后的一小段，断言第一个实参是 `false`。
        // 这比 `contains("validate(")` 强得多 —— 改 `false` → `true` 必然变红。
        val body = SourceScan.functionBody(src, "override fun validate(")
        assertTrue("没找到 validate 的函数体，断言在空转", body != null)

        for (marker in listOf("ActionsValidation.EMPTY", "ActionsValidation.WILDCARD")) {
            val idx = body!!.indexOf(marker)
            assertTrue("validate 里缺少 $marker 分支", idx >= 0)

            val after = body.substring(idx)
            val resIdx = after.indexOf("ValidationResult(")
            assertTrue("$marker 分支没有构造 ValidationResult", resIdx >= 0)

            // ⚠️ 必须**折叠空白**再比：`SourceScan.stripped` 保留换行与缩进结构
            // （那是它做大括号配对的前提），所以真实源码里 `ValidationResult(` 与
            // `false` 之间是一个换行 + 12 个空格 —— 直接 `contains("ValidationResult(false")`
            // 恒不命中（实现期实际踩到）。
            val args = after.substring(resIdx, minOf(resIdx + 40, after.length))
                .replace(Regex("\\s+"), " ")
            assertTrue(
                "⚠️ $marker 分支必须返回 ValidationResult(false, …) —— " +
                    "改成 true 等于「拦下来了但放行」，而源码扫描之外没有任何东西会发现。" +
                    "实参片段：$args",
                args.contains("ValidationResult( false"),
            )
        }
    }

    @Test
    fun `the module never overrides requiredPermissions`() {
        // 与 BroadcastTriggerModuleTest 里那条互补：那条断言的是**当前值**，
        // 这条断言的是**源码里不出现覆写**（有人为「将来可能需要」加一行空覆写也会红）
        val src = SourceScan.stripped(modulePath)
        assertTrue(
            "不得覆写 requiredPermissions（缺权限走运行时诊断，不要静态猜）",
            !src.contains("override val requiredPermissions"),
        )
    }

    // ═══ ⑧ extras 预算 = 8 KiB（防「统一口径」改回 48 KiB）═══

    @Test
    fun `the handler keeps its own smaller extras budget and explains why`() {
        // ⚠️ 这一条要同时看**代码**与**注释**（理由就写在 KDoc 里），
        // 所以刻意取两份：剥注释的查代码，原样的查 KDoc。
        val code = SourceScan.stripped(handlerPath)
        val raw = SourceScan.file(handlerPath).readText()

        assertTrue(
            "广播载荷的 extras 预算必须是 8 KiB",
            code.contains("MAX_EXTRAS_JSON_BYTES = 8 * 1024"),
        )
        // ⚠️⚠️ 这条是**防回退**的关键：有人看到 ActivityPayload 用 48 KiB
        // 会想「统一口径」，但那 48 KiB 是为 **Binder oneway 半缓冲**标定的，
        // 而本载荷**同进程传递、不经过 Binder** ⇒ 48 KiB 是为一个不存在的上限付代价。
        // KDoc 里必须写着这条理由，否则下一个人只会看到两个不同的数字。
        assertTrue(
            "MAX_EXTRAS_JSON_BYTES 的 KDoc 必须写明「不经过 Binder」的理由",
            raw.contains("不经过 Binder"),
        )
        // 反向锁：**代码里**不得出现 48 KiB 的字面量
        //（注释里提到它是允许的 —— 那正是「不要照抄这个数」的说明本身）
        assertTrue(
            "广播 Handler 的代码里不得出现 48 * 1024（那是 ActivityPayload 的预算，语境不同）",
            !code.contains("48 * 1024"),
        )
    }

    // ═══ ⑨ 必须继承 BaseTriggerHandler（不能是 ListeningTriggerHandler）═══

    @Test
    fun `the handler extends BaseTriggerHandler not ListeningTriggerHandler`() {
        val src = SourceScan.stripped(handlerPath)

        assertTrue(
            "必须继承 BaseTriggerHandler —— ListeningTriggerHandler 的四个方法全 final、" +
                "只在「空↔非空」边界触发 ⇒ 已有 1 个触发器时再加第 2 个，它的 receiver 永不注册",
            src.contains("class BroadcastTriggerHandler : BaseTriggerHandler()"),
        )
        assertTrue(
            "不得继承 ListeningTriggerHandler",
            !src.contains("ListeningTriggerHandler()"),
        )
    }
}
