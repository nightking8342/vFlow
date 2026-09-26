package com.vflow.hookprobe;
import android.content.BroadcastReceiver; import android.content.Context;
import android.content.Intent; import android.os.Binder; import android.util.Log;
/** 无任何权限要求的接收器 —— 用于测「不可见的 App 能否发广播进来」 */
public class FreeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i("HookProbe/VFlow", "★★★ 收到【无权限】广播 | action="
                + (i==null?"null":i.getAction()) + " token="
                + (i==null?null:i.getStringExtra("token"))
                + " senderUid=" + Binder.getCallingUid());
    }
}
