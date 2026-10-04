# 超级岛通知体积探针

## 它回答什么

2026-09-29 的崩溃日志（`.mindfs/upload/2026-10-04/vflow-crash-20260929-114632.txt`）：

```
java.lang.RuntimeException: android.os.TransactionTooLargeException: data parcel size 1035216 bytes
    at android.app.NotificationManager.notifyAsUser(NotificationManager.java:958)
```

发生在**第 84 次**（日志里 `IslandDispatcher: 已附加岛参数` 共 84 行）`notify` 上。

本探针要回答的**唯一**问题是：

> 单次通知的体积，会不会随 `notify` 次数增长？

## 为什么不能用 vFlow 自己的日志回答

同一份日志里，`IslandDispatcher` 每次的字段长度是这样的：

```
state=RUNNING titleLength=17 stepNameLength=4 statusLength=16 ...
```

把 84 次全部加起来 —— `title` 1428 + `status` 771 + `stepName` 410 = **2609 字符**
≈ 5.1 KiB（按 UTF-16 上界算）。**占 1 MB 的 0.5%。**

⇒ **字节不在文本里。** 日志能证明「不是文本」，但证明不了「是什么」。
只能靠一个同形复现来定位。

## 为什么「累积」这个说法本身就要被质疑

`NotificationManager.notify(id, n)` 对**同一个 ID** 是**替换**语义：通知栏里始终
只有一条，历史的那份被丢弃。从「通知栏内容」的角度看**不存在累积**。

但每个调用方持有的是**同一组 `RemoteViews` 实例**（`IslandNotificationDispatcher`
按 `workflowId` 缓存，注释里写明了这是刻意的性能设计）。`RemoteViews` 内部维护一份
**动作列表** —— `setTextViewText` / `setImageViewBitmap` / `setViewVisibility`
每次调用都往里追加一条。若那个列表只增不减，那么：

- 通知栏里仍然只有一条（替换语义成立）；
- 但**每次 notify 的事务体积在变大** —— 第 84 次比第 1 次大得多。

**这两个说法不矛盾**，而这正是本探针要区分的形态。

## 场景设计

五个场景，每次只动**一个变量**：

| 场景 | 复用 RV | 写位图 | 附 `miui.focus.pics` | 用途 |
|---|---|---|---|---|
| `A_reuse_rv_with_icon` | ✅ | ✅ | ✅ | **完全照搬 vFlow 的用法** |
| `B_reuse_rv_no_icon` | ✅ | ❌ | ✅ | 与 A 的差 = 写位图那一项的分量 |
| `C_reuse_rv_no_pics` | ✅ | ✅ | ❌ | 与 A 的差 = 每次新建 Icon 塞 extras 的分量 |
| `D_fresh_rv_with_icon` | ❌ | ✅ | ✅ | 对照组：问题在「复用」还是「RemoteViews 本身」 |
| `E_baseline_no_rv` | — | — | — | 基线：证明通知本体不涨 |

场景内每次只改一个字段的**值**（`status` 文本），模拟 vFlow 里模块自报进度 ——
即绝大多数更新的内容几乎没变。

## 判据表

每个场景收尾打一行 `RESULT`，含**整条采样曲线**（每 10% 一个点）：

```
RESULT A_reuse_rv_with_icon first=230000 last=2045300 growth=+1815300 per_round=181530.0
       samples=11 curve=230000,431700,633400,...,2045300,2045300
```

`verify.sh` 按曲线**形态**判定（不是只看首末 —— 那分不出「递增」与「早涨后平」，
而两者的结论完全相反）：

| 形态 | 判据 | 结论 |
|---|---|---|
| `linear` | 尾部仍在涨 | **复现崩溃**。修法：不要复用同一组 RemoteViews |
| `plateau` | 前 25% 涨、后 25% 平 | 单次体积有上界 ⇒ 不会随次数无限增长；但**若平台值本身接近 1 MiB 仍会超限** |
| `flat` | 全程几乎不动 | **「累积」假设被证伪** —— 崩溃另有原因，改查 `B/C/D` 的分组差值 |

⚠️ 线性分支还会把外推值（第 84 次）与崩溃真值 **1,033,192 字节**做**计算比较**，
并如实报出「同量级」还是「差 N 倍」。**不做「看着差不多」的目测判断**。

## 用法

```bash
bash scripts/probe/island-probe/build.sh          # 只构建
bash scripts/probe/island-probe/verify.sh         # 全流程（无设备则打印「未验证」并 exit 0）
bash scripts/probe/island-probe/verify.sh logs    # 只重抓日志（探针已跑过）
```

## 三条安全约定

1. **独立包名 `com.vflow.islandprobe` + debug 签名** —— 与已装 vFlow 完全无关，
   因此**可以在日常使用的设备上跑**。
   ⚠️ 不要为图方便改成用 `vFlow.jks` 签名 —— 同签会让它与正式版争包名。
2. **自建通知渠道 + 自建通知 ID（990001 起），结束时全部 cancel、渠道也删掉**。
3. **不碰 vFlow 的任何代码与数据**，不读它的 `SharedPreferences`。

## 已踩过并写进代码的坑

- ⚠️ **必须先 `aapt2 link --java` 生成 `R.java` 再 `javac`** —— 本探针用
  `R.layout` / `R.id`，顺序反了会得到一堆「找不到符号: 类 R」
  （既有探针不用 `R.`，故没踩过这个）。
- ⚠️ **必须先 grant `POST_NOTIFICATIONS`**。Android 13+ 默认「询问」，
  不授权时 `notify` 静默失败 ⇒ 打出一堆 0 字节 ⇒ **看起来像「没有增长」**。
  那是假阴性，比跑不起来更危险。
- ⚠️ Windows 下 Python 的 stdout 默认 **GBK**，而判定文案是 UTF-8
  ⇒ 表头乱码、`✓` 直接抛 `UnicodeEncodeError`。已在脚本里
  `sys.stdout.reconfigure(encoding='utf-8')`（与 `xposed-js-verify.sh` 记的
  「subprocess 默认 GBK 解码」是同一根因，位置从「读设备」挪到了「写输出」）。
- ⚠️ **`adb` 固定第一条 serial** —— 同设备可能出现多条 transport（USB + 无线 TLS），
  不带 `-s` 会以 `more than one device/emulator` **静默**失败。
- ⚠️ 曲线只留 11 个采样点：200 个数塞进一行日志会超 logcat 单行上限。
