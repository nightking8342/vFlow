#!/usr/bin/env bash
#
# 折叠屏触发器 —— P0 真机验证脚手架（第一层：零代码）
#
# 用途：在不构建 APK、不写任何 Kotlin 的前提下，用 adb 问清
#       docs/fork/fold-trigger-design.md §7 中第 1、2(部分)、4、5、6 项的
#       前置事实。跑完本脚本，方案里"信号优先级"和"降级策略"的
#       大部分不确定性就能消除。
#
# 用法：
#   ./scripts/fold-trigger-verify.sh snapshot     # 一次性快照（设备静止时跑）
#   ./scripts/fold-trigger-verify.sh watch        # 边开合边观察（另开一个终端跑）
#   ./scripts/fold-trigger-verify.sh raw          # 把所有原始输出存盘，供人工细看
#
# 典型流程：
#   终端 A: ./scripts/fold-trigger-verify.sh watch
#   终端 B: （此时手动折叠、展开、半折手机，看 A 的输出）
#
# 输出同时落盘到 .fold-verify/<时间戳>/，便于回填设计文档 §9。

set -uo pipefail

OUT_DIR=".fold-verify/$(date +%Y%m%d-%H%M%S)"
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

# ---------- 前置检查 ----------
require_device() {
  if ! adb get-state >/dev/null 2>&1; then
    printf '%s未检测到设备。请先连接手机并开启 USB 调试。%s\n' "$C_RED" "$C_RST" >&2
    printf '提示：adb devices -l 应能看到设备。\n' >&2
    exit 1
  fi
}

sh() { adb shell "$@" 2>&1 | tr -d '\r'; }

# ---------- 1. 设备信息 ----------
check_device_info() {
  hdr "1. 设备信息"

  local brand model release sdk
  brand=$(sh getprop ro.product.brand)
  model=$(sh getprop ro.product.model)
  release=$(sh getprop ro.build.version.release)
  sdk=$(sh getprop ro.build.version.sdk)

  info "品牌   : $brand"
  info "型号   : $model"
  info "系统   : Android $release (API $sdk)"

  # MIUI / 澎湃 OS 版本
  local miui
  miui=$(sh getprop ro.miui.ui.version.name)
  if [ -n "$miui" ]; then
    info "MIUI   : $miui"
  fi
  local osver
  osver=$(sh getprop ro.mi.os.version.name)
  [ -n "$osver" ] && info "澎湃OS : $osver"

  # 保存
  {
    echo "brand=$brand"; echo "model=$model"
    echo "android=$release"; echo "sdk=$sdk"
    echo "miui=$miui"; echo "hyperos=$osver"
  } > "$OUT_DIR/device-info.txt"

  # API 30 是 TYPE_HINGE_ANGLE 的门槛
  if [ "${sdk:-0}" -ge 30 ]; then
    ok "API $sdk >= 30 —— 铰链角度传感器（TYPE_HINGE_ANGLE）在 API 层面可用"
  else
    bad "API $sdk < 30 —— TYPE_HINGE_ANGLE 不可用，只能走小米 device_posture 或 DisplayListener"
  fi
}

# ---------- 2. 折叠相关硬件特性 ----------
check_features() {
  hdr "2. 折叠相关系统特性（PackageManager.FEATURE_*）"

  sh pm list features > "$OUT_DIR/features.txt"
  local feats
  feats=$(grep -iE 'hinge|fold|display|screen' "$OUT_DIR/features.txt" || true)

  if [ -n "$feats" ]; then
    while IFS= read -r line; do info "$line"; done <<< "$feats"
  else
    warn "未匹配到 hinge/fold 相关特性"
  fi

  echo "---"
  if grep -qi 'android.hardware.sensor.hinge_angle' "$OUT_DIR/features.txt"; then
    ok "FEATURE_SENSOR_HINGE_ANGLE 存在 —— 设备声明支持铰链角度传感器"
  else
    bad "FEATURE_SENSOR_HINGE_ANGLE 不存在 —— 铰链角度传感器路径不可用，必须依赖 device_posture"
  fi
}

