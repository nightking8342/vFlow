package com.chaomixian.vflow.xposed

import android.os.Build

/**
 * hook 点的**集中登记处**，按 `Build.VERSION.SDK_INT` 分级。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.5（照 ShortX 的按 API 分级模式）。
 *
 * ## ⚠️ 为什么必须收敛在一处
 *
 * hook 点是「字符串类名 + 方法名 + 取值路径」的三元组，它们**编译期无法校验**：
 * 写错了只在运行期表现为 `ClassNotFoundException` 或「hook 上了但永远不命中」。
 * 散落各文件时，排查需要翻遍全仓；收敛在这里，出问题只需看一个文件
 * （§5.2 的「hook 点在新系统上不存在 ⇒ 功能不触发、无报错」）。
 *
 * ## ⚠️ 本文件运行在 system_server 里
 *
 * 只允许 `java.*` / `android.os.Build`（**纯常量类，无 Context 初始化**）/
 * `com.chaomixian.vflow.xposed.**`。
 */
object HookTargets {

    /**
     * 「Activity 已 resume」的 hook 点。
     *
     * **实测确证**（`P0-FINDINGS.md` §1）：存在、能挂上、真的被调用，
     * 且经 `ActivityRecord.forToken(IBinder)` 反查后**能拿到完整 Intent（含 extras）**——
     * 这正是前三条通道做不到、本通道存在的理由。
     */
    object ActivityResumed {

        /**
         * 类名候选，**按 API 从新到旧**。
         *
         * ⚠️⚠️ 必须是 `com.android.server.wm.*`。写成 `android.app.ActivityRecord`
         * 会得到 `ClassNotFoundException` —— 这是探针**实际踩过的坑**
         * （当时想当然以为在 `android.app` 下）。
         *
         * ⚠️ 加新候选时**只追加、不要重排**：匹配是「首个能 loadClass 成功的」，
         * 重排会改变在不同系统上的实际选中项。
         */
        val CLASS_CANDIDATES = listOf(
            // API 12+ —— 实测确证存在的就是这一个
            "com.android.server.wm.ActivityRecord",
            // API 9~11（历史形态，保留以供将来向下适配时使用）。
            // ⚠️ 本项目 minSdk 29，实际上走不到这里；写出来是为了让「本该在此处适配」
            //    这件事显式可见，而不是被默默忽略
            "com.android.server.am.ActivityRecord",
        )

        const val METHOD_NAME = "activityResumedLocked"

        /**
         * 方法签名：`static void activityResumedLocked(android.os.IBinder, boolean)`
         *
         * ⚠️⚠️ **它是 `static` 的，没有 `this`** ——
         * 所以**不能用 `chain.getThisObject()`**（恒为 null）。
         * 探针曾因此误判「hook 失败」。取值只能走 `chain.getArg(0)` + [forTokenMethod]。
         */
        const val ARG_COUNT = 2

        /**
         * 反查实例的静态方法名。
         *
         * ⚠️ 这是**唯一需要的反查路径**（探针验证过另一条「遍历活动列表」的兜底路径用不上）。
         * 签名：`static ActivityRecord forToken(android.os.IBinder)`。
         */
        const val FOR_TOKEN_METHOD = "forToken"
    }

    /**
     * 当前系统该用哪个 hook 点。
     *
     * ⚠️ 目前两种候选都指向同一套方法名，差异只在类名候选列表与加载顺序。
     * 保留分级结构是为了「加第 N 个系统版本适配」时改动面积最小。
     */
    fun activityResumedClassCandidates(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            ActivityResumed.CLASS_CANDIDATES[0],
        )
        else -> ActivityResumed.CLASS_CANDIDATES
    }
}
