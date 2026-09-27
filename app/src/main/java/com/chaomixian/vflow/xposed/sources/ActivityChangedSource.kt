package com.chaomixian.vflow.xposed.sources

import android.os.Bundle
import com.chaomixian.vflow.xposed.HookLog
import com.chaomixian.vflow.xposed.HookRuntime
import com.chaomixian.vflow.xposed.HookSource
import com.chaomixian.vflow.xposed.HookTargets
import com.chaomixian.vflow.xposed.wire.ActivityPayload
import com.chaomixian.vflow.xposed.wire.HookConditionWire
import com.chaomixian.vflow.xposed.wire.HookConditions
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * `activity_changed` 的 hook 适配器 —— **本通道里唯一 Xposed 特有的一段业务代码**。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.3 / §4.1 / §4.3。
 * 取值路径由 P0 探针实测确证（`P0-FINDINGS.md` §1）。
 *
 * ## 它为什么存在（价值论据，不是「技术能做」）
 *
 * 现有 `app_switch` 是**包级**触发器：`AppSwitchTriggerHandler.kt:47` 按包名去重
 * （`if (packageName == previousPackage) return@onEach`），
 * **同包内 Activity 切换被直接丢弃**。而 Activity 级触发器在 vFlow 里根本不存在。
 *
 * 更关键的是**能拿到完整 Intent（含 extras）** —— `dumpsys` 那条路拿不到
 * （实测：文本版只有 `(has extras)`，proto 版 grep 键名 0 命中）。
 * 这是前三条通道原理上做不到的。
 *
 * ## ⚠️⚠️ 三条实测踩出来的实现约束（每条都曾导致误判）
 *
 * | 约束 | 不遵守的表现 |
 * |---|---|
 * | 类名必须是 `com.android.server.wm.*` | `ClassNotFoundException`（曾写成 `android.app.*`） |
 * | 方法**是 `static`**，无 `this` | 用 `chain.getThisObject()` 取值**恒为 null**（曾误判「hook 失败」） |
 * | 回调**必须 `proceed()`** | 被 hook 的原方法不执行 ⇒ **破坏整机行为** |
 *
 * ## ⚠️ 引用面
 *
 * 本文件运行在 system_server 里。允许：`io.github.libxposed.*` / `java.*` /
 * `kotlin.*` / `android.os.Bundle`（**纯数据类**，无 Context 初始化）/
 * `com.chaomixian.vflow.xposed.**`。
 * **禁止** `android.content.*`（除本包 [HookRuntime] 那层的传输需要）/
 * Gson / `core.*` / `services.*` / `DebugLogger`。
 */
class ActivityChangedSource : HookSource {

    override val topic: String = ActivityPayload.TOPIC

    /** 已挂上的 hook 句柄。用于卸载与热更新时替换。 */
    @Volatile
    private var handle: XposedInterface.HookHandle? = null

    /**
     * 当前过滤条件。
     *
     * ⚠️ 用 `@Volatile` 而不是静态字段：热更新换 classloader，
     * **静态字段在新代际里是全新的**（实测读到 null）。
     */
    @Volatile
    private var conditions: HookConditions = HookConditions(emptySet(), emptySet())

    override fun mount(runtime: HookRuntime, classLoader: ClassLoader) {
        // ⚠️ 幂等：热更新、框架重放都可能再调
        if (handle != null) {
            HookLog.e("ActivityChangedSource 已挂载，跳过重复 mount")
            return
        }

        try {
            val method = findTargetMethod(classLoader)
            if (method == null) {
                // ⚠️ 不静默：hook 点找不到意味着**功能彻底不可用**，
                // 而用户只会看到「触发器配了但不触发」（§5.2）
                //
                // ⚠️ 报错信息**不能只说「系统版本不兼容」** —— 那是我踩过的误导：
                // 真因往往是**传错了 ClassLoader**（模块自己的 vs system_server 的）。
                // 所以把实际用的 ClassLoader 一并打出来，供一眼区分两者
                HookLog.e(
                    "❌ 找不到 hook 点（已试 ${HookTargets.activityResumedClassCandidates()}）\n" +
                        "   用的是 ClassLoader = $classLoader\n" +
                        "   ⇒ 若它是**模块自己的** loader（而非 system_server 的 PathClassLoader），" +
                        "说明传错了；不是系统版本问题"
                )
                return
            }

            HookLog.e("hook 点已找到：${method.declaringClass.name}#${method.name}")

            handle = runtime.xposed().hook(method)
                .intercept { chain -> onActivityResumed(chain, runtime) }
            HookLog.e("✅ hook 已挂上")
        } catch (t: Throwable) {
            // ⚠️ 整体吞掉：本方法跑在 system_server 里，未捕获异常危险性 = 整机（§5.1）
            HookLog.e("mount 失败：${t.javaClass.simpleName} ${t.message}", t)
        }
    }