# ---------- 3. 传感器枚举 ----------
check_sensors() {
  hdr "3. 传感器枚举（找铰链传感器）"

  sh dumpsys sensorservice > "$OUT_DIR/sensorservice-full.txt"

  # 标准铰链传感器
  local hinge
  hinge=$(grep -i 'hinge' "$OUT_DIR/sensorservice-full.txt" || true)
  if [ -n "$hinge" ]; then
    ok "发现 hinge 相关传感器："
    while IFS= read -r line; do info "$line"; done <<< "$hinge"
  else
    bad "dumpsys 中未发现 hinge 传感器"
  fi

  echo "---"
  # 厂商私有传感器（三星 65686/65695/65697、Duo 33171006 等）
  local priv
  priv=$(grep -iE 'fold|folding|lid|posture|flex' "$OUT_DIR/sensorservice-full.txt" || true)
  if [ -n "$priv" ]; then
    ok "发现折叠姿态相关（含厂商私有）："
    while IFS= read -r line; do info "$line"; done <<< "$priv"
  else
    warn "未发现 fold/lid/posture 命名的传感器"
  fi

  echo "---"
  # 打印完整传感器清单行数，供人工核对
  local count
  count=$(grep -cE '^\s*0x[0-9a-fA-F]+\)' "$OUT_DIR/sensorservice-full.txt" || echo 0)
  info "传感器清单条目数（粗估）：$count"
  info "完整 dump 已存：$OUT_DIR/sensorservice-full.txt"
}

# ---------- 4. 小米 Settings.Global 关键项 ----------
read_setting() {
  # 返回去除换行与 "null" 的裸值
  local key="$1"
  local v
  v=$(sh settings get global "$key")
  v="${v%$'\n'}"
  if [ -z "$v" ] || [ "$v" = "null" ]; then
    printf ''
  else
    printf '%s' "$v"
  fi
}

posture_to_text() {
  case "$1" in
    0) printf 'UNKNOWN（未知 / 或该机型不适用）' ;;
    1) printf 'FOLDED（折叠）' ;;
    2) printf 'HALF_OPENED（半折）' ;;
    3) printf 'UNFOLDED（展开）' ;;
    *) printf '非法值' ;;
  esac
}

check_miui_settings() {
  hdr "4. 小米 Settings.Global 关键项（§7 第 1 项）"

  # --- device_posture：本方案的主力信号 ---
  local posture
  posture=$(read_setting device_posture)
  if [ -n "$posture" ]; then
    ok "device_posture = $posture  → $(posture_to_text "$posture")"
    info "★ 这是主力信号，可读即成功。继续看 watch 模式能否跟随变化。"
  else
    bad "device_posture 读不到（空或 null）"
    info "可能是：非小米设备 / 被系统拦截 / 该机型不上报"
    info "→ 主力信号不可用，方案需降级到铰链传感器或 DisplayListener"
  fi

  echo "---"
  # --- display_features：折痕几何（P2 才用，但顺手确认） ---
  local dispfeat
  dispfeat=$(read_setting display_features)
  if [ -n "$dispfeat" ]; then
    ok "display_features = $dispfeat"
    info "（P2 折痕几何用，本期不需要，但说明该 key 可读）"
  else
    warn "display_features 读不到（本期不影响）"
  fi

  echo "---"
  # --- 折叠屏标识：注意官方属性名有拼写错误 muilt（不是 multi） ---
  local mtype mtype2
  mtype=$(sh getprop persist.sys.muiltdisplay_type)
  mtype2=$(sh getprop persist.sys.multidisplay_type)
  if [ -n "$mtype" ]; then
    info "persist.sys.muiltdisplay_type = $mtype  （官方文档写法，含拼写错误）"
    [ "$mtype" = "2" ] && ok "值为 2 —— 按官方文档即为折叠屏设备"
  fi
  if [ -n "$mtype2" ]; then
    info "persist.sys.multidisplay_type = $mtype2  （拼写修正版）"
  fi
  if [ -z "$mtype" ] && [ -z "$mtype2" ]; then
    warn "两个拼写的 muiltdisplay_type 都读不到"
  fi

  {
    echo "device_posture=$posture"
    echo "display_features=$dispfeat"
    echo "persist.sys.muiltdisplay_type=$mtype"
    echo "persist.sys.multidisplay_type=$mtype2"
  } > "$OUT_DIR/miui-settings.txt"
}

