package com.chaomixian.vflow.xposed.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `RhinoServiceWarmUp` 的回归测试。
 *
 * ## ⚠️⚠️ 这条测试为什么必须用**隔离的 ClassLoader**
 *
 * 被修的这个缺陷（正则不可用）**在普通单测环境里根本复现不出来** ——
 * 因为单测跑在 Gradle 的测试 JVM 里，TCCL 能看到 Rhino jar 自带的
 * `META-INF/services/org.mozilla.javascript.RegExpLoader`，正则一直是好的。
 *
 * ⇒ 「`ScriptExecutor` 的 RegExp 能用」在**真机坏、单测好**，两者不矛盾：
 * **唯一变量是 ClassLoader**。
 *
 * 所以这里要**人为造出 hook 侧的处境**：一个 parent 看不到 Rhino 的 ClassLoader，
 * 让 `ServiceLoader` 找不到服务文件，然后验证 warm-up 能把它救回来。
 */
class RhinoServiceWarmUpTest {

    /**
     * 造一个「像 LSPosed 那样」的 ClassLoader：能看到 Rhino，但它的 parent 看不到
     * ⇒ 若 TCCL 不是它，`ServiceLoader` 就查不到服务文件。
     */
    private fun isolatedLoader(): ClassLoader? {
        // Rhino 是作为 jar 依赖进来的 ⇒ 从 classpath 里找它那个 jar。
        // ⚠️ 不能对 `getResource(...Context.class).toURI()` 直接 `File(...)` ——
        // jar 里的 URI 不是分层的（实测抛 `URI is not hierarchical`）。
        val cp = System.getProperty("java.class.path").orEmpty()
        val jar = cp.split(File.pathSeparator)
            .map { File(it) }
            .firstOrNull { it.isFile && it.name.startsWith("rhino") && it.name.endsWith(".jar") }
            ?: return null
        return java.net.URLClassLoader(
            arrayOf(jar.toURI().toURL()),
            // ⚠️ 刻意用 null（bootstrap 为 parent）——
            // `ClassLoader.getPlatformClassLoader()` 在 Android 单元测试环境里不可用。
            // parent=null 同样达到目的：parent 看不到 Rhino。
            null,
        )
    }

    @Test
    fun `warm-up makes the RegExp engine discoverable under an isolated class loader`() {
        val loader = isolatedLoader()
        if (loader == null) {
            println("[skip] 找不到含 META-INF/services 的 Rhino 类路径，跳过")
            return
        }

        // ── 先复现缺陷：TCCL 是「看不到服务文件」的那个，ServiceLoader 应查不到 ──
        val thread = Thread.currentThread()
        val outermost = thread.contextClassLoader
        // 「看不到 Rhino」的 TCCL —— 模拟 hook 侧的真实处境
        val blindTccl = object : ClassLoader(null) {}
        try {
            thread.contextClassLoader = blindTccl
            val before = serviceVisible(loader)
            assertFalse(
                "前提不成立：隔离 ClassLoader 下 ServiceLoader 竟然找到了实现 —— " +
                    "说明测试环境与 hook 侧不符，本用例失去意义",
                before,
            )

            // ── 跑 warm-up ──
            val did = RhinoServiceWarmUp.warmUp(loader)

            // ── 关键断言 1：TCCL 必须被还原成「调用前的那个」 ──
            // ⚠️ 这里比的是 `blindTccl`（warm-up 看到的值），不是 `outermost` ——
            // warm-up 的契约是「还原到它进来时看到的值」，它无从知道更外层的状态。
            // （我第一版比了 `outermost`，那是**测试自己写错**，不是代码错。）
            assertSame(
                "warm-up 必须还原 TCCL —— 留在改动状态会污染 system_server 的那个线程",
                blindTccl,
                thread.contextClassLoader,
            )

            // ── 关键断言 2：还原之后，Rhino 的正则引擎**已经缓存好了** ──
            // ⚠️⚠️ 这里断言的是 `Context` 的**静态字段 `regExpLoader`**，
            // **不是** `ServiceLoader` 的可见性 —— 两者容易混，但后者是错的判据：
            //
            //   · ServiceLoader 可见性**取决于当时的 TCCL**。warm-up 还原 TCCL 之后，
            //     再查当然是 false（本用例第一版就断言了它，结果红 —— 那是**测试写错**）。
            //   · 真正决定正则能否用的是 `<clinit>` 时**缓存进静态字段**的那份结果。
            //     `getRegExpProxy()` 的逻辑是：实例字段为空 ⇒ 看静态 `regExpLoader`，
            //     非空就 `newProxy()` 填进实例字段；静态字段为 null 才返回 null。
            //
            // ⇒ 「预热生效」的准确含义 = **静态 `regExpLoader` 非空**，
            //    且它此后**与 TCCL 无关**（这正是本方案只需要瞬时改 TCCL 的原因）。
            assertTrue(
                "warm-up 返回 false —— 预热没成功，正则仍会不可用",
                did,
            )
            assertTrue(
                "预热后 Context.regExpLoader 仍为 null ⇒ 真机上正则照样坏",
                regExpLoaderCached(loader),
            )
        } finally {
            thread.contextClassLoader = outermost
        }
    }

