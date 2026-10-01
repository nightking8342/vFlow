package com.vflow.shortcutprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 快捷方式探针：注入 system_server，验掉 `xposed-capability-invocation-design.md` §6.2 的五项【推断】。
 *
 * ⚠️ 这是**一次性探针**，不是实现。它不接通信、不建框架、**不引任何 App 侧代码**，
 * 唯一产出是日志。正式实现是第二步的事（见 plan-4.md §6 S8–S14）。
 *
 * ## 要验的五项（对应 plan-4.md §5 的判定表）
 *
 * | # | 问题 | 打印点 | 通过判据 |
 * |---|---|---|---|
 * | 1 | {@code LocalServices.getService(ShortcutService)} 能否取到 | [1] | 非 null 且类名是 com.android.server.pm.ShortcutService |
 * | 2 | ★ hook 层调用时 {@code injectBinderCallingUid()} 返回什么 | [5] | 返回 1000 |
 * | 3 | 内部字段名是否随版本漂移 | [1.1]/[2.1]/[2.2] | 能看到 mShortcutUsers（或等价） |
 * | 4 | getIntents() 稳定、dat 完整、extras 的 javaClass | [6.2]/[6.3] | dat 完整、extra_scene_account 是 java.lang.String |
 * | 5 | ShortcutPackage 是否已按当前用户过滤 | [7]/[8] | userKeys 匹配当前用户 |
 *
 * 外加两项本轮新增（plan-4.md 里记为「额外的第 4/6 项」）：
 * [6.0] 米家那条的定位过程（区分「dat 残缺」与「漏包」）；
 * [3.2]/[3.3] 全量体积估算（**三个口径**，见下）。
 *
 * ## ⚠️⚠️ `[3.2]`/`[3.3]` 的口径警告（2026-10-01 修订，别按老写法换算）
 *
 * 真实 wire parcel（能否发得出去）≈ **UTF-16 代码单元数 × 2 + 信封固定开销**
 * —— binder 的 AIDL 字符串走 `Parcel.writeString16`。
 *
 * ⚠️ **不是**「UTF-8 字节数 × 2」：那只在**全 ASCII** 下巧合成立。
 *
 * | 载荷 | 单元数 : UTF-8 字节数 | 「UTF-8×2」相对真实值的误差 |
 * |---|---|---|
 * | 全 ASCII | 1 : 1 | 恰好相等 |
 * | **含 CJK** | 1 : **3** | **高估 3 倍** |
 *
 * ⚠️ 但**本 capability 的实际载荷几乎是纯 ASCII**：本机 407 条实测
 * CJK 只占 **1.8%**，`UTF-8 / 单元 = 1.04` ⇒ 「×2」在本场景下只高估 **4%**。
 * **机制要写对，但别据此以为本 capability 的数是错的。**
 *
 * ⇒ 探针**三个口径都报**（UTF-8 下界 / 单元数 / parcel 上界 + 「UTF-8×2」对照），
 * 换算不是探针的职责。
 *
 * ## ⚠️ 三条硬约束（都来自既有实测教训）
 *
 * 1. **绝不写文件**。曾加过「每次打日志都写文件到 /sdcard」的版本，
 *    **模块完全不被加载**（装回不写文件的版本即正常）。探针路径上零 I/O。
 * 2. **延后 60 秒再跑**。探针比设备解锁早约 47 秒（P0-FINDINGS.md:198 实测），
 *    未解锁时包解析会得到「假的拿不到」。延迟在**独立线程**里做 ——
 *    `onSystemServerStarting` 的调用栈上阻塞会拖住 system_server 启动。
 * 3. **所有反射逐层 try/catch**。任一项失败只打日志、继续下一项 ——
 *    探针的价值在于「一次跑完拿到尽可能多的结论」，任何一处抛都不该中断全程。
 *
 * ## 使用
 *
 * 1. 打包：`bash scripts/probe/xposed-channel/build.sh shortcutprobe`
 * 2. 安装：`adb install -r -t out/shortcutprobe.apk`
 * 3. 在 LSPosed 里启用本模块，**作用域勾选「系统框架」**（scope.list 写的是 `system`）
 * 4. **重启设备**（system_server 要重启才会注入）
 * 5. 抓日志：`bash scripts/xposed-shortcut-probe-verify.sh`
 */
