package com.vflow.hookprobe;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * P0 探针：注入 system_server，验证 Xposed 通道的地基假设。
 *
 * ⚠️ 这是**探针**，不是实现。它的唯一目的是回答「能不能做」，不做任何业务逻辑。
 * 所有 hook 回调**只打日志、立刻返回**（不阻塞、不改行为）——
 * 这样第一次注入若出问题，能干净地归因到「注入本身」而非「hook 里干了什么」。
 *
 * ## 要验的 5 条（对应设计文档 §8）
 *
 * | # | 问题 | 判据 |
 * |---|---|---|
 * | 18 | {@code onSystemServerStarting} 是否真的触发 | 入口日志出现 |
 * | 1  | {@code ActivityRecord.activityResumedLocked} 是否仍存在 | 能挂上 + 事件日志 |
 * | 14 | system_server 内能否 bindService 到 vFlow | 拿到 IBinder |
 * | 15 | system_server 能否持有 vFlow 的 signature 权限 | checkPermission 结果 |
 * | 17 | 热更新对 system_server 是否适用 | 触发热更新后的行为 |
 *
 * ## 使用
 *
 * 1. 编译安装（见 build.sh），在 LSPosed 里启用本模块
 * 2. **作用域勾选「系统框架」**（scope.list 里写的是特殊虚拟包名 `system`）
 * 3. 重启设备（system_server 需要重启才会注入）
 * 4. `adb logcat -s HookProbe:*` 看结果
 */
public class HookProbeEntry extends XposedModule {

    public static final String TAG = "HookProbe";

