package com.chaomixian.vflow.core.logcat.bench

import com.chaomixian.vflow.core.logcat.LogcatParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * logcat 触发器的**匹配开销基准**。
 *
 * ## 它要回答的问题
 *
 * 设计文档 §5.3 决定「**不**把过滤下推到 logcat 命令行」，理由是热更新与
 * 进程数。代价是**全量日志行都要进 Core 解析并逐条件匹配**。
 * 这个代价能不能接受，是本节要给出数字的地方。
 *
 * ## 默认不跑
 *
 * 它量的是**耗时**，不是行为，因此不该进日常 `./gradlew test`
 * （在 CI 上跑出来的数也没有可比性）。默认用 [assumeTrue] 跳过。
 *
 * 手动运行：
 * ```
 * VFLOW_BENCHMARK=true ./gradlew testDebugUnitTest --tests "*LogcatTriggerBenchmark*"
 * ```
 *
 * 用真机抓的日志（比合成数据可信）：
 * ```
 * ./gradlew testDebugUnitTest --tests "*LogcatTriggerBenchmark*" -Dvflow.benchmark=true \
 *   -Dvflow.benchmark.logfile=D:/tmp/logcat_capture.log
 * ```
 *
 * ## 怎么读结果
 *
 * 报表里最关键的一行是 **`推算 Core CPU 占用`**，它把 µs/行 换算成
 * 「在某某日志速率下 Core 吃多少单核」。判断标准是：
 *
 * | 结论 | 后续动作 |
 * |---|---|
 * | 高负载下仍 < 5% | 匹配成本可忽略，**不做**索引扫描优化（省下来的复杂度更值钱） |
 * | 10% 上下 | 做优化的第 2 条（级别位掩码），视数据决定第 3 条 |
 * | > 30% | 第 3 条（索引扫描）必须做，且要复核是否漏算了其他环节 |
 *
 * ⚠️ **本基准不覆盖的部分**（它们不受"是否下推过滤"影响，但会影响整体性能）：
 * - JSON 构建与 socket 写（取决于**命中率**，不是日志量）→ 见文档 §6.5.4
 * - 背压与丢弃策略 → 同上
 * - 跨进程传输
 */
class LogcatTriggerBenchmarkTest {

    /**
     * 是否运行。
     *
     * ⚠️ **用环境变量而非 `-D` 系统属性**：Gradle 的 `-D` 设的是**守护进程**的属性，
     * 不会自动传进 fork 出来的测试 JVM，`System.getProperty` 读到的是 null——
     * 表现是测试"跑过了"但其实被 `assumeTrue` 静默跳过。
     *（这一点已经踩过一次：报告文件没生成，测试结果里却是绿色。）
     *
     * 环境变量则会随 fork 的 JVM 继承下来，无需改 `build.gradle.kts`。
     * 系统属性仍保留一路读取，方便将来在构建脚本里显式转发。
     */
    private val enabled: Boolean
        get() = System.getenv("VFLOW_BENCHMARK") == "true" ||
            System.getProperty("vflow.benchmark") == "true"

    // ── 场景定义 ────────────────────────────────────────────────

    /**
     * 触发器配置场景。**覆盖用户会真实配出来的形态**：
     * - 宽条件（`any` / 低级别）—— 成本下限
     * - 窄条件（`equals` + 高级别）—— 常见形态
     * - regex —— 最贵的形态，必须单列
     */
    private data class Scenario(val name: String, val conditions: List<TriggerPipeline.Condition>)

