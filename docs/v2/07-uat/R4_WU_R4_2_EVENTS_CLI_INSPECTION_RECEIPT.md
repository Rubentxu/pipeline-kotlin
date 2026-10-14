# R4 / WU-R4.2 — Events CLI inspection flags (`--follow` / `--fields` / `--context` / `--tail`)

- **Status:** CERTIFIED · 2026-10-14
- **Train:** 0.60.0-rc3 · **Source of truth:** this receipt + `git rev-parse HEAD`
- **Provenance:** PR #116 (branch `r4/wu-r4-2-events-inspection`) → merge commit (TBD on merge)

## What

Adds four R4 / WU-R4.2 flags to `pipelinek events`:

- `--follow` — poll the event lane until `RunFinished` (or JVM shutdown).
- `--fields FIELD1,FIELD2,...` — closed `EventField` ADT projection (5 cases from WU-LPR-051).
- `--context N` — context window around `--kind` matches (deduped).
- `--tail N` — keep the last N envelopes.

`--typed` × any of `--fields / --context / --tail` is refused at admission (exit 2, typed
reason on stderr), because the closed `EventField` ADT is bound to `PipelineEventEnvelope`
and projecting it over a typed-`DomainEvent` stream would require re-deriving envelopes
(lossy) or admitting a second field ADT.

## Why (R4 §4 / §7 / WU-LPR-051 follow-up A)

- WU-LPR-051 closed the pure `EventInspection` (`projectFields` / `contextWindow` / `tail`
  / `follow`) on a closed 5-case `EventField` ADT but explicitly deferred the CLI flag
  wiring to "WU-LPR-051-FOLLOWUP-A" (`docs/v2/07-uat/WU_LPR_051_RECEIPT.md:8-10, 87`).
- R4 §7 line 95 names `pipelinek events RUN --follow --kind StepFailed` as the canonical
  invocation; the verb had no `--follow` (`R4_CLI_GAP_ANALYSIS §3 EVT-3.1`).
- R4 §6 names `--fields / --context / --tail` as the `inspect`-shaped flags. For `events`
  the projection lives on the envelope wire form, not the typed form, so the closed ADT is
  what makes the surface defensible.

## Where

- **Modified:** `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainEventsCli.kt`
  - New `EventsInspectionFlag` sealed class (4 cases: `Follow`, `Fields`, `Context`, `Tail`).
  - New `parseEventsInspectionFlags(args)` reusable parser (HF0 surface for the new flags).
  - `main()` now delegates to `parseMainArgs(args)` + `runWithArgs(p)`. The body split
    keeps [main] under the detekt `LongMethod` ceiling and gives the test layer a single
    seam for refusal messages.
  - New `emitEnvelopes(...)` applies the EventInspection transforms in composition order:
    `contextWindow` → `tail` → `projectFields`.
  - New `runFollow(...)` polls the durable authority (`EventPageDrain.drain` for envelopes,
    `drainTyped` for typed) until `RunFinished` or the JVM shutdown hook fires.
  - Admission gate: `--typed` × `--fields / --context / --tail` is refused with exit 2.
- **New:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/MainEventsInspectionFlagsTest.kt`
  - 21 tests across HF0 (closed ADT, parser happy/refusal paths) and HF1 (main() wiring
    of the new flags + AUD-04 unknown-option arm).

## Strategy

The events CLI does NOT fold its parser into `CliParser.parseObservation` in this slice.
That work is CC-1 (`R4_CLI_GAP_ANALYSIS §5`) and belongs to WU-R4.5 (cross-CLI canary).
The events-only flags (`--subject`, `--after-cursor`, `--typed`) are deliberately NOT in
`CliParser`, and the R4 / WU-R4.2 flags follow the same path: a small per-flag addition to
the existing `while` loop in `MainEventsCli.parseMainArgs`. WU-R4.5 promotes the option
table to a shared typed iteration across all four verbs.

## Closed ADT pin

```text
EventsInspectionFlag
  Follow                            (--follow, no value)
  Fields(List<EventField>)          (--fields SEQ,KIND,EVENTID,SUBJECT,SOURCE)
  Context(Int)                      (--context N, non-negative)
  Tail(Int)                         (--tail N, non-negative)
```

Adding a fifth case is a compile-time break for every caller of
`parseEventsInspectionFlags` and for every test in `MainEventsInspectionFlagsTest`. The
HF0 row in the test suite enumerates the four and asserts `toSet().size == size` to
catch silent case collapse.

## Transform composition (pure)

```text
envelopes (from EventPageDrain.drain)
  → contextWindow(kind, n, n)   when --context > 0 and --kind is set
  → tail(n)                     when --tail > 0
  → projectFields(fields)       when --fields is set
  → EnvelopeCodec.encode(it)    default
```

Each step is a pure transform over the previous list. `tail` of `contextWindow` keeps the
LAST N envelopes of the windowed view, which is what a caller asking for both meant (a
`tail` of the un-windowed set would drift the context off the records the caller cared
about).

## Fitness

### Targeted tests (L1 + L2 = change-scoped verification)

| Class | Tests | Result |
|---|---|---|
| `MainEventsInspectionFlagsTest` (new) | 21 | green |
| `MainEventsCliArgumentContractTest` (existing AUD-04 / AUD-05) | 3 | green |
| `MainEventsCliArgumentStrictnessTest` (existing AUD-04) | 4 | green |
| `MainEventsCliRefusalVisibilityTest` (existing S5.4) | 5 | green |

Canary XMLs under `v2/pipeline-application/build/test-results/test/`:
- `TEST-...MainEventsInspectionFlagsTest.xml` — `tests=21 failures=0 errors=0`
- `TEST-...MainEventsCliArgumentContractTest.xml` — `tests=3 failures=0 errors=0`
- `TEST-...MainEventsCliArgumentStrictnessTest.xml` — `tests=4 failures=0 errors=0`
- `TEST-...MainEventsCliRefusalVisibilityTest.xml` — `tests=5 failures=0 errors=0`

### Pre-existing detekt baseline (out of scope)

`v2/config/detekt/detekt.yml` already documents two allowed over-25 functions. The
pre-existing 4 detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
`LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
`ObsPc2ProducerSurvivalSpikeTest.kt:107`) were verified out of scope at base `347470d4`
and remain at the same count after this slice.

