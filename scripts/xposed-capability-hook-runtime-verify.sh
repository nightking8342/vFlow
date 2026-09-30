#!/usr/bin/env bash
# ③ hook 侧执行运行时的**真机端到端验证脚本**（诊断 capability 的四条路径）。
#
# ⚠️⚠️ 前置：本脚本需要 **App 侧有「发起一次 ③ 调用」的运行时**，
# 那是 T1（`feature/xposed-capability-app-runtime`）的交付物。
# 若 `CapabilityInvoker` 不在工作区，本脚本**走不到 hook 层** ——
# 见下方第 0 步的判据。
#
# ⚠️ **日志取法（唯一可行的两条）**：
#   - hook 侧：`adb logcat` **读不到启动期日志**（开机洪流把 2 MiB 环形缓冲填满，
#     `xposed-channel-design.md` §4.1 有实测量化）⇒ 必须用
#     **LSPosed 管理器导出的 verbose 日志**（持久化、不丢）。
#   - App 侧：首页「最近日志」（`DebugLogger` 的内存缓冲出口）或 `adb logcat`。
#
# ⚠️ **两端用同一个 `request_id` 比对** —— 跨进程调用链**无关联 id**
#   （架构文档 §10-#18），`request_id` 是唯一的串联手段。

set -uo pipefail

TASK2_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK="$TASK2_DIR/app/build/outputs/apk/release/app-arm64-v8a-release.apk"

echo "═══ 第 0 步 · 前置检查（不能省）═══"

# 0-a) T2（本任务）的代码是否在工作区
if [ -d "$TASK2_DIR/app/src/main/java/com/chaomixian/vflow/xposed/capabilities" ]; then
  echo "✅ T2（hook 侧执行运行时）在"
else
  echo "❌ T2 不在，停止"; exit 1
fi

# 0-b) ⚠️ CapabilityFallbacks.kt 是 T2 与 T1 的 add/add 冲突点
#      ⇒ 必须含【两边】的东西：T2 的注册行 + T1 的 planOf
if grep -q "DIAGNOSTIC" "$TASK2_DIR/app/src/main/java/com/chaomixian/vflow/core/xposed/CapabilityFallbacks.kt" 2>/dev/null; then
  echo "✅ T2 的 diagnostic 注册行在"
else
  echo "❌ 缺注册行 ⇒ 下面每一步都会失败在 App 侧（hook 层收不到请求）"; exit 1
fi
if grep -q "fun planOf" "$TASK2_DIR/app/src/main/java/com/chaomixian/vflow/core/xposed/CapabilityFallbacks.kt" 2>/dev/null; then
  echo "✅ T1 的 planOf 在"
else
  echo "⚠️  缺 planOf —— 若 T1 已合并，CapabilityInvokerTest 会编译不过"
fi

# 0-c) T1 的调用入口是否存在（否则没有发起方 ⇒ 端到端验证做不了）
if [ -f "$TASK2_DIR/app/src/main/java/com/chaomixian/vflow/core/xposed/CapabilityInvoker.kt" ]; then
  echo "✅ T1 的调用入口在 ⇒ 可以做端到端验证"
  READY=1
else
  echo "❌ T1 的 CapabilityInvoker 不在 ⇒ **无发起方**，hook 层收不到任何请求"
  echo "   ⇒ 四路径验证【不可能】做。本任务的真机验证标注「未验证」。"
  READY=0
fi

if [ "${READY}" = "0" ]; then
  echo
  echo "═══ 结论：本任务的真机端到端验证未执行（前置 T1 未就绪）═══"
  echo "下面这张表是「T1 就绪后立即执行」的脚本，判据已写全。"
  exit 0
fi

echo
echo "═══ 装包 ═══"
[ -f "$APK" ] || { echo "❌ 找不到 APK：$APK（先 ./gradlew assembleRelease）"; exit 1; }
adb install -r "$APK" || exit 1
echo "✅ 已装。⚠️ LSPosed 里确认：模块已勾选、scope 含「系统」"
echo "   重装 APK 会触发热更新（autoHotReload），无需重启手机"