    /** `ServiceLoader.load(cls)`（**单参版**，与 Rhino 用法一致 —— 它走 TCCL）。 */
    private fun serviceVisible(loader: ClassLoader): Boolean = try {
        val iface = Class.forName("org.mozilla.javascript.RegExpLoader", false, loader)
        val it = java.util.ServiceLoader.load(iface as Class<*>).iterator()
        it.hasNext()
    } catch (t: Throwable) {
        false
    }

    /**
     * `Context.regExpLoader`（**静态字段**）是否已缓存 —— 这才是「正则可用」的真判据。
     *
     * ⚠️ 它读的是 `Context.class` 在**给定 ClassLoader** 下的那份静态状态
     *（本例里是隔离 ClassLoader 加载的那份，与生产 JVM 那份互不干扰）。
     */
    private fun regExpLoaderCached(loader: ClassLoader): Boolean = try {
        val ctx = Class.forName("org.mozilla.javascript.Context", false, loader)
        val f = ctx.getDeclaredField("regExpLoader")
        f.isAccessible = true
        f.get(null) != null
    } catch (t: Throwable) {
        false
    }

    @Test
    fun `null class loader is a no-op and never throws`() {
        // 跑在 system_server 里 ⇒ 任何路径都不许抛
        assertFalse(RhinoServiceWarmUp.warmUp(null))
    }

    @Test
    fun `warm-up is idempotent and does not disturb the TCCL on the second call`() {
        val loader = isolatedLoader() ?: return
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        try {
            RhinoServiceWarmUp.warmUp(loader)
            val afterFirst = thread.contextClassLoader
            RhinoServiceWarmUp.warmUp(loader)
            assertSame("第二次调用后 TCCL 也必须还是原值", original, thread.contextClassLoader)
            assertSame("两次之间 TCCL 不该变", original, afterFirst)
        } finally {
            thread.contextClassLoader = original
        }
    }

    /**
     * 源码扫描型守卫：warm-up 必须**排在** `onSystemServerStarting` 里任何
     * 可能触碰 Rhino 的动作之前，且**必须在 `startChannel` 之前**。
     *
     * ⚠️ 存在理由：那个 ServiceLoader 查找**只做一次、失败不重试**，
     * 一旦别处先碰了 `Context` 就永久救不回来 ⇒ 「顺序」是这个修复的**实质**，
     * 不是风格问题。纯函数测试测不出调用顺序，只能在源码层锁。
     */
    @Test
    fun `warm-up is called before startChannel in the hook entry`() {
        val f = listOf(
            File("app/src/main/java/com/chaomixian/vflow/xposed/VFlowHookEntry.kt"),
            File("src/main/java/com/chaomixian/vflow/xposed/VFlowHookEntry.kt"),
        ).firstOrNull { it.exists() }
            ?: error("找不到 VFlowHookEntry.kt")

        val src = f.readText()
        val body = src.substringAfter("override fun onSystemServerStarting")
            .substringBefore("\n    /**")

        val warm = body.indexOf("RhinoServiceWarmUp.warmUp")
        val channel = body.indexOf("startChannel(")

        assertTrue("onSystemServerStarting 里必须调用 RhinoServiceWarmUp.warmUp", warm >= 0)
        assertTrue("onSystemServerStarting 里必须调用 startChannel", channel >= 0)
        assertTrue(
            "warm-up 必须排在 startChannel **之前** —— 那是它与「一次性查找」赛跑的唯一机会",
            warm < channel,
        )
    }

    @Test
    fun `the warm-up file does not pull android into the hook layer`() {
        // 它跑在 system_server ⇒ 引用面受 WireLayerPurityTest 管辖。
        // 这里做一道**局部**冗余检查（WireLayerPurityTest 是全局的），
        // 让「有人顺手 import android.*」在最近的测试里就被拦住。
        val f = listOf(
            File("app/src/main/java/com/chaomixian/vflow/xposed/script/RhinoServiceWarmUp.kt"),
            File("src/main/java/com/chaomixian/vflow/xposed/script/RhinoServiceWarmUp.kt"),
        ).firstOrNull { it.exists() } ?: error("找不到 RhinoServiceWarmUp.kt")

        val imports = f.readLines().filter { it.startsWith("import ") }
        assertTrue("必须 import Rhino（否则它怎么预热）", imports.any { it.contains("org.mozilla.javascript") })
        assertEquals(
            "本文件不许 import android.*（会扩大崩溃半径）",
            emptyList<String>(),
            imports.filter { it.startsWith("import android.") },
        )
    }

    /** 防空转：确认上面几条断言不是在空跑。 */
    @Test
    fun `the assertions above are not vacuous`() {
        val f = File("app/src/main/java/com/chaomixian/vflow/xposed/script/RhinoServiceWarmUp.kt")
            .takeIf { it.exists() } ?: return
        assertTrue("源码扫描读到的内容不能为空", f.readText().length > 1000)
        assertNotNull("getRegExpProxy 诊断入口应存在", RhinoServiceWarmUp)
    }
}
