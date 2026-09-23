package com.chaomixian.vflow.server.common

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由配置的一致性测试。
 *
 * ## 为什么值得单独测
 *
 * Core 有**两张**路由表，分别服务两条路径：
 *
 * | | 普通请求 | 流式请求 |
 * |---|---|---|
 * | 查哪张表 | [Config.ROUTING_TABLE] | [Config.STREAM_METHODS] |
 * | 转发方式 | 一问一答 | 独占连接，持续推流 |
 *
 * 新增一个流式 target 时**只加前者**，请求会被流式白名单拦下、
 * 然后落到普通路径去转发 —— 而那条路承载不了长连接，
 * 表现是 `ECONNREFUSED`（Worker 连接被拒），**同时 `ping` 却正常**
 * （它走 Master 内部处理，不经过 Worker）。
 *
 * 这个坑已实际踩过一次（logcat 触发器），症状是"触发器完全不工作"，
 * 而日志里只有一行看起来像网络问题的错误。
 */
class RoutingTableConsistencyTest {

    @Test
    fun `every stream target exists in the routing table`() {
        // ⚠️ 核心不变量。Config 的 init 块里也有同样的 check，
        // 但那条只在类被加载时才触发 —— 这里让它在**构建期**就暴露
        Config.STREAM_METHODS.forEach { (method, target) ->
            assertTrue(
                "流式方法 $method 指向 target「$target」，但它不在 ROUTING_TABLE 里。" +
                    "该流将无法工作 —— 新增流式能力时必须同时更新两张表。",
                Config.ROUTING_TABLE.containsKey(target)
            )
        }
    }

    @Test
    fun `clipboard streaming is still registered`() {
        // 回归保护：改动这张表时不要把既有的剪贴板流弄丢
        assertTrue(
            "剪贴板流必须保留",
            Config.STREAM_METHODS.containsKey("subscribeClipboardStream")
        )
    }

    @Test
    fun `logcat streaming is registered`() {
        // 这次的受害者。它缺失时的症状见类注释
        assertTrue(
            "logcat 流必须登记 —— 漏了它触发器会完全不工作",
            Config.STREAM_METHODS.containsKey("subscribeLogcatStream")
        )
    }

    /**
     * 数据卡切换（`isub`）走的是**普通请求**而非流式，所以它只需登记在
     * [Config.ROUTING_TABLE] 里。这条测试锁住这个事实 ——
     * 它是"只加了 serviceWrappers 忘了加路由表"那个坑的第四位受害者预定位。
     *
     * 症状：Core 侧 wrapper 写好了、ShellWorker 也注册了，
     * 但请求在 Master 这一层就回 `{"error":"No route"}`，模块直接报失败。
     */
    @Test
    fun `isub target is routable for data subscription switching`() {
        assertTrue(
            "isub 必须登记在 ROUTING_TABLE —— 漏了则数据卡切换模块直接报 No route",
            Config.ROUTING_TABLE.containsKey("isub")
        )
    }

    @Test
    fun `the isub target routes to the shell worker`() {
        // setDefaultDataSubId 需要 MODIFY_PHONE_STATE，而 shell (UID 2000)
        // 在 AOSP 里持有该权限（packages/Shell/AndroidManifest.xml）。
        // 不该走 root worker —— 那会要求用户必须有 root，而无谓地抬高门槛。
        assertTrue(
            "isub 应路由到 SHELL worker（shell 恰好持有 MODIFY_PHONE_STATE）",
            Config.ROUTING_TABLE["isub"] == Config.WorkerType.SHELL
        )
    }

    @Test
    fun `isub is not accidentally registered as a stream target`() {
        // isub 是普通的一问一答请求。若有人把它加进 STREAM_METHODS，
        // 会走独占长连接路径 —— 那条路没有对应的 wrapper 签名，会挂住。
        assertTrue(
            "isub 不是流式能力，不应出现在 STREAM_METHODS 里",
            Config.STREAM_METHODS.values.none { it == "isub" }
        )
    }

    @Test
    fun `stream targets are not accidentally duplicated across methods`() {
        // 同一个 target 被两个订阅方法指向是允许的（一个 target 可以有多种流），
        // 但**方法名不能重复** —— 那是 map 的 key，重复会静默覆盖
        val methods = Config.STREAM_METHODS.keys
        assertTrue("方法数应与表大小一致（无重复 key）", methods.size == Config.STREAM_METHODS.size)
    }

    @Test
    fun `the logcat target routes to the shell worker`() {
        // logcat 只需要 shell 身份（shell 在 log 组里，能读全量日志），
        // 不该走 root worker —— 那会要求用户必须有 root
        assertTrue(
            "logcat 应路由到 SHELL worker",
            Config.ROUTING_TABLE["logcat"] == Config.WorkerType.SHELL
        )
    }
}
