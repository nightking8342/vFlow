package com.chaomixian.vflow.xposed.wire

/**
 * §3.6 **结果大小契约**的框架层原语。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.6。
 *
 * ## ⚠️⚠️ 为什么必须在框架层统一，而不是各 handler 自己管
 *
 * 与 §5.3（分级背压）**同一条理由**：各写各的必然出现
 * 「有的截了有的没截、有的带标志有的不带」，
 * 而失败形态是**静默的数据缺失**。
 *
 * ## 为什么 256 KiB 这个量级（先把「1MB」说清楚）
 *
 * 「Binder 1MB 上限」**不是「每个事务 1MB」**，它比这个说法脏得多：
 *
 * | 事实 | 值 |
 * |---|---|
 * | 谁定的 | AOSP `libbinder` 的 `ProcessState.cpp`：进程 `mmap` `/dev/binder` 的大小 |
 * | 归谁 | ⚠️ **接收方进程**，且**该进程内所有在途事务共享这一份** |
 * | **oneway 走哪半边** | ⚠️⚠️ 异步事务用**单独记账的一半**（内核 `free_async_space = buffer_size / 2`） |
 * | 实际边界 | 低于 1016 KiB，还要扣 interface token / 长度前缀 / 对齐填充 / 对方已占用的部分 |
 * | 能不能调 | ⚠️ **不是应用可配项**（`initWithMmapSize` 只在 native、且须在任何 binder 调用前） |
 *
 * ⇒ 半缓冲 ≈ 508 KiB，而 [DEFAULT_MAX_RESULT_BYTES] 取 256 KiB 是**留足余量**
 *（那半边是共享的，同一条连接上可能有并发的 `report` 事件在飞）。
 *
 * ## ⚠️ oneway 超限 = **静默丢弃**
 *
 * | | 超限时的表现 |
 * |---|---|
 * | 同步调用 | 抛异常（虽然可能被误报成 `DeadObjectException`） |
 * | **oneway**（我们的 `resolve` 与 `report`） | ⚠️ **静默丢弃** —— 异步缓冲耗尽时事务被直接扔掉，**不通知发送方** |
 *
 * ⇒ **不能等 binder 报错**（报错根本回不来），必须在**产出阶段**就截断。
 *
 * ## ⚠️ 截断发生在「产出」而不是「序列化后」
 *
 * handler 组结果时就按上限收，**不是拼完大对象再砍** ——
 * 后者内存已经占过，且跑在 **system_server** 里。
 *
 * ## 依赖白名单
 *
 * 本文件跑在 system_server 里，只允许 `java.*` / `kotlin.*`（连 `org.json` 都不需要）。
 * 由 `WireLayerPurityTest` 源码扫描锁住。
 */
object ResultBudget {

    /**
     * 默认响应上限：**256 KiB**（§3.6 契约 1 的建议量级）。
     *
     * ⚠️ 这是**上限**，不是目标 —— 单个 capability 可以声明更小的值
     *（`Capability.maxResultBytes`）。声明得越小越安全。
     */
    const val DEFAULT_MAX_RESULT_BYTES = 256 * 1024