    /**
     * 统一日志出口。
     *
     * ⚠️ **为什么只打日志、不写文件**：
     * 曾加过「每次 say 都写文件到 `/sdcard/vFlow/`」的版本，**结果模块完全不被加载**
     * （实测：装回不带写文件的版本即正常）。**hook 路径上不做任何 I/O**
     * 是硬要求（设计文档 §3.4.4：回调内只做「取值 + 入队」）。
     *
     * ⚠️ **用 ERROR 级**：LSPosed 的框架日志实测只记 Error 级内容（INFO/WARN 看不到）。
     *
     * ⚠️ **读日志**：`adb logcat | grep "E HookProbe"`。
     * 启动期日志会被开机洪流挤掉 ⇒ **要抓必须重启后立刻抓**，
     * 或用 LSPosed 管理器导出的 verbose 日志（持久化、不丢）。
     */
    private void say(String msg) {
        try {
            log(android.util.Log.ERROR, TAG, msg);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.e(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    private void warn(String msg) {
        say(msg);
    }

    private void warn(String msg, Throwable t) {
        try {
            log(android.util.Log.ERROR, TAG, msg, t);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 目标 hook 点。
     *
     * ⚠️ **类名按 Android 版本分级**（ShortX 实证，见其 `ActivityRecordHook.java`）：
     *   - API 12+ → {@code com.android.server.wm.ActivityRecord}
     *   - API 9~11 → {@code com.android.server.am.ActivityRecord}
     *
     * ⚠️ **曾写错成 {@code android.app.ActivityRecord}** ⇒ `ClassNotFoundException`
     *    （那是个不存在的包名，我第一次手写时想当然了）。已实际踩过。
     */
    private static final String[] CLS_ACTIVITY_RECORD_CANDIDATES = {
            "com.android.server.wm.ActivityRecord",   // API 12+
            "com.android.server.am.ActivityRecord",   // API 9~11
    };
    private static final String M_ACTIVITY_RESUMED_LOCKED = "activityResumedLocked";

    /** 我们要 bind 的目标（探针阶段用 fake-vflow，避免改 vFlow 代码）。 */
    private static final String TARGET_PKG = "com.vflow.hookprobe.vflow";
    private static final String TARGET_SVC = "com.vflow.hookprobe.FakeVFlowService";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // ⚠️ 官方要求：不要在 onModuleLoaded 之前做初始化
        say( "════ onModuleLoaded ════");
        say( "  进程名      = " + param.getProcessName());
        say( "  isSystemServer = " + param.isSystemServer());
        say( "  本类 ClassLoader = " + getClass().getClassLoader());

        // 框架能力：API 版本（getApiVersion 在 XposedInterface 上）
        try {
            say( "  框架 API 版本 = " + getApiVersion());
            say( "  框架名/版本   = " + getFrameworkName() + " / " + getFrameworkVersion());
        } catch (Throwable t) {
            warn( "  查框架信息失败", t);
        }

        // 基线日志：确认注入成功
        say( "  注入成功 ✅ 【v3-replaceHook】");
    }

    /**
     * ⭐ #18：system_server 启动回调 —— 这是 5b 形态的正牌入口。
     *
     * ⚠️ 如果这个回调**没被调用**，说明要么 scope 没配对（要勾「系统框架」），
     * 要么框架在 Android 17 上的行为与文档不符（→ §8-18）。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        say( "════ onSystemServerStarting ════");
        say( "  ✅ 5b 入口回调【触发】了");
        ClassLoader cl = param.getClassLoader();
        // ⚠️ 不缓存它 —— 热更新后静态字段在新代际里是 null（实测）。
        //    hook 回调需要的类改从 `chain.getExecutable()` 推（见 onActivityResumedLockedHook）。

        // ⭐ #1：hook ActivityRecord.activityResumedLocked（纯 hook，不需要系统服务）
        hookActivityResumedLocked(cl);

        // ⚠️ #14/#15 需要系统服务（PackageManager / ActivityManager），
        //    而 onSystemServerStarting 是「【即将】启动」——此刻它们【都还是 null】。
        //    实测：getPackageManager() 与 ContextImpl.bindServiceCommon 里的
        //    IActivityManager 均为 null ⇒ NullPointerException。
        //    改为延迟到服务就绪后再做（见 scheduleServiceProbe）。
        scheduleServiceProbe(cl);
    }

    // ---------------------------------------------------------------- #17 热更新

    /**
     * ⭐ #17：热更新 —— 旧代码即将退休。
     *
     * 官方（102 源码逐字）：{@code Hot reload allows modules to be updated without restarting the process.}
     *
     * ⚠️ 本回调**运行在【旧】代码里**。官方要求：
     * 「须先停掉自己的线程/回调、释放 JNI 引用」，否则可能崩。
     * **探针这里只打日志**（它本来就没有需要停的线程）；
     * 正式实现若起了采集线程，**必须在这里停掉**。
     *
     * @return {@code true} = 放行热更新；{@code false} = 拒绝。探针放行。
     */
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        say( "════ onHotReloading（旧代码 · 热更新即将发生）════");
        // ⚠️ 只放行，不在此处重挂 hook（要等新代码的 onHotReloaded）
        return true;
    }

    /**
     * ⭐ #17：热更新 —— **新代码已接管**。
     *
     * ⚠️ **官方明确：hook 不会自动重挂** ——
     * 「Hot reload does not automatically replay this callback or package lifecycle callbacks」
     * ⇒ **必须在这里自己重新挂**，否则热更新后 hook 全失效（且是静默的）。
     *
     * ⚠️ **正确做法：用 `HookHandle.replaceHook()` 原子替换，【不需要 ClassLoader】。**
     *
     *     官方源码逐字（102）：
     *     「Hooks can be **atomically replaced** by api or same id.」
     *     `HookHandle` 上有：`getExecutable()` / `unhook()` / `getId()` /
     *     **`replaceHook(Hooker)`** —— 后者正是为此设计的。
     *
     * ⚠️⚠️ **我在此处踩了两个坑（都靠实测才发现）**：
     *   ① `HotReloadedParam` **没有 `getClassLoader()`** —— 与它父接口
     *      `ModuleLoadedParam` 都没有；全 API 只有 `PackageReadyParam` 和
     *      `SystemServerStartingParam` 有。（我最初想当然写了，编译失败才发现）
     *   ② 我改用「静态字段缓存 ClassLoader」，结果 **`onHotReloaded` 里读到 null** ——
     *      因为热更新是**新 classloader 加载新代码**，
     *      **新代际里的静态字段是全新的、拿不到旧代际赋的值**。
     *      这正好印证官方警告：「The saved state must **not** contain objects
     *      created under the old module classloader」。
     *   **⇒ 事实上根本不需要 ClassLoader：`replaceHook()` 就够了。**
     *
     * ⚠️ 另一个必须验的点：**这是否在 system_server 里也会触发** ——
     * 官方文档没区分进程，但 system_server 重启成本极高，
     * **框架对它的策略可能不同**（§4.6 注①）。
     */
    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        say( "════ onHotReloaded（新代码 · 已接管）════");
        java.util.List<io.github.libxposed.api.XposedInterface.HookHandle> old = null;
        try {
            old = param.getOldHookHandles();
            say( "  旧 hook 句柄数 = " + describeHandles(old));
            say( "  savedState = " + param.getSavedInstanceState());
        } catch (Throwable t) {
            warn( "  读 HotReloadedParam 失败", t);
        }
        say( "  ⇒ 若此后 activityResumedLocked 仍能命中，说明【system_server 热更新可用】（§8-17）");

        // ⭐ 正解：**原子替换**旧 hook（不需要 ClassLoader）
        if (old != null && !old.isEmpty()) {
            for (io.github.libxposed.api.XposedInterface.HookHandle h : old) {
                try {
                    java.lang.reflect.Executable ex = h.getExecutable();
                    say( "  替换旧 hook: " + ex + " (id=" + h.getId() + ")");
                    // ⚠️ replaceHook 用的是**旧句柄**，但回调是**新代码**里的 Hooker
                    h.replaceHook(this::onActivityResumedLockedHook);
                    say( "  ✅ replaceHook 成功");
                } catch (Throwable t) {
                    warn( "  ❌ replaceHook 失败", t);
                }
            }
        } else {
            say( "  ⚠️ 没有旧 hook 句柄 ⇒ 退化为「找不到 ClassLoader、无法重挂」");
        }
    }

    /**
     * hook 回调的**公共逻辑**（原在 `hookActivityResumedLocked` 的 lambda 里）。
     *
     * ⚠️ 提到独立方法是为了**热更新后能复用同一份逻辑**
     * （`replaceHook` 需要的 `Hooker` 必须是新代际里的对象）。
     */
    private Object onActivityResumedLockedHook(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
        try {
            Object arg0 = chain.getArg(0);
            Object arg1 = chain.getArg(1);
            say( "  ★ activityResumedLocked | arg0="
                    + (arg0 == null ? "null" : arg0.getClass().getName())
                    + " arg1=" + arg1);

            // ⚠️⚠️ **不再用静态字段缓存类** —— 热更新时静态字段在新代际里是 null
            //     （我用静态字段缓存 ClassLoader，实测读到 null，就是这个原因）。
            //     `ActivityRecord` 的 Class **能从 chain 自身推出来**：
            //     被 hook 的方法就是 `ActivityRecord` 的静态方法。
            Class<?> recordCls = null;
            try {
                java.lang.reflect.Executable ex = chain.getExecutable();
                if (ex != null) recordCls = ex.getDeclaringClass();
            } catch (Throwable ignored) {
            }
            if (recordCls == null) {
                warn( "    ⚠️ 拿不到被 hook 方法的声明类，无法反查");
                return chain.proceed();   // ⚠️ 必须 proceed，否则原方法不执行
            }

            Object record = resolveActivityRecord(recordCls, arg0);
            if (record != null) {
                say( "    ✅ 反查到 ActivityRecord: " + record.getClass().getName()
                        + " | pkg=" + readField(record, "packageName")
                        + " component=" + readField(record, "mActivityComponent")
                        + " intent=" + readShort(record, "intent"));
            } else {
                warn( "    ⚠️ 未能由 token 反查到 ActivityRecord");
            }
        } catch (Throwable t) {
            warn( "  回调内取值失败（不影响调用方）", t);
        }
        // ⚠️ 必须调用 proceed() —— 否则被 hook 的方法不会执行（整机行为会被破坏！）
        return chain.proceed();
    }

    /**
     * 把句柄列表描述成一行文本（探针用，只读）。
     *
     * ⚠️ **实测**：`XposedInterfaceWrapper`（`XposedModule` 的基类）**没有**
     * `getHookHandles()` 方法（我用 `javap` 查过本地 aar 的 `classes.jar`）。
     * 旧 hook 句柄只能从 `HotReloadedParam.getOldHookHandles()` 拿。
     */
    private static String describeHandles(Object handles) {
        if (handles == null) return "null";
        if (handles instanceof java.util.List) {
            return ((java.util.List<?>) handles).size() + " 个";
        }
        return handles.getClass().getName();
    }

    /**
     * 把需要系统服务的探测推迟到「服务已就绪」之后。
     *
     * ⚠️ 实测：在 {@code onSystemServerStarting} 里直接做会 NPE ——
     * 那时 `ActivityManager` / `PackageManager` 尚未注册。
     *
     * 做法：轮询等待系统服务可用（最多 ~30 秒），就绪后再探测。
     * 探针用轮询是为了简单；正式实现应 hook 明确的就绪点。
     */
    private void scheduleServiceProbe(ClassLoader cl) {
        new Thread(() -> {
            // ⚠️ 上限从 60 次(30s) 提到 300 次(150s)：
            //    实测探针在**设备解锁之前**就跑了（比解锁早约 47 秒），
            //    而目标 Service 是 `directBootAware=false` —— 未解锁时用户数据组件取不到。
            //    原来的 30 秒不够等到解锁，所以必须放宽上限。
            for (int i = 0; i < 300; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    android.content.Context ctx = getSystemContext();
                    if (ctx == null) continue;
                    // 判据 ①：PackageManager 可用（系统服务就绪）
                    if (ctx.getPackageManager() == null) continue;
                    // 判据 ②：⭐ 设备【已解锁】
                    //
                    // ⚠️ 为什么必须加这条（实测教训）：
                    //    Device 未解锁时，`directBootAware=false` 的组件（我们的目标
                    //    Service 正是）**在 package 解析阶段就被排除**，
                    //    表现为 `getServiceInfo` 抛 NameNotFoundException、
                    //    `resolveService` 返 null、`bindService` 返 false ——
                    //    **看起来像「包可见性」问题，实际是「还没解锁」**。
                    //
                    // ⚠️ 这条判据在**正式实现里也是必需的**：
                    //    hook 层必须先确认「用户已解锁、vFlow 可用」才开始通信，
                    //    否则会得到一堆假的「连不上」。
                    //
                    // ⚠️ 它是一次跨进程调用（IPC），与同处的 checkPermission / bindService
                    //    同类；而「启动期不能新增 IPC」那条结论其实是**推断**
                    //    （三次「不加载」我都只做了整体回退、没做二分定位）。
                    //    本处风险与既有调用相同。
                    android.os.UserManager um = (android.os.UserManager)
                            ctx.getSystemService(android.content.Context.USER_SERVICE);
                    if (um == null) continue;
                    if (!um.isUserUnlocked()) {
                        if ((i % 20) == 0) {   // 每 10 秒打一次，避免刷屏
                            say( "  等待用户解锁…（已等 " + ((i + 1) * 500) + "ms）");
                        }
                        continue;
                    }
                    say( "  #14/#15 系统服务已就绪【且已解锁】（等待 " + ((i + 1) * 500) + "ms）");
                    probeCommunication(ctx);
                    return;
                } catch (Throwable ignored) {
                    // 没就绪，继续等
                }
            }
            warn( "  #14/#15 等「服务就绪 + 用户解锁」超时（150s），放弃");
        }, "HookProbe-wait").start();
    }

    // ---------------------------------------------------------------- #1

    /**
     * hook {@code ActivityRecord.activityResumedLocked}。
     *
     * 这是设计文档 §4.1 里 {@code activity_changed} 触发器的数据来源
     * （能拿到 Activity 的 Intent —— 那是前三条通道拿不到的东西）。
     *
     * ⚠️ 回调内**只取参数 + 打日志**，不改返回值、不阻塞（§3.4.4 的硬要求）。
     */
    private void hookActivityResumedLocked(ClassLoader cl) {
        say( "──── #1 尝试 hook ActivityRecord." + M_ACTIVITY_RESUMED_LOCKED + " ────");
        try {
            // 按版本候选逐个试（见 CLS_ACTIVITY_RECORD_CANDIDATES 注释）
            Class<?> cls = null;
            String usedName = null;
            for (String cand : CLS_ACTIVITY_RECORD_CANDIDATES) {
                try {
                    cls = cl.loadClass(cand);
                    usedName = cand;
                    break;
                } catch (ClassNotFoundException e) {
                    say( "  ✗ 无 " + cand);
                }
            }
            if (cls == null) {
                warn( "  ❌ 所有候选类名都不存在 ⇒ hook 点在 Android 17 上可能已改名（§8-1）");
                return;
            }
            say( "  ✅ 类存在: " + usedName);

            // 打印所有同名方法（确认签名，避免猜错）
            boolean found = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (M_ACTIVITY_RESUMED_LOCKED.equals(m.getName())) {
                    found = true;
                    say( "  找到方法: " + m + "  (参数 "
                            + java.util.Arrays.toString(m.getParameterTypes()) + ")");
                }
            }
            if (!found) {
                warn( "  ❌ 找不到方法 " + M_ACTIVITY_RESUMED_LOCKED
                        + " ⇒ 该 hook 点在 Android 17 上可能已改名（§8-1）");
                return;
            }

            // 逐个挂（可能有多个重载）
            for (Method m : cls.getDeclaredMethods()) {
                if (!M_ACTIVITY_RESUMED_LOCKED.equals(m.getName())) continue;
                try {
                    // ⚠️ 回调体**提到独立方法** `onActivityResumedLockedHook`，
                    //    因为热更新后 `replaceHook()` 需要复用同一个 Hooker。
                    hook(m).intercept(this::onActivityResumedLockedHook);
                    say( "  ✅ 已挂上: " + m);
                } catch (Throwable t) {
                    warn( "  ❌ 挂载失败: " + m, t);
                }
            }
        } catch (Throwable t) {
            warn( "  ❌ hook 过程异常", t);
        }
    }

