#!/usr/bin/env bash
#
# 快捷方式探针 · 一键验证脚本（全 adb，零代码）
#
# 用途：装探针 → 采 dumpsys 基线 → 抓探针日志 → 按 plan-4.md §5 的五项判据**自动判定**。
#
# ⚠️ 两个设计要点：
#   1. **无设备时打印「未验证」并 exit 0**，不是报错退出 ——
#      否则「没设备」看起来像「验证失败」，而它们是完全不同的两件事。
#   2. **探针本身不跑 dumpsys**（它是 system_server 内的只读代码，不执行 shell），
#      计数对比在这里做 —— 本脚本两边都能拿到。
#
# 用法：
#   bash scripts/xposed-shortcut-probe-verify.sh            # 全流程（含安装，需人工重启）
#   bash scripts/xposed-shortcut-probe-verify.sh logs        # 只抓日志（设备已重启过）
#   bash scripts/xposed-shortcut-probe-verify.sh base        # 只采 dumpsys 基线
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
OUT="$HERE/probe/xposed-channel/out"
APK="$OUT/shortcutprobe.apk"
LOG="/tmp/shortcutprobe.log"
BASE="/tmp/shortcutbase.txt"
MODE="${1:-full}"

echo "══ 快捷方式探针 · 验证 ══"

# ── 步骤 0：设备探测（⚠️ 无设备 ⇒ 打印「未验证」并 exit 0）──
DEVICES="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
  echo
  echo "未验证：无已连接设备。"
  echo "  接上设备（或 adb connect <ip>:<port>）后重跑本脚本。"
  echo "  探针 APK 已就绪：$APK"
  echo
  echo "  ⚠️ 这是「未执行」，不是「验证失败」——两者含义完全不同。"
  exit 0
fi
echo "设备：$DEVICES"

# ── 步骤 1：采 dumpsys 基线 ──
collect_base() {
  echo
  echo "── [1] 采 dumpsys 基线 ──"
  adb shell dumpsys shortcut > "$BASE" 2>/dev/null
  local n
  n="$(grep -c 'ShortcutInfo' "$BASE" 2>/dev/null || echo 0)"
  echo "  dumpsys 里的 ShortcutInfo 块数 = $n"
  echo "  基线已存：$BASE"

  # ⚠️ 目标包的**存在性**必须先判 —— 本次真机实测就撞到「米家根本没建快捷方式」。
  # 不判的话，[6] 的「未找到」会被误读成「漏包」（包可见性问题），
  # 而两者完全不是一回事、下一步也完全不同。
  echo
  echo "  ── 目标样本选取 ──"
  local mihome
  mihome="$(grep -c 'packageName=com\.xiaomi\.smarthome' "$BASE" 2>/dev/null || echo 0)"
  if [ "$mihome" -gt 0 ]; then
    echo "  ✓ 米家（com.xiaomi.smarthome）有 $mihome 条快捷方式 ⇒ 探针 [6] 会走主路径"
    echo "  ── 其中一条的 dat（看是否被省略成 ...）：──"
    grep -A 12 'packageName=com\.xiaomi\.smarthome' "$BASE" | grep -m1 'dat='
  else
    echo "  ⚠️ 本设备上米家**没有快捷方式**（dumpsys 里 0 条）"
    echo "     ⇒ 探针 [6] 会走「未找到」分支，但**这不是缺陷** ——"
    echo "       若 dumpsys 也是 0 条，说明该设备本来就没建过米家快捷方式；"
    echo "       只有『dumpsys 有、探针没有』才是漏包（包可见性）。"
    echo "     ⇒ 本轮改用探针 [6.5] 的**回退样本**做 dat 对照（不依赖米家，见下方）"
  fi

  # 全设备 dat 残缺样本（与探针 [6.5] 配对）
  local truncated
  truncated="$(grep -oE 'dat=[^ ]*\.\.\.' "$BASE" 2>/dev/null | wc -l | tr -d ' ')"
  echo "  ── dumpsys 里被省略成 ... 的 dat 共 $truncated 处（这正是 18.1% 那类）──"
  grep -oE 'packageName=[a-z0-9._]+' "$BASE" 2>/dev/null | sort | uniq -c | sort -rn | head -5 \
    | sed 's/^/     /'
}

# ── 步骤 2：安装 ──
install_probe() {
  echo
  echo "── [2] 安装探针 ──"
  [ -f "$APK" ] || { echo "  ✗ 找不到 $APK —— 先跑：bash scripts/probe/xposed-channel/build.sh shortcutprobe" >&2; return 1; }
  adb uninstall com.vflow.shortcutprobe.xposed >/dev/null 2>&1
  adb install -r -t "$APK" || { echo "  ✗ 安装失败" >&2; return 1; }
  echo "  ✓ 已安装。"
  echo
  echo "  ⚠️⚠️ 接下来两步必须**手工**做（脚本做不了）："
  echo "     1. 打开 LSPosed → 启用「vFlow ShortcutProbe」→ **作用域勾选「系统框架」**"
  echo "     2. **重启设备**（system_server 必须重启才会注入；只重启 App 无效）"
  echo
  echo "     重启并等约 90 秒（探针延后 60 秒跑）后，执行："
  echo "       bash scripts/xposed-shortcut-probe-verify.sh logs"
}

