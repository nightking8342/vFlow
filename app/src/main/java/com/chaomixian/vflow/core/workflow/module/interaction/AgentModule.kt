// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentModule.kt
package com.chaomixian.vflow.core.workflow.module.interaction

import android.content.ContentValues.TAG
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.ai.AiModelConfigResolution
import com.chaomixian.vflow.core.ai.AiModelConfigResolver
import com.chaomixian.vflow.core.ai.ManualAiConfig
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.*
import com.chaomixian.vflow.core.utils.VirtualDisplayManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.ui.chat.AgentLoopRequest
import com.chaomixian.vflow.ui.chat.AgentObservation
import com.chaomixian.vflow.ui.chat.AgentObservationProvider
import com.chaomixian.vflow.ui.chat.AgentTask
import com.chaomixian.vflow.ui.chat.AgentToolRunner
import com.chaomixian.vflow.ui.chat.ChatAgentArtifactStore
import com.chaomixian.vflow.ui.chat.ChatAgentEngine
import com.chaomixian.vflow.ui.chat.ChatAgentToolDefinition
import com.chaomixian.vflow.ui.chat.ChatAgentToolRiskLevel
import com.chaomixian.vflow.ui.chat.ChatAgentToolUsageScope
import com.chaomixian.vflow.ui.chat.ChatCompletionClient
import com.chaomixian.vflow.ui.chat.ChatImageAttachment
import com.chaomixian.vflow.ui.chat.ChatImageSource
import com.chaomixian.vflow.ui.chat.ChatPreparedToolBatch
import com.chaomixian.vflow.ui.chat.ChatPreparedToolItem
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import com.chaomixian.vflow.ui.chat.ChatPresetRepository
import com.chaomixian.vflow.ui.chat.ChatToolCall
import com.chaomixian.vflow.ui.chat.ChatToolResult
import com.chaomixian.vflow.ui.chat.ChatToolResultStatus
import com.chaomixian.vflow.ui.chat.ObservationMode
import com.chaomixian.vflow.ui.chat.WorkflowAgentHost
import com.chaomixian.vflow.ui.overlay.AgentOverlayManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal fun parseAgentToolArguments(argsStr: String): Map<String, Any?> {
    val root = runCatching { JsonParser.parseString(argsStr) }.getOrNull() ?: return emptyMap()
    if (!root.isJsonObject) return emptyMap()

    return root.asJsonObject.entrySet().associate { (key, value) ->
        key to normalizeAgentToolArgument(value)
    }
}

private fun normalizeAgentToolArgument(value: JsonElement): Any? {
    return when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate { (key, nestedValue) ->
            key to normalizeAgentToolArgument(nestedValue)
        }
        value.isJsonArray -> value.asJsonArray.map { normalizeAgentToolArgument(it) }
        value.isJsonPrimitive -> {
            val primitive = value.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber
                else -> primitive.asString
            }
        }
        else -> null
    }
}

/** `AgentModule` 手动档的采样温度：**现状不发送该字段**，1.0 是「不指定」的等价取值。 */
internal const val AGENT_MODULE_TEMPERATURE: Double = 1.0

/**
 * **归一化坐标 → 真实像素**（现状 `norm / 1000.0 * extent`，逐字保留）。
 *
 * ⚠️ 坐标语义是「归一化 0-1000」（不是批 2 的 `ChatAgentCoordinates` 那套 0-999 口径）——
 * 工作流宿主的 `AgentModule` 一直用 1000 分母，本批**不改**（改了会让既有步骤点偏）。
 */
internal fun agentNormalizedToPixel(norm: Int, extent: Int): Int = (norm / 1000.0 * extent).toInt()

/**
 * 思考段的**包裹标签**（与 `ChatReplyNormalizer` 认的标签、以及提示词里的契约一致）。
 *
 * ⚠️ 用字符串拼接构造（而不是直接写成一个字面量）：见交付说明的「工具链吞标签」记录。
 */
private const val THINK_OPEN_TAG: String = "<" + "think" + ">"
private const val THINK_CLOSE_TAG: String = "<" + "/think" + ">"

/**
 * **本模块自己的工具表**（中性形态，供引擎下发；fork：AI 能力统一批 4 的 T6）。
 *
 * 8 个既有工具的**名字 / 描述 / 参数 schema 逐字保留**（线格式与改造前手写的
 * `getNativeToolsSchema()` 逐字段相同）；**新增 3 个**（§1.8.5 的 D-a / D-b）：
 * `long_press` / `double_tap` / `swipe`，坐标一律**归一化 0-1000**。
 *
 * ⚠️ 本表**天然不含**工作流读写类工具 ⇒ 工作流宿主白名单过滤对它是**恒等**的。
 */
