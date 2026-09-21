package com.chaomixian.vflow.server.wrappers

import org.json.JSONObject
import java.io.BufferedReader
import java.io.PrintWriter

/**
 * 支持长连接事件推送的 Wrapper 接口。
 *
 * ## 为什么 `handleStream` 需要 `reader`
 *
 * 原先的签名只有 `writer`——流建立之后**这条连接就再没有上行数据了**。
 * 对纯推送型的流（如剪贴板）够用，但对**需要中途改条件**的流（如 logcat 触发器）
 * 就不够：App 改了触发器条件后，没有任何途径把新条件送进 Core。
 *
 * 补上 `reader` 后，流从「单向推送」升级为「双工」——
 * handleStream 的实现可以一边推事件、一边读控制帧。
 *
 * ⚠️ **实现者注意**：`reader.readLine()` 与推事件的循环**不能在同一线程上串行**
 * （读会阻塞）。需要把「产出事件」放到独立线程/协程，主循环留给控制帧。
 * 见 `LogcatStreamWrapper` 的线程模型说明。
 *
 * ⚠️ **兼容性**：`reader` 是**新增参数**，所有既有实现都要同步改签名。
 * 目前只有 `IClipboardWrapper` 一个实现（它忽略该参数即可）。
 */
interface StreamingWrapper {
    /**
     * 处理流式请求。
     *
     * @param method 订阅方法名
     * @param params 订阅参数（可携带初始状态）
     * @param writer 向 App 推送事件的通道（每行一个 JSON 对象）
     * @param reader 来自 App 的**上行**通道。订阅帧之后 App 仍可继续写控制帧；
     *               返回 null / 抛异常表示 App 已断开，应结束本流
     * @return true 表示该请求已被消费，调用方不应再继续按普通 request/response 处理
     */
    fun handleStream(
        method: String,
        params: JSONObject,
        writer: PrintWriter,
        reader: BufferedReader,
    ): Boolean
}
