package com.chaomixian.vflow.ui.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.CardIconBitmap
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.TileManager
import com.chaomixian.vflow.core.workflow.TileSlot
import com.chaomixian.vflow.core.workflow.WorkflowIconValue
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.WorkflowVisuals
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import com.chaomixian.vflow.services.AccessibilityKeepAliveManager
import com.chaomixian.vflow.ui.main.MainActivity

/**
 * 两个磁贴池的**共用基类**：状态渲染（§4.7 状态表）+ 图标 + 兜底跳转。
 *
 * ⚠️⚠️ **本类不实现 `onClick`** —— 两个池的点击语义完全不同
 * （执行型「跑一次」/ 开关型「翻转 `isEnabled`」），且**各有一个只属于自己池的
 * 「越界兜底」（闸 3）**。把它放在基类里就得先判 kind 再分派，等于把两条语义
 * 混进一个函数；而分派的 `when` 一写错，表现是「开关型磁贴去执行工作流」，
 * **不报错**。故下沉到 [BaseExecuteTileService] / [BaseToggleTileService]。
 *
 * ⚠️⚠️ **`state` 完全由本类按 kind 决定，子类不得覆写** ——
 * §3 的核心结论：**高亮与否只由 `Tile.state` 决定**，与 `TOGGLEABLE_TILE` 元数据无关。
 * 执行型恒 `STATE_INACTIVE`（这就是用户要的「没有高亮」），开关型才读 `isEnabled` 做双态。
 * 子类若「顺手」把它改回读 `isEnabled`，执行型会**重新常亮**，而没有行为测试会红。
 */
abstract class BaseWorkflowTileService : TileService() {

    /**
     * 该磁贴的**绝对槽位**（执行池 0–19 / 开关池 20–39）。
     *
     * ⚠️ 执行池的 0..19 与 `WorkflowTileServiceN` 的 N **逐字对应**
     * （`WorkflowTileService0` 就是槽位 0），开关池的 N 则是**池内**槽号，
     * 绝对索引要加 [TileSlot.TOGGLE_INDEX_OFFSET]。这个换算由子类写死，
     * 不靠类名后缀去推 —— 两池的 0..19 **看起来一样**。
     */
    abstract fun getTileIndex(): Int

    /**
     * 该磁贴属于哪一池。
     *
     * ⚠️ 由两个子类**各自写死一个常量**，而不是 `TileSlot.kindOf(getTileIndex())` ——
     * 后者会让「类名叫 Toggle、索引却写 0」这种错配**自动消失**（推出来的 kind 跟着
     * 索引走），于是那个错配再也报不出来。现在它会在 [onStartListening] 里被
     * 一致性检查抓到并打日志。
     */
    abstract fun tileKind(): TileKind

