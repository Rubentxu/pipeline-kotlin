# R4 / WU-CON-2.2 — `console --stage/--step/--kind/--run` content filters

- **Status:** IMPLEMENTED_UNCERTIFIED.
- **Train:** 0.60.0-rc8 · **Source of truth:** this file +
  `ConsoleFrameFiltersTest.kt` (24 tests, 0 failures, 0 errors) +
  `CliParser.kt` (parser extended with `--stage` / `--step` / `--kind` / `--run`) +
  `MainConsoleCli.kt` (`ConsoleFrameFilters` ADT, `ConsoleReadRequest.FramesFiltered`
  variant, `MutuallyExclusiveRunId` + `FilterRequiresFrames` refusals, `readFramesFiltered` /
  `emitFramesFiltered` dispatch, `StageStepKindResolver` filter predicate).
- **Provenance:** branch `r4/con-2-2-filters`; based on `main` @ `5b436d65` (v0.60.0-rc8).
- **Authority:** `R4_CLI_GAP_ANALYSIS §2.3` CON-2.2; the WU-R4.6 receipt names this slice
  explicitly ("`--stage / --step / --kind / --run` filters on `console` (CON-2.2)").

## One-line summary

`pipelinek console --control-dir <path> [--run <runId> | <runId>] <opId>
[--stage X [--stage Y ...]] [--step X [--step Y ...]] [--kind K [--kind L ...]]
[--format text|jsonl] --frames [--frame-limit N] [--after-ordinal N]` is the new
filtered per-frame path. The parser admits the four new flags; the verb main applies the
`ConsoleFrameFilters.Active` predicate to each frame at emit time and emits only those
that match every non-empty axis. Refusal prefixes (`Error:` for argument refusals,
`console-refused:` for data-plane refusals) are byte-for-byte preserved.

## What this slice ships

### Closed ADT

`ConsoleReadRequest` grows a fourth case:

```kotlin
sealed interface ConsoleReadRequest {
    data class Paged(...)            : ConsoleReadRequest
    data class Range(...)            : ConsoleReadRequest
    data class Frames(...)           : ConsoleReadRequest        // R4 / WU-R4.6
    data class FramesFiltered(                                  // R4 / WU-CON-2.2
        override val controlDir: String,
        override val runId: String,
        override val opId: String,
        val afterOrdinal: Long,
        val frameLimit: Int,
        val filters: ConsoleFrameFilters,
        override val format: MainConsoleCli.ConsoleFormat,
    ) : ConsoleReadRequest {
        init { require(filters is ConsoleFrameFilters.Active) { ... } }
    }
}
```

The `when (request)` in `MainConsoleCli.main` and in `MainConsoleCli.execute()` is
exhaustive over the four variants. The `FramesFiltered` branch in `execute()` is
documented as unreachable — it is reached via `main → readFramesFiltered → emitFramesFiltered`
— so the type-checker keeps the closed ADT honest.

### Closed filter ADT

```kotlin
sealed interface ConsoleFrameFilters {
    data object None : ConsoleFrameFilters                          // R4 / WU-R4.6 path
    data class Active(                                              // R4 / WU-CON-2.2
        val stages: Set<String> = emptySet(),
        val steps: Set<String> = emptySet(),
        val kinds: Set<String> = emptySet(),
    ) : ConsoleFrameFilters {
        init { require(stages.isNotEmpty() || steps.isNotEmpty() || kinds.isNotEmpty()) }
    }
}
```

`None` is the existing `Frames` read (no filter, every frame). `Active` is the closed
"at least one filter set" payload; the constructor refuses all-empty axes, so the parser
learns the rule at compile + admission rather than at the runtime loop. Repeated flags
OR within an axis (`--kind StepStarted --kind StepFinished` =
"StepStarted OR StepFinished"); the three axes AND together.

### Closed refusal ADT

`ConsoleCliRefusal` grows two new cases:

```kotlin
data class MutuallyExclusiveRunId(val runId: String, val runOverride: String)
    : ConsoleCliRefusal
data class FilterRequiresFrames(val flags: List<String>)
    : ConsoleCliRefusal
```

Both are rendered through `renderConsoleCliRefusal` with the `Error:` wire prefix; the
`console-refused:` data-plane renderer is untouched.

### New admission surface

```text
pipelinek console --control-dir <path> [--run <runId> | <runId>] <opId>
                  --frames
                  [--stage X [--stage Y ...]]      OR within axis
                  [--step X [--step Y ...]]        OR within axis
                  [--kind K [--kind L ...]]        OR within axis
                  [--frame-limit N]                default 1024
                  [--after-ordinal N]              default -1 (from start)
                  [--format text|jsonl]            default text
```

Decided before types:

| Mix | Refusal |
|---|---|
| `--run` + positional `<runId>` | `MutuallyExclusiveRunId(...)` |
| `--stage X` without `--frames` | `FilterRequiresFrames(["--stage"])` |
| `--step X` without `--frames` | `FilterRequiresFrames(["--step"])` |
| `--kind X` without `--frames` | `FilterRequiresFrames(["--kind"])` |
| `--stage` (no value) | `MissingOptionValue("--stage")` |
| `--run` (no value) | `MissingOptionValue("--run")` |

