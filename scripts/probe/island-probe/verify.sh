#!/usr/bin/env bash
#
# 超级岛通知体积探针 · 一键验证脚本（全 adb，零代码）
#
# 用途：装探针 → 跑 → 抓日志 → 按判据表**自动判定**「通知体积随 notify 次数增长」是否成立。
#
# ⚠️ 三个设计要点：
#   1. **无设备时打印「未验证」并 exit 0**，不是报错退出 ——
#      否则「没设备」看起来像「验证失败」，而它们是完全不同的两件事。
#   2. ⚠️ **先 grant POST_NOTIFICATIONS**。Android 13+ 默认「询问」，
#      不授权时 `notify` 静默失败 ⇒ 探针打出一堆 0 字节 ⇒ 看起来像「没有增长」。
#      **那是假阴性**，比跑不起来更危险。
#   3. **判定必须看斜率**（首 vs 末），不能看绝对值 —— 崩溃是累积到第 84 次才发生的。
#
# 用法：
#   bash scripts/probe/island-probe/verify.sh          # 全流程
#   bash scripts/probe/island-probe/verify.sh logs     # 只重抓日志（探针已跑过）
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/out/islandprobe.apk"
PKG="com.vflow.islandprobe"
ACTIVITY="$PKG/.MainActivity"
LOG="/tmp/islandprobe.log"
MODE="${1:-full}"

echo "══ 超级岛通知体积探针 · 验证 ══"

# ── 步骤 0：APK 就绪检查 ──
if [ ! -f "$APK" ]; then
  echo "✗ 探针未构建。先跑：bash scripts/probe/island-probe/build.sh"
  exit 1
fi
echo "探针：$APK"

# ── 步骤 1：设备探测（⚠️ 无设备 ⇒ 打印「未验证」并 exit 0）──
# ⚠️ 固定第一条 serial：adb 对同一台设备可能开出多条 transport（USB + 无线 TLS），
#    不带 -s 的命令会以 "more than one device/emulator" **静默**失败
#    （既有验证脚本踩过，表现为采集文件全空、判定全判「未验证」）。
SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$SERIAL" ]; then
  echo
  echo "未验证：无已连接设备。"
  echo "  接上设备（或 adb connect <ip>:<port>）后重跑本脚本。"
  echo "  探针 APK 已就绪：$APK"
  echo
  echo "  ⚠️ 这是「未执行」，不是「验证失败」——两者含义完全不同。"
  exit 0
fi
echo "设备：$SERIAL"

if [ "$MODE" != "logs" ]; then
  # ── 步骤 2：安装（⚠️ 独立包名，与 vFlow 无关，-r 保留数据）──
  echo
  echo "── [2] 安装 ──"
  adb -s "$SERIAL" install -r -t "$APK" || { echo "✗ 安装失败"; exit 1; }

  # ⚠️⚠️ 通知权限：不授权 ⇒ notify 静默失败 ⇒ 全 0 字节 ⇒ 假阴性。
  echo "── [3] 授权通知（不做这步会得到全 0 的假阴性）──"
  adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>&1 \
    | sed 's/^/    /' || true

  # ── 步骤 4：清日志 → 跑 ──
  echo
  echo "── [4] 跑探针（5 场景 × 200 次 notify，约 25 秒）──"
  adb -s "$SERIAL" logcat -c
  adb -s "$SERIAL" shell am start -n "$ACTIVITY" | sed 's/^/    /'
  sleep 28
fi

# ── 步骤 5：抓日志 ──
echo
echo "── [5] 抓日志 ──"
adb -s "$SERIAL" logcat -d -s IslandProbe > "$LOG" 2>&1 || true
LINES="$(wc -l < "$LOG" | tr -d ' ')"
echo "    $LOG（$LINES 行）"
if [ "$LINES" -lt 5 ]; then
  echo "    ⚠️ 日志几乎为空 —— 探针可能没起来。真机排查："
  echo "       adb -s $SERIAL logcat -d | grep -i 'islandprobe\\|AndroidRuntime'"
  echo "    判定：未验证"
  exit 0
fi

# ── 步骤 6：判定 ──
echo
echo "════════ 判据表 ════════"
python - "$LOG" <<'PY'
import re, sys, io

# ⚠️⚠️ Windows 下 Python 的 stdout 默认按 **GBK** 编码，而本脚本里的判定文案是 UTF-8
#   ⇒ 中文全变 mojibake、`✓` 直接抛 UnicodeEncodeError（实测：表头乱码 + 判定段崩）。
#   这与 `xposed-js-verify.sh` 里记的那条「subprocess 默认 GBK 解码、设备发 UTF-8」
#   是**同一个根因**，位置从「读设备输出」挪到了「写脚本输出」而已。
#   ⚠️ 不要靠 `PYTHONIOENCODING=utf-8` 环境变量绕过 —— 脚本从别的 shell 调用时它不一定在。
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

path = sys.argv[1]
txt = io.open(path, encoding='utf-8', errors='replace').read()

results = {}
for m in re.finditer(
        r'RESULT (\S+)\s+first=(-?\d+)\s+last=(-?\d+)\s+growth=([+-]?\d+)'
        r'\s+per_round=([-\d.]+)\s+samples=(\d+)\s+curve=(\S*)', txt):
    name, first, last, growth, per, n, curve = m.groups()
    pts = [int(x) for x in curve.split(',') if x.strip().lstrip('-').isdigit()]
    results[name] = dict(first=int(first), last=int(last), growth=int(growth),
                         per=float(per), curve=pts)

