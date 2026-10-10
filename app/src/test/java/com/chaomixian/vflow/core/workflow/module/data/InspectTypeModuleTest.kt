package com.chaomixian.vflow.core.workflow.module.data

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNull
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinate
import com.chaomixian.vflow.core.types.complex.VFile
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「查看数据类型」模块（`vflow.data.inspect_type`）的回归测试。
 *
 * 设计文档：`docs/fork/type-inspection-module-design.md` §8（测试计划）。
 *
 * ## 覆盖的四层
 *
 * 1. **纯函数层**（`inspectValueType`）—— 各类型 → `type_id`，以及**防空转**
 *    （`type_id` 必须来自 `VObject.type.id`，不是硬编码）；
 * 2. **模块声明体检** —— 参数 / 枚举 / 哨兵 / 权限面；
 * 3. ⚠️ **条件输出**（本模块最易错、最易静默的一处）；
 * 4. **源码扫描型接线锚定** + 三语键齐全。
 */
class InspectTypeModuleTest {

    private val module = InspectTypeModule()

    // ═══════════ A. 纯函数层 ═══════════

    /** 方案 A1：各类型 → `type_id` 正确（逐种）。 */
    @Test
    fun `inspectValueType reports the runtime type id for every value kind`() {
        val cases = listOf<VObject>(
            VImage("content://x/1.png"),
            VFile("/tmp/demo.txt"),
            VDictionary(mapOf("a" to VString("1"))),
            VList(listOf(VString("1"))),
            VNumber(1.0),
            VString("hello"),
            VBoolean(true),
            VCoordinate(1, 2),
            VNull,
        )
        val expected = listOf(
            VTypeRegistry.IMAGE.id,
            VTypeRegistry.FILE.id,
            VTypeRegistry.DICTIONARY.id,
            VTypeRegistry.LIST.id,
            VTypeRegistry.NUMBER.id,
            VTypeRegistry.STRING.id,
            VTypeRegistry.BOOLEAN.id,
            VTypeRegistry.COORDINATE.id,
            VTypeRegistry.NULL.id,
        )

        assertEquals(cases.size, expected.size)
        cases.forEachIndexed { index, value ->
            assertEquals(
                "值 ${value.javaClass.simpleName} 的 type_id 不对",
                expected[index],
                inspectValueType(value, null).typeId,
            )
        }
    }

    /**
     * ⚠️ 方案 A2：**防空转** —— `type_id` 必须来自 `VObject.type.id`。
     *
     * 这条**刻意不**断言等于硬编码字面量：把 `inspectValueType` 的 `typeId`
     * 改成硬编码 `VTypeRegistry.STRING.id` 时，它必红（反证 R1）。
     * 只断言「等于 `vflow.type.string`」的写法**看不出**这个 bug。
     */
    @Test
    fun `type id comes from the value itself, not a hardcoded literal`() {
        val cases = listOf<VObject>(
            VImage("content://x/1.png"),
            VFile("/tmp/demo.txt"),
            VDictionary(emptyMap()),
            VList(emptyList()),
            VNumber(1.0),
            VString("hello"),
            VBoolean(true),
            VCoordinate(1, 2),
            VNull,
        )
        cases.forEach { value ->
            assertEquals(
                "type_id 不是取自 value.type.id（疑似硬编码）",
                value.type.id,
                inspectValueType(value, null).typeId,
            )
        }
    }

    /** 方案 A3：期望类型匹配 ⇒ `matched = true`。 */
    @Test
    fun `matched is true when the value is of the expected type`() {
        val result = inspectValueType(VImage("content://x/1.png"), VTypeRegistry.IMAGE.id)
        assertEquals(VTypeRegistry.IMAGE.id, result.typeId)
        assertEquals(true, result.matched)
    }

    /** 方案 A4：期望类型不匹配 ⇒ `matched = false`。 */
    @Test
    fun `matched is false when the value is of a different type`() {
        val result = inspectValueType(VString("hello"), VTypeRegistry.IMAGE.id)
        assertEquals(VTypeRegistry.STRING.id, result.typeId)
        assertEquals(false, result.matched)
    }

    /** 方案 A5：「仅查看」（`expectedTypeId = null`）⇒ `matched = null`。 */
    @Test
    fun `matched is null when no expected type was chosen`() {
        val result = inspectValueType(VString("hello"), null)
        assertEquals(VTypeRegistry.STRING.id, result.typeId)
        assertNull(result.matched)
    }

