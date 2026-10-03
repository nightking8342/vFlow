// case 13：**阳性对照** —— `ui` 档里应当能 `new Handler()`。
//
// 现状（换成三档之前）这一步**必然抛**：
//   Can't create handler inside thread ... that has not called Looper.prepare()
// ⇒ 它不抛，就是「`ui` 档真的给了 Looper」的直接证据。
//
// ⚠️⚠️ **必须 `importClass(android.os.Handler)`** ——
// Handler 在 **`android.os`** 而非 `java.lang`（`java.lang.Handler` **不存在**）。
// 直接写 `new java.lang.Handler()` 会让 Rhino 把它当**包名**解析，
// 报 `TypeError: [JavaPackage java.lang.Handler] 不是函数，它是 object` ——
// **那个报错与「线程没有 Looper」无关**，会让本用例**假阴性**（2026-10-03 实际踩到）。
// 而 `importClass` 正是 hook 侧 `ImporterTopLevel` 提供的（`ScriptExecutor.kt:205`）。
//
// ⚠️ 用 try/catch 把结果**写成数据**而不是让它抛 —— 抛出去会让工作流失败，
// 而失败的产物在 `out/` 里看不出版本差异（两种情形都是「失败」）。
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
