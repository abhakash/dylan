#!/usr/bin/env bash
set -euo pipefail
# bump-version.sh — semver helper for Dylan (VERSION file is source of truth)
# Usage: ./tools/bump-version.sh [patch|minor|major|show]   (default: patch)
# In CI this is called automatically after merge to main; locally use to cut a release.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT/VERSION"
MODE="${1:-patch}"

current="$(cat "$VERSION_FILE" | tr -d '[:space:]')"
IFS='.' read -r MAJ MIN PAT <<<"$current"

case "$MODE" in
  show)
    echo "$current"
    exit 0
    ;;
  patch) PAT=$((PAT+1)) ;;
  minor) MIN=$((MIN+1)); PAT=0 ;;
  major) MAJ=$((MAJ+1)); MIN=0; PAT=0 ;;
  *) echo "unknown mode: $MODE (use patch|minor|major|show)"; exit 1 ;;
esac

next="$MAJ.$MIN.$PAT"
echo "$next" > "$VERSION_FILE"
echo "Bumped $current -> $next"

# sync iOS MARKETING_VERSION / CURRENT_PROJECT_VERSION
if [[ -f "$ROOT/tools/sync-ios-version.sh" ]]; then
  bash "$ROOT/tools/sync-ios-version.sh" "$next" || true
fi

# optional: auto-commit if inside git
if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if [[ -n "$(git -C "$ROOT" status --porcelain "$VERSION_FILE")" ]] || git -C "$ROOT" diff --quiet -- "$VERSION_FILE" 2>/dev/null; then
    :
  fi
fi
echo "Next: git add VERSION iosApp/... && git commit -m \"chore(release): v$next\" && git tag v$next"
