package com.chaomixian.vflow.ui.shortcut_picker

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.chaomixian.vflow.services.ShellManager
import java.util.Locale

object ShortcutPickerSupport {
    private val shortcutBlockRegex = Regex(
        """ShortcutInfo.*flags.*\n\s+packageName=(.*)\n\s+activity=(.*)\n\s+shortLabel=(.*), resId=.*\n\s+longLabel=(.*), resId=.*\n\s+disabledMessage=(.*), resId=.*\n\s+disabledReason=(.*)\n\s+categories=(.*)\n\s+persons=(.*)\n\s+icon=(.*)\n\s+rank=(.*), timestamp=.*\n\s+intents=([\s\S]*?)extras=(.*)\n\s+iconRes="""
    )

    /**
     * 一次加载的结果 + **要不要给用户留痕**。
     *
     * @param items 列表项
     * @param degraded 这次是不是**走了降级路径**（dumpsys 有损）
     * @param notice 需要在界面上显示的提示（`null` = 不显示）
     */
    data class LoadResult(
        val items: List<ShortcutPickerItem>,
        val degraded: Boolean,
        val notice: String? = null,
    )

    /**
     * **选择器的主加载入口**：Xposed 可用走无损源，不可用静默降级到 dumpsys。
     *
     * 设计文档 `docs/fork/xposed-capability-invocation-design.md` §6.3：
     * 「③ 是数据源，不是目的」—— 痛点在**用户选快捷方式的那一刻**，
     * 所以落点是给选择器换源，**不是新建模块**（存量工作流里的旧步骤本能力也救不了）。
     *
     * ## ⚠️⚠️ 降级必须**留痕**，否则「昨天能用今天不能用」会被当成回归
     *
     * 两条路的结果**本来就不同**，而这**不是 bug**（§6.2 的设计）：
     *
     * | | 无损（③） | 降级（dumpsys） |
     * |---|---|---|
     * | dat 完整性 | ✅ 完整 | ❌ **18.1% 被省略** |
     * | extras 类型 | ✅ 带 `javaClass` | ❌ **靠 `length < 10` 猜**（米家因此报「无账号权限」） |
     *
     * ⇒ 同一台设备在「Xposed 掉线前后」结果会变。**不告诉用户**的话，
     * 他会以为是 App 坏了、去查错的方向（§6.2 那句「留痕」的全部意义）。
     *
     * ## 为什么**不**返回失败
     *
     * 这是**替换型**能力（`Capability.fallback != null`）⇒ 有替代实现就**不该失败**，
     * 只是更差。`CapabilityInvokeOutcome.Degraded` 已把这个语义表达在类型上。
     */
    suspend fun loadShortcutsWithFallback(context: Context): LoadResult =
        when (
            val outcome = com.chaomixian.vflow.core.xposed.CapabilityInvoker.invokeOrFallback(
                capability = com.chaomixian.vflow.xposed.capability.CapabilityNames.QUERY_SHORTCUT_INTENTS,
                params = emptyMap(),
            )
        ) {
            is com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome.Success ->
                LoadResult(items = loadShortcuts(context), degraded = false)

            // ⚠️ 降级：**静默换源 + 留痕**。`reason.code` 只用于日志，
            // 界面文案**不按 code 分支**（§6.4 约束 2：detail 与 code 都可能本地化，
            // 而这里需要的只是「告诉用户走了有损路径」这一件事）
            is com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome.Degraded ->
                LoadResult(
                    items = loadShortcuts(context),
                    degraded = true,
                    notice = DEGRADED_NOTICE,
                )

            // ⚠️ 独占型才会走到这里；本能力是替换型 ⇒ 只有「降级实现自己也坏了」一种成因。
            // 此时仍**尽力**给 dumpsys 的结果（连降级都失败时至少别显示空白）
            is com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome.Failed ->
                LoadResult(
                    items = loadShortcuts(context),
                    degraded = true,
                    notice = DEGRADED_NOTICE,
                )
        }

    /**
     * 降级留痕文案。
     *
     * ⚠️ **必须说清「为什么」与「怎么办」** —— 只说「已降级」会让用户以为功能坏了。
     * 关键是让他知道：**数据仍可用，只是可能不完整**（dat 残缺 / extras 类型丢失）。
     *
     * ⚠️ 暂用中文字面量（选择器本身也是硬编码中文，见 `UnifiedShortcutPickerSheet`
     * 的 `getString` 与既有 `text_shortcut_picker_*` 混用）——
     * 补三语资源是**独立的一次改动**，不塞进本任务（控制 diff 面积）。
     */
    private const val DEGRADED_NOTICE =
        "Xposed 通道不可用，已改用系统接口读取（数据可能有损：部分快捷方式的启动参数会缺失或类型不准）。"