    protected val tileManager: TileManager by lazy { TileManager(applicationContext) }
    protected val workflowManager: WorkflowManager by lazy { WorkflowManager(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        AccessibilityKeepAliveManager.onQuickSettingsPanelVisible(applicationContext)
        warnIfKindMismatch()
        updateTileState()
    }

    /** 池与索引的自洽性检查（只在真的错配时打日志，正常运行零噪声）。 */
    private fun warnIfKindMismatch() {
        val index = getTileIndex()
        val expected = TileSlot.kindOf(index)
        if (expected != tileKind()) {
            DebugLogger.e(
                TAG,
                "磁贴 kind 与槽位不一致：${javaClass.simpleName} 声明 ${tileKind()}，" +
                    "但槽位 $index 属于 $expected。状态与点击行为会按声明的那一池走，" +
                    "请检查 TileSlot 的偏移与 WorkflowTileServices 的索引。"
            )
        }
    }

    /**
     * §4.7 状态表。**六个格子逐条落实**，没有隐含分支。
     *
     * ⚠️ `subtitle` 与 `icon` **每一格都要显式设置**（包括设为 `null`）——
     * `Tile` 对象在两次 `onStartListening` 之间是**复用的**，只在新值非空时赋值
     * 会让「上一次的 subtitle」粘住不放（例如从越界态恢复后仍显示「请重新绑定」），
     * 而这是**纯视觉、无报错的**。
     */
    private fun updateTileState() {
        val qsTile = qsTile ?: return

        val tileIndex = getTileIndex()
        val kind = tileKind()
        val bound: WorkflowTile? = tileManager.getTile(tileIndex)
        val workflow: Workflow? = bound?.workflowId?.let { workflowManager.getWorkflow(it) }

        when {
            // ── 未绑定 / 工作流已被删：两池各自的名字 + 提示（§4.7 前两行）──
            workflow == null -> {
                qsTile.state = Tile.STATE_INACTIVE
                qsTile.label = TileSlot.displayName(kind, slotOf(tileIndex, kind))
                qsTile.subtitle = getString(R.string.tile_unbound_subtitle)
                applyFallbackIcon(qsTile)
            }

            // ── 越界态（§4.6 闸 3 的可见面）：唯一允许用 UNAVAILABLE 的一格 ──
            // ⚠️ `bound` 的 null 检查是**冗余的**（`workflow != null` 已保证
            //    `bound != null` 与 `bound.workflowId != null`），但保留它是为了
            //    让「越界判据的输入一定非空」在类型上成立 —— 去掉后要写 `bound!!`。
            bound != null && TileGate.isOutOfKind(bound, workflow) -> {
                qsTile.state = Tile.STATE_UNAVAILABLE
                qsTile.label = workflow.name
                qsTile.subtitle = getString(TileGate.outOfKindMessageRes(kind))
                applyFallbackIcon(qsTile)
            }

            else -> {
                qsTile.label = workflow.name
                qsTile.subtitle = subtitleForBound(kind, workflow)
                qsTile.state = stateForBound(kind, workflow)
                applyWorkflowIcon(qsTile, workflow)
            }
        }

        qsTile.updateTile()
    }

    /** 已绑定且未越界时的 state。**执行型恒 `INACTIVE`**（§3.2）。 */
    private fun stateForBound(kind: TileKind, workflow: Workflow): Int = when (kind) {
        // ⚠️⚠️ 不读 `isEnabled` —— 读它就会重新常亮，而那正是本次要修的
        TileKind.EXECUTE -> Tile.STATE_INACTIVE
        TileKind.TOGGLE -> if (workflow.isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    }

    /** 已绑定且未越界时的 subtitle。执行型**不显示**（§4.7 表里是「—」）。 */
    private fun subtitleForBound(kind: TileKind, workflow: Workflow): CharSequence? = when (kind) {
        TileKind.EXECUTE -> null
        TileKind.TOGGLE -> getString(
            if (workflow.isEnabled) R.string.tile_toggle_enabled else R.string.tile_toggle_disabled
        )
    }

    /**
     * 磁贴图标 = 工作流的 `cardIconRes`（§4.3 的三种形态，**两条回落**）。
     *
     * ⚠️⚠️ 三条「改错了不报错」的防线（§7 第 1/2/14 条）：
     * 1. `resolveIconDrawableResOrZero` 可能返回 **0**（老数据存了已下线的图标名），
     *    而 `Icon.createWithResource(context, 0)` **不抛异常、只显示空白** ⇒ 显式回落；
     * 2. 自定义图片**必须缩到 192px** 再交给 `Icon`（它要跨 Binder 给 SystemUI），
     *    否则一张 4K 照片直接撞 `TransactionTooLargeException` ⇒ 磁贴完全不更新；
     * 3. 解码失败返回 `null`（文件被删 / 换机后路径失效是常态），
     *    直接 `createWithBitmap(null)` 会 **NPE 崩 service** ⇒ 同样回落。
     */
    private fun applyWorkflowIcon(qsTile: Tile, workflow: Workflow) {
        val raw = workflow.cardIconRes

        if (WorkflowIconValue.isCustomImage(raw)) {
            val path = WorkflowIconValue.filePathOf(raw)
            // ⚠️⚠️ **必须包 try/catch**（不只是判 null）—— `BitmapFactory.decodeFile`
            //     的文档明确写着图片数据无法解码时**抛 `RuntimeException`**
            //     （已在单测里实测到同类行为：pure JVM 下它直接抛 "not mocked"）。
            //     一个**损坏的 PNG** 就能让 `onStartListening` 崩掉 ⇒ 磁贴永久空白，
            //     而 `TileService` 崩溃的表现是「这个磁贴坏掉了」，用户只能删掉重加。
            //     `loadCenterCroppedBitmap` 内部只对**裁剪/缩放**段做了 try/catch，
            //      解码段没有（那是既有实现的形态），所以这层保护必须在这里。
            val bitmap = if (path != null && CardIconBitmap.isUsableFile(path)) {
                try {
                    CardIconBitmap.loadCenterCroppedBitmap(path)
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "自定义图标解码失败，回落默认图标：$path", e)
                    null
                }
            } else {
                null
            }
            if (bitmap != null) {
                qsTile.icon = Icon.createWithBitmap(bitmap)
                return
            }
            // 自定义图片存在但解不出来 ⇒ **直接回落**。
            // ⚠️ 不能「拿 raw 再走一次资源名解析」—— raw 是路径，
            //    `getIdentifier("…/xxx.png")` 必然返回 0。
            applyFallbackIcon(qsTile)
            return
        }

        val iconRes = WorkflowVisuals.resolveIconDrawableResOrZero(applicationContext, raw)
        if (iconRes != 0) {
            qsTile.icon = Icon.createWithResource(applicationContext, iconRes)
        } else {
            applyFallbackIcon(qsTile)
        }
    }

    /** 未绑定 / 越界 / 图标不可用时的兜底图标。 */
    private fun applyFallbackIcon(qsTile: Tile) {
        qsTile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_workflows)
    }

    /** 绝对索引 → 池内槽号；越界时按该池起点回落（只影响显示名，不影响行为）。 */
    private fun slotOf(tileIndex: Int, kind: TileKind): Int =
        TileSlot.indexInKind(tileIndex) ?: (tileIndex - TileSlot.offsetOf(kind))

    /**
     * 取「已绑定且工作流仍在」的组合；任一不成立返回 `null`。
     *
     * ⚠️ 两个子类的 `onClick` 第一步都是它 —— 抽在这里避免两份各自判一遍
     * （漏一处的表现是「工作流已删，点磁贴崩在 `name` 上」）。
     */
    protected fun boundWorkflow(): Pair<Workflow, WorkflowTile>? {
        val tile = tileManager.getTile(getTileIndex()) ?: return null
        val workflowId = tile.workflowId ?: return null
        val workflow = workflowManager.getWorkflow(workflowId) ?: return null
        return workflow to tile
    }

    /**
     * 「未绑定 ⇒ 点一下打开 App」。
     *
     * ⚠️ 两个池的未绑定行为**完全一样**，故留在基类。
     * ⚠️ 刻意走 `PendingIntent.send()`（既有写法）而非 `startActivity` ——
     * `TileService` 在**面板收起后**才允许启动 Activity，直接 `startActivity`
     * 在部分 ROM 上会被拦（`Background activity start denied`）。
     */
    protected fun openApp() {
        val intent = MainActivity.createAppLaunchIntent(this).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            pendingIntent.send()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to start activity", e)
        }
    }

    protected fun toast(message: CharSequence) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    companion object {
        private const val TAG = "vFlowTile"
    }
}
