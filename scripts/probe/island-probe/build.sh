#!/usr/bin/env bash
#
# 超级岛通知体积探针 —— 打包脚本
#
# 复用本仓库既有的极简打包链路（同 scripts/probe/xposed-channel/build.sh）：
#   javac(带 platform android.jar) → d8 → aapt2 compile/link → 注入 classes.dex
#   → zipalign → apksigner
#
# ⚠️ 三个已踩过的坑，不要"优化"掉：
#   1. d8 必须【显式列出所有 *.class】—— 只给目录会让匿名内部类丢失
#   2. Git Bash 下 adb 推设备路径要 MSYS_NO_PATHCONV=1
#   3. 最小 APK 必须声明 uses-sdk targetSdkVersion，否则小米会拦到 ReviewPermissionsActivity
#
# ⚠️ 本探针**不依赖 vFlow.jks**：它是独立包名、独立签名的旁路验证工具，
#    装到设备上不影响已装的 vFlow（这也是敢在**日常使用设备**上跑它的前提）。
#    故**不要**把它改成同签 —— 同签会让它与正式版争包名。
#
# 用法：
#   bash scripts/probe/island-probe/build.sh            # 只构建
#   bash scripts/probe/island-probe/build.sh install    # 构建 + 安装
#   bash scripts/probe/island-probe/build.sh run        # 构建 + 安装 + 清日志 + 跑 + 抓日志
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$HERE/out"
WORK="$HERE/.work"
PKG="com.vflow.islandprobe"
ACTIVITY="$PKG/.MainActivity"
# R.java 会被 aapt2 输出到 gen/<包名转路径>/R.java
PKG_PATH="${PKG//./\/}"

# ---------- 环境 ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$REPO_ROOT/local.properties" ]; then
  SDK="$(grep -E '^sdk\.dir=' "$REPO_ROOT/local.properties" | cut -d= -f2- | tr -d '\r')"
fi
[ -z "$SDK" ] && { echo "✗ 找不到 Android SDK" >&2; exit 1; }
SDK="${SDK//\\//}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"; BT="${BT%/}"
[ -z "$BT" ] && { echo "✗ 找不到 build-tools" >&2; exit 1; }
PLATFORM="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1)"; PLATFORM="${PLATFORM%/}"
ANDROID_JAR="$PLATFORM/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "✗ 找不到 $ANDROID_JAR" >&2; exit 1; }

pick() { [ -f "$1.bat" ] && echo "$1.bat" || echo "$1"; }
AAPT2="$(pick "$BT/aapt2")"
D8="$(pick "$BT/d8")"
ZIPALIGN="$(pick "$BT/zipalign")"
APKSIGNER="$(pick "$BT/apksigner")"

echo "SDK=$SDK"
echo "build-tools=$(basename "$BT")  platform=$(basename "$PLATFORM")"

