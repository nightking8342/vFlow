// 文件: main/java/com/chaomixian/vflow/core/backup/SecretFieldScrubber.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

/**
 * 工作流**步骤参数**里的凭证字段的处理层。
 *
 * ## 它解决的是哪一个泄漏点
 *
 * 备份的 `secrets` scope 只管 `SharedPreferences` 里的密钥。但**工作流的步骤参数里
 * 也有密钥** —— 例如 `AgentModule` 与 `AutoGLMModule` 都有 `api_key` 输入
 * （`AgentModule.kt:134` / `AutoGLMModule.kt:99`，已核实）。
 * 它们随 `workflows` scope 一起导出，**完全绕过 secrets 的勾选**
 * （静默失效点 6）。
 *
 * 本对象提供两条对称的处理：
 *
 * | 场景 | 方法 | 结果 |
 * |---|---|---|
 * | 用户**没勾**「包含密钥」 | [scrub] | 命中的值清成 `""`，**并返回位置列表** |
 * | 用户**勾了**「包含密钥」且有口令 | [encryptInPlace] | 命中的值就地换成 `$enc` 节点 |
 *
 * ## ⚠️ 这是**精确 id 清单**，不是通用规则（有意的取舍）
 *
 * [SCRUB_SUBSTRINGS] 覆盖的是「名字里含这些词的 id」，而 [SCRUB_EXCLUDED] /
 * [SCRUB_EXACT] 是**逐个人工判定过的 id**。
 * ⇒ **未来新增的参数 id 若不在名单内，不会自动被清洗。**
 *
 * 这是刻意选的方向：**宁可漏清洗一个新凭证**（用户下次导出时手动删掉那个模块的密钥），
 * **也不误伤一个正常参数** —— 后者是静默失效，用户完全无从发现
 * （`key_code` 被清空会让按键模块在某个凌晨静默失灵，没人会联想到是备份清洗干的）。
 *
 * ⇒ **后续维护者请把这三个常量当「白名单」维护**：新增推送/鉴权类模块时，
 * 回到这里补一行；删掉某个模块时，也要把它在这里的条目一并处理（见
 * `SecretFieldScrubberTest` 的反僵尸用例）。
 */
object SecretFieldScrubber {

    /**
     * ① **子串匹配**（忽略大小写）。
     *
     * 覆盖：`access_token` / `bot_token` / `file_token`（飞书、Telegram）、
     * `api_key`（三个 AI 模块）、`device_key`（Bark 推送）、
     * 以及未来任何名字里带 `token` / `secret` / `password` 的参数。
     *
     * ⚠️ **`device_key` 必须作为独立子串保留** —— 它名字里不带 `token`/`secret`，
     * 单靠那三个词会漏掉它（Bark 推送的真凭证）；而只留 `key` 子串又会误伤
     * `key_code`。故它走这里，而 `key` 走 [SCRUB_EXACT]。
     */
    val SCRUB_SUBSTRINGS = listOf("token", "secret", "password", "device_key", "api_key")

    /**
     * ② **排除名单** —— 看着像凭证但**不是**的 id。
     *
     * 故意用**显式集合**而非规则：便于逐项反向锁住（`SecretFieldScrubberTest`
     * 对每个 id 各有一条「原样保留」的断言），也便于靠反僵尸用例发现它们随代码演进
     * 变成了僵尸条目。
     *
     * | id | 为什么不清 |
     * |---|---|
     * | `page_token` | 飞书**分页游标**，不是凭证（`FeishuGetMessageHistoryModule.kt:134`） |
     * | `key_code` | **按键码（Int）**（`CorePressKeyModule.kt:53` / `KeyEventTriggerModule.kt:105`）。清成 `""` 会让按键模块静默失效 |
     * | `key_encoding` | 密钥的**编码方式**（HEX/UTF8/…）（`CryptoModuleSupport.kt:145`） |
     * | `key_action` | 按键**动作**（`SendKeyEventModule.kt:67`） |
     * | `auth_mode` | 鉴权**方式**（值本身是 `tenant_access_token` 这类标识，非凭证）（`FeishuSendMessageModule.kt:69`） |
     */
    val SCRUB_EXCLUDED = setOf("page_token", "key_code", "key_encoding", "key_action", "auth_mode")

    /**
     * ③ **单列精确匹配** —— `key`（加解密模块的密钥，`CryptoModuleSupport.kt:136`）
     * 是凭证，但它作为**子串**会误伤上面那三个 `key_*`。
     *
     * 三段式把这两件事分开表达：`key` 只走精确匹配（命中它自己、不碰 `key_*`），
     * 其余走子串匹配。
     */
    val SCRUB_EXACT = setOf("key")

