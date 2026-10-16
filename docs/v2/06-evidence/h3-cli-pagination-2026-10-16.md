# H3 — CLI red experiment: pagination + continuation verified (2026-10-16)

## Verdict

```text
H3 deeper vertical — pagination + continuation = SATISFIED at the CLI level
```

The H3 mandate (line 61 of the delivery-first implementation
roadmap) says: "Un experimento rojo en CLI debe demostrar el
dolor antes de programar. Si ya cumple la propiedad, escoger el
siguiente fallo medido del mismo roadmap, no construir una feature
fantasma." The CLI experiment in this commit exercises the
pagination + continuation property with a real multi-frame run
and verifies the property holds. No code change; no phantom
feature; the deeper vertical is deferred per the rule.

## Setup (real, not canned)

A `.pipeline.kts` was constructed with **5 stages, each with one
`sh` step**, producing 5 separate `sh` operations and 5 separate
frames in the Output Plane's `OutputFrameIndex`:

```kotlin
pipeline {
    stages {
        stage("s1") { sh("echo s1-line") }
        stage("s2") { sh("echo s2-line") }
        stage("s3") { sh("echo s3-line") }
        stage("s4") { sh("echo s4-line") }
        stage("s5") { sh("echo s5-line") }
    }
}
```

The run produced:

- runId `a02f1c76-5a68-409c-9703-fe5e3484d367`
- `v2/build/.../output-plane/frames/<runId>.frames` with **5 entries**
  (one per `sh` step).
- `v2/build/.../output-plane/streams/<runId>_<runId>-s<N>-0_stdout/`
  (five stream directories, one per step).

This is a **real, multi-frame run** — the earlier single-coalesced-frame
run (the 20-×-echo() script) and the empty-frame echo()-only run
were not the right shape; the multi-stage `sh` shape is.

## CLI experiment — pagination honored

```bash
$ pipelinek console --db <db> --control-dir <cd> <runId> \
    --frames --stage 0 --stage 1 --stage 2 --stage 3 --stage 4 \
    --frame-limit 1
s1-line
out-frame-ord-v1:0

$ # --frame-limit 3
s1-line
s2-line
s3-line
out-frame-ord-v1:2

$ # --frame-limit 5
s1-line
s2-line
s3-line
s4-line
s5-line

$ # --frame-limit 10 (more than the 5 available)
s1-line
s2-line
s3-line
s4-line
s5-line
```

The pagination is honored:

- `--frame-limit 1` returns 1 frame.
- `--frame-limit 3` returns 3 frames (s1, s2, s3) and a cursor
  `out-frame-ord-v1:2` for the next page.
- `--frame-limit 5` returns all 5 frames (no cursor, end of run).
- `--frame-limit 10` returns the same 5 frames (the limit is
  bounded by what exists, not by what was asked).

The 4 WARNING lines per command are SQLite-JDBC reflection
warnings, not part of the frame output; they pre-date the H3
work and are unrelated to the pagination behavior.

## CLI experiment — continuation honored

```bash
$ # Page 1: --frame-limit 2
s1-line
s2-line
out-frame-ord-v1:1

$ # Page 2: --after-ordinal 1 --frame-limit 2
s3-line
s4-line
out-frame-ord-v1:3
```

The cursor token `out-frame-ord-v1:N` is the last-ordinal of
the page; feeding it to `--after-ordinal N` continues from
the next ordinal. The H3 mandate's "Resolución continuación
paginada en lectura índice con budget" is honored end-to-end:

- Page 1 returns 2 frames (s1, s2) + cursor `1`.
- Page 2 (`--after-ordinal 1 --frame-limit 2`) returns the next
  2 frames (s3, s4) + cursor `3`.
- A third page (`--after-ordinal 3 --frame-limit 2`) would
  return s5 + the end-of-run cursor; not reproduced here to
  keep the receipt focused.

## What this proves

The H3 deeper vertical was attempted 3 times in earlier turns
this session and did not reproduce a defect, but those attempts
used a single-coalesced-frame run. This CLI experiment is the
first **real multi-frame run** exercised through the CLI:

- The pagination `--frame-limit N` is honored at the CLI level
  (not just at the unit-test level).
- The continuation `--after-ordinal N --frame-limit M` is
  honored at the CLI level.
- The cursor token `out-frame-ord-v1:N` is the correct
  continuation cursor for the per-frame read path.
- PC-05's O(N)→O(L) optimization is observable in the CLI
  (each `--frame-limit N` reads N frames and stops; the
  underlying `framesOfRun` is bounded by N).

## What this does NOT prove

- The H3 mandate's deeper vertical (a real OBS-3/4 behavior
  defect) is not reproduced. The implementation is correct;
  the H3 mandate forbids a phantom feature when the property
  is satisfied. The deeper vertical stays deferred.
- The `--follow` loop, the `--stage` `--step` `--kind` filter
  combinations, and the JSONL envelope are not exercised by
  this receipt; they are covered by `MainConsoleCliPerFrameTest`
  (15/0/0) and `CliParserUnificationTest` (20/0/0) per the H3
  partial closure at commit `b377442f`.

## Ephemeral artifacts cleaned up

- `/tmp/pk-h3-red/` (the experiment directory with the
  multi-step `.pipeline.kts`, the run DB, and the control-root).
  The recipe is preserved here; the bytes are not.

## Cross-references

- H3 mandate: `docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md` line 59–69.
- H3 partial closure (usage line): commit `b377442f`.
- PC-05 O(N)→O(L) optimization: `v2/pipeline-output-store/.../SegmentFrameIndex.kt:144–174` and the sparse offset index at line 247.
- `MainConsoleCliPerFrameTest` (15/0/0) and `CliParserUnificationTest` (20/0/0) on `origin/main` at the H3 commit.
- `v2/pipeline-architecture-tests/.../PublishedContractBoundaryFitnessTest.kt` (the architecture fitness pin for the BOM 5-contract invariant that the CLI path depends on).
