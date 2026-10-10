#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

echo "== Dylan full gate (SOTA) =="
echo "VERSION=$(cat VERSION)  versionCode via git rev-list --count HEAD"
./gradlew wrapper --version 2>&1 | head -n 4 || true

echo ""
echo "→ ktlintCheck + detekt + Android lint"
./gradlew ktlintCheck detekt :androidApp:lintDebug

echo ""
echo "→ jvmTest (--rerun: force the test task only, leave compilation cached)"
./gradlew :shared:jvmTest --rerun

if [ "${DYLAN_LIVE_GATES:-0}" = "1" ]; then
  echo ""
  echo "→ live network gates (DYLAN_LIVE_GATES=1): probeCi + contractDrift"
  ./gradlew :shared:probeCi
  ./gradlew :shared:contractDrift
else
  echo ""
  echo "→ skipping :shared:probeCi + :shared:contractDrift (both hit the live JioSaavn API)"
  echo "  re-run with DYLAN_LIVE_GATES=1 to include them"
fi

echo ""
echo "→ assembleDebug + assembleRelease (R8) + iOS klibs"
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64

echo ""
echo "→ artifacts"
shopt -s nullglob
apks=(androidApp/build/outputs/apk/debug/*.apk androidApp/build/outputs/apk/release/*.apk)
if [ "${#apks[@]}" -eq 0 ]; then
  echo "MISSING ARTIFACTS: no APK under androidApp/build/outputs/apk/{debug,release}"
  exit 1
fi
for apk in "${apks[@]}"; do
  if [ ! -s "$apk" ]; then
    echo "MISSING/EMPTY ARTIFACT: $apk"
    exit 1
  fi
done
ls -lh "${apks[@]}"
shasum -a 256 "${apks[@]}"

echo ""
echo "✅ All gates passed — VERSION $(cat VERSION) $(git rev-parse --short HEAD 2>/dev/null || echo dev)"
