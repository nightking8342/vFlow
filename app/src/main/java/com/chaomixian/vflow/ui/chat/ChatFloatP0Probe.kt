package com.chaomixian.vflow.ui.chat

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P0 技术验证探针 —— 临时文件，验证完成后连同 ChatFloatP0Activity / ChatFloatP0Service 一起删除。
 *
 * 目的：把七项待验证假设的实测结果统一收集，输出到 logcat（tag=P0_PROBE）供 adb 抓取。
 * 不参与任何生产逻辑。
 */
object ChatFloatP0Probe {

    const val LOG_TAG = "P0_PROBE"

    enum class Status { PASS, FAIL, INFO, PENDING }

    data class Result(
        val id: String,
        val title: String,
        val status: Status,
        val detail: String,
        val atMillis: Long = System.currentTimeMillis(),
    ) {
        val time: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(atMillis))
    }

    private val _results = MutableStateFlow<List<Result>>(emptyList())
    val results: StateFlow<List<Result>> = _results.asStateFlow()

    private val seenIds = linkedSetOf<String>()

    /** 记录一条结论。同一 id 只保留最新一条，避免重复。 */
    @Synchronized
    fun record(id: String, title: String, status: Status, detail: String) {
        val entry = Result(id, title, status, detail)
        val marker = when (status) {
            Status.PASS -> "PASS"
            Status.FAIL -> "FAIL"
            Status.INFO -> "INFO"
            Status.PENDING -> "PEND"
        }
        Log.i(LOG_TAG, "[$marker] $id | $title | $detail")
        seenIds += id
        _results.value = _results.value.filterNot { it.id == id } + entry
    }

    fun pass(id: String, title: String, detail: String) = record(id, title, Status.PASS, detail)
    fun fail(id: String, title: String, detail: String) = record(id, title, Status.FAIL, detail)
    fun info(id: String, title: String, detail: String) = record(id, title, Status.INFO, detail)

    @Synchronized
    fun reset() {
        seenIds.clear()
        _results.value = emptyList()
    }

    /** 便于 logcat 里一眼看出整体结论。 */
    fun summarize() {
        val all = _results.value
        val pass = all.count { it.status == Status.PASS }
        val fail = all.count { it.status == Status.FAIL }
        Log.i(LOG_TAG, "====== P0 SUMMARY: pass=$pass fail=$fail total=${all.size} ======")
        all.forEach { r ->
            Log.i(LOG_TAG, "  ${r.status} ${r.id} :: ${r.detail}")
        }
    }
}