    /** 方案 A6：`VNull` + 「空」⇒ `matched = true`（「空」选项可用）。 */
    @Test
    fun `the null option can match an empty value`() {
        val result = inspectValueType(VNull, VTypeRegistry.NULL.id)
        assertEquals(VTypeRegistry.NULL.id, result.typeId)
        assertEquals(true, result.matched)
    }

    // ═══════════ B. 模块声明体检 ═══════════

    @Test
    fun `module id is stable`() {
        assertEquals("vflow.data.inspect_type", module.id)
    }

    @Test
    fun `module lives in the data category`() {
        assertEquals("data", module.metadata.categoryId)
        assertEquals("数据", module.metadata.category)
    }

    /** 方案 B9：`value` 必须有 `supportsRichText = true`（漏了只有真机肉眼能发现）。 */
    @Test
    fun `the value input renders magic variables as pills`() {
        val value = module.getInputs().single { it.id == InspectTypeModule.PARAM_VALUE }
        assertTrue(
            "value 缺 supportsRichText ⇒ 用户从 🪄 选的变量会显示成裸 {{...}} 而非胶囊",
            value.supportsRichText,
        )
    }

    /** 方案 B10：`value` 是 `ANY`。 */
    @Test
    fun `the value input accepts any type`() {
        val value = module.getInputs().single { it.id == InspectTypeModule.PARAM_VALUE }
        assertEquals(ParameterType.ANY, value.staticType)
        assertTrue(value.acceptsMagicVariable)
    }

    /** 方案 B11：`expected_type` 是 `ENUM`。 */
    @Test
    fun `the expected type input is an enum`() {
        val expected = module.getInputs().single { it.id == InspectTypeModule.PARAM_EXPECTED_TYPE }
        assertEquals(ParameterType.ENUM, expected.staticType)
        assertFalse(
            "期望类型不该接受魔法变量（它必须是一个可静态归一的枚举值）",
            expected.acceptsMagicVariable,
        )
    }

    /** 方案 B12：`options.first()` 是「仅查看」且 `defaultValue` 也是它。 */
    @Test
    fun `view only is both the first option and the default`() {
        val expected = module.getInputs().single { it.id == InspectTypeModule.PARAM_EXPECTED_TYPE }
        assertEquals(InspectTypeModule.EXPECTED_VIEW_ONLY, expected.options.first())
        assertEquals(InspectTypeModule.EXPECTED_VIEW_ONLY, expected.defaultValue)
    }

    /** 方案 B13：选项与本地化资源**一一对应**。 */
    @Test
    fun `options and localized option resources are aligned`() {
        val expected = module.getInputs().single { it.id == InspectTypeModule.PARAM_EXPECTED_TYPE }
        assertEquals(
            "options 与 optionsStringRes 长度必须相等",
            expected.options.size,
            expected.optionsStringRes.size,
        )
        assertEquals(10, expected.options.size)
        // 每个资源 id 都必须存在（0 表示未设置 / 资源缺失）
        expected.optionsStringRes.forEach { res ->
            assertTrue("optionsStringRes 里出现无效资源 id：$res", res != 0)
        }
    }

    /**
     * ⚠️ 方案 B14：**哨兵不撞车**。
     *
     * 判据：`VTypeRegistry.getType(哨兵)` 回落到 `ANY`（未知 id 的行为）
     * ⇒ 哨兵不是任何已注册类型。撞车会让「仅查看」被当成一个真实类型去比较
     * （`matched` 恒假）且不报错。
     */
    @Test
    fun `the view only sentinel is not a registered type id`() {
        assertTrue(
            "哨兵必须带前缀（对齐 WORKFLOW_TAB_ALL / vflow.icon.category. 的惯例）",
            InspectTypeModule.EXPECTED_VIEW_ONLY.startsWith("vflow.inspect_type."),
        )
        assertSame(
            "哨兵与 VTypeRegistry 的某个类型 id 撞车了",
            VTypeRegistry.ANY,
            VTypeRegistry.getType(InspectTypeModule.EXPECTED_VIEW_ONLY),
        )
        // 逐项反向锁：哨兵不得出现在任何已注册类型 id 里
        val registeredIds = listOf(
            VTypeRegistry.STRING, VTypeRegistry.NUMBER, VTypeRegistry.BOOLEAN,
            VTypeRegistry.DICTIONARY, VTypeRegistry.LIST, VTypeRegistry.IMAGE,
            VTypeRegistry.FILE, VTypeRegistry.COORDINATE, VTypeRegistry.NULL,
            VTypeRegistry.ANY, VTypeRegistry.DATE, VTypeRegistry.TIME,
            VTypeRegistry.SCREEN_ELEMENT, VTypeRegistry.UI_COMPONENT,
            VTypeRegistry.COORDINATE_REGION, VTypeRegistry.NOTIFICATION,
            VTypeRegistry.EVENT, VTypeRegistry.APP,
        ).map { it.id }
        assertFalse(
            "哨兵撞上了已注册类型：${InspectTypeModule.EXPECTED_VIEW_ONLY}",
            registeredIds.contains(InspectTypeModule.EXPECTED_VIEW_ONLY),
        )
    }