internal fun agentModuleToolDefinitions(): List<ChatAgentToolDefinition> = listOf(
    agentToolDefinition(
        name = "click_point",
        description = "Tap at specific NORMALIZED coordinates (0-1000). x=0 is left, x=1000 is right. y=0 is top, y=1000 is bottom.",
        parametersJson = """{"type":"object","properties":{"x":{"type":"integer","description":"Normalized X coordinate (0-1000)"},"y":{"type":"integer","description":"Normalized Y coordinate (0-1000)"}},"required":["x","y"]}""",
    ),
    agentToolDefinition(
        name = "click_element",
        description = "Click an element by exact text. Only use this if visual coordinates are impossible to determine.",
        parametersJson = """{"type":"object","properties":{"target":{"type":"string","description":"Exact text or ID."}},"required":["target"]}""",
    ),
    agentToolDefinition(
        name = "input_text",
        description = "Input text into focused field.",
        parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
    ),
    agentToolDefinition(
        name = "scroll",
        description = "Scroll the view. 'down' means going further down (swiping up).",
        parametersJson = """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down","left","right"]}},"required":["direction"]}""",
    ),
    agentToolDefinition(
        name = "press_key",
        description = "System keys.",
        parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["back","home","recents"]}},"required":["action"]}""",
    ),
    agentToolDefinition(
        name = "launch_app",
        description = "Launch an app by name.",
        parametersJson = """{"type":"object","properties":{"app_name":{"type":"string","description":"Exact app name (e.g. 'WeChat')"}},"required":["app_name"]}""",
    ),
    agentToolDefinition(
        name = "wait",
        description = "Wait for a specific amount of time (seconds).",
        parametersJson = """{"type":"object","properties":{"seconds":{"type":"integer"}},"required":["seconds"]}""",
    ),
    agentToolDefinition(
        name = "long_press",
        description = "Long press at specific NORMALIZED coordinates (0-1000). Use it to open context menus, select text, or trigger long-press interactions. x=0 is left, x=1000 is right. y=0 is top, y=1000 is bottom.",
        parametersJson = """{"type":"object","properties":{"x":{"type":"integer","description":"Normalized X coordinate (0-1000)"},"y":{"type":"integer","description":"Normalized Y coordinate (0-1000)"}},"required":["x","y"]}""",
    ),
    agentToolDefinition(
        name = "double_tap",
        description = "Double tap at specific NORMALIZED coordinates (0-1000). Use it to zoom, select text, or open an item. x=0 is left, x=1000 is right. y=0 is top, y=1000 is bottom.",
        parametersJson = """{"type":"object","properties":{"x":{"type":"integer","description":"Normalized X coordinate (0-1000)"},"y":{"type":"integer","description":"Normalized Y coordinate (0-1000)"}},"required":["x","y"]}""",
    ),
    agentToolDefinition(
        name = "swipe",
        description = "Swipe from one point to another using NORMALIZED coordinates (0-1000). Use it to scroll content, switch pages, or pull down the notification shade. All four values are normalized 0-1000 (0,0 is top-left; 1000,1000 is bottom-right).",
        parametersJson = """{"type":"object","properties":{"start_x":{"type":"integer","description":"Normalized start X (0-1000)"},"start_y":{"type":"integer","description":"Normalized start Y (0-1000)"},"end_x":{"type":"integer","description":"Normalized end X (0-1000)"},"end_y":{"type":"integer","description":"Normalized end Y (0-1000)"}},"required":["start_x","start_y","end_x","end_y"]}""",
    ),
    agentToolDefinition(
        name = "finish_task",
        description = "Call this when goal is achieved.",
        parametersJson = """{"type":"object","properties":{"result":{"type":"string"},"success":{"type":"boolean"}},"required":["result","success"]}""",
    ),
)

private fun agentToolDefinition(
    name: String,
    description: String,
    parametersJson: String,
): ChatAgentToolDefinition = ChatAgentToolDefinition(
    name = name,
    title = name,
    description = description,
    // ⚠️ 合成的 moduleId：本模块自己的 runner **不查注册表**，这两个字段只为满足类型。
    moduleId = "vflow.ai.agent.$name",
    moduleDisplayName = "AI 智能体 · $name",
    inputSchema = Json.parseToJsonElement(parametersJson).jsonObject,
    permissionNames = emptyList(),
    // 工作流宿主的 requestApproval 恒 Approved（D12）⇒ 风险等级只用于批级表达。
    riskLevel = ChatAgentToolRiskLevel.STANDARD,
    usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
)

/**
 * 提示词**协议段**（`AgentModule` 现有提示词里**去掉 19 条规则**的那部分）。
 *
 * ⚠️ 19 条规则**不在这里** —— 它们是 [WORKFLOW_RULES]（`ui/chat/AgentPrompts.kt`）的
 * **唯一来源**，由 `WorkflowModulePromptPlan` 拼在协议段**之后**（后 = 优先级更高，D13）。
 * `# User Goal` 同样由 plan 拼（引擎把 `instruction` 交过去）。
 *
 * 含**每次执行现算**的内容（日期 / 时间 / RECENT APPS）⇒ 必须由调用方传进来。
 */
internal fun agentModuleProtocolSection(
    dateString: String,
    timeString: String,
    recentApps: String,
): String = """
            # SETUP
            You are a professional Android operation agent assistant that can fulfill the user's high-level instructions. Given a screenshot of the Android interface at each step, you first analyze the situation, then plan the best course of action using Python-style pseudo-code.

            Current Date: $dateString
            Current Time: $timeString

            Your response format must be structured as follows:

            Think first: Use <think>...</think> to analyze the current screen, identify key elements, and determine the most efficient action.
            
            Your output should STRICTLY follow the format:
            <think>
            [Your thought]
            </think>
            
            # RECENTLY USED APPS (Context):
                The user has used the following apps in the last 30 days (Sorted by usage frequency). 
                **PRIORITIZE these apps** when the user's request is vague (e.g., "play music" -> pick the music app **which is used most** from this list).
                $recentApps
                        
            # CRITICAL INSTRUCTION: TOOL USAGE
            **You MUST use the native Function Calling mechanism.**
            - **DO NOT** output Markdown code blocks or XML tags for tools.
            - **DO NOT** just describe actions.
            - **First** `<think>...</think>`, **Then** `Tool Call`.
            
            # MANDATORY PROTOCOL:
            1. **LAUNCH APP FIRST**: If task requires a specific app (e.g. "WeChat") and it's not open, CALL `launch_app`.
            2. **ONE STEP AT A TIME**: ONE tool call per turn.
            3. **VISUAL PRIORITY**: Use `click_point(x, y)` with normalized coordinates (0-1000) from the screenshot.
            4. **VERIFY SUCCESS (CRITICAL)**: Before taking the next step, **CHECK the screen state** (Screenshot/XML) to confirm the previous action worked. 
               - Did the screen change? 
               - Did the text appear in the box?
               - **If NOT, DO NOT proceed. RETRY with a different method.**
            5. **GESTURES**: `long_press(x, y)` / `double_tap(x, y)` / `swipe(start_x, start_y, end_x, end_y)` — every coordinate is normalized (0-1000). When the target is off-screen, use `swipe` (rules 5 / 12 / 15) instead of repeating the same click.
            
            # MOBILE UI COMMON SENSE (CRITICAL):
            1. **Search Hints are NOT Content**: Text inside search bars (e.g., "Search...", "请输入", "Type here") is usually a **placeholder**. You do **NOT** need to delete it. Just click the field and call `input_text`.
            2. **Popups**: If a permission popup or ad appears, close or allow it first.
            
            # INPUT TEXT PROTOCOL (STRICT):
            1. **CLICK FIRST**: You CANNOT input text into a field that is not focused. **Step 1: Click the input field.**
            2. **WAIT & VERIFY**: Wait for the next turn. Look for visual signs of focus (cursor, caret, keyboard appearing) or check if `focused="true"` in XML.
            3. **THEN INPUT**: Only call `input_text` AFTER you have confirmed the field is focused in the current turn.
            
            # HYBRID PERCEPTION STRATEGY (VISION + XML):
            You have two inputs: **Screenshot (Vision)** and **UI Hierarchy (XML)**. You MUST combine them:
            1. **Vision First**: Locate the target element visually on the Screenshot to get approximate coordinates.
            2. **XML Verification (CRITICAL)**: **ALWAYS** look up the corresponding node in the XML using the visual coordinates.
               - Match the visual location with the `bounds="[l,t][r,b]"` in XML.
               - **CONFIRM** the element type: Is it an `EditText`? A `Button`? Or just `TextView` (label)?
               - **READ** the exact `text` or `id` from XML. Visual OCR can be wrong; XML is the ground truth for text content.
            3. **Action Selection**:
               - If the element has a unique `text` or `id` in XML, prefer `click_element(target="...")`. It is more robust than coordinates.
               - If it's an icon with no text/id, use `click_point(x,y)` with normalized coordinates.
               
            # REASONING GUIDE:
            - **Bad Reasoning**: "I see the search bar. I will call `input_text('hello')` immediately." (Wrong! It might not be focused).
            - **Good Reasoning (Turn 1)**: "I see the search bar. I must focus it first. I will call `click_point(500, 100)`."
            - **Good Reasoning (Turn 2)**: "I see the keyboard is now visible / the field cursor is blinking. Now it is safe to call `input_text('hello')`."
            - **Verification**: 
              - "I tried clicking 'Submit', but the screen is the same. The click might have failed. I will try `click_element` or adjust coordinates."
              
            # THINKING PROTOCOL:
                Your output format:
                <think>
                1. Observation: "I see a 'Search' bar at top."
                2. Cross-Check: "In XML, I found a node <EditText text='Search...' bounds='[100,50][900,150]' editable='true' /> matching that location."
                3. Reasoning: "The field is editable. I need to click it first to focus."
                4. Plan: "Call click_element('Search')."
                </think>
