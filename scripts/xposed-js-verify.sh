#!/usr/bin/env bash
#
# Xposed JavaScript 模块（`vflow.xposed.js` / capability `xposed_js`）真机端到端验证
#
# 对应任务：T4（`.mindfs/tasks/plan-8.md`）。
#
# ## 三条设计纪律（照既有脚本 `xposed-shortcut-probe-verify.sh`）
#
#   1. **无设备 ⇒ 打印「未验证」并 `exit 0`** —— 不是报错退出。
#      「没设备」与「验证失败」是两件完全不同的事。
#   2. **两端用同一个 `request_id` 串联** —— 跨进程调用链没有关联 id，
#      `request_id` 是唯一的串联手段（`xposed-architecture-v2.md` §10-#18）。
#   3. **判定三态** `pass / fail / unknown`；`unknown` 必须说清为什么判不了，
#      **不得当成 pass**。
#
# ## ⚠️ 日志取法（本仓库反复确认过的事实）
#
#   - **启动期**日志（模块加载 / hook 挂载）`adb logcat` **读不到** ——
#     开机洪流把环形缓冲填满。**唯一正路是 LSPosed 管理器导出的 verbose 日志**。
#     本脚本对此只打印人工指引，**不尝试用 logcat 绕开**。
#   - **运行时**日志（本 capability 的调用发生在运行期、不在开机洪流里）
#     `adb logcat` **能**读到 —— 这一点已实测（见 `cases/README.md` 的证据）。
#     hook 侧 TAG = `VFlowHook`（`HookLog` → `android.util.Log.e`）。
#
# ## ⚠️⚠️ 用例脚本必须用【表达式】形式，不能写顶层 `return`
#
# Rhino 1.9.0 的语法解析器**不接受顶层 `return`**（`msg.bad.return` =「返回的值无效」），
# 这是**解析期**错误（连第 1 行都没开始执行）。实测见 `cases/README.md`。
# ⇒ 取「最后一行表达式」作为返回值（Rhino 会把最后一个语句的值当结果）。
#
# 用法：
#   bash scripts/xposed-js-verify.sh setup     # 前置检查 + 装包 + 打印人工步骤
#   bash scripts/xposed-js-verify.sh base      # 采基线（设备 / 通道 / 能力清单 / 池状态）
#   bash scripts/xposed-js-verify.sh run       # 跑全部用例（建工作流 → 触发 → 采集）
#   bash scripts/xposed-js-verify.sh run 01    # 只跑某几个用例（可给多个编号）
#   bash scripts/xposed-js-verify.sh logs      # 只重采日志
#   bash scripts/xposed-js-verify.sh judge     # 离线判定（读已采集的日志）
#   bash scripts/xposed-js-verify.sh full      # setup + base + run + judge
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
CASES="$HERE/xposed-js-verify/cases"
OUT="$HERE/xposed-js-verify/out"
APK="$REPO_ROOT/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
WF_ID_FILE="$OUT/workflow_ids.txt"
DRIVER="$HERE/xposed-js-verify/t4_driver.py"
MODE="${1:-full}"
shift 2>/dev/null || true
CASE_FILTER="$*"

mkdir -p "$OUT"

# ── 三态判定累计 ───────────────────────────────────────────────
PASS=0; FAIL=0; UNKNOWN=0
declare -a VERDICTS=()

verdict() { # verdict <编号> <描述> <状态> <证据/原因>
  local no="$1" desc="$2" st="$3" why="${4:-}"
  case "$st" in
    pass)    PASS=$((PASS+1));    mark="✅ 通过" ;;
    fail)    FAIL=$((FAIL+1));    mark="❌ 失败" ;;
    *)       UNKNOWN=$((UNKNOWN+1)); mark="⚠️  未验证"; st="unknown" ;;
  esac
  printf '  %s  [%s] %s\n' "$mark" "$no" "$desc"
  [ -n "$why" ] && printf '        %s\n' "$why"
  VERDICTS+=("$no|$desc|$st|$why")
}

banner() { echo; echo "── $* ──"; }

# ── 步骤 0：设备探测（⚠️ 无设备 ⇒ 打印「未验证」并 exit 0）──────
# ⚠️⚠️ adb 可能对**同一台设备**开出多条 transport（USB + 无线 TLS 调试各一条）。
# 此时所有**不带 `-s`** 的命令都会以 `error: more than one device/emulator` 失败 ——
# 而失败是**静默**的：日志采集返回空文件，判定全判「未验证」，看起来像「设备没问题但功能不对」。
# ⇒ 一律固定第一条可用 serial，全脚本只用 `adb -s "$SERIAL"`。
SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')"
DEVICES="$SERIAL"
if [ -z "$DEVICES" ]; then
  echo "══ Xposed JavaScript 模块 · 真机端到端验证 ══"
  echo
  echo "未验证：无已连接设备。"
  echo "  接上设备（或 adb connect <ip>:<port>）后重跑本脚本。"
  echo "  APK 已就绪：$APK"
  echo
  echo "  ⚠️ 这是「未执行」，不是「验证失败」——两者含义完全不同。"
  exit 0
