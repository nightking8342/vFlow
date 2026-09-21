package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatCommands] 的回归测试。
 *
 * 重点锁死三处**真机实测踩出来的硬约束**（调试工具文档 §2.1 / §2.1b / §2.4）：
 * 这些约束改错了**不会报错**，只会让功能静默失效或挂死。
 */
class LogcatCommandsTest {

    // ── 硬约束 1：stdio 必须切断（否则 exec 永久挂起）────────────

    @Test
    fun `start capture redirects all three stdio streams`() {
        // ⚠️ 本测试保护的是一个"不报错、只静默挂住"的坑：
        // 后台 logcat 继承 shell 的 stdout -> 管道不关 ->
        // ShizukuUserService.readStream 等不到 EOF -> exec 永不返回。
        // 三个重定向缺一不可。
        val cmd = LogcatCommands.buildStartCapture()

        assertTrue("缺 stdout 重定向", cmd.contains(">/dev/null"))
        assertTrue("缺 stderr 重定向", cmd.contains("2>&1"))
        assertTrue("缺 stdin 重定向", cmd.contains("</dev/null"))
    }

    @Test
    fun `start capture runs logcat in background and writes pidfile`() {
        val cmd = LogcatCommands.buildStartCapture()

        assertTrue("必须以后台方式启动", cmd.contains("& echo \$!"))
        assertTrue("必须写 pidfile", cmd.contains(LogcatCommands.PID_FILE))
    }

    @Test
    fun `start capture uses absolute logcat path`() {
        // app_process / sh 环境的 PATH 不确定，必须用绝对路径。
        // 注意命令以 `timeout N` 开头（见下方的超时兜底测试），
        // 所以这里断言「包含」而非「以 logcat 开头」。
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue(cmd.contains(LogcatCommands.LOGCAT))
    }

    @Test
    fun `stop capture removes pidfile even if kill fails`() {
        // kill 失败（进程已死）也必须删 pidfile，
        // 否则会留下 STALE 脏状态，下次进入误报"上次异常结束"
        val cmd = LogcatCommands.buildStopCapture()

        assertTrue(cmd.contains("kill"))
        assertTrue("必须 rm pidfile", cmd.contains("rm -f ${LogcatCommands.PID_FILE}"))
    }

    // ── 硬约束 2：跨进程传输必须有界（否则打死 Binder）──────────

    @Test
    fun `snapshot always bounds the line count`() {
        // 裸跑 logcat -d 会吐全量缓冲区，实测触发
        // DeadObjectException: ... running out of binder buffer space
        val cmd = LogcatCommands.buildSnapshot(lines = 500)
        assertTrue(cmd.contains("-T 500"))
        assertTrue("-d 必须存在（一次性快照而非长驻）", cmd.contains(" -d"))
    }

    @Test
    fun `snapshot clamps oversized line count to the hard limit`() {
        val cmd = LogcatCommands.buildSnapshot(lines = 999_999)
        assertTrue(cmd.contains("-T ${LogcatCommands.MAX_LINES}"))
    }

    @Test
    fun `snapshot clamps non-positive line count to at least one`() {
        assertTrue(LogcatCommands.buildSnapshot(lines = 0).contains("-T 1"))
        assertTrue(LogcatCommands.buildSnapshot(lines = -5).contains("-T 1"))
    }

    @Test
    fun `search bounds the line count`() {
        assertTrue(LogcatCommands.buildSearch(limit = 300).contains("tail -n 300"))
        assertTrue(
            LogcatCommands.buildSearch(limit = 999_999)
                .contains("tail -n ${LogcatCommands.MAX_LINES}")
        )
    }

    @Test
    fun `probe state is a single self-contained shell conditional`() {
        // 状态判定必须是一条命令拿全部（避免多次跨进程往返），
        // 且输出恒为一行小字符串，不会撞 Binder 上限
        val cmd = LogcatCommands.buildProbeState()

        assertTrue(cmd.contains("IDLE"))
        assertTrue(cmd.contains("CAPTURING"))
        assertTrue(cmd.contains("STALE"))
        assertTrue("必须读 pidfile", cmd.contains(LogcatCommands.PID_FILE))
    }

    @Test
    fun `probe state matches pid exactly instead of grepping process names`() {
        // ⚠️ 实测：grep logcat 会把系统/其他进程算进来，
        // 且可见性还受执行身份影响（同设备两轮分别数到 3 和 1）。
        // 必须用 ps -A -o PID= 精确匹配 pidfile 里的 pid。
        val cmd = LogcatCommands.buildProbeState()

        assertTrue("应使用 -o PID= 只取 pid 列", cmd.contains("-o PID="))
        assertTrue("应使用 grep -qw 做整词精确匹配", cmd.contains("grep -qw"))
        assertTrue("不应按进程名 grep", !cmd.contains("grep [l]ogcat"))
    }

    // ── 状态解析 ─────────────────────────────────────────────────

    @Test
    fun `parses idle state`() {
        assertEquals(CaptureState.Idle, LogcatCommands.parseProbeState("IDLE"))
    }

    @Test
    fun `parses capturing state with pid`() {
        assertEquals(CaptureState.Capturing(6100), LogcatCommands.parseProbeState("CAPTURING:6100"))
    }

    @Test
    fun `parses stale state with pid`() {
        assertEquals(CaptureState.Stale(23831), LogcatCommands.parseProbeState("STALE:23831"))
    }

    @Test
    fun `tolerates surrounding whitespace and extra lines`() {
        assertEquals(CaptureState.Capturing(42), LogcatCommands.parseProbeState("\n  CAPTURING:42  \n"))
    }

    @Test
    fun `falls back to idle on unrecognized output`() {
        // 保守取 Idle：宁可让用户点得动，也不要误报"正在采集"而卡住操作
        assertEquals(CaptureState.Idle, LogcatCommands.parseProbeState("something unexpected"))
        assertEquals(CaptureState.Idle, LogcatCommands.parseProbeState(""))
    }

    // ── 刷新分流（§4.1.1）────────────────────────────────────────

    @Test
    fun `idle refresh reads the buffer`() {
        val cmd = LogcatCommands.buildRefresh(CaptureState.Idle, lines = 100)
        assertTrue("空闲态应读 logcat 缓冲区", cmd!!.contains("-d"))
        assertTrue(cmd.contains("-T 100"))
    }

    @Test
    fun `capturing refresh reads the capture file`() {
        val cmd = LogcatCommands.buildRefresh(CaptureState.Capturing(1), lines = 100)
        assertTrue("采集态应读采集文件", cmd!!.contains("tail -n 100"))
        assertTrue(cmd.contains(LogcatCommands.CAPTURE_FILE))
        assertTrue("采集态不应读缓冲区", !cmd.contains("logcat -d"))
    }

    @Test
    fun `stale refresh does not execute any command`() {
        // STALE 应走提示 + 手动清理，而不是跑命令
        assertNull(LogcatCommands.buildRefresh(CaptureState.Stale(1)))
    }

    // ── TAG 过滤 ─────────────────────────────────────────────────

    @Test
    fun `snapshot applies tag filter with level when provided`() {
        val cmd = LogcatCommands.buildSnapshot(lines = 10, tag = "MyApp", minLevel = LogLevel.WARN)
        assertTrue(cmd.contains("-s 'MyApp':W"))
    }

    @Test
    fun `snapshot omits tag filter when tag is blank`() {
        assertTrue(!LogcatCommands.buildSnapshot(lines = 10, tag = null).contains("-s"))
        assertTrue(!LogcatCommands.buildSnapshot(lines = 10, tag = "   ").contains("-s"))
    }

    @Test
    fun `tag stats snapshot ignores tag filter`() {
        // ⚠️ 核心行为：用户点「TAG 统计」正是因为不知道该填哪个 TAG，
        // 用已填的条件统计等于让他自己回答自己的问题。
        val cmd = LogcatCommands.buildSnapshotForTagStats(lines = 500)

        assertTrue("必须没有 -s 过滤", !cmd.contains("-s"))
        assertTrue(cmd.contains("-T 500"))
    }

    @Test
    fun `tag stats snapshot keeps level filter`() {
        // 「只看 W 以上有哪些 TAG」是合理诉求，级别过滤保留
        val cmd = LogcatCommands.buildSnapshotForTagStats(lines = 500, minLevel = LogLevel.WARN)
        assertTrue(cmd.contains("*:W"))
    }

    @Test
    fun `tag stats snapshot omits filter spec at verbose level`() {
        val cmd = LogcatCommands.buildSnapshotForTagStats(lines = 500, minLevel = LogLevel.VERBOSE)
        assertTrue(!cmd.contains("*:V"))
    }

    // ── shell 转义 ───────────────────────────────────────────────

    @Test
    fun `shell quote wraps plain value`() {
        assertEquals("'MyApp'", LogcatCommands.shellQuote("MyApp"))
    }