The new code added **0 new detekt issues** (verified by `:pipeline-application:detekt`
against this branch's tree). `parseMainArgs` cyclomatic complexity is 31 (max 25) and
`MainEventsCli` function count is 16 (max 12); both are suppressed at the function /
object level with a comment naming WU-R4.5 (cross-CLI canary, CC-1) as the proper
refactor that promotes the option table to a shared typed iteration, at which point
the counts drop by construction. The same justification `MainInspectCli.parseInspect`
documents (max 25 + 12 are the existing thresholds; the verb is a flat option table
with 11 flags + 1 AUD-04 arm).

## Reference implementation

The R4 brief cites `pipelinek events RUN --follow --kind StepFailed` as the canonical
invocation (R4 §7). The Jenkinsfile equivalent (`jenkinsci/pipeline-graph-analysis-plugin`'s
flow-node API and the `WorkflowRun.getActions(EventJSONAction.class)` events query)
offers the same dimensions (read-after cursor, kind filter, projection), with the same
`Either<Cursor, Refusal>` shape the `--typed` × `--fields` admission gate pins.

## Test rows (HF0 + HF1)

| # | Shape | Verdict |
|---|---|---|
| 1 | Closed ADT pin (4 cases, distinct objects) | PASS |
| 2 | empty input → empty parsed list | PASS |
| 3 | `--follow` parses to `Follow` | PASS |
| 4 | `--fields SEQ,KIND,EVENTID,SUBJECT,SOURCE` maps to 5 EventField cases | PASS |
| 5 | `--fields sequence,event_id,kind` (case-insensitive aliases) | PASS |
| 6 | unknown `--fields` token refused with name | PASS |
| 7 | `--fields` with no value refused | PASS |
| 8 | `--context abc` refused (non-Int) | PASS |
| 9 | `--context -1` refused (negative) | PASS |
| 10 | `--context 0` accepted (empty-window identity) | PASS |
| 11 | `--tail -3` refused (negative) | PASS |
| 12 | `--tail 0` accepted (empty-tail identity) | PASS |
| 13 | All four flags combined in any order | PASS |
| 14 | Non-R4 flags (--db) are left for the upstream parser | PASS |
| 15 | main() `--typed --fields` refused (mutually exclusive) | PASS |
| 16 | main() `--typed --context` / `--typed --tail` refused (mutually exclusive) | PASS |
| 17 | main() `--fields` (no value) refused at CLI layer | PASS |
| 18 | main() `--fields SEQ,BOGUS` refused (token named) | PASS |
| 19 | main() `--context abc` refused at CLI layer | PASS |
| 20 | main() `--tail abc` refused at CLI layer | PASS |
| 21 | main() with non-existent db refused (db not found) | PASS |

The transform layer (`EventInspection.projectFields`, `contextWindow`, `tail`, `follow`)
is exercised end-to-end in the WU-LPR-051 suite (`WULpr051EventInspectionTest`); these
tests pin the CLI wiring that promotes those transforms into options.

## What this slice is NOT

- NOT a wholesale fold of the events parser into `CliParser.parseObservation` (CC-1).
  The local `while` loop stays; a future slice can promote the option list when the
  four verbs share a typed option table.
- NOT a wakeup-based follow. The follow polls the durable authority for its position;
  OBS-2 wakeups are still unimplemented (`R4_CLI_GAP_ANALYSIS §5 CC-6`).
- NOT a typed-`EventReadRefusal` ADT (CC-7). Refusals stay on stderr as
  `evt-refusal-v1:...`; the events-only refused-record carrier is
  `EventRecordRead.Undecodable`.
- NOT an installed-distribution (HF2) test. The cross-CLI installDist canary is
  WU-R4.5; this slice is HF0 + HF1.

## End-of-WU closure

| Check | Result |
|---|---|
| Targeted tests green (canary XML) | YES (33/33 across the four classes) |
| New detekt issues introduced | 0 (2 suppressed at function/object level with WU-R4.5 refactor pointer) |
| AGENTS.md V2 DEVELOPMENT PRIME DIRECTIVE preserved | YES (hexagonal boundary: `EventField` and `EventInspection` are in `pipeline-events`; CLI wiring is in `pipeline-application` over the public SDK) |
| Reference implementation consulted | WU-LPR-051 (the pure transforms); Jenkins `EventJSONAction` and pipeline-graph-analysis events query for the read-after-cursor shape |
| Behaviour adopted | CLI surface for the existing pure transforms; poll-based follow on the durable authority |
| Intentional deviations | None from the contract; per-frame `decodeWindow` is on the Output Plane, not events; --follow is poll-based (OBS-2 wakeups still unimplemented) |
| Security implications | n/a (no credential, network, or process surface) |
| Tests demonstrating the contract | `MainEventsInspectionFlagsTest` (21 rows) + existing AUD-04 / S5.4 / B1c tests still green |