fi

echo "══ Xposed JavaScript 模块 · 真机端到端验证 ══"
echo "设备：$DEVICES"
echo "用例目录：$CASES"
# ⚠️ 必须导出：驱动里的 adb 调用同样要带 -s（见本文件头部说明）
export T4_ADB_SERIAL="$SERIAL"

# ────────────────────────────────────────────────────────────────
# setup：前置检查 + 装包 + 人工步骤
# ────────────────────────────────────────────────────────────────
do_setup() {
  banner "前置检查"

  local ok=1
  # 集成完整性：T2（hook 侧）+ T3（App 侧模块）的文件必须在
  for f in \
    "app/src/main/java/com/chaomixian/vflow/xposed/script/ScriptExecutor.kt" \
    "app/src/main/java/com/chaomixian/vflow/xposed/script/ScriptSandbox.kt" \
    "app/src/main/java/com/chaomixian/vflow/xposed/script/ScriptRequest.kt" \
    "app/src/main/java/com/chaomixian/vflow/xposed/script/JsConsole.kt" \
    "app/src/main/java/com/chaomixian/vflow/xposed/capabilities/XposedJsCapabilityHandler.kt" \
    "app/src/main/java/com/chaomixian/vflow/core/workflow/module/xposed/XposedJsModule.kt" \
    "app/src/main/java/com/chaomixian/vflow/core/workflow/module/xposed/XposedJsSupport.kt" \
    "app/src/main/java/com/chaomixian/vflow/core/execution/JsTimeout.kt" ; do
    if [ -f "$REPO_ROOT/$f" ]; then echo "  ✅ $(basename "$f")"
    else echo "  ❌ 缺 $f"; ok=0; fi
  done

  grep -q "XPOSED_JS" "$REPO_ROOT/app/src/main/java/com/chaomixian/vflow/core/xposed/CapabilityFallbacks.kt" 2>/dev/null \
    && echo "  ✅ CapabilityFallbacks 含 XPOSED_JS 注册" \
    || { echo "  ❌ CapabilityFallbacks 缺 XPOSED_JS 注册 ⇒ App 侧第一步就失败"; ok=0; }
  grep -q "XposedJsModule" "$REPO_ROOT/app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt" 2>/dev/null \
    && echo "  ✅ ModuleRegistry 注册了 XposedJsModule" \
    || { echo "  ❌ ModuleRegistry 未注册 XposedJsModule"; ok=0; }
  # ⚠️ CapabilityInvoker 的 deepConvert 深转换是「无损结果进不了选择器」缺陷的修复，
  #    不能因为集成 T3 而被覆盖掉（见 plan-8 P-1 的最高级警告）。
  grep -q "fun deepConvert" "$REPO_ROOT/app/src/main/java/com/chaomixian/vflow/core/xposed/CapabilityInvoker.kt" 2>/dev/null \
    && echo "  ✅ CapabilityInvoker.deepConvert 在（未被旧版本覆盖）" \
    || { echo "  ❌ CapabilityInvoker 缺 deepConvert ⇒ 输出会恒空"; ok=0; }

  [ "$ok" = "1" ] || { echo; echo "集成不完整，停止。"; exit 1; }

  banner "APK"
  if [ -f "$APK" ]; then
    echo "  ✅ $APK"
  else
    echo "  ❌ 找不到 APK —— 先 ./gradlew assembleRelease"; exit 1
  fi

  banner "安装"
  adb -s "$SERIAL" install -r "$APK" || { echo "安装失败"; exit 1; }
  echo "  ✅ 已安装（重装会触发热更新，无需重启手机）"

  cat <<'MANUAL'

  ── 必须【人工】做的一次性步骤（脚本做不了）──

    1. LSPosed 里启用「vFlow」→ **作用域勾选「系统框架 / system」**
       （只重启 App 不生效，system_server 必须重新注入）
    2. 重启设备（**首次**启用时才需要；此后重装 APK 走热更新）
    3. 在 App 内**开启「远程 Web 服务」**（设置 → 远程控制，默认 8080）
       —— 脚本要靠它建工作流。⚠️ 也可以由脚本用 root 直接写 prefs 打开
       （`--open-api` 见下）。

    做完整行：bash scripts/xposed-js-verify.sh base
MANUAL
}