    /**
     * 判断一个参数 id 是否应当被清洗/加密。
     *
     * ⚠️ 判定顺序：**精确在前、排除在子串之后**。
     * `key` 落在 [SCRUB_EXACT] 里 ⇒ 直接命中（它不含任何子串词，走子串是命中不了的）。
     * 其余先过子串、再被排除名单否决。
     */
    fun shouldScrub(id: String): Boolean =
        id in SCRUB_EXACT ||
            (SCRUB_SUBSTRINGS.any { id.contains(it, ignoreCase = true) } && id !in SCRUB_EXCLUDED)

    /**
     * 清洗：把命中的参数值替换为 `""`。
     *
     * @param workflows 工作流的 `JsonArray`（**就地修改** —— 调用方通常刚从
     *   `env.json.toJsonTree` 拿到它，改完直接进信封）。
     * @return 被清洗的**位置**列表（`<stepId>.<paramId>`）。
     *   ⚠️ **返回值必须被消费** —— 它要进信封的 `summary.scrubbedFields`。
     *   不返回的话，用户永远不会知道密钥被抹掉了（静默失效点 6 的另一半）。
     */
    fun scrub(workflows: JsonArray): List<String> {
        val touched = mutableListOf<String>()
        forEachStepParameter(workflows) { stepId, paramId, _, replace ->
            if (shouldScrub(paramId)) {
                replace(JsonPrimitive(""))
                touched += "$stepId.$paramId"
            }
        }
        return touched
    }

    /**
     * 加密：把命中的**字符串**参数值就地换成 `$enc` 信封节点。
     *
     * @return 处理了几处。**不返回位置列表** —— 与 [scrub] 不对称是刻意的：
     *   加密不是「丢失信息」，没有必要告诉用户「这几个字段被加密了」。
     *
     * ⚠️ **只处理 JSON 字符串原始值**。理由见方案 §6.7 防线 3：
     * 字典/列表类型的参数会被 `VObjectGsonAdapter` 写成
     * `{"type":"vflow.type.dictionary","value":{…}}` ——
     * 那种对象**不是密文信封**，不该被当密文解。
     * （当前所有命中项都是裸 `String`，见 `AgentModuleUIProvider.readFromEditor`。）
     *
     * ⚠️ **加密后的形状必须与 `BackupPipeline` 深走时查找的形状一致** ——
     * 两者都只用 [SecretEnvelope] 的 `isWrapped` / `unwrap`，**不各写一份判据**
     * （方案 §6.7 防线 1）。形状不匹配是**静默失效**：`$enc` 对象会被
     * Gson 反序列化成 `LinkedTreeMap` 混进 `parameters`，模块拿到的不是字符串而是 map，
     * 行为错乱且零报错。
     */
    fun encryptInPlace(workflows: JsonArray, ctx: SecretContext): Int {
        var count = 0
        forEachStepParameter(workflows) { _, paramId, current, replace ->
            if (shouldScrub(paramId) &&
                current != null && current.isJsonPrimitive && current.asJsonPrimitive.isString
            ) {
                replace(ctx.seal(current.asString))
                count++
            }
        }
        return count
    }

    /**
     * 遍历全部步骤参数并把处理权交给 [action]。
     *
     * 遍历范围 = **`triggers` + `steps`**，与 `Workflow.allSteps`
     * （`Workflow.kt:45-46`）一致 —— 触发器也带参数，也可能有凭证
     * （`LogcatTriggerHandler` 类注释记过「只处理 steps 会漏掉触发器」的同类教训）。
     *
     * @param action `(stepId, paramId, currentValue, replace)`。`replace` 就地把
     *   该参数的值换成新节点 —— 抽出这个回调而不是让每个调用方各写一遍
     *   `params.add(key, elem)`，是为了让「清洗」与「加密」**共用同一条遍历**：
     *   两份遍历迟早会漂移（本仓库在 logcat 的 app/core 双份实现上记过这个代价）。
     */
    private inline fun forEachStepParameter(
        workflows: JsonArray,
        action: (stepId: String, paramId: String, current: JsonElement?, replace: (JsonElement) -> Unit) -> Unit
    ) {
        for (workflowElement in workflows) {
            val workflow = workflowElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (containerKey in listOf("triggers", "steps")) {
                val steps = workflow.get(containerKey)
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray ?: continue
                for (stepElement in steps) {
                    val step = stepElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val stepId = step.get("id")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?: continue
                    val params = step.get("parameters")
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject ?: continue

                    // ⚠️ 先收集键再改：直接在 entrySet 上改 map 会 ConcurrentModificationException。
                    //    （Gson 的 JsonObject 是 LinkedTreeMap，改值不换键其实安全，
                    //     但显式收集键让这条不依赖实现细节。）
                    for (paramId in params.keySet().toList()) {
                        action(stepId, paramId, params.get(paramId)) { replacement ->
                            params.add(paramId, replacement)
                        }
                    }
                }
            }
        }
    }
}
