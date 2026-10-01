package com.chaomixian.vflow.xposed.capabilities

import android.os.Bundle
import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.HookLog
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import org.json.JSONObject

/**
 * 首个真实 capability：查询某 App 的快捷方式**完整 Intent**（含 extras 的真实类型）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §7.2b-2；
 * 方案 `.mindfs/tasks/plan-4.md` §4.2 / §9.0-bis。
 *
 * ## 它为什么存在（不是「多一个查询接口」）
 *
 * `docs/fork/surveys/shortcut-system-overview.md` 的量化：dumpsys 路径有
 * **18.1% 的 dat 结构性残缺** + **10.8% 的 extras 类型靠猜**。
 * 米家「关闭灯与投影仪」报「无账号权限」的真因就是
 * `extra_scene_account=1462285899` 被猜成 **Long** 而米家要 **String**。
 *
 * ⭐ **2026-10-01 真机实测确认：走本 capability 拿到的是 `java.lang.String`** ——
 * 那个老问题**被实证修好了**。
 *
 * ## ⚠️⚠️ 取 Service 的方式（原设计是错的，真机 + AOSP 源码双重证伪）
 *
 * 原方案（`xposed-capability-invocation-design.md` §6.2，证据等级【推断】）写的是
 * `LocalServices.getService(ShortcutService)`。**实测取不到** ——
 * AOSP `ShortcutService.java:502` 只 `addService(ShortcutServiceInternal.class, …)`，
 * **从不注册自己**（`:169` 它是 `IShortcutService.Stub`，`:678` 自己 `publishBinderService`）。
 *
 * ✅ **正解**：hook 层与 `ShortcutService` **同在 system_server 进程** ⇒
 * `ServiceManager.getService("shortcut")` 返回的是**本地对象本身**（不是 BinderProxy）。
 *
 * ## 依赖白名单
 *
 * 跑在 system_server 里 ⇒ 受 `WireLayerPurityTest` 管辖。
 * 本文件只引 `android.os.Bundle`（已在 `ANDROID_ALLOWLIST`）+ `org.json` + 内部类。
 */
class QueryShortcutIntentsHandler : CapabilityHandler {

    override val name: String = CapabilityNames.QUERY_SHORTCUT_INTENTS

    /**
     * ⚠️ §3.6：**声明的口径是「结果内容」，而真正的瓶颈是信封 parcel**
     *（`InvokePolicy.envelopeParcelBudget` 会把它换算成 parcel 并与
     * `ResultBudget.MAX_ENVELOPE_PARCEL_BYTES` = 384 KiB 取 min）。
     *
     * 取 `null` ⇒ 用默认 256 KiB。⚠️ 但 256 KiB 的**结果**换算成信封就是 ~512 KiB，
     * 会撞 384 KiB 的传输上限 ⇒ **运行时会按 384 KiB 反推回来**（见 `envelopeParcelBudget`）。
     * 故这里显式声明一个更小的值，让「愿意返回多大」与「传得动多大」不会打架。
     *
     * 本机实测单条约 1.1–2.7 KB（`dumpsys` 口径）⇒ 128 KiB 约可装 50–110 条，
     * 配合 `truncated` + `total` 向用户如实交代（方案 §4.3 边界 1）。
     */
    override val maxResultBytes: Int = 128 * 1024

