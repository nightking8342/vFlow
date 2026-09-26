package com.vflow.hookprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.util.Log;

/**
 * 探针用「带权限保护的下行接收器」——验证 Q2 / Q3。
 *
 * 形态与未来 vFlow 的 hook 下行通道一致：
 *   exported + 注册时声明 broadcastPermission
 *
 * 判定要点：日志里的 callerUid 能区分「谁发的」。
 *   同签的 fake-hook 发 → 收到（Q2 成立）
 *   异签的 fake-hook 发 → 收不到（Q3 成立，signature 权限有效）
 *
 * ⚠️ 本探针类的注册走 AndroidManifest 静态声明（receiver + android:permission），
 * 语义与动态 registerReceiver(..., broadcastPermission, ...) 等价：
 *   两者都是「发送方必须持有该权限」。
 * 静态声明的好处：进程没起来也能收，且免去「接收器超时」干扰。
 */
public class GuardedReceiver extends BroadcastReceiver {

    public static final String TAG = "HookProbe/VFlow";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        String token = intent == null ? null : intent.getStringExtra("token");
        Log.i(TAG, "★ 收到受保护广播 | action=" + action
                + " token=" + token
                + " senderUid=" + Binder.getCallingUid()
                + " senderPid=" + Binder.getCallingPid());
    }
}
