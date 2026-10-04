#!/usr/bin/env python3
"""M1-INTEGRATION non-vacuity harness for the Output Plane survival contract.

`OutputSingleAuthorityFitnessTest` reads the transcript back through the live store that just wrote
it, in the same process. That proves the bytes were committed. It does NOT prove they are still
there afterwards, and the gap between those two claims is where this line found a real defect: the
shell substrate deletes per-operation control directories on a successful exit, and the Output
Plane's survival depended on `output-plane` happening to be a sibling of those directories rather
than a child of one.

Each mutation below breaks that survival in a way a reasonable refactor could, and the named test
MUST go red. A mutation the suite survives is not weak coverage - it is a guard that does not exist,
and it is reported as such.
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
SHEXEC = ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt"
PROVIDER = ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/OutputPlaneProvider.kt"
STORE = ROOT / "v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/SegmentOutputStore.kt"

BACKUP = {p: p.read_text() for p in (SHEXEC, PROVIDER, STORE)}

SURVIVES = "committed output survives the invocation that produced it, as seen by a later process"
EXTENT = "a fresh process recovers the committed extent rather than starting empty"

MUTATIONS = [
    (
        # The headline. "Put each operation's output with the rest of that operation's control
        # data" is a reasonable-sounding refactor that would silently destroy every committed
        # transcript, because the shell substrate recursively deletes the per-operation control
        # directory on success. Nothing in the type system objects, and git cannot see it coming,
        # because no file S4 also edits is involved.
        "I1", "the Output Plane is resolved per-operation instead of per-run", SURVIVES, SHEXEC,
        "val store = OutputPlaneProvider.storeFor(controlDirRoot)",
        "val store = OutputPlaneProvider.storeFor(controlDirRoot.resolve(opId))",
    ),
    (
        # I2 IS RETIRED, and the reason is that it does not break the property it names.
        #
        # Nesting the store one level deeper - {root}/output-plane/nested instead of
        # {root}/output-plane - leaves the Output Plane a sibling of the per-operation control
        # directories, exactly as before. The invariant GEOMETRY states still holds, so the suite
        # staying green is CORRECT rather than weak. A mutation that does not falsify its own claim
        # is not evidence; keeping it would leave a permanent red row meaning nothing.
        #
        # Note what this says about the geometry test: it is not unfalsifiable. I1 moves the store
        # under the per-operation directory, after which {root}/output-plane does not exist at all
        # and `the Output Plane is not inside any per-operation control directory` fails on its
        # first assertion. I1 is the mutation that attacks the geometry.
        "I3", "the ingested window is abandoned instead of committed", EXTENT, STORE,
        "                    reservation.write(window.copyOf(read))\n                    committed = reservation.commit()",
        "                    reservation.write(window.copyOf(read))\n                    committed = reservation.abandon()",
    ),
    (
        # O3 as seen by a fresh process. If recovery-before-read is load-bearing, then skipping the
        # recover() in the provider must cost a later process its answer.
        "I4", "the provider hands out a store without recovering it", EXTENT, PROVIDER,
        "SegmentOutputStore(root.resolve(OUTPUT_DIR)).also { it.recover() }",
        "SegmentOutputStore(root.resolve(OUTPUT_DIR))",
    ),
]


def restore():
    for p, text in BACKUP.items():
        p.write_text(text)


def run():
    # Real `sh` processes, so the timeout is generous. A mutation that makes a read loop forever
    # must surface as a failure rather than stall the harness.
    proc = subprocess.run(
        ["./gradlew", ":pipeline-application:test", "--tests", "*OutputPlaneSurvivalFitnessTest*",
         "--console=plain", "--offline", "--max-workers=1"],
        cwd=ROOT / "v2", capture_output=True, text=True, timeout=900,
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
