// case 14：**阴性对照** —— `default` 档里 `new java.lang.Handler()` **应当抛**。
//
// 与 case 13 构成一对：只有阳性会通过，就能证明「ui 档给 Looper」不是
// 「任何线程都能 new Handler」的平凡结论。
//
// ⚠️ 本用例**不写 `thread_mode`** ⇒ 走 default 档（那正是要验的阴性面）。
// 判据：`ok == false` 且 `err` 含 `Looper`（消息里必然提到 Looper.prepare）。
var r = {};
try {
    var h = new java.lang.Handler();
    r.ok = true;
} catch (e) {
    r.ok = false;
    r.err = String(e);
}
r;