    /**
     * UTF-8 字节数 —— **全仓唯一的口径处**。
     *
     * ## ⚠️⚠️ 为什么必须收敛成一处（这是本文件存在的直接理由之一）
     *
     * 本仓库曾长期按**字符**计预算（`String.length`），而契约按**字节**立：
     *
     * ```
     * 全 ASCII（1 字节/字符）  ⇒ 字符数 ≈ 字节数      ⇒ 看着没问题
     * 全 CJK（3 字节/字符）    ⇒ 字节数是字符数的 3 倍 ⇒ ❌ 静默超限
     * 叠加 emoji（4 字节/字符）⇒ 更糟
     * ```
     *
     * 表现是「**打开某些 App 不触发、换一个就正常**」——
     * 正是最难被当成 bug 上报的那类。见 `ActivityPayload` 的对应修正。
     *
     * ⚠️ 用 `toByteArray(Charsets.UTF_8)` 而不是 ×3 估算：
     * 估算法在纯 ASCII 时会**大幅高估**（把本该通过的数据截掉），
     * 而真实的编码成本本来就是可精确计算的 —— 没有理由去估。
     */
    fun byteSizeOf(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * 按字节上限**收集**元素（§3.6 契约 2/3/4 的可复用原语）。
     *
     * ## 语义（三个契约的落实）
     *
     * | 契约 | 落实 |
     * |---|---|
     * | 2 主动截断 | 累加到超限就停，**不依赖 binder 报错** |
     * | 3 截断带标志位 | 返回 [Budgeted.truncated] |
     * | 4 需要全量走分页 | 返回 [Budgeted.nextCursor]（可喂给下一次请求的 `cursor`） |
     *
     * ## ⚠️⚠️ 单元素超限的特例：**仍然收下这一个**
     *
     * 若某个元素单独就超上限，**收下它并置 truncated**，而不是「一个都不收」。
     *
     * 理由：不收的话 `nextCursor` 会永远停在同一个位置 ⇒
     * **分页死循环**（调用方拿着一模一样的 cursor 反复请求，每次都得到空结果）。
     * 而收下它至少让调用方能推进到下一步，并且 `truncated=true` 已经告诉了它
     * 「这条不完整」。
     *
     * ⚠️ 这**不是**「放宽上限」—— 上限仍然是 [maxBytes]，
     * 单元素超限本身就是**实现缺陷或数据异常**（对应
     * [CapabilityErrorCode.PAYLOAD_TOO_LARGE] 的「不是用户能处理的失败」），
     * 调用方应当把它当异常上报，而不是当成正常截断。
     *
     * @param items 待收集元素（**必须是完整列表**，本函数不做 IO）
     * @param maxBytes 上限（字节）
     * @param sizeOf 单个元素的 UTF-8 字节数。⚠️ 用 [byteSizeOf] 而不是 `String.length`
     * @param startIndex 起始下标（分页用；上一次的 `nextCursor`）
     */
    fun <T> collectWithin(
        items: List<T>,
        maxBytes: Int,
        sizeOf: (T) -> Int,
        startIndex: Int = 0,
    ): Budgeted<T> {
        // ⚠️ 上限必须是正数 —— 传 0 或负数时下面 `used + size > maxBytes` 恒真，
        // 会得到「收下第一个元素」的结果，与语义不符。这里显式钳到至少 1，
        // 让「上限非法」不至于变成另一个静默行为。
        val limit = maxBytes.coerceAtLeast(1)
        val from = startIndex.coerceAtLeast(0)
        if (from >= items.size) {
            return Budgeted(emptyList(), truncated = false, nextCursor = null)
        }

        val collected = ArrayList<T>()
        var used = 0
        var index = from

        while (index < items.size) {
            val item = items[index]
            // ⚠️ sizeOf 由调用方给出，可能抛（如自定义对象的序列化）。
            // 抛了就当「这个元素无法计量」⇒ 收下并标记截断（同单元素超限的处理）
            val size = try {
                sizeOf(item)
            } catch (_: Throwable) {
                collected.add(item)
                index++
                return Budgeted(collected, truncated = true, nextCursor = index)
            }

            if (collected.isNotEmpty() && used + size > limit) {
                // 装不下了 ⇒ 停在这里，cursor 指向**本条**（下次从它开始）
                return Budgeted(collected, truncated = true, nextCursor = index)
            }

            collected.add(item)
            used += size
            index++

            // 单元素就超限 ⇒ 收下这一个后立刻停（否则会继续把后面的也塞进去）
            if (used > limit) {
                return Budgeted(collected, truncated = true, nextCursor = index)
            }
        }

        // 走完了全部元素 ⇒ 没有截断，cursor 为 null（§3.6 契约 4：全量已取完）
        return Budgeted(collected, truncated = false, nextCursor = null)
    }
}

/**
 * [ResultBudget.collectWithin] 的结果。
 *
 * @param items 已收下的元素
 * @param truncated 是否发生过截断。⚠️ **必须传给下游** ——
 *   少了几项时用户不能误以为「本来就没有」（照 `ActivityPayload.truncated` 的先例）
 * @param nextCursor 截断时给下一批的**原始列表下标**；未截断为 null
 */
data class Budgeted<T>(
    val items: List<T>,
    val truncated: Boolean,
    val nextCursor: Int?,
)
