package com.chaomixian.vflow.ui.chat

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **非流式入口的线程归属**（验收退回点 / 需求 #6「ChatAgent 既有行为不得回退」）。
 *
 * ## 被锁住的是什么
 *
 * `generateReply` 走的是**同步阻塞**的 `httpClient.newCall(...).execute()`
 * （`complete()` → `executeJsonRequest()`）。它的生产调用方在 **Main** 上：
 * `ChatScreen.onStartBenchmark` → `ChatViewModel.startBenchmarkRun` 的
 * `viewModelScope.launch {` → `ChatBenchmarkRunner.generateReply`（该文件全篇没有任何
 * `withContext` / `Dispatchers` 切换）。故 `generateReply` **自己必须**把 adapter 调用
 * 包进 `withContext(Dispatchers.IO)` —— 这正是改造中被弄丢、又被验收退回的那一处。
 *
 * ## 做法
 *
 * 把调用方协程钉在**单线程 dispatcher** 上（线程名固定为 [CALLER_THREAD_NAME]），
 * 再用 OkHttp `Interceptor` 记录**真正执行 `execute()` 的线程名**。
 * 断言两者**不相同** ⇒ 阻塞 HTTP 没有落在调用方线程上。
 *
 * ## ⚠️ 必做反证（本条就是本用例存在的理由）
 *
 * 去掉 `generateReply` 里那个 `withContext(Dispatchers.IO)`（把 `complete(request)` 直接
 * 返回）⇒ 拦截器记录的线程名变成 [CALLER_THREAD_NAME] ⇒
 * [generateReply_keepsBlockingHttpOffTheCallerThread] **变红**。
 *
 * ## 为什么不是「恒真断言」
 *
 * 断言锚在**改造中新增的那次 `withContext(Dispatchers.IO)` 所产生的线程切换**上，
 * 而不是任何改造前就存在的标识符。为防止「调用方协程其实没跑在单线程上」导致假绿，
 * 用例同时**正向**断言了协程体所在线程确实等于 [CALLER_THREAD_NAME]（前提校验）。
 */
class ChatCompletionThreadAffinityTest {

    private lateinit var server: MockWebServer

    /** 真正执行 `httpClient...execute()` 的线程名。 */
    private val httpThreadName = AtomicReference<String?>(null)

    private val httpClient = OkHttpClient.Builder()
        // 同步请求的拦截器链就跑在**调用 `execute()` 的那个线程**上，
        // 故这里记到的名字就是「阻塞 HTTP 落在哪个线程」的直接证据。
        .addInterceptor(
            Interceptor { chain ->
                httpThreadName.set(Thread.currentThread().name)
                chain.proceed(chain.request())
            },
        )
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun generateReply_keepsBlockingHttpOffTheCallerThread() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))

        val callerDispatcher = Executors
            .newSingleThreadExecutor { runnable -> Thread(runnable, CALLER_THREAD_NAME) }
            .asCoroutineDispatcher()
        try {
            val client = ChatCompletionClient(httpClient = httpClient)
            var coroutineThreadName: String? = null

            val result = withContext(callerDispatcher) {
                coroutineThreadName = Thread.currentThread().name
                // 内层 runBlocking 不继承外层上下文 ⇒ 它的 body 就跑在**当前线程**（单线程 dispatcher）
                runBlocking {
                    client.generateReply(
                        preset = ChatPresetConfig(
                            provider = ChatProvider.OPENAI.storageValue,
                            model = "test-model",
                            apiKey = "test-key",
                            baseUrl = server.url("/v1").toString(),
                        ),
                        history = listOf(
                            ChatMessage(
                                id = "m1",
                                role = ChatMessageRole.USER,
                                content = "hi",
                                timestampMillis = 1L,
                            ),
                        ),
                    )
                }
            }

            // 前提校验：调用方协程确实被钉在单线程上（否则下面的「不相同」会假绿）
            // ⚠️ 用 startsWith 而不是 equals：kotlinx.coroutines 会在跑协程时给线程名追加
            // 「 @coroutine#N」后缀（实测 `vflow-chat-caller-thread @coroutine#1`）。
            assertTrue(
                "测试前提被破坏：调用方协程没有跑在单线程 dispatcher 上（实际：$coroutineThreadName）",
                coroutineThreadName.orEmpty().startsWith(CALLER_THREAD_NAME),
            )
            assertEquals("请求必须真的走通，否则本用例没有意义", "ok", result.content)

            val httpThreadName = httpThreadName.get()
            assertNotNull("拦截器没有被触发：HTTP 根本没发出去，本用例会假绿", httpThreadName)
            assertFalse(
                "阻塞式 httpClient.execute() 不得在调用方（Main）线程上执行 —— " +
                    "generateReply 必须自带 withContext(Dispatchers.IO)（HTTP 线程：$httpThreadName）",
                httpThreadName.orEmpty().startsWith(CALLER_THREAD_NAME),
            )
        } finally {
            callerDispatcher.close()
        }
    }

    private companion object {
        /**
         * 刻意取一个不可能与 OkHttp / coroutines 内部线程重名的名字 ——
         * 若断言失败，日志里一眼能看出「HTTP 跑在了调用方线程上」。
         */
        private const val CALLER_THREAD_NAME = "vflow-chat-caller-thread"
    }
}
