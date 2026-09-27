// 文件: main/java/com/chaomixian/vflow/permissions/Permission.kt
package com.chaomixian.vflow.permissions

import android.content.Context
import android.os.Parcelable
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize

/**
 * 权限类型的枚举。
 * - RUNTIME: 标准的运行时权限，如存储、联系人等，通过请求码申请。
 * - SPECIAL: 特殊权限，需要用户跳转到系统设置页面手动开启，如无障碍服务、悬浮窗等。
 */
enum class PermissionType {
    RUNTIME,
    SPECIAL
}

/**
 * 权限的数据类模型。
 *
 * @param id 权限的唯一标识符，通常是 Android Manifest 中的权限字符串。
 * @param name 显示给用户的权限名称（用于国际化回退）。
 * @param description 解释为什么需要这个权限（用于国际化回退）。
 * @param type 权限的类型 (RUNTIME 或 SPECIAL)。
 * @param runtimePermissions 如果这是一个权限组，这里列出所有实际需要请求的运行时权限。
 * @param nameStringRes 权限名称的字符串资源 ID（用于国际化）。
 * @param descriptionStringRes 权限描述的字符串资源 ID（用于国际化）。
 */
@Parcelize
data class Permission(
    val id: String,
    val name: String,
    val description: String,
    val type: PermissionType,
    val runtimePermissions: List<String> = emptyList(),
    @param:StringRes val nameStringRes: Int? = null,
    @param:StringRes val descriptionStringRes: Int? = null,
    /**
     * 该权限的授予动作是否发生在 **vFlow 之外**（如 LSPosed / 第三方框架）。
     *
     * ## ⚠️⚠️ 为什么必须有这个字段
     *
     * `createRequestIntent()` 返回 `null` 有两种**完全不同**的含义：
     * - 「这是运行时权限，该走 `requestPermissions`」
     * - 「这个权限**没法在 App 内授予**，用户得去别的地方操作」
     *
     * 而两个 UI 入口（`PermissionActivity` / `OnboardingActivity`）
     * **都把 null 当成前者** —— 于是对 `XPOSED_HOOK` 这类权限，
     * 它们要么去 `requestPermissions`（弹不出对话框、系统静默拒绝），
     * 要么转去 `autoGrantPermission`（要 Shizuku/Root，且根本没有 shell 授予方式）。
     *
     * **表现是「点授予什么都不发生、也没有提示」** —— 又一个静默失效。
     * 本字段让 UI 能识别这种情况并给出**引导**。
     */
    val grantedExternally: Boolean = false
) : Parcelable {
    /**
     * 获取本地化的权限名称
     */
    fun getLocalizedName(context: Context): String {
        return if (nameStringRes != null) context.getString(nameStringRes) else name
    }

    /**
     * 获取本地化的权限描述
     */
    fun getLocalizedDescription(context: Context): String {
        return if (descriptionStringRes != null) context.getString(descriptionStringRes) else description
    }
}
