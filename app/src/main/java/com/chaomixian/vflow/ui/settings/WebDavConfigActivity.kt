package com.chaomixian.vflow.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import com.chaomixian.vflow.ui.common.BaseActivity
import com.chaomixian.vflow.ui.common.VFlowTheme

/**
 * WebDAV 配置管理页。
 *
 * ⚠️ 薄壳，照 `GlobalVariableConfigActivity` 的范式 —— `BaseActivity` 已处理
 * 语言切换（`attachBaseContext`）+ 动态主题 + 边衬区，故这里只需 `setContent`。
 */
class WebDavConfigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VFlowTheme {
                WebDavConfigScreen(onBack = { finish() })
            }
        }
    }
}
