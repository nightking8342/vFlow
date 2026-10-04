// ⚠️ 包名必须与 AndroidManifest 的 `package=` **逐字一致** ——
// aapt2 link 生成的 R.java 落在同名的包目录下，包名不一致就得额外 import，
// 而探针这类一次性工程不值得为此增加一层间接。
package com.vflow.islandprobe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 超级岛通知体积探针 —— 定位「工作流跑到一半、通知把自己撑爆」的真因。
 *
 * ## 要回答的问题
 *
 * 崩溃日志（2026-09-29，小米 2308CPXD0C / Android 17）：
 * 第 84 次 `notify` 时 parcel 达 **1,033,192 字节**，抛 `TransactionTooLargeException`。
 * 而同一份日志里 `IslandDispatcher` 每次的字段长度（title=17 / status ≤ 30）
 * 全部加起来**只有 5.1 KiB** ⇒ 字节不在文本里，只可能在**被反复写入的对象**里。
 *
 * 本探针**不跑 vFlow 的代码**，只复现同一套 RemoteViews 用法，把候选逐个测出来：
 *
 * | 问 | 假设 | 判据 |
 * |---|---|---|
 * | Q1 | 复用同一组 RemoteViews、反复 `update()` ⇒ 内部 action 列表**只增不减** | 第 1 次 vs 第 200 次的 parcel 字节 |
 * | Q2 | 那个增长与「写整张图标位图」是**乘性**关系（每多 1 个 action 多 1 张图） | 有图 / 无图两组的斜率比 |
 * | Q3 | `IslandIcons.buildPics` 每次新建 Icon 是否额外放大单次体积 | 有 pics / 无 pics 的差值 |
 *
 * ## 关键判据是「斜率」，不是「绝对值」
 *
 * 1 MiB 是 binder 单次事务的**总值**上界，通知 extras 只是其中一部分
 * ⇒ 单次 notify 往往远不到 1 MiB。崩溃是**累积到第 84 次**才发生的，
 * 所以唯一能区分「每次是常数开销」与「每次递增」的，是**多次 notify 的字节序列**。
 * 因此本探针把每次的 parcel 字节都记下来，让斜率自己说话。
 *
 * ## ⚠️ 已知的平台噪声（读结果前必读）
 *
 * **`notify()` 对同一 ID 是「替换」而非「新建」** ⇒ 通知栏里始终只有一条，
 * 但**每次替换在 binder 上仍是一次完整事务**。所以：
 *
 * - 若看到字节随次数增长 ⇒ 那是**单次事务**在变大，不是历史累积。
 * - 反之若每次都一样大 ⇒ 累积解释不成立，得往别处找（系统侧合并、图标编码等）。
 *
 * 这也正是本探针最有价值的一条判据：**它能直接证伪「累积」这个假设**。
 *
 * ## 用法
 *
 * ```
 * adb shell am start -n com.vflow.islandprobe/.MainActivity
 * adb logcat -s IslandProbe
 * ```
 *
 * 跑完自动 `finish()`；每场景收尾打一行 `RESULT ...` 便于 grep 汇总。
 * ⚠️ **全程不碰 vFlow 的代码与数据**：自建渠道、自建通知 ID（990001 起）、结束时全部 cancel。
 */
public class MainActivity extends Activity {

    private static final String TAG = "IslandProbe";
    private static final String CHANNEL_ID = "island_probe";
    private static final int BASE_ID = 990001;

    /** 每个场景重复多少次。⚠️ 84 是崩溃时的真实次数，跑 200 留足余量看趋势。 */
    private static final int ROUNDS = 200;