""".trimIndent()

/** 死循环检测的一条动作记录（名字 + 原始参数，与改造前 `LastAction` 逐字相同）。 */
internal class LastAction(val name: String, val args: String)

/**
 * **模块自己的工具执行器**（引擎的 [AgentToolRunner] 接缝；fork：AI 能力统一批 4 的 T6）。
 *
 * 为什么**不**改用 chat 的 native helper（§3.1 的三条理由，可验证）：
 * 1. chat helper 里**没有** `double_tap`（补它要改禁改文件 `ChatAgentNativeTooling.kt`）；
 * 2. native helper 的 `display_id` 取自**工具参数**（默认 0），而本模块是把
 *    `_target_display_id` 注入 `ExecutionContext` 交给 `AgentTools` ⇒ 改用它会让
 *    虚拟屏模式下的点击落到**主屏**（静默错屏）；
 * 3. `call_module` 的执行上下文不带 `_target_display_id` ⇒ 同样错屏，且修不了。
 *
 * 保留的行为：动作前 `awaitState()` 闸门、死循环检测（`repeatCount` / `threshold`）、
 * 未知工具报错文案、悬浮窗动作状态文案、`finish_task` 拦截。
 */
internal class AgentModuleToolRunner(
    private val tools: AgentTools,
    private val overlay: AgentOverlayManager,
    private val appContext: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onProgress: suspend (ProgressUpdate) -> Unit,
    private val requiredPermissions: List<Permission>,
    private val onFinishTask: (text: String, success: Boolean) -> Unit,
) : AgentToolRunner {

    private var preparedCalls: List<ChatToolCall> = emptyList()
    private var lastAction: LastAction? = null
    private var repeatCount = 0

    override fun prepare(
        toolCalls: List<ChatToolCall>,
        artifacts: ChatAgentArtifactStore,
    ): ChatPreparedToolBatch {
        preparedCalls = toolCalls
        return ChatPreparedToolBatch(
            // ⚠️ `ChatPreparedToolBatch` 没有独立的 calls 字段 ⇒ 借 `ImmediateResult` 承载 toolCall
            //    （本模块的 runner **永不走** chat 的模块执行器，那个 result 只是**占位**）。
            //    `prepare` 只被引擎与 `decideApproval` 调用，两条路径拿到的 calls 相同 ⇒ 语义安全。
            items = toolCalls.map { call ->
                ChatPreparedToolItem.ImmediateResult(
                    toolCall = call,
                    result = placeholderResult(call),
                    riskLevel = ChatAgentToolRiskLevel.STANDARD,
                )
            },
            missingPermissions = requiredPermissions.filterNot { PermissionManager.isGranted(appContext, it) },
            riskLevel = ChatAgentToolRiskLevel.STANDARD,
        )
    }

    override suspend fun execute(
        batch: ChatPreparedToolBatch,
        artifacts: ChatAgentArtifactStore,
    ): List<ChatToolResult> = preparedCalls.map { call -> executeOne(call) }

    override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> = toolCalls.map { call ->
        resultFor(call, ChatToolResultStatus.REJECTED, "REJECTED_BY_THE_HOST")
    }

    override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> =
        preparedCalls.map { call ->
            resultFor(call, ChatToolResultStatus.PERMISSION_REQUIRED, "PERMISSION_REQUIRED")
        }

    private fun placeholderResult(call: ChatToolCall): ChatToolResult = ChatToolResult(
        callId = call.id,
        name = call.name,
        status = ChatToolResultStatus.SUCCESS,
        summary = "",
        outputText = "",
    )

    private fun resultFor(
        call: ChatToolCall,
        status: ChatToolResultStatus,
        text: String,
    ): ChatToolResult = ChatToolResult(
        callId = call.id,
        name = call.name,
        status = status,
        summary = text.take(80),
        outputText = text,
    )

    private suspend fun executeOne(call: ChatToolCall): ChatToolResult {
        val name = call.name
        val argsStr = call.argumentsJson

        // 动作前闸门：用户点「结束」时 awaitState 抛 CancellationException
        // （由模块侧按「协程是否仍 active」分流）；暂停则在这里等。
        overlay.awaitState()

        val args = runCatching { parseAgentToolArguments(argsStr) }.getOrElse { e ->
            return resultFor(call, ChatToolResultStatus.ERROR, invalidToolCallText(e))
        }

        val actionDisplay = when (name) {
            "click_point" -> "点击坐标"
            "click_element" -> "点击元素"
            "input_text" -> "输入文本"
            "scroll" -> "滚动屏幕"
            "launch_app" -> "启动应用"
            "wait" -> "等待中..."
            "finish_task" -> "任务完成"
            "long_press" -> "长按坐标"
            "double_tap" -> "双击坐标"
            "swipe" -> "滑动手势"
            else -> "执行: $name"
        }
        overlay.updateStatus(null, actionDisplay)

        // 死循环检测（与改造前逐字相同）
        val currentAction = LastAction(name, argsStr)
        if (lastAction == currentAction) repeatCount++ else repeatCount = 0
        lastAction = currentAction
        val threshold = if (name == "scroll" || name == "wait") 5 else 2
        if (repeatCount >= threshold) {
            DebugLogger.w("AgentModule", "检测到死循环: $name")
            onProgress(ProgressUpdate("检测到操作卡死，强制AI换策略"))
            return resultFor(
                call,
                ChatToolResultStatus.SUCCESS,
                "SYSTEM_INTERVENTION: Repeated action detected. STOP. Try 'scroll', 'press_key(back)', or 'finish_task(success=false)'.",
            )
        }

        onProgress(ProgressUpdate("执行: $name"))
        DebugLogger.d("AgentModule", "Calling tool: $name args: $args")

        val output = try {
            when (name) {
                "click_point" -> {
                    val normX = (args["x"] as? Number)?.toInt() ?: 0
                    val normY = (args["y"] as? Number)?.toInt() ?: 0
                    val realX = agentNormalizedToPixel(normX, screenWidth)
                    val realY = agentNormalizedToPixel(normY, screenHeight)
                    DebugLogger.d("AgentModule", "坐标映射: Norm($normX, $normY) -> Pixel($realX, $realY)")
                    tools.clickPoint(realX, realY)
                }

                "long_press" -> {
                    val normX = (args["x"] as? Number)?.toInt() ?: 0
                    val normY = (args["y"] as? Number)?.toInt() ?: 0
                    val realX = agentNormalizedToPixel(normX, screenWidth)
                    val realY = agentNormalizedToPixel(normY, screenHeight)
                    DebugLogger.d("AgentModule", "坐标映射: Norm($normX, $normY) -> Pixel($realX, $realY)")
                    tools.longPress(realX, realY)
                }

                "double_tap" -> {
                    val normX = (args["x"] as? Number)?.toInt() ?: 0
                    val normY = (args["y"] as? Number)?.toInt() ?: 0
                    val realX = agentNormalizedToPixel(normX, screenWidth)
                    val realY = agentNormalizedToPixel(normY, screenHeight)
                    DebugLogger.d("AgentModule", "坐标映射: Norm($normX, $normY) -> Pixel($realX, $realY)")
                    tools.doubleTap(realX, realY)
                }

                "swipe" -> {
                    val startX = agentNormalizedToPixel((args["start_x"] as? Number)?.toInt() ?: 0, screenWidth)
                    val startY = agentNormalizedToPixel((args["start_y"] as? Number)?.toInt() ?: 0, screenHeight)
                    val endX = agentNormalizedToPixel((args["end_x"] as? Number)?.toInt() ?: 0, screenWidth)
                    val endY = agentNormalizedToPixel((args["end_y"] as? Number)?.toInt() ?: 0, screenHeight)
                    DebugLogger.d("AgentModule", "滑动映射: Pixel($startX, $startY) -> ($endX, $endY)")
                    tools.swipe(startX, startY, endX, endY)
                }

                "click_element" -> tools.clickElement(args["target"]?.toString() ?: "")
                "input_text" -> tools.inputText(args["text"]?.toString() ?: "")
                "scroll" -> tools.scroll(args["direction"]?.toString() ?: "up")
                "press_key" -> tools.pressKey(args["action"]?.toString() ?: "")
                "launch_app" -> tools.launchApp(args["app_name"]?.toString() ?: "")
                "wait" -> tools.wait((args["seconds"] as? Number)?.toInt() ?: 5)

                "finish_task" -> {
                    val result = args["result"]?.toString() ?: "Done"
                    val success = args["success"] as? Boolean ?: true
                    // 拦截记录：循环结束后**优先**返回它（现状是当场 break 并返回）。
                    onFinishTask(result, success)
                    "Task finished (success=$success). Do not call any more tools."
                }

                else -> "Error: Unknown tool name '$name'"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.e("AgentModule", "Error executing tool call", e)
            return resultFor(call, ChatToolResultStatus.ERROR, invalidToolCallText(e))
        }
        return resultFor(call, ChatToolResultStatus.SUCCESS, output)
    }

    private fun invalidToolCallText(e: Throwable): String =
        "SYSTEM ERROR: Invalid tool call format or parameters. Error: ${e.message}. " +
            "Please generate a valid JSON tool call."
}

class AgentModule : BaseModule() {
    companion object {
        private const val PROVIDER_DASHSCOPE = "dashscope"
        private const val PROVIDER_BIGMODEL = "bigmodel"
        private const val PROVIDER_CUSTOM = "custom"
        private const val DISPLAY_MODE_MAIN = "main_screen"
        private const val DISPLAY_MODE_VIRTUAL = "virtual_screen"
    }

    override val id = "vflow.ai.agent"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_ai_agent_name,
        descriptionStringRes = R.string.module_vflow_ai_agent_desc,
        name = "AI 智能体",
        description = "全自动 AI 助手。基于视觉和UI结构理解屏幕，自动完成任务。",
        iconRes = R.drawable.rounded_hexagon_nodes_24,
        category = "界面交互",
        categoryId = "interaction"
    )
    override val aiMetadata = AiModuleMetadata(
        allowSavedWorkflow = false,
    )

    override val uiProvider: ModuleUIProvider = AgentModuleUIProvider()

    override fun getRequiredPermissions(step: ActionStep?): List<Permission> {
        return listOf(
            PermissionManager.ACCESSIBILITY,
            PermissionManager.STORAGE,
            PermissionManager.USAGE_STATS,
            PermissionManager.OVERLAY
        ) + ShellManager.getRequiredPermissions(LogManager.applicationContext)
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            "provider",
            "服务商",
            ParameterType.ENUM,
            PROVIDER_BIGMODEL,
            options = listOf(PROVIDER_DASHSCOPE, PROVIDER_BIGMODEL, PROVIDER_CUSTOM),
            nameStringRes = R.string.param_vflow_ai_agent_provider_name,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_agent_provider_dashscope,
                R.string.option_vflow_ai_agent_provider_zhipu,
                R.string.option_vflow_ai_completion_provider_custom
            ),
            legacyValueMap = mapOf(
                "阿里云百炼" to PROVIDER_DASHSCOPE,
                "DashScope" to PROVIDER_DASHSCOPE,
                "智谱" to PROVIDER_BIGMODEL,
                "Zhipu" to PROVIDER_BIGMODEL,
                "自定义" to PROVIDER_CUSTOM,
                "Custom" to PROVIDER_CUSTOM
            )
        ),
        InputDefinition("base_url", "Base URL", ParameterType.STRING, "https://dashscope.aliyuncs.com/compatible-mode/v1", nameStringRes = R.string.param_vflow_ai_agent_base_url_name),
        InputDefinition("api_key", "API Key", ParameterType.STRING, "", nameStringRes = R.string.param_vflow_ai_agent_api_key_name),
        InputDefinition("model", "模型", ParameterType.STRING, "glm-4.6v-flash", nameStringRes = R.string.param_vflow_ai_agent_model_name),
        InputDefinition("instruction", "指令", ParameterType.STRING, "", acceptsMagicVariable = true, supportsRichText = true, nameStringRes = R.string.agent_instruction_label),
        InputDefinition("max_steps", "最大步数", ParameterType.NUMBER, 15.0, nameStringRes = R.string.agent_max_steps_label),
        InputDefinition(
            "display_mode",
            "执行环境",
            ParameterType.ENUM,
            DISPLAY_MODE_MAIN,
            options = listOf(DISPLAY_MODE_MAIN, DISPLAY_MODE_VIRTUAL),
            nameStringRes = R.string.param_vflow_ai_agent_display_mode_name,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_agent_display_mode_main,
                R.string.option_vflow_ai_agent_display_mode_virtual
            ),
            legacyValueMap = mapOf(
                "主屏幕" to DISPLAY_MODE_MAIN,
                "Main Screen" to DISPLAY_MODE_MAIN,
                "虚拟屏幕 (后台)" to DISPLAY_MODE_VIRTUAL,
                "Virtual Screen (Background)" to DISPLAY_MODE_VIRTUAL
            ),
            // fork：允许用魔法变量驱动执行环境 —— 折叠触发器（`vflow.trigger.fold`）的
            // `fold_state` / `is_folded` 因此能分流「展开 ⇒ 主屏（内屏）；折叠 ⇒ 虚拟屏」。
            // ⚠️ 折叠屏上**没有第二块可承载任务的屏**（`canHostTasks=false` + 面板点不亮，见
            //    `docs/fork/surveys/multi-display-survey.md`）⇒「指定某块物理屏」已被否决，
            //    变量驱动是这条需求唯一可落地的形态。
            acceptsMagicVariable = true,
            isHidden = false
        ),
        // fork：运行界面 —— 悬浮窗（卡片里带虚拟屏预览）/ 监视器页面（画面 + 完整思考流 + 控制）。
        // 原来是悬浮窗卡片上一个「监视器」按钮，改成参数后：① 按钮不再挤占卡片宽度（外屏上会被裁掉）；
        // ② 无人值守的工作流也能直接落到页面上。
        InputDefinition(
            "monitor_mode",
            "运行界面",
            ParameterType.ENUM,
            AgentOverlayManager.MONITOR_MODE_OVERLAY,
            options = listOf(
                AgentOverlayManager.MONITOR_MODE_OVERLAY,
                AgentOverlayManager.MONITOR_MODE_ACTIVITY
            ),
            nameStringRes = R.string.param_vflow_ai_agent_monitor_mode_name,
            optionsStringRes = listOf(
                R.string.option_vflow_ai_agent_monitor_overlay,
                R.string.option_vflow_ai_agent_monitor_activity
            ),
            legacyValueMap = mapOf(
                "悬浮窗" to AgentOverlayManager.MONITOR_MODE_OVERLAY,
                "Overlay" to AgentOverlayManager.MONITOR_MODE_OVERLAY,
                "监视器页面" to AgentOverlayManager.MONITOR_MODE_ACTIVITY,
                "Monitor screen" to AgentOverlayManager.MONITOR_MODE_ACTIVITY
            ),
            isHidden = false
        ),
        // fork：配置引用 + 可配前段（追加，不重排既有参数）。
        AiModelConfigRef.SOURCE_INPUT,
        AiModelConfigRef.CONFIG_ID_INPUT,
        InputDefinition(
            "system_prompt",
            "系统提示词前段",
            ParameterType.STRING,
            "",
            // ⚠️ plain：不接受变量、不走富文本胶囊（避开 supportsRichText 那个坑）。
            acceptsMagicVariable = false,
            supportsRichText = false,
            hintStringRes = R.string.hint_ai_agent_system_prompt_prefix,
            nameStringRes = R.string.hint_ai_agent_system_prompt_prefix
        ),
        InputDefinition("tools", "工具配置", ParameterType.ANY, isHidden = true, nameStringRes = R.string.param_vflow_ai_agent_tools_name)
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("result", "最终结果", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_ai_agent_result_name),
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_ai_agent_success_name)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val rawInstruction = step.parameters["instruction"]?.toString() ?: ""
        val displayMode = step.parameters["display_mode"] as? String ?: DISPLAY_MODE_MAIN
        val prefix = if (displayMode == DISPLAY_MODE_VIRTUAL) context.getString(R.string.summary_vflow_agent_background_prefix) else ""

        // 如果指令复杂，只显示标题
        if (VariableResolver.isComplex(rawInstruction)) {
            return PillUtil.buildSpannable(
                context,
                prefix + metadata.getLocalizedName(context),
                PillUtil.richTextPreview(rawInstruction)
            )
        }

        val instructionPill = PillUtil.createPillFromParam(step.parameters["instruction"], getInputs().find { it.id == "instruction" })
        return PillUtil.buildSpannable(context, prefix + metadata.getLocalizedName(context) + ": ", instructionPill)
    }

    // 定义工具 Schema

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val inputsById = getInputs().associateBy { it.id }
        // 获取参数
        val baseUrl = context.getVariableAsString("base_url", "")
        val apiKey = context.getVariableAsString("api_key", "")
        val model = context.getVariableAsString("model", "glm-4.6v-flash")
        val instruction = VariableResolver.resolve(context.getVariableAsString("instruction", ""), context)
        // 现在 variables 是 Map<String, VObject>，使用 getVariableAsInt 获取
        val maxSteps = context.getVariableAsInt("max_steps") ?: 15
        val rawDisplayMode = context.getVariableAsString("display_mode", DISPLAY_MODE_MAIN)
        val displayMode = inputsById["display_mode"]?.normalizeEnumValue(rawDisplayMode) ?: rawDisplayMode
        // fork：运行界面（悬浮窗 / 监视器页面）
        val monitorMode = inputsById["monitor_mode"]?.normalizeEnumValue(
            context.getVariableAsString("monitor_mode", AgentOverlayManager.MONITOR_MODE_OVERLAY)
        ) ?: AgentOverlayManager.MONITOR_MODE_OVERLAY
        val provider = inputsById["provider"]?.normalizeEnumValue(
            context.getVariableAsString("provider", PROVIDER_BIGMODEL)
        ) ?: PROVIDER_BIGMODEL
        // fork：可配前段（引用模型配置时**值拷贝**，也可手填；D7 / D13）。
        val systemPromptPrefix = context.getVariableAsString("system_prompt", "")

        // 配置引用（fork：AI 能力统一批 4 的 T6）：默认 manual ⇒ 老步骤行为与改造前等价。
        val resolvedPreset = when (
            val resolution = AiModelConfigResolver.resolve(
                parameters = mapOf(
                    AiModelConfigRef.PARAM_SOURCE to context.getVariableAsString(
                        AiModelConfigRef.PARAM_SOURCE,
                        AiModelConfigRef.SOURCE_MANUAL,
                    ),
                    AiModelConfigRef.PARAM_CONFIG_ID to
                        context.getVariableAsString(AiModelConfigRef.PARAM_CONFIG_ID, ""),
                ),
                presets = ChatPresetRepository(context.applicationContext).getPresets(),
                manual = ManualAiConfig(
                    provider = provider,
                    model = model,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    systemPrompt = systemPromptPrefix,
                    // 本模块**没有** temperature 参数（现状不发送该字段）⇒ 取「不指定」的等价值。
                    temperature = AGENT_MODULE_TEMPERATURE,
                ),
            )
        ) {
            is AiModelConfigResolution.Resolved -> resolution.preset
            // 引用解析失败 ⇒ **明确报错，不静默**（D8 的兜底分支）。
            is AiModelConfigResolution.Missing ->
                return ExecutionResult.Failure(resolution.title, resolution.message)
        }

        // ⚠️ 校验的是**解析后**的 key：引用模式下步骤里的 `api_key` 恒为空串。
        if (resolvedPreset.apiKey.isBlank()) {
            return ExecutionResult.Failure("配置错误", "API Key 不能为空")
        }

        // 虚拟屏幕逻辑
        var targetDisplayId = 0
        if (displayMode == DISPLAY_MODE_VIRTUAL) {
            if (!ShellManager.isShizukuActive(context.applicationContext)) {
                return ExecutionResult.Failure("权限不足", "使用虚拟屏幕需要 Shizuku 权限。")
            }
            onProgress(ProgressUpdate("正在创建虚拟屏幕..."))
            targetDisplayId = VirtualDisplayManager.startHeadlessSession()
            if (targetDisplayId < 0) {
                return ExecutionResult.Failure("环境错误", "虚拟屏幕创建失败。")
            }
            // 将 displayId 注入到变量中，供 AgentTools 和 AgentUtils 使用
            context.setVariable("_target_display_id", VObjectFactory.from(targetDisplayId))
            VirtualDisplayManager.getCurrentSessionSize()?.let { (width, height) ->
                context.setVariable("_target_display_width", VObjectFactory.from(width))
                context.setVariable("_target_display_height", VObjectFactory.from(height))
            }
            DebugLogger.i("AgentModule", "Agent 将运行在 Display ID: $targetDisplayId")
        }

        // 初始化悬浮窗
        // fork：把模块名与步数上限交给悬浮窗，它会登记进 AgentRunMonitor（监视器要显示这两个）
        val overlayManager = AgentOverlayManager(
            context.applicationContext,
            moduleLabel = metadata.getLocalizedName(context.applicationContext),
            maxSteps = maxSteps,
            monitorMode = monitorMode,
        )
        withContext(Dispatchers.Main) {
            overlayManager.show()
        }

        try {
            // fork：虚拟屏预览（方案 A）。虚拟屏模式下用户在主屏上看不到 AI 正在操作的屏幕，
            // 这里把每一帧推给悬浮窗。只在虚拟屏启动；停止由 finally 里的 release() 负责。
            // ⚠️ 必须放在 try 内：放在 show() 与 try 之间的话，那段窗口里被取消就没人收尾了。
            // fork：`activity` 模式下画面交给监视器页面，这里**不再注册**预览监听者（省每帧 ≈10MB 拷贝）
            if (targetDisplayId > 0 && overlayManager.usesOverlayPreview) {
                VirtualDisplayManager.addPreviewListener { frame ->
                    overlayManager.updatePreview(frame)
                }
            }

            val agentTools = AgentTools(context)

            val date = Date()
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateString = dateFormat.format(date)
            val timeString = timeFormat.format(date)

            val recentApps = AgentUtils.getRecentApps(context.applicationContext)

            // fork：本模块**自己的** 11 个工具（8 个既有 + long_press / double_tap / swipe）。
            val toolDefinitions = agentModuleToolDefinitions()

            val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            // 默认主屏尺寸
            var screenWidth = displayMetrics.widthPixels
            var screenHeight = displayMetrics.heightPixels

            // 如果使用虚拟屏幕，使用真实会话尺寸，保证模型归一化坐标映射正确。
            if (targetDisplayId > 0) {
                val size = VirtualDisplayManager.getCurrentSessionSize()
                screenWidth = size?.first ?: screenWidth
                screenHeight = size?.second ?: screenHeight
                // fork：这里**不能**调 `hideForScreenshot()`。悬浮窗挂在主屏、被截的图来自虚拟屏，
                // 两者互不干扰；而 `hideForScreenshot()` 之后本分支**永远没有对应的 restore**
                // （restore 只写在 `targetDisplayId == 0` 那条路上）⇒ 整场任务里悬浮窗
                // （彩虹边框 + 控制卡片 + 暂停/结束按钮 + 虚拟屏预览）全程不可见。
            }

            // finish_task 的拦截记录（循环结束后**优先**返回它）
            var recordedFinishTask: Pair<String, Boolean>? = null

            val runner = AgentModuleToolRunner(
                tools = agentTools,
                overlay = overlayManager,
                appContext = context.applicationContext,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                onProgress = onProgress,
                requiredPermissions = getRequiredPermissions(null),
                onFinishTask = { text, success -> recordedFinishTask = text to success },
            )

            // 提示词：前段（可配）+ 协议段（本模块的）+ 公共段 + 19 条规则（WORKFLOW_RULES）+ User Goal
            val host = WorkflowAgentHost(
                allToolNames = toolDefinitions.map { it.name },
                // D17-④：工作流侧保留硬上限，由模块的 `max_steps` 注入。
                maxRounds = maxSteps,
                // D14：观察输入保持 BOTH（= 现状），本批不参数化。
                observationMode = ObservationMode.BOTH,
                prefix = systemPromptPrefix,
                protocolSection = agentModuleProtocolSection(dateString, timeString, recentApps),
                progressSink = { update -> onProgress(update) },
                streamingSink = { content, _ -> overlayManager.publishModelOutput(content, "决策中...") },
            )

            val engine = ChatAgentEngine(
                chatClient = ChatCompletionClient(),
                allTools = toolDefinitions,
                toolRunner = runner,
            )

            // 观察装配（引擎不做任何截屏 / IO ⇒ 机制在这里注入）
            val observationProvider = AgentObservationProvider { mode, round, _ ->
                // 每轮开头检查暂停 / 结束（现状每步开头也有）
                overlayManager.awaitState()
                overlayManager.updateStatus("分析屏幕...", "准备截屏")
                // 主屏幕才需要隐藏悬浮窗
                if (targetDisplayId == 0) {
                    overlayManager.hideForScreenshot()
                }
                val screenshotResult = AgentUtils.captureScreen(context.applicationContext, context)
                val uiHierarchy = if (mode == ObservationMode.SCREENSHOT) {
                    null
                } else {
                    AgentUtils.dumpHierarchy(context.applicationContext, targetDisplayId)
                }
                if (targetDisplayId == 0) {
                    overlayManager.restoreAfterScreenshot()
                }
                uiHierarchy?.let { DebugLogger.d("AgentModule", "Step $round Context: $it") }
                overlayManager.updateStatus("AI 思考中...", "生成计划")
                onProgress(ProgressUpdate("思考中..."))

                AgentObservation(
                    // 截图失败时把现状那条提示补进文本（引擎的文本段没有独立 part 机制）
                    text = uiHierarchy?.let {
                        if (screenshotResult.path == null) {
                            "$it\n[System: Screenshot failed. Rely on XML.]"
                        } else {
                            it
                        }
                    },
                    image = screenshotResult.path?.let { path ->
                        ChatImageAttachment(
                            uri = path,
                            source = ChatImageSource.AGENT,
                            mimeType = "image/jpeg",
                        )
                    },
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
            }

            try {
                engine.runLoop(
                    host,
                    AgentLoopRequest(
                        preset = resolvedPreset,
                        task = AgentTask(instruction = instruction),
                        observation = observationProvider,
                    ),
                )
            } catch (e: CancellationException) {
                // ⚠️ 分流：悬浮窗「结束」抛的 CancellationException 时协程**仍 active**
                //    ⇒ 与改造前逐字相同的失败文案；协程已被取消（工作流/执行器取消）时原样上抛。
                if (currentCoroutineContext().isActive) {
                    return ExecutionResult.Failure("任务取消", "用户手动停止了任务。")
                }
                throw e
            }

            // 两条出口，都不丢弃模型产出的文本：
            // ① `finish_task` 被调用过 ⇒ 返回**模型自己给出的**显式收尾（`result` + `success`）——
            //    那是模型明确声明的完成态，比收尾总结更权威，故优先；
            // ② 否则走宿主映射 ⇒ 到顶时正文 = **模型的收尾总结**（Q4 裁决），
            //    只有收尾轮没产出文本（空白）时才回退固定文案。
            val finished = recordedFinishTask
            return if (finished != null) {
                ExecutionResult.Success(mapOf(
                    "result" to VString(finished.first),
                    "success" to VBoolean(finished.second),
                ))
            } else {
                host.toExecutionResult()
            }
        } finally {
            // 销毁前清理虚拟屏幕上的应用
            // 现在 variables 是 Map<String, VObject>，使用 getVariableAsInt 获取
            val targetDisplayId = context.getVariableAsInt("_target_display_id") ?: 0
            if (targetDisplayId > 0) {
                withContext(NonCancellable) {
                    AgentUtils.killTopAppOnDisplay(context.applicationContext, targetDisplayId)
                    DebugLogger.i(TAG, "释放虚拟屏幕...")
                    VirtualDisplayManager.release()
                }
            }
            // 关闭悬浮窗
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    overlayManager.dismiss()
                }
            }
        }
    }
}