# ────────────────────────────────────────────────────────────────
# base：采基线
# ────────────────────────────────────────────────────────────────
do_base() {
  banner "设备"
  echo "  型号   : $(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
  echo "  Android: $(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r') (API $(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "  uptime : $(adb -s "$SERIAL" shell uptime | tr -d '\r')"
  local sspid; sspid="$(adb -s "$SERIAL" shell pidof system_server | tr -d '\r')"
  echo "  system_server pid: $sspid"
  echo "$sspid" > "$OUT/system_server_pid.txt"

  banner "通道状态（重启 App 后抓 30 秒内的连接日志）"
  adb -s "$SERIAL" logcat -c
  adb -s "$SERIAL" shell am force-stop com.chaomixian.vflow
  sleep 1
  adb -s "$SERIAL" shell am start -n com.chaomixian.vflow/.ui.main.MainActivity >/dev/null 2>&1
  sleep 12
  adb -s "$SERIAL" logcat -d 2>/dev/null > "$OUT/base_logcat.txt"

  local got_bind got_reg
  got_bind="$(grep -c 'bindService 成功' "$OUT/base_logcat.txt" || true)"
  got_reg="$(grep -c 'registerCallback 成功' "$OUT/base_logcat.txt" || true)"
  if [ "${got_bind:-0}" -gt 0 ] && [ "${got_reg:-0}" -gt 0 ]; then
    echo "  ✅ hook 层已连接（bindService 成功 + registerCallback 成功）"
  else
    echo "  ⚠️ 没抓到连接日志 —— 可能模块没启用 / 没勾 system 作用域 / 时间窗错过了"
    echo "     → 重新执行本命令，或先看 LSPosed 里模块状态"
  fi

  local cap
  cap="$(grep -o '能力交换成功[^"]*' "$OUT/base_logcat.txt" | head -1)"
  if [ -n "$cap" ]; then
    echo "  ✅ $cap"
  else
    local absent
    absent="$(grep -o '能力交换[^"]*' "$OUT/base_logcat.txt" | head -1)"
    echo "  ⚠️ ${absent:-未见能力交换日志}"
  fi

  banner "能力清单（第 2 项：capabilities() 里是否有 xposed_js）"
  # hook 侧不发清单日志（清单只在应答体里）⇒ 用 App 侧 presence 结论 + 一次真实调用反证。
  # ⚠️ 这一项**不能**靠读日志判定「清单内容」——改为「发一次 xposed_js 调用，
  #    若回的是 handler_error 而不是 capability_absent，说明清单里确实有它」。
  echo "  （判定放在 run 阶段：capability_absent ⇒ 清单里没有；其它错误码 ⇒ 清单里有）"
  echo "  基线已存：$OUT/base_logcat.txt"
}

# ────────────────────────────────────────────────────────────────
# run：跑用例
# ────────────────────────────────────────────────────────────────
do_run() {
  banner "建工作流并触发"
  if [ ! -f "$DRIVER" ]; then echo "  ❌ 找不到驱动 $DRIVER"; exit 1; fi

  # 需要 API token：先确认 8080 通了
  adb -s "$SERIAL" forward tcp:8080 tcp:8080 >/dev/null 2>&1
  if ! curl -s -m 8 -o /dev/null "http://127.0.0.1:8080/"; then
    echo "  ❌ 连不上 8080 —— 先在 App 内开启「远程 Web 服务」"
    exit 1
  fi
  echo "  ✅ API 可达"

  python "$DRIVER" run ${CASE_FILTER:-} "$CASES" "$OUT" || exit 1
}

# ────────────────────────────────────────────────────────────────
# break / restore：构造第 9 项（channel_down）的真实失败
# ────────────────────────────────────────────────────────────────
#
# ⚠️ 为什么改【组件状态】而不是「在 LSPosed 里禁用模块」：
#   LSPosed 是**人工 GUI 操作**，不可复现、也无法由脚本恢复；
#   而 `pm disable HookChannelService` 是可逆、可脚本化、且**同样真实**地
#   让 hook 层 `bindService` 失败 —— 这正是 `channel_down` 的触发条件
#   （`CapabilityInvoker.kt:198` 「hook 层未连接，无法发起调用」）。
#
# ⚠️ Shell（uid 2000）**改不动**组件状态（`SecurityException: Shell cannot change
#   component state`），必须借 vFlow Core 的 root 通道。这也是脚本里唯一需要
#   Core 的地方。
CORE_HELPER="$HERE/xposed-js-verify/core_exec.py"

