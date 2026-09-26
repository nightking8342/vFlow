package com.vflow.hookprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 探针：假 hook 层（第三方签名，模拟「hook 代码跑在目标 App 进程里」）。
 *
 * ⚠️ 本 APK 刻意【不声明任何 <queries>】—— 这是 Q1 的全部意义所在。
 *    普通第三方 App 不会声明能看到 vFlow，而 hook 层在目标 App 进程里
 *    用的正是目标 App 的可见性。所以「不声明 queries 的第三方能否 bind 到 vFlow」
 *    就是「hook 层能否 bind 到 vFlow」。
 *
 * 四条验证，每条的判据见注释。
 */
public class ProbeActivity extends Activity {

    public static final String TAG = "HookProbe/Hook";
    public static final String VFLOW_SERVICE = "com.vflow.hookprobe.FakeVFlowService";
    public static final String VFLOW_RECEIVER_PKG = "com.vflow.hookprobe.vflow";
    public static final String ACTION_PUSH = "com.vflow.hookprobe.PUSH_CONDITIONS";
    public static final String ACTION_OPEN = "com.vflow.hookprobe.PUSH_OPEN";
    public static final String ACTION_FREE = "com.vflow.hookprobe.PUSH_FREE";

    private TextView output;
    private boolean bound = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            say("Q1 bindService: ✅ 成功");
            say("   service=" + service);
            say("   ⭐ 结论：hook 层【能】bind 到 vFlow（包可见性不拦显式 bind）");
            Log.i(TAG, "Q1 BIND_OK binder=" + service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        output = new TextView(this);
        output.setTextSize(11f);
        root.addView(output);

        addButton(root, "Q1  bindService 到 vFlow", this::probeBind);
        addButton(root, "Q4  startService 到 vFlow", this::probeStartService);
        addButton(root, "Q2/Q3 发受保护广播", this::probeSendGuardedBroadcast);
        addButton(root, "Q5  读 /sdcard/vFlow/", this::probeReadSharedFile);
        addButton(root, "清屏", () -> output.setText(""));

        setContentView(root);

        say("=== HookProbe 启动 ===");
        say("本 APK uid=" + android.os.Process.myUid()
                + "  pkg=" + getPackageName());
        say("（对照：真 vFlow uid=10684，假 vFlow 装了之后见其自身日志）");
        say("");

        // 支持「无人值守全跑」：adb shell am start ... --ez autorun true
        // 这样不必点按钮（本机 uiautomator dump 不可用，点按钮难自动化）
        if (getIntent() != null && getIntent().getBooleanExtra("autorun", false)) {
            say("*** autorun 模式：按顺序跑完全部验证 ***");
            root.postDelayed(this::probeBind, 500);
            root.postDelayed(this::probeReadSharedFile, 1500);
            root.postDelayed(this::probeSendGuardedBroadcast, 2500);
            root.postDelayed(this::probeStartService, 3500);
            root.postDelayed(() -> say("*** autorun 完成 ***"), 4500);
        }
    }

