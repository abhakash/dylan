#!/usr/bin/env bash
# lane-check.sh — CI gate for the W1 lane discipline.
#
# The codebase runs every lane on its own dispatcher (AppDispatchers.Lane) and asserts the lane at
# runtime (`assertInContext`). That assertion catches a violation only once the code actually RUNS on
# the wrong lane — which for a rarely-taken branch means never. These are the lexical gates that fail
# at PR time instead.
#
# Rule 1: in a component that owns a lane, `scope.launch {` must say which lane it is on. A bare
# `scope.launch {` inherits the caller's dispatcher, so a call made from the wrong lane silently
# runs on the wrong lane — the exact class of bug the Lane type exists to make unrepresentable.
#
# Rule 2: commonMain must not call the thread-scoped `AppDispatchers.assert`. On iOS the
# thread-scoped lane slot is never published, so that call always throws. See below.
#
# The checks are deliberately narrow so they have no false positives on the existing tree:
#   - rule 1 only inspects commonMain (where the shared, lane-confined components live),
#   - it only flags a bare `scope.launch {` that is NOT `scope.launch(disp.on(Lane.X)) {`,
#   - and every currently-accepted exception is listed explicitly below, with the reason it is
#     safe. An exception is a decision to be reviewed, not a suppression to be added quietly.
#
# Adding a new bare `scope.launch {` means adding an entry here, which puts the justification in
# the diff where a reviewer sees it.
#
# Why exceptions are content-anchored, not line-anchored
# -----------------------------------------------------
# This was originally a "file:line" allowlist that re-checked the line still held a bare launch. The
# check was right and the shape was wrong: a line-numbered allowlist breaks whenever an unrelated
# edit above it shifts the line, so a concurrent refactor turns the gate red for reasons no reviewer
# can act on — and the tempting response is to "fix" the number, discarding the justification the
# entry exists to preserve. Observed in practice: an unrelated 113-line change to Orchestrator.kt
# moved three allowances and failed the gate on code nobody had touched.
#
# An exception is therefore keyed on the *source text* of the launch line plus the line after it —
# a fingerprint, not a location. That survives a refactor that moves the code, and is still
# invalidated by a change to the construct itself. The following line is needed because several
# bare launches share identical text (`scope.launch {` occurs three times in Orchestrator.kt), so
# the launch line alone cannot tell them apart.
#
# Two properties keep the list honest, and both are checked below:
#   * a fingerprint must be unique — two bare launches claiming the same one means a second site
#     was waved through, or an entry is dead weight;
#   * every entry must match at least one bare launch — an allowance guarding nothing is a lie.
#
# POSIX-ish on purpose: macOS ships bash 3.2, so no associative arrays.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/shared/src/commonMain/kotlin/dylan"
# Second scan root, for F-09. The rule is about launches on a **component** scope, and
# `androidApp`'s service container is the only such scope outside shared. Its pattern is narrower
# than shared's, on purpose: a Compose UI scope is a plain `rememberCoroutineScope()` with no lane
# behind it, so a gate that also flagged those would be wrong rather than merely blind. The two
# roots are therefore not redundant -- the shared pattern matches a bare `scope.launch {`, which
# androidApp does not contain, and vice versa.
ANDROID="$ROOT/androidApp/src/main/kotlin"

if [[ ! -d "$SRC" ]]; then
  echo "lane-check: source root not found at $SRC" >&2
  exit 1
fi

if [[ ! -d "$ANDROID" ]]; then
  echo "lane-check: second source root not found at $ANDROID" >&2
  exit 1
fi