    @Test
    fun `shell quote escapes embedded single quotes`() {
        // 不转义会导致命令拼接错误甚至注入
        assertEquals("'a'\\''b'", LogcatCommands.shellQuote("a'b"))
    }

    @Test
    fun `snapshot quotes a tag containing shell metacharacters`() {
        val cmd = LogcatCommands.buildSnapshot(lines = 10, tag = "a'; rm -rf / #")
        assertTrue("危险字符必须被单引号包裹", cmd.contains("'a'\\''; rm -rf / #'"))
    }

    // ── 其他 ─────────────────────────────────────────────────────

    @Test
    fun `uses threadtime verbosity`() {
        assertTrue(LogcatCommands.buildSnapshot().contains("-v threadtime"))
        assertTrue(LogcatCommands.buildStartCapture().contains("-v threadtime"))
    }

    @Test
    fun `start capture enables rotation so the file stays bounded`() {
        // -r/-n 是 logcat 自带的轮转，省掉自己写清理逻辑
        val cmd = LogcatCommands.buildStartCapture(rotateKb = 1024, rotateCount = 3)
        assertTrue(cmd.contains("-r 1024"))
        assertTrue(cmd.contains("-n 3"))
    }

    // ── 时长上限的 shell 侧兜底 ────────────────────────────────

    @Test
    fun `start capture wraps logcat with a shell-side timeout`() {
        // ⚠️ 这是在补救一个真实漏洞：App 侧计时只在 App 活着时有效，
        // 而采集设计成脱离 UI 存活——App 被杀时"防忘记关"就失效了，
        // logcat 会无限期跑下去。下推到 shell 侧后无论 App 死活都会停。
        val cmd = LogcatCommands.buildStartCapture(timeoutSec = 300)

        assertTrue("必须有 timeout 前缀", cmd.contains("timeout 300"))
        // ⚠️ 不能直接写 `cmd.indexOf(LOGCAT)` 比较先后 ——
        // 前面清理残留的 `pkill -f '...logcat...'` 里也含 "logcat"，
        // 会匹配到那一处而不是真正的启动位置。
        // 这里锚定到"启动命令"的完整前缀上。
        val launchAt = cmd.indexOf("timeout 300 " + LogcatCommands.LOGCAT)
        assertTrue("应能找到启动命令", launchAt >= 0)
        assertTrue(
            "timeout 必须紧跟在启动命令前",
            launchAt < cmd.indexOf(LogcatCommands.CAPTURE_FILE, launchAt),
        )
    }

    @Test
    fun `start capture uses the default timeout when unspecified`() {
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue(cmd.contains("timeout ${LogcatCommands.DEFAULT_TIMEOUT_SEC}"))
    }

    @Test
    fun `start capture clamps a non-positive timeout to at least one second`() {
        // timeout 0 在 toybox 里表示"不超时"，与用户意图相反，必须夹取
        assertTrue(LogcatCommands.buildStartCapture(timeoutSec = 0).contains("timeout 1 "))
        assertTrue(LogcatCommands.buildStartCapture(timeoutSec = -5).contains("timeout 1 "))
    }

    // ── 采集完成标记 ★ ──────────────────────────────────────────

    @Test
    fun `stopping a capture writes the completion marker`() {
        // ⚠️ 这个标记是「数据源是否已固定」的唯一依据。
        // 漏了它的话，停止采集后状态会退回 Idle —— 而 Idle 读的是
        // **实时滚动的缓冲区**，用户切个过滤就看到"现在的日志"而不是刚采的那批
        val cmd = LogcatCommands.buildStopCapture()
        assertTrue("停止时必须写下完成标记", cmd.contains("touch ${LogcatCommands.DONE_FILE}"))
    }

    @Test
    fun `stopping a capture still clears the pidfile`() {
        val cmd = LogcatCommands.buildStopCapture()
        assertTrue("pidfile 仍要删（否则留下 STALE）", cmd.contains("rm -f ${LogcatCommands.PID_FILE}"))
    }

    @Test
    fun `starting a capture clears a stale completion marker`() {
        // ⚠️ 顺序关键：新采集启动时若不删旧标记，
        // "pidfile 已写、进程还没起来"的那个瞬间会被探测成"已完成"，
        // 用户看到的就是上一批日志
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue("开始采集时应清掉上一轮的完成标记", cmd.contains("rm -f ${LogcatCommands.DONE_FILE}"))
    }

    @Test
    fun `probe checks the process before the completion marker`() {
        // ⚠️ 判定顺序有讲究：**先看进程，再看标记**。
        // 反过来的话，采集中（pidfile 在、进程活着）却带着旧标记时
        // 会被判成 COMPLETED
        val cmd = LogcatCommands.buildProbeState()
        val pidCheck = cmd.indexOf("ps -A -o PID=")
        val doneCheck = cmd.indexOf(LogcatCommands.DONE_FILE)
        assertTrue("两处都要有", pidCheck >= 0 && doneCheck >= 0)
        assertTrue("进程判定必须在标记判定之前", pidCheck < doneCheck)
    }

    @Test
    fun `parses the completed state`() {
        assertEquals(CaptureState.Completed, LogcatCommands.parseProbeState("COMPLETED"))
    }

    @Test
    fun `clearing the completion marker is a separate command`() {
        // 放弃这批日志 = 删标记（回到缓冲区）。
        // 与清理 STALE（删 pidfile）是两个不同的动作，不能混用
        assertEquals("rm -f ${LogcatCommands.DONE_FILE}", LogcatCommands.buildClearDone())
        assertTrue(!LogcatCommands.buildClearDone().contains(LogcatCommands.PID_FILE))
    }

    @Test
    fun `a completed state refreshes from the capture file`() {
        // 已完成态的数据源是采集文件（不是缓冲区）
        val cmd = LogcatCommands.buildRefresh(CaptureState.Completed)
        assertTrue(cmd!!.contains("tail"))
        assertTrue(cmd.contains(LogcatCommands.CAPTURE_FILE))
        assertTrue("已完成态不该读缓冲区", !cmd.contains("logcat -d"))
    }

    @Test
    fun `tag stats on a completed state also read the capture file`() {
        val cmd = LogcatCommands.buildTagStats(CaptureState.Completed)
        assertTrue(cmd!!.contains(LogcatCommands.CAPTURE_FILE))
    }

    // ── 采集检索 ★ ──────────────────────────────────────────────

    @Test
    fun `search covers rotated files not just the current one`() {
        // ⚠️ 只看当前那份的话，能见到的还是最近一小段。
        // 轮转出来的历史份里才有更早的日志
        val cmd = LogcatCommands.buildSearch()
        assertTrue("必须用通配符覆盖轮转文件", cmd.contains("${LogcatCommands.CAPTURE_FILE}*"))
    }

    @Test
    fun `search orders files by time so output is chronological`() {
        // ⚠️ 轮转文件名是 .1 .2 .10，shell 通配符按**字典序**展开时
        // .10 会排在 .2 前面 —— 直接 cat 通配符会得到乱序输出。
        // 必须用 ls -t 按修改时间排。
        //
        // ⚠️ 2026-09-19 起改为 `ls -t`（倒序）而不是 `ls -tr`（正序）：
        // 定窗读取要从**最新**的文件往回数、凑够窗口就停，
        // 老文件根本不打开。选中的文件在循环里往字符串前面插，
        // 拼出来仍是时间正序，时间序不受影响。
        val cmd = LogcatCommands.buildSearch()
        assertTrue("应按修改时间排序（倒序，从最新往回数）", cmd.contains("ls -t "))
        assertTrue("应把选中的文件拼起来读", cmd.contains("cat "))
    }

    @Test
    fun `search never reads the whole file into the result`() {
        // ⚠️ 采集文件可达数百 MB，结果必须被截断 ——
        // 否则会撞 Binder 上限把 UserService 打死
        val cmd = LogcatCommands.buildSearch(limit = 500)
        assertTrue("Shell 侧必须截断", cmd.contains("tail -n 500"))
    }

    @Test
    fun `search clamps an oversized limit`() {
        val cmd = LogcatCommands.buildSearch(limit = 999_999)
        assertTrue(cmd.contains("tail -n ${LogcatCommands.MAX_LINES}"))
    }

    @Test
    fun `search passes the pattern through shell quoting`() {
        // ⚠️ 模式必须经 shellQuote 包裹。不用的话，用户 TAG 里的引号/分号
        // 会造成命令拼接错误甚至注入。
        // 用 buildSearchPattern 算出的**期望值**比对，避免把逻辑重写一遍
        val expected = LogcatCommands.shellQuote(
            LogcatCommands.buildSearchPattern("MyApp", "", LogLevel.VERBOSE).regex
        )
        assertTrue("模式应被 shellQuote 包裹", LogcatCommands.buildSearch(tagQuery = "MyApp").contains(expected))
    }

