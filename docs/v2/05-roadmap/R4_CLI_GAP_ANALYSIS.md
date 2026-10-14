# R4 — CLI Gap Analysis (observe / console / events / inspect)

**Scope:** surface-level gap audit of the four PipelineK CLI verbs the R4
brief enumerates — `observe`, `console`, `events`, `inspect` — against
`docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md`, ADR-0088, SPEC-01
(PK-L7) and `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` OBS-4..7.
**No code changes.** The intent is to pin the user-visible contract, mark
what is already met by the SHAs today, and to give the next implementer
a top-5 list whose leverage is measured, not implied.
**Reference SHAs:** HEAD = `438b344b` (`test(obs-pc-208): R2.3(b) TODO 4 —
add f03, f07, f09, f10 falsifiers`).
**Authority chain:** AGENTS.md · CLI_OBSERVABILITY_SPEC.md · ADR-0088 ·
SPEC-01 (PK-L7) · OBS_PROGRESSIVE_CONSOLE_ROADMAP.md (OBS-4..7) ·
`coordination/PAIR_RELEASE_FLOW.md` (cross-repo consumer parity).

---

## Executive summary

1. **`observe` is the closest to done.** Filters by run/stage/step/kind/channel,
   `--limit` / `--tail-bytes` budgets, `--follow`, pure JSONL, typed
   `ObserveRefusal` ADT and `OutputRefusal` mirroring are already in
   `MainObserveCli.kt`. Gaps: cross-frame UTF-8 is per-record (PC-08 is
   open in OBS roadmap), follow is poll-based (the `EventsCommitted`
   wakeup emitter does not yet exist), and the verb refuses `FULL` — a
   product-side decision, not a defect.
2. **`console` emits raw bytes — not the JSONL envelope R4 §4 mandates.**
   `MainConsoleCli.kt` writes `OutputPage.bytes` verbatim to stdout and the
   continuation token to stderr. There is no per-record `{type, operation,
   channel, ordinal, text, base64}` shape, no `--follow`, no
   `--stage/--step/--kind/--run` filters, and no UTF-8 chunking contract
   across pages.
3. **`events` lacks the `inspect`-shaped flags and `--follow`.**
   `MainEventsCli.kt` implements `--kind / --subject / --limit /
   --after-cursor / --typed` only. Per the WU-LPR-051 receipt
   (`docs/v2/07-uat/WU_LPR_051_RECEIPT.md` §6 finding F1), the CLI flag
   wiring for `--fields / --tail / --context / --after-sequence` is the
   documented "migration target" and has not landed. `--follow` is also
   absent; CLI_OBSERVABILITY_SPEC §7 line 95 names the canonical
   `pipelinek events RUN --follow --kind StepFailed` invocation.
4. **`inspect` is unimplemented.** There is no `MainInspectCli.kt`, no
   dispatcher entry in `Main.kt`, and no test. ADR-0088 §Decision
   designates `inspect --failed --context --log-tail --fields --format
   json` as the primary bounded diagnostic surface; `LOCAL_PRODUCTION_READY.md`
   L78 and `LOCAL_CICD_PRODUCT_PROFILE.md` L59 both cite it. Functionality
   is wholly missing.
5. **There is no single argument parser, no single output-formatting
   layer, and no cross-CLI installed-distribution harness.** Three
   hand-rolled parsers (parseObservation, `MainConsoleCliAdmission`,
   inline loop in `MainEventsCli`); three output encoders
   (`RunObservationOutput.Stream`, raw `OutputPage.bytes`,
   `JsonEventLog.encodeOne`); only `WULpr010CliCharacterizationTest`,
   `C8InstalledDistributionCanaryTest` and `Lpr011ResumeLifecycleUatTest`
   run against the `installDist` binary, and none of them cover the
   four observation verbs together.

**Headline gap:** `inspect` does not exist as a CLI verb. The functionality
ADR-0088 names as the primary agentic diagnostic surface has no entry
point, no admission, no output codec, and no test.

---

## 1. `observe` — MainObserveCli.kt

**Source:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainObserveCli.kt` (614 lines)
**Parser:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CliParser.kt` (via `parseObservation`, L652)
**Tests:** `ObsE5ObserveReplayTest`, `ObsE5ObserveFollowTest`, `ObsE5TailTest`,
`ObsE5LimitTest`, `ObsE5OutcomeTest`, `ObsE5ChannelAdmissionTest`,
`ObserveReplayEncoderEquivalenceTest`, `ObservationCliContractTest`.

### 1.1 Current behavior

- One public verb over both durable authorities (event lane via
  `EventStore`, output lane via `FrameIndexedObservationOutputReader`)
  [MainObserveCli.kt:18-43].
- USAGE: `pipelinek observe <runId> [--control-root <dir>] [--db <path>]
  [--view normal|events|console|quiet] [--format text|jsonl|json]
  [--stage S] [--step S] [--kind K] [--channel stdout|stderr]
  [--grep TEXT] [--limit N] [--tail-bytes N] [--follow]`
  [MainObserveCli.kt:46-51].
- `ObservationParseResult.Parsed` carries a `CompiledObservationQuery`,
  `ObservationView`, `ObservationFormat`, `tailBytes`, `dbPath`,
  `controlRoot`, `follow`, `budget: RecordBudget`
  [CliParser.kt:384-403]. Filters compile to a closed
  `CompiledObservationQuery.accepts(record)` —
  `ObservationQuery.kt:106-143`.
- `--stage` and `--step` are parsed by `TextFilterOptions.addTextFilter`
  into `state.stageNames` and `state.stepNames` and become
  `ObservationQuery.stageNames` / `stepNames` [CliParser.kt:943-944,
  350-369].
- `--limit` resolves to `RecordBudget.UpTo(n)` (refused if `n <= 0`,
  [RecordBudget.kt:43-46]); `--tail-bytes` resolves to a `Long?` start
  in the output lane [CliParser.kt:1005-1011].
