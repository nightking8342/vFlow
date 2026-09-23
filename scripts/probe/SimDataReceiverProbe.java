package com.vflow.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 数据卡切换广播接收探针 —— 验证触发器侧的三个根本问题：
 *   1. 以 App 同类方式动态注册的 receiver，能否收到系统发出的该广播？
 *   2. 广播的 extras 里到底有哪些 key、值是什么？（决定 App 侧读哪个常量）
 *   3. 目标值与当前值相同时（幂等切换），服务端是否不广播？（决定触发器要不要自己去重）
 *
 * 自包含设计：自己注册 → 自己切换 → 自己观察 → 自己还原，避免跨进程时序协调。
 *
 * 用法：app_process /system/bin com.vflow.probe.SimDataReceiverProbe
 */
public class SimDataReceiverProbe {

    /** 与 AOSP SubscriptionManager 的 @hide 常量逐字一致；小米真机实测未被改动。 */
    private static final String ACTION = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED";

    private static final StringBuilder LOG = new StringBuilder();
    private static int receivedCount = 0;

    /** 用于跳出 Looper.loop() 而不销毁 Looper（quit() 会让后续 pump 失效）。 */
    private static final class StopPump extends RuntimeException {
        StopPump() { super("stop-pump", null, false, false); }
    }

    public static void main(String[] args) {
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
                System.out.println("已 prepareMainLooper");
            }
            Context ctx = obtainSystemContext();
            System.out.println("Context = " + ctx);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    receivedCount++;
                    System.out.println(">>> [收到广播 #" + receivedCount + "] " + intent.getAction());
                    Bundle extras = intent.getExtras();
                    if (extras == null) {
                        System.out.println("    (extras 为 null)");
                    } else {
                        for (String k : extras.keySet()) {
                            System.out.println("     extra['" + k + "'] = " + extras.get(k));
                        }
                    }
                }
            };

            registerReceiverCompat(ctx, receiver);

            // ---- 对照实验 C：自定义广播 ----
            // 用于区分「shell 进程的动态 receiver 根本收不到任何广播」与
            // 「只有数据卡这条广播收不到」。若是前者，则本探针进程状态不具代表性，
            // 不能据此断定 vFlow（前台 Service）也收不到。
            final int[] pingCount = {0};
            BroadcastReceiver pingReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    pingCount[0]++;
                    System.out.println(">>> [收到自定义广播 #" + pingCount[0] + "] " + intent.getAction());
                }
            };
            ctx.registerReceiver(pingReceiver, new IntentFilter("com.vflow.probe.PING"));
            System.out.println("\n[实验C] 发一条自定义广播，验证本进程动态 receiver 是否可用");
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"/system/bin/am", "broadcast", "-a", "com.vflow.probe.PING"});
                p.waitFor();
            } catch (Throwable t) {
                System.out.println("  发自定义广播失败: " + unwrap(t));
            }
            pump(3000);
            System.out.println("实验C 收到自定义广播数 = " + pingCount[0]
                    + (pingCount[0] > 0 ? "  ✅ 进程 receiver 可用" : "  ❌ 进程 receiver 不可用"));

            Object iSub = obtainISub();
            Method getDefault = findMethod(iSub.getClass(), "getDefaultDataSubId");
            Method setDefault = findMethod(iSub.getClass(), "setDefaultDataSubId");
            if (getDefault == null || setDefault == null) {
                System.out.println("FAIL: isub 方法缺失");
                return;
            }
            getDefault.setAccessible(true);
            setDefault.setAccessible(true);

            int start = (Integer) getDefault.invoke(iSub);
            int other = (start == 1) ? 2 : 1;
            System.out.println("起始 subId = " + start + "，将切到 " + other);

            // ---- 实验 A：真实切换，应收到广播 ----
            System.out.println("\n[实验A] 真实切换 " + start + " -> " + other);
            switchTo(iSub, setDefault, other);
            pump(3000);
            countAfterA = receivedCount;
            System.out.println("实验A 期间收到广播数 = " + countAfterA);

            // ---- 实验 B：幂等切换（同一值），预期不广播 ----
            System.out.println("\n[实验B] 幂等切换，目标值仍为 " + other + "（预期无广播）");
            switchTo(iSub, setDefault, other);
            pump(3000);
            System.out.println("实验B 期间收到广播数 = " + (receivedCount - countAfterA));

            // ---- 还原 ----
            System.out.println("\n[还原] 切回 " + start);
            switchTo(iSub, setDefault, start);
            pump(2000);

            System.out.println("\n=== 总计收到数据卡广播 " + receivedCount + " 次 ===");
            ctx.unregisterReceiver(pingReceiver);
            ctx.unregisterReceiver(receiver);
            System.out.println("已注销 receiver");
        } catch (Throwable t) {
            System.out.println("EXCEPTION: " + unwrap(t));
            t.printStackTrace(System.out);
        }
    }

    private static int countAfterA = 0;

    private static void switchTo(Object iSub, Method setDefault, int subId) {
        try {
            setDefault.invoke(iSub, subId);
            System.out.println("  setDefaultDataSubId(" + subId + ") 已调用");
        } catch (Throwable t) {
            System.out.println("  调用失败: " + unwrap(t));
        }
    }

    /** 让主 Looper 转一会儿，好让广播送达；不 quit，可反复调用。 */
    private static void pump(long ms) {
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override public void run() { throw new StopPump(); }
        }, ms);
        try {
            Looper.loop();
        } catch (StopPump expected) {
            // 正常跳出
        }
    }

    /**
     * Android 14+ 对动态注册非系统广播强制要求 flag，系统广播本应豁免。
     * 两种都试一遍，把真实行为打出来 —— 这正是 App 侧要照抄的写法依据。
     */
    private static void registerReceiverCompat(Context ctx, BroadcastReceiver r) throws Exception {
        IntentFilter filter = new IntentFilter(ACTION);
        try {
            ctx.registerReceiver(r, filter);
            System.out.println("registerReceiver 成功（未传 flag）→ 系统广播确实豁免 flag 要求");
        } catch (Throwable t) {
            System.out.println("未传 flag 失败: " + unwrap(t));
            int exported = Context.class.getField("RECEIVER_EXPORTED").getInt(null);
            ctx.registerReceiver(r, filter, exported);
            System.out.println("registerReceiver 成功（RECEIVER_EXPORTED=" + exported + "）");
        }
    }

    /** ActivityThread.systemMain() 会 prepareMainLooper，是 shell 进程里拿 Context 的常规做法。 */
    private static Context obtainSystemContext() throws Exception {
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Method systemMain = atCls.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object at = systemMain.invoke(null);
        Method getSystemContext = atCls.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(at);
    }

    private static Object obtainISub() throws Exception {
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Method getService = smClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, "isub");
        Class<?> ibinderClass = Class.forName("android.os.IBinder");
        Class<?> stubClass = Class.forName("com.android.internal.telephony.ISub$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", ibinderClass);
        asInterface.setAccessible(true);
        return asInterface.invoke(null, binder);
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
        }
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static String unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException && c.getCause() != null) c = c.getCause();
        return c.getClass().getName() + ": " + c.getMessage();
    }
}