    /**
     * 由 token（IBinder）反查 ActivityRecord 实例。
     *
     * ⚠️ 探针阶段**穷举**几种可能路径并把结果都打出来，正式实现要确定用哪条：
     *   ① ActivityRecord.forToken(IBinder)  —— AOSP 常见的静态查询入口
     *   ② 遍历 ActivityTaskManagerService 的活动列表（成本高，仅作兜底）
     *   ③ 字段名可能是 mToken / token
     */
    private Object resolveActivityRecord(Class<?> activityRecordCls, Object token) {
        if (token == null) return null;
        // 路径 ①：静态 forToken
        try {
            Method forToken = activityRecordCls.getDeclaredMethod("forToken", android.os.IBinder.class);
            forToken.setAccessible(true);
            Object r = forToken.invoke(null, token);
            if (r != null) {
                say( "    反查路径 ①: ActivityRecord.forToken(IBinder) 命中");
                return r;
            }
        } catch (NoSuchMethodException e) {
            warn( "    反查路径 ①: 没有 forToken(IBinder) 方法");
        } catch (Throwable t) {
            warn( "    反查路径 ①: forToken 调用失败", t);
        }

        // 路径 ②：ActivityTaskManagerService.mRootWindowContainer 里遍历（兜底，代价高）
        try {
            ClassLoader cl = activityRecordCls.getClassLoader();
            Class<?> atms = cl.loadClass("com.android.server.wm.ActivityTaskManagerService");
            Method getInstance = atms.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object atmsInst = getInstance.invoke(null);
            if (atmsInst != null) {
                say( "    反查路径 ②: 拿到 ActivityTaskManagerService 实例，"
                        + "但遍历活动列表代价高，探针不展开（正式实现再定）");
            }
        } catch (Throwable t) {
            warn( "    反查路径 ②: 不可用", t);
        }

        return null;
    }

