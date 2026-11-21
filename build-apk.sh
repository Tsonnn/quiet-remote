#!/bin/bash
# Manual APK build — no Gradle daemon, no plugin downloads.
# Uses only the Android SDK build-tools already installed in this VM:
#   aapt2 (resources) -> javac -> d8 (dex) -> zipalign -> apksigner
# Produces: Quiet-Remote-v47.2.apk (debug-signed, sideload-installable)
# Usage: ./build-apk.sh [output-apk-name]  (default: Quiet-Remote-v47.2.apk)
set -euo pipefail

OUT_APK="${1:-Quiet-Remote-v47.2.apk}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK=/home/hatch/Android/sdk
JAVA_HOME=/home/hatch/tools/jdk-17.0.21+9
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BUILD="$ROOT/build-manual"

export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "== 1. Compile resources (aapt2 compile)"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/compiled.zip"

echo "== 2. Link APK (aapt2 link)"
"$BT/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 50 --version-name 47.2 \
  --auto-add-overlay \
  --java "$BUILD/gen" \
  -o "$BUILD/app-unsigned.apk" \
  "$BUILD/compiled.zip"

echo "== 3. Compile Java (javac)"
find "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
find "$ROOT/app/src/main/java" -name '*.java' >> "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -source 11 -target 11 \
  -cp "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 4. DEX (d8)"
"$JAVA_HOME/bin/jar" --create --file "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"

echo "== 5. Add classes.dex to APK"
(cd "$BUILD/dex" && zip -q -X ../app-unsigned.apk classes.dex)

echo "== 6. Align (zipalign)"
"$BT/zipalign" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "== 7. Debug keystore (created once, reused for rebuilds)"
if [ ! -f "$ROOT/debug.keystore" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$ROOT/debug.keystore" \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi

echo "== 8. Sign (apksigner)"
"$BT/apksigner" sign \
  --ks "$ROOT/debug.keystore" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$ROOT/$OUT_APK" \
  "$BUILD/app-aligned.apk"

echo "== 9. Verify"
"$BT/apksigner" verify --verbose "$ROOT/$OUT_APK"
"$BT/aapt2" dump badging "$ROOT/$OUT_APK"
ls -lh "$ROOT/$OUT_APK"
echo "BUILD OK"
