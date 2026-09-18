package com.chaomixian.vflow.core.logcat.bench

import java.io.File
import kotlin.random.Random

/**
 * 基准测试的输入数据。
 *
 * ## 为什么不写死一份样本
 *
 * 日志的**分布**（各级别占比、TAG 集中度、行长度）直接决定解析与匹配的开销。
 * 拿一份手写的样本测出来的数字，只能代表那份样本。
 *
 * 因此按优先级三级取材：
 *
 * 1. **真机采集的日志文件**（`-Dvflow.benchmark.logfile=<path>`）——最有代表性
 * 2. 合成数据（默认）——分布**刻意贴近实测**，见下
 *
 * ## 合成数据的分布依据
 *
 * 来自调试工具真机采集的观察（`docs/fork/logcat-readability-survey.md`）：
 *
 * | 特征 | 取值 | 理由 |
 * |---|---|---|
 * | V/D 占多数 | 约 70% | Android 系统日志的常态 |
 * | W/E/F 少量 | 约 15% | |
 * | 无前缀续行 | 约 8% | 多行堆栈，必须包含——它们走的**是降级路径**，成本不同 |
 * | 日志头标记行 | 每 2000 行 1 条 | `--------- beginning of main`，走的又是另一条路径 |
 * | TAG 集中 | 从 40 个里取 | 真实日志的 TAG 高度集中，这影响 `contains` 的命中率 |
 *
 * ⚠️ **续行与标记行必须包含**：它们走的是与正常行完全不同的代码路径
 * （降级继承 / 标记识别），漏掉会让基准数字偏乐观。
 */
internal object LogcatBenchmarkFixture {

    /** 近似的真实 TAG 池。集中度高是真实日志的特征。 */
    private val TAGS = listOf(
        "ActivityManager", "WindowManager", "ActivityTaskManager", "InputDispatcher",
        "ViewRootImpl", "Choreographer", "OpenGLRenderer", "SurfaceFlinger",
        "DisplayManager", "PowerManagerService", "AlarmManager", "JobScheduler",
        "ConnectivityService", "WifiService", "BluetoothAdapter", "TelephonyManager",
        "NotificationService", "PackageManager", "AppOps", "StorageManagerService",
        "BatteryService", "SensorService", "AudioFlinger", "CameraService",
        "LocationManagerService", "NetworkManagement", "UsbService", "VibratorManager",
        "vFlow", "vFlowShellManager", "VFlowCoreBridge", "TriggerServiceManager",
        "ModuleManager", "WorkflowExecutor", "AccessibilityService", "IslandCapability",
        "DebugLogger", "LogcatTriggerHandler", "PerfettoTrace", "art",
    )

    private val MESSAGES = listOf(
        "onCreate called", "setRequestedOrientation", "relayoutWindow",
        "Reporting frame rate", "Finished drawing", "handleMessage what=",
        "Scheduling restart of crashed service", "Start proc for activity",
        "acquireWakeLock", "releaseWakeLock", "updateNotification",
        "connect: network 100", "disconnect: reason 3", "onTransact code=",
        "GC freed 12345 objects", "Background concurrent copying GC freed",
    )

    /**
     * 加载一行一条的日志文本。
     *
     * @param lineCount 需要多少行（不足时循环重复已有内容）
     */
    fun load(lineCount: Int): List<String> {
        val fromFile = loadFromFile()
        if (fromFile != null) {
            return if (fromFile.size >= lineCount) {
                fromFile.subList(0, lineCount)
            } else {
                // 文件太短就循环拼到够长——重复的行数不会影响单行成本
                List(lineCount) { fromFile[it % fromFile.size] }
            }
        }
        return synthesize(lineCount)
    }

