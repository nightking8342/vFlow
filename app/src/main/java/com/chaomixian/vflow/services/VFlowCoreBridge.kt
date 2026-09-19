// 文件: main/java/com/chaomixian/vflow/services/VFlowCoreBridge.kt
package com.chaomixian.vflow.services

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.logging.DebugLogger
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * vFlowCore 的客户端桥接器。
 * 负责与 vFlowCore 进行 Socket 通信。
 */
object VFlowCoreBridge {
    private const val TAG = "VFlowCoreBridge"
    private const val PREFS_NAME = "vFlowPrefs"
    private const val PREF_UNIX_SOCKET_ENABLED = "core_unix_socket_enabled"

    /**
     * 记录「上次**启动 Core 时**，包里的 dex 指纹是什么」。
     *
     * ## 它解决什么问题
     *
     * `MainActivity.checkCoreAutoStart()` 只判断"Core 活没活"，
     * 不判断"跑的是不是新代码"。所以改了 `core/src` 之后：
     *
     * | 场景 | 结果 |
     * |---|---|
     * | 装新 apk，Core 进程还活着 | ❌ 继续跑**旧代码**，新功能完全无效且无任何提示 |
     * | Core 被杀 / 手机重启 | ✅ 启动时自然加载新 dex |
     *
     * 这个坑实际踩过两次（logcat 触发器的 Core 侧实现、路由表注册），
     * 两次都是"代码明明写了却不生效"，排查成本很高。
     *
     * ## 为什么不靠版本号
     *
     * `vflowCoreVersion` 是手写常量，只在**功能稳定、要发版**时才该动它；
     * 而"改了 core 就该重启"是开发期的高频需求，两者节奏不同。
     * 用它当依据的话，要么频繁改版本号，要么就一直忘了改（实际就是这样）。
     *
     * ## 为什么用 dex 指纹而不是时间戳
     *
     * 指纹只在 **core 源码真的变了** 时才变。用构建时间戳的话，
     * 改一行 app 代码也会触发"core 有更新"的误报。
     *
     * ✅ 已验证 `:core:buildDex` 是**确定性**的：同样源码产出字节相同的 dex，
     * 所以不会因为重复构建而产生假变化。
     */
    private const val PREF_LAST_LAUNCHED_DEX_FINGERPRINT = "core_last_launched_dex_fingerprint"

    /** core dex 在 assets 里的名字。 */
    private const val CORE_DEX_ASSET = "vFlowCore.dex"
    private const val HOST = "127.0.0.1"
    private const val PORT = 19999
    private const val CORE_VERSION_ASSET = "vFlowCore.version"
    private const val UNIX_SOCKET_SUFFIX = "_vflow_core"

