# R4 / WU-CON-2.3 — `pipelinek console --follow` (per-frame tail-f mode)

- **Status:** IMPLEMENTED_UNCERTIFIED.
- **Train:** post-`main@5b436d65` (v0.60.0-rc8); this slice does NOT produce a candidate.
- **Source of truth:** this file + the test class `ConsoleFollowTest.kt`.
- **Provenance:** branch `r4/con-2-3-follow` (this slice); based on `main` @ `5b436d65` (R4 / WU-R4.7 CC-1 parser unification, post-v0.60.0-rc8 candidate handoff).
- **Depends on:** R4 / WU-R4.6 (`console --frames`, per-frame records for the JSONL path). `--follow` is a thin loop over the per-frame read the R4.6 slice opened.

## One-line summary

`pipelinek console --follow` adds a tail-f mode over the per-frame path:

```text
--follow                              enter follow mode: poll the opId, emit new frames as they arrive, exit on EOF or limit
--follow-poll-interval <millis>       poll interval in milliseconds (default 1000, must be > 0)
--follow-max-polls <N>                maximum number of polls (default 60 — about 1 minute of follow at the default interval)
```

`--follow` requires `--frames`. Refusal prefixes are preserved byte-for-byte:

- argument refusals keep `Error: ...` (via `renderConsoleCliRefusal`);
- data-plane refusals keep `console-refused: ...` (via `ConsoleReadService.renderRefusal`).

## Acceptance contract (from the slice charge)

1. **End-to-end:** `pipelinek console --follow --format jsonl` against a run that produces multiple frames over time emits frames in observation order, with at most `--follow-max-polls` polls, and exits 0 when the run finishes.
2. **First poll starts from `-1`.** A subsequent poll uses the highest ordinal observed in the prior poll + 1. The cursor is local — it is NOT persisted; a fresh process invocation starts over. (Pinned by `FollowConfig` constructor + the loop's local `ordinal` variable; no journal write.)
3. **Empty polls are observable.** After the writer signals EOF, the verb exits 0. The verb MUST NOT loop forever if the op has no writer and never produces more frames.
4. **Refusal prefixes are byte-for-byte preserved.** Argument refusals keep `Error: ...` (via `renderConsoleCliRefusal`). Data-plane refusals keep `console-refused: ...` (via `ConsoleReadService.renderRefusal`). DO NOT touch those renderers.
5. **Unknown option** still refuses as `Error: unknown option: <name>`.
6. **Non-positive `--follow-poll-interval` refuses.** `0` and negative values refuse with `Error: --follow-poll-interval must be a positive integer, got: <value>` (the existing `NotAnInteger` refusal carries both shapes).
7. **Negative `--follow-max-polls` refuses.** Same shape.
8. **`--follow` without `--frames` refuses** with `Error: --follow requires --frames` (a new typed refusal, `ConsoleCliRefusal.FollowRequiresFrames`).

## What was actually built

### Files changed

| Path | Change |
| --- | --- |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt` | Added `FollowConfig` (closed payload with non-positive `require` at construction) and `FollowFrames` case to `ClosedADRequest`. Added `ConsoleCliRefusal.FollowRequiresFrames` and the renderer branch. Added `MainConsoleCli.runFollowFrames` interpreter and `MainConsoleCli.emitFollowPage` emitter. Updated the `when (request)` dispatch in `MainConsoleCli.main` and `MainConsoleCli.execute` to admit the new case. |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CliParser.kt` | Added `--follow`, `--follow-poll-interval`, `--follow-max-polls` to the `parseConsole` token loop. Added `consoleAdmitFollowFrames` to admit the typed request. Added the `follow && !frames → FollowRequiresFrames` guard in `consoleValidate`. |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ConsoleFollowTest.kt` | New test class with 22 rows covering HF0 ADT, HF1 parser admission, HF1 prefix preservation, and HF2 installDist canary. |

### ADT shape

The closed payload is `FollowConfig`:

```kotlin
data class FollowConfig(
    val pollIntervalMillis: Long,
    val maxPolls: Int,
) {
    init {
        require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive, got $pollIntervalMillis" }
        require(maxPolls > 0) { "maxPolls must be positive, got $maxPolls" }
    }
    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 1000L
        const val DEFAULT_MAX_POLLS: Int = 60
        val DEFAULT: FollowConfig = FollowConfig(DEFAULT_POLL_INTERVAL_MILLIS, DEFAULT_MAX_POLLS)
    }
}
```

`FollowConfig` is a closed value, not a flag bag. Both fields are required, both refused at construction if non-positive.

`ConsoleReadRequest.FollowFrames` mirrors `Frames` plus a `FollowConfig`:

```kotlin
data class FollowFrames(
    override val controlDir: String,
    override val runId: String,
    override val opId: String,
    val afterOrdinal: Long,
    val frameLimit: Int,
    val followConfig: FollowConfig,
    override val format: MainConsoleCli.ConsoleFormat,
) : ConsoleReadRequest {
    init {
        require(frameLimit > 0) { "frameLimit must be positive, got $frameLimit" }
        require(afterOrdinal >= -1L) { "afterOrdinal must be -1 (from start) or non-negative, got $afterOrdinal" }
    }
}
```

### Loop semantics

```text
for poll in 0 until followConfig.maxPolls:
    if shutdown.get(): return 2                  # SIGINT / SIGTERM
    page = reader.readOutput(runId, ordinal, frameLimit)
    if page.records.isNotEmpty():
        emitFollowPage(page.records, format, out, err)
        ordinal = last record's frame.ordinal + 1L
    decision = followDecision(
        moreFrames = page.moreFrames,
        tailStates = reader.tailStatesOf(runId),
        runFinished = false,                     # console verb has no event-plane access
    )
    when decision:
        Finished -> return 0                     # EOF: 0 new frames AND every known stream is Sealed
        ReadAgain:
            if poll < maxPolls - 1:
                Thread.sleep(pollIntervalMillis)  # last iteration does not sleep