public class ShortcutProbeEntry extends XposedModule {

    public static final String TAG = "ShortcutProbe";

    /** 探针延后执行的毫秒数。见类注释第 2 条。 */
    private static final long DELAY_MS = 60_000L;

    /**
     * 目标 App：米家。dat 残缺与 extras 类型丢失的典型（survey §3.3.1）。
     *
     * ⚠️⚠️ **包名是 `com.xiaomi.smarthome`，不是 `com.xiaomi.mihome`** ——
     * 后者出现在 survey `:620` 与设计文档 `:440` 里，是讲 `verifyCaller` 时的随手举例，
     * **是错的**（同一份 survey 的 `:287` 用的是正确包名，文档内部就不一致）。
     * 真机 `pm list packages` 实测确认：米家 = `com.xiaomi.smarthome`。
     */
    private static final String TARGET_PKG = "com.xiaomi.smarthome";

    /** 那条例外 extras 的键：vFlow 把它猜成 Long，而米家要 String（报「无账号权限」）。 */
    private static final String TARGET_EXTRA_KEY = "extra_scene_account";

    /** 找不到目标包时，改报这么多条「带 dat 的样本」，供脚本侧与 dumpsys 配对。 */
    private static final int FALLBACK_SAMPLE_COUNT = 5;

    // ── 阶段 A：连接期（父会话的 T2 缺陷暴露在这里）──
    /** 收发双向掉过的包：`binderCallingUid`。 */
    private static final String INJECT_UID_METHOD = "injectBinderCallingUid";

    /** ShortcutService 的候选类名（版本间可能搬家）。 */
    private static final String[] SERVICE_CLASS_CANDIDATES = {
            "com.android.server.pm.ShortcutService",
            "com.android.server.pm.shortcut.ShortcutService",
    };

    // ══════════════════════════════════════════════════════════
    // 日志出口
    // ══════════════════════════════════════════════════════════

