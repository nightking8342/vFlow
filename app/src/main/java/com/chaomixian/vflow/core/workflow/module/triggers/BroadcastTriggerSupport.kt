package com.chaomixian.vflow.core.workflow.module.triggers

/**
 * 广播触发器（`vflow.trigger.broadcast`）的**纯函数层**。
 *
 * 设计文档：`docs/fork/broadcast-trigger-design.md`。
 *
 * ## ⚠️ 无 Android 依赖，可跑纯 JVM 单测
 *
 * 这里全是**「改错了不报错、只静默变差」**的地方 ——
 * 归一化漏一步 ⇒ 用户配了但永不触发（漏掉空格/空串），或注册出一个非法 filter
 * （`"*"` 被原样交给 `addAction`，平台**不会**报错，只是永远匹配不到）。
 * 两者都没有任何可观测点，所以必须由测试钉住。
 *
 * ## ⚠️ 三类条件的平台语义**各不相同**（这是本模块最容易做错的地方）
 *
 * | 条件 | 语义 | 通配 |
 * |---|---|---|
 * | `action` | OR（列表里任一命中即可） | ❌ **没有**（`addAction("*")` 无效） |
 * | `data` scheme | OR（列表里任一命中即可） | ❌ **没有**（`addDataScheme("*")` 同样无效） |
 * | `category` | **AND**（intent 携带的每个 category 都要在列表里） | — |
 *
 * ⚠️ category 的语义与 action **相反**（AOSP `addCategory` 的 javadoc 专门用
 * 「the semantics of categories is the opposite of actions」开头）——
 * 凭直觉写成「任一命中」会**误触发**。
 *
 * ⇒ 本层**不试图自己判定命中**（那是 `IntentFilter` 的活，见 Handler 的精确注册决策），
 * 只负责把用户输入收敛成**能交给 `IntentFilter` 的形态**。
 */
object BroadcastTriggerSupport {

    /**
     * 通配符字面量。
     *
     * ⚠️⚠️ **平台没有任何通配写法**（设计文档 §3.1/§3.2）：
     * - `IntentFilter.matchAction` 是 `mActions.contains(action)` ——
     *   `addAction("*")` 只是把字面量 `"*"` 存进列表，真实 action 永远匹配不到；
     * - `matchData` 的 scheme 分支同理（`schemes.contains(scheme)`）。
     *
     * 而且**不声明任何 action** 的 filter 也**不是**「任意」——
     * 它只匹配「没有 action 的 intent」（类级 javadoc 逐字）。
     * ⇒ **不得沿用本仓库其它触发器「留空 = 任意」的惯例**：
     * 那样做出来的默认值是「永不触发」且无任何报错。
     */
    const val WILDCARD = "*"

    /**
     * 从 parameters 的 `Any?` 解出字符串列表。
     *
     * 兼容三种存储形态（编辑器 / JSON 导入 / AI 写入都可能给不同形状）：
     * `List<*>`（正常）、单个 `String`（用户手工改 JSON）、`null`（未填）。
     *
     * ⚠️ `List<*>` 里的**非 String 元素被丢弃而不是 `toString()`** ——
     * 把 `123` 变成 `"123"` 会造出一个**永远不会匹配**的 action，
     * 而用户看到列表里那一项「长得没问题」。宁可少一项也不要造假项。
     */
    fun stringListOf(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is String -> if (raw.isEmpty()) emptyList() else listOf(raw)
        is List<*> -> raw.filterIsInstance<String>()
        else -> emptyList()
    }

    /**
     * trim + 丢弃空串 + **保序去重**。
     *
     * ⚠️ **本函数不丢弃 [WILDCARD]** —— 编辑期的职责分工是：
     * [stripBlank] 只管「空白」，`"*"` 留给 [validateActions] 拦并**给出文案**。
     * 若在这里悄悄丢掉，用户输入 `"*"` 后点保存会看到列表里那一项凭空消失、
     * 既没有报错也不知道为什么。
     *
     * ⚠️ `trim()` 用 **Kotlin 默认语义**（`Char.isWhitespace()`，覆盖全角空格
     * U+3000 等 Unicode 空白）。**不用** `trim(' ')`：
     * 中文输入法下用户很容易打进全角空格，用 `trim(' ')` 会漏掉它
     * ⇒ 保存出一个「看起来没填、实际是 `"　"`」的项，
     * 而它在 `IntentFilter` 里是个合法的（永远不会命中的）字面量。
     */
    fun stripBlank(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (item in raw) {
            val trimmed = item.trim()
            if (trimmed.isNotEmpty()) out.add(trimmed)
        }
        return out.toList()
    }

    /**
     * 运行时归一化 action。注册 filter 前**必须**过这一步。
     *
     * 在 [stripBlank] 之上额外丢弃 [WILDCARD] ——
     * 编辑期漏网的（JSON 导入 / 直接改 prefs / 旧版本工作流）在这里兜住。
     */
    fun normalizeActions(raw: List<String>): List<String> =
        stripBlank(raw).filter { it != WILDCARD }

    /**
     * 运行时归一化 scheme。
     *
     * ## ⚠️ 刻意**不做大小写转换**
     *
     * `IntentFilter.matchData` 是 `schemes.contains(scheme)` —— **精确比较、区分大小写**。
     * 若我方把 scheme 小写化后再 `addDataScheme`，而广播里写的是大写 scheme，
     * 则 **filter 就把它拦掉了**，`onReceive` 根本进不来
     * ⇒ 归一化不但没帮忙，还制造了「明明配了却收不到」。
     *
     * **原样注册、原样比较**：`IntentFilter` 是唯一判定点。
     */
    fun normalizeSchemes(raw: List<String>): List<String> =
        stripBlank(raw).filter { it != WILDCARD }

    /**
     * 运行时归一化 category。
     *
     * 与 [normalizeSchemes] 同理不做大小写转换（`matchCategories` 也是
     * `mCategories.contains(category)` 精确比较）。
     */
    fun normalizeCategories(raw: List<String>): List<String> =
        stripBlank(raw).filter { it != WILDCARD }

    /** [validateActions] 的判定结果。**纯枚举** —— 文案由模块层翻，纯函数层不认识 Android 资源。 */
    enum class ActionsValidation { OK, EMPTY, WILDCARD }

    /**
     * 编辑期校验。
     *
     * ⚠️ 判据建在 [stripBlank] 之上：**只剩空白/空串 = 空**。
     * 直接判 `raw.isEmpty()` 会漏掉「用户敲了几个空格」这种最常见的情形。
     *
     * ⚠️ **只要有任何一项是 [WILDCARD] 就报 WILDCARD**（哪怕同时还有合法项）——
     * 用户填 `"*"` 的意图是「通配」，而这个意图平台**做不到**；
     * 归一化时静默丢掉 `"*"` 会让他以为监听成功了，实际只监听了剩下的那一项。
     */
    fun validateActions(raw: List<String>): ActionsValidation {
        val nonBlank = stripBlank(raw)
        if (nonBlank.isEmpty()) return ActionsValidation.EMPTY
        if (nonBlank.any { it == WILDCARD }) return ActionsValidation.WILDCARD
        return ActionsValidation.OK
    }
}
