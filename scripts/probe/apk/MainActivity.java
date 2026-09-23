package com.vflow.simprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.util.List;

/**
 * 第二轮探针：确定实现细节。
 *   Q1 用 RECEIVER_NOT_EXPORTED 注册（本仓库既定写法）能否收到该广播？
 *   Q2 普通 App 带 READ_PHONE_STATE 能否用 SubscriptionManager 做 subId -> 卡槽 映射？
 */
public class MainActivity extends Activity {
    private static final String TAG = "SimProbe2";
    private static final String ACTION =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED";

    private BroadcastReceiver mk(final String label) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                Bundle e = intent.getExtras();
                Log.i(TAG, ">>> [" + label + "] 收到广播 extras=" + (e == null ? "null" : e.toString()));
            }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "=== SimProbe2 uid=" + android.os.Process.myUid() + " ===");

        IntentFilter f1 = new IntentFilter(ACTION);
        IntentFilter f2 = new IntentFilter(ACTION);
        try {
            // RECEIVER_NOT_EXPORTED = 4
            this.registerReceiver(mk("NOT_EXPORTED"), f1, 4);
            Log.i(TAG, "注册 NOT_EXPORTED 成功");
        } catch (Throwable t) { Log.e(TAG, "注册 NOT_EXPORTED 失败", t); }
        try {
            // RECEIVER_EXPORTED = 2
            this.registerReceiver(mk("EXPORTED"), f2, 2);
            Log.i(TAG, "注册 EXPORTED 成功");
        } catch (Throwable t) { Log.e(TAG, "注册 EXPORTED 失败", t); }

        // ---- Q2: subId -> 卡槽映射（需要 READ_PHONE_STATE）----
        try {
            SubscriptionManager sm = this.getSystemService(SubscriptionManager.class);
            if (sm == null) { Log.w(TAG, "SubscriptionManager 不可用"); return; }
            Log.i(TAG, "READ_PHONE_STATE granted=" +
                    (checkSelfPermission("android.permission.READ_PHONE_STATE") == 0));
            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null) {
                Log.w(TAG, "getActiveSubscriptionInfoList 返回 null（权限不足？）");
            } else {
                Log.i(TAG, "可用卡数量 = " + list.size());
                for (SubscriptionInfo si : list) {
                    Log.i(TAG, "  卡槽(simSlotIndex)=" + si.getSimSlotIndex()
                            + "  subId=" + si.getSubscriptionId()
                            + "  运营商=" + si.getCarrierName()
                            + "  显示名=" + si.getDisplayName());
                }
                int dds = SubscriptionManager.getDefaultDataSubscriptionId();
                Log.i(TAG, "默认数据卡 subId(SubscriptionManager.getDefaultDataSubscriptionId)=" + dds);
                SubscriptionInfo ddsInfo = sm.getActiveSubscriptionInfo(dds);
                Log.i(TAG, "默认数据卡 -> 卡槽=" + (ddsInfo == null ? "n/a" : ddsInfo.getSimSlotIndex()));
            }
        } catch (Throwable t) { Log.e(TAG, "映射测试异常", t); }
    }
}
