#!/usr/bin/env python3
"""M1-P2 non-vacuity harness.

The P2 law is "for any one execution, the transcript bytes exist in exactly one place". Each
mutation below either puts the bytes back into a second place or removes them from the only one,
and the named test MUST go red. A mutation that leaves the suite green means the guard is
decoration - which is the whole reason this file exists rather than a comment claiming rigor.

Every mutation is reverted. The store is never left mutated.
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
SHEXEC = ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt"
BACKUP = SHEXEC.read_text()

SINGLE = "durable sh writes its transcript to the Output Plane and emits no console event"
STDOUT = "returnStdout stays an exact typed value and is not the transcript"
LOSSY = "the transcript read back is byte-exact, contiguous and lossless"

MUTATIONS = [
    (
        "M8",
        "process output rides a console event again",
        SINGLE,
        "ingestTranscriptIntoOutputPlane(\n                    controlDirRoot = controlDirRootNonNull,",
        "eventSink.append(EchoOutputCaptured(eventId = java.util.UUID.randomUUID().toString(), runId = runId, sequence = 0L, occurredAt = java.time.Instant.now(), stepIndex = stepIndex, content = \"leaked\"))\n                ingestTranscriptIntoOutputPlane(\n                    controlDirRoot = controlDirRootNonNull,",
    ),
    (
        "M9",
        "ingest becomes a no-op",
        SINGLE,
        "        val store = OutputPlaneProvider.storeFor(controlDirRoot)\n        store.open(OutputPlaneProvider.streamId(runId, opId))\n            .appendFrom(redacted)",
        "        redacted.close()",
    ),
    (
        "M10",
        "console.log is retained after a successful exit",
        SINGLE,
        "val deleteRetainedLog = (terminal as? DurableTaskTerminal.Exited)?.exitCode == 0",
        "val deleteRetainedLog = false",
    ),
    (
        "M11",
        "the typed returnStdout value is folded back into the transcript",
        STDOUT,
        "?: terminalExited?.output?.consoleTranscript?.byteInputStream()\n                    ?: terminalExited?.output?.capturedStdout?.byteInputStream()",
        "?: terminalExited?.output?.capturedStdout?.byteInputStream()\n                    ?: terminalExited?.output?.consoleTranscript?.byteInputStream()",
    ),
    (
        "M12",
        "the Output Plane ingests fewer bytes than the transcript holds",
        LOSSY,
        ".appendFrom(redacted)",
        ".appendFrom(java.io.ByteArrayInputStream(ByteArray(0)))",
    ),
]


def restore():
    SHEXEC.write_text(BACKUP)


def run():
    proc = subprocess.run(
        ["./gradlew", ":pipeline-application:test", "--tests", "*OutputSingleAuthorityFitnessTest*",
         "--console=plain", "--offline", "--max-workers=1"],
        cwd=ROOT / "v2", capture_output=True, text=True, timeout=1800,
    )
    return proc.stdout + proc.stderr


killed = 0
bad = 0

try:
    for name, desc, expect, pattern, replacement in MUTATIONS:
        restore()
        source = SHEXEC.read_text()
        if pattern not in source:
            print(f"SKIP        {name}  target pattern not found  ({desc})")
            bad += 1
            continue
        SHEXEC.write_text(source.replace(pattern, replacement, 1))

        out = run()
        if "\ne: " in out or out.startswith("e: "):
            print(f"COMPILE-ERR {name}  proved nothing  ({desc})")
            for line in out.splitlines():
                if line.startswith("e: "):
                    print("             " + line[:160])
                    break
            bad += 1
        elif "BUILD SUCCESSFUL" in out:
            print(f"VACUOUS     {name}  suite stayed GREEN  ({desc})")
            print(f"             guard expected to fire: {expect}")
            bad += 1
        elif expect in out:
            print(f"killed      {name}  {expect}")
            killed += 1
        else:
            print(f"WRONG       {name}  red, but not on the expected guard  ({desc})")
            for line in out.splitlines():
                if "FAILED" in line:
                    print("             " + line.strip()[:160])
            bad += 1
finally:
    restore()

print()
print(f"mutations killed: {killed}   vacuous or wrong: {bad}")
sys.exit(0 if bad == 0 else 1)
