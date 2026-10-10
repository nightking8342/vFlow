package com.chaomixian.vflow.ui.shortcut

import com.chaomixian.vflow.core.workflow.model.Workflow
import java.text.Collator
import java.util.Locale

/**
 * 「为工作流创建快捷方式」的**纯函数层**（无 Android 依赖，可纯 JVM 单测）。
 *
 * ## 它是干什么的
 *
 * 响应系统的 `android.intent.action.CREATE_SHORTCUT`：第三方启动器 / 手势工具
 * （如 ShortX 的「应用快捷方式」）会 `queryIntentActivities(CREATE_SHORTCUT)`
 * 枚举候选 App，再以**本 App 的 Activity 为 component** 发起 `startActivityForResult`，
 * 让本 App 自己弹列表、把用户选中的那个目标以 **完整 Intent 对象**交回去
 * （`EXTRA_SHORTCUT_INTENT` / `EXTRA_SHORTCUT_NAME`），对方存下来事后重放。
 *
 * 对照 ShortX 的实现：它的响应方是 `CreateShortcutActivity`（label「指令快捷方式」），
 * 返回的 Intent 指向它自己的 `ActionShortcutActivity`。vFlow 的对应物是
 * [com.chaomixian.vflow.ui.common.ShortcutExecutorActivity] +
 * [com.chaomixian.vflow.ui.common.ShortcutExecutorActivity.ACTION_EXECUTE_WORKFLOW]。
 *
 * ## 可选项判据（用户 2026-10-07 定案）
 *
 * **只有一个条件：有「手动触发」触发器**（[Workflow.hasManualTrigger]）。
 *
 * ⚠️ 刻意**不是** `!hasAutoTriggers()`：判据是「**有手动触发器**」，一个同时挂着定时
 * 触发器的工作流**照样可以手动跑**，没有理由把它藏起来。同理，函数工作流只要有手动
 * 触发器也**照列**。
 *
 * ⚠️ **2026-10-10 起这条判据与磁贴执行池 [com.chaomixian.vflow.core.workflow.TileGate]
 * 逐字相同**（那次修订把执行池从 `!hasAutoTriggers()` 改成了 `hasManualTrigger()`，
 * 理由也是「执行 = 卡片上的 ▶ = 手动点火」）。两处**仍是各自独立的判据**、
 * 不得互相调用 —— 它们的语义相近但归属不同（一个产出快捷方式、一个绑定磁贴），
 * 将来任一侧要单独放宽时不该被另一侧绑住。
 *
 * ## 为什么抽成纯函数
 *
 * 判据与文案规则一旦写进 Activity 就只能靠真机验证。这里抽出来后，
 * 「哪些工作流会出现在列表里」「快捷方式显示什么名字」两件事可以逐格断言 ——
 * 而它们错了的表现都是**静默的**（列少了 / 名字不对，都不报错）。
 */
object CreateShortcutSupport {

    /**
     * 可作为快捷方式目标的工作流：**有手动触发器即可**，按名称排序。
     *
     * ⚠️ 排序用中文 Collator（与工作流列表 `WorkflowListRoute` 同一套），
     * 否则「阿/波/次」会按 UTF-16 码位乱序，而第三方 App 的列表照抄我们给的顺序。
     */
    fun pickableWorkflows(all: List<Workflow>): List<Workflow> =
        all.filter { it.hasManualTrigger() }
            .sortedWith { a, b -> CHINESE_COLLATOR.compare(a.name, b.name) }

    /**
     * 快捷方式显示名：**自定义名优先，否则工作流名**。
     *
     * ⚠️ 与 `ShortcutHelper.createShortcutInfo` 的 `shortLabel` / `longLabel`
     * **必须同一套规则** —— 同一个工作流在「长按桌面图标」与「第三方手势工具」
     * 两处显示不同名字，会被当成两个不同的东西。故那边也改调本函数（不再各写一份）。
     *
     * ⚠️ 空串按「未设置」处理（`takeIf { isNotEmpty() }`）：`ShortcutConfigActivity`
     * 保存时已把空白转成 null，但存量数据 / 外部导入的 `signalName` 可能是空串，
     * 直接用会让第三方列表里出现一格空白。
     */
    fun shortcutLabelOf(workflow: Workflow): String =
        workflow.shortcutName?.takeIf { it.isNotEmpty() } ?: workflow.name

    private val CHINESE_COLLATOR: Collator =
        Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
}