do_break() {
  banner "构造 channel_down（禁用 App 侧端点组件；不触碰 LSPosed）"
  [ -f "$CORE_HELPER" ] || { echo "  ❌ 缺 $CORE_HELPER"; exit 1; }
  adb -s "$SERIAL" forward tcp:19999 tcp:19999 >/dev/null 2>&1
  python "$CORE_HELPER" "pm disable --user 0 com.chaomixian.vflow/.services.HookChannelService" || exit 1
  adb -s "$SERIAL" shell am force-stop com.chaomixian.vflow
  sleep 2
  adb -s "$SERIAL" shell am start -n com.chaomixian.vflow/.ui.main.MainActivity >/dev/null 2>&1
  sleep 12
  echo "  ✅ 已断链。现在执行：bash scripts/xposed-js-verify.sh run 09"
  echo "     跑完后【务必】恢复：bash scripts/xposed-js-verify.sh restore"
}

do_restore() {
  banner "恢复（重新启用端点组件）"
  [ -f "$CORE_HELPER" ] || { echo "  ❌ 缺 $CORE_HELPER"; exit 1; }
  adb -s "$SERIAL" forward tcp:19999 tcp:19999 >/dev/null 2>&1
  python "$CORE_HELPER" "pm enable --user 0 com.chaomixian.vflow/.services.HookChannelService" || exit 1
  adb -s "$SERIAL" shell am force-stop com.chaomixian.vflow
  sleep 2
  adb -s "$SERIAL" shell am start -n com.chaomixian.vflow/.ui.main.MainActivity >/dev/null 2>&1
  sleep 15
  # ⚠️ 同样**不能**写 `adb logcat -d | grep -q` —— 见 do_scope 里的说明
  #（pipefail + grep -q 对大输入假阴性）。这里输入虽小，但同一根因，
  # 不该留一个「碰巧没踩到」的写法在前面当坏例子。
  local rl="$OUT/restore_logcat.txt"
  adb -s "$SERIAL" logcat -d 2>/dev/null > "$rl"
  if grep -q 'hook 层已连接' "$rl"; then
    echo "  ✅ 通道已恢复"
  else
    echo "  ⚠️ 未见「hook 层已连接」—— 等一会儿再看，或重启 App"
  fi
}

# ────────────────────────────────────────────────────────────────
# scope：反推 LSPosed 作用域是否已勾选 `system`（只读，无副作用）
# ────────────────────────────────────────────────────────────────
#
# ⚠️ 存在的理由：裁决 1 说「LSPosed 里 vFlow 是否勾了 `system` 我这边判不了」。
# 其实**能从 hook 侧日志反推**，不必让用户去翻 LSPosed 界面：
# hook 层的代码只有**被注入 system_server** 才会运行，所以只要看到
# `bindService 成功` / `hook 点已挂上`，就证明作用域**确实已勾选**。
do_scope() {
  banner "反推 LSPosed 作用域（判据：hook 层代码跑起来了吗）"
  adb -s "$SERIAL" logcat -c
  adb -s "$SERIAL" shell am force-stop com.chaomixian.vflow
  sleep 1
  adb -s "$SERIAL" shell am start -n com.chaomixian.vflow/.ui.main.MainActivity >/dev/null 2>&1
  sleep 12
  local log
  log="$(adb -s "$SERIAL" logcat -d 2>/dev/null)"
  # ⚠️⚠️ **必须先落到文件再用 `grep -q` 直接读文件** ——
  #    不要写 `echo "$log" | grep -q …`：`set -o pipefail` 下它**会假阴性**。
  #    机理：全量 logcat 有数 MiB，`grep -q` 一命中就退出 ⇒ `echo` 收到 SIGPIPE
  #    （退出码 141）⇒ pipefail 让**整条管道**返回非零 ⇒ `if` 判否，
  #    **明明匹配上了却走 else 分支**。实测复现（3 MiB 输入必现）。
  #    改用 `grep -c` 或落盘也不行（前者读完全部输入，慢；后者其实等价于本写法）。
  local lf="$OUT/scope_logcat.txt"
  printf '%s\n' "$log" > "$lf"
  if grep -q 'bindService 成功' "$lf"; then
    echo "  ✅ 作用域已勾选 \`system\` —— hook 层代码跑在 system_server 里了"
    echo "     （证据：$(grep -o 'bindService 成功，拿到 binder[^ ]*' "$lf" | head -1)）"
    echo "     ⇒ 装新 APK 走 hot reload 即可，**不需要**重启设备"
  elif grep -q 'VFlowHook' "$lf"; then
    echo "  ⚠️ 见到 VFlowHook 日志但没见到 bindService 成功 —— 可能还在重连，稍后重跑"
    grep -E 'VFlowHook' "$lf" | tail -3 | sed 's/^/       /'
  else
    echo "  ❌ 一条 VFlowHook 日志都没有 ⇒ 作用域**可能未勾选**，或装完没重启设备"
    echo "     ⇒ 需人工：LSPosed → 启用 vFlow → 作用域勾「系统框架」→ 重启设备"
    echo "     ⚠️ LSPosed 的 GUI 操作脚本做不了。若已勾选，先确认装完 APK 后重启过设备。"
  fi
}

