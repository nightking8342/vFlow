// 文件：main/java/com/chaomixian/vflow/core/workflow/model/FunctionSignatureDefaults.kt
// 描述：函数工作流「直接执行」时的参数种子派生。
//      这是 fork 新增文件（上游无此文件），配合 docs/fork/function-workflow.md §9.1。
//
// 背景：函数工作流的默认值此前只有**一个**消费点——CallFunctionModule.execute 在调用方
// 未传参时逐参数回退到默认值。而「直接执行」（列表页播放按钮 / 触发器 / 快捷方式 /
// 悬浮窗 / 编辑器内执行）走的是 WorkflowExecutor.execute 这个主入口，那里的
// namedVariables 是空表，于是工作流内部所有 {{vars.<参数名>}} 都解析成空值，
// 表现为「明明配了默认值，单独跑却报错」。

package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VObjectFactory

/**
 * 函数签名 → 命名变量种子的纯函数层。
 * 与 [FunctionSignatureHelper]（保存期的返回值推导）分开，因为两者的调用时机与职责都不同：
 * 这里是**执行期**、处理的是**入参**。
 */
object FunctionSignatureDefaults {

    /**
     * 派生「直接执行」函数工作流时的命名变量初始种子。
     *
     * 只收纳**声明了默认值**的参数：
     * - 无默认值的参数不放进表里。执行期 `namedVariables[name]` 取到 null 后经
     *   `VObjectFactory.from(null)` 即为 `VNull`，与显式种入 `VNull` 等价，故不必写。
     * - 保持「表里没有 = 该参数未赋值」这一可判定的状态，便于将来区分「声明了空默认值」
     *   与「根本没声明默认值」。
     *
     * 非函数工作流（`functionSignature == null`）返回空表——这正是普通工作流的既有行为，
     * 调用方无需分支。
     *
     * @param signature 工作流的函数签名；普通工作流传 null。
     * @return 参数名 → 默认值 VObject 的表。
     */
    fun seedNamedVariables(signature: FunctionSignature?): Map<String, VObject> {
        val params = signature?.params ?: return emptyMap()
        return params.mapNotNull { param ->
            param.defaultValue?.let { param.name to VObjectFactory.from(it) }
        }.toMap()
    }
}