    /**
     * 统一日志出口。**只打日志，绝不写文件**（见类注释第 1 条）。
     *
     * ⚠️ 用 ERROR 级：LSPosed 的框架日志实测只持久化 Error 级（INFO/WARN 看不到）。
     * 双通道：`log()`（框架日志）+ `android.util.Log`（logcat，但启动期会被开机洪流挤掉）。
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

    private void say(String msg, Throwable t) {
        say(msg + " ✗ " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    // ══════════════════════════════════════════════════════════
    // 生命周期回调
    // ══════════════════════════════════════════════════════════

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            say("════ ShortcutProbe onModuleLoaded ════");
            say("  processName   = " + param.getProcessName());
            say("  isSystemServer= " + param.isSystemServer());
            say("  apiVersion    = " + getApiVersion());
        } catch (Throwable t) {
            say("onModuleLoaded 失败", t);
        }
    }

    /**
     * 作用域进程（system_server）即将就绪。
     *
     * ⚠️ **本回调必须立刻返回** —— 它跑在 system_server 的启动路径上，阻塞会拖住整机启动。
     * 真正的探针延后 60 秒、在**独立线程**里跑（见类注释第 2 条）。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            say("════ ShortcutProbe onSystemServerStarting ════");
            say("  将于 " + (DELAY_MS / 1000) + " 秒后开始探测" +
                    "（设备解锁比探针早约 47 秒；未解锁时包解析会给出假的『拿不到』）");
        } catch (Throwable t) {
            say("onSystemServerStarting 日志失败", t);
        }

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(DELAY_MS);
                } catch (InterruptedException e) {
                    say("[延迟] 被中断，立即开始");
                }
                try {
                    runProbe();
                } catch (Throwable t) {
                    // ⚠️ 探针跑在 system_server 里，异常绝不能逃逸（崩溃半径 = 整机）
                    say("探针整体失败（已捕获，不影响系统）", t);
                }
            }
        }, "ShortcutProbe-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        say("════ ShortcutProbe onHotReloading ════");
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        say("════ ShortcutProbe onHotReloaded ════（探针不重挂 hook，无操作）");
    }

    // ══════════════════════════════════════════════════════════
    // 探针主体
    // ══════════════════════════════════════════════════════════

    private void runProbe() {
        long t0 = System.currentTimeMillis();
        say("════════ ShortcutProbe 开始 ════════");

        Object shortcutService = probeService();
        if (shortcutService == null) {
            say("⚠️ 拿不到 ShortcutService —— 后续项全部跳过（见 §5 第 1 项的失败退路）");
            say("════════ ShortcutProbe 结束（" + (System.currentTimeMillis() - t0) + " ms）════════");
            return;
        }

        Map<String, Object> users = probeUsers(shortcutService);
        List<Object> allShortcuts = collectAll(users);
        probeCount(allShortcuts);
        probeInjectUid(shortcutService);
        probeMihome(allShortcuts);
        probeUserFiltering(shortcutService, users);
        probeCount2(allShortcuts);

        say("════════ ShortcutProbe 结束（" + (System.currentTimeMillis() - t0) + " ms）════════");
    }

    // ── [1] LocalServices.getService(ShortcutService) ─────────────

    private Object probeService() {
        say("[1] LocalServices.getService(ShortcutService) ──");
        Class<?> cls = null;
        for (String name : SERVICE_CLASS_CANDIDATES) {
            try {
                cls = Class.forName(name);
                say("[1]   类名命中：" + name);
                break;
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (cls == null) {
            say("[1] ✗ 候选类名全未命中：" + String.join(", ", SERVICE_CLASS_CANDIDATES));
            return null;
        }

        Object svc = null;
        try {
            Class<?> localServices = Class.forName("com.android.server.LocalServices");
            Method getService = localServices.getDeclaredMethod("getService", Class.class);
            getService.setAccessible(true);
            svc = getService.invoke(null, cls);
        } catch (Throwable t) {
            say("[1] ✗ LocalServices.getService 失败", t);
            return null;
        }

        if (svc == null) {
            say("[1] ✗ LocalServices 返回 null（服务未注册 / 时机太早）");
            return null;
        }
        say("[1] ✓ 拿到：" + svc.getClass().getName());
        dumpFields("[1.1] ShortcutService 字段", svc);
        return svc;
    }

    // ── [2] ShortcutUser / ShortcutPackage ────────────────────────

    /**
     * 按**类型**找「用户映射」字段 —— **不写死字段名**。
     *
     * 这是 plan-4.md §5 第 3 项的落实：内部字段名随版本会漂移，
     * 正式实现必须按「Map 且 value 看起来像 ShortcutUser」来探，而不是硬编码 `mShortcutUsers`。
     * 探针这里顺带把**全字段转储**打出来，供正式实现产出候选名单。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> probeUsers(Object svc) {
        say("[2] 找用户映射字段（按类型探，不写死名）──");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : allFields(svc.getClass())) {
            try {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(svc);
                if (!(v instanceof Map)) continue;
                Map<Object, Object> m = (Map<Object, Object>) v;
                say("[2]   候选字段 " + f.getName() + "（" + f.getType().getSimpleName()
                        + "，size=" + m.size() + "）");
                if (m.isEmpty()) continue;

                Object first = m.values().iterator().next();
                String vCls = first == null ? "null" : first.getClass().getName();
                say("[2]     值类型：" + vCls);
                // 只收「值看起来像 ShortcutUser」的那个字段
                if (vCls.contains("ShortcutUser")) {
                    say("[2]     ✓ 判定为 ShortcutUser 映射：" + f.getName()
                            + "，userKeys=" + describeKeys(m));
                    for (Map.Entry<Object, Object> e : m.entrySet()) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                        dumpFields("[2.1] ShortcutUser[" + e.getKey() + "] 字段", e.getValue());
                        probePackages(e.getValue());
                    }
                }
            } catch (Throwable t) {
                say("[2]   字段 " + f.getName() + " 读取失败", t);
            }
        }
        if (out.isEmpty()) say("[2] ✗ 没有找到任何 ShortcutUser 映射");
        return out;
    }

    private void probePackages(Object user) {
        if (user == null) return;
        for (Field f : allFields(user.getClass())) {
            try {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(user);
                if (!(v instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) v;
                if (m.isEmpty()) continue;
                Object first = m.values().iterator().next();
                String vCls = first == null ? "null" : first.getClass().getName();
                if (!vCls.contains("ShortcutPackage")) continue;

                say("[2.2] ShortcutPackage 映射字段：" + f.getName() + "，size=" + m.size());
                if (first != null) dumpFields("[2.2] ShortcutPackage 字段", first);
                // 只详查目标包，避免刷屏
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (TARGET_PKG.equals(String.valueOf(e.getKey()))) {
                        say("[2.3] ShortcutPackage.mPackageName = " + e.getKey());
                        say("[2.4] ShortcutPackage.mShortcuts.size = " + countShortcuts(e.getValue()));
                    }
                }
            } catch (Throwable t) {
                say("[2.2] 读取失败：" + f.getName(), t);
            }
        }
    }

    private int countShortcuts(Object pkg) {
        if (pkg == null) return -1;
        for (Field f : allFields(pkg.getClass())) {
            try {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(pkg);
                if (v instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) v;
                    if (!m.isEmpty()) {
                        Object first = m.values().iterator().next();
                        String c = first == null ? "" : first.getClass().getName();
                        if (c.contains("ShortcutInfo")) return m.size();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    // ── [3] 全量遍历 + 计数 + 字节估算 ────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Object> collectAll(Map<String, Object> users) {
        List<Object> all = new ArrayList<>();
        for (Object user : users.values()) {
            if (user == null) continue;
            for (Field f : allFields(user.getClass())) {
                try {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(user);
                    if (!(v instanceof Map)) continue;
                    for (Object pkg : ((Map<?, ?>) v).values()) {
                        all.addAll(shortcutsOf(pkg));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return all;
    }

    private List<Object> shortcutsOf(Object pkg) {
        List<Object> out = new ArrayList<>();
        if (pkg == null) return out;
        for (Field f : allFields(pkg.getClass())) {
            try {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(pkg);
                if (!(v instanceof Map)) continue;
                for (Object info : ((Map<?, ?>) v).values()) {
                    if (info != null && info.getClass().getName().contains("ShortcutInfo")) {
                        out.add(info);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private void probeCount(List<Object> all) {
        say("[3] 全量遍历：ShortcutInfo 总数 = " + all.size());

        long rawBytes = 0;
        StringBuilder detail = new StringBuilder();
        for (Object info : all) {
            int n = intentCount(info);
            long b = estimateRawBytes(info);
            rawBytes += b;
            String pkg = str(readField(info, "mPackageName"));
            String label = str(readField(info, "mShortLabel"));
            detail.append("\n[3.1]   ").append(pkg).append("|").append(label)
                    .append("|intents=").append(n).append("|~").append(b).append("B");
        }
        // 明细只打前 20 条，其余折叠（LSPosed 日志有长度限制）
        // ⚠️ 从 1 开始：detail 以 "\n" 开头，第 0 项是空串（打了会得到一行无意义的 "[3.1] "）
        String[] lines = detail.toString().split("\n");
        for (int i = 1; i < Math.min(lines.length, 21); i++) say(lines[i]);
        if (lines.length > 21) say("[3.1]   …（其余 " + (lines.length - 21) + " 条省略）");

        // ⚠️⚠️ 口径必须写准（2026-10-01 父会话更正了一次机制说明，我复核后采纳）：
        //
        //   真实 parcel ≈ UTF-16 **代码单元数** × 2 + 信封固定开销
        //                （binder 的 AIDL 字符串走 Parcel.writeString16）
        //
        // **不是**「UTF-8 字节数 × 2」—— 那只在**全 ASCII** 下巧合成立：
        //   · 全 ASCII：1 单元 = 1 UTF-8 字节 ⇒ 两者相等（实测比值 2.04）
        //   · CJK：     1 单元 = 3 UTF-8 字节 ⇒ 「UTF-8×2」是真实值的 **3 倍**（错）
        //
        // ⇒ 探针**三个口径都报**，让读的人自己选；换算本身不是探针的职责。
        long units = 0;
        for (Object info : all) units += estimateRawUnits(info);
        long parcelUpper = units * 2;         // 真实 parcel 的上界
        long asciiNaive = rawBytes * 2;       // 「UTF-8 × 2」——仅全 ASCII 时等于上界
        say("[3.2] 全量 resultJson 估算（UTF-8 下界）：raw = " + rawBytes + " B"
                + "（单条均 " + (all.isEmpty() ? 0 : rawBytes / all.size()) + " B）");
        say("[3.3] ★ 全量 UTF-16 代码单元 = " + units + "（单条均 "
                + (all.isEmpty() ? 0 : units / all.size()) + "）");
        say("[3.3] ★ 真实 parcel 上界 = 单元×2 = " + parcelUpper + " B"
                + "（半缓冲约 520192 B）");
        say("[3.3]   对照「UTF-8×2」= " + asciiNaive + " B"
                + "（比值 " + fmt(asciiNaive, parcelUpper) + "；全 ASCII 时 ≈1.00，全 CJK 时 ≈3.00）");
        say("[3.3]   ⚠️ 换算成 parcel 要用【单元×2】，**不要用 UTF-8×2**（见类注释的口径说明）");
        say("[3.3]   截断决策的条数上限（content 口径 262144B）："
                + estimateCapacity(all, rawBytes, 262144L) + " 条");
        say("[3.3]   截断决策的条数上限（信封 parcel 口径 393216B=384KiB）："
                + estimateCapacity(all, parcelUpper, 393216L) + " 条");
    }

    private long estimateCapacity(List<Object> all, long totalBytes, long limit) {
        if (all.isEmpty() || totalBytes <= 0) return 0;
        long perItem = Math.max(1, totalBytes / all.size());
        return limit / perItem;
    }

    /**
     * 单条 ShortcutInfo 的 `resultJson` 口径**估算**（不真序列化）。
     *
     * ⚠️ 只累加字段长度，不拼 JSON —— `dumpsys` 全量是 436 KB，
     * 在 system_server 里拼一份等量 JSON 没有必要，且会引入内存/耗时风险。
     * 这个数是**下界估计**（不含键名与 JSON 结构开销），故保守。
     */
    private long estimateRawBytes(Object info) {
        return collectEstimate(info, true);
    }