    /** 读字段并截断（避免 intent 太长撑爆日志）。 */
    private String readShort(Object obj, String name) {
        String v = readField(obj, name);
        return v.length() > 200 ? v.substring(0, 200) + "…" : v;
    }

    /** 反射读字段，失败返回 "?"（探针用，不追求健壮）。 */
    private String readField(Object obj, String name) {
        if (obj == null) return "null";
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) return "null";
                    // 常见：ComponentName / String
                    if (v instanceof android.content.ComponentName) {
                        return ((android.content.ComponentName) v).toShortString();
                    }
                    return String.valueOf(v);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    // ---------------------------------------------------------- #14 / #15

    /**
     * #14：system_server 内能否 bindService 到 vFlow？
     * #15：system_server 能否持有 vFlow 的 signature 权限？
     *
     * ⚠️ 这里只做**能力探测**，不做设计文档里的完整通信。
     * bindService 是异步的，回调里只打日志。
     */
    private void probeCommunication(android.content.Context ctx) {
        int pid = android.os.Process.myPid();
        int uid = android.os.Process.myUid();
        say( "  本进程 pid=" + pid + " uid=" + uid);

        // ═════════════════ #15：signature 权限 ═════════════════
        //
        // ⚠️ **必须做对照组**，否则结论会错：
        //   上一版只查了 `HOOK_CONTROL`（vFlow 定义的权限），得到 GRANTED ——
        //   但传的是 (pid=自己, uid=1000)，**查询方 = 被查询方**，
        //   无法区分「真放行」与「自己查自己一律放行」。
        //
        //   因此加查一个 **第三方 App 定义的 signature 权限**（misightservice 的 BIND_SERVICE）
        //   作为对照：
        //     · 若 两者都 GRANTED  ⇒ 「自检放行」，说明 HOOK_CONTROL 的 GRANTED 不可信
        //     · 若 前者 GRANTED、对照 DENIED ⇒ 「真放行」，结论成立
        try {
            if (ctx == null) {
                say( "  #15 拿不到 Context，跳过\n");
            } else {
                String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
                say( "  #15 本 uid(" + uid + ") 对应的包数="
                        + (pkgs == null ? "null" : pkgs.length));

                int r1 = ctx.checkPermission(
                        "com.vflow.hookprobe.permission.HOOK_CONTROL", pid, uid);
                say( "  #15 ① HOOK_CONTROL (vFlow 定义)      = "
                        + (r1 == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? "GRANTED" : "DENIED"));

                int r2 = ctx.checkPermission(
                        "com.miui.misightservice.permission.BIND_SERVICE", pid, uid);
                say( "  #15 ② BIND_SERVICE (第三方定义·对照) = "
                        + (r2 == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? "GRANTED" : "DENIED"));

                int r3 = ctx.checkPermission(
                        "com.xiaomi.smarthome.permission.PUSH_WRITE_PROVIDER", pid, uid);
                say( "  #15 ③ PUSH_WRITE_PROVIDER (对照2)    = "
                        + (r3 == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ? "GRANTED" : "DENIED"));

                boolean g1 = r1 == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean g2 = r2 == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean g3 = r3 == android.content.pm.PackageManager.PERMISSION_GRANTED;
                say( "  #15 判读：");
                if (g1 && (g2 || g3)) {
                    say( "⚠️ 目标权限 GRANTED，但【对照也 GRANTED】");
                    say( "      ⇒ 是「自己查自己一律放行」，**结论不可信**");
                    say( "      ⇒ system_server 能否持 vFlow 签名权限【仍未定论】");
                } else if (g1 && !g2 && !g3) {
                    say( "✅ 目标 GRANTED 且【对照均 DENIED】");
                    say( "      ⇒ 是【真放行】：system_server 确实持有 vFlow 的 signature 权限");
                    say( "      ⇒ bindService 那侧的权限保护成立（§4.2.4）");
                } else {
                    say( "目标权限 DENIED ⇒ system_server 拿不到，鉴权需完全靠 token（§4.2.4）");
                }
            }
        } catch (Throwable t) {
            say( "  #15 探测异常: " + t);
        }

        // ═════════════════ #14：bindService ═════════════════
        try {
            say( "  #14 Context: " + ctx.getClass().getName()
                    + " (包名=" + ctx.getPackageName() + ")");

            // 诊断 ContextImpl 内部状态 —— bindServiceCommon 需要 mMainThread
            say( "  #14 ContextImpl 内部状态：");
            for (String f : new String[]{"mMainThread", "mPackageManager",
                                         "mActivityManager", "mBase"}) {
                say( "      ." + f + " = " + readField(ctx, f));
            }

            // 目标 Service 是否存在
            try {
                android.content.pm.ResolveInfo ri = ctx.getPackageManager()
                        .resolveService(new android.content.Intent()
                                .setComponent(new android.content.ComponentName(TARGET_PKG, TARGET_SVC)), 0);
                say( "  #14 resolveService(" + TARGET_PKG + ") = "
                        + (ri == null ? "null ⇒ 服务不存在/不可见 ❌" : ri.serviceInfo.name));
            } catch (Throwable t) {
                say( "  #14 resolveService 失败: " + t);
            }

            android.content.Intent intent = new android.content.Intent()
                    .setComponent(new android.content.ComponentName(TARGET_PKG, TARGET_SVC));
            boolean ok = ctx.bindService(intent, new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
                    say("  ★★★ #14 bindService 成功，拿到 binder=" + service);
                    say("      连接状态本身即可作为「hook 挂载态」判据（§3.3）");
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                    say("  #14 onServiceDisconnected");
                }
            }, android.content.Context.BIND_AUTO_CREATE);

