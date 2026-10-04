#!/usr/bin/env python3
"""M1-P3 non-vacuity harness.

P3's job is to make the read side *consumable*, so the things worth breaking are the consumer
affordances: the cross-plane refusal, the bounded page, the stream separation, and the fact that a
refusal is an error rather than an empty success. Each mutation removes one of those and the named
test MUST go red.
"""
import subprocess
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
CLI = ROOT / "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt"
CUR = ROOT / "v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputCursor.kt"

BACKUP = {p: p.read_text() for p in (CLI, CUR)}

BOUNDED = "a paged read hands back a continuation token and the next page continues exactly"
FOREIGN = "a continuation token naming another stream is refused"
CROSSPLANE = "an event cursor pasted as an output token is refused, not silently offset"
UNKNOWN = "an unknown operation is refused as unknown, not served as empty"
SEAM = "the CLI writes transcript bytes to stdout and the token to stderr"
RANGE = "the CLI --range branch returns the same bytes the service range does"

MUTATIONS = [
    # N1 is intentionally absent. The foreign-stream rule lives in SegmentOutputStore alone; the
    # service used to carry a duplicate copy and the harness proved the duplicate unreachable.
    # Duplicating a refusal is how two implementations of one rule start disagreeing.
    (
        "N2", "the read is no longer bounded by maxBytes", BOUNDED, CLI,
        "ConsoleReadService.read(root, runId, opId, after, maxBytes)",
        "ConsoleReadService.read(root, runId, opId, after, Int.MAX_VALUE)",
    ),
    (
        "N3", "an unknown stream is served as an empty page instead of refused", UNKNOWN, CLI,
        "        val cursor = after ?: OutputCursor.start(stream)\n        return when (val result = store.read(stream, cursor, maxBytes)) {",
        "        val cursor = after ?: OutputCursor.start(stream)\n        if (store.committedExtent(stream) == null) {\n            return Result.Page(dev.rubentxu.pipeline.v2.output.OutputPage(ByteArray(0), stream, 0L, null, 0L))\n        }\n        return when (val result = store.read(stream, cursor, maxBytes)) {",
    ),
    (
        "N4", "any cursor prefix decodes, so an event cursor is accepted", CROSSPLANE, CUR,
        'if (parts[0] != TOKEN_PREFIX) return null',
        'if (parts.size != 3) return null',
    ),
    (
        "N5", "the continuation token is printed to stdout, polluting the transcript", SEAM, CLI,
        "result.page.next?.let { err.println(it.encode()) }",
        "result.page.next?.let { out.println(it.encode()) }",
    ),
    (
        "N6", "a byte range is served as the whole stream", RANGE, CLI,
        "            ConsoleReadService.readRange(root, runId, opId, from, to)",
        "            ConsoleReadService.read(root, runId, opId, null, Int.MAX_VALUE)",
    ),
]


def restore():
    for p, text in BACKUP.items():
        p.write_text(text)


def run():
    proc = subprocess.run(
        ["./gradlew", ":pipeline-application:test", "--tests", "*ConsoleReadServiceTest*",
         "--console=plain", "--offline", "--max-workers=1"],
        cwd=ROOT / "v2", capture_output=True, text=True, timeout=2400,
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
    # Prove the restore actually happened. A harness that leaves a mutation behind is worse than
    # one that reports VACUOUS, because the next green run is measured against broken code.
    drifted = [str(p) for p, text in BACKUP.items() if p.read_text() != text]
    if drifted:
        print(f"RESTORE FAILED - these files are left mutated: {drifted}")
        bad += 1

print()
print(f"mutations killed: {killed}   vacuous or wrong: {bad}")
sys.exit(0 if bad == 0 else 1)