- Pure JSONL: `ObservationFormat.JSON_LINES` uses
  `ObservationJsonLines.encodeOne` per record, with explicit `text`/
  `base64` swap on invalid UTF-8 (`ObservationJsonLines.kt:97-117`).
  `format == TEXT` routes through `HumanConsoleRenderer.stream` per
  `RunObservationOutput.Stream` [RunObservationOutput.kt:130-180].
- `--follow` is two-pole: one LANE per round trip. Event-lane finishes on
  `RunFinished`; output-lane finishes when every `OutputTailState` is
  sealed [MainObserveCli.kt:260-381]. The follow refuses non-incremental
  formats (twice: at admission via `CliError.FollowNeedsAnIncrementalFormat`,
  and at runtime via `ObserveRefusal.FollowNeedsAnIncrementalFormat`).
- Five typed refusals: `NoDurableEventStore`, `NoOutputPlane`,
  `FullViewUnavailable`, `PlaneRefused(reason: OutputRefusal)`,
  `FollowNeedsAnIncrementalFormat` [MainObserveCli.kt:524-566]. All
  carry `toString()` prose; no case is "an empty answer".
- No invented global ordering: `ObservationView.FULL` is refused by
  `resolveView` in both `run` and `observe`
  [`ObservationView.kt:138-150`, `CliParser.kt:721-722`].

### 1.2 Required by R4 (CLI_OBSERVABILITY_SPEC)

| § | Demand | Verdict |
|---|---|---|
| §1 | `view` / `format` orthogonality | met |
| §3 | `--format jsonl|json`: stdout = machine payload only; diagnostics on stderr | met (`ObservationFormat` + parsing/refusal split) |
| §4 | tagged records `{type, sequence, kind, subject}` | met for events via `ObservationJsonLines.encodeEventLine`; observation records for output lane carry `{type, channel, operation, ordinal, from, to, text/base64}` |
| §5 | filters by run/stage/step/event kind/outcome/channel/text/sequence-or-cursor/limit-or-tail-or-context | met for stage/step/kind/channel/text/limit/cursor; `--outcome` parsed but `e` filter is reachable in `applyObservation` via `buildObservationQuery` `outcomes` [CliParser.kt:357-358]; `--run` is the positional arg |
| §6 | `--fields` projection | **not implemented in `observe`** (live projection cuts by view; `--fields` is documented as the `inspect` affordance, see §3) |
| §7 | `--follow` consumes `EventCursor` / output byte offset; wakeups for low latency; durable read-after for recovery | partial (poll-based; wakeup seam absent) |
| §8 | tail bounded; reverse/seek-based reading instead of loading the whole transcript | met (FrameIndexedObservationOutputReader tail walks backwards in batches that double — see `ObservationOutputReader.kt:242-260`) |
| §9 | redaction before persistence | met (T6 canaries wrap the event store at construction time — `Main.kt:221`) |
| §11 | no total ordering lie between event `sequence` and console byte offset | met (`FULL` is refused; follow handles one lane at a time) |
| §12 | exit-code contract `{0, 1, 2}` | partially met — `MainObserveCli.main` returns `0` for `Replayed` / `Followed`, `2` for `Refused`; no reserved `1` slot for "plane refused" because `Refused` is collapsed to `2`. Other observation CLIs split `1` (refused) and `2` (usage) per §12 table. |

### 1.3 Gaps (per CLI command)