            say( "  #14 bindService() 返回 " + ok
                    + (ok ? " ⇒ 已提交，等 onServiceConnected（见 ★★★ 日志）"
                          : " ⇒ 被拒 ❌"));

            // ═══════════ #14b：可见性对照矩阵（**全部只读、不新增 IPC**）═══════════
            //
            // 目的：判断 #14 失败的**性质**。已知 `cmd package query-services`
            // （shell / uid 2000）**能看到**该 Service，而 uid 1000 看不到
            // ⇒ 两者都 < FIRST_APPLICATION_UID(10000)，**却结果不同**
            // ⇒ 不能只靠 AppsFilter 源码推理，必须拿对照数据。
            //
            // 判据设计：
            //   · 若「第三方包」全部查不到、而系统包能查到
            //     ⇒ 是【包可见性】过滤（与网络/广播无关）
            //   · 若 App 侧的 targetSdk 越高越查不到
            //     ⇒ 是 Android 11+ 的**静态过滤规则**在起作用
            //   · 若连 `getInstalledPackages` 都查不到目标包
            //     ⇒ 过滤发生在 package 级（比组件级更早）
            //
            // ⚠️ 这里**只调用只读查询 API**，与 #14 在同一个线程、同一时点，
            //    不增加新的跨进程调用（`getPackageManager()` 已在上面用过）。
            say( "  #14b 可见性对照矩阵（只读）：");
            probeVisibility(ctx.getPackageManager());

        } catch (Throwable t) {
            say( "  #14 bindService 抛异常: " + t);
        }
    }

    /**
     * #14b：可见性对照矩阵（**只读查询**，用于给 #14 的失败定性）。
     *
     * ⚠️ 只做「查询并打印」，**不改变任何状态、不发广播、不 bind**。
     */
    private void probeVisibility(android.content.pm.PackageManager pm) {
        try {
            // ① package 级可见性：目标包的整体可见性
            String[] pkgs = {
                    TARGET_PKG,                    // 我们的假 vFlow（普通应用）
                    "bin.mt.plus",                 // 普通应用（对照）
                    "com.android.settings",        // 系统应用（对照）
                    "com.chaomixian.vflow",        // 真 vFlow（普通应用）
            };
            for (String p : pkgs) {
                String r;
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(p, 0);
                    r = "✅可见 (targetSdk=" + pi.applicationInfo.targetSdkVersion + ")";
                } catch (Throwable t) {
                    r = "❌不可见 (" + t.getClass().getSimpleName() + ")";
                }
                say( "      getPackageInfo(" + p + ") = " + r);
            }

            // ② 组件级可见性：用不同查询方式对比（同一目标）
            say( "      组件级查询（目标=" + TARGET_PKG + "/" + TARGET_SVC + "）：");
            try {
                android.content.Intent explicit = new android.content.Intent()
                        .setComponent(new android.content.ComponentName(TARGET_PKG, TARGET_SVC));
                android.content.pm.ResolveInfo ri = pm.resolveService(explicit, 0);
                say( "          resolveService(显式组件) = " + (ri == null ? "null ❌" : "命中 ✅"));
            } catch (Throwable t) {
                say( "          resolveService 异常: " + t);
            }
            try {
                android.content.pm.ServiceInfo si = pm.getServiceInfo(
                        new android.content.ComponentName(TARGET_PKG, TARGET_SVC), 0);
                say( "          getServiceInfo(显式组件) = ✅ " + si.name);
            } catch (Throwable t) {
                say( "          getServiceInfo(显式组件) = ❌ "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }

            // ②b ⭐ 决定性对照：**同一次查询里，查两个不同的 Service**
            //
            // ⚠️ 已确认的设备事实（用 `adb shell` 从外部查到，与探针无关）：
            //    `cmd package query-services -n com.vflow.hookprobe.vflow/.FakeVFlowService`
            //    ⇒ 1 services found，且 `enabled=true exported=true permission=null`
            //    `cmd package query-services -n bin.mt.plus/bin.mt.function.ar.ActivityRecordService`
            //    ⇒ 1 services found
            //    **两个组件都真实存在且导出。**
            //
            // 假设 H1：**system_server（uid 1000）看不到【任何】第三方 Service**
            //   ⇒ 两个都查不到
            // 假设 H2：**只有我们那个包被特殊过滤**
            //   ⇒ 目标查不到、bin.mt.plus 查得到
            // 假设 H3：`getServiceInfo` 这条 API 有别的坑（与进程/uid 无关）
            //   ⇒ 两个都查得到，但别的地方失败
            //
            // ⚠️ 用**两个组件同一次调用**对比 —— 这是唯一能把
            //    「uid 1000 的通性」与「这个包的个性」区分开的做法。
            // ⚠️ 只读查询，不改任何状态。
            say( "      ⭐ 三组对照（同一次查询，只读）：");
            String[][] targets = {
                    {"com.android.systemui",
                     "com.android.systemui.personalcontext.AutofillRendererService"},   // ① 系统应用
                    {"bin.mt.plus",
                     "bin.mt.function.ar.ActivityRecordService"},                      // ② 第三方普通应用
                    {TARGET_PKG, TARGET_SVC},                                          // ③ 我们的包
            };
            for (String[] t : targets) {
                android.content.ComponentName cn =
                        new android.content.ComponentName(t[0], t[1]);
                String r1;
                try {
                    android.content.pm.ServiceInfo si = pm.getServiceInfo(cn, 0);
                    r1 = "✅ " + si.name + " (exported=" + si.exported + ")";
                } catch (Throwable e) {
                    r1 = "❌ " + e.getClass().getSimpleName();
                }
                say( "          getServiceInfo(" + t[0] + ") = " + r1);

                String r2;
                try {
                    android.content.pm.ResolveInfo ri = pm.resolveService(
                            new android.content.Intent().setComponent(cn), 0);
                    r2 = (ri == null ? "null ❌" : "命中 ✅");
                } catch (Throwable e) {
                    r2 = "异常 " + e.getClass().getSimpleName();
                }
                say( "          resolveService(" + t[0] + ") = " + r2);
            }

            // ②c 包级 Service 清单（GET_SERVICES）—— 看**可见的** services 数组长什么样
            //
            // 与 ②b 互补：②b 问「能否取到某个具体组件」，这里问
            // 「PackageInfo 里被过滤后还剩哪些组件」。
            say( "      ⭐ 包级 services 数组（GET_SERVICES，只读）：");
            for (String p : new String[]{TARGET_PKG, "bin.mt.plus"}) {
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(
                            p, android.content.pm.PackageManager.GET_SERVICES);
                    int n = (pi.services == null ? -1 : pi.services.length);
                    say( "          " + p + ": services=" + (n < 0 ? "null" : n + " 个"));
                    if (pi.services != null) {
                        for (int i = 0; i < pi.services.length && i < 4; i++) {
                            say( "              [" + i + "] " + pi.services[i].name);
                        }
                    }
                } catch (Throwable e) {
                    say( "          " + p + ": 查询失败 " + e.getClass().getSimpleName());
                }
            }

            // ③ 目标 App 自身的 uid（用于排除「同 uid 才可见」的干扰）
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(TARGET_PKG, 0);
                say( "      目标App uid=" + ai.uid + " (本进程 uid=1000)");
            } catch (Throwable t) {
                say( "      拿不到目标 App 的 uid: " + t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            say( "  #14b 对照矩阵异常: " + t);
        }
    }

    /**
     * 在 system_server 内拿 Context。
     *
     * ⚠️ system_server 没有 Activity，标准做法是反射
     * {@code ActivityThread.currentActivityThread().getSystemContext()}。
     * 这条路径本身也是探针要验的东西之一。
     */
    private android.content.Context getSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) {
                warn( "  currentActivityThread() 返回 null");
                return null;
            }
            Method getCtx = at.getDeclaredMethod("getSystemContext");
            getCtx.setAccessible(true);
            return (android.content.Context) getCtx.invoke(thread);
        } catch (Throwable t) {
            warn( "  getSystemContext 失败", t);
            return null;
        }
    }
}
