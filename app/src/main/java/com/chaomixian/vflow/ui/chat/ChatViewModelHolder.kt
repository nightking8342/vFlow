package com.chaomixian.vflow.ui.chat

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

/**
 * Chat 悬浮窗改造（§5.1）：把 `ChatViewModel` 提升到 Application 作用域。
 *
 * **为什么需要**：`ChatViewModel` 现在由 `MainComposeShell` 的 `viewModel()` 获取，
 * 绑定最近的 `ViewModelStoreOwner`（`MainActivity`）。悬浮窗从 Service 弹出时拿不到同一个
 * 实例，若自建则会形成**两套会话**（窗里说的话回 App 看不到），违反设计目标 G2。
 *
 * **怎么做到**：持有一把进程级的 `ViewModelStore`，App 与 Service 都经此取 VM，
 * 同一 key（规范化类名）必然返回同一实例。
 *
 * **代价（有意为之）**：VM 与进程同寿，`onCleared()` 不再触发，
 * `ChatViewModel` 里注册的 `prefsListener` 不会注销。详见设计文档 §5.1。
 */
object ChatViewModelHolder {

    @Volatile
    private var store: ViewModelStore? = null

    @MainThread
    fun get(application: Application): ChatViewModel {
        val targetStore = store ?: synchronized(this) {
            store ?: ViewModelStore().also { store = it }
        }
        return ViewModelProvider(
            targetStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[ChatViewModel::class.java]
    }

    /** 仅供测试/验证使用；正常运行时不应调用。 */
    @MainThread
    fun clearForTest() {
        synchronized(this) {
            store?.clear()
            store = null
        }
    }
}