    private fun scenarios(): List<Scenario> = listOf(
        Scenario(
            "1 个触发器 · TAG contains + message contains（最常见）",
            listOf(
                cond("t1", TriggerPipeline.Matcher.Contains("vFlow"), TriggerPipeline.Matcher.Contains("error"), 'I')
            )
        ),
        Scenario(
            "1 个触发器 · 全 any 且 minLevel=V（成本上限）",
            listOf(
                cond("t1", TriggerPipeline.Matcher.Any, TriggerPipeline.Matcher.Any, 'V')
            )
        ),
        Scenario(
            "1 个触发器 · regex（最贵）",
            listOf(
                cond(
                    "t1",
                    TriggerPipeline.Matcher.RegexMatcher(Regex("vFlow.*")),
                    TriggerPipeline.Matcher.RegexMatcher(Regex("(error|fail|exception)", RegexOption.IGNORE_CASE)),
                    'E'
                )
            )
        ),
        Scenario(
            "3 个触发器 · 混合（实数场景）",
            listOf(
                cond("t1", TriggerPipeline.Matcher.Contains("vFlow"), TriggerPipeline.Matcher.Any, 'I'),
                cond("t2", TriggerPipeline.Matcher.Equals("ActivityManager"), TriggerPipeline.Matcher.Contains("crash"), 'E'),
                cond("t3", TriggerPipeline.Matcher.Any, TriggerPipeline.Matcher.Contains("timeout"), 'W'),
            )
        ),
        Scenario(
            "10 个触发器 · 混合（压力）",
            (1..10).map { i ->
                cond(
                    "t$i",
                    if (i % 2 == 0) TriggerPipeline.Matcher.Contains("Manager")
                    else TriggerPipeline.Matcher.Equals("Tag$i"),
                    TriggerPipeline.Matcher.Contains("msg$i"),
                    'I'
                )
            }
        ),
    )

    private fun cond(
        id: String,
        tag: TriggerPipeline.Matcher,
        msg: TriggerPipeline.Matcher,
        level: Char,
    ) = TriggerPipeline.Condition(id, tag, msg, level)

    // ── 主基准 ──────────────────────────────────────────────────

