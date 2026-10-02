// case 12：三档落**不同线程**的取证用例。
//
// 脚本本身不重要（只返回线程名），关键是让 hook 日志打出
// `执行：… 档=<mode> 线程=<Thread.currentThread().name>`——
// 那是「三档真的有区别」唯一可从 logcat 观测的证据。
//
// 期望（`xposed-js-verify.sh judge` 断言）：
//   default → 线程名含 `DefaultDispatcher-worker`
//   io      → 线程名含 `DefaultDispatcher-worker`（与 default 共用线程池，但档位名不同）
//   ui      → 线程名含 `VFlowHook-ui`
({thread: java.lang.Thread.currentThread().getName()})
