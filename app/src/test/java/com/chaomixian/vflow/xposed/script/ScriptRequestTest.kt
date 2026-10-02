package com.chaomixian.vflow.xposed.script

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScriptRequest] 的参数层编解码单测。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §4 步骤 8。
 *
 * ## ⚠️ 这些用例全部是「改坏了就会红」的形态
 *
 * 方案 §6.3 点名：本任务最容易做错的是「写一批**看起来在测**的用例，实际什么都没测」。
 * 所以每条关键用例的**反证方式**都写在它自己的注释里（做法：把代码改回 bug 版本，
 * 确认该用例变红，再改回）。
 *
 * ## 防空转
 *
 * 末尾两条「扫描非空」断言（照 `WireLayerPurityTest` 的
 * `files are present` / `non-empty` 两条），防「输入集被清空 ⇒ 全部空转通过」。
 */
class ScriptRequestTest {

    private fun decode(json: String): ScriptParams? {
        // ⚠️ 记到 **companion** 里（不是实例字段）—— JUnit 对每个用例新建一个实例，
        // 用实例字段的话计数器永远是 0，末尾那条防空转断言会恒假。
        distinctInputs += json
        return ScriptRequest.decode(json)
    }

    // ── 正常路径 ─────────────────────────────────────────────

    @Test
    fun `round trip preserves the script text verbatim`() {
        val script = "var a = 1;\nreturn {ok: a};"
        val json = ScriptRequest.encode(ScriptParams(script = script, inputs = emptyMap()))
        val back = decode(json)

        assertNotNull("合法 params 必须解得出来", back)
        // ⚠️ 逐字相等，不是 contains —— 脚本源码里换行/缩进被改写会静默改变语义
        assertEquals(script, back!!.script)
    }

    @Test
    fun `round trip preserves input values`() {
        val json = ScriptRequest.encode(
            ScriptParams(script = "1", inputs = mapOf("n" to 5, "s" to "hi", "b" to true)),
        )
        val inputs = decode(json)!!.inputs

        assertEquals(3, inputs.size)
        assertEquals(5, inputs["n"])
        assertEquals("hi", inputs["s"])
        assertEquals(true, inputs["b"])
    }

    @Test
    fun `round trip preserves a null value instead of dropping the key`() {
        // ⚠️⚠️ 这是本文件最要紧的一条（方案的 E8 实测）。
        //
        // 反证方式：把 `ScriptRequest.encode` 里的 `value ?: JSONObject.NULL`
        // 改回 `inputs.put(key, value)` ⇒ 本用例变红（键会消失）。
        //
        // 真实的失败形态：脚本返回 `{ok: null, err: "x"}`，用户看到 `{"err":"x"}`，
        // 会去查脚本 —— 而脚本没错。**静默**，且方向错。
        val json = ScriptRequest.encode(
            ScriptParams(script = "1", inputs = mapOf("nil" to null, "other" to 1)),
        )
        assertTrue("编码结果里应保留 null 键：$json", json.contains("\"nil\":null"))

        val inputs = decode(json)!!.inputs
        assertTrue("解码后 nil 键必须存在", inputs.containsKey("nil"))
        assertEquals(2, inputs.size)
        // ⚠️ 值是 JSONObject.NULL 哨兵（不是 Java null）—— 这是往返无损的前提
        assertEquals(JSONObject.NULL, inputs["nil"])
    }

    @Test
    fun `unknown keys are ignored not rejected`() {
        // 协议是**加法演进**的：旧端不认识新键是常态，不能因此拒绝整个请求
        //（那会让「新 App + 旧 hook」直接不可用，而它们本该能协作）
        val params = decode("""{"script":"1","future_key":123,"another":{"deep":true}}""")
        assertNotNull(params)
        assertEquals("1", params!!.script)
    }

    // ── 坏输入：一律 null，绝不抛 ─────────────────────────────

    @Test
    fun `malformed json returns null instead of throwing`() {
        // ⚠️ 反证方式：让 `decode` 里的 try/catch 改成 rethrow ⇒ 本用例变红（抛异常）。
        //
        // 为什么必须不抛：它跑在 hook 层（system_server）。抛出去会被顶层兜成
        // `handler_error`，而「参数不合法」应当是 handler 可判定的失败 ——
        // 那样才能给出更具体的 detail。
        //
        // ⚠️ 用 assertNull 而不是「接住异常」——「接住异常」会把「抛了」和「返回 null」
        // 都当成通过，测不出这条要求。
        val bad = listOf(
            "not json",
            "",
            "   ",
            "{",
            "[1,2,3]",      // 是合法 JSON，但不是**对象**
            "\"a string\"",
            "123",
            "null",
        )
        bad.forEach { json ->
            assertNull("坏 params 必须返回 null：`$json`", decode(json))
        }
    }

    @Test
    fun `missing script key returns null`() {
        assertNull(decode("{}"))
        assertNull(decode("""{"inputs":{"a":1}}"""))
    }

