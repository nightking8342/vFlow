package com.chaomixian.vflow.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.chaomixian.vflow.ui.workflow_list.WorkflowListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 工作流列表页的 UI 状态。
 *
 * ⚠️ **fork 变更**：原先还有 `openFolder` / `folderWorkflows` 两个字段，以及
 * `openFolder()` / `updateFolderWorkflows()` / `closeFolder()` 三个方法 ——
 * 它们服务的是「点文件夹卡片 → 弹底部弹窗」那条链路。
 * 文件夹改成**顶部 Tab 栏**之后，内容直接从 `items` 里按 `folderId` 筛
 * （`filterByFolderTab`），不需要在 ViewModel 里再存一份文件夹内容，
 * 那条链路整体删除了。
 */
data class WorkflowListUiState(
    val items: List<WorkflowListItem> = emptyList(),
    val isLoading: Boolean = true,
    val executionStateVersion: Int = 0,
)

class WorkflowListViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WorkflowListUiState())
    val uiState: StateFlow<WorkflowListUiState> = _uiState.asStateFlow()

    fun setItems(items: List<WorkflowListItem>) {
        _uiState.update { it.copy(items = items, isLoading = false) }
    }

    fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }

    fun bumpExecutionStateVersion() {
        _uiState.update { state ->
            state.copy(executionStateVersion = state.executionStateVersion + 1)
        }
    }
}
