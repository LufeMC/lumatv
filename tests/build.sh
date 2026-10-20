#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${JAVA_HOME:?Set JAVA_HOME}"
BT="$ANDROID_HOME/build-tools/36.0.0"
AJ="$ANDROID_HOME/platforms/android-36/android.jar"
PLAYER_CP="$(find out/player/jars -name '*.jar' | paste -sd: -)"
rm -rf out/tests
mkdir -p out/tests/classes
"$BT/aapt2" link -o out/tests/base.apk -I "$AJ" --manifest tests/AndroidManifest.xml -A tests/assets -0 mkv -0 srt
"$JAVA_HOME/bin/javac" -nowarn -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ:out/classes:$PLAYER_CP" -d out/tests/classes tests/*.java
"$BT/d8" --min-api 30 --lib "$AJ" --classpath out/classes --output out/tests $(find out/tests/classes -name '*.class')
(cd out/tests && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/tests/base.apk out/tests/aligned.apk
LUMATV_SIGNING_PASS="${LUMATV_KEY_PASS:-}"
if [ -z "$LUMATV_SIGNING_PASS" ] && command -v security >/dev/null 2>&1; then
  LUMATV_SIGNING_PASS="$(security find-generic-password -s lumatv-keystore -w 2>/dev/null || true)"
fi
export LUMATV_SIGNING_PASS="${LUMATV_SIGNING_PASS:-freemytv}"
"$BT/apksigner" sign --ks freemytv.keystore --ks-pass env:LUMATV_SIGNING_PASS --out out/tests/tests.apk out/tests/aligned.apk
