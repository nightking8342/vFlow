package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 广播触发器模块的**声明体检**。
 *
 * 设计文档：`docs/fork/broadcast-trigger-design.md` §4。
 *
 * ## ⚠️ 这类测试存在的理由
 *
 * 触发器模块的**声明**（id / 分类 / 权限 / 输入 / 输出）写错了**不会有任何报错** ——
 * 表现为「配不了」「配了不触发」「输出取不到值」。
 * 本仓库因此在 `SimDataSwitch` / `ActivityChanged` 上专门写了同类的体检测试。
 *
 * ⚠️ 本类**不测 `validate()` 的文案分支** —— 它要 `appContext`
 * （`BaseModule` 的 `protected lateinit`，只在 `ModuleRegistry.register` 时注入），
 * 纯 JVM 里访问会抛 `UninitializedPropertyAccessException`。
 * 纯函数层的行为由 `BroadcastTriggerSupportTest` 覆盖。
 */
class BroadcastTriggerModuleTest {

    private val module = BroadcastTriggerModule()

    @Test
    fun `id has the trigger prefix and matches the design doc`() {
        // ⚠️ `vflow.trigger.` 前缀是**必须的** —— 系统据此前缀与
        // metadata.categoryId 一起判定它是触发器（两者缺一不可）
        assertEquals("vflow.trigger.broadcast", module.id)
        assertTrue(module.id.startsWith("vflow.trigger."))
    }

    @Test
    fun `metadata declares the trigger category`() {
        assertEquals("trigger", module.metadata.categoryId)
        assertEquals("触发器", module.metadata.category)
    }

    @Test
    fun `module icon differs from other trigger modules`() {
        // ⚠️ 图标撞车会让用户在模块选择器里**分不清谁是谁** ——
        // 而这不报错，只有肉眼能看出来
        assertTrue(
            "图标与 ActivityChangedTriggerModule 撞车",
            module.metadata.iconRes != ActivityChangedTriggerModule().metadata.iconRes,
        )
        assertTrue(
            "图标与 SimDataSwitchTriggerModule 撞车",
            module.metadata.iconRes != SimDataSwitchTriggerModule().metadata.iconRes,
        )
    }

    @Test
    fun `declares no required permissions`() {
        // ⚠️⚠️ **反向锁，本模块最重要的声明之一**。
        //
        // action 由用户自由填写，**无法静态推导需要什么权限**；
        // 按前缀猜（如 SMS_RECEIVED ⇒ RECEIVE_SMS）会误判。
        // 而 `TriggerService.handleWorkflowChanged` 会在注册前检查权限、
        // 缺失时**静默把整个工作流置为 isEnabled=false**
        // ⇒ 「声明得不对」的代价是**整个工作流被静默禁用**，不是「少收几条」。
        //
        // ⇒ 刻意为空 + 运行时诊断（Handler 的 registerReceiver try/catch + ERROR 日志）。
        // 加任何一条都会让**每一个**广播触发器（哪怕只是监听一个第三方 App 的自定义 action）
        // 无条件向用户索要敏感权限。
        assertTrue(
            "requiredPermissions 必须为空（缺权限走运行时诊断，不要静态猜）",
            module.requiredPermissions.isEmpty(),
        )
    }

