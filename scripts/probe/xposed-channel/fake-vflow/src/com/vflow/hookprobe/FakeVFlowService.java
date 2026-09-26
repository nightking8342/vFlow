package com.vflow.hookprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * 探针用「假 vFlow 服务」。
 *
 * 存在的唯一理由：回答「第三方应用（= hook 层在目标 App 进程里的 uid）能否 bind 上来」。
 * 真实 vFlow 的所有 Service 的 onBind 都返回 null（TriggerService.kt:467 等），
 * 所以拿真实 vFlow 验不了这一条 —— 必须有一个真的返回 binder 的 Service。
 *
 * 注意 onBind 会打印调用方 uid，这是判定的关键证据。
 */
public class FakeVFlowService extends Service {

    public static final String TAG = "HookProbe/VFlow";

    /**
     * 一个最小的可用 binder —— 探针不需要真接口，能证明「连通了」即可。
     *
     * ⚠️ 注意 getCallingUid() 的语义：它只在【Binder 事务内】有效。
     * onBind() 是由 AMS 调用的，不是 binder 事务 → 在 onBind 里调 getCallingUid()
     * 拿到的是【本进程 uid】，会误判。必须在一个真正被客户端调用的方法里取。
     * （这个坑已实际踩过：onBind 里读到 callerUid=自己，看起来像"没人连上来"）
     */
    public class ProbeBinder extends Binder {
        public String ping() {
            // ★ 这里才是 binder 事务，getCallingUid 才代表真实调用方
            int from = Binder.getCallingUid();
            Log.i(TAG, "★ ping() 被真实调用 | callerUid=" + from
                    + " (本进程 uid=" + android.os.Process.myUid() + ")");
            return "pong-from-fake-vflow";
        }
    }

    private final ProbeBinder binder = new ProbeBinder();

    @Override
    public IBinder onBind(Intent intent) {
        // ⚠️ 不要在这里读 getCallingUid()（见 ProbeBinder 注释）
        Log.i(TAG, "★ onBind 被调用（有客户端连上来）| action="
                + (intent == null ? "null" : intent.getAction()));
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "onUnbind 被调用");
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand 被调用（说明 startService 成功了）| callerUid="
                + Binder.getCallingUid());
        return START_NOT_STICKY;
    }
}