    /**
     * 找到目标方法。
     *
     * ⚠️ 按**候选类名**逐个尝试 `loadClass`（不同系统版本在不同包下），
     * 再在 `declaredMethods` 里**按名字 + 参数个数**匹配。
     *
     * 不按精确参数类型匹配，是因为 `ActivityRecord$Token` 是**内部类**，
     * 直接引用它会把那个类拖进依赖（且它在不同版本可能改名）；
     * 参数个数 + 名字已足够区分（该方法在该类里唯一）。
     */
    private fun findTargetMethod(classLoader: ClassLoader): Method? {
        for (candidate in HookTargets.activityResumedClassCandidates()) {
            val cls = try {
                classLoader.loadClass(candidate)
            } catch (_: Throwable) {
                continue
            }

            val method = cls.declaredMethods.firstOrNull { m ->
                m.name == HookTargets.ActivityResumed.METHOD_NAME &&
                    m.parameterCount == HookTargets.ActivityResumed.ARG_COUNT
            }
            if (method != null) return method
        }
        return null
    }

    /**
     * ★ **hook 回调体** —— 跑在 system_server 的线程上。
     *
     * ⚠️ 这里**只做「取值 + 序列化 + emit」**，绝不做 IPC / I/O。
     * 发送由 [HookRuntime] 的独立线程负责。
     */
    private fun onActivityResumed(chain: XposedInterface.Chain, runtime: HookRuntime): Any? {
        try {
            val token = chain.getArg(0)
            if (token is android.os.IBinder) {
                val record = resolveRecord(chain, token)
                if (record != null) {
                    emitIfInteresting(runtime, record)
                }
            }
        } catch (t: Throwable) {
            // ⚠️ 节流日志：本回调每秒可能命中几十次，每次都打会把 logcat 冲掉
            HookLog.throttled("resumed-error", "取值失败：${t.javaClass.simpleName} ${t.message}")
        }
        // ⚠️⚠️ 必须 proceed()。不调用它，被 hook 的原方法就不会执行 ——
        // 那是**破坏整机行为**，不是「功能不生效」这么轻
        return chain.proceed()
    }

    /**
     * 反查 `ActivityRecord` 实例。
     *
     * ⚠️ 因为目标是 `static` 方法，**没有 `this`** ⇒ `chain.getThisObject()` 恒为 null。
     * 唯一可行的路径是 `ActivityRecord.forToken(IBinder)`（探针确证）。
     *
     * ⚠️ 取 `ActivityRecord` 类的途径是 `chain.getExecutable().declaringClass` ——
     * **不要缓存到静态字段**（热更新换 classloader 后静态字段是全新的）。
     */
    private fun resolveRecord(chain: XposedInterface.Chain, token: android.os.IBinder): Any? {
        val recordCls = chain.executable.declaringClass
        val forToken = try {
            recordCls.getDeclaredMethod("forToken", android.os.IBinder::class.java).apply {
                isAccessible = true
            }
        } catch (_: Throwable) {
            HookLog.throttled("no-fortoken", "找不到 forToken(IBinder) —— 反查路径失效")
            return null
        }
        return try {
            forToken.invoke(null, token)   // 静态方法，实例传 null
        } catch (t: Throwable) {
            HookLog.throttled("fortoken-fail", "forToken 调用失败：${t.javaClass.simpleName}")
            null
        }
    }

    /** 取出字段 → 判断是否被订阅 → emit。 */
    private fun emitIfInteresting(runtime: HookRuntime, record: Any) {
        val packageName = readString(record, "packageName") ?: return

        // ── 过滤：只按「包是否被关心」判断 ──
        // ⚠️ 这是**唯一的过滤**，且它不是业务判定 —— 见 HookConditions 的说明
        val cond = conditions
        if (cond.isEmpty) return
        if (!cond.caresAboutPackage(packageName)) return
        if (topic !in cond.topics) return

        val className = readComponentClassName(record)
        val intentUri = readIntentUri(record)
        val extras = readIntentExtras(record)

        val payload = ActivityPayload.encode(
            packageName = packageName,
            className = className,
            intentUri = intentUri,
            extras = extras,
        )
        runtime.emit(topic, payload)
    }

    private fun readString(target: Any, fieldName: String): String? = try {
        val f = target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
        f.get(target) as? String
    } catch (_: Throwable) {
        // 字段名可能随版本变化 —— 单个字段读不到不应影响其它字段
        HookLog.throttled("field-$fieldName", "读字段 $fieldName 失败（版本差异？）")
        null
    }