    @Test
    fun `search escapes regex metacharacters in user input`() {
        // ⚠️ 不转义的话，TAG 里的 `.` 会被当成"任意字符"，
        // 表现是匹配结果莫名其妙地多 —— 用户完全看不出原因
        val p = LogcatCommands.buildSearchPattern("a.b", "", LogLevel.VERBOSE)
        // 用码位比较而非转义字面量 —— 后者在源码里层数一多就容易写错
        val backslash = 0x5C.toChar()
        assertTrue("点号前应有反斜杠", p.regex.contains("$backslash."))
    }

    @Test
    fun `search is case insensitive when a keyword is given`() {
        val withTag = LogcatCommands.buildSearchPattern("MyApp", "", LogLevel.VERBOSE)
        assertTrue("有关键字时应大小写不敏感", withTag.grepFlags.contains("i"))

        val noKeyword = LogcatCommands.buildSearchPattern("", "", LogLevel.VERBOSE)
        assertTrue("无关键字时不需要 i", !noKeyword.grepFlags.contains("i"))
    }

    @Test
    fun `search level filter admits every level at or above the threshold`() {
        // ⚠️ "最低级别"是**至少这么严重**：W 要放行 W/E/F 三级。
        // 只放行 W 会让 ERROR 被过滤掉 —— 而那正是最该看到的
        val p = LogcatCommands.buildSearchPattern("", "", LogLevel.WARN)
        assertTrue("W 应在内", p.regex.contains("W"))
        assertTrue("E 应在内", p.regex.contains("E"))
        assertTrue("F 应在内", p.regex.contains("F"))
        assertTrue("I 不该在内", !p.regex.contains("I"))
    }

    @Test
    fun `search omits the level filter at verbose`() {
        // V 是最低级，放行全部 —— 加个 `[VDIWEF]` 只是白费
        val p = LogcatCommands.buildSearchPattern("", "", LogLevel.VERBOSE)
        assertTrue("V 门槛下不该有级别过滤", !p.regex.contains("[VDIWEF]"))
    }

    @Test
    fun `search combines tag and message conditions`() {
        val p = LogcatCommands.buildSearchPattern("MyApp", "error", LogLevel.VERBOSE)
        assertTrue("TAG 条件应在", p.regex.contains("MyApp"))
        assertTrue("消息条件应在", p.regex.contains("error"))
    }

    @Test
    fun `an empty search matches everything`() {
        val p = LogcatCommands.buildSearchPattern("", "", LogLevel.VERBOSE)
        assertEquals(".", p.regex)
        assertEquals("", p.grepFlags)
    }

    @Test
    fun `grep uses extended regex so that plus is a quantifier`() {
        // ⚠️ **本测试保护的是一个已实际踩过的坑。**
        //
        // pattern 里用了 `[ ]+` 这类 ERE 语法。而在基本正则（BRE，grep 默认）里
        // `+` 是**字面字符**而不是量词 —— `[ ]+` 会去匹配"一个空格后跟一个加号"，
        // 永远匹配不到任何日志行。
        //
        // 症状极其误导：采集文件好好地写着日志、命令也正常返回、
        // 只是**永远搜不到东西**，看起来完全像"采集不到日志"。
        //
        // 旧的断言只检查了 `-a`，因此这个 bug 溜过去了。
        val cmd = LogcatCommands.buildSearch()

        assertTrue("必须带 -E（扩展正则），否则 + 不是量词", cmd.contains("grep -aE") || cmd.contains("-E"))
        assertTrue("必须带 -a：混进二进制字节时 grep 会判定 binary file 而不输出内容", cmd.contains("-a"))
    }

    @Test
    fun `the search pattern only uses syntax that requires -E`() {
        // 反向确认：pattern 里确实含有"BRE 下会失效"的语法，
        // 所以 -E 是必需的而非可选。若哪天把 pattern 改写成纯 BRE 语法，
        // 这条会失败，提醒把 -E 的说明一并更新
        val p = LogcatCommands.buildSearchPattern("tag", "msg", LogLevel.INFO)
        assertTrue(
            "pattern 含 + 量化（BRE 下会失效），因此必须配 -E",
            p.regex.contains("+")
        )
    }

    @Test
    fun `rotation capacity is large enough for the default time limit`() {
        // ⚠️ 原值 1MB × 4 在高频日志下只覆盖约 10 秒，
        // 用户"采 5 分钟再回来查"时早期日志早被挤掉。
        // 64MB × 4 = 256MB，按 400KB/s 可覆盖约 10 分钟
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue("轮转容量应远大于 1MB", LogcatCommands.ROTATE_KB >= 64 * 1024)
        assertTrue(cmd.contains("-r ${LogcatCommands.ROTATE_KB}"))
        assertTrue(cmd.contains("-n ${LogcatCommands.ROTATE_COUNT}"))
    }

    // ── cat 必须切断 stdin ★（挂死级 bug）──────────────────────

    @Test
    fun `search redirects stdin so a bare cat cannot hang`() {
        // ⚠️⚠️ **本测试保护的是一个会导致界面卡死的 bug。**
        //
        // 当通配符无匹配时（刚点「开始采集」——`buildStartCapture` 会先
        // `rm -f ... $CAPTURE_GLOB`，而 logcat 还没创建新文件），
        // `$(ls -tr ... 2>/dev/null)` 展开为空串，命令变成**裸 `cat`**。
        // 而 `cat` 不带参数时**读标准输入**，在 exec 通道下 stdin 不关闭
        // → **命令永不返回**。
        //
        // 连锁反应：in-flight 标志被卡住 → 界面一直显示"刷新中"
        // → 连「停止」按钮都变灰点不动（canStop 里检查了 refreshing）
        // → 必须退出重进才能恢复。
        val cmd = LogcatCommands.buildSearch()
        assertTrue(
            "cat 必须显式重定向 stdin（</dev/null），否则无匹配时会挂死",
            cmd.contains("</dev/null")
        )
    }

    @Test
    fun `capture size query also redirects stdin`() {
        // 同一类问题：无匹配时 `du` 无参数也会读 stdin
        val cmd = LogcatCommands.buildCaptureSize()
        assertTrue(cmd.contains("</dev/null"))
    }

    @Test
    fun `search still works normally when files exist`() {
        // 回归：换实现不该影响正常路径的语义
        val cmd = LogcatCommands.buildSearch(tagQuery = "MyApp", limit = 100)
        assertTrue("仍要按时间排轮转份（倒序挑选）", cmd.contains("ls -t "))
        assertTrue("仍是扩展正则（-E 可能与其他标志连写为 -aEi）", cmd.contains("E"))
        assertTrue("仍要截断", cmd.contains("tail -n 100"))
        // ⚠️ 这条断言在 2026-09-19 从"检索全部轮转份"**反转**了：
        // 全体读取正是性能问题的根源（读 1000 行要 4–6 秒，且越采越慢）
        assertFalse("不应再无差别读取全部轮转份", cmd.contains("ls -tr"))
    }

    @Test
    fun `search is wrapped in a timeout`() {
        // ⚠️ 检索命令**必须**有超时兜底。
        //
        // 实测过一条跑了 **24943ms** 才返回的检索命令：期间 Core 被重启、
        // 连接断开，命令是靠那次断开"顺带"结束的，不是自己结束的。
        //
        // 没有 timeout 时，只要底层通道一直不回，这个协程就永远挂着 ——
        // 而它持有 `refreshing` 标志，界面表现为**一直加载中**、
        // 所有依赖该标志的按钮（含「删除采集文件」）全部变灰。
        //
        // `</dev/null` 只挡得住"裸 cat 读 stdin"这一种挂法，挡不住通道本身不回。
        val cmd = LogcatCommands.buildSearch()
        assertTrue(
            "检索命令必须带 timeout 兜底：$cmd",
            cmd.startsWith("timeout ") || cmd.contains(" timeout ")
        )
    }

    @Test
    fun `the search timeout is neither too tight nor unbounded`() {
        // 太紧：大文件检索会被误杀（正常几百毫秒，慢的也就一两秒）
        // 太长：用户要盯着转圈很久，且标志位被占用的时间同样很久
        val cmd = LogcatCommands.buildSearch()
        val sec = Regex("""timeout\s+(\d+)""").find(cmd)?.groupValues?.get(1)?.toIntOrNull()
        requireNotNull(sec) { "解析不出超时秒数，命令格式变了？cmd=$cmd" }
        assertTrue("超时不宜短于 5 秒（大文件会被误杀），实为 $sec", sec >= 5)
        assertTrue("超时不宜长于 60 秒（用户要干等），实为 $sec", sec <= 60)
    }

    @Test
    fun `the timeout is applied to cat not to the whole pipeline`() {
        // ⚠️ `timeout` 只能加在**第一个命令**上。
        // 写成 `cat ... | grep ... | tail ... ` 外面套 timeout 是无效的 ——
        // shell 的 timeout 作用于单个命令，管道需要 `timeout` 只裹住最上游，
        // 或改用 `timeout sh -c '...'`。
        //
        // 这里锁住当前实现：timeout 紧跟 cat，且在整个管道的最前
        val cmd = LogcatCommands.buildSearch()
        val timeoutAt = cmd.indexOf("timeout ")
        val catAt = cmd.indexOf("cat ")
        assertTrue("timeout 应在 cat 之前", timeoutAt in 0 until catAt)
        assertTrue("timeout 不应被别的命令包在前面", timeoutAt < cmd.indexOf("|"))
    }

