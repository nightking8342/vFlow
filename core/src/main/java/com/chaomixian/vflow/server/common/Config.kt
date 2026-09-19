// 文件: server/src/main/java/com/chaomixian/vflow/server/common/Config.kt
package com.chaomixian.vflow.server.common

object Config {
    enum class WorkerType {
        SHELL,
        ROOT
    }

    // ============================================
    // 日志配置
    // ============================================

    /**
     * DEBUG 模式开关
     * - true: 显示所有日志（包括 DEBUG 级别）
     * - false: 只显示 INFO、WARN、ERROR 级别
     *
     * 生产环境建议设置为 false 以减少日志输出
     */
    const val DEBUG = true

    // ============================================
    // 端口配置
    // ============================================

    // Master 监听端口 (对外)
    const val PORT_MASTER = 19999

    // Worker 监听端口 (对内 - Loopback)
    const val PORT_WORKER_SHELL = 20001
    const val PORT_WORKER_ROOT = 20002

    // ============================================
    // 网络配置
    // ============================================

    // Socket 连接与读取超时 (毫秒)
    // 0 = 无限超时，适用于长连接场景
    const val SOCKET_TIMEOUT = 0

    // 监听地址配置
    const val LOCALHOST = "127.0.0.1"  // 本地回环
    const val BIND_ADDRESS = "0.0.0.0"  // 绑定所有网卡，允许远程连接
    private const val UNIX_SOCKET_PREFIX = "vflow"

    // ============================================
    // 路由表配置
    // ============================================

    // 路由表配置：定义哪些 Target 由哪个 Worker 处理
    val ROUTING_TABLE = mapOf(
        // Shell 权限可处理
        "clipboard" to WorkerType.SHELL,
        "input" to WorkerType.SHELL,
        "audio" to WorkerType.SHELL,
        "wifi" to WorkerType.SHELL,
        "bluetooth_manager" to WorkerType.SHELL,
        "nfc" to WorkerType.SHELL,
        "power" to WorkerType.SHELL,
        "activity" to WorkerType.SHELL,
        "connectivity" to WorkerType.SHELL,
        "location" to WorkerType.SHELL,
        "alarm" to WorkerType.SHELL,
        "activity_task" to WorkerType.SHELL,
        "screenshot" to WorkerType.SHELL,
        // logcat 触发器（fork 新增）。
        //
        // ⚠️ 加了它才算注册完成 —— `serviceWrappers`（ShellWorker 侧）
        // 只是"谁能处理"，这张表才是"转发给谁"。
        // 只改一处的话请求会在这里被拦下并回 `{"error":"No route"}`，
        // 表现是流建立不起来、触发器静默不工作。
        "logcat" to WorkerType.SHELL,

        // 必须 Root 权限
        "hotspot" to WorkerType.ROOT,
        "uinput" to WorkerType.ROOT,
        "system_root" to WorkerType.ROOT
    )

    /**
     * 流式订阅方法 → target。
     *
     * ## ⚠️ 为什么必须与 [ROUTING_TABLE] 同时维护
     *
     * 流式请求与普通请求走**两条不同的路由路径**：
     * 普通请求查 [ROUTING_TABLE]，流式请求查本表。
     * 新增一个流式 target 时若只加了 [ROUTING_TABLE]，
     * 请求会被 Master 的流式白名单拦下、落到普通路径去转发 ——
     * 而普通路径承载不了长连接，表现为 Worker 连接被拒（`ECONNREFUSED`），
     * 同时 `ping` 却正常（它走 Master 内部处理，不经过 Worker）。
     * 这个坑已实际踩过（logcat 触发器）。
     */
    val STREAM_METHODS = mapOf(
        "subscribeClipboardStream" to "clipboard",
        "subscribeLogcatStream" to "logcat",
    )

    // 注意：system target 由 Master 动态路由，不在静态路由表中

    // ============================================
    // 初始化配置
    // ============================================

    init {
        // 根据 DEBUG 配置设置日志级别
        Logger.setLevel(if (DEBUG) Logger.Level.DEBUG else Logger.Level.INFO)

        // ⚠️ 一致性校验：流式表里的每个 target 都必须在路由表里。
        // 漏了的话请求会被流式白名单放行、却在查路由表时拿到 null，
        // 表现是"流静默建立不起来"。启动时直接报出来，比事后排查便宜得多
        STREAM_METHODS.forEach { (method, target) ->
            check(ROUTING_TABLE.containsKey(target)) {
                "⚠️ 配置错误：流式方法 $method 指向 target「$target」，" +
                    "但它不在 ROUTING_TABLE 里 —— 该流将无法工作。" +
                    "新增流式能力时必须同时更新这两张表。"
            }
        }
    }

    fun getWorkerPort(type: WorkerType): Int {
        return when (type) {
            WorkerType.SHELL -> PORT_WORKER_SHELL
            WorkerType.ROOT -> PORT_WORKER_ROOT
        }
    }

    fun getWorkerSocketName(type: WorkerType, appPackageName: String?): String {
        val packageSuffix = (appPackageName ?: "com.chaomixian.vflow").replace('.', '_')
        val workerSuffix = when (type) {
            WorkerType.SHELL -> "shell"
            WorkerType.ROOT -> "root"
        }
        return "${UNIX_SOCKET_PREFIX}_${packageSuffix}_worker_$workerSuffix"
    }
}
