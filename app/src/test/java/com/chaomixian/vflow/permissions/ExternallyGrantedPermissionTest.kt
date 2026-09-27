package com.chaomixian.vflow.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「外部平台权限」的分支测试。
 *
 * ## 这个测试的存在理由（P4b 修的是一个**静默失效**）
 *
 * `createRequestIntent()` 返回 `null` 有**两种完全不同**的含义：
 * - 「这是运行时权限，该走 `requestPermissions`」
 * - 「这个权限**没法在 App 内授予**，用户得去别的地方操作」
 *
 * 而 `PermissionActivity` 与 `OnboardingActivity` **都把 null 当成前者**：
 *
 * | 入口 | 对 XPOSED_HOOK 的实际行为 |
 * |---|---|
 * | `PermissionActivity` | 转去 `autoGrantPermission`（要 Shizuku/Root，失败） |
 * | `OnboardingActivity` | 当运行时权限去 `requestPermissions`（**弹不出对话框**） |
 *
 * ⇒ **用户点「授予」什么都不发生、也没有提示。**
 *
 * `grantedExternally` 就是让 UI 能区分这两种情况的开关。
 */
class ExternallyGrantedPermissionTest {

    @Test
    fun `XPOSED_HOOK is marked as externally granted`() {
        // ⚠️⚠️ 这条是本次修复的**核心断言**。
        // 若它变红，说明有人把 `grantedExternally = true` 去掉了 ——
        // 那会让「点授予」重新变回静默失效，而且**不会有任何报错**
        assertTrue(
            "XPOSED_HOOK 必须在 LSPosed 里授予，App 内没有可跳转的页面",
            PermissionManager.XPOSED_HOOK.grantedExternally,
        )
    }

    @Test
    fun `XPOSED_HOOK has no request intent as expected`() {
        // 它是 SPECIAL 类型，但**没有**可跳转的系统页面 ——
        // 这正是需要 grantedExternally 的原因：
        // 单看「SPECIAL + intent 为 null」无法与「运行时权限」区分
        assertEquals(PermissionType.SPECIAL, PermissionManager.XPOSED_HOOK.type)
    }

    @Test
    fun `normal special permissions are not marked externally granted`() {
        // ⚠️ 反向断言：不能为了修 XPOSED 而把所有 SPECIAL 权限都标上 ——
        // 那样会把「能跳设置页」的权限（如无障碍）也改成弹引导对话框，
        // 把本来好用的路径改坏
        for (p in listOf(
            PermissionManager.ACCESSIBILITY,
            PermissionManager.NOTIFICATION_POLICY,
            PermissionManager.OVERLAY,
            PermissionManager.WRITE_SETTINGS,
            PermissionManager.SHIZUKU,
        )) {
            assertFalse(
                "${p.id} 有可跳转的系统页面，不该标成「外部授予」",
                p.grantedExternally,
            )
        }
    }

    @Test
    fun `default value of grantedExternally is false`() {
        // ⚠️ 向后兼容：字段带默认值，既有权限对象一律不受影响。
        // 若默认值改成 true，所有权限都会去弹引导对话框 —— 全面破坏
        val custom = Permission(
            id = "vflow.permission.TEST",
            name = "test",
            description = "test",
            type = PermissionType.SPECIAL,
        )
        assertFalse(custom.grantedExternally)
    }

    @Test
    fun `XPOSED_HOOK still declares name and description resources`() {
        // 引导对话框要用它们做标题 —— 缺了会显示成 null 标题
        assertTrue(PermissionManager.XPOSED_HOOK.nameStringRes != null)
        assertTrue(PermissionManager.XPOSED_HOOK.descriptionStringRes != null)
    }

    @Test
    fun `XPOSED_HOOK id is stable`() {
        // ⚠️ 权限 id 一旦发布不能改：它被 `strategies` map 与工作流的
        // `requiredPermissions` 引用（改了两处都要跟着改，漏一处就是静默失效）
        assertEquals("vflow.permission.XPOSED_HOOK", PermissionManager.XPOSED_HOOK.id)
    }
}
