package com.chaomixian.vflow.xposed

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaomixian.vflow.xposed.capabilities.HookCapabilityRuntime
import com.chaomixian.vflow.xposed.wire.ThreadModes
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **`ui` 档线程上 `Looper.myLooper() != null`** —— 证明「UI 档真的给了 Looper」。
 *
 * ## ⚠️⚠️ 为什么必须是 instrumented（不能写在 `src/test`）
 *
 * 纯 JVM 单测里这条**跑不了**（本项目无 Robolectric、未开 `returnDefaultValues`）：
 * `HandlerThread.getLooper()` / `Looper.myLooper()` 在 AGP 的 mockable jar 里
 * 一律抛 `Method … not mocked`（实测：那条错误曾让 `HookCapabilityRuntimeTest`
 * 的 27 个用例全红）。
 *
 * ⇒ `src/test` 里只能用**假 dispatcher** 覆盖「分发到 ui 档」的**语义**；
 * 而「`ui` 档的线程**真的**有 `Looper`」这个**事实**，只能在有 Android 运行时的
 * 设备/模拟器上验 —— 那是本文件存在的**唯一**理由。
 *
 * ## ⚠️ 必须在 `androidTest` 源集
 *
 * 放 `src/test/java/...` 不会被 `connectedDebugAndroidTest` 采集 ⇒
 * 这条验收会**静默不跑**（比不写更坏：看起来像已覆盖）。
 *
 * ## 怎么跑
 *
 * ```bash
 * # ⚠️ adb 有两条 transport 时必须固定设备，否则 UTP 会报 "No online devices found."
 * ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest
 * ```
 *
 * ⚠️ **无设备时任务 FAILED（不是跳过）**，且它**不在 `./gradlew test` 里**——
 * 不要把 `connectedDebugAndroidTest` 塞进那个门禁（那会让无设备的机器整条 `test` 跑不起来）。
 * 无设备时**如实记为「未验证」**；它的兜底是真机项「`ui` 档脚本里
 * `new java.lang.Handler()` 不抛」。
 */
@RunWith(AndroidJUnit4::class)
class HookCapabilityRuntimeUiLooperTest {

    /**
     * 直接验「`Handler(HandlerThread.looper).asCoroutineDispatcher()` 产出的 dispatcher
     * 会把协程投到**一个有 Looper 的线程**上」。
     *
     * ⚠️ 与生产代码用的是**同一段构造逻辑**（`Handler(ht.looper)` + `asCoroutineDispatcher`），
     * 但**不**经 `HookCapabilityRuntime` 的私有 `UiDispatcherHolder`（它是 private object，
     * 只暴露委托壳）。这里验的是**平台能力**（这条接法到底给不给 Looper），
     * 而「生产真的用了这条接法」由 `defaultDispatchers()` 的源码 + 真机项覆盖。
     */
    @Test
    fun uiModeRunsOnAThreadWithALooper() {
        // ⚠️ 扩展 `asCoroutineDispatcher` 的接收者是 `Handler`，**不是** `HandlerThread`
        //（它不继承 Handler、也没有 getHandler()）⇒ 必须显式造一个 Handler。
        val ht = HandlerThread("ui-looper-test").apply { start() }
        val seen = AtomicReference<Looper?>()
        val threadName = AtomicReference<String?>()
        val latch = CountDownLatch(1)

        val handler = Handler(ht.looper)
        val scope = CoroutineScope(
            handler.asCoroutineDispatcher("ui-looper-test") + SupervisorJob() + CoroutineName("ui-looper-test"),
        )
        try {
            scope.launch {
                seen.set(Looper.myLooper())
                threadName.set(Thread.currentThread().name)
                latch.countDown()
            }

            assertTrue("ui 档的协程应在 5 秒内执行", latch.await(5, TimeUnit.SECONDS))
            assertNotNull("❌ ui 档线程上必须有 Looper —— 这正是这一档的**全部意义**", seen.get())
            assertTrue(
                "协程应跑在 HandlerThread 上（实际线程名 ${threadName.get()}）",
                threadName.get()?.contains("ui-looper-test") == true,
            )
        } finally {
            scope.cancel()
            ht.quitSafely()
        }
    }

    /**
     * 阴性对照：**`Dispatchers.Default` 的线程上没有 Looper**。
     *
     * ⚠️ 有它才能证明上一条不是「任何线程都有 Looper」的平凡结论 ——
     * 这两档的差别必须是**真的**（`default` 档脚本里 `new Handler()` 必然抛，
     * `ui` 档才可以）。这正是真机验收里那对阴/阳性对照的单测版。
     */
    @Test
    fun defaultModeRunsOnAThreadWithoutALooper() {
        val seen = AtomicReference<Looper?>()
        val latch = CountDownLatch(1)

        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default + SupervisorJob())
        try {
            scope.launch {
                seen.set(Looper.myLooper())
                latch.countDown()
            }
            assertTrue("default 档的协程应在 5 秒内执行", latch.await(5, TimeUnit.SECONDS))
            // ⚠️ 断言的是「**没有** Looper」。若某天 Dispatchers.Default 被换成了
            // 带 Looper 的线程（不该发生），本条会变红 —— 那是正确的信号。
            org.junit.Assert.assertNull(
                "default 档线程上**不该**有 Looper（否则三档的区别不成立）",
                seen.get(),
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * `HookCapabilityRuntime.defaultDispatchers()` 必须真的给出三档。
     *
     * ⚠️ 这条在纯 JVM 里也跑不了 —— 因为它的 `ui` 档是懒委托壳，但**取值**
     * 本身不碰 Android；不过这里仍放在 instrumented 里，是为了与上面两条
     * 构成「三档在生产构造点上是齐的」这一组事实。
     */
    @Test
    fun defaultDispatchersHasAllThreeTiers() {
        val table = HookCapabilityRuntime.defaultDispatchers()
        for (mode in listOf(ThreadModes.DEFAULT, ThreadModes.IO, ThreadModes.UI)) {
            assertTrue("defaultDispatchers 必须含 $mode 档", table.containsKey(mode))
        }
    }
}