    @Test
    fun `the timeout wraps the whole pipeline so its exit code survives`() {
        // ★ 这条锁住的是「超时能被上层发现」这个能力本身。
        //
        // 管道退出码 = **最后一个命令**的退出码。写成
        //   `timeout N cat ... | grep ... | tail ...`
        // 时整个管道的退出码恒为 0（`tail` 的），`timeout` 到点杀掉 cat 之后
        // 上层拿到的是"退出码 0 + 空输出"—— 与"没有匹配到"完全无法区分。
        // 实测：`timeout 1 sh -c 'sleep 3'` 单独跑返回 124，接个 `| cat` 就变 0。
        //
        // 唯一能让 124 传出来的写法是把 timeout 放在最后一段的位置：
        //   `timeout N sh -c '<整条管道>'`
        val cmd = LogcatCommands.buildSearch()
        assertTrue(
            "timeout 必须裹住整条管道（timeout N sh -c ...），当前是：$cmd",
            cmd.startsWith("timeout ") && cmd.contains(" sh -c "),
        )
        // 反向：不能是 "timeout N cat ... | grep ..." 这种只裹最上游的写法
        assertFalse(
            "timeout 后面不能直接跟 cat 再进管道 —— 退出码会被吞",
            Regex("""timeout\s+\d+\s+\S*cat\b""").containsMatchIn(cmd),
        )
    }

    @Test
    fun `timeout results are recognised from the exit code`() {
        // ⚠️ 只能认退出码。
        //
        // 三条执行路径（Shizuku / Root / Core）在退出码非零时都会把输出
        // 包装成 `Error: ...`，超时的输出恰好是 `Error: Exit code 124`。
        // 因此**不能**用"输出以 Error: 开头"来排除超时——那会把唯一
        // 要检测的情况排除掉，修复会无声失效。
        assertTrue(LogcatCommands.isTimeoutResult(LogcatCommands.TIMEOUT_EXIT_CODE))
        assertFalse("成功不能算超时", LogcatCommands.isTimeoutResult(0))
        assertFalse("Shell 层失败的 -1 不是超时", LogcatCommands.isTimeoutResult(-1))
        assertFalse("普通非零退出码不是超时", LogcatCommands.isTimeoutResult(1))
    }

    @Test
    fun `the timeout exit code matches what timeout actually returns`() {
        // 124 是 toybox / GNU timeout 超时后的约定退出码。
        // 这条断言是为了让"改成别的数字"必须是一次有意识的改动
        assertEquals(124, LogcatCommands.TIMEOUT_EXIT_CODE)
    }

    // ── 定窗读取（性能修复）────────────────────────────────────────

    @Test
    fun `search reads only the newest files instead of every rotated file`() {
        // ★ 这是本次性能修复的核心断言。
        //
        // 老实现 `cat $(ls -tr ...)` 会把**每个**轮转文件都读一遍 ——
        // 为了最后 1000 行扫完整个 256MB。实测读 1000 行要 4–6 秒，
        // 且**采集越久越慢**（读的量与文件总量成正比）。
        //
        // 新实现从最新往回数，凑够窗口就停，老文件**根本不打开**。
        // 这里断言的是"存在按大小累加后提前退出的逻辑" ——
        // 少了它就会退化成"把文件全打开"，等于没优化。
        val cmd = LogcatCommands.buildSearch()
        assertTrue("必须按大小累加：$cmd", cmd.contains("acc="))
        assertTrue("累够就必须提前退出（break），否则老文件全被打开", cmd.contains("break"))
        assertTrue("必须先按时间倒序取（ls -t），才能从最新的往回数", cmd.contains("ls -t"))
    }

    @Test
    fun `search never cats every file unconditionally`() {
        // 反向断言：不能出现"无条件下 cat 所有文件"的写法。
        // `cat $(ls -tr ...)` 就是老实现的特征串（tr = 时间正序，全量）
        val cmd = LogcatCommands.buildSearch()
        assertFalse("不应再无条件 cat 全部文件", cmd.contains("cat \$(ls -tr"))
    }

    @Test
    fun `the window is a bounded byte range`() {
        // 窗口必须有上下界：
        // - 太小：取不满用户要的 N 行（过滤窄时尤其明显）
        // - 太大：跨进程传输变慢，失去优化的意义
        val cmd = LogcatCommands.buildSearch()
        assertTrue("应有 tail -c 截出末段", cmd.contains("tail -c"))
        assertTrue("应有 head -c 截出窗口", cmd.contains("head -c"))
    }

    @Test
    fun `window size is clamped to a sane range`() {
        assertEquals(
            "过小的窗口应被抬到下限",
            LogcatCommands.MIN_READ_WINDOW_KB,
            LogcatCommands.clampWindowKb(1),
        )
        assertEquals(
            "过大的窗口应被压到上限",
            LogcatCommands.MAX_READ_WINDOW_KB,
            LogcatCommands.clampWindowKb(999_999),
        )
        assertEquals(8192, LogcatCommands.clampWindowKb(8192))
    }

    @Test
    fun `the window floor leaves every line-limit step untouched`() {
        // ★ 窗口下限**不得**干扰任何档位。
        //
        // ⚠️ 这条断言的正确写法很关键。天真的写法是
        //   `assertEquals(needed, clampWindowKb(needed))`
        // —— 那是**同义反复**：只要 `needed >= floor` 就恒成立，
        // 把 floor 调到任何小值都不会变红，等于没测。
        // （我第一版就是这么写的，用"把下限改回去"做反证时才发现的。）
        //
        // 真正要锁的是**双向**：
        // 1. 下限不能**大于**最小档所需窗口（否则会把 200 档抬大）
        // 2. 下限也不能**小于**某个合理值（否则失去兜底意义）
        //
        // 第 1 条是实质约束 —— 最小档的窗口算出来是 312KB，
        // 所以 floor 必须严格小于它。调大到 320 就会变红。
        val smallestStepBytes = 200L * LogcatCommands.AVG_LINE_BYTES * 8   // 最小档 200 条
        val neededKb = (smallestStepBytes / 1024).toInt()

        assertTrue(
            "下限(${LogcatCommands.MIN_READ_WINDOW_KB}KB) 必须小于最小档所需窗口(${neededKb}KB)，" +
                "否则会把该档位抬大、破坏「窗口 = 档位所需」的关系",
            LogcatCommands.MIN_READ_WINDOW_KB < neededKb,
        )

        // 且每个档位的窗口都不该被下限改动（这才是"绝不干扰"的直接验证）
        listOf(200, 500, 1000, 2000).forEach { limit ->
            val w = LogcatCommands.windowKbForPageLines(limit)
            assertEquals(
                "${limit} 档的窗口(${w}KB)不该被下限夹动",
                w,
                LogcatCommands.clampWindowKb(w),
            )
        }
    }

    @Test
    fun `paging skips backwards from the end`() {
        // 翻页：skip 越大，取到的内容越早。
        // 写法是 `tail -c (skip + window) | head -c window` ——
        // 倒数 skip+window 字节的第一段，正是"比当前窗口更早的那一窗"
        val first = LogcatCommands.buildSearch()
        val second = LogcatCommands.buildSearch(skipBytes = 4L * 1024 * 1024)

        assertTrue("第一页 skip 应为 0", first.contains("acc - 0)"))
        assertTrue("第二页应带上 skip", second.contains("acc - 4194304)"))
    }

    @Test
    fun `paging by the window size walks backwards without gaps or overlap`() {
        // ⚠️ 这条锁的是翻页的**连续性**：每次 skip 恰好前进一个窗口，
        // 相邻两页既不重叠也不留缝。
        // 若 skip 的步进与窗口大小不一致，用户翻页时会发现
        // "有些行永远看不到"或"同一段反复出现" —— 而这两者都很难归因
        val windowKb = LogcatCommands.READ_WINDOW_KB
        val windowBytes = windowKb * 1024L

        val page0 = LogcatCommands.buildWindowedRead(skipBytes = 0L, windowKb = windowKb)
        val page1 = LogcatCommands.buildWindowedRead(skipBytes = windowBytes, windowKb = windowKb)

        assertTrue("第 0 页 skip 为 0", page0.contains("acc - 0)"))
        assertTrue(
            "第 1 页 skip 应恰好等于一个窗口大小（$windowBytes）",
            page1.contains("acc - $windowBytes)"),
        )
    }

    @Test
    fun `a negative skip is treated as zero`() {
        // 越界的 skip 若原样进命令，`tail -c` 会收到负数而报错，
        // 表现为"翻到第一页就报错"
        val cmd = LogcatCommands.buildWindowedRead(skipBytes = -5000L, windowKb = 1024)
        assertTrue("负数应被夹到 0：$cmd", cmd.contains("acc - 0)"))
    }