    private void addButton(LinearLayout root, String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> { try { r.run(); } catch (Throwable t) { say("❌ " + t); } });
        root.addView(b);
    }

    private void say(String s) {
        Log.i(TAG, s);
        output.append(s + "\n");
    }

    // ---------- Q1: bindService（包可见性）----------

    private void probeBind() {
        say("");
        say("--- Q1 bindService（不声明 <queries>）---");
        Intent i = new Intent().setComponent(
                new ComponentName(VFLOW_RECEIVER_PKG, VFLOW_SERVICE));
        try {
            boolean ok = bindService(i, conn, Context.BIND_AUTO_CREATE);
            say("bindService() 返回 " + ok);
            if (!ok) {
                say("❌ 结论：bind 被拒 —— 包可见性拦住了");
                say("   ⇒ hook 层需要目标 App 声明 <queries>，或改用广播");
            }
            // 回调 onServiceConnected / onServiceDisconnected 见上
        } catch (Throwable t) {
            say("❌ bindService 抛异常: " + t);
            say("   ⇒ 包可见性拦住了（NameNotFoundException 语义）");
            Log.e(TAG, "Q1 BIND_FAIL", t);
        }
    }

    // ---------- Q4: startService（Android 12 后台启动限制）----------

    private void probeStartService() {
        say("");
        say("--- Q4 startService ---");
        Intent i = new Intent().setComponent(
                new ComponentName(VFLOW_RECEIVER_PKG, VFLOW_SERVICE));
        try {
            ComponentName cn = startService(i);
            say("startService() 返回 " + cn);
            say("（注意：本 App 此刻在前台，属豁免场景之一）");
            say("真正的判据是「后台时」——见 build.sh 输出的 am 命令");
        } catch (Throwable t) {
            say("❌ startService 抛异常: " + t);
            Log.e(TAG, "Q4 START_FAIL", t);
        }
    }

    // ---------- Q2/Q3: 受保护广播 ----------

    private void probeSendGuardedBroadcast() {
        say("");
        say("--- Q2/Q3 发受保护广播 ---");

        // ⚠️ 先分离两个可能的原因：包不可见 / 权限被拦
        boolean visible = isPackageVisible(VFLOW_RECEIVER_PKG);
        say("包可见性: vFlow 对我" + (visible ? "【可见】" : "【不可见】"));
        if (!visible) {
            say("  ⚠️ 包不可见 ⇒ 广播即使是隐式 action 也可能【投不出去】");
            say("     （这比『权限拦住』更基础 —— 权限检查前就投递失败了）");
        }
        say("本 APK 签名 = " + (isSameSigAsVFlow() ? "同签" : "异签/不可见"));

        // ★ 先报告自己持有这两个权限的状况 —— 这是结论成立的前提
        String HOOK_CONTROL = "com.vflow.hookprobe.permission.HOOK_CONTROL";
        String OPEN_CONTROL = "com.vflow.hookprobe.permission.OPEN_CONTROL";
        say("本 APK 权限持有情况（结论的前提）:");
        say("  OPEN_CONTROL (normal)     = " + checkSelfPermission(OPEN_CONTROL)
                + "   ← 应为 GRANTED");
        say("  HOOK_CONTROL (signature)  = " + checkSelfPermission(HOOK_CONTROL)
                + "   ← 应为 DENIED（本 APK 异签）");
        say("");

        // 两条都发，构成对照矩阵
        Intent guarded = new Intent(ACTION_PUSH);
        guarded.setPackage(VFLOW_RECEIVER_PKG);
        guarded.putExtra("token", "tok-guarded-" + System.currentTimeMillis());
        sendBroadcast(guarded);
        say("① 已发【受保护】广播（receiver 要求 signature HOOK_CONTROL）");

        Intent open = new Intent(ACTION_OPEN);
        open.setPackage(VFLOW_RECEIVER_PKG);
        open.putExtra("token", "tok-open-" + System.currentTimeMillis());
        sendBroadcast(open);
        say("② 已发【对照】广播（receiver 只要求 normal OPEN_CONTROL）");

        // ③ 无权限接收器 —— 决定性问题：
        //    「包不可见的 App」能否把广播送达 vFlow？
        //    这条是方案可行性的地基（bindService 已因包可见性出局，广播是唯一希望）
        Intent free = new Intent(ACTION_FREE);
        free.setPackage(VFLOW_RECEIVER_PKG);
        free.putExtra("token", "tok-free-" + System.currentTimeMillis());
        sendBroadcast(free);
        say("③ 已发【无权限】广播（receiver 不要求任何权限）");

        say("");
        say("判据（看 vFlow 侧日志）：");
        say("  ③ 收到            ⇒ ★ 包不可见【不影响广播投递】⇒ 方案可行");
        say("  ③ 收不到          ⇒ ❌ 包可见性连广播也拦 ⇒ 通道不成立");
        say("  ② 收到 + ① 没收到 ⇒ signature 权限生效（鉴权有效）");
    }

    /** 能否查询到该包 —— 包可见性的直接判据。 */
    private boolean isPackageVisible(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断本 APK 与假 vFlow 是否同签 —— 用于自动标注预期结果。
     * 用 Signature 比对；两者签名不同则返回 false。
     */
    private boolean isSameSigAsVFlow() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.Signature[] mine =
                    pm.getPackageInfo(getPackageName(),
                            android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            android.content.pm.Signature[] theirs =
                    pm.getPackageInfo(VFLOW_RECEIVER_PKG,
                            android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            if (mine == null || theirs == null) return false;
            return mine[0].equals(theirs[0]);
        } catch (Throwable t) {
            // 包不可见时 getPackageInfo 也拿不到 —— 本身就是 Q1 的一个旁证
            Log.w(TAG, "isSameSigAsVFlow 失败（包可能不可见）: " + t);
            return false;
        }
    }

    // ---------- Q5: 共享文件可读性 ----------

    private void probeReadSharedFile() {
        say("");
        say("--- Q5 读 /sdcard/vFlow/ ---");

        // 先看自己有哪些存储相关权限（判断依据，不能只看"能读"）
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
            say("本 APK 声明的权限: " + java.util.Arrays.toString(pi.requestedPermissions));
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    if (p.contains("STORAGE") || p.contains("MEDIA")) {
                        say("  " + p + " = " + checkSelfPermission(p));
                    }
                }
            }
        } catch (Throwable t) { say("查权限失败: " + t); }

        java.io.File dir = new java.io.File("/sdcard/vFlow");
        say("exists=" + dir.exists() + " canRead=" + dir.canRead()
                + " canWrite=" + dir.canWrite());
        say("能 list() 只说明【目录遍历】放行；真正判据是【能否读文件内容】↓");

        // ★ 关键：读已知的具体文件（这才是共享文件方案的判据）
        //   注意别用遍历：temp/ 下有大量子项，遍历会耗尽预算且掩盖结论
        String[] candidates = {
                "/sdcard/vFlow/logs/probe-test.txt",
                "/sdcard/vFlow/logs/vflow_logging_enabled.marker",
                "/sdcard/vFlow/logs/server_process.log",
                "/sdcard/vFlow/logs/logcat_capture.log",
                "/sdcard/vFlow/exports/",
                "/sdcard/vFlow/scripts/",
                "/sdcard/vFlow/modules/",
        };
        int ok = 0, fail = 0;
        for (String p : candidates) {
            java.io.File f = new java.io.File(p);
            if (f.isDirectory()) {
                String[] l = f.list();
                say("  dir  " + p + " list=" + (l == null ? "null" : l.length + " 项"));
                continue;
            }
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[32];
                int n = in.read(buf);
                String head = new String(buf, 0, Math.max(n, 0)).replaceAll("[\\r\\n]", " ");
                say("  ✅ 读到 " + p + " (" + n + "B) 内容前 32 字节: 「" + head + "」");
                ok++;
            } catch (Throwable t) {
                say("  ❌ 读失败 " + p + " → " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                fail++;
            }
        }
        say("读文件结果: 成功 " + ok + " 个, 失败 " + fail + " 个");
        if (ok > 0) {
            say("★★ 结论：能读到【文件内容】⇒ 第三方应用可读");
            say("   ⇒ 『第三方读不到 + hook 读得到』的前提【不成立】");
            say("   ⇒ 共享文件不能用来传 token（会泄露）");
        } else if (fail > 0) {
            say("★★ 结论：能 list 目录名，但【读不到文件内容】");
            say("   ⇒ 与 §4.2.3.2 一 的推断一致（受分区存储限制）");
        }

        // 写测试
        java.io.File probe = new java.io.File(dir, "probe-write-test.txt");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(probe)) {
                out.write("x".getBytes());
            }
            say("⚠️ 竟然可写！已创建并删除 " + probe.getName());
            probe.delete();
        } catch (Throwable t) {
            say("写测试失败（预期）: " + t);
        }
    }
}
