#!/usr/bin/env python3
"""M1-P4 non-vacuity harness.

P1, P2 and P3 have mutation harnesses. P4 did not, and that gap was named rather than papered
over: the three defects this slice found were found by *writing* the tests, not by mutating them,
which is a weaker form of evidence than the one the rest of the line is held to.

Each mutation below breaks one conformance property and the named test MUST go red.
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
STORE = ROOT / "v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/SegmentOutputStore.kt"
CURSOR = ROOT / "v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputCursor.kt"

BACKUP = {p: p.read_text() for p in (STORE, CURSOR)}

LOSSLESS = "a deterministic stream of pseudo-random bytes round-trips with no missing or duplicated byte"
RANGE = "an arbitrary range of a long stream equals the same slice of the whole read"
RESTART = "a restart mid-stream resumes at the committed offset with no gap and no repeat"
IDEMPOTENT = "recovery is safe to run twice, and after an interrupted recovery"
DANGLING = "a commit count ahead of the readable bytes is treated as uncommitted, not as content"
SLOW = "a slow reader that stops and resumes sees every committed byte exactly once"
WINDOW = "the writer never materialises the whole producer, only one window"
PAGEDGES = "the last page reports no continuation, and an intermediate one always does"

MUTATIONS = [
    (
        "Q1", "appendFrom writes bytes but never commits", LOSSLESS, STORE,
        "                    reservation.write(window.copyOf(read))\n                    committed = reservation.commit()",
        "                    reservation.write(window.copyOf(read))\n                    committed = reservation.abandon()",
    ),
    # Q2 is RETIRED, not fixed. Mutating `commit()` to record the segment size instead of the
    # reservation position left the suite green — and that is correct, not a weak test: in this
    # store the two values COINCIDE, because every reservation truncates any stale tail before it
    # writes. They could only diverge if a reservation failed to truncate, and that is Q3's subject.
    # Keeping a mutation that cannot fail would be keeping a guard that does not exist.
    (
        "Q3", "recovery stops releasing the outstanding reservation", RESTART, STORE,
        "                if (Files.deleteIfExists(layout.reservationFile)) releasedReservations++",
        "                if (false) releasedReservations++\n                if (Files.exists(layout.reservationFile)) Files.deleteIfExists(layout.reservationFile)",
    ),
    (
        "Q4", "the dangling-commit count is not reported", DANGLING, STORE,
        "                unbackedBytes += maxOf(0L, committedLocked(layout) - readableEndLocked(layout))",
        "                unbackedBytes += 0L",
    ),
    (
        # Q5 IS STILL OPEN. The mutation applies and the suite stays green, and the reason was not
        # established within the session that wrote it. Recorded as an open item rather than as
        # coverage: the range-agreement test only asks for ranges INSIDE the committed extent, so it
        # may simply never exercise the clamping branch. Until that is resolved this row is a hole,
        # and the harness says so.
        "Q5", "a byte range silently clamps to what is readable instead of refusing", RANGE, STORE,
        "readRangeLocked(layout, stream, from, to)",
        "readRangeLocked(layout, stream, from, minOf(to, extent))",
    ),
    (
        "Q6", "a page is no longer bounded by maxBytes", SLOW, STORE,
        "readRangeLocked(layout, stream, cursor.committedOffset, minOf(cursor.committedOffset + maxBytes, extent))",
        "readRangeLocked(layout, stream, cursor.committedOffset, extent)",
    ),
    (
        "Q7", "the last page always hands back a continuation cursor", PAGEDGES, STORE,
        "next = if (to >= extent) null else OutputCursor(stream, to),",
        "next = OutputCursor(stream, to),",
    ),
    (
        "Q8", "recovery is no longer idempotent in the stable field", IDEMPOTENT, STORE,
        "                committedBytes += committedLocked(layout)",
        "                committedBytes += committedLocked(layout) + reservationsReleased.toLong()",
    ),
    (
        "Q9", "the writer buffers the whole producer instead of one window", WINDOW, STORE,
        "val window = ByteArray(windowBytes)",
        "val window = ByteArray(windowBytes.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() * 64)",
    ),
]


def restore():
    for p, text in BACKUP.items():
        p.write_text(text)


def run():
    # 300 s, not 2400. A mutation that makes a test loop forever must be reported as a kill, not
    # stall the whole harness; the read loops are now page-capped, so a hang means something else
    # is wrong and is worth seeing quickly.
    proc = subprocess.run(
        ["./gradlew", ":pipeline-output:test", "--tests", "*OutputPlaneConformanceTest*",
         "--console=plain", "--offline", "--max-workers=1"],
        cwd=ROOT / "v2", capture_output=True, text=True, timeout=300,
    )
    return proc.stdout + proc.stderr


killed = bad = 0
try:
    for name, desc, expect, path, pattern, replacement in MUTATIONS:
        restore()
        source = path.read_text()
        if pattern not in source:
            print(f"SKIP        {name}  target not found  ({desc})")
            bad += 1
            continue
        path.write_text(source.replace(pattern, replacement, 1))
        out = run()
        if "\ne: " in out or out.startswith("e: "):
            print(f"COMPILE-ERR {name}  proved nothing  ({desc})")
            for line in out.splitlines():
                if line.startswith("e: "):
                    print("             " + line[:150]); break
            bad += 1
        elif "BUILD SUCCESSFUL" in out:
            print(f"VACUOUS     {name}  suite stayed GREEN  ({desc})")
            print(f"             guard expected to fire: {expect}")
            bad += 1
        elif expect in out:
            print(f"killed      {name}  {expect}")
            killed += 1
        else:
            print(f"WRONG       {name}  red, but not the expected guard  ({desc})")
            for line in out.splitlines():
                if "FAILED" in line:
                    print("             " + line.strip()[:150])
            bad += 1
finally:
    restore()
    drifted = [str(p) for p, text in BACKUP.items() if p.read_text() != text]
    if drifted:
        print(f"RESTORE FAILED - left mutated: {drifted}")
        bad += 1

print()
print(f"mutations killed: {killed}   vacuous or wrong: {bad}")
sys.exit(0 if bad == 0 else 1)
