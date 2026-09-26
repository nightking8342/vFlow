#!/usr/bin/env bash
#
# Xposed 通道通信探针 —— 打包脚本
#
# 复用的是本仓库既有的探针打包链路（见 docs/fork/sim-data-switch-design.md §9.5）：
#   javac(带 platform android.jar) → d8 → aapt2 link → 注入 classes.dex → zipalign → apksigner
#
# ⚠️ 三个已踩过的坑，不要"优化"掉：
#   1. d8 必须【显式列出所有 *.class】—— 只给目录会让匿名内部类丢失
#   2. Git Bash 下 adb 推设备路径要 MSYS_NO_PATHCONV=1
#   3. 最小 APK 必须声明 uses-sdk targetSdkVersion，否则小米会拦到 ReviewPermissionsActivity
#
# 产出：
#   out/fake-vflow.apk   —— 用 vFlow.jks 签名（与真 vFlow 同签）
#   out/fake-hook.apk    —— debug 签名（异签，模拟普通第三方 App）
#
# 用法：
#   ./build.sh              # 构建两个 APK
#   ./build.sh queries      # 额外构建「声明了 <queries>」的对照变体
#   ./build.sh install      # 构建 + 安装
#   ./build.sh verify       # 构建 + 安装 + 跑验证 + 抓日志
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$HERE/out"
WORK="$HERE/.work"

# ---------- 环境 ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$REPO_ROOT/local.properties" ]; then
  SDK="$(grep -E '^sdk\.dir=' "$REPO_ROOT/local.properties" | cut -d= -f2- | tr -d '\r')"
fi
[ -z "$SDK" ] && { echo "✗ 找不到 Android SDK" >&2; exit 1; }
SDK="${SDK//\\//}"
echo "SDK: $SDK"

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"; BT="${BT%/}"
[ -z "$BT" ] && { echo "✗ 找不到 build-tools" >&2; exit 1; }
echo "build-tools: $BT"

PLATFORM="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1)"; PLATFORM="${PLATFORM%/}"
ANDROID_JAR="$PLATFORM/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "✗ 找不到 $ANDROID_JAR" >&2; exit 1; }
echo "android.jar: $ANDROID_JAR"

AAPT2="$BT/aapt2"
pick() { [ -f "$1.bat" ] && echo "$1.bat" || echo "$1"; }
D8="$(pick "$BT/d8")"
ZIPALIGN="$(pick "$BT/zipalign")"
APKSIGNER="$(pick "$BT/apksigner")"
echo "d8=$D8"

# ---------- 签名 ----------
JKS="$REPO_ROOT/vFlow.jks"
SIGNPROPS="$REPO_ROOT/signing.properties"
HAVE_VFLOW_SIG=0
if [ -f "$JKS" ] && [ -f "$SIGNPROPS" ]; then
  # ⚠️ 不要用 `cut -d= -f2- | tr -d '\r'`：signing.properties 是 CRLF，
  #    而密码含 * % ^ @ 等字符，tr 的字符集语义会把它弄坏
  #    （症状：apksigner 报 "keystore password was incorrect"，但 keytool 能解开）。
  #    用 bash 原生参数展开逐行取，只剥掉行尾 CR。
  while IFS= read -r _line; do
    _line="${_line%$'\r'}"
    case "$_line" in
      KEYSTORE_PASSWORD=*) KS_PASS="${_line#KEYSTORE_PASSWORD=}" ;;
      KEYSTORE_ALIAS=*)    KS_ALIAS="${_line#KEYSTORE_ALIAS=}" ;;
      KEY_PASSWORD=*)      KEY_PASS="${_line#KEY_PASSWORD=}" ;;
    esac
  done < "$SIGNPROPS"
  if [ -n "${KS_PASS:-}" ] && [ -n "${KS_ALIAS:-}" ]; then
    HAVE_VFLOW_SIG=1
    echo "✓ vFlow.jks 就位（alias=$KS_ALIAS）—— fake-vflow 将与真 vFlow 同签"
  else
    echo "⚠️ signing.properties 解析不出 KEYSTORE_PASSWORD/ALIAS"
  fi