    @Test
    fun `the windowed read still redirects stdin`() {
        // ⚠️ 无匹配时 `files` 为空串 → `cat` 无参数 → **读 stdin 而挂死**。
        // 这是踩过的坑，换了实现方式后必须重新确认它没丢
        val cmd = LogcatCommands.buildWindowedRead(skipBytes = 0L, windowKb = 1024)
        assertTrue("cat 必须重定向 stdin", cmd.contains("</dev/null"))
    }

    @Test
    fun `the windowed read is wrapped in a timeout`() {
        // 同上：超时兜底不能因为改了实现而丢掉
        val cmd = LogcatCommands.buildSearch()
        assertTrue("必须有 timeout 兜底", cmd.contains("timeout "))
    }

    @Test
    fun `file sizes are read with a redirect not as gnu-style arguments`() {
        // ⚠️ `wc -c < f` 的 `<` 不能省。
        // 写成 `wc -c f` 会把文件名一起输出（如 "1000 f"），
        // 参与算术运算时出错并被 `|| echo 0` 吞掉 → 大小恒为 0 →
        // 累加永远凑不够 → **把所有文件都打开**，优化静默失效
        val cmd = LogcatCommands.buildWindowedRead(skipBytes = 0L, windowKb = 1024)
        assertTrue("必须是 wc -c < 形式：$cmd", cmd.contains("wc -c < "))
    }

    // ── 取不满就扩窗重读 ─────────────────────────────────────────

    @Test
    fun `retries with a larger window when the result is short of the limit`() {
        // ★ 这条防的是"静默变少"：
        // 窗口 16MB ≈ 8 万行，命中率低于 2.5% 时取不满 2000 条。
        // 不重试的话界面显示 80 行，看起来像"这批就只有 80 行"
        val next = LogcatCommands.nextWindowForRetry(
            returned = 80,
            limit = 2000,
            currentWindowKb = LogcatCommands.READ_WINDOW_KB,
        )
        assertNotNull("取不满时必须扩大窗口重读", next)
        assertTrue("应比原窗口大", next!! > LogcatCommands.READ_WINDOW_KB)
    }

    @Test
    fun `does not retry when the result already hit the limit`() {
        // 取满了说明窗口够用，再读纯属浪费一次跨进程命令
        assertNull(
            LogcatCommands.nextWindowForRetry(
                returned = 2000,
                limit = 2000,
                currentWindowKb = LogcatCommands.READ_WINDOW_KB,
            )
        )
    }

    @Test
    fun `does not retry once the window ladder is exhausted`() {
        // 窗口到顶还取不满 —— 那说明"少"是真的少（不是被窗口截的），
        // 继续重试只会无限循环
        val top = LogcatCommands.READ_WINDOW_LADDER_KB.last()
        assertNull(
            LogcatCommands.nextWindowForRetry(
                returned = 10,
                limit = 2000,
                currentWindowKb = top,
            )
        )
    }

    @Test
    fun `the window ladder is strictly increasing and stays within bounds`() {
        // 阶梯必须单调递增，否则重试会原地打转（读同一个窗口 → 同样取不满 → 再试）
        val ladder = LogcatCommands.READ_WINDOW_LADDER_KB
        assertTrue("阶梯不应为空", ladder.isNotEmpty())
        ladder.zipWithNext().forEach { (a, b) ->
            assertTrue("阶梯必须递增：$a → $b", b > a)
        }
        ladder.forEach {
            assertEquals("每一档都要落在夹取范围内：$it", it, LogcatCommands.clampWindowKb(it))
        }
    }

    @Test
    fun `retry ladder walks to the next step instead of jumping`() {
        // 逐档前进（16MB → 32MB），这样第一次重试代价最小；
        // 一上来就跳到最大窗口会让"常见情况"也付出最大代价
        val first = LogcatCommands.READ_WINDOW_LADDER_KB.first()
        val second = LogcatCommands.READ_WINDOW_LADDER_KB[1]
        assertEquals(
            second,
            LogcatCommands.nextWindowForRetry(returned = 1, limit = 2000, currentWindowKb = first),
        )
    }

    // ── 翻页：页码 → 字节偏移 ────────────────────────────────────

    @Test
    fun `page one reads the newest window`() {
        // 第 1 页 = 不跳过任何字节 = 最新的那一窗
        val cmd = LogcatCommands.buildSearch(skipBytes = 0L)
        assertTrue("第 1 页应不跳过（skip=0）", cmd.contains("acc - 0)"))
    }

    @Test
    fun `page n skips n minus one windows`() {
        // ★ 页码 → 偏移的换算必须严格是 (page-1) × 窗口。
        // 差一页会让相邻两页**重叠或漏内容** ——
        // 表现是"同一段总出现"或"有些行永远翻不到"，都很难归因
        val w = LogcatCommands.READ_WINDOW_KB * 1024L
        val page3 = LogcatCommands.buildSearch(skipBytes = 2L * w)
        assertTrue("第 3 页应跳过 2 个窗口（$w × 2）", page3.contains("acc - ${2L * w})"))
    }

    @Test
    fun `page skip is independent of the line limit`() {
        // ⚠️ 页是按**字节窗口**切的，不是按行数。
        // 若误用行数算偏移，改一次「条数」就会让所有页码错位
        val w = LogcatCommands.READ_WINDOW_KB * 1024L
        val a = LogcatCommands.buildSearch(limit = 500, skipBytes = w)
        val b = LogcatCommands.buildSearch(limit = 2000, skipBytes = w)
        assertTrue("同为第 2 页，偏移应一致", a.contains("acc - $w)") && b.contains("acc - $w)"))
    }

    // ── 滚动轴拖动预览 ──────────────────────────────────────────

    @Test
    fun `dragging to the top maps to the first item`() {
        assertEquals(0, scrollIndexForDrag(dragY = 0f, trackHeightPx = 1000f, itemCount = 100))
    }

    @Test
    fun `dragging to the bottom maps to the last item`() {
        // ★ 底部必须**用完**：映射到 itemCount-1 而不是 itemCount-1 都到不了。
        // 到不了的话滑块永远停在离底一点的位置，看起来像"还有内容没显示"
        assertEquals(99, scrollIndexForDrag(dragY = 1000f, trackHeightPx = 1000f, itemCount = 100))
    }

    @Test
    fun `drag beyond the track is clamped`() {
        // 手指拖出轨道外（常见于快速拖动）不应算出越界下标而崩
        assertEquals(0, scrollIndexForDrag(dragY = -500f, trackHeightPx = 1000f, itemCount = 100))
        assertEquals(99, scrollIndexForDrag(dragY = 5000f, trackHeightPx = 1000f, itemCount = 100))
    }

    @Test
    fun `zero track height or empty list does not crash`() {
        assertEquals(0, scrollIndexForDrag(0f, 0f, 100))
        assertEquals(0, scrollIndexForDrag(100f, 1000f, 0))
        assertEquals(0, scrollIndexForDrag(100f, 1000f, 1))
    }

    @Test
    fun `thumb reaches both ends of the track`() {
        // 首项在顶部、末项在底部，中间线性插值
        assertEquals(0f, thumbYForScrollIndex(0, 1000f, 100, 50f), 0.01f)
        assertEquals(950f, thumbYForScrollIndex(99, 1000f, 100, 50f), 0.01f)
        assertEquals(950f * 49f / 99f, thumbYForScrollIndex(49, 1000f, 100, 50f), 0.5f)
    }

    @Test
    fun `thumb height reflects the visible fraction`() {
        // 看得见的占 1/10 → 滑块占轨道 1/10
        assertEquals(100f, thumbHeightForTrack(1000f, visibleCount = 10, itemCount = 100, minThumbPx = 20f), 0.01f)
    }

    @Test
    fun `thumb height never shrinks below the minimum`() {
        // ⚠️ 列表很长时按比例算出来的滑块只有一两像素，根本按不住。
        // 下限保证它始终可点
        assertEquals(20f, thumbHeightForTrack(1000f, visibleCount = 1, itemCount = 100000, minThumbPx = 20f), 0.01f)
    }

    @Test
    fun `thumb height never exceeds the track`() {
        // 内容比视口还少时，比例会 >1 —— 不夹住的话滑块比轨道还长
        assertEquals(1000f, thumbHeightForTrack(1000f, visibleCount = 500, itemCount = 10, minThumbPx = 20f), 0.01f)
    }

    private fun line(tag: String) = LogcatLine(
        tag = tag,
        message = "m",
        level = LogLevel.INFO,
        pid = 1,
        tid = 1,
        timestamp = "09-19 10:00:00.000",
        raw = tag,
        isContinuation = false,
    )

    // ── 缓冲区标记行（真机实测踩到的）───────────────────────────