    suspend fun loadShortcuts(context: Context): List<ShortcutPickerItem> {
        if (!ShellManager.isShizukuActive(context) && !ShellManager.isRootAvailable()) {
            return emptyList()
        }

        val output = ShellManager.execShellCommand(context, "dumpsys shortcut", ShellManager.ShellMode.AUTO)
        if (output.startsWith("Error:", ignoreCase = true) || !output.contains("Shortcuts:")) {
            return emptyList()
        }

        val packageManager = context.packageManager
        val defaultIcon = packageManager.defaultActivityIcon

        return shortcutBlockRegex.findAll(output)
            .mapNotNull { match ->
                val packageName = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val activityName = match.groupValues.getOrNull(2)?.trim().orEmpty()
                val shortcutLabel = match.groupValues.getOrNull(3)?.trim().orEmpty()
                val rawIntent = match.groupValues.getOrNull(11).orEmpty()

                if (packageName.isBlank() || shortcutLabel.isBlank() || rawIntent.isBlank()) {
                    return@mapNotNull null
                }

                val launchCommand = buildLaunchCommand(rawIntent) ?: return@mapNotNull null
                ShortcutPickerItem(
                    appName = loadAppName(packageManager, packageName) ?: packageName,
                    packageName = packageName,
                    shortcutLabel = shortcutLabel,
                    activityName = activityName,
                    launchCommand = launchCommand,
                    icon = loadAppIcon(packageManager, packageName) ?: defaultIcon
                )
            }
            .distinctBy { it.stableId }
            .sortedWith(
                compareBy<ShortcutPickerItem> { it.appName.lowercase(Locale.getDefault()) }
                    .thenBy { it.shortcutLabel.lowercase(Locale.getDefault()) }
            )
            .toList()
    }

    /**
     * ③ 的**降级实现**：没有 Xposed 通道时，用 dumpsys 路径返回**同形状**的结果。
     *
     * ## ⚠️⚠️ 它必须与无损路径「同入参、同形状、更差」（§6.2 的 S7 修正）
     *
     * 契约是「**同样的入参、同形状的结果、更差的实现**」——
     * 所以这里**刻意复用** [loadShortcuts] 的整条解析链（正则 + `readData` + `buildLaunchCommand`），
     * 不另写一份。另写会让「升级/降级的结果差异」变成**两个实现之间**的差异，无从对照。
     *
     * ## ⚠️ 它 `suspend`，而 `Capability.fallback` 也要 `suspend` —— 两者对齐
     *
     * `loadShortcuts` 要跑 shell（Shizuku/Root），本就是挂起函数。
     *
     * ## ⚠️ 返回形状与 `QueryShortcutIntentsHandler` 的 `items` 对齐
     *
     * 同一套键（`package_name` / `shortcut_label` / `launch_command` …），
     * 但**只有 dumpsys 能提供的那些** —— 缺的键**不填假值**：
     * 「这条路给不出 extras 类型」是**事实**，如实缺键比填 `unknown` 更有信息量
     *（调用方按 `containsKey` 就能分辨走了哪条路）。
     *
     * ⚠️ **它是有损的**（这正是它作为「更差的实现」的原因）：
     * dat 残缺 18.1%、extras 类型靠猜（`length < 10` 启发式）。
     */
    suspend fun queryViaDumpsys(packageName: String): Map<String, Any?> {
        val all = loadShortcuts(requireContextOrNull() ?: return emptyMap())
        val filtered = if (packageName.isBlank()) {
            all
        } else {
            all.filter { it.packageName == packageName }
        }
        return mapOf(
            "items" to filtered.map { item ->
                mapOf(
                    "package_name" to item.packageName,
                    "shortcut_label" to item.shortcutLabel,
                    "activity_name" to item.activityName,
                    // ⚠️ 降级路径的产物是**已拼好的命令**，不是结构化 Intent
                    // ⇒ 键名刻意与无损路径不同（`launch_command` vs `intent_data`），
                    //   让「走了哪条路」在结果里**一眼可辨**，不必靠日志
                    "launch_command" to item.launchCommand,
                    "source" to "dumpsys",
                )
            },
        )
    }

    /**
     * 取 Context。
     *
     * ⚠️ `loadShortcuts` 需要它，而本对象是 `object`（无 Context）⇒
     * 复用仓库既有的 `LogManager.applicationContext`（已在多处这样用）。
     * ⚠️ 拿不到时返回 null 而**不是抛** —— 降级路径不允许把异常带进调用方。
     */
    private fun requireContextOrNull(): Context? = try {
        com.chaomixian.vflow.core.logging.LogManager.applicationContext
    } catch (_: Throwable) {
        null
    }