else
  echo "⚠️ 无 vFlow.jks/signing.properties —— fake-vflow 无同签，Q2/Q3 预期会反转"
fi

mkdir -p "$OUT"

# ---------- 编译 + 打包 ----------
# $1=srcDir $2=manifest $3=outApk $4=vflow|debug
build_apk() {
  local srcDir="$1" manifest="$2" outApk="$3" signMode="$4"
  local name; name="$(basename "$outApk" .apk)"
  local w="$WORK/$name"
  rm -rf "$w"; mkdir -p "$w/classes"

  echo "── [$name] javac ──"
  local sources; sources="$(find "$srcDir" -name '*.java')"
  [ -z "$sources" ] && { echo "✗ $srcDir 下没有 .java" >&2; return 1; }
  # ⚠️ 不要加 -source/-target/-bootclasspath：JDK 21 已废弃 -bootclasspath，
  #    且 -source 17 会连带要求 --system，直接让编译失败（已实际踩过）。
  #    Android 类只放 -classpath，默认 target 即可。
  # shellcheck disable=SC2086
  javac -nowarn -classpath "$ANDROID_JAR" -d "$w/classes" $sources
  [ -n "$(find "$w/classes" -name '*.class' 2>/dev/null)" ] || { echo "✗ javac 没产出 class" >&2; return 1; }

  echo "── [$name] d8 ──"
  # ⚠️ 显式列出所有 *.class（含内部类 $Xxx.class）
  local classList; classList="$(find "$w/classes" -name '*.class')"
  # shellcheck disable=SC2086
  "$D8" --min-api 29 --output "$w" $classList
  [ -f "$w/classes.dex" ] || { echo "✗ d8 没产出 classes.dex" >&2; return 1; }

  echo "── [$name] aapt2 link ──"
  "$AAPT2" link -o "$w/base.apk" -I "$ANDROID_JAR" \
       --manifest "$manifest" --min-sdk-version 29 --target-sdk-version 36 \
       --auto-add-overlay

  echo "── [$name] 注入 classes.dex + zipalign ──"
  # ⚠️ jar 要在 classes.dex 所在目录执行（d8 把 dex 写在 $w/ 下）
  cp "$w/base.apk" "$w/merged.apk"
  ( cd "$w" && jar uf merged.apk classes.dex ) \
    || { echo "✗ 注入 dex 失败" >&2; return 1; }
  "$ZIPALIGN" -f -p 4 "$w/merged.apk" "$w/aligned.apk" \
    || { echo "✗ zipalign 失败" >&2; return 1; }

  echo "── [$name] 签名 ──"
  if [ "$signMode" = "vflow" ] && [ "$HAVE_VFLOW_SIG" = "1" ]; then
    # ⚠️ 必须用 env: 前缀，不能用 "pass:$XXX"：
    #    vFlow 的 keystore 密码含 * % ^ @ 等字符，写成 "pass:ZHmXZ*gv*..." 时
    #    星号会被 shell 当 glob 展开，密码被改坏 —— 症状是 apksigner 报
    #    "keystore password was incorrect"，但同一个密码 keytool 却能解开。
    #    env: 把值放进环境变量，绕开命令行解析。（已实际踩过）
    export VF_KS_PASS="$KS_PASS" VF_KEY_PASS="$KEY_PASS"
    "$APKSIGNER" sign --ks "$JKS" --ks-key-alias "$KS_ALIAS" \
        --ks-pass env:VF_KS_PASS --key-pass env:VF_KEY_PASS \
        --out "$outApk" "$w/aligned.apk"
    unset VF_KS_PASS VF_KEY_PASS
  else
    local dbg="$HOME/.android/debug.keystore"
    if [ ! -f "$dbg" ]; then
      keytool -genkeypair -keystore "$dbg" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
    fi
    "$APKSIGNER" sign --ks "$dbg" --ks-key-alias androiddebugkey \
        --ks-pass pass:android --key-pass pass:android \
        --out "$outApk" "$w/aligned.apk"
  fi
  echo "✓ $outApk"
}