    @Test
    fun `capture stats filters out logcat buffer marker lines`() {
        // ★★ 真机实测：采集文件的**第一行**通常不是日志，而是
        //     `--------- beginning of main`
        // 直接取首行前 18 字符，得到的是 `--------- beginnin` ——
        // 这就是状态栏里显示"覆盖 beginnin–14:57:29"的由来。
        val cmd = LogcatCommands.buildCaptureStats()
        assertTrue("必须反选掉标记行", cmd.contains("grep -avE"))
        assertTrue("匹配 5 个以上短横开头", cmd.contains("^-{5,}"))
    }

    @Test
    fun `capture stats uses -E for the marker pattern`() {
        // ⚠️ **同一个坑第二次**：`{5,}` 是 ERE 语法，grep 默认按 BRE 解析时
        // 会把它当**字面字符**，于是这条反选匹配不到任何行 —— 标记行原样留下。
        //
        // 实测症状：`TOTAL` 比实际多 1、首行仍是 `--------- beginnin`。
        // 项目里 `buildSearch` 早就因为这个踩过坑（`[ ]+` 缺 -E 导致搜不到日志），
        // 写这条新命令时又忘了。
        val cmd = LogcatCommands.buildCaptureStats()
        assertTrue("标记行过滤必须带 -E", cmd.contains("-avE"))
        assertFalse("不能写成不带 -E 的 -av", cmd.contains("grep -av "))
    }

    @Test
    fun `capture stats does not put single quotes inside the inner script`() {
        // ⚠️ shellQuote 会把整段脚本再包一层单引号，脚本内部若出现单引号
        // 会被再解析一次。正则用双引号即可（`^-{5,}` 没有 shell 特殊字符）。
        val cmd = LogcatCommands.buildCaptureStats()
        val inner = cmd.removePrefix("timeout 60 sh -c ").removeSurrounding("'")
        assertFalse("内层脚本不应含单引号", inner.contains("'"))
    }

    @Test
    fun `capture stats wraps the level pattern in a character class`() {
        // ★★★ 用户报「TAG 统计始终为 0 / 没有统计到任何 TAG」的根因。
        //
        // 级别过滤是 sed 匹配式里的一个**字符类**，必须写成 `[DIWEF]`。
        // 原实现丢掉了方括号，直接串进裸的 `DIWEF` —— 那会被当成
        // "依次出现 D、I、W、E、F 这五个字符"，而日志行里级别只占**一个**字符位置，
        // 于是**永远匹配不到任何行**，TAG 列表恒为空。
        //
        // ⚠️ 这个 bug 长期没暴露，因为**默认级别是 VERBOSE**，
        // 而那个分支当初恰好写成了带括号的 `[VDIWEF]`。
        // 所以必须**逐个级别**验证，不能只测默认值。
        LogLevel.entries.forEach { level ->
            val chars = levelCharsFrom(level)
            val cmd = LogcatCommands.buildCaptureStats(level)
            assertTrue(
                "级别 $level 的字符类必须是 [$chars]（带方括号），否则 TAG 永远提取不到。命令：$cmd",
                cmd.contains("[$chars]"),
            )
        }
    }

    @Test
    fun `the level pattern is never the only occurrence without brackets`() {
        // ⚠️ 只断言"含 [$chars]"是不够的 —— 万一代码写成 `DIWEF[DIWEF]`，
        // 上一条仍会通过，而裸写的那处照样把匹配搞挂。
        // 这里反过来验证：级别字符**每一次出现**都必须处在方括号里。
        LogLevel.entries.forEach { level ->
            val chars = levelCharsFrom(level)
            val cmd = LogcatCommands.buildCaptureStats(level)
            // 把命令里所有形如 [XXX] 的字符类挖掉，剩下的文本里不该再出现这些级别字符
            val stripped = cmd.replace(Regex("""\[$chars]"""), "")
            // 残留的裸写：紧邻的字母序列里含这些字符，且不在方括号内
            val bare = Regex("""(?<![\[A-Z])[$chars]{2,}(?![\]A-Z])""").find(stripped)
            assertNull(
                "级别 $level 出现了裸写的字符序列（会匹配不到任何行）：${bare?.value}；命令：$cmd",
                bare,
            )
        }
    }

    /** 按 [LogcatCommands.buildCaptureStats] 的口径算出该级别及以上应有的级别字符集。 */
    private fun levelCharsFrom(minLevel: LogLevel): String =
        LogLevel.entries
            .filter { it.priority >= minLevel.priority }
            .joinToString("") { it.char.toString() }

    // ── 全量命中统计（页数改按过滤后命中数算）────────────────────

    @Test
    fun `filtered count reuses the same pattern as the read`() {
        // ★ 口径必须与读取**完全同源**。
        //
        // 统计说"命中 500 条"、翻页却只翻得出 300 条 —— 这种不一致
        // 比不显示统计还糟。两者都调 buildSearchPattern，这里锁住这一点：
        // 同样的过滤条件，统计命令里的 grep 正则必须与读取命令里的一致。
        val tag = "MyTag"
        val msg = "error"
        val level = LogLevel.WARN

        val countCmd = LogcatCommands.buildFilteredCount(tag, msg, level)
        val searchCmd = LogcatCommands.buildSearch(tag, msg, level)

        // 从两条命令里各抠出 grep -e '...' 的正则，应当逐字相同
        fun regexOf(cmd: String): String? =
            Regex("""grep -aE\w* -e '([^']+)'""").find(cmd)?.groupValues?.get(1)

        val countRegex = regexOf(countCmd)
        val searchRegex = regexOf(searchCmd)
        assertNotNull("统计命令里应能抠出 grep 正则：$countCmd", countRegex)
        assertEquals("统计与读取必须用同一个过滤正则", searchRegex, countRegex)
    }

    @Test
    fun `filtered count only returns a number not the matching lines`() {
        // ⚠️ 只回传一个数字，不回传命中行 / 位置索引。
        // 命中可能上万条，索引几百 KB 会撞 Binder 上限打死 UserService
        // （类注释第 1 条的真机实测教训）。这里确认命令里以 `wc -l` 收尾。
        val cmd = LogcatCommands.buildFilteredCount()
        assertTrue("应用 wc -l 只数行数：$cmd", cmd.contains("wc -l"))
        assertFalse("不该出现会输出内容的操作", cmd.contains("| head -c") || cmd.contains("| tail -c"))
    }

    @Test
    fun `filtered count filters out buffer marker lines`() {
        // 标记行（`--------- beginning of main`）不是日志，
        // 混进命中数会让页数虚高
        val cmd = LogcatCommands.buildFilteredCount()
        assertTrue("必须反选标记行：$cmd", cmd.contains("-avE"))
        assertTrue(cmd.contains("^-{5,}"))
    }

    @Test
    fun `filtered count uses wc not grep -c`() {
        // ⚠️ `grep -c` 在**无匹配时退出码为 1**，而 ShellManager 会把非零退出码
        // 包成 `Error: ...` —— 于是"0 条命中"看起来像"命令失败"。
        // `wc -l` 无匹配时输出 0 且退出码 0，是正确选择。
        val cmd = LogcatCommands.buildFilteredCount()
        assertFalse("不能用 grep -c（无匹配时退出码为 1）", cmd.contains("grep -c"))
        assertTrue(cmd.contains("wc -l"))
    }

    @Test
    fun `filtered count redirects stdin so it cannot hang`() {
        // 没有采集文件时 CAPTURE_GLOB 不展开，`cat` 无参数会**读 stdin 而挂死**
        val cmd = LogcatCommands.buildFilteredCount()
        assertTrue("cat 必须重定向 stdin：$cmd", cmd.contains("</dev/null"))
    }

    @Test
    fun `filtered count result parsing`() {
        assertEquals(12345, LogcatCommands.parseFilteredCount("12345\n"))
        assertEquals(0, LogcatCommands.parseFilteredCount("0"))
        assertEquals(7, LogcatCommands.parseFilteredCount("\n  7  \n"))
        // ⚠️ 解析不出来必须是 null 而不是 0 ——
        // "不知道有多少" 与 "一条都没有" 对界面的含义完全不同
        assertNull("空输出不能当成 0", LogcatCommands.parseFilteredCount(""))
        assertNull("非数字不能当成 0", LogcatCommands.parseFilteredCount("Error: something"))
    }

    // ── 按行数分页 ───────────────────────────────────────────────

    @Test
    fun `page one skips nothing`() {
        // 第 1 页 = 最新一页，不跳过任何内容
        assertEquals(0L, LogcatCommands.skipBytesForPage(page = 1, linesPerPage = 1000))
    }

    @Test
    fun `page offset is page minus one times lines per page`() {
        // ★ 页码按**行数**分页（用户定的语义）：
        // 第 N 页跳过 (N-1) × 每页条数 行。
        val skip = LogcatCommands.skipBytesForPage(page = 3, linesPerPage = 1000)
        assertEquals(2L * 1000 * LogcatCommands.AVG_LINE_BYTES, skip)
    }