# ────────────────────────────────────────────────────────────────
# consistency：与 vflow.system.js 的行为一致性核对
# ────────────────────────────────────────────────────────────────
do_consistency() {
  banner "一致性核对（vflow.system.js vs vflow.xposed.js）"
  adb -s "$SERIAL" forward tcp:8080 tcp:8080 >/dev/null 2>&1
  adb -s "$SERIAL" forward tcp:19999 tcp:19999 >/dev/null 2>&1
  python "$HERE/xposed-js-verify/consistency_check.py"
}

# ────────────────────────────────────────────────────────────────
# logs：重采（run 已采过，这里是补采入口）
# ────────────────────────────────────────────────────────────────
do_logs() {
  banner "补采日志"
  adb -s "$SERIAL" logcat -d 2>/dev/null > "$OUT/last_logcat.txt"
  echo "  行数 $(wc -l < "$OUT/last_logcat.txt") → $OUT/last_logcat.txt"
}

# ────────────────────────────────────────────────────────────────
# judge：离线判定
# ────────────────────────────────────────────────────────────────
do_judge() {
  banner "逐项判定"
  if [ ! -d "$OUT/cases" ]; then
    echo "  ⚠️ 没有采集产物（$OUT/cases）—— 先跑 run"
    verdict "1-10" "全部用例" unknown "尚无采集产物"
    return
  fi

  local c
  # ── 第 1 项（★ P0）：Rhino 能在 system_server 起来 ──
  if [ -f "$OUT/cases/01.hook.txt" ]; then
    local got_invoke got_mark
    got_invoke="$(grep -c '收到 invoke：xposed_js' "$OUT/cases/01.hook.txt" || true)"
    got_mark="$(grep -c 'VFLOW_JS_MARK 2' "$OUT/cases/01.hook.txt" || true)"
    if [ "${got_invoke:-0}" -gt 0 ] && [ "${got_mark:-0}" -gt 0 ]; then
      verdict 1 "★ Rhino 在 system_server 起来（hook 侧有 console.log 输出）" pass \
        "hook 日志：收到 invoke + VFLOW_JS_MARK 2（脚本真的执行了，不只是通道通）"
    elif [ "${got_invoke:-0}" -gt 0 ]; then
      verdict 1 "★ Rhino 在 system_server 起来" fail \
        "收到 invoke 但**没有** console.log 输出 ⇒ 脚本没跑起来或 console 未装"
    else
      verdict 1 "★ Rhino 在 system_server 起来" unknown \
        "hook 侧连 invoke 都没收到 ⇒ 通道问题，与 Rhino 无关，先查 LSPosed 状态"
    fi
  else
    verdict 1 "★ Rhino 在 system_server 起来" unknown "没有 case 01 的采集"
  fi

  # ── 第 2 项：能力可见 ──
  if [ -f "$OUT/cases/01.app.txt" ]; then
    if grep -q 'capability_absent' "$OUT/cases/01.app.txt"; then
      verdict 2 "能力可见（capabilities() 含 xposed_js）" fail \
        "App 侧回了 capability_absent ⇒ hook 层清单里没有 xposed_js"
    elif grep -qE 'code=(timeout|handler_error|payload_too_large)' "$OUT/cases/01.app.txt" \
         || [ -f "$OUT/cases/01.out.txt" ]; then
      verdict 2 "能力可见（capabilities() 含 xposed_js）" pass \
        "非 capability_absent ⇒ hook 层认识这个名字（清单里有它）"
    else
      verdict 2 "能力可见" unknown "App 侧无足够日志"
    fi
  else
    verdict 2 "能力可见" unknown "没有 case 01 的 App 侧采集"
  fi

  # ── 第 3 项：正常路径 ──
  if [ -f "$OUT/cases/01.out.txt" ]; then
    if grep -q '"result": *"2"' "$OUT/cases/01.out.txt"; then
      verdict 3 "正常路径（outputs.result == 2，经 items[0]）" pass \
        "$(head -c 120 "$OUT/cases/01.out.txt")"
    else
      verdict 3 "正常路径" fail "产物是：$(head -c 200 "$OUT/cases/01.out.txt")"
    fi
  else
    verdict 3 "正常路径" unknown "没有 case 01 的产物文件"
  fi

  # 第 3 项的补充：无返回值 ⇒ outputs 空字典（`items = [{}]`），**不是失败**。
  # 交付要求点名过这一条（「别误判为失败」）。
  if [ -f "$OUT/cases/10.app.txt" ]; then
    if grep -qE '模块执行失败|code=' "$OUT/cases/10.app.txt"; then
      echo "        ↳ 补充 10（无返回值）：❌ 被当成失败了 —— $(grep -o 'code=[a-z_]*' "$OUT/cases/10.app.txt" | head -1)"
    elif grep -q '执行完毕' "$OUT/cases/10.app.txt"; then
      echo "        ↳ 补充 10（无返回值）：✅ 工作流正常完成 ⇒ outputs 是空字典（items = [{}]），未被误判为失败"
    else
      echo "        ↳ 补充 10（无返回值）：⚠️ 未见结论日志"
    fi
  fi

  # ── 第 4 项：序列化（嵌套 + null 键保留）──
  #
  # ⚠️⚠️ 判据**不能**靠读产物整串。模块 `outputs` 是 `VDictionary`，而它的
  # `asString()` 把**每个值都包了引号**（嵌套字典 ⇒ `{"a": "{"b": "1, 2"}"}`）。
  # 拿它去 grep `[1, 2]` 永远 grep 不到（中间隔着一层引号），
  # 于是「正确实现」会被判成失败 —— 那是判据写错，不是功能错。
  #
  # 用探针做**硬断言**：把 `{{s1.outputs.a.b}}` 落盘。若取到 `1, 2`，
  # 就证明 `a` 是**可按键导航的字典**（不是 `[object Object]` 字符串）。
  local v4_ok=1 v4_why=""
  local p02 p03b p03a
  p02="$(cat "$OUT/cases/02.probe0.txt" 2>/dev/null)"
  p03b="$(cat "$OUT/cases/03.probe0.txt" 2>/dev/null)"
  # 探测 `a` 键是否存在：命中的键渲染成空串（值本身为空），
  # **没命中的**变量引用会原样保留成字面量 `{{…}}`。
  p03a="$(cat "$OUT/cases/03.probe1.txt" 2>/dev/null)"

  if [ -z "$p02" ]; then
    v4_ok=-1; v4_why="没有 case 02 的探针结果"
  elif [ "$p02" = "1, 2" ]; then
    v4_why="02 探针 {{s1.outputs.a.b}} = '$p02' ⇒ 嵌套对象可按键导航（不是 [object Object]）"
    # 顺带记录「产物整串确实不是 [object Object]」
    if grep -q '\[object Object\]' "$OUT/cases/02.out.txt" 2>/dev/null; then
      v4_ok=0; v4_why="$v4_why，**但产物里出现了 [object Object]**"
    fi
  else
    v4_ok=0; v4_why="02 探针 = '$p02'（期望 '1, 2'）"
  fi
  if [ "$v4_ok" = "1" ]; then
    if [ "$p03b" = "1" ]; then
      v4_why="$v4_why；03 兄弟键 b = '$p03b' ⇒ 字典本身完好"
      if [ "$p03a" = "{{s1.outputs.a}}" ]; then
        v4_ok=0; v4_why="$v4_why，**但 a 键不存在**（引用原样未展开）"
      else
        v4_why="$v4_why；a 键**存在**（值渲染为空 ⇒ null 键未被静默丢弃）"
      fi
    else
      v4_ok=0; v4_why="$v4_why；03 兄弟键 b = '$p03b'（期望 '1'）"
    fi
  fi
  case "$v4_ok" in
    1)  verdict 4 "返回值序列化（嵌套结构 + null 键保留）" pass "$v4_why" ;;
    0)  verdict 4 "返回值序列化" fail "$v4_why" ;;
    *)  verdict 4 "返回值序列化" unknown "$v4_why" ;;
  esac

  # ── 第 5 项：超时 ──
  local v5="unknown" v5_why="没有 case 04/05 的采集"
  if [ -f "$OUT/cases/04.hook.txt" ] || [ -f "$OUT/cases/04.app.txt" ]; then
    local t4 t5
    t4="$(grep -c '执行超时\|code=timeout' "$OUT/cases/04.app.txt" 2>/dev/null || true)"
    t5="$(grep -c '执行超时\|code=timeout' "$OUT/cases/05.app.txt" 2>/dev/null || true)"
    t4="${t4:-0}"; t5="${t5:-0}"
    if [ "$t4" -gt 0 ] && [ "$t5" -gt 0 ]; then
      v5=pass
      v5_why="04（纯死循环）与 05（try/catch 包死循环）**都**报了超时 ⇒ JS 层拦不住"
      # ⚠️ 端到端**测不出 hook 侧沙箱的中断精度**：App 侧 `withTimeout` 用的是
      #    同一个 `timeout_ms`，且必先到期（它是端到端 RTT，沙箱只覆盖脚本执行段）
      #    ⇒ 沙箱中断那一刻的响应已被判为取消。
      #    能测到的是「App 侧确实按用户给的预算超时」——记为实测值。
      local t_det
      t_det="$(grep -o 'code=timeout detail=[^）]*）' "$OUT/cases/04.app.txt" 2>/dev/null | head -1)"
      [ -n "$t_det" ] && v5_why="$v5_why；04 实测：$t_det"
    elif [ "$t4" -gt 0 ]; then
      v5=fail; v5_why="04 超时，但 05 **没**超时 ⇒ try/catch 能拦（与预期不符，是缺陷）"
    else
      v5=fail; v5_why="04 也没报超时"
    fi
  fi
  verdict 5 "超时路径（含 JS try/catch 拦不住）" "$v5" "$v5_why"

  # ── 第 6 项：阻塞不可中断（**已知限制**，只记录表现）──
  #
  # ⚠️ 这一项**不是**「验收失败项」—— `Thread.sleep` 不可中断是设计主动接受的代价
  #（`ScriptSandbox` 类注释的「已知限制」一节）。要验的是它的**最坏后果**：
  # 是「那个工作线程被占住」，**不是**「整机卡死」。
  #
  # 完整判据需要两截：
  #  a) 三次**并发**（必须用三个不同工作流 —— 同一个会被 `block_new` 重入保护挡住，
  #     请求到不了 capability 层，池永远不满）⇒ 第 3 个立刻 `handler_error`「池已满」；
  #  b) 等阻塞自然结束（sleep 3s）后**池会恢复**（tail 那一次不再被拒）。
  #
  #  ⚠️ 本项同时是「出队判过期」（hook 侧 `runOnWorker` 首行那处判定）的**回归判据**：
  #     当前池是 `SynchronousQueue`（容量 0）⇒ 并发打满时第 3 个请求在 `onInvoke`
  #     就被拒，**根本不会进队列** ⇒「排队已超预算」这一格在真机上**不可达**
  #     （与端到端单测同一根因）。故真机这边只验「行为与改动前一致」——
  #     第 3 个仍必须是 `handler_error`「工作线程池已满」，**不得**被那个判据
  #     改写成 `timeout`。判据语义本身由接缝级单测覆盖。
  local v6="unknown" v6_why="没有 case 06 的采集"
  if [ -f "$OUT/cases/06.app.txt" ]; then
    local full tail_ok
    full="$(grep -c '工作线程池已满' "$OUT/cases/06.app.txt" || true)"
    full="${full:-0}"
    tail_ok=0
    if [ -f "$OUT/cases/06.tail.app.txt" ] && ! grep -q '工作线程池已满' "$OUT/cases/06.tail.app.txt"; then
      tail_ok=1
    fi
    if [ "$full" -gt 0 ] && [ "$tail_ok" = "1" ]; then
      v6=pass
      v6_why="并发 3 次 ⇒ 第 3 个立刻 handler_error「工作线程池已满（容量 2）」；"\
