package com.chaomixian.vflow.core.execution

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.workflow.GlobalVariableStore
import kotlinx.coroutines.runBlocking
import org.mozilla.javascript.*

/**
 * JavaScript 脚本执行器。
 * 负责创建 JavaScript 环境，将 vFlow 模块作为函数暴露给脚本，并执行脚本。
 */
class JsExecutor(private val executionContext: ExecutionContext) {

    companion object {
        private const val TAG = "JsExecutor"
    }

    /**
     * 执行一段 JavaScript 脚本。
     * @param script 要执行的 JS 代码。
     * @param inputs 从工作流传递给脚本的输入变量 Map (可读写)。
     * @return 脚本返回的对象，已转换为 Kotlin Map。
     */
    fun execute(script: String, inputs: MutableMap<String, Any?>): Map<String, Any?> {
        val context = Context.enter()
        try {
            // 设置优化级别，-1 表示解释模式，0+ 表示优化模式
            @Suppress("DEPRECATION")
            context.optimizationLevel = -1

            // 绑定应用的 ClassLoader。缺少这一步时，脚本里的
            // Packages.<应用内部类> 会被 Rhino 当成包路径对象（NativeJavaPackage），
            // 表现为「xxx 不是函数，它是 object」。
            context.setApplicationClassLoader(executionContext.applicationContext.classLoader)

            // 创建作用域。
            // 用 ImporterTopLevel 而非 initStandardObjects()：前者额外提供
            // importClass / importPackage 两个 Java 互操作入口，使 Auto.js、
            // ShortX 风格的脚本可以直接粘贴运行，无需改写成 Packages.xxx 全路径。
            // 其构造函数内部已初始化标准对象，无需再调 initStandardObjects()。
            val scope = ImporterTopLevel(context)

            // 注入真实的 Android Context（经 javaToJS 桥接为 Java 对象）。
            // 此前这里注入的是空壳 JS 对象，导致脚本中的
            // context.getSystemService(...) / getContentResolver() 等调用全部失效。
            // 注入 Application Context 即可：调用能否成功取决于进程 UID，与用哪个
            // Context 无关；需要更高权限的操作应走 vflow.shizuku.shell_command。
            ScriptableObject.putProperty(
                scope,
                "context",
                Context.javaToJS(executionContext.applicationContext, scope)
            )

            // 注入 inputs (直接作为对象)
            val inputsObj = context.newObject(scope)
            inputs.forEach { (key, value) ->
                inputsObj.put(key, inputsObj, JsValueConverter.coerceToJs(context, scope, value))
            }
            ScriptableObject.putProperty(scope, "inputs", inputsObj)

            // 注入 sys (魔法变量)
            val sysObj = context.newObject(scope)
            executionContext.magicVariables.forEach { (key, vObj) ->
                sysObj.put(key, sysObj, JsValueConverter.coerceToJs(context, scope, vObj))
            }
            ScriptableObject.putProperty(scope, "sys", sysObj)

            // 注入 vars (命名变量)
            val varsObj = context.newObject(scope)
            executionContext.namedVariables.forEach { (key, vObj) ->
                varsObj.put(key, varsObj, JsValueConverter.coerceToJs(context, scope, vObj))
            }
            ScriptableObject.putProperty(scope, "vars", varsObj)

            // 注入 global (持久化全局变量快照)
            val globalObj = context.newObject(scope)
            runCatching {
                GlobalVariableStore.getAll(executionContext.applicationContext).forEach { (key, vObj) ->
                    globalObj.put(key, globalObj, JsValueConverter.coerceToJs(context, scope, vObj))
                }
            }.onFailure { error ->
                DebugLogger.w(TAG, "加载全局变量到 JavaScript 环境失败: ${error.message}", error)
            }
            ScriptableObject.putProperty(scope, "global", globalObj)

            // 自动构建并注入 vFlow 模块树
            injectVFlowModules(context, scope)

            DebugLogger.d(TAG, "开始执行 JavaScript 脚本...")
            val result = context.evaluateString(scope, script, "vflow_script", 1, null)

            // 处理结果
            return when (result) {
                is NativeObject, is ScriptableObject -> {
                    val kotlinResult = JsValueConverter.coerceToKotlin(result)
                    if (kotlinResult is Map<*, *>) {
                        kotlinResult.entries.associate { (key, value) -> key.toString() to value }
                    } else {
                        emptyMap()
                    }
                }
                is NativeArray -> {
                    val listResult = JsValueConverter.coerceToKotlin(result) as? List<*>
                    mapOf("result" to listResult)
                }
                is org.mozilla.javascript.Undefined -> emptyMap()
                else -> mapOf("result" to JsValueConverter.coerceToKotlin(result))
            }

        } catch (e: RhinoException) {
            val details = e.details().takeIf { it.isNotBlank() } ?: e.message.orEmpty()
            val source = e.lineSource()?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            val errorMsg = "JavaScript Error at line ${e.lineNumber()}: ${e.columnNumber()}: $details$source"
            DebugLogger.e(TAG, errorMsg)
            throw RuntimeException(errorMsg, e)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Script execution failed", e)
            throw RuntimeException("Execution failed: ${e.message}", e)
        } finally {
            Context.exit()
        }
    }