    @Test
    fun benchmarkMatchThroughput() {
        assumeTrue("默认跳过（用 VFLOW_BENCHMARK=true 开启）", enabled)

        val lines = LogcatBenchmarkFixture.load(LINE_COUNT)
        val report = StringBuilder()

        report.appendLine("=".repeat(78))
        report.appendLine("logcat 触发器匹配基准")
        report.appendLine("=".repeat(78))
        report.appendLine("数据源  : ${LogcatBenchmarkFixture.dataSourceLabel()}")
        report.appendLine("样本行数: ${lines.size}")
        report.appendLine("运行环境: JVM ${System.getProperty("java.version")} · " +
            "${Runtime.getRuntime().availableProcessors()} 核")
        report.appendLine()

        val dist = LogcatBenchmarkFixture.levelDistribution(lines)
        report.appendLine("级别分布: " + dist.entries.joinToString("  ") { "${it.key}=${it.value * 100 / lines.size}%" })
        report.appendLine()

        // ---- 1. 解析成本（两种实现对比）----
        report.appendLine("-".repeat(78))
        report.appendLine("① 解析成本（每行）")
        report.appendLine("-".repeat(78))
        report.appendLine("%-14s %14s %14s".format("实现", "ns/行", "相对"))

        val regexNs = measureNsPerLine { measureRegexParse(lines) }
        val indexedNs = measureNsPerLine { measureIndexedParse(lines) }

        report.appendLine("%-14s %14d %14s".format("正则（现行）", regexNs, "1.00x"))
        report.appendLine(
            "%-14s %14d %14s".format(
                "索引扫描（候选）", indexedNs,
                "%.2fx".format(indexedNs.toDouble() / regexNs)
            )
        )
        report.appendLine()

        // ---- 2. 匹配成本 ----
        report.appendLine("-".repeat(78))
        report.appendLine("② 匹配成本（解析之后，逐条件）")
        report.appendLine("-".repeat(78))
        // ⚠️ 必须同时给出「掩码放行率」：minLevel 高的场景绝大多数行在
        // 位掩码那一步就短路了，ns/行 会低得好看，但那是**条件本身窄**而不是
        // 匹配快。不给这一列，不同场景之间的数字没法横向比。
        report.appendLine("%-40s %10s %10s %10s".format("场景", "ns/行", "掩码放行", "命中率"))

        val parsed = lines.mapNotNull { TriggerPipeline.parseIndexed(it) }
        val matchResults = mutableMapOf<String, Long>()

        for (s in scenarios()) {
            val mask = TriggerPipeline.levelMask(s.conditions)

            // 命中率单独数一遍。**不能写在计时循环里**——
            // 测量会跑多轮，计数器跨轮累加，命中率会算出 >100% 的荒谬值。
            var hits = 0
            for (p in parsed) {
                if (TriggerPipeline.matches(
                        p.raw, p.tagStart, p.tagEnd, p.raw, p.level, s.conditions, mask
                    )
                ) hits++
            }

            val ns = measureNsPerLine {
                var sink = 0
                for (p in parsed) {
                    if (TriggerPipeline.matches(
                            p.raw, p.tagStart, p.tagEnd, p.raw, p.level, s.conditions, mask
                        )
                    ) sink++
                }
                blackhole(sink)
            }
            matchResults[s.name] = ns
            val hitRate = if (parsed.isEmpty()) 0.0 else hits * 100.0 / parsed.size

            // 位掩码放行率：这一比例的行才会真正走到 TAG / message 比较
            val passMask = parsed.count { p -> mask and (1 shl (p.level - 'A')) != 0 }
            val passRate = if (parsed.isEmpty()) 0.0 else passMask * 100.0 / parsed.size

            report.appendLine("%-40s %10d %9.1f%% %9.1f%%".format(s.name, ns, passRate, hitRate))
        }
        report.appendLine()

        // ---- 3. 端到端与 CPU 推算 ----
        report.appendLine("-".repeat(78))
        report.appendLine("③ 端到端（解析 + 匹配）与 CPU 推算")
        report.appendLine("-".repeat(78))
        report.appendLine("%-40s %10s %10s %10s".format("场景", "ns/行", "1k行/s", "10k行/s"))

        for (s in scenarios()) {
            val mask = TriggerPipeline.levelMask(s.conditions)
            val totalNs = indexedNs + matchResults.getValue(s.name)
            val p1k = percentOfOneCore(totalNs, 1_000)
            val p10k = percentOfOneCore(totalNs, 10_000)
            report.appendLine("%-40s %10d %9.1f%% %9.1f%%".format(s.name, totalNs, p1k, p10k))
        }
        report.appendLine()
        report.appendLine("注：百分比 = 该日志速率下占用的单核比例。")
        report.appendLine("    真实设备 CPU 弱于本机 JVM，实测应更差，请按 2~5 倍保守估计。")
        report.appendLine()

        // ---- 4. 级别位掩码的收益 ----
        report.appendLine("-".repeat(78))
        report.appendLine("④ 级别位掩码短路的收益（优化第 2 条）")
        report.appendLine("-".repeat(78))

        val allInfo = listOf(cond("t", TriggerPipeline.Matcher.Contains("x"), TriggerPipeline.Matcher.Contains("y"), 'I'))
        val maskOn = TriggerPipeline.levelMask(allInfo)
        val skipRatio = lowLevelRatio(lines)
        report.appendLine("所有触发器 minLevel=I 时，V/D 两级（约占 %d%%）整行免扫描丢弃".format(skipRatio))
        report.appendLine("掩码 = 0b${Integer.toBinaryString(maskOn)}")
        report.appendLine()

        val text = report.toString()
        println(text)

        // 同时落盘。Gradle 默认不在控制台显示测试里的 println，
        // 写文件既能直接看，也便于改完优化后对比前后两次的数字
        // （这正是"先测量再优化"的意义所在）。
        val out = java.io.File(System.getProperty("user.dir"), "build/reports/logcat-trigger-benchmark.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)
        println("报告已写入: ${out.absolutePath}")

        // 基准断言：只保证它跑完了并产出了合理数字，不设性能门槛
        // （门槛会因机器而异，写死了只会变成"改机器就红"的噪声测试）
        assertTrue("正则解析应产生正数耗时", regexNs > 0)
        assertTrue("索引扫描应产生正数耗时", indexedNs > 0)
        assertTrue("样本应被解析出绝大多数行", parsed.size > lines.size * 9 / 10)
    }

    /**
     * 正确性对照：索引扫描与正则解析**必须给出相同的 tag / level**。
     *
     * ⚠️ 这不是性能测试，**它会随默认测试一起跑**（不需要开开关）。
     * 索引扫描是靠偏移硬算的，写错了不会崩、只会**静默解析出错误的 tag**——
     * 那样触发器就永远不命中了，而用户完全无从察觉。
     *
     * 这条对照是整个优化能否落地的前提：只要它绿着，
     * 换解析器就是安全的；它红了就说明偏移算错了。
     */
    @Test
    fun indexedParserAgreesWithRegexParser() {
        val lines = LogcatBenchmarkFixture.synthesizeForTest(5_000)
        var compared = 0

        for (line in lines) {
            val indexed = TriggerPipeline.parseIndexed(line) ?: continue
            val viaRegex = LogcatParser.parseLine(line, prev = null)

            if (viaRegex !is com.chaomixian.vflow.core.logcat.LogcatParseResult.Line) continue
            val expected = viaRegex.line

            val actualTag = line.substring(indexed.tagStart, indexed.tagEnd)
            if (expected.isContinuation) continue   // 降级行由调用方处理，不在索引解析的职责内

            compared++
            org.junit.Assert.assertEquals("TAG 不一致: $line", expected.tag, actualTag)
            org.junit.Assert.assertEquals("级别不一致: $line", expected.level.char, indexed.level)
        }

        assertTrue("应当对照到足够多的真实行，实际 $compared", compared > 3_000)
    }

    // ── 测量工具 ────────────────────────────────────────────────

    /**
     * 跑一轮并返回「每行纳秒」。
     *
     * 做法：预热若干轮（让 JIT 编译与去优化稳定下来），再取多轮的最小值。
     * **取最小值而非平均**——GC 或调度抖动只会让某轮变慢，最小值最接近真实成本。
     */
    private inline fun measureNsPerLine(crossinline body: () -> Unit): Long {
        repeat(WARMUP_ROUNDS) { body() }
        var best = Long.MAX_VALUE
        repeat(MEASURE_ROUNDS) {
            val t0 = System.nanoTime()
            body()
            val elapsed = System.nanoTime() - t0
            if (elapsed < best) best = elapsed
        }
        return best / LINE_COUNT
    }

    private fun measureRegexParse(lines: List<String>) {
        var sink = 0
        for (line in lines) {
            val r = LogcatParser.parseLine(line, prev = null)
            if (r is com.chaomixian.vflow.core.logcat.LogcatParseResult.Line) sink++
        }
        blackhole(sink)
    }

    private fun measureIndexedParse(lines: List<String>) {
        var sink = 0
        for (line in lines) {
            val p = TriggerPipeline.parseIndexed(line)
            if (p != null) sink += p.tagEnd - p.tagStart
        }
        blackhole(sink)
    }

    /** 防止 JIT 把整段循环当死代码消除。 */
    private var blackholeSink: Long = 0
    private fun blackhole(v: Int) {
        blackholeSink += v
    }

    /** `nsPerLine` 换算成「单核占用百分比」。 */
    private fun percentOfOneCore(nsPerLine: Long, linesPerSecond: Int): Double {
        // 每行 nsPerLine 纳秒，每秒 linesPerSecond 行：
        //   耗时(s/秒) = nsPerLine * linesPerSecond / 1e9
        //   换成百分比  = × 100
        // 合并即 / 1e7（即一秒钟 1e7 纳秒 = 1% 单核）
        return nsPerLine.toDouble() * linesPerSecond / 1e7
    }

    /** V/D 两级占比（位掩码能省掉的那部分）。 */
    private fun lowLevelRatio(lines: List<String>): Int {
        val dist = LogcatBenchmarkFixture.levelDistribution(lines)
        val total = lines.size
        val low = (dist['V'] ?: 0) + (dist['D'] ?: 0)
        return if (total == 0) 0 else low * 100 / total
    }

    private companion object {
        /** 样本行数。够大以摊薄启动开销，又不至于让单测跑成分钟级。 */
        const val LINE_COUNT = 200_000

        const val WARMUP_ROUNDS = 3
        const val MEASURE_ROUNDS = 5
    }
}
