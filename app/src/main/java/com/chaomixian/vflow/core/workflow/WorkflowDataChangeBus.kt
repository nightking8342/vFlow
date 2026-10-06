package com.chaomixian.vflow.core.workflow

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「**工作流数据被 App 之外改掉了**」的进程内通知（fork 独有）。
 *
 * ## 为什么需要它
 *
 * 工作流列表页只在**两个**时机重新读盘：`ON_RESUME` 与 `isActive` 变化。
 * 而**下拉 QS 面板不会触发这两个**（Activity 既不重启、`isActive` 也没变）
 * ⇒ 用户从**开关型磁贴**改了 `isEnabled`，回到列表页看到的还是旧状态，
 * 必须切出去再切回来才刷新。
 *
 * ⚠️ 那正是「界面与实际不一致」里最难自查的一种：磁贴是对的、数据是对的、
 * 只有列表那一格是旧的。
 *
 * ## 为什么只由**磁贴**发布，而不是由 `WorkflowManager.saveWorkflow` 发布
 *
 * `saveWorkflow` 是**所有**写入路径的汇聚点（列表页自己的开关、编辑器、AI、
 * 导入……），在那里发布会让「列表页自己改自己」也绕一圈回来重新 `loadData()`。
 * 虽然 `loadData` 会 `cancel` 上一个 job、不会成环，但**每一次列表内开关都多付
 * 一整趟读盘 + 一次 `setLoading(true)`**（观感上是那一下多余的闪）。
 *
 * ⇒ 发布点刻意只放在**真正来自 App 之外**的入口（QS 面板的磁贴）。
 * 其余路径本来就在 App 内、它们的 UI 自己会刷。
 *
 * ⚠️ **进程内**即可，**不要**改成广播：磁贴 service 与 App 同进程
 * （manifest 未声明 `android:process`），而广播还多一层
 * `RECEIVER_EXPORTED` / `NOT_EXPORTED` 的坑（本仓库在数据卡切换上踩过 ——
 * 用错那个 flag 会**静默收不到**）。
 *
 * ⚠️ `extraBufferCapacity = 1` + `DROP_OLDEST`：这是个**信号**不是事件流，
 * 连发多次只该触发一次刷新；而 `tryEmit` 不能因为没订阅者就抛/挂。
 */
object WorkflowDataChangeBus {

    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 订阅它来刷新 UI。**不是** lifecycle-aware —— 由订阅方自己决定要不要在后台也刷。 */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * 通知「工作流数据在 App 之外被改动了」。
     *
     * ⚠️ **无订阅者时静默丢弃**（`tryEmit` 直接成功、值没人接）——
     * 那是**正确**行为：没有正在显示的列表就没有要刷的东西，
     * 而列表下次出现时会走它自己的 `ON_RESUME` / `isActive` 路径读一次盘。
     */
    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