    /** 方案 B15：枚举含「空」（补 If 判不出 `VNull` 的既有缺口）。 */
    @Test
    fun `the enum offers the empty type`() {
        assertTrue(
            "枚举必须含「空」—— If 的「存在 / 不存在」判不出 VNull，这是用户唯一的手段",
            InspectTypeModule.EXPECTED_TYPE_OPTIONS.contains(VTypeRegistry.NULL.id),
        )
    }

    // ═══════════ C. 条件输出（最易错、最易静默） ═══════════

    /** 方案 C16：选「仅查看」⇒ **不含 `matched`**，恰含 `type_id` / `type_name`。 */
    @Test
    fun `view only produces no matched output`() {
        val step = stepWith(InspectTypeModule.EXPECTED_VIEW_ONLY)
        val ids = module.getOutputs(step).map { it.id }
        assertFalse(
            "「仅查看」时不得输出 matched（否则用户判「为真」会永远走假分支）",
            ids.contains(InspectTypeModule.OUTPUT_MATCHED),
        )
        assertEquals(
            listOf(InspectTypeModule.OUTPUT_TYPE_ID, InspectTypeModule.OUTPUT_TYPE_NAME),
            ids,
        )
    }

    /** 方案 C17：选具体类型 ⇒ **含 `matched` 且 `typeName = BOOLEAN`**。 */
    @Test
    fun `a concrete expected type adds a boolean matched output`() {
        val step = stepWith(VTypeRegistry.IMAGE.id)
        val outputs = module.getOutputs(step)
        val matched = outputs.single { it.id == InspectTypeModule.OUTPUT_MATCHED }
        assertEquals(VTypeRegistry.BOOLEAN.id, matched.typeName)
        assertNotNull(matched.nameStringRes)
    }

    /**
     * 方案 C21：选具体类型 ⇒ 同时含 `value_if_matched`，且它的**静态类型就是期望类型本身**。
     *
     * 这是「省掉 If + 创建变量两步」的全部原理：选择器按 `typeName` 决定能不能继续展开属性
     * （`MagicVariablePickerSheet` 的 `canNavigateDeeper`）⇒ 标成 `ANY` 就等于没做。
     */
    @Test
    fun `a concrete expected type also adds a value output typed as the expectation`() {
        for (expected in listOf(
            VTypeRegistry.IMAGE.id,
            VTypeRegistry.DICTIONARY.id,
            VTypeRegistry.LIST.id,
            VTypeRegistry.FILE.id,
            VTypeRegistry.STRING.id,
        )) {
            val outputs = module.getOutputs(stepWith(expected))
            val handed = outputs.single { it.id == InspectTypeModule.OUTPUT_VALUE_IF_MATCHED }
            assertEquals(
                "value_if_matched 的静态类型必须是期望类型本身（否则选择器展不开属性）",
                expected,
                handed.typeName,
            )
            assertNotNull(handed.nameStringRes)
        }
    }

    /** 方案 C22：「仅查看」⇒ **不含** `value_if_matched`（与 `matched` 同条件，护栏不能被拆）。 */
    @Test
    fun `view only produces no typed value output`() {
        val ids = module.getOutputs(stepWith(InspectTypeModule.EXPECTED_VIEW_ONLY)).map { it.id }
        assertFalse(
            "「仅查看」时不得输出 value_if_matched（否则用户不选类型也能拿到带类型的值）",
            ids.contains(InspectTypeModule.OUTPUT_VALUE_IF_MATCHED),
        )
    }