# "path|launch line text|next line text|reason"
# Both texts are trimmed and matched verbatim.
ALLOWED=(
  "diag/FileLogSink.kt|scope.launch { drainLoop() }|}|the log sink's own writer loop; the scope is already IO-bound"
  "playback/Orchestrator.kt|scope.launch {|e.events.collect { post(Msg.E(it)) }|fire-and-forget notify into the state inbox (the P0-4 waiter wake); post() is the inbox's own trySend"
  "playback/Orchestrator.kt|scope.launch {|e.positionFlow.collect {|fire-and-forget notify into the state inbox"
  "playback/Orchestrator.kt|scope.launch {|var lastPos = 0L|fire-and-forget wake of the session watcher loop"
  "download/DownloadEngine.kt|repeat(WORKER_COUNT) { index -> scope.launch { worker(index) } }|}|constructs the worker slots; this call defines the lane"
  "download/DownloadEngine.kt|scope.launch { library.writeIntent(job) }|poke()|best-effort intent write; the result is not awaited"
  "download/DownloadEngine.kt|scope.launch { library.writeIntent(job) }|log.i(\"dl\", \"preempt \${result.victim.label} for \${job.label}\")|best-effort intent write; the result is not awaited"
  "download/DownloadEngine.kt|scope.launch { sweepParts() }|}|part-sweep loop, launched from the engine's own start()"
  "download/DownloadEngine.kt|if (!keepPart) scope.launch { parts.deleteParts(key) }|}|best-effort part cleanup after a non-kept part"
  "download/DownloadEngine.kt|if (!keepPart) scope.launch { parts.deleteParts(job.key) }|}|best-effort part cleanup after a non-kept part"
  "download/DownloadEngine.kt|scope.launch {|delay(wait)|failure reporting; the result is not awaited"
  # ---- androidApp (second scan root, F-09) ------------------------------------------------------
  # All three are in DylanMediaService.kt and all three are *decisions*, not suppressions: each body
  # was read end to end before being listed, and each says what would make the entry wrong.
  # They are here rather than named because the state collector was the only one this change was
  # scoped to fix; the other three are what the extension surfaced and nobody had reviewed before
  # it. Naming the lane on them is the right eventual outcome and is a separate change.
  "dylan/android/media/DylanMediaService.kt|container.scope.launch {|e.audioRoute.collect { app.mediaHub.publish(it) }|routeJob: the body writes a MutableStateFlow via MediaHub.publish and collects a StateFlow; nothing in it asserts a lane, so the missing tag costs a tag and nothing else"
  "dylan/android/media/DylanMediaService.kt|container.scope.launch { pending.set(computeResumptionItems()) }|pending|the resumption pre-warm's catch-up fill: computeResumptionItems takes dbLane itself for the settings read and the row pass, so nothing here needs an ambient state tag"
  "dylan/android/media/DylanMediaService.kt|container.scope.launch {|val items = computeResumptionItems()|prewarmResume: same as above - one explicit dbLane hop inside, no lane assertion anywhere in the body"
)

violations=0
SEEN="$(mktemp)"
trap 'rm -f "$SEEN"' EXIT