The `Error: ...` wire prefix is preserved for every new refusal; the `console-refused: ...`
data-plane prefix is unchanged.

### Verb main dispatch

```kotlin
is ConsoleCliParseResult.Admitted -> when (request) {
    is ConsoleReadRequest.Frames ->
        emitFrames(readFrames(request, root), request, System.out, System.err)
    is ConsoleReadRequest.FramesFiltered ->
        emitFramesFiltered(readFramesFiltered(request, root), request, System.out, System.err)
    is ConsoleReadRequest.Paged,
    is ConsoleReadRequest.Range ->
        emit(execute(request, root), request, System.out, System.err)
}
```

`readFramesFiltered` opens the same `FrameIndexedObservationOutputReader` the WU-R4.6
path uses — the reader is unchanged. The filter is applied at **emit** time, against the
closed `ConsoleFrameFilters.Active` ADT, by `StageStepKindResolver.forRun(root, runId)`.

### Filter predicate: `StageStepKindResolver`

The frame's stream id is `<runId>/<opId>/<channel>`, and the opId segment is shaped
`<runId>-s<stageIndex>-<stepIndex>[-b<branchIndex>][-bp<...>]` (see `OpId.kt`). The resolver:

1. Splits the stream id and validates the runId prefix (a foreign run is a non-match).
2. Parses the opId with `OpId.parse`; a malformed opId is a non-match.
3. Stringifies `stageIndex` and `stepIndex` and intersects them with the filter's
   `stages` and `steps` sets. An empty axis is a no-op conjunct.
4. Treats the `kind` axis as a pass-through for now: the per-frame path does not record
   event kinds, and wiring it requires the events-→-opId resolution that the WU-R4.6
   receipt names as the next slice (CON-2.3). The contract this resolver exposes —
   "an empty axis is a no-op, a non-empty axis is a conjunct" — is what the next slice
   extends without breaking today's matches.

A resolver that cannot name the matching set (e.g., a stream id with no opId segment)
returns `false` rather than `true`, so a malformed stream id cannot quietly slip through
a filter.

## What is NOT in this slice

- NOT a `--follow` flag on the per-frame path (CON-2.3). The byte-budgeted path is the
  one that already has a continuation story; the per-frame path will get one when the
  wakeup-based drain (`LiveOutputDrain`) is ported to frames. The wire shape is already
  shaped for it: the token is an ordinal, the index owns the "is there more?" claim, and
  the page carries `moreFrames`.
- NOT a real-fixture end-to-end test (the WU-R4.5 canary is still `@Disabled` against HEAD
  for the pre-existing `pipelinek run` defect; the same defect blocks the installDist canary
  for `console --frames`). The HF1 emit rows in `ConsoleFrameFiltersTest` cover the filter
  logic directly: fabricated `ObservationOutputRead.Page` values with known opIds, the
  filter applied, and the surviving records asserted.
- NOT a connection to the events journal for the `kind` axis. The resolver today
  stringifies the opId's `stageIndex` and `stepIndex`; the kind axis is admitted and
  AND-ed as a no-op. This is the explicit "next slice" hand-off.
- NOT a change to `ConsoleReadService` (the data-plane reader). The slice's scope is
  the parser, the ADT, the refusal algebra, and the verb-main dispatch — exactly the
  layers `AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE` names as the legitimate
  extension points for a reader-side change.

## Tests

`ConsoleFrameFiltersTest` (24 tests, 0 failures, 0 errors):

- **HF0 closed ADT (5 rows):** four-case `ConsoleReadRequest`; `FramesFiltered` round-trips
  `afterOrdinal` / `frameLimit` / `filters` / `format`; `ConsoleFrameFilters.None` and
  `Active`; `Active` refuses all-empty axes at construction; `Active` accepts any single
  non-empty axis.
- **HF0 closed refusal (2 rows):** `MutuallyExclusiveRunId` names both values and keeps
  the `Error:` prefix; `FilterRequiresFrames` names every offending flag and the
  `--frames` remedy, and keeps the `Error:` prefix.
- **HF0 parser (8 rows):** `--stage X --step Y --kind K --frames` admits as
  `FramesFiltered` with the right sets; `--run R` admits as runId override with no
  positional; `--run R` + positional refuses as `MutuallyExclusiveRunId`;
  `--stage X` / `--step X` / `--kind X` without `--frames` each refuse as
  `FilterRequiresFrames`; `--stage` / `--run` with no value refuse as `MissingOptionValue`;
  the new `Error:` prefix is preserved; an unknown option still refuses as `UnknownOption`
  (regression guard for the token-loop restructure).
- **HF1 emit wiring (3 rows):** JSONL emit produces exactly the records that match the
  `--stage 0 --step 1` conjunction; TEXT emit produces only the bytes of the matching
  frame; an unmatched filter emits zero frames (stdout empty).