cat <<'TABLE'

═══ 六步验证表（每一步：操作 → 两端日志判据）═══

两端日志按同一个 request_id 比对。hook 侧 TAG = VFlowHook。

┌───┬──────────────────────────────────┬──────────────────────────────────────────────┬────────────────────────────────────────────┐
│ # │ 操作                             │ hook 侧日志（LSPosed verbose 导出）          │ App 侧结果                                 │
├───┼──────────────────────────────────┼──────────────────────────────────────────────┼────────────────────────────────────────────┤
│ 1 │ diagnostic, params={"mode":"ok"} │ 收到 invoke：diagnostic                      │ ok=true                                    │
│   │                                  │ → 执行完成（elapsedMs 很小）                 │ result.items[0].mode == "ok"               │
├───┼──────────────────────────────────┼──────────────────────────────────────────────┼────────────────────────────────────────────┤
│ 2 │ diagnostic, mode=slow,           │ 收到 invoke：diagnostic                      │ Failed, error.code == "timeout"            │
│   │ timeout_ms=1000                  │ → 执行超时（elapsedMs≈1250 > budget=1000）   │                                            │
│   │                                  │ → 回 timeout                                 │                                            │
├───┼──────────────────────────────────┼──────────────────────────────────────────────┼────────────────────────────────────────────┤
│ 3 │ diagnostic, mode=throw           │ 收到 invoke：diagnostic                      │ Failed, error.code == "handler_error"      │
│   │                                  │ → handler 抛异常：IllegalStateException:     │ detail 含 "故意抛出"                       │
│   │                                  │    diagnostic: 故意抛出的异常                │                                            │
│   │                                  │ → 已转成 handler_error 响应                  │                                            │
├───┼──────────────────────────────────┼──────────────────────────────────────────────┼────────────────────────────────────────────┤
│ 4 │ diagnostic, mode=huge            │ 收到 invoke：diagnostic                      │ Success                                    │
│   │                                  │ → 结果超预算，已截断：收下 <N>/2000 项，     │ truncated == true                          │
│   │                                  │   nextCursor=<N>                             │ nextCursor != null                         │
│   │                                  │                                              │ ⚠️ 只验第一页 + 游标，**不验自动翻页**      │
├───┼──────────────────────────────────┼──────────────────────────────────────────────┼────────────────────────────────────────────┤
│ 5 │ 并发 3 个 mode=slow, timeout=8000│ 第 1/2 个：执行中                            │ 第 3 个**立刻**失败（不等 8 秒）           │
│   │ （⚠️ 三个必须**同时在途**）      │ 第 3 个：工作线程池已满（容量 2 个并发），   │ error.code == "handler_error"              │
│   │                                  │   回 handler_error                           │ detail 含「工作线程池已满」                │
├───┼──────────────────────────────────┼──────────────────────────────────────────────┼────────────────────────────────────────────┤
│ 6 │ 调一个不存在的名（如 diagnostic2）│ 收到 invoke：diagnostic2                     │ Failed, error.code == "capability_absent"  │
│   │                                  │ → 未知名，回 capability_absent（已注册：     │                                            │
│   │                                  │   [diagnostic]）                             │                                            │
└───┴──────────────────────────────────┴──────────────────────────────────────────────┴────────────────────────────────────────────┘

⚠️ 第 4 步的两个数必须一致：hook 侧「收下 N/2000」的 N 与 App 侧 nextCursor
   应是**同一个数**（框架算 nextCursor = startIndex + 收下的元素数）。
   不一致说明分页记账与上报脱节。

⚠️ 第 5 步的时序要求：三个 slow 必须**真正同时在途**（timeout_ms 给足 8000ms），
   否则第 1/2 个可能已跑完、池空出来，第 3 个就成功了 ——
   那**不是** bug，是验证方法错。

⚠️ 额外可验（本任务的验收 #7）：让 hook 层换代（重装 APK 触发 hot reload）后
   立刻发一次调用 ⇒ 应回 detail 含「已停止」、**不含**「工作线程池已满」。
TABLE