| ID | Gap | Severity | Reference |
|---|---|---|---|
| OBS-1.1 | `CliError` notes "OPTION BELONGS TO OBSERVE" is REFUSED, but `--fields` is NOT in `EXECUTION_ONLY_OPTIONS` and NOT rejected for `run`. `--fields` was specified as `pipeline observe`/`pipeline inspect`, not `pipeline run`; a parser-level refusal (read-only verbs may carry `--fields`) is missing. | MAJOR | `CliParser.kt:712-774` |
| OBS-1.2 | Cross-frame incremental UTF-8. `ObservationJsonLines.decodeStrictly` uses a fresh `CharsetDecoder` per record and `decode(ByteBuffer)` in one shot — a 4 KiB frame split mid-codepoint emits `null`/base64 for both halves, which does NOT cross the boundary. OBS roadmap PC-08 is `OBS-4 P1` and unimplemented (`OBS_PROGRESSIVE_CONSOLE_ROADMAP.md:45`). | MAJOR | `ObservationJsonLines.kt:125-131`; `ObservationOutputReader.kt` (per-frame `decode`) |
| OBS-1.3 | `--follow` is poll-based. The author's own comment at `MainObserveCli.kt:253-259` states that an `EventsCommitted` emitter is missing (OBS-D bis), so the loop asks the durable authority for its position. The spec's "wakeups for low latency" is not satisfied. | MAJOR | `MainObserveCli.kt:299-315` |
| OBS-1.4 | Exit-code contract: §12 table reserves `1` for "plane refused". `observe` returns `2` for every `ObserveRefusal`, including `PlaneRefused` (which is the analogue of `console`'s `1`). The status-only consumer cannot distinguish "I asked wrong" from "the store said no". | MINOR | `MainObserveCli.kt:103-114` (compare `MainConsoleCli.kt:459-474`) |
| OBS-1.5 | `tailBytes` on the `FULL` view, even though full is refused: the `OutputPlane` `NoOutputPlane` refusal arrives first because full is refused in `replay()` at L135 / `follow()` at L271, but the parsed query keeps `tailBytes` regardless. Stylistic: a view-aware admission check would pre-empt the runtime refusal. | NIT | `MainObserveCli.kt:129-136` |

### 1.4 Test coverage

- `ObsE5ObserveReplayTest` (`MainObserveCli.kt`-adjacent): the OBSERVE-1..9
  matrix. Drives `MainObserveCli.replay(...)` and `follow(...)` with a
  hand-rolled `ObserveLanes` fixture, NOT a real store. HF1 (in-process,
  no filesystem).
- `ObsE5ObserveFollowTest`: follows the same pattern against in-memory
  `ObserveLanes`. Asserts the nine FOLLOW-* rows.
- `ObservationCliContractTest`, `ObsE5TailTest`, `ObsE5LimitTest`,
  `ObsE5OutcomeTest`, `ObsE5ChannelAdmissionTest`, `ObserveReplayEncoderEquivalenceTest`:
  further in-process coverage of the boundary.
- `MainEventsCliArgumentStrictnessTest` and friends:
  `MainEventsCli.main([])` only, with `System.setOut/setErr`.
- **NO installed-distribution (`installDist`) test exercises `observe`
  against a built JAR.** Searches under
  `v2/pipeline-application/src/test/.../cli/` and `WULpr*` return no
  match. The HF2 (HF2 = forked real distribution) rung in the harness
  ladder (ADR-0072) is empty for `observe`.
- Mutation notes: the OBS-E5 receipts document `M-O1/O2/O3` flipping
  OBSERVE-3..8 1:1, but no mutation covers `--fields` or `--follow`
  polling-vs-wakeup.

### 1.5 Implementation note

Close the §12 exit-code split first (NIT is fine, but the contract table
ships with three verbs returning 2 for refusals): introduce a third
`ObserveOutcome.Refused` flag distinguishing `UsageRefused` from
`PlaneRefused`, return `2` / `1` respectively, and pin both in
`ObservCliExitContractTest`. The cross-frame UTF-8 and wakeup follow are
their own WUs (R6/OBS-2/4 territory, see §3 here). `--fields` is best
deferred to the `inspect` WU (§3 below) since the spec places the
projection there.

---

## 2. `console` — MainConsoleCli.kt

**Source:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt` (663 lines, all in one file)
**Parser:** `MainConsoleCliAdmission` (a private object at L554-662) — NOT
the unified `CliParser`.
**Service:** `ConsoleReadService` (also in the same file, L45-292).
**Tests:** `MainConsoleCliArgumentContractTest`,
`MainConsoleCliArgumentStrictnessTest`, `MainConsoleCliRequestStrictnessTest`.

### 2.1 Current behavior

- One read-only verb, two positional arguments: `--control-dir <path>
  <runId> <opId>`. Optional `--max-bytes N` / `--after-cursor TOKEN` /
  `--range FROM:TO` [L415-414].
- Admission gates:
  - `--max-bytes abc` → `ConsoleCliRefusal.NotAnInteger("--max-bytes", "abc")` → stderr + exit 2 [L646-649, L383-392]
  - `--max-bytes 0` → same `NotAnInteger` path (the check is `takeIf { it > 0 }`)
  - `--range 0:10 --max-bytes 100` → `IncompatibleWithRange("--max-bytes")` (range collapses the budget) [L617-624]
  - `--range abc:def` → `NotARange` [L630-637]
  - cursor pasted into `--after-cursor` → `NotACursorToken` [L653-655]
- Stores: `ConsoleReadService.read(...)`/`readRange(...)`
  [L77-249]. Each call concatenates `OrderedChannels` (channel order
  fixed by `OutputPlaneProvider.streamsOf(runId, opId)`); the merged space
  is a projection, not a kernel-fact about interleaving, and the
  continuation cursor names the stream the read stopped on.
- Emits `OutputPage.bytes` RAW to stdout, then `next` cursor to stderr
  [L460-474]; the docstring justifies the raw bytes by saying re-encoding
  could corrupt anything that is not valid UTF-8. **No record shape on
  stdout** — there is no `{type, channel, operation, ordinal, text,
  base64}` JSONL envelope.
- All refusals are rendered via `renderRefusal(reason: OutputRefusal)` to
  stderr with a `console-refused: ...` prefix [L252-292]. Exit codes: 0
  for page emitted, 1 for plane-refused, 2 for usage [L459-473, matching
  §12 table].

### 2.2 Required by R4

| § | Demand | Verdict |
|---|---|---|
| §1 | view orthogonality | n/a here (`console` is a verb, not a view) |
| §3 | machine stdout clean, diagnostics on stderr | met for the BYTES use case but R4 also expects a JSONL envelope (§4) |
| §4 | `{type:"console","operation":"...","offset":8192,"channel":"merged","text":"..."}` per line | **NOT met.** stdout is the raw transcript. There is no envelope, no `type` field, no per-record prefix. |
| §5 | minimum filters: run, stage, step, kind, channel, sequence/cursor, limit/tail | **NOT met.** `console` takes only `<runId> <opId>` and `--control-dir`. There is no `--stage`, no `--step`, no `--kind`, no `--channel`, no `--grep`, no `--run`. Stage/step filtering is forced to resolve to a single `opId` upstream of the verb. |
| §7 | cursor / follow on operation byte offset | partial (cursor yes, follow NO; no `--follow`) |
| §8 | tail bounded | partial (cursor advances page-by-page; there is no `--tail-bytes` for `console` analogous to `observe`'s) |
| §11 | no total ordering lie | met for the merge (the doc at `ConsoleReadService.readRange` makes the projection explicit) |
| §12 | exit-code contract | met (`0/1/2` exactly as the table requires) |

### 2.3 Gaps

| ID | Gap | Severity | Reference |
|---|---|---|---|
| CON-2.1 | **No JSONL envelope.** Spec §4 mandates a tagged per-line record; current emits raw bytes. A consumer that wants `{type, channel, ordinal, text, base64}` cannot get it from `console`. | BLOCKER for R4 §4, MAJOR for spec consumers | `MainConsoleCli.emit` at L460-474 |
| CON-2.2 | **No `--stage` / `--step` / `--kind` / `--run` filters.** `<runId> <opId>` are the only way to scope; if the caller has a stage name and wants its console, they have to map it to an opId externally or call `observe --view console` instead. `ObservationQuery` (the closed query already used by `observe`) is the obvious place; the verb refuses to share it. | MAJOR | L417-414, L562-602 |
| CON-2.3 | **No `--follow`.** Once the durable read has caught up to `committedEnd`, there is no built-in tail. `observe --view console --follow` reads the same plane, but the dedicated verb has no analogue. R4 names `console` as one of the four verbs. | MAJOR | L417 (no flag set) |
| CON-2.4 | **No cross-page UTF-8 contract.** The read concatenates channel streams into a `ByteArrayOutputStream` and writes them. A 4 KiB page boundary that lands inside a multi-byte codepoint is re-decoded by the consumer, which is allowed to see U+FFFD on the boundary. R4 lists "contexto acotado, UTF-8 incremental" as a hard requirement. | MAJOR | `ConsoleReadService.kt:127, 222-228` |
| CON-2.5 | The parser lives OUTSIDE the unified `CliParser.parseObservation` (its own `MainConsoleCliAdmission`). Refusal messages use a `console-refused:` prefix while `CliParser` uses `Error:` style. The reader cannot tell whether `pipeline console --bogus` and `pipeline observe --bogus` are part of the same CLI just by reading the stderr text. | MINOR | `MainConsoleCliAdmission.kt:1-660` vs `CliParser.kt:22-25` for the unified surface |

### 2.4 Test coverage

- `MainConsoleCliArgumentContractTest` (HF1 in-process): only argument
  refusal rows for `--max-bytes abc/0/-3/1.5`, unknown option, third
  positional, missing value.
- `MainConsoleCliArgumentStrictnessTest`, `MainConsoleCliRequestStrictnessTest`
  (HF1): parser edge cases; they do not exercise a real `OutputPlane`.
- `ConsoleReadServiceTest` (HF1): tests `ConsoleReadService.read`/`readRange`
  with synthetic stores.
- **NO installed-distribution (`HF2` / real `installDist` JAR) test
  exercises `console`.** No `WULpr*CliConsoleTest` exists; no test
  under `v2/pipeline-application/src/test/.../cli/` matches `console`.
- No UTF-8 boundary test (`4 KiB page splits inside a 3-byte UTF-8
  character`). This is a stated PC-08 gap (`OBS_PROGRESSIVE_CONSOLE_ROADMAP.md:45`).

### 2.5 Implementation note

The cleanest path is to make `console` accept the same
`CompiledObservationQuery` (already built in `CliParser.parseObservation`)
and the same `ObservationFormat` (`text` = raw bytes, `jsonl` = envelope).
Move the parser into `CliParser.parseObservation` so `--stage`, `--step`,
`--kind`, `--channel`, `--limit`, `--tail-bytes`, `--follow`, `--format
jsonl|json` all reuse the OBS-E5 surface — closing two gaps (CON-2.1..2.3)
in one push. UTF-8 chunking is then a `StreamingUtf8Decoder` port wrapped
around the read cursor, exactly the seam OBS roadmap PC-08 names.

---

## 3. `events` — MainEventsCli.kt

**Source:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainEventsCli.kt` (380 lines)
**Parser:** ad-hoc `while (i < args.size)` loop in `main`, L97-145 — NOT
`CliParser`.
**Tests:** `MainEventsCliArgumentContractTest`,
`MainEventsCliArgumentStrictnessTest`, `MainEventsCliRefusalVisibilityTest`,
`EventPageDrainTest`, `TypedEventsCapabilityRedTest`.

### 3.1 Current behavior

- CLI surface: `pipelinek events --db <path> <runId>
  [--kind K] [--subject v1:run:ID|v1:stage:ID:N|...] [--limit N]
  [--after-cursor TOKEN] [--typed]`
  [L97-156]. No `--stage` / `--step` / `--fields` / `--tail` /
  `--context` / `--follow` / `--format jsonl|json` / `--run`.
- Two projections: `RowProjection.Envelope` (the V1 wire envelope via
  `EnvelopeCodec.encode`), `RowProjection.Typed` (the typed
  `DomainEvent` via `JsonEventLog.encodeOne`). Both hit the same store
  through `EventPageDrain` and produce the same exit code
  [L83-95, 244-326].
- `--kind` and `--subject` build `EventQuery.ByKind`/`BySubject`
  [L189-200]; the typed-emission path bypasses these for the typed
  projection only by reusing the same reader.
- Refusal reporting: per-row `evt-refusal-v1:<runId>:<seq>:<reason>:<kind>`
  on stderr plus an `evt-refusals-v1:<runId>:<count>` summary, then the
  continuation token (also stderr) [L329-352]. This is parser and verb
  authored separately; the format is NOT a typed `EventReadRefusal`
  value carried in a sealed result, only the `EventRecordRead.Undecodable`
  list emitted by `EventPageDrain`.
- Reader reach: `EventPageDrain.drain` / `drainTyped` are the two
  outcomes `Outcome.Answered` / `Outcome.Stalled`; the CLI exit contract
  is `0` for both, `2` for usage [L301-326].
- Cursor tokens: an `EventCursor` carries the `runId`, decode fails for
  a token that names a different run [L172-183].

### 3.2 Required by R4

| § | Demand | Verdict |
|---|---|---|
| §1 | view orthogonality | n/a here |
| §3 | machine stdout clean, diagnostics on stderr | met (envelopes to stdout, refusals + cursor to stderr) |
| §4 | tagged records `{type, sequence, kind, subject}` | partial. Envelope-shape (`EnvelopeCodec.encode`) is NOT the §4 record; the typed projection is `JsonEventLog.encodeOne` and has the same problem. WU-LPR-051 §6 finding F1 says "the CLI flag wiring for `--fields` is the migration target" — implies a record-shape change. |
| §5 | filters: run / stage / step / kind / channel / text / sequence-cursor / limit-tail-context | partial. `--kind` and cursor-bound limit exist; `--stage`, `--step`, `--fields`, `--context`, `--tail` are absent (see CON-2.2 analogue). |
| §6 | `--fields` projection | **NOT met.** WU-LPR-051 §6 explicitly defers this to `WU-LPR-051-FOLLOWUP-A` (CLI flag wiring). |
| §7 | `--follow --kind StepFailed` | **NOT met.** §7 calls this out as a canonical example; the verb has no `--follow`. |
| §8 | tail bounded | partial (only via `--limit N`). No `--tail N`, no `--context N`. |
| §12 | exit codes | met (`0/2` per table; `1` is not used). |

### 3.3 Gaps

| ID | Gap | Severity | Reference |
|---|---|---|---|
| EVT-3.1 | **No `--follow`.** R4 §7 example line 95 (`pipelinek events RUN --follow --kind StepFailed`) is not runnable today. Only `ObserveLanes.eventSliceOf` / `MainObserveCli.follow` carries a follow loop (`MainObserveCli.kt:299-316`). | BLOCKER for §7, MAJOR for R4 install-time use | `MainEventsCli.kt:97-156` |
| EVT-3.2 | **No `--fields` / `--context` / `--tail`.** WU-LPR-051 closed the pure `EventInspection` (`projectFields`/`contextWindow`/`tail`), but the CLI flag wiring was deferred to `WU-LPR-051-FOLLOWUP-A` (`WU_LPR_051_RECEIPT.md:8-10, 87`). | MAJOR | `WU_LPR_051_RECEIPT.md:8-10, 87` |
| EVT-3.3 | **No `--stage` / `--step` filters.** `EventQuery.ByKind`/`BySubject` exist; their stage/step/operation-numeric axes do not. The acceptance test "filter to one Step" needs at least one of these. | MAJOR | `MainEventsCli.kt:189-200` |
| EVT-3.4 | Typed refusals: stderr line `evt-refusal-v1:...` is machine-readable but the verb's return type is `Int`, not a sealed `Result<EventPage, EventReadRefusal>`. Script-branching must regex-parse stderr, which the spec calls out as "history != stdout rule" (§6 mixed shape). | MINOR | `MainEventsCli.kt:329-336` |
| EVT-3.5 | Parser lives in a separate hand-rolled `while` loop, not the unified `CliParser`. AUD-04 was a previous fix here (`MainEventsCli.kt:121-131`); the verb still routes unknown options inline. The `Error:` prefix and the prose form differ from the unified parser's messages. | MINOR | `MainEventsCli.kt:97-145` |

### 3.4 Test coverage

- `MainEventsCliArgumentContractTest` (HF1): one unknown option row,
  one extra-positional row, both pinning exit code `2` and stderr naming
  the offending token; the exit-code contract pin for `Stalled` is here
  too [L96-122].
- `MainEventsCliArgumentStrictnessTest`, `MainEventsCliRefusalVisibilityTest`:
  more HF1 rows. None of them invoke a real CLI process; all drive
  `MainEventsCli.main` with `System.setOut/setErr`.
- `EventPageDrainTest` (HF1): the reader reach is unit-tested.
- **No installed-distribution test exercises `events`** against a built
  JAR. HF2 rung is empty for `events`.
- Mutation discipline: AUD-04 / AUD-05 mutating the parser's `else ->`
  branch and `exitCodeFor` are documented
  (`MainEventsCli.kt:121-133, 274-326`).

### 3.5 Implementation note

The minimal R4 push is (a) fold the parser into
`CliParser.parseObservation`'s shared arity/recognise layer
[CliParser.kt:747-766]; (b) wire `--fields` / `--context` / `--tail` via
the closed `EventField` ADT (5 cases) already published by WU-LPR-051
(see the `inspect` sketch in §4); (c) wire `--follow` reusing
`ObserveLanes.eventSliceOf` so the event-lane follow is not duplicated
again (`MainObserveCli.kt:299-316`).

---

## 4. `inspect` — NOT IMPLEMENTED (BLOCKER)

**Source:** none. A grep for `MainInspectCli`, `class.*Inspect` and the
`"inspect"` verb yields no entry.
**Spec citations:**
- `docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md` §6 lines 77-83
  (`--fields` restricts output projection; example line 79-83).
- `docs/v2/04-adrs/ADR-0088-cli-observation-contract.md` §Decision
  line 30 (the primary bounded diagnostic surface).
- `docs/v2/01-product/LOCAL_PRODUCTION_READY.md` line 78
  (`pipelinek inspect <run> --failed --context 5 --log-tail 100 --format json`).
- `docs/v2/03-specifications/LOCAL_CICD_PRODUCT_PROFILE.md` line 59
  (`pipelinek inspect ...`).
- `docs/v2/07-uat/WU_LPR_051_RECEIPT.md` lines 27-31 ("the CLI flag
  wiring (`--fields`, `--tail`, `--context`, `--after-sequence`) is
  NOT part of this WU").

### 4.1 Required by R4

The `inspect` invocation has five semantic dimensions in the spec, not a
flat list:

```text
inputs   --control-root PATH, --db PATH, <runId>
state    --failed (boolean shorthand for outcome=failure), --kind K, --stage S, --step N
context  --context N (records around a match), --log-tail N (bytes per failed step)
shape    --fields STAGE,STEP,OUTCOME,MESSAGE,CONSOLETAIL
output   --format json|text
```

(`--context`, `--log-tail`, `--fields` come from CLI_OBSERVABILITY_SPEC
§6/§7; `--failed` from ADR-0088 §Decision line 30 and
`LOCAL_PRODUCTION_READY.md` line 78.)

### 4.2 Current behavior

- The verb has no implementation, no `MainInspectCli.kt`, no dispatcher
  entry, and no test.
- `Main.kt:149-191` dispatches `validate`, `run`, `observe`, `events`,
  `console`, `capabilities`, `sdk-info`, `credentials` and not
  `inspect` [verified at L110-188].
- Functionality is partially spread across two places:
  - `EventInspection` (per `WULpr051EventInspectionTest.kt`,
    `WU_LPR_051_RECEIPT.md` §3) defines the four pure transforms
    (`projectFields`, `contextWindow`, `tail`, `follow`) on an
    `EventInspection` value and a closed `EventField` ADT of 5 cases.
  - `CompiledObservationQuery` reuses the OBS-E5 selection on
    stage/step/kind/outcome/channel/text (`ObservationQuery.kt:106-143`).
- Neither surface has a CLI binding today. R4 needs that binding.

### 4.3 Gaps

| ID | Gap | Severity | Reference |
|---|---|---|---|
| INSP-4.1 | **No `MainInspectCli.kt` and no entry in `Main.kt`.** The verb described in §6 of the spec is unreachable. | BLOCKER | grep for `MainInspectCli` returns zero results |
| INSP-4.2 | **No admission model for `--failed` / `--context` / `--log-tail` / `--fields`.** The new flags have no entry in `FlagOption` / `ValuedOption` enums [CliParser.kt:784-842]. Required-flag-vs-conflict logic does not exist. | BLOCKER | `CliParser.kt:784-842` |
| INSP-4.3 | **No record shape with `{stage, step, outcome, message, consoleTail}`.** Spec §6 names these fields; `ObservationRecord.Event` carries `kind/sequence/...` but the `consoleTail` field is a per-Event property no current encoder produces. The closed `EventField` ADT (5 cases: `Sequence`, `Kind`, `EventId`, `Subject`, `Source`) does NOT match §6 (`stage, step, outcome, message, consoleTail` are NOT in the ADT). | MAJOR | `WU_LPR_051_RECEIPT.md` §3 |
| INSP-4.4 | **No `--context N` / `--log-tail N`.** The pure `contextWindow`/`tail` exist (`WU_LPR_051_RECEIPT.md` §3), but their CLI wiring is the explicit `WU-LPR-051-FOLLOWUP-A`. | MAJOR | `WU_LPR_051_RECEIPT.md:8-10, 27-31, 87` |
| INSP-4.5 | **No JSON document mode for `inspect`.** `--format json` is the canonical use, but the verb does not exist to emit it. `MainConsoleCli.main` only emits raw bytes; `RunObservationOutput.encode` is the bounded JSON writer but it is wired to `run`/`observe`, not `inspect`. | MAJOR | spec line 79-83 |
| INSP-4.6 | **No installed-distribution acceptance test for any of the four CLIs together.** R4 is "consolidar `observe`, `console`, `events` e `inspect`". The cross-CLI harness would show whether the four share a contract today (they currently do not — three parsers, three output shapes). | MAJOR | `WULpr010CliCharacterizationTest.kt:38-40` notes "events/credentials MISSING" but does NOT run observe, console, or inspect |

### 4.4 Test coverage

- None at the verb level. The HF0 / HF1 / HF2 stack is empty for
  `inspect`.
- Indirect evidence on supporting components: `WULpr051EventInspectionTest`
  tests the `EventField` ADT; `CompiledObservationQuery.accepts(record)`
  tests the OBS-E5 selection; both are HF1.

### 4.5 Implementation note

Reach R4 §6 by composing existing surfaces rather than writing a new
selection model:
- Selection: extend `ObservationQuery` with a `--failed` shorthand that
  unions `StepFailed`, `StageFinished(outcome=failure)` and
  `RunFinished(outcome=failure)` outcomes into `ObservedOutcome.failure`.
  Already in scope for `Outcome` parse [CliParser.kt:991-996].
- Projection: a closed ADT for `InspectField` covering `Stage`, `Step`,
  `Outcome`, `Message`, `ConsoleTail`. Distinct from `EventField` (5
  cases, WU-LPR-051). One per record.
- Tail: consume `FrameIndexedObservationOutputReader.readTail(runId, n)`
  (`ObservationOutputReader.kt:202-230`) and bind to the `StepFailed`
  records' `operation` field.
- Document mode: reuse `RunObservationOutput.encode` with
  `ObservationFormat.JSON`. Lifetime bounded by `--limit`.
- Tests: HF0 closed-ADT pins; HF1 in-process `MainInspectCli.main` cases
  for each `--failed/--context/--log-tail/--fields` combination; HF2
  installed-distribution test (the missing rung §4.6 asks for).

---

## 5. Cross-cutting gaps

| ID | Gap | Why it matters for R4 | Where |
|---|---|---|---|
| CC-1 | **Three parsers in production.** `CliParser.parseObservation` (canonical, used by `observe`); `MainConsoleCliAdmission` (private to `console`); inline `while` loop in `MainEventsCli`. Every new flag has to be added in three places; every refusal prefix differs (`Error:` vs `console-refused:`, no `evt-refused:` even though there is `evt-refusal-v1:`). AUD-04 has fixed two of them; the third (`MainEventsCli`) was fixed but still does not call `applyOption`. | R4 calls for a CONSOLIDATED CLI (`SPEC-01 PK-L7`, AGENTS.md §"consolidar"). Without one parser, the four verbs cannot share `--fields`, `--context`, `--tail`, `--follow`, `--format`. | `CliParser.kt:1-1058`; `MainConsoleCli.kt:417-662`; `MainEventsCli.kt:97-156` |
| CC-2 | **Three output codecs for "observation records".** Events: `EnvelopeCodec.encode` / `JsonEventLog.encodeOne`. Console: raw `OutputPage.bytes`. Observe: `RunObservationOutput.Stream` over `ObservationRecord.{Event,Output}`. R4 §4 expects ONE tagged-record shape: `{type, sequence, kind, subject}` for events, `{type, channel, ordinal, operation, text, base64}` for output. The codecs agree on shape today only by accident; future re-encoding would diverge them. | Spec §4 mandates a tag; the lack of a single writer is the structural defect. | `ObservationJsonLines.kt:50-135`; `MainConsoleCli.kt:460-474`; `MainEventsCli.kt:208, 254` |
| CC-3 | **No single installed-distribution harness for the four CLIs.** `WULpr010CliCharacterizationTest` runs `installDist` for `pipeline run/validate/version/doctor`, NOT for `events`, `console`, `observe` or `inspect`. `C8InstalledDistributionCanaryTest`, `Lpr011ResumeLifecycleUatTest`, `HttpInstalledUatTest`, `P3DPluginEventInstalledDistributionUatTest` exist but cover different verbs. Cross-CLI parity is not measured against the SHAs the operator publishes. | R4 wants Fabric/Jenkins/CLI equivalence (SPEC-01 PK-L7; `PAIR_RELEASE_FLOW.md`); an installed-distribution canary is the only signal that the published bytes match. | `WULpr010CliCharacterizationTest.kt:18-58` |
| CC-4 | **No CLI-surface manifest for the four observation verbs.** ADR-0088 §Decision lists `view`, `format`, `query/projection`; there is no machine-readable list of `(verb, flag, semantics, refusal cases)`. `SEMANTIC CONSTITUTION §1` mandates "Every public DSL construct is classified exactly once"; for the CLI surface the analogue is a `CLI_SURFACE_MANIFEST.md` (referenced as the canonical place for classification). A search under `docs/v2/surface/` returns `DSL_SURFACE_MANIFEST.md` only — no CLI analogue. | R4 readiness needs the manifest to enumerate one entry per flag per verb; AUD-04 / OBS-E5 have shown the audit is the cheap place to find the next bug. | `docs/v2/surface/` |
| CC-5 | **Cross-frame UTF-8 incremental decoder is missing.** PC-08 (`OBS_PROGRESSIVE_CONSOLE_ROADMAP.md:45`) names the defect; `ObservationJsonLines.decodeStrictly` uses `ByteBuffer.wrap(bytes).decode(...)` per-record and emits `null`/base64 on malformed input. A multi-byte codepoint split across a frame boundary will produce two base64 entries instead of one UTF-8 character in the next frame. | Spec §1 ("incremental UTF-8") and the `OBS-4` block both require it. None of the existing tests pin a cross-frame codepoint boundary. | `ObservationJsonLines.kt:125-131` |
| CC-6 | **Wakeup-based follow is unimplemented.** `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` OBS-2 already cites the absence. `MainObserveCli.kt:255-259` documents it: "There is no `EventsCommitted` emitter yet (OBS-D bis), so this asks the durable authority for its position rather than being told." CLI_OBSERVABILITY_SPEC §7 names "wakeups for low latency; durable read-after for recovery" as the contract. | R4 names UTF-8 incremental AND bounded context. Polling at `FOLLOW_IDLE_MILLIS = 25` (`MainObserveCli.kt:414`) is not low-latency and not aligned with the wakeup seam SPEC-01 PK-L3 requires. | `MainObserveCli.kt:299-316` |
| CC-7 | **Refusals span three vocabularies.** `ObserveRefusal` (5 cases, `MainObserveCli.kt:524-566`), `ConsoleCliRefusal` (9 cases, `MainConsoleCli.kt:343-376`), and the `evt-refusal-v1:<runId>:<seq>:...` stderr line in `MainEventsCli.kt:329-336`. No sealed `EventReadRefusal` ADT (the events store has `EventRecordRead.Undecodable`, but the verb ignores its other refusal cases). Spec §6 "history != stdout rule" is implicit; a closed-typed-result returns the seam to one shape. | Refusals that are typed values, not stderr regexes, are the same defect class the `console` ADT closed for outputs. | `MainEventsCli.kt:329-336`; `EventRecordRead.kt` |

---

## 6. Top-5 smallest units of work that would visibly advance R4

(Ranked by leverage: smallest blast radius, biggest user-visible
impact. Each is one PR-sized slice with a stated exit code and a
single highest-pinned receipt.)

### WU-R4.1 — `inspect` skeleton (BLOCKER gap)

**Scope:** introduce `MainInspectCli.kt`, dispatch in `Main.kt:149-...`,
build a typed `CompiledInspectionQuery` (subset of
`CompiledObservationQuery` plus `--failed` shorthand), bind to the pure
`EventInspection` from `WULpr051EventInspectionTest` for `--fields`,
bind to `FrameIndexedObservationOutputReader.readTail` for `--log-tail`,
bind to `RunObservationOutput.encode(ObservationFormat.JSON)` for the
JSON document, and ship `--context N` through `contextWindow`. Pure
closed-ADT for `InspectField` (`Stage`, `Step`, `Outcome`, `Message`,
`ConsoleTail`).

**Why first:** without the verb, the second-agent's first task becomes
"find what shape the spec wants", which is the work this survey already
did. One WU is enough to land the user-visible CLI; expanding it is
additive.

**Tests:** HF0 closed-ADT pin for `InspectField`; HF1 in-process
`MainInspectCli.main` driving each flag; HF2 `installDist` test
`InspectCliInstallDistCanaryTest` asserts the full byte sequence under
`/tmp/` is reproducible on the SAME JAR `pipeline run` used
(`SPEC-01 PK-L7`).

**Receipt:** `docs/v2/07-uat/R4_INSPECT_SKELETON_RECEIPT.md`.

### WU-R4.2 — Wire `events` to `CliParser.parseObservation` and add `--follow` / `--fields` / `--context` / `--tail`

**Scope:** delete the inline `while` in `MainEventsCli.main`
[L97-156] in favour of `CliParser.parseObservation` + `applyOption`
shared with `observe`. Extend `EventField`'s caller (already 5 cases,
`WU_LPR_051_RECEIPT.md` §3) with the new flag surface; implement
`--follow` reusing `ObserveLanes.eventSliceOf` so the event-lane follow
loop is in ONE place (`MainObserveCli.kt:299-316`). This also closes
AUD-04's remaining door (a future option can break across parsers
again).

