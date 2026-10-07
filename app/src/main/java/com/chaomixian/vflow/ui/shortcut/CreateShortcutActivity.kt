package com.chaomixian.vflow.ui.shortcut

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.locale.LocaleManager
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.chaomixian.vflow.ui.common.SearchableWorkflowDialog
import com.chaomixian.vflow.ui.common.ShortcutHelper
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.chaomixian.vflow.ui.common.WorkflowDialogItem

/**
 * 响应系统的 `android.intent.action.CREATE_SHORTCUT` —— **让第三方 App 取走本 App 的快捷方式**。
 *
 * ## 数据流（与 ShortX 的 `CreateShortcutActivity` 完全同构）
 *
 * ```
 * ① 第三方 App：queryIntentActivities(CREATE_SHORTCUT) 枚举候选
 * ② 用户选中 vFlow → 以本 Activity 为 component 发起 startActivityForResult
 * ③ 本 Activity 弹出工作流选择框（复用 SearchableWorkflowDialog，带搜索）
 * ④ 用户选中 → setResult(RESULT_OK, {EXTRA_SHORTCUT_INTENT, EXTRA_SHORTCUT_NAME}) → finish()
 * ⑤ 对方把那个 Intent **对象**存下来，事后重放
 * ```
 *
 * ⚠️ **带 `EXTRA_SHORTCUT_INTENT` 的结果会由系统回填成本 App 的 intent**
 * （`ShortcutManager.createShortcutResultIntent` 的既有语义）—— 这里刻意**不调它**，
 * 直接 `putExtra` 到自建的 Intent 上。ShortX 是先调它、再用两个 extra 覆盖
 * （那是为了带 icon）；vFlow 的图标走 [ShortcutHelper] 的既有链路，
 * 不需要在这里换图标，少一次系统调用少一层耦合。
 *
 * ## 与「添加到主屏幕」的关系
 *
 * `WorkflowListScreen` 的「添加到主屏幕」（[ShortcutHelper.requestPinnedShortcut]）
 * 走的是 **`requestPinShortcut`** —— 那条路只对**默认桌面**有效，第三方手势工具
 * 收不到。本 Activity 是**同一件事的另一半**：不推给桌面，而是**等别人来取**。
 * 两者共用同一条执行链路（[com.chaomixian.vflow.ui.common.ShortcutExecutorActivity]）
 * 与同一个显示名规则（[CreateShortcutSupport.shortcutLabelOf]）。
 *
 * ## 为什么要透明主题
 *
 * `CREATE_SHORTCUT` 是**两级跳**（第三方 App → 本页 → 回到第三方 App），
 * 用不透明主题会让整个屏幕闪一下。manifest 里声明
 * `Theme.vFlow.Transparent.Default`，运行期再 `setTheme` 拿动态取色
 * ——与 `OverlayUIActivity` 逐字同套路数（它在 `onCreate` 里做同一件事）。
 *
 * ## 一处如实记录的形态
 *
 * 本页是 `AppCompatActivity` 而非 `BaseActivity` —— 基类的 `onCreate` 会给窗口设
 * `setDecorFitsSystemWindows(false)`（沉浸式），而本页是个浮动对话框宿主，
 * 不需要也不该要沉浸式。语言与显示缩放照 `BaseActivity.attachBaseContext` 逐行复刻。
 */
class CreateShortcutActivity : AppCompatActivity() {

    private val workflowManager by lazy { WorkflowManager(this) }

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getLanguage(newBase)
        val localizedContext = LocaleManager.applyLanguage(newBase, languageCode)
        val context = AppearanceManager.applyDisplayScale(localizedContext)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ 必须在 super.onCreate 之前 —— 与 `BaseActivity.applyDynamicTheme` 同序
        setTheme(ThemeUtils.getThemeResId(this, transparent = true))
        super.onCreate(savedInstanceState)

        val pickable = CreateShortcutSupport.pickableWorkflows(workflowManager.getAllWorkflows())
        if (pickable.isEmpty()) {
            // 「没有可选的」是一条**正常**结果，但第三方 App 只会看到 RESULT_CANCELED
            // ⇒ 必须在本侧给出原因，否则用户以为是第三方工具坏了。
            Toast.makeText(this, R.string.create_shortcut_no_workflows, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        SearchableWorkflowDialog.show(
            context = this,
            titleResId = R.string.create_shortcut_title,
            items = pickable.map { WorkflowDialogItem(id = it.id, name = CreateShortcutSupport.shortcutLabelOf(it)) },
            onSelected = { item -> pickable.firstOrNull { it.id == item.id }?.let(::finishWithShortcut) },
            onCancelled = {
                // 用户取消：第三方 App 通过 RESULT_CANCELED 判断（Activity 默认结果码），
                // 这里不额外 setResult，只负责收尾。
                finish()
            }
        )
    }

    /**
     * ⚠️ `Intent.EXTRA_SHORTCUT_INTENT` / `EXTRA_SHORTCUT_NAME` 在新 SDK 里被标了
     * deprecated（官方推荐改用 `ShortcutManager` 那一套）—— 但**这里必须用它们**：
     * `CREATE_SHORTCUT` 是一条**跨 App 的既有协议**，调用方（ShortX 的
     * `C1477OoooooO.java:269`、各启动器）读的就是
     * `"android.intent.extra.shortcut.INTENT"` / `"…NAME"` 这两个键。
     * 换成新 API 会让对端**读不到任何东西**，且它是静默的（对方只看到「用户取消了」）。
     */
    @Suppress("DEPRECATION")
    private fun finishWithShortcut(workflow: Workflow) {
        val label = CreateShortcutSupport.shortcutLabelOf(workflow)
        val result = Intent()
            .putExtra(Intent.EXTRA_SHORTCUT_INTENT, ShortcutHelper.executionIntent(this, workflow))
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, label)

        DebugLogger.i(TAG, "已向调用方返回快捷方式：${workflow.name}（label=$label）")
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private companion object {
        const val TAG = "CreateShortcut"
    }
}
