#!/usr/bin/env bash
# Offline APK builder for SimWheel Phone.
#
# This builds the app WITHOUT the Android Gradle Plugin or the Android SDK,
# using a minimal toolchain (aapt2 + android.jar + javac + dx + a signer).
# It exists because some environments can't reach Google's Maven (where AGP and
# the SDK live). The app deliberately uses only the Android framework (no
# AndroidX), so this works.
#
# Tools are expected in $TOOLS (default: /tmp/apkbuild), downloaded by
# tools/fetch_buildtools.sh:
#   aapt2                 (extracted from apktool)
#   android.jar           (API 34 stubs)
#   dalvik-dx.jar         (Jake Wharton's repackaged dx, Java 8 capable)
#   uber-apk-signer.jar   (zipalign + v1/v2/v3 signing)
#
# Usage: tools/build_apk.sh [output.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-/tmp/apkbuild}"
OUT="${1:-$ROOT/SimWheel-debug.apk}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

AAPT2="$TOOLS/aapt2"
ANDROID_JAR="$TOOLS/android.jar"
DX="$TOOLS/dalvik-dx.jar"
SIGNER="$TOOLS/uber-apk-signer.jar"

APP="$ROOT/app/src/main"
MANIFEST="$APP/AndroidManifest.xml"
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE=1
VERSION_NAME=1.0

echo ">> [1/6] aapt2 compile resources"
mkdir -p "$WORK/compiled"
"$AAPT2" compile --dir "$APP/res" -o "$WORK/compiled/res.zip"

echo ">> [2/6] aapt2 link -> base APK + R.java"
mkdir -p "$WORK/gen"
"$AAPT2" link \
    -o "$WORK/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --java "$WORK/gen" \
    "$WORK/compiled/res.zip" \
    --auto-add-overlay

echo ">> [3/6] javac (app + generated R.java) -> .class"
mkdir -p "$WORK/classes"
find "$APP/java" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$WORK/classes" \
    @"$WORK/sources.txt" 2>"$WORK/javac.log" || { cat "$WORK/javac.log"; exit 1; }
grep -v "obsolete\|To suppress\|warning" "$WORK/javac.log" || true

echo ">> [4/6] dx -> classes.dex"
java -cp "$DX" com.android.dx.command.Main --dex \
    --min-sdk-version="$MIN_SDK" \
    --output="$WORK/classes.dex" "$WORK/classes"

echo ">> [5/6] package dex into APK"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
( cd "$WORK" && zip -uj unsigned.apk classes.dex >/dev/null )

echo ">> [6/6] zipalign + sign (debug)"
java -jar "$SIGNER" -a "$WORK/unsigned.apk" --allowResign -o "$WORK/signed" >/dev/null
cp "$WORK"/signed/*.apk "$OUT"

echo ""
echo ">> DONE: $OUT"
unzip -l "$OUT" | grep -E "classes.dex|resources.arsc|AndroidManifest" || true
ls -la "$OUT"