# ---------- 对照变体：给 fake-hook 加 <queries> ----------
# 目的：排除「包不可见」这个混淆变量，好单独观察【权限】的效果。
#
# 实现方式：只改 manifest（加 queries），源码一个字不动 —— 因为 queries
# 只影响「能否看到对方包」，不改任何代码逻辑。为了能与原 fake-hook 共存，
# 用 aapt2 的 --rename-manifest-package 换包名（而不是 sed 源码）。
make_queries_variant_apk() {
  local dst="$WORK/queries-var/AndroidManifest.xml"
  mkdir -p "$WORK/queries-var"
  sed 's#</manifest>#    <queries><package android:name="com.vflow.hookprobe.vflow"/></queries>\n</manifest>#' \
      "$HERE/fake-hook/AndroidManifest.xml" > "$dst"
  grep -q "<queries>" "$dst" || { echo "✗ 插入 queries 失败" >&2; return 1; }
  echo "✓ 对照 manifest 已生成（含 queries）"
}

# 用 --rename-manifest-package 打包对照变体（复用同一份源码）
build_apk_renamed() {
  local srcDir="$1" manifest="$2" outApk="$3" signMode="$4" newPkg="$5"
  local name; name="$(basename "$outApk" .apk)"
  local w="$WORK/$name"; rm -rf "$w"; mkdir -p "$w/classes"

  echo "── [$name] javac ──"
  javac -nowarn -classpath "$ANDROID_JAR" -d "$w/classes" \
        $(find "$srcDir" -name '*.java')
  echo "── [$name] d8 ──"
  "$D8" --min-api 29 --output "$w" $(find "$w/classes" -name '*.class')
  echo "── [$name] aapt2 link (rename → $newPkg) ──"
  "$AAPT2" link -o "$w/base.apk" -I "$ANDROID_JAR" --manifest "$manifest" \
       --min-sdk-version 29 --target-sdk-version 36 --auto-add-overlay \
       --rename-manifest-package "$newPkg"
  cp "$w/base.apk" "$w/merged.apk"
  ( cd "$w" && jar uf merged.apk classes.dex )
  "$ZIPALIGN" -f -p 4 "$w/merged.apk" "$w/aligned.apk"
  local dbg="$HOME/.android/debug.keystore"
  "$APKSIGNER" sign --ks "$dbg" --ks-key-alias androiddebugkey \
      --ks-pass pass:android --key-pass pass:android \
      --out "$outApk" "$w/aligned.apk"
  echo "✓ $outApk"
}

# ---------- 设备操作 ----------
ADB_FLAGS=(-r -t)
install_both() {
  echo "── 安装 ──"
  adb install "${ADB_FLAGS[@]}" "$OUT/fake-vflow.apk"
  adb install "${ADB_FLAGS[@]}" "$OUT/fake-hook.apk"
  [ -f "$OUT/fake-hook-withqueries.apk" ] && \
    echo "（对照变体 fake-hook-withqueries.apk 与 fake-hook 同包名，装它会覆盖，需分开测）"
}

# ---------- 主流程 ----------
MODE="${1:-}"
build_apk "$HERE/fake-vflow/src" "$HERE/fake-vflow/AndroidManifest.xml" \
          "$OUT/fake-vflow.apk" vflow
build_apk "$HERE/fake-hook/src" "$HERE/fake-hook/AndroidManifest.xml" \
          "$OUT/fake-hook.apk" debug

if [ "$MODE" = "queries" ] || [ "$MODE" = "verify" ]; then
  make_queries_variant_apk
  build_apk_renamed "$HERE/fake-hook/src" "$WORK/queries-var/AndroidManifest.xml" \
            "$OUT/fake-hook-withqueries.apk" debug "com.vflow.hookprobe.hookq"
fi

case "$MODE" in
  install|verify) install_both ;;
esac

echo
echo "══ 产出 ══"
ls -la "$OUT"/*.apk