    /** 同上，但按 **UTF-16 代码单元**数（喂给真实 parcel 口径）。 */
    private long estimateRawUnits(Object info) {
        return collectEstimate(info, false);
    }

    /**
     * 单条 ShortcutInfo 的内容量估算。
     *
     * @param utf8 true ⇒ 计 UTF-8 字节；false ⇒ 计 UTF-16 代码单元
     *
     * ⚠️ 二者**不是同一个量**：CJK 字符 1 单元 = 3 UTF-8 字节。
     * 真实 parcel 走 `writeString16` ⇒ 按**单元**算，不是按 UTF-8 字节算。
     */
    private long collectEstimate(Object info, boolean utf8) {
        long n = 0;
        n += measure(str(readField(info, "mPackageName")), utf8);
        n += measure(str(readField(info, "mShortLabel")), utf8);
        n += measure(str(readField(info, "mActivity")), utf8);
        try {
            for (Object intent : intentsOf(info)) {
                n += measure(String.valueOf(intent), utf8);
                Object extras = callNoArg(intent, "getExtras");
                if (extras != null) n += measure(String.valueOf(extras), utf8) * 2; // extras 双份：key + value
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static String fmt(long a, long b) {
        if (b <= 0) return "n/a";
        return String.format(java.util.Locale.ROOT, "%.2f", (double) a / b);
    }

    // ── [4]/[5] 计数对比 + injectBinderCallingUid ─────────────────

    private void probeCount2(List<Object> all) {
        // ⚠️ 探针**读不到** `dumpsys` 的输出（那是 shell 的），所以这里只报 hook 侧的计数，
        // 对比交回 verify 脚本（它两边都能拿到）。这样切分是为了让探针保持「只读数据、不执行命令」。
        say("[4] 计数对比：hook=" + all.size() + " dumpsys=?（由 xposed-shortcut-probe-verify.sh 比对）");
    }

    /**
     * ★ 文档标注「唯一必须真机验的项」。
     *
     * `isCallerSystem()` = `UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)`，
     * 而 `getShortcuts` 的 `verifyCaller` 唯一豁免就是它。
     * 若此处返回 1000 ⇒ 系统服务在 system_server 内互调**不应**需要改包可见性；
     * 若返回非 1000 ⇒ 需要在 hook 层给 system_server 的 PackageManager 放行。
     */
    private void probeInjectUid(Object svc) {
        say("[5] ★ injectBinderCallingUid()（文档标注的唯一必须真机验项）──");
        try {
            Method m = null;
            Class<?> c = svc.getClass();
            while (c != null && m == null) {
                try {
                    m = c.getDeclaredMethod(INJECT_UID_METHOD);
                } catch (NoSuchMethodException ignored) {
                }
                c = c.getSuperclass();
            }
            if (m == null) {
                say("[5] ✗ 找不到方法 " + INJECT_UID_METHOD + "（在服务类及其父类上）");
                return;
            }
            m.setAccessible(true);
            Object uid = m.invoke(svc);
            say("[5] ✓ 返回 = " + uid + "（期望 1000 = Process.SYSTEM_UID）");
        } catch (Throwable t) {
            say("[5] ✗ 调用失败", t);
        }
    }

    // ── [6] 米家那条 ─────────────────────────────────────────────

    private void probeMihome(List<Object> all) {
        say("[6] ★ 米家（" + TARGET_PKG + "）那条 ──");
        List<Object> hits = new ArrayList<>();
        for (Object info : all) {
            if (TARGET_PKG.equals(str(readField(info, "mPackageName")))) hits.add(info);
        }

        say("[6.0] 定位到 " + hits.size() + " 条属于 " + TARGET_PKG);
        if (hits.isEmpty()) {
            // ⚠️ 两种可能，必须区分（否则会把「本来就没建」误判成「漏包」）：
            //   ① 该设备上米家**没有快捷方式**（用户没建）—— 真机实测这台就是这种
            //   ② 有但被包可见性过滤掉（「漏包」）
            // 判据：`dumpsys shortcut` 里也没有 ⇒ 是 ①；有 ⇒ 是 ②。
            // 探针读不到 shell 输出，故把两个方向都提示出来，由 verify 脚本比对。
            say("[6.0] ✗ 未找到。两种可能，**先用 dumpsys 区分**：");
            say("[6.0]    ① dumpsys 里也没有 ⇒ 该设备上米家本来就没建快捷方式（非缺陷）");
            say("[6.0]    ② dumpsys 里有、这里没有 ⇒ **漏包**（包可见性，与 [5] 互为印证）");
            say("[6.0]    → 见 verify 脚本的『目标样本选取』一节");
            probeDatSamples(all, FALLBACK_SAMPLE_COUNT);
            return;
        }

        Object info = hits.get(0);
        say("[6.0] 样本：" + str(readField(info, "mShortLabel")));
        List<Object> intents = intentsOf(info);
        say("[6.1] getIntents().size = " + intents.size());
        if (intents.isEmpty()) {
            say("[6.2] ✗ getIntents() 为空 —— 该条无可用 Intent");
            return;
        }

        Object last = intents.get(intents.size() - 1);
        // ⚠️ 看 dam 是否完整 —— 这是 dumpsys 路径丢的 18.1%
        try {
            Method toUri = last.getClass().getMethod("toUri", int.class);
            toUri.setAccessible(true);
            say("[6.2] Intent[last].toUri(0) = " + toUri.invoke(last, 0));
        } catch (Throwable t) {
            say("[6.2] toUri 失败，改打 toString： " + last);
        }

        // ⚠️ [6.3]：extras 的 javaClass —— vFlow 在 dumpsys 路径上**只能按数字形态猜**，
        // 猜成 Long 就会让米家 getString() 拿到 null、报「无账号权限」（survey §3.3.1）
        Object extras = callNoArg(last, "getExtras");
        if (extras == null) {
            say("[6.3] ✗ getExtras() 为 null —— 改试 mExtras 字段");
            extras = readField(last, "mExtras");
        }
        if (extras == null) {
            say("[6.3] ✗ 拿不到 extras");
            return;
        }
        say("[6.3] extras 类 = " + extras.getClass().getName());
        dumpExtras(extras);

        // [6.4] 全量转储
        say("[6.4] extras 全量 = " + String.valueOf(extras));
    }

    /**
     * 回退路径：目标包没有快捷方式时，报若干条**带 dat 的样本**。
     *
     * ## 为什么必须有它（不是锦上添花）
     *
     * 本次真机实测量到一个前提问题（survey 文档没写）：
     * **这台设备上米家根本没有快捷方式** ⇒ `[6]` 会走到「未找到」，
     * 而那正是「dat 是否完整」这条判据**唯一需要证据**的地方。
     *
     * 若就此返回，探针的核心结论（「hook 能拿到完整 dat」）**就永远拿不到证据** ——
     * 而 dat 完整性恰好是本 capability 存在的理由（dumpsys 路径丢 18.1%）。
     *
     * ⇒ 改报**任意几条带 dat 的样本**：脚本侧拿同样的标识去 `dumpsys` 里找，
     * 就能做成「hook 侧完整 vs dumpsys 侧省略」的配对对照。
     * 这**不依赖米家**，因此不受「这台设备没建米家快捷方式」的影响。
     */
    private void probeDatSamples(List<Object> all, int limit) {
        say("[6.5] 回退：报若干条**带 dat** 的样本，供脚本与 dumpsys 配对对照");
        int shown = 0;
        for (Object info : all) {
            if (shown >= limit) break;
            List<Object> intents = intentsOf(info);
            if (intents.isEmpty()) continue;
            Object last = intents.get(intents.size() - 1);
            String uri = "";
            try {
                Method toUri = last.getClass().getMethod("toUri", int.class);
                toUri.setAccessible(true);
                uri = String.valueOf(toUri.invoke(last, 0));
            } catch (Throwable t) {
                uri = String.valueOf(last);
            }
            // 只报带 dat 的（dat 才是 dumpsys 会省略的那个字段）
            int at = uri.indexOf("dat=");
            if (at < 0) continue;

            String pkg = str(readField(info, "mPackageName"));
            String label = str(readField(info, "mShortLabel"));
            Object extras = callNoArg(last, "getExtras");
            say("[6.5]   " + pkg + " | " + label);
            say("[6.5]     dat = " + uri.substring(at, Math.min(uri.length(), at + 160)));
            if (extras != null) {
                say("[6.5]     extras = " + extras);
            }
            shown++;
        }
        if (shown == 0) {
            say("[6.5] ✗ 全部条目都没有 dat —— 说明 ③ 的收益在本设备样本上可能无法体现");
        } else {
            say("[6.5] ✓ 已报 " + shown + " 条样本（脚本侧去 dumpsys 找同名项比对）");
        }
    }

    private void dumpExtras(Object extras) {
        try {
            Method keySet = extras.getClass().getMethod("keySet");
            keySet.setAccessible(true);
            Object ks = keySet.invoke(extras);
            if (!(ks instanceof Iterable)) {
                say("[6.3] keySet 返回非 Iterable");
                return;
            }
            Method get = extras.getClass().getMethod("get", String.class);
            get.setAccessible(true);
            for (Object k : (Iterable<?>) ks) {
                String key = String.valueOf(k);
                Object v = get.invoke(extras, key);
                String cls = v == null ? "null" : v.getClass().getName();
                String mark = TARGET_EXTRA_KEY.equals(key) ? "  ← ★ vFlow 猜成 Long 的那个键" : "";
                say("[6.3]   " + key + " -> " + cls + " = " + v + mark);
            }
        } catch (Throwable t) {
            say("[6.3] 遍历 extras 失败", t);
        }
    }

    // ── [7]/[8] 用户过滤 ─────────────────────────────────────────

    private void probeUserFiltering(Object svc, Map<String, Object> users) {
        say("[7] ShortcutPackage 用户归属 ──");
        say("[7]   ShortcutUser 映射的 key 集合 = " + users.keySet());
        int myUserId = -1;
        try {
            Class<?> uh = Class.forName("android.os.UserHandle");
            Method my = uh.getDeclaredMethod("myUserId");
            my.setAccessible(true);
            Object v = my.invoke(null);
            myUserId = v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            say("[7]   读 myUserId 失败", t);
        }
        say("[7]   UserHandle.myUserId() = " + myUserId);

        say("[8] 是否已按当前用户过滤 ──");
        boolean filtered = users.size() == 1 && users.containsKey(String.valueOf(myUserId));
        say("[8]   " + (filtered ? "✓ 单用户，key 匹配（注意：单用户设备上『已过滤』与『只有一个用户』不可区分）"
                : "⚠️ key 集合含多用户或与 myUserId 不符 ⇒ 正式实现**必须**显式按 myUserId 过滤"));
    }

    // ══════════════════════════════════════════════════════════
    // 反射工具（全部带兜底，绝不抛）
    // ══════════════════════════════════════════════════════════

    private static List<Field> allFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) out.add(f);
        }
        return out;
    }