"阻塞自然结束后 tail 那次**不再被拒** ⇒ 最坏后果是占住工作线程（有界、会自行恢复），不是整机卡死"
    elif [ "$full" -gt 0 ]; then
      v6=pass
      v6_why="并发 3 次 ⇒ 第 3 个 handler_error「池已满」（tail 恢复未采集到）"
    elif grep -q 'code=timeout' "$OUT/cases/06.app.txt"; then
      v6=unknown
      v6_why="只看到 App 侧超时、**没有**采到「池已满」—— 多半是三次并发没做到"\
"（工作流的重入保护会把第 2/3 次挡在 capability 层之外），不能据此判 pass"
    else
      v6=unknown; v6_why="未见预期日志"
    fi
  fi
  verdict 6 "阻塞调用不可中断（已知限制：记录表现，非验收失败项）" "$v6" "$v6_why"

  # ── 第 7 项（★ 定义性约束）：没有 vflow.* 模块树 ──
  if [ -f "$OUT/cases/07.out.txt" ]; then
    local has_undef has_ref
    has_undef="$(grep -c '"t": *"undefined"' "$OUT/cases/07.out.txt" || true)"
    has_ref="$(grep -ciE 'referenceerror|未定义|is not defined' "$OUT/cases/07.out.txt" || true)"
    if [ "${has_undef:-0}" -gt 0 ] && [ "${has_ref:-0}" -gt 0 ]; then
      verdict 7 "★ 脚本里没有 vflow.* 模块树（定义性约束）" pass \
        "typeof vflow == undefined 且访问 vflow.device 抛 ReferenceError"
    else
      verdict 7 "★ 脚本里没有 vflow.* 模块树" fail \
        "产物：$(head -c 200 "$OUT/cases/07.out.txt")—— 若 vflow 能用属**严重缺陷**（误注入了模块树）"
    fi
  else
    verdict 7 "★ 脚本里没有 vflow.* 模块树" unknown "没有 case 07 的产物"
  fi

  # ── 第 8 项：栈回溯 ──
  if [ -f "$OUT/cases/08.app.txt" ]; then
    if grep -qE '第 [0-9]+ 行' "$OUT/cases/08.app.txt"; then
      verdict 8 "栈回溯（可定位的行号，非「未知错误」）" pass \
        "$(grep -oE '第 [0-9]+ 行第 [0-9]+ 列' "$OUT/cases/08.app.txt" | head -1)"
    else
      verdict 8 "栈回溯" fail "未见行号：$(grep -o '脚本错误[^"]*' "$OUT/cases/08.app.txt" | head -1)"
    fi
  else
    verdict 8 "栈回溯" unknown "没有 case 08 的 App 侧采集"
  fi

  # ── 第 9 项：失败分类 ──
  #
  # ⚠️ **裁决 2（用户明确决定）：「不要在 LSPosed 里禁用 vFlow 模块」（破坏性操作不允许）**。
  # 本脚本**从来没有**碰过 LSPosed —— `break` 走的是「禁用 App 侧端点组件」，
  # 那是**可逆、可脚本化**的等价构造（让 hook 层 `bindService` 失败）。
  # 若那次没跑，本项按「部分验证」记：其余失败码（handler_error / timeout）已有证据，
  # `channel_down` 缺端到端触发时**必须如实说明**，不得拿静态证据充数。
  local v9="unknown" v9_why="没有 case 09 / 08 的采集"
  if [ -f "$OUT/cases/09.app.txt" ] && grep -q 'code=channel_down' "$OUT/cases/09.app.txt"; then
    v9=pass
    v9_why="App 侧 code=channel_down + 文案指向 LSPosed（弹窗截图已存）；"\
