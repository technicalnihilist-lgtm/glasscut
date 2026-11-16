#!/bin/bash
set -euo pipefail

export JAVA_HOME=/scratch/work/jdk
export PATH=$JAVA_HOME/bin:$PATH
export TMPDIR=/scratch/work/tmp
export JAVA_TOOL_OPTIONS="-Xmx200m -Xss512k -XX:+UseSerialGC -XX:ReservedCodeCacheSize=16m -XX:CompressedClassSpaceSize=24m -XX:MaxMetaspaceSize=72m"
mkdir -p "$TMPDIR"

SDK=/scratch/work/sdk
BT=$SDK/android-14
AJAR=$SDK/platforms/android-34/android.jar
APP=/scratch/work/app
BUILD=/scratch/work/build
OUT=${1:-/scratch/work/GlassCut.apk}
KS=/scratch/work/glasscut-release.jks

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# version comes from the manifest: one source of truth, no drift between the
# manifest and the aapt2 link flags
VCODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$APP/AndroidManifest.xml")
VNAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml")
echo "version $VNAME ($VCODE)"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  -A "$APP/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VCODE" \
  --version-name "$VNAME" \
  --auto-add-overlay

echo "[3/6] javac"
find "$APP/java" -name "*.java" > "$BUILD/srcs.txt"
find "$BUILD/gen" -name "*.java" >> "$BUILD/srcs.txt"
javac -J-Xmx220m -J-Xss512k -J-XX:+UseSerialGC -J-XX:ReservedCodeCacheSize=24m \
  -J-XX:CompressedClassSpaceSize=32m -J-XX:MaxMetaspaceSize=96m -J-XX:MaxDirectMemorySize=16m \
  -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -classpath "$AJAR" \
  -d "$BUILD/classes" @"$BUILD/srcs.txt" 2>&1 | grep -v "bootstrap class path" || true

echo "[4/6] d8"
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.txt"
"$BT/d8" -JXmx220m -JXss512k -JXX:+UseSerialGC -JXX:ReservedCodeCacheSize=24m \
  -JXX:CompressedClassSpaceSize=32m -JXX:MaxMetaspaceSize=96m -JXX:MaxDirectMemorySize=16m \
  --lib "$AJAR" --min-api 24 --release --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[5/6] package + align"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import sys, zipfile, shutil
base, dex, out = sys.argv[1:4]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("classes.dex packed")
PY
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  keytool -J-Xmx160m -J-Xss512k -J-XX:+UseSerialGC -J-XX:ReservedCodeCacheSize=16m \
    -J-XX:CompressedClassSpaceSize=24m -J-XX:MaxMetaspaceSize=72m \
    -genkeypair -v -keystore "$KS" -alias glasscut \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass glasscut -keypass glasscut \
    -dname "CN=GlassCut, OU=Mobile, O=GlassCut, L=Mumbai, ST=Maharashtra, C=IN" >/dev/null 2>&1
  echo "keystore created"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:glasscut --key-pass pass:glasscut \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$BUILD/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT" | head -4
ls -lh "$OUT"
echo "BUILD OK"
