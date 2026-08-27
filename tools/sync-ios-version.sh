#!/usr/bin/env bash
set -euo pipefail
# sync-ios-version.sh — keeps iosApp MARKETING_VERSION in sync with root VERSION
# Usage: ./tools/sync-ios-version.sh [version]   (defaults to reading VERSION file)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="${1:-$(cat "$ROOT/VERSION" | tr -d '[:space:]')}"
BUILD="$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 0)"
PBX="$ROOT/iosApp/iosApp.xcodeproj/project.pbxproj"

if [[ ! -f "$PBX" ]]; then echo "No pbxproj at $PBX"; exit 0; fi

# MARKETING_VERSION = semver base (0.1.3), CURRENT_PROJECT_VERSION = build number
perl -i -pe "s/MARKETING_VERSION = \"[^\"]+\"/MARKETING_VERSION = \"$VER\"/g" "$PBX"
perl -i -pe "s/CURRENT_PROJECT_VERSION = [0-9]+/CURRENT_PROJECT_VERSION = $BUILD/g" "$PBX"
echo "Synced iOS: MARKETING_VERSION=$VER CURRENT_PROJECT_VERSION=$BUILD"
