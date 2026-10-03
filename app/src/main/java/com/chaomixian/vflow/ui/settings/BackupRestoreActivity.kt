// 文件: main/java/com/chaomixian/vflow/ui/settings/BackupRestoreActivity.kt
// 描述: 备份/恢复二级页的 Activity 骨架（fork 新增）。范式照 GlobalVariableConfigActivity。
package com.chaomixian.vflow.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import com.chaomixian.vflow.ui.common.BaseActivity
import com.chaomixian.vflow.ui.common.VFlowTheme

class BackupRestoreActivity : BaseActivity() {

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, BackupRestoreActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VFlowTheme {
                BackupRestoreScreen(onBack = { finish() })
            }
        }
    }
}