    // 用于在 ping() 中执行网络操作的 IO 线程池
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VFlowCoreBridge-IO").apply { isDaemon = true }
    }

    private var socket: Socket? = null
    private var localSocket: LocalSocket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val isConnecting = AtomicBoolean(false)
    private var activeTransport: ConnectionTransport? = null

    var isConnected = false
        private set

    var currentUid: Int = -1
        private set

    var runningVersionCode: Int = -1
        private set

    var runningVersionName: String = ""
        private set

    private var packagedVersionInfoCache: CoreVersionInfo? = null

    // 心跳机制：定期发送 ping 保持连接活跃
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VFlowCoreBridge-Heartbeat").apply { isDaemon = true }
    }
    private var heartbeatFuture: ScheduledFuture<*>? = null

    private enum class ConnectionTransport {
        TCP,
        UNIX
    }

    /**
     * 核心权限模式
     */
    enum class PrivilegeMode {
        NONE,    // 未连接
        SHELL,   // Shizuku / Shell (uid 2000)
        ROOT     // Root (uid 0)
    }

    /**
     * 命令执行模式
     */
    enum class ExecMode {
        SHELL,   // 强制使用 Shell 权限
        ROOT,    // 强制使用 Root 权限
        AUTO     // 根据用户的默认 shell 偏好自动选择
    }

    data class CoreOperationResult(
        val success: Boolean,
        val error: String? = null
    )

    data class ShellExecResult(
        val output: String,
        val exitCode: Int,
        val success: Boolean
    )

    data class ClipboardStreamEvent(
        val event: String,
        val sequence: Long,
        val text: String = "",
        val signature: String = "",
        val imageUri: String? = null
    )

    data class CoreVersionInfo(
        val versionCode: Int,
        val versionName: String
    )

    data class CoreVersionStatus(
        val packaged: CoreVersionInfo,
        val running: CoreVersionInfo?,
        val needsUpdate: Boolean
    )

    val privilegeMode: PrivilegeMode
        get() = when {
            !isConnected -> PrivilegeMode.NONE
            currentUid == 0 -> PrivilegeMode.ROOT
            else -> PrivilegeMode.SHELL // 应该是2000
        }

    val packagedVersionInfo: CoreVersionInfo
        get() = packagedVersionInfoCache ?: loadPackagedVersionInfo().also {
            if (it.versionCode > 0) {
                packagedVersionInfoCache = it
            }
        }

    fun isUnixSocketEnabled(context: Context? = null): Boolean {
        val appContext = resolveAppContext(context) ?: return false
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_UNIX_SOCKET_ENABLED, false)
    }

    fun setUnixSocketEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(PREF_UNIX_SOCKET_ENABLED, enabled)
        }
        disconnect()
    }

    fun getUnixSocketName(context: Context? = null): String {
        val packageName = resolveAppContext(context)?.packageName ?: "com.chaomixian.vflow"
        val sanitizedPackage = packageName.replace('.', '_')
        return "${sanitizedPackage}$UNIX_SOCKET_SUFFIX"
    }

    val runningVersionInfo: CoreVersionInfo?
        get() = if (runningVersionCode > 0 || runningVersionName.isNotBlank()) {
            CoreVersionInfo(
                versionCode = runningVersionCode.takeIf { it > 0 } ?: -1,
                versionName = runningVersionName.ifBlank { "unknown" }
            )
        } else {
            null
        }

    /**
     * 当前 **apk 里**的 core dex 指纹。
     *
     * @return 16 位十六进制摘要；读取失败返回 null
     */
    fun packagedDexFingerprint(): String? {
        val appContext = resolveAppContext() ?: return null
        return runCatching {
            appContext.assets.open(CORE_DEX_ASSET).use { coreDexFingerprint(it) }
        }.onFailure {
            DebugLogger.d(TAG, "读取 core dex 指纹失败: ${it.message}")
        }.getOrNull()
    }

    /**
     * **包里的 core dex 是否比"上次启动时用的"更新**。
     *
     * 这是"改了 core 需要重启"的判据，**与版本号无关** ——
     * 只有 core 源码真的变了才为 true，改 app 代码不会误报。
     *
     * 首次安装（没有记录）返回 false：那时 Core 本来就会被启动，
     * 不需要额外提示。
     */
    fun isCoreDexNewerThanRunning(): Boolean {
        val appContext = resolveAppContext() ?: return false
        val lastLaunched = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_LAUNCHED_DEX_FINGERPRINT, null)

        return shouldPromptCoreRestart(packagedDexFingerprint(), lastLaunched)
    }

    /**
     * 记录"本次启动 Core 用的是这个指纹"。
     *
     * ⚠️ **必须在真正部署 dex 之后调用**（见 `CoreLauncher.deployDex`）——
     * 提前记录的话，若部署失败，下次就不会再提示了。
     */
    fun recordLaunchedDexFingerprint(context: Context) {
        val fingerprint = packagedDexFingerprint() ?: return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_LAUNCHED_DEX_FINGERPRINT, fingerprint)
            .apply()
    }

    fun getCoreVersionStatus(): CoreVersionStatus {
        val packaged = packagedVersionInfo
        val running = runningVersionInfo
        val needsUpdate = when {
            !isConnected -> false
            running == null -> true
            running.versionCode <= 0 -> true
            packaged.versionCode > running.versionCode -> true
            else -> false
        }
        return CoreVersionStatus(
            packaged = packaged,
            running = running,
            needsUpdate = needsUpdate
        )
    }

    /**
     * 确保 vFlow Core 正在运行并已连接。
     * 如果连接失败，会请求 CoreManagementService 启动 vFlow Core，并轮询等待。
     */
    suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        // 尝试直接 Ping
        if (ping()) return@withContext true

        // 避免并发重复启动
        if (isConnecting.get()) return@withContext false
        isConnecting.set(true)

        try {
            DebugLogger.i(TAG, "vFlowCore 未响应，请求 Service 启动...")

            // 发送启动 Intent 给 Service
            val intent = Intent(context, CoreManagementService::class.java).apply {
                action = CoreManagementService.ACTION_START_CORE
            }
            context.startService(intent)

            //  轮询等待启动 (最多等待 5 秒)
            for (i in 1..20) { // 20 * 250ms = 5秒
                delay(250)
                if (establishConnection()) {
                    // 再次验证
                    if (checkConnection()) {
                        DebugLogger.i(TAG, "vFlowCore 连接成功 (尝试 $i) 权限: $$privilegeMode")
                        return@withContext true
                    }
                }
            }
            DebugLogger.e(TAG, "vFlowCore 启动超时")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "连接过程异常", e)
        } finally {
            isConnecting.set(false)
        }
        return@withContext false
    }

    /**
     * 发送 Ping 并更新状态 (UID)
     */
    private fun checkConnection(): Boolean {
        if (isConnectionClosed()) {
            if (!establishConnection()) return false
        }
        val res = sendRaw(JSONObject().put("target", "system").put("method", "ping"))
        val success = res?.optBoolean("success") == true

        if (success) {
            isConnected = true
            // 启动心跳保持连接活跃
            startHeartbeat()
            // 更新 UID 缓存
            if (res.has("uid")) {
                currentUid = res.optInt("uid")
            }
            updateVersionInfo(res)
        } else {
            close()
            currentUid = -1
        }
        return success
    }

    /**
     * 建立 Socket 连接
     */
    private fun establishConnection(): Boolean {
        close() // 清理旧连接
        return if (isUnixSocketEnabled()) {
            ConnectionTransport.UNIX
        } else {
            ConnectionTransport.TCP
        }.let { establishConnection(it) }
    }

    private fun establishConnection(transport: ConnectionTransport): Boolean {
        return try {
            when (transport) {
                ConnectionTransport.TCP -> {
                    DebugLogger.d(TAG, "正在建立到 $HOST:$PORT 的 TCP 连接...")
                    socket = Socket(HOST, PORT).apply {
                        soTimeout = 0
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    writer = PrintWriter(socket!!.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                }
                ConnectionTransport.UNIX -> {
                    val socketName = getUnixSocketName()
                    DebugLogger.d(TAG, "正在建立到 @$socketName 的 UNIX 套接字连接...")
                    localSocket = LocalSocket(LocalSocket.SOCKET_STREAM).apply {
                        connect(
                            LocalSocketAddress(
                                socketName,
                                LocalSocketAddress.Namespace.ABSTRACT
                            )
                        )
                        setSoTimeout(0)
                    }
                    writer = PrintWriter(localSocket!!.getOutputStream(), true)
                    reader = BufferedReader(InputStreamReader(localSocket!!.getInputStream()))
                }
            }
            activeTransport = transport
            DebugLogger.i(TAG, "${transport.name} 连接建立成功")
            true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "${transport.name} 连接失败: ${e.javaClass.simpleName} - ${e.message}")
            close()
            false
        }
    }

    private fun isConnectionClosed(): Boolean {
        return when (activeTransport) {
            ConnectionTransport.TCP -> socket == null || socket!!.isClosed
            ConnectionTransport.UNIX -> localSocket == null
            null -> true
        }
    }

    /**
     * 关闭连接（私有方法）
     */
    private fun close() {
        stopHeartbeat() // 停止心跳
        try { socket?.close() } catch (e: Exception) {}
        try { localSocket?.close() } catch (e: Exception) {}
        socket = null
        localSocket = null
        writer = null
        reader = null
        activeTransport = null
        isConnected = false
        clearVersionInfo()
    }

    /**
     * 断开连接（公开方法）
     * 用于主动断开与 vFlowCore 的连接
     */
    fun disconnect() {
        DebugLogger.i(TAG, "主动断开与 vFlowCore 的连接")
        close()
        currentUid = -1
    }

    /**
     * 启动心跳机制
     * 每 30 秒发送一次 ping，保持连接活跃
     */
    private fun startHeartbeat() {
        stopHeartbeat() // 先停止之前的心跳

        heartbeatFuture = heartbeatExecutor.scheduleWithFixedDelay({
            try {
                if (!isConnectionClosed() && isConnected) {
                    val req = JSONObject().put("target", "system").put("method", "ping")
                    DebugLogger.d(TAG, "Heartbeat: sending ping")
                    sendRaw(req)
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Heartbeat failed: ${e.message}")
                close()
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * 停止心跳机制
     */
    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    /**
     * 发送 ping 包检查连接是否活跃
     * 这个方法现在是 public 的，供 Service 做健康检查使用
     * 注意：此方法会在 IO 线程执行网络操作，不会阻塞调用线程
     */
    fun ping(): Boolean {
        // 使用 Future 在 IO 线程执行，避免在主线程进行网络操作
        val future = ioExecutor.submit<Boolean> {
            // 如果 Socket 对象都不存在，尝试建立一次
            if (isConnectionClosed()) {
                DebugLogger.d(TAG, "ping: socket 未建立，尝试连接...")
                if (!establishConnection()) {
                    DebugLogger.w(TAG, "ping: 建立 socket 连接失败")
                    return@submit false
                }
            }

            val req = JSONObject().put("target", "system").put("method", "ping")
            DebugLogger.d(TAG, "ping: 发送 ping 请求: $req")

            val res = sendRaw(req)

            if (res == null) {
                DebugLogger.w(TAG, "ping: 未收到响应")
                return@submit false
            }

            val success = res.optBoolean("success") == true
            DebugLogger.d(TAG, "ping: 收到响应: $res, success=$success")

            if (success) {
                // 更新 UID
                if (res.has("uid")) {
                    currentUid = res.optInt("uid")
                    isConnected = true
                    updateVersionInfo(res)
                    DebugLogger.i(TAG, "ping: 成功, uid=$currentUid, mode=$privilegeMode")
                }
            } else {
                DebugLogger.w(TAG, "ping: 响应中 success=false 或不存在")
                clearVersionInfo()
            }

            return@submit success
        }

        return try {
            // 等待结果，最多 5 秒
            future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            DebugLogger.w(TAG, "ping: 超时")
            future.cancel(true)
            close()
            false
        } catch (e: Exception) {
            DebugLogger.w(TAG, "ping: 执行异常: ${e.javaClass.simpleName} - ${e.message}")
            close()
            false
        }
    }

    private fun updateVersionInfo(response: JSONObject) {
        runningVersionCode = if (response.has("versionCode")) {
            response.optInt("versionCode", -1)
        } else {
            -1
        }
        runningVersionName = if (response.has("versionName")) {
            response.optString("versionName", "")
        } else {
            ""
        }
    }

    private fun clearVersionInfo() {
        runningVersionCode = -1
        runningVersionName = ""
    }

    private fun loadPackagedVersionInfo(): CoreVersionInfo {
        val appContext = resolveAppContext()
        if (appContext == null) {
            return CoreVersionInfo(versionCode = -1, versionName = "unknown")
        }

        val versionText = runCatching {
            appContext.assets.open(CORE_VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        }.getOrNull()

        val versionCode = versionText?.toIntOrNull() ?: -1
        val versionName = versionText?.takeIf { it.isNotBlank() } ?: "unknown"
        return CoreVersionInfo(versionCode = versionCode, versionName = versionName)
    }

    private fun resolveAppContext(context: Context? = null): Context? {
        return context?.applicationContext ?: runCatching { LogManager.applicationContext }.getOrNull()
    }

    /**
     * 底层发送方法
     */
    @Synchronized
    private fun sendRaw(json: JSONObject): JSONObject? {
        try {
            // 双重检查连接
            if (isConnectionClosed()) {
                if (!establishConnection()) return null
            }

            val jsonStr = json.toString()
            DebugLogger.d(TAG, "发送: $jsonStr")
            writer?.println(jsonStr)

            if (writer?.checkError() == true) {
                DebugLogger.w(TAG, "写入数据时发生错误")
                close()
                return null
            }

            val line = reader?.readLine()
            DebugLogger.d(TAG, "接收: $line")

            if (line == null) {
                // 连接被对端关闭
                close()
                return null
            }

            return JSONObject(line)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "通信异常: ${e.javaClass.simpleName} - ${e.message}", e)
            close()
            return null
        }
    }

    /**
     * logcat 流的**上行 writer**。
     *
     * `StreamingWrapper` 的流建立后这条连接归它独占，而 [updateLogcatTriggers]
     * 需要从另一个协程往同一个 socket 写控制帧 —— 因此必须把 writer 存下来。
     * 用锁保护是因为它同时被流协程（登记/清理）与调用方（写入）访问。
     */
    private var logcatStreamWriter: PrintWriter? = null
    private val logcatWriterLock = Any()

    // 业务 API 封装
    /**
     * 执行 Shell 命令（使用当前权限模式）
     * @param cmd 要执行的命令
     * @return 命令输出
     */
    fun exec(cmd: String): String {
        return execWithResult(cmd, if (privilegeMode == PrivilegeMode.ROOT) ExecMode.ROOT else ExecMode.SHELL).output
    }

    /**
     * 执行 Shell 命令（可指定执行模式）
     * @param cmd 要执行的命令
     * @param mode 执行模式：SHELL(强制 Shell), ROOT(强制 Root), AUTO(根据用户偏好)
     * @param context 用于 AUTO 模式下读取用户偏好设置
     * @return 命令输出
     */
    fun exec(cmd: String, mode: ExecMode, context: Context? = null): String {
        return execWithResult(cmd, mode, context).output
    }

    fun execWithResult(cmd: String, mode: ExecMode, context: Context? = null): ShellExecResult {
        // 根据 mode 决定使用哪个权限级别
        val execAsRoot = when (mode) {
            ExecMode.ROOT -> true
            ExecMode.SHELL -> false
            ExecMode.AUTO -> {
                // AUTO 模式：读取用户的默认 shell 偏好设置
                if (context != null) {
                    val prefs = context.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
                    val defaultShellMode = prefs.getString("default_shell_mode", "shizuku")
                    defaultShellMode == "root"
                } else {
                    // 没有提供 context，回退到当前权限模式
                    privilegeMode == PrivilegeMode.ROOT
                }
            }
        }

        val req = JSONObject()
            .put("target", "system")
            .put("method", "exec")
            .put("params", JSONObject()
                .put("cmd", cmd)
                .put("asRoot", execAsRoot))
        val res = sendRaw(req)
        return ShellExecResult(
            output = res?.optString("output", "") ?: "",
            exitCode = res?.optInt("exitCode", -1) ?: -1,
            success = res?.optBoolean("success", false) ?: false
        )
    }

    fun performClick(x: Int, y: Int): Boolean {
        val req = JSONObject()
            .put("target", "input")
            .put("method", "tap")
            .put("params", JSONObject().put("x", x).put("y", y))
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean {
        val req = JSONObject()
            .put("target", "input")
            .put("method", "swipe")
            .put("params", JSONObject()
                .put("x1", x1).put("y1", y1)
                .put("x2", x2).put("y2", y2)
                .put("duration", duration))
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    fun performUinputTap(x: Int, y: Int): CoreOperationResult {
        val req = JSONObject()
            .put("target", "uinput")
            .put("method", "tap")
            .put("params", JSONObject()
                .put("x", x)
                .put("y", y))
        val res = sendRaw(req)
        return CoreOperationResult(
            success = res?.optBoolean("success") == true,
            error = res?.optString("error")?.takeIf { it.isNotBlank() }
        )
    }

    fun performUinputLongPress(x: Int, y: Int, duration: Long): CoreOperationResult {
        val req = JSONObject()
            .put("target", "uinput")
            .put("method", "longPress")
            .put("params", JSONObject()
                .put("x", x)
                .put("y", y)
                .put("duration", duration))
        val res = sendRaw(req)
        return CoreOperationResult(
            success = res?.optBoolean("success") == true,
            error = res?.optString("error")?.takeIf { it.isNotBlank() }
        )
    }

    fun performUinputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): CoreOperationResult {
        val req = JSONObject()
            .put("target", "uinput")
            .put("method", "swipe")
            .put("params", JSONObject()
                .put("x1", x1)
                .put("y1", y1)
                .put("x2", x2)
                .put("y2", y2)
                .put("duration", duration))
        val res = sendRaw(req)
        return CoreOperationResult(
            success = res?.optBoolean("success") == true,
            error = res?.optString("error")?.takeIf { it.isNotBlank() }
        )
    }

    fun inputText(text: String): Boolean {
        val req = JSONObject()
            .put("target", "input")
            .put("method", "inputText")
            .put("params", JSONObject().put("text", text))
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    fun pressKey(keyCode: Int): Boolean {
        val req = JSONObject()
            .put("target", "input")
            .put("method", "key")
            .put("params", JSONObject().put("code", keyCode))
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    fun setClipboard(text: String): Boolean {
        val req = JSONObject()
            .put("target", "clipboard")
            .put("method", "setClipboard")
            .put("params", JSONObject().put("text", text))
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    fun getClipboard(): String {
        val req = JSONObject()
            .put("target", "clipboard")
            .put("method", "getClipboard")
        val res = sendRaw(req)
        return res?.optString("text", "") ?: ""
    }

    suspend fun streamClipboardEvents(onEvent: suspend (ClipboardStreamEvent) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val subscribeRequest = JSONObject()
            .put("target", "clipboard")
            .put("method", "subscribeClipboardStream")

        return@withContext try {
            if (isUnixSocketEnabled()) {
                val socketName = getUnixSocketName()
                LocalSocket(LocalSocket.SOCKET_STREAM).use { streamSocket ->
                    bindStreamCancellation(streamSocket)
                    streamSocket.connect(
                        LocalSocketAddress(
                            socketName,
                            LocalSocketAddress.Namespace.ABSTRACT
                        )
                    )
                    streamSocket.setSoTimeout(0)
                    val streamWriter = PrintWriter(streamSocket.outputStream, true)
                    val streamReader = BufferedReader(InputStreamReader(streamSocket.inputStream))
                    streamWriter.println(subscribeRequest.toString())
                    if (streamWriter.checkError()) {
                        return@withContext false
                    }
                    consumeClipboardStream(streamReader, onEvent)
                }
            } else {
                Socket(HOST, PORT).use { streamSocket ->
                    bindStreamCancellation(streamSocket)
                    streamSocket.soTimeout = 0
                    streamSocket.keepAlive = true
                    streamSocket.tcpNoDelay = true
                    val streamWriter = PrintWriter(streamSocket.getOutputStream(), true)
                    val streamReader = BufferedReader(InputStreamReader(streamSocket.getInputStream()))
                    streamWriter.println(subscribeRequest.toString())
                    if (streamWriter.checkError()) {
                        return@withContext false
                    }
                    consumeClipboardStream(streamReader, onEvent)
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "剪贴板事件流异常: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        }
    }

    private suspend fun bindStreamCancellation(closeable: Closeable) {
        coroutineContext[Job]?.invokeOnCompletion {
            runCatching { closeable.close() }
        }
    }

    private suspend fun consumeClipboardStream(
        reader: BufferedReader,
        onEvent: suspend (ClipboardStreamEvent) -> Unit
    ): Boolean {
        while (true) {
            val line = reader.readLine() ?: return false
            DebugLogger.d(TAG, "剪贴板事件流接收: $line")
            val payload = JSONObject(line)
            if (!payload.optBoolean("success")) {
                return false
            }
            onEvent(
                ClipboardStreamEvent(
                    event = payload.optString("event"),
                    sequence = payload.optLong("sequence", -1L),
                    text = payload.optString("text", ""),
                    signature = payload.optString("signature", ""),
                    imageUri = payload.optString("imageUri").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    /**
     * 订阅 logcat 事件流（**双工**）。
     *
     * 与 [streamClipboardEvents] 的区别：这条连接**保持 writer**，
     * 订阅之后仍可向 Core 发控制帧（条件更新），见 [updateLogcatTriggers]。
     *
     * ⚠️ **必须保留 writer 的生命周期**：把 writer 存到 [logcatStreamWriter]，
     * 供并发的 [updateLogcatTriggers] 使用。这是 `StreamingWrapper` 加 reader
     * 参数之后新增的能力（见 core 侧 `StreamingWrapper` 的说明）。
     *
     * @param conditionArray 初始条件列表。订阅帧自带全量条件 ——
     *   这样 Core 不必等第二次通信才开始工作，且"重连即重发"的语义天然正确
     * @param onEvent 每收到一个事件回调一次
     * @return 流是否曾成功建立（断开时返回 false，由调用方决定是否重连）
     */
    suspend fun streamLogcatEvents(
        conditionArray: JSONArray,
        onEvent: suspend (JSONObject) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val subscribeRequest = JSONObject()
            .put("target", "logcat")
            .put("method", "subscribeLogcatStream")
            .put("params", JSONObject().put("conditions", conditionArray))

        return@withContext try {
            if (isUnixSocketEnabled()) {
                val socketName = getUnixSocketName()
                LocalSocket(LocalSocket.SOCKET_STREAM).use { streamSocket ->
                    bindStreamCancellation(streamSocket)
                    streamSocket.connect(
                        LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                    )
                    streamSocket.soTimeout = 0
                    val writer = PrintWriter(streamSocket.outputStream, true)
                    val reader = BufferedReader(InputStreamReader(streamSocket.inputStream))
                    // ⚠️ 订阅帧发出后**立刻**登记 writer，不要等 consumeLogcatStream。
                    // 否则存在竞态：addTrigger 触发的条件下发可能早于注册，
                    // 那时 writer 还是 null → 条件丢失 → 触发器不工作
                    synchronized(logcatWriterLock) { logcatStreamWriter = writer }
                    writer.println(subscribeRequest.toString())
                    if (writer.checkError()) return@withContext false
                    consumeLogcatStream(reader, onEvent)
                }
            } else {
                Socket(HOST, PORT).use { streamSocket ->
                    bindStreamCancellation(streamSocket)
                    streamSocket.soTimeout = 0
                    streamSocket.keepAlive = true
                    streamSocket.tcpNoDelay = true
                    val writer = PrintWriter(streamSocket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(streamSocket.getInputStream()))
                    synchronized(logcatWriterLock) { logcatStreamWriter = writer }
                    writer.println(subscribeRequest.toString())
                    if (writer.checkError()) return@withContext false
                    consumeLogcatStream(reader, onEvent)
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "logcat 事件流异常: ${e.javaClass.simpleName} - ${e.message}", e)
            false
        } finally {
            // 无论怎么退出都要清掉 writer，否则 updateLogcatTriggers 会往一个
            // 已关闭的 socket 上写，且 checkError 不一定会立刻反映出来
            synchronized(logcatWriterLock) { logcatStreamWriter = null }
        }
    }

    private suspend fun consumeLogcatStream(
        reader: BufferedReader,
        onEvent: suspend (JSONObject) -> Unit,
    ): Boolean {
        // 登记已在连接建立处完成（见 streamLogcatEvents 的说明）
        while (true) {
            val line = reader.readLine() ?: return false
            val payload = try {
                JSONObject(line)
            } catch (e: Exception) {
                DebugLogger.d(TAG, "logcat 流收到非法 JSON，跳过: ${e.message}")
                continue
            }
            if (!payload.optBoolean("success")) {
                DebugLogger.w(TAG, "logcat 流被 Core 拒绝: $line")
                return false
            }

            // ⚠️ 这条日志是**区分两类故障的关键**：
            // - 收到 "ready" → Core 认识 subscribeLogcatStream，是新代码，流真的建立了
            // - 从未收到     → Core 不认识这个 method（旧代码），或连接被立刻关掉
            //
            // 之所以需要它：Core 的版本号是写死的常量（`vflowCoreVersion`），
            // 不随 app 版本变，所以光看 ping 的 versionCode 判断不出跑的是不是新代码。
            if (payload.optString("event") == "ready") {
                DebugLogger.i(TAG, "logcat 流已建立（Core 已就绪）")
            }

            onEvent(payload)
        }
    }

    /**
     * 向已建立的 logcat 流下发新的条件列表（**全量替换**）。
     *
     * ⚠️ **这是双工协议的上行方向**，也是 `StreamingWrapper` 需要 reader 参数的原因。
     *
     * @return true 表示已写入；false 表示当前没有活跃的流
     *   （此时不必重试——App 侧的重连逻辑会带着完整条件重新订阅）
     */
    fun updateLogcatTriggers(conditionArray: JSONArray): Boolean {
        val frame = JSONObject()
            .put("method", "updateTriggers")
            .put("conditions", conditionArray)

        val writer = synchronized(logcatWriterLock) { logcatStreamWriter } ?: return false

        return try {
            writer.println(frame.toString())
            !writer.checkError()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "下发 logcat 条件失败: ${e.message}")
            false
        }
    }

    // Power Management APIs
    fun wakeUp(): Boolean {
        val req = JSONObject()
            .put("target", "power")
            .put("method", "wakeUp")
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    fun goToSleep(): Boolean {
        val req = JSONObject()
            .put("target", "power")
            .put("method", "goToSleep")
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    /**
     * 获取屏幕当前状态
     * @return true = 屏幕亮屏（可交互），false = 屏幕熄屏
     */
    fun isInteractive(): Boolean {
        val req = JSONObject()
            .put("target", "power")
            .put("method", "isInteractive")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    // WiFi Management APIs
    fun setWifiEnabled(enabled: Boolean): Boolean {
        val req = JSONObject()
            .put("target", "wifi")
            .put("method", "setWifiEnabled")
            .put("params", JSONObject().put("enabled", enabled))
        return sendRaw(req)?.optBoolean("success", false) ?: false
    }

    /**
     * 获取 WiFi 当前状态
     */
    fun isWifiEnabled(): Boolean {
        val req = JSONObject()
            .put("target", "wifi")
            .put("method", "isEnabled")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    /**
     * 切换 WiFi 状态（开启→关闭，关闭→开启）
     * @return 返回切换后的状态
     */
    fun toggleWifi(): Boolean {
        val req = JSONObject()
            .put("target", "wifi")
            .put("method", "toggle")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    fun setHotspotEnabled(enabled: Boolean): Boolean {
        val req = JSONObject()
            .put("target", "hotspot")
            .put("method", "setEnabled")
            .put("params", JSONObject().put("enabled", enabled))
        return sendRaw(req)?.optBoolean("success", false) ?: false
    }

    fun isHotspotEnabled(): Boolean {
        val req = JSONObject()
            .put("target", "hotspot")
            .put("method", "isEnabled")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    fun toggleHotspot(): Boolean {
        val req = JSONObject()
            .put("target", "hotspot")
            .put("method", "toggle")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    // Bluetooth Management APIs
    fun setBluetoothEnabled(enabled: Boolean): Boolean {
        val req = JSONObject()
            .put("target", "bluetooth_manager")
            .put("method", "setBluetoothEnabled")
            .put("params", JSONObject().put("enabled", enabled))
        return sendRaw(req)?.optBoolean("success", false) ?: false
    }

    /**
     * 获取蓝牙当前状态
     */
    fun isBluetoothEnabled(): Boolean {
        val req = JSONObject()
            .put("target", "bluetooth_manager")
            .put("method", "isEnabled")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    /**
     * 切换蓝牙状态（开启→关闭，关闭→开启）
     * @return 返回切换后的状态
     */
    fun toggleBluetooth(): Boolean {
        val req = JSONObject()
            .put("target", "bluetooth_manager")
            .put("method", "toggle")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    // NFC Management APIs
    fun setNfcEnabled(enabled: Boolean): Boolean {
        val req = JSONObject()
            .put("target", "nfc")
            .put("method", "setNfcEnabled")
            .put("params", JSONObject().put("enabled", enabled))
        return sendRaw(req)?.optBoolean("success", false) ?: false
    }

    /**
     * 获取NFC当前状态
     */
    fun isNfcEnabled(): Boolean {
        val req = JSONObject()
            .put("target", "nfc")
            .put("method", "isEnabled")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    /**
     * 切换NFC状态（开启→关闭，关闭→开启）
     * @return 返回切换后的状态
     */
    fun toggleNfc(): Boolean {
        val req = JSONObject()
            .put("target", "nfc")
            .put("method", "toggle")
        val res = sendRaw(req)
        return res?.optBoolean("enabled", false) ?: false
    }

    // Audio Management APIs
    /**
     * 设置音量
     * @param streamType 音频流类型 (0=通话, 1=系统, 2=铃声, 3=音乐, 4=闹钟, 5=通知)
     * @param volume 音量值 (0-100)
     * @return Triple<是否成功, 当前音量, 最大音量>
     */
    fun setVolume(streamType: Int, volume: Int): Triple<Boolean, Int, Int> {
        val req = JSONObject()
            .put("target", "audio")
            .put("method", "setVolume")
            .put("params", JSONObject()
                .put("streamType", streamType)
                .put("volume", volume))
        val res = sendRaw(req)
        val success = res?.optBoolean("success", false) ?: false
        val currentLevel = res?.optInt("currentLevel", 0) ?: 0
        val maxLevel = res?.optInt("maxLevel", 0) ?: 0
        return Triple(success, currentLevel, maxLevel)
    }

    /**
     * 获取音量
     * @param streamType 音频流类型
     * @return Pair<当前音量, 最大音量>
     */
    fun getVolume(streamType: Int): Pair<Int, Int> {
        val req = JSONObject()
            .put("target", "audio")
            .put("method", "getVolume")
            .put("params", JSONObject().put("streamType", streamType))
        val res = sendRaw(req)
        val currentLevel = res?.optInt("currentLevel", 0) ?: 0
        val maxLevel = res?.optInt("maxLevel", 0) ?: 0
        return Pair(currentLevel, maxLevel)
    }

    /**
     * 调整音量
     * @param streamType 音频流类型
     * @param direction 调整方向 (1=升高, -1=降低, 0=保持)
     * @return Triple<是否成功, 当前音量, 最大音量>
     */
    fun adjustVolume(streamType: Int, direction: Int): Triple<Boolean, Int, Int> {
        val req = JSONObject()
            .put("target", "audio")
            .put("method", "adjustVolume")
            .put("params", JSONObject()
                .put("streamType", streamType)
                .put("direction", direction))
        val res = sendRaw(req)
        val success = res?.optBoolean("success", false) ?: false
        val currentLevel = res?.optInt("currentLevel", 0) ?: 0
        val maxLevel = res?.optInt("maxLevel", 0) ?: 0
        return Triple(success, currentLevel, maxLevel)
    }

    /**
     * 静音/取消静音
     * @param streamType 音频流类型
     * @param mute true=静音, false=取消静音
     * @return Triple<是否成功, 当前音量, 最大音量>
     */
    fun muteVolume(streamType: Int, mute: Boolean): Triple<Boolean, Int, Int> {
        val req = JSONObject()
            .put("target", "audio")
            .put("method", "mute")
            .put("params", JSONObject()
                .put("streamType", streamType)
                .put("mute", mute))
        val res = sendRaw(req)
        val success = res?.optBoolean("success", false) ?: false
        val currentLevel = res?.optInt("currentLevel", 0) ?: 0
        val maxLevel = res?.optInt("maxLevel", 0) ?: 0
        return Triple(success, currentLevel, maxLevel)
    }

    /**
     * 音量信息数据类
     */
    data class VolumeInfo(
        val musicCurrent: Int,
        val musicMax: Int,
        val notificationCurrent: Int,
        val notificationMax: Int,
        val ringCurrent: Int,
        val ringMax: Int,
        val systemCurrent: Int,
        val systemMax: Int,
        val alarmCurrent: Int,
        val alarmMax: Int,
        val callCurrent: Int,
        val callMax: Int
    )

    /**
     * 获取所有音量
     * @return VolumeInfo 包含所有音频流的音量信息
     */
    fun getAllVolumes(): VolumeInfo? {
        val req = JSONObject()
            .put("target", "audio")
            .put("method", "getAllVolumes")
        val res = sendRaw(req)
        if (res?.optBoolean("success", false) == true) {
            val volumes = res.optJSONObject("volumes")
            if (volumes != null) {
                return VolumeInfo(
                    musicCurrent = volumes.optJSONObject("music")?.optInt("current", 0) ?: 0,
                    musicMax = volumes.optJSONObject("music")?.optInt("max", 0) ?: 0,
                    notificationCurrent = volumes.optJSONObject("notification")?.optInt("current", 0) ?: 0,
                    notificationMax = volumes.optJSONObject("notification")?.optInt("max", 0) ?: 0,
                    ringCurrent = volumes.optJSONObject("ring")?.optInt("current", 0) ?: 0,
                    ringMax = volumes.optJSONObject("ring")?.optInt("max", 0) ?: 0,
                    systemCurrent = volumes.optJSONObject("system")?.optInt("current", 0) ?: 0,
                    systemMax = volumes.optJSONObject("system")?.optInt("max", 0) ?: 0,
                    alarmCurrent = volumes.optJSONObject("alarm")?.optInt("current", 0) ?: 0,
                    alarmMax = volumes.optJSONObject("alarm")?.optInt("max", 0) ?: 0,
                    callCurrent = 0, // 通话音量不常用，默认为0
                    callMax = 0
                )
            }
        }
        return null
    }

    /**
     * 截图
     * @return 返回base64编码后的图片
     */
    fun captureScreen(): String {
        val req = JSONObject()
            .put("target", "screenshot")
            .put("method", "capture")
        val res = sendRaw(req)
        return res?.optString("data", "") ?: ""
    }

    /**
     * 扩展截图方法，支持格式、质量和尺寸参数
     * @param format 输出格式 ("png" 或 "jpeg")
     * @param quality JPEG质量 (1-100)，仅对JPEG有效
     * @param maxWidth 最大宽度，0表示不限制
     * @param maxHeight 最大高度，0表示不限制
     * @return Base64编码的图像数据
     */
    fun captureScreenEx(format: String = "png", quality: Int = 90, maxWidth: Int = 0, maxHeight: Int = 0): String {
        val req = JSONObject()
            .put("target", "screenshot")
            .put("method", "captureScreen")
            .put("params", JSONObject().apply {
                put("format", format)
                put("quality", quality)
                put("maxWidth", maxWidth)
                put("maxHeight", maxHeight)
                put("includeBase64", true)
            })
        val res = sendRaw(req)
        return res?.optString("data", "") ?: ""
    }

    /**
     * 获取屏幕尺寸
     * @return Pair<宽度, 高度>
     */
    fun getScreenSize(): Pair<Int, Int> {
        val req = JSONObject()
            .put("target", "screenshot")
            .put("method", "getScreenSize")
            .put("params", JSONObject())
        val res = sendRaw(req)
        if (res?.optBoolean("success", false) == true) {
            val width = res.optInt("width", 0)
            val height = res.optInt("height", 0)
            return Pair(width, height)
        }
        return Pair(0, 0)
    }

    // Activity Management APIs
    fun forceStopPackage(packageName: String): Boolean {
        val req = JSONObject()
            .put("target", "activity")
            .put("method", "forceStopPackage")
            .put("params", JSONObject().put("package", packageName))
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    // System Control APIs
    /**
     * 请求 vFlow Core 优雅退出
     */
    fun shutdown(): Boolean {
        val req = JSONObject()
            .put("target", "system")
            .put("method", "exit")
        return sendRaw(req)?.optBoolean("success") ?: false
    }

    /**
     * 重启 vFlow Core
     * 用于 DEX 更新后加载新代码
     */
    suspend fun restart(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            DebugLogger.i(TAG, "请求重启 vFlow Core...")
            val intent = Intent(context, CoreManagementService::class.java).apply {
                action = CoreManagementService.ACTION_RESTART_CORE
            }
            context.startService(intent)

            // 等待重启完成
            for (i in 1..20) { // 20 * 250ms = 5秒
                delay(250)
                if (establishConnection()) {
                    if (checkConnection()) {
                        DebugLogger.i(TAG, "vFlow Core 重启成功 (尝试 $i) 权限: $privilegeMode")
                        return@withContext true
                    }
                }
            }
            DebugLogger.e(TAG, "vFlow Core 重启超时")
            false
        } catch (e: Exception) {
            DebugLogger.e(TAG, "重启过程异常", e)
            false
        }
    }

    /**
     * 回放触摸序列
     * @param touchSequenceJson JSON格式的触摸序列数据
     * @param speedMultiplier 回放速度倍率，1.0为正常速度
     * @return 是否成功回放
     */
    fun replayTouchSequence(touchSequenceJson: String, speedMultiplier: Float = 1.0f): Boolean {
        val req = JSONObject()
            .put("target", "input")
            .put("method", "replaySequence")
            .put("params", JSONObject().apply {
                put("sequence", touchSequenceJson)
                put("speedMultiplier", speedMultiplier)
            })
        return sendRaw(req)?.optBoolean("success") ?: false
    }
}

/**
 * 算一个输入流的**内容指纹**（SHA-256 前 16 位十六进制）。
 *
 * 抽成顶层函数是为了**可单测** —— 指纹算错的表现是
 * 「改了 core 却不提示重启」，用户会继续跑旧代码且毫无察觉。
 *
 * ⚠️ 必须**逐块读**而不是 `readBytes()`：dex 有 2.7MB，
 * 整个读进内存在低端设备上不划算。
 */
internal fun coreDexFingerprint(input: java.io.InputStream): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        digest.update(buf, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * 判定「是否需要提示重启 Core」。
 *
 * ## 为什么独立成纯函数
 *
 * 这是本机制的核心判断，逻辑简单但**错法很隐蔽**：
 * 返回错的 false 会让用户静默地继续跑旧代码（这个坑已实际踩过两次），
 * 返回错的 true 会让提示变得不可信、最终被无视。
 *
 * @param currentFingerprint apk 里 dex 的指纹；null 表示读取失败
 * @param lastLaunchedFingerprint 上次启动 Core 时记录的指纹；null 表示从未记录
 * @return true = 应当提示重启
 */
internal fun shouldPromptCoreRestart(
    currentFingerprint: String?,
    lastLaunchedFingerprint: String?,
): Boolean {
    // 读不出当前指纹：不提示。提示了也没法让用户判断真假，
    // 而"提示不可信"比"没提示"更糟
    if (currentFingerprint.isNullOrBlank()) return false

    // 从未记录（首次安装 / 刚从更早的版本升级上来）：
    // 不提示 —— 那时 Core 本来就会被启动，不需要额外提醒
    if (lastLaunchedFingerprint.isNullOrBlank()) return false

    return currentFingerprint != lastLaunchedFingerprint
}
