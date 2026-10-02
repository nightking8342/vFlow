// case 13：**阳性对照** —— `ui` 档里应当能 `new java.lang.Handler()`。
//
// 现状（换成三档之前）这一步**必然抛**：
//   Can't create handler inside thread ... that has not called Looper.prepare()
// ⇒ 它不抛，就是「`ui` 档真的给了 Looper」的直接证据。
//
// ⚠️ 用 try/catch 把结果**写成数据**而不是让它抛 —— 抛出去会让工作流失败，
// 而失败的产物在 `out/` 里看不出版本差异（两种情形都是「失败」）。
var r = {};
try {
    var h = new java.lang.Handler();
    r.ok = true;
    r.looper = String(java.lang.Thread.currentThread().getName());
} catch (e) {
    r.ok = false;
    r.err = String(e);
}
r;
