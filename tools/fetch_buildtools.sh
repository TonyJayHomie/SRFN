#!/usr/bin/env bash
# Fetch the minimal offline APK toolchain into $TOOLS (default /tmp/apkbuild).
#
# None of these come from Google's Maven / the Android SDK, so this works in
# environments that can only reach Maven Central + GitHub:
#   - apktool        (GitHub)        -> we extract its bundled linux aapt2
#   - android.jar    (GitHub mirror) -> API 34 compile stubs
#   - dalvik-dx      (Maven Central) -> Java-8-capable dexer
#   - uber-apk-signer(GitHub)        -> zipalign + v1/v2/v3 signing
set -euo pipefail

TOOLS="${TOOLS:-/tmp/apkbuild}"
mkdir -p "$TOOLS"
cd "$TOOLS"

APKTOOL_VER="2.9.3"
DX_VER="16.0.1"
SIGNER_VER="1.3.0"
ANDROID_JAR_URL="https://github.com/Sable/android-platforms/raw/master/android-34/android.jar"

dl() { echo ">> fetching $2"; curl -fsSL -m 180 -o "$1" "$2"; }

[ -f apktool.jar ]          || dl apktool.jar          "https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VER}/apktool_${APKTOOL_VER}.jar"
[ -f dalvik-dx.jar ]        || dl dalvik-dx.jar        "https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/${DX_VER}/dalvik-dx-${DX_VER}.jar"
[ -f uber-apk-signer.jar ]  || dl uber-apk-signer.jar  "https://github.com/patrickfav/uber-apk-signer/releases/download/v${SIGNER_VER}/uber-apk-signer-${SIGNER_VER}.jar"
[ -f android.jar ]          || dl android.jar          "$ANDROID_JAR_URL"

# Extract the bundled linux aapt2 from apktool.
if [ ! -x aapt2 ]; then
    echo ">> extracting aapt2 from apktool"
    unzip -o -j apktool.jar "prebuilt/linux/aapt2_64" -d . >/dev/null
    mv -f aapt2_64 aapt2
    chmod +x aapt2
fi

echo ">> toolchain ready in $TOOLS:"
ls -la aapt2 android.jar dalvik-dx.jar uber-apk-signer.jar
./aapt2 version
