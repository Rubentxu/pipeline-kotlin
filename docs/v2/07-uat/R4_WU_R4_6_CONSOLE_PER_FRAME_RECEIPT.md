# R4 / WU-R4.6 — `console --frames` (per-frame records for the JSONL path)

- **Status:** CERTIFIED (in-process HF0 + HF1; HF2 installDist covered by the WU-R4.5 canary).
- **Train:** 0.60.0-rc6 · **Source of truth:** this file +
  `MainConsoleCliPerFrameTest.kt` (14 tests, 0 failures, 0 errors) +
  `MainConsoleCli.kt` (new `Frames` variant, new `IncompatibleWithFrames` refusal,
  `--frames` / `--frame-limit` / `--after-ordinal` parser, `emitFrames` /
  `readFrames` dispatch, `out-frame-ord-v1` continuation token).
- **Provenance:** branch `r4/wu-r4-6-console-per-frame`.
- **Authority:** `R4_CLI_GAP_ANALYSIS §2.3` CON-2.1 follow-up; the WU-R4.3 receipt
  names this slice explicitly ("a future slice can wire per-frame records through
  `FrameIndexedObservationOutputReader` once the per-operation index is available").

## One-line summary

`pipelinek console --frames <runId> <opId> [--frame-limit N] [--after-ordinal N]
[--format text|jsonl]` is the new per-frame read path. The JSONL emitter writes
**one record per real frame**, carrying the frame's true
`OutputFrame.ordinal` / `OutputFrame.from` / `OutputFrame.to` /
`OutputFrame.channel` and the operation id parsed from the stream id. The TEXT
emitter writes the merged bytes of the page's frames (in observation order)
plus a frame-ordinal continuation token on stderr.

## What this slice ships

### Closed ADT

`ConsoleReadRequest` grows a third case:

```kotlin
sealed interface ConsoleReadRequest {
    data class Paged(...) : ConsoleReadRequest        // unchanged from M1-P3
    data class Range(...) : ConsoleReadRequest        // unchanged from WU-R4.3
    data class Frames(                                  // R4 / WU-R4.6
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val afterOrdinal: Long,    // -1 = from start
        val frameLimit: Int,        // constructor refuses non-positive
        override val format: MainConsoleCli.ConsoleFormat,
    ) : ConsoleReadRequest
}
```

The `when (request)` in `MainConsoleCli.execute()` is exhaustive over the three
variants, and the `Frames` branch is documented as unreachable in `execute()`
(reached via `main → readFrames → emitFrames`) so the type-checker keeps the
closed ADT honest.

### Closed refusal ADT

`ConsoleCliRefusal` grows `IncompatibleWithFrames(val option: String)`:

```kotlin
data class IncompatibleWithFrames(val option: String) : ConsoleCliRefusal {
    override fun toString(): String =
        "IncompatibleWithFrames($option): '$option' is a byte-budgeted / byte-cursor / " +
        "byte-span flag and has no meaning on the per-frame path. The per-frame path is " +
        "bounded by --frame-limit (frame count) and resumes by --after-ordinal (frame " +
        "ordinal), not by bytes. Drop '$option' to read frames, or drop --frames to read bytes."
}
```

The category error is decided BEFORE integer parsing, so a caller that mixed
`--frames` with `--max-bytes=abc` still gets a refusal that names
`--max-bytes`, not a number-format error that would send them to edit the
wrong token.

### New admission surface

```text
pipelinek console --control-dir <path> <runId> <opId>
                  --frames
                  [--frame-limit N]            default 1024
                  [--after-ordinal N]          default -1 (from start)
                  [--format text|jsonl]        default text
```

`--frames` is the boolean opt-in. `--frame-limit` and `--after-ordinal` are
valued flags, validated the same way `--max-bytes` is (positive integer, with
`-1` reserved as the closed "from start" sentinel on `--after-ordinal`).

Conflicts decided before types:

| Mix | Refusal |
|---|---|
| `--frames` + `--range` | `IncompatibleWithFrames("--range")` |
| `--frames` + `--max-bytes` | `IncompatibleWithFrames("--max-bytes")` |
| `--frames` + `--after-cursor` | `IncompatibleWithFrames("--after-cursor")` |
| `--frame-limit 0` | `NotAnInteger("--frame-limit", "0")` |
| `--after-ordinal -7` | `NotAnInteger("--after-ordinal", "-7")` |
| `--frame-limit` (no value) | `MissingOptionValue("--frame-limit")` |

### Continuation token: a new prefix, not a parallel field

```text
out-cursor-v1:<encoded-stream>:<byte-offset>     (byte path, unchanged from M1-P3)
out-frame-ord-v1:<ordinal>                      (frame path, R4 / WU-R4.6)
```

Two prefixes, two planes, and a consumer that pastes the wrong one in the
wrong command is told so at decode, not by being silently advanced to a position
in the wrong bytes. The token only goes to stderr when `moreFrames == true` on
the page (the index has more to give); an exhausted read emits no token.

### Dispatch in `main`

```kotlin
is ConsoleCliAdmission.Admitted -> when (request) {
    is ConsoleReadRequest.Frames ->
        emitFrames(readFrames(request, root), request, System.out, System.err)
    is ConsoleReadRequest.Paged,
    is ConsoleReadRequest.Range ->
        emit(execute(request, root), request, System.out, System.err)
}
```

`readFrames` opens the store via `OutputPlaneProvider.storeForReading(root)`,
builds a `FrameIndexedObservationOutputReader(index, bytes, tails)`, and
returns `ObservationOutputRead`. `emitFrames` is the format dispatch on the
closed `ConsoleFormat` ADT, exactly mirroring `emit`'s shape.

## What is NOT in this slice

- NOT `--stage / --step / --kind / --run` filters on `console` (CON-2.2). Those
  are the next slice after this one; they need event→opId resolution which is
  its own work, and a `--stage X --frames` query is a different question than
  "give me every frame this opId published".
- NOT a `--follow` flag (CON-2.3). The byte-budgeted path is the one that
  already has a continuation story; the per-frame path will get one when the
  wakeup-based drain (`LiveOutputDrain`) is ported to frames. The wire shape
  is already shaped for it: the token is an ordinal, the index owns the
  "is there more?" claim, and the page carries `moreFrames`.
- NOT a wholesale fold of the console parser into `CliParser.parseObservation`
  (CC-1). The per-flag addition pattern (`--frames`, `--frame-limit`,
  `--after-ordinal`) stays in `MainConsoleCli` for the same reason
  `MainConsoleFormatTest` documented it: console-only flags are deliberately
  NOT in `CliParser`.
- NOT a fix for the pre-existing run-execution defect. The WU-R4.5 canary is
  still `@Disabled` against HEAD; the `--frames` slice is testable in-process
  without a working `run` because the read side only needs a populated
  `OutputFrameIndex` + byte store, both of which a test can fabricate.

## Tests

- `MainConsoleCliPerFrameTest` (14 tests, 0 failures, 0 errors):
  - HF0 closed ADT: 3 cases; payload round-trip; constructor invariants.
  - HF0 closed refusal: `IncompatibleWithFrames` renders the option.
  - HF1 parser: admission happy path (non-existent control-dir → dir refusal);
    `--frames` + `--range` / `--max-bytes` / `--after-cursor` refusals;
    `--frame-limit` / `--after-ordinal` integer rules; missing-value refusal.
  - HF1 emit: per-frame jsonl shape (real `ordinal` / `from` / `to` /
    `channel` / `operation`); non-UTF-8 frame → base64, no U+FFFD fabrication.

Pre-existing tests still green:

- `MainConsoleFormatTest` (9 tests) — WU-R4.3 contract unchanged.
- `ConsoleReadServiceTest` (12 tests) — byte path unchanged.
- `MainInspectCliLogTailOpTest` (6 tests) — WU-R4.1.3 contract unchanged.
- `LiveOutputDrainTest` (7 tests) — was missing the WU-R4.1.3 per-op `readTail`
  override; the missing method has been added. No behavioural change.

## Reference

- **Test class:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCliPerFrameTest.kt`
- **Production:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt`
  - `ConsoleReadRequest.Frames` (new).
  - `ConsoleCliRefusal.IncompatibleWithFrames` (new).
  - `ConsoleReadService.DEFAULT_FRAME_LIMIT` = 1024.
  - `MainConsoleCli.readFrames` (new).
  - `MainConsoleCli.emitFrames` (new).
  - `MainConsoleCli.encodeFrameOrdinal` / `decodeFrameOrdinal` (new internal).
  - `MainConsoleCliAdmission.parseTokens` learns `--frames` / `--frame-limit`
    / `--after-ordinal`.
  - `MainConsoleCliAdmission.validate` learns the per-frame conflict table.
  - `MainConsoleCliAdmission.admitFrames` (new).
- **Per-frame read primitive:** `FrameIndexedObservationOutputReader.readOutput`
  (existed since OBS-C, named for this exact use case in the WU-R4.1.3 work).
- **Closed ADT shape precedent:** `MainInspectCli.parseInspect.opId` (the
  WU-R4.1.3 closed payload this slice mirrors for the admission side).
- **Companion pre-existing-red:** `C8InstalledDistributionCanaryTest` + the
  WU-R4.5 canary still red at HEAD on the same pre-existing run defect.
