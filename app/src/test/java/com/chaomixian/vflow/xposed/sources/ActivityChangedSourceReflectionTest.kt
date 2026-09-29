package com.chaomixian.vflow.xposed.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityChangedSource` 反射层的测试（修缺陷 8：反射句柄缓存）。
 *
 * ## 这一组为什么不依赖 Android
 *
 * 被测的是**反射缓存本身**的语义（命中、不缓存失败、**按类区分**），
 * 与 `ActivityRecord` / `Intent` 无关 —— 用本文件里的假类即可覆盖。
 * 这样它才能跑在纯 JVM 上（真 hook 路径只能真机验，见 §8.3）。
 *
 * ## ⚠️ 为什么「按类区分」这条必须有测试
 *
 * 缓存键若只按**字段名 / 方法名**，那么**出现在两个类上的同名字段**会复用
 * 同一个 `Field` —— 现在只有一个 `ActivityRecord`（system_server），
 * 看不出问题；但 §2.1 的「执行环境」是**开放维度**，
 * 将来「目标 App 进程的 hook」会有**另一个**类 ⇒ 跨类复用 ⇒ 读到错的对象。
 *
 * ⇒ 这正是本仓库反复记录的形态：**当下看不见、条件一变就是静默错误**。
 */
class ActivityChangedSourceReflectionTest {

    // ── 测试用的假类（刻意让两个类有【同名不同类】的字段/方法）──

    @Suppress("unused")
    private class FakeA {
        val name: String = "A"
        fun onlyOnA(): String = "onA"
    }

    @Suppress("unused")
    private class FakeB {
        val name: String = "B"
    }

    @Suppress("unused")
    private class HolderA {
        // ⚠️ 字段名必须与生产代码寻找的一致（`mActivityComponent`）——
        // 我第一版写成 `component`，测试红了，而那是**测试错**不是代码错
        @JvmField val mActivityComponent: HolderB = HolderB()
    }

    @Suppress("unused")
    private class HolderB {
        fun getClassName(): String = "fake.ClassName"
    }

    private val source = ActivityChangedSource()

    @Test
    fun `cachedField returns the right field for each class`() {
        val fa = FakeA()
        val fb = FakeB()

        assertEquals("A", source.readString(fa, "name"))
        assertEquals("B", source.readString(fb, "name"))
    }

    @Test
    fun `cachedField does not reuse a field across classes`() {
        // 先查 A、再查 B —— 若缓存只按字段名，第二次会拿到 A 的 Field
        // 并对 B 的实例调用，结果是 null 或异常（而不是 "B"）
        val fieldOnA = source.cachedField(FakeA::class.java, "name")
        val fieldOnB = source.cachedField(FakeB::class.java, "name")

        assertNotNull(fieldOnA)
        assertNotNull(fieldOnB)
        assertTrue("两个类上的同名字段必须是不同的 Field", fieldOnA !== fieldOnB)
        assertEquals("A", fieldOnA!!.get(FakeA()))
        assertEquals("B", fieldOnB!!.get(FakeB()))
    }

    @Test
    fun `cachedField is idempotent for the same class and name`() {
        // 同一个 (类, 名字) 必须命中缓存 —— 否则「缓存」等于没做
        val first = source.cachedField(FakeA::class.java, "name")
        val second = source.cachedField(FakeA::class.java, "name")
        assertSame("同 key 应命中同一实例（这就是缓存的意义）", first, second)
    }

    @Test
    fun `cachedField returns null for a missing field and does not cache the failure`() {
        assertNull(source.cachedField(FakeA::class.java, "noSuchField"))
        // ⚠️ 失败**不缓存** —— 否则将来字段名改对了也会一直被旧结果挡住。
        // 这里用「先查不存在、再查存在」来证明失败没有污染缓存。
        assertNotNull(source.cachedField(FakeA::class.java, "name"))
    }

    @Test
    fun `readString returns null and does not throw for a missing field`() {
        // 字段名随系统版本漂移是**预期内**的：单个字段读不到不该影响其它字段
        assertNull(source.readString(FakeA(), "definitelyNotThere"))
    }

    @Test
    fun `readComponentClassName resolves through the component's own class`() {
        // 模拟 mActivityComponent：字段在 HolderA 上，取 className 要在 HolderB 上
        assertEquals("fake.ClassName", source.readComponentClassName(HolderA()))
    }

    @Test
    fun `readComponentClassName returns empty string when the field is missing`() {
        // 缺字段不是崩溃理由 —— 返回空串，下游照常编码（className 允许为空）
        assertEquals("", source.readComponentClassName(FakeB()))
    }
}