    /**
     * 从 `-Dvflow.benchmark.logfile=<path>` 指定的文件读真实日志。
     *
     * 真机上的采集文件是 `/sdcard/vFlow/logs/logcat_capture.log`；
     * 拉回电脑后这样指定：
     * ```
     * ./gradlew testDebugUnitTest --tests "*LogcatBenchmark*" \
     *   -Dvflow.benchmark=true -Dvflow.benchmark.logfile=D:/tmp/capture.log
     * ```
     */
    private fun loadFromFile(): List<String>? {
        val path = System.getenv("VFLOW_BENCHMARK_LOGFILE")
            ?: System.getProperty("vflow.benchmark.logfile")
            ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            file.readLines().filter { it.isNotBlank() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 是否用的是真实日志（报告里要写明，否则数字会被误读）。 */
    fun dataSourceLabel(): String =
        (System.getenv("VFLOW_BENCHMARK_LOGFILE") ?: System.getProperty("vflow.benchmark.logfile"))
            ?.takeIf { File(it).isFile }
            ?.let { "真实日志 $it" }
            ?: "合成数据（分布贴近实测）"

    /**
     * 直接取合成数据。
     *
     * 正确性对照测试（索引解析 vs 正则解析）用它而不是 [load]——
     * 那份对照**必须每次 CI 都跑**，不能因为本机没配真机日志就静默跳过，
     * 也不能因为那天恰好指定了个真实日志文件而改变对照集。
     */
    fun synthesizeForTest(count: Int): List<String> = synthesize(count)

    private fun synthesize(count: Int): List<String> {
        val rng = Random(20260919)   // 固定种子：结果可复现，两次跑才能对比
        return List(count) { i ->
            when {
                // 日志头标记行：不匹配 threadtime，走"标记识别"路径
                i % 2000 == 0 && i > 0 -> "--------- beginning of main"

                // 降级行（多行堆栈的续行）：无前缀，走"继承上一条"路径
                rng.nextInt(100) < 8 ->
                    "\tat com.example.app.SomeClass.someMethod(SomeClass.java:${rng.nextInt(2000)})"

                else -> {
                    val level = pickLevel(rng)
                    val tag = TAGS[rng.nextInt(TAGS.size)]
                    val msg = MESSAGES[rng.nextInt(MESSAGES.size)] + rng.nextInt(10000)
                    // ⚠️ 一律用 %5d 右对齐（真实 logcat 的 threadtime 就是定宽列）。
                    // 变宽会导致"级别字符在第 31 列"这个前提不成立 ——
                    // 偏移算错不会崩，只会让统计与解析静默失效（已踩过）。
                    val pid = 1000 + rng.nextInt(2000)
                    val tid = 1000 + rng.nextInt(2000)
                    // 时间戳的毫秒部分补零，保证总宽 18 字符
                    val ms = rng.nextInt(1000).toString().padStart(3, '0')
                    val sec = (i / 100 % 60).toString().padStart(2, '0')
                    val min = (i / 6000 % 60).toString().padStart(2, '0')
                    val hour = (i / 360000 % 24).toString().padStart(2, '0')
                    "09-19 %s:%s:%s.%s %5d %5d %c %s: %s"
                        .format(hour, min, sec, ms, pid, tid, level, tag, msg)
                }
            }
        }
    }

    /** 级别分布贴近实测：V/D 占多数。 */
    private fun pickLevel(rng: Random): Char = when (rng.nextInt(100)) {
        in 0..34 -> 'V'
        in 35..69 -> 'D'
        in 70..84 -> 'I'
        in 85..94 -> 'W'
        in 95..98 -> 'E'
        else -> 'F'
    }

    /**
     * 报告里的各级别占比。
     *
     * 用途：级别位掩码短路（见 §优化的第 2 条）的收益**完全取决于**
     * 低位级别占多大比例，所以要把它测出来而不是假设。
     */
    fun levelDistribution(lines: List<String>): Map<Char, Int> {
        val counts = linkedMapOf('V' to 0, 'D' to 0, 'I' to 0, 'W' to 0, 'E' to 0, 'F' to 0, '?' to 0)
        for (line in lines) {
            val level = levelCharOf(line) ?: '?'
            counts[level] = (counts[level] ?: 0) + 1
        }
        return counts.filterValues { it > 0 }
    }

    /**
     * 从一行的固定偏移处取级别字符（`threadtime` 是定宽前缀）。
     *
     * 这里刻意用与主解析器**不同**的方式取（主解析器用正则），
     * 免得统计本身也依赖被测代码。
     */
    private fun levelCharOf(line: String): Char? {
        if (line.length < 33) return null
        // MM-dd HH:mm:ss.SSS = 18，再空一格 = 19，pid/tid 各补 5 位宽 + 空格
        val c = line.getOrNull(31)
        return c?.takeIf { it in "VDIWEF" }
    }
}