# ── 步骤 3：抓日志并自动判定 ──
judge_logs() {
  echo
  echo "── [3] 抓探针日志 ──"
  # ⚠️ 启动期日志会被开机洪流挤掉 ⇒ 用 `-d` 拉当前缓冲（探针延后 60s，通常在缓冲内）
  adb logcat -d -s ShortcutProbe:E > "$LOG" 2>/dev/null
  local lines
  lines="$(wc -l < "$LOG" | tr -d ' ')"
  echo "  抓到 $lines 行 → $LOG"
  if [ "$lines" -eq 0 ]; then
    echo
    echo "  ✗ 一条探针日志都没有。可能原因（按优先级）："
    echo "     1. LSPosed 里没启用模块，或作用域没勾「系统框架」"
    echo "     2. 装完没重启设备"
    echo "     3. 重启后还没等满 60 秒（探针延后跑）"
    echo "     4. 日志被开机洪流挤掉了 ⇒ 重启后**立刻**抓，或用 LSPosed 导出 verbose 日志"
    echo "     ⚠️ 不要为绕过它而改探针代码 —— P0-FINDINGS.md §4 记过这条路走不通。"
    return 1
  fi

  echo
  echo "── [4] 五项判据自动判定 ──"
  local pass=0 fail=0 unknown=0
  chk() { # chk <标签> <判据描述> <grep 模式> <期望>
    local tag="$1" desc="$2" pat="$3"
    if grep -qE "$pat" "$LOG"; then
      echo "  ✅ $tag  $desc"
      pass=$((pass+1))
    elif grep -q "\[${tag%% *}\]" "$LOG"; then
      echo "  ❌ $tag  $desc  —— 打印了该项但判据不满足，见日志"
      fail=$((fail+1))
    else
      echo "  ⚠️ $tag  $desc  —— 该项**根本没打印**（上游项失败导致跳过？）"
      unknown=$((unknown+1))
    fi
  }

  # 1：能否取到 ShortcutService
  chk "1"  "LocalServices 取到 ShortcutService"        '\[1\] ✓ 拿到：'
  # 2：★ injectBinderCallingUid
  chk "2"  "★ injectBinderCallingUid() 返回 1000"      '\[5\] ✓ 返回 = 1000'
  # 3：字段名（能看到用户映射候选）
  chk "3"  "内部字段名可见（用户映射候选）"             '\[2\]   候选字段 '
  # 4：dat 完整 + extras 类型
  # ⚠️ 4a 的主判据是「目标包有多少条」。0 条时**不是失败**（设备本来就没建），
  #    故 4a 只在「>0」时算通过，并且下面的回退样本 4c 兜住核心结论。
  if grep -qE '\[6\.0\] 定位到 [1-9]' "$LOG"; then
    echo "  ✅ 4a 目标包条目被定位到（非漏包）"
    pass=$((pass+1))
    chk "4b" "extras: extra_scene_account 是 String"    'extra_scene_account -> java\.lang\.String'
  elif grep -qE '\[6\.0\]   ✗ 未找到' "$LOG"; then
    echo "  ⚠️ 4a 目标包未找到 —— **先看 [1] 的 dumpsys 计数**："
    echo "        dumpsys 也是 0 ⇒ 该设备本来就没建（非缺陷，不计失败）"
    echo "        dumpsys 有而这里没有 ⇒ 漏包（包可见性）"
    unknown=$((unknown+1))
  fi
  # 4c：dat 对照的**核心证据**（不依赖米家）—— 见探针 probeDatSamples 的设计说明
  chk "4c" "回退样本已产出（带 dat 的条目）"            '\[6\.5\] ✓ 已报 [1-9]'
  # 5：用户过滤
  chk "5"  "用户归属已打印"                             '\[7\]   UserHandle\.myUserId\(\) = '

  # 4c：dat 完整性的**人工判读**（自动判不了「完整」与否，但要提示）
  echo
  echo "  ── 需人工判读的两项 ──"
  grep -m2 '\[6\.2\]' "$LOG" | sed 's/^/    /'
  echo "    ↑ dat 是否完整：看 toUri(0) 里 dat= 后面是不是 '...'（省略号 = 残缺）"

  local hook_count dumpsys_count
  hook_count="$(grep -oE '\[3\] 全量遍历：ShortcutInfo 总数 = [0-9]+' "$LOG" | grep -oE '[0-9]+$')"
  dumpsys_count="$(grep -c 'ShortcutInfo' "$BASE" 2>/dev/null || echo '?')"
  echo
  echo "  ── 计数对比 ──"
  echo "    hook 侧 = ${hook_count:-未打印} / dumpsys 侧 = $dumpsys_count"
  if [ -n "${hook_count:-}" ] && [ "$dumpsys_count" != "?" ]; then
    if [ "$hook_count" = "$dumpsys_count" ]; then
      echo "    ✅ 一致"
    else
      echo "    ⚠️ 不一致 ⇒ 排查顺序：**先用户过滤（第 5 项）→ 再包可见性（第 2 项）→ 最后才版本差异**"
    fi
  fi

  echo
  echo "  ── 汇总：通过 $pass / 不满足 $fail / 未打印 $unknown ──"
  echo "  （完整日志：$LOG）"
  return 0
}

case "$MODE" in
  base)   collect_base ;;
  logs)   collect_base; judge_logs ;;
  full)   collect_base; install_probe ;;
  *)      echo "未知模式：$MODE（可用：full / logs / base）" >&2; exit 2 ;;
esac

echo
echo "══ 结束 ══"