    /** 方案 C18：`expected_type` 缺失 / 非法 ⇒ 与「仅查看」同（回落）。 */
    @Test
    fun `a missing or invalid expected type falls back to view only`() {
        val missing = module.getOutputs(ActionStep(module.id, emptyMap()))
        val invalid = module.getOutputs(stepWith("vflow.type.not_a_real_type"))
        val blank = module.getOutputs(stepWith(""))
        for ((label, outputs) in listOf(
            "缺失" to missing,
            "非法值" to invalid,
            "空串" to blank,
        )) {
            assertFalse(
                "expected_type $label 时不得输出 matched",
                outputs.map { it.id }.contains(InspectTypeModule.OUTPUT_MATCHED),
            )
        }
    }

    /** 方案 C19：`type_id` / `type_name` 恒为 STRING（两种情形下都成立）。 */
    @Test
    fun `type id and type name are always strings`() {
        for (step in listOf(stepWith(InspectTypeModule.EXPECTED_VIEW_ONLY), stepWith(VTypeRegistry.IMAGE.id))) {
            val outputs = module.getOutputs(step)
            assertEquals(
                VTypeRegistry.STRING.id,
                outputs.single { it.id == InspectTypeModule.OUTPUT_TYPE_ID }.typeName,
            )
            assertEquals(
                VTypeRegistry.STRING.id,
                outputs.single { it.id == InspectTypeModule.OUTPUT_TYPE_NAME }.typeName,
            )
        }
    }

    /** 方案 C20：`getOutputs(null)` ⇒ 空列表（与 `CreateVariableModule` 一致）。 */
    @Test
    fun `null step produces no outputs`() {
        assertTrue(module.getOutputs(null).isEmpty())
    }

    /** 条件输出必须真的被 `getDynamicOutputs` 反映（编辑器选择器读的是它）。 */
    @Test
    fun `dynamic outputs follow the conditional outputs`() {
        assertEquals(
            module.getOutputs(stepWith(VTypeRegistry.IMAGE.id)).map { it.id },
            module.getDynamicOutputs(stepWith(VTypeRegistry.IMAGE.id), emptyList()).map { it.id },
        )
    }

    // ═══════════ D. 源码扫描型（接线锚定） ═══════════

    /** 方案 D21：`ModuleRegistry.kt` 剥注释后含注册行 + 防空转。 */
    @Test
    fun `the module is registered in ModuleRegistry`() {
        val source = SourceScan.stripped(REGISTRY_PATH)
        assertTrue(
            "ModuleRegistry 剥注释后行数异常（${source.lines().size}），剥注释可能剥过头",
            source.lines().size > 100,
        )
        assertTrue(
            "ModuleRegistry 里没有 register(InspectTypeModule(), context)",
            source.contains("register(InspectTypeModule(), context)"),
        )
    }

    /**
     * 方案 D22：模块源码**不出现** `usageScopes` / `AiModuleUsageScope`
     * ⇒ 不进 DIRECT_TOOL / TEMPORARY_WORKFLOW 工具清单（避免污染 catalog）。
     */
    @Test
    fun `the module does not declare ai tool scopes`() {
        val source = SourceScan.stripped(MODULE_PATH)
        assertTrue(
            "源码剥注释后行数异常（${source.lines().size}）",
            source.lines().size > 50,
        )
        assertFalse(
            "本模块不得声明 usageScopes（会进 AI 工具清单、污染 catalog）",
            source.contains("usageScopes"),
        )
        assertFalse(
            "本模块不得引用 AiModuleUsageScope",
            source.contains("AiModuleUsageScope"),
        )
        // 反证用：确认扫描到的确实是模块源码，而不是读错了文件
        assertTrue(source.contains("class InspectTypeModule"))
    }

    /** 方案 D23：源码里确实写了 `supportsRichText = true`（与 B9 互补：B9 测行为、这条测声明点）。 */
    @Test
    fun `the source declares supportsRichText on the value input`() {
        val source = SourceScan.stripped(MODULE_PATH)
        assertTrue(
            "源码里没有 supportsRichText = true",
            source.contains("supportsRichText = true"),
        )
    }

