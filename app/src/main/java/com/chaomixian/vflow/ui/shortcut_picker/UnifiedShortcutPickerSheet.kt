package com.chaomixian.vflow.ui.shortcut_picker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.services.ShellManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UnifiedShortcutPickerSheet : BottomSheetDialogFragment() {

    private lateinit var adapter: ShortcutPickerAdapter
    private var onResultCallback: ((Intent) -> Unit)? = null

    companion object {
        const val EXTRA_SELECTED_PACKAGE_NAME = "selected_package_name"
        const val EXTRA_SELECTED_SHORTCUT_LABEL = "selected_shortcut_label"
        const val EXTRA_SELECTED_LAUNCH_COMMAND = "selected_launch_command"

        fun newInstance(): UnifiedShortcutPickerSheet = UnifiedShortcutPickerSheet()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.sheet_shortcut_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleView = view.findViewById<TextView>(R.id.title_text)
        val stateView = view.findViewById<TextView>(R.id.state_text)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        val searchView = view.findViewById<SearchView>(R.id.search_view)
        val backButton = view.findViewById<View>(R.id.back_button)

        titleView.text = getString(R.string.text_select_shortcut)
        backButton.setOnClickListener { dismiss() }

        adapter = ShortcutPickerAdapter { item ->
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_PACKAGE_NAME, item.packageName)
                putExtra(EXTRA_SELECTED_SHORTCUT_LABEL, item.shortcutLabel)
                putExtra(EXTRA_SELECTED_LAUNCH_COMMAND, item.launchCommand)
            }
            onResultCallback?.invoke(resultIntent)
            dismiss()
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText)
                return true
            }
        })

        // ⚠️ 前置条件放宽：**③ 通道可用时不再需要 Shizuku/Root** ——
        // 无损路径走 hook 层（system_server 内反射读 ShortcutService），
        // 与 shell 权限无关。原来的「没 Shizuku 就直接出提示、连加载都不试」
        // 会在装了 Xposed 的设备上**白白挡住**可用路径。
        val shellReady = ShellManager.isShizukuActive(requireContext()) || ShellManager.isRootAvailable()

        stateView.isVisible = true
        stateView.text = getString(R.string.text_shortcut_picker_loading)

        CoroutineScope(Dispatchers.IO).launch {
            val loaded = ShortcutPickerSupport.loadShortcutsWithFallback(requireContext())
            withContext(Dispatchers.Main) {
                adapter.submitList(loaded.items)
                // ⚠️ 三种「空」要分开说 —— 用户的自救方向完全不同：
                //   · 走了降级且没有 shell ⇒ 提示装 Shizuku/Root（**唯一能自救的**）
                //   · 走了降级但有 shell ⇒ 数据就是空的
                //   · 走了无损路径 ⇒ 数据就是空的（不该再提 Shizuku）
                stateView.isVisible = loaded.items.isEmpty() || loaded.notice != null
                stateView.text = when {
                    loaded.notice != null -> loaded.notice
                    loaded.items.isEmpty() && !shellReady && loaded.degraded ->
                        getString(R.string.text_shortcut_picker_requires_shell)
                    loaded.items.isEmpty() ->
                        getString(R.string.text_shortcut_picker_empty)
                    else -> null
                }
                stateView.isVisible = stateView.text != null
            }
        }
    }

    fun setOnResultCallback(callback: (Intent) -> Unit) {
        onResultCallback = callback
    }
}