# ---------- 5. Display 信息 ----------
check_displays() {
  hdr "5. Display 信息（DisplayListener 兜底路径的基础）"

  sh dumpsys display > "$OUT_DIR/display-full.txt"

  # 提取 display 设备摘要
  local disp
  disp=$(grep -E 'mDisplayId=|DisplayDeviceInfo|uniqueId|mBaseDisplayInfo' "$OUT_DIR/display-full.txt" | head -40 || true)
  if [ -n "$disp" ]; then
    while IFS= read -r line; do info "$line"; done <<< "$disp"
  else
    warn "未能提取 display 摘要，请人工看 $OUT_DIR/display-full.txt"
  fi

  echo "---"
  local dcount
  dcount=$(grep -cE '^\s*Display [0-9]+' "$OUT_DIR/display-full.txt" || echo 0)
  info "疑似 display 数量：$dcount"
  info "（折叠屏通常内外屏是两个逻辑 display；数量>1 支持 DisplayListener 兜底路径）"

  # 当前窗口尺寸
  echo "---"
  local wm
  wm=$(sh wm size)
  info "wm size: $wm"
  local wmd
  wmd=$(sh wm density)
  info "wm density: $wmd"
}

# ---------- 6. 快照汇总 ----------
do_snapshot() {
  require_device
  printf '%s折叠屏触发器 P0 验证 —— 快照模式%s\n' "$C_BLD" "$C_RST"
  printf '输出目录：%s\n' "$OUT_DIR"

  check_device_info
  check_features
  check_sensors
  check_miui_settings
  check_displays

  hdr "快照完成"
  info "原始输出已存：$OUT_DIR"
  info "下一步：运行 './scripts/fold-trigger-verify.sh watch'，"
  info "        然后手动折叠/展开/半折手机，观察信号是否跟随变化。"
}

# ---------- 7. 持续观察（多信号融合实时对比） ----------
# 一次性取回全部信号，避免多次 adb 往返造成秒级延迟。
# 四路信号：
#   device_posture  —— 小米主力信号
#   hinge_angle     —— oem_hinge_angle (type 36) 最新事件值
#   fold_status     —— 小米私有传感器，事件首字段=状态、次字段=角度
#   device_state    —— DeviceStateManager 的 mCommittedState
#   屏0/屏1          —— 内外屏 state（ON/OFF）
read_all_signals() {
  MSYS_NO_PATHCONV=1 adb shell sh /data/local/tmp/vflow-fold-probe.sh 2>/dev/null | tr -d '\r'
}

# 把探针脚本推送到设备（只做一次，watch 启动时调用）
# 注意：MSYS_NO_PATHCONV=1 会让 adb 把本地路径也当远端处理，
# 因此本地路径必须用相对路径（相对当前工作目录），否则 Windows adb 找不到文件。
push_probe() {
  local probe=".fold-verify/probe.sh"
  mkdir -p .fold-verify
  cat > "$probe" <<'PROBE'
echo "P="; settings get global device_posture
dumpsys sensorservice 2>/dev/null | awk '
  /HINGE_ANGLE Wakeup: last/ {f=1; next}
  /FOLD_STATUS Wakeup: last/ {f=2; next}
  f && /^[[:space:]]+[0-9]+ \(ts=/ { if(f==1) h=$0; else fl=$0; next }
  f && !/^[[:space:]]+[0-9]+ \(ts=/ { f=0 }
  END {
    gsub(/.*\) /,"",h); sub(/,.*/,"",h);
    gsub(/.*\) /,"",fl); sub(/,.*/,"",fl);
    print "H=" h; print "F=" fl
  }'
echo "DS="; dumpsys device_state 2>/dev/null | grep -m1 "mCommittedState" | sed -E "s/.*name='([^']*)'.*/\1/"
echo "D0="; dumpsys display 2>/dev/null | grep -m2 -oE "state (ON|OFF), committedState (ON|OFF)" | sed -E "s/state ([A-Z]+), committedState ([A-Z]+)/\1\/\2/" | tr "\n" " "
PROBE
  MSYS_NO_PATHCONV=1 adb push "$probe" /data/local/tmp/vflow-fold-probe.sh >/dev/null 2>&1
}

# 按前缀从 read_all_signals 的输出里取值（下一行即值）
signal_field() {
  printf '%s\n' "$1" | awk -v p="$2:" '$0 == p { getline; print; exit }'
}

# 把传感器事件行压成首个数值（形如 "  10 (ts=..., wall=...) 178.00, " → "178.00"）
extract_first_value() {
  printf '%s' "$1" | sed -E 's/.*\) //; s/,.*//; s/[[:space:]]+$//'
}

