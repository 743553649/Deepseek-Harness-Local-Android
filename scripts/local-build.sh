#!/data/data/app.dsh.mobile/files/engine/bin/bash
# ============================================================
# 真机本地构建（Android / aarch64，没有可用 NDK 的环境）
#
# 用法：
#   bash scripts/local-build.sh            # 出 debug APK
#   bash scripts/local-build.sh test       # 跑 JVM 单测（CI 里那一步）
#
# 产物：app/build/outputs/apk/debug/app-debug.apk
#
# ⚠️ 本地产物不含 assets/runtime.zip（运行时闭包由 CI 的 collect-runtime 注入），
#    装上去引擎起不来 —— 它只用来验证「编译链路通」。要能用的包，走 CI。
#    详见 docs/local-android-build.md
# ============================================================
set -eu

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DSH_HOME="${DSH_HOME:-/data/user/0/app.dsh.mobile/files/dsh-home}"
EXT_BIN="/data/user/0/app.dsh.mobile/files/engine/extensions/android-buildtools/bin"

GRADLE_BIN="${GRADLE_BIN:-$DSH_HOME/work/tools/gradle-8.9/bin/gradle}"
AAPT2="${AAPT2:-$EXT_BIN/aapt2}"
SDK="${ANDROID_SDK_ROOT:-$(dirname "$DSH_HOME")/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$DSH_HOME/work/.gradle}"

TASK="assembleDebug"
[ "${1:-}" = "test" ] && TASK="testDebugUnitTest"

# ---- 前置检查：缺什么直接说清楚，别让它跑到一半才报错 ----
[ -x "$GRADLE_BIN" ] || { echo "❌ 找不到 Gradle 8.9：$GRADLE_BIN"; echo "   与 CI 对齐的版本，从镜像下载解压到 $DSH_HOME/work/tools/ 即可"; exit 1; }
[ -x "$AAPT2" ]     || { echo "❌ 找不到 arm64 版 aapt2：$AAPT2"; exit 1; }
[ -d "$SDK/platforms" ] || { echo "❌ 找不到 Android SDK：$SDK"; exit 1; }
[ -d "$SDK/build-tools/34.0.0" ] || { echo "❌ 缺少 build-tools 34.0.0（36.0.0 那份是手工拼的，AGP 会判定损坏）"; exit 1; }

# SDK 路径用 local.properties 告诉 AGP（该文件已在 .gitignore 里，不入库）
if [ ! -f "$HERE/local.properties" ]; then
  printf 'sdk.dir=%s\n' "$SDK" > "$HERE/local.properties"
  echo "已生成 local.properties → $SDK"
fi

echo "Gradle : $GRADLE_BIN"
echo "aapt2  : $AAPT2"
echo "SDK    : $SDK"
echo "任务   : $TASK"
echo

# 三个关键参数：
#   -PusePrebuiltPty                    用 app/prebuilt/pty 的预编译 .so，跳过 CMake（真机没有 arm64 宿主 NDK）
#   -Pabi=arm64-v8a                    与 CI 矩阵一致的真机 ABI
#   -Pandroid.aapt2FromMavenOverride    让 AGP 用 arm64 的 aapt2，否则它会去 Google Maven
#                                       拉一个 x86_64 的，报 "aapt2: syntax error: unexpected '('"
"$GRADLE_BIN" -p "$HERE" "$TASK" \
  -PusePrebuiltPty \
  -Pabi=arm64-v8a \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  --no-daemon --console=plain

APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo
  echo "✅ 产物：$APK（$(wc -c < "$APK") 字节）"
  echo "   校验：$EXT_BIN/apksigner verify --print-certs \"$APK\""
  echo "   提醒：本地产物缺 runtime.zip，装上去引擎起不来，别当可用包分发"
fi