    private void dumpFields(String tag, Object obj) {
        if (obj == null) {
            say(tag + " = null");
            return;
        }
        StringBuilder sb = new StringBuilder(tag).append("：");
        for (Field f : allFields(obj.getClass())) {
            sb.append("\n").append(tag).append("   ")
                    .append(f.getType().getSimpleName()).append(" ").append(f.getName());
        }
        for (String line : sb.toString().split("\n")) say(line);
    }

    private Object readField(Object obj, String name) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object callNoArg(Object obj, String name) {
        if (obj == null) return null;
        try {
            Method m = obj.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Object> intentsOf(Object info) {
        List<Object> out = new ArrayList<>();
        if (info == null) return out;
        Object v = callNoArg(info, "getIntents");
        if (v instanceof List) {
            for (Object o : (List<?>) v) out.add(o);
            return out;
        }
        // 退路：直接读 mIntents 字段
        Object f = readField(info, "mIntents");
        if (f instanceof List) {
            for (Object o : (List<?>) f) out.add(o);
        }
        return out;
    }

    private int intentCount(Object info) {
        return intentsOf(info).size();
    }

    private static String describeKeys(Map<?, ?> m) {
        List<String> keys = new ArrayList<>();
        for (Object k : m.keySet()) keys.add(String.valueOf(k));
        return keys.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int len(String s) {
        return s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * 按指定口径量一个串。
     *
     * ⚠️ 按**代码单元**数时用 `codePointAt` + `charCount`，而不是 `length` ——
     * 与 `InvokePolicy.estimatedParcelBytes` 同一写法：
     * 它能把**孤立代理**（不成对的 `Char`）也算成 2 单元，
     * 而 binder 写的正是 UTF-16 代码单元。
     *（本仓库在 `ActivityPayload.truncateToBytes` 上踩过「按字符算会低估」的坑。）
     */
    private static int measure(String s, boolean utf8) {
        if (s == null) return 0;
        if (utf8) return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int units = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            units += (cp > 0xFFFF) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return units;
    }
}
