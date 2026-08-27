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
./gradlew ktlintCheck detekt :androidApp:lintDebug --no-configuration-cache --continue

echo ""
echo "→ jvmTest --rerun-tasks + probeCi + contractDrift"
./gradlew :shared:jvmTest --rerun-tasks :shared:probeCi :shared:contractDrift --no-configuration-cache

echo ""
echo "→ assembleDebug + assembleRelease (R8) + iOS klibs"
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease --no-configuration-cache
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 --no-configuration-cache

echo ""
echo "→ artifacts"
ls -lh androidApp/build/outputs/apk/debug/*.apk androidApp/build/outputs/apk/release/*.apk 2>&1 | head -n 10 || true
shasum -a 256 androidApp/build/outputs/apk/debug/*.apk androidApp/build/outputs/apk/release/*.apk 2>&1 | head -n 10 || true
echo ""
echo "✅ All gates passed — VERSION $(cat VERSION) $(git rev-parse --short HEAD 2>/dev/null || echo dev)"