**Why second:** revives the documented `WU-LPR-051-FOLLOWUP-A` and
delivers `pipelinek events RUN --follow --kind StepFailed` (§7 example).

**Tests:** HF1 reuse `MainEventsCliArgumentContractTest` mutated rows;
HF2 `EventsInstallDistFollowTest` driving the built JAR against a
synthetic `SqliteEventStore`.

**Receipt:** `docs/v2/07-uat/R4_EVENTS_CLI_RECEIPT.md` with
`WU-LPR-051-FOLLOWUP-A` supersession.

### WU-R4.3 — JSONL envelope for `console`, plus `--stage` / `--step` / `--kind` / `--follow`

**Scope:** make `console` accept the same `ObservationQuery` and
`ObservationFormat` (`text | jsonl | json`) the rest of the suite
already uses, and route its emitter through `ObservationJsonLines` (or a
new `ConsoleJsonLines` if the record semantics diverge). Delete the
private `MainConsoleCliAdmission` parser and route through
`CliParser.parseObservation` so the verb participates in the unified
option table.

**Why third:** closes `CON-2.1`, `CON-2.2`, `CON-2.3`, and CC-1 (for the
`console` verb) in one WU.

**Tests:** HF1 `MainConsoleCliJsonlEnvelopeTest`; HF2
`ConsoleInstallDistFollowTest`; HF2
`ConsoleInstallDistTailBoundaryTest` (PC-08 boundary).