return 0                                         # hit maxPolls, exit 0
```

Three exit paths:

- `FollowDecision.Finished` — page returned 0 new frames AND every known output stream is `Sealed` (the durable authority promises nothing more will arrive on this run). Exit 0.
- `maxPolls` reached — bounded at admission. An op that owns no stream (e.g. `sh("true")`) cannot be `Finished` because no stream exists to seal; `maxPolls` is what stops the loop on a silent op. Exit 0.
- SIGINT/SIGTERM — JVM shutdown hook flips a flag; loop checks between rounds. Exit 2.

### EOF detection rule (the design decision this slice documents)

EOF is a pure function of the per-frame read:

```text
0 new frames AND every known output stream is Sealed
```

The pure function is `followDecision(moreFrames, tailStates, runFinished)` from `ObservationWakeup.kt` — the same closed ADT the `events` and `observe` followers use. No new decision authority is introduced.

`runFinished = false` for the console verb is deliberate: console does not have event-plane access, and the only authority it has is the output plane. A silent run that owns no streams is bounded by `maxPolls`, not by an answer the run plane cannot give. (Documented in `MainConsoleCli.runFollowFrames` KDoc.)

### Concurrency discipline (PAR-D + CTX-P in force)

PAR-D (ADR-0076): coroutines execute a previously determined typed decision; they are not the authority for durable truth. The follow loop is a coroutine, not a journal. The typed decision (`FollowFrames`) is what reaches the runtime; the loop is a pure interpreter of that decision. No journal mutation, no separate dispatch path.

CTX-P (Context as explicit immutable data): the `afterOrdinal` cursor is local state threaded as a parameter through each iteration, never a global or thread-local. The durable authority is unchanged by the loop.

### Refusal prefixes preserved

| Refusal | Rendered line |
| --- | --- |
| `MissingOptionValue("--follow-poll-interval")` | `Error: --follow-poll-interval requires a value` |
| `MissingOptionValue("--follow-max-polls")` | `Error: --follow-max-polls requires a value` |
| `NotAnInteger("--follow-poll-interval", "abc")` | `Error: --follow-poll-interval must be a positive integer, got: abc` |
| `NotAnInteger("--follow-max-polls", "-1")` | `Error: --follow-max-polls must be a positive integer, got: -1` |
| `NotAnInteger("--follow-poll-interval", "0")` | `Error: --follow-poll-interval must be a positive integer, got: 0` |
| `FollowRequiresFrames` | `Error: --follow requires --frames; the per-frame path is the only one a follow can resume on, because the byte path advances by byte cursor and the follow would lose frame attribution on the resume.` |
| `UnknownOption("--follow-imaginary")` | `Error: unknown option: --follow-imaginary` |
| `console-refused: unknown-stream ...` | (data-plane, `ConsoleReadService.renderRefusal`) |

All argument refusals carry the `Error:` prefix. Data-plane refusals keep `console-refused:` on stderr. The two renderers are independent (`renderConsoleCliRefusal` for the parser side, `ConsoleReadService.renderRefusal` for the read side).

## Failure modes considered (and how each is closed)

| Failure mode | Closed by |
| --- | --- |
| Loop forever on a run that never produces output | `FollowConfig.maxPolls` is the loop bound (CLI admission); the for loop cannot exceed it. |
| Loop forever on a run that produces output but never finishes | `FollowDecision.Finished` returns when every known stream is `Sealed`. If no stream exists, `maxPolls` is the bound. |
| Miss SIGINT and leave a poll thread behind | `Runtime.getRuntime().addShutdownHook(...)` flips an `AtomicBoolean`; the loop checks it between rounds; the hook is `removeShutdownHook`'d in `finally`. |
| Cursor leaked across runs | DR-5: deterministic replay-local state is allowed when fully reconstructible from durable history. The local `ordinal` is reconstructible from `(afterOrdinal + frameLimit)`; it is not journaled. A fresh process invocation starts over from `afterOrdinal`. |
| Fabricate a frame to detect EOF | ADR-M1 §D2 forbids inventing an observation to buy a termination. The follow uses `OutputTailState` from the durable store, not a synthetic frame. |
| Conflate follow and replay | `ReplayPolicy.NEVER` (DR-1..DR-2) is preserved: `--follow` does not change the `ReplayPolicy` of the underlying per-frame read; it changes how many times the read is invoked. |
| Thread-locals or coroutine-locals for the cursor | CTX-P: `ordinal` is a function parameter; each poll derives the next iteration's `afterOrdinal` from the previous page's records. No shared mutable context. |

## Test rows (HF0..HF2)

`ConsoleFollowTest.kt` — 22 tests, all passing.

### HF0 — closed ADT shape

- `ConsoleReadRequest is a four-case closed ADT after --follow lands`
- `FollowFrames payload round-trips afterOrdinal, frameLimit, followConfig, and format`
- `FollowConfig refuses non-positive pollIntervalMillis at construction` (covers 0 and -1)
- `FollowConfig refuses non-positive maxPolls at construction` (covers 0 and -1)
- `FollowFrames requires frameLimit to be positive at construction`
- `FollowFrames requires afterOrdinal to be -1 or non-negative at construction`

### HF1 — parser admission

- `parser admits --follow --format jsonl with the documented defaults`
- `parser admits --follow with explicit --follow-poll-interval and --follow-max-polls`
- `parser refuses --follow without --frames`
- `parser refuses --follow-poll-interval 0 as a non-positive integer`
- `parser refuses --follow-poll-interval -1 as a non-positive integer`
- `parser refuses --follow-poll-interval abc as NotAnInteger`
- `parser refuses --follow-poll-interval without a value`
- `parser refuses --follow-max-polls 0 as a non-positive integer`
- `parser refuses --follow-max-polls -3 as a non-positive integer`
- `parser refuses --follow-max-polls without a value`
- `parser refuses an unknown --follow-prefixed option`

### HF1 — prefix preservation

- `argument refusals keep the Error prefix` (covers every new parser refusal)
- `data-plane refusals keep the console-refused prefix` (cross-check on `ConsoleReadService.renderRefusal`)
- `main refuses --follow without --frames through the same Error prefix as parser`
- `main admits --follow --frames against a non-existent control dir and fails on the dir check`

### HF2 — installDist canary

- `installed distribution follows a real run to its terminal state and exits 0`
  (`@TempDir` + `AppBinSupport.discover()`; `assumeTrue` skips the row when `installDist` is not built; bound `5` × 100 ms = 500 ms of polling plus the read; exit 0 is the contract for a finished run; no data-plane refusal on stderr.)

### Test result XML path and SHA

- XML: `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ConsoleFollowTest.xml`
- All 22 rows: `failures="0" errors="0"` (green on the new SHA after the installDist row runs against the binary `v2/pipeline-application/build/install/pipelinek/bin/pipelinek` at this SHA).

## Cross-checks against existing rows

- `MainConsoleCliPerFrameTest` (R4 / WU-R4.6) — re-ran alongside this slice: all rows green. The new `Frames` and `FollowFrames` constructors both share the same `frameLimit > 0` and `afterOrdinal >= -1L` shape.
- `MainConsoleCliArgumentContractTest` — re-ran: all rows green. The new `NotAnInteger("--follow-poll-interval", ...)` and `NotAnInteger("--follow-max-polls", ...)` refusals render through the same `renderConsoleCliRefusal` path.
- `MainConsoleFormatTest` — re-ran: all rows green. The dispatch in `MainConsoleCli.execute` is exhaustive over the closed `ConsoleReadRequest` ADT (now four cases: `Paged`, `Range`, `Frames`, `FollowFrames`).
- `ConsoleReadServiceTest` — re-ran: all rows green. The byte-budgeted path is unchanged.
- `MainConsoleCliArgumentStrictnessTest`, `MainConsoleCliRequestStrictnessTest`, `*CliParser*` — re-ran: all rows green. The new `--follow*` flags ride the same token loop the existing `--frames` flag uses.

## What this slice does NOT do (out of scope)

- **Content filters (`--stage/--step/--kind/--run`).** Different agent, different PR (CON-2.2). The two slices meet at `ConsoleReadRequest` — both add one. `FollowFrames` does not block `FramesFiltered` from landing.
- **Changes to `ConsoleReadService` (the data-plane reader).** The interpreter drives the existing reader with new `after-ordinal` values; the reader is unchanged.
- **The v0.61.0-rc1 candidate.** The coordinator builds the candidate after both CON-2.2 and CON-2.3 are merged.
- **`--format text` tail-mode for the byte path.** The byte path has its own follow story (the events verb's `--follow`); the per-frame path is what `--frames` opened.

## Status: IMPLEMENTED_UNCERTIFIED

The slice is implemented and the 22 HF0..HF2 rows pass on this SHA. CERTIFIED requires the harness to drive the installDist canary against a real candidate (v0.61.0-rc1) on real runs whose output plane is populated — which is the responsibility of the next release-train step, not this WU.

## Reference implementation consulted

- **None.** No external step implementation has a `--follow` for per-frame output; the closest reference is the events verb's `--follow` (`MainEventsCli.runFollow`), which uses a different plane and a different EOF rule. This slice reuses the closed `FollowDecision` ADT (`ObservationWakeup.kt`) — the authority the existing followers already consume — rather than inventing a new one.
- **Behaviour adopted:** the same `for (poll in 0 until maxPolls) { read; emit; sleep }` shape, against a per-frame reader instead of an event drain.
- **Intentional deviations from `events --follow`:** `runFinished` is always `false` (no event-plane access from `console`); the per-poll bound is `frameLimit` instead of `EVENTS_PER_READ`; EOF uses `OutputTailState` instead of `RunFinished` events.
- **Security implications reviewed:** none — the verb is read-only and does not change journal state.
- **Tests demonstrating the contract:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ConsoleFollowTest.kt` (this slice).