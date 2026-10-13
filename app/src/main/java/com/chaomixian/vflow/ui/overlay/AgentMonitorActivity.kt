package com.chaomixian.vflow.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.chaomixian.vflow.ui.common.VFlowTheme

/**
 * fork：AI 运行监视器（**只读观察窗 + 运行控制**）。
 *
 * 为什么单开一个 Activity，而不是继续堆在悬浮窗那张卡片里：
 *  1. 悬浮窗只有 150×320dp，**放不下完整思考流**；
 *  2. Activity 能进系统**小窗/分屏**（manifest 里 `resizableActivity="true"`），
 *     用户可以把监视器缩到角落继续看；
 *  3. 数据全部来自进程内单例（[AgentRunMonitor] + `VirtualDisplayManager` 的预览帧流），
 *     所以 Activity 被销毁/重建**不丢状态**。
 *
 * ⚠️ 刻意**不**声明 `supportsPictureInPicture` —— 小窗完全交给系统手势/最近任务
 * （见 `docs/fork/chat-agent-enhancement-plan.md` 的取舍）。
 */
class AgentMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VFlowTheme {
                AgentMonitorContent()
            }
        }
    }
}
