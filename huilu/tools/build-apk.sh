#!/usr/bin/env bash
# 备用构建：不需要 Android Studio / Android Gradle 插件，直接用 aapt + kotlinc + dx + apksigner 打出可安装的 APK。
# 正常开发请用 Android Studio 或 `gradle :app:assembleDebug`；这个脚本用于没有完整 Android SDK 的环境（例如 CI 沙箱）。
#
# 依赖：JDK 17+、gradle、aapt、dalvik-exchange（或 dx）、zipalign、apksigner、curl、zip
#   Debian/Ubuntu：apt install aapt dalvik-exchange zipalign apksigner zip
# 用法：tools/build-apk.sh [--test]
#   --test  另外在 Robolectric 里跑 App 的集成测试（会额外下载约 350 MB 的 Android 框架包）
# 可选环境变量：
#   ANDROID_JAR  指向某个 platforms/android-34/android.jar；不设则从 Maven Central 下载 Robolectric 的 Android 14 框架包
#   MAVEN_REPO   Maven Central 镜像地址
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/build/fallback
DEPS=$OUT/deps
REPO=${MAVEN_REPO:-https://repo1.maven.org/maven2}
KOTLIN=2.0.21
VERSION_NAME=0.2.0
VERSION_CODE=2
MIN_SDK=26
TARGET_SDK=34
APK=$ROOT/dist/huilu-$VERSION_NAME.apk
RUN_TESTS=0
[ "${1:-}" = "--test" ] && RUN_TESTS=1
GRADLE=${GRADLE:-$([ -x "$ROOT/gradlew" ] && echo "$ROOT/gradlew" || echo gradle)}

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT"/*.apk
mkdir -p "$DEPS" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$ROOT/dist"

fetch() { # group/artifact/version/file
  local dest=$DEPS/$(basename "$1")
  [ -s "$dest" ] || curl -fsSL --retry 5 --retry-delay 3 -o "$dest" "$REPO/$1"
  echo "$dest"
}

ANDROID_JAR=${ANDROID_JAR:-$(fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar)}
STDLIB=$(fetch org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN/kotlin-stdlib-$KOTLIN.jar)
COMPILER_CP=$(fetch org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN/kotlin-compiler-embeddable-$KOTLIN.jar)
COMPILER_CP=$COMPILER_CP:$STDLIB
COMPILER_CP=$COMPILER_CP:$(fetch org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN/kotlin-script-runtime-$KOTLIN.jar)
COMPILER_CP=$COMPILER_CP:$(fetch org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar)
COMPILER_CP=$COMPILER_CP:$(fetch org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN/kotlin-daemon-embeddable-$KOTLIN.jar)
COMPILER_CP=$COMPILER_CP:$(fetch org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar)
COMPILER_CP=$COMPILER_CP:$(fetch org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar)
COMPILER_CP=$COMPILER_CP:$(fetch org/jetbrains/annotations/13.0/annotations-13.0.jar)

# Shizuku 的 AIDL 接口（AAR 里只取 classes.jar；不含 invokedynamic）
SHIZUKU_AAR=$(fetch dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar)
SHIZUKU_JAR=$DEPS/shizuku-aidl-13.1.5.jar
[ -s "$SHIZUKU_JAR" ] || unzip -p "$SHIZUKU_AAR" classes.jar > "$SHIZUKU_JAR"

echo "== core（纯 Kotlin 闭环逻辑）+ 单元测试"
(cd "$ROOT" && "$GRADLE" -q --console=plain :core:test :core:jar)
CORE_JAR=$ROOT/core/build/libs/core.jar

echo "== 资源与清单"
python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
s = s.replace('<manifest ', '<manifest package="huilu.app" ', 1)
open(sys.argv[2], 'w', encoding='utf-8').write(s)
PY
aapt package -f -m --auto-add-overlay \
  -M "$OUT/AndroidManifest.xml" -S "$ROOT/app/src/main/res" -I "$ANDROID_JAR" \
  -J "$OUT/gen" -F "$OUT/base.apk" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME
javac -nowarn -source 8 -target 8 -cp "$ANDROID_JAR" -d "$OUT/classes" "$OUT"/gen/huilu/app/R.java 2>&1 | grep -v -e '^warning' -e 'JAVA_TOOL' || true

echo "== Kotlin 编译"
find "$ROOT/app/src/main/java" -name '*.kt' > "$OUT/sources.txt"
java -Xmx2g -Dfile.encoding=UTF-8 -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class \
  -classpath "$ANDROID_JAR:$CORE_JAR:$STDLIB:$SHIZUKU_JAR:$OUT/classes" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v 'JAVA_TOOL' || true
[ -f "$OUT/classes/huilu/app/App.class" ] || { echo "Kotlin 编译失败"; exit 1; }

echo "== dex"
# dx 不认识 Java 9 的 module-info，去掉
cp "$STDLIB" "$OUT/stdlib.jar" && zip -q -d "$OUT/stdlib.jar" 'META-INF/versions/*' >/dev/null 2>&1 || true
python3 "$ROOT/tools/check-indy.py" "$OUT/stdlib.jar" "$OUT/classes" "$CORE_JAR" "$SHIZUKU_JAR"
DX=$(command -v dalvik-exchange || command -v dx)
"$DX" --dex --min-sdk-version=$MIN_SDK --output="$OUT/dex/classes.dex" "$OUT/classes" "$CORE_JAR" "$SHIZUKU_JAR" "$OUT/stdlib.jar"

echo "== 打包、对齐、签名"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$ROOT/tools/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version $MIN_SDK --out "$APK" "$OUT/aligned.apk"
apksigner verify --min-sdk-version $MIN_SDK "$APK"
rm -f "$APK.idsig"
echo "== 完成：$APK（$(du -h "$APK" | cut -f1)）"

if [ $RUN_TESTS = 1 ]; then
  echo "== Robolectric 集成测试"
  # 版本与 Robolectric 4.14.1 内置的 SDK 列表对应：运行用 14，解析 APK 用最高版本 15
  fetch org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/android-all-instrumented-14-robolectric-10818077-i7.jar >/dev/null
  fetch org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/android-all-instrumented-15-robolectric-12650502-i7.jar >/dev/null
  (cd "$ROOT" && "$GRADLE" --console=plain :robotest:test)
fi