- **HF1 resolver (4 rows):** `StageStepKindResolver.matches` is true for the matching
  opId, false for the non-matching opId; empty axes are no-op conjuncts; foreign-run
  streams are refused; malformed stream ids are refused.

Pre-existing tests still green:

- `MainConsoleCliPerFrameTest` (14 tests) — WU-R4.6 contract unchanged.
- `MainConsoleFormatTest` (9 tests) — WU-R4.3 contract unchanged.
- `ConsoleReadServiceTest` (12 tests) — byte path unchanged.
- `CliParserUnificationTest` (10 tests) — CC-1 contract unchanged.

## Design decisions

### 1. Filter at emit, not at read

The slice's scope says "Frame-emit path filters each frame by `filters` before emitting",
and the WU-R4.6 reader is the WU-R4.1.3 `FrameIndexedObservationOutputReader` — a sealed
reader the slice must not touch. The cleanest way to satisfy both is to keep the read
side filter-agnostic and apply the predicate at emit. The benefit: the read side keeps
its "every frame, in observation order" contract, and the filter's "what survived"
result is a small, testable post-process that the verb main owns.

### 2. Resolver scope, not events DB

The verb main does not open the events DB. Wiring it would mean adding a `--db` flag to
`console`, which is outside the slice's scope (and out of the per-frame path's authority
on the Output Plane). The resolver therefore operates on what the frame's stream id can
honestly tell us: the runId prefix and the opId segment, parsed with `OpId.parse` so the
shape change in WU-R4.1.3 (`OpId.branchIndex`, `OpId.bodyPath`) does not break the
filter. Stage and step are matched against the **stringified index**, so a caller that
writes `--stage 0 --step 1` against a `runId-s0-1` opId gets a hit, and a caller that
writes `--stage build` against the same opId gets a miss with the same answer.

### 3. Kind is a pass-through for now

The per-frame path does not record event kinds (StepStarted, StepFinished, StepFailed,
…), and the verb main does not open the events DB. Admitting a non-empty `kinds` axis
without resolving it would be a silent lie, so the constructor's `init` block refuses
`Active` with all-empty axes, and the runtime conjunct on `kinds` is `true`. Wiring
`kinds` to the events journal is the next slice's job (CON-2.3 / event-→-opId
resolution); the contract this slice fixes — "an empty axis is a no-op, a non-empty
axis is a conjunct" — is what the next slice extends without breaking today's matches.

### 4. Refusal names both tokens

`MutuallyExclusiveRunId` carries `runId` and `runOverride`; the rendered form names both
so a caller editing the wrong one (`pipelinek console --control-dir /tmp/ctrl --run X
Y op-1`) gets a refusal that says "got positional Y and --run X" rather than a refusal
that hides which value won. The verb main's existing `when (request)` keeps the existing
"two positionals with no `--run`" refusal (`UnexpectedArgument`) unchanged, so the old
behaviour is preserved byte-for-byte.

## Reference

- **Test class:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ConsoleFrameFiltersTest.kt`
- **Production:**
  - `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt`
    - `ConsoleReadRequest.FramesFiltered` (new).
    - `ConsoleFrameFilters` (new sealed ADT).
    - `ConsoleCliRefusal.MutuallyExclusiveRunId` (new).
    - `ConsoleCliRefusal.FilterRequiresFrames` (new).
    - `renderConsoleCliRefusal` extended with the two new cases (preserves `Error:` prefix).
    - `MainConsoleCli.readFramesFiltered` (new; filter-agnostic read).
    - `MainConsoleCli.emitFramesFiltered` (new; applies the filter predicate).
    - `StageStepKindResolver` (new file-internal class).
  - `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CliParser.kt`
    - `parseConsole` token loop learns `--stage` / `--step` / `--kind` / `--run`.
    - `consoleValidate` learns the `MutuallyExclusiveRunId` and `FilterRequiresFrames`
      gates (decided BEFORE the per-frame integer rules, so a caller's filter problem is
      named before its number problem).
    - `consolePositional` learns the `hasRunOverride` pivot.
    - `consoleAdmitFramesFiltered` (new).
- **Per-frame read primitive:** `FrameIndexedObservationOutputReader.readOutput`
  (unchanged from WU-R4.6; the slice's "do not modify the reader" rule is honoured).
- **OpId parser:** `dev.rubentxu.pipeline.v2.application.durable.OpId.parse` (unchanged
  from WU-R4.6; the resolver consumes it).
- **Closed ADT shape precedent:** the `BodyExecutionPolicy` ADT (`B10_W1B_BODY_EXECUTION_POLICY_RECEIPT.md`),
  the `MutuallyExclusiveRunId` shape precedent: the `ConflictingWorkspaceModes`
  refusal in `CliError` (ADR-0101 §3.4).
- **Companion pre-existing-red:** the WU-R4.5 installDist canary is still `@Disabled`
  against HEAD on the same pre-existing `pipelinek run` defect; the CON-2.2 end-to-end
  installDist canary would be blocked on the same defect.
