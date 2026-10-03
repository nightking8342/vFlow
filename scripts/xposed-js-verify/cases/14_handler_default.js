// case 14：**阴性对照** —— `default` 档里 `new Handler()` **应当抛**。
//
// 与 case 13 构成一对：只有阳性会通过，就能证明「ui 档给 Looper」不是
// 「任何线程都能 new Handler」的平凡结论。
//
// ⚠️ 本用例**不写 `thread_mode`** ⇒ 走 default 档（那正是要验的阴性面）。
// 判据：`ok == false` 且 `err` 含 `Looper`（消息里必然提到 Looper.prepare）。
//
// ⚠️⚠️ **必须 `importClass(android.os.Handler)`** —— 同 case 13，
// Handler 在 `android.os` 而非 `java.lang`；用错包名会得到
// 「JavaPackage ... 不是函数」而**不是**「没有 Looper」⇒ 阴性面**假阳性**。
var r = {};
try {
    importClass(android.os.Handler);
    var h = new Handler();
    r.ok = true;
    r.thread = String(java.lang.Thread.currentThread().getName());
} catch (e) {
    r.ok = false;
    r.err = String(e);
}
r;