    @Test
    fun `non string script returns null instead of stringifying`() {
        // ⚠️⚠️ 反证方式：把 `obj.opt(KEY_SCRIPT)` 改回 `obj.optString(KEY_SCRIPT)` ⇒ 本用例变红。
        //
        // `optString` 对**非字符串**值返回其 toString()：`{"script":5}` ⇒ `"5"`
        // ⇒ 一个类型写错的请求会被**静默当成**合法脚本去执行，
        // 报错变成「脚本第 1 行有语法错误」—— 排查方向整个错掉。
        listOf(
            """{"script":5}""",
            """{"script":true}""",
            """{"script":null}""",
            """{"script":{"nested":1}}""",
            """{"script":[1,2]}""",
        ).forEach { json ->
            assertNull("script 不是字符串就必须拒绝：`$json`", decode(json))
        }
    }

    @Test
    fun `empty script is allowed and passed through`() {
        // ⚠️ 刻意**不**拒绝空串（与方案一致：「只判类型不判空」）。
        //
        // 理由：拒绝它会把「空脚本」这一情形从「跑出空结果」变成「参数非法」，
        // 而空脚本是**合法**的（等价于什么都不做）。这条同时锁住
        // 「将来有人加 `if (script.isBlank()) return null` 时会被发现」。
        val params = decode("""{"script":""}""")
        assertNotNull("空脚本应当放行（会跑出空结果）", params)
        assertEquals("", params!!.script)
    }

    // ── inputs 的宽容 ─────────────────────────────────────────

    @Test
    fun `missing inputs becomes an empty map`() {
        val params = decode("""{"script":"1"}""")
        assertNotNull(params)
        assertTrue("缺 inputs ⇒ 空 Map（脚本仍能跑，只是没输入）", params!!.inputs.isEmpty())
    }

    @Test
    fun `non object inputs becomes an empty map instead of failing`() {
        // ⚠️ 宽容是有意的：脚本不依赖输入时，`inputs` 给什么形态都该能跑。
        // 把「inputs 不是对象」当成整体失败，会让一次本可成功的调用失败。
        listOf(
            """{"script":"1","inputs":5}""",
            """{"script":"1","inputs":"x"}""",
            """{"script":"1","inputs":null}""",
            """{"script":"1","inputs":[1,2]}""",
        ).forEach { json ->
            val params = decode(json)
            assertNotNull("inputs 形态错误不该导致整体失败：`$json`", params)
            assertTrue("inputs 应回落成空 Map：`$json`", params!!.inputs.isEmpty())
        }
    }

    @Test
    fun `nested inputs keep their org json types so they survive a round trip`() {
        // ⚠️ 这条锁的是「值原样保留 org.json 类型」这个设计决策（见 ScriptRequest 类注释）。
        //
        // 若改成递归转成 Kotlin Map/List，就会多出两份 null 处理代码 ——
        // 而那正是最容易写错的地方（`JSONObject` **不实现** `Map`，
        // `as? Map<*,*>` 恒为 null，本仓库踩过这个坑）。
        val json = """{"script":"1","inputs":{"o":{"n":null},"arr":[1,null,3]}}"""
        val inputs = decode(json)!!.inputs

        assertTrue("嵌套对象应保持 JSONObject 类型", inputs["o"] is JSONObject)
        assertTrue("数组应保持 JSONArray 类型", inputs["arr"] is JSONArray)

        // 往返无损：再编码一次，语义必须一致
        val again = ScriptRequest.encode(ScriptParams(script = "1", inputs = inputs))
        val back = decode(again)!!.inputs
        assertEquals(
            "往返后嵌套结构必须无损",
            (inputs["o"] as JSONObject).toString(),
            (back["o"] as JSONObject).toString(),
        )
    }

    @Test
    fun `encode emits both keys so the wire shape is stable`() {
        val json = ScriptRequest.encode(ScriptParams(script = "x", inputs = mapOf("a" to 1)))
        val obj = JSONObject(json)

        assertTrue("必须写 script 键", obj.has(ScriptRequest.KEY_SCRIPT))
        assertTrue("必须写 inputs 键", obj.has(ScriptRequest.KEY_INPUTS))
        // ⚠️ 键名是**跨进程协议的一部分**，写死在这里防「顺手改名」
        assertEquals("script", ScriptRequest.KEY_SCRIPT)
        assertEquals("inputs", ScriptRequest.KEY_INPUTS)
    }

    // ── 防空转 ───────────────────────────────────────────────

    @Test
    fun `the test class exercised many distinct inputs so the checks are not vacuous`() {
        // ⚠️ 防「输入集被清空 ⇒ 上面那些逐项断言全部空转通过」。
        // 照 WireLayerPurityTest 的 files-are-present 那条的形态。
        //
        // ⚠️ 计数器在 companion 里 —— 见 `decode` 的注释。
        // ⚠️ 阈值取实测值（19）之下的 15 —— 这是个**防空转的守卫**，不是覆盖率目标。
        // 定高了会在每次微调用例集时变红（而恒红/常红的守卫会被下一个实现者删掉）；
        // 定低了才真的没用。15 能抓住「用例被清空」，又不会因一次正常调整误报。
        assertTrue(
            "本测试类只喂了 ${distinctInputs.size} 个不同的 params 串，" +
                "覆盖度异常 —— 是否有人把用例表清空了？",
            distinctInputs.size >= 15,
        )
        assertTrue(
            "应当同时覆盖过「合法」与「非法」两侧",
            distinctInputs.any { it.contains("script") } &&
                distinctInputs.any { !it.contains("script") },
        )
    }

    private companion object {
        /** 喂过的**全部** params 串（去重）。仅供防空转断言用。 */
        val distinctInputs = mutableSetOf<String>()
    }
}
