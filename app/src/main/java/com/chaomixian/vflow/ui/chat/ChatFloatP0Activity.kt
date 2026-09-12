package com.chaomixian.vflow.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * P0 技术验证入口 —— 临时文件，验证完成后删除。
 *
 * 用法：启动本 Activity → 授权悬浮窗 → 点各项按钮执行验证 → 看本页结果表 / logcat(tag=P0_PROBE)。
 */
class ChatFloatP0Activity : Activity() {

    companion object {
        /** adb 驱动验证的 action 键。 */
        const val EXTRA_P0_ACTION = "p0_action"
    }

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 支持 adb 驱动：am start -n .../.ChatFloatP0Activity --es p0_action start_probe|focus|screencap|summary|stop
        val adbAction = intent?.getStringExtra(EXTRA_P0_ACTION)
        if (adbAction != null) {
            handleAdbAction(adbAction)
            finish()
            return
        }

        ChatFloatP0Probe.reset()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Chat 悬浮窗 · P0 技术验证"
            textSize = 21f
        })

        root.addView(TextView(this).apply {
            text = "设备：${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}\n" +
                "七项待验证假设，逐项打点。结果同时输出到 logcat（tag=P0_PROBE）。"
            textSize = 12f
            setPadding(0, 12, 0, 20)
        })

        statusView = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, 16, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        fun addButton(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        addButton("1. 检查悬浮窗权限") { ensureOverlayPermission() }
        addButton("2. 启动验证 Service（⑦项核心：②③⑤⑥ + ①）") { startProbeService() }
        addButton("3. 切换窗口焦点（③ IME 关键对照）") {
            startService(probeIntent().apply { action = ChatFloatP0Service.ACTION_TOGGLE_FOCUS })
        }
        addButton("4. 执行 screencap（⑦ 截图是否含悬浮窗）") {
            ChatFloatP0Service.serviceRef?.get()?.probeScreencap()
                ?: toast("请先启动验证 Service")
        }
        addButton("5. 对照：仅改 LayoutParams（不 remove/add）") {
            ChatFloatP0Service.serviceRef?.get()?.probeUpdateLayoutParams()
                ?: toast("请先启动验证 Service")
        }
        addButton("6. 输出汇总（logcat P0 SUMMARY）") {
            ChatFloatP0Probe.summarize()
            renderStatus()
            toast("已输出到 logcat")
        }
        addButton("7. 停止验证 Service") {
            startService(probeIntent().apply { action = ChatFloatP0Service.ACTION_HIDE })
        }

        root.addView(ScrollView(this).apply { addView(statusView) })

        setContentView(root)
        ensureOverlayPermission()
        observeProbeResults()
    }

    private fun probeIntent() = Intent(this, ChatFloatP0Service::class.java)

    /** adb 驱动入口：`am start ... --es p0_action <cmd>`，便于自动化验证。 */
    private fun handleAdbAction(action: String) {
        when (action) {
            "start_probe" -> {
                val vm = ChatViewModelHolder.get(application)
                val identity = System.identityHashCode(vm).toString()
                ChatFloatP0Probe.record(
                    "P1",
                    "Activity 侧 VM",
                    ChatFloatP0Probe.Status.INFO,
                    "vm@$identity（已传给 Service 比对）"
                )
                val svcIntent = probeIntent().apply {
                    this.action = ChatFloatP0Service.ACTION_SHOW
                    putExtra(ChatFloatP0Service.EXTRA_ACTIVITY_VM_IDENTITY, identity)
                }
                try {
                    startForegroundService(svcIntent)
                } catch (t: Throwable) {
                    ChatFloatP0Probe.fail("P6", "前台服务启动", "抛异常：${t.message}")
                }
            }

            "focus" -> startService(
                probeIntent().apply { this.action = ChatFloatP0Service.ACTION_TOGGLE_FOCUS }
            )

            "expand" -> startService(
                probeIntent().apply { this.action = ChatFloatP0Service.ACTION_EXPAND }
            )

            "ime" -> startService(
                probeIntent().apply { this.action = ChatFloatP0Service.ACTION_REQUEST_IME }
            )

            "readd" -> startService(
                probeIntent().apply { this.action = ChatFloatP0Service.ACTION_REMOVE_READD }
            )

            "screencap" -> ChatFloatP0Service.serviceRef?.get()?.probeScreencap()
                ?: ChatFloatP0Probe.fail("P7", "screencap", "Service 未运行")

            "summary" -> ChatFloatP0Probe.summarize()

            "stop" -> startService(
                probeIntent().apply { this.action = ChatFloatP0Service.ACTION_HIDE }
            )
        }
    }

    private fun startProbeService() {
        // ① 先在本 Activity 侧取一次 VM，把身份传给 Service 比对
        val vm = ChatViewModelHolder.get(application)
        val identity = System.identityHashCode(vm).toString()
        ChatFloatP0Probe.info(
            "P1",
            "Activity 侧 VM",
            "vm@$identity（将传给 Service 比对）"
        )
        val intent = probeIntent().apply {
            action = ChatFloatP0Service.ACTION_SHOW
            putExtra(ChatFloatP0Service.EXTRA_ACTIVITY_VM_IDENTITY, identity)
        }
        try {
            // ⑥ 前台服务：用 startForegroundService 满足 API 26+ 契约
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail("P6", "前台服务启动", "startForegroundService 抛异常：${t.message}")
        }
        renderStatus()
    }

    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            ChatFloatP0Probe.fail("P0", "悬浮窗权限", "未授权，即将跳转设置页")
            toast("请先授予「显示在其他应用上层」权限")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            ChatFloatP0Probe.pass("P0", "悬浮窗权限", "已授权")
        }
        renderStatus()
    }

    private fun observeProbeResults() {
        // Activity 不是 LifecycleOwner，用轻量轮询刷新（验证 demo，无需精致）
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                renderStatus()
                handler.postDelayed(this, 700L)
            }
        }
        handler.post(ticker)
    }

    private fun renderStatus() {
        val all = ChatFloatP0Probe.results.value
        val pass = all.count { it.status == ChatFloatP0Probe.Status.PASS }
        val fail = all.count { it.status == ChatFloatP0Probe.Status.FAIL }
        val body = buildString {
            append("==== 结果 (PASS=$pass FAIL=$fail) ====\n")
            if (all.isEmpty()) append("（暂无）\n")
            all.forEach { r ->
                val mark = when (r.status) {
                    ChatFloatP0Probe.Status.PASS -> "✅"
                    ChatFloatP0Probe.Status.FAIL -> "❌"
                    ChatFloatP0Probe.Status.INFO -> "ℹ️"
                    ChatFloatP0Probe.Status.PENDING -> "…"
                }
                append("$mark ${r.id} ${r.title}\n    ${r.detail}\n")
            }
        }
        statusView.text = body
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
