#!/usr/bin/env bash
#
# 数据卡（DDS）切换 —— 跨机型验证脚手架（全 adb，零代码）
#
# 用途：在不构建 APK、不写任何 Kotlin 的前提下，用 adb 问清
#       docs/fork/sim-data-switch-design.md 里的关键前置事实，
#       供在新机型上复测时使用（设计文档 §8.4 要求）。
#
# ⚠️ 本脚本只做**只读**探测 + 一次可选的切换往返（带还原）。
#    `service call` 事务码扫描**刻意没有实现** —— 事务码逐机型漂移，
#    调错码会改到相邻设置项（见设计文档 §3.2）。需要时请手工、谨慎地做。
#
# 用法：
#   ./scripts/sim-data-verify.sh snapshot       # 一次性快照（推荐先跑这个）
#   ./scripts/sim-data-verify.sh broadcast      # 清日志 → 切卡 → 抓广播（**会短暂断网**）
#   ./scripts/sim-data-verify.sh raw            # 把所有原始输出存盘，供人工细看
#
# 输出同时落盘到 .sim-verify/<时间戳>/，便于回填设计文档 §9。
#
# 注意：broadcast 子命令会在**卡1/卡2 之间来回切一次**并还原。若当前有多卡
#       且你不希望断网，请不要跑它。

set -uo pipefail

# Git Bash 下必须禁掉路径转换，否则 /data/local/tmp 之类的设备路径会被改写成 Windows 路径
export MSYS_NO_PATHCONV=1

OUT_DIR=".sim-verify/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"

# ---------- 颜色 ----------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
  C_BLU=$'\033[36m'; C_BLD=$'\033[1m';  C_RST=$'\033[0m'
else
  C_RED=''; C_GRN=''; C_YEL=''; C_BLU=''; C_BLD=''; C_RST=''
fi

hdr()  { printf '\n%s=== %s ===%s\n' "$C_BLD$C_BLU" "$1" "$C_RST"; }
ok()   { printf '  %s✓%s %s\n' "$C_GRN" "$C_RST" "$1"; }
bad()  { printf '  %s✗%s %s\n' "$C_RED" "$C_RST" "$1"; }
warn() { printf '  %s!%s %s\n' "$C_YEL" "$C_RST" "$1"; }
info() { printf '    %s\n' "$1"; }

sh() { adb shell "$@" 2>&1 | tr -d '\r'; }

require_device() {
  if ! adb get-state >/dev/null 2>&1; then
    printf '%s未检测到设备。请先连接手机并开启调试。%s\n' "$C_RED" "$C_RST" >&2
    printf '提示：adb devices -l 应能看到设备。\n' >&2
    exit 1
  fi
}

# ============================================================ snapshot

cmd_snapshot() {
  hdr "设备身份"
  local brand model sdk ver
  brand=$(sh getprop ro.product.brand)
  model=$(sh getprop ro.product.model)
  sdk=$(sh getprop ro.build.version.sdk)
  ver=$(sh getprop ro.build.version.release)
  info "brand=$brand model=$model android=$ver (SDK $sdk)"

  hdr "1. 双卡情况（订阅列表）"
  local isub_dump="$OUT_DIR/isub.txt"
  sh dumpsys isub > "$isub_dump"
  if grep -q "Logical SIM slot" "$isub_dump" 2>/dev/null; then
    grep -E "Logical SIM slot|^default|^activeData|Active modem count" "$isub_dump" | sed 's/^/    /'
    ok "已落盘：$isub_dump"
  else
    bad "dumpsys isub 无预期输出 —— 该 ROM 可能改了 isub 结构"
  fi

  hdr "2. 当前默认上网卡（三方交叉核对）"
  # 路径 A：Settings（只读）
  local from_settings
  from_settings=$(sh settings get global multi_sim_data_call)
  info "settings get global multi_sim_data_call = $from_settings"
  # 路径 B：dumpsys
  local from_dump
  from_dump=$(grep -E "^defaultDataSubId" "$isub_dump" 2>/dev/null | head -1 | sed 's/.*=//')
  info "dumpsys isub defaultDataSubId              = $from_dump"
  if [ -n "$from_settings" ] && [ -n "$from_dump" ] && [ "$from_settings" = "$from_dump" ]; then
    ok "两条路径一致（$from_settings）"
  else
    warn "两条路径不一致 —— 值得细看"
  fi

  hdr "3. shell 是否持有 MODIFY_PHONE_STATE（切换的前提）"
  local pkg_dump="$OUT_DIR/shell_pkg.txt"
  sh dumpsys package com.android.shell > "$pkg_dump"
  if grep -q "MODIFY_PHONE_STATE: granted=true" "$pkg_dump"; then
    ok "shell 持有 MODIFY_PHONE_STATE —— 反射调 setDefaultDataSubId 可行"
  else
    bad "shell 未持有 MODIFY_PHONE_STATE —— 本 ROM 上切换路径可能不可用"
    warn "注意：不要用某个子命令的 'Permission denied' 反推权限，见设计文档 §2.3"
  fi

  hdr "4. 可用的设置路径盘点（预期：全部不可用于切卡）"
  info "svc data 子命令："
  sh svc data 2>&1 | sed 's/^/      /'
  info "cmd phone 里 data 段："
  sh cmd phone help 2>&1 | grep -iA 4 "Mobile Data Test Mode" | sed 's/^/      /'
  warn "两者都只有 enable/disable —— 无法切换数据卡（设计文档 §2.1）"

  hdr "5. isub 服务是否存在"
  if sh service list 2>/dev/null | grep -q "isub:"; then
    ok "isub 服务存在：$(sh service list 2>/dev/null | grep 'isub:' | head -1)"
  else
    bad "未找到 isub 服务"
  fi

  hdr "结论要点"
  info "1. 切换需要 shell 持有 MODIFY_PHONE_STATE（第 3 节）"
  info "2. 接收广播理论上不需要任何权限"
  info "3. 用 broadcast 子命令实测第 2 条（会短暂断网）"
}

