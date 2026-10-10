#!/usr/bin/env bash
# Falsifiability gate for the dylan.fuzz suite, run in a scratch copy of the tree.
#
# WHY A SCRATCH COPY AND NOT THE WORKING TREE. Every mutation here edits a file another agent owns
# (`dylan.sq`, `1.sqm`, `DriverFactory`, `Transfer`). Mutating the shared tree would put a broken
# build in front of everyone for as long as the run takes, which is exactly the accident the git-state
# warnings in this repo's brief are about. So: rsync the sources (no build dirs) somewhere private,
# mutate *there*, and delete it at the end. Nothing outside $SCRATCH is ever written.
#
# A mutation that does NOT turn its named test red is a FAILURE, not a warning. §5.8 of the plan is
# explicit about this: "a mutation that does not redden its test is a hard failure of the gate, not a
# warning: it means the test named in the table is vacuous, which is precisely the failure mode this
# repo keeps hitting."
#
# Usage:  tools/fuzz-mutation-gate.sh [regex-of-tests]
# Env:    FUZZ_MUT_KEEP=1 leaves the scratch copy for inspection.
set -uo pipefail

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
REPO=$(cd "$(dirname "$0")/.." && pwd)
SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/dylan-mutgate.XXXXXX")
TESTS=${1:-'dylan.fuzz.*'}

cleanup() { [ "${FUZZ_MUT_KEEP:-0}" = "1" ] || rm -rf "$SCRATCH"; }
trap cleanup EXIT

echo "repo    : $REPO"
echo "scratch : $SCRATCH"
rsync -a --quiet \
  --exclude 'build/' --exclude '.git/' --exclude '.gradle/' --exclude '.kotlin/' \
  --exclude '*.har' --exclude '*.png' --exclude '*.log' "$REPO/" "$SCRATCH/"

SQ="$SCRATCH/shared/src/commonMain/sqldelight/dylan/db/dylan.sq"
SQM="$SCRATCH/shared/src/commonMain/sqldelight/migrations/1.sqm"
FACTORY="$SCRATCH/shared/src/jvmMain/kotlin/dylan/db/DriverFactory.jvm.kt"
TRANSFER="$SCRATCH/shared/src/commonMain/kotlin/dylan/download/Transfer.kt"
ORIGIN="$SCRATCH/shared/src/jvmTest/kotlin/dylan/fuzz/OriginScript.kt"
FSQL="$SCRATCH/shared/src/jvmTest/kotlin/dylan/fuzz/Sql.kt"
LEDGER="$SCRATCH/shared/src/jvmTest/kotlin/dylan/fuzz/EnvironmentLedger.kt"
FAULTY="$SCRATCH/shared/src/jvmTest/kotlin/dylan/fuzz/FaultyFileSystem.kt"

PASS=0
FAIL=0
declare -a RED=() GREEN=()

# run <label> <file> <test-filter>
#   Mutates nothing itself: the caller has already applied the edit. Restores, runs, records.
run() {
  local label="$1" file="$2" filter="$3"
  local out rc
  # `--rerun` re-executes the test task and nothing else: without it a mutation whose inputs
  # were seen in an earlier gate run is answered from the build cache, and a falsifiability
  # gate that can be satisfied by a cache is not a gate.
  out=$(cd "$SCRATCH" && ./gradlew --console=plain ":shared:jvmTest" --tests "$filter" --rerun 2>&1); rc=$?
  # Which test actually went red. A mutation that fails at *compile* time has not reddened
  # anything, and the gate must be able to tell those apart from a real red.
  local red compile
  red=$(printf '%s\n' "$out" | grep -oE '^[A-Za-z0-9_]+\[jvm\] > [A-Za-z0-9_]+\[jvm\] FAILED' \
        | sed 's/\[jvm\] > / /; s/\[jvm\] FAILED//' | sort -u | paste -sd, -)
  compile=$(printf '%s\n' "$out" | grep -cE '^e: ' || true)
  if [ $rc -eq 0 ]; then
    GREEN+=("$label"); PASS=$((PASS+1))
    printf '  \033[31mNOT RED\033[0m  %s\n' "$label"
  elif [ -z "$red" ] && [ "${compile:-0}" -gt 0 ]; then
    GREEN+=("$label [DID NOT COMPILE - the build failed, no test ran]"); PASS=$((PASS+1))
    printf '  \033[31mNOT RED\033[0m  %s  (compile error, %s)\n' "$label" "$compile"
  elif [ -z "$red" ]; then
    GREEN+=("$label [red for an unidentified reason]"); PASS=$((PASS+1))
    printf '  \033[31mNOT RED\033[0m  %s  (build red, no test named)\n' "$label"
  else
    RED+=("$label -> $red"); FAIL=$((FAIL+1))
    printf '  red  %s\n         -> %s\n' "$label" "$red"
  fi
  cp "$file.bak" "$file"
}

