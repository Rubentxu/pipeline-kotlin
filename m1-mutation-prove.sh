#!/usr/bin/env bash
# M1-P1 non-vacuity harness.
#
# A guard nobody broke is a guard nobody knows works. Each mutation below breaks one property of
# the store, and the named test MUST go red. If a mutation leaves the suite green, the guard it
# targeted does not exist and the test asserting it is decoration.
#
# Every mutation is reverted immediately. The store is never left mutated.
set -uo pipefail

STORE="v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/SegmentOutputStore.kt"
BACKUP=$(mktemp)
cp "$STORE" "$BACKUP"

restore() { cp "$BACKUP" "$STORE"; }
trap restore EXIT

pass=0
fail=0

mutate() {
  local name="$1" desc="$2" expect="$3" pattern="$4" replacement="$5"
  restore
  python3 - "$STORE" "$pattern" "$replacement" <<'PY'
import sys
path, pattern, replacement = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
if pattern not in s:
    print("MUTATION-TARGET-NOT-FOUND")
    sys.exit(3)
open(path, 'w').write(s.replace(pattern, replacement, 1))
PY
  if [ $? -ne 0 ]; then
    echo "SKIP  $name  (target pattern not found)"
    fail=$((fail+1))
    return
  fi

  # No -q here: it suppresses the per-test FAILED lines, and this harness's whole job is to read
  # WHICH test went red. A green-looking run that merely failed to compile would otherwise be
  # indistinguishable from a killed guard.
  out=$(cd v2 && ./gradlew :pipeline-output:test --console=plain --offline --max-workers=1 2>&1)
  if echo "$out" | grep -q "^e: "; then
    echo "COMPILE-ERROR  $name  -> the mutation did not build, so it proved nothing"
    echo "$out" | grep "^e: " | head -3
    fail=$((fail+1))
  elif echo "$out" | grep -q "BUILD SUCCESSFUL"; then
    echo "VACUOUS  $name  -> suite stayed GREEN; $expect did not catch it"
    fail=$((fail+1))
  elif echo "$out" | grep -qF "$expect"; then
    echo "killed   $name  -> $expect went red"
    pass=$((pass+1))
  else
    echo "WRONG    $name  -> suite went red but not on $expect"
    echo "$out" | grep "FAILED" | head -5
    fail=$((fail+1))
  fi
}

# M1: reads no longer require recovery. O3 is the obligation that recovery is an entry point.
mutate M1 "read before recover() is allowed" \
  "O3 - reads are refused before recovery has run" \
  'override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult {
        requireRecovered()' \
  'override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult {'

# M2: a foreign cursor is clamped instead of refused - the silent wrong answer.
mutate M2 "foreign cursor silently reads the named stream" \
  "a cursor naming another stream is refused, never clamped" \
  'if (cursor.stream != stream) {
            return OutputReadResult.Refused(OutputRefusal.ForeignStream(expected = stream, actual = cursor.stream))
        }' \
  'if (false) {
            return OutputReadResult.Refused(OutputRefusal.ForeignStream(expected = stream, actual = cursor.stream))
        }'

# M3: recovery stops dropping the uncommitted tail.
#
# Found by instrumenting the store, not by reading it. There are two truncations of an
# unreconciled tail - one in recover() and one when a reservation is taken - and they are
# redundant with each other. Mutating the reservation-time one leaves the suite green because
# recover() has already done the work; mutating the recover() one is what a reader observes.
mutate M3 "recovery keeps the uncommitted tail" \
  "a reservation after an unreconciled tail does not expose the stale bytes" \
  'if (currentEnd > committed) {
            truncateTo(layout.segmentFile, onDisk - (currentEnd - committed))
            return currentEnd - committed
        }' \
  'if (false) {
            truncateTo(layout.segmentFile, onDisk - (currentEnd - committed))
            return currentEnd - committed
        }'

# M4: recovery stops releasing the outstanding reservation, so the order gets a permanent hole.
mutate M4 "recovery does not release the reservation" \
  "I3 and I6 - an unused reservation is released, so the order stays dense" \
  'releasedBytes += reconcile(layout)
                if (Files.deleteIfExists(layout.reservationFile)) releasedReservations++' \
  'releasedBytes += reconcile(layout)
                if (false) releasedReservations++'

# M5: the reader resumes from the file size instead of the committed offset - strategy A's flaw.
mutate M5 "committed offset read from file size" \
  "O2 - the committed offset, not the file size, is what a reader resumes from" \
  'private fun committedLocked(layout: Layout): Long =
        if (Files.exists(layout.commitFile)) {
            Files.readString(layout.commitFile).trim().toLongOrNull() ?: 0L
        } else 0L' \
  'private fun committedLocked(layout: Layout): Long =
        if (Files.exists(layout.segmentFile)) Files.size(layout.segmentFile)
        else if (Files.exists(layout.commitFile)) {
            Files.readString(layout.commitFile).trim().toLongOrNull() ?: 0L
        } else 0L'

# M6: a page is no longer bounded by maxBytes.
mutate M6 "read ignores maxBytes" \
  "page is bounded by maxBytes and the cursor resumes exactly where it stopped" \
  'minOf(cursor.committedOffset + maxBytes, extent)' \
  'extent'

# M7: rotation rewinds the committed offset, losing every previously committed byte.
mutate M7 "segment rotation rewinds the committed offset" \
  "a long stream seals segments and reads back identically across the seam" \
  'Files.writeString(layout.commitFile, "$committed\n")
    }
' \
  'Files.writeString(layout.commitFile, "$currentBase\n")
    }
'

restore
echo
echo "mutations killed: $pass   vacuous or wrong: $fail"
[ "$fail" -eq 0 ]