    /** `mActivityComponent` 是 `ComponentName`；取它的 className。 */
    private fun readComponentClassName(record: Any): String = try {
        val f = record.javaClass.getDeclaredField("mActivityComponent").apply { isAccessible = true }
        val component = f.get(record)
        // 用反射取 className，避免 import android.content.ComponentName
        // （保持本文件对 android.content 的依赖为零）
        component?.javaClass?.getMethod("getClassName")?.invoke(component) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * 取 `intent.toUri(1)`（`URI_INTENT_SCHEME`）。
     *
     * ⚠️ 常量 `1` 是 `Intent.URI_INTENT_SCHEME`。**不直接引用该常量** ——
     * 它会 import `android.content.Intent`（本文件刻意保持对 `android.content` 零依赖，
     * 见文件头）。这里用反射调用 `toUri(int)`，值 1 由本注释锁定。
     */
    private fun readIntentUri(record: Any): String? = try {
        val f = record.javaClass.getDeclaredField("intent").apply { isAccessible = true }
        val intent = f.get(record) ?: return null
        // URI_INTENT_SCHEME == 1（ShortX 用的也是这个，见 §4.3）
        intent.javaClass.getMethod("toUri", Int::class.javaPrimitiveType).invoke(intent, 1) as? String
    } catch (_: Throwable) {
        HookLog.throttled("intent-uri", "取 intent URI 失败（extras 可能仍可用）")
        null
    }

    /**
     * 取 Intent 的 extras。
     *
     * ⚠️ 这是本通道**最不可替代**的部分：`dumpsys` 拿不到 extras
     * （文本版只有 `(has extras)`，proto 版 grep 键名 0 命中）。
     *
     * ⚠️ 保留**原始类型**（不 toString）—— 类型信息一旦丢成字符串就无法恢复，
     * 而 `dumpsys` 那条路正是这么把米家的 String 猜成了 Long。
     */
    private fun readIntentExtras(record: Any): Map<String, Any?> = try {
        val f = record.javaClass.getDeclaredField("intent").apply { isAccessible = true }
        val intent = f.get(record) ?: return emptyMap()
        val extras = intent.javaClass.getMethod("getExtras").invoke(intent) as? Bundle
            ?: return emptyMap()

        val out = LinkedHashMap<String, Any?>()
        for (key in extras.keySet()) {
            out[key] = try {
                extras.get(key)
            } catch (_: Throwable) {
                null
            }
        }
        out
    } catch (_: Throwable) {
        HookLog.throttled("extras", "取 extras 失败")
        emptyMap()
    }

    /**
     * 热更新后接手旧 hook。
     *
     * ⚠️⚠️ **不做这件事，热更新之后事件就永久消失了** ——
     * `onHotReloaded` 的默认实现会 unhook 全部旧 hook，而热更新不会重放
     * `onSystemServerStarting`。表现是「重装 APK 后心跳正常、但事件不再产生」。
     *
     * ⚠️ **不需要 ClassLoader**（`HotReloadedParam` 根本没有 `getClassLoader()`，实测）。
     * 回调里需要的类从 `chain.getExecutable().getDeclaringClass()` 推。
     *
     * ⚠️ **不能用静态字段传跨代际状态** —— 热更新是新 classloader 加载新代码，
     * 静态字段在新代际里是全新的。
     */
    override fun remountAfterHotReload(
        oldHandles: List<XposedInterface.HookHandle>,
        runtime: HookRuntime,
    ): Boolean {
        if (oldHandles.isEmpty()) {
            HookLog.e("热更新后无旧句柄 ⇒ 无法接手（需重启设备才能重挂 hook）")
            return false
        }

        var ok = false
        for (old in oldHandles) {
            try {
                // 只接手**我们关心的那个**方法；旧代际可能还有别的 hook
                val exec = old.executable ?: continue
                if (exec.name != HookTargets.ActivityResumed.METHOD_NAME) continue

                old.replaceHook { chain -> onActivityResumed(chain, runtime) }
                HookLog.e("✅ replaceHook 成功：$exec")
                ok = true
            } catch (t: Throwable) {
                HookLog.e("❌ replaceHook 失败：${t.javaClass.simpleName} ${t.message}")
            }
        }

        if (!ok) {
            HookLog.e("⚠️ 未接手任何 hook ⇒ 热更新后事件不会产生")
        } else {
            // 与 unmount() 的语义保持一致：接管成功的句柄现在归本代际持有
            handle = null
        }
        return ok
    }

    override fun unmount() {
        try {
            handle?.unhook()
        } catch (t: Throwable) {
            HookLog.e("unhook 失败：${t.javaClass.simpleName}")
        }
        handle = null
    }

    override fun applyConditions(conditionsJson: String) {
        // ⚠️ 只解析成「过滤项」，不做任何业务判定 —— 见 HookConditions
        conditions = HookConditionWire.decode(conditionsJson)
        HookLog.e(
            "条件已更新：topics=${conditions.topics} packages=${conditions.packages.size} 个" +
                if (conditions.packages.isEmpty()) "（全部）" else ""
        )
    }
}