mutate() { # mutate <file> <perl-expr>
  cp "$1" "$1.bak"
  perl -pi -e "$2" "$1"
  if cmp -s "$1" "$1.bak"; then
    printf '  \033[31mMUTATION DID NOT APPLY\033[0m  %s  (pattern did not match)\n' "$1"
    cp "$1.bak" "$1"; FAIL=$((FAIL+1)); return 1
  fi
  return 0
}

echo
echo "=== production mutations (the plan's G-numbers) ==="

# G-01 — the totals trigger stops counting rows. INV-02/INV-04/INV-05 are the load-bearing checks.
if mutate "$SQ" 's/final_count = final_count \+ 1/final_count = final_count + 0/'; then
  run "G-01 dylan.sq final_count + 1 -> + 0" "$SQ" 'dylan.fuzz.M1InvariantsTest'
fi

# G-02 — the delete trigger loses its clamp. Needs a store whose totals were already short.
if mutate "$SQ" 's/MAX\(0, final_bytes - old\.bytes\)/final_bytes - old.bytes/'; then
  run "G-02 dylan.sq MAX(0, final_bytes - old.bytes) -> final_bytes - old.bytes" "$SQ" 'dylan.fuzz.M1InvariantsTest'
fi

# G-10 — the pin CHECK is removed. INV-18 alone cannot see it; only the write-and-expect-throw can.
#
# This needs TWO edits, not one. The pin CHECK is the last constraint in the `library` table body, so
# deleting that line alone leaves `CHECK (play_count >= 0),` as the final entry: a dangling comma
# before `)`. SQLDelight rejects that at *codegen* time, `:shared:generateCommonMainDylanInterface`
# fails, and the build dies before a single test executes. The gate then reports "red for an
# unidentified reason" — the mutant build was red, but nothing was *measured*, which is exactly the
# false-green this gate exists to prevent.
#
# `mutate` runs `perl -pi -e`, which is per-line, so `\n` inside a pattern never spans lines and the
# two edits have to be separate `s///` clauses in one expression (both anchored to a single line).
# The first drops the now-dangling comma; the second blanks the CHECK line itself.
if mutate "$SQ" 's/^  CHECK \(play_count >= 0\),$/  CHECK (play_count >= 0)/; s/^  CHECK \(\(pinned = 0\) = \(pinned_at_ms IS NULL\)\)$//'; then
  run "G-10 dylan.sq delete the pin CHECK" "$SQ" 'dylan.fuzz.M1InvariantsTest'
fi

# G-12 — the pragma stops being a connection property, so every pooled connection loses it.
if mutate "$FACTORY" 's/^\s*setProperty\("foreign_keys", "true"\)\n//m'; then
  run "G-12 DriverFactory.jvm.kt drop the foreign_keys property" "$FACTORY" 'dylan.fuzz.*'
fi

# G-11 — the migration stops dropping orphans, and the FK violation wedges the chain (F-1's door).
if mutate "$SQM" 's/^DELETE FROM cached_files\n//m'; then
  run "G-11 1.sqm delete the orphan DELETE guard" "$SQM" 'dylan.fuzz.M2MigrationTest'
fi