    /**
     * 方案 D26：本模块**不得构造任何具体类型的值** —— "不伪造"的源码级守卫。
     *
     * 行为测试（F30）只能证明「走这条路径时没伪造」；这条扫描断言防的是**将来有人**
     * 为了"顺手转换一下"而在本文件里写 `VImage(...)` / `VDictionary(...)`。
     *
     * ⚠️ `VString(` / `VBoolean(` **不在**禁列 —— 它们用于 `type_id` / `type_name` / `matched`
     * 这三个本就该是字符串/布尔的输出，属正常使用。
     * ⚠️ 反证：在 `execute` 里加一行 `VImage(value.asString())` ⇒ 本条必红。
     */
    @Test
    fun `the module never fabricates a value of the target type`() {
        val source = SourceScan.stripped(MODULE_PATH)
        assertTrue("剥注释后行数异常（${source.lines().size}）", source.lines().size > 50)
        for (ctor in listOf("VImage(", "VFile(", "VDictionary(", "VList(", "VNumber(", "VCoordinate(")) {
            assertFalse(
                "本模块不得构造 $ctor —— 未匹配时只给 VNull，不伪造（见 OUTPUT_VALUE_IF_MATCHED 的 KDoc）",
                source.contains(ctor),
            )
        }
        // 反证用：确认扫描到的确实是模块源码
        assertTrue(source.contains("class InspectTypeModule"))
    }

    // ═══════════ E. 三语键齐全 ═══════════

    /** 方案 E24：三份 `strings_module.xml` 的键集合逐字相等。 */
    @Test
    fun `all three languages declare the same inspect type keys`() {
        val zh = inspectKeys("src/main/res/values/strings_module.xml")
        val en = inspectKeys("src/main/res/values-en/strings_module.xml")
        val ja = inspectKeys("src/main/res/values-ja/strings_module.xml")

        assertTrue("中文文案里没有 vflow_data_inspect_type 键", zh.isNotEmpty())
        assertEquals("英文文案的键集合与中文不一致", zh, en)
        assertEquals("日文文案的键集合与中文不一致", zh, ja)
    }

