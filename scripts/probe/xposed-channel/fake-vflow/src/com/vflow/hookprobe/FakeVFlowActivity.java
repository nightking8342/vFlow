package com.vflow.hookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 假 vFlow 的入口 Activity —— 唯一用途是让本进程进入「前台」状态。
 *
 * 起因：静态接收器在「cached 进程」状态下可能收不到广播
 * （小米 Greezer / Android 广播队列的省电策略）。
 * 把进程提到前台，可以排除「进程状态」这个干扰变量，
 * 从而让「收不到广播」的结论确实归因到【权限】或【投递】。
 */
public class FakeVFlowActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("FakeVFlow 前台占位（仅用于让进程不被 cached）\n"
                + "uid=" + android.os.Process.myUid());
        tv.setTextSize(14f);
        setContentView(tv);

        // ★ 自检：自己发广播给自己的两个接收器。
        // 同应用广播不受 receiver 的 android:permission 限制（权限只拦外部发送方），
        // 所以这一步验证的是「接收器本身是否工作」——
        // 若这一步也收不到 ⇒ 是接收器/清单配置的问题，与权限无关。
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) {
            tv.postDelayed(() -> {
                android.util.Log.i("HookProbe/VFlow", "=== 自检：自己发广播给自己 ===");
                sendBroadcast(new android.content.Intent("com.vflow.hookprobe.PUSH_OPEN")
                        .setPackage(getPackageName())
                        .putExtra("token", "selftest-open"));
                sendBroadcast(new android.content.Intent("com.vflow.hookprobe.PUSH_CONDITIONS")
                        .setPackage(getPackageName())
                        .putExtra("token", "selftest-guarded"));
                android.util.Log.i("HookProbe/VFlow", "自检广播已发出");
            }, 1000);
        }
    }
}