"**未触碰 LSPosed** —— 断链是禁用 App 侧 HookChannelService 组件构造的（可逆）"
  elif [ -f "$OUT/cases/08.app.txt" ] && grep -q 'code=handler_error' "$OUT/cases/08.app.txt"; then
    v9=unknown
    v9_why="已验到 handler_error（脚本运行时抛异常）的文案与分类；"\
"**channel_down 未做端到端触发**（裁决 2 不允许禁用 LSPosed 模块）。"\
"⚠️ 它的文案正确性目前**只有 T3 的 aapt2 静态证据**（九条 capability_error_* 在 release 包里），"\
"**没有端到端渲染过** —— 不得当成「已验证」"
  else
    v9=unknown; v9_why="未见任何失败码"
  fi
  verdict 9 "失败分类（handler_error 已验；channel_down 见说明）" "$v9" "$v9_why"

  # ── 第 10 项：整机稳定 ──
  local pid_before pid_now
  pid_before="$(cat "$OUT/system_server_pid.txt" 2>/dev/null)"
  pid_now="$(adb -s "$SERIAL" shell pidof system_server 2>/dev/null | tr -d '\r')"
  if [ -n "$pid_before" ] && [ -n "$pid_now" ]; then
    if [ "$pid_before" = "$pid_now" ]; then
      verdict 10 "整机稳定（system_server 未重启）" pass "pid $pid_now 全程未变"
    else
      verdict 10 "整机稳定" fail "system_server pid 变了：$pid_before → $pid_now（设备重启过？）"
    fi
  else
    verdict 10 "整机稳定" unknown "拿不到 system_server pid"
  fi
}

# ── 分派 ──────────────────────────────────────────────────────
case "$MODE" in
  setup)   do_setup ;;
  base)    do_base ;;
  run)     do_run ;;
  logs)    do_logs; do_judge ;;
  judge)   do_judge ;;
  consistency) do_consistency ;;
  scope)   do_scope ;;
  break)   do_break ;;
  restore) do_restore ;;
  full)    do_setup; do_base; do_run; do_judge ;;
  *)       echo "未知模式：$MODE（可用：setup / base / run / logs / judge / scope / consistency / break / restore / full）" >&2; exit 2 ;;
esac

if [ "$MODE" = "run" ] || [ "$MODE" = "full" ] || [ "$MODE" = "judge" ]; then
  echo
  echo "  ── 汇总：通过 $PASS / 失败 $FAIL / 未验证 $UNKNOWN ──"
  printf '%s\n' "${VERDICTS[@]}" > "$OUT/verdicts.txt" 2>/dev/null || true
fi

echo
echo "══ 结束 ══"