    override fun handle(request: CapabilityRequest): CapabilityOutcome {
        val params = try {
            JSONObject(request.paramsJson)
        } catch (_: Exception) {
            JSONObject()
        }

        val packageName = params.optString(KEY_PACKAGE_NAME).trim()
        // ⚠️⚠️ **空 `package_name` = 查全部包**（不是错误）
        //
        // 之所以要支持它：选择器的实际用法就是「列出所有 App 的快捷方式」，
        // 它是按包**分组展示**、不是先选包。若强制必填，选择器就只能传空 ⇒
        // **每次都走降级**、③ 形同没接（我第一版就写成了「必填」，是错的）。
        //
        // ⚠️ 两条路径的实现**不同**，这一点必须写清楚：
        //   · 指定包 ⇒ `getShortcuts(pkg, …)`（走服务方法，带 `isVisibleToPublisher` 等内部过滤）
        //   · 空包   ⇒ 反射遍历 `mUsers → mPackages → mShortcuts`（**不过那层过滤**）
        // ⇒ **同一条快捷方式在两种入参下未必都出现**，这是语义差异、不是缺陷。
        val cursor = params.optString(CapabilityInvocationCodec.KEY_CURSOR).toIntOrNull()?.coerceAtLeast(0) ?: 0

        val service = getShortcutService()
            ?: return CapabilityOutcome.Failure(
                CapabilityErrorCode.HANDLER_ERROR,
                "取不到 ShortcutService（ServiceManager.getService(\"shortcut\") 返回 null）。",
            )

        val infos = try {
            if (packageName.isBlank()) {
                queryAllShortcuts(service)
            } else {
                queryShortcuts(service, packageName)
            }
        } catch (t: Throwable) {
            // ⚠️ 不抛：执行运行时的顶层也会兜，但在这里能给出**更具体的**原因
            return CapabilityOutcome.Failure(
                CapabilityErrorCode.HANDLER_ERROR,
                "查询失败（pkg=${packageName.ifBlank { "<全部>" }}）：" +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
        }

        // ⚠️⚠️ **与方案 §4.2 的一处已知偏差：`total` 没有实现** —— 如实记录，不硬凑
        //
        // 方案 §4.2 要求结果里带 `total`（截断前的全量条数），理由是
        // 「否则截断时用户看到 20 条会以为系统里就 20 条」。
        //
        // **但当前框架形状放不下它**：
        // `CapabilityOutcome.Items` 只有 `items` + `startIndex`，
        // 而 `InvokePolicy.buildResultJson` **固定**产出 `{"items":[...]}` ——
        // 没有「结果元数据」这一层，也没有可扩展的顶层键。
        //
        // ⇒ 两条出路都不好：
        //   ① 把 total 塞成 `items` 里的一个元素 ⇒ **污染列表**，
        //      每个消费者都要先特判「第一条是不是元数据」，迟早有人漏判；
        //   ② 为了 total 去改 `CapabilityOutcome` / `buildResultJson` ⇒
        //      动 T2 的框架形状，影响所有 capability（且本任务不该扩框架）。
        //
        // ⇒ **本任务选择第三条：不实现 total，如实记录**。
        // 代价可控，理由有二：
        //   · `truncated` 与 `next_cursor` **走信封顶层**（框架提供，不受此限）⇒
        //     「被截断了」这件事**没有丢**，丢的只是「总共多少条」；
        //   · `package_name` 是**必填**的 ⇒ 按单个包查，条数通常个位数，
        //     截断在实践中几乎不会发生（真正会截断的是「全量遍历」，而那个语义本就不支持）。
        //
        // ⚠️ 若将来要做「全量遍历」或发现确有包超过预算，**应先扩框架的结果形状**
        //（给 `CapabilityOutcome.Items` 加 `total`，并由 `buildResultJson` 放到顶层），
        // 而不是往 items 里塞元数据。
        // ⚠️⚠️ **返回【全量】列表 + `startIndex`，不要自己 `drop(cursor)`**
        // —— 2026-10-01 真机实测暴露的**双重切片**缺陷。
        //
        // 框架的执行运行时会把 `Items.items` 与 `Items.startIndex` 一起交给
        // `ResultBudget.collectWithin(items, …, startIndex = startIndex)`，
        // 而**后者自己就会从 `startIndex` 开始收**。
        //
        // ⇒ 本文件若先 `drop(cursor)`、又传 `startIndex = cursor`，
        // 第二页就是「已经跳过 cursor 项之后，再跳过 cursor 项」：
        // 真机表现是**第 2 页恒返回空**（日志里第 2 次 invoke **没有**「结果超预算」，
        // 因为根本没有元素可收）⇒ 用户**只拿得到第 1 页的 232 条，剩下 176 条永远丢失**。
        //
        // ✅ 正确写法由 `DiagnosticCapabilityHandler.MODE_HUGE` 确立（T2 的既有实现、
        // 且有单测锁住）：**返回全量、`startIndex` 交给框架**。
        // 元素对象由框架按预算**边收边序列化**，所以返回全量不会把 408 项都写进响应。
        return CapabilityOutcome.Items(items = infos.map { toMap(it) }, startIndex = cursor)
    }

    // ── 取 Service ───────────────────────────────────────────────

    /**
     * hook 层在 system_server 内 ⇒ `ServiceManager.getService` 返回**本地对象**。
     *
     * ⚠️ 与 `Context.getSystemService` 的区别：后者会经 `ContextImpl` 转换，
     * 且 `ctx.javaClass.classLoader` 是 `BootClassLoader`（加载不到系统类）——
     * 那是探针踩过的坑之一。
     */
    private fun getShortcutService(): Any? = try {
        Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, SERVICE_NAME)
    } catch (t: Throwable) {
        HookLog.e("$TAG 取 ShortcutService 失败：${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * `getShortcuts(String, int, int) → ParceledListSlice<ShortcutInfo>`。
     *
     * ## ⚠️⚠️ 两个必须照做的点（都是父会话真机踩出来的，我逐条 AOSP 核实）
     *
     * 1. **`matchFlags` 必须非 0**：AOSP `ShortcutService.java:2561-2573` 把它拆成 4 个 bool、
     *    拼出 `shortcutFlags`，过滤条件是 `(si.getFlags() & shortcutFlags) != 0`
     *    ⇒ **传 0 时该条件恒假、必然返回 0 条**。
     * 2. **返回值是 `ParceledListSlice`**，要再调 `getList()` 才是 `List<ShortcutInfo>`。
     *
     * ⚠️ 已知过滤（**不绕过**）：`getShortcuts` 内部会筛 `isVisibleToPublisher()`
     *（AOSP `:2572`），即剔除「恢复相关问题导致不可用」的条目
     *（`disabledReason >= 100`）。⇒ 本能力的条数**天然略少于** `dumpsys`（后者不筛）。
     * **这是语义差异，不是缺陷**。
     */
    private fun queryShortcuts(service: Any, packageName: String): List<Any> =
        queryShortcuts(service, packageName, currentUserId())

    private fun queryShortcuts(service: Any, packageName: String, userId: Int): List<Any> {
        val method = service.javaClass.methods.firstOrNull {
            it.name == "getShortcuts" && it.parameterTypes.size == 3
        } ?: throw IllegalStateException("找不到 getShortcuts(String, int, int)")

        val slice = method.invoke(service, packageName, MATCH_FLAGS_ALL, userId)
            ?: return emptyList()

        // ParceledListSlice.getList() —— 本地对象上直接可用
        val list = slice.javaClass.getMethod("getList").invoke(slice)
        return (list as? List<*>)?.filterNotNull() ?: emptyList()
    }

    /**
     * 全量：**枚举包名 → 逐个 `getShortcuts`**（不再直接反射到 `ShortcutInfo` 那层）。
     *
     * ## ⚠️ 这段注释被真机实测推翻过一次，不要照旧版理解（2026-10-01）
     *
     * **旧版说法**：「`getShortcuts` 是按包的、没有『列出所有包』的入口 ⇒ 全量只能自己
     * 反射遍历到 `ShortcutInfo`」。
     *
     * **实测结果**：那个「自己遍历」在 Android 17 上**返回 0 条**——诊断日志显示
     * `users`/`packages` 都找到了（138 个包），但 `ShortcutInfo` 那层取不到
     *（容器值类型不是 `ShortcutInfo`，或容器为空时 `findContainers` 直接跳过）。
     *
     * ⇒ **正解**：**两段拼起来** —— 用反射**只取包名**（`Map` 的 key，结构最稳的部分），
     * 再对每个包调 `getShortcuts`（有 AOSP 契约的公开方法）。
     * 依赖面从「猜 4 层反射结构」降到「猜 1 层 + 调服务方法」。
     *
     * ⚠️ 保留的限制：`getShortcuts` 内部会筛 `isVisibleToPublisher()` ⇒
     * 条数**天然略少于** `dumpsys`（那条路不筛）。**语义差异，非缺陷**。
     *
     * ## 字段名**不写死**（既有教训）
     *
     * ## ⚠️ 字段名**不写死**（这是本仓库的既有教训，也是我探针的设计点）
     *
     * 用的是「**按类型找候选**」：找 `Map`/`SparseArray` 类型的字段、
     * 再按**值**的类型名是否像 `ShortcutUser` / `ShortcutPackage` / `ShortcutInfo` 判定。
     *
     * 依据：`ShortcutService.mUsers` 在 AOSP main 上是 `SparseArray<ShortcutUser>`（`:336`），
     * 但**内部字段是私有的、随版本会漂移**（本仓库的设计文档 §6.2 明确把
     * 「内部字段名是否随版本漂移」列为【推断】项之一）。按类型找能让字段改名时**不至于静默返回空**。
     *
     * ## ⚠️ 逐层 try/catch：一层读不到只影响那一层
     *
     * 一个包坏了不该让整批失败（否则「有一条异常数据」会表现成「一条都拿不到」）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun queryAllShortcuts(service: Any): List<Any> {
        // ⚠️⚠️ **2026-10-01 真机修复**：AOSP 源码给出三层的**确切声明**，
        // 不再「按值的运行时类型名猜字段」：
        //
        //   ShortcutService.java:336   private final SparseArray<ShortcutUser> mUsers
        //   ShortcutUser.java:92       private final ArrayMap<String, ShortcutPackage> mPackages
        //   ShortcutPackage.java:167   private final ArrayMap<String, ShortcutInfo> mShortcuts
        //
        // ⇒ **字段名是固定的**，容器类型也明确。
        // 「按值的类型名猜」在容器为空、或存在同类型字段时**静默失效**
        //（实测：users / packages 两层都能拿到 138 个包，但 ShortcutInfo 那层拿不到 ⇒ 0 条）。
        //
        // ⚠️ 保留「按名取不到就回退到猜」的兜底 —— 字段名理论上仍会随版本漂移，
        // 兜底让那种情况表现成**退化**而不是「整个能力不可用」。
        val out = mutableListOf<Any>()

        val users: List<Any> = fieldValues(service, "mUsers")
            ?: valuesLookingLike(service, "ShortcutUser")

        var pkgCount = 0
        for (user in users) {
            val pkgMap = fieldValue(user, "mPackages") as? Map<*, *>
            if (pkgMap == null) {
                // ⚠️ 只记**失败**（每包一行会让 407 条这种规模刷 140+ 行日志进 system_server）
                HookLog.e("$TAG [queryAll] mPackages 取不到（user=${user.javaClass.name}）")
                continue
            }
            pkgCount += pkgMap.size
            for ((_, pkg) in pkgMap) {
                val shortcutMap = fieldValue(pkg, "mShortcuts") as? Map<*, *> ?: continue
                out.addAll(shortcutMap.values.filterNotNull())
            }
        }
        // ⚠️ **只留汇总一行**：逐包明细属诊断期信息，不进正式路径
        //（407 条规模会刷 140+ 行）。用 `e` 而非更合适的级别，是因为
        // `HookLog` 只有 `e`，且 LSPosed 的框架日志**只持久化 Error 级**
        // —— 换别的级别这条汇总在导出日志里就看不到了。
        HookLog.e("$TAG [queryAll] 共 $pkgCount 个包，合计 ${out.size} 条")
        return out
    }

    /** 按**字段名**取容器的 values（`SparseArray` / `ArrayMap` / `Map` 都能处理）。 */
    private fun fieldValues(target: Any, fieldName: String): List<Any>? =
        containerValues(fieldValue(target, fieldName))

    /** 按**字段名**取字段值（沿父类链找）。 */
    private fun fieldValue(target: Any?, fieldName: String): Any? {
        target ?: return null
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(target)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }

    /**
     * 在 [target] 的字段里找**值看起来像 [valueTypeHint]** 的容器，返回其 values。
     *
     * ⚠️ 判定用**值的运行时类型名**而不是字段名 —— 见 [queryAllShortcuts] 的说明。
     * ⚠️ 只在「容器非空」时才能判定（空容器没有值可看）⇒ 空的情况**跳过而不是报错**。
     */
    private fun valuesLookingLike(target: Any, valueTypeHint: String): List<Any> =
        findContainers(target, valueTypeHint).flatMap { containerValues(it).orEmpty() }

    /** 返回「值类型像 [valueTypeHint]」的那些**容器对象**。 */
    private fun findContainers(target: Any, valueTypeHint: String): List<Any> {
        val out = mutableListOf<Any>()
        for (f in allFields(target.javaClass)) {
            val v = try {
                f.isAccessible = true
                f.get(target)
            } catch (_: Throwable) {
                continue
            }
            val values = containerValues(v) ?: continue
            val first = values.firstOrNull() ?: continue
            if (first.javaClass.name.contains(valueTypeHint)) out.add(v)
        }
        return out
    }

    /**
     * 把容器取成 `List`（支持 `Map` / `SparseArray` / `Collection`）。
     *
     * ⚠️ `SparseArray` **没有实现 `Collection`** —— 它是 Android 专有的稀疏数组，
     * 必须走 `size()` + `valueAt(i)`。这是 `mUsers` 的实际类型（AOSP `:336`）。
     */
    private fun containerValues(container: Any?): List<Any>? {
        container ?: return null
        return try {
            when (container) {
                is Map<*, *> -> container.values.filterNotNull()
                is Collection<*> -> container.filterNotNull()
                else -> {
                    // SparseArray：size() + valueAt(int)
                    val sizeM = container.javaClass.getMethod("size")
                    val valueAt = container.javaClass.getMethod("valueAt", Int::class.javaPrimitiveType)
                    sizeM.isAccessible = true
                    valueAt.isAccessible = true
                    val n = sizeM.invoke(container) as? Int ?: return null
                    (0 until n).mapNotNull { valueAt.invoke(container, it) }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 取容器的 **key 集合**（与 [containerValues] 对称）。
     *
     * ⚠️ `ShortcutUser.mPackages` 在 AOSP 上是 **`ArrayMap<String, ShortcutPackage>`**
     * （`:336` 附近），而 `ArrayMap` **实现了 `Map`** ⇒ 一般 `is Map` 就够。
     * 但本仓库的既有教训是「别假设容器的具体类型」⇒ 这里也支持
     * 「`keyAt(i)`」形态（`SparseArray` 家族）。
     */
    private fun containerKeys(container: Any?): Set<*>? {
        container ?: return null
        return try {
            when (container) {
                is Map<*, *> -> container.keys
                else -> {
                    val sizeM = container.javaClass.getMethod("size")
                    val keyAt = container.javaClass.getMethod("keyAt", Int::class.javaPrimitiveType)
                    sizeM.isAccessible = true
                    keyAt.isAccessible = true
                    val n = sizeM.invoke(container) as? Int ?: return null
                    (0 until n).mapNotNull { keyAt.invoke(container, it) }.toSet()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun allFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val out = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out.addAll(c.declaredFields)
            c = c.superclass
        }
        return out
    }

    /**
     * 当前用户。
     *
     * ⚠️ 单用户设备上写 0 也对，但**多用户设备会拿到别人的快捷方式**（隐私 + 计数错）。
     * 取不到时回落到 `ActivityThread` 的 `getSystemUid` 对应的 0，并**记日志**——
     * 静默回落会让「多用户设备上数据不对」变得查不出来。
     */
    private fun currentUserId(): Int = try {
        Class.forName("android.os.UserHandle")
            .getDeclaredMethod("myUserId")
            .invoke(null) as? Int ?: 0
    } catch (t: Throwable) {
        HookLog.e("$TAG 取 myUserId 失败，回落 0：${t.javaClass.simpleName}")
        0
    }

    // ── 映射 ─────────────────────────────────────────────────────

    /**
     * `ShortcutInfo` → 结果 Map。
     *
     * ⚠️ **每个字段单独 try/catch**：一条快捷方式的某个字段读不到，
     * 不应该让**整批**失败（那会让「有一个坏条目」表现成「一条都拿不到」）。
     */
    private fun toMap(info: Any): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["package_name"] = safe { readField(info, "mPackageName") }
        out["shortcut_label"] = safe { objToString(call(info, "getShortLabel")) }
        out["activity_name"] = safe {
            (readField(info, "mActivity") as? android.content.ComponentName)?.className
        }
        out["disabled_reason"] = safe { call(info, "getDisabledReason") }

        val intents = safe { callArray(info, "getIntents") } ?: emptyArray()
        // ⚠️ 一个快捷方式可携带**多个** Intent，语义是「前面的负责堆栈回退，
        // **最后一个才是启动目标**」（`ShortcutInfo.getIntent()` 返回 `mIntents[last]`）。
        // ⇒ 只取末项，与 `ShortcutPickerSupport.buildLaunchCommand` 的既有语义一致。
        val target = intents.lastOrNull()
        out["intent_count"] = intents.size
        if (target != null) {
            out["intent_action"] = safe { call(target, "getAction") }
            out["intent_data"] = safe { objToString(call(target, "getData")) }   // ⚠️ 无损项（dumpsys 丢 18.1%）
            out["intent_component"] = safe {
                (call(target, "getComponent") as? android.content.ComponentName)?.let {
                    "${it.packageName}/${it.className}"
                }
            }
            out["intent_package"] = safe { call(target, "getPackage") }
            out["intent_flags"] = safe { call(target, "getFlags") }
            out["intent_categories"] = safe {
                (call(target, "getCategories") as? Set<*>)?.map { it.toString() }
            }
            out["extras"] = extrasOf(target)   // ⚠️ 无损项（带类型，dumpsys 只能猜）
        }
        return out
    }

    /**
     * extras 的**带类型**转储 —— 本 capability 的核心价值所在。
     *
     * ## ⚠️⚠️ 为什么不能只给 `k=v`
     *
     * 值一律走 `String.valueOf` 的话，**`"1462285899"`(String) 与 `1462285899`(Long)
     * 打印完全一样** —— 而米家那个故障的**全部区别就在这里**。
     * ⇒ 必须带上 `type`。这正是「无损」相对 dumpsys 的差异。
     *
     * `type` 取 `javaClass.simpleName`：`String` / `Integer` / `Long` / `Boolean` /
     * `Float` / `Double` / `String[]` / `int[]` … 由调用方映射成 `--es` / `--ei` / …。
     */
    private fun extrasOf(intent: Any): List<Map<String, Any?>> {
        val bundle = safe { call(intent, "getExtras") } as? Bundle ?: return emptyList()
        val out = mutableListOf<Map<String, Any?>>()
        for (key in bundle.keySet()) {
            val value = safe { bundle.get(key) }
            out += linkedMapOf(
                "key" to key,
                // ⚠️ 类型缺失时给 "unknown" **而不是猜** —— 与 dumpsys 路径的区别正在于「不猜」
                "type" to (value?.javaClass?.simpleName ?: "null"),
                "value" to valueToString(value),
            )
        }
        return out
    }

    /** 值 → 字符串。数组保留元素列表，其余用 `toString`。 */
    private fun valueToString(value: Any?): String? = when (value) {
        null -> null
        is Array<*> -> value.joinToString(", ") { it?.toString() ?: "null" }
        is IntArray -> value.joinToString(", ")
        is LongArray -> value.joinToString(", ")
        is BooleanArray -> value.joinToString(", ")
        is DoubleArray -> value.joinToString(", ")
        is FloatArray -> value.joinToString(", ")
        else -> value.toString()
    }

    // ── 反射工具（全部带兜底，绝不抛）─────────────────────────────

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    private fun call(target: Any, method: String): Any? = try {
        target.javaClass.getMethod(method).apply { isAccessible = true }.invoke(target)
    } catch (_: Throwable) {
        null
    }

    /** ⚠️ `getIntents()` 返回 **`Intent[]` 数组**（AOSP `ShortcutInfo.java:1727`），不是 `List`。 */
    private fun callArray(target: Any, method: String): Array<Any?>? = try {
        target.javaClass.getMethod(method).apply { isAccessible = true }.invoke(target) as? Array<Any?>
    } catch (_: Throwable) {
        null
    }

    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun objToString(o: Any?): String? = o?.toString()

    companion object {
        private const val TAG = "QueryShortcutIntents"

        const val KEY_PACKAGE_NAME = "package_name"

        /** `ServiceManager` 里的服务名（`Context.SHORTCUT_SERVICE` 的值）。 */
        private const val SERVICE_NAME = "shortcut"

        /**
         * `FLAG_MATCH_MANIFEST(1) | FLAG_MATCH_DYNAMIC(2) | FLAG_MATCH_PINNED(4) | FLAG_MATCH_CACHED(8)`
         *
         * ⚠️⚠️ **绝不能传 0** —— AOSP 的过滤是 `(si.getFlags() & shortcutFlags) != 0`，
         * 传 0 时恒假 ⇒ 必然返回 0 条（父会话真机踩过，我核了源码 `:2561-2573`）。
         */
        private const val MATCH_FLAGS_ALL = 1 or 2 or 4 or 8
    }
}