trim() { printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'; }

# How many ALLOWED entries claim this fingerprint ("rel<TAB>a<TAB>b").
claims() {
  local want="$1" n=0 entry e_rel e_rest e_a e_b
  for entry in "${ALLOWED[@]}"; do
    e_rel="${entry%%|*}"
    e_rest="${entry#*|}"
    e_a="${e_rest%%|*}"
    e_b="${e_rest#*|}"; e_b="${e_b%%|*}"
    if [[ "$e_rel"$'\t'"$e_a"$'\t'"$e_b" == "$want" ]]; then
      n=$((n + 1))
    fi
  done
  printf '%s' "$n"
}

# ---- rule 1: a bare `scope.launch {` must name its lane -----------------------------------------
#
# Emits "<dir>|<rel>:<line>" for every launch in [dir] matching [pattern], so one loop can walk
# both roots and still resolve each hit against the root it came from.
scan_launches() {
  local dir="$1" pattern="$2"
  (cd "$dir" && grep -rn "$pattern" . --include="*.kt" | sed 's|^\./||' | awk -v d="$dir" '{print d "|" $0}')
}

while IFS= read -r hit; do
  [[ -z "$hit" ]] && continue
  from="${hit%%|*}"
  rest="${hit#*|}"
  rel="${rest%%:*}"
  line="${rest#*:}"; line="${line%%:*}"
  a="$(trim "$(sed -n "${line}p" "$from/$rel")")"
  b="$(trim "$(sed -n "$((line + 1))p" "$from/$rel")")"
  fp="$rel"$'\t'"$a"$'\t'"$b"
  printf '%s\n' "$fp" >>"$SEEN"

  n="$(claims "$fp")"
  if [[ "$n" -eq 0 ]]; then
    echo "lane-check: bare launch at $rel:$line - say which lane with 'scope.launch(disp.on(Lane.X)) {'."
    echo "            If this one is genuinely safe, add it to ALLOWED in tools/lane-check.sh WITH a reason."
    violations=$((violations + 1))
  elif [[ "$n" -gt 1 ]]; then
    echo "lane-check: $n ALLOWED entries all match $rel:$line ($a) - one of them is dead weight."
    violations=$((violations + 1))
  fi
done < <(scan_launches "$SRC" "scope\.launch {" && scan_launches "$ANDROID" "\.scope\.launch {")

# ---- rule 2: no thread-scoped `assert` in commonMain --------------------------------------------
#
# `AppDispatchers.assert` reads the per-thread slot that `lanePublication` writes. On iOS that
# publication is a no-op by construction — there is no portable "a coroutine is resuming on this
# thread" hook (kotlinx-coroutines#4208 is still open) — so the iOS `slot` is permanently empty and
# `assert(lane)` ALWAYS throws `LaneViolation`. It is callable from commonMain at all only because
# the recent `kotlin.assert` → `LaneViolation` change turned it into a real common function; before
# that it was JVM-only and did not resolve there.
#
# So one `disp.assert(Lane.X)` in shared code is not a style nit: it is a guaranteed, unconditional
# crash on the iOS target while passing silently on JVM and Android. Production code asserts with
# `assertInContext`, which reads the coroutine-scoped `LaneTag` and works on every target. Only
# commonMain is scanned — in a platform source set `assert` is legitimate.
while IFS= read -r hit; do
  [[ -z "$hit" ]] && continue
  echo "lane-check: thread-scoped assert at ${hit#*:} — it cannot succeed on iOS, where the lane slot is"
  echo "            never published (see AppDispatchers.assert). Use disp.assertInContext(lane), which is"
  echo "            coroutine-scoped and works on every target."
  violations=$((violations + 1))
done < <(cd "$SRC" && grep -rnE "\.assert\([^)]*\bLane\.[A-Z]+" . --include="*.kt" | sed 's|^\./||')

# ---- stale allowance entries ---------------------------------------------------------------------

# An allowance matching no bare launch guards nothing, so the next edit on a line like it would be
# waved through for a reason that no longer exists.
for entry in "${ALLOWED[@]}"; do
  e_rel="${entry%%|*}"
  e_rest="${entry#*|}"
  e_a="${e_rest%%|*}"
  e_b="${e_rest#*|}"; e_b="${e_b%%|*}"
  # Either root: the fingerprint carries only the path relative to its own root, so which root a
  # file belongs to has to be tried rather than known.
  if [[ ! -f "$SRC/$e_rel" && ! -f "$ANDROID/$e_rel" ]]; then
    echo "lane-check: ALLOWED entry for $e_rel ($e_a) refers to a file that no longer exists."
    violations=$((violations + 1))
    continue
  fi
  if ! grep -qxF "$e_rel"$'\t'"$e_a"$'\t'"$e_b" "$SEEN"; then
    echo "lane-check: ALLOWED entry for $e_rel ($e_a / $e_b) matches no bare launch - it guards nothing."
    echo "            Drop the entry, or update its texts to the construct it was granted for."
    violations=$((violations + 1))
  fi
done

if [[ "$violations" -ne 0 ]]; then
  echo "lane-check: $violations problem(s). See docs/build-and-gates.md for why this gate exists."
  exit 1
fi

echo "lane-check: OK — every non-exceptioned launch names its lane, and no commonMain code asserts through the thread-scoped slot."