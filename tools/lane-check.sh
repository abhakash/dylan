#!/usr/bin/env bash
# lane-check.sh — CI gate for the W1 lane discipline.
#
# The codebase runs every lane on its own dispatcher (AppDispatchers.Lane) and asserts the lane at
# runtime (`assertInContext`). That assertion catches a violation only once the code actually RUNS on
# the wrong lane — which for a rarely-taken branch means never. This is the lexical gate that fails
# at PR time instead.
#
# Rule: in a component that owns a lane, `scope.launch {` must say which lane it is on. A bare
# `scope.launch {` inherits the caller's dispatcher, so a call made from the wrong lane silently
# runs on the wrong lane — the exact class of bug the Lane type exists to make unrepresentable.
#
# The check is deliberately narrow so it has no false positives on the existing tree:
#   - it only inspects commonMain (where the shared, lane-confined components live),
#   - it only flags a bare `scope.launch {` that is NOT `scope.launch(disp.on(Lane.X)) {`,
#   - and every currently-accepted exception is listed explicitly below, with the reason it is
#     safe. An exception is a decision to be reviewed, not a suppression to be added quietly.
#
# Adding a new bare `scope.launch {` means adding an entry here, which puts the justification in
# the diff where a reviewer sees it.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/shared/src/commonMain/kotlin/dylan"

if [[ ! -d "$SRC" ]]; then
  echo "lane-check: source root not found at $SRC" >&2
  exit 1
fi

# "path:line" -> why a bare scope.launch is correct there.
# Each was reviewed; keep the reason specific enough to fail a future review.
ALLOWED=(
  "diag/FileLogSink.kt:56|the log sink's own writer loop; the scope is already IO-bound"
  "playback/Orchestrator.kt:189|fire-and-forget notify into the state inbox (the P0-4 waiter wake)"
  "playback/Orchestrator.kt:193|fire-and-forget notify into the state inbox"
  "playback/Orchestrator.kt:1109|fire-and-forget wake of a background loop"
  "download/DownloadEngine.kt:255|constructs the worker slots; this call defines the lane"
  "download/DownloadEngine.kt:269|best-effort intent write; the result is not awaited"
  "download/DownloadEngine.kt:274|best-effort intent write; the result is not awaited"
  "download/DownloadEngine.kt:293|part-sweep loop, launched from the engine's own start()"
  "download/DownloadEngine.kt:329|best-effort part cleanup after a non-kept part"
  "download/DownloadEngine.kt:347|best-effort part cleanup after a non-kept part"
  "download/DownloadEngine.kt:418|failure reporting; the result is not awaited"
  "download/DownloadEngine.kt:625|failure reporting; the result is not awaited"
)

violations=0

is_allowed() {
  local key="$1" entry
  for entry in "${ALLOWED[@]}"; do
    [[ "${entry%%|*}" == "$key" ]] && return 0
  done
  return 1
}

while IFS= read -r hit; do
  [[ -z "$hit" ]] && continue
  file="${hit%%:*}"
  rest="${hit#*:}"
  line="${rest%%:*}"
  rel="${file#"$SRC"/}"
  key="$rel:$line"
  if ! is_allowed "$key"; then
    echo "lane-check: bare 'scope.launch {' at $rel:$line - say which lane with 'scope.launch(disp.on(Lane.X)) {'."
    echo "            If this one is genuinely safe, add it to ALLOWED in tools/lane-check.sh WITH a reason."
    violations=$((violations + 1))
  fi
done < <(cd "$SRC" && grep -rn "scope\.launch {" . --include="*.kt" | sed 's|^\./||')

# Stale entries: an allowance whose line no longer contains a bare launch has drifted, so the
# exception is now guarding nothing and the next edit on that line will be waved through.
for entry in "${ALLOWED[@]}"; do
  key="${entry%%|*}"
  rel="${key%:*}"
  line="${key##*:}"
  file="$SRC/$rel"
  if [[ ! -f "$file" ]]; then
    echo "lane-check: ALLOWED entry $key refers to a file that no longer exists."
    violations=$((violations + 1))
    continue
  fi
  actual="$(sed -n "${line}p" "$file")"
  if [[ "$actual" != *"scope.launch {"* ]]; then
    echo "lane-check: ALLOWED entry $key has drifted - line is now: ${actual:-<blank>}"
    echo "            Fix the line number or drop the entry; a stale allowance guards nothing."
    violations=$((violations + 1))
  fi
done

if [[ "$violations" -ne 0 ]]; then
  echo "lane-check: $violations problem(s). See docs/build-and-gates.md for why this gate exists."
  exit 1
fi

echo "lane-check: OK — every non-exceptioned launch names its lane."