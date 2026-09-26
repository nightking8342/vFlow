package com.vflow.hookprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.util.Log;

/**
 * 阳性对照接收器 —— 只要求 normal 级权限（任何应用都持有）。
 *
 * 存在的唯一理由：把「没收到广播」的原因分离成两种可能——
 *   ① 【投递失败】：包不可见 / 进程没起来 / 广播压根没送出
 *   ② 【权限拦截】：送到了，但发送方不持有要求的权限
 *
 * 判据：
 *   OPEN 收到 + GUARDED 没收到 ⇒ 投递没问题，是权限拦的（Q3 成立）
 *   OPEN 也没收到                ⇒ 是投递问题，Q3 的结论无效，需先修投递
 */
public class OpenReceiver extends BroadcastReceiver {

    public static final String TAG = "HookProbe/VFlow";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "★ 收到【对照】广播（normal 权限）| action="
                + (intent == null ? "null" : intent.getAction())
                + " token=" + (intent == null ? null : intent.getStringExtra("token"))
                + " senderUid=" + Binder.getCallingUid());
    }
}