# ---------- 构建 ----------
build() {
  rm -rf "$WORK"; mkdir -p "$WORK/classes" "$OUT"

  echo "── javac ──"
  # ⚠️ 不要加 -source/-target/-bootclasspath：JDK 21 已废弃 -bootclasspath，
  #    且 -source 17 会连带要求 --system，直接让编译失败（既有探针踩过）。
  #
  # ⚠️⚠️ **必须先 aapt2 link 生成 R.java 再 javac** —— 本探针用 `R.layout` / `R.id`
  #    引用资源，而 aapt2 的 `--java` 参数才会把 R.java 输出到源码树里。
  #    顺序反了会得到一堆 `找不到符号: 类 R`（既有探针不用 R. 故没踩过这个坑）。
  #    产出目录用 `$WORK/gen`（不放源码树，避免污染仓库）。
  mkdir -p "$WORK/gen"
  "$AAPT2" compile --dir "$HERE/res" -o "$WORK/res.zip"
  "$AAPT2" link -o "$WORK/base.apk" -I "$ANDROID_JAR" \
       --manifest "$HERE/AndroidManifest.xml" \
       --min-sdk-version 29 --target-sdk-version 36 \
       --java "$WORK/gen" \
       --auto-add-overlay "$WORK/res.zip"
  [ -f "$WORK/gen/$PKG_PATH/R.java" ] \
    || { echo "✗ aapt2 没生成 R.java（检查 --java 与包名）" >&2; exit 1; }

  # shellcheck disable=SC2046
  javac -nowarn -classpath "$ANDROID_JAR" -d "$WORK/classes" \
        $(find "$WORK/gen" -name '*.java') $(find "$HERE" -name '*.java')
  [ -n "$(find "$WORK/classes" -name '*.class' 2>/dev/null)" ] \
    || { echo "✗ javac 没产出 class" >&2; exit 1; }

  echo "── d8 ──"
  # ⚠️ 显式列出所有 *.class（含内部类 $Xxx.class）
  # shellcheck disable=SC2046
  "$D8" --min-api 29 --output "$WORK" $(find "$WORK/classes" -name '*.class')
  [ -f "$WORK/classes.dex" ] || { echo "✗ d8 没产出 classes.dex" >&2; exit 1; }

  echo "── aapt2 compile + link ──"
  # （已在上一步做过：本探针必须先 link 拿 R.java，故此处不再重复）

  echo "── 注入 classes.dex + zipalign ──"
  cp "$WORK/base.apk" "$WORK/merged.apk"
  ( cd "$WORK" && jar uf merged.apk classes.dex )
  "$ZIPALIGN" -f -p 4 "$WORK/merged.apk" "$WORK/aligned.apk"

  echo "── 签名（debug key —— 独立包名，不需与 vFlow 同签）──"
  local dbg="$HOME/.android/debug.keystore"
  if [ ! -f "$dbg" ]; then
    keytool -genkeypair -keystore "$dbg" -storepass android -keypass android \
      -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
      -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
  fi
  "$APKSIGNER" sign --ks "$dbg" --ks-key-alias androiddebugkey \
      --ks-pass pass:android --key-pass pass:android \
      --out "$OUT/islandprobe.apk" "$WORK/aligned.apk"

  echo "✓ $OUT/islandprobe.apk"
}

# ---------- 设备操作 ----------
# ⚠️ adb 对同一台设备可能开出多条 transport（USB + 无线 TLS）⇒ 不带 -s 的命令
#    会以 "more than one device/emulator" **静默**失败（既有验证脚本踩过）。
#    这里固定取第一条 serial。
pick_serial() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}'
}

install_apk() {
  local s; s="$(pick_serial)"
  [ -z "$s" ] && { echo "未验证：无已连接设备。" ; exit 0; }
  echo "── 安装到 $s ──"
  # ⚠️ `-r` 保留数据；探针是独立包名，与 vFlow 无关。
  adb -s "$s" install -r -t "$OUT/islandprobe.apk"
  # Android 13+ 的通知权限默认是「询问」⇒ 不 grant 的话 notify 静默失败，
  # 探针会输出一堆 0 字节，看起来像「没有增长」——**这是假阴性**。
  adb -s "$s" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
}

run_probe() {
  local s; s="$(pick_serial)"
  [ -z "$s" ] && { echo "未验证：无已连接设备。" ; exit 0; }
  echo "── 清日志 ──"
  adb -s "$s" logcat -c
  echo "── 启动探针 ──"
  adb -s "$s" shell am start -n "$ACTIVITY"
  echo "── 等待跑完（5 个场景 × 200 次 notify）──"
  sleep 25
  echo "── 抓日志 ──"
  adb -s "$s" logcat -d -s IslandProbe > /tmp/islandprobe.log 2>&1 || true
  echo "已写入 /tmp/islandprobe.log"
  echo
  echo "════════ RESULT 汇总 ════════"
  grep -E '^.*RESULT ' /tmp/islandprobe.log || echo "（没抓到 RESULT —— 探针可能没跑起来，见日志）"
  echo
  echo "════════ 关键采样 ════════"
  grep -E '图标 |场景 |#[0-9]+ *parcel=' /tmp/islandprobe.log | head -60 || true
}

build
case "${1:-}" in
  install) install_apk ;;
  run)     install_apk; run_probe ;;
  *)       echo "（只构建。用 install / run 装到设备）" ;;
esac