    /**
     * 标准化模块接口映射。
     * 扫描 ModuleRegistry 中的所有模块，解析 ID (如 vflow.device.click)，
     * 并在 JavaScript 环境中构建对应的对象树和函数。
     */
    private fun injectVFlowModules(context: Context, scope: Scriptable) {
        // 获取或创建根对象 'vflow'
        val vflowObjAny = scope.get("vflow", scope)
        val vflowObj: Scriptable = if (vflowObjAny is Scriptable) {
            vflowObjAny
        } else {
            val newObj = context.newObject(scope)
            ScriptableObject.putProperty(scope, "vflow", newObj)
            newObj
        }

        val allModules = ModuleRegistry.getAllModules()

        for (module in allModules) {
            // 仅处理标准命名空间的模块 (vflow.*)
            if (!module.id.startsWith("vflow.")) continue

            // 拆分 ID，例如 ["vflow", "device", "click"]
            val parts = module.id.split('.')
            var currentObj: Scriptable = vflowObj

            // 构建中间路径 (如 "device")
            for (i in 1 until parts.size - 1) {
                val partName = parts[i]
                val nextObjAny = currentObj.get(partName, currentObj)

                // 如果属性不存在或不是 Scriptable，创建新的对象
                if (nextObjAny !is Scriptable) {
                    val newObj = context.newObject(scope)
                    currentObj.put(partName, currentObj, newObj)
                    currentObj = newObj
                } else {
                    currentObj = nextObjAny
                }
            }

            // 在叶子节点绑定函数 (如 "click")
            val functionName = parts.last()
            val moduleFunction = JsModuleWrapperFunction(module, executionContext, context, scope)
            currentObj.put(functionName, currentObj, moduleFunction)
        }
    }
}

/**
 * JavaScript 模块包装函数。
 * 将 JavaScript 函数调用桥接到 Kotlin 的 ActionModule.execute。
 * JS 调用示例: vflow.device.toast({ message: "Hello" })
 */
class JsModuleWrapperFunction(
    private val module: ActionModule,
    private val executionContext: ExecutionContext,
    private val rhinoContext: Context,
    private val rhinoScope: Scriptable
) : BaseFunction() {

    override fun call(
        cx: org.mozilla.javascript.Context,
        scope: Scriptable,
        thisObj: Scriptable,
        args: Array<Any?>
    ): Any? {
        // 解析参数
        val params = mutableMapOf<String, Any?>()
        if (args.isNotEmpty() && args[0] is Scriptable) {
            val argObj = args[0] as Scriptable
            val ids = argObj.ids
            for (id in ids) {
                val key = id.toString()
                val propValue = argObj.get(key, argObj)
                if (propValue != org.mozilla.javascript.Context.getUndefinedValue()) {
                    params[key] = JsValueConverter.coerceToKotlin(propValue)
                }
            }
        }

        // 准备模块上下文
        val moduleContext = executionContext.copy(
            variables = ExecutionContext.mutableMapToVObjectMap(params),
            magicVariables = mutableMapOf()
        )

        // 同步执行挂起函数
        var executionResult: ExecutionResult
        runBlocking {
            executionResult = module.execute(moduleContext) { progress ->
                DebugLogger.d("JsExecutor", "[JS->${module.metadata.name}] ${progress.message}")
            }
        }

        // 将结果转换回 JavaScript
        return when (executionResult) {
            is ExecutionResult.Success -> {
                val outputs = executionResult.outputs
                if (outputs.isEmpty()) {
                    org.mozilla.javascript.Context.getUndefinedValue()
                } else if (outputs.size == 1 && outputs.containsKey("result")) {
                    JsValueConverter.coerceToJs(cx, rhinoScope, outputs["result"])
                } else {
                    JsValueConverter.coerceToJs(cx, rhinoScope, outputs)
                }
            }
            is ExecutionResult.Failure -> {
                val fail = executionResult
                // 抛出 JavaScript 错误，脚本可以用 try-catch 捕获
                throw org.mozilla.javascript.JavaScriptException(
                    "${fail.errorTitle}: ${fail.errorMessage}",
                    "ModuleError",
                    0
                )
            }
            else -> org.mozilla.javascript.Context.getUndefinedValue()
        }
    }

    override fun getArity(): Int = 1
}
