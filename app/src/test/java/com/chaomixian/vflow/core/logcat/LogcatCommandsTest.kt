package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
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
        // app_process / sh 环境的 PATH 不确定，必须用绝对路径
        val cmd = LogcatCommands.buildStartCapture()
        assertTrue(cmd.startsWith(LogcatCommands.LOGCAT))
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
    fun `tail bounds the line count`() {
        assertTrue(LogcatCommands.buildTail(300).contains("tail -n 300"))
        assertTrue(LogcatCommands.buildTail(999_999).contains("tail -n ${LogcatCommands.MAX_LINES}"))
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
}