    /** 曲线采样点数（每 10% 一个）。⚠️ 不逐次留——200 个数塞进一行日志会超 logcat 单行上限。 */
    private static final int SAMPLE_STEPS = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "════════ IslandProbe 开始 uid=" + android.os.Process.myUid()
                + " density=" + getResources().getDisplayMetrics().density
                + " iconPx=" + iconPx() + " ════════");

        try {
            createChannel();
            Bitmap icon = makeIcon();

            // A：完全照搬 vFlow 的用法 —— 复用一组 RV + 每次写整张图标
            scenario(Id.A, "A_reuse_rv_with_icon", true, icon, true, true);

            // B：复用 RV，但**不写图标**。
            //    与 A 的斜率差 = 「每个累积的 action 是否各带一份位图」。
            scenario(Id.B, "B_reuse_rv_no_icon", false, icon, true, true);

            // C：复用 RV + 写图标，但**不带 miui.focus.pics**。
            //    与 A 的差 = 「每次新建 Icon 并塞进 extras」的独立开销。
            scenario(Id.C, "C_reuse_rv_no_pics", true, icon, true, false);

            // D：每次新建 RemoteViews（vFlow 注释里说的「错误用法」）。
            //    对照组：若 D 不涨而 A 涨，则问题确定在「复用 + 反复写」。
            scenario(Id.D, "D_fresh_rv_with_icon", true, icon, false, true);

            // E：基线 —— 普通通知，无 RemoteViews、无岛参数。
            baselineScenario();
        } catch (Throwable t) {
            Log.e(TAG, "探针异常", t);
        } finally {
            cleanup();
            Log.i(TAG, "════════ IslandProbe 结束 ════════");
            finish();
        }
    }

    // ================================================================
    // 场景
    // ================================================================

    /**
     * 按 [withIcon] 决定是否写位图、按 [reuseRv] 决定复用还是每次新建、
     * 按 [withPics] 决定是否附 `miui.focus.pics`。
     *
     * 每次只改**一个字段的值**（status 文本），模拟 vFlow 里模块自报进度 ——
     * 即绝大多数更新的内容几乎没变；若这仍导致增长，那就是裸动作数在累积。
     */
    private void scenario(int notifId, String label, boolean withIcon, Bitmap icon,
                          boolean reuseRv, boolean withPics) {

        Log.i(TAG, "──────── 场景 " + label + " ────────");

        RemoteViews rv = reuseRv ? newRemoteViews() : null;
        final int sampleStride = Math.max(1, ROUNDS / SAMPLE_STEPS);

        // 只保留「每 10% 一个采样点」的字节数 —— 200 次全留会撑爆日志，
        // 而斜率靠这些点已经足够看清。最后一次无论如何都留。
        int[] series = new int[SAMPLE_STEPS + 1];
        int seriesN = 0;
        int firstBytes = -1, lastBytes = -1;

        for (int round = 1; round <= ROUNDS; round++) {
            if (!reuseRv) rv = newRemoteViews();

            // 与 vFlow `IslandViews.applyToCard` 同形的写入序列
            rv.setTextViewText(R.id.probe_title, "探测工作流名称占位");
            rv.setTextViewText(R.id.probe_chip, "执行中");
            if (withIcon) rv.setImageViewBitmap(R.id.probe_icon, icon);
            rv.setTextViewText(R.id.probe_progress, "3/8");
            rv.setTextViewText(R.id.probe_step, "延迟");
            rv.setTextViewText(R.id.probe_status, "正在延迟 2500ms #" + (round % 4));
            rv.setTextViewText(R.id.probe_timer_label, "已运行");
            rv.setViewVisibility(R.id.probe_status, View.VISIBLE);

            Notification n = buildNotification(rv, round, withPics, icon);
            int bytes = parcelBytes(n);

            if (round == 1) firstBytes = bytes;
            lastBytes = bytes;

            boolean sample = (round % sampleStride == 0) || (round == 1) || (round == ROUNDS);
            if (sample && seriesN < series.length) series[seriesN++] = bytes;
            if (sample) {
                Log.i(TAG, String.format("  %s #%-4d parcel=%8d bytes (%6.1f KiB) actions=%d",
                        label, round, bytes, bytes / 1024.0, actionCount(rv)));
            }

            notify(notifId, n);
        }

        // ⚠️ 把**整条采样曲线**打进一行 RESULTS，供 verify 脚本判「递增 vs 达平台」。
        //    只看 first/last 无法区分这两种形态 —— 而它们的修法完全不同
        //    （递增 ⇒ 别复用 RemoteViews；达平台 ⇒ 是「每写一次位图就多存一份」的常数开销）。
        StringBuilder curve = new StringBuilder();
        for (int k = 0; k < seriesN; k++) {
            if (k > 0) curve.append(',');
            curve.append(series[k]);
        }
        Log.i(TAG, String.format(
                "RESULT %s first=%d last=%d growth=%+d per_round=%.1f samples=%d curve=%s",
                label, firstBytes, lastBytes, lastBytes - firstBytes,
                (lastBytes - firstBytes) / (double) (ROUNDS - 1),
                seriesN, curve));
    }

    /** 基线：普通通知、无 RemoteViews、无岛参数。证明「通知本体」不随次数增长。 */
    private void baselineScenario() {
        Log.i(TAG, "──────── 场景 E_baseline_no_rv ────────");
        int first = -1, last = -1;
        for (int i = 1; i <= ROUNDS; i++) {
            Notification n = buildNotification(null, i, false, null);
            int bytes = parcelBytes(n);
            if (i == 1) {
                first = bytes;
                Log.i(TAG, "  基线 extras keys=" + n.extras.keySet());
            }
            last = bytes;
            if (i == 1 || i % 50 == 0) {
                Log.i(TAG, String.format("  E_baseline #%-4d parcel=%8d bytes", i, bytes));
            }
            notify(BASE_ID, n);
        }
        Log.i(TAG, String.format("RESULT E_baseline_no_rv first=%d last=%d growth=%+d",
                first, last, last - first));
    }

    // ================================================================
    // 与 vFlow 同形的通知装配
    // ================================================================

    private RemoteViews newRemoteViews() {
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.probe_island);
        rv.setImageViewResource(R.id.probe_icon, android.R.drawable.ic_menu_help);
        return rv;
    }

    private Notification buildNotification(RemoteViews rv, int round, boolean withPics, Bitmap icon) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("探测通知")
                .setContentText("第 " + round + " 次")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(
                        this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // ⚠️ 不给 Builder 设自定义视图 —— 岛参数走的是 extras，崩溃那次也是这条路径
        //    （栈是 `INotificationManager.enqueueNotificationWithTag`）。

        Notification n = b.build();
        if (rv != null) {
            // ⚠️ 与 `IslandNotificationDispatcher.attachIslandParams` 逐字同形：
            //    `Notification.extras` 是 **public 字段**，`build()` 之后仍可变 ——
            //    三条 RemoteViews 都塞进 extras，**不走** setCustomContentView。
            n.extras.putParcelable("miui.focus.rv", rv);
            n.extras.putParcelable("miui.focus.rvNight", rv);
            n.extras.putParcelable("miui.focus.rv.island.expand", rv);
            n.extras.putString("miui.focus.param.custom", "{\"probe\":true}");
            if (withPics && icon != null) {
                Bundle pics = new Bundle();
                pics.putParcelable("miui.focus.pic_vflow", Icon.createWithBitmap(icon));
                n.extras.putBundle("miui.focus.pics", pics);
            }
        }
        return n;
    }

    private void notify(int id, Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            nm.notify(id, n);
        } catch (Throwable t) {
            Log.e(TAG, "notify 失败 id=" + id + "  size=" + parcelBytes(n) + " —— " + t);
            throw t;
        }
    }

    // ================================================================
    // 度量
    // ================================================================

    /**
     * 一次通知的 parcel 字节数。
     *
     * ⚠️ 用 `writeParcelable` + `dataSize()`，**不用** `Parcel.marshall()`：
     * 后者要求写完整 head、且对含 file descriptor 的对象抛异常
     * （Icon 内部可能带 ParcelFileDescriptor）。`dataSize()` 足够回答
     * 「这次发了多大」，且不抛。
     */
    private static int parcelBytes(Notification n) {
        Parcel p = Parcel.obtain();
        try {
            p.writeParcelable(n, 0);
            return p.dataSize();
        } catch (Throwable t) {
            Log.e(TAG, "parcelBytes 失败", t);
            return -1;
        } finally {
            p.recycle();
        }
    }

    /**
     * 反射读 RemoteViews 内部的 action 列表大小。
     *
     * ⚠️ 主判据是 parcel 字节，action 数只是**解释项** —— 拿不到时返回 -1 而不是抛，
     * 字段名在 AOSP 各版本或有出入。
     */
    private static int actionCount(RemoteViews rv) {
        if (rv == null) return -1;
        try {
            Field f = RemoteViews.class.getDeclaredField("mActions");
            f.setAccessible(true);
            Object v = f.get(rv);
            if (v instanceof java.util.Collection) return ((java.util.Collection<?>) v).size();
            Method size = v.getClass().getMethod("size");
            return (Integer) size.invoke(v);
        } catch (Throwable t) {
            return -1;
        }
    }

    private int iconPx() {
        // 与 vFlow `IslandIcons.roundedAppBitmap` 口径一致：max(48, 48 * density)
        return Math.max(48, (int) (48 * getResources().getDisplayMetrics().density));
    }

    /** 裁圆的应用图标位图 —— 与 vFlow `IslandIcons.roundedAppBitmap` 同形。 */
    private Bitmap makeIcon() {
        int size = iconPx();
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path path = new Path();
        path.addOval(0f, 0f, size, size, Path.Direction.CW);
        c.clipPath(path);
        Drawable d;
        try {
            d = getPackageManager().getApplicationIcon(getPackageName());
        } catch (Throwable t) {
            d = getResources().getDrawable(android.R.drawable.sym_def_app_icon, null);
        }
        d.setBounds(0, 0, size, size);
        d.draw(c);
        Log.i(TAG, String.format("图标 iconPx=%d rawBitmap=%.1f KiB（此值 × 每次写入次数 即 Q2 的量级）",
                size, bmp.getByteCount() / 1024.0));
        return bmp;
    }

    // ================================================================
    // 基础设施
    // ================================================================

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "岛探针", NotificationManager.IMPORTANCE_LOW));
    }

    private void cleanup() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        for (int id = BASE_ID; id < BASE_ID + 16; id++) {
            try { nm.cancel(id); } catch (Throwable ignored) { }
        }
        // 顺带删掉渠道，避免在用户的设置页里留一条垃圾
        try { nm.deleteNotificationChannel(CHANNEL_ID); } catch (Throwable ignored) { }
    }

    /** 各场景的固定通知 ID。⚠️ 不能共用——共用会让系统做「替换」优化，字节数失去可比性。 */
    private static final class Id {
        static final int A = BASE_ID + 0;
        static final int B = BASE_ID + 1;
        static final int C = BASE_ID + 2;
        static final int D = BASE_ID + 3;
    }
}