# ============================================================ broadcast

cmd_broadcast() {
  hdr "广播验证：清日志 → 切卡 → 抓广播"
  warn "此操作会在卡1/卡2 之间切一次并还原，期间蜂窝数据会短暂中断"

  local before
  before=$(sh settings get global multi_sim_data_call)
  info "当前默认数据卡 subId = $before"

  # 找到当前卡之外的另一张卡
  local cards
  cards=$(sh dumpsys isub 2>/dev/null | grep -oE "subId=[0-9]+" | grep -oE "[0-9]+" | sort -u | tr '\n' ' ')
  info "dumpsys 里出现的 subId：$cards"

  local other=""
  for s in $cards; do
    if [ "$s" != "$before" ]; then other="$s"; break; fi
  done

  if [ -z "$other" ]; then
    bad "找不到第二张卡（单卡机型？）—— 无法验证切换"
    return
  fi
  info "将切到 subId=$other，随后还原为 $before"

  sh logcat -c -b all

  warn "脚本无法直接调 setDefaultDataSubId（那需要 shell binder 调用）。"
  info "请在这之前用 vFlow 的『切换数据卡』模块，或手工在系统设置里切一次。"
  info "然后等 3 秒，看下面的广播抓取结果。"
  printf '\n    %s按回车继续…%s' "$C_YEL" "$C_RST"; read -r _

  hdr "广播抓取结果"
  local bc="$OUT_DIR/broadcast.txt"
  sh logcat -d -b all -v time | grep -iE "DATA_SUBSCRIPTION|broadcastSubId" > "$bc"
  if [ -s "$bc" ]; then
    ok "抓到广播（已落盘 $bc）"
    # 只看发送方那一行，最干净
    grep "broadcastSubId action" "$bc" | sed 's/^/    /' | head -10
    hdr "extras 实测（这是 App 侧要读的 key）"
    sh dumpsys activity broadcasts history 2>/dev/null \
      | grep -A 3 "ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED" \
      | grep "extras:" | head -3 | sed 's/^/    /'
    ok "预期形如：android.telephony.extra.SUBSCRIPTION_INDEX=N, subscription=N"
  else
    bad "没抓到广播 —— 该 ROM 可能改了 action 字符串或根本不发这条广播"
    warn "这是关键失败：触发器将无法工作。请把 $bc 与完整 logcat 留档。"
  fi

  hdr "还原检查"
  local after
  after=$(sh settings get global multi_sim_data_call)
  info "当前默认数据卡 subId = $after（期望恢复到 $before）"
}

# ============================================================ raw

cmd_raw() {
  hdr "全量转储"
  for c in "dumpsys isub" "dumpsys telephony.registry" "dumpsys activity broadcasts" \
           "settings list global" "service list" "cmd phone help"; do
    local f="$OUT_DIR/$(echo "$c" | tr ' .' '__').txt"
    sh $c > "$f" 2>&1
    info "$c -> $f ($(wc -l < "$f" 2>/dev/null || echo 0) 行)"
  done
  sh getprop > "$OUT_DIR/getprop.txt" 2>&1
  ok "已落盘到 $OUT_DIR/"
}

# ============================================================ main

require_device

case "${1:-snapshot}" in
  snapshot)  cmd_snapshot ;;
  broadcast) cmd_broadcast ;;
  raw)       cmd_raw ;;
  *) printf '用法: %s [snapshot|broadcast|raw]\n' "$0" >&2; exit 1 ;;
esac

printf '\n%s完成。输出目录：%s%s\n' "$C_GRN" "$OUT_DIR" "$C_RST"