# G-06 — the writer stops folding its write-set back, so the resume asks from a stale offset (DL-1).
if mutate "$TRANSFER" 's/live\.store\(live\.load\(\)\.wrote\(written\.at\)\)/Unit/'; then
  run "G-06 Transfer.kt stop folding the write-set back" "$TRANSFER" 'dylan.fuzz.M0SeamsTest'
fi

echo
echo "=== harness mutations (a harness nobody has broken is a harness nobody knows works) ==="

# H-01 — the exact bug this suite inherited: Buffer.write's third argument is an END INDEX.
if mutate "$ORIGIN" 's/sink\.write\(bytes, off, off \+ n\)/sink.write(bytes, off, n)/'; then
  run "H-01 OriginScript.SliceSource pass a length where an end index belongs" "$ORIGIN" 'dylan.fuzz.M0SeamsTest'
fi

# H-02 — a clean close is turned back into a failure, which is the truncation-equals-ambiguity.
# (The replacement must still compile: `FeedClosed` is not a Throwable, so the original attempt at
# this mutation failed the build rather than the test, and the gate correctly refused to count it.)
if mutate "$ORIGIN" 's/if \(throwAtEnd\) throw IOException\(RESET_DETAIL, closed\) else Feed\.Eof/throw IOException(RESET_DETAIL, closed)/'; then
  run "H-02 OriginScript.FeedSource treat a clean close as a failure" "$ORIGIN" 'dylan.fuzz.M0SeamsTest'
fi

# H-03 — the byte budget stops biting, so "the volume is full" becomes a no-op.
if mutate "$FAULTY" 's/private fun overQuota\(\): Boolean = written\.get\(\) >= quotaBytes/private fun overQuota(): Boolean = false/'; then
  run "H-03 FaultyFileSystem.overQuota always false" "$FAULTY" 'dylan.fuzz.M0SeamsTest'
fi

# H-04 — the decorator stops counting the buffered write path, i.e. the app bypasses it entirely.
# This is §5.8's "the injector was actually reached" as a mutation rather than as a comment.
if mutate "$FAULTY" 's/^        count\("sink"\)\n//m'; then
  run "H-04 FaultyFileSystem.sink stop counting" "$FAULTY" 'dylan.fuzz.M0SeamsTest'
fi

# H-05 — EXPLAIN QUERY PLAN read off the wrong column, which makes "no sort" vacuously true.
if mutate "$FSQL" 's/\(0\.\.PLAN_COLUMNS\)/(0..0)/'; then
  run "H-05 Sql.plan read column 0 instead of the plan text" "$FSQL" 'dylan.fuzz.M1InvariantsTest'
fi

# H-06 — the primitive stops telling "no row" from a real zero, which is INV-04's whole point.
if mutate "$FSQL" 's/else NO_ROW/else 0L/'; then
  run "H-06 Sql.scalarLong return 0 for 'no row'" "$FSQL" 'dylan.fuzz.M1InvariantsTest'
fi

# H-07 — the ledger suspends nothing, so a deliberate break is indistinguishable from a defect.
if mutate "$LEDGER" 's/return out/return emptyMap()\n        \/\/ out/'; then
  run "H-07 EnvironmentLedger suspend nothing" "$LEDGER" 'dylan.fuzz.M1InvariantsTest'
fi

echo
echo "=== baseline (no mutation): must be green, or the gate is measuring nothing ==="
if (cd "$SCRATCH" && ./gradlew --console=plain ":shared:jvmTest" --tests "$TESTS" --rerun); then
  echo "  green    baseline $TESTS"
else
  echo "  \033[31mBASELINE IS RED\033[0m  $TESTS"
  FAIL=$((FAIL+1))
fi

echo
echo "=== summary ==="
printf 'reddened their test : %d\n' "${#RED[@]}"
for r in "${RED[@]:-}"; do [ -n "$r" ] && echo "    + $r"; done
printf 'did NOT redden      : %d\n' "${#GREEN[@]}"
for g in "${GREEN[@]:-}"; do [ -n "$g" ] && echo "    ! $g"; done
echo
if [ "${#GREEN[@]}" -gt 0 ]; then
  echo "GATE RED: a mutation that does not redden its test means that test is vacuous."
  exit 1
fi
echo "GATE GREEN"