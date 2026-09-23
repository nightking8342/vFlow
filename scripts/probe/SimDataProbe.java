package com.vflow.probe;

import java.lang.reflect.Method;

/**
 * 数据卡（DDS）切换原型探针。
 *
 * 目的：验证「以 shell 身份（UID 2000）经 isub binder 调用 setDefaultDataSubId」是否可行。
 * 选择反射按**方法名**调用而非 service call + 事务码 —— 事务码由 ISub.aidl 方法顺序决定，
 * 逐机型会漂移，且调错码会改到相邻设置项；反射不依赖顺序。
 *
 * 全部走反射，不 import 任何 Android 类，故可纯 javac 编译 + d8 转 dex，
 * 无需 android.jar（app_process 运行时用的是设备真实 framework）。
 *
 * 用法（只读，不改状态）：
 *   app_process /system/bin com.vflow.probe.SimDataProbe
 * 用法（切换）：
 *   app_process /system/bin com.vflow.probe.SimDataProbe <targetSubId>
 */
public class SimDataProbe {

    public static void main(String[] args) {
        System.out.println("=== vFlow DDS 探针 ===");
        System.out.println("uid check: " + System.getProperty("user.name"));
        try {
            Object iSub = obtainISub();
            if (iSub == null) {
                System.out.println("FAIL: isub 不可达");
                return;
            }
            System.out.println("ISub 实例 = " + iSub.getClass().getName());

            Method getDefault = findMethod(iSub.getClass(), "getDefaultDataSubId");
            if (getDefault == null) {
                System.out.println("FAIL: 找不到 getDefaultDataSubId");
                return;
            }
            getDefault.setAccessible(true);
            Object current = getDefault.invoke(iSub);
            System.out.println("当前 defaultDataSubId = " + current);

            // 顺带把可用卡列出来，便于核对 subId ↔ 卡槽
            dumpSubList(iSub);

            if (args.length < 1) {
                System.out.println("(只读模式，未改动任何状态。传 subId 参数则执行切换)");
                return;
            }

            int target = Integer.parseInt(args[0]);
            if (String.valueOf(target).equals(String.valueOf(current))) {
                System.out.println("目标与当前一致，无需切换。");
                return;
            }

            Method setDefault = findMethod(iSub.getClass(), "setDefaultDataSubId");
            if (setDefault == null) {
                System.out.println("FAIL: 找不到 setDefaultDataSubId");
                return;
            }
            setDefault.setAccessible(true);

            System.out.println(">>> 调用 setDefaultDataSubId(" + target + ") ...");
            long t0 = System.currentTimeMillis();
            try {
                setDefault.invoke(iSub, target);
                System.out.println(">>> 调用返回，耗时 " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable t) {
                System.out.println(">>> 调用抛异常: " + unwrap(t));
            }

            // 回读（服务端内存态）
            Object after = getDefault.invoke(iSub);
            System.out.println("切换后 defaultDataSubId = " + after
                    + (String.valueOf(target).equals(String.valueOf(after)) ? "  ✅ 生效" : "  ❌ 未生效"));
        } catch (Throwable t) {
            System.out.println("EXCEPTION: " + unwrap(t));
            t.printStackTrace(System.out);
        }
    }

    /** ServiceManager.getService("isub") → ISub$Stub.asInterface(binder) */
    private static Object obtainISub() throws Exception {
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Method getService = smClass.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, "isub");
        System.out.println("isub binder = " + binder);
        if (binder == null) return null;

        Class<?> ibinderClass = Class.forName("android.os.IBinder");
        Class<?> stubClass = Class.forName("com.android.internal.telephony.ISub$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", ibinderClass);
        asInterface.setAccessible(true);
        return asInterface.invoke(null, binder);
    }

    /** 列可用卡，核对 subId ↔ 卡槽。失败不影响主流程。 */
    private static void dumpSubList(Object iSub) {
        try {
            Method getList = findMethod(iSub.getClass(), "getActiveSubInfoList");
            if (getList == null) {
                System.out.println("(无法列出卡列表：getActiveSubInfoList 不存在)");
                return;
            }
            getList.setAccessible(true);
            Object list = getList.invoke(iSub);
            System.out.println("可用卡列表 = " + list);
        } catch (Throwable t) {
            System.out.println("(列卡失败，忽略: " + unwrap(t) + ")");
        }
    }

    /** 按名字找方法；优先精确匹配无参，再退回同名任意重载。 */
    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
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

    /** 剥掉 InvocationTargetException，露出真实异常（否则看不到 SecurityException）。 */
    private static String unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getName() + ": " + c.getMessage();
    }
}