**Receipt:** `docs/v2/07-uat/R4_CONSOLE_CLI_RECEIPT.md`.

### WU-R4.4 — Cross-frame incremental UTF-8 decoder

**Scope:** introduce `StreamingUtf8Decoder` port in `pipeline-output`
that consumes a `ByteArray` page, holds a half-codepoint across calls,
and emits a complete `CharSequence` to callers. Wire it around
`FrameIndexedObservationOutputReader.readTail` for the `--view console`
path and around `ConsoleReadService.read` for the JSONL envelope path
in WU-R4.3. PC-08 closes for both verbs.

**Why fourth:** unlocks the JSONL envelope promised in WU-R4.3 from
being a lossy view; the decoder is its own seam so the OBS-4 UAT rows
that name cross-frame UTF-8 can land.

**Tests:** HF0 `StreamingUtf8DecoderTest` (4 KiB input split mid-codepoint
in all positions; cross-decoder equivalence with `String(bytes, UTF_8)`
on a whole buffer); HF1 `ObserveCliUtf8AcrossFrameTest`; HF2
`ObserveCliUtf8FollowTest` against a `sh` emitter that writes a known
codepoint boundary at frame N-1 / N.

**Receipt:** `docs/v2/07-uat/R4_UTF8_INCREMENTAL_DECODER_RECEIPT.md`.