do_watch() {
  require_device
  push_probe
  printf '%s折叠屏触发器 P0 验证 —— 观察模式%s\n' "$C_BLD" "$C_RST"
  printf '现在请手动操作手机，依次做：\n'
  printf '  1) 折叠（合上）\n  2) 展开（打开）\n'
  printf '  3) 折到一半 → 保持几秒（测 TENT / HALF_OPENED）\n'
  printf '  4) 半折 → 完全展开\n  5) 横竖屏各转一次（测误判）\n'
  printf '按 Ctrl+C 结束。\n\n'

  printf '%s%-8s %-6s %-8s %-9s %-11s %s%s\n' \
    "$C_BLD" "时间" "posture" "angle" "fold_st" "devState" "屏0/屏1" "$C_RST"
  printf '%s\n' "--------------------------------------------------------------------------------"

  local first=1
  local prev_p="" prev_f="" prev_ds="" prev_d0=""

  while true; do
    local raw p h f ds d0
    raw=$(read_all_signals)

    p=$(signal_field "$raw" "P" | tr -d '[:space:]')
    h=$(extract_first_value "$(signal_field "$raw" "H")")
    f=$(extract_first_value "$(signal_field "$raw" "F")")
    ds=$(signal_field "$raw" "DS" | sed -E "s/.*name='([^']*)'.*/\1/")
    d0=$(signal_field "$raw" "D0" | head -2 | tr '\n' '/' | sed -E 's/state //g; s/, committedState //g')

    [ -z "$p" ]  && p="-"
    [ -z "$h" ]  && h="-"
    [ -z "$f" ]  && f="-"
    [ -z "$ds" ] && ds="-"
    [ -z "$d0" ] && d0="-"

    if [ "$first" = "1" ] || [ "$p" != "$prev_p" ] || [ "$f" != "$prev_f" ] \
       || [ "$ds" != "$prev_ds" ] || [ "$d0" != "$prev_d0" ]; then
      local ts mark=""
      ts=$(date +%H:%M:%S)
      [ "$first" = "1" ] && mark="  (基线)"
      printf '%s%-8s %-6s %-8s %-9s %-11s %s%s\n' \
        "$C_BLU" "$ts" "$p" "$h" "$f" "$ds" "$d0$mark" "$C_RST"

      # 逐项标注变化，直接读出「哪些信号跟着动了」
      if [ "$first" != "1" ]; then
        [ "$p"  != "$prev_p" ]  && printf '  %s→ posture     : %s → %s  [%s]%s\n' \
          "$C_GRN" "$prev_p" "$p" "$(posture_to_text "$p")" "$C_RST"
        [ "$f"  != "$prev_f" ]  && printf '  %s→ fold_status : %s → %s%s\n' \
          "$C_GRN" "$prev_f" "$f" "$C_RST"
        [ "$ds" != "$prev_ds" ] && printf '  %s→ deviceState : %s → %s%s\n' \
          "$C_GRN" "$prev_ds" "$ds" "$C_RST"
        [ "$d0" != "$prev_d0" ] && printf '  %s→ 内外屏      : %s → %s%s\n' \
          "$C_GRN" "$prev_d0" "$d0" "$C_RST"
      fi

      first=0
      prev_p="$p"; prev_f="$f"; prev_ds="$ds"; prev_d0="$d0"
    fi

    sleep 0.5
  done
}

# ---------- 8. 原始转储 ----------
do_raw() {
  require_device
  printf '把所有原始输出转储到 %s\n' "$OUT_DIR"

  check_device_info >/dev/null
  check_features   >/dev/null
  check_sensors    >/dev/null
  check_miui_settings >/dev/null
  check_displays   >/dev/null

  sh getprop > "$OUT_DIR/getprop.txt"
  sh settings list global > "$OUT_DIR/settings-global.txt"

  printf '完成。关键文件：\n'
  for f in "$OUT_DIR"/*; do printf '  %s\n' "$f"; done
  printf '\n提示：settings-global.txt 里可 grep -i "fold\\|posture\\|display_f" 找其它相关 key。\n'
}

# ---------- 入口 ----------
case "${1:-snapshot}" in
  snapshot) do_snapshot ;;
  watch)    do_watch ;;
  raw)      do_raw ;;
  *)
    printf '用法：%s [snapshot|watch|raw]\n' "$0" >&2
    printf '  snapshot  一次性快照（默认）\n' >&2
    printf '  watch     持续观察信号变化（边操作手机边看）\n' >&2
    printf '  raw       转储全部原始输出供人工细看\n' >&2
    exit 1
    ;;
esac