    @Test
    fun `page offset tracks the line limit so paging stays consistent`() {
        // ⚠️ 这正是"按行数分页"要修的问题：页码的物理含义由每页条数决定。
        // 若偏移与条数脱钩，切一次条数就会翻到完全无关的位置。
        val a = LogcatCommands.skipBytesForPage(page = 2, linesPerPage = 500)
        val b = LogcatCommands.skipBytesForPage(page = 2, linesPerPage = 2000)
        assertEquals("第 2 页的偏移应随每页条数线性变化", a * 4, b)
    }

    @Test
    fun `page offset is zero for out-of-range pages`() {
        // 越界页码不该算出负偏移（负值会让 tail -c 报错）
        assertEquals(0L, LogcatCommands.skipBytesForPage(page = 0, linesPerPage = 1000))
        assertEquals(0L, LogcatCommands.skipBytesForPage(page = -5, linesPerPage = 1000))
    }

    @Test
    fun `page window grows with the line limit`() {
        // 选了 2000 条却只给 16MB 窗口的话，窄过滤下取不满一页
        val small = LogcatCommands.windowKbForPageLines(200)
        val large = LogcatCommands.windowKbForPageLines(2000)
        assertTrue("条数越多窗口越大(" + small + " -> " + large + ")", large > small)
    }

    @Test
    fun `page window is clamped to the allowed range`() {
        // 窗口要经跨进程传输，不能无限大
        assertEquals(
            LogcatCommands.MAX_READ_WINDOW_KB,
            LogcatCommands.windowKbForPageLines(999_999),
        )
    }

    // ── 孤儿 logcat 进程（真机事故）────────────────────────────

    @Test
    fun `stop kills leftover capture processes not just the pidfile pid`() {
        // ★★ 真机事故：设备上累积了 **24 个**采集进程同时写一个文件 ——
        // 「点采集到结束只差 2 秒」却拿到几小时的日志，且持续吃 CPU。
        //
        // 真机复现出的链条：pidfile 丢失时 `kill $(cat PIDFILE)` 变成**裸 kill**，
        // 报错被 2>/dev/null 吞掉，但后面的 rm/touch 用 `;` 连接所以照常执行 ——
        // 界面据此显示"已完成"、通知消失，**而进程一个都没死**。
        val cmd = LogcatCommands.buildStopCapture()
        assertTrue("必须按特征兜底杀进程（ps + kill）", cmd.contains("ps -A -o PID,ARGS"))
        assertTrue("特征串要含采集参数", cmd.contains("logcat"))
    }

    @Test
    fun `start also cleans up leftovers before launching`() {
        // 启动时也要先清理 —— 否则一旦有过残留，它们会一直累积下去
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue("启动前必须清残留", cmd.contains("ps -A -o PID,ARGS"))
    }

    @Test
    fun `cleanup happens before the capture files are deleted`() {
        // ⚠️ 顺序要紧：先杀进程再删文件。
        // 反过来的话，残留进程会**立刻重新创建**这几个文件并继续写，
        // 于是"新采集"的文件里混着旧进程的输出。
        val cmd = LogcatCommands.buildStartCapture()
        val killAt = cmd.indexOf("ps -A -o PID,ARGS")
        val rmAt = cmd.indexOf("rm -f " + LogcatCommands.CAPTURE_FILE)
        assertTrue("应能找到杀进程与 rm", killAt >= 0 && rmAt >= 0)
        assertTrue("杀进程必须在 rm 之前", killAt < rmAt)
    }

    @Test
    fun `stop does not kill when the pidfile is missing`() {
        // ⚠️ `kill $(cat 缺失文件)` 在 sh 里会变成**裸 kill**（无参数），
        // 行为完全变了。必须先判断文件非空（`-s` 而非 `-f`：空文件同样有问题）。
        val cmd = LogcatCommands.buildStopCapture()
        assertTrue("必须先判断 pidfile 非空", cmd.contains("if [ -s "))
    }

    @Test
    fun `the kill pattern matches the start command parameters`() {
        // ⚠️ 两边的轮转参数必须一致，否则改过参数后就杀不掉了：
        // pkill 的特征串匹配不上实际启动的命令行。
        val start = LogcatCommands.buildStartCapture(rotateKb = 1024, rotateCount = 2)
        val stop = LogcatCommands.buildStopCapture()
        // stop 用默认参数，所以这里断言的是"特征串里确实带了参数"
        assertTrue("特征串应含 -r 参数", stop.contains("-r "))
        assertTrue("特征串应含 -n 参数", stop.contains("-n "))
        assertTrue("启动命令也应有同样的参数", start.contains("-r 1024") && start.contains("-n 2"))
    }

    // ── 杀残留进程（真机 Error 143）────────────────────────────

    @Test
    fun `the kill logic never uses pkill -f`() {
        // ★★ 真机事故：点「开始采集」报
        //     Error (code 143): Command failed with no error message
        // 143 = 128 + 15 = **SIGTERM** —— 被自己发出的信号打死。
        //
        // 根因：应用用 `ProcessBuilder("sh","-c",整条命令)` 执行，
        // 于是 **sh 自己的命令行里完整包含这段命令文本**；
        // 而 `pkill -f` 匹配完整命令行 → **匹配到自己** → 自杀。
        //
        // 用字符类 `[l]ogcat` 也救不了：它只是让「模式字符串自身」不再匹配，
        // 命令里别处的 `logcat`（`/system/bin/logcat`、`logcat_capture.log`）
        // 照样被这个正则匹配到 —— 依然自杀。**这个坑踩了两次。**
        //
        // 现在改成 ps 取 pid、显式排除 ${"$"} 与 $PPID，不依赖任何正则技巧。
        val stop = LogcatCommands.buildStopCapture()
        val start = LogcatCommands.buildStartCapture()
        assertFalse("停止不应再用 pkill -f", stop.contains("pkill"))
        assertFalse("启动不应再用 pkill -f", start.contains("pkill"))
    }

    @Test
    fun `the kill logic excludes itself and its parent`() {
        // 不排除自身的话，kill 会把执行命令的 shell 一起杀掉 ——
        // 表现就是退出码 143、命令"失败"。
        val stop = LogcatCommands.buildStopCapture()
        assertTrue("必须取自身 pid", stop.contains("ME="))
        assertTrue("必须排除自身", stop.contains("!= \"${"$"}ME\""))
        assertTrue("必须排除父 shell", stop.contains("!= \"${"$"}PPID\""))
    }

    @Test
    fun `the kill pattern will not match the trigger stream`() {
        // ⚠️ logcat 触发器的流是 `logcat -v threadtime -T 1`，
        // 不含 -r/-n/-f。特征串必须带这三个参数，否则会误杀触发器的流。
        // （真机已验证：带参数的特征串不会命中 -T 1 那条）
        val stop = LogcatCommands.buildStopCapture()
        assertTrue("特征串应含 -r", stop.contains("-r "))
        assertTrue("特征串应含 -n", stop.contains("-n "))
        assertTrue("特征串应含 -f（输出文件）", stop.contains("-f "))
    }

    @Test
    fun `stop guards the pidfile read against a missing file`() {
        // ⚠️ `kill ${"$"}(cat PIDFILE)` 在 pidfile 丢失时会变成**裸 kill**，
        // 行为完全变了（报错被 2>/dev/null 吞掉，后续语句照跑）。
        // 这就是 24 个孤儿进程累积的直接原因。
        val stop = LogcatCommands.buildStopCapture()
        assertTrue("必须先判断 pidfile 非空（-s 而非 -f）", stop.contains("if [ -s "))
    }

    // ── 采集必须带 -T 1（"采 2 秒得到 65 分钟"的真因）────────────

    @Test
    fun `capture starts from now not from the whole buffer`() {
        // ★★ 真机现象：点采集和结束只差 2 秒，结果却有 65 分钟的日志；
        // 而且**手动删掉文件后重新采，仍是 65 分钟** ——
        // 说明内容不来自文件，来自 logcat 的**内存缓冲区**。
        //
        // 根因：`logcat` 不带 `-T` 时**默认从缓冲区开头 dump 全部已有日志**，
        // 然后才跟随新日志。缓冲区里积压几十分钟，就全进了采集文件。
        //
        // `-T 1` = 只从最后 1 行开始，即"从现在起"。
        // 触发器的流（LogcatStreamWrapper）一直用的是 `-T 1`，
        // **查看器这条采集命令当初漏了它**。
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue("采集必须从当前时刻开始：$cmd", cmd.contains("-T 1"))
    }

    @Test
    fun `the -T flag is part of the logcat invocation not the kill pattern`() {
        // ⚠️ 不能用 `indexOf("-r ")` 做位置断言 ——
        // 命令前面还有清理残留的 `ps ... grep "..."` 特征串，里面**也有 `-r `**，
        // 会匹配到那一处而不是真正的启动参数（第 143 那次就是被这类问题坑的）。
        // 这里锚定到"logcat 启动前缀"这整段。
        val cmd = LogcatCommands.buildStartCapture()
        val launch = "timeout 300 " + LogcatCommands.LOGCAT
        val launchAt = cmd.indexOf(launch)
        assertTrue("应能找到启动命令", launchAt >= 0)
        val after = cmd.substring(launchAt)
        assertTrue("-T 1 必须在 logcat 的参数里：$after", after.contains("-T 1 -r "))
    }