    internal fun buildLaunchCommand(rawIntentBlock: String): String? {
        val condensed = rawIntentBlock.replace(Regex("\n\\s+"), "")
        // 一个快捷方式可携带多个 Intent，语义是「前面的负责堆栈回退，最后一个才是启动目标」
        // （ShortcutInfo.getIntent() 返回 mIntents[length - 1]）。取第一个会启动到错误目标：
        // 美团「扫一扫」的首个 Intent 只是跳主界面的兜底项（extras 为 shortcuts=true），
        // 真正触发扫一扫的 dat=imeituan://… 在最后一个，取首项会退化成「打开 App 主页」。
        // 注意收尾不能写成 `\]`：多 Intent 时数组的 `]` 落在整段末尾，中间项后面是逗号，
        // 会让 `.*?` 吞掉整个段、把多个 Intent 的键值混成一个。extras 用花括号界定即可。
        val match = Regex("""Intent \{(.*?)\}/(?:PersistableBundle\[\{(.*?)\}\]|null)""")
            .findAll(condensed)
            .lastOrNull()
            ?: return null
        val intentData = readData(match.groupValues.getOrNull(1).orEmpty())
        val extraData = readData(match.groupValues.getOrNull(2).orEmpty())

        if (intentData.isEmpty()) {
            return null
        }

        return buildString {
            append("am start")
            intentData["act"]?.takeIf { it.isNotBlank() }?.let {
                append(" -a ")
                append(shellQuote(it))
            }
            // pkg 是目标包限定。部分快捷方式（如小米「垃圾清理」）只有 act + pkg、没有 cmp，
            // 丢掉就没有任何定位信息了；pkg 与 cmp 并存时两者都保留（与 dump 原文顺序一致）。
            intentData["pkg"]?.takeIf { it.isNotBlank() }?.let {
                append(" -p ")
                append(shellQuote(it))
            }
            intentData["cmp"]?.takeIf { it.isNotBlank() }?.let {
                append(" -n ")
                append(shellQuote(it))
            }
            intentData["dat"]?.takeIf { it.isNotBlank() }?.let {
                append(" -d ")
                append(shellQuote(it))
            }
            intentData["flg"]?.takeIf { it.isNotBlank() }?.let {
                append(" -f ")
                append(it)
            }
            extraData.forEach { (key, value) ->
                if (key.isBlank() || value.isBlank()) return@forEach
                when {
                    isBoolean(value) -> {
                        append(" --ez ")
                        append(shellQuote(key))
                        append(' ')
                        append(value.lowercase(Locale.ROOT))
                    }
                    isInteger(value) && value.length < 10 -> {
                        append(" --ei ")
                        append(shellQuote(key))
                        append(' ')
                        append(value)
                    }
                    isLong(value) -> {
                        append(" --el ")
                        append(shellQuote(key))
                        append(' ')
                        append(value)
                    }
                    isFloat(value) -> {
                        append(" --ef ")
                        append(shellQuote(key))
                        append(' ')
                        append(value)
                    }
                    else -> {
                        append(" --es ")
                        append(shellQuote(key))
                        append(' ')
                        append(shellQuote(value))
                    }
                }
            }
        }
    }

    internal fun readData(rawData: String): Map<String, String> {
        val normalized = rawData.replace(", ", " ")
        val key = StringBuilder()
        val value = StringBuilder()
        val result = linkedMapOf<String, String>()
        var readingKey = true
        var seenEquals = false

        normalized.forEach { char ->
            when {
                char == ' ' -> {
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        result[key.toString()] = value.toString()
                        key.clear()
                        value.clear()
                        readingKey = true
                        seenEquals = false
                    }
                }
                char == '=' && !seenEquals -> {
                    seenEquals = true
                    readingKey = false
                }
                readingKey -> key.append(char)
                else -> value.append(char)
            }
        }

        if (key.isNotEmpty() && value.isNotEmpty()) {
            result[key.toString()] = value.toString()
        }

        return result
    }

    private fun loadAppName(packageManager: PackageManager, packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAppIcon(packageManager: PackageManager, packageName: String): Drawable? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationIcon(appInfo)
        } catch (_: Exception) {
            null
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun isBoolean(value: String): Boolean = value == "true" || value == "false"

    private fun isInteger(value: String): Boolean = value.toIntOrNull() != null

    private fun isLong(value: String): Boolean = value.toLongOrNull() != null

    private fun isFloat(value: String): Boolean = value.toFloatOrNull() != null
}