    /** 方案 E25：非空（`zh.size >= 19`）+ 无重复键。 */
    @Test
    fun `the inspect type key set is complete and free of duplicates`() {
        val zh = inspectKeys("src/main/res/values/strings_module.xml")
        assertTrue(
            "键数异常（${zh.size} 个），检查过滤条件是否失效",
            zh.size >= 19,
        )

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

    /** 图标资源确实在磁盘上（按字符串名解析，编过不代表资源在）。 */
    @Test
    fun `the module icon resource exists on disk`() {
        val file = File("src/main/res/drawable/rounded_type_specimen_24.xml")
        assertTrue("图标资源不存在：${file.absolutePath}", file.isFile)
    }

    // ═══════════ F. 输出装配（`execute()` 的纯函数层） ═══════════

    /*
     * ⚠️ **为什么测 `buildInspectionOutputs` 而不是 `module.execute(...)`**
     *
     * `execute` 里唯一的 Android 依赖是 `value.type.getLocalizedName(context)`，
     * 而纯 JVM 单测**取不到可用的 `Context`**：`Context.getString` 是 `final`
     * （无法覆写 —— 方案 §5.2 给的 `ContextWrapper` 覆写写法**编译不过**，实测
     * `'getString' in 'Context' is final and cannot be overridden`）、
     * `Resources` 的构造器要一个 package-private 的 `AssetManager`（造不出来）、
     * 本项目没有 Robolectric / mockito，且**明确否决**开
     * `unitTests.isReturnDefaultValues`（那会让「没接上」表现为默认值 ⇒ 断言恒真）。
     * 本仓库在 `BroadcastTriggerWiringTest` 上记过同源结论。
     *
     * ⇒ 把「结果 → outputs」抽成纯函数后，「`matched` 键的**条件写入**」这个
     * 本模块最易静默的点**真的被行为测试覆盖**了（改 `?.let` 成 `?: false` 必红），
     * 而不是退化成只能证明「源码里写了」的扫描断言。
     */

    /** 方案 F26：匹配 ⇒ `type_id` 正确、`matched` 是 `VBoolean(true)`、`type_name` 在位。 */
    @Test
    fun `outputs carry the type id and a true match`() {
        val image = VImage("content://x/1.png")
        val outputs = buildInspectionOutputs(
            inspectValueType(image, VTypeRegistry.IMAGE.id),
            typeName = "图片",
            value = image,
        )
        assertEquals(VTypeRegistry.IMAGE.id, (outputs[InspectTypeModule.OUTPUT_TYPE_ID] as VString).raw)
        assertEquals(true, (outputs[InspectTypeModule.OUTPUT_MATCHED] as VBoolean).raw)
        assertEquals("图片", (outputs[InspectTypeModule.OUTPUT_TYPE_NAME] as VString).raw)
    }

    /**
     * 方案 F29：匹配 ⇒ `value_if_matched` 是**原来那个对象**（同一引用，不是副本）。
     *
     * ⚠️ 必须用 `assertSame` —— `assertEquals` 对 `VImage` 这种 data class 会被"值相等"蒙过去，
     * 从而**漏掉**「实现偷偷重新构造了一个对象」这类缺陷。
     */
    @Test
    fun `a matched value is handed back as the very same object`() {
        val image = VImage("content://x/1.png")
        val outputs = buildInspectionOutputs(
            inspectValueType(image, VTypeRegistry.IMAGE.id),
            typeName = "图片",
            value = image,
        )
        assertSame(
            "匹配时必须交回原对象本身（不得重新构造）",
            image,
            outputs[InspectTypeModule.OUTPUT_VALUE_IF_MATCHED],
        )
    }

    /**
     * 方案 F30：**未匹配 ⇒ `VNull`，绝不伪造** —— 本模块最重要的一条行为断言。
     *
     * 对照 `CreateVariableModule` 的 `TYPE_IMAGE` 分支（`:263-265`）：它对**任何**输入都会
     * 造出一个 `VImage`（把字典字符串化当路径）⇒ 属性访问静默 `VNull`，而用户以为拿到了图。
     * 本模块只给空，不造值。
     */
    @Test
    fun `a mismatched value becomes null instead of a fabricated one`() {
        val dictionary = VDictionary(mapOf("code" to VNumber(200.0)))
        val outputs = buildInspectionOutputs(
            inspectValueType(dictionary, VTypeRegistry.IMAGE.id),
            typeName = "字典",
            value = dictionary,
        )
        assertEquals(false, (outputs[InspectTypeModule.OUTPUT_MATCHED] as VBoolean).raw)
        assertSame(
            "未匹配时必须给 VNull（不得伪造出目标类型的值）",
            VNull,
            outputs[InspectTypeModule.OUTPUT_VALUE_IF_MATCHED],
        )
    }

    /** 方案 F27：选「仅查看」⇒ `outputs` **不含 `matched` / `value_if_matched` 键**（不是恒假布尔）。 */
    @Test
    fun `outputs omit matched under view only`() {
        val text = VString("hello")
        val outputs = buildInspectionOutputs(
            inspectValueType(text, null),
            typeName = "文本",
            value = text,
        )
        assertFalse(
            "「仅查看」时 outputs 里不得有 matched 键",
            outputs.containsKey(InspectTypeModule.OUTPUT_MATCHED),
        )
        assertFalse(
            "「仅查看」时 outputs 里不得有 value_if_matched 键（否则编辑期护栏被拆）",
            outputs.containsKey(InspectTypeModule.OUTPUT_VALUE_IF_MATCHED),
        )
        assertEquals(VTypeRegistry.STRING.id, (outputs[InspectTypeModule.OUTPUT_TYPE_ID] as VString).raw)
    }

    /** 方案 F28：`value` 缺省（`getVariable` 返回 `VNull`）⇒ `type_id` 是「空」。 */
    @Test
    fun `outputs report the empty type for an absent value`() {
        val outputs = buildInspectionOutputs(
            inspectValueType(VNull, null),
            typeName = "空",
            value = VNull,
        )
        assertEquals(VTypeRegistry.NULL.id, (outputs[InspectTypeModule.OUTPUT_TYPE_ID] as VString).raw)
    }

    /** 不匹配时 `matched` 是 `false`（而不是缺失）。 */
    @Test
    fun `outputs carry a false match for a mismatched type`() {
        val text = VString("hello")
        val outputs = buildInspectionOutputs(
            inspectValueType(text, VTypeRegistry.IMAGE.id),
            typeName = "文本",
            value = text,
        )
        assertEquals(false, (outputs[InspectTypeModule.OUTPUT_MATCHED] as VBoolean).raw)
    }

    // ═══════════ helpers ═══════════

    private fun stepWith(expectedType: String): ActionStep =
        ActionStep(module.id, mapOf(InspectTypeModule.PARAM_EXPECTED_TYPE to expectedType))

    private fun inspectKeys(path: String): Set<String> =
        Regex("""<string name="([a-z_]*vflow_data_inspect_type[^"]+)"""")
            .findAll(File(path).readText())
            .map { it.groupValues[1] }
            .toSet()

    private companion object {
        const val MODULE_PATH =
            "src/main/java/com/chaomixian/vflow/core/workflow/module/data/InspectTypeModule.kt"
        const val REGISTRY_PATH =
            "src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"
    }
}