    /** 从 kill 命令里抠出 grep 的特征串，并把防自匹配的 `[l]ogcat` 还原。 */
    private fun killPatternOf(killCmd: String): String {
        val raw = Regex("""grep "([^"]+)"""").find(killCmd)?.groupValues?.get(1)
        requireNotNull(raw) { "抠不出特征串，kill 命令格式变了？kill=$killCmd" }
        return raw.replace("[l]ogcat", "logcat")
    }

    @Test
    fun `the kill pattern matches what ps actually shows not the launch command`() {
        // ★★★ 这是「结束采集后文件还在涨」的**真正**根因回归测试。
        //
        // 前两版测试都拿特征串去比对 **buildStartCapture 的输出**，于是通过了 ——
        // 但那是错的参照物：**`ps` 显示的 ARGS 与启动命令行不是一回事**。
        //
        // 真机实测（Android 17）：
        //   启动命令:  timeout 300 /system/bin/logcat -v threadtime -T 1 -r 65536 ...
        //   ps 显示:    8857 logcat -v threadtime -T 1 -r 65536 -n 4 -f ...
        //                    ↑ logcat 启动后把 argv[0] 改写成了 "logcat"，路径没了
        //
        // 所以特征串带 `/system/bin/` 前缀时，grep **一个进程都匹配不到**，
        // 停止采集只杀得掉 pidfile 里的 timeout，logcat 本体继续写。
        //
        // 这条断言锚定真正要匹配的东西：**ps 的输出形状**。
        val psLine =
            " 8857 logcat -v threadtime -T 1 -r 65536 -n 4 -f " + LogcatCommands.CAPTURE_FILE
        val pattern = killPatternOf(LogcatCommands.killCapturedProcesses())

        assertTrue(
            "特征串必须能匹配 ps 的输出行，否则杀不掉采集进程。\n" +
                "  ps 输出：$psLine\n  特征串：  $pattern",
            psLine.contains(pattern),
        )
        assertFalse(
            "特征串**不能**带绝对路径 —— ps 的 ARGS 里没有它。特征串：$pattern",
            pattern.contains("/system/bin/"),
        )
    }

    @Test
    fun `the kill pattern starts at the program name`() {
        // 特征串必须**从程序名开始**。带路径或带前导空格都会让它不再是
        // ps 输出的连续子串（前导空格尤其隐蔽：ps 的 ARGS 前是 PID 列）
        val pattern = killPatternOf(LogcatCommands.killCapturedProcesses())
        assertTrue(
            "特征串应以 logcat 开头，实为：$pattern",
            pattern.startsWith("logcat "),
        )
    }

    @Test
    fun `the kill pattern carries the same rotation params as the launch`() {
        // 非默认轮转参数也必须贯穿到查杀。否则一旦有人传了自定义 rotate，
        // 启动用新参数、查杀还按旧参数匹配 → 静默失配（同一条根因）
        val pattern = killPatternOf(
            LogcatCommands.killCapturedProcesses(rotateKb = 1024, rotateCount = 2)
        )
        val psLine = " 1234 logcat -v threadtime -T 1 -r 1024 -n 2 -f " + LogcatCommands.CAPTURE_FILE

        assertTrue(
            "自定义轮转参数下特征串仍须匹配 ps 输出。\n" +
                "  ps 输出：$psLine\n  特征串：  $pattern",
            psLine.contains(pattern),
        )
    }

    @Test
    fun `the kill pattern includes the -T flag it must match`() {
        // 反向锁：`-T 1` 已经成了查杀识别的必要组成。
        // 将来若移除 / 改写 `-T 1`，这条会提醒"必须同步考虑查杀"，
        // 而不是等到真机上"文件一直涨"才发现
        val kill = LogcatCommands.killCapturedProcesses()
        assertTrue("特征串必须含 -T 1（与实际命令行一致）：$kill", kill.contains("-T 1"))
    }

    @Test
    fun `the kill pattern does not match the logcat trigger stream`() {
        // 误杀防线：触发器的流是 `logcat -v threadtime -T 1`，**不含 -r/-n/-f**，
        // 与采集进程的区别必须保持。若特征串被放宽到只认 logcat，触发器会被反复杀掉
        val pattern = killPatternOf(LogcatCommands.killCapturedProcesses())
        val triggerStream = "19144 logcat -v threadtime -T 1"

        assertFalse(
            "触发器的流不该被采集查杀匹配到。特征串：$pattern",
            triggerStream.contains(pattern),
        )
    }

    @Test
    fun `snapshot mode is unaffected by the -T fix`() {
        // 空闲态的 `logcat -d -T N` 本来就是"取最近 N 行"，语义不同，别被误改
        val snap = LogcatCommands.buildSnapshot(lines = 100)
        assertTrue("快照仍应是 -d + -T N", snap.contains("-d") && snap.contains("-T 100"))
    }

    // ── 翻页游标 ★（修「翻页漏内容」）────────────────────────────

    @Test
    fun `advancing a page uses the actual content size not an estimate`() {
        // ★ 这是「翻页漏内容」修复的核心断言。
        //
        // 原实现步进 = 条数 × 200（估算行长）。真实行长 141 时，
        // 步进能覆盖 1418 行而一页只显示 1000 行 → 每页漏掉 418 行，
        // 且**不报错**，用户只会觉得"有些日志翻不到"。
        //
        // 新实现用上一页**实际内容**的字节数推进，逐行对齐。
        // 用 141 字节/行（与估算值 200 明显不同）验证它不再依赖估算。
        val realLineBytes = 141L
        val lines = 1000
        val contentBytes = lines * realLineBytes

        val next = LogcatCommands.nextPageSkipBytes(
            previousSkipBytes = 0L,
            previousContentBytes = contentBytes,
            lineLimit = lines,
        )
        assertEquals(
            "步进必须等于实际内容量，不能是 lines × 200 的估算",
            contentBytes,
            next,
        )
        assertNotEquals(
            "绝不能退回估算值（那正是漏 42% 的来源）",
            lines.toLong() * LogcatCommands.AVG_LINE_BYTES,
            next,
        )
    }

    @Test
    fun `advancing a page accumulates from the previous cursor`() {
        // 连续翻页必须是累加，否则每次都会读回同一段
        val next = LogcatCommands.nextPageSkipBytes(
            previousSkipBytes = 12_345L,
            previousContentBytes = 1_000L,
            lineLimit = 100,
        )
        assertEquals(13_345L, next)
    }

    @Test
    fun `an empty page means we cannot go further back`() {
        // 读到文件开头时，再往前跳只会跳进虚空。
        // 返回 null 让调用方**明确提示**，而不是拿估算值去跳
        assertNull(
            "空页应表示翻不动了",
            LogcatCommands.nextPageSkipBytes(0L, previousContentBytes = 0L, lineLimit = 100),
        )
    }

    @Test
    fun `paging tolerates absurd inputs`() {
        // 条数为 0 / 负数时不该算出负的或不动的 skip
        assertNull(LogcatCommands.nextPageSkipBytes(0L, 1000L, lineLimit = 0))
        assertNull(LogcatCommands.nextPageSkipBytes(0L, 1000L, lineLimit = -5))
        // 负的 skip 夹到 0
        assertEquals(1000L, LogcatCommands.nextPageSkipBytes(-999L, 1000L, lineLimit = 100))
    }

    @Test
    fun `content size counts the raw bytes of each line`() {
        // ⚠️ 必须用 raw（原始行）而不是拼接后的展示文本 ——
        // 定窗读取按字节定位，换算回去要用同一口径才不会错位。
        val line = LogcatLine(
            tag = "T", message = "m", level = LogLevel.INFO,
            pid = 1, tid = 1, timestamp = "09-21 00:00:00.000",
            raw = "abcdefghij",     // 10 字节
            isContinuation = false,
        )
        // 10 字节内容 + 1 字节换行
        assertEquals(11L, LogcatCommands.contentBytesOf(listOf(line)))
        assertEquals(22L, LogcatCommands.contentBytesOf(listOf(line, line)))
        assertEquals("空列表应为 0", 0L, LogcatCommands.contentBytesOf(emptyList()))
    }

    @Test
    fun `content size counts multibyte characters by their utf-8 bytes`() {
        // 日志里大量中文；按字符数算会让步进偏小、翻页重叠
        val line = LogcatLine(
            tag = "T", message = "中文", level = LogLevel.INFO,
            pid = 1, tid = 1, timestamp = "09-21 00:00:00.000",
            raw = "中文",            // 6 字节 UTF-8（2 字 × 3）
            isContinuation = false,
        )
        assertEquals("按 UTF-8 字节算，不是字符数", 7L, LogcatCommands.contentBytesOf(listOf(line)))
    }
}