if not results:
    print("未验证：日志里没有 RESULT 行（探针可能中途崩了）。")
    print("  看日志末尾：")
    for line in txt.strip().splitlines()[-15:]:
        print("   ", line)
    sys.exit(0)

print("%-26s %10s %10s %10s %12s" % ("场景", "首次", "末次", "growth", "每轮增量"))
print("-" * 74)
for k in sorted(results):
    r = results[k]
    print("%-26s %10d %10d %10d %12.1f" % (k, r['first'], r['last'], r['growth'], r['per']))
print()
for k in sorted(results):
    r = results[k]
    if r['curve']:
        print("  %-24s 曲线 %s" % (k, " → ".join(str(v) for v in r['curve'])))

print()
print("════════ 结论 ════════")

def get(prefix):
    for k in sorted(results):
        if k.startswith(prefix):
            return results[k]
    return None

def shape(r):
    """把曲线判成『递增』还是『达平台』—— 两者的结论完全不同。"""
    c = r['curve']
    n = len(c)
    if n < 5:
        return 'unknown'

    # ⚠️ 判据必须看**尾部**，不能只看后半段总和 —— 后半段里仍有增长时总和会掩盖
    #    「最后几步已经持平」这个事实（实测：末两点重复的曲线仍被判成 linear）。
    early = c[n // 4] - c[0]                # 最初 25% 的涨幅
    tail = c[-1] - c[(n * 3) // 4]          # 最后 25% 的涨幅
    total = c[-1] - c[0]

    if total <= 4096:
        return 'flat'
    if tail <= max(4096, early * 0.1):
        return 'plateau'
    # ⚠️ 「线性 vs 超线性」按**每段增速**比，不看绝对涨幅 —— 后者会被首段的大跳带偏。
    return 'linear'

# 崩溃日志的真值（2026-09-29，小米 2308CPXD0C）
CRASH_BYTES = 1033192
CRASH_ROUND = 84

a, b, d, e = get('A_'), get('B_'), get('D_'), get('E_')

if e:
    print(("✓ 基线（无 RemoteViews）：%+d 字节 —— 常数，符合预期" % e['growth'])
          if e['growth'] <= 1024 else
          "✗ 基线也在涨 %+d —— 问题不在岛参数，先查通知本身" % e['growth'])

if a:
    s = shape(a)
    print("核心场景 A（复用 RV + 写图标）：%s，首次 %d → 末次 %d（每轮 %+.1f）"
          % (s, a['first'], a['last'], a['per']))

    # ⚠️⚠️ 外推值必须**与崩溃真值做计算比较**，不能写死「同量级」——
    #   假数据的线性外推可以轻松算出 14 MiB，把它说成「和 1 MB 同量级」是错的。
    #   这里判的是「量级」（十进制），差 10 倍以上就如实说不同量级。
    if s == 'linear':
        proj = a['first'] + a['per'] * CRASH_ROUND
        ratio = proj / CRASH_BYTES
        same_mag = 0.1 <= ratio <= 10
        print("  ★ 判为**线性递增** ⇒ 每次 notify 都比上次大一截，与次数同阶")
        print("     外推第 %d 次 ≈ %.2f MiB；崩溃真值 %.2f MiB —— **%s**"
              % (CRASH_ROUND, proj / 1048576.0, CRASH_BYTES / 1048576.0,
                 "同量级" if same_mag else "差 %.0f 倍，量级不符" % (ratio if ratio >= 1 else 1 / ratio)))
        if same_mag:
            print("     ⇒ 复现了崩溃。修法：**不要复用同一组 RemoteViews**，或写之前重建")
        else:
            print("     ⇒ 形态对上了但**量级对不上** —— 复现不完整，"
                  "看 B/C/D 的差值是哪种分量在放大")
    elif s == 'plateau':
        print("  ★ 判为**早涨后平** ⇒ 增长集中在开头，之后稳定")
        print("     说明单次体积有上界 ⇒ **不会**随 notify 次数无限增长")
        print("     但若平台值本身已接近 1 MiB（见上表末次），单条通知照样可能超限")
    elif s == 'flat':
        print("  ✗ **没有增长** ⇒ 「累积」假设被证伪，崩溃另有原因（见分组对比）")
    else:
        print("  形态存疑，看上面的曲线自行判断")

if a and b:
    if a['per'] > 1024 and b['per'] < a['per'] / 4:
        print("★ A(有图) %.1f vs B(无图) %.1f 字节/轮 ⇒ 增长与**每次写位图**是乘性关系"
              % (a['per'], b['per']))
    else:
        print("· A %.1f 与 B %.1f 接近 ⇒ 增长与图标无关" % (a['per'], b['per']))

if a and d:
    if d['per'] < a['per'] / 2:
        print("★ D(每次新建 RV) %.1f vs A(复用) %.1f ⇒ 问题在【复用】而非 RemoteViews 本身"
              % (d['per'], a['per']))
    else:
        print("· 新建 RV 也涨 %.1f —— 比预想的更靠底层" % d['per'])
PY

echo
echo "════════ 原始采样（前 40 行）════════"
grep -E '图标 |场景 |parcel=' "$LOG" | head -40 || true