### WU-R4.5 — Cross-CLI installed-distribution canary (HF2) for the four verbs

**Scope:** a single test class `ObservabilityCliInstallDistCanaryTest`
that (a) runs `pipeline run` to populate both stores under a
`@TempDir`; (b) drives `pipeline observe`, `pipeline events`,
`pipeline console`, `pipeline inspect` against the SAME `--db` and
`--control-root`; (c) asserts the per-verb output is byte-stable for
the SAME fixture across run-rerun. This is the rung the operator
needs to declare parity between Fabric / Jenkins / CLI on a SINGLE
candidate (PAIR_RELEASE_FLOW.md §5).

**Why fifth:** it is the rung that turns R4's "consolidar" claim into a
measurable thing. Without it, every per-verb WU above is a feature
flag, not a contract.

**Tests:** a single HF2 class with one method per verb + one parity
method (each verb's bytes over the same fixture match the captured
SHA-256).

**Receipt:** `docs/v2/07-uat/R4_CLI_INSTALLED_DISTRIBUTION_CANARY_RECEIPT.md`.

> Honorable mentions (NOT in the top-5 because they have larger blast
> radius for smaller visible gain, or are valid only as part of a
> later WU): wakeup-based follow (WU-R6.x; OBS-2 territory), unified
> CLI surface manifest under `docs/v2/surface/CLI_SURFACE_MANIFEST.md`
> (touch every verb; deliverable is documentation), and a typed
> `EventReadRefusal` ADT returned by `MainEventsCli.main` (CC-7;
> touches one verb, but the user-visible win is only for failure-mode
> scripting).