    @Test
    fun `inputs declare exactly the five schema parameters`() {
        val inputs = module.getInputs()
        val ids = inputs.map { it.id }.toSet()
        assertEquals(
            setOf("actions", "data_schemes", "categories", "match_mode", "cooldown_ms"),
            ids,
        )
        // 参数 key 必须是 snake_case（仓库代码风格约定）
        ids.forEach { id ->
            assertTrue("参数 key `$id` 应为 snake_case", id.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `the three list parameters are ANY with empty defaults and no variables`() {
        val byId = module.getInputs().associateBy { it.id }
        for (id in listOf("actions", "data_schemes", "categories")) {
            val def = byId.getValue(id)
            assertEquals("$id 应为 ANY（由 UIProvider 接管渲染）", ParameterType.ANY, def.staticType)
            assertEquals("$id 的默认值应为空列表", emptyList<String>(), def.defaultValue)
            // ⚠️ action/scheme/category 是**字面量标识**，填 `{{...}}` 没有语义 ——
            // 而且 IntentFilter 在工作流执行**之前**就注册好了，运行期解析没有意义
            assertTrue("$id 不得接受魔法变量", !def.acceptsMagicVariable)
            assertTrue("$id 不得接受命名变量", !def.acceptsNamedVariable)
        }
    }

    @Test
    fun `actions is declared required while the other lists are not`() {
        val byId = module.getInputs().associateBy { it.id }
        // ⚠️ `isRequired` 在这里只作契约声明（被 UIProvider 接管的参数自动表单不渲染它），
        // 真正的拦截在 validate()
        assertTrue("actions 必须是必填（平台没有通配写法）", byId.getValue("actions").isRequired)
        assertTrue(!byId.getValue("data_schemes").isRequired)
        assertTrue(!byId.getValue("categories").isRequired)
    }

    @Test
    fun `match_mode offers exactly one stable option`() {
        val mode = module.getInputs().first { it.id == "match_mode" }
        assertEquals(ParameterType.ENUM, mode.staticType)
        assertEquals(BroadcastTriggerModule.MATCH_EXACT, mode.defaultValue)
        // ⚠️⚠️ **反向锁**：options **恰为** `["exact"]`。
        // `IntentFilter` 做不了子串匹配，而「注册得足够宽」没有合法写法
        // ⇒ 加 `contains` 等于给出一个「看起来能用、实际永远收不到」的选项。
        assertEquals(listOf("exact"), mode.options)
        // 选项是**稳定常量**，不能存本地化文案（切语言后已保存的工作流失配）
        assertEquals(BroadcastTriggerModule.MATCH_EXACT, "exact")
        // 只有一项时用 CHIP_GROUP 更直观（DROPDOWN 会渲染成只含一项的下拉）
        assertEquals(InputStyle.CHIP_GROUP, mode.inputStyle)
        assertTrue(!mode.acceptsMagicVariable)
    }

    @Test
    fun `match_mode has a localized option so the chip is not a raw constant`() {
        // ⚠️ 只有一项的 chip 若拿不到本地化文案，会显示成裸 `exact`。
        // 已核 ActionEditorSheet：自动表单的 ENUM 主路径**会**传 optionsStringRes
        // ⇒ 我们的模块（走自动表单）显示正常。这条断言只是把「必须配资源」钉住。
        val mode = module.getInputs().first { it.id == "match_mode" }
        assertEquals(1, mode.options.size)
        assertEquals(1, mode.optionsStringRes.size)
    }

    @Test
    fun `cooldown default is positive and not a magic variable`() {
        val cd = module.getInputs().first { it.id == "cooldown_ms" }
        assertEquals(ParameterType.NUMBER, cd.staticType)
        val default = cd.defaultValue as? Number
        assertNotNull(default)
        // ⚠️ 默认必须 > 0：本模块注册的是 EXPORTED receiver（任意应用可发），
        // 不设冷却会让一个高频广播瞬间刷爆工作流
        assertTrue("冷却默认值应大于 0，实际=$default", default!!.toLong() > 0L)
        assertTrue(!cd.acceptsMagicVariable)
    }

    @Test
    fun `outputs match the payload fields one to one`() {
        val outputs = module.getOutputs(null).map { it.id }.toSet()
        assertEquals(
            setOf(
                "action", "data_uri", "scheme", "mime_type",
                "categories", "extras_json", "flags", "truncated",
            ),
            outputs,
        )
    }

    @Test
    fun `truncated output is a boolean so users can detect partial extras`() {
        // ⚠️ 载荷可能被截断（超预算）**或** extras 整体读不出（BadParcelableException）。
        // 不把它暴露出来的话，「extras 少了几个键」会被当成「那个应用本来就没传」
        val truncated = module.getOutputs(null).first { it.id == "truncated" }
        assertEquals(VTypeRegistry.BOOLEAN.id, truncated.typeName)
    }

    @Test
    fun `categories output is a list of strings`() {
        val categories = module.getOutputs(null).first { it.id == "categories" }
        assertEquals(VTypeRegistry.LIST.id, categories.typeName)
        assertEquals(VTypeRegistry.STRING.id, categories.listElementType)
    }

    @Test
    fun `extras_json is a single string not per-key outputs`() {
        // ⚠️ extras 的键**不可枚举**（任意应用都能塞任意键），
        // 所以只给一个 JSON 字符串 + 空字典键声明，由下游用 JSON 模块解析。
        // 为每个键建输出是做不到的
        val extras = module.getOutputs(null).first { it.id == "extras_json" }
        assertEquals(VTypeRegistry.STRING.id, extras.typeName)
        assertTrue("不得逐键建输出", extras.dictionaryKeys.isEmpty())
    }

    @Test
    fun `flags is a number`() {
        assertEquals(
            VTypeRegistry.NUMBER.id,
            module.getOutputs(null).first { it.id == "flags" }.typeName,
        )
    }

    @Test
    fun `no sender package output is exposed`() {
        // ⚠️⚠️ **反向锁**：`Intent.getSentFromPackage()` 是 API 34+ 且默认 null
        //（发送方需 `BroadcastOptions.setShareIdentityEnabled(true)` opt-in）
        // ⇒ 暴露它 = 永远是 null = **误导**（用户会以为「这个广播没有发送方」）。
        // 用户 2026-07-07 已明确否掉鉴权层。
        val outputs = module.getOutputs(null).map { it.id }
        assertTrue("不得暴露 sender_package", !outputs.any { it.contains("sender") })
    }

    @Test
    fun `uiProvider handles exactly the three list parameters`() {
        val provider = module.uiProvider
        assertNotNull("三个列表参数必须由自绘编辑器接管", provider)
        assertEquals(
            setOf("actions", "data_schemes", "categories"),
            provider!!.getHandledInputIds(),
        )
    }

    @Test
    fun `every declared input has a localized name resource`() {
        // 漏一个 nameStringRes 会让某个语言下显示成硬编码中文
        module.getInputs().forEach { def ->
            assertNotNull("参数 `${def.id}` 缺 nameStringRes", def.nameStringRes)
        }
    }

    @Test
    fun `every declared output has a localized name resource`() {
        module.getOutputs(null).forEach { def ->
            assertNotNull("输出 `${def.id}` 缺 nameStringRes", def.nameStringRes)
        }
    }

    // ═══ 三语文案 ═══

    @Test
    fun `all three languages declare the same broadcast trigger keys`() {
        // ⚠️ 三份 `strings_module.xml` 的键名集合**必须逐字一致**：
        // 漏一个会导致该语言下回落到默认（中文），而这不报错。
        //
        // ⚠️ 测试工作目录是 `app/`（Gradle 默认）
        val zh = broadcastKeys("src/main/res/values/strings_module.xml")
        val en = broadcastKeys("src/main/res/values-en/strings_module.xml")
        val ja = broadcastKeys("src/main/res/values-ja/strings_module.xml")

        assertTrue("中文文案里没有 vflow_trigger_broadcast 键", zh.isNotEmpty())
        assertEquals("英文文案的键集合与中文不一致", zh, en)
        assertEquals("日文文案的键集合与中文不一致", zh, ja)
    }

    @Test
    fun `the broadcast trigger key set is not accidentally tiny`() {
        // 防空转：若上面的 `broadcastKeys` 写错了（比如匹配不到任何键），
        // 三条集合都是空集合、互相「一致」⇒ 断言全部空转通过
        val zh = broadcastKeys("src/main/res/values/strings_module.xml")
        assertTrue(
            "键数异常（${zh.size} 个），检查过滤条件是否失效",
            zh.size >= 25,
        )
    }

    @Test
    fun `no duplicate broadcast trigger keys within a file`() {
        // 重复键会让资源合并**构建失败**（`Found item String/x more than one time`），
        // 所以正常情况不会出现；这条防的是「同一次追加里写了两遍」被漏过
        for (path in listOf(
            "src/main/res/values/strings_module.xml",
            "src/main/res/values-en/strings_module.xml",
            "src/main/res/values-ja/strings_module.xml",
        )) {
            val all = Regex("""<string name="([^"]+)"""")
                .findAll(File(path).readText())
                .map { it.groupValues[1] }
                .toList()
            val dupes = all.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            assertTrue("$path 存在重复键：$dupes", dupes.isEmpty())
        }
    }

    private fun broadcastKeys(path: String): Set<String> =
        Regex("""<string name="([a-z_]*vflow_trigger_broadcast_[^"]+)"""")
            .findAll(File(path).readText())
            .map { it.groupValues[1] }
            .toSet()

    // ═══ 图标资源确实存在 ═══

    @Test
    fun `the module icon resource exists on disk`() {
        // ⚠️ 图标按**字符串名** + `getIdentifier` 解析（Material Symbols 全量库），
        // 所以 `R.drawable.rounded_settings_input_antenna_24` 能编过不代表资源在
        // —— 这里落地检查一次，防的是「改名后编译照过、运行时图标空白」
        val file = File("src/main/res/drawable/rounded_settings_input_antenna_24.xml")
        assertTrue("图标资源不存在：${file.absolutePath}", file.isFile)
    }

    @Test
    fun `no requiredPermissions override exists in the module source`() {
        // ⚠️ 与上面的 `declares no required permissions` 互补：
        // 那条断言的是**当前值**，这条断言的是**源码里不出现覆写**
        // —— 有人为了「将来可能需要」加一行空的覆写时，也会在这里变红
        val src = File("src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/BroadcastTriggerModule.kt")
        assertTrue(src.isFile)
        // ⚠️⚠️ **必须剥注释**：本文件的 KDoc 里逐字写着
        // 「`BroadcastTriggerWiringTest` 有一条反向锁：本文件不得出现
        // `override val requiredPermissions`」—— 不剥注释的话这条断言**恒红**，
        // 而恒红的断言会被下个实现者直接删掉。
        val code = SourceScan.stripCommentsPreservingStructure(src.readText())
        assertTrue(
            "BroadcastTriggerModule 不得覆写 requiredPermissions",
            !code.contains("override val requiredPermissions"),
        )
    }
}