---

## 7. Open questions for the operator

1. **Should `inspect` and `observe --view events --format json` be two
   names for the same wire?** ADR-0088 §Decision puts `inspect` as the
   "primary bounded diagnostic surface"; `observe` does most of it
   today via `--view events --format json --fields ...`. Conflating them
   would mean the same `--fields` projection routes through both verbs
   (one CLI flag, two entry points). Splitting them would mean the
   spec's six `--fields` (stage / step / outcome / message / consoleTail
   + ?) need their own ADT (`InspectField`), distinct from the
   `EventField` already shipped in WU-LPR-051. The decision shapes
   whether the HUD stops at five fields or grows.
2. **Is the `--run` filter expected to be a `--run` flag (and
   `<runId>` stays positional, becoming optional), or is the positional
   `<runId>` enough and `--run` is rejected?**
   `pipelinek observe <runId>` already takes the run as positional
   [MainObserveCli.kt:46-51]; `events` matches
   [MainEventsCli.kt:18]; `console` matches
   [MainConsoleCli.kt:398-400]. A `--run` flag would be a second
   authority on the same value.
3. **Should the wakeup seam be a per-R4 deliverable or move to OBS-2
   / R5?** `MainObserveCli.kt:255-259` documents that `EventsCommitted`
   does not yet exist. R4's R-letter spec asks for incremental UTF-8
   and bounded context; `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` OBS-2
   already owns wakeups. Confirming scope keeps the R4 receipts
   defensible; expanding scope dissolves the gate.
4. **Is the `--log-tail N` default-capped?** `docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md`
   §8 says "Default maximum bytes scanned for a line tail should be
   capped/configurable to protect pathological single-line output."
   The closed-ADT pin needs the cap once before declaring
   `IMPLEMENTED_PARTIAL`.

---

## 8. Authority and cross-references

- AGENTS.md (Hexagonal Architecture, Step semantics, Testing rules)
- `docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md` (R4 contract)
- `docs/v2/04-adrs/ADR-0088-cli-observation-contract.md` (view/format/query split)
- `docs/v2/03-specifications/SPEC-01-PIPELINEK-LIVE-PLANES.md` (PK-L7)
- `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-4..7)
- `docs/v2/07-uat/WU_LPR_051_RECEIPT.md` (CLI flag wiring deferred)
- `coordination/PAIR_RELEASE_FLOW.md` (cross-repo consumer parity gate)
- `docs/pipelinek-semantic-evolution/07-AGENTS-patch.md` (Semantic Constitution)

Reference SHA = HEAD `438b344b`; rebasing onto a fresh SHA does not
invalidate this audit because every claim cites a file:line, and the
SHAs of those files are themselves documented in
`v2/pipeline-application/build` outputs the harness will produce.